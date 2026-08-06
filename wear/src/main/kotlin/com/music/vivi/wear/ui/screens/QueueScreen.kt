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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.music.vivi.wear.R
import com.music.vivi.wear.WearGraph
import com.music.vivi.wear.playback.ActiveRoute
import com.music.vivi.wear.ui.components.StatusMessage
import com.music.vivi.wear.ui.components.TrackRow

@UnstableApi
@Composable
fun QueueScreen() {
    val state by WearGraph.router.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState(
        // Open on the current track rather than the top; a 40-song queue with
        // the playing item off-screen is useless.
        initialCenterItemIndex = (state.queueIndex + 1).coerceAtLeast(0),
    )

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                ListHeader { Text(state.queueTitle ?: stringResource(R.string.queue)) }
            }

            if (state.queue.isEmpty()) {
                item { StatusMessage(stringResource(R.string.nothing_playing)) }
            } else {
                itemsIndexed(state.queue, key = { index, track -> "$index:${track.id}" }) { index, track ->
                    TrackRow(
                        track = track,
                        isPlaying = index == state.queueIndex,
                        onClick = {
                            if (state.route == ActiveRoute.WATCH) {
                                WearGraph.router.seekToQueueIndex(index)
                            } else {
                                // The phone owns its own queue; re-issuing the
                                // list is the only way to jump within it.
                                WearGraph.router.play(state.queue, index, state.queueTitle)
                            }
                        },
                    )
                }
            }
        }
    }
}
