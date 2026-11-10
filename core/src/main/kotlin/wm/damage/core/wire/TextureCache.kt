package wm.damage.core.wire

import wm.damage.core.geom.LintError
import wm.damage.core.gfx.Rle

/**
 * Layout of the CFW's texture cache (g2flash a5d1c31, `patches/texture_cache.c`; the
 * Damage build's contract 2, `FIRMWARE.md` §4). The cache is written with mode 12 (v1:
 * u16 byte offsets inside the first 64 KiB) or mode 19 (v2: u16 offsets in 4-byte units
 * over the session's size, up to 160 KiB), read by modes 13/14 (v1) and 17/18 (v2).
 *
 * On the wire a v1 cached image is
 *
 *     [width:u8][height:u8][4bpp RLE covering exactly width*height pixels]
 *
 * — the same RLE tokens as modes 3/6, but over a bare pixel run: there is NO pad
 * nibble at the end of an odd-width row, because the firmware's scanner walks
 * tokens until the pixel count is met and never consults a row stride. A v2 image
 * record is the same with u16 dimensions (1..640 by 1..2048). A glyph is a v1 record
 * in both contracts.
 *
 * A mode-14 "font" is a table of 96 little-endian uint16 byte offsets, one per
 * character 32..127; a mode-18 font is 224 offsets in 4-byte units, one per code
 * 32..255 (Latin-1). Several fonts can share one cache and share glyphs.
 *
 * Two properties this builder maintains deliberately:
 *
 *  - **The guard bytes at offset 0 stay zero.** The firmware rejects an image whose
 *    width or height is 0, so a table entry that was never filled in points at a
 *    guaranteed-rejected image rather than at whatever happens to sit at the start
 *    of the cache.
 *  - **Every table entry is filled.** The firmware validates every character of a
 *    string before it draws any of it, so one unmapped character would silently
 *    drop the whole line. Unmapped characters get a visible tofu box instead — a
 *    missing glyph should look wrong, not look like nothing.
 *
 * A v2 cache keeps every record 4-byte aligned (the offsets are in 4-byte units).
 * The cache is lease-scoped: the firmware allocates and zeroes it on the first write
 * and frees it when the framebuffer lease ends (or on mode 11), so a session uploads
 * its atlas once after acquiring the lease.
 */
object TextureCache {
    /** Offsets [0,GUARD) stay zero so an unwritten table entry is always rejected. */
    const val GUARD = 2
    /** A v2 cache's guard: one aligned unit. */
    const val GUARD2 = 4

    const val FIRST_CHAR = 32
    /** The last code of a v1 table (96 entries). */
    const val LAST_CHAR = 127
    /** The last code of a v2 table (224 entries, Latin-1). */
    const val LAST_CHAR2 = 255

    /** One 4bpp image destined for the cache as a v1 record: [levels] is row-major, one 0..15 per pixel. */
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

    /** A v2 image record (`FIRMWARE.md` §4): u16 dimensions, drawn by mode 17. */
    class Image2(val w: Int, val h: Int, val levels: ByteArray) {
        init {
            if (w !in 1..CfwModes.MAX_IMAGE2_W || h !in 1..CfwModes.MAX_IMAGE2_H)
                throw LintError("v2 image ${w}x$h is outside 1..${CfwModes.MAX_IMAGE2_W} x 1..${CfwModes.MAX_IMAGE2_H}")
            if (levels.size != w * h)
                throw LintError("v2 image ${w}x$h given ${levels.size} levels, expected ${w * h}")
        }

        /** [w u16][h u16][RLE]. */
        fun encode(): ByteArray = CfwModes.image2Bytes(w, h, Rle.encodeLevels(levels))
    }

