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
        if (maxW <= 0) {
            // no room at all: the MARK still draws — a silently absent label
            // is the exact omission this helper exists to prevent (review
            // 2026-09-01 F2 sibling)
            if (str.isNotEmpty()) Icons.tri(g, x, y + 5, 11, markLv)
            return str.isNotEmpty()
        }
        if (tx.measure(str, f) <= maxW) {
            tx.draw(g, x / 4 * 4, y / 2 * 2, str, f, lv)
            return false
        }
        // leave room for the mark itself. The cut lands on a CODE-POINT
        // boundary (take(n) could split a surrogate pair — a lone surrogate
        // reaching the rasterizer is the L1 throw-inside-paint class) and is
        // found by binary search (log-n measures, was n² per paint) — the
        // R2#20a fitEnd fix, applied to the kit itself.
        val textMax = maxW - 14
        val bounds = ArrayList<Int>(str.length + 1)
        var i = 0
        while (i < str.length) { bounds.add(i); i += Character.charCount(str.codePointAt(i)) }
        bounds.add(str.length)
        // prefix width is monotone in the boundary index: largest prefix ≤ textMax
        var best = 0
        var a = 0
        var b = bounds.size - 1
        while (a <= b) {
            val m = (a + b) / 2
            if (tx.measure(str.substring(0, bounds[m]), f) <= textMax) { best = bounds[m]; a = m + 1 } else b = m - 1
        }
        tx.draw(g, x / 4 * 4, y / 2 * 2, str.substring(0, best), f, lv)
        Icons.tri(g, x + maxW - 10, y + 5, 11, markLv)
        return true
    }

    /** Right-aligned draw ending at [xRight]. */
    fun right(g: Gray8, tx: TextRasterizer, xRight: Int, y: Int, str: String, lv: Int, f: FontSpec) {
        tx.draw(g, (xRight - tx.measure(str, f)) / 4 * 4, y / 2 * 2, str, f, lv)
    }

    private val warnedGlyphs = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * DYNAMIC (externally-sourced) text — summaries, notification bodies,
     * session names, file names: every glyph the face cannot draw becomes a
     * visible '?', logged once per glyph set. Chrome has carried this rule
     * since round 4; review 2026-09-01 L1 found Main and the notification box
     * without it — a CJK tmux-session name THREW inside Main's row paint and
     * left the content half-painted with no damage hint. Never throw on data.
     */
    fun dynamic(tx: TextRasterizer, str: String, f: FontSpec): String {
        if (str.isEmpty() || tx.covers(str, f)) return str
        val bad = LinkedHashSet<String>()
        val fixed = buildString(str.length) {
            var i = 0
            while (i < str.length) {
                val cp = str.codePointAt(i)
                i += Character.charCount(cp)
                val s = String(Character.toChars(cp))
                if (cp < 0x20) { append('?'); bad.add("U+%04X".format(cp)) }
                else if (tx.covers(s, f)) append(s)
                else { append('?'); bad.add(s) }
            }
        }
        // bounded (R2#20b): a month of CJK browsing must not grow this set
        // forever — clearing re-logs at worst, which is the loud direction
        if (warnedGlyphs.size > 512) warnedGlyphs.clear()
        if (warnedGlyphs.add(bad.joinToString(""))) {
            wm.damage.core.util.Log.w("draw", "glyphs the ${f.face} face cannot draw: $bad — shown as '?' (first seen in '${str.take(60)}')")
        }
        return fixed
    }
}
