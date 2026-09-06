package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg

/**
 * `HANDOFF.md` §36 and §37.0 — the incident of 2026-09-05 19:53 and the
 * measurement of 22:04: the glasses in the firmware's Silent Mode refused
 * every image, and the shell answered with a 20 KB keyframe every 3.5 s for
 * a quarter of an hour; then, fixed to sleep, it woke on the push OFF and was
 * refused for four more minutes, because leaving Silent Mode ends the
 * firmware's EvenHub page. Now: the push puts the shell to sleep at once
 * (frames stop, the lease is dropped, one notice), a missed push is covered
 * by three refusals, and the wake — the push OFF, or a refusal streak the
 * glasses do not explain — is a SESSION REBUILD through the keeper, G2CC's
 * reconnect-and-relayout path: a fresh CREATE, then belief equal to glass.
 */
class SilentGlassesTest {

    private class Rig(scope: CoroutineScope) {
        val tmp = Files.createTempDirectory("damage-silent")
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
        val statuses = ArrayList<String>()
        val keeper = ShellKeeper(shell, transport, scope,
            onStatus = { synchronized(statuses) { statuses.add(it) } }, retryPauseMs = 100)
        val done = ArrayList<TransportEvent.FlushDone>()
        val silentEvents = ArrayList<Boolean>()
        val notes = ArrayList<TransportEvent.Note>()
        val inputs = ArrayList<TransportEvent.Input>()
        val kinds = java.util.concurrent.ConcurrentHashMap<String, Int>()

        init {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                transport.events.collect {
                    kinds.merge(it::class.simpleName ?: "?", 1, Int::plus)
                    when (it) {
                        is TransportEvent.FlushDone -> synchronized(done) { done.add(it) }
                        is TransportEvent.SilentMode -> synchronized(silentEvents) { silentEvents.add(it.on) }
                        is TransportEvent.Note -> synchronized(notes) { notes.add(it) }
                        is TransportEvent.Input -> synchronized(inputs) { inputs.add(it) }
                        else -> {}
                    }
                }
            }
        }

        fun flushes() = synchronized(done) { done.size }
        fun failures() = synchronized(done) { done.count { !it.ok } }
        fun leaseHeld() = sim.leaseHeld(Arm.LEFT, System.currentTimeMillis()) && sim.leaseHeld(Arm.RIGHT, System.currentTimeMillis())
        fun driving() = keeper.state == ShellKeeper.State.RUNNING && transport.state.value.started

