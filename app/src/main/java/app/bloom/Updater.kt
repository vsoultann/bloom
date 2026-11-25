package app.bloom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from GitHub releases (github.com/vsoultann/bloom).
 * Check → download the release APK (progress) → Android's own installer asks to confirm.
 * Releases are signed with the same key, so Android installs them over the current app.
 */
object Updater {
    private const val API = "https://api.github.com/repos/vsoultann/bloom/releases/latest"
    const val RELEASES = "https://github.com/vsoultann/bloom/releases"

    data class Release(val version: String, val notes: String, val apkUrl: String?, val page: String)

    enum class State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

    var state by mutableStateOf(State.IDLE); private set
    var latest by mutableStateOf<Release?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var message by mutableStateOf<String?>(null); private set
    private var apk: File? = null

    val current get() = BuildConfig.VERSION_NAME

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        state = State.CHECKING; message = null
        runCatching {
            val c = URL(API).openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Bloom/$current (Android)")
            c.connectTimeout = 8000; c.readTimeout = 8000
            try {
                if (c.responseCode == 404) return@runCatching null
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val assets = o.optJSONArray("assets")
                val apkUrl = assets?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") }
                Release(o.optString("tag_name").removePrefix("v"), o.optString("body"), apkUrl, o.optString("html_url", RELEASES))
            } finally { c.disconnect() }
        }.onSuccess { r ->
            latest = r
            state = if (r != null && isNewer(r.version, current)) State.AVAILABLE else State.UP_TO_DATE
        }.onFailure { state = State.FAILED; message = "Couldn't reach GitHub: ${it.message}" }
            .getOrNull()?.takeIf { isNewer(it.version, current) }
    }

    /** Downloads the APK of [latest]; returns true when it's ready to install. */
    suspend fun download(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val r = latest ?: return@withContext false
        val url = r.apkUrl ?: run { state = State.FAILED; message = "This release has no APK attached"; return@withContext false }
        state = State.DOWNLOADING; progress = 0f
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "bloom-${r.version}.apk")
        runCatching {
            var c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            // GitHub redirects to its CDN (different host): follow by hand
            var hops = 0
            while (c.responseCode in 301..308 && hops++ < 5) {
                val next = c.getHeaderField("Location"); c.disconnect()
                c = URL(next).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 30_000
            }
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: -1L
                c.inputStream.use { i -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; if (total > 0) progress = done.toFloat() / total }
                } }
            } finally { c.disconnect() }
        }.onSuccess { apk = out; progress = 1f; state = State.READY }
            .onFailure { out.delete(); state = State.FAILED; message = "Download failed: ${it.message}" }
        state == State.READY
    }

    /** Opens Android's installer (first time: asks to allow installs from Bloom). */
    fun install(ctx: Context) {
        val f = apk ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            message = "Allow Bloom to install updates, then tap Install again"
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-', ' ').mapNotNull { it.toIntOrNull() }
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
