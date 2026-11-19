package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.gfx.Rle
import wm.damage.core.motion.BoxFace
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.MainLocalCheck
import wm.damage.core.shell.MainStaging
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
     * draws nothing; a row re-staged in place keeps the mark and a notch onto it lands on its new paint.
     */
    @Test
    fun theLocalCheckHoldsAtEveryHeightOnBothCaches_DM2() {
        val r = MainLocalCheck.run(BoxFace, frames = false)
        for (line in r.table) println("DM2-staging $line")
        for (c in r.cases) for (l in c.lines.take(2)) println("DM2-case ${c.name}: $l")
        assertTrue(r.failures.isEmpty(), "failures:\n" + r.failures.take(40).joinToString("\n"))
        assertEquals(4 * 2 * 5, r.cases.size, "every height and cache ran its five cases")
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

    /** The period and steps per height (decisions V: the fit 14.4 + 0.156 ms per 1,000 px, the smallest period at least
     *  that plus 2 ms, T nearest 200 ms), for the report's table. */
    @Test
    fun thePeriodAndStepsFollowTheMeasuredFitAtEachHeight_DM2() {
        for (h in listOf(288, 352, 416, 480)) {
            val rig = MainLocalCheck.MainRig(BoxFace, h, MainLocalCheck.sampleRows())
            val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
            val pan = ms.pan(MainStaging.WHOLE, 1)
            val stepMs = MainStaging.STEP_MS_BASE + MainStaging.STEP_MS_PER_KPX * pan.areaPx / 1000.0
            println("DM2-timing h=$h: ${pan.kA}+${pan.kB} rows, ${pan.areaPx} px a step, modeled ${"%.1f".format(stepMs)} ms, P ${ms.period} (${"%.1f".format(ms.period * 1000.0 / 1024)} ms) T ${ms.t} (${"%.0f".format(ms.t * ms.period * 1000.0 / 1024)} ms)")
            assertEquals(rig.layout.rowsAbove to rig.layout.rowsBelow, pan.kA to pan.kB, "$h: the whole list moves")
            assertTrue(ms.period * 1000.0 / 1024 >= stepMs + MainStaging.STEP_MARGIN_MS, "$h: the period holds a step and the margin")
            assertTrue((ms.period - 1) * 1000.0 / 1024 < stepMs + MainStaging.STEP_MARGIN_MS, "$h: the smallest such period")
            assertTrue(kotlin.math.abs(ms.t * ms.period * 1000.0 / 1024 - 200) <= ms.period * 1000.0 / 1024 / 2 + 0.01, "$h: T nearest 200 ms")
            assertEquals(ms.t, ms.pans.first().program.T, "$h: every pan has the staging's T")
            assertEquals(MainStaging.PRESS_T, ms.press.program.T, "$h: the press keeps T = 3")
        }
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
        assertEquals(1, strip.size, "Torrents shows once in the strip at 352 (no margin copy)")
    }

    /** Decisions B.4: on a 256 KiB session with BIND in force the atlas lays out over 128 KiB, under Main's own region;
     *  everywhere else (160 KiB, or no BIND: ladder stages 0–3) as before. */
    @Test
    fun theAtlasLaysOutUnderMainsRegionOnlyAt256KiBWithBind_DM2() {
        fun st(kib: Int, bind: Boolean) = LinkState(damageContract = 2, damageFeatures = 0x5ff, cacheSize = kib * 1024,
            flagsInForce = DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE or (if (bind) DamageMsg.FLAG_BIND else 0))
        assertEquals(128 * 1024, st(256, true).atlasCapacity)
        assertEquals(192 * 1024, st(256, false).atlasCapacity)
        assertEquals(96 * 1024, st(160, true).atlasCapacity)
        assertEquals(96 * 1024, st(160, false).atlasCapacity)
        assertEquals(128 * 1024 until 192 * 1024, CfwModes.mainRegion(256 * 1024, true))
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

    /** What the rules read for `snapback cause=restaged` ([wm.damage.core.local.BoundList.staleAt]): a row whose key
     *  changed is stale for every fire that draws it — every notch (the whole list moves) and the press at that row, not
     *  the press at another — until its re-stage is submitted. */
    @Test
    fun aStaleRowIsSaidForTheFiresThatDrawIt_DM2() {
        val rows = MainLocalCheck.sampleRows()
        val rig = MainLocalCheck.MainRig(BoxFace, 352, rows)
        val ms = rig.staging(CfwModes.CACHE2_MAX, CfwModes.mainRegion(CfwModes.CACHE2_MAX, true))
        val b = wm.damage.core.shell.MainBinding()
        val keys = { List(ms.rows) { rig.surface.rowKey(it) } }
        b.begin(ms, emptyList(), keys(), 0L)
        val list = b.list!!
        val down = list.entryFor(CfwModes.EV_UPROLL)!!; val tap = list.entryFor(CfwModes.EV_TAP)!!
        assertTrue((0 until ms.rows).none { list.staleAt(down, it) || list.staleAt(tap, it) }, "nothing stale at first")
        rows[3].s = rows[3].s.copy(progress = 0.95)
        b.keysChanged(keys())
        assertTrue(b.stale(3) && b.staleRows == listOf(3))
        assertTrue((1 until ms.rows).all { list.staleAt(down, it) }, "every notch draws the whole list, row 3 with it")
        assertTrue(list.staleAt(tap, 3) && !list.staleAt(tap, 2), "the press draws its own row only")
        val r = ms.restage(3, rig.view, rig.kit, { _, f -> f() }) as MainStaging.Restage.Changed
        b.changed(3)
        b.submitted(r, StagedRecord(MainStageKey(ms, 1003, "row 3's re-stage"), Gray8(1, 1), ByteArray(4)), 1L)
        assertTrue(!b.stale(3) && (0 until ms.rows).none { list.staleAt(down, it) }, "current once its re-stage is submitted")
    }
}
