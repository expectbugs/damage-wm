package wm.damage.core.sim

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Zl
import wm.damage.core.transport.Arm
import wm.damage.core.transport.LensPanels
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.LaunchMsg
import wm.damage.core.wire.LoggerMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg

/**
 * A byte-exact model of the CFW glasses — DESIGN.md §9.2's offline simulator.
 * Consumes the SAME AA packets the BLE transport would write and models what
 * g2flash's firmware does with them: reassembly, ImgRawMsg accumulation, mode
 * 3/6/8/9 dispatch onto per-lens packed-4bpp shadows (and, for a Damage build of
 * contract 2, `FIRMWARE.md` §4's modes 17–24 with their refusal record, written
 * from the contract text — never from the fork's C), the duplicate-fid ring
 * with its f_dup/f_skip/f_reorder flags, the per-arm framebuffer lease with its
 * fail-OPEN expiry, the warmup-frame drop, the msgId-255 silent drop, and the
 * stuck-session trap. Where the hardware fails in SILENCE, this model fails in
 * silence too — but reports every such event through [SimDiag] so a test or the
 * dev overlay can make it loud. Nothing like the EvenHub simulator, which lies.
 *
 * Sans-IO: all methods are synchronous; the caller supplies time. One instance
 * models the PAIR (both arms — the firmware propagates image traffic cross-lens,
 * overview.md §2), with per-lens shadow and diagnostic context.
 */
class GlassFirmwareSim() : LensPanels {

    interface SimDiag {
        /** A modeled silent failure or notable event — the sim making the hardware's silence visible. */
        fun event(kind: String, detail: String)

        /** A notify the glasses would send (acks, input events), from [arm]. */
        fun notify(arm: Arm, packet: ByteArray)

        /** The panel content changed on [arm] (a present happened, or stock repainted). */
        fun panelChanged(arm: Arm)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SimDiag>()

    fun attachListener(l: SimDiag) { listeners.add(l) }

    fun detachListener(l: SimDiag) { listeners.remove(l) }

    private val lensListeners = java.util.concurrent.CopyOnWriteArrayList<LensPanels.LensListener>()

    private val diag = object : SimDiag {
        override fun event(kind: String, detail: String) { for (l in listeners) l.event(kind, detail) }
        override fun notify(arm: Arm, packet: ByteArray) { for (l in listeners) l.notify(arm, packet) }
        override fun panelChanged(arm: Arm) {
            for (l in listeners) l.panelChanged(arm)
            for (l in lensListeners) l.panelChanged(arm)
        }
    }

    // ------------------------------------------------------------------ LensPanels
    /** The sim IS a local, exact mirror: every replica can draw it directly. */
    override val exact: Boolean get() = true
    override val stride: Int get() = left.stride
    override fun panel(arm: Arm): ByteArray = ctx(arm).panel
    @Synchronized
    override fun snapshot(arm: Arm): ByteArray = ctx(arm).panel.copyOf()
    override fun addListener(l: LensPanels.LensListener) { lensListeners.add(l) }
    override fun removeListener(l: LensPanels.LensListener) { lensListeners.remove(l) }

    /** Per-lens firmware context: shadow + cfw_diag state (zlib_glue.c cfw_ctx). */
    /** The mode-3 frame-order diagnostics as one block (cfw_context.h damage_diag_state):
     *  the self-test swaps its own in for a step and restores the live one after. */
    class DiagState {
        var lastFid = 0
        var highFid = 0
        var fidResync = false
        var diagSeen = false
        var fDup = false
        var fSkip = false
        var fReorder = false
        val recentFids = IntArray(Geometry.CFW_FID_RING) { 0xFFFF }
        var recentPos = 0
    }

    class LensCtx {
        val stride = (Geometry.PANEL_W + 1) / 2
        /** The live shadow — the container's display allocation A on glass. A `var`
         *  only so a self-test step can point the dispatcher at the scratch shadow
         *  (zlib_glue.c dispatches on a container state whose A is the scratch). */
        var shadow = ByteArray(stride * Geometry.PANEL_H)
        var lastFid = 0
        var highFid = 0
        var fidResync = false
        var diagSeen = false
        var fDup = false
        var fSkip = false
        var fReorder = false
        val recentFids = IntArray(Geometry.CFW_FID_RING) { 0xFFFF }
        var recentPos = 0
        /** panel = what the wearer sees; presents copy shadow -> panel. */
        val panel = ByteArray(stride * Geometry.PANEL_H)
        var leaseDeadline = 0L      // 0 = no lease; fail-open
        var seeded = false          // a mode-6 keyframe has landed
        /** The 64 KiB texture cache: null until the first mode-12 write allocates
         *  and zeroes it, dropped again when the lease ends or mode 11 runs
         *  (texture_cache.c cfw_texture_cache_update / cfw_texture_cache_release). */
        var textureCache: ByteArray? = null
        /** cfw_ctx diag_hide, inverted: mode 7 sub 2 shows the overlay, sub 1 hides
         *  it (zlib_glue.c); it draws into the physical framebuffer only, so the
         *  modeled panel is unchanged — this is state for tests. */
        var overlayShown = false
        /** The stock log stream's RAM switch (LoggerMsg): set by a sid-0x0F
         *  BLE_LOGGER_SWITCH_SET, cleared by the next app start (a CREATE). */
        var loggerOn = false
        /** `FIRMWARE.md` §3 flags; cleared at every texture-cache release point. */
        var damageFlags = 0
        /** `FIRMWARE.md` §3 field 4, a register: the status the last recording op left
         *  (FLAGS_SET, FLAGS_CLEAR, a malformed or unknown request); TELEMETRY records
         *  nothing, and a lease lapse clears the flags, never this. */
        var damageStatus = 0
        /** F1.3: direct presents counted (the copy hook's count on glass). The sim has no
         *  timing, so its transfer stamp is always 0. */
        var presentSeq = 0L
        /** F1.5: mode-12 writes that changed the cache; the CACHE_KEEP latch read when the
         *  flags clear, spent by the fresh acquire that follows. */
        var cacheGen = 0L
        var cacheKeepLatched = false
        /** The current lapse or release has been settled once (the C's dmg_lease_settled):
         *  tick(), fbLeaseActive() and a release may each notice the same lapse. */
        var lapseSettled = false
        /** The self-test (mode 16): the scratch shadow, the step count, the last step's
         *  refusal and scratch CRC, its own frame-order diagnostics. */
        var stShadow: ByteArray? = null
        var stActive = false
        /** The scratch's own "a keyframe has landed": kept across steps as the scratch's
         *  bytes are, so the unseeded-delta diagnostic speaks of the scratch, not the live
         *  shadow (the C has no such flag; its bytes are the state). */
        var stSeeded = false
        var stSeq = 0L
        var stRefused = false
        var stCrc = 0L
        var stDiag = DiagState()
        /** `FIRMWARE.md` §4 (contract 2). Op 5: the size the next allocation takes (0 = the 64 KiB
         *  default) and the allocated size while the cache is up; reverts to 0 with the cache. */
        var cacheBytes = 0
        /** Fields 23–25: the last image-lane refusal (v1 modes included), sticky until mode 7 sub 0. */
        var refSeen = false
        var refMode = 0
        var refReason = 0
        var refSeq = 0L
        /** Mode 23: the live session's save-under slots and the self-test's, swapped per step. */
        var slots = arrayOfNulls<SaveSlot>(CfwModes.SAVE_SLOTS)
        var stSlots = arrayOfNulls<SaveSlot>(CfwModes.SAVE_SLOTS)
        /** Field 26 / the presented notify's field 6: the last present's path (0 full, 1 rows). */
        var lastPath = 0
        /** `FIRMWARE.md` §4, mode 24 (the second Phase 2 review): the panel no longer shows the whole
         *  previous frame, so the next present goes whole — a partial refresh only ever adds its own
         *  rows. Set by a message that could have changed the shadow and did not present, by a lease
         *  release point (stock repaints after it) and by the frame that carries the overlay. At the
         *  start both the panel and the shadow are zero, and the lease a session must acquire is a
         *  fresh one, which sets it. */
        var panelStale = false
        /** The overlay was drawn into the framebuffer by the last present: the frame after it is full
         *  too, since its rows still show the overlay. */
        var overlayInFb = false
        /** Set by [present] so a top-level message knows whether anything reached the panel. */
        var presented = false
        val slotBytes: Int get() = slots.sumOf { it?.bytes ?: 0 }
    }

    /** One captured rect (mode 23): the levels row-major, [bytes] what it takes in the pool. */
    class SaveSlot(val rect: wm.damage.core.geom.Rect, val levels: ByteArray) {
        val bytes: Int get() = CfwModes.saveBytes(rect)
    }

    /** The batch context (`FIRMWARE.md` §4): the clip (mode 20) the v2 ops after it honour and
     *  the present hint (mode 24) the batch's present carries. Made per top-level message, so
     *  both end with the batch, as the C keeps them on the worker's stack. */
    class BatchCtx {
        var clip: wm.damage.core.geom.Rect? = null
        var hint: IntRange? = null
    }

    /** The active panel record the sim models (telemetry field 10 stays unsent — the sim does not
     *  know the heap either): JBD4010 by default, so a mode-24 hint takes the partial path. */
    @Volatile var panelRecord: Long = PANEL_JBD4010

    var left = LensCtx()
        private set
    var right = LensCtx()
        private set
    private fun ctx(arm: Arm) = if (arm == Arm.LEFT) left else right

    /** One lens reboots at [now] (seen on glass 2026-09-15 12:54: the right arm's link timed
     *  out and its uptime read 13 s at the rebuild): every byte of its RAM is gone — the
     *  shadow, the lease, the texture cache, the flags — and RIGHT's uptime restarts. */
    @Synchronized
    fun rebootForTest(arm: Arm, now: Long) {
        if (arm == Arm.LEFT) left = LensCtx() else { right = LensCtx(); uptimeOffsetMs = now }
        diag.event("reboot", "$arm rebooted: RAM cleared")
    }

    /** Seen on glass 2026-09-15 (`HANDOFF.md` §59): a field-112 request written to both arms is
     *  answered twice by RIGHT, a few ms apart (I: the LEFT copy reaches RIGHT over the lenses'
     *  own link and runs there again). Off by default; the keeper's tests turn it on. */
    @Volatile var duplicateControlReplies = false
    /** How much later the forwarded copy is answered (the glasses: ~6 ms). */
    @Volatile var duplicateReplyDelayMs = 6L

    /** FW_SIDE(): 2 = LEFT lens, 1 = RIGHT (zlib_glue.c lens_side_fn comment). */
    private fun fwSide(arm: Arm) = if (arm == Arm.LEFT) 2 else 1

    private val reassemblers = mapOf(
        Arm.LEFT to AaFrame.Reassembler { diag.event("transport", "L: $it") },
        Arm.RIGHT to AaFrame.Reassembler { diag.event("transport", "R: $it") },
    )

    // --- EvenHub app-level state ------------------------------------------------
    var layoutCreated = false
        private set
    private var warmupPending = false
    private data class ImgSession(val session: Int, val total: Int) {
        val buf = java.io.ByteArrayOutputStream()
        var nextFrag = 0
        var broken = false
    }
    private var img: ImgSession? = null
    private val brokenSessions = HashSet<Int>()
    /** What g2flash a5d1c31 advertises, read out of the built image's rodata
     *  (`strings fws/... | grep EVENCFW`). The a5d1c31 set dropped `img576` and
     *  `compass10` and added the texture-cache/font/cleanup tokens; the version
     *  went 8 -> 16. Damage's REQUIRED_CAPS are all still present. */
    /** Modeled glasses battery (f4.12 of the settings READ response) and the
     *  last brightness write accepted (null level = auto). */
    var batteryPct = 87

