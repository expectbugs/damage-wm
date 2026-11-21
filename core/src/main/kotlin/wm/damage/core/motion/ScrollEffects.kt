package wm.damage.core.motion

import wm.damage.core.geom.Layout
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.shell.MainStaging
import wm.damage.core.wire.CfwModes

/** K1-1: the Scroll tier's effects (`MOTION.md` §7: one effect, no randomness), as `ShellSettings.scrollEffect` stores
 *  them ([id]) and the `Scroll effect` row shows them ([word]). Glitch, Flip, Crush, Scramble/Encode and Matrix are the
 *  next firmware build's (ruled: built once on its verbs). */
enum class ScrollEffect(val id: String, val word: String) {
    NONE("none", "None"), DEPTH("depth", "Depth"), SLIDE("slide", "Slide"), FALL("fall", "Fall");

    companion object {
        val DEFAULT = DEPTH
        fun byId(id: String): ScrollEffect? = entries.firstOrNull { it.id == id }
    }
}

/**
 * K1-1 (`MOTION.md` §7, ruled by Adam 2026-11-19; `private/build081/K1-spec.md` §2–§3): the Scroll tier on Main's bound
 * list, on the firmware he wears (C2's engine and local input, `FIRMWARE.md` §5–§6). Context for the reader: Adam's own
 * glasses on his own firmware fork; display-rendering work on a personal device. Nothing here sends.
 *
 * An effect is a function from what is staged ([Staged]: the layout, the planes, the records) and the tier's motion (T
 * steps) to one notch's program ([program]) — a `when` over [ScrollEffect], no registry. Every effect draws the same
 * strip or wrap record and takes the same four parameters by the same rules ([rules]: P0 the upper strip's start y, P1
 * the entering row's band form, P2 the leaving row's, P3 the lower strip's start y), so a fire, a report and a COMMIT
 * read alike whichever effect is in force. Every effect's END is Main's own paint at the new cursor (`ScrollEffectsTest`,
 * `desktop --local-check`). Each effect's period is fitted on its own tracks' painted area ([areaPx], the fit of
 * [MainStaging.timing]; at stage 5 the fast DRAW path's, [timing]).
 *
 * - [ScrollEffect.NONE]: one step — the rows at their new place and the entering row's band form in the band.
 * - [ScrollEffect.DEPTH]: today's (stage DM0, decisions V; the programs of APK 0.82 byte for byte): the rows above and
 *   below the band pan one pitch with ease-out; in the band the leaving form goes back to the list's plane and dims over
 *   the swap ([MainStaging.swapOf]), then the entering one comes forward and brightens.
 * - [ScrollEffect.SLIDE]: the rows as Depth; in the band two tracks over its area — the leaving form slides out and the
 *   entering one slides in behind it, the band's height while the rows travel a pitch, both on the band's plane at
 *   full level (the second track's backdrop is `none`: the two forms abut at every step, so nothing shows between them).
 * - [ScrollEffect.FALL]: Slide's tracks on an uploaded ease ([fallTable]): an ease-in that runs [FALL_OVERSHOOT_PCT] %
 *   past the end at step T − 1 and settles at T. No level is eased (the forms stay at 15), so the overshoot never takes
 *   a level outside 0..15. At that one step (by the ease's arithmetic, at 352: 2 px past a 32 px pitch, 4 px past the
 *   64 px band) the band shows its backdrop in the 4 px the entering form has passed, and at the list's two extreme
 *   cursors the rows show 2 px of backdrop past the strip's end.
 */
object ScrollEffects {
    /** Fall's overshoot at step T − 1, in percent of the travel (a pitch for the rows, the band's height for the band). */
    const val FALL_OVERSHOOT_PCT = 6

    /** What is staged that every pan draws from: Main's [layout], its list's plane [listD] and its band's [bandD], [band]
     *  a band form's record (any: P1 and P2 name the real one), [records] every record a parameter may name. */
    class Staged(val layout: Layout, val listD: Int, val bandD: Int, val band: Motion.Source.Record, val records: List<Motion.Source.Record>)

