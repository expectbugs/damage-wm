package wm.damage.core.sim

import wm.damage.core.geom.Geometry
import wm.damage.core.gfx.Zl
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.EvenHubMsg
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
class GlassFirmwareSim() {

    enum class Arm { LEFT, RIGHT }

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

    private val diag = object : SimDiag {
        override fun event(kind: String, detail: String) { for (l in listeners) l.event(kind, detail) }
        override fun notify(arm: Arm, packet: ByteArray) { for (l in listeners) l.notify(arm, packet) }
        override fun panelChanged(arm: Arm) { for (l in listeners) l.panelChanged(arm) }
    }

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
    var capabilityString =
        "EVENCFW/8 img576 img640 imgz rle wakelease directfb fbguard wearnotify compass10"

    private var glassSeq = 0
    private fun nextSeq(): Int { glassSeq = (glassSeq + 1) and 0xFF; return glassSeq }

    /** Host wrote [packet] to [arm]'s write characteristic. */
    @Synchronized
    fun write(arm: Arm, packet: ByteArray, now: Long) {
        val frame = reassemblers.getValue(arm).offer(packet) ?: return
        when (frame.sid) {
            EvenHubMsg.SID -> evenHub(arm, frame.payload, now)
            SettingsMsg.SID -> settings(arm, frame.payload, now)
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
                // Stock repaint: the panel no longer shows our frame.
                stockPattern(c.panel)
                diag.event("lease", "$arm FB lease EXPIRED — stock repainted over us (fail-open)")
                diag.panelChanged(arm)
            }
        }
    }

    @Synchronized
    fun leaseHeld(arm: Arm, now: Long): Boolean = ctx(arm).leaseDeadline > now

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
        ack(EvenHubMsg.CMD_IMAGE, msgId, null)
        if (s.buf.size() > s.total) {
            diag.event("image", "session $session overran declared total ${s.total} — abort")
            abort(session, total, fragIdx, msgId)
            return
        }
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
        // ImgResCmd with ErrorCode (field 8) = APP_REQUEST_UPGRADE_IMAGE_RAW_DATA_FAILED
        // — the failure shape seen in our own captures (overview.md §9.2).
        val res = Pb.cat(
            Pb.v(1, EvenHubMsg.IMG_CONTAINER_ID),
            Pb.v(3, session), Pb.v(4, total), Pb.v(6, fragIdx),
            Pb.v(8, 1),
        )
        val payload = Pb.cat(Pb.v(1, EvenHubMsg.CMD_IMAGE + 1), Pb.v(2, msgId), Pb.l(5, res))
        diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), EvenHubMsg.SID, EvenHubMsg.FLAG_ACK,
            payload, AaFrame.TYPE_RESPONSE).single())
    }

    private fun ack(cmd: Int, msgId: Int, error: Long?) {
        val ackType = if (cmd == EvenHubMsg.CMD_KEEPALIVE) cmd else cmd + 1
        val payload = Pb.cat(Pb.v(1, ackType), Pb.v(2, msgId))
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
                if (src.size < 2) return false
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
                    if (subMode != 3 && subMode != 6 && subMode != 9) {
                        diag.event("decode", "$arm mode-8 sub-mode $subMode rejected"); return false
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
            else -> {
                diag.event("decode", "$arm unmodeled mode ${modeByte and 0x7F} — BMP fallback would run")
                false
            }
        }
    }

    /** zlib_glue.c cfw_diag(), verbatim semantics. Returns true = duplicate, skip. */
    private fun cfwDiag(c: LensCtx, hasFid: Boolean, fid: Int): Boolean {
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
                    ctx(arm).leaseDeadline = now + SettingsMsg.LEASE_EXPIRY_MS
                    diag.event("lease", "$arm FB lease acquired/renewed (90 s)")
                }
                SettingsMsg.OP_FB_RELEASE -> {
                    ctx(arm).leaseDeadline = 0
                    stockPattern(ctx(arm).panel)
                    diag.event("lease", "$arm FB lease released — stock repaints")
                    diag.panelChanged(arm)
                }
                else -> diag.event("lease", "$arm control op ${control[3]} (unmodeled)")
            }
            return
        }
        val cmdId = (Pb.varintField(payload, 1) ?: 0L).toInt()
        if (cmdId == 2) {
            // Settings READ -> response carrying the capability string in field 100.
            val msgId = (Pb.varintField(payload, 2) ?: 0L).toInt()
            val resp = Pb.cat(
                Pb.v(1, 2), Pb.v(2, msgId),
                Pb.l(SettingsMsg.CAPABILITY_FIELD, capabilityString.toByteArray(Charsets.UTF_8)),
            )
            diag.notify(Arm.RIGHT, AaFrame.frame(nextSeq(), SettingsMsg.SID,
                SettingsMsg.FLAG_RESPONSE, resp, AaFrame.TYPE_RESPONSE).single())
        }
    }

    // ------------------------------------------------------------------ input
    /** Inject a gesture as the glasses would REALLY report it (e0-01): scroll
     *  notches ride Text_ItemEvents on the capture container (they carry no
     *  source byte — G2_BLE_PROTOCOL.md §6.6); everything else rides
     *  Sys_ItemEvents with an EventSource. RIGHT arm — Left is silent. */
    @Synchronized
    fun injectGesture(eventType: Int, source: Int = EvenHubMsg.SRC_RING) {
        val dev = if (eventType == EvenHubMsg.EV_SCROLL_TOP || eventType == EvenHubMsg.EV_SCROLL_BOTTOM) {
            val text = Pb.cat(Pb.v(1, EvenHubMsg.TEXT_CONTAINER_ID),
                Pb.s(2, EvenHubMsg.TEXT_CONTAINER_NAME), Pb.v(3, eventType))
            Pb.l(2, text)
        } else {
            Pb.l(3, Pb.cat(Pb.v(1, eventType), Pb.v(2, source)))
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
