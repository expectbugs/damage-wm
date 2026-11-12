package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.LaunchMsg

/** HANDOFF.md §8.2 "Prelude": the sid-0x01 connect prelude precedes the carrier
 *  CREATE, the firmware model acks it, and a session that ends while the
 *  prelude wait is open completes start() with a clear error. */
class PreludeTest {

    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    @Test
    fun transportSendsThePreludeBeforeCreate(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            t.start(warmup())
            assertEquals(1, sim.preludeAcks, "exactly one prelude per connect")
            // the strict model only activates the page when the prelude came first
            assertTrue(sim.layoutCreated, "CREATE followed the prelude")
            assertTrue(t.state.value.started)
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun preludeBytesMatchTheReferenceShape() {
        val p = LaunchMsg.prelude(156)
        // {1:2, 2:156, 4:{3:{2:{2:{1:0,2:0}}}}} — faceclaw PRELUDE_F5872_PAYLOAD
        val expected = byteArrayOf(
            0x08, 0x02, 0x10, 0x9C.toByte(), 0x01,
            0x22, 0x0A, 0x1A, 0x08, 0x12, 0x06, 0x12, 0x04, 0x08, 0x00, 0x10, 0x00,
        )
        assertEquals(expected.toList(), p.toList())
        assertEquals(156, LaunchMsg.msgIdOf(LaunchMsg.response(2, 156)))
        assertFalse(LaunchMsg.isEvent(LaunchMsg.FLAG_RESPONSE))
        assertTrue(LaunchMsg.isEvent(0x01))
    }

    /** A wire that never delivers sid-0x01 packets: the prelude wait stays open
     *  until the session ends. */
    private class MutePreludeTransport(val glass: GlassFirmwareSim, scope: CoroutineScope) :
        CfwTransportBase(scope, "mute") {
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
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            if ((packet[6].toInt() and 0xFF) == LaunchMsg.SID) return   // never reaches the glasses
            glass.write(arm, packet, nowMs())
        }
    }

    @Test
    fun stopDuringThePreludeWaitEndsStartWithAClearError(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = MutePreludeTransport(GlassFirmwareSim(), scope)
            val starting = async { runCatching { t.start(warmup()) } }
            delay(300)
            assertFalse(starting.isCompleted, "start() waits for the prelude ack (no timeout)")
            t.stop()
            val r = starting.await()
            val e = r.exceptionOrNull() ?: fail("start() should have ended with an error")
            assertTrue(e is LintError && e.message!!.contains("connect prelude"), "got: $e")
            assertFalse(t.state.value.started)
            // the instance is reusable: a later start() runs a fresh gate
            val t2 = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            t2.start(warmup())
            t2.stop()
        } finally {
            scope.cancel()
        }
    }

    // ---- APK 0.65, P5 item 14 (the green-gate sweep's GG-4): the three re-asks of the start, each entered by a pin that
    // loses its FIRST write on the way out (the glasses never see it, simpler than losing an answer that may span
    // packets). Real pacing: the control gates re-ask after 2 s; the warmup re-sends on its instant pacing. Each is
    // watched to fail with its re-send removed (the first start then does not end inside the harness bound).

    /** Runs a start under a harness bound that decides once; returns its error (null when it completed). */
    private suspend fun startEnds(scope: CoroutineScope, t: CfwTransportBase, what: String): Throwable? {
        val starting = scope.async { runCatching { t.start(warmup()) } }
        waitUntil(15_000, message = { "the start did not end ($what)" }) { starting.isCompleted }
        return starting.await().exceptionOrNull()
    }

    @Test
    fun aPreludeWriteTheGlassesNeverSawIsSentAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = StartRig(sim, scope)
            val swallowed = java.util.concurrent.atomic.AtomicBoolean(false)
            t.onWrite = { _, p -> if (StartRig.sid(p) == LaunchMsg.SID && swallowed.compareAndSet(false, true)) null else p }
            val e = startEnds(scope, t, "the prelude re-ask")
            assertTrue(e == null, "the start completes on the re-sent prelude: $e")
            assertTrue(swallowed.get(), "the first prelude was lost")
            assertEquals(1, sim.preludeAcks, "the second prelude is the first the glasses saw")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aCapabilityReadTheGlassesNeverSawIsAskedAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = StartRig(GlassFirmwareSim(), scope)
            // the first sid-0x09 write of a start is the capability READ (the prelude is 0x01; the lease follows CREATE)
            val swallowed = java.util.concurrent.atomic.AtomicBoolean(false)
            t.onWrite = { _, p -> if (StartRig.sid(p) == wm.damage.core.wire.SettingsMsg.SID && swallowed.compareAndSet(false, true)) null else p }
            val e = startEnds(scope, t, "the capability re-ask")
            assertTrue(e == null, "the start completes on the re-asked READ: $e")
            assertTrue(swallowed.get() && !t.state.value.capability.isNullOrEmpty(), "the second READ answered the gate")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** The warmup's ack never comes (its message never reached the glasses): it is sent again on its pacing, the second
     *  copy's ack releases the first as lost, and that ENDS the start loudly (the design, §34; GG-4's corrected
     *  expectation) — the next start on the same instance completes. */
    @Test
    fun aWarmupTheGlassesNeverSawEndsTheStartAndTheNextOneCompletes(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = StartRig(GlassFirmwareSim(), scope)
            val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is wm.damage.core.transport.TransportEvent.Note) notes.add("${it.kind}: ${it.detail}") }
            }
            // every packet of the first image message (they share the AA seq) never reaches the glasses
            val lostSeq = java.util.concurrent.atomic.AtomicInteger(-1)
            t.onWrite = { _, p ->
                if (StartRig.firstOfImage(p) && lostSeq.compareAndSet(-1, p[2].toInt() and 0xFF)) null
                else if (StartRig.sid(p) == wm.damage.core.wire.EvenHubMsg.SID && lostSeq.get() == (p[2].toInt() and 0xFF) && p[5].toInt() != 1) null
                else p
            }
            val e = startEnds(scope, t, "the warmup re-send") ?: fail("the start completed with its first warmup lost")
            assertTrue(e is LintError && "lost" in e.message!! && "acked first" in e.message!!, "the start ends naming the lost ack: $e")
            assertTrue(notes.any { it.startsWith("control: ") && "warmup frame went unacked" in it && "sent again" in it }, "the re-send's note: $notes")
            assertFalse(t.state.value.started)
            t.onWrite = null
            val e2 = startEnds(scope, t, "the next start")
            assertTrue(e2 == null, "the next start on the same instance completes: $e2")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** §64.2 item 5's escape: the first prelude never reaches the glasses and every later sid-0x01 write throws. The
     *  re-ask hands the SAME deferred the gate waits on, so the first later failure ends the start; a throwaway one
     *  left it re-asking for good. */
    @Test
    fun aPreludeWhoseReAsksCannotBeWrittenEndsTheStart(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val t = StartRig(GlassFirmwareSim(), scope)
            val first = java.util.concurrent.atomic.AtomicBoolean(true)
            t.onWrite = { _, p ->
                if (StartRig.sid(p) != LaunchMsg.SID) p
                else if (first.compareAndSet(true, false)) null
                else throw java.io.IOException("RIGHT: write characteristic gone")
            }
            val e = startEnds(scope, t, "the prelude's escape") ?: fail("the start completed with no prelude written")
            assertTrue(e is LintError && "connect prelude ended early" in e.message!! && "prelude not written" in e.message!!, "got: $e")
            assertFalse(t.state.value.started)
        } finally {
            scope.cancel()
        }
    }
}