    /** The four parameters' rules (`FIRMWARE.md` §6.2 step 9; c' the new cursor, [marginA] the strip's rows before row 0 —
     *  k, or k + 1 on a ring strip, C3 DM-0): P0 lens.y − (c + marginA) × rowH and P3 lens.bottom − (c + 1 + marginA) ×
     *  rowH (kind 2, c = c' − step), P1 the entering row's band form and P2 the leaving row's (kind 1). The same for every
     *  effect. */
    fun rules(l: Layout, marginA: Int, step: Int): List<MainStaging.Rule> = listOf(
        MainStaging.Rule(CfwModes.KIND_PITCH, l.lens.y + (step - marginA) * l.rowH),
        MainStaging.Rule(CfwModes.KIND_ITEM, 0),
        MainStaging.Rule(CfwModes.KIND_ITEM, -step),
        MainStaging.Rule(CfwModes.KIND_PITCH, l.lens.bottom + (step - 1 - marginA) * l.rowH))

    /** Band tracks an effect paints (each over the band's whole area). */
    private fun bandTracks(e: ScrollEffect) = if (e == ScrollEffect.SLIDE || e == ScrollEffect.FALL) 2 else 1

    /** The pixels one step of [e]'s whole-list pan paints per lens at [l] with the list at [listD] and the band at
     *  [bandD] — every track's area, what its period is fitted on. */
    fun areaPx(e: ScrollEffect, l: Layout, listD: Int, bandD: Int): Int {
        val rowW = l.content.w - Layout.RAIL_W
        return (l.rowsAbove + l.rowsBelow) * l.rowH * rowW + bandTracks(e) * l.lens.h * (rowW + kotlin.math.abs(listD - bandD))
    }

    /** The bytes one step of [e]'s pan decodes at [l] on the fast DRAW path (`FIRMWARE.md` §7.12: each DRAW of a record
     *  decodes from its kept start to the last source row its output inside the area takes): k + 1 strip rows for each
     *  of the two row tracks (a step between two places shows parts of k + 1 rows) and one band form per band track
     *  (Depth's two band segments draw one at a time). Rows and forms at the DM spec's §1.6 sizes
     *  ([MainStaging.FAST_ROW_BYTES], [MainStaging.FAST_BAND_FORM_BYTES]; M-PC). */
    fun walkBytes(e: ScrollEffect, l: Layout): Int =
        (l.rowsAbove + 1 + l.rowsBelow + 1) * MainStaging.FAST_ROW_BYTES + bandTracks(e) * MainStaging.FAST_BAND_FORM_BYTES

    /** [e]'s period (OS ticks) and steps for [motion] at Main's [l] with the list at [listD] and the band at [bandD]: on
     *  [areaPx] pixels a step, C2's fit ([MainStaging.timing]) — or, C3 FF, with EFFECTS in force ([stage5]: every fire
     *  then runs the fast DRAW path, `FIRMWARE.md` §7.12), the fast path's ([MainStaging.fastStepMs] of the area and
     *  [walkBytes]) — on K1-1's rule ([MainStaging.timingOf]); one step for None (at the fitted period: the swap at once). */
    fun timing(e: ScrollEffect, l: Layout, listD: Int, bandD: Int, motion: MainStaging.ScrollMotion, stage5: Boolean): Pair<Int, Int> {
        val area = areaPx(e, l, listD, bandD)
        val m = if (e == ScrollEffect.NONE) motion.copy(frames = 0) else motion
        val pt = if (stage5) MainStaging.timingOf(MainStaging.fastStepMs(area, walkBytes(e, l)), m) else MainStaging.timing(area, m)
        return if (e == ScrollEffect.NONE) pt.first to 1 else pt
    }

    /** Under Auto, the steps of [motion]'s length at the period in force [period] (decisions BF's measured period: K1-1
     *  item 4 — the steps follow it); a fixed count and None keep theirs. */
    fun stepsAt(motion: MainStaging.ScrollMotion, period: Int, t: Int): Int =
        if (motion.effect == ScrollEffect.NONE || motion.frames > 0) t
        else kotlin.math.floor(motion.lengthMs * 1024.0 / 1000.0 / period).toInt().coerceAtLeast(MainStaging.MIN_STEPS)

    /** Fall's ease over [t] steps (`FIRMWARE.md` §5.4 table: E[1..n], E[n] = 4096): E[j] = peak × (j / (t − 1))² up to
     *  the peak at t − 1, then 4096 at t; the peak is [FALL_OVERSHOOT_PCT] % past 4096. */
    fun fallTable(t: Int): IntArray {
        if (t < 2) throw LintError("Fall's ease needs two steps or more, not $t")
        val peak = 4096L + 4096L * FALL_OVERSHOOT_PCT / 100
        val d = (t - 1).toLong() * (t - 1)
        return IntArray(t) { i -> val j = (i + 1).toLong(); if (j == t.toLong()) 4096 else ((peak * j * j + d / 2) / d).toInt() }
    }

