package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/** `FIRMWARE.md` §0/§3 (draft, Phase 1): the DamageCaps field, the control ops and the
 *  telemetry record — bytes from the contract text, then through the transport's probes
 *  against the simulator's model of a Damage build and of the installed upstream build. */
class DamageMsgTest {

    @Test
    fun controlBytesFollowTheContract() {
        // G2SettingPackage{1: 1, 2: 0, 112: ['D','M',1,op,argLo,argHi]}; field 112 wire 2 = 82 07
        assertEquals("08011000" + "8207" + "06" + "444d01012a00", DamageMsg.control(DamageMsg.OP_TELEMETRY, 42).hex())
        assertEquals("08011000" + "8207" + "06" + "444d01020080", DamageMsg.control(DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_PROBE).hex())
    }

    @Test
    fun capsAndTelemetryParse() {
        // FIRMWARE.md §0's own example body: 0a 03 44 4d 47 10 01 18 00
        val read = Pb.cat(Pb.v(1, 2), Pb.v(2, 5), Pb.l(110, "0a03444d4710011800".unhex()))
        assertEquals(DamageMsg.Caps(1, 0), DamageMsg.parseCaps(read))
        assertNull(DamageMsg.parseCaps(Pb.cat(Pb.v(1, 2), Pb.l(110, Pb.s(1, "XYZ")))), "a field 110 without the marker is not DamageCaps")
        assertNull(DamageMsg.parseCaps(Pb.cat(Pb.v(1, 2), Pb.v(2, 5))), "an upstream READ has no field 110")

        val record = Pb.cat(Pb.v(1, 42), Pb.v(2, 5000), Pb.v(3, 0x8000), Pb.v(4, 0), Pb.v(10, 0x0070B024), Pb.v(12, 90000), Pb.v(14, 2))
        val t = assertNotNull(DamageMsg.parseTelemetry(Pb.cat(Pb.v(1, 3), Pb.v(2, 0), Pb.l(111, record))))
        assertEquals(42L, t.requestId); assertEquals(0x8000L, t.flags); assertEquals(90000L, t.leaseMsLeft); assertEquals(2L, t.lens)
        assertEquals("id=42 uptimeMs=5000 flags=0x8000 status=0 panel=0x70b024(JBD4010) leaseMs=90000 lens=2", t.describe())
    }

    @Test
    fun probesAgainstADamageBuildAndAnUpstreamBuild(): Unit = runBlocking {
        for (contract in listOf(1, null)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val sim = GlassFirmwareSim().also { it.damageContract = contract }
                var clock = 1_000_000L
                val t = SimTransport(sim, scope, SimTransport.Timing(instant = true), clock = { clock })
                val notes = ArrayList<Pair<String, String>>()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    t.events.collect { if (it is TransportEvent.Note) synchronized(notes) { notes.add(it.kind to it.detail) } }
                }
                fun has(kind: String, text: String) = synchronized(notes) { notes.any { it.first == kind && text in it.second } }
                t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))

                if (contract == null) {
                    until("the upstream build is named") { has("glass", "no DamageCaps field") }
                    t.devProbe("telemetry", "read")
                    until("a probe into an upstream build says what to expect") { has("probe", "without DamageCaps") }
                    delay(200)
                    assertTrue(!has("glass", "uptimeMs="), "an upstream build answers no telemetry")
                    t.stop()
                    continue
                }
                until("the Damage build is named") { has("glass", "DamageCaps contract 1 features 0x3") }
                t.devProbe("flags", "probe")
                until("both arms armed") { Arm.entries.all { sim.damageFlags(it) == DamageMsg.FLAG_PROBE } }
                until("both arms answered with the flag in force") { has("glass", "R ") && has("glass", "L ") && has("glass", "flags=0x8000") }
                t.devProbe("flags", "0x0001")
                until("an unimplemented bit answers status 2") { has("glass", "status=2") }
                assertTrue(Arm.entries.all { sim.damageFlags(it) == DamageMsg.FLAG_PROBE }, "a refused set changes nothing")
                // a lease lapse on the glasses' clock clears the flags (the texture cache's release points)
                clock += 200_000L
                t.devProbe("telemetry", "read")
                until("the lapse cleared both arms") { Arm.entries.all { sim.damageFlags(it) == 0 } }
                t.devProbe("flags", "bogus")
                t.stop()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private suspend fun until(what: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 10_000) delay(5)
        assertTrue(cond(), "never true: $what")
    }
}
