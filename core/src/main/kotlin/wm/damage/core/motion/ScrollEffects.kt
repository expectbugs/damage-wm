package wm.damage.core.motion

import wm.damage.core.geom.Layout
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.shell.MainStaging
import wm.damage.core.wire.CfwModes

/** K1-1: the Scroll tier's effects (`MOTION.md` §7: one effect, no randomness), as `ShellSettings.scrollEffect` stores
 *  them ([id]) and the `Scroll effect` row shows them ([word]), in the order his plan gives them (None first). C3 DM-1:
 *  Flip and Crush, version-2 programs at stage 5 with their fallbacks below it ([ScrollEffects.plays]). Glitch,
 *  Scramble/Encode and Matrix are not built here (DM-2). */
enum class ScrollEffect(val id: String, val word: String) {
    NONE("none", "None"), DEPTH("depth", "Depth"), SLIDE("slide", "Slide"), FLIP("flip", "Flip"), FALL("fall", "Fall"), CRUSH("crush", "Crush");

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
 *
 * C3 DM-1 (the DM spec §1.2, §1.3; decisions AD's D2, D3, S6): two effects of version 2, built only through the gate's
 * token ([Motion.effects]: ladder stage 5 on a DamageCaps bit-11 build), each with its fallback ([plays]):
 * - [ScrollEffect.CRUSH] (his design, `MOTION.md` §7): pitch +rowH ([pitch]) — its kind-2 parameters are source-window
 *   first rows (`FIRMWARE.md` §7.5), the strip drawn as windows at fixed y's. Phase 1 ([phase1] steps): the entering
 *   side's rows move one pitch and the entering band form slides into the band, squashing the leaving one to one pixel
 *   row against the band's far edge, the far side's rows still; phase 2: the far side's rows move one pitch and the
 *   leaving row grows from one pixel row to a row, as its row picture. Each phase's movers share one ease and one step
 *   count; C3 X1DM1 (decisions BP (1)): the two phases are the two parts of ONE ease-out over T, split where it reaches
 *   half its travel ([halfStep], [easeOutParts]: the wheel's swap form), so the motion hands from one side to the other
 *   at speed instead of easing out to a near stop between them. Below stage 5, or when the offline check refuses it,
 *   Slide plays.
 * - [ScrollEffect.FLIP]: the rows as Depth's; the band turns about its vertical axis — the leaving form narrows to one
 *   column at the band's centre over phase 1, the entering one widens from one column over phase 2, each on its table.
 *   Version 2's width scale at stage 5, C3 X1DM1 (decisions BP (2), his "what moves, moves together"): the turn follows
 *   the rows — edge-on at the step where the rows' ease-out reaches half their travel ([halfStep]), the angle the rows'
 *   eased progress ([flipOnRows]). Below stage 5 (or refused) the turn on the height ([Plays.v2] false: C2's vertical
 *   scale, a version-1 record) at constant speed, split at [phase1] ([flipOut], [flipIn]; its bytes as DM-1 built them).
 * Both are functions of the layout, the planes, the strip's margin and the step alone (K1-2 moves them to window lists:
 * the band forms, P1 and P2 by kind 1, are what is Main's).
 */
object ScrollEffects {
    /** Fall's overshoot at step T − 1, in percent of the travel (a pitch for the rows, the band's height for the band). */
    const val FALL_OVERSHOOT_PCT = 6

    /** What is staged that every pan draws from: Main's [layout], its list's plane [listD] and its band's [bandD], [band]
     *  a band form's record (any: P1 and P2 name the real one), [records] every record a parameter may name. */
    class Staged(val layout: Layout, val listD: Int, val bandD: Int, val band: Motion.Source.Record, val records: List<Motion.Source.Record>)

    /**
     * C3 DM-1 (D3 as ruled, decisions AD): what one notch draws for an asked effect — [effect]'s own program, of version 2
     * where [v2] (Crush; Flip's width turn), or its fallback: Slide for Crush, Flip's height turn (version 1) for Flip.
     * Every other effect plays itself in version 1. [why] says why a fallback plays (null when the effect plays itself).
     */
    data class Plays(val effect: ScrollEffect, val v2: Boolean, val why: String? = null) {
        /** The words the `Scroll effect` row adds for [asked] when this is not its own program ("" when it is). */
        fun words(asked: ScrollEffect): String = when {
            asked == ScrollEffect.CRUSH && effect != ScrollEffect.CRUSH -> "plays ${effect.word}"
            asked == ScrollEffect.FLIP && !v2 -> "plays its height turn"
            else -> ""
        }
    }

    /** What plays for [asked] with the gate's token [fx] (null: no version 2 here — below stage 5, or a build without
     *  DamageCaps bit 11). The offline check at every cursor may still refuse a version-2 form: [fallback] then. */
    fun plays(asked: ScrollEffect, fx: Motion.Effects?): Plays = playsAt(asked, fx != null)

    /** The same, by whether the gate gives a token ([v2Here]: EFFECTS in force) — for what is said or fitted before a build. */
    fun playsAt(asked: ScrollEffect, v2Here: Boolean): Plays = when {
        asked != ScrollEffect.CRUSH && asked != ScrollEffect.FLIP -> Plays(asked, false)
        v2Here -> Plays(asked, true)
        else -> fallback(asked, NOT_STAGE5)
    }

    /** Why a fallback plays below stage 5. */
    const val NOT_STAGE5 = "EFFECTS is not in force (version 2 only at ladder stage 5 on a DamageCaps bit-11 build)"

    /** D3: Crush → Slide, Flip → its height turn (version 1); [why] said. */
    fun fallback(asked: ScrollEffect, why: String): Plays =
        if (asked == ScrollEffect.CRUSH) Plays(ScrollEffect.SLIDE, false, why) else Plays(asked, false, why)

    /** The list's pitch for what plays (decisions AD's S6): +rowH for Crush (its kind-2 values are source-window first
     *  rows, `FIRMWARE.md` §7.5), −rowH for every other form (the strips' start y). */
    fun pitch(p: Plays, l: Layout): Int = if (p.effect == ScrollEffect.CRUSH && p.v2) l.rowH else -l.rowH

    /** The four parameters' rules (`FIRMWARE.md` §6.2 step 9; c' the new cursor, [marginA] the strip's rows before row 0 —
     *  k, or k + 1 on a ring strip, C3 DM-0): P0 lens.y − (c + marginA) × rowH and P3 lens.bottom − (c + 1 + marginA) ×
     *  rowH (kind 2, c = c' − step), P1 the entering row's band form and P2 the leaving row's (kind 1). The same for every
     *  effect of pitch −rowH. */
    fun rules(l: Layout, marginA: Int, step: Int): List<MainStaging.Rule> = listOf(
        MainStaging.Rule(CfwModes.KIND_PITCH, l.lens.y + (step - marginA) * l.rowH),
        MainStaging.Rule(CfwModes.KIND_ITEM, 0),
        MainStaging.Rule(CfwModes.KIND_ITEM, -step),
        MainStaging.Rule(CfwModes.KIND_PITCH, l.lens.bottom + (step - 1 - marginA) * l.rowH))

    /** C3 DM-1: [p]'s rules. Crush (pitch +rowH, the DM spec §1.2): P0 the strip window both row tracks share — its first
     *  strip row, Main's row c' − k − 1 (a notch down) or c' − k (up): (M − k − 1) × rowH, resp. (M − k) × rowH, with M
     *  [marginA] and k the rows above; P1 the entering band form, P2 the leaving one (kind 1); P3 row c's own row in the
     *  strip, (M − step) × rowH. On a ring list kinds 1 and 2 take the virtual row (§7.8). Every other form: [rules]. */
    fun rules(p: Plays, l: Layout, marginA: Int, step: Int): List<MainStaging.Rule> =
        if (p.effect != ScrollEffect.CRUSH || !p.v2) rules(l, marginA, step) else listOf(
            MainStaging.Rule(CfwModes.KIND_PITCH, (marginA - l.rowsAbove - (if (step > 0) 1 else 0)) * l.rowH),
            MainStaging.Rule(CfwModes.KIND_ITEM, 0),
            MainStaging.Rule(CfwModes.KIND_ITEM, -step),
            MainStaging.Rule(CfwModes.KIND_PITCH, (marginA - step) * l.rowH))

    /** Flip's height turn's split of [t] steps (version 1, below stage 5 or refused): phase 1 takes ⌈t / 2⌉ (the leaving
     *  form turned to its edge), phase 2 the rest. Crush and Flip's width turn split at [halfStep] (C3 X1DM1). */
    fun phase1(t: Int): Int = (t + 1) / 2

    /** C3 X1DM1 (decisions BP (1)): the first step at which one ease-out over [t] steps (`FIRMWARE.md` §5.4: E[j] = 4096 −
     *  ⌊4096 (t − j)² / t²⌋) reaches half its travel, E[j] ≥ 2048 (at most t − 1) — where Crush's second phase starts.
     *  The arithmetic of the wheel's `Wheel.swapTables` (its n1), copied beside it: the scroll code does not take the
     *  wheel's file. */
    fun halfStep(t: Int): Int {
        if (t < 2) throw LintError("an ease-out split at half its travel needs two steps or more, not $t")
        val eo = Motion.easeE(Motion.Ease.EaseOut, t)
        return (1 until t).first { eo[it] >= 2048 || it == t - 1 }
    }

    /** C3 X1DM1 (decisions BP (1)): one ease-out over [t] steps as two tables (`FIRMWARE.md` §5.4: E[1..n], E[n] = 4096),
     *  split at [halfStep] n1 — E1 its first n1 steps rescaled to end at 4096, E2 its last t − n1 rescaled from 0 — so a
     *  mover that goes the first part of a travel on E1 and the rest on E2 is where one ease-out over t puts it, within a
     *  pixel (pinned). Crush's phase 1 runs on E1, its phase 2 on E2. The wheel's `Wheel.swapTables` arithmetic, copied. */
    fun easeOutParts(t: Int): Pair<IntArray, IntArray> {
        val eo = Motion.easeE(Motion.Ease.EaseOut, t)
        val n1 = halfStep(t)
        val e1 = IntArray(n1) { i -> Math.round(4096.0 * eo[i + 1] / eo[n1]).toInt() }
        val e2 = IntArray(t - n1) { i -> Math.round(4096.0 * (eo[n1 + i + 1] - eo[n1]) / (4096 - eo[n1])).toInt() }
        return e1 to e2
    }

    /** Band tracks an effect paints (each over the band's whole area). */
    private fun bandTracks(e: ScrollEffect) = if (e == ScrollEffect.SLIDE || e == ScrollEffect.FALL || e == ScrollEffect.CRUSH) 2 else 1

    /** The pixels one step of [e]'s whole-list pan paints per lens at [l] with the list at [listD] and the band at
     *  [bandD] — every track's area, what its period is fitted on. Crush (its version-2 form, the DM spec §1.6's model):
     *  its two row tracks, its two band tracks, and the leaving row's one row (its track paints only what it draws:
     *  backdrop none). Flip: Depth's area (the turn is the band track's). */
    fun areaPx(e: ScrollEffect, l: Layout, listD: Int, bandD: Int): Int {
        val rowW = l.content.w - Layout.RAIL_W
        return (l.rowsAbove + l.rowsBelow) * l.rowH * rowW + bandTracks(e) * l.lens.h * (rowW + kotlin.math.abs(listD - bandD)) +
            (if (e == ScrollEffect.CRUSH) l.rowH * rowW else 0)
    }

    /** The bytes one step of [e]'s pan decodes at [l] on the fast DRAW path (`FIRMWARE.md` §7.12: each DRAW of a record
     *  decodes from its kept start to the last source row its output inside the area takes): k + 1 strip rows for each
     *  of the two row tracks (a step between two places shows parts of k + 1 rows) and one band form per band track
     *  (Depth's two band segments draw one at a time); Crush's leaving row one row more. Rows and forms at the DM spec's
     *  §1.6 sizes ([MainStaging.FAST_ROW_BYTES], [MainStaging.FAST_BAND_FORM_BYTES]; M-PC). */
    fun walkBytes(e: ScrollEffect, l: Layout): Int =
        (l.rowsAbove + 1 + l.rowsBelow + 1 + (if (e == ScrollEffect.CRUSH) 1 else 0)) * MainStaging.FAST_ROW_BYTES +
            bandTracks(e) * MainStaging.FAST_BAND_FORM_BYTES

    /** The pixels one step of what plays writes a pixel at a time on the fast DRAW path (`FIRMWARE.md` §7.12: "every draw
     *  whose width scales writes a pixel at a time"): Flip's width turn, its band's area; none for the rest (Crush's
     *  scaled heights draw from the row buffer with their width unscaled: two pixels a byte). */
    fun pixelPx(p: Plays, l: Layout, listD: Int, bandD: Int): Int =
        if (p.effect == ScrollEffect.FLIP && p.v2) l.lens.h * (l.content.w - Layout.RAIL_W + kotlin.math.abs(listD - bandD)) else 0

    /** [e]'s period (OS ticks) and steps for [motion] at Main's [l] with the list at [listD] and the band at [bandD], as it
     *  plays where the gate gives a token exactly at stage 5 ([playsAt]) — see the [Plays] form. */
    fun timing(e: ScrollEffect, l: Layout, listD: Int, bandD: Int, motion: MainStaging.ScrollMotion, stage5: Boolean): Pair<Int, Int> =
        timing(playsAt(e, stage5), l, listD, bandD, motion, stage5)

    /** What plays' ([p]) period (OS ticks) and steps for [motion]: on [areaPx] pixels a step, C2's fit
     *  ([MainStaging.timing]) — or, C3 FF, with EFFECTS in force ([stage5]: every fire then runs the fast DRAW path,
     *  `FIRMWARE.md` §7.12), the fast path's ([MainStaging.fastStepMs] of the area, [walkBytes] and, DM-1, [pixelPx]) — on
     *  K1-1's rule ([MainStaging.timingOf]); one step for None (at the fitted period: the swap at once). */
    fun timing(p: Plays, l: Layout, listD: Int, bandD: Int, motion: MainStaging.ScrollMotion, stage5: Boolean): Pair<Int, Int> {
        val e = p.effect
        val area = areaPx(e, l, listD, bandD)
        val m = if (e == ScrollEffect.NONE) motion.copy(frames = 0) else motion
        val pt = if (stage5) MainStaging.timingOf(MainStaging.fastStepMs(area, walkBytes(e, l), pixelPx(p, l, listD, bandD)), m) else MainStaging.timing(area, m)
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

    /** C3 DM-1, Flip's phase-1 table over [n] steps (`FIRMWARE.md` §5.4: E[1..n], E[n] = 4096): E[j] = round(4096 ×
     *  (1 − cos(π j / 2n))) — the width wF − (wF − 1) × E / 4096 then goes as wF × cos θ for a turn at constant speed. */
    fun flipOut(n: Int): IntArray = IntArray(n) { i -> Math.round(4096.0 * (1.0 - kotlin.math.cos(Math.PI * (i + 1) / (2.0 * n)))).toInt() }

    /** C3 DM-1, Flip's phase-2 table over [n] steps: E[j] = round(4096 × sin(π j / 2n)) — the width from one column back
     *  to wF as the rest of the turn. */
    fun flipIn(n: Int): IntArray = IntArray(n) { i -> Math.round(4096.0 * kotlin.math.sin(Math.PI * (i + 1) / (2.0 * n))).toInt() }

    /** C3 X1DM1 (decisions BP (2)), Flip's width turn on the rows' ease over [t] steps: the band edge-on at [halfStep] n1,
     *  the angle θ the rows' eased progress (E_r their ease-out over t) — phase 1 E[j] = round(4096 × (1 − cos(π/2 × E_r[j]
     *  / E_r[n1]))), phase 2 E[i] = round(4096 × sin(π/2 × (E_r[n1 + i] − E_r[n1]) / (4096 − E_r[n1]))) — so the drawn width
     *  goes as 1 + (wF − 1) × |cos θ| with θ from 0 to π over the rows' travel (pinned within a pixel). E[n] = 4096 each. */
    fun flipOnRows(t: Int): Pair<IntArray, IntArray> {
        val eo = Motion.easeE(Motion.Ease.EaseOut, t)
        val n1 = halfStep(t)
        val out = IntArray(n1) { i -> Math.round(4096.0 * (1.0 - kotlin.math.cos(Math.PI / 2 * eo[i + 1] / eo[n1]))).toInt() }
        val inn = IntArray(t - n1) { i -> Math.round(4096.0 * kotlin.math.sin(Math.PI / 2 * (eo[n1 + i + 1] - eo[n1]) / (4096 - eo[n1]))).toInt() }
        return out to inn
    }

    /** The ease tables what plays uploads at [t] steps (placed by the caller before its programs, in this order): Fall's
     *  one; Flip's two (phase 1's, phase 2's: [flipOnRows] for the width turn, [flipOut] and [flipIn] for the height
     *  turn); Crush's two ([easeOutParts]: E1, E2); none for the rest. */
    fun tables(p: Plays, t: Int): List<IntArray> = when (p.effect) {
        ScrollEffect.FALL -> listOf(fallTable(t))
        ScrollEffect.FLIP -> if (p.v2) flipOnRows(t).toList() else phase1(t).let { n1 -> listOf(flipOut(n1), flipIn(t - n1)) }
        ScrollEffect.CRUSH -> easeOutParts(t).toList()
        else -> emptyList()
    }

    /**
     * What plays' ([p]) pan at off4 [at4]: [kA] rows above the band and [kB] below drawn from [strip] (a strip slot or the
     * wrap's record) one notch in [step] (+1 down, −1 up) over [t] steps (one for None); [tables] are its own ([tables]
     * at [t], already placed); [fx] the gate's token for a version-2 form (Crush, Flip's width turn: refused without it).
     * Three per-lens tracks (four for Slide and Fall, five for Crush): the rows above the band and the rows below it — the
     * strip drawn twice, one pitch apart (decisions F) — and the band over both planes' x on each lens.
     */
    fun program(p: Plays, s: Staged, strip: Motion.Source.Record, kA: Int, kB: Int, step: Int, t: Int, at4: Int,
                tables: List<Motion.Ease.Table> = emptyList(), fx: Motion.Effects? = null): MotionProgram {
        val e = p.effect
        if (p.v2 && fx == null) throw LintError("§7.11: ${e.word}'s version-2 pans are built only through the gate (EFFECTS in force)")
        if (e == ScrollEffect.CRUSH) return crush(s, strip, kA, kB, step, t, at4, tables, fx!!)
        if (e == ScrollEffect.FLIP) return flip(p.v2, s, strip, kA, kB, step, t, at4, tables, fx)
        val l = s.layout
        val cx = l.content.x
        val rowW = l.content.w - Layout.RAIL_W
        val listD = s.listD; val bandD = s.bandD
        val shift = listD - bandD
        val bandW = rowW + kotlin.math.abs(shift)
        val n = if (e == ScrollEffect.NONE) 1 else t
        val ease: Motion.Ease = when (e) {
            ScrollEffect.NONE -> Motion.Ease.linear
            ScrollEffect.FALL -> tables.firstOrNull() ?: throw LintError("Fall's pans are drawn on their uploaded table")
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
                else -> throw LintError("${e.word} has a builder of its own")
            }
        }
    }

    /** The rects every Main pan draws in, per lens: the rows above the band ([aL], [aR]), below it ([bL], [bR]) and the
     *  band over both planes' x ([nL], [nR]; Depth's band rects). */
    private class Rects(l: Layout, kA: Int, kB: Int, listD: Int, bandD: Int) {
        val rowW = l.content.w - Layout.RAIL_W
        val cx = l.content.x
        val aL = Rect(cx - listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH); val aR = Rect(cx + listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH)
        val bL = Rect(cx - listD, l.lens.bottom, rowW, kB * l.rowH); val bR = Rect(cx + listD, l.lens.bottom, rowW, kB * l.rowH)
        val bandW = rowW + kotlin.math.abs(listD - bandD)
        val nL = Rect(cx - maxOf(listD, bandD), l.lens.y, bandW, l.lens.h); val nR = Rect(cx + minOf(listD, bandD), l.lens.y, bandW, l.lens.h)
    }

    /**
     * C3 DM-1, Crush (the DM spec §1.2; his words, `MOTION.md` §7; flat and sequential, decisions J): five per-lens tracks,
     * version 2, pitch +rowH — d = [step], E the entering side (the rows below for a notch down, above for up), F the far
     * side; n1 = [halfStep] steps, n2 the rest; every DRAW at levels 15; phase 1's movers (T1, T3, T4) on E1 and phase 2's
     * (T2, T5) on E2 ([tables]: [easeOutParts], placed) — the two parts of one ease-out over T (C3 X1DM1, decisions BP (1)):
     * - T1, E, level 0: the strip's window (first row ← P0, [kA] + [kB] + 2 rows down, [kA] + 1 up) at a fixed y, one pitch
     *   over n1 — then held.
     * - T2, F, level 0: held over n1 (a waiting track holds its next segment's first state: the far rows where they stand),
     *   then the same window (first row ← P0; [kA] + 1 rows down, [kA] + [kB] + 2 up) one pitch over n2.
     * - T3, the band, level 0: the leaving band form (← P2) at the band's plane squashed from the band's height to one pixel
     *   row against the far edge (anchor top for a notch down, bottom for up) over n1 — then held (a sliver T4 covers).
     * - T4, the band, backdrop none (inside T3): the entering band form (← P1) slid in from the entering side, the band's
     *   height over n1, its far edge meeting T3's block at every step (pinned) — at rest over the whole band.
     * - T5, F, backdrop none (inside T2): row c's own row (window ← P3, one row) parked unscaled outside F over n1, then
     *   grown from one pixel row to a row at the band's edge (anchor bottom for a notch down, top for up) over n2, covering
     *   every row T2 brings into F (pinned).
     * At step T every track rests where Main's own paint at c' has it: END = Main's paint (pinned at every cursor).
     */
    private fun crush(s: Staged, strip: Motion.Source.Record, kA: Int, kB: Int, step: Int, t: Int, at4: Int,
                      tables: List<Motion.Ease.Table>, fx: Motion.Effects): MotionProgram {
        val l = s.layout
        val r = Rects(l, kA, kB, s.listD, s.bandD)
        val cx = r.cx; val dL = s.listD; val dB = s.bandD; val rowH = l.rowH
        val n1 = halfStep(t); val n2 = t - n1
        val down = step > 0
        val (eL, eR) = if (down) r.bL to r.bR else r.aL to r.aR
        val (fL, fR) = if (down) r.aL to r.aR else r.bL to r.bR
        if (tables.size != 2) throw LintError("Crush's phases run on their two uploaded tables (one ease-out split at half its travel)")
        val (e1, e2) = tables[0] to tables[1]
        val window = listOf(Motion.Pref(0, Motion.Field.AUX0))
        val leaving = listOf(Motion.Pref(3, Motion.Field.AUX0))
        return Motion.program2(at4, fx, records = s.records) {
            track(eL, eR, Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - dL, xR = cx + dL, y = if (down) l.lens.bottom - (kA + 1) * rowH else l.lens.y - (kA + 1) * rowH,
                    dy = -step * rowH, ease = e1, n = n1, windowRows = (if (down) kA + kB + 2 else kA + 1) * rowH, prefs = window)
                wait(n2)
            }
            track(fL, fR, Motion.Backdrop.Level(0)) {
                wait(n1)
                draw(strip, x = cx - dL, xR = cx + dL, y = if (down) l.lens.y - kA * rowH else l.lens.bottom - (kA + 2) * rowH,
                    dy = -step * rowH, ease = e2, n = n2, windowRows = (if (down) kA + 1 else kA + kB + 2) * rowH, prefs = window)
            }
            track(r.nL, r.nR, Motion.Backdrop.Level(0)) {
                draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y, h0 = l.lens.h, h1 = 1, anchor = if (down) 0 else 2,
                    ease = e1, n = n1, prefs = listOf(Motion.Pref(2, Motion.Field.SRC)))
                wait(n2)
            }
            track(r.nL, r.nR, Motion.Backdrop.None) {
                draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y + step * l.lens.h, dy = -step * l.lens.h, ease = e1, n = n1,
                    prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
                wait(n2)
            }
            track(fL, fR, Motion.Backdrop.None) {
                draw(strip, x = cx - dL, xR = cx + dL, y = if (down) l.lens.y else l.lens.bottom - rowH, n = n1, windowRows = rowH, prefs = leaving)
                draw(strip, x = cx - dL, xR = cx + dL, y = if (down) l.lens.y - rowH else l.lens.bottom, h0 = 1, h1 = rowH,
                    anchor = if (down) 2 else 0, ease = e2, n = n2, windowRows = rowH, prefs = leaving)
            }
        }
    }

    /**
     * C3 DM-1, Flip (the DM spec §1.3; decisions AD's D2): the rows as Depth's (T1 above, y ← P0; T2 below, y ← P3; ease 1
     * over T), and the band (level 0) turning about its vertical axis at the band's plane — the leaving form (← P2) from
     * its own width wF to one column at the band's centre over phase 1, then the entering one (← P1) from one column back
     * to wF over phase 2, on its two tables ([tables], placed). [v2]: the width (version 2, stage 5), C3 X1DM1: split at
     * [halfStep] on [flipOnRows], so the turn follows the rows; else the same turn on the height (version 1, C2's vertical
     * scale, anchor centre) split at [phase1] on [flipOut] and [flipIn] — Flip's fallback, its bytes unchanged. END: the
     * second segment ends at the source's own size, column c of source column c — Depth's END.
     */
    private fun flip(v2: Boolean, s: Staged, strip: Motion.Source.Record, kA: Int, kB: Int, step: Int, t: Int, at4: Int,
                     tables: List<Motion.Ease.Table>, fx: Motion.Effects?): MotionProgram {
        val l = s.layout
        val r = Rects(l, kA, kB, s.listD, s.bandD)
        val cx = r.cx; val dL = s.listD; val dB = s.bandD
        val n1 = if (v2) halfStep(t) else phase1(t); val n2 = t - n1
        if (tables.size != 2) throw LintError("Flip's band turns on its two uploaded tables")
        val ease = Motion.Ease.easeOut
        val build: Motion.ProgramBuilder.() -> Unit = {
            track(r.aL, r.aR, Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - dL, xR = cx + dL, y = 0, dy = -step * l.rowH, ease = ease, n = t, prefs = listOf(Motion.Pref(0, Motion.Field.Y)))
            }
            track(r.bL, r.bR, Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - dL, xR = cx + dL, y = 0, dy = -step * l.rowH, ease = ease, n = t, prefs = listOf(Motion.Pref(3, Motion.Field.Y)))
            }
            track(r.nL, r.nR, Motion.Backdrop.Level(0)) {
                if (v2) {
                    draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y, w0 = s.band.w, w1 = 1, hAnchor = 1, ease = tables[0], n = n1,
                        prefs = listOf(Motion.Pref(2, Motion.Field.SRC)))
                    draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y, w0 = 1, w1 = s.band.w, hAnchor = 1, ease = tables[1], n = n2,
                        prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
                } else {
                    draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y, h0 = l.lens.h, h1 = 1, anchor = 1, ease = tables[0], n = n1,
                        prefs = listOf(Motion.Pref(2, Motion.Field.SRC)))
                    draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y, h0 = 1, h1 = l.lens.h, anchor = 1, ease = tables[1], n = n2,
                        prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
                }
            }
        }
        return if (v2) Motion.program2(at4, fx ?: throw LintError("§7.11: Flip's width turn is built only through the gate"), records = s.records, build = build)
            else Motion.program(at4, s.records, bit10 = true, build = build)
    }
}
