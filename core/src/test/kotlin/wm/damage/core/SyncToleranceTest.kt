package wm.damage.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sync.RemoteSync
import wm.damage.core.sync.SyncNet
import wm.damage.core.sync.SyncPeer
import wm.damage.core.transport.SimTransport
import wm.damage.core.util.Log

/**
 * HANDOFF §75.1, APK 0.64 P3 part a: two copies of one record whose stamps are within
 * [Persistence.STAMP_TOLERANCE_MS] are the same record. The handshake no longer moves them, a
 * stored stamp no longer moves for them, and a record the store already holds does no work on
 * the shell loop. A DIFFERENT value keeps the strict order on arrival.
 *
 * No pin here reads a wall clock to decide. The wire pins hand the sync code a clock the test
 * holds (`now`), so a skew is exact; a wait is on an event and decides on one evaluation.
 */
class SyncToleranceTest {

    private val tol = Persistence.STAMP_TOLERANCE_MS

    private fun blob(n: Int): JsonObject = buildJsonObject { put("v", n) }

    /** ONE evaluation decides: the condition that ends the wait is the one that passes it. */
    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (true) {
            if (cond()) return
            if (System.currentTimeMillis() - t0 >= ms) fail("did not happen: $what")
            delay(10)
        }
    }

    /**
     * The clock of the two receive-side skew pins (R2, RE-6): it answers [value]; once [signalled] is set it
     * opens [readAfter] — the event "the clock was read after the prefix", on which the test thread moves the
     * clock at once. [afterSignal] runs right after the latch opens (the harness self-check below passes one
     * that waits for the test thread's move).
     */
    private fun signallingClock(value: AtomicLong, signalled: AtomicBoolean, readAfter: CountDownLatch,
                                onRead: () -> Unit = {}, afterSignal: () -> Unit = {}): () -> Long = {
        onRead()
        val v = value.get()                                 // read FIRST: the signal lets the test thread move it
        if (signalled.get()) { readAfter.countDown(); afterSignal() }
        v
    }

    /** R2 (RE-6), the harness's own check: the reading the clock answers is the one taken before it signalled.
     *  The test thread moves the clock the moment the latch opens; a clock that reads its value after the
     *  signal can answer the moved value — a 9 s skew on a correct tree, and the message that names the
     *  product defect. Here the signal is followed by a wait for that move, so the late read happens every time. */
    @Test
    fun theSkewPinsClockAnswersTheValueItReadBeforeItsSignal() {
        val v = AtomicLong(1)
        val opened = CountDownLatch(1)
        val moved = CountDownLatch(1)
        val clock = signallingClock(v, AtomicBoolean(true), opened, afterSignal = { moved.await(10, TimeUnit.SECONDS) })
        val got = AtomicLong(-1)
        val reader = Thread { got.set(clock()) }.apply { isDaemon = true; start() }
        assertTrue(opened.await(10, TimeUnit.SECONDS), "the clock signalled")
        v.set(2); moved.countDown()                       // the test thread's move, as the two pins make it
        reader.join(10_000)
        assertEquals(1L, got.get(), "the clock answered a value read after its signal: the test thread's move")
    }

    private suspend fun settle(shell: Shell) {
        val t0 = System.currentTimeMillis()
        while (true) {
            if (shell.isQuiescent()) return
            if (System.currentTimeMillis() - t0 >= 20_000) fail("shell did not settle: ${shell.quiescenceReport()}")
            delay(5)
        }
    }

    private fun fileKey(f: Path): Any =
        Files.readAttributes(f, BasicFileAttributes::class.java).fileKey() ?: error("no file key on this file system")

    /**
     * Was the store's file replaced while [action] ran? A save writes a temp file and renames it
     * over the store, so the file under the name is a new one (a new inode) after any save. The
     * file as it was is held OPEN for the duration: its inode number cannot then be handed to a
     * later temp file, which is how two saves could read as none. A modified time is not read: it
     * can repeat inside one clock tick.
     */
    private inline fun replacedDuring(f: Path, action: () -> Unit): Boolean =
        Files.newByteChannel(f).use {
            val before = fileKey(f)
            action()
            fileKey(f) != before
        }

    private fun linesNaming(what: String) = Log.recent().count { what in it }

    // ---------------------------------------------------------------- the store's table

    @Test
    fun anEqualValueInsideTheToleranceMovesNothing() {
        val tmp = Files.createTempDirectory("damage-tol-equal")
        try {
            val f = tmp.resolve("state.json")
            val p = Persistence(f)
            p.put("window.w", blob(1))
            assertTrue(p.save(), "the store's file exists before anything is measured")
            val t = p.stamp("window.w")

            val replaced = replacedDuring(f) {
                assertFalse(p.tryApplyRemote("window.w", blob(1), t + 300))
                assertFalse(p.tryApplyRemote("window.w", blob(1), t - 300))
                assertFalse(p.tryApplyRemote("window.w", blob(1), t + tol), "exactly the tolerance is inside it")
            }
            assertEquals(t, p.stamp("window.w"), "an equal value inside the tolerance moved the stamp")
            assertFalse(replaced, "an equal value inside the tolerance was saved: the file was replaced")
            assertTrue(p.holdsSame("window.w", blob(1), t + 300))
            assertFalse(p.holdsSame("window.w", blob(2), t + 300), "a different value is never held")
            assertFalse(p.holdsSame("window.absent", blob(1), t), "an absent key is never held")

            // the instrument sees a save when there is one
            assertTrue(replacedDuring(f) { assertTrue(p.tryApplyRemote("window.w", blob(2), t + 300)) },
                "a record that applies is saved, and the instrument sees it")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun anEqualValueBeyondTheToleranceAdoptsTheHigherStamp() {
        val tmp = Files.createTempDirectory("damage-tol-beyond")
        try {
            val f = tmp.resolve("state.json")
            val p = Persistence(f)
            p.put("window.w", blob(1))
            assertTrue(p.save())
            val t = p.stamp("window.w")

            assertFalse(p.tryApplyRemote("window.w", blob(1), t - 5_000), "an equal value is never 'applied'")
            assertEquals(t, p.stamp("window.w"), "an older stamp is never adopted")

            assertFalse(p.holdsSame("window.w", blob(1), t + tol + 1))
            val replaced = replacedDuring(f) { assertFalse(p.tryApplyRemote("window.w", blob(1), t + tol + 1)) }
            assertEquals(t + tol + 1, p.stamp("window.w"), "past the tolerance the higher stamp is adopted")
            assertTrue(replaced, "an adopted stamp is saved")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** The walk of 2026-11-10: each side's skew estimate errs by the one-way delay, the same sign
     *  both ways, so a record sent across arrives stamped a delay later than the copy it meets. */
    @Test
    fun aStampNeverWalks() {
        val tmp = Files.createTempDirectory("damage-tol-walk")
        try {
            val a = Persistence(tmp.resolve("a.json"))
            val b = Persistence(tmp.resolve("b.json"))
            a.put("window.w", blob(1))
            b.tryApplyRemote("window.w", blob(1), a.stamp("window.w"))
            val a0 = a.stamp("window.w")
            val b0 = b.stamp("window.w")
            repeat(50) {
                b.tryApplyRemote("window.w", blob(1), a.stamp("window.w") + 137)
                a.tryApplyRemote("window.w", blob(1), b.stamp("window.w") + 211)
            }
            assertEquals(a0, a.stamp("window.w"), "A's stamp climbed over 50 exchanges")
            assertEquals(b0, b.stamp("window.w"), "B's stamp climbed over 50 exchanges")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aDifferentValueKeepsTheStrictOrder() {
        val tmp = Files.createTempDirectory("damage-tol-strict")
        try {
            val f = tmp.resolve("state.json")
            val p = Persistence(f)
            val k = "window.strict${System.nanoTime()}"         // the log is the process's: a name no other test writes
            val far = "$k.far"
            p.put(k, blob(1))
            p.put(far, blob(1))
            val t = p.stamp(k)

            assertTrue(p.tryApplyRemote(k, blob(2), t + 300), "a different value 300 ms newer must land")
            assertEquals(blob(2), p.get(k))
            assertEquals(t + 300, p.stamp(k))
            assertTrue(p.tryApplyRemote(k, blob(3), t + 301), "and one a millisecond after that")
            val held = t + 301

            // a refused record changes nothing, so nothing is saved for it (0.64 P3 verify: the
            // table's last row had no pin on the file; the applies above wrote it)
            val replaced = replacedDuring(f) {
                assertFalse(p.tryApplyRemote(k, blob(4), held), "an equal stamp does not replace a different value")
                assertFalse(p.tryApplyRemote(k, blob(4), held - 1))
            }
            assertFalse(replaced, "a refused record was saved: the file was replaced")
            assertEquals(blob(3), p.get(k))
            assertEquals(held, p.stamp(k))
            val said = "'$k': the peer's record is"
            assertEquals(1, linesNaming(said), "a refusal inside the tolerance is said once per key")
            assertTrue(Log.recent().any { said in it && "0 ms older than ours, inside the tolerance" in it },
                "the line carries the distance of the first refusal")

            // a refusal past the tolerance is today's ordinary outcome and is not said
            assertFalse(p.tryApplyRemote(far, blob(4), p.stamp(far) - tol - 1))
            assertEquals(0, linesNaming("'$far': the peer's record is"), "a refusal past the tolerance was said")
            assertFalse(p.tryApplyRemote(far, blob(4), p.stamp(far) - tol))
            assertEquals(1, linesNaming("'$far': the peer's record is"), "exactly the tolerance is inside it")

            // a new sync connection may say it once more
            p.syncConnectionStarted()
            assertFalse(p.tryApplyRemote(k, blob(4), held - 2))
            assertFalse(p.tryApplyRemote(k, blob(4), held - 3))
            assertEquals(2, linesNaming(said), "a new connection says a standing refusal once more, and only once")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aBaselineLosesToAnyRealStamp() {
        assertTrue(Persistence.newer(1, 0), "any real stamp is newer than a baseline's 0 at once")
        assertTrue(Persistence.newer(1_000, 0), "any real stamp is newer than a baseline's 0 at once")
        assertFalse(Persistence.newer(0, 0), "a baseline is not newer than a baseline")
        assertFalse(Persistence.newer(0, 5_000), "a baseline is never newer than a real stamp")
        assertFalse(Persistence.newer(tol + 1, 1), "exactly the tolerance apart is not newer")
        assertTrue(Persistence.newer(tol + 2, 1))
        assertFalse(Persistence.newer(5, tol + 5))
    }

    // ---------------------------------------------------------------- raw frames

    private val rawJson = Json { ignoreUnknownKeys = true }

    private fun DataOutputStream.frame(o: JsonObject) {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
        flush()
    }

    private fun DataInputStream.frame(): JsonObject {
        val n = readInt()
        val b = ByteArray(n)
        readFully(b)
        return rawJson.parseToJsonElement(b.toString(Charsets.UTF_8)).jsonObject
    }

    private fun syncFrame(clock: Long, vararg stamps: Pair<String, Long>): JsonObject = buildJsonObject {
        put("t", "sync")
        put("stamps", buildJsonObject { for ((k, t) in stamps) put(k, t) })
        put("clock", clock)
    }

    private fun rec(k: String, v: JsonObject, t: Long): JsonObject = buildJsonObject { put("k", k); put("v", v); put("t", t) }

    private fun JsonObject.keysOf(field: String): List<String> = (this[field] as? JsonArray ?: JsonArray(emptyList())).map {
        if (field == "records") it.jsonObject["k"]!!.jsonPrimitive.content else it.jsonPrimitive.content
    }

    /** The raw client's end: [ask] writes one frame and reads the server's next one. */
    private inner class Wire(val inp: DataInputStream, val out: DataOutputStream) {
        fun ask(w: JsonObject): JsonObject { out.frame(w); return inp.frame() }
    }

    /** A raw client on the real server half ([SyncNet.serve]) with this side's clock read from
     *  [serverNow]: the test writes the frames a peer would and reads each `syncok`. */
    private fun withServer(store: Persistence, serverNow: () -> Long, firstSync: JsonObject,
                           body: (wire: Wire, first: JsonObject) -> Unit) {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { client ->
                val served = listener.accept()
                val t = Thread {
                    SyncNet.serve(served, DataInputStream(served.getInputStream().buffered()),
                        DataOutputStream(served.getOutputStream().buffered()), SyncPeer(store),
                        firstSync.toString(), now = serverNow)
                }.apply { isDaemon = true; start() }
                val wire = Wire(DataInputStream(client.getInputStream().buffered()),
                    DataOutputStream(client.getOutputStream().buffered()))
                try {
                    body(wire, wire.inp.frame())
                } finally {
                    client.close()
                    t.join(10_000)
                }
            }
        }
    }

    /** Also "phone 0.63 against the new PC": the old client's handshake is these frames. */
    @Test
    fun aHandshakeLeavesRecordsInsideTheToleranceAlone() {
        val tmp = Files.createTempDirectory("damage-tol-shake")
        try {
            val store = Persistence(tmp.resolve("pc.json"))
            store.put("window.w", blob(1))
            val s = store.stamp("window.w")
            val n = 5_000_000L                  // the server's clock
            val ahead = 7_000L                  // the peer's clock runs this far ahead: its stamps arrive shifted back
            fun ask(off: Long) = syncFrame(n + ahead, "window.w" to s + ahead + off)

            // a refusal inside the tolerance, said for the connection before this one
            val k = "local.said${System.nanoTime()}"         // not a synced key: no handshake lists it
            val said = "'$k': the peer's record is"
            store.put(k, blob(1))
            assertFalse(store.tryApplyRemote(k, blob(2), store.stamp(k) - 1))
            assertFalse(store.tryApplyRemote(k, blob(2), store.stamp(k) - 1))
            assertEquals(1, linesNaming(said))

            withServer(store, { n }, ask(300)) { wire, first ->
                assertEquals(emptyList(), first.keysOf("want"), "a peer's copy 300 ms newer was asked for")
                assertEquals(emptyList(), first.keysOf("records"))
                assertEquals(n, first["clock"]!!.jsonPrimitive.long, "the answer carries the server's clock")
                // the answer is written after the attach: a new connection may say a refusal once more
                assertFalse(store.tryApplyRemote(k, blob(2), store.stamp(k) - 1))
                assertEquals(2, linesNaming(said), "a new connection did not start a new 'said' memory")

                val older = wire.ask(ask(-300))
                assertEquals(emptyList(), older.keysOf("records"), "our copy 300 ms newer was sent")
                assertEquals(emptyList(), older.keysOf("want"))

                val edge = wire.ask(ask(tol))
                assertEquals(emptyList(), edge.keysOf("want"), "exactly the tolerance is inside it")

                // past the tolerance the handshake still moves the record, each way
                assertEquals(listOf("window.w"), wire.ask(ask(tol + 1)).keysOf("want"))
                val sent = wire.ask(ask(-tol - 1))
                assertEquals(listOf("window.w"), sent.keysOf("records"))
                val r = sent["records"]!!.jsonArray[0].jsonObject
                assertEquals(blob(1), r["v"]!!.jsonObject)
                assertEquals(s, r["t"]!!.jsonPrimitive.long, "a record goes out with its own stamp")
            }
            assertEquals(s, store.stamp("window.w"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** A stamp of 0 is "never edited". Shifted by the skew it read as a real stamp (review NIT 13). */
    @Test
    fun aBaselinesZeroStaysZeroAcrossTheWire() {
        val tmp = Files.createTempDirectory("damage-tol-zero")
        try {
            val store = Persistence(tmp.resolve("pc.json"))
            store.putBaseline("window.base", blob(0))
            val n = 5_000_000L
            // the peer's clock runs 10 s BEHIND: unguarded, its 0 arrives as +10,000
            val first = syncFrame(n - 10_000, "window.base" to 0L, "window.never" to 0L)
            withServer(store, { n }, first) { wire, answer ->
                assertEquals(emptyList(), answer.keysOf("want"), "a peer's baseline (stamp 0) was asked for")
                assertEquals(emptyList(), answer.keysOf("records"))
                // a pushed record stamped 0 is stored at 0. The server takes frames in order, so
                // its answer to the handshake after the push says the push was applied
                wire.out.frame(buildJsonObject { put("t", "syncrec"); put("records", buildJsonArray { add(rec("window.z", blob(4), 0L)) }) })
                wire.ask(first)
                assertEquals(blob(4), store.get("window.z"))
                assertEquals(0L, store.stamp("window.z"), "a pushed record stamped 0 was shifted by the skew")
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * The server's half of finding 9: its receive time for a re-handshake is taken when the
     * frame's length prefix arrives. The rig sends the prefix and half the frame, waits for the
     * server to read its clock, moves the clock on by 9 s and sends the rest. Read late, the
     * skew is 9 s off and a copy 300 ms newer is asked for.
     */
    @Test
    fun theServersSkewIsTakenWhenTheFramesLengthArrives() {
        val tmp = Files.createTempDirectory("damage-tol-clock-srv")
        try {
            val store = Persistence(tmp.resolve("pc.json"))
            store.put("window.w", blob(1))
            val s = store.stamp("window.w")
            val n = AtomicLong(5_000_000)
            val ahead = 7_000L
            val prefixSent = AtomicBoolean(false)
            val readAfterPrefix = CountDownLatch(1)
            val clock = signallingClock(n, prefixSent, readAfterPrefix)
            val frame = syncFrame(5_000_000 + ahead, "window.w" to s + ahead + 300)
            withServer(store, clock, frame) { wire, first ->
                assertEquals(emptyList(), first.keysOf("want"))
                val b = frame.toString().toByteArray(Charsets.UTF_8)
                val half = b.size / 2
                wire.out.writeInt(b.size)
                wire.out.write(b, 0, half)
                prefixSent.set(true)
                wire.out.flush()
                val early = readAfterPrefix.await(10, TimeUnit.SECONDS)
                n.set(5_009_000)
                wire.out.write(b, half, b.size - half)
                wire.out.flush()
                val answer = wire.inp.frame()
                assertTrue(early, "the server did not read its clock when the frame's length prefix arrived")
                assertEquals(emptyList(), answer.keysOf("want"),
                    "the skew was computed from a clock read after the frame's body arrived")
                assertEquals(emptyList(), answer.keysOf("records"))
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- the real client against raw frames

    private class RawServer : AutoCloseable {
        private val listener = ServerSocket(0)
        val port: Int get() = listener.localPort
        lateinit var sock: Socket
        lateinit var inp: DataInputStream
        lateinit var out: DataOutputStream
        fun accept() {
            sock = listener.accept()
            inp = DataInputStream(sock.getInputStream().buffered())
            out = DataOutputStream(sock.getOutputStream().buffered())
        }
        override fun close() {
            try { if (::sock.isInitialized) sock.close() } catch (e: Exception) { /* closing */ }
            listener.close()
        }
    }

    /**
     * "The new phone against PC de67f99": the old server's strict diff still sends our own record
     * back 300 ms "newer" and asks for it. It reaches the applier (decided where it is applied,
     * review finding 1), moves no stamp and rewrites no file; what the server asked for is sent.
     */
    @Test
    fun theOldServersReplyMovesNoStampAndRewritesNoFile(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-tol-oldpc")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = RawServer()
        var client: RemoteSync? = null
        try {
            val f = tmp.resolve("phone.json")
            val store = Persistence(f)
            store.put("window.own", blob(1))
            // a refusal inside the tolerance, said for the connection before this one
            val k = "local.said${System.nanoTime()}"         // not a synced key: no handshake lists it
            val said = "'$k': the peer's record is"
            store.put(k, blob(1))
            assertFalse(store.tryApplyRemote(k, blob(2), store.stamp(k) - 1))
            assertFalse(store.tryApplyRemote(k, blob(2), store.stamp(k) - 1))
            assertEquals(1, linesNaming(said))
            assertTrue(store.save())
            val own = store.stamp("window.own")

            data class Seen(val k: String, val t: Long, val replaced: Boolean)
            val seen = CopyOnWriteArrayList<Seen>()
            val n = 9_000_000L                                   // the client's clock, held
            client = RemoteSync("127.0.0.1", server.port, "tok", scope, SyncPeer(store, applier = { k, v, t ->
                // on the client's reader, one record at a time: the file is read before and after THIS record
                val replaced = replacedDuring(f) { store.tryApplyRemote(k, v, t) }
                seen.add(Seen(k, t, replaced))
            }), retryPacingMs = 60_000, now = { n })

            server.accept()
            server.inp.frame()                                   // hello
            val sync = server.inp.frame()
            assertEquals("sync", sync["t"]!!.jsonPrimitive.content)
            assertEquals(n, sync["clock"]!!.jsonPrimitive.long)
            assertEquals(own, sync["stamps"]!!.jsonObject["window.own"]!!.jsonPrimitive.long)

            // the server's clock runs 10 s behind the client's: its stamps arrive shifted forward by 10 s
            val behind = 10_000L
            server.out.frame(buildJsonObject {
                put("t", "syncok")
                put("clock", n - behind)
                put("records", buildJsonArray {
                    add(rec("window.own", blob(1), own + 300 - behind))
                    add(rec("window.zero", blob(5), 0L))
                    add(rec("window.marker", blob(9), own + 50 - behind))
                })
                put("want", buildJsonArray { add("window.own"); add("window.marker") })
            })
            awaitTrue("the client handled the three records") { seen.size == 3 }

            assertEquals(Seen("window.own", own + 300, false), seen[0],
                "our own record 300 ms newer: it reaches the applier on this clock, and the file is not rewritten")
            assertEquals(own, store.stamp("window.own"), "the stored stamp moved for an equal value inside the tolerance")
            assertEquals(Seen("window.zero", 0L, true), seen[1], "a record stamped 0 is not shifted by the skew")
            assertEquals(0L, store.stamp("window.zero"))
            assertEquals(Seen("window.marker", own + 50, true), seen[2], "a new record is saved, and the instrument sees it")

            val answer = server.inp.frame()
            assertEquals("syncrec", answer["t"]!!.jsonPrimitive.content)
            assertEquals(listOf("window.own", "window.marker"), answer.keysOf("records"), "what the server asked for is sent")

            // a live push stamped 0 is not shifted either
            server.out.frame(buildJsonObject { put("t", "syncrec"); put("records", buildJsonArray { add(rec("window.zero2", blob(6), 0L)) }) })
            awaitTrue("the client handled the push") { seen.size == 4 }
            assertEquals(Seen("window.zero2", 0L, true), seen[3], "a pushed record stamped 0 is not shifted by the skew")

            // the connect started a new 'said' memory
            assertFalse(store.tryApplyRemote(k, blob(2), store.stamp(k) - 1))
            assertEquals(2, linesNaming(said), "a new connection did not start a new 'said' memory")
        } finally {
            client?.close()
            server.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- finding 9: where the clocks are read

    /**
     * A re-handshake waits in the outbox behind the pushes queued before it. Its `clock` is the
     * time its bytes leave, not the time it was queued: the peer subtracts its own receive time
     * from it, and the wait would otherwise sit in the skew.
     *
     * The rig holds the frame AHEAD: the test's clock parks the sender where the first handshake
     * is stamped for the wire. A 300 KB push and a second handshake are then queued behind it,
     * the clock is moved from 1,000 to 9,000, and the sender is let go. With the stamp taken where
     * the frame is queued, both handshakes are stamped on the test's own thread (which the clock
     * never parks) at 1,000.
     */
    @Test
    fun aHandshakeQueuedBehindAPushCarriesTheClockOfItsWrite(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-tol-clock-out")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = RawServer()
        var client: RemoteSync? = null
        val gate = CountDownLatch(1)
        try {
            val store = Persistence(tmp.resolve("phone.json"))
            val t = AtomicLong(1_000)
            val testThread = Thread.currentThread()
            val armed = AtomicBoolean(false)
            client = RemoteSync("127.0.0.1", server.port, "tok", scope, SyncPeer(store), retryPacingMs = 60_000,
                rehandshakeMs = 3_600_000, now = {
                    if (armed.get() && Thread.currentThread() !== testThread) gate.await()
                    t.get()
                })
            server.accept()
            server.inp.frame()                                   // hello
            assertEquals(1_000L, server.inp.frame()["clock"]!!.jsonPrimitive.long, "the connect's own handshake")
            // a local put is queued as a push only once the client is attached, and it attaches
            // before it applies this answer's record: the record in the store is that event
            server.out.frame(buildJsonObject {
                put("t", "syncok"); put("clock", 1_000L)
                put("records", buildJsonArray { add(rec("window.attached", blob(1), 500L)) })
            })
            awaitTrue("the client attached") { store.get("window.attached") != null }

            armed.set(true)
            client.rehandshake()                                 // H1: the sender parks where it is stamped
            store.put("window.big", buildJsonObject { put("s", "x".repeat(300_000)) })
            client.rehandshake()                                 // H2: queued behind H1 and the push
            t.set(9_000)
            armed.set(false)
            gate.countDown()

            val frames = ArrayList<JsonObject>()
            while (frames.count { it["t"]!!.jsonPrimitive.content == "sync" } < 2) frames.add(server.inp.frame())
            assertEquals(listOf("sync", "syncrec", "sync"), frames.map { it["t"]!!.jsonPrimitive.content },
                "the second handshake left behind the push")
            assertEquals(9_000L, frames[2]["clock"]!!.jsonPrimitive.long,
                "a handshake queued behind a push carries the clock of the moment it was queued")
            assertEquals(9_000L, frames[0]["clock"]!!.jsonPrimitive.long)
        } finally {
            gate.countDown()
            client?.close()
            server.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * The client's receive time is taken when a frame's length prefix arrives, before its body is
     * read and decoded: a 216 KB `syncok` otherwise puts its own transfer time into the skew.
     *
     * The rig sends the prefix and half the body, waits for the client to read its clock (the
     * event the fix creates; its absence is this pin's failure), moves the clock from 1,000,000 to
     * 1,009,000 and sends the rest. The skew is read from the stamp a record is stored at.
     */
    @Test
    fun theSkewIsTakenWhenTheFramesLengthArrives(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-tol-clock-in")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = RawServer()
        var client: RemoteSync? = null
        try {
            val store = Persistence(tmp.resolve("phone.json"))
            val t = AtomicLong(1_000_000)
            val reads = AtomicInteger(0)
            val readAfterPrefix = CountDownLatch(1)
            val prefixSent = AtomicBoolean(false)
            client = RemoteSync("127.0.0.1", server.port, "tok", scope, SyncPeer(store), retryPacingMs = 60_000,
                rehandshakeMs = 3_600_000, now = signallingClock(t, prefixSent, readAfterPrefix, onRead = { reads.incrementAndGet() }))
            server.accept()
            server.inp.frame()                                   // hello
            server.inp.frame()                                   // sync (its clock read is before prefixSent)

            val body = buildJsonObject {
                put("t", "syncok")
                put("clock", 2_000_000L)                         // the server's clock: 1,000,000 ahead
                put("records", buildJsonArray { add(rec("window.m", blob(1), 2_000_500L)) })
            }.toString().toByteArray(Charsets.UTF_8)
            val half = body.size / 2
            server.out.writeInt(body.size)
            server.out.write(body, 0, half)
            prefixSent.set(true)
            server.out.flush()
            val early = readAfterPrefix.await(10, TimeUnit.SECONDS)
            t.set(1_009_000)
            server.out.write(body, half, body.size - half)
            server.out.flush()
            assertTrue(early, "the client did not read its clock when the frame's length prefix arrived (before the body)")

            awaitTrue("the record was stored") { store.get("window.m") != null }
            assertEquals(1_000_500L, store.stamp("window.m"),
                "the skew was computed from a clock read after the body arrived")
        } finally {
            client?.close()
            server.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- the live pair

    /** Guards the wrong fix: a tolerance on a DIFFERENT value would refuse the second of two edits
     *  made within two seconds, and the tolerant handshake would never carry it again. */
    @Test
    fun aSecondEditRightAfterTheFirstReachesThePeer(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-tol-second")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = ServerSocket(0).use { it.localPort }
        var host: ContentHostServer? = null
        var client: RemoteSync? = null
        try {
            val storeA = Persistence(tmp.resolve("a.json"))
            val storeB = Persistence(tmp.resolve("b.json"))
            storeB.put("window.w", blob(1))                      // the first edit: the handshake carries it
            host = ContentHostServer(LocalContent(tmp), port, "tok", sync = SyncPeer(storeA))
            host.start()
            client = RemoteSync("127.0.0.1", port, "tok", scope, SyncPeer(storeB), retryPacingMs = 200)
            // A holds it once the client answered the server's `want`, and the client attaches
            // before it answers: the next put is queued as a live push
            awaitTrue("the first edit reached A") { storeA.get("window.w") == blob(1) }
            storeB.put("window.w", blob(2))                      // at once: well inside the tolerance
            awaitTrue("the second edit reached A") { storeA.get("window.w") == blob(2) }
        } finally {
            client?.close()
            host?.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- the shell's door (review finding 1)

    private class CountingWin : DamageWindow("cw", "Counting", IconKind.FILES) {
        @Volatile var value = 0
        val items = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val saveCalls = AtomicInteger(0)
        val subCalls = AtomicInteger(0)
        var ctx: ShellServices? = null
        override fun onRegistered(ctx: ShellServices) { this.ctx = ctx }
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 },
            { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> }, { })
        override fun summary() = Summary("counting")
        /** The next [saveState] says it [entered] and waits on the gate (P5a, RE-4's sibling): a save held inside it. */
        @Volatile private var holdSave: Pair<CountDownLatch, CountDownLatch>? = null
        fun holdNextSave(entered: CountDownLatch, gate: CountDownLatch) { holdSave = entered to gate }
        override fun saveState(): JsonObject {
            saveCalls.incrementAndGet()
            holdSave?.let { (e, g) -> holdSave = null; e.countDown(); g.await() }
            return buildJsonObject { put("v", value) }
        }
        override fun restoreState(state: JsonObject) { value = state["v"]!!.jsonPrimitive.content.toInt() }
        override fun saveSubState(): Map<String, JsonObject> {
            subCalls.incrementAndGet()
            return items.entries.associate { (k, v) -> k to buildJsonObject { put("v", v) } }
        }
        override fun restoreSubState(subKey: String, state: JsonObject) {
            items[subKey] = state["v"]!!.jsonPrimitive.content.toInt()
        }
    }

    private class Rig(val tmp: Path, val scope: CoroutineScope, val f: Path, val store: Persistence,
                      val shell: Shell, val win: CountingWin)

    /** A started shell whose store holds `window.cw` and 76 of its items with real stamps. */
    private suspend fun rig(name: String): Rig {
        val tmp = Files.createTempDirectory(name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = tmp.resolve("s.json")
        val store = Persistence(f)
        store.put("window.cw", blob(3))
        for (i in 0 until 76) store.put("window.cw.i$i", blob(i))
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)),
            store, null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val win = CountingWin()
        shell.register(win)
        shell.start()
        settle(shell)
        assertEquals(3, win.value, "the window was restored from the store")
        assertEquals(76, win.items.size)
        assertTrue(store.save())
        return Rig(tmp, scope, f, store, shell, win)
    }

    /**
     * What an old PC re-sends every handshake: all 77 records, equal values, stamps a link delay
     * newer. Each reaches the loop as a no-op: the window is not asked for its state, no stamp
     * moves, the store's file is not rewritten.
     */
    @Test
    fun aHeldRecordPostedToTheShellDoesNoWorkAndSavesNothing(): Unit = runBlocking {
        val r = rig("damage-tol-held")
        try {
            val keys = listOf("window.cw") + (0 until 76).map { "window.cw.i$it" }
            val before = keys.associateWith { r.store.stamp(it) }
            r.win.saveCalls.set(0)
            r.win.subCalls.set(0)
            val replaced = replacedDuring(r.f) {
                for (k in keys) assertTrue(r.shell.postSync(k, r.store.get(k)!!, before[k]!! + 300))
                settle(r.shell)
            }
            assertEquals(0, r.win.saveCalls.get(), "a held record asked the window for its state (the freshen ran)")
            assertEquals(0, r.win.subCalls.get(), "a held item record asked the window for its items")
            assertEquals(before, keys.associateWith { r.store.stamp(it) }, "a stamp moved")
            assertFalse(replaced, "the store's file was rewritten for records it already held")

            // the same door, a record that is NOT held: the freshen runs, the stamp is adopted and saved
            val adopted = replacedDuring(r.f) {
                r.shell.postSync("window.cw", r.store.get("window.cw")!!, before["window.cw"]!! + tol + 1)
                r.shell.postSync("window.cw.i0", r.store.get("window.cw.i0")!!, before["window.cw.i0"]!! + tol + 1)
                settle(r.shell)
            }
            assertEquals(1, r.win.saveCalls.get(), "the counters see a freshen when there is one")
            assertEquals(1, r.win.subCalls.get())
            assertTrue(adopted, "a stamp adopted through the shell's door is saved")
            assertEquals(before["window.cw"]!! + tol + 1, r.store.stamp("window.cw"))
            r.shell.stop()
        } finally {
            r.scope.cancel()
            r.tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Records for one key are applied in the order they were posted, each against the store as
     * the one before it left it. The peer steps a row 3 → 4 → 3 inside two seconds: this side must
     * end on 3. It passes without the tolerance too; it is here for the PLACEMENT of the
     * held-record test, which made on the poster's thread compares the second record with the
     * store as it was before the first was applied, and drops it.
     */
    @Test
    fun twoRecordsForOneKeyPostedWhileTheLoopIsBusyEndOnTheSecond(): Unit = runBlocking {
        val r = rig("damage-tol-order")
        val gate = CountDownLatch(1)
        try {
            val s = r.store.stamp("window.cw")
            val entered = CountDownLatch(1)
            r.win.ctx!!.runOnShell { entered.countDown(); gate.await() }
            assertTrue(entered.await(20, TimeUnit.SECONDS), "the loop took the holding action")

            assertTrue(r.shell.postSync("window.cw", blob(4), s + 500))
            assertTrue(r.shell.postSync("window.cw", blob(3), s + 900))
            assertEquals(blob(3), r.store.get("window.cw"), "nothing is applied while the loop is held")
            gate.countDown()
            settle(r.shell)

            assertEquals(blob(3), r.store.get("window.cw"), "the second record for the key was not applied")
            assertEquals(s + 900, r.store.stamp("window.cw"))
            assertEquals(3, r.win.value, "the window shows the last value the peer sent")
            r.shell.stop()
        } finally {
            gate.countDown()
            r.scope.cancel()
            r.tmp.toFile().deleteRecursively()
        }
    }

    /**
     * R2 (RE-4): a synced record that reaches the shell in its stop's last instant — `running` still true, so
     * it is posted, behind the Shutdown — is stored through the persistence by its `dropped` arm in the loop's
     * drain. It was counted there and lost: the peer's second edit inside 2 s then stayed missing until a
     * handshake carried it. The order is made (the loop held, the stop's Shutdown queued by an undispatched
     * launch, the record behind it); the wait is on the STORE, one evaluation, and the wall clock bounds the
     * failing path only.
     */
    /**
     * P5 part a, RE-4's sibling: the Shutdown arm set `running = false` ahead of its final `saveAll(sync = true)`. A
     * synced record that came in that span met `postSync` false and was applied store-direct by the host's
     * applier — and the save that followed put the live state over it under a fresh stamp (which the listener
     * then pushes to the peer). The final save is held on a gate inside the window's own `saveState`; the record
     * comes then, through the host's applier (the shell while it runs, the store otherwise); the store must end
     * on the record. The wait is on the STORE, one evaluation.
     */
    @Test
    fun aSyncedRecordThatComesDuringTheFinalSaveIsKept(): Unit = runBlocking {
        val r = rig("damage-tol-final")
        val gate = CountDownLatch(1)
        try {
            val s = r.store.stamp("window.cw")
            val entered = CountDownLatch(1)
            r.win.holdNextSave(entered, gate)
            val stopping = r.scope.launch { r.shell.stop() }
            assertTrue(entered.await(20, TimeUnit.SECONDS), "the stop's final save is held inside the window's state")
            if (!r.shell.postSync("window.cw", blob(4), s + 500)) r.store.tryApplyRemote("window.cw", blob(4), s + 500)
            gate.countDown()
            stopping.join()
            awaitTrue("the record that came during the final save met the store and stayed") { r.store.get("window.cw") == blob(4) }
            assertEquals(s + 500, r.store.stamp("window.cw"), "the peer's stamp is kept")
        } finally {
            gate.countDown()
            r.scope.cancel()
            r.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aSyncedRecordQueuedBehindTheShutdownIsStored(): Unit = runBlocking {
        val r = rig("damage-tol-behind")
        val gate = CountDownLatch(1)
        try {
            val s = r.store.stamp("window.cw")
            val entered = CountDownLatch(1)
            r.win.ctx!!.runOnShell { entered.countDown(); gate.await() }
            assertTrue(entered.await(20, TimeUnit.SECONDS), "the loop took the holding action")
            val stopping = r.scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { r.shell.stop() }
            assertTrue(r.shell.postSync("window.cw", blob(4), s + 500), "running is still true: the record is the shell's")
            gate.countDown()
            stopping.join()
            awaitTrue("the record queued behind the Shutdown met the store") { r.store.get("window.cw") == blob(4) }
            assertEquals(s + 500, r.store.stamp("window.cw"), "the peer's stamp is kept")
        } finally {
            gate.countDown()
            r.scope.cancel()
            r.tmp.toFile().deleteRecursively()
        }
    }
}
