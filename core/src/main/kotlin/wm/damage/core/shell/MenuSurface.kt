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

    /**
     * The box's vertical rhythm, MEASURED from the chrome face (review §28
     * #7). The design numbers — an 18 px title band and a 24 px row pitch —
     * are what Clear Sans 13 bold / 17 fit at 100 %, and they are kept there
     * exactly; under the global font scale the title's baseline fell below
     * its own rule and the rows' descenders ran into the next row's caps, so
     * the band and the pitch follow the ink from here up. Even values only
     * (the box is a damage rect).
     */
    private fun titleH(): Int = maxOf(TITLE_H, evenUp(text.metrics(fTitle).ascent + 2))

    /** Where the title is drawn so its BASELINE lands on the band's last row
     *  — the rule below it then clears every cap. 🔴 A constant `+4` put
     *  Clear Sans 13 bold's caps on rows 9..19 under a rule at 18..19, so
     *  the rule struck straight through the title (review §30, visible in
     *  `snapshots/13-files-menu.png`). Measured, so it holds at every step of
     *  the font ladder. */
    private fun titleY(): Int = titleH() - text.metrics(fTitle).ascent
    private fun rowH(): Int = maxOf(ROW_H, evenUp(text.metrics(fRow).let { it.ascent + it.descent } - 1))
    private fun chromeH(): Int = titleH() + 4 + 8
    private fun evenUp(v: Int): Int = (v + 1) / 2 * 2

    fun openWith(s: Spec) {
        require(s.items.isNotEmpty()) { "a menu needs at least one item" }
        spec = s
        // the cursor opens on the first row that can DO something (review
        // §30): a menu whose first row is dim opens on a tap that is a no-op,
        // and the Music menu with an empty queue is exactly that. Every menu
        // whose first row is live — which is all of them but that one — is
        // unchanged, the safety Cancels included.
        cursor = s.items.indexOfFirst { it.enabled }.coerceAtLeast(0)
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
        return minOf(n, maxOf(1, (maxH - chromeH()) / rowH()))
    }

    /** The box's WIDTH follows the row face too (review §29, the live walk):
     *  the design's 248 at 100 %, grown by the same ratio the row pitch grew,
     *  so a label keeps the room it was designed with under a bigger chrome
     *  face — at 120 % the fixed box cut "Fold and leave" to "Fold and ▸".
     *  Grid-legal, and never wider than the content. */
    private fun boxW(l: Layout): Int =
        Geometry.snapX((W * rowH() / ROW_H).coerceIn(W, l.content.w - 16))

    fun rect(l: Layout): Rect? {
        val s = spec ?: return null
        val rows = visibleRows(l, s.items.size)
        val h = Geometry.snapY(chromeH() + rows * rowH())
        val w = boxW(l)
        val cx = l.safe.x + l.safe.w / 2
        val cy = l.content.y + l.content.h / 2
        return Rect(Geometry.snapX(cx - w / 2), Geometry.snapY(cy - h / 2), w, h)
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
        // every string here is DYNAMIC (file names, torrent names, tracker
        // text): sanitized against THIS surface's rasterizer and the exact
        // spec it draws with — the chrome face and weight can differ from the
        // caller's (review 2026-09-01 R2-W6)
        val titleH = titleH()
        val rowH = rowH()
        // the rule FIRST, then the title over it: a rule painted last would
        // silently clip whatever it lands on, which is exactly how the
        // strikethrough below went unseen for so long (review §30)
        g.fillRect(box.x + 8, box.y + titleH, box.w - 16, 2, Level.FAINT)
        Draw.fit(g, text, box.x + 8, box.y + titleY(), Draw.dynamic(text, s.title.uppercase(), fTitle), Level.DIM, fTitle, box.w - 16)

        val rows = visibleRows(l, s.items.size)
        // the pan window follows the cursor
        if (cursor < top) top = cursor
        if (cursor >= top + rows) top = cursor - rows + 1
        for (i in 0 until rows) {
            val idx = top + i
            val it = s.items.getOrNull(idx) ?: break
            val y = box.y + titleH + 4 + i * rowH
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
            val detailFull = if (it.detail.isEmpty()) "" else Draw.dynamic(text, it.detail, fDetail)
            val detailShown = if (detailFull.isEmpty()) "" else fitEnd(detailFull, fDetail, detailMax)
            // a detail cut at its HEAD gets the drawn continuation mark on
            // that edge (review §29, the live walk: "nothing queued" read
            // "othing queued" at 130 % with nothing to say it was cut — the
            // §2.4 r3 rule, NO TRUNCATION means an advertised cut, applies
            // to the tail-keeping fit exactly as to the prefix one)
            val cut = detailShown.length < detailFull.length
            val detailW = if (detailShown.isEmpty()) 0 else text.measure(detailShown, fDetail) + 8 + (if (cut) 12 else 0)
            Draw.fit(g, text, box.x + 20, y + 2, Draw.dynamic(text, it.label, fRow), lv, fRow, box.w - 28 - detailW)
            if (detailShown.isNotEmpty()) {
                val dlv = if (focusedRow) Level.MID else Level.DIM
                Draw.right(g, text, box.right - 8, y + 6, detailShown, dlv, fDetail)
                if (cut) wm.damage.core.gfx.Icons.tri(g, box.right - 8 - text.measure(detailShown, fDetail) - 4, y + 11, 11, dlv, left = true)
            }
        }
        if (s.items.size > rows) {
            // more rows exist above/below the pan window — advertise, never hide
            if (top > 0) g.fillRect(box.x + box.w / 2 - 8, box.y + titleH + 2, 16, 2, Level.MID)
            if (top + rows < s.items.size) g.fillRect(box.x + box.w / 2 - 8, box.bottom - 6, 16, 2, Level.MID)
        }
        return box
    }

    /** Fit [s] into [maxW] keeping its END (a path/name's tail is the
     *  distinctive part); the caller's row label carries the mark. Cuts on
     *  CODE-POINT boundaries — takeLast on a char index split surrogate
     *  pairs — and binary-searches the fit (log-n measures per paint, was
     *  n² for a long path detail) (R2#20a). */
    private fun fitEnd(s: String, f: FontSpec, maxW: Int): String {
        if (maxW <= 0) return ""
        if (text.measure(s, f) <= maxW) return s
        val bounds = ArrayList<Int>(s.length + 1)
        var i = 0
        while (i < s.length) { bounds.add(i); i += Character.charCount(s.codePointAt(i)) }
        bounds.add(s.length)
        // the tail from bounds[m]: width is monotone in m, so binary-search
        // the SMALLEST start whose tail fits (empty tail always fits)
        var best = s.length
        var a = 0
        var b = bounds.size - 1
        while (a <= b) {
            val m = (a + b) / 2
            if (text.measure(s.substring(bounds[m]), f) <= maxW) { best = bounds[m]; b = m - 1 } else a = m + 1
        }
        return s.substring(best)
    }

    companion object {
        const val W = 248                      // ×4 ✓ — the notification box's width
        /** The 100 % rhythm — the FLOOR of the measured values above. */
        const val ROW_H = 24
        const val TITLE_H = 18
        const val CHROME_H = TITLE_H + 4 + 8   // title + rule gap + bottom pad (kept even)
    }
}
