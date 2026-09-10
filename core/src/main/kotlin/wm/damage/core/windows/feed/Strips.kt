package wm.damage.core.windows.feed

import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.ImageDecoder

/**
 * FEED.md §2.6/§3.4 — a decoded image becomes a [Strip]: fit to the document
 * width (never upscaled; a multiple of 4 wide, an even height), inverted when
 * the §3.4 histogram says it is line art on a white page, quantized to
 * [levels] gray levels snapped ONTO the 16-level grid (no dithering — the
 * standing rule), and packed two pixels per byte. The window unpacks and
 * cuts it into 32 px rows for its Document.
 */
object Strips {

    const val STRIP_H = 32

    /** §3.4: more than 60 % of the pixels in the top two of 16 bins = a white page. */
    fun decideInvert(d: ImageDecoder.Decoded): Boolean {
        if (d.w <= 0 || d.h <= 0) return false
        var bright = 0L
        val n = d.w.toLong() * d.h
        for (b in d.gray) if ((b.toInt() and 0xFF) >= 224) bright++
        return bright * 100 / n > 60
    }

    fun prepare(d: ImageDecoder.Decoded, width: Int, levels: Int, mode: LineArt): Strip {
        require(d.w > 0 && d.h > 0) { "empty image" }
        val invert = when (mode) {
            LineArt.NEVER -> false
            LineArt.ALWAYS -> true
            LineArt.AUTO -> decideInvert(d)
        }
        val maxW = (width.coerceAtLeast(4) / 4) * 4
        val w = if (d.w <= maxW) (d.w / 4) * 4 else maxW
        val h = if (d.w <= maxW) (d.h / 2) * 2 else maxOf(2, (d.h.toLong() * w / d.w).toInt() / 2 * 2)
        val ww = w.coerceAtLeast(4)
        val lv = levels.coerceIn(2, 16)
        val packed = ByteArray(ww / 2 * h)
        for (y in 0 until h) {
            val sy0 = y * d.h / h
            val sy1 = maxOf(sy0 + 1, (y + 1) * d.h / h)
            for (x in 0 until ww) {
                val sx0 = x * d.w / ww
                val sx1 = maxOf(sx0 + 1, (x + 1) * d.w / ww)
                var sum = 0
                var cnt = 0
                for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                    if (sx >= d.w || sy >= d.h) continue
                    sum += d.gray[sy * d.w + sx].toInt() and 0xFF
                    cnt++
                }
                var v = if (cnt == 0) 0 else sum / cnt
                if (invert) v = 255 - v
                val q = quantize(v, lv)
                val i = y * (ww / 2) + (x shr 1)
                if (x and 1 == 0) packed[i] = (q shl 4).toByte()
                else packed[i] = (packed[i].toInt() or q).toByte()
            }
        }
        return Strip(ww, h, invert, lv, packed)
    }

    /** 0..255 → a 0..15 nibble through [levels] steps: 16 levels is the
     *  plain (v+8)/17 rounding every other image path uses; fewer levels
     *  snap onto the grid so the compositor's diff sees stable pixels. */
    fun quantize(v: Int, levels: Int): Int {
        if (levels >= 16) return ((v + 8) / 17).coerceIn(0, 15)
        val step = (v * (levels - 1) + 127) / 255              // 0..levels-1
        return ((step * 15 + (levels - 1) / 2) / (levels - 1)).coerceIn(0, 15)
    }

    fun unpack(s: Strip): Gray8 {
        val g = Gray8(s.w, s.h)
        val stride = s.w / 2
        for (y in 0 until s.h) for (x in 0 until s.w) {
            val b = s.packed[y * stride + (x shr 1)].toInt() and 0xFF
            val q = if (x and 1 == 0) b shr 4 else b and 15
            g[x, y] = q * 17
        }
        return g
    }

    /** Whole-row strips of [stripH]; the last one padded with unlit rows. */
    fun cut(g: Gray8, stripH: Int = STRIP_H): List<Gray8> {
        val out = ArrayList<Gray8>()
        var y = 0
        while (y < g.h) {
            val s = Gray8(g.w, stripH)
            val rows = minOf(stripH, g.h - y)
            System.arraycopy(g.pix, y * g.w, s.pix, 0, rows * g.w)
            out.add(s)
            y += stripH
        }
        return out
    }

    /** The lit fraction — for the record and the tests. */
    fun ink(s: Strip): Double {
        var lit = 0L
        val stride = s.w / 2
        for (y in 0 until s.h) for (x in 0 until s.w) {
            val b = s.packed[y * stride + (x shr 1)].toInt() and 0xFF
            val q = if (x and 1 == 0) b shr 4 else b and 15
            if (q > 0) lit++
        }
        return lit.toDouble() / (s.w.toLong() * s.h)
    }

    // ------------------------------------------------------------ the cache file form
    private val MAGIC = "DSTR".toByteArray(Charsets.US_ASCII)

    fun encode(s: Strip): ByteArray {
        val out = ByteArray(MAGIC.size + 6 + s.packed.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        var i = MAGIC.size
        out[i++] = (s.w shr 8).toByte(); out[i++] = s.w.toByte()
        out[i++] = (s.h shr 8).toByte(); out[i++] = s.h.toByte()
        out[i++] = (if (s.inverted) 1 else 0).toByte()
        out[i++] = s.levels.toByte()
        System.arraycopy(s.packed, 0, out, i, s.packed.size)
        return out
    }

    fun decode(b: ByteArray): Strip {
        require(b.size >= MAGIC.size + 6 && b.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not a strip file" }
        var i = MAGIC.size
        val w = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); i += 2
        val h = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); i += 2
        val inv = b[i++].toInt() != 0
        val lv = b[i++].toInt() and 0xFF
        val packed = b.copyOfRange(i, b.size)
        require(packed.size == w / 2 * h) { "strip file: ${packed.size} B for ${w}x$h" }
        return Strip(w, h, inv, lv, packed)
    }
}
