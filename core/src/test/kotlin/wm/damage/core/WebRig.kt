package wm.damage.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.ActionLevels
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.DevicesCore
import wm.damage.core.app.Levels
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side
import wm.damage.core.web.Access
import wm.damage.core.web.Blob
import wm.damage.core.web.BlobSource
import wm.damage.core.web.Bundle
import wm.damage.core.web.Devices
import wm.damage.core.web.WebServer

/*
 * Build S1-1, package P6: the rig the web listener's tests share — P1's [RecordRig] host (the made-up window `t`), a
 * device table in a temp directory, an [AppNet] serving it, and a real [WebServer] bound to loopback port 0 (never
 * another interface, never the service's port, never the real data directory). [Page] is a browser's WebSocket written
 * by hand; [http] one request. The fixture's made-up values are what the no-secret-in-the-log pins search for.
 */
internal class WebRig(
    val blobs: BlobSource = BlobSource { null },
    reach: ReachGate = ReachGate(Side.PC),
    val rec: RecordRig = RecordRig(),
    val host: AppHost = rec.opened(),
    /** The names `Host` may carry (2.4's pin leaves the loopback literal out, so only the arriving address admits it). */
    hosts: List<String> = listOf("localhost", "127.0.0.1"),
    /** S1-2 Q2: the window `t`'s actions that go into an outside program ([AppNet]'s `isProgram`). */
    program: Set<String> = emptySet(),
    /** S1-2 Q2: the lane's ping pacing (a viewer's interval is never shorter). */
    pingEveryMs: Long = wm.damage.core.app.Viewers.PING_EVERY_MS,
    /** S1-2 Q2: the upload route's state; null = none. */
    val uploads: wm.damage.core.web.Uploads? = null,
) : AutoCloseable {
    val dir: Path = Files.createTempDirectory("damage-web")
    val devices = Devices(dir.resolve(Devices.FILE), clock = { rec.now }).load()
    val core = DevicesCore(devices)
    val net = AppNet(host, mapOf("t" to 1), clock = { rec.now }, levels = TEST_LEVELS,
        minLevel = { if (it == "t") Levels.VIEW else Levels.FULL }, devices = core,
        isProgram = { w, a -> w == "t" && a in program }, pingEveryMs = pingEveryMs)
    val bundle = Bundle(mapOf("index.html" to "<!doctype html><p>fixture</p>".toByteArray(), "app.js" to "export {}".toByteArray()))
    val server = WebServer(0, reach, Access(hosts), devices, net, bundle, blobs,
        bindAddress = InetAddress.getLoopbackAddress(), clock = { rec.now }, uploads = uploads).also { it.start() }
    val port: Int get() = server.boundPort
    val origin: String get() = "http://127.0.0.1:$port"

    /** One waiting page's side, recorded. */
    class Waiting : Devices.Waiting {
        val claims = LinkedBlockingQueue<String>()
        val ends = LinkedBlockingQueue<String>()
        override fun approved(claim: String) { claims.put(claim) }
        override fun ended(reason: String) { ends.put(reason) }
    }

    /** A device approved at [level] in process (request → approve → redeem): its row and its secret. */
    fun approved(level: String, name: String = "fixture page", replaces: String? = null): Pair<Devices.Row, String> {
        val w = Waiting()
        val p = devices.request("127.0.0.1", null, name, "browser", replaces, w)
        assertEquals(null, devices.approve(p.code, level, "local"))
        val r = devices.redeem(w.claims.poll(10, TimeUnit.SECONDS)!!)!!
        return r.row to r.secret
    }

    override fun close() {
        server.close()
        net.close()
        rec.cleanUp()
        dir.toFile().deleteRecursively()
    }

    companion object {
        /** The window `t`'s levels: `place` (and S1-2 Q2's program op `peek`) is view; `wipe` (Q2) full; every other
         *  action is control. */
        val TEST_LEVELS = ActionLevels { w, a ->
            if (w != "t") null else when (a) { "place", "peek" -> Levels.VIEW; "wipe" -> Levels.FULL; else -> Levels.CONTROL }
        }

        fun blob(type: String, bytes: ByteArray, min: String = Levels.VIEW, name: String = "f") = Blob(type, bytes, min, name)
    }
}

/** One HTTP answer: its status, its headers (names lower-cased, every value kept), its body. */
internal class Resp(val code: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    fun header(k: String): String? = headers[k.lowercase()]?.firstOrNull()
    val text: String get() = String(body, Charsets.UTF_8)
}