    /**
     * An offset table placed in the cache, addressed by mode 14 (96 entries, byte offsets)
     * or, when [v2], by mode 18 (224 entries, offsets in 4-byte units).
     *
     * [glyphWidths] is the authority on advance: the draw moves the pen by each glyph's
     * cached image WIDTH (plus the kerning adjusts the phone emits) and nothing else, so the
     * widths recorded here when the atlas was packed are exactly what the firmware will do.
     * Nothing should ever supply a width from outside the atlas — a metric advance that
     * disagrees with the image would misplace every glyph after it, silently.
     */
    class Font(val tableOffset: Int, val glyphOffsets: IntArray, val glyphWidths: IntArray, val v2: Boolean = false) {
        /** The last code the table covers: 127 (v1) or 255 (v2). */
        val lastChar: Int get() = FIRST_CHAR + glyphOffsets.size - 1

        /** The table field a draw carries: bytes for mode 14, 4-byte units for mode 18. */
        val tableField: Int get() = if (v2) tableOffset / 4 else tableOffset

        /** True when [ch] has an entry (a glyph or the tofu) in this table. */
        fun covers(ch: Char): Boolean = ch.code in FIRST_CHAR..lastChar

        /** The pen advance for [ch], in pixels. */
        fun width(ch: Char): Int {
            val i = ch.code - FIRST_CHAR
            if (i !in glyphOffsets.indices)
                throw LintError("U+%04X is outside a cached font's %d..%d range"
                    .format(ch.code, FIRST_CHAR, lastChar))
            return glyphWidths[i]
        }

