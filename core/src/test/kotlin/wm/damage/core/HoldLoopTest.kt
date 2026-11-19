package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8

/**
 * Decisions AQ.1 (the focused read FX's B1, its cases turned into pins): the compositor's hold loop ends on Main's real
 * geometry — the list's tracks at disparity 8 and its band at 4 (his Depth 8), whose two lens rects split across the
 * areas' edges at the rail and the left margin — when a sub planned from one lens's dirty cells meets the OTHER lens's
 * held areas. Each assemble runs on its own daemon thread joined for a bounded time, so a loop that does not end fails
 * the test instead of stopping it; a throw (the loop's backstop) fails it too. After the wait, every cell reaches the
 * lenses: nothing left dirty on either.
 *
 * Context for the reader: Adam's own glasses on his own display firmware; this is the phone's compositor, tested
 * against its own model, nothing contacts a device.
 */
class HoldLoopTest {
    private val l = Layout()
    private val listD = 8
    private val bandD = 4
    private val cx = l.content.x
    private val rowW = l.content.w - Layout.RAIL_W
    private val full = Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)

    /** Main's three tracks per lens as `MainStaging.assemble` lays them out. */
    private fun areas(): List<Pair<Rect, Rect>> {
        val kA = l.rowsAbove; val kB = l.rowsBelow
        val bandW = rowW + kotlin.math.abs(listD - bandD)
        return listOf(
            Rect(cx - listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH) to Rect(cx + listD, l.lens.y - kA * l.rowH, rowW, kA * l.rowH),
            Rect(cx - listD, l.lens.bottom, rowW, kB * l.rowH) to Rect(cx + listD, l.lens.bottom, rowW, kB * l.rowH),
            Rect(cx - maxOf(listD, bandD), l.lens.y, bandW, l.lens.h) to Rect(cx + minOf(listD, bandD), l.lens.y, bandW, l.lens.h),
        )
    }

    private fun mainPlanes(contentD: Int = listD, lensD: Int? = bandD) = listOfNotNull(
        Compositor.PlaneRegion(l.content, contentD), lensD?.let { Compositor.PlaneRegion(l.lens, it) })

    /** Main-like content: rows of text-like bars, the band with its rules, the rail with its track and thumb at [thumbY]. */
    private fun paintMain(g: Gray8, seed: Int, thumbY: Int) {
        g.fillRect(l.content, 0)
        var y = l.lens.y - l.rowsAbove * l.rowH
        var i = 0
        while (y + l.rowH <= l.content.bottom) {
            if (y < l.lens.y || y >= l.lens.bottom) g.fillRect(cx + 40, y + 10, 80 + ((i + seed) % 5) * 40, 10, 9 * 17)
            y += l.rowH; i++
        }
        g.fillRect(l.lens, 0)
        g.fillRect(l.lens.x, l.lens.y, l.lens.w - Layout.RAIL_W, 2, 5 * 17)
        g.fillRect(l.lens.x, l.lens.bottom - 2, l.lens.w - Layout.RAIL_W, 2, 5 * 17)
        g.fillRect(cx + 72, l.lens.y + 10, 200 + (seed % 3) * 60, 14, 15 * 17)
        g.fillRect(l.rail, 0)
        g.fillRect(l.rail.x + 4, l.rail.y, 4, l.rail.h, 3 * 17)
        g.fillRect(l.rail.x + 4, thumbY, 4, 40, 12 * 17)
    }

    /** A compositor with Main on both lenses, every cell sent. */
    private fun settled(): Compositor {
        val c = Compositor()
        c.v2 = true
        c.planes = mainPlanes()
        paintMain(c.composed, 0, l.lens.y - 60)
        var n = 0
        while (c.assembleFlush(Geometry.rectBudget(1)) != null) assertTrue(++n <= 30, "Main settles")
        return c
    }

    private fun endPlan(untilMs: Long) = Compositor.EndPlan(0, areas(), { left: Boolean, g: Gray8 ->
        for ((al, ar) in areas()) g.fillRect(if (left) al else ar, 2 * 17) }, untilMs = untilMs)

    /** [block] on a daemon thread joined for [ms]: it must end, and not by a throw. */
    private fun <T> bounded(what: String, c: Compositor, ms: Long = 10_000, block: () -> T): T {
        var err: Throwable? = null
        var out: T? = null
        val e0 = c.epoch
        val t = Thread({ try { out = block() } catch (x: Throwable) { err = x } }, "hold-loop").apply { isDaemon = true; start() }
        t.join(ms)
        assertTrue(!t.isAlive, "$what: still running after $ms ms (epoch $e0 -> ${c.epoch}: rounds rolled back)")
        err?.let { throw AssertionError("$what: threw $it", it) }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    /** The wait released at its end and every assemble after it taken: nothing dirty on either lens. */
    private fun drained(what: String, c: Compositor) {
        c.releaseDeferred(Long.MAX_VALUE)
        bounded("$what: the assembles after the wait", c) { var n = 0; while (c.assembleFlush(Geometry.rectBudget(3)) != null) assertTrue(++n <= 40, "$what: settles") }
        assertEquals(0, c.dirtyCellsForTest(true, listOf(full)).size + c.dirtyCellsForTest(false, listOf(full)).size,
            "$what: every cell reached both lenses")
    }

    private fun cellIdx(c: Compositor, x: Int, y: Int) = (y / Geometry.Y_STEP) * c.cellsWForTest + x / Geometry.X_STEP

    /** The control: a COMMIT with no one-lens dirt ends, and the lenses get everything after the wait. */
    @Test
    fun aCommitWithNoOneLensDirtEnds_AQ1() {
        val c = settled()
        paintMain(c.composed, 1, l.lens.y - 60); c.damage(l.content)
        bounded("a COMMIT", c) { c.assembleWithEnds(Geometry.rectBudget(3), listOf(endPlan(1_000)), 0) }
        drained("the control", c)
    }

    /** FX s1: a right-lens cell of the left margin (depth 0) in a track's rows is unknown at a COMMIT: its delta writes
     *  the same rect on the left lens, inside the left areas (they start at x 8). */
    @Test
    fun aRightMarginCellUnknownAtACommitEnds_AQ1() {
        val c = settled()
        val y = l.lens.y - 2 * l.rowH
        c.unknownForTest(false)[cellIdx(c, 8, y)] = true
        c.damage(Rect(8, y, 4, 2))
        paintMain(c.composed, 1, l.lens.y - 60); c.damage(l.content)
        bounded("a right margin cell (8,$y) unknown, a COMMIT", c) { c.assembleWithEnds(Geometry.rectBudget(3), listOf(endPlan(1_000)), 0) }
        drained("s1", c)
    }

    /** FX s2: a flush with the band's text and the rail thumb (in the band's rows) merged into one rect is rolled back (it
     *  failed); the next assemble is a COMMIT's. */
    @Test
    fun aRolledBackMergedFlushThenACommitEnds_AQ1() {
        val c = settled()
        paintMain(c.composed, 1, l.lens.y + 4); c.damage(l.content)
        val a = c.assembleFlush(Geometry.rectBudget(16))!!
        assertTrue(a.touched.any { it.left && it.rect.x <= 608 && it.rect.right >= 612 && it.rect.y < l.lens.bottom && it.rect.bottom > l.lens.y },
            "the merged flush covers the rail's first columns at the band's depth on the left (else this proves nothing)")
        c.rollback(a)
        paintMain(c.composed, 2, l.lens.y + 4); c.damage(l.lens)
        bounded("a rolled-back merged flush, then a COMMIT", c) { c.assembleWithEnds(Geometry.rectBudget(3), listOf(endPlan(1_000)), 0) }
        drained("s2", c)
    }

    /** FX s3b: during the wait the band plane goes (a window whose first view is not a list, the menu, the keyboard, the
     *  switcher): the next assemble holds Main's areas while the rail's columns change depth. */
    @Test
    fun theBandPlaneGoingDuringTheWaitEnds_AQ1() {
        val c = settled()
        paintMain(c.composed, 1, l.lens.y - 60); c.damage(l.content)
        bounded("a COMMIT (its wait to 1000)", c) { c.assembleWithEnds(Geometry.rectBudget(3), listOf(endPlan(1_000)), 0) }
        assertTrue(c.deferring, "deferring until the motion's end")
        c.planes = mainPlanes(8, null)
        paintMain(c.composed, 3, l.lens.y + 20); c.damageAll()
        bounded("the next assemble during the wait (planes 8, no band)", c) { c.assembleFlush(Geometry.rectBudget(3)) }
        drained("s3b", c)
    }

    /** FX s3: during the wait a window with its own Depth (12; its band at 8) opens over Main. */
    @Test
    fun aWindowWithItsOwnDepthDuringTheWaitEnds_AQ1() {
        val c = settled()
        paintMain(c.composed, 1, l.lens.y - 60); c.damage(l.content)
        bounded("a COMMIT (its wait to 1000)", c) { c.assembleWithEnds(Geometry.rectBudget(3), listOf(endPlan(1_000)), 0) }
        c.planes = mainPlanes(12, 8)
        paintMain(c.composed, 3, l.lens.y + 20); c.damageAll()
        bounded("the window's first assemble during the wait (planes 12/8)", c) { c.assembleFlush(Geometry.rectBudget(3)) }
        drained("s3", c)
    }
}
