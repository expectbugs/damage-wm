package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.app.AppWire
import wm.damage.core.app.ClockSkew

/** Music's motion update, stage L: the PC–phone clock line (`ClockSkew`) and the pong that carries the PC's clock. */
class ClockSkewTest {
    @Test
    fun theBestPingSaysTheDifferenceOnceAndAgainOnlyWhenItMoves() {
        val c = ClockSkew()
        // PC 37 ms ahead; pings of 80, 40 and 120 ms round trip (the PC answers at the midpoint)
        val first = assertNotNull(c.sample(pcMs = 1_000 + 40 + 37, sentMs = 1_000, gotMs = 1_080))
        assertTrue("+37 ms" in first && "± 40 ms" in first && "over the 20 ms" in first, first)
        val better = c.sample(pcMs = 2_000 + 20 + 37 + 6, sentMs = 2_000, gotMs = 2_040)       // the shorter round trip: +43
        assertNull(better, "a move under 10 ms is not said again")
        assertNull(c.sample(pcMs = 3_000 + 60 + 90, sentMs = 3_000, gotMs = 3_120), "a longer round trip does not replace the best")
        val moved = assertNotNull(c.sample(pcMs = 4_000 + 5 + 2, sentMs = 4_000, gotMs = 4_010), "the best moved to +2 ms")
        assertTrue("+2 ms" in moved && "over the" !in moved, moved)
        assertNull(c.sample(pcMs = 0, sentMs = 5_000, gotMs = 4_999), "a negative round trip is no sample")
        assertEquals("""{"t":"pong","k":7,"ms":123}""", AppWire.pong(7, 123).toString())
        assertEquals("""{"t":"pong","k":7}""", AppWire.pong(7).toString(), "an older answer's form stays")
    }
}
