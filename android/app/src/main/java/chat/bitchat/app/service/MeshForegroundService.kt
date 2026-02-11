// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import chat.bitchat.app.R
import chat.bitchat.app.ui.MainActivity
import timber.log.Timber

/**
 * Foreground service that keeps the mesh network alive in the background.
 * Shows a persistent notification with mesh status.
 *
 * This is required on Android to:
 * - Keep BLE scanning/advertising active
 * - Keep Wi-Fi Aware sessions active
 * - Survive Doze mode and battery optimization
 */
class MeshForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "bitchat_mesh_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "chat.bitchat.MESH_START"
        const val ACTION_STOP = "chat.bitchat.MESH_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.i("MeshForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Timber.i("MeshForegroundService stopping")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Timber.i("MeshForegroundService starting")
                startForeground(NOTIFICATION_ID, buildNotification(0))
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("MeshForegroundService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BitChat Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the mesh network active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(peerCount: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MeshForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (peerCount > 0) {
            "$peerCount peer${if (peerCount != 1) "s" else ""} connected"
        } else {
            "Scanning for peers..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BitChat Mesh Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: custom icon
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updatePeerCount(count: Int) {
        val notification = buildNotification(count)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
