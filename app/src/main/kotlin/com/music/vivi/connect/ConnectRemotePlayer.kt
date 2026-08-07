/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.vivi.wearsync.NowPlayingState
import com.music.vivi.wearsync.SeekCommand
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncPaths

/**
 * A [Player] whose audio lives on another device.
 *
 * This is what makes Connect feel like Spotify rather than like a remote
 * control app. Swapping this into the existing MediaSession means the
 * miniplayer, the Now Playing screen, the notification and the lockscreen
 * controls all start showing and driving the *other* device without a single
 * change to any of them — they already read from the session, and the session
 * no longer cares where the audio physically comes out.
 *
 * Only state and commands cross the wire; nothing is decoded here.
 */
@UnstableApi
class ConnectRemotePlayer(
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {

    @Volatile
    private var remote: NowPlayingState = NowPlayingState.IDLE

    /** Pushes a fresh snapshot from the peer and republishes to listeners. */
    fun update(state: NowPlayingState) {
        remote = state
        invalidateState()
    }

    override fun getState(): State {
        val snapshot = remote
        val track = snapshot.track

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
            )
            .apply {
                // Advertised conditionally so the UI greys out skip buttons that
                // would do nothing, exactly as it does for local playback.
                if (snapshot.canSkipNext) {
                    add(Player.COMMAND_SEEK_TO_NEXT)
                    add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
                if (snapshot.canSkipPrevious) {
                    add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
            }
            .build()

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(
                snapshot.isPlaying,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setPlaybackState(if (track == null) Player.STATE_IDLE else Player.STATE_READY)
            .setShuffleModeEnabled(snapshot.shuffle)
            .setRepeatMode(snapshot.repeatMode)

        if (track != null) {
            builder.setPlaylist(
                ImmutableList.of(
                    MediaItemData.Builder(track.id)
                        .setMediaItem(
                            MediaItem.Builder()
                                .setMediaId(track.id)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(track.title)
                                        .setArtist(track.artist)
                                        .setAlbumTitle(track.album)
                                        .setArtworkUri(track.thumbnailUrl?.toUri())
                                        .build()
                                )
                                .build()
                        )
                        .setDurationUs(
                            if (snapshot.durationMs > 0) snapshot.durationMs * 1000 else C_TIME_UNSET
                        )
                        .build()
                )
            )
            builder.setCurrentMediaItemIndex(0)
            // Extrapolating rather than a fixed value: the peer only publishes on
            // state changes, so a static position would freeze the progress bar
            // between songs.
            builder.setContentPositionMs(
                PositionSupplier.getExtrapolating(
                    snapshot.positionMs.coerceAtLeast(0L),
                    if (snapshot.isPlaying) 1f else 0f,
                )
            )
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        ConnectBridge.sendCommand(
            if (playWhenReady) SyncPaths.CMD_PLAY else SyncPaths.CMD_PAUSE
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                ConnectBridge.sendCommand(SyncPaths.CMD_NEXT)

            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                ConnectBridge.sendCommand(SyncPaths.CMD_PREVIOUS)

            else -> ConnectBridge.sendCommand(
                SyncPaths.CMD_SEEK,
                SyncCodec.encode(SeekCommand(positionMs)),
            )
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        ConnectBridge.sendCommand(SyncPaths.CMD_TOGGLE_SHUFFLE)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        ConnectBridge.sendCommand(
            SyncPaths.CMD_SET_REPEAT,
            SyncCodec.encode(com.music.vivi.wearsync.RepeatCommand(repeatMode)),
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        ConnectBridge.sendCommand(SyncPaths.CMD_PAUSE)
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val C_TIME_UNSET = androidx.media3.common.C.TIME_UNSET
    }
}
