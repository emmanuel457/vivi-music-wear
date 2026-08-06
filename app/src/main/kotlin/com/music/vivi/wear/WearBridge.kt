/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import androidx.media3.common.Player
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.music.vivi.extensions.currentMetadata
import com.music.vivi.models.MediaMetadata
import com.music.vivi.playback.MusicService
import com.music.vivi.wearsync.NowPlayingState
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncKeys
import com.music.vivi.wearsync.SyncPaths
import com.music.vivi.wearsync.WearTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's half of the watch link.
 *
 * Holds a weak handle to the live [MusicService] so the (short-lived, system
 * started) [PhoneWearListenerService] can act on the real player without
 * binding to it — binding would start playback infrastructure just to answer a
 * "what's playing?" ping.
 */
object WearBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serviceRef = WeakReference<MusicService>(null)

    /**
     * DataItems are content-addressed: republishing identical bytes is a no-op
     * and the watch never sees an event. A revision counter guarantees an
     * explicit REQ_STATE always lands.
     */
    private val revision = AtomicLong(0L)

    /** Last publish time, to keep a seeking user from flooding the Bluetooth link. */
    @Volatile
    private var lastPublishAt = 0L

    private const val MIN_PUBLISH_INTERVAL_MS = 900L

    fun attach(service: MusicService) {
        serviceRef = WeakReference(service)
    }

    fun detach() {
        serviceRef = WeakReference(null)
        publishIdle()
    }

    /** The live playback service, or null when nothing is playing. */
    fun musicService(): MusicService? = serviceRef.get()

    private fun service(): MusicService? = serviceRef.get()

    // ── Publishing state ─────────────────────────────────────────────────────

    /**
     * Called from `MusicService.onEvents`. Throttled, because media3 emits
     * events far faster than a watch screen can use them and each one costs a
     * Bluetooth round trip.
     */
    fun onPlayerEvents(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishAt < MIN_PUBLISH_INTERVAL_MS) return
        lastPublishAt = now
        scope.launch { publishNowPlaying() }
    }

    suspend fun publishNowPlaying() {
        val service = service() ?: run { publishIdleBlocking(); return }
        val state = runCatching { captureState(service) }.getOrNull() ?: return
        putDataItem(SyncPaths.STATE_NOW_PLAYING, SyncCodec.encode(state))
    }

    private fun publishIdle() {
        scope.launch { publishIdleBlocking() }
    }

    private suspend fun publishIdleBlocking() {
        putDataItem(
            SyncPaths.STATE_NOW_PLAYING,
            SyncCodec.encode(NowPlayingState.IDLE),
        )
    }

    private fun captureState(service: MusicService): NowPlayingState {
        val player: Player = service.player
        val metadata: MediaMetadata? = player.currentMetadata
        val audioManager = service.getSystemService<AudioManager>()
        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

        return NowPlayingState(
            track = metadata?.toWearTrack(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            queueIndex = player.currentMediaItemIndex,
            queueSize = player.mediaItemCount,
            queueTitle = service.queueTitle,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            canSkipNext = player.hasNextMediaItem(),
            canSkipPrevious = player.hasPreviousMediaItem(),
            volume = if (maxVolume > 0) currentVolume.toFloat() / maxVolume else 0f,
            capturedAtElapsedRealtime = SystemClock.elapsedRealtime(),
            phonePlaybackActive = player.mediaItemCount > 0,
        )
    }

    // ── Data Layer plumbing ──────────────────────────────────────────────────

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private suspend fun putDataItem(path: String, payload: ByteArray) {
        if (!::appContext.isInitialized) return
        runCatching {
            val request = PutDataMapRequest.create(path).apply {
                dataMap.putByteArray(SyncKeys.PAYLOAD, payload)
                dataMap.putLong(SyncKeys.REVISION, revision.incrementAndGet())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(appContext).putDataItem(request).await()
        }.onFailure { Timber.w(it, "Could not publish %s to the watch", path) }
    }

    internal fun requireContext(): Context = appContext
}

/** Flattens the phone's rich metadata into the watch's wire model. */
internal fun MediaMetadata.toWearTrack(): WearTrack = WearTrack(
    id = id,
    title = title,
    artist = artists.joinToString(", ") { it.name },
    album = album?.title,
    durationSec = duration,
    thumbnailUrl = thumbnailUrl,
    liked = liked,
    explicit = explicit,
)

/**
 * Rebuilds the phone's metadata model from a watch-sent track.
 *
 * The tag matters as much as the media metadata: the phone's queue, notification
 * and history all read `MediaItem.metadata`, so a MediaItem without it plays but
 * shows up blank everywhere else.
 */
internal fun WearTrack.toMediaMetadata(): MediaMetadata = MediaMetadata(
    id = id,
    title = title,
    artists = artist.split(", ")
        .filter { it.isNotBlank() }
        .map { MediaMetadata.Artist(id = null, name = it) },
    duration = durationSec,
    thumbnailUrl = thumbnailUrl,
    album = null,
    explicit = explicit,
    liked = liked,
)

internal fun WearTrack.toMediaItem(): androidx.media3.common.MediaItem {
    val metadata = toMediaMetadata()
    return androidx.media3.common.MediaItem.Builder()
        .setMediaId(id)
        .setUri(id)
        .setCustomCacheKey(id)
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(artist)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(thumbnailUrl?.let(android.net.Uri::parse))
                .setDisplayTitle(title)
                .build()
        )
        .build()
}
