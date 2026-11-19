package wm.damage.core.shell

import wm.damage.core.geom.Layout
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.motion.MainPan
import wm.damage.core.motion.Motion
import wm.damage.core.motion.MotionProgram
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.TextureCache

/**
 * Build C2, stage DMS (the first half of the spec's package DM, `C2-spec.md` §6.4; `C2-decisions.md` F and K): Main's
 * rows staged on the glasses from Main's OWN painters with real text, and the two pan programs per width. Context for
 * the reader: Adam's own glasses on his own firmware fork (display-rendering work on a personal device); nothing here
 * sends — the shell writes the records through its own stage writes and plays a program only for the `mainseq` probe.
 *
 * **The records** (one staging = one layout, one pair of planes, one row count), laid out from the start of the cache's
 * stage reserve ([CfwModes.STAGE_RESERVE], the top 64 KiB of a 160 or 256 KiB session), each 4-byte aligned, in this
 * order: the strip, every row's band form, the programs.
 * - **The strip:** every row in its row form (`MainSurface.paintRow`, not resting) stacked at the row pitch, with
 *   [Layout.rowsAbove] margin rows before row 0 and [Layout.rowsBelow] after the last — the rows Main shows past either
 *   end at the cursor's extremes (the other end's rows where Main wraps, blank where it does not), so a program's END
 *   is Main's own paint at EVERY cursor. The margins carry no wrap motion: a notch across the wrap is an edge (below).
 * - **The band forms:** row i in the lens band as `ContentKit.paintLensBand` paints it with i at the cursor, the rail's
 *   columns aside.
 * - **The programs:** for each width k (the band and k rows on each side, capped at what the height shows; `C2-decisions`
 *   K ruled k = 2 for C2) one notch down (step +1) and one up (step −1). Three per-lens tracks, none meeting another:
 *   the rows above the band and the rows below it — the strip drawn twice, one pitch apart (decisions F: the band is
 *   taller than one row and holds one), panned one pitch with ease-out over T steps at P = 34 — and the band, where the
 *   leaving row's band form goes back to the list plane and dims over [MainPan.SWAP] steps, then the entering row's comes
 *   forward from there to the band's plane and brightens (dxL ≠ dxR), with no size change. The motion's shape and
 *   constants are stage DM0's `mainpan` programs' (`MainPan`), measured on the glasses.
 *
 * **One program per direction serves every cursor** through PLAY's parameters, each computed by the rule a binding
 * entry will carry (`FIRMWARE.md` §6.2 step 9: kind 0 the argument, kind 1 the item table's entry at c' + arg, kind 2
 * c' × pitch + arg, c' the new cursor): P0 the upper strip's start y (kind 2), P1 the entering row's band form (kind 1
 * at c'), P2 the leaving row's (kind 1 at c' − step), P3 the lower strip's start y (kind 2, the same pitch). A new
 * cursor outside the rows is an edge — the wrap included — and no program plays there.
 *
 * END (`FIRMWARE.md` §5.4) of every program is level-0 backdrops and records drawn at level 15, so [paintEnd] puts the
 * pictures' RAW pixels into a lens picture: the shell's belief then equals its truth byte for byte wherever the rows
 * did not change since they were staged (the diff compares raw bytes; the glass holds their levels).
 */
