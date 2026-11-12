package wm.damage.core.shell

import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import wm.damage.core.text.DrawnStrings
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * Main — the dashboard and rest state (DESIGN.md §4.2). A fixed lens band with
 * the window list panning through it; three columns (icon · name dim · summary
 * brighter); numerics right-aligned; overflow gets the drawn continuation mark;
 * Settings is the last entry so one scroll up from the top lands on it.
 *
 * Two appearances: ACTIVE (full dashboard) and RESTING (lens + dim names only —
 * icons drop at rest, the exact fix BUD005 forced when row icons pushed resting
 * ink to 5.4 %).
 */
class MainSurface(
    private val text: TextRasterizer,
    private val windows: () -> List<DamageWindow>,
    private val onCommit: (DamageWindow) -> Unit,
    /** §4.2 Settings "Presence": 0 = rows away entirely at rest, 1 = dim names. */
    private val presence: () -> Int = { 1 },
    /** Theme icons (2026-09-01); null/miss falls back to the drawn set. */
    private val icons: () -> wm.damage.core.gfx.IconSource? = { null },
) {
    val model = ListModel()
    var resting = false

    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val fRowB = FontSpec(Face.SYSTEM, 18, bold = true)

    fun view(): WindowView.ListView = WindowView.ListView(
        model,
        rowCount = { windows().size },
        paintRow = ::paintRow,
        paintLens = ::paintLens,
        onCommit = { idx -> windows().getOrNull(idx)?.let(onCommit) },
    )

    fun focusedWindow(): DamageWindow? = windows().getOrNull(model.cursor)

    private fun paintRow(g: Gray8, index: Int, r: Rect, dim: Boolean) {
        val w = windows().getOrNull(index) ?: return
        if (dim && resting) {
            // resting (§4.2): presence 1 keeps dim names; presence 0 drops the
            // rows entirely — the ink-floor knob actually doing something
            if (presence() >= 1) draw(g, r.x + 40, r.y + 7, w.name.uppercase(), Level.REST, fSmall)
            return
        }
        wm.damage.core.gfx.IconPaint.draw(g, icons(), wm.damage.core.gfx.IconNames.forKind(w.icon),
            r.x + 12, r.y + 6, 20, w.icon, Level.DIM)
        draw(g, r.x + 40, r.y + 7, w.name.uppercase(), Level.DIM, fSmall)
        val s = w.summary()
        val unavailable = w.needs.isNotEmpty() && s.line.isEmpty()
        val summaryX = r.x + 160
        val maxW = r.right - 24 - summaryX - (if (s.progress != null) 152 else 0)
        // substituted ONCE, and that one string is measured, fitted and drawn (0.64 R1): the mark
        // below was decided on the raw summary while the substituted one was drawn, and the two
        // differ in width whenever the face lacks a character — a summary that fit raw was cut
        // with no mark. `Draw.dynamic` returns a covered string as it is, so the draw's own
        // substitution finds nothing left to do.
        val line = Draw.dynamic(text, if (unavailable) "unavailable offline" else s.line, fRow)
        drawFit(g, summaryX, r.y + 5, line, if (unavailable) Level.REST else Level.BODY, fRow, maxW)
        if (s.progress != null) Icons.blocks(g, r.right - 168, r.y + 12, 144, 6, s.progress, level = Level.of(4))
        if (s.more || text.measure(line, fRow) > maxW) Icons.tri(g, r.right - 16, r.y + 11, 11, Level.DIM)
    }

    private fun paintLens(g: Gray8, r: Rect, index: Int) {
        val w = windows().getOrNull(index) ?: return
        val s = w.summary()
        // BAND-HEIGHT icon (Adam, 2026-09-01 — DESIGN §4.5b revised): the
        // focused row's icon spans the 64 px lens at the switcher-class size,
        // "so it can be a better more visually appealing icon"
        wm.damage.core.gfx.IconPaint.draw(g, icons(), wm.damage.core.gfx.IconNames.forKind(w.icon),
            r.x + 8, r.y + 4, 56, w.icon, Level.HEAD)
        val tx0 = r.x + 72
        // Each string of the band is substituted ONCE, here, and that one string is what is
        // measured, fitted and drawn (0.64 R1). The three decisions below — where the name ends,
        // whether line one fits and where a right-aligned one starts, whether the detail is cut —
        // were taken on the raw string while `draw` drew the substituted one. A character the
        // face lacks that measures nothing by itself (a joiner, a variation selector, a control
        // code) becomes a `?` with an advance, so the drawn string is the wider one: a line one
        // placed by its raw width ran right of the band, into the rail's columns and past the
        // rect that is damaged, and a detail that fit raw was cut with no mark.
        val name = Draw.dynamic(text, w.name.uppercase(), fRowB)
        draw(g, tx0, r.y + 8, name, Level.HEAD, fRowB)
        // right-aligned first line, bounded so it can never overrun the name
        // (an unbounded draw was a silent-overlap finding); overflow gets the
        // drawn mark — the lens is the reveal, the mark says "more exists"
        val nameEnd = tx0 + text.measure(name, fRowB) + 16
        val l1Max = r.right - 16 - nameEnd
        val l1 = Draw.dynamic(text, s.line, fRow)
        if (text.measure(l1, fRow) <= l1Max) {
            drawRight(g, r.right - 16, r.y + 8, l1, Level.BODY, fRow)
        } else {
            drawFit(g, nameEnd, r.y + 8, l1, Level.BODY, fRow, l1Max - 16)
            Icons.tri(g, r.right - 14, r.y + 13, 11, Level.DIM)
        }
        // line two sits below line one's MEASURED ink (review §29): 34 at
        // 100 %, lower as the chrome face grows
        val y2 = Draw.lineBelow(text, fRowB, r.y + 8, r.y + 34)
        if (s.detail.isNotEmpty()) {
            // The detail ends 20 px short of whatever stands to its right, so a cut one has
            // room for its mark: the 4 px lead the mark is drawn at, the mark, and clear columns
            // after it. What stands to its right is the progress blocks' left edge when the row
            // has a progress, and the band's own right edge when it has none.
            // Until 0.64 the detail ran 12 px INTO the first block (the blocks are filled after
            // it) with its mark under that block, and with no progress it ran to the band's edge
            // with its mark in the rail's columns, where the next full repaint cleared it: a cut
            // with no mark either way (`CLAUDE.md`, No Truncation). Glyphs under a block also
            // keep the band out of the texture cache: measured on the simulator, 2.4 KB of
            // pixels where the same band ships as a 52 B base and three draws (`OverlayCachedPaintTest`);
            // the two 2.2 KB paints of Main on glass on 2026-11-10 are read as this, inferred —
            // the journal does not hold the string (`HANDOFF.md` §75.3).
            val edge = if (s.progress != null) r.right - 212 else r.right
            val dMax = edge - 20 - tx0
            val detail = Draw.dynamic(text, s.detail, fRow)
            drawFit(g, tx0, y2, detail, Level.BODY, fRow, dMax)
            if (text.measure(detail, fRow) > dMax) Icons.tri(g, tx0 + dMax + 4, y2 + 5, 11, Level.DIM)
        }
        if (s.progress != null) Icons.blocks(g, r.right - 212, y2 + 6, 196, 8, s.progress)
    }

    private fun draw(g: Gray8, x: Int, y: Int, str: String, lv: Int, f: FontSpec) {
        // Main's rows carry EXTERNAL text (summaries from tmux sessions, book
        // titles): substitute-not-throw, like Chrome (review 2026-09-01 L1 —
        // a CJK session name threw mid-repaint and left the content half
        // painted). Our own symbol literals stay guarded by tools/lint.py.
        text.draw(g, (x / 4) * 4, (y / 2) * 2, Draw.dynamic(text, str, f), f, lv)
    }

    private fun drawRight(g: Gray8, xRight: Int, y: Int, str: String, lv: Int, f: FontSpec) =
        draw(g, xRight - text.measure(str, f), y, str, lv, f)

    /** Draw as much as fits; the caller adds the continuation mark. The full
     *  text is always REACHABLE (focus the row and the lens shows it) — that is
     *  what NO TRUNCATION requires; the mark advertises the rest (§4.2). */
    private fun drawFit(g: Gray8, x: Int, y: Int, str: String, lv: Int, f: FontSpec, maxW: Int) {
        // SANITIZE FIRST, then fit: the old order measured the raw string and
        // drew the substituted one, so the fit was computed against glyphs
        // that were never drawn. The cut is the shared code-point-boundary
        // binary search (Draw.prefix) — a walk from the end measured O(n²)
        // substrings of a long summary on the shell loop.
        val s = Draw.dynamic(text, str, f)
        draw(g, x, y, Draw.prefix(text, s, f, maxW), lv, f)
    }
}
