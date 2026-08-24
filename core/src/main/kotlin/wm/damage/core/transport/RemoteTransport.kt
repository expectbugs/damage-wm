package wm.damage.core.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.geom.Rect
import wm.damage.core.util.Log

/**
 * The transport <-> shell seam, serialized (DESIGN.md §10.1: "the seams between
 * roles must be protocols, not function calls"). This is what lets the SHELL
 * live on the PC while the TRANSPORT lives on the phone or the bridge appliance
 * — or the reverse — with both able to take over.
 *
 * Framing: 4-byte length-prefixed JSON control lines; a flush's compressed op
 * payloads follow their header as one raw binary block. Token-gated. Exactly
 * ONE driver at a time: a second shell gets "busy" (the arbitration Adam's
 * seamless-takeover requirement needs); when the remote driver disconnects the
 * server surfaces it so the local shell can take back over.
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class Ctl(
    val t: String,
    val token: String = "",
    val id: Long = 0,
    val epoch: Long = 0,
    val label: String = "",
    val ops: List<WireOp> = emptyList(),
    val ok: Boolean = false,
    val ackMs: Long = 0,
    val bytes: Int = 0,
    val error: String? = null,
    val evType: Int = 0,
    val evSource: Int = 0,
    val detail: String = "",
    val held: Boolean = false,
    val connected: Boolean = false,
    val flags: Map<String, Boolean> = emptyMap(),
    val state: WireState? = null,
    val warmupLen: Int = 0,
    val wide: Boolean = false,
)

@Serializable
private data class WireOp(
    val k: String,                       // "kf" | "d" | "c"
    val box: List<Int> = emptyList(),
    val src: List<Int> = emptyList(),
    val dst: List<Int> = emptyList(),
    val disp: Int = 0,
    val len: Int = 0,
)

@Serializable
data class WireState(
    val connected: Boolean, val started: Boolean, val leaseHeld: Boolean,
    val inFlight: Int, val window: Int, val ackMsEma: Double,
    val bytesPerSecEma: Double, val capability: String?, val rssiDbm: Int?,
    val transportName: String,
)

private fun LinkState.toWire() = WireState(connected, started, leaseHeld, inFlight, window,
    ackMsEma, bytesPerSecEma, capability, rssiDbm, transportName)

private fun WireState.toState() = LinkState(connected, started, leaseHeld, inFlight, window,
    ackMsEma, bytesPerSecEma, capability, rssiDbm, "remote:$transportName")

private fun DataOutputStream.send(c: Ctl, blob: ByteArray? = null) {
    val b = json.encodeToString(Ctl.serializer(), c).toByteArray(Charsets.UTF_8)
    synchronized(this) {
        writeInt(b.size)
        write(b)
        if (blob != null) write(blob)
        flush()
    }
}

private fun DataInputStream.readCtl(): Pair<Ctl, ByteArray?> {
    val n = readInt()
    require(n in 1..(1 shl 20)) { "control frame length $n out of range" }
    val b = ByteArray(n)
    readFully(b)
    val c = json.decodeFromString(Ctl.serializer(), b.toString(Charsets.UTF_8))
    val blobLen = c.ops.sumOf { it.len } + c.warmupLen
    val blob = if (blobLen > 0) ByteArray(blobLen).also { readFully(it) } else null
    return c to blob
}

private fun List<Int>.rect(): Rect = Rect(this[0], this[1], this[2], this[3])
private fun Rect.wire(): List<Int> = listOf(x, y, w, h)

// ================================================================== client
/** The shell side of the seam — a [Transport] whose real implementation is on
 *  the other end of a socket. */
