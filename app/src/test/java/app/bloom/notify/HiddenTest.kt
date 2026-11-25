package app.bloom.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenTest {
    private val np = NowPlaying("Bohemian Rhapsody - Remastered 2011", "Queen", "Spotify")

    @Test fun roundTrip() {
        val s = "studying 📚" + Hidden.encode(np, "studying 📚")
        val back = Hidden.decode(s)!!
        assertTrue(np.title.startsWith(back.title))
        assertEquals("Queen", back.artist)
        assertEquals("studying 📚", Hidden.strip(s))
    }

    @Test fun staysWithinBudget() {
        val long = NowPlaying("x".repeat(300), "y".repeat(300), "z".repeat(50))
        for (limit in intArrayOf(128, 96, 64)) {
            val visible = "hello there"
            val s = visible + Hidden.encode(long, visible, limit)
            assertTrue("len ${s.length} > $limit", s.length <= limit)
            Hidden.decode(s)?.let { assertTrue(it.title.isNotEmpty()) }
        }
    }

    @Test fun noRoomMeansNoHiddenPart() {
        val visible = "a".repeat(120)
        assertEquals("", Hidden.encode(np, visible))
    }

    @Test fun unicodeIsCutOnCharacterBoundaries() {
        val s = Hidden.encode(NowPlaying("日本語のとても長いタイトル".repeat(5), "アーティスト", "App"), "", 64)
        val back = Hidden.decode(s)!!
        assertTrue(back.title.all { it != '�' })
    }

    @Test fun decodesLegacyV1() {
        // v1: two U+2063 marks, 4 zero-width chars per byte, closing mark
        val zw = charArrayOf('​', '‌', '‍', '⁠')
        val bytes = "T\u001FA\u001FB".toByteArray()
        val sb = StringBuilder("⁣⁣")
        for (b in bytes) { val v = b.toInt() and 0xFF; for (sh in intArrayOf(6, 4, 2, 0)) sb.append(zw[(v shr sh) and 3]) }
        sb.append('⁣')
        assertEquals(NowPlaying("T", "A", "B"), Hidden.decode("hi$sb"))
        assertEquals("hi", Hidden.strip("hi$sb"))
    }

    @Test fun plainStatusUntouched() {
        assertNull(Hidden.decode("just vibing"))
        assertEquals("just vibing", Hidden.strip("just vibing"))
    }
}
