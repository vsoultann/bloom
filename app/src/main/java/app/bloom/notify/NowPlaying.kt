package app.bloom.notify

/**
 * "Listening to" for Bloom users only. The track is appended to your Root status as
 * invisible characters (zero-width code points), so Root's app shows your normal
 * status while Bloom decodes it. 2 bits per character, bracketed by U+2063.
 */
data class NowPlaying(val title: String, val artist: String, val app: String)

object Hidden {
    private const val MARK = '⁣'
    private val ALPHABET = charArrayOf('​', '‌', '‍', '⁠')
    private const val SEP = '\u001F'
    /** Root's status limit isn't documented; keep the hidden part short. */
    private const val MAX_BYTES = 72

    fun encode(np: NowPlaying): String {
        fun cut(s: String, n: Int) = if (s.toByteArray().size <= n) s else s.take(n).let { t -> var x = t; while (x.toByteArray().size > n) x = x.dropLast(1); x }
        val app = cut(np.app, 14)
        val artist = cut(np.artist, 22)
        val title = cut(np.title, MAX_BYTES - app.toByteArray().size - artist.toByteArray().size - 2)
        val bytes = "$title$SEP$artist$SEP$app".toByteArray(Charsets.UTF_8)
        val sb = StringBuilder().append(MARK).append(MARK)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            for (shift in intArrayOf(6, 4, 2, 0)) sb.append(ALPHABET[(v shr shift) and 3])
        }
        return sb.append(MARK).toString()
    }

    fun decode(status: String): NowPlaying? {
        val a = status.indexOf("$MARK$MARK")
        if (a < 0) return null
        val b = status.indexOf(MARK, a + 2).takeIf { it > a + 2 } ?: return null
        val body = status.substring(a + 2, b)
        if (body.length % 4 != 0) return null
        val bytes = ByteArray(body.length / 4) { i ->
            var v = 0
            for (k in 0 until 4) { val d = ALPHABET.indexOf(body[i * 4 + k]); if (d < 0) return null; v = (v shl 2) or d }
            v.toByte()
        }
        val parts = String(bytes, Charsets.UTF_8).split(SEP)
        if (parts.size < 3 || parts[0].isBlank()) return null
        return NowPlaying(parts[0], parts[1], parts[2])
    }

    /** The status without any hidden part (what people should see / edit). */
    fun strip(status: String): String {
        val a = status.indexOf("$MARK$MARK")
        if (a < 0) return status
        val b = status.indexOf(MARK, a + 2).takeIf { it > a + 2 } ?: return status.substring(0, a)
        return (status.substring(0, a) + status.substring(b + 1)).trimEnd()
    }
}
