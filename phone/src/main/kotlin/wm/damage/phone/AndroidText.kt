package wm.damage.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import wm.damage.core.geom.LintError
import wm.damage.core.gfx.Gray8
import wm.damage.core.text.Face
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.util.Log

/**
 * Android TextRasterizer over android.graphics, fonts from the bundled assets
 * (the locked §Type faces ride in the APK — Apache-2.0/OFL, see
 * assets/fonts/FONT-LICENSES.txt). Same x-height normalisation as the desktop
 * rasterizer so the shell's nominal sizes land at the measured visual size.
 */
class AndroidText(
    context: Context,
    private val contentScaleProvider: () -> Double = { 1.0 },
) : TextRasterizer {

    private val faces = HashMap<Pair<Face, Boolean>, Typeface>()
    private val scale = HashMap<Face, Double>()
    private val paints = HashMap<Triple<Face, Boolean, Int>, Paint>()

    init {
        val am = context.assets
        val files = mapOf(
            (Face.SYSTEM to false) to "fonts/ClearSans-Regular.ttf",
            (Face.SYSTEM to true) to "fonts/ClearSans-Bold.ttf",
            (Face.LIST to false) to "fonts/FiraSans-Regular.otf",
            (Face.LIST to true) to "fonts/FiraSans-Bold.otf",
            (Face.READER to false) to "fonts/Alegreya-Regular.otf",
            (Face.READER to true) to "fonts/Alegreya-Bold.otf",
            (Face.MONO to false) to "fonts/JetBrainsMono-Regular.ttf",
            (Face.MONO to true) to "fonts/JetBrainsMono-Bold.ttf",
        )
        for ((key, path) in files) {
            faces[key] = try {
                Typeface.createFromAsset(am, path)
            } catch (e: Exception) {
                throw LintError("bundled font $path unreadable — the locked faces are required: $e")
            }
        }
        for (face in Face.entries) {
            val xh = xHeightRatio(faces.getValue(face to false))
            scale[face] = if (xh > 0.01) BASE_XHEIGHT / xh else 1.0
            Log.i("fonts", "${face.family}: x-height %.3f, scale %.2f".format(xh, scale[face]))
        }
    }

    private fun xHeightRatio(tf: Typeface): Double {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = tf
        p.textSize = 100f
        val bounds = android.graphics.Rect()
        p.getTextBounds("x", 0, 1, bounds)
        return bounds.height() / 100.0
    }

    private fun paint(spec: FontSpec): Paint {
        val contentScale = if (spec.face == Face.SYSTEM) 1.0 else contentScaleProvider()
        val px = Math.round(spec.sizePx * scale.getValue(spec.face) * contentScale).toInt().coerceAtLeast(7)
        return paints.getOrPut(Triple(spec.face, spec.bold, px)) {
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = faces.getValue(spec.face to spec.bold)
                textSize = px.toFloat()
                color = 0xFFFFFFFF.toInt()
            }
        }
    }

    override fun measure(text: String, font: FontSpec): Int =
        if (text.isEmpty()) 0 else Math.ceil(paint(font).measureText(text).toDouble()).toInt()

    override fun metrics(font: FontSpec): FontMetrics {
        val fm = paint(font).fontMetricsInt
        return FontMetrics(-fm.ascent, fm.descent, fm.descent - fm.ascent)
    }

    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        if (text.isEmpty()) return
        val p = paint(font)
        val fm = p.fontMetricsInt
        val w = measure(text, font) + 4
        val h = (fm.descent - fm.ascent) + 4
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.drawText(text, 0f, (-fm.ascent).toFloat(), p)
        val pixels = ByteArray(w * h)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(pixels))
        bmp.recycle()
        for (yy in 0 until h) {
            val row = yy * w
            for (xx in 0 until w) {
                val cov = pixels[row + xx].toInt() and 0xFF
                if (cov > 0) surface.blend(x + xx, y + yy, level, cov)
            }
        }
    }

    override fun covers(text: String, font: FontSpec): Boolean {
        // Typeface has no cmap query; approximate by comparing against tofu
        // width — Paint.hasGlyph exists on API 23+ and is the real check.
        val p = paint(font)
        for (ch in text) if (!p.hasGlyph(ch.toString())) return false
        return true
    }

    companion object {
        const val BASE_XHEIGHT = 0.550
    }
}
