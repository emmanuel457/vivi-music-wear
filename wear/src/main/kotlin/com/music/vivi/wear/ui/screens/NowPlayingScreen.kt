/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
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
import com.music.vivi.wear.data.PlaybackRoute
import com.music.vivi.wear.playback.ActiveRoute
import com.music.vivi.wear.ui.Routes
import com.music.vivi.wear.ui.components.StatusMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Now Playing, laid out like the system media control rather than a scrolling
 * list.
 *
 * The previous version stacked eight elements in a Column — route badge,
 * artwork, title, artist, a linear progress bar, timestamps, transport, and a
 * secondary control row — which overflowed a round screen and clipped the
 * bottom row entirely. This keeps four bands: metadata, transport, secondary
 * actions, and the artwork promoted to a blurred backdrop so the cover reads
 * large without consuming a band of its own. Progress moved onto an arc around
 * the play button, which removes the bar and the timestamps from the stack.
 */
@UnstableApi
@Composable
fun NowPlayingScreen(navController: NavHostController) {
    val state by WearGraph.router.state.collectAsStateWithLifecycle()
    val route by WearGraph.prefs.route.collectAsStateWithLifecycle(PlaybackRoute.AUTO)
    val scope = rememberCoroutineScope()
    var positionMs by remember { mutableLongStateOf(0L) }

    // Ticking only while this screen is composed and audio is running keeps the
    // watch out of a 1 Hz wake loop whenever the user is anywhere else.
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
                    .blur(20.dp),
            )
        }
        // Text over arbitrary album art is unreadable without this; the cover
        // stays visible but never competes with the controls.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
        ) {
            Text(
                text = track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
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

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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

            Spacer(Modifier.height(6.dp))

            Text(
                text = "${formatDuration(positionMs)} / ${formatDuration(state.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            // Three actions, not seven. Shuffle and repeat moved to the queue
            // screen, where the thing they reorder is actually visible.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(
                    onClick = { WearGraph.router.toggleLike() },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = if (track.liked) {
                            Icons.Rounded.Favorite
                        } else {
                            Icons.Rounded.FavoriteBorder
                        },
                        contentDescription = stringResource(R.string.cd_like),
                        tint = if (track.liked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                IconButton(
                    onClick = {
                        // Cycles where audio comes out, the watch equivalent of
                        // the output picker in the reference layout.
                        val next = when (route) {
                            PlaybackRoute.AUTO -> PlaybackRoute.WATCH
                            PlaybackRoute.WATCH -> PlaybackRoute.PHONE
                            PlaybackRoute.PHONE -> PlaybackRoute.AUTO
                        }
                        scope.launch { WearGraph.router.setPreferredRoute(next) }
                    },
                    modifier = Modifier.size(30.dp),
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

                IconButton(
                    onClick = { navController.navigate(Routes.QUEUE) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = stringResource(R.string.queue),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Play/pause with progress drawn as an arc around it.
 *
 * Folding progress into the button removes a whole band from the layout, which
 * is what let the secondary row fit back on screen.
 */
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
        Canvas(modifier = Modifier.size(60.dp)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (fraction > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
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
