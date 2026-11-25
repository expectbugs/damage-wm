package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import wm.damage.core.shell.WindowLanes
import wm.damage.core.transport.Arm

/**
 * Music's motion update, stage L (`private/build085/MM-lean-spec.md` §5): the held lanes' chain against the firmware
 * model on one virtual clock ([LaneRig]) — the scripted chain, then a seeded walk of the cancel envelope at three
 * timings (the fuzz's short motion, his stage-4 period, the bar's own: T 255 at P 128).
 *
 * What every sequence must hold: no lane instance open on either lens once the release that ended its chain has landed
 * (unless a newer chain opened it); every stretch RIGHT says drew its last step was drawn or folded whole (drawn + folded
 * = T: no seam lost); a notice's timer is never closed by a lane; and with nothing in the way the chain runs again.
 */
class MusicLaneFuzzTest {

    enum class Timing(val period: Int, val steps: Int) { DEFAULT(16, 3), STAGE4(46, 6), BAR(128, 255) }

    private fun lenMs(t: Timing) = t.steps * t.period * 1000L / 1024

    // ------------------------------------------------------------------------------------------------ scripted
    /** The chain: its area written and proven, a PLAY in slot 0, PLAY-AFTERs into slots 1, 0, 1 … each sent with half
     *  the drawing stretch left, every stretch drawn whole and none late; the bar's program written once. */
    @Test
    fun aChainOfStretchesPlaysOnTheLensClockWithOneWriteAndPlayAftersOnly() {
        val t = Timing.STAGE4
        val r = LaneRig(1, t.period, t.steps, fixed = true)
        r.pump()
        r.runUntil(r.nowMs + 8 * lenMs(t) + 500)
        val lane = r.sent
        assertTrue(lane.size >= 8, "a stretch a stretch's length: ${lane.size} — ${r.notes.takeLast(6)}")
        assertEquals(0L, lane[0][3], "the first is a PLAY")
        assertTrue(lane.drop(1).all { it[3] == 1L }, "the rest are PLAY-AFTERs: ${lane.map { it[3] }}")
        assertEquals(lane.indices.map { (it % 2).toLong() }, lane.map { it[1] }, "slots 0 and 1 in turn")
        val drew = r.bodies.filter { it[1] and 0xF000 == WindowLanes.TAG && it[2] == 0 }
        assertTrue(drew.size >= 6, "RIGHT said ${drew.size} stretches drew their last step")
        for (b in drew) assertEquals(t.steps, b[3] + b[4], "drawn + folded = T for tag 0x%04x".format(b[1]))
        assertTrue(r.bodies.none { it[1] and 0xF000 == WindowLanes.TAG && it[2] != 0 }, "no lane stretch closed or refused: ${r.bodies.map { it.toList() }}")
        assertEquals(1, r.notes.count { "written for its programs" in it }, "one area written: ${r.notes}")
        assertTrue(r.notes.none { "ends" in it }, "the chain never ended: ${r.notes.filter { "ends" in it }}")
        // the seams: each PLAY-AFTER went with half the stretch before it left (sent at start + T·P / 2)
        for (i in 2 until lane.size) {
            val gap = lane[i][2] - lane[i - 1][2]
            assertTrue(kotlin.math.abs(gap - lenMs(t)) <= 60, "stretch ${i + 1} went ${gap} ms after the one before, a stretch is ${lenMs(t)} ms")
        }
    }

    /** A seek, a pause, a track change: the chain ends at once, its owners closed by the frame that rewrites the lane,
     *  and the next starts from the song's new place (a seek reuses the written area: the same song, the same bytes). */
    @Test
    fun aChangedKeyEndsTheChainAndTheNextStartsFromTheNewPlace() {
        val t = Timing.STAGE4
        val r = LaneRig(2, t.period, t.steps, fixed = true)
        r.pump(); r.runUntil(r.nowMs + 2 * lenMs(t))
        val before = r.sent.size
        r.seek(30_000)
        assertTrue(r.notes.any { "its key changed" in it }, "the chain ended at the seek: ${r.notes.takeLast(3)}")
        r.runUntil(r.nowMs + 200)
        assertTrue(r.sent.size > before, "a new chain started: ${r.notes.takeLast(4)}")
        assertEquals(0L, r.sent[before][3], "with a PLAY")
        assertEquals(1, r.notes.count { "written for its programs" in it }, "the seek wrote nothing: ${r.notes}")
        r.pause(); r.runUntil(r.nowMs + 200)
        assertEquals(emptyList(), r.laneTagsOpen(), "paused: nothing of the chain open on either lens")
        r.play(); r.track(); r.runUntil(r.nowMs + 300)
        assertEquals(2, r.notes.count { "written for its programs" in it }, "a new song: its program in the next area: ${r.notes}")
        r.runUntil(r.nowMs + lenMs(t))
        assertTrue(r.lanes.open && r.laneTagsOpen().isNotEmpty(), "running again")
    }

