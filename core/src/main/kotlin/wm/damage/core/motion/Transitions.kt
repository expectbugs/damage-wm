package wm.damage.core.motion

import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.wire.CfwModes

/**
 * Build C3, stage DP2: the full-screen transitions of `MOTION.md` §7 as version-2 programs (`FIRMWARE.md` §7.2–§7.5,
 * §7.7, §7.11). Context for the reader: Adam's own glasses on his own firmware fork; display-rendering work. A
 * transition is two halves over one area — the leaving screen's picture moved, shrunk, sliced, spread or dimmed IN PLACE
 * by an OWN program (the out half, no LAND), then the arriving window's picture drawn from the shadow by an OWN + LAND
 * program started by PLAY-AFTER (the in half), whose last step copies the shadow in.
 *
 * An effect is a pair of PATHS — the out half's (identity → its far pose) and the in half's (its far pose → identity),
 * each with an ease. Played reversed in time (Close/Back; leaving the silent clock), the pair swaps: the out half runs
 * the in half's path backwards on the panel's picture, the in half the out half's backwards from the shadow, each ease
 * reversed in time (E'[j] = 4096 − E[n − j]: ease-out ↔ ease-in). What an in-place path cannot carry backwards is
 * dropped there and said here: a reseed every step and a stagger (both layer new only, §7.4).
 *
 * Every program is built through [Motion.program2] — the one gate, so nothing here is built below stage 5 — and so is
 * refused offline where the glasses would refuse it, the step budget at the PLAY and the PLAY-AFTER included.
 *
 * C3 stage DM-T: Encode and Matrix (GLYPHS, §7.6) are not paths. C3 MX2 (his word, 2026-11-21: "rain-like vertical lines
 * of characters … [that] wash away the screen we transition from and create the one we are transitioning to"): Matrix is
 * one fall of rain from the act — its heads come in over the held picture in the out half while the arriving frame lands
 * (R1, decisions BU), and in the in half its columns take the old screen away below their heads and leave the shadow
 * above their trails (reveal 1, in place, LAND);
 * Encode a dim with the leaving screen's letters turning to noise and gone over it, then the arriving screen with its
 * letters masked, turning to noise and revealed ([CellLists]). Both read the atlas's fonts, so the shell holds every other
 * cache write while one is in hand (the spec's D6), and Encode's cell lists live in the stage reserve.
 */
object Transitions {
    /** The tiers that play a transition: [word] what the journal says, [title] what the Settings rows say — the tier's
     *  name in `MOTION.md` §7 (C3 X2T item 7). */
    enum class Tier(val word: String, val title: String) { SELECT("Select", "Select"), BACK("Back", "Close / Back"), LOCK("Lock", "Lock Screen") }

    /** `MOTION.md` §7's effects that this build draws. [glyphs]: Encode and Matrix (C3 DM-T), drawn from the atlas. */
    enum class Effect(val id: String, val label: String) {
        DEPTH("depth", "Depth"), WARP("warp", "Warp"), FLIP("flip", "Flip"), FALL("fall", "Fall"),
        GLITCH("glitch", "Glitch"), EXPLODE("explode", "Explode"), FADE("fade", "Fade"), BUILD("build", "Build"),
        ENCODE("encode", "Encode"), MATRIX("matrix", "Matrix");
        val glyphs: Boolean get() = this == ENCODE || this == MATRIX
        companion object { fun of(id: String): Effect? = entries.firstOrNull { it.id == id } }
    }

    /** The effects each tier offers, in its rows' order — `MOTION.md` §7's (Close/Back: Select's, each reversed in time). */
    fun effectsOf(tier: Tier): List<Effect> = when (tier) {
        Tier.SELECT, Tier.BACK -> listOf(Effect.DEPTH, Effect.WARP, Effect.FLIP, Effect.FALL, Effect.GLITCH, Effect.ENCODE, Effect.MATRIX, Effect.EXPLODE)
        Tier.LOCK -> listOf(Effect.FADE, Effect.BUILD, Effect.MATRIX, Effect.ENCODE, Effect.GLITCH, Effect.EXPLODE)
    }

    /** The tier's effects as first stored — what a stored value that names nothing this build draws reads as. */
    fun defaultsOf(tier: Tier): List<Effect> = if (tier == Tier.LOCK) listOf(Effect.FADE) else listOf(Effect.DEPTH)

    /** Steps a half and the period (OS ticks) for a tier's `length` (ms) and `frames` (0 = Auto: about one frame per
     *  45 ms, the measured step of today's pans — `MOTION.md` §7's "450 ms at 10 frames"). Each half is half the length;
     *  an in-place or `new` step costs ≈ 1–3 ms (§7.12, I), so the period is set by the length and frames. */
    data class Timing(val steps: Int, val period: Int) {
        /** The half's nominal length in ms (T steps at P ticks; a tick is 1/1024 s). */
        val halfMs: Long get() = steps.toLong() * period * 1000L / 1024
    }

    fun timing(lengthMs: Int, frames: Int): Timing {
        val steps = stepsOf(lengthMs, frames)
        val p = unclamped(lengthMs, steps).coerceIn(CfwModes.MOTION_PERIODS.first, CfwModes.MOTION_PERIODS.last)
        return Timing(steps, p)
    }

    private fun stepsOf(lengthMs: Int, frames: Int): Int =
        maxOf(1, (if (frames > 0) frames else (Math.round(lengthMs / 45.0).toInt().coerceIn(4, 16) + 1) / 2 * 2) / 2)
    private fun unclamped(lengthMs: Int, steps: Int): Int = Math.round(lengthMs / 2.0 / steps * 1.024).toInt()

