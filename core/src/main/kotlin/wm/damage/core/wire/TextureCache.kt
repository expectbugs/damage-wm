package wm.damage.core.wire

import wm.damage.core.geom.LintError
import wm.damage.core.gfx.Rle

/**
 * Layout of the CFW's 64 KiB texture cache (g2flash a5d1c31,
 * `patches/texture_cache.c`). The cache is written with mode 12, read by modes
 * 13 (one image) and 14 (a string of cached glyphs).
 *
 * On the wire a cached image is
 *
 *     [width:u8][height:u8][4bpp RLE covering exactly width*height pixels]
 *
 * — the same RLE tokens as modes 3/6, but over a bare pixel run: there is NO pad
 * nibble at the end of an odd-width row, because the firmware's scanner walks
 * tokens until the pixel count is met and never consults a row stride.
 *
 * A mode-14 "font" is nothing but a table of 96 little-endian uint16 offsets, one
 * per character 32..127, each pointing at a cached image elsewhere in the cache.
 * Several fonts can share one cache and share glyphs.
 *
 * Two properties this builder maintains deliberately:
 *
 *  - **Offsets 0 and 1 are left zero.** The firmware rejects an image whose width
 *    or height byte is 0, so a font-table entry that was never filled in points at
 *    a guaranteed-rejected image rather than at whatever happens to sit at the
 *    start of the cache.
 *  - **Every table entry is filled.** The firmware validates every character of a
 *    mode-14 string before it draws any of it, so one unmapped character would
 *    silently drop the whole line. Unmapped characters get a visible tofu box
 *    instead — a missing glyph should look wrong, not look like nothing.
 *
 * The cache is lease-scoped: the firmware allocates and zeroes it on the first
 * mode-12 write and frees it when the framebuffer lease ends (or on mode 11), so
 * a session uploads its atlas once after acquiring the lease.
 */
object TextureCache {
    /** Offsets [0,GUARD) stay zero so an unwritten table entry is always rejected. */
    const val GUARD = 2

    const val FIRST_CHAR = 32
    const val LAST_CHAR = 127

    /** One 4bpp image destined for the cache: [levels] is row-major, one 0..15 per pixel. */
    class Image(val w: Int, val h: Int, val levels: ByteArray) {
        init {
            if (w !in 1..CfwModes.MAX_TEXTURE_DIM || h !in 1..CfwModes.MAX_TEXTURE_DIM)
                throw LintError("cached image ${w}x$h is outside 1..${CfwModes.MAX_TEXTURE_DIM} " +
                    "(width and height are u8 fields)")
            if (levels.size != w * h)
                throw LintError("cached image ${w}x$h given ${levels.size} levels, expected ${w * h}")
        }

        /** [w][h][RLE] exactly as the firmware's scanner expects. */
        fun encode(): ByteArray {
            val rle = Rle.encodeLevels(levels)
            val out = ByteArray(2 + rle.size)
            out[0] = w.toByte()
            out[1] = h.toByte()
            rle.copyInto(out, 2)
            return out
        }
    }

    /**
     * A 96-entry offset table placed in the cache, addressed by mode 14.
     *
     * [glyphWidths] is the authority on advance: mode 14 moves the pen by each
     * glyph's cached image WIDTH and nothing else, so the widths recorded here when
     * the atlas was packed are exactly what the firmware will do. Nothing should
     * ever supply a width from outside the atlas — a metric advance that disagrees
     * with the image would misplace every glyph after it, silently.
     */
    class Font(val tableOffset: Int, val glyphOffsets: IntArray, val glyphWidths: IntArray) {
        /** The pen advance for [ch], in pixels. */
        fun width(ch: Char): Int {
            val i = ch.code - FIRST_CHAR
            if (i !in glyphOffsets.indices)
                throw LintError("U+%04X is outside a cached font's %d..%d range"
                    .format(ch.code, FIRST_CHAR, LAST_CHAR))
            return glyphWidths[i]
        }

        /** The width of [text] as mode 14 will actually lay it out, kerning included. */
        fun measure(text: String, kern: (Char, Char) -> Int = { _, _ -> 0 }): Int {
            var w = 0
            var prev: Char? = null
            for (ch in text) {
                prev?.let { w += kern(it, ch) }
                w += width(ch)
                prev = ch
            }
            return w
        }
    }

    /**
     * Packs images and font tables into one cache image, then emits the mode-12
     * messages that write it. Deduplicates identical glyphs by their encoded bytes,
     * which is most of why an atlas fits: a face's blank-ish glyphs collapse.
     */
    class Builder {
        private val bytes = java.io.ByteArrayOutputStream().apply { write(ByteArray(GUARD)) }
        private val seen = HashMap<String, Int>()

        val used: Int get() = bytes.size()
        val free: Int get() = CfwModes.TEXTURE_CACHE_SIZE - bytes.size()