internal class MainStaging private constructor(
    val layout: Layout,
    /** The planes in force: Main's list at [listD], its band at [bandD] (`Shell.updatePlanes`; the left lens at x − d). */
    val listD: Int,
    val bandD: Int,
    val rows: Int,
    val cacheSize: Int,
    val strip: Picture,
    val bands: List<Picture>,
    val pans: List<Pan>,
    /** What goes into the cache, in write order; [end] is the byte past the last. */
    val blobs: List<Blob>,
) {
    /** One picture Main's painters drew: [raw] as painted (8-bit; what [paintEnd] puts into belief), its levels as the
     *  v2 record the glasses decode, at the byte offset [off]. */
    class Picture internal constructor(val name: String, val raw: Gray8, val image: TextureCache.Image2,
        /** `[w u16][h u16][RLE]` (`FIRMWARE.md` §4), unpadded: its length is the record's read range. */
        val encoded: ByteArray, val off: Int) {
        val off4: Int get() = off / 4
        val record: Motion.Source.Record get() = Motion.Source.record(off4, encoded)
    }

    /** One cache write's worth: [bytes] padded to the next 4-byte boundary with explicit zeros (the live cache is not
     *  zeroed, `HANDOFF.md` §65), at the byte offset [off]. */
    class Blob(val name: String, val off: Int, val bytes: ByteArray, val raw: Gray8?)

    /** A parameter's rule (`FIRMWARE.md` §6.2 step 9): [kind] 0 = [arg]; 1 = the item table's entry at c' + [arg];
     *  2 = c' × pitch + [arg]. */
    class Rule(val kind: Int, val arg: Int)

    /** The pan of width [k] (as asked) one notch in [step] (+1 down, −1 up): [kA] / [kB] rows above and below the band
     *  move, [areasL] / [areasR] the tracks' areas on each lens. */
    class Pan(val k: Int, val kA: Int, val kB: Int, val step: Int, val program: MotionProgram, val rules: List<Rule>) {
        val areasL: List<Rect> get() = program.tracks.map { it.areaL }
        val areasR: List<Rect> get() = program.tracks.map { it.areaR }
        fun areas(left: Boolean) = if (left) areasL else areasR
        /** Pixels one step repaints per lens (the step's cost on the glasses goes with it: `C2-decisions` K). */
        val areaPx: Int get() = program.tracks.sumOf { it.areaL.w * it.areaL.h }
        val prog4: Int get() = program.at
    }

    val pitch: Int get() = -layout.rowH
    val start: Int get() = blobs.first().off
    val end: Int get() = blobs.last().let { it.off + it.bytes.size }
    val bytes: Int get() = blobs.sumOf { it.bytes.size }

    /** The pan for width [k] (capped as built) and [step]. */
    fun pan(k: Int, step: Int): Pan {
        val kA = minOf(k, layout.rowsAbove); val kB = minOf(k, layout.rowsBelow)
        return pans.firstOrNull { it.kA == kA && it.kB == kB && it.step == step }
            ?: throw LintError("Main's staging holds no pan of $kA/$kB rows in step $step")
    }

    /** PLAY's P0..P3 for [pan] landing on the new cursor [cPrime], by each parameter's rule — null at an edge (a new
     *  cursor, or an item, outside the rows: no local motion there). A kind-2 value outside s16 is the firmware's 12. */
    fun params(pan: Pan, cPrime: Int): IntArray? {
        if (cPrime !in 0 until rows) return null
        val p = IntArray(4)
        for ((i, r) in pan.rules.withIndex()) p[i] = when (r.kind) {
            0 -> r.arg
            1 -> { val j = cPrime + r.arg; if (j !in 0 until rows) return null; s16(bands[j].off4) }
            2 -> (cPrime * pitch + r.arg).also { if (it !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("P$i = $it is outside s16 — refused (12)") }
            else -> throw LintError("a parameter rule of kind ${r.kind}")
        }
        return p
    }

    /** END([pan], [p]) on one lens's [pic], as the firmware writes it into that lens's shadow at the PLAY (`FIRMWARE.md`
     *  §5.4): per track its level backdrop, then its last segment's final state, clipped to its area. */
    fun paintEnd(pan: Pan, p: IntArray, left: Boolean, pic: Gray8) = paintState(pan, p, left, pic, start = false)

    /** Every track's state before step 1 (its first segment at j = 0) — what the lens shows if step 1 drew nothing new. */
    fun paintStart(pan: Pan, p: IntArray, left: Boolean, pic: Gray8) = paintState(pan, p, left, pic, start = true)

    private fun paintState(pan: Pan, p: IntArray, left: Boolean, pic: Gray8, start: Boolean) {
        val segs = pan.program.resolve(p, staticOnly = false)
        pan.program.tracks.forEachIndexed { i, t ->
            val area = t.area(left)
            val back = t.backdrop as? Motion.Backdrop.Level ?: throw LintError("Main's programs use level backdrops")
            pic.fillRect(area, back.code * 17)
            val s = (if (start) segs[i].firstOrNull { it.verb != Motion.WAIT } else segs[i].lastOrNull { it.verb != Motion.WAIT })
                ?: return@forEachIndexed
            if (s.verb != Motion.DRAW || s.kind != Motion.KIND_RECORD) throw LintError("Main's programs draw records")
            val own = t.perLens && !left
            val x = (if (own) s.xR else s.xL) + (if (start) 0 else if (own) s.dxR else s.dxL)
            val y = s.y + (if (start) 0 else s.dy)
            val pc = pictureAt(s.src) ?: throw LintError("no picture of Main's at off4 ${s.src}")
            blit(pic, pc, x, y, area, if (start) s.lvl0 else s.lvl1, s.opt)
        }
    }

    private fun pictureAt(off4: Int): Picture? = if (strip.off4 == off4) strip else bands.firstOrNull { it.off4 == off4 }

    /** [pc] at ([x0], [y0]) clipped to [clip] at level [lvl]: the raw pixels at 15 with no option bits (what END always
     *  is), else §2's v1 LUT over its levels (a step's intermediate level). */
    private fun blit(pic: Gray8, pc: Picture, x0: Int, y0: Int, clip: Rect, lvl: Int, opt: Int) {
        val raw = lvl == 15 && opt and 0x30 == 0
        for (yy in maxOf(clip.y, y0, 0) until minOf(clip.bottom, y0 + pc.raw.h, pic.h))
            for (xx in maxOf(clip.x, x0, 0) until minOf(clip.right, x0 + pc.raw.w, pic.w)) {
                val sx = xx - x0; val sy = yy - y0
                if (raw) { pic[xx, yy] = pc.raw[sx, sy]; continue }
                val c = pc.image.levels[sy * pc.raw.w + sx].toInt()
                if (opt and 0x10 != 0 && c == 0) continue
                pic[xx, yy] = ((if (opt and 0x20 != 0) 15 - c else c) * (lvl and 0x0F) / 15) * 17
            }
    }

    companion object {
        /** The widths the probe plays (as asked; [pan] caps them at what the height shows). */
        val WIDTHS = listOf(1, 2, 3)

        private fun s16(v: Int) = if (v > Short.MAX_VALUE) v - 0x10000 else v

        /**
         * Main at [layout] staged for a session cache of [cacheSize] bytes: its pictures painted by [view]'s own
         * painters (`MainSurface.view()`; [kit]'s band paint), each through [relay] — the shell passes its cached-text
         * relay, so a face the glasses hold blits from the atlas here exactly as on the live paint, and nothing is
         * recorded. Refuses, loudly, a staging the reserve cannot hold or a strip taller than a record.
         */
        fun build(layout: Layout, view: WindowView.ListView, kit: ContentKit, listD: Int, bandD: Int, cacheSize: Int,
                  relay: (Gray8, () -> Unit) -> Unit = { _, b -> b() }): MainStaging {
            if (cacheSize < CfwModes.STAGE_RESERVE || cacheSize > CfwModes.CACHE2_MAX_RIDERS || cacheSize % 4 != 0)
                throw LintError("no staging for a cache of $cacheSize B (a contract-2 session's 64..256 KiB)")
            val n = view.rowCount()
            if (n < 1) throw LintError("Main has no rows to stage")
            val l = layout
            val cx = l.content.x
            val rowW = l.content.w - Layout.RAIL_W
            val mA = l.rowsAbove; val mB = l.rowsBelow
            val wraps = n > l.rowsAbove + l.rowsBelow + 1             // `ContentKit.wrapIndex`
            val stripRows = mA + n + mB
            if (stripRows * l.rowH > CfwModes.MAX_IMAGE2_H) throw LintError("Main's strip of $stripRows rows is taller than a record (${CfwModes.MAX_IMAGE2_H})")
            var at = cacheSize - CfwModes.STAGE_RESERVE
            fun next(size: Int): Int { val o = at; at += (size + 3) and 3.inv(); return o }

            // the strip: row form at the pitch, margins as Main shows past the ends
            val sg = Gray8(cx + rowW, stripRows * l.rowH)
            relay(sg) {
                for (s in 0 until stripRows) {
                    val i = s - mA
                    val idx = if (wraps) i.mod(n) else i.takeIf { it in 0 until n } ?: continue
                    view.paintRow(sg, idx, Rect(cx, s * l.rowH, rowW, l.rowH), false)
                }
            }
            val strip = picture("strip", crop(sg, Rect(cx, 0, rowW, sg.h))) { next(it) }
            // each row's band form, painted where the band is (the painters snap x and y to the panel's grid)
            val bands = (0 until n).map { i ->
                val g = Gray8(cx + l.lens.w, l.lens.bottom)
                relay(g) { kit.paintLensBand(g, l, view, i) }
                picture("band $i", crop(g, Rect(l.lens.x, l.lens.y, rowW, l.lens.h))) { next(it) }
            }
            val records = listOf(strip.record) + bands.map { it.record }

            // the programs, back to back after the pictures
            val shift = listD - bandD
            val bandW = rowW + kotlin.math.abs(shift)
            val pans = ArrayList<Pan>()
            val progs = ArrayList<Pair<Int, ByteArray>>()
            val widths = WIDTHS.map { minOf(it, mA) to minOf(it, mB) }.distinct()
            for ((kA, kB) in widths) for (step in intArrayOf(1, -1)) {
                val k = WIDTHS.first { minOf(it, mA) == kA && minOf(it, mB) == kB }
                val at4 = at / 4
                val prog = Motion.program(at4, records) {
                    // the rows above the band: the strip, its start y P0, panned one pitch
                    track(Rect(cx - listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH), Rect(cx + listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH), Motion.Backdrop.Level(0)) {
                        draw(strip.record, x = cx - listD, xR = cx + listD, y = 0, dy = -step * l.rowH, ease = Motion.Ease.easeOut, n = MainPan.T,
                            prefs = listOf(Motion.Pref(0, Motion.Field.Y)))
                    }
                    // the rows below it: the same strip one pitch lower, its start y P3
                    track(Rect(cx - listD, l.lens.bottom, rowW, kB * l.rowH), Rect(cx + listD, l.lens.bottom, rowW, kB * l.rowH), Motion.Backdrop.Level(0)) {
                        draw(strip.record, x = cx - listD, xR = cx + listD, y = 0, dy = -step * l.rowH, ease = Motion.Ease.easeOut, n = MainPan.T,
                            prefs = listOf(Motion.Pref(3, Motion.Field.Y)))
                    }
                    // the band, its area over both planes' x on each lens: the leaving row's form (P2) goes back and dims,
                    // then the entering row's (P1) comes forward and brightens
                    track(Rect(cx - maxOf(listD, bandD), l.lens.y, bandW, l.lens.h), Rect(cx + minOf(listD, bandD), l.lens.y, bandW, l.lens.h), Motion.Backdrop.Level(0)) {
                        draw(bands[0].record, x = cx - bandD, xR = cx + bandD, y = l.lens.y, dx = -shift, dxR = shift,
                            lvl0 = 15, lvl1 = MainPan.LVL_BACK, ease = Motion.Ease.easeOut, n = MainPan.SWAP, prefs = listOf(Motion.Pref(2, Motion.Field.SRC)))
                        draw(bands[0].record, x = cx - listD, xR = cx + listD, y = l.lens.y, dx = shift, dxR = -shift,
                            lvl0 = MainPan.LVL_BACK, lvl1 = 15, ease = Motion.Ease.easeOut, n = MainPan.T - MainPan.SWAP, prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
                    }
                }
                next(prog.bytes.size)
                progs += at4 * 4 to prog.bytes
                pans += Pan(k, kA, kB, step, prog, listOf(
                    Rule(2, l.lens.y + (step - mA) * l.rowH),                 // P0: lens.y − (c + mA) × pitch, c = c' − step
                    Rule(1, 0),                                               // P1: the entering row's band form
                    Rule(1, -step),                                           // P2: the leaving row's
                    Rule(2, l.lens.bottom + (step - 1 - mA) * l.rowH)))       // P3: lens.bottom − (c + 1 + mA) × pitch
            }
            if (at > cacheSize) throw LintError("Main's staging ends at $at, past the $cacheSize B cache: ${at - (cacheSize - CfwModes.STAGE_RESERVE)} B " +
                "for a ${CfwModes.STAGE_RESERVE} B reserve (the strip ${strip.encoded.size} B, $n band forms ${bands.sumOf { it.encoded.size }} B)")
            val progBytes = ByteArray(progs.last().let { (o, b) -> o + b.size } - progs.first().first).also { out ->
                for ((o, b) in progs) b.copyInto(out, o - progs.first().first)
            }
            val blobs = listOf(Blob(strip.name, strip.off, pad(strip.encoded), strip.raw)) +
                bands.map { Blob(it.name, it.off, pad(it.encoded), it.raw) } +
                Blob("programs", progs.first().first, pad(progBytes), null)
            return MainStaging(layout, listD, bandD, n, cacheSize, strip, bands, pans, blobs)
        }

        private fun picture(name: String, raw: Gray8, place: (Int) -> Int): Picture {
            val img = TextureCache.Image2(raw.w, raw.h, ByteArray(raw.w * raw.h) { Pack.level(raw.pix[it].toInt() and 0xFF).toByte() })
            val enc = img.encode()
            return Picture(name, raw, img, enc, place(enc.size))
        }

        private fun crop(g: Gray8, r: Rect): Gray8 = Gray8(r.w, r.h).also { out ->
            for (y in 0 until r.h) System.arraycopy(g.pix, (r.y + y) * g.w + r.x, out.pix, y * r.w, r.w)
        }

        private fun pad(b: ByteArray) = if (b.size % 4 == 0) b else b.copyOf((b.size + 3) / 4 * 4)
    }
}

/**
 * Build C2, stage DMS: Adam's scroll test as a plan (his words, 2026-11-18: "Six notches upwards quickly, pause 2s, six
 * notches downwards one at a time. Pause 3s." once per width). The shell's `mainseq` probe plays it; the pacing is on
 * the shell's loop, never a timeout on a send.
 */
internal object MainSeqPlan {
    /** [fastMs] between the up notches' PLAYs (the ring's fastest measured pace: 282 ms on C1, `C2-spec.md` §6.6),
     *  [pauseMs] before the first down notch, [slowMs] between the down notches (and after the last, before the end),
     *  [betweenMs] between two widths, [followUpMs] from a PLAY to the follow-up flush that draws the rows outside its
     *  areas (it stands in for the phone's answer of the real build). */
    data class Pace(val fastMs: Long = 280, val pauseMs: Long = 2_000, val slowMs: Long = 1_000, val betweenMs: Long = 3_000,
                    val followUpMs: Long = 100)

    /** One notch: width [k], the cursor [from] → [to], played [afterMs] after the previous PLAY was sent (the first:
     *  once the records are counted and nothing is in flight). */
    data class Notch(val k: Int, val from: Int, val to: Int, val afterMs: Long)

    /** Up to six notches each way from the last of [rows] rows (fewer when the list is shorter), for each width. */
    fun plan(ks: List<Int>, rows: Int, pace: Pace): List<Notch> {
        val n = minOf(6, rows - 1)
        val last = rows - 1
        val out = ArrayList<Notch>()
        for ((ki, k) in ks.withIndex()) {
            for (i in 0 until n) out += Notch(k, last - i, last - i - 1, if (i > 0) pace.fastMs else if (ki > 0) pace.betweenMs else 0L)
            for (i in 0 until n) out += Notch(k, last - n + i, last - n + i + 1, if (i == 0) pace.pauseMs else pace.slowMs)
        }
        return out
    }

    /** What RIGHT's notify-114 says (`FIRMWARE.md` §5.8), read from the transport's `motion` note — its form is
     *  `DamageMsg.Motion.describe()`'s, which `MainStagingTest` pins. */
    data class Notify(val tag: Int, val outcome: String, val drawn: Long?, val folded: Long?, val stepUs: Long?)

    private val NOTIFY = Regex("""^seq \S+ slot \S+ tag 0x([0-9a-f]{4}) (drew its last step|closed while drawing|refused \(([^)]*)\)|outcome \S+)(?: ordinal \S+ drawn (\S+) folded (\S+) stepUs (\S+))?""")

    fun parseNotify(detail: String): Notify? {
        val m = NOTIFY.find(detail) ?: return null
        val what = m.groupValues[2]
        val outcome = when {
            what == "drew its last step" -> "drew"
            what == "closed while drawing" -> "closed"
            what.startsWith("refused") -> "refused: ${m.groupValues[3]}"
            else -> what
        }
        return Notify(m.groupValues[1].toInt(16), outcome, m.groupValues[4].toLongOrNull(), m.groupValues[5].toLongOrNull(), m.groupValues[6].toLongOrNull())
    }
}
