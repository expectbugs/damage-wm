package wm.damage.core.motion

import wm.damage.core.geom.Layout
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.shell.ContentKit
import wm.damage.core.shell.ListStaging
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.TextureCache

/** K1-1: the Scroll tier's effects (`MOTION.md` §7: one effect, no randomness), as `ShellSettings.scrollEffect` stores
 *  them ([id]) and the `Scroll effect` row shows them ([word]), in the order his plan gives them (None first). C3 DM-1:
 *  Flip and Crush, version-2 programs at stage 5 with their fallbacks below it ([ScrollEffects.plays]). C3 DM-2a: Matrix,
 *  version 2 on Main at stage 5, its records part of the staging ([ScrollEffects.hasRecords]); Depth below it and on a
 *  window's list. C3 DM-2b: Scramble, the same on Main (its cells and private faces the staging's records). The row
 *  follows this order (no ordinal is stored or sent: the settings keep [id]). Glitch is not built as a scroll effect. */
enum class ScrollEffect(val id: String, val word: String) {
    NONE("none", "None"), DEPTH("depth", "Depth"), SLIDE("slide", "Slide"), FLIP("flip", "Flip"), FALL("fall", "Fall"), CRUSH("crush", "Crush"),
    SCRAMBLE("scramble", "Scramble"), MATRIX("matrix", "Matrix");

    companion object {
        val DEFAULT = DEPTH
        fun byId(id: String): ScrollEffect? = entries.firstOrNull { it.id == id }
    }
}

/** K1-2 stage 2 (the K1-2 spec §2.1, §4; decisions BC O3): what a list's staging holds, so what its programs draw from.
 *  [MAIN_BANDS] — Main's: the strip, each row's band form, the item table, the press (P1 and P2 name band forms by kind 1);
 *  [STRIP_WINDOWS] — a window's list: the strip and its pan slots alone (no band forms, no item table, no press, no wrap
 *  record), every track drawing the strip as source windows at pitch +rowH (`FIRMWARE.md` §7.5), the band's track the
 *  entering row's ROW form at the band area's first line (its real band arrives one held flush after, O7). */
