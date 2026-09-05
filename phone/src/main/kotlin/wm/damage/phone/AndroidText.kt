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

    // concurrent: the reader lays books out on an IO thread while the shell
    // loop draws chrome (round 4 — two threads inserting the same new key)
    private val faces = java.util.concurrent.ConcurrentHashMap<Pair<Face, Boolean>, Typeface>()
    private val scale = java.util.concurrent.ConcurrentHashMap<Face, Double>()

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

    private val paintsFull = java.util.concurrent.ConcurrentHashMap<FontSpec, Paint>()

    // string-level caches (2026-09-05, GlyphCaches.kt): a measure and a
    // rendered coverage mask depend only on the text and the RESOLVED font.
    // On Android every uncached measure is a shaping pass and every uncached
    // draw allocates a bitmap — on the shell loop, per paint.
    private val measures = wm.damage.core.text.MeasureCache()
    private val rasters = wm.damage.core.text.RasterCache()

    private fun resolvedPx(spec: FontSpec): Int {
        val contentScale = if (spec.face == Face.SYSTEM) 1.0 else contentScaleProvider()
        return Math.round(spec.sizePx * scale.getValue(spec.face) * contentScale).toInt().coerceAtLeast(7)
    }

    private fun glyphKey(spec: FontSpec, text: String) =
        wm.damage.core.text.GlyphKey(spec.face, resolvedPx(spec), spec.bold, spec.italic, text)

    private fun paint(spec: FontSpec): Paint {
        val px = resolvedPx(spec)
        val key = spec.copy(sizePx = px)
        return paintsFull.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = faces.getValue(spec.face to spec.bold)
                textSize = px.toFloat()
                color = 0xFFFFFFFF.toInt()
                // no italic asset variants are bundled: synthesize the slant so
                // desktop and phone render the same spec the same way
                if (spec.italic) textSkewX = -0.25f
            }
        }
    }

    override fun measure(text: String, font: FontSpec): Int =
        if (text.isEmpty()) 0 else measures.get(glyphKey(font, text)) {
            Math.ceil(paint(font).measureText(text).toDouble()).toInt()
        }

    override fun metrics(font: FontSpec): FontMetrics {
        val fm = paint(font).fontMetricsInt
        return FontMetrics(-fm.ascent, fm.descent, fm.descent - fm.ascent)
    }

    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        if (text.isEmpty()) return
        val m = rasters.get(glyphKey(font, text)) { render(text, font) }
        val cov = m.cov
        for (yy in 0 until m.h) {
            val row = yy * m.w
            for (xx in 0 until m.w) {
                val c = cov[row + xx].toInt() and 0xFF
                if (c > 0) surface.blend(x + xx, y + yy, level, c)
            }
        }
    }

    /** The uncached path, unchanged: the string into a fresh ALPHA_8 bitmap
     *  at (0, ascent), read back as a TIGHT coverage mask. */
    private fun render(text: String, font: FontSpec): wm.damage.core.text.GlyphMask {
        val p = paint(font)
        val fm = p.fontMetricsInt
        val w = measure(text, font) + 4
        val h = (fm.descent - fm.ascent) + 4
        if (w <= 0 || h <= 0) return wm.damage.core.text.GlyphMask(0, 0, ByteArray(0))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        c.drawText(text, 0f, (-fm.ascent).toFloat(), p)
        // ALPHA_8 bitmaps may pad rows (getRowBytes >= width) — indexing by
        // width alone skews or crashes on padded devices (review round 1)
        val rowBytes = bmp.rowBytes
        val pixels = ByteArray(rowBytes * h)
        bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(pixels))
        bmp.recycle()
        val cov = ByteArray(w * h)
        for (yy in 0 until h) System.arraycopy(pixels, yy * rowBytes, cov, yy * w, w)
        return wm.damage.core.text.GlyphMask(w, h, cov)
    }

    override fun covers(text: String, font: FontSpec): Boolean {
        // Typeface has no cmap query; approximate by comparing against tofu
        // width — Paint.hasGlyph exists on API 23+ and is the real check.
        val p = paint(font)
        // by CODE POINT (review 2026-09-01 R4-K aside): a supplementary
        // character (an emoji) is two UTF-16 units, and asking for each half
        // said "uncovered" for every glyph the face actually has
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!p.hasGlyph(String(Character.toChars(cp)))) return false
        }
        return true
    }

    companion object {
        const val BASE_XHEIGHT = 0.550
    }
}
