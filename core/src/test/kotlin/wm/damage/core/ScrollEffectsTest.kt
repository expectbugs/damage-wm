package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.motion.BoxFace
import wm.damage.core.motion.Motion
import wm.damage.core.motion.ScrollEffect
import wm.damage.core.motion.ScrollEffects
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.MainLocalCheck
import wm.damage.core.shell.MainStaging
import wm.damage.core.shell.ShellSettings
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
        rig.staging(cache, CfwModes.mainRegion(cache, true), MainStaging.ScrollMotion.DEFAULT.copy(effect = e))

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

    private fun digest(pans: List<MainStaging.Pan>): String {
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
                        val p = (if (pan.strip == MainStaging.WRAP_STRIP) ms.wrapParams(pan, cPrime) else ms.params(pan, cPrime)) ?: continue
                        val c = cPrime - pan.step
                        val to = cPrime.mod(ms.rows)
                        Motion.play(pan.program, MainStaging.SLOT, ms.period, 1, 0, p)
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
                    val whole = ms.pan(MainStaging.WHOLE, 1)
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
                val m = MainStaging.ScrollMotion.DEFAULT.copy(effect = e)
                val s5 = rig.staging(cache, CfwModes.mainRegion(cache, true), m, stage5 = true)
                val s4 = rig.staging(cache, CfwModes.mainRegion(cache, true), m)
                val what0 = "$h ${cache / 1024} KiB ${e.word} at stage 5"
                val l = s5.layout
                val wraps = MainStaging.ringFits(l, s5.rows)
                assertEquals(wraps, s5.ring, "$what0: the ring form exactly where Main's paint wraps")
                assertTrue(s5.stage5 && !s4.stage5, "$what0: each staging records the EFFECTS it was made under")
                val k = l.rowsAbove
                if (!wraps) {
                    // C3 FF: today's form — every record but the programs byte for byte (the programs take the fast fit's
                    // T); END is checked below at every cursor a notch lands on, the ends being edges
                    assertEquals(s4.blobs.dropLast(1).map { it.off to it.bytes.toList() }, s5.blobs.dropLast(1).map { it.off to it.bytes.toList() }, "$what0: today's form, byte for byte")
                    assertEquals("programs", s5.blobs.last().name, "$what0: the programs last")
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
                assertEquals(ScrollEffects.timing(e, l, s5.listD, s5.bandD, m, true), s5.period to s5.t, "$what0: the fast DRAW path's fit")
                assertEquals(if (e == ScrollEffect.NONE) 1 else s5.t, s5.pan(MainStaging.WHOLE, 1).program.T, "$what0: the pans' T is the fit's")
                val list = s5.boundList(1)
                var n = 0
                for (pan in s5.pans) for (cPrime in -1..s5.rows) {
                    val c = cPrime - pan.step
                    if (c !in 0 until s5.rows) continue                 // no notch lands there from a row
                    // what the glasses compute for this pan's step from row c — at v = c' unwrapped across the wrap
                    val le = list.entries.first { !it.isTap && it.step == pan.step }
                    val p = (list.predict(c, le) as? wm.damage.core.local.BoundList.Fire.Drew)?.p ?: continue
                    val to = cPrime.mod(s5.rows)
                    Motion.play(pan.program, MainStaging.SLOT, s5.period, 1, 0, p)
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
                val m = MainStaging.ScrollMotion.DEFAULT.copy(effect = e)
                val s5 = rig.staging(cache, CfwModes.mainRegion(cache, true), m, stage5 = true)
                val s4 = rig.staging(cache, CfwModes.mainRegion(cache, true), m)
                val what = "$h ${cache / 1024} KiB ${e.word}"
                if (e == ScrollEffect.DEPTH) assertEquals(16 to 19, s5.period to s5.t, "$what: the spec's §1.6 — Depth P 16, T 19 at stage 5")
                assertEquals(ScrollEffects.timing(e, s4.layout, s4.listD, s4.bandD, m, false), s4.period to s4.t, "$what: C2's fit below stage 5")
                if (e == ScrollEffect.NONE) assertEquals(1, s5.t, "$what: None one step")
                // the band's swap keeps its 2-of-5 proportion at the fitted T (Depth's two band segments)
                if (e == ScrollEffect.DEPTH) assertEquals(listOf(MainStaging.swapOf(s5.t), s5.t - MainStaging.swapOf(s5.t)),
                    s5.pan(MainStaging.WHOLE, 1).program.tracks[2].segments.map { it.steps }, "$what: the swap's proportion")
                for (pan in s5.pans) for (cPrime in -1..s5.rows) {
                    val p = s5.params(pan, cPrime) ?: continue
                    val c = pan.program.cost(p)
                    if (c > worst) { worst = c; worstAt = "$what strip ${pan.strip} step ${pan.step} c' $cPrime" }
                    assertTrue(c * 100 <= Motion.BUDGET_CAP * 80, "$what strip ${pan.strip} step ${pan.step} c' $cPrime: C $c past 80 % of the cap")
                }
                if (cache == CfwModes.CACHE2_MAX_RIDERS) {
                    fit += "h$h ${e.word}: stage 5 P ${s5.period} (${"%.1f".format(s5.period * 1000.0 / 1024)} ms) T ${s5.t} = ${"%.0f".format(s5.t * s5.period * 1000.0 / 1024)} ms; " +
                        "stage 4 P ${s4.period} T ${s4.t}; the fast step ${"%.1f".format(MainStaging.fastStepMs(ScrollEffects.areaPx(e, s5.layout, s5.listD, s5.bandD), ScrollEffects.walkBytes(e, s5.layout)))} ms (modeled)"
                    bytes += "h$h ${e.word}: the programs record ${s5.blobs.last().bytes.size} B at T ${s5.t} (stage 4 ${s4.blobs.last().bytes.size} B at T ${s4.t}); " +
                        "a pan slot ${s5.progSlot} B (${s4.progSlot}); the staging ${s5.end - s5.start} B of the region's ${s5.regionEnd - s5.regionStart} (${s4.end - s4.start})"
                }
            }
        }
        // a fixed count: spread over the length, never under the fitted period; Auto uncapped (D8: the `Scroll frames` row is his cap)
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        val cache = CfwModes.CACHE2_MAX_RIDERS
        fun at(m: MainStaging.ScrollMotion) = rig.staging(cache, CfwModes.mainRegion(cache, true), m, stage5 = true).let { it.period to it.t }
        assertEquals(31 to 10, at(MainStaging.ScrollMotion(300, 10)), "10 frames over 300 ms: P ⌈307.2 / 10⌉, above the fit's 16")
        assertEquals(16 to 40, at(MainStaging.ScrollMotion(300, 40)), "40 frames: never under the fitted period (the motion runs longer)")
        assertEquals(16 to 51, at(MainStaging.ScrollMotion(800, 0)), "800 ms Auto: ⌊819.2 / 16⌋ steps, no cap")
        // the programs-only re-make of a stage-5 staging (an effect changed) takes the same fit
        val d5 = rig.staging(cache, CfwModes.mainRegion(cache, true), MainStaging.ScrollMotion.DEFAULT, stage5 = true)
        val ps = assertNotNull((d5.remake(MainStaging.ScrollMotion.DEFAULT.copy(effect = ScrollEffect.SLIDE)) as? MainStaging.Remake.Owed)?.programs)
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
            val pr = ms.pan(MainStaging.WHOLE, 1).program
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
        val d = MainStaging.ScrollMotion.DEFAULT
        assertNull(ms.remakeOwed(d), "nothing owed at the staging's own motion")
        ms.periodInForce = 55
        assertTrue(ms.remakeOwed(d)!!.contains("55"), "${ms.remakeOwed(d)}")
        val p = assertNotNull((ms.remake(d) as? MainStaging.Remake.Owed)?.programs)
        assertEquals(listOf(5, 55, 46, 1, slots[1]), listOf(p.t, p.periodInForce, p.period, p.slot, p.off), "Auto at P 55: T 5, the floor kept, slot 1")
        val old = ms.pans.first().prog4
        ms.switchTo(p)
        assertEquals(listOf(5, 46, 55), listOf(ms.t, ms.period, ms.periodInForce))
        assertEquals(5, ms.pan(MainStaging.WHOLE, 1).program.T)
        assertTrue(ms.programAt(old).T == 6, "the old pan still named until released")
        ms.releasePrograms()
        assertTrue(runCatching { ms.programAt(old) }.isFailure, "released")
        assertNull(ms.remakeOwed(d), "nothing owed once T follows P")
        // a change of the effect: its own fit, the period in force reset to it, back into slot 0
        val slide = d.copy(effect = ScrollEffect.SLIDE)
        val ps = assertNotNull((ms.remake(slide) as? MainStaging.Remake.Owed)?.programs)
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
                assertTrue(ms.remake(MainStaging.ScrollMotion.DEFAULT.copy(effect = e)) is MainStaging.Remake.Owed, "$h ${cache / 1024}: ${e.word} fits the slot")
        }
        // a region a little short of the spare slot: the staging gives the slot up and nothing else
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        val cache = CfwModes.CACHE2_MAX_RIDERS
        val region = CfwModes.mainRegion(cache, true)
        val full = rig.staging(cache, region)
        val short = MainStaging.build(rig.layout, rig.view, rig.kit, rig.listD, rig.bandD, cache, region.first, full.end - 4,
            widths = listOf(MainStaging.WHOLE), motion = MainStaging.ScrollMotion.DEFAULT)
        assertNull(short.progSlots, "the spare slot given up")
        assertTrue(short.wrap != null, "the wrap kept")
        assertEquals(digest(full.pans), digest(short.pans), "the same pans")
        assertTrue(short.remake(MainStaging.ScrollMotion.DEFAULT.copy(effect = ScrollEffect.SLIDE)) is MainStaging.Remake.NoRoom)
    }

    // ------------------------------------------------------------------------------- C3 DM-1: Crush and Flip

    /** C3 X2DM1a: motions at whose T the step nearest half is not the first step past it, for Crush at 352 (225 ms Auto: 14
     *  steps; `Scroll frames` 4 and 8). */
    private val otherSplits = listOf(MainStaging.ScrollMotion(225, 0), MainStaging.ScrollMotion(300, 4), MainStaging.ScrollMotion(300, 8))

    private fun stage5(h: Int, cache: Int, e: ScrollEffect, rig: MainLocalCheck.MainRig,
                       m: MainStaging.ScrollMotion = MainStaging.ScrollMotion.DEFAULT.copy(effect = e)) =
        rig.staging(cache, CfwModes.mainRegion(cache, true), m, stage5 = true)

    /** The cache as the staging writes it — every record at its offset (the programs' own records are written by the
     *  model's renderer itself, `Motion.stepOnModel`). */
    private fun cacheOf(ms: MainStaging): ByteArray = ByteArray(ms.cacheSize).also { c -> for (b in ms.blobs) b.bytes.copyInto(c, b.off) }

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
    private class Notch(val pan: MainStaging.Pan, val c: Int, val to: Int, val p: IntArray)

    private fun notches(ms: MainStaging, activeOnly: Boolean = false): List<Notch> {
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
     * the phone's belief (`MainStaging.paintEnd`); every PLAY passes the offline checks, the step budget among them.
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
                    Motion.play(nt.pan.program, MainStaging.SLOT, ms.period, 1, 0, nt.p)
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
        for ((h, m) in heights.map { it to MainStaging.ScrollMotion.DEFAULT } + otherSplits.map { 352 to it }) {
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
        for ((h, m) in heights.map { it to MainStaging.ScrollMotion.DEFAULT } + otherSplits.drop(1).map { 352 to it }) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val cache = CfwModes.CACHE2_MAX_RIDERS
            for (st5 in if (m == MainStaging.ScrollMotion.DEFAULT) listOf(true, false) else listOf(true)) {
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
            for (m0 in listOf(MainStaging.ScrollMotion.DEFAULT) + otherSplits) for (e in listOf(ScrollEffect.CRUSH, ScrollEffect.FLIP)) {
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
                    assertTrue(c * 100 <= Motion.BUDGET_CAP * MainStaging.BUDGET_PCT, "$what ${nt.c}→${nt.to}: C $c past 80 % of the cap")
                }
                assertTrue(ms.end <= ms.regionEnd && ms.start >= ms.regionStart, "$what: inside Main's region")
                if (cache == CfwModes.CACHE2_MAX_RIDERS) assertNotNull(ms.progSlots, "$what: the spare pan slot at 256 KiB")
                val ms4 = staging(h, cache, e, rig)
                val step = MainStaging.fastStepMs(ScrollEffects.areaPx(e, ms.layout, ms.listD, ms.bandD), ScrollEffects.walkBytes(e, ms.layout), ScrollEffects.pixelPx(ms.plays, ms.layout, ms.listD, ms.bandD))
                table += "h$h ${cache / 1024} KiB ${e.word}: step ${"%.1f".format(step)} ms, P ${ms.period} T ${ms.t} (n1 ${ScrollEffects.halfStep(ms.t)}); " +
                    "C worst ${"%,d".format(worst)} u (${"%.1f".format(worst * 100.0 / Motion.BUDGET_CAP)} %) at $at; one pan ${ms.pan(MainStaging.WHOLE, 1).program.bytes.size} B, " +
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
        fun lay(e: ScrollEffect, strip: Motion.Source.Record, margin: Int) = MainStaging.lay(
            ScrollEffects.Staged(l, rig.listD, rig.bandD, band, listOf(strip, band)), ScrollEffects.plays(e, fx), fx, 30000,
            listOf(1, -1).map { MainStaging.PanSpec(MainStaging.WHOLE, l.rowsAbove, l.rowsBelow, it, 0) }, margin, n, false, items, { strip }) { 16 to 19 }
        val margin = l.rowsAbove
        val good = Motion.Source.Record(1000, rowW, (n + 2 * margin) * l.rowH, 20_000)
        assertEquals(ScrollEffects.Plays(ScrollEffect.CRUSH, true), lay(ScrollEffect.CRUSH, good, margin).plays, "Main's strip: Crush plays itself")
        // a strip whose Crush pans cost between 80 % and the whole cap (the record's L counted whole): refused offline
        val big = Motion.Source.Record(1000, rowW, (n + 2 * margin) * l.rowH, 76_000)
        val staged = ScrollEffects.Staged(l, rig.listD, rig.bandD, band, listOf(big, band))
        val unchecked = MainStaging.layPans(staged, ScrollEffects.Plays(ScrollEffect.CRUSH, true), 19, 30000,
            listOf(1, -1).map { MainStaging.PanSpec(MainStaging.WHOLE, l.rowsAbove, l.rowsBelow, it, 0) }, margin, fx) { big }.first
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
     * in a slot of its own size with a quarter's slack ([MainStaging.slack], the wheel's cells' pattern), the spare in the
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
            val big = MainStaging.slack(ms.bands.maxOf { it.encoded.size })
            for ((i, b) in ms.bands.withIndex()) assertEquals(MainStaging.slack(b.encoded.size), b.slot, "$what: row $i's band slot is its own size")
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
            val paint = MainStaging.Paint(rig.layout, rig.view, rig.kit).also { p -> while (!p.done) p.step { _, bb -> bb() } }
            val room = IntArray(ms.rows).also { it[3] = big + 400 }
            val roomy = MainStaging.assemble(paint, rig.listD, rig.bandD, cache, region.first, region.last + 1, listOf(MainStaging.WHOLE),
                MainStaging.ScrollMotion.DEFAULT, stage5 = s5, fx = if (s5) MainLocalCheck.effectsAt(true) else null, bandRoom = room)
            val big2 = MainStaging.slack(maxOf(ms.bands.maxOf { it.encoded.size }, big + 400))
            assertEquals(big2, roomy.bands[3].slot, "$what: the changing row's slot is the largest, its own last one counted")
            assertEquals(listOf(big2, big2), roomy.free.values.toList(), "$what: the spares as large")
            for (i in roomy.bands.indices) if (i != 3) assertEquals(MainStaging.slack(roomy.bands[i].encoded.size), roomy.bands[i].slot, "$what: row $i its own size")
        }
        // a region that holds the staging only without the band room: the room given up, never a refusal
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        val region = CfwModes.mainRegion(CfwModes.CACHE2_MAX, true)
        val paint = MainStaging.Paint(rig.layout, rig.view, rig.kit).also { p -> while (!p.done) p.step { _, bb -> bb() } }
        val plain = MainStaging.assemble(paint, rig.listD, rig.bandD, CfwModes.CACHE2_MAX, region.first, region.last + 1, listOf(MainStaging.WHOLE), MainStaging.ScrollMotion.DEFAULT)
        val short = MainStaging.assemble(paint, rig.listD, rig.bandD, CfwModes.CACHE2_MAX, region.first, plain.end, listOf(MainStaging.WHOLE), MainStaging.ScrollMotion.DEFAULT,
            bandRoom = IntArray(plain.rows) { 6_000 })
        assertEquals(plain.bands.map { it.slot }, short.bands.map { it.slot }, "the band room given up where the region cannot hold it")
    }

    /**
     * K1-2 stage 1 (O12) on the rig, his height 352 in Main's own region at both stages. (1) A row's title that grows by
     * one glyph, by a word, and a row that gains a badge (▸ and the progress bar) — each changed twice: each change moves
     * the band form into the smallest free slot that holds it (two are free between moves: the spares, or one and the
     * slot the move before freed) — the first into a spare, the second into the row's own old slot where it fits, else
     * the other spare. (2) Two rows whose band forms grow past their own slots, in turn, then the first again: the only
     * free slots are then the two rows' own old ones, so it is `Outgrown` (a full staging, a new mark), and in the next
     * staging — by the room carried by window ([wm.damage.core.shell.MainBinding.bandRoomFor]) — both rows have the
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
            val b = wm.damage.core.shell.MainBinding()
            val ids = rows.map { it.id }
            lateinit var ms: MainStaging
            lateinit var img: ByteArray
            fun stage() {
                val paint = MainStaging.Paint(rig.layout, rig.view, rig.kit).also { p -> while (!p.done) p.step { _, bb -> bb() } }
                ms = MainStaging.assemble(paint, rig.listD, rig.bandD, cache, region.first, region.last + 1, listOf(MainStaging.WHOLE),
                    MainStaging.ScrollMotion.DEFAULT, room = b.roomFor(ids), stage5 = s5, fx = if (s5) MainLocalCheck.effectsAt(true) else null,
                    bandRoom = b.bandRoomFor(ids))
                b.begin(ms, emptyList(), List(ms.rows) { rig.surface.rowKey(it) }, 0L, ids)
                img = cacheOf(ms)
            }
            init { stage() }
            fun endsHold(what: String) {
                for (nt in notches(ms, activeOnly = true)) {
                    Motion.play(nt.pan.program, MainStaging.SLOT, ms.period, 1, 0, nt.p)
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
                    is MainStaging.Restage.Changed -> {
                        val mv = assertNotNull(r.band, "$what: the band form changed")
                        assertEquals(freeBefore.filter { it.value >= mv.encoded.size }.minByOrNull { it.value }!!.value, mv.slot, "$what: the smallest free slot that holds it")
                        for (w in r.writes) { assertTrue(w.off >= ms.start && w.off + w.bytes.size <= ms.end, "$what: ${w.what} at ${w.off} inside the staging"); w.bytes.copyInto(img, w.off) }
                        ms.apply(r); ms.release(mv.oldOff4)
                        assertEquals(end0, ms.end, "$what: the staging's extent kept")
                        val out = "moved ${mv.encoded.size} B into a ${mv.slot} B slot"
                        endsHold("$what ($out)")
                        out
                    }
                    is MainStaging.Restage.Outgrown -> "outgrown (${r.why})"
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

    private fun differ(a: Gray8, b: Gray8, areas: List<Rect>): Int {
        var n = 0
        for (r in areas) for (y in r.y until r.bottom) for (x in r.x until r.right) if (a[x, y] != b[x, y]) n++
        return n
    }
}
