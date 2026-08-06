/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlaylistPlay
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

@UnstableApi
@Composable
fun LibraryScreen(navController: NavHostController) {
    val listState = rememberScalingLazyListState()
    val library by WearGraph.library.snapshot.collectAsStateWithLifecycle()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { ListHeader { Text(stringResource(R.string.library)) } }

            item {
                SectionButton(
                    label = stringResource(R.string.liked_songs),
                    secondaryLabel = library.likedSongs.size.toString(),
                    icon = Icons.Rounded.Favorite,
                    onClick = { navController.navigate(Routes.LIKED) },
                )
            }
            item {
                SectionButton(
                    label = stringResource(R.string.recent),
                    secondaryLabel = library.recentSongs.size.toString(),
                    icon = Icons.Rounded.History,
                    onClick = { navController.navigate(Routes.RECENT) },
                )
            }
            item {
                SectionButton(
                    label = stringResource(R.string.downloads),
                    secondaryLabel = library.downloadedSongs.size.toString(),
                    icon = Icons.Rounded.DownloadForOffline,
                    onClick = { navController.navigate(Routes.DOWNLOADS) },
                )
            }

            if (library.playlists.isNotEmpty()) {
                item { ListHeader { Text(stringResource(R.string.playlists)) } }
                items(library.playlists, key = { it.id }) { playlist ->
                    SectionButton(
                        label = playlist.name,
                        secondaryLabel = "${playlist.songCount}",
                        icon = Icons.Rounded.PlaylistPlay,
                        onClick = { navController.navigate(Routes.playlist(playlist.id)) },
                    )
                }
            }

            if (library.likedSongs.isEmpty() && library.playlists.isEmpty()) {
                item { StatusMessage(stringResource(R.string.empty_library)) }
            }
        }
    }
}
