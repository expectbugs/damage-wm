package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.ShellSettings
import wm.damage.core.wire.EvenHubMsg

/**
 * Build W, package C (R6 T12, pin W15; watched failing on the tree without `Notice.gatedBy`): a notice from a HOST
 * category (`Shell.notifyFromHost`, the bus watcher's "Bastion") is shown with Global `Notify · Damage` off — its own
 * rows gated it — while an app-less notice without that mark is still filtered; the mark survives a restart's
 * save and restore; and, naming no app, a tap only dismisses it (no "not available").
 */
class BastionGateTest {

    @Test
    fun aHostCategoryNoticePassesNotifyDamageOffAndACourtesyNoticeDoesNot(): Unit = runBlocking {
        ShellRig(ShellSettings(notifyDamage = false)).use { r ->
            r.shell.start(); r.settle("start")
            r.shell.services.notifyInternal("link", "radio floor back to 40 ms")
            r.settle("a courtesy notice")
            assertEquals(emptyList(), r.posted(), "Notify · Damage off still filters an app-less notice without gatedBy")
            r.shell.notifyFromHost("bastion", "Fresh tank nearly full · 91 · 08:12", "fresh_full", "Bastion")
            waitUntil(10_000, 10, { "the host's notice is up (${r.posted()})" }) {
                r.shell.notifications.current?.body == "Fresh tank nearly full · 91 · 08:12" }
            val n = r.shell.notifications.current!!
            assertEquals("DAMAGE · bastion", n.source)
            assertEquals("fresh_full", n.thread)
            assertEquals("Bastion", n.gatedBy)
            assertNull(n.appId, "it names no app")
            assertEquals(0, r.urgent.get(), "never the host's urgent hook")
            r.shell.stop()
        }
    }

    @Test
    fun aTapOnlyDismissesIt(): Unit = runBlocking {
        ShellRig(ShellSettings(notifyDamage = false)).use { r ->
            r.shell.start(); r.settle("start")
            r.shell.notifyFromHost("bastion", "Water pump fault · on · 08:12", "pump_fault", "Bastion")
            waitUntil(10_000, 10, { "the box took focus (${r.posted()})" }) {
                r.shell.notifications.current?.thread == "pump_fault" && r.shell.notifications.focused }
            r.shell.postGesture(EvenHubMsg.EV_CLICK)
            r.settle("the tap")
            assertNull(r.shell.notifications.current, "a tap dismisses it")
            assertTrue(r.posted().none { it.body.contains("not available") }, "no app to open, nothing refused: ${r.posted()}")
            r.shell.stop()
        }
    }

    @Test
    fun theMarkSurvivesARestart(): Unit = runBlocking {
        ShellRig(ShellSettings(notifyDamage = false)).use { r ->
            r.shell.start(); r.settle("start")
            r.shell.notifyFromHost("bastion", "first", "a", "Bastion")
            r.shell.notifyFromHost("bastion", "second", "b", "Bastion")
            waitUntil(10_000, 10, { "both posted (${r.posted()})" }) { r.posted().size == 2 }
            r.shell.stop()
            r.shell.start(); r.settle("restart")
            val back = r.posted()
            assertEquals(listOf("first", "second"), back.map { it.body }, "both restored")
            assertTrue(back.all { it.gatedBy == "Bastion" }, "the mark restored: ${back.map { it.gatedBy }}")
            r.shell.stop()
        }
    }
}
