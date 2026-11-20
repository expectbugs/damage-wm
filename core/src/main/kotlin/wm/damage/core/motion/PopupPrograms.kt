package wm.damage.core.motion

import wm.damage.core.geom.Rect

/**
 * Build K1-4a: the notice box's engine programs (`FIRMWARE.md` §5; spec K1 §6; `DESIGN.md` §4.5's timer) — the focus
 * timer as ONE program and the two arrivals the glasses draw, all from the box's own pixels (`self`).
 *
 * The box the phone draws while its timer runs is the programs' END (`E`): full ink, the top rule's bar whole at level 8
 * under the text column, level 1 at its ends — what every PLAY writes into the shadow (§5.6) and what the next PLAY
 * captures (§5.4), so a restart captures the same picture whatever step the last one had reached: nothing dims or
 * brightens from restart to restart. (A program whose END was the 90 % picture would leave that in the shadow, and the
 * next capture would start from it — a restart with no box bytes needs END = the full-ink picture: §5.4's `self`, §5.5
 * PLAY's row.)
 *
 * Three tracks, per lens (the box sits at the content's disparity d until focus: left lens x − d, right x + d):
 *  1. the box above its bottom rule — backdrop 0 (the hole), DRAW `self` with its level eased: the timer 5 (≈ 30 %)
 *     at step 1, 5 → 14 (≈ 93 %) over steps 2..16, 15 at step 17 (= END, 2.0 s after step 1 at P 128);
 *  2. the top rule's bar run (8 px in from each end) — backdrop level 1, REVEAL of a level-8 solid 232×2 from the left
 *     over steps 2..17 (empty at step 1, whole at END); the rule's ends are the box's own capture (track 1), so they
 *     dim with the box (a level-1 mark is 0 below level 15) and are exact at END;
 *  3. the bottom rule — backdrop `self`, held (the v1 LUT draws a level-1 mark at 0 below level 15: §5.4's DRAW, so the
 *     rule is kept at its own level by its own capture instead).
 * The arrivals (Fade, Slide) run at their own period ([PopupPrograms.arrival]); one instance has one period (§5.3), so
 * the timer at P 128 cannot carry a 300 ms arrival in its own steps — the arrival is its own PLAY in the same slot,
 * holding the timer's first look until the timer's PLAY replaces it (closes it: same slot, §5.5) at its nominal end.
 */
object PopupPrograms {
    /** Slot 2 (spec §9: 0 the bound list, 1 the wrap; 2 and 3 transitions, the notice, Lock). */
    const val SLOT = 2
    /** The timer's period: 128 OS ticks = 125 ms; 16 steps after step 1 = 2.0 s (the grace, his "2s flat"). */
    const val TIMER_PERIOD = 128
    const val TIMER_STEPS = 17
    /** The timer's levels: 5 of 15 at step 1, 14 at step 16 (his "from like 30% to 90%"), 15 at END. */
    const val LVL_START = 5
    const val LVL_LAST = 14
    /** The bar's lit level (`Notifications.BAR_LIT`'s, the body's reading level) and its unlit rule. */
    const val BAR_LEVEL = 8
    const val RULE_LEVEL = 1
    /** The largest program here, in bytes (three per-lens tracks of three segments): a program slot's size. */
    const val SLOT_BYTES = 4 + 3 * (20 + 3 * Motion.SEGMENT_BYTES)
    init {
        // three line counts × three styles, then the capture (the 248×104 box: 12,896 B a lens) inside the region
        check(9 * SLOT_BYTES <= wm.damage.core.wire.CfwModes.NOTICE_PROGRAMS &&
            wm.damage.core.wire.CfwModes.NOTICE_PROGRAMS + 124 * 104 <= wm.damage.core.wire.CfwModes.NOTICE_REGION)
    }

