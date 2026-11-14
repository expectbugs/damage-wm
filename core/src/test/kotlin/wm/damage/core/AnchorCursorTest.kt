package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import wm.damage.core.shell.AnchorCursor

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 (`CONTRACT.md` §11.1): the glasses' shared index-to-anchor helper — a cursor is saved as the
 * id of the row it rests on (the index beside it, for an older build), and a restore lands on that row once the rows
 * have arrived, even when the list changed underneath; once, never again after input.
 */
class AnchorCursorTest {
    @Test
    fun aSavedRowIsFoundAgainInAChangedList_once() {
        val rows = listOf("folder:Series", "book:a", "book:b", "book:c")
        assertEquals("book:b", AnchorCursor.saveAnchor(rows, 2))
        assertEquals("", AnchorCursor.saveAnchor(rows, 9))
        val c = AnchorCursor()
        c.restore("book:b")
        // a book added ahead of it on the PC: the cursor follows the row, not the index
        assertEquals(3, c.resolve(listOf("folder:Series", "book:0", "book:a", "book:b", "book:c")))
        assertNull(c.pending)
        assertNull(c.resolve(rows), "spent: a later listing never moves a cursor input may have moved since")
    }

    @Test
    fun aRowThatIsGoneOrNoAnchorKeepsTheRestoredIndex() {
        val c = AnchorCursor()
        c.restore("book:gone")
        assertNull(c.resolve(listOf("book:a")))
        assertNull(c.pending)
        c.restore("")
        assertNull(c.pending, "an empty anchor is none")
        c.restore(null)
        assertNull(c.resolve(listOf("book:a")))
        c.restore("book:a")
        c.clear()
        assertNull(c.resolve(listOf("book:a")), "input before the rows arrived: nothing to resolve")
    }
}
