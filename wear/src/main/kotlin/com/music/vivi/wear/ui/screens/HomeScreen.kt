/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.vivi.wear.R
import com.music.vivi.wear.WearGraph
import com.music.vivi.wear.playback.toWearTrack
import com.music.vivi.wear.ui.Routes
import com.music.vivi.wear.ui.components.LoadingRow
import com.music.vivi.wear.ui.components.NowPlayingCard
import com.music.vivi.wear.ui.components.SectionButton
import com.music.vivi.wear.ui.components.StatusMessage
import com.music.vivi.wear.ui.components.TrackRow
import com.music.vivi.wearsync.WearTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@UnstableApi
@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: HomeViewModel = viewModel(),
) {
    val listState = rememberScalingLazyListState()
    val library by WearGraph.library.snapshot.collectAsStateWithLifecycle()
    val playback by WearGraph.router.state.collectAsStateWithLifecycle()
    val quickPicks by viewModel.quickPicks.collectAsStateWithLifecycle()
    val quickPicksLoading by viewModel.loading.collectAsStateWithLifecycle()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // The one thing a user opens a watch music app to do is get back to
            // what is already playing, so it goes above everything else.
            if (playback.track != null) {
                item {
                    NowPlayingCard(
                        state = playback,
                        onClick = { navController.navigate(Routes.NOW_PLAYING) },
                        onTogglePlayPause = { WearGraph.router.togglePlayPause() },
                    )
                }
            }

            item {
                SectionButton(
                    label = stringResource(R.string.search),
                    icon = Icons.Rounded.Search,
                    onClick = { navController.navigate(Routes.SEARCH) },
                )
            }

            item {
                SectionButton(
                    label = stringResource(R.string.library),
                    icon = Icons.Rounded.LibraryMusic,
                    onClick = { navController.navigate(Routes.LIBRARY) },
                )
            }

            if (library.recentSongs.isNotEmpty()) {
                item { ListHeader { Text(stringResource(R.string.recent)) } }
                items(library.recentSongs.take(RECENT_ON_HOME), key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isPlaying = playback.track?.id == track.id,
                        onClick = {
                            WearGraph.router.play(
                                tracks = library.recentSongs,
                                startIndex = library.recentSongs.indexOfFirst { it.id == track.id }
                                    .coerceAtLeast(0),
                                queueTitle = null,
                            )
                            navController.navigate(Routes.NOW_PLAYING)
                        },
                    )
                }
            }

            item { ListHeader { Text(stringResource(R.string.quick_picks)) } }
            when {
                quickPicksLoading && quickPicks.isEmpty() -> item { LoadingRow() }
                quickPicks.isEmpty() -> item {
                    StatusMessage(stringResource(R.string.empty_library))
                }
                else -> items(quickPicks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isPlaying = playback.track?.id == track.id,
                        onClick = {
                            WearGraph.router.play(
                                tracks = quickPicks,
                                startIndex = quickPicks.indexOfFirst { it.id == track.id }
                                    .coerceAtLeast(0),
                                queueTitle = null,
                            )
                            navController.navigate(Routes.NOW_PLAYING)
                        },
                    )
                }
            }

            item {
                SectionButton(
                    label = stringResource(R.string.settings),
                    icon = Icons.Rounded.Settings,
                    onClick = { navController.navigate(Routes.SETTINGS) },
                )
            }
        }
    }
}

private const val RECENT_ON_HOME = 5

class HomeViewModel : ViewModel() {

    private val _quickPicks = MutableStateFlow<List<WearTrack>>(emptyList())
    val quickPicks: StateFlow<List<WearTrack>> = _quickPicks.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            YouTube.home()
                .onSuccess { page ->
                    // The home feed is a wall of carousels; a watch has room for
                    // one flat list, so we take songs off the top sections only.
                    _quickPicks.value = page.sections
                        .asSequence()
                        .flatMap { it.items.asSequence() }
                        .filterIsInstance<SongItem>()
                        .distinctBy { it.id }
                        .take(QUICK_PICK_LIMIT)
                        .map { it.toWearTrack() }
                        .toList()
                }
                .onFailure { Timber.w(it, "Quick picks unavailable") }
            _loading.value = false
        }
    }

    private companion object {
        const val QUICK_PICK_LIMIT = 20
    }
}
