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
 *   §5.1  partition damage toward the rect budget, priced by compressing
 *   §5.2  mode 9 is a general primitive — declared translations become copies
 *   §5.4  occlusion is handled by paint order (later ops win in a mode-8 batch)
 *   §5.6  hash before send — nothing whose bytes did not change is sent
 *   §5.12 shadows: damage is computed against what was SENT; a lost flush
 *         marks what it touched UNKNOWN, so it is transmitted again from the
 *         truth — never "restored" from a snapshot the glass may not hold
 *   §5.13 backpressure coalescing — pending damage merges, never queues
 *   §5.14 damage epochs
 *
 * Stereo (review rounds 4–6): the compositor reasons PER LENS. It keeps an
 * expected shadow of what each lens shows, renders the per-lens TRUTH of the
 * nominal frame under the plane map (the nominal frame is the transparent
 * base every shift may spill over — §3.3's insets; each region vacates its
 * nominal area to black, the seam; region pieces render at their shift far
 * to near, the nearest wins), diffs shadow against truth on the 4×2 damage
 * grid, merges the differences toward the fid budget — never across a
 * different plane, so a merged delta cannot paint another region's pixels
 * at the wrong shift — and emits whatever closes the gap: nominal deltas at
 * their disparity, black stereo pairs for whole seam strips. Each planned op
 * is applied to the shadows as it is planned, so an op's effect on the OTHER
 * lens is seen and repaired in the same flush, in later-wins order. Whatever
 * the fid budget cannot carry stays dirty and goes out next flush. Plane
 * changes, seam cleanup, keyframe follow-ups and reclaims are not special
 * cases — they are differences between shadow and truth.
 *
 * Threading: confined to the shell loop — rollback arrives via a loop message,
 * never a transport thread.
 */
class Compositor(val width: Int = Geometry.PANEL_W, val height: Int = Geometry.PANEL_H) {

    /** The frame being composed (what the user should see next), NOMINAL. */
    val composed = Gray8(width, height)

    /** What each lens is believed to show (the diff base, §5.12). */
    private val shadowL = Gray8(width, height)
    private val shadowR = Gray8(width, height)

    /** Per-lens cells whose glass content is UNKNOWN (a flush that touched
     *  them was lost while others may have landed): always dirty until an op
     *  paints them again. */
    private val cellsW = width / CW
    private val cellsH = height / CH
    private val unknownL = BooleanArray(cellsW * cellsH)
    private val unknownR = BooleanArray(cellsW * cellsH)

    /** Per-lens truth of `composed` under `planes`, and per-pixel owners. */
    private val truthL = Gray8(width, height)
    private val truthR = Gray8(width, height)
    private val ownerL = ShortArray(width * height)
    private val ownerR = ShortArray(width * height)
    private class Piece(val rect: Rect, val d: Int)
    private var pieces: List<Piece> = emptyList()

    /** Nominal-space HINTS of where the truth may have changed. */
    private val pendingDamage = ArrayList<Rect>()
    private val pendingCopies = ArrayList<PlannedCopy>()
    /** Dirt a budget-limited flush left behind (nominal-space bbox); the
     *  next flush continues at the WIDE aim rather than re-partitioning
     *  from scratch (round 6: restarting at the pipelined aim re-created the
     *  same over-merged plan every flush). */
    private var residual: Rect? = null

    /** Per-lens copies applied to the shadows, most recent last — a later
     *  rollback translates a lost flush's touched rects through them (round
     *  6: the pixels a lost delta failed to update have since MOVED). */
    private class AppliedCopy(val epoch: Long, val left: Boolean, val src: Rect, val dst: Rect)
    private val appliedCopies = ArrayDeque<AppliedCopy>()

    var epoch: Long = 0
        private set

    /** The whole batch must fit the firmware's bmp_max (Geometry.MODE8_MAX)
     *  with the batch header and a margin for the emitter's framing. A var so
     *  a test can lower it and drive the byte-refusal path deterministically. */
    internal var batchMax: Int = Geometry.MODE8_MAX - 1024

    /** True until a keyframe is ASSEMBLED; set again by rollback or request.
     *  Assembly-time clearing is what prevents duplicate keyframes piling up
     *  while one is in flight, and lets a request DURING flight stand. */
    var needsKeyframe = true
        private set

    data class PlannedCopy(val src: Rect, val dst: Rect)

    /** Ordered plane regions, top-most last: a nominal pixel belongs to the
     *  LAST region containing it. Regions vacate their nominal area (a seam is
     *  black) and render at their shift; the remainder is the transparent
     *  plane-0 base every shift may spill over (§3.3). */
    data class PlaneRegion(val rect: Rect, val disparity: Int)

