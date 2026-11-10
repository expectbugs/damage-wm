package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wm.damage.core.transport.LinkEstimator

/**
 * `HANDOFF.md` §73: the link estimator as medians over isolated flushes. Each case is one of the
 * four defects the phone's journal of 2026-11-09 showed in the two EMAs it replaces; [Ema] is
 * that arithmetic (`CfwTransportBase.updateEma` before §73), kept here so every pin says what
 * the unfixed estimator read on the same observations.
 */
class LinkEstimatorTest {

    /** The estimator §73 replaced, verbatim: α = 0.3, every flush, the sample clamped at 0. */
    private class Ema {
        var floor = 60.0
        var transfer = 20.0
        fun sample(bytes: Int, ackMs: Long) {
            val a = 0.3
            if (bytes < 400) floor = floor * (1 - a) + ackMs * a
            if (bytes >= 1000) transfer = transfer * (1 - a) + (maxOf(0.0, ackMs - floor) / (bytes / 1000.0)) * a
        }
    }

    @Test
    fun aTermReadsItsDefaultUntilItHasThreeObservations() {
        val e = LinkEstimator()
        assertEquals(60.0, e.floorMs); assertEquals(20.0, e.transferMsPerKb)
        e.sample(100, 90, isolated = true); e.sample(100, 90, isolated = true)
        assertEquals(60.0, e.floorMs, "two small flushes are not yet a floor")
        e.sample(100, 90, isolated = true)
        assertEquals(90.0, e.floorMs)
        e.sample(3000, 390, isolated = true); e.sample(3000, 390, isolated = true)
        assertEquals(20.0, e.transferMsPerKb, "two big flushes are not yet a transfer term")
        e.sample(3000, 390, isolated = true)
        assertEquals(100.0, e.transferMsPerKb, 1e-9)
    }

    /** 12:06 on 2026-11-09: a 33 B flush that waited behind a 3 KB chunk acked in 324 ms and the
     *  EMA floor went 117 → 179; the 25 chunks after it then read 10 ms/KB where isolated flushes
     *  said 29. A flush that was not alone on the lane is no observation. */
    @Test
    fun aSmallFlushQueuedBehindABigOneDoesNotMoveTheFloor() {
        val e = LinkEstimator(); val old = Ema()
        repeat(9) { e.sample(58, 58, isolated = true); old.sample(58, 58) }
        assertEquals(58.0, e.floorMs)
        assertFalse(e.sample(33, 324, isolated = false), "a queued flush is not an observation")
        old.sample(33, 324)
        assertEquals(58.0, e.floorMs, "the floor stands")
        assertTrue(old.floor > 130.0, "the EMA booked the big flush's time as the floor: ${old.floor}")
        repeat(25) { e.sample(3069, 148, isolated = true); old.sample(3069, 148) }
        assertEquals((148 - 58) / 3.069, e.transferMsPerKb, 1e-6)       // 29.3 ms/KB
        assertTrue(old.transfer < 12.0, "the EMA read the same chunks against its raised floor: ${old.transfer}")
    }

    /** 14:18:00: one 1,919 B flush acked in 335 ms (116 ms/KB against a floor of 112) moved the
     *  EMA 44.9 → 66.3 and flipped the regime. One reading does not move a median of nine. */
    @Test
    fun oneSlowSampleAmongNineDoesNotMoveTheEstimate() {
        val e = LinkEstimator(); val old = Ema()
        repeat(9) { e.sample(100, 60, isolated = true) }
        old.floor = 60.0; old.transfer = 44.9
        repeat(9) { e.sample(3000, 60 + 135, isolated = true) }          // 45 ms/KB
        assertEquals(45.0, e.transferMsPerKb, 1e-9)
        e.sample(1919, 60 + 223, isolated = true)                        // 116 ms/KB
        old.sample(1919, 60 + 223)
        assertEquals(45.0, e.transferMsPerKb, 1e-9, "the median stands")
        assertTrue(old.transfer > 65.0, "the EMA crossed the slow edge on that one reading: ${old.transfer}")
        // five of nine is a change: the median follows a link that really did slow down
        repeat(4) { e.sample(3000, 60 + 348, isolated = true) }          // 116 ms/KB
        assertEquals(116.0, e.transferMsPerKb, 0.2)
    }

