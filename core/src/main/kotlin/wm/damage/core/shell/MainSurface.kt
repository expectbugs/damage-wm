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
            // resting: dim names only, no icons, no summaries (§4.2)
            draw(g, r.x + 40, r.y + 7, w.name.uppercase(), Level.REST, fSmall)
            return
        }
        Icons.draw(g, r.x + 12, r.y + 6, 20, 20, w.icon, Level.DIM)
        draw(g, r.x + 40, r.y + 7, w.name.uppercase(), Level.DIM, fSmall)
        val s = w.summary()
        val unavailable = w.needs.isNotEmpty() && s.line.isEmpty()
        val summaryX = r.x + 160
        val maxW = r.right - 24 - summaryX - (if (s.progress != null) 152 else 0)
        val line = if (unavailable) "unavailable offline" else s.line
        drawFit(g, summaryX, r.y + 5, line, if (unavailable) Level.REST else Level.BODY, fRow, maxW)
        if (s.progress != null) Icons.blocks(g, r.right - 168, r.y + 12, 144, 6, s.progress, level = Level.of(4))
        if (s.more || text.measure(line, fRow) > maxW) Icons.tri(g, r.right - 16, r.y + 11, 11, Level.DIM)
    }

    private fun paintLens(g: Gray8, r: Rect, index: Int) {
        val w = windows().getOrNull(index) ?: return
        val s = w.summary()
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, w.icon, Level.HEAD)
        draw(g, r.x + 44, r.y + 8, w.name.uppercase(), Level.HEAD, fRowB)
        // right-aligned first line, full-width second line — the two 32 px lines
        val l1 = s.line
        drawRight(g, r.right - 16, r.y + 8, l1, Level.BODY, fRow)
        if (s.detail.isNotEmpty()) {
            drawFit(g, r.x + 44, r.y + 34, s.detail, Level.BODY, fRow, r.right - 60 - (if (s.progress != null) 200 else 0))
        }
        if (s.progress != null) Icons.blocks(g, r.right - 212, r.y + 40, 196, 8, s.progress)
    }

    private fun draw(g: Gray8, x: Int, y: Int, str: String, lv: Int, f: FontSpec) {
        DrawnStrings.check(str, text, f)
        text.draw(g, (x / 4) * 4, (y / 2) * 2, str, f, lv)
    }

    private fun drawRight(g: Gray8, xRight: Int, y: Int, str: String, lv: Int, f: FontSpec) =
        draw(g, xRight - text.measure(str, f), y, str, lv, f)

    /** Draw as much as fits; the caller adds the continuation mark. The full
     *  text is always REACHABLE (focus the row and the lens shows it) — that is
     *  what NO TRUNCATION requires; the mark advertises the rest (§4.2). */
    private fun drawFit(g: Gray8, x: Int, y: Int, str: String, lv: Int, f: FontSpec, maxW: Int) {
        if (text.measure(str, f) <= maxW) { draw(g, x, y, str, lv, f); return }
        var n = str.length
        while (n > 0 && text.measure(str.take(n), f) > maxW) n--
        draw(g, x, y, str.take(n), lv, f)
    }
}