    var planes: List<PlaneRegion> = emptyList()
        set(value) {
            for (p in value) {
                val errs = ArrayList(Geometry.checkRect(p.rect, "plane region"))
                if (p.disparity % Geometry.X_STEP != 0)
                    errs += "GEO006 plane disparity ${p.disparity} off the 4 px ladder"
                // both lenses render the region shifted by |d| — a region
                // closer than that to a panel edge would emit boxes the
                // firmware rejects in SILENCE, forever (round 5)
                val m = kotlin.math.abs(p.disparity)
                if (p.rect.x - m < 0 || p.rect.right + m > width)
                    errs += "GEO002 plane region ${p.rect} at disparity ${p.disparity} shifts off the panel"
                if (errs.isNotEmpty()) throw LintError(errs.joinToString("; "))
            }
            if (value == field) return
            val old = field
            field = value
            // every region that entered, left or moved re-renders somewhere
            // else per lens: hint their areas, the truth diff does the rest
            val oldSet = old.toSet()
            val newSet = value.toSet()
            for (p in old) if (p !in newSet) hint(p.rect)
            for (p in value) if (p !in oldSet) hint(p.rect)
        }

    private fun disparityAt(r: Rect): Int {
        for (p in planes.asReversed()) if (p.rect.contains(r)) return p.disparity
        return 0
    }

    private fun inRegion(r: Rect): Boolean = planes.any { it.rect.contains(r) }

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
    /** Mark a nominal-space rect as possibly changed. */
    fun damage(r: Rect) {
        if (r.w <= 0 || r.h <= 0) return
        hint(r)
        epoch++
    }

    private fun hint(r: Rect) {
        val a = r.alignOut()
        if (a.w > 0 && a.h > 0) pendingDamage.add(a)
    }

    fun damageAll() = damage(Rect(0, 0, width, height))

