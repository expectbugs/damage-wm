package wm.damage.core

import kotlin.test.Test
import wm.damage.core.transport.Arm
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

/** Review round 5 regressions. */
class Round5Test {

    /** F1: the diag-flag clear resets the firmware's fid baseline; the host
     *  must resync with it, or the next delta manufactures an f_skip. */
    @Test
    fun clearDiagFlagsResyncsTheHostFidSequence(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val done = ArrayList<TransportEvent.FlushDone>()
            val flags = ArrayList<Map<String, Boolean>>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    if (it is TransportEvent.FlushDone) synchronized(done) { done.add(it) }
                    if (it is TransportEvent.DiagFlags) synchronized(flags) { flags.add(it.flags) }
                }
            }
            val g = Gray8(640, 480)
            val full = Zl.encodeCfw(Pack.rect(g, Rect(0, 0, 640, 480)))
            t.start(full)
            t.submit(FlushRequest(listOf(DisplayOp.Keyframe(full)), 1, wide = true))
            g.fillRect(100, 100, 40, 20, 8 * 17)
            val p = Zl.encodeCfw(Pack.rect(g, Rect(100, 100, 40, 20)))
            repeat(4) { i -> t.submit(FlushRequest(listOf(DisplayOp.Delta(Rect(100 + 48 * i, 100, 40, 20), p)), 2L + i)) }
            waitFor { synchronized(done) { done.size } >= 5 }
            t.clearDiagFlags()
            // deltas after the clear must continue the FIRMWARE's sequence
            repeat(3) { i -> t.submit(FlushRequest(listOf(DisplayOp.Delta(Rect(100 + 48 * i, 200, 40, 20), p)), 10L + i)) }
            waitFor { synchronized(done) { done.size } >= 8 }
            assertTrue(synchronized(done) { done.all { it.ok } }, "a flush failed: ${done.filter { !it.ok }}")
            for (arm in Arm.entries) {
                assertTrue(sim.flags(arm).none { it.value }, "sticky flags on $arm after clearDiagFlags: ${sim.flags(arm)}")
            }
            assertTrue(synchronized(flags) { flags.none { f -> f.any { it.value } } }, "DiagFlags raised: $flags")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** Rollback marks what a lost flush touched UNKNOWN instead of restoring
     *  bytes: with flush A lost and a later flush B landed, the glass must
     *  still end equal to the truth once everything drains. */
    @Test
    fun lostFlushBetweenLandedOnesConvergesToTruth(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val done = HashSet<Long>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.FlushDone) synchronized(done) { done.add(it.id) } }
            }
            t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
            val l = Layout()
            val comp = Compositor()
            comp.planes = listOf(Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0))
            comp.composed.fillRect(l.content, 4 * 17)
            comp.composed.fillRect(l.lens, 12 * 17)
            comp.requestKeyframe()
            suspend fun ship(a: Compositor.Assembled) {
                val id = t.submit(FlushRequest(a.ops, a.epoch, "", wide = a.wide))
                waitFor { synchronized(done) { id in done } }
            }
            while (comp.hasPending || comp.needsKeyframe) ship(assertNotNull(comp.assembleFlush(5)))

            // A: a row highlight — assembled but LOST (never reaches the sim)
            comp.composed.fillRect(l.content.x, l.content.y + 40, l.content.w, 16, 15 * 17)
            comp.damage(Rect(l.content.x, l.content.y + 40, l.content.w, 16))
            val a = assertNotNull(comp.assembleFlush(5))
            // B: a slide of the band below the lens — LANDS
            val band = Rect(l.content.x, l.lens.bottom, l.content.w - Layout.RAIL_W, l.rowsBelow * Layout.ROW_H)
            val src = Rect(band.x, band.y + 32, band.w, band.h - 32)
            val dst = Rect(band.x, band.y, band.w, band.h - 32)
            comp.composed.fillRect(band.x, band.y + 64, band.w, 16, 9 * 17)   // something to move
            comp.damage(Rect(band.x, band.y + 64, band.w, 16))
            val moved = Gray8(src.w, src.h); moved.blit(comp.composed, src, 0, 0)
            comp.composed.blit(moved, Rect(0, 0, src.w, src.h), dst.x, dst.y)
            comp.composed.fillRect(band.x, band.bottom - 32, band.w, 32, 2 * 17)
            comp.declareShift(src, dst)
            comp.damage(Rect(band.x, band.bottom - 32, band.w, 32))
            val b = assertNotNull(comp.assembleFlush(5))
            ship(b)
            comp.rollback(a)          // A reported lost AFTER B landed
            while (comp.hasPending || comp.needsKeyframe) ship(assertNotNull(comp.assembleFlush(5)))

            // the glass must show the truth of the CURRENT frame
            for (left in booleanArrayOf(true, false)) {
                val glass = lens(sim, left)
                val expected = comp.expectedLens(left)
                for (y in 0 until 480) for (x in 0 until 640) {
                    assertEquals(expected[x, y], glass[x, y], "${if (left) "L" else "R"} ($x,$y) belief != glass")
                }
                val d = if (left) -8 else 8
                // the lost highlight row, at the content shift
                for (x in l.content.x + 16 until l.content.right - 16) {
                    assertEquals(15 * 17, glass[x + d, l.content.y + 44], "${if (left) "L" else "R"} highlight row missing at ${x + d}")
                }
            }
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    private fun lens(sim: GlassFirmwareSim, left: Boolean): Gray8 {
        val ctx = if (left) sim.left else sim.right
        val g = Gray8(640, 480)
        for (y in 0 until 480) for (x in 0 until 640) {
            val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
            val n = if (x and 1 == 0) b shr 4 else b and 0x0F
            g[x, y] = n * 17
        }
        return g
    }

    private suspend fun waitFor(cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 10_000) delay(5)
        assertTrue(cond(), "condition never became true")
    }
}
