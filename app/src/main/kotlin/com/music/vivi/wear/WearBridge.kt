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
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.music.vivi.wearsync.SyncCapabilities
import com.music.vivi.extensions.currentMetadata
import com.music.vivi.extensions.metadata
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    @Volatile
    private var wasPlaying = false

    private val _watches = MutableStateFlow<List<com.music.vivi.connect.ConnectDevice>>(emptyList())

    /**
     * Paired watches running Vivi Music, shaped as Connect devices so the picker
     * can list them beside phones and tablets. The watch is a real playback
     * device — it streams on its own and has its own downloads — so hiding it
     * from the device list made it look like an accessory rather than a peer.
     */
    val watches: StateFlow<List<com.music.vivi.connect.ConnectDevice>> = _watches.asStateFlow()

    /** Keeps [watches] current. The Data Layer, not NSD, is the watch's transport. */
    fun startWatchDiscovery() {
        if (!::appContext.isInitialized) return
        val client = Wearable.getCapabilityClient(appContext)
        client.addListener(
            { info -> _watches.value = info.nodes.map(::toDevice) },
            SyncCapabilities.WATCH,
        )
        scope.launch {
            runCatching {
                client.getCapability(SyncCapabilities.WATCH, CapabilityClient.FILTER_REACHABLE)
                    .await()
                    .nodes
            }.onSuccess { nodes -> _watches.value = nodes.map(::toDevice) }
                .onFailure { Timber.w(it, "Could not enumerate paired watches") }
        }
    }

    private fun toDevice(node: com.google.android.gms.wearable.Node) =
        com.music.vivi.connect.ConnectDevice(
            id = node.id,
            name = node.displayName,
            // Not addressable by IP; commands reach it over the Data Layer.
            host = "wear",
            port = 0,
            connected = true,
            kind = com.music.vivi.connect.DeviceKind.WATCH,
        )

    /** Sends to one watch node, for a targeted playback transfer. */
    fun sendToWatchNode(nodeId: String, path: String, payload: ByteArray) {
        if (!::appContext.isInitialized) return
        scope.launch {
            runCatching {
                Wearable.getMessageClient(appContext).sendMessage(nodeId, path, payload).await()
            }.onFailure { Timber.w(it, "Could not send %s to watch %s", path, nodeId) }
        }
    }

    /** Fire-and-forget message to every paired watch running Vivi Music. */
    private suspend fun sendToWatches(path: String, payload: ByteArray = ByteArray(0)) {
        if (!::appContext.isInitialized) return
        runCatching {
            val nodes = Wearable.getCapabilityClient(appContext)
                .getCapability(SyncCapabilities.WATCH, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
            val messageClient = Wearable.getMessageClient(appContext)
            for (node in nodes) {
                runCatching { messageClient.sendMessage(node.id, path, payload).await() }
            }
        }.onFailure { Timber.w(it, "Could not notify watches on %s", path) }
    }

    suspend fun publishNowPlaying() {
        val service = service() ?: run { publishIdleBlocking(); return }

        // ExoPlayer throws when touched from any thread but the one it was
        // built on. This scope is Dispatchers.IO, so reading the player here
        // directly threw on every single publish and the catch below swallowed
        // it -- the watch silently never received a now-playing update, while
        // library sync kept working because it never touches the player.
        val state = withContext(Dispatchers.Main) {
            runCatching { captureState(service) }
                .onFailure { Timber.w(it, "Could not read playback state for the watch") }
                .getOrNull()
        } ?: return

        // A false -> true transition means this phone just took over. Tell the
        // watch so it drops any local queue, mirroring what the watch does to us
        // via NOTIFY_WATCH_PLAYING; without it "last actor wins" holds in one
        // direction only. Derived from the captured state rather than read off
        // the player, because this method is also reached from IO threads.
        if (state.isPlaying && !wasPlaying) {
            scope.launch { sendToWatches(SyncPaths.NOTIFY_PHONE_PLAYING) }
        }
        wasPlaying = state.isPlaying

        lastState = state
        putDataItem(SyncPaths.STATE_NOW_PLAYING, SyncCodec.encode(state))
        publishQueueIfChanged(service)
        // Same snapshot, second transport: watches get it over the Data Layer,
        // phones and tablets over Vivi Connect.
        com.music.vivi.connect.ConnectBridge.onPlaybackStateChanged(state)
    }

    /** Last state we successfully captured, for callers that aren't on main. */
    @Volatile
    private var lastState: NowPlayingState = NowPlayingState.IDLE

    @Volatile
    private var lastQueue: com.music.vivi.wearsync.QueueSnapshot =
        com.music.vivi.wearsync.QueueSnapshot.EMPTY

    /** The playing device's queue, for transfers and remote queue screens. */
    fun queueSnapshot(): com.music.vivi.wearsync.QueueSnapshot = lastQueue

    /**
     * Captures the queue and publishes it when it has actually changed.
     *
     * Kept out of [NowPlayingState] on purpose: that is republished on every
     * position tick and play/pause, and pushing a hundred tracks through the
     * Data Layer at that rate would saturate a Bluetooth link for data that
     * changes once a song.
     */
    private suspend fun publishQueueIfChanged(service: MusicService) {
        val snapshot = withContext(Dispatchers.Main) {
            runCatching {
                val player = service.player
                val tracks = (0 until player.mediaItemCount)
                    .take(com.music.vivi.wearsync.QueueSnapshot.MAX_TRACKS)
                    .mapNotNull { index ->
                        player.getMediaItemAt(index).metadata?.toWearTrack()
                    }
                com.music.vivi.wearsync.QueueSnapshot(
                    tracks = tracks,
                    queueTitle = service.queueTitle,
                    currentIndex = player.currentMediaItemIndex,
                    // Identity of the queue itself, so a peer can tell a genuine
                    // change from the same list being re-sent.
                    revision = tracks.fold(7L) { acc, t -> acc * 31 + t.id.hashCode() },
                )
            }.getOrNull()
        } ?: return

        if (snapshot.revision == lastQueue.revision &&
            snapshot.currentIndex == lastQueue.currentIndex
        ) {
            return
        }
        lastQueue = snapshot
        putDataItem(SyncPaths.STATE_QUEUE, SyncCodec.encode(snapshot))
        com.music.vivi.connect.ConnectBridge.onQueueChanged(snapshot)
    }

    /**
     * Current playback state, or idle when nothing is running. Used by Connect
     * to brief a peer the moment it links up.
     *
     * Returns the cached snapshot rather than reading the player: callers run on
     * arbitrary threads, and blocking onto main here would risk deadlocking the
     * very thread that produces the value.
     */
    fun snapshot(): NowPlayingState =
        if (service() == null) NowPlayingState.IDLE else lastState

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
