package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import wm.damage.core.transport.Arm
import wm.damage.core.transport.PairFind
import wm.damage.core.transport.PairFind.Decision
import wm.damage.core.transport.PairFind.Lens
import wm.damage.core.transport.PairFind.Via
import wm.damage.core.transport.PairWatch

/** Stages BL and BLF: the scan's decision — wait, or connect with each lens taken from the scan or by its remembered
 *  address — and the record it reads (the asks counted, the GATT list, the link notices). The pin is today's case
 *  (2026-11-25 15:02: LEFT seen, RIGHT held and unseen). */
class PairFindTest {
    private val seen = Lens(seen = true, heldAsks = 0, ours = false)
    private val held = Lens(seen = false, heldAsks = PairFind.HELD_ASKS, ours = false)
    private val absent = Lens(seen = false, heldAsks = 0, ours = false)

    private fun connect(l: Via, r: Via) = Decision.Connect(l, r)

    // ---- the decision

    @Test fun bothSeenConnectsFromTheScanAsToday() {
        assertEquals(connect(Via.SCAN, Via.SCAN), PairFind.decide(seen, seen))
        assertEquals(connect(Via.SCAN, Via.SCAN), PairFind.decide(seen, seen.copy(heldAsks = 5, ours = true)))
    }

    @Test fun todaysCaseTakesRightByItsAddress() {
        assertEquals(connect(Via.SCAN, Via.HELD), PairFind.decide(seen, held))
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
        // the lens began to advertise after the phone listed it: the scan's device is preferred
        val both = held.copy(seen = true)
        assertEquals(connect(Via.SCAN, Via.SCAN), PairFind.decide(seen, both))
        assertEquals(connect(Via.SCAN, Via.HELD), PairFind.decide(both, held))
    }

    @Test fun aLensNeitherSeenNorHeldKeepsTheWait() {
        assertEquals(Decision.Wait, PairFind.decide(seen, absent))
        assertEquals(Decision.Wait, PairFind.decide(absent, absent))
        assertEquals(Decision.Wait, PairFind.decide(held, absent))
    }

    // ---- the record the decision reads, fed as the phone's glue feeds it

    private val l = "AA:00:00:00:00:01"
    private val r = "AA:00:00:00:00:02"

    /** A scan of the remembered pair with LEFT seen at once; RIGHT asked [n] times with the list answer [listed]. */
    private fun PairWatch.scanWithLeftSeen(n: Int, listed: Boolean?): Decision {
        scanStarts(l, r)
        seen(Arm.LEFT)
        repeat(n) { ask(Arm.LEFT, false, false); ask(Arm.RIGHT, listed, false) }
        return decide()
    }

    @Test fun aLinkUpWithNoDownSinceIsHeldOnItsOwn() {
        val w = PairWatch()
        w.linkNotice(r, up = true, atMs = 1_000)        // the app's own connect, the session before the wake
        assertEquals(connect(Via.SCAN, Via.HELD), w.scanWithLeftSeen(2, listed = false))
        assertEquals(PairWatch.Notice(true, 1_000), w.notice(Arm.RIGHT))
    }

    @Test fun upThenDownIsNotHeld() {
        val w = PairWatch()
        w.linkNotice(r, up = true, atMs = 1_000)
        w.linkNotice(r, up = false, atMs = 2_000)       // the phone dropped the link after the app released it
        assertEquals(Decision.Wait, w.scanWithLeftSeen(2, listed = false))
    }

    @Test fun anUnknownLinkIsNotHeld() {
        val w = PairWatch()                              // no notice in this transport's life
        assertEquals(Decision.Wait, w.scanWithLeftSeen(3, listed = false))
        assertEquals(Decision.Wait, w.scanWithLeftSeen(3, listed = null))   // the list could not be read
        assertEquals(null, w.notice(Arm.RIGHT))
    }

    @Test fun theListAloneStillWorks() {
        val w = PairWatch()
        assertEquals(connect(Via.SCAN, Via.HELD), w.scanWithLeftSeen(2, listed = true))
    }

    @Test fun aNotHeldAskStartsTheCountAgain() {
        val w = PairWatch()
        w.scanStarts(l, r)
        w.seen(Arm.LEFT)
        w.ask(Arm.RIGHT, true, false)
        w.ask(Arm.RIGHT, false, false)
        w.ask(Arm.RIGHT, true, false)
        assertEquals(1, w.heldAsks(Arm.RIGHT))
        assertEquals(Decision.Wait, w.decide())
        w.ask(Arm.RIGHT, true, false)
        assertEquals(connect(Via.SCAN, Via.HELD), w.decide())
    }

    @Test fun aLinkDownEndsTheCountAtOnce() {
        val w = PairWatch()
        w.linkNotice(r, up = true, atMs = 1_000)
        w.scanStarts(l, r)
        repeat(2) { w.ask(Arm.LEFT, false, false); w.ask(Arm.RIGHT, false, false) }
        assertEquals(Decision.Wait, w.decide())          // LEFT not yet seen
        w.linkNotice(r.lowercase(), up = false, atMs = 9_000)   // RIGHT's link goes down between two asks
        w.seen(Arm.LEFT)                                 // and LEFT's advertisement arrives before the next ask
        assertEquals(Decision.Wait, w.decide())
    }

    @Test fun bluetoothOffClearsEveryRecord() {
        val w = PairWatch()
        w.linkNotice(r, up = true, atMs = 1_000)
        w.bluetoothOff()
        assertEquals(Decision.Wait, w.scanWithLeftSeen(2, listed = false))
        assertEquals(null, w.notice(Arm.RIGHT))
    }

    @Test fun aNewScanCountsFromZero() {
        val w = PairWatch()
        w.scanStarts(l, r)
        repeat(3) { w.ask(Arm.RIGHT, true, false) }      // LEFT never seen: that scan waited, then was ended
        w.scanStarts(l, r)
        w.seen(Arm.LEFT)
        w.ask(Arm.RIGHT, true, false)
        assertEquals(Decision.Wait, w.decide())
    }

    @Test fun aFirstTimePairIsNeverTakenByAddress() {
        val w = PairWatch()
        w.linkNotice(r, up = true, atMs = 1_000)
        w.scanStarts(null, null)
        w.seen(Arm.LEFT)
        repeat(3) { w.ask(Arm.RIGHT, true, false) }
        assertEquals(Decision.Wait, w.decide())
    }

    @Test fun aRecordForAnotherAddressDoesNotCount() {
        val w = PairWatch()
        w.linkNotice(r, up = true, atMs = 1_000)         // the old address, still up
        w.scanStarts(l, "AA:00:00:00:00:03")             // the pair was re-remembered with another RIGHT
        w.seen(Arm.LEFT)
        repeat(2) { w.ask(Arm.RIGHT, false, false) }
        assertEquals(Decision.Wait, w.decide())
        assertEquals(null, w.notice(Arm.RIGHT))
    }

    @Test fun aLensThisAppStillHoldsIsNotTakenOnTheRecordEither() {
        val w = PairWatch()
        w.linkNotice(r, up = true, atMs = 1_000)
        w.scanStarts(l, r)
        w.seen(Arm.LEFT)
        repeat(2) { w.ask(Arm.RIGHT, true, ours = true) }
        assertEquals(Decision.Wait, w.decide())
    }
}
