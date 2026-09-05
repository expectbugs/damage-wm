package wm.damage.core.comp

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8

/**
 * The vertical TRANSLATION between two frames of a canvas window, found by
 * comparing them — so the shell can transmit a scroll the way `CLAUDE.md`'s
 * endless-scroll rule says: **mode 8 { mode 9 rect-copy + mode 3 fill }**, the
 * shift done on the device and only the newly exposed strip on the wire
 * (`DESIGN.md` §5.2/§5.3).
 *
 * 🔴 **Why a DETECTOR and not something each window declares.** A `CanvasView`
 * owns its own damage, and until 2026-09-05 a canvas scroll was
 * `paint(); damage(content)` — every row moved, so the truth diff correctly
 * found the whole content area changed and sent it. MEASURED: a tmux scroll
 * shipped 7.4–10.8 KB, and 6–12 KB flushes measure a **median 1193 ms** on the
 * glasses (n=277 isolated flushes in the production journal, implying ~6.9 KB/s
 * on the wire). A list scroll, which has always declared its shift, ships
 * 72–2,215 B. Detecting it here means every canvas window gets the cheap path
 * without knowing this file exists — the one Adam reported, the ones beside it,
 * and the ones not written yet — and no window can get it wrong by reporting a
 * translation it did not make.
 *
 * **It cannot draw a wrong frame.** `Compositor.declareShift` replays the copy
 * onto the per-lens SHADOWS and then diffs them against the truth of `composed`
 * under the plane map, so a shift that is not real costs bytes to repair and
 * nothing else. This detector goes further and never spends even those: it
 * verifies the block byte-for-byte before returning it, so what it declares is
 * a translation that actually happened.
 *
 * Vertical only, on purpose: the ring scrolls one axis, every canvas here is a
 * column of lines, and a horizontal search would double the cost for a case
 * nothing produces.
 */
object CanvasShift {

