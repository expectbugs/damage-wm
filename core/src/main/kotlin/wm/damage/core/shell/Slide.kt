package wm.damage.core.shell

import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level

/**
 * Animated vertical slides — motion is first-class (DESIGN.md §6): ease-out
 * steps quantized to the 2 px damage grid, retargetable (a second notch re-aims,
 * never queues), and each frame's damage is only the translation plus the newly
 * exposed strip, so the animation costs about what snapping would (§6.1).
 *
 * Model: the band's TARGET content is what the models already say (cursor /
 * topLine updated immediately — optimistic, §5.11); [offsetPx] is how far the
 * band currently sits from that target: content[Y] = target[Y - offset]. Each
 * frame shifts toward zero and paints the strip that entered, via
 * [paintTargetSlice]. When the offset reaches zero the band equals a direct
 * repaint of the target state — SlideEquivalenceTest asserts exactly that.
 */
class Slide(
    private val comp: Compositor,
    /** The region that slides (a list band or the document area). */
    val region: Rect,
    /** Paint the TARGET content for region-relative rows [y0, y0+h) into g. */
    private val paintTargetSlice: (g: Gray8, y0: Int, h: Int) -> Unit,
) {
    var offsetPx = 0
        private set

    /** Frames per notch (`HANDOFF.md` §40, the Global `Slide frames` setting):
     *  null = the ease-out halving rule with its 8 px floor (auto — list 3,
     *  doc 5); N = the same ease-out on the 2 px grid, resampled to at most
     *  N frames, the last one taking whatever is left (1 = one copy and one
     *  strip). The shell sets it from the setting before every retarget. */
    var frames: Int? = null
    private var stepIndex = 0

    /** A strip the last frame left BLANK (§40: copy first, fill second): its
     *  bytes go out one flush after the translation that exposed it, so the
     *  band moves on the glass ~70 ms after the notch instead of after the
     *  strip's 1–4 KB. Filled by [fillDeferred] — the shell calls it before
     *  the next step; [step] calls it itself so a caller that never does
     *  still gets the right pixels, just not the split. */
    private var deferred: Rect? = null

    /** Content moved by [dy] px (positive = content moved UP = scrolled down):
     *  the visual sits dy further from target. Even values only (grid). */
    fun retarget(dy: Int) {
        require(dy % 2 == 0) { "slide step $dy not on the 2 px grid" }
        offsetPx += dy
        stepIndex = 0                    // N frames from HERE, for what is left
    }

    val active: Boolean get() = offsetPx != 0 || deferred != null

    /** Finish instantly: paint the target state and damage the region — used
     *  when an overlay is about to paint on top (§6.3: motion yields). */
    fun snap(g: Gray8) {
        if (offsetPx == 0 && deferred == null) return
        offsetPx = 0
        deferred = null
        paintFull(g)
    }

    /**
     * Land the strip the previous frame left blank: painted from the target
     * state at the band's CURRENT displacement (a retarget in between moved
     * the target, and content[Y] = target[Y − offset] holds for every row)
     * and damaged. At offset 0 it is the settle itself — the whole band
     * repainted from the target, hash-before-send dropping what did not
     * change. True when something was painted.
     */
    fun fillDeferred(g: Gray8): Boolean {
        val r = deferred ?: return false
        deferred = null
        if (offsetPx == 0) {
            paintFull(g)
            return true
        }
        val tmp = Gray8(r.w, r.h)
        paintTargetSlice(tmp, (r.y - region.y) - offsetPx, r.h)
        g.blit(tmp, Rect(0, 0, r.w, r.h), r.x, r.y)
        comp.damage(r)
        return true
    }

    /** Advance one frame on the composed surface [g]. Ease-out: half the
     *  remaining distance, floor 8 px, always even. A backlog past the region
     *  height jump-cuts (there is nothing meaningful left to slide). True
     *  while more remains. */
    fun step(g: Gray8): Boolean {
        fillDeferred(g)                  // a split's second half never waits past the next frame
        if (offsetPx == 0) return false
        val mag = kotlin.math.abs(offsetPx)
        if (mag >= region.h) {          // jump-cut: the whole band is new content
            offsetPx = 0
            paintFull(g)
            return false
        }
        val n = frames
        var s = when {
            n == null -> maxOf(8, (mag / 2 + 1) / 2 * 2)      // auto: the halving rule, 8 px floor
            stepIndex >= n - 1 -> mag                            // the last of N takes what is left
            else -> maxOf(2, (mag / 2 + 1) / 2 * 2)             // halving on the 2 px grid
        }
        if (s > mag) s = mag
        stepIndex++
        val down = offsetPx > 0            // content still needs to move UP by s

        val keep = region.h - s
        if (keep > 0) {
            val src: Rect
            val dst: Rect
            if (down) {
                src = Rect(region.x, region.y + s, region.w, keep)
                dst = Rect(region.x, region.y, region.w, keep)
            } else {
                src = Rect(region.x, region.y, region.w, keep)
                dst = Rect(region.x, region.y + s, region.w, keep)
            }
            val tmp = Gray8(src.w, src.h)
            tmp.blit(g, src, 0, 0)
            g.blit(tmp, Rect(0, 0, src.w, src.h), dst.x, dst.y)
            comp.declareShift(src, dst)
        }
        offsetPx -= if (down) s else -s

        // While an offset o remains, the band shows target content displaced by
        // o: content[Y] = target[Y - o]. The strip that just entered at the
        // incoming edge shows target rows at (stripY - o).
        val stripY: Int = if (down) region.h - s else 0
        val r = Rect(region.x, region.y + stripY, region.w, s)
        if (r.w * r.h >= SPLIT_FILL_PX) {
            // §40: the strip is worth a flush of its own — blank it now (a
            // uniform run, a few bytes) so this flush is the translation, and
            // fill it on the next pump. Active until that lands.
            g.fillRect(r.x, r.y, r.w, r.h, Level.BG)
            comp.damage(r)
            deferred = r
            return true
        }
        val tmp = Gray8(region.w, s)
        paintTargetSlice(tmp, stripY - offsetPx, s)
        g.blit(tmp, Rect(0, 0, region.w, s), r.x, r.y)
        comp.damage(r)

        if (offsetPx == 0) {
            // settle: repaint the whole band from target state so any rounding
            // or mid-retarget artifact cannot survive; hash-before-send drops
            // the unchanged parts, so this costs nothing extra on the wire
            paintFull(g)
            return false
        }
        return true
    }

    private fun paintFull(g: Gray8) {
        val full = Gray8(region.w, region.h)
        paintTargetSlice(full, 0, region.h)
        g.blit(full, Rect(0, 0, region.w, region.h), region.x, region.y)
        comp.damage(region)
    }

    companion object {
        /** A strip at least this large (in pixels) is filled one flush after
         *  the translation that exposed it (§40). Calibrated on the measured
         *  phone path: ~1 KB of text is 10–15 K px (a 32 px list row of
         *  600 px measured 1–2 KB; a Reader strip of 50–100 px 1.2–4.3 KB),
         *  and 1 KB is ~200 ms of the link. A list row's first half-step
         *  (16 px) stays under it; a document's or a terminal's strip does not. */
        const val SPLIT_FILL_PX = 12_000
    }
}
