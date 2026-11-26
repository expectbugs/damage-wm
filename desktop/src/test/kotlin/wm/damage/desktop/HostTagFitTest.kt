package wm.damage.desktop

import kotlin.test.Test
import kotlin.test.assertTrue
import wm.damage.core.geom.Layout
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.Chrome
import wm.damage.core.shell.HostTag
import wm.damage.core.text.Face
import wm.damage.core.text.Faces
import wm.damage.core.text.FontSpec
import wm.damage.core.text.StyleTransform
import wm.damage.core.text.StyledText

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b H2 (`private/build085/RB-lean-spec.md` §4.5): the link cell's host half, measured on the thing it names like the
 * status cell (`StatusCellFitTest`): the REAL rasterizer, the four locked faces, every chrome size the status bar's
 * height admits, every tag of `HostTag`'s vocabulary at its widest reading, good signal. At the design size every tag
 * is drawn whole and stops short of the signal bars, in every face, or this test fails. Above it the cell does not grow
 * (it is not the status cell): the tags that take the fit path there (the continuation mark, counted) are printed.
 */
class HostTagFitTest {

    @Test
    fun everyTagFitsBeforeTheBarsInEveryFaceAtTheDesignSize() {
        val awt = AwtText()
        val over = ArrayList<String>()
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
                for (tag in HostTag.WIDEST) {
                    val g = Gray8(640, 480)
                    chrome.invalidate()
                    val runs = chrome.hostFitRuns
                    chrome.sync(g, l, Chrome.State(linkBars = 4, hostState = tag))
                    val whole = chrome.hostFitRuns == runs
                    val w = text.measure(tag, FontSpec(Face.SYSTEM, 13))
                    println("host tag: $label ${pct}% '$tag' $w px of ${c.w - 48 - 6}: ${if (whole) "whole" else "fit path"}")
                    val lit = (c.x until c.right - 44).filter { x -> (c.y until c.bottom).any { y -> g[x, y] != 0 } }
                    assertTrue(lit.isNotEmpty(), "$label ${pct}% '$tag': the tag is drawn")
                    assertTrue(lit.last() < c.right - 48, "$label ${pct}% '$tag': its ink stops before the bars' gap (last ink at ${lit.last()})")
                    if (pct == 100) assertTrue(whole, "$label at the design size: '$tag' ($w px) is drawn whole")
                    else if (!whole) over += "$label ${pct}% '$tag'"
                }
            }
        }
        println("host tag: above the design size the fit path ran for ${over.size}: $over")
    }
}
