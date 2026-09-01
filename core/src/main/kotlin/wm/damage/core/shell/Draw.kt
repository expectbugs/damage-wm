package wm.damage.core.shell

import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * The shared fit-with-mark helper (EXPLOSION §16.11, agreed 2026-09-01): a
 * handle that does not fit is elided WITH the drawn `▸` continuation mark —
 * by construction, never by per-window discipline. This closes the §18.1
 * debt (tmux rows clipping bare) and Reader's silent drawFit clips: an
 * unadvertised cut is now impossible for any caller that draws through here.
 *
 * The rule it implements (§2.4 r3, worded honestly 2026-09-01): NO TRUNCATION
 * governs CONTENT; rows/titles are handles, elided only when the cut is
 * advertised and the full text is reachable (the lens, descent).
 */
object Draw {

    /**
     * Draw [str] at ([x],[y]) in [f]/[lv], fitted to [maxW]. A string that
     * does not fit is cut to fit AND gets the drawn continuation mark at the
     * right edge of the box ([x]+[maxW]). Returns true when it was cut.
     * Coordinates snap to the damage grid here so callers cannot drift.
     */
    fun fit(g: Gray8, tx: TextRasterizer, x: Int, y: Int, str: String, lv: Int,
        f: FontSpec, maxW: Int, markLv: Int = Level.DIM): Boolean {
        if (maxW <= 0) return false
        if (tx.measure(str, f) <= maxW) {
            tx.draw(g, x / 4 * 4, y / 2 * 2, str, f, lv)
            return false
        }
        // leave room for the mark itself
        val textMax = maxW - 14
        var n = str.length
        while (n > 0 && tx.measure(str.take(n), f) > textMax) n--
        tx.draw(g, x / 4 * 4, y / 2 * 2, str.take(n), f, lv)
        Icons.tri(g, x + maxW - 10, y + 5, 11, markLv)
        return true
    }

    /** Right-aligned draw ending at [xRight]. */
    fun right(g: Gray8, tx: TextRasterizer, xRight: Int, y: Int, str: String, lv: Int, f: FontSpec) {
        tx.draw(g, (xRight - tx.measure(str, f)) / 4 * 4, y / 2 * 2, str, f, lv)
    }
}