/** One request on its own connection; [headers] beyond `Host` (default: the rig's loopback name and port). */
internal fun http(port: Int, method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
                  host: String = "127.0.0.1:$port"): Resp {
    Socket(InetAddress.getLoopbackAddress(), port).use { s ->
        s.soTimeout = 10_000                                   // a test's own bound on a stalled answer
        val out = s.getOutputStream()
        val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: $host\r\n")
        for ((k, v) in headers) sb.append("$k: $v\r\n")
        if (body != null) sb.append("Content-Length: ${body.size}\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        body?.let { out.write(it) }
        out.flush()
        val inp = DataInputStream(s.getInputStream().buffered())
        return readResp(inp)
    }
}

internal fun readResp(inp: DataInputStream): Resp {
    val status = line(inp) ?: throw AssertionError("no answer")
    val code = status.split(' ')[1].toInt()
    val headers = LinkedHashMap<String, MutableList<String>>()
    while (true) {
        val l = line(inp) ?: break
        if (l.isEmpty()) break
        val i = l.indexOf(':')
        headers.getOrPut(l.substring(0, i).trim().lowercase()) { ArrayList() }.add(l.substring(i + 1).trim())
    }
    val n = headers["content-length"]?.firstOrNull()?.toInt()
    val body = if (n != null) ByteArray(n).also { inp.readFully(it) } else ByteArray(0)
    return Resp(code, headers, body)
}

private fun line(inp: DataInputStream): String? {
    val b = ByteArrayOutputStream()
    while (true) {
        val c = inp.read()
        if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.ISO_8859_1)
        if (c == '\n'.code) return b.toString(Charsets.ISO_8859_1).trimEnd('\r')
        b.write(c)
    }
}

/** A browser page's WebSocket written by hand: the upgrade (with [cookie] and [origin] when given), then every text
 *  frame the PC sends into [frames]. [upgrade] holds the answer to the upgrade; a refused one opens no socket. */
internal class Page(port: Int, cookie: String? = null, origin: String? = "http://127.0.0.1:$port", host: String = "127.0.0.1:$port") : AutoCloseable {
    val sock = Socket(InetAddress.getLoopbackAddress(), port)
    private val out = sock.getOutputStream()
    private val inp = DataInputStream(sock.getInputStream().buffered())
    val frames = LinkedBlockingQueue<JsonObject>()
    val upgrade: Resp
    @Volatile var closed = false

    init {
        val sb = StringBuilder("GET /ws HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n")
        cookie?.let { sb.append("Cookie: ${Access.COOKIE}=$it\r\n") }
        origin?.let { sb.append("Origin: $it\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
        upgrade = readResp(inp)
        if (upgrade.code == 101) Thread({
            try {
                while (true) {
                    val b0 = inp.read(); if (b0 < 0) break
                    val b1 = inp.read(); if (b1 < 0) break
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) len = inp.readUnsignedShort().toLong() else if (len == 127L) len = inp.readLong()
                    val p = ByteArray(len.toInt()).also { inp.readFully(it) }
                    if (b0 and 0x0F == 1) frames.put(AppWire.parse(String(p, Charsets.UTF_8)))
                    if (b0 and 0x0F == 8) break
                }
            } catch (e: Exception) {
                // the socket ended under the read: [closed] says so
            }
            closed = true
        }, "test-page").apply { isDaemon = true }.start()
    }

    fun send(o: JsonObject) {
        val p = AppWire.encode(o)
        val mask = byteArrayOf(7, 1, 3, 9)
        synchronized(out) {
            out.write(0x81)
            when {
                p.size < 126 -> out.write(0x80 or p.size)
                else -> { out.write(0x80 or 126); out.write(p.size shr 8); out.write(p.size and 0xFF) }
            }
            out.write(mask)
            for (i in p.indices) out.write(p[i].toInt() xor mask[i and 3].toInt())
            out.flush()
        }
    }

    /** The lane's hello, as a page sends it (with its own name and, when it remembers one, the device it was). */
    fun hello(stream: String = "page-1", name: String? = null, replaces: String? = null) = send(buildJsonObject {
        put("t", "app"); put("proto", AppWire.PROTO); put("stream", stream)
        put("contracts", buildJsonObject { put("t", 1) })
        name?.let { put("name", it) }
        replaces?.let { put("replaces", it) }
    })

    fun next(): JsonObject = frames.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no frame within 10 s")

    fun next(t: String): JsonObject {
        val f = next()
        assertEquals(t, AppWire.type(f), "the next frame: $f")
        return f
    }

    /** Waits (a test's bound) until the PC has closed this socket. */
    fun awaitClosed() {
        val until = System.currentTimeMillis() + 10_000
        while (!closed && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(closed, "the PC closed the socket")
    }

    override fun close() {
        try { sock.close() } catch (e: Exception) { /* closing a test socket */ }
    }
}

/** Waits (a test's bound, 10 s) until [cond] holds. */
internal fun eventually(what: String, cond: () -> Boolean) {
    val until = System.currentTimeMillis() + 10_000
    while (!cond() && System.currentTimeMillis() < until) Thread.sleep(20)
    assertTrue(cond(), what)
}

/** Every recent log line of this process — the no-secret pins search them for the fixture's own (random) values. */
internal fun recentLog(): List<String> = wm.damage.core.util.Log.recent(5_000)
