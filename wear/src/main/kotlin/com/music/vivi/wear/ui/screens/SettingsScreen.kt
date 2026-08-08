/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.music.vivi.wear.R
import com.music.vivi.wear.WearGraph
import com.music.vivi.wear.data.PlaybackRoute
import com.music.vivi.wear.data.WearAudioQuality
import com.music.vivi.wear.ui.Routes
import com.music.vivi.wear.ui.components.SectionButton
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@UnstableApi
@Composable
fun SettingsScreen(navController: NavHostController) {
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    val signedIn by WearGraph.session.signedIn.collectAsStateWithLifecycle()
    val accountName by WearGraph.session.accountName.collectAsStateWithLifecycle()
    val lastSynced by WearGraph.prefs.lastSyncedAt.collectAsStateWithLifecycle(0L)
    val route by WearGraph.prefs.route.collectAsStateWithLifecycle(PlaybackRoute.AUTO)
    val quality by WearGraph.prefs.audioQuality.collectAsStateWithLifecycle(WearAudioQuality.AUTO)
    val phoneReachable by WearGraph.phoneLink.phoneReachable.collectAsStateWithLifecycle()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { ListHeader { Text(stringResource(R.string.settings)) } }

            item {
                SectionButton(
                    label = when {
                        // The phone can hand over a valid cookie with no display
                        // name attached, which rendered as a bare "Signed in as"
                        // followed by nothing.
                        signedIn && !accountName.isNullOrBlank() ->
                            stringResource(R.string.signed_in_as, accountName!!)
                        signedIn -> stringResource(R.string.signed_in)
                        else -> stringResource(R.string.not_signed_in)
                    },
                    secondaryLabel = if (!signedIn && phoneReachable) {
                        stringResource(R.string.setup_request)
                    } else if (!phoneReachable) {
                        stringResource(R.string.phone_not_connected)
                    } else {
                        null
                    },
                    icon = Icons.Rounded.AccountCircle,
                    onClick = {
                        if (!signedIn) navController.navigate(Routes.SETUP)
                    },
                )
            }

            item {
                SectionButton(
                    label = stringResource(R.string.sync_now),
                    secondaryLabel = if (lastSynced > 0L) {
                        stringResource(
                            R.string.last_synced,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastSynced)),
                        )
                    } else {
                        stringResource(R.string.sync_never)
                    },
                    icon = Icons.Rounded.Sync,
                    onClick = { WearGraph.requestSync() },
                )
            }

            item { ListHeader { Text(stringResource(R.string.play_on)) } }

            item {
                SectionButton(
                    label = when (route) {
                        PlaybackRoute.AUTO -> stringResource(R.string.quality_auto)
                        PlaybackRoute.WATCH -> stringResource(R.string.this_watch)
                        PlaybackRoute.PHONE -> stringResource(R.string.phone)
                    },
                    icon = if (route == PlaybackRoute.PHONE) {
                        Icons.Rounded.Smartphone
                    } else {
                        Icons.Rounded.Watch
                    },
                    onClick = {
                        val next = when (route) {
                            PlaybackRoute.AUTO -> PlaybackRoute.WATCH
                            PlaybackRoute.WATCH -> PlaybackRoute.PHONE
                            PlaybackRoute.PHONE -> PlaybackRoute.AUTO
                        }
                        scope.launch { WearGraph.router.setPreferredRoute(next) }
                    },
                )
            }

            item { ListHeader { Text(stringResource(R.string.audio_quality)) } }

            item {
                SectionButton(
                    label = when (quality) {
                        WearAudioQuality.AUTO -> stringResource(R.string.quality_auto)
                        WearAudioQuality.HIGH -> stringResource(R.string.quality_high)
                        WearAudioQuality.LOW -> stringResource(R.string.quality_low)
                    },
                    icon = Icons.Rounded.GraphicEq,
                    onClick = {
                        val next = when (quality) {
                            WearAudioQuality.AUTO -> WearAudioQuality.HIGH
                            WearAudioQuality.HIGH -> WearAudioQuality.LOW
                            WearAudioQuality.LOW -> WearAudioQuality.AUTO
                        }
                        scope.launch { WearGraph.prefs.setAudioQuality(next) }
                    },
                )
            }

            if (signedIn) {
                item {
                    SectionButton(
                        label = stringResource(R.string.sign_out),
                        icon = Icons.Rounded.Logout,
                        onClick = {
                            scope.launch {
                                WearGraph.session.signOut()
                                WearGraph.library.clear()
                            }
                        },
                    )
                }
            }
        }
    }
}
