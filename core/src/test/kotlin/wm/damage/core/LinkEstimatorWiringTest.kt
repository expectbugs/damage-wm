package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.LinkState
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.RemoteTransportServer
import wm.damage.core.transport.WireState
import wm.damage.core.transport.floorMeasured
import wm.damage.core.transport.transferMeasured
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.CfwModes

/**
 * `HANDOFF.md` §73 — the estimator where it is fed: which flushes the transport calls isolated,
 * what a session start does to the readings, and that the count of open ack clocks cannot stay
 * open. The arithmetic is `LinkEstimatorTest`'s; this is the lane's.
 *
 * The rig holds the glasses' answers until the test releases them and moves the transport's
 * clock by hand, so an ack time is exactly what the test says it is.
 */
class LinkEstimatorWiringTest {

    private class HeldTransport(val glass: GlassFirmwareSim, scope: CoroutineScope) : CfwTransportBase(scope, "held") {
        @Volatile var clock = 1_000_000L
        /** While set, the glasses' answers wait in [held]. A test sets it directly; it is read and
         *  cleared under [held]'s lock. */
        @Volatile var hold = false
        private val held = ArrayList<Pair<Arm, ByteArray>>()
        override val instant: Boolean get() = true
        override fun nowMs(): Long = clock

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) {
                    // decided and queued in ONE step: an answer that read "held" a moment before a
                    // release (or a discard) must not join the list after it was emptied
                    synchronized(held) { if (hold) { held.add(arm to packet); return } }
                    onNotifyPacket(arm, packet)
                }
                override fun panelChanged(arm: Arm) {}
            })
        }

        /** One shot: the next completion parks here — after its ack arrived (the window slot is
         *  back) and before it has read its ack time (its ack clock is still open). */
        @Volatile var deliveredGate: CompletableDeferred<Unit>? = null
        @Volatile var parked = false
        override suspend fun onImageDelivered() {
            val g = deliveredGate ?: return
            deliveredGate = null
            parked = true
            g.await()
        }

        /** Everything the glasses said while held, in the order they said it, and nothing they say
         *  meanwhile ahead of it: answers stay held until the last kept one has been handed over.
         *  The class sweep (0.64 R1; review 1a, RB-8) of the defect measured and fixed at
         *  `OverlayCachedPaintTest.HeldTransport.release`; this rig has no shell answering a
         *  completion, so only the list's lock applied here. Nothing was seen to fail for it. */
        fun release() {
            while (true) {
                val next = synchronized(held) { if (held.isEmpty()) { hold = false; null } else held.removeAt(0) } ?: return
                onNotifyPacket(next.first, next.second)
            }
        }
        /** The link ended with those answers in the air. */
        fun discard() { synchronized(held) { hold = false; held.clear() } }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() { glass.linkReset() }
        override suspend fun writeArm(arm: Arm, packet: ByteArray) { glass.write(arm, packet, clock) }
        override fun onMaintenanceTick() {
            glass.tick(clock)
            val ok = glass.leaseHeld(Arm.LEFT, clock) && glass.leaseHeld(Arm.RIGHT, clock)
            if (!ok && state.value.started) setLease(false, "lease LOST") else if (ok) setLease(true, "lease held")
        }
        fun endLink(reason: String) = onLinkDown(reason)
    }

    private class Rig(val scope: CoroutineScope) {
        val sim = GlassFirmwareSim()
        val t = HeldTransport(sim, scope)
        var comp = Compositor()
        val l = Layout()
        val done = HashMap<Long, TransportEvent.FlushDone>()
        private var row = 0

        init {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.FlushDone) synchronized(done) { done[it.id] = it } }
            }
        }

        suspend fun start() = t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))

        suspend fun until(what: String, cond: () -> Boolean) {
            waitUntil(10_000, 2, { what }) { cond() }
        }

        suspend fun await(id: Long): TransportEvent.FlushDone {
            until("flush $id completes") { synchronized(done) { id in done } }
            return synchronized(done) { done.getValue(id) }
        }

        /** A small display flush: one 16-row band of the content repainted. */
        fun small(): FlushRequest {
            val r = Rect(l.content.x, l.content.y + 16 + 16 * (row++ % 12), 64, 16)
            comp.composed.fillRect(r, ((row % 14) + 1) * 17); comp.damage(r)
            val a = assertNotNull(comp.assembleFlush(5))
            return FlushRequest(a.ops, a.epoch, "small", wide = a.wide)
        }

        /** A 3 KB cache write (bytes that do not matter to anything that draws). */
        fun big(): FlushRequest {
            val data = ByteArray(3000) { (it * 31 + 7).toByte() }
            return FlushRequest(listOf(DisplayOp.CacheWrite(CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(1024, data))))), comp.epoch, "ATLAS", writer = "test")
        }

        /** A cache write as small as a chrome tick (the size of a one-unit table patch). */
        fun smallCacheWrite(): FlushRequest {
            val data = ByteArray(60) { (it * 13 + 5).toByte() }
            return FlushRequest(listOf(DisplayOp.CacheWrite(CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(1024, data))))), comp.epoch, "ATLAS", writer = "test")
        }

        fun keyframe(): FlushRequest {
            comp.requestKeyframe()
            val a = assertNotNull(comp.assembleFlush(5))
            return FlushRequest(a.ops, a.epoch, "keyframe", wide = a.wide)
        }

        /** Submit [flushes] back to back with the glasses' answers held, then let [ackMs] pass and release. */
        suspend fun timed(ackMs: Long, vararg flushes: FlushRequest): List<TransportEvent.FlushDone> {
            t.hold = true
            val before = t.state.value.inFlight
            val ids = flushes.map { t.submit(it) }
            until("${flushes.size} flush(es) written and un-acked (in flight ${t.state.value.inFlight}, was $before)") { t.state.value.inFlight >= before + flushes.size }
            t.clock += ackMs
            t.release()
            return ids.map { await(it) }
        }
    }

    @Test
    fun onlyAFlushAloneOnTheLaneIsAnObservationAndASessionStartsOver(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            var st = rig.t.state.value
            assertEquals(0, st.floorSamples, "a session starts unmeasured"); assertEquals(0, st.transferSamples)
            assertEquals(60.0, st.floorMsEma); assertEquals(20.0, st.transferMsPerKbEma)

            // the keyframe and three small flushes, each alone, each acked 58 ms after the lane took it
            var floors = 0
            for (f in listOf(rig.keyframe(), rig.small(), rig.small(), rig.small())) {
                val d = rig.timed(58, f).single()
                assertTrue(d.ok, "$d"); assertEquals(58L, d.ackMs, "the rig's clock is the ack time")
                if (d.bytes < 400) floors++
            }
            assertTrue(floors >= 3, "at least three of them were small ($floors)")
            st = rig.t.state.value
            assertEquals(floors, st.floorSamples); assertEquals(58.0, st.floorMsEma, "the floor is the isolated small flushes' median")

            // a small flush written BEHIND a 3 KB one that is still un-acked: both acked 324 ms later
            // (12:06 on 2026-11-09: a 33 B flush acked in 324 ms moved the EMA floor 117 -> 179)
            val (big, queued) = rig.timed(324, rig.big(), rig.small())
            assertTrue(big.ok && queued.ok, "$big $queued")
            assertTrue(queued.bytes < 400 && queued.ackMs >= 324, "the queued flush booked the big one's time: $queued")
            st = rig.t.state.value
            assertEquals(floors, st.floorSamples, "a flush that was not alone on the lane is no floor observation")
            assertEquals(58.0, st.floorMsEma, "the floor stands")
            assertEquals(1, st.transferSamples, "the big flush WAS alone when the lane took it: one transfer observation")
            assertTrue(st.ackMsEma > 60.0, "the all-sizes readout still takes every acked flush: ${st.ackMsEma}")

            // and the lane reads as clear again afterwards: the next lone flush is an observation
            assertTrue(rig.timed(58, rig.small()).single().ok)
            assertEquals(floors + 1, rig.t.state.value.floorSamples, "the ack clocks closed")

            // a flush in flight when the link ends: swept, never acked
            rig.t.hold = true
            val before = rig.t.state.value.inFlight
            val lost = rig.t.submit(rig.small())
            rig.until("the flush is written") { rig.t.state.value.inFlight > before }
            rig.t.endLink("test: supervision timeout")
            rig.t.discard()
            assertFalse(rig.await(lost).ok, "the swept flush failed")
            rig.t.stop()

            // a new session measures its own link: both terms back at their defaults, in the state at once
            rig.comp = Compositor()
            rig.start()
            st = rig.t.state.value
            assertEquals(0, st.floorSamples, "the floor starts over with the session"); assertEquals(0, st.transferSamples)
            assertEquals(60.0, st.floorMsEma); assertEquals(20.0, st.transferMsPerKbEma)
            // and the swept flush did not leave an ack clock open: the first lone small flush is an observation
            var after = 0
            for (f in listOf(rig.keyframe(), rig.small())) if (rig.timed(70, f).single().bytes < 400) after++
            assertTrue(after >= 1)
            assertEquals(after, rig.t.state.value.floorSamples, "a lone flush after the restart is an observation")
            rig.t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** A table patch is a cache write of a few dozen bytes. Its ack floor against a display
     *  flush's is not measured, so it is never a floor observation, alone on the lane or not. */
    @Test
    fun aLoneSmallCacheWriteIsNoFloorObservation(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            assertTrue(rig.timed(58, rig.keyframe()).single().ok)
            val base = rig.t.state.value.floorSamples
            repeat(4) {
                val d = rig.timed(200, rig.smallCacheWrite()).single()
                assertTrue(d.ok && d.bytes < 400 && d.ackMs == 200L, "a small cache write, alone, acked in 200 ms: $d")
            }
            assertEquals(base, rig.t.state.value.floorSamples, "four lone small cache writes are no floor observations")
            assertEquals(60.0.takeIf { base < 3 } ?: 58.0, rig.t.state.value.floorMsEma, "and the floor did not move")
            // the same lane DOES take a display flush of that size
            val d = rig.timed(58, rig.small()).single()
            assertTrue(d.ok && d.bytes < 400, "$d")
            assertEquals(base + 1, rig.t.state.value.floorSamples, "a lone small display flush is one")
            rig.t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** The window's slot comes back when the ack ARRIVES; the completion reads its ack time a
     *  dispatch later, behind the completions before it. A flush the lane takes in that gap sees
     *  every slot free and is still behind a flush whose time is not yet read — the count of open
     *  ack clocks is what says so. Mutation: `alone` from the window alone counts both. */
    @Test
    fun aFlushTakenWhileAnAckClockIsStillOpenIsNotAlone(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope); rig.start()
            assertTrue(rig.timed(58, rig.keyframe()).single().ok)
            repeat(2) { assertTrue(rig.timed(58, rig.small()).single().ok) }
            val base = rig.t.state.value.floorSamples

            // A: acked at once, its completion parked between the ack and the reading of its time
            val gate = CompletableDeferred<Unit>()
            rig.t.parked = false
            rig.t.deliveredGate = gate
            val a = rig.t.submit(rig.small())
            rig.until("A is acked and its completion is parked") { rig.t.parked && rig.t.state.value.inFlight == 0 }
            // B: the lane takes it with every window slot free and A's clock open
            rig.t.hold = true
            val b = rig.t.submit(rig.small())
            rig.until("B is written and un-acked") { rig.t.state.value.inFlight == 1 }
            rig.t.clock += 58
            rig.t.release()
            gate.complete(Unit)
            val da = rig.await(a); val db = rig.await(b)
            assertTrue(da.ok && db.ok && da.bytes < 400 && db.bytes < 400, "$da $db")
            assertEquals(base + 1, rig.t.state.value.floorSamples,
                "A was alone when the lane took it; B was taken behind A's open ack clock and is no observation")

            // both clocks closed: the next lone flush is an observation again
            assertTrue(rig.timed(58, rig.small()).single().ok)
            assertEquals(base + 2, rig.t.state.value.floorSamples, "the ack clocks closed")
            rig.t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** The observation counts cross the seam (a shell on the PC reads the phone's link state), and
     *  a peer on a build that does not send them reads as "not reported": its two numbers are taken
     *  as measured, so nothing downstream calls a real reading a default. */
    @Test
    fun theObservationCountsCrossTheSeamAndAnOlderPeerReadsAsNotReported(): Unit = runBlocking {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString(WireState.serializer(), """{"connected":true,"started":true,"leaseHeld":true,"inFlight":0,""" +
            """"window":3,"ackMsEma":120.0,"bytesPerSecEma":9000.0,"capability":null,"rssiDbm":null,"transportName":"phone",""" +
            """"floorMsEma":112.0,"transferMsPerKbEma":66.0}""")
        assertEquals(-1, old.floorSamples); assertEquals(-1, old.transferSamples)
        assertTrue(LinkState(floorSamples = -1, transferSamples = -1).let { it.floorMeasured && it.transferMeasured }, "not reported: taken at its word")
        assertFalse(LinkState(floorSamples = 2, transferSamples = 0).let { it.floorMeasured || it.transferMeasured }, "under three observations: a default")
        assertTrue(LinkState(floorSamples = 3, transferSamples = 3).let { it.floorMeasured && it.transferMeasured })
        val sent = json.encodeToString(WireState.serializer(), old.copy(floorSamples = 7, transferSamples = 4))
        assertTrue("\"floorSamples\":7" in sent && "\"transferSamples\":4" in sent, sent)

        // and end to end: the far end's counts are the client's
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = java.net.ServerSocket(0).use { it.localPort }
        val rig = Rig(scope)
        val server = RemoteTransportServer(rig.t, port, "tok", scope)
        server.start()
        try {
            val client = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            client.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
            rig.until("the session's state reached the client") { client.state.value.started && client.state.value.floorSamples == 0 }
            assertFalse(client.state.value.floorMeasured, "a session's defaults read as defaults across the seam")
            assertTrue(rig.timed(58, rig.keyframe()).single().ok)
            repeat(3) { assertTrue(rig.timed(58, rig.small()).single().ok) }
            val far = rig.t.state.value.floorSamples
            assertTrue(far >= 3, "the far end has a floor ($far)")
            rig.until("the counts crossed (client ${client.state.value.floorSamples}, far end $far)") { client.state.value.floorSamples == far }
            assertTrue(client.state.value.floorMeasured)
            assertEquals(58.0, client.state.value.floorMsEma)
            client.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
