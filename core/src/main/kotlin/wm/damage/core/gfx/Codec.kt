package wm.damage.core.gfx

import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The firmware's image codec chain: quantize -> pack 4bpp -> nibble RLE -> deflate.
 *
 * RLE format per g2flash/patches/zlib_glue.c (modes 3 and 6 only). Runs over the
 * pixel NIBBLES of the tightly packed rows in wire order (high nibble = left
 * pixel), including the pad nibble that ends an odd-width row; runs may cross
 * row boundaries. Token:
 *
 *     [cnt4|color4]                  cnt 1..15      (1 byte)
 *     [0|color4][cnt8]               cnt 1..255     (2 bytes)
 *     [0|color4][0][cntLo][cntHi]    cnt 1..65535   (4 bytes, little-endian)
 *
 * The low nibble is ALWAYS the colour; a high nibble of 0 escapes to the wider
 * forms; 65535 is the longest single run and an encoder splits longer ones.
 * This is a line-for-line twin of research/fbfeas.py rle_nibble(), which was
 * verified by round-tripping 301 cases through a port of the firmware decoder;
 * RleParityTest pins this implementation to vectors generated from that file.
 */
object Rle {
    fun encode(packed: ByteArray): ByteArray {
        val out = ArrayList<Byte>(packed.size / 2 + 16)
        val n = packed.size * 2
        var i = 0
        while (i < n) {
            val c = nib(packed, i)
            var run = 1
            while (i + run < n && nib(packed, i + run) == c && run < 65535) run++
            i += run
            var left = run
            while (left > 0) {
                val take = minOf(left, 65535)
                when {
                    take <= 15 -> out.add(((take shl 4) or c).toByte())
                    take <= 255 -> { out.add(c.toByte()); out.add(take.toByte()) }
                    else -> {
                        out.add(c.toByte()); out.add(0)
                        out.add((take and 0xFF).toByte()); out.add(((take shr 8) and 0xFF).toByte())
                    }
                }
                left -= take
            }
        }
        return out.toByteArray()
    }

    /** Decode into exactly [expectedNibbles] nibbles of packed bytes. The firmware
     *  rejects a stream that decodes to any other count and keeps the previous
     *  frame; we mirror that as a loud error. */
    fun decode(rle: ByteArray, expectedNibbles: Int): ByteArray {
        val out = ByteArray((expectedNibbles + 1) / 2)
        var nOut = 0
        var i = 0
        while (i < rle.size) {
            val b = rle[i].toInt() and 0xFF
            val color = b and 0x0F
            var cnt = (b shr 4) and 0x0F
            i += 1
            if (cnt == 0) {
                if (i >= rle.size) throw LintError("RLE truncated in 8-bit escape")
                cnt = rle[i].toInt() and 0xFF
                i += 1
                if (cnt == 0) {
                    if (i + 1 >= rle.size) throw LintError("RLE truncated in 16-bit escape")
                    cnt = (rle[i].toInt() and 0xFF) or ((rle[i + 1].toInt() and 0xFF) shl 8)
                    i += 2
                    if (cnt == 0) throw LintError("RLE zero-length 16-bit run")
                }
            }
            if (nOut + cnt > expectedNibbles)
                throw LintError("RLE decodes past expected $expectedNibbles nibbles " +
                    "(at $nOut, run $cnt) — firmware would reject and keep the previous frame")
            repeat(cnt) {
                val idx = nOut shr 1
                out[idx] = if (nOut and 1 == 0) ((color shl 4) or (out[idx].toInt() and 0x0F)).toByte()
                else ((out[idx].toInt() and 0xF0) or color).toByte()
                nOut++
            }
        }
        if (nOut != expectedNibbles)
            throw LintError("RLE decoded $nOut nibbles, expected $expectedNibbles — " +
                "firmware would reject and keep the previous frame")
        return out
    }

