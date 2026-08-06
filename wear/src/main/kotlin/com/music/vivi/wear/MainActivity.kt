/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import androidx.wear.compose.material3.AppScaffold
import com.music.vivi.wear.ui.ViviWearNavHost
import com.music.vivi.wear.ui.theme.ViviWearTheme

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        WearGraph.ensureInitialized(applicationContext)

        // Ask for whatever the phone has while the user is looking at the app —
        // if it is out of range these are no-ops and the cached library shows.
        WearGraph.requestSync()

        setContent { ViviWearRoot() }
    }
}

@UnstableApi
@Composable
private fun ViviWearRoot() {
    ViviWearTheme {
        AppScaffold {
            ViviWearNavHost()
        }
    }
}