    /** C3 X2T item 7 (decisions AG (4), AM (3)): the ms a transition of [lengthMs] at [frames] really plays (both halves)
     *  when the period's clamp to 16..128 ticks changes it — 600 ms at 4 frames plays 500 — else null (a length only
     *  rounded to whole ticks is not said). Encode's and Matrix's "the length holds, frames give" is not a clamp. */
    fun clampedPlayMs(lengthMs: Int, frames: Int): Long? {
        val steps = stepsOf(lengthMs, frames)
        if (unclamped(lengthMs, steps) in CfwModes.MOTION_PERIODS) return null
        return 2 * timing(lengthMs, frames).halfMs
    }

    /**
     * The DM spec §3, "the length holds, frames give": Encode's and Matrix's step is longer than the period [timing] gives
     * at short lengths and many frames, so their period is the larger of that one and the step's fit, and their frames the
     * fewer of the tier's and what the length holds at that period (even, at least 2). The step (I, the spec's model on
     * §7.12's fast-path figure): 0.5 ms + 0.036 µs a pixel of the area + 0.058 ms a cell (Encode, the larger half) or 0.065
     * ms a lit rain cell (Matrix); the period ⌈⌈ms·1.024⌉·1.10⌉ ticks in 16..128. C3 X1MX2 item 3 (decisions BV): Warp and
     * Flip too, on the step his glasses measured ([WARP_STEP_MS_352], [FLIP_STEP_MS_352]: the larger half's, since both
     * halves share one period), carried to the area by the same form — 0.5 ms + a rate per pixel of the area, the rate
     * his 640 × 352 gives (modeled at the other heights). Every other effect: [timing].
     */
    fun timingFor(effect: Effect, lengthMs: Int, frames: Int, area: Rect, cells: Int = 0): Timing {
        val base = timing(lengthMs, frames)
        val px = area.w.toDouble() * area.h
        val ms = when (effect) {
            Effect.ENCODE -> 0.5 + 0.036e-3 * px + 0.058 * cells
            Effect.MATRIX -> 0.5 + 0.036e-3 * px + 0.065 * rainCols(area) * RAIN_TRAIL
            Effect.WARP -> 0.5 + (WARP_STEP_MS_352 - 0.5) * px / MEASURED_PX
            Effect.FLIP -> 0.5 + (FLIP_STEP_MS_352 - 0.5) * px / MEASURED_PX
            else -> return base
        }
        val fit = Math.ceil(Math.ceil(ms * 1.024) * 1.10).toInt().coerceIn(CfwModes.MOTION_PERIODS.first, CfwModes.MOTION_PERIODS.last)
        // C3 X1MX2 item 3's class: the frames give only to a longer period — at the setting's own period (rounded to whole
        // ticks, so 2·T·P may pass the length by under a tick a step) the setting's frames play, as for every other effect
        if (fit <= base.period) return base
        val f = (minOf(base.steps * 2, Math.floor(lengthMs * 1.024 / fit).toInt()) / 2 * 2).coerceAtLeast(2)
        return Timing(f / 2, fit)
    }

    /** C3 X1MX2 item 3: Warp's step on his glasses, measured (APK 0.83, 2026-11-21, his glasses at stage 5, height 352,
     *  5 steps a half at P 46): the largest step-drawing time RIGHT reported for its out half, 58.5–58.8 ms over 5 Selects,
     *  each folding one step; its in half 11.5–48.5 ms. */
    const val WARP_STEP_MS_352 = 58.8
    /** C3 X1MX2 item 3: Flip's step on his glasses, measured (as [WARP_STEP_MS_352]): its out half 60.3 ms over 2
     *  Selects, each folding one step; its in half 20.0–20.1 ms. */
    const val FLIP_STEP_MS_352 = 60.3
    /** The area those two were measured over: 640 × 352. */
    private const val MEASURED_PX = 640.0 * 352

    /** What Encode and Matrix draw from (C3 DM-T): [rain] the atlas face the rain draws (Matrix); the leaving and the
     *  arriving screens' cells (Encode), whose lists are laid out from [reserveAt4] in at most [reserveRoom] bytes. */
    class Glyphs(val rain: CellLists.Face? = null, val leaving: CellLists.Screen? = null, val arriving: CellLists.Screen? = null,
                 val reserveAt4: Int = 0, val reserveRoom: Int = 0) {
        /** The larger half's cells (Encode's step fit). */
        val cells: Int get() = maxOf((leaving as? CellLists.Screen.Cells)?.cells ?: 0, (arriving as? CellLists.Screen.Cells)?.cells ?: 0)
    }

    /** One cache write a transition makes besides its slot's: [at4] and its bytes. */
    class Write(val at4: Int, val bytes: ByteArray)

    // ------------------------------------------------------------------------------------------------- the paths
    enum class EaseKind { LINEAR, OUT, IN;
        /** Reversed in time: E'[j] = 4096 − E[n − j]. */
        val reversed: EaseKind get() = when (this) { LINEAR -> LINEAR; OUT -> IN; IN -> OUT }
    }

    /** One half's far pose: a uniform move ([dxL] / [dxR] per lens, [dy]), a shrink to [w] × [h] at the centre (0 on an
     *  axis: unscaled), a radial spread by the factor [spread] (1/4096) over a [gw] × [gh] grid, slices ([slices] of them,
     *  one column) torn sideways by up to [tear] px, or none of these (a level only); [level] the far pose's level. */
    data class Path(val dxL: Int = 0, val dxR: Int = 0, val dy: Int = 0, val w: Int = 0, val h: Int = 0,
                    val spread: Int = 0, val gw: Int = 8, val gh: Int = 6, val slices: Int = 0, val tear: Int = 0,
                    val level: Int = 15, val ease: EaseKind = EaseKind.LINEAR,
                    /** Layer new only: the random model's hash takes the step (opt bit 6) / the pieces' stagger. */
                    val reseed: Boolean = false, val stagger: Boolean = false) {
        val scales: Boolean get() = w != 0 || h != 0
    }

