package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.transport.DisplayOp

/**
 * Build C2, the compositor's half of P3's per-batch answer and P10's diff (`Compositor.assembleWithEnds`): each planned
 * COMMIT's END applied to the model before the diff, so an adopting batch does not send the list's area again; a COMMIT
 * kept when the batch's only draws lie outside the slot's areas (judged by the rects the compositor really wrote — the
 * stage beside this one found the transport's reading of a cached draw as reaching the panel's edges); kept too when a
 * draw really crosses them, which then waits for the next batch (decisions AA.1).
 */
class CompositorEndsTest {
    private val band = Rect(64, 120, 32, 8)
    /** Above and left of the band: outside it by its real rect, inside it by a reading that runs a draw to the panel's
     *  right and bottom edges (the transport's reading of a cached glyph run). */
    private val clock = Rect(16, 40, 16, 4)

    private fun fresh(): Compositor {
        val c = Compositor()
        c.composed.fillRect(band, 5 * 17)
        c.requestKeyframe()
        assertTrue(c.assembleFlush(Geometry.rectBudget(3)) != null, "the keyframe")
        return c
    }

    private fun end(level: Int) = Compositor.EndPlan(0, listOf(band to band), { _, g: Gray8 -> g.fillRect(band, level * 17) })
    private fun endUntil(level: Int, untilMs: Long) = Compositor.EndPlan(0, listOf(band to band), { _, g: Gray8 -> g.fillRect(band, level * 17) }, untilMs = untilMs)

    /** The rects the flush's drawing ops write (the touched list also carries a riding COMMIT's areas, for its hint). */
    private fun writes(a: Compositor.Assembled?): List<Rect> = (a?.ops ?: emptyList()).flatMap { op ->
        when (op) { is DisplayOp.Delta -> listOf(op.box); is DisplayOp.Fill -> listOf(op.left, op.right); is DisplayOp.Keyframe -> listOf(Rect(0, 0, 640, 480)); else -> emptyList() } }

    @Test
    fun anAdoptingBatchDoesNotSendTheListsAreaAgain_P10() {
        val c = fresh()
        c.composed.fillRect(band, 9 * 17)          // the phone's row moved to what the lens drew: END is the truth there
        c.damage(band)
        val (a, denied) = c.assembleWithEnds(Geometry.rectBudget(3), listOf(end(9)))
        assertTrue(denied.isEmpty(), "the COMMIT stands")
        assertTrue(writes(a).none { it.overlaps(band) }, "nothing drawn in the list's area: ${writes(a)} (ops ${a?.ops?.map { it::class.simpleName }})")
        assertEquals(9 * 17 shr 4, c.shadowForTest(true)[band.x + 1, band.y + 1] shr 4, "the model holds END")
    }

    @Test
    fun aDrawOutsideTheAreasKeepsTheCommit() {
        val c = fresh()
        c.composed.fillRect(band, 9 * 17); c.damage(band)
        c.composed.fillRect(clock, 12 * 17); c.damage(clock)
        val (a, denied) = c.assembleWithEnds(Geometry.rectBudget(3), listOf(end(9)))
        assertTrue(denied.isEmpty(), "a draw outside the areas does not meet them")
        assertTrue(writes(a).any { it.overlaps(clock) } && writes(a).none { it.overlaps(band) }, "the clock only: ${writes(a)}")
    }

    /** Decisions AA.1: a draw crossing the areas (a row staged before Main's last repaint of it, a badge) does not turn
     *  the COMMIT — it waits: nothing inside the areas rides the COMMIT's flush, the model holds END there, and the next
     *  assemble paints the truth over it. */
    @Test
    fun aDrawCrossingTheAreasWaitsForTheNextBatch() {
        val c = fresh()
        c.composed.fillRect(band, 3 * 17)          // the truth in the area is not END
        c.damage(band)
        c.composed.fillRect(clock, 12 * 17); c.damage(clock)
        val (a, denied) = c.assembleWithEnds(Geometry.rectBudget(3), listOf(end(9)))
        assertTrue(denied.isEmpty(), "the COMMIT stands")
        assertTrue(writes(a).none { it.overlaps(band) }, "nothing drawn in the area beside the COMMIT: ${writes(a)}")
        assertTrue(writes(a).any { it.overlaps(clock) }, "a draw outside the areas still rides: ${writes(a)}")
        for (left in listOf(true, false)) assertEquals(9 * 17 shr 4, c.shadowForTest(left)[band.x + 1, band.y + 1] shr 4, "the model holds END")
        assertTrue(c.hasPending, "the area's cells stay dirty")
        val next = c.assembleFlush(Geometry.rectBudget(3))
        assertTrue(writes(next).any { it.overlaps(band) }, "the next batch paints the area: ${writes(next)}")
        for (left in listOf(true, false)) assertEquals(3 * 17 shr 4, c.shadowForTest(left)[band.x + 1, band.y + 1] shr 4, "then the model is the truth")
    }

