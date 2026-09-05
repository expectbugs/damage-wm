package wm.damage.core.windows.tmux

import kotlin.math.roundToInt
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * The grid renderer (TMUX.md §3.3, revised 2026-08-31 after Adam's first
 * on-glass session): styled cells -> JetBrains Mono on the 16-gray panel.
 *
 * Columns sit on a FRACTIONAL pitch — pitchX = rect.w / cols — with every
 * cell edge rounded per column, so 80 columns genuinely span the full width
 * (the old integer cell width floored 7.6 px to 7 and left a 48 px gutter:
 * "narrow and centered", his words). Rounding drifts a glyph at most 1 px
 * against its neighbour, which mono at these sizes absorbs.
 *
 * HISTORY IS NOT DRAWN HERE. Scrollback is normal-screen text even when the
 * live pane runs a TUI, so it renders through [FlowRender.renderHistory] —
 * the §18 grid retirement (2026-08-31) left this class with the ONE job of
 * an alternate-screen pane. A grid history path lived here until 2026-09-02
 * and was removed once a review found nothing could reach it.
 *
 * Context rows (verdict 5: ON) draw dimmed above the live pane with a faint
 * seam; the cursor cell renders inverted, static — a blink would spend two
 * rects a second forever against §6's motion discipline.
 */
class TermRender(private val text: TextRasterizer) {

    /** Geometry chosen for one (rect, cols, rows) combination. */
    data class FitSpec(val sizePx: Int, val pitchX: Double, val cellH: Int, val x0: Int, val y0: Int,
        val cols: Int, val rowsShown: Int, val contextShown: Int) {
        /** The left edge of [col] — every cell boundary rounds independently
         *  so the grid spans the full pitch with <=1 px local drift. */
        fun cellX(col: Int): Int = x0 + (col * pitchX).roundToInt()
    }

    private var fitKey: Long = -1
    private var fitCols = -1
    private var fit: FitSpec? = null
    private val coverCache = HashMap<Long, Boolean>()

    /** Pick the largest MONO size whose glyph advance fits the column pitch
     *  and whose line height fits [liveRows] in the rect; context rows ride
     *  only in genuinely spare height (they never shrink the live cells). */
    fun fitFor(rect: Rect, cols: Int, liveRows: Int, contextRows: Int): FitSpec {
        val key = (rect.w.toLong() shl 40) or (rect.h.toLong() shl 20) or
            (liveRows.toLong() shl 8) or contextRows.toLong()
        fit?.let { if (key == fitKey && cols == fitCols) return it }
        val pitchX = rect.w.toDouble() / maxOf(1, cols)
        var size = 29
        var cellH: Int
        do {
            size--
            val f = FontSpec(Face.MONO, size)
            val advance = text.measure("MMMMMMMMMM", f) / 10.0
            // the MEASURED ink, not the line height alone — see FlowRender.lineH
            cellH = maxOf(2, text.metrics(f).let { maxOf(it.lineHeight, it.ascent + it.descent) })
            if (advance <= pitchX && cellH * liveRows <= rect.h) break
        } while (size > 5)
        val spare = rect.h - cellH * liveRows
        val ctx = minOf(contextRows, maxOf(0, (spare - 2) / cellH))
        val spec = FitSpec(size, pitchX, cellH,
            x0 = rect.x,
            y0 = rect.y,                     // TOP-aligned: Adam's fit loses the BOTTOM (§12 sizes)
            cols = cols, rowsShown = liveRows, contextShown = ctx)
        fitKey = key
        fitCols = cols
        fit = spec
        return spec
    }

    /** The rect, size mode or typography changed (Shell routes both through
     *  onLayoutChanged/onFontScaleChanged): forget the fit AND the coverage
     *  cache — a per-app font override (Style.kt) swaps the face under
     *  Face.MONO, and stale coverage would draw tofu boxes for glyphs the new
     *  face has (or glyphs for ones it lacks). */
    fun invalidate() {
        fitKey = -1
        fit = null
        coverCache.clear()
    }