    /** The out half's path and the in half's, for a forward play ([Effect]'s own direction) over [area]. */
    private fun paths(e: Effect, area: Rect, depth: Int): Pair<Path, Path> {
        val d = DEPTH_PX.coerceAtMost(maxOf(4, 16 - depth))      // further back by up to 8 px, inside the 16-px budget
        return when (e) {
            // the screen recedes and fades; the next comes forward out of the dark (K1 §5's Depth, both halves)
            Effect.DEPTH -> Path(dxL = -d, dxR = d, level = 0, ease = EaseKind.OUT) to Path(dxL = -d, dxR = d, level = 0, ease = EaseKind.OUT)
            // shrinks into the distance, dimming; the next grows out of it (C3-design §2.3)
            Effect.WARP -> Path(w = maxOf(1, area.w / 8), h = maxOf(1, area.h / 8), level = 4, ease = EaseKind.IN) to
                Path(w = maxOf(1, area.w / 8), h = maxOf(1, area.h / 8), level = 4, ease = EaseKind.OUT)
            // a card turned edge-on, then the other face turned back
            Effect.FLIP -> Path(w = 1, ease = EaseKind.IN) to Path(w = 1, ease = EaseKind.OUT)
            // falls out of the bottom under gravity; the next falls in from above and settles
            Effect.FALL -> Path(dy = area.h, ease = EaseKind.IN) to Path(dy = -area.h, ease = EaseKind.OUT)
            // slices torn sideways, more each step; the next arrives torn, the tears re-dealt each step and closing
            Effect.GLITCH -> Path(slices = SLICES, tear = TEAR_PX, level = 5) to Path(slices = SLICES, tear = TEAR_PX, level = 5, reseed = true)
            // pieces fly outward; the next assembles from them
            Effect.EXPLODE -> Path(spread = SPREAD, level = 0, ease = EaseKind.OUT) to Path(spread = SPREAD, level = 0, ease = EaseKind.OUT)
            Effect.FADE -> Path(level = 0) to Path(level = 0)
            // its out half Explode's; its in half the pieces arriving one after another
            Effect.BUILD -> Path(spread = SPREAD, level = 0, ease = EaseKind.OUT) to Path(spread = SPREAD, level = 0, stagger = true)
            Effect.ENCODE, Effect.MATRIX -> throw LintError("${e.label} is not a path (C3 DM-T: [build] draws it from the atlas)")
        }
    }

    // --------------------------------------------------------------------------------------------- the programs
    /** A transition's two programs and the records that hold them, laid out from [at4] (4-byte units) in one blob. */
    class Built internal constructor(val effect: Effect, val reversed: Boolean, val area: Rect, val timing: Timing,
                                     val out: MotionProgram, val inn: MotionProgram, val at4: Int, val blob: ByteArray,
                                     /** Encode: the out half's cell lists (written with the records) and the in half's (above the
                                      *  out lists: with the records when the three fit one compressed message, else after the
                                      *  out PLAY, where they meet no range the out half reads). */
                                     val outLists: Write? = null, val inLists: Write? = null) {
        /** The out half's and the in half's C (§7.12, u) and the sum the PLAY-AFTER is judged by (the in half waits on
         *  the out half: the larger). C3 X3 (decisions CD (a)): each worked out once a plan, at its first read, and kept —
         *  the share check, the PLAY, the PLAY-AFTER and the `owed:` line read these. */
        val outCost: Long by lazy { out.cost() }
        val inCost: Long by lazy { inn.cost() }
        fun describe(): String = "${effect.label}${if (reversed) " reversed" else ""} over $area, ${timing.steps} steps a half at P ${timing.period}, " +
            "records ${blob.size} B at off4 $at4 (out ${out.bytes.size} B, in ${inn.bytes.size} B), C out ${outCost} / in ${inCost} u" +
            (outLists?.let { o -> ", cell lists ${o.bytes.size} B at off4 ${o.at4} (out) and ${inLists?.bytes?.size} B at off4 ${inLists?.at4} (in)" } ?: "") +
            (if (effect.glyphs) ", glyph walk ${maxOf(out.walkBytes(), inn.walkBytes())} B" else "")
        /** C3 X2T item 3: the bytes Encode's lists take in the stage reserve, both halves' (null: none — every other
         *  effect, or an Encode whose screens hold no cell). */
        val listsRange: IntRange? get() = listOfNotNull(outLists, inLists).takeIf { it.isNotEmpty() }
            ?.let { ws -> ws.minOf { it.at4 * 4 } until ws.maxOf { it.at4 * 4 + it.bytes.size } }
        /** The DM spec §3: each half at most [SHARE_MAX] of the step budget's cap, alone (the rest is for what else draws). */
        val withinShare: Boolean get() = maxOf(outCost, inCost) * 100 <= Motion.BUDGET_CAP * SHARE_MAX_PERCENT
    }

