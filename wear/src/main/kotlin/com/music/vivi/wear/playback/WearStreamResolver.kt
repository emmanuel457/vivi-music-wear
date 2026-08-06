/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.playback

import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.music.innertube.models.YouTubeClient.Companion.IOS
import com.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.music.innertube.models.response.PlayerResponse
import com.music.vivi.wear.data.WearAudioQuality
import timber.log.Timber

/**
 * Resolves a playable audio URL for the watch.
 *
 * This is deliberately a fraction of the phone's `YTPlayerUtils`. The phone
 * chases every edge case — PoToken generation, Rhino-based signature
 * deobfuscation, NewPipe fallbacks, JioSaavn substitution, age-gate reroutes.
 * On a watch each of those costs CPU, memory and battery to serve a small
 * minority of tracks, so we lean on the ANDROID_VR clients, which return direct
 * unciphered URLs and need neither a PoToken nor a signature timestamp, and
 * accept that a rare track will fail over to the phone instead.
 */
object WearStreamResolver {

    private val MAIN_CLIENT: YouTubeClient = ANDROID_VR_1_43_32

    private val FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_1_61_48,
        ANDROID_VR_NO_AUTH,
        IOS,
        WEB_REMIX,
    )

    data class Stream(
        val url: String,
        val expiresInSeconds: Int,
        val bitrate: Int,
        val mimeType: String,
        val loudnessDb: Double?,
    )

    suspend fun resolve(
        videoId: String,
        quality: WearAudioQuality,
        isMetered: Boolean,
    ): Result<Stream> = runCatching {
        var lastReason: String? = null

        for (client in listOf(MAIN_CLIENT, *FALLBACK_CLIENTS)) {
            if (client.loginRequired && YouTube.cookie == null) continue

            val response = YouTube.player(videoId, null, client).getOrNull()
            if (response == null) {
                lastReason = "no response from ${client.clientName}"
                continue
            }
            if (response.playabilityStatus.status != "OK") {
                lastReason = "${client.clientName}: ${response.playabilityStatus.status}"
                continue
            }

            val format = pickFormat(response, quality, isMetered)
            if (format == null) {
                lastReason = "${client.clientName}: no audio format"
                continue
            }

            // Only direct URLs are usable here — a ciphered format would need the
            // JS player deobfuscator we intentionally left out of this module.
            val url = format.url
            if (url.isNullOrEmpty()) {
                lastReason = "${client.clientName}: ciphered format, skipping"
                continue
            }

            val expires = response.streamingData?.expiresInSeconds ?: 3600
            Timber.d("Resolved %s via %s (%d bps)", videoId, client.clientName, format.bitrate)
            return@runCatching Stream(
                url = url,
                expiresInSeconds = expires,
                bitrate = format.bitrate,
                mimeType = format.mimeType,
                loudnessDb = format.loudnessDb,
            )
        }

        throw NoStreamAvailableException(lastReason ?: "no client produced a stream")
    }.onFailure { Timber.w(it, "Stream resolution failed for %s", videoId) }

    private fun pickFormat(
        response: PlayerResponse,
        quality: WearAudioQuality,
        isMetered: Boolean,
    ): PlayerResponse.StreamingData.Format? {
        val audioFormats = response.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        // A watch streaming over LTE or a phone hotspot should not pull 256 kbps
        // Opus; AUTO therefore biases low on metered links.
        val preferHighBitrate = when (quality) {
            WearAudioQuality.HIGH -> true
            WearAudioQuality.LOW -> false
            WearAudioQuality.AUTO -> !isMetered
        }

        return audioFormats.maxByOrNull { format ->
            val direction = if (preferHighBitrate) 1 else -1
            // Opus decodes cheaper than AAC on Wear silicon, so nudge webm ahead
            // of an equivalent m4a.
            format.bitrate * direction + (if (format.mimeType.startsWith("audio/webm")) 10_240 else 0)
        }
    }

    class NoStreamAvailableException(message: String) : Exception(message)
}
