package app.bloom.notify

/**
 * "Listening to" for Bloom users only. The track is appended to your Root status as
 * invisible characters (zero-width code points), so Root's app shows your normal
 * status while Bloom decodes it. 2 bits per character, bracketed by U+2063.
 */
data class NowPlaying(val title: String, val artist: String, val app: String)

object Hidden {
    private const val MARK = '\u2063'
    /** v1 (Bloom ≤ 0.3): 2 bits per zero-width character. Still decoded. */
    private val ALPHABET = charArrayOf('\u200B', '\u200C', '\u200D', '\u2060')
    /** v2: 4 bits per character, variation selectors U+FE00..U+FE0F (invisible, 1 UTF-16 unit each). */
    private const val V2 = '\u2064'
    private const val VS = 0xFE00
    private const val SEP = '\u001F'
    /**
     * Root rejected long statuses with error 300 (the v1 hidden part alone was ~290 chars).
     * Keep the whole status within this many UTF-16 units; [encode] callers can pass a
     * smaller budget to retry after a rejection.
     */
    const val STATUS_LIMIT = 128

    private fun cut(s: String, n: Int): String {
        if (n <= 0) return ""
        var t = s
        while (t.toByteArray().size > n) t = t.dropLast(1)
        return t
    }

    /**
     * Hidden now-playing suffix for a status whose visible part is [visible], or "" if
     * there's no room. Title wins over artist, artist over app.
     */
    fun encode(np: NowPlaying, visible: String = "", limit: Int = STATUS_LIMIT): String {
        val roomBytes = (limit - visible.length - 3) / 2   // 3 marks; 2 chars per byte
        if (roomBytes < 8) return ""
        val app = cut(np.app, minOf(12, roomBytes / 5))
        val artist = cut(np.artist, maxOf(0, minOf(28, roomBytes - app.toByteArray().size - 2 - 8)))
        val title = cut(np.title, roomBytes - app.toByteArray().size - artist.toByteArray().size - 2)
        if (title.isBlank()) return ""
        val bytes = "$title$SEP$artist$SEP$app".toByteArray(Charsets.UTF_8)
        val sb = StringBuilder().append(MARK).append(V2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append((VS + (v shr 4)).toChar()).append((VS + (v and 15)).toChar())
        }
        return sb.append(MARK).toString()
    }

    fun decode(status: String): NowPlaying? {
        val a = status.indexOf(MARK)
        if (a < 0 || a + 1 >= status.length) return null
        val b = status.indexOf(MARK, a + 2).takeIf { it > a + 2 } ?: return null
        val body = status.substring(a + 2, b)
        val bytes = when (status[a + 1]) {
            V2 -> {
                if (body.length % 2 != 0) return null
                ByteArray(body.length / 2) { i ->
                    val hi = body[i * 2].code - VS; val lo = body[i * 2 + 1].code - VS
                    if (hi !in 0..15 || lo !in 0..15) return null
                    ((hi shl 4) or lo).toByte()
                }
            }
            MARK -> {
                if (body.length % 4 != 0) return null
                ByteArray(body.length / 4) { i ->
                    var v = 0
                    for (k in 0 until 4) { val d = ALPHABET.indexOf(body[i * 4 + k]); if (d < 0) return null; v = (v shl 2) or d }
                    v.toByte()
                }
            }
            else -> return null
        }
        val parts = String(bytes, Charsets.UTF_8).split(SEP)
        if (parts.size < 3 || parts[0].isBlank()) return null
        return NowPlaying(parts[0], parts[1], parts[2])
    }

    /** The status without any hidden part (what people should see / edit). */
    fun strip(status: String): String {
        val a = status.indexOf(MARK)
        if (a < 0 || a + 1 >= status.length || (status[a + 1] != MARK && status[a + 1] != V2)) return status
        val b = status.indexOf(MARK, a + 2).takeIf { it > a + 2 } ?: return status.substring(0, a)
        return (status.substring(0, a) + status.substring(b + 1)).trimEnd()
    }
}
