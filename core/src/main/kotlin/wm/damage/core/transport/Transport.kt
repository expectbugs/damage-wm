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

    /** What the glasses are believed to hold, per lens — every replica draws
     *  this (HANDOFF.md §8.2). Exact for local transports (the bytes written
     *  are applied to a firmware model synchronously); a seam-fed mirror lags. */
    val mirror: LensPanels

    /** A gesture from a replica (mouse, touch, browser page) enters HERE, as a
     *  ring event on [events], so it reaches whichever shell drives this
     *  transport — a phone touch during a PC takeover included. */
    fun injectInput(type: Int)

    /** A typed LINE from a replica (phone strip, browser page, desktop
     *  preview) — the ring cannot type (TMUX.md verdict 1; the REMINDER.md
     *  open item, ported). Arrives as [TransportEvent.Text] on [events], so it
     *  reaches whichever shell drives; the focused window decides whether it
     *  accepts text (DamageWindow.onTypedText) and ALWAYS stages a confirm
     *  before anything runs. Default: refused loudly by the shell's router. */
    fun injectText(line: String) {}

    /** Push the panel-brightness setting to the glasses (2026-08-31): a
     *  sid-0x09 write on the control lane, fire-and-forget — the firmware
     *  restores its own value across reboots, so the shell re-pushes on every
     *  session start. Default: ignored (a transport with no panel). */
    fun setBrightness(auto: Boolean, level: Int) {}

    /** The connection priority the radio should hold (2026-09-12, `HANDOFF.md`
     *  §47): one of [ShellSettings.LINK_PRIORITIES] — "high" (Android's
     *  11.25–15 ms interval) or "balanced" (30–50 ms). The phone's transport
     *  asks the platform for it at connect time and asks AGAIN, paced,
     *  whenever the glasses move the link to slower parameters (measured
     *  2026-09-12: 105 ms / latency 4 for three hours, every flush ~470 ms
     *  slower). Default: no radio to ask. */
    fun setLinkPriority(name: String) {}

    /** Hold or drop the framebuffer lease on demand (2026-09-05, `HANDOFF.md`
     *  §36): the shell drops it while the glasses are in the firmware's Silent
     *  Mode — with the lease held nothing paints anyway, and without it the
     *  stock firmware owns the display and the both-temple gesture again — and
     *  takes it back on wake, keyframing after. Default: nothing to hold. */
    suspend fun setLeaseWanted(wanted: Boolean) {}

    /**
     * End the running session ON PURPOSE and report the link as down, so the
     * session keeper rebuilds everything from the connect up — prelude,
     * capability READ, carrier CREATE, lease, warmup, the shell's keyframe.
     * G2CC's recovery path (its response-gap watchdog reconnected and the
     * fresh session rebuilt the layout), adopted 2026-09-05 (`HANDOFF.md`
     * §37.0): leaving the firmware's Silent Mode ends the EvenHub session,
     * and nothing paints again until a CREATE — measured on glass, images
     * refused for four minutes after the glasses said they were awake. The
     * shell calls this on the wake, never a keyframe. Returns false, and does
     * nothing, when no session is started (or the transport cannot restart
     * its far end — the seam driver). Default: cannot.
     */
    suspend fun restartSession(reason: String): Boolean = false

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
     * Submit one atomic flush (one mode-8 batch, or a bare keyframe). Enqueues
     * in call order and returns at once; the backpressure signal §5.13's
     * coalescing rides on is [LinkState.inFlight] against [LinkState.window],
     * which the shell's pump gates a submit on (the fragment window inside the
     * transport backs it). Completion (ack + measured latency, or failure)
     * arrives as [TransportEvent.FlushDone] carrying the returned id.
     */
    suspend fun submit(flush: FlushRequest): Long

    /** Clear the CFW's sticky diagnostic flags + fid ring (mode 7 sub 0) —
     *  required at the deliberate fid wrap (§8.2 #6). */
    suspend fun clearDiagFlags()

    /** Release the lease and stop. The screen returns to stock. */
    suspend fun stop()

    /** True while a start() in progress has COMMITTED the far end — for the
     *  seam client, from the server's grant (the phone has yielded its shell)
     *  until the start completes or fails. The arbitration lets an engaged
     *  higher-priority path finish before a lower one tries. */
    val engaged: Boolean get() = false
}

/** The firmware answered and is not the CFW (or lacks a required capability):
 *  a refusal no retry can change. The session keeper goes terminal on it and
 *  the arbitration disables the path; every other start failure is retried. */
