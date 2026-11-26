package app.bloom.ui.components

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import app.bloom.core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Root cosmetic art (avatar frames, card borders, name fonts).
 *
 * Root (0.9.46) ships each cosmetic as a `.tar.gz` bundle with a `manifest.json` and its
 * files (PNG/GIF/WebP art, TTF/OTF fonts). Bundle URLs come from
 * `UserCosmeticGrpcService/GetBundlesByIds`. Bundles are unpacked once into
 * `files/cosmetics/<id>/` and reused. Parsing is tolerant: the manifest's exact layout
 * was read from Root's decompiled client and isn't documented.
 */
data class CosmeticAsset(
    val id: String,
    val type: String,                 // avatarframe · cardborder · namefont · nameeffect
    val art: File? = null,
    val canvas: Float = 256f,         // Root's DEFAULT_FRAME_CANVAS (width)
    val canvasH: Float = 256f,        // some frames are taller (fire: 256×320)
    val avatarSize: Float = 168f,     // Root's DEFAULT_FRAME_AVATAR_SIZE
    val insets: FloatArray = floatArrayOf(0f, 0f, 0f, 0f), // left, top, right, bottom (image px)
    val font: File? = null,
    val fontWeight: Int = 400,
    val sizeScale: Float = 1f,
    val renderer: String? = null,
    /** bundle folder (scene layers reference files in it) */
    val dir: File? = null,
    /** card-border scene: layered, timed art (JSON from the manifest) */
    val scene: String? = null,
    /** art Root draws itself, e.g. "shimmer" (an animated outline) */
    val clientArt: String? = null,
)

