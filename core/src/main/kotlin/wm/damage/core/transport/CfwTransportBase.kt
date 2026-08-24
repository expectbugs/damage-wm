package wm.damage.core.transport

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
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
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg

/**
 * The CFW transport choreography, sans-IO: capability gate, carrier CREATE,
 * FB lease on both arms with 45 s renewal, the sacrificial warmup, idle
 * keepalive, msgId/MapSessionId/fid discipline, <=3800 B fragmenting, the
 * pipelined message window, and ack routing. Subclasses supply only the wire
 * ([writeArm], [connectLink]/[disconnectLink]) and feed notifications to
 * [onNotifyPacket] — SimTransport and the phone's BleTransport run the SAME
 * protocol brain the selfcheck exercises.
 *
 * Concurrency model (rebuilt after review round 1 found the original racing
 * every counter and ack-gating each fragment — the forbidden pattern):
 *
 *  - TWO worker lanes. The IMAGE lane owns fids and MapSessionId and writes
 *    flushes strictly in submission order (fid order == wire order by
 *    construction). The CONTROL lane carries lease renewal, keepalive and the
 *    carrier refresh — it never touches the ack window, so a wedged ack stream
 *    can stall pixels but can never cost the lease.
 *  - [wire] is a mutex held per MESSAGE write (all AA packets of one EvenHub
 *    message): the two lanes may interleave between messages — legal, each
 *    message reassembles independently — but never inside one. msgId and the
 *    AA seq are allocated under the same mutex, so counter order matches wire
 *    order.
 *  - Each image fragment message takes a window slot ([WINDOW]=3) and frees it
 *    when its ack arrives (any thread; the pending map is concurrent) — real
 *    pipelining per Faceclaw's exercised CFW path. Flush completion is the
 *    FINAL fragment's ack, handled asynchronously so the image lane moves
 *    straight on to the next flush.
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

    protected val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
    override val events = _events.asSharedFlow()

    private val _state = MutableStateFlow(LinkState(transportName = name))
    override val state = _state.asStateFlow()
    private val stateLock = Any()

    /** All read-copy-update on the state flow goes through here — plain
     *  `.value = .value.copy()` from two coroutines loses updates. */
    protected fun updateState(f: (LinkState) -> LinkState) {
        synchronized(stateLock) { _state.value = f(_state.value) }
    }

    // ------------------------------------------------------------------ queues
    private sealed class ImgWork {
        class Flush(val id: Long, val request: FlushRequest) : ImgWork()
        class Raw(val image: ByteArray, val done: CompletableDeferred<Unit>?) : ImgWork()
    }

    private sealed class CtlWork {
        class Hub(val payload: ByteArray, val awaitAck: CompletableDeferred<EvenHubMsg.Ack>?) : CtlWork()
        class Settings(val payload: ByteArray) : CtlWork()
        class Lease(val op: Int) : CtlWork()
    }

    private val imageQueue = Channel<ImgWork>(Channel.UNLIMITED)
    private val controlQueue = Channel<CtlWork>(Channel.UNLIMITED)
    private val flushIds = AtomicLong(0)

    /** In-flight un-acked image messages; slots free on ack. */
    private val window = Semaphore(WINDOW)

    /** Held per MESSAGE write; also guards msgId/aaSeq/session allocation. */
    private val wire = Mutex()

    // wire-mutex-confined counters
    private var msgId = 0
    private var aaSeq = 0
    private var session = 1
    @Volatile private var sessionPenalty = false

    // image-lane-confined
    private val fids = FidAllocator()
    private val tracker = FidTracker()

    @Volatile protected var lastImageAtMs = 0L
    @Volatile protected var running = false
    private var started = false

    private class PendingAck(val flushId: Long, val windowed: Boolean) {
        val done = CompletableDeferred<EvenHubMsg.Ack>()
    }

    /** msgId -> pending. Written under [wire], completed by the notify thread. */
    private val pendingAcks = ConcurrentHashMap<Int, PendingAck>()

    private val capabilityChannel = Channel<String>(Channel.CONFLATED)
    private val reassemblers = mapOf(
        Arm.LEFT to AaFrame.Reassembler { Log.w(name, "L: $it") },
        Arm.RIGHT to AaFrame.Reassembler { Log.w(name, "R: $it") },
    )

    // ------------------------------------------------------------------ wire seam
    /** Write one AA packet to [arm]. Callers hold [wire]; implementations need
     *  no ordering of their own. */
    protected abstract suspend fun writeArm(arm: Arm, packet: ByteArray)

    /** Bring the physical link up; notifications must then reach [onNotifyPacket]. */
    protected abstract suspend fun connectLink()

    protected abstract suspend fun disconnectLink()

    /** Called on each maintenance tick — subclasses report lease/link extras. */
    protected open fun onMaintenanceTick() {}

    /** Hook awaiting/modeling one image message's ack round trip — runs in the
     *  async completion path, AFTER the final fragment ack arrives (or, for the
     *  sim, models the arrival delay itself). */
    protected open suspend fun onImageDelivered() {}

    protected open val instant: Boolean = false

    protected open fun nowMs(): Long = System.currentTimeMillis()

    /** Subclasses feed every notification packet here. Thread-safe. */
    protected fun onNotifyPacket(arm: Arm, packet: ByteArray) {
        val frame = synchronized(reassemblers) { reassemblers.getValue(arm).offer(packet) } ?: return
        when (frame.sid) {
            EvenHubMsg.SID -> when (frame.flag) {
                EvenHubMsg.FLAG_ACK -> {
                    val ack = EvenHubMsg.parseAck(frame.payload)
                    if (ack == null) {
                        // NO SILENT FAILURES: an unparseable ack means a pending
                        // message may now wait forever — say so.
                        Log.e(name, "unparseable e0 ack (${frame.payload.size} B) — a message may stall")
                        emitFault("ack", "unparseable ack payload")
                        return
                    }
                    val pending = pendingAcks.remove(ack.msgId)
                    if (pending == null) {
                        Log.w(name, "ack for unknown msgId ${ack.msgId} (late or duplicate)")
                        return
                    }
                    if (pending.windowed) {
                        window.release()
                        updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
                    }
                    if (ack.errorCode != null && !pending.done.isCompleted) {
                        emitFault("imgres", "ErrorCode=${ack.errorCode} on msgId ${ack.msgId}")
                    }
                    pending.done.complete(ack)
                }
                EvenHubMsg.FLAG_EVENT -> routeEvent(frame.payload)
                EvenHubMsg.FLAG_ABORT ->
                    emitFault("abort", "e0-02 reassembly abort from glasses")
            }
            SettingsMsg.SID -> {
                val cap = SettingsMsg.parseCapability(frame.payload)
                if (cap != null) {
                    capabilityChannel.trySend(cap)
                } else if (frame.flag == SettingsMsg.FLAG_RESPONSE) {
                    // A settings response WITHOUT the EVENCFW field IS the answer
                    // (stock firmware): forward emptiness so the gate refuses
                    // loudly instead of hanging in silence.
                    capabilityChannel.trySend("")
                }
            }
        }
    }

    private fun routeEvent(payload: ByteArray) {
        when (val ev = EvenHubMsg.parseEvent(payload)) {
            is EvenHubMsg.Event.Gesture -> emitInput(ev.type, ev.source)
            is EvenHubMsg.Event.TextEvent -> {
                // On the CFW carrier, ring scroll arrives as Text_ItemEvents on
                // the capture container (G2_BLE_PROTOCOL.md §6.6: "scrollUp/
                // scrollDn @nav" are text-region events; taps are sys events).
                // The wire carries no per-source byte for these (§6.6 note).
                when (ev.type) {
                    EvenHubMsg.EV_SCROLL_TOP, EvenHubMsg.EV_SCROLL_BOTTOM ->
                        emitInput(ev.type, EvenHubMsg.SRC_RING)
                    else -> Log.d(name, "text event ${ev.type} on '${ev.name}' ignored")
                }
            }
            is EvenHubMsg.Event.ListSelect ->
                Log.w(name, "list event on the carrier (container '${ev.name}') — unexpected, ignored")
            null -> Log.w(name, "unparseable e0-01 event (${payload.size} B)")
        }
    }

    private fun emitInput(type: Int, source: Int) {
        if (!_events.tryEmit(TransportEvent.Input(type, source))) {
            Log.e(name, "input event buffer overflow — a gesture was DROPPED")
        }
    }

    protected fun emitFault(what: String, detail: String) {
        if (!_events.tryEmit(TransportEvent.Fault(what, detail))) {
            Log.e(name, "FAULT DROPPED (buffer full): $what: $detail")
        }
    }

    protected fun emitFlags(flags: Map<String, Boolean>) {
        if (flags.any { it.value }) _events.tryEmit(TransportEvent.DiagFlags(flags))
    }

    protected fun setLease(held: Boolean, detail: String) {
        var changed = false
        synchronized(stateLock) {
            if (_state.value.leaseHeld != held) {
                _state.value = _state.value.copy(leaseHeld = held)
                changed = true
            }
        }
        if (changed) _events.tryEmit(TransportEvent.Lease(held, detail))
    }

    // ------------------------------------------------------------------ lifecycle
    override suspend fun start(warmupFrame: ByteArray) {
        check(!started) { "transport already started — a second driver must stop() first" }
        started = true
        running = true
        connectLink()
        updateState { it.copy(connected = true) }
        _events.emit(TransportEvent.Link(true, "$name link up"))

        scope.launch { imageLane() }
        scope.launch { controlLane() }

        // 1. Capability gate (§9.2b): field 100 rides the settings READ response
        //    itself — no timeout needed by construction; an ABSENT field is a
        //    loud refusal (see onNotifyPacket).
        controlQueue.trySend(CtlWork.Settings(SettingsMsg.settingsQuery(0)))
        val cap = capabilityChannel.receive()
        val missing = SettingsMsg.missingCaps(cap)
        if (cap.isEmpty() || missing.isNotEmpty()) {
            val msg = if (cap.isEmpty())
                "capability gate FAILED: no EVENCFW string — this is NOT the CFW; refusing to paint"
            else "capability gate FAILED: '$cap' missing $missing — refusing to paint"
            _events.emit(TransportEvent.Fault("capability", msg))
            running = false
            throw LintError(msg)
        }
        updateState { it.copy(capability = cap) }

        // 2. Carrier CREATE — image container + the full-screen dummy text
        //    container that is the event antenna (overview.md §4.1).
        val createAck = CompletableDeferred<EvenHubMsg.Ack>()
        controlQueue.trySend(CtlWork.Hub(EvenHubMsg.carrierCreate(0), createAck))
        val created = createAck.await()
        if (created.errorCode != null)
            throw LintError("carrier CREATE rejected: ErrorCode=${created.errorCode}")

        // 3. FB lease, BOTH arms (display_copy_hook runs per lens).
        controlQueue.trySend(CtlWork.Lease(SettingsMsg.OP_FB_ACQUIRE))

        // 4. The sacrificial warmup frame — the firmware silently drops the
        //    first burst after CREATE (§5.17: make it the splash).
        val warmupDone = CompletableDeferred<Unit>()
        imageQueue.trySend(ImgWork.Raw(warmupFrame, warmupDone))
        warmupDone.await()

        updateState { it.copy(started = true, leaseHeld = true) }

        // Maintenance: lease renewal (45 s against the 90 s fail-open expiry —
        // a liveness requirement, not a timeout), idle keepalive, carrier text
        // refresh, subclass tick. All enqueue on the CONTROL lane, which never
        // blocks on the ack window — a stalled flush cannot cost the lease.
        scope.launch {
            while (isActive && running) {
                delay(if (instant) 50 else SettingsMsg.LEASE_RENEW_MS)
                if (running) controlQueue.trySend(CtlWork.Lease(SettingsMsg.OP_FB_ACQUIRE))
            }
        }
        scope.launch {
            while (isActive && running) {
                delay(if (instant) 50 else 4_000)
                if (running && (nowMs() - lastImageAtMs > 4_000 || instant)) {
                    controlQueue.trySend(CtlWork.Hub(EvenHubMsg.keepalive(0), null))
                }
            }
        }
        scope.launch {
            while (isActive && running) {
                delay(if (instant) 50 else 30_000)
                if (running) controlQueue.trySend(CtlWork.Hub(EvenHubMsg.carrierTextUpgrade(0), null))
            }
        }
        scope.launch {
            while (isActive && running) {
                delay(if (instant) 20 else 1_000)
                onMaintenanceTick()
            }
        }
    }

    /** Non-blocking: the flush enters the image lane's queue in CALL order,
     *  which is what makes fid order == wire order. Backpressure is
     *  [LinkState.inFlight] against [LinkState.window] (the shell's pump gates
     *  on it) plus the window semaphore inside the lane. */
    override suspend fun submit(flush: FlushRequest): Long {
        check(_state.value.started) { "submit before start()" }
        val id = flushIds.incrementAndGet()
        imageQueue.trySend(ImgWork.Flush(id, flush))
        return id
    }

    override suspend fun clearDiagFlags() {
        imageQueue.trySend(ImgWork.Raw(byteArrayOf(7, 0), null))
    }

    override suspend fun stop() {
        running = false
        started = false
        controlQueue.trySend(CtlWork.Lease(SettingsMsg.OP_FB_RELEASE))
        // let the control lane drain the release before dropping the link
        delay(if (instant) 20 else 200)
        try {
            disconnectLink()
        } catch (e: Exception) {
            Log.w(name, "disconnect: ${e.message}")
        }
        updateState { it.copy(started = false, leaseHeld = false, connected = false) }
        _events.emit(TransportEvent.Link(false, "$name stopped"))
    }

    // ------------------------------------------------------------------ image lane
    private suspend fun imageLane() {
        for (work in imageQueue) {
            if (!running) continue
            try {
                when (work) {
                    is ImgWork.Flush -> laneFlush(work)
                    is ImgWork.Raw -> {
                        val final = writeImage(work.image)
                        completeAsync(final, flushId = -1, bytes = work.image.size,
                            t0 = nowMs(), done = work.done)
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "image lane error", e)
                when (work) {
                    is ImgWork.Flush -> _events.emit(TransportEvent.FlushDone(
                        work.id, false, 0, 0, e.message ?: e.toString()))
                    is ImgWork.Raw -> work.done?.completeExceptionally(e)
                }
            }
        }
    }

    private suspend fun laneFlush(work: ImgWork.Flush) {
        val t0 = nowMs()
        try {
            if (work.request.wide) {
                // §8.2 #4: a wide flush drains the pipeline and runs at depth 1.
                repeat(WINDOW) { window.acquire() }
                repeat(WINDOW) { window.release() }
            }
            val encoded = Emit.encode(work.request, fids, tracker,
                window = if (work.request.wide) 1 else WINDOW)
            val final = writeImage(encoded.image)
            if (fids.wrapPending) {
                // §8.2 #6: the deliberate 0xFFFE -> 1 wrap trips f_skip once;
                // clear the flags in-lane so the panic path stays meaningful.
                writeImage(byteArrayOf(7, 0))
                fids.clearWrap()
            }
            completeAsync(final, work.id, encoded.image.size, t0, done = null)
        } catch (e: Exception) {
            sessionPenalty = true
            _events.emit(TransportEvent.FlushDone(work.id, false, nowMs() - t0, 0,
                e.message ?: e.toString()))
        }
    }

    /** Await the final ack off-lane so the lane pipelines the next flush. */
    private fun completeAsync(
        final: PendingAck?, flushId: Long, bytes: Int, t0: Long,
        done: CompletableDeferred<Unit>?,
    ) {
        if (final == null) {
            done?.complete(Unit)
            if (flushId >= 0) _events.tryEmit(TransportEvent.FlushDone(flushId, true, 0, bytes))
            return
        }
        scope.launch {
            try {
                val ack = final.done.await()
                onImageDelivered()
                val ackMs = nowMs() - t0
                if (ack.errorCode != null) {
                    sessionPenalty = true
                    done?.completeExceptionally(LintError("ImgResCmd.ErrorCode=${ack.errorCode}"))
                    if (flushId >= 0) _events.emit(TransportEvent.FlushDone(flushId, false, ackMs,
                        bytes, "ImgResCmd.ErrorCode=${ack.errorCode} — damage recomputes with a fresh fid"))
                } else {
                    updateEma(ackMs, bytes)
                    done?.complete(Unit)
                    if (flushId >= 0) _events.emit(TransportEvent.FlushDone(flushId, true, ackMs, bytes))
                }
            } catch (e: Exception) {
                done?.completeExceptionally(e)
                if (flushId >= 0) _events.emit(TransportEvent.FlushDone(flushId, false,
                    nowMs() - t0, bytes, e.message ?: e.toString()))
            }
        }
    }

    /**
     * One CFW image buffer -> sequential ImgRawMsg fragment messages (<=3800 B),
     * bulk to the LEFT arm. Fragments write back-to-back, each holding a window
     * slot until its ack arrives — up to WINDOW un-acked messages ride the link.
     * Returns the FINAL fragment's pending ack (flush completion).
     */
    private suspend fun writeImage(image: ByteArray): PendingAck? {
        require(image.isNotEmpty()) { "empty image buffer" }
        lastImageAtMs = nowMs()
        if (sessionPenalty) {
            sessionPenalty = false
            wire.withLock { nextSessionLocked(afterFailure = true) }
        }
        val sess = wire.withLock { nextSessionLocked(afterFailure = false) }
        var index = 0
        var off = 0
        var finalPending: PendingAck? = null
        while (off < image.size) {
            val end = minOf(off + Geometry.MAX_IMAGE_FRAGMENT, image.size)
            window.acquire()
            updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
            try {
                wire.withLock {
                    val id = nextMsgIdLocked()
                    val msg = EvenHubMsg.imageFragment(id, sess, image.size, index,
                        image.copyOfRange(off, end))
                    val pending = PendingAck(-1, windowed = true)
                    pendingAcks[id] = pending
                    if (end == image.size) finalPending = pending
                    for (p in AaFrame.frame(nextSeqLocked(), EvenHubMsg.SID,
                            EvenHubMsg.FLAG_REQUEST, msg)) {
                        writeArm(Arm.LEFT, p)
                    }
                }
            } catch (e: Exception) {
                window.release()
                throw e
            }
            off = end
            index++
        }
        return finalPending
    }

    // wire-mutex-confined counter helpers (callers hold `wire`)
    private fun nextMsgIdLocked(): Int { msgId = (msgId + 1) % 250; return msgId }
    private fun nextSeqLocked(): Int { aaSeq = (aaSeq + 1) and 0xFF; return aaSeq }
    private fun nextSessionLocked(afterFailure: Boolean): Int {
        session = (session + if (afterFailure) 3 else 1) % 250
        if (session == 0) session = 1
        return session
    }

    // ------------------------------------------------------------------ control lane
    private suspend fun controlLane() {
        for (work in controlQueue) {
            try {
                when (work) {
                    is CtlWork.Hub -> {
                        val pending = PendingAck(-1, windowed = false)
                        wire.withLock {
                            val id = nextMsgIdLocked()
                            pendingAcks[id] = pending
                            val payload = restampMsgId(work.payload, id)
                            for (p in AaFrame.frame(nextSeqLocked(), EvenHubMsg.SID,
                                    EvenHubMsg.FLAG_REQUEST, payload)) {
                                writeArm(Arm.RIGHT, p)
                            }
                        }
                        if (work.awaitAck != null) {
                            work.awaitAck.complete(pending.done.await())
                        } else {
                            scope.launch {
                                val ack = pending.done.await()
                                if (ack.errorCode != null)
                                    emitFault("control", "ErrorCode=${ack.errorCode}")
                            }
                        }
                    }
                    is CtlWork.Settings -> wire.withLock {
                        val payload = restampMsgId(work.payload, nextMsgIdLocked())
                        for (p in AaFrame.frame(nextSeqLocked(), SettingsMsg.SID,
                                SettingsMsg.FLAG_REQUEST, payload)) {
                            writeArm(Arm.RIGHT, p)
                        }
                    }
                    is CtlWork.Lease -> {
                        // Fire-and-forget by design (settings_ext.c: MagicRandom 0
                        // is consumed before the stock decoder discards the field)
                        // — pure writes, can never wedge behind a stuck ack.
                        wire.withLock {
                            for (arm in Arm.entries) {
                                val nonce = (nowMs() and 0xFFFF).toInt()
                                for (p in AaFrame.frame(nextSeqLocked(), SettingsMsg.SID,
                                        SettingsMsg.FLAG_REQUEST, SettingsMsg.control(work.op, nonce))) {
                                    writeArm(arm, p)
                                }
                            }
                        }
                        if (work.op == SettingsMsg.OP_FB_ACQUIRE)
                            setLease(true, "FB lease acquired/renewed (both arms)")
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "control lane error", e)
                if (work is CtlWork.Hub) work.awaitAck?.completeExceptionally(e)
                emitFault("control", e.message ?: e.toString())
            }
        }
    }

    /** Rebuild an EvenHub/settings payload with field 2 = [id]. Payloads are
     *  built with msgId 0 by callers; the lanes own the real counter. */
    private fun restampMsgId(payload: ByteArray, id: Int): ByteArray {
        val fields = Pb.fields(payload)
        val parts = ArrayList<ByteArray>(fields.size)
        for (f in fields) {
            parts += when {
                f.field == 2 && f.varint != null -> Pb.v(2, id)
                f.varint != null -> Pb.v(f.field, f.varint)
                else -> Pb.l(f.field, f.bytes!!)
            }
        }
        return Pb.cat(parts)
    }

    private fun updateEma(ackMs: Long, bytes: Int) {
        updateState { s ->
            val a = 0.3
            s.copy(
                ackMsEma = s.ackMsEma * (1 - a) + ackMs * a,
                bytesPerSecEma = if (ackMs > 0)
                    s.bytesPerSecEma * (1 - a) + (bytes * 1000.0 / ackMs) * a else s.bytesPerSecEma,
            )
        }
    }

    companion object {
        /** Faceclaw ships WINDOW_SIZE = 3 on exactly the CFW path (§8.1). */
        const val WINDOW = 3
    }
}
