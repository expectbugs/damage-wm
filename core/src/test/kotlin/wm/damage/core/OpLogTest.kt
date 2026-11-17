package wm.damage.core

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.Change
import wm.damage.core.app.OpLog
import wm.damage.core.app.RecordAlerts
import wm.damage.core.app.StreamKey

/**
 * Build S1-1, package P1 (`CONTRACT.md` §9.7 item 1, §13 (b) "operation journal" as the spec's §3 corrects it; dec.
 * A.12, B.1): no answer before the op's line has landed; an incomplete last line is kept aside;
 * a start replays only the lines past the covering save; a failed append answers "retry", publishes nothing, leaves
 * no line and is said; the log is set aside whole at a start, never cut.
 */
class OpLogTest {
    private val rig = RecordRig()

    @After
    fun tearDown() = rig.cleanUp()

    private fun lines() = Files.readAllLines(rig.logFile).filter { it.isNotEmpty() }

    /** The pin of "append, force, then answer" (mutation S1-answer-before-line): at the moment the answer is handed
     *  out, the op's line is in the file and the force has run. */
    @Test
    fun noAnswerBeforeTheLineLands() {
        val h = rig.opened()
        var lineThere = false
        var forcedThen = -1
        var got: Answer? = null
        h.act(op("phone", "s1", 1, "set", listOf("a"), j("v1"))) {
            got = it
            lineThere = lines().any { l -> "\"stream\":\"s1\",\"n\":1," in l }
            forcedThen = rig.forced.get()
        }
        h.executor.call {}
        assertEquals(Answer.Seen(), got)
        assertTrue(lineThere, "the line was in the log when the answer went out")
        assertEquals(1, forcedThen, "and forced to disk")
        // the publish follows the answer
        val order = ArrayList<String>()
        h.addListener { order += "publish ${it.seq}" }
        h.act(op("phone", "s1", 2, "set", listOf("a"), j("v2"))) { order += "answer" }
        h.executor.call {}
        assertEquals(listOf("answer", "publish 2"), order)
    }

    /** An incomplete last line is cut and kept aside; L1 C2: the store keeps its epoch (every subscription takes a
     *  snapshot, so no client resumes across it). */
    @Test
    fun anIncompleteLastLineIsKeptAsideAndTheEpochStays() {
        val h = rig.opened()
        rig.ask(h, op("phone", "s1", 1, "set", listOf("a"), j("v1")))
        rig.ask(h, op("phone", "s1", 2, "set", listOf("b"), j("v2")))
        val epoch = h.epoch
        rig.stop(h)
        // a stop cut the next line short: never answered (the answer follows the force)
        Files.write(rig.logFile, "{\"seq\":3,\"hw\":3,\"op\":{\"dev".toByteArray(), StandardOpenOption.APPEND)
        rig.now += 1_000
        val h2 = rig.host()
        val o = h2.open()
        assertEquals(epoch, h2.epoch)
        assertNull(o.newEpoch)
        assertEquals(2L, h2.seq)
        assertEquals(j("v2"), rig.item(h2, "b")!!.value, "every complete line kept")
        val kept = Files.list(rig.dir).use { s -> s.filter { it.fileName.toString().startsWith("oplog.jsonl.partial-") }.toList() }
        assertEquals("{\"seq\":3,\"hw\":3,\"op\":{\"dev", String(Files.readAllBytes(kept.single())), "its bytes kept aside")
        // the next line starts clean, and the next start keeps the epoch
        rig.ask(h2, op("phone", "s1", 3, "set", listOf("c"), j("v3")))
        assertTrue(lines().all { it.startsWith("{\"seq\":") && it.endsWith("}") })
        val e2 = h2.epoch
        rig.stop(h2)
        val h3 = rig.host()
        assertNull(h3.open().newEpoch)
        assertEquals(e2, h3.epoch)
        assertEquals(j("v3"), rig.item(h3, "c")!!.value)
    }

