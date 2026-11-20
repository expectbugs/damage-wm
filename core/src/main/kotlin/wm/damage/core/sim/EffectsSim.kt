package wm.damage.core.sim

import wm.damage.core.geom.Rect

/**
 * `FIRMWARE.md` §7.4–§7.6 (build C3): the effect verbs of ONE lens as the simulator models them — the hash, SHRINK's
 * drop order, G(b → k), PIECES (both layers), SHRINK, and GLYPHS (CELLS and RAIN) with the walks of the records they
 * name. Written from §7's text alone, never from the fork's C, so that the conformance vectors compare two readings of
 * one contract. Stateless: every result is a function of its arguments (the in-place verbs read their base from the
 * framebuffer they are given, §7.2 "the base"). Where §7 allows two readings, the one taken is marked READING.
 *
 * Every product of §7.4–§7.6 is a 32-bit product that wraps, read as s32, `>>` arithmetic (§7.2): Kotlin's [Int]
 * arithmetic is exactly that. "mod" is the unsigned remainder ([umod]).
 */
object EffectsSim {

    // ------------------------------------------------------------------------------------------- §7.6 the hash
    /** §7.6 h(x), u32 wrapping: x ^= x >> 16; x *= 0x7FEB352D; x ^= x >> 15; x *= 0x846CA68B; x ^= x >> 16. */
    fun h(v: Int): Int {
        var x = v
        x = x xor (x ushr 16)
        x *= 0x7FEB352D
        x = x xor (x ushr 15)
        x *= 0x846CA68B.toInt()
        return x xor (x ushr 16)
    }

    /** §7.6 H2(s, a) = h(s ^ h(a ^ 0x9E3779B9)). */
    fun h2(s: Int, a: Int): Int = h(s xor h(a xor 0x9E3779B9.toInt()))

    /** §7.6 H3(s, a, b) = h(H2(s, a) ^ h(b ^ 0x85EBCA6B)). */
    fun h3(s: Int, a: Int, b: Int): Int = h(h2(s, a) xor h(b xor 0x85EBCA6B.toInt()))

    /** §7.6: "mod" is the unsigned remainder of a u32 by [n] ≥ 1. */
    fun umod(x: Int, n: Int): Int = ((x.toLong() and 0xFFFFFFFFL) % n).toInt()

    // ------------------------------------------------------------------------------------- §7.5 the drop order
    /** §7.5: with m the least integer such that 2^m ≥ [n], for t = 0 .. 2^m − 1, i = t with its m low bits reversed; each
     *  i < n is the next index. */
    fun dropOrder(n: Int): IntArray {
        require(n >= 1) { "a drop order of size $n" }
        var m = 0
        while ((1 shl m) < n) m++
        val out = IntArray(n)
        var k = 0
        for (t in 0 until (1 shl m)) {
            var i = 0
            for (b in 0 until m) if (t and (1 shl b) != 0) i = i or (1 shl (m - 1 - b))
            if (i < n) out[k++] = i
        }
        return out
    }

    // ----------------------------------------------------------------------------------------------- §7.4 G
    /** §7.4: G(b → k) as a 16-entry table from [levels] = L_b .. L_k (L_0 = 15 is the caller's): g_t(v) = 0 when
     *  L_(t−1) = 0, else min(15, ⌊v·L_t / L_(t−1)⌋), applied g_(b+1) first. The identity when b = k. */
    fun gTable(levels: IntArray): IntArray = IntArray(16) { v0 ->
        var v = v0
        for (t in 1 until levels.size) v = if (levels[t - 1] == 0) 0 else minOf(15, (v * levels[t]) / levels[t - 1])
        v
    }

