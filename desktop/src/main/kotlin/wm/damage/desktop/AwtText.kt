package wm.damage.desktop

import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.io.File
import wm.damage.core.geom.LintError
import wm.damage.core.gfx.Gray8
import wm.damage.core.text.Face
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.util.Log

/**
 * Desktop TextRasterizer over AWT. Fonts resolve through fc-match to the same
 * files design/render_shots.py measured; sizes are x-height normalised the way
 * the §Type comparisons were (DejaVu Sans's 0.550 ratio is the baseline), so
 * the shell's nominal sizes land at the measured visual size on every face.
 */
class AwtText(private val contentScaleProvider: () -> Double = { 1.0 }) : TextRasterizer {

    private data class Key(val face: Face, val bold: Boolean, val italic: Boolean, val px: Int)

    // concurrent: the reader lays books out on an IO thread while the shell
    // loop draws chrome (round 4 — two threads inserting the same new key)
    private val base = java.util.concurrent.ConcurrentHashMap<Pair<Face, Boolean>, Font>()
    private val scale = java.util.concurrent.ConcurrentHashMap<Face, Double>()
    private val derived = java.util.concurrent.ConcurrentHashMap<Key, Font>()
    private val frc = FontRenderContext(null, true, true)

    init {
        for (face in Face.entries) {
            for (bold in listOf(false, true)) {
                val file = fcMatch(face.family, bold)
                    ?: throw LintError("font '${face.family}'${if (bold) " Bold" else ""} not installed — " +
                        "the locked §Type faces are required")
                base[face to bold] = Font.createFont(Font.TRUETYPE_FONT, File(file))
            }
            val xh = xHeightRatio(base.getValue(face to false))
            scale[face] = if (xh > 0.01) BASE_XHEIGHT / xh else 1.0
            Log.i("fonts", "${face.family}: x-height %.3f, scale %.2f".format(xh, scale[face]))
        }
    }

    private fun fcMatch(family: String, bold: Boolean): String? {
        val style = if (bold) "Bold" else "Regular"
        val out = ProcessBuilder("fc-match", "-f", "%{family[0]}|%{file}", "$family:style=$style")
            .redirectErrorStream(true).start().inputStream.readBytes().toString(Charsets.UTF_8)
        if ("|" !in out) return null
        val (got, path) = out.split("|", limit = 2)
        // fc-match substitutes silently when a family is missing; reject substitutes
        return if (got.split(" ").first().lowercase() == family.split(" ").first().lowercase()) path.trim()
        else null
    }

    private fun xHeightRatio(f: Font): Double {
        val big = f.deriveFont(100f)
        val gv = big.createGlyphVector(frc, "x")
        return gv.visualBounds.height / 100.0
    }

    private fun font(spec: FontSpec): Font {
        val contentScale = if (spec.face == Face.SYSTEM) 1.0 else contentScaleProvider()
        val px = Math.round(spec.sizePx * scale.getValue(spec.face) * contentScale).toInt().coerceAtLeast(7)
        val key = Key(spec.face, spec.bold, spec.italic, px)
        return derived.getOrPut(key) {
            var f = base.getValue(spec.face to spec.bold).deriveFont(px.toFloat())
            if (spec.italic) f = f.deriveFont(Font.ITALIC)
            f
        }
    }

    override fun measure(text: String, font: FontSpec): Int {
        if (text.isEmpty()) return 0
        return Math.ceil(font(font).getStringBounds(text, frc).width).toInt()
    }

    override fun metrics(font: FontSpec): FontMetrics {
        val f = font(font)
        val lm = f.getLineMetrics("Ag", frc)
        return FontMetrics(Math.ceil(lm.ascent.toDouble()).toInt(),
            Math.ceil(lm.descent.toDouble()).toInt(),
            Math.ceil(lm.height.toDouble()).toInt())
    }

    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        if (text.isEmpty()) return
        val f = font(font)
        val bounds = f.getStringBounds(text, frc)
        val lm = f.getLineMetrics(text, frc)
        val w = Math.ceil(bounds.width).toInt() + 4
        val h = Math.ceil(lm.height.toDouble()).toInt() + 4
        if (w <= 0 || h <= 0) return
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g2.font = f
        g2.color = java.awt.Color.WHITE
        g2.drawString(text, 0, Math.round(lm.ascent))
        g2.dispose()
        val raster = img.raster
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                val cov = raster.getSample(xx, yy, 0)
                if (cov > 0) surface.blend(x + xx, y + yy, level, cov)
            }
        }
    }

    override fun covers(text: String, font: FontSpec): Boolean =
        base.getValue(font.face to font.bold).canDisplayUpTo(text) == -1

    companion object {
        /** DejaVu Sans's measured x-height ratio — the §Type baseline. */
        const val BASE_XHEIGHT = 0.550
    }
}
