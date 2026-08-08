/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.music.innertube.models.SongItem
import com.music.vivi.wearsync.WearTrack

private const val EXTRA_DURATION_SEC = "vivi.durationSec"
private const val EXTRA_LIKED = "vivi.liked"
private const val EXTRA_EXPLICIT = "vivi.explicit"
private const val EXTRA_THUMBNAIL = "vivi.thumbnail"

/**
 * The watch has no database, so a track's display data has to survive inside the
 * MediaItem itself — that's what lets playback restore correctly after the
 * service is killed and rebuilt without a round trip to the phone.
 */
fun WearTrack.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    // The resolver keys off the DataSpec key, which media3 populates from the
    // custom cache key. The URI is a placeholder that ResolvingDataSource swaps
    // for a real googlevideo URL at load time.
    .setUri(id)
    .setCustomCacheKey(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(thumbnailUrl?.toUri())
            .setExtras(
                Bundle().apply {
                    putInt(EXTRA_DURATION_SEC, durationSec)
                    putBoolean(EXTRA_LIKED, liked)
                    putBoolean(EXTRA_EXPLICIT, explicit)
                    putString(EXTRA_THUMBNAIL, thumbnailUrl)
                }
            )
            .build()
    )
    .build()

fun MediaItem.toWearTrack(): WearTrack {
    val extras = mediaMetadata.extras
    return WearTrack(
        id = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString(),
        durationSec = extras?.getInt(EXTRA_DURATION_SEC, -1) ?: -1,
        thumbnailUrl = extras?.getString(EXTRA_THUMBNAIL)
            ?: mediaMetadata.artworkUri?.toString(),
        liked = extras?.getBoolean(EXTRA_LIKED, false) ?: false,
        explicit = extras?.getBoolean(EXTRA_EXPLICIT, false) ?: false,
    )
}

/** Bridges innertube search/browse results into the watch's flat track model. */
fun SongItem.toWearTrack(): WearTrack = WearTrack(
    id = id,
    title = title,
    artist = artists.joinToString(", ") { it.name },
    album = album?.name,
    durationSec = duration ?: -1,
    thumbnailUrl = thumbnail,
    explicit = explicit,
)
