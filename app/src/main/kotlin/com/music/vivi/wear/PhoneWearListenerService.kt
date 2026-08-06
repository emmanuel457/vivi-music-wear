/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear

import android.content.Intent
import android.media.AudioManager
import androidx.core.content.getSystemService
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.music.vivi.db.MusicDatabase
import com.music.vivi.playback.queues.ListQueue
import com.music.vivi.wearsync.LikeCommand
import com.music.vivi.wearsync.PlayTracksCommand
import com.music.vivi.wearsync.RepeatCommand
import com.music.vivi.wearsync.SeekCommand
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncPaths
import com.music.vivi.wearsync.VolumeCommand
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles everything the watch sends.
 *
 * Started by the system per message, so it must not assume any of the app's
 * long-lived state exists — [WearBridge] is initialised defensively and every
 * player command tolerates a null service.
 */
@UnstableApi
@AndroidEntryPoint
class PhoneWearListenerService : WearableListenerService() {

    @Inject
    lateinit var database: MusicDatabase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        WearBridge.init(applicationContext)
    }

    override fun onMessageReceived(event: MessageEvent) {
        Timber.d("Watch -> phone: %s", event.path)
        when (event.path) {
            SyncPaths.REQ_STATE -> scope.launch { WearBridge.publishNowPlaying() }

            SyncPaths.REQ_LIBRARY -> scope.launch {
                WearLibraryPublisher(applicationContext, database).publishTo(event.sourceNodeId)
            }

            // Never honoured silently: this releases account cookies, so it goes
            // through a confirmation the user has to see and accept.
            SyncPaths.REQ_AUTH -> startAuthConfirmation(event.sourceNodeId)

            SyncPaths.CMD_TOGGLE -> onPlayer { if (it.isPlaying) it.pause() else it.play() }
            SyncPaths.CMD_PLAY -> onPlayer { it.play() }
            SyncPaths.CMD_PAUSE -> onPlayer { it.pause() }
            SyncPaths.CMD_NEXT -> onPlayer { it.seekToNextMediaItem() }
            SyncPaths.CMD_PREVIOUS -> onPlayer { it.seekToPreviousMediaItem() }
            SyncPaths.CMD_TOGGLE_SHUFFLE -> onPlayer { it.shuffleModeEnabled = !it.shuffleModeEnabled }

            SyncPaths.CMD_SEEK -> {
                val command = SyncCodec.decodeOrNull<SeekCommand>(event.data) ?: return
                onPlayer { it.seekTo(command.positionMs) }
            }

            SyncPaths.CMD_SET_REPEAT -> {
                val command = SyncCodec.decodeOrNull<RepeatCommand>(event.data) ?: return
                onPlayer { it.repeatMode = command.mode }
            }

            SyncPaths.CMD_TOGGLE_LIKE -> {
                val command = SyncCodec.decodeOrNull<LikeCommand>(event.data) ?: return
                scope.launch { toggleLike(command) }
            }

            SyncPaths.CMD_VOLUME -> {
                val command = SyncCodec.decodeOrNull<VolumeCommand>(event.data) ?: return
                adjustVolume(command.steps)
            }

            SyncPaths.CMD_PLAY_TRACKS -> {
                val command = SyncCodec.decodeOrNull<PlayTracksCommand>(event.data) ?: return
                playFromWatch(command)
            }

            // The watch started playing locally. Stop here so the user isn't
            // hearing the same song out of two devices.
            SyncPaths.NOTIFY_WATCH_PLAYING -> onPlayer { it.pause() }

            // The watch released playback. Push fresh state so it can fall back
            // to mirroring this phone instead of showing an empty screen.
            SyncPaths.NOTIFY_WATCH_STOPPED -> scope.launch { WearBridge.publishNowPlaying() }
        }
    }

    private fun playFromWatch(command: PlayTracksCommand) {
        if (command.tracks.isEmpty()) return
        val service = WearBridge.musicService() ?: run {
            Timber.w("CMD_PLAY_TRACKS arrived with no MusicService running")
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

    private suspend fun toggleLike(command: LikeCommand) {
        val song = runCatching { database.song(command.trackId).first() }.getOrNull() ?: return
        // SongEntity.toggleLike() also fires the YouTube like/unlike, so the
        // change lands on the account and not just this phone's database.
        database.query { update(song.song.toggleLike()) }
        WearBridge.onPlayerEvents(force = true)
    }

    private fun adjustVolume(steps: Int) {
        val audioManager = getSystemService<AudioManager>() ?: return
        val direction = if (steps >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps).coerceAtMost(MAX_VOLUME_STEPS)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
        WearBridge.onPlayerEvents(force = true)
    }

    private fun startAuthConfirmation(nodeId: String) {
        val intent = Intent(this, WearAuthConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(WearAuthConfirmActivity.EXTRA_NODE_ID, nodeId)
        }
        runCatching { startActivity(intent) }
            .onFailure { Timber.w(it, "Could not show the account handoff prompt") }
    }

    /** Runs [block] against the live player on the main thread, if there is one. */
    private fun onPlayer(block: (androidx.media3.common.Player) -> Unit) {
        val service = WearBridge.musicService() ?: return
        scope.launch {
            withContext(Dispatchers.Main) {
                runCatching { block(service.player) }
                    .onFailure { Timber.w(it, "Watch command failed") }
            }
            WearBridge.onPlayerEvents(force = true)
        }
    }

    private companion object {
        const val MAX_VOLUME_STEPS = 5
    }
}
