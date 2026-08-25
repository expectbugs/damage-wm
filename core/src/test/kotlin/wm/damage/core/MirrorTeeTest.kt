package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
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
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/**
 * HANDOFF.md §8.2 "Mirror": a hardware-style transport keeps a private
 * firmware model fed the exact bytes it writes. Here the "glasses" are a
 * SEPARATE sim behind the wire seam; after every flush the transport's mirror
 * must hold exactly what that sim holds, on both lenses, byte for byte.
 */
class MirrorTeeTest {

    /** A transport whose wire ends in its own GlassFirmwareSim (standing in for
     *  the real pair); the base class tees into a second, private mirror. */
    private class TeeTransport(val glass: GlassFirmwareSim, scope: CoroutineScope) :
        CfwTransportBase(scope, "tee") {
        override val instant: Boolean get() = true

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() {}
        override suspend fun writeArm(arm: Arm, packet: ByteArray) { glass.write(arm, packet, nowMs()) }
        override fun onMaintenanceTick() { glass.tick(nowMs()) }
    }

    private fun paintScene(g: Gray8, l: Layout, seed: Int) {
        g.clear(0)
        g.fillRect(l.topBar, 3 * 17)
        var y = l.content.y
        var k = seed
        while (y < l.content.bottom) {
            g.fillRect(l.content.x, y, l.content.w, minOf(16, l.content.bottom - y), ((k % 14) + 1) * 17)
            y += 16; k++
        }
    }

    @Test
    fun mirrorEqualsTheGlassAfterEveryFlush(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val glass = GlassFirmwareSim()
            val t = TeeTransport(glass, scope)
            val done = HashSet<Long>()
            val failed = ArrayList<String>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    if (it is TransportEvent.FlushDone) synchronized(done) {
                        if (!it.ok) failed.add("flush ${it.id}: ${it.error}")
                        done.add(it.id)
                    }
                    if (it is TransportEvent.Fault) failed.add("fault ${it.what}: ${it.detail}")
                }
            }
            t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
            assertNotSame(glass, t.mirror, "the mirror is a private model, not the wire's far end")
            assertTrue(t.mirror.exact, "a local mirror is exact")

            val comp = Compositor()
            val l = Layout()
            suspend fun ship(a: Compositor.Assembled) {
                val id = t.submit(FlushRequest(a.ops, a.epoch, "", wide = a.wide))
                val t0 = System.currentTimeMillis()
                while (synchronized(done) { id !in done } && System.currentTimeMillis() - t0 < 10_000) delay(5)
                assertTrue(synchronized(done) { id in done }, "flush $id never completed")
            }
            suspend fun drain(label: String) {
                var n = 0
                while (comp.hasPending || comp.needsKeyframe) {
                    val a = comp.assembleFlush(Geometry.rectBudget(3)) ?: break
                    ship(a)
                    assertTrue(++n < 40, "$label: did not converge")
                }
                for (arm in Arm.entries) {
                    assertContentEquals(glass.panel(arm), t.mirror.panel(arm), "$label: mirror != glass on $arm")
                }
                assertTrue(glass.panel(Arm.LEFT).any { it.toInt() != 0 }, "$label: the glass is blank")
            }

            // keyframe, a delta, a declared shift (copy), a stereo plane map
            paintScene(comp.composed, l, 1)
            comp.requestKeyframe()
            drain("keyframe")
            comp.composed.fillRect(l.lens, 12 * 17)
            comp.damage(l.lens)
            drain("delta")
            val band = Rect(l.content.x, l.lens.bottom, l.content.w - Layout.RAIL_W, l.rowsBelow * Layout.ROW_H)
            val src = Rect(band.x, band.y + 32, band.w, band.h - 32)
            val dst = Rect(band.x, band.y, band.w, band.h - 32)
            val tmp = Gray8(src.w, src.h); tmp.blit(comp.composed, src, 0, 0)
            comp.composed.blit(tmp, Rect(0, 0, src.w, src.h), dst.x, dst.y)
            comp.composed.fillRect(band.x, band.bottom - 32, band.w, 32, 5 * 17)
            comp.declareShift(src, dst)
            comp.damage(Rect(band.x, band.bottom - 32, band.w, 32))
            drain("shift")
            comp.planes = listOf(Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0))
            drain("stereo")

            assertEquals(emptyList(), failed, "faults or failed flushes during the tee run")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun injectedInputReachesTheEventFlow(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = TeeTransport(GlassFirmwareSim(), scope)
            val seen = ArrayList<TransportEvent.Input>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.Input) synchronized(seen) { seen.add(it) } }
            }
            t.injectInput(EvenHubMsg.EV_CLICK)
            t.injectInput(EvenHubMsg.EV_SCROLL_BOTTOM)
            val t0 = System.currentTimeMillis()
            while (synchronized(seen) { seen.size } < 2 && System.currentTimeMillis() - t0 < 5_000) delay(5)
            val got = synchronized(seen) { seen.toList() }
            assertEquals(listOf(EvenHubMsg.EV_CLICK, EvenHubMsg.EV_SCROLL_BOTTOM), got.map { it.type })
            assertTrue(got.all { it.source == EvenHubMsg.SRC_RING }, "injected gestures are ring gestures")
        } finally {
            scope.cancel()
        }
    }
}
