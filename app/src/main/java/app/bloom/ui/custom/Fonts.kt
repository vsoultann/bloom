package app.bloom.ui.custom

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * App fonts: the system font, a set of Google Fonts (all SIL Open Font License,
 * downloaded once from the google/fonts repository and kept on the phone), or a
 * TTF/OTF you import yourself.
 */
object Fonts {
    data class Choice(val id: String, val label: String, val path: String?)

    /** id → path inside github.com/google/fonts (variable fonts, one file covers every weight). */
    val builtIn = listOf(
        Choice("system", "System", null),
        Choice("inter", "Inter", "ofl/inter/Inter%5Bopsz,wght%5D.ttf"),
        Choice("nunito", "Nunito", "ofl/nunito/Nunito%5Bwght%5D.ttf"),
        Choice("outfit", "Outfit", "ofl/outfit/Outfit%5Bwght%5D.ttf"),
        Choice("manrope", "Manrope", "ofl/manrope/Manrope%5Bwght%5D.ttf"),
        Choice("dmsans", "DM Sans", "ofl/dmsans/DMSans%5Bopsz,wght%5D.ttf"),
        Choice("lexend", "Lexend", "ofl/lexend/Lexend%5Bwght%5D.ttf"),
        Choice("quicksand", "Quicksand", "ofl/quicksand/Quicksand%5Bwght%5D.ttf"),
        Choice("spacegrotesk", "Space Grotesk", "ofl/spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf"),
        Choice("comfortaa", "Comfortaa", "ofl/comfortaa/Comfortaa%5Bwght%5D.ttf"),
        Choice("jetbrainsmono", "JetBrains Mono", "ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf"),
    )

    private fun dir(ctx: Context) = File(ctx.filesDir, "fonts").apply { mkdirs() }
    fun fileFor(ctx: Context, id: String) = File(dir(ctx), "$id.ttf")
    fun isDownloaded(ctx: Context, id: String) = id == "system" || fileFor(ctx, id).let { it.exists() && it.length() > 1000 }

    /** Downloads a built-in font (once). */
    suspend fun download(ctx: Context, id: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val c = builtIn.first { it.id == id }
            val f = fileFor(ctx, id)
            if (!f.exists() || f.length() < 1000) {
                val tmp = File(f.path + ".part")
                URL("https://cdn.jsdelivr.net/gh/google/fonts@main/${c.path}").openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                if (tmp.length() < 1000) error("download failed")
                tmp.renameTo(f)
            }
            f
        }
    }

    /** Copies an imported font file in; returns its id ("import:<name>"). */
    suspend fun import(ctx: Context, uri: android.net.Uri, name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safe = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "font" }
            val id = "import_$safe"
            ctx.contentResolver.openInputStream(uri)!!.use { i -> fileFor(ctx, id).outputStream().use { o -> i.copyTo(o) } }
            // make sure Android can actually read it
            android.graphics.Typeface.createFromFile(fileFor(ctx, id))
            id
        }
    }

    fun imported(ctx: Context): List<Choice> = dir(ctx).listFiles().orEmpty()
        .filter { it.name.startsWith("import_") && it.name.endsWith(".ttf") }
        .map { Choice(it.name.removeSuffix(".ttf"), it.name.removePrefix("import_").removeSuffix(".ttf").replace('_', ' '), null) }

    /** The family for a font id (variable fonts get a real instance per weight). */
    fun family(ctx: Context, id: String, boldBoost: Int = 0): FontFamily? {
        if (id == "system") return null
        val f = fileFor(ctx, id)
        if (!f.exists()) return null
        return runCatching {
            FontFamily(listOf(300, 400, 500, 600, 700, 800).map { w ->
                val weight = (w + boldBoost).coerceIn(100, 900)
                Font(f, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
            })
        }.getOrNull()
    }
}
