package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import wm.damage.core.windows.reader.ReaderAnchor

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4: a place in a book as the PC record holds it, `@c<offset>.<ver>~<quote of 32>` — written,
 * read back, refused when malformed, and found again by its quote across extractor versions.
 */
class ReaderAnchorTest {
    private val text = "It was a bright cold day in April, and the clocks were striking thirteen. ".repeat(4)

    @Test
    fun anAnchorIsWrittenAndReadBackWhole() {
        val a = ReaderAnchor.at(text, 9)
        assertEquals(9, a.offset)
        assertEquals(ReaderAnchor.EXTRACTOR, a.ver)
        assertEquals(text.substring(9, 9 + ReaderAnchor.QUOTE), a.quote)
        assertEquals(a, ReaderAnchor.parse(a.format()))
        // the quote runs to the end and may hold any character, the separator included
        val odd = ReaderAnchor(5, 1, "a~b\nc @c7.1")
        assertEquals(odd, ReaderAnchor.parse(odd.format()))
        // a place with no text at hand (a migrated one): an empty quote
        assertEquals("@c42.1~", ReaderAnchor(42).format())
        assertEquals(ReaderAnchor(42), ReaderAnchor.parse("@c42.1~"))
        assertEquals(ReaderAnchor(42), ReaderAnchor.parse("@c42.1"))
        // the end of the text quotes what is left
        assertEquals("", ReaderAnchor.at(text, text.length).quote)
        assertEquals(text.length, ReaderAnchor.at(text, text.length + 10).offset)
    }

    @Test
    fun aMalformedPlaceIsNotAPlace() {
        for (s in listOf(null, "", "42", "@c.1~", "@c-4.1~", "@cx.1~", "@c4.0~", "@c4~q", "c4.1~q", "@c99999999999.1~"))
            assertNull(ReaderAnchor.parse(s), "$s")
        assertFailsWith<IllegalArgumentException> { ReaderAnchor(-1) }
        assertFailsWith<IllegalArgumentException> { ReaderAnchor(1, 0) }
    }

    @Test
    fun anotherExtractorsPlaceIsFoundAgainByItsQuote_nearestTheOldOffset() {
        val q = "the clocks were striking"
        val second = text.indexOf(q, text.indexOf(q) + 1)
        // written by version 0's text, shifted: the quote's nearest occurrence wins
        val old = ReaderAnchor(second + 3, 2, q)
        assertEquals(second, ReaderAnchor.refind(old, text, ver = 1))
        // the same version: the offset as it is
        assertEquals(second + 3, ReaderAnchor.refind(ReaderAnchor(second + 3, 1, q), text, ver = 1))
        // a quote that is not found, or none: the offset, clamped
        assertEquals(17, ReaderAnchor.refind(ReaderAnchor(17, 2, "no such words"), text, ver = 1))
        assertEquals(text.length, ReaderAnchor.refind(ReaderAnchor(text.length + 50, 2, ""), text, ver = 1))
    }
}
