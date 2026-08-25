package wm.damage.core.transport

import java.io.DataInputStream
import java.util.concurrent.ConcurrentHashMap
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
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.util.Log
import wm.damage.core.wire.EvenHubMsg

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

private const val MAX_OP_PAYLOAD = 200_000       // > MODE8_MAX, < anything absurd
private const val MAX_BLOB = 4 shl 20

private fun DataInputStream.readCtl(): Pair<Ctl, ByteArray?> {
    val n = readInt()
    require(n in 1..(1 shl 20)) { "control frame length $n out of range" }
    val b = ByteArray(n)
    readFully(b)
    val c = json.decodeFromString(Ctl.serializer(), b.toString(Charsets.UTF_8))
    // peer-declared lengths are VALIDATED before any allocation: a buggy peer
    // must produce a loud session error, never an OOM (an Error would skip the
    // reader thread's catch and kill the link in silence)
    var blobLen = 0L
    for (op in c.ops) {
        require(op.len in 0..MAX_OP_PAYLOAD) { "op payload ${op.len} out of range" }
        blobLen += op.len
    }
    require(c.warmupLen in 0..MAX_OP_PAYLOAD) { "warmup length ${c.warmupLen} out of range" }
    blobLen += c.warmupLen
    require(blobLen <= MAX_BLOB) { "blob total $blobLen exceeds $MAX_BLOB" }
    val blob = if (blobLen > 0) ByteArray(blobLen.toInt()).also { readFully(it) } else null
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

    /** The far end's mirror as streamed over the seam — display-only (lags). */
    override val mirror: RemoteMirror = RemoteMirror()

    override fun injectInput(type: Int) =
        emit(TransportEvent.Input(type, EvenHubMsg.SRC_RING), "Input")

    private var sock: Socket? = null
    private var out: DataOutputStream? = null
    private val nextId = AtomicLong(1)
    private val started = Channel<String?>(1)
    /** A deliberate stop(): the reader's resulting link-down is expected and
     *  logged as such, not as an error (round 3 D9). */
    @Volatile private var closing = false

    /** Each start() is a numbered session; a reader thread left over from an
     *  earlier session reports its EOF into THAT session only, never into a
     *  newer one's state or start rendezvous (round 4 D3). */
    private val session = AtomicLong(0)

    override suspend fun start(warmupFrame: ByteArray) {
        closing = false
        val mySession = session.incrementAndGet()
        // a failed earlier attempt leaves its link-down reason in the channel;
        // consuming it here would misreport THIS attempt (round 3 D9)
        while (started.tryReceive().isSuccess) { /* drain residue */ }
        val s = Socket(host, port)
        sock = s
        try {
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
            // reader thread: no timeouts; EOF/IOException = link down, loud
            Thread({
                try {
                    while (true) route(inp.readCtl())
                } catch (e: EOFException) {
                    down(mySession, "transport server closed")
                } catch (e: Exception) {
                    down(mySession, "transport link error: ${e.message}")
                }
            }, "remote-transport-reader").start()

            o.send(Ctl(t = "start", warmupLen = warmupFrame.size), warmupFrame)
            val err = started.receive()
            if (err != null) throw IllegalStateException("remote transport start failed: $err")
            _state.value = _state.value.copy(connected = true, started = true)
        } catch (e: Exception) {
            // a refused/failed claim must not leak the socket or leave a
            // half-open session holding the server's driver slot (round 2 #B5)
            try { s.close() } catch (c: Exception) { /* closing */ }
            throw e
        }
    }

    private fun down(fromSession: Long, reason: String) {
        if (fromSession != session.get()) {
            Log.i("remote-transport", "reader of session $fromSession ended ($reason) — a newer session is live")
            return
        }
        if (closing) Log.i("remote-transport", "$reason (expected: closing)")
        else Log.e("remote-transport", reason)
        _state.value = _state.value.copy(connected = false, started = false, leaseHeld = false)
        // a caller parked in start() must get the answer, not a silent hang
        started.trySend(reason)
        emit(TransportEvent.Link(false, reason), "Link-down")
    }

    /** NO SILENT FAILURES: every dropped event is named (round 2 #B13) — a
     *  full buffer here means the shell loop is wedged, which is exactly when
     *  silence would be most misleading. */
    private fun emit(ev: TransportEvent, what: String) {
        if (!_events.tryEmit(ev)) {
            Log.e("remote-transport", "$what event DROPPED (buffer full)" +
                if (ev is TransportEvent.FlushDone)
                    " — the shell will see flush ${ev.id} as forever in flight" else "")
        }
    }

    private fun route(msg: Pair<Ctl, ByteArray?>) {
        val (c, _) = msg
        when (c.t) {
            "started" -> started.trySend(null)
            "startfail" -> started.trySend(c.detail)
            "done" -> emit(TransportEvent.FlushDone(c.id, c.ok, c.ackMs, c.bytes, c.error),
                "FlushDone ${c.id}")
            "input" -> emit(TransportEvent.Input(c.evType, c.evSource), "Input")
            "lease" -> emit(TransportEvent.Lease(c.held, c.detail), "Lease")
            "link" -> emit(TransportEvent.Link(c.connected, c.detail), "Link")
            "flags" -> emit(TransportEvent.DiagFlags(c.flags), "DiagFlags")
            "fault" -> emit(TransportEvent.Fault(c.detail.substringBefore(':'),
                c.detail.substringAfter(':', "")), "Fault")
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
            is DisplayOp.StereoPair -> {
                ops.add(WireOp("sp", src = op.left.wire(), dst = op.right.wire(), len = op.payload.size))
                blobLen += op.payload.size
            }
        }
        val blob = ByteArray(blobLen)
        var off = 0
        for (op in flush.ops) {
            val p = when (op) {
                is DisplayOp.Keyframe -> op.payload
                is DisplayOp.Delta -> op.payload
                is DisplayOp.StereoPair -> op.payload
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
        closing = true
        session.incrementAndGet()   // the reader's EOF belongs to the old session
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
        var holdsSlot = false
        var innerStarted = false
        // innerId rendezvous (round 2 #B9): the inner id is known only AFTER
        // submit returns, so the submitter and the completion collector race to
        // putIfAbsent — whoever arrives FIRST deposits (Long clientId, or the
        // FlushDone event); the second arrival sees the deposit, removes it and
        // sends. Atomic, no retry loop, no window to leak a completion.
        val rendezvous = ConcurrentHashMap<Long, Any>()
        var fwd: kotlinx.coroutines.Job? = null
        try {
            val (hello, _) = inp.readCtl()
            if (hello.t != "hello" || hello.token != token) {
                out.send(Ctl(t = "busy", detail = "bad token"))
                return
            }
            synchronized(this) {
                if (driver != null) {
                    out.send(Ctl(t = "busy", detail = driver!!.inetAddress.toString()))
                    return
                }
                driver = sock
                holdsSlot = true
            }
            out.send(Ctl(t = "grant"))
            Log.i("transport-server", "remote shell ${sock.inetAddress} claimed the transport")
            onRemoteDriver(true)

            // ONE forwarding job per session: events (with the id-mapped done
            // router), then state. Send failures tear the session down rather
            // than throwing into the shared scope.
            fwd = scope.launch {
                launch {
                    inner.events.collect { ev ->
                        try {
                            if (ev is TransportEvent.FlushDone) {
                                val prev = rendezvous.putIfAbsent(ev.id, ev)
                                if (prev is Long) {
                                    rendezvous.remove(ev.id)
                                    out.send(Ctl(t = "done", id = prev, ok = ev.ok,
                                        ackMs = ev.ackMs, bytes = ev.bytes, error = ev.error))
                                }
                                // prev == null: deposited; the submitter completes
                            } else {
                                forward(out, ev)
                            }
                        } catch (e: java.io.IOException) {
                            Log.w("transport-server", "event forward failed — closing session: ${e.message}")
                            sock.close()
                        }
                    }
                }
                launch {
                    inner.state.collect { st ->
                        try {
                            out.send(Ctl(t = "state", state = st.toWire()))
                        } catch (e: java.io.IOException) {
                            Log.w("transport-server", "state forward failed — closing session: ${e.message}")
                            sock.close()
                        }
                    }
                }
            }
            while (running) {
                val (c, blob) = inp.readCtl()
                when (c.t) {
                    "start" -> {
                        if (innerStarted) {
                            out.send(Ctl(t = "startfail", detail = "transport already started"))
                        } else {
                            val warmup = blob ?: ByteArray(0)
                            try {
                                runBlocking { inner.start(warmup) }
                                innerStarted = true
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
                        var bad = false
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
                                "sp" -> {
                                    ops.add(DisplayOp.StereoPair(w.src.rect(), w.dst.rect(),
                                        blob!!.copyOfRange(off, off + w.len)))
                                    off += w.len
                                }
                                else -> {
                                    // an unknown kind desynchronizes the payload
                                    // offsets — every later op would carry the
                                    // WRONG bytes; reject the whole flush loudly
                                    Log.e("transport-server", "unknown op kind '${w.k}' — flush ${c.id} rejected")
                                    out.send(Ctl(t = "done", id = c.id, ok = false,
                                        error = "unknown op kind '${w.k}' (version skew?)"))
                                    bad = true
                                }
                            }
                            if (bad) break
                        }
                        if (!bad) {
                            val clientId = c.id
                            try {
                                val innerId = runBlocking {
                                    inner.submit(FlushRequest(ops, c.epoch, c.label, c.wide))
                                }
                                val prev = rendezvous.putIfAbsent(innerId, clientId)
                                if (prev is TransportEvent.FlushDone) {
                                    // the completion beat us here: it deposited
                                    rendezvous.remove(innerId)
                                    out.send(Ctl(t = "done", id = clientId, ok = prev.ok,
                                        ackMs = prev.ackMs, bytes = prev.bytes, error = prev.error))
                                }
                            } catch (e: Exception) {
                                out.send(Ctl(t = "done", id = clientId, ok = false,
                                    error = e.message ?: e.toString()))
                            }
                        }
                    }
                    "cleardiag" -> scope.launch {
                        try { inner.clearDiagFlags() } catch (e: Exception) {
                            Log.w("transport-server", "cleardiag: ${e.message}")
                        }
                    }
                    "stop" -> {
                        runBlocking { inner.stop() }
                        innerStarted = false
                        break
                    }
                    else -> Log.w("transport-server", "unknown control '${c.t}' ignored")
                }
            }
        } catch (e: EOFException) {
            Log.i("transport-server", "remote shell ${sock.inetAddress} disconnected")
        } catch (e: Exception) {
            Log.w("transport-server", "driver session error: ${e.message}")
        } finally {
            fwd?.cancel()
            try { sock.close() } catch (e: Exception) { /* closed */ }
            // only a connection that actually HELD the driver slot may signal
            // the local shell to take back over — a rejected connection must
            // not trigger a dual-driver overlap (review round 1)
            if (holdsSlot) {
                if (innerStarted) {
                    try { runBlocking { inner.stop() } } catch (e: Exception) {
                        Log.w("transport-server", "inner stop after driver loss: ${e.message}")
                    }
                }
                onRemoteDriver(false)
            }
            // release the slot LAST (round 3 D6): a reconnect during teardown is
            // answered "busy", never granted a transport still being stopped
            synchronized(this) { if (driver === sock) driver = null }
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

/**
 * The client side of the mirror stream: packed per-lens panels the seam
 * server sends as row-range updates. Never exact (it lags the far end), so the
 * shell's divergence check ignores it; every replica on this side draws it.
 */
class RemoteMirror : LensPanels {
    override val exact: Boolean get() = false
    override val stride: Int = (Geometry.PANEL_W + 1) / 2
    private val panels = mapOf(
        Arm.LEFT to ByteArray(stride * Geometry.PANEL_H),
        Arm.RIGHT to ByteArray(stride * Geometry.PANEL_H),
    )
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<LensPanels.LensListener>()

    override fun panel(arm: Arm): ByteArray = panels.getValue(arm)
    override fun addListener(l: LensPanels.LensListener) { listeners.add(l) }
    override fun removeListener(l: LensPanels.LensListener) { listeners.remove(l) }

    /** Apply a row-range update: [rows] whole packed rows starting at [y0]. */
    fun apply(arm: Arm, y0: Int, rows: Int, packed: ByteArray, off: Int = 0) {
        require(y0 >= 0 && rows >= 0 && y0 + rows <= Geometry.PANEL_H) { "panel rows $y0+$rows out of range" }
        require(off + rows * stride <= packed.size) { "panel update short: ${packed.size - off} B for $rows rows" }
        System.arraycopy(packed, off, panels.getValue(arm), y0 * stride, rows * stride)
        for (l in listeners) l.panelChanged(arm)
    }
}