    /** Three strikes (here: RIGHT refusing every PLAY with EFFECTS disarmed under it) and the lanes are off for the
     *  session, said once; a session start lifts it. */
    @Test
    fun threeRefusalsTurnTheLanesOffForTheSession() {
        val t = Timing.DEFAULT
        val r = LaneRig(3, t.period, t.steps, fixed = true)
        for (a in Arm.entries) r.sim.conformanceControl(a, wm.damage.core.wire.DamageMsg.OP_FLAGS_SET, 0x3C, r.tick())   // EFFECTS clear
        r.pump(); r.runUntil(r.nowMs + 3_000)
        assertTrue(r.lanes.off != null, "off after three strikes: ${r.notes}")
        assertEquals(1, r.notes.count { "off for this session" in it }, "said once")
        val sends = r.sent.size
        r.runUntil(r.nowMs + 2_000)
        assertEquals(sends, r.sent.size, "nothing more sent while off")
        r.session()
        r.runUntil(r.nowMs + 1_000)
        assertTrue(r.lanes.off == null && r.lanes.open, "a session start lifts it: ${r.notes.takeLast(3)}")
    }

    /** A PLAY-AFTER that lands after its stretch was due (a stalled link) would show the past: the chain starts again. */
    @Test
    fun aLatePlayAfterRestartsTheChain() {
        val t = Timing.STAGE4
        val r = LaneRig(4, t.period, t.steps, fixed = true)
        r.pump(); r.runUntil(r.nowMs + lenMs(t) / 4)
        r.upExtraNext = lenMs(t) + 200                 // the next message (a PLAY-AFTER) is held on the way
        r.runUntil(r.nowMs + 3 * lenMs(t))
        assertTrue(r.notes.any { "landed" in it && "after its step 1 was due" in it }, "said late: ${r.notes}")
        assertTrue(r.lanes.open, "running again")
    }

    // ------------------------------------------------------------------------------------------------- the walk
    @Test
    fun theCancelEnvelopeAtThreeTimings() {
        val n = (System.getenv("MML_FUZZ_N") ?: "40").toInt()
        val fails = ArrayList<String>()
        val figures = StringBuilder()
        for (t in Timing.entries) {
            var stretches = 0; var seams = 0; var restarts = 0; var strikesOff = 0; var events = 0
            for (seed in 1..n.toLong()) {
                val f = walk(seed * 7919 + t.ordinal, t)
                fails += f.fails.map { "${t.name} seed $seed: $it" }
                stretches += f.stretches; seams += f.seams; restarts += f.restarts; strikesOff += f.off; events += f.events
            }
            figures.append("${t.name} (P ${t.period}, T ${t.steps}): $n sequences, $events events, $stretches stretches drawn, $seams seams checked whole, " +
                "$restarts chain ends, $strikesOff sequences off for strikes\n")
        }
        println("MusicLaneFuzzTest (modeled, the firmware model on a virtual clock):\n$figures")
        if (fails.isNotEmpty()) fail(fails.take(30).joinToString("\n") + "\n(${fails.size} in all)")
    }

    private class Walk(val fails: List<String>, val stretches: Int, val seams: Int, val restarts: Int, val off: Int, val events: Int)

    private fun walk(seed: Long, t: Timing): Walk {
        val r = LaneRig(seed, t.period, t.steps)
        val fails = ArrayList<String>()
        val len = lenMs(t)
        var events = 0
        r.pump()
        r.runUntil(r.nowMs + minOf(len, 2_000))
        repeat(12) {
            events++
            when (r.rnd.nextInt(10)) {
                0 -> r.seek(r.rnd.nextInt(60_000) - 20_000L)
                1 -> { r.pause(); r.runUntil(r.nowMs + 50 + r.rnd.nextInt(400)); r.play() }
                2 -> r.track()
                3 -> r.notice(near = false)
                4 -> {                                   // a box over the lane: the gate first, the box's PLAY once the release landed
                    r.boxOver = true; r.pump(); r.runUntil(r.nowMs + 120)
                    r.notice(near = true); r.runUntil(r.nowMs + 700); r.boxOver = false; r.pump()
                }
                5 -> r.keyframe()
                6 -> r.session()
                7 -> r.upExtraNext = len / 2 + r.rnd.nextInt(len.toInt() + 1)
                else -> {}
            }
            // the release's frame lands within a round trip: what an ended chain left open is gone by then
            r.runUntil(r.nowMs + 150)
            val left = r.laneTagsOpen().filter { it in r.endedTags }
            if (left.isNotEmpty()) fails += "[${r.nowMs}] an ended chain's tags ${left.map { "0x%04x".format(it) }} are open on a lens — ${r.notes.takeLast(3)}"
            r.runUntil(r.nowMs + r.rnd.nextInt(maxOf(200, minOf(len.toInt() * 3, 6_000))))
        }
        // nothing in the way: within one stretch and a round trip the chain runs again (unless off for strikes)
        r.runUntil(r.nowMs + len + 400)
        if (r.lanes.off == null && !(r.lanes.open && r.laneTagsOpen().isNotEmpty())) fails += "the chain did not run again: ${r.notes.takeLast(4)}"
        val drew = r.bodies.filter { it[1] and 0xF000 == WindowLanes.TAG && it[2] == 0 }
        for (b in drew) if (b[3] + b[4] != t.steps) fails += "tag 0x%04x drew %d + folded %d, not T %d".format(b[1], b[3], b[4], t.steps)
        fails += r.noticeClosedByLane
        return Walk(fails, r.lanes.stretchesDrawn, drew.size, r.notes.count { "the chain ends" in it }, if (r.lanes.off != null) 1 else 0, events)
    }
}
