/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.music.vivi.wear.R
import com.music.vivi.wear.WearGraph
import com.music.vivi.wear.data.DownloadState
import com.music.vivi.wear.data.PlaybackRoute
import com.music.vivi.wear.playback.ActiveRoute
import com.music.vivi.wear.ui.Routes
import com.music.vivi.wear.ui.components.StatusMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Now Playing, anchored like the system media control.
 *
 * Three anchored bands rather than one Column: metadata pinned to the top,
 * transport locked to the true centre of the screen, and secondary actions at
 * the bottom. A single Column let the transport drift vertically as the title
 * wrapped or the download state changed, which on a round face reads as the
 * whole UI shifting.
 */
@UnstableApi
@Composable
fun NowPlayingScreen(navController: NavHostController) {
    val state by WearGraph.router.state.collectAsStateWithLifecycle()
    val route by WearGraph.prefs.route.collectAsStateWithLifecycle(PlaybackRoute.AUTO)
    val downloads by WearGraph.downloads.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var positionMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.isPlaying, state.track?.id) {
        while (true) {
            positionMs = WearGraph.router.positionMs()
            if (!state.isPlaying) break
            delay(1000)
        }
    }

    val track = state.track
    if (track == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            StatusMessage(stringResource(R.string.nothing_playing))
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!track.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = stringResource(R.string.cd_artwork),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(18.dp),
            )
        }
        // Lighter than before: at 62% the cover was almost invisible. 48% still
        // clears text contrast while letting the artwork actually read.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
        )

        // ── Top: metadata ────────────────────────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 26.dp, start = 12.dp, end = 12.dp),
        ) {
            Text(
                text = track.title,
                maxLines = 1,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                // Long titles were ellipsised to "INTENSE GHANA PRAISE M…".
                // Scrolling shows the whole thing without stealing a second line
                // from a screen this short.
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(
                text = track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── Centre: transport ────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.align(Alignment.Center),
        ) {
            IconButton(
                onClick = { WearGraph.router.previous() },
                enabled = state.canSkipPrevious,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = stringResource(R.string.cd_previous),
                )
            }

            PlayButtonWithProgress(
                isPlaying = state.isPlaying,
                positionMs = positionMs,
                durationMs = state.durationMs,
                onClick = { WearGraph.router.togglePlayPause() },
            )

            IconButton(
                onClick = { WearGraph.router.next() },
                enabled = state.canSkipNext,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = stringResource(R.string.cd_next),
                )
            }
        }

        // ── Bottom: time, secondary actions, queue handle ────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = "${formatDuration(positionMs)} / ${formatDuration(state.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconButton(
                    onClick = { WearGraph.router.toggleLike() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (track.liked) {
                            Icons.Rounded.Favorite
                        } else {
                            Icons.Rounded.FavoriteBorder
                        },
                        // Named explicitly so it is never mistaken for download.
                        contentDescription = stringResource(R.string.cd_like),
                        tint = if (track.liked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                // Download is its own control. Liking a song never stored audio,
                // which was impossible to tell from a heart alone.
                val downloadState = downloads[track.id] ?: DownloadState.NONE
                IconButton(
                    onClick = { WearGraph.downloads.toggle(track) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = when (downloadState) {
                            DownloadState.DOWNLOADED -> Icons.Rounded.Check
                            DownloadState.DOWNLOADING -> Icons.Rounded.Downloading
                            DownloadState.NONE -> Icons.Rounded.FileDownload
                        },
                        contentDescription = stringResource(R.string.cd_download),
                        tint = when (downloadState) {
                            DownloadState.DOWNLOADED -> MaterialTheme.colorScheme.primary
                            DownloadState.DOWNLOADING -> MaterialTheme.colorScheme.secondary
                            DownloadState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                IconButton(
                    onClick = {
                        val next = when (route) {
                            PlaybackRoute.AUTO -> PlaybackRoute.WATCH
                            PlaybackRoute.WATCH -> PlaybackRoute.PHONE
                            PlaybackRoute.PHONE -> PlaybackRoute.AUTO
                        }
                        scope.launch { WearGraph.router.setPreferredRoute(next) }
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (state.route == ActiveRoute.PHONE) {
                            Icons.Rounded.Smartphone
                        } else {
                            Icons.Rounded.Watch
                        },
                        contentDescription = stringResource(R.string.cd_output),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // A handle rather than an icon. The queue is a whole screen away, so
            // it reads better as "there is more below" than as a fourth control
            // competing with the three above it.
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                    .clickable { navController.navigate(Routes.QUEUE) }
            )
        }
    }
}

@Composable
private fun PlayButtonWithProgress(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onClick: () -> Unit,
) {
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val progressColor = MaterialTheme.colorScheme.primary

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(64.dp)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (fraction > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.cd_pause else R.string.cd_play
                ),
            )
        }
    }
}

internal fun formatDuration(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
