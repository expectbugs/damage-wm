package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MainSurface
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.WindowView
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * APK 0.64 R1 (RB-5) — Main measures the string it draws.
 *
 * Main's rows carry text from outside (a tmux session's name, a track's title), drawn through
 * `Draw.dynamic`: a character the face has no glyph for becomes a visible `?`. Three sites decided
 * on the RAW string and drew the substituted one — the row's summary and the lens's detail (does
 * it fit, does the cut get its mark) and the lens's first line (where a right-aligned string
 * starts). The two differ in width exactly when the face lacks a character, and most when that
 * character measures nothing by itself (a joiner, a variation selector, a control code): the
 * string grows by a `?` for each. A summary or a detail that fit raw was then cut with no mark
 * (`CLAUDE.md`, No Truncation: a cut is advertised), and a first line was placed by its raw
 * width and ran right of the band — into the rail's columns, and past the rect that is damaged
 * once the difference passed 28 px.
 *
 * Each site substitutes once and uses that one string for the measure, the fit and the draw.
 * A unit test on `MainSurface.view()`'s two painters, over a face made for it; each case was
 * watched to fail on the tree before the fix (`research/mutations-76-r1.json`, R1a-20 … 23).
 */
class MainLensTest {

    /**
     * A face with no glyph for U+200D (a joiner). By itself the code measures NOTHING and is not
     * covered; every other character, the `?` that takes its place included, advances 8 px and
     * inks a 6 x 10 block. A string with joiners in it is wider once it has been substituted.
     */
    private class JoinerFace : TextRasterizer {
        /** Every string handed to [draw], in order (R3's notice pin reads what was DRAWN). */
        val drawn = ArrayList<String>()
        private fun adv(ch: Char) = if (ch == JOINER) 0 else 8
        override fun measure(text: String, font: FontSpec): Int = text.sumOf { adv(it) }
        override fun metrics(font: FontSpec) = FontMetrics(12, 4, 16)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
            drawn.add(text)
            var px = x
            for (ch in text) {
                if (adv(ch) > 0 && ch != ' ') surface.fillRect(px, y + 2, 6, 10, level)
                px += adv(ch)
            }
        }
        override fun covers(text: String, font: FontSpec) = JOINER !in text
    }

    private class Card(name: String, private val s: Summary) : DamageWindow("card", name, IconKind.FILES) {
        private val model = ListModel()
        override fun view(): WindowView = WindowView.ListView(model, { 1 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = s
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private val layout = Layout()
    /** The lens band as `ContentKit` hands it to the painter: the lens less the rail. */
    private val band = Rect(layout.lens.x, layout.lens.y, layout.lens.w - Layout.RAIL_W, layout.lens.h)
    /** A row slot under the lens, as `ContentKit.rowRect` makes it. */
    private val row = Rect(layout.content.x, layout.lens.bottom, layout.content.w - Layout.RAIL_W, layout.rowH)

    private fun main(name: String, s: DamageWindow.Summary): WindowView.ListView =
        MainSurface(JoinerFace(), { listOf<DamageWindow>(Card(name, s)) }, {}).view()

    /** [n] characters the face has, with [joiners] joiners spread among them: [n] x 8 px raw,
     *  ([n] + [joiners]) x 8 px once each joiner is a `?`. No spaces, so every advance is inked. */
    private fun text(n: Int, joiners: Int): String = buildString {
        val every = n / (joiners + 1)
        for (i in 0 until n) {
            append('a' + i % 26)
            if (joiners > 0 && (i + 1) % every == 0 && (i + 1) / every <= joiners) append(JOINER)
        }
    }.also { s -> check(s.count { it == JOINER } == joiners && s.length == n + joiners) }

    /** The continuation mark (`Icons.tri`, 11 high: nine rows of 2..6..2 px) with its first
     *  column at [x], somewhere in [rows]: the lit widths of its nine rows over [x] until [edge]. */
    private fun markAt(g: Gray8, x: Int, edge: Int, rows: IntRange): List<Int> {
        val top = rows.firstOrNull { y -> g[x, y] != 0 } ?: return emptyList()
        return (0 until 9).map { i -> (x until edge).count { g[it, top + i] != 0 } }
    }

    private val wholeMark = listOf(2, 3, 4, 5, 6, 5, 4, 3, 2)

    /** The lit columns of [g] at or right of [x0] over [rows]. */
    private fun litRightOf(g: Gray8, x0: Int, rows: IntRange): Int =
        (x0 until g.w).count { x -> rows.any { y -> g[x, y] != 0 } }

    /** The last lit column of [g] left of [x1] over [rows], or -1. */
    private fun lastLitBefore(g: Gray8, x1: Int, rows: IntRange): Int =
        (0 until x1).lastOrNull { x -> rows.any { y -> g[x, y] != 0 } } ?: -1

    // ------------------------------------------------------------------ the lens's detail

    /** A detail that fits the band as it came and not as it is drawn: 480 px raw against the 504
     *  the band gives a detail with no progress beside it, 560 px with its ten joiners shown. It
     *  is cut to fit — and the cut has its mark, 16 px left of the band's edge.
     *  Before: the mark was decided on the raw width, so the cut had none. */
    @Test
    fun aDetailThatOnlyFitsBeforeSubstitutionIsCutWithItsMark() {
        val detail = text(60, joiners = 10)
        val g = Gray8(640, 480)
        main("MUSIC", DamageWindow.Summary("playing", detail = detail)).paintLens(g, band, 0)
        val edge = band.right
        val line2 = (band.y + 34) until (band.bottom - 2)
        assertEquals(wholeMark, markAt(g, edge - 16, edge, line2), "the cut detail's mark stands whole at x=${edge - 16}")
        val end = lastLitBefore(g, edge - 16, line2)
        assertTrue(end in (edge - 20 - 16)..(edge - 21), "the detail is cut to the band less its mark's room: its ink ends at x=$end, the mark starts at ${edge - 16}")
    }

    /** The other way stays as it was: a detail that fits as drawn has no mark. */
    @Test
    fun aDetailThatFitsAsDrawnHasNoMark() {
        val detail = text(56, joiners = 6)                        // 448 px raw, 496 px drawn, 504 px of room
        val g = Gray8(640, 480)
        main("MUSIC", DamageWindow.Summary("playing", detail = detail)).paintLens(g, band, 0)
        val line2 = (band.y + 34) until (band.bottom - 2)
        assertEquals(emptyList(), markAt(g, band.right - 16, band.right, line2), "no mark for a detail that was not cut")
        assertEquals(band.x + 72 + 62 * 8 - 3, lastLitBefore(g, 640, line2), "all 62 characters are drawn")
    }

    // ------------------------------------------------------------------ the lens's first line

    /** A first line that fits beside the name both ways and is wider as drawn: 400 px raw, 448 px
     *  with its six joiners shown, 452 px of room. It is right-aligned by the width it is drawn
     *  at and ends 16 px left of the band's edge.
     *  Before: it was placed by its raw width and its last 48 px ran past that edge. */
    @Test
    fun aFirstLineIsRightAlignedByTheWidthItIsDrawnAt() {
        val line = text(50, joiners = 6)
        val g = Gray8(640, 480)
        main("MUSIC", DamageWindow.Summary(line)).paintLens(g, band, 0)
        val line1 = (band.y + 8) until (band.y + 8 + 16)
        assertEquals(0, litRightOf(g, band.right - 16, line1), "nothing of line one is lit at or right of x=${band.right - 16}")
        assertEquals(band.right - 16 - 3, lastLitBefore(g, 640, line1), "the line's last glyph ends at the right-aligned edge")
        // 56 glyphs of 8 px, the first at the edge less the drawn width (on the 4 px grid)
        val first = (0 until 640).first { x -> line1.any { y -> g[x, y] != 0 } && x > band.x + 72 + 5 * 8 }
        assertEquals(band.right - 16 - 56 * 8, first, "and its first glyph starts where 56 advances put it")
    }

    /** A first line that fits beside the name as it came and not as it is drawn: 400 px raw,
     *  496 px with twelve joiners shown, 452 px of room. It is cut, from the name's side, and gets
     *  its mark at the band's right.
     *  Before: it took the fitting arm, started where its raw width put it and ran 96 px past
     *  the edge — over the rail's columns and off the band. */
    @Test
    fun aFirstLineThatOnlyFitsBeforeSubstitutionIsCutWithItsMark() {
        val line = text(50, joiners = 12)
        val g = Gray8(640, 480)
        main("MUSIC", DamageWindow.Summary(line)).paintLens(g, band, 0)
        val line1 = (band.y + 8) until (band.y + 8 + 16)
        assertEquals(wholeMark, markAt(g, band.right - 14, band.right, line1), "the cut line's mark stands whole at x=${band.right - 14}")
        assertEquals(0, litRightOf(g, band.right, line1), "nothing is lit past the band's right edge (x=${band.right})")
        val nameEnd = band.x + 72 + 5 * 8 + 16
        val end = lastLitBefore(g, band.right - 14, line1)
        assertTrue(end < band.right - 16 - 16, "the line is cut short of its mark: its ink ends at x=$end")
        assertTrue(end > nameEnd + 400, "and is cut, not dropped: its ink runs from the name to x=$end")
    }

    // ------------------------------------------------------------------ a row's summary

    /** The same in a row: a summary that fits its column as it came (360 px against 412) and not
     *  as it is drawn (440 px) is cut with its mark at the row's right.
     *  Before: cut with no mark. */
    @Test
    fun aRowsSummaryThatOnlyFitsBeforeSubstitutionIsCutWithItsMark() {
        val line = text(45, joiners = 10)
        val g = Gray8(640, 480)
        main("MUSIC", DamageWindow.Summary(line)).paintRow(g, 0, row, false)
        val rows = row.y until row.bottom
        assertEquals(wholeMark, markAt(g, row.right - 16, row.right, rows), "the cut summary's mark stands whole at x=${row.right - 16}")
        val end = lastLitBefore(g, row.right - 16, rows)
        assertTrue(end <= row.right - 24, "the summary is cut inside its column: its ink ends at x=$end")
    }

    // ------------------------------------------------------------------ the name beside line one

    /** The lens's name is drawn through the same substitution, and line one's room is measured
     *  from where the name ENDS: a name with joiners in it (no window has one today; a name is
     *  the window's own literal) ends where it is drawn to, and line one starts 16 px after it. */
    @Test
    fun lineOnesRoomIsMeasuredFromTheNameAsDrawn() {
        val name = "AB" + JOINER + JOINER + JOINER + "CD"                 // 32 px raw, 56 px drawn
        val line = text(64, joiners = 0)                                   // 512 px: cut either way, from the name's end
        val g = Gray8(640, 480)
        main(name, DamageWindow.Summary(line)).paintLens(g, band, 0)
        val line1 = (band.y + 8) until (band.y + 8 + 16)
        val tx0 = band.x + 72
        val nameInkEnd = tx0 + 7 * 8 - 3                                  // seven glyphs as drawn
        // the first unlit gap after the name is the 16 px between it and line one
        val gapStart = (tx0 until 640).first { x -> line1.none { y -> g[x, y] != 0 } && x > nameInkEnd }
        val lineStart = (gapStart until 640).first { x -> line1.any { y -> g[x, y] != 0 } }
        assertEquals(tx0 + 7 * 8 + 16, lineStart, "line one starts 16 px after the name as it is drawn")
    }

    // ------------------------------------------------------------------ a notice's body (R3, 2A-1)

    /** A notice's body is wrapped on the string that is DRAWN. `Notifications.bodyLines` wrapped
     *  the raw body and `paint` drew each line through `Draw.dynamic`: a joiner (0 px raw, a
     *  `?` of 8 px drawn) in a line that filled the 232 px body width came back 8 px wider, and
     *  `Draw.fit` cut its tail behind the mark. The cut word was on no other line — lost from the
     *  notice. Here line one is 29 advances raw (232 px, exactly the width) with one joiner in it:
     *  every word of the body, as drawn, must be whole on some drawn line. */
    @Test
    fun aNoticeBodyIsWrappedOnTheStringItDraws() {
        val face = JoinerFace()
        val n = Notifications(face)
        val l = Layout()
        val body = "aaaaaaaaa" + JOINER + " bbbbbbbbbb cccccccc dddd"   // line one: 29 x 8 = 232 px raw
        n.post(Notifications.Notice("SMS · X", "t", body, "12:00"), l)
        repeat(6) { n.stepUnfurl(l, silent = false) }
        face.drawn.clear()
        val g = Gray8(640, 480)
        n.paint(g, l, silent = false)!!
        val words = body.replace(JOINER, '?').split(' ')
        val lost = words.filter { w -> face.drawn.none { w in it } }
        assertEquals(emptyList(), lost, "words of the body on no drawn line (drawn: ${face.drawn})")
    }

    private companion object {
        const val JOINER = '‍'
    }
}
