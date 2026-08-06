/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.music.vivi.wear.R
import com.music.vivi.wear.WearGraph
import kotlinx.coroutines.launch

/**
 * Account handoff.
 *
 * The watch cannot complete a Google sign-in on its own, and asking a user to
 * type a password on a watch is not a real option. It asks the phone instead,
 * and the phone prompts before releasing anything.
 */
@UnstableApi
@Composable
fun SetupScreen(navController: NavHostController) {
    val signedIn by WearGraph.session.signedIn.collectAsStateWithLifecycle()
    val phoneReachable by WearGraph.phoneLink.phoneReachable.collectAsStateWithLifecycle()
    var requested by remember { mutableStateOf(false) }

    // The phone answers asynchronously, whenever the user taps Approve there.
    LaunchedEffect(signedIn) {
        if (signedIn) navController.popBackStack()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = when {
                !phoneReachable -> stringResource(R.string.phone_not_connected)
                requested -> stringResource(R.string.setup_waiting)
                else -> stringResource(R.string.setup_body)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                WearGraph.requestAccount()
                requested = true
            },
            enabled = phoneReachable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setup_request))
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                WearGraph.scope.launch { WearGraph.prefs.setSetupComplete(true) }
                navController.popBackStack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setup_skip))
        }
    }
}