object CosmeticStore {
    /** id → asset (null = failed / nothing usable). Missing key = not loaded yet. */
    val assets = mutableStateMapOf<String, CosmeticAsset?>()
    private val inFlight = HashSet<String>()
    private val lock = Mutex()
    // a bad bundle must never take the app down
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("Bloom", "cosmetics", e) })
    private const val MAX_PACKED = 20L * 1024 * 1024
    private const val MAX_UNPACKED = 60L * 1024 * 1024

    /** Root's built-in name-effect renderers (ids from the client bundle). */
    val builtInEffects = mapOf(
        "0024c0c29400802d8000000000000101" to "solid",
        "0024c0c29400802d8000000000000102" to "gradient",
        "0024c0c29400802d8000000000000103" to "neon",
        "0024c0c29400802d8000000000000104" to "toon",
        "0024c0c29400802d8000000000000105" to "pop",
        "0024c0c29400802d8000000000000106" to "gummy",
        "0024c0c29400802d8000000000000107" to "prism",
    )

    /** Asks for these cosmetics; they appear in [assets] when ready. Cheap to call often. */
    fun request(ctx: Context, ids: Collection<String?>) {
        val want = ids.filterNotNull().filter { it.length == 32 && it !in assets && it !in builtInEffects }.distinct()
        if (want.isEmpty()) return
        scope.launch {
            val todo = lock.withLock { want.filter { inFlight.add(it) } }
            if (todo.isEmpty()) return@launch
            try {
                val root = File(ctx.filesDir, "cosmetics").apply { mkdirs() }
                val missing = mutableListOf<String>()
                for (id in todo) {
                    val dir = File(root, id)
                    val cached = File(dir, "manifest.json").takeIf { it.exists() }?.let { parse(id, dir) }
                    if (cached != null) assets[id] = cached else missing += id
                }
                if (missing.isEmpty()) return@launch
                val urls = runCatching { Core.social("cosmetic_bundles", JSONObject().put("ids", JSONArray(missing))) as JSONObject }
                    .onFailure { runCatching { Core.log("cosmetics", "bundle urls: ${it.message}") } }.getOrNull() ?: return@launch
                for (id in missing) {
                    val url = urls.optString(id).takeIf { it.isNotEmpty() }
                    assets[id] = url?.let { u -> runCatching { download(u, File(root, id)); parse(id, File(root, id)) }
                        .onFailure { e -> runCatching { Core.log("cosmetics", "$id: ${e.message}") } }.getOrNull() }
                }
            } finally {
                lock.withLock { inFlight.removeAll(todo.toSet()) }
            }
        }
    }

    private fun download(url: String, dir: File) {
        val tmp = File(dir.parentFile, "${dir.name}.part").apply { deleteRecursively(); mkdirs() }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        try {
            if (conn.responseCode !in 200..299) error("bundle download failed (${conn.responseCode})")
            if (conn.contentLengthLong > MAX_PACKED) error("bundle is too large")
            val gz = url.substringBefore('?').let { it.endsWith(".gz") || it.endsWith(".tgz") }
            conn.inputStream.buffered().use { raw ->
                raw.mark(4)
                val b0 = raw.read(); val b1 = raw.read(); raw.reset()
                val isGzip = gz || (b0 == 0x1f && b1 == 0x8b)
                untar(if (isGzip) GZIPInputStream(raw) else raw, tmp)
            }
        } finally { conn.disconnect() }
        if (!File(tmp, "manifest.json").exists()) {
            // some bundles keep everything inside one top folder
            tmp.listFiles()?.singleOrNull { it.isDirectory && File(it, "manifest.json").exists() }?.let { inner ->
                inner.listFiles()?.forEach { it.renameTo(File(tmp, it.name)) }
            }
        }
        if (!File(tmp, "manifest.json").exists()) { tmp.deleteRecursively(); error("bundle has no manifest.json") }
        dir.deleteRecursively()
        tmp.renameTo(dir)
    }

    /** Minimal ustar reader: regular files only, no paths outside [dest], size-capped. */
    private fun untar(input: InputStream, dest: File) {
        val header = ByteArray(512)
        var total = 0L
        val canon = dest.canonicalPath + File.separator
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break
            fun str(off: Int, len: Int) = String(header, off, len, Charsets.UTF_8).substringBefore('\u0000').trim()
            val name = (str(345, 155).let { if (it.isNotEmpty()) "$it/" else "" }) + str(0, 100)
            val size = str(124, 12).ifEmpty { "0" }.toLong(8)
            val kind = header[156].toInt().toChar()
            total += size
            if (total > MAX_UNPACKED) error("bundle is too large unpacked")
            val out = File(dest, name.removePrefix("./"))
            val regular = (kind == '0' || kind == '\u0000') && !name.endsWith("/")
            if (regular && out.canonicalPath.startsWith(canon) && !out.name.startsWith("._")) {
                out.parentFile?.mkdirs()
                out.outputStream().use { o -> copyN(input, o, size) }
            } else skipN(input, size)
            val pad = (512 - size % 512) % 512
            skipN(input, pad)
        }
    }

    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var n = 0
        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) return false; n += r }
        return true
    }
    private fun copyN(i: InputStream, o: java.io.OutputStream, len: Long) {
        val buf = ByteArray(32 * 1024); var left = len
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) error("truncated bundle"); o.write(buf, 0, r); left -= r }
    }
    private fun skipN(i: InputStream, len: Long) {
        var left = len; val buf = ByteArray(8192)
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) return; left -= r }
    }

    private val images = setOf("png", "webp", "gif", "apng", "jpg", "jpeg")
    private val fonts = setOf("ttf", "otf")

    private fun parse(id: String, dir: File): CosmeticAsset? {
        val m = runCatching { JSONObject(File(dir, "manifest.json").readText()) }.getOrNull() ?: return null
        val type = m.optString("type").replace(Regex("[_\\s-]"), "").lowercase()
        val key = when (type) { "avatarframe" -> "avatarFrame"; "cardborder" -> "cardBorder"; "namefont" -> "nameFont"; "nameeffect" -> "nameEffect"; else -> "" }
        val entry = m.opt(key).let { if (it is JSONArray) it.optJSONObject(0) else it as? JSONObject } ?: JSONObject()
        val folder = m.optString("folder").trim('/').takeIf { it.isNotEmpty() && !it.contains("..") }
        val all = dir.walkTopDown().filter { it.isFile }.toList()

        fun resolve(v: Any?): File? {
            val p = when (v) {
                is String -> v
                is JSONObject -> listOf("path", "uri", "url", "src", "file").firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotEmpty() } }
                is JSONArray -> return (0 until v.length()).firstNotNullOfOrNull { resolve(v.opt(it)) }
                else -> null
            } ?: return null
            if (p.startsWith("http")) return null
            val clean = p.substringAfter("://").removePrefix("./").trimStart('/')
            if (clean.contains("..")) return null
            return listOfNotNull(folder?.let { File(dir, "$it/$clean") }, File(dir, clean)).firstOrNull { it.isFile }
                ?: all.firstOrNull { it.name == clean.substringAfterLast('/') }
        }
        fun find(keys: List<String>, exts: Set<String>): File? =
            keys.firstNotNullOfOrNull { k -> resolve(entry.opt(k)) ?: resolve(m.opt(k)) }
                ?: all.firstOrNull { it.extension.lowercase() in exts }

        fun num(k: String, d: Float) = (entry.opt(k) ?: m.opt(k)).let { (it as? Number)?.toFloat() } ?: d
        val canvasObj = entry.optJSONObject("canvas") ?: m.optJSONObject("canvas")
        val canvas = canvasObj?.let { (it.opt("width") as? Number)?.toFloat() } ?: num("canvas", 256f)
        val canvasH = canvasObj?.let { (it.opt("height") as? Number)?.toFloat() } ?: canvas
        val ins = entry.optJSONObject("insets") ?: m.optJSONObject("insets")
        return CosmeticAsset(
            id = id, type = type,
            art = when {
                type == "avatarframe" -> find(listOf("art", "artUri", "image", "path", "src"), images)
                type == "cardborder" && entry.optJSONObject("art")?.optString("source") != "client" && entry.optJSONObject("scene") == null ->
                    find(listOf("art", "artUri", "image", "path", "src"), images)
                else -> null
            },
            canvas = canvas.takeIf { it > 0 } ?: 256f,
            canvasH = canvasH.takeIf { it > 0 } ?: (canvas.takeIf { it > 0 } ?: 256f),
            avatarSize = num("avatarSize", 168f).takeIf { it > 0 } ?: 168f,
            insets = floatArrayOf((ins?.optDouble("left", 0.0) ?: 0.0).toFloat(), (ins?.optDouble("top", 0.0) ?: 0.0).toFloat(), (ins?.optDouble("right", 0.0) ?: 0.0).toFloat(), (ins?.optDouble("bottom", 0.0) ?: 0.0).toFloat()),
            font = if (type == "namefont") find(listOf("font", "fontUri", "path", "src"), fonts) else null,
            fontWeight = num("fontWeight", num("weight", 400f)).toInt().coerceIn(100, 900),
            sizeScale = num("sizeScale", 1f).coerceIn(0.5f, 2f),
            renderer = (entry.opt("renderer") as? JSONObject)?.optString("id") ?: entry.optString("renderer").takeIf { it.isNotEmpty() },
            dir = dir,
            scene = entry.optJSONObject("scene")?.toString(),
            clientArt = entry.optJSONObject("art")?.takeIf { it.optString("source") == "client" }?.optString("id")?.takeIf { it.isNotEmpty() },
        )
    }
}
