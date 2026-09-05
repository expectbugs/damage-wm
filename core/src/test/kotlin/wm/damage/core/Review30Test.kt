package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.Chrome
import wm.damage.core.shell.MenuSurface
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.WindowView
import wm.damage.core.text.Face
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.windows.files.FEntry
import wm.damage.core.windows.files.FLocation
import wm.damage.core.windows.files.FStat
import wm.damage.core.windows.files.FTrashEntry
import wm.damage.core.windows.files.FilesProvider
import wm.damage.core.windows.files.FilesWindow
import wm.damage.core.windows.files.PdfInfo
import wm.damage.core.windows.files.TextChunk
import wm.damage.core.windows.games.GamesWindow

/**
 * The fifth whole-codebase review (`HANDOFF.md` §30) — one pin per verified
 * defect, each failing against the unfixed tree.
 */
class Review30Test {

    /**
     * A rasterizer with Clear Sans's MEASURED numbers (the real AWT
     * rasterizer, 2026-09-04: 12 → 15 + 4, 13 → 16 + 4, 16 → 19 + 5,
     * 17 → 20 + 5, 18 → 21 + 6) that inks the way a real face does: a string
     * with no descenders stops at the BASELINE, one with a descender fills
     * ascent + descent. That distinction is the whole point — a band sized to
     * the ascent holds caps and lets descenders hang, and a fake that always
     * fills one or the other cannot tell the two cases apart.
     *
     * Its reported line HEIGHT is one row SHORT of ascent + descent, which is
     * what Clear Sans actually reports (13 inks 20 rows in a 19 px line) and
     * the reason `metrics().lineHeight` is never a line box.
     */
    private class CapText : TextRasterizer {
        private val measured = mapOf(11 to (13 to 4), 12 to (15 to 4), 13 to (16 to 4), 14 to (17 to 5),
            15 to (18 to 5), 16 to (19 to 5), 17 to (20 to 5), 18 to (21 to 6), 19 to (22 to 6),
            21 to (25 to 7), 26 to (31 to 8), 36 to (43 to 11))
        fun asc(f: FontSpec) = measured[f.sizePx]?.first ?: Math.round(f.sizePx * 7.0 / 6).toInt()
        fun desc(f: FontSpec) = measured[f.sizePx]?.second ?: Math.round(f.sizePx / 3.0).toInt()
        fun ink(f: FontSpec) = asc(f) + desc(f)
        override fun measure(text: String, font: FontSpec): Int = text.length * (font.sizePx / 2 + 1)
        override fun metrics(font: FontSpec) =
            FontMetrics(asc(font), desc(font), maxOf(1, asc(font) + desc(font) - 1))
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
            if (text.isBlank()) return
            val deep = text.any { it in DESCENDERS }
            surface.fillRect(x, y, measure(text, font), if (deep) ink(font) else asc(font), level)
        }
        override fun covers(text: String, font: FontSpec) = true

