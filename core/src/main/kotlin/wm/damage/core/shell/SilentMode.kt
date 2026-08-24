package wm.damage.core.shell

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Icons

/**
 * Silent mode — everything hidden but a small dim analog clock in the top-right
 * corner (DESIGN.md §1.5): 80x80, hour + minute hands only, NO second hand,
 * ever; the minute hand SNAPS once a minute — 60 flushes/hour, the cheapest
 * possible "alive". All input is swallowed except double-tap (back to Main).
 * This completes the gloves fix: a stray ring long-press lands here and does
 * nothing.
 */
object SilentMode {
    /** The clock's box, safe-rect relative (536,48,80,80 at the full panel). */
    fun clockRect(l: Layout): Rect =
        Rect(Geometry.snapX(l.safe.right - 104), Geometry.snapY(l.safe.y + 48), 80, 80)

    /** Paint the whole silent surface (a keyframe-ish repaint on entry). */
    fun paintAll(g: Gray8, l: Layout, hh: Int, mm: Int) {
        g.fillRect(Rect(0, 0, g.w, g.h), 0)
        paintClock(g, l, hh, mm)
    }

    /** Paint just the clock box (the minute tick's damage). */
    fun paintClock(g: Gray8, l: Layout, hh: Int, mm: Int) {
        val r = clockRect(l)
        g.fillRect(r, 0)
        Icons.analogClock(g, r.x + r.w / 2, r.y + r.h / 2, 34, hh, mm)
    }
}