        /** The width of [text] as the draw will actually lay it out, kerning included. */
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
     * Packs images and font tables into one cache image, then emits the messages that
     * write it. Deduplicates identical glyphs by their encoded bytes, which is most of
     * why an atlas fits: a face's blank-ish glyphs collapse. A [v2] builder aligns every
     * record to 4 bytes over a cache of [capacity] bytes (op 5's size) and writes with
     * mode 19; a v1 builder packs the 64 KiB and writes with mode 12.
     */
    class Builder(val v2: Boolean = false, val capacity: Int = CfwModes.TEXTURE_CACHE_SIZE) {
        init {
            if (v2 && capacity !in CfwModes.TEXTURE_CACHE_SIZE..CfwModes.CACHE2_MAX)
                throw LintError("a v2 cache of $capacity B is not 64..160 KiB")
            if (!v2 && capacity != CfwModes.TEXTURE_CACHE_SIZE)
                throw LintError("a v1 cache is ${CfwModes.TEXTURE_CACHE_SIZE} B, not $capacity")
        }

        /** The guard's size: the first record starts here. */
        val guard: Int get() = if (v2) GUARD2 else GUARD
        /** The packed image, [size] bytes of it in use (the guard's zeros first). An array rather
         *  than a stream since `HANDOFF.md` §73: a table entry is rewritten in place when a face
         *  gains a glyph ([extendFont]). */
        private var buf = ByteArray(4096)
        private var size = if (v2) GUARD2 else GUARD
        /** Record bytes -> offset, by CONTENT (`ByteBuffer`'s equals and hash walk the bytes).
         *  Until §73 the key was every byte formatted through `String.format`, twice per glyph —
         *  a repack of 64–87 KB held the phone's loop 505 and 762 ms (journal, 2026-11-09).
         *  A key wraps a private copy: nothing the map holds can change under it. */
        private val seen = HashMap<java.nio.ByteBuffer, Int>()

        val used: Int get() = size
        val free: Int get() = capacity - size

        private fun write(b: ByteArray) {
            if (size + b.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + b.size))
            b.copyInto(buf, size)
            size += b.size
        }

        /** Add a v1 record (a glyph, a v1 icon), returning its cache offset in BYTES.
         *  Identical bytes reuse an offset. */
        fun add(image: Image): Int = addEncoded(image.encode())

        /** Add a v2 record, returning its cache offset in bytes (a multiple of 4). */
        fun add2(image: Image2): Int {
            if (!v2) throw LintError("a v2 image record needs a v2 cache")
            return addEncoded(image.encode())
        }

        /** Add already-encoded record bytes (a caller that encoded once for both the blit
         *  and the cache), returning the offset in bytes. On a v2 cache the record is padded
         *  on both sides, so the packed content always ends on a 4-byte boundary: an upload
         *  resumes from that end, and a mode-19 offset in 4-byte units cannot name an odd one
         *  (2026-09-15 review: an icon left the end at 1–3 mod 4 and the next upload landed early). */
        fun addEncoded(enc: ByteArray): Int {
            seen[java.nio.ByteBuffer.wrap(enc)]?.let { return it }
            // sized BEFORE the alignment pad is written: a record that does not fit leaves nothing behind
            val off = if (v2) (size + 3) / 4 * 4 else size
            if (off + enc.size > capacity)
                throw LintError("texture atlas overflows the $capacity B " +
                    "cache: $size B used, ${enc.size} B more needed")
            if (v2) pad()
            write(enc)
            if (v2) pad()
            seen[java.nio.ByteBuffer.wrap(enc.copyOf())] = off
            return off
        }

        private fun pad() {
            val n = (4 - size % 4) % 4
            if (n > 0) write(ByteArray(n))
        }

        /** The bytes [addFont] would write for this font as the cache stands:
         *  the table plus every glyph (and the tofu) whose bytes are not in
         *  the cache yet, identical glyphs counted once (a v2 record's alignment
         *  pad counted at its worst). */
        fun priceFont(glyphs: Map<Char, Image>, tofu: Image): Int = priceEncoded(glyphs, tofu).first

        /** The bytes these records would take as the cache stands, with no table: what a face
         *  that is already resident needs to gain them (`HANDOFF.md` §73) — records already in
         *  the cache cost nothing, identical ones count once, a v2 record's pad at its worst. */
        fun priceRecords(records: Collection<Image>): Int {
            val fresh = HashSet<java.nio.ByteBuffer>()
            var need = 0
            for (g in records) {
                val k = java.nio.ByteBuffer.wrap(g.encode())
                if (k !in seen && fresh.add(k)) need += k.remaining() + (if (v2) 3 else 0)
            }
            return need
        }

        private val tableChars: Int get() = if (v2) CfwModes.FONT2_TABLE_CHARS else CfwModes.FONT_TABLE_CHARS
        private val tableBytes: Int get() = tableChars * 2

        private fun priceEncoded(glyphs: Map<Char, Image>, tofu: Image): Pair<Int, Map<Char, ByteArray>> {
            val tofuEnc = tofu.encode()
            val encs = HashMap<Char, ByteArray>(glyphs.size)
            val fresh = HashSet<java.nio.ByteBuffer>()
            var need = tableBytes + (if (v2) 3 else 0)
            fun price(enc: ByteArray) { val k = java.nio.ByteBuffer.wrap(enc); if (k !in seen && fresh.add(k)) need += enc.size + (if (v2) 3 else 0) }
            price(tofuEnc)
            // only what the table can hold is priced: a set learned on a v2 atlas may name codes a
            // v1 table has no entry for, and pricing them refused a face with room to spare
            for ((c, g) in glyphs) {
                if (c.code !in FIRST_CHAR until FIRST_CHAR + tableChars) continue
                val e = g.encode(); encs[c] = e; price(e)
            }
            return need to encs
        }

        /**
         * Reserve and fill a font table for [glyphs] (character -> image). Every
         * character of the table's range gets an entry; anything absent from [glyphs]
         * points at [tofu], which is added to the cache if it is not already there.
         */
        fun addFont(glyphs: Map<Char, Image>, tofu: Image): Font {
            // Size the WHOLE font before a byte is written (`HANDOFF.md` §41):
            // a font that did not fit used to leave the glyphs that did behind
            // it — 7 KB of orphan images went up the link on glass (2026-09-06,
            // 18:05:46) and the cache was full for good. Dedup counts as the
            // writes below will: identical bytes cost once.
            val tofuEnc = tofu.encode()
            val (need, encs) = priceEncoded(glyphs, tofu)
            if (size + need > capacity)
                throw LintError("no room for a $need B font: $size B of " +
                    "$capacity used — nothing written")
            val n = tableChars
            val offsets = IntArray(n)
            val widths = IntArray(n)
            val tofuOffset = addEncoded(tofuEnc)
            for (i in 0 until n) {
                val ch = (FIRST_CHAR + i).toChar()
                val g = glyphs[ch]
                offsets[i] = if (g != null) addEncoded(encs.getValue(ch)) else tofuOffset
                widths[i] = (g ?: tofu).w
            }
            val table = ByteArray(tableBytes)
            for (i in offsets.indices) putEntry(table, i * 2, offsets[i])
            if (v2) pad()
            val off = size
            check(off + table.size <= capacity) { "a font sized to fit then did not: $off + ${table.size}" }
            write(table)
            return Font(off, offsets, widths, v2)
        }

        /** One table entry: the record's offset in the table's own units (bytes on a v1 cache,
         *  4-byte units on a v2 one), little-endian. */
        private fun putEntry(dst: ByteArray, at: Int, recordOffset: Int) {
            val field = if (v2) recordOffset / 4 else recordOffset
            dst[at] = (field and 0xFF).toByte()
            dst[at + 1] = ((field shr 8) and 0xFF).toByte()
        }

        /**
         * `HANDOFF.md` §73: give [font]'s table real entries for [glyphs] — a face that drew a
         * character it was not packed with. The new records go at the END of the packed bytes
         * (identical bytes reuse an offset, as ever) and the table's entries for those codes are
         * rewritten IN PLACE; nothing else moves, so every offset a live draw can name stays what
         * it was. A cache write is a plain copy at an offset on both contracts (`FIRMWARE.md` §4:
         * mode 19; mode 12 in `texture_cache.c`), and a draw reads only the entries of its own
         * string's codes, so a table whose other entries change under it draws the same.
         *
         * Priced first, like [addFont]: an extension that does not fit writes nothing. Returns
         * the font as it now stands and the byte spans of the table that changed — each a whole
         * 4-byte unit on a v2 cache (a mode-19 offset is in 4-byte units and an entry is two
         * bytes, so the unit's other entry rides along with the value this image holds for it),
         * a single 2-byte entry on a v1 one — for the atlas's upload to send ([message]). A code whose record is
         * already what its entry names (a glyph byte-identical to the tofu) changes no span.
         */
        fun extendFont(font: Font, glyphs: Map<Char, Image>): Pair<Font, List<IntRange>> {
            if (font.v2 != v2) throw LintError("a ${if (font.v2) "v2" else "v1"} font cannot be extended in a ${if (v2) "v2" else "v1"} cache")
            if (font.tableOffset < guard || font.tableOffset + tableBytes > size)
                throw LintError("font table at ${font.tableOffset} is not in this cache ($size B used)")
            val inRange = glyphs.filterKeys { it.code in FIRST_CHAR until FIRST_CHAR + tableChars }
            val need = priceRecords(inRange.values)
            if (size + need > capacity)
                throw LintError("no room for $need B of glyphs: $size B of $capacity used — nothing written")
            val offsets = font.glyphOffsets.copyOf()
            val widths = font.glyphWidths.copyOf()
            val changed = java.util.TreeSet<Int>()          // table-relative byte offsets of changed spans
            for ((ch, g) in inRange.toSortedMap()) {
                val i = ch.code - FIRST_CHAR
                val off = addEncoded(g.encode())
                widths[i] = g.w
                if (off == offsets[i]) continue
                offsets[i] = off
                putEntry(buf, font.tableOffset + i * 2, off)
                changed += if (v2) (i * 2) / 4 * 4 else i * 2
            }
            val unit = if (v2) 4 else 2
            // adjacent units ride one span; a span never leaves the table (448 and 192 are multiples of the unit)
            val spans = ArrayList<IntRange>()
            for (rel in changed) {
                val abs = font.tableOffset + rel
                val last = spans.lastOrNull()
                if (last != null && last.last + 1 == abs) spans[spans.size - 1] = last.first..(abs + unit - 1)
                else spans += abs..(abs + unit - 1)
            }
            return Font(font.tableOffset, offsets, widths, v2) to spans
        }

        /** The packed cache contents, guard included. */
        fun content(): ByteArray = buf.copyOf(size)

        /** The packed bytes of [span] as the image holds them now. */
        fun read(span: IntRange): ByteArray {
            if (span.first < 0 || span.last >= size) throw LintError("span $span is outside the $size B packed")
            return buf.copyOfRange(span.first, span.last + 1)
        }

        /** One cache-write message carrying [writes] (offset in bytes -> data): mode 19 on a v2
         *  cache (every offset a multiple of 4), mode 12 on a v1 one. Never empty — the encoders
         *  refuse an empty list, and the firmware bumps no generation for one. */
        fun message(writes: List<Pair<Int, ByteArray>>): ByteArray =
            if (v2) CfwModes.cacheUpdate2(writes.map { (off, data) ->
                if (off % 4 != 0) throw LintError("a mode-19 write cannot start at byte $off: its offset is in 4-byte units")
                CfwModes.CacheWrite2(off / 4, data)
            }, capacity)
            else CfwModes.cacheUpdate(writes.map { (off, data) -> CfwModes.CacheWrite(off, data) })

        /** A cache-write message's own bytes around its data: the mode byte, and per write the
         *  offset and length fields (u16 each on both contracts). */
        val messageOverhead: Int get() = 1
        val writeOverhead: Int get() = 4

        /**
         * The messages that write everything added so far, each no larger than
         * [maxMessage] bytes — mode 12 for a v1 cache, mode 19 for a v2 one. Split
         * rather than sent as one message so a slow link makes visible progress and
         * a rejection names a smaller range.
         */
        fun messages(maxMessage: Int = 3072): List<ByteArray> {
            if (maxMessage <= 9) throw LintError("cache chunk size $maxMessage leaves no room for data")
            val all = content()
            // The guard is already zero in a freshly allocated cache; never write it.
            if (all.size <= guard) throw LintError("texture atlas is empty")
            val out = ArrayList<ByteArray>()
            var pos = guard
            while (pos < all.size) {
                out += chunk(all, pos, maxMessage).also { pos += chunkLen(all.size, pos, maxMessage) }
            }
            return out
        }

        /** How many bytes a chunk starting at [pos] carries: a multiple of 4 on a v2 cache
         *  unless it is the last one (the next chunk's offset must stay in 4-byte units). */
        fun chunkLen(total: Int, pos: Int, maxMessage: Int): Int {
            val room = maxMessage - 5          // [mode] + [off16][len16]
            var n = minOf(room, total - pos)
            if (v2 && pos + n < total) n -= n % 4
            // a caller that walked the upload with this would never reach the end: say so here
            // rather than spin (2026-09-15, second review)
            if (n <= 0) throw LintError("a $maxMessage B message carries no cache bytes${if (v2) " on a v2 cache (4-byte units)" else ""}")
            return n
        }

        /** One write message for [all]'s bytes from [pos]. A v2 chunk must start on a 4-byte
         *  boundary: its offset field is in 4-byte units and would round an odd start down. */
        fun chunk(all: ByteArray, pos: Int, maxMessage: Int): ByteArray {
            if (v2 && pos % 4 != 0)
                throw LintError("a mode-19 chunk cannot start at byte $pos: its offset is in 4-byte units")
            val n = chunkLen(all.size, pos, maxMessage)
            val data = all.copyOfRange(pos, pos + n)
            return if (v2) CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(pos / 4, data)), capacity)
            else CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(pos, data)))
        }
    }

    /**
     * Encode [text] as the string bytes of a mode-14 (v1) or mode-18 (v2) draw for [font].
     *
     * **Glyph images are advance-width boxes.** The draw moves the pen by the cached
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
            if (c < FIRST_CHAR || c > font.lastChar)
                throw LintError("a cached draw cannot draw U+%04X ('%s'): this font covers only %d..%d"
                    .format(c, ch, FIRST_CHAR, font.lastChar))
            prev?.let { p ->
                val dx = kern(p, ch)
                if (dx != 0) out.write(CfwModes.xAdjust(dx).toInt())
            }
            out.write(c)
            prev = ch
        }
        val bytes = out.toByteArray()
        if (bytes.size > 0xFF)
            throw LintError("a cached draw's string for \"$text\" needs ${bytes.size} B with its " +
                "kerning, past the u8 length — split the line")
        return bytes
    }
}
