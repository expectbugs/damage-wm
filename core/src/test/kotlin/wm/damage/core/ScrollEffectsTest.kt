package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.local.BoundList
import wm.damage.core.motion.BoxFace
import wm.damage.core.motion.ListForm
import wm.damage.core.motion.Motion
import wm.damage.core.motion.ScrollEffect
import wm.damage.core.motion.ScrollEffects
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.MainLocalCheck
import wm.damage.core.shell.ListStaging
import wm.damage.core.shell.ContentKit
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowListCheck
import wm.damage.core.wire.CfwModes

/**
 * K1-1 (`MOTION.md` §7; `private/build083/K1-1-brief.md`): the Scroll tier's effects on Main's bound list and the re-make of
 * the pans alone. Context for the reader: Adam's own glasses on his own firmware fork; nothing here contacts a device.
 * The fire-through-the-hook pins of every effect (the last step and COMMIT's END against Main's paint, on the firmware
 * model) are `MainLocalCheck`'s, run by `MainBindTest`; these are the staging's own.
 */
class ScrollEffectsTest {

    private val heights = ShellSettings.HEIGHTS
    private val caches = listOf(CfwModes.CACHE2_MAX, CfwModes.CACHE2_MAX_RIDERS)

    private fun staging(h: Int, cache: Int, e: ScrollEffect = ScrollEffect.DEPTH, rig: MainLocalCheck.MainRig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())) =
        rig.staging(cache, CfwModes.mainRegion(cache, true), ListStaging.ScrollMotion.DEFAULT.copy(effect = e))

    /** Today's Depth programs (APK 0.82, `main` at 39afb77), read on the unchanged tree with this rig: per height and cache,
     *  the pans in order (strip slot : step) and the SHA-256 of their records back to back. K1-2 stage 1 (O1): at 256 KiB
     *  the second strip's pans (1:±1) are gone and the rest are 160 KiB's byte for byte (Main's region starts at 96 KiB on
     *  both; `private/build083/K1-2-S1-built.md` §4 has the diff read); 160 KiB's are unchanged. */
    private val todays = mapOf(
        (288 to 160) to ("0:1,0:-1,2:1,2:-1" to "656b94c4c9f5587d92edf7872931a77a7c373c9f2b119e636c6bec45dee0900f"),
        (288 to 256) to ("0:1,0:-1,2:1,2:-1" to "656b94c4c9f5587d92edf7872931a77a7c373c9f2b119e636c6bec45dee0900f"),
        (352 to 160) to ("0:1,0:-1,2:1,2:-1" to "08934284c6cbbc8718b90f49fab2b3d0e6343e47a42d1574dad39f38cee8c2cb"),
        (352 to 256) to ("0:1,0:-1,2:1,2:-1" to "08934284c6cbbc8718b90f49fab2b3d0e6343e47a42d1574dad39f38cee8c2cb"),
        (416 to 160) to ("0:1,0:-1" to "1a0f359f7329ac99bd9781eec93c2f0f1fe0aa5460ed903c2ee0925ddf23131e"),
        (416 to 256) to ("0:1,0:-1" to "1a0f359f7329ac99bd9781eec93c2f0f1fe0aa5460ed903c2ee0925ddf23131e"),
        (480 to 160) to ("0:1,0:-1" to "99a6314e4dbbc6742b27dbc304cc827bcb203e9662689579f7bd256324e5a499"),
        (480 to 256) to ("0:1,0:-1" to "99a6314e4dbbc6742b27dbc304cc827bcb203e9662689579f7bd256324e5a499"),
    )

    private fun digest(pans: List<ListStaging.Pan>): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (p in pans) md.update(p.program.bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The brief's pin: Depth's programs are today's, record for record, at every height on both caches (the default
     *  motion, so T 6 at 352 as on his glasses), and so are their parameters' rules. */
    @Test
    fun depthIsTodaysProgramsByteForByte_K11() {
        for ((key, want) in todays) {
            val (h, kib) = key
            val ms = staging(h, kib * 1024)
            assertEquals(want.first, ms.pans.joinToString(",") { "${it.strip}:${it.step}" }, "$h at $kib KiB: the pans in order")
            assertEquals(want.second, digest(ms.pans), "$h at $kib KiB: Depth's records are today's byte for byte")
        }
    }

    /**
     * Every effect's END is Main's own paint at the new cursor — at every cursor a notch lands on, both ways, across the
     * wrap where Main wraps, at the four heights, with one strip (160 KiB) and two (256) — raw pixel for pixel in the
     * program's areas; every PLAY passes the offline checks; the start state of the effects that move from the old
     * picture (all but None, whose one step IS its end) is Main at the old cursor; and the parameters' rules are Depth's.
     */
    @Test
    fun everyEffectsEndIsMainsPaintAtEveryCursorBothWays_K11() {
        val table = ArrayList<String>()
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) {
                val depthRules = staging(h, cache, ScrollEffect.DEPTH, rig).pans.map { p -> p.rules.map { it.kind to it.arg } }
                for (e in ScrollEffect.entries) {
                    val ms = staging(h, cache, e, rig)
                    val what0 = "$h ${cache / 1024} KiB ${e.word}"
                    assertEquals(depthRules, ms.pans.map { p -> p.rules.map { it.kind to it.arg } }, "$what0: the rules are Depth's")
                    var n = 0
                    for (pan in ms.pans) for (cPrime in -1..ms.rows) {
                        val p = (if (pan.strip == ListStaging.WRAP_STRIP) ms.wrapParams(pan, cPrime) else ms.params(pan, cPrime)) ?: continue
                        val c = cPrime - pan.step
                        val to = cPrime.mod(ms.rows)
                        Motion.play(pan.program, ListStaging.SLOT, ms.period, 1, 0, p)
                        for (left in listOf(true, false)) {
                            val end = rig.truth(c, left).copy().also { ms.paintEnd(pan, p, left, it) }
                            assertEquals(0, differ(end, rig.truth(to, left), pan.areas(left)), "$what0 strip ${pan.strip} step ${pan.step} $c→$cPrime left=$left: px where END is not Main's paint at $to")
                            if (e != ScrollEffect.NONE) {
                                val start = rig.truth(c, left).copy().also { ms.paintStart(pan, p, left, it) }
                                assertEquals(0, differ(start, rig.truth(c, left), pan.areas(left)), "$what0 strip ${pan.strip} step ${pan.step} $c→$cPrime left=$left: px where the start state is not Main at $c")
                            }
                        }
                        n++
                    }
                    assertTrue(n >= 2 * (ms.rows - 1), "$what0: $n notches checked")
                    val whole = ms.pan(ListStaging.WHOLE, 1)
                    // C3 DM-1: by what plays (Crush plays Slide below stage 5; Flip's height turn draws Depth's areas)
                    assertEquals(ScrollEffects.areaPx(ms.plays.effect, ms.layout, ms.listD, ms.bandD), whole.areaPx, "$what0: the fitted area is the tracks' own")
                    assertEquals(if (e == ScrollEffect.NONE) 1 else ms.t, whole.program.T, "$what0: T")
                    if (cache == CfwModes.CACHE2_MAX_RIDERS) table += "h$h ${e.word}: ${whole.program.tracks.size} tracks, ${whole.areaPx} px a step, " +
                        "P ${ms.period} (${"%.1f".format(ms.period * 1000.0 / 1024)} ms), T ${ms.t} = ${"%.0f".format(ms.t * ms.period * 1000.0 / 1024)} ms at 300 ms Auto"
                }
            }
        }
        println("K11-effects (modeled, the fit)\n" + table.joinToString("\n"))
    }

    /**
     * C3 DM-0 (the DM spec §1.1, §2; decisions AD's S2): the stage-5 form. Where Main's paint wraps (the sample's eight rows
     * at 288 and 352) the strip's margins are k + 1 rows of the other end each side, no wrap record is built, the rules'
     * P0 and P3 take that margin, and every effect's END is Main's own paint at the new cursor at EVERY cursor a notch
     * lands on, the wrap included — at the virtual row v = −1 or n, as the glasses compute a ring fire (the bound list's
     * prediction, `BoundList.predict`) — with its start state Main at the old cursor, and no wrap pan for the shell (C3
     * X2M0). At 416 and 480 the staging is today's form at stage 5 too — every record but the programs byte for byte (C3
     * FF: the programs carry the fast DRAW path's fit at every height), END = Main's paint at every cursor a notch lands on.
     */
    @Test
    fun theStage5FormHoldsEveryEffectAcrossTheWrapAndIsTodaysWhereMainDoesNotWrap_DM0() {
        val table = ArrayList<String>()
        var ends = 0
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) for (e in ScrollEffect.entries) {
                val m = ListStaging.ScrollMotion.DEFAULT.copy(effect = e)
                val s5 = rig.staging(cache, CfwModes.mainRegion(cache, true), m, stage5 = true)
                val s4 = rig.staging(cache, CfwModes.mainRegion(cache, true), m)
                val what0 = "$h ${cache / 1024} KiB ${e.word} at stage 5"
                val l = s5.layout
                val wraps = ListStaging.ringFits(l, s5.rows)
                assertEquals(wraps, s5.ring, "$what0: the ring form exactly where Main's paint wraps")
                assertTrue(s5.stage5 && !s4.stage5, "$what0: each staging records the EFFECTS it was made under")
                val k = l.rowsAbove
                if (!wraps) {
                    // C3 FF: today's form — every record but the programs byte for byte (the programs take the fast fit's
                    // T); END is checked below at every cursor a notch lands on, the ends being edges
                    // (C3 DM-2a: Matrix's records lie past the programs, a staging record of their own)
                    fun form(ms: ListStaging) = ms.blobs.filter { it.name != "programs" && it.name != "Matrix's records" }.map { it.off to it.bytes.toList() }
                    assertEquals(form(s4), form(s5), "$what0: today's form, byte for byte")
                    assertEquals(if (e == ScrollEffect.MATRIX) listOf("programs", "Matrix's records") else listOf("programs"),
                        s5.blobs.map { it.name }.dropWhile { it != "programs" }, "$what0: the programs last (Matrix's records after them)")
                } else {
                    assertEquals(null, s5.wrap, "$what0: no wrap record")
                    assertEquals(k + 1, s5.margin, "$what0: margins of k + 1")
                    assertEquals((s5.rows + 2 * (k + 1)) * l.rowH, s5.strip.raw.h, "$what0: the strip's rows")
                    // (C3 DM-1: Crush's P0 and P3 are source-window first rows at pitch +rowH, the DM spec §1.2)
                    for (pan in s5.pans) assertEquals(if (e == ScrollEffect.CRUSH) listOf((k + 1 - k - (if (pan.step > 0) 1 else 0)) * l.rowH, (k + 1 - pan.step) * l.rowH)
                        else listOf(l.lens.y + (pan.step - k - 1) * l.rowH, l.lens.bottom + (pan.step - 1 - k - 1) * l.rowH),
                        listOf(pan.rules[0].arg, pan.rules[3].arg), "$what0: P0's and P3's rules take the margin")
                    assertNull(s5.wrapPan(1) ?: s5.wrapPan(-1), "$what0: no wrap pan (the wrap notch fires on the glasses)")
                }
                // (C3 DM-2b: of what plays — Scramble on this rig, with no runs, plays Depth, said)
                assertEquals(ScrollEffects.timing(s5.plays, l, s5.listD, s5.bandD, m, true), s5.period to s5.t, "$what0: the fast DRAW path's fit")
                assertEquals(if (e == ScrollEffect.NONE) 1 else s5.t, s5.pan(ListStaging.WHOLE, 1).program.T, "$what0: the pans' T is the fit's")
                val list = s5.boundList(1)
                var n = 0
                for (pan in s5.pans) for (cPrime in -1..s5.rows) {
                    val c = cPrime - pan.step
                    if (c !in 0 until s5.rows) continue                 // no notch lands there from a row
                    // what the glasses compute for this pan's step from row c — at v = c' unwrapped across the wrap
                    val le = list.entries.first { !it.isTap && it.step == pan.step }
                    val p = (list.predict(c, le) as? wm.damage.core.local.BoundList.Fire.Drew)?.p ?: continue
                    val to = cPrime.mod(s5.rows)
                    Motion.play(pan.program, ListStaging.SLOT, s5.period, 1, 0, p)
                    for (left in listOf(true, false)) {
                        val end = rig.truth(c, left).copy().also { s5.paintEnd(pan, p, left, it) }
                        assertEquals(0, differ(end, rig.truth(to, left), pan.areas(left)), "$what0 strip ${pan.strip} step ${pan.step} $c→$cPrime left=$left: px where END is not Main's paint at $to")
                        if (e != ScrollEffect.NONE) {
                            val start = rig.truth(c, left).copy().also { s5.paintStart(pan, p, left, it) }
                            assertEquals(0, differ(start, rig.truth(c, left), pan.areas(left)), "$what0 strip ${pan.strip} step ${pan.step} $c→$cPrime left=$left: px where the start state is not Main at $c")
                        }
                    }
                    n++
                }
                assertEquals(s5.pans.size * (if (wraps) s5.rows else s5.rows - 1), n, "$what0: every cursor${if (wraps) ", the two wraps among them," else ""} on every pan")
                ends += n
                if (e == ScrollEffect.DEPTH) table += "h$h ${cache / 1024} KiB: the strip ${s5.blobs[0].bytes.size} B (${s5.strip.raw.h / l.rowH} rows; " +
                    "stage 4: ${s4.blobs[0].bytes.size} B and a wrap record ${s4.wrap?.encoded?.size ?: 0} B), the staging ${s5.end - s5.start} B (stage 4: ${s4.end - s4.start} B)"
            }
        }
        println("DM0-staging (BoxFace, modeled): END = paint at $ends notches at stage 5\n" + table.joinToString("\n"))
    }

    /**
     * C3 FF (the DM spec §1.1 "The fast-path fit", §1.6, §5.1's pin; decisions AY (4), BA (6)): at stage 5 the pans take the
     * fast DRAW path's fit, then K1-1's rule — Depth at 352 P 16, T 19 (C2's fit: P 46, T 6), at every height P 16 T 19 for
     * Depth; a fixed frame count spread over the length, never under the fitted period; None one step; Auto uncapped (D8);
     * the band's swap at its proportion. Below stage 5 C2's fit stands. Every pan at every cursor, both directions, four
     * heights, both caches, every effect stays inside 80 % of the step budget's cap (§7.12, the DSL's calculator), and
     * each effect's programs record is printed at the fitted T beside C2's.
     */
    @Test
    fun stage5TakesTheFastPathsFitAndStaysInsideTheBudget_FF() {
        val fit = ArrayList<String>()
        val bytes = ArrayList<String>()
        var worst = 0L; var worstAt = ""
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) for (e in ScrollEffect.entries) {
                val m = ListStaging.ScrollMotion.DEFAULT.copy(effect = e)
                val s5 = rig.staging(cache, CfwModes.mainRegion(cache, true), m, stage5 = true)
                val s4 = rig.staging(cache, CfwModes.mainRegion(cache, true), m)
                val what = "$h ${cache / 1024} KiB ${e.word}"
                if (e == ScrollEffect.DEPTH) assertEquals(16 to 19, s5.period to s5.t, "$what: the spec's §1.6 — Depth P 16, T 19 at stage 5")
                assertEquals(ScrollEffects.timing(e, s4.layout, s4.listD, s4.bandD, m, false), s4.period to s4.t, "$what: C2's fit below stage 5")
                if (e == ScrollEffect.NONE) assertEquals(1, s5.t, "$what: None one step")
                // the band's swap keeps its 2-of-5 proportion at the fitted T (Depth's two band segments)
                if (e == ScrollEffect.DEPTH) assertEquals(listOf(ListStaging.swapOf(s5.t), s5.t - ListStaging.swapOf(s5.t)),
                    s5.pan(ListStaging.WHOLE, 1).program.tracks[2].segments.map { it.steps }, "$what: the swap's proportion")
                for (pan in s5.pans) for (cPrime in -1..s5.rows) {
                    val p = s5.params(pan, cPrime) ?: continue
                    val c = pan.program.cost(p)
                    if (c > worst) { worst = c; worstAt = "$what strip ${pan.strip} step ${pan.step} c' $cPrime" }
                    assertTrue(c * 100 <= Motion.BUDGET_CAP * 80, "$what strip ${pan.strip} step ${pan.step} c' $cPrime: C $c past 80 % of the cap")
                }
                if (cache == CfwModes.CACHE2_MAX_RIDERS) {
                    fit += "h$h ${e.word}: stage 5 P ${s5.period} (${"%.1f".format(s5.period * 1000.0 / 1024)} ms) T ${s5.t} = ${"%.0f".format(s5.t * s5.period * 1000.0 / 1024)} ms; " +
                        "stage 4 P ${s4.period} T ${s4.t}; the fast step ${"%.1f".format(ListStaging.fastStepMs(ScrollEffects.areaPx(e, s5.layout, s5.listD, s5.bandD), ScrollEffects.walkBytes(e, s5.layout)))} ms (modeled)"
                    bytes += "h$h ${e.word}: the programs record ${s5.blobs.last().bytes.size} B at T ${s5.t} (stage 4 ${s4.blobs.last().bytes.size} B at T ${s4.t}); " +
                        "a pan slot ${s5.progSlot} B (${s4.progSlot}); the staging ${s5.end - s5.start} B of the region's ${s5.regionEnd - s5.regionStart} (${s4.end - s4.start})"
                }
            }
        }
        // a fixed count: spread over the length, never under the fitted period; Auto uncapped (D8: the `Scroll frames` row is his cap)
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        val cache = CfwModes.CACHE2_MAX_RIDERS
        fun at(m: ListStaging.ScrollMotion) = rig.staging(cache, CfwModes.mainRegion(cache, true), m, stage5 = true).let { it.period to it.t }
        assertEquals(31 to 10, at(ListStaging.ScrollMotion(300, 10)), "10 frames over 300 ms: P ⌈307.2 / 10⌉, above the fit's 16")
        assertEquals(16 to 40, at(ListStaging.ScrollMotion(300, 40)), "40 frames: never under the fitted period (the motion runs longer)")
        assertEquals(16 to 51, at(ListStaging.ScrollMotion(800, 0)), "800 ms Auto: ⌊819.2 / 16⌋ steps, no cap")
        // the programs-only re-make of a stage-5 staging (an effect changed) takes the same fit
        val d5 = rig.staging(cache, CfwModes.mainRegion(cache, true), ListStaging.ScrollMotion.DEFAULT, stage5 = true)
        val ps = assertNotNull((d5.remake(ListStaging.ScrollMotion.DEFAULT.copy(effect = ScrollEffect.SLIDE)) as? ListStaging.Remake.Owed)?.programs)
        assertEquals(listOf(16, 19, 16), listOf(ps.period, ps.t, ps.periodInForce), "a re-make at stage 5: Slide on the fast fit")
        println("FF-fit (modeled)\n" + fit.joinToString("\n") + "\nFF-bytes (BoxFace)\n" + bytes.joinToString("\n") +
            "\nFF-budget: the largest C ${"%,d".format(worst)} u (${"%.1f".format(worst * 100.0 / Motion.BUDGET_CAP)} % of the cap) at $worstAt")
    }

    /** Slide's and Fall's band: two tracks over the band's one area, the second with no backdrop (inside the first's, as
     *  §5.5 asks), the forms at the band's plane and full level throughout; Fall's table: E[T] = 4096, its peak 6 % past it
     *  at T − 1, rising to it; one table for every pan of the staging, placed in the pans' slot before them. */
    @Test
    fun slideAndFallDrawTheBandInTwoTracksAndFallOnItsTable_K11() {
        for (e in listOf(ScrollEffect.SLIDE, ScrollEffect.FALL)) {
            val ms = staging(352, CfwModes.CACHE2_MAX_RIDERS, e)
            val pr = ms.pan(ListStaging.WHOLE, 1).program
            assertEquals(4, pr.tracks.size, "${e.word}: four tracks")
            assertTrue(pr.tracks[3].backdrop is Motion.Backdrop.None && pr.tracks[2].areaL == pr.tracks[3].areaL && pr.tracks[2].areaR == pr.tracks[3].areaR, "${e.word}: the second band track over the first's area")
            for (s in pr.tracks.drop(2).flatMap { it.segments }) assertEquals(15 to 15, s.lvl0 to s.lvl1, "${e.word}: the band forms at full level")
            val tables = ms.pans.flatMap { p -> p.program.tables.map { it.first } }.toSet()
            assertEquals(if (e == ScrollEffect.FALL) 1 else 0, tables.size, "${e.word}: one table for the staging's pans")
            if (e == ScrollEffect.FALL) assertEquals(ms.progSlots!![0] / 4, tables.single(), "Fall: the table first in the pans' slot")
        }
        for (t in 3..40) {
            val tb = ScrollEffects.fallTable(t)
            assertEquals(t, tb.size)
            assertEquals(4096, tb.last(), "T $t: E[T]")
            assertEquals(4096 + 4096 * ScrollEffects.FALL_OVERSHOOT_PCT / 100, tb[t - 2], "T $t: the peak at T − 1")
            assertTrue(tb.toList().dropLast(1).zipWithNext().all { (a, b) -> b > a }, "T $t: rising to the peak: ${tb.toList()}")
        }
    }

    /**
     * K1-1 item 4: the re-make. Under Auto the steps follow the period in force (55 at 352: T 5 for 300 ms), the staging's
     * own period stays the floor; a fixed count and None keep theirs; a change of the effect is re-made with its own fit.
     * The re-made pans go to the free slot and switch, the old ones stay nameable until released; a staging with no
     * spare slot (or the probes' fixed rule) owes a full staging instead.
     */
    @Test
    fun theRemakeFollowsTheRowsAndAutosStepsTheMeasuredPeriod_K11() {
        val ms = staging(352, CfwModes.CACHE2_MAX_RIDERS)
        val slots = assertNotNull(ms.progSlots, "352 at 256 KiB holds a spare pan slot")
        assertEquals(46 to 6, ms.period to ms.t)
        val d = ListStaging.ScrollMotion.DEFAULT
        assertNull(ms.remakeOwed(d), "nothing owed at the staging's own motion")
        ms.periodInForce = 55
        assertTrue(ms.remakeOwed(d)!!.contains("55"), "${ms.remakeOwed(d)}")
        val p = assertNotNull((ms.remake(d) as? ListStaging.Remake.Owed)?.programs)
        assertEquals(listOf(5, 55, 46, 1, slots[1]), listOf(p.t, p.periodInForce, p.period, p.slot, p.off), "Auto at P 55: T 5, the floor kept, slot 1")
        val old = ms.pans.first().prog4
        ms.switchTo(p)
        assertEquals(listOf(5, 46, 55), listOf(ms.t, ms.period, ms.periodInForce))
        assertEquals(5, ms.pan(ListStaging.WHOLE, 1).program.T)
        assertTrue(ms.programAt(old).T == 6, "the old pan still named until released")
        ms.releasePrograms()
        assertTrue(runCatching { ms.programAt(old) }.isFailure, "released")
        assertNull(ms.remakeOwed(d), "nothing owed once T follows P")
        // a change of the effect: its own fit, the period in force reset to it, back into slot 0
        val slide = d.copy(effect = ScrollEffect.SLIDE)
        val ps = assertNotNull((ms.remake(slide) as? ListStaging.Remake.Owed)?.programs)
        assertEquals(0, ps.slot)
        assertEquals(ps.period, ps.periodInForce, "a new effect starts from its own fit")
        assertTrue(ps.reset)
        // a fixed count and None keep their steps whatever the period
        val fixed = staging(352, CfwModes.CACHE2_MAX_RIDERS).also { it.periodInForce = 80 }
        assertTrue(fixed.remakeOwed(d.copy(frames = 10))!!.startsWith("the motion"), "a change of frames is a change of the rows")
        val f10 = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows()).staging(CfwModes.CACHE2_MAX_RIDERS, CfwModes.mainRegion(CfwModes.CACHE2_MAX_RIDERS, true), d.copy(frames = 10))
        f10.periodInForce = 80
        assertNull(f10.remakeOwed(d.copy(frames = 10)), "fixed frames: the steps stay")
        val none = staging(352, CfwModes.CACHE2_MAX_RIDERS, ScrollEffect.NONE).also { it.periodInForce = 80 }
        assertNull(none.remakeOwed(d.copy(effect = ScrollEffect.NONE)), "None: one step whatever the period")
        // the probes' fixed rule: never re-made
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        assertNull(rig.staging(CfwModes.CACHE2_MAX_RIDERS, CfwModes.mainRegion(CfwModes.CACHE2_MAX_RIDERS, true), null).progSlots)
    }

    /** The spare slot is the first thing a short region gives up (before the rows' room and the wrap), and a staging
     *  without it owes a full staging for a change of the rows; at 160 and 256 KiB with the sample rows every height
     *  holds it. The slot holds every effect's pans at this length. */
    @Test
    fun theSparePanSlotHoldsEveryEffectAndIsGivenUpFirst_K11() {
        for (h in heights) for (cache in caches) {
            val ms = staging(h, cache)
            val slots = assertNotNull(ms.progSlots, "$h at ${cache / 1024} KiB: a spare slot")
            assertEquals(slots[0] + ms.progSlot, slots[1], "$h: the slots back to back")
            assertTrue(ms.end <= ms.regionEnd, "$h: inside the region")
            for (e in ScrollEffect.entries) if (e != ScrollEffect.DEPTH)
                assertTrue(ms.remake(ListStaging.ScrollMotion.DEFAULT.copy(effect = e)) is ListStaging.Remake.Owed, "$h ${cache / 1024}: ${e.word} fits the slot")
        }
        // a region a little short of the spare slot: the staging gives the slot up and nothing else
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        val cache = CfwModes.CACHE2_MAX_RIDERS
        val region = CfwModes.mainRegion(cache, true)
        val full = rig.staging(cache, region)
        val short = ListStaging.build(rig.layout, rig.view, rig.kit, rig.listD, rig.bandD, cache, region.first, full.end - 4,
            widths = listOf(ListStaging.WHOLE), motion = ListStaging.ScrollMotion.DEFAULT)
        assertNull(short.progSlots, "the spare slot given up")
        assertTrue(short.wrap != null, "the wrap kept")
        assertEquals(digest(full.pans), digest(short.pans), "the same pans")
        assertTrue(short.remake(ListStaging.ScrollMotion.DEFAULT.copy(effect = ScrollEffect.SLIDE)) is ListStaging.Remake.NoRoom)
    }

    // ------------------------------------------------------------------------------- C3 DM-2a: Matrix and its records

    /** Every record of [ms] (its name, offset and bytes), its pan slots, its end, P and T — the staging as the glasses get it. */
    private fun stagingDigest(ms: ListStaging): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (b in ms.blobs) { md.update("${b.name}@${b.off}:".toByteArray()); md.update(b.bytes) }
        md.update("slot ${ms.progSlot} ${ms.progSlots?.toList()} end ${ms.end} P ${ms.period} T ${ms.t}".toByteArray())
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /** Today's stagings ("height KiB stage effect" → [stagingDigest]), measured on the tree before DM-2a (`b9744a18` with
     *  DM-2a part 1, which changes only the window form) by the same digest. */
    private val todaysStagings = mapOf(
        "288 160 4 none" to "a81a0f5f77e2bc3d", "288 160 4 depth" to "365e73feb912e4a5", "288 160 4 slide" to "8b7147d9586888da",
        "288 160 4 flip" to "7680bfb1330739e3", "288 160 4 fall" to "f8151fe78d1e9488", "288 160 4 crush" to "8b7147d9586888da",
        "288 160 5 none" to "98e9d6af04897b70", "288 160 5 depth" to "0a2377d030710e97", "288 160 5 slide" to "5b5f1e74150216b1",
        "288 160 5 flip" to "134893f0e4848c53", "288 160 5 fall" to "6839690fe7a12be8", "288 160 5 crush" to "bb079feaa0e5436c",
        "288 256 4 none" to "a81a0f5f77e2bc3d", "288 256 4 depth" to "365e73feb912e4a5", "288 256 4 slide" to "8b7147d9586888da",
        "288 256 4 flip" to "7680bfb1330739e3", "288 256 4 fall" to "f8151fe78d1e9488", "288 256 4 crush" to "8b7147d9586888da",
        "288 256 5 none" to "98e9d6af04897b70", "288 256 5 depth" to "0a2377d030710e97", "288 256 5 slide" to "5b5f1e74150216b1",
        "288 256 5 flip" to "134893f0e4848c53", "288 256 5 fall" to "6839690fe7a12be8", "288 256 5 crush" to "bb079feaa0e5436c",
        "352 160 4 none" to "202815ff73c68606", "352 160 4 depth" to "ae7ff9d276084515", "352 160 4 slide" to "cefae98fef2fd6e0",
        "352 160 4 flip" to "8ce30022942427a8", "352 160 4 fall" to "418529a21cbb9077", "352 160 4 crush" to "cefae98fef2fd6e0",
        "352 160 5 none" to "17f6082dfac69924", "352 160 5 depth" to "3bea12fffa8706b6", "352 160 5 slide" to "975f162387a884cb",
        "352 160 5 flip" to "1a05c316422179ca", "352 160 5 fall" to "eec17ed54dca8a52", "352 160 5 crush" to "c1ad5c657813bf77",
        "352 256 4 none" to "202815ff73c68606", "352 256 4 depth" to "ae7ff9d276084515", "352 256 4 slide" to "cefae98fef2fd6e0",
        "352 256 4 flip" to "8ce30022942427a8", "352 256 4 fall" to "418529a21cbb9077", "352 256 4 crush" to "cefae98fef2fd6e0",
        "352 256 5 none" to "17f6082dfac69924", "352 256 5 depth" to "3bea12fffa8706b6", "352 256 5 slide" to "975f162387a884cb",
        "352 256 5 flip" to "1a05c316422179ca", "352 256 5 fall" to "eec17ed54dca8a52", "352 256 5 crush" to "c1ad5c657813bf77",
        "416 160 4 none" to "f643fdae0216823a", "416 160 4 depth" to "c681e454c5eef178", "416 160 4 slide" to "0154d51ebf12ddd5",
        "416 160 4 flip" to "9ed1cfc6812da864", "416 160 4 fall" to "7769eb2024f5a3c5", "416 160 4 crush" to "0154d51ebf12ddd5",
        "416 160 5 none" to "a015126cf400c47a", "416 160 5 depth" to "fa3ee964c36adf9e", "416 160 5 slide" to "022df0a7922dead5",
        "416 160 5 flip" to "00079ce6debf1ad0", "416 160 5 fall" to "553c415fb9a41363", "416 160 5 crush" to "cadb41d71af16f7d",
        "416 256 4 none" to "f643fdae0216823a", "416 256 4 depth" to "c681e454c5eef178", "416 256 4 slide" to "0154d51ebf12ddd5",
        "416 256 4 flip" to "9ed1cfc6812da864", "416 256 4 fall" to "7769eb2024f5a3c5", "416 256 4 crush" to "0154d51ebf12ddd5",
        "416 256 5 none" to "a015126cf400c47a", "416 256 5 depth" to "fa3ee964c36adf9e", "416 256 5 slide" to "022df0a7922dead5",
        "416 256 5 flip" to "00079ce6debf1ad0", "416 256 5 fall" to "553c415fb9a41363", "416 256 5 crush" to "cadb41d71af16f7d",
        "480 160 4 none" to "084fc8f4a54718fe", "480 160 4 depth" to "152d18ddaf50eecf", "480 160 4 slide" to "127c2b256ba04938",
        "480 160 4 flip" to "d0283ec34fa7603d", "480 160 4 fall" to "59f2cbb981c9d4e8", "480 160 4 crush" to "127c2b256ba04938",
        "480 160 5 none" to "cb81a5d60f56bec2", "480 160 5 depth" to "6e92816352afb0eb", "480 160 5 slide" to "8965a3b168d171f0",
        "480 160 5 flip" to "73dc6412095e4364", "480 160 5 fall" to "e61fd8838fe45f7e", "480 160 5 crush" to "1077923b39641f77",
        "480 256 4 none" to "084fc8f4a54718fe", "480 256 4 depth" to "152d18ddaf50eecf", "480 256 4 slide" to "127c2b256ba04938",
        "480 256 4 flip" to "d0283ec34fa7603d", "480 256 4 fall" to "59f2cbb981c9d4e8", "480 256 4 crush" to "127c2b256ba04938",
        "480 256 5 none" to "cb81a5d60f56bec2", "480 256 5 depth" to "6e92816352afb0eb", "480 256 5 slide" to "8965a3b168d171f0",
        "480 256 5 flip" to "73dc6412095e4364", "480 256 5 fall" to "e61fd8838fe45f7e", "480 256 5 crush" to "1077923b39641f77")

    /**
     * C3 DM-2a, the pan slots' and the records' pin (decisions DV 2, DW 3, DW 4; the DM-2 spec §8): a staging with no record
     * effect in force is today's byte for byte — every record's offset and bytes, the pan slots, the end, P and T — at the
     * four heights, both caches, below and at stage 5, for every effect without records; Matrix asked below stage 5 is
     * Depth's staging byte for byte, said. Where a Matrix staging's records do not fit, they are given up FIRST — the
     * staging is Depth's, byte for byte, with the spare pan slot kept (today's order then follows) — said "does not fit".
     */
    @Test
    fun aStagingWithoutRecordsIsTodaysAndRecordsAreGivenUpFirst_DM2a() {
        var n = 0
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) for (s5 in listOf(false, true)) {
                val region = CfwModes.mainRegion(cache, true)
                fun st(e: ScrollEffect, end: Int = region.last + 1) = ListStaging.build(rig.layout, rig.view, rig.kit, rig.listD, rig.bandD, cache, region.first, end,
                    widths = listOf(ListStaging.WHOLE), motion = ListStaging.ScrollMotion.DEFAULT.copy(effect = e), stage5 = s5, fx = MainLocalCheck.effectsAt(s5), text = rig.text)
                val what0 = "$h ${cache / 1024} KiB stage ${if (s5) 5 else 4}"
                for (e in ScrollEffect.entries.filter { !ScrollEffects.hasRecords(it) }) {
                    val ms = st(e)
                    assertEquals(todaysStagings["$h ${cache / 1024} ${if (s5) 5 else 4} ${e.id}"], stagingDigest(ms), "$what0 ${e.word}: today's staging byte for byte")
                    assertNull(ms.recordEffect, "$what0 ${e.word}: no records")
                    n++
                }
                val mx = st(ScrollEffect.MATRIX)
                val depth = st(ScrollEffect.DEPTH)
                if (!s5) {
                    assertEquals(stagingDigest(depth), stagingDigest(mx), "$what0: Matrix below stage 5 is Depth's staging")
                    assertEquals("plays Depth", mx.plays.words(ScrollEffect.MATRIX), "$what0: said")
                    continue
                }
                assertEquals(ScrollEffect.MATRIX, mx.recordEffect, "$what0: Matrix's staging holds its records")
                assertEquals(depth.blobs.take(depth.blobs.size - 1).map { it.name to it.off }, mx.blobs.take(depth.blobs.size - 1).map { it.name to it.off }, "$what0: nothing before the programs moves")
                assertEquals(listOf("programs", "Matrix's records"), mx.blobs.takeLast(2).map { it.name }, "$what0: the records last")
                assertEquals(mx.progSlots!![1] + mx.progSlot, mx.blobs.last().off, "$what0: the records right past the spare pan slot")
                // given up first: a region that ends one word short of Matrix's records
                val short = st(ScrollEffect.MATRIX, mx.end - 4)
                val dShort = st(ScrollEffect.DEPTH, mx.end - 4)
                assertEquals(stagingDigest(dShort), stagingDigest(short), "$what0: Matrix's records do not fit — Depth's staging in that region byte for byte")
                assertTrue(short.progSlots != null && short.recordEffect == null, "$what0: the records given up before the spare pan slot")
                assertTrue(short.plays.effect == ScrollEffect.DEPTH && short.plays.why!!.startsWith("does not fit"), "$what0: said: ${short.plays}")
            }
        }
        println("DM2a-today: $n stagings without records are today's byte for byte")
    }

    /**
     * C3 DM-2a, the records rule (decisions DW 1, DW 2; the DM-2 spec §8): Depth → Matrix → Depth on Main at stage 5 — a
     * programs-only re-make into Matrix from a staging without its records, and out of it from Matrix's, is refused
     * (NoRoom, its reason the records), so each change is a full staging (the shell's branch); Matrix's own Auto re-make
     * (another T at the period in force) keeps its records and fits its pan slot; and below stage 5 Matrix asked of a Depth
     * staging is Depth's pans re-made (nothing to stage).
     */
    @Test
    fun aChangeIntoOrOutOfMatrixIsAFullStaging_DM2a() {
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) {
                val fx = MainLocalCheck.effectsAt(true)
                val m = ListStaging.ScrollMotion.DEFAULT
                val depth = stage5(h, cache, ScrollEffect.DEPTH, rig)
                val into = depth.remake(m.copy(effect = ScrollEffect.MATRIX), fx)
                assertTrue(into is ListStaging.Remake.NoRoom && into.records && "Matrix's records are staging records" in into.why, "$h ${cache / 1024}: into Matrix: ${(into as? ListStaging.Remake.NoRoom)?.why}")
                val mx = stage5(h, cache, ScrollEffect.MATRIX, rig)
                val out = mx.remake(m.copy(effect = ScrollEffect.DEPTH), fx)
                assertTrue(out is ListStaging.Remake.NoRoom && out.records && "Matrix's records are staging records" in out.why, "$h ${cache / 1024}: out of Matrix: ${(out as? ListStaging.Remake.NoRoom)?.why}")
                mx.periodInForce = mx.period + 8
                val auto = assertNotNull((mx.remake(m.copy(effect = ScrollEffect.MATRIX), fx) as? ListStaging.Remake.Owed)?.programs, "$h ${cache / 1024}: Matrix's Auto re-make")
                assertEquals(ScrollEffect.MATRIX to true, auto.plays.effect to auto.plays.v2, "$h ${cache / 1024}: Matrix's own pans at the new T ${auto.t}")
                val below = staging(h, cache, ScrollEffect.DEPTH, rig).remake(m.copy(effect = ScrollEffect.MATRIX))
                assertEquals(ScrollEffect.DEPTH, (below as? ListStaging.Remake.Owed)?.programs?.plays?.effect, "$h ${cache / 1024}: below stage 5 Matrix plays Depth's pans")
            }
        }
    }

    /**
     * C3 DM-2a, Matrix (the DM spec §1.5, §5's pins; decisions DR (b), (n), AD's D1): at stage 5, at EVERY cursor a notch
     * lands on, both directions, the four heights, both caches — the model's END (`Motion.endOnModel`) is Main's own paint
     * at the new cursor in the program's areas, and so is belief's; every bound program's first read range lies inside
     * Main's region (D1: private records, no atlas byte); at the middle step lit rain cells differ from Depth's pan at the
     * same T in the rows' areas and nowhere in the band (the rain falls behind it), and at step T none do. C3 DM-2aF
     * (decisions FA 2): at 256 KiB on [NoisyText], whose 16 noise codes are 16 different glyphs (the box face draws them as
     * one), the rain's whole cells at the middle step show two or more of the noise set's glyphs as staged ([rainGlyphs]) —
     * so a rain of one code, or of fills with no glyph, is seen; at 160 KiB the box face (NoisyText's rows alone overfill
     * Main's 64 KiB region there).
     */
    @Test
    fun matrixsRainFallsThroughTheRowsAndIsGoneAtT_DM2a() {
        var n = 0
        val fit = ArrayList<String>()
        var cells = 0
        val kinds = HashSet<Int>()
        for (h in heights) {
            val noisyRig = MainLocalCheck.MainRig(NoisyText(), h, MainLocalCheck.sampleRows())
            val boxRig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) {
                val noisy = cache == CfwModes.CACHE2_MAX_RIDERS
                val rig = if (noisy) noisyRig else boxRig
                val ms = stage5(h, cache, ScrollEffect.MATRIX, rig)
                val what0 = "$h ${cache / 1024} KiB Matrix"
                assertEquals(ScrollEffects.Plays(ScrollEffect.MATRIX, true), ms.plays, "$what0: its own version-2 pans at stage 5")
                assertEquals(-ms.layout.rowH, ms.boundList(1).pitch, "$what0: pitch −rowH (Depth's rules)")
                val cacheImg = cacheOf(ms)
                val records = listOfNotNull(ms.strip.record, ms.wrap?.record) + ms.bands.map { it.record }
                val staged = ScrollEffects.Staged(ms.layout, ms.listD, ms.bandD, ms.bands[0].record, records)
                // C3 DM-2aF: the noise set's glyphs as staged — the first 16 records of Matrix's records, in the alphabet's
                // order, read from the staged bytes (not through the font table the glasses' walk reads)
                val recs = ms.blobs.last()
                assertEquals("Matrix's records", recs.name, "$what0: Matrix's records lie last")
                val noise = ArrayList<Triple<Int, Int, ByteArray>>()
                var o = 0
                for (code in ScrollEffects.NOISE_CODES) {
                    val gw = recs.bytes[o].toInt() and 0xFF; val gh = recs.bytes[o + 1].toInt() and 0xFF
                    val (px, end) = assertNotNull(wm.damage.core.sim.MotionSim.rle(recs.bytes, o + 2, gw * gh), "$what0: the glyph record of '${code.toChar()}' at ${recs.off + o}")
                    noise += Triple(gw, gh, px); o = (end + 3) and 3.inv()
                }
                if (noisy) assertEquals(noise.size, noise.map { it.third.toList() }.distinct().size, "$what0: the face draws the ${noise.size} noise codes as as many glyphs")
                for (nt in notches(ms)) {
                    Motion.play(nt.pan.program, ListStaging.SLOT, ms.period, 1, 0, nt.p)
                    val range = nt.pan.program.firstRange(nt.p)
                    assertTrue(range.first >= ms.regionStart && range.last < ms.end, "$what0 ${nt.c}→${nt.to}: the read range $range inside Main's ${ms.regionStart}..${ms.end}")
                    val depth = ScrollEffects.program(ScrollEffects.Plays(ScrollEffect.DEPTH, false), staged,
                        if (nt.pan.strip == 0) ms.strip.record else ms.wrap!!.record, nt.pan.kA, nt.pan.kB, nt.pan.step, ms.t, nt.pan.prog4)
                    val bandAreas = listOf(nt.pan.program.tracks.last().area(true), nt.pan.program.tracks.last().area(false))
                    for (left in listOf(true, false)) {
                        val base = panel(rig.truth(nt.c, left))
                        val what = "$what0 step ${nt.pan.step} ${nt.c}→${nt.to} left=$left"
                        val end = Motion.endOnModel(nt.pan.program, nt.p, cacheImg, base, base, left, tag = 7)
                        assertEquals(0, differModel(end, rig.truth(nt.to, left), nt.pan.areas(left)), "$what: px where the model's END is not Main's paint at ${nt.to}")
                        val belief = rig.truth(nt.c, left).copy().also { ms.paintEnd(nt.pan, nt.p, left, it) }
                        assertEquals(0, differModel(end, belief, nt.pan.areas(left)), "$what: px where belief's END is not the model's")
                        val mid = ms.t / 2
                        fun lit(k: Int, areas: List<Rect>): Int {
                            val a = Motion.stepOnModel(nt.pan.program, nt.p, cacheImg, base, base, left, tag = 7, k = k)
                            val b = Motion.stepOnModel(depth, nt.p, cacheImg, base, base, left, tag = 7, k = k)
                            var d = 0
                            for (r in areas) for (y in r.y until r.bottom) for (x in r.x until r.right) if (nibble(a, x, y) != nibble(b, x, y)) d++
                            return d
                        }
                        val rows = listOf(nt.pan.program.tracks[1].area(left), nt.pan.program.tracks[2].area(left))
                        assertTrue(lit(mid, rows) > 0, "$what: at step $mid of ${ms.t} the rain lights cells over the rows")
                        if (noisy) {
                            val seen = rainGlyphs(Motion.stepOnModel(nt.pan.program, nt.p, cacheImg, base, base, left, tag = 7, k = mid),
                                nt.pan.program.tracks[3].area(left), bandAreas[if (left) 0 else 1], noise)
                            assertTrue(seen.toSet().size >= 2, "$what: at step $mid the rain's whole cells show ${seen.size} noise glyph(s) of " +
                                "${seen.toSet().size} kind(s): ${seen.toSet().map { ScrollEffects.NOISE_CODES[it].toChar() }} — two kinds or more are the noise set's")
                            cells += seen.size; kinds += seen
                        }
                        assertEquals(0, lit(mid, listOf(bandAreas[if (left) 0 else 1])), "$what: no rain over the band at step $mid")
                        assertEquals(0, lit(ms.t, nt.pan.areas(left)), "$what: at step T no cell is lit")
                    }
                    n++
                }
                if (cache == CfwModes.CACHE2_MAX_RIDERS) fit += "h$h: P ${ms.period} (${"%.1f".format(ms.period * 1000.0 / 1024)} ms) T ${ms.t}, the step " +
                    "${"%.1f".format(ListStaging.fastStepMs(ScrollEffects.areaPx(ScrollEffect.MATRIX, ms.layout, ms.listD, ms.bandD), ScrollEffects.walkBytes(ScrollEffect.MATRIX, ms.layout), 0, ScrollEffects.rainCells(ScrollEffect.MATRIX, ms.layout)))} ms, " +
                    "${ms.pan(ListStaging.WHOLE, 1).areaPx} px a step, C ${"%,d".format(ms.pans.maxOf { p -> (0 until ms.rows).mapNotNull { c -> ms.params(p, c) }.maxOf { p.program.cost(it) } })} u at most; " +
                    "the records ${ms.blobs.last().bytes.size} B, a pan slot ${ms.progSlot} B, the free span ${ms.freeSpan} B"
            }
        }
        println("DM2a-matrix (NoisyText at 256 KiB, the box face at 160 KiB; modeled): the model's END = Main's paint = belief at $n notches; at the middle step $cells whole rain " +
            "cells drew a noise glyph, ${kinds.size} of the ${ScrollEffects.NOISE_CODES.size} codes seen; the fit at 256 KiB:\n" + fit.joinToString("\n"))
    }

    /** C3 DM-2aF (decisions FA 2): the noise glyphs in [pic] (a panel's nibbles) — each whole rain cell of [area] (Matrix's
     *  T3: column c at its x + c·[ScrollEffects.RAIN_CW], cell row r at its y + r·ch, the rain's X and Y being 0; §7.6 RAIN)
     *  inside the panel and clear of [band] (drawn over the rain) whose every pixel is one of [glyphs] (w, h, levels) at one
     *  level L of 1..15 — its box filled 0, the glyph at the box's corner through `make_lut(L)` — gives that glyph's index.
     *  A cell of the rows' own picture, or a fill with no glyph, gives none. */
    private fun rainGlyphs(pic: ByteArray, area: Rect, band: Rect, glyphs: List<Triple<Int, Int, ByteArray>>): List<Int> {
        val cw = ScrollEffects.RAIN_CW; val ch = glyphs[0].second
        val out = ArrayList<Int>()
        fun drawn(x0: Int, y0: Int, g: Triple<Int, Int, ByteArray>, lv: Int): Boolean {
            for (yy in 0 until ch) for (xx in 0 until cw) {
                val v = if (xx < g.first && yy < g.second) g.third[yy * g.first + xx].toInt() else 0
                if (nibble(pic, x0 + xx, y0 + yy) != v * lv / 15) return false
            }
            return true
        }
        var y0 = area.y
        while (y0 + ch <= area.bottom) {
            var x0 = area.x
            while (x0 + cw <= area.right) {
                if (x0 >= 0 && y0 >= 0 && x0 + cw <= wm.damage.core.geom.Geometry.PANEL_W && y0 + ch <= wm.damage.core.geom.Geometry.PANEL_H &&
                    Rect(x0, y0, cw, ch).intersect(band) == null) {
                    val hit = glyphs.indices.firstOrNull { i -> (1..15).any { lv -> drawn(x0, y0, glyphs[i], lv) } }
                    if (hit != null) out += hit
                }
                x0 += cw
            }
            y0 += ch
        }
        return out
    }

    /** Pixels where [l]'s rows' areas (Matrix's T1 above the band and T2 below it, where the rain falls in view) differ from
     *  [r]'s, each lens's area read at its own plane — [pan]'s areas on LEFT against RIGHT's, or one lens's against itself —
     *  clipped to the panel. */
    private fun rowsDiffer(l: ByteArray, r: ByteArray, pan: ListStaging.Pan, lLeft: Boolean = true, rLeft: Boolean = false): Int {
        var d = 0
        for (t in listOf(1, 2)) {
            val a = pan.program.tracks[t].area(lLeft); val b = pan.program.tracks[t].area(rLeft)
            for (y in 0 until a.h) for (x in 0 until a.w) {
                val xa = a.x + x; val ya = a.y + y; val xb = b.x + x; val yb = b.y + y
                if (xa !in 0 until wm.damage.core.geom.Geometry.PANEL_W || xb !in 0 until wm.damage.core.geom.Geometry.PANEL_W ||
                    ya !in 0 until wm.damage.core.geom.Geometry.PANEL_H || yb !in 0 until wm.damage.core.geom.Geometry.PANEL_H) continue
                if (nibble(l, xa, ya) != nibble(r, xb, yb)) d++
            }
        }
        return d
    }

    /**
     * C3 DM-2aF (decisions EV 1; `C3-DM2a-V1-a.md` F1): Matrix's rain is one picture on both lenses whatever their S. A bound
     * fire's tag is the lens's own S (`FIRMWARE.md` §7.2: the seed is h0 after substitution, the tag mixed in only under opt
     * bit 7), and the two S can differ for the rest of a lease (§6.4 "Both lenses": LEFT ahead after a dropped inter-lens
     * answer). With LEFT's S set one ahead of RIGHT's (the counter set, as a dropped answer leaves it), Main's Matrix pans at
     * stage 5, 256 KiB, the four heights, fired through BOTH lenses' hooks at every cursor in both directions, draw at EVERY
     * step 1..T the same rows' areas (above and below the band, where the rain is in view) on LEFT and RIGHT, each at its own
     * plane; a notch fired again from the same row (both S moved on) draws them again step for step — the rain follows the
     * row it lands on, not S; and on the model a notch's parameters with another row's P1 (the entering row's band form,
     * which seeds the rain) draw other rain over the same rows at some step.
     */
    @Test
    fun matrixsRainIsOnePictureOnBothLensesWhateverTheirS_DM2aF() {
        val cache = CfwModes.CACHE2_MAX_RIDERS
        var steps = 0
        val lenses = ArrayList<String>(); val again = ArrayList<String>(); val sameRain = ArrayList<String>()
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val ms = stage5(h, cache, ScrollEffect.MATRIX, rig)
            assertEquals(ScrollEffects.Plays(ScrollEffect.MATRIX, true), ms.plays, "$h: Matrix plays its own version-2 pans")
            val list = ms.boundList(1)
            val sim = wm.damage.core.sim.GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = MainLocalCheck.features(true); it.clockIsOsTick = true }
            val lens = MainLocalCheck.Lens(sim, cache)
            val fails = ArrayList<String>()
            for (arm in wm.damage.core.transport.Arm.entries) {
                sim.conformanceLease(arm, true, lens.now)
                sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_CACHE_SIZE, cache / 1024, lens.now)
                sim.conformanceControl(arm, wm.damage.core.wire.DamageMsg.OP_FLAGS_SET, MainLocalCheck.flags(true), lens.now)
            }
            for (b in ms.blobs) lens.write(b.off, b.bytes, b.name, fails)
            for (s in list.sets()) lens.send("a SET", s, fails)
            lens.send("the LIST", list.listMessage(0), fails)
            assertEquals(emptyList(), fails, "$h: the staging written and the list SET on both lenses")
            sim.bindForTest(wm.damage.core.transport.Arm.LEFT).seqForTest(1)     // LEFT's S one ahead, as a dropped answer leaves it
            /** One notch of [e] from row [c] through both hooks: the pan it plays and each step's (LEFT, RIGHT) panels; null
             *  where the list's edge is (no pan plays there). */
            fun notch(e: BoundList.Entry, c: Int): Pair<ListStaging.Pan, List<Pair<ByteArray, ByteArray>>>? {
                val f = list.predict(c, e) as? BoundList.Fire.Drew ?: return null
                lens.now += 1_000
                lens.keyframe(rig.truth(c, true), rig.truth(c, false))
                lens.send("DISCARD to cursor $c", CfwModes.motionDiscard(ListStaging.SLOT, 1, ListStaging.LIST, c), fails)
                lens.notifies()
                val a = assertNotNull(lens.fire(CfwModes.DEV_RING, e.eventId), "$h ${e.step} from $c: RIGHT's report")
                assertEquals(f.prog4 to f.cursor, a.prog to a.cursor, "$h ${e.step} from $c: drew as predicted (${a.describe()})")
                val pan = ms.pans.first { it.prog4 == f.prog4 }
                val t0 = lens.now
                val out = (1..ms.t).map { k -> lens.wake(t0 + (k - 1).toLong() * e.period); lens.panel(wm.damage.core.transport.Arm.LEFT) to lens.panel(wm.damage.core.transport.Arm.RIGHT) }
                lens.wake(t0 + ms.t.toLong() * e.period)
                lens.notifies()
                return pan to out
            }
            // the three parts each count their misses, so a run shows every one of them (the watch reads all three)
            val first = HashMap<Pair<Int, Int>, List<Pair<ByteArray, ByteArray>>>()
            for (e in list.entries.filter { !it.isTap }) for (c in 0 until ms.rows) {
                val (pan, got) = notch(e, c) ?: continue
                for ((k, lr) in got.withIndex()) {
                    val d = rowsDiffer(lr.first, lr.second, pan)
                    if (d > 0) lenses += "$h step ${e.step} from $c, step ${k + 1}: $d px"
                    steps++
                }
                first[e.step to c] = got
            }
            assertEquals(emptyList(), fails, "$h: every DISCARD applied")
            // the same notches again: both lenses' S have moved on by 2·rows, and every step is the first time's
            for (e in list.entries.filter { !it.isTap }) for (c in listOf(0, ms.rows / 2)) {
                val was = first[e.step to c] ?: continue
                val (pan, got) = notch(e, c) ?: continue
                for ((k, lr) in got.withIndex()) rowsDiffer(was[k].second, lr.second, pan, lLeft = false).let { d -> if (d > 0) again += "$h step ${e.step} from $c, step ${k + 1}: $d px" }
            }
            // another row's P1 over the same rows: other rain (the model, one tag for both)
            val cacheImg = cacheOf(ms)
            val ns = notches(ms, activeOnly = true)
            for (nt in ns.filter { it.c == ms.rows / 2 }) {
                val other = ns.first { it.pan === nt.pan && it.p[1] != nt.p[1] }
                val p2 = nt.p.copyOf().also { it[1] = other.p[1] }
                val base = panel(rig.truth(nt.c, true))
                val moved = (1..ms.t).count { k -> rowsDiffer(Motion.stepOnModel(nt.pan.program, nt.p, cacheImg, base, base, true, tag = 7, k = k),
                    Motion.stepOnModel(nt.pan.program, p2, cacheImg, base, base, true, tag = 7, k = k), nt.pan, rLeft = true) > 0 }
                if (moved == 0) sameRain += "$h step ${nt.pan.step} from ${nt.c}: P1 of row ${other.to} for ${nt.to}"
            }
        }
        assertEquals(Triple(emptyList<String>(), emptyList<String>(), emptyList<String>()), Triple(lenses.take(6), again.take(6), sameRain.take(6)),
            "(a) of $steps steps, ${lenses.size} where LEFT's rows (S one ahead) are not RIGHT's; (b) ${again.size} steps where a notch from the same row " +
            "(S moved on) is not its first time's; (c) ${sameRain.size} notches where another row's P1 draws the same rain at every step")
        println("DM2aF-lenses: $steps steps with LEFT's S one ahead drew the same rows on both lenses")
    }

    /**
     * C3 DM-2aF, the class (decisions EV 1): no program fired from a bound list mixes the fire's tag into a seed — opt bit 7
     * of a PIECES or GLYPHS segment (`FIRMWARE.md` §7.2) — since a local fire's tag is each lens's own S. Every program the
     * stagings bind carries none: Main's pans for every effect at stage 5 (version 2) and below it (version 1), the four
     * heights, both caches, and its press; a window list's pans for every effect; the wheel's two notches, with motion and
     * without (Matrix at stage 5 holds its rain, so the sweep reads a seeded verb). The refusal that holds it for an effect
     * still to come (Scramble) is [theStagingsCheckRefusesABoundPanThatMixesTheTag_DM2aF].
     */
    @Test
    fun noBoundProgramMixesTheTagIntoASeed_DM2aF() {
        fun mixes(p: wm.damage.core.motion.MotionProgram) = p.tracks.any { t -> t.segments.any { (it.verb == Motion.PIECES || it.verb == Motion.GLYPHS) && it.opt and 0x80 != 0 } }
        var n = 0
        var seeded = 0
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) for (s5 in listOf(false, true)) for (e in ScrollEffect.entries) {
                val ms = rig.staging(cache, CfwModes.mainRegion(cache, true), ListStaging.ScrollMotion.DEFAULT.copy(effect = e), stage5 = s5)
                val what = "$h ${cache / 1024} KiB stage ${if (s5) 5 else 4} ${e.word}"
                if (s5 && e == ScrollEffect.MATRIX) assertEquals(ScrollEffects.Plays(ScrollEffect.MATRIX, true), ms.plays, "$what: Matrix plays itself")
                for (p in ms.pans.map { it.program } + ms.press.program) {
                    assertTrue(!mixes(p), "$what: a bound program at off4 ${p.at} mixes the tag into a seed")
                    if (p.tracks.any { t -> t.segments.any { it.verb == Motion.GLYPHS } }) seeded++
                    n++
                }
            }
        }
        assertTrue(seeded > 0, "the sweep read Matrix's rain")
        val wrig = WindowListCheck.WindowRig(BoxFace, 352, WindowListCheck.Sample.GLOBAL)
        for (e in ScrollEffect.entries) for (p in wrig.staging(e, region256.first, region256.last + 1).pans) { assertTrue(!mixes(p.program), "a window list's ${e.word} pan mixes the tag"); n++ }
        for (none in listOf(false, true)) {
            val b = wm.damage.core.motion.WheelCheck.Rig(BoxFace, 352, 6, 8).block(none = none)
            for (step in intArrayOf(1, -1)) { assertTrue(!mixes(b.program(step)), "the wheel's ${if (step > 0) "down" else "up"} program (none $none) mixes the tag"); n++ }
        }
        println("DM2aF-class: $n bound programs, $seeded with Matrix's rain, mix no tag")
    }

    /**
     * C3 DM-2aF, the class's refusal (decisions EV 1): the staging's own check of a bound pan ([ListStaging.checkEveryCursor],
     * run on every pan a staging or a re-make lays, Main's and a window's) refuses a program that mixes the fire's tag into
     * a seed, saying why — a pan of Matrix's areas and rain with the tag mixed in is refused, the same pan without it passes
     * — so such an effect plays its fallback on Main, or a window's list is not staged, said; never a silent change of it.
     */
    @Test
    fun theStagingsCheckRefusesABoundPanThatMixesTheTag_DM2aF() {
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        val ms = stage5(352, CfwModes.CACHE2_MAX_RIDERS, ScrollEffect.MATRIX, rig)
        val real = ms.pan(ListStaging.WHOLE, 1)
        val rainSeg = real.program.tracks[3].segments.single()
        fun with(mix: Boolean) = ListStaging.Pan(real.k, real.kA, real.kB, real.step, Motion.program2(real.prog4, MainLocalCheck.effectsAt(true)!!) {
            val w = real.program.tracks[0]
            track(w.areaL, w.areaR, Motion.Backdrop.Level(0)) { wait(ms.t) }
            track(w.areaL, w.areaR, Motion.Backdrop.None) { rain(rainSeg.rain!!, ms.t, x = 0, xR = 0, mixTag = mix) }
        }, real.rules, real.strip, real.pitch)
        ListStaging.checkEveryCursor(with(false), ms.rows, ms.ring, ms.itemOffs)
        val refused = assertFailsWith<LintError>("the staging's check refuses a bound pan that mixes the tag into its seed") {
            ListStaging.checkEveryCursor(with(true), ms.rows, ms.ring, ms.itemOffs)
        }
        assertTrue("tag" in (refused.message ?: "") && "seed" in (refused.message ?: ""), "said: ${refused.message}")
        println("DM2aF-refusal: ${refused.message}")
    }

    // ------------------------------------------------------------------------------- C3 DM-2b: Scramble and its cells

    /** Main drawn through a cached text whose atlas holds its faces (the phone with `Cached text` on): Scramble's runs. */
    private fun cachedRig(h: Int) = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows(), cached = true)

    /** Each cell box a notch's cell tracks draw on [left]'s lens with the parameters [p], clipped to its track's area, with
     *  its cell: the selected list (P2) at the track's offset. */
    /** Each cell's box on the lens (clipped to its track's area), the cell, and its track's index. */
    private fun cellBoxes(prog: wm.damage.core.motion.MotionProgram, p: IntArray, left: Boolean): List<Triple<Rect, Motion.Cell, Int>> {
        val out = ArrayList<Triple<Rect, Motion.Cell, Int>>()
        prog.resolve(p, staticOnly = false).forEachIndexed { ti, segs ->
            val area = prog.tracks[ti].area(left)
            for (r in segs) {
                val tb = r.cells ?: continue
                val x0 = if (left) r.xL else r.xR
                for (c in tb.lists[r.aux0].cells) Rect(x0 + c.bx, r.y + c.by, c.bw, c.bh).intersect(area)?.let { out += Triple(it, c, ti) }
            }
        }
        return out
    }

    /**
     * C3 DM-2b, Scramble (the DM spec §1.4, §5's pins; the DM-2 spec §1): at stage 5 on Main drawn through its atlas, at
     * EVERY cursor a notch lands on, both directions, the four heights, both caches — the model's END is Main's own paint at
     * the new cursor in the program's areas, and so is belief's; every bound program's first read range lies inside Main's
     * region (D1: private records, no atlas byte) and its step budget inside 80 % of the cap. The picture: outside every
     * cell box it is END from step 1 (nothing moves); at step 1 the old letters stand in their boxes (at least half of them:
     * a cell whose noise starts at step 1 shows noise); at the middle step some box holds neither its old picture nor END's
     * (an alphabet glyph). Where the records do not fit the staging is Depth's, said.
     */
    @Test
    fun scramblesLettersChangeInPlaceAndSettleOnMainsPaint_DM2b() {
        var n = 0
        val fit = ArrayList<String>()
        for (h in heights) {
            val rig = cachedRig(h)
            for (cache in caches) {
                val ms = stage5(h, cache, ScrollEffect.SCRAMBLE, rig)
                val what0 = "$h ${cache / 1024} KiB Scramble"
                if (ms.recordEffect == null) {
                    assertEquals(ScrollEffect.DEPTH, ms.plays.effect, "$what0: its records given up, Depth plays")
                    assertTrue(ms.plays.why != null, "$what0: said")
                    fit += "h$h ${cache / 1024} KiB: plays Depth — ${ms.plays.why}"
                    continue
                }
                assertEquals(ScrollEffects.Plays(ScrollEffect.SCRAMBLE, true), ms.plays, "$what0: its own version-2 pans at stage 5")
                assertEquals(listOf("programs", "Scramble's records"), ms.blobs.takeLast(2).map { it.name }, "$what0: the records last")
                val cacheImg = cacheOf(ms)
                var oldCells = 0; var oldShown = 0; var noisy = 0
                // the band's own (T7, track index 6): a wrong table index puts another row's letters there
                var bandOld = 0; var bandShown = 0
                for (nt in notches(ms)) {
                    Motion.play(nt.pan.program, ListStaging.SLOT, ms.period, 1, 0, nt.p)
                    // the staging's own parameters (its belief's, `ListStaging.params`) name the list's P2: the cursor before
                    assertEquals(nt.c, ms.params(nt.pan, nt.to)!![2], "$what0 ${nt.c}→${nt.to}: the staging's P2 is the cursor before the move")
                    assertEquals(nt.c, nt.p[2], "$what0 ${nt.c}→${nt.to}: the list's P2 is the cursor before the move")
                    val range = nt.pan.program.firstRange(nt.p)
                    assertTrue(range.first >= ms.regionStart && range.last < ms.end, "$what0 ${nt.c}→${nt.to}: the read range $range inside Main's ${ms.regionStart}..${ms.end}")
                    assertTrue(nt.pan.program.cost(nt.p) * 100 <= Motion.BUDGET_CAP * ListStaging.BUDGET_PCT, "$what0 ${nt.c}→${nt.to}: inside 80 % of the cap")
                    for (left in listOf(true, false)) {
                        val start = rig.truth(nt.c, left)
                        val base = panel(start)
                        val what = "$what0 step ${nt.pan.step} ${nt.c}→${nt.to} left=$left"
                        val end = Motion.endOnModel(nt.pan.program, nt.p, cacheImg, base, base, left, tag = 7)
                        assertEquals(0, differModel(end, rig.truth(nt.to, left), nt.pan.areas(left)), "$what: px where the model's END is not Main's paint at ${nt.to}")
                        val belief = start.copy().also { ms.paintEnd(nt.pan, nt.p, left, it) }
                        assertEquals(0, differModel(end, belief, nt.pan.areas(left)), "$what: px where belief's END is not the model's")
                        val boxes = cellBoxes(nt.pan.program, nt.p, left)
                        // the band's masks (T6, track index 5) are row nt.to's own band glyphs — the boxes the band's old
                        // list (T7) holds for that row; a wrong table index masks another row's letters and leaves the new bare
                        fun bandBoxes(p: IntArray, ti: Int) = cellBoxes(nt.pan.program, p, left).filter { it.third == ti }.map { it.first }
                            .sortedWith(compareBy({ it.x }, { it.y }, { it.w }, { it.h }))
                        assertEquals(bandBoxes(nt.p.copyOf().also { it[2] = nt.to }, 6), bandBoxes(nt.p, 5), "$what: the band's masks are row ${nt.to}'s band glyphs")
                        val one = Motion.stepOnModel(nt.pan.program, nt.p, cacheImg, base, base, left, tag = 7, k = 1)
                        val inBox = BooleanArray(wm.damage.core.geom.Geometry.PANEL_W * wm.damage.core.geom.Geometry.PANEL_H)
                        for ((r, _, _) in boxes) for (y in r.y until r.bottom) for (x in r.x until r.right) inBox[y * wm.damage.core.geom.Geometry.PANEL_W + x] = true
                        var outside = 0
                        for (r in nt.pan.areas(left)) for (y in r.y until r.bottom) for (x in r.x until r.right)
                            if (!inBox[y * wm.damage.core.geom.Geometry.PANEL_W + x] && nibble(one, x, y) != nibble(end, x, y)) outside++
                        assertEquals(0, outside, "$what: px outside every cell box where step 1 is not END (something moves)")
                        val mid = Motion.stepOnModel(nt.pan.program, nt.p, cacheImg, base, base, left, tag = 7, k = ms.t / 2)
                        val endG = rig.truth(nt.to, left)
                        for ((b, c, ti) in boxes) {
                            fun same(pic: ByteArray, want: Gray8) = (b.y until b.bottom).all { y -> (b.x until b.right).all { x -> nibble(pic, x, y) == wm.damage.core.gfx.Pack.level(want[x, y]) } }
                            if (c.old != 32) { oldCells++; if (same(one, start)) oldShown++ }
                            if (c.old != 32 && ti == 6) { bandOld++; if (same(one, start)) bandShown++ }
                            if (!same(mid, start) && !same(mid, endG)) noisy++
                        }
                    }
                    n++
                }
                assertTrue(oldCells > 0 && oldShown * 2 >= oldCells, "$what0: at step 1 the old letters stand in $oldShown of $oldCells old-role boxes")
                assertTrue(bandOld > 0 && bandShown * 2 >= bandOld, "$what0: at step 1 the band's old letters stand in $bandShown of its $bandOld old-role boxes")
                assertTrue(noisy > 0, "$what0: at step ${ms.t / 2} no box holds an alphabet glyph")
                if (cache == CfwModes.CACHE2_MAX_RIDERS) fit += "h$h: P ${ms.period} (${"%.1f".format(ms.period * 1000.0 / 1024)} ms) T ${ms.t}, " +
                    "${ms.pan(ListStaging.WHOLE, 1).areaPx} px a step, C ${"%,d".format(ms.pans.maxOf { p -> notches(ms).filter { it.pan === p }.maxOf { p.program.cost(it.p) } })} u at most; " +
                    "the records ${ms.blobs.last().bytes.size} B, a pan slot ${ms.progSlot} B, the free span ${ms.freeSpan} B; old letters at step 1 in $oldShown of $oldCells boxes"
            }
        }
        assertTrue(n > 0, "Scramble played somewhere")
        println("DM2b-scramble (BoxFace through its atlas, modeled): the model's END = Main's paint = belief at $n notches; the fit:\n" + fit.joinToString("\n"))
    }

    /**
     * C3 DM-2b, the records rule for Scramble (decisions DW 1, DW 2, EP 2; the DM-2 spec §8): Depth → Scramble → Depth on
     * Main at stage 5 — each change a full staging (NoRoom, its reason the records); Scramble's own Auto re-make at a T'
     * its lists' noise allows keeps its records; one at a T' with T' − N < 1 is NoRoom, said (a full staging is owed); a
     * staging whose records were given up (Depth, said) re-makes Depth's pans for Scramble's own re-makes and asks no
     * further staging; without Scramble's runs (cached text off) its records are not made — Depth, said.
     */
    @Test
    fun aChangeIntoOrOutOfScrambleIsAFullStagingAndItsReMakesKeepItsDecision_DM2b() {
        val fx = MainLocalCheck.effectsAt(true)
        val m = ListStaging.ScrollMotion.DEFAULT
        var played = 0
        for (h in heights) {
            val rig = cachedRig(h)
            for (cache in caches) {
                val what0 = "$h ${cache / 1024}"
                val depth = stage5(h, cache, ScrollEffect.DEPTH, rig)
                val sc = stage5(h, cache, ScrollEffect.SCRAMBLE, rig)
                if (sc.recordEffect == null) {
                    // its own re-make keeps the staging's decision: Depth's pans, programs only, no further staging
                    sc.periodInForce = sc.period + 8
                    val again = sc.remake(m.copy(effect = ScrollEffect.SCRAMBLE), fx)
                    assertEquals(ScrollEffect.DEPTH, (again as? ListStaging.Remake.Owed)?.programs?.plays?.effect, "$what0: given up, Scramble's re-make plays Depth's pans: ${(again as? ListStaging.Remake.NoRoom)?.why}")
                    continue
                }
                played++
                val into = depth.remake(m.copy(effect = ScrollEffect.SCRAMBLE), fx)
                assertTrue(into is ListStaging.Remake.NoRoom && "Scramble's records are staging records" in into.why, "$what0: into Scramble: ${(into as? ListStaging.Remake.NoRoom)?.why}")
                val out = sc.remake(m.copy(effect = ScrollEffect.DEPTH), fx)
                assertTrue(out is ListStaging.Remake.NoRoom && "Scramble's records are staging records" in out.why, "$what0: out of Scramble: ${(out as? ListStaging.Remake.NoRoom)?.why}")
                val noise = maxOf(1, sc.t / 3)
                // Auto at a period that keeps T' − N ≥ 1: its own pans, its records kept
                val okPeriod = (sc.period * sc.t) / (noise + 1)
                sc.periodInForce = okPeriod
                val tOk = ScrollEffects.stepsAt(m.copy(effect = ScrollEffect.SCRAMBLE), okPeriod, sc.t)
                if (tOk != sc.t && tOk - noise >= 1) {
                    val auto = assertNotNull((sc.remake(m.copy(effect = ScrollEffect.SCRAMBLE), fx) as? ListStaging.Remake.Owed)?.programs, "$what0: Scramble's Auto re-make to $tOk")
                    assertEquals(ScrollEffect.SCRAMBLE to true, auto.plays.effect to auto.plays.v2, "$what0: Scramble's own pans at the new T ${auto.t}")
                }
                // Auto at a period whose T' leaves no step past the noise: NoRoom, said. T' is at least MIN_STEPS (3), so
                // the branch is reached only by a staging whose N is 3 or more (T ≥ 9): at the default length where the
                // staging's T is that long, else Scramble staged at a length that gives it (his `Scroll length` row)
                val lm = if (sc.t >= 9) m.copy(effect = ScrollEffect.SCRAMBLE)
                    else m.copy(effect = ScrollEffect.SCRAMBLE, lengthMs = (10 * sc.period * 1000 + 1023) / 1024)
                val ls = if (sc.t >= 9) sc else stage5(h, cache, ScrollEffect.SCRAMBLE, rig, lm)
                assertTrue(ls.recordEffect == ScrollEffect.SCRAMBLE && ls.t >= 9, "$what0: precondition: Scramble staged at ${lm.lengthMs} ms with T ${ls.t} ≥ 9: ${ls.plays}")
                val longPeriod = ls.period * ls.t
                ls.periodInForce = longPeriod
                val tShort = ScrollEffects.stepsAt(lm, longPeriod, ls.t)
                assertTrue(tShort - maxOf(1, ls.t / 3) < 1, "$what0: precondition: T' $tShort − N ${maxOf(1, ls.t / 3)} < 1")
                val refused = ls.remake(lm, fx)
                assertTrue(refused is ListStaging.Remake.NoRoom && "Scramble's re-made pans were refused" in refused.why, "$what0: T' − N < 1 owes a full staging, said: ${(refused as? ListStaging.Remake.Owed)?.programs?.plays}")
                // a full staging that still refuses (the region one word short of the records) ends at Depth, said, and its
                // re-makes for Scramble keep that: Depth's pans, no further staging
                val region = CfwModes.mainRegion(cache, true)
                val short = ListStaging.build(rig.layout, rig.view, rig.kit, rig.listD, rig.bandD, cache, region.first, sc.end - 4, widths = listOf(ListStaging.WHOLE),
                    motion = m.copy(effect = ScrollEffect.SCRAMBLE), stage5 = true, fx = fx, text = rig.text, runs = rig.cachedText)
                assertTrue(short.recordEffect == null && short.plays.effect == ScrollEffect.DEPTH && short.plays.why!!.startsWith("does not fit"), "$what0: said: ${short.plays}")
                short.periodInForce = longPeriod
                val kept = short.remake(m.copy(effect = ScrollEffect.SCRAMBLE), fx)
                assertEquals(ScrollEffect.DEPTH, (kept as? ListStaging.Remake.Owed)?.programs?.plays?.effect, "$what0: the given-up staging's re-make: ${(kept as? ListStaging.Remake.NoRoom)?.why}")
                // without the runs (cached text off): Depth, said
                val plain = stage5(h, cache, ScrollEffect.SCRAMBLE, MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows()))
                assertTrue(plain.recordEffect == null && plain.plays.effect == ScrollEffect.DEPTH && "cached text is off" in plain.plays.why!!, "$what0: no runs: ${plain.plays}")
            }
        }
        assertTrue(played > 0, "Scramble's records fit somewhere")
    }

    // ------------------------------------------------------------------------------- C3 DM-1: Crush and Flip

    /** C3 X2DM1a: motions at whose T the step nearest half is not the first step past it, for Crush at 352 (225 ms Auto: 14
     *  steps; `Scroll frames` 4 and 8). */
    private val otherSplits = listOf(ListStaging.ScrollMotion(225, 0), ListStaging.ScrollMotion(300, 4), ListStaging.ScrollMotion(300, 8))

    private fun stage5(h: Int, cache: Int, e: ScrollEffect, rig: MainLocalCheck.MainRig,
                       m: ListStaging.ScrollMotion = ListStaging.ScrollMotion.DEFAULT.copy(effect = e)) =
        rig.staging(cache, CfwModes.mainRegion(cache, true), m, stage5 = true)

    /** The cache as the staging writes it — every record at its offset (the programs' own records are written by the
     *  model's renderer itself, `Motion.stepOnModel`). */
    private fun cacheOf(ms: ListStaging): ByteArray = ByteArray(ms.cacheSize).also { c -> for (b in ms.blobs) b.bytes.copyInto(c, b.off) }

    private fun panel(g: Gray8): ByteArray = wm.damage.core.gfx.Pack.rect(g, Rect(0, 0, wm.damage.core.geom.Geometry.PANEL_W, wm.damage.core.geom.Geometry.PANEL_H))

    private fun nibble(b: ByteArray, x: Int, y: Int): Int = (b[y * (wm.damage.core.geom.Geometry.PANEL_W / 2) + (x shr 1)].toInt() and 0xFF).let { if (x and 1 == 0) it shr 4 else it and 0x0F }

    /** Pixels of the model's panel [nib] inside [areas] whose level is not [want]'s. */
    private fun differModel(nib: ByteArray, want: Gray8, areas: List<Rect>): Int {
        var n = 0
        for (r in areas) for (y in r.y until r.bottom) for (x in r.x until r.right) if (nibble(nib, x, y) != wm.damage.core.gfx.Pack.level(want[x, y])) n++
        return n
    }

    /** One notch as the glasses fire it on [ms]'s bound list: [pan] from row [c] to row [to] with the parameters [p] (the
     *  list's prediction — at the virtual row across a ring list's wrap, `FIRMWARE.md` §7.8). */
    private class Notch(val pan: ListStaging.Pan, val c: Int, val to: Int, val p: IntArray)

    private fun notches(ms: ListStaging, activeOnly: Boolean = false): List<Notch> {
        val list = ms.boundList(1)
        val out = ArrayList<Notch>()
        for (pan in ms.pans) {
            if (activeOnly && pan.strip != 0) continue                 // the strip's pans (not the wrap record's)
            val le = list.entries.first { !it.isTap && it.step == pan.step }
            for (c in 0 until ms.rows) {
                val f = list.predict(c, le) as? wm.damage.core.local.BoundList.Fire.Drew ?: continue
                out += Notch(pan, c, f.cursor, f.p!!)
            }
        }
        return out
    }

    /**
     * C3 DM-1, the DM spec §5.1's "every effect's END" for the two new effects: at stage 5 Crush (version 2, pitch +rowH)
     * and Flip (version 2, the width turn) — at EVERY cursor a notch lands on, both directions, both strips at 256 KiB,
     * across the wrap on a ring list (288, 352), at the four heights on both caches — the model's END (`Motion.endOnModel`,
     * the renderers the conformance vectors check) is Main's own paint at the new cursor in the program's areas, and so is
     * the phone's belief (`ListStaging.paintEnd`); every PLAY passes the offline checks, the step budget among them.
     */
    @Test
    fun crushAndFlipEndAtMainsPaintOnTheModelAtEveryCursor_DM1() {
        var n = 0
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) for (e in listOf(ScrollEffect.CRUSH, ScrollEffect.FLIP)) {
                val ms = stage5(h, cache, e, rig)
                val what0 = "$h ${cache / 1024} KiB ${e.word}"
                assertEquals(ScrollEffects.Plays(e, true), ms.plays, "$what0: its own version-2 pans at stage 5")
                assertEquals(if (e == ScrollEffect.CRUSH) ms.layout.rowH else -ms.layout.rowH, ms.boundList(1).pitch, "$what0: the list's pitch (S6)")
                val cacheImg = cacheOf(ms)
                for (nt in notches(ms)) {
                    Motion.play(nt.pan.program, ListStaging.SLOT, ms.period, 1, 0, nt.p)
                    for (left in listOf(true, false)) {
                        val base = panel(rig.truth(nt.c, left))
                        val end = Motion.endOnModel(nt.pan.program, nt.p, cacheImg, base, base, left)
                        val what = "$what0 strip ${nt.pan.strip} step ${nt.pan.step} ${nt.c}→${nt.to} left=$left"
                        assertEquals(0, differModel(end, rig.truth(nt.to, left), nt.pan.areas(left)), "$what: px where the model's END is not Main's paint at ${nt.to}")
                        val belief = rig.truth(nt.c, left).copy().also { ms.paintEnd(nt.pan, nt.p, left, it) }
                        assertEquals(0, differModel(end, belief, nt.pan.areas(left)), "$what: px where belief's END is not the model's")
                    }
                    n++
                }
            }
        }
        println("DM1-end (BoxFace, modeled): the model's END = Main's paint = belief at $n notches of Crush and Flip at stage 5")
    }

    /**
     * C3 DM-1, the DM spec §5.1's "Crush's phases" and "Crush's covers", on the model at every step of every notch (the
     * active strip's pans at 256 KiB, the four heights, the wrap on a ring list): through phase 1 (steps 1..n1) the far
     * side's rows are Main at the old cursor; from phase 2's first step the entering side's rows are Main at the new one;
     * at step n1 the leaving band form is squashed to ONE pixel row against the band's far edge; and at every step of
     * phase 1 the entering form meets the squashed block (no row of the band between them), at every step of phase 2 the
     * growing row covers every row of row c that the far rows have brought into the far side (the two inequalities of
     * §1.2, read from the program's own segments at its own eases). C3 X1DM1: phase 1 is [ScrollEffects.halfStep]'s steps
     * (the split's own pin is [crushsTwoPhasesAreOneEaseOutSplitAtHalfItsTravel_X1DM1], which prints the step tables).
     * C3 X2DM1a: and at 352 at the motions whose T the step nearest half splits elsewhere than the first step past it
     * ([otherSplits]: 14, 4 and 8 steps), with the last step Main's paint at the new cursor in every track's area.
     */
    @Test
    fun crushRunsInTwoPhasesAndItsCoversLeaveNoGap_DM1() {
        var steps = 0
        for ((h, m) in heights.map { it to ListStaging.ScrollMotion.DEFAULT } + otherSplits.map { 352 to it }) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val cache = CfwModes.CACHE2_MAX_RIDERS
            val ms = stage5(h, cache, ScrollEffect.CRUSH, rig, m.copy(effect = ScrollEffect.CRUSH))
            val cacheImg = cacheOf(ms)
            val l = ms.layout
            val t = ms.t
            val n1 = ScrollEffects.halfStep(t)
            for (nt in notches(ms, activeOnly = true)) {
                val pr = nt.pan.program
                val segs = pr.resolve(nt.p, staticOnly = false)
                val down = nt.pan.step > 0
                val what0 = "$h ${m.copy(effect = ScrollEffect.CRUSH).words()} T $t step ${nt.pan.step} ${nt.c}→${nt.to}"
                // the phases on the model
                for (left in listOf(true, false)) {
                    val base = panel(rig.truth(nt.c, left))
                    val far = pr.tracks[1].area(left); val enter = pr.tracks[0].area(left)
                    for (k in 1..t) {
                        val pic = Motion.stepOnModel(pr, nt.p, cacheImg, base, base, left, k = k)
                        if (k <= n1) assertEquals(0, differModel(pic, rig.truth(nt.c, left), listOf(far)), "$what0 left=$left step $k: the far side is not Main at ${nt.c} in phase 1")
                        else assertEquals(0, differModel(pic, rig.truth(nt.to, left), listOf(enter)), "$what0 left=$left step $k: the entering side is not at rest in phase 2")
                        if (k == t) assertEquals(0, differModel(pic, rig.truth(nt.to, left), pr.tracks.map { it.area(left) }), "$what0 left=$left: the last step is not Main's paint at ${nt.to}")
                        steps++
                    }
                }
                // the squash: T3's height at n1 is one row, anchored at the far edge (top for a notch down)
                val s3 = segs[2][0]
                val e3 = Motion.easeE(s3.ease, s3.steps)
                assertEquals(n1, s3.steps, "$what0: T3 squashes over phase 1")
                assertEquals(1, Motion.eased(s3.h0, s3.h1 - s3.h0, e3[n1]), "$what0: one pixel row at step n1")
                assertEquals(if (down) 0 else 2, s3.anchor and 3, "$what0: squashed against the far edge")
                // the covers, at every step of each phase
                val s4 = segs[3][0]
                val e4 = Motion.easeE(s4.ease, s4.steps)
                for (j in 0..n1) {
                    val h3 = Motion.eased(s3.h0, s3.h1 - s3.h0, e3[j])
                    val block = Motion.anchoredY(s3.y, l.lens.h, h3, s3.anchor and 3).let { it until it + h3 }
                    val y4 = Motion.eased(s4.y, s4.dy, e4[j])
                    assertTrue(if (down) y4 <= block.last + 1 else y4 + l.lens.h >= block.first, "$what0 phase 1 step $j: the entering form ($y4) meets the squashed block $block")
                }
                val s2 = segs[1].last { it.verb == Motion.DRAW }; val s5 = segs[4].last { it.verb == Motion.DRAW }
                val e2 = Motion.easeE(s2.ease, s2.steps); val e5 = Motion.easeE(s5.ease, s5.steps)
                val f = pr.tracks[1].areaL
                for (j in 0..t - n1) {
                    val top = s2.y + (s5.aux0 - s2.aux0) + Motion.eased(0, s2.dy, e2[j])          // row c's top as T2 draws it
                    val inF = maxOf(top, f.y) until minOf(top + l.rowH, f.bottom)
                    val h5 = Motion.eased(s5.h0, s5.h1 - s5.h0, e5[j])
                    val grown = Motion.anchoredY(s5.y, l.rowH, h5, s5.anchor and 3).let { it until it + h5 }
                    assertTrue(inF.isEmpty() || (inF.first >= grown.first && inF.last <= grown.last), "$what0 phase 2 step $j: row c's rows in the far side $inF outside the growing row $grown")
                }
            }
        }
        println("DM1-phases: $steps step pictures checked")
    }

    /**
     * C3 X1DM1 (decisions BP (1)): Crush's two phases are the two parts of ONE ease-out over T — the wheel's swap form. On
     * every pan at stage 5 (the four heights, his 256 KiB, both strips, both directions), read from the program's own
     * segments: phase 2 starts at the step whose ease-out over T is nearest half its travel (§5.4: |E − 2048| least; C3
     * X2DM1a, decisions CA (3) — at T 19 and 17 the first step past half); the
     * phase-1 movers (T1, T3, T4) share one table over those steps and the phase-2 movers (T2 after its WAIT, T5's growth)
     * another over the rest; a mover that goes the first part of a travel on the first and the rest on the second is
     * within a pixel of one ease-out over T at every step, for every travel up to twice the band's height; and the rows'
     * pixels a step across both phases are within a pixel of what one ease-out over T of the two pitches moves at that
     * step (the near-stop between the phases is gone). Printed for the report: the step counts and the pixels a step.
     */
    @Test
    fun crushsTwoPhasesAreOneEaseOutSplitAtHalfItsTravel_X1DM1() {
        val shapes = ArrayList<String>()
        fun px(d: Int, e: IntArray) = (1 until e.size).map { j -> Motion.eased(0, d, e[j]) - Motion.eased(0, d, e[j - 1]) }
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val ms = stage5(h, CfwModes.CACHE2_MAX_RIDERS, ScrollEffect.CRUSH, rig)
            val t = ms.t; val l = ms.layout
            val eo = Motion.easeE(Motion.Ease.easeOut, t)
            val half = (1 until t).minBy { kotlin.math.abs(eo[it] - 2048) }
            var e1 = IntArray(0); var e2 = IntArray(0)
            for (pan in ms.pans) {
                val tr = pan.program.tracks
                val what = "$h strip ${pan.strip} step ${pan.step} T $t"
                fun draws(i: Int) = tr[i].segments.filter { it.verb == Motion.DRAW }
                val p1 = draws(0).single(); val p2 = draws(1).single()
                assertEquals(half, p1.steps, "$what: phase 2 starts at the step whose ease-out over T is nearest half its travel (${half + 1}), not at ${p1.steps + 1}")
                assertEquals(listOf(Motion.WAIT to half, Motion.DRAW to t - half), tr[1].segments.map { it.verb to it.steps }, "$what: T2 waits phase 1, then moves over the rest")
                assertEquals(half, tr[4].segments.first().steps, "$what: T5 parked over phase 1")
                e1 = Motion.easeE(p1.ease, p1.steps); e2 = Motion.easeE(p2.ease, p2.steps)
                for (i in listOf(2, 3)) assertEquals(e1.toList(), draws(i).single().let { Motion.easeE(it.ease, it.steps).toList() }, "$what: T${i + 1} on phase 1's ease")
                assertEquals(e2.toList(), draws(4).last().let { Motion.easeE(it.ease, it.steps).toList() }, "$what: T5's growth on phase 2's ease")
                for (d in 1..2 * l.lens.h) {
                    val d1 = Motion.eased(0, d, eo[half])
                    for (j in 0..t) {
                        val two = if (j <= half) Motion.eased(0, d1, e1[j]) else d1 + Motion.eased(0, d - d1, e2[j - half])
                        val one = Motion.eased(0, d, eo[j])
                        assertTrue(kotlin.math.abs(two - one) <= 1, "$what: a mover of $d px on the two tables is at $two px after step $j, one ease-out over T at $one")
                    }
                }
                val rows = px(l.rowH, e1) + px(l.rowH, e2); val ref = px(2 * l.rowH, eo)
                for (j in 0 until t) assertTrue(kotlin.math.abs(rows[j] - ref[j]) <= 1, "$what: the rows move ${rows[j]} px at step ${j + 1}, one ease-out over T of two pitches ${ref[j]}")
            }
            val stepMs = ms.period * 1000.0 / 1024
            val rows = px(l.rowH, e1) + px(l.rowH, e2); val ref = px(2 * l.rowH, eo)
            shapes += "h$h T $t P ${ms.period}: phase 1 $half steps (${"%.0f".format(half * stepMs)} ms), phase 2 ${t - half} (${"%.0f".format((t - half) * stepMs)} ms); " +
                "phase 1 rows px a step ${px(l.rowH, e1)}, band forms ${px(l.lens.h, e1)}; phase 2 rows ${px(l.rowH, e2)}, growing row ${px(l.rowH - 1, e2)}; " +
                "one ease-out over T of two pitches ${ref}; at the handoff the rows ${rows[half - 1]} -> ${rows[half]} px (the one ease-out ${ref[half - 1]} -> ${ref[half]}); " +
                "E1 ${e1.drop(1)}, E2 ${e2.drop(1)}"
        }
        println("X1DM1-split (modeled; BoxFace)\n" + shapes.joinToString("\n"))
    }

    /**
     * C3 DM-1, the DM spec §5.1's "Flip": at the middle of the turn (step n1) the band shows only one column of the band
     * form at the band's centre — the rest of the band its backdrop — at stage 5 (the width turn, version 2); and the same
     * on the height below stage 5 (its fallback: one row at the band's middle, version 1). Rows and band start and end
     * together (T steps each). C3 X1DM1: n1 is the rows' half-travel step at stage 5 ([ScrollEffects.halfStep]), ⌈T/2⌉
     * below it ([ScrollEffects.phase1]). C3 X2DM1a: and at 352, stage 5, at `Scroll frames` 4 and 8 (the step nearest half
     * is not the first past it there).
     */
    @Test
    fun flipTurnsTheBandToOneColumnAtTheMiddleStep_DM1() {
        for ((h, m) in heights.map { it to ListStaging.ScrollMotion.DEFAULT } + otherSplits.drop(1).map { 352 to it }) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val cache = CfwModes.CACHE2_MAX_RIDERS
            for (st5 in if (m == ListStaging.ScrollMotion.DEFAULT) listOf(true, false) else listOf(true)) {
                val ms = if (st5) stage5(h, cache, ScrollEffect.FLIP, rig, m.copy(effect = ScrollEffect.FLIP)) else staging(h, cache, ScrollEffect.FLIP, rig)
                assertEquals(ScrollEffects.Plays(ScrollEffect.FLIP, st5, if (st5) null else ScrollEffects.NOT_STAGE5), ms.plays, "$h stage ${if (st5) 5 else 4}: Flip's form")
                val cacheImg = cacheOf(ms)
                val l = ms.layout
                val n1 = if (st5) ScrollEffects.halfStep(ms.t) else ScrollEffects.phase1(ms.t)
                for (nt in notches(ms, activeOnly = true)) {
                    val pr = nt.pan.program
                    assertEquals(if (st5) 2 else 1, pr.version, "the version")
                    assertTrue(pr.tracks.all { tr -> tr.segments.sumOf { it.steps } == ms.t }, "rows and band start and end together")
                    for (left in listOf(true, false)) {
                        val base = panel(rig.truth(nt.c, left))
                        val pic = Motion.stepOnModel(pr, nt.p, cacheImg, base, base, left, k = n1)
                        val band = pr.tracks[2].area(left)
                        val x = (if (left) l.content.x - ms.bandD else l.content.x + ms.bandD) + ((l.content.w - wm.damage.core.geom.Layout.RAIL_W - 1) shr 1)
                        val y = l.lens.y + ((l.lens.h - 1) shr 1)
                        var lit = 0; var off = 0
                        for (yy in band.y until band.bottom) for (xx in band.x until band.right) if (nibble(pic, xx, yy) != 0) {
                            lit++
                            if (if (st5) xx != x else yy != y) off++
                        }
                        assertTrue(lit > 0, "$h stage ${if (st5) 5 else 4} ${nt.c}→${nt.to} left=$left: nothing of the band form drawn at step $n1")
                        assertEquals(0, off, "$h stage ${if (st5) 5 else 4} ${nt.c}→${nt.to} left=$left: px of the band drawn off the centre ${if (st5) "column $x" else "row $y"} at step $n1")
                    }
                }
            }
        }
    }

    /**
     * C3 X1DM1 (decisions BP (2), his "what moves, moves together"): Flip's width turn follows the rows. On every pan at
     * stage 5 (the four heights, his 256 KiB, both strips, both directions), from the program's own segments: the band is
     * edge-on (one column) at the step where the rows' ease-out over T is nearest half their travel (C3 X2DM1a: at 416,
     * T 15, step 4 — the first step past half was 5), and at every step its
     * drawn width is within a pixel of 1 + (wF − 1) |cos θ|, θ the rows' eased progress (0 at the start, π/2 at that step,
     * π at the end). Below stage 5 the height turn keeps DM-1's tables (⌈T/2⌉, [ScrollEffects.flipOut] and
     * [ScrollEffects.flipIn]). Printed for the report: the band's width a step beside the rows' pixels a step.
     */
    @Test
    fun flipsTurnFollowsTheRows_X1DM1() {
        val shapes = ArrayList<String>()
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val ms = stage5(h, CfwModes.CACHE2_MAX_RIDERS, ScrollEffect.FLIP, rig)
            val t = ms.t
            var widths = emptyList<Int>(); var rowsPx = emptyList<Int>(); var half = 0
            for (pan in ms.pans) {
                val tr = pan.program.tracks
                val what = "$h strip ${pan.strip} step ${pan.step} T $t"
                val rows = tr[0].segments.single()
                val er = Motion.easeE(rows.ease, rows.steps)
                assertEquals(t, rows.steps, "$what: the rows over T")
                half = (1 until t).minBy { kotlin.math.abs(er[it] - 2048) }
                val (out, inn) = tr[2].segments.also { assertEquals(2, it.size, "$what: the band's two segments") }.let { it[0] to it[1] }
                assertEquals(half to t - half, out.steps to inn.steps, "$what: the band edge-on where the rows reach half their travel (step $half)")
                val wF = out.w0
                val eOut = Motion.easeE(out.ease, out.steps); val eIn = Motion.easeE(inn.ease, inn.steps)
                widths = (1..t).map { j -> if (j <= half) Motion.eased(out.w0, out.w1 - out.w0, eOut[j]) else Motion.eased(inn.w0, inn.w1 - inn.w0, eIn[j - half]) }
                rowsPx = (1..t).map { j -> Motion.eased(0, ms.layout.rowH, er[j]) - Motion.eased(0, ms.layout.rowH, er[j - 1]) }
                assertEquals(1, widths[half - 1], "$what: one column at step $half")
                for (j in 1..t) {
                    val theta = if (j <= half) Math.PI / 2 * er[j] / er[half] else Math.PI / 2 * (1.0 + (er[j] - er[half]).toDouble() / (4096 - er[half]))
                    val want = 1 + (wF - 1) * kotlin.math.abs(kotlin.math.cos(theta))
                    assertTrue(kotlin.math.abs(widths[j - 1] - want) <= 1.0, "$what step $j: the band ${widths[j - 1]} px wide where the rows' progress puts the turn at ${"%.1f".format(want)}")
                }
            }
            val ms4 = staging(h, CfwModes.CACHE2_MAX_RIDERS, ScrollEffect.FLIP, rig)
            val n4 = ScrollEffects.phase1(ms4.t)
            for (pan in ms4.pans) {
                val band = pan.program.tracks[2].segments
                assertEquals(listOf(ScrollEffects.flipOut(n4).toList(), ScrollEffects.flipIn(ms4.t - n4).toList()), band.map { (it.ease as Motion.Ease.Table).e.toList() },
                    "$h stage 4: the height turn keeps DM-1's tables")
            }
            shapes += "h$h T $t P ${ms.period}: edge-on at step $half; band width a step $widths; rows px a step $rowsPx"
        }
        println("X1DM1-flip (modeled; BoxFace)\n" + shapes.joinToString("\n"))
    }

    /**
     * C3 X2DM1a (decisions CA (3); the second review's finding 3): Crush's phases and Flip's width turn split at the step
     * whose ease-out over T is NEAREST half its travel (computed here: |E[j] − 2048| least, the earlier of two equal), at
     * every height, at 300 ms Auto (Flip at 416: 15 steps, where the first step past half is not the nearest) and at the
     * motions where the two rules part for Crush ([otherSplits]: 14 steps at 288–416, Frames 4 and 8). From each pan's
     * own segments at stage 5 (256 KiB): Crush's phase 2 starts at that step and its rows (one pitch on phase 1's table,
     * one on phase 2's) never move more than a pixel faster in a step than in the step before — the speed carries across
     * the hand-off, where the first step past half made them 2–11 px a step faster at 4, 8 and 14 steps; Flip's band is
     * edge-on at that step. C3 X2DM1a (decisions CA (4)): Crush's two tables are exactly the two parts of one ease-out over
     * T at that step (the arithmetic computed here) — phase 2's is that ease-out's tail, kept: the built-in ease-out over its
     * steps equals it within 2/4096 but draws a row a pixel apart at 10, 12, 34 and 45 steps. Printed for the report: the
     * rows' px a step at each motion.
     */
    @Test
    fun theSplitIsAtTheStepNearestHalfAtEveryStepCount_X2DM1a() {
        val shapes = ArrayList<String>()
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (m0 in listOf(ListStaging.ScrollMotion.DEFAULT) + otherSplits) for (e in listOf(ScrollEffect.CRUSH, ScrollEffect.FLIP)) {
                val m = m0.copy(effect = e)
                val ms = stage5(h, CfwModes.CACHE2_MAX_RIDERS, e, rig, m)
                val t = ms.t
                val eo = Motion.easeE(Motion.Ease.easeOut, t)
                val half = (1 until t).minBy { kotlin.math.abs(eo[it] - 2048) }
                val what0 = "$h ${m.words()} T $t"
                assertEquals(ScrollEffects.Plays(e, true), ms.plays, "$what0: plays itself in version 2")
                var rows = emptyList<Int>()
                for (pan in ms.pans) {
                    val tr = pan.program.tracks
                    val what = "$what0 strip ${pan.strip} step ${pan.step}"
                    if (e == ScrollEffect.FLIP) {
                        assertEquals(half to t - half, tr[2].segments.let { it[0].steps to it[1].steps }, "$what: the band edge-on at the step nearest half the rows' travel ($half)")
                        continue
                    }
                    val p1 = tr[0].segments.single { it.verb == Motion.DRAW }; val p2 = tr[1].segments.single { it.verb == Motion.DRAW }
                    assertEquals(half, p1.steps, "$what: phase 2 starts at the step whose ease-out over T is nearest half its travel (${half + 1}), not at ${p1.steps + 1}")
                    val e1 = Motion.easeE(p1.ease, p1.steps); val e2 = Motion.easeE(p2.ease, p2.steps)
                    val w1 = IntArray(half) { i -> Math.round(4096.0 * eo[i + 1] / eo[half]).toInt() }
                    val w2 = IntArray(t - half) { i -> Math.round(4096.0 * (eo[half + i + 1] - eo[half]) / (4096 - eo[half])).toInt() }
                    assertEquals(w1.toList() to w2.toList(), e1.drop(1) to e2.drop(1), "$what: the two tables are the two parts of one ease-out over T " +
                        "at step $half (phase 2's that ease-out's tail, not the built-in ease-out over its ${t - half} steps)")
                    fun px(e: IntArray) = (1 until e.size).map { j -> Motion.eased(0, ms.layout.rowH, e[j]) - Motion.eased(0, ms.layout.rowH, e[j - 1]) }
                    rows = px(e1) + px(e2)
                    for (j in 1 until t) assertTrue(rows[j] <= rows[j - 1] + 1, "$what: the rows move ${rows[j - 1]} px at step $j, then ${rows[j]} at step ${j + 1}" +
                        (if (j == half) " (the hand-off)" else ""))
                }
                shapes += if (e == ScrollEffect.FLIP) "h$h ${m.words()}: P ${ms.period} T $t, the band edge-on at step $half"
                    else "h$h ${m.words()}: P ${ms.period} T $t, phase 1 $half steps; the rows' px a step ${rows.take(half)} | ${rows.drop(half)}"
            }
        }
        println("X2DM1a-split (modeled; BoxFace)\n" + shapes.joinToString("\n"))
    }

    /**
     * C3 DM-1 (D3 as ruled; the DM spec §5.1's "the stage-5 form"): a stored Crush below stage 5 plays Slide's bytes
     * exactly — the same pans byte for byte, the same period and T, at the four heights on both caches — and the row says
     * so ("plays Slide"); a stored Flip plays its height turn (version 1, C2's vertical scale). At stage 5 both play
     * themselves in version 2, Crush at pitch +rowH.
     */
    @Test
    fun aStoredCrushAtStage4PlaysSlidesBytesExactly_DM1() {
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) {
                val what = "$h ${cache / 1024} KiB"
                val crush = staging(h, cache, ScrollEffect.CRUSH, rig)
                val slide = staging(h, cache, ScrollEffect.SLIDE, rig)
                assertEquals(digest(slide.pans), digest(crush.pans), "$what: Crush plays Slide's pans byte for byte")
                assertEquals(slide.blobs.map { it.off to it.bytes.toList() }, crush.blobs.map { it.off to it.bytes.toList() }, "$what: the whole staging's bytes are Slide's")
                assertEquals(slide.period to slide.t, crush.period to crush.t, "$what: Slide's period and T")
                assertEquals(ScrollEffect.SLIDE, crush.plays.effect, what)
                assertEquals("plays Slide", crush.plays.words(ScrollEffect.CRUSH), "$what: the row's words")
                assertEquals(slide.boundList(1).sets().map { it.toList() }, crush.boundList(1).sets().map { it.toList() }, "$what: the SETs are Slide's")
                val flip = staging(h, cache, ScrollEffect.FLIP, rig)
                assertTrue(!flip.plays.v2 && flip.pans.all { it.program.version == 1 } && flip.pans.all { pn -> pn.program.tracks[2].segments.all { it.h0 != 0 && it.h1 != 0 } },
                    "$what: Flip plays its height turn in version 1")
                assertEquals("plays its height turn", flip.plays.words(ScrollEffect.FLIP), "$what: the row's words")
                val c5 = stage5(h, cache, ScrollEffect.CRUSH, rig)
                assertTrue(c5.plays.v2 && c5.pans.all { it.program.version == 2 && it.pitch == c5.layout.rowH }, "$what: Crush in version 2 at stage 5, pitch +rowH")
                assertEquals("", c5.plays.words(ScrollEffect.CRUSH), "$what: nothing said at stage 5")
            }
        }
    }

    /**
     * C3 DM-1, the brief's item 6 (the DM spec §1.6, §1.7; decisions BE): at stage 5 Crush and Flip take the fast-path fit
     * — Crush P 16 T 19 at 288/352/416, P 18 T 17 at 480; Flip P 16 T 19, P 18 T 17, P 20 T 15, P 22 T 13 (the spec's
     * §1.6 exactly, modeled) — and every program at every cursor a notch lands on (the wrap included on a ring list), both
     * directions, both strips, four heights, both caches stays inside 80 % of the step budget's cap (the DSL's calculator,
     * §7.12, the record's L counted whole: an upper bound); the programs record and the two pan slots lie inside Main's
     * region. The table is printed for the report.
     */
    @Test
    fun crushAndFlipFitTheSpecsPeriodsAndStayInsideTheBudget_DM1() {
        val want = mapOf(ScrollEffect.CRUSH to listOf(16 to 19, 16 to 19, 16 to 19, 18 to 17), ScrollEffect.FLIP to listOf(16 to 19, 18 to 17, 20 to 15, 22 to 13))
        val table = ArrayList<String>()
        for ((hi, h) in heights.withIndex()) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            for (cache in caches) for (e in listOf(ScrollEffect.CRUSH, ScrollEffect.FLIP)) {
                val ms = stage5(h, cache, e, rig)
                val what = "$h ${cache / 1024} KiB ${e.word}"
                assertEquals(want.getValue(e)[hi], ms.period to ms.t, "$what: the fast-path fit (the DM spec §1.6)")
                var worst = 0L; var at = ""
                for (nt in notches(ms)) {
                    val c = nt.pan.program.cost(nt.p)
                    if (c > worst) { worst = c; at = "step ${nt.pan.step} ${nt.c}→${nt.to}" }
                    assertTrue(c * 100 <= Motion.BUDGET_CAP * ListStaging.BUDGET_PCT, "$what ${nt.c}→${nt.to}: C $c past 80 % of the cap")
                }
                assertTrue(ms.end <= ms.regionEnd && ms.start >= ms.regionStart, "$what: inside Main's region")
                if (cache == CfwModes.CACHE2_MAX_RIDERS) assertNotNull(ms.progSlots, "$what: the spare pan slot at 256 KiB")
                val ms4 = staging(h, cache, e, rig)
                val step = ListStaging.fastStepMs(ScrollEffects.areaPx(e, ms.layout, ms.listD, ms.bandD), ScrollEffects.walkBytes(e, ms.layout), ScrollEffects.pixelPx(ms.plays, ms.layout, ms.listD, ms.bandD))
                table += "h$h ${cache / 1024} KiB ${e.word}: step ${"%.1f".format(step)} ms, P ${ms.period} T ${ms.t} (n1 ${ScrollEffects.halfStep(ms.t)}); " +
                    "C worst ${"%,d".format(worst)} u (${"%.1f".format(worst * 100.0 / Motion.BUDGET_CAP)} %) at $at; one pan ${ms.pan(ListStaging.WHOLE, 1).program.bytes.size} B, " +
                    "programs record ${ms.blobs.last().bytes.size} B, pan slot ${ms.progSlot} B ${if (ms.progSlots != null) "×2" else "(no spare slot)"}, staging ${ms.end - ms.start} B of the region's " +
                    "${ms.regionEnd - ms.regionStart}; stage 4: ${ms4.plays.effect.word}${if (ms4.plays.v2) "" else " v1"} P ${ms4.period} T ${ms4.t}, pan slot ${ms4.progSlot} B"
            }
        }
        println("DM1-fit-budget-bytes (modeled; BoxFace)\n" + table.joinToString("\n"))
    }

    /**
     * C3 DM-1 (D3: "when the DSL refuses a program offline"): a version-2 form the offline check refuses at some cursor —
     * its step budget past 80 % of the cap (a strip record far larger than Main stages), or a source window that leaves
     * the strip (a strip with no margins) — gives way to its fallback, the reason said; with Main's own strip it plays
     * itself.
     */
    @Test
    fun aVersion2FormTheOfflineCheckRefusesPlaysItsFallback_DM1() {
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        val l = rig.layout
        val fx = MainLocalCheck.effectsAt(true)!!
        val rowW = l.content.w - wm.damage.core.geom.Layout.RAIL_W
        val band = Motion.Source.Record(4000, rowW, l.lens.h, 3000)
        val n = 8
        val items = IntArray(n) { band.off4 }
        fun lay(e: ScrollEffect, strip: Motion.Source.Record, margin: Int) = ListStaging.lay(
            ScrollEffects.Staged(l, rig.listD, rig.bandD, band, listOf(strip, band)), ScrollEffects.plays(e, fx), fx, 30000,
            listOf(1, -1).map { ListStaging.PanSpec(ListStaging.WHOLE, l.rowsAbove, l.rowsBelow, it, 0) }, margin, n, false, items, { strip }) { 16 to 19 }
        val margin = l.rowsAbove
        val good = Motion.Source.Record(1000, rowW, (n + 2 * margin) * l.rowH, 20_000)
        assertEquals(ScrollEffects.Plays(ScrollEffect.CRUSH, true), lay(ScrollEffect.CRUSH, good, margin).plays, "Main's strip: Crush plays itself")
        // a strip whose Crush pans cost between 80 % and the whole cap (the record's L counted whole): refused offline
        val big = Motion.Source.Record(1000, rowW, (n + 2 * margin) * l.rowH, 76_000)
        val staged = ScrollEffects.Staged(l, rig.listD, rig.bandD, band, listOf(big, band))
        val unchecked = ListStaging.layPans(staged, ScrollEffects.Plays(ScrollEffect.CRUSH, true), 19, 30000,
            listOf(1, -1).map { ListStaging.PanSpec(ListStaging.WHOLE, l.rowsAbove, l.rowsBelow, it, 0) }, margin, fx) { big }.first
        val c = unchecked.maxOf { pn -> pn.program.cost(intArrayOf(0, band.off4, band.off4, (margin - pn.step) * l.rowH)) }
        assertTrue(c * 100 > Motion.BUDGET_CAP * 80 && c <= Motion.BUDGET_CAP, "the dear strip's Crush costs between 80 % and the cap: $c")
        val dear = lay(ScrollEffect.CRUSH, big, margin)
        assertEquals(ScrollEffect.SLIDE, dear.plays.effect, "a budget past 80 % (${"%.1f".format(c * 100.0 / Motion.BUDGET_CAP)} %): Slide plays")
        assertTrue(dear.plays.why!!.contains("80 %"), "the reason said: ${dear.plays.why}")
        val huge = Motion.Source.Record(1000, rowW, (n + 2 * margin) * l.rowH, 400_000)
        val bare = Motion.Source.Record(1000, rowW, n * l.rowH, 20_000)
        val out = lay(ScrollEffect.CRUSH, bare, margin)
        assertEquals(ScrollEffect.SLIDE, out.plays.effect, "a window past the strip: Slide plays")
        assertTrue(out.plays.why!!.contains("source window"), "the reason said: ${out.plays.why}")
        val flip = lay(ScrollEffect.FLIP, huge, margin)
        assertTrue(flip.plays.effect == ScrollEffect.FLIP && !flip.plays.v2 && flip.pans.all { it.program.version == 1 }, "Flip refused: its height turn")
    }

    // ------------------------------------------------------------- K1-2 stage 1 (O12): Main's band slots sized per row

    /**
     * K1-2 stage 1 (decisions BQ (5), BC O12): at the four heights on both caches, stage 4 and 5, each row's band form lies
     * in a slot of its own size with a quarter's slack ([ListStaging.slack], the wheel's cells' pattern), the spare in the
     * largest; a row whose band form changed in the last staging (`bandRoom`) gets the largest; the records lie back to
     * back inside `[start, end)` with none over another, and the region's free span is the region less that extent; a
     * short region gives the band room up with the strip's (never a refusal for its sake).
     */
    @Test
    fun bandSlotsAreEachRowsOwnSizeAndTheFreeSpanIsTheRegionLessTheStaging_O12() {
        for (h in heights) for (cache in caches) for (s5 in listOf(false, true)) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val region = CfwModes.mainRegion(cache, true)
            val what = "$h ${cache / 1024} KiB stage ${if (s5) 5 else 4}"
            val ms = rig.staging(cache, region, stage5 = s5)
            val big = ListStaging.slack(ms.bands.maxOf { it.encoded.size })
            for ((i, b) in ms.bands.withIndex()) assertEquals(ListStaging.slack(b.encoded.size), b.slot, "$what: row $i's band slot is its own size")
            assertEquals(listOf(big, big), ms.free.values.toList(), "$what: two spares, the largest")
            assertTrue(ms.bands.any { it.slot < big }, "$what: the shape guard — some row's slot is smaller than the largest")
            val spans = (ms.blobs.map { it.off until it.off + it.bytes.size } + ms.bands.map { it.off until it.off + it.slot } +
                ms.free.map { (o, n) -> o until o + n } + listOfNotNull(ms.wrap?.let { it.off until it.off + it.slot }, ms.strip.let { it.off until it.off + it.slot }) +
                (ms.progSlots?.map { it until it + ms.progSlot } ?: emptyList())).filter { !it.isEmpty() }
            for (r in spans) assertTrue(r.first >= ms.start && r.last < ms.end, "$what: ${r.first}..${r.last} inside the staging ${ms.start}..${ms.end}")
            assertEquals(ms.end, spans.maxOf { it.last + 1 }, "$what: the staging ends at its last slot")
            assertEquals(region.last + 1 - ms.end, ms.freeSpan, "$what: the free span is the region less the staging")
            assertEquals(region.first, ms.start, "$what: from the region's first byte")
            // a row whose band form changed in the last staging: the largest slot (its own last largest counted in it)
            val paint = ListStaging.Paint(rig.layout, rig.view, rig.kit).also { p -> while (!p.done) p.step { _, bb -> bb() } }
            val room = IntArray(ms.rows).also { it[3] = big + 400 }
            val roomy = ListStaging.assemble(paint, rig.listD, rig.bandD, cache, region.first, region.last + 1, listOf(ListStaging.WHOLE),
                ListStaging.ScrollMotion.DEFAULT, stage5 = s5, fx = if (s5) MainLocalCheck.effectsAt(true) else null, bandRoom = room)
            val big2 = ListStaging.slack(maxOf(ms.bands.maxOf { it.encoded.size }, big + 400))
            assertEquals(big2, roomy.bands[3].slot, "$what: the changing row's slot is the largest, its own last one counted")
            assertEquals(listOf(big2, big2), roomy.free.values.toList(), "$what: the spares as large")
            for (i in roomy.bands.indices) if (i != 3) assertEquals(ListStaging.slack(roomy.bands[i].encoded.size), roomy.bands[i].slot, "$what: row $i its own size")
        }
        // a region that holds the staging only without the band room: the room given up, never a refusal
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        val region = CfwModes.mainRegion(CfwModes.CACHE2_MAX, true)
        val paint = ListStaging.Paint(rig.layout, rig.view, rig.kit).also { p -> while (!p.done) p.step { _, bb -> bb() } }
        val plain = ListStaging.assemble(paint, rig.listD, rig.bandD, CfwModes.CACHE2_MAX, region.first, region.last + 1, listOf(ListStaging.WHOLE), ListStaging.ScrollMotion.DEFAULT)
        val short = ListStaging.assemble(paint, rig.listD, rig.bandD, CfwModes.CACHE2_MAX, region.first, plain.end, listOf(ListStaging.WHOLE), ListStaging.ScrollMotion.DEFAULT,
            bandRoom = IntArray(plain.rows) { 6_000 })
        assertEquals(plain.bands.map { it.slot }, short.bands.map { it.slot }, "the band room given up where the region cannot hold it")
        // and it goes with the strip's room, before the wrap's record (B082's order): the wrap kept as it was
        assertEquals(plain.wrap?.slot, short.wrap?.slot, "the band room goes first: the wrap's record kept as it was")
    }

    /**
     * K1-2 stage 1 (O12) on the rig, his height 352 in Main's own region at both stages. (1) A row's title that grows by
     * one glyph, by a word, and a row that gains a badge (▸ and the progress bar) — each changed twice: each change moves
     * the band form into the smallest free slot that holds it (two are free between moves: the spares, or one and the
     * slot the move before freed) — the first into a spare, the second into the row's own old slot where it fits, else
     * the other spare. (2) Two rows whose band forms grow past their own slots, in turn, then the first again: the only
     * free slots are then the two rows' own old ones, so it is `Outgrown` (a full staging, a new mark), and in the next
     * staging — by the room carried by window ([wm.damage.core.shell.ListBinding.bandRoomFor]) — both rows have the
     * largest slot and move as before. After every move: every write inside `[start, end)` (the free span untouched),
     * the staging's extent kept, and the END of every pan at every cursor both ways on the model — drawn from the cache
     * as written — Main's own paint at the new cursor, and belief's.
     */
    @Test
    fun aBandFormThatOutgrowsItsSlotIsRePlacedAndEveryEndStaysMainsPaint_O12() {
        val cache = CfwModes.CACHE2_MAX_RIDERS
        val region = CfwModes.mainRegion(cache, true)
        class Run(val s5: Boolean, prep: (List<MainLocalCheck.SampleRow>) -> Unit = {}) {
            val rows = MainLocalCheck.sampleRows().also(prep)
            val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
            val b = wm.damage.core.shell.ListBinding()
            val ids = rows.map { it.id }
            lateinit var ms: ListStaging
            lateinit var img: ByteArray
            fun stage() {
                val paint = ListStaging.Paint(rig.layout, rig.view, rig.kit).also { p -> while (!p.done) p.step { _, bb -> bb() } }
                ms = ListStaging.assemble(paint, rig.listD, rig.bandD, cache, region.first, region.last + 1, listOf(ListStaging.WHOLE),
                    ListStaging.ScrollMotion.DEFAULT, room = b.roomFor(ids), stage5 = s5, fx = if (s5) MainLocalCheck.effectsAt(true) else null,
                    bandRoom = b.bandRoomFor(ids))
                b.begin(ms, emptyList(), List(ms.rows) { rig.surface.rowKey(it) }, 0L, ids)
                img = cacheOf(ms)
            }
            init { stage() }
            fun endsHold(what: String) {
                for (nt in notches(ms, activeOnly = true)) {
                    Motion.play(nt.pan.program, ListStaging.SLOT, ms.period, 1, 0, nt.p)
                    for (left in listOf(true, false)) {
                        val pb = panel(rig.truth(nt.c, left))
                        val end = Motion.endOnModel(nt.pan.program, nt.p, img, pb, pb, left)
                        assertEquals(0, differModel(end, rig.truth(nt.to, left), nt.pan.areas(left)), "$what step ${nt.pan.step} ${nt.c}→${nt.to} left=$left: px where the model's END is not Main's paint")
                        val belief = rig.truth(nt.c, left).copy().also { ms.paintEnd(nt.pan, nt.p, left, it) }
                        assertEquals(0, differModel(end, belief, nt.pan.areas(left)), "$what step ${nt.pan.step} ${nt.c}→${nt.to} left=$left: px where belief's END is not the model's")
                    }
                }
            }
            /** Row [id]'s summary set to [s], painted again and re-staged: a move applied (its writes into the cache image)
             *  and its old slot released, as the shell does at the switch's acknowledgement, then every END checked; or
             *  `Outgrown`. The outcome in words. */
            fun change(id: String, s: DamageWindow.Summary, what: String): String {
                val row = rows.indexOfFirst { it.id == id }
                rows[row].s = s
                rig.repainted()
                val freeBefore = ms.free.toMap()
                val end0 = ms.end
                return when (val r = ms.restage(row, rig.view, rig.kit, { _, f -> f() })) {
                    is ListStaging.Restage.Changed -> {
                        val mv = assertNotNull(r.band, "$what: the band form changed")
                        assertEquals(freeBefore.filter { it.value >= mv.encoded.size }.minByOrNull { it.value }!!.value, mv.slot, "$what: the smallest free slot that holds it")
                        for (w in r.writes) { assertTrue(w.off >= ms.start && w.off + w.bytes.size <= ms.end, "$what: ${w.what} at ${w.off} inside the staging"); w.bytes.copyInto(img, w.off) }
                        ms.apply(r); ms.release(mv.oldOff4)
                        assertEquals(end0, ms.end, "$what: the staging's extent kept")
                        val out = "moved ${mv.encoded.size} B into a ${mv.slot} B slot"
                        endsHold("$what ($out)")
                        out
                    }
                    is ListStaging.Restage.Outgrown -> "outgrown (${r.why})"
                    else -> throw AssertionError("$what: $r")
                }
            }
            fun slotOf(id: String) = ms.bands[rows.indexOfFirst { it.id == id }].slot
            fun summaryOf(id: String) = rows.first { it.id == id }.s
        }
        val outcomes = ArrayList<String>()
        for (s5 in listOf(false, true)) {
            val st = "stage ${if (s5) 5 else 4}"
            // (1) one row's growth, twice
            data class Grow(val name: String, val id: String, val step: (DamageWindow.Summary, Int) -> DamageWindow.Summary)
            for (g in listOf(
                Grow("a title one glyph longer", "games") { s, k -> s.copy(line = s.line + "!".repeat(k)) },
                Grow("a title one word longer", "games") { s, k -> s.copy(line = s.line + " tonight".repeat(k)) },
                Grow("a row that gains a badge", "settings") { s, k -> s.copy(progress = 0.3 * k, more = true) })) {
                val r = Run(s5)
                val base = r.summaryOf(g.id)
                val own = r.slotOf(g.id); val big = r.ms.free.values.max()
                assertTrue(own < big, "$st ${g.name}: the row's own slot $own B under the largest $big B")
                val c1 = r.change(g.id, g.step(base, 1), "$st ${g.name}, change 1")
                assertTrue(c1.startsWith("moved") && r.slotOf(g.id) == big, "$st ${g.name}: the first change into a spare: $c1")
                val c2 = r.change(g.id, g.step(base, 2), "$st ${g.name}, change 2")
                assertTrue(c2.startsWith("moved"), "$st ${g.name}: the second change moves (a spare is still free): $c2")
                outcomes += "$st ${g.name} (${g.id}): own slot $own B, the largest $big B; $c1; then $c2"
            }
            // (2) two rows past their own slots in turn, then the first again: outgrown; the next staging carries their room.
            // Files and Feed staged with no summary (a band form of their name and icon alone, the smallest slots), then
            // given the fixture's summaries — forms no larger than the largest slot, far past their own
            val full = MainLocalCheck.sampleRows().associate { it.id to it.s }
            val r = Run(s5) { rows -> for (w in rows) if (w.id == "files" || w.id == "feed") w.s = DamageWindow.Summary("") }
            val long = { id: String, k: Int -> full.getValue(id).copy(detail = full.getValue(id).detail + " ·".repeat(k)) }
            val f0 = r.slotOf("files"); val d0 = r.slotOf("feed"); val big = r.ms.free.values.max()
            val a1 = r.change("files", long("files", 0), "$st two rows: Files 1")
            val b1 = r.change("feed", long("feed", 0), "$st two rows: Feed 1")
            val a2 = r.change("files", long("files", 1), "$st two rows: Files 2")
            assertTrue(a1.startsWith("moved") && b1.startsWith("moved") && a2.startsWith("outgrown"),
                "$st two rows: Files into a spare, Feed into the other, Files again finds only the two own slots ($f0, $d0 B): $a1; $b1; $a2")
            val fi = r.rows.indexOfFirst { it.id == "files" }
            assertTrue(r.ms.bandSeen[fi] > f0, "$st: Files' largest band form kept for the next staging: ${r.ms.bandSeen[fi]}")
            r.b.drop()
            r.stage()
            val big2 = r.ms.free.values.max()
            assertEquals(big2, r.slotOf("files"), "$st: after the full staging Files has the largest slot")
            assertEquals(big2, r.slotOf("feed"), "$st: and Feed")
            r.endsHold("$st after the full staging")
            val a3 = r.change("files", long("files", 0), "$st two rows: Files 3")
            val b2 = r.change("feed", long("feed", 1), "$st two rows: Feed 2")
            val a4 = r.change("files", long("files", 1), "$st two rows: Files 4")
            assertTrue(listOf(a3, b2, a4).all { it.startsWith("moved") }, "$st: after the full staging both rows move as before: $a3; $b2; $a4")
            outcomes += "$st two rows (Files own $f0 B, Feed own $d0 B, the largest $big B): $a1; $b1; Files again $a2; the full staging gives both $big2 B; then $a3; $b2; $a4"
        }
        println("O12 FACT (the rig, BoxFace): " + outcomes.joinToString(" | "))
    }

    // ------------------------------------------------------------------- K1-2 stage 2: a window's list in the window form
    private val region256 = CfwModes.mainRegion(CfwModes.CACHE2_MAX_RIDERS, true)

    /**
     * K1-2 stage 2 (the K1-2 spec §2.1, §8 pin 10): every effect of the window form ([ScrollEffects.WINDOW_EFFECTS]) at
     * every cursor a notch lands on, both ways, at the four heights, on a ring list and a plain one (Settings' categories,
     * Global and a three-row category, through Settings' own painters), both lenses: END is the list's own paint at the new
     * cursor in the rows' areas and the entering row's ROW form at the band area's first line in the band's area; the start
     * state (every effect but None, whose one step is its end) is the list at the old cursor with the leaving row's row
     * form there. The staging is the strip and the programs alone, laid out down from the top it is given; the list is
     * glasses list 1 at pitch +rowH with no item table and a report-only tap entry; every rule is kind 2 (P0..P2) and kind 0
     * (P3); the band's area leaves its two rules out — no area of any track covers a rule's pixel.
     */
    @Test
    fun theWindowFormsEndIsTheListsPaintWithTheRowFormInTheBand_K12S2() {
        var ends = 0
        val table = ArrayList<String>()
        for (h in heights) for (sample in WindowListCheck.Sample.entries) {
            val rig = WindowListCheck.WindowRig(BoxFace, h, sample)
            val l = rig.layout
            for (e in ScrollEffects.WINDOW_EFFECTS) {
                val ms = rig.staging(e, region256.first, region256.last + 1)
                val what0 = "$h ${sample.id} ${e.word}"
                assertEquals(ListForm.STRIP_WINDOWS, ms.form, what0)
                assertTrue(ms.bands.isEmpty() && ms.wrap == null && ms.blobs.map { it.name } == listOf("strip", "programs"), "$what0: the strip and the programs alone: ${ms.blobs.map { it.name }}")
                assertEquals(ListStaging.ringFits(l, ms.rows), ms.ring, "$what0: a ring list exactly where the list's paint wraps")
                assertEquals(region256.last + 1, ms.end, "$what0: laid out down from its top")
                assertEquals(e, ms.plays.effect, "$what0: the form plays its own effect")
                val list = ms.boundList(1, list = WindowListCheck.LIST, entryIds = WindowListCheck.ENTRIES)
                assertTrue(list.list == 1 && list.items == null && list.items4 == CfwModes.ITEMS_NONE && list.pitch == l.rowH && list.ring == ms.ring,
                    "$what0: list 1, no item table, pitch +rowH")
                assertEquals(listOf(3, 4, 5), list.entries.map { it.entry }.sorted(), "$what0: entries 3–5 (decisions BD)")
                assertTrue(list.entryFor(CfwModes.EV_TAP)!!.reportOnly, "$what0: the tap entry is report-only (no press)")
                for (pan in ms.pans) {
                    assertEquals(listOf(CfwModes.KIND_PITCH, CfwModes.KIND_PITCH, CfwModes.KIND_PITCH, CfwModes.KIND_ARG), pan.rules.map { it.kind }, "$what0: the rules' kinds")
                    for (left in listOf(true, false)) {
                        val band = pan.areas(left)[2]
                        assertTrue(band.y == l.lens.y + ContentKit.LENS_RULE && band.bottom == l.lens.bottom - ContentKit.LENS_RULE, "$what0: the band's area leaves its rules out: $band")
                        assertTrue(pan.areas(left).none { a -> a.y < l.lens.y + ContentKit.LENS_RULE && a.bottom > l.lens.y || a.y < l.lens.bottom && a.bottom > l.lens.bottom - ContentKit.LENS_RULE },
                            "$what0: no track's area covers a rule: ${pan.areas(left)}")
                    }
                    val entry = list.entryFor(if (pan.step > 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)!!
                    for (c in 0 until ms.rows) {
                        val f = list.predict(c, entry) as? BoundList.Fire.Drew ?: continue
                        val to = f.cursor; val p = f.p!!
                        Motion.play(pan.program, ListStaging.SLOT, ms.period, 1, 0, p)          // the offline checks at these P
                        for (left in listOf(true, false)) {
                            val areas = pan.areas(left)
                            val end = rig.truth(c, left).copy().also { ms.paintEnd(pan, p, left, it) }
                            assertEquals(0, differ(end, rig.withBandRow(rig.truth(to, left), to, left, areas[2]), areas),
                                "$what0 step ${pan.step} $c→$to left=$left: px where END is not the list's paint (and the row form in the band)")
                            if (e != ScrollEffect.NONE) {
                                val start = rig.truth(c, left).copy().also { ms.paintStart(pan, p, left, it) }
                                assertEquals(0, differ(start, rig.withBandRow(rig.truth(c, left), c, left, areas[2]), areas),
                                    "$what0 step ${pan.step} $c→$to left=$left: px where the start state is not the list at $c (and its row form in the band)")
                            }
                        }
                        ends++
                    }
                }
                if (e == ScrollEffect.DEPTH) table += "$what0: ${ms.rows} rows ${if (ms.ring) "ring" else "plain"}, strip ${ms.strip.encoded.size} B in ${ms.strip.slot} B, pan slot ${ms.progSlot} B ×${ms.progSlots?.size ?: 1}, P ${ms.period} T ${ms.t}"
            }
        }
        assertTrue(ends >= 800, "$ends notches checked")
        println("K12S2-window-form ($ends notches; BoxFace)\n" + table.joinToString("\n"))
    }

    /**
     * K1-2 stage 2 (O7): the row form's place in the band read off Settings' two paints (the first window list to bind):
     * a category's row draws its name in the row face, its band the same name in that face's bold — drawn
     * [ScrollEffects.BAND_ROW_DY] below the band's top, the row's name line lies on the band's title line (the first ink row
     * of the same word in its columns; BoxFace's metrics), every category. Global's rows are printed beside it (their
     * value in the row face on the same line, their 13-px label above).
     */
    @Test
    fun theBandRowsPlaceIsReadOffSettingsTwoPaints_K12S2() {
        fun inkTop(g: Gray8, x0: Int, x1: Int, y0: Int, y1: Int): Int = (y0 until y1).first { y -> (x0 until x1).any { x -> g[x, y] != 0 } }
        val seen = ArrayList<String>()
        for (sample in listOf(WindowListCheck.Sample.CATEGORIES, WindowListCheck.Sample.GLOBAL)) {
            val rig = WindowListCheck.WindowRig(BoxFace, 352, sample)
            val l = rig.layout
            for (i in 0 until rig.rows) {
                val row = rig.rowForm(i)                                     // at the row's own x 0 = content.x
                val name = inkTop(row, 40, 260, 0, row.h)
                val g = Gray8(wm.damage.core.geom.Geometry.PANEL_W, wm.damage.core.geom.Geometry.PANEL_H)
                rig.kit.paintLensBand(g, l, rig.view, i)
                val title = inkTop(g, l.lens.x + 44, l.lens.x + 260, l.lens.y + ContentKit.LENS_RULE, l.lens.bottom - ContentKit.LENS_RULE) - l.lens.y
                seen += "${sample.id} $i: name ${name + ScrollEffects.BAND_ROW_DY}, title $title"
                if (sample == WindowListCheck.Sample.CATEGORIES)
                    assertEquals(title, name + ScrollEffects.BAND_ROW_DY, "category $i: the name line in the band, against its title")
            }
        }
        println("K12S2-band-row (BoxFace, 352): " + seen.joinToString("; "))
    }

    /** K1-2 stage 2: Crush and Flip asked on a window list play Slide and Depth (not built on the form yet), said; the form
     *  is refused below stage 5, at 160 KiB and where its space is short (the spare pan slot given up first). */
    @Test
    fun theWindowFormsFallbacksAndRefusals_K12S2() {
        val fx = MainLocalCheck.effectsAt(true)
        assertEquals(ScrollEffect.SLIDE, ScrollEffects.playsFor(ScrollEffect.CRUSH, ListForm.STRIP_WINDOWS, fx).effect)
        assertEquals(ScrollEffect.DEPTH, ScrollEffects.playsFor(ScrollEffect.FLIP, ListForm.STRIP_WINDOWS, fx).effect)
        assertEquals("plays Slide", ScrollEffects.playsFor(ScrollEffect.CRUSH, ListForm.STRIP_WINDOWS, fx).words(ScrollEffect.CRUSH))
        assertEquals("plays Depth", ScrollEffects.playsFor(ScrollEffect.FLIP, ListForm.STRIP_WINDOWS, fx).words(ScrollEffect.FLIP))
        // Main's words as before
        assertEquals("plays Slide", ScrollEffects.plays(ScrollEffect.CRUSH, null).words(ScrollEffect.CRUSH))
        assertEquals("plays its height turn", ScrollEffects.plays(ScrollEffect.FLIP, null).words(ScrollEffect.FLIP))
        assertEquals("", ScrollEffects.plays(ScrollEffect.FLIP, fx).words(ScrollEffect.FLIP))
        assertEquals("", ScrollEffects.plays(ScrollEffect.DEPTH, fx).words(ScrollEffect.DEPTH))
        val rig = WindowListCheck.WindowRig(BoxFace, 352, WindowListCheck.Sample.GLOBAL)
        val crush = rig.staging(ScrollEffect.CRUSH, region256.first, region256.last + 1)
        assertTrue(crush.plays.effect == ScrollEffect.SLIDE && crush.motion?.effect == ScrollEffect.CRUSH && crush.plays.why == ScrollEffects.windowNotYet(ScrollEffect.CRUSH), "Crush on a window list: ${crush.plays}")
        val m = ListStaging.ScrollMotion.DEFAULT
        val cache = CfwModes.CACHE2_MAX_RIDERS
        assertFailsWith<LintError>("below stage 5") { ListStaging.buildWindow(rig.layout, rig.view, rig.kit, rig.listD, rig.bandD, cache, m, null) }
        assertFailsWith<LintError>("at 160 KiB") { ListStaging.buildWindow(rig.layout, rig.view, rig.kit, rig.listD, rig.bandD, CfwModes.CACHE2_MAX, m, MainLocalCheck.effectsAt(true), 0, 160 * 1024) }
        val full = rig.staging(ScrollEffect.DEPTH, region256.first, region256.last + 1)
        val need = full.end - full.start
        val floorTight = region256.last + 1 - (need - full.progSlot)
        val tight = rig.staging(ScrollEffect.DEPTH, floorTight, region256.last + 1)
        assertTrue(tight.progSlots == null && tight.start == floorTight, "the spare pan slot is given up first where the space is short: ${tight.progSlots?.toList()} at ${tight.start}")
        val e = assertFailsWith<LintError> { rig.staging(ScrollEffect.DEPTH, region256.last + 1 - (need - full.progSlot) + 4, region256.last + 1) }
        assertTrue("not bound" in (e.message ?: ""), "${e.message}")
    }

    /**
     * C3 DM-2a, the defect first (the DM-2 spec §1, §8's first pin; decisions DR (j), DV): every effect `Scroll effect`
     * offers, asked on a window's list before its own form is built, stages and plays — itself where the form plays it
     * ([ScrollEffects.WINDOW_EFFECTS]), Slide for Crush, Depth for any other — and says so in the row's words with its
     * reason naming the asked effect, at the staging, at a programs-only re-make from Depth, and in the per-app row's
     * words (the gate's token absent: the row is said before any staging). Without the window branch's general fallback
     * an effect outside the form reaches `windowPan`, which refuses it: the list is not staged.
     */
    @Test
    fun aWindowListPlaysDepthForAnEffectItsFormDoesNotPlay_DM2a() {
        val fx = MainLocalCheck.effectsAt(true)
        val rig = WindowListCheck.WindowRig(BoxFace, 352, WindowListCheck.Sample.GLOBAL)
        val depth = rig.staging(ScrollEffect.DEPTH, region256.first, region256.last + 1)
        for (e in ScrollEffect.entries) {
            val want = if (e in ScrollEffects.WINDOW_EFFECTS) e else if (e == ScrollEffect.CRUSH) ScrollEffect.SLIDE else ScrollEffect.DEPTH
            val words = if (want == e) "" else "plays ${want.word}"
            for (token in listOf(fx, null)) {
                val p = ScrollEffects.playsFor(e, ListForm.STRIP_WINDOWS, token)
                assertEquals(want, p.effect, "${e.word} on a window list (token ${token != null})")
                assertEquals(words, p.words(e), "${e.word}: the row's words")
                if (want != e) assertTrue(e.word in (p.why ?: ""), "${e.word}: the reason names it: ${p.why}")
            }
            val ms = rig.staging(e, region256.first, region256.last + 1)
            assertEquals(e to want, ms.motion?.effect to ms.plays.effect, "${e.word}: the window list stages and plays ${want.word}")
            val made = depth.remake(ListStaging.ScrollMotion.DEFAULT.copy(effect = e), fx)
            assertEquals(if (e == ScrollEffect.DEPTH) null else want, (made as? ListStaging.Remake.Owed)?.programs?.plays?.effect,
                "${e.word}: a programs-only re-make from Depth plays ${want.word} (${(made as? ListStaging.Remake.NoRoom)?.why})")
        }
    }

    /** K1-2 stage 2: a window list's row whose value changes is re-staged by its strip's part alone — its segment(s) written
     *  in place inside the strip's slot, no band form and no item word — and after it every END is the list's paint. */
    @Test
    fun aWindowListsChangedRowIsItsStripSegmentAlone_K12S2() {
        for (h in heights) {
            val rig = WindowListCheck.WindowRig(BoxFace, h, WindowListCheck.Sample.APP3)
            val ms = rig.staging(ScrollEffect.DEPTH, region256.first, region256.last + 1)
            rig.values["${rig.app}/1"] = "On"
            rig.repainted()
            val r0 = ms.restage(1, rig.view, rig.kit, { _, b -> b() })
            val r = r0 as? ListStaging.Restage.Changed ?: throw AssertionError("$h: row 1 changed, re-staged as $r0")
            assertTrue(r.band == null && r.wrapWrites.isEmpty() && r.strip.isNotEmpty(), "$h: the strip's part alone")
            assertTrue(r.writes.all { w -> w.off >= ms.strip.off && w.off + w.bytes.size <= ms.strip.off + ms.strip.slot }, "$h: every write inside the strip's slot")
            ms.apply(r)
            val list = ms.boundList(1, list = WindowListCheck.LIST, entryIds = WindowListCheck.ENTRIES)
            for (pan in ms.pans) {
                val entry = list.entryFor(if (pan.step > 0) CfwModes.EV_UPROLL else CfwModes.EV_DOWNROLL)!!
                for (c in 0 until ms.rows) {
                    val f = list.predict(c, entry) as? BoundList.Fire.Drew ?: continue
                    for (left in listOf(true, false)) {
                        val areas = pan.areas(left)
                        val end = rig.truth(c, left).copy().also { ms.paintEnd(pan, f.p!!, left, it) }
                        assertEquals(0, differ(end, rig.withBandRow(rig.truth(f.cursor, left), f.cursor, left, areas[2]), areas), "$h after the re-stage: $c→${f.cursor} left=$left")
                    }
                }
            }
        }
    }

    private fun differ(a: Gray8, b: Gray8, areas: List<Rect>): Int {
        var n = 0
        for (r in areas) for (y in r.y until r.bottom) for (x in r.x until r.right) if (a[x, y] != b[x, y]) n++
        return n
    }
}
