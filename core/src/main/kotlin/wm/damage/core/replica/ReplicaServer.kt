package wm.damage.core.replica

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.geom.Geometry
import wm.damage.core.transport.Arm
import wm.damage.core.transport.LensPanels
import wm.damage.core.util.Log
import wm.damage.core.wire.EvenHubMsg

/**
 * The browser replica (HANDOFF.md §8.2 "Replica page"): a dependency-free
 * HTTP/1.1 + WebSocket (RFC 6455) server that serves one self-contained page
 * and streams the transport's MIRROR to it — per-lens packed panels as dirty
 * row-range frames, exact to the firmware model, never Even's simulator —
 * plus a status line, and takes the page's mouse/keyboard as ring gestures.
 * Served by the desktop program and by the phone, token-gated like the seam.
 *
 *   GET /?token=T            → the page
 *   GET /ws?token=T          → WebSocket upgrade
 *   server → client binary   [arm u8][y0 u16 LE][rows u16 LE][rows × stride bytes]
 *   server → client text     {"t":"status", ...}
 *   client → server text     {"t":"input","ev":"tap|double|up|down|hold|release"}
 *
 * Fragmented WebSocket messages are not accepted (the page never sends them);
 * a client that does gets a loud close. One sender thread per client keeps
 * panel frames and status in order.
 */
