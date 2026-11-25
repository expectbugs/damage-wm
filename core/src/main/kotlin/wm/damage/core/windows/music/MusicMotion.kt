package wm.damage.core.windows.music

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.gfx.Pack
import wm.damage.core.motion.Motion
import wm.damage.core.motion.MotionProgram
import wm.damage.core.shell.LaneAt
import wm.damage.core.shell.LanePlan
import wm.damage.core.shell.LaneSpec
import wm.damage.core.shell.WindowLanes
import wm.damage.core.wire.TextureCache

/**
 * Music's motion update, stage L (`private/build085/MM-lean-spec.md` §4.3–§4.5; his answer 2): the progress bar as a
 * continuous fill the glasses grow a pixel at a time on their own clock, and THE one place the song's place a step shows
 * is worked out.
 *
 * **The equation** ([heardAt]): the step visible at phone time t shows the song as heard then — `heard(t + dDisp)`, with
 * `heard(x) = position(x) − offset(output)` (`LyricsSync.heardPos`, the per-output `Lyrics offset` row, the one timing
 * knob) and `dDisp` the present's transfer ([DISPLAY_MS]). position(x) is the player's place now carried forward to x
 * while it plays (`MusicPlayer.positionMs` — the sink's own on the playing host; the record's `now` extrapolated on the
 * phone's clock when the PC plays, which carries the two wall clocks' difference uncorrected: the S1 link's journal line
 * measures it). The phone time of a step comes from the chain ([LaneAt.stepOneMs], [LaneAt.stepMs]); the lens's own clock
 * is placed by its PLAY's landing (`WindowLanes.flushDone`), and each later stretch is exactly one stretch on.
 *
 * **The bar** ([barProgram]): one per-lens track over the bar, backdrop the unlit level; one DRAW of a solid record of
 * the bar's width at the lit level, its right edge on the lit boundary, sliding right by the stretch's growth, linear —
 * the record's x on each lens is P0 / P1, so the program is the same bytes for every stretch of a song (no write a
 * stretch: only its PLAY-AFTER, 17 B). The track holds its last pose, so a late chain freezes the bar where it was.
 * The period is chosen so a step moves it at most a pixel ([barPeriod]).
 */
object MusicMotion {
    /** dDisp: a step shows on the panel this long after its tick (the present's transfer, 2–5 ms, measured,
     *  `FIRMWARE.md` §5.3). */
    const val DISPLAY_MS = 3L
    /** The song time a stretch of the bar covers: as many steps as a program allows. */
    const val BAR_STEPS = Motion.MAX_STEPS

    /** The song's place, as heard, at phone time [t] — given the player's place [posNow] at phone time [now], whether it
     *  [playing], and the output's offset [offsetMs]. */
    fun heardAt(t: Long, now: Long, posNow: Long, playing: Boolean, offsetMs: Long): Long =
        LyricsSync.heardPos(if (playing) posNow + (t + DISPLAY_MS - now) else posNow, offsetMs)

    /**
     * The song's place standing still while it plays (the second review's finding 3): the phone's stream waiting for data,
     * or the PC's player buffering (`PlayerCore.follow` maps its BUFFERING to PLAYING). [stalled] is true once the place
     * has not moved for more than [slackMs], counted from where it stopped: a sample that finds it moved by less than the
     * clock since the last one puts the stop there — the song's zero grows by the same amount, so the pump that would see
     * the zero moved past the slack sees the stall first. A place that kept up or jumped (a seek), or not playing, starts
     * the watch again. `MusicGlasses.lanes` asks it at every pump (no lanes while it stands, so the chain ends once and
     * the bar stands); the lane rig asks it too.
     */
    class StillPlace(private val slackMs: Long) {
        private var place = Long.MIN_VALUE
        private var at = 0L
        private var since = 0L
        private var was = false
        fun stalled(now: Long, playing: Boolean, place: Long): Boolean {
            val moved = place - this.place
            since = when {
                !playing || !was || moved < 0 -> now
                moved == 0L -> since
                moved >= now - at -> now
                else -> at + moved
            }
            this.place = place; at = now; was = playing
            return now - since > slackMs
        }
    }

    /** The bar's lit pixels for a song place: [w] · pos / dur, within 0..w (rounded half up). */
    fun litPx(w: Int, posMs: Long, durMs: Long): Int =
        if (durMs <= 0) 0 else ((w.toLong() * posMs.coerceIn(0, durMs) * 2 + durMs) / (2 * durMs)).toInt().coerceIn(0, w)