    // ---------------------------------------------------------------------------------------- §7.4 PIECES
    /** §7.4: piece [p]'s home rect in [area] — columns [l + ⌊i·w/gw⌋, l + ⌊(i+1)·w/gw⌋), rows likewise. */
    fun home(area: Rect, gw: Int, gh: Int, p: Int): Rect {
        val i = p % gw; val j = p / gw
        val x0 = area.x + (i * area.w) / gw; val x1 = area.x + ((i + 1) * area.w) / gw
        val y0 = area.y + (j * area.h) / gh; val y1 = area.y + ((j + 1) * area.h) / gh
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    /** What a PIECES segment's displacement reads, per lens: the grid, model, stagger, the factor's ends, the anchor
     *  (x, y), the displacement (dx, dy), the seed, the reseed bit and the eases (over the segment's steps, and over
     *  n' = steps − stagger when staggered). */
    class PiecesArgs(val gw: Int, val gh: Int, val model: Int, val stagger: Int, val f0: Int, val f1: Int,
                     val x: Int, val y: Int, val dx: Int, val dy: Int, val seed: Int, val reseed: Boolean,
                     val e: IntArray, val eStag: IntArray?)

    /** §7.4: D_p at the segment's local step [t] — the factor f from e, then the model's displacement. Under stagger
     *  the piece's own step t_p = min(max(t − δ_p, 0), n') and e is the ease at t_p over n'; opt bit 6's t is [t]. */
    fun displacement(a: PiecesArgs, area: Rect, p: Int, t: Int): IntArray {
        val e = if (a.stagger == 0) a.e[t] else {
            val n1 = a.e.size - 1 - a.stagger
            val delta = umod(h3(a.seed, p, 1), a.stagger + 1)
            a.eStag!![minOf(maxOf(t - delta, 0), n1)]
        }
        val f = a.f0 + (((a.f1 - a.f0) * e + 2048) shr 12)
        return when (a.model) {
            MODEL_UNIFORM -> intArrayOf((a.dx * f + 2048) shr 12, (a.dy * f + 2048) shr 12)
            MODEL_RADIAL -> {
                val hr = home(area, a.gw, a.gh, p)
                val cx = (hr.x + hr.right) shr 1; val cy = (hr.y + hr.bottom) shr 1
                intArrayOf(((cx - a.x) * f + 2048) shr 12, ((cy - a.y) * f + 2048) shr 12)
            }
            else -> {
                val r = h3(a.seed, p, if (a.reseed) t else 0)
                val rx = (r and 0xFFFF).toShort().toInt(); val ry = (r ushr 16).toShort().toInt()
                val bigDx = (a.dx * f + 2048) shr 12; val bigDy = (a.dy * f + 2048) shr 12
                intArrayOf((rx * bigDx) shr 15, (ry * bigDy) shr 15)
            }
        }
    }

    /** §7.4 layer new: over the track's backdrop (already painted), pieces in index order — the shadow's home rect of
     *  piece p drawn at its home rect moved by D_p, clipped to [area], through `make_lut(lvl)`; a source 0 is not drawn
     *  when [transparent]. */
    fun piecesNew(buf: ByteArray, stride: Int, shadow: ByteArray, area: Rect, a: PiecesArgs, t: Int, lvl: Int, transparent: Boolean) {
        val lut = lut(lvl)
        for (p in 0 until a.gw * a.gh) {
            val hr = home(area, a.gw, a.gh, p)
            val d = displacement(a, area, p, t)
            val dest = hr.translate(d[0], d[1]).intersect(area) ?: continue
            for (yy in dest.y until dest.bottom) for (xx in dest.x until dest.right) {
                val v = nib(shadow, stride, xx - d[0], yy - d[1])
                if (transparent && v == 0) continue
                setNib(buf, stride, xx, yy, lut[v])
            }
        }
    }

    /**
     * §7.4 layer old, in place, from base step b to step k: [buf]'s [area] holds the base picture (the framebuffer,
     * §7.2). [jb] and [jk] are the segment's local steps of the track's states at b and k. Every pixel q of the area
     * becomes G of the base pixel at q − Δ_p when q lies in some piece p's home rect moved by D_p(k) (clipped) and
     * q − Δ_p lies in p's base rect (its home rect moved by D_p(b), clipped); otherwise the vacate level [vacate], not
     * passed through G. READING: when two pieces' moved rects contain q (no accepted input makes that) the higher
     * index is THE piece for q and its base-rect test decides (the other reading: the highest index among the pieces
     * that pass both tests).
     */
    fun piecesOld(buf: ByteArray, stride: Int, area: Rect, a: PiecesArgs, jb: Int, jk: Int, g: IntArray, vacate: Int) {
        val n = a.gw * a.gh
        val base = grab(buf, stride, area)
        val owner = IntArray(area.w * area.h) { -1 }
        val dk = Array(n) { displacement(a, area, it, jk) }
        val db = Array(n) { displacement(a, area, it, jb) }
        for (p in 0 until n) {
            val dest = home(area, a.gw, a.gh, p).translate(dk[p][0], dk[p][1]).intersect(area) ?: continue
            for (yy in dest.y until dest.bottom) for (xx in dest.x until dest.right) owner[(yy - area.y) * area.w + (xx - area.x)] = p
        }
        for (yy in area.y until area.bottom) for (xx in area.x until area.right) {
            val p = owner[(yy - area.y) * area.w + (xx - area.x)]
            var v = vacate
            if (p >= 0) {
                val baseRect = home(area, a.gw, a.gh, p).translate(db[p][0], db[p][1]).intersect(area)
                val sx = xx - (dk[p][0] - db[p][0]); val sy = yy - (dk[p][1] - db[p][1])
                if (baseRect != null && sx >= baseRect.x && sx < baseRect.right && sy >= baseRect.y && sy < baseRect.bottom)
                    v = g[base[(sy - area.y) * area.w + (sx - area.x)].toInt()]
            }
            setNib(buf, stride, xx, yy, v)
        }
    }

    // ---------------------------------------------------------------------------------------- §7.5 SHRINK
    /**
     * §7.5 SHRINK, in place, from base step b at size ([wb], [hb]) to step k at size ([wk], [hk]) in [area] (Wa × Ha):
     * every pixel q becomes G of the base pixel at the place, at size (W(b), H(b)), of the kept row and column q is the
     * place of at size (W(k), H(k)); every other pixel the vacate level. [anchor] bits 0–1 vertical, 2–3 horizontal.
     */
    fun shrink(buf: ByteArray, stride: Int, area: Rect, wb: Int, hb: Int, wk: Int, hk: Int, anchor: Int, g: IntArray, vacate: Int) {
        val base = grab(buf, stride, area)
        val colFrom = placeMap(area.w, wb, wk, (anchor shr 2) and 3)
        val rowFrom = placeMap(area.h, hb, hk, anchor and 3)
        for (r in 0 until area.h) for (c in 0 until area.w) {
            val br = rowFrom[r]; val bc = colFrom[c]
            setNib(buf, stride, area.x + c, area.y + r, if (br < 0 || bc < 0) vacate else g[base[br * area.w + bc].toInt()])
        }
    }

    /** One axis of SHRINK: for each place q of an axis of [size] cells, the base place (at size [sb]) of the kept index
     *  q is the place of at size [sk], or −1 when q is no place. The kept indices at a size are the first ones of the
     *  drop order for [size]; the r-th kept index in increasing order lies at a + r (a: 0, (size − kept) >> 1 or
     *  size − kept by [anchor]). */
    private fun placeMap(size: Int, sb: Int, sk: Int, anchor: Int): IntArray {
        val order = dropOrder(size)
        val keptB = order.copyOfRange(0, sb).sortedArray()
        val keptK = order.copyOfRange(0, sk).sortedArray()
        val rankB = IntArray(size) { -1 }
        keptB.forEachIndexed { r, i -> rankB[i] = r }
        fun a(kept: Int) = when (anchor) { 1 -> (size - kept) shr 1; 2 -> size - kept; else -> 0 }
        val ab = a(sb); val ak = a(sk)
        return IntArray(size) { q ->
            val r = q - ak
            if (r !in 0 until sk) -1 else rankB[keptK[r]].let { check(it >= 0) { "SHRINK grew: index ${keptK[r]} kept at $sk and not at $sb" }; ab + it }
        }
    }

    // ---------------------------------------------------------------------------------------- §7.6 GLYPHS
    /** A v1 glyph record `[w u8][h u8][RLE]` as walked: its pixels and the bytes of its RLE stream. */
    class Glyph(val w: Int, val h: Int, val px: ByteArray, val rle: Int)

    /** One cell of a cell list (§7.6): box, font (byte offset of its 224-entry table), old and new codes, fill, opt. */
    class Cell(val bx: Int, val by: Int, val bw: Int, val bh: Int, val font: Int, val old: Int, val new: Int, val fill: Int, val opt: Int)

    /** A cell list as walked, with the glyphs its fonts' named codes resolve to (font byte offset → code → glyph). */
    class CellList(val cells: List<Cell>, val alphabet: IntArray, val noise: Int, val glyphs: Map<Int, Map<Int, Glyph>>)

    /** A rain record as walked (§7.6), with the glyphs of its alphabet in its font (code → glyph). */
    class Rain(val cols: Int, val trail: Int, val cw: Int, val ch: Int, val alphabet: IntArray, val headLvl: Int, val tailLvl: Int,
               val reveal: Int, val fillLvl: Int, val spread: Int, val flicker: Int, val glyphs: Map<Int, Glyph>)

    /** The lowest and highest byte a walk read, for the instance's first read range (§5.6). */
    class Reads(var lo: Int, var hi: Int) { fun add(a: Int, b: Int) { lo = minOf(lo, a); hi = maxOf(hi, b) } }

    /** §7.6 CELLS: the cell table at byte [tab], the list its entry [index] names, the alphabet, every font a cell
     *  names and every glyph record a cell's codes or the alphabet name in each of those fonts. Null for any fault
     *  (5, the walk's): a range, a reserved byte, anything outside the cache, an index at or past n. READING: the
     *  table's `0 u16`, the list's three zero bytes are reserved bytes and must be 0 (A.9), and padding to 4 B is not
     *  read (not in the read range). Code 32 names entry 0 and its record is walked (§7.6: "code c (32..255) names entry
     *  c − 32"; only the budget says codes 0 and 32 name no glyph "for this sum"). */
    fun walkCells(cache: ByteArray, tab: Int, index: Int, reads: Reads): CellList? {
        if (tab + 4 > cache.size) return null
        val n = u16(cache, tab)
        if (n !in 1..1024 || u16(cache, tab + 2) != 0 || tab + 4 + 2 * n > cache.size || index >= n) return null
        reads.add(tab, tab + 4 + 2 * n)
        val list = u16(cache, tab + 4 + 2 * index) * 4
        if (list + 8 > cache.size) return null
        val count = u16(cache, list)
        if (count !in 1..512 || u8(cache, list + 5) != 0 || u8(cache, list + 6) != 0 || u8(cache, list + 7) != 0) return null
        if (list + 8 + 12 * count > cache.size) return null
        reads.add(list, list + 8 + 12 * count)
        val alphabet = walkAlphabet(cache, u16(cache, list + 2) * 4, reads) ?: return null
        val noise = u8(cache, list + 4)
        val cells = ArrayList<Cell>(count)
        for (c in 0 until count) {
            val at = list + 8 + 12 * c
            val cell = Cell(s16(cache, at), s16(cache, at + 2), u8(cache, at + 4), u8(cache, at + 5), u16(cache, at + 6) * 4,
                u8(cache, at + 8), u8(cache, at + 9), u8(cache, at + 10), u8(cache, at + 11))
            if (!codeOk(cell.old) || !codeOk(cell.new) || !(cell.fill in 0..15 || cell.fill == 0xFF) || cell.opt and 0xD0 != 0) return null
            cells += cell
        }
        val glyphs = HashMap<Int, HashMap<Int, Glyph>>()
        for (cell in cells) {
            val byFont = glyphs.getOrPut(cell.font) { HashMap() }
            for (code in listOf(cell.old, cell.new) + alphabet.toList()) {
                if (code == 0 || code in byFont) continue
                byFont[code] = glyphOf(cache, cell.font, code, reads) ?: return null
            }
        }
        return CellList(cells, alphabet, noise, glyphs)
    }

    /** §7.6 RAIN: the 16-B rain record at byte [off] (ranges, reserved bytes 0), its alphabet, its font and every glyph
     *  its alphabet names there. Null for any fault (5). */
    fun walkRain(cache: ByteArray, off: Int, reads: Reads): Rain? {
        if (off + 16 > cache.size) return null
        val cols = u8(cache, off); val trail = u8(cache, off + 1); val cw = u8(cache, off + 2); val ch = u8(cache, off + 3)
        val headLvl = u8(cache, off + 8); val tailLvl = u8(cache, off + 9); val reveal = u8(cache, off + 10)
        val fillLvl = u8(cache, off + 11); val flicker = u8(cache, off + 13)
        if (cols !in 1..128 || trail !in 1..32 || cw < 1 || ch < 1 || headLvl > 15 || tailLvl > 15 || reveal > 2 ||
            fillLvl > 15 || flicker < 1 || u16(cache, off + 14) != 0) return null
        reads.add(off, off + 16)
        val font = u16(cache, off + 4) * 4
        val alphabet = walkAlphabet(cache, u16(cache, off + 6) * 4, reads) ?: return null
        val glyphs = HashMap<Int, Glyph>()
        for (code in alphabet) if (code !in glyphs) glyphs[code] = glyphOf(cache, font, code, reads) ?: return null
        return Rain(cols, trail, cw, ch, alphabet, headLvl, tailLvl, reveal, fillLvl, u8(cache, off + 12), flicker, glyphs)
    }

    private fun codeOk(c: Int) = c == 0 || c in 32..255

    /** §7.6 an alphabet: `n u8 (1..224) · codes` (each 32..255). */
    private fun walkAlphabet(cache: ByteArray, off: Int, reads: Reads): IntArray? {
        if (off + 1 > cache.size) return null
        val n = u8(cache, off)
        if (n !in 1..224 || off + 1 + n > cache.size) return null
        val codes = IntArray(n) { u8(cache, off + 1 + it) }
        if (codes.any { it < 32 }) return null
        reads.add(off, off + 1 + n)
        return codes
    }

    /** Code [code] (32..255) in the 224-entry v2 table at byte [font]: the table inside the cache, then the v1 glyph
     *  record its entry names, walked whole. */
    private fun glyphOf(cache: ByteArray, font: Int, code: Int, reads: Reads): Glyph? {
        if (font + FONT_TABLE > cache.size) return null
        reads.add(font, font + FONT_TABLE)
        val off = u16(cache, font + 2 * (code - 32)) * 4
        if (off + 2 > cache.size) return null
        val w = u8(cache, off); val h = u8(cache, off + 1)
        if (w == 0 || h == 0) return null
        val (px, end) = MotionSim.rle(cache, off + 2, w * h) ?: return null
        reads.add(off, end)
        return Glyph(w, h, px, end - off - 2)
    }

    /**
     * §7.6 CELLS at local step [t] of [n] steps: each cell in list order — its schedule s_c = 1 + (H2(seed, c) mod
     * (n − N)), old code before s_c, alphabet noise for N steps, the new code from s_c + N on; its box filled with
     * `fill` (when not 0xFF) unless it has settled to a non-zero new code; then its glyph (not for codes 0 and 32) at
     * the box's corner through `make_lut((opt & 0x20) | ⌊(opt & 15)·lvl / 15⌋)`, 0 pixels not drawn. Everything is
     * clipped to [area]; ([x], [y]) are the segment's offsets X(t), Y(t).
     */
    fun cells(buf: ByteArray, stride: Int, area: Rect, list: CellList, seed: Int, n: Int, t: Int, x: Int, y: Int, lvl: Int) {
        val span = n - list.noise
        list.cells.forEachIndexed { c, cell ->
            val sc = 1 + umod(h2(seed, c), span)
            val settled = t >= sc + list.noise
            val code = when {
                t < sc -> cell.old
                !settled -> list.alphabet[umod(h3(seed, c, t), list.alphabet.size)]
                else -> cell.new
            }
            val bx = cell.bx + x; val by = cell.by + y
            if (cell.fill != 0xFF && !(settled && cell.new != 0)) Rect(bx, by, cell.bw, cell.bh).intersect(area)?.let { fill(buf, stride, it, cell.fill) }
            if (code != 0 && code != 32)
                glyph(buf, stride, list.glyphs[cell.font]!![code]!!, bx, by, area, lut((cell.opt and 0x20) or (((cell.opt and 15) * lvl) / 15)))
        }
    }

    /**
     * §7.6 RAIN at local step [t] with ease value [e]: R = ⌈Ha / ch⌉ cell rows; column c at l + X + c·cw with start
     * offset o_c = H2(seed, c) mod (spread + 1) and head −o_c + (((R + o_c + trail)·e) >> 12). Lit cells (head − trail
     * < r ≤ head, 0 ≤ r < R): the box filled with fillLvl, then the glyph alphabet[H3(seed, c, r·256 + ⌊t/flicker⌋) mod
     * n] clipped to its box (code 32 draws nothing) through `make_lut(⌊L_u·lvl/15⌋)`. Above the trail: reveal 1 the box
     * from [shadow], 2 filled with fillLvl, 0 left. Below the head nothing. All clipped to [area].
     */
    fun rain(buf: ByteArray, stride: Int, shadow: ByteArray?, area: Rect, r0: Rain, seed: Int, t: Int, e: Int, x: Int, y: Int, lvl: Int) {
        val rows = (area.h + r0.ch - 1) / r0.ch
        for (c in 0 until r0.cols) {
            val xc = area.x + x + c * r0.cw
            val oc = umod(h2(seed, c), r0.spread + 1)
            val head = -oc + (((rows + oc + r0.trail) * e) shr 12)
            for (r in 0 until rows) {
                val yr = area.y + y + r * r0.ch
                val box = Rect(xc, yr, r0.cw, r0.ch).intersect(area) ?: continue
                if (r > head) continue                                  // below the head: nothing
                if (r > head - r0.trail) {
                    val u = head - r
                    fill(buf, stride, box, r0.fillLvl)
                    val code = r0.alphabet[umod(h3(seed, c, r * 256 + t / r0.flicker), r0.alphabet.size)]
                    if (code == 32) continue
                    val lu = if (r0.trail == 1) r0.headLvl else (r0.headLvl * (r0.trail - 1 - u) + r0.tailLvl * u) / (r0.trail - 1)
                    glyph(buf, stride, r0.glyphs[code]!!, xc, yr, box, lut((lu * lvl) / 15))
                } else when (r0.reveal) {
                    1 -> copy(shadow!!, buf, stride, box)
                    2 -> fill(buf, stride, box, r0.fillLvl)
                }
            }
        }
    }

    /** A glyph at (x0, y0) through [lut], its 0 pixels not drawn, clipped to [clip]. */
    private fun glyph(buf: ByteArray, stride: Int, g: Glyph, x0: Int, y0: Int, clip: Rect, lut: IntArray) {
        val ya = maxOf(clip.y, y0); val yb = minOf(clip.bottom, y0 + g.h)
        val xa = maxOf(clip.x, x0); val xb = minOf(clip.right, x0 + g.w)
        for (yy in ya until yb) for (xx in xa until xb) {
            val v = g.px[(yy - y0) * g.w + (xx - x0)].toInt()
            if (v != 0) setNib(buf, stride, xx, yy, lut[v])
        }
    }

    // ------------------------------------------------------------------------------------------- helpers
    /** cfw_texture_make_lut (§2's v1 LUT): level bits 0–3, bit 5 inverse. */
    fun lut(options: Int): IntArray = IntArray(16) { i -> ((if (options and 0x20 != 0) 15 - i else i) * (options and 15)) / 15 }

    fun fill(buf: ByteArray, stride: Int, r: Rect, level: Int) {
        for (yy in r.y until r.bottom) for (xx in r.x until r.right) setNib(buf, stride, xx, yy, level)
    }

    /** [r] of [from] into the same place of [to]. */
    fun copy(from: ByteArray, to: ByteArray, stride: Int, r: Rect) {
        for (yy in r.y until r.bottom) for (xx in r.x until r.right) setNib(to, stride, xx, yy, nib(from, stride, xx, yy))
    }

    /** [r] of [buf] as levels, row-major. */
    fun grab(buf: ByteArray, stride: Int, r: Rect): ByteArray = ByteArray(r.w * r.h) { nib(buf, stride, r.x + it % r.w, r.y + it / r.w).toByte() }

    const val MODEL_UNIFORM = 0; const val MODEL_RADIAL = 1; const val MODEL_RANDOM = 2
    /** §7.6 a font: a 224-entry v2 table, 448 B. */
    const val FONT_TABLE = 448

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    fun nib(buf: ByteArray, stride: Int, x: Int, y: Int): Int {
        val b = buf[y * stride + (x shr 1)].toInt() and 0xFF
        return if (x and 1 == 0) b shr 4 else b and 0x0F
    }
    fun setNib(buf: ByteArray, stride: Int, x: Int, y: Int, v: Int) {
        val i = y * stride + (x shr 1)
        val b = buf[i].toInt() and 0xFF
        buf[i] = (if (x and 1 == 0) (v shl 4) or (b and 0x0F) else (b and 0xF0) or v).toByte()
    }
}
