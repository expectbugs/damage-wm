package wm.damage.core.sim

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Zl

/**
 * C3 ML1 (decisions BZ): the firmware model's pixel loops over a packed 4bpp buffer (row [stride] bytes, the high nibble
 * the left pixel), in one place. The phone keeps a private model as its mirror and applies every message it writes to it
 * inside the write call; his first stage-5 session spent about 170 ms there on a flush that carried a PLAY (ML1, measured
 * in his journal), the model's per-pixel loops run 70–93 times slower on his phone than on the PC.
 *
 * [FastPixelLoops] writes the same nibbles with row spans and byte copies, and returns the same values. A test keeps the
 * plain loops these replaced and installs them on its own thread ([withPlain]) to replay the same messages through two
 * models and compare every buffer and the engine's state after each message (`MirrorLoopsReplayTest`). Every fast form
 * takes the per-pixel path for a row whose bytes are not all inside the buffer, so a rect out of range fails as before.
 */
internal interface PixelLoops {
    /** [shadow] into [fb] at every panel pixel outside every rect of [rects] (§7.3: the direct path with owners open). */
    fun copyOutside(shadow: ByteArray, fb: ByteArray, stride: Int, rects: List<Rect>)
    /** [r] of [from] into the same place of [to]. */
    fun copy(from: ByteArray, to: ByteArray, stride: Int, r: Rect)
    /** Every pixel of [r] written with [level] as one nibble write does. */
    fun fill(buf: ByteArray, stride: Int, r: Rect, level: Int)
    /** [r] of [buf] as levels, row-major. */
    fun grab(buf: ByteArray, stride: Int, r: Rect): ByteArray
    /** §5.6: [r] of [buf] as tight packed rows, (w+1)/2 bytes, high nibble first. */
    fun tightPack(buf: ByteArray, stride: Int, r: Rect): ByteArray
    /** Tight packed rows of [w]×[h] at [off] as levels, row-major. */
    fun unpack(cache: ByteArray, off: Int, w: Int, h: Int): ByteArray
    /** A source's levels [px] (rows [w] wide, its pixel (0, 0) at ([x0], [y0])) into rows [yA]..[yB) and columns
     *  [xA]..[xB) of [buf]; [options] null draws the levels as they are, else §2's v1 LUT (level bits 0-3, bit 4
     *  transparent tested on the source, bit 5 inverse). */
    fun blit(buf: ByteArray, stride: Int, px: ByteArray, w: Int, x0: Int, y0: Int, xA: Int, xB: Int, yA: Int, yB: Int, options: Int?)
    /** [count] levels [v] into [px] from [from] (a record's RLE token). */
    fun levels(px: ByteArray, from: Int, count: Int, v: Byte)
    /** A frame's RLE run, for [Zl.inflateRleInto]. */
    val rleRun: Zl.NibbleRun

    companion object {
        private val plain = ThreadLocal<PixelLoops?>()

        /** The loops in force on this thread: the fast ones, unless a test installed its own. */
        fun current(): PixelLoops = plain.get() ?: FastPixelLoops

        /** [block] with [loops] in force on this thread (the replay test's reference model). */
        fun <T> withPlain(loops: PixelLoops, block: () -> T): T {
            val prior = plain.get()
            plain.set(loops)
            try { return block() } finally { plain.set(prior) }
        }
    }
}

internal object FastPixelLoops : PixelLoops {
    private fun nib(buf: ByteArray, i: Int, x: Int): Int {
        val b = buf[i].toInt() and 0xFF
        return if (x and 1 == 0) b shr 4 else b and 0x0F
    }

    private fun setNib(buf: ByteArray, i: Int, x: Int, v: Int) {
        val b = buf[i].toInt() and 0xFF
        buf[i] = (if (x and 1 == 0) (v shl 4) or (b and 0x0F) else (b and 0xF0) or v).toByte()
    }

    /** Pixels [a]..[b) of the row at byte [row] of [src] into [dst]: an odd first pixel and an even last one by nibble,
     *  the bytes between them whole. */
    private fun copySpan(src: ByteArray, dst: ByteArray, row: Int, a: Int, b: Int) {
        if (a >= b) return
        val lo = row + (a shr 1)
        val hi = row + ((b - 1) shr 1)
        if (lo < 0 || hi >= src.size || hi >= dst.size) {
            for (x in a until b) { val i = row + (x shr 1); setNib(dst, i, x, nib(src, i, x)) }
            return
        }
        var x = a
        if (x and 1 == 1) {
            val i = row + (x shr 1)
            dst[i] = ((dst[i].toInt() and 0xF0) or (src[i].toInt() and 0x0F)).toByte()
            x++
        }
        val bytes = (b - x) shr 1
        if (bytes > 0) {
            System.arraycopy(src, row + (x shr 1), dst, row + (x shr 1), bytes)
            x += bytes shl 1
        }
        if (x < b) {
            val i = row + (x shr 1)
            dst[i] = ((src[i].toInt() and 0xF0) or (dst[i].toInt() and 0x0F)).toByte()
        }
    }

