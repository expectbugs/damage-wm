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

    /** An incomplete last line is cut and said (L1 C6); L1 C2: the store keeps its epoch (every subscription takes a
     *  snapshot, so no client resumes across it). */
    @Test
    fun anIncompleteLastLineIsCutAndTheEpochStays() {
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
        assertTrue(o.problems.any { "incomplete last line was cut" in it }, "said: ${o.problems}")
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
        assertEquals(5, lines().size, "nothing was cut from the log")
        // the resent op 5 changes nothing (L1 C1: applying an op twice changes nothing)
        assertEquals(Answer.Seen(), rig.ask(h2, op("phone", "s1", 5, "set", listOf("k5"), j("v5"))))
        assertEquals(j("v5"), rig.item(h2, "k5")!!.value)
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
    fun aRefusalForGoodTakesALine() {
        val h = rig.opened()
        val got = rig.ask(h, op("phone", "s1", 1, "nosuch", listOf("a"), j("v")))
        assertEquals(Answer.UNKNOWN, (got as Answer.Refused).code)
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
        rig.ask(h, op("phone", "s1", 1, "set", listOf("x"), j("b"), at = 5))
        for (l in lines()) assertEquals(l, OpLog.encode(OpLog.decode(l)))
        assertFalse(lines().any { '\n' in it })
    }

    /**
     * L1 C1, the decoders first: `oplog.jsonl` lines as 0.67 wrote them — `hw`, an op with `expect` and no `at`, an
     * effect whose items carry `rev` and an `na` mark, answers `seen` and `lost` — replay past a `record.json` as 0.67
     * wrote it (`streams`, `colls`, `rev`, `na`): the store keeps its epoch and every effect lands.
     */
    @Test
    fun a067LogAndRecordAreReadAndReplayed() {
        Files.createDirectories(rig.dir)
        Files.writeString(rig.recordFile, """{"v":1,"epoch":1763300000000,"items":{"rec.reader.place.a":{"v":"10|v1|",""" +
            """"rev":2,"by":"phone","at":1763300000100,"na":{"mine":"5|v1|","by":"web-1","at":1763300000200}}},""" +
            """"colls":{},"streams":{"phone":{"p1":2},"migration":{"migration":19}},"covered":2}""")
        Files.writeString(rig.logFile, listOf(
            """{"seq":1,"hw":1,"op":{"device":"phone","stream":"p1","n":1,"window":"reader","action":"place","targets":["place.a"],"result":"9|v1|","expect":{"value":null},"base":0},"eff":{"items":{"rec.reader.place.a":{"v":"9|v1|","rev":1,"by":"phone","at":1763300000050}}},"ans":{"a":"seen"}}""",
            """{"seq":2,"hw":2,"op":{"device":"phone","stream":"p1","n":2,"window":"reader","action":"place","targets":["place.a"],"result":"10|v1|","expect":{"value":"9|v1|"}},"eff":{"items":{"rec.reader.place.a":{"v":"10|v1|","rev":2,"by":"phone","at":1763300000100}}},"ans":{"a":"seen"}}""",
            """{"seq":3,"hw":1,"op":{"device":"web-1","stream":"w1","n":1,"window":"reader","action":"place","targets":["place.a"],"result":"5|v1|","expect":{"value":"9|v1|"}},"eff":{"items":{"rec.reader.place.a":{"v":"10|v1|","rev":2,"by":"phone","at":1763300000100,"na":{"mine":"5|v1|","by":"web-1","at":1763300000200}}}},"ans":{"a":"lost","losses":[{"item":"place.a","standing":"10|v1|","by":"phone","at":1763300000100,"mine":"5|v1|"}]}}""",
            """{"seq":4,"hw":3,"op":{"device":"phone","stream":"p1","n":3,"window":"reader","action":"place","targets":["place.b"],"result":"7|v1|","expect":{"pre":1763200000000}},"eff":{"items":{"rec.reader.place.b":{"v":"7|v1|","rev":0,"by":"phone","at":1763200000000}}},"ans":{"a":"seen","again":true}}""",
        ).joinToString("\n", postfix = "\n"))
        val h = AppHost(rig.dir, AppHost.WINDOWS, wm.damage.core.app.CoreExecutor("damage-core-067"), { 1_763_400_000_000L })
        try {
            val o = h.open()
            assertNull(o.newEpoch, "the store keeps its epoch: ${o.problems}")
            assertEquals(emptyList(), o.problems)
            assertEquals(1_763_300_000_000L, h.epoch)
            assertEquals(2, o.replayed, "the lines past the save")
            val items = h.read("reader").third.items
            assertEquals(wm.damage.core.app.Item(j("10|v1|"), "phone", 1_763_300_000_100L), items["rec.reader.place.a"])
            assertEquals(wm.damage.core.app.Item(j("7|v1|"), "phone", 1_763_200_000_000L), items["rec.reader.place.b"])
            val lost = OpLog.decode(lines()[2])
            assertEquals(Answer.Seen(), lost.answer, "a loss answer reads as seen")
            assertEquals(0L, lost.op.at, "an op of the log without its input time: only its effect is replayed")
            assertEquals(1_763_200_000_000L, OpLog.decode(lines()[3]).op.at, "a migrated place keeps its old stamp")
        } finally {
            h.close()
            h.executor.shutdown()
        }
    }
}