    /** Decisions AA.1's loop: a delta the planner spans ACROSS the areas (two dirty cells either side of the band, one
     *  rect allowed) writes inside them — it is held back too, and the flush taken again, so nothing beside the COMMIT
     *  meets the areas; the next assemble paints both cells. */
    @Test
    fun aDeltaSpanningTheAreasIsHeldToo() {
        fun sides(c: Compositor) {
            c.composed.fillRect(Rect(band.x - 8, band.y, 4, 2), 12 * 17); c.composed.fillRect(Rect(band.right + 4, band.y, 4, 2), 12 * 17)
            c.damage(Rect(band.x - 8, band.y, 4, 2)); c.damage(Rect(band.right + 4, band.y, 4, 2))
        }
        val plain = fresh(); sides(plain)
        assertTrue(writes(plain.assembleFlush(Geometry.rectBudget(1))).any { it.overlaps(band) }, "the planner spans the band here (else this proves nothing)")
        val c = fresh(); sides(c)
        val (a, denied) = c.assembleWithEnds(Geometry.rectBudget(1), listOf(end(5)))
        assertTrue(denied.isEmpty(), "the COMMIT stands")
        assertTrue(writes(a).none { it.overlaps(band) }, "nothing written inside the areas beside the COMMIT: ${writes(a)}")
        val next = c.assembleFlush(Geometry.rectBudget(1))
        assertTrue(writes(next).isNotEmpty(), "the held cells go in the next batch")
        assertEquals(12 * 17 shr 4, c.shadowForTest(true)[band.x - 7, band.y] shr 4, "then the model is the truth there")
    }

    /** Stage DI, the chrome overlap settled: with the bound list's areas kept out, two deltas either side of them (the
     *  top divider's mark and Main's rail thumb, at one disparity) are not merged into one rect across them — the
     *  compressed union would be cheaper, which is why the planner took it. Without the keep-out the union is taken. */
    @Test
    fun noMergeAcrossTheBoundListsAreas_DI() {
        fun sides(c: Compositor) {
            c.composed.fillRect(Rect(band.x - 8, band.y, 4, 2), 12 * 17); c.composed.fillRect(Rect(band.right + 4, band.y, 4, 2), 12 * 17)
            c.damage(Rect(band.x - 8, band.y, 4, 2)); c.damage(Rect(band.right + 4, band.y, 4, 2))
        }
        val plain = fresh(); sides(plain)
        assertTrue(writes(plain.assembleFlush(Geometry.rectBudget(3))).any { it.overlaps(band) }, "without the keep-out the union across the band is taken (else this proves nothing)")
        val c = fresh(); sides(c)
        c.keepOut = listOf(band to band)
        val a = c.assembleFlush(Geometry.rectBudget(3))
        assertTrue(writes(a).none { it.overlaps(band) }, "nothing written inside the kept-out areas: ${writes(a)}")
        assertEquals(12 * 17 shr 4, c.shadowForTest(true)[band.x - 7, band.y] shr 4, "both cells painted")
        assertEquals(12 * 17 shr 4, c.shadowForTest(true)[band.right + 5, band.y] shr 4, "both cells painted")
    }

    /** Decisions AF.7: the cells held behind a COMMIT wait for the adopted motion's end — no assemble before then sends
     *  them (a draw outside the areas still goes), and once released the next assemble paints them. */
    @Test
    fun theHeldCellsWaitForTheMotionsEnd_AF7() {
        val c = fresh()
        c.composed.fillRect(band, 3 * 17); c.damage(band)           // the truth in the area is not END
        val (a, _) = c.assembleWithEnds(Geometry.rectBudget(3), listOf(Compositor.EndPlan(0, listOf(band to band), { _, g: Gray8 -> g.fillRect(band, 9 * 17) }, untilMs = 1_000)), nowMs = 0)
        assertTrue(writes(a).none { it.overlaps(band) }, "nothing in the area beside the COMMIT")
        assertTrue(c.deferring && !c.hasPending, "the area's cells wait (deferring, not pending)")
        c.composed.fillRect(clock, 12 * 17); c.damage(clock)
        assertTrue(!c.releaseDeferred(500), "not before the motion's end")
        val mid = c.assembleFlush(Geometry.rectBudget(3))
        assertTrue(writes(mid).any { it.overlaps(clock) } && writes(mid).none { it.overlaps(band) }, "a draw outside goes, the area still waits: ${writes(mid)}")
        assertTrue(c.releaseDeferred(1_000), "released at the motion's end")
        val after = c.assembleFlush(Geometry.rectBudget(3))
        assertTrue(writes(after).any { it.overlaps(band) }, "then the area is painted: ${writes(after)}")
        for (left in listOf(true, false)) assertEquals(3 * 17 shr 4, c.shadowForTest(left)[band.x + 1, band.y + 1] shr 4)
    }