    override fun copyOutside(shadow: ByteArray, fb: ByteArray, stride: Int, rects: List<Rect>) {
        val w = Geometry.PANEL_W
        val spans = IntArray(2 * rects.size)
        for (y in 0 until Geometry.PANEL_H) {
            val row = y * stride
            // the covered columns of this row, each rect clipped to the panel, ordered by start
            var n = 0
            for (r in rects) {
                if (y < r.y || y >= r.bottom) continue
                val a = maxOf(0, r.x); val b = minOf(w, r.right)
                if (a >= b) continue
                var k = n
                while (k > 0 && spans[2 * (k - 1)] > a) { spans[2 * k] = spans[2 * (k - 1)]; spans[2 * k + 1] = spans[2 * (k - 1) + 1]; k-- }
                spans[2 * k] = a; spans[2 * k + 1] = b
                n++
            }
            var x = 0
            for (k in 0 until n) {
                val a = spans[2 * k]; val b = spans[2 * k + 1]
                if (a > x) copySpan(shadow, fb, row, x, a)
                if (b > x) x = b
            }
            if (x < w) copySpan(shadow, fb, row, x, w)
        }
    }

    override fun copy(from: ByteArray, to: ByteArray, stride: Int, r: Rect) {
        for (y in r.y until r.bottom) copySpan(from, to, y * stride, r.x, r.right)
    }

    override fun fill(buf: ByteArray, stride: Int, r: Rect, level: Int) {
        if (r.w <= 0 || r.h <= 0) return
        // a whole byte written as two nibble writes, high then low, whatever it held
        val whole = (((level shl 4) and 0xF0) or level).toByte()
        for (y in r.y until r.bottom) {
            val row = y * stride
            val lo = row + (r.x shr 1); val hi = row + ((r.right - 1) shr 1)
            if (lo < 0 || hi >= buf.size) {
                for (x in r.x until r.right) setNib(buf, row + (x shr 1), x, level)
                continue
            }
            var x = r.x
            if (x and 1 == 1) { setNib(buf, row + (x shr 1), x, level); x++ }
            val bytes = (r.right - x) shr 1
            if (bytes > 0) {
                java.util.Arrays.fill(buf, row + (x shr 1), row + (x shr 1) + bytes, whole)
                x += bytes shl 1
            }
            if (x < r.right) setNib(buf, row + (x shr 1), x, level)
        }
    }

    override fun grab(buf: ByteArray, stride: Int, r: Rect): ByteArray {
        if (r.w <= 0 || r.h <= 0)                         // the plain form, as it was
            return ByteArray(r.w * r.h) { val x = r.x + it % r.w; nib(buf, (r.y + it / r.w) * stride + (x shr 1), x).toByte() }
        val out = ByteArray(r.w * r.h)
        var o = 0
        for (y in r.y until r.bottom) {
            val row = y * stride
            if (row + (r.x shr 1) < 0 || row + ((r.right - 1) shr 1) >= buf.size) {
                for (x in r.x until r.right) out[o++] = nib(buf, row + (x shr 1), x).toByte()
                continue
            }
            // an odd first column alone, then both nibbles of each byte, an even last column alone
            var x = r.x
            if (x and 1 == 1) { out[o++] = (buf[row + (x shr 1)].toInt() and 0x0F).toByte(); x++ }
            var i = row + (x shr 1)
            while (x + 1 < r.right) {
                val v = buf[i++].toInt()
                out[o++] = ((v shr 4) and 0x0F).toByte()
                out[o++] = (v and 0x0F).toByte()
                x += 2
            }
            if (x < r.right) out[o++] = ((buf[i].toInt() shr 4) and 0x0F).toByte()
        }
        return out
    }

