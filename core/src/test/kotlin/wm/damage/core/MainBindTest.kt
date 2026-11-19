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
}
