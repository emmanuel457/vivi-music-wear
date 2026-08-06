/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.music.vivi.wear.data.PhoneLink
import com.music.vivi.wear.data.PlaybackRoute
import com.music.vivi.wear.data.WearPrefs
import com.music.vivi.wearsync.LikeCommand
import com.music.vivi.wearsync.PlayTracksCommand
import com.music.vivi.wearsync.RepeatCommand
import com.music.vivi.wearsync.SeekCommand
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncPaths
import com.music.vivi.wearsync.SyncRepeatMode
import com.music.vivi.wearsync.VolumeCommand
import com.music.vivi.wearsync.WearTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Where audio is actually coming out right now. */
enum class ActiveRoute { NONE, WATCH, PHONE }

/**
 * What the UI renders, flattened so screens never branch on the route.
 *
 * Position is deliberately absent — see [positionMs]. Holding it in state would
 * push a recomposition every tick even with the screen off.
 */
data class UiPlaybackState(
    val route: ActiveRoute = ActiveRoute.NONE,
    val track: WearTrack? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val durationMs: Long = 0L,
    val queue: List<WearTrack> = emptyList(),
    val queueIndex: Int = 0,
    val queueTitle: String? = null,
    val shuffle: Boolean = false,
    val repeatMode: Int = SyncRepeatMode.OFF,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val error: String? = null,
)

/**
 * Decides whether a control acts on this watch or on the phone, and keeps the
 * two from fighting over the same track.
 *
 * The rule, which is what YouTube Music and Spotify both settle on: whichever
 * device most recently started playing owns the session. A user who taps play
 * on the watch while the phone is mid-track is asking to move playback, not to
 * start a second one — so we tell the phone to stop.
 */
