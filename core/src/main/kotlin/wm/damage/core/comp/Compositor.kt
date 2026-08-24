package wm.damage.core.comp

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.transport.DisplayOp
import wm.damage.core.util.Log

/**
 * Damage tracking with a single mode-8 flush per frame — the architecture the
 * firmware is asking for, and the project's name (overview.md §4). The engine
 * rules are DESIGN.md §5, implemented here:
 *
 *   §5.1  price damage partitions by actually compressing them
 *   §5.2  mode 9 is a general primitive — declared translations become copies
 *   §5.4  occlusion is handled by paint order (later ops win in a mode-8 batch)
 *   §5.6  hash before send — a rect whose bytes did not change is dropped
 *   §5.12 two shadows: damage is computed against what was SENT, advancing on
 *         send and rolling back on failure
 *   §5.13 backpressure coalescing — pending damage merges, never queues
 *   §5.14 damage epochs
 *
 * Stereo: coordinates are NOMINAL; a plane map assigns each region a disparity
 * and a flush splits damage at plane boundaries so a stereo region is never
 * partially updated with a non-stereo delta (§3.4).
 */
class Compositor(val width: Int = Geometry.PANEL_W, val height: Int = Geometry.PANEL_H) {

    /** The frame being composed (what the user should see next). */
    val composed = Gray8(width, height)

    /** What the glasses' shadow holds (nominal space) — the diff base (§5.12). */
    private val sent = Gray8(width, height)

    private val pendingDamage = ArrayList<Rect>()
    private val pendingCopies = ArrayList<PlannedCopy>()
    var epoch: Long = 0
        private set

    /** True until the first keyframe reaches the glasses (or after divergence). */
    var needsKeyframe = true
        private set

    data class PlannedCopy(val src: Rect, val dst: Rect)

    /** Ordered plane regions, top-most last: a damage rect takes the disparity
     *  of the LAST region containing it. Default: everything at plane 0. */
    data class PlaneRegion(val rect: Rect, val disparity: Int)

    var planes: List<PlaneRegion> = emptyList()

    private fun disparityAt(r: Rect): Int {
        for (p in planes.asReversed()) if (p.rect.contains(r)) return p.disparity
        return 0
    }

    /** Split [r] so every piece lies wholly inside one plane region (or none). */
    private fun splitByPlanes(r: Rect): List<Rect> {
        var pieces = listOf(r)
        for (p in planes) {
            val next = ArrayList<Rect>(pieces.size + 3)
            for (piece in pieces) {
                val inter = piece.intersect(p.rect)
                if (inter == null || inter == piece) { next.add(piece); continue }
                // split into the intersection plus up to 4 aligned remainders
                next.add(inter)
                if (piece.y < inter.y) next.add(Rect(piece.x, piece.y, piece.w, inter.y - piece.y))
                if (inter.bottom < piece.bottom) next.add(Rect(piece.x, inter.bottom, piece.w, piece.bottom - inter.bottom))
                if (piece.x < inter.x) next.add(Rect(piece.x, inter.y, inter.x - piece.x, inter.h))
                if (inter.right < piece.right) next.add(Rect(inter.right, inter.y, piece.right - inter.right, inter.h))
            }
            pieces = next
        }
        return pieces.map { it.alignOut() }.filter { it.w > 0 && it.h > 0 }
    }

    // ------------------------------------------------------------------ input
    /** Mark a nominal-space rect as needing transmission. */
    fun damage(r: Rect) {
        if (r.w <= 0 || r.h <= 0) return
        val a = r.alignOut()
        if (a.w <= 0 || a.h <= 0) return
        pendingDamage.add(a)
        epoch++
    }

    fun damageAll() = damage(Rect(0, 0, width, height))

    /**
     * Declare a translation (§5.2/§5.3): [region] content moved so that the
     * pixels now at [dst] came from [src] (same size). The caller has ALREADY
     * repainted `composed`; this records the cheap transmission path. The sent
     * shadow is updated at flush time by replaying the copy.
     */
    fun declareShift(src: Rect, dst: Rect) {
        if (src.w != dst.w || src.h != dst.h) {
            Log.e("comp", "declareShift size mismatch $src -> $dst — falling back to damage")
            damage(src.union(dst))
            return
        }
        pendingCopies.add(PlannedCopy(src, dst))
        epoch++
    }

    val hasPending: Boolean get() = pendingDamage.isNotEmpty() || pendingCopies.isNotEmpty()

