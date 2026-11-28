package wm.damage.core.shell

import wm.damage.core.comp.CachedText
import wm.damage.core.comp.GlyphAtlas
import wm.damage.core.gfx.Gray8
import wm.damage.core.motion.TxBench
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.wire.CfwModes

/**
 * C3 stage TXB: the probe `txbench`'s records for Settings' Global at a height, painted through [text] as the shell paints
 * it with `Cached text` on — the size of what the probe writes on a face the tests and the desktop give (the room it needs
 * against the room his journal shows). Context for the reader: Adam's own glasses on his own firmware fork;
 * display-rendering work. The atlas holds every face the rows draw that fits a 160 KiB layout (the phone's atlas holds the
 * faces it has seen drawn: a face it does not hold gives no cells there).
 */
object TxBenchCheck {
    private class Faces(val base: TextRasterizer) : TextRasterizer by base {
        val seen = LinkedHashSet<FontSpec>()
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) { seen += font; base.draw(surface, x, y, text, font, level) }
    }

    /** The records laid in `[at, end)` for Global at [height] (a [wm.damage.core.geom.LintError] where they do not fit). */
    fun global(text: TextRasterizer, height: Int, at: Int, end: Int): TxBench.Bench {
        val faces = Faces(text)
        WindowListCheck.WindowRig(faces, height, WindowListCheck.Sample.GLOBAL).let { r ->
            for (i in 0 until r.rows) { ListStaging.paintStripRow(r.view, r.layout, i) { _, b -> b() }; ListStaging.paintBandForm(r.view, r.kit, r.layout, i) { _, b -> b() } }
        }
        val ct = CachedText(text).also { c ->
            c.atlas = GlyphAtlas(text, v2 = true, capacity = CfwModes.CACHE2_MAX).also { a -> for (f in faces.seen) a.add(f, GlyphAtlas.allCodes(true)) }
            c.live = faces.seen.toSet()
        }
        val r = WindowListCheck.WindowRig(ct, height, WindowListCheck.Sample.GLOBAL)
        return TxBenchRun.bench(r.view, r.kit, r.layout, ct, at, end, MainLocalCheck.effectsAt(true)!!)
    }
}
