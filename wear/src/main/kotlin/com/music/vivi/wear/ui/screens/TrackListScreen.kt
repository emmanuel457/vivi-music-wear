/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.music.vivi.wear.R
import com.music.vivi.wear.WearGraph
import com.music.vivi.wear.ui.Routes
import com.music.vivi.wear.ui.components.SectionButton
import com.music.vivi.wear.ui.components.StatusMessage
import com.music.vivi.wear.ui.components.TrackRow
import com.music.vivi.wearsync.WearTrack

/** Which slice of the synced library a [TrackListScreen] is showing. */
enum class TrackListSource { LIKED, RECENT, DOWNLOADS }

@UnstableApi
@Composable
fun TrackListScreen(
    source: TrackListSource,
    navController: NavHostController,
) {
    val library by WearGraph.library.snapshot.collectAsStateWithLifecycle()

    val tracks = when (source) {
        TrackListSource.LIKED -> library.likedSongs
        TrackListSource.RECENT -> library.recentSongs
        TrackListSource.DOWNLOADS -> library.downloadedSongs
    }
    val titleRes = when (source) {
        TrackListSource.LIKED -> R.string.liked_songs
        TrackListSource.RECENT -> R.string.recent
        TrackListSource.DOWNLOADS -> R.string.downloads
    }

    TrackListContent(
        title = stringResource(titleRes),
        tracks = tracks,
        navController = navController,
    )
}

@UnstableApi
@Composable
fun TrackListContent(
    title: String,
    tracks: List<WearTrack>,
    navController: NavHostController,
    isLoading: Boolean = false,
) {
    val listState = rememberScalingLazyListState()
    val playback by WearGraph.router.state.collectAsStateWithLifecycle()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { ListHeader { Text(title) } }

            when {
                isLoading && tracks.isEmpty() -> item { StatusMessage(stringResource(R.string.loading)) }
                tracks.isEmpty() -> item { StatusMessage(stringResource(R.string.empty_library)) }
                else -> {
                    item {
                        SectionButton(
                            label = stringResource(R.string.cd_play),
                            icon = Icons.Rounded.PlayArrow,
                            onClick = {
                                WearGraph.router.play(tracks, 0, title)
                                navController.navigate(Routes.NOW_PLAYING)
                            },
                        )
                    }
                    item {
                        SectionButton(
                            label = stringResource(R.string.cd_shuffle),
                            icon = Icons.Rounded.Shuffle,
                            onClick = {
                                val shuffled = tracks.shuffled()
                                WearGraph.router.play(shuffled, 0, title)
                                navController.navigate(Routes.NOW_PLAYING)
                            },
                        )
                    }
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            isPlaying = playback.track?.id == track.id,
                            onClick = {
                                WearGraph.router.play(
                                    tracks = tracks,
                                    startIndex = tracks.indexOfFirst { it.id == track.id }
                                        .coerceAtLeast(0),
                                    queueTitle = title,
                                )
                                navController.navigate(Routes.NOW_PLAYING)
                            },
                        )
                    }
                }
            }
        }
    }
}
