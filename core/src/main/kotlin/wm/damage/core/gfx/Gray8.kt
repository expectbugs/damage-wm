package wm.damage.core.gfx

import wm.damage.core.geom.Rect

/**
 * The composition surface: one byte per pixel, 0..255, quantized to the panel's
 * 16 levels only at pack time. Drawing primitives are pure Kotlin so they render
 * identically on desktop and Android; only TEXT goes through a platform seam
 * (wm.damage.core.text.TextRasterizer).
 *
 * Levels: the shell's restrained ramp (DESIGN.md §8.5) lives in [Level]; a level
 * n paints as n*17 here so quantization is exact and reversible.
 */
class Gray8(val w: Int, val h: Int) {
    val pix = ByteArray(w * h)

    fun clear(v: Int = 0) = pix.fill(v.toByte())

    operator fun get(x: Int, y: Int): Int = pix[y * w + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, v: Int) {
        if (x in 0 until w && y in 0 until h) pix[y * w + x] = v.toByte()
    }

    fun fillRect(r: Rect, v: Int) = fillRect(r.x, r.y, r.w, r.h, v)

    fun fillRect(x: Int, y: Int, rw: Int, rh: Int, v: Int) {
        val x0 = x.coerceAtLeast(0); val y0 = y.coerceAtLeast(0)
        val x1 = (x + rw).coerceAtMost(w); val y1 = (y + rh).coerceAtMost(h)
        // an EMPTY span paints nothing — the same as every other out-of-range
        // case here. Without the guard a negative width reaches Arrays.fill
        // with from > to, which THROWS: a fault inside a paint leaves the
        // content half-drawn with no damage hint (the L1 class), where a
        // no-op is what the clipping contract already promises everywhere else
        if (x1 <= x0 || y1 <= y0) return
        val b = v.toByte()
        for (yy in y0 until y1) pix.fill(b, yy * w + x0, yy * w + x1)
    }

    fun outlineRect(x: Int, y: Int, rw: Int, rh: Int, v: Int, width: Int = 1) {
        fillRect(x, y, rw, width, v)
        fillRect(x, y + rh - width, rw, width, v)
        fillRect(x, y, width, rh, v)
        fillRect(x + rw - width, y, width, rh, v)
    }

    /** Solid ellipse inside the box (x,y,rw,rh). */
    fun fillEllipse(x: Int, y: Int, rw: Int, rh: Int, v: Int) {
        if (rw <= 0 || rh <= 0) return
        val cx = x + rw / 2.0; val cy = y + rh / 2.0
        val ax = rw / 2.0; val ay = rh / 2.0
        for (yy in y until y + rh) {
            val dy = (yy + 0.5 - cy) / ay
            val t = 1.0 - dy * dy
            if (t < 0) continue
            val half = ax * kotlin.math.sqrt(t)
            val x0 = kotlin.math.ceil(cx - half - 0.5).toInt()
            val x1 = kotlin.math.floor(cx + half - 0.5).toInt()
            for (xx in x0..x1) set(xx, yy, v)
        }
    }

    /** Ellipse outline of the given stroke width (drawn as outer minus inner). */
    fun outlineEllipse(x: Int, y: Int, rw: Int, rh: Int, v: Int, width: Int) {
        val tmp = Gray8(rw + 2, rh + 2)
        tmp.fillEllipse(1, 1, rw, rh, 1)
        if (rw > 2 * width && rh > 2 * width)
            tmp.fillEllipse(1 + width, 1 + width, rw - 2 * width, rh - 2 * width, 0)
        for (yy in 0 until rh + 2) for (xx in 0 until rw + 2)
            if (tmp[xx, yy] != 0) set(x + xx - 1, y + yy - 1, v)
    }

    /** Thick line via a filled square brush along a Bresenham walk. */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, v: Int, width: Int = 1) {
        var x = x0; var y = y0
        val dx = kotlin.math.abs(x1 - x0); val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        val half = width / 2
        while (true) {
            fillRect(x - half, y - half, width, width, v)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** Scanline-filled polygon. */
    fun fillPolygon(xs: IntArray, ys: IntArray, v: Int) {
        if (xs.size < 3) return
        val minY = ys.min().coerceAtLeast(0)
        val maxY = ys.max().coerceAtMost(h - 1)
        val n = xs.size
        for (yy in minY..maxY) {
            val cuts = ArrayList<Double>(4)
            var j = n - 1
            for (i in 0 until n) {
                val yi = ys[i]; val yj = ys[j]
                if ((yi <= yy && yj > yy) || (yj <= yy && yi > yy)) {
                    cuts += xs[i] + (yy - yi).toDouble() / (yj - yi) * (xs[j] - xs[i])
                }
                j = i
            }
            cuts.sort()
            var k = 0
            while (k + 1 < cuts.size) {
                val a = kotlin.math.ceil(cuts[k] - 0.5).toInt()
                val b = kotlin.math.floor(cuts[k + 1] - 0.5).toInt()
                for (xx in a..b) set(xx, yy, v)
                k += 2
            }
        }
    }

    /** Copy a rect from another surface (same-size rows, clipped). */
    fun blit(src: Gray8, srcRect: Rect, dx: Int, dy: Int) {
        for (yy in 0 until srcRect.h) {
            val sy = srcRect.y + yy; val ty = dy + yy
            if (sy !in 0 until src.h || ty !in 0 until h) continue
            for (xx in 0 until srcRect.w) {
                val sx = srcRect.x + xx; val tx = dx + xx
                if (sx in 0 until src.w && tx in 0 until w) pix[ty * w + tx] = src.pix[sy * src.w + sx]
            }
        }
    }

    /** Alpha-blend a coverage value (0..255) of paint level `v` onto a pixel —
     *  the same compositing PIL does for AA text, so renders match render_shots. */
    fun blend(x: Int, y: Int, v: Int, coverage: Int) {
        if (x !in 0 until w || y !in 0 until h || coverage <= 0) return
        val i = y * w + x
        val bg = pix[i].toInt() and 0xFF
        pix[i] = ((bg * (255 - coverage) + v * coverage) / 255).toByte()
    }

    fun copy(): Gray8 {
        val out = Gray8(w, h)
        pix.copyInto(out.pix)
        return out
    }

    fun regionEquals(o: Gray8, r: Rect): Boolean {
        for (yy in r.y until r.bottom) for (xx in r.x until r.right)
            if (pix[yy * w + xx] != o.pix[yy * w + xx]) return false
        return true
    }
}

/** The restrained ramp — DESIGN.md §8.5: ~5 levels for UI, all 16 reserved for
 *  imagery. A level is 0..15; on the Gray8 surface it paints as n*17. */
object Level {
    fun of(n: Int): Int = n.coerceIn(0, 15) * 17
    val BG = of(0)
    val FAINT = of(1)
    val REST = of(2)      // resting-state names (§4.2)
    val DIM = of(3)       // rules, dim chrome, metadata
    val MID = of(6)
    val BODY = of(8)      // the reading level
    val HEAD = of(12)     // the thing the eye scans for
    val HOT = of(15)      // urgency; spent only when something is wrong
}
