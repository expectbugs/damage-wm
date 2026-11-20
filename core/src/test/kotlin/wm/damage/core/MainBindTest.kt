package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.gfx.Rle
import wm.damage.core.motion.BoxFace
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.MainBinding
import wm.damage.core.shell.MainLocalCheck
import wm.damage.core.shell.MainStaging
import wm.damage.core.shell.ShellSettings
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.MainStageKey
import wm.damage.core.shell.StageKey
import wm.damage.core.shell.StageRing
import wm.damage.core.shell.StagedRecord
import wm.damage.core.shell.Staging
import wm.damage.core.transport.LinkState
import wm.damage.core.transport.atlasCapacity

/**
 * Build C2, stage DM2 (`C2-DM2-brief.md`; spec §3 DM, §6.4; decisions B.4, V): Main's bound list. Context for the
 * reader: Adam's own glasses on his own firmware fork; nothing here contacts a device — the glasses are the firmware
 * model ([wm.damage.core.sim.GlassFirmwareSim]) driven message by message ([MainLocalCheck], the core of `desktop
 * --local-check`).
 *
 * The text is [BoxFace] (half-level edges: raw values that are not a level's, so a belief painted from the records'
 * levels instead of the pictures' raw pixels would differ from the truth byte for byte).
 */
class MainBindTest {

    /**
     * The pins of the package, at Main's four heights and on both caches (160 KiB: the shared reserve; 256: Main's own
     * region, where the band forms' off4 pass 32767 and the lens carries them as negative P): for every cursor and both
     * directions [wm.damage.core.local.BoundList.predict] equals what the simulator's fire reports in fields 5, 8,
     * 10–14; the last step is Main's own paint at the moved cursor in the program's areas; COMMIT's END in the shadow is
     * what belief takes; the press's END is the band row at the cursor drawn one notch forward; the report-only entry
     * draws nothing; and keeping rows current (decisions X): a band form moved to a free slot — a notch before the switch
     * named and committed after it, one after it on the new slot; a row form written with the entries cleared; a row
     * stale by several changes committed from the staged copies, then repainted.
     */
    @Test
    fun theLocalCheckHoldsAtEveryHeightOnBothCaches_DM2() {
        val r = MainLocalCheck.run(BoxFace, frames = false)
        for (line in r.table) println("DM2-staging $line")
        for (c in r.cases) for (l in c.lines.take(2)) println("DM2-case ${c.name}: $l")
        assertTrue(r.failures.isEmpty(), "failures:\n" + r.failures.take(40).joinToString("\n"))
        // decisions AZ.4: and the wrap both ways where Main wraps (288 and 352 with the sample's eight rows), on both caches;
        // K1-1: every other Scroll effect's pans both ways (and its wraps), and the pans re-made alone, at each height and cache
        assertEquals(4 * 2 * (7 + 3 * 2 + 1) + 2 * 2 * 2 * 4, r.cases.size, "every height and cache ran its cases, and the wrapping heights their wraps per effect")
        assertEquals(32, r.cases.count { it.name.contains("-wrap-") }, "the wrap cases: ${r.cases.filter { it.name.contains("-wrap-") }.map { it.name }}")
        assertEquals(8, r.cases.count { it.name.endsWith("-reprogram") && it.lines.isNotEmpty() && it.lines.none { l -> l.startsWith("no spare") } }, "the re-make ran at every height and cache")
    }

