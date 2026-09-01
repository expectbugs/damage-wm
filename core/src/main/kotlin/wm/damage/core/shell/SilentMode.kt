package wm.damage.core.shell

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Icons

/**
 * Silent mode — everything hidden but a digital clock flush to the top-right
 * (DESIGN.md §1.5, revised 2026-08-31): drawn LED-style digits, 12-hour,
 * minutes only — it repaints once a minute, 60 flushes/hour, the cheapest
 * possible "alive". All input is swallowed except double-tap (back to Main).
 * This completes the gloves fix: a stray ring long-press lands here and does
 * nothing.
 *
 * Sizes (2026-09-01, Adam — `ShellSettings.silentClock`): **large** = the
 * original 144×48 seven-segment box; **medium** = a 112×34 seven-segment box,
 * same top-right corner; **small** = the title bar clock's EXACT size and
 * position (the layout's clock cell, drawn through the same chrome text
 * painter the caller supplies).
 */
object SilentMode {
    /** The clock's box for [size], safe-rect relative. Large/medium: flush to
     *  the TOP-RIGHT (Adam, 2026-08-31 — "all the way up and all the way
     *  right, and forget analog"). Small: the chrome clock cell itself. */
    fun clockRect(l: Layout, size: String): Rect = when (size) {
        "small" -> l.clockCell
        "medium" -> Rect(Geometry.snapX(l.safe.right - 112), Geometry.snapY(l.safe.y), 112, 34)
        else -> Rect(Geometry.snapX(l.safe.right - 144), Geometry.snapY(l.safe.y), 144, 48)
    }

    /** Paint the whole silent surface (a keyframe-ish repaint on entry).
     *  [smallPainter] draws the title-bar-clock text into the given rect —
     *  supplied by the shell so this object needs no rasterizer. */
    fun paintAll(g: Gray8, l: Layout, hh: Int, mm: Int, size: String,
        smallPainter: (Gray8, Rect) -> Unit) {
        g.fillRect(Rect(0, 0, g.w, g.h), 0)
        paintClock(g, l, hh, mm, size, smallPainter)
    }

    /** Paint just the clock box (the minute tick's damage). */
    fun paintClock(g: Gray8, l: Layout, hh: Int, mm: Int, size: String,
        smallPainter: (Gray8, Rect) -> Unit) {
        val r = clockRect(l, size)
        g.fillRect(r, 0)
        when (size) {
            "small" -> smallPainter(g, r)
            "medium" -> Icons.sevenSegClockMedium(g, r.x + 4, r.y + 2, hh, mm)
            else -> Icons.sevenSegClock(g, r.x + 4, r.y + 2, hh, mm)
        }
    }
}
