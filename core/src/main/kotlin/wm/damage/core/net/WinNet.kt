package wm.damage.core.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import wm.damage.core.util.Log

/**
 * The GENERIC window channel over the CONTENT port — EXPLOSION §16.10 (agreed
 * 2026-09-01): the tmux pattern generalized so every HOST-need window gets a
 * provider seam without inventing a new wire. A connection that sends
 * `{"t":"win","win":"files"}` after the hello becomes that window's persistent
 * channel: id-correlated request/response, with an optional RAW BLOB following
 * an answer frame (file bytes, thumbnails, rasterized pages — mirrors the
 * library `get` framing, so big payloads never ride base64).
 *
 * Same port, same token, zero new phone config. Unknown `t` values are logged,
 * never fatal — protocol growth stays additive (the tmux `tpace` precedent).
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class WWire(
    val t: String,                                   // "wreq" | "wres" | "wpush"
    val id: Long = 0,
    val op: String = "",
    val args: JsonObject = JsonObject(emptyMap()),
    val ok: Boolean = false,
    val detail: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
    /** ≥0 on a "wres": that many raw bytes follow this frame immediately. */
    val blobLen: Long = -1,
)

private fun DataOutputStream.sendWire(w: WWire, blob: ByteArray? = null) {
    val b = json.encodeToString(WWire.serializer(), w).toByteArray(Charsets.UTF_8)
    synchronized(this) {
        writeInt(b.size)
        write(b)
        if (blob != null) write(blob)
        flush()
    }
}

private fun DataInputStream.readWireFrame(maxLen: Int = 4 shl 20): WWire {
    val n = readInt()
    require(n in 1..maxLen) { "win frame length $n out of range" }
    val b = ByteArray(n)
    readFully(b)
    return json.decodeFromString(WWire.serializer(), b.toString(Charsets.UTF_8))
}

/** What a window's host side implements — one service per window id,
 *  registered on the ContentHostServer. Requests are served inline on the
 *  channel's thread (one window's ops are sequential by design — the Files
 *  one-op-at-a-time rule). Throwing answers the request in-band, loudly. */
interface WinService {
    class Answer(val data: JsonObject = JsonObject(emptyMap()), val blob: ByteArray? = null)

    fun request(op: String, args: JsonObject): Answer

    /** The driver's channel ended (review 2026-09-01 P4): a service holding
     *  per-driver state (the Torrents focus pace) clears it here. */
    fun detached() {}
}

object WinNet {

    /** Cap on a single blob answer (a text preview, a page raster). */
    const val BLOB_MAX = 64L shl 20

    /** Serve one client on an already-helloed content connection. Blocks until
     *  the client leaves; the caller owns the socket. */
    fun serve(sock: Socket, inp: DataInputStream, out: DataOutputStream, winId: String, service: WinService) {
        Log.i("win-host", "driver ${sock.inetAddress} attached to the '$winId' channel")
        try {
            // greet (R3#2): the win lane is request-driven, so this is the
            // client's only unsolicited "the host really speaks this lane"
            // frame — its healthy flip waits for it, not for the upgrade
            // write (which an old host may answer by closing). Unknown to no
            // one: every win-serving build ships with this greeting.
            out.sendWire(WWire("wup"))
            while (true) {
                val w = inp.readWireFrame()
                when (w.t) {
                    "wreq" -> {
                        val res = try {
                            val a = service.request(w.op, w.args)
                            val blob = a.blob
                            if (blob != null) {
                                require(blob.size.toLong() <= BLOB_MAX) { "blob ${blob.size} B over cap" }
                                out.sendWire(WWire("wres", id = w.id, ok = true, data = a.data,
                                    blobLen = blob.size.toLong()), blob)
                                continue
                            }
                            WWire("wres", id = w.id, ok = true, data = a.data)
                        } catch (e: Exception) {
                            Log.w("win-host", "'$winId' op '${w.op}' failed: ${e.message}")
                            WWire("wres", id = w.id, ok = false, detail = e.message ?: e.toString())
                        }
                        out.sendWire(res)
                    }
                    else -> Log.w("win-host", "unknown win control '${w.t}' ignored")
                }
            }
        } catch (e: EOFException) {
            Log.i("win-host", "driver ${sock.inetAddress} left the '$winId' channel")
        } catch (e: Exception) {
            Log.w("win-host", "'$winId' channel ended: ${e.message}")
        } finally {
            try { sock.close() } catch (e: Exception) { /* closed */ }
            try { service.detached() } catch (e: Exception) { Log.e("win-host", "'$winId' detached hook", e) }
        }
    }
}

/**
 * The remote side (the phone shell): one persistent connection per window,
 * keeper-style reconnect with pacing, forever; requests are id-correlated and
 * park until the answer or the link's end fails them loudly. While
 * disconnected [stateLine] carries the §10.5 staleness surface
 * ("PC unreachable Ns") for the window to show.
 */
