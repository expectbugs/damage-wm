package wm.damage.core.comp

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
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
 *         send and rolling back on failure — copies included
 *   §5.13 backpressure coalescing — pending damage merges, never queues
 *   §5.14 damage epochs
 *
 * Stereo: coordinates are NOMINAL; a plane map assigns each region a disparity
 * and a flush splits damage at plane boundaries so a stereo region is never
 * partially updated with a non-stereo delta (§3.4). Keyframes emit the plane
 * PARTITION (not per-region rects) plus parallax-seam cleanup strips, because a
 * mode-6 keyframe has no stereo form and seeds both lenses at nominal.
 *
 * Threading: confined to the shell loop. Every mutator asserts nothing here is
 * called concurrently — rollback arrives via a loop message, never a transport
 * thread.
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

    /** True until a keyframe is ASSEMBLED; set again by rollback or request.
     *  Assembly-time clearing is what prevents duplicate keyframes piling up
     *  while one is in flight, and lets a request DURING flight stand. */
    var needsKeyframe = true
        private set

    data class PlannedCopy(val src: Rect, val dst: Rect)

    /** Ordered plane regions, top-most last: a damage rect takes the disparity
     *  of the LAST region containing it. Default: everything at plane 0. */
    data class PlaneRegion(val rect: Rect, val disparity: Int)

    var planes: List<PlaneRegion> = emptyList()
        set(value) {
            for (p in value) {
                val errs = Geometry.checkRect(p.rect, "plane region") +
                    if (p.disparity % Geometry.X_STEP != 0)
                        listOf("GEO006 plane disparity ${p.disparity} off the 4 px ladder") else emptyList()
                if (errs.isNotEmpty()) throw LintError(errs.joinToString("; "))
            }
            field = value
        }

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
     * Declare a translation (§5.2/§5.3): pixels now at [dst] came from [src]
     * (same size). The caller has ALREADY repainted `composed`; this records
     * the cheap transmission path. Damage already pending inside [src] moves
     * with the content — otherwise a damage-then-shift frame would transmit
     * the wrong rows and lose the change entirely.
     */
    fun declareShift(src: Rect, dst: Rect) {
        if (src.w != dst.w || src.h != dst.h) {
            Log.e("comp", "declareShift size mismatch $src -> $dst — falling back to damage")
            damage(src.union(dst))
            return
        }
        val dx = dst.x - src.x
        val dy = dst.y - src.y
        for (i in pendingDamage.indices) {
            val d = pendingDamage[i]
            if (src.contains(d)) {
                pendingDamage[i] = d.translate(dx, dy)
            } else if (d.overlaps(src)) {
                pendingDamage[i] = d.union(d.translate(dx, dy)).alignOut()
            }
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

        // Snapshot the copy destinations BEFORE replaying, so a failed flush can
        // roll the diff base back (§5.12 — review round 1: without this, a lost
        // scroll flush left `sent` permanently ahead and hash-before-send then
        // suppressed the repair forever).
        val undo = ArrayList<Pair<Rect, ByteArray>>(copies.size + 4)
        for (c in copies) undo.add(c.dst to snapshot(sent, c.dst))

        // Replay copies on `sent` so the hash-before-send diff below is honest.
        for (c in copies) {
            val tmp = Gray8(c.src.w, c.src.h)
            tmp.blit(sent, c.src, 0, 0)
            sent.blit(tmp, Rect(0, 0, c.src.w, c.src.h), c.dst.x, c.dst.y)
        }

        // Merge overlapping damage, drop unchanged rects (§5.6).
        var rects = normalize(pendingDamage)
        pendingDamage.clear()
        rects = rects.filter { !composed.regionEquals(sent, it) }
        if (rects.isEmpty() && copies.isEmpty()) {
            // nothing changed after all — the copy snapshots are moot
            return null
        }

        val ops = ArrayList<DisplayOp>(copies.size + rects.size)
        for (c in copies) ops.add(DisplayOp.Copy(c.src, c.dst, disparityAt(c.dst)))

        var wide = false
        if (rects.isNotEmpty()) {
            // Partition, split at plane boundaries (a stereo region is never
            // partially updated with a non-stereo delta, §3.4). Splitting can
            // push the count past the budget; §8.2 #4's answer is a WIDE flush
            // (window drained, depth 1). Groups merged within one plane can
            // geometrically span another plane, so merge and re-split until
            // stable — with a keyframe as the honest last resort.
            var parts = mergeToBudget(rects, rectBudget).flatMap { splitByPlanes(it) }
            if (parts.size > rectBudget) {
                val bboxParts = splitByPlanes(rects.reduce(Rect::union).alignOut())
                if (bboxParts.size < parts.size) parts = bboxParts
            }
            if (parts.size > rectBudget) wide = true
            var attempts = 0
            while (parts.size > Geometry.CFW_FID_RING && attempts < 3) {
                val groups = parts.groupBy { disparityAt(it) }
                val per = maxOf(1, Geometry.CFW_FID_RING / groups.size)
                parts = groups.flatMap { (_, group) -> mergeToBudget(group, per) }
                    .flatMap { splitByPlanes(it) }
                attempts++
                wide = true
            }
            if (parts.size > Geometry.CFW_FID_RING) {
                // pathological plane/damage interaction: a keyframe repaints
                // everything correctly in one wide flush
                Log.w("comp", "damage partition would need ${parts.size} rects — keyframing instead")
                pendingDamage.clear()
                needsKeyframe = true
                return assembleKeyframe()
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
        for (r in touched) undo.add(r to snapshot(sent, r))
        for (r in touched) sent.blit(composed, r, r.x, r.y)

        return Assembled(ops, epoch, undo, copies, wide = wide)
    }

    /**
     * Cold start / divergence / resize: one mode-6 nominal keyframe plus, in
     * the SAME atomic batch, a stereo delta for every non-zero piece of the
     * plane PARTITION (review round 1: emitting whole regions repainted nested
     * plane-0 areas at the wrong depth), plus black parallax-seam strips at
     * stereo piece edges not continued by an equal-disparity neighbour.
     */
    private fun assembleKeyframe(): Assembled {
        pendingDamage.clear()
        pendingCopies.clear()
        needsKeyframe = false
        val full = Rect(0, 0, width, height)
        val ops = ArrayList<DisplayOp>(4)
        ops.add(DisplayOp.Keyframe(compress(full)))

        val pieces = splitByPlanes(full)
        val stereo = pieces.filter { disparityAt(it) != 0 }
        for (piece in stereo) {
            ops.add(DisplayOp.Delta(piece, compress(piece), disparityAt(piece)))
        }
        for (piece in stereo) {
            val d = disparityAt(piece)
            val mag = kotlin.math.abs(d)
            // seam strips: skip an edge continued by a piece at the SAME
            // disparity (their lens coverage abuts with no gap — a black strip
            // there would erase valid shifted content)
            val rightCont = pieces.any {
                it.x == piece.right && disparityAt(it) == d &&
                    it.y < piece.bottom && piece.y < it.bottom
            }
            val leftCont = pieces.any {
                it.right == piece.x && disparityAt(it) == d &&
                    it.y < piece.bottom && piece.y < it.bottom
            }
            val black = Zl.encodeCfw(Pack.rect(Gray8(mag, piece.h), Rect(0, 0, mag, piece.h)))
            if (!rightCont) {
                val (l, r) = if (d > 0)
                    Rect(piece.right - mag, piece.y, mag, piece.h) to Rect(piece.right, piece.y, mag, piece.h)
                else
                    Rect(piece.right, piece.y, mag, piece.h) to Rect(piece.right - mag, piece.y, mag, piece.h)
                if (inPanel(l) && inPanel(r)) ops.add(DisplayOp.StereoPair(l, r, black))
            }
            if (!leftCont) {
                val (l, r) = if (d > 0)
                    Rect(piece.x - mag, piece.y, mag, piece.h) to Rect(piece.x, piece.y, mag, piece.h)
                else
                    Rect(piece.x, piece.y, mag, piece.h) to Rect(piece.x - mag, piece.y, mag, piece.h)
                if (inPanel(l) && inPanel(r)) ops.add(DisplayOp.StereoPair(l, r, black))
            }
        }
        val undo = listOf(full to snapshot(sent, full))
        sent.blit(composed, full, 0, 0)
        return Assembled(ops, epoch, undo, emptyList(), keyframe = true, wide = true)
    }

    private fun inPanel(r: Rect): Boolean =
        r.x >= 0 && r.y >= 0 && r.right <= width && r.bottom <= height

    /** The flush [a] was rejected or lost: restore the diff base — copies AND
     *  deltas — and re-damage every affected region, so the NEXT flush
     *  recomputes with fresh fids (§8.2 rule 1 — never retransmit). */
    fun rollback(a: Assembled) {
        for ((r, bytes) in a.undo.asReversed()) restore(sent, r, bytes)
        for ((r, _) in a.undo) pendingDamage.add(r)
        for (c in a.copies) {
            pendingDamage.add(c.src)
            pendingDamage.add(c.dst)
        }
        if (a.keyframe) needsKeyframe = true
        epoch++
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

    /** Adjacent-on-the-grid rects merge too — aligned rects that touch share
     *  run structure. */
    private fun touches(a: Rect, b: Rect): Boolean =
        !(a.right < b.x || b.right < a.x || a.bottom < b.y || b.bottom < a.y)

    private fun mergeToBudget(rects: List<Rect>, budget: Int): List<Rect> {
        val out = rects.toMutableList()
        while (out.size > budget) {
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
        /** Rects past the pipelined budget (or a keyframe): the transport runs
         *  this flush with the window drained (depth 1) — §8.2 #4. */
        val wide: Boolean = false,
    )
}
