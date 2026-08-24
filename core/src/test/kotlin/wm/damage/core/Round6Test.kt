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

/** Review round 6 regressions: convergence, rect economy, payload caps. */
class Round6Test {

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

        /** Drain; returns (flushes, fids). */
        suspend fun drain(label: String, maxFlushes: Int = 40): Pair<Int, Int> {
            var n = 0; var fids = 0
            while (comp.hasPending || comp.needsKeyframe) {
                val a = comp.assembleFlush(Geometry.rectBudget(3)) ?: break
                n++
                fids += a.ops.count { it is DisplayOp.Delta || it is DisplayOp.StereoPair }
                val id = t.submit(FlushRequest(a.ops, a.epoch, label, wide = a.wide))
                val t0 = System.currentTimeMillis()
                while (synchronized(done) { id !in done } && System.currentTimeMillis() - t0 < 10_000) delay(5)
                assertTrue(synchronized(done) { id in done }, "$label: flush $id never completed")
                assertTrue(n <= maxFlushes, "$label: did not converge in $maxFlushes flushes")
            }
            return n to fids
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

    /** Cell-granular random content in the seam columns of the ordinary
     *  "box over a list" map converged never (40 wide flushes) in round 6. */
    @Test
    fun cellNoiseUnderABoxConverges(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            val l = Layout()
            val box = Rect(l.notificationMax.x, l.notificationMax.y, l.notificationMax.w, 104)
            val rnd = java.util.Random(7)
            fun noise(g: Gray8) {
                for (cy in 0 until 480 / 2) for (cx in 0 until 640 / 4) {
                    val v = if (rnd.nextBoolean()) 0 else (rnd.nextInt(15) + 1) * 17
                    g.fillRect(cx * 4, cy * 2, 4, 2, v)
                }
            }
            for (boxPlane in intArrayOf(8, 0)) {
                val comp = rig.comp
                noise(comp.composed)
                comp.planes = listOf(
                    Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0),
                    Compositor.PlaneRegion(box, boxPlane),
                )
                comp.requestKeyframe()
                val (n1, _) = rig.drain("keyframe box@$boxPlane")
                assertTrue(n1 <= 12, "keyframe over cell noise took $n1 flushes")
                rig.assertBeliefMatchesGlass("keyframe box@$boxPlane")
                noise(comp.composed)
                comp.damageAll()
                val (n2, _) = rig.drain("damageAll box@$boxPlane")
                assertTrue(n2 <= 12, "damageAll over cell noise took $n2 flushes")
                rig.assertBeliefMatchesGlass("damageAll box@$boxPlane")
                // and a small later change must not inherit an endless residual
                comp.composed.fillRect(l.content.x, l.content.bottom - 40, 200, 16, 13 * 17)
                comp.damage(Rect(l.content.x, l.content.bottom - 40, 200, 16))
                val (n3, _) = rig.drain("small change box@$boxPlane")
                assertTrue(n3 <= 3, "a small change took $n3 flushes")
                rig.assertBeliefMatchesGlass("small change box@$boxPlane")
            }
            assertEquals(emptyList(), rig.failed)
        } finally {
            scope.cancel()
        }
    }

    /** A text-shaped line (scattered marks) at the content plane must be a
     *  handful of rects and ONE pipelined flush, not dozens of wide ones —
     *  and assembly must stay fast on a page of them. */
    @Test
    fun textShapedDamageStaysWithinTheBudgetAndFast(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            val l = Layout()
            val comp = rig.comp
            comp.composed.fillRect(l.content, 0)
            comp.planes = listOf(Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0))
            comp.requestKeyframe()
            rig.drain("keyframe")
            val rnd = java.util.Random(3)
            fun textLine(y: Int) {
                var x = l.content.x + 16
                while (x < l.content.right - 40) {
                    val w = 2 + rnd.nextInt(4); val h = 4 + rnd.nextInt(16)
                    comp.composed.fillRect(x, y + 4 + rnd.nextInt(8), w, h, (6 + rnd.nextInt(9)) * 17)
                    x += w + 1 + rnd.nextInt(5)
                }
            }
            // one line
            textLine(l.content.y + 60)
            comp.damage(Rect(l.content.x, l.content.y + 60, l.content.w, 32))
            val a = assertNotNull(comp.assembleFlush(Geometry.rectBudget(3)))
            val fids = a.ops.count { it is DisplayOp.Delta || it is DisplayOp.StereoPair }
            assertTrue(fids <= Geometry.rectBudget(3), "one text line cost $fids fids")
            assertTrue(!a.wide, "one text line ran wide")
            val id = rig.t.submit(FlushRequest(a.ops, a.epoch, "line", wide = a.wide))
            while (synchronized(rig.done) { id !in rig.done }) delay(5)
            rig.drain("line rest")
            rig.assertBeliefMatchesGlass("one line")
            // a page of twelve lines: bounded flushes and bounded assembly time
            for (i in 0 until 12) textLine(l.content.y + 80 + i * 26)
            comp.damage(l.content)
            val t0 = System.nanoTime()
            val first = assertNotNull(comp.assembleFlush(Geometry.rectBudget(3)))
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 400, "page assembly took $ms ms")
            val id2 = rig.t.submit(FlushRequest(first.ops, first.epoch, "page", wide = first.wide))
            while (synchronized(rig.done) { id2 !in rig.done }) delay(5)
            val (n, _) = rig.drain("page rest")
            assertTrue(n <= 4, "a page of text took ${n + 1} flushes")
            rig.assertBeliefMatchesGlass("page")
            assertEquals(emptyList(), rig.failed)
        } finally {
            scope.cancel()
        }
    }

    /** Content that compresses past the 16-bit sub-message length must still
     *  reach the glass: the keyframe ships bare, oversize deltas split. */
    @Test
    fun incompressibleContentStillShips(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            val l = Layout()
            val comp = rig.comp
            // dark 3-level grain: ~66 KB compressed — past a mode-8
            // sub-message's 16-bit length, well under the bare mode-6 cap
            val rnd = java.util.Random(11)
            for (y in 0 until 480) for (x in 0 until 640) comp.composed[x, y] = rnd.nextInt(3) * 17
            comp.planes = listOf(Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0))
            comp.requestKeyframe()
            val (n, _) = rig.drain("grain keyframe", maxFlushes = 60)
            assertTrue(n >= 2, "an oversize keyframe must ship bare, follow-ups next flush")
            rig.assertBeliefMatchesGlass("grain keyframe")
            // and a content-sized delta of 4-level grain (split if oversize)
            for (y in l.content.y until l.content.bottom) for (x in l.content.x until l.content.right)
                comp.composed[x, y] = rnd.nextInt(4) * 17
            comp.damage(l.content)
            rig.drain("grain delta", maxFlushes = 60)
            rig.assertBeliefMatchesGlass("grain delta")
            assertEquals(emptyList(), rig.failed, "flush failures: ${rig.failed}")
        } finally {
            scope.cancel()
        }
    }

    /** A lost delta whose pixels a later landed copy has MOVED: the unknown
     *  marks must move with them, or the moved rows stay stale forever. */
    @Test
    fun lostDeltaThenCopyMovesTheUnknownMarks(): Unit = runBlocking {
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
            // A: highlight a row inside the band — assembled, LOST
            val row = Rect(band.x, band.y + 64, band.w, 16)
            comp.composed.fillRect(row, 14 * 17)
            comp.damage(row)
            val a = assertNotNull(comp.assembleFlush(5))
            // B: slide the band up 32 px — LANDS
            val src = Rect(band.x, band.y + 32, band.w, band.h - 32)
            val dst = Rect(band.x, band.y, band.w, band.h - 32)
            val moved = Gray8(src.w, src.h); moved.blit(comp.composed, src, 0, 0)
            comp.composed.blit(moved, Rect(0, 0, src.w, src.h), dst.x, dst.y)
            comp.composed.fillRect(band.x, band.bottom - 32, band.w, 32, 1 * 17)
            comp.declareShift(src, dst)
            comp.damage(Rect(band.x, band.bottom - 32, band.w, 32))
            val b = assertNotNull(comp.assembleFlush(5))
            val idB = rig.t.submit(FlushRequest(b.ops, b.epoch, "B", wide = b.wide))
            while (synchronized(rig.done) { idB !in rig.done }) delay(5)
            comp.rollback(a)
            rig.drain("repair")
            rig.assertBeliefMatchesGlass("after lost delta + landed copy")
            // the highlight now sits 32 px higher, at the content shift
            for (left in booleanArrayOf(true, false)) {
                val g = rig.lens(left)
                val d = if (left) -8 else 8
                for (x in row.x + 8 until row.right - 8)
                    assertEquals(14 * 17, g[x + d, row.y - 32 + 8], "${if (left) "L" else "R"} moved highlight missing at ${x + d}")
            }
            assertEquals(emptyList(), rig.failed)
        } finally {
            scope.cancel()
        }
    }
}
