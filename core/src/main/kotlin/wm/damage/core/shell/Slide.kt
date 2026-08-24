package wm.damage.core.shell

import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8

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

    /** Content moved by [dy] px (positive = content moved UP = scrolled down):
     *  the visual sits dy further from target. Even values only (grid). */
    fun retarget(dy: Int) {
        require(dy % 2 == 0) { "slide step $dy not on the 2 px grid" }
        offsetPx += dy
    }

    val active: Boolean get() = offsetPx != 0

    /** Finish instantly: paint the target state and damage the region — used
     *  when an overlay is about to paint on top (§6.3: motion yields). */
    fun snap(g: Gray8) {
        if (offsetPx == 0) return
        offsetPx = 0
        paintFull(g)
    }

    /** Advance one frame on the composed surface [g]. Ease-out: half the
     *  remaining distance, floor 8 px, always even. A backlog past the region
     *  height jump-cuts (there is nothing meaningful left to slide). True
     *  while more remains. */
    fun step(g: Gray8): Boolean {
        if (offsetPx == 0) return false
        val mag = kotlin.math.abs(offsetPx)
        if (mag >= region.h) {          // jump-cut: the whole band is new content
            offsetPx = 0
            paintFull(g)
            return false
        }
        var s = maxOf(8, (mag / 2 + 1) / 2 * 2)
        if (s > mag) s = mag
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
}
