package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * C3 SC1 (the lead's part CD): an adjust notch in Settings composes the content once. Before it, a row that applies per
 * notch composed twice — `applySettings` repaints the content with the new value, and the scroll handler's Settings
 * branch composed again after it (his stage-5 session: an adjust notch's handler 56 ms median against 28–30 ms for the
 * one-paint acts, measured). A row that STAGES its value (Size, a host row: applied on the tap) still composes once, in
 * the scroll handler — its own text ("… (tap applies)") is all that changes.
 */
class SettingsAdjustPaintTest {
    @Test
    fun anAdjustNotchComposesTheContentOnce_SC1(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("sc1-adjust")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope) {
                Shell.LocalClock(12, 0, "12:00", "PM")
            }
            shell.register(TransitionShellTest.Row("w0", "Win 0", DamageWindow.Summary("summary 0")))
            suspend fun settle(what: String) =
                waitUntil(10_000, 10, { "$what: the shell did not settle — ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
            fun composes(): Int = CompletableFuture<Int>().also { f -> shell.services.runOnShell { f.complete(shell.contentComposesForTest()) } }
                .get(10, TimeUnit.SECONDS)
            /** One gesture, settled; the content's compositions it made. */
            suspend fun act(what: String, gesture: Int): Int {
                settle("$what: before")
                val c0 = composes()
                shell.postGesture(gesture)
                settle(what)
                return composes() - c0
            }
            shell.start(); settle("start")
            act("Main: to Settings", EvenHubMsg.EV_SCROLL_BOTTOM)
            act("Settings opens", EvenHubMsg.EV_CLICK)
            assertEquals("settings", shell.currentWindowId())
            act("Global", EvenHubMsg.EV_CLICK)

            // Brightness (Global's row 0) applies per notch: the notch's one composition is `applySettings`'s
            act("Brightness: adjust", EvenHubMsg.EV_CLICK)
            assertTrue(shell.settings.brightnessAuto, "precondition: auto")
            assertEquals(1, act("Brightness: a notch leaves auto", EvenHubMsg.EV_SCROLL_BOTTOM),
                "an adjust notch on a row that applies composes the content once")
            assertTrue(!shell.settings.brightnessAuto, "the notch applied")
            val level = shell.settings.brightness
            assertEquals(1, act("Brightness: a second notch", EvenHubMsg.EV_SCROLL_BOTTOM), "and so does the next")
            assertEquals(minOf(100, level + 5), shell.settings.brightness, "the second notch applied")
            act("Brightness: tap keeps", EvenHubMsg.EV_CLICK)

            // Size (row 1) stages: the scroll handler's composition is its only one, and it stays
            act("to Size", EvenHubMsg.EV_SCROLL_BOTTOM)
            act("Size: adjust", EvenHubMsg.EV_CLICK)
            val height = shell.settings.heightMode
            assertEquals(1, act("Size: a notch stages", EvenHubMsg.EV_SCROLL_TOP), "a staged row's notch still composes the content once")
            assertEquals(height, shell.settings.heightMode, "Size stages: nothing applied before the tap")
            act("Size: double tap cancels", EvenHubMsg.EV_DOUBLE_CLICK)

            // an app's category (Win 0's, the second): its first row is a host row, which stages
            act("back to the categories", EvenHubMsg.EV_DOUBLE_CLICK)
            act("to Win 0's category", EvenHubMsg.EV_SCROLL_BOTTOM)
            act("Win 0's category", EvenHubMsg.EV_CLICK)
            act("its first row: adjust", EvenHubMsg.EV_CLICK)
            val styles = shell.settings.appStyles
            assertEquals(1, act("a host row's notch stages", EvenHubMsg.EV_SCROLL_BOTTOM), "a host row's notch composes the content once")
            assertEquals(styles, shell.settings.appStyles, "a host row stages: nothing applied before the tap")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
