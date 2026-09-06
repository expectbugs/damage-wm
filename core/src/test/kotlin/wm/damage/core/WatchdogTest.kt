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
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent

/**
 * `HANDOFF.md` §40 — the response-gap watchdog, G2CC's recovery shape: a
 * session whose glasses stop answering altogether (link up, every packet
 * lost on the way back) is probed and then rebuilt through the keeper. Never
 * while the glasses say they are silent — that sleep has its own wake.
 */
class WatchdogTest {

    private class Rig(scope: CoroutineScope) {
        val tmp = Files.createTempDirectory("damage-watchdog")
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
        val keeper = ShellKeeper(shell, transport, scope, retryPauseMs = 100)
        val notes = java.util.concurrent.CopyOnWriteArrayList<TransportEvent.Note>()

        init {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                transport.events.collect { if (it is TransportEvent.Note) notes.add(it) }
            }
        }

        fun driving() = keeper.state == ShellKeeper.State.RUNNING && transport.state.value.started

        suspend fun until(what: String, maxMs: Long = 10_000, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < maxMs) delay(10)
            assertTrue(cond(), "$what — keeper ${keeper.state} attempts ${keeper.attempts} '${keeper.lastReason}' notes=${notes.map { it.kind + ": " + it.detail }}")
        }
    }

    @Test
    fun aSessionThatStopsAnsweringIsProbedThenRebuilt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.keeper.start()
            rig.until("first session") { rig.driving() }
            assertEquals(1, rig.sim.preludeAcks)
            delay(300)
            assertEquals(1, rig.keeper.attempts, "a healthy session is never restarted")
            assertTrue(rig.notes.none { it.kind == "watchdog" }, "a healthy session is never probed: ${rig.notes}")

            rig.transport.notifyFilter = { _, _ -> false }          // the glasses go quiet: every packet back is lost
            rig.until("the probe") { rig.notes.any { it.kind == "watchdog" && it.detail.contains("probe sent") } }
            rig.until("the rebuild") { rig.notes.any { it.kind == "restart" && it.detail.contains("watchdog") } }
            rig.until("the keeper tries again") { rig.keeper.attempts == 2 }
            rig.transport.notifyFilter = null                        // the glasses answer again
            rig.until("the second session drives") { rig.driving() && rig.sim.preludeAcks >= 2 }
            delay(300)
            assertEquals(2, rig.keeper.attempts, "one rebuild per silence, not a loop")
            rig.keeper.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theWatchdogSleepsWithTheGlasses(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.keeper.start()
            rig.until("first session") { rig.driving() }
            rig.sim.setSilent(true)                                  // the push says silent
            rig.until("asleep") { rig.shell.glassesAsleep }
            rig.transport.notifyFilter = { _, _ -> false }          // and then nothing comes back at all
            delay(1_500)                                             // many times the instant rule's whole span
            assertEquals(1, rig.keeper.attempts, "no rebuild while the glasses say they are silent")
            assertTrue(rig.notes.none { it.kind == "watchdog" }, "no probe either: ${rig.notes.filter { it.kind == "watchdog" }}")
            rig.keeper.stop()
        } finally {
            scope.cancel()
        }
    }
}
