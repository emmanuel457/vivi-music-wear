/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wearsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A track, flattened for the wire. The watch has no Room database and no
 * artist/album relations, so everything it needs to render a row is inlined.
 */
@Serializable
data class WearTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String? = null,
    val durationSec: Int = -1,
    val thumbnailUrl: String? = null,
    val liked: Boolean = false,
    val explicit: Boolean = false,
    /** True when the phone has this song downloaded locally. */
    val downloadedOnPhone: Boolean = false,
)

@Serializable
data class WearPlaylist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val thumbnailUrl: String? = null,
    val isLocal: Boolean = false,
)

/**
 * Snapshot of what the phone is doing right now.
 *
 * [positionMs] is only correct as of [capturedAtElapsedRealtime]; the watch
 * extrapolates from there rather than asking for ticks, which would keep the
 * Bluetooth link hot and wreck battery on both devices.
 */
@Serializable
data class NowPlayingState(
    val track: WearTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val queueTitle: String? = null,
    val shuffle: Boolean = false,
    val repeatMode: Int = SyncRepeatMode.OFF,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    /** Phone media volume, 0f..1f. */
    val volume: Float = 0f,
    /** `SystemClock.elapsedRealtime()` on the *phone* when this was captured. */
    val capturedAtElapsedRealtime: Long = 0L,

    /**
     * Stable id of the device that owns playback.
     *
     * Spotify Connect designates exactly one active device and every other
     * device is a pure remote. Inferring that from `isPlaying` flags arriving
     * asynchronously from both sides let two devices claim it at once — or
     * neither — which is what produced blank screens, flicker, and a device
     * playing its own stale queue while displaying the peer's track.
     */
    val activeDeviceId: String? = null,

    /**
     * Monotonic claim counter. A snapshot carrying a lower epoch than one
     * already seen is stale and must be discarded; without this a late-arriving
     * update overwrites a newer one, which is why a paused device would revert
     * to showing some earlier song.
     */
    val epoch: Long = 0L,
    /**
     * True while the phone's MusicService holds an active player. Drives the
     * watch's automatic remote-vs-local decision.
     */
    val phonePlaybackActive: Boolean = false,
) {
    companion object {
        val IDLE = NowPlayingState()
    }
}

/**
 * The signed-in YouTube session, handed from phone to watch so the user never
 * has to type a Google password on a 45 mm screen.
 *
 * This is account-bearing material. It is only ever sent after an explicit
 * confirmation on the phone, and only to a node paired via the Data Layer,
 * which requires both APKs to carry the same signature.
 */
@Serializable
data class AuthPayload(
    val cookie: String? = null,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val accountName: String? = null,
    val accountEmail: String? = null,
    val accountChannelHandle: String? = null,
    val issuedAtEpochMs: Long = 0L,
    /** Set when the user signs out on the phone, to clear the watch too. */
    val revoked: Boolean = false,
)

/**
 * Bulk library payload, streamed over a ChannelClient because it routinely
 * exceeds the 100 KB DataItem ceiling.
 */
@Serializable
data class LibrarySnapshot(
    val likedSongs: List<WearTrack> = emptyList(),
    val recentSongs: List<WearTrack> = emptyList(),
    val downloadedSongs: List<WearTrack> = emptyList(),
    val playlists: List<WearPlaylist> = emptyList(),
    val generatedAtEpochMs: Long = 0L,
) {
    companion object {
        val EMPTY = LibrarySnapshot()
    }
}

/**
 * Watch asks the phone to start a queue.
 *
 * Carries whole tracks rather than bare ids: the watch can be playing results
 * from a search the phone has never seen, and making the phone re-resolve every
 * id over the network would add seconds of silence to a button press. At roughly
 * 200 bytes a track this stays far inside the Data Layer's 100 KB message
 * ceiling for any queue a watch can realistically show — see [MAX_TRACKS].
 */
@Serializable
data class PlayTracksCommand(
    val tracks: List<WearTrack>,
    val startIndex: Int = 0,
    val queueTitle: String? = null,
) {
    companion object {
        const val MAX_TRACKS = 100
    }
}

@Serializable
data class SeekCommand(val positionMs: Long)

@Serializable
data class RepeatCommand(val mode: Int)

/** Relative volume nudge; [steps] is signed and applied to the phone's media stream. */
@Serializable
data class VolumeCommand(val steps: Int)

@Serializable
data class LikeCommand(val trackId: String, val liked: Boolean)

/**
 * Encode/decode helpers. `encodeDefaults` is on so a field the receiver knows
 * about but the sender left at its default still arrives, and `ignoreUnknownKeys`
 * so a newer phone build talking to an older watch build degrades instead of
 * throwing.
 */
object SyncCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): ByteArray =
        json.encodeToString(value).toByteArray(Charsets.UTF_8)

    inline fun <reified T> decode(bytes: ByteArray): T =
        json.decodeFromString(String(bytes, Charsets.UTF_8))

    inline fun <reified T> decodeOrNull(bytes: ByteArray?): T? =
        if (bytes == null || bytes.isEmpty()) null
        else runCatching { decode<T>(bytes) }.getOrNull()
}
