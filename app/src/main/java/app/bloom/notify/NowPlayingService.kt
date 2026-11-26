package app.bloom.notify

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import app.bloom.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Watches media sessions (Spotify, YouTube Music, SoundCloud, …) and publishes what's
 * playing as hidden text in your Root status (see [Hidden]). Android requires
 * notification access for this; Bloom never reads your notifications' content.
 */
class NowPlayingService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("Bloom", "now playing", e) })
    private var msm: MediaSessionManager? = null
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var pending: Job? = null
    private var published: NowPlaying? = null
    private var lastPublish = 0L

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()) }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
        val me = ComponentName(this, NowPlayingService::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsChanged, me)
            track(m.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun track(list: List<MediaController>) {
        callbacks.keys.filter { it !in list }.forEach { c -> callbacks.remove(c)?.let { c.unregisterCallback(it) } }
        list.filter { it !in callbacks }.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = schedule()
                override fun onMetadataChanged(metadata: MediaMetadata?) = schedule()
            }
            c.registerCallback(cb)
            callbacks[c] = cb
        }
        schedule()
    }

    /** Debounced: song skips and seeks settle before we touch the status. */
    private fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(4000)
            publish(current())
        }
    }

    private suspend fun prefs(): JSONObject {
        runCatching { Core.init(filesDir.absolutePath, java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000) }
        return runCatching { Core.settings() }.getOrDefault(JSONObject())
    }

    private suspend fun current(): NowPlaying? {
        val p = prefs()
        if (!p.optBoolean("np_on", false)) return null
        val allowed = p.optJSONArray("np_apps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        val playing = callbacks.keys.firstOrNull { c ->
            c.playbackState?.state == PlaybackState.STATE_PLAYING && (allowed.isEmpty() || c.packageName in allowed)
        } ?: return null
        val md = playing.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        return NowPlaying(title, artist, appLabel(this, playing.packageName))
    }

    private suspend fun publish(np: NowPlaying?) {
        if (np == published) return
        // never more than once every 20 s (Root rate limits status changes)
        val wait = 20_000 - (System.currentTimeMillis() - lastPublish)
        if (wait > 0) delay(wait)
        val me = runCatching { Core.me() }.getOrNull() ?: return
        val clean = Hidden.strip(me.statusTextRaw)
        // Root rejects statuses that are too long (error 300): shrink the hidden part and retry
        var ok = false
        var lastErr: String? = null
        for (limit in intArrayOf(Hidden.STATUS_LIMIT, 96, 64)) {
            val status = if (np == null) clean else clean + Hidden.encode(np, clean, limit)
            val r = runCatching { Core.editProfile(JSONObject().put("status", status)) }
            if (r.isSuccess) { ok = true; break }
            lastErr = r.exceptionOrNull()?.message
            if (np == null || lastErr?.contains("rejected") != true) break
        }
        if (ok) { published = np; lastPublish = System.currentTimeMillis() }
        else runCatching { Core.log("now_playing", "status update failed: $lastErr") }
    }

    companion object {
        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(ctx, NowPlayingService::class.java).flattenToString())
        }

        fun appLabel(ctx: Context, pkg: String): String = when (pkg) {
            "com.spotify.music" -> "Spotify"
            "com.google.android.apps.youtube.music" -> "YouTube Music"
            "com.google.android.youtube" -> "YouTube"
            "com.soundcloud.android" -> "SoundCloud"
            "deezer.android.app" -> "Deezer"
            "com.apple.android.music" -> "Apple Music"
            else -> runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }

        /** Apps that currently have a media session (to pick which ones may share). */
        fun mediaApps(ctx: Context): List<Pair<String, String>> {
            if (!hasAccess(ctx)) return emptyList()
            val m = ctx.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
            return runCatching { m.getActiveSessions(ComponentName(ctx, NowPlayingService::class.java)) }.getOrDefault(emptyList())
                .map { it.packageName }.distinct().map { it to appLabel(ctx, it) }
        }
    }
}