    /**
     * The same tokens over a bare run of 4bpp LEVELS rather than the nibbles of
     * packed rows. Cached texture images (modes 13/14) carry exactly width*height
     * pixels with NO pad nibble at the end of an odd-width row — see
     * g2flash/patches/texture_cache.c cfw_texture_image_at(), which walks tokens
     * until the pixel count is met and never consults a row stride.
     */
    fun encodeLevels(levels: ByteArray): ByteArray {
        val out = ArrayList<Byte>(levels.size / 2 + 16)
        var i = 0
        while (i < levels.size) {
            val c = levels[i].toInt() and 0x0F
            if ((levels[i].toInt() and 0xFF) > 15)
                throw LintError("texture level ${levels[i].toInt() and 0xFF} at $i is not 0..15")
            var run = 1
            while (i + run < levels.size && (levels[i + run].toInt() and 0x0F) == c &&
                (levels[i + run].toInt() and 0xFF) <= 15 && run < 65535
            ) run++
            i += run
            emit(out, c, run)
        }
        return out.toByteArray()
    }

    /** Decode exactly [pixels] levels; the firmware rejects any other count. */
    fun decodeLevels(rle: ByteArray, pixels: Int): ByteArray {
        val out = ByteArray(pixels)
        var n = 0
        var i = 0
        while (i < rle.size) {
            val b = rle[i].toInt() and 0xFF
            val color = b and 0x0F
            var cnt = (b shr 4) and 0x0F
            i += 1
            if (cnt == 0) {
                if (i >= rle.size) throw LintError("texture RLE truncated in 8-bit escape")
                cnt = rle[i].toInt() and 0xFF
                i += 1
                if (cnt == 0) {
                    if (i + 1 >= rle.size) throw LintError("texture RLE truncated in 16-bit escape")
                    cnt = (rle[i].toInt() and 0xFF) or ((rle[i + 1].toInt() and 0xFF) shl 8)
                    i += 2
                    if (cnt == 0) throw LintError("texture RLE zero-length 16-bit run")
                }
            }
            if (n + cnt > pixels)
                throw LintError("texture RLE decodes past $pixels pixels (at $n, run $cnt)")
            repeat(cnt) { out[n++] = color.toByte() }
        }
        if (n != pixels) throw LintError("texture RLE decoded $n levels, expected $pixels")
        return out
    }

    private fun emit(out: ArrayList<Byte>, color: Int, count: Int) {
        var left = count
        while (left > 0) {
            val take = minOf(left, 65535)
            when {
                take <= 15 -> out.add(((take shl 4) or color).toByte())
                take <= 255 -> { out.add(color.toByte()); out.add(take.toByte()) }
                else -> {
                    out.add(color.toByte()); out.add(0)
                    out.add((take and 0xFF).toByte()); out.add(((take shr 8) and 0xFF).toByte())
                }
            }
            left -= take
        }
    }

    private fun nib(b: ByteArray, i: Int): Int {
        val v = b[i shr 1].toInt() and 0xFF
        return if (i and 1 == 0) (v shr 4) else (v and 0x0F)
    }
}

object Pack {
    /** 0..255 gray -> 0..15 level, same rounding as render_shots.py quantize(). */
    fun level(v: Int): Int = minOf(15, (v + 8) / 17)

    /** Pack a Gray8 rect into tight 4bpp rows, high nibble = left pixel, with a
     *  zero pad nibble ending an odd-width row (zlib_glue.c wire order). */
    fun rect(src: Gray8, r: Rect): ByteArray {
        val rowBytes = (r.w + 1) / 2
        val out = ByteArray(rowBytes * r.h)
        var o = 0
        for (y in r.y until r.bottom) {
            var x = r.x
            while (x < r.right) {
                val hi = level(src[x, y])
                val lo = if (x + 1 < r.right) level(src[x + 1, y]) else 0
                out[o++] = ((hi shl 4) or lo).toByte()
                x += 2
            }
        }
        return out
    }

