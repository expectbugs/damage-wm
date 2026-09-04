package wm.damage.core.windows.tmux

import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * The FLOW renderer — the grid retirement (Adam, 2026-08-31, superseding
 * TMUX.md verdict 2): terminal output is TEXT, presented with real typography
 * instead of a cell lattice. The provider captures with `-J` so tmux's own
 * wrap points dissolve into LOGICAL lines; this renderer wraps them at the
 * content width through the per-app style transform — so Font / Font size /
 * Font style all do exactly what they say — and draws them tail-anchored,
 * with SGR colours as levels, backgrounds as run fills, and runs of rule
 * characters collapsed into drawn horizontal rules (the shell's own motif).
 *
 * The GRID renderer (TermRender) survives only as the alternate-screen
 * fallback: when `#{alternate_on}` says a full-screen TUI owns the pane,
 * flowed text would lie about it. TmuxWindow picks per frame.
 *
 * NO TRUNCATION: wrapping keeps every character — a break lands after the
 * last space that fits, a spaceless overflow hard-breaks with guaranteed
 * progress, and indentation is preserved verbatim (terminal output means its
 * leading spaces). The 16 px side pads absorb the one legal overshoot (a
 * single glyph wider than the wrap width ships whole, the Wrap.kt rule).
 *
 * The §17.2 divider-bleed guard applies here as everywhere: a line whose box
 * (+2 px of AA slack) would cross rect.bottom is not drawn.
 */
class FlowRender(private val text: TextRasterizer) {

    /** One display line after wrapping: styled pieces, or a collapsed rule. */
    sealed class DLine {
        class Text(val runs: List<Sgr.Run>, val width: Int) : DLine()
        object Rule : DLine()
    }

    data class HistView(val offset: Int, val maxOffset: Int)

    /** Bumped by [invalidate]; keys every cache so a style/layout change can
     *  never serve a stale wrap (the TermRender lesson, twice over). */
    private var epoch = 0
    private var lineHCache = -1

    /** Wrapped layouts, keyed by (source list identity, width, epoch) — one
     *  slot for the live tail, one for history. */
    private class Slot(val key: Triple<List<String>, Int, Int>, val lines: List<DLine>)
    private var live: Slot? = null
    private var hist: Slot? = null

    fun invalidate() {
        epoch++
        lineHCache = -1
        live = null
        hist = null
    }

    private fun spec(bold: Boolean = false) = FontSpec(Face.MONO, BASE_SIZE, bold = bold)

    /** One line box for the whole view (bold included), even per §2.4 rule 7. */
    fun lineH(): Int {
        if (lineHCache < 0) {
            val h = maxOf(text.metrics(spec()).lineHeight, text.metrics(spec(true)).lineHeight)
            lineHCache = if (h % 2 == 0) h else h + 1
        }
        return lineHCache
    }

    // ------------------------------------------------------------------ layout
    private fun layout(lines: List<String>, widthPx: Int, slot: Slot?, keep: (Slot) -> Unit): List<DLine> {
        val key = Triple(lines, widthPx, epoch)
        slot?.let { if (it.key.first === lines && it.key.second == widthPx && it.key.third == epoch) return it.lines }
        val out = ArrayList<DLine>(lines.size + 8)
        for (line in lines) {
            // SANITIZE before wrapping, the way the Files viewer does: terminal
            // output is external text, and JetBrains Mono has no glyph for a
            // fair amount of what a modern TUI prints — the live panes on this
            // machine carry U+23BF/U+23F5/U+2722/U+273B from Claude Code's own
            // interface (review 2026-09-03). Raw, those were silent tofu boxes.
            // Doing it HERE (not at draw time) keeps measure and draw on the
            // same string, so the wrap stays exact.
            val runs = Sgr.parseRuns(line).map { r ->
                if (r.text.isEmpty()) r
                else Sgr.Run(wm.damage.core.shell.Draw.dynamic(text, r.text, spec(r.flags and Sgr.BOLD != 0)),
                    r.fg, r.bg, r.flags)
            }
            val plain = runs.joinToString("") { it.text }
            if (isRule(plain)) { out.add(DLine.Rule); continue }
            wrapStyled(runs, widthPx, out)
        }
        keep(Slot(key, out))
        return out
    }

    /** A logical line that IS a horizontal rule (tmux/CLI separators, CC's
     *  box edges between sections): collapsed to a drawn rule — reading
     *  structure, not a row of glyphs. Conservative: 4+ rule characters and
     *  nothing else but spaces. */
    private fun isRule(plain: String): Boolean {
        val t = plain.trim()
        return t.length >= 4 && t.all { it in RULE_CHARS }
    }

    /**
     * Greedy styled wrap: break after the last space that fits; a spaceless
     * overflow hard-breaks at the widest fitting prefix (never fewer than one
     * character — progress is guaranteed, an oversize glyph ships whole).
     * Widths are measured per styled piece through the live transform, so
     * what measured is what draws.
     */
    private fun wrapStyled(runs: List<Sgr.Run>, widthPx: Int, out: MutableList<DLine>) {
        if (runs.isEmpty() || runs.all { it.text.isEmpty() }) {
            out.add(DLine.Text(emptyList(), 0))
            return
        }
        // flatten to (char, run-style-index) so breaks can cross run seams
        val styles = ArrayList<Sgr.Run>(runs.size)
        val chars = StringBuilder()
        val styleOf = ArrayList<Int>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            styles.add(r)
            for (ch in r.text) { chars.append(ch); styleOf.add(styles.size - 1) }
        }
        val n = chars.length
        fun widthOf(from: Int, to: Int): Int {
            var w = 0
            var i = from
            while (i < to) {
                val s = styleOf[i]
                var j = i + 1
                while (j < to && styleOf[j] == s) j++
                w += text.measure(chars.substring(i, j), spec(styles[s].flags and Sgr.BOLD != 0))
                i = j
            }
            return w
        }
        var pos = 0
        while (pos < n) {
            // widest end with width <= widthPx, by doubling + binary search
            var lo = pos + 1
            var hi = n
            if (widthOf(pos, hi) > widthPx) {
                var step = 1
                var last = pos
                while (last + step < n && widthOf(pos, last + step) <= widthPx) { last += step; step *= 2 }
                lo = last
                hi = minOf(n, last + step)
                while (lo + 1 < hi) {
                    val mid = (lo + hi) / 2
                    if (widthOf(pos, mid) <= widthPx) lo = mid else hi = mid
                }
            } else lo = n
            var end = maxOf(lo, pos + 1)              // progress even when char 1 overflows
            if (end < n) {
                // prefer the last space INSIDE the fitted stretch; the break
                // eats it (terminal indentation before pos stays verbatim)
                var sp = -1
                for (i in end - 1 downTo pos + 1) if (chars[i] == ' ') { sp = i; break }
                if (sp > pos) {
                    emitPiece(chars, styleOf, styles, pos, sp, out)
                    pos = sp + 1
                    continue
                }
            }
            emitPiece(chars, styleOf, styles, pos, end, out)
            pos = end
        }
    }

    private fun emitPiece(chars: StringBuilder, styleOf: List<Int>, styles: List<Sgr.Run>,
        from: Int, to: Int, out: MutableList<DLine>) {
        val pieces = ArrayList<Sgr.Run>(2)
        var w = 0
        var i = from
        while (i < to) {
            val s = styleOf[i]
            var j = i + 1
            while (j < to && styleOf[j] == s) j++
            val src = styles[s]
            val t = chars.substring(i, j)
            pieces.add(Sgr.Run(t, src.fg, src.bg, src.flags))
            w += text.measure(t, spec(src.flags and Sgr.BOLD != 0))
            i = j
        }
        out.add(DLine.Text(pieces, w))
    }

    // ------------------------------------------------------------------ paint
    /** The LIVE tail: the last display lines that fit, top-down — terminal
     *  behaviour (a fresh session sits at the top; a full one shows the tail
     *  with the newest line at the bottom). A small underscore marker stands
     *  in for the cursor at the end of the tail. */
    fun renderTail(g: Gray8, rect: Rect, frame: PaneFrame) {
        val widthPx = rect.w - 2 * PAD_X
        val all = layout(frame.lines, widthPx, live) { live = it }
        val lh = lineH()
        // the -2 keeps `fit` in agreement with the bleed guard below: without
        // it an exact-fit view would count one more line than draws — and the
        // guard would drop the NEWEST line, the prompt
        val fit = maxOf(1, (rect.h - PAD_TOP - 2) / lh)
        val shown = if (all.size <= fit) all else all.subList(all.size - fit, all.size)
        var y = rect.y + PAD_TOP
        var lastText: DLine.Text? = null
        var lastY = y
        for (dl in shown) {
            if (y + lh + 2 > rect.bottom) break            // the divider-bleed guard
            draw(g, dl, rect.x + PAD_X, y, widthPx)
            if (dl is DLine.Text) { lastText = dl; lastY = y }
            y += lh
        }
        if (frame.cursorVisible && lastText != null) {
            val cx = rect.x + PAD_X + minOf(lastText.width + 4, widthPx)
            g.fillRect(cx, lastY + lh - 4, maxOf(4, lh / 3), 2, Level.BODY)
        }
    }

    /** Frozen scrollback through the SAME flow: [offset] display lines back
     *  from the live edge, a slim position rail at the right edge. */
    fun renderHistory(g: Gray8, rect: Rect, lines: List<String>, offset: Int): HistView {
        val widthPx = rect.w - 2 * PAD_X
        val all = layout(lines, widthPx, hist) { hist = it }
        val lh = lineH()
        val fit = maxOf(1, (rect.h - PAD_TOP - 2) / lh)   // agrees with the bleed guard
        val maxOffset = maxOf(0, all.size - fit)
        val at = offset.coerceIn(0, maxOffset)
        val end = all.size - at
        val start = maxOf(0, end - fit)
        var y = rect.y + PAD_TOP
        for (i in start until end) {
            if (y + lh + 2 > rect.bottom) break
            draw(g, all[i], rect.x + PAD_X, y, widthPx)
            y += lh
        }
        if (maxOffset > 0) {
            val track = rect.h - 8
            val span = maxOf(16, track * fit / all.size)
            val ty = rect.y + 4 + ((track - span) * (maxOffset - at).toDouble() / maxOffset).toInt()
            g.fillRect(rect.right - 6, rect.y + 4, 3, track, Level.FAINT)
            g.fillRect(rect.right - 6, ty, 3, span, Level.DIM)
        }
        return HistView(at, maxOffset)
    }

    private fun draw(g: Gray8, dl: DLine, x0: Int, y: Int, widthPx: Int) {
        val lh = lineH()
        if (dl is DLine.Rule) {
            g.fillRect(x0, y + lh / 2 - 1, widthPx, 2, Level.DIM)
            return
        }
        val t = dl as DLine.Text
        var x = x0
        // backgrounds first so glyph AA composites onto them
        for (r in t.runs) {
            val f = spec(r.flags and Sgr.BOLD != 0)
            val w = text.measure(r.text, f)
            val bg = if (r.flags and Sgr.REVERSE != 0) r.fg else r.bg
            if (bg > 0) g.fillRect(x, y, w, lh, Level.of(bg))
            x += w
        }
        x = x0
        for (r in t.runs) {
            val f = spec(r.flags and Sgr.BOLD != 0)
            val w = text.measure(r.text, f)
            var fg = if (r.flags and Sgr.REVERSE != 0) r.bg else r.fg
            if (r.flags and Sgr.BOLD != 0) fg = minOf(15, fg + 2)
            if (r.flags and Sgr.DIM != 0) fg = maxOf(1, fg - 3)
            if (r.text.isNotBlank()) text.draw(g, x, y + 1, r.text, f, Level.of(fg))
            if (r.flags and Sgr.UNDERLINE != 0) g.fillRect(x, y + lh - 2, w, 1, Level.of(fg))
            x += w
        }
    }

    companion object {
        /** Nominal size of the flow view before the per-app transform — the
         *  design point (JBM ~16 px em, ~60 chars per 576 px line); the user's
         *  Font size scales it for real, which is the point of the rework. */
        const val BASE_SIZE = 16

        const val PAD_X = 16
        const val PAD_TOP = 6

        /** Characters a separator line is made of (box-drawing horizontals +
         *  the ASCII rules CLI tools draw). */
        // These are characters to RECOGNISE in a pane, never to draw: a
        // matching line is replaced by a drawn rule (TMUX.md §18).
        // lint:allow-symbols
        private const val RULE_CHARS = "─━═╌╍┄┅┈┉—-=_"
    }
}
