/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Vivi Music's palette, darkened for an always-black OLED watch face.
 *
 * Wear has no light theme worth supporting — every pixel that isn't black costs
 * battery on these panels, so backgrounds stay at true black rather than the
 * elevated greys the phone app uses.
 */
private val ViviColors = ColorScheme(
    primary = Color(0xFFB3C5FF),
    onPrimary = Color(0xFF00287A),
    primaryContainer = Color(0xFF1B3B8F),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFC1C5DD),
    onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF414659),
    onSecondaryContainer = Color(0xFFDDE1F9),
    tertiary = Color(0xFFE3BADA),
    onTertiary = Color(0xFF432740),
    surfaceContainerLow = Color(0xFF101318),
    surfaceContainer = Color(0xFF1A1C22),
    surfaceContainerHigh = Color(0xFF25272E),
    onSurface = Color(0xFFE3E2E9),
    onSurfaceVariant = Color(0xFFC5C6D0),
    background = Color.Black,
    onBackground = Color(0xFFE3E2E9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun ViviWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ViviColors,
        content = content,
    )
}