class CapabilityRefused(message: String) : wm.damage.core.geom.LintError(message)

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

    /** A mode-9 copy with EXPLICIT per-lens rects (§41): the slide that puts
     *  a flat cached draw at each lens's own x. Same size on both lenses;
     *  the firmware's copy is overlap-safe (draw.c rect_copy_4bpp). No fid. */
    data class CopyPair(val srcL: Rect, val dstL: Rect, val srcR: Rect, val dstR: Rect) : DisplayOp()

    /** A stereo delta with EXPLICIT per-lens boxes (same size, §3.2) — used for
     *  the vacated-strip cleanup a stereo region needs after a keyframe: the
     *  left lens clears the region's right inner strip while the right lens
     *  clears the left one, which box±d cannot express. */
    data class StereoPair(val left: Rect, val right: Rect, val payload: ByteArray) : DisplayOp()

    /** A mode-12 texture-cache write (§40) — one complete message, sent as
     *  its own image (mode 12 is not a batch sub-mode). A flush made only of
     *  these is the atlas upload; the transport writes them in order and
     *  completes on the last ack. Never mixed with the other ops. */
    data class CacheWrite(val payload: ByteArray) : DisplayOp()

    /** A mode-14 draw of cached glyphs at (x, y), FLAT — the firmware draws
     *  the same pixels into both lenses (§40). [text] is the string bytes
     *  `TextureCache.layout` produced; [options] the LUT top + flags. No fid. */
    data class DrawText(val fontOffset: Int, val x: Int, val y: Int, val options: Int, val text: ByteArray) : DisplayOp()

    /** A mode-13 draw of one cached image, flat, no fid (§40). */
    data class DrawImage(val cacheOffset: Int, val x: Int, val y: Int, val options: Int) : DisplayOp()
}

/** kind: DELTA rides the pipeline; KEYFRAME also rebaselines fid discipline. */
data class FlushRequest(
    val ops: List<DisplayOp>,
    val epoch: Long,
    val label: String = "",
    /** Rects exceed the pipelined budget: run with the window drained (§8.2 #4). */
    val wide: Boolean = false,
)

sealed class TransportEvent {
    /** A ring/temple gesture, already decoded. type = EvenHubMsg.EV_*, source = SRC_*. */
    data class Input(val type: Int, val source: Int) : TransportEvent()

    /** A typed line from a replica ([Transport.injectText]). */
    data class Text(val line: String) : TransportEvent()

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

    /** Device battery telemetry read off the wire (2026-08-31): glasses from
     *  the sid-0x09 device-info response (field 4.12/4.13), ring from a
     *  sid-0x91 relay when the glasses forward one. Null = unreported in this
     *  event; consumers keep their last value. */
    data class Battery(
        val glassesPct: Int? = null,
        val glassesCharging: Boolean? = null,
        val ringPct: Int? = null,
    ) : TransportEvent()

    /** A transport-layer problem worth surfacing (decode failure, e0-02 abort,
     *  session bump, capability mismatch). Never swallowed. */
    data class Fault(val what: String, val detail: String) : TransportEvent()

    /** A transport-layer FACT for the journal (2026-09-05, §34): recorded,
     *  never a status or a notice — a control message the firmware ate and a
     *  re-send answered is the first one. Faults stay faults. */
    data class Note(val kind: String, val detail: String) : TransportEvent()

    /** The firmware's Silent Mode, as the glasses push it (§36): while [on]
     *  the firmware refuses every image, so the shell stops sending. */
    data class SilentMode(val on: Boolean) : TransportEvent()
}

data class LinkState(
    val connected: Boolean = false,
    val started: Boolean = false,
    val leaseHeld: Boolean = false,
    val inFlight: Int = 0,
    val window: Int = 3,
    val ackMsEma: Double = 176.0,     // measured stock median until real data arrives
    val bytesPerSecEma: Double = 11_000.0,
    /** The ack floor: an EMA over SMALL flushes only (< 400 B), where transfer
     *  time is nil. 60 ms measured on both radio paths (`HANDOFF.md` §31.6). */
    val floorMsEma: Double = 60.0,
    /** The transfer term: an EMA of (ack − floor) per KB over flushes of
     *  1 KB and more — the number that told the two radio paths apart when
     *  the floor could not (2026-09-05, `HANDOFF.md` §32): ~20 ms/KB on
     *  PC-direct BlueZ, ~125 ms/KB through the phone. The default is the fast
     *  regime, so nothing adapts until a real flush has been measured. */
    val transferMsPerKbEma: Double = 20.0,
    /** The link's connection parameters as the platform reported them
     *  ("L 15.00ms/0/5000ms R …"), empty until it does. The phone's BLE stack
     *  grants or refuses the priority request without an API to ask; the
     *  parameters callback is the only place the answer appears. */
    val linkParams: String = "",
    /** The same parameters as numbers (2026-09-12): the LARGEST connection
     *  interval over the arms in ms, and the largest slave latency — 0 until
     *  the platform reports them. The shell's slow-link regime reads these
     *  at once instead of waiting for the ack EMA to notice. */
    val linkIntervalMs: Double = 0.0,
    val linkLatency: Int = 0,
    /** The firmware's Silent Mode as last pushed or read (§36). */
    val glassesSilent: Boolean = false,
    val capability: String? = null,
    val rssiDbm: Int? = null,
    val transportName: String = "none",
    /** What the transport is doing right now, for status lines: "scanning for
     *  the pair", "connecting RIGHT", "connect prelude", "" once driving. */
    val detail: String = "",
)
