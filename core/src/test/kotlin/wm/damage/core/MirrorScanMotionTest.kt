package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.motion.TestPrograms
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport

/**
 * Build C2, stage DP1 — the lead's decision F (`C2-decisions.md`, on stage DM0's read): the mirror agreement scan under
 * an open motion. While an instance is open its areas are the lens's own to draw and are left out of the compare; the
 * scan took them out pixel by pixel, about ten times the plain scan's cost, on every FlushDone (365 ms seen on his phone
 * on 2026-11-18 at 16:21:12). The scan now takes them out of each row as spans. The measure is the journal's `loop`
 * note (`mirror n= total= max=`, ms) with a motion open: C1's four test programs played through the simulator with the
 * transport's clock held, so each stays open, and twenty small repaints beside it — and the same twenty with none open.
 * The numbers are printed (sim, not glass: the phone's are read after the install); what is asserted is that every
 * scan ran under the open motion, compared rows, and found nothing — and that a pixel that differs beside the motion's
 * area on one of its rows is still found (a span the scan dropped would hide it).
 */
class MirrorScanMotionTest {
    private val tmp: Path = Files.createTempDirectory("mirror-scan-motion")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private fun lines(journal: Path): List<String> = if (Files.exists(journal)) Files.readAllLines(journal) else emptyList()
    private fun loopNotes(journal: Path): List<String> = lines(journal).filter { "\"kind\":\"loop\"" in it }
        .map { Regex(""""detail":"((?:[^"\\]|\\.)*)"""").find(it)!!.groupValues[1] }
    private fun mirror(detail: String): Triple<Int, Double, Double>? =
        Regex("""mirror n=(\d+) total=([\d.]+) max=([\d.]+)""").find(detail)?.groupValues?.let { Triple(it[1].toInt(), it[2].toDouble(), it[3].toDouble()) }
    private fun rows(detail: String): Long = Regex("""rows=(\d+)""").find(detail)!!.groupValues[1].toLong()

    /** A repaint below every test program's areas (their lowest edge is y 336), whatever the lenses' disparity — a flush
     *  that met an open area would close the instance in its batch. */
    private suspend fun edgeRepaint(shell: Shell, n: Int) {
        val done = CompletableDeferred<Unit>()
        shell.services.runOnShell {
            val r = Rect(40 + (n % 10) * 56, 380 + (n / 10 % 2) * 32, 40, 16)
            shell.comp.composed.fillRect(r, ((n % 12) + 3) * 17)
            shell.comp.damage(r)
            done.complete(Unit)
        }
        done.await()
    }

    private class Run(val name: String, val scans: Int, val totalMs: Double, val maxMs: Double, val rows: Long) {
        override fun toString() = "| $name | $scans | ${"%.2f".format(totalMs / maxOf(1, scans))} | ${"%.1f".format(maxMs)} | $rows |"
    }

    /** One session: [program] played (none: no motion), twenty repaints, the minute's `loop` note read. */
    private suspend fun measure(program: String?): Run {
        val dir = Files.createTempDirectory(tmp, "run")
        val journal = dir.resolve("journal.jsonl")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
        val held = 1_763_480_000_000L                    // the transport's clock, held: the engine draws no step and no END
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { held })
        t.devProbe("c1", "3")
        val shell = Shell(FakeText(), t, Persistence(dir.resolve("s.json")), journal, scope, HELD_CLOCK)
        shell.start()
        waitUntil(30_000, 10, { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        assertEquals(0x2c, t.state.value.flagsInForce, "ENGINE in force")
        if (program != null) {
            t.devProbe("motion", program)
            waitUntil(10_000, 10, { "$program's PLAY lands" }) { sim.motionWindows(Arm.LEFT).isNotEmpty() && shell.isQuiescent() }
        }
        shell.postMinuteTick()                             // the baseline: what the start ran goes into this note
        waitUntil(10_000, 10, { "the baseline tick" }) { shell.isQuiescent() }
        val before = loopNotes(journal).size
        for (n in 1..20) {
            edgeRepaint(shell, n)
            waitUntil(10_000, 10, { "repaint $n settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        }
        if (program != null) assertTrue(sim.motionWindows(Arm.LEFT).isNotEmpty(), "$program: still open after the repaints — every scan ran under it")
        shell.postMinuteTick()
        waitUntil(10_000, 10, { "the measured note: ${loopNotes(journal)}" }) { loopNotes(journal).size > before }
        val notes = loopNotes(journal).drop(before)
        val m = notes.mapNotNull { mirror(it) }
        assertEquals(0, shell.divergencesReported, "${program ?: "no motion"}: belief and mirror agree outside the motion: ${shell.lastDivergence}")
        shell.stop()
        return Run(program ?: "(no motion)", m.sumOf { it.first }, m.sumOf { it.second }, m.maxOfOrNull { it.third } ?: 0.0, notes.sumOf { rows(it) })
    }

    @Test
    fun theMirrorScanUnderAnOpenMotionCostsWhatThePlainScanCosts_C2F(): Unit = runBlocking {
        val runs = listOf(measure(null)) + listOf("list", "popover", "recede", "flip").map { measure(it) }
        println("MirrorScanMotionTest (sim, this PC's JVM): | program | scans | mean ms | max ms | rows compared |")
        for (r in runs) println("MirrorScanMotionTest: $r")
        for (r in runs) {
            assertTrue(r.scans >= 20, "${r.name}: twenty repaints are twenty scans or more: $r")
            assertTrue(r.rows > 0, "${r.name}: the scans compared rows: $r")
        }
    }

    /** The compare's correctness under an open motion: a pixel the shell never drew, written onto the glasses beside the
     *  motion's area — right of it, on one of its rows, and in another session left of it — is found (a divergence),
     *  while the area itself is not compared. A scan that dropped the span after or before the area would miss it. */
    @Test
    fun aPixelBesideTheMotionsAreaOnItsRowsIsStillFound_C2F(): Unit = runBlocking {
        for (x in listOf(620, 10)) {
            val dir = Files.createTempDirectory(tmp, "plant")
            val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff }
            val held = 1_763_480_000_000L
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { held })
            t.devProbe("c1", "3")
            val journal = dir.resolve("journal.jsonl")
            val shell = Shell(FakeText(), t, Persistence(dir.resolve("s.json")), journal, scope, HELD_CLOCK)
            shell.start()
            waitUntil(30_000, 10, { "the session settles" }) { shell.isQuiescent() }
            t.devProbe("motion", "list")                  // LIST: x 32..608, from y 74
            waitUntil(10_000, 10, { "the PLAY lands" }) { sim.motionWindows(Arm.LEFT).isNotEmpty() && shell.isQuiescent() }
            assertEquals(0, shell.divergencesReported)
            val y = 120
            // one pixel of level 9 written raw (the self-test door's live write: the shell's belief never hears of it)
            t.devProbe("selftest", "live:" + wm.damage.core.wire.CfwModes.batch(listOf(wm.damage.core.wire.CfwModes.fill(Rect(x, y, 1, 1), 9)))
                .joinToString("") { "%02x".format(it) })
            // the repaint's FlushDone runs the scan that must find it, with the motion still open (the keyframe the
            // divergence asks for closes it afterwards)
            assertTrue(sim.motionWindows(Arm.LEFT).isNotEmpty(), "the motion is open before the scan")
            edgeRepaint(shell, 1)
            // the journal's `divergence` note: the report itself is cleared again once the keyframe restores agreement
            fun episodes() = lines(journal).filter { "\"kind\":\"divergence\"" in it && "episode" in it }
            waitUntil(10_000, 10, { "the planted pixel at ($x, $y) is found: ${episodes()}" }) { episodes().isNotEmpty() }
            assertTrue(episodes().any { "1 px differ, first at ($x,$y)" in it }, "x $x: ${episodes()}")
            shell.stop()
        }
    }
}
