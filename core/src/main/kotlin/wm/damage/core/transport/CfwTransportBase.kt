package wm.damage.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import wm.damage.core.geom.FidAllocator
import wm.damage.core.geom.FidTracker
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.util.Log
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.SettingsMsg

/**
 * The CFW transport choreography, sans-IO: capability gate, carrier CREATE,
 * FB lease on both arms with 45 s renewal, the sacrificial warmup, idle
 * keepalive, msgId/MapSessionId/fid discipline, <=3800 B fragmenting, the
 * 3-deep pipeline window with the §8.2 #4 wide-flush drain, and ack routing.
 *
 * Subclasses supply only the wire: [writeArm] (one AA packet to one arm) and
 * feed notifications back through [onNotifyPacket]. SimTransport and the
 * phone's BleTransport therefore run the SAME protocol brain — the one the
 * selfcheck and the round-trip tests exercise — and first light changes only
 * the glue underneath it.
 *
 * Reference arm split (overview.md §2, graded strong-not-proven — verify with
 * a two-arm capture at first light): bulk pixels -> LEFT, control -> RIGHT;
 * events and acks arrive on RIGHT.
 */
abstract class CfwTransportBase(
    protected val scope: CoroutineScope,
    private val name: String,
) : Transport {

    enum class Arm { LEFT, RIGHT }

    protected val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    override val events = _events.asSharedFlow()

    protected val _state = MutableStateFlow(LinkState(transportName = name))
    override val state = _state.asStateFlow()

    private val window = Semaphore(WINDOW)
    private val writeMutex = Mutex()          // multi-fragment messages must never interleave
    private val fids = FidAllocator()
    private val tracker = FidTracker()
    private var msgId = 0
    private var session = 1
    private var aaSeq = 0
    private var flushCounter = 0L
    @Volatile protected var lastImageAtMs = 0L
    @Volatile protected var running = false

    private val pendingAcks = HashMap<Int, Channel<EvenHubMsg.Ack>>()
    private val capabilityChannel = Channel<String>(Channel.CONFLATED)
    private val reassemblers = mapOf(
        Arm.LEFT to AaFrame.Reassembler { Log.w(name, "L: $it") },
        Arm.RIGHT to AaFrame.Reassembler { Log.w(name, "R: $it") },
    )

    // ------------------------------------------------------------------ wire seam
    /** Write one AA packet to [arm]'s write characteristic. May model or incur
     *  transfer time. Serialized by the base (never interleaves messages). */
    protected abstract suspend fun writeArm(arm: Arm, packet: ByteArray)

    /** Bring the physical link up (connect, MTU, subscribe). After this,
     *  notifications must flow into [onNotifyPacket]. */
    protected abstract suspend fun connectLink()

    /** Tear the physical link down. */
    protected abstract suspend fun disconnectLink()

    /** Called by the base each maintenance tick — report lease/link extras. */
    protected open fun onMaintenanceTick() {}

    /** Interval scale for tests: instant transports shrink the waits. */
    protected open val instant: Boolean = false

    /** Subclasses feed every notification packet here (any arm). */
    protected fun onNotifyPacket(arm: Arm, packet: ByteArray) {
        val frame = reassemblers.getValue(arm).offer(packet) ?: return
        when (frame.sid) {
            EvenHubMsg.SID -> when (frame.flag) {
                EvenHubMsg.FLAG_ACK -> {
                    val ack = EvenHubMsg.parseAck(frame.payload) ?: return
                    pendingAcks.remove(ack.msgId)?.trySend(ack)
                }
                EvenHubMsg.FLAG_EVENT -> {
                    val ev = EvenHubMsg.parseEvent(frame.payload)
                    if (ev is EvenHubMsg.Event.Gesture)
                        _events.tryEmit(TransportEvent.Input(ev.type, ev.source))
                }
                EvenHubMsg.FLAG_ABORT ->
                    _events.tryEmit(TransportEvent.Fault("abort", "e0-02 reassembly abort from glasses"))
            }
            SettingsMsg.SID -> {
                SettingsMsg.parseCapability(frame.payload)?.let { capabilityChannel.trySend(it) }
            }
        }
    }

    protected fun emitFault(what: String, detail: String) {
        _events.tryEmit(TransportEvent.Fault(what, detail))
    }

    protected fun emitFlags(flags: Map<String, Boolean>) {
        if (flags.any { it.value }) _events.tryEmit(TransportEvent.DiagFlags(flags))
    }

    protected fun setLease(held: Boolean, detail: String) {
        if (held != _state.value.leaseHeld) {
            _state.value = _state.value.copy(leaseHeld = held)
            _events.tryEmit(TransportEvent.Lease(held, detail))
        }
    }

    // ------------------------------------------------------------------ counters
    /** msgId cycles inside one byte — the glasses go silent past 255
     *  (G2_BLE_PROTOCOL.md §3). Wrap early at 250 for margin. */
    private fun nextMsgId(): Int { msgId = (msgId + 1) % 250; return msgId }

    private fun nextSeq(): Int { aaSeq = (aaSeq + 1) and 0xFF; return aaSeq }

    /** Bump per push; jump by 3 after any failure — the stuck-session trap
     *  wants >=2 (overview.md §9.2/§9.4). */
    private fun nextSession(afterFailure: Boolean = false): Int {
        session = (session + if (afterFailure) 3 else 1) % 250
        if (session == 0) session = 1
        return session
    }

    // ------------------------------------------------------------------ lifecycle
    override suspend fun start(warmupFrame: ByteArray) {
        running = true
        connectLink()
        _state.value = _state.value.copy(connected = true)
        _events.emit(TransportEvent.Link(true, "$name link up"))

        // 1. Capability gate (§9.2b): field 100 rides the settings READ response
        //    itself — no timeout needed by construction.
        writeSerialized(Arm.RIGHT, AaFrame.frame(nextSeq(), SettingsMsg.SID,
            SettingsMsg.FLAG_REQUEST, SettingsMsg.settingsQuery(nextMsgId())))
        val cap = capabilityChannel.receive()
        val missing = SettingsMsg.missingCaps(cap)
        if (missing.isNotEmpty()) {
            val msg = "capability gate FAILED: '$cap' missing $missing — refusing to paint"
            _events.emit(TransportEvent.Fault("capability", msg))
            throw LintError(msg)
        }
        _state.value = _state.value.copy(capability = cap)

        // 2. Carrier CREATE — image container + the full-screen dummy text
        //    container that is the event antenna (overview.md §4.1).
        sendControl(EvenHubMsg.carrierCreate(nextMsgId()))

        // 3. FB lease, BOTH arms (display_copy_hook runs per lens).
        renewLease()

        // 4. The sacrificial warmup frame — the firmware silently drops the
        //    first burst after CREATE (§5.17: make it the splash).
        sendImage(warmupFrame)

        _state.value = _state.value.copy(started = true, leaseHeld = true)

        // Maintenance: lease renewal (45 s against the 90 s fail-open expiry —
        // a liveness requirement, not a timeout), idle keepalive, carrier text
        // refresh, subclass tick.
        scope.launch {
            while (isActive && running) {
                delay(if (instant) 50 else SettingsMsg.LEASE_RENEW_MS)
                if (!running) break
                try {
                    renewLease()
                } catch (e: Exception) {
                    Log.e(name, "lease renewal failed", e)
                    setLease(false, "renewal failed: ${e.message}")
                }
            }
        }
        scope.launch {
            while (isActive && running) {
                delay(if (instant) 50 else 4_000)
                if (!running) break
                if (nowMs() - lastImageAtMs > 4_000 || instant) {
                    try {
                        sendControl(EvenHubMsg.keepalive(nextMsgId()))
                    } catch (e: Exception) {
                        emitFault("keepalive", e.message ?: "keepalive failed")
                    }
                }
            }
        }
        scope.launch {
            while (isActive && running) {
                delay(if (instant) 50 else 30_000)
                if (!running) break
                try {
                    sendControl(EvenHubMsg.carrierTextUpgrade(nextMsgId()))
                } catch (e: Exception) {
                    emitFault("carrier", e.message ?: "carrier text refresh failed")
                }
            }
        }
        scope.launch {
            while (isActive && running) {
                delay(if (instant) 20 else 1_000)
                onMaintenanceTick()
            }
        }
    }

    protected open fun nowMs(): Long = System.currentTimeMillis()

    private suspend fun renewLease() {
        for (arm in Arm.entries) {
            val nonce = (nowMs() and 0xFFFF).toInt()
            writeSerialized(arm, AaFrame.frame(nextSeq(), SettingsMsg.SID,
                SettingsMsg.FLAG_REQUEST, SettingsMsg.fbAcquire(nonce)))
        }
        setLease(true, "FB lease acquired/renewed (both arms)")
    }

    override suspend fun submit(flush: FlushRequest): Long {
        check(_state.value.started) { "submit before start()" }
        val id = ++flushCounter
        // A wide flush (rects past the pipelined budget) drains the window and
        // runs at depth 1 — §8.2 #4: trade depth for rects on demand.
        val permits = if (flush.wide) WINDOW else 1
        repeat(permits) { window.acquire() }
        _state.value = _state.value.copy(inFlight = WINDOW - window.availablePermits)
        scope.launch {
            val t0 = nowMs()
            try {
                val encoded = Emit.encode(flush, fids, tracker,
                    window = if (flush.wide) 1 else WINDOW)
                sendImage(encoded.image)
                afterImageDelivered()
                val ackMs = nowMs() - t0
                updateEma(ackMs, encoded.image.size)
                _events.emit(TransportEvent.FlushDone(id, true, ackMs, encoded.image.size))
                if (fids.wrapPending) {
                    // §8.2 #6: the deliberate 0xFFFE -> 1 wrap trips f_skip once;
                    // clear the flags so the panic path stays meaningful.
                    clearDiagFlags()
                    fids.clearWrap()
                }
            } catch (e: Exception) {
                nextSession(afterFailure = true)
                _events.emit(TransportEvent.FlushDone(id, false, nowMs() - t0, 0,
                    e.message ?: e.toString()))
            } finally {
                repeat(permits) { window.release() }
                _state.value = _state.value.copy(inFlight = WINDOW - window.availablePermits)
            }
        }
        return id
    }

    /** Hook after a flush's image fully acks (SimTransport reads diag flags). */
    protected open suspend fun afterImageDelivered() {}

    override suspend fun clearDiagFlags() {
        sendImage(byteArrayOf(7, 0))
    }

    override suspend fun stop() {
        running = false
        try {
            for (arm in Arm.entries) {
                writeSerialized(arm, AaFrame.frame(nextSeq(), SettingsMsg.SID,
                    SettingsMsg.FLAG_REQUEST, SettingsMsg.fbRelease(0)))
            }
        } catch (e: Exception) {
            Log.w(name, "lease release on stop failed: ${e.message}")
        }
        disconnectLink()
        _state.value = _state.value.copy(started = false, leaseHeld = false, connected = false)
        _events.emit(TransportEvent.Link(false, "$name stopped"))
    }

    // ------------------------------------------------------------------ plumbing
    /** One CFW image buffer -> sequential ImgRawMsg fragments (<=3800 B), bulk
     *  to the LEFT arm, each fragment ack-gated (the pipeline window rides at
     *  the flush level; fragments of one message stay strictly ordered). */
    protected suspend fun sendImage(image: ByteArray) {
        writeMutex.withLock {
            lastImageAtMs = nowMs()
            val sess = nextSession()
            var index = 0
            var off = 0
            while (off < image.size) {
                val end = minOf(off + Geometry.MAX_IMAGE_FRAGMENT, image.size)
                val id = nextMsgId()
                val msg = EvenHubMsg.imageFragment(id, sess, image.size, index,
                    image.copyOfRange(off, end))
                val ch = Channel<EvenHubMsg.Ack>(1)
                pendingAcks[id] = ch
                writeFrames(Arm.LEFT, AaFrame.frame(nextSeq(), EvenHubMsg.SID,
                    EvenHubMsg.FLAG_REQUEST, msg))
                // Ack-driven, no timeout: a lost ack surfaces as a stalled
                // window in the status bar, loudly — never a silent skip.
                val ack = ch.receive()
                pendingAcks.remove(id)
                if (ack.errorCode != null) {
                    nextSession(afterFailure = true)
                    throw LintError("ImgResCmd.ErrorCode=${ack.errorCode} on fragment $index — " +
                        "session bumped; damage will be recomputed with a fresh fid")
                }
                off = end
                index++
            }
        }
    }

    /** One control EvenHub message to the RIGHT arm, awaiting its ack. */
    protected suspend fun sendControl(payload: ByteArray) {
        writeMutex.withLock {
            val id = (wm.damage.core.wire.Pb.varintField(payload, 2) ?: 0L).toInt()
            val ch = Channel<EvenHubMsg.Ack>(1)
            pendingAcks[id] = ch
            writeFrames(Arm.RIGHT, AaFrame.frame(nextSeq(), EvenHubMsg.SID,
                EvenHubMsg.FLAG_REQUEST, payload))
            ch.receive()
            pendingAcks.remove(id)
        }
    }

    private suspend fun writeSerialized(arm: Arm, packets: List<ByteArray>) {
        writeMutex.withLock { writeFrames(arm, packets) }
    }

    private suspend fun writeFrames(arm: Arm, packets: List<ByteArray>) {
        for (p in packets) writeArm(arm, p)
    }

    private fun updateEma(ackMs: Long, bytes: Int) {
        val s = _state.value
        val a = 0.3
        _state.value = s.copy(
            ackMsEma = s.ackMsEma * (1 - a) + ackMs * a,
            bytesPerSecEma = if (ackMs > 0)
                s.bytesPerSecEma * (1 - a) + (bytes * 1000.0 / ackMs) * a else s.bytesPerSecEma,
        )
    }

    companion object {
        /** Faceclaw ships WINDOW_SIZE = 3 on exactly the CFW path (§8.1). */
        const val WINDOW = 3
    }
}
