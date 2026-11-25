package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import wm.damage.core.transport.PairFind
import wm.damage.core.transport.PairFind.Decision
import wm.damage.core.transport.PairFind.Lens
import wm.damage.core.transport.PairFind.Via

/** Stage BL: the scan's decision — wait, or connect with each lens taken from the scan or by its remembered
 *  address. The table of cases; the pin is today's case (2026-11-25 15:02: LEFT seen, RIGHT held and unseen). */
class PairFindTest {
    private val seen = Lens(remembered = true, seen = true, heldAsks = 0, ours = false)
    private val held = Lens(remembered = true, seen = false, heldAsks = PairFind.HELD_ASKS, ours = false)
    private val absent = Lens(remembered = true, seen = false, heldAsks = 0, ours = false)

    private fun connect(l: Via, r: Via) = Decision.Connect(l, r)

    @Test fun bothSeenConnectsFromTheScanAsToday() {
        assertEquals(connect(Via.SCAN, Via.SCAN), PairFind.decide(seen, seen))
        // a first-time pair (nothing remembered) found by name: the same answer
        val fresh = seen.copy(remembered = false)
        assertEquals(connect(Via.SCAN, Via.SCAN), PairFind.decide(fresh, fresh))
    }

    @Test fun todaysCaseTakesRightByItsAddress() {
        assertEquals(connect(Via.SCAN, Via.HELD), PairFind.decide(seen, held))
        // held on more asks than needed: the same
        assertEquals(connect(Via.SCAN, Via.HELD), PairFind.decide(seen, held.copy(heldAsks = 40)))
    }

    @Test fun eitherLensOrBothCanBeTakenByAddress() {
        assertEquals(connect(Via.HELD, Via.SCAN), PairFind.decide(held, seen))
        assertEquals(connect(Via.HELD, Via.HELD), PairFind.decide(held, held))
    }

    @Test fun oneAskIsNotEnough() {
        val once = held.copy(heldAsks = 1)
        assertEquals(Decision.Wait, PairFind.decide(seen, once))
        assertEquals(Decision.Wait, PairFind.decide(once, once))
    }

    @Test fun aLensThisAppStillHoldsIsNeverTaken() {
        val ours = held.copy(ours = true)
        assertEquals(Decision.Wait, PairFind.decide(seen, ours))
        assertEquals(Decision.Wait, PairFind.decide(ours, seen))
    }

    @Test fun aLensTheScanHasSeenIsTakenFromTheScan() {
        // the lens began to advertise after Android listed it: the scan's device is preferred
        val both = held.copy(seen = true)
        assertEquals(connect(Via.SCAN, Via.SCAN), PairFind.decide(seen, both))
        assertEquals(connect(Via.SCAN, Via.HELD), PairFind.decide(both, held))
    }

    @Test fun aLensWithNoRememberedAddressIsNeverTakenByAddress() {
        val unknown = held.copy(remembered = false)
        assertEquals(Decision.Wait, PairFind.decide(seen, unknown))
        assertEquals(Decision.Wait, PairFind.decide(unknown, seen))
    }

    @Test fun aLensNeitherSeenNorHeldKeepsTheWait() {
        assertEquals(Decision.Wait, PairFind.decide(seen, absent))
        assertEquals(Decision.Wait, PairFind.decide(absent, absent))
        assertEquals(Decision.Wait, PairFind.decide(held, absent))
    }
}