        companion object {
            /** The characters that put ink below the baseline. */
            const val DESCENDERS = "gjpqy,;()[]{}/@"
        }
    }

    private fun rowsOf(g: Gray8, r: Rect): List<Set<Int>> =
        (r.y until r.bottom).map { y -> (r.x until r.right).map { x -> g[x, y] }.toSet() }

    // ================================================================ §30 #1
    /**
     * The notification box's top rule STRUCK THROUGH its own source line, the
     * queue badge and the timestamp.
     *
     * The band is 16 px and Clear Sans 13 bold inks 20 (ascent 16 + descent
     * 4); drawn at the constant `+2` the caps ran to row 17 and the rule sits
     * at 16..17. Visible in `snapshots/08-notification-focused.png` as a line
     * through "SMS · MOM" and a bisected "14:32", where `design/shots/
     * notification.png` — the reference this was ported from — has all three
     * clear of it. The source row is now placed from the MEASURED ascent so
     * its baseline lands on the band's last row.
     */
    @Test
    fun theNotificationRuleDoesNotStrikeItsSourceLine() {
        val tx = CapText()
        val n = Notifications(tx)
        val l = Layout()
        n.post(Notifications.Notice("SMS · MOM", "MOM", "on my way, should be there", "14:32"), l)
        n.post(Notifications.Notice("SMS · DAD", "DAD", "another", "14:33"), l)   // a +N badge
        repeat(6) { n.stepUnfurl(l, silent = false) }
        n.takeFocus()
        val g = Gray8(640, 480)
        val box = n.paint(g, l, silent = false)!!
        val full = n.fullRect(n.current!!, l, silent = false)
        assertEquals(full, box, "the box should be fully unfurled")
        // the source rule is the first full-width run of its own level; none
        // of its rows may carry anything else
        val ruleRows = (full.y until full.bottom).filter { y ->
            (full.x until full.right).all { x -> g[x, y] == Level.DIM }
        }
        assertTrue(ruleRows.isNotEmpty(), "the top rule must span the box")
        val top = ruleRows.first()
        for (y in top until top + 2) {
            val vals = (full.x until full.right).map { x -> g[x, y] }.toSet()
            assertEquals(setOf(Level.DIM), vals,
                "row $y of the box is the source rule — the source line, the badge or the " +
                    "timestamp is drawn through it")
        }
        // the source line really is drawn above it (a pin that passes on an
        // empty box is no pin)
        assertTrue((full.y until top).any { y -> (full.x until full.right).any { g[it, y] != 0 } },
            "the source line must be visible above its rule")
    }

    // ================================================================ §30 #2
    /**
     * The context menu's title rule struck through the title the same way —
     * `snapshots/13-files-menu.png` shows the rule across the bottom of
     * "BIN". The title is placed from its measured ascent now, and the rule
     * is painted BEFORE it so a future regression shows as ink on the rule
     * rather than as a silent clip.
     */
    @Test
    fun theMenuRuleDoesNotStrikeItsTitle() {
        val tx = CapText()
        val m = MenuSurface(tx)
        m.openWith(MenuSurface.Spec("bin", listOf(MenuSurface.Item("Open"), MenuSurface.Item("Delete")), onCommit = {}))
        val l = Layout()
        val g = Gray8(640, 480)
        val box = m.paint(g, l)!!
        val ruleRow = (box.y until box.bottom).first { y ->
            (box.x + 8 until box.right - 8).all { x -> g[x, y] == Level.FAINT }
        }
        for (y in ruleRow until ruleRow + 2) {
            val vals = (box.x + 8 until box.right - 8).map { g[it, y] }.toSet()
            assertEquals(setOf(Level.FAINT), vals, "row $y is the title rule — the title is drawn through it")
        }
        // …and count the title's own ink, which catches the defect whichever
        // way round the two are painted: a rule drawn LAST simply eats the
        // rows it lands on, and the rule-row check above cannot see that
        var dim = 0
        for (y in box.y until box.bottom) for (x in box.x until box.right) if (g[x, y] == Level.DIM) dim++
        val bracketing = 4 * box.w                       // the two 2 px box rules
        val title = tx.measure("BIN", FontSpec(Face.SYSTEM, 13, bold = true)) * tx.asc(FontSpec(Face.SYSTEM, 13, bold = true))
        assertEquals(bracketing + title, dim,
            "the title's ink is ${dim - bracketing} px, not $title — the rule ate some of it")
    }

    // ================================================================ §30 #3
    /**
     * Games' documents sized their line box from `metrics().lineHeight`,
     * which for Clear Sans is one to two rows SHORTER than the ink, and they
     * mix a 17 px bold heading into a 13/16 px body. Every line drew past its
     * own line rect — and `Shell.paintDocSlice` renders each line into a
     * buffer exactly one line box tall, so the first scroll chopped the
     * descenders off every row and the settle repaint baked the chop in.
     */
    @Test
    fun gamesDocumentsHoldTheInkTheyDraw() {
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val tx = CapText()
            val w = GamesWindow(tx, bg) { 1_700_000_000_000L }
            w.onActivate(ProbeServices, ActivationSource.MAIN)
            // the root list → Bankroll (a document), then Standings → a character
            val root = w.view() as WindowView.ListView
            val bank = (0 until root.rowCount()).first { i ->
                w.let { _ -> root.model.cursor = i; w.rootRow == "Bankroll" }
            }
            root.onCommit(bank)
            assertNoInkPastTheLineBox(w, "Bankroll")
        } finally {
            bg.cancel()
        }
    }

    private fun assertNoInkPastTheLineBox(w: GamesWindow, what: String) {
        val v = w.view()
        assertTrue(v is WindowView.DocView, "$what should be a document")
        v as WindowView.DocView
        val width = 580
        var checked = 0
        for (i in 0 until minOf(v.lineCount(), 24)) {
            // the SAME rect the line is given, on a surface with room BELOW it:
            // any pixel past the rect is ink the slide path's per-line buffer
            // silently loses
            val g = Gray8(width, v.lineHeight * 3)
            v.paintLine(g, i, Rect(0, 0, width, v.lineHeight))
            val over = (v.lineHeight until g.h).filter { y -> (0 until width).any { g[it, y] != 0 } }
            assertTrue(over.isEmpty(),
                "$what line $i inks rows $over past its ${v.lineHeight} px line box")
            if ((0 until v.lineHeight).any { y -> (0 until width).any { g[it, y] != 0 } }) checked++
        }
        assertTrue(checked >= 3, "$what should have drawn some lines (drew $checked)")
    }

    // ================================================================ §30 #4
    /**
     * A list of exactly ONE row is drawn ONLY by the lens — the slots above
     * and below resolve to no index — so Files' "no locations" and "trash is
     * empty" placeholder rows were unreachable and the content band went
     * BLANK behind a failed or unanswered listing. Every other window with a
     * one-row state says it in the lens; Files did not.
     */
    @Test
    fun filesSaysWhyAnEmptyListIsEmpty() {
        val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val tmp = Files.createTempDirectory("damage-r30-files")
            val w = FilesWindow(CapText(), EmptyFiles, bg, null, tmp.toString())
            w.onRegistered(ProbeServices)
            w.onActivate(ProbeServices, ActivationSource.MAIN)
            val l = Layout()
            val lens = Rect(l.lens.x, l.lens.y, l.lens.w - Layout.RAIL_W, l.lens.h)

            val locs = w.view() as WindowView.ListView
            assertEquals(1, locs.rowCount(), "an empty locations list still has its placeholder row")
            val g = Gray8(640, 480)
            locs.paintLens(g, lens, 0)
            assertTrue(lit(g, lens) > 0, "an empty locations list must SAY so in the lens")

            // …and the trash, entered from its own location row
            val t = FilesWindow(CapText(), TrashOnlyFiles, bg, null, tmp.toString())
            t.onRegistered(ProbeServices)
            t.onActivate(ProbeServices, ActivationSource.MAIN)
            (t.view() as WindowView.ListView).onCommit(0)      // the single Trash location
            val tv = t.view() as WindowView.ListView
            assertEquals(1, tv.rowCount(), "an empty trash still has its placeholder row")
            val g2 = Gray8(640, 480)
            tv.paintLens(g2, lens, 0)
            assertTrue(lit(g2, lens) > 0, "an empty trash must SAY so in the lens")
        } finally {
            bg.cancel()
        }
    }

    private fun lit(g: Gray8, r: Rect): Int {
        var n = 0
        for (y in r.y until r.bottom) for (x in r.x until r.right) if (g[x, y] != 0) n++
        return n
    }

    // ================================================================ §30 #5
    /**
     * The title bar's AM/PM marker sat at a constant `+52` — which is `4 +
     * the widest time` at 100 %, i.e. a ZERO gap ("10:20PM" on the glass) —
     * and one step up the font ladder the time is 53 px wide, so the marker
     * landed inside the last digit. Both the marker's x and the cell's width
     * are measured now.
     */
    @Test
    fun theClockMarkerNeverTouchesTheTime() {
        for (px in listOf(16, 18, 19, 21)) {
            val tx = CapText()
            val chrome = Chrome(ScaledChromeText(tx, px))
            val need = chrome.clockCellWidth()
            val w = chrome.clockWidths()
            assertTrue(need >= Chrome.CLOCK_PAD + w[0] + Chrome.CLOCK_GAP + w[1] + Chrome.CLOCK_PAD,
                "the ${px}px clock cell ($need) cannot hold ${w[0]} + ${w[1]}")
            val cell = Rect(0, 0, need, Layout.TOP_H)
            // the two halves drawn APART, so where each one really lands is
            // observable rather than inferred from their union
            val gTime = Gray8(need + 64, Layout.TOP_H)
            chrome.paintClockText(gTime, cell, "12:38", "")
            val gMark = Gray8(need + 64, Layout.TOP_H)
            chrome.paintClockText(gMark, cell, "", "PM")
            val timeEnd = (0 until gTime.w).last { x -> (0 until gTime.h).any { gTime[x, it] != 0 } }
            val markerStart = (0 until gMark.w).first { x -> (0 until gMark.h).any { gMark[x, it] != 0 } }
            val markerEnd = (0 until gMark.w).last { x -> (0 until gMark.h).any { gMark[x, it] != 0 } }
            assertTrue(markerStart > timeEnd,
                "at ${px}px the AM/PM marker starts at $markerStart, on top of a time ending at $timeEnd")
            assertTrue(markerEnd < cell.right, "the marker ($markerEnd) leaves the ${need}px cell")
        }
    }

    /** The chrome's own faces at one step of the ladder. */
    private class ScaledChromeText(private val base: TextRasterizer, private val px: Int) : TextRasterizer {
        private fun map(f: FontSpec) = FontSpec(f.face, maxOf(6, f.sizePx * px / 16), f.bold, f.italic)
        override fun measure(text: String, font: FontSpec) = base.measure(text, map(font))
        override fun metrics(font: FontSpec) = base.metrics(map(font))
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) =
            base.draw(surface, x, y, text, map(font), level)
        override fun covers(text: String, font: FontSpec) = base.covers(text, map(font))
    }

    // ================================================================ §30 #6
    /**
     * The MEDIUM seven-segment readout put its last digit at `x0 + 84` where
     * every other pair is 24 apart, so the minutes printed with a visible gap
     * — "10:2 1" in Music Mode's clock.
     */
    @Test
    fun theMediumClockDigitsAreEvenlySpaced() {
        val g = Gray8(160, 40)
        Icons.sevenSegClockMedium(g, 0, 0, 10, 88)
        val cols = (0 until g.w).filter { x -> (0 until g.h).any { g[x, it] != 0 } }
        // the lit column RUNS: the '1' is only its two right bars, then the
        // three full digits and the colon between them
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        starts.add(cols.first())
        for (i in 1 until cols.size) if (cols[i] != cols[i - 1] + 1) { ends.add(cols[i - 1]); starts.add(cols[i]) }
        ends.add(cols.last())
        assertEquals(listOf(14, 24, 47, 56, 80), starts,
            "the minute pair sits at 56 and 80 — the same 24 px pitch as the hours")
        assertEquals(97, ends.last(), "the medium readout is 98 px wide, not 102")
        assertEquals(listOf(6, 5, 5, 6), (1 until starts.size).map { starts[it] - ends[it - 1] - 1 },
            "every digit gap matches the large face's 6 px, the colon's two are 5")
    }


    // ================================================================ §30 #7
    /**
     * The wheel's own rule must not cross the name it brackets.
     *
     * §28 #11 sized the centre band from the name's ASCENT — the top half of
     * the promise. The name is drawn at `bandTop + 64` and inks
     * `ascent + descent`, so on the live wheel at 130 % the lower rule landed
     * on row 288 with "Settings" still inking 290-292 and the rule struck
     * straight through the word. Measured on the running program, pinned here.
     */
    @Test fun theSwitcherBandHoldsTheNameItDraws() {
        for (scale in listOf(1.0, 1.15, 1.2)) {
            val text = wm.damage.core.text.StyledText(ScalingText()) {
                wm.damage.core.text.StyleTransform(scale = scale).apply(it)
            }
            val sw = wm.damage.core.shell.Switcher(text)
            // a name with a DESCENDER: the ascent alone would say it fits
            val a = NamedWin("a", "Paging")
            val b = NamedWin("b", "Other")
            val c = NamedWin("c", "Third")
            sw.openWith(a, listOf(a, b, c))
            val l = Layout(Rect(0, 0, 640, 480))
            val g = Gray8(640, 480)
            val p = sw.paint(g, l)
            // no window is dirty, so HOT ink is the centre name and nothing else
            val hot = (p.y until p.bottom).filter { y -> (p.x until p.right).any { x -> g[x, y] == Level.HOT } }
            // the panel's leftmost column carries the rules and nothing else:
            // both icons and both names are inset well past it
            val rules = (p.y until p.bottom).filter { y -> g[p.x, y] == Level.DIM }
            assertTrue(hot.isNotEmpty(), "scale $scale: the centre name drew nothing")
            assertTrue(rules.size >= 2, "scale $scale: the wheel drew ${rules.size} band rules")
            val under = rules.filter { it > hot.first() }
            assertTrue(under.isNotEmpty(), "scale $scale: no band rule under the name")
            assertTrue(hot.last() < under.first(),
                "scale $scale: the name inks rows ${hot.first()}..${hot.last()} and the band rule " +
                    "under it starts at ${under.first()} — the rule crosses the name")
            // and every neighbour stays clear of the rules too
            val above = rules.filter { it < hot.first() }
            val dim = (p.y + 2 until above.last()).filter { y ->
                (p.x until p.right).any { x -> g[x, y] != 0 }
            }
            if (dim.isNotEmpty()) assertTrue(dim.last() < above.last(),
                "scale $scale: the upper neighbour inks row ${dim.last()}, the rule is at ${above.last()}")
        }
    }

    // ================================================================ §30 #8
    /**
     * Every line a seat cell draws stays inside the cell the table allocator
     * handed it.
     *
     * Measured live at the 288 rung with the global scale at 130 %: the 15 px
     * name and the 14 px stack ink 49 rows into a 29 px cell, so every
     * opponent's stack was drawn through the top edge of the board's card
     * slots — ink outside the rect its own allocator promised, the standing
     * §27 defect.
     */
    @Test fun theHoldemSeatStripStaysInsideItsBand() {
        val who = (0 until 6).map { wm.damage.core.windows.games.kit.Seats.Occupant("c$it", "Seat $it", human = it == 0) }
        for (scale in listOf(1.0, 1.15, 1.3)) {
            for (panel in listOf(288, 352, 416, 480)) {
                val text = wm.damage.core.text.StyledText(ScalingText()) {
                    wm.damage.core.text.StyleTransform(scale = scale).apply(it)
                }
                val view = wm.damage.core.windows.games.holdem.HoldemView(text)
                val table = wm.damage.core.windows.games.holdem.HoldemTable.start(
                    wm.damage.core.windows.games.holdem.HoldemRules.Table.REGULAR, 7L, who, IntArray(6) { 200 })
                val v = table.view()
                val g = Gray8(640, 480)
                val content = Layout(Rect(0, 0, 640, panel)).content
                view.paint(g, content, wm.damage.core.windows.games.holdem.HoldemView.Model(
                    v = v, spec = table.spec, mySeat = 0, revealed = 0, cast = emptyMap(), cursor = 1,
                    showStats = true, archetypes = false, handsToLevel = 20))
                val t = view.layout!!
                // the gap the allocator left between the seat strip and the
                // board is the promise: nothing may be drawn in it
                for (i in 0 until 5) {
                    val cell = t.seatCell(i, 5)
                    for (y in cell.bottom until t.board.y) for (x in cell.x until cell.right) {
                        assertEquals(0, g[x, y],
                            "scale $scale panel $panel seat $i: ink at ($x,$y) between the cell " +
                                "$cell and the board ${t.board} — the seat painted past its band")
                    }
                    // and the MONEY is still there: dropping the second row
                    // to make the band fit took every opponent's stack off a
                    // poker screen, which is the other half of the defect.
                    // The stack is right-aligned, so the cell's right third
                    // carries it in both the two-row and the compact form.
                    val moneyInk = (cell.y until cell.bottom).sumOf { y ->
                        (cell.x + 2 * cell.w / 3 until cell.right).count { x -> g[x, y] != 0 }
                    }
                    assertTrue(moneyInk > 0,
                        "scale $scale panel $panel seat $i: nothing is drawn in the money column " +
                            "of $cell — the seat says who but not how much")
                }
            }
        }
    }

    // ================================================================ §30 #9
    /**
     * The tmux staleness surface (§10.5) reaches EVERY level.
     *
     * The live pane painted `provState` itself, but the sessions list — and
     * Main's row — said nothing at all while one host had stopped answering,
     * as long as another host was alive. Found on the live walk: `ghost` had
     * been failing its status poll for half an hour and the window read clean.
     */
    @Test fun tmuxSaysAHostIsQuietOnEveryLevel() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val prov = ListenerTmux()
            val w = wm.damage.core.windows.tmux.TmuxWindow(FakeText(), prov, scope)
            w.onRegistered(ProbeServices)
            prov.push(listOf(wm.damage.core.windows.tmux.TmuxSessionInfo(
                "beardos", "claude", 1, false, 0L, false, "idle")))
            assertEquals("sessions", w.title(), "a healthy provider must say nothing extra")
            prov.state("ghost: not answering")
            assertTrue(w.title().contains("ghost"),
                "the sessions level said '${w.title()}' while a host had stopped answering")
            assertTrue(w.summary().detail.contains("ghost"),
                "Main's row said '${w.summary().detail}' while a host had stopped answering")
            prov.state("")
            assertEquals("sessions", w.title(), "the surface must clear when the host answers again")
        } finally {
            scope.cancel()
        }
    }

    // ================================================================ §30 #10
    /**
     * A STAGED settings row must not claim it applies live.
     *
     * On the live walk, scrolling Size three notches left the panel at 480
     * under a hint reading "scroll adjusts live" — the shell describing a
     * program that is not running. Size and every host row stage their value
     * and apply it on the tap; the hint now says so.
     */
    @Test fun aStagedSettingsRowSaysItAppliesOnTheTap() {
        val text = RecText()
        var st = wm.damage.core.shell.ShellSettings()
        val w = wm.damage.core.shell.SettingsWindow(text, { st }, { st = it })
        (w.view() as WindowView.ListView).onCommit(0)             // open Global
        val rows = w.view() as WindowView.ListView
        // find the row by NAME, never by index (the §24 harness rule)
        var sizeAt = -1
        for (i in 0 until rows.rowCount()) {
            text.drawn.clear()
            rows.paintRow(Gray8(640, 40), i, Rect(0, 0, 640, 40), false)
            if (text.drawn.any { it == "Size" }) { sizeAt = i; break }
        }
        assertTrue(sizeAt >= 0, "no Size row in Global")
        text.drawn.clear()
        rows.paintLens(Gray8(640, 64), Rect(0, 0, 640, 64), sizeAt)
        assertTrue(text.drawn.any { it == "tap to adjust" },
            "a row at rest says how to start: ${text.drawn}")
        rows.onCommit(sizeAt)                                      // enter the adjust level
        text.drawn.clear()
        rows.paintLens(Gray8(640, 64), Rect(0, 0, 640, 64), sizeAt)
        assertTrue(text.drawn.none { it.contains("adjusts live") },
            "the staged Size row said ${text.drawn} — nothing about it is live")
        assertTrue(text.drawn.any { it.contains("tap applies") },
            "the staged Size row must say the tap is what applies it: ${text.drawn}")
        // a row that DOES apply per notch keeps the live wording
        var depthAt = -1
        for (i in 0 until rows.rowCount()) {
            text.drawn.clear()
            rows.paintRow(Gray8(640, 40), i, Rect(0, 0, 640, 40), false)
            if (text.drawn.any { it == "Depth" }) { depthAt = i; break }
        }
        assertTrue(depthAt >= 0, "no Depth row in Global")
        rows.onCommit(depthAt)
        text.drawn.clear()
        rows.paintLens(Gray8(640, 64), Rect(0, 0, 640, 64), depthAt)
        assertTrue(text.drawn.any { it.contains("adjusts live") },
            "Depth previews per notch and must still say so: ${text.drawn}")
    }

    // ================================================================ §30 #11
    /**
     * A line box is the larger of the face's line height and its MEASURED
     * ink, never the line height alone.
     *
     * AWT ceils ascent and descent separately and the height once, so
     * JetBrains Mono 16 inks 25 rows against a 24 px line at 115 % and 29
     * against 28 at 130 % (measured against the real rasterizer). A box a row
     * short of its own text puts every line's descenders in the tops of the
     * next — the §29 rhythm defect — and presses the bottom line against the
     * rect it is drawn in.
     */
    @Test fun theFlowLineBoxHoldsTheInkItDraws() {
        for (scale in listOf(0.85, 1.0, 1.15, 1.3)) {
            val text = wm.damage.core.text.StyledText(ShortLineText()) {
                wm.damage.core.text.StyleTransform(scale = scale).apply(it)
            }
            val flow = wm.damage.core.windows.tmux.FlowRender(text)
            val f = FontSpec(Face.MONO, wm.damage.core.windows.tmux.FlowRender.BASE_SIZE)
            val ink = text.metrics(f).let { it.ascent + it.descent }
            assertTrue(flow.lineH() >= ink,
                "scale $scale: the flow's ${flow.lineH()} px line box cannot hold its $ink px ink")
        }
    }


    // ================================================================ §30 #12
    /**
     * 🔴 A wheel closed mid-spin stops spinning.
     *
     * `Switcher.spinning` is what the shell's frame loop reads to decide it
     * owes another Pump, and `Shell.isQuiescent()` reads the same flag. The
     * drum is stepped only while the wheel is OPEN, and `close()` left
     * `spinPos` short of the cursor — so a scroll followed by a commit or a
     * cancel inside the four animation frames left the flag true for ever:
     * an unbounded loop of empty frames and a shell that never reported
     * itself idle. The oracle walk found it at h=288 step 180.
     */
    @Test fun aWheelClosedMidSpinStopsSpinning() {
        for (howItEnds in listOf("close", "reopen")) {
            val sw = wm.damage.core.shell.Switcher(FakeText())
            val a = NamedWin("a", "A")
            val b = NamedWin("b", "B")
            val c = NamedWin("c", "C")
            sw.openWith(a, listOf(a, b, c))
            assertTrue(!sw.spinning, "a wheel at rest does not spin")
            sw.scroll(1)
            assertTrue(sw.spinning, "a fresh notch spins")
            sw.close()
            assertTrue(!sw.spinning, "$howItEnds: a closed wheel is not spinning")
            if (howItEnds == "reopen") {
                sw.openWith(a, listOf(a, b, c))
                assertTrue(!sw.spinning, "a reopened wheel starts at rest")
            }
        }
    }

    /** The same defect where it was actually felt: the shell must reach
     *  quiescence after the wheel is scrolled and then cancelled. */
    @Test fun theShellSettlesAfterAWheelIsCancelledMidSpin() = kotlinx.coroutines.runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("review30-wheel")
        try {
            val store = wm.damage.core.shell.Persistence(tmp.resolve("state.json"))
            // §1.2: a bare long-press is a no-op by default; the direct open
            // is the Settings choice, which is what this pin needs
            store.put("shell.settings", wm.damage.core.shell.ShellSettings(
                longPress = wm.damage.core.shell.ShellSettings.LongPress.SWITCHER).toJson())
            val transport = wm.damage.core.transport.SimTransport(
                wm.damage.core.sim.GlassFirmwareSim(), scope,
                wm.damage.core.transport.SimTransport.Timing(instant = true))
            val shell = wm.damage.core.shell.Shell(FakeText(), transport, store, null, scope)
            shell.register(NamedWin("one", "One"))
            shell.register(NamedWin("two", "Two"))
            shell.start()
            suspend fun settle(what: String) {
                val t0 = System.currentTimeMillis()
                while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 30_000) kotlinx.coroutines.delay(5)
                assertTrue(shell.isQuiescent(), "$what: ${shell.quiescenceReport()}")
            }
            settle("boot")
            // open a window first: from a bare boot the wheel holds only
            // "Main" and a scroll of a one-entry drum is not a spin at all
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_CLICK)
            settle("a window is focused")
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_RING_LONG_PRESS)
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_RING_LONG_PRESS_RELEASE)
            settle("the wheel is open")
            assertTrue(shell.switcherIsOpen, "the long-press opens the wheel")
            assertTrue(shell.switcherEntryCount > 1, "the wheel needs more than one entry to spin")
            // BOTH from the loop itself, so the cancel is already queued behind
            // the scroll and lands inside the four animation frames — posting
            // them from the test thread races the loop and passes either way,
            // which is a pin that proves nothing (the §26 vacuous-pin lesson)
            shell.services.runOnShell {
                shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM)
                shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_DOUBLE_CLICK)
            }
            settle("the wheel was cancelled mid-spin")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }


    // ================================================================ §30 #18
    /**
     * 🔴 An EMPTY shelf still has one row — the Files defect, one window over.
     *
     * `ContentKit.paintList` returns immediately on a row count of zero, so the
     * Reader's library level drew a cleared band and NOTHING else: no message,
     * no lens, for every state before the first scan lands, for a library with
     * no books, and for a scan that failed. Main's row said why the whole time;
     * the window did not.
     */
    @Test fun theReaderSaysWhyAnEmptyShelfIsEmpty() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val w = wm.damage.core.windows.reader.ReaderWindow(FakeText(), EmptyContent, scope)
            w.onRegistered(ProbeServices)
            val v = w.view() as WindowView.ListView
            assertTrue(v.rowCount() >= 1, "an empty shelf still has the row that says so")
            val g = Gray8(640, 64)
            v.paintLens(g, Rect(0, 0, 640, 64), 0)
            val lit = (0 until 64).sumOf { y -> (0 until 640).count { x -> g[x, y] != 0 } }
            assertTrue(lit > 0, "the library level drew nothing at all for an empty shelf")
            // and the row is not a silent no-op
            ProbeOps.clear()
            v.onCommit(0)
            assertTrue(ProbeOps.seen.isNotEmpty(), "a tap on the placeholder did nothing at all")
        } finally {
            scope.cancel()
        }
    }


    // ================================================================ §31 #1
    /**
     * The translation detector finds a real shift, and only a real one.
     *
     * 🔴 The positive case is also the pin for the bug that shipped inside the
     * first draft of it: the byte verification advanced its cursors AND indexed
     * by the loop variable, so it compared `pix[a + 2i]` and declined every
     * shift there is. It failed SAFE — the old full-content path — which is
     * exactly why only a measurement found it.
     */
    @Test fun theCanvasShiftDetectorFindsATranslation() {
        val region = Rect(16, 34, 608, 416)
        fun frame(scroll: Int): Gray8 {
            val g = Gray8(640, 480)
            // text-like: two short bars per row, at a position that depends on
            // the LINE, so every row is distinctive and none is uniform
            for (i in 0 until 40) {
                val y = region.y + i * 10 - scroll
                if (y < region.y || y + 8 > region.bottom) continue
                g.fillRect(region.x + 8 + (i * 37) % 200, y, 60 + (i * 13) % 120, 6, 9)
                g.fillRect(region.x + 300 + (i * 53) % 150, y, 40 + (i * 7) % 90, 6, 5)
            }
            return g
        }
        fun snap(g: Gray8): Gray8 = Gray8(region.w, region.h).also { it.blit(g, region, 0, 0) }

        val a = frame(0)
        val b = frame(40)                       // content moved UP by 40
        val got = wm.damage.core.comp.CanvasShift.detect(snap(a), Rect(0, 0, region.w, region.h), b, region, minRun = 52)
        assertTrue(got != null, "a 40 px translation was not found")
        val (src, dst) = got!!
        assertEquals(40, src.y - dst.y, "the offset is the 40 px the content moved")
        assertEquals(src.w, region.w)
        assertTrue(src.h >= 300, "the block should cover nearly the whole overlap, got ${src.h}")
        // grid-legal, and inside the region
        assertTrue(src.y % 2 == 0 && dst.y % 2 == 0 && src.h % 2 == 0, "the copy is on the 2 px grid")
        assertTrue(region.contains(src) && region.contains(dst), "$src / $dst escape $region")
        // and the block it names really IS the same pixels
        for (y in 0 until src.h) for (x in 0 until src.w) {
            assertEquals(a[src.x + x, src.y + y], b[dst.x + x, dst.y + y],
                "row $y of the declared block is not the same content")
        }

        // an unchanged frame is not a translation
        assertTrue(wm.damage.core.comp.CanvasShift.detect(snap(a), Rect(0, 0, region.w, region.h), a, region, 52) == null,
            "an unchanged frame must not be declared a shift — the copy and its repairs cost more than nothing")
        // nor is an unrelated one
        val other = Gray8(640, 480).also { g ->
            for (i in 0 until 40) g.fillRect(region.x + 4 + (i * 91) % 400, region.y + i * 11, 33, 5, 12)
        }
        assertTrue(wm.damage.core.comp.CanvasShift.detect(snap(a), Rect(0, 0, region.w, region.h), other, region, 52) == null,
            "two unrelated frames must not be declared a shift")
        // a shift too small to be worth a copy is declined
        assertTrue(wm.damage.core.comp.CanvasShift.detect(snap(a), Rect(0, 0, region.w, region.h), frame(40), region, minRun = 400) == null,
            "a block under the floor is not worth the copy op")

        // a region off the compositor's 4x2 CELL grid gets no copy. Not a
        // firmware rule — mode 9 takes full uint16 coords (`zlib_glue.c`) — but
        // `declareShift` moves the per-lens `unknown` marks with the copy
        // through a cell-quantised `moveCells`, and exclusive mode passes rects
        // a WINDOW chose
        val odd = Rect(region.x + 2, region.y + 1, region.w - 8, region.h - 40)
        assertTrue(wm.damage.core.comp.CanvasShift.detect(
            snap(a), Rect(2, 1, odd.w, odd.h), b, odd, minRun = 52) == null,
            "an unaligned region must not produce a copy the firmware would drop in silence")

        // 🔴 an ODD-HEIGHT region is declined. The offset sweep starts at
        // `-h + 2` and steps by 2, so an odd height makes every candidate offset
        // odd and the copy's src row lands off the 2 px grid — which the
        // firmware refuses IN SILENCE while `declareShift` has already replayed
        // the copy onto the shadows, so the diff finds nothing to repair and the
        // glass keeps the old frame. Unreachable today (`layout.content.h` is
        // even and no window returns an odd-height exclusive rect); pinned
        // because exclusive mode passes rects a WINDOW chose.
        // The same grid rule for HEIGHT, which is the easy one to lose: the
        // offset sweep steps by 2 from `-h + 2`, so an odd height proposes only
        // odd offsets and the copy's src row lands off the cell grid.
        //
        // ⚠ The failing combination is odd height AND an ODD shift — an
        // odd-height sweep declines an even translation harmlessly, so the odd
        // one is the only one that gets through. The first draft of this pin
        // used the 40 px shift above and passed with the guard removed.
        val odd41 = frame(41)
        for (oddH in listOf(region.h - 1, region.h - 3)) {
            val odd = Rect(region.x, region.y, region.w, oddH)
            val got2 = wm.damage.core.comp.CanvasShift.detect(
                snap(a), Rect(0, 0, odd.w, odd.h), odd41, odd, minRun = 52)
            assertTrue(got2 == null,
                "an odd-height region produced $got2 — a copy off the compositor's 4x2 cell grid")
        }
        // the guard is the PARITY, not the size: the even heights either side
        // still find the even translation
        for (evenH in listOf(region.h, region.h - 2)) {
            val even = Rect(region.x, region.y, region.w, evenH)
            assertTrue(wm.damage.core.comp.CanvasShift.detect(
                snap(a), Rect(0, 0, even.w, even.h), b, even, minRun = 52) != null,
                "the even height $evenH should still find the translation")
        }
        // and an even region asked for an ODD translation simply declines —
        // its sweep never proposes an odd offset (no copy, never a wrong one)
        assertTrue(wm.damage.core.comp.CanvasShift.detect(
            snap(a), Rect(0, 0, region.w, region.h), odd41, region, minRun = 52) == null,
            "an odd translation in an even region must be declined, not rounded")

        // 🔴 the OTHER shape the shell uses (exclusive mode's own damage path):
        // two panel-sized frames and a sub-band, so `was` has a non-zero origin
        // — the canvas call site always passes (0,0) and would never catch an
        // offset mistake here
        val band = Rect(region.x, region.y + 100, region.w, 200)
        val bandWas = Rect(band.x - 16, band.y - 34, band.w, band.h)   // a safe-rect-relative snapshot
        val prev = Gray8(624, 446).also { it.blit(a, Rect(16, 34, 624, 446), 0, 0) }
        val sub = wm.damage.core.comp.CanvasShift.detect(prev, bandWas, b, band, minRun = 52)
        assertTrue(sub != null, "the same translation inside a sub-band was not found")
        assertEquals(40, sub!!.first.y - sub.second.y, "the sub-band's offset is the same 40 px")
        assertTrue(band.contains(sub.first) && band.contains(sub.second),
            "${sub.first} / ${sub.second} escape the band $band")
        for (y in 0 until sub.first.h) for (x in 0 until sub.first.w) {
            assertEquals(a[sub.first.x + x, sub.first.y + y], b[sub.second.x + x, sub.second.y + y],
                "the sub-band block is not the same content")
        }
    }

    // ================================================================ §31 #2
    /**
     * 🔴 A canvas scroll ships the TRANSLATION, not the whole content area.
     *
     * MEASURED before this: a tmux scroll cost 7.4–10.8 KB, and 6–12 KB
     * measures a median 1193 ms on the glasses against 65 ms under 500 B. The
     * pin compares a scroll (a translation) with a jump (nothing overlaps) in
     * the same window, so it needs no absolute byte number and no chrome
     * subtraction: the scroll must cost a fraction of the jump.
     */
    @Test fun aCanvasScrollShipsTheShiftNotTheScreen() = kotlinx.coroutines.runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("review31-canvas")
        try {
            val store = wm.damage.core.shell.Persistence(tmp.resolve("state.json"))
            val sim = wm.damage.core.sim.GlassFirmwareSim()
            val transport = wm.damage.core.transport.SimTransport(sim, scope,
                wm.damage.core.transport.SimTransport.Timing(instant = true))
            val shell = wm.damage.core.shell.Shell(FakeText(), transport, store, null, scope)
            val win = ScrollCanvas()
            shell.register(win)
            val bytes = java.util.concurrent.atomic.AtomicLong(0)
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                transport.events.collect {
                    if (it is wm.damage.core.transport.TransportEvent.FlushDone) bytes.addAndGet(it.bytes.toLong())
                }
            }
            shell.start()
            suspend fun settle() {
                val t0 = System.currentTimeMillis()
                while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 30_000) kotlinx.coroutines.delay(5)
                assertTrue(shell.isQuiescent(), "the shell did not settle — ${shell.quiescenceReport()}")
            }
            /**
             * 🔴 The GLASS, not just the byte count. A declared copy becomes a
             * mode-9 rect the firmware applies itself; if it landed anywhere
             * but where the compositor's shadow put it — or the firmware
             * refused it for alignment, which it does IN SILENCE — belief and
             * panel come apart and the byte saving would be a wrong frame.
             * Read ON the loop (`sampleIdle`, §30) so the sample cannot tear.
             */
            suspend fun assertGlass(what: String) {
                val pair = shell.sampleIdle {
                    fun quant(g: Gray8) = Gray8(g.w, g.h).also { o ->
                        for (i in g.pix.indices) o.pix[i] = (wm.damage.core.gfx.Pack.level(g.pix[i].toInt() and 0xFF) * 17).toByte()
                    }
                    fun panel(left: Boolean): Gray8 {
                        val ctx = if (left) sim.left else sim.right
                        return Gray8(640, 480).also { g ->
                            for (y in 0 until 480) for (x in 0 until 640) {
                                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
                            }
                        }
                    }
                    listOf(true, false).map { l ->
                        Triple(l, quant(shell.comp.expectedLens(l)), panel(l))
                    }
                } ?: throw AssertionError("$what: the shell never held still long enough to sample")
                for ((left, belief, glass) in pair) {
                    val arm = if (left) "LEFT" else "RIGHT"
                    // not vacuous: a blank panel would match a blank belief
                    assertTrue(glass.pix.count { it.toInt() != 0 } > 1_000,
                        "$what: the $arm panel is blank — this check would pass on nothing")
                    for (y in 0 until 480) for (x in 0 until 640) {
                        if (belief[x, y] != glass[x, y]) throw AssertionError(
                            "$what: $arm belief != glass at ($x,$y) — " +
                                "expected ${belief[x, y]} got ${glass[x, y]}")
                    }
                }
                val flags = sim.flags(wm.damage.core.transport.Arm.LEFT) + sim.flags(wm.damage.core.transport.Arm.RIGHT)
                assertTrue(flags.none { it.value }, "$what: sticky diagnostic flags ${flags.filterValues { it }}")
            }

            settle()
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_CLICK)      // Main row 0 = the canvas
            settle()
            assertEquals("canvas", shell.currentWindowId(), "the canvas window did not open")
            assertGlass("the canvas opened")

            bytes.set(0)
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM)   // one notch: a translation
            settle()
            val scrolled = bytes.get()
            assertGlass("after the scroll that IS a translation")

            bytes.set(0)
            win.jump = true                                                  // the next notch changes everything
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM)
            settle()
            val jumped = bytes.get()
            assertGlass("after the jump that is NOT a translation")

            assertTrue(jumped > 1500, "the jump must be a real full-area repaint (scrolled=$scrolled jumped=$jumped)")
            assertTrue(scrolled * 3 < jumped,
                "a canvas scroll shipped $scrolled B where a full change of the same window ships " +
                    "$jumped B — the translation is not being declared, so the scroll is paying " +
                    "for the whole content area")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }


    // ================================================================ §31 #3
    /** The blit fast path is the slow path, in every case that clips. */
    @Test fun theWholeRowBlitAgreesWithThePerPixelOne() {
        val rnd = java.util.Random(31)
        val src = Gray8(37, 23).also { for (i in it.pix.indices) it.pix[i] = rnd.nextInt(256).toByte() }
        // every interesting shape: inside, hanging off each edge, oversized,
        // zero-sized, and negative origins
        val cases = listOf(
            Rect(0, 0, 37, 23) to (0 to 0), Rect(4, 5, 10, 8) to (3 to 2),
            Rect(-5, -3, 20, 12) to (0 to 0), Rect(30, 18, 20, 12) to (2 to 2),
            Rect(0, 0, 37, 23) to (-6 to -4), Rect(0, 0, 37, 23) to (20 to 10),
            Rect(0, 0, 0, 5) to (1 to 1), Rect(2, 2, 5, 0) to (1 to 1),
        )
        for ((r, d) in cases) {
            val fast = Gray8(31, 19).also { it.clear(7) }
            fast.blit(src, r, d.first, d.second)
            // the reference: one pixel at a time, through the clipping `set`
            val slow = Gray8(31, 19).also { it.clear(7) }
            for (yy in 0 until r.h) for (xx in 0 until r.w) {
                val sx = r.x + xx; val sy = r.y + yy
                if (sx in 0 until src.w && sy in 0 until src.h) slow[d.first + xx, d.second + yy] = src[sx, sy]
            }
            assertTrue(fast.pix.contentEquals(slow.pix), "blit differs for $r -> $d")
        }
    }

    // ---------------------------------------------------------------- fakes
    private object ProbeServices : wm.damage.core.shell.ShellServices {
        override fun requestRender(window: wm.damage.core.shell.DamageWindow) {}
        override fun setOperation(op: String) { ProbeOps.seen.add(op) }
        override fun notifyInternal(source: String, body: String, urgent: Boolean,
            appId: String?, thread: String, target: String?) {}
        override fun openWindow(id: String, target: String?): Boolean = false
        override fun runOnShell(action: () -> Unit) = action()
        override fun docContentWidth(): Int = 560
        override fun docContentHeight(): Int = 384
    }

    private object EmptyFiles : FilesProvider {
        override fun locations(): List<FLocation> = emptyList()
        override fun list(dir: String, showHidden: Boolean): List<FEntry> = emptyList()
        override fun stat(path: String) = FStat(0, 0, "", "", false)
        override fun du(path: String) = 0L
        override fun readText(path: String, offset: Long, maxBytes: Int) = TextChunk("", false, 0)
        override fun readBytes(path: String, maxBytes: Int) = ByteArray(0)
        override fun thumb(path: String, sizePx: Int): Gray8? = null
        override fun pdfInfo(path: String) = PdfInfo(0, 0)
        override fun pdfText(path: String) = ""
        override fun pdfPage(path: String, page: Int, widthPx: Int) = ByteArray(0)
        override fun trash(path: String) = ""
        override fun trashList(): List<FTrashEntry> = emptyList()
        override fun restore(id: String) = ""
        override fun purge(id: String) {}
        override fun rename(path: String, newName: String) = path
        override fun mkdir(dir: String, name: String) = dir
        override fun copy(src: String, destDir: String) = destDir
        override fun move(src: String, destDir: String) = destDir
        override fun openOnPc(path: String) {}
    }

    private object TrashOnlyFiles : FilesProvider by EmptyFiles {
        override fun locations(): List<FLocation> = listOf(FLocation("Trash", "/trash", "trash"))
    }

    /** A window that exists only to carry a NAME into the switcher. */
    private class NamedWin(id: String, name: String) :
        wm.damage.core.shell.DamageWindow(id, name, wm.damage.core.gfx.IconKind.FILES) {
        private val model = wm.damage.core.shell.ListModel()
        override fun view(): WindowView = WindowView.ListView(model, { 0 },
            { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> }, { })
        override fun title() = "root"
        override fun summary() = Summary("root")
        override fun saveState() = kotlinx.serialization.json.JsonObject(emptyMap())
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
    }

    /** [FakeText] that also records every string it was asked to draw. */
    private class RecText : TextRasterizer {
        val drawn = ArrayList<String>()
        override fun measure(text: String, font: FontSpec): Int = text.length * 8
        override fun metrics(font: FontSpec) = FontMetrics(12, 4, 16)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
            drawn.add(text)
        }
        override fun covers(text: String, font: FontSpec) = true
    }

    /** A face whose reported line height is a row SHORT of its ink — what AWT
     *  actually reports for JetBrains Mono at several of the ladder's scales. */
    private class ShortLineText : TextRasterizer {
        private fun asc(f: FontSpec) = Math.ceil(f.sizePx * 1.1).toInt()
        private fun desc(f: FontSpec) = Math.ceil(f.sizePx * 0.35).toInt()
        override fun measure(text: String, font: FontSpec): Int = text.length * maxOf(4, font.sizePx * 3 / 5)
        override fun metrics(font: FontSpec) =
            FontMetrics(asc(font), desc(font), asc(font) + desc(font) - 1)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
            surface.fillRect(x, y, measure(text, font), asc(font) + desc(font), level)
        }
        override fun covers(text: String, font: FontSpec) = true
    }

    /** A tmux provider that pushes what the test tells it to. */
    private class ListenerTmux : wm.damage.core.windows.tmux.TmuxProvider {
        private val ls = java.util.concurrent.CopyOnWriteArrayList<wm.damage.core.windows.tmux.TmuxProvider.Listener>()
        fun push(s: List<wm.damage.core.windows.tmux.TmuxSessionInfo>) {
            for (l in ls) l.status(s, wm.damage.core.windows.tmux.TmuxConfig())
        }
        fun state(v: String) { for (l in ls) l.state(v) }
        override fun addListener(l: wm.damage.core.windows.tmux.TmuxProvider.Listener) { ls.add(l) }
        override fun removeListener(l: wm.damage.core.windows.tmux.TmuxProvider.Listener) { ls.remove(l) }
        override fun subscribe(l: wm.damage.core.windows.tmux.TmuxProvider.Listener,
            target: wm.damage.core.windows.tmux.TmuxTarget?) {}
        override fun sendKeys(target: wm.damage.core.windows.tmux.TmuxTarget, keys: List<String>) {}
        override fun sendLiteral(target: wm.damage.core.windows.tmux.TmuxTarget, text: String) {}
        override fun history(target: wm.damage.core.windows.tmux.TmuxTarget, lines: Int): List<String> = emptyList()
        override fun windows(target: wm.damage.core.windows.tmux.TmuxTarget) =
            emptyList<wm.damage.core.windows.tmux.TmuxWinInfo>()
        override fun newSession(host: String): String = "g2-1"
        override fun killSession(target: wm.damage.core.windows.tmux.TmuxTarget) {}
        override fun renameSession(target: wm.damage.core.windows.tmux.TmuxTarget, newName: String) {}
        override fun selectWindow(target: wm.damage.core.windows.tmux.TmuxTarget, idx: Int) {}
        override fun resizeWindow(target: wm.damage.core.windows.tmux.TmuxTarget, cols: Int, rows: Int) {}
        override fun close() {}
    }

    /** A content provider with no books at all. */
    private object EmptyContent : wm.damage.core.content.ContentProvider {
        override fun library(): List<wm.damage.core.content.BookMeta> = emptyList()
        override fun openBook(id: String): java.nio.file.Path = throw IllegalStateException("no books")
        override fun state() = ""
    }

    /** Records the operations a window reports, so a pin can tell "it did
     *  something" from "it silently did nothing". */
    private object ProbeOps {
        val seen = ArrayList<String>()
        fun clear() = seen.clear()
    }

    /** A canvas window whose scroll is a pure vertical translation — and, on
     *  demand, one that is not. */
    private class ScrollCanvas : wm.damage.core.shell.DamageWindow(
        "canvas", "Canvas", wm.damage.core.gfx.IconKind.TERMINAL,
    ) {
        private var scroll = 0
        var jump = false
        override fun view() = WindowView.CanvasView(
            paint = { g, r ->
                g.fillRect(r, 0)
                // dense, glyph-shaped rows: a terminal's own run structure, so
                // the byte numbers this pin compares are the ones a real pane
                // produces rather than a sparse pattern's
                for (i in 0 until 60) {
                    val y = r.y + i * 10 - scroll
                    if (y < r.y || y + 8 > r.bottom) continue
                    // the LINE's index is the only seed, so a line renders
                    // identically wherever it lands — which is what makes a
                    // scroll a translation at all — and the run structure is
                    // noisy enough that the encoder cannot collapse it, the
                    // way anti-aliased type cannot be collapsed
                    val k = if (jump) i * 7 + 3 else i
                    var rnd = (k * 2654435761L) xor 0x9E3779B97F4A7C15uL.toLong()
                    fun next(): Int { rnd = rnd * 6364136223846793005L + 1442695040888963407L; return ((rnd ushr 33).toInt() and 0x7FFFFFFF) }
                    var x = r.x + 4
                    while (x < r.right - 12) {
                        val wgl = 2 + next() % 5
                        val lv = next() % 15 + 1
                        if (next() % 4 != 0) g.fillRect(x, y, wgl, 6, lv)
                        x += wgl + 1 + next() % 3
                    }
                }
            },
            onScroll = { d -> scroll += d * 40 },
        )
        override fun title() = "canvas"
        override fun summary() = Summary("canvas")
        override fun saveState() = kotlinx.serialization.json.JsonObject(emptyMap())
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
    }
}