    fun requestKeyframe() {
        needsKeyframe = true
        epoch++
    }

    // ------------------------------------------------------------------ flush
    /**
     * Assemble everything pending into one atomic op list (§4's whole thesis:
     * the ack floor is per MESSAGE, so all damage ships in ONE mode-8 batch).
     * Returns null when nothing actually changed. [rectBudget] is the mode-3
     * budget for the current pipeline depth (§8.2).
     */
    fun assembleFlush(rectBudget: Int): Assembled? {
        if (needsKeyframe) return assembleKeyframe()
        if (!hasPending) return null

        val copies = ArrayList(pendingCopies)
        pendingCopies.clear()

        // Copies invalidate the diff base for their destination: replay them on
        // `sent` so the subsequent hash-before-send diff is honest.
        for (c in copies) {
            val tmp = Gray8(c.src.w, c.src.h)
            tmp.blit(sent, c.src, 0, 0)
            sent.blit(tmp, Rect(0, 0, c.src.w, c.src.h), c.dst.x, c.dst.y)
        }

        // Merge overlapping damage, drop unchanged rects (§5.6).
        var rects = normalize(pendingDamage)
        pendingDamage.clear()
        rects = rects.filter { !composed.regionEquals(sent, it) }
        if (rects.isEmpty() && copies.isEmpty()) return null

        // Partition pricing (§5.1): candidates are the merged set (merged down
        // to the budget) and the single bounding box; the real deflate decides.
        val ops = ArrayList<DisplayOp>(copies.size + rects.size)
        for (c in copies) ops.add(DisplayOp.Copy(c.src, c.dst, disparityAt(c.dst)))

        var wide = false
        if (rects.isNotEmpty()) {
            // Partition candidates, split at plane boundaries (a stereo region is
            // never partially updated with a non-stereo delta, §3.4). Splitting
            // can push the count past the budget; §8.2 #4's answer is to trade
            // pipeline depth for rects — the transport runs a WIDE flush with the
            // window drained — rather than merging across planes, which is
            // impossible, or failing, which was the first selfcheck's bug.
            var parts = mergeToBudget(rects, rectBudget).flatMap { splitByPlanes(it) }
            if (parts.size > rectBudget) {
                val bboxParts = splitByPlanes(rects.reduce(Rect::union).alignOut())
                if (bboxParts.size < parts.size) parts = bboxParts
            }
            if (parts.size > rectBudget) wide = true
            if (parts.size > Geometry.CFW_FID_RING) {
                // even at window 1 the fid ring bounds one flush: merge within
                // each plane group (cross-plane merges are illegal) until the
                // total fits
                val groups = parts.groupBy { disparityAt(it) }
                val per = maxOf(1, Geometry.CFW_FID_RING / groups.size)
                parts = groups.flatMap { (_, group) -> mergeToBudget(group, per) }
                wide = true
            }
            val direct = parts.map { it to compress(it) }
            val bbox = parts.reduce(Rect::union).alignOut()
            val bboxPieces = splitByPlanes(bbox).map { it to compress(it) }
            val directCost = direct.sumOf { it.second.size + 15 }
            val bboxCost = bboxPieces.sumOf { it.second.size + 15 }
            val chosen = if (bboxCost < directCost && bboxPieces.size <= maxOf(rectBudget, direct.size)) bboxPieces else direct
            for ((r, payload) in chosen) ops.add(DisplayOp.Delta(r, payload, disparityAt(r)))
        }

        // Advance the sent shadow optimistically (§5.12); rollback() undoes.
        val touched = ops.mapNotNull { (it as? DisplayOp.Delta)?.box }
        val undo = touched.map { it to snapshot(sent, it) }
        for (r in touched) sent.blit(composed, r, r.x, r.y)

        return Assembled(ops, epoch, undo, copies, wide = wide)
    }

