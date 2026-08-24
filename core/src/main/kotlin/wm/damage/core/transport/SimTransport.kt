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
import wm.damage.core.geom.LintError
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.util.Log
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.SettingsMsg

/**
 * Transport over the byte-exact firmware model. Runs the SAME bring-up the BLE
 * transport will run on real glasses — capability gate, carrier CREATE, warmup,
 * FB lease on both arms with 45 s renewal — and the same emit path (Emit +
 * fragmenting + msgId/session discipline), with link timing modeled from the
 * measured numbers (176 ms ack, ~11 KB/s) so the scheduler and telemetry behave
 * realistically. [Timing.instant] turns modeling off for tests.
 *
 * Reference arm split (overview.md §2, graded strong-not-proven): bulk pixels ->
 * LEFT, control -> RIGHT; events and acks arrive on RIGHT.
 */
class SimTransport(
    private val sim: GlassFirmwareSim,
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Transport {

    data class Timing(
        val ackMs: Long = 176,
        val bytesPerSec: Double = 11_000.0,
        val instant: Boolean = false,
    )

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    override val events = _events.asSharedFlow()

    private val _state = MutableStateFlow(LinkState(transportName = "sim"))
    override val state = _state.asStateFlow()

    private val window = Semaphore(3)
    private val writeMutex = Mutex()          // multi-fragment messages must never interleave
    private val fids = FidAllocator()
    private val tracker = FidTracker()
    private var msgId = 0
    private var session = 1
    private var aaSeq = 0
    private var flushCounter = 0L
    @Volatile private var lastImageAtMs = 0L
    @Volatile private var running = false

    /** msgId cycles inside one byte — the glasses go silent past 255
     *  (G2_BLE_PROTOCOL.md §3). We wrap early at 250 for margin. */
    private fun nextMsgId(): Int { msgId = (msgId + 1) % 250; return msgId }

    private fun nextSeq(): Int { aaSeq = (aaSeq + 1) and 0xFF; return aaSeq }

    /** Bump normally per push; jump by 3 after any failure — the stuck-session
     *  trap wants >=2 (overview.md §9.2/§9.4). */
    private fun nextSession(afterFailure: Boolean = false): Int {
        session = (session + if (afterFailure) 3 else 1) % 250
        if (session == 0) session = 1
        return session
    }

    private val pendingAcks = HashMap<Int, Channel<EvenHubMsg.Ack>>()

    init {
        // Route everything the sim notifies straight into transport events.
        sim.attachListener(object : GlassFirmwareSim.SimDiag {
            override fun event(kind: String, detail: String) {
                Log.w("sim/$kind", detail)
                if (kind in setOf("decode", "fid", "session", "msgid", "compressmode", "lease"))
                    _events.tryEmit(TransportEvent.Fault(kind, detail))
            }

            override fun notify(arm: GlassFirmwareSim.Arm, packet: ByteArray) {
                onNotify(packet)
            }

            override fun panelChanged(arm: GlassFirmwareSim.Arm) {}
        })
    }

    private val reassembler = AaFrame.Reassembler { Log.w("sim-transport", it) }

    private fun onNotify(packet: ByteArray) {
        val frame = reassembler.offer(packet) ?: return
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
                val cap = SettingsMsg.parseCapability(frame.payload)
                if (cap != null) capabilityChannel.trySend(cap)
            }
        }
    }

    private val capabilityChannel = Channel<String>(1)

    override suspend fun start(warmupFrame: ByteArray) {
        running = true
        _state.value = _state.value.copy(connected = true)
        _events.emit(TransportEvent.Link(true, "sim link up"))

        // 1. Capability gate — the EVENCFW string must carry every required token
        //    (DESIGN.md §9.2b). No timeout needed: field 100 is in the settings
        //    READ response itself.
        writePacketsControl(AaFrame.frame(nextSeq(), SettingsMsg.SID, SettingsMsg.FLAG_REQUEST,
            SettingsMsg.settingsQuery(nextMsgId())))
        val cap = capabilityChannel.receive()
        val missing = SettingsMsg.missingCaps(cap)
        if (missing.isNotEmpty()) {
            val msg = "capability gate FAILED: '$cap' missing $missing — refusing to paint"
            _events.emit(TransportEvent.Fault("capability", msg))
            throw LintError(msg)
        }
        _state.value = _state.value.copy(capability = cap)

        // 2. Carrier CREATE — image container + full-screen dummy text container.
        sendControlMessage(EvenHubMsg.carrierCreate(nextMsgId()))

        // 3. FB lease, BOTH arms (display_copy_hook runs per lens).
        renewLease()

        // 4. The sacrificial warmup frame — the firmware silently drops the first
        //    burst after CREATE, so this splash is expected to vanish (§5.17).
        sendImage(EvenHubMsg.IMG_CONTAINER_ID, warmupFrame)

        _state.value = _state.value.copy(started = true, leaseHeld = true)

        // Maintenance loops: lease renewal (45 s against the 90 s expiry — a
        // liveness requirement, not a timeout), idle keepalive, carrier text
        // refresh, and sim time advance.
        scope.launch {
            while (isActive && running) {
                delay(if (timing.instant) 50 else SettingsMsg.LEASE_RENEW_MS)
                if (!running) break
                renewLease()
            }
        }
        scope.launch {
            while (isActive && running) {
                delay(if (timing.instant) 50 else 4_000)
                if (!running) break
                if (clock() - lastImageAtMs > 4_000 || timing.instant) {
                    try {
                        sendControlMessage(EvenHubMsg.keepalive(nextMsgId()))
                    } catch (e: LintError) {
                        _events.emit(TransportEvent.Fault("keepalive", e.message ?: "keepalive failed"))
                    }
                }
            }
        }
        scope.launch {
            while (isActive && running) {
                delay(if (timing.instant) 50 else 30_000)
                if (!running) break
                sendControlMessage(EvenHubMsg.carrierTextUpgrade(nextMsgId()))
            }
        }
        scope.launch {
            while (isActive && running) {
                delay(if (timing.instant) 20 else 1_000)
                sim.tick(clock())
                val held = sim.leaseHeld(GlassFirmwareSim.Arm.LEFT, clock()) &&
                    sim.leaseHeld(GlassFirmwareSim.Arm.RIGHT, clock())
                if (held != _state.value.leaseHeld) {
                    _state.value = _state.value.copy(leaseHeld = held)
                    _events.emit(TransportEvent.Lease(held,
                        if (held) "lease held" else "lease LOST — stock repaints (fail-open)"))
                }
            }
        }
    }

    private suspend fun renewLease() {
        for (arm in GlassFirmwareSim.Arm.entries) {
            val nonce = (clock() and 0xFFFF).toInt()
            writePackets(arm, AaFrame.frame(nextSeq(), SettingsMsg.SID, SettingsMsg.FLAG_REQUEST,
                SettingsMsg.fbAcquire(nonce)))
        }
        if (!_state.value.leaseHeld) {
            _state.value = _state.value.copy(leaseHeld = true)
            _events.emit(TransportEvent.Lease(true, "FB lease acquired (both arms)"))
        }
    }

    override suspend fun submit(flush: FlushRequest): Long {
        check(_state.value.started) { "submit before start()" }
        val id = ++flushCounter
        window.acquire()
        _state.value = _state.value.copy(inFlight = 3 - window.availablePermits)
        scope.launch {
            val t0 = clock()
            try {
                val encoded = Emit.encode(flush, fids, tracker, window = 3)
                sendImage(EvenHubMsg.IMG_CONTAINER_ID, encoded.image)
                if (!timing.instant) delay(timing.ackMs)
                val ackMs = clock() - t0
                updateEma(ackMs, encoded.image.size)
                _events.emit(TransportEvent.FlushDone(id, true, ackMs, encoded.image.size))
                emitFlags()
                if (fids.wrapPending) {
                    // §8.2 #6: the deliberate 0xFFFE -> 1 wrap trips f_skip once;
                    // clear the flags so the panic path stays meaningful.
                    clearDiagFlags()
                    fids.clearWrap()
                }
            } catch (e: Exception) {
                nextSession(afterFailure = true)
                _events.emit(TransportEvent.FlushDone(id, false, clock() - t0, 0,
                    e.message ?: e.toString()))
            } finally {
                window.release()
                _state.value = _state.value.copy(inFlight = 3 - window.availablePermits)
            }
        }
        return id
    }

    override suspend fun clearDiagFlags() {
        sendImage(EvenHubMsg.IMG_CONTAINER_ID, byteArrayOf(7, 0))
    }

    override suspend fun stop() {
        running = false
        for (arm in GlassFirmwareSim.Arm.entries) {
            writePackets(arm, AaFrame.frame(nextSeq(), SettingsMsg.SID, SettingsMsg.FLAG_REQUEST,
                SettingsMsg.fbRelease(0)))
        }
        _state.value = _state.value.copy(started = false, leaseHeld = false, connected = false)
        _events.emit(TransportEvent.Link(false, "sim transport stopped"))
    }

    // ------------------------------------------------------------------ plumbing
    /** Send one CFW image buffer as sequential ImgRawMsg fragments (<=3800 B
     *  each), bulk to the LEFT arm, awaiting the final fragment's ack. */
    private suspend fun sendImage(containerId: Int, image: ByteArray) {
        writeMutex.withLock {
            lastImageAtMs = clock()
            val sess = nextSession()
            val frags = image.toList().chunked(wm.damage.core.geom.Geometry.MAX_IMAGE_FRAGMENT)
            var lastAck: EvenHubMsg.Ack? = null
            for ((i, chunk) in frags.withIndex()) {
                val id = nextMsgId()
                val msg = EvenHubMsg.imageFragment(id, sess, image.size, i, chunk.toByteArray())
                val ch = Channel<EvenHubMsg.Ack>(1)
                pendingAcks[id] = ch
                writePackets(GlassFirmwareSim.Arm.LEFT,
                    AaFrame.frame(nextSeq(), EvenHubMsg.SID, EvenHubMsg.FLAG_REQUEST, msg))
                // Ack-driven, no timeout: the sim always answers; on hardware a
                // lost ack surfaces as a stalled window in the status bar.
                lastAck = ch.receive()
                pendingAcks.remove(id)
                if (lastAck.errorCode != null) {
                    nextSession(afterFailure = true)
                    throw LintError("ImgResCmd.ErrorCode=${lastAck.errorCode} on fragment $i — " +
                        "session bumped; recompute damage with a fresh fid")
                }
            }
        }
    }

    /** Send one control EvenHub message to the RIGHT arm and await its ack. */
    private suspend fun sendControlMessage(payload: ByteArray) {
        writeMutex.withLock {
            val id = (wm.damage.core.wire.Pb.varintField(payload, 2) ?: 0L).toInt()
            val ch = Channel<EvenHubMsg.Ack>(1)
            pendingAcks[id] = ch
            writePackets(GlassFirmwareSim.Arm.RIGHT,
                AaFrame.frame(nextSeq(), EvenHubMsg.SID, EvenHubMsg.FLAG_REQUEST, payload))
            ch.receive()
            pendingAcks.remove(id)
        }
    }

    private suspend fun writePacketsControl(packets: List<ByteArray>) =
        writePackets(GlassFirmwareSim.Arm.RIGHT, packets)

    private suspend fun writePackets(arm: GlassFirmwareSim.Arm, packets: List<ByteArray>) {
        for (p in packets) {
            if (!timing.instant) {
                val ms = (p.size / timing.bytesPerSec * 1000).toLong()
                if (ms > 0) delay(ms)
            }
            sim.write(arm, p, clock())
        }
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

    private fun emitFlags() {
        val l = sim.flags(GlassFirmwareSim.Arm.LEFT)
        val r = sim.flags(GlassFirmwareSim.Arm.RIGHT)
        val merged = (l.keys + r.keys).associateWith { (l[it] ?: false) || (r[it] ?: false) }
        if (merged.any { it.value }) _events.tryEmit(TransportEvent.DiagFlags(merged))
    }
}