    /** A keyframe writes the whole model and closes every instance: a COMMIT cannot ride it (END would not survive in
     *  the model), so the slot is answered with DISCARD and the keyframe paints the truth. */
    @Test
    fun aKeyframesBatchDeniesTheCommit() {
        val c = fresh()
        c.composed.fillRect(band, 9 * 17); c.damage(band)
        c.requestKeyframe()
        val (a, denied) = c.assembleWithEnds(Geometry.rectBudget(3), listOf(end(9)))
        assertEquals(setOf(0), denied, "the keyframe's batch answers with DISCARD")
        assertTrue(a != null && a.keyframe, "the keyframe goes")
    }

    @Test
    fun noPainterNoCommit() {
        val c = fresh()
        c.composed.fillRect(band, 9 * 17); c.damage(band)
        val (_, denied) = c.assembleWithEnds(Geometry.rectBudget(3), listOf(Compositor.EndPlan(0, listOf(band to band), null)))
        assertEquals(setOf(0), denied, "a list whose owner gave no END painter is answered with DISCARD")
    }

    /** Decisions AL.2 (V2 reader B's component test, inverted): during the held cells' wait, a sub the planner spans across
     *  the areas (one rect allowed, a draw above and below them with no keep-out) is held whole by the re-hold loop; what it
     *  held waits with the areas and is made dirty at the release, so the cells above and below go then. */
    @Test
    fun aSpanHeldWholeDuringTheDeferralGoesAtTheRelease_AL2() {
        val above = Rect(band.x, band.y - 20, 4, 2)
        val below = Rect(band.x, band.bottom + 12, 4, 2)
        fun sides(c: Compositor) {
            c.composed.fillRect(above, 12 * 17); c.composed.fillRect(below, 12 * 17); c.damage(above); c.damage(below)
        }
        val plain = fresh(); sides(plain)
        assertTrue(writes(plain.assembleFlush(Geometry.rectBudget(1))).any { it.overlaps(band) }, "the planner spans the band here, one rect allowed")
        val c = fresh()
        c.composed.fillRect(band, 3 * 17); c.damage(band)
        c.assembleWithEnds(Geometry.rectBudget(3), listOf(Compositor.EndPlan(0, listOf(band to band), { _, g: Gray8 -> g.fillRect(band, 9 * 17) }, untilMs = 1_000)), nowMs = 0)
        assertTrue(c.deferring, "deferring until the motion's end")
        sides(c)
        val mid = writes(c.assembleFlush(Geometry.rectBudget(1)))
        assertTrue(mid.none { it.overlaps(band) }, "nothing over the areas during the wait: $mid")
        assertTrue(c.releaseDeferred(1_000), "released at the motion's end")
        val sent = ArrayList<Rect>()
        repeat(3) { sent += writes(c.assembleFlush(Geometry.rectBudget(1))) }
        val unk = c.unknownForTest(true); val cw = c.cellsWForTest; val cellW = 640 / cw; val cellH = 480 / (unk.size / cw)
        fun unknownAt(r: Rect) = unk[(r.y / cellH) * cw + r.x / cellW]
        assertTrue((mid + sent).any { it.overlaps(above) } && (mid + sent).any { it.overlaps(below) } && !unknownAt(above) && !unknownAt(below),
            "the cells above and below the band were sent: during $mid, after $sent; unknown above ${unknownAt(above)} below ${unknownAt(below)}")
        assertEquals(12, c.shadowForTest(true)[above.x + 1, above.y] shr 4, "above: the truth")
        assertEquals(12, c.shadowForTest(true)[below.x + 1, below.y] shr 4, "below: the truth")
    }

    /** Decisions AL.6 (R3): a wait that starts with nothing deferred takes its own end — a clock stepped back after the last
     *  one is not carried into it; within one burst the latest end holds. */
    @Test
    fun aNewWaitTakesItsOwnEnd_AL6() {
        val c = fresh()
        c.composed.fillRect(band, 3 * 17); c.damage(band)
        c.assembleWithEnds(Geometry.rectBudget(3), listOf(endUntil(9, 10_000)), nowMs = 9_000)
        assertTrue(c.releaseDeferred(10_000), "released at the first motion's end")
        c.composed.fillRect(band, 4 * 17); c.damage(band)
        c.assembleWithEnds(Geometry.rectBudget(3), listOf(endUntil(9, 5_000)), nowMs = 4_000)    // the clock stepped back
        assertEquals(5_000, c.deferUntilMs, "the new wait's own end")
        c.assembleWithEnds(Geometry.rectBudget(3), listOf(endUntil(9, 4_800)), nowMs = 4_100)
        assertEquals(5_000, c.deferUntilMs, "within the burst, the latest end")
    }
}
