/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.media3.common.util.UnstableApi
import com.music.vivi.MainActivity
import com.music.vivi.R
import com.music.vivi.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps this device reachable by Vivi Connect while the app is closed.
 *
 * Android reclaims an idle process, and Connect lives in that process — so a
 * device the user had swiped away stopped advertising, stopped receiving state,
 * and had no MediaSession for a notification to attach to. It only looked
 * connected because a recently used device still had a warm service.
 *
 * Spotify solves this the same way, which is why it keeps a persistent
 * notification whenever it is running. A foreground service is the only
 * mechanism Android offers: from Android 12 onward an app in the background
 * cannot start one on demand, so it has to already be running when the peer's
 * claim arrives.
 */
@UnstableApi
class ConnectPresenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // When a peer takes over, bring MusicService up so its MediaSession can
        // host the remote player and post the media notification. Starting it
        // here is allowed precisely because this service is already foreground.
        scope.launch {
            ConnectBridge.remoteOwnsPlayback.collect { remoteOwns ->
                if (!remoteOwns) return@collect
                if (MusicService.isRunning) return@collect
                runCatching {
                    startService(Intent(this@ConnectPresenceService, MusicService::class.java))
                }.onFailure { Timber.w(it, "Could not start MusicService for a remote session") }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restarted by the system if killed: the whole point is to stay present.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.connect_devices),
            // MIN so it sits silently at the bottom of the shade. This exists to
            // hold the process open, not to be read.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            description = "Keeps this device available to your other devices"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Available to your other devices")
            .setContentText("Vivi Connect is on")
            .setSmallIcon(R.drawable.cast)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    companion object {
        private const val CHANNEL_ID = "vivi_connect_presence"
        private const val NOTIFICATION_ID = 4711

        /**
         * Starts presence if the user has Connect on. Safe to call repeatedly.
         *
         * Must be called while the app is in the foreground — an app in the
         * background cannot start a foreground service on Android 12+.
         */
        fun start(context: Context) {
            val intent = Intent(context, ConnectPresenceService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Timber.w(it, "Could not start Connect presence") }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ConnectPresenceService::class.java))
            }
        }
    }
}