@UnstableApi
class PlaybackRouter(
    private val context: Context,
    private val phoneLink: PhoneLink,
    private val prefs: WearPrefs,
    private val scope: CoroutineScope,
) {

    private var controller: MediaController? = null

    private val localState = MutableStateFlow(UiPlaybackState())

    private val _state = MutableStateFlow(UiPlaybackState())
    val state: StateFlow<UiPlaybackState> = _state.asStateFlow()

    /** Queue as we handed it to the local player; media3 only round-trips MediaItems. */
    private var localQueue: List<WearTrack> = emptyList()
    private var localQueueTitle: String? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishLocal(player)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Timber.w(error, "Local playback error")
            localState.value = localState.value.copy(
                isLoading = false,
                error = friendlyError(error),
            )
        }
    }

    fun start() {
        connectController()
        scope.launch {
            combine(localState, phoneLink.nowPlaying, phoneLink.phoneReachable) { local, phone, reachable ->
                merge(local, phone, reachable)
            }.collect { _state.value = it }
        }
    }

    private fun connectController() {
        val token = SessionToken(context, ComponentName(context, WearMusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }
                .onSuccess { newController ->
                    controller = newController
                    newController.addListener(playerListener)
                    publishLocal(newController)
                }
                .onFailure { Timber.e(it, "Could not bind local MediaController") }
        }, MoreExecutors.directExecutor())
    }

    private fun publishLocal(player: Player) {
        val hasQueue = player.mediaItemCount > 0
        val current = player.currentMediaItem?.toWearTrack()
        localState.value = UiPlaybackState(
            route = if (hasQueue) ActiveRoute.WATCH else ActiveRoute.NONE,
            track = current,
            isPlaying = player.isPlaying,
            isLoading = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            queue = localQueue.ifEmpty { player.readQueue() },
            queueIndex = player.currentMediaItemIndex,
            queueTitle = localQueueTitle,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            canSkipNext = player.hasNextMediaItem(),
            canSkipPrevious = player.hasPreviousMediaItem(),
        )
    }

    private fun Player.readQueue(): List<WearTrack> =
        (0 until mediaItemCount).map { getMediaItemAt(it).toWearTrack() }

    /**
     * Local wins whenever the watch holds a queue; otherwise we mirror the phone.
     * Falling back to the phone's snapshot when it is unreachable would leave a
     * stale track on screen with dead buttons.
     */
    private fun merge(
        local: UiPlaybackState,
        phone: com.music.vivi.wearsync.NowPlayingState,
        phoneReachable: Boolean,
    ): UiPlaybackState = when {
        // Backstop for a dropped NOTIFY_PHONE_PLAYING. MessageClient is
        // fire-and-forget, so we cannot rely on it alone: if the phone says it
        // is actively playing while our own player is idle, the phone is the
        // real source of audio and holding a stale local queue would strand the
        // UI on a track nobody can hear.
        local.route == ActiveRoute.WATCH && !local.isPlaying &&
            phoneReachable && phone.phonePlaybackActive && phone.isPlaying ->
            phoneState(phone)

        local.route == ActiveRoute.WATCH -> local
        phoneReachable && phone.phonePlaybackActive -> phoneState(phone)

        else -> UiPlaybackState()
    }

    private fun phoneState(phone: com.music.vivi.wearsync.NowPlayingState) = UiPlaybackState(
        route = ActiveRoute.PHONE,
        track = phone.track,
        isPlaying = phone.isPlaying,
        durationMs = phone.durationMs,
        // The phone sends only the current track, not its whole queue, so the
        // watch's Queue screen shows a single row in remote mode.
        queue = listOfNotNull(phone.track),
        queueIndex = 0,
        queueTitle = phone.queueTitle,
        shuffle = phone.shuffle,
        repeatMode = phone.repeatMode,
        canSkipNext = phone.canSkipNext,
        canSkipPrevious = phone.canSkipPrevious,
    )

    /** Live position, sampled only while a screen that shows it is on. */
    fun positionMs(): Long = when (_state.value.route) {
        ActiveRoute.WATCH -> controller?.currentPosition ?: 0L
        ActiveRoute.PHONE -> phoneLink.extrapolatedPositionMs()
        ActiveRoute.NONE -> 0L
    }

    // ── Starting playback ────────────────────────────────────────────────────

    /**
     * Resolves the target device, then either hands the queue to the phone or
     * plays it here.
     */
    fun play(tracks: List<WearTrack>, startIndex: Int = 0, queueTitle: String? = null) {
        if (tracks.isEmpty()) return
        scope.launch {
            val target = resolveTarget()
            Timber.d("play(%d tracks) -> %s", tracks.size, target)
            when (target) {
                ActiveRoute.PHONE -> {
                    val sent = phoneLink.send(
                        SyncPaths.CMD_PLAY_TRACKS,
                        SyncCodec.encode(trimQueue(tracks, startIndex, queueTitle)),
                    )
                    // The phone went away between the routing decision and the
                    // send; play here rather than dropping the tap on the floor.
                    if (!sent) playLocal(tracks, startIndex, queueTitle)
                }
                else -> playLocal(tracks, startIndex, queueTitle)
            }
        }
    }

    /**
     * Clips a queue to what fits in one Data Layer message, keeping the track
     * the user actually tapped. Truncating from the front would silently start
     * the wrong song.
     */
    private fun trimQueue(
        tracks: List<WearTrack>,
        startIndex: Int,
        queueTitle: String?,
    ): PlayTracksCommand {
        if (tracks.size <= PlayTracksCommand.MAX_TRACKS) {
            return PlayTracksCommand(tracks, startIndex, queueTitle)
        }
        val from = startIndex.coerceIn(0, tracks.lastIndex)
        val window = tracks.drop(from).take(PlayTracksCommand.MAX_TRACKS)
        return PlayTracksCommand(window, 0, queueTitle)
    }

    private suspend fun resolveTarget(): ActiveRoute = when (prefs.route.first()) {
        PlaybackRoute.WATCH -> ActiveRoute.WATCH
        PlaybackRoute.PHONE -> ActiveRoute.PHONE
        PlaybackRoute.AUTO ->
            // Only follow the phone when it already has a session going. An idle
            // phone in your pocket should not steal a track you started on your
            // wrist with headphones on.
            if (phoneLink.phonePlaying.value) ActiveRoute.PHONE else ActiveRoute.WATCH
    }

    private suspend fun playLocal(tracks: List<WearTrack>, startIndex: Int, queueTitle: String?) {
        localQueue = tracks
        localQueueTitle = queueTitle
        withContext(Dispatchers.Main) {
            val player = controller ?: run {
                Timber.w("No local controller yet; dropping play request")
                return@withContext
            }
            player.setMediaItems(tracks.map(WearTrack::toMediaItem), startIndex, 0L)
            player.prepare()
            player.play()
        }
        // Two devices playing the same account at once is always a mistake.
        phoneLink.send(SyncPaths.NOTIFY_WATCH_PLAYING)
    }

    /** The phone told us it resumed; drop our local queue so we mirror it again. */
    fun onPhoneReclaimedPlayback() {
        scope.launch(Dispatchers.Main) {
            controller?.let {
                it.pause()
                it.clearMediaItems()
            }
            localQueue = emptyList()
            localQueueTitle = null
        }
    }

    // ── Transport ────────────────────────────────────────────────────────────

    fun togglePlayPause() = dispatch(
        local = { if (it.isPlaying) it.pause() else it.play() },
        remote = { SyncPaths.CMD_TOGGLE to ByteArray(0) },
    )

    fun next() = dispatch(
        local = { it.seekToNextMediaItem() },
        remote = { SyncPaths.CMD_NEXT to ByteArray(0) },
    )

    fun previous() = dispatch(
        local = { it.seekToPreviousMediaItem() },
        remote = { SyncPaths.CMD_PREVIOUS to ByteArray(0) },
    )

    fun seekTo(positionMs: Long) = dispatch(
        local = { it.seekTo(positionMs) },
        remote = { SyncPaths.CMD_SEEK to SyncCodec.encode(SeekCommand(positionMs)) },
    )

    fun toggleShuffle() = dispatch(
        local = { it.shuffleModeEnabled = !it.shuffleModeEnabled },
        remote = { SyncPaths.CMD_TOGGLE_SHUFFLE to ByteArray(0) },
    )

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            SyncRepeatMode.OFF -> SyncRepeatMode.ALL
            SyncRepeatMode.ALL -> SyncRepeatMode.ONE
            else -> SyncRepeatMode.OFF
        }
        dispatch(
            local = { it.repeatMode = next },
            remote = { SyncPaths.CMD_SET_REPEAT to SyncCodec.encode(RepeatCommand(next)) },
        )
    }

    fun seekToQueueIndex(index: Int) = dispatch(
        local = { it.seekTo(index, 0L) },
        remote = { SyncPaths.CMD_SEEK to SyncCodec.encode(SeekCommand(0L)) },
    )

    /** Only meaningful on the phone; the watch's own volume is a system gesture. */
    fun nudgePhoneVolume(steps: Int) {
        phoneLink.sendAsync(SyncPaths.CMD_VOLUME, SyncCodec.encode(VolumeCommand(steps)))
    }

    fun toggleLike() {
        val track = _state.value.track ?: return
        phoneLink.sendAsync(
            SyncPaths.CMD_TOGGLE_LIKE,
            SyncCodec.encode(LikeCommand(track.id, !track.liked)),
        )
    }

    fun stopLocal() {
        scope.launch(Dispatchers.Main) {
            controller?.let {
                it.stop()
                it.clearMediaItems()
            }
            localQueue = emptyList()
            localQueueTitle = null
            phoneLink.send(SyncPaths.NOTIFY_WATCH_STOPPED)
        }
    }

    suspend fun setPreferredRoute(route: PlaybackRoute) {
        prefs.setRoute(route)
        // Switching to the phone while the watch is playing should actually move
        // the music, not just change a preference for next time.
        if (route == PlaybackRoute.PHONE && _state.value.route == ActiveRoute.WATCH) {
            val current = _state.value
            val sent = phoneLink.send(
                SyncPaths.CMD_PLAY_TRACKS,
                SyncCodec.encode(
                    trimQueue(current.queue, current.queueIndex, current.queueTitle)
                ),
            )
            if (sent) stopLocal()
        }
    }

    private fun dispatch(
        local: (MediaController) -> Unit,
        remote: () -> Pair<String, ByteArray>,
    ) {
        when (_state.value.route) {
            ActiveRoute.WATCH -> controller?.let(local)
            ActiveRoute.PHONE -> {
                val (path, payload) = remote()
                phoneLink.sendAsync(path, payload)
            }
            ActiveRoute.NONE -> Unit
        }
    }

    private fun friendlyError(error: androidx.media3.common.PlaybackException): String = when {
        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "No connection"
        error.cause is WearStreamResolver.NoStreamAvailableException -> "Can't play this here"
        else -> "Playback failed"
    }

    fun release() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }
}
