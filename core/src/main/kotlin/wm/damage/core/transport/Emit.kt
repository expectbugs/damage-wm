package wm.damage.core.transport

import wm.damage.core.geom.FidAllocator
import wm.damage.core.geom.FidTracker
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.wire.CfwModes

/**
 * The shared emitter: FlushRequest -> one CFW image buffer, with fids stamped at
 * emit time. Used by every transport implementation so the bytes are identical
 * whether they cross a BLE link, the network seam, or go straight into the sim.
 *
 * Disparity convention (DESIGN.md §3.3): content parks FAR, which is uncrossed —
 * the left lens draws shifted LEFT, the right lens shifted RIGHT ("the left lens
 * draws at x=0 and the right at x=32" at the max ladder step). Positive d = far
 * (L at x-d, R at x+d); negative d = crossed/near, the reserved plane-+1 slot.
 */
object Emit {
    /** Serialize a flush. Returns the image-buffer bytes plus the fids consumed
     *  (in order), validating every silent-failure rule on the way out. */
    fun encode(flush: FlushRequest, fids: FidAllocator, tracker: FidTracker, window: Int): Encoded {
        if (flush.ops.isEmpty()) throw LintError("empty flush")
        val subs = ArrayList<ByteArray>(flush.ops.size)
        val consumed = ArrayList<Int>()
        var deltaRects = 0

        for (op in flush.ops) {
            when (op) {
                is DisplayOp.Keyframe -> {
                    tracker.keyframe()
                    subs += CfwModes.keyframe(op.payload)
                }
                is DisplayOp.Delta -> {
                    deltaRects++
                    val fid = fids.take()
                    consumed += fid
                    val errs = tracker.delta(fid)
                    if (errs.isNotEmpty()) throw LintError(errs.joinToString("; "))
                    subs += if (op.disparity == 0) {
                        CfwModes.delta(op.box, op.payload, fid)
                    } else {
                        val (l, r) = stereo(op.box, op.disparity)
                        CfwModes.deltaStereo(l, r, op.payload, fid)
                    }
                }
                is DisplayOp.Copy -> {
                    subs += if (op.disparity == 0) {
                        CfwModes.copy(op.src, op.dst)
                    } else {
                        val (sl, sr) = stereo(op.src, op.disparity)
                        val (dl, dr) = stereo(op.dst, op.disparity)
                        CfwModes.copyStereo(sl, dl, sr, dr)
                    }
                }
            }
        }

        // BUD001 at the real boundary: mode-3 rects x window <= fid ring.
        if (deltaRects > Geometry.rectBudget(window))
            throw LintError(
                "BUD001 $deltaRects mode-3 rects with a $window-deep pipeline exceeds " +
                    "${Geometry.rectBudget(window)} — a retransmit would age out of the " +
                    "duplicate ring and be RE-APPLIED",
            )

        // A lone keyframe goes bare (no mode-8 wrapper); everything else batches.
        val image = if (subs.size == 1 && flush.ops[0] is DisplayOp.Keyframe) subs[0]
        else CfwModes.batch(subs)
        if (image.size > Geometry.MODE8_MAX)
            throw LintError("BUD002 flush ${image.size} B exceeds the firmware cap ${Geometry.MODE8_MAX}")
        return Encoded(image, consumed)
    }

    /** Build the per-lens pair for a nominal rect at disparity [d]. */
    fun stereo(r: Rect, d: Int): Pair<Rect, Rect> {
        if (d % Geometry.X_STEP != 0)
            throw LintError("GEO006 disparity $d not on the 4 px ladder")
        val l = r.translate(-d, 0)
        val rr = r.translate(d, 0)
        val errs = Geometry.checkStereoPair(l, rr)
        if (errs.isNotEmpty()) throw LintError(errs.joinToString("; "))
        return l to rr
    }

    data class Encoded(val image: ByteArray, val fids: List<Int>)
}