    /** Where a box is on the panel: [box] nominal, its top rule [ruleOff] rows down, the bar's run [barInset] in from each
     *  end, at disparity [d]. */
    data class Geo(val box: Rect, val ruleOff: Int, val barInset: Int, val d: Int) {
        val upper: Rect get() = Rect(box.x, box.y, box.w, box.h - 2)
        /** The bar's run on the top rule (inset from both ends): the bar's track. Not the whole rule — where a nearer
         *  plane covers the box's edge columns on a lens (Main's selection bar, one notch nearer, covers 4 of them) the
         *  truth there is that plane's, which only a `self` track reproduces; the rule's ends come from the box's capture. */
        val rule: Rect get() = Rect(box.x + barInset, box.y + ruleOff, box.w - 2 * barInset, 2)
        val bottom: Rect get() = Rect(box.x, box.bottom - 2, box.w, 2)
        val barW: Int get() = box.w - 2 * barInset
        fun l(r: Rect) = r.translate(-d, 0)
        fun r(r: Rect) = r.translate(d, 0)
        /** Pixels one step paints per lens (the three tracks: the box's area): what the period's fit is taken on. */
        val areaPx: Int get() = box.w * box.h
    }

    enum class Style { TIMER, FADE, SLIDE }

    /** The timer program at [at] (off4). */
    fun timer(at: Int, g: Geo): MotionProgram = Motion.program(at) {
        track(g.l(g.upper), g.r(g.upper), Motion.Backdrop.Level(0)) {
            val (xl, xr, y) = Triple(g.l(g.upper).x, g.r(g.upper).x, g.upper.y)
            draw(Motion.Source.self, xl, y, lvl0 = LVL_START, lvl1 = LVL_START, n = 1, xR = xr)
            draw(Motion.Source.self, xl, y, lvl0 = LVL_START, lvl1 = LVL_LAST, n = TIMER_STEPS - 2, xR = xr)
            draw(Motion.Source.self, xl, y, lvl0 = LVL_LAST, lvl1 = 15, n = 1, xR = xr)
        }
        barTrack(g, lead = 1, revealSteps = TIMER_STEPS - 1)
        bottomTrack(g, TIMER_STEPS)
    }

    /**
     * An arrival at [at] (off4): [f] steps of its motion — Fade: the box's marks from level 0 to the timer's first look;
     * Slide: the box moved down into its place from above its top edge, at the timer's first look — then [hold] steps at
     * the timer's first look, then END (the full-ink picture, as the timer's: the shadow the timer's PLAY captures).
     */
    fun arrival(at: Int, g: Geo, style: Style, f: Int, hold: Int): MotionProgram = Motion.program(at) {
        require(style != Style.TIMER)
        track(g.l(g.upper), g.r(g.upper), Motion.Backdrop.Level(0)) {
            val (xl, xr, y) = Triple(g.l(g.upper).x, g.r(g.upper).x, g.upper.y)
            if (style == Style.FADE) draw(Motion.Source.self, xl, y, lvl0 = 0, lvl1 = LVL_START, ease = Motion.Ease.easeOut, n = f, xR = xr)
            else draw(Motion.Source.self, xl, y - g.upper.h, dy = g.upper.h, lvl0 = LVL_START, lvl1 = LVL_START, ease = Motion.Ease.easeOut, n = f, xR = xr)
            draw(Motion.Source.self, xl, y, lvl0 = LVL_START, lvl1 = LVL_START, n = hold, xR = xr)
            draw(Motion.Source.self, xl, y, lvl0 = LVL_START, lvl1 = 15, n = 1, xR = xr)
        }
        barTrack(g, lead = f + hold, revealSteps = 1)
        bottomTrack(g, f + hold + 1)
    }

    private fun Motion.ProgramBuilder.barTrack(g: Geo, lead: Int, revealSteps: Int) =
        track(g.l(g.rule), g.r(g.rule), Motion.Backdrop.Level(RULE_LEVEL)) {
            wait(lead)
            reveal(Motion.Source.level(BAR_LEVEL, g.barW, 2), Motion.Edge.LEFT, n = revealSteps,
                x = g.l(g.rule).x, xR = g.r(g.rule).x, y = g.rule.y)
        }

    private fun Motion.ProgramBuilder.bottomTrack(g: Geo, steps: Int) =
        track(g.l(g.bottom), g.r(g.bottom), Motion.Backdrop.Self) {
            var left = steps
            while (left > 0) { val n = minOf(left, Motion.MAX_STEPS); wait(n); left -= n }
        }

    /** The level the timer draws the box at step [k] (1..T): what `NoticeTimer` tests and the renders read. */
    fun timerLevel(k: Int): Int = when {
        k <= 1 -> LVL_START
        k >= TIMER_STEPS -> 15
        else -> Motion.eased(LVL_START, LVL_LAST - LVL_START, Motion.easeE(Motion.Ease.linear, TIMER_STEPS - 2)[k - 1])
    }
}