    /**
     * The translation from [before] to [after], as `src to dst` in [after]'s
     * coordinates for [Compositor.declareShift], or null when this repaint was
     * not one.
     *
     * [was] is where the region sits in [before] and [now] where the same
     * region sits in [after] — the two surfaces may be different sizes (a
     * region-sized snapshot against the live panel, or two panel-sized frames),
     * so the caller says where to read each. Same size, both grid-legal.
     * [minRun] is the smallest block worth a copy.
     *
     * Every rect returned is grid-legal: x/w come from [now], and the block
     * start, its height and the offset are all even.
     */
    fun detect(before: Gray8, was: Rect, after: Gray8, now: Rect, minRun: Int): Pair<Rect, Rect>? {
        val w = now.w
        val h = now.h
        if (w <= 0 || h <= 0 || minRun <= 0) return null
        if (was.w != w || was.h != h) return null
        if (was.x < 0 || was.y < 0 || was.right > before.w || was.bottom > before.h) return null
        if (now.x < 0 || now.y < 0 || now.right > after.w || now.bottom > after.h) return null
        // Every copy this returns stays on the compositor's 4x2 CELL grid.
        //
        // ⚠ Not a firmware requirement — mode 9 has none. `zlib_glue.c`: "full
        // uint16 coords; the rects may overlap", validated for same-size and
        // in-bounds only, and `rect_copy_4bpp` has a nibble path for an odd
        // left/width. The constraint is OURS: `Compositor.declareShift` moves
        // the per-lens `unknown` marks with the copy through `moveCells`, which
        // is cell-quantised (`s.x / CW`, `s.y / CH`, and CW/CH ARE X_STEP and
        // Y_STEP), so a copy off that grid moves those marks approximately.
        // The consequence is confined to the unknown-cell map — it can only
        // matter after a lost flush — but the grid costs nothing to hold, and
        // the diff scan reasons in the same cells.
        //
        // [h] is in the list for a reason that is easy to miss: the offset
        // sweep below starts at `-h + 2` and steps by 2, so an ODD height makes
        // every candidate offset odd and `region.y + s0 + bestDy` lands on an
        // odd row however carefully `s0` and `len` are snapped. The canvas path
        // always passes the content rect, which is even by construction;
        // exclusive mode passes rects a WINDOW chose, which is why the guard
        // belongs here rather than in the callers.
        if (now.x % Geometry.X_STEP != 0 || w % Geometry.X_STEP != 0 ||
            now.y % Geometry.Y_STEP != 0 || h % Geometry.Y_STEP != 0) return null
        val region = now

        val hb = LongArray(h)
        val ha = LongArray(h)
        // A row of one value matches at EVERY offset, so counting it would let
        // a pane's blank half elect a shift that saves nothing. Flat rows are
        // still copied — they just do not get a vote.
        val flat = BooleanArray(h)
        for (y in 0 until h) {
            hb[y] = rowHash(before.pix, (was.y + y) * before.w + was.x, w)
            val off = (region.y + y) * after.w + region.x
            ha[y] = rowHash(after.pix, off, w)
            flat[y] = uniform(after.pix, off, w)
        }

        fun score(dy: Int): Int {
            var n = 0
            val y0 = maxOf(0, -dy)
            val y1 = minOf(h, h - dy)
            for (y in y0 until y1) if (!flat[y] && ha[y] == hb[y + dy]) n++
            return n
        }

        // "no shift" is the baseline: a frame that did not move, or moved less
        // than it stayed, must not be declared as a translation — the copy and
        // the repairs behind it would cost more than the plain diff.
        val still = score(0)
        var bestDy = 0
        var best = still
        // stepping by Y_STEP, not a literal 2, is what keeps this in lockstep
        // with the grid guard above: the guard's whole job is to make every
        // offset this proposes land on a cell row
        var dy = -h + Geometry.Y_STEP
        while (dy <= h - Geometry.Y_STEP) {
            if (dy != 0) {
                val s = score(dy)
                if (s > best) { best = s; bestDy = dy }
            }
            dy += Geometry.Y_STEP
        }
        if (bestDy == 0) return null

        // the longest CONTIGUOUS block that moved together — a copy is one rect
        val y0 = maxOf(0, -bestDy)
        val y1 = minOf(h, h - bestDy)
        var runStart = -1
        var bestStart = -1
        var bestLen = 0
        for (y in y0..y1) {
            val ok = y < y1 && ha[y] == hb[y + bestDy]
            if (ok) {
                if (runStart < 0) runStart = y
            } else if (runStart >= 0) {
                if (y - runStart > bestLen) { bestLen = y - runStart; bestStart = runStart }
                runStart = -1
            }
        }
        if (bestStart < 0) return null

        var s0 = bestStart
        var len = bestLen
        // the same grid: the block's start and height land on a cell row
        if (s0 % Geometry.Y_STEP != 0) { val d = Geometry.Y_STEP - s0 % Geometry.Y_STEP; s0 += d; len -= d }
        len -= len % Geometry.Y_STEP
        if (len < minRun) return null

        // 🔴 VERIFY, byte for byte. The hashes chose the block; this is what
        // makes the declaration a fact rather than a guess, and it is why a
        // hash collision cannot cost anything: a mismatch simply declines.
        for (y in s0 until s0 + len) {
            val a = (region.y + y) * after.w + region.x
            val b = (was.y + y + bestDy) * before.w + was.x
            for (i in 0 until w) if (after.pix[a + i] != before.pix[b + i]) return null
        }

        val src = Rect(region.x, region.y + s0 + bestDy, w, len)
        val dst = Rect(region.x, region.y + s0, w, len)
        return src to dst
    }

    /** FNV-1a over one row — cheap, and only ever a shortlist: every block it
     *  proposes is verified byte for byte before it is declared. */
    private fun rowHash(pix: ByteArray, off: Int, w: Int): Long {
        var hsh = -0x340d631b7bdddcdbL              // 14695981039346656037
        for (i in 0 until w) {
            hsh = hsh xor (pix[off + i].toLong() and 0xFF)
            hsh *= 0x100000001b3L
        }
        return hsh
    }

    private fun uniform(pix: ByteArray, off: Int, w: Int): Boolean {
        val v = pix[off]
        for (i in 1 until w) if (pix[off + i] != v) return false
        return true
    }
}