    /** One step moves the bar a pixel at most: the song time of a pixel in OS ticks (1.024 a ms), within the engine's
     *  periods — 128 (≈ 125 ms) for any song over ≈ a minute on a 500-px bar. */
    fun barPeriod(w: Int, durMs: Long): Int =
        (durMs * 1024 / 1000 / maxOf(1, w)).toInt().coerceIn(Motion.PERIOD_30HZ, 128)

    /** The bar's growth over a stretch, whole pixels: DRAW moves by D·E[j]/4096 with linear E, so D is the stretch's
     *  growth rounded (the error is under half a pixel, put right at the next stretch's start). */
    fun barGrowth(w: Int, durMs: Long, period: Int, steps: Int): Int =
        if (durMs <= 0) 0 else Math.round(w.toDouble() * steps * period * 1000.0 / 1024.0 / durMs).toInt()

    /** The bar's solid lit record: [w]×[h] at level [lit] (0..15). */
    fun barRecord(w: Int, h: Int, lit: Int): Gray8 = Gray8(w, h).also { it.fillRect(Rect(0, 0, w, h), Level.of(lit)) }

    /** The bar's program at [at4] (version 2, OWN, no LAND): [area] nominal at disparity [d]; [unlit] the backdrop's
     *  level; [rec] the lit record ([barRecord], [area]'s size); [grow] px over [steps] steps. P0 / P1: the record's x on
     *  the left / right lens at step 0. */
    fun barProgram(at4: Int, fx: Motion.Effects, rec: Motion.Source.Record, area: Rect, d: Int, unlit: Int, grow: Int, steps: Int): MotionProgram =
        Motion.program2(at4, fx, own = true, records = listOf(rec)) {
            track(area.translate(-d, 0), area.translate(d, 0), Motion.Backdrop.Level(unlit)) {
                draw(rec, area.x - d - area.w, area.y, dx = grow, n = steps, xR = area.x + d - area.w,
                    prefs = listOf(Motion.Pref(0, Motion.Field.XL), Motion.Pref(1, Motion.Field.XR)))
            }
        }

    /** P0 / P1 for a stretch whose step 1 shows the song at [posMs]: the record placed so its right edge is at the lit
     *  boundary one step before (DRAW's step j is x + D·⌊4096·j / T⌋ / 4096, so step 1 adds D / T). */
    fun barParams(area: Rect, d: Int, posMs: Long, durMs: Long, grow: Int, steps: Int): IntArray {
        val lit = if (durMs <= 0) 0.0 else area.w * posMs.coerceIn(0, durMs).toDouble() / durMs
        val x0 = area.x - area.w + Math.round(lit - grow.toDouble() / steps).toInt()
        return intArrayOf(x0 - d, x0 + d, 0, 0)
    }

    /**
     * The bar's lane set: [bar] the bar's rect as the window painted it (nominal, on the grid); [lit] / [unlit] its two
     * levels (0..15); the song of [durMs]; [key] the window's own (the track, the play state, a seek, the output, the
     * layout); [heard] the equation at a phone time.
     */
    fun barLanes(key: Any, bar: Rect, lit: Int, unlit: Int, durMs: Long, heard: (Long) -> Long): LaneSpec {
        val period = barPeriod(bar.w, durMs)
        val grow = barGrowth(bar.w, durMs, period, BAR_STEPS)
        return LaneSpec(listOf(key, bar, lit, unlit, durMs), listOf(bar), period, BAR_STEPS, listOf(barRecord(bar.w, bar.h, lit))) { at: LaneAt ->
            val rec = at.statics[0]
            LanePlan(barProgram(at.at4, at.fx, rec, bar, at.d, unlit, grow, at.steps),
                barParams(bar, at.d, heard(at.stepOneMs), durMs, grow, at.steps))
        }
    }

    // ------------------------------------------------------------------------------------------- stage S: Scope
    /** Scope's period: A4's 30 Hz (spec §4.4, P = 34). */
    const val SCOPE_PERIOD = Motion.PERIOD_30HZ
    /** How far a stretch's place at step 0 may lie past the anchor its cells were laid for and still use them (ruling 6;
     *  `MM-S-built.md` §1): a chain's first stretch is planned again at every pump while its area is written and proven
     *  (its step 1 is "now + 70"), and within this slack its bytes stay the same, so the area is not written again. It
     *  costs [SCOPE_SLACK_MS] / 40 cells a stretch. */
    const val SCOPE_SLACK_MS = 1_000L
    /** Today's look (`ScopeViz`): a 4-px column every 8 px, one a 40-ms column of the song — the field moves 1 px each 5 ms. */
    private const val MS_PER_PX = ScopeViz.MS_PER_COL / ScopeViz.COL_PITCH
    /** A column's code: 32 + its RMS level (1..15); a level of 0 draws no column and names no cell. */
    private const val CODE0 = 32

