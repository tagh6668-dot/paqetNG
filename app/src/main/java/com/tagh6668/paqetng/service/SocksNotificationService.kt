package com.tagh6668.paqetng.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tagh6668.paqetng.MainActivity
import com.tagh6668.paqetng.PaqetNGApplication
import com.tagh6668.paqetng.R

/**
 * Foreground service that shows an ongoing notification when paqet is running
 * in SOCKS-only mode (no VPN). Allows user to disconnect directly from notification.
 * This mirrors PaqetNGVpnService's notification but without creating a TUN.
 */
class SocksNotificationService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "SocksNotification: ACTION_STOP - stopping paqet")
            // Stop paqet core
            try {
                val app = application as? PaqetNGApplication
                app?.paqetRunner?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop paqetRunner", e)
            }
            // Broadcast so UI can sync state (optional)
            sendBroadcast(Intent(ACTION_SOCKS_STOPPED).setPackage(packageName))
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        // Normal start - show foreground notification
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Notify tile etc that socks started
        sendBroadcast(Intent(ACTION_SOCKS_STARTED).setPackage(packageName))
        return START_STICKY
    }

    override fun onDestroy() {
        sendBroadcast(Intent(ACTION_SOCKS_STOPPED).setPackage(packageName))
        if (Build.VERSION.SDK_INT >= 31) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION")
            try { stopForeground(true) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= 31) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SOCKS",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentPending = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, SocksNotificationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_socks_connected))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(contentPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_action_disconnect), stopPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "SocksNotify"
        const val ACTION_STOP = "com.tagh6668.paqetng.socks.STOP"
        const val ACTION_SOCKS_STARTED = "com.tagh6668.paqetng.socks.STARTED"
        const val ACTION_SOCKS_STOPPED = "com.tagh6668.paqetng.socks.STOPPED"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "socks_channel"
    }
}
