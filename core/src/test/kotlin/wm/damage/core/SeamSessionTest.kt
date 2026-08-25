package wm.damage.core

import java.net.ServerSocket
import kotlin.test.Test
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
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.RemoteTransportServer
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent

/**
 * HANDOFF.md §8.2 amendment 10 (round 2, b2-1; round 3, a3): a seam that ends
 * answers every outstanding flush as failed BEFORE its link-down, exactly
 * once; a link loss on the far end lowers the client's `started` (the session
 * keeper's restart signal) and is reported with its reason; the same client
 * reconnects afterwards.
 */
class SeamSessionTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    /** A far end whose flushes are accepted and never completed (a phone whose
     *  radio stopped answering). */
    private class Hanging(inner: Transport) : Transport by inner {
        private val ids = java.util.concurrent.atomic.AtomicLong(100)
        override suspend fun submit(flush: FlushRequest): Long = ids.getAndIncrement()
    }

    /** A sim-backed far-end transport whose link the test can end. */
    private class Fragile(val glass: GlassFirmwareSim, scope: CoroutineScope) : CfwTransportBase(scope, "fragile") {
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
        fun endLink(reason: String) = onLinkDown(reason)
    }

    @Test
    fun aSeamThatEndsFailsTheOutstandingFlushOnceBeforeItsLinkDown(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val inner = Hanging(SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)))
        val server = RemoteTransportServer(inner, port, "tok", scope)
        server.start()
        try {
            val client = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            val events = ArrayList<TransportEvent>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                client.events.collect { synchronized(events) { events.add(it) } }
            }
            client.start(warmup())
            assertTrue(client.state.value.started)
            val id = client.submit(FlushRequest(listOf(DisplayOp.Keyframe(warmup())), 1L))
            delay(300)
            assertTrue(synchronized(events) { events.none { it is TransportEvent.FlushDone } }, "the far end holds the flush")

            server.close()                                  // the phone app went away mid-flush
            val t0 = System.currentTimeMillis()
            fun linkDown() = synchronized(events) { events.any { it is TransportEvent.Link && !it.connected } }
            while (!linkDown() && System.currentTimeMillis() - t0 < 5_000) delay(10)
            assertTrue(linkDown(), "the link end was reported")
            delay(200)                                      // anything late would show up here
            val seq = synchronized(events) { events.toList() }
            val doneIx = seq.indexOfFirst { it is TransportEvent.FlushDone && it.id == id }
            val downIx = seq.indexOfFirst { it is TransportEvent.Link && !it.connected }
            assertTrue(doneIx >= 0, "the outstanding flush was answered: $seq")
            val done = seq[doneIx] as TransportEvent.FlushDone
            assertFalse(done.ok, "answered as failed")
            assertTrue("${done.error}".contains("seam link ended"), "with the reason: ${done.error}")
            assertTrue(doneIx < downIx, "the failed flush precedes the link-down, so the shell rolls back first: $seq")
            assertEquals(1, seq.count { it is TransportEvent.FlushDone }, "answered exactly once: $seq")
            assertFalse(client.state.value.started, "the session is over for the keeper's poll")
            client.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun aFarEndLinkLossLowersStartedReportsItAndTheClientReconnects(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val glass = GlassFirmwareSim()
        val inner = Fragile(glass, scope)
        val server = RemoteTransportServer(inner, port, "tok", scope)
        server.start()
        try {
            val client = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            val links = ArrayList<TransportEvent.Link>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                client.events.collect { if (it is TransportEvent.Link) synchronized(links) { links.add(it) } }
            }
            client.start(warmup())
            assertTrue(client.state.value.started)
            assertEquals(1, glass.preludeAcks)

            inner.endLink("test: out of range")
            val t0 = System.currentTimeMillis()
            fun down() = synchronized(links) { links.any { !it.connected && it.detail.contains("out of range") } }
            while (!(down() && !client.state.value.started) && System.currentTimeMillis() - t0 < 5_000) delay(10)
            assertTrue(down(), "the far end's link end reached the client with its reason: $links")
            assertFalse(client.state.value.started, "the keeper's poll sees the session end")

            // the keeper's next attempt: the same client, a fresh session. The
            // server releases its driver slot LAST during teardown (round 3 D6),
            // so an attempt that lands inside the teardown is answered "busy"
            // — the keeper's pause covers that; here a short retry does.
            client.stop()
            var attempts = 0
            while (true) {
                attempts++
                try { client.start(warmup()); break } catch (e: IllegalStateException) {
                    assertTrue("driven by" in "${e.message}" && attempts < 50, "unexpected: ${e.message}")
                    delay(100)
                }
            }
            assertTrue(client.state.value.started)
            assertEquals(2, glass.preludeAcks, "the far end connected the glasses again")
            client.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