class ReplicaServer(
    private val port: Int,
    private val token: String,
    /** The mirror to stream — a provider, because the host may rebuild its
     *  stack (and mirror) under a running page. */
    private val panels: () -> LensPanels?,
    private val status: () -> Status,
    private val onInput: (Int) -> Unit,
) : AutoCloseable {

    data class Status(
        val transport: String = "none",
        val connected: Boolean = false,
        val started: Boolean = false,
        val leaseHeld: Boolean = false,
        val ackMs: Int = 0,
        val bytesPerSec: Int = 0,
        val driver: String = "",
        val note: String = "",
    )

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val clients = ConcurrentHashMap.newKeySet<Client>()
    private val json = Json { ignoreUnknownKeys = true }

    val clientCount: Int get() = clients.size

    fun start() {
        running = true
        val s = ServerSocket(port)
        server = s
        Thread({
            Log.i("replica", "browser replica on :$port (token-gated)")
            while (running) {
                val sock = try { s.accept() } catch (e: Exception) {
                    if (running) Log.e("replica", "accept failed", e)
                    break
                }
                Thread({ serve(sock) }, "replica-${sock.inetAddress}").apply { isDaemon = true }.start()
            }
        }, "replica-server").apply { isDaemon = true }.start()
        Thread({
            // status once a second to every client; a client whose socket is
            // gone shows up as a send failure and is dropped loudly
            while (running) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                val st = status()
                for (c in clients) c.sendStatus(st)
            }
        }, "replica-status").apply { isDaemon = true }.start()
    }

    private fun serve(sock: Socket) {
        try {
            val inp = DataInputStream(sock.getInputStream().buffered())
            val out = sock.getOutputStream()
            val req = readRequest(inp) ?: return
            val (method, target) = req
            val path = target.substringBefore('?')
            val query = queryOf(target)
            if (method != "GET") { reply(out, 405, "text/plain", "GET only"); return }
            if (query["token"] != token) {
                Log.w("replica", "rejected ${sock.inetAddress}: bad or missing token on $path")
                reply(out, 403, "text/plain", "token required: open the page from the desktop's printed link"); return
            }
            when (path) {
                "/", "/index.html" -> reply(out, 200, "text/html; charset=utf-8", page())
                "/ws" -> {
                    val key = req.headers["sec-websocket-key"]
                    if (key == null || !req.headers["upgrade"].equals("websocket", true)) {
                        reply(out, 400, "text/plain", "websocket upgrade expected"); return
                    }
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    val c = Client(sock, inp, out)
                    clients.add(c)
                    try { c.run() } finally { clients.remove(c); c.close() }
                }
                else -> reply(out, 404, "text/plain", "not found")
            }
        } catch (e: EOFException) {
            // client closed
        } catch (e: Exception) {
            Log.w("replica", "session ${sock.inetAddress} ended: ${e.message}")
        } finally {
            try { sock.close() } catch (e: Exception) { /* closed */ }
        }
    }

    private class Request(val method: String, val target: String, val headers: Map<String, String>) {
        operator fun component1() = method
        operator fun component2() = target
    }

    private fun readRequest(inp: DataInputStream): Request? {
        val first = readLine(inp) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) throw IOException("bad request line '$first'")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(inp) ?: return null
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return Request(parts[0], parts[1], headers)
    }

    /** One ISO-8859-1 header line (CRLF-terminated); null at EOF. */
    private fun readLine(inp: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("header line too long")
        }
    }

    private fun queryOf(target: String): Map<String, String> {
        val q = target.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i < 0) null else URLDecoder.decode(kv.substring(0, i), "UTF-8") to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1))
        out.write(b)
        out.flush()
    }

    private fun page(): String {
        val res = ReplicaServer::class.java.getResourceAsStream("/wm/damage/core/replica/replica.html")
            ?: throw IllegalStateException("replica.html resource missing from the build")
        return BufferedReader(InputStreamReader(res, Charsets.UTF_8)).use { it.readText() }
    }

    /** One connected page. */
    private inner class Client(private val sock: Socket, private val inp: DataInputStream, private val out: OutputStream) {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val stride = (Geometry.PANEL_W + 1) / 2
        private val lastSent = mapOf(Arm.LEFT to ByteArray(stride * Geometry.PANEL_H), Arm.RIGHT to ByteArray(stride * Geometry.PANEL_H))
        @Volatile private var open = true
        private var source: LensPanels? = null
        private val listener = LensPanels.LensListener { arm -> pushPanel(arm) }
        private val sender = Thread({
            try {
                while (open) {
                    val f = queue.take()
                    if (f.isEmpty()) break
                    synchronized(out) { out.write(f); out.flush() }
                }
            } catch (e: Exception) {
                if (open) Log.w("replica", "send to ${sock.inetAddress} failed: ${e.message}")
                open = false
                try { sock.close() } catch (c: Exception) { /* closing */ }
            }
        }, "replica-sender").apply { isDaemon = true }

        fun run() {
            sender.start()
            attach()
            sendStatus(status())
            while (open) {
                val f = readFrame(inp) ?: break
                when (f.opcode) {
                    OP_TEXT -> handleText(String(f.payload, Charsets.UTF_8))
                    OP_PING -> queue.put(frame(OP_PONG, f.payload))
                    OP_CLOSE -> { queue.put(frame(OP_CLOSE, ByteArray(0))); break }
                    OP_PONG, OP_BINARY -> {}
                    else -> { Log.w("replica", "unsupported frame opcode ${f.opcode} — closing"); break }
                }
                attach()   // the host may have rebuilt its mirror meanwhile
            }
        }

        /** Follow the provider: (re)subscribe when the mirror object changes,
         *  and send both full panels on every (re)subscription. */
        private fun attach() {
            val p = panels()
            if (p === source) return
            source?.removeListener(listener)
            source = p
            if (p != null) {
                p.addListener(listener)
                for (arm in Arm.entries) { java.util.Arrays.fill(lastSent.getValue(arm), 0); pushPanel(arm) }
            }
        }

        private fun pushPanel(arm: Arm) {
            val p = source ?: return
            val now = p.panel(arm)
            val last = lastSent.getValue(arm)
            synchronized(last) {
                var first = -1
                var lastRow = -1
                for (y in 0 until Geometry.PANEL_H) {
                    val o = y * stride
                    var same = true
                    for (i in 0 until stride) if (now[o + i] != last[o + i]) { same = false; break }
                    if (!same) { if (first < 0) first = y; lastRow = y }
                }
                if (first < 0) return
                val rows = lastRow - first + 1
                val body = ByteArray(5 + rows * stride)
                body[0] = (if (arm == Arm.LEFT) 0 else 1).toByte()
                body[1] = (first and 0xFF).toByte(); body[2] = (first shr 8).toByte()
                body[3] = (rows and 0xFF).toByte(); body[4] = (rows shr 8).toByte()
                System.arraycopy(now, first * stride, body, 5, rows * stride)
                System.arraycopy(now, first * stride, last, first * stride, rows * stride)
                queue.put(frame(OP_BINARY, body))
            }
        }

        fun sendStatus(st: Status) {
            if (!open) return
            val o = buildJsonObject {
                put("t", "status"); put("transport", st.transport); put("connected", st.connected)
                put("started", st.started); put("lease", st.leaseHeld); put("ackMs", st.ackMs)
                put("bps", st.bytesPerSec); put("driver", st.driver); put("note", st.note)
                put("clients", clients.size)
            }
            queue.put(frame(OP_TEXT, o.toString().toByteArray(Charsets.UTF_8)))
        }

        private fun handleText(s: String) {
            val o = try { json.parseToJsonElement(s).jsonObject } catch (e: Exception) {
                Log.w("replica", "malformed frame from ${sock.inetAddress}: ${e.message}"); return
            }
            when (o["t"]?.jsonPrimitive?.content) {
                "input" -> {
                    val ev = o["ev"]?.jsonPrimitive?.content
                    val type = gestureOf(ev)
                    if (type == null) Log.w("replica", "unknown input '$ev' ignored") else onInput(type)
                }
                "hb" -> {}
                else -> Log.w("replica", "unknown message ${o["t"]} ignored")
            }
        }

        fun close() {
            open = false
            source?.removeListener(listener)
            source = null
            queue.offer(ByteArray(0))
            try { sock.close() } catch (e: Exception) { /* closed */ }
        }
    }

    override fun close() {
        running = false
        server?.close()
        for (c in clients) c.close()
        clients.clear()
    }

    private class Frame(val opcode: Int, val payload: ByteArray)

    /** Read one client frame (masked per RFC 6455); null at EOF. Fragments
     *  (FIN=0 or continuation) are refused loudly. */
    private fun readFrame(inp: DataInputStream): Frame? {
        val b0 = inp.read(); if (b0 < 0) return null
        val b1 = inp.read(); if (b1 < 0) return null
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0F
        if (!fin || opcode == 0) throw IOException("fragmented websocket message (unsupported)")
        val masked = b1 and 0x80 != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = inp.readUnsignedShort().toLong()
        else if (len == 127L) len = inp.readLong()
        if (len < 0 || len > MAX_CLIENT_FRAME) throw IOException("client frame of $len B refused")
        if (!masked) throw IOException("unmasked client frame (RFC 6455 requires a mask)")
        val mask = ByteArray(4); inp.readFully(mask)
        val payload = ByteArray(len.toInt()); inp.readFully(payload)
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(opcode, payload)
    }

    companion object {
        const val OP_TEXT = 1
        const val OP_BINARY = 2
        const val OP_CLOSE = 8
        const val OP_PING = 9
        const val OP_PONG = 10
        private const val MAX_CLIENT_FRAME = 64 * 1024L
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** RFC 6455 §4.2.2: base64(SHA-1(key + GUID)). */
        fun acceptKey(key: String): String {
            val sha = MessageDigest.getInstance("SHA-1").digest((key.trim() + WS_GUID).toByteArray(Charsets.ISO_8859_1))
            return Base64.getEncoder().encodeToString(sha)
        }

        /** A server→client frame (unmasked), FIN set. */
        fun frame(opcode: Int, payload: ByteArray): ByteArray {
            val n = payload.size
            val header = when {
                n < 126 -> byteArrayOf((0x80 or opcode).toByte(), n.toByte())
                n < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
                else -> ByteArray(10).also {
                    it[0] = (0x80 or opcode).toByte(); it[1] = 127.toByte()
                    for (i in 0 until 8) it[2 + i] = (n.toLong() shr (8 * (7 - i))).toByte()
                }
            }
            return header + payload
        }

        fun gestureOf(ev: String?): Int? = when (ev) {
            "tap" -> EvenHubMsg.EV_CLICK
            "double" -> EvenHubMsg.EV_DOUBLE_CLICK
            "up" -> EvenHubMsg.EV_SCROLL_TOP
            "down" -> EvenHubMsg.EV_SCROLL_BOTTOM
            "hold" -> EvenHubMsg.EV_RING_LONG_PRESS
            "release" -> EvenHubMsg.EV_RING_LONG_PRESS_RELEASE
            else -> null
        }
    }
}