    /** Whether [e] uploads an ease table (placed by the caller before its programs). */
    fun usesTable(e: ScrollEffect) = e == ScrollEffect.FALL

    /**
     * [e]'s pan at off4 [at4]: [kA] rows above the band and [kB] below drawn from [strip] (a strip slot or the wrap's
     * record) one notch in [step] (+1 down, −1 up) over [t] steps (one for None); [table] is Fall's ([fallTable] at
     * [t], already placed). Three per-lens tracks (four for Slide and Fall): the rows above the band and the rows below
     * it — the strip drawn twice, one pitch apart (decisions F) — and the band over both planes' x on each lens.
     */
    fun program(e: ScrollEffect, s: Staged, strip: Motion.Source.Record, kA: Int, kB: Int, step: Int, t: Int, at4: Int,
                table: Motion.Ease.Table? = null): MotionProgram {
        val l = s.layout
        val cx = l.content.x
        val rowW = l.content.w - Layout.RAIL_W
        val listD = s.listD; val bandD = s.bandD
        val shift = listD - bandD
        val bandW = rowW + kotlin.math.abs(shift)
        val n = if (e == ScrollEffect.NONE) 1 else t
        val ease: Motion.Ease = when (e) {
            ScrollEffect.NONE -> Motion.Ease.linear
            ScrollEffect.FALL -> table ?: throw LintError("Fall's pans are drawn on their uploaded table")
            else -> Motion.Ease.easeOut
        }
        return Motion.program(at4, s.records) {
            // the rows above the band: the strip, its start y P0, panned one pitch
            track(Rect(cx - listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH), Rect(cx + listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH), Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - listD, xR = cx + listD, y = 0, dy = -step * l.rowH, ease = ease, n = n,
                    prefs = listOf(Motion.Pref(0, Motion.Field.Y)))
            }
            // the rows below it: the same strip one pitch lower, its start y P3
            track(Rect(cx - listD, l.lens.bottom, rowW, kB * l.rowH), Rect(cx + listD, l.lens.bottom, rowW, kB * l.rowH), Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - listD, xR = cx + listD, y = 0, dy = -step * l.rowH, ease = ease, n = n,
                    prefs = listOf(Motion.Pref(3, Motion.Field.Y)))
            }
            // the band, its area over both planes' x on each lens
            val bandL = Rect(cx - maxOf(listD, bandD), l.lens.y, bandW, l.lens.h)
            val bandR = Rect(cx + minOf(listD, bandD), l.lens.y, bandW, l.lens.h)
            when (e) {
                // the leaving row's form (P2) goes back and dims, then the entering row's (P1) comes forward and brightens
                ScrollEffect.DEPTH -> track(bandL, bandR, Motion.Backdrop.Level(0)) {
                    val swap = MainStaging.swapOf(t)
                    draw(s.band, x = cx - bandD, xR = cx + bandD, y = l.lens.y, dx = -shift, dxR = shift,
                        lvl0 = 15, lvl1 = MainPan.LVL_BACK, ease = ease, n = swap, prefs = listOf(Motion.Pref(2, Motion.Field.SRC)))
                    draw(s.band, x = cx - listD, xR = cx + listD, y = l.lens.y, dx = shift, dxR = -shift,
                        lvl0 = MainPan.LVL_BACK, lvl1 = 15, ease = ease, n = t - swap, prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
                }
                // the entering row's form (P1) in the band at once
                ScrollEffect.NONE -> track(bandL, bandR, Motion.Backdrop.Level(0)) {
                    draw(s.band, x = cx - bandD, xR = cx + bandD, y = l.lens.y, n = 1, prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
                }
                // the leaving form (P2) slides out of the band and the entering one (P1) slides in behind it, the band's
                // height in the notch's direction, one eased offset for both so they abut at every step
                ScrollEffect.SLIDE, ScrollEffect.FALL -> {
                    track(bandL, bandR, Motion.Backdrop.Level(0)) {
                        draw(s.band, x = cx - bandD, xR = cx + bandD, y = l.lens.y, dy = -step * l.lens.h, ease = ease, n = t,
                            prefs = listOf(Motion.Pref(2, Motion.Field.SRC)))
                    }
                    track(bandL, bandR, Motion.Backdrop.None) {
                        draw(s.band, x = cx - bandD, xR = cx + bandD, y = l.lens.y + step * l.lens.h, dy = -step * l.lens.h, ease = ease, n = t,
                            prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
                    }
                }
            }
        }
    }
}
