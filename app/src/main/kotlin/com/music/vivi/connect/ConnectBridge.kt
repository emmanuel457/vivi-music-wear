/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import android.content.Context
import androidx.media3.common.Player
import androidx.datastore.preferences.core.edit
import androidx.media3.common.util.UnstableApi
import com.music.innertube.YouTube
import com.music.vivi.constants.AccountChannelHandleKey
import com.music.vivi.constants.AccountEmailKey
import com.music.vivi.constants.AccountNameKey
import com.music.vivi.constants.DataSyncIdKey
import com.music.vivi.constants.InnerTubeCookieKey
import com.music.vivi.playback.queues.ListQueue
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.getAsync
import com.music.vivi.wear.WearBridge
import com.music.vivi.wear.toMediaItem
import com.music.vivi.wearsync.NowPlayingState
import com.music.vivi.wearsync.PlayTracksCommand
import com.music.vivi.wearsync.RepeatCommand
import com.music.vivi.wearsync.SeekCommand
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the [ConnectManager] for this process and joins it to playback.
 *
 * Deliberately parallel to [WearBridge] rather than folded into it: the watch
 * link is a Google-managed, always-paired, one-peer channel, while Connect is a
 * self-managed many-peer LAN mesh. Their lifecycles and failure modes have
 * nothing in common — only the payloads are shared.
 */