        /** Add an image, returning its cache offset. Identical bytes reuse an offset. */
        fun add(image: Image): Int = addEncoded(image.encode())

        private fun addEncoded(enc: ByteArray): Int {
            val key = enc.toHexKey()
            seen[key]?.let { return it }
            val off = bytes.size()
            if (off + enc.size > CfwModes.TEXTURE_CACHE_SIZE)
                throw LintError("texture atlas overflows the ${CfwModes.TEXTURE_CACHE_SIZE} B " +
                    "cache: $off B used, ${enc.size} B more needed")
            bytes.write(enc)
            seen[key] = off
            return off
        }

        /**
         * Reserve and fill a font table for [glyphs] (character -> image). Every
         * character 32..127 gets an entry; anything absent from [glyphs] points at
         * [tofu], which is added to the cache if it is not already there.
         */
        fun addFont(glyphs: Map<Char, Image>, tofu: Image): Font {
            val offsets = IntArray(CfwModes.FONT_TABLE_CHARS)
            val widths = IntArray(CfwModes.FONT_TABLE_CHARS)
            val tofuOffset = add(tofu)
            for (c in FIRST_CHAR..LAST_CHAR) {
                val g = glyphs[c.toChar()]
                offsets[c - FIRST_CHAR] = if (g != null) add(g) else tofuOffset
                widths[c - FIRST_CHAR] = (g ?: tofu).w
            }
            val table = ByteArray(CfwModes.FONT_TABLE_BYTES)
            for (i in offsets.indices) {
                table[i * 2] = (offsets[i] and 0xFF).toByte()
                table[i * 2 + 1] = ((offsets[i] shr 8) and 0xFF).toByte()
            }
            val off = bytes.size()
            if (off + table.size > CfwModes.TEXTURE_CACHE_SIZE)
                throw LintError("no room for a ${table.size} B font table: $off B of " +
                    "${CfwModes.TEXTURE_CACHE_SIZE} used")
            bytes.write(table)
            return Font(off, offsets, widths)
        }

        /** The packed cache contents, guard included. */
        fun content(): ByteArray = bytes.toByteArray()

        /**
         * The mode-12 messages that write everything added so far, each no larger
         * than [maxMessage] bytes. Split rather than sent as one 64 KiB message so
         * a slow link makes visible progress and a rejection names a smaller range.
         */
        fun messages(maxMessage: Int = 3072): List<ByteArray> {
            if (maxMessage <= 5) throw LintError("mode-12 chunk size $maxMessage leaves no room for data")
            val all = content()
            // The guard is already zero in a freshly allocated cache; never write it.
            if (all.size <= GUARD) throw LintError("texture atlas is empty")
            val out = ArrayList<ByteArray>()
            var pos = GUARD
            while (pos < all.size) {
                val room = maxMessage - 5          // [12] + [off16][len16]
                val n = minOf(room, all.size - pos)
                out += CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(pos, all.copyOfRange(pos, pos + n))))
                pos += n
            }
            return out
        }

        private fun ByteArray.toHexKey(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
            return sb.toString()
        }
    }

    /**
     * Encode [text] as mode-14 string bytes for [font].
     *
     * **Glyph images are advance-width boxes.** Mode 14 moves the pen by the cached
     * image's width and by nothing else, so the way to express side bearings is to
     * rasterize each glyph into an image as wide as its advance, with the bearings
     * as blank columns inside it. Blank columns are one RLE run each and, with the
     * transparent option set, paint nothing — so they are very nearly free, and they
     * keep the pen exactly where the type designer put it.
     *
     * That leaves the inline adjust bytes free for their real job: **kerning**.
     * [kern] gives the pair adjustment for (left, right) in pixels, which the
     * firmware limits to −10…+20. An adjustment lands *before* the right-hand glyph,
     * which is where the firmware applies it (`texture_cache.c`: an adjust byte
     * moves x, then the next glyph draws at the new x).
     */
    fun layout(text: String, font: Font, kern: (Char, Char) -> Int = { _, _ -> 0 }): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var prev: Char? = null
        for (ch in text) {
            val c = ch.code
            if (c < FIRST_CHAR || c > LAST_CHAR)
                throw LintError("mode-14 cannot draw U+%04X ('%s'): a cached font covers only %d..%d"
                    .format(c, ch, FIRST_CHAR, LAST_CHAR))
            prev?.let { p ->
                val dx = kern(p, ch)
                if (dx != 0) out.write(CfwModes.xAdjust(dx).toInt())
            }
            out.write(c)
            prev = ch
        }
        val bytes = out.toByteArray()
        if (bytes.size > 0xFF)
            throw LintError("mode-14 string for \"$text\" needs ${bytes.size} B with its " +
                "kerning, past the u8 length — split the line")
        return bytes
    }
}
