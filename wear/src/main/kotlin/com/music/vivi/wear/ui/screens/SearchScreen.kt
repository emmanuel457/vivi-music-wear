/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.screens

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.wear.input.RemoteInputIntentHelper
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.vivi.wear.R
import com.music.vivi.wear.WearGraph
import com.music.vivi.wear.playback.toWearTrack
import com.music.vivi.wear.ui.Routes
import com.music.vivi.wear.ui.components.LoadingRow
import com.music.vivi.wear.ui.components.SectionButton
import com.music.vivi.wear.ui.components.StatusMessage
import com.music.vivi.wear.ui.components.TrackRow
import com.music.vivi.wearsync.WearTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

private const val REMOTE_INPUT_KEY = "vivi_search_query"

@UnstableApi
@Composable
fun SearchScreen(
    navController: NavHostController,
    viewModel: SearchViewModel = viewModel(),
) {
    val listState = rememberScalingLazyListState()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val playback by WearGraph.router.state.collectAsStateWithLifecycle()

    // Wear has no on-screen keyboard a Compose TextField can use. RemoteInput is
    // the platform path: it hands off to the system's voice / handwriting / tiny
    // keyboard picker and returns the finished string.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = result.data
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(REMOTE_INPUT_KEY)
            ?.toString()
            .orEmpty()
        if (text.isNotBlank()) viewModel.search(text)
    }

    fun launchInput() {
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent,
            listOf(
                RemoteInput.Builder(REMOTE_INPUT_KEY)
                    .setLabel("Search")
                    .build()
            ),
        )
        launcher.launch(intent)
    }

    // Open the picker straight away — the user tapped "Search" to type, not to
    // look at an empty screen with another button on it.
    LaunchedEffect(Unit) {
        if (query.isBlank()) launchInput()
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                ListHeader { Text(query.ifBlank { stringResource(R.string.search) }) }
            }

            item {
                SectionButton(
                    label = stringResource(R.string.search),
                    icon = Icons.Rounded.Mic,
                    onClick = ::launchInput,
                )
            }

            when {
                loading -> item { LoadingRow() }
                query.isBlank() -> Unit
                results.isEmpty() -> item { StatusMessage(stringResource(R.string.no_results)) }
                else -> items(results, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isPlaying = playback.track?.id == track.id,
                        onClick = {
                            WearGraph.router.play(
                                tracks = results,
                                startIndex = results.indexOfFirst { it.id == track.id }
                                    .coerceAtLeast(0),
                                queueTitle = query,
                            )
                            navController.navigate(Routes.NOW_PLAYING)
                        },
                    )
                }
            }
        }
    }
}

class SearchViewModel : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<WearTrack>>(emptyList())
    val results: StateFlow<List<WearTrack>> = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun search(text: String) {
        _query.value = text
        viewModelScope.launch {
            _loading.value = true
            // Songs only. A watch has no room for the album/artist/video tabs,
            // and every one of them is another tap away from hearing music.
            YouTube.search(text, YouTube.SearchFilter.FILTER_SONG)
                .onSuccess { result ->
                    _results.value = result.items
                        .filterIsInstance<SongItem>()
                        .map { it.toWearTrack() }
                }
                .onFailure {
                    Timber.w(it, "Search failed")
                    _results.value = emptyList()
                }
            _loading.value = false
        }
    }
}
