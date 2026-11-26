package app.bloom.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.app.PendingIntent

/**
 * Keeps an active call alive in the background and satisfies Android's rule that
 * microphone, camera and screen capture run inside a typed foreground service.
 */
class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // buttons on the notification / Now Bar
        when (intent?.action) {
            ACTION_HANGUP -> { onHangUp?.invoke(); return START_NOT_STICKY }
            ACTION_MUTE -> { onMute?.invoke(); return START_NOT_STICKY }
        }
        val nm = getSystemService(NotificationManager::class.java)
        // DEFAULT importance: Live Updates (Now Bar) can't be promoted from a MIN/LOW-key channel
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Calls in progress", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
        last = intent ?: last
        val n = build(this, last)
        // Only claim what we're actually allowed to use; Android kills the app otherwise.
        fun has(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var types = 0
        if (has(android.Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (last?.getBooleanExtra("camera", false) == true && has(android.Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (last?.getBooleanExtra("screen", false) == true) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val ok = types != 0 && runCatching { startForeground(1, n, types) }.onFailure { e ->
            android.util.Log.w("Bloom", "voice service couldn't start", e)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "call_live"
        private const val ACTION_HANGUP = "app.bloom.HANGUP"
        private const val ACTION_MUTE = "app.bloom.MUTE"
        /** set by the view model: what the notification buttons do */
        @Volatile var onHangUp: (() -> Unit)? = null
        @Volatile var onMute: (() -> Unit)? = null
        /** the person's picture for the call notification (set by the view model) */
        @Volatile var avatar: android.graphics.Bitmap? = null
        private var last: Intent? = null

        /**
         * Ongoing call as an Android 16+ Live Update: CallStyle with a running timer, hang-up and
         * mute buttons, a short status-bar chip, and a request to be promoted (One UI's Now Bar).
         */
        private fun build(ctx: Context, i: Intent?): Notification {
            val title = i?.getStringExtra("title") ?: "Call"
            val since = i?.getLongExtra("since", 0L) ?: 0L
            val muted = i?.getBooleanExtra("muted", false) == true
            val open = PendingIntent.getActivity(ctx, 42, Intent(ctx, app.bloom.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open_call", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val hang = PendingIntent.getService(ctx, 43, Intent(ctx, VoiceService::class.java).setAction(ACTION_HANGUP), PendingIntent.FLAG_IMMUTABLE)
            val mute = PendingIntent.getService(ctx, 44, Intent(ctx, VoiceService::class.java).setAction(ACTION_MUTE), PendingIntent.FLAG_IMMUTABLE)
            val person = androidx.core.app.Person.Builder().setName(title).setImportant(true)
                .apply { avatar?.let { setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(it)) } }.build()
            return androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setStyle(androidx.core.app.NotificationCompat.CallStyle.forOngoingCall(person, hang))
                .setContentTitle(title)
                .setContentText(if (since > 0) (if (muted) "In call · muted" else "In call") else "Calling…")
                .setContentIntent(open)
                .addAction(if (muted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off, if (muted) "Unmute" else "Mute", mute)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                .setOngoing(true).setOnlyAlertOnce(true)
                .apply { if (since > 0) { setWhen(since); setUsesChronometer(true); setShowWhen(true) } }
                // Live Update: Now Bar / status chip (Android 16+, One UI 8+)
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(if (since > 0) (if (muted) "Muted" else "In call") else "Calling")
                .setForegroundServiceBehavior(androidx.core.app.NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun start(ctx: Context, title: String, camera: Boolean = false, screen: Boolean = false, since: Long = 0L, muted: Boolean = false) {
            runCatching { ctx.startForegroundService(Intent(ctx, VoiceService::class.java).putExtra("title", title).putExtra("camera", camera).putExtra("screen", screen).putExtra("since", since).putExtra("muted", muted)) }
        }

        /** Refresh the live notification (connected / muted / picture) without restarting anything. */
        fun update(ctx: Context, title: String, since: Long, muted: Boolean, camera: Boolean, screen: Boolean) {
            val i = Intent(ctx, VoiceService::class.java).putExtra("title", title).putExtra("camera", camera).putExtra("screen", screen).putExtra("since", since).putExtra("muted", muted)
            last = i
            runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(1, build(ctx, i)) }
        }

        fun stop(ctx: Context) { avatar = null; last = null; ctx.stopService(Intent(ctx, VoiceService::class.java)) }
    }
}