    /**
     * The out and in programs of [effect] over [area] (both lenses), [reversed] in time or not, [depth] the content's
     * disparity (the Depth effect stays inside the 16-px budget), laid out from [at4] in at most [room] bytes: the easing
     * tables first, then the out program, then the in program, 4-byte aligned. [seed] seeds the random slices.
     * Refused ([LintError], its message naming the half) where the glasses would refuse a program, or when the records
     * do not fit [room]. No out half is ever replaced: every effect's out program is taken at every height, length and
     * frame count (pinned offline), so §7.11's one-step freeze is not built. C3 X1MX2 item 2 (decisions CB (2)): [plane]
     * is the transition's one plane — the nearer of the leaving and the arriving screen's content planes, the shell's —
     * and Matrix's rain grid sits at it in both halves; [depth] stays Global `Depth`, which only the Depth effect reads.
     * [start]: the rain's out-half travel (C3 X1MX2 item 1; the built one unless a comparison names another).
     */
    fun build(effects: Motion.Effects, effect: Effect, reversed: Boolean, area: Rect, timing: Timing, at4: Int, room: Int,
              depth: Int = 8, seed: Int = 0x5EED, glyphs: Glyphs? = null, plane: Int = depth, start: RainStart = RAIN_START): Built {
        if (effect == Effect.MATRIX) return matrix(effects, reversed, area, timing, at4, room, seed, glyphs, plane, start)
        if (effect == Effect.ENCODE) return encode(effects, reversed, area, timing, at4, room, seed,
            glyphs ?: throw LintError("Encode: no cells were recorded for it"))
        val (o, i) = paths(effect, area, depth)
        // forward: out runs O on the panel, in runs I from the shadow; reversed in time: out runs I backwards on the
        // panel (identity → I's far pose, its ease reversed), in runs O backwards from the shadow
        val outPath = if (!reversed) o else i.copy(ease = i.ease.reversed, reseed = false, stagger = false)
        val inPath = if (!reversed) i else o.copy(ease = o.ease.reversed)
        val n = timing.steps
        var off = at4
        val outTable = tableFor(outPath.ease, n, off)?.also { off += words(it.record.size) }
        val inTable = tableFor(inPath.ease, if (inPath.stagger) n - stagger(n) else n, off)
        if (inPath.stagger && inTable != null) throw LintError("a staggered in half takes a built-in ease, not a table")
        inTable?.let { off += words(it.record.size) }
        val out = half("out") { Motion.program2(off, effects, own = true) { inPlace(this, outPath, area, n, outTable ?: easeOf(outPath.ease), seed) } }
        off += words(out.bytes.size)
        val inn = half("in") { Motion.program2(off, effects, own = true, land = true) { fromShadow(this, inPath, area, n, inTable ?: easeOf(inPath.ease), seed) } }
        off += words(inn.bytes.size)
        val size = (off - at4) * 4
        if (size > room) throw LintError("the transition's records are $size B, past the $room B their slot holds")
        val blob = ByteArray(size)
        for ((o4, b) in out.records + inn.records) b.copyInto(blob, (o4 - at4) * 4)
        return Built(effect, reversed, area, timing, out, inn, at4, blob)
    }

    /**
     * Matrix's rain over an area for one face at one disparity (C3 MX2): cells [cw] × [ch] — a column 1.5 times the face's
     * widest letter (at least [RAIN_CW_MIN]) so its characters stand in separate vertical lines, a row the face's box
     * height (at least [RAIN_CH_MIN]) — [rows] = R, a trail of a third of them, the columns' start offsets spread over R
     * rows, [cols] columns from [xL] on the left lens and [xR] on the right: −d and +d (the content plane's offsets, so a
     * column's cut between the new and the old picture falls on the same content in both eyes) moved left by whole columns
     * until both grids start at or left of the area, and enough of them to reach its right edge on both.
     */
    class RainGrid(val cw: Int, val ch: Int, val rows: Int, val trail: Int, val spread: Int, val cols: Int, val xL: Int, val xR: Int,
                   /** The area's width (C3 X1MX2: which columns lie whole inside it on both lenses). */
                   val areaW: Int) {
        val perLens: Boolean get() = xL != xR
        /** §7.6: the columns' start offsets o_c = H2(seed, c) mod (spread + 1) for the rain record's hash seed [s]. */
        fun offsets(s: Int): IntArray = IntArray(cols) { c -> wm.damage.core.sim.EffectsSim.umod(wm.damage.core.sim.EffectsSim.h2(s, c), spread + 1) }
        /** Column [c]'s cells lie whole inside the area on both lenses: a head that enters a row there changes both pictures. */
        fun wholeOnBoth(c: Int): Boolean = whole(xL, c) && whole(xR, c)
        /** Column [c]'s cells lie whole inside the area on the lens whose grid starts at [x]. */
        fun whole(x: Int, c: Int): Boolean = x + c * cw >= 0 && x + (c + 1) * cw <= areaW
    }

    fun rainGrid(area: Rect, face: CellLists.Face, depth: Int): RainGrid {
        val cw = maxOf(RAIN_CW_MIN, (3 * face.widestAlpha + 1) / 2).coerceAtMost(RAIN_CELL_MAX)
        val ch = maxOf(RAIN_CH_MIN, face.lineH).coerceAtMost(RAIN_CELL_MAX)
        val rows = (area.h + ch - 1) / ch
        val d = kotlin.math.abs(depth)
        val k = (d + cw - 1) / cw
        val cols = ((area.w + d + k * cw + cw - 1) / cw).coerceIn(1, 128)
        return RainGrid(cw, ch, rows, ((rows + 2) / 3).coerceIn(4, 32), rows.coerceAtMost(255), cols, -depth - k * cw, depth - k * cw, area.w)
    }