class RemoteTransportClient(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val scope: CoroutineScope,
) : Transport {
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    override val events = _events.asSharedFlow()
    private val _state = MutableStateFlow(LinkState(transportName = "remote:$host"))
    override val state = _state.asStateFlow()

    private var sock: Socket? = null
    private var out: DataOutputStream? = null
    private val nextId = AtomicLong(1)
    private val started = Channel<String?>(1)

    override suspend fun start(warmupFrame: ByteArray) {
        val s = Socket(host, port)
        sock = s
        val inp = DataInputStream(s.getInputStream().buffered())
        val o = DataOutputStream(s.getOutputStream().buffered())
        out = o
        o.send(Ctl(t = "hello", token = token))
        val (resp, _) = inp.readCtl()
        when (resp.t) {
            "grant" -> {}
            "busy" -> throw IllegalStateException("transport at $host is driven by ${resp.detail}")
            else -> throw IllegalStateException("unexpected ${resp.t} from $host")
        }
        // reader thread: no timeouts; EOF/IOException = link down, surfaced loudly
        Thread({
            try {
                while (true) route(inp.readCtl())
            } catch (e: EOFException) {
                down("transport server closed")
            } catch (e: Exception) {
                down("transport link error: ${e.message}")
            }
        }, "remote-transport-reader").start()

        o.send(Ctl(t = "start", warmupLen = warmupFrame.size), warmupFrame)
        val err = started.receive()
        if (err != null) throw IllegalStateException("remote transport start failed: $err")
        _state.value = _state.value.copy(connected = true, started = true)
    }

    private fun down(reason: String) {
        Log.e("remote-transport", reason)
        _state.value = _state.value.copy(connected = false, started = false, leaseHeld = false)
        _events.tryEmit(TransportEvent.Link(false, reason))
    }

    private fun route(msg: Pair<Ctl, ByteArray?>) {
        val (c, _) = msg
        when (c.t) {
            "started" -> started.trySend(null)
            "startfail" -> started.trySend(c.detail)
            "done" -> _events.tryEmit(TransportEvent.FlushDone(c.id, c.ok, c.ackMs, c.bytes, c.error))
            "input" -> _events.tryEmit(TransportEvent.Input(c.evType, c.evSource))
            "lease" -> _events.tryEmit(TransportEvent.Lease(c.held, c.detail))
            "link" -> _events.tryEmit(TransportEvent.Link(c.connected, c.detail))
            "flags" -> _events.tryEmit(TransportEvent.DiagFlags(c.flags))
            "fault" -> _events.tryEmit(TransportEvent.Fault(c.detail.substringBefore(':'),
                c.detail.substringAfter(':', "")))
            "state" -> c.state?.let { _state.value = it.toState() }
            else -> Log.w("remote-transport", "unknown control ${c.t}")
        }
    }

    override suspend fun submit(flush: FlushRequest): Long {
        val o = out ?: throw IllegalStateException("remote transport not started")
        val id = nextId.getAndIncrement()
        val ops = ArrayList<WireOp>(flush.ops.size)
        var blobLen = 0
        for (op in flush.ops) when (op) {
            is DisplayOp.Keyframe -> { ops.add(WireOp("kf", len = op.payload.size)); blobLen += op.payload.size }
            is DisplayOp.Delta -> {
                ops.add(WireOp("d", box = op.box.wire(), disp = op.disparity, len = op.payload.size))
                blobLen += op.payload.size
            }
            is DisplayOp.Copy -> ops.add(WireOp("c", src = op.src.wire(), dst = op.dst.wire(), disp = op.disparity))
        }
        val blob = ByteArray(blobLen)
        var off = 0
        for (op in flush.ops) {
            val p = when (op) {
                is DisplayOp.Keyframe -> op.payload
                is DisplayOp.Delta -> op.payload
                else -> null
            } ?: continue
            p.copyInto(blob, off)
            off += p.size
        }
        o.send(Ctl(t = "flush", id = id, epoch = flush.epoch, label = flush.label, ops = ops,
            wide = flush.wide), blob)
        return id
    }

    override suspend fun clearDiagFlags() {
        out?.send(Ctl(t = "cleardiag"))
    }

    override suspend fun stop() {
        try { out?.send(Ctl(t = "stop")) } catch (e: Exception) { /* closing anyway */ }
        sock?.close()
        _state.value = _state.value.copy(connected = false, started = false)
    }
}

// ================================================================== server
/**
 * The transport side of the seam: wraps a real local [Transport] (the phone's
 * BLE transport, or a sim) and serves ONE remote shell. [onRemoteDriver] fires
 * with true when a remote shell claims (the local shell must yield the glasses)
 * and false when it disconnects (the local shell takes back over).
 */
