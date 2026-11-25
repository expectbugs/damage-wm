package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.HostBackend
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.NoticeRecord
import wm.damage.core.app.Notices
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.WindowState
import wm.damage.core.windows.tmux.TmuxCore

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, pieces B9 and B15: Tmux's core on a real PC record — a waiting edge raised once into the notice record
 * with today's body, thread and deep link (the pane's whole line, never cut), none for a muted session or with Alerts
 * off — and its rows as ops the record takes. A scripted provider; temp directories.
 */
class TmuxCoreTest {
    private val dir = Files.createTempDirectory("damage-tmux-core")
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-tmux-core")).also { it.open() }

    @AfterTest
    fun tearDown() {
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    private fun state() = WindowState(TmuxCore.WINDOW, host.read(TmuxCore.WINDOW).third.items)

    private fun notice(id: String): NoticeRecord? {
        repeat(4) { host.executor.call {} }           // the raise, its answer and the record's publish
        return host.read(Notices.WINDOW).third.items["rec.notices.$id"]?.let { Notices.parse(id, it.value) }
    }

    private fun write(action: String, item: String, v: Boolean, n: Long) {
        host.executor.call {}
        val done = java.util.concurrent.CountDownLatch(1)
        host.act(Op("phone", OpId.Queued("s", n), TmuxCore.WINDOW, action, listOf(item), JsonPrimitive(v), at = System.currentTimeMillis())) { done.countDown() }
        done.await()
    }

    @Test
    fun aWaitingEdgeIsRaisedOnceWithTodaysBox_noneWhenMutedOrWithAlertsOff() {
        val p = TestTmux()
        val core = TmuxCore()
        core.attachAlerts(p, NoticeCore(host)) { state() }
        val long = (0 until 120).joinToString("") { ('a' + it % 26).toString() } + "😀"
        p.alert(p.sessions[0].copy(waiting = true, lastLine = long))
        val r = notice("tmux:beardos/claude")
        // S1-3 fix A5: the record (readable at view level) carries no pane content; the glasses add the line
        assertEquals("claude wants input", r?.body, "the session wants input, without the pane's line (one host: no suffix)")
        assertTrue(listOf("abc", "😀").none { it in r!!.body }, "no pane content in the record")
        assertEquals("tmux", r?.app)
        assertEquals("session:beardos:claude", r?.target, "a tap opens that session's live view")
        assertEquals("Tmux", r?.gatedBy)
        assertEquals("1", r?.occ)
        p.alert(p.sessions[0].copy(waiting = true, lastLine = "again"))
        assertEquals("2", notice("tmux:beardos/claude")?.occ, "the next waiting edge is the next occurrence")
        // muted: nothing
        write(TmuxCore.MUTE_ACTION, TmuxCore.muteName("beardos", "build"), true, 1)
        p.alert(p.sessions[1].copy(waiting = true))
        assertNull(notice("tmux:beardos/build"), "a muted session raises nothing")
        // Alerts off: nothing
        write(TmuxCore.SET, TmuxCore.ALERTS, false, 2)
        p.alert(p.sessions[0].copy(waiting = true, lastLine = "third"))
        assertEquals("2", notice("tmux:beardos/claude")?.occ, "Alerts off raises nothing")
        // two hosts listed: the host joins the body, as today
        write(TmuxCore.SET, TmuxCore.ALERTS, true, 3)
        p.sessions = p.sessions + p.sessions[1].copy(host = "slappy")
        p.status()
        p.alert(p.sessions[2].copy(waiting = true, lastLine = "y/n"))
        assertEquals("build · slappy wants input", notice("tmux:slappy/build")?.body)
        core.detachAlerts()
        assertTrue(p.listeners.isEmpty())
    }

    @Test
    fun aPresenterRowChangeIsAnOpTheRecordTakes() {
        val core = TmuxCore()
        val src = LocalWindowSource(core, HostBackend(host, "local", "s-local"))
        src.act(TmuxCore.muteDraft("beardos", "claude", true))
        src.act(TmuxCore.setDraft(TmuxCore.SIZE, JsonPrimitive(288)))
        eventually("both rows in the record") { TmuxCore.muted(state(), "beardos", "claude") && TmuxCore.size(state()) == 288 }
        assertEquals(setOf("beardos/claude"), TmuxCore.mutedSet(state()))
        src.close()
    }
}
