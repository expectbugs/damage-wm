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
                    assertEquals(ScrollEffects.areaPx(e, ms.layout, ms.listD, ms.bandD), whole.areaPx, "$what0: the fitted area is the tracks' own")
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
     * X2M0). At 416 and 480 the staging is today's form, byte for byte, at stage 5 too.
     */
    @Test
    fun theStage5FormHoldsEveryEffectAcrossTheWrapAndIsTodaysWhereMainDoesNotWrap_DM0() {
        val table = ArrayList<String>()
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
                if (!wraps) {
                    assertEquals(s4.blobs.map { it.off to it.bytes.toList() }, s5.blobs.map { it.off to it.bytes.toList() }, "$what0: today's form, byte for byte")
                    continue
                }
                val k = l.rowsAbove
                assertEquals(null, s5.wrap, "$what0: no wrap record")
                assertEquals(k + 1, s5.margin, "$what0: margins of k + 1")
                assertEquals((s5.rows + 2 * (k + 1)) * l.rowH, s5.strip.raw.h, "$what0: the strip's rows")
                for (pan in s5.pans) assertEquals(listOf(l.lens.y + (pan.step - k - 1) * l.rowH, l.lens.bottom + (pan.step - 1 - k - 1) * l.rowH),
                    listOf(pan.rules[0].arg, pan.rules[3].arg), "$what0: P0's and P3's rules take the margin")
                assertNull(s5.wrapPan(1) ?: s5.wrapPan(-1), "$what0: no wrap pan (the wrap notch fires on the glasses)")
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
                assertEquals(s5.pans.size * s5.rows, n, "$what0: every cursor, the two wraps among them, on every pan")
                if (e == ScrollEffect.DEPTH) table += "h$h ${cache / 1024} KiB: the strip ${s5.blobs[0].bytes.size} B (${s5.rows + 2 * (k + 1)} rows; " +
                    "stage 4: ${s4.blobs[0].bytes.size} B and a wrap record ${s4.wrap?.encoded?.size ?: 0} B), the staging ${s5.end - s5.start} B (stage 4: ${s4.end - s4.start} B)"
            }
        }
        println("DM0-staging (BoxFace, modeled)\n" + table.joinToString("\n"))
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

    private fun differ(a: Gray8, b: Gray8, areas: List<Rect>): Int {
        var n = 0
        for (r in areas) for (y in r.y until r.bottom) for (x in r.x until r.right) if (a[x, y] != b[x, y]) n++
        return n
    }
}
