package wm.damage.core.shell

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.local.BoundList
import wm.damage.core.motion.MainPan
import wm.damage.core.motion.Motion
import wm.damage.core.motion.MotionProgram
import wm.damage.core.wire.CfwModes

/**
 * Build C2, the spec's package DM (`C2-spec.md` §6.4; `C2-decisions.md` B.4, F, P and V — V replaces B.1 and K): Main's rows staged on the
 * glasses from Main's OWN painters with real text, the pan programs, the press, and the bound list they make. Context
 * for the reader: Adam's own glasses on his own firmware fork (display-rendering work on a personal device); nothing
 * here sends — the shell writes the records through its own stage writes ([MainBinding], the `mainseq` probe).
 *
 * **The records** (one staging = one layout, one pair of planes, one row count), laid out from the start of the region
 * the caller gives (the shared stage reserve at 160 KiB, Main's own region at 256: [CfwModes.mainRegion]), each 4-byte
 * aligned, in this order: the strip, every row's band form, the item table, the programs.
 * - **The strip:** every row in its row form (`MainSurface.paintRow`, not resting) stacked at the row pitch, with
 *   [Layout.rowsAbove] margin rows before row 0 and [Layout.rowsBelow] after the last — the rows Main shows past either
 *   end at the cursor's extremes (the other end's rows where Main wraps, blank where it does not), so a program's END
 *   is Main's own paint at EVERY cursor. Where Main wraps, each margin holds one row more of the other end (decisions
 *   AZ.4): the row a pan ACROSS the wrap shows at its last step, which the phone starts itself ([wrapParams]).
 *   **Each strip row is one segment of a fixed length** inside the one record: its own token stream (no run crosses it),
 *   padded to its length by splitting runs ([rlePadded]) — so a row whose row form changes is written again in its
 *   segment alone, and the record, its offsets and the list's mark stay. The strip is named only inside the pan
 *   programs (no parameter is free to name it), so that write goes only while the list is withdrawn — its entries
 *   CLEARed first, SET again after (decisions X.2: never rewritten under a table that still points at it).
 * - **The band forms:** row i in the lens band as `ContentKit.paintLensBand` paints it with i at the cursor, the rail's
 *   columns aside, each in a slot with slack ([slack]); and one spare slot. A row whose band form changes is written
 *   into a free slot and switched by ONE write of its item-table word — the report's P1/P2 (fields 11–12) then name the
 *   slot the lens drew from, before the switch and after it; the old slot is retired ([retired]) until that write is
 *   acknowledged and then is the next spare (decisions X.2).
 * - **The item table:** one u16 off4 per row, its band form's slot (`FIRMWARE.md` §6.3 LIST; kind 1's lookup).
 * - **The programs:** for each width k (the band and k rows on each side, capped at what the height shows) one notch
 *   down (step +1) and one up (step −1); the bound list's is [WHOLE], every row Main shows at the height (decisions V,
 *   after Adam watched the three widths: "Only the third test looked good" — everything on the screen moves together),
 *   the probe's test widths are [WIDTHS]; and [Press], the tap's press. A pan has three per-lens tracks, none meeting
 *   another: the rows above the band and the rows below it — the strip drawn twice, one pitch apart (decisions F) —
 *   panned one pitch with ease-out over [t] steps at the period [period] ([timing], one per staging, from the whole
 *   list's area), and the band, where the leaving row's band form goes back to the list plane and dims over
 *   [MainPan.SWAP] steps, then the entering row's comes forward from there to the band's plane and brightens (dxL ≠ dxR),
 *   with no size change (stage DM0's motion).
 *
 * **One program per direction serves every cursor** through the parameters, each computed by the rule its binding entry
 * carries (`FIRMWARE.md` §6.2 step 9: kind 0 the argument, kind 1 the item table's entry at c' + arg, kind 2 c' × pitch +
 * arg, c' the new cursor): P0 the upper strip's start y (kind 2), P1 the entering row's band form (kind 1 at c'), P2 the
 * leaving row's (kind 1 at c' − step), P3 the lower strip's start y (kind 2, the same pitch). A new cursor outside the
 * rows is an edge — the wrap included — and no program plays there (§6.4 "Main's wrap is this edge").
 *
 * END (`FIRMWARE.md` §5.4) of every program is level-0 backdrops and records drawn at level 15, so [paintEnd] puts the
 * pictures' RAW pixels into a lens picture: belief then equals what the lens drew byte for byte — from the PHONE'S OWN
 * COPY of the staged records, never from Main's live paint (decisions X.1): a row older than Main's truth still gives a
 * COMMIT, and the ordinary repaint that follows brings the changed cell. A picture is found by the off4 the parameters
 * name, so a fire that drew a retired band slot is painted from that slot's picture.
 */
