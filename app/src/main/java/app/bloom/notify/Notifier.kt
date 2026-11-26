package app.bloom.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import app.bloom.MainActivity

/**
 * Message notifications. What triggers one and how much it shows are both
 * user settings (see [Prefs]); the decision itself is made by the view model.
 */
object Notifier {
    const val DMS = "dms"
    const val MENTIONS = "mentions"
    const val PINGS = "pings"
    const val SERVER = "server"
    const val EXTRA_CHANNEL = "open_channel"
    const val EXTRA_MESSAGE = "open_message"

    /** "full" picture + message · "message" message, no picture · "who" only who pinged and where · "private" nothing personal */
    data class Prefs(val style: String, val showPlace: Boolean)

    data class Item(
        val channelId: Long,
        val messageId: Long,
        val senderName: String,
        val senderAvatarUrl: String?,
        val text: String,
        val place: String?,
        val why: String, // DMS / MENTIONS / PINGS / SERVER
        val whyLabel: String, // "mentioned you", "replied to you", …
    )

    private var ready = false

    /** recent lines per chat, so a conversation notification shows more than the last message */
    private data class Line(val text: String, val at: Long, val person: Person)
    private val history = HashMap<Long, ArrayDeque<Line>>()

    fun ensureChannels(ctx: Context) {
        if (ready) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(DMS, "Direct messages", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(MENTIONS, "Mentions & replies", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(PINGS, "@everyone / @here", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(SERVER, "Other server messages", NotificationManager.IMPORTANCE_LOW),
        ).forEach { nm.createNotificationChannel(it) }
        ready = true
    }

    fun allowed(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    suspend fun show(ctx: Context, it: Item, prefs: Prefs) {
        if (!allowed(ctx)) return
        ensureChannels(ctx)
        val private = prefs.style == "private"
        val showPicture = prefs.style == "full"
        val showText = prefs.style == "full" || prefs.style == "message"

        val icon = if (showPicture) avatar(ctx, it.senderAvatarUrl)?.let { b -> IconCompat.createWithBitmap(circle(b)) } else null
        val sender = Person.Builder().setName(if (private) "Someone" else it.senderName).apply { icon?.let { i -> setIcon(i) } }.build()
        val me = Person.Builder().setName("You").build()
        val body = when {
            private -> "New message"
            showText -> it.text.ifBlank { "Sent an attachment" }
            else -> it.whyLabel // "who" style: just who pinged you
        }
        val title = if (prefs.showPlace && !private) it.place else null

        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHANNEL, it.channelId)
            putExtra(EXTRA_MESSAGE, it.messageId)
        }
        val pi = PendingIntent.getActivity(ctx, it.channelId.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // conversation: a long-lived shortcut per chat lets Android show it under "Conversations"
        // with the person's picture, and lets you make it a Priority conversation
        val shortcutId = "chat_${it.channelId}"
        val convName = if (private) "Bloom" else (title ?: it.senderName)
        runCatching {
            val sc = androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, shortcutId)
                .setShortLabel(convName.take(24)).setLongLabel(convName)
                .setLongLived(true)
                .setIsConversation()
                .setPerson(sender)
                .setLocusId(androidx.core.content.LocusIdCompat(shortcutId))
                .setIcon(icon ?: IconCompat.createWithResource(ctx, android.R.drawable.stat_notify_chat))
                .setIntent(Intent(open).setAction(Intent.ACTION_VIEW))
                .setCategories(setOf("app.bloom.category.CONVERSATION"))
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(ctx, sc)
        }
        val lines = history.getOrPut(it.channelId) { ArrayDeque() }
        lines.addLast(Line(body, System.currentTimeMillis(), sender))
        while (lines.size > 6) lines.removeFirst()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(title)
            .setGroupConversation(title != null)
        for (l in lines) style.addMessage(l.text, l.at, l.person)
        val n = NotificationCompat.Builder(ctx, it.why)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(style)
            .setContentTitle(if (private) "Bloom" else it.senderName)
            .setContentText(body)
            .apply { if (showPicture) icon?.let { i -> setLargeIcon(i.toIcon(ctx)) } }
            .setVisibility(if (private) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup("chat_${it.channelId}")
            .setShortcutId(shortcutId)
            .setLocusId(androidx.core.content.LocusIdCompat(shortcutId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(it.channelId.toInt(), n) }
    }

    /** "X is calling" while Bloom is in the background; tapping it opens the call card. */
    suspend fun ringing(ctx: Context, channelId: Long, caller: String, avatarUrl: String?) {
        if (!allowed(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true)
        })
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHANNEL, channelId)
        }
        val pi = PendingIntent.getActivity(ctx, 9000 + channelId.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val icon = avatar(ctx, avatarUrl)?.let { IconCompat.createWithBitmap(circle(it)) }
        val person = Person.Builder().setName(caller).apply { icon?.let { setIcon(it) } }.build()
        val n = NotificationCompat.Builder(ctx, "calls")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, pi, pi))
            .setContentTitle(caller).setContentText("is calling you")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(35_000)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify((channelId + 7_000_000).toInt(), n) }
    }

    /** Clear a chat's notification once you open it. */
    fun clear(ctx: Context, channelId: Long) = runCatching { history.remove(channelId); NotificationManagerCompat.from(ctx).cancel(channelId.toInt()) }

    private suspend fun avatar(ctx: Context, url: String?): Bitmap? {
        if (url == null) return null
        val req = ImageRequest.Builder(ctx).data(url).size(192).allowHardware(false).build()
        return runCatching { SingletonImageLoader.get(ctx).execute(req).image?.toBitmap() }.getOrNull()
    }

    private fun circle(src: Bitmap): Bitmap {
        val s = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(s / 2f, s / 2f, s / 2f, p)
        return out
    }
}