    /**
     * Matrix's travel tables over [n] steps a half for [grid] and the rain's hash seed [s] (C3 MX2 R1, decisions BU): E[1..n]
     * of the out half's drops and of the in half's rain. The out half's heads come in over the old screen no further than
     * one trail by step n − 1, so no head passes its trail (no cell above a trail: the out half has no new picture to leave
     * there); its last value is 4096 (§5.4's E[n]), where the rain lights nothing: its step n repeats step n − 1. How they
     * come in is [start]'s ([RAIN_START], C3 X1MX2 item 1). The in half carries the same heads on from e1, the out half's
     * last drawn value, at its own constant step, so its first value lies one of its steps past it. [seed] is the hash seed
     * both halves' rain records take: [s], or for B and C the first after it whose columns give every drawn out step a
     * picture of its own (a narrow grid whose earliest column sits at an edge: 1 seed in 65,536 at 288 with 18-px columns
     * at depth 8, modeled — `TransitionsTest`). Null for one step a half (no step n − 1: the hold, then the rain).
     */
    class RainTravel(val out: IntArray, val inn: IntArray, val e1: Int, val seed: Int)

    fun rainTravel(grid: RainGrid, n: Int, s: Int, start: RainStart = RAIN_START): RainTravel? {
        if (n < 2) return null
        val shape: ((Double) -> Double)? = when (start) {
            RainStart.AS_BUILT -> null
            RainStart.GATHERING -> { u -> RAIN_LIFT + (1 - RAIN_LIFT) * u * Math.sqrt(u) }
            RainStart.SETTLING -> { u -> (1 - u).let { v -> RAIN_LIFT + (1 - RAIN_LIFT) * (1 - v * Math.sqrt(v)) } }
        }
        var seed = s
        val out = if (shape == null) rainOutAsBuilt(grid, n)
            else (0 until RAIN_SEED_TRIES).firstNotNullOfOrNull { i -> rainOutShaped(grid, n, (s + i) and 0xFFFF, shape, strict = true)?.also { seed = (s + i) and 0xFFFF } }
                ?: rainOutShaped(grid, n, s, shape, strict = false)!!
        val e1 = out[n - 2]
        return RainTravel(out, IntArray(n) { j -> e1 + ((4096 - e1) * (j + 1)) / n }, e1, seed)
    }

    /**
     * C3 X1MX2 item 1 (decisions CB (1): "the start must answer the tap"): the out half's travel, three candidates for the
     * lead's eye; [RAIN_START] is the one built. [AS_BUILT] (A, MX2 R1): from rest, gathering speed, e1·(t/(n − 1))² — at 8
     * steps its first three frames are one picture and on some seeds its first step lights nothing. [GATHERING] (B) and
     * [SETTLING] (C) read the act's own start offsets ([rainOutShaped]): their first step lights the earliest column whole
     * on both lenses on every seed, about a quarter of the way in, and every drawn step changes the picture; B then
     * gathers speed into the hold at the join (a stop), C slows into it (the drops settle, then fall).
     */
    enum class RainStart { AS_BUILT, GATHERING, SETTLING }
    val RAIN_START = RainStart.SETTLING
    /** B's and C's first step: this share of the way from the first cell to the last value no head passes its trail at. */
    private const val RAIN_LIFT = 0.25
    /** B and C: the seeds tried after the act's own for one whose drawn out steps each show a picture of their own. */
    private const val RAIN_SEED_TRIES = 16

    /** A (MX2 R1): E[t] = ⌊e1·t² / (n − 1)²⌋ to e1 = ⌊(trail − 1)·4096 / (R + trail)⌋, then 4096. */
    private fun rainOutAsBuilt(grid: RainGrid, n: Int): IntArray {
        val e1 = ((grid.trail - 1) * 4096) / (grid.rows + grid.trail)
        return IntArray(n) { j -> if (j == n - 1) 4096 else (e1 * (j + 1) * (j + 1)) / ((n - 1) * (n - 1)) }
    }

    /**
     * B and C: the out half's E[1..n − 1] between eLo — where the earliest column whole on both lenses enters its first row
     * (§7.6: head_c ≥ j from e = ⌈(j + o_c)·4096 / (R + o_c + trail)⌉) — and eHi, the last value at which no column's head
     * passes its trail (its least offset's head at trail − 1), at eLo + (eHi − eLo)·[shape](u), u = (t − 1)/(n − 2), the
     * shape 0.25 at u = 0 and 1 at u = 1; then E[n] = 4096. The picture changes only where a head enters a row, so each value
     * is moved inside the run between two such changes the fewest steps that give every drawn step a change of its own (a
     * forward pass, then a backward one inside eHi). On every seed step 1 lights at least one cell on both lenses. Where the
     * columns whole on both lenses enter fewer rows below eHi than there are drawn steps, [strict] answers null (the caller
     * tries the next seed); else those steps share a picture.
     */
    private fun rainOutShaped(grid: RainGrid, n: Int, s: Int, shape: (Double) -> Double, strict: Boolean): IntArray? {
        val o = grid.offsets(s)
        val r = grid.rows; val tr = grid.trail
        val oa = o.min()
        val eHi = ((tr + oa) * 4096 + (r + oa + tr) - 1) / (r + oa + tr) - 1
        val found = IntArray(grid.cols * minOf(tr, r))
        var k = 0
        for (c in 0 until grid.cols) if (grid.wholeOnBoth(c)) for (j in 0 until minOf(tr, r)) {
            val e = ((j + o[c]) * 4096 + (r + o[c] + tr) - 1) / (r + o[c] + tr)
            if (e <= eHi) found[k++] = e
        }
        if (k == 0) return if (strict) null else IntArray(n) { j -> if (j == n - 1) 4096 else eHi }
        found.sort(0, k)
        var m = 0
        for (i in 0 until k) if (m == 0 || found[i] != found[m - 1]) found[m++] = found[i]
        if (strict && m < n - 1) return null
        val at = found.copyOf(m)
        val eLo = at[0]
        val target = IntArray(n - 1) { t -> eLo + ((eHi - eLo) * shape(if (n <= 2) 1.0 else t.toDouble() / (n - 2))).toInt() }
        // the last change at or below each target, then each step its own change, inside the m there are
        val idx = IntArray(n - 1) { t -> at.count { it <= target[t] } - 1 }
        var prev = -1
        for (t in idx.indices) { idx[t] = maxOf(idx[t], prev + 1); prev = idx[t] }
        var next = m
        for (t in idx.indices.reversed()) { idx[t] = minOf(idx[t], next - 1); next = idx[t] }
        return IntArray(n) { j ->
            if (j == n - 1) 4096
            else idx[j].coerceAtLeast(0).let { i -> target[j].coerceIn(at[i], if (i + 1 < m) at[i + 1] - 1 else eHi) }
        }
    }

