package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.geom.FidAllocator
import wm.damage.core.geom.FidTracker
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect

/** The same fixtures tools/lint.py --selftest fires — the two gates must agree. */
class GeometryTest {
    private fun fires(rule: String, findings: List<String>) =
        assertTrue(findings.any { rule in it }, "$rule should fire, got $findings")

    @Test
    fun rulesFire() {
        fires("GEO001", Geometry.checkRect(Rect(250, 100, 100, 50)))
        fires("GEO001", Geometry.checkRect(Rect(100, 33, 100, 50)))
        fires("GEO002", Geometry.checkRect(Rect(600, 400, 100, 100)))
        fires("GEO003", Geometry.checkRect(Rect(0, 0, 0, 10)))
        fires("GEO004", Geometry.checkStereoPair(Rect(0, 34, 608, 416), Rect(32, 34, 604, 416)))
        fires("GEO005", Geometry.checkStereoPair(Rect(0, 34, 600, 416), Rect(0, 36, 600, 416)))
        fires("GEO008", Geometry.checkCells(mapOf("a" to Rect(0, 0, 100, 32)), span = Rect(0, 0, 640, 32)))
        fires("BUD001", Geometry.checkBatch(List(6) { Rect(0, 34, 8, 8) }, window = 3))
        fires("BUD002", Geometry.checkBatch(emptyList(), payload = Geometry.MODE8_MAX + 1))
        fires("BUD003", Geometry.checkFrameSize(1200, Geometry.FrameKind.LAYOUT))
        fires("BUD004", Geometry.checkFrameSize(4096, Geometry.FrameKind.IMAGE))
        fires("BUD005", Geometry.checkInk(50, 100, 0.15, "x"))
    }

    @Test
    fun fidRulesFire() {
        val t = FidTracker()
        fires("FID004", t.delta(1))
        val t2 = FidTracker(); t2.keyframe(); t2.delta(1)
        fires("FID002", t2.delta(5))
        val t3 = FidTracker(); t3.keyframe(); t3.delta(7)
        fires("FID001", t3.delta(7))
        val t4 = FidTracker(); t4.keyframe()
        fires("FID003", t4.delta(0xFFFF))
    }

    @Test
    fun validGeometryStaysSilent() {
        assertEquals(emptyList(), Geometry.checkRect(Rect(16, 34, 608, 416)))
        assertEquals(
            emptyList<String>(),
            Geometry.checkBatch(listOf(Rect(0, 0, 640, 32), Rect(0, 452, 640, 28)), window = 3),
        )
    }

    @Test
    fun fidWrapIsWhitelisted() {
        val t = FidTracker()
        t.keyframe()
        assertEquals(emptyList(), t.delta(Geometry.FID_MAX))
        // the deliberate wrap 0xFFFE -> 1 must not report FID002 host-side
        assertEquals(emptyList(), t.delta(Geometry.FID_MIN))
    }

    @Test
    fun allocatorWraps() {
        val a = FidAllocator(Geometry.FID_MAX - 1)
        assertEquals(Geometry.FID_MAX - 1, a.take())
        assertEquals(Geometry.FID_MAX, a.take())
        assertTrue(a.wrapPending)
        assertEquals(Geometry.FID_MIN, a.take())
    }

    @Test
    fun mode8CapMatchesFirmware() {
        // zlib_glue.c: bmp_max = 118 + ((((640+1)>>1)+3)&~3)*480 = 118 + 320*480
        assertEquals(118 + 320 * 480, Geometry.MODE8_MAX)
    }

    @Test
    fun layoutTilesAndAlignsAcrossSafeRects() {
        val safes = listOf(
            Rect(0, 0, 640, 480),
            Rect(0, 96, 640, 288),          // Faceclaw's default band, centred-ish
            Rect(0, 0, 640, 288),
            Rect(0, 192, 640, 288),
            Rect(16, 40, 608, 400),
        )
        for (safe in safes) {
            val l = Layout(safe)
            val errs = Geometry.checkCells(
                mapOf("title" to l.titleCell, "battery" to l.batteryCell, "clock" to l.clockCell),
                span = l.topBar,
            ) + Geometry.checkCells(
                mapOf("op" to l.opCell, "status" to l.statusCell, "thru" to l.thruCell,
                    "tape" to l.tapeCell, "link" to l.linkCell),
                span = l.statusBar,
            ) + Geometry.checkRect(l.content, "content") +
                Geometry.checkRect(l.lens, "lens") +
                Geometry.checkRect(l.switcherPanel, "switcher") +
                Geometry.checkRect(l.notificationMax, "notification")
            assertEquals(emptyList(), errs, "layout for safe $safe")
            // the lens centre sits on the content-area centre axis (§4.2)
            assertTrue(kotlin.math.abs((l.lens.y + l.lens.h / 2) - (l.content.y + l.content.h / 2)) <= 1)
        }
    }

    @Test
    fun defaultLayoutMatchesDesignTable() {
        val l = Layout()
        assertEquals(Rect(0, 0, 384, 32), l.titleCell)
        assertEquals(Rect(384, 0, 176, 32), l.batteryCell)
        assertEquals(Rect(560, 0, 80, 32), l.clockCell)
        assertEquals(Rect(16, 34, 608, 416), l.content)
        assertEquals(Rect(0, 450, 640, 2), l.bottomDivider)
        assertEquals(Rect(0, 452, 640, 28), l.statusBar)
        assertEquals(Rect(16, 210, 608, 64), l.lens)          // §4.2: y 210, h 64
        assertEquals(Rect(200, 154, 240, 176), l.switcherPanel) // §4.3
        assertEquals(Rect(196, 190, 248, 104), l.notificationMax) // §4.5
        // §4.5b rebalanced status cells
        assertEquals(160, l.opCell.w)
        assertEquals(Rect(420, 452, 100, 28), l.tapeCell)
    }
}
