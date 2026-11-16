package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppWire
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.ProgramResult

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.4 and §5 V3; decisions A.7): ops into an outside program on the `app` lane,
 * played by a page written by hand against a real web listener over a real record (`WebRig`, the made-up window `t`
 * whose `kick` (control), `peek` (view) and `wipe` (full) are program actions). Never queued: an op naming an ended
 * connection never runs; the level is checked on the PC at apply time; the call runs on the window's pool, never the
 * executor; its answer is Sent with the call's result, or Refused in the program's own words; one `program` journal
 * note per op. Loopback port 0, temp directories, a made-up handler.
 */
class ProgramOpTest {
    private fun rig() = WebRig(program = setOf("kick", "peek", "wipe"))

    /** A page at [level], past its welcome; its connection id. */
    private fun WebRig.page(level: String): Pair<Page, Long> {
        val (_, secret) = approved(level, "fixture $level page")
        val p = Page(port, secret)
        assertEquals(101, p.upgrade.code)
        p.hello(stream = "s-$level")
        return p to p.next("welcome")["conn"]!!.jsonPrimitive.content.toLong()
    }

    private fun connOp(conn: Long, k: Long, action: String, targets: List<String> = listOf("x1")) = buildJsonObject {
        put("t", "op")
        put("op", buildJsonObject {
            put("device", ""); put("conn", conn); put("k", k); put("window", "t"); put("action", action)
            put("targets", buildJsonArray { for (t in targets) add(JsonPrimitive(t)) }); put("result", JsonNull)
            put("expect", buildJsonObject { put("any", true) })
        })
    }

    private fun Page.answerTo(conn: Long, k: Long): JsonObject {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (AppWire.type(f) == "ans" && f["conn"]?.jsonPrimitive?.content == "$conn" && f["k"]?.jsonPrimitive?.content == "$k")
                return f["answer"]!!.jsonObject
        }
        throw AssertionError("no answer to ($conn, $k) within 10 s")
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test
    fun aProgramOpRunsOnItsPoolAndIsAnsweredSentWithItsResult() = rig().use { r ->
        val ran = CopyOnWriteArrayList<Op>()
        r.net.serve("t", program = { op, peer ->
            ran += op
            ProgramResult.Sent(buildJsonObject { put("name", "made-up result"); put("by", peer.device) })
        })
        val (page, conn) = r.page(Levels.CONTROL)
        page.send(connOp(conn, 1, "kick", listOf("a", "b")))
        val a = page.answerTo(conn, 1)
        assertEquals("sent", a.s("a"), "Sent: $a")
        assertEquals("made-up result", a["result"]!!.jsonObject.s("name"), "the call's result travels in the answer: $a")
        assertEquals(listOf("a", "b"), ran.single().targets)
        assertTrue(ran.single().device.startsWith("web-"), "the connection's device, never the op's own word: ${ran.single().device}")
        assertTrue(Files.readAllLines(r.rec.journal).any { "\"kind\":\"program\"" in it && "t kick [a, b]" in it && "sent" in it },
            "one program note: ${Files.readAllLines(r.rec.journal).filter { "program" in it }}")
        page.close()
    }

    @Test
    fun aProgramThatThrowsIsRefusedInItsOwnWords() = rig().use { r ->
        r.net.serve("t", program = { _, _ -> throw IllegalStateException("the made-up client answered 403 Forbidden") })
        val (page, conn) = r.page(Levels.CONTROL)
        page.send(connOp(conn, 1, "kick"))
        val a = page.answerTo(conn, 1)
        assertEquals("refused", a.s("a"))
        assertEquals(Answer.PROGRAM, a.s("code"))
        assertEquals("the made-up client answered 403 Forbidden", a.s("reason"))
        page.close()
    }

    /** Pin (the spec's Q2: "an op of an ended connection runs"): an op naming a connection that has ended, sent on
     *  another, is refused `ended` and never reaches the program. */
    @Test
    fun anOpOfAnEndedConnectionNeverRuns() = rig().use { r ->
        val ran = CopyOnWriteArrayList<Op>()
        r.net.serve("t", program = { op, _ -> ran += op; ProgramResult.Sent() })
        val (first, oldConn) = r.page(Levels.CONTROL)
        first.close()
        eventually("the first connection ended on the PC") { r.net.connections() == 0 }
        val (page, conn) = r.page(Levels.CONTROL)
        page.send(connOp(oldConn, 7, "kick"))
        val a = page.answerTo(oldConn, 7)
        assertEquals("refused", a.s("a"), "$a")
        assertEquals(Answer.ENDED, a.s("code"), "$a")
        // the same connection's next op runs: the refusal was for the ended one only
        page.send(connOp(conn, 1, "kick"))
        assertEquals("sent", page.answerTo(conn, 1).s("a"))
        assertEquals(1, ran.size, "only the live connection's op ran: $ran")
        page.close()
    }

