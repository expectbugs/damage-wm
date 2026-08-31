package wm.damage.core.windows.tmux

import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * The live-grid renderer (TMUX.md §3.3): styled cells -> JetBrains Mono on the
 * 16-gray panel, FIT to whatever rect the layout gives — height mode runs
 * 288..480 (Adam mid-build), so fit = min(width-fit, height-fit), re-derived
 * whenever the rect or grid changes. Glyphs sit at exact cellW multiples so
 * 80 columns never shear; the mono face makes that alignment functional, not
 * decorative (§Type).
 *
 * Context rows (verdict 5: ON by default) draw dimmed above the live pane
 * with a faint separator; the cursor cell renders inverted, static — a blink
 * would spend two rects a second forever against §6's motion discipline.
 */
class TermRender(private val text: TextRasterizer) {

    /** Geometry chosen for one (rect, cols, rows) combination. */
    data class FitSpec(val sizePx: Int, val cellW: Int, val cellH: Int, val x0: Int, val y0: Int,
        val cols: Int, val rowsShown: Int, val contextShown: Int)

    private var fitKey: Long = -1
    private var fitCols = -1
    private var fit: FitSpec? = null
    private val coverCache = HashMap<Long, Boolean>()

    /** Pick the largest MONO size whose grid fits [rect]: [cols] wide and
     *  [liveRows] high, plus up to [contextRows] more when height is spare
     *  (context never shrinks the live pane's cells). */
    fun fitFor(rect: Rect, cols: Int, liveRows: Int, contextRows: Int): FitSpec {
        val key = (rect.w.toLong() shl 40) or (rect.h.toLong() shl 20) or
            (liveRows.toLong() shl 8) or contextRows.toLong()
        fit?.let { if (key == fitKey && cols == fitCols) return it }
        var size = 29
        var cellW: Int
        var cellH: Int
        do {
            size--
            val f = FontSpec(Face.MONO, size)
            cellW = maxOf(1, (text.measure("MMMMMMMMMM", f) + 9) / 10)
            cellH = maxOf(1, text.metrics(f).lineHeight)
        } while (size > 5 && (cellW * cols > rect.w || cellH * liveRows > rect.h))
        // context rows ride only in genuinely spare height (+2 px separator)
        val spare = rect.h - cellH * liveRows
        val ctx = minOf(contextRows, maxOf(0, (spare - 2) / cellH))
        val gridW = cellW * cols
        val spec = FitSpec(size, cellW, cellH,
            x0 = rect.x + maxOf(0, (rect.w - gridW) / 2),
            y0 = rect.y,                     // TOP-aligned: Adam's fit loses the BOTTOM (§12 sizes)
            cols = cols, rowsShown = liveRows, contextShown = ctx)
        fitKey = key
        fitCols = cols
        fit = spec
        return spec
    }

    /** The rect or size mode changed (Shell.onLayoutChanged): forget the fit. */
    fun invalidate() {
        fitKey = -1
        fit = null
    }

    /**
     * Paint [frame] into [rect] (cleared to BG first — Canvas owns its area).
     * [wantContext] is the Settings row; the frame's own contextRows bound it
     * (an alternate-screen pane simply has none — TmuxModel).
     */
    fun render(g: Gray8, rect: Rect, frame: PaneFrame, wantContext: Boolean): FitSpec {
        g.fillRect(rect, Level.BG)
        val parsed = Sgr.parse(frame.lines, frame.cols)
        val ctxAvail = if (wantContext) frame.contextRows else 0
        val spec = fitFor(rect, frame.cols, frame.rows, ctxAvail)
        val totalCtx = minOf(spec.contextShown, frame.contextRows)
        // rows drawn top-down: the last `rows` lines are the live pane; up to
        // totalCtx history rows sit directly above them, dimmed. A capture
        // whose trailing blank rows were trimmed has FEWER lines than the
        // pane — then line 0 IS pane row 0 and the missing rows are blank.
        val liveStart = maxOf(0, frame.lines.size - frame.rows)
        val firstRow = liveStart - totalCtx
        var y = spec.y0
        for (r in maxOf(0, firstRow) until frame.lines.size) {
            val isCtx = r < liveStart
            val row = parsed.rows.getOrNull(r) ?: continue
            drawRow(g, spec, row, y, dim = isCtx)
            y += spec.cellH
            if (isCtx && r == liveStart - 1) {
                // the faint seam between the past and the pane
                g.fillRect(spec.x0, y, spec.cellW * spec.cols, 1, Level.FAINT)
                y += 2
            }
        }
        // the cursor cell, inverted, only when the live pane is on show
        if (frame.cursorVisible && frame.cursorY in 0 until frame.rows) {
            val cy = spec.y0 + (totalCtx * spec.cellH + if (totalCtx > 0) 2 else 0) +
                frame.cursorY * spec.cellH
            val cx = spec.x0 + frame.cursorX * spec.cellW
            if (frame.cursorX in 0 until spec.cols && cy + spec.cellH <= rect.bottom) {
                g.fillRect(cx, cy, spec.cellW, spec.cellH, Level.BODY)
                val row = parsed.rows.getOrNull(liveStart + frame.cursorY)
                val cp = row?.cp?.getOrNull(frame.cursorX) ?: ' '.code
                if (cp != ' '.code && cp != Sgr.CONT) {
                    drawGlyph(g, spec, cx, cy, cp, level = 0, bold = false)
                }
            }
        }
        return spec
    }