    /**
     * Matrix (C3 MX2; his word of 2026-11-21, decisions BS; R1, decisions BU). The out half holds the panel's picture —
     * PIECES layer old, uniform, no move, level 15 → 15 (§7.2: it moves no pixel; §7.4: G is the identity) — and over it
     * the rain's heads come in from the top edge ([rainTravel]: reveal 0, nothing above any trail, so the old picture stays
     * but for the lit cells), while the arriving frame lands behind it. A rain segment ends past the bottom at its last
     * step (its heads reach R + trail at E = 4096, §7.6), so the out half's step T paints nothing and repeats step T − 1:
     * the one step at which it does not repaint what the step before painted (§7.11's phone rule, bent there and only
     * there, decisions BU: a lens that folds step T − 1 shows step T − 2's shorter trails for one period; the in half's
     * first step covers every cell either drew). The in half is the same rain carried on — the same seed, grid and
     * characters — with reveal 1, in place: below each head the old picture, above each trail the shadow; LAND at step T,
     * where the rain would paint the shadow everywhere. Each column its own start and a constant speed in each half, the
     * late starters faster; fill 0 behind each character, the head at 15 fading to [RAIN_TAIL_LVL], each cell keeping one
     * character ([RAIN_FLICKER]) through both halves. RAIN falls one way (the DM spec's S1, ruled): Back's records are
     * Select's.
     */
    private fun matrix(effects: Motion.Effects, reversed: Boolean, area: Rect, timing: Timing, at4: Int, room: Int, seed: Int, g: Glyphs?,
                       plane: Int, start: RainStart): Built {
        val face = g?.rain ?: throw LintError("Matrix: no face the glasses hold has a letter or a digit")
        if (face.alphabet.isEmpty()) throw LintError("Matrix: the face ${face.spec} holds no letter or digit")
        val n = timing.steps
        val grid = rainGrid(area, face, plane)
        val travel = rainTravel(grid, n, (seed + 1) and 0xFFFF, start)
        val s = travel?.seed ?: ((seed + 1) and 0xFFFF)
        var off = at4
        val alphabet = Motion.Alphabet(off, face.alphabet).also { off += words(it.bytes.size) }
        fun record(reveal: Int) = Motion.Rain(off, grid.cols, grid.trail, grid.cw, grid.ch, face.font, alphabet, headLvl = 15, tailLvl = RAIN_TAIL_LVL,
            reveal = reveal, fillLvl = 0, spread = grid.spread, flicker = RAIN_FLICKER).also { off += 4 }
        val drops = travel?.let { record(0) }
        val rain = record(1)
        fun table(e: IntArray) = Motion.Ease.Table(off, e).also { off += words(it.record.size) }
        val outEase = travel?.let { table(it.out) }
        val inEase: Motion.Ease = travel?.let { table(it.inn) } ?: Motion.Ease.Linear
        val out = half("out") { Motion.program2(off, effects, own = true) {
            track(area, Motion.Backdrop.None) { pieces(Motion.LAYER_OLD, n, Motion.Ease.Linear, lvl0 = 15, lvl1 = 15) }
            if (drops != null && outEase != null) {
                if (grid.perLens) track(area, area, Motion.Backdrop.None) { rain(drops, n, outEase, x = grid.xL, xR = grid.xR, seed = s) }
                else track(area, Motion.Backdrop.None) { rain(drops, n, outEase, x = grid.xL, seed = s) }
            }
        } }
        off += words(out.bytes.size)
        val inn = half("in") { Motion.program2(off, effects, own = true, land = true) {
            if (grid.perLens) track(area, area, Motion.Backdrop.None) { rain(rain, n, inEase, x = grid.xL, xR = grid.xR, seed = s) }
            else track(area, Motion.Backdrop.None) { rain(rain, n, inEase, x = grid.xL, seed = s) }
        } }
        off += words(inn.bytes.size)
        return Built(Effect.MATRIX, reversed, area, timing, out, inn, at4, blobOf(at4, off, room, out, inn))
    }

