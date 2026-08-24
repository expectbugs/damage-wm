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
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent

/**
 * The per-lens oracle (review round 4): after every flush the compositor's
 * belief about each lens must equal what the firmware model actually holds,
 * and — once nothing is pending — each lens must equal the independent
 * per-lens TRUTH of the nominal frame under the plane map: every region
 * vacates its nominal area (black seam), its pieces render at their shift,
 * far to near with the nearest winning, over the transparent nominal base.
 */
class LensOracleTest {

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

        /** Drain everything pending through the sim; returns the flush count. */
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

        /** Belief vs firmware, both lenses, whole panel. */
        fun assertBeliefMatchesGlass(label: String) {
            for (left in booleanArrayOf(true, false)) {
                val bad = firstDiff(comp.expectedLens(left), lens(left))
                assertEquals(null, bad, "$label: ${if (left) "LEFT" else "RIGHT"} belief != glass at $bad")
            }
        }

        /** Firmware vs the independent truth, both lenses. */
        fun assertGlassIsTruth(label: String, planes: List<Compositor.PlaneRegion>) {
            for (left in booleanArrayOf(true, false)) {
                val truth = truthOf(comp.composed, planes, left)
                val bad = firstDiff(truth, lens(left))
                assertEquals(null, bad, "$label: ${if (left) "LEFT" else "RIGHT"} glass != truth at $bad")
            }
        }
    }

    private companion object {
        fun firstDiff(a: Gray8, b: Gray8): String? {
            for (y in 0 until 480) for (x in 0 until 640) {
                if (a[x, y] != b[x, y]) return "($x,$y) expected ${a[x, y]} got ${b[x, y]}"
            }
            return null
        }

        /** Independent per-lens truth: nominal base, regions vacated, pieces
         *  painted at their shift far to near. Written without reference to
         *  the compositor's own code. */
        fun truthOf(composed: Gray8, planes: List<Compositor.PlaneRegion>, left: Boolean): Gray8 {
            val out = composed.copy()
            for (p in planes) out.fillRect(p.rect, 0)
            // partition the panel into cells that share the same LAST region
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
    }

    private fun paintScene(g: Gray8, l: Layout, seed: Int) {
        g.clear(0)
        g.fillRect(l.topBar, 3 * 17)
        g.fillRect(l.statusBar, 2 * 17)
        // content: bands of different levels so shifts are visible
        var y = l.content.y
        var k = seed
        while (y < l.content.bottom) {
            g.fillRect(l.content.x, y, l.content.w, minOf(16, l.content.bottom - y), ((k % 14) + 1) * 17)
            y += 16; k++
        }
        // the lens band: distinct
        g.fillRect(l.lens, 15 * 17)
        g.fillRect(l.lens.x + 8, l.lens.y + 8, l.lens.w - 16, l.lens.h - 16, 6 * 17)
    }

    @Test
    fun beliefMatchesGlassAndTruthAcrossTransitions(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.start()
            val l = Layout()
            val comp = rig.comp
            val box = Rect(l.notificationMax.x, l.notificationMax.y, l.notificationMax.w, 104)
            val centre = Rect(l.switcherPanel.x, l.switcherPanel.y + 44, l.switcherPanel.w, 88)

            for (d in intArrayOf(8, 12, 16)) {
                val content = listOf(Compositor.PlaneRegion(l.content, d), Compositor.PlaneRegion(l.lens, 0))
                paintScene(comp.composed, l, d)
                comp.planes = content
                comp.requestKeyframe()
                rig.drain("keyframe d=$d")
                rig.assertBeliefMatchesGlass("keyframe d=$d")
                rig.assertGlassIsTruth("keyframe d=$d", content)

                // a notification arrives at the content plane
                val arrived = content + Compositor.PlaneRegion(box, d)
                comp.composed.fillRect(box, 0)
                comp.composed.fillRect(box.x, box.y + 16, box.w, 2, 9 * 17)
                comp.damage(box)
                comp.planes = arrived
                rig.drain("box arrives d=$d")
                rig.assertBeliefMatchesGlass("box arrives d=$d")
                rig.assertGlassIsTruth("box arrives d=$d", arrived)

                // focus step: the box comes forward with no pixel change
                val focused = content + Compositor.PlaneRegion(box, 0)
                comp.planes = focused
                rig.drain("focus d=$d")
                rig.assertBeliefMatchesGlass("focus d=$d")
                rig.assertGlassIsTruth("focus d=$d", focused)

                // emergency: crossed plane
                val crossed = content + Compositor.PlaneRegion(box, -4)
                comp.planes = crossed
                rig.drain("crossed d=$d")
                rig.assertGlassIsTruth("crossed d=$d", crossed)

                // furl: region gone, the whole scene repainted underneath
                paintScene(comp.composed, l, d + 1)
                comp.damageAll()
                comp.planes = content
                rig.drain("furl d=$d")
                rig.assertBeliefMatchesGlass("furl d=$d")
                rig.assertGlassIsTruth("furl d=$d", content)

                // switcher open: panel at d, centre band forward
                val wheel = content + Compositor.PlaneRegion(l.switcherPanel, d) + Compositor.PlaneRegion(centre, 0)
                comp.composed.fillRect(l.switcherPanel, 1 * 17)
                comp.composed.fillRect(centre, 12 * 17)
                comp.damage(l.switcherPanel)
                comp.planes = wheel
                rig.drain("wheel d=$d")
                rig.assertGlassIsTruth("wheel d=$d", wheel)

                // a box over the open wheel (the busiest map)
                val busy = wheel + Compositor.PlaneRegion(box, d)
                comp.composed.fillRect(box, 0)
                comp.damage(box)
                comp.planes = busy
                rig.drain("wheel+box d=$d")
                rig.assertGlassIsTruth("wheel+box d=$d", busy)

                // a declared shift (slide) of the band below the lens, under the plane map
                val band = Rect(l.content.x, l.lens.bottom, l.content.w - Layout.RAIL_W, l.rowsBelow * Layout.ROW_H)
                comp.planes = content
                paintScene(comp.composed, l, d + 2)
                comp.damageAll()
                rig.drain("reset d=$d")
                val src = Rect(band.x, band.y + 32, band.w, band.h - 32)
                val dst = Rect(band.x, band.y, band.w, band.h - 32)
                val tmp = Gray8(src.w, src.h); tmp.blit(comp.composed, src, 0, 0)
                comp.composed.blit(tmp, Rect(0, 0, src.w, src.h), dst.x, dst.y)
                comp.composed.fillRect(band.x, band.bottom - 32, band.w, 32, 5 * 17)
                comp.declareShift(src, dst)
                comp.damage(Rect(band.x, band.bottom - 32, band.w, 32))
                rig.drain("slide d=$d")
                rig.assertBeliefMatchesGlass("slide d=$d")
                rig.assertGlassIsTruth("slide d=$d", content)
            }
            assertEquals(emptyList(), rig.failed, "transport faults during the oracle run")
            rig.t.stop()
        } finally {
            scope.cancel()
        }
    }
}