class RemoteTransportServer(
    private val inner: Transport,
    private val port: Int,
    private val token: String,
    private val scope: CoroutineScope,
    private val onRemoteDriver: (Boolean) -> Unit = {},
) : AutoCloseable {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var driver: Socket? = null

    fun start() {
        running = true
        val s = ServerSocket(port)
        server = s
        Thread({
            Log.i("transport-server", "transport seam on :$port")
            while (running) {
                val client = try { s.accept() } catch (e: Exception) {
                    if (running) Log.e("transport-server", "accept failed", e)
                    break
                }
                Thread({ serve(client) }, "transport-driver-${client.inetAddress}").start()
            }
        }, "transport-server").start()
    }

    private fun serve(sock: Socket) {
        val inp = DataInputStream(sock.getInputStream().buffered())
        val out = DataOutputStream(sock.getOutputStream().buffered())
        try {
            val (hello, _) = inp.readCtl()
            if (hello.t != "hello" || hello.token != token) {
                out.send(Ctl(t = "busy", detail = "bad token"))
                sock.close()
                return
            }
            synchronized(this) {
                if (driver != null) {
                    out.send(Ctl(t = "busy", detail = driver!!.inetAddress.toString()))
                    sock.close()
                    return
                }
                driver = sock
            }
            out.send(Ctl(t = "grant"))
            Log.i("transport-server", "remote shell ${sock.inetAddress} claimed the transport")
            onRemoteDriver(true)

            // forward inner transport events + state while this driver holds it
            val fwd = scope.launch {
                launch { inner.events.collect { ev -> forward(out, ev) } }
                launch { inner.state.collect { st -> out.send(Ctl(t = "state", state = st.toWire())) } }
            }
            try {
                while (running) {
                    val (c, blob) = inp.readCtl()
                    when (c.t) {
                        "start" -> {
                            val warmup = blob ?: ByteArray(0)
                            scope.launch {
                                try {
                                    inner.start(warmup)
                                    out.send(Ctl(t = "started"))
                                } catch (e: Exception) {
                                    Log.e("transport-server", "inner start failed", e)
                                    out.send(Ctl(t = "startfail", detail = e.message ?: e.toString()))
                                }
                            }
                        }
                        "flush" -> {
                            val ops = ArrayList<DisplayOp>(c.ops.size)
                            var off = 0
                            for (w in c.ops) {
                                when (w.k) {
                                    "kf" -> {
                                        ops.add(DisplayOp.Keyframe(blob!!.copyOfRange(off, off + w.len)))
                                        off += w.len
                                    }
                                    "d" -> {
                                        ops.add(DisplayOp.Delta(w.box.rect(),
                                            blob!!.copyOfRange(off, off + w.len), w.disp))
                                        off += w.len
                                    }
                                    "c" -> ops.add(DisplayOp.Copy(w.src.rect(), w.dst.rect(), w.disp))
                                }
                            }
                            val clientId = c.id
                            scope.launch {
                                try {
                                    val innerId = inner.submit(FlushRequest(ops, c.epoch, c.label, c.wide))
                                    // map inner completion to the client's id
                                    inner.events.collect { ev ->
                                        if (ev is TransportEvent.FlushDone && ev.id == innerId) {
                                            out.send(Ctl(t = "done", id = clientId, ok = ev.ok,
                                                ackMs = ev.ackMs, bytes = ev.bytes, error = ev.error))
                                            throw kotlinx.coroutines.CancellationException("delivered")
                                        }
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    // delivered
                                } catch (e: Exception) {
                                    out.send(Ctl(t = "done", id = clientId, ok = false, error = e.message))
                                }
                            }
                        }
                        "cleardiag" -> scope.launch { inner.clearDiagFlags() }
                        "stop" -> {
                            runBlocking { inner.stop() }
                            break
                        }
                    }
                }
            } finally {
                fwd.cancel()
            }
        } catch (e: EOFException) {
            Log.i("transport-server", "remote shell ${sock.inetAddress} disconnected")
        } catch (e: Exception) {
            Log.w("transport-server", "driver session error: ${e.message}")
        } finally {
            synchronized(this) { if (driver === sock) driver = null }
            try { sock.close() } catch (e: Exception) { /* closed */ }
            onRemoteDriver(false)
        }
    }

    private fun forward(out: DataOutputStream, ev: TransportEvent) {
        when (ev) {
            is TransportEvent.Input -> out.send(Ctl(t = "input", evType = ev.type, evSource = ev.source))
            is TransportEvent.Lease -> out.send(Ctl(t = "lease", held = ev.held, detail = ev.detail))
            is TransportEvent.Link -> out.send(Ctl(t = "link", connected = ev.connected, detail = ev.detail))
            is TransportEvent.DiagFlags -> out.send(Ctl(t = "flags", flags = ev.flags))
            is TransportEvent.Fault -> out.send(Ctl(t = "fault", detail = "${ev.what}:${ev.detail}"))
            is TransportEvent.FlushDone -> {} // delivered per-flush with id mapping
        }
    }

    override fun close() {
        running = false
        server?.close()
        driver?.close()
    }
}
