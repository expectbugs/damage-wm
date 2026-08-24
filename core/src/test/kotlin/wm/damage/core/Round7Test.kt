package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent

/** Review round 7 regressions: rollback cost after many copies, batch bytes. */
class Round7Test {

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
                }
            }
        }

        suspend fun start() = t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))

        suspend fun ship(a: Compositor.Assembled) {
            val id = t.submit(FlushRequest(a.ops, a.epoch, "", wide = a.wide))
            val t0 = System.currentTimeMillis()
            while (synchronized(done) { id !in done } && System.currentTimeMillis() - t0 < 10_000) delay(5)
            assertTrue(synchronized(done) { id in done }, "flush $id never completed")
        }

        suspend fun drain(label: String, maxFlushes: Int = 40): Int {
            var n = 0
            while (comp.hasPending || comp.needsKeyframe) {
                val a = comp.assembleFlush(Geometry.rectBudget(3)) ?: break
                n++
                ship(a)
                assertTrue(n <= maxFlushes, "$label: did not converge in $maxFlushes flushes")
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

        fun assertBeliefMatchesGlass(label: String) {
            for (left in booleanArrayOf(true, false)) {
                val e = comp.expectedLens(left); val g = lens(left)
                for (y in 0 until 480) for (x in 0 until 640) {
                    assertEquals(e[x, y], g[x, y], "$label: ${if (left) "L" else "R"} ($x,$y) belief != glass")
                }
            }
        }
    }

    /** A lost flush followed by many landed slide copies: the rollback must
     *  stay cheap (the frontier coalesces) and the repair must converge. */
    @Test
    fun rollbackAfterManyCopiesStaysBounded(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            val l = Layout()
            val comp = rig.comp
            comp.composed.fillRect(l.content, 3 * 17)
            comp.planes = listOf(Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0))
            comp.requestKeyframe()
            rig.drain("keyframe")
            val band = Rect(l.content.x, l.lens.bottom, l.content.w - Layout.RAIL_W, l.rowsBelow * Layout.ROW_H)
            // the lost flush: a row inside the band
            val row = Rect(band.x, band.y + band.h - 48, band.w, 16)
            comp.composed.fillRect(row, 14 * 17)
            comp.damage(row)
            val lost = assertNotNull(comp.assembleFlush(5))
            // thirty landed slide steps
            val src = Rect(band.x, band.y + 32, band.w, band.h - 32)
            val dst = Rect(band.x, band.y, band.w, band.h - 32)
            repeat(30) { i ->
                val moved = Gray8(src.w, src.h); moved.blit(comp.composed, src, 0, 0)
                comp.composed.blit(moved, Rect(0, 0, src.w, src.h), dst.x, dst.y)
                comp.composed.fillRect(band.x, band.bottom - 32, band.w, 32, ((i % 5) + 1) * 17)
                comp.declareShift(src, dst)
                comp.damage(Rect(band.x, band.bottom - 32, band.w, 32))
                rig.ship(assertNotNull(comp.assembleFlush(5)))
            }
            val t0 = System.nanoTime()
            comp.rollback(lost)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 200, "rollback after 30 copies took $ms ms")
            val n = rig.drain("repair", maxFlushes = 6)
            assertTrue(n <= 6, "repair took $n flushes")
            rig.assertBeliefMatchesGlass("after rollback")
            assertEquals(emptyList(), rig.failed)
        } finally {
            scope.cancel()
        }
    }

    /** More bytes than one mode-8 batch may carry (bmp_max): the compositor
     *  must spread them over flushes, never hand the transport a batch it
     *  refuses. */
    @Test
    fun batchBytesAreCappedAtAssembly(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            val l = Layout()
            val comp = rig.comp
            val rnd = java.util.Random(5)
            fun grain() {
                comp.composed.clear(0)
                for (y in l.content.y until l.content.bottom) for (x in l.content.x until l.content.right)
                    comp.composed[x, y] = rnd.nextInt(16) * 17
            }
            grain()
            comp.planes = listOf(Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0))
            comp.requestKeyframe()
            rig.drain("grain keyframe", maxFlushes = 60)
            rig.assertBeliefMatchesGlass("grain keyframe")
            // a whole-content change under a lowered batch cap (a displayable
            // frame's own diff rarely exceeds the real one) — must take more
            // than one flush, every batch accepted by the firmware model
            comp.batchMax = 60_000
            grain()
            comp.damageAll()
            val n = rig.drain("grain change", maxFlushes = 60)
            assertTrue(n >= 2, "a change past the batch cap must span flushes (took $n)")
            rig.assertBeliefMatchesGlass("grain change")
            assertEquals(emptyList(), rig.failed, "the transport refused a batch: ${rig.failed}")
        } finally {
            scope.cancel()
        }
    }
}
