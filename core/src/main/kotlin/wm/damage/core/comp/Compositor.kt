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
 *   §5.6  hash before send — nothing whose bytes did not change is sent
 *   §5.12 shadows: damage is computed against what was SENT, advancing on
 *         send and rolling back on failure — copies included
 *   §5.13 backpressure coalescing — pending damage merges, never queues
 *   §5.14 damage epochs
 *
 * Stereo (review round 4 rewrite): the compositor reasons PER LENS. It keeps
 * an expected shadow of what each lens shows, renders the per-lens TRUTH of
 * the nominal frame under the plane map (each region vacates its nominal
 * area, its pieces render at their shift far to near, the nearest wins, the
 * 16 px insets are the transparent shift budget of §3.3), and emits whatever
 * ops make shadow == truth — nominal deltas at their disparity, black stereo
 * pairs where the truth is a parallax seam. Each planned op is applied to the
 * shadows as it is planned, so an op's effect on the OTHER lens (a far piece
 * spilling under a nearer one) is seen and repaired in the same flush, in
 * later-wins order. Whatever the fid budget cannot carry stays dirty and
 * goes out next flush. Plane changes, seam cleanup, keyframe follow-ups and
 * reclaims are no longer special cases — they are just differences between
 * shadow and truth.
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
    /** Dirt a budget-limited flush left behind (nominal-space bbox). */
    private var residual: Rect? = null

    var epoch: Long = 0
        private set

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
                val errs = Geometry.checkRect(p.rect, "plane region") +
                    if (p.disparity % Geometry.X_STEP != 0)
                        listOf("GEO006 plane disparity ${p.disparity} off the 4 px ladder") else emptyList()
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
     * budget for the current pipeline depth (§8.2); a flush that needs more
     * runs WIDE (window drained, 16 fids), and anything past even that stays
     * dirty for the next flush.
     */
    fun assembleFlush(rectBudget: Int): Assembled? {
        val keyframe = needsKeyframe
        val ops = ArrayList<DisplayOp>()
        val undo = ArrayList<Undo>()
        val full = Rect(0, 0, width, height)

        val copies = ArrayList(pendingCopies)
        pendingCopies.clear()
        val hints = ArrayList(pendingDamage)
        pendingDamage.clear()
        residual?.let { hints.add(it) }
        residual = null

        if (keyframe) {
            needsKeyframe = false
            // the mode-6 seeds BOTH lenses at nominal; everything stereo is
            // then a difference between that and the truth
            undo.add(Undo(true, full, snapshot(shadowL, full)))
            undo.add(Undo(false, full, snapshot(shadowR, full)))
            System.arraycopy(composed.pix, 0, shadowL.pix, 0, composed.pix.size)
            System.arraycopy(composed.pix, 0, shadowR.pix, 0, composed.pix.size)
            ops.add(DisplayOp.Keyframe(compress(full)))
        } else {
            if (hints.isEmpty() && copies.isEmpty()) return null
            // Replay declared copies on the shadows per lens, undo-snapshotted
            // (§5.12 — a lost scroll flush must roll the diff base back).
            for (c in copies) {
                val d = disparityAt(c.dst)
                val (sl, dl) = c.src.translate(-d, 0) to c.dst.translate(-d, 0)
                val (sr, dr) = c.src.translate(d, 0) to c.dst.translate(d, 0)
                undo.add(Undo(true, dl.clip(), snapshot(shadowL, dl.clip())))
                undo.add(Undo(false, dr.clip(), snapshot(shadowR, dr.clip())))
                copyWithin(shadowL, sl, dl)
                copyWithin(shadowR, sr, dr)
                ops.add(DisplayOp.Copy(c.src, c.dst, d))
                hints.add(c.src); hints.add(c.dst)
            }
        }

        // The area the diff scans: every hint, widened by the maximum shift
        // so a lens-space consequence of a nominal change is inside it.
        val area: List<Rect> = if (keyframe) listOf(full)
        else normalize(hints.map { widen(it) })

        renderTruth()
        val budget = Geometry.rectBudget(1)     // the wide budget; pipelined checked below
        var fids = 0
        var iterations = 0
        var dirtyLeft = false
        while (iterations < MAX_ITERATIONS) {
            iterations++
            val dirtyL = dirtyCells(true, area)
            val dirtyR = dirtyCells(false, area)
            if (dirtyL.isEmpty() && dirtyR.isEmpty()) break
            val planned = planOps(dirtyL, dirtyR)
            if (planned.isEmpty()) break
            var exhausted = false
            for (op in planned) {
                if (fids >= budget) { exhausted = true; break }
                applyToShadows(op, undo)
                ops.add(op)
                fids++
            }
            if (exhausted) { dirtyLeft = true; break }
        }
        if (!dirtyLeft && iterations >= MAX_ITERATIONS &&
            (dirtyCells(true, area).isNotEmpty() || dirtyCells(false, area).isNotEmpty())) {
            dirtyLeft = true
        }
        if (dirtyLeft) {
            // whatever did not fit stays dirty: the next flush picks it up
            residual = area.reduce(Rect::union)
            Log.i("comp", "flush carries $fids fids; more dirt remains (${if (keyframe) "keyframe follow-up" else "budget"})")
        }

        if (ops.isEmpty()) return null
        val wide = keyframe || dirtyLeft || fids > rectBudget
        return Assembled(ops, epoch, undo, copies, hints, keyframe = keyframe, wide = wide)
    }

    /** The flush [a] was rejected or lost: restore the per-lens diff base and
     *  re-hint every affected region, so the NEXT flush recomputes with fresh
     *  fids (§8.2 rule 1 — never retransmit). */
    fun rollback(a: Assembled) {
        for (u in a.undo.asReversed()) restore(if (u.left) shadowL else shadowR, u.rect, u.bytes)
        for (h in a.hints) pendingDamage.add(h)
        for (c in a.copies) { pendingDamage.add(c.src); pendingDamage.add(c.dst) }
        for (op in a.ops) when (op) {
            is DisplayOp.Delta -> pendingDamage.add(op.box)
            is DisplayOp.StereoPair -> { pendingDamage.add(widen(op.left)); pendingDamage.add(widen(op.right)) }
            else -> {}
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
     *  cell index -> owner of its truth (uniform per cell by construction of
     *  aligned regions; the first differing pixel decides otherwise). */
    private fun dirtyCells(left: Boolean, area: List<Rect>): HashMap<Int, Short> {
        val shadow = if (left) shadowL else shadowR
        val truth = if (left) truthL else truthR
        val owner = if (left) ownerL else ownerR
        val out = HashMap<Int, Short>()
        for (a in area) {
            val cx0 = a.x / CW; val cx1 = (a.right + CW - 1) / CW
            val cy0 = a.y / CH; val cy1 = (a.bottom + CH - 1) / CH
            for (cy in cy0 until cy1) for (cx in cx0 until cx1) {
                val idx = cy * cellsW + cx
                if (out.containsKey(idx)) continue
                val px = cx * CW; val py = cy * CH
                var diff = false
                var own: Short = OWNER_REMAINDER
                loop@ for (y in py until py + CH) {
                    val off = y * width + px
                    for (x in 0 until CW) {
                        if (shadow.pix[off + x] != truth.pix[off + x]) {
                            diff = true; own = owner[off + x]; break@loop
                        }
                    }
                }
                if (diff) out[idx] = own
            }
        }
        return out
    }

    /** Turn dirty cells into ops, later-wins ordered: the remainder base
     *  first, far pieces, plane-0 regions, near pieces, then seam blacks. */
    private fun planOps(dirtyL: Map<Int, Short>, dirtyR: Map<Int, Short>): List<DisplayOp> {
        val deltas = LinkedHashMap<Pair<Rect, Int>, DisplayOp.Delta>()
        val blacksL = ArrayList<Rect>()
        val blacksR = ArrayList<Rect>()
        for (left in booleanArrayOf(true, false)) {
            val dirty = if (left) dirtyL else dirtyR
            val byOwner = HashMap<Short, ArrayList<Int>>()
            for ((idx, own) in dirty) byOwner.getOrPut(own) { ArrayList() }.add(idx)
            for ((own, cells) in byOwner) {
                val rects = rectsOf(cells)
                when {
                    own == OWNER_SEAM -> (if (left) blacksL else blacksR).addAll(rects)
                    own == OWNER_REMAINDER -> for (r in rects) {
                        deltas.getOrPut(r to 0) { DisplayOp.Delta(r, compress(r), 0) }
                    }
                    else -> {
                        val piece = pieces[own.toInt()]
                        val shift = if (left) piece.d else -piece.d
                        for (r in rects) {
                            val nominal = r.translate(shift, 0).intersect(piece.rect) ?: continue
                            deltas.getOrPut(nominal to piece.d) { DisplayOp.Delta(nominal, compress(nominal), piece.d) }
                        }
                    }
                }
            }
        }
        val out = ArrayList<DisplayOp>(deltas.size + blacksL.size + blacksR.size)
        // remainder (transparent base) first, then far -> near
        out.addAll(deltas.values.sortedWith(compareBy({ if (it.disparity == 0 && !inRegion(it.box)) 0 else 1 }, { -it.disparity })))
        out.addAll(pairBlacks(blacksL, blacksR))
        return out
    }

    /** Black seam rects come in mirrored L/R pairs almost always; pair equal
     *  sizes, and give a leftover a same-size box the other lens shows black
     *  in both truth and shadow (a no-op there) — failing that, the same box,
     *  which the next iteration repairs on the other lens. */
    private fun pairBlacks(l: List<Rect>, r: List<Rect>): List<DisplayOp.StereoPair> {
        val out = ArrayList<DisplayOp.StereoPair>()
        val restL = ArrayDeque(l)
        val restR = ArrayList(r)
        while (restL.isNotEmpty()) {
            val a = restL.removeFirst()
            val exact = restR.indexOfFirst { it.w == a.w && it.h == a.h && it.y == a.y }
            val bi = if (exact >= 0) exact else restR.indexOfFirst { it.h == a.h && it.y == a.y }
            if (bi >= 0) {
                val b = restR.removeAt(bi)
                if (b.w == a.w) {
                    out.add(DisplayOp.StereoPair(a, b, black(a.w, a.h)))
                } else {
                    // same rows, different widths: pair the common width, requeue the rest
                    val w = minOf(a.w, b.w)
                    out.add(DisplayOp.StereoPair(Rect(a.x, a.y, w, a.h), Rect(b.x, b.y, w, b.h), black(w, a.h)))
                    if (a.w > w) restL.addFirst(Rect(a.x + w, a.y, a.w - w, a.h))
                    if (b.w > w) restR.add(Rect(b.x + w, b.y, b.w - w, b.h))
                }
                continue
            }
            val partner = noOpBlackBox(false, a) ?: a
            out.add(DisplayOp.StereoPair(a, partner, black(a.w, a.h)))
        }
        for (b in restR) {
            val partner = noOpBlackBox(true, b) ?: b
            out.add(DisplayOp.StereoPair(partner, b, black(b.w, b.h)))
        }
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
            while (true) {
                val base = (cy + rows) * cellsW + cx
                if (cy + rows >= cellsH) break
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
    private fun applyToShadows(op: DisplayOp, undo: ArrayList<Undo>) {
        when (op) {
            is DisplayOp.Delta -> {
                val l = op.box.translate(-op.disparity, 0).clip()
                val r = op.box.translate(op.disparity, 0).clip()
                undo.add(Undo(true, l, snapshot(shadowL, l)))
                undo.add(Undo(false, r, snapshot(shadowR, r)))
                paintNominal(shadowL, op.box, -op.disparity)
                paintNominal(shadowR, op.box, op.disparity)
            }
            is DisplayOp.StereoPair -> {
                undo.add(Undo(true, op.left, snapshot(shadowL, op.left)))
                undo.add(Undo(false, op.right, snapshot(shadowR, op.right)))
                shadowL.fillRect(op.left, 0)
                shadowR.fillRect(op.right, 0)
            }
            else -> throw IllegalStateException("planned op of kind $op")
        }
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

    private fun snapshot(s: Gray8, r: Rect): ByteArray {
        if (r.w <= 0 || r.h <= 0) return ByteArray(0)
        val out = ByteArray(r.w * r.h)
        var o = 0
        for (y in r.y until r.bottom) {
            System.arraycopy(s.pix, y * s.w + r.x, out, o, r.w)
            o += r.w
        }
        return out
    }

    private fun restore(s: Gray8, r: Rect, bytes: ByteArray) {
        if (r.w <= 0 || r.h <= 0) return
        var o = 0
        for (y in r.y until r.bottom) {
            System.arraycopy(bytes, o, s.pix, y * s.w + r.x, r.w)
            o += r.w
        }
    }

    class Undo(val left: Boolean, val rect: Rect, val bytes: ByteArray)

    data class Assembled(
        val ops: List<DisplayOp>,
        val epoch: Long,
        val undo: List<Undo>,
        val copies: List<PlannedCopy>,
        /** The nominal hints this flush consumed — re-hinted on rollback. */
        val hints: List<Rect>,
        val keyframe: Boolean = false,
        /** Needs the window drained (depth 1): a keyframe, a budget-limited
         *  flush, or more rects than the pipelined budget — §8.2 #4. */
        val wide: Boolean = false,
    )

    private val cellsW = width / CW
    private val cellsH = height / CH

    companion object {
        private const val CW = Geometry.X_STEP
        private const val CH = Geometry.Y_STEP
        /** The largest shift on the ladder (§3.3 — the whole inset). */
        private const val MAX_SHIFT = 16
        private const val MAX_ITERATIONS = 6
        private const val OWNER_REMAINDER: Short = -1
        private const val OWNER_SEAM: Short = -2
    }
}
