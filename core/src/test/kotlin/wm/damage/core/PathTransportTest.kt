package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.LensPanels
import wm.damage.core.transport.LinkState
import wm.damage.core.transport.PathTransport
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/** HANDOFF.md §8.2 "Arbitration": the first path to start wins, the others are
 *  cancelled cleanly; a failed attempt is retried while the race is undecided;
 *  a capability refusal disables a path for good. */
class PathTransportTest {

    /** A path whose start never completes until it is cancelled. */
    private class Stall(val label: String) : Transport {
        @Volatile var cancelled = false
        @Volatile var starts = 0
        override val events = MutableSharedFlow<TransportEvent>()
        override val state = MutableStateFlow(LinkState(transportName = label))
        override val mirror: LensPanels = GlassFirmwareSim()
        override fun injectInput(type: Int) {}
        override suspend fun start(warmupFrame: ByteArray) {
            starts++
            try { awaitCancellation() } finally { cancelled = true }
        }
        override suspend fun submit(flush: FlushRequest): Long = error("never drives")
        override suspend fun clearDiagFlags() {}
        override suspend fun stop() {}
    }

    /** Counts starts and fails the first [failFirst] of them. */
    private class Counting(val inner: Transport, val failFirst: Int = 0) : Transport by inner {
        @Volatile var starts = 0
        override suspend fun start(warmupFrame: ByteArray) {
            starts++
            if (starts <= failFirst) throw IllegalStateException("simulated failure #$starts")
            inner.start(warmupFrame)
        }
    }

    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    @Test
    fun theFirstPathToStartWinsAndTheOtherIsCancelled(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val phone = Stall("remote:phone")
            val sim = GlassFirmwareSim()
            val ble = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val path = PathTransport(listOf(PathTransport.Candidate("remote:phone", phone), PathTransport.Candidate("ble", ble)),
                scope, headStartMs = 0)
            val events = ArrayList<TransportEvent>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                path.events.collect { synchronized(events) { events.add(it) } }
            }
            path.start(warmup())
            assertEquals("ble", path.activeName)
            assertTrue(path.state.value.started, "the winner's state is forwarded")
            assertEquals("sim", path.state.value.transportName)
            assertTrue(phone.cancelled, "the stalled attempt was cancelled")
            assertTrue(path.mirror.exact, "the winner's local mirror is exact")

            // the active path carries flushes; its completion reaches the path's events
            val id = path.submit(FlushRequest(listOf(DisplayOp.Keyframe(warmup())), 1L))
            val t0 = System.currentTimeMillis()
            fun done() = synchronized(events) { events.any { it is TransportEvent.FlushDone && it.id == id && it.ok } }
            while (!done() && System.currentTimeMillis() - t0 < 5_000) delay(5)
            assertTrue(done(), "the flush completed through the active path")
            assertTrue(sim.left.seeded, "the keyframe reached the winner's glasses")
            assertTrue(path.mirror.panel(Arm.LEFT) === sim.panel(Arm.LEFT), "the mirror proxy shows the winner's panels")

            path.injectInput(EvenHubMsg.EV_CLICK)
            val t1 = System.currentTimeMillis()
            fun input() = synchronized(events) { events.any { it is TransportEvent.Input && it.type == EvenHubMsg.EV_CLICK } }
            while (!input() && System.currentTimeMillis() - t1 < 5_000) delay(5)
            assertTrue(input(), "injected input reaches the path's events")

            path.stop()
            assertEquals(null, path.activeName)
            // a new race after a stop: the sim wins again
            path.start(warmup())
            assertEquals("ble", path.activeName)
            path.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aRefusedPathIsDisabledAndAFailedAttemptIsRetried(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stockSim = GlassFirmwareSim().apply { capabilityString = "not-a-cfw" }
            val refusing = Counting(SimTransport(stockSim, scope, SimTransport.Timing(instant = true)))
            val flaky = Counting(SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), failFirst = 1)
            val path = PathTransport(listOf(PathTransport.Candidate("stock", refusing), PathTransport.Candidate("flaky", flaky)),
                scope, headStartMs = 0, retryMs = 100)
            path.start(warmup())
            assertEquals("flaky", path.activeName)
            assertEquals(2, flaky.starts, "the failed attempt was retried while the race was open")
            assertEquals(1, refusing.starts)
            assertTrue("stock" in path.disabled, "the capability refusal disabled the path")
            path.stop()

            path.start(warmup())
            assertEquals("flaky", path.activeName)
            assertEquals(1, refusing.starts, "a disabled path is not tried again")
            path.stop()
        } finally {
            scope.cancel()
        }
    }
}
