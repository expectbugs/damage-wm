package wm.damage.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import wm.damage.core.geom.Rect

/**
 * The transport <-> shell seam — DESIGN.md §10 open item #12, the one interface
 * that cannot be refactored away later. Everything above it is the shell
 * (damage tracking, rasterization, compression); everything below it owns the
 * glasses (BLE links, the FB lease, msgId/seq/fid discipline, fragmentation,
 * event forwarding). The same interface is implemented by:
 *
 *   SimTransport        — the byte-exact firmware model (development, tests)
 *   BleTransport        — the real CFW path (:phone, banked until flash day)
 *   RemoteTransport     — this seam serialized over the network, so the shell
 *                         can live on the PC while the transport lives on the
 *                         phone or the bridge appliance, or vice versa
 *
 * Coordinates in [DisplayOp] are NOMINAL (single-frame); disparity is a per-op
 * property and the EMITTER builds the per-lens box pair (§3.4). Payloads arrive
 * already compressed (zlib(rle)) because compression is the shell's job (§10.1).
 * Fids are stamped by the transport at EMIT time, never at plan time (§8.2 #5).
 */
interface Transport {
    /** Everything the glasses say, plus transport-internal state changes. */
    val events: Flow<TransportEvent>

    /** Link + lease + pipeline state, updated continuously. */
    val state: StateFlow<LinkState>

    /**
     * Bring the display up: capability gate (EVENCFW string must carry
     * img640/directfb/fbguard/imgz/rle — refuse loudly otherwise), carrier
     * CREATE (image container + full-screen dummy text container), FB lease
     * acquire on BOTH arms, and the sacrificial warmup frame ([warmupFrame],
     * a mode-6 payload — the boot splash, since the firmware silently drops
     * the first burst after CREATE).
     */
    suspend fun start(warmupFrame: ByteArray)

    /**
     * Submit one atomic flush (one mode-8 batch, or a bare keyframe). Suspends
     * until the transport accepts it into the in-flight window — that
     * suspension is the backpressure signal §5.13's coalescing rides on.
     * Completion (ack + measured latency, or failure) arrives as
     * [TransportEvent.FlushDone] carrying the returned id.
     */
    suspend fun submit(flush: FlushRequest): Long

    /** Clear the CFW's sticky diagnostic flags + fid ring (mode 7 sub 0) —
     *  required at the deliberate fid wrap (§8.2 #6). */
    suspend fun clearDiagFlags()

    /** Release the lease and stop. The screen returns to stock. */
    suspend fun stop()
}

/** One display operation inside a flush. Order matters: mode-8 sub-messages
 *  apply to the shadow in order, later ops win. */
sealed class DisplayOp {
    /** Mode-6 keyframe: [payload] = zlib(rle(packed full 640x480)). Rebaselines
     *  the fid sequence and seeds the shadow. */
    data class Keyframe(val payload: ByteArray) : DisplayOp()

    /** Mode-3 delta: [box] nominal, x4/x2-aligned; [payload] = zlib(rle(packed
     *  box pixels)); [disparity] on the 4 px ladder — 0 emits a flat delta,
     *  +d = far (L at x-d, R at x+d), -d = crossed/near. */
    data class Delta(val box: Rect, val payload: ByteArray, val disparity: Int = 0) : DisplayOp()

    /** Mode-9 rect copy, nominal coords; [disparity] shifts both rects per lens. */
    data class Copy(val src: Rect, val dst: Rect, val disparity: Int = 0) : DisplayOp()
}

/** kind: DELTA rides the pipeline; KEYFRAME also rebaselines fid discipline. */
data class FlushRequest(
    val ops: List<DisplayOp>,
    val epoch: Long,
    val label: String = "",
)

sealed class TransportEvent {
    /** A ring/temple gesture, already decoded. type = EvenHubMsg.EV_*, source = SRC_*. */
    data class Input(val type: Int, val source: Int) : TransportEvent()

    /** A submitted flush completed. [ok]=false carries the loud reason. */
    data class FlushDone(
        val id: Long, val ok: Boolean, val ackMs: Long,
        val bytes: Int, val error: String? = null,
    ) : TransportEvent()

    /** Lease state changed. held=false after start() is a HARD error (fail-open
     *  means stock is repainting over us). */
    data class Lease(val held: Boolean, val detail: String) : TransportEvent()

    /** Link up/down. Down fails all outstanding flushes. */
    data class Link(val connected: Boolean, val detail: String) : TransportEvent()

    /** CFW sticky diagnostic flags observed (f_dup/f_skip/f_reorder/f_snap_of).
     *  Any true flag is a hard error during bring-up (§9.2). */
    data class DiagFlags(val flags: Map<String, Boolean>) : TransportEvent()

    /** A transport-layer problem worth surfacing (decode failure, e0-02 abort,
     *  session bump, capability mismatch). Never swallowed. */
    data class Fault(val what: String, val detail: String) : TransportEvent()
}

data class LinkState(
    val connected: Boolean = false,
    val started: Boolean = false,
    val leaseHeld: Boolean = false,
    val inFlight: Int = 0,
    val window: Int = 3,
    val ackMsEma: Double = 176.0,     // measured stock median until real data arrives
    val bytesPerSecEma: Double = 11_000.0,
    val capability: String? = null,
    val rssiDbm: Int? = null,
    val transportName: String = "none",
)