        /** Decides on ONE evaluation (§27.6): the condition is not re-tested
         *  after the wait, where a message arriving in the gap reads as a
         *  settle that never happened. */
        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            val before = HashMap(kinds)
            var settled = false
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) { settled = true; break }
                delay(10)
            }
            val during = kinds.mapValues { (k, v) -> v - (before[k] ?: 0) }.filterValues { it > 0 }
            if (!settled) {
                val dump = Thread.getAllStackTraces().entries.mapNotNull { (t, st) ->
                    val mine = st.filter { it.className.startsWith("wm.damage") || it.className.startsWith("kotlinx.coroutines.sync") }
                    if (mine.isEmpty()) null else "  ${t.name} [${t.state}]\n" + st.take(14).joinToString("\n") { "    at $it" }
                }.joinToString("\n")
                throw AssertionError("$what: shell did not settle — ${shell.quiescenceReport()}; events during the wait: $during; flushes ${done.size} failed ${failures()}\nthreads:\n$dump")
            }
        }

        /** Wait for a condition decided by the sim, the transport or the keeper, off the loop. */
        suspend fun until(what: String, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < 10_000) delay(10)
            assertTrue(cond(), "$what — keeper ${keeper.state} attempts ${keeper.attempts} '${keeper.lastReason}'; shell ${shell.quiescenceReport()}; transport ${transport.state.value}; notes ${synchronized(notes) { notes.map { it.kind + ": " + it.detail } }}")
        }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(640, 480)
            for (y in 0 until 480) for (x in 0 until 640) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        /** The glass shows what the shell believes — through the emitter's quantiser. */
        fun assertGlassMatchesBelief(what: String) {
            for (left in booleanArrayOf(true, false)) {
                val e = shell.comp.expectedLens(left); val g = lens(left)
                var diffs = 0
                for (y in 0 until 480) for (x in 0 until 640) {
                    val q = minOf(15, (e[x, y] + 8) / 17) * 17
                    if (q != g[x, y]) diffs++
                }
                assertEquals(0, diffs, "$what: ${if (left) "L" else "R"} belief != glass ($diffs px)")
            }
        }

        suspend fun up(what: String) {
            keeper.start()
            until("$what: the keeper drives") { driving() }
            settle(what)
        }
    }

    @Test
    fun thePushPutsTheShellToSleepAndThePushOffRebuildsTheSession(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.shell.silentPacingMs = 200
            rig.up("start")
            assertTrue(rig.leaseHeld(), "the session holds the lease")
            assertFalse(rig.shell.glassesAsleep)
            assertEquals(1, rig.sim.preludeAcks)
            val flushesAwake = rig.flushes()

            rig.sim.setSilent(true)                         // the both-temple press, pushed
            rig.until("the push puts the shell to sleep") { rig.shell.glassesAsleep }
            rig.settle("after the push")
            rig.until("the lease is released while silent") { !rig.leaseHeld() }
            assertEquals(listOf(true), synchronized(rig.silentEvents) { rig.silentEvents.toList() })
            assertTrue(rig.sim.carrierLost, "the model: the page ended with the mode")
            assertNull(rig.shell.lastDivergence, "the released lease paints the mirror's stock pattern — no divergence report while asleep")

            // gestures while silent: the surface follows, nothing is sent, and
            // the checks the ring triggers wait on the glasses' own word
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("scroll while silent") }
            delay(700)                                      // three pacing ticks
            assertEquals(flushesAwake, rig.flushes(), "no flush leaves while the glasses are silent")
            assertEquals(0, rig.failures(), "no flush was even tried, so none failed")
            assertTrue(rig.shell.glassesAsleep, "the glasses still say silent: the shell stays asleep")
            assertEquals(1, rig.keeper.attempts, "no rebuild while the glasses say they are silent")
            assertEquals(1, rig.sim.preludeAcks)

            rig.sim.setSilent(false)                        // the both-temple press again
            rig.until("the push OFF rebuilds the session") { rig.keeper.attempts == 2 && rig.driving() }
            rig.until("the glasses saw a second connect and CREATE") { rig.sim.preludeAcks == 2 && !rig.sim.carrierLost }
            rig.until("awake in the new session") { !rig.shell.glassesAsleep }
            rig.until("the lease is back") { rig.leaseHeld() }
            rig.settle("the keyframe after the rebuild")
            assertTrue(rig.flushes() > flushesAwake, "a keyframe went out in the new session")
            assertEquals(0, rig.failures(), "the rebuild's frames were accepted — nothing was refused")
            assertEquals(listOf(true, false), synchronized(rig.silentEvents) { rig.silentEvents.toList() })
            assertTrue(synchronized(rig.notes) { rig.notes.any { it.kind == "restart" } }, "the restart is a journaled fact")
            rig.assertGlassMatchesBelief("after the rebuild")
            delay(300)
            assertEquals(2, rig.keeper.attempts, "exactly one rebuild per wake")
            rig.keeper.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun threeRefusalsWithoutAPushSleepTheShellAndTheNextCheckRebuildsTheSession(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.shell.silentPacingMs = 200
            rig.up("start")
            val flushesAwake = rig.flushes()

            rig.sim.reportSilentRestored = false            // and the 60 s poll does not carry the state either
            rig.sim.setSilent(true, push = false)           // the push was missed
            repeat(6) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("scroll") }
            rig.until("three refusals put the shell to sleep") { rig.shell.glassesAsleep }
            val failedAtSleep = rig.failures()
            assertTrue(failedAtSleep in 1..Shell.REFUSALS_TO_SLEEP + 3,
                "a refusal streak, not a storm: $failedAtSleep failed flushes (was 266 keyframes in the incident)")
            rig.until("the lease is released") { !rig.leaseHeld() }

            // the glasses never said "silent", so the sleeping shell's next
            // check asks for a rebuild; the mode ends meanwhile, and the
            // rebuilt session paints. (A rebuild that lands while the mode is
            // still on is refused again and sleeps again — a paced loop, one
            // per pacing, never a storm.)
            rig.sim.setSilent(false, push = false)
            rig.until("a rebuild follows") { rig.keeper.attempts >= 2 }
            rig.until("a rebuilt session paints and is awake") { rig.driving() && !rig.shell.glassesAsleep && !rig.sim.carrierLost }
            rig.until("the lease is back") { rig.leaseHeld() }
            rig.settle("the keyframe after the rebuild")
            assertTrue(rig.flushes() > flushesAwake, "a keyframe went out on the rebuild")
            val failedTotal = rig.failures()
            assertTrue(failedTotal < 4 * (Shell.REFUSALS_TO_SLEEP + 3),
                "paced rebuilds, not a storm: $failedTotal failed flushes over ${rig.keeper.attempts} sessions")
            rig.assertGlassMatchesBelief("after the rebuild")
            rig.keeper.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theFirmwaresSystemEventsAreJournaledNotDroppedAsGestures(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope)
            rig.up("start")
            for (type in intArrayOf(EvenHubMsg.EV_FOREGROUND_ENTER, EvenHubMsg.EV_FOREGROUND_EXIT, EvenHubMsg.EV_SYSTEM_EXIT)) {
                rig.sim.injectGesture(type)
            }
            rig.until("three system events journaled") {
                synchronized(rig.notes) { rig.notes.count { it.kind == "event" } } == 3
            }
            val texts = synchronized(rig.notes) { rig.notes.filter { it.kind == "event" }.map { it.detail } }
            assertTrue(texts.any { it.startsWith("FOREGROUND_ENTER") } && texts.any { it.startsWith("FOREGROUND_EXIT") } &&
                texts.any { it.startsWith("SYSTEM_EXIT") }, "named by the firmware's event: $texts")
            assertTrue(synchronized(rig.inputs) { rig.inputs.none { it.type == EvenHubMsg.EV_FOREGROUND_ENTER ||
                it.type == EvenHubMsg.EV_FOREGROUND_EXIT || it.type == EvenHubMsg.EV_SYSTEM_EXIT } },
                "a system event is not a gesture")
            rig.sim.injectGesture(EvenHubMsg.EV_SCROLL_BOTTOM)   // a real gesture still arrives as input
            rig.until("the scroll is an input") { synchronized(rig.inputs) { rig.inputs.any { it.type == EvenHubMsg.EV_SCROLL_BOTTOM } } }
            rig.settle("after the scroll")
            rig.keeper.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theWireFactsParse() {
        assertEquals(true, SettingsMsg.parseSilentModePush(SettingsMsg.silentModePush(true)))
        assertEquals(false, SettingsMsg.parseSilentModePush(SettingsMsg.silentModePush(false)))
        // a READ response is not the push (command 2), but restores the state in 4.14
        val read = Pb.cat(Pb.v(1, 2), Pb.v(2, 7), Pb.l(4, Pb.cat(Pb.v(12, 80), Pb.v(13, 0), Pb.v(14, 1))))
        assertNull(SettingsMsg.parseSilentModePush(read))
        assertEquals(true, SettingsMsg.parseSilentRestored(read))
        // a device block without the field says nothing (stock firmware may omit it)
        assertNull(SettingsMsg.parseSilentRestored(Pb.cat(Pb.v(1, 2), Pb.l(4, Pb.v(12, 80)))))
    }
}
