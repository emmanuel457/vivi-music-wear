/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** Fallback seed used before any artwork has been read. */
private val DefaultSeed = Color(0xFF5B7BD5)

/**
 * Builds a Wear colour scheme from a single seed colour.
 *
 * Rather than sampling tones off the artwork directly, this keeps only the
 * seed's *hue* and pins every lightness value itself. Album art is arbitrary —
 * a washed-out sleeve or a near-white one would otherwise produce pale
 * containers with pale text, which is exactly the unreadable state this
 * replaces. Fixing lightness means contrast is guaranteed for any input, and
 * the artwork only ever influences colour temperature.
 *
 * Containers stay dark and content stays light throughout: on an OLED watch a
 * light container is both harder to read outdoors and more expensive to light.
 */
private fun schemeFromSeed(seed: Color): ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    val hue = hsv[0]
    // Heavily saturated artwork would tint the whole watch face; cap it.
    val chroma = hsv[1].coerceIn(0.15f, 0.55f)

    fun tone(saturation: Float, value: Float) =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))

    return ColorScheme(
        // Accent: light and legible against black, used sparingly.
        primary = tone(chroma * 0.55f, 0.94f),
        onPrimary = tone(chroma, 0.20f),
        primaryContainer = tone(chroma * 0.80f, 0.34f),
        onPrimaryContainer = tone(chroma * 0.25f, 0.96f),

        secondary = tone(chroma * 0.35f, 0.86f),
        onSecondary = tone(chroma, 0.18f),
        secondaryContainer = tone(chroma * 0.60f, 0.28f),
        onSecondaryContainer = tone(chroma * 0.20f, 0.94f),

        tertiary = tone(chroma * 0.45f, 0.88f),
        onTertiary = tone(chroma, 0.20f),

        // List rows live on these. Dark, faintly tinted by the artwork.
        surfaceContainerLow = tone(chroma * 0.30f, 0.11f),
        surfaceContainer = tone(chroma * 0.30f, 0.17f),
        surfaceContainerHigh = tone(chroma * 0.30f, 0.25f),

        onSurface = tone(chroma * 0.06f, 0.98f),
        // 0.82 value against a 0.17 container clears WCAG AA at body sizes,
        // which the old light-grey-on-light-blue secondary label did not.
        onSurfaceVariant = tone(chroma * 0.18f, 0.82f),

        background = Color.Black,
        onBackground = tone(chroma * 0.06f, 0.98f),

        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )
}

/**
 * @param seedColor dominant colour of the current track's artwork, or null
 *   before any has been extracted.
 */
@Composable
fun ViviWearTheme(
    seedColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val target = seedColor ?: DefaultSeed
    // Cross-fade between tracks. Snapping the whole UI to a new hue on every
    // song change is jarring on a screen this small.
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 600),
        label = "themeSeed",
    )
    val scheme = remember(animated) { schemeFromSeed(animated) }

    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
