package wm.damage.desktop

import kotlin.test.Test
import kotlin.test.assertTrue
import wm.damage.core.geom.Layout
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.Chrome
import wm.damage.core.shell.HostTag
import wm.damage.core.text.Faces
import wm.damage.core.text.FontSpec
import wm.damage.core.text.StyleTransform
import wm.damage.core.text.StyledText

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b H2 (`private/build085/RB-lean-spec.md` §4.5) and R-b F (`RB-decisions.md` Part E item 1, Part F item 2: no tag is
 * cut): the link cell's host half, measured on the thing it names like the status cell (`StatusCellFitTest`): the REAL
 * rasterizer, the four locked faces, every chrome size the status bar's height admits, every tag of `HostTag`'s
 * vocabulary at its widest reading, at good signal and beside the poor-signal dBm figure (the narrowest figure and a
 * wide one). In every state the tag is drawn whole — its whole form or its short form, never the fit path — and its ink
 * stops before the bars' gap; beside a poor signal either the dBm figure is drawn untouched with a blank column before
 * it, or it is not drawn and the tag is drawn as at good signal. A tag added to the vocabulary that fits in neither form
 * fails here. What each state drew is read from the pixels and printed.
 */
class HostTagFitTest {

    /** Good signal, then the narrowest poor figure and a wide one (`Chrome`: poor is -75 dBm or worse). */
    private val signals = listOf<Int?>(null, -75, -105)

    @Test
    fun everyTagIsDrawnWholeInOneOfItsFormsAtEverySizeAndSignal() {
        val awt = AwtText()
        val cut = ArrayList<String>()
        val wrong = ArrayList<String>()
        var states = 0
        var short = 0
        var yielded = 0
        for (label in Faces.LABELS) {
            val face = Faces.byLabel(label) ?: error(label)
            for (pct in listOf(100, 105, 110, 115, 120, 125, 130)) {
                val scale = pct / 100.0
                val m = awt.metrics(FontSpec(face, Math.round(16 * scale).toInt(), bold = true))
                if (pct > 100 && m.ascent + m.descent > Layout.STATUS_H) continue   // the shell's chromeScale stops here
                val text = StyledText(awt) { StyleTransform(face = face, systemOnly = true, scale = scale).apply(it) }
                val chrome = Chrome(text)
                val l = Layout(statusW = chrome.statusCellWidth())
                val c = l.linkCell
                val bars = c.right - 48
                val left = c.x until c.right - 44
                fun cell(dbm: Int?, tag: String): Gray8 {
                    val g = Gray8(640, 480)
                    chrome.invalidate()
                    chrome.sync(g, l, Chrome.State(linkBars = 4, linkDbm = dbm, hostState = tag))
                    return g
                }
                fun col(g: Gray8, x: Int) = (c.y until c.bottom).map { y -> g[x, y] }
                fun lit(g: Gray8, x: Int) = (c.y until c.bottom).any { y -> g[x, y] != 0 }
                fun same(a: Gray8, b: Gray8, xs: IntRange) = xs.all { col(a, it) == col(b, it) }
                // each tag's short form drawn as a tag of its own (whole), and each tag at good signal
                val shortOf = HostTag.WIDEST.associateWith { cell(null, HostTag.short(it)) }
                val good = HashMap<String, Gray8>()
                for (dbm in signals) {
                    val alone = dbm?.let { cell(it, "") }                          // the dim mark and the figure
                    val figureAt = alone?.let { a -> (c.x + 12 until bars).first { lit(a, it) } }
                    for (tag in HostTag.WIDEST) {
                        val runs = chrome.hostFitRuns
                        val g = cell(dbm, tag)
                        val where = "$label ${pct}% ${dbm?.let { "$it dBm" } ?: "good signal"} '$tag'"
                        states++
                        if (chrome.hostFitRuns != runs) cut += where
                        val ink = left.filter { lit(g, it) }
                        if (ink.isEmpty() || ink.last() >= bars && dbm == null) wrong += "$where: ink ${ink.firstOrNull()}..${ink.lastOrNull()}, the bars' gap at $bars"
                        val drawn: String
                        if (dbm == null) {
                            good[tag] = g
                            drawn = if (same(g, shortOf.getValue(tag), left)) "short" else "whole"
                        } else {
                            val figure = same(g, alone!!, figureAt!! until bars) && !lit(g, figureAt - 1)
                            val asGood = same(g, good.getValue(tag), left)
                            if (!figure && !asGood) wrong += "$where: the dBm figure is neither drawn untouched with a gap before it nor given up"
                            if (!figure) yielded++
                            drawn = (if (same(g, shortOf.getValue(tag), if (figure) c.x until figureAt - 1 else left)) "short" else "whole") +
                                if (figure) " beside the figure" else ", the figure not drawn"
                        }
                        if (drawn.startsWith("short")) short++
                        println("host tag: $where: $drawn${if (chrome.hostFitRuns != runs) " — CUT (fit path)" else ""}")
                    }
                }
            }
        }
        println("host tag: $states states; the short form in $short; the dBm figure given up in $yielded; cut ${cut.size}; drawn wrong ${wrong.size}")
        assertTrue(cut.isEmpty(), "every tag is drawn whole in one of its forms; cut in ${cut.size} states: ${cut.take(12)}")
        assertTrue(wrong.isEmpty(), "every tag's ink stops before the bars and the dBm figure: ${wrong.size} states: ${wrong.take(12)}")
    }
}
