package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.HostSetting
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/** HANDOFF.md §8.1 decision 6 (a notification waits behind the switcher
 *  wheel) and §8.2 host-supplied Settings rows. */
class WheelAndHostSettingsTest {

    private class Rig(scope: CoroutineScope, tmp: java.nio.file.Path) {
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("s.json")), null, scope) {
            Shell.LocalClock(12, 0, "12:00", "PM")
        }
    }

    private suspend fun settle(shell: Shell) {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 10_000) delay(10)
        assertTrue(shell.isQuiescent(), "shell did not settle: ${shell.quiescenceReport()}")
    }

    private fun notice(thread: String) =
        Notifications.Notice("SMS · TEST", thread, "a message that waits its turn", "12:00")

    @Test
    fun aNoticeArrivingWhileTheWheelIsOpenWaitsBehindIt(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-wheel")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = Rig(scope, tmp)
            r.shell.start(); settle(r.shell)

            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)     // the wheel opens
            settle(r.shell)
            r.shell.postNotice(notice("t1"))
            settle(r.shell)
            assertFalse(r.shell.notifications.active, "the box waits while the wheel is open")
            assertEquals(1, r.shell.notifications.queued().size)

            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)     // cancel closes the wheel
            settle(r.shell)
            assertTrue(r.shell.notifications.active, "the box appears once the wheel closes")
            assertEquals("t1", r.shell.notifications.current?.thread)
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aBoxOnScreenStepsAsideForTheWheelAndReturnsUnread(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-wheel2")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = Rig(scope, tmp)
            r.shell.start(); settle(r.shell)
            r.shell.postNotice(notice("t2"))
            settle(r.shell)
            assertTrue(r.shell.notifications.active)

            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)     // the wheel opens over it
            settle(r.shell)
            assertFalse(r.shell.notifications.active, "the box left the screen for the wheel")
            assertEquals(listOf("t2"), r.shell.notifications.queued().map { it.thread })
            assertFalse(r.shell.notifications.queued().single().read, "it stays unread")

            r.shell.postGesture(EvenHubMsg.EV_CLICK)               // commit closes the wheel
            settle(r.shell)
            assertTrue(r.shell.notifications.active, "the box is back after the wheel")
            assertEquals("t2", r.shell.notifications.current?.thread)
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aNoticeForTheAppTheWheelCommitsToIsNotShownAsNew(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-wheel3")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = Rig(scope, tmp)
            r.shell.start(); settle(r.shell)
            // visit Settings once so it is the most recent inactive window the wheel opens on
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP); settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_CLICK); settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); settle(r.shell)
            assertEquals(null, r.shell.currentWindowId())
            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)     // the wheel opens; cursor on Settings
            settle(r.shell)
            r.shell.postNotice(Notifications.Notice("DAMAGE · settings", "t3", "a notice about settings", "12:00", appId = "settings"))
            settle(r.shell)
            assertEquals(1, r.shell.notifications.queued().size, "queued behind the wheel")
            r.shell.postGesture(EvenHubMsg.EV_CLICK)               // commit to Settings: its notices are read
            settle(r.shell)
            assertEquals("settings", r.shell.currentWindowId())
            assertFalse(r.shell.notifications.active, "a notice for the app just entered is not shown as new (§4.5, round 1 f6)")
            assertTrue(r.shell.notifications.queued().isEmpty(), "and it does not linger in the queue")
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aHostRowStagesOnScrollAndAppliesOnTap(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-host")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = Rig(scope, tmp)
            var current = "sim"
            var applied: String? = null
            r.shell.hostSettings = listOf(HostSetting("Target", listOf("sim", "glasses"), { current }) { applied = it })
            r.shell.start(); settle(r.shell)

            r.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)     // Main wraps up to Settings
            settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_CLICK)          // enter Settings
            settle(r.shell)
            assertEquals("settings", r.shell.currentWindowId())
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)     // wrap to the last row: the host row
            settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_CLICK)          // adjust
            settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)  // stage "glasses"
            settle(r.shell)
            assertEquals(null, applied, "scrolling only stages")
            r.shell.postGesture(EvenHubMsg.EV_CLICK)          // tap applies
            settle(r.shell)
            assertEquals("glasses", applied)

            // adjust again, stage, double-tap reverts: nothing applied
            current = "glasses"
            applied = null
            r.shell.postGesture(EvenHubMsg.EV_CLICK); settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); settle(r.shell)
            assertEquals(null, applied, "double-tap reverts the staged choice")
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }
}