    /**
     * Declare a translation (§5.2/§5.3): pixels now at [dst] came from [src]
     * (same size). The caller has ALREADY repainted `composed`; this records
     * the cheap transmission path. Damage already pending inside [src] moves
     * with the content — otherwise a damage-then-shift frame would transmit
     * the wrong rows and lose the change entirely. A shift that crosses plane
     * pieces has no single per-lens copy (round 5): it is plain damage.
     */
    fun declareShift(src: Rect, dst: Rect) {
        if (src.w != dst.w || src.h != dst.h) {
            Log.e("comp", "declareShift size mismatch $src -> $dst — falling back to damage")
            damage(src.union(dst))
            return
        }
        if (splitByPlanes(src).size != 1 || splitByPlanes(dst).size != 1 ||
            disparityAt(src.alignOut()) != disparityAt(dst.alignOut())) {
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
        pendingDamage.isNotEmpty() || pendingCopies.isNotEmpty() || residual != null

    fun requestKeyframe() {
        needsKeyframe = true
        epoch++
    }

    // ------------------------------------------------------------------ flush
    /**
     * Assemble everything pending into one atomic op list (§4's whole thesis:
     * the ack floor is per MESSAGE, so all damage ships in ONE mode-8 batch).
     * Returns null when nothing actually changed. [rectBudget] is the mode-3
     * budget for the current pipeline depth (§8.2) — the partition aims at
     * it; a flush that needs more runs WIDE (window drained, 16 fids), and
     * anything past even that stays dirty for the next flush.
     */
    fun assembleFlush(rectBudget: Int): Assembled? {
        val keyframe = needsKeyframe
        val ops = ArrayList<DisplayOp>()
        val touched = ArrayList<Touched>()
        val full = Rect(0, 0, width, height)

        val copies = ArrayList(pendingCopies)
        pendingCopies.clear()
        val hints = ArrayList(pendingDamage)
        pendingDamage.clear()
        val continuing = residual != null
        residual?.let { hints.add(it) }
        residual = null

        var bareKeyframe = false
        if (keyframe) {
            needsKeyframe = false
            // the mode-6 seeds BOTH lenses at nominal; everything stereo is
            // then a difference between that and the truth
            System.arraycopy(composed.pix, 0, shadowL.pix, 0, composed.pix.size)
            System.arraycopy(composed.pix, 0, shadowR.pix, 0, composed.pix.size)
            java.util.Arrays.fill(unknownL, false)
            java.util.Arrays.fill(unknownR, false)
            touched.add(Touched(true, full)); touched.add(Touched(false, full))
            val payload = compress(full)
            ops.add(DisplayOp.Keyframe(payload))
            // Inside a mode-8 batch every sub-message is length-prefixed with
            // 16 bits (zlib_glue.c: seglen = rd16); only a BARE mode-6 may
            // run to bmp_max. A keyframe past the batch cap ships alone and
            // its follow-ups wait one flush (round 6: bundling it looped in
            // failure forever).
            if (payload.size > MAX_SUB) {
                bareKeyframe = true
                if (payload.size > Geometry.MODE8_MAX)
                    Log.e("comp", "keyframe ${payload.size} B exceeds even the bare cap ${Geometry.MODE8_MAX} — the transport will refuse it loudly")
            }
        } else {
            if (hints.isEmpty() && copies.isEmpty()) return null
            // Replay declared copies on the shadows per lens (§5.12) — the
            // unknown marks move with the pixels.
            for (c in copies) {
                val d = disparityAt(c.dst.alignOut())
                for (left in booleanArrayOf(true, false)) {
                    val s = c.src.translate(if (left) -d else d, 0)
                    val t = c.dst.translate(if (left) -d else d, 0)
                    copyWithin(if (left) shadowL else shadowR, s, t)
                    moveCells(if (left) unknownL else unknownR, s, t)
                    touched.add(Touched(left, t.clip()))
                    appliedCopies.addLast(AppliedCopy(epoch, left, s.clip(), t.clip()))
                }
                while (appliedCopies.size > COPY_HISTORY) appliedCopies.removeFirst()
                ops.add(DisplayOp.Copy(c.src, c.dst, d))
                hints.add(c.src); hints.add(c.dst)
            }
        }

        // The area the diff scans: every hint, widened by the maximum shift
        // so a lens-space consequence of a nominal change is inside it.
        val area: List<Rect> = if (keyframe) listOf(full)
        else normalize(hints.map { widen(it) })

        var fids = 0
        // the batch's byte budget (round 7): every sub-message counts toward
        // the firmware's bmp_max, not only against its own 16-bit length
        var bytes = ops.sumOf { (it as? DisplayOp.Keyframe)?.payload?.size ?: 0 } + BATCH_HEADER
        var dirtyLeft = false
        if (bareKeyframe) {
            dirtyLeft = true
        } else {
            renderTruth()
            val budget = Geometry.rectBudget(1)     // the wide budget
            var iterations = 0
            while (iterations < MAX_ITERATIONS) {
                iterations++
                val dirtyL = dirtyCells(true, area)
                val dirtyR = dirtyCells(false, area)
                if (dirtyL.isEmpty() && dirtyR.isEmpty()) break
                if (fids >= budget || bytes >= batchMax) { dirtyLeft = true; break }
                // the first pass partitions toward the PIPELINED budget (§8.2 —
                // "1–3 rects") unless this flush continues budget-limited work,
                // which stays wide until clean; repairs in later passes may
                // push a flush wide as well
                val aim = if (iterations == 1 && !continuing) maxOf(1, rectBudget - fids) else budget - fids
                val planned = planOps(dirtyL, dirtyR, aim)
                if (planned.isEmpty()) break
                var exhausted = false
                for (p in planned) {
                    if (fids >= budget) { exhausted = true; break }
                    val before = ops.size
                    fids += emit(p, ops, touched, budget - fids, batchMax - bytes)
                    for (i in before until ops.size) bytes += sizeOf(ops[i])
                    if (ops.size == before) exhausted = true   // refused for bytes: it waits
                }
                if (exhausted) { dirtyLeft = true; break }
            }
            if (!dirtyLeft && iterations >= MAX_ITERATIONS &&
                (dirtyCells(true, area).isNotEmpty() || dirtyCells(false, area).isNotEmpty())) {
                dirtyLeft = true
            }
        }
        if (dirtyLeft) {
            // whatever did not fit stays dirty: the next flush picks it up
            residual = area.reduce(Rect::union)
            Log.i("comp", "flush carries $fids fids; more dirt remains (${if (keyframe) "keyframe follow-up" else "budget"})")
        }

        if (ops.isEmpty()) {
            if (dirtyLeft) {
                // dirt that no op could carry even into an EMPTY batch would
                // otherwise sit pending forever: say so, and drop it rather
                // than stall the pump in silence
                Log.e("comp", "dirt remains that no op can carry (batch cap $batchMax) — dropped")
                residual = null
            }
            return null
        }
        val wide = keyframe || dirtyLeft || fids > rectBudget
        return Assembled(ops, epoch, touched, copies, hints, keyframe = keyframe, wide = wide)
    }

    /**
     * The flush [a] was rejected or lost. Everything it touched on either
     * lens is now UNKNOWN — other flushes may have landed before or after,
     * so no snapshot can be "restored" (round 5): the cells are marked and
     * will be transmitted again from the truth, with fresh fids (§8.2 rule 1
     * — never retransmit bytes). Copies applied since move the marks along.
     */
    fun rollback(a: Assembled) {
        for (t in a.touched) {
            val unknown = if (t.left) unknownL else unknownR
            // Where are the pixels this flush failed to update NOW? In place
            // (a copy reads its source, it does not clear it) and wherever
            // every later copy carried them. The frontier is COALESCED after
            // each copy (round 7: accumulating and re-walking it doubled per
            // copy — a scrolled band reached an OutOfMemoryError in ~20).
            var frontier: List<Rect> = listOf(t.rect)
            for (c in appliedCopies) {
                if (c.left != t.left || c.epoch <= a.epoch) continue
                val carried = ArrayList<Rect>()
                for (r in frontier) {
                    val inSrc = r.intersect(c.src) ?: continue
                    val m = inSrc.translate(c.dst.x - c.src.x, c.dst.y - c.src.y).clip()
                    if (m.w > 0 && m.h > 0) carried.add(m)
                }
                if (carried.isNotEmpty()) frontier = normalize(frontier + carried)
            }
            for (r in frontier) {
                markUnknown(unknown, r)
                if (r != t.rect) pendingDamage.add(widen(r))
            }
        }
        for (h in a.hints) pendingDamage.add(h)
        for (c in a.copies) { pendingDamage.add(c.src); pendingDamage.add(c.dst) }
        for (op in a.ops) when (op) {
            is DisplayOp.Delta -> pendingDamage.add(op.box)
            is DisplayOp.StereoPair -> { pendingDamage.add(widen(op.left)); pendingDamage.add(widen(op.right)) }
            is DisplayOp.Copy -> { pendingDamage.add(op.src); pendingDamage.add(op.dst) }
            is DisplayOp.Keyframe -> {}
        }
        if (a.keyframe) needsKeyframe = true
        epoch++
    }

    /** What the compositor believes a lens shows — test/selfcheck oracle
     *  against the simulator's own panels. */
    internal fun expectedLens(left: Boolean): Gray8 = if (left) shadowL else shadowR

    // ------------------------------------------------------------------ truth
    /** Render the per-lens truth of `composed` under the plane map. */
    private fun renderTruth() {
        System.arraycopy(composed.pix, 0, truthL.pix, 0, composed.pix.size)
        System.arraycopy(composed.pix, 0, truthR.pix, 0, composed.pix.size)
        java.util.Arrays.fill(ownerL, OWNER_REMAINDER)
        java.util.Arrays.fill(ownerR, OWNER_REMAINDER)
        val full = Rect(0, 0, width, height)
        // every region vacates its nominal area: what nothing re-covers is seam
        for (p in planes) {
            truthL.fillRect(p.rect, 0); truthR.fillRect(p.rect, 0)
            fillOwner(ownerL, p.rect, OWNER_SEAM); fillOwner(ownerR, p.rect, OWNER_SEAM)
        }
        val all = splitByPlanes(full).filter { inRegion(it) }.map { Piece(it, disparityAt(it)) }
        pieces = all.sortedByDescending { it.d }          // far first, nearest last
        for ((i, piece) in pieces.withIndex()) {
            paintShifted(truthL, ownerL, piece.rect, -piece.d, i.toShort())
            paintShifted(truthR, ownerR, piece.rect, piece.d, i.toShort())
        }
    }

    private fun paintShifted(dst: Gray8, owner: ShortArray, r: Rect, dx: Int, id: Short) {
        val x0 = maxOf(0, r.x + dx); val x1 = minOf(width, r.right + dx)
        if (x1 <= x0) return
        for (y in r.y until r.bottom) {
            val srcOff = y * width + (x0 - dx)
            val dstOff = y * width + x0
            System.arraycopy(composed.pix, srcOff, dst.pix, dstOff, x1 - x0)
            java.util.Arrays.fill(owner, dstOff, dstOff + (x1 - x0), id)
        }
    }

    private fun fillOwner(owner: ShortArray, r: Rect, id: Short) {
        for (y in r.y until r.bottom) java.util.Arrays.fill(owner, y * width + r.x, y * width + r.right, id)
    }

    // ------------------------------------------------------------------ diff
    /** Dirty grid cells (4x2, the damage grid) of one lens within [area]:
     *  cell index -> owner of its truth. A cell is dirty when its glass
     *  content is unknown, or when shadow and truth differ. Cells never mix
     *  owners: regions and shifts are grid-aligned by construction. */
    private fun dirtyCells(left: Boolean, area: List<Rect>): HashMap<Int, Short> {
        val shadow = if (left) shadowL else shadowR
        val truth = if (left) truthL else truthR
        val owner = if (left) ownerL else ownerR
        val unknown = if (left) unknownL else unknownR
        val out = HashMap<Int, Short>()
        for (a in area) {
            val cx0 = a.x / CW; val cx1 = (a.right + CW - 1) / CW
            val cy0 = a.y / CH; val cy1 = (a.bottom + CH - 1) / CH
            for (cy in cy0 until cy1) for (cx in cx0 until cx1) {
                val idx = cy * cellsW + cx
                if (out.containsKey(idx)) continue
                val px = cx * CW; val py = cy * CH
                var diff = unknown[idx]
                if (!diff) {
                    loop@ for (y in py until py + CH) {
                        val off = y * width + px
                        for (x in 0 until CW) {
                            if (shadow.pix[off + x] != truth.pix[off + x]) { diff = true; break@loop }
                        }
                    }
                }
                if (diff) out[idx] = owner[py * width + px]
            }
        }
        return out
    }

    private sealed class Planned {
        class Delta(val rect: Rect, val d: Int, val owner: Short) : Planned()
        class Black(val l: Rect, val r: Rect) : Planned()
    }

    /** Turn dirty cells into planned ops, partitioned toward [aim] rects and
     *  later-wins ordered: the remainder base first, far pieces, plane-0
     *  regions, near pieces, then seam blacks. */
    private fun planOps(dirtyL: Map<Int, Short>, dirtyR: Map<Int, Short>, aim: Int): List<Planned> {
        val deltas = LinkedHashMap<Pair<Rect, Int>, Planned.Delta>()
        val blacksL = ArrayList<Rect>()
        val blacksR = ArrayList<Rect>()
        for (left in booleanArrayOf(true, false)) {
            val dirty = if (left) dirtyL else dirtyR
            val byOwner = HashMap<Short, ArrayList<Int>>()
            for ((idx, own) in dirty) byOwner.getOrPut(own) { ArrayList() }.add(idx)
            for ((own, cells) in byOwner) {
                when {
                    own == OWNER_SEAM -> (if (left) blacksL else blacksR).addAll(seamStrips(left, cells))
                    own == OWNER_REMAINDER -> for (r in coarsen(rectsOf(cells), COARSE_MAX)) {
                        deltas.getOrPut(r to 0) { Planned.Delta(r, 0, own) }
                    }
                    else -> {
                        val piece = pieces[own.toInt()]
                        val shift = if (left) piece.d else -piece.d
                        for (r in coarsen(rectsOf(cells), COARSE_MAX)) {
                            val nominal = r.translate(shift, 0).intersect(piece.rect) ?: continue
                            deltas.getOrPut(nominal to piece.d) { Planned.Delta(nominal, piece.d, own) }
                        }
                    }
                }
            }
        }
        val blacks = pairBlacks(dedupe(blacksL), dedupe(blacksR))
        // seam pairs are few (whole strips); the deltas get the rest of the
        // aim, but never less than two — one rect per plane-0 half beside a
        // box is the minimum that does not force a merge ACROSS the box
        val merged = partition(deltas.values.toList(), maxOf(2, aim - blacks.size))
        val out = ArrayList<Planned>(merged.size + blacks.size)
        out.addAll(merged.sortedWith(compareBy({ if (it.owner == OWNER_REMAINDER) 0 else 1 }, { -it.d })))
        out.addAll(blacks)
        return out
    }

    /** A dirty seam cell stands for its WHOLE strip: the maximal rectangle of
     *  seam-owned cells around it. Painting black over cells that are
     *  already black is a no-op on the glass, and the strip is one pair
     *  instead of a dozen (round 6: cell-granular seams starved the budget). */
    private fun seamStrips(left: Boolean, cells: List<Int>): List<Rect> {
        val owner = if (left) ownerL else ownerR
        fun seam(cx: Int, cy: Int): Boolean =
            cx in 0 until cellsW && cy in 0 until cellsH && owner[cy * CH * width + cx * CW] == OWNER_SEAM
        val out = LinkedHashSet<Rect>()
        val covered = HashSet<Int>()
        for (idx in cells) {
            if (idx in covered) continue
            val cy = idx / cellsW; val cx = idx % cellsW
            var x0 = cx; while (seam(x0 - 1, cy)) x0--
            var x1 = cx; while (seam(x1 + 1, cy)) x1++
            fun rowIsSeam(y: Int): Boolean { for (x in x0..x1) if (!seam(x, y)) return false; return true }
            var y0 = cy; while (rowIsSeam(y0 - 1)) y0--
            var y1 = cy; while (rowIsSeam(y1 + 1)) y1++
            for (y in y0..y1) for (x in x0..x1) covered.add(y * cellsW + x)
            out.add(Rect(x0 * CW, y0 * CH, (x1 - x0 + 1) * CW, (y1 - y0 + 1) * CH))
        }
        return out.toList()
    }

    private fun dedupe(rects: List<Rect>): List<Rect> = rects.distinct()

    /**
     * §5.1/§8.2: merge planned deltas toward [target] rects — within an
     * owner piece first (a union inside the piece is always correct), then
     * across pieces of the same disparity, but NEVER across a pixel of a
     * different plane (round 6: a merged delta spanning a nearer box painted
     * the box's pixels at the wrong shift on every flush, forever). Least area
     * growth first; a final priced pass merges neighbours whose compressed
     * union is cheaper than the sum.
     */
    private fun partition(deltas: List<Planned.Delta>, target: Int): List<Planned.Delta> {
        if (deltas.size <= target) return price(deltas)
        val groups = LinkedHashMap<Short, ArrayList<Rect>>()
        val dOf = HashMap<Short, Int>()
        for (p in deltas) { groups.getOrPut(p.owner) { ArrayList() }.add(p.rect); dOf[p.owner] = p.d }
        // 1. within owners, proportionally (a piece is one plane: always safe)
        var total = deltas.size
        val out = ArrayList<Planned.Delta>()
        for ((own, rects) in groups) {
            val share = maxOf(1, (target.toLong() * rects.size / total).toInt())
            for (r in mergeToBudget(rects, share) { true }) out.add(Planned.Delta(r, dOf.getValue(own), own))
        }
        if (out.size <= target) return price(out)
        // 2. across owners of one disparity — only where the union holds no
        //    other plane's pixels
        val byD = LinkedHashMap<Int, ArrayList<Planned.Delta>>()
        for (p in out) byD.getOrPut(p.d) { ArrayList() }.add(p)
        total = out.size
        val result = ArrayList<Planned.Delta>()
        for ((d, list) in byD) {
            val share = maxOf(1, (target.toLong() * list.size / total).toInt())
            if (list.size <= share) { result.addAll(list); continue }
            val remainder = list.filter { it.owner == OWNER_REMAINDER }
            val inPieces = list.filter { it.owner != OWNER_REMAINDER }
            for (r in mergeToBudget(inPieces.map { it.rect }, maxOf(1, share - minOf(1, remainder.size))) { u -> samePlane(u, d) })
                result.add(Planned.Delta(r, d, OWNER_MIXED))
            for (r in mergeToBudget(remainder.map { it.rect }, 1) { u -> planes.none { it.rect.overlaps(u) } })
                result.add(Planned.Delta(r, 0, OWNER_REMAINDER))
        }
        return price(result)
    }

    /** True when every plane piece of [u] renders at [d] — the union paints
     *  no pixel at a shift that is not its own. */
    private fun samePlane(u: Rect, d: Int): Boolean =
        splitByPlanes(u).all { inRegion(it) && disparityAt(it) == d }

    /** §5.1: merge neighbouring rects of one disparity while the compressed
     *  union costs no more than the parts (each mode-3 sub-message carries a
     *  15-byte header of its own). Bounded: the list is already small. */
    private fun price(deltas: List<Planned.Delta>): List<Planned.Delta> {
        if (deltas.size < 2 || deltas.size > PRICE_MAX) return deltas
        val out = deltas.toMutableList()
        var merged = true
        while (merged && out.size >= 2) {
            merged = false
            outer@ for (i in out.indices) for (j in i + 1 until out.size) {
                val a = out[i]; val b = out[j]
                if (a.d != b.d) continue
                val u = a.rect.union(b.rect)
                val ok = if (a.owner == OWNER_REMAINDER || b.owner == OWNER_REMAINDER)
                    planes.none { it.rect.overlaps(u) } else samePlane(u, a.d)
                if (!ok) continue
                val cu = compress(u).size + SUB_HEADER
                val cab = compress(a.rect).size + compress(b.rect).size + 2 * SUB_HEADER
                if (cu <= cab) {
                    out.removeAt(j); out.removeAt(i)
                    out.add(Planned.Delta(u, a.d, if (a.owner == b.owner) a.owner else OWNER_MIXED))
                    merged = true
                    break@outer
                }
            }
        }
        return out
    }

    /** Least-area-growth pairwise merging down to [budget], skipping pairs
     *  [ok] refuses. Called on lists already coarsened to a small n. */
    private fun mergeToBudget(rects: List<Rect>, budget: Int, ok: (Rect) -> Boolean): List<Rect> {
        val out = rects.toMutableList()
        while (out.size > budget && out.size > 1) {
            var bi = -1; var bj = -1; var best = Long.MAX_VALUE
            for (i in out.indices) for (j in i + 1 until out.size) {
                val u = out[i].union(out[j])
                val grow = u.area.toLong() - out[i].area - out[j].area
                if (grow < best && ok(u)) { best = grow; bi = i; bj = j }
            }
            if (bi < 0) break                    // nothing left that may merge
            val u = out[bi].union(out[bj])
            out.removeAt(bj); out.removeAt(bi)
            out.add(u)
        }
        return out
    }

    /** Cheap pre-merge for the O(n²)-per-step merger: union rects by
     *  doubling row bands until at most [maxN] remain (round 6: hundreds of
     *  text-run rects made assembly cubic — seconds on the shell loop). */
    private fun coarsen(rects: List<Rect>, maxN: Int): List<Rect> {
        var cur = rects
        var band = CH * 4
        while (cur.size > maxN && band <= height * 2) {
            cur = cur.groupBy { it.y / band }.values.map { g -> g.reduce(Rect::union) }
            band *= 2
        }
        return cur
    }

    /** Black seam rects come in mirrored L/R pairs almost always; pair equal
     *  sizes, and give a leftover a same-size box the other lens shows black
     *  in both truth and shadow (a no-op there) — failing that, the same box,
     *  which the next pass repairs on the other lens. */
    private fun pairBlacks(l: List<Rect>, r: List<Rect>): List<Planned.Black> {
        val out = ArrayList<Planned.Black>()
        val restL = ArrayDeque(l)
        val restR = ArrayList(r)
        while (restL.isNotEmpty()) {
            val a = restL.removeFirst()
            val exact = restR.indexOfFirst { it.w == a.w && it.h == a.h && it.y == a.y }
            val bi = if (exact >= 0) exact else restR.indexOfFirst { it.h == a.h && it.y == a.y }
            if (bi >= 0) {
                val b = restR.removeAt(bi)
                if (b.w == a.w) {
                    out.add(Planned.Black(a, b))
                } else {
                    // same rows, different widths: pair the common width, requeue the rest
                    val w = minOf(a.w, b.w)
                    out.add(Planned.Black(Rect(a.x, a.y, w, a.h), Rect(b.x, b.y, w, b.h)))
                    if (a.w > w) restL.addFirst(Rect(a.x + w, a.y, a.w - w, a.h))
                    if (b.w > w) restR.add(Rect(b.x + w, b.y, b.w - w, b.h))
                }
                continue
            }
            out.add(Planned.Black(a, noOpBlackBox(false, a) ?: a))
        }
        for (b in restR) out.add(Planned.Black(noOpBlackBox(true, b) ?: b, b))
        return out
    }

    /** A box of [like]'s size on the given lens where truth AND shadow are
     *  already black — painting black there changes nothing. */
    private fun noOpBlackBox(left: Boolean, like: Rect): Rect? {
        val truth = if (left) truthL else truthR
        val shadow = if (left) shadowL else shadowR
        var x = 0
        while (x + like.w <= width) {
            val c = Rect(x, like.y, like.w, like.h)
            if (isBlack(truth, c) && isBlack(shadow, c)) return c
            x += Geometry.X_STEP
        }
        return null
    }

    private fun isBlack(g: Gray8, r: Rect): Boolean {
        for (y in r.y until r.bottom) {
            val off = y * width
            for (x in r.x until r.right) if (g.pix[off + x] != 0.toByte()) return false
        }
        return true
    }

    /** Greedy row-run rectangles over a set of grid cells. */
    private fun rectsOf(cells: List<Int>): List<Rect> {
        val set = HashSet(cells)
        val used = HashSet<Int>()
        val out = ArrayList<Rect>()
        for (idx in cells.sorted()) {
            if (idx in used) continue
            val cy = idx / cellsW; val cx = idx % cellsW
            var run = 0
            while (cx + run < cellsW && (idx + run) in set && (idx + run) !in used) run++
            var rows = 1
            while (cy + rows < cellsH) {
                val base = (cy + rows) * cellsW + cx
                var ok = true
                for (k in 0 until run) if (!((base + k) in set) || (base + k) in used) { ok = false; break }
                if (!ok) break
                rows++
            }
            for (ry in 0 until rows) for (k in 0 until run) used.add((cy + ry) * cellsW + cx + k)
            out.add(Rect(cx * CW, cy * CH, run * CW, rows * CH))
        }
        return out
    }

    // ------------------------------------------------------------------ shadows
    /** Emit one planned op: compress now (only what actually ships is
     *  priced), split a delta whose payload would not fit a mode-8
     *  sub-message (round 6), paint it into both shadows, clear the unknown
     *  marks it covers. Returns the fids consumed (0 when [fidsLeft] is 0). */
    private fun emit(p: Planned, ops: ArrayList<DisplayOp>, touched: ArrayList<Touched>, fidsLeft: Int, bytesLeft: Int): Int = when (p) {
        is Planned.Delta -> emitDelta(p.rect, p.d, ops, touched, fidsLeft, bytesLeft)
        is Planned.Black -> {
            val payload = black(p.l.w, p.l.h)
            if (fidsLeft <= 0 || payload.size + SUB_HEADER > bytesLeft) 0 else {
                shadowL.fillRect(p.l, 0)
                shadowR.fillRect(p.r, 0)
                markKnown(unknownL, p.l); markKnown(unknownR, p.r)
                touched.add(Touched(true, p.l)); touched.add(Touched(false, p.r))
                ops.add(DisplayOp.StereoPair(p.l, p.r, payload))
                1
            }
        }
    }

    private fun sizeOf(op: DisplayOp): Int = SUB_HEADER + when (op) {
        is DisplayOp.Keyframe -> op.payload.size
        is DisplayOp.Delta -> op.payload.size
        is DisplayOp.StereoPair -> op.payload.size
        is DisplayOp.Copy -> 16
    }

    private fun emitDelta(rect: Rect, d: Int, ops: ArrayList<DisplayOp>, touched: ArrayList<Touched>, fidsLeft: Int, bytesLeft: Int): Int {
        if (fidsLeft <= 0) return 0
        val payload = compress(rect)
        val overSub = payload.size > MAX_SUB
        val overBatch = payload.size + SUB_HEADER > bytesLeft
        if (overSub || overBatch) {
            // too big for a sub-message, or for what is left of this batch:
            // halve along the longer side and ship what fits — the rest stays
            // dirty. A batch too full to hold even a quarter-sized part is
            // simply full (round 7: refusing an intact op that fits nowhere in
            // the remaining bytes would have starved it forever).
            val splittable = rect.h >= 2 * CH || rect.w >= 2 * CW
            if (!splittable) {
                if (overSub) throw LintError("BUD002 a ${rect.w}x${rect.h} delta compresses to ${payload.size} B, past the sub-message cap $MAX_SUB")
                return 0
            }
            if (!overSub && bytesLeft < MIN_SPLIT_BYTES) return 0
            val (a, b) = if (rect.h >= 2 * CH && rect.h >= rect.w / 2) {
                val h1 = Geometry.snapY(rect.h / 2)
                Rect(rect.x, rect.y, rect.w, h1) to Rect(rect.x, rect.y + h1, rect.w, rect.h - h1)
            } else {
                val w1 = Geometry.snapX(rect.w / 2)
                Rect(rect.x, rect.y, w1, rect.h) to Rect(rect.x + w1, rect.y, rect.w - w1, rect.h)
            }
            val before = ops.size
            val used = emitDelta(a, d, ops, touched, fidsLeft, bytesLeft)
            val spent = (before until ops.size).sumOf { sizeOf(ops[it]) }
            return used + emitDelta(b, d, ops, touched, fidsLeft - used, bytesLeft - spent)
        }
        val l = rect.translate(-d, 0).clip()
        val r = rect.translate(d, 0).clip()
        paintNominal(shadowL, rect, -d)
        paintNominal(shadowR, rect, d)
        markKnown(unknownL, l); markKnown(unknownR, r)
        touched.add(Touched(true, l)); touched.add(Touched(false, r))
        ops.add(DisplayOp.Delta(rect, payload, d))
        return 1
    }

    private fun markUnknown(unknown: BooleanArray, r: Rect) = setCells(unknown, r, true)
    private fun markKnown(unknown: BooleanArray, r: Rect) = setCells(unknown, r, false)

    private fun setCells(cells: BooleanArray, r: Rect, v: Boolean) {
        val c = r.clip()
        if (c.w <= 0 || c.h <= 0) return
        val cx0 = c.x / CW; val cx1 = (c.right + CW - 1) / CW
        val cy0 = c.y / CH; val cy1 = (c.bottom + CH - 1) / CH
        for (cy in cy0 until cy1) java.util.Arrays.fill(cells, cy * cellsW + cx0, cy * cellsW + cx1, v)
    }

    /** Unknown marks travel with a copy: dst takes src's marks. */
    private fun moveCells(cells: BooleanArray, src: Rect, dst: Rect) {
        val s = src.clip(); val t = dst.clip()
        if (s.w <= 0 || s.h <= 0) return
        val cw = minOf(s.w, t.w) / CW; val ch = minOf(s.h, t.h) / CH
        val tmp = BooleanArray(cw * ch)
        for (cy in 0 until ch) for (cx in 0 until cw)
            tmp[cy * cw + cx] = cells[(s.y / CH + cy) * cellsW + s.x / CW + cx]
        for (cy in 0 until ch) for (cx in 0 until cw)
            cells[(t.y / CH + cy) * cellsW + t.x / CW + cx] = tmp[cy * cw + cx]
    }

    private fun paintNominal(dst: Gray8, r: Rect, dx: Int) {
        val x0 = maxOf(0, r.x + dx); val x1 = minOf(width, r.right + dx)
        if (x1 <= x0) return
        for (y in r.y until r.bottom) {
            System.arraycopy(composed.pix, y * width + (x0 - dx), dst.pix, y * width + x0, x1 - x0)
        }
    }

    private fun copyWithin(g: Gray8, src: Rect, dst: Rect) {
        val tmp = ByteArray(src.w * src.h)
        var o = 0
        for (y in src.y until src.bottom) {
            val x0 = maxOf(0, src.x); val x1 = minOf(width, src.right)
            if (y < 0 || y >= height || x1 <= x0) { o += src.w; continue }
            System.arraycopy(g.pix, y * width + x0, tmp, o + (x0 - src.x), x1 - x0)
            o += src.w
        }
        o = 0
        for (y in dst.y until dst.bottom) {
            val x0 = maxOf(0, dst.x); val x1 = minOf(width, dst.right)
            if (y >= 0 && y < height && x1 > x0) {
                System.arraycopy(tmp, o + (x0 - dst.x), g.pix, y * width + x0, x1 - x0)
            }
            o += dst.w
        }
    }

    private fun Rect.clip(): Rect {
        val nx = maxOf(0, x); val ny = maxOf(0, y)
        val nr = minOf(width, right); val nb = minOf(height, bottom)
        return Rect(nx, ny, maxOf(0, nr - nx), maxOf(0, nb - ny))
    }

    /** Widen a nominal rect by the maximum shift on both sides (clipped). */
    private fun widen(r: Rect): Rect =
        Rect(r.x - MAX_SHIFT, r.y, r.w + 2 * MAX_SHIFT, r.h).clip().alignOut()

    // ------------------------------------------------------------------ helpers
    private fun compress(r: Rect): ByteArray = Zl.encodeCfw(Pack.rect(composed, r))

    private fun black(w: Int, h: Int): ByteArray =
        Zl.encodeCfw(Pack.rect(Gray8(w, h), Rect(0, 0, w, h)))

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

    private fun touches(a: Rect, b: Rect): Boolean =
        !(a.right < b.x || b.right < a.x || a.bottom < b.y || b.bottom < a.y)

    /** A per-lens rect a flush painted — UNKNOWN again if that flush is lost. */
    class Touched(val left: Boolean, val rect: Rect)

    data class Assembled(
        val ops: List<DisplayOp>,
        val epoch: Long,
        val touched: List<Touched>,
        val copies: List<PlannedCopy>,
        /** The nominal hints this flush consumed — re-hinted on rollback. */
        val hints: List<Rect>,
        val keyframe: Boolean = false,
        /** Needs the window drained (depth 1): a keyframe, a budget-limited
         *  flush, or more rects than the pipelined budget — §8.2 #4. */
        val wide: Boolean = false,
    )

    companion object {
        private const val CW = Geometry.X_STEP
        private const val CH = Geometry.Y_STEP
        /** The largest shift on the ladder (§3.3 — the whole inset). */
        private const val MAX_SHIFT = 16
        private const val MAX_ITERATIONS = 6
        /** zlib_glue.c: a mode-8 sub-message is prefixed by rd16 — 65,535 B. */
        private const val MAX_SUB = 0xFFFF
        private const val BATCH_HEADER = 118
        /** Below this much room, an op that does not fit waits for the next
         *  flush instead of being split into fragments. */
        private const val MIN_SPLIT_BYTES = 4096
        /** mode-3 sub-message header: mode + box (4) + fid (2) + len16 (2) ~ 15 B. */
        private const val SUB_HEADER = 15
        private const val COARSE_MAX = 24
        private const val PRICE_MAX = 8
        private const val COPY_HISTORY = 64
        private const val OWNER_REMAINDER: Short = -1
        private const val OWNER_SEAM: Short = -2
        private const val OWNER_MIXED: Short = -3
    }
}
