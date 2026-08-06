/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.data

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.music.vivi.wear.WearGraph
import com.music.vivi.wearsync.LibrarySnapshot
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Receives everything the phone pushes, including while the watch UI is closed.
 *
 * The system starts this service on delivery, so [WearGraph] may not have been
 * touched yet in this process — hence the explicit [WearGraph.ensureInitialized].
 */
class WearSyncListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        WearGraph.ensureInitialized(applicationContext)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            val path = event.dataItem.uri.path ?: continue
            if (event.type == DataEvent.TYPE_DELETED) {
                if (path == SyncPaths.STATE_NOW_PLAYING) WearGraph.phoneLink.onPhoneStateCleared()
                continue
            }
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            when (path) {
                SyncPaths.STATE_NOW_PLAYING -> WearGraph.phoneLink.applyNowPlaying(map)
                SyncPaths.STATE_AUTH -> {
                    WearGraph.phoneLink.applyAuth(map)
                    val payload = WearGraph.phoneLink.auth.value ?: continue
                    scope.launch { WearGraph.session.acceptFromPhone(payload) }
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            // The phone took playback back; stop local audio so the user isn't
            // hearing two devices at once.
            SyncPaths.NOTIFY_WATCH_STOPPED -> WearGraph.router.onPhoneReclaimedPlayback()
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != SyncPaths.CHANNEL_LIBRARY) return
        val client = Wearable.getChannelClient(this)
        scope.launch {
            runCatching {
                val stream = client.getInputStream(channel).await()
                val bytes = stream.use { it.readBytes() }
                val snapshot = SyncCodec.decode<LibrarySnapshot>(bytes)
                WearGraph.library.save(snapshot)
                WearGraph.prefs.setLastSyncedAt(System.currentTimeMillis())
                Timber.i(
                    "Library synced: %d liked, %d recent, %d playlists",
                    snapshot.likedSongs.size,
                    snapshot.recentSongs.size,
                    snapshot.playlists.size,
                )
            }.onFailure {
                Timber.w(it, "Library channel read failed")
            }
            runCatching { client.close(channel).await() }
        }
    }
}
