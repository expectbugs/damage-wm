package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent

/**
 * 2026-08-31 (Adam: "the brightness setting and battery displays don't work"):
 * the setting edited a stored value nobody transmitted, and the battery cells
 * had no wire source. Pins both round trips through the byte-exact model:
 *
 *  - the session-start brightness push reaches the firmware as the sid-0x09
 *    write faceclaw's builder defines (the sim decodes the same bytes);
 *  - the settings READ response's device-info block (f4: battery=12,
 *    charging=13 — G2CC §10, capture-confirmed) comes back as a
 *    TransportEvent.Battery.
 */
class BatteryBrightnessTest {

    @Test
    fun brightnessLandsAndBatteryArrives(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-bb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            sim.batteryPct = 63
            // poison the sim's brightness so only a REAL decoded write can
            // restore the defaults the shell pushes at start (auto)
            sim.brightnessAuto = false
            sim.brightnessLevel = 7
            val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val batteries = ArrayList<TransportEvent.Battery>()
            val collector = scope.launch {
                transport.events.collect {
                    if (it is TransportEvent.Battery) synchronized(batteries) { batteries.add(it) }
                }
            }
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("s.json")), null, scope) {
                Shell.LocalClock(12, 0, "12:00", "PM")
            }
            shell.start()
            val t0 = System.currentTimeMillis()
            while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 10_000) delay(10)
            assertTrue(shell.isQuiescent(), "shell did not settle: ${shell.quiescenceReport()}")

            // the start-time push overwrote the poisoned values with the
            // configured default (auto) — proves write, encode, sim decode
            assertTrue(sim.brightnessAuto, "the session-start brightness push never reached the firmware model")
            assertEquals(null, sim.brightnessLevel)

            // the capability-gate response already carried f4.12: at least one
            // Battery event with the modeled percentage reached the event flow
            val got = synchronized(batteries) { batteries.mapNotNull { it.glassesPct } }
            assertTrue(63 in got, "glasses battery from the settings response never surfaced (saw $got)")

            shell.stop()
            collector.cancel()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }
}
