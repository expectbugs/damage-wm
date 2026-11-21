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
     *  the pans in order (strip slot : step) and the SHA-256 of their records back to back. */
    private val todays = mapOf(
        (288 to 160) to ("0:1,0:-1,2:1,2:-1" to "656b94c4c9f5587d92edf7872931a77a7c373c9f2b119e636c6bec45dee0900f"),
        (288 to 256) to ("0:1,0:-1,1:1,1:-1,2:1,2:-1" to "f4fbe1e079b9771593a041514422479ee1bb85659eae97f3cbb783258a3e296f"),
        (352 to 160) to ("0:1,0:-1,2:1,2:-1" to "08934284c6cbbc8718b90f49fab2b3d0e6343e47a42d1574dad39f38cee8c2cb"),
        (352 to 256) to ("0:1,0:-1,1:1,1:-1,2:1,2:-1" to "84e763fa97f88ab8e3475275ca2bd11c7987f63f7703c92052bce088db351cef"),
        (416 to 160) to ("0:1,0:-1" to "1a0f359f7329ac99bd9781eec93c2f0f1fe0aa5460ed903c2ee0925ddf23131e"),
        (416 to 256) to ("0:1,0:-1,1:1,1:-1" to "1bda524be8c864794503b49d0d7fcf3703280f9aea35bbbe6a54ee9582ce6448"),
        (480 to 160) to ("0:1,0:-1" to "99a6314e4dbbc6742b27dbc304cc827bcb203e9662689579f7bd256324e5a499"),
        (480 to 256) to ("0:1,0:-1,1:1,1:-1" to "6bdc98fc486021a080db4a9808c92c2a7361c07fb6766b0154660bb2744a8f25"),
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
                    // what the glasses compute for this pan's step from row c — at v = c' unwrapped across the wrap (the
                    // two strips' pans share their rules; only their programs differ)
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
            widths = listOf(MainStaging.WHOLE), twoStrips = true, motion = MainStaging.ScrollMotion.DEFAULT)
        assertNull(short.progSlots, "the spare slot given up")
        assertTrue(short.wrap != null && short.stripB != null, "the wrap and the second strip kept")
        assertEquals(digest(full.pans), digest(short.pans), "the same pans")
        assertTrue(short.remake(MainStaging.ScrollMotion.DEFAULT.copy(effect = ScrollEffect.SLIDE)) is MainStaging.Remake.NoRoom)
    }

    // ------------------------------------------------------------------------------- C3 DM-1: Crush and Flip

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
            if (activeOnly && pan.strip != ms.activeStrip) continue
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
     * §1.2, read from the program's own segments at its own eases). Also: what Crush's two phases do at the fitted step
     * counts, printed for the report (each phase's ease-out, the pixels moved per step).
     */
    @Test
    fun crushRunsInTwoPhasesAndItsCoversLeaveNoGap_DM1() {
        var steps = 0
        val shapes = ArrayList<String>()
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val cache = CfwModes.CACHE2_MAX_RIDERS
            val ms = stage5(h, cache, ScrollEffect.CRUSH, rig)
            val cacheImg = cacheOf(ms)
            val l = ms.layout
            val t = ms.t
            val n1 = ScrollEffects.phase1(t)
            for (nt in notches(ms, activeOnly = true)) {
                val pr = nt.pan.program
                val segs = pr.resolve(nt.p, staticOnly = false)
                val down = nt.pan.step > 0
                val what0 = "$h step ${nt.pan.step} ${nt.c}→${nt.to}"
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
                val s2 = segs[1][1]; val s5 = segs[4][1]
                val e2 = Motion.easeE(s2.ease, s2.steps); val e5 = Motion.easeE(s5.ease, s5.steps)
                val f = pr.tracks[1].areaL
                for (j in 0..t - n1) {
                    val top = s2.y + (s5.aux0 - s2.aux0) + Motion.eased(0, s2.dy, e2[j])          // row c's top as T2 draws it
                    val inF = maxOf(top, f.y) until minOf(top + l.rowH, f.bottom)
                    val h5 = Motion.eased(s5.h0, s5.h1 - s5.h0, e5[j])
                    val grown = Motion.anchoredY(s5.y, l.rowH, h5, s5.anchor and 3).let { it until it + h5 }
                    assertTrue(inF.isEmpty() || (inF.first >= grown.first && inF.last <= grown.last), "$what0 phase 2 step $j: row c's rows in the far side $inF outside the growing row $grown")
                }
                // the phases on the model
                for (left in listOf(true, false)) {
                    val base = panel(rig.truth(nt.c, left))
                    val far = pr.tracks[1].area(left); val enter = pr.tracks[0].area(left)
                    for (k in 1..t) {
                        val pic = Motion.stepOnModel(pr, nt.p, cacheImg, base, base, left, k = k)
                        if (k <= n1) assertEquals(0, differModel(pic, rig.truth(nt.c, left), listOf(far)), "$what0 left=$left step $k: the far side is not Main at ${nt.c} in phase 1")
                        else assertEquals(0, differModel(pic, rig.truth(nt.to, left), listOf(enter)), "$what0 left=$left step $k: the entering side is not at rest in phase 2")
                        steps++
                    }
                }
            }
            // what the two phases look like at the fitted T (the brief, decisions BE (6)): each phase's ease-out over its steps
            fun moved(n: Int, d: Int) = (1..n).map { j -> Motion.eased(0, d, Motion.easeE(Motion.Ease.easeOut, n)[j]) - Motion.eased(0, d, Motion.easeE(Motion.Ease.easeOut, n)[j - 1]) }
            shapes += "h$h T $t (P ${ms.period}): phase 1 n1 $n1 — rows px a step ${moved(n1, l.rowH)}, band ${moved(n1, l.lens.h)}; " +
                "phase 2 n2 ${t - n1} — rows ${moved(t - n1, l.rowH)}, growing row ${moved(t - n1, l.rowH - 1)}"
        }
        println("DM1-phases: $steps step pictures checked\n" + shapes.joinToString("\n"))
    }

    /**
     * C3 DM-1, the DM spec §5.1's "Flip": at the middle of the turn (step n1) the band shows only one column of the band
     * form at the band's centre — the rest of the band its backdrop — at stage 5 (the width turn, version 2); and the same
     * on the height below stage 5 (its fallback: one row at the band's middle, version 1). Rows and band start and end
     * together (T steps each).
     */
    @Test
    fun flipTurnsTheBandToOneColumnAtTheMiddleStep_DM1() {
        for (h in heights) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val cache = CfwModes.CACHE2_MAX_RIDERS
            for (st5 in listOf(true, false)) {
                val ms = if (st5) stage5(h, cache, ScrollEffect.FLIP, rig) else staging(h, cache, ScrollEffect.FLIP, rig)
                assertEquals(ScrollEffects.Plays(ScrollEffect.FLIP, st5, if (st5) null else ScrollEffects.NOT_STAGE5), ms.plays, "$h stage ${if (st5) 5 else 4}: Flip's form")
                val cacheImg = cacheOf(ms)
                val l = ms.layout
                val n1 = ScrollEffects.phase1(ms.t)
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
                table += "h$h ${cache / 1024} KiB ${e.word}: step ${"%.1f".format(step)} ms, P ${ms.period} T ${ms.t} (n1 ${ScrollEffects.phase1(ms.t)}); " +
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

    private fun differ(a: Gray8, b: Gray8, areas: List<Rect>): Int {
        var n = 0
        for (r in areas) for (y in r.y until r.bottom) for (x in r.x until r.right) if (a[x, y] != b[x, y]) n++
        return n
    }
}
