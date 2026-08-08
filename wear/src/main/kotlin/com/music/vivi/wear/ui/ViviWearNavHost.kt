/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.music.vivi.wear.ui.screens.HomeScreen
import com.music.vivi.wear.ui.screens.LibraryScreen
import com.music.vivi.wear.ui.screens.NowPlayingScreen
import com.music.vivi.wear.ui.screens.PlaylistScreen
import com.music.vivi.wear.ui.screens.QueueScreen
import com.music.vivi.wear.ui.screens.SearchScreen
import com.music.vivi.wear.ui.screens.SettingsScreen
import com.music.vivi.wear.ui.screens.SetupScreen
import com.music.vivi.wear.ui.screens.TrackListScreen
import com.music.vivi.wear.ui.screens.TrackListSource

object Routes {
    const val HOME = "home"
    const val NOW_PLAYING = "now_playing"
    const val QUEUE = "queue"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val SETUP = "setup"

    const val LIKED = "liked"
    const val RECENT = "recent"
    const val DOWNLOADS = "downloads"

    const val PLAYLIST_ARG = "playlistId"
    const val PLAYLIST_PATTERN = "playlist/{$PLAYLIST_ARG}"

    fun playlist(id: String) = "playlist/$id"
}

@UnstableApi
@Composable
fun ViviWearNavHost(
    navController: NavHostController = rememberSwipeDismissableNavController(),
) {
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = Routes.HOME,
    ) {
        composable(Routes.HOME) { HomeScreen(navController) }
        composable(Routes.NOW_PLAYING) { NowPlayingScreen(navController) }
        composable(Routes.QUEUE) { QueueScreen() }
        composable(Routes.LIBRARY) { LibraryScreen(navController) }
        composable(Routes.SEARCH) { SearchScreen(navController) }
        composable(Routes.SETTINGS) { SettingsScreen(navController) }
        composable(Routes.SETUP) { SetupScreen(navController) }

        composable(Routes.LIKED) { TrackListScreen(TrackListSource.LIKED, navController) }
        composable(Routes.RECENT) { TrackListScreen(TrackListSource.RECENT, navController) }
        composable(Routes.DOWNLOADS) { TrackListScreen(TrackListSource.DOWNLOADS, navController) }

        composable(
            route = Routes.PLAYLIST_PATTERN,
            arguments = listOf(navArgument(Routes.PLAYLIST_ARG) { type = NavType.StringType }),
        ) { entry ->
            PlaylistScreen(
                playlistId = entry.arguments?.getString(Routes.PLAYLIST_ARG).orEmpty(),
                navController = navController,
            )
        }
    }
}