    private fun assembleKeyframe(): Assembled {
        pendingDamage.clear()
        pendingCopies.clear()
        val full = Rect(0, 0, width, height)
        val ops = ArrayList<DisplayOp>(2)
        ops.add(DisplayOp.Keyframe(compress(full)))
        // A mode-6 keyframe has no stereo form: it seeds both lenses identically
        // at the nominal position. Each stereo region then needs, in the SAME
        // atomic batch: (a) its stereo delta, and (b) the vacated-strip cleanup —
        // the shifted repaint leaves a d-wide ghost of nominal content at the
        // region's inner edge on each lens (left lens at the right edge, right
        // lens at the left), which the snapshot harness caught as doubled rails.
        for (p in planes) if (p.disparity != 0) {
            val r = p.rect.alignOut()
            ops.add(DisplayOp.Delta(r, compress(r), p.disparity))
            val d = kotlin.math.abs(p.disparity)
            if (d > 0) {
                val strip = Gray8(d, r.h)
                val black = Zl.encodeCfw(Pack.rect(strip, Rect(0, 0, d, r.h)))
                val lGhost: Rect
                val rGhost: Rect
                if (p.disparity > 0) {      // far: L drew at x-d, ghost at its right
                    lGhost = Rect(r.right - d, r.y, d, r.h)
                    rGhost = Rect(r.x, r.y, d, r.h)
                } else {                    // crossed: mirrored
                    lGhost = Rect(r.x, r.y, d, r.h)
                    rGhost = Rect(r.right - d, r.y, d, r.h)
                }
                ops.add(DisplayOp.StereoPair(lGhost, rGhost, black))
            }
        }
        val undo = listOf(full to snapshot(sent, full))
        sent.blit(composed, full, 0, 0)
        return Assembled(ops, epoch, undo, emptyList(), keyframe = true)
    }

    /** The flush [a] was rejected or lost: restore the diff base and re-damage,
     *  so the NEXT flush recomputes with fresh fids (§8.2 rule 1 — never
     *  retransmit). */
    fun rollback(a: Assembled) {
        for ((r, bytes) in a.undo) restore(sent, r, bytes)
        for ((r, _) in a.undo) pendingDamage.add(r)
        if (a.keyframe) needsKeyframe = true
        epoch++
    }

    fun keyframeDelivered(a: Assembled) {
        if (a.keyframe) needsKeyframe = false
    }

    // ------------------------------------------------------------------ helpers
    private fun compress(r: Rect): ByteArray = Zl.encodeCfw(Pack.rect(composed, r))

    private fun normalize(rects: List<Rect>): List<Rect> {
        val out = ArrayList<Rect>()
        for (r in rects) {
            var cur = r
            var merged = true
            while (merged) {
                merged = false
                val it = out.iterator()
                while (it.hasNext()) {
                    val o = it.next()
                    if (o.overlaps(cur) || touches(o, cur)) {
                        cur = cur.union(o)
                        it.remove()
                        merged = true
                    }
                }
            }
            out.add(cur)
        }
        return out
    }

    /** Adjacent-on-the-grid rects merge too — a 1 px gap between aligned rects
     *  cannot exist (alignment is 4/2), so touching means same run region. */
    private fun touches(a: Rect, b: Rect): Boolean =
        !(a.right < b.x || b.right < a.x || a.bottom < b.y || b.bottom < a.y)

    private fun mergeToBudget(rects: List<Rect>, budget: Int): List<Rect> {
        val out = rects.toMutableList()
        while (out.size > budget) {
            // merge the pair whose union grows least
            var bi = 0; var bj = 1; var best = Long.MAX_VALUE
            for (i in out.indices) for (j in i + 1 until out.size) {
                val grow = out[i].union(out[j]).area.toLong() - out[i].area - out[j].area
                if (grow < best) { best = grow; bi = i; bj = j }
            }
            val u = out[bi].union(out[bj])
            out.removeAt(bj); out.removeAt(bi)
            out.add(u)
        }
        return out
    }

    private fun snapshot(s: Gray8, r: Rect): ByteArray {
        val out = ByteArray(r.w * r.h)
        var o = 0
        for (y in r.y until r.bottom) {
            System.arraycopy(s.pix, y * s.w + r.x, out, o, r.w)
            o += r.w
        }
        return out
    }

    private fun restore(s: Gray8, r: Rect, bytes: ByteArray) {
        var o = 0
        for (y in r.y until r.bottom) {
            System.arraycopy(bytes, o, s.pix, y * s.w + r.x, r.w)
            o += r.w
        }
    }

    data class Assembled(
        val ops: List<DisplayOp>,
        val epoch: Long,
        val undo: List<Pair<Rect, ByteArray>>,
        val copies: List<PlannedCopy>,
        val keyframe: Boolean = false,
        /** More mode-3 rects than the pipelined budget: the transport must run
         *  this flush with the window drained (depth 1) — §8.2 #4. */
        val wide: Boolean = false,
    )
}
