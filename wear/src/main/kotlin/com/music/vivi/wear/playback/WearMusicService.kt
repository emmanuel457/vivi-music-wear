/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.music.vivi.wear.MainActivity
import com.music.vivi.wear.WearGraph
import com.music.vivi.wear.data.WearDownloadManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Local playback on the watch: streams to Bluetooth headphones over the watch's
 * own Wi-Fi or LTE, with no phone involved.
 */
@UnstableApi
class WearMusicService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    /**
     * Resolved stream URLs, keyed by video id, with their expiry. googlevideo
     * URLs are signed and short-lived, so a stale entry produces a 403 rather
     * than a graceful failure — always check the deadline before reuse.
     */
    private val resolvedUrls = ConcurrentHashMap<String, Pair<String, Long>>()

    override fun onCreate() {
        super.onCreate()
        WearGraph.ensureInitialized(applicationContext)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // NETWORK rather than LOCAL: the watch dozes aggressively, and
            // without the network lock the stream stalls the moment the screen
            // turns off, which is most of the time on a watch.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.repeatMode = ExoPlayer.REPEAT_MODE_OFF

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the watch app away should not kill audio mid-track.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        val connectivityManager = getSystemService<ConnectivityManager>()

        val upstream = OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        )

        val streamCacheFactory = CacheDataSource.Factory()
            .setCache(playerCache(this))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Downloads are read first and never written here — WearDownloadManager
        // owns writes via CacheWriter. Chaining them means a downloaded track is
        // served from the watch's own storage before anything considers the
        // network, which is what makes offline playback actually offline.
        val downloadCache = WearDownloadManager.downloadCache(this)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(streamCacheFactory)
            .setCacheWriteDataSinkFactory(null)

        return ResolvingDataSource.Factory(cacheFactory) { dataSpec: DataSpec ->
            val mediaId = dataSpec.key ?: return@Factory dataSpec

            // Fully downloaded: resolve nothing. Hitting YouTube here would make
            // an offline watch fail on a track it already has.
            if (downloadCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)) {
                return@Factory dataSpec
            }

            // Already on disk for this range — leave the spec alone so the cache
            // serves it without touching the network.
            if (playerCache(this).isCached(mediaId, dataSpec.position, CHUNK_LENGTH)) {
                return@Factory dataSpec
            }

            resolvedUrls[mediaId]
                ?.takeIf { (_, expiresAt) -> expiresAt > System.currentTimeMillis() }
                ?.let { (url, _) -> return@Factory dataSpec.withUri(url.toUri()) }

            val quality = runBlocking { WearGraph.prefs.audioQuality.first() }
            val metered = connectivityManager?.isActiveNetworkMetered ?: false

            val stream = runBlocking {
                WearStreamResolver.resolve(mediaId, quality, metered)
            }.getOrElse { throw it }

            resolvedUrls[mediaId] = stream.url to
                (System.currentTimeMillis() + stream.expiresInSeconds * 1000L)

            Timber.d("Streaming %s at %d bps", mediaId, stream.bitrate)
            dataSpec.withUri(stream.url.toUri()).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
        }
    }

    companion object {
        private const val CHUNK_LENGTH = 512 * 1024L

        /**
         * SimpleCache holds an exclusive lock on its directory, so a second
         * instance over the same folder throws. The service can be recreated
         * within one process lifetime, hence the process-wide singleton.
         */
        @Volatile
        private var cache: SimpleCache? = null

        fun playerCache(service: WearMusicService): SimpleCache =
            cache ?: synchronized(this) {
                cache ?: SimpleCache(
                    File(service.cacheDir, "player"),
                    // 256 MB: watches ship with little storage and the OS will
                    // start reclaiming aggressively past that.
                    LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                    StandaloneDatabaseProvider(service),
                ).also { cache = it }
            }
    }
}