    /**
     * Encode (C3 DM-T; the DM spec §3). Out half: PIECES uniform layer old over the area — no move, its level 15 → 0 (the
     * dim; in place, the first track) — then one CELLS track per list of the leaving screen: each letter shown, then noise,
     * then gone (its box filled with 0 over the dim). In half: the shadow over the area (backdrop `new`), then one CELLS
     * track per list of the arriving screen: each letter masked, noise, then revealed (32: the box no longer filled), LAND.
     * Reversed in time (Back; leaving the clock) the pair swaps as every effect's does: the out half runs the in half
     * backwards — the leaving picture whole, its letters turning to noise and masked (the dim's level held at 15) — and the
     * in half the out half backwards — the shadow brought up from black (DRAW `new`, level 0 → 15) with its letters
     * masked, noise, then revealed. Each list's track is offset by its plane's disparity (−d left, +d right). The out lists
     * go in the stage reserve with the records' write; the in lists above them — in that write too when the three fit one
     * compressed message, else after the out PLAY (the shell's choice, C3 X2T item 2).
     */
    private fun encode(effects: Motion.Effects, reversed: Boolean, area: Rect, timing: Timing, at4: Int, room: Int, seed: Int, g: Glyphs): Built {
        val leaving = g.leaving as? CellLists.Screen.Cells ?: throw LintError("Encode: the leaving screen " + ((g.leaving as? CellLists.Screen.NoFit)?.why ?: "was not recorded"))
        val arriving = g.arriving as? CellLists.Screen.Cells ?: throw LintError("Encode: the arriving screen " + ((g.arriving as? CellLists.Screen.NoFit)?.why ?: "was not recorded"))
        val n = timing.steps
        var why = ""
        val outL = CellLists.lay(leaving, g.reserveAt4, g.reserveRoom, n, out = true) { why = it } ?: throw LintError("Encode: the leaving screen's cells do not fit: $why")
        val inL = CellLists.lay(arriving, outL.end4, g.reserveRoom - outL.blob.size, n, out = false) { why = it }
            ?: throw LintError("Encode: the arriving screen's cells do not fit beside the leaving screen's: $why")
        var off = at4
        val out = half("out") { Motion.program2(off, effects, own = true) {
            track(area, Motion.Backdrop.None) { pieces(Motion.LAYER_OLD, n, Motion.Ease.Linear, lvl0 = 15, lvl1 = if (reversed) 15 else 0) }
            cellTracks(this, outL, area, n, seed)
        } }
        off += words(out.bytes.size)
        val inn = half("in") { Motion.program2(off, effects, own = true, land = true) {
            if (!reversed) track(area, Motion.Backdrop.New) { wait(n) }
            else track(area, Motion.Backdrop.Level(0)) { draw(Motion.Source.New, area.x, area.y, lvl0 = 0, lvl1 = 15, ease = Motion.Ease.Linear, n = n) }
            cellTracks(this, inL, area, n, seed + 1)
        } }
        off += words(inn.bytes.size)
        // every record the programs name is in the slot's blob or in one of the two lists' blobs, byte for byte
        val lists = listOf(outL, inL)
        for ((o4, b) in out.records + inn.records) if (o4 !in at4 until off) {
            val l = lists.firstOrNull { o4 in it.at4 until it.end4 } ?: throw LintError("Encode: a record at off4 $o4 is in neither the slot nor the lists")
            if (!b.contentEquals(l.blob.copyOfRange((o4 - l.at4) * 4, (o4 - l.at4) * 4 + b.size))) throw LintError("Encode: the record at off4 $o4 differs from its list's bytes")
        }
        return Built(Effect.ENCODE, reversed, area, timing, out, inn, at4, blobOf(at4, off, room, out, inn),
            Write(outL.at4, outL.blob).takeIf { outL.blob.isNotEmpty() }, Write(inL.at4, inL.blob).takeIf { inL.blob.isNotEmpty() })
    }

    /** One CELLS track per list of [l] over [area], offset by its disparity on each lens (a pair of one rect when it has one). */
    private fun cellTracks(b: Motion.ProgramBuilder, l: CellLists.Laid, area: Rect, n: Int, seed: Int) {
        val table = l.table ?: return
        l.disparities.forEachIndexed { i, d ->
            if (d == 0) b.track(area, Motion.Backdrop.None) { cells(table, i, n, seed = seed and 0xFFFF) }
            else b.track(area, area, Motion.Backdrop.None) { cells(table, i, n, x = -d, xR = d, seed = seed and 0xFFFF) }
        }
    }

    /** The slot's bytes from [at4] to [end4]: every record of [out] and [inn] there (a record elsewhere is a list's). */
    private fun blobOf(at4: Int, end4: Int, room: Int, out: MotionProgram, inn: MotionProgram): ByteArray {
        val size = (end4 - at4) * 4
        if (size > room) throw LintError("the transition's records are $size B, past the $room B their slot holds")
        val blob = ByteArray(size)
        for ((o4, b) in out.records + inn.records) if (o4 in at4 until end4) b.copyInto(blob, (o4 - at4) * 4)
        return blob
    }

    /** The step fit's count of Matrix's lit cells over [area]: [rainCols] × [RAIN_TRAIL] — §7.12's designed 12-px columns
     *  and trail of 8, which bounds what [rainGrid] lights at every face and depth (C3 MX2: columns ≥ [RAIN_CW_MIN] px,
     *  rows ≥ [RAIN_CH_MIN] px, so at most ⌈(W + 16 + 28)/14⌉ columns and a trail of ⌈⌈H/20⌉/3⌉ ≤ 8), so the period rule
     *  is DM-T's. */
    fun rainCols(area: Rect): Int = ((area.w + RAIN_CW - 1) / RAIN_CW).coerceIn(1, 128)

    /** [build]'s refusal of one half, said with its name ("the out half: …"), so the journal says which half it was. */
    private fun <T> half(name: String, f: () -> T): T =
        try { f() } catch (e: LintError) { throw LintError("the $name half: ${e.message}") }