    /** Pin (the spec's Q2: "a level below the action's is refused only at the client"): the PC checks the level at
     *  apply time; a view device's control op never reaches the program. */
    @Test
    fun aLevelBelowTheActionsIsRefusedOnThePc() = rig().use { r ->
        val ran = CopyOnWriteArrayList<Op>()
        r.net.serve("t", program = { op, _ -> ran += op; ProgramResult.Sent() })
        val (page, conn) = r.page(Levels.VIEW)
        page.send(connOp(conn, 1, "kick"))
        val a = page.answerTo(conn, 1)
        assertEquals("refused", a.s("a"), "$a")
        assertEquals(Answer.LEVEL, a.s("code"), "$a")
        page.send(connOp(conn, 2, "peek"))
        assertEquals("sent", page.answerTo(conn, 2).s("a"), "a view action at view runs")
        val (full, fconn) = r.page(Levels.CONTROL)
        full.send(connOp(fconn, 1, "wipe"))
        assertEquals(Answer.LEVEL, full.answerTo(fconn, 1).s("code"), "a full action at control is refused")
        assertEquals(listOf("peek"), ran.map { it.action })
        page.close(); full.close()
    }

    @Test
    fun aStalledProgramNeverHoldsTheRecord() = rig().use { r ->
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        r.net.serve("t", program = { _, _ -> started.countDown(); release.await(30, TimeUnit.SECONDS); ProgramResult.Sent() })
        val (page, conn) = r.page(Levels.CONTROL)
        try {
            page.send(connOp(conn, 1, "kick"))
            assertTrue(started.await(10, TimeUnit.SECONDS), "the call started")
            // a record op is answered meanwhile: the call holds its pool's thread, never the executor
            page.send(buildJsonObject {
                put("t", "op")
                put("op", wm.damage.core.app.OpJson.op(op("", "s-control", 1, "set", listOf("x"), j("v"), wm.damage.core.app.Expect.Value(JsonNull))))
            })
            val until = System.currentTimeMillis() + 10_000
            var seen: JsonObject? = null
            while (seen == null && System.currentTimeMillis() < until) {
                val f = page.frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (AppWire.type(f) == "ans" && f.s("stream") == "s-control") seen = f["answer"]!!.jsonObject
            }
            assertEquals("seen", seen?.s("a"), "a record op is answered while a program call is stalled")
        } finally {
            release.countDown()
        }
        assertEquals("sent", page.answerTo(conn, 1).s("a"))
        page.close()
    }

    @Test
    fun aQueuedProgramOpAndAWindowWithoutAHandlerAreRefused() = rig().use { r ->
        val (page, conn) = r.page(Levels.CONTROL)
        // no handler served for `t`
        page.send(connOp(conn, 1, "kick"))
        assertEquals(Answer.UNSUPPORTED, page.answerTo(conn, 1).s("code"))
        // a program action sent with a stream number is never queued: the record knows no such action
        page.send(buildJsonObject {
            put("t", "op")
            put("op", wm.damage.core.app.OpJson.op(op("", "s-control", 1, "kick", listOf("x"))))
        })
        val until = System.currentTimeMillis() + 10_000
        var got: JsonObject? = null
        while (got == null && System.currentTimeMillis() < until) {
            val f = page.frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (AppWire.type(f) == "ans" && f.s("stream") == "s-control") got = f["answer"]!!.jsonObject
        }
        assertEquals(Answer.UNKNOWN, got?.s("code"), "$got")
        page.close()
    }

    @Test
    fun anAnswerAfterItsConnectionEndedIsDroppedAndTheCallStillRuns() = rig().use { r ->
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        r.net.serve("t", program = { _, _ -> release.await(30, TimeUnit.SECONDS); done.countDown(); ProgramResult.Sent() })
        val (page, conn) = r.page(Levels.CONTROL)
        page.send(connOp(conn, 1, "kick"))
        eventually("the op is in flight") { r.net.connectionLines().any { "t kick sent" in it } }
        page.close()
        eventually("the connection ended on the PC") { r.net.connections() == 0 }
        release.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "a call whose connection ended still runs to its end")
        eventually("the dropped answer is said") { recentLog().any { "answered after its connection ended" in it } }
    }
}