    /** `FIRMWARE.md` §0: null models the installed upstream build (no field 110, field 112
     *  ignored); a contract number models a Damage build — DamageCaps on every READ and
     *  the §3 control ops answered. Only RIGHT answers, as the stock senders' lens rule
     *  has it (`CLAIMS.md`, 2026-09-14); LEFT runs every op and reports nothing. */
    @Volatile var damageContract: Int? = null

    /** The glasses' uptime as telemetry reports it is the modeled clock less this
     *  offset; a test models a reset by setting the offset to the clock (uptime 0). */
    @Volatile var uptimeOffsetMs = 0L

    /** The firmware's Silent Mode (§36): while on, every image is refused
     *  with ImgResCmd status 5 (measured on the real pair 2026-09-05) and the
     *  READ response restores the state in field 4.14. [setSilent] toggles it
     *  the way the both-temple long-press does — with the device push. */
    @Volatile var silentMode = false
        private set
    /** EvenHub keepalives (Cmd 12) received, for the §42 pin: none while the
     *  shell sleeps with the glasses. */
    @Volatile var keepalivesSeen = 0
        private set
    /** FB_RELEASE control ops received (both arms count), for the §42 pin: a
     *  stop after a link loss sends none. */
    @Volatile var releasesSeen = 0
        private set

    /** Whether READ responses carry `silentModeSwitchRestored` (field 4.14).
     *  Stock firmware may omit it; a test withholds it to exercise the
     *  refusal fallback on its own. */
    @Volatile var reportSilentRestored = true

    /** The EvenHub app slot ended under Silent Mode and no CREATE has come
     *  since (`HANDOFF.md` §37.0, measured 2026-09-05 22:04): after the
     *  glasses said they were awake every image was still refused with
     *  status 5, for four minutes, until a fresh session's CREATE. Set with
     *  the mode, cleared by the next CREATE — so a shell that answers the
     *  wake with a keyframe instead of a rebuild is refused here too. */
    @Volatile var carrierLost = false
        private set

