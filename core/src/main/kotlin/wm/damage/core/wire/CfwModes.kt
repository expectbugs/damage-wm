package wm.damage.core.wire

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect

/**
 * CFW display-mode payload builders — g2flash/patches/zlib_glue.c is the
 * authoritative contract (overview.md §4). All multi-byte integers little-endian
 * (zlib_glue.c rd16: p[0] | p[1]<<8). These bytes are the "image" the EvenHub
 * ImgRawMsg carries; the firmware dispatches on the first byte after reassembly.
 *
 *   mode 6:  [6][zlib(rle(packed 640x480))]                      keyframe, seeds the shadow
 *   mode 3:  [3][l/4][t/2][w/4][h/2][fidLo][fidHi][zlib(rle)]    delta onto the shadow
 *   stereo:  [3|80][Lbox 4][Rbox 4][fidLo][fidHi][zlib(rle)]     one payload, two boxes
 *   mode 9:  [9][src 8B][dst 8B]                                 rect-copy inside the shadow
 *   stereo:  [9|80][Lsrc][Ldst][Rsrc][Rdst]                      two rect-sets
 *   mode 8:  [8][count][len16][submsg]...                        atomic multi-op (3/6/9/13/14/15)
 *   mode 7:  [7][sub]                                            0 clear flags · 1 hide · 2 show
 *   mode 11: [11]                                                session cleanup before disconnect
 *   mode 12: [12]([off16][len16][data])...                       write the 64 KiB texture cache
 *   mode 13: [13][off16][x16][y16][opt8]                         draw a cached image
 *   mode 14: [14][font16][x16][y16][opt8][len8][bytes]           draw cached glyphs
 *
 * The HIGH BIT of the mode byte is the "lenses differ" flag. Mode-3 boxes are
 * QUANTIZED (left/width x4, top/height x2, one byte each); mode-9 rects are full
 * uint16 and may overlap. A malformed payload is rejected in SILENCE and the
 * previous frame stays up — which is why every builder here lint-checks first.
 *
 * Modes 11–15 arrived with g2flash a5d1c31 (2026-08-30). **Damage does not emit
 * mode 15** (draw with the firmware's own 20 px font): its pixels come from an
 * LVGL font chain that lives inside the firmware, so no offline model can predict
 * them, and the compositor's per-lens belief — the thing `LensOracleTest` pins —
 * would stop being exact. Modes 13/14 draw from a cache WE wrote, so the model
 * reproduces them bit for bit and the oracle survives. See overview.md §4.6.
 */
object CfwModes {
    const val STEREO_BIT = 0x80

    /** texture_cache.h CFW_TEXTURE_CACHE_SIZE — lease-scoped, zeroed on first write. */
    const val TEXTURE_CACHE_SIZE = 65536

    /** A mode-14 font is 96 little-endian uint16 image offsets for chars 32..127. */
    const val FONT_TABLE_CHARS = 96
    const val FONT_TABLE_BYTES = FONT_TABLE_CHARS * 2

    /** Cached images carry width/height as u8 each (texture_cache.c). */
    const val MAX_TEXTURE_DIM = 255

    /** Sub-modes a mode-8 batch accepts (zlib_glue.c mode 8). Mode 15 is legal on
     *  the wire and deliberately never built here — see the note above. */
    val BATCH_SUBMODES = setOf(3, 6, 9, 13, 14, 15)

    /** Options byte for modes 13/14: low nibble = top output colour, bit 4 makes
     *  source colour 0 transparent, bit 5 reverses the ramp
     *  (texture_cache.c cfw_texture_make_lut: lut[i] = (src * top) / 15). */
    const val OPT_TRANSPARENT = 0x10
    const val OPT_INVERSE = 0x20

    fun options(top: Int = 15, transparent: Boolean = false, inverse: Boolean = false): Int {
        if (top !in 0..15) throw LintError("texture options: top colour $top is not 0..15")
        return top or (if (transparent) OPT_TRANSPARENT else 0) or (if (inverse) OPT_INVERSE else 0)
    }

