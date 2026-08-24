package wm.damage.core.shell

import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level

/**
 * The WM-owned content machinery for List and Document modes (§4.6): the lens
 * primitive, the fixed-cursor panning list, endless document scroll, and the
 * scroll rail. This is where "List and Document are nearly free because the WM
 * tracks damage for them" is implemented: a one-step move is mode-9 copies plus
 * small fills, never a repaint (§5.3).
 */
class ContentKit(private val comp: Compositor) {

    /** Paint the WM scroll rail (§4.6): a filled thumb, repainted only when it
     *  would move >= 2 px. Returns the damaged rect or null. */
    private var lastThumbY = -1
    fun paintRail(g: Gray8, l: Layout, frac: Double, span: Int): Rect? {
        val track = l.rail
        if (span >= track.h) {
            // everything fits: nothing to scroll, so no rail at all (ink is
            // opacity and cost on this panel — §4.2)
            if (lastThumbY == -2) return null
            lastThumbY = -2
            g.fillRect(track, Level.BG)
            return Rect(track.x, track.y, track.w, track.h)
        }
        val thumbSpan = span.coerceIn(16, track.h)
        val ty = track.y + ((track.h - thumbSpan) * frac.coerceIn(0.0, 1.0)).toInt()
        if (lastThumbY >= 0 && kotlin.math.abs(ty - lastThumbY) < 2) return null
        lastThumbY = ty
        g.fillRect(track, Level.BG)
        g.fillRect(track.x + 4, track.y, 4, track.h, Level.FAINT)
        g.fillRect(track.x + 4, Geometry.snapY(ty), 4, Geometry.snapY(thumbSpan), Level.BODY)
        return Rect(track.x, track.y, track.w, track.h)
    }

    fun resetRail() {
        lastThumbY = -1
    }

    // ======================================================== fixed-cursor list
    /**
     * Full repaint of a ListView into the content area. Slot geometry per §4.2:
     * rowsAbove rows, the lens band, rowsBelow rows, 16 px pads. The list wraps
     * when longer than the visible slots; shorter lists show blanks (honest).
     */
    fun paintList(g: Gray8, l: Layout, v: WindowView.ListView, resting: Boolean = false) {
        g.fillRect(l.content, Level.BG)
        val n = v.rowCount()
        if (n == 0) return
        val c = v.model.cursor.mod(n)
        v.model.cursor = c
        val slots = l.rowsAbove + l.rowsBelow + 1
        for (slot in 0 until l.rowsAbove) {
            val idx = wrapIndex(c - l.rowsAbove + slot, n, slots)
            if (idx != null) v.paintRow(g, idx, rowRect(l, slot, above = true), resting)
        }
        paintLensBand(g, l, v, c)
        for (slot in 0 until l.rowsBelow) {
            val idx = wrapIndex(c + 1 + slot, n, slots)
            if (idx != null) v.paintRow(g, idx, rowRect(l, slot, above = false), resting)
        }
        paintRail(g, l, if (n > 1) c.toDouble() / (n - 1) else 0.0, railSpan(l, n))
        comp.damage(l.content)
    }

    private fun paintLensBand(g: Gray8, l: Layout, v: WindowView.ListView, c: Int) {
        g.fillRect(l.lens, Level.BG)
        // bracketing rules — the shell's motif (§4.2)
        g.fillRect(l.lens.x, l.lens.y, l.lens.w - Layout.RAIL_W, 2, Level.DIM)
        g.fillRect(l.lens.x, l.lens.bottom - 2, l.lens.w - Layout.RAIL_W, 2, Level.DIM)
        v.paintLens(g, Rect(l.lens.x, l.lens.y, l.lens.w - Layout.RAIL_W, l.lens.h), c)
    }

    private fun rowRect(l: Layout, slot: Int, above: Boolean): Rect = if (above) {
        Rect(l.content.x, l.content.y + Layout.CONTENT_PAD + slot * Layout.ROW_H,
            l.content.w - Layout.RAIL_W, Layout.ROW_H)
    } else {
        Rect(l.content.x, l.lens.bottom + slot * Layout.ROW_H,
            l.content.w - Layout.RAIL_W, Layout.ROW_H)
    }

    /** Wrap only when the list overfills the visible slots; shorter lists show
     *  blanks instead of duplicated rows. */
    private fun wrapIndex(i: Int, n: Int, slots: Int): Int? =
        if (n > slots) i.mod(n) else if (i in 0 until n) i else null

    private fun railSpan(l: Layout, n: Int): Int =
        (l.content.h * (l.rowsAbove + l.rowsBelow + 1) / maxOf(1, n)).coerceIn(16, l.content.h)

    // ======================================================== endless document
    /** Full repaint of a DocView. */
    fun paintDoc(g: Gray8, l: Layout, v: WindowView.DocView) {
        g.fillRect(l.content, Level.BG)
        val lines = visibleLines(l, v)
        val top = v.model.topLine.coerceIn(0, maxOf(0, v.lineCount() - 1))
        v.model.topLine = top
        for (i in 0 until lines) {
            val idx = top + i
            if (idx >= v.lineCount()) break
            v.paintLine(g, idx, lineRect(l, v, i))
        }
        docRail(g, l, v)
        comp.damage(l.content)
    }

    private fun docRail(g: Gray8, l: Layout, v: WindowView.DocView) {
        val lines = visibleLines(l, v)
        val maxTop = maxOf(1, v.lineCount() - lines)
        paintRail(g, l, v.model.topLine.toDouble() / maxTop,
            (l.content.h.toLong() * lines / maxOf(1, v.lineCount())).toInt())
            ?.let { comp.damage(it) }
    }

    fun visibleLines(l: Layout, v: WindowView.DocView): Int =
        (l.content.h - 2 * Layout.CONTENT_PAD) / v.lineHeight

    private fun lineRect(l: Layout, v: WindowView.DocView, slot: Int): Rect = Rect(
        l.content.x, l.content.y + Layout.CONTENT_PAD + slot * v.lineHeight,
        l.content.w - Layout.RAIL_W, v.lineHeight,
    )
}
