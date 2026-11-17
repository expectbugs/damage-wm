package wm.damage.core.web

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Levels
import wm.damage.core.app.Outbox
import wm.damage.core.app.Peer
import wm.damage.core.net.ReachGate
import wm.damage.core.replica.HttpRequest
import wm.damage.core.replica.HttpWs
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (`CONTRACT.md` §9.2, §9.4, §9.5, §10; the spec's §6; dec. B.3, A.5): the PC's web interface
 * listener — HTTP/1.1 and WebSocket on `webPort`, one request per connection, built on the replica's codec
 * ([HttpWs]). On every accepted socket, in order:
 *  1. the reach gate ([ReachGate], the PC's, shared with every listener) BEFORE a byte is read; a refusal is closed
 *     unread and said by the gate;
 *  2. [Access]: `Host`, then `Origin` when present; a refusal is answered 403 and said once per source per minute;
 *  3. the device secret from the cookie, read once per request; the device's level with it.
 * Then the routes:
 *  - `GET /` and the bundle's files: the page bundle ([Bundle]); with no device too. A visit with a device re-sets its
 *    cookie (C§10.3: never reaching the browser's 400-day cap).
 *  - `GET /ws`: the WebSocket. With a device it carries the `app` lane ([AppNet.serve], the device's id and level); with
 *    none it is the pairing socket only: the page's hello gets `pending{code}`, then `approved{claim}` or
 *    `end{denied}`; its row lives while the socket is open ([Devices.request], [Devices.drop]).
 *  - `POST /pair/claim`: the claim, redeemed once ([Devices.redeem]); the answer sets the cookie, `HttpOnly;
 *    SameSite=Strict`, no `Secure` (plain HTTP on his networks, accepted on his word).
 *  - `GET /blob/<id>`: a device's blob within its level ([Blobs]).
 *  - P7: `GET /w/…` (an address, `CONTRACT.md` §5) answers the bundle's page, so a reload or a link lands on the
 *    client, which reads the address; `GET /font/<name>`: one of the glasses' faces (the glass theme), for an approved
 *    device ([FontSource]).
 * With no approved device it therefore serves only the bundle and the pairing socket. Security in proportion (dec.
 * B.3): no captcha, no rate limiter beyond the code's own, no second factor.
 *
 * Build S1-2, package Q2 (the spec's §2.4): `GET /blob/<id>` through the blob registry ([BlobSources]), `?r=` a
 * rendition its source makes, `?dl=1` a download whatever the type; `PUT /blob/upload/<id>` an upload's body, for the
 * device the id was minted for ([Uploads]).
 */
/** P7: the file of one of the glasses' faces by its name in [WebServer.FONTS], or null when this host has none. */
fun interface FontSource {
    fun file(name: String): java.nio.file.Path?
}

class WebServer(
    private val port: Int,
    private val reach: ReachGate,
    private val access: Access,
    private val devices: Devices,
    private val app: AppNet,
    private val bundle: Bundle,
    private val blobs: BlobSource,
    /** The tailnet's name for an arriving address (the status `PhonePresence` reads), or null; never a condition. */
    private val tailnetName: (InetAddress) -> String? = { null },
    /** P7: the glasses' faces by name ([FONTS]); none on a host without them. */
    private val fonts: FontSource = FontSource { null },
    /** The address to bind; null = every address (the reach gate judges each arrival). Tests bind loopback. */
    private val bindAddress: InetAddress? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** S1-2 Q2: the uploads a window's `upload` op minted; null = no upload route. */
    private val uploads: Uploads? = null,
) : AutoCloseable {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    /** Refusal lines said, by source and reason: one per minute. */
    private val said = ConcurrentHashMap<String, Long>()

    /** The bound port (a test binds 0), or the configured one before [start]. */
    val boundPort: Int get() = server?.localPort ?: port

    /** The sockets the listener holds now (the keepalive pin reads them). */
    internal fun openSockets(): List<Socket> = open.toList()

    fun start() {
        val s = ServerSocket(port, 50, bindAddress)
        server = s
        running = true
        Thread({
            Log.i(TAG, "web interface on :${s.localPort} (the device secret on every data path; ${bundle.files.size} bundle file(s))")
            while (running) {
                val sock = try { s.accept() } catch (e: Exception) {
                    if (running) Log.e(TAG, "accept failed", e)
                    break
                }
                // C§10.9: the reach gate on the accepted socket, before a byte is read; a refusal is closed unread
                if (!reach.admit(sock, "web interface")) { close(sock); continue }
                // fix round 1, ruling 2.3 (4): TCP keepalive on every socket the listener holds — a plain request,
                // the pairing socket and the lane alike — so a peer gone without a FIN is found by the kernel and its
                // thread (and a waiting page's row) goes; TCP liveness, not a timeout on any radio, render or input path
                try { sock.keepAlive = true } catch (e: Exception) {
                    Log.w(TAG, "keepalive not set on ${sock.inetAddress?.hostAddress}: ${e.message}")
                }
                open.add(sock)
                Thread({
                    try { serve(sock) } finally { open.remove(sock); close(sock) }
                }, "web-${sock.inetAddress?.hostAddress}").apply { isDaemon = true }.start()
            }
        }, "web-server").apply { isDaemon = true }.start()
    }

    override fun close() {
        running = false
        try { server?.close() } catch (e: Exception) { Log.w(TAG, "closing the listener: ${e.message}") }
        for (s in open) close(s)
    }

    private fun serve(sock: Socket) {
        try {
            val inp = DataInputStream(sock.getInputStream().buffered())
            val out = sock.getOutputStream().buffered()
            val req = HttpWs.readRequest(inp) ?: return
            val why = access.refusal(req, sock, boundPort)
            if (why != null) {
                refused(sock, why)
                HttpWs.reply(out, 403, "text/plain; charset=utf-8", "refused: $why")
                return
            }
            // C§10.5: who the request is, and at what level, read once
            val secret = Access.cookie(req)
            val device = devices.authenticate(secret)
            val path = req.path
            when {
                req.method == "GET" && path == WS_PATH -> socket(sock, inp, out, req, device)
                req.method == "POST" && path == CLAIM_PATH -> claim(sock, inp, out, req)
                req.method == "PUT" && path.startsWith(UPLOAD_PREFIX) -> upload(sock, inp, out, req, device, path.removePrefix(UPLOAD_PREFIX))
                req.method == "GET" && path.startsWith(BLOB_PREFIX) -> blob(sock, out, req, device, path.removePrefix(BLOB_PREFIX))
                req.method == "GET" && path.startsWith(FONT_PREFIX) -> font(sock, out, device, path.removePrefix(FONT_PREFIX))
                // P7: an address is the client's to read; the server answers its page
                req.method == "GET" && (path == ADDRESS_ROOT || path.startsWith("$ADDRESS_ROOT/")) ->
                    page(out, req, "/", device?.let { secret })
                req.method == "GET" -> page(out, req, path, device?.let { secret })
                else -> HttpWs.reply(out, 405, "text/plain", "GET, the claim's POST and an upload's PUT only")
            }
        } catch (e: EOFException) {
            // the client closed
        } catch (e: Exception) {
            Log.w(TAG, "request from ${sock.inetAddress?.hostAddress} ended: ${e::class.simpleName}: ${e.message}")
        } catch (e: Throwable) {
            // as the replica (§71): an Error here drops this request, never the process
            Log.e(TAG, "request from ${sock.inetAddress?.hostAddress} failed", e)
        }
    }

    // ---- routes ----

    private fun page(out: OutputStream, req: HttpRequest, path: String, secret: String?) {
        val name = path.removePrefix("/").ifEmpty { Bundle.INDEX }
        val b = bundle.file(path)
        if (b == null) {
            HttpWs.reply(out, 404, "text/plain", "not found")
            return
        }
        val h = buildList {
            add("Content-Security-Policy" to Bundle.csp(req.headers["host"] ?: ""))
            add("X-Content-Type-Options" to "nosniff")
            add("Referrer-Policy" to "no-referrer")
            add("Cache-Control" to "no-cache")
            // C§10.3: re-set on each visit, so it never reaches the browser's 400-day cap
            secret?.let { add("Set-Cookie" to Access.setCookie(it)) }
        }
        HttpWs.replyBytes(out, 200, Bundle.typeOf(name), b, h)
    }

    private fun blob(sock: Socket, out: OutputStream, req: HttpRequest, device: Devices.Row?, id: String) {
        // the device's level, read once for this request; none = no approved device
        val level = device?.level
        if (level == null) {
            refused(sock, "a blob without an approved device")
            HttpWs.reply(out, 403, "text/plain", "this device is not approved")
            return
        }
        // S1-2 Q2: `?r=` a rendition the blob's source makes; `?dl=1` a download whatever the type
        val q = HttpWs.queryOf(req.target)
        val r = q["r"]
        // fix round 1, ruling 2.3 (8): a blob that cannot be served is answered with its status and said, never an
        // ended request with no answer
        val b = try {
            if (r == null) blobs.resolve(id) else blobs.rendition(id, r)
        } catch (e: BlobTooLarge) {
            Log.w(TAG, "blob $id refused: ${e.message}")
            HttpWs.reply(out, 413, "text/plain; charset=utf-8", e.message ?: "this blob is past what the listener serves")
            return
        } catch (e: Exception) {
            val why = "blob $id could not be read (${e::class.simpleName}: ${e.message})"
            Log.e(TAG, why)
            HttpWs.reply(out, 500, "text/plain; charset=utf-8", why)
            return
        }
        if (b == null) {
            HttpWs.reply(out, 404, "text/plain", if (r == null) "no such blob" else "no rendition '$r' of this blob")
            return
        }
        // C§10.5: the blob's minimum level against the device's, at apply time
        Levels.refusal(level, b.minLevel, "this blob")?.let {
            HttpWs.reply(out, 403, "text/plain", it.reason)
            return
        }
        Blobs.serve(out, req, b, download = q["dl"] == "1")
    }

    /** S1-2 Q2: an upload's body, for the device its id was minted for; answered with the reason whatever happens. */
    private fun upload(sock: Socket, inp: DataInputStream, out: OutputStream, req: HttpRequest, device: Devices.Row?, raw: String) {
        if (device == null) {
            refused(sock, "an upload without an approved device")
            HttpWs.reply(out, 403, "text/plain", "this device is not approved")
            return
        }
        val u = uploads
        if (u == null) {
            HttpWs.reply(out, 404, "text/plain; charset=utf-8", "this PC takes no uploads on this listener")
            return
        }
        val id = try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (e: IllegalArgumentException) { raw }
        val len = req.headers["content-length"]?.let { it.trim().toLongOrNull() ?: -1L }
        val r = u.receive(id, device.id, len?.takeIf { it >= 0 }, inp)
        HttpWs.reply(out, r.code, if (r.code == 201) "application/json" else "text/plain; charset=utf-8", r.text)
        if (r.bodyLeft) {
            // refused before its body: the answer goes out whole, then what the uploader still sends is read and
            // dropped until it closes (a close with unread bytes would reset the socket under the answer); a body
            // that stalls holds this thread until TCP keepalive ends it, as any request's would
            sock.shutdownOutput()
            val buf = ByteArray(64 * 1024)
            while (inp.read(buf) >= 0) { /* dropped: the upload was refused */ }
        }
    }

    /** P7: one of the glasses' faces, by its fixed name, for an approved device (a data path wants the secret). */
    private fun font(sock: Socket, out: OutputStream, device: Devices.Row?, name: String) {
        if (device == null) {
            refused(sock, "a font without an approved device")
            HttpWs.reply(out, 403, "text/plain", "this device is not approved")
            return
        }
        val file = if (name in FONTS) fonts.file(name) else null
        val bytes = try {
            file?.let { java.nio.file.Files.readAllBytes(it) }
        } catch (e: IOException) {
            Log.w(TAG, "the face $name could not be read from $file (${e::class.simpleName}: ${e.message})")
            null
        }
        if (bytes == null) {
            HttpWs.reply(out, 404, "text/plain", "no face '$name' on this PC")
            return
        }
        val type = if (file.toString().lowercase().endsWith(".otf")) "font/otf" else "font/ttf"
        // a face never changes under its name within a run: the page keeps it a day
        HttpWs.replyBytes(out, 200, type, bytes, listOf("X-Content-Type-Options" to "nosniff",
            "Cache-Control" to "private, max-age=86400"))
    }

    private fun claim(sock: Socket, inp: DataInputStream, out: OutputStream, req: HttpRequest) {
        // the claim is taken from the PC's own page only: a browser always sends Origin on a POST
        if (req.headers["origin"] == null) {
            refused(sock, "a claim without an Origin")
            HttpWs.reply(out, 403, "text/plain", "a claim is taken from the PC's own page only")
            return
        }
        val len = req.headers["content-length"]?.toIntOrNull()
        if (len == null || len !in 1..MAX_CLAIM_BODY) {
            HttpWs.reply(out, 400, "text/plain", "a claim is one short body")
            return
        }
        val body = ByteArray(len).also { inp.readFully(it) }
        val r = try {
            devices.redeem(String(body, Charsets.US_ASCII).trim())
        } catch (e: IllegalStateException) {
            Log.e(TAG, e.message ?: "the device table could not be written")
            HttpWs.reply(out, 500, "text/plain", e.message ?: "the device table could not be written")
            return
        }
        if (r == null) {
            refused(sock, "a claim that is not a live one")
            HttpWs.reply(out, 403, "text/plain", "this approval is no longer valid; the page asks again")
            return
        }
        val answer = buildJsonObject { put("device", r.row.id); put("level", r.row.level) }.toString()
        HttpWs.reply(out, 200, "application/json", answer, listOf("Set-Cookie" to Access.setCookie(r.secret),
            "Cache-Control" to "no-store"))
    }

    private fun socket(sock: Socket, inp: DataInputStream, out: OutputStream, req: HttpRequest, device: Devices.Row?) {
        val key = req.headers["sec-websocket-key"]
        if (key == null || !req.headers["upgrade"].equals("websocket", true)) {
            HttpWs.reply(out, 400, "text/plain", "websocket upgrade expected")
            return
        }
        HttpWs.switching(out, key)
        val ws = WsIo(sock, inp, out)
        val hello = ws.read()
        // the `app` lane only as the device the secret names, at its level (read once, at the upgrade)
        val peer = device?.let { Peer(it.id, it.level) }
        if (peer != null) {
            app.serve(sock, ws, hello.toString(), peer, bundle.info())
        } else {
            pair(sock, ws, hello)
        }
    }

    /** The pairing socket (dec. A.5): the waiting page's row lives exactly as long as this socket. */
    private fun pair(sock: Socket, ws: WsIo, hello: JsonObject) {
        val addr = sock.inetAddress
        val outbox = Outbox("pair-${addr?.hostAddress}", PAIR_OUTBOX, write = { ws.write(it) }, close = { close(sock) })
        val waiting = object : Devices.Waiting {
            override fun approved(claim: String) { outbox.offer(AppWire.encode(AppWire.approved(claim))) }
            override fun ended(reason: String) = outbox.finish(AppWire.encode(AppWire.end(reason)))
        }
        outbox.start()
        fun s(k: String) = (hello[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val p = devices.request(addr?.hostAddress ?: "an unknown address", addr?.let { tailnetName(it) },
            s("name") ?: "a browser", "browser", s("replaces"), waiting)
        try {
            outbox.offer(AppWire.encode(AppWire.pending(p.code)))
            val unknown = HashSet<String>()
            while (true) {
                val f = ws.read()
                when (val t = AppWire.type(f)) {
                    "ping" -> outbox.offer(AppWire.encode(AppWire.pong(AppWire.num(f, "k"))))
                    "end" -> return
                    else -> if (unknown.add(t)) Log.w(TAG, "a waiting page sent '$t'; only ping and end are taken before approval")
                }
            }
        } catch (e: EOFException) {
            // the page closed
        } finally {
            devices.drop(p)
            outbox.stop()
            close(sock)
        }
    }

    private fun refused(sock: Socket, why: String) {
        val key = "${sock.inetAddress?.hostAddress} $why"
        val now = clock()
        val last = said[key]
        if (last != null && now - last < ReachGate.PACE_MS) return
        said[key] = now
        if (said.size > ReachGate.PRUNE_PAST) said.entries.removeIf { now - it.value >= ReachGate.PACE_MS }
        Log.w(TAG, "refused ${sock.inetAddress?.hostAddress}: $why")
    }

    /** The lane's frames over a WebSocket: text frames of one JSON object; pings answered here; a close is the end. */
    class WsIo(private val sock: Socket, private val inp: DataInputStream, private val out: OutputStream) : AppNet.LaneIo {
        override fun read(): JsonObject {
            while (true) {
                val f = HttpWs.readFrame(inp, MAX_CLIENT_FRAME) ?: throw EOFException("the page closed")
                when (f.opcode) {
                    HttpWs.OP_TEXT -> return AppWire.parse(String(f.payload, Charsets.UTF_8))
                    HttpWs.OP_PING -> send(HttpWs.frame(HttpWs.OP_PONG, f.payload))
                    HttpWs.OP_PONG -> {}
                    HttpWs.OP_CLOSE -> {
                        try { send(HttpWs.frame(HttpWs.OP_CLOSE, f.payload.copyOfRange(0, minOf(2, f.payload.size)))) }
                        catch (e: IOException) { /* the page has gone already; the close was its own */ }
                        throw EOFException("the page closed the socket")
                    }
                    else -> throw IOException("websocket opcode ${f.opcode} is not taken (text frames only)")
                }
            }
        }

        override fun write(frame: ByteArray) = send(HttpWs.frame(HttpWs.OP_TEXT, frame))

        private fun send(b: ByteArray) = synchronized(out) {
            out.write(b)
            out.flush()
        }
    }

    companion object {
        const val WS_PATH = "/ws"
        const val CLAIM_PATH = "/pair/claim"
        const val BLOB_PREFIX = "/blob/"
        /** S1-2 Q2: an upload's body (`Uploads`). */
        const val UPLOAD_PREFIX = "/blob/upload/"
        /** P7: the faces' route and the addresses' root (`/w/<window>/<target>`, `CONTRACT.md` §5). */
        const val FONT_PREFIX = "/font/"
        const val ADDRESS_ROOT = "/w"
        /** The faces a page may ask for: each of the glasses' four (`text/Text.kt`'s `Face`), regular and bold. */
        val FONTS: Set<String> = listOf("system", "list", "reader", "mono").flatMap { listOf("$it-regular", "$it-bold") }.toSet()
        /** The default `webPort` (dec. B.3): the next of the PC's ports (7300 setup, 7401–7404 content, transport,
         *  replica, media). */
        const val DEFAULT_PORT = 7405
        private const val MAX_CLAIM_BODY = 512
        private const val MAX_CLIENT_FRAME = 1L shl 20
        private const val PAIR_OUTBOX = 64
        private const val TAG = "web"

        private fun close(s: Socket) {
            try { s.close() } catch (e: Exception) { Log.w(TAG, "closing ${s.inetAddress?.hostAddress}: ${e.message}") }
        }
    }
}
