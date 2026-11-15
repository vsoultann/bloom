package app.bloom.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps an active call alive in the background and satisfies Android's rule that
 * microphone, camera and screen capture run inside a typed foreground service.
 */
class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice calls", NotificationManager.IMPORTANCE_LOW))
        val title = intent?.getStringExtra("title") ?: "In a voice channel"
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText("Bloom is connected to voice")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .build()
        // Only claim what we're actually allowed to use; Android kills the app otherwise.
        fun has(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var types = 0
        if (has(android.Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (intent?.getBooleanExtra("camera", false) == true && has(android.Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (intent?.getBooleanExtra("screen", false) == true) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val ok = types != 0 && runCatching { startForeground(1, n, types) }.onFailure { e ->
            android.util.Log.w("Bloom", "voice service couldn't start", e)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "voice"
        fun start(ctx: Context, title: String, camera: Boolean = false, screen: Boolean = false) {
            runCatching { ctx.startForegroundService(Intent(ctx, VoiceService::class.java).putExtra("title", title).putExtra("camera", camera).putExtra("screen", screen)) }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, VoiceService::class.java)) }
    }
}