    /**
     * Paint the LIVE pane [frame] into [rect] (cleared to BG — Canvas owns its
     * area). [wantContext] is the Settings row; the frame's own contextRows
     * bound it (an alternate-screen pane simply has none — TmuxModel).
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
            // HARD bottom guard: a glyph's AA can bleed a px or two past its
            // line box, and pixels past rect.bottom land on the DIVIDER and
            // linger (nothing owns them) — Adam saw exactly that as garbage
            // on the status bar (2026-08-31). Better a dropped bottom row.
            if (y + spec.cellH + 2 > rect.bottom) break
            drawRow(g, spec, row, y, dim = isCtx)
            y += spec.cellH
            if (isCtx && r == liveStart - 1) {
                // the faint seam between the past and the pane
                g.fillRect(spec.x0, y, spec.cellX(spec.cols) - spec.x0, 1, Level.FAINT)
                y += 2
            }
        }
        // the cursor cell, inverted, only when the live pane is on show. The
        // guard carries the SAME +2 margin as the row loop above: with 0-1 px
        // of slack the bottom row is dropped, and a cursor drawn there anyway
        // would sit on a blank row and bleed its glyph's AA onto the divider —
        // the exact defect the row guard exists for (2026-08-31).
        if (frame.cursorVisible && frame.cursorY in 0 until frame.rows) {
            val cy = spec.y0 + (totalCtx * spec.cellH + if (totalCtx > 0) 2 else 0) +
                frame.cursorY * spec.cellH
            val cx = spec.cellX(frame.cursorX)
            val cw = spec.cellX(frame.cursorX + 1) - cx
            if (frame.cursorX in 0 until spec.cols && cy + spec.cellH + 2 <= rect.bottom) {
                g.fillRect(cx, cy, cw, spec.cellH, Level.BODY)
                val row = parsed.rows.getOrNull(liveStart + frame.cursorY)
                val cp = row?.cp?.getOrNull(frame.cursorX) ?: ' '.code
                if (cp != ' '.code && cp != Sgr.CONT) {
                    drawGlyph(g, spec, frame.cursorX, cy, cp, level = 0, bold = false)
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
                g.fillRect(spec.cellX(c), y, spec.cellX(e) - spec.cellX(c), spec.cellH, Level.of(lv))
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
            drawGlyph(g, spec, col, y, cp, Level.of(fg), bold = fl and Sgr.BOLD != 0)
            if (fl and Sgr.UNDERLINE != 0) {
                val x = spec.cellX(col)
                g.fillRect(x, y + spec.cellH - 2, spec.cellX(col + Sgr.width(cp)) - x, 1, Level.of(fg))
            }
        }
    }

    /** Reverse video swaps the cell's colours. */
    private fun effFg(row: Sgr.Row, c: Int): Int =
        if (row.flags[c].toInt() and Sgr.REVERSE != 0) row.bg[c].toInt() else row.fg[c].toInt()

    private fun effBg(row: Sgr.Row, c: Int): Int =
        if (row.flags[c].toInt() and Sgr.REVERSE != 0) row.fg[c].toInt() else row.bg[c].toInt()

    private fun drawGlyph(g: Gray8, spec: FitSpec, col: Int, y: Int, cp: Int, level: Int, bold: Boolean) {
        val f = FontSpec(Face.MONO, spec.sizePx, bold = bold)
        val s = String(Character.toChars(cp))
        val key = (cp.toLong() shl 1) or (if (bold) 1L else 0L)
        val covered = coverCache.getOrPut(key) { text.covers(s, f) }
        val x = spec.cellX(col)
        if (!covered) {
            // the visible tofu box — a glyph the face lacks is SHOWN missing,
            // never dropped (the texture-cache table's idiom)
            val w = spec.cellX(col + Sgr.width(cp)) - x
            g.fillRect(x + 1, y + 1, maxOf(1, w - 2), 1, level)
            g.fillRect(x + 1, y + spec.cellH - 3, maxOf(1, w - 2), 1, level)
            g.fillRect(x + 1, y + 1, 1, spec.cellH - 3, level)
            g.fillRect(x + maxOf(2, w - 2), y + 1, 1, spec.cellH - 3, level)
            return
        }
        text.draw(g, x, y, s, f, level)
    }
}
