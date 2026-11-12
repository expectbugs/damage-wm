package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport

/**
 * APK 0.65, P6 item 5 (the loop-cost sweep's LC-2, `scratchpad/sweeps/loop-cost-verified.md`): after every ack at
 * rest the shell copied both lenses of the model (2 × 153,600 B) and compared all 480 rows of each — about 116 s of
 * loop time in four hours of APK 0.63 (measured indirectly) — though since the last agreeing check only the rows a
 * present wrote and the rows our flushes touched can differ. Fixed: the model keeps, per lens, the rows written to
 * its panel since they were last taken; the shell keeps the rows its completed flushes touched since the last agreeing
 * check; the check compares the union, read in place under the model's monitor, and compares whole on the session's
 * first check, after a failed or rolled-back flush, while a disagreement stands, and once a minute. The rule of the
 * check (at rest only, one report per episode, one keyframe) is unchanged.
 */
class MirrorSpanTest {
    private val tmp: Path = Files.createTempDirectory("mirror-span")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private val journal: Path get() = tmp.resolve("journal.jsonl")
    private fun lines(): List<String> = if (Files.exists(journal)) Files.readAllLines(journal) else emptyList()
    private fun loopNotes() = lines().filter { "\"kind\":\"loop\"" in it }

    private suspend fun started(sim: GlassFirmwareSim, wrap: (SimTransport) -> Transport = { it }): Shell {
        val shell = Shell(FakeText(), wrap(SimTransport(sim, scope, SimTransport.Timing(instant = true))),
            Persistence(tmp.resolve("state.json")), journal, scope)
        shell.start()
        settle(shell, "the start")
        return shell
    }

