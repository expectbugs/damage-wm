package wm.damage.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.shell.Persistence
import wm.damage.core.sync.RemoteSync
import wm.damage.core.sync.SyncPeer
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 (the spec's §4, "New PC, old APK" and "Old PC, new APK"): Reader's keys leave the sync
 * channel in both directions. An older build on the other side still speaks the old key set: the new phone ignores an
 * old PC's `window.reader*` records (applied, they would land in the glasses' local row), and the new PC ignores an
 * old phone's pushes and sends none — each said once per key per connection, not once per record. The other keys sync
 * as before. The older side is played by a raw socket speaking the sync channel's frames, on loopback port 0.
 */
class SyncCrossVersionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp = Files.createTempDirectory("damage-sync-cross")
    private val logged = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { _, tag, msg -> logged += "$tag: $msg" }
    private val closing = ArrayList<AutoCloseable>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        Log.addSink(sink)
    }

    @AfterTest
    fun tearDown() {
        Log.removeSink(sink)
        for (c in closing.asReversed()) try { c.close() } catch (e: Exception) { /* the rest still close */ }
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    private fun blob(n: Int) = buildJsonObject { put("n", n) }

    private fun DataOutputStream.frame(o: JsonObject) {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
        flush()
    }

    private fun DataInputStream.frame(): JsonObject {
        val b = ByteArray(readInt())
        readFully(b)
        return json.parseToJsonElement(b.toString(Charsets.UTF_8)).jsonObject
    }

    private fun records(vararg r: Pair<String, JsonObject>, at: Long) = buildJsonArray {
        for ((k, v) in r) add(buildJsonObject { put("k", k); put("v", v); put("t", at) })
    }

    @Test
    fun anOldPcsReaderRecordsAreIgnored(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        closing += server
        // the old PC: answers the handshake with its whole key set, Reader's included, then pushes one more
        val oldPc = Thread {
            try {
                val s = server.accept()
                closing += s
                val inp = DataInputStream(s.getInputStream().buffered())
                val out = DataOutputStream(s.getOutputStream().buffered())
                inp.frame()                                                       // the hello
                inp.frame()                                                       // the client's handshake
                out.frame(buildJsonObject {
                    put("t", "syncok"); put("clock", System.currentTimeMillis())
                    put("records", records("window.reader.book.x" to buildJsonObject { put("off", 5) },
                        "window.reader" to buildJsonObject { put("level", "BOOK") },
                        "window.feed" to blob(1), at = now))
                    put("want", buildJsonArray {})
                })
                out.frame(buildJsonObject {
                    put("t", "syncrec")
                    put("records", records("window.reader.book.x" to buildJsonObject { put("off", 6) },
                        "window.games" to blob(2), at = now + 1))
                })
                while (true) inp.frame()                                          // hold the connection
            } catch (e: Exception) { /* the test ended it */ }
        }.apply { isDaemon = true; start() }
        val store = Persistence(tmp.resolve("phone.json"))
        store.put("window.reader", buildJsonObject { put("level", "LIBRARY"); put("mine", true) })
        val client = RemoteSync("127.0.0.1", server.localPort, "tok", scope, SyncPeer(store), retryPacingMs = 200)
        closing += AutoCloseable { client.close() }
        readerAwait("the keys that sync arrived") { store.get("window.feed") == blob(1) && store.get("window.games") == blob(2) }
        assertNull(store.get("window.reader.book.x"), "an old PC's place is not applied into the phone's own row")
        assertEquals(buildJsonObject { put("level", "LIBRARY"); put("mine", true) }, store.get("window.reader"),
            "the glasses' local row is the phone's alone")
        val said = logged.filter { "does not sync any more" in it }
        assertEquals(2, said.size, "once per key per connection, not per record: $said")
        assertTrue(said.any { "'window.reader.book.x'" in it } && said.any { "'window.reader'" in it }, "$said")
        oldPc.interrupt()
    }

    @Test
    fun aNewPcIgnoresAnOldPhonesReaderKeys_andSendsNone(): Unit = runBlocking {
        val storeA = Persistence(tmp.resolve("pc.json"))
        storeA.put("window.reader.book.z", buildJsonObject { put("off", 9) })
        storeA.put("window.reader", buildJsonObject { put("level", "BOOK") })
        storeA.put("window.feed", blob(1))
        val host = ContentHostServer(LocalContent(tmp), 0, "tok", sync = SyncPeer(storeA),
            bindAddress = InetAddress.getLoopbackAddress())
        host.start()
        closing += host
        // the old phone: hello, then a handshake whose stamps name Reader's keys as newer there
        val s = Socket("127.0.0.1", host.boundPort)
        closing += s
        val inp = DataInputStream(s.getInputStream().buffered())
        val out = DataOutputStream(s.getOutputStream().buffered())
        out.frame(buildJsonObject { put("t", "hello"); put("token", "tok"); put("proto", 1) })
        val later = System.currentTimeMillis() + 60_000
        out.frame(buildJsonObject {
            put("t", "sync"); put("clock", System.currentTimeMillis())
            put("stamps", buildJsonObject { put("window.reader.book.x", later); put("window.reader", later); put("window.tmux", later) })
        })
        val ok = inp.frame()
        assertEquals("syncok", ok["t"]!!.jsonPrimitive.content)
        val sent = ok["records"]!!.jsonArray.map { it.jsonObject["k"]!!.jsonPrimitive.content }
        val wanted = ok["want"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("window.feed" in sent, "the other keys sync as before: $sent")
        assertTrue(sent.none { it.startsWith("window.reader") }, "the new PC sends none of Reader's keys: $sent")
        assertEquals(listOf("window.tmux"), wanted, "and asks for none of them")
        // the old phone pushes a place twice, its row once, and a key that syncs
        val now = System.currentTimeMillis()
        out.frame(buildJsonObject { put("t", "syncrec"); put("records", records("window.reader.book.x" to buildJsonObject { put("off", 1) }, at = now)) })
        out.frame(buildJsonObject { put("t", "syncrec"); put("records", records("window.reader.book.x" to buildJsonObject { put("off", 2) }, at = now + 1)) })
        out.frame(buildJsonObject { put("t", "syncrec"); put("records", records("window.reader" to buildJsonObject { put("level", "X") },
            "window.tmux" to blob(3), at = now + 2)) })
        readerAwait("the key that syncs arrived") { storeA.get("window.tmux") == blob(3) }
        delay(100)
        assertNull(storeA.get("window.reader.book.x"))
        assertEquals(buildJsonObject { put("level", "BOOK") }, storeA.get("window.reader"))
        val said = logged.filter { "unsyncable key" in it }
        assertEquals(2, said.size, "once per key per connection: $said")
        assertFalse(said.any { "window.tmux" in it })
    }
}
