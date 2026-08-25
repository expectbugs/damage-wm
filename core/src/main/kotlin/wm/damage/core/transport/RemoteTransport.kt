package wm.damage.core.transport

import java.io.DataInputStream
import java.util.concurrent.ConcurrentHashMap
import java.io.DataOutputStream
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import wm.damage.core.gfx.Zl
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
 *
 * The mirror stream (HANDOFF.md §8.2 "the seam carries the mirror"): the
 * server watches its transport's mirror and, through the SAME ordered sender
 * as events, state and done messages, sends changed row ranges as `panel`
 * messages (`arm`, `y0`, `rows`, `rawLen`, then the rows deflated). A change
 * only queues a per-arm MARK; the sender builds the frame when it reaches
 * the mark, from the live panel against what this client last received — so
 * a slow link carries the latest content, not every intermediate frame, and
 * memory stays bounded (round 1, d3). The mark for a flush is queued during
 * its write, before its `done` can exist, so a panel update always precedes
 * the `done` of the flush that produced it. A fresh session gets both full
 * panels first. The client inflates and applies them to its [RemoteMirror].
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
    // panel messages (rows deflated; rawLen = rows × stride)
    val arm: String = "",
    val y0: Int = 0,
    val rows: Int = 0,
    val blobLen: Int = 0,
    val rawLen: Int = 0,
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
    val transportName: String, val detail: String = "",
)

private fun LinkState.toWire() = WireState(connected, started, leaseHeld, inFlight, window,
    ackMsEma, bytesPerSecEma, capability, rssiDbm, transportName, detail)

