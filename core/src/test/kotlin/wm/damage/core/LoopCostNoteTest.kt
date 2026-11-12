package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.LensPanels
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport

/**
 * APK 0.65, P6 item 1 (the loop-cost sweep's LC-7, `scratchpad/sweeps/loop-cost-verified.md`): the loop timed
 * every message, and wrote that time only inside the `submit` line of a flush the same turn assembled. A message
 * that flushed nothing — a save, a parked window's render request, a stretch at the sync cadence — and the mirror
 * scan an ack at rest runs (whose turn submits nothing by construction) reached no row: the journal read the
 * scan as zero. Fixed: one `loop` note a minute from `handleMinute`, per message class the count, total and
 * maximum handler time, the pump and the mirror scan apart. The `done` line stays where it was: the report's
 * WAIT column is `done.t − submit.t`, and a `done` written after the scan would put the scan into every wait.
 */
class LoopCostNoteTest {
    private val tmp: Path = Files.createTempDirectory("loop-cost-note")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private fun lines(journal: Path): List<String> = if (Files.exists(journal)) Files.readAllLines(journal) else emptyList()

    /** The `loop` notes' details, in order. */
    private fun loopNotes(journal: Path): List<String> = lines(journal).filter { "\"kind\":\"loop\"" in it }
        .map { Regex(""""detail":"((?:[^"\\]|\\.)*)"""").find(it)!!.groupValues[1] }

    /** One entry of a note: `name n=N total=T max=M` (ms) → (n, total, max), or null. */
    private fun entry(detail: String, name: String): Triple<Int, Double, Double>? =
        Regex("""(?:^|; |: )${Regex.escape(name)} n=(\d+) total=([\d.]+) max=([\d.]+)""").find(detail)
            ?.groupValues?.let { Triple(it[1].toInt(), it[2].toDouble(), it[3].toDouble()) }

    private suspend fun started(transport: Transport): Pair<Shell, Path> {
        val journal = tmp.resolve("journal.jsonl")
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), journal, scope)
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        return shell to journal
    }

    /** The finding's pin: a 60 ms `Run` that paints nothing, then a minute tick. Unfixed: no `loop` note at all. */
    @Test
    fun aMessageThatFlushesNothingIsCountedInTheMinutesLoopNote(): Unit = runBlocking {
        val (shell, journal) = started(SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)))
        val submits = lines(journal).count { "\"ev\":\"submit\"" in it }
        val ran = CompletableDeferred<Unit>()
        shell.services.runOnShell { Thread.sleep(60); ran.complete(Unit) }
        ran.await()
        shell.postMinuteTick()
        waitUntil(10_000, message = { "a loop note names Run at 60 ms or more: ${loopNotes(journal)}" }) {
            loopNotes(journal).any { (entry(it, "Run")?.third ?: 0.0) >= 60.0 }
        }
        val note = loopNotes(journal).first { (entry(it, "Run")?.third ?: 0.0) >= 60.0 }
        assertTrue((entry(note, "pump")?.first ?: 0) >= 1, "the pumps' time is in the note apart: $note")
        assertTrue(lines(journal).count { "\"ev\":\"submit\"" in it } == submits, "the Run flushed nothing (its time reached no row before)")
        shell.stop()
    }

    /** Ten acks at rest, then a minute tick: the notes written after the baseline count ten scans or more (no
     *  more than the acks in that stretch) with a time above zero. Unfixed: no note. */
    @Test
    fun tenAcksAtRestAreCountedAsMirrorScans(): Unit = runBlocking {
        val (shell, journal) = started(SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)))
        shell.postMinuteTick()                     // the baseline: what the start ran goes into this note (or none)
        waitUntil(10_000, message = { "the baseline tick is handled: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        val before = loopNotes(journal).size
        val donesBefore = lines(journal).count { "\"ev\":\"done\"" in it && "\"ok\":true" in it }
        for (n in 1..10) {
            nonInputRepaint(shell, n)
            waitUntil(10_000, message = { "repaint $n is acked at rest: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        }
        shell.postMinuteTick()
        waitUntil(10_000, message = { "a loop note after the ten acks: ${loopNotes(journal)}" }) { loopNotes(journal).size > before }
        val after = loopNotes(journal).drop(before)
        val scans = after.sumOf { entry(it, "mirror")?.first ?: 0 }
        val total = after.sumOf { entry(it, "mirror")?.second ?: 0.0 }
        val acks = lines(journal).count { "\"ev\":\"done\"" in it && "\"ok\":true" in it } - donesBefore
        assertTrue(scans in 10..acks, "ten acks at rest are ten scans or more, no more than the acks ($acks): $after")
        assertTrue(total > 0.0, "the scans' time is above zero: $after")
        shell.stop()
    }

    /** A mirror whose consistent copy takes [ms] per arm: the scan's time made large enough to see in a wait. */
    private class SlowMirror(private val inner: LensPanels, private val ms: Long) : LensPanels by inner {
        override fun snapshot(arm: Arm): ByteArray { Thread.sleep(ms); return inner.snapshot(arm) }
        /** P6 item 5: the check reads the panel in place through this. */
        override fun <T> readPanel(arm: Arm, block: (ByteArray) -> T): T { Thread.sleep(ms); return inner.readPanel(arm, block) }
    }

    /** The challenge's correction: the `done` line's `t` precedes the scan. With a scan of 2 × 100 ms behind every
     *  ack on an instant simulator, the median wait (`done.t − submit.t`) of ten repaints stays under 100 ms; a
     *  `done` written after the scan reads 200 ms or more. */
    @Test
    fun theDoneLineIsWrittenBeforeTheMirrorScan(): Unit = runBlocking {
        val sim = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val slow = SlowMirror(sim.mirror, 100)
        val (shell, journal) = started(object : Transport by sim { override val mirror: LensPanels get() = slow })
        val ids = lines(journal).filter { "\"ev\":\"submit\"" in it }.map { Regex(""""id":(\d+)""").find(it)!!.groupValues[1] }.toSet()
        for (n in 1..10) {
            nonInputRepaint(shell, n)
            waitUntil(10_000, message = { "repaint $n is acked at rest: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        }
        val all = lines(journal)
        fun t(l: String) = Regex(""""t":(\d+)""").find(l)!!.groupValues[1].toLong()
        fun id(l: String) = Regex(""""id":(\d+)""").find(l)!!.groupValues[1]
        val sub = all.filter { "\"ev\":\"submit\"" in it && id(it) !in ids }.associate { id(it) to t(it) }
        val waits = all.filter { "\"ev\":\"done\"" in it && id(it) in sub }.map { t(it) - sub.getValue(id(it)) }.sorted()
        assertTrue(waits.size >= 10, "ten repaints were submitted and acked: $waits")
        val median = waits[waits.size / 2]
        assertTrue(median < 100, "the wait does not hold the scan (median $median ms of $waits)")
        shell.stop()
    }
}
