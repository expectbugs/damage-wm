package wm.damage.core

import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.AppWire
import wm.damage.core.app.Levels
import wm.damage.core.windows.tmux.TmuxCore
import wm.damage.core.windows.tmux.TmuxStream
import wm.damage.core.windows.tmux.TmuxTarget

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, pieces B9–B11: Tmux on the `app` lane as a web page meets it — the description, a device below full
 * refused at attach and at op, every key a never-queued op run in arrival order (raw bytes as literal and hex runs),
 * frames only to a viewer in front (detached by a need of none and by the connection's end, a fresh init on return),
 * and the control-mode stream through the scripted client. A scripted provider and a record in a temp directory.
 */
class TmuxScopesTest {
    private val controls = CopyOnWriteArrayList<FakeControl>()
    private val stream = TmuxStream({ h, s -> listOf("scripted", h, s) }) { FakeControl().also { c -> controls += c } }
    private val rig = TmuxLaneRig(stream = stream)

    @AfterTest
    fun tearDown() = rig.close()

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
    private fun desc(f: JsonObject) = f["desc"]!!.jsonObject
    private val claude = "session:beardos:claude"
    private val frameSrc = "live/tmux/frame/beardos/claude"
    private val streamSrc = "live/tmux/stream/beardos/claude"

    @Test
    fun theDescriptionCarriesSessionsHostsKeysActionsAndRows_andFollowsAMute() {
        val (page, _) = rig.page(Levels.FULL)
        page.send(TmuxLaneRig.sub("desc/tmux"))
        val d = desc(page.nextFor("snap", "desc/tmux"))
        assertEquals("full", d.s("min"))
        assertEquals("2 sessions", d["summary"]!!.jsonObject.s("line"), "the glasses' Main row")
        val s = d["sessions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("claude", "build"), s.map { it.s("name") })
        assertEquals(rig.provider.sessions[0].activity * 1000, s[0]["activity"]!!.jsonPrimitive.longOrNull, "activity on the PC's ms")
        assertEquals("false", s[0].s("muted"))
        assertEquals("ok", d["hosts"]!!.jsonArray[0].jsonObject["health"]!!.jsonObject.s("state"))
        assertEquals(14, d["keys"]!!.jsonObject["quick"]!!.jsonArray.size, "the configured quick keys")
        val actions = d["actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("key", "snippet", "line", "raw", "new", "rename", "end", "select", "mute", "set"), actions.map { it.s("id") }.toSet())
        assertTrue(actions.all { it.s("level") == Levels.FULL }, "every action at full")
        val rows = d["settings"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("id") }
        assertEquals("true", rows.getValue("alerts").s("value"), "Alerts: absent is on")
        assertEquals("480", rows.getValue("glasses.size").s("value"), "Size: absent is 480")
        // a mute (a record op) moves the description
        page.send(buildJsonObject {
            put("t", "op")
            put("op", buildJsonObject {
                put("device", ""); put("stream", "s-full"); put("n", 1); put("window", "tmux"); put("action", "mute")
                put("targets", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("mute.beardos/claude")))); put("result", true)
                put("at", System.currentTimeMillis())
            })
        })
        val again = desc(page.nextFor("snap", "desc/tmux"))
        assertEquals("true", again["sessions"]!!.jsonArray[0].jsonObject.s("muted"))
    }

    @Test
    fun belowFullNothingIsServedOrTaken() {
        val (page, conn) = rig.page(Levels.CONTROL)
        for (scope in listOf("desc/tmux", "tmux/history:beardos:claude", frameSrc, streamSrc)) {
            page.send(TmuxLaneRig.sub(scope))
            val a = page.nextFor("ans", scope)["answer"]!!.jsonObject
            assertEquals("level", a.s("code"), "$scope at control: $a")
        }
        assertEquals(1, rig.provider.listeners.size, "no frame viewer was attached (the scopes' own listener only)")
        assertTrue(controls.isEmpty(), "no control client was started")
        page.send(TmuxLaneRig.op(conn, 1, "key", claude, buildJsonObject { put("key", "Enter") }))
        assertEquals("level", page.answerOf(conn, 1).s("code"))
        assertTrue(rig.provider.sent.isEmpty(), "nothing reached tmux: ${rig.provider.sent}")
    }

    @Test
    fun keysRunInArrivalOrder_rawBytesAsLiteralAndHexRuns_andTheActionsAnswerWithTheirResult() {
        val (page, conn) = rig.page(Levels.FULL)
        val typed = listOf("l", "s", " -la", "\r", "\u001b[A", "\u0003")
        for ((i, d) in typed.withIndex()) page.send(TmuxLaneRig.op(conn, i + 1L, "raw", claude, buildJsonObject { put("data", d) }))
        for (i in typed.indices) assertEquals("sent", page.answerOf(conn, i + 1L).s("a"))
        assertEquals(listOf("raw:claude:-l l", "raw:claude:-l s", "raw:claude:-l  -la", "raw:claude:-H 0d",
            "raw:claude:-H 1b|-l [A", "raw:claude:-H 03"), rig.provider.sent.toList(), "in arrival order, one op each")
        rig.provider.sent.clear()
        page.send(TmuxLaneRig.op(conn, 10, "line", claude, buildJsonObject { put("text", "make test") }))
        assertEquals("sent", page.answerOf(conn, 10).s("a"))
        page.send(TmuxLaneRig.op(conn, 11, "key", claude, buildJsonObject { put("key", "C-c") }))
        assertEquals("sent", page.answerOf(conn, 11).s("a"))
        page.send(TmuxLaneRig.op(conn, 12, "select", claude, buildJsonObject { put("window", 1) }))
        assertEquals("sent", page.answerOf(conn, 12).s("a"))
        assertEquals(listOf("lit:claude:make test", "keys:claude:Enter", "keys:claude:C-c", "sel:claude:1"), rig.provider.sent.toList())
        page.send(TmuxLaneRig.op(conn, 13, "rename", claude, buildJsonObject { put("text", "my work") }))
        val ren = page.answerOf(conn, 13)
        assertEquals("session:beardos:my-work", ren["result"]!!.jsonObject["renamed"]!!.jsonObject.s("to"), "$ren")
        page.send(TmuxLaneRig.op(conn, 14, "new", "beardos"))
        assertEquals("g2-1", page.answerOf(conn, 14)["result"]!!.jsonObject.s("name"))
        page.send(TmuxLaneRig.op(conn, 15, "key", claude))
        val bad = page.answerOf(conn, 15)
        assertEquals("refused", bad.s("a"), "a key without its name: $bad")
    }

    /** S1-3 fix A4: keys queued behind a stalled one are not typed once their connection has ended or their device
     *  was revoked (they would land late in a prompt that has moved on); the one already running finishes. */
    @Test
    fun keysQueuedBehindAStalledOneAreNotTypedAfterTheirConnectionEnds() {
        for (revoke in listOf(false, true)) {
            rig.provider.sent.clear()
            val gate = java.util.concurrent.CountDownLatch(1).also { rig.provider.rawGate = it }
            val (page, conn) = rig.page(Levels.FULL)
            for ((i, d) in listOf("a", "b", "c").withIndex()) page.send(TmuxLaneRig.op(conn, i + 1L, "raw", claude, buildJsonObject { put("data", d) }))
            eventually("the three keys taken") { rig.net.connectionLines().any { it.split("tmux raw").size == 4 } }
            if (revoke) assertEquals(null, rig.devices.revoke(rig.devices.rows().last().id, "test")) else page.close()
            eventually("the connection ended") { rig.net.connections() == 0 }
            gate.countDown()
            rig.provider.rawGate = null
            Thread.sleep(300)
            assertEquals(listOf("raw:claude:-l a"), rig.provider.sent.toList(), "revoked $revoke: only the key already running")
        }
    }

    @Test
    fun framesGoOnlyToAViewerInFront_aNeedOfNoneAndTheConnectionsEndDetachIt() {
        val (page, _) = rig.page(Levels.FULL)
        page.send(TmuxLaneRig.sub(frameSrc))
        val init = page.liveOf(frameSrc)["init"]!!.jsonObject
        assertEquals(80, init["cols"]!!.jsonPrimitive.intOrNull)
        assertEquals(1_700_000_000_000L, init["capturedAt"]!!.jsonPrimitive.longOrNull)
        val run = init["lines"]!!.jsonArray[0].jsonArray[0].jsonObject
        assertEquals("+ made-up output", run.s("t"))
        assertEquals(2, run["fg"]!!.jsonPrimitive.intOrNull, "green as its xterm index")
        assertEquals(true, init["cursor"]!!.jsonObject["visible"]!!.jsonPrimitive.booleanOrNull)
        rig.provider.line = "next"
        rig.provider.push(TmuxTarget("beardos", "claude"))
        assertEquals("next", page.liveOf(frameSrc)["frame"]!!.jsonObject["lines"]!!.jsonArray[0].jsonArray[0].jsonObject.s("t"))
        // hidden: the viewer ends and the provider listener goes
        page.send(TmuxLaneRig.sub(frameSrc, "none"))
        eventually("the frame viewer detached") { rig.provider.subs.isEmpty() && rig.provider.listeners.size == 1 }
        rig.provider.push(TmuxTarget("beardos", "claude"))
        assertNull(page.frames.poll(300, TimeUnit.MILLISECONDS)?.takeIf { AppWire.type(it) == "live" }, "nothing after the need went")
        // shown again: a fresh init; then the connection ends and the viewer with it
        page.send(TmuxLaneRig.sub(frameSrc))
        assertTrue(page.liveOf(frameSrc).containsKey("init"), "a fresh init on return")
        page.close()
        eventually("the connection's end detached the viewer") { rig.provider.subs.isEmpty() && rig.provider.listeners.size == 1 }
    }

    @Test
    fun theStreamSendsAnInitThenThePanesOutput_andItsLastViewerEndsTheClient() {
        val (page, _) = rig.page(Levels.FULL)
        page.send(TmuxLaneRig.sub(streamSrc))
        val init = page.liveOf(streamSrc)["init"]!!.jsonObject
        assertEquals(1, controls.size)
        assertEquals(80, init["cols"]!!.jsonPrimitive.intOrNull)
        assertEquals("\u001b[32m\$\u001b[0m made-up\n", String(Base64.getDecoder().decode(init.s("data")!!)))
        assertEquals(2, init["cursor"]!!.jsonObject["x"]!!.jsonPrimitive.intOrNull)
        assertTrue(controls[0].commands.all { it.startsWith("display-message") || it.startsWith("capture-pane") },
            "the stream writes its init commands only, never a key: ${controls[0].commands}")
        controls[0].output("hello\r\n\\done")
        assertEquals("hello\r\n\\done", String(Base64.getDecoder().decode(page.liveOf(streamSrc).s("data")!!)))
        page.send(buildJsonObject { put("t", "unsub"); put("scope", streamSrc) })
        eventually("the last viewer's leave closed the client's stdin") { controls[0].closed && stream.running() == 0 }
        // a session that ends: its viewers are told
        page.send(TmuxLaneRig.sub(streamSrc))
        page.liveOf(streamSrc)
        controls[1].exit()
        assertEquals("the session ended", page.liveOf(streamSrc)["end"]!!.jsonObject.s("reason"))
    }

    /** S1-3 fix C2: a live viewer the sweep lets go (no ping reached the PC for two intervals, the socket still up) is
     *  told — `end{again}` — and its client ends; asked again, it gets a fresh init and is not let go at the next tick. */
    @Test
    fun aViewerTheSweepLetsGoIsToldAndAsksAgain() {
        val (page, _) = rig.page(Levels.FULL)
        page.send(TmuxLaneRig.sub(streamSrc))
        assertTrue(page.liveOf(streamSrc).containsKey("init"))
        rig.skewMs = 60_000                                     // a minute with no ping from the page
        rig.net.sweepViewers()
        val end = page.liveOf(streamSrc)["end"]!!.jsonObject
        assertEquals("true", end.s("again"), "$end")
        assertTrue(end.s("reason")!!.startsWith("The PC let this view go (no ping for"), "$end")
        eventually("the swept viewer's client ended") { stream.running() == 0 }
        page.send(TmuxLaneRig.sub(streamSrc))                   // the page asks again at once
        assertTrue(page.liveOf(streamSrc).containsKey("init"), "a fresh init")
        assertEquals(0, rig.net.sweepViewers(), "its sub counts as a word from it: not let go at the next tick")
    }
}
