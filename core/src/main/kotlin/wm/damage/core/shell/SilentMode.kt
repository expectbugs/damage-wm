package wm.damage.core.shell

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Icons

/**
 * Silent mode — everything hidden but a small seven-segment digital clock
 * flush to the top-right (DESIGN.md §1.5, revised 2026-08-31): drawn LED-style
 * digits, 12-hour, minutes only — it repaints once a minute, 60 flushes/hour,
 * the cheapest possible "alive". All input is swallowed except double-tap
 * (back to Main). This completes the gloves fix: a stray ring long-press
 * lands here and does nothing.
 */
object SilentMode {
    /** The clock's box, safe-rect relative: DIGITAL, flush to the TOP-RIGHT
     *  (Adam, 2026-08-31, second revision — "all the way up and all the way
     *  right, and forget analog"): a drawn seven-segment readout in a
     *  144x48 box whose top and right edges touch the safe rect; the digits
     *  sit 2 px inside it. */
    fun clockRect(l: Layout): Rect =
        Rect(Geometry.snapX(l.safe.right - 144), Geometry.snapY(l.safe.y), 144, 48)

    /** Paint the whole silent surface (a keyframe-ish repaint on entry). */
    fun paintAll(g: Gray8, l: Layout, hh: Int, mm: Int) {
        g.fillRect(Rect(0, 0, g.w, g.h), 0)
        paintClock(g, l, hh, mm)
    }

    /** Paint just the clock box (the minute tick's damage). */
    fun paintClock(g: Gray8, l: Layout, hh: Int, mm: Int) {
        val r = clockRect(l)
        g.fillRect(r, 0)
        Icons.sevenSegClock(g, r.x + 4, r.y + 2, hh, mm)
    }
}