enum class ListForm { MAIN_BANDS, STRIP_WINDOWS }

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
 * [ListStaging.timing]; at stage 5 the fast DRAW path's, [timing]).
 *
 * - [ScrollEffect.NONE]: one step — the rows at their new place and the entering row's band form in the band.
 * - [ScrollEffect.DEPTH]: today's (stage DM0, decisions V; the programs of APK 0.82 byte for byte): the rows above and
 *   below the band pan one pitch with ease-out; in the band the leaving form goes back to the list's plane and dims over
 *   the swap ([ListStaging.swapOf]), then the entering one comes forward and brightens.
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
 *   first rows (`FIRMWARE.md` §7.5), the strip drawn as windows at fixed y's. Phase 1 ([halfStep] steps): the entering
 *   side's rows move one pitch and the entering band form slides into the band, squashing the leaving one to one pixel
 *   row against the band's far edge, the far side's rows still; phase 2: the far side's rows move one pitch and the
 *   leaving row grows from one pixel row to a row, as its row picture. Each phase's movers share one ease and one step
 *   count; C3 X1DM1 (decisions BP (1)): the two phases are the two parts of ONE ease-out over T, split at the step where
 *   it is nearest half its travel ([halfStep], C3 X2DM1a; the tables `Wheel.swapTables`' arithmetic, the one copy), so
 *   the motion hands from one side to the other at speed instead of easing out to a near stop between them. Below stage
 *   5, or when the offline check refuses it, Slide plays.
 * - [ScrollEffect.FLIP]: the rows as Depth's; the band turns about its vertical axis — the leaving form narrows to one
 *   column at the band's centre over phase 1, the entering one widens from one column over phase 2, each on its table.
 *   Version 2's width scale at stage 5, C3 X1DM1 (decisions BP (2), his "what moves, moves together"): the turn follows
 *   the rows — edge-on at the step where the rows' ease-out is nearest half their travel ([halfStep]), the angle the rows'
 *   eased progress ([flipOnRows]). Below stage 5 (or refused) the turn on the height ([Plays.v2] false: C2's vertical
 *   scale, a version-1 record) at constant speed, split at [phase1] ([flipOut], [flipIn]; its bytes as DM-1 built them).
 * Both are functions of the layout, the planes, the strip's margin and the step alone (K1-2 moves them to window lists:
 * the band forms, P1 and P2 by kind 1, are what is Main's).
 */
object ScrollEffects {
    /** Fall's overshoot at step T − 1, in percent of the travel (a pitch for the rows, the band's height for the band). */
    const val FALL_OVERSHOOT_PCT = 6

    /** What is staged that every pan draws from: the list's [layout], its plane [listD] and its band's [bandD], a band
     *  form's record (any: P1 and P2 name the real one; none on a window list), [records] every record a parameter may
     *  name, and the staging's [form] (K1-2 stage 2). */
    class Staged(val layout: Layout, val listD: Int, val bandD: Int, private val band0: Motion.Source.Record?, val records: List<Motion.Source.Record>,
                 val form: ListForm = ListForm.MAIN_BANDS,
                 /** C3 DM-2a, DM-2b: the effect's records where the staging holds them ([EffectRecords]: Matrix's rain,
                  *  Scramble's cells); null otherwise. */
                 val recs: EffectRecords? = null) {
        val band: Motion.Source.Record get() = band0 ?: throw LintError("a window list stages no band forms (the K1-2 spec §2.1: the strip alone)")
    }

    /**
     * C3 DM-1 (D3 as ruled, decisions AD): what one notch draws for an asked effect — [effect]'s own program, of version 2
     * where [v2] (Crush; Flip's width turn), or its fallback: Slide for Crush, Flip's height turn (version 1) for Flip.
     * Every other effect plays itself in version 1. [why] says why a fallback plays (null when the effect plays itself).
     */
    data class Plays(val effect: ScrollEffect, val v2: Boolean, val why: String? = null) {
        /** The words the `Scroll effect` row adds for [asked] when this is not its own program ("" when it is). */
        fun words(asked: ScrollEffect): String = when {
            asked == ScrollEffect.FLIP && effect == ScrollEffect.FLIP -> if (v2) "" else "plays its height turn"
            asked != effect -> "plays ${effect.word}"
            else -> ""
        }
    }

    /** What plays for [asked] with the gate's token [fx] (null: no version 2 here — below stage 5, or a build without
     *  DamageCaps bit 11). The offline check at every cursor may still refuse a version-2 form: [fallback] then. */
    fun plays(asked: ScrollEffect, fx: Motion.Effects?): Plays = playsAt(asked, fx != null)

    /** The same, by whether the gate gives a token ([v2Here]: EFFECTS in force) — for what is said or fitted before a build. */
    fun playsAt(asked: ScrollEffect, v2Here: Boolean): Plays = when {
        asked != ScrollEffect.CRUSH && asked != ScrollEffect.FLIP && asked != ScrollEffect.MATRIX && asked != ScrollEffect.SCRAMBLE -> Plays(asked, false)
        v2Here -> Plays(asked, true)
        else -> fallback(asked, NOT_STAGE5)
    }

    /** Why a fallback plays below stage 5. */
    const val NOT_STAGE5 = "EFFECTS is not in force (version 2 only at ladder stage 5 on a DamageCaps bit-11 build)"

    /** D3: Crush → Slide, Scramble and Matrix → Depth (decisions AD's D3), Flip → its height turn (version 1); [why] said. */
    fun fallback(asked: ScrollEffect, why: String): Plays = when (asked) {
        ScrollEffect.CRUSH -> Plays(ScrollEffect.SLIDE, false, why)
        ScrollEffect.MATRIX, ScrollEffect.SCRAMBLE -> Plays(ScrollEffect.DEPTH, false, why)
        else -> Plays(asked, false, why)
    }

    /** C3 DM-2a, the records rule (the DM-2 spec §1; decisions DV 2, DW 1–4): whether what plays as [e] draws records that
     *  are part of the list's staging ([EffectRecords]: Matrix's rain, C3 DM-2b Scramble's cells) — laid last in it, given
     *  up first where it does not fit, and changed only by a full staging (a programs-only re-make into or out of such an
     *  effect is refused, and one whose pans come back refused, `ListStaging.remake`). */
    fun hasRecords(e: ScrollEffect): Boolean = e == ScrollEffect.MATRIX || e == ScrollEffect.SCRAMBLE

    /** K1-2 stage 2: what plays for [asked] on a staging of [form]. Main's: [plays]. A window list's (stage 5 only: its
     *  programs are version 2, source windows, so [fx] is never null there): the effects of [WINDOW_EFFECTS] themselves;
     *  C3 DM-2a (the DM-2 spec §1, decisions DR (j)): every other is not built on the window form yet — Crush plays Slide,
     *  as its fallback does on Main, and any other Depth, each said ([windowNotYet]), so no effect outside the form's own
     *  reaches its builder (which refuses one). */
    fun playsFor(asked: ScrollEffect, form: ListForm, fx: Motion.Effects?): Plays = when {
        form == ListForm.MAIN_BANDS -> plays(asked, fx)
        asked in WINDOW_EFFECTS -> Plays(asked, true)
        asked == ScrollEffect.CRUSH -> Plays(ScrollEffect.SLIDE, true, windowNotYet(asked))
        else -> Plays(ScrollEffect.DEPTH, true, windowNotYet(asked))
    }

    /** Why [asked] plays another effect on a window list. */
    fun windowNotYet(asked: ScrollEffect): String =
        "${asked.word} is not built on a window list's form yet (it plays ${WINDOW_EFFECTS.joinToString { it.word }} itself; the others come later)"

    /** The effects a window list plays itself ([playsFor]): its pan slots are sized by the largest of them. */
    val WINDOW_EFFECTS = listOf(ScrollEffect.NONE, ScrollEffect.DEPTH, ScrollEffect.SLIDE, ScrollEffect.FALL)

    /** The list's pitch for what plays (decisions AD's S6): +rowH for Crush (its kind-2 values are source-window first
     *  rows, `FIRMWARE.md` §7.5), −rowH for every other form (the strips' start y). */
    fun pitch(p: Plays, l: Layout, form: ListForm = ListForm.MAIN_BANDS): Int =
        if (form == ListForm.STRIP_WINDOWS || p.effect == ScrollEffect.CRUSH && p.v2) l.rowH else -l.rowH

    /** The four parameters' rules (`FIRMWARE.md` §6.2 step 9; c' the new cursor, [marginA] the strip's rows before row 0 —
     *  k, or k + 1 on a ring strip, C3 DM-0): P0 lens.y − (c + marginA) × rowH and P3 lens.bottom − (c + 1 + marginA) ×
     *  rowH (kind 2, c = c' − step), P1 the entering row's band form and P2 the leaving row's (kind 1). The same for every
     *  effect of pitch −rowH. */
    fun rules(l: Layout, marginA: Int, step: Int): List<ListStaging.Rule> = listOf(
        ListStaging.Rule(CfwModes.KIND_PITCH, l.lens.y + (step - marginA) * l.rowH),
        ListStaging.Rule(CfwModes.KIND_ITEM, 0),
        ListStaging.Rule(CfwModes.KIND_ITEM, -step),
        ListStaging.Rule(CfwModes.KIND_PITCH, l.lens.bottom + (step - 1 - marginA) * l.rowH))

    /** C3 DM-1: [p]'s rules. Crush (pitch +rowH, the DM spec §1.2): P0 the strip window both row tracks share — its first
     *  strip row, Main's row c' − k − 1 (a notch down) or c' − k (up): (M − k − 1) × rowH, resp. (M − k) × rowH, with M
     *  [marginA] and k the pan's rows above ([kA]); P1 the entering band form, P2 the leaving one (kind 1); P3 row c's own row in the
     *  strip, (M − step) × rowH. On a ring list kinds 1 and 2 take the virtual row (§7.8). Every other form: [rules]. */
    fun rules(p: Plays, form: ListForm, l: Layout, marginA: Int, step: Int, kA: Int): List<ListStaging.Rule> = when {
        // K1-2 stage 2 (the K1-2 spec §2.1): the window form at pitch +rowH — P0 the strip window both row tracks share,
        // its first row Main's formula for Crush ((M − k − 1) × rowH down, (M − k) × rowH up; window kA + kB + 2 rows: down
        // [c' − 1 − kA, c' + kB], up [c' − kA, c' + kB + 1]), P1 the entering row c' (M × rowH, one row), P2 the leaving
        // row c' − step ((M − step) × rowH), P3 kind 0 (free: Crush on this form takes it later). On a ring list kind 2
        // takes the virtual row (§7.8), and the margins hold every window at v = −1 and v = n
        form == ListForm.STRIP_WINDOWS -> listOf(
            ListStaging.Rule(CfwModes.KIND_PITCH, (marginA - kA - (if (step > 0) 1 else 0)) * l.rowH),
            ListStaging.Rule(CfwModes.KIND_PITCH, marginA * l.rowH),
            ListStaging.Rule(CfwModes.KIND_PITCH, (marginA - step) * l.rowH),
            ListStaging.Rule(CfwModes.KIND_ARG, 0))
        // C3 DM-2b: Scramble's P2 is the cursor before the move (kind 3, `FIRMWARE.md` §7.8): its cell tables' index
        p.effect == ScrollEffect.SCRAMBLE && p.v2 -> rules(l, marginA, step).let { listOf(it[0], it[1], ListStaging.Rule(CfwModes.KIND_CURSOR, 0), it[3]) }
        p.effect != ScrollEffect.CRUSH || !p.v2 -> rules(l, marginA, step)
        else -> listOf(
            ListStaging.Rule(CfwModes.KIND_PITCH, (marginA - kA - (if (step > 0) 1 else 0)) * l.rowH),
            ListStaging.Rule(CfwModes.KIND_ITEM, 0),
            ListStaging.Rule(CfwModes.KIND_ITEM, -step),
            ListStaging.Rule(CfwModes.KIND_PITCH, (marginA - step) * l.rowH))
    }

    /** Flip's height turn's split of [t] steps (version 1, below stage 5 or refused): phase 1 takes ⌈t / 2⌉ (the leaving
     *  form turned to its edge), phase 2 the rest. Crush and Flip's width turn split at [halfStep] (C3 X1DM1). */
    fun phase1(t: Int): Int = (t + 1) / 2

    /** C3 X1DM1 (decisions BP (1)), X2DM1a (CA (3)): the step at which one ease-out over [t] steps (`FIRMWARE.md` §5.4:
     *  E[j] = 4096 − ⌊4096 (t − j)² / t²⌋) is NEAREST half its travel, |E[j] − 2048| least over 1..t − 1 (the earlier of
     *  two equal; no two are equal at 3..51 steps) — where Crush's second phase starts and Flip's width turn is edge-on.
     *  The first step past half made the rows move faster after the hand-off than before it at 4, 5, 7, 8 and 14 steps
     *  (2–11 px a step); at 19 and 17 steps the two are the same step. The wheel's swap keeps the first step past half
     *  (`Wheel.swapTables`' own split: it would move at 4, 8, 14, 28 and 38 steps, which the wheel can get). */
    fun halfStep(t: Int): Int {
        if (t < 2) throw LintError("an ease-out split at half its travel needs two steps or more, not $t")
        val eo = Motion.easeE(Motion.Ease.EaseOut, t)
        return (1 until t).minBy { kotlin.math.abs(eo[it] - 2048) }
    }

    /** Band tracks an effect paints (each over the band's whole area). */
    private fun bandTracks(e: ScrollEffect) = if (e == ScrollEffect.SLIDE || e == ScrollEffect.FALL || e == ScrollEffect.CRUSH) 2 else 1

    /** The pixels one step of [e]'s whole-list pan paints per lens at [l] with the list at [listD] and the band at
     *  [bandD] — every track's area, what its period is fitted on. Crush (its version-2 form, the DM spec §1.6's model):
     *  its two row tracks, its two band tracks, and the leaving row's one row (its track paints only what it draws:
     *  backdrop none). Flip: Depth's area (the turn is the band track's). */
    fun areaPx(e: ScrollEffect, l: Layout, listD: Int, bandD: Int, form: ListForm = ListForm.MAIN_BANDS): Int {
        val rowW = l.content.w - Layout.RAIL_W
        // K1-2 stage 2: a window list's band tracks leave the band's two rules out of their area
        val bandH = if (form == ListForm.STRIP_WINDOWS) l.lens.h - 2 * ContentKit.LENS_RULE else l.lens.h
        return (l.rowsAbove + l.rowsBelow) * l.rowH * rowW + bandTracks(e) * bandH * (rowW + kotlin.math.abs(listD - bandD)) +
            (if (e == ScrollEffect.CRUSH) l.rowH * rowW else 0) +
            // C3 DM-2a (decisions DW 5: every track's area): Matrix's T0 and its rain, each over the list's whole area
            (if (e == ScrollEffect.MATRIX) 2 * rowW * rainH(l) else 0) +
            // C3 DM-2b: Scramble's cell tracks — one over each row area, two over the band
            (if (e == ScrollEffect.SCRAMBLE) (l.rowsAbove + l.rowsBelow) * l.rowH * rowW + 2 * bandH * (rowW + kotlin.math.abs(listD - bandD)) else 0)
    }

    /** The bytes one step of [e]'s pan decodes at [l] on the fast DRAW path (`FIRMWARE.md` §7.12: each DRAW of a record
     *  decodes from its kept start to the last source row its output inside the area takes): k + 1 strip rows for each
     *  of the two row tracks (a step between two places shows parts of k + 1 rows) and one band form per band track
     *  (Depth's two band segments draw one at a time); Crush's leaving row one row more. Rows and forms at the DM spec's
     *  §1.6 sizes ([ListStaging.FAST_ROW_BYTES], [ListStaging.FAST_BAND_FORM_BYTES]; M-PC). */
    fun walkBytes(e: ScrollEffect, l: Layout, form: ListForm = ListForm.MAIN_BANDS): Int =
        (l.rowsAbove + 1 + l.rowsBelow + 1 + (if (e == ScrollEffect.CRUSH) 1 else 0)) * ListStaging.FAST_ROW_BYTES +
            // K1-2 stage 2: a window list's band track draws one strip row (its row form), not a band form
            bandTracks(e) * (if (form == ListForm.STRIP_WINDOWS) ListStaging.FAST_ROW_BYTES else ListStaging.FAST_BAND_FORM_BYTES)

    /** The pixels one step of what plays writes a pixel at a time on the fast DRAW path (`FIRMWARE.md` §7.12: "every draw
     *  whose width scales writes a pixel at a time"): Flip's width turn, its band's area; none for the rest (Crush's
     *  scaled heights draw from the row buffer with their width unscaled: two pixels a byte). */
    fun pixelPx(p: Plays, l: Layout, listD: Int, bandD: Int): Int =
        if (p.effect == ScrollEffect.FLIP && p.v2) l.lens.h * (l.content.w - Layout.RAIL_W + kotlin.math.abs(listD - bandD)) else 0

    /** C3 DM-2a (the DM-2 spec §1, "the fit"; the DM spec §1.6's assumption): the rain cells one step of [e] lights at [l]
     *  — every column's whole trail ([rainCols] × [RAIN_TRAIL]), Matrix's alone; none for the rest. */
    fun rainCells(e: ScrollEffect, l: Layout): Int = if (e == ScrollEffect.MATRIX) rainCols(l) * RAIN_TRAIL else 0

    /** C3 DM-2a: the rain's columns over Main's rows (⌈rowW / [RAIN_CW]⌉; the last one clipped to the area, §7.6). */
    fun rainCols(l: Layout): Int = (l.content.w - Layout.RAIL_W + RAIN_CW - 1) / RAIN_CW

    /** C3 DM-2a: the height of Main's whole list area at [l] — the rows above the band, the band, the rows below — where
     *  Matrix's T0 and its rain lie (the DM spec's W). */
    fun rainH(l: Layout): Int = (l.rowsAbove + l.rowsBelow) * l.rowH + l.lens.h

    /** [e]'s period (OS ticks) and steps for [motion] at Main's [l] with the list at [listD] and the band at [bandD], as it
     *  plays where the gate gives a token exactly at stage 5 ([playsAt]) — see the [Plays] form. */
    fun timing(e: ScrollEffect, l: Layout, listD: Int, bandD: Int, motion: ListStaging.ScrollMotion, stage5: Boolean): Pair<Int, Int> =
        timing(playsAt(e, stage5), l, listD, bandD, motion, stage5)

    /** What plays' ([p]) period (OS ticks) and steps for [motion]: on [areaPx] pixels a step, C2's fit
     *  ([ListStaging.timing]) — or, C3 FF, with EFFECTS in force ([stage5]: every fire then runs the fast DRAW path,
     *  `FIRMWARE.md` §7.12), the fast path's ([ListStaging.fastStepMs] of the area, [walkBytes], DM-1 [pixelPx] and, DM-2a,
     *  [rainCells]) — on
     *  K1-1's rule ([ListStaging.timingOf]); one step for None (at the fitted period: the swap at once). */
    fun timing(p: Plays, l: Layout, listD: Int, bandD: Int, motion: ListStaging.ScrollMotion, stage5: Boolean,
               form: ListForm = ListForm.MAIN_BANDS,
               /** C3 DM-2b: the cells a step of Scramble's draws ([ScrambleText.cellsPerStep]); read for Scramble alone. */
               cells: Int = 0): Pair<Int, Int> {
        val e = p.effect
        val area = areaPx(e, l, listD, bandD, form)
        val m = if (e == ScrollEffect.NONE) motion.copy(frames = 0) else motion
        val pt = if (stage5) ListStaging.timingOf(ListStaging.fastStepMs(area, walkBytes(e, l, form), pixelPx(p, l, listD, bandD), rainCells(e, l),
            if (e == ScrollEffect.SCRAMBLE) cells else 0), m) else ListStaging.timing(area, m)
        return if (e == ScrollEffect.NONE) pt.first to 1 else pt
    }

    /** Under Auto, the steps of [motion]'s length at the period in force [period] (decisions BF's measured period: K1-1
     *  item 4 — the steps follow it); a fixed count and None keep theirs. */
    fun stepsAt(motion: ListStaging.ScrollMotion, period: Int, t: Int): Int =
        if (motion.effect == ScrollEffect.NONE || motion.frames > 0) t
        else kotlin.math.floor(motion.lengthMs * 1024.0 / 1000.0 / period).toInt().coerceAtLeast(ListStaging.MIN_STEPS)

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
     *  turn); Crush's two — one ease-out over [t] steps split at [halfStep] n1 (C3 X1DM1, decisions BP (1)): E1 its first
     *  n1 steps rescaled to end at 4096, E2 its last t − n1 rescaled from 0, so a mover that goes the first part of a travel
     *  on E1 and the rest on E2 is where one ease-out over t puts it, within a pixel (pinned); C3 X2DM1a (decisions CA (4)):
     *  `Wheel.swapTables`' arithmetic, the one copy, at the scroll effects' split. E2 is kept, not the built-in ease-out
     *  over n2 (equal within 2/4096, but a row is drawn a pixel apart at 10, 12, 34 and 45 steps). None for the rest. */
    fun tables(p: Plays, t: Int): List<IntArray> = when (p.effect) {
        ScrollEffect.FALL -> listOf(fallTable(t))
        ScrollEffect.FLIP -> if (p.v2) flipOnRows(t).toList() else phase1(t).let { n1 -> listOf(flipOut(n1), flipIn(t - n1)) }
        ScrollEffect.CRUSH -> Wheel.swapTables(t, halfStep(t)).toList()
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
        if (s.form == ListForm.STRIP_WINDOWS) return windowPan(e, s, strip, kA, kB, step, t, at4, tables,
            fx ?: throw LintError("§7.11: a window list's pans are version 2 (source windows), built only through the gate (EFFECTS in force)"))
        if (e == ScrollEffect.CRUSH) return crush(s, strip, kA, kB, step, t, at4, tables, fx!!)
        if (e == ScrollEffect.MATRIX) return matrix(s, strip, kA, kB, step, t, at4, fx ?: throw LintError("§7.11: Matrix's pans are version 2, built only through the gate (EFFECTS in force)"))
        if (e == ScrollEffect.SCRAMBLE) return scramble(s, strip, kA, kB, step, t, at4, fx ?: throw LintError("§7.11: Scramble's pans are version 2, built only through the gate (EFFECTS in force)"))
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
                    val swap = ListStaging.swapOf(t)
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

    /**
     * K1-2 stage 2 (the K1-2 spec §2.1; decisions BC O3, O7): a window list's pan of [e] — version 2, every track drawing
     * [strip] as a source window (`FIRMWARE.md` §7.5) at pitch +rowH ([rules]' window form), so one program serves every
     * cursor, and the step's cost does not grow with the list (§7.12, the window form's row):
     * - the rows above the band (area as Main's, level 0): the shared window (first row ← P0, [kA] + [kB] + 2 rows) at a
     *   fixed y, one pitch of travel in the notch's direction;
     * - the rows below it (area as Main's, level 0): the same window drawn the band's height less a row lower — the band
     *   is two pitches tall and holds one row, as Main's pan places its two halves;
     * - the band, over both planes' x as Main's, its area the band LESS its two rules ([ContentKit.LENS_RULE]) so they never
     *   flicker (O7): one strip row ← P2 (the leaving row) and ← P1 (the entering row) in their ROW form, drawn at the area's
     *   first line ([BAND_ROW_DY] below the band's top). Depth: Main's swap (the leaving row goes back to the list's plane
     *   and dims, then the entering one comes forward and brightens); Slide and Fall: two band tracks travelling the band's
     *   height (Main's two speeds); None: one step, the entering row at once.
     * END = the window's own paint at c' in the rows' areas, and in the band's area the entering row's row form at its first
     * line (pinned at every cursor both ways, four heights, ring and plain); the real band follows one held flush after.
     */
    private fun windowPan(e: ScrollEffect, s: Staged, strip: Motion.Source.Record, kA: Int, kB: Int, step: Int, t: Int, at4: Int,
                          tables: List<Motion.Ease.Table>, fx: Motion.Effects): MotionProgram {
        if (e !in WINDOW_EFFECTS) throw LintError("${e.word} has no window-list form yet (${WINDOW_EFFECTS.joinToString { it.word }} only)")
        val l = s.layout
        val r = Rects(l, kA, kB, s.listD, s.bandD)
        val cx = r.cx; val dL = s.listD; val dB = s.bandD; val rowH = l.rowH
        val shift = dL - dB
        val n = if (e == ScrollEffect.NONE) 1 else t
        val ease: Motion.Ease = when (e) {
            ScrollEffect.NONE -> Motion.Ease.linear
            ScrollEffect.FALL -> tables.firstOrNull() ?: throw LintError("Fall's pans are drawn on their uploaded table")
            else -> Motion.Ease.easeOut
        }
        val window = listOf(Motion.Pref(0, Motion.Field.AUX0))
        val rows = (kA + kB + 2) * rowH
        // the shared window's first row at its first state: a notch down shows its rows 0 .. kA − 1 above the band (Main's
        // rows c − kA .. c − 1), a notch up its rows 1 .. kA
        val y1 = l.lens.y - kA * rowH - (if (step < 0) rowH else 0)
        val bandY = l.lens.y + BAND_ROW_DY
        return Motion.program2(at4, fx, records = s.records) {
            track(r.aL, r.aR, Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - dL, xR = cx + dL, y = y1, dy = -step * rowH, ease = ease, n = n, windowRows = rows, prefs = window)
            }
            track(r.bL, r.bR, Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - dL, xR = cx + dL, y = y1 + l.lens.h - rowH, dy = -step * rowH, ease = ease, n = n, windowRows = rows, prefs = window)
            }
            val leaving = listOf(Motion.Pref(2, Motion.Field.AUX0))
            val entering = listOf(Motion.Pref(1, Motion.Field.AUX0))
            when (e) {
                ScrollEffect.DEPTH -> track(r.wL, r.wR, Motion.Backdrop.Level(0)) {
                    val swap = ListStaging.swapOf(t)
                    draw(strip, x = cx - dB, xR = cx + dB, y = bandY, dx = -shift, dxR = shift, lvl0 = 15, lvl1 = MainPan.LVL_BACK, ease = ease, n = swap,
                        windowRows = rowH, prefs = leaving)
                    draw(strip, x = cx - dL, xR = cx + dL, y = bandY, dx = shift, dxR = -shift, lvl0 = MainPan.LVL_BACK, lvl1 = 15, ease = ease, n = t - swap,
                        windowRows = rowH, prefs = entering)
                }
                ScrollEffect.NONE -> track(r.wL, r.wR, Motion.Backdrop.Level(0)) {
                    draw(strip, x = cx - dB, xR = cx + dB, y = bandY, n = 1, windowRows = rowH, prefs = entering)
                }
                else -> {
                    // Slide and Fall: the leaving row slides out of the band and the entering one in from the notch's side,
                    // one eased offset of the band's height for both (Main's two speeds, K1-1 review finding 3)
                    track(r.wL, r.wR, Motion.Backdrop.Level(0)) {
                        draw(strip, x = cx - dB, xR = cx + dB, y = bandY, dy = -step * l.lens.h, ease = ease, n = t, windowRows = rowH, prefs = leaving)
                    }
                    track(r.wL, r.wR, Motion.Backdrop.None) {
                        draw(strip, x = cx - dB, xR = cx + dB, y = bandY + step * l.lens.h, dy = -step * l.lens.h, ease = ease, n = t, windowRows = rowH, prefs = entering)
                    }
                }
            }
        }
    }

    /** K1-2 stage 2 (O7): where a window list's band track draws a row form, below the band's top — read off Settings' two
     *  paints (the first window list to bind): a category's row draws its name at row + 4 (+ 5 on the 2-px grid) in the
     *  row face, its band the title at + 8 in its bold, so at + 4 the name line lies on the title line (pinned,
     *  `ScrollEffectsTest`); Global's rows draw their value in the same face on the same line (their 13-px label sits
     *  above it). Files' rows (name + 4, band + 6) land 2 px low. Inside the band's area (it starts below the 2-px rule). */
    const val BAND_ROW_DY = 4

    /** The rects every Main pan draws in, per lens: the rows above the band ([aL], [aR]), below it ([bL], [bR]) and the
     *  band over both planes' x ([nL], [nR]; Depth's band rects). */
    private class Rects(l: Layout, kA: Int, kB: Int, listD: Int, bandD: Int) {
        val rowW = l.content.w - Layout.RAIL_W
        val cx = l.content.x
        val aL = Rect(cx - listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH); val aR = Rect(cx + listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH)
        val bL = Rect(cx - listD, l.lens.bottom, rowW, kB * l.rowH); val bR = Rect(cx + listD, l.lens.bottom, rowW, kB * l.rowH)
        val bandW = rowW + kotlin.math.abs(listD - bandD)
        val nL = Rect(cx - maxOf(listD, bandD), l.lens.y, bandW, l.lens.h); val nR = Rect(cx + minOf(listD, bandD), l.lens.y, bandW, l.lens.h)
        /** K1-2 stage 2 (O7): a window list's band area — Depth's band rects less the band's two rules. */
        val wL = Rect(nL.x, l.lens.y + ContentKit.LENS_RULE, bandW, l.lens.h - 2 * ContentKit.LENS_RULE)
        val wR = Rect(nR.x, l.lens.y + ContentKit.LENS_RULE, bandW, l.lens.h - 2 * ContentKit.LENS_RULE)
    }

    /**
     * C3 DM-1, Crush (the DM spec §1.2; his words, `MOTION.md` §7; flat and sequential, decisions J): five per-lens tracks,
     * version 2, pitch +rowH — d = [step], E the entering side (the rows below for a notch down, above for up), F the far
     * side; n1 = [halfStep] steps, n2 the rest; every DRAW at levels 15; phase 1's movers (T1, T3, T4) on E1 and phase 2's
     * (T2, T5) on E2 ([tables], placed) — the two parts of one ease-out over T (C3 X1DM1, decisions BP (1)):
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
     * At step T every track rests where Main's own paint at c' has it: END = Main's paint (pinned at every cursor). T1, T3
     * and T4 end with phase 1 and hold their last state to step T (`FIRMWARE.md` §5.4: tracks shorter than T hold to the
     * end), so they carry no trailing WAIT (C3 X1DM1, decisions BP (3): 120 B a pan).
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
            }
            track(fL, fR, Motion.Backdrop.Level(0)) {
                wait(n1)
                draw(strip, x = cx - dL, xR = cx + dL, y = if (down) l.lens.y - kA * rowH else l.lens.bottom - (kA + 2) * rowH,
                    dy = -step * rowH, ease = e2, n = n2, windowRows = (if (down) kA + 1 else kA + kB + 2) * rowH, prefs = window)
            }
            track(r.nL, r.nR, Motion.Backdrop.Level(0)) {
                draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y, h0 = l.lens.h, h1 = 1, anchor = if (down) 0 else 2,
                    ease = e1, n = n1, prefs = listOf(Motion.Pref(2, Motion.Field.SRC)))
            }
            track(r.nL, r.nR, Motion.Backdrop.None) {
                draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y + step * l.lens.h, dy = -step * l.lens.h, ease = e1, n = n1,
                    prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
            }
            track(fL, fR, Motion.Backdrop.None) {
                draw(strip, x = cx - dL, xR = cx + dL, y = if (down) l.lens.y else l.lens.bottom - rowH, n = n1, windowRows = rowH, prefs = leaving)
                draw(strip, x = cx - dL, xR = cx + dL, y = if (down) l.lens.y - rowH else l.lens.bottom, h0 = 1, h1 = rowH,
                    anchor = if (down) 2 else 0, ease = e2, n = n2, windowRows = rowH, prefs = leaving)
            }
        }
    }

    /** C3 DM-2a (the DM spec §1.5): Matrix's rain — 24-px columns, a trail of six cells, the head at level 15 fading to
     *  [RAIN_TAIL_LVL], fill 0 behind each glyph, each cell keeping its glyph [RAIN_FLICKER] steps. */
    const val RAIN_CW = 24
    const val RAIN_TRAIL = 6
    const val RAIN_TAIL_LVL = 3
    const val RAIN_FLICKER = 2
    /** The rain's seed (h0): a constant of ours (decisions DW 10 — no source names one; any u16 serves). The fire's tag is
     *  mixed into it (opt bit 7), so each notch's rain differs and both lenses draw alike; `v5-dm-rain-fire` pins its bytes. */
    const val RAIN_SEED = 0x4D58
    /** The noise set's codes (the DM spec §1.4): 16, so every alphabet code is a real glyph in the private table (§7.11). */
    val NOISE_CODES: IntArray = "0123456789ABCDEF".map { it.code }.toIntArray()
    /** The face the noise is drawn in: Main's row face (`MainSurface`'s), through the shell's styled text. */
    val NOISE_FACE = FontSpec(Face.SYSTEM, 18)

    /** C3 DM-2b: Scramble's seed (h0): a constant of ours, as the rain's (decisions DW 10, EP 10 — no source names one; any
     *  u16 serves). The fire's tag is mixed into it (opt bit 7), so each notch's noise differs and both lenses draw alike;
     *  `v5-dm-cells-fire` pins its bytes. */
    const val SCRAMBLE_SEED = 0x5343

    /** C3 DM-2a, DM-2b — the one records holder (the records rule, [hasRecords]): an effect's staging records laid from
     *  byte [at] as one blob of [bytes], and what its pans name — Matrix's rain record ([rain]) or Scramble's cell tables
     *  ([cells]). */
    class EffectRecords(val at: Int, val bytes: ByteArray, val rain: Motion.Rain? = null, val cells: CellTables? = null)

    /** C3 DM-2b: Scramble's seven cell tables, each indexed by the cursor before the move (P2, kind 3): the rows above and
     *  below the band for each step ([above], [below]), the band's old-role lists ([bandOld]: row c's) and its new-role
     *  lists for each step ([bandNew]: row (c + step) mod n's). */
    class CellTables(val above: Map<Int, Motion.CellTable>, val below: Map<Int, Motion.CellTable>, val bandOld: Motion.CellTable,
                     val bandNew: Map<Int, Motion.CellTable>)

    /** A records blob as it is laid: each part 4-byte aligned from byte [at]. */
    private class Blob4(val at: Int) {
        val out = java.io.ByteArrayOutputStream()
        fun here4() = (at + out.size()) / 4
        fun put(b: ByteArray) { out.write(b); repeat((4 - b.size % 4) % 4) { out.write(0) } }
    }

    /** The noise set laid into [b]: [NOISE_CODES] drawn in [NOISE_FACE] through [text] as the atlas draws a face's glyphs
     *  (`GlyphAtlas`'s render: an advance-width box the face's ink height tall, quantised to 4 bits) — private copies
     *  inside the staging, so no atlas write ever meets a bound program's read range (§7.6). A code the face cannot
     *  draw: refused ([LintError]; the effect then plays Depth, said). */
    private fun noise(text: TextRasterizer, b: Blob4, who: String): List<Motion.Glyph> {
        val h = text.metrics(NOISE_FACE).let { it.ascent + it.descent }.coerceIn(1, CfwModes.MAX_TEXTURE_DIM)
        return NOISE_CODES.map { c ->
            val s = c.toChar().toString()
            val adv = if (text.covers(s, NOISE_FACE)) text.measure(s, NOISE_FACE) else 0
            if (adv !in 1..CfwModes.MAX_TEXTURE_DIM) throw LintError("$who's noise face $NOISE_FACE cannot draw '$s' (its advance $adv)")
            val g = Gray8(adv, h).also { text.draw(it, 0, 0, s, NOISE_FACE, 255) }
            val rec = TextureCache.Image(adv, h, ByteArray(adv * h) { Pack.level(g.pix[it].toInt() and 0xFF).toByte() }).encode()
            Motion.Glyph.of(b.here4(), rec).also { b.put(rec) }
        }
    }

    /**
     * C3 DM-2a (the DM spec §1.4's D1, §1.5; the DM-2 spec §1, "faces and alphabet"): Matrix's records at [l] laid from byte
     * [at], each 4-byte aligned — the noise set ([noise]); its private 224-entry table (each alphabet code its own glyph,
     * every other entry the first glyph, never read); the alphabet; and the rain record: [rainCols] columns of [RAIN_CW],
     * cells the face's height, reveal 0 (not in place: a bound program may hold it, §7.2), spread R = ⌈[rainH] / ch⌉. An
     * off4 past the u16 reach: refused ([LintError]; Matrix then plays Depth, said).
     */
    fun rainRecords(l: Layout, text: TextRasterizer, at: Int): EffectRecords {
        if (at % 4 != 0) throw LintError("Matrix's records at $at are not 4-byte aligned")
        val b = Blob4(at)
        val glyphs = noise(text, b, "Matrix")
        val h = glyphs[0].h
        val font = Motion.Font(b.here4(), IntArray(224) { i -> glyphs[NOISE_CODES.indexOf(i + 32).coerceAtLeast(0)].off4 }, glyphs).also { b.put(it.bytes) }
        val alphabet = Motion.Alphabet(b.here4(), NOISE_CODES).also { b.put(it.bytes) }
        val rain = Motion.Rain(b.here4(), rainCols(l), RAIN_TRAIL, RAIN_CW, h, font, alphabet, headLvl = 15, tailLvl = RAIN_TAIL_LVL, reveal = 0,
            fillLvl = 0, spread = (rainH(l) + h - 1) / h, flicker = RAIN_FLICKER).also { b.put(it.bytes) }
        return EffectRecords(at, b.out.toByteArray(), rain = rain)
    }

    /** C3 DM-2b: Main's text as Scramble's cells take it — each row's packed glyphs in its row form ([rows]) and in its band
     *  form ([bands]), each with its face (`CellLists.glyphs` of the runs `ListStaging.Paint` recorded), and the atlas's
     *  entry of every face they name (the glyph images the pictures were blitted from: the private copies' source). */
    class ScrambleText(val rows: List<List<Pair<FontSpec, CellLists.Glyph>>>, val bands: List<List<Pair<FontSpec, CellLists.Glyph>>>,
                       val entries: Map<FontSpec, wm.damage.core.comp.GlyphAtlas.Entry>) {
        /** The cells one step draws inside the tracks' areas at the worst cursor (modeled): at cursor c in step d, the old
         *  role of the rows a track shows before the move and the new role of those it shows after, and the band's two
         *  lists — the fit's cell term ([timing]). */
        fun cellsPerStep(l: Layout, ring: Boolean): Int {
            val n = rows.size
            fun cnt(r: Int) = if (ring) rows[r.mod(n)].size else rows.getOrNull(r)?.size ?: 0
            return (0 until n).maxOf { c -> intArrayOf(1, -1).maxOf { d ->
                (1..l.rowsAbove).sumOf { cnt(c - it) + cnt(c + d - it) } + (1..l.rowsBelow).sumOf { cnt(c + it) + cnt(c + d + it) } +
                    bands[c].size + bands[(c + d).mod(n)].size } }
        }
    }

    /**
     * C3 DM-2b (the DM spec §1.4; the DM-2 spec §1): Scramble's records at [l] laid from byte [at], each part 4-byte aligned
     * — the noise set ([noise]); one private 224-entry table per face [text] names, with private copies of its glyphs (the
     * atlas's images): a code the text uses names its own copy, so does an alphabet code the face packs, the other
     * alphabet codes the noise glyph, every other entry the first noise glyph (never read), so every alphabet code is a
     * real glyph in every table (§7.11); the alphabet ([NOISE_CODES]); the cell lists; the seven tables ([CellTables]).
     *
     * Cells: every glyph of a strip row s (Main's row [stripRow]`[s]`, none on a blank margin row; Main's row r is strip row
     * r + [margin]) twice — new-role (old =
     * new = 32, a mask) at its final place, by = (s − d)·rowH + y, and old-role (old = its code, new = 32) at its start
     * place, by = s·rowH + y — fill 0, its run's level, its box its glyph's width and the table's widest alphabet glyph by
     * the face's line and the table's tallest alphabet glyph, so a cell's fill repaints all it drew the step before. A
     * direction's lists are chunks of [k] strip rows, new-role cells first (old letters draw over the masks), starting
     * every k − w + 1 rows (w = k_rows + 1, the window a track shows over a notch), so each window lies in one; each row
     * table maps cursor c to the chunk holding its window — above: Main's rows c − kA .. c (a notch down) or c − kA − 1 ..
     * c − 1 (up); below: c + 1 .. c + kB + 1 or c .. c + kB. The band's lists are each row's band form's glyphs, old-role
     * and new-role. Noise N = max(1, ⌊[t] / 3⌋) in every list. A list with no glyph holds one cell that draws nothing
     * (32, 32, no fill: §7.6's 1..512). Past 512 cells, past the u16 reach, a face the atlas does not hold, or no text at
     * all: refused ([LintError]; Scramble then plays Depth, said).
     */
    fun scrambleRecords(l: Layout, text: ScrambleText, stripRow: IntArray, margin: Int, noiseText: TextRasterizer, at: Int, t: Int, k: Int): EffectRecords {
        if (at % 4 != 0) throw LintError("Scramble's records at $at are not 4-byte aligned")
        val all = (text.rows + text.bands).flatten()
        if (all.isEmpty()) throw LintError("Main's rows draw no text the atlas holds")
        val b = Blob4(at)
        val noise = noise(noiseText, b, "Scramble")
        val noiseOf = NOISE_CODES.indices.associate { NOISE_CODES[it] to noise[it] }
        class Private(val font: Motion.Font, val boxW: Int, val boxH: Int)
        val faces = all.map { it.first }.distinct().associateWith { spec ->
            val e = text.entries[spec] ?: throw LintError("the face $spec is not in the atlas")
            val codes = (all.filter { it.first == spec }.map { it.second.code } + NOISE_CODES.filter { e.packs(it.toChar()) }).distinct().sorted()
            val own = codes.associateWith { c -> e.images[c - TextureCache.FIRST_CHAR].encode().let { rec -> Motion.Glyph.of(b.here4(), rec).also { b.put(rec) } } }
            val alpha = NOISE_CODES.map { own[it] ?: noiseOf.getValue(it) }
            val font = Motion.Font(b.here4(), IntArray(224) { i -> (own[i + TextureCache.FIRST_CHAR] ?: noiseOf[i + TextureCache.FIRST_CHAR] ?: noise[0]).off4 },
                own.values + noise).also { b.put(it.bytes) }
            Private(font, alpha.maxOf { it.w }, maxOf(e.lineH, alpha.maxOf { it.h }))
        }
        val alphabet = Motion.Alphabet(b.here4(), NOISE_CODES).also { b.put(it.bytes) }
        val steps = maxOf(1, t / 3)
        fun cell(g: Pair<FontSpec, CellLists.Glyph>, by: Int, old: Int) = faces.getValue(g.first).let { f ->
            Motion.Cell(g.second.x, by, minOf(255, maxOf(g.second.w, f.boxW)), minOf(255, f.boxH), f.font, old, 32, fill = 0, level = g.second.level) }
        val blank = Motion.Cell(0, 0, 0, 0, faces.values.first().font, 32, 32)
        fun list(cells: List<Motion.Cell>) = Motion.CellList(b.here4(), alphabet, steps, cells.ifEmpty { listOf(blank) }).also { b.put(it.bytes) }
        val n = text.rows.size
        val stripRows = stripRow.size
        val m = margin
        val w = maxOf(l.rowsAbove, l.rowsBelow) + 1
        if (k < w || k > stripRows) throw LintError("Scramble's chunks of $k strip rows do not hold a window of $w in a strip of $stripRows")
        val every = k - w + 1
        fun rowOf(s: Int) = stripRow[s].let { if (it < 0) emptyList() else text.rows[it] }
        fun table(lists: List<Motion.CellList>) = Motion.CellTable(b.here4(), lists).also { b.put(it.bytes) }
        val above = HashMap<Int, Motion.CellTable>(); val below = HashMap<Int, Motion.CellTable>()
        for (d in intArrayOf(1, -1)) {
            val chunks = (0..maxOf(0, stripRows - w) / every).map { j ->
                val rs = j * every until minOf(stripRows, j * every + k)
                list(rs.flatMap { s -> rowOf(s).map { cell(it, (s - d) * l.rowH + it.second.y, 32) } } +
                    rs.flatMap { s -> rowOf(s).map { cell(it, s * l.rowH + it.second.y, it.second.code) } })
            }
            fun chunkAt(x: Int) = chunks[x.coerceIn(0, maxOf(0, stripRows - w)) / every]
            above[d] = table(List(n) { c -> chunkAt(c + m - l.rowsAbove - (if (d < 0) 1 else 0)) })
            below[d] = table(List(n) { c -> chunkAt(c + m + (if (d > 0) 1 else 0)) })
        }
        val bandOld = text.bands.map { gs -> list(gs.map { cell(it, it.second.y, it.second.code) }) }
        val bandNew = text.bands.map { gs -> list(gs.map { cell(it, it.second.y, 32) }) }
        val oldT = table(bandOld)
        val newT = intArrayOf(1, -1).associateWith { d -> table(List(n) { c -> bandNew[(c + d).mod(n)] }) }
        return EffectRecords(at, b.out.toByteArray(), cells = CellTables(above, below, oldT, newT))
    }

    /**
     * C3 DM-2a, Matrix (the DM spec §1.5; decisions DR (b): "the list moves one row as Depth's while the rain falls through
     * the rows (reveal 0)"; (n): the rain falls down on both notch directions): five per-lens tracks, version 2, pitch
     * −rowH, Depth's parameters by Depth's rules —
     * - T0, the list's whole area W (the rows above, the band, the rows below; [rainH] tall), level 0, WAIT T: it paints its
     *   area every step, so the rain inside it may have backdrop none (§7.2); T1, T2 and T4 draw every pixel of it again;
     * - T1, T2: Depth's rows above and below the band (the strip, y ← P0 and ← P3, one pitch on ease-out);
     * - T3, W, backdrop none: the rain ([Staged.rain], GLYPHS kind 1) at the area's corner on each lens, linear over T,
     *   seeded with [RAIN_SEED] and the fire's tag — drawn before the band, so it falls behind it;
     * - T4: Depth's band (P2 back and dim, then P1 forward and bright).
     * END: at step T every head is at R + trail, so no cell is lit and reveal 0 leaves the rows — Depth's END (pinned).
     */
    private fun matrix(s: Staged, strip: Motion.Source.Record, kA: Int, kB: Int, step: Int, t: Int, at4: Int, fx: Motion.Effects): MotionProgram {
        val rain = s.recs?.rain ?: throw LintError("Matrix's rain is a record of the staging, and this staging holds none (a full staging with Matrix in force lays it)")
        val l = s.layout
        val r = Rects(l, kA, kB, s.listD, s.bandD)
        val cx = r.cx; val dL = s.listD; val dB = s.bandD; val shift = dL - dB
        val wL = Rect(cx - dL, l.lens.y - kA * l.rowH, r.rowW, (kA + kB) * l.rowH + l.lens.h)
        val wR = Rect(cx + dL, wL.y, wL.w, wL.h)
        val ease = Motion.Ease.easeOut
        return Motion.program2(at4, fx, records = s.records) {
            track(wL, wR, Motion.Backdrop.Level(0)) { wait(t) }
            track(r.aL, r.aR, Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - dL, xR = cx + dL, y = 0, dy = -step * l.rowH, ease = ease, n = t, prefs = listOf(Motion.Pref(0, Motion.Field.Y)))
            }
            track(r.bL, r.bR, Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - dL, xR = cx + dL, y = 0, dy = -step * l.rowH, ease = ease, n = t, prefs = listOf(Motion.Pref(3, Motion.Field.Y)))
            }
            track(wL, wR, Motion.Backdrop.None) { rain(rain, t, x = 0, xR = 0, seed = RAIN_SEED, mixTag = true) }
            track(r.nL, r.nR, Motion.Backdrop.Level(0)) {
                val swap = ListStaging.swapOf(t)
                draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y, dx = -shift, dxR = shift,
                    lvl0 = 15, lvl1 = MainPan.LVL_BACK, ease = ease, n = swap, prefs = listOf(Motion.Pref(2, Motion.Field.SRC)))
                draw(s.band, x = cx - dL, xR = cx + dL, y = l.lens.y, dx = shift, dxR = -shift,
                    lvl0 = MainPan.LVL_BACK, lvl1 = 15, ease = ease, n = t - swap, prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
            }
        }
    }

    /**
     * C3 DM-2b, Scramble (the DM spec §1.4; his words, `MOTION.md` §7: the rows stay in place and the letters change in
     * random order to the new ones): seven per-lens tracks, version 2, pitch −rowH; P0 and P3 Depth's kind 2, P1 kind 1
     * (the entering band form), P2 kind 3 (the cursor before the move, `FIRMWARE.md` §7.8), every cell track's index —
     * - T1, T2: the rows above and below the band (level 0): the strip, y ← P0 and ← P3, one pitch in ONE step, then held —
     *   nothing moves after step 1;
     * - T3, the band (level 0): the entering band form (← P1) in one step, then held;
     * - T4, T5, inside T1 and T2 (backdrop none): the rows' cells ([CellTables.above], [CellTables.below] for [step]),
     *   offset by y ← P0 and ← P3 as the strip, over T;
     * - T6, T7, inside T3 (backdrop none): the band's new-role cells ([CellTables.bandNew]), then its old-role ones
     *   ([CellTables.bandOld]) — masks first, so the old letters draw over them — over T.
     * Every cell track seeded with [SCRAMBLE_SEED] and the fire's tag, its levels 15. END: at step T every cell is settled
     * on its new code 32 (§7.6: no fill, no glyph), so T1–T3 alone show — Depth's END (pinned at every cursor).
     */
    internal fun scramble(s: Staged, strip: Motion.Source.Record, kA: Int, kB: Int, step: Int, t: Int, at4: Int, fx: Motion.Effects): MotionProgram {
        val tb = s.recs?.cells ?: throw LintError("Scramble's cells are records of the staging, and this staging holds none (a full staging with Scramble in force lays them)")
        val l = s.layout
        val r = Rects(l, kA, kB, s.listD, s.bandD)
        val cx = r.cx; val dL = s.listD; val dB = s.bandD
        val cursor = Motion.Pref(2, Motion.Field.AUX0)
        return Motion.program2(at4, fx, records = s.records) {
            track(r.aL, r.aR, Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - dL, xR = cx + dL, y = 0, dy = -step * l.rowH, n = 1, prefs = listOf(Motion.Pref(0, Motion.Field.Y)))
            }
            track(r.bL, r.bR, Motion.Backdrop.Level(0)) {
                draw(strip, x = cx - dL, xR = cx + dL, y = 0, dy = -step * l.rowH, n = 1, prefs = listOf(Motion.Pref(3, Motion.Field.Y)))
            }
            track(r.nL, r.nR, Motion.Backdrop.Level(0)) {
                draw(s.band, x = cx - dB, xR = cx + dB, y = l.lens.y, n = 1, prefs = listOf(Motion.Pref(1, Motion.Field.SRC)))
            }
            track(r.aL, r.aR, Motion.Backdrop.None) {
                cells(tb.above.getValue(step), n = t, x = cx - dL, xR = cx + dL, seed = SCRAMBLE_SEED, mixTag = true, prefs = listOf(cursor, Motion.Pref(0, Motion.Field.Y)))
            }
            track(r.bL, r.bR, Motion.Backdrop.None) {
                cells(tb.below.getValue(step), n = t, x = cx - dL, xR = cx + dL, seed = SCRAMBLE_SEED, mixTag = true, prefs = listOf(cursor, Motion.Pref(3, Motion.Field.Y)))
            }
            track(r.nL, r.nR, Motion.Backdrop.None) {
                cells(tb.bandNew.getValue(step), n = t, x = cx - dB, xR = cx + dB, y = l.lens.y, seed = SCRAMBLE_SEED, mixTag = true, prefs = listOf(cursor))
            }
            track(r.nL, r.nR, Motion.Backdrop.None) {
                cells(tb.bandOld, n = t, x = cx - dB, xR = cx + dB, y = l.lens.y, seed = SCRAMBLE_SEED, mixTag = true, prefs = listOf(cursor))
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