class RemoteWin(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val winId: String,
    private val scope: CoroutineScope,
    private val retryPacingMs: Long = 2_000,
    private val onState: (String) -> Unit = {},
) : AutoCloseable {

    @Serializable
    private data class Hello(val t: String = "hello", val token: String, val proto: Int = 1)

    @Serializable
    private data class Upgrade(val t: String = "win", val win: String)

    class Answer(val data: JsonObject, val blob: ByteArray?)

    @Volatile private var out: DataOutputStream? = null
    @Volatile private var running = true
    @Volatile var stateLine: String = "connecting to $host…"
        private set
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<Answer>>()
    @Volatile private var sockRef: Socket? = null
    private val loop: Job = scope.launch(kotlinx.coroutines.Dispatchers.IO) { connectLoop() }

    private suspend fun connectLoop() {
        var offlineSince = 0L
        while (scope.isActive && running) {
            try {
                Socket(host, port).use { sock ->
                    sockRef = sock
                    sock.tcpNoDelay = true
                    sock.keepAlive = true   // OS liveness probing on an idle link, not a bound on work (R3#1)
                    val inp = DataInputStream(sock.getInputStream().buffered())
                    val o = DataOutputStream(sock.getOutputStream().buffered())
                    val hello = json.encodeToString(Hello.serializer(), Hello(token = token)).toByteArray(Charsets.UTF_8)
                    synchronized(o) { o.writeInt(hello.size); o.write(hello); o.flush() }
                    val up = json.encodeToString(Upgrade.serializer(), Upgrade(win = winId)).toByteArray(Charsets.UTF_8)
                    synchronized(o) { o.writeInt(up.size); o.write(up); o.flush() }
                    out = o
                    while (true) {
                        val w = inp.readWireFrame()
                        // healthy only on a KNOWN-GOOD frame (R3#2 + R4#3):
                        // flipping on ANY frame — the "err" refusal included —
                        // re-created the flap-and-relog cycle on the in-band
                        // refusal path (the RemoteSync `attached` shape)
                        if (w.t == "wup" || w.t == "wres") {
                            if (offlineSince != 0L || stateLine.isNotEmpty()) { offlineSince = 0; setState("") }
                        }
                        when (w.t) {
                            "wup" -> {}   // the host's greeting — the healthy flip above IS its meaning
                            "wres" -> {
                                val blob = if (w.blobLen >= 0) {
                                    require(w.blobLen <= WinNet.BLOB_MAX) { "blob ${w.blobLen} B over cap" }
                                    val b = ByteArray(w.blobLen.toInt())
                                    inp.readFully(b)
                                    b
                                } else null
                                val fut = pending.remove(w.id)
                                if (fut == null) {
                                    Log.w("win-remote", "'$winId' answer for unknown request ${w.id}")
                                } else if (w.ok) {
                                    fut.complete(Answer(w.data, blob))
                                } else {
                                    fut.completeExceptionally(IllegalStateException(
                                        w.detail.ifEmpty { "'$winId' request refused" }))
                                }
                            }
                            // the host's in-band refusal (an old host, or a
                            // win id it does not serve): treat like the sync
                            // channel does (R3#2) — paced quiet retry, never
                            // "attached and healthy"
                            "err" -> throw java.io.IOException(
                                "host refused the '$winId' channel${if (w.detail.isNotEmpty()) ": ${w.detail}" else ""}")
                            else -> Log.w("win-remote", "unknown win control '${w.t}' ignored")
                        }
                    }
                }
            } catch (e: Exception) {
                out = null
                if (offlineSince == 0L) {
                    offlineSince = System.currentTimeMillis()
                    Log.w("win-remote", "'$winId' channel to $host down: ${e.message}")
                }
                failPending("'$winId' channel to $host down: ${e.message}")
                setState("PC unreachable ${(System.currentTimeMillis() - offlineSince) / 1000}s")
            }
            if (!running) return
            delay(retryPacingMs)          // pacing between attempts, not a timeout
        }
    }

    private fun setState(s: String) {
        stateLine = s
        try { onState(s) } catch (e: Exception) { Log.e("win-remote", "state hook", e) }
    }

    private fun failPending(why: String) {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.completeExceptionally(IllegalStateException(why))
        }
    }

    /** Blocking request (call from background coroutines, never the shell
     *  loop). Parks until the host answers or the link's end fails it — its
     *  fate is always decided, never abandoned. */
    fun request(op: String, args: JsonObject = JsonObject(emptyMap())): Answer {
        val o = out ?: throw IllegalStateException("host $host is not reachable ('$winId')")
        val id = nextId.getAndIncrement()
        val fut = CompletableFuture<Answer>()
        pending[id] = fut
        try {
            o.sendWire(WWire("wreq", id = id, op = op, args = args))
        } catch (e: Exception) {
            pending.remove(id)
            throw IllegalStateException("'$winId' request not sent: ${e.message}")
        }
        return try {
            fut.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception ?: e)
        }
    }

    override fun close() {
        running = false
        loop.cancel()
        failPending("'$winId' channel closed")
        try { sockRef?.close() } catch (e: Exception) { /* closing */ }
    }
}
