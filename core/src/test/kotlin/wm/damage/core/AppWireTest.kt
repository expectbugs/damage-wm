package wm.damage.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppClient
import wm.damage.core.app.AppWire
import wm.damage.core.app.Contracts
import wm.damage.core.app.Cursor
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.net.WinService
import wm.damage.core.shell.Persistence
import wm.damage.core.sync.SyncPeer
import wm.damage.core.util.Log

/**
 * Build S1-1, package P2: the `app` lane on a real content host over loopback port 0 (the spec's §2 P2 and §4
 * "Versioning"; dec. A.4). First contact adopts `seq` and is given nothing old; a resume inside the ring gets the
 * changes, one outside it or in another epoch a snapshot, never a gap; an op is answered before its change goes out and
 * carries the connection's device, never its own word; an op of an ended connection is refused; the local secret is
 * taken on loopback only; the other lanes still answer beside it; and an old PC's in-band refusal, or a lane hello of
 * another protocol, is met cleanly on both sides. Made-up window `t`, devices and values.
 */
class AppWireTest {

    private val rigs = ArrayList<AutoCloseable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }

    @AfterTest
    fun tearDown() {
        Log.removeSink(sink)
        scope.cancel()
        for (r in rigs.asReversed()) r.close()
    }

    private fun <T : AutoCloseable> keep(t: T): T = t.also { rigs += it }

    @Test
    fun firstContactAdoptsSeqAndPopsNothingOld() {
        val rig = keep(AppLaneRig())
        rig.write("desk", "d1", 1, "a", "one")
        rig.write("desk", "d1", 2, "b", "two")
        rig.write("desk", "d1", 3, "a", "three")
        val lane = keep(RawLane(rig.port))
        val w = lane.welcome()
        assertEquals(AppWire.PROTO, w.proto)
        assertEquals("phone", w.device, "the shared token's holder is the phone")
        assertEquals(rig.host.epoch, w.epoch)
        assertEquals(3L, w.seq)
        assertEquals(mapOf("t" to 1), w.contracts)
        assertEquals(emptyMap(), w.highWater, "the phone has sent nothing yet")
        lane.send(AppWire.sub("t", null))
        val snap = AppWire.parseSnap(lane.next("snap"))
        assertEquals(3L, snap.seq)
        assertEquals(JsonPrimitive("three"), snap.state.items["rec.t.a"]!!.value)
        assertEquals(JsonPrimitive("two"), snap.state.items["rec.t.b"]!!.value)
        lane.quiet()
    }

    /**
     * Fix round 2, ruling 1.6 (F1a's Found 3): while the log is closed (it did not open at this start), the lane serves
     * no record — a snapshot would lack the log's unreplayed lines, under a new epoch — and refuses the subscription
     * with the reason; the device keeps its own copy, as with the PC out of reach.
     */
    @Test
    fun whileTheLogIsClosed_aSubscriptionIsRefusedWithTheReason_neverASnapshot() {
        val rec = RecordRig()
        rec.opened().also { h -> rec.ask(h, op("desk", "d1", 1, "set", listOf("a"), j("one"))); rec.stop(h) }
        java.nio.file.Files.setPosixFilePermissions(rec.logFile, emptySet())     // this start cannot open the log
        val rig = keep(AppLaneRig(rec, rec.opened()))
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.send(AppWire.sub("t", null))
        val a = wm.damage.core.app.OpJson.answer(lane.next("ans")["answer"] as JsonObject) as Answer.Refused
        assertEquals(Answer.RETRY, a.code)
        assertEquals(wm.damage.core.app.AppHost.LOG_CLOSED, a.reason)
        // the PC-served scopes the same: one of a window (the web's Reader pages) is refused; one of the PC's own (the
        // diagnosis page) is still served, so the page can show what is wrong
        rig.net.attach(object : wm.damage.core.app.AppNet.Described {
            override fun gate(scope: String) = when (scope) {
                "desc/t" -> wm.damage.core.app.AppNet.Gate("t", "view")
                "diag" -> wm.damage.core.app.AppNet.Gate(null, "view")
                else -> null
            }
            override fun describe(scope: String) = buildJsonObject { put("scope", scope) }
            override fun follows(scope: String, c: wm.damage.core.app.Change?) = false
            override fun addListener(l: () -> Unit) {}
        })
        lane.send(AppWire.sub("desc/t", null))
        val d = wm.damage.core.app.OpJson.answer(lane.next("ans")["answer"] as JsonObject) as Answer.Refused
        assertEquals(wm.damage.core.app.AppHost.LOG_CLOSED, d.reason)
        lane.send(AppWire.sub("diag", null))
        assertTrue(lane.next("snap")["desc"] != null, "the PC's own page is still described")
        lane.quiet()
    }

    @Test
    fun aResumeInsideTheRingGetsTheChangesAndThenTheLiveOnes() {
        val rig = keep(AppLaneRig())
        rig.write("desk", "d1", 1, "a", "one")
        val e = rig.host.epoch
        rig.write("desk", "d1", 2, "a", "two")
        rig.write("desk", "d1", 3, "b", "three")
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.send(AppWire.sub("t", Cursor(e, 1)))
        val c2 = AppWire.parseChg(lane.next("chg"))
        val c3 = AppWire.parseChg(lane.next("chg"))
        assertEquals(listOf(2L, 3L), listOf(c2.seq, c3.seq))
        assertEquals(JsonPrimitive("two"), c2.patches.items["rec.t.a"]!!.value)
        assertEquals("desk", c3.origin!!.device)
        lane.quiet()
        rig.write("desk", "d1", 4, "a", "four")
        val live = AppWire.parseChg(lane.next("chg"))
        assertEquals(4L, live.seq)
        assertEquals(OpId.Queued("d1", 4), live.origin!!.id)
    }

    /** P1's ring floor (`ChangeRing.since`) answered by a snapshot: after a restart the ring holds nothing below the
     *  record's `seq`, so a cursor under it cannot be served as changes — the changes it lacks are not in memory. */
    @Test
    fun aResumeOutsideTheRingGetsASnapshot() {
        val rec = RecordRig()
        val first = rec.opened()
        rec.ask(first, op("desk", "d1", 1, "set", listOf("a"), j("one")))
        rec.ask(first, op("desk", "d1", 2, "set", listOf("b"), j("two")))
        val e = first.epoch
        rec.ask(first, op("desk", "d1", 3, "set", listOf("a"), j("three")))
        rec.stop(first)
        val again = rec.opened()                         // same epoch; the ring starts empty at seq 3
        assertEquals(e, again.epoch)
        val rig = keep(AppLaneRig(rec, again))
        rig.write("desk", "d1", 4, "c", "four")           // the ring now holds 4 only
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.send(AppWire.sub("t", Cursor(e, 2)))
        val snap = AppWire.parseSnap(lane.next("snap"))
        assertEquals(4L, snap.seq)
        assertEquals(JsonPrimitive("three"), snap.state.items["rec.t.a"]!!.value, "change 3 is in the snapshot, not lost")
        lane.quiet()
    }

    @Test
    fun aNewEpochSendsASnapshot() {
        val rig = keep(AppLaneRig())
        rig.write("desk", "d1", 1, "a", "one")
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.send(AppWire.sub("t", Cursor(rig.host.epoch + 1, 1)))
        assertEquals(1L, AppWire.parseSnap(lane.next("snap")).seq)
        lane.quiet()
    }

    @Test
    fun anOpIsAnsweredBeforeItsChangeAndCarriesTheConnectionsDevice() {
        val rig = keep(AppLaneRig())
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.send(AppWire.sub("t", null))
        lane.next("snap")
        // the op names another device: the connection's own (the token's holder) is what the record keeps
        lane.op(Op("someone-else", OpId.Queued("s1", 1), "t", "set", listOf("a"), j("mine")))
        val ans = AppWire.parseAns(lane.next("ans"))
        assertEquals(OpId.Queued("s1", 1), ans.id)
        assertEquals(Answer.Seen(), ans.answer)
        val chg = AppWire.parseChg(lane.next("chg"))
        assertEquals("phone", chg.origin!!.device)
        assertEquals("phone", chg.patches.items["rec.t.a"]!!.by)
        assertEquals(mapOf("s1" to 1L), rig.host.highWaters("phone"))
        assertEquals(emptyMap(), rig.host.highWaters("someone-else"))
        val again = keep(RawLane(rig.port))
        assertEquals(mapOf("s1" to 1L), again.welcome().highWater, "welcome carries the device's high-water")
    }

    /** An op that does not decode but names its stream and number takes that number (refused for good), so the
     *  client's next op is not met with "expected n" for ever. */
    @Test
    fun anOpThatDoesNotDecodeTakesItsNumberAndTheStreamGoesOn() {
        val rig = keep(AppLaneRig())
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.opJson(buildJsonObject { put("device", "phone"); put("stream", "s1"); put("n", 1); put("window", "t") })
        val bad = lane.answer() as Answer.Refused
        lane.op(Op("phone", OpId.Queued("s1", 2), "t", "set", listOf("a"), j("two")))
        assertEquals(Answer.Seen(), lane.answer(), "the stream goes on past the op that did not decode")
        assertTrue(bad.reason.startsWith("the op does not decode"), bad.reason)
        assertEquals(Answer.UNKNOWN, bad.code, "refused for good: the client drops it")
    }

    @Test
    fun anOpOfAnEndedConnectionIsRefused() {
        val rig = keep(AppLaneRig())
        val gone = RawLane(rig.port)
        val goneConn = gone.welcome().conn
        gone.close()
        val lane = keep(RawLane(rig.port))
        val conn = lane.welcome().conn
        assertTrue(conn > goneConn, "a reconnect starts a new conn")
        val seq = rig.host.seq
        lane.op(Op("phone", OpId.Conn(goneConn, 1), "t", "set", listOf("a"), j("x")))
        val ended = lane.answer() as Answer.Refused
        assertEquals(Answer.ENDED, ended.code)
        lane.op(Op("phone", OpId.Conn(conn, 1), "t", "set", listOf("a"), j("x")))
        assertEquals(Answer.UNSUPPORTED, (lane.answer() as Answer.Refused).code, "S1-1 has no never-queued action")
        assertEquals(seq, rig.host.seq, "neither took a line")
    }

    /** C§10.6: the local secret names the device `local` only on a loopback arrival (the rig models another one). */
    @Test
    fun theLocalSecretIsRefusedOffLoopback() {
        // a refused hello is sent alone: a host that closes on unread bytes resets the connection
        val off = keep(AppLaneRig(localSecret = "local-secret-test", onLoopback = { false }))
        val err = keep(RawLane(off.port, token = "", local = "local-secret-test", lane = false)).next("err")
        assertEquals(JsonPrimitive("bad token"), err["detail"])
        val on = keep(AppLaneRig(localSecret = "local-secret-test", onLoopback = { true }))
        assertEquals("local", keep(RawLane(on.port, token = "", local = "local-secret-test")).welcome().device)
        assertEquals("err", AppWire.type(keep(RawLane(on.port, token = "", local = "not-the-secret", lane = false)).next()))
        on.localSecret = null                            // the file deleted under a running client: its next hello
        assertEquals("err", AppWire.type(keep(RawLane(on.port, token = "", local = "local-secret-test", lane = false)).next()))
    }

    /** An old client never asks for the lane: every other lane answers on a host that serves it, as before. */
    @Test
    fun theOtherLanesStillAnswerOnTheSameHost() {
        val echo = object : WinService {
            override fun request(op: String, args: JsonObject) = WinService.Answer(buildJsonObject { put("op", op) })
        }
        val rec = RecordRig()
        val store = Persistence(rec.dir.resolve("state.json"))
        val rig = keep(AppLaneRig(rec, sync = SyncPeer(store), win = mapOf("echo" to echo)))
        fun lane(vararg frames: JsonObject): JsonObject = loopback(rig.port).use { s ->
            val o = DataOutputStream(s.getOutputStream().buffered())
            val i = DataInputStream(s.getInputStream().buffered())
            AppWire.write(o, AppWire.encode(buildJsonObject { put("t", "hello"); put("token", "tok"); put("proto", 1) }))
            for (f in frames) AppWire.write(o, AppWire.encode(f))
            AppWire.read(i)
        }
        assertEquals("library", AppWire.type(lane(buildJsonObject { put("t", "library") })))
        val miss = lane(buildJsonObject { put("t", "get"); put("id", "no-such-book") })
        assertEquals("err", AppWire.type(miss))
        assertTrue((miss["detail"] as JsonPrimitive).content.startsWith("book no-such-book"), "$miss")
        assertEquals("syncok", AppWire.type(lane(buildJsonObject {
            put("t", "sync"); put("clock", 1L); put("stamps", buildJsonObject {})
        })))
        assertEquals("wup", AppWire.type(lane(buildJsonObject { put("t", "win"); put("win", "echo") })))
        assertEquals("welcome", AppWire.type(lane(AppWire.hello("s1", mapOf("t" to 1)))))
    }

    /** A newer client's lane hello (protocol 3) is ended cleanly with the reason; the host serves the next. */
    @Test
    fun aLaneHelloOfAnotherProtocolIsEndedCleanly() {
        val rig = keep(AppLaneRig())
        val newer = keep(RawLane(rig.port, proto = 3))
        val end = newer.next("end")
        assertEquals(JsonPrimitive(AppWire.END_PROTO), end["reason"])
        assertTrue("speaks 2" in (end["detail"] as JsonPrimitive).content, "$end")
        assertEquals("phone", keep(RawLane(rig.port)).welcome().device)
    }

    /**
     * New client, old PC (§4): an old content host answers the lane with its in-band "unknown request" (as
     * `Content.kt`'s else-branch has since R3#2). The client's content hello is `proto: 1` (an old host refuses any
     * other), it is never welcomed, it says the refusal once, retries on its pacing, and every window it converts is
     * on different builds. The same holds for a new host that serves no lane.
     */
    @Test
    fun anOldPcsRefusalOfTheLaneIsSurvivedQuietlyAtPacing(): Unit = runBlocking {
        Log.addSink(sink)
        val connections = AtomicInteger()
        val protos = CopyOnWriteArrayList<Int>()
        val old = keep(ServerSocket(0, 50, InetAddress.getLoopbackAddress()))
        Thread({
            while (true) {
                val s = try { old.accept() } catch (e: Exception) { break }
                connections.incrementAndGet()
                Thread({
                    s.use {
                        try {
                            val i = DataInputStream(it.getInputStream().buffered())
                            val o = DataOutputStream(it.getOutputStream().buffered())
                            val hello = AppWire.read(i)
                            val proto = (hello["proto"] as JsonPrimitive).content.toInt()
                            protos += proto
                            if (proto != 1) {
                                AppWire.write(o, AppWire.encode(buildJsonObject { put("t", "err"); put("detail", "protocol $proto unsupported (host speaks 1)") }))
                                return@use
                            }
                            while (true) {
                                val t = AppWire.type(AppWire.read(i))
                                AppWire.write(o, AppWire.encode(buildJsonObject { put("t", "err"); put("detail", "unknown request '$t' (version skew?)") }))
                            }
                        } catch (e: Exception) { /* the client closed */ }
                    }
                }, "old-pc-conn").apply { isDaemon = true }.start()
            }
        }, "old-pc").apply { isDaemon = true }.start()
        val welcomed = AtomicInteger()
        val states = CopyOnWriteArrayList<Contracts.State>()
        val client = keep(AppClient("127.0.0.1", old.localPort, AppClient.Credential.Token("tok"), "s1",
            object : AppClient.Listener {
                override fun welcome(w: wm.damage.core.app.Welcome) { welcomed.incrementAndGet() }
                override fun contracts(s: Contracts.State) { states += s }
            }, scope, contracts = mapOf("t" to 1), retryPacingMs = 10, refusedPacingMs = 50, pingEveryMs = 0))
        waitUntil(10_000, 5, { "three paced attempts: ${connections.get()}" }) { connections.get() >= 3 }
        assertEquals(setOf(1), protos.toSet(), "the content port's hello stays proto 1")
        assertEquals(0, welcomed.get())
        val known = client.contractState as Contracts.State.Known
        assertTrue(known.oldPc && !known.shared("t"), "$known")
        assertEquals(Contracts.mismatchLine("t"), known.line("t"))
        assertEquals(1, states.size, "the state is said once, not per attempt: $states")
        assertEquals(1, logs.count { "refused the app lane" in it }, "said once per run of refusals: $logs")
        // a new host that serves no lane refuses the same way
        val bare = keep(AppLaneRig(withApp = false))
        val c2 = keep(AppClient("127.0.0.1", bare.port, AppClient.Credential.Token("tok"), "s1", object : AppClient.Listener {},
            scope, contracts = mapOf("t" to 1), retryPacingMs = 10, refusedPacingMs = 50, pingEveryMs = 0))
        waitUntil(10_000, 5, { "refused: ${c2.contractState}" }) { (c2.contractState as? Contracts.State.Known)?.oldPc == true }
        assertNull(c2.welcome)
    }

    private fun loopback(port: Int) = java.net.Socket(InetAddress.getLoopbackAddress(), port)
}
