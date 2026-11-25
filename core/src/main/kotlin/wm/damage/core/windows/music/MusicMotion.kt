package wm.damage.core.windows.music

import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.motion.Motion
import wm.damage.core.motion.MotionProgram
import wm.damage.core.shell.LaneAt
import wm.damage.core.shell.LanePlan
import wm.damage.core.shell.LaneSpec

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
     * has not moved for more than [slackMs]; not playing, the watch starts again. `MusicGlasses.lanes` asks it at every
     * pump (no lanes while it stands, so the chain ends once and the bar stands); the lane rig asks it too.
     */
    class StillPlace(private val slackMs: Long) {
        private var place = Long.MIN_VALUE
        private var since = 0L
        fun stalled(now: Long, playing: Boolean, place: Long): Boolean {
            if (!playing || place != this.place) { this.place = place; since = now }
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

    /** The continuous bar as the window paints it: [x, x + lit) at [litLevel], the rest at [unlitLevel] (0..255 values). */
    fun paintBar(g: Gray8, r: Rect, lit: Int, litLevel: Int, unlitLevel: Int) {
        val l = lit.coerceIn(0, r.w)
        if (l > 0) g.fillRect(Rect(r.x, r.y, l, r.h), litLevel)
        if (l < r.w) g.fillRect(Rect(r.x + l, r.y, r.w - l, r.h), unlitLevel)
    }
}
