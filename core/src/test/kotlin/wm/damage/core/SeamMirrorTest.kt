package wm.damage.core

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.RemoteTransportServer
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/** HANDOFF.md §8.2 "the seam carries the mirror": over a loopback seam the
 *  client's mirror equals the server's sim after every flush, and the panel
 *  update precedes the flush's done message. */
class SeamMirrorTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun clientMirrorTracksTheServerSimAndPanelsPrecedeDone(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val sim = GlassFirmwareSim()
        val inner = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val server = RemoteTransportServer(inner, port, "tok", scope)
        server.start()
        try {
            val client = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            val done = HashSet<Long>()
            val failed = ArrayList<String>()
            val orderingViolations = ArrayList<String>()
            val inputs = ArrayList<Int>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                client.events.collect { ev ->
                    when (ev) {
                        is TransportEvent.FlushDone -> {
                            // at this moment the sim holds exactly this flush (the
                            // test runs one flush at a time): the client mirror
                            // must already show it
                            for (arm in Arm.entries) {
                                if (!client.mirror.panel(arm).contentEquals(sim.panel(arm)))
                                    orderingViolations.add("flush ${ev.id}: $arm panel lagged its done")
                            }
                            synchronized(done) {
                                if (!ev.ok) failed.add("flush ${ev.id}: ${ev.error}")
                                done.add(ev.id)
                            }
                        }
                        is TransportEvent.Input -> synchronized(inputs) { inputs.add(ev.type) }
                        else -> {}
                    }
                }
            }
            client.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
            assertFalse(client.mirror.exact, "a seam-fed mirror is display-only")
            assertTrue(client.state.value.started)

            val comp = Compositor()
            val l = Layout()
            suspend fun ship(a: Compositor.Assembled) {
                val id = client.submit(FlushRequest(a.ops, a.epoch, "", wide = a.wide))
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
                    assertContentEquals(sim.panel(arm), client.mirror.panel(arm), "$label: client mirror != server sim on $arm")
                }
            }

            comp.composed.fillRect(l.topBar, 3 * 17)
            comp.composed.fillRect(l.content, 6 * 17)
            comp.requestKeyframe()
            drain("keyframe")
            comp.composed.fillRect(l.lens, 12 * 17)
            comp.damage(l.lens)
            drain("delta")
            comp.planes = listOf(Compositor.PlaneRegion(l.content, 8), Compositor.PlaneRegion(l.lens, 0))
            drain("stereo")
            assertTrue(sim.panel(Arm.LEFT).any { it.toInt() != 0 }, "the sim painted something")

            // input from the far end reaches the client's event flow
            sim.injectGesture(EvenHubMsg.EV_CLICK)
            val t0 = System.currentTimeMillis()
            while (synchronized(inputs) { inputs.isEmpty() } && System.currentTimeMillis() - t0 < 5_000) delay(5)
            assertEquals(listOf(EvenHubMsg.EV_CLICK), synchronized(inputs) { inputs.toList() })

            assertEquals(emptyList(), failed)
            assertEquals(emptyList(), orderingViolations)
            client.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
