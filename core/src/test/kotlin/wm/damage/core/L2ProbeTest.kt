package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent

/**
 * REGRESSION (review 2026-09-01 L2 — CONFIRMED by this probe, then fixed):
 * Compositor.pairBlacks — an UNPAIRED seam strip whose noOpBlackBox lookup
 * finds nothing ships Planned.Black(b, b): REAL black on the opposite lens,
 * while the same-flush repair loop (dirtyCells) rescans only `area`. Before
 * the fix, seamStrips expanded a strip to the seam's FULL maximal extent, so
 * strip rows outside `area` held a lens/truth divergence nothing ever
 * re-hinted (1,920 px of black over lit content, permanently, silently). The
 * fix bounds every strip to the scanned area (`areaCellSet`), which makes the
 * fallback's collateral in-area — repaired within the same atomic batch.
 *
 * Geometry (all rects 4x2 aligned, composed uniformly lit so the only black
 * anywhere is seam):
 *   A = (96,0 64x240)   d16   - its RIGHT-lens spill [112,176) covers C's
 *                               right-lens seam [168,176) over rows [0,240)
 *   C = (168,0 64x480)  d8    - right-lens seam [168,176), left seam [224,232)
 *   E = (232,240 64x240) d16  - its LEFT-lens spill [216,232) covers C's
 *                               left-lens seam over rows [240,480), so the
 *                               LEFT lens has NO full-height black column
 *
 * Removing A hints only A.rect (rows [0,240)): C's right-lens seam becomes
 * newly black over rows [0,240) inside the area, seamStrips expands the strip
 * to the full column [168,176)x[0,480), there is no left-lens no-op box of
 * that height, and the fallback paints the LEFT lens black over the full
 * column. Rows [0,240) are inside the area and self-repair; rows [240,480)
 * on the LEFT lens (C's lit content there) should stay black forever.
 */
class L2ProbeTest {

    private class Rig(scope: CoroutineScope) {
        val sim = GlassFirmwareSim()
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val comp = Compositor()
        val done = HashSet<Long>()
        val failed = ArrayList<String>()

        init {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    if (it is TransportEvent.FlushDone) synchronized(done) {
                        if (!it.ok) failed.add("flush ${it.id}: ${it.error}")
                        done.add(it.id)
                    }
                    if (it is TransportEvent.Fault && it.what != "warmup") failed.add("fault ${it.what}: ${it.detail}")
                }
            }
        }

        suspend fun start() {
            t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
        }

        suspend fun drain(label: String): Int {
            var n = 0
            while (comp.hasPending || comp.needsKeyframe) {
                val a = comp.assembleFlush(Geometry.rectBudget(3)) ?: break
                n++
                val id = t.submit(FlushRequest(a.ops, a.epoch, label, wide = a.wide))
                val t0 = System.currentTimeMillis()
                while (synchronized(done) { id !in done } && System.currentTimeMillis() - t0 < 10_000) delay(5)
                assertTrue(synchronized(done) { id in done }, "$label: flush $id never completed")
                assertTrue(n < 40, "$label: did not converge")
            }
            return n
        }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(640, 480)
            for (y in 0 until 480) for (x in 0 until 640) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                val n = if (x and 1 == 0) b shr 4 else b and 0x0F
                g[x, y] = n * 17
            }
            return g
        }
    }

    private companion object {
        /** Independent per-lens truth, the LensOracleTest model verbatim. */
        fun truthOf(composed: Gray8, planes: List<Compositor.PlaneRegion>, left: Boolean): Gray8 {
            val out = composed.copy()
            for (p in planes) out.fillRect(p.rect, 0)
            val cuts = { sel: (Rect) -> IntArray ->
                val s = sortedSetOf(0)
                for (p in planes) for (v in sel(p.rect)) s.add(v)
                s
            }
            val xs = cuts { intArrayOf(it.x, it.right) }.plus(640).toIntArray()
            val ys = cuts { intArrayOf(it.y, it.bottom) }.plus(480).toIntArray()
            data class Cell(val r: Rect, val d: Int)
            val cells = ArrayList<Cell>()
            for (i in 0 until xs.size - 1) for (j in 0 until ys.size - 1) {
                val r = Rect(xs[i], ys[j], xs[i + 1] - xs[i], ys[j + 1] - ys[j])
                if (r.w <= 0 || r.h <= 0) continue
                val owner = planes.lastOrNull { it.rect.contains(r) } ?: continue
                cells.add(Cell(r, owner.disparity))
            }
            for (c in cells.sortedByDescending { it.d }) {
                val shift = if (left) -c.d else c.d
                for (y in c.r.y until c.r.bottom) for (x in c.r.x until c.r.right) {
                    val tx = x + shift
                    if (tx in 0 until 640) out[tx, y] = composed[x, y]
                }
            }
            return out
        }

        fun diffReport(want: Gray8, got: Gray8): String? {
            var n = 0
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
            var first: String? = null
            for (y in 0 until 480) for (x in 0 until 640) {
                if (want[x, y] != got[x, y]) {
                    if (first == null) first = "first at ($x,$y) want ${want[x, y]} got ${got[x, y]}"
                    n++
                    if (x < x0) x0 = x; if (y < y0) y0 = y
                    if (x > x1) x1 = x; if (y > y1) y1 = y
                }
            }
            return if (n == 0) null else "$n px differ in [$x0..$x1]x[$y0..$y1]; $first"
        }
    }

    @Test
    fun unpairedSeamBlackLandsOutsideRepairArea(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.start()
            val comp = rig.comp

            val a = Compositor.PlaneRegion(Rect(96, 0, 64, 240), 16)
            val c = Compositor.PlaneRegion(Rect(168, 0, 64, 480), 8)
            val e = Compositor.PlaneRegion(Rect(232, 240, 64, 240), 16)

            // Fully lit uniform frame: the only black anywhere is seam.
            comp.composed.clear(8 * 17)
            comp.planes = listOf(a, c, e)
            comp.requestKeyframe()
            rig.drain("seed")

            // Sanity: the seeded state must be exactly right on both lenses.
            for (left in booleanArrayOf(true, false)) {
                val bad = diffReport(truthOf(comp.composed, listOf(a, c, e), left), rig.lens(left))
                assertEquals(null, bad, "seed: ${if (left) "LEFT" else "RIGHT"} glass != truth ($bad)")
            }

            // Remove A. The only hint is A.rect (rows 0..240). C's right-lens
            // seam column [168,176) is now black over its full height.
            comp.planes = listOf(c, e)
            rig.drain("A removed")
            assertTrue(!comp.hasPending && !comp.needsKeyframe, "not quiescent after removal")

            // Belief must always match the glass (this is expected to hold:
            // the shadow tracks what was really sent).
            for (left in booleanArrayOf(true, false)) {
                val bad = diffReport(comp.expectedLens(left), rig.lens(left))
                assertEquals(null, bad, "after removal: ${if (left) "LEFT" else "RIGHT"} belief != glass ($bad)")
            }

            // At rest the glass must equal the truth of the current frame.
            // The claim predicts the LEFT lens holds a real-black column at
            // [168,176) x [240,480) over C's lit content.
            for (left in booleanArrayOf(true, false)) {
                val bad = diffReport(truthOf(comp.composed, listOf(c, e), left), rig.lens(left))
                assertEquals(null, bad, "after removal: ${if (left) "LEFT" else "RIGHT"} glass != truth ($bad)")
            }
            assertEquals(emptyList(), rig.failed, "transport faults")
            rig.t.stop()
        } finally {
            scope.cancel()
        }
    }
}
