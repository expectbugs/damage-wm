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
}
