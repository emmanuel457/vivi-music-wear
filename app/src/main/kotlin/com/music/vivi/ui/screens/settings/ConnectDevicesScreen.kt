/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.music.vivi.connect.ConnectBridge
import com.music.vivi.connect.ConnectDevice
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.PlayTracksCommand
import com.music.vivi.wearsync.SyncPaths
import com.music.vivi.wear.WearBridge

/**
 * Vivi Connect device picker: what Spotify's "Devices available" sheet does,
 * over the local network instead of Spotify's servers.
 *
 * Every device running Vivi on this Wi-Fi and signed into the same account
 * appears here. Tapping one hands playback to it; the transport row drives
 * whichever device currently holds the audio.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectDevicesScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val devices by (ConnectBridge.devices()?.collectAsState()
        ?: androidx.compose.runtime.mutableStateOf(emptyList<ConnectDevice>()))
    val running by ConnectBridge.runningState().collectAsState()
    val identityMissing by ConnectBridge.identityMissing.collectAsState()

    val remote = ConnectBridge.remoteState()
    val local = WearBridge.snapshot()
    // Whichever side actually has audio is the one worth showing.
    val showing = if (local.phonePlaybackActive) local else remote

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = showing.track?.title ?: "Nothing playing",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (showing.track != null) {
                        Text(
                            text = showing.track!!.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(onClick = {
                                ConnectBridge.sendCommand(SyncPaths.CMD_PREVIOUS)
                            }) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                            }
                            IconButton(onClick = {
                                ConnectBridge.sendCommand(SyncPaths.CMD_TOGGLE)
                            }) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = "Play or pause")
                            }
                            IconButton(onClick = {
                                ConnectBridge.sendCommand(SyncPaths.CMD_NEXT)
                            }) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            if (devices.size <= 1) {
                item {
                    // Three distinct states. Collapsing them into one message is
                    // what made the last failure impossible to diagnose from a
                    // screenshot: a running-but-lonely device and a device that
                    // never started looked identical.
                    val message = when {
                        running -> "Looking for devices… Open Vivi Music on your " +
                            "other device, signed into the same account and on " +
                            "this Wi-Fi. Some guest and office networks block the " +
                            "discovery Vivi Connect uses."
                        identityMissing -> "Waiting for your account. Vivi Connect " +
                            "authenticates devices using your account so playback " +
                            "can't be controlled by strangers on the same network. " +
                            "Sign in, then come back."
                        else -> "Vivi Connect couldn't open a network listener on " +
                            "this device."
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            items(devices, key = { it.id }) { device ->
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = {
                        Text(
                            when {
                                device.isSelf -> "This device"
                                device.connected -> "Connected"
                                else -> "Available"
                            }
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (device.isSelf) {
                                Icons.Rounded.PhoneAndroid
                            } else {
                                Icons.Rounded.Tablet
                            },
                            contentDescription = null,
                            tint = if (device.isPlaying) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !device.isSelf) {
                            transferTo()
                        },
                )
            }
        }
    }
}

/**
 * Hands the current queue to the peer.
 *
 * Sends the tracks themselves rather than ids: the target may never have seen
 * them, and making it re-resolve every id over the network would add seconds of
 * silence to the handover.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun transferTo() {
    val state = WearBridge.snapshot()
    val track = state.track ?: return
    ConnectBridge.sendCommand(
        SyncPaths.CMD_PLAY_TRACKS,
        SyncCodec.encode(
            PlayTracksCommand(
                tracks = listOf(track),
                startIndex = 0,
                queueTitle = state.queueTitle,
            )
        ),
    )
    // The peer pauses us via NOTIFY_WATCH_PLAYING once it starts, so we do not
    // stop here -- doing both would race and could leave nothing playing.
}