    @Test
    fun aStartReplaysOnlyTheLinesPastTheCoveringSave() {
        val h = rig.opened()
        for (n in 1L..3L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        rig.tick(h)
        for (n in 4L..5L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        val epoch = h.epoch
        rig.stop(h)                                     // no save: a stop
        val h2 = rig.host()
        val o = h2.open()
        assertEquals(2, o.replayed)
        assertNull(o.newEpoch)
        assertEquals(epoch, h2.epoch)
        assertEquals(5L, h2.seq)
        for (n in 1L..5L) assertEquals(j("v$n"), rig.item(h2, "k$n")!!.value)
        assertEquals(5L, h2.highWater(StreamKey("phone", "s1")))
        assertEquals(5, lines().size, "nothing was cut from the log")
        // the resent op 5 is answered from the replayed answers, not "already handled"
        assertEquals(Answer.Seen(), rig.ask(h2, op("phone", "s1", 5, "set", listOf("k5"), j("v5"))))
    }

    /** Dec. B.1: a failed append answers the op with a retry refusal; nothing is published; no line is left. */
    @Test
    fun aFailedAppendPublishesNothingAndAnswersRetry() {
        val h = rig.opened()
        rig.ask(h, op("phone", "s1", 1, "set", listOf("a"), j("v1")))
        val published = ArrayList<Change>()
        h.addListener { published += it }
        val size = Files.size(rig.logFile)
        rig.syncFails = true
        val got = rig.ask(h, op("phone", "s1", 2, "set", listOf("a"), j("v2")))
        assertEquals(Answer.RETRY, (got as Answer.Refused).code)
        assertTrue(published.isEmpty(), "nothing published")
        assertEquals(1L, h.seq)
        assertEquals(1L, h.highWater(StreamKey("phone", "s1")), "the number is not taken")
        assertEquals(j("v1"), rig.item(h, "a")!!.value)
        assertEquals(size, Files.size(rig.logFile), "the line written before the failed force is cut back")
        // said once per run of failures: the log line, a journal note, the alert
        rig.ask(h, op("phone", "s1", 2, "set", listOf("a"), j("v2")))
        assertEquals(1, rig.said.count { it.startsWith("raise ${RecordAlerts.LOG}") })
        // build S1-2 Q1 (N3's class): a page keeps no queue, so the alert does not say every device keeps its changes
        assertTrue(rig.said.any { it.startsWith("raise ${RecordAlerts.LOG}") && "a page says so and keeps none" in it }, "${rig.said}")
        assertEquals(1, rig.notes().count { "FAILED" in it && "operation log" in it })
        // the resend lands once the disk takes it, and the end of the run is said
        rig.syncFails = false
        assertEquals(Answer.Seen(), rig.ask(h, op("phone", "s1", 2, "set", listOf("a"), j("v2"))))
        assertEquals(listOf(2L), published.map { it.seq })
        assertTrue(rig.said.last() == "clear ${RecordAlerts.LOG}")
        assertTrue(rig.notes().any { "works again after 2 failure(s)" in it })
        rig.stop(h)
        val h2 = rig.opened()
        assertEquals(j("v2"), rig.item(h2, "a")!!.value)
        assertEquals(2L, h2.seq)
    }

    @Test
    fun aRefusalForGoodTakesItsNumberAndALine() {
        val h = rig.opened()
        val got = rig.ask(h, op("phone", "s1", 1, "nosuch", listOf("a"), j("v")))
        assertEquals(Answer.UNKNOWN, (got as Answer.Refused).code)
        assertEquals(1L, h.highWater(StreamKey("phone", "s1")), "the client drops it and goes on with 2")
        assertEquals(Answer.Seen(), rig.ask(h, op("phone", "s1", 2, "set", listOf("a"), j("v"))))
        assertEquals(2, lines().size)
    }

    @Test
    fun theLogIsSetAsideWholeAtAStartOnceASaveCoversIt() {
        rig.setAsideBytes = 1
        val h = rig.opened()
        for (n in 1L..3L) rig.ask(h, op("phone", "s1", n, "set", listOf("k$n"), j("v$n")))
        val before = Files.readAllBytes(rig.logFile)
        rig.stop(h)
        val h2 = rig.host()
        val o = h2.open(LocalDateTime.of(2026, 11, 14, 0, 17, 0))
        assertEquals("oplog-20261114-001700.jsonl", o.setAside!!.fileName.toString())
        assertTrue(before.contentEquals(Files.readAllBytes(o.setAside!!)), "set aside whole, never cut")
        assertEquals(0L, Files.size(rig.logFile))
        for (n in 1L..3L) assertEquals(j("v$n"), rig.item(h2, "k$n")!!.value, "the record covers every line set aside")
        val epoch = h2.epoch
        rig.ask(h2, op("phone", "s1", 4, "set", listOf("k4"), j("v4")))
        rig.stop(h2)
        rig.setAsideBytes = AppHost.LOG_SET_ASIDE_BYTES
        val h3 = rig.host()
        val o3 = h3.open()
        assertEquals(epoch, h3.epoch)
        assertEquals(1, o3.replayed)
        assertNull(o3.setAside)
        assertEquals(4L, h3.seq)
    }

    @Test
    fun aLineThatDoesNotDecodeIsSaid() {
        val h = rig.opened()
        rig.ask(h, op("phone", "s1", 1, "set", listOf("a"), j("v1")))
        rig.stop(h)
        Files.write(rig.logFile, "not a line\n".toByteArray(), StandardOpenOption.APPEND)
        val h2 = rig.host()
        val o = h2.open()
        assertNull(o.newEpoch)
        assertTrue(o.problems.any { it.startsWith("line 2:") }, o.problems.toString())
        assertNotNull(o.setAside, "a log holding a bad line is set aside once covered, so the next start is clean")
        assertTrue(rig.notes().any { "line 2:" in it })
        val epoch = h2.epoch
        rig.stop(h2)
        val h3 = rig.host()
        assertNull(h3.open().newEpoch)
        assertEquals(epoch, h3.epoch)
        assertEquals(j("v1"), rig.item(h3, "a")!!.value)
    }

    @Test
    fun theLineFormRoundTrips() {
        val h = rig.opened()
        rig.ask(h, op("desk", "d", 1, "set", listOf("x"), j("c")))
        rig.ask(h, op("phone", "s1", 1, "set", listOf("x"), j("b"), wm.damage.core.app.Expect.Value(j("a"))))
        for (l in lines()) assertEquals(l, OpLog.encode(OpLog.decode(l)))
        assertFalse(lines().any { '\n' in it })
    }
}
