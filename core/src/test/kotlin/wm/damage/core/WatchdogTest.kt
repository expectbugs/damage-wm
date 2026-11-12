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
 * while the glasses say they are silent — that sleep has its own wake — and,
 * from 0.64, never while the page traffic sleeps for any reason: a rest, or a
 * sleep the glasses did not announce (a refusal streak).
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
            // R2 (RH-N2): a list window, so a scroll at Main moves a row and its frame leaves (and is refused)
            shell.register(Rows(FakeText()))
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                transport.events.collect { if (it is TransportEvent.Note) notes.add(it) }
            }
        }

        fun driving() = keeper.state == ShellKeeper.State.RUNNING && transport.state.value.started

        suspend fun until(what: String, maxMs: Long = 10_000, cond: () -> Boolean) =
            // R2 (RH-8): the shared wait — ONE evaluation decides (`WaitTest`); `isQuiescent()` is not monotone here
            waitUntil(maxMs, 10, { "$what — keeper ${keeper.state} attempts ${keeper.attempts} '${keeper.lastReason}' notes=${notes.map { it.kind + ": " + it.detail }}" }, cond)
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

    /**
     * APK 0.64 (`HANDOFF.md` §75.4; the P4 design's Q1): the watchdog keys on the page traffic
     * (`pageTrafficWanted`), not on the glasses' own Silent Mode flag. A rest releases the lease on purpose
     * and stops the keepalive — the very quiet the watchdog reads as a session that stopped answering — while
     * the transport's flag stays false, so the old condition probed a resting session and rebuilt it.
     * An ABSENCE over a bounded wait, said as such: 1,500 ms is many times the instant rule's whole span
     * (the unfixed condition probes and rebuilds within about 0.8 s here).
     * Mutation watched: the watchdog reads the glasses' flag alone again.
     */
    @Test
    fun theWatchdogRestsWithARest(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.shell.silentPacingMs = 60_000                        // the shell's own check does not run inside this test
            rig.keeper.start()
            rig.until("first session") { rig.driving() }
            assertTrue(rig.shell.devProbe("rest", "on"))             // the shell's own sleep: the glasses say nothing
            rig.until("resting") { rig.shell.resting }
            rig.until("the release reached the glasses") {
                val now = System.currentTimeMillis()
                !rig.sim.leaseHeld(wm.damage.core.transport.Arm.LEFT, now) && !rig.sim.leaseHeld(wm.damage.core.transport.Arm.RIGHT, now) }
            assertTrue(!rig.transport.state.value.glassesSilent, "the transport's own flag does not show a rest")
            rig.transport.notifyFilter = { _, _ -> false }          // and then nothing comes back at all
            delay(1_500)
            assertEquals(1, rig.keeper.attempts, "no rebuild while the shell rests")
            assertTrue(rig.notes.none { it.kind == "watchdog" }, "no probe either: ${rig.notes.filter { it.kind == "watchdog" }}")
            assertTrue(rig.shell.resting, "still resting")
            rig.keeper.stop()
        } finally {
            scope.cancel()
        }
    }

    /**
     * The same condition's other reach (Q1, accepted): a sleep the glasses did not announce — three refused
     * frames in a row with no push and no READ saying so. The shell is asleep with the transport's flag
     * false, exactly as in a rest; until 0.64 the watchdog probed that session about 13 s in and rebuilt it
     * about 23 s in, ahead of the shell's own 60 s check. Now that check (or an input event) is the way back.
     * The same absence over the same bounded wait; the same mutation.
     */
    @Test
    fun theWatchdogStandsDownForASleepTheGlassesDidNotAnnounce(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.shell.silentPacingMs = 60_000                        // the paced check is not this test's subject
            rig.keeper.start()
            rig.until("first session") { rig.driving() && rig.shell.isQuiescent() }
            rig.sim.reportSilentRestored = false                     // the READ does not carry the state
            rig.sim.setSilent(true, push = false)                    // and the push was missed
            repeat(8) {
                if (!rig.shell.glassesAsleep) {
                    rig.shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM)
                    rig.until("the scroll's flush is answered, or refused into the sleep") { rig.shell.isQuiescent() }
                }
            }
            rig.until("three refusals put the shell to sleep") { rig.shell.glassesAsleep }
            assertTrue(!rig.shell.resting && !rig.transport.state.value.glassesSilent, "asleep for Silent Mode, unannounced")
            rig.until("the release reached the glasses") {
                val now = System.currentTimeMillis()
                !rig.sim.leaseHeld(wm.damage.core.transport.Arm.LEFT, now) && !rig.sim.leaseHeld(wm.damage.core.transport.Arm.RIGHT, now) }
            val attempts = rig.keeper.attempts
            rig.transport.notifyFilter = { _, _ -> false }
            delay(1_500)
            assertEquals(attempts, rig.keeper.attempts, "no rebuild by the watchdog: the shell's paced check owns this sleep's way back")
            assertTrue(rig.notes.none { it.kind == "watchdog" }, "no probe: ${rig.notes.filter { it.kind == "watchdog" }}")
            rig.keeper.stop()
        } finally {
            scope.cancel()
        }
    }

    /** A list window, so Main has a row above Settings and a scroll paints (RH-N2). */
    private class Rows(private val tx: wm.damage.core.text.TextRasterizer) :
        wm.damage.core.shell.DamageWindow("rows", "Rows", wm.damage.core.gfx.IconKind.FILES) {
        private val model = wm.damage.core.shell.ListModel()
        private val font = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 20)
        override fun view(): wm.damage.core.shell.WindowView = wm.damage.core.shell.WindowView.ListView(model, { 40 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 4, "Row $i text", font, wm.damage.core.gfx.Level.BODY) },
            paintLens = { g, r, i -> tx.draw(g, r.x + 44, r.y + 6, "Row $i", font, wm.damage.core.gfx.Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("40 rows")
        override fun saveState(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {}
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
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
