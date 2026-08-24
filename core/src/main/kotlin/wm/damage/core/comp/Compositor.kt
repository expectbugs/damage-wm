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
 * partially updated with a non-stereo delta (§3.4). Per-lens correctness
 * (review round 3) rests on three rules:
 *
 *   ORDER    within a batch, later ops win — so black parallax SEAMS go first
 *            (the background nothing else covers), then stereo deltas FAR to
 *            NEAR (a nearer region's shifted edge legitimately overlaps a
 *            farther one's), then plane-0 repaints, then crossed (near) planes.
 *            A keyframe seeds both lenses at nominal and then follows exactly
 *            that order for every non-flat piece of the plane PARTITION.
 *   BUDGET   every explicit per-lens op costs a fid; the firmware's ring holds
 *            16. Explicit work is queued and drained at most a budget per WIDE
 *            flush, so a keyframe of any plane map completes in a few flushes
 *            instead of failing forever.
 *   PLANES   a plane-map change re-renders the changed regions even when not
 *            one nominal byte moved (the diff cannot see disparity), and queues
 *            seam cleanup for the ghost columns their OLD shifted renders left.
 *
 * Threading: confined to the shell loop — rollback arrives via a loop message,
 * never a transport thread.
 */
class Compositor(val width: Int = Geometry.PANEL_W, val height: Int = Geometry.PANEL_H) {

    /** The frame being composed (what the user should see next). */
    val composed = Gray8(width, height)

    /** What the glasses' shadow holds (nominal space) — the diff base (§5.12). */
    private val sent = Gray8(width, height)

    private val pendingDamage = ArrayList<Rect>()
    private val pendingCopies = ArrayList<PlannedCopy>()

    /** Explicit per-lens ops that bypass the nominal diff: seam-cleanup pairs,
     *  and the stereo repaints + plane-0 reclaims a keyframe could not fit in
     *  one flush. Drained IN ORDER, at most one fid budget per (wide) flush,
     *  ahead of the damage partition (round 3 C1/C6). */
    private val explicitOps = ArrayList<DisplayOp>()

    /** Damage sent even when its nominal bytes equal `sent`: a region whose
     *  PLANE changed re-renders at the new shift although no pixel of
     *  `composed` moved — the diff cannot see disparity (round 3 C2). */
    private val forcedDamage = ArrayList<Rect>()
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
            if (value == field) return
            val old = field
            field = value
            onPlanesChanged(old, value)
        }

    private fun disparityAt(r: Rect): Int = disparityIn(planes, r)

    private fun disparityIn(map: List<PlaneRegion>, r: Rect): Int {
        for (p in map.asReversed()) if (p.rect.contains(r)) return p.disparity
        return 0
    }

    /**
     * The plane map changed. Every region in the symmetric difference — added,
     * removed, or re-planed — re-renders at its new shift (forced past the
     * hash filter: its nominal bytes are typically unchanged), and the ghost
     * columns its OLD shifted render left on each lens are queued for seam
     * cleanup. This is THE transition mechanism: notification focus-step and
     * furl, switcher open/close, the live depth preview — one rule for all
     * (review round 3 C2, and the round-2 #A7 cases it subsumes).
     */
    private fun onPlanesChanged(old: List<PlaneRegion>, new: List<PlaneRegion>) {
        val oldSet = old.toSet()
        val newSet = new.toSet()
        val changed = (old.filter { it !in newSet } + new.filter { it !in oldSet })
            .map { it.rect }.distinct()
        for (r in changed) {
            forcedDamage.add(r.alignOut())
            seamCleanup(r, old)
        }
        if (changed.isNotEmpty()) epoch++
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

    val hasPending: Boolean get() =
        pendingDamage.isNotEmpty() || pendingCopies.isNotEmpty() ||
            explicitOps.isNotEmpty() || forcedDamage.isNotEmpty()

    /**
     * A region is transitioning planes: it was last painted under [old] and
     * re-renders under the CURRENT map. Per lens, the old shifted render leaves
     * ghost columns of width |dOld - dNew| that the new render does not cover —
     * and no nominal rect maps onto them, because they sit ON the parallax
     * seam, whose correct content is black (§3.4). Queue explicit black stereo
     * pairs (review round 2 #A7: expanding the damage rect cannot fix this —
     * the expansion's side slivers render at the surrounding plane's own shift
     * and still miss the seam columns).
     *
     * The rect is split into horizontal bands wherever a plane-region boundary
     * of EITHER map crosses it, and each band takes its own old and new
     * disparity — so a box straddling the forward lens strip does not paint
     * black over live lens pixels, and a content-plane change does not clean
     * a lens band that never moved.
     */
    private fun seamCleanup(r: Rect, old: List<PlaneRegion>) {
        val a = r.alignOut()
        if (a.w <= 0 || a.h <= 0) return
        val cuts = sortedSetOf(a.y, a.bottom)
        for (p in planes + old) {
            if (p.rect.y in (a.y + 1) until a.bottom) cuts.add(p.rect.y)
            if (p.rect.bottom in (a.y + 1) until a.bottom) cuts.add(p.rect.bottom)
        }
        val ys = cuts.toIntArray()
        var queuedAny = false
        for (i in 0 until ys.size - 1) {
            val band = Rect(a.x, ys[i], a.w, ys[i + 1] - ys[i])
            if (band.h <= 0) continue
            val dOld = disparityIn(old, band)
            val dNew = disparityAt(band)
            if (dNew == dOld) continue
            val w = minOf(kotlin.math.abs(dOld - dNew), band.w)
            // For dOld > dNew the L render moved right (was x-dOld, now x-dNew):
            // its ghost is the leading edge of the OLD L box; R is mirrored.
            val (lg, rg) = if (dOld > dNew)
                Rect(band.x - dOld, band.y, w, band.h) to
                    Rect(band.right + dOld - w, band.y, w, band.h)
            else
                Rect(band.right - dOld - w, band.y, w, band.h) to
                    Rect(band.x + dOld, band.y, w, band.h)
            if (lg.x < 0 || lg.right > width || rg.x < 0 || rg.right > width) {
                // a strip would fall off-panel (never expected for the centred
                // shell surfaces) — repaint everything instead, loudly correct
                Log.w("comp", "seamCleanup strip off-panel for $band " +
                    "dOld=$dOld dNew=$dNew — keyframing")
                requestKeyframe()
                return
            }
            explicitOps.add(DisplayOp.StereoPair(lg, rg, black(w, band.h)))
            queuedAny = true
        }
        if (queuedAny) epoch++
    }

    private fun black(w: Int, h: Int): ByteArray =
        Zl.encodeCfw(Pack.rect(Gray8(w, h), Rect(0, 0, w, h)))

    /** The two inner ghost strips a stereo piece leaves after a NOMINAL seed
     *  (keyframe): for d>0 the L render moved left, leaving stale nominal
     *  pixels at the piece's right edge on L; R mirrored. Emitted BEFORE the
     *  stereo deltas, so anything that legitimately renders there (a same- or
     *  farther-plane neighbour's shifted content) paints over the black. */
    private fun innerSeamPair(piece: Rect, d: Int): DisplayOp.StereoPair {
        val w = minOf(kotlin.math.abs(d), piece.w)
        val l = if (d > 0) Rect(piece.right - w, piece.y, w, piece.h) else Rect(piece.x, piece.y, w, piece.h)
        val r = if (d > 0) Rect(piece.x, piece.y, w, piece.h) else Rect(piece.right - w, piece.y, w, piece.h)
        return DisplayOp.StereoPair(l, r, black(w, piece.h))
    }

    /** Far to near: positive (uncrossed, far) first, largest shift first; then
     *  plane 0; then crossed (near) planes, nearest last (§3.4: nearer wins). */
    private fun <T> farToNear(items: List<T>, d: (T) -> Int): List<T> = items.sortedByDescending { d(it) }

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

        // Explicit per-lens work (seams, keyframe remainders) is transient and
        // runs WIDE: depth 1, the full 16-fid budget, drained in order.
        val wideBudget = Geometry.rectBudget(1)
        val taken: List<DisplayOp> = ArrayList(explicitOps.subList(0, minOf(wideBudget, explicitOps.size)))
        repeat(taken.size) { explicitOps.removeAt(0) }
        var wide = taken.isNotEmpty()
        val partBudget = (if (wide) wideBudget else rectBudget) - taken.size

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

        // Merge overlapping damage, drop unchanged rects (§5.6) — except where
        // a FORCED rect (plane change) overlaps: those bytes match by
        // construction and must go out anyway.
        val forced = normalize(forcedDamage)
        forcedDamage.clear()
        var rects = normalize(pendingDamage + forced)
        pendingDamage.clear()
        rects = rects.filter { r -> forced.any { it.overlaps(r) } || !composed.regionEquals(sent, r) }
        if (rects.isNotEmpty() && partBudget <= 0) {
            // no fid left after the explicit work: the damage waits one flush
            // (composed and sent are untouched for it, so nothing is lost)
            pendingDamage.addAll(rects)
            forcedDamage.addAll(forced)
            rects = emptyList()
        }
        if (rects.isEmpty() && copies.isEmpty() && taken.isEmpty()) {
            // nothing changed after all — the copy snapshots are moot
            return null
        }

        val ops = ArrayList<DisplayOp>(copies.size + rects.size + taken.size)
        for (c in copies) ops.add(DisplayOp.Copy(c.src, c.dst, disparityAt(c.dst)))
        ops.addAll(taken)   // seams and explicit repaints FIRST — later ops win

        val damageOps = ArrayList<DisplayOp.Delta>(rects.size)
        if (rects.isNotEmpty()) {
            // Partition, split at plane boundaries (a stereo region is never
            // partially updated with a non-stereo delta, §3.4). Splitting can
            // push the count past the budget; §8.2 #4's answer is a WIDE flush
            // (window drained, depth 1). Groups merged within one plane can
            // geometrically span another plane, so merge and re-split until
            // stable — with a keyframe as the honest last resort.
            var parts = mergeToBudget(rects, partBudget).flatMap { splitByPlanes(it) }
            if (parts.size > partBudget) {
                val bboxParts = splitByPlanes(rects.reduce(Rect::union).alignOut())
                if (bboxParts.size < parts.size) parts = bboxParts
            }
            if (parts.size > partBudget) wide = true
            var attempts = 0
            while (parts.size + taken.size > Geometry.CFW_FID_RING && attempts < 3) {
                val groups = parts.groupBy { disparityAt(it) }
                val per = maxOf(1, (Geometry.CFW_FID_RING - taken.size) / maxOf(1, groups.size))
                parts = groups.flatMap { (_, group) -> mergeToBudget(group, per) }
                    .flatMap { splitByPlanes(it) }
                attempts++
                wide = true
            }
            if (parts.size + taken.size > Geometry.CFW_FID_RING) {
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
            val chosen = if (bboxCost < directCost && bboxPieces.size <= maxOf(partBudget, direct.size)) bboxPieces else direct
            // far to near (§3.4): a nearer region's shifted edge legitimately
            // overlaps a farther one's, and later ops win
            for ((r, payload) in farToNear(chosen) { disparityAt(it.first) })
                damageOps.add(DisplayOp.Delta(r, payload, disparityAt(r)))
        }
        ops.addAll(damageOps)

        // Advance the sent shadow optimistically (§5.12); rollback() undoes.
        // Only the DAMAGE deltas move it: explicit ops are per-lens repaints of
        // nominal content the shadow already holds.
        val touched = damageOps.map { it.box }
        for (r in touched) undo.add(r to snapshot(sent, r))
        for (r in touched) sent.blit(composed, r, r.x, r.y)

        return Assembled(ops, epoch, undo, copies, wide = wide, explicit = taken)
    }

    /**
     * Cold start / divergence / resize: one mode-6 nominal keyframe (seeds BOTH
     * lenses at nominal) followed by the per-lens plan for every non-flat piece
     * of the plane PARTITION (review round 1: emitting whole regions repainted
     * nested plane-0 areas at the wrong depth), in later-wins order:
     *
     *   1. black seam pairs at every stereo piece's inner ghost strips — the
     *      background; anything that legitimately renders there (a same- or
     *      farther-plane neighbour's shifted content) paints over it
     *   2. far planes, farthest first
     *   3. plane-0 pieces beside a far piece, taking back the |d| columns its
     *      shifted render spilled over them (the nominal seed painted them
     *      FIRST) — §3.4: the nearer plane wins
     *   4. crossed (near) planes, nearest last
     *
     * Round 3 C1: the plan can exceed the 16-fid ring for a busy plane map;
     * what does not fit this WIDE flush is queued as explicit work and drains
     * over the following flushes, in the same order.
     */
    private fun assembleKeyframe(): Assembled {
        pendingDamage.clear()
        forcedDamage.clear()
        pendingCopies.clear()
        explicitOps.clear()       // the keyframe's own plan supersedes them
        needsKeyframe = false
        val full = Rect(0, 0, width, height)

        val pieces = splitByPlanes(full)
        val far = farToNear(pieces.filter { disparityAt(it) > 0 }) { disparityAt(it) }
        val near = farToNear(pieces.filter { disparityAt(it) < 0 }) { disparityAt(it) }
        val plan = ArrayList<DisplayOp>()
        for (piece in far + near) plan.add(innerSeamPair(piece, disparityAt(piece)))
        for (piece in far) plan.add(DisplayOp.Delta(piece, compress(piece), disparityAt(piece)))
        for (piece in pieces) {
            if (disparityAt(piece) != 0) continue
            val besideFar = far.any { s ->
                s.y < piece.bottom && piece.y < s.bottom && (s.right == piece.x || piece.right == s.x)
            }
            if (besideFar) plan.add(DisplayOp.Delta(piece, compress(piece), 0))
        }
        for (piece in near) plan.add(DisplayOp.Delta(piece, compress(piece), disparityAt(piece)))

        val budget = Geometry.rectBudget(1)     // the mode-6 itself costs no fid
        val now = plan.take(budget)
        explicitOps.addAll(plan.drop(budget))
        if (explicitOps.isNotEmpty()) {
            Log.i("comp", "keyframe plan is ${plan.size} per-lens ops — " +
                "${explicitOps.size} queued for the following flushes")
        }

        val ops = ArrayList<DisplayOp>(1 + now.size)
        ops.add(DisplayOp.Keyframe(compress(full)))
        ops.addAll(now)
        val undo = listOf(full to snapshot(sent, full))
        sent.blit(composed, full, 0, 0)
        return Assembled(ops, epoch, undo, emptyList(), keyframe = true, wide = true, explicit = now)
    }

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
        explicitOps.addAll(0, a.explicit)   // the per-lens work is still owed
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
        /** Explicit per-lens ops riding this flush — re-queued on rollback. */
        val explicit: List<DisplayOp> = emptyList(),
    )
}
