package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.HostBackend
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.NoticeMirror
import wm.damage.core.app.Notices
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.WindowState
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.tmux.TmuxConfig
import wm.damage.core.windows.tmux.TmuxCore
import wm.damage.core.windows.tmux.TmuxGlasses

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, pieces B9 and B14: Tmux's glasses presenter in its two arrangements. On the PC a waiting edge is raised
 * once by the core into the record and the shell shows today's one box from it; a session muted on the glasses raises
 * nothing more. On the phone the rows and mutes are the PC record's: one changed on the glasses reaches the PC, one
 * changed in a browser reaches the glasses. A scripted provider, a shell on the simulator, a PC record and the `app`
 * lane on loopback port 0, temp directories.
 */
class TmuxArrangementsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("damage-tmux-arr")
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-tmux-arr")).also { it.open() }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    private fun state() = WindowState(TmuxCore.WINDOW, host.read(TmuxCore.WINDOW).third.items)

    @Test
    fun onThePc_aWaitingEdgeIsTodaysOneBoxFromTheRecord_andAMuteOnTheGlassesSilencesTheNext(): Unit = runBlocking {
        val p = TestTmux()
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)),
            Persistence(dir.resolve("state.json")), null, scope)
        val mirror = NoticeMirror(HostBackend(host, "local", "notices-test"))
        shell.noticeInput = mirror
        mirror.attach(shell.noticeSink)
        val core = TmuxCore().also { it.attachAlerts(p, NoticeCore(host)) { state() } }
        val win = TmuxGlasses(FakeText(), p, scope, core, LocalWindowSource(core, HostBackend(host, "local", "glasses-test")))
        shell.register(win)
        shell.start()
        suspend fun settle() = waitUntil(10_000, 10, { "shell did not settle: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        settle()
        // the provider's order (S1-3 fix A5): the status that carries the waiting session, then its alert
        p.sessions = listOf(p.sessions[0].copy(waiting = true, lastLine = "Do you want to make this edit?"), p.sessions[1])
        p.status()
        p.alert(p.sessions[0])
        waitUntil(10_000, 10, { "no box" }) { shell.notifications.active }
        settle()
        val cur = assertNotNull(shell.notifications.current)
        assertNotNull(cur.record, "the box is the record's notice")
        assertEquals("DAMAGE · tmux", cur.source, "today's source")
        assertEquals("beardos/claude", cur.thread, "one box per session, as today")
        assertEquals("claude wants input: Do you want to make this edit?", cur.body, "today's box: the glasses add the line")
        assertEquals("claude wants input", cur.record?.let { Notices.parse(it.id, host.read(Notices.WINDOW).third.items["rec.notices.${it.id}"]!!.value) }?.body,
            "the record holds no pane content")
        assertEquals("tmux", cur.appId)
        assertEquals("session:beardos:claude", cur.target)
        assertEquals(0, shell.notifications.queueDepth, "one box")
        assertTrue(win.dirty, "the waiting edge marks the window")
        // mute claude on the glasses: the field first, the op after; the next edge raises nothing
        shell.services.runOnShell {
            win.open("session:beardos:claude")
            (win.view() as WindowView.CanvasView).onTap!!()                                    // → keys
            (win.view() as WindowView.ListView).onCommit(TmuxConfig.DEFAULT_QUICK_KEYS.size + 3)  // → Session…
            (win.view() as WindowView.ListView).onCommit(0)                                    // Alerts: on — tap to mute
        }
        eventually("the glasses' mute reached the record") { TmuxCore.muted(state(), "beardos", "claude") }
        val before = host.read(Notices.WINDOW).third.items.size
        p.alert(p.sessions[0].copy(waiting = true, lastLine = "again"))
        repeat(4) { host.executor.call {} }
        assertEquals(before, host.read(Notices.WINDOW).third.items.size, "a muted session raises nothing")
        assertEquals("1", host.read(Notices.WINDOW).third.items["rec.notices.tmux:beardos/claude"]?.let { Notices.parse("tmux:beardos/claude", it.value)?.occ })
        shell.stop()
        win.detach()
        core.detachAlerts()
        mirror.detach(shell.noticeSink)
    }

    @Test
    fun onThePhone_theRowsAndMutesAreThePcRecords(): Unit = runBlocking {
        val rig = ReaderRig(scope)
        try {
            val h = rig.pc()
            val srv = rig.serve(h)
            val wc = rig.phone(srv.boundPort)
            val services = ReaderServices()
            val core = TmuxCore()
            val src = rig.keep(LocalWindowSource(core, wc))
            val win = TmuxGlasses(FakeText(), TestTmux(), scope, core, src)
            win.onRegistered(services)
            readerAwait("the PC shares Tmux") { wc.shares(core.window) }
            val row = win.appSettings().first { it.name == "Context rows" }
            services.onLoop { row.apply("off") }
            assertEquals("off", row.current())
            readerAwait("the glasses' row reached the PC record") {
                h.read(core.window).third.items["rec.tmux.glasses.context"]?.let { it.value == JsonPrimitive(false) && it.by == "phone" } == true
            }
            // a mute and the Size row set in a browser reach the glasses
            val got = ArrayList<Answer>()
            h.act(Op("web-00aa01", OpId.Queued("w1", 1), core.window, TmuxCore.MUTE_ACTION, listOf("mute.beardos/build"),
                JsonPrimitive(true), at = System.currentTimeMillis())) { got += it }
            h.act(Op("web-00aa01", OpId.Queued("w1", 2), core.window, TmuxCore.SET, listOf(TmuxCore.SIZE),
                JsonPrimitive(352), at = System.currentTimeMillis())) { got += it }
            h.executor.call {}
            assertTrue(got.all { it is Answer.Seen }, "$got")
            readerAwait("the browser's Size reached the glasses") { services.onLoop { win.preferredHeight } == 352 }
            readerAwait("the browser's mute reached the glasses") { TmuxCore.muted(src.state(), "beardos", "build") }
            win.detach()
        } finally {
            rig.close()
        }
    }
}
