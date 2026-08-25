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
}