@UnstableApi
object ConnectBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var manager: ConnectManager? = null

    /** What a peer device is playing, for the picker and remote-control UI. */
    fun remoteState(): NowPlayingState = manager?.remoteState?.value ?: NowPlayingState.IDLE

    fun devices() = manager?.devices

    fun isRunning() = manager?.running?.value == true

    /** Observable form of [isRunning], so the picker reflects a late start. */
    fun runningState(): StateFlow<Boolean> = manager?.running ?: MutableStateFlow(false)

    /**
     * Starts Connect and keeps it started.
     *
     * Called from [com.music.vivi.App], not from MusicService: a device being
     * used purely as a remote never starts playback, so tying the lifecycle to
     * the player meant a tablet never advertised itself and never appeared to
     * anyone.
     */
    fun start(context: Context) {
        if (manager != null) return
        val app = context.applicationContext
        val instance = ConnectManager(app)

        instance.snapshotProvider = { WearBridge.snapshot() }
        instance.onCommand = { path, payload -> execute(path, payload) }

        manager = instance

        scope.launch {
            // Sign-in can happen long after launch, and the identity fields are
            // populated lazily, so poll until one appears rather than giving up
            // on the single reading available at startup.
            while (true) {
                val identity = resolveIdentity(app)
                if (identity != null) {
                    _identityMissing.value = false
                    instance.start(identity)
                    return@launch
                }
                _identityMissing.value = true
                delay(IDENTITY_RETRY_MS)
            }
        }
    }

    private val _identityMissing = MutableStateFlow(true)

    /** True when Connect is idle purely because no account identity resolved. */
    val identityMissing: StateFlow<Boolean> = _identityMissing.asStateFlow()

    /**
     * Finds a value that is identical on every device signed into this account.
     *
     * `AccountEmailKey` alone was not enough: it is only ever written when the
     * user opens Account settings, so a perfectly signed-in device can have it
     * blank — which silently disabled Connect and also showed up as a bare
     * "Signed in as" on the watch.
     */
    private suspend fun resolveIdentity(app: Context): String? {
        val store = app.dataStore

        store.getAsync(AccountEmailKey)?.takeIf { it.isNotBlank() }?.let { return it }
        store.getAsync(AccountChannelHandleKey)?.takeIf { it.isNotBlank() }?.let { return it }

        // dataSyncId is "<account>||<session>"; only the leading segment is
        // stable across devices, so the session half must be dropped.
        store.getAsync(DataSyncIdKey)
            ?.substringBefore("||")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Nothing cached. Ask YouTube directly, which is authoritative and needs
        // only the cookie we already have.
        if (!store.getAsync(InnerTubeCookieKey).isNullOrBlank()) {
            YouTube.accountInfo().getOrNull()?.let { info ->
                val identity = info.email ?: info.channelHandle ?: info.name
                if (identity.isNotBlank()) {
                    // Cache it so the next launch resolves instantly and the
                    // account screens stop showing blanks.
                    runCatching {
                        store.edit { settings ->
                            settings[AccountNameKey] = info.name
                            info.email?.let { settings[AccountEmailKey] = it }
                            info.channelHandle?.let { settings[AccountChannelHandleKey] = it }
                        }
                    }
                    return identity
                }
            }
        }
        return null
    }

    private const val IDENTITY_RETRY_MS = 15_000L

    fun stop() {
        manager?.stop()
        manager = null
    }

    /** Called from MusicService's event hook, alongside the watch publish. */
    fun onPlaybackStateChanged(state: NowPlayingState) {
        manager?.broadcastState(state)
    }

    /** Sends a transport command to the peer currently holding playback. */
    fun sendCommand(path: String, payload: ByteArray = ByteArray(0)) {
        manager?.sendToActivePeer(path, payload)
    }

    /**
     * Applies a command that arrived from a peer.
     *
     * Mirrors the watch path in [com.music.vivi.wear.PhoneWearListenerService];
     * both act on the same live player through [WearBridge.musicService].
     */
    private fun execute(path: String, payload: ByteArray) {
        when (path) {
            SyncPaths.CMD_TOGGLE -> onPlayer { if (it.isPlaying) it.pause() else it.play() }
            SyncPaths.CMD_PLAY -> onPlayer { it.play() }
            SyncPaths.CMD_PAUSE -> onPlayer { it.pause() }
            SyncPaths.CMD_NEXT -> onPlayer { it.seekToNextMediaItem() }
            SyncPaths.CMD_PREVIOUS -> onPlayer { it.seekToPreviousMediaItem() }
            SyncPaths.CMD_TOGGLE_SHUFFLE -> onPlayer { it.shuffleModeEnabled = !it.shuffleModeEnabled }

            SyncPaths.CMD_SEEK -> SyncCodec.decodeOrNull<SeekCommand>(payload)
                ?.let { command -> onPlayer { it.seekTo(command.positionMs) } }

            SyncPaths.CMD_SET_REPEAT -> SyncCodec.decodeOrNull<RepeatCommand>(payload)
                ?.let { command -> onPlayer { it.repeatMode = command.mode } }

            SyncPaths.CMD_PLAY_TRACKS -> SyncCodec.decodeOrNull<PlayTracksCommand>(payload)
                ?.let { playTracks(it) }

            // A peer took over. Stop here so two devices in the same room are
            // not playing the same song a half-second apart.
            SyncPaths.NOTIFY_WATCH_PLAYING -> onPlayer { it.pause() }

            else -> Timber.d("Ignoring unknown Connect command %s", path)
        }
    }

    private fun playTracks(command: PlayTracksCommand) {
        if (command.tracks.isEmpty()) return
        val service = WearBridge.musicService() ?: run {
            Timber.w("Connect asked us to play with no MusicService running")
            return
        }
        scope.launch(Dispatchers.Main) {
            service.playQueue(
                ListQueue(
                    title = command.queueTitle,
                    items = command.tracks.map { it.toMediaItem() },
                    startIndex = command.startIndex.coerceIn(0, command.tracks.lastIndex),
                )
            )
        }
    }

    private fun onPlayer(block: (Player) -> Unit) {
        val service = WearBridge.musicService() ?: return
        scope.launch {
            withContext(Dispatchers.Main) {
                runCatching { block(service.player) }
                    .onFailure { Timber.w(it, "Connect command failed") }
            }
            WearBridge.onPlayerEvents(force = true)
        }
    }
}