    fun setSilent(on: Boolean, push: Boolean = true) {
        silentMode = on
        if (on) {
            // the slot goes with the mode: Faceclaw's reading is that Silent
            // Mode blocks app launches (its own comment on silentMode), and
            // the glass measured a fresh CREATE's warmup and first flush
            // accepted, then everything refused a second later
            layoutCreated = false
            carrierLost = true
        }
        diag.event("silent", "Silent Mode ${if (on) "ON — images refused, the app slot ended" else "OFF — images stay refused until a CREATE"}${if (push) " (pushed)" else " (no push)"}")
        if (push) diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), SettingsMsg.SID,
            SettingsMsg.FLAG_RESPONSE, SettingsMsg.silentModePush(on), AaFrame.TYPE_RESPONSE).single())
    }
    var brightnessAuto = true
    var brightnessLevel: Int? = null
    /** Test knob (§47): eat this many settings WRITES — no store, no answer —
     *  the session-start class of loss the re-send exists for. */
    @Volatile var eatSettingsWrites = 0
    /** Settings writes answered (§47), for tests. */
    @Volatile var settingsWritesAnswered = 0

    var capabilityString = "EVENCFW/16 img640 imgz rle wakelease directfb fbguard " +
        "wearnotify cleanup11 texcache12 teximg13 texstr14 font15 micctl"

    /** The connect prelude (LaunchMsg) has been received this connection. Modeled
     *  STRICT (graded U — see LaunchMsg): a CREATE with no prelude is acked but
     *  the page never becomes active, so images are never painted. */
    var preludeSeen = false
        private set

    /** How many preludes were acked — tests and the selfcheck assert >= 1. */
    var preludeAcks = 0
        private set

    private var glassSeq = 0
    private fun nextSeq(): Int { glassSeq = (glassSeq + 1) and 0xFF; return glassSeq }

    /** Host wrote [packet] to [arm]'s write characteristic. */
    @Synchronized
    fun write(arm: Arm, packet: ByteArray, now: Long) {
        val frame = reassemblers.getValue(arm).offer(packet) ?: return
        when (frame.sid) {
            EvenHubMsg.SID -> evenHub(arm, frame.payload, now)
            SettingsMsg.SID -> settings(arm, frame.payload, now)
            LaunchMsg.SID -> launch(frame.payload)
            LoggerMsg.SID -> logger(arm, frame.payload)
            else -> diag.event("sid", "unmodeled sid 0x${frame.sid.toString(16)} — ignored")
        }
    }

    /** Advance modeled time: lease expiry fails OPEN — stock LVGL repaints over us
     *  (settings_ext.c). The shadow survives; the PANEL is what stock clobbers. */
    @Synchronized
    fun tick(now: Long) {
        for (arm in Arm.entries) {
            val c = ctx(arm)
            if (c.leaseDeadline != 0L && now >= c.leaseDeadline) {
                c.leaseDeadline = 0
                // The texture cache is lease-scoped: the firmware frees it when the
                // lease ends, so a resumed session must upload its atlas again —
                // unless CACHE_KEEP was armed (FIRMWARE.md §3, F1.5).
                val kept = leaseEnded(arm, c)
                // Stock repaint: the panel no longer shows our frame.
                stockPattern(c.panel)
                c.panelStale = true
                diag.event("lease", "$arm FB lease EXPIRED — stock repainted over us " +
                    "(fail-open); texture cache ${if (kept) "KEPT under CACHE_KEEP" else "freed"}")
                diag.panelChanged(arm)
            }
        }
    }

    /** The firmware's own lease predicate. NOTE this is not a pure query: like
     *  `cfw_fb_lease_active()`, noticing a lapse releases the texture cache. */
    @Synchronized
    fun leaseHeld(arm: Arm, now: Long): Boolean = fbLeaseActive(arm, now)

    /** The BLE link ended: per-connection state goes — the EvenHub page (G2CC
     *  observed the slot ending with a lens drop), the prelude, a half-received
     *  image. Leases (time-based) and broken sessions (firmware RAM) persist:
     *  which is which on real hardware is graded U. */
    @Synchronized
    fun linkReset() {
        layoutCreated = false
        warmupPending = false
        preludeSeen = false
        img = null
        diag.event("launch", "link reset: page and prelude state cleared")
    }

    // ------------------------------------------------------------------ EvenHub
    private fun evenHub(arm: Arm, payload: ByteArray, now: Long) {
        val fields = try { Pb.fields(payload) } catch (e: IllegalArgumentException) {
            diag.event("proto", "unparseable e0 payload: ${e.message}"); return
        }
        val cmd = (fields.firstOrNull { it.field == 1 }?.varint ?: -1L).toInt()
        val msgIdRaw = fields.firstOrNull { it.field == 2 }?.varint ?: -1L
        if (msgIdRaw > 0xFF) {
            // THE msgId RULE: >255 encodes as a 2-byte varint and the glasses
            // silently reject the frame and drop the app slot. No ack. Ever.
            diag.event("msgid", "msgId $msgIdRaw > 255 — frame SILENTLY dropped (app slot dead)")
            return
        }
        val msgId = msgIdRaw.toInt()
        when (cmd) {
            EvenHubMsg.CMD_CREATE -> {
                if (!preludeSeen) {
                    diag.event("launch", "CREATE with no connect prelude — acked, but the page never " +
                        "becomes active (modeled strict; the firmware's requirement is unverified)")
                    ack(cmd, msgId, null)
                    return
                }
                layoutCreated = true
                // LoggerMsg: every display-thread app start ends the log stream (FUN_0044227E);
                // the page starts on both lenses (graded I), so both switches clear
                left.loggerOn = false; right.loggerOn = false
                carrierLost = false       // a new slot — the one thing that ends a §37.0 refusal
                warmupPending = true      // the first image burst after CREATE is silently dropped
                img = null
                ack(cmd, msgId, null)
            }
            EvenHubMsg.CMD_IMAGE -> imageFragment(arm, fields, msgId, now)
            EvenHubMsg.CMD_TEXT_UPGRADE -> ack(cmd, msgId, null)
            EvenHubMsg.CMD_KEEPALIVE -> { keepalivesSeen++; ack(cmd, msgId, null) }
            EvenHubMsg.CMD_SHUTDOWN -> { layoutCreated = false; ack(cmd, msgId, null) }
            else -> diag.event("evenhub", "unmodeled Cmd $cmd — acked")
                .also { ack(cmd, msgId, null) }
        }
    }

    private fun imageFragment(arm: Arm, fields: List<Pb.Field>, msgId: Int, now: Long) {
        val wrapper = fields.firstOrNull { it.field == 5 }?.bytes ?: run {
            diag.event("image", "Cmd=3 with no ImgRawMsg wrapper"); return
        }
        val session = (Pb.varintField(wrapper, 3) ?: 0L).toInt()
        val total = (Pb.varintField(wrapper, 4) ?: 0L).toInt()
        val compressMode = (Pb.varintField(wrapper, 5) ?: 0L).toInt()
        val fragIdx = (Pb.varintField(wrapper, 6) ?: 0L).toInt()
        val data = Pb.bytesField(wrapper, 8) ?: ByteArray(0)

        if ((silentMode && !warmupPending) || carrierLost) {
            // measured 2026-09-05 (§36): a 37-byte clock delta and an 860-byte
            // keyframe refused alike, every fragment, until the mode is off —
            // and (§37.0) after it is off, until a CREATE. The warmup right
            // after a CREATE passes: the glass acked a fresh session's warmup
            // (and, once, its first flush) under Silent Mode at 22:04:02; the
            // model keeps only the warmup exception, the stricter side.
            if (carrierLost && !silentMode)
                diag.event("silent", "image refused: the app slot ended under Silent Mode and no CREATE has come since")
            errorAck(msgId, session, total, fragIdx)
            return
        }
        if (compressMode != 0) {
            // An unknown CompressMode is silently treated as raw — garbage, not an
            // error (overview.md §8). The CFW path must always send 0.
            diag.event("compressmode", "nonzero CompressMode $compressMode on the CFW path — " +
                "hardware would render garbage in silence")
        }
        // Stuck-session trap (overview.md §9.2, seen on our own wire): a session
        // adjacent to a broken one inherits its stale buffers.
        if (brokenSessions.any { kotlin.math.abs(it - session) <= 1 }) {
            diag.event("session", "session $session adjacent to a broken session — " +
                "fragment accepted but transfer will fail (bump MapSessionId by >=2)")
            errorAck(msgId, session, total, fragIdx)
            return
        }
        var s = img
        if (s == null || s.session != session) {
            if (fragIdx != 0) {
                diag.event("image", "fragment $fragIdx opens session $session (expected 0) — abort")
                abort(session, total, fragIdx, msgId)
                return
            }
            if (s != null) {
                // the model must be LOUD where the firmware is quiet (review
                // 2026-09-01 L7): a new session discarding a partial one is
                // exactly the interleaving defect this model exists to expose
                diag.event("image", "session $session opened while session ${s.session} held " +
                    "${s.buf.size()}/${s.total} B — partial DISCARDED (interleaved writes?)")
            }
            s = ImgSession(session, total)
            img = s
        }
        if (fragIdx != s.nextFrag) {
            diag.event("image", "fragment $fragIdx out of order (expected ${s.nextFrag}) — abort")
            abort(session, total, fragIdx, msgId)
            return
        }
        s.buf.write(data)
        s.nextFrag++
        // overrun checked BEFORE the ack (review 2026-09-01 L7): acking
        // success and then aborting made the transport read OK for a fragment
        // the session dropped — the error must be the FIRST answer
        if (s.buf.size() > s.total) {
            diag.event("image", "session $session overran declared total ${s.total} — abort")
            abort(session, total, fragIdx, msgId)
            return
        }
        ack(EvenHubMsg.CMD_IMAGE, msgId, null)
        if (s.buf.size() == s.total) {
            img = null
            val image = s.buf.toByteArray()
            if (!layoutCreated) {
                diag.event("image", "image completed with NO layout — acked but never painted " +
                    "(the dummy text container is missing)")
                return
            }
            if (warmupPending) {
                warmupPending = false
                diag.event("warmup", "first image burst after CREATE silently dropped (g2-kit gotcha)")
                return
            }
            // Cross-lens propagation: every completed image reaches BOTH lenses.
            for (lensArm in Arm.entries) dispatchTop(lensArm, image, now)
        }
    }

    private fun abort(session: Int, total: Int, fragIdx: Int, msgId: Int) {
        img = null
        brokenSessions.add(session)
        diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), EvenHubMsg.SID, EvenHubMsg.FLAG_ABORT,
            ByteArray(0), AaFrame.TYPE_RESPONSE).single())
        errorAck(msgId, session, total, fragIdx)
    }

    private fun errorAck(msgId: Int, session: Int, total: Int, fragIdx: Int) {
        // ImgResCmd with status (field 8) = APP_REQUEST_UPGRADE_IMAGE_RAW_DATA_FAILED
        // (5) — the failure shape seen in our own captures (overview.md §9.2).
        // It used to send 1, which is a CREATE failure and not reachable here.
        val res = Pb.cat(
            Pb.v(1, EvenHubMsg.IMG_CONTAINER_ID),
            Pb.v(3, session), Pb.v(4, total), Pb.v(6, fragIdx),
            Pb.v(8, 5),
        )
        val payload = Pb.cat(Pb.v(1, EvenHubMsg.CMD_IMAGE + 1), Pb.v(2, msgId), Pb.l(5, res))
        diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), EvenHubMsg.SID, EvenHubMsg.FLAG_ACK,
            payload, AaFrame.TYPE_RESPONSE).single())
    }

    private fun ack(cmd: Int, msgId: Int, error: Long?) {
        val ackType = if (cmd == EvenHubMsg.CMD_KEEPALIVE) cmd else cmd + 1
        // The real firmware ALWAYS carries a status in field 8, and its success
        // value differs per operation (4 for image raw data, 0 for a page create,
        // …). This model used to omit the field entirely on success, which is why
        // nothing offline caught the transport treating a non-zero status as a
        // failure — first light, 2026-08-30. Send what the glasses send.
        val status = error ?: EvenHubMsg.successStatusFor(cmd)
        val payload = Pb.cat(Pb.v(1, ackType), Pb.v(2, msgId), Pb.l(5, Pb.v(8, status)))
        // Acks return on the RIGHT arm regardless of which arm was written
        // (overview.md §7: "the ack returns on R either way").
        diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), EvenHubMsg.SID, EvenHubMsg.FLAG_ACK,
            payload, AaFrame.TYPE_RESPONSE).single())
        if (error != null) diag.event("ack", "error code $error")
    }

    // ------------------------------------------------------------- mode dispatch
    /** `FIRMWARE.md` §4: every image-lane refusal records the message's mode byte, a reason and
     *  the copy sequence at the time (fields 23–25); the C's dispatcher records in the same
     *  places, in the same order of checks, so the conformance vectors compare the record. */
    private fun refuse(c: LensCtx, src: ByteArray, reason: Int): Boolean {
        c.refSeen = true
        c.refMode = src[0].toInt() and 0xFF
        c.refReason = reason
        c.refSeq = c.presentSeq
        return false
    }

    /** One completed image message as the deferred worker takes it, at the top level. `FIRMWARE.md`
     *  §4: a message that could have changed the shadow (the modes that hold the display gate) and
     *  ended without presenting leaves those changes off the panel, so the next present goes whole. */
    private fun dispatchTop(arm: Arm, src: ByteArray, now: Long): Boolean {
        val c = ctx(arm)
        c.presented = false
        val ok = dispatchImage(arm, src, now)
        if (src.isNotEmpty() && (src[0].toInt() and 0x7F) in GATED_MODES && !c.presented) c.panelStale = true
        return ok
    }

    /** zlib_glue.c image_dispatch, per lens. present=false only inside mode 8; [batch] is the
     *  batch context a mode-8 message makes for its sub-messages. */
    private fun dispatchImage(arm: Arm, src: ByteArray, now: Long, present: Boolean = true, batch: BatchCtx? = null): Boolean {
        if (src.isEmpty()) return false
        val c = ctx(arm)
        val modeByte = src[0].toInt() and 0xFF
        val stereo = modeByte and CfwStereo != 0
        return when (modeByte and 0x7F) {
            6 -> {
                if (src.size < 3) {
                    // §4: a mode-3/6 message under 3 bytes is too short for its header — recorded as
                    // length on the way to the BMP path, before the fid ring is touched
                    diag.event("decode", "$arm mode-6 of ${src.size} B — too short for its header")
                    return refuse(c, src, REF_LENGTH)
                }
                cfwDiag(c, hasFid = false, fid = 0)
                val packed = try {
                    Zl.decodeCfw(src.copyOfRange(1, src.size), Geometry.PANEL_W * Geometry.PANEL_H)
                } catch (e: Exception) {
                    diag.event("decode", "$arm mode-6 decompress failed: ${e.message} — previous frame stays up")
                    return refuse(c, src, REF_STREAM)
                }
                packed.copyInto(c.shadow)
                c.seeded = true
                if (present) present(arm, now, batch)
                true
            }
            3 -> {
                if (src.size < 3) {
                    diag.event("decode", "$arm mode-3 of ${src.size} B — too short for its header")
                    return refuse(c, src, REF_LENGTH)
                }
                val boxOff = if (stereo) (if (fwSide(arm) == 2) 1 else 5) else 1
                val fidOff = if (stereo) 9 else 5
                val zOff = if (stereo) 11 else 7
                if (src.size < zOff + 1) {
                    diag.event("decode", "$arm mode-3 too short — rejected in silence"); return refuse(c, src, REF_LENGTH)
                }
                if (stereo) {
                    // firmware size-checks the pair: src[3]!=src[7] || src[4]!=src[8]
                    if (src[3] != src[7] || src[4] != src[8]) {
                        diag.event("decode", "$arm stereo boxes differ in SIZE — rejected in silence")
                        return refuse(c, src, REF_BOUNDS)
                    }
                }
                val l = (src[boxOff].toInt() and 0xFF) * 4
                val t = (src[boxOff + 1].toInt() and 0xFF) * 2
                val w = (src[boxOff + 2].toInt() and 0xFF) * 4
                val h = (src[boxOff + 3].toInt() and 0xFF) * 2
                if (w == 0 || h == 0 || l + w > Geometry.PANEL_W || t + h > Geometry.PANEL_H) {
                    diag.event("decode", "$arm mode-3 box ($l,$t ${w}x$h) out of bounds — " +
                        "rejected in SILENCE, previous frame stays up")
                    return refuse(c, src, REF_BOUNDS)
                }
                val fid = (src[fidOff].toInt() and 0xFF) or ((src[fidOff + 1].toInt() and 0xFF) shl 8)
                if (!c.seeded) diag.event("decode", "$arm mode-3 delta on an UNSEEDED shadow (no keyframe)")
                if (cfwDiag(c, hasFid = true, fid = fid)) {
                    // zlib_glue.c returns 0 (success) on a dup skip: standalone,
                    // no present happens; inside a mode-8 batch the REMAINING
                    // subs still apply — the sim must not abort the batch.
                    diag.event("fid", "$arm fid $fid duplicate in ring — delta SILENTLY SKIPPED")
                    return true
                }
                val packed = try {
                    Zl.decodeCfw(src.copyOfRange(zOff, src.size), w * h)
                } catch (e: Exception) {
                    diag.event("decode", "$arm mode-3 decompress failed: ${e.message}")
                    return refuse(c, src, REF_STREAM)
                }
                // Composite the tight box into the shadow. w is x4 so w/2 whole bytes,
                // and l is x4 so l/2 is a whole byte offset (the quantization's purpose).
                val rowBytes = w / 2
                for (row in 0 until h) {
                    System.arraycopy(packed, row * rowBytes, c.shadow, (t + row) * c.stride + l / 2, rowBytes)
                }
                if (present) present(arm, now, batch)
                true
            }
            9 -> {
                val need = if (stereo) 32 else 16
                if (src.size < 1 + need) { diag.event("decode", "$arm mode-9 short"); return refuse(c, src, REF_LENGTH) }
                var off = 1
                if (stereo && fwSide(arm) != 2) off += 16   // right lens uses the 2nd set
                fun rd16(i: Int) = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
                val sL = rd16(off); val sT = rd16(off + 2); val sW = rd16(off + 4); val sH = rd16(off + 6)
                val dL = rd16(off + 8); val dT = rd16(off + 10); val dW = rd16(off + 12); val dH = rd16(off + 14)
                if (sW == 0 || sH == 0 || sW != dW || sH != dH ||
                    sL + sW > Geometry.PANEL_W || sT + sH > Geometry.PANEL_H ||
                    dL + dW > Geometry.PANEL_W || dT + dH > Geometry.PANEL_H
                ) {
                    diag.event("decode", "$arm mode-9 rects invalid — rejected in silence")
                    return refuse(c, src, REF_BOUNDS)
                }
                rectCopy4bpp(c.shadow, c.stride, sL, sT, dL, dT, sW, sH)
                if (present) present(arm, now, batch)
                true
            }
            8 -> {
                if (!present) { diag.event("decode", "$arm nested mode-8 rejected"); return refuse(c, src, REF_MODE) }
                if (src.size > Geometry.MODE8_MAX) {
                    diag.event("decode", "$arm mode-8 over bmp_max — rejected in silence"); return refuse(c, src, REF_LENGTH)
                }
                if (src.size < 2) {
                    diag.event("decode", "$arm mode-8 of ${src.size} B — too short for a batch header, rejected")
                    return refuse(c, src, REF_LENGTH)
                }
                val count = src[1].toInt() and 0xFF
                var pos = 2
                val bctx = BatchCtx()                    // the clip and the hint live and die with this batch
                for (i in 0 until count) {
                    if (pos + 2 > src.size) { diag.event("decode", "$arm mode-8 truncated"); return refuse(c, src, REF_LENGTH) }
                    val segLen = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    if (segLen < 1 || pos + segLen > src.size) {
                        diag.event("decode", "$arm mode-8 bad seglen"); return refuse(c, src, REF_LENGTH)
                    }
                    val subMode = src[pos].toInt() and 0x7F
                    if (subMode !in CfwModes.BATCH_SUBMODES) {
                        diag.event("decode", "$arm mode-8 sub-mode $subMode rejected"); return refuse(c, src, REF_MODE)
                    }
                    if (subMode == 15) {
                        // The firmware WOULD accept and draw this. The model stops here
                        // because it cannot know the pixels — say which of the two this
                        // is, so nobody reads it as a hardware rejection at first light.
                        diag.event("decode", "$arm mode-8 sub $i is mode 15: the firmware " +
                            "would draw it, but this MODEL cannot predict its pixels, so " +
                            "the batch stops here. Damage does not emit mode 15 by design.")
                        return false
                    }
                    if (!dispatchImage(arm, src.copyOfRange(pos, pos + segLen), now, present = false, batch = bctx)) {
                        diag.event("decode", "$arm mode-8 sub $i FAILED — whole batch aborted")
                        return false                     // the sub-message recorded its own reason
                    }
                    pos += segLen
                }
                present(arm, now, bctx)
                true
            }
            5 -> true                                    // a sound on the buzzer: no display change, never refused
            10 -> {
                if (src.size < 2) return refuse(c, src, REF_LENGTH)
                if (src[1].toInt() == 0 || src[1].toInt() == 1) true else refuse(c, src, REF_VALUE)
            }
            7 -> {
                if (src.size >= 2 && (src[1].toInt() == 1 || src[1].toInt() == 2)) {
                    ctx(arm).overlayShown = src[1].toInt() == 2
                    diag.event("diag", "$arm mode-7 sub-${src[1]}: overlay ${if (src[1].toInt() == 2) "shown" else "hidden"}")
                }
                if (src.size >= 2 && src[1].toInt() == 0) {
                    val c2 = ctx(arm)
                    c2.fDup = false; c2.fSkip = false; c2.fReorder = false
                    c2.recentFids.fill(0xFFFF)
                    c2.diagSeen = false; c2.fidResync = false
                    c2.lastFid = 0; c2.highFid = 0
                    c2.refSeen = false; c2.refMode = 0; c2.refReason = 0; c2.refSeq = 0
                    diag.event("diag", "$arm mode-7 sub-0: flags and fid ring cleared")
                }
                true
            }
            11 -> {
                // cfw_cleanup_session(): hands the screen back. direct_lease_deadline
                // goes to 0 (so the repaint guard fails OPEN and stock takes over),
                // the texture cache is freed, snapshots are dropped, the overlay hides.
                // FIRMWARE.md §3: the cache goes regardless of CACHE_KEEP, the latch and
                // the self-test scratch with it.
                c.leaseDeadline = 0
                releaseCache(c)
                c.damageFlags = 0
                c.cacheKeepLatched = false
                c.lapseSettled = false
                freeScratch(c)
                c.slots.fill(null)
                stockPattern(c.panel)
                c.panelStale = true
                diag.event("cleanup", "$arm mode-11 session cleanup: FB lease released, " +
                    "texture cache freed, stock repaints")
                diag.panelChanged(arm)
                true
            }
            12 -> {
                // The firmware validates the WHOLE entry list before writing a byte,
                // so a malformed update leaves the cache untouched.
                var pos = 1
                var hasData = false
                while (pos < src.size) {
                    if (src.size - pos < 4) {
                        diag.event("decode", "$arm mode-12 entry header truncated — whole update rejected")
                        return refuse(c, src, REF_LENGTH)
                    }
                    val off = rd16at(src, pos)
                    val len = rd16at(src, pos + 2)
                    pos += 4
                    if (len > src.size - pos) {
                        diag.event("decode", "$arm mode-12 entry of $len B runs past the message — whole update rejected")
                        return refuse(c, src, REF_LENGTH)
                    }
                    if (off + len > CfwModes.TEXTURE_CACHE_SIZE) {
                        diag.event("decode", "$arm mode-12 entry [$off,${off + len}) leaves the v1 window " +
                            "— whole update rejected in silence")
                        return refuse(c, src, REF_RECORD)
                    }
                    if (len > 0) hasData = true
                    pos += len
                }
                if (!hasData) return true                    // firmware returns 0, writes nothing
                if (!fbLeaseActive(arm, now)) {
                    diag.event("decode", "$arm mode-12 with NO framebuffer lease — rejected " +
                        "(the cache is lease-scoped)")
                    return refuse(c, src, REF_NO_LEASE)
                }
                val cache = c.textureCache ?: allocateCache(arm, c)
                pos = 1
                while (pos < src.size) {
                    val off = rd16at(src, pos)
                    val len = rd16at(src, pos + 2)
                    pos += 4
                    src.copyInto(cache, off, pos, pos + len)
                    pos += len
                }
                c.cacheGen++                                 // FIRMWARE.md §3 (F1.5)
                true
            }
            // `FIRMWARE.md` §0: mode 16 is a Damage build's, 17–24 contract 2's; on any other build they
            // are modes with no handler (recorded 8, then the stock BMP loader refuses them)
            16 -> if (damageContract != null) selfTest(arm, c, src, now) else noHandler(arm, c, src)
            in 17..24 -> if ((damageContract ?: 0) >= 2) dispatchV2(arm, c, src, now, present, batch) else noHandler(arm, c, src)
            13, 14 -> {
                // the C's order: the message's length, then the lease, then the records
                val m = modeByte and 0x7F
                if (m == 13 && src.size != 8) {
                    diag.event("decode", "$arm mode-13 is ${src.size} B; the firmware wants exactly 8")
                    return refuse(c, src, REF_LENGTH)
                }
                if (m == 14 && src.size < 9) { diag.event("decode", "$arm mode-14 too short"); return refuse(c, src, REF_LENGTH) }
                if (!fbLeaseActive(arm, now)) {
                    diag.event("decode", "$arm mode-$m with NO framebuffer " +
                        "lease — rejected in silence")
                    return refuse(c, src, REF_NO_LEASE)
                }
                // The firmware imposes no keyframe requirement here, but a cached draw
                // onto an unseeded shadow is still a design error: present_shadow pushes
                // the WHOLE panel, and on glass the shadow is the container's display
                // buffer A holding whatever was there before, not the zeroes we start at.
                if (!c.seeded) diag.event("decode", "$arm mode-$m onto an " +
                    "UNSEEDED shadow (no keyframe) — the model shows black around it, " +
                    "the glass shows stale buffer content")
                val ok = if (m == 13) drawCachedImage(arm, c, src)
                else drawCachedText(arm, c, src)
                if (!ok) return false
                if (present) present(arm, now, batch)
                true
            }
            15 -> {
                // Legal firmware feature; Damage never emits it. Mode 15 renders with
                // the stock LVGL 20 px font chain that lives inside the firmware, so
                // this model cannot predict its pixels — and a belief the model cannot
                // reproduce would quietly break the per-lens oracle. Loud, not silent.
                diag.event("decode", "$arm mode-15 (builtin-font text) is not modeled: its " +
                    "glyphs come from the firmware's own font, so no offline model can " +
                    "know the resulting pixels. Damage does not emit mode 15 by design.")
                false
            }
            else -> noHandler(arm, c, src)
        }
    }

    private fun noHandler(arm: Arm, c: LensCtx, src: ByteArray): Boolean {
        val mode = src[0].toInt() and 0x7F
        diag.event("decode", "$arm unmodeled mode $mode — BMP fallback would run")
        // a BMP ('B') records nothing; any other mode is recorded as unknown, then the
        // stock loader refuses it as the firmware does
        return if (mode != 0x42) refuse(c, src, REF_MODE) else false
    }

    /** The texture cache's allocation: the session's size (op 5's, or the 64 KiB default). */
    private fun allocateCache(arm: Arm, c: LensCtx): ByteArray {
        val size = cacheSizeOf(c)
        return ByteArray(size).also {
            c.textureCache = it
            c.cacheBytes = size
            diag.event("texture", "$arm texture cache allocated and zeroed ($size B)")
        }
    }

    private fun cacheSizeOf(c: LensCtx): Int = if (c.cacheBytes != 0) c.cacheBytes else CfwModes.TEXTURE_CACHE_SIZE

    /** cfw_texture_cache_release: the cache goes, and with it the size asked for. */
    private fun releaseCache(c: LensCtx) {
        c.textureCache = null
        c.cacheBytes = 0
    }

    /** The self-test's scratch and its save-under slots go together (damage_self_test_release). */
    private fun freeScratch(c: LensCtx) {
        c.stShadow = null
        c.stSlots.fill(null)
    }

    /**
     * `settings_ext.c cfw_fb_lease_active()` — the ONE predicate the firmware uses
     * for modes 12–15, for long-press forwarding and for suppressing the stock quit
     * dialog. Detecting a lapse is itself a release point in the C, so the texture
     * cache goes with it here too, lazily, on whatever call notices first.
     *
     * The deadline is deliberately left standing so `tick()` can still model stock's
     * repaint over us exactly once; the C's `direct_lease_deadline = 0` there is
     * about its own bookkeeping, not about what the wearer sees.
     */
    private fun fbLeaseActive(arm: Arm, now: Long): Boolean {
        val c = ctx(arm)
        // No lease held: there is no expiry to notice, and a check is none of the four
        // release points (FIRMWARE.md §3) — whatever the flags hold stays until one comes
        // (2026-09-14, second review: this check used to clear them, the C's does not)
        if (c.leaseDeadline == 0L) return false
        if (now >= c.leaseDeadline) {
            val had = c.textureCache != null
            val kept = leaseEnded(arm, c)
            if (had) diag.event("texture", "$arm texture cache ${if (kept) "kept under CACHE_KEEP" else "freed"}: the FB lease has lapsed")
            return false
        }
        return true
    }

    /** `FIRMWARE.md` §3 (F1.5), the fork's damage_lease_ended: a lapse noticed or an
     *  FB_RELEASE. The CACHE_KEEP flag, read before the flags clear, is latched for the
     *  fresh acquire that follows and decides the cache now; the self-test scratch never
     *  outlives the lease. Returns whether the cache was kept. */
    private fun leaseEnded(arm: Arm, c: LensCtx): Boolean {
        freeScratch(c)
        if (!c.lapseSettled) {
            c.lapseSettled = true
            c.cacheKeepLatched = c.damageFlags and DamageMsg.FLAG_CACHE_KEEP != 0
            if (!c.cacheKeepLatched) releaseCache(c)
            else if (c.textureCache == null) c.cacheBytes = 0     // the size asked for goes, kept cache or not (§4)
            c.slots.fill(null)                           // save-under: freed at every release point
            c.damageFlags = 0
            c.panelStale = true                          // stock repaints after a release point (§4)
        }
        // A lapse is noticed ONCE (the guard above; the deadline left standing re-enters
        // here on every later check and must change nothing). An FB_RELEASE is its own
        // release point and clears the flags regardless — its handler does that, so a
        // FLAGS_SET taken after a settled lapse does not survive it (2026-09-14, second review).
        return c.cacheKeepLatched && c.textureCache != null
    }

    /** damage_lease_fresh_acquire: an acquire with no live lease. A lapse nobody
     *  settled is settled first; then the latch decides whether the cache carries over,
     *  and the next lease starts unsettled. */
    private fun leaseFreshAcquire(arm: Arm, c: LensCtx) {
        if (!c.lapseSettled) leaseEnded(arm, c)
        freeScratch(c)
        if (!c.cacheKeepLatched) releaseCache(c)
        else if (c.textureCache == null) c.cacheBytes = 0
        c.slots.fill(null)
        c.cacheKeepLatched = false
        c.lapseSettled = false
        c.damageFlags = 0
        c.panelStale = true
    }

    /** The 21-byte mic-configuration read-back (`mic_control.c mic_append_status`).
     *  Modeled for its SHAPE only — it trails every sid-0x09 read response, so a
     *  parser that assumes the capability field is last must fail here, not on glass. */
    private fun micStatusBody(): ByteArray {
        val b = ByteArray(21)
        b[0] = 'M'.code.toByte(); b[1] = 'C'.code.toByte(); b[2] = 1
        return b
    }

    private fun rd16at(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** texture_cache.c cfw_texture_image_at: [w:u8][h:u8][RLE of exactly w*h pixels]. */
    private class CachedImage(val w: Int, val h: Int, val levels: ByteArray)

    private fun imageAt(arm: Arm, c: LensCtx, offset: Int, what: String): CachedImage? {
        val cache = c.textureCache ?: run {
            diag.event("decode", "$arm $what: no texture cache has been written"); return null
        }
        if (offset < 0 || offset > CfwModes.TEXTURE_CACHE_SIZE - 2) {
            diag.event("decode", "$arm $what: offset $offset out of the cache"); return null
        }
        val w = cache[offset].toInt() and 0xFF
        val h = cache[offset + 1].toInt() and 0xFF
        if (w == 0 || h == 0) {
            diag.event("decode", "$arm $what: cache offset $offset holds no image " +
                "(${w}x$h) — rejected in silence")
            return null
        }
        val avail = CfwModes.TEXTURE_CACHE_SIZE - offset - 2
        // Catch only what the decoder raises for malformed CACHE CONTENT. A model
        // defect must not come back dressed as a firmware rejection.
        val levels = try {
            decodeCachedRle(cache, offset + 2, avail, w * h)
        } catch (e: IllegalStateException) {
            diag.event("decode", "$arm $what: malformed RLE at $offset (${e.message})")
            return null
        }
        return CachedImage(w, h, levels)
    }

    /** The firmware's scanner: walk tokens until exactly [pixels] are produced,
     *  never reading past the cache, rejecting zero counts and overruns. */
    private fun decodeCachedRle(cache: ByteArray, start: Int, avail: Int, pixels: Int): ByteArray {
        val out = ByteArray(pixels)
        var n = 0
        var pos = 0
        while (n < pixels) {
            if (pos >= avail) throw IllegalStateException("ran out of cache")
            val op = cache[start + pos].toInt() and 0xFF
            val color = op and 0x0F
            var cnt = op shr 4
            var used = 1
            if (cnt == 0) {
                if (pos + 1 >= avail) throw IllegalStateException("truncated 8-bit escape")
                cnt = cache[start + pos + 1].toInt() and 0xFF
                used = 2
                if (cnt == 0) {
                    if (pos + 3 >= avail) throw IllegalStateException("truncated 16-bit escape")
                    cnt = (cache[start + pos + 2].toInt() and 0xFF) or
                        ((cache[start + pos + 3].toInt() and 0xFF) shl 8)
                    used = 4
                    if (cnt == 0) throw IllegalStateException("zero-length run")
                }
            }
            if (cnt > pixels - n) throw IllegalStateException("run $cnt overruns $pixels pixels")
            repeat(cnt) { out[n++] = color.toByte() }
            pos += used
        }
        return out
    }

    /** cfw_texture_make_lut: lut[i] = (source * top) / 15, source reversed if INVERSE. */
    private fun makeLut(options: Int): IntArray {
        val top = options and 0x0F
        return IntArray(16) { i ->
            val source = if (options and CfwModes.OPT_INVERSE != 0) 15 - i else i
            (source * top) / 15
        }
    }

    /** cfw_texture_render: clip to the panel; transparency tests the ORIGINAL
     *  source level, before the LUT, so colour 0 is skipped even for an inverse ramp. */
    private fun renderCached(c: LensCtx, img: CachedImage, x0: Int, y0: Int, options: Int) {
        val lut = makeLut(options)
        val transparent = options and CfwModes.OPT_TRANSPARENT != 0
        for (p in img.levels.indices) {
            val color = img.levels[p].toInt() and 0x0F
            if (transparent && color == 0) continue
            val x = x0 + p % img.w
            val y = y0 + p / img.w
            if (x < 0 || y < 0 || x >= Geometry.PANEL_W || y >= Geometry.PANEL_H) continue
            val idx = y * c.stride + (x shr 1)
            val b = c.shadow[idx].toInt() and 0xFF
            c.shadow[idx] = (if (x and 1 == 1) (b and 0xF0) or lut[color]
            else (b and 0x0F) or (lut[color] shl 4)).toByte()
        }
    }

    /** Mode 13: [13][off16][x16][y16][opt8] — payload after the mode byte is exactly 7. */
    private fun drawCachedImage(arm: Arm, c: LensCtx, src: ByteArray): Boolean {
        val img = imageAt(arm, c, rd16at(src, 1), "mode-13") ?: return refuse(c, src, REF_RECORD)
        renderCached(c, img, rd16at(src, 3), rd16at(src, 5), src[7].toInt() and 0xFF)
        return true
    }

    /** Mode 14: [14][font16][x16][y16][opt8][len8][bytes]. Every character is
     *  validated before ANY glyph is drawn, so one bad byte drops the whole line. */
    private fun drawCachedText(arm: Arm, c: LensCtx, src: ByteArray): Boolean {
        val fontOffset = rd16at(src, 1)
        val strLen = src[8].toInt() and 0xFF
        if (src.size != 9 + strLen) {
            diag.event("decode", "$arm mode-14 length ${src.size} != ${9 + strLen} for its " +
                "declared string length")
            return refuse(c, src, REF_LENGTH)
        }
        if (fontOffset > CfwModes.TEXTURE_CACHE_SIZE - CfwModes.FONT_TABLE_BYTES) {
            diag.event("decode", "$arm mode-14 font table at $fontOffset does not fit"); return refuse(c, src, REF_RECORD)
        }
        val cache = c.textureCache ?: run {
            diag.event("decode", "$arm mode-14: no texture cache has been written"); return refuse(c, src, REF_RECORD)
        }
        val options = src[7].toInt() and 0xFF
        val glyphs = ArrayList<Pair<Int, CachedImage?>>(strLen)
        for (i in 0 until strLen) {
            val ch = src[9 + i].toInt() and 0xFF
            if (ch in 1..31) { glyphs += ch to null; continue }
            if (ch < 32 || ch > 127) {
                diag.event("decode", "$arm mode-14 byte $ch at $i is neither an x adjust " +
                    "(1..31) nor a glyph (32..127) — the WHOLE string is rejected")
                return refuse(c, src, REF_CODE)
            }
            val off = rd16at(cache, fontOffset + (ch - 32) * 2)
            val img = imageAt(arm, c, off, "mode-14 glyph ${ch.toChar()}") ?: return refuse(c, src, REF_RECORD)
            glyphs += ch to img
        }
        var x = rd16at(src, 3)
        val y = rd16at(src, 5)
        for ((ch, img) in glyphs) {
            if (img == null) { x += ch - 11; continue }
            renderCached(c, img, x, y, options)
            x += img.w
        }
        return true
    }

    /** zlib_glue.c cfw_diag(), verbatim semantics. Returns true = duplicate, skip. */
    private fun cfwDiag(c: LensCtx, hasFid: Boolean, fid: Int): Boolean {
        c.diagSeen = true            // debug.c sets this on entry, before the has_fid branch
        if (!hasFid) { c.fidResync = true; return false }
        for (f in c.recentFids) if (f == fid) { c.fDup = true; return true }
        if (!c.fidResync) {
            val d = (fid - c.lastFid) and 0xFFFF
            if (d >= 0x8000) c.fReorder = true
            else if (d > 1) c.fSkip = true
        }
        c.fidResync = false
        c.lastFid = fid
        if (fid > c.highFid) c.highFid = fid
        c.recentFids[c.recentPos] = fid
        c.recentPos = (c.recentPos + 1) % Geometry.CFW_FID_RING
        return false
    }

    private fun present(arm: Arm, now: Long, batch: BatchCtx? = null) {
        val c = ctx(arm)
        if (c.stActive) return            // a self-test step: present_shadow publishes nothing
        // `FIRMWARE.md` §4, mode 24: on a JBD4010 pair a hinted present transfers only rows
        // y0..y1 to the panel (the framebuffer holds the whole shadow; the panel shows the rest
        // at its next full refresh) — so a hint that misses a changed row is visible HERE, in
        // the oracle, before it is on glass. Any other panel: the full refresh.
        // §4: the hint is honoured only while the panel still shows the whole previous frame and no
        // overlay sits in the framebuffer; otherwise this frame goes whole (the second review)
        val hide = !c.overlayShown
        val rows = batch?.hint?.takeIf { panelRecord == PANEL_JBD4010 && !c.panelStale && hide && !c.overlayInFb }
        c.panelStale = false
        c.overlayInFb = !hide
        c.presented = true
        if (c.leaseDeadline <= now) {
            // No lease: our present lands, but stock will clobber it on its next
            // repaint. Model the present as landing, then rely on tick() for the
            // clobber; a real session must simply hold the lease.
            diag.event("lease", "$arm present WITHOUT a live FB lease — stock will repaint over this")
        }
        if (rows == null) c.shadow.copyInto(c.panel)
        else System.arraycopy(c.shadow, rows.first * c.stride, c.panel, rows.first * c.stride, (rows.last - rows.first + 1) * c.stride)
        c.lastPath = if (rows == null) 0 else 1
        diag.panelChanged(arm)
        // F1.3: the copy hook counts the direct frame; the refresh that follows is timed
        // (0 here: the model has no clock for it) and, under PRESENTED, reported — by
        // RIGHT only, the sender's lens rule
        c.presentSeq++
        if (c.damageFlags and DamageMsg.FLAG_PRESENTED != 0 && arm == Arm.RIGHT) {
            val body = Pb.cat(Pb.v(1, c.presentSeq), Pb.v(2, 0), Pb.v(3, 0), Pb.v(4, 0), Pb.v(5, fwSide(arm)),
                if ((damageContract ?: 0) >= 2) Pb.v(6, c.lastPath) else ByteArray(0))
            diag.notify(arm, AaFrame.frame(nextSeq(), SettingsMsg.SID, SettingsMsg.FLAG_RESPONSE,
                Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(DamageMsg.PRESENTED_FIELD, body)), AaFrame.TYPE_RESPONSE).single())
        }
    }

    // ------------------------------------------------------------- the self-test (mode 16)
    /** `FIRMWARE.md` §3: [16][0] begin (the lease held; a zeroed scratch shadow), [16][1][msg]
     *  a step — the message through the same dispatcher against the scratch, presents
     *  suppressed, the self-test's own frame-order diagnostics swapped in — then the scratch
     *  CRC-32, the step count and the refusal recorded; [16][2] end. A step that cannot run
     *  (no begin, the lease lapsed) is refused and not counted. Modes 13/14 read the live
     *  cache; a cache write or a non-drawing mode is refused and counted. */
    private fun selfTest(arm: Arm, c: LensCtx, src: ByteArray, now: Long): Boolean {
        if (src.size < 2) return refuse(c, src, REF_LENGTH)       // `FIRMWARE.md` §4: every image-lane refusal records
        when (src[1].toInt() and 0xFF) {
            0 -> {
                if (!fbLeaseActive(arm, now)) { diag.event("selftest", "$arm begin refused: no FB lease"); return refuse(c, src, REF_NO_LEASE) }
                c.stShadow = ByteArray(c.stride * Geometry.PANEL_H)
                c.stSeq = 0; c.stRefused = false; c.stCrc = 0; c.stDiag = DiagState(); c.stSeeded = false
                c.stSlots.fill(null)                     // a begin starts with empty save-under slots
                diag.event("selftest", "$arm begin: scratch shadow allocated and zeroed")
                return true
            }
            2 -> { freeScratch(c); diag.event("selftest", "$arm end: scratch freed"); return true }
            1 -> {
                if (src.size < 3) return refuse(c, src, REF_LENGTH)
                if (!fbLeaseActive(arm, now)) { diag.event("selftest", "$arm step refused: the lease lapsed (scratch freed)"); return refuse(c, src, REF_NO_LEASE) }
                val scratch = c.stShadow ?: run { diag.event("selftest", "$arm step refused: no begin"); return refuse(c, src, REF_SCRATCH) }
                val msg = src.copyOfRange(2, src.size)
                var ok = false
                if ((msg[0].toInt() and 0x7F) in CfwModes.SELF_TEST_MODES) {
                    val live = c.shadow; val liveSeeded = c.seeded
                    c.shadow = scratch; c.seeded = c.stSeeded
                    swapDiag(c); swapSlots(c)            // the self-test's diagnostics and save-under slots, not the session's
                    c.stActive = true
                    try { ok = dispatchImage(arm, msg, now) } finally {
                        c.stActive = false
                        swapSlots(c); swapDiag(c)
                        c.stSeeded = c.seeded
                        c.shadow = live; c.seeded = liveSeeded
                    }
                } else {
                    diag.event("selftest", "$arm step refused: mode ${msg[0].toInt() and 0x7F} is not a drawing message")
                    refuse(c, msg, REF_MODE)             // recorded with the step's message, as the C does
                }
                c.stSeq++
                c.stRefused = !ok
                c.stCrc = java.util.zip.CRC32().also { it.update(scratch) }.value
                return ok
            }
            else -> return refuse(c, src, REF_VALUE)
        }
    }

    private fun swapSlots(c: LensCtx) {
        val t = c.slots
        c.slots = c.stSlots
        c.stSlots = t
    }

    private fun swapDiag(c: LensCtx) {
        val s = c.stDiag
        val t = DiagState()
        t.lastFid = c.lastFid; t.highFid = c.highFid; t.fidResync = c.fidResync; t.diagSeen = c.diagSeen
        t.fDup = c.fDup; t.fSkip = c.fSkip; t.fReorder = c.fReorder; t.recentPos = c.recentPos
        c.recentFids.copyInto(t.recentFids)
        c.lastFid = s.lastFid; c.highFid = s.highFid; c.fidResync = s.fidResync; c.diagSeen = s.diagSeen
        c.fDup = s.fDup; c.fSkip = s.fSkip; c.fReorder = s.fReorder; c.recentPos = s.recentPos
        s.recentFids.copyInto(c.recentFids)
        c.stDiag = t
    }

    // ------------------------------------------------- direct seams for tests
    // Unit-testing the mode dispatcher means handing it a message and reading the
    // resulting pixels. Everything else still goes the long way round through
    // write() and the reassembler; these three exist so a decode test does not
    // have to build a whole EvenHub image session to exercise one mode byte.

    /** Grant [arm] a framebuffer lease expiring at [deadline] (modeled clock). */
    @Synchronized
    fun forceLease(arm: Arm, deadline: Long) { ctx(arm).leaseDeadline = deadline }

    /** Dispatch one reassembled image message as the deferred worker would. */
    @Synchronized
    fun dispatchForTest(arm: Arm, src: ByteArray, now: Long): Boolean =
        dispatchTop(arm, src, now)

    /** Paint the whole shadow one 4bpp [level] — a known background to draw onto. */
    @Synchronized
    fun fillShadowForTest(arm: Arm, level: Int) {
        require(level in 0..15) { "level $level" }
        ctx(arm).shadow.fill(((level shl 4) or level).toByte())
    }

    private fun rectCopy4bpp(shadow: ByteArray, stride: Int, sL: Int, sT: Int, dL: Int, dT: Int, w: Int, h: Int) {
        // Nibble-accurate copy with overlap safety: stage the source region first.
        val tmp = Array(h) { IntArray(w) }
        for (y in 0 until h) for (x in 0 until w) tmp[y][x] = getNibble(shadow, stride, sL + x, sT + y)
        for (y in 0 until h) for (x in 0 until w) setNibble(shadow, stride, dL + x, dT + y, tmp[y][x])
    }

    private fun getNibble(buf: ByteArray, stride: Int, x: Int, y: Int): Int {
        val b = buf[y * stride + (x shr 1)].toInt() and 0xFF
        return if (x and 1 == 0) b shr 4 else b and 0x0F
    }

    private fun setNibble(buf: ByteArray, stride: Int, x: Int, y: Int, v: Int) {
        val i = y * stride + (x shr 1)
        val b = buf[i].toInt() and 0xFF
        buf[i] = (if (x and 1 == 0) (v shl 4) or (b and 0x0F) else (b and 0xF0) or v).toByte()
    }

    private fun stockPattern(panel: ByteArray) {
        // A visibly-not-ours pattern standing in for the stock LVGL dashboard.
        panel.fill(0)
        val stride = (Geometry.PANEL_W + 1) / 2
        for (y in 100 until 110) for (xb in 40 until stride - 40) panel[y * stride + xb] = 0x55
    }

    // ------------------------------------------------------------------ launch (sid 0x01)
    /** The connect prelude: acked on RIGHT with the request type and msgId echoed
     *  (the reference resolves it on (sid, msgId)). */
    private fun launch(payload: ByteArray) {
        val fields = try { Pb.fields(payload) } catch (e: IllegalArgumentException) {
            diag.event("proto", "unparseable 01 payload"); return
        }
        val type = (fields.firstOrNull { it.field == 1 }?.varint ?: -1L).toInt()
        val msgIdRaw = fields.firstOrNull { it.field == 2 }?.varint ?: -1L
        if (msgIdRaw > 0xFF) {
            diag.event("msgid", "sid-0x01 msgId $msgIdRaw > 255 — frame SILENTLY dropped")
            return
        }
        preludeSeen = true
        preludeAcks++
        diag.event("launch", "connect prelude (type $type, msgId $msgIdRaw) acked")
        diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), LaunchMsg.SID, LaunchMsg.FLAG_RESPONSE,
            LaunchMsg.response(type, msgIdRaw.toInt()), AaFrame.TYPE_RESPONSE).single())
    }

    // ------------------------------------------------------------------ settings
    private fun settings(arm: Arm, payload: ByteArray, now: Long) {
        val fields = try { Pb.fields(payload) } catch (e: IllegalArgumentException) {
            diag.event("proto", "unparseable 09 payload"); return
        }
        val damage = fields.firstOrNull { it.field == DamageMsg.CONTROL_FIELD }?.bytes
        if (damage != null && damageContract != null) {
            damageControl(arm, damage, now)
            if (arm == Arm.LEFT && duplicateControlReplies) {
                // the forwarded copy, answered again by RIGHT a few ms later — later, as on the
                // glasses, so a waiter registered in between sees it (the keeper's race)
                val copy = damage.copyOf()
                val later = duplicateReplyDelayMs
                Thread {
                    Thread.sleep(later)
                    synchronized(this) { damageControl(Arm.RIGHT, copy, now + later) }
                }.apply { isDaemon = true }.start()
            }
            return
        }
        val control = fields.firstOrNull { it.field == SettingsMsg.CONTROL_FIELD }?.bytes
        if (control != null && control.size == 6 && control[0] == 'F'.code.toByte() &&
            control[1] == 'C'.code.toByte()
        ) {
            when (control[3].toInt()) {
                SettingsMsg.OP_FB_ACQUIRE -> {
                    // settings_ext.c: "A fresh lease must earn preservation with a
                    // newly presented direct frame; a renewal keeps the current one."
                    // A FRESH acquire — no lease, or one already lapsed — releases the
                    // texture cache; a renewal of a live lease keeps it.
                    val c = ctx(arm)
                    // NOTE the order: fbLeaseActive() is not a pure query — it
                    // releases the cache itself when it notices a lapse, so the
                    // "was there one?" question has to be asked BEFORE it runs
                    // or this narration can never fire (review 2026-09-02)
                    val hadCache = c.textureCache != null
                    if (c.leaseDeadline == 0L || now >= c.leaseDeadline) {
                        leaseFreshAcquire(arm, c)
                        if (hadCache)
                            diag.event("texture", "$arm fresh FB lease after a lapse — " +
                                (if (c.textureCache != null) "texture cache carried over under CACHE_KEEP (FIRMWARE.md §3)"
                                else "texture cache freed; the atlas must be uploaded again"))
                    }
                    c.leaseDeadline = now + SettingsMsg.LEASE_EXPIRY_MS
                    diag.event("lease", "$arm FB lease acquired/renewed (90 s)")
                }
                SettingsMsg.OP_FB_RELEASE -> {
                    releasesSeen++
                    ctx(arm).leaseDeadline = 0
                    val kept = leaseEnded(arm, ctx(arm))  // settings_ext.c releases the cache here, or keeps it (F1.5)
                    ctx(arm).damageFlags = 0              // FIRMWARE.md §3: a release point clears the flags, a settled lapse before it or not
                    stockPattern(ctx(arm).panel)
                    ctx(arm).panelStale = true
                    diag.event("lease", "$arm FB lease released — stock repaints, " +
                        "texture cache ${if (kept) "kept under CACHE_KEEP" else "freed"}")
                    diag.panelChanged(arm)
                }
                else -> diag.event("lease", "$arm control op ${control[3]} (unmodeled)")
            }
            return
        }
        val cmdId = (Pb.varintField(payload, 1) ?: 0L).toInt()
        if (cmdId == 2) {
            // Settings READ -> response carrying the capability string in field 100.
            // Field 104 (the 21-byte mic read-back) trails it on every response since
            // a5d1c31 — modeled so a parser that assumes field 100 is last fails HERE
            // rather than on glass. Contents are inert for us; only the shape matters.
            // Field 4 is the stock device-info block; battery=12 / charging=13
            // (G2CC docs/G2_BLE_PROTOCOL.md §10, capture-confirmed) — modeled so
            // the chrome's battery path is exercised offline.
            val msgId = (Pb.varintField(payload, 2) ?: 0L).toInt()
            val resp = Pb.cat(
                Pb.v(1, 2), Pb.v(2, msgId),
                Pb.l(4, Pb.cat(Pb.v(12, batteryPct), Pb.v(13, 0),
                    if (reportSilentRestored) Pb.v(SettingsMsg.SILENT_RESTORED_FIELD, if (silentMode) 1 else 0) else ByteArray(0))),
                Pb.l(SettingsMsg.CAPABILITY_FIELD, capabilityString.toByteArray(Charsets.UTF_8)),
                Pb.l(SettingsMsg.MIC_STATUS_FIELD, micStatusBody()),
                damageContract?.let { v ->
                    Pb.l(DamageMsg.CAPS_FIELD, Pb.cat(Pb.s(1, "DMG"), Pb.v(2, v),
                        Pb.v(3, if (v >= 2) DamageMsg.PHASE2_FEATURES else DamageMsg.PHASE1_FEATURES)))
                } ?: ByteArray(0),
            )
            diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), SettingsMsg.SID,
                SettingsMsg.FLAG_RESPONSE, resp, AaFrame.TYPE_RESPONSE).single())
        } else if (cmdId == 1) {
            // Settings WRITE. The only one Damage sends is brightness — f3 =
            // DeviceReceiveInfoFromAPP{f1 = brightness{f1=auto[, f2=level]}}
            // (faceclaw BleProtocol.buildSetBrightness). Stored for tests.
            if (eatSettingsWrites > 0) {
                eatSettingsWrites--
                diag.event("settings", "write EATEN (test knob, ${eatSettingsWrites} more)")
                return
            }
            val info = Pb.bytesField(payload, 3)
            val bri = info?.let { Pb.bytesField(it, 1) }
            if (bri != null) {
                brightnessAuto = Pb.varintField(bri, 1) == 1L
                brightnessLevel = if (brightnessAuto) null else (Pb.varintField(bri, 2) ?: 0L).toInt()
                diag.event("settings", "brightness -> ${if (brightnessAuto) "auto" else "$brightnessLevel"}")
            } else {
                diag.event("settings", "unmodeled settings write: ${payload.take(16).joinToString("") { "%02x".format(it) }}")
            }
            // §47: the answer — `09-00` echoing the msgId (G2CC
            // docs/G2_BLE_PROTOCOL.md §3 row 15: `09-20 type 1` → `09-00`;
            // faceclaw awaits the same ack). Body shape beyond f1/f2 is not
            // modeled: the transport matches on f2 alone.
            val msgId = (Pb.varintField(payload, 2) ?: 0L).toInt()
            settingsWritesAnswered++
            diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), SettingsMsg.SID,
                SettingsMsg.FLAG_RESPONSE, Pb.cat(Pb.v(1, 1), Pb.v(2, msgId)), AaFrame.TYPE_RESPONSE).single())
        }
    }

    // ------------------------------------------------------------------ input
    /** Inject a gesture as the glasses would REALLY report it (e0-01): scroll
     *  notches ride Text_ItemEvents on the capture container (they carry no
     *  source byte — G2_BLE_PROTOCOL.md §6.6); everything else rides
     *  Sys_ItemEvents. RIGHT arm — Left is silent.
     *
     *  The EventSource field is emitted only for CLICK and DOUBLE_CLICK, because
     *  that is the only case the stock sender writes it (see
     *  `EvenHubMsg.reportsSource`). Modeling a source on long-press would let code
     *  depend on a field that is absent on glass — precisely the kind of silent
     *  sim-only truth this model exists to refuse. */
    @Synchronized
    fun injectGesture(eventType: Int, source: Int = EvenHubMsg.SRC_RING) {
        val dev = if (eventType == EvenHubMsg.EV_SCROLL_TOP || eventType == EvenHubMsg.EV_SCROLL_BOTTOM) {
            val text = Pb.cat(Pb.v(1, EvenHubMsg.TEXT_CONTAINER_ID),
                Pb.s(2, EvenHubMsg.TEXT_CONTAINER_NAME), Pb.v(3, eventType))
            Pb.l(2, text)
        } else if (EvenHubMsg.reportsSource(eventType)) {
            Pb.l(3, Pb.cat(Pb.v(1, eventType), Pb.v(2, source)))
        } else {
            Pb.l(3, Pb.v(1, eventType))
        }
        val payload = Pb.cat(Pb.v(1, 2), Pb.l(13, dev))
        diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), EvenHubMsg.SID, EvenHubMsg.FLAG_EVENT,
            payload, AaFrame.TYPE_RESPONSE).single())
    }

    // ------------------------------------------------------------------ Damage control (FIRMWARE.md §3)
    /** The §3 ops on [arm]: the flag set (bit 15 PROBE the only implemented bit) and the
     *  telemetry reply every op gets. Modeled fields only — the sim has no timings, heap
     *  arenas, panel record or boot count, and a record leaves out what is not known. */
    private fun damageControl(arm: Arm, body: ByteArray, now: Long) {
        val c = ctx(arm)
        if (body.size != 6 || body[0] != 'D'.code.toByte() || body[1] != 'M'.code.toByte() || body[2].toInt() != 1) {
            c.damageStatus = DamageMsg.STATUS_MALFORMED   // recorded, not answered (§3)
            diag.event("damage", "$arm malformed field-112 body — status 1 recorded, no answer")
            return
        }
        val op = body[3].toInt() and 0xFF
        val arg = (body[4].toInt() and 0xFF) or ((body[5].toInt() and 0xFF) shl 8)
        val contract2 = (damageContract ?: 1) >= 2
        val implemented = if (contract2) DamageMsg.FLAGS_IMPLEMENTED else DamageMsg.FLAGS_IMPLEMENTED_V1
        var requestId = 0
        var withCacheCrc = false
        when (op) {
            DamageMsg.OP_TELEMETRY -> requestId = arg      // records no status: field 4 is the register
            DamageMsg.OP_CACHE_INFO -> { requestId = arg; withCacheCrc = true }
            DamageMsg.OP_FLAGS_SET ->
                // contract 2: nothing is armed without the lease (the check settles a lapse first)
                if (contract2 && !fbLeaseActive(arm, now)) c.damageStatus = DamageMsg.STATUS_NO_LEASE
                else if (arg and implemented.inv() != 0) c.damageStatus = DamageMsg.STATUS_UNSUPPORTED
                else { c.damageFlags = arg; c.damageStatus = DamageMsg.STATUS_OK }
            DamageMsg.OP_FLAGS_CLEAR -> { c.damageFlags = 0; c.damageStatus = DamageMsg.STATUS_OK }
            DamageMsg.OP_CACHE_SIZE ->
                // contract 2: the size the cache is allocated at by its first write
                if (!contract2) c.damageStatus = DamageMsg.STATUS_MALFORMED
                else if (!fbLeaseActive(arm, now)) c.damageStatus = DamageMsg.STATUS_NO_LEASE
                else if (arg < DamageMsg.CACHE_MIN_KIB || arg > DamageMsg.CACHE_BUDGET_KIB) c.damageStatus = DamageMsg.STATUS_BUDGET
                else if (c.textureCache != null) c.damageStatus = DamageMsg.STATUS_ALLOCATED
                else { c.cacheBytes = arg * 1024; c.damageStatus = DamageMsg.STATUS_OK }
            else -> c.damageStatus = DamageMsg.STATUS_MALFORMED   // unknown op: recorded and answered
        }
        val leased = fbLeaseActive(arm, now)          // notices a lapse first: flags then read 0
        val diagBits = (if (c.fReorder) 1 else 0) or (if (c.fSkip) 2 else 0) or (if (c.fDup) 4 else 0)
        val cache = c.textureCache
        val record = Pb.cat(
            Pb.v(1, requestId), Pb.v(2, now - uptimeOffsetMs), Pb.v(3, c.damageFlags), Pb.v(4, c.damageStatus),
            Pb.v(11, diagBits), Pb.v(12, if (leased) c.leaseDeadline - now else 0L), Pb.v(14, fwSide(arm)),
            Pb.v(15, 0), Pb.v(16, c.presentSeq), Pb.v(17, c.cacheGen),
            if (cache != null) Pb.v(18, cache.size) else ByteArray(0),
            if (cache != null && withCacheCrc) Pb.v(19, java.util.zip.CRC32().also { it.update(cache) }.value) else ByteArray(0),
            Pb.v(20, c.stSeq),
            if (c.stSeq > 0) Pb.cat(Pb.v(21, if (c.stRefused) 1 else 0), Pb.v(22, c.stCrc)) else ByteArray(0),
            if (contract2 && c.refSeen) Pb.cat(Pb.v(23, c.refMode), Pb.v(24, c.refReason), Pb.v(25, c.refSeq)) else ByteArray(0),
            if (contract2) Pb.v(26, c.lastPath) else ByteArray(0),
        )
        diag.event("damage", "$arm op $op status ${c.damageStatus} flags 0x${c.damageFlags.toString(16)}")
        // only RIGHT can send: FUN_00475b14 refuses on the left lens (CLAIMS.md, 2026-09-14)
        if (arm != Arm.RIGHT) return
        diag.notify(arm, AaFrame.frame(nextSeq(), SettingsMsg.SID, SettingsMsg.FLAG_RESPONSE,
            Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(DamageMsg.TELEMETRY_FIELD, record)), AaFrame.TYPE_RESPONSE).single())
    }

    @Synchronized
    fun damageFlags(arm: Arm): Int = ctx(arm).damageFlags

    /** F1.5 seams for tests: the cache's generation, whether it is allocated, the keep latch. */
    @Synchronized
    fun cacheGen(arm: Arm): Long = ctx(arm).cacheGen
    @Synchronized
    fun cacheAllocated(arm: Arm): Boolean = ctx(arm).textureCache != null

    /** The self-test's last scratch CRC-32 and step count on [arm] (`FIRMWARE.md` §3). */
    @Synchronized
    fun selfTestCrc(arm: Arm): Long = ctx(arm).stCrc
    @Synchronized
    fun selfTestSteps(arm: Arm): Long = ctx(arm).stSeq

    /** `FIRMWARE.md` §4: the last image-lane refusal on [arm] as fields 23–25 would report it
     *  (mode byte, reason, copy sequence; zeros when none) plus the status register. */
    @Synchronized
    fun refusalRecord(arm: Arm): List<Int> = ctx(arm).let { c ->
        if (c.refSeen) listOf(c.refMode, c.refReason, c.refSeq.toInt(), c.damageStatus) else listOf(0, 0, 0, c.damageStatus)
    }
    @Synchronized
    fun damageStatus(arm: Arm): Int = ctx(arm).damageStatus
    /** The last present's path on [arm]: 0 the full refresh, 1 the partial rows of a mode-24 hint. */
    @Synchronized
    fun lastPath(arm: Arm): Int = ctx(arm).lastPath
    /** The texture cache's size on [arm] when allocated, else 0. */
    @Synchronized
    fun cacheSize(arm: Arm): Int = ctx(arm).textureCache?.size ?: 0
    /** Bytes the live save-under slots hold on [arm]. */
    @Synchronized
    fun saveSlotBytes(arm: Arm): Int = ctx(arm).slotBytes

    // ------------------------------------------------------------------ logger (sid 0x0F)
    /** LoggerMsg: only BLE_LOGGER_SWITCH_SET is modeled — the RAM switch and
     *  its answer {cmd 1, magic, bleTransEn} on the arm it came in on. No log
     *  lines are generated; any other cmd is reported, never answered. */
    private fun logger(arm: Arm, payload: ByteArray) {
        val m = LoggerMsg.parse(payload)
        if (m == null) { diag.event("logger", "$arm unparseable sid-0x0F payload"); return }
        if (m.cmd != LoggerMsg.CMD_SWITCH_SET || m.transEn == null) {
            diag.event("logger", "$arm sid-0x0F cmd ${m.cmd} (unmodeled) — ignored")
            return
        }
        ctx(arm).loggerOn = m.transEn == 1
        diag.event("logger", "$arm log stream ${if (m.transEn == 1) "on" else "off"}")
        diag.notify(arm, AaFrame.frame(nextSeq(), LoggerMsg.SID, SettingsMsg.FLAG_RESPONSE,
            Pb.cat(Pb.v(1, LoggerMsg.CMD_SWITCH_SET), Pb.v(2, m.magic ?: 0), Pb.v(3, m.transEn)),
            AaFrame.TYPE_RESPONSE).single())
    }

    // ------------------------------------------------------------------ conformance vectors
    /** `FIRMWARE.md` §9: one completed image message into [arm]'s dispatch — what the
     *  deferred handler receives on each lens — for `ConformanceVectorTest`, which
     *  runs the same vector files the fork's host harness runs through the C.
     *  True where the firmware returns 0. */
    @Synchronized
    fun conformanceMessage(arm: Arm, message: ByteArray, now: Long): Boolean = dispatchTop(arm, message, now)

    /** The framebuffer lease op for [arm] through the modeled sid-0x09 control path. */
    @Synchronized
    fun conformanceLease(arm: Arm, acquire: Boolean, now: Long) =
        settings(arm, SettingsMsg.control(if (acquire) SettingsMsg.OP_FB_ACQUIRE else SettingsMsg.OP_FB_RELEASE, 1), now)

    /** A Damage control op (`FIRMWARE.md` §3/§4) for [arm] through the modeled sid-0x09 path. */
    @Synchronized
    fun conformanceControl(arm: Arm, op: Int, arg: Int, now: Long) = settings(arm, DamageMsg.control(op, arg), now)

    /** CRC-32 (zlib polynomial) over [arm]'s packed 640x480 shadow, rows in order. */
    @Synchronized
    fun shadowCrc32(arm: Arm): Long = java.util.zip.CRC32().also { it.update(ctx(arm).shadow) }.value

    /** CRC-32 over what [arm]'s lens SHOWS (`FIRMWARE.md` §9): a full refresh transfers the frame
     *  whole, a mode-24 hint only its rows, a release point puts stock content there. */
    @Synchronized
    fun panelCrc32(arm: Arm): Long = java.util.zip.CRC32().also { it.update(ctx(arm).panel) }.value

    @Synchronized
    fun overlayShown(arm: Arm): Boolean = ctx(arm).overlayShown

    @Synchronized
    fun loggerOn(arm: Arm): Boolean = ctx(arm).loggerOn

    /** Sticky diagnostic flags for [arm] — the mode-7 overlay's content. */
    @Synchronized
    fun flags(arm: Arm): Map<String, Boolean> {
        val c = ctx(arm)
        return mapOf("f_dup" to c.fDup, "f_skip" to c.fSkip, "f_reorder" to c.fReorder)
    }

    // ------------------------------------------------------------------ contract 2: modes 17–24
    /** `FIRMWARE.md` §4, written from the contract text. The order of the checks is the
     *  contract's (length → not in a batch → the lease → DRAW2 → the op's own), since the
     *  reason recorded is. A per-lens form takes the first x or rect on LEFT, the second on RIGHT. */
    private fun dispatchV2(arm: Arm, c: LensCtx, src: ByteArray, now: Long, present: Boolean, batch: BatchCtx?): Boolean {
        val mode = src[0].toInt() and 0x7F
        val pair = src[0].toInt() and CfwStereo != 0
        val left = fwSide(arm) == 2
        fun s16(i: Int) = rd16at(src, i).toShort().toInt()
        fun leaseAndFlag(): Boolean {
            if (!fbLeaseActive(arm, now)) { diag.event("decode", "$arm mode-$mode with no FB lease"); return refuse(c, src, REF_NO_LEASE) }
            if (c.damageFlags and DamageMsg.FLAG_DRAW2 == 0) { diag.event("decode", "$arm mode-$mode with DRAW2 not armed"); return refuse(c, src, REF_DRAW2) }
            return true
        }
        fun rectOk(i: Int): Rect? {
            val r = Rect(rd16at(src, i), rd16at(src, i + 2), rd16at(src, i + 4), rd16at(src, i + 6))
            return if (r.w == 0 || r.h == 0 || r.right > Geometry.PANEL_W || r.bottom > Geometry.PANEL_H) null else r
        }
        /** The rect this lens takes from a plain rect at [i] or a per-lens pair starting there: a pair
         *  is checked whole on both lenses — both rects inside the panel, not empty, one size — before
         *  either lens takes its own (`FIRMWARE.md` §4), so the lenses cannot decide differently. */
        fun rectAt(i: Int): Rect? {
            if (!pair) return rectOk(i)
            val l = rectOk(i) ?: return null
            val r = rectOk(i + 8) ?: return null
            if (l.w != r.w || l.h != r.h) return null
            return if (left) l else r
        }
        val clip = Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H).let { p -> batch?.clip?.let { p.intersect(it) } ?: p }
        when (mode) {
            19 -> {
                // `FIRMWARE.md` §4, mode 19: every entry's shape (1), the lease (3), DRAW2 (9), every entry
                // inside the session's cache (5); a list with no data is then accepted with nothing written
                var pos = 1
                while (pos < src.size) {
                    if (src.size - pos < 4) return refuse(c, src, REF_LENGTH)
                    val len = rd16at(src, pos + 2)
                    pos += 4
                    if (len > src.size - pos) return refuse(c, src, REF_LENGTH)
                    pos += len
                }
                if (!leaseAndFlag()) return false
                val size = cacheSizeOf(c)
                var hasData = false
                pos = 1
                while (pos < src.size) {
                    val off = rd16at(src, pos) * 4
                    val len = rd16at(src, pos + 2)
                    pos += 4
                    if (off > size || len > size - off) return refuse(c, src, REF_RECORD)
                    if (len > 0) hasData = true
                    pos += len
                }
                if (!hasData) return true
                val cache = c.textureCache ?: allocateCache(arm, c)
                pos = 1
                while (pos < src.size) {
                    val off = rd16at(src, pos) * 4
                    val len = rd16at(src, pos + 2)
                    pos += 4
                    src.copyInto(cache, off, pos, pos + len)
                    pos += len
                }
                c.cacheGen++
                return true
            }
            20 -> {
                if (src.size != (if (pair) 17 else 9)) return refuse(c, src, REF_LENGTH)
                if (present || batch == null) { diag.event("decode", "$arm mode-20 outside a batch"); return refuse(c, src, REF_NO_BATCH) }
                if (!leaseAndFlag()) return false
                val r = rectAt(1) ?: return refuse(c, src, REF_BOUNDS)
                batch.clip = r
                return true
            }
            24 -> {
                if (src.size != 5) return refuse(c, src, REF_LENGTH)
                if (present || batch == null) { diag.event("decode", "$arm mode-24 outside a batch"); return refuse(c, src, REF_NO_BATCH) }
                if (!leaseAndFlag()) return false
                val y0 = rd16at(src, 1); val y1 = rd16at(src, 3)
                if (y0 > y1 || y1 >= Geometry.PANEL_H) return refuse(c, src, REF_BOUNDS)
                batch.hint = y0..y1
                return true
            }
            17 -> {
                if (src.size != (if (pair) 10 else 8)) return refuse(c, src, REF_LENGTH)
                if (!leaseAndFlag()) return false
                val off = rd16at(src, 1) * 4
                val x = if (pair) s16(if (left) 3 else 5) else s16(3)
                val y = s16(if (pair) 7 else 5)
                val options = src[if (pair) 9 else 7].toInt() and 0xFF
                val img = image2At(c, off) ?: return refuse(c, src, REF_RECORD)
                renderClipped(c, img, x, y, options, clip)
                if (present) present(arm, now, batch)
                return true
            }
            18 -> {
                val head = if (pair) 11 else 9
                if (src.size < head) return refuse(c, src, REF_LENGTH)
                val strLen = src[head - 1].toInt() and 0xFF
                if (src.size != head + strLen) return refuse(c, src, REF_LENGTH)
                if (!leaseAndFlag()) return false
                val font = rd16at(src, 1) * 4
                var x = if (pair) s16(if (left) 3 else 5) else s16(3)
                val y = s16(if (pair) 7 else 5)
                val options = src[if (pair) 9 else 7].toInt() and 0xFF
                val size = cacheSizeOf(c)
                val cache = c.textureCache
                if (cache == null || font > size || size - font < CfwModes.FONT2_TABLE_BYTES) return refuse(c, src, REF_RECORD)
                val glyphs = ArrayList<Pair<Int, CachedImage?>>(strLen)
                for (i in 0 until strLen) {
                    val ch = src[head + i].toInt() and 0xFF
                    if (ch in 1..31) { glyphs += ch to null; continue }
                    if (ch < 32) return refuse(c, src, REF_CODE)
                    val g = image1At(c, rd16at(cache, font + (ch - 32) * 2) * 4) ?: return refuse(c, src, REF_RECORD)
                    glyphs += ch to g
                }
                for ((ch, g) in glyphs) {
                    if (g == null) { x += ch - 11; continue }
                    renderClipped(c, g, x, y, options, clip)
                    x += g.w
                }
                if (present) present(arm, now, batch)
                return true
            }
            21 -> {
                if (src.size != (if (pair) 18 else 10)) return refuse(c, src, REF_LENGTH)
                if (!leaseAndFlag()) return false
                val r = rectAt(1) ?: return refuse(c, src, REF_BOUNDS)
                val level = src[src.size - 1].toInt() and 0xFF
                if (level > 15) return refuse(c, src, REF_VALUE)
                r.intersect(clip)?.let { a -> for (y in a.y until a.bottom) for (x in a.x until a.right) setNibble(c.shadow, c.stride, x, y, level) }
                if (present) present(arm, now, batch)
                return true
            }
            22 -> {
                if (src.size != (if (pair) 25 else 17)) return refuse(c, src, REF_LENGTH)
                if (!leaseAndFlag()) return false
                val r = rectAt(1) ?: return refuse(c, src, REF_BOUNDS)
                val lb = src.size - 8
                val lut = IntArray(16) { i -> val b = src[lb + i / 2].toInt() and 0xFF; if (i and 1 == 0) b shr 4 else b and 0x0F }
                r.intersect(clip)?.let { a ->
                    for (y in a.y until a.bottom) for (x in a.x until a.right) setNibble(c.shadow, c.stride, x, y, lut[getNibble(c.shadow, c.stride, x, y)])
                }
                if (present) present(arm, now, batch)
                return true
            }
            23 -> {
                if (src.size < 3) return refuse(c, src, REF_LENGTH)
                val sub = src[1].toInt() and 0xFF
                val slot = src[2].toInt() and 0xFF
                if (src.size != (if (sub == 0) (if (pair) 19 else 11) else 3)) return refuse(c, src, REF_LENGTH)
                if (!leaseAndFlag()) return false
                if (sub > 2) return refuse(c, src, REF_VALUE)
                if (slot >= CfwModes.SAVE_SLOTS) return refuse(c, src, REF_SCRATCH)
                when (sub) {
                    2 -> { c.slots[slot] = null; return true }
                    1 -> {
                        val s = c.slots[slot] ?: return refuse(c, src, REF_SCRATCH)
                        for (y in 0 until s.rect.h) for (x in 0 until s.rect.w)
                            setNibble(c.shadow, c.stride, s.rect.x + x, s.rect.y + y, s.levels[y * s.rect.w + x].toInt())
                        if (present) present(arm, now, batch)
                        return true
                    }
                    else -> {
                        val r = rectAt(3) ?: return refuse(c, src, REF_BOUNDS)
                        val held = c.slotBytes - (c.slots[slot]?.bytes ?: 0)     // the slot's own bytes are replaced
                        if (held + CfwModes.saveBytes(r) > CfwModes.SAVE_BUDGET) return refuse(c, src, REF_SCRATCH)
                        val levels = ByteArray(r.w * r.h)
                        for (y in 0 until r.h) for (x in 0 until r.w) levels[y * r.w + x] = getNibble(c.shadow, c.stride, r.x + x, r.y + y).toByte()
                        c.slots[slot] = SaveSlot(r, levels)
                        return true
                    }
                }
            }
            else -> return refuse(c, src, REF_MODE)
        }
    }

    /** A v2 image record at byte offset [off]: [w u16][h u16][RLE of w*h], bounded by the
     *  session's cache size; null when it is not a record. */
    private fun image2At(c: LensCtx, off: Int): CachedImage? {
        val cache = c.textureCache ?: return null
        val size = cacheSizeOf(c)
        if (off > size || size - off < 4) return null
        val w = rd16at(cache, off); val h = rd16at(cache, off + 2)
        if (w == 0 || h == 0 || w > CfwModes.MAX_IMAGE2_W || h > CfwModes.MAX_IMAGE2_H) return null
        val levels = try { decodeCachedRle(cache, off + 4, size - off - 4, w * h) } catch (e: IllegalStateException) { return null }
        return CachedImage(w, h, levels)
    }

    /** A v1 record ([w u8][h u8][RLE]) bounded by the session's cache size: a mode-18 glyph. */
    private fun image1At(c: LensCtx, off: Int): CachedImage? {
        val cache = c.textureCache ?: return null
        val size = cacheSizeOf(c)
        if (off > size || size - off < 2) return null
        val w = cache[off].toInt() and 0xFF; val h = cache[off + 1].toInt() and 0xFF
        if (w == 0 || h == 0) return null
        val levels = try { decodeCachedRle(cache, off + 2, size - off - 2, w * h) } catch (e: IllegalStateException) { return null }
        return CachedImage(w, h, levels)
    }

    /** cfw_texture_render with a clip: the same LUT and transparency rule, every pixel tested
     *  against [clip] (the panel cut by the batch's clip) instead of the panel alone. */
    private fun renderClipped(c: LensCtx, img: CachedImage, x0: Int, y0: Int, options: Int, clip: Rect) {
        val lut = makeLut(options)
        val transparent = options and CfwModes.OPT_TRANSPARENT != 0
        for (p in img.levels.indices) {
            val color = img.levels[p].toInt() and 0x0F
            if (transparent && color == 0) continue
            val x = x0 + p % img.w
            val y = y0 + p / img.w
            if (x < clip.x || y < clip.y || x >= clip.right || y >= clip.bottom) continue
            setNibble(c.shadow, c.stride, x, y, lut[color])
        }
    }

    companion object {
        private const val CfwStereo = 0x80
        /** The two panel operations records of stock 2.2.6.10 (`CLAIMS.md`). */
        const val PANEL_JBD4010 = 0x0070B024L
        /** The modes that take the display gate, so a top-level one of them could have changed the
         *  shadow (`FIRMWARE.md` §4, mode 24's panel rule). */
        private val GATED_MODES = setOf(3, 6, 8, 9, 11, 13, 14, 15, 17, 18, 21, 22, 23)
        const val PANEL_A6NG = 0x0070AFE4L
        // `FIRMWARE.md` §4: the reasons of an image-lane refusal (telemetry field 24)
        const val REF_LENGTH = 1
        const val REF_BOUNDS = 2
        const val REF_NO_LEASE = 3
        const val REF_NO_SHADOW = 4
        const val REF_RECORD = 5
        const val REF_CODE = 6
        const val REF_SCRATCH = 7
        const val REF_MODE = 8
        const val REF_DRAW2 = 9
        const val REF_NO_BATCH = 10
        const val REF_NO_MEMORY = 11
        const val REF_VALUE = 12
        const val REF_STREAM = 13
    }
}
