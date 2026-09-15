package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import wm.damage.core.wire.LoggerMsg
import wm.damage.core.wire.Pb

/** `HANDOFF.md` §49: the Phase 0 probes — the logger message bytes against
 *  Even's schema, the overlay toggle and the log-stream switch through the
 *  transport, the switch re-sent after a session start, and the journaled
 *  notes (probe, battery). */
class DevProbeTest {

    @Test
    fun loggerSwitchBytesFollowTheVendorSchema() {
        // logger_main_msg_ctx: f1 cmd = BLE_LOGGER_SWITCH_SET (1), f2 magicRandom, f3 bleTransEn
        assertEquals("08011007" + "1801", LoggerMsg.switchSet(7, on = true).hex())
        // "off" still carries field 3: the handler reads the oneof's tag before the value
        assertEquals("08011007" + "1800", LoggerMsg.switchSet(7, on = false).hex())
    }

    @Test
    fun aLogLineParses() {
        val line = "[display_thread]startup applicationID = 224"
        val payload = Pb.cat(Pb.v(1, LoggerMsg.CMD_DEVICE_SEND_DATA), Pb.v(2, 0), Pb.s(5, line))
        val m = assertNotNull(LoggerMsg.parse(payload))
        assertEquals(LoggerMsg.CMD_DEVICE_SEND_DATA, m.cmd)
        assertEquals(line, m.logStr)
        assertEquals(null, LoggerMsg.parse(byteArrayOf(0x0a, 0x7f)), "an overrunning length does not parse")
    }

    @Test
    fun probesReachTheModelAndTheJournalNotes(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val notes = ArrayList<Pair<String, String>>()
            val faults = ArrayList<String>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    if (it is TransportEvent.Note) synchronized(notes) { notes.add(it.kind to it.detail) }
                    if (it is TransportEvent.Fault) synchronized(faults) { faults.add("${it.what}: ${it.detail}") }
                }
            }
            val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

            // before any session: the logger wish is kept, the overlay is refused
            t.devProbe("logger", "on")
            t.devProbe("diag", "show")
            assertTrue(Arm.entries.none { sim.overlayShown(it) }, "no session, no overlay message")

            t.start(full)
            until("the start re-sends the wanted log switch") { Arm.entries.all { sim.loggerOn(it) } }
            until("the switch's answer is journaled") {
                synchronized(notes) { notes.any { it.first == "probe" && it.second.startsWith("logger switch answered") } }
            }

            t.devProbe("diag", "show")
            until("overlay shown on both lenses") { Arm.entries.all { sim.overlayShown(it) } }
            t.devProbe("diag", "hide")
            until("overlay hidden on both lenses") { Arm.entries.none { sim.overlayShown(it) } }
            assertTrue(Arm.entries.all { sim.flags(it).none { f -> f.value } }, "the toggle raised no sticky flag")

            // a session restart ends the stream on the glasses; the new start sends it again
            assertTrue(t.restartSession("test: probe re-arm"))
            t.start(full)
            until("re-armed after the second start") { Arm.entries.all { sim.loggerOn(it) } }

            t.devProbe("logger", "off")
            until("stream off on both arms") { Arm.entries.none { sim.loggerOn(it) } }
            assertTrue(t.restartSession("test: stays off"))
            t.start(full)
            delay(200)
            assertTrue(Arm.entries.none { sim.loggerOn(it) }, "an unwanted stream is not re-sent")

            t.devProbe("diag", "sideways")        // refused by value, loudly, and still journaled
            t.devProbe("warp", "9")               // not a probe this transport runs
            // the collector runs on its own dispatcher: wait for the notes, never assume them
            for (want in listOf("logger=on", "diag=show", "diag=hide", "logger=off", "diag=sideways", "warp=9"))
                until("probe note '$want' journaled") { synchronized(notes) { notes.any { it.first == "probe" && it.second == want } } }
            assertTrue(synchronized(faults) { faults.isEmpty() }, "probes raised faults: $faults")
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    /** The self-test and cache probes reach the model of a Damage build; an upstream build
     *  is told what to expect. `FIRMWARE.md` §3: RIGHT reports the scratch CRC through telemetry. */
    @Test
    fun selfTestAndCacheProbesReachTheModel(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim().also { it.damageContract = 1 }
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val notes = ArrayList<Pair<String, String>>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.Note) synchronized(notes) { notes.add(it.kind to it.detail) } }
            }
            fun has(kind: String, text: String) = synchronized(notes) { notes.any { it.first == kind && text in it.second } }
            val full = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
            t.start(full)
            until("the Damage build is named") { has("glass", "DamageCaps contract 1") }
            t.devProbe("selftest", "begin")
            t.devProbe("selftest", "step:" + wm.damage.core.wire.CfwModes.keyframe(full).hex())
            until("one step ran on both lenses") { Arm.entries.all { sim.selfTestSteps(it) == 1L && sim.selfTestCrc(it) != 0L } }
            t.devProbe("telemetry", "read")
            until("RIGHT reports the step and its scratch CRC") { has("glass", "R ") && has("glass", "stSteps=1 stRefused=0 stCrc=%08x".format(sim.selfTestCrc(Arm.RIGHT))) }
            t.devProbe("selftest", "step:0c0000")     // refused by the builder: not a drawing message
            t.devProbe("selftest", "end")
            t.devProbe("cache", "info")
            until("CACHE_INFO answered (no cache yet: no size, no CRC)") { has("glass", "cacheGen=0 stSteps=1") }
            t.devProbe("selftest", "sideways")        // refused by value, journaled
            until("the probe note") { has("probe", "selftest=sideways") }
            t.stop()
        } finally {
            scope.cancel()
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private suspend fun until(what: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 10_000) delay(5)
        assertTrue(cond(), "never true: $what")
    }
}
