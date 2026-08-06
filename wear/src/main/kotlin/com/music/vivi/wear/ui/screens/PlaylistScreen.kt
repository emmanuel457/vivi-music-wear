/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.music.innertube.YouTube
import com.music.vivi.wear.playback.toWearTrack
import com.music.vivi.wearsync.WearTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Playlist contents are fetched live rather than synced.
 *
 * The phone only ships playlist *names* in its snapshot — pushing every track of
 * every playlist across Bluetooth would blow past the Data Layer's limits and
 * take minutes, for data the user may never open.
 */
@UnstableApi
@Composable
fun PlaylistScreen(
    playlistId: String,
    navController: NavHostController,
    viewModel: PlaylistViewModel = viewModel(),
) {
    LaunchedEffect(playlistId) { viewModel.load(playlistId) }

    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    TrackListContent(
        title = title,
        tracks = tracks,
        navController = navController,
        isLoading = loading,
    )
}

class PlaylistViewModel : ViewModel() {

    private val _tracks = MutableStateFlow<List<WearTrack>>(emptyList())
    val tracks: StateFlow<List<WearTrack>> = _tracks.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var loadedId: String? = null

    fun load(playlistId: String) {
        if (loadedId == playlistId) return
        loadedId = playlistId
        viewModelScope.launch {
            _loading.value = true
            YouTube.playlist(playlistId)
                .onSuccess { page ->
                    _title.value = page.playlist.title
                    _tracks.value = page.songs.map { it.toWearTrack() }
                }
                .onFailure { Timber.w(it, "Playlist %s failed to load", playlistId) }
            _loading.value = false
        }
    }
}