    /** Unpack tight 4bpp rows into a Gray8 (levels expanded n*17). */
    fun unpack(packed: ByteArray, w: Int, h: Int): Gray8 {
        val rowBytes = (w + 1) / 2
        require(packed.size == rowBytes * h) { "packed size ${packed.size} != $rowBytes*$h" }
        val out = Gray8(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val b = packed[y * rowBytes + (x shr 1)].toInt() and 0xFF
            val n = if (x and 1 == 0) (b shr 4) else (b and 0x0F)
            out[x, y] = n * 17
        }
        return out
    }

    fun inkFraction(s: Gray8): Double {
        var lit = 0
        for (b in s.pix) if (Pack.level(b.toInt() and 0xFF) > 0) lit++
        return lit.toDouble() / (s.w * s.h)
    }
}

/**
 * Deflate at level 6 — Faceclaw's measured result, adopted in DESIGN.md/§12:
 * level 9 costs 18-109 ms per frame on the worker; level 1 inflates typical
 * payloads past the 3800 B fragment boundary and adds a whole ack round trip.
 * One long-lived Deflater per thread: constructing one allocates a native zlib
 * stream, measurable at per-frame rates.
 */
object Zl {
    private val deflaters = ThreadLocal.withInitial { Deflater(6) }

    fun deflate(data: ByteArray): ByteArray {
        val d = deflaters.get()
        d.reset()
        d.setInput(data)
        d.finish()
        val buf = ByteArray(maxOf(64, data.size + data.size / 2 + 32))
        var total = 0
        val chunks = ArrayList<ByteArray>()
        while (!d.finished()) {
            val n = d.deflate(buf)
            if (n > 0) { chunks.add(buf.copyOf(n)); total += n }
        }
        val out = ByteArray(total)
        var o = 0
        for (c in chunks) { c.copyInto(out, o); o += c.size }
        return out
    }

    /** Inflate with the firmware's window (windowBits 15 = 32 KB — zlib_glue.c
     *  FW_INIT2(strm, 15, ...)); java.util.zip uses exactly that. */
    fun inflate(data: ByteArray, expectedMax: Int): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(data)
            val out = ByteArray(expectedMax)
            var o = 0
            while (!inf.finished()) {
                val n = inf.inflate(out, o, out.size - o)
                if (n == 0 && inf.needsDictionary()) throw LintError(
                    "zlib stream demands a preset dictionary (FDICT) — refused, the firmware has none")
                if (n == 0 && inf.needsInput()) throw LintError("zlib stream truncated")
                o += n
                if (o == out.size && !inf.finished())
                    throw LintError("zlib inflates past expected $expectedMax bytes")
            }
            return out.copyOf(o)
        } finally {
            inf.end()
        }
    }

    /** The full mode-3/6 payload chain. */
    fun encodeCfw(packed: ByteArray): ByteArray = deflate(Rle.encode(packed))

    fun decodeCfw(z: ByteArray, expectedNibbles: Int): ByteArray {
        // RLE worst case is ~2 tokens per nibble pair; bound the inflate generously.
        val rle = inflate(z, expectedNibbles * 2 + 64)
        return Rle.decode(rle, expectedNibbles)
    }

    /**
     * The firmware's own mode-3/6 decode: it inflates in small chunks and RLE-decodes
     * straight into the destination rows, so **a stream that fails part-way leaves the
     * pixels it had already written**. [decodeCfw] above decodes to a buffer first and
     * is the encoder's inverse; this is what the glasses do, and the model needs it to
     * hold the same shadow after a refusal (`g2flash/patches/zlib_glue.c :: inflate_rle`
     * with `patches/rle.c` — behaviour read, implementation ours; 2026-09-16 review).
     *
     * [dst] holds [rows] rows of [rowBytes] bytes, [stride] bytes apart, the first at
     * [off] — mode 6 writes the whole shadow (stride == rowBytes), mode 3 the box inside
     * it. Runs cross rows freely: the nibble stream is the wire order, not per-row, and
     * the high nibble of a byte is its left pixel.
     *
     * Returns true only when the stream ends cleanly on exactly `rowBytes * rows * 2`
     * nibbles with no token half-parsed — every other outcome is the firmware's reason 13.
     *
     * One stated modelling limit: zlib can report a data error in the same call that
     * produced output, and `java.util.zip` hands back either the bytes or the exception,
     * never both, so those bytes are not written here where the firmware would write
     * them. It needs bit corruption that survived the link's CRC to reach; a truncated
     * stream, an RLE that runs short and one that runs long — the shapes a defect on our
     * own side produces — are all exact.
     */
    fun inflateRleInto(z: ByteArray, dst: ByteArray, off: Int, stride: Int, rowBytes: Int, rows: Int): Boolean {
        val rs = RleSink(dst, off, stride, rowBytes, rows)
        val inf = Inflater()
        var ended = false
        try {
            inf.setInput(z)
            val chunk = ByteArray(256)                      // the firmware's RLE_CHUNK
            while (true) {
                val got = try { inf.inflate(chunk) } catch (e: java.util.zip.DataFormatException) { break }
                if (got > 0) rs.feed(chunk, got)
                if (rs.err) break
                if (inf.finished()) { ended = true; break }
                if (got == 0) break                         // truncated, or a preset dictionary: no progress
            }
        } finally {
            inf.end()
        }
        return ended && rs.done
    }
}