    /** A mode-3 box must be x4/x2 aligned and in bounds; w is x4 so each packed
     *  row is exactly w/2 bytes — no pad nibble inside a delta payload. */
    fun delta(box: Rect, zlibRle: ByteArray, fid: Int): ByteArray {
        failIf(Geometry.checkRect(box, "mode-3 box"))
        failIfFid(fid)
        val out = ByteArray(7 + zlibRle.size)
        out[0] = 3
        out[1] = (box.x / 4).toByte()
        out[2] = (box.y / 2).toByte()
        out[3] = (box.w / 4).toByte()
        out[4] = (box.h / 2).toByte()
        out[5] = (fid and 0xFF).toByte()
        out[6] = ((fid shr 8) and 0xFF).toByte()
        zlibRle.copyInto(out, 7)
        return out
    }

    /** Stereo delta: left box then right box (same size), one shared payload.
     *  Each lens draws at its own box (box_off = FW_SIDE()==2 ? 1 : 5). */
    fun deltaStereo(left: Rect, right: Rect, zlibRle: ByteArray, fid: Int): ByteArray {
        failIf(Geometry.checkStereoPair(left, right))
        failIfFid(fid)
        val out = ByteArray(11 + zlibRle.size)
        out[0] = (3 or STEREO_BIT).toByte()
        writeBox(out, 1, left)
        writeBox(out, 5, right)
        out[9] = (fid and 0xFF).toByte()
        out[10] = ((fid shr 8) and 0xFF).toByte()
        zlibRle.copyInto(out, 11)
        return out
    }

    fun keyframe(zlibRle: ByteArray): ByteArray {
        val out = ByteArray(1 + zlibRle.size)
        out[0] = 6
        zlibRle.copyInto(out, 1)
        return out
    }

    /** Rect-copy inside the shadow; src/dst same size, full uint16 coords,
     *  overlap allowed (the firmware's copy handles direction). */
    fun copy(src: Rect, dst: Rect): ByteArray {
        checkCopy(src, dst)
        val out = ByteArray(17)
        out[0] = 9
        writeRect16(out, 1, src)
        writeRect16(out, 9, dst)
        return out
    }

    /** Stereo rect-copy: left pair then right pair; each lens uses its own. */
    fun copyStereo(srcL: Rect, dstL: Rect, srcR: Rect, dstR: Rect): ByteArray {
        checkCopy(srcL, dstL)
        checkCopy(srcR, dstR)
        if (srcL.w != srcR.w || srcL.h != srcR.h)
            throw LintError("stereo copy: lens rect-sets differ in size $srcL vs $srcR")
        val out = ByteArray(33)
        out[0] = (9 or STEREO_BIT).toByte()
        writeRect16(out, 1, srcL)
        writeRect16(out, 9, dstL)
        writeRect16(out, 17, srcR)
        writeRect16(out, 25, dstR)
        return out
    }

    /** Atomic multi-op batch. Only shadow ops are legal inside; the firmware checks
     *  `submode & 0x7f` so stereo sub-ops ride fine. Sub-messages apply to the
     *  shadow IN ORDER, then one present (zlib_glue.c mode 8). The accepted set
     *  grew to 3/6/9/13/14/15 with g2flash a5d1c31 — so a cached icon or a line of
     *  cached glyphs rides in the SAME single flush as the pixel deltas, which is
     *  the whole point of batching. Of those, only mode 3 burns a fid. */
    fun batch(subs: List<ByteArray>): ByteArray {
        if (subs.isEmpty()) throw LintError("empty mode-8 batch")
        if (subs.size > 0xFF) throw LintError("mode-8 count ${subs.size} exceeds the u8 field")
        for ((i, s) in subs.withIndex()) {
            if (s.isEmpty()) throw LintError("mode-8 sub $i empty")
            val m = s[0].toInt() and 0x7F
            if (m !in BATCH_SUBMODES)
                throw LintError("mode-8 sub $i is mode $m; the firmware accepts only " +
                    "shadow ops ${BATCH_SUBMODES.joinToString("/")}")
            if (s.size > 0xFFFF) throw LintError("mode-8 sub $i length ${s.size} exceeds len16")
        }
        val total = 2 + subs.sumOf { 2 + it.size }
        failIf(Geometry.checkBatch(emptyList(), payload = total))
        val out = ByteArray(total)
        out[0] = 8
        out[1] = subs.size.toByte()
        var o = 2
        for (s in subs) {
            out[o] = (s.size and 0xFF).toByte()
            out[o + 1] = ((s.size shr 8) and 0xFF).toByte()
            o += 2
            s.copyInto(out, o)
            o += s.size
        }
        return out
    }

