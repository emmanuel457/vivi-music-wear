/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Pulls a representative colour out of the current track's artwork.
 *
 * Thumbnails are fetched at a deliberately tiny size — Palette quantises the
 * whole bitmap on the CPU, and doing that at full resolution on watch silicon
 * is slow enough to be visible. 48 px carries more than enough colour
 * information for a hue.
 */
suspend fun extractSeedColor(context: Context, url: String?): Color? {
    if (url.isNullOrBlank()) return null
    return withContext(Dispatchers.Default) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(SAMPLE_SIZE, SAMPLE_SIZE)
                // Palette must read pixels back, which a hardware bitmap forbids.
                .allowHardware(false)
                .build()

            val result = context.imageLoader.execute(request)
            val bitmap = (result as? SuccessResult)
                ?.image
                ?.let { it as? BitmapImage }
                ?.bitmap
                ?: return@runCatching null

            val palette = Palette.from(bitmap).maximumColorCount(MAX_COLORS).generate()

            // Vibrant first: it is what the eye reads as "the colour of this
            // cover". Muted and dominant are fallbacks for monochrome sleeves,
            // where vibrant comes back null.
            val argb = palette.vibrantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: palette.mutedSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: return@runCatching null

            Color(argb)
        }.onFailure { Timber.d(it, "Could not read a seed colour from artwork") }
            .getOrNull()
    }
}

private const val SAMPLE_SIZE = 48
private const val MAX_COLORS = 16

/**
 * Tracks the seed colour for [artworkUrl], recomputing only when the URL
 * actually changes so scrolling or a position tick never triggers a decode.
 */
@Composable
fun rememberSeedColor(context: Context, artworkUrl: String?): State<Color?> {
    val seed = remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(artworkUrl) {
        seed.value = extractSeedColor(context, artworkUrl)
    }
    return seed
}