    /** Decisions AF.4 (review V1Db §4, its probe turned into the pin): a report adopted before a band switch's
     *  acknowledgement names the retired slot; while a burst is open the slot is NOT released by the ack, so the COMMIT
     *  planned after it still paints END from it; with no burst open it is released. */
    @Test
    fun aBandSlotIsReleasedOnlyWithNoBurstOpen_AF4() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
        val b = wm.damage.core.shell.MainBinding()
        val keys = { List(ms.rows) { rig.surface.rowKey(it) } }
        b.begin(ms, emptyList(), keys(), 0L)
        val first = b.list!!
        val tor = rows.indexOfFirst { it.id == "torrents" }
        val down = first.entries.first { it.eventId == CfwModes.EV_UPROLL }
        val pOld = (first.predict(tor - 1, down) as wm.damage.core.local.BoundList.Fire.Drew).p!!
        rows[tor].s = rows[tor].s.copy(detail = "iso 9%")
        b.keysChanged(keys())
        val r = ms.restage(tor, rig.view, rig.kit, { _, f -> f() }) as MainStaging.Restage.Changed
        b.changed(tor); b.submitted(r, StagedRecord(MainStageKey(ms, 1000 + tor, "re-stage"), Gray8(1, 1), ByteArray(4)), 1L)
        val second = b.list!!
        assertTrue(second.names(tor, down, down.prog4, pOld), "adopted before the acknowledgement")
        b.releaseIfDue(b.patches.single(), acked = true, burstOpen = true)
        second.paintEnd!!(true, down.prog4, pOld, Gray8(640, 480))      // the COMMIT planned in the burst: still paintable
        assertTrue(second.previous != null, "kept while the burst is open")
        b.releaseIfDue(b.patches.single(), acked = true, burstOpen = false)
        assertNull(second.previous, "released once no burst is open")
    }

    /** AD.2 (follow-up FC): a strip switch is released only once the flush that carried the SETs naming the new strip has
     *  completed, not when the rules built them (a fire before a lens applied them draws the old pan, and its report comes
     *  before that flush's ack); a flush that failed leaves it for the next one; a new session forgets the flush in flight. */
    @Test
    fun aStripSwitchIsReleasedWhenItsSetsLand_AD2() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val ms = rig.staging(256 * 1024, CfwModes.mainRegion(256 * 1024, true))
        val b = wm.damage.core.shell.MainBinding()
        val keys = { List(ms.rows) { rig.surface.rowKey(it) } }
        b.begin(ms, emptyList(), keys(), 0L)
        val mus = rows.indexOfFirst { it.id == "music" }
        rows[mus].s = rows[mus].s.copy(line = "Night Drive - Low Tides")
        rig.repainted()
        b.keysChanged(keys())
        val r = ms.restage(mus, rig.view, rig.kit, { _, f -> f() }) as MainStaging.Restage.Changed
        assertEquals(1, r.toStrip, "a row form change at 256 KiB goes to the other strip: stripB ${ms.stripB != null} strip ${r.strip.map { it.what }} band ${r.band != null}")
        b.changed(mus); b.submitted(r, StagedRecord(MainStageKey(ms, 1000 + mus, "re-stage"), Gray8(1, 1), ByteArray(4)), 1L)
        val p = b.patches.single(); val list = b.list!!
        fun kept(what: String) { b.releaseIfDue(p, acked = true, burstOpen = false); assertTrue(list.previous != null && !p.released, what) }
        b.tailSent(5L, entriesHeld = false); kept("a flush before the rules hold the new pans is not the SETs'")
        b.tailSent(6L, entriesHeld = true); b.tailDone(5L, ok = true); kept("sent is not landed: the SETs' flush in flight")
        b.tailDone(6L, ok = false); kept("that flush failed: the next one carries them")
        b.tailSent(7L, entriesHeld = true); b.tailsForgotten(); b.tailDone(7L, ok = true); kept("a new session forgets the flush in flight")
        b.tailSent(8L, entriesHeld = true); b.tailDone(8L, ok = true)
        b.releaseIfDue(p, acked = true, burstOpen = false)
        assertNull(list.previous, "released once the SETs landed")
    }

    /** Decisions AF.8: ONE re-stage per pace in all — the row owed longest first, the next one a whole pace later. */
    @Test
    fun oneReStagePerPaceInAllTheStalestFirst_AF8() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, false))
        val b = wm.damage.core.shell.MainBinding()
        val keys = { List(ms.rows) { rig.surface.rowKey(it) } }
        b.begin(ms, emptyList(), keys(), 0L)
        val tor = rows.indexOfFirst { it.id == "torrents" }; val mus = rows.indexOfFirst { it.id == "music" }
        rows[tor].s = rows[tor].s.copy(detail = "iso 9%"); rows[mus].s = rows[mus].s.copy(detail = "Low Tide · q 6/12")
        b.keysChanged(keys())
        b.changed(tor, 2_000L); b.changed(mus, 1_000L)                 // Music owed longer
        assertEquals(mus, b.nextOwed(10_000L), "the stalest first")
        val r = ms.restage(mus, rig.view, rig.kit, { _, f -> f() }) as MainStaging.Restage.Changed
        b.submitted(r, StagedRecord(MainStageKey(ms, 1000 + mus, "re-stage"), Gray8(1, 1), ByteArray(4)), 10_000L)
        b.releaseIfDue(b.patches.single(), acked = true, burstOpen = false)
        assertNull(b.nextOwed(14_000L), "the other row waits for the pace in all, not its own")
        assertEquals(tor, b.nextOwed(15_000L), "then the next")
    }

    /** A strip row's segment decodes to exactly its levels at every length from its natural one to well past the slack,
     *  and a length below the natural one is refused (the re-stage then asks for a full staging). */
    @Test
    fun aSegmentPaddedToItsSlotDecodesToItsLevels_DM2() {
        val rnd = java.util.Random(0xD2)
        for (trial in 0 until 40) {
            val w = 596; val h = 32
            val lv = ByteArray(w * h) { 0 }
            // runs of every class: single pixels, 16..255, 256 and more, a whole black row
            var i = rnd.nextInt(700)
            while (i < lv.size) { val n = 1 + rnd.nextInt(if (trial % 3 == 0) 20 else 400); val c = rnd.nextInt(16); for (k in i until minOf(lv.size, i + n)) lv[k] = c.toByte(); i += n + rnd.nextInt(300) }
            val natural = MainStaging.rleSize(lv, 0, lv.size)
            assertNull(MainStaging.rlePadded(lv, 0, lv.size, natural - 1), "trial $trial: one byte short of the natural $natural is refused")
            for (size in listOf(natural, natural + 1, natural + 3, MainStaging.slack(natural), natural * 3)) {
                val seg = MainStaging.rlePadded(lv, 0, lv.size, size) ?: throw AssertionError("trial $trial: no stream of $size B (natural $natural)")
                assertEquals(size, seg.size)
                assertTrue(Rle.decodeLevels(seg, lv.size).contentEquals(lv), "trial $trial at $size B: decodes to its levels")
            }
        }
    }

    /** The fixed rule (decisions V, AU.3; the `mainseq` probe keeps it, AW.4): the fit 15.66 + 0.156 ms per 1,000 px, P0
     *  the smallest period that holds it, T nearest 200 ms at P0, the period P0 with 10 % on it — P 46 (44.9 ms) T 5 at
     *  352, above the first trial's slowest step (41.4 ms). */
    @Test
    fun thePeriodAndStepsFollowTheMeasuredFitAtEachHeight_AU3() {
        val want = mapOf(288 to (39 to 6), 352 to (46 to 5), 416 to (52 to 4), 480 to (59 to 4))
        for (h in listOf(288, 352, 416, 480)) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true), motion = null)
            val pan = ms.pan(MainStaging.WHOLE, 1)
            val stepMs = MainStaging.STEP_MS_BASE + MainStaging.STEP_MS_PER_KPX * pan.areaPx / 1000.0
            assertEquals(rig.layout.rowsAbove to rig.layout.rowsBelow, pan.kA to pan.kB, "$h: the whole list moves")
            val p0 = kotlin.math.ceil(stepMs * 1024.0 / 1000.0).toInt()
            assertTrue(ms.period >= p0 * MainStaging.PERIOD_MARGIN && ms.period - 1 < p0 * MainStaging.PERIOD_MARGIN, "$h: the period P0 with the margin, rounded up")
            assertTrue(kotlin.math.abs(ms.t * p0 * 1000.0 / 1024 - 200) <= p0 * 1000.0 / 1024 / 2 + 0.01, "$h: T nearest 200 ms at P0")
            assertEquals(want[h], ms.period to ms.t, "$h: (P, T)")
            assertEquals(ms.t, ms.pans.first().program.T, "$h: every pan has the staging's T")
            assertEquals(MainStaging.PRESS_T, ms.press.program.T, "$h: the press keeps T = 3")
        }
    }

    /** Build ANIM (decisions AV, AW.1): the motion in force from the `Animations` rows at every height — Auto: the fitted
     *  period and the most steps that fit in the length (at least 3); a fixed count: that many steps, the period never
     *  under the fitted one; the defaults (300 ms since decisions AZ.7, Auto) give T 6 at P 46 at 352. The band's swap keeps its 2-of-5 share. */
    @Test
    fun theMotionFollowsTheAnimationsRowsAtEachHeight_ANIM() {
        val table = ArrayList<String>()
        for (h in listOf(288, 352, 416, 480)) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val region = CfwModes.mainRegion(CfwModes.CACHE2_MAX, true)
            val fixed = rig.staging(CfwModes.CACHE2_MAX, region, motion = null)
            val pFit = fixed.period
            for (len in ShellSettings.SCROLL_LENGTHS) for (f in ShellSettings.FRAME_COUNTS) {
                val ms = rig.staging(CfwModes.CACHE2_MAX, region, MainStaging.ScrollMotion(len, f))
                val lenTicks = len * 1024.0 / 1000.0
                if (f == 0) {
                    assertEquals(pFit, ms.period, "$h $len ms Auto: the fitted period")
                    assertEquals(maxOf(MainStaging.MIN_STEPS, kotlin.math.floor(lenTicks / pFit).toInt()), ms.t, "$h $len ms Auto: the steps that fit")
                } else {
                    assertEquals(f, ms.t, "$h $len ms $f frames: the count")
                    assertTrue(ms.period >= pFit, "$h $len ms $f frames: never under the fitted period")
                    assertEquals(maxOf(pFit, kotlin.math.ceil(lenTicks / f).toInt()).coerceIn(CfwModes.MOTION_PERIODS.first, CfwModes.MOTION_PERIODS.last), ms.period, "$h $len ms $f frames: the period")
                }
                assertEquals(ms.t, ms.pans.first().program.T, "$h $len $f: the pans' T")
                if (f == 0) table += "h$h $len ms Auto: T ${ms.t} at P ${ms.period} (${"%.1f".format(ms.period * 1000.0 / 1024)} ms) = ${"%.0f".format(ms.t * ms.period * 1000.0 / 1024)} ms"
            }
        }
        println("ANIM-table\n" + table.joinToString("\n"))
        val d = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows()).staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
        assertEquals(46 to 6, d.period to d.t, "the defaults at 352 (300 ms since decisions AZ.7): T 6 at P 46")
        assertEquals(4, MainStaging.swapOf(10), "the band's swap at T 10")
        assertEquals(2, MainStaging.swapOf(5), "the band's swap at T 5, as before")
    }

    /** A summary that grows past its slots' slack is not written in place: a full staging (a new mark) is owed. */
    @Test
    fun aRowThatOutgrowsItsSlotOwesAFullStaging_DM2() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
        val tmux = rows.indexOfFirst { it.id == "tmux" }
        assertEquals(MainStaging.Restage.Same, ms.restage(tmux, rig.view, rig.kit, { _, b -> b() }), "nothing changed: nothing to write")
        rows[tmux].s = DamageWindow.Summary("17 sessions across four hosts, two of them busy", detail = "build: running the whole battery since 14:02 with 3 failures", progress = 0.5, more = true)
        val r = ms.restage(tmux, rig.view, rig.kit, { _, b -> b() })
        assertTrue(r is MainStaging.Restage.Outgrown, "a row grown past its slack: $r")
        // a small change fits: Torrents' progress
        val tor = rows.indexOfFirst { it.id == "torrents" }
        rows[tor].s = rows[tor].s.copy(progress = 0.95, line = "3 active, 3.4 MB/s", detail = "debian-13.iso 95%")
        val r2 = ms.restage(tor, rig.view, rig.kit, { _, b -> b() })
        assertTrue(r2 is MainStaging.Restage.Changed, "a progress step is written in place: $r2")
        r2 as MainStaging.Restage.Changed
        println("DM2-restage torrents: ${r2.bytes} B in ${r2.writes.size} write(s): ${r2.writes.joinToString { "${it.what} ${it.bytes.size} B" }}")
        assertTrue(r2.writes.all { it.off % 4 == 0 && it.bytes.size % 4 == 0 }, "every write 4-aligned")
        val strip = r2.writes.filter { it.what.startsWith("strip") }
        assertEquals(1, strip.size, "Torrents shows once in the strip at 352 (no margin copy; B082: the wrap's rows are a record of their own)")
    }

    /** B082 (row slack, G4 §2c): a row that changes gets segment room once its picture outgrows its segment — the tail is
     *  laid out again ONCE with the changed row given the largest it has drawn and a quarter more — so its next change, a
     *  little larger again (past what plain slack holds, inside the room), writes that row's segments alone, not the
     *  strip's tail (13–16 KB on his glasses every paced 5 s). */
    @Test
    fun aChangingRowGetsRoomSoItsNextChangeIsItsSegmentAlone_B082() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val music = rows.indexOfFirst { it.id == "music" }
        val sm = rows[music] as MainLocalCheck.SampleRow
        // a short line to start from: the line is fitted to the row's width, so a long one has nowhere to grow
        val base = sm.s.copy(line = "Night")
        sm.s = base
        val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
        fun rleWith(line: String): Int {
            sm.s = base.copy(line = line)
            val g = MainStaging.paintStripRow(rig.view, rig.layout, music) { _, b -> b() }
            val lv = ByteArray(g.pix.size) { wm.damage.core.gfx.Pack.level(g.pix[it].toInt() and 0xFF).toByte() }
            return MainStaging.rleSize(lv, 0, lv.size)
        }
        val s0 = rleWith(base.line)
        val seg0 = ms.strip.segLen!![rig.layout.rowsAbove + music]
        // the first change: past its segment (the tail is laid out again once)
        val t1 = (1..60).map { base.line + " " + "W".repeat(it) }.first { rleWith(it) > seg0 }
        val s1 = rleWith(t1)
        val r1 = ms.restage(music, rig.view, rig.kit, { _, b -> b() })
        assertTrue(r1 is MainStaging.Restage.Changed && r1.relaid != null, "the first change outgrew its segment: $r1")
        r1 as MainStaging.Restage.Changed
        val tail = r1.writes.filter { it.what.startsWith("strip") }.sumOf { it.bytes.size }
        ms.apply(r1)
        r1.band?.let { ms.release(it.oldOff4) }   // the shell frees the old band slot at the switch's ack
        // the second change: larger than plain slack would hold, inside the room
        val room = MainStaging.roomFor(maxOf(s0, s1))
        val t2 = (1..80).map { t1 + "W".repeat(it) }.firstOrNull { rleWith(it).let { n -> n > MainStaging.segSlack(s1) && n <= room } }
        assertNotNull(t2, "a second change between the plain slack (${MainStaging.segSlack(s1)}) and the room ($room): " +
            (1..80).joinToString { "${rleWith(t1 + "W".repeat(it))}" })
        rleWith(t2!!)
        val r2 = ms.restage(music, rig.view, rig.kit, { _, b -> b() })
        assertTrue(r2 is MainStaging.Restage.Changed, "$r2")
        r2 as MainStaging.Restage.Changed
        val strip = r2.writes.filter { it.what.startsWith("strip") }
        println("B082-room music: first change $s0 → $s1 B (tail ${tail} B), second → ${rleWith(t2)} B: ${strip.joinToString { "${it.what} ${it.bytes.size} B" }}")
        assertTrue(r2.relaid == null && strip.none { "laid out again" in it.what || ".." in it.what }, "the second change wrote its segments alone: ${strip.map { it.what }}")
        assertTrue(strip.sumOf { it.bytes.size } < tail / 3, "its bytes (${strip.sumOf { it.bytes.size }}) far under the tail's ($tail)")
    }

    /** B082 (row slack): the room a row earned by changing is carried by its window into the next staging (a font set,
     *  a motion change, an outgrown tail all stage again): that row's segments start with room there, the others not. */
    @Test
    fun aChangedRowsRoomIsCarriedIntoTheNextStaging_B082() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
        val b = MainBinding()
        val ids = rows.map { it.id }
        b.begin(ms, emptyList(), List(ms.rows) { null }, 0L, ids)
        val music = rows.indexOfFirst { it.id == "music" }
        (rows[music] as MainLocalCheck.SampleRow).s = rows[music].summary().copy(line = "Night Drive - Low Tide (live, 1998)")
        assertTrue(ms.restage(music, rig.view, rig.kit, { _, bb -> bb() }) is MainStaging.Restage.Changed)
        b.drop()
        val room = b.roomFor(ids)
        assertEquals(MainStaging.roomFor(ms.seenMax[music]), room[music], "Music's room carried by its window")
        assertTrue(room.withIndex().all { (i, v) -> i == music || v == 0 }, "no other row given room: ${room.toList()}")
        val region = CfwModes.mainRegion(CfwModes.CACHE2_MAX, true)
        val ms2 = MainStaging.build(rig.layout, rig.view, rig.kit, rig.listD, rig.bandD, CfwModes.CACHE2_MAX,
            region.first, region.last + 1, widths = listOf(MainStaging.WHOLE))
        val ms3 = MainStaging.assemble(MainStaging.Paint(rig.layout, rig.view, rig.kit).also { p -> while (!p.done) p.step { _, bb -> bb() } },
            rig.listD, rig.bandD, CfwModes.CACHE2_MAX, region.first, region.last + 1, listOf(MainStaging.WHOLE), room = room)
        val s = rig.layout.rowsAbove + music
        assertTrue(ms3.strip.segLen!![s] >= room[music] && ms3.strip.segLen!![s] > ms2.strip.segLen!![s], "Music's segment starts with its room: ${ms3.strip.segLen!![s]} vs ${ms2.strip.segLen!![s]}")
    }

    /** B082: room is given only where the region holds it — a room past the region leaves the staging as it was without
     *  it, never a staging refused (Main unbound) for the sake of the room. */
    @Test
    fun aRoomTheRegionCannotHoldIsLeftOut_B082() {
        val rig = MainLocalCheck.MainRig(BoxFace, 352, MainLocalCheck.sampleRows())
        val region = CfwModes.mainRegion(CfwModes.CACHE2_MAX, true)
        val paint = MainStaging.Paint(rig.layout, rig.view, rig.kit).also { p -> while (!p.done) p.step { _, bb -> bb() } }
        val plain = MainStaging.assemble(paint, rig.listD, rig.bandD, CfwModes.CACHE2_MAX, region.first, region.last + 1, listOf(MainStaging.WHOLE))
        val ms = MainStaging.assemble(paint, rig.listD, rig.bandD, CfwModes.CACHE2_MAX, region.first, region.last + 1, listOf(MainStaging.WHOLE),
            room = IntArray(plain.rows) { 8_000 })
        assertTrue(ms.strip.segLen!!.contentEquals(plain.strip.segLen!!), "the strip as it was: ${ms.strip.segLen!!.toList()}")
        assertEquals(plain.wrap?.slot, ms.wrap?.slot, "the room goes first: the wrap's record kept as it was")
    }

    /** B082 (found by `--local-check` with the desktop's font: 288 at 160 KiB passed its region by 1,328 B): a region
     *  that cannot hold the wrap's record with its slack takes it tight, and one that cannot hold it at all leaves it
     *  out — the strips as they were, never a staging refused (Main unbound) for the wrap's sake. */
    @Test
    fun aWrapRecordTheRegionCannotHoldIsLaidTightThenLeftOut_B082() {
        val rig = MainLocalCheck.MainRig(BoxFace, 288, MainLocalCheck.sampleRows())
        val region = CfwModes.mainRegion(CfwModes.CACHE2_MAX, true)
        val paint = MainStaging.Paint(rig.layout, rig.view, rig.kit).also { p -> while (!p.done) p.step { _, bb -> bb() } }
        fun at(end: Int) = MainStaging.assemble(paint, rig.listD, rig.bandD, CfwModes.CACHE2_MAX, region.first, end, listOf(MainStaging.WHOLE))
        val full = at(region.last + 1)
        val wp = assertNotNull(full.wrap, "Main wraps at 288 with eight rows")
        val extra = wp.slot - ((wp.encoded.size + 3) and 3.inv())
        assertTrue(extra > 0, "the wrap's slot holds an eighth more: $extra B")
        val tight = at(full.end - extra / 2)
        assertTrue(tight.wrap != null && tight.wrap!!.slot == (tight.wrap!!.encoded.size + 3) and 3.inv(), "laid tight: ${tight.wrap?.slot}")
        val none = at(full.end - wp.slot)
        assertTrue(none.wrap == null && !none.wraps, "left out")
        assertTrue(none.strip.segLen!!.contentEquals(full.strip.segLen!!), "the strip as it was")
    }

    /** B082 (the wrap's own record): a changed row that the wrap's record shows is written there too — its segment in the
     *  record's own slot, beside the strip's — and after the booking the record's row is the row's new picture. */
    @Test
    fun aRestageWritesTheRowIntoTheWrapRecordToo_B082() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
        val wp = assertNotNull(ms.wrap, "Main wraps at 352 with eight rows")
        val tor = rows.indexOfFirst { it.id == "torrents" }
        val l = rig.layout
        val j = (0 until l.rowsAbove + l.rowsBelow + 2).first { (ms.rows - 1 - l.rowsAbove + it).mod(ms.rows) == tor }
        (rows[tor] as MainLocalCheck.SampleRow).s = rows[tor].summary().copy(line = "3 active, 3.4 MB/s", progress = 0.95)
        val r = ms.restage(tor, rig.view, rig.kit, { _, b -> b() })
        assertTrue(r is MainStaging.Restage.Changed, "$r")
        r as MainStaging.Restage.Changed
        assertTrue(r.wrapWrites.any { it.what.startsWith("wrap row $j") && it.off >= wp.off && it.off < wp.off + wp.slot }, "the wrap record's row $j written: ${r.wrapWrites.map { it.what }}")
        ms.apply(r)
        val g = MainStaging.paintStripRow(rig.view, l, tor) { _, b -> b() }
        for (y in 0 until l.rowH) for (x in 0 until g.w) if (wp.raw[x, j * l.rowH + y] != g[x, y]) throw AssertionError("wrap row $j is not the new picture at ($x,$y)")
    }

    /** Decisions B.4: on a 256 KiB session with BIND in force the atlas lays out over 128 KiB, under Main's own region;
     *  everywhere else (160 KiB, or no BIND: ladder stages 0–3) as before. */
    @Test
    fun theAtlasLaysOutUnderMainsRegionOnlyAt256KiBWithBind_DM2() {
        fun st(kib: Int, bind: Boolean) = LinkState(damageContract = 2, damageFeatures = 0x5ff, cacheSize = kib * 1024,
            flagsInForce = DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE or (if (bind) DamageMsg.FLAG_BIND else 0))
        // AD.2, AU.1: Main's own region is 128 KiB at 256 (a second strip), the stage reserve 32 KiB above it, the atlas
        // keeps the 96 KiB it has at 160
        assertEquals(96 * 1024, st(256, true).atlasCapacity)
        assertEquals(192 * 1024, st(256, false).atlasCapacity)
        assertEquals(96 * 1024, st(160, true).atlasCapacity)
        assertEquals(96 * 1024, st(160, false).atlasCapacity)
        assertEquals(96 * 1024 until 224 * 1024, CfwModes.mainRegion(256 * 1024, true))
        assertEquals(32 * 1024, CfwModes.stageReserve(256 * 1024, true))
        assertEquals(64 * 1024, CfwModes.stageReserve(256 * 1024, false))
        assertEquals(64 * 1024, CfwModes.stageReserve(160 * 1024, true))
        assertEquals(96 * 1024 until 160 * 1024, CfwModes.mainRegion(160 * 1024, true))
    }

    /** A document's strips take the shared reserve (Main's records placed in the ring at 160 KiB go), never Main's own
     *  region (its records outside the ring at 256 KiB stay). */
    @Test
    fun aDocumentsStripsTakeTheSharedReserveNeverMainsOwnRegion_DM2() {
        val stg = Staging()
        stg.ring = StageRing(192 * 1024, 256 * 1024)
        val ms = Any()
        val own = StagedRecord(MainStageKey(ms, 0, "strip"), Gray8(1, 1), ByteArray(8)).also { it.off = 128 * 1024 }
        stg.records[own.key] = own
        val shared = StagedRecord(MainStageKey(ms, 1, "band 0"), Gray8(1, 1), ByteArray(8))
        assertTrue(stg.ring!!.placeAt(shared, 192 * 1024) != null)
        stg.records[shared.key] = shared
        val doc = Any()
        val strip = StagedRecord(StageKey(doc, 0, 3, 24, 300), Gray8(1, 1), ByteArray(8))
        stg.records[strip.key] = strip
        val gone = stg.retain(doc, 24, 300)
        assertEquals(listOf(shared), gone, "the shared reserve's record of Main's goes, Main's own region's stays")
        assertTrue(stg.records.containsKey(own.key) && stg.records.containsKey(strip.key))
    }

    /** The binding's books for decisions X: a band move makes a new list with the same mark whose `previous` names the
     *  old slot until the switch is acknowledged, then the old slot is free; a row-form change withdraws the list until
     *  its write is submitted. */
    @Test
    fun aBandSwitchAndAWithdrawalAreKeptInTheBindingsBooks_DM2() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
        val b = wm.damage.core.shell.MainBinding()
        val keys = { List(ms.rows) { rig.surface.rowKey(it) } }
        b.begin(ms, emptyList(), keys(), 0L)
        val first = b.list!!
        val tor = rows.indexOfFirst { it.id == "torrents" }
        rows[tor].s = rows[tor].s.copy(detail = "iso 9%")
        b.keysChanged(keys())
        assertTrue(b.stale(tor) && b.staleRows == listOf(tor))
        val r = ms.restage(tor, rig.view, rig.kit, { _, f -> f() }) as MainStaging.Restage.Changed
        val mv = r.band!!
        assertTrue(r.strip.isEmpty(), "the detail is the band form's alone")
        val rec = StagedRecord(MainStageKey(ms, 1000 + tor, "re-stage"), Gray8(1, 1), ByteArray(4))
        b.changed(tor); b.submitted(r, rec, 1L)
        val second = b.list!!
        assertTrue(second !== first && second.mark == first.mark && second.previous === first, "a new list, the same mark, the old one as previous")
        assertEquals(mv.off / 4, second.items!![tor]); assertEquals(mv.oldOff4, first.items!![tor])
        assertTrue(ms.free.isEmpty() && mv.oldOff4 in ms.retired, "the spare taken, the old slot retired")
        b.switchAcked(b.patches.single())
        assertNull(second.previous, "previous forgotten once acknowledged")
        assertEquals(setOf(mv.oldOff4 * 4), ms.free.keys, "the old slot is the spare now")
        // a row-form change: withdrawn until its write is submitted
        rows[tor].s = rows[tor].s.copy(line = "3 active, 14.0 MB/s")
        val r2 = ms.restage(tor, rig.view, rig.kit, { _, f -> f() }) as MainStaging.Restage.Changed
        assertTrue(r2.strip.isNotEmpty())
        b.withdraw(tor, 7L)
        assertEquals(tor, b.withdrawnFor)
        b.patchCounted(b.patches.single())
        b.submitted(r2, rec, 2L)
        assertNull(b.withdrawnFor, "offered again at the write's submission")
    }

    /** A row form grown past its segment (a longer line) lays the strip's tail out again inside the strip's own slot — one
     *  write from that segment on, the record and its place kept — and the new strip decodes to Main's rows. */
    @Test
    fun aRowFormGrownPastItsSegmentReLaysTheStripsTail_DM2() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
        val tmux = rows.indexOfFirst { it.id == "tmux" }
        val off0 = ms.strip.off
        rows[tmux].s = rows[tmux].s.copy(line = "3 sessions · 2 attached · 1 idle")
        val r = ms.restage(tmux, rig.view, rig.kit, { _, f -> f() })
        assertTrue(r is MainStaging.Restage.Changed && r.relaid != null, "re-laid in the strip's slot: $r")
        r as MainStaging.Restage.Changed
        val tail = r.strip.single()
        assertTrue(tail.off >= off0 && tail.off + tail.bytes.size <= off0 + ms.strip.slot, "the tail inside the strip's slot")
        ms.apply(r)
        assertEquals(off0, ms.strip.off)
        // the strip as the glasses hold it now decodes to the rows as painted
        val enc = ms.strip.encoded
        val px = Rle.decodeLevels(enc.copyOfRange(4, 4 + ms.strip.segLen!!.sum()), ms.strip.raw.w * ms.strip.raw.h)
        assertTrue(px.contentEquals(ms.strip.levels), "the re-laid strip decodes to its rows")
        println("DM2-relaid: ${tail.what} ${tail.bytes.size} B (strip slot ${ms.strip.slot} B)")
    }
}
