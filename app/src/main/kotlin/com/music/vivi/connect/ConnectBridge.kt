/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.music.vivi.constants.AccountEmailKey
import com.music.vivi.constants.AccountNameKey
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

    fun start(context: Context) {
        if (manager != null) return
        val app = context.applicationContext
        val instance = ConnectManager(app)

        instance.snapshotProvider = { WearBridge.snapshot() }
        instance.onCommand = { path, payload -> execute(path, payload) }

        manager = instance

        scope.launch {
            // Peers authenticate against a value only devices on the same
            // account share, so Connect cannot start until we know who is
            // signed in.
            val identity = app.dataStore.getAsync(AccountEmailKey)
                ?: app.dataStore.getAsync(AccountNameKey)
            instance.start(identity)
        }
    }

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
