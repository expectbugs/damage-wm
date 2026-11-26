package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wm.damage.core.motion.Motion
import wm.damage.core.shell.WindowLanes
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.windows.music.MusicMotion
import wm.damage.core.windows.music.ScopeRehearsal

/**
 * Music's motion update, stage S: Scope's lane planner (`MusicMotion.scopeLanes`) and its rehearsal, with no shell —
 * T from the room at every height (ruling 4), the anchor that keeps a stretch's bytes while its place moves within the
 * slack (ruling 6), the step budget, and each rehearsal scene played on the firmware model: every step on both lenses
 * today's Scope panned, at the song's place (`ScopeRehearsal`'s checks).
 */
class ScopeLaneTest {
    private val fx = Motion.effects(DamageMsg.Caps(2, 0xDFF), 0x7C)
    private val region = CfwModes.musicRegion(CfwModes.CACHE2_MAX_RIDERS, true)!!
    private val stepMs = 34 * 1000.0 / 1024

    /** T at each height's strip: what fits the room with every column drawn — over 2 s of play at every height, so the
     *  tables are not narrowed (ruling 4) — and the worst stretch's step budget under 80 % of the cap (§7.12: every
     *  designed program keeps 20 %). Printed for the report. */
    @Test
    fun tIsWhatTheRoomGivesAtEveryHeight() {
        val loud = ScopeRehearsal.envelope(IntArray(30_000) { 15 })
        for (h in listOf(288, 352, 416, 480)) {
            val strip = ScopeRehearsal.strip(h)
            val spec = MusicMotion.scopeLanes(0, strip, loud) { it }
            val (plan, blob) = WindowLanes.area(spec, region.first, 8, fx, 60_000, stepMs)
            val cost = plan.program.cost(plan.p)
            println("ScopeLaneTest: height $h, strip $strip: T ${spec.steps} (${"%.0f".format(spec.steps * stepMs)} ms a stretch), " +
                "a loud stretch's area ${blob.size} B of ${WindowLanes.AREA_BYTES}, its step cost $cost u (${cost * 100 / Motion.BUDGET_CAP} % of the cap), " +
                "walk ${plan.program.walkBytes()} B, room for tables ${WindowLanes.tableRoom(spec.statics, spec.glyphs)} B")
            assertTrue(spec.steps * stepMs >= 2_000, "height $h: T ${spec.steps} is under 2 s of play")
            assertTrue(cost <= Motion.BUDGET_CAP * 8 / 10, "height $h: $cost u past 80 % of the cap")
            assertTrue(blob.size <= WindowLanes.AREA_BYTES, "height $h: the area holds it")
        }
    }

    /** The anchor (ruling 6): plans whose place moves within the slack give the same bytes (the place rides in P0 / P1,
     *  a pixel each 5 ms, ∓ the disparity on the two lenses); past it, or back before it, the cells are laid again. */
    @Test
    fun theAnchorKeepsAStretchsBytesWithinTheSlack() {
        val data = ScopeRehearsal.envelope(IntArray(30_000) { 1 + it % 15 })
        var place = 12_345L
        val spec = MusicMotion.scopeLanes(0, ScopeRehearsal.strip(480), data) { place }
        fun plan(at: Long): Pair<IntArray, ByteArray> { place = at; return WindowLanes.area(spec, region.first, 8, fx, 0, stepMs).let { it.first.p to it.second } }
        val (p0, b0) = plan(12_345)
        val (p1, b1) = plan(12_345 + 500)
        assertContentEquals(b0, b1, "half a second on: the same bytes")
        assertEquals(-100, (p1[0] + 8) - (p0[0] + 8), "the place 500 ms on moves the field 100 px left: P0 $p0 → $p1")
        assertEquals(16, p1[1] - p1[0], "P1 is P0 plus twice the disparity")
        val (p2, b2) = plan(12_345 + MusicMotion.SCOPE_SLACK_MS + 100)
        assertFalse(b0.contentEquals(b2), "past the slack: laid again")
        assertTrue(p2[0] + 8 in -8..0, "the new anchor is the 40-ms grid point at or before the place: x ${p2[0] + 8}")
        val (_, b3) = plan(12_000)
        assertFalse(b2.contentEquals(b3), "a place before the anchor (a seek back): laid again")
    }

    /** Each scene the lead looks at (`desktop --motion-check`, `music-scope-*`) holds on the model. */
    @Test
    fun theRehearsalHoldsForEveryScene() {
        for (sc in ScopeRehearsal.scenes()) {
            val r = ScopeRehearsal.play(sc)
            println("ScopeLaneTest: ${sc.name}: ${r.checked} steps checked; ${r.notes.joinToString("; ")}")
            assertEquals(emptyList(), r.failures.take(8), "${sc.name}: ${r.failures.size} failure(s)")
            assertTrue(r.checked >= 2 * r.steps, "${sc.name}: ${r.checked} steps checked")
        }
    }
}
