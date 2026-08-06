/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.music.vivi.db.MusicDatabase
import com.music.vivi.db.entities.Playlist
import com.music.vivi.db.entities.Song
import com.music.vivi.wearsync.LibrarySnapshot
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncPaths
import com.music.vivi.wearsync.WearPlaylist
import com.music.vivi.wearsync.WearTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Streams a library snapshot to the watch.
 *
 * Uses a ChannelClient rather than a DataItem because a real library blows past
 * the Data Layer's 100 KB per-item ceiling, and unlike a DataItem a channel
 * doesn't sit in the replication store consuming space on both devices.
 */
class WearLibraryPublisher(
    private val context: Context,
    private val database: MusicDatabase,
) {

    suspend fun publishTo(nodeId: String) {
        val snapshot = buildSnapshot()
        val client = Wearable.getChannelClient(context)
        var channel: com.google.android.gms.wearable.ChannelClient.Channel? = null
        runCatching {
            channel = client.openChannel(nodeId, SyncPaths.CHANNEL_LIBRARY).await()
            val output = client.getOutputStream(channel!!).await()
            output.use { it.write(SyncCodec.encode(snapshot)) }
            Timber.i(
                "Pushed library to watch: %d liked, %d recent, %d downloaded, %d playlists",
                snapshot.likedSongs.size,
                snapshot.recentSongs.size,
                snapshot.downloadedSongs.size,
                snapshot.playlists.size,
            )
        }.onFailure {
            Timber.w(it, "Library push to %s failed", nodeId)
        }
        channel?.let { runCatching { client.close(it).await() } }
    }

    private suspend fun buildSnapshot(): LibrarySnapshot {
        // Each list is capped: a watch cannot usefully scroll thousands of rows,
        // and the transfer runs over Bluetooth at a few tens of KB/s.
        val liked = database.likedSongsByCreateDateAsc().first()
            .asReversed()
            .take(MAX_LIKED)
            .map { it.toWearTrack() }

        val downloaded = database.downloadedSongsByCreateDateAsc().first()
            .asReversed()
            .take(MAX_DOWNLOADED)
            .map { it.toWearTrack().copy(downloadedOnPhone = true) }

        val recent = database.events().first()
            .map { it.song }
            .distinctBy { it.id }
            .take(MAX_RECENT)
            .map { it.toWearTrack() }

        val playlists = database.playlistsByCreateDateAsc().first()
            .asReversed()
            .take(MAX_PLAYLISTS)
            .map { it.toWearPlaylist() }

        return LibrarySnapshot(
            likedSongs = liked,
            recentSongs = recent,
            downloadedSongs = downloaded,
            playlists = playlists,
            generatedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val MAX_LIKED = 300
        const val MAX_DOWNLOADED = 300
        const val MAX_RECENT = 50
        const val MAX_PLAYLISTS = 50
    }
}

internal fun Song.toWearTrack(): WearTrack = WearTrack(
    id = song.id,
    title = song.title,
    artist = artists.joinToString(", ") { it.name },
    album = song.albumName,
    durationSec = song.duration,
    thumbnailUrl = song.thumbnailUrl,
    liked = song.liked,
    explicit = song.explicit,
    downloadedOnPhone = song.isDownloaded,
)

internal fun Playlist.toWearPlaylist(): WearPlaylist = WearPlaylist(
    id = playlist.id,
    name = playlist.name,
    songCount = songCount,
    thumbnailUrl = songThumbnails.firstOrNull { !it.isNullOrBlank() },
    isLocal = playlist.browseId == null,
)
