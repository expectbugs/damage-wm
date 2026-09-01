package wm.damage.core.shell

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * The floating CONTEXT MENU (EXPLOSION §16.11, Adam's Files design 2026-09-01:
 * "a tap should work like a right-click"). A bespoke surface in the house
 * language — a HOLE, not a card (level 0 is see-through), bracketed by two
 * horizontal rules, floated at plane 0 over the content dimmed at −1, the
 * content still visible around it. Shell furniture like the switcher and the
 * notification box: drawn in the system face, its own geometry, deliberately
 * NOT a generic overlay abstraction (§0) — the shell routes input to it while
 * open, and it shares only the compositor plumbing.
 *
 * Grammar while open: scroll moves the cursor (wraps), tap commits the row
 * (menu closes FIRST, then the callback runs), double-tap closes = cancel.
 * The cursor OPENS at row 0 — Open sits there by Files' contract, which makes
 * tap-tap navigation nearly one gesture and keeps §1.7 (destructive rows
 * never at rest, never index 0/1).
 */
class MenuSurface(private val text: TextRasterizer) {

    class Item(val label: String, val detail: String = "", val enabled: Boolean = true)

    class Spec(
        val title: String,
        val items: List<Item>,
        /** Runs on the shell loop AFTER the menu closed and its under-content
         *  was restored — the handler may open another menu or navigate. */
        val onCommit: (Int) -> Unit,
        /** Cancel path only (double-tap / displaced by wheel or silent). */
        val onClose: (() -> Unit)? = null,
    )

    var open = false
        private set
    var cursor = 0
        private set
    private var spec: Spec? = null
    private var top = 0

    private var under: Gray8? = null
    private var underRect: Rect? = null

    private val fTitle = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fRow = FontSpec(Face.SYSTEM, 17)
    private val fDetail = FontSpec(Face.SYSTEM, 13)

    fun openWith(s: Spec) {
        require(s.items.isNotEmpty()) { "a menu needs at least one item" }
        spec = s
        cursor = 0
        top = 0
        open = true
        invalidateUnder()
    }

    /** Close without running anything (the cancel/displaced path). The caller
     *  restores the under-content via [restoreUnderFinished]. */
    fun close(): Spec? {
        val s = spec
        open = false
        spec = null
        return s
    }

    fun scroll(delta: Int) {
        val s = spec ?: return
        val n = s.items.size
        if (n <= 1) return
        cursor = (cursor + delta).mod(n)
    }

    fun selected(): Int = cursor
    fun current(): Spec? = spec

    // ------------------------------------------------------------------ paint
    /** Rows that fit the content area, given the box chrome. */
    private fun visibleRows(l: Layout, n: Int): Int {
        val maxH = l.content.h - 32
        return minOf(n, maxOf(1, (maxH - CHROME_H) / ROW_H))
    }

    fun rect(l: Layout): Rect? {
        val s = spec ?: return null
        val rows = visibleRows(l, s.items.size)
        val h = Geometry.snapY(CHROME_H + rows * ROW_H)
        val cx = l.safe.x + l.safe.w / 2
        val cy = l.content.y + l.content.h / 2
        return Rect(Geometry.snapX(cx - W / 2), Geometry.snapY(cy - h / 2), W, h)
    }

    fun captureUnder(g: Gray8, box: Rect) {
        if (underRect == box && under != null) return
        val u = Gray8(box.w, box.h)
        u.blit(g, box, 0, 0)
        under = u
        underRect = box
    }

    fun invalidateUnder() {
        under = null
        underRect = null
    }

    /** Restore what the menu covered; null when the snapshot was invalidated
     *  (the caller repaints the content instead). */
    fun restoreUnderFinished(g: Gray8): Rect? {
        val u = under
        val ur = underRect
        invalidateUnder()
        if (u == null || ur == null) return null
        g.blit(u, Rect(0, 0, ur.w, ur.h), ur.x, ur.y)
        return ur
    }

    /** Paint the menu (capture the under-content first). Returns the box. */
    fun paint(g: Gray8, l: Layout): Rect? {
        val s = spec ?: return null
        val box = rect(l) ?: return null
        captureUnder(g, box)
        g.fillRect(box, Level.BG)                       // the hole
        // bracketing rules — the shell's motif
        g.fillRect(box.x, box.y, box.w, 2, Level.DIM)
        g.fillRect(box.x, box.bottom - 2, box.w, 2, Level.DIM)
        Draw.fit(g, text, box.x + 8, box.y + 4, s.title.uppercase(), Level.DIM, fTitle, box.w - 16)
        g.fillRect(box.x + 8, box.y + TITLE_H, box.w - 16, 2, Level.FAINT)

        val rows = visibleRows(l, s.items.size)
        // the pan window follows the cursor
        if (cursor < top) top = cursor
        if (cursor >= top + rows) top = cursor - rows + 1
        for (i in 0 until rows) {
            val idx = top + i
            val it = s.items.getOrNull(idx) ?: break
            val y = box.y + TITLE_H + 4 + i * ROW_H
            val focusedRow = idx == cursor
            val lv = when {
                !it.enabled -> Level.REST
                focusedRow -> Level.HEAD
                else -> Level.BODY
            }
            if (focusedRow) {
                // focus as brightness plus a small drawn lead mark
                wm.damage.core.gfx.Icons.tri(g, box.x + 6, y + 6, 11, Level.MID)
            }
            // the detail is CAPPED to half the box (review 2026-09-01 F2): an
            // unbounded right-align walked left past box.x, painting outside
            // the damage rect and the plane-0 region — undamaged composed ink
            // the mirror check cannot see, shipped as garbage later
            val detailMax = (box.w / 2 - 12).coerceAtLeast(0)
            val detailShown = if (it.detail.isEmpty()) "" else fitEnd(it.detail, fDetail, detailMax)
            val detailW = if (detailShown.isEmpty()) 0 else text.measure(detailShown, fDetail) + 8
            Draw.fit(g, text, box.x + 20, y + 2, it.label, lv, fRow, box.w - 28 - detailW)
            if (detailShown.isNotEmpty()) {
                Draw.right(g, text, box.right - 8, y + 6, detailShown, if (focusedRow) Level.MID else Level.DIM, fDetail)
            }
        }
        if (s.items.size > rows) {
            // more rows exist above/below the pan window — advertise, never hide
            if (top > 0) g.fillRect(box.x + box.w / 2 - 8, box.y + TITLE_H + 2, 16, 2, Level.MID)
            if (top + rows < s.items.size) g.fillRect(box.x + box.w / 2 - 8, box.bottom - 6, 16, 2, Level.MID)
        }
        return box
    }

    /** Fit [s] into [maxW] keeping its END (a path/name's tail is the
     *  distinctive part); the caller's row label carries the mark. */
    private fun fitEnd(s: String, f: FontSpec, maxW: Int): String {
        if (maxW <= 0) return ""
        if (text.measure(s, f) <= maxW) return s
        var n = s.length
        while (n > 0 && text.measure(s.takeLast(n), f) > maxW) n--
        return s.takeLast(n)
    }

    companion object {
        const val W = 248                      // ×4 ✓ — the notification box's width
        const val ROW_H = 24
        const val TITLE_H = 18
        const val CHROME_H = TITLE_H + 4 + 8   // title + rule gap + bottom pad (kept even)
    }
}
