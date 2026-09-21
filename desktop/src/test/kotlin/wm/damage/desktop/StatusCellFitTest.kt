package wm.damage.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.geom.Layout
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.Chrome
import wm.damage.core.shell.InputEcho
import wm.damage.core.shell.ShellState
import wm.damage.core.text.Face
import wm.damage.core.text.Faces
import wm.damage.core.text.FontSpec
import wm.damage.core.text.StyleTransform
import wm.damage.core.text.StyledText

/**
 * The status cell's fit, measured on the thing it names (`HANDOFF.md` §69): the REAL rasterizer,
 * the four locked faces, every chrome size the status bar's height admits, every state word beside
 * every input echo. The cell is sized from the same two vocabularies (`Chrome.statusCellWidth`), so
 * the fit path never runs, no ink leaves the cell, and the two words never touch.
 */
class StatusCellFitTest {

    @Test
    fun everyStateFitsBesideEveryEchoInEveryFaceAtEveryChromeSize() {
        val awt = AwtText()
        val echoes = listOf("") + InputEcho.WORDS + InputEcho.UNKNOWN_FORMS
        for (label in Faces.LABELS) {
            val face = Faces.byLabel(label) ?: error(label)
            for (pct in listOf(100, 105, 110, 115, 120, 125, 130)) {
                val scale = pct / 100.0
                val px = Math.round(16 * scale).toInt()
                val m = awt.metrics(FontSpec(face, px, bold = true))
                if (pct > 100 && m.ascent + m.descent > Layout.STATUS_H) continue   // the shell's chromeScale stops here
                val text = StyledText(awt) { StyleTransform(face = face, systemOnly = true, scale = scale).apply(it) }
                val chrome = Chrome(text)
                val w = chrome.statusCellWidth()
                val l = Layout(statusW = w)                      // throws when the op cell has no room left
                println("status cell: $label ${px}px -> $w px (op cell ${l.opCell.w})")
                assertTrue(l.opCell.w >= 40, "$label ${px}px: the op cell keeps its floor")
                for (state in ShellState.WORDS) for (echo in echoes) {
                    val g = Gray8(640, 480)
                    chrome.invalidate()
                    chrome.sync(g, l, Chrome.State(status = state, inputEcho = echo))
                    val c = l.statusCell
                    // nothing of this cell's text outside it: the neighbours' columns next to it
                    // are painted by their own cells, so look INSIDE for the gap instead
                    val lit = (c.x until c.right).filter { x -> (c.y until c.bottom).any { y -> g[x, y] != 0 } }
                    assertTrue(lit.isNotEmpty(), "$label ${px}px '$state': the word is drawn")
                    assertTrue(lit.first() >= c.x && lit.last() < c.right, "$label ${px}px '$state'+'$echo': ink inside the cell")
                    if (echo.isNotEmpty()) {
                        val gaps = lit.zipWithNext().maxOf { (a, b) -> b - a }
                        assertTrue(gaps >= 6, "$label ${px}px '$state' and '$echo' keep clear of each other (widest gap $gaps px)")
                    }
                }
                assertEquals(0, chrome.statusFitRuns, "$label ${px}px: the fit path never runs")
            }
        }
    }
}
