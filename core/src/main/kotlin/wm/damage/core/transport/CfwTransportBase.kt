package wm.damage.core.transport

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.util.Log
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.LaunchMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.RingMsg
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
 *    carrier refresh — it never touches the ack window, so a stalled ACK
 *    stream can freeze pixels but cannot cost the lease. (A stalled WRITE is
 *    different: [wire] is held per message across writeArm, so a GATT write
 *    that never returns blocks renewals too — the 90 s fail-open is the
 *    backstop there; nothing in software can reach a link that will not
 *    take a byte.)
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
 *
 * The mirror (HANDOFF.md §8.2): [mirrorSim] is a firmware model fed the exact
 * packets this transport writes — AFTER each write succeeds, so a failed write
 * leaves it untouched — and is what every replica draws. A subclass that IS a
 * firmware model (the sim transport) passes its own sim in and nothing is
 * teed; a hardware transport passes null and gets a private mirror. The
 * mirror's own notifications are discarded; a decode/fid/session event it
 * reports is the model predicting that the firmware would reject or skip our
 * bytes in silence, so it is surfaced as a `mirror/<kind>` fault.
 */
abstract class CfwTransportBase(
    protected val scope: CoroutineScope,
    private val name: String,
    mirrorSim: GlassFirmwareSim? = null,
) : Transport {

    /** The firmware model behind [mirror]. */
    protected val mirrorSim: GlassFirmwareSim = mirrorSim ?: GlassFirmwareSim()
    private val teeMirror = mirrorSim == null
    override val mirror: LensPanels get() = this.mirrorSim

    init {
        if (teeMirror) {
            this.mirrorSim.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {
                    when (kind) {
                        "decode", "fid", "session", "msgid", "compressmode", "abort", "image" ->
                            emitFault("mirror/$kind", detail)      // the model predicting a silent rejection
                        "lease", "warmup", "launch", "transport", "proto" ->
                            Log.i(name, "mirror/$kind: $detail")   // first-light narration, visible
                        else -> Log.d(name, "mirror/$kind: $detail")
                    }
                }
                override fun notify(arm: Arm, packet: ByteArray) {}   // the model's acks are not the glasses'
                override fun panelChanged(arm: Arm) {}
            })
        }
    }

    /** Write one AA packet and, once it went out, apply it to the mirror. Every
     *  packet this transport sends passes through here. */
    private suspend fun writePacket(arm: Arm, packet: ByteArray) {
        writeArm(arm, packet)
        if (teeMirror) mirrorSim.write(arm, packet, nowMs())
    }

    override fun injectInput(type: Int) = emitInput(type, EvenHubMsg.SRC_RING)

    override fun injectText(line: String) {
        if (!_events.tryEmit(TransportEvent.Text(line))) {
            Log.e(name, "typed-text event buffer overflow — a line was DROPPED")
        }
    }

    /** Panel brightness (2026-08-31): a sid-0x09 write on the control lane,
     *  fire-and-forget like the lease (msgId 0; the firmware's ack is not
     *  awaited — a lost write is corrected by the next push or session start). */
    override fun setBrightness(auto: Boolean, level: Int) {
        if (!_state.value.started) return
        Log.i(name, "brightness -> ${if (auto) "auto" else "$level"}")
        controlQueue.trySend(CtlWork.Settings(sessionEpoch.get(), SettingsMsg.brightnessWrite(0, auto, level)))
    }

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
    /** Every queued item is stamped with the session epoch it belongs to.
     *  Sessions end (stop, failed start, link death) by bumping the epoch and
     *  SWEEPING — lanes drop stale work LOUDLY, never execute it into the next
     *  driver's session (review round 3 D1/D8). */
    private sealed class ImgWork(val epoch: Long) {
        class Flush(epoch: Long, val id: Long, val request: FlushRequest) : ImgWork(epoch)
        class Raw(epoch: Long, val image: ByteArray, val done: CompletableDeferred<Unit>?) : ImgWork(epoch)
        /** Mode-7 sub-0: clears the firmware's flags, fid ring AND fid
         *  baseline — so the host's tracker and allocator resync with it
         *  (round 5 F1: a bare raw clear left them a full sequence ahead and
         *  the next delta manufactured the f_skip it was meant to clear). */
        class ClearDiag(epoch: Long) : ImgWork(epoch)
    }

    private sealed class CtlWork(val epoch: Long) {
        class Hub(epoch: Long, val payload: ByteArray, val awaitAck: CompletableDeferred<EvenHubMsg.Ack>?) : CtlWork(epoch)
        /** [failed] completes with the reason if the write never goes out —
         *  a gate waiting for this query's answer must not park forever on a
         *  write failure that ends no link (round 7 D1). */
        class Settings(epoch: Long, val payload: ByteArray, val failed: CompletableDeferred<String>? = null) : CtlWork(epoch)
        class Lease(epoch: Long, val op: Int, val written: CompletableDeferred<Unit>? = null) : CtlWork(epoch)
        /** The sid-0x01 connect prelude (LaunchMsg); [failed] completes with the
         *  reason if the write never goes out, like [Settings]. */
        class Launch(epoch: Long, val failed: CompletableDeferred<String>? = null) : CtlWork(epoch)
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
    private var fids = FidAllocator()
    private val tracker = FidTracker()

    /** Test hook: start the fid sequence near the wrap so a test can drive
     *  the 0xFFFE -> 1 boundary in a few flushes. Call before start(). */
    internal fun seedFidsForTest(start: Int) {
        fids = FidAllocator(start)
    }

    @Volatile protected var lastImageAtMs = 0L
    @Volatile private var lastImageAckAtMs = 0L
    @Volatile private var stallReported = false
    @Volatile protected var running = false
    @Volatile private var started = false
    /** Lanes and maintenance loops are launched exactly ONCE and survive
     *  stop/start cycles (they idle on `running`) — a second start() during a
     *  PC takeover must not double them: two image lanes racing Emit was
     *  review round 2's fid-corruption finding. */
    @Volatile private var workersLaunched = false

    /** The session epoch: bumped by start() and stop(). Work stamped with an
     *  older epoch is dropped loudly by the lanes (round 3 D1/D8). */
    private val sessionEpoch = AtomicLong(0)

    /** True only while start()'s capability gate is waiting — settings frames
     *  arriving at any other time must not poison the CONFLATED rendezvous
     *  for a future gate (round 3 observation: uncorrelated capability). */
    @Volatile private var awaitingCapability = false

    /** True from session entry until start() returns or fails: the sweep's
     *  gate-abort sentinel is owed for that whole span (round 5 F2). */
    @Volatile private var startInProgress = false

    /** This session asked for the FB lease (start() step 3): only then does a
     *  rollback release it. */
    @Volatile private var leaseRequested = false

    private class PendingAck(val flushId: Long, val windowed: Boolean) {
        val done = CompletableDeferred<EvenHubMsg.Ack>()
    }

    /** msgId -> pending. Written under [wire], completed by the notify thread. */
    private val pendingAcks = ConcurrentHashMap<Int, PendingAck>()

    private val capabilityChannel = Channel<String>(Channel.CONFLATED)

    /** The prelude rendezvous: the ack ("ok"), or the session-end marker. Only
     *  open while [awaitingPrelude]; [preludeMsgId] is stamped by the control
     *  lane under the wire mutex BEFORE the request is written, so the ack can
     *  never arrive before the id it must match is known. */
    private val preludeChannel = Channel<String>(Channel.CONFLATED)
    @Volatile private var awaitingPrelude = false
    @Volatile private var preludeMsgId = -1
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
                        lastImageAckAtMs = nowMs()
                        stallReported = false
                        updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
                    }
                    if (ack.errorCode != null && !pending.done.isCompleted) {
                        emitFault("imgres", "${ack.statusText} (${ack.errorCode}) on msgId ${ack.msgId}")
                    }
                    pending.done.complete(ack)
                }
                EvenHubMsg.FLAG_EVENT -> routeEvent(frame.payload)
                EvenHubMsg.FLAG_ABORT ->
                    emitFault("abort", "e0-02 reassembly abort from glasses")
            }
            LaunchMsg.SID -> {
                val hex = frame.payload.joinToString("") { "%02x".format(it) }
                if (!awaitingPrelude) {
                    Log.i(name, "sid-0x01 frame outside the prelude wait (flag 0x%02x): %s".format(frame.flag, hex))
                    return
                }
                val id = LaunchMsg.msgIdOf(frame.payload)
                if (LaunchMsg.isEvent(frame.flag)) {
                    // the reference resolves acks only on non-event flags; a
                    // matching id under an event flag would mean the flag
                    // assumption (graded U) is wrong — say so at first light
                    Log.w(name, "sid-0x01 EVENT frame during the prelude wait (flag 0x%02x, msgId %s, pending %d): %s"
                        .format(frame.flag, id, preludeMsgId, hex))
                    return
                }
                if (id == preludeMsgId) preludeChannel.trySend("ok")
                else Log.w(name, "sid-0x01 response for msgId $id while waiting for $preludeMsgId — ignored (flag 0x%02x): %s"
                    .format(frame.flag, hex))
            }
            SettingsMsg.SID -> {
                // Battery rides EVERY device-info response (payload f4.12/13 —
                // G2CC §10, capture-confirmed), the capability-gate answer and
                // the periodic poll alike: parse it before any gate check.
                SettingsMsg.parseBattery(frame.payload)?.let { (pct, chg) ->
                    emitBattery(glassesPct = pct, glassesCharging = chg)
                }
                if (!awaitingCapability) {
                    Log.d(name, "settings frame outside the capability gate ignored")
                    return
                }
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
            RingMsg.SID -> {
                // The ring data relay. Whether the glasses push RingRawData
                // unprompted is an open probe (CAPABILITIES.md §3) — read what
                // arrives, log what does not parse, request nothing.
                val pct = RingMsg.parseBattery(frame.payload)
                if (pct != null) emitBattery(ringPct = pct)
                else Log.i(name, "sid-0x91 ring relay without rawData battery: " +
                    frame.payload.take(24).joinToString("") { "%02x".format(it) })
            }
        }
    }

    private var lastLoggedGlassesPct = -1
    private var lastLoggedRingPct = -1

    private fun emitBattery(glassesPct: Int? = null, glassesCharging: Boolean? = null, ringPct: Int? = null) {
        // log CHANGES (the 60 s poll would otherwise repeat one line forever):
        // the first-light lesson — a telemetry path you cannot observe in the
        // log is undiagnosable when the cell stays blank
        if (glassesPct != null && glassesPct != lastLoggedGlassesPct) {
            lastLoggedGlassesPct = glassesPct
            Log.i(name, "battery: glasses $glassesPct%${if (glassesCharging == true) " (charging)" else ""}")
        }
        if (ringPct != null && ringPct != lastLoggedRingPct) {
            lastLoggedRingPct = ringPct
            Log.i(name, "battery: ring $ringPct% (sid-0x91 relay)")
        }
        if (!_events.tryEmit(TransportEvent.Battery(glassesPct, glassesCharging, ringPct))) {
            Log.w(name, "battery event dropped (buffer full): g=$glassesPct r=$ringPct")
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
        // Log every inbound gesture. At first light (2026-08-30) input did not work
        // and NOTHING in the log could say whether events were arriving and being
        // mishandled or never arriving at all — the two have completely different
        // causes and we could not tell them apart. An input path you cannot observe
        // is a silent failure even when every individual component is loud.
        Log.i(name, "input: ${EvenHubMsg.eventName(type)} (type $type, source $source)")
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
        if (flags.any { it.value } && !_events.tryEmit(TransportEvent.DiagFlags(flags))) {
            Log.e(name, "DiagFlags event DROPPED (buffer full): $flags")
        }
    }

    protected fun setLease(held: Boolean, detail: String) {
        var changed = false
        synchronized(stateLock) {
            if (_state.value.leaseHeld != held) {
                _state.value = _state.value.copy(leaseHeld = held)
                changed = true
            }
        }
        if (changed && !_events.tryEmit(TransportEvent.Lease(held, detail))) {
            Log.e(name, "Lease event DROPPED (buffer full): held=$held $detail")
        }
    }

    // ------------------------------------------------------------------ lifecycle
    override suspend fun start(warmupFrame: ByteArray) {
        check(!started) { "transport already started — a second driver must stop() first" }
        val epoch = sessionEpoch.incrementAndGet()
        started = true
        // the gate may be aborted by a sweep from the moment the session
        // begins (a link death DURING connect, round 5 F2) — drain any stale
        // residue first, then arm the abort path for the whole start
        capabilityChannel.tryReceive()
        preludeChannel.tryReceive()
        preludeMsgId = -1
        lastImageAtMs = nowMs()        // the keepalive counts from THIS session
        leaseRequested = false
        startInProgress = true
        try {
            connectLink()
            // `running` only once a link exists (round 8): the maintenance
            // loops would otherwise queue keepalives and renewals into a link
            // still being scanned for, each a loud fault and a stale pending
            running = true
            updateState { it.copy(connected = true) }
            _events.emit(TransportEvent.Link(true, "$name link up"))

            if (!workersLaunched) {
                workersLaunched = true
                scope.launch { imageLane() }
                scope.launch { controlLane() }
                launchMaintenance()
            }

            // 0. Connect prelude (HANDOFF.md §8.2, LaunchMsg): the CFW reference
            //    settles ~800 ms after both arms are up, sends one sid-0x01
            //    app-launch request and waits for its ack before the lease and
            //    the settings query. Same gate shape as the capability query:
            //    the ack, the session-end marker, or the write failing — every
            //    way it can end is a completion here; nothing is time-bounded.
            updateState { it.copy(detail = "connect prelude") }
            if (!instant) delay(PRELUDE_SETTLE_MS)
            awaitingPrelude = true
            val pre = try {
                val writeFailed = CompletableDeferred<String>()
                controlQueue.trySend(CtlWork.Launch(epoch, writeFailed))
                kotlinx.coroutines.selects.select<String> {
                    preludeChannel.onReceive { it }
                    writeFailed.onAwait { reason -> SWEPT + "prelude not written: $reason" }
                }
            } finally {
                awaitingPrelude = false
            }
            if (pre.startsWith(SWEPT)) {
                throw LintError("connect prelude ended early — ${pre.removePrefix(SWEPT)}")
            }

            // 1. Capability gate (§9.2b): field 100 rides the settings READ
            //    response itself — no timeout needed by construction; an ABSENT
            //    field is a loud refusal (see onNotifyPacket). The rendezvous
            //    was drained at session entry; open the gate window now.
            updateState { it.copy(detail = "capability query") }
            awaitingCapability = true
            val cap = try {
                val queryFailed = CompletableDeferred<String>()
                controlQueue.trySend(CtlWork.Settings(epoch, SettingsMsg.settingsQuery(0), queryFailed))
                // Seen live 2026-08-31 (HANDOFF.md §12): a query that lands
                // while the firmware is still settling a PREVIOUS session's
                // context (its SYSTEM_EXIT / sid-0x01 status chatter) can be
                // eaten — three successive starts parked here. The READ is
                // idempotent, so RE-ASK on a pacing tick until an answer or
                // the sweep arrives. Pacing, not a timeout (the keeper's
                // 250 ms poll is the precedent): the gate never gives up on
                // its own, it just repeats the question.
                val reask = scope.launch {
                    while (true) {
                        delay(CAPABILITY_REASK_MS)
                        if (!awaitingCapability) break
                        Log.i(name, "capability query unanswered after ${CAPABILITY_REASK_MS} ms — asking again")
                        controlQueue.trySend(CtlWork.Settings(epoch, SettingsMsg.settingsQuery(0), CompletableDeferred()))
                    }
                }
                try {
                    // the answer, the sweep's sentinel, or the query's own write
                    // failing — every way the gate can end is a completion here
                    kotlinx.coroutines.selects.select<String> {
                        capabilityChannel.onReceive { it }
                        queryFailed.onAwait { reason -> SWEPT + "capability query not written: $reason" }
                    }
                } finally {
                    reask.cancel()
                }
            } finally {
                awaitingCapability = false
            }
            if (cap.startsWith(SWEPT)) {
                // the session ended (link loss, stop) while the gate waited: the
                // session-end clear answered it so this start fails LOUDLY
                // instead of parking forever (round 4 D1). Not a refusal — the
                // firmware never answered — so it is retried like a link loss.
                throw LintError("capability query ended early — ${cap.removePrefix(SWEPT)}")
            }
            val missing = SettingsMsg.missingCaps(cap)
            if (cap.isEmpty() || missing.isNotEmpty()) {
                val msg = if (cap.isEmpty())
                    "capability gate FAILED: no EVENCFW string — this is NOT the CFW; refusing to paint"
                else "capability gate FAILED: '$cap' missing $missing — refusing to paint"
                _events.emit(TransportEvent.Fault("capability", msg))
                throw CapabilityRefused(msg)
            }
            updateState { it.copy(capability = cap) }

            // 2. Carrier CREATE — image container + the full-screen dummy text
            //    container that is the event antenna (overview.md §4.1).
            updateState { it.copy(detail = "carrier create") }
            val createAck = CompletableDeferred<EvenHubMsg.Ack>()
            controlQueue.trySend(CtlWork.Hub(epoch, EvenHubMsg.carrierCreate(0), createAck))
            // Same eaten-message class as the capability gate (HANDOFF.md §12,
            // seen live one gate further down): a CREATE that lands in the
            // firmware's previous-session teardown is never answered. RE-SEND
            // on the same pacing tick. A duplicate CREATE before the warmup is
            // safe — it recreates the empty carrier, and the sacrificial
            // warmup follows the LAST create either way. The same deferred
            // rides every send: whichever ack arrives first completes it, and
            // the sweep fails it on a session end, so the loop always ends.
            val createReask = scope.launch {
                while (true) {
                    delay(CAPABILITY_REASK_MS)
                    if (createAck.isCompleted) break
                    Log.i(name, "carrier CREATE unacked after ${CAPABILITY_REASK_MS} ms — sending again")
                    controlQueue.trySend(CtlWork.Hub(epoch, EvenHubMsg.carrierCreate(0), createAck))
                }
            }
            val created = try {
                createAck.await()
            } finally {
                createReask.cancel()
            }
            if (created.errorCode != null)
                throw LintError("carrier CREATE rejected: ${created.statusText} (${created.errorCode})")

            // 3. FB lease, BOTH arms (display_copy_hook runs per lens). The
            //    write is awaited: a lease that never reached the wire would
            //    otherwise read as held while the glasses fail open (review
            //    round 1, a4).
            updateState { it.copy(detail = "framebuffer lease") }
            val leaseWritten = CompletableDeferred<Unit>()
            leaseRequested = true
            controlQueue.trySend(CtlWork.Lease(epoch, SettingsMsg.OP_FB_ACQUIRE, leaseWritten))
            leaseWritten.await()

            // 4. The sacrificial warmup frame — the firmware silently drops the
            //    first burst after CREATE (§5.17: make it the splash).
            updateState { it.copy(detail = "warmup frame") }
            val warmupDone = CompletableDeferred<Unit>()
            imageQueue.trySend(ImgWork.Raw(epoch, warmupFrame, warmupDone))
            warmupDone.await()

            updateState { it.copy(started = true, leaseHeld = true, detail = "") }
            startInProgress = false
        } catch (e: Exception) {
            startInProgress = false
            // Roll back COMPLETELY (round 3 D4): a failed start must not leave
            // the lease renewing with no driver, or the instance refusing every
            // retry with "already started". The rollback runs even when this
            // coroutine was CANCELLED (a lost arbitration, a keeper pause):
            // the links must not outlive the attempt (review round 1, a2).
            // The lease is released only if this session asked for it — a
            // release from a session that never held it would take another
            // driver's lease away.
            running = false
            started = false
            withContext(NonCancellable) {
                sweepSession("start failed: ${e.message}")
                if (workersLaunched && leaseRequested) {
                    controlQueue.trySend(CtlWork.Lease(epoch, SettingsMsg.OP_FB_RELEASE))
                }
                try {
                    disconnectLink()
                } catch (d: Exception) {
                    Log.w(name, "disconnect after failed start: ${d.message}")
                }
                if (teeMirror) mirrorSim.linkReset()
                updateState { it.copy(connected = false, started = false, leaseHeld = false, detail = "") }
            }
            throw e
        }
    }

    /** Maintenance: lease renewal (45 s against the 90 s fail-open expiry — a
     *  liveness requirement, not a timeout), idle keepalive, carrier text
     *  refresh, subclass tick. Launched ONCE; each loop idles while !running so
     *  the same set serves every start/stop cycle without duplication. All
     *  enqueue on the CONTROL lane, which never blocks on the ack window — a
     *  stalled flush cannot cost the lease. */
    private fun launchMaintenance() {
        // The three enqueuing loops run only once start() has completed:
        // nothing may reach the wire before the connect prelude (review
        // round 1, a3); start() enqueues the first lease itself.
        scope.launch {
            while (isActive) {
                delay(if (instant) 50 else SettingsMsg.LEASE_RENEW_MS)
                if (_state.value.started) controlQueue.trySend(CtlWork.Lease(sessionEpoch.get(), SettingsMsg.OP_FB_ACQUIRE))
            }
        }
        scope.launch {
            while (isActive) {
                delay(if (instant) 50 else 4_000)
                if (_state.value.started && (nowMs() - lastImageAtMs > 4_000 || instant)) {
                    controlQueue.trySend(CtlWork.Hub(sessionEpoch.get(), EvenHubMsg.keepalive(0), null))
                }
                // Stall REPORT (round 4 D5) — a diagnostic, not a timeout:
                // nothing is cancelled or retried. A lost image ack with the
                // link otherwise healthy leaves the window full forever and the
                // screen frozen while every indicator reads fine; say so.
                if (running && !instant && !stallReported && window.availablePermits == 0 &&
                    lastImageAckAtMs != 0L && nowMs() - lastImageAckAtMs > STALL_REPORT_MS) {
                    stallReported = true
                    emitFault("stall", "window full with no image ack for " +
                        "${(nowMs() - lastImageAckAtMs) / 1000} s — a fragment ack was lost; " +
                        "the msgId cycle cannot recover it without new writes")
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(if (instant) 50 else 30_000)
                if (_state.value.started) controlQueue.trySend(CtlWork.Hub(sessionEpoch.get(), EvenHubMsg.carrierTextUpgrade(0), null))
            }
        }
        scope.launch {
            // Battery poll (2026-08-31): the BARE device-info READ (G2CC §10's
            // live-confirmed form — the f4-sub-request form comes back without
            // the device-info block on the real CFW). First ask ~5 s after
            // start, then once a minute; onNotifyPacket parses f4.12/13 from
            // every sid-0x09 response, unsolicited 09-01 updates included.
            var first = true
            while (isActive) {
                delay(if (instant) 60 else if (first) 5_000 else 60_000)
                if (_state.value.started) {
                    first = false
                    controlQueue.trySend(CtlWork.Settings(sessionEpoch.get(), SettingsMsg.deviceInfoQuery(0)))
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(if (instant) 20 else 1_000)
                if (teeMirror) {
                    val now = nowMs()
                    mirrorSim.tick(now)
                    // the model is the one thing that knows when the lease
                    // would have failed open: derive the lease state from it,
                    // as the sim transport does (review round 1, a4)
                    if (_state.value.started) {
                        val held = mirrorSim.leaseHeld(Arm.LEFT, now) && mirrorSim.leaseHeld(Arm.RIGHT, now)
                        if (!held) setLease(false, "lease expired in the model — renewals did not reach the wire (fail-open)")
                        else setLease(true, "lease held (model)")
                    }
                }
                if (running) onMaintenanceTick()
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
        imageQueue.trySend(ImgWork.Flush(sessionEpoch.get(), id, flush))
        return id
    }

    override suspend fun clearDiagFlags() {
        imageQueue.trySend(ImgWork.ClearDiag(sessionEpoch.get()))
    }

    override suspend fun stop() {
        val epoch = sessionEpoch.incrementAndGet()   // everything older is stale
        running = false
        started = false
        // Sweep FIRST (round 3 D1): fail every pending ack, restore the window
        // permits, and drain both queues loudly — a lane parked on a permit
        // that a dead link will never return would otherwise deadlock this
        // stop and every future start on the same instance.
        sweepSession("$name stopped")
        withContext(NonCancellable) {
        if (workersLaunched) {
            // AWAIT the release actually reaching the wire — a fixed sleep lost
            // it behind a mid-flight keyframe's wire-mutex hold (round 2 #6),
            // leaving the glasses leased/frozen up to the 90 s fail-open. The
            // select also completes if the transport scope itself dies (the
            // lanes' finally-drains fail `released` on the way out; the onJoin
            // arm is the last-resort if death races the enqueue) — round 3 D3.
            val released = CompletableDeferred<Unit>()
            controlQueue.trySend(CtlWork.Lease(epoch, SettingsMsg.OP_FB_RELEASE, released))
            try {
                val job = scope.coroutineContext[kotlinx.coroutines.Job]
                if (job == null) {
                    released.await()
                } else {
                    kotlinx.coroutines.selects.select<Unit> {
                        released.onAwait { }
                        job.onJoin {
                            Log.w(name, "transport scope ended before the release write — " +
                                "the 90 s fail-open covers the lease")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(name, "lease release write failed: ${e.message}")
            }
        }
        try {
            disconnectLink()
        } catch (e: Exception) {
            Log.w(name, "disconnect: ${e.message}")
        }
        if (teeMirror) mirrorSim.linkReset()
        updateState { it.copy(started = false, leaseHeld = false, connected = false, detail = "") }
        if (!_events.tryEmit(TransportEvent.Link(false, "$name stopped")))
            Log.e(name, "Link(false) after stop DROPPED (buffer full)")
        }
    }

    /** End-of-session sweep: fail every in-flight ack (releasing its window
     *  permit — invariant: map membership <=> permit held), then drain both
     *  queues, completing everything exceptionally. LOUD by construction. */
    private fun sweepSession(why: String) {
        // a start() that is, or is about to be, parked on the capability gate
        // is a waiter too (round 4 D1, round 5 F2): the sentinel waits in the
        // CONFLATED rendezvous for it
        if (startInProgress) {
            capabilityChannel.trySend(SWEPT + why)
            preludeChannel.trySend(SWEPT + why)
        }
        for (id in pendingAcks.keys.toList()) {
            val p = pendingAcks.remove(id) ?: continue
            if (p.windowed) window.release()
            p.done.completeExceptionally(LintError("$why (msgId $id un-acked)"))
        }
        updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
        while (true) {
            val w = imageQueue.tryReceive().getOrNull() ?: break
            failImgWork(w, why)
        }
        while (true) {
            val w = controlQueue.tryReceive().getOrNull() ?: break
            failCtlWork(w, why)
        }
    }

    private fun failImgWork(w: ImgWork, why: String) {
        when (w) {
            is ImgWork.Flush -> {
                if (!_events.tryEmit(TransportEvent.FlushDone(w.id, false, 0, 0, why)))
                    Log.e(name, "FlushDone for swept flush ${w.id} DROPPED (buffer full)")
            }
            is ImgWork.Raw -> w.done?.completeExceptionally(LintError(why))
            is ImgWork.ClearDiag -> Log.w(name, "diag clear dropped: $why")
        }
    }

    private fun failCtlWork(w: CtlWork, why: String) {
        when (w) {
            is CtlWork.Hub -> w.awaitAck?.completeExceptionally(LintError(why))
                ?: Log.w(name, "control message dropped: $why")
            is CtlWork.Lease -> w.written?.completeExceptionally(LintError(why))
                ?: Log.w(name, "lease op ${w.op} dropped: $why")
            is CtlWork.Settings -> w.failed?.complete(why)
                ?: Log.w(name, "settings write dropped: $why")
            is CtlWork.Launch -> w.failed?.complete(why)
                ?: Log.w(name, "prelude write dropped: $why")
        }
    }

    /** Subclasses call this when the physical link dies out from under a
     *  session (BLE disconnect): sweeps so nothing waits on acks that will
     *  never come, and surfaces the loss (round 3 D1). */
    protected fun onLinkDown(reason: String) {
        Log.e(name, "link down: $reason")
        // the session is over: clear the latches like stop() does, or the
        // maintenance loops keep writing into a dead link and the next
        // start() is refused as "already started" (round 4 D2). The lease
        // cannot be released through a dead link — the fail-open is the
        // backstop, and the shell is told so.
        running = false
        started = false
        sessionEpoch.incrementAndGet()     // the session is over: queued work is stale
        sweepSession("link down: $reason")
        if (teeMirror) mirrorSim.linkReset()
        updateState { it.copy(connected = false, started = false, leaseHeld = false) }
        if (!_events.tryEmit(TransportEvent.Link(false, reason))) {
            Log.e(name, "Link-down event DROPPED (buffer full)")
        }
    }

    // ------------------------------------------------------------------ image lane
    private suspend fun imageLane() {
        try {
            for (work in imageQueue) {
                if (work.epoch != sessionEpoch.get()) {
                    failImgWork(work, "stale session work dropped (epoch ${work.epoch}, now ${sessionEpoch.get()})")
                    continue
                }
                if (!running) {
                    failImgWork(work, "transport not running")
                    continue
                }
                try {
                    when (work) {
                        is ImgWork.Flush -> laneFlush(work)
                        is ImgWork.Raw -> {
                            val final = writeImage(work.image, work.epoch)
                            completeAsync(final, flushId = -1, bytes = work.image.size,
                                t0 = nowMs(), done = work.done)
                        }
                        is ImgWork.ClearDiag -> {
                            writeImage(byteArrayOf(7, 0), work.epoch)
                            tracker.resync()
                            fids.restart()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    failImgWork(work, "image lane cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(name, "image lane error", e)
                    when (work) {
                        is ImgWork.Flush -> _events.emit(TransportEvent.FlushDone(
                            work.id, false, 0, 0, e.message ?: e.toString()))
                        is ImgWork.Raw -> work.done?.completeExceptionally(e)
                        is ImgWork.ClearDiag -> emitFault("diag", "clear failed: ${e.message}")
                    }
                }
            }
        } finally {
            // the lane is dying (scope cancelled): nothing queued can ever run
            // — say so to every waiter rather than leave it parked (round 3 D3)
            while (true) {
                val w = imageQueue.tryReceive().getOrNull() ?: break
                failImgWork(w, "image lane terminated")
            }
        }
    }

    private suspend fun laneFlush(work: ImgWork.Flush) {
        val t0 = nowMs()
        // state to hand back if the WRITE fails after the encode consumed fids
        // (round 5 F3): the glasses never saw them
        var firstFid = fids.peek()
        var lastBefore = tracker.last
        var seededBefore = tracker.seeded
        var encodeReached = false
        var flushWritten = false
        try {
            if (work.request.wide) {
                // §8.2 #4: a wide flush drains the pipeline and runs at depth 1.
                repeat(WINDOW) { window.acquire() }
                repeat(WINDOW) { window.release() }
            }
            if (fids.wrapPending) {
                // a previous flush ended on 0xFFFE and its clear did not reach
                // the wire: the firmware still holds the old baseline — clear
                // before anything else goes out
                writeImage(byteArrayOf(7, 0), work.epoch)
                tracker.resync()
                fids.clearWrap()
            }
            // §8.2 #6 handled BEFORE encoding (round 3 D2): a flush must never
            // SPAN the 0xFFFE -> 1 wrap — a mode-7 clear cannot ride inside a
            // mode-8 batch, and a post-wrap fid issued without one FID001s on
            // the host (and would f_skip on the glasses). If the fids left
            // before the wrap cannot cover this flush, clear the firmware's
            // ring now, restart the sequence, and encode entirely post-wrap.
            val rects = work.request.ops.count { it is DisplayOp.Delta || it is DisplayOp.StereoPair }
            if (Geometry.FID_MAX - fids.peek() + 1 < rects) {
                writeImage(byteArrayOf(7, 0), work.epoch)
                tracker.resync()
                fids.restart()
            }
            firstFid = fids.peek(); lastBefore = tracker.last; seededBefore = tracker.seeded
            encodeReached = true
            val encoded = Emit.encode(work.request, fids, tracker,
                window = if (work.request.wide) 1 else WINDOW)
            val final = writeImage(encoded.image, work.epoch)
            flushWritten = true
            // the flush is on the wire: its completion is its own, whatever
            // happens to the wrap clear below (round 7 D4)
            completeAsync(final, work.id, encoded.image.size, t0, done = null)
            if (fids.wrapPending) {
                // the flush ended EXACTLY on 0xFFFE: the mode-7 sub-0 clear
                // resets the FIRMWARE's fid ring, flags and lastFid; the host
                // tracker resyncs with it so the next flush starts fresh at 1
                writeImage(byteArrayOf(7, 0), work.epoch)
                tracker.resync()
                fids.clearWrap()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (flushWritten) {
                // only the post-write wrap clear can fail here; the flush
                // itself landed. wrapPending stands, the next flush retries.
                emitFault("wrap", "fid wrap clear not written: ${e.message} — retried before the next flush")
                return
            }
            if (encodeReached) {
                // the encode may have consumed fids the glasses never saw. A
                // throw BEFORE the encode (a failed wrap clear) touched no fid
                // and must leave wrapPending standing for the next retry
                // (round 6): rewinding would drop it and the next delta would
                // present the wrong baseline in silence.
                tracker.rewind(lastBefore, firstFid, seededBefore)
                fids.rewind(firstFid)
            }
            sessionPenalty = true
            _events.emit(TransportEvent.FlushDone(work.id, false, nowMs() - t0, 0,
                e.message ?: e.toString()))
        }
    }

    /** The previous flush's completion job (image-lane confined): each
     *  completion joins it first, so FlushDone events leave in SUBMISSION
     *  order by construction — acks arrive in wire order, but the completion
     *  coroutines would otherwise race for a dispatcher thread (round 3: an
     *  order-sensitive test flaked under load). A failed or swept predecessor
     *  completes too, so the join can never outlive the session. */
    private var lastCompletion: kotlinx.coroutines.Job? = null

    /** Await the final ack off-lane so the lane pipelines the next flush. */
    private fun completeAsync(
        final: PendingAck?, flushId: Long, bytes: Int, t0: Long,
        done: CompletableDeferred<Unit>?,
    ) {
        val prev = lastCompletion
        if (final == null) {
            lastCompletion = scope.launch {
                prev?.join()
                done?.complete(Unit)
                if (flushId >= 0) _events.emit(TransportEvent.FlushDone(flushId, true, 0, bytes))
            }
            return
        }
        lastCompletion = scope.launch {
            try {
                prev?.join()          // inside the try: a cancelled join still
                val ack = final.done.await()   // fails `done` below, never parks it
                onImageDelivered()
                val ackMs = nowMs() - t0
                if (ack.errorCode != null) {
                    sessionPenalty = true
                    done?.completeExceptionally(LintError("ImgResCmd ${ack.statusText} (${ack.errorCode})"))
                    if (flushId >= 0) _events.emit(TransportEvent.FlushDone(flushId, false, ackMs,
                        bytes, "ImgResCmd ${ack.statusText} (${ack.errorCode}) — damage recomputes with a fresh fid"))
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
    private suspend fun writeImage(image: ByteArray, atEpoch: Long): PendingAck? {
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
            if (atEpoch != sessionEpoch.get()) {
                // the session ended while we waited for a slot (the sweep is
                // what returned it): never write a stale message into the next
                // driver's session (round 3 D8)
                window.release()
                throw LintError("session ended while waiting for a window slot")
            }
            updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
            var registered: Pair<Int, PendingAck>? = null
            try {
                wire.withLock {
                    val id = nextMsgIdLocked()
                    val msg = EvenHubMsg.imageFragment(id, sess, image.size, index,
                        image.copyOfRange(off, end))
                    val pending = PendingAck(-1, windowed = true)
                    registerPending(id, pending)
                    registered = id to pending
                    if (end == image.size) finalPending = pending
                    for (p in AaFrame.frame(nextSeqLocked(), EvenHubMsg.SID,
                            EvenHubMsg.FLAG_REQUEST, msg)) {
                        writePacket(Arm.LEFT, p)
                    }
                }
            } catch (e: Exception) {
                // The fragment never fully left, so its ack can never arrive:
                // the entry must go WITH the permit — invariant: map membership
                // <=> permit held (round 3 D7: a stale entry double-released
                // the permit a counter cycle later). Release only if we, not a
                // racing ack, removed it.
                val r = registered
                if (r == null || pendingAcks.remove(r.first, r.second)) window.release()
                updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
                throw e
            }
            off = end
            index++
        }
        return finalPending
    }

    /** msgId cycles 1..249: a WEDGED pending (lost ack) whose id comes around
     *  again must fail LOUDLY and free its window slot, not be silently
     *  overwritten with the slot leaked and the late ack misrouted (review
     *  round 2 #2). Callers hold `wire`. */
    private fun registerPending(id: Int, pending: PendingAck) {
        val prior = pendingAcks.put(id, pending)
        if (prior != null) {
            Log.e(name, "msgId $id reused while its ack is still pending — the original " +
                "message's ack was LOST; failing it and freeing its slot")
            emitFault("ack", "msgId $id pending across a full counter cycle (lost ack)")
            if (prior.windowed) {
                window.release()
                updateState { it.copy(inFlight = WINDOW - window.availablePermits) }
            }
            prior.done.completeExceptionally(
                LintError("ack for msgId $id never arrived (counter cycled)"))
        }
    }

    // wire-mutex-confined counter helpers (callers hold `wire`). msgId never
    // takes the value 0: it is a 1-byte field that dies past 255 and whether
    // real firmware accepts 0 is unverified — 1..249 dodges the question.
    private fun nextMsgIdLocked(): Int { msgId = msgId % 249 + 1; return msgId }
    private fun nextSeqLocked(): Int { aaSeq = (aaSeq + 1) and 0xFF; return aaSeq }
    private fun nextSessionLocked(afterFailure: Boolean): Int {
        session = (session + if (afterFailure) 3 else 1) % 250
        if (session == 0) session = 1
        return session
    }

    // ------------------------------------------------------------------ control lane
    private suspend fun controlLane() {
        try {
            for (work in controlQueue) {
                if (work.epoch != sessionEpoch.get()) {
                    failCtlWork(work, "stale session control work dropped")
                    continue
                }
                try {
                    when (work) {
                        is CtlWork.Hub -> {
                            val pending = PendingAck(-1, windowed = false)
                            var registeredId = -1
                            try {
                                wire.withLock {
                                    val id = nextMsgIdLocked()
                                    registerPending(id, pending)
                                    registeredId = id
                                    val payload = restampMsgId(work.payload, id)
                                    for (p in AaFrame.frame(nextSeqLocked(), EvenHubMsg.SID,
                                            EvenHubMsg.FLAG_REQUEST, payload)) {
                                        writePacket(Arm.RIGHT, p)
                                    }
                                }
                            } catch (e: Exception) {
                                // a message that never fully left has no ack to
                                // wait for: its entry must not linger to resurface
                                // as a spurious lost-ack fault a cycle later
                                if (registeredId >= 0) pendingAcks.remove(registeredId, pending)
                                throw e
                            }
                            // NEVER await the ack inline (round 3 D3): a lost ack
                            // would park the lane and every lease renewal behind
                            // it — the one thing this lane exists to prevent. The
                            // watcher forwards to the caller off-lane, and a swept
                            // or counter-cycled pending is reported, never thrown
                            // into the scope (round 3 D5: that crashes Android).
                            val awaiting = work.awaitAck
                            scope.launch {
                                try {
                                    val ack = pending.done.await()
                                    if (awaiting != null) awaiting.complete(ack)
                                    else if (ack.errorCode != null)
                                        emitFault("control", "${ack.statusText} (${ack.errorCode})")
                                } catch (e: Exception) {
                                    if (awaiting != null) awaiting.completeExceptionally(e)
                                    else Log.w(name, "control ack never arrived: ${e.message}")
                                }
                            }
                        }
                        is CtlWork.Settings -> try {
                            wire.withLock {
                                val payload = restampMsgId(work.payload, nextMsgIdLocked())
                                for (p in AaFrame.frame(nextSeqLocked(), SettingsMsg.SID,
                                        SettingsMsg.FLAG_REQUEST, payload)) {
                                    writePacket(Arm.RIGHT, p)
                                }
                            }
                        } catch (e: Exception) {
                            work.failed?.complete(e.message ?: e.toString())
                            throw e
                        }
                        is CtlWork.Launch -> try {
                            wire.withLock {
                                val id = nextMsgIdLocked()
                                preludeMsgId = id          // known before the bytes leave
                                for (p in AaFrame.frame(nextSeqLocked(), LaunchMsg.SID,
                                        LaunchMsg.FLAG_REQUEST, LaunchMsg.prelude(id))) {
                                    writePacket(Arm.RIGHT, p)
                                }
                            }
                        } catch (e: Exception) {
                            work.failed?.complete(e.message ?: e.toString())
                            throw e
                        }
                        is CtlWork.Lease -> {
                            if (work.op == SettingsMsg.OP_FB_ACQUIRE && !running) {
                                // a renewal that slipped past stop()'s running=false
                                // must not re-lease glasses just released (round 3
                                // D10); the epoch guard above catches the rest
                                work.written?.complete(Unit)
                            } else {
                                // Fire-and-forget by design (settings_ext.c:
                                // MagicRandom 0 is consumed before the stock
                                // decoder discards the field) — pure writes, can
                                // never wedge behind a stuck ack.
                                try {
                                    wire.withLock {
                                        for (arm in Arm.entries) {
                                            val nonce = (nowMs() and 0xFFFF).toInt()
                                            for (p in AaFrame.frame(nextSeqLocked(), SettingsMsg.SID,
                                                    SettingsMsg.FLAG_REQUEST, SettingsMsg.control(work.op, nonce))) {
                                                writePacket(arm, p)
                                            }
                                        }
                                    }
                                    work.written?.complete(Unit)
                                } catch (e: Exception) {
                                    work.written?.completeExceptionally(e)
                                    throw e
                                }
                                if (work.op == SettingsMsg.OP_FB_ACQUIRE)
                                    setLease(true, "FB lease acquired/renewed (both arms)")
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    failCtlWork(work, "control lane cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(name, "control lane error", e)
                    failCtlWork(work, e.message ?: e.toString())
                    emitFault("control", e.message ?: e.toString())
                }
            }
        } finally {
            while (true) {
                val w = controlQueue.tryReceive().getOrNull() ?: break
                failCtlWork(w, "control lane terminated")
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

        /** Marker the session-end clear pushes into the capability and prelude
         *  rendezvous (a leading NUL: no real answer can start with it). */
        private const val SWEPT = "\u0000swept: "

        /** The reference settles this long after both arms are up before the
         *  prelude (faceclaw sleepDuringConnectSettling(800)) — pacing, not a
         *  timeout; skipped when [instant]. */
        private const val PRELUDE_SETTLE_MS = 800L
        /** Re-ask pacing for the capability gate (2026-08-31): the firmware
         *  can eat a settings READ sent while it settles a previous session's
         *  context. Pacing, not a timeout — the gate never exits on time. */
        private const val CAPABILITY_REASK_MS = 2_000L

        /** Reporting threshold for the stall diagnostic — well past any
         *  measured ack (176 ms median, 7–13 KB/s); reports, never acts. */
        private const val STALL_REPORT_MS = 10_000L
    }
}
