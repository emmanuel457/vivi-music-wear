/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.data

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.music.vivi.wear.playback.WearStreamResolver
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.WearTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

enum class DownloadState { NONE, DOWNLOADING, DOWNLOADED }

@Serializable
private data class DownloadIndex(val tracks: List<WearTrack> = emptyList())

/**
 * Stores audio on the watch itself, so a downloaded song plays with no phone in
 * range and no network at all.
 *
 * Downloads live in their own [SimpleCache] with a [NoOpCacheEvictor], separate
 * from the streaming cache. The streaming cache is LRU-evicted at 256 MB — if
 * downloads shared it, the thing the user explicitly saved for a flight would be
 * silently discarded to make room for something they streamed once.
 */
@UnstableApi
class WearDownloadManager(
    private val context: Context,
    private val prefs: WearPrefs,
    private val scope: CoroutineScope,
) {

    /** Invoked after a successful watch download, to mirror it to the phone. */
    var onDownloadedToWatch: ((WearTrack) -> Unit)? = null

    private val indexFile = File(context.filesDir, "downloads.json")

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val _downloaded = MutableStateFlow<List<WearTrack>>(emptyList())

    /** Tracks stored on this watch, for the Downloads library section. */
    val downloaded: StateFlow<List<WearTrack>> = _downloaded.asStateFlow()

    private val httpFactory by lazy {
        OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext
        runCatching { SyncCodec.decode<DownloadIndex>(indexFile.readBytes()) }
            .onSuccess { index ->
                _downloaded.value = index.tracks
                _states.value = index.tracks.associate { it.id to DownloadState.DOWNLOADED }
            }
            .onFailure {
                Timber.w(it, "Download index unreadable, discarding")
                indexFile.delete()
            }
    }

    fun toggle(track: WearTrack) {
        when (_states.value[track.id]) {
            DownloadState.DOWNLOADED -> scope.launch { remove(track) }
            DownloadState.DOWNLOADING -> Unit // already in flight
            else -> scope.launch { download(track) }
        }
    }

    private suspend fun download(track: WearTrack) = withContext(Dispatchers.IO) {
        setState(track.id, DownloadState.DOWNLOADING)

        val quality = prefs.audioQuality.first()
        // A download is a deliberate act, so never quality-degrade it just
        // because the watch happens to be on a metered link right now.
        val stream = WearStreamResolver.resolve(track.id, quality, isMetered = false)
            .getOrElse {
                Timber.w(it, "Download failed to resolve %s", track.id)
                setState(track.id, DownloadState.NONE)
                return@withContext
            }

        val dataSpec = DataSpec.Builder()
            .setUri(stream.url.toUri())
            // Must match the MediaItem's customCacheKey or playback will not
            // find what we just stored.
            .setKey(track.id)
            .build()

        val dataSource = CacheDataSource.Factory()
            .setCache(downloadCache(context))
            .setUpstreamDataSourceFactory(httpFactory)
            .createDataSource()

        runCatching {
            CacheWriter(dataSource, dataSpec, null) { _, bytesCached, _ ->
                Timber.v("Downloaded %d bytes of %s", bytesCached, track.id)
            }.cache()
        }.onSuccess {
            addToIndex(track)
            setState(track.id, DownloadState.DOWNLOADED)
            Timber.i("Downloaded %s to the watch", track.title)
            // Mirror it to the phone so the song is genuinely offline on both
            // devices, not just on whichever one happened to be in hand.
            onDownloadedToWatch?.invoke(track)
        }.onFailure {
            Timber.w(it, "Download of %s failed", track.id)
            // Leave nothing half-stored: a partial file would look downloaded
            // and then stall mid-song offline.
            runCatching { downloadCache(context).removeResource(track.id) }
            setState(track.id, DownloadState.NONE)
        }
    }

    private suspend fun remove(track: WearTrack) = withContext(Dispatchers.IO) {
        runCatching { downloadCache(context).removeResource(track.id) }
            .onFailure { Timber.w(it, "Could not remove %s", track.id) }
        _downloaded.value = _downloaded.value.filterNot { it.id == track.id }
        setState(track.id, DownloadState.NONE)
        persist()
    }

    private fun setState(id: String, state: DownloadState) {
        _states.value = if (state == DownloadState.NONE) {
            _states.value - id
        } else {
            _states.value + (id to state)
        }
    }

    private suspend fun addToIndex(track: WearTrack) {
        if (_downloaded.value.none { it.id == track.id }) {
            _downloaded.value = _downloaded.value + track
        }
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(indexFile.parentFile, "${indexFile.name}.tmp")
            tmp.writeBytes(SyncCodec.encode(DownloadIndex(_downloaded.value)))
            if (indexFile.exists()) indexFile.delete()
            tmp.renameTo(indexFile)
        }.onFailure { Timber.w(it, "Could not persist the download index") }
    }

    companion object {
        @Volatile
        private var cache: SimpleCache? = null

        /**
         * Process-wide because SimpleCache holds an exclusive lock on its
         * directory; a second instance over the same folder throws.
         */
        fun downloadCache(context: Context): SimpleCache =
            cache ?: synchronized(this) {
                cache ?: SimpleCache(
                    File(context.filesDir, "downloads"),
                    // Never evicted. These are files the user asked to keep.
                    NoOpCacheEvictor(),
                    StandaloneDatabaseProvider(context),
                ).also { cache = it }
            }
    }
}
