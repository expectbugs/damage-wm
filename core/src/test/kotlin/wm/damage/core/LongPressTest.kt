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
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * DESIGN.md §1.2/§1.3 revised 2026-08-30: a bare long-press is a NO-OP by
 * default (the most common accidental press, gloves worst); the switcher
 * opens by the chord — long-press, then double-tap inside the 800 ms window
 * (armed by event 9, refreshed by the release, ended by any other gesture);
 * a stale chord's double-tap is plain back; the "Long-press · switcher"
 * setting restores the direct open.
 *
 * 🔴 Every event-9/10 here is injected with SOURCE 0 — the wire truth:
 * `Sys_ItemEvent.EventSource` is ABSENT for those types by firmware design.
 * The first version of this suite used postGesture's SRC_RING default, so it
 * passed while the shell's §1 source filter discarded every REAL long-press
 * and the switcher was unreachable on glass (found live 2026-08-31,
 * HANDOFF.md §12). Do not "tidy" these back to the default.
 */
class LongPressTest {

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

    /** Enter Settings once and leave, so the wheel's cursor lands on it. */
    private suspend fun visitSettings(shell: Shell) {
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP); settle(shell)
        shell.postGesture(EvenHubMsg.EV_CLICK); settle(shell)
        shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); settle(shell)
        assertEquals(null, shell.currentWindowId())
    }

    @Test
    fun aBareLongPressLeavesAFocusedNoticeAlone(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-lp1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = Rig(scope, tmp)
            r.shell.start(); settle(r.shell)
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t1", "still here after a stray press", "12:00"))
            settle(r.shell)
            delay(3_000)                                       // the 2.5 s grace
            settle(r.shell)
            assertTrue(r.shell.notifications.focused, "the box took focus after the grace")

            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS, 0) // the old dismiss-unread: now nothing
            settle(r.shell)
            assertTrue(r.shell.notifications.active, "a bare long-press leaves the box exactly as it was")
            assertTrue(r.shell.notifications.focused)

            delay(1_000)                                       // the chord window expires
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)    // plain double-tap: dismiss + read
            settle(r.shell)
            assertFalse(r.shell.notifications.active, "an unarmed double-tap keeps its §4.5 meaning")
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theChordOpensTheWheelAndParksAFocusedBoxUnread(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-lp2")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = Rig(scope, tmp)
            r.shell.start(); settle(r.shell)
            visitSettings(r.shell)
            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t2", "parked, not discarded", "12:00"))
            settle(r.shell)
            delay(3_000); settle(r.shell)
            assertTrue(r.shell.notifications.focused)

            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS, 0) // arm
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)    // the chord: wheel over the box
            settle(r.shell)
            assertFalse(r.shell.notifications.active, "the box stepped aside for the wheel")
            assertEquals(listOf("t2"), r.shell.notifications.queued().map { it.thread })
            assertFalse(r.shell.notifications.queued().single().read, "parked UNREAD (§4.5, 2026-08-30)")

            r.shell.postGesture(EvenHubMsg.EV_CLICK)           // commit: the wheel's cursor is on Settings
            settle(r.shell)
            assertEquals("settings", r.shell.currentWindowId(), "the chord had opened a live wheel")
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aStaleChordIsPlainBack(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-lp3")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = Rig(scope, tmp)
            r.shell.start(); settle(r.shell)
            visitSettings(r.shell)

            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS, 0) // armed…
            settle(r.shell)
            delay(1_000)                                       // …and expired (window is 800 ms)
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)    // plain back: Main -> silent (§1.4)
            settle(r.shell)
            // silent swallows everything but double-tap: the taps change nothing
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
            r.shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(r.shell)
            assertEquals(null, r.shell.currentWindowId(), "a stale chord went back to silent, not to a wheel")

            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)    // wake
            settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS, 0) // a fresh chord still works
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(r.shell)
            assertEquals("settings", r.shell.currentWindowId())
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theWindowRunsFromTheRelease(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-lp4")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val r = Rig(scope, tmp)
            r.shell.start(); settle(r.shell)
            visitSettings(r.shell)

            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS, 0)          // arm at t=0 (held)
            settle(r.shell)
            delay(600)
            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, 0)  // let go at ~600: refresh
            settle(r.shell)
            delay(600)                                                  // 1.2 s after 9, 0.6 s after 10
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)             // inside the refreshed window
            settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(r.shell)
            assertEquals("settings", r.shell.currentWindowId(), "a slow release still chords: the window runs from letting go")
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun enablingLongPressRestoresTheDirectOpenAndDismissUnread(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-lp5")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // persisted before boot, the way the Settings row leaves it
            Persistence(tmp.resolve("s.json")).apply {
                put("shell.settings", ShellSettings(longPress = ShellSettings.LongPress.SWITCHER).toJson())
                save()
            }
            val r = Rig(scope, tmp)
            r.shell.start(); settle(r.shell)
            visitSettings(r.shell)

            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS, 0) // direct open, no chord needed
            settle(r.shell)
            r.shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(r.shell)
            assertEquals("settings", r.shell.currentWindowId(), "long-press alone opened the wheel when enabled")
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)    // back to Main
            settle(r.shell)

            r.shell.postNotice(Notifications.Notice("SMS · TEST", "t5", "dismissable unread again", "12:00"))
            settle(r.shell)
            delay(3_000); settle(r.shell)
            assertTrue(r.shell.notifications.focused)
            r.shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS, 0) // the pre-revision §4.5 gesture
            settle(r.shell)
            assertFalse(r.shell.notifications.active, "long-press dismisses the focused notice when enabled")
            r.shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }
}