class MainStaging private constructor(
    val layout: Layout,
    /** The planes in force: Main's list at [listD], its band at [bandD] (`Shell.updatePlanes`; the left lens at x − d). */
    val listD: Int,
    val bandD: Int,
    val rows: Int,
    val cacheSize: Int,
    /** Every program's period in OS ticks and the pans' steps ([timing] of the whole list's area at this height). */
    val period: Int,
    val t: Int,
    /** The region the records lie in, `[regionStart, regionEnd)`. */
    val regionStart: Int,
    val regionEnd: Int,
    val strip: Picture,
    /** Decisions AD.2: on a 256 KiB session (Main's own region) a second strip slot — a row-form change is written there
     *  whole-or-by-segment and the entries are SET to the pans that name it, with no withdrawal; null at 160 KiB, where
     *  the strip is written in place with the list withdrawn (decisions Z). Its [Picture.encoded] is empty until first
     *  written. */
    val stripB: Picture?,
    /** Each row's band form as the item table names it now (a re-stage moves a row to another slot, [apply]). */
    val bands: MutableList<Picture>,
    /** The item table's off4 and its entries (each band form's slot, a u16 off4), as the glasses were last sent them. */
    val items4: Int,
    val itemOffs: IntArray,
    /** Band slots no table names: a re-stage writes into one ([spares]: byte offset to size). */
    private val spares: MutableMap<Int, Int>,
    val pans: List<Pan>,
    val press: Press,
    /** What goes into the cache, in write order; [end] is the byte past the last. */
    val blobs: List<Blob>,
    /** Strip row s shows Main's row [stripRow]`[s]` (−1: a blank margin row). */
    private val stripRow: IntArray,
    /** Build ANIM (AW.1): the `Animations` rows the pans were made for; null: the fixed rule (the probes). */
    val motion: ScrollMotion? = null,
) {
    /** Build ANIM (decisions AV, AW): one notch's motion on a bound list — [lengthMs] (`Scroll length`) and [frames]
     *  (`Frames`, 0 = Auto). */
    data class ScrollMotion(val lengthMs: Int, val frames: Int) {
        companion object {
            /** `ShellSettings`' defaults: 300 ms, Auto (T 6 at P 46 at height 352; decisions AY.7, AZ.7). */
            val DEFAULT = ScrollMotion(300, 0)
        }
    }

    /** One picture Main's painters drew: [raw] as painted (8-bit; what [paintEnd] puts into belief), [levels] what the
     *  glasses decode, [encoded] the v2 record (`[w u16][h u16][RLE]`, `FIRMWARE.md` §4) at the byte offset [off] in a
     *  slot of [slot] bytes. The strip's [segOff] / [segLen] are its rows' segments inside the record. [raw], [levels]
     *  and [encoded] change in place at a re-stage ([apply]). */
    class Picture internal constructor(val name: String, val raw: Gray8, val levels: ByteArray, encoded: ByteArray,
        val off: Int, val slot: Int, val segOff: IntArray? = null, val segLen: IntArray? = null) {
        var encoded: ByteArray = encoded
            internal set
        val off4: Int get() = off / 4
        /** The record as a program names it; its read range is the whole slot (a re-stage changes the length inside it). */
        val record: Motion.Source.Record get() = Motion.Source.Record(off4, raw.w, raw.h, slot)
    }

    /** One cache write's worth: [bytes] padded to the next 4-byte boundary with explicit zeros (the live cache is not
     *  zeroed, `HANDOFF.md` §65), at the byte offset [off]. */
    class Blob(val name: String, val off: Int, val bytes: ByteArray, val raw: Gray8?)

    /** A parameter's rule (`FIRMWARE.md` §6.2 step 9): [kind] 0 = [arg]; 1 = the item table's entry at c' + [arg];
     *  2 = c' × pitch + [arg]. */
    class Rule(val kind: Int, val arg: Int)

    /** The pan of width [k] (as asked) one notch in [step] (+1 down, −1 up): [kA] / [kB] rows above and below the band
     *  move, [areasL] / [areasR] the tracks' areas on each lens. */
    class Pan(val k: Int, val kA: Int, val kB: Int, val step: Int, val program: MotionProgram, val rules: List<Rule>,
              /** The strip slot it draws (0 [strip], 1 [stripB]). */
              val strip: Int = 0) {
        val areasL: List<Rect> get() = program.tracks.map { it.areaL }
        val areasR: List<Rect> get() = program.tracks.map { it.areaR }
        fun areas(left: Boolean) = if (left) areasL else areasR
        /** Pixels one step repaints per lens (the step's cost on the glasses goes with it: `C2-decisions` K). */
        val areaPx: Int get() = program.tracks.sumOf { it.areaL.w * it.areaL.h }
        val prog4: Int get() = program.at
    }

    /** The tap's press (`FIRMWARE.md` §6.4 "The tap press", §6.9; rulings §14 E2): the band row at the cursor (P0, kind 1
     *  at c' — a tap's c' is the cursor) moved one more depth notch forward, from [bandD] to [pressD], over [PRESS_T]
     *  steps; the lens then holds it until the slot's DISCARD after the act's first frame. No size change. */
    class Press(val pressD: Int, val program: MotionProgram, val rules: List<Rule>) {
        fun areas(left: Boolean) = program.tracks.map { it.area(left) }
        val prog4: Int get() = program.at
    }

    /** Decisions BF (G4): the period the bound list's entries are SET with — the fit's [period] after a staging, then the
     *  measured steps' ([Shell]'s adaptive period). The programs, their T and the records do not change with it. */
    var periodInForce: Int = period

    val pitch: Int get() = -layout.rowH
    val start: Int get() = blobs.first().off
    val end: Int get() = blobs.last().let { it.off + it.bytes.size }
    val bytes: Int get() = blobs.sumOf { it.bytes.size }

    /** The strip the bound list's pans draw now (0 [strip], 1 [stripB]): switched by a row-form change at 256 KiB. */
    var activeStrip = 0
        private set
    private val cur: Picture get() = if (activeStrip == 0) strip else stripB!!

    /** The pan for width [k] (capped as built) and [step]: the whole list's draws the active strip, a test width strip 0. */
    fun pan(k: Int, step: Int): Pan {
        val kA = minOf(k, layout.rowsAbove); val kB = minOf(k, layout.rowsBelow)
        val sx = if (k == WHOLE) activeStrip else 0
        return pans.firstOrNull { it.kA == kA && it.kB == kB && it.step == step && it.strip == sx }
            ?: throw LintError("Main's staging holds no pan of $kA/$kB rows in step $step")
    }

    /** PLAY's P0..P3 for [pan] landing on the new cursor [cPrime], by each parameter's rule — null at an edge (a new
     *  cursor, or an item, outside the rows: no local motion there). A kind-2 value outside s16 is the firmware's 12. */
    fun params(pan: Pan, cPrime: Int): IntArray? = paramsBy(pan.rules, cPrime)

    /** Decisions AZ.4: Main wraps here — its strip's margins hold the other end's rows (none blank), one more at each end. */
    val wraps: Boolean get() = stripRow.size > rows && stripRow.all { it >= 0 }

    /**
     * Decisions AZ.4: PLAY's P0..P3 for [pan] across the wrap — from the end row to the row just past it, [cPrime] −1 (an
     * up notch from row 0) or [rows] (a down notch from the last row) — or null where Main does not wrap or [cPrime] is not
     * that. A kind-1 parameter names the other end's row (the item at c' mod [rows]); a kind-2 one takes c' unwrapped, and
     * the strip's extra margin row is what it shows at the last step — so END is Main's own paint at the wrapped row, the
     * picture slides one row as at every other notch, and the lens's cursor is the answer's to set (its DISCARD names it).
     */
    fun wrapParams(pan: Pan, cPrime: Int): IntArray? {
        if (!wraps || (cPrime != -1 && cPrime != rows) || cPrime - pan.step !in 0 until rows) return null
        val p = IntArray(4)
        for ((i, r) in pan.rules.withIndex()) p[i] = when (r.kind) {
            CfwModes.KIND_ARG -> r.arg
            CfwModes.KIND_ITEM -> s16(itemOffs[(cPrime + r.arg).mod(rows)])
            CfwModes.KIND_PITCH -> (cPrime * pitch + r.arg).also { if (it !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("P$i = $it is outside s16 — refused (12)") }
            else -> throw LintError("a parameter rule of kind ${r.kind}")
        }
        return p
    }

    /** The press's P0..P3 at the cursor [c] (a tap does not move it), or null outside the rows. */
    fun pressParams(c: Int): IntArray? = paramsBy(press.rules, c)

    private fun paramsBy(rules: List<Rule>, cPrime: Int): IntArray? {
        if (cPrime !in 0 until rows) return null
        val p = IntArray(4)
        for ((i, r) in rules.withIndex()) p[i] = when (r.kind) {
            CfwModes.KIND_ARG -> r.arg
            CfwModes.KIND_ITEM -> { val j = cPrime + r.arg; if (j !in 0 until rows) return null; s16(itemOffs[j]) }
            CfwModes.KIND_PITCH -> (cPrime * pitch + r.arg).also { if (it !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("P$i = $it is outside s16 — refused (12)") }
            else -> throw LintError("a parameter rule of kind ${r.kind}")
        }
        return p
    }

    /** END([pan], [p]) on one lens's [pic], as the firmware writes it into that lens's shadow at the PLAY or COMMIT
     *  (`FIRMWARE.md` §5.4, §6.5): per track its level backdrop, then its last segment's final state, clipped to its area. */
    fun paintEnd(pan: Pan, p: IntArray, left: Boolean, pic: Gray8) = paintState(pan.program, p, left, pic, start = false)

    /** END of the program at [prog4] (a pan or the press) — the bound list's [BoundList.paintEnd]. */
    fun paintEndOf(prog4: Int, p: IntArray, left: Boolean, pic: Gray8) = paintState(programAt(prog4), p, left, pic, start = false)

    /** Every track's state before step 1 (its first segment at j = 0) — what the lens shows if step 1 drew nothing new. */
    fun paintStart(pan: Pan, p: IntArray, left: Boolean, pic: Gray8) = paintState(pan.program, p, left, pic, start = true)

    fun paintStartOf(prog4: Int, p: IntArray, left: Boolean, pic: Gray8) = paintState(programAt(prog4), p, left, pic, start = true)

    fun programAt(prog4: Int): MotionProgram = if (press.prog4 == prog4) press.program
        else pans.firstOrNull { it.prog4 == prog4 }?.program ?: throw LintError("Main's staging holds no program at off4 $prog4")

    private fun paintState(program: MotionProgram, p: IntArray, left: Boolean, pic: Gray8, start: Boolean) {
        val segs = program.resolve(p, staticOnly = false)
        program.tracks.forEachIndexed { i, t ->
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

    private fun pictureAt(off4: Int): Picture? = if (strip.off4 == off4) strip else if (stripB?.off4 == off4) stripB
        else bands.firstOrNull { it.off4 == off4 } ?: retired[off4]

    /** Band forms a switch has replaced, by off4, kept until the switch's write is acknowledged ([release]): a report from a
     *  fire before the switch names them, and its COMMIT's END draws them. */
    val retired = HashMap<Int, Picture>()

    /** [pc] at ([x0], [y0]) clipped to [clip] at level [lvl]: the raw pixels at 15 with no option bits (what END always
     *  is), else §2's v1 LUT over its levels (a step's intermediate level). */
    private fun blit(pic: Gray8, pc: Picture, x0: Int, y0: Int, clip: Rect, lvl: Int, opt: Int) {
        val raw = lvl == 15 && opt and 0x30 == 0
        for (yy in maxOf(clip.y, y0, 0) until minOf(clip.bottom, y0 + pc.raw.h, pic.h))
            for (xx in maxOf(clip.x, x0, 0) until minOf(clip.right, x0 + pc.raw.w, pic.w)) {
                val sx = xx - x0; val sy = yy - y0
                if (raw) { pic[xx, yy] = pc.raw[sx, sy]; continue }
                val c = pc.levels[sy * pc.raw.w + sx].toInt()
                if (opt and 0x10 != 0 && c == 0) continue
                pic[xx, yy] = ((if (opt and 0x20 != 0) 15 - c else c) * (lvl and 0x0F) / 15) * 17
            }
    }

    // ---------------------------------------------------------------------------------------- the bound list
    /**
     * Main's list as the glasses bind it (`FIRMWARE.md` §6.3; spec §6.4): list [LIST] in slot [SLOT], [rows] rows at
     * [pitch], the item table, and three entries — id 4 (UPROLL, the next row: `EV_SCROLL_BOTTOM`) the pan down, id 5
     * (DOWNROLL) the pan up, both of the whole list ([WHOLE]), and the tap entry with the press, all at [period]. Which form of the tap entry is SET is
     * `Taps`' (the rules, §6.7: none under `phone`, report-only under `report`, this one under `glasses`). [mark] is this
     * staging's identity (a full re-stage makes a new one; a row re-staged keeps it, P9). The item table is the one
     * as last sent ([itemOffs]); [BoundList.paintEnd] paints END from the picture each parameter names; [previous] (the
     * list before the last band switch, until its write is acknowledged) lets a report from a fire before the switch be
     * named ([BoundList.names]).
     */
    fun boundList(mark: Int, previous: BoundList? = null): BoundList {
        val down = pan(WHOLE, 1); val up = pan(WHOLE, -1)
        fun params(rules: List<Rule>) = rules.map { CfwModes.BindParam(it.kind, it.arg) }
        val entries = listOf(
            BoundList.Entry(ENTRY_DOWN, CfwModes.EV_UPROLL, BoundList.rowStep(CfwModes.EV_UPROLL), periodInForce, down.prog4, params(down.rules), down.program),
            BoundList.Entry(ENTRY_UP, CfwModes.EV_DOWNROLL, BoundList.rowStep(CfwModes.EV_DOWNROLL), periodInForce, up.prog4, params(up.rules), up.program),
            BoundList.Entry(ENTRY_TAP, CfwModes.EV_TAP, 0, periodInForce, press.prog4, params(press.rules), press.program),
        )
        return BoundList(LIST, SLOT, rows, pitch, mark, items4, itemOffs.copyOf(), entries,
            paintEnd = { left, prog4, p, pic -> paintEndOf(prog4, p, left, pic) }).also { it.previous = previous }
    }

    // -------------------------------------------------------------------------------------- re-staging a row
    /** One write of a re-stage: [bytes] at the byte offset [off] (4-aligned, a multiple of 4 long). */
    class Write(val off: Int, val bytes: ByteArray, val what: String)

    /** A band form's move: its new record written into the free slot at [off] ([slot] bytes), then [switch] — the one
     *  item-table word that names it (two u16 entries, the other one unchanged) — after it in the same flush. */
    class BandMove internal constructor(val row: Int, internal val g: Gray8, val encoded: ByteArray, val off: Int, val slot: Int,
        val write: Write, val switch: Write, val oldOff4: Int)

    /** Row [row]'s pictures painted again ([build]'s painters and relay): [Same] when they are what the glasses were last
     *  sent; [Changed] with what puts them on the glasses — [strip] its row form's segments (in place, written only while
     *  the list is withdrawn; or, with [Changed.toStrip], into the other strip slot, which the entries are then SET to:
     *  decisions AD.2) and [band] its band form's move; [Outgrown] when a segment no longer fits or no free slot holds the
     *  band form (a full staging, a new mark). */
    sealed class Restage {
        object Same : Restage()
        class Changed(val row: Int, val strip: List<Write>, internal val stripRows: List<Pair<Int, Gray8>>, val band: BandMove?,
                      /** The strip's segment lengths when its tail was laid out again, else null. */
                      internal val relaid: IntArray? = null,
                      /** AD.2: the strip slot the writes go to and the pans that name it (null: in place, withdrawn). */
                      val toStrip: Int? = null,
                      /** AD.2: the switched-to strip's whole record as it will then be. */
                      internal val stripEnc: ByteArray? = null) : Restage() {
            /** In write order: the strip's segments, the band form, its switch. */
            val writes: List<Write> get() = strip + listOfNotNull(band?.write, band?.switch)
            val bytes: Int get() = writes.sumOf { it.bytes.size }
        }
        class Outgrown(val why: String) : Restage()
    }

    /** Re-paints row [row] (its row form at every strip row that shows it, its band form) and compares the raw pixels
     *  with what the glasses were last sent. [force]: the writes even when nothing changed (a write that failed). */
    fun restage(row: Int, view: WindowView.ListView, kit: ContentKit, relay: (Gray8, () -> Unit) -> Unit, force: Boolean = false): Restage {
        if (row !in 0 until rows) throw LintError("Main has no row $row to re-stage (it stages $rows)")
        val l = layout
        val c = cur
        var writes = ArrayList<Write>()
        val newStrip = ArrayList<Pair<Int, Gray8>>()
        val w = c.raw.w
        val segLen = c.segLen!!
        val segOff = c.segOff!!
        val g = paintStripRow(view, l, row, relay)
        val lv = levelsOf(g)
        var outgrown = -1
        for (s in stripRow.indices) {
            if (stripRow[s] != row) continue
            val same = !force && java.util.Arrays.equals(g.pix, 0, g.pix.size, c.raw.pix, s * l.rowH * w, (s + 1) * l.rowH * w)
            if (same) continue
            newStrip += s to g
            val seg = rlePadded(lv, 0, lv.size, segLen[s])
            if (seg == null) { if (outgrown < 0) outgrown = s; continue }
            writes += Write(c.off + segOff[s], seg, "strip row $s")
        }
        var relaid: IntArray? = null
        if (outgrown >= 0) {
            // a segment outgrew its length (a longer title): the strip's tail from that segment is laid out again inside the
            // strip's own slot — its slack is the whole strip's — and written as one piece (the list withdrawn, as every
            // strip write); only when the tail does not fit is a full staging owed
            val first = outgrown
            val segPx = l.rowH * w
            val lens = segLen.copyOf()
            val tail = java.io.ByteArrayOutputStream()
            for (k in first until stripRow.size) {
                val lk = if (newStrip.any { it.first == k }) lv else c.levels.copyOfRange(k * segPx, (k + 1) * segPx)
                lens[k] = segSlack(rleSize(lk, 0, lk.size))
                tail.write(rlePadded(lk, 0, lk.size, lens[k])!!)
            }
            if (segOff[first] + tail.size() > c.slot)
                return Restage.Outgrown("strip row $first (Main's row $row) outgrew its segment and the strip's tail (${tail.size()} B) no longer fits its slot")
            writes.removeAll { it.off >= c.off + segOff[first] }
            writes += Write(c.off + segOff[first], tail.toByteArray(), "strip rows $first..${stripRow.size - 1}, laid out again")
            relaid = lens
        }
        // decisions AD.2: with a second strip slot the new strip goes there — every segment that differs from what that slot
        // holds — and the entries are SET to its pans; nothing is written under a table that names it
        var toStrip: Int? = null
        var stripEnc: ByteArray? = null
        val o = if (activeStrip == 0) stripB else strip
        if (o != null && writes.isNotEmpty()) {
            val enc = c.encoded.copyOf()
            var full = enc
            for (wr in writes) { val at = wr.off - c.off; full = full.copyOf(maxOf(full.size, at + wr.bytes.size)).also { wr.bytes.copyInto(it, at) } }
            val nLens = relaid ?: segLen
            val nOffs = IntArray(nLens.size).also { a -> for (k in nLens.indices) a[k] = if (k == 0) segOff[0] else a[k - 1] + nLens[k - 1] }
            val ow = ArrayList<Write>()
            val to = 1 - activeStrip
            val oOff = o.segOff!!; val oLen = o.segLen!!
            // segment by segment against what that slot holds (written with the base records; the header — w, h — is the
            // same in both); where the two layouts part (a tail laid out again in either), the rest goes as one piece.
            // Segments are 4-byte aligned.
            for (k in nLens.indices) {
                if (oOff[k] != nOffs[k] || oLen[k] != nLens[k]) { ow += Write(o.off + nOffs[k], pad(full.copyOfRange(nOffs[k], full.size)), "strip rows $k.. into slot $to"); break }
                val seg = full.copyOfRange(nOffs[k], nOffs[k] + nLens[k])
                if (!seg.contentEquals(o.encoded.copyOfRange(oOff[k], oOff[k] + oLen[k]))) ow += Write(o.off + nOffs[k], seg, "strip row $k into slot $to")
            }
            writes = ow
            toStrip = 1 - activeStrip
            stripEnc = full
        }
        val bg = paintBandForm(view, kit, l, row, relay)
        val old = bands[row]
        var band: BandMove? = null
        if (force || !bg.pix.contentEquals(old.raw.pix)) {
            val enc = TextureCacheImage.encode(bg)
            val (off, slot) = spares.entries.filter { it.value >= enc.size }.minByOrNull { it.value }?.toPair()
                ?: return Restage.Outgrown("no free slot holds band form $row's ${enc.size} B (free: ${spares.values.sorted()})")
            // the item table's 4-byte word holding entry [row] and its neighbour (zero past the last: the table's pad)
            val w0 = row and 1.inv()
            val word = ByteArray(4)
            for (k in 0..1) { val v = when (w0 + k) { row -> off / 4; in 0 until rows -> itemOffs[w0 + k]; else -> 0 }
                put16(word, 2 * k, v) }
            band = BandMove(row, bg, enc, off, slot, Write(off, pad(enc), "band form $row"), Write(items4 * 4 + 2 * w0, word, "item $row"), old.off4)
        }
        return if (writes.isEmpty() && band == null) Restage.Same else Restage.Changed(row, writes, newStrip, band, relaid, toStrip, stripEnc)
    }

    /** The glasses are sent [r]'s writes (one flush, in [Restage.Changed.writes]' order): from now on the pictures and
     *  the item table here are [r]'s — the strip's segments in place (the list withdrawn) or, with [Restage.Changed.toStrip],
     *  the other strip slot made the active one (AD.2); the band form at its new slot, the old slot retired until [release]. */
    fun apply(r: Restage.Changed) {
        val c = cur
        val target = when (r.toStrip) { null -> c; 0 -> strip; else -> stripB!! }
        val w = c.raw.w
        if (target !== c) { System.arraycopy(c.raw.pix, 0, target.raw.pix, 0, c.raw.pix.size); System.arraycopy(c.levels, 0, target.levels, 0, c.levels.size)
            c.segLen!!.copyInto(target.segLen!!); c.segOff!!.copyInto(target.segOff!!) }
        for ((s, g) in r.stripRows) {
            System.arraycopy(g.pix, 0, target.raw.pix, s * layout.rowH * w, g.pix.size)
            val lv = levelsOf(g)
            System.arraycopy(lv, 0, target.levels, s * layout.rowH * w, lv.size)
        }
        r.relaid?.let { lens ->
            val so = target.segOff!!; val sl = target.segLen!!
            for (k in lens.indices) { sl[k] = lens[k]; if (k > 0) so[k] = so[k - 1] + sl[k - 1] }
        }
        if (r.stripEnc != null) target.encoded = r.stripEnc
        else {
            var enc = target.encoded
            for (wr in r.strip) {
                val at = wr.off - target.off
                enc = enc.copyOf(maxOf(enc.size, at + wr.bytes.size)).also { wr.bytes.copyInto(it, at) }
            }
            target.encoded = enc
        }
        r.toStrip?.let { activeStrip = it }
        r.band?.let { m ->
            val old = bands[m.row]
            retired[old.off4] = old
            spares.remove(m.off)
            bands[m.row] = Picture("band ${m.row}", m.g, levelsOf(m.g), m.encoded, m.off, m.slot)
            itemOffs[m.row] = m.off / 4
        }
    }

    /** The switch that retired the band slot at [off4] has been acknowledged: no later report can name it, and the next
     *  write into it comes after the switch in stream order (P9) — it is a free slot again. */
    fun release(off4: Int) { retired.remove(off4)?.let { spares[it.off] = it.slot } }

    /** The free band slots (byte offset to size), for the report and the tests. */
    val free: Map<Int, Int> get() = spares

    /**
     * Decisions AF.8: Main's pictures painted a row per call — the shell paints one row per loop turn ([step]), so no
     * handler paints the whole staging (484 ms was measured on his phone for one), then [assemble] lays them out.
     * Each row is painted once: its row form (every strip row that shows it shares it) and its band form.
     */
    class Paint(val layout: Layout, private val view: WindowView.ListView, private val kit: ContentKit) {
        val n = view.rowCount()
        internal val rowForms = arrayOfNulls<Gray8>(n)
        internal val bandForms = arrayOfNulls<Gray8>(n)
        var next = 0
            private set
        val done: Boolean get() = next >= n
        fun step(relay: (Gray8, () -> Unit) -> Unit) {
            rowForms[next] = paintStripRow(view, layout, next, relay)
            bandForms[next] = paintBandForm(view, kit, layout, next, relay)
            next++
        }
    }

    companion object {
        /** The widths the probe plays (as asked; [pan] caps them at what the height shows). */
        val WIDTHS = listOf(1, 2, 3)
        /** The bound list's width: every row Main shows at the height in force ([pan] caps it there; decisions V). */
        const val WHOLE = Int.MAX_VALUE

        /**
         * The period (OS ticks, 1,024 a second: `FIRMWARE.md` §5.2's 90,000-tick lease is 87.9 s) and the steps of a
         * program that repaints [areaPx] pixels a step per lens (decisions V, AU.3): the step's drawing time from the fit —
         * ms ≈ [STEP_MS_BASE] + 0.156 per 1,000 area pixels — gives the smallest period P0 that holds it and T, the whole
         * number of steps nearest to 200 ms at P0 (at least the band's swap plus one), times [stepsFactor] (AU.5: a longer
         * pan by more, smaller steps, never slower ones); the period sent is P0 with [PERIOD_MARGIN] on it (rounded up),
         * inside 16..128 — a step's own time varies from motion to motion.
         */
        fun timing(areaPx: Int, motion: ScrollMotion? = null): Pair<Int, Int> {
            val stepMs = STEP_MS_BASE + STEP_MS_PER_KPX * areaPx / 1000.0
            val p0 = kotlin.math.ceil(stepMs * 1024.0 / 1000.0).toInt()
            val pFit = kotlin.math.ceil(p0 * PERIOD_MARGIN).toInt().coerceIn(CfwModes.MOTION_PERIODS.first, CfwModes.MOTION_PERIODS.last)
            // the fixed rule (T1; the `mainseq` probe keeps it, AW.4): T nearest 200 ms at P0
            if (motion == null) return pFit to kotlin.math.round(MOTION_MS / (p0 * 1000.0 / 1024.0)).toInt().coerceAtLeast(MIN_STEPS)
            // build ANIM (AV, AW.1): Auto — the most steps that fit in the length at the fitted period; a fixed count — the
            // period that spreads it over the length, never under the fitted one (the motion may then run longer)
            val lenTicks = motion.lengthMs * 1024.0 / 1000.0
            if (motion.frames <= 0) return pFit to kotlin.math.floor(lenTicks / pFit).toInt().coerceAtLeast(MIN_STEPS)
            val p = maxOf(pFit, kotlin.math.ceil(lenTicks / motion.frames).toInt()).coerceIn(CfwModes.MOTION_PERIODS.first, CfwModes.MOTION_PERIODS.last)
            return p to maxOf(motion.frames, MIN_STEPS)
        }
        /** The fewest steps a pan has: the band's two segments need at least one each (the swap is ≥ 1). */
        const val MIN_STEPS = 3
        /** The band's swap — the leaving row's share of the steps — kept at its 2-of-5 proportion for any T. */
        fun swapOf(t: Int): Int = kotlin.math.round(t * MainPan.SWAP / 5.0).toInt().coerceIn(1, t - 1)
        /** The pixels one step of the whole-list pan repaints per lens at [l] with the list at [listD] and the band at
         *  [bandD] (the tracks above and below the band, and the band's area): what [timing] is fitted on. */
        fun wholeAreaPx(l: Layout, listD: Int, bandD: Int): Int {
            val rowW = l.content.w - Layout.RAIL_W
            return (l.rowsAbove + l.rowsBelow) * l.rowH * rowW + l.lens.h * (rowW + kotlin.math.abs(listD - bandD))
        }
        /** The fit: the slope from the three points measured on 11-18 with the `mainseq` probe at height 352 (76,544 /
         *  114,688 / 152,832 px a step), the base from the bound list's own steps on glass in the first trial at stage 4
         *  (11-19, height 352, 152,832 px: 38.8–41.4 ms, median 39.5 — decisions AU.3; 11-18's fit said 38.2). */
        const val STEP_MS_BASE = 15.66
        const val STEP_MS_PER_KPX = 0.156
        /** AU.3: 13 of the first trial's 32 motions folded a step at P 42 (41.0 ms) — the period carries 10 % over the fit. */
        const val PERIOD_MARGIN = 1.10
        /** The motion's length the steps are chosen for (≈ 200 ms: under the ring's least scroll gap, spec §6.6). */
        const val MOTION_MS = 200.0
        /** Main's list on the glasses and its engine slot (`FIRMWARE.md` §6.3: one slot per list). */
        const val LIST = 0
        const val SLOT = 0
        /** Decisions AZ.4: the phone's PLAY across the wrap goes in a slot no list is bound to (P9: no PLAY into a bound
         *  slot); the next fire in [SLOT] closes it (its close pass meets the same areas), as a PLAY's or COMMIT's would. */
        const val WRAP_SLOT = 1
        /** The entries' numbers (§6.3: 0..7; the lowest-numbered match fires, and each event id has one). */
        const val ENTRY_DOWN = 0
        const val ENTRY_UP = 1
        const val ENTRY_TAP = 2
        /** The press: T = 3 steps, then HOLD (spec §6.6; decisions B.10). */
        const val PRESS_T = 3
        /** A slot's slack (decisions B.4): a quarter of the picture's record, at least [SLACK_MIN] bytes — what a row's
         *  summary may grow by before its re-stage needs a full staging (a new mark). */
        const val SLACK_DIV = 4
        const val SLACK_MIN = 64
        /** The strip's own slot beyond its segments: an eighth of them (a longer title re-lays the tail into it). */
        const val STRIP_EXTRA_DIV = 8
        /** A strip segment's slack: an eighth of its row form. */
        const val SEG_SLACK_DIV = 8

        private fun s16(v: Int) = v.toShort().toInt()

        /** A slot for a record of [n] bytes: [n] plus the slack, 4-byte aligned. */
        fun slack(n: Int): Int = (n + maxOf(n / SLACK_DIV, SLACK_MIN) + 3) and 3.inv()

        /** A strip segment for a row form of [n] bytes: an eighth more (the strip's own extra room takes a larger growth,
         *  [STRIP_EXTRA_DIV]). */
        fun segSlack(n: Int): Int = (n + maxOf(n / SEG_SLACK_DIV, SLACK_MIN) + 3) and 3.inv()

        /**
         * Main at [layout] staged for a session cache of [cacheSize] bytes, its records laid out from [regionStart] and
         * refused past [regionEnd] (the shared stage reserve by default; [CfwModes.mainRegion] for the bound list): its
         * pictures painted by [view]'s own painters (`MainSurface.view()`; [kit]'s band paint), each through [relay] —
         * the shell passes its cached-text relay, so a face the glasses hold blits from the atlas here exactly as on the
         * live paint, and nothing is recorded. Refuses, loudly, a staging the region cannot hold or a strip taller than a
         * record.
         */
        fun build(layout: Layout, view: WindowView.ListView, kit: ContentKit, listD: Int, bandD: Int, cacheSize: Int,
                  regionStart: Int = cacheSize - CfwModes.STAGE_RESERVE, regionEnd: Int = cacheSize,
                  relay: (Gray8, () -> Unit) -> Unit = { _, b -> b() }, widths: List<Int> = WIDTHS + WHOLE, twoStrips: Boolean = false,
                  motion: ScrollMotion? = null): MainStaging {
            val p = Paint(layout, view, kit)
            while (!p.done) p.step(relay)
            return assemble(p, listD, bandD, cacheSize, regionStart, regionEnd, widths, twoStrips, motion)
        }

        /**
         * [paint]'s pictures laid out from [regionStart] and refused past [regionEnd], with the programs of [widths] (the
         * bound staging builds only [WHOLE]: AF.10 — the probe's test widths are the probe's) and, with [twoStrips], a
         * second strip slot and the whole list's pans that draw it (AD.2: Main's own region at 256 KiB).
         */
        fun assemble(paint: Paint, listD: Int, bandD: Int, cacheSize: Int, regionStart: Int = cacheSize - CfwModes.STAGE_RESERVE,
                     regionEnd: Int = cacheSize, widths: List<Int> = WIDTHS + WHOLE, twoStrips: Boolean = false,
                     motion: ScrollMotion? = null): MainStaging {
            val layout = paint.layout
            if (cacheSize < CfwModes.STAGE_RESERVE || cacheSize > CfwModes.CACHE2_MAX_RIDERS || cacheSize % 4 != 0)
                throw LintError("no staging for a cache of $cacheSize B (a contract-2 session's 64..256 KiB)")
            if (regionStart % 4 != 0 || regionStart < 0 || regionEnd > cacheSize || regionStart >= regionEnd)
                throw LintError("no staging in the region $regionStart..$regionEnd of a $cacheSize B cache")
            if (!paint.done) throw LintError("Main's staging assembled before its ${paint.n} rows were painted (${paint.next} so far)")
            val n = paint.n
            if (n < 1) throw LintError("Main has no rows to stage")
            if (n > CfwModes.LIST_MAX) throw LintError("Main's $n rows are more than a bound list holds")
            val l = layout
            val cx = l.content.x
            val rowW = l.content.w - Layout.RAIL_W
            val mA = l.rowsAbove; val mB = l.rowsBelow
            val wraps = n > l.rowsAbove + l.rowsBelow + 1             // `ContentKit.wrapIndex`
            // decisions AZ.4: the strip's margins — one row more at each end where Main wraps, for the pan across the wrap
            val sA = if (wraps) mA + 1 else mA; val sB = if (wraps) mB + 1 else mB
            val stripRows = sA + n + sB
            if (stripRows * l.rowH > CfwModes.MAX_IMAGE2_H) throw LintError("Main's strip of $stripRows rows is taller than a record (${CfwModes.MAX_IMAGE2_H})")
            var at = regionStart
            fun next(size: Int): Int { val o = at; at += (size + 3) and 3.inv(); return o }

            // the strip: row form at the pitch, margins as Main shows past the ends; one fixed segment per strip row
            val stripRow = IntArray(stripRows) { s -> val i = s - sA; if (wraps) i.mod(n) else if (i in 0 until n) i else -1 }
            val sg = Gray8(rowW, stripRows * l.rowH)
            for (s in 0 until stripRows) if (stripRow[s] >= 0)
                paint.rowForms[stripRow[s]]!!.pix.copyInto(sg.pix, s * l.rowH * rowW)
            val slv = levelsOf(sg)
            val segPx = l.rowH * rowW
            val segLen = IntArray(stripRows) { s -> segSlack(rleSize(slv, s * segPx, (s + 1) * segPx)) }
            val segOff = IntArray(stripRows)
            val stripEnc = ByteArray(4 + segLen.sum())
            put16(stripEnc, 0, rowW); put16(stripEnc, 2, sg.h)
            var o = 4
            for (s in 0 until stripRows) {
                segOff[s] = o
                rlePadded(slv, s * segPx, (s + 1) * segPx, segLen[s])!!.copyInto(stripEnc, o)
                o += segLen[s]
            }
            // the strip's slot: its segments and an eighth more, so a row grown past its segment re-lays the tail in place
            val stripSlot = (stripEnc.size + stripEnc.size / STRIP_EXTRA_DIV + 3) and 3.inv()
            val strip = Picture("strip", sg, slv, stripEnc, next(stripSlot), stripSlot, segOff, segLen)
            // AD.2: the second strip slot, one size with the first, written with the base records (decisions AL.4: so a
            // row-form change switches with only its differing segments, never the whole strip in the switch's flush)
            val stripB = if (!twoStrips) null else Picture("strip B", Gray8(sg.w, sg.h).also { sg.pix.copyInto(it.pix) }, slv.copyOf(),
                stripEnc.copyOf(), next(stripSlot), stripSlot, segOff.copyOf(), segLen.copyOf())
            // each row's band form, painted where the band is (the painters snap x and y to the panel's grid), in a slot
            // every band slot one size — the largest band form's with its slack — so a row's moved band form fits any free
            // slot, the spare or one a switch freed (a smaller row has the difference as its headroom besides)
            val bandPics = (0 until n).map { i -> paint.bandForms[i]!!.let { g -> g to TextureCacheImage.encode(g) } }
            val bandMax = bandPics.maxOf { it.second.size }
            val bandSlot = slack(bandMax)
            val bands = bandPics.mapIndexed { i, (g, enc) -> Picture("band $i", g, levelsOf(g), enc, next(bandSlot), bandSlot) }
            // one spare band slot, the largest band form's: a re-stage writes there and switches the item table to it
            val spareSlot = bandSlot
            val spareOff = next(spareSlot)
            // the item table: each row's band form (kind 1)
            val itemOffs = IntArray(n) { bands[it].off4 }
            val items4 = next(2 * n) / 4
            if (bands.any { it.off4 > 0xFFFF } || spareOff / 4 > 0xFFFF || items4 > 0xFFFF) throw LintError("Main's records pass the u16 off4 reach")
            // every slot a band form can be in is a record the programs know (a parameter names one of them)
            val records = listOfNotNull(strip.record, stripB?.record) + bands.map { it.record } + Motion.Source.Record(spareOff / 4, rowW, l.lens.h, spareSlot)

            // the programs, back to back after the item table
            val shift = listD - bandD
            val bandW = rowW + kotlin.math.abs(shift)
            val pans = ArrayList<Pan>()
            val progs = ArrayList<Pair<Int, ByteArray>>()
            val asked = widths
            // the strip each width's pans draw: the test widths strip 0 only; the whole list's both strips with [twoStrips]
            val plans = asked.map { minOf(it, mA) to minOf(it, mB) }.distinct().flatMap { kk ->
                val k = asked.first { minOf(it, mA) == kk.first && minOf(it, mB) == kk.second }
                if (k == WHOLE && stripB != null) listOf(kk to 0, kk to 1) else listOf(kk to 0) }
            // one period and one T for every program of the staging: the whole list's (decisions V)
            val (period, t) = timing((mA + mB) * l.rowH * rowW + l.lens.h * bandW, motion)
            val swap = swapOf(t)                                          // the leaving row's share of the steps
            for ((kk, sx) in plans) for (step in intArrayOf(1, -1)) {
                val (kA, kB) = kk
                val k = asked.first { minOf(it, mA) == kA && minOf(it, mB) == kB }
                val sr = if (sx == 0) strip.record else stripB!!.record
                val at4 = at / 4
                val prog = Motion.program(at4, records) {
                    // the rows above the band: the strip, its start y P0, panned one pitch
                    track(Rect(cx - listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH), Rect(cx + listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH), Motion.Backdrop.Level(0)) {
                        draw(sr, x = cx - listD, xR = cx + listD, y = 0, dy = -step * l.rowH, ease = Motion.Ease.easeOut, n = t,
                            prefs = listOf(Motion.Pref(0, Motion.Field.Y)))
                    }
                    // the rows below it: the same strip one pitch lower, its start y P3
                    track(Rect(cx - listD, l.lens.bottom, rowW, kB * l.rowH), Rect(cx + listD, l.lens.bottom, rowW, kB * l.rowH), Motion.Backdrop.Level(0)) {
                        draw(sr, x = cx - listD, xR = cx + listD, y = 0, dy = -step * l.rowH, ease = Motion.Ease.easeOut, n = t,
                            prefs = listOf(Motion.Pref(3, Motion.Field.Y)))
                    }
                    // the band, its area over both planes' x on each lens: the leaving row's form (P2) goes back and dims,
                    // then the entering row's (P1) comes forward and brightens
                    track(Rect(cx - maxOf(listD, bandD), l.lens.y, bandW, l.lens.h), Rect(cx + minOf(listD, bandD), l.lens.y, bandW, l.lens.h), Motion.Backdrop.Level(0)) {
                        draw(bands[0].record, x = cx - bandD, xR = cx + bandD, y = l.lens.y, dx = -shift, dxR = shift,
                            lvl0 = 15, lvl1 = MainPan.LVL_BACK, ease = Motion.Ease.easeOut, n = swap, prefs = listOf(Motion.Pref(2, Motion.Field.SRC)))
                        draw(bands[0].record, x = cx - listD, xR = cx + listD, y = l.lens.y, dx = shift, dxR = -shift,
                            lvl0 = MainPan.LVL_BACK, lvl1 = 15, ease = Motion.Ease.easeOut, n = t - swap, prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
                    }
                }
                next(prog.bytes.size)
                progs += at4 * 4 to prog.bytes
                pans += Pan(k, kA, kB, step, prog, listOf(
                    Rule(CfwModes.KIND_PITCH, l.lens.y + (step - sA) * l.rowH),            // P0: lens.y − (c + sA) × rowH, c = c' − step
                    Rule(CfwModes.KIND_ITEM, 0),                                            // P1: the entering row's band form
                    Rule(CfwModes.KIND_ITEM, -step),                                        // P2: the leaving row's
                    Rule(CfwModes.KIND_PITCH, l.lens.bottom + (step - 1 - sA) * l.rowH)), sx)   // P3: lens.bottom − (c + 1 + sA) × rowH
            }
            // the press: the band row at the cursor, one depth notch nearer, never nearer than the screen plane (`DESIGN.md`
            // §3.1's rule for the selection, which the press continues; at Global Depth 4 or less it cannot come forward)
            val pressD = maxOf(0, bandD - Geometry.X_STEP)
            val ds = bandD - pressD
            val pressAt4 = at / 4
            val pressProg = Motion.program(pressAt4, records) {
                track(Rect(cx - bandD, l.lens.y, rowW + ds, l.lens.h), Rect(cx + pressD, l.lens.y, rowW + ds, l.lens.h), Motion.Backdrop.Level(0)) {
                    draw(bands[0].record, x = cx - bandD, xR = cx + bandD, y = l.lens.y, dx = ds, dxR = -ds,
                        ease = Motion.Ease.easeOut, n = PRESS_T, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
                }
            }
            next(pressProg.bytes.size)
            progs += pressAt4 * 4 to pressProg.bytes
            val press = Press(pressD, pressProg, listOf(Rule(CfwModes.KIND_ITEM, 0), Rule(CfwModes.KIND_ARG, 0), Rule(CfwModes.KIND_ARG, 0), Rule(CfwModes.KIND_ARG, 0)))
            if (at > regionEnd) throw LintError("Main's staging ends at $at, past its region's end $regionEnd: ${at - regionStart} B " +
                "for a ${regionEnd - regionStart} B region (the strip ${strip.slot} B${if (stripB != null) " twice" else ""}, $n band forms ${bands.sumOf { it.slot }} B and a spare slot of $spareSlot B)")
            val progBytes = ByteArray(progs.last().let { (o2, b) -> o2 + b.size } - progs.first().first).also { out ->
                for ((o2, b) in progs) b.copyInto(out, o2 - progs.first().first)
            }
            val itemBytes = ByteArray(2 * n).also { b -> itemOffs.forEachIndexed { i, v -> put16(b, 2 * i, v) } }
            val blobs = listOfNotNull(Blob(strip.name, strip.off, pad(strip.encoded), strip.raw), stripB?.let { Blob(it.name, it.off, pad(it.encoded), it.raw) }) +
                // a band form's slack past its record is never read (a record ends at w × h pixels): not sent
                bands.map { Blob(it.name, it.off, pad(it.encoded), it.raw) } +
                Blob("items", items4 * 4, pad(itemBytes), null) +
                Blob("programs", progs.first().first, pad(progBytes), null)
            return MainStaging(layout, listD, bandD, n, cacheSize, period, t, regionStart, regionEnd, strip, stripB, bands.toMutableList(), items4, itemOffs,
                mutableMapOf(spareOff to spareSlot), pans, press, blobs, stripRow, motion)
        }

        /** Main's row [row] in its row form, [l.rowH] tall and the row's width (the rail aside), at y 0 — the pitch is
         *  even (`Layout`), so the painters' 2-px snap lands as at its place in the strip. */
        internal fun paintStripRow(view: WindowView.ListView, l: Layout, row: Int, relay: (Gray8, () -> Unit) -> Unit): Gray8 {
            val cx = l.content.x
            val rowW = l.content.w - Layout.RAIL_W
            val g = Gray8(cx + rowW, l.rowH)
            relay(g) { view.paintRow(g, row, Rect(cx, 0, rowW, l.rowH), false) }
            return crop(g, Rect(cx, 0, rowW, l.rowH))
        }

        /** Main's row [row] in the band, as `ContentKit.paintLensBand` paints it, cropped to the band (the rail aside). */
        internal fun paintBandForm(view: WindowView.ListView, kit: ContentKit, l: Layout, row: Int, relay: (Gray8, () -> Unit) -> Unit): Gray8 {
            val rowW = l.content.w - Layout.RAIL_W
            val g = Gray8(l.content.x + l.lens.w, l.lens.bottom)
            relay(g) { kit.paintLensBand(g, l, view, row) }
            return crop(g, Rect(l.lens.x, l.lens.y, rowW, l.lens.h))
        }

        private fun levelsOf(g: Gray8) = ByteArray(g.pix.size) { Pack.level(g.pix[it].toInt() and 0xFF).toByte() }

        private fun cost(n: Int) = if (n <= 15) 1 else if (n <= 255) 2 else 4

        /** The natural length of `levels[from, to)`'s token stream (`Rle`'s forms, no run crossing the ends). */
        internal fun rleSize(levels: ByteArray, from: Int, to: Int): Int {
            var size = 0
            var i = from
            while (i < to) {
                val c = levels[i]
                var n = 1
                while (i + n < to && levels[i + n] == c && n < 65535) n++
                size += cost(n); i += n
            }
            return size
        }

        /**
         * `levels[from, to)` as one RLE token stream (`FIRMWARE.md` §4's v2 tokens, `Rle.encodeLevels`' canonical forms:
         * a run of 1..15 in one byte, 16..255 in two, 256..65535 in four; no run crosses the ends) made exactly [size]
         * bytes long by splitting runs: a single pixel peeled off a run, the rest kept in its own class, adds exactly one
         * byte. Null when the natural stream is longer than [size] or the runs cannot give the bytes asked. Every token
         * stays canonical, so the decoder sees nothing it does not see in any record.
         */
        internal fun rlePadded(levels: ByteArray, from: Int, to: Int, size: Int): ByteArray? {
            var d = size - rleSize(levels, from, to)
            if (d < 0) return null
            val out = ByteArray(size)
            var o = 0
            fun put(c: Int, n: Int) {
                when {
                    n <= 15 -> out[o++] = ((n shl 4) or c).toByte()
                    n <= 255 -> { out[o++] = c.toByte(); out[o++] = n.toByte() }
                    else -> { out[o++] = c.toByte(); out[o++] = 0; out[o++] = (n and 0xFF).toByte(); out[o++] = ((n shr 8) and 0xFF).toByte() }
                }
            }
            var i = from
            while (i < to) {
                val c = levels[i].toInt() and 0x0F
                var n = 1
                while (i + n < to && levels[i + n] == levels[i] && n < 65535) n++
                i += n
                val floor = if (n <= 15) 1 else if (n <= 255) 16 else 256
                val peel = minOf(d, n - floor)
                d -= peel
                repeat(peel) { put(c, 1) }
                put(c, n - peel)
            }
            return if (d == 0 && o == size) out else null
        }

        private fun put16(b: ByteArray, at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }

        private fun crop(g: Gray8, r: Rect): Gray8 = Gray8(r.w, r.h).also { out ->
            for (y in 0 until r.h) System.arraycopy(g.pix, (r.y + y) * g.w + r.x, out.pix, y * r.w, r.w)
        }

        private fun pad(b: ByteArray) = if (b.size % 4 == 0) b else b.copyOf((b.size + 3) / 4 * 4)
    }
}

/** A v2 record of a picture's levels (`[w u16][h u16][RLE]`, `TextureCache.Image2`). */
internal object TextureCacheImage {
    fun encode(g: Gray8): ByteArray = wm.damage.core.wire.TextureCache.Image2(g.w, g.h,
        ByteArray(g.pix.size) { Pack.level(g.pix[it].toInt() and 0xFF).toByte() }).encode()
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

    private val NOTIFY = Regex("""^seq \S+ slot \S+ tag 0x([0-9a-f]{4}) (?:(drew its last step|closed while drawing|outcome \S+) ordinal \S+ drawn (\S+) folded (\S+) stepUs (\S+)|refused \((.*)\))""")

    fun parseNotify(detail: String): Notify? {
        val m = NOTIFY.find(detail) ?: return null
        val g = m.groupValues
        val outcome = when {
            g[2] == "drew its last step" -> "drew"
            g[2] == "closed while drawing" -> "closed"
            g[2].isNotEmpty() -> g[2]
            else -> "refused: ${g[6]}"
        }
        return Notify(g[1].toInt(16), outcome, g[3].toLongOrNull(), g[4].toLongOrNull(), g[5].toLongOrNull())
    }
}