    /** Scope's strip [a] as `ScopeViz` draws it: the zero line's centre row, the tallest half-column, the columns across. */
    class ScopeGeo(val a: Rect) {
        init {
            if (a.w < Viz.MIN_W || a.h < Viz.MIN_H || a != Rect(Geometry.snapX(a.x), Geometry.snapY(a.y), Geometry.snapX(a.w), Geometry.snapY(a.h)))
                throw LintError("Scope's lane strip $a is not on the 4x2 grid or under ${Viz.MIN_W}x${Viz.MIN_H}")
        }
        val cy = a.y + Geometry.snapY(a.h / 2)
        val maxHalf = maxOf(Geometry.Y_STEP, Geometry.snapY(a.h / 3))
        val cols = maxOf(1, a.w / ScopeViz.COL_PITCH)
    }

    /** RMS level 1..15 of song column [k] (its two 20-ms slots, the louder), or 0 (silence, before or past the track). */
    fun scopeLevel(d: VizData, k: Long): Int {
        if (k < 0 || 2 * k + 1 > Int.MAX_VALUE) return 0
        return maxOf(0, rmsSlot(d, (2 * k).toInt()), rmsSlot(d, (2 * k + 1).toInt()))
    }

    /** The glyph of level [lv]'s column: the strip's full column height, the column `ScopeViz` draws centred in it at the
     *  body level, its other pixels 0 (not drawn, §7.6). */
    fun scopeGlyph(g: ScopeGeo, lv: Int): TextureCache.Image {
        val h = 2 * g.maxHalf
        val half = heightOf(lv.toDouble(), g.maxHalf)
        val body = Pack.level(Level.BODY).toByte()
        return TextureCache.Image(ScopeViz.COL_W, h, ByteArray(ScopeViz.COL_W * h) { i -> if (i / ScopeViz.COL_W in g.maxHalf - half until g.maxHalf + half) body else 0 })
    }

    /** Song columns a stretch of [steps] lays out (ruling 6): those on the strip at its anchor, and every one that enters
     *  over the stretch and the slack after it, with one to spare at each end for the engine's rounding. */
    private fun scopeSpan(g: ScopeGeo, steps: Int, stepMs: Double): Pair<Int, Int> =
        -g.cols to Math.ceil((SCOPE_SLACK_MS + steps * stepMs) / ScopeViz.MS_PER_COL).toInt() + 1

    /** T from the room (ruling 4): the most steps (≤ 255) whose stretch's cells fit the tables' room an area leaves after
     *  [statics], the font of [glyphs] and the program's own room — every column drawn (a loud passage: the worst case). */
    fun scopeSteps(g: ScopeGeo, statics: List<Gray8>, glyphs: Map<Int, TextureCache.Image>, stepMs: Double): Int {
        val cellsMax = (WindowLanes.tableRoom(statics, glyphs) - 8 - 4 - 8) / 12      // the table, the alphabet, the list's head
        val t = (Motion.MAX_STEPS downTo 1).firstOrNull { scopeSpan(g, it, stepMs).let { (lo, hi) -> hi - lo + 1 } <= minOf(cellsMax, 512) }
        return t ?: throw LintError("Scope's strip ${g.a} leaves no room for a stretch of one step ($cellsMax cells fit an area)")
    }

    /** The zero line `ScopeViz` draws: [w] × 2 at the faint level. */
    fun scopeLine(w: Int): Gray8 = Gray8(w, Geometry.Y_STEP).also { it.fillRect(Rect(0, 0, w, Geometry.Y_STEP), Level.FAINT) }