    private fun drawRow(g: Gray8, spec: FitSpec, row: Sgr.Row, y: Int, dim: Boolean) {
        val n = minOf(row.cols, spec.cols)
        // background runs first (a run of equal bg is one fill)
        var c = 0
        while (c < n) {
            val bg = effBg(row, c)
            var e = c + 1
            while (e < n && effBg(row, e) == bg) e++
            if (bg > 0) {
                val lv = if (dim) maxOf(1, bg * 2 / 3) else bg
                g.fillRect(spec.x0 + c * spec.cellW, y, (e - c) * spec.cellW, spec.cellH, Level.of(lv))
            }
            c = e
        }
        // glyphs per cell — exact column positions are the point of mono
        for (col in 0 until n) {
            val cp = row.cp[col]
            if (cp == ' '.code || cp == Sgr.CONT) continue
            val fl = row.flags[col].toInt()
            var fg = effFg(row, col)
            if (fl and Sgr.BOLD != 0) fg = minOf(15, fg + 2)
            if (fl and Sgr.DIM != 0) fg = maxOf(1, fg - 3)
            if (dim) fg = maxOf(1, fg * 3 / 5)
            val x = spec.x0 + col * spec.cellW
            drawGlyph(g, spec, x, y, cp, Level.of(fg), bold = fl and Sgr.BOLD != 0)
            if (fl and Sgr.UNDERLINE != 0) {
                g.fillRect(x, y + spec.cellH - 2, spec.cellW * Sgr.width(cp), 1, Level.of(fg))
            }
        }
    }

    /** Reverse video swaps the cell's colours. */
    private fun effFg(row: Sgr.Row, c: Int): Int =
        if (row.flags[c].toInt() and Sgr.REVERSE != 0) row.bg[c].toInt() else row.fg[c].toInt()

    private fun effBg(row: Sgr.Row, c: Int): Int =
        if (row.flags[c].toInt() and Sgr.REVERSE != 0) row.fg[c].toInt() else row.bg[c].toInt()

    private fun drawGlyph(g: Gray8, spec: FitSpec, x: Int, y: Int, cp: Int, level: Int, bold: Boolean) {
        val f = FontSpec(Face.MONO, spec.sizePx, bold = bold)
        val s = String(Character.toChars(cp))
        val key = (cp.toLong() shl 1) or (if (bold) 1L else 0L)
        val covered = coverCache.getOrPut(key) { text.covers(s, f) }
        if (!covered) {
            // the visible tofu box — a glyph the face lacks is SHOWN missing,
            // never dropped (the texture-cache table's idiom)
            val w = spec.cellW * Sgr.width(cp)
            g.fillRect(x + 1, y + 1, maxOf(1, w - 2), 1, level)
            g.fillRect(x + 1, y + spec.cellH - 3, maxOf(1, w - 2), 1, level)
            g.fillRect(x + 1, y + 1, 1, spec.cellH - 3, level)
            g.fillRect(x + maxOf(2, w - 2), y + 1, 1, spec.cellH - 3, level)
            return
        }
        text.draw(g, x, y, s, f, level)
    }
}
