package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import wm.damage.core.geom.FidAllocator
import wm.damage.core.geom.FidTracker
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.Emit
import wm.damage.core.transport.FlushRequest
import wm.damage.core.wire.CfwModes

/**
 * APK 0.65, P5 item 13 (the green-gate sweep's GG-2): the emit's own refusals, each handed a flush it must refuse and
 * each asserting the rule id. `GeometryTest` pins the rule FUNCTIONS; nothing handed the emit a flush over the budget,
 * a delta on an unseeded tracker or a box off the grid, so its call sites could go and every test stayed green. The
 * budget at the emit is the one of the three with no downstream oracle (the model applies an over-budget batch and
 * belief still equals glass); the fid and box checks are second lines behind the simulator. Pure: no transport.
 */
class EmitRulesTest {
    private fun deltas(n: Int) = (0 until n).map { i -> DisplayOp.Delta(Rect(0, 8 * i, 32, 8), ByteArray(8) { 1 }) }
    private fun seeded() = FidTracker().also { assertTrue(it.keyframe().isEmpty(), "the keyframe seeds the tracker") }

    /** Six deltas against the 3-deep pipeline's budget of five: BUD001, decided before a fid is taken. Seeded first —
     *  on an unseeded tracker the emit throws FID004 instead, and a pin reading only "throws" would pass without it. */
    @Test
    fun overTheBudgetIsRefusedBeforeAFidIsTaken() {
        val fids = FidAllocator(); val tracker = seeded()
        val before = fids.peek()
        val e = assertFailsWith<LintError> { Emit.encode(FlushRequest(deltas(6), 0, "over"), fids, tracker, window = 3) }
        assertTrue("BUD001" in (e.message ?: ""), "the budget's rule: $e")
        assertEquals(before, fids.peek(), "no fid taken for a refused flush")
    }

    /** The budget's two edges: five at depth 3 and sixteen at depth 1 go out. */
    @Test
    fun theBudgetsEdgesEncode() {
        val f3 = FidAllocator(); val e3 = Emit.encode(FlushRequest(deltas(5), 0, "five"), f3, seeded(), window = 3)
        assertEquals(5, e3.fids.size)
        val f1 = FidAllocator(); val e1 = Emit.encode(FlushRequest(deltas(16), 0, "sixteen"), f1, seeded(), window = 1)
        assertEquals(16, e1.fids.size)
    }

    /** A delta on an unseeded tracker: FID004, and the allocator and the tracker as they were. */
    @Test
    fun aDeltaBeforeAKeyframeIsRefusedAndHandsItsFidBack() {
        val fids = FidAllocator(); val tracker = FidTracker()
        val before = fids.peek(); val lastBefore = tracker.last
        val e = assertFailsWith<LintError> { Emit.encode(FlushRequest(deltas(1), 0, "unseeded"), fids, tracker, window = 3) }
        assertTrue("FID004" in (e.message ?: ""), "the fid rule: $e")
        assertEquals(before, fids.peek(), "the fid handed back")
        assertEquals(lastBefore, tracker.last, "the tracker as it was")
    }

    /** A mode-3 box off the 4 px grid (GEO001); a stereo pair pushed off the panel (GEO002). */
    @Test
    fun aBoxOffTheGridOrOffThePanelIsRefused() {
        val e1 = assertFailsWith<LintError> { CfwModes.delta(Rect(250, 100, 100, 50), ByteArray(8), 1) }
        assertTrue("GEO001" in (e1.message ?: ""), "the alignment rule: $e1")
        val e2 = assertFailsWith<LintError> { Emit.stereo(Rect(0, 34, 640, 32), 4) }
        assertTrue("GEO002" in (e2.message ?: ""), "the bounds rule: $e2")
    }
}