    /** One stretch's program (version 2, OWN, no LAND) at [at4] with its tables from [tables4]: the strip [g] at disparity
     *  [d] — first track its background (level 0) and the zero line ([line], a static), the second inside it the columns:
     *  a GLYPHS CELLS list of [cells], offset by P0 / P1 (x on the left / right lens at step 0) and moved by [dx] over the
     *  stretch. With no column (silence, before the track) the list holds one cell of code 32, which draws nothing, so
     *  every stretch has the same shape. */
    fun scopeProgram(at4: Int, tables4: Int, fx: Motion.Effects, line: Motion.Source.Record, font: Motion.Font, g: ScopeGeo, d: Int,
                     cells: List<Pair<Int, Int>>, dx: Int, steps: Int): MotionProgram {
        val a = g.a
        val alphabet = Motion.Alphabet(tables4 + 2, intArrayOf(CODE0))
        val list = Motion.CellList(tables4 + 3, alphabet, 0, cells.map { (bx, lv) ->
            Motion.Cell(bx, g.cy - g.maxHalf, ScopeViz.COL_W, 2 * g.maxHalf, font, CODE0 + lv, CODE0 + lv)
        }.ifEmpty { listOf(Motion.Cell(a.right - ScopeViz.COL_W, g.cy - g.maxHalf, ScopeViz.COL_W, 2 * g.maxHalf, font, CODE0, CODE0)) })
        val table = Motion.CellTable(tables4, listOf(list))
        return Motion.program2(at4, fx, own = true, records = listOf(line)) {
            track(a.translate(-d, 0), a.translate(d, 0), Motion.Backdrop.Level(0)) {
                draw(line, a.x - d, g.cy - Geometry.Y_STEP, n = steps, xR = a.x + d)
            }
            track(a.translate(-d, 0), a.translate(d, 0), Motion.Backdrop.None) {
                cells(table, 0, n = steps, x = 0, dx = dx, xR = 0, dxR = dx, prefs = listOf(Motion.Pref(0, Motion.Field.XL), Motion.Pref(1, Motion.Field.XR)))
            }
        }
    }

    /**
     * Scope's lane set (stage S; spec §4.5, rulings A, 4, 6, 7): Music Mode's strip [strip] (nominal, on the grid) playing
     * the RMS envelope of [data] — today's look (`ScopeViz`: 4-px columns on an 8-px pitch, the zero line under them)
     * panned left at 200 px/s on the glasses' clock, P 34 and T from the room. Song column k (its 20-ms slots 2k and 2k + 1)
     * stands at the strip's right edge (x = right − 4) when the song is at its **anchor** 40k + 20 — where today's paint
     * puts the same column — and 1 px further left each 5 ms. A stretch's cells are laid for an anchor on that 40-ms grid at
     * or before its place at step 0, kept while later plans stay within [SCOPE_SLACK_MS] of it (the planner's one state);
     * the place past the anchor rides in P0 / P1. [heard] is the song as heard at a phone time ([heardAt]).
     */
    fun scopeLanes(key: Any, strip: Rect, data: VizData, heard: (Long) -> Long): LaneSpec {
        val g = ScopeGeo(strip)
        val line = scopeLine(strip.w)
        val glyphs = (1..15).associate { (CODE0 + it) to scopeGlyph(g, it) }
        val stepMs = SCOPE_PERIOD * 1000.0 / 1024.0
        val steps = scopeSteps(g, listOf(line), glyphs, stepMs)
        val dx = -Math.round(steps * stepMs / MS_PER_PX).toInt()
        var anchor = Long.MIN_VALUE
        return LaneSpec(listOf(key, strip), listOf(strip), SCOPE_PERIOD, steps, listOf(line), glyphs) { at: LaneAt ->
            val p0 = Math.round(heard(at.stepOneMs) - at.stepMs)
            if (anchor == Long.MIN_VALUE || p0 < anchor || p0 - anchor >= SCOPE_SLACK_MS)
                anchor = Math.floorDiv(p0 - ScopeViz.MS_PER_COL / 2, ScopeViz.MS_PER_COL) * ScopeViz.MS_PER_COL + ScopeViz.MS_PER_COL / 2
            val kq = (anchor - ScopeViz.MS_PER_COL / 2) / ScopeViz.MS_PER_COL
            val (lo, hi) = scopeSpan(g, at.steps, at.stepMs)
            val cells = (lo..hi).mapNotNull { j -> scopeLevel(data, kq + j).takeIf { it > 0 }?.let { lv -> (strip.right - ScopeViz.COL_W + j * ScopeViz.COL_PITCH) to lv } }
            val x = -Math.round((p0 - anchor).toDouble() / MS_PER_PX).toInt()
            LanePlan(scopeProgram(at.at4, at.tables4, at.fx, at.statics[0], at.font!!, g, at.d, cells, dx, at.steps), intArrayOf(x - at.d, x + at.d, 0, 0))
        }
    }

    /** The continuous bar as the window paints it: [x, x + lit) at [litLevel], the rest at [unlitLevel] (0..255 values). */
    fun paintBar(g: Gray8, r: Rect, lit: Int, litLevel: Int, unlitLevel: Int) {
        val l = lit.coerceIn(0, r.w)
        if (l > 0) g.fillRect(Rect(r.x, r.y, l, r.h), litLevel)
        if (l < r.w) g.fillRect(Rect(r.x + l, r.y, r.w - l, r.h), unlitLevel)
    }
}
