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
 * stage beside this one found the transport's reading of a cached draw as reaching the panel's edges); turned to
 * DISCARD, and the area painted from the truth in the same flush, when a draw really crosses them.
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

    private fun end(level: Int) = Compositor.EndPlan(0, listOf(band to band)) { _, g: Gray8 -> g.fillRect(band, level * 17) }

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

    @Test
    fun aDrawCrossingTheAreasTurnsItToDiscardAndPaintsTheTruth() {
        val c = fresh()
        c.composed.fillRect(band, 3 * 17)          // the truth in the area is not END (a cover, a badge)
        c.damage(band)
        val (a, denied) = c.assembleWithEnds(Geometry.rectBudget(3), listOf(end(9)))
        assertEquals(setOf(0), denied, "the COMMIT is turned to DISCARD")
        assertTrue(a != null && a.ops.any { it is DisplayOp.Delta || it is DisplayOp.Fill }, "the flush paints the area")
        assertTrue(writes(a).any { it.overlaps(band) }, "in the area: ${writes(a)}")
        for (left in listOf(true, false)) assertEquals(3 * 17 shr 4, c.shadowForTest(left)[band.x + 1, band.y + 1] shr 4, "the model is the truth, not END")
    }

    @Test
    fun noPainterNoCommit() {
        val c = fresh()
        c.composed.fillRect(band, 9 * 17); c.damage(band)
        val (_, denied) = c.assembleWithEnds(Geometry.rectBudget(3), listOf(Compositor.EndPlan(0, listOf(band to band), null)))
        assertEquals(setOf(0), denied, "a list whose owner gave no END painter is answered with DISCARD")
    }
}
