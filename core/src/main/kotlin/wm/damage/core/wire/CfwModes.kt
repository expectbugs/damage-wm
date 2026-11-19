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
 * Contract 2 (`FIRMWARE.md` §4, the Damage build's modes 17–24, behind flag bit 2 DRAW2;
 * a rect is [l u16][t u16][w u16][h u16] unquantized, a cache offset is u16 in 4-byte
 * units, x and y are s16, the high bit of the mode byte is the per-lens form: the left
 * lens takes the first rect or x, the right the second):
 *   mode 17: [17][off4][x s16][y s16][opt8]                       draw a v2 image record (u16 dims)
 *   mode 18: [18][font4][x s16][y s16][opt8][len8][bytes]         draw glyphs through a 224-entry table
 *   mode 19: [19]([off4][len16][data])...                         write the cache at 4-byte-unit offsets
 *   mode 20: [20][rect]                                           clip the v2 ops after it in the batch
 *   mode 21: [21][rect][level8]                                   fill, no fid, no zlib
 *   mode 22: [22][rect][lut 8 B]                                  every pixel through a 16-entry LUT
 *   mode 23: [23][0][slot][rect] · [23][1][slot] · [23][2][slot]  save-under: capture, restore, free
 *   mode 24: [24][y0 u16][y1 u16]                                 the batch's present sends rows y0..y1
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

    /** Sub-modes a mode-8 batch accepts (zlib_glue.c mode 8; the Damage build adds
     *  17/18/20/21/22/23/24 — every v2 op but the cache write). Mode 15 is legal on
     *  the wire and deliberately never built here — see the note above. */
    val BATCH_SUBMODES = setOf(3, 6, 9, 13, 14, 15, 17, 18, 20, 21, 22, 23, 24)

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
    /** [engine]: the batch may carry mode 26 too — only where ENGINE is in force (`FIRMWARE.md` §5.5: "allowed alone
     *  or as a mode-8 sub-message"); a build without the engine has no handler for it. [bind] (build C2, §6.3, §6.6): it
     *  may carry modes 27 and 28 too — only where BIND is in force on a bit-10 build. */
    fun batch(subs: List<ByteArray>, engine: Boolean = false, bind: Boolean = false): ByteArray {
        if (subs.isEmpty()) throw LintError("empty mode-8 batch")
        if (subs.size > 0xFF) throw LintError("mode-8 count ${subs.size} exceeds the u8 field")
        for ((i, s) in subs.withIndex()) {
            if (s.isEmpty()) throw LintError("mode-8 sub $i empty")
            val m = s[0].toInt() and 0x7F
            if (m !in BATCH_SUBMODES && !(engine && m == MOTION_MODE) && !(bind && (m == BIND_MODE || m == VSCALE_MODE)))
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

    /** The Damage build's self-test (`FIRMWARE.md` §3, mode 16): [16][0] allocates a scratch
     *  shadow (the lease must be held), [16][1][message] runs one drawing message against it
     *  with nothing presented, [16][2] frees it. A step's message may be anything a shadow
     *  op is (3/6/8/9/13/14/15); a cache write or a non-drawing mode is refused. Only the RIGHT
     *  lens reports the scratch CRC (telemetry fields 20–22). Not a batch sub-mode. */
    const val SELF_TEST_MODE = 16
    fun selfTestBegin(): ByteArray = byteArrayOf(SELF_TEST_MODE.toByte(), 0)
    fun selfTestEnd(): ByteArray = byteArrayOf(SELF_TEST_MODE.toByte(), 2)
    fun selfTestStep(message: ByteArray): ByteArray {
        if (message.isEmpty()) throw LintError("empty self-test step")
        val m = message[0].toInt() and 0x7F
        if (m !in SELF_TEST_MODES)
            throw LintError("self-test step is mode $m; the firmware runs only ${SELF_TEST_MODES.joinToString("/")} against the scratch")
        val out = ByteArray(2 + message.size)
        out[0] = SELF_TEST_MODE.toByte()
        out[1] = 1
        message.copyInto(out, 2)
        return out
    }
    /** What a self-test step may carry: the shadow messages (zlib_glue.c is_shadow_message, minus mode 11)
     *  and, on a contract-2 build, the v2 ops but the cache write. */
    val SELF_TEST_MODES = setOf(3, 6, 8, 9, 13, 14, 15, 17, 18, 20, 21, 22, 23, 24)

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
        // A reassembled image message larger than the firmware's snapshot capacity is
        // DROPPED with only a sticky f_snap_of flag to show for it (zlib_glue.c
        // cfw_snapshot). Every other path here is bounded; bound this one too.
        if (total > Geometry.MODE8_MAX)
            throw LintError("mode-12 message of $total B exceeds the ${Geometry.MODE8_MAX} B " +
                "reassembly ceiling — split it (TextureCache.Builder.messages() does)")
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

    // ---------------------------------------------------------------- contract 2 (`FIRMWARE.md` §4)
    /** A v2 image record is [w u16][h u16][RLE]: w 1..640, h 1..2048. */
    const val MAX_IMAGE2_W = 640
    const val MAX_IMAGE2_H = 2048
    /** A mode-18 font is 224 little-endian off4 entries for codes 32..255. */
    const val FONT2_TABLE_CHARS = 224
    const val FONT2_TABLE_BYTES = FONT2_TABLE_CHARS * 2
    /** The save-under pool (mode 23): four slots, 48 KiB between them. */
    const val SAVE_SLOTS = 4
    const val SAVE_BUDGET = 48 * 1024
    /** The largest cache a Phase 2 build allocates (op 5; budget A). */
    const val CACHE2_MAX = 160 * 1024
    /** The largest cache a build with the riders (DamageCaps bit 8, `FIRMWARE.md` §5.9) allocates: the encoders'
     *  bounds take it; what a session asks for is the ladder's (`CfwTransportBase`), never more than its build's. */
    const val CACHE2_MAX_RIDERS = 256 * 1024
    /** Page staging (`HANDOFF.md` §66, Adam 2026-09-16): the top of a contract-2 cache is a reserve for the
     *  focused document's staged strips (v2 image records written by mode 19, drawn by mode 17); the atlas
     *  lays out over the rest (`LinkState.atlasCapacity`). 64 KiB since the first read on glass (§66.1): a
     *  five-line strip of Adam's book measured 12–17 KB (an image strip 34 KB), two to four times the 1×
     *  renders' model, and 32 KiB held one. */
    const val STAGE_RESERVE = 64 * 1024
    /** Build C2 (`C2-decisions.md` B.4; AD.2; AU.1): on a 256 KiB session with BIND in force, Main's staged rows have a
     *  region of their own, the 128 KiB below a 32 KiB stage reserve — room for a second strip, so a row-form change is
     *  switched with no withdrawal (and for one strip when two do not fit) — and the atlas lays out over the 96 KiB under
     *  it, what it has at 160 KiB (its largest use seen in his journal: 78,136 B). 96 KiB held his rows at the first
     *  trial's start (55,488 B) and not after his app visits (103,088 B, 11-19 12:25). Everywhere else (160 KiB, or no
     *  BIND: ladder stages 0–3 keep today's layout byte for byte) Main stages into the shared reserve when it comes to the
     *  front. */
    const val MAIN_REGION = 128 * 1024
    /** AU.1: the stage reserve on a 256 KiB session with BIND in force (Page staging's strips; nothing in daily use draws
     *  on it at stage 4). */
    const val STAGE_RESERVE_BOUND = 32 * 1024

    private fun ownRegion(cacheSize: Int, bind: Boolean) = bind && cacheSize >= CACHE2_MAX_RIDERS

    /** The stage reserve at the top of a session's cache: [STAGE_RESERVE], or [STAGE_RESERVE_BOUND] when Main has its own
     *  region below it. */
    fun stageReserve(cacheSize: Int, bind: Boolean): Int = if (ownRegion(cacheSize, bind)) STAGE_RESERVE_BOUND else STAGE_RESERVE

    /** The bytes above the atlas's layout: the stage reserve, and Main's region when [mainRegion] gives it one. */
    fun reserveAbove(cacheSize: Int, bind: Boolean): Int = stageReserve(cacheSize, bind) + if (ownRegion(cacheSize, bind)) MAIN_REGION else 0

    /** Where Main's staged rows lie in a session of [cacheSize] bytes: its own region on a 256 KiB session with [bind],
     *  else the shared stage reserve (`[cacheSize − STAGE_RESERVE, cacheSize)`). */
    fun mainRegion(cacheSize: Int, bind: Boolean): IntRange =
        if (ownRegion(cacheSize, bind)) (cacheSize - STAGE_RESERVE_BOUND - MAIN_REGION) until (cacheSize - STAGE_RESERVE_BOUND)
        else (cacheSize - STAGE_RESERVE) until cacheSize
    /** The largest offset a u16 in 4-byte units reaches. */
    const val OFF4_REACH = 0xFFFF * 4

    /** Bytes of the v2 message that [image2] describes, for the encoder's own bound. */
    fun image2Bytes(w: Int, h: Int, rle: ByteArray): ByteArray {
        if (w !in 1..MAX_IMAGE2_W || h !in 1..MAX_IMAGE2_H)
            throw LintError("v2 image ${w}x$h is outside 1..$MAX_IMAGE2_W x 1..$MAX_IMAGE2_H")
        val out = ByteArray(4 + rle.size)
        put16(out, 0, w); put16(out, 2, h)
        rle.copyInto(out, 4)
        return out
    }

    /** Mode 17: draw the v2 record at [off4] (4-byte units) at a signed (x, y), clipped by the
     *  firmware to the panel and the batch's clip. [w]/[h] are the record's, for the lint that a
     *  draw wholly off the panel costs a message for nothing. */
    fun drawImage2(off4: Int, x: Int, y: Int, options: Int, w: Int, h: Int): ByteArray {
        checkOff4(off4, "mode-17 record")
        checkDraw2(x, y, w, h, "mode-17")
        checkOptions(options, "mode-17")
        val out = ByteArray(8)
        out[0] = 17
        put16(out, 1, off4); putS16(out, 3, x); putS16(out, 5, y)
        out[7] = options.toByte()
        return out
    }

    /** Mode 17's per-lens form: the left lens draws at [xL], the right at [xR], one y. */
    fun drawImage2Pair(off4: Int, xL: Int, xR: Int, y: Int, options: Int, w: Int, h: Int): ByteArray {
        checkOff4(off4, "mode-17 record")
        checkDraw2(xL, y, w, h, "mode-17 L"); checkDraw2(xR, y, w, h, "mode-17 R")
        checkOptions(options, "mode-17")
        val out = ByteArray(10)
        out[0] = (17 or STEREO_BIT).toByte()
        put16(out, 1, off4); putS16(out, 3, xL); putS16(out, 5, xR); putS16(out, 7, y)
        out[9] = options.toByte()
        return out
    }

    /** Mode 18: draw [text] (codes 32..255 and adjust bytes 1..31, never 0) through the
     *  224-entry table at [font4]. The firmware refuses a 0 byte and rejects the whole line. */
    fun drawText2(font4: Int, x: Int, y: Int, options: Int, text: ByteArray): ByteArray {
        checkFont4(font4)
        checkTextXY(x, y, "mode-18")
        checkOptions(options, "mode-18")
        checkText2(text, "mode-18")
        val out = ByteArray(9 + text.size)
        out[0] = 18
        put16(out, 1, font4); putS16(out, 3, x); putS16(out, 5, y)
        out[7] = options.toByte(); out[8] = text.size.toByte()
        text.copyInto(out, 9)
        return out
    }

    fun drawText2Pair(font4: Int, xL: Int, xR: Int, y: Int, options: Int, text: ByteArray): ByteArray {
        checkFont4(font4)
        checkTextXY(xL, y, "mode-18 L"); checkTextXY(xR, y, "mode-18 R")
        checkOptions(options, "mode-18")
        checkText2(text, "mode-18")
        val out = ByteArray(11 + text.size)
        out[0] = (18 or STEREO_BIT).toByte()
        put16(out, 1, font4); putS16(out, 3, xL); putS16(out, 5, xR); putS16(out, 7, y)
        out[9] = options.toByte(); out[10] = text.size.toByte()
        text.copyInto(out, 11)
        return out
    }

    /** One run of bytes to write at [off4] (4-byte units) — [data] need not be a multiple of 4. */
    data class CacheWrite2(val off4: Int, val data: ByteArray) {
        override fun equals(other: Any?) = other is CacheWrite2 && off4 == other.off4 && data.contentEquals(other.data)
        override fun hashCode() = off4 * 31 + data.contentHashCode()
    }

    /** Mode 19: the v2 cache write, offsets in 4-byte units over the session's cache size
     *  ([cacheSize] bytes, op 5's) — validated whole by the firmware, refused whole. */
    fun cacheUpdate2(writes: List<CacheWrite2>, cacheSize: Int): ByteArray {
        if (writes.isEmpty()) throw LintError("empty mode-19 cache update")
        if (cacheSize !in TEXTURE_CACHE_SIZE..CACHE2_MAX_RIDERS) throw LintError("mode-19 cache size $cacheSize is not 64..256 KiB")
        var total = 1
        for (w in writes) {
            if (w.data.isEmpty()) throw LintError("mode-19 write at ${w.off4} is empty")
            if (w.off4 !in 0..0xFFFF) throw LintError("mode-19 offset ${w.off4} is outside the u16 field")
            if (w.off4 * 4 + w.data.size > cacheSize)
                throw LintError("mode-19 write [${w.off4 * 4}, ${w.off4 * 4 + w.data.size}) leaves the $cacheSize B cache")
            if (w.data.size > 0xFFFF) throw LintError("mode-19 write of ${w.data.size} B exceeds the u16 length field")
            total += 4 + w.data.size
        }
        if (total > Geometry.MODE8_MAX)
            throw LintError("mode-19 message of $total B exceeds the ${Geometry.MODE8_MAX} B reassembly ceiling — split it")
        val out = ByteArray(total)
        out[0] = 19
        var o = 1
        for (w in writes) {
            put16(out, o, w.off4); put16(out, o + 2, w.data.size)
            o += 4
            w.data.copyInto(out, o)
            o += w.data.size
        }
        return out
    }

    /** Mode 20: the v2 ops after it in the same batch draw inside [r] only. Batch-only:
     *  the firmware refuses it at the top level (reason 10). */
    fun clip(r: Rect): ByteArray {
        checkRect2(r, "mode-20 clip")
        val out = ByteArray(9)
        out[0] = 20
        writeRect16(out, 1, r)
        return out
    }

    fun clipPair(l: Rect, r: Rect): ByteArray {
        checkPair2(l, r, "mode-20 clip")
        val out = ByteArray(17)
        out[0] = (20 or STEREO_BIT).toByte()
        writeRect16(out, 1, l); writeRect16(out, 9, r)
        return out
    }

    /** Mode 21: fill [r] with [level] 0..15 — nibble-exact, no fid, no zlib. */
    fun fill(r: Rect, level: Int): ByteArray {
        checkRect2(r, "mode-21 fill")
        checkLevel(level, "mode-21")
        val out = ByteArray(10)
        out[0] = 21
        writeRect16(out, 1, r)
        out[9] = level.toByte()
        return out
    }

    fun fillPair(l: Rect, r: Rect, level: Int): ByteArray {
        checkPair2(l, r, "mode-21 fill")
        checkLevel(level, "mode-21")
        val out = ByteArray(18)
        out[0] = (21 or STEREO_BIT).toByte()
        writeRect16(out, 1, l); writeRect16(out, 9, r)
        out[17] = level.toByte()
        return out
    }

    /** The 8 LUT bytes for [table] (16 entries 0..15): entry i is nibble i, high nibble first. */
    fun lutBytes(table: IntArray): ByteArray {
        if (table.size != 16) throw LintError("a LUT has 16 entries, not ${table.size}")
        for ((i, v) in table.withIndex()) if (v !in 0..15) throw LintError("LUT entry $i is $v, not 0..15")
        return ByteArray(8) { i -> ((table[2 * i] shl 4) or table[2 * i + 1]).toByte() }
    }

    /** Mode 22: every pixel p of [r] becomes table[p]. */
    fun lut(r: Rect, table: IntArray): ByteArray {
        checkRect2(r, "mode-22 LUT")
        val out = ByteArray(17)
        out[0] = 22
        writeRect16(out, 1, r)
        lutBytes(table).copyInto(out, 9)
        return out
    }

    fun lutPair(l: Rect, r: Rect, table: IntArray): ByteArray {
        checkPair2(l, r, "mode-22 LUT")
        val out = ByteArray(25)
        out[0] = (22 or STEREO_BIT).toByte()
        writeRect16(out, 1, l); writeRect16(out, 9, r)
        lutBytes(table).copyInto(out, 17)
        return out
    }

    /** The bytes a save-under capture of [r] takes in the pool: tight rows, two pixels per byte. */
    fun saveBytes(r: Rect): Int = ((r.w + 1) / 2) * r.h

    /** Mode 23 sub 0: capture [r] into [slot].
     *
     *  The check below is **one rect against the whole pool**, and the firmware prices a capture
     *  against what is LEFT of it — `FIRMWARE.md` §4 says "four slots, [SAVE_BUDGET] **between
     *  them**", and `GlassFirmwareSim` models that correctly. So two 24,000 B captures into
     *  different slots both encode here and the second is refused on glass with reason 7
     *  (2026-09-16 review). The encoder is stateless and cannot know the pool; the caller must
     *  keep the ledger. `POPOVER.md`'s deck capture is the first customer and the place to put
     *  it — until then this bound only catches a single rect that could never fit. */
    fun saveCapture(slot: Int, r: Rect): ByteArray {
        checkSlot(slot)
        checkRect2(r, "mode-23 capture")
        if (saveBytes(r) > SAVE_BUDGET) throw LintError("mode-23 capture of $r (${saveBytes(r)} B) exceeds the $SAVE_BUDGET B pool")
        val out = ByteArray(11)
        out[0] = 23; out[1] = 0; out[2] = slot.toByte()
        writeRect16(out, 3, r)
        return out
    }

    fun saveCapturePair(slot: Int, l: Rect, r: Rect): ByteArray {
        checkSlot(slot)
        checkPair2(l, r, "mode-23 capture")
        if (saveBytes(l) > SAVE_BUDGET) throw LintError("mode-23 capture of $l (${saveBytes(l)} B) exceeds the $SAVE_BUDGET B pool")
        val out = ByteArray(19)
        out[0] = (23 or STEREO_BIT).toByte(); out[1] = 0; out[2] = slot.toByte()
        writeRect16(out, 3, l); writeRect16(out, 11, r)
        return out
    }

    /** Mode 23 sub 1: write [slot] back at its captured rect (an empty slot is refused, reason 7). */
    fun saveRestore(slot: Int): ByteArray { checkSlot(slot); return byteArrayOf(23, 1, slot.toByte()) }

    /** Mode 23 sub 2: free [slot] (an empty slot is a no-op). */
    fun saveFree(slot: Int): ByteArray { checkSlot(slot); return byteArrayOf(23, 2, slot.toByte()) }

    /** Mode 24: the batch's present transfers rows [y0]..[y1] (inclusive) through the JBD4010's
     *  per-row partial entry. Batch-only. The rows must cover every pixel the batch changed —
     *  the panel shows nothing else until the next full refresh. */
    fun presentHint(y0: Int, y1: Int): ByteArray {
        if (y0 < 0 || y1 < y0 || y1 >= Geometry.PANEL_H)
            throw LintError("mode-24 rows $y0..$y1 are not 0 <= y0 <= y1 < ${Geometry.PANEL_H}")
        val out = ByteArray(5)
        out[0] = 24
        put16(out, 1, y0); put16(out, 3, y1)
        return out
    }

    /** The inline x-adjust byte for mode 18 (the same channel as mode 14). */
    fun xAdjust2(dx: Int): Byte = xAdjust(dx)

    // ---- build C1 (`FIRMWARE.md` §5.5, §5.9): mode 26 motion control and mode 25 the deflated cache write. Sent only
    // with the flag each needs in force (ENGINE, ZWRITE — `CfwTransportBase`); the encoders lint the fields alone.
    const val MOTION_MODE = 26
    const val ZWRITE_MODE = 25
    const val MOTION_PLAY = 0
    const val MOTION_RETARGET = 1
    const val MOTION_CLOSE = 2
    /** CLOSE's slot for every instance (§5.5). */
    const val MOTION_ALL = 0xFF
    const val MOTION_SLOTS = 4
    /** §5.5: a PLAY's period, in OS ticks per step. */
    val MOTION_PERIODS = 16..128
    /** §5.9: what a mode-25 stream inflates to at most — exactly a mode-19 entry list, no mode byte. */
    const val ZWRITE_MAX = 16_384

    /** Mode 26 sub 0 PLAY (§5.5): `slot u8 · period u8 · prog u16 (off4) · tag u16 · cap u16 (off4) · P0..P3 s16`. */
    fun motionPlay(slot: Int, period: Int, prog4: Int, tag: Int, cap4: Int, p: IntArray): ByteArray {
        if (slot !in 0 until MOTION_SLOTS) throw LintError("mode-26 PLAY slot $slot is not 0..3")
        if (period !in MOTION_PERIODS) throw LintError("mode-26 PLAY period $period is not 16..128 ticks")
        checkOff4(prog4, "mode-26 PLAY program"); checkOff4(cap4, "mode-26 PLAY capture")
        if (tag !in 0..0xFFFF) throw LintError("mode-26 PLAY tag $tag is not a u16")
        val out = byteArrayOf(MOTION_MODE.toByte(), MOTION_PLAY.toByte(), slot.toByte(), period.toByte()) + ByteArray(14)
        put16(out, 4, prog4); put16(out, 6, tag); put16(out, 8, cap4)
        putParams(out, 10, p)
        return out
    }

    /** Mode 26 sub 1 RETARGET (§5.5): `slot u8 · P0..P3 s16`. */
    fun motionRetarget(slot: Int, p: IntArray): ByteArray {
        if (slot !in 0 until MOTION_SLOTS) throw LintError("mode-26 RETARGET slot $slot is not 0..3")
        val out = byteArrayOf(MOTION_MODE.toByte(), MOTION_RETARGET.toByte(), slot.toByte()) + ByteArray(8)
        putParams(out, 3, p)
        return out
    }

    /** Mode 26 sub 2 CLOSE (§5.5): one slot, or [MOTION_ALL]. */
    fun motionClose(slot: Int): ByteArray {
        if (slot !in 0 until MOTION_SLOTS && slot != MOTION_ALL) throw LintError("mode-26 CLOSE slot $slot is not 0..3 or 0xFF")
        return byteArrayOf(MOTION_MODE.toByte(), MOTION_CLOSE.toByte(), slot.toByte())
    }

    private fun putParams(out: ByteArray, at: Int, p: IntArray) {
        if (p.size > 4) throw LintError("mode 26 carries four parameters, not ${p.size}")
        for (i in 0 until 4) {
            val v = p.getOrElse(i) { 0 }
            if (v !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("mode-26 parameter P$i = $v is not an s16")
            putS16(out, at + 2 * i, v)
        }
    }

    /** Mode 25 (§5.9): [mode19] — a whole mode-19 message as [cacheUpdate2] builds it — deflated: `[25][zlib of its
     *  entry list]`. The list (the message less its mode byte) inflates to at most [ZWRITE_MAX] bytes. */
    fun zwrite(mode19: ByteArray): ByteArray {
        if (mode19.size < 5 || mode19[0].toInt() != 19) throw LintError("mode 25 deflates a mode-19 message, not mode ${mode19.firstOrNull()}")
        if (mode19.size - 1 > ZWRITE_MAX) throw LintError("a mode-25 list of ${mode19.size - 1} B exceeds the $ZWRITE_MAX B inflate")
        return byteArrayOf(ZWRITE_MODE.toByte()) + wm.damage.core.gfx.Zl.deflate(mode19.copyOfRange(1, mode19.size))
    }

    // ---- build C2 (`FIRMWARE.md` §6.3, §6.5, §6.6): mode 27 the bindings, mode 26's subs 3–5 the answers, mode 28 the
    // vertical-scale draw. Sent only on a bit-10 build with BIND in force (`CfwTransportBase`'s gate at the image lane);
    // the encoders lint every field §6 bounds offline. What depends on the glasses' table (one slot per list, a cursor
    // below its list's count, the program's walk) is the glasses' to refuse, and the phone's rules never send it.
    const val BIND_MODE = 27
    const val VSCALE_MODE = 28
    const val BIND_SET = 0
    const val BIND_CLEAR = 1
    const val BIND_LIST = 2
    const val MOTION_COMMIT = 3
    const val MOTION_DISCARD = 4
    const val MOTION_SETTLE = 5
    /** §6.3: entries 0..7, lists 0..3; CLEAR's and DISCARD/SETTLE's "every" and "none". */
    const val BIND_ENTRIES = 8
    const val BIND_LISTS = 4
    const val BIND_ALL = 0xFF
    /** Build C2-FA (decisions AG.5): the sub-messages a batch keeps for the local rules' tail while a bound list is in
     *  front with BIND in force (`Compositor.subsLeft`) — the most one change of the list in front puts in a tail, read
     *  from `LocalRules.tail`: one list's CLEARs and the next one's SETs and LIST (2·8 + 1), the table repair's re-sends
     *  (8), an answer per slot (4) with its list's riding LIST (4), a SETTLE (1), and the wrap's PLAY ahead of them
     *  (decisions AZ.4, 1). A tail that passes it anyway is refused loudly with its batch ([batch]: the count is one byte),
     *  never wrapped. */
    const val BIND_TAIL_SUBS = 2 * BIND_ENTRIES + 1 + BIND_ENTRIES + MOTION_SLOTS + BIND_LISTS + 1 + 1
    /** SET's devType for a scroll entry (any source) and a tap entry (the ring); §6.1's event ids. */
    const val DEV_ANY = 0xFF
    const val DEV_RING = 4
    const val EV_TAP = 0
    const val EV_UPROLL = 4
    const val EV_DOWNROLL = 5
    /** SET's prog for a report-only tap entry, LIST's items for "no table" (§6.3). */
    const val PROG_NONE = 0xFFFF
    const val ITEMS_NONE = 0xFFFF
    /** §6.3 LIST: a list holds 1..1024 rows. */
    const val LIST_MAX = 1024
    /** §6.2 step 9's parameter kinds: 0 the arg · 1 the item table's entry at c' + arg · 2 c' × pitch + arg. */
    const val KIND_ARG = 0
    const val KIND_ITEM = 1
    const val KIND_PITCH = 2
    /** §6.6: mode 28's and a scaled DRAW's output height. */
    val VSCALE_H = 1..2048

    /** One parameter of a SET (§6.3: `kind u8 (0..2) · arg s16`). */
    data class BindParam(val kind: Int, val arg: Int)

    /** Mode 27 sub 0 SET (§6.3): `entry u8 · devType u8 · eventId u8 · list u8 · step s8 · slot u8 · period u8 · prog u16 ·
     *  4 × {kind u8, arg s16}` — 23 B with the header. Two shapes only: a scroll entry (eventId 4 or 5, devType 0xFF, step
     *  ≠ 0) and a tap entry (eventId 0, devType 4, step 0); a tap entry with prog [PROG_NONE] is report-only. */
    fun bindSet(entry: Int, devType: Int, eventId: Int, list: Int, step: Int, slot: Int, period: Int, prog4: Int,
                params: List<BindParam>): ByteArray {
        if (entry !in 0 until BIND_ENTRIES) throw LintError("mode-27 SET entry $entry is not 0..7")
        if (list !in 0 until BIND_LISTS) throw LintError("mode-27 SET list $list is not 0..3")
        if (slot !in 0 until MOTION_SLOTS) throw LintError("mode-27 SET slot $slot is not 0..3")
        if (period !in MOTION_PERIODS) throw LintError("mode-27 SET period $period is not 16..128 ticks")
        if (step !in Byte.MIN_VALUE..Byte.MAX_VALUE) throw LintError("mode-27 SET step $step is not an s8")
        checkOff4(prog4, "mode-27 SET program")
        val scroll = (eventId == EV_UPROLL || eventId == EV_DOWNROLL) && devType == DEV_ANY && step != 0
        val tap = eventId == EV_TAP && devType == DEV_RING && step == 0
        if (!scroll && !tap) throw LintError("mode-27 SET entry $entry (devType $devType, eventId $eventId, step $step) is " +
            "neither a scroll entry (id 4 or 5, devType 0xFF, step ≠ 0) nor a tap entry (id 0, devType 4, step 0) — refused (12)")
        if (scroll && prog4 == PROG_NONE) throw LintError("mode-27 SET: a scroll entry naming prog 0xFFFF — refused (5): no program fits there")
        if (params.size > 4) throw LintError("mode-27 SET carries four parameters, not ${params.size}")
        val out = ByteArray(23)
        out[0] = BIND_MODE.toByte(); out[1] = BIND_SET.toByte()
        out[2] = entry.toByte(); out[3] = devType.toByte(); out[4] = eventId.toByte(); out[5] = list.toByte()
        out[6] = step.toByte(); out[7] = slot.toByte(); out[8] = period.toByte()
        put16(out, 9, prog4)
        for (i in 0 until 4) {
            val p = params.getOrElse(i) { BindParam(KIND_ARG, 0) }
            if (p.kind !in KIND_ARG..KIND_PITCH) throw LintError("mode-27 SET parameter $i kind ${p.kind} is not 0..2")
            if (p.arg !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("mode-27 SET parameter $i arg ${p.arg} is not an s16")
            out[11 + 3 * i] = p.kind.toByte()
            putS16(out, 12 + 3 * i, p.arg)
        }
        return out
    }

    /** Mode 27 sub 1 CLEAR (§6.3): one entry, or [BIND_ALL]. Lists stay. */
    fun bindClear(entry: Int): ByteArray {
        if (entry !in 0 until BIND_ENTRIES && entry != BIND_ALL) throw LintError("mode-27 CLEAR entry $entry is not 0..7 or 0xFF")
        return byteArrayOf(BIND_MODE.toByte(), BIND_CLEAR.toByte(), entry.toByte())
    }

    /** Mode 27 sub 2 LIST (§6.3): `list u8 · items u16 (off4, or [ITEMS_NONE]) · count u16 (1..1024) · shift s16 · pitch
     *  s16 · mark u8` — 12 B with the header. Always applied on the glasses: the cursor moves by [shift] or is lost. */
    fun bindList(list: Int, items4: Int, count: Int, shift: Int, pitch: Int, mark: Int): ByteArray {
        if (list !in 0 until BIND_LISTS) throw LintError("mode-27 LIST list $list is not 0..3")
        checkOff4(items4, "mode-27 LIST item table")
        if (count !in 1..LIST_MAX) throw LintError("mode-27 LIST count $count is not 1..$LIST_MAX")
        if (shift !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("mode-27 LIST shift $shift is not an s16")
        if (pitch !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("mode-27 LIST pitch $pitch is not an s16")
        if (mark !in 0..0xFF) throw LintError("mode-27 LIST mark $mark is not a u8")
        val out = ByteArray(12)
        out[0] = BIND_MODE.toByte(); out[1] = BIND_LIST.toByte(); out[2] = list.toByte()
        put16(out, 3, items4); put16(out, 5, count); putS16(out, 7, shift); putS16(out, 9, pitch)
        out[11] = mark.toByte()
        return out
    }

    /** Mode 26 sub 3 COMMIT (§6.5): `slot u8 · S u16 · list u8 · cursor u16 · prog u16 · P0..P3 s16` — 18 B with the
     *  header. The phone agrees with the report S: END(prog, P) into both shadows. */
    fun motionCommit(slot: Int, s: Int, list: Int, cursor: Int, prog4: Int, p: IntArray): ByteArray {
        if (slot !in 0 until MOTION_SLOTS) throw LintError("mode-26 COMMIT slot $slot is not 0..3")
        if (list !in 0 until BIND_LISTS) throw LintError("mode-26 COMMIT list $list is not 0..3")
        checkS(s, "COMMIT"); checkCursor(cursor, "COMMIT"); checkOff4(prog4, "mode-26 COMMIT program")
        val out = ByteArray(18)
        out[0] = MOTION_MODE.toByte(); out[1] = MOTION_COMMIT.toByte(); out[2] = slot.toByte()
        put16(out, 3, s); out[5] = list.toByte(); put16(out, 6, cursor); put16(out, 8, prog4)
        putParams(out, 10, p)
        return out
    }

    /** Mode 26 sub 4 DISCARD (§6.5): `slot u8 (0xFF all) · S u16 · list u8 (0xFF none) · cursor u16` — 8 B with the
     *  header. Stops every local instance tagged at or after [s] in the named slots; sets the named list's cursor. */
    fun motionDiscard(slot: Int, s: Int, list: Int, cursor: Int): ByteArray {
        checkSlotOrAll(slot, "DISCARD"); checkListOrNone(list, "DISCARD"); checkS(s, "DISCARD"); checkCursor(cursor, "DISCARD")
        val out = ByteArray(8)
        out[0] = MOTION_MODE.toByte(); out[1] = MOTION_DISCARD.toByte(); out[2] = slot.toByte()
        put16(out, 3, s); out[5] = list.toByte(); put16(out, 6, cursor)
        return out
    }

    /** Mode 26 sub 5 SETTLE (§6.5): `slot u8 (0xFF all) · S u16 · list u8 (0xFF none) · cursor u16 · age u16` — 10 B with
     *  the header. Stops each local tagged at or after [s] that has held its last step at least [age] OS ticks. */
    fun motionSettle(slot: Int, s: Int, list: Int, cursor: Int, age: Int): ByteArray {
        checkSlotOrAll(slot, "SETTLE"); checkListOrNone(list, "SETTLE"); checkS(s, "SETTLE"); checkCursor(cursor, "SETTLE")
        if (age !in 0..0xFFFF) throw LintError("mode-26 SETTLE age $age is not a u16")
        val out = ByteArray(10)
        out[0] = MOTION_MODE.toByte(); out[1] = MOTION_SETTLE.toByte(); out[2] = slot.toByte()
        put16(out, 3, s); out[5] = list.toByte(); put16(out, 6, cursor); put16(out, 8, age)
        return out
    }

    /** Mode 28 (§6.6): the v2 record at [off4] drawn [hOut] rows tall (1..2048) at a signed (x, y) — `off4 u16 · x s16 ·
     *  y s16 · options u8 · H u16`, 10 B with the mode byte. [w] is the record's width, for the lint that a draw wholly
     *  off the panel costs a message for nothing. Clip, options and transparency as mode 17. */
    fun drawScaled(off4: Int, x: Int, y: Int, options: Int, hOut: Int, w: Int): ByteArray {
        checkOff4(off4, "mode-28 record")
        if (hOut !in VSCALE_H) throw LintError("mode-28 H $hOut is not 1..2048 — refused (12)")
        checkDraw2(x, y, w, hOut, "mode-28")
        checkOptions(options, "mode-28")
        val out = ByteArray(10)
        out[0] = VSCALE_MODE.toByte()
        put16(out, 1, off4); putS16(out, 3, x); putS16(out, 5, y); out[7] = options.toByte(); put16(out, 8, hOut)
        return out
    }

    /** Mode 28's per-lens form (`0x9C`): the left lens draws at [xL], the right at [xR] — 12 B with the mode byte. */
    fun drawScaledPair(off4: Int, xL: Int, xR: Int, y: Int, options: Int, hOut: Int, w: Int): ByteArray {
        checkOff4(off4, "mode-28 record")
        if (hOut !in VSCALE_H) throw LintError("mode-28 H $hOut is not 1..2048 — refused (12)")
        checkDraw2(xL, y, w, hOut, "mode-28 L"); checkDraw2(xR, y, w, hOut, "mode-28 R")
        checkOptions(options, "mode-28")
        val out = ByteArray(12)
        out[0] = (VSCALE_MODE or STEREO_BIT).toByte()
        put16(out, 1, off4); putS16(out, 3, xL); putS16(out, 5, xR); putS16(out, 7, y); out[9] = options.toByte(); put16(out, 10, hOut)
        return out
    }

    /** Build C2's gate (§6.8: "the phone sends none of them without it"): true when [image] — top level, a mode-8 batch's
     *  sub-messages, a self-test step's message — carries mode 27, mode 28 or a mode-26 sub 3..5. */
    fun carriesBind(image: ByteArray): Boolean {
        fun one(m: ByteArray, from: Int, to: Int): Boolean {
            if (to - from < 1) return false
            val mode = m[from].toInt() and 0x7F
            return when {
                mode == BIND_MODE || mode == VSCALE_MODE -> true
                mode == MOTION_MODE -> to - from >= 2 && (m[from + 1].toInt() and 0xFF) in MOTION_COMMIT..MOTION_SETTLE
                mode == 8 && to - from >= 2 -> {
                    var i = from + 2
                    var k = 0
                    val n = m[from + 1].toInt() and 0xFF
                    while (k < n && i + 2 <= to) {
                        val len = (m[i].toInt() and 0xFF) or ((m[i + 1].toInt() and 0xFF) shl 8)
                        if (one(m, i + 2, minOf(to, i + 2 + len))) return true
                        i += 2 + len; k++
                    }
                    false
                }
                mode == SELF_TEST_MODE && to - from >= 3 && m[from + 1].toInt() == 1 -> one(m, from + 2, to)
                else -> false
            }
        }
        return one(image, 0, image.size)
    }

    private fun checkS(s: Int, what: String) { if (s !in 0..0xFFFF) throw LintError("mode-26 $what S $s is not a u16") }
    private fun checkCursor(c: Int, what: String) {
        // a cursor names a row of a list of 1..1024: 0..1023 (whether it is below the named list's count is the glasses')
        if (c !in 0 until LIST_MAX) throw LintError("mode-26 $what cursor $c is not 0..${LIST_MAX - 1}")
    }
    private fun checkSlotOrAll(slot: Int, what: String) {
        if (slot !in 0 until MOTION_SLOTS && slot != BIND_ALL) throw LintError("mode-26 $what slot $slot is not 0..3 or 0xFF")
    }
    private fun checkListOrNone(list: Int, what: String) {
        if (list !in 0 until BIND_LISTS && list != BIND_ALL) throw LintError("mode-26 $what list $list is not 0..3 or 0xFF")
    }

    private fun checkOff4(off4: Int, what: String) {
        if (off4 !in 0..0xFFFF) throw LintError("$what offset $off4 (4-byte units) is outside the u16 field")
    }

    private fun checkFont4(font4: Int) {
        if (font4 !in 0..0xFFFF) throw LintError("mode-18 font table at $font4 (4-byte units) is outside the u16 field")
        if (font4 * 4 + FONT2_TABLE_BYTES > CACHE2_MAX_RIDERS)
            throw LintError("mode-18 font table at ${font4 * 4} does not fit its $FONT2_TABLE_BYTES B in the largest cache")
    }

    /** A v2 draw may start off the panel (the firmware clips), but one whose whole box is off
     *  the panel costs a message for nothing: refused here, as mode 13's origin check is. */
    private fun checkDraw2(x: Int, y: Int, w: Int, h: Int, what: String) {
        if (x !in -32768..32767 || y !in -32768..32767) throw LintError("$what draw at ($x,$y) is outside the s16 fields")
        if (x + w <= 0 || y + h <= 0 || x >= Geometry.PANEL_W || y >= Geometry.PANEL_H)
            throw LintError("$what draw of ${w}x$h at ($x,$y) lies wholly outside the ${Geometry.PANEL_W}x${Geometry.PANEL_H} panel")
    }

    private fun checkTextXY(x: Int, y: Int, what: String) {
        if (x !in -32768..32767 || y !in -32768..32767) throw LintError("$what draw at ($x,$y) is outside the s16 fields")
        if (x >= Geometry.PANEL_W || y >= Geometry.PANEL_H)
            throw LintError("$what draw origin ($x,$y) starts past the ${Geometry.PANEL_W}x${Geometry.PANEL_H} panel")
    }

    private fun checkText2(text: ByteArray, what: String) {
        if (text.isEmpty()) throw LintError("empty $what string")
        if (text.size > 0xFF) throw LintError("$what string of ${text.size} B exceeds the u8 length")
        for ((i, b) in text.withIndex()) if (b.toInt() == 0)
            throw LintError("$what string byte $i is 0; the firmware rejects the whole message")
    }

    /** A wire rect for the v2 ops: unquantized, non-empty, inside the panel. */
    private fun checkRect2(r: Rect, what: String) {
        if (r.w <= 0 || r.h <= 0) throw LintError("$what $r is empty")
        if (r.x < 0 || r.y < 0 || r.right > Geometry.PANEL_W || r.bottom > Geometry.PANEL_H)
            throw LintError("$what $r is outside the ${Geometry.PANEL_W}x${Geometry.PANEL_H} panel — refused in silence")
    }

    /** A per-lens pair: one size, one y (`FIRMWARE.md` §4: horizontal offsets only). */
    private fun checkPair2(l: Rect, r: Rect, what: String) {
        checkRect2(l, "$what L"); checkRect2(r, "$what R")
        if (l.w != r.w || l.h != r.h) throw LintError("$what pair $l / $r: one size")
        if (l.y != r.y) throw LintError("$what pair $l / $r: vertical disparity is forbidden (DESIGN.md §3.4)")
    }

    private fun checkLevel(level: Int, what: String) {
        if (level !in 0..15) throw LintError("$what level $level is not 0..15")
    }

    private fun checkSlot(slot: Int) {
        if (slot !in 0 until SAVE_SLOTS) throw LintError("mode-23 slot $slot is not 0..${SAVE_SLOTS - 1}")
    }

    private fun putS16(out: ByteArray, off: Int, v: Int) = put16(out, off, v and 0xFFFF)

    private fun checkCacheOffset(off: Int, what: String) {
        // An image needs at least its [w][h] header plus one token.
        if (off < 0 || off > TEXTURE_CACHE_SIZE - 3)
            throw LintError("$what offset $off cannot hold an image in the " +
                "$TEXTURE_CACHE_SIZE B texture cache")
    }

    /** The firmware clips a cached draw against the panel and accepts an origin that
     *  puts every pixel off-screen — it costs a message and an ack and paints nothing.
     *  Partial clipping off the right or bottom edge is legitimate and stays allowed;
     *  an origin that is already past the panel is a caller bug. */
    private fun checkDrawXY(x: Int, y: Int, what: String) {
        if (x !in 0..0xFFFF || y !in 0..0xFFFF)
            throw LintError("$what draw at ($x,$y) is outside the u16 coordinate fields")
        if (x >= Geometry.PANEL_W || y >= Geometry.PANEL_H)
            throw LintError("$what draw origin ($x,$y) starts outside the " +
                "${Geometry.PANEL_W}x${Geometry.PANEL_H} panel; every pixel would clip away " +
                "and the message would cost an ack for nothing")
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
