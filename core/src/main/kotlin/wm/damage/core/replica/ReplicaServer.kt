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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
 * Per client, one sender thread builds each panel frame AT SEND TIME from
 * the live mirror against what that client last received, so a slow viewer
 * gets the latest content rather than every intermediate frame (memory per
 * client is bounded); a fresh or re-attached client gets both full panels.
 * The status is coalesced the same way. Fragmented WebSocket messages are
 * not accepted (the page never sends them); a client that does gets a loud
 * close.
 */
class ReplicaServer(
    private val port: Int,
    private val token: String,
    /** The mirror to stream — a provider, because the host may rebuild its
     *  stack (and mirror) under a running page. Re-checked every second. */
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
            // once a second: follow the mirror provider (a rebuilt stack) and
            // send the status; a client whose socket is gone shows up as a
            // send failure on its own sender and is dropped loudly
            while (running) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                val st = status()
                for (c in clients) { c.attach(); c.sendStatus(st) }
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
                if (path != "/favicon.ico")
                    Log.w("replica", "rejected ${sock.inetAddress}: bad or missing token on $path")
                reply(out, 403, "text/plain", "token required: open the page from the program's printed link"); return
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
        /** Work for the sender: an Arm (a panel to rebuild and send), STATUS, or END. */
        private val queue = LinkedBlockingQueue<Any>()
        private val stride = (Geometry.PANEL_W + 1) / 2
        private val lastSent = mapOf(Arm.LEFT to ByteArray(stride * Geometry.PANEL_H), Arm.RIGHT to ByteArray(stride * Geometry.PANEL_H))
        /** A panel token is queued at most once per arm until the sender takes it. */
        private val dirtyQueued = mapOf(Arm.LEFT to AtomicBoolean(false), Arm.RIGHT to AtomicBoolean(false))
        private val fullNext = mapOf(Arm.LEFT to AtomicBoolean(true), Arm.RIGHT to AtomicBoolean(true))
        private val pendingStatus = AtomicReference<Status?>(null)
        private val statusQueued = AtomicBoolean(false)
        @Volatile private var open = true
        @Volatile private var source: LensPanels? = null
        private val listener = LensPanels.LensListener { arm -> markDirty(arm) }
        private val sender = Thread({
            try {
                while (open) {
                    when (val w = queue.take()) {
                        END -> break
                        STATUS -> {
                            statusQueued.set(false)
                            pendingStatus.getAndSet(null)?.let { write(frame(OP_TEXT, statusJson(it))) }
                        }
                        is Arm -> {
                            dirtyQueued.getValue(w).set(false)
                            buildPanel(w)?.let { write(frame(OP_BINARY, it)) }
                        }
                        is ByteArray -> write(w)          // a pre-framed control frame (pong)
                    }
                }
            } catch (e: Exception) {
                if (open) Log.w("replica", "send to ${sock.inetAddress} failed: ${e.message}")
                open = false
                try { sock.close() } catch (c: Exception) { /* closing */ }
            }
        }, "replica-sender").apply { isDaemon = true }

        private fun write(f: ByteArray) {
            synchronized(out) { out.write(f); out.flush() }
        }

        fun run() {
            sender.start()
            attach()
            sendStatus(status())
            while (open) {
                val f = readFrame(inp) ?: break
                when (f.opcode) {
                    OP_TEXT -> handleText(String(f.payload, Charsets.UTF_8))
                    OP_PING -> queue.put(frame(OP_PONG, f.payload))
                    OP_CLOSE -> {
                        // echo the close synchronously so the browser sees a clean 1000
                        try { write(frame(OP_CLOSE, f.payload.copyOfRange(0, minOf(2, f.payload.size)))) } catch (e: Exception) { /* closing */ }
                        break
                    }
                    OP_PONG -> {}
                    OP_BINARY -> Log.w("replica", "binary frame (${f.payload.size} B) from ${sock.inetAddress} ignored — the page sends text only")
                    else -> { Log.w("replica", "unsupported frame opcode ${f.opcode} — closing"); break }
                }
            }
        }

        /** Follow the provider: (re)subscribe when the mirror object changes
         *  and send both FULL panels on every (re)subscription — a fresh
         *  client, a reconnect or a host rebuild must never keep stale rows. */
        @Synchronized
        fun attach() {
            if (!open) return          // the status thread may still hold a closed client
            val p = panels()
            if (p === source) return
            source?.removeListener(listener)
            source = p
            if (p != null) {
                p.addListener(listener)
                for (arm in Arm.entries) { fullNext.getValue(arm).set(true); markDirty(arm) }
            }
        }

        private fun markDirty(arm: Arm) {
            if (!open) return
            if (dirtyQueued.getValue(arm).compareAndSet(false, true)) queue.put(arm)
        }

        /** The frame for [arm] as of NOW: the changed row range against what
         *  this client last received (the whole panel after an attach). The
         *  bytes recorded as sent are exactly the bytes queued. */
        private fun buildPanel(arm: Arm): ByteArray? {
            val p = source ?: return null
            val now = p.panel(arm)
            val last = lastSent.getValue(arm)
            synchronized(last) {
                var first = -1
                var lastRow = -1
                if (fullNext.getValue(arm).getAndSet(false)) {
                    first = 0; lastRow = Geometry.PANEL_H - 1
                } else {
                    for (y in 0 until Geometry.PANEL_H) {
                        val o = y * stride
                        var same = true
                        for (i in 0 until stride) if (now[o + i] != last[o + i]) { same = false; break }
                        if (!same) { if (first < 0) first = y; lastRow = y }
                    }
                }
                if (first < 0) return null
                val rows = lastRow - first + 1
                val body = ByteArray(5 + rows * stride)
                body[0] = (if (arm == Arm.LEFT) 0 else 1).toByte()
                body[1] = (first and 0xFF).toByte(); body[2] = (first shr 8).toByte()
                body[3] = (rows and 0xFF).toByte(); body[4] = (rows shr 8).toByte()
                System.arraycopy(now, first * stride, body, 5, rows * stride)
                System.arraycopy(body, 5, last, first * stride, rows * stride)
                return body
            }
        }

        fun sendStatus(st: Status) {
            if (!open) return
            pendingStatus.set(st)
            if (statusQueued.compareAndSet(false, true)) queue.put(STATUS)
        }

        private fun statusJson(st: Status): ByteArray = buildJsonObject {
            put("t", "status"); put("transport", st.transport); put("connected", st.connected)
            put("started", st.started); put("lease", st.leaseHeld); put("ackMs", st.ackMs)
            put("bps", st.bytesPerSec); put("driver", st.driver); put("note", st.note)
            put("clients", clients.size)
        }.toString().toByteArray(Charsets.UTF_8)

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
            synchronized(this) {
                source?.removeListener(listener)
                source = null
            }
            queue.offer(END)
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
        private val STATUS = Any()
        private val END = Any()

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