    /** The out half: one in-place track over [area] (backdrop none, its first and only track, §7.2). */
    private fun inPlace(b: Motion.ProgramBuilder, p: Path, area: Rect, n: Int, ease: Motion.Ease, seed: Int) {
        val perLens = p.dxL != p.dxR
        val build: Motion.TrackBuilder.() -> Unit = {
            when {
                p.scales -> shrink(n, ease, w0 = if (p.w != 0) area.w else 0, w1 = p.w, h0 = if (p.h != 0) area.h else 0, h1 = p.h,
                    anchor = 1, hAnchor = 1, lvl0 = 15, lvl1 = p.level)
                p.spread != 0 -> pieces(Motion.LAYER_OLD, n, ease, gw = p.gw, gh = p.gh, model = Motion.RADIAL,
                    x = area.x + area.w / 2, y = area.y + area.h / 2, lvl0 = 15, lvl1 = p.level, f0 = 0, f1 = p.spread)
                p.slices != 0 -> pieces(Motion.LAYER_OLD, n, ease, gw = 1, gh = p.slices, model = Motion.RANDOM, dx = p.tear,
                    lvl0 = 15, lvl1 = p.level, seed = seed, f0 = 0, f1 = 4096)
                else -> if (perLens) pieces(Motion.LAYER_OLD, n, ease, dx = p.dxL, dxR = p.dxR, dy = p.dy, lvl0 = 15, lvl1 = p.level)
                        else pieces(Motion.LAYER_OLD, n, ease, dx = p.dxL, dy = p.dy, lvl0 = 15, lvl1 = p.level)
            }
        }
        if (perLens) b.track(area, area, Motion.Backdrop.None, build) else b.track(area, Motion.Backdrop.None, build)
    }

    /** The in half: one track over [area], backdrop level 0, drawing the shadow from the far pose to rest. */
    private fun fromShadow(b: Motion.ProgramBuilder, p: Path, area: Rect, n: Int, ease: Motion.Ease, seed: Int) {
        val perLens = p.dxL != p.dxR
        val build: Motion.TrackBuilder.() -> Unit = {
            when {
                p.scales -> draw(Motion.Source.New, area.x, area.y, lvl0 = p.level, lvl1 = 15, ease = ease, n = n,
                    w0 = if (p.w != 0) p.w else 0, w1 = if (p.w != 0) area.w else 0, h0 = if (p.h != 0) p.h else 0, h1 = if (p.h != 0) area.h else 0,
                    anchor = 1, hAnchor = 1)
                p.spread != 0 -> pieces(Motion.LAYER_NEW, n, ease, gw = p.gw, gh = p.gh, model = Motion.RADIAL,
                    stagger = if (p.stagger) stagger(n) else 0, x = area.x + area.w / 2, y = area.y + area.h / 2,
                    lvl0 = p.level, lvl1 = 15, f0 = p.spread, f1 = 0)
                p.slices != 0 -> pieces(Motion.LAYER_NEW, n, ease, gw = 1, gh = p.slices, model = Motion.RANDOM, dx = p.tear,
                    lvl0 = p.level, lvl1 = 15, seed = seed, f0 = 4096, f1 = 0, reseed = p.reseed)
                perLens -> draw(Motion.Source.New, area.x + p.dxL, area.y + p.dy, dx = -p.dxL, dy = -p.dy, xR = area.x + p.dxR, dxR = -p.dxR,
                    lvl0 = p.level, lvl1 = 15, ease = ease, n = n)
                else -> draw(Motion.Source.New, area.x + p.dxL, area.y + p.dy, dx = -p.dxL, dy = -p.dy, lvl0 = p.level, lvl1 = 15, ease = ease, n = n)
            }
        }
        if (perLens) b.track(area, area, Motion.Backdrop.Level(0), build) else b.track(area, Motion.Backdrop.Level(0), build)
    }

    private fun easeOf(k: EaseKind): Motion.Ease = when (k) { EaseKind.LINEAR -> Motion.Ease.Linear; EaseKind.OUT -> Motion.Ease.EaseOut; EaseKind.IN -> Motion.Ease.Linear }

    /** Ease-in is ease-out reversed in time — a table (`n u8 · 0 · E[1..n]`): E[j] = ⌊4096·j²/n²⌋, never falling. */
    private fun tableFor(k: EaseKind, n: Int, at4: Int): Motion.Ease.Table? =
        if (k != EaseKind.IN) null else Motion.Ease.Table(at4, IntArray(n) { j -> ((4096L * (j + 1) * (j + 1)) / (n.toLong() * n)).toInt() })

    /** A staggered in half delays each piece by up to about a third of its steps (n' = n − stagger ≥ 1, §7.4). */
    private fun stagger(n: Int): Int = (n / 3).coerceAtMost(n - 1)

    private fun words(bytes: Int) = (bytes + 3) / 4

    /** Further back than the content plane by this much, per lens (Depth). */
    private const val DEPTH_PX = 8
    private const val SLICES = 24
    private const val TEAR_PX = 48
    /** Explode's spread: an edge piece flies 1.5 times its distance from the centre further out. */
    private const val SPREAD = 6144
    /** §7.12's designed rain, 12-px columns and a trail of 8: what the step fit counts ([rainCols]). */
    const val RAIN_CW = 12
    const val RAIN_TRAIL = 8
    /** C3 MX2: Matrix's cells are at least this wide and tall (and at most [RAIN_CELL_MAX]). */
    const val RAIN_CW_MIN = 14
    const val RAIN_CH_MIN = 20
    const val RAIN_CELL_MAX = 64
    /** C3 MX2: a trail's top character's level (its head's is 15), and a flicker past any half's steps (each cell keeps
     *  one character while the light runs down its column). */
    const val RAIN_TAIL_LVL = 3
    const val RAIN_FLICKER = 255
    /** The DM spec §3: an Encode or Matrix half takes at most this share of the step budget's cap. */
    const val SHARE_MAX_PERCENT = 80L
}