    /** Diagnostic overlay: 0 clear sticky flags (and the fid ring), 1 hide, 2 show. */
    fun diag(sub: Int): ByteArray {
        require(sub in 0..2) { "mode-7 sub $sub" }
        return byteArrayOf(7, sub.toByte())
    }

    /** Session cleanup before disconnect: releases the lease and direct-framebuffer
     *  ownership, stops CFW timers, buzzer and compass, and drops the texture cache.
     *  The singleton context survives. Extra bytes are reserved and ignored. */
    fun cleanup(): ByteArray = byteArrayOf(11)

    /** One run of bytes to write into the texture cache at [offset]. */
    data class CacheWrite(val offset: Int, val data: ByteArray) {
        override fun equals(other: Any?) = other is CacheWrite &&
            offset == other.offset && data.contentEquals(other.data)
        override fun hashCode() = offset * 31 + data.contentHashCode()
    }

    /** Mode 12. The firmware validates the WHOLE entry list before writing a byte,
     *  so a rejected update leaves the cache untouched — but it is rejected in
     *  silence, hence the checks here. An empty update is a firmware no-op; we
     *  refuse it rather than emit a message that means nothing. */
    fun cacheUpdate(writes: List<CacheWrite>): ByteArray {
        if (writes.isEmpty()) throw LintError("empty mode-12 cache update")
        var total = 1
        for (w in writes) {
            if (w.data.isEmpty()) throw LintError("mode-12 write at ${w.offset} is empty")
            if (w.offset < 0 || w.offset + w.data.size > TEXTURE_CACHE_SIZE)
                throw LintError("mode-12 write [${w.offset}, ${w.offset + w.data.size}) " +
                    "leaves the $TEXTURE_CACHE_SIZE B texture cache")
            if (w.data.size > 0xFFFF)
                throw LintError("mode-12 write of ${w.data.size} B exceeds the u16 length field")
            total += 4 + w.data.size
        }
        val out = ByteArray(total)
        out[0] = 12
        var o = 1
        for (w in writes) {
            put16(out, o, w.offset)
            put16(out, o + 2, w.data.size)
            o += 4
            w.data.copyInto(out, o)
            o += w.data.size
        }
        return out
    }

    /** Mode 13: draw the cached image at [cacheOffset]. The firmware requires the
     *  payload after the mode byte to be EXACTLY 7 bytes. x/y are unsigned. */
    fun drawImage(cacheOffset: Int, x: Int, y: Int, options: Int): ByteArray {
        checkCacheOffset(cacheOffset, "mode-13 image")
        checkDrawXY(x, y, "mode-13")
        checkOptions(options, "mode-13")
        val out = ByteArray(8)
        out[0] = 13
        put16(out, 1, cacheOffset)
        put16(out, 3, x)
        put16(out, 5, y)
        out[7] = options.toByte()
        return out
    }

