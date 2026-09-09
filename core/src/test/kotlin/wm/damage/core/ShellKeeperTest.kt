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
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase

/** HANDOFF.md §8.2 "Session keeper": a link end restarts the session; a
 *  capability refusal is terminal. */
class ShellKeeperTest {

    /** A sim-backed transport whose link can be ended from the test. */
    private class FragileTransport(val glass: GlassFirmwareSim, scope: CoroutineScope) :
        CfwTransportBase(scope, "fragile") {
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
        override suspend fun writeArm(arm: Arm, packet: ByteArray) { glass.write(arm, packet, nowMs()) }
        override fun onMaintenanceTick() { glass.tick(nowMs()) }
        fun endLink(reason: String) = onLinkDown(reason)
    }

    private suspend fun until(what: String, maxMs: Long = 10_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < maxMs) delay(10)
        assertTrue(cond(), what)
    }

    @Test
    fun aLinkEndRestartsTheSessionOnce(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-keeper")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val glass = GlassFirmwareSim()
            val t = FragileTransport(glass, scope)
            val clock = Shell.LocalClock(12, 0, "12:00", "PM")
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("s.json")), null, scope) { clock }
            val statuses = ArrayList<String>()
            val keeper = ShellKeeper(shell, t, scope, onStatus = { synchronized(statuses) { statuses.add(it) } },
                retryPauseMs = 100)
            keeper.start()
            until("first session up") { keeper.state == ShellKeeper.State.RUNNING && t.state.value.started }
            assertEquals(1, glass.preludeAcks)

            t.endLink("test: out of range")
            until("second session up") { keeper.attempts == 2 && keeper.state == ShellKeeper.State.RUNNING }
            until("the glasses saw a second connect") { glass.preludeAcks == 2 }
            assertTrue(t.state.value.started, "the transport is started again")
            delay(300)
            assertEquals(2, keeper.attempts, "exactly one restart per link end")

            keeper.stop()
            assertEquals(ShellKeeper.State.STOPPED, keeper.state)
            assertTrue(!t.state.value.started)
            assertTrue(synchronized(statuses) { statuses.any { it.startsWith("link ended") } })
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /** `HANDOFF.md` §37.0: a deliberate restart is a link end the keeper
     *  rebuilds from — exactly once — and it is refused when nothing runs. */
    @Test
    fun aRestartRequestRebuildsTheSessionOnce(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-keeper3")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val glass = GlassFirmwareSim()
            val t = FragileTransport(glass, scope)
            assertTrue(!t.restartSession("nothing started"), "refused before any session")
            val clock = Shell.LocalClock(12, 0, "12:00", "PM")
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("s.json")), null, scope) { clock }
            val statuses = ArrayList<String>()
            val keeper = ShellKeeper(shell, t, scope, onStatus = { synchronized(statuses) { statuses.add(it) } },
                retryPauseMs = 100)
            keeper.start()
            until("first session up") { keeper.state == ShellKeeper.State.RUNNING && t.state.value.started }
            assertEquals(1, glass.preludeAcks)

            assertTrue(t.restartSession("test: the glasses left Silent Mode"), "a running session restarts")
            assertTrue(!t.state.value.started, "the session is over the moment the restart returns")
            until("second session up") { keeper.attempts == 2 && keeper.state == ShellKeeper.State.RUNNING }
            until("the glasses saw a second connect") { glass.preludeAcks == 2 }
            delay(300)
            assertEquals(2, keeper.attempts, "exactly one rebuild per restart")
            assertTrue(synchronized(statuses) { statuses.any { it.startsWith("link ended: restart: test") } },
                "the keeper narrates the restart by its reason: $statuses")

            keeper.stop()
            assertTrue(!t.restartSession("stopped"), "refused after stop")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aCapabilityRefusalIsTerminal(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-keeper2")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val glass = GlassFirmwareSim()
            glass.capabilityString = "not-a-cfw"
            val t = FragileTransport(glass, scope)
            val clock = Shell.LocalClock(12, 0, "12:00", "PM")
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("s.json")), null, scope) { clock }
            var terminal: String? = null
            val keeper = ShellKeeper(shell, t, scope, onTerminal = { terminal = it }, retryPauseMs = 50)
            keeper.start()
            until("terminal state") { keeper.state == ShellKeeper.State.TERMINAL }
            assertTrue(terminal != null, "the host was told")
            delay(300)
            assertEquals(1, keeper.attempts, "no retry after a refusal")
            assertTrue(!t.state.value.started)
            keeper.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /** §42 (2026-09-09): a stop after a LINK LOSS sends no lease release — the
     *  write into the dropped arm was a `control` fault at every one of a day's
     *  rebuilds, and the release that reached the surviving arm freed that
     *  lens's texture cache for a lease the keeper re-acquires within seconds.
     *  A deliberate stop still releases both arms. And every keeper transition
     *  is a `keeper` note in the shell's journal. */
    @Test
    fun aStopAfterALinkLossReleasesNothingAndTheKeeperNarratesIntoTheJournal(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-keeper4")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val glass = GlassFirmwareSim()
            val t = FragileTransport(glass, scope)
            val clock = Shell.LocalClock(12, 0, "12:00", "PM")
            val journal = tmp.resolve("journal.jsonl")
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("s.json")), journal, scope) { clock }
            val keeper = ShellKeeper(shell, t, scope, retryPauseMs = 100)
            keeper.start()
            until("first session up") { keeper.state == ShellKeeper.State.RUNNING && t.state.value.started }
            assertEquals(0, glass.releasesSeen, "a driving session releases nothing")

            t.endLink("test: supervision timeout")
            until("second session up") { keeper.attempts == 2 && keeper.state == ShellKeeper.State.RUNNING }
            until("the glasses saw a second connect") { glass.preludeAcks == 2 }
            assertEquals(0, glass.releasesSeen, "no release through a link that is gone")
            assertTrue(glass.leaseHeld(Arm.LEFT, System.currentTimeMillis()) && glass.leaseHeld(Arm.RIGHT, System.currentTimeMillis()),
                "the re-acquire renewed the lease on both arms")

            keeper.stop()
            assertEquals(2, glass.releasesSeen, "a deliberate stop releases both arms")
            val notes = Files.readAllLines(journal).filter { it.contains("\"kind\":\"keeper\"") }
            assertTrue(notes.any { it.contains("starting") }, "the first attempt is journaled: $notes")
            assertTrue(notes.any { it.contains("link ended: test: supervision timeout") }, "the link end and its reason: $notes")
            assertTrue(notes.any { it.contains("reconnecting (attempt 2)") }, "the second attempt: $notes")
            assertTrue(notes.any { it.contains("stopped") }, "the stop: $notes")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
