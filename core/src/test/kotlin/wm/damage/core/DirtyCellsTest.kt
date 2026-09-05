package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect

/**
 * `HANDOFF.md` §35: the compositor's cell diff was rewritten as a tight
 * mismatch scan. This pins it, cell for cell and owner for owner, against the
 * loop it replaced, over random shadows, truths, unknown marks and areas.
 */
class DirtyCellsTest {

    private val CW = Geometry.X_STEP
    private val CH = Geometry.Y_STEP

    /** The scan as it was: every cell visited, 8 pixels compared. */
    private fun reference(c: Compositor, left: Boolean, area: List<Rect>): Map<Int, Short> {
        val shadow = c.shadowForTest(left); val truth = c.truthForTest(left)
        val owner = c.ownerForTest(left); val unknown = c.unknownForTest(left)
        val width = c.width; val cellsW = c.cellsWForTest
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
                        for (x in 0 until CW) if (shadow.pix[off + x] != truth.pix[off + x]) { diff = true; break@loop }
                    }
                }
                if (diff) out[idx] = owner[py * width + px]
            }
        }
        return out
    }

    @Test
    fun theScanFindsExactlyTheCellsTheOldLoopFound() {
        val rnd = kotlin.random.Random(7)
        val c = Compositor()
        repeat(60) { round ->
            for (left in booleanArrayOf(true, false)) {
                val shadow = c.shadowForTest(left); val truth = c.truthForTest(left)
                val owner = c.ownerForTest(left); val unknown = c.unknownForTest(left)
                // mostly-equal panels with sparse differences of every shape:
                // single pixels, runs, whole cells, cells at the area's edges
                rnd.nextBytes(shadow.pix)
                System.arraycopy(shadow.pix, 0, truth.pix, 0, shadow.pix.size)
                repeat(rnd.nextInt(0, 400)) {
                    val i = rnd.nextInt(truth.pix.size)
                    truth.pix[i] = (truth.pix[i] + 1 + rnd.nextInt(3)).toByte()
                }
                repeat(rnd.nextInt(0, 6)) {
                    val x = rnd.nextInt(c.width); val y = rnd.nextInt(c.height); val n = rnd.nextInt(1, 80)
                    for (k in 0 until n) if (x + k < c.width) truth.pix[y * c.width + x + k] = 0
                }
                java.util.Arrays.fill(unknown, false)
                repeat(rnd.nextInt(0, 30)) { unknown[rnd.nextInt(unknown.size)] = true }
                for (i in owner.indices) owner[i] = (rnd.nextInt(5) - 3).toShort()
            }
            // areas: a few grid-aligned rects, sometimes overlapping, sometimes the whole panel
            val area = ArrayList<Rect>()
            if (round % 7 == 0) area.add(Rect(0, 0, c.width, c.height))
            repeat(rnd.nextInt(1, 5)) {
                val x = Geometry.snapX(rnd.nextInt(c.width - 8)); val y = Geometry.snapY(rnd.nextInt(c.height - 4))
                val w = Geometry.snapX(rnd.nextInt(4, c.width - x)).coerceAtLeast(CW)
                val h = Geometry.snapY(rnd.nextInt(2, c.height - y)).coerceAtLeast(CH)
                area.add(Rect(x, y, w, h))
            }
            for (left in booleanArrayOf(true, false)) {
                assertEquals(reference(c, left, area), c.dirtyCellsForTest(left, area),
                    "round $round ${if (left) "L" else "R"} area $area")
            }
        }
    }
}
