package app.bloom.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Optional: keeps Bloom's connection to Root alive while it's in the background so
 * message notifications keep arriving. Shows a quiet, minimised notification (Android
 * requires one for this).
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Stay connected", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Bloom is connected")
            .setContentText("So you get message notifications")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        // Android can refuse (background start limits, revoked permission): stop quietly instead of crashing
        if (runCatching { ServiceCompat.startForeground(this, 7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING) }.isFailure) {
            stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "keepalive"
        fun set(ctx: Context, on: Boolean) {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (on) runCatching { ctx.startForegroundService(i) } else ctx.stopService(i)
        }
    }
}
