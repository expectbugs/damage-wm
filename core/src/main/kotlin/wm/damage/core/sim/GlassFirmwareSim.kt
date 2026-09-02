package wm.damage.core.sim

import wm.damage.core.geom.Geometry
import wm.damage.core.gfx.Zl
import wm.damage.core.transport.Arm
import wm.damage.core.transport.LensPanels
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.LaunchMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg

/**
 * A byte-exact model of the CFW glasses — DESIGN.md §9.2's offline simulator.
 * Consumes the SAME AA packets the BLE transport would write and models what
 * g2flash's firmware does with them: reassembly, ImgRawMsg accumulation, mode
 * 3/6/8/9 dispatch onto per-lens packed-4bpp shadows, the duplicate-fid ring
 * with its f_dup/f_skip/f_reorder flags, the per-arm framebuffer lease with its
 * fail-OPEN expiry, the warmup-frame drop, the msgId-255 silent kill, and the
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
    class LensCtx {
        val stride = (Geometry.PANEL_W + 1) / 2
        val shadow = ByteArray(stride * Geometry.PANEL_H)
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
    }

    val left = LensCtx()
    val right = LensCtx()
    private fun ctx(arm: Arm) = if (arm == Arm.LEFT) left else right

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
    var brightnessAuto = true
    var brightnessLevel: Int? = null

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
                // lease ends, so a resumed session must upload its atlas again.
                c.textureCache = null
                // Stock repaint: the panel no longer shows our frame.
                stockPattern(c.panel)
                diag.event("lease", "$arm FB lease EXPIRED — stock repainted over us " +
                    "(fail-open); texture cache freed")
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
                warmupPending = true      // the first image burst after CREATE is silently dropped
                img = null
                ack(cmd, msgId, null)
            }
            EvenHubMsg.CMD_IMAGE -> imageFragment(arm, fields, msgId, now)
            EvenHubMsg.CMD_TEXT_UPGRADE -> ack(cmd, msgId, null)
            EvenHubMsg.CMD_KEEPALIVE -> ack(cmd, msgId, null)
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

        if (compressMode != 0) {
            // An unknown CompressMode is silently treated as raw — garbage, not an
            // error (overview.md §8). The CFW path must always send 0.
            diag.event("compressmode", "nonzero CompressMode $compressMode on the CFW path — " +
                "hardware would render garbage in silence")
        }
        // Stuck-session trap (overview.md §9.2, seen on our own wire): a session
        // adjacent to a broken one inherits its dead buffers.
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
            for (lensArm in Arm.entries) dispatchImage(lensArm, image, now)
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
    /** zlib_glue.c image_dispatch, per lens. present=false only inside mode 8. */
    private fun dispatchImage(arm: Arm, src: ByteArray, now: Long, present: Boolean = true): Boolean {
        if (src.isEmpty()) return false
        val c = ctx(arm)
        val modeByte = src[0].toInt() and 0xFF
        val stereo = modeByte and CfwStereo != 0
        return when (modeByte and 0x7F) {
            6 -> {
                cfwDiag(c, hasFid = false, fid = 0)
                val packed = try {
                    Zl.decodeCfw(src.copyOfRange(1, src.size), Geometry.PANEL_W * Geometry.PANEL_H)
                } catch (e: Exception) {
                    diag.event("decode", "$arm mode-6 decompress failed: ${e.message} — previous frame stays up")
                    return false
                }
                packed.copyInto(c.shadow)
                c.seeded = true
                if (present) present(arm, now)
                true
            }
            3 -> {
                val boxOff = if (stereo) (if (fwSide(arm) == 2) 1 else 5) else 1
                val fidOff = if (stereo) 9 else 5
                val zOff = if (stereo) 11 else 7
                if (src.size < zOff + 1) {
                    diag.event("decode", "$arm mode-3 too short — rejected in silence"); return false
                }
                if (stereo) {
                    // firmware size-checks the pair: src[3]!=src[7] || src[4]!=src[8]
                    if (src[3] != src[7] || src[4] != src[8]) {
                        diag.event("decode", "$arm stereo boxes differ in SIZE — rejected in silence")
                        return false
                    }
                }
                val l = (src[boxOff].toInt() and 0xFF) * 4
                val t = (src[boxOff + 1].toInt() and 0xFF) * 2
                val w = (src[boxOff + 2].toInt() and 0xFF) * 4
                val h = (src[boxOff + 3].toInt() and 0xFF) * 2
                if (w == 0 || h == 0 || l + w > Geometry.PANEL_W || t + h > Geometry.PANEL_H) {
                    diag.event("decode", "$arm mode-3 box ($l,$t ${w}x$h) out of bounds — " +
                        "rejected in SILENCE, previous frame stays up")
                    return false
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
                    return false
                }
                // Composite the tight box into the shadow. w is x4 so w/2 whole bytes,
                // and l is x4 so l/2 is a whole byte offset (the quantization's purpose).
                val rowBytes = w / 2
                for (row in 0 until h) {
                    System.arraycopy(packed, row * rowBytes, c.shadow, (t + row) * c.stride + l / 2, rowBytes)
                }
                if (present) present(arm, now)
                true
            }
            9 -> {
                val need = if (stereo) 32 else 16
                if (src.size < 1 + need) { diag.event("decode", "$arm mode-9 short"); return false }
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
                    return false
                }
                rectCopy4bpp(c.shadow, c.stride, sL, sT, dL, dT, sW, sH)
                if (present) present(arm, now)
                true
            }
            8 -> {
                if (!present) { diag.event("decode", "$arm nested mode-8 rejected"); return false }
                if (src.size > Geometry.MODE8_MAX) {
                    diag.event("decode", "$arm mode-8 over bmp_max — rejected in silence"); return false
                }
                if (src.size < 2) {
                    diag.event("decode", "$arm mode-8 of ${src.size} B — too short for a batch header, rejected")
                    return false
                }
                val count = src[1].toInt() and 0xFF
                var pos = 2
                for (i in 0 until count) {
                    if (pos + 2 > src.size) { diag.event("decode", "$arm mode-8 truncated"); return false }
                    val segLen = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    if (segLen < 1 || pos + segLen > src.size) {
                        diag.event("decode", "$arm mode-8 bad seglen"); return false
                    }
                    val subMode = src[pos].toInt() and 0x7F
                    if (subMode !in CfwModes.BATCH_SUBMODES) {
                        diag.event("decode", "$arm mode-8 sub-mode $subMode rejected"); return false
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
                    if (!dispatchImage(arm, src.copyOfRange(pos, pos + segLen), now, present = false)) {
                        diag.event("decode", "$arm mode-8 sub $i FAILED — whole batch aborted")
                        return false
                    }
                    pos += segLen
                }
                present(arm, now)
                true
            }
            7 -> {
                if (src.size >= 2 && src[1].toInt() == 0) {
                    val c2 = ctx(arm)
                    c2.fDup = false; c2.fSkip = false; c2.fReorder = false
                    c2.recentFids.fill(0xFFFF)
                    c2.diagSeen = false; c2.fidResync = false
                    c2.lastFid = 0; c2.highFid = 0
                    diag.event("diag", "$arm mode-7 sub-0: flags and fid ring cleared")
                }
                true
            }
            11 -> {
                // cfw_cleanup_session(): hands the screen back. direct_lease_deadline
                // goes to 0 (so the repaint guard fails OPEN and stock takes over),
                // the texture cache is freed, snapshots are dropped, the overlay hides.
                c.leaseDeadline = 0
                c.textureCache = null
                stockPattern(c.panel)
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
                        return false
                    }
                    val off = rd16at(src, pos)
                    val len = rd16at(src, pos + 2)
                    pos += 4
                    if (len > src.size - pos || off + len > CfwModes.TEXTURE_CACHE_SIZE) {
                        diag.event("decode", "$arm mode-12 entry [$off,${off + len}) len $len " +
                            "out of range — whole update rejected in silence")
                        return false
                    }
                    if (len > 0) hasData = true
                    pos += len
                }
                if (!hasData) return true                    // firmware returns 0, writes nothing
                if (!fbLeaseActive(arm, now)) {
                    diag.event("decode", "$arm mode-12 with NO framebuffer lease — rejected " +
                        "(the cache is lease-scoped)")
                    return false
                }
                val cache = c.textureCache ?: ByteArray(CfwModes.TEXTURE_CACHE_SIZE).also {
                    c.textureCache = it
                    diag.event("texture", "$arm texture cache allocated and zeroed " +
                        "(${CfwModes.TEXTURE_CACHE_SIZE} B)")
                }
                pos = 1
                while (pos < src.size) {
                    val off = rd16at(src, pos)
                    val len = rd16at(src, pos + 2)
                    pos += 4
                    src.copyInto(cache, off, pos, pos + len)
                    pos += len
                }
                true
            }
            13, 14 -> {
                if (!fbLeaseActive(arm, now)) {
                    diag.event("decode", "$arm mode-${modeByte and 0x7F} with NO framebuffer " +
                        "lease — rejected in silence")
                    return false
                }
                // The firmware imposes no keyframe requirement here, but a cached draw
                // onto an unseeded shadow is still a design error: present_shadow pushes
                // the WHOLE panel, and on glass the shadow is the container's display
                // buffer A holding whatever was there before, not the zeroes we start at.
                if (!c.seeded) diag.event("decode", "$arm mode-${modeByte and 0x7F} onto an " +
                    "UNSEEDED shadow (no keyframe) — the model shows black around it, " +
                    "the glass shows stale buffer content")
                val ok = if ((modeByte and 0x7F) == 13) drawCachedImage(arm, c, src)
                else drawCachedText(arm, c, src)
                if (!ok) return false
                if (present) present(arm, now)
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
            else -> {
                diag.event("decode", "$arm unmodeled mode ${modeByte and 0x7F} — BMP fallback would run")
                false
            }
        }
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
        if (c.leaseDeadline == 0L || now >= c.leaseDeadline) {
            if (c.textureCache != null) {
                c.textureCache = null
                diag.event("texture", "$arm texture cache freed: the FB lease has lapsed")
            }
            return false
        }
        return true
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
        if (src.size != 8) {
            diag.event("decode", "$arm mode-13 is ${src.size} B; the firmware wants exactly 8")
            return false
        }
        val img = imageAt(arm, c, rd16at(src, 1), "mode-13") ?: return false
        renderCached(c, img, rd16at(src, 3), rd16at(src, 5), src[7].toInt() and 0xFF)
        return true
    }

    /** Mode 14: [14][font16][x16][y16][opt8][len8][bytes]. Every character is
     *  validated before ANY glyph is drawn, so one bad byte drops the whole line. */
    private fun drawCachedText(arm: Arm, c: LensCtx, src: ByteArray): Boolean {
        if (src.size < 9) { diag.event("decode", "$arm mode-14 too short"); return false }
        val fontOffset = rd16at(src, 1)
        val strLen = src[8].toInt() and 0xFF
        if (src.size != 9 + strLen) {
            diag.event("decode", "$arm mode-14 length ${src.size} != ${9 + strLen} for its " +
                "declared string length")
            return false
        }
        if (fontOffset > CfwModes.TEXTURE_CACHE_SIZE - CfwModes.FONT_TABLE_BYTES) {
            diag.event("decode", "$arm mode-14 font table at $fontOffset does not fit"); return false
        }
        val cache = c.textureCache ?: run {
            diag.event("decode", "$arm mode-14: no texture cache has been written"); return false
        }
        val options = src[7].toInt() and 0xFF
        val glyphs = ArrayList<Pair<Int, CachedImage?>>(strLen)
        for (i in 0 until strLen) {
            val ch = src[9 + i].toInt() and 0xFF
            if (ch in 1..31) { glyphs += ch to null; continue }
            if (ch < 32 || ch > 127) {
                diag.event("decode", "$arm mode-14 byte $ch at $i is neither an x adjust " +
                    "(1..31) nor a glyph (32..127) — the WHOLE string is rejected")
                return false
            }
            val off = rd16at(cache, fontOffset + (ch - 32) * 2)
            val img = imageAt(arm, c, off, "mode-14 glyph ${ch.toChar()}") ?: return false
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

    private fun present(arm: Arm, now: Long) {
        val c = ctx(arm)
        if (c.leaseDeadline > now) {
            c.shadow.copyInto(c.panel)
        } else {
            // No lease: our present lands, but stock will clobber it on its next
            // repaint. Model the present as landing, then rely on tick() for the
            // clobber; a real session must simply hold the lease.
            c.shadow.copyInto(c.panel)
            diag.event("lease", "$arm present WITHOUT a live FB lease — stock will repaint over this")
        }
        diag.panelChanged(arm)
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
        dispatchImage(arm, src, now)

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
                    if (!fbLeaseActive(arm, now)) {
                        if (hadCache)
                            diag.event("texture", "$arm fresh FB lease after a lapse — " +
                                "texture cache freed; the atlas must be uploaded again")
                        c.textureCache = null
                    }
                    c.leaseDeadline = now + SettingsMsg.LEASE_EXPIRY_MS
                    diag.event("lease", "$arm FB lease acquired/renewed (90 s)")
                }
                SettingsMsg.OP_FB_RELEASE -> {
                    ctx(arm).leaseDeadline = 0
                    ctx(arm).textureCache = null          // settings_ext.c releases it here
                    stockPattern(ctx(arm).panel)
                    diag.event("lease", "$arm FB lease released — stock repaints, " +
                        "texture cache freed")
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
                Pb.l(4, Pb.cat(Pb.v(12, batteryPct), Pb.v(13, 0))),
                Pb.l(SettingsMsg.CAPABILITY_FIELD, capabilityString.toByteArray(Charsets.UTF_8)),
                Pb.l(SettingsMsg.MIC_STATUS_FIELD, micStatusBody()),
            )
            diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), SettingsMsg.SID,
                SettingsMsg.FLAG_RESPONSE, resp, AaFrame.TYPE_RESPONSE).single())
        } else if (cmdId == 1) {
            // Settings WRITE. The only one Damage sends is brightness — f3 =
            // DeviceReceiveInfoFromAPP{f1 = brightness{f1=auto[, f2=level]}}
            // (faceclaw BleProtocol.buildSetBrightness). Stored for tests.
            val info = Pb.bytesField(payload, 3)
            val bri = info?.let { Pb.bytesField(it, 1) }
            if (bri != null) {
                brightnessAuto = Pb.varintField(bri, 1) == 1L
                brightnessLevel = if (brightnessAuto) null else (Pb.varintField(bri, 2) ?: 0L).toInt()
                diag.event("settings", "brightness -> ${if (brightnessAuto) "auto" else "$brightnessLevel"}")
            } else {
                diag.event("settings", "unmodeled settings write: ${payload.take(16).joinToString("") { "%02x".format(it) }}")
            }
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

    /** Sticky diagnostic flags for [arm] — the mode-7 overlay's content. */
    @Synchronized
    fun flags(arm: Arm): Map<String, Boolean> {
        val c = ctx(arm)
        return mapOf("f_dup" to c.fDup, "f_skip" to c.fSkip, "f_reorder" to c.fReorder)
    }

    companion object {
        private const val CfwStereo = 0x80
    }
}
