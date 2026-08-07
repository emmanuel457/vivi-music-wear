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
import com.music.vivi.constants.ConnectRelayEnabledKey
import com.music.vivi.constants.ConnectRelayRoomKey
import com.music.vivi.constants.ListenTogetherServerUrlKey
import com.music.vivi.listentogether.ListenTogetherServers
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
    fun remoteState(): NowPlayingState = _remoteState.value

    private val _remoteState = MutableStateFlow(NowPlayingState.IDLE)

    /**
     * Peer playback, as a flow.
     *
     * Kept here rather than read off [ConnectManager] so it survives the manager
     * being null before startup and stays a single source whether the frame
     * arrived over the LAN or the relay.
     */
    val remoteStateFlow: StateFlow<NowPlayingState> = _remoteState.asStateFlow()

    private val _remoteOwnsPlayback = MutableStateFlow(false)

    /**
     * True when a peer holds the audio and this device does not. Drives whether
     * the MediaSession is backed by the local player or by the remote one.
     */
    val remoteOwnsPlayback: StateFlow<Boolean> = _remoteOwnsPlayback.asStateFlow()

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

        // Funnel LAN frames into the single remote-state flow, and recompute who
        // owns playback. "A peer is playing and we are not" is the whole
        // condition for handing our MediaSession to the remote player.
        scope.launch {
            instance.remoteState.collect { state ->
                _remoteState.value = state
                recomputeOwnership()
            }
        }

        scope.launch {
            // Sign-in can happen long after launch, and the identity fields are
            // populated lazily, so poll until one appears rather than giving up
            // on the single reading available at startup.
            while (true) {
                val identities = resolveIdentities(app)
                if (identities.isNotEmpty()) {
                    _identityMissing.value = false
                    instance.start(identities)
                    return@launch
                }
                _identityMissing.value = true
                delay(IDENTITY_RETRY_MS)
            }
        }

        restoreRelay(app)
    }

    /** Remembers the room so a rejoin needs no code, and reflects host/guest. */
    private fun persistRoom(app: Context, code: String?) {
        scope.launch {
            app.dataStore.edit { settings ->
                if (code.isNullOrBlank()) settings.remove(ConnectRelayRoomKey)
                else settings[ConnectRelayRoomKey] = code
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
    private suspend fun resolveIdentities(app: Context): List<String?> {
        val store = app.dataStore
        val identities = mutableListOf<String?>()

        identities += store.getAsync(AccountEmailKey)
        identities += store.getAsync(AccountChannelHandleKey)

        // dataSyncId is "<account>||<session>"; only the leading segment is
        // stable across devices, so the session half must be dropped.
        identities += store.getAsync(DataSyncIdKey)?.substringBefore("||")

        // Always ask YouTube too, not only when the cache is empty. Two devices
        // can have different subsets of these fields cached, and returning just
        // the first hit meant they hashed different strings and silently refused
        // each other. Gathering everything and matching on any overlap fixes it.
        if (!store.getAsync(InnerTubeCookieKey).isNullOrBlank()) {
            YouTube.accountInfo().getOrNull()?.let { info ->
                identities += info.email
                identities += info.channelHandle
                identities += info.name
                // Cache it so later launches resolve without a network call and
                // the account screens stop showing blanks.
                runCatching {
                    store.edit { settings ->
                        settings[AccountNameKey] = info.name
                        info.email?.let { settings[AccountEmailKey] = it }
                        info.channelHandle?.let { settings[AccountChannelHandleKey] = it }
                    }
                }
            }
        }
        return identities.filterNot { it.isNullOrBlank() }
    }

    /** Discovery diagnostics for the picker. */
    fun status(): StateFlow<ConnectStatus> =
        manager?.status ?: MutableStateFlow(ConnectStatus())

    // ── Relay fallback ───────────────────────────────────────────────────────

    private val relay = ConnectRelay()

    val relayState: StateFlow<RelayState> get() = relay.state

    /** Pairing code to type into another device; null until hosting/joined. */
    val relayRoomCode: StateFlow<String?> get() = relay.roomCode

    /**
     * Brings the relay up. Off unless the user turns it on: this is the only
     * part of Vivi Connect that sends anything to a server the user does not
     * control.
     *
     * @param joinCode null to host, or a code shown by another device.
     */
    fun startRelay(context: Context, joinCode: String?) {
        val app = context.applicationContext
        scope.launch {
            val identities = resolveIdentities(app)
            val prints = ConnectProtocol.fingerprints(identities)
            if (prints.isEmpty()) {
                Timber.w("Relay needs a signed-in account to authenticate devices")
                return@launch
            }
            relay.fingerprints = prints
            relay.onFrame = { frame ->
                // Same dispatch as the LAN transport: state updates the mirror,
                // everything else acts on this device's player.
                if (frame.path == SyncPaths.STATE_NOW_PLAYING) {
                    SyncCodec.decodeOrNull<NowPlayingState>(frame.payloadBytes())?.let {
                        manager?.applyRemoteState(it)
                        // Also fed directly, so relay-only pairs (no LAN peer)
                        // still drive the session swap.
                        _remoteState.value = it
                        recomputeOwnership()
                    }
                } else {
                    execute(frame.path, frame.payloadBytes())
                }
            }

            val serverUrl = app.dataStore.getAsync(ListenTogetherServerUrlKey)
                ?.takeIf { it.isNotBlank() }
                ?: ListenTogetherServers.defaultServerUrl

            // The username doubles as proof of account: the host approves a join
            // only when it recognises the fingerprint embedded here.
            val username = "vivi-${prints.first()}"

            app.dataStore.edit { it[ConnectRelayEnabledKey] = true }
            relay.start(serverUrl, username, joinCode)

            // Persist whatever room we end up in, host or guest, so the next
            // launch rejoins without the user finding the code again.
            scope.launch {
                relay.roomCode.collect { code -> persistRoom(app, code) }
            }
        }
    }

    fun stopRelay(context: Context) {
        relay.stop()
        scope.launch {
            context.applicationContext.dataStore.edit { it[ConnectRelayEnabledKey] = false }
        }
    }

    /** Re-establishes the relay on launch if the user previously enabled it. */
    private fun restoreRelay(app: Context) {
        scope.launch {
            if (app.dataStore.getAsync(ConnectRelayEnabledKey) != true) return@launch
            val savedRoom = app.dataStore.getAsync(ConnectRelayRoomKey)
            Timber.i("Restoring relay (room=%s)", savedRoom ?: "host")
            startRelay(app, savedRoom)
        }
    }

    /** Broadcasts state over the relay too, when it is up. */
    private fun relayBroadcast(state: NowPlayingState) {
        relay.sendFrame(relayFrame(SyncPaths.STATE_NOW_PLAYING, SyncCodec.encode(state)))
    }

    private const val IDENTITY_RETRY_MS = 15_000L

    fun stop() {
        manager?.stop()
        manager = null
    }

    /** Called from MusicService's event hook, alongside the watch publish. */
    fun onPlaybackStateChanged(state: NowPlayingState) {
        manager?.broadcastState(state)
        relayBroadcast(state)
        // Local playback starting is what takes ownership back from a peer.
        localOwnsPlayback = state.phonePlaybackActive
        recomputeOwnership()
    }

    @Volatile
    private var localOwnsPlayback = false

    private fun recomputeOwnership() {
        val remote = _remoteState.value
        _remoteOwnsPlayback.value = !localOwnsPlayback &&
            remote.phonePlaybackActive &&
            remote.track != null
    }

    /** Sends a transport command to the peer currently holding playback. */
    fun sendCommand(path: String, payload: ByteArray = ByteArray(0)) {
        manager?.sendToActivePeer(path, payload)
        // Sent over both transports. A device reachable on the LAN and via the
        // relay would otherwise miss the command whenever the LAN link is the
        // one that happens to be down.
        relay.sendFrame(relayFrame(path, payload))
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
