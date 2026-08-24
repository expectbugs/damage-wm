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
 *   mode 8:  [8][count][len16][submsg]...                        atomic multi-op (3/6/9 only)
 *   mode 7:  [7][sub]                                            0 clear flags · 1 hide · 2 show
 *
 * The HIGH BIT of the mode byte is the "lenses differ" flag. Mode-3 boxes are
 * QUANTIZED (left/width x4, top/height x2, one byte each); mode-9 rects are full
 * uint16 and may overlap. A malformed payload is rejected in SILENCE and the
 * previous frame stays up — which is why every builder here lint-checks first.
 */
object CfwModes {
    const val STEREO_BIT = 0x80

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

    /** Atomic multi-op batch. Only shadow ops 3/6/9 are legal inside; the
     *  firmware checks `submode & 0x7f` so stereo sub-ops ride fine. Sub-messages
     *  apply to the shadow IN ORDER, then one present (zlib_glue.c mode 8). */
    fun batch(subs: List<ByteArray>): ByteArray {
        if (subs.isEmpty()) throw LintError("empty mode-8 batch")
        if (subs.size > 0xFF) throw LintError("mode-8 count ${subs.size} exceeds the u8 field")
        for ((i, s) in subs.withIndex()) {
            if (s.isEmpty()) throw LintError("mode-8 sub $i empty")
            val m = s[0].toInt() and 0x7F
            if (m != 3 && m != 6 && m != 9)
                throw LintError("mode-8 sub $i is mode $m; only shadow ops 3/6/9 are accepted")
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