    /** A big flush acked at or under the floor is no reading; the EMA fed it in as 0 and lost
     *  30 % of its estimate each time (four such samples on 2026-11-09). */
    @Test
    fun anAckNotAboveTheFloorIsSkippedNotFedAsZero() {
        val e = LinkEstimator(); val old = Ema()
        repeat(3) { e.sample(100, 120, isolated = true) }
        repeat(3) { e.sample(2000, 220, isolated = true) }               // 50 ms/KB
        old.floor = 120.0; old.transfer = 50.0
        assertEquals(50.0, e.transferMsPerKb, 1e-9)
        assertFalse(e.sample(1623, 110, isolated = true), "an ack under the floor is not an observation")
        old.sample(1623, 110)
        assertEquals(50.0, e.transferMsPerKb, 1e-9)
        assertEquals(3, e.transferSamples)
        assertEquals(35.0, old.transfer, 1e-9, "the EMA took a zero")
    }

    /** A session's first big flushes are its atlas upload, ahead of any small flush. Priced against
     *  the 60 ms default on a link whose floor is 130 they read 87 ms/KB — SLOW on a default — and
     *  stood in the ring until nine more big flushes came. They are priced when the floor is. */
    @Test
    fun bigFlushesTimedBeforeTheFloorIsMeasuredArePricedWhenItIs() {
        val e = LinkEstimator()
        repeat(9) { assertTrue(e.sample(3069, 326, isolated = true), "held for the floor") }
        assertEquals(20.0, e.transferMsPerKb, "no transfer term before the floor is measured")
        assertEquals(0, e.transferSamples)
        repeat(2) { e.sample(100, 130, isolated = true) }
        assertEquals(0, e.transferSamples)
        e.sample(100, 130, isolated = true)                              // the floor's third observation
        assertEquals(9, e.transferSamples)
        assertEquals((326 - 130) / 3.069, e.transferMsPerKb, 1e-6)       // 63.9, not (326 - 60) / 3.069 = 86.7
        // and a held flush whose ack is not above the measured floor is dropped, never a zero
        val f = LinkEstimator()
        f.sample(2000, 100, isolated = true)
        repeat(3) { f.sample(100, 130, isolated = true) }
        assertEquals(0, f.transferSamples)
    }

    /** A cache write is never a floor observation (its ack floor against a display flush's is not
     *  measured), but a big one prices the transfer term like any flush. */
    @Test
    fun aCacheWriteIsNoFloorObservation() {
        val e = LinkEstimator()
        repeat(5) { assertFalse(e.sample(85, 62, isolated = true, floor = false)) }
        assertEquals(0, e.floorSamples)
        repeat(3) { e.sample(100, 90, isolated = true) }
        repeat(3) { assertTrue(e.sample(3069, 240, isolated = true, floor = false)) }
        assertEquals((240 - 90) / 3.069, e.transferMsPerKb, 1e-6)
    }

    /** A session start clears both terms: the regime never stands on a reading taken before the
     *  link came back (a SLOW regime stood from before 08:47 to 12:06 on 2026-11-09). */
    @Test
    fun aSessionStartClearsBothTerms() {
        val e = LinkEstimator()
        repeat(9) { e.sample(100, 140, isolated = true) }
        repeat(9) { e.sample(3000, 140 + 300, isolated = true) }
        assertEquals(140.0, e.floorMs); assertEquals(100.0, e.transferMsPerKb, 1e-9)
        e.reset()
        assertEquals(60.0, e.floorMs); assertEquals(20.0, e.transferMsPerKb)
        assertEquals(0, e.floorSamples); assertEquals(0, e.transferSamples)
    }

    /** The bands are §32's: 400..999 B feeds neither term, and the rings hold nine. */
    @Test
    fun theBandsAndTheRing() {
        val e = LinkEstimator()
        assertFalse(e.sample(400, 80, isolated = true)); assertFalse(e.sample(999, 80, isolated = true))
        assertTrue(e.sample(399, 80, isolated = true)); assertTrue(e.sample(1000, 200, isolated = true))
        repeat(20) { e.sample(100, 70, isolated = true) }
        assertEquals(LinkEstimator.RING, e.floorSamples)
        assertEquals(70.0, e.floorMs, "the first reading has left the ring")
        assertEquals(1, e.transferSamples, "the big flush that came before the floor was priced once it was measured")
    }

    /** An instant link (every harness): acks of 0–2 ms never read as a slow transfer term. */
    @Test
    fun anInstantLinkStaysFast() {
        val e = LinkEstimator()
        repeat(30) { e.sample(100, (it % 3).toLong(), isolated = true); e.sample(4000, (it % 3).toLong(), isolated = true) }
        assertTrue(e.transferMsPerKb < 35.0, "an instant link is the fast regime: ${e.transferMsPerKb}")
    }
}
