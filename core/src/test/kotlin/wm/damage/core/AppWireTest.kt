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
     * with the reason; a phone keeps its own copy, as with the PC out of reach (the reason, since S1-2 Q1's N3, says only
     * the PC's part: a browser keeps none). L1 R-a: once a later tick opens the log, what was refused is served.
     */
    @Test
    fun whileTheLogIsClosed_aSubscriptionIsRefusedWithTheReason_neverASnapshot_andServedOnceItOpens() {
        val rec = RecordRig()
        rec.opened().also { h -> rec.ask(h, op("desk", "d1", 1, "set", listOf("a"), j("one"))); rec.stop(h) }
        val perms = java.nio.file.Files.getPosixFilePermissions(rec.logFile)
        java.nio.file.Files.setPosixFilePermissions(rec.logFile, emptySet())     // this start cannot open the log
        val rig = keep(AppLaneRig(rec, rec.opened()))
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.send(AppWire.sub("t", null))
        val a = wm.damage.core.app.OpJson.answer(lane.next("ans")["answer"] as JsonObject) as Answer.Refused
        assertEquals(Answer.RETRY, a.code)
        assertEquals(wm.damage.core.app.AppHost.LOG_CLOSED, a.reason)
        // build S1-2 Q1 (N3): the PC's words alone — a browser is told this too, and a browser keeps no copy
        assertTrue("its own copy" !in a.reason, "the refusal speaks only for the PC: '${a.reason}'")
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
        assertTrue("its own copy" !in d.reason, "a window's scope is refused in the PC's words alone: '${d.reason}'")
        lane.send(AppWire.sub("diag", null))
        assertTrue(lane.next("snap")["desc"] != null, "the PC's own page is still described")
        lane.quiet()
        java.nio.file.Files.setPosixFilePermissions(rec.logFile, perms)
        rec.tick(rig.host)                                    // the log opens
        rec.tick(rig.host)                                    // the lane serves what it refused meanwhile
        assertEquals(JsonPrimitive("one"), AppWire.parseSnap(lane.next("snap")).state.items["rec.t.a"]!!.value)
        assertTrue(lane.next("snap")["desc"] != null, "the window's scope is described")
        lane.quiet()
    }

    /** L1 C2: a `sub` that names a cursor (a 0.67 phone's) still gets a snapshot, then the live changes. */
    @Test
    fun aSubscriptionWithACursorGetsASnapshotAndThenTheLiveChanges() {
        val rig = keep(AppLaneRig())
        rig.write("desk", "d1", 1, "a", "one")
        val e = rig.host.epoch
        rig.write("desk", "d1", 2, "a", "two")
        rig.write("desk", "d1", 3, "b", "three")
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.send(AppWire.sub("t", Cursor(e, 1)))
        val snap = AppWire.parseSnap(lane.next("snap"))
        assertEquals(3L, snap.seq)
        assertEquals(JsonPrimitive("two"), snap.state.items["rec.t.a"]!!.value)
        lane.quiet()
        rig.write("desk", "d1", 4, "a", "four")
        val live = AppWire.parseChg(lane.next("chg"))
        assertEquals(4L, live.seq)
    }

    @Test
    fun anOpIsAnsweredBeforeItsChangeAndCarriesTheConnectionsDevice() {
        val rig = keep(AppLaneRig())
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.send(AppWire.sub("t", null))
        lane.next("snap")
        // the op names another device: the connection's own (the token's holder) is what the record keeps
        lane.op(Op("someone-else", OpId.Queued("s1", 1), "t", "set", listOf("a"), j("mine"), at = 1))
        val ans = AppWire.parseAns(lane.next("ans"))
        assertEquals(OpId.Queued("s1", 1), ans.id)
        assertEquals(Answer.Seen, ans.answer)
        val chg = AppWire.parseChg(lane.next("chg"))
        assertEquals("phone", chg.patches.items["rec.t.a"]!!.by)
    }

    /** An op that does not decode is refused for good in words, to the id it names; the client's next op is taken. */
    @Test
    fun anOpThatDoesNotDecodeIsRefusedAndTheNextIsTaken() {
        val rig = keep(AppLaneRig())
        val lane = keep(RawLane(rig.port))
        lane.welcome()
        lane.opJson(buildJsonObject { put("device", "phone"); put("stream", "s1"); put("n", 1); put("window", "t") })
        val bad = lane.answer() as Answer.Refused
        lane.op(Op("phone", OpId.Queued("s1", 2), "t", "set", listOf("a"), j("two"), at = 1))
        assertEquals(Answer.Seen, lane.answer(), "the next op is taken")
        assertTrue(bad.reason.startsWith("the op does not decode"), bad.reason)
        assertEquals(Answer.INVALID, bad.code, "refused for good: the client drops it")
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
        lane.op(Op("phone", OpId.Conn(goneConn, 1), "t", "set", listOf("a"), j("x"), at = 1))
        val ended = lane.answer() as Answer.Refused
        assertEquals(Answer.ENDED, ended.code)
        lane.op(Op("phone", OpId.Conn(conn, 1), "t", "set", listOf("a"), j("x"), at = 1))
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

    /** A lane hello of another protocol — APK 0.67's (protocol 2), which then says the builds differ and waits — is
     *  ended cleanly with the reason; the host serves the next. */
    @Test
    fun aLaneHelloOfAnotherProtocolIsEndedCleanly() {
        val rig = keep(AppLaneRig())
        val older = keep(RawLane(rig.port, proto = 2))
        val end = older.next("end")
        assertEquals(JsonPrimitive(AppWire.END_PROTO), end["reason"])
        assertTrue("speaks ${AppWire.PROTO}" in (end["detail"] as JsonPrimitive).content, "$end")
        assertEquals("phone", keep(RawLane(rig.port)).welcome().device)
    }

    private fun loopback(port: Int) = java.net.Socket(InetAddress.getLoopbackAddress(), port)
}
