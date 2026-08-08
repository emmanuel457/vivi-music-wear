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
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Watch
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.music.vivi.connect.RelayState
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
    val devices by ConnectBridge.devices().collectAsState()
    val running by ConnectBridge.runningState().collectAsState()
    val identityMissing by ConnectBridge.identityMissing.collectAsState()
    val status by ConnectBridge.status().collectAsState()

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
                                ConnectBridge.dispatchTransport(SyncPaths.CMD_PREVIOUS)
                            }) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                            }
                            IconButton(onClick = {
                                ConnectBridge.dispatchTransport(SyncPaths.CMD_TOGGLE)
                            }) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = "Play or pause")
                            }
                            IconButton(onClick = {
                                ConnectBridge.dispatchTransport(SyncPaths.CMD_NEXT)
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

                // Without this, "saw nothing" and "saw it and threw it away"
                // are indistinguishable from a screenshot, which is exactly
                // where the last two rounds of debugging got stuck.
                if (running) {
                    item {
                        Text(
                            text = buildString {
                                append(if (status.advertising) "Advertising" else "Not advertising")
                                append(" · ")
                                append(if (status.discovering) "Discovering" else "Not discovering")
                                append(" · seen ").append(status.servicesSeen)
                                if (status.rejectedDifferentAccount > 0) {
                                    append(" · ").append(status.rejectedDifferentAccount)
                                        .append(" on another account")
                                }
                                if (status.resolveFailures > 0) {
                                    append(" · ").append(status.resolveFailures)
                                        .append(" unresolved")
                                }
                                status.lastError?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
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
                            // Chosen from what the device actually is, not from whether the row

                            // happens to be this device.

                            imageVector = when (device.kind) {
                                com.music.vivi.connect.DeviceKind.WATCH -> Icons.Rounded.Watch
                                com.music.vivi.connect.DeviceKind.TABLET -> Icons.Rounded.TabletAndroid
                                com.music.vivi.connect.DeviceKind.FOLDABLE -> Icons.Rounded.Smartphone
                                else -> Icons.Rounded.PhoneAndroid
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
                            ConnectBridge.transferPlaybackTo(device)
                        },
                )
            }

            item {
                // Polled rather than read once. sessionDiagnostics() is a plain
                // function over mutable fields, so composing it read a snapshot
                // that never refreshed — the line on screen could be minutes
                // stale, which is worse than no line at all when it is the only
                // thing being used to diagnose.
                var diagnostics by remember { mutableStateOf(ConnectBridge.sessionDiagnostics()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        diagnostics = ConnectBridge.sessionDiagnostics()
                        kotlinx.coroutines.delay(1000)
                    }
                }
                Text(
                    text = diagnostics,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )


            }



            item { RelaySection() }
        }
    }
}

/**
 * The opt-in internet fallback.
 *
 * Kept visually separate and off by default because it is the one part of
 * Connect that leaves your network: it relays through the Listen Together
 * servers this app already ships, which are run by the upstream author rather
 * than by you.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun RelaySection() {
    val context = LocalContext.current
    val relayState by ConnectBridge.relayState.collectAsState()
    val roomCode by ConnectBridge.relayRoomCode.collectAsState()
    var codeInput by remember { mutableStateOf("") }
    var showJoin by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp)) {
        HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Control over the internet",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = when (relayState) {
                        RelayState.OFF ->
                            "Off. Devices only find each other on the same Wi-Fi."
                        RelayState.CONNECTING -> "Connecting…"
                        RelayState.HOSTING ->
                            "On. Other devices can join with the code below."
                        RelayState.JOINED -> "On. Connected to your other device."
                        RelayState.FAILED -> "Couldn't reach the relay server. Retrying…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = relayState != RelayState.OFF,
                onCheckedChange = { enabled ->
                    if (enabled) ConnectBridge.startRelay(context, null)
                    else ConnectBridge.stopRelay(context)
                },
            )
        }

        if (relayState == RelayState.OFF) {
            Text(
                text = "Relays playback state through a public Listen Together " +
                    "server so your devices can reach each other from anywhere. " +
                    "Leave this off if you only use them at home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // Room codes are issued by the server, so a device cannot work out which
        // room to join on its own. This one-time handover is what replaces the
        // mDNS fingerprint the LAN transport matches on.
        roomCode?.let { code ->
            Text(
                text = "Pairing code",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = code,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Enter this on your other device once. Both reconnect on " +
                    "their own after that.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (relayState != RelayState.OFF) {
            TextButton(
                onClick = { showJoin = !showJoin },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(if (showJoin) "Cancel" else "Join with a code instead")
            }
        }

        if (showJoin) {
            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it.uppercase().trim() },
                label = { Text("Pairing code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    ConnectBridge.startRelay(context, codeInput)
                    showJoin = false
                },
                enabled = codeInput.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Join")
            }
        }
    }
}

// Transfer lives in ConnectBridge.transferPlaybackTo, which targets one device.
// The version that used to live here broadcast the queue to every peer, so with
// three devices it started playback on all of them at once.
