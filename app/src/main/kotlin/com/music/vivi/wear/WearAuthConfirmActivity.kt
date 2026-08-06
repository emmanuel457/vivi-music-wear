/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.music.vivi.constants.AccountChannelHandleKey
import com.music.vivi.constants.AccountEmailKey
import com.music.vivi.constants.AccountNameKey
import com.music.vivi.constants.DataSyncIdKey
import com.music.vivi.constants.InnerTubeCookieKey
import com.music.vivi.constants.VisitorDataKey
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.getAsync
import com.music.vivi.wearsync.AuthPayload
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncKeys
import com.music.vivi.wearsync.SyncPaths
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Confirms handing the signed-in YouTube session to a paired watch.
 *
 * This exists because the watch's request arrives over the Data Layer with no
 * human in the loop. The payload is an account cookie: even though the Data
 * Layer only reaches devices paired to this phone and running an APK with our
 * signature, releasing it silently would mean a compromised or borrowed watch
 * could take the account without the owner ever seeing it. So the phone asks.
 */
@AndroidEntryPoint
class WearAuthConfirmActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
        if (nodeId.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                ConfirmScreen(
                    onApprove = {
                        scope.launch { sendAuth() }
                        finish()
                    },
                    onDeny = { finish() },
                )
            }
        }
    }

    private suspend fun sendAuth() {
        val store = dataStore
        val payload = AuthPayload(
            cookie = store.getAsync(InnerTubeCookieKey),
            visitorData = store.getAsync(VisitorDataKey),
            dataSyncId = store.getAsync(DataSyncIdKey),
            accountName = store.getAsync(AccountNameKey),
            accountEmail = store.getAsync(AccountEmailKey),
            accountChannelHandle = store.getAsync(AccountChannelHandleKey),
            issuedAtEpochMs = System.currentTimeMillis(),
        )

        runCatching {
            val request = PutDataMapRequest.create(SyncPaths.STATE_AUTH).apply {
                dataMap.putByteArray(SyncKeys.PAYLOAD, SyncCodec.encode(payload))
                dataMap.putLong(SyncKeys.REVISION, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(applicationContext).putDataItem(request).await()
            Timber.i("Account handed to watch")
        }.onFailure { Timber.w(it, "Account handoff failed") }
    }

    companion object {
        const val EXTRA_NODE_ID = "nodeId"
    }
}

@Composable
private fun ConfirmScreen(
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Text(
                text = "Sign in on your watch?",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Your paired watch is asking to use this YouTube Music " +
                    "account. Only approve this if you started the request on " +
                    "your own watch.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDeny, modifier = Modifier.weight(1f)) {
                    Text("Not now")
                }
                Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                    Text("Approve")
                }
            }
        }
    }
}
