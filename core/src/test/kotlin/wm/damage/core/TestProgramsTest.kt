package wm.damage.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.motion.TestPrograms
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.CfwModes

/**
 * Build C1, package D3b (spec §3 D3b, decision A.15): the feel session's test programs and the probe that plays one.
 * Context for the reader: Adam's own glasses on his own firmware fork; nothing here contacts a device — the stand-in is
 * the simulator behind `SimTransport`. Each program lays out in the top 64 KiB of the session's cache with its first
 * read range before its captures; `probe motion=NAME` sends its records and its PLAY only while ENGINE is in force, and
 * nothing for a name that is not a test program.
 */
class TestProgramsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun clean() { scope.cancel() }

    private val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
    private val notes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    /** A, the layout: every record and capture of every program inside the top [CfwModes.STAGE_RESERVE] bytes of a
     *  160 and a 256 KiB cache, the first read range (records, tables, program) wholly before the capture range. */
    @Test
    fun everyProgramLaysOutInTheTopOfTheCacheWithItsCapturesAfterItsRecords_A15() {
        for (size in listOf(CfwModes.CACHE2_MAX, CfwModes.CACHE2_MAX_RIDERS)) for (name in TestPrograms.NAMES) {
            val tp = TestPrograms.build(name, size)!!
            val first = tp.program.firstRange(tp.params)
            val lo = tp.writes.minOf { it.first * 4 }
            val hi = tp.writes.maxOf { (o, b) -> o * 4 + b.size }
            assertTrue(lo >= size - CfwModes.STAGE_RESERVE, "$name at ${size / 1024} KiB: the first write at $lo is below the reserve")
            assertTrue(tp.end <= size, "$name at ${size / 1024} KiB: ends at ${tp.end}")
            assertEquals(lo until hi, first, "$name: what is written is the first read range, tight")
            if (tp.program.captureBytes > 0) assertTrue(tp.cap4 * 4 >= hi, "$name: the captures at ${tp.cap4 * 4} start after the records end at $hi")
            // the messages: each record's bytes once, in mode-19 messages, then the PLAY
            val msgs = tp.messages()
            assertEquals(CfwModes.MOTION_MODE, msgs.last()[0].toInt(), "$name: the PLAY goes last")
            assertTrue(msgs.dropLast(1).all { it[0].toInt() == 19 }, "$name: records go as mode-19 writes")
        }
        assertNull(TestPrograms.build("bogus", CfwModes.CACHE2_MAX), "a name that is not a test program builds nothing")
    }

    private fun collect(t: SimTransport) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { t.events.collect { if (it is TransportEvent.Note) notes.add(it.kind to it.detail) } }
    }

    /** A session on a C1 build (0x1ff) at ladder stage [stage]; [seen] takes the mode-19 and mode-26 images the
     *  simulator receives on RIGHT. */
    private suspend fun session(stage: Int, seen: MutableList<ByteArray>): Pair<GlassFirmwareSim, SimTransport> {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        collect(t)
        if (stage != 0) t.devProbe("c1", "$stage")
        t.start(full)
        sim.imageObserver = { arm, img -> val m = img[0].toInt() and 0x7F; if (arm == Arm.RIGHT && (m == 19 || m == CfwModes.MOTION_MODE)) seen += img }
        return sim to t
    }

    /** The probe plays the named program with ENGINE in force — its records in the top of the cache, then its PLAY, and
     *  the instance opens over the program's areas — and sends nothing at stage 0 (ENGINE not armed) or for a name
     *  that is not a test program. */
    @Test
    fun theProbePlaysATestProgramOnlyWithEngineInForce_A15(): Unit = runBlocking {
        val off = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val (_, t0) = session(0, off)
        t0.devProbe("motion", "popover")
        // one evaluation decides: the refusal note, or an image that should not have gone
        waitUntil(10_000, message = { "the refusal note: $notes" }) { off.isNotEmpty() || notes.any { it.first == "motion" && "ENGINE is not in force" in it.second } }
        delay(200)
        assertEquals(0, off.size, "stage 0: no record and no PLAY reached the glasses")
        assertTrue(notes.any { it.first == "motion" && "ENGINE is not in force" in it.second }, "the note says why: $notes")
        t0.stop()

        val seen = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        val (sim, t) = session(3, seen)
        assertEquals(0x2c, t.state.value.flagsInForce, "stage 3: ENGINE in force")
        t.devProbe("motion", "bogus")
        delay(200)
        assertEquals(0, seen.size, "a name that is not a test program sends nothing")
        t.devProbe("motion", "popover")
        val tp = TestPrograms.build("popover", t.state.value.cacheSize)!!
        waitUntil(10_000, message = { "the PLAY landed: ${seen.size} images; $notes" }) { sim.motionWindows(Arm.RIGHT).any { it.slot == 0 } }
        assertEquals(tp.messages().map { it.toList() }, seen.map { it.toList() }, "the program's records, then its PLAY, as built")
        val w = sim.motionWindows(Arm.RIGHT).single()
        assertEquals(tp.program.tracks.map { it.areaR }, w.areas, "the instance opened over the program's areas")
        assertTrue(notes.any { it.first == "motion" && "test program sent (popover" in it.second }, "the probe says what it sent: $notes")
        t.stop()
    }
}