    override fun tightPack(buf: ByteArray, stride: Int, r: Rect): ByteArray {
        val rb = (r.w + 1) / 2
        val out = ByteArray(rb * r.h)
        if (r.w <= 0 || r.h <= 0) return out
        val odd = r.w and 1 == 1
        for (yy in 0 until r.h) {
            val row = (r.y + yy) * stride
            val o = yy * rb
            val first = row + (r.x shr 1)
            val last = row + ((r.x + r.w - 1) shr 1)
            if (first < 0 || last >= buf.size) {
                for (xx in 0 until r.w) {
                    val x = r.x + xx
                    val v = nib(buf, row + (x shr 1), x)
                    val i = o + xx / 2
                    out[i] = (if (xx and 1 == 0) v shl 4 else (out[i].toInt() and 0xF0) or v).toByte()
                }
                continue
            }
            if (r.x and 1 == 0) {
                System.arraycopy(buf, first, out, o, r.w shr 1)
                if (odd) out[o + rb - 1] = (buf[last].toInt() and 0xF0).toByte()
            } else {
                var j = first
                for (k in 0 until (r.w shr 1)) {
                    out[o + k] = (((buf[j].toInt() and 0x0F) shl 4) or ((buf[j + 1].toInt() and 0xFF) shr 4)).toByte()
                    j++
                }
                if (odd) out[o + rb - 1] = ((buf[j].toInt() and 0x0F) shl 4).toByte()
            }
        }
        return out
    }

    override fun unpack(cache: ByteArray, off: Int, w: Int, h: Int): ByteArray {
        val rb = (w + 1) / 2
        if (w <= 0 || h <= 0)                             // the plain form, as it was
            return ByteArray(w * h) { p -> nib(cache, off + (p / w) * rb + (p % w) / 2, p % w).toByte() }
        val px = ByteArray(w * h)
        for (y in 0 until h) {
            val src = off + y * rb
            val o = y * w
            var x = 0
            while (x + 1 < w) {
                val b = cache[src + (x shr 1)].toInt() and 0xFF
                px[o + x] = (b shr 4).toByte()
                px[o + x + 1] = (b and 0x0F).toByte()
                x += 2
            }
            if (x < w) px[o + x] = ((cache[src + (x shr 1)].toInt() and 0xFF) shr 4).toByte()
        }
        return px
    }

    override fun blit(buf: ByteArray, stride: Int, px: ByteArray, w: Int, x0: Int, y0: Int, xA: Int, xB: Int, yA: Int, yB: Int, options: Int?) {
        if (xA >= xB) return
        val top = (options ?: 15) and 0x0F
        val inverse = options != null && options and 0x20 != 0
        val transparent = options != null && options and 0x10 != 0
        for (yy in yA until yB) {
            val row = yy * stride
            val src = (yy - y0) * w - x0                      // px[src + xx] is the source pixel under column xx
            val inside = row + (xA shr 1) >= 0 && row + ((xB - 1) shr 1) < buf.size && src + xA >= 0 && src + xB - 1 < px.size
            var xx = xA
            if (inside) {
                // an odd first column alone, then whole bytes (two nibble writes, high then low; a pair with a transparent
                // pixel by nibble), an even last column alone
                if (xx and 1 == 1) {
                    val c = px[src + xx].toInt()
                    if (!(transparent && c == 0))
                        setNib(buf, row + (xx shr 1), xx, if (options == null) c else ((if (inverse) 15 - c else c) * top) / 15)
                    xx++
                }
                while (xx + 1 < xB) {
                    val c0 = px[src + xx].toInt(); val c1 = px[src + xx + 1].toInt()
                    val v0 = if (options == null) c0 else ((if (inverse) 15 - c0 else c0) * top) / 15
                    val v1 = if (options == null) c1 else ((if (inverse) 15 - c1 else c1) * top) / 15
                    val i = row + (xx shr 1)
                    if (transparent && (c0 == 0 || c1 == 0)) {
                        if (c0 != 0) setNib(buf, i, xx, v0)
                        if (c1 != 0) setNib(buf, i, xx + 1, v1)
                    } else buf[i] = (((v0 shl 4) and 0xF0) or v1).toByte()
                    xx += 2
                }
            }
            while (xx < xB) {
                val c = px[src + xx].toInt()
                if (!(transparent && c == 0))
                    setNib(buf, row + (xx shr 1), xx, if (options == null) c else ((if (inverse) 15 - c else c) * top) / 15)
                xx++
            }
        }
    }

    override fun levels(px: ByteArray, from: Int, count: Int, v: Byte) {
        if (count <= 0) return
        if (from < 0 || from + count > px.size) { var n = from; repeat(count) { px[n++] = v }; return }
        java.util.Arrays.fill(px, from, from + count, v)
    }

    override val rleRun: Zl.NibbleRun = Zl.FAST_RUN
}