    /** Mode 14: draw [text] with the cached font whose 96-entry offset table starts
     *  at [fontOffset]. Byte values 1..31 are inline x adjustments of (b - 11), i.e.
     *  -10..+20 px; 32..127 select a glyph and advance x by its cached image WIDTH
     *  (there is no kerning and no side bearing — bake those into the glyph or use
     *  an adjust byte). Byte 0 and bytes above 127 make the firmware reject the
     *  whole message, so they are refused here. */
    fun drawCachedText(fontOffset: Int, x: Int, y: Int, options: Int, text: ByteArray): ByteArray {
        if (fontOffset < 0 || fontOffset > TEXTURE_CACHE_SIZE - FONT_TABLE_BYTES)
            throw LintError("mode-14 font table at $fontOffset does not fit its " +
                "$FONT_TABLE_BYTES B in the $TEXTURE_CACHE_SIZE B cache")
        checkDrawXY(x, y, "mode-14")
        checkOptions(options, "mode-14")
        if (text.isEmpty()) throw LintError("empty mode-14 string")
        if (text.size > 0xFF) throw LintError("mode-14 string of ${text.size} B exceeds the u8 length")
        for ((i, b) in text.withIndex()) {
            val v = b.toInt() and 0xFF
            if (v == 0 || v > 127)
                throw LintError("mode-14 string byte $i is $v; the firmware accepts only " +
                    "1..31 (x adjust) and 32..127 (glyph) and rejects the whole message")
        }
        val out = ByteArray(9 + text.size)
        out[0] = 14
        put16(out, 1, fontOffset)
        put16(out, 3, x)
        put16(out, 5, y)
        out[7] = options.toByte()
        out[8] = text.size.toByte()
        text.copyInto(out, 9)
        return out
    }

    /** The inline x-adjust byte for a shift of [dx] px, which must be -10..+20
     *  (texture_cache.c: bytes 1..31 adjust x by ch - 11). This is the only
     *  kerning channel mode 14 has. */
    fun xAdjust(dx: Int): Byte {
        if (dx < -10 || dx > 20) throw LintError("mode-14 x adjust $dx is outside -10..+20")
        return (dx + 11).toByte()
    }

    private fun checkCacheOffset(off: Int, what: String) {
        // An image needs at least its [w][h] header plus one token.
        if (off < 0 || off > TEXTURE_CACHE_SIZE - 3)
            throw LintError("$what offset $off cannot hold an image in the " +
                "$TEXTURE_CACHE_SIZE B texture cache")
    }

    private fun checkDrawXY(x: Int, y: Int, what: String) {
        if (x !in 0..0xFFFF || y !in 0..0xFFFF)
            throw LintError("$what draw at ($x,$y) is outside the u16 coordinate fields")
    }

    private fun checkOptions(o: Int, what: String) {
        if (o !in 0..0xFF) throw LintError("$what options byte $o is not 0..255")
    }

    private fun put16(out: ByteArray, off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeBox(out: ByteArray, off: Int, r: Rect) {
        out[off] = (r.x / 4).toByte()
        out[off + 1] = (r.y / 2).toByte()
        out[off + 2] = (r.w / 4).toByte()
        out[off + 3] = (r.h / 2).toByte()
    }

    private fun writeRect16(out: ByteArray, off: Int, r: Rect) {
        var o = off
        for (v in intArrayOf(r.x, r.y, r.w, r.h)) {
            out[o] = (v and 0xFF).toByte()
            out[o + 1] = ((v shr 8) and 0xFF).toByte()
            o += 2
        }
    }

    private fun checkCopy(src: Rect, dst: Rect) {
        if (src.w != dst.w || src.h != dst.h)
            throw LintError("mode-9 copy: src $src and dst $dst differ in size")
        if (src.w <= 0 || src.h <= 0) throw LintError("mode-9 copy: degenerate rect $src")
        for (r in listOf(src, dst))
            if (r.x < 0 || r.y < 0 || r.right > Geometry.PANEL_W || r.bottom > Geometry.PANEL_H)
                throw LintError("mode-9 rect $r out of ${Geometry.PANEL_W}x${Geometry.PANEL_H} bounds")
    }

    private fun failIf(errs: List<String>) {
        if (errs.isNotEmpty()) throw LintError(errs.joinToString("; "))
    }

    private fun failIfFid(fid: Int) {
        if (fid < Geometry.FID_MIN || fid > Geometry.FID_MAX)
            throw LintError("FID003 fid $fid outside [${Geometry.FID_MIN}, ${Geometry.FID_MAX}]")
    }
}