/**
 * The byte-at-a-time side of [Zl.inflateRleInto]: the token stream of `Rle` decoded
 * into a rectangular 4bpp destination as the bytes arrive. Separate from [Rle.decode]
 * because it models a firmware that cannot roll back — what it has written stays
 * written when the stream turns out to be malformed.
 */
private class RleSink(
    private val dst: ByteArray, private val off: Int,
    private val stride: Int, private val rowBytes: Int, rows: Int,
) {
    private var left = rowBytes.toLong() * rows * 2       // nibbles still expected
    private var pos = 0L                                  // nibbles written
    private val rowNibbles = rowBytes.toLong() * 2
    private val lastNibble = rowNibbles * rows
    private var st = 0                                    // 0 opcode · 1 cnt8 · 2 cntLo · 3 cntHi
    private var cnt = 0
    private var color = 0
    var err = false; private set

    /** A clean, exact decode: every expected nibble written, no token half-parsed. */
    val done: Boolean get() = !err && left == 0L && st == 0

    /** A run is written whole or not at all: one that would overrun the frame is the
     *  malformed case and nothing of it lands (the firmware checks the count first). */
    private fun emit(v: Int, n: Int) {
        if (n > left) { err = true; return }
        left -= n
        var k = n
        while (k > 0) {
            if (pos >= lastNibble) { err = true; return }
            val row = (pos / rowNibbles).toInt()
            val inRow = (pos - row * rowNibbles).toInt()
            val i = off + row * stride + (inRow shr 1)
            dst[i] = if (inRow and 1 == 0) (((v shl 4) or (dst[i].toInt() and 0x0F)).toByte())
                     else (((dst[i].toInt() and 0xF0) or v).toByte())
            pos++; k--
        }
    }

    fun feed(p: ByteArray, n: Int) {
        var i = 0
        while (i < n && !err) {
            val b = p[i].toInt() and 0xFF
            when (st) {
                0 -> {
                    color = b and 0x0F
                    val c = b shr 4
                    if (c != 0) emit(color, c) else st = 1      // the escape: the count follows
                }
                1 -> if (b != 0) { emit(color, b); st = 0 } else st = 2
                2 -> { cnt = b; st = 3 }
                else -> {
                    cnt = cnt or (b shl 8)
                    if (cnt == 0) err = true                    // a zero-length run cannot be encoded
                    else emit(color, cnt)
                    st = 0
                }
            }
            i++
        }
    }
}