    private suspend fun settle(shell: Shell, what: String) =
        waitUntil(15_000, message = { "$what: the shell settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }

    /** One byte of [arm]'s buffer set to two white pixels at (x, y), under the model's monitor. */
    private fun poke(sim: GlassFirmwareSim, buffer: ByteArray, x: Int, y: Int) =
        synchronized(sim) { buffer[y * sim.left.stride + (x shr 1)] = 0xFF.toByte() }

    /** The challenge's first pin: ten small flushes at rest on a contract-2 model (each carries a row hint) compare
     *  their rows — at most one whole pair (960 rows) beside ten small spans. Unfixed: ten whole pairs, 9,600 rows.
     *  The minute's whole compare is not due: the session's first check, a whole one, ran seconds before. */
    @Test
    fun tenSmallFlushesAtRestCompareTheirRowsNotTheWholeLenses(): Unit = runBlocking {
        val shell = started(GlassFirmwareSim().also { it.damageContract = 2 })
        for (n in 11..12) { nonInputRepaint(shell, n); settle(shell, "warm repaint $n") }    // the start's chrome rides the first
        shell.postMinuteTick(); settle(shell, "the baseline note")
        val before = loopNotes().size
        val hintsBefore = lines().count { "\"ev\":\"submit\"" in it && "\"op\":\"hint\"" in it }
        for (n in 1..10) { nonInputRepaint(shell, n); settle(shell, "repaint $n") }
        assertTrue(lines().count { "\"ev\":\"submit\"" in it && "\"op\":\"hint\"" in it } - hintsBefore >= 10, "the ten flushes carried a hint")
        shell.postMinuteTick()
        waitUntil(10_000, message = { "the loop note after the ten: ${loopNotes().drop(before)}" }) { loopNotes().size > before }
        val after = loopNotes().drop(before)
        val scans = after.sumOf { Regex("""mirror n=(\d+)""").find(it)!!.groupValues[1].toInt() }
        val rows = after.sumOf { Regex("""rows=(\d+)""").find(it)!!.groupValues[1].toLong() }
        assertTrue(scans >= 10, "ten acks at rest, ten scans or more: $after")
        assertTrue(rows <= 960 + scans * 64L, "$scans scans compared $rows rows: at most one whole pair and small spans")
        shell.stop()
    }

    /** The second pin (watched to fail on the finding's variant that compares only the rects a flush declared): the
     *  model writes one pixel OUTSIDE the flush's declared rect but inside the rows its hint presents — reported at
     *  that flush's ack, because the rows are compared whole across. */
    @Test
    fun aPixelOutsideTheDeclaredRectInsideTheHintedRowsIsReportedAtItsAck(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        val shell = started(sim)
        assertEquals(0, shell.divergencesReported)
        val c = shell.layout.content
        val n = 3
        val bandY = c.y + 8 + n * 24                      // `nonInputRepaint`'s band: x c.x+8, 96 wide, 16 high
        poke(sim, sim.left.shadow, c.x + 400, bandY + 6)
        nonInputRepaint(shell, n)
        settle(shell, "the repaint, the report and the repair")
        assertEquals(1, shell.divergencesReported, "the pixel the model wrote outside the rect was reported at the ack")
        assertEquals(null, shell.lastDivergence, "the keyframe restored agreement")
        shell.stop()
    }

    /** The third pin: a panel byte altered with no present at all is in neither span, so only a whole compare can
     *  read it; a rolled-back flush asks for one. In one loop turn the byte is altered and a repaint is painted whose
     *  submit fails (rolled back); the next message's pump sends the repaint again (its rows hinted, the byte's rows
     *  not presented), and the check at its ack compares whole and reports the byte. The rows the window compared
     *  say the same. Unfixed every check is whole; with the rollback's condition absent the check reads the spans. */
    @Test
    fun aPanelByteAlteredWithNoPresentIsFoundByTheWholeCompareAfterARolledBackFlush(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        val failNext = AtomicBoolean(false)
        val shell = started(sim) { inner ->
            object : Transport by inner {
                override suspend fun submit(flush: FlushRequest): Long =
                    if (failNext.getAndSet(false)) throw IllegalStateException("test: the link ended before the submit")
                    else inner.submit(flush)
            }
        }
        for (n in 1..2) { nonInputRepaint(shell, n); settle(shell, "warm repaint $n") }
        shell.postMinuteTick(); settle(shell, "the baseline note")
        val before = loopNotes().size
        val c = shell.layout.content
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        shell.services.runOnShell {
            poke(sim, sim.left.panel, c.x + 400, c.y + 300)          // rows no flush below touches
            val r = wm.damage.core.geom.Rect(c.x + 8, c.y + 8 + 3 * 24, 96, 16)
            shell.comp.composed.fillRect(r, 11 * 17); shell.comp.damage(r)
            failNext.set(true)
            done.complete(Unit)
        }
        done.await()
        shell.services.runOnShell { }                                // its pump sends the rolled-back repaint
        waitUntil(15_000, message = { "the rolled-back flush's whole compare reports the byte: ${shell.quiescenceReport()}" }) {
            shell.divergencesReported == 1
        }
        settle(shell, "the repair")
        shell.postMinuteTick()
        waitUntil(10_000, message = { "the loop note: ${loopNotes().drop(before)}" }) { loopNotes().size > before }
        val rows = loopNotes().drop(before).sumOf { Regex("""rows=(\d+)""").find(it)!!.groupValues[1].toLong() }
        assertTrue(rows >= 960, "a whole pair was compared after the rollback: $rows rows")
        shell.stop()
    }

    /** The model's span: on a contract-1 model a present is whole (no hint), so a pixel the model holds in rows no
     *  flush of ours touched is presented and compared. With the model's mark of an unhinted present absent, the
     *  shell's span (the repaint's rows) misses it. */
    @Test
    fun anUnhintedPresentMarksEveryRowOfTheLens(): Unit = runBlocking {
        val sim = GlassFirmwareSim()
        val shell = started(sim)
        val c = shell.layout.content
        poke(sim, sim.left.shadow, c.x + 400, c.y + 300)
        nonInputRepaint(shell, 1)
        settle(shell, "the repaint, the report and the repair")
        assertEquals(1, shell.divergencesReported, "the whole present showed the pixel and the check compared it")
        shell.stop()
    }

    /** R4 item 6 (review 3B-1): the shell's own span (the ok arm of `completeFlush`, P6-20) is the only source of a
     *  flush's rows when the model writes none for it while the flush is acked ok. The model's flag for "inside a
     *  self-test step" (`present` publishes nothing, `GlassFirmwareSim.present`) is set on the LEFT lens directly: the
     *  reader's sequence — a self-test BEGIN through `dispatchForTest` — does not hold, because the model sets the flag
     *  only around one step's own dispatch, so the shell's flush presented as usual and nothing was reported (0, on the
     *  tree with the guard in place). With the flag set, the model's span stays empty, the rows the shell's span names
     *  are compared and the stale panel is reported. With the shell's span taking no rows (P6-20), `lo > hi` and the
     *  check reads agreement (0). */
    @Test
    fun aFlushTheModelDoesNotPresentIsComparedOverTheShellsOwnRows(): Unit = runBlocking {
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        val shell = started(sim)
        assertEquals(0, shell.divergencesReported)
        synchronized(sim) { sim.left.stActive = true }      // the model's presents on LEFT publish nothing from here
        nonInputRepaint(shell, 3)
        settle(shell, "the repaint and its check")
        assertTrue(shell.divergencesReported >= 1, "the flush the model did not present was compared over the shell's rows (${shell.divergencesReported})")
        shell.stop()
    }

    /** The minute's whole compare, kept beside the spans (the one check that does not depend on the new
     *  bookkeeping): a minute after the session's first (whole) check, a check compares whole again. Real time, no
     *  knob: the window opens after the start and runs 61 s, then one repaint's ack. */
    @Test
    fun aWholeCompareRunsAMinuteAfterTheLast(): Unit = runBlocking {
        val shell = started(GlassFirmwareSim().also { it.damageContract = 2 })
        shell.postMinuteTick(); settle(shell, "the baseline note")
        val before = loopNotes().size
        val t0 = System.currentTimeMillis()
        delay(61_000 - (System.currentTimeMillis() - t0))
        nonInputRepaint(shell, 4); settle(shell, "a repaint after the minute")
        shell.postMinuteTick()
        waitUntil(10_000, message = { "the loop note: ${loopNotes().drop(before)}" }) { loopNotes().size > before }
        val rows = loopNotes().drop(before).sumOf { Regex("""rows=(\d+)""").find(it)!!.groupValues[1].toLong() }
        assertTrue(rows >= 960, "a whole pair was compared within the minute's window: $rows rows ${loopNotes().drop(before)}")
        shell.stop()
    }
}
