package app.bloom.ui.custom

import android.content.Context
import android.icu.lang.UCharacter
import android.icu.lang.UProperty

/**
 * Emoji artwork packs, drawn as images in place of the phone's emoji.
 * Apple / Twitter (Twemoji) / Google / Facebook come from the emoji-datasource
 * packages on jsDelivr; Windows 11 (Fluent, MIT) from microsoft/fluentui-emoji.
 * Images are downloaded on demand and cached; nothing is bundled.
 */
object EmojiPacks {
    data class Pack(val id: String, val label: String)
    val all = listOf(
        Pack("system", "System"), Pack("apple", "Apple"), Pack("fluent", "Windows 11"),
        Pack("google", "Google"), Pack("twitter", "Twitter"), Pack("facebook", "Facebook"),
    )

    @Volatile private var fluent: Map<String, String>? = null
    fun load(ctx: Context) {
        if (fluent != null) return
        fluent = runCatching {
            ctx.assets.open("fluent_emoji.tsv").bufferedReader().readLines()
                .mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** "1f600" or "2764-fe0f" style ids, with and without FE0F. */
    fun ids(e: String): List<String> {
        val cps = e.codePoints().toArray()
        val full = cps.joinToString("-") { Integer.toHexString(it) }
        val bare = cps.filter { it != 0xFE0F }.joinToString("-") { Integer.toHexString(it) }
        return listOf(full, bare).distinct()
    }

    /** Image URLs to try in order for this emoji in this pack (empty = use the system font). */
    fun urls(pack: String, e: String): List<String> {
        val ids = ids(e)
        return when (pack) {
            "apple", "google", "twitter", "facebook" ->
                ids.map { "https://cdn.jsdelivr.net/npm/emoji-datasource-$pack@15.1.2/img/$pack/64/$it.png" }
            "fluent" -> ids.mapNotNull { fluent?.get(it) }.distinct().map { "https://cdn.jsdelivr.net/gh/microsoft/fluentui-emoji@main/assets/" + it.replace(" ", "%20") }
            else -> emptyList()
        }
    }

    /** Length (in chars) of the emoji sequence starting at [i], or 0 if none starts there. */
    fun emojiAt(s: String, i: Int): Int {
        var j = i
        var seen = false
        var regional = 0
        while (j < s.length) {
            val cp = s.codePointAt(j)
            val n = Character.charCount(cp)
            val pict = UCharacter.hasBinaryProperty(cp, UProperty.EXTENDED_PICTOGRAPHIC) && cp > 0xFF
            val isRegional = cp in 0x1F1E6..0x1F1FF
            when {
                !seen && isRegional -> { seen = true; regional = 1; j += n }
                seen && isRegional && regional == 1 -> { regional = 2; j += n; break }
                !seen && pict -> { seen = true; j += n }
                // keycaps: digit/#/* + FE0F + 20E3
                !seen && (cp in 0x30..0x39 || cp == 0x23 || cp == 0x2A) && s.length > j + 2 && s[j + 1] == '️' && s[j + 2] == '⃣' -> return 3
                seen && (cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 || cp in 0xE0020..0xE007F) -> j += n
                seen && cp == 0x200D && j + 1 < s.length -> {
                    val next = s.codePointAt(j + 1)
                    if (UCharacter.hasBinaryProperty(next, UProperty.EXTENDED_PICTOGRAPHIC)) j += 1 + Character.charCount(next) else break
                }
                else -> break
            }
        }
        return if (seen) j - i else 0
    }
}