private fun WireState.toState() = LinkState(connected, started, leaseHeld, inFlight, window,
    ackMsEma, bytesPerSecEma, capability, rssiDbm, "remote:$transportName", detail)

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
private const val MAX_PANEL_BLOB = ((Geometry.PANEL_W + 1) / 2) * Geometry.PANEL_H   // one full lens

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
    require(c.blobLen in 0..MAX_PANEL_BLOB + 1024) { "panel blob ${c.blobLen} out of range" }
    if (c.t == "panel") {
        val stride = (Geometry.PANEL_W + 1) / 2
        require(c.y0 in 0..Geometry.PANEL_H && c.rows in 0..(Geometry.PANEL_H - c.y0)) { "panel rows ${c.y0}+${c.rows} out of range" }
        require(c.rawLen == c.rows * stride) { "panel rawLen ${c.rawLen} != ${c.rows} rows × $stride" }
    }
    blobLen += c.blobLen
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

    /** From the server's grant (the phone yielded its shell for us) until
     *  start() completes or fails: the arbitration holds the radio off. */
    @Volatile private var engagedFlag = false
    override val engaged: Boolean get() = engagedFlag

    private val stateLock = Any()
    private fun updateState(f: (LinkState) -> LinkState) {
        synchronized(stateLock) { _state.value = f(_state.value) }
    }

    /** Flushes submitted and not yet answered, with their submit time — the
     *  seam's stall REPORT (a fault, nothing cancelled), like the CFW
     *  transport's own (round 1, d11). */
    private val pendingSubmits = ConcurrentHashMap<Long, Long>()
    @Volatile private var stallReported = false
    @Volatile private var stallWatch: Job? = null

    @Volatile private var sock: Socket? = null
    @Volatile private var out: DataOutputStream? = null

    /** The seam-side equivalent of the base's session sweep: every flush
     *  still outstanding is answered as failed, loudly, so the shell rolls
     *  back its cells and its in-flight bookkeeping empties (round 2, b2-1). */
    private fun failOutstanding(why: String) {
        for (id in pendingSubmits.keys.toList()) {
            if (pendingSubmits.remove(id) != null)
                emit(TransportEvent.FlushDone(id, false, 0, 0, why), "FlushDone $id")
        }
    }
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
        // an INTERRUPTIBLE connect: a cancelled start() (a lost race in the
        // auto transport, a keeper pause) closes the channel instead of
        // leaving a thread parked in the OS connect to a silent peer
        val ch = withContext(Dispatchers.IO) {
            runInterruptible { java.nio.channels.SocketChannel.open(java.net.InetSocketAddress(host, port)) }
        }
        val s = ch.socket()
        s.tcpNoDelay = true     // a small done/input right after a panel must not wait on Nagle
        s.keepAlive = true      // an OS liveness probe on an idle link, not a bound on our work
        sock = s
        engagedFlag = false
        try {
            val inp = DataInputStream(s.getInputStream().buffered())
            val o = DataOutputStream(s.getOutputStream().buffered())
            out = o
            o.send(Ctl(t = "hello", token = token))
            val (resp, _) = inp.readCtl()
            when (resp.t) {
                "grant" -> engagedFlag = true      // the phone has yielded: we are committed
                "busy" -> throw IllegalStateException("transport at $host is driven by ${resp.detail}")
                else -> throw IllegalStateException("unexpected ${resp.t} from $host")
            }
            // reader thread: no timeouts; EOF/IOException = link down, loud
            Thread({
                try {
                    while (true) route(inp.readCtl(), mySession)
                } catch (e: EOFException) {
                    down(mySession, "transport server closed")
                } catch (e: Exception) {
                    down(mySession, "transport link error: ${e.message}")
                }
            }, "remote-transport-reader").start()

            o.send(Ctl(t = "start", warmupLen = warmupFrame.size), warmupFrame)
            val err = started.receive()
            if (err != null) {
                if (err.contains("capability gate FAILED")) throw CapabilityRefused("remote transport: $err")
                throw IllegalStateException("remote transport start failed: $err")
            }
            updateState { it.copy(connected = true, started = true) }
            pendingSubmits.clear()
            stallReported = false
            stallWatch?.cancel()
            stallWatch = scope.launch {
                while (isActive) {
                    delay(2_000)
                    val now = System.currentTimeMillis()
                    val oldest = pendingSubmits.values.minOrNull() ?: continue
                    if (!stallReported && now - oldest > STALL_REPORT_MS) {
                        stallReported = true
                        emit(TransportEvent.Fault("stall", "no done from $host for ${(now - oldest) / 1000} s with " +
                            "${pendingSubmits.size} flush(es) outstanding — the seam or the phone's link is not answering"), "Fault")
                    }
                }
            }
        } catch (e: Exception) {
            // a refused/failed claim must not leak the socket or leave a
            // half-open session holding the server's driver slot (round 2 #B5);
            // the reader's resulting EOF is expected, not a link loss
            closing = true
            try { s.close() } catch (c: Exception) { /* closing */ }
            throw e
        } finally {
            engagedFlag = false
        }
    }

    private fun down(fromSession: Long, reason: String) {
        if (fromSession != session.get()) {
            Log.i("remote-transport", "reader of session $fromSession ended ($reason) — a newer session is live")
            return
        }
        if (closing) Log.i("remote-transport", "$reason (expected: closing)")
        else Log.e("remote-transport", reason)
        stallWatch?.cancel()
        updateState { it.copy(connected = false, started = false, leaseHeld = false) }
        failOutstanding("seam link ended: $reason")
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

    private fun route(msg: Pair<Ctl, ByteArray?>, fromSession: Long) {
        val (c, blob) = msg
        // a reader left over from an earlier session still routes what its
        // stream had buffered when stop() closed the socket: a stale state,
        // panel or event must not reach this session (round 4, R4-2)
        if (fromSession != session.get()) {
            Log.i("remote-transport", "${c.t} from session $fromSession ignored — a newer session is live")
            return
        }
        when (c.t) {
            "panel" -> {
                val arm = if (c.arm == "L") Arm.LEFT else Arm.RIGHT
                if (c.rows == 0) return
                val raw = try {
                    Zl.inflate(blob ?: ByteArray(0), c.rawLen)
                } catch (e: Exception) {
                    emit(TransportEvent.Fault("seam", "panel update for ${c.rows} rows did not inflate: ${e.message}"), "Fault")
                    return
                }
                if (raw.size != c.rawLen) {
                    emit(TransportEvent.Fault("seam", "panel update inflated to ${raw.size} B, expected ${c.rawLen}"), "Fault")
                    return
                }
                mirror.apply(arm, c.y0, c.rows, raw)
            }
            "started" -> started.trySend(null)
            "startfail" -> started.trySend(c.detail)
            "done" -> {
                // answered once: a done still in the socket buffer when stop()
                // failed the outstanding flushes, or one for an id this side
                // never issued (version skew), must not reach the shell as a
                // second completion (round 3, a3-2)
                if (pendingSubmits.remove(c.id) == null) {
                    Log.w("remote-transport", "done for flush ${c.id} that is not outstanding (already answered, or unknown) — ignored")
                    return
                }
                stallReported = false
                emit(TransportEvent.FlushDone(c.id, c.ok, c.ackMs, c.bytes, c.error), "FlushDone ${c.id}")
            }
            "input" -> emit(TransportEvent.Input(c.evType, c.evSource), "Input")
            "lease" -> emit(TransportEvent.Lease(c.held, c.detail), "Lease")
            "link" -> emit(TransportEvent.Link(c.connected, c.detail), "Link")
            "flags" -> emit(TransportEvent.DiagFlags(c.flags), "DiagFlags")
            "fault" -> emit(TransportEvent.Fault(c.detail.substringBefore(':'),
                c.detail.substringAfter(':', "")), "Fault")
            "state" -> c.state?.let { st -> updateState { st.toState() } }
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
        pendingSubmits[id] = System.currentTimeMillis()
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
        stallWatch?.cancel()
        try { out?.send(Ctl(t = "stop")) } catch (e: Exception) { /* closing anyway */ }
        sock?.close()
        updateState { it.copy(connected = false, started = false) }
        failOutstanding("remote transport stopped")
    }

    companion object {
        private const val STALL_REPORT_MS = 10_000L
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

    /** What the ordered sender carries: a message, or a per-arm mark that
     *  the panel changed (the frame is built when the mark is reached). */
    private sealed class Out {
        class Msg(val ctl: Ctl, val blob: ByteArray?) : Out()
        class Panel(val arm: Arm) : Out()
    }

    private fun serve(sock: Socket) {
        try { sock.tcpNoDelay = true; sock.keepAlive = true } catch (e: Exception) { Log.w("transport-server", "socket options: ${e.message}") }
        val inp = DataInputStream(sock.getInputStream().buffered())
        val out = DataOutputStream(sock.getOutputStream().buffered())
        var holdsSlot = false
        val innerStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        // the inner start runs as a JOB, never on this reader thread: a driver
        // that leaves while its start is parked in a scan must be seen, and
        // its attempt cancelled (round 1, d1)
        var startJob: Job? = null
        // innerId rendezvous (round 2 #B9): the inner id is known only AFTER
        // submit returns, so the submitter and the completion collector race to
        // putIfAbsent — whoever arrives FIRST deposits (Long clientId, or the
        // FlushDone event); the second arrival sees the deposit, removes it and
        // sends. Atomic, no retry loop, no window to leak a completion.
        val rendezvous = ConcurrentHashMap<Long, Any>()
        var fwd: kotlinx.coroutines.Job? = null
        // ONE ordered outbox for everything that leaves after the handshake:
        // events, state, panel updates and done messages — a panel update
        // queued during a write precedes the done of the flush it belongs to
        val outbox = Channel<Out>(Channel.UNLIMITED)
        fun post(c: Ctl, blob: ByteArray? = null) {
            val r = outbox.trySend(Out.Msg(c, blob))
            if (!r.isSuccess && !r.isClosed) Log.e("transport-server", "outbox refused ${c.t} — message lost")
        }
        val stride = inner.mirror.stride
        val lastSent = mapOf(Arm.LEFT to ByteArray(stride * Geometry.PANEL_H), Arm.RIGHT to ByteArray(stride * Geometry.PANEL_H))
        val fullNext = mapOf(Arm.LEFT to java.util.concurrent.atomic.AtomicBoolean(true), Arm.RIGHT to java.util.concurrent.atomic.AtomicBoolean(true))
        val markQueued = mapOf(Arm.LEFT to java.util.concurrent.atomic.AtomicBoolean(false), Arm.RIGHT to java.util.concurrent.atomic.AtomicBoolean(false))
        fun markPanel(arm: Arm) {
            if (markQueued.getValue(arm).compareAndSet(false, true)) outbox.trySend(Out.Panel(arm))
        }
        /** The frame for [arm] as of NOW against what the client last received:
         *  the changed row range (the whole panel after attach), deflated. */
        fun buildPanel(arm: Arm): Pair<Ctl, ByteArray>? {
            markQueued.getValue(arm).set(false)     // a change from here on queues a new mark
            val now = inner.mirror.panel(arm)
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
                val raw = now.copyOfRange(first * stride, (first + rows) * stride)
                System.arraycopy(raw, 0, last, first * stride, raw.size)   // exactly what is sent
                val blob = Zl.deflate(raw)
                return Ctl(t = "panel", arm = if (arm == Arm.LEFT) "L" else "R", y0 = first, rows = rows,
                    blobLen = blob.size, rawLen = raw.size) to blob
            }
        }
        val mirrorListener = LensPanels.LensListener { arm -> markPanel(arm) }
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

            // ONE forwarding job per session: the sender drains the outbox in
            // order; events (with the id-mapped done router), state and panel
            // updates all post into it. A send failure ends the session rather
            // than throwing into the shared scope.
            fwd = scope.launch {
                launch {
                    try {
                        for (o in outbox) when (o) {
                            is Out.Msg -> out.send(o.ctl, o.blob)
                            is Out.Panel -> buildPanel(o.arm)?.let { (c, blob) -> out.send(c, blob) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // an IOException is the link ending; anything else is a
                        // defect — either way the session ends here, never in
                        // the host's scope (round 2, b2-4)
                        Log.e("transport-server", "sender ended — closing session: ${e.message}")
                        sock.close()
                    }
                }
                launch {
                    inner.events.collect { ev ->
                        if (ev is TransportEvent.FlushDone) {
                            val prev = rendezvous.putIfAbsent(ev.id, ev)
                            if (prev is Long) {
                                rendezvous.remove(ev.id)
                                post(Ctl(t = "done", id = prev, ok = ev.ok,
                                    ackMs = ev.ackMs, bytes = ev.bytes, error = ev.error))
                            }
                            // prev == null: deposited; the submitter completes
                        } else {
                            toCtl(ev)?.let { post(it) }
                        }
                    }
                }
                launch {
                    inner.state.collect { st -> post(Ctl(t = "state", state = st.toWire())) }
                }
            }
            // the mirror stream: both full panels first, then every change
            inner.mirror.addListener(mirrorListener)
            for (arm in Arm.entries) markPanel(arm)
            while (running) {
                val (c, blob) = inp.readCtl()
                when (c.t) {
                    "start" -> {
                        if (innerStarted.get() || startJob?.isActive == true) {
                            post(Ctl(t = "startfail", detail = "transport already started"))
                        } else {
                            val warmup = blob ?: ByteArray(0)
                            startJob = scope.launch {
                                try {
                                    inner.start(warmup)
                                    innerStarted.set(true)
                                    post(Ctl(t = "started"))
                                } catch (e: CancellationException) {
                                    post(Ctl(t = "startfail", detail = "start cancelled: the driver left"))
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("transport-server", "inner start failed", e)
                                    post(Ctl(t = "startfail", detail = e.message ?: e.toString()))
                                }
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
                                    post(Ctl(t = "done", id = c.id, ok = false,
                                        error = "unknown op kind '${w.k}' (version skew?)"))
                                    bad = true
                                }
                            }
                            if (bad) break
                        }
                        if (!bad && !innerStarted.get()) {
                            post(Ctl(t = "done", id = c.id, ok = false, error = "transport not started"))
                        } else if (!bad) {
                            val clientId = c.id
                            try {
                                val innerId = runBlocking {
                                    inner.submit(FlushRequest(ops, c.epoch, c.label, c.wide))
                                }
                                val prev = rendezvous.putIfAbsent(innerId, clientId)
                                if (prev is TransportEvent.FlushDone) {
                                    // the completion beat us here: it deposited
                                    rendezvous.remove(innerId)
                                    post(Ctl(t = "done", id = clientId, ok = prev.ok,
                                        ackMs = prev.ackMs, bytes = prev.bytes, error = prev.error))
                                }
                            } catch (e: Exception) {
                                post(Ctl(t = "done", id = clientId, ok = false,
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
                        runBlocking { startJob?.cancelAndJoin() }
                        if (innerStarted.getAndSet(false)) runBlocking { inner.stop() }
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
            inner.mirror.removeListener(mirrorListener)
            fwd?.cancel()
            outbox.close()
            try { sock.close() } catch (e: Exception) { /* closed */ }
            // a start still in progress is cancelled and awaited: its rollback
            // disconnects, so the local shell can take the glasses back
            try { runBlocking { startJob?.cancelAndJoin() } } catch (e: Exception) { Log.w("transport-server", "start cancel: ${e.message}") }
            // only a connection that actually HELD the driver slot may signal
            // the local shell to take back over — a rejected connection must
            // not trigger a dual-driver overlap (review round 1)
            if (holdsSlot) {
                if (innerStarted.get()) {
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

    private fun toCtl(ev: TransportEvent): Ctl? = when (ev) {
        is TransportEvent.Input -> Ctl(t = "input", evType = ev.type, evSource = ev.source)
        is TransportEvent.Lease -> Ctl(t = "lease", held = ev.held, detail = ev.detail)
        is TransportEvent.Link -> Ctl(t = "link", connected = ev.connected, detail = ev.detail)
        is TransportEvent.DiagFlags -> Ctl(t = "flags", flags = ev.flags)
        is TransportEvent.Fault -> Ctl(t = "fault", detail = "${ev.what}:${ev.detail}")
        is TransportEvent.FlushDone -> null   // delivered per-flush with id mapping
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
    override fun snapshot(arm: Arm): ByteArray = synchronized(panels) { panels.getValue(arm).copyOf() }
    override fun addListener(l: LensPanels.LensListener) { listeners.add(l) }
    override fun removeListener(l: LensPanels.LensListener) { listeners.remove(l) }

    /** Apply a row-range update: [rows] whole packed rows starting at [y0]. */
    fun apply(arm: Arm, y0: Int, rows: Int, packed: ByteArray, off: Int = 0) {
        require(y0 >= 0 && rows >= 0 && y0 + rows <= Geometry.PANEL_H) { "panel rows $y0+$rows out of range" }
        require(off + rows * stride <= packed.size) { "panel update short: ${packed.size - off} B for $rows rows" }
        synchronized(panels) { System.arraycopy(packed, off, panels.getValue(arm), y0 * stride, rows * stride) }
        for (l in listeners) l.panelChanged(arm)
    }
}
