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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.HostBackend
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.MemoryRecord
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.NoticeMirror
import wm.damage.core.app.NoticeRecord
import wm.damage.core.app.NoticeRef
import wm.damage.core.app.NoticeSink
import wm.damage.core.app.Notices
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.torrents.TorrentsCore
import wm.damage.core.windows.torrents.TorrentsGlasses

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q4 (the spec's §3.2, §2.1 "Mixed builds"; `CONTRACT.md` §7.2 item 1, §9.7 item 2, §11.1): the
 * glasses' presenter of Torrents in its two arrangements. On the PC a done is raised once by the core into the record
 * and the shell shows one box, today's box, from it. While the PC raises, the glasses raise nothing; where the record's
 * notices do not reach the presenter (its in-memory fallback, the tools) it raises today's notice. On the phone the rows and the search recents are the PC record's: a row changed on the glasses
 * reaches the PC, one changed in a browser reaches the glasses. A scripted provider, a shell on the simulator, a PC
 * record and the `app` lane on loopback port 0, temp directories.
 */
class TorrentsArrangementsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("damage-torrents-arr")
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-torrents-arr")).also { it.open() }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    /** The PC's arrangement (`DesktopStack` over `runShell`'s parts): the core raises into the record, the stack's
     *  shell shows the record through the process's mirror, the presenter reads the record as device `local`. */
    private inner class PcRig(recordNotices: () -> Boolean) {
        val provider = TestTorrents()
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)),
            Persistence(dir.resolve("state.json")), null, scope)
        val mirror = NoticeMirror(HostBackend(host, "local", "notices-test"))
        val core = TorrentsCore().also { it.attachNotices(provider, provider.epoch, NoticeCore(host)) }
        val win = TorrentsGlasses(FakeText(), provider, scope, core, LocalWindowSource(core, HostBackend(host, "local", "glasses-test")),
            recordNotices)

        /** What the mirror handed the shell (the record's notices, popped). */
        val pops = java.util.concurrent.CopyOnWriteArrayList<NoticeRecord>()

        init {
            shell.noticeInput = mirror
            mirror.attach(object : NoticeSink {
                override fun pop(rec: NoticeRecord) { pops += rec; shell.noticeSink.pop(rec) }
                override fun withdraw(ref: NoticeRef, queuedOnly: Boolean) = shell.noticeSink.withdraw(ref, queuedOnly)
            })
            shell.register(win)
        }

        suspend fun settle() = waitUntil(10_000, 10, { "shell did not settle: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
    }

    private suspend fun oneDoneBox(r: PcRig): Notifications.Notice {
        r.shell.start()
        r.settle()
        r.provider.event("done", "c0ffee01", "A made-up download (1080p)")
        waitUntil(10_000, 10, { "the mirror did not hand the record's notice on (${r.mirror.cursor})" }) { r.pops.isNotEmpty() }
        waitUntil(10_000, 10, { "the window was not marked dirty" }) { r.win.dirty }
        waitUntil(10_000, 10, { "no box" }) { r.shell.notifications.active }
        r.settle()
        val cur = assertNotNull(r.shell.notifications.current)
        assertEquals(0, r.shell.notifications.queueDepth, "one box: ${listOf(cur) + r.shell.notifications.queued()}")
        assertEquals(listOf("1"), r.pops.map { it.occ }, "raised once, handed on once")
        assertEquals(1, host.read(Notices.WINDOW).third.items.keys.count { it.endsWith(":c0ffee01") }, "one notice in the record")
        return cur
    }

    @Test
    fun onThePc_aDoneIsRaisedOnceByTheCore_andShownAsTodaysOneBox(): Unit = runBlocking {
        val r = PcRig { true }
        val cur = oneDoneBox(r)
        assertNotNull(cur.record, "the box is the record's notice: a dismiss elsewhere takes it away")
        assertEquals("DAMAGE · torrent", cur.source, "today's source")
        assertEquals("c0ffee01", cur.thread)
        assertEquals("done · A made-up download (1080p)", cur.body, "today's words, through the window's face")
        assertEquals("torrents", cur.appId)
        assertEquals("t:c0ffee01", cur.target)
        assertTrue(r.win.dirty, "the record's notice marks the window dirty, as today's raise does")
        r.shell.stop()
        r.win.detach()
    }

    /** Pins (the spec's Q4: "the glasses raise while the PC does"; the fallback's own raise). */
    @Test
    fun theGlassesRaiseNothingWhileThePcRaises_andTodaysNoticeWhereTheRecordsDoNotReachThem() {
        var pcRaises = true
        val services = ReaderServices()
        val p = TestTorrents()
        val core = TorrentsCore()
        val win = TorrentsGlasses(FakeText(), p, scope, core, LocalWindowSource(core, MemoryRecord(TorrentsCore.ACTIONS)), { pcRaises })
        win.onRegistered(services)
        p.event("done", "c0ffee01", "A made-up download (1080p)")
        assertEquals(emptyList(), services.notices, "the PC raises it: nothing here")
        assertTrue(win.dirty, "the event still marks the window dirty")
        pcRaises = false
        win.dirty = false
        p.event("error", "c0ffee03", "A made-up stopped one")
        assertEquals(listOf("torrent: error · A made-up stopped one"), services.notices.toList(), "no record notices: today's raise")
        assertTrue(win.dirty)
        win.detach()
    }

    @Test
    fun onThePhone_theRowsAndTheRecentsAreThePcRecords(): Unit = runBlocking {
        val rig = ReaderRig(scope)
        try {
            val h = rig.pc()
            val srv = rig.serve(h)
            val wc = rig.phone(srv.boundPort)
            val services = ReaderServices()
            val p = TestTorrents()
            val core = TorrentsCore()
            val src = rig.keep(LocalWindowSource(core, wc))
            val win = TorrentsGlasses(FakeText(), p, scope, core, src,
                recordNotices = { wc.shares(core.window) && wc.shares(Notices.WINDOW) }, device = wc.device)
            win.onRegistered(services)
            readerAwait("the PC shares Torrents and the notice record") { wc.shares(core.window) && wc.shares(Notices.WINDOW) }
            // a row changed on the glasses: the field at the commit, the op after it, to the PC
            val row = win.appSettings().first { it.name == "Notify · done" }
            services.onLoop { row.apply("off") }
            assertEquals("off", row.current())
            readerAwait("the glasses' row reached the PC record") {
                h.read(core.window).third.items["rec.torrents.notify.done"]?.let { it.value == JsonPrimitive(false) && it.by == "phone" } == true
            }
            // a search: the recents, one register
            services.onLoop { win.onTypedText("made up") }
            readerAwait("the search's recents reached the PC record") {
                h.read(core.window).third.items["rec.torrents.recents"]?.value == JsonArray(listOf(JsonPrimitive("made up")))
            }
            // a row changed in a browser reaches the glasses' row
            val got = ArrayList<Answer>()
            h.act(Op("web-00aa01", OpId.Queued("w1", 1), core.window, TorrentsCore.SET, listOf(TorrentsCore.NOTIFY_ERRORS),
                JsonPrimitive(false), at = System.currentTimeMillis())) { got += it }
            h.executor.call {}
            assertTrue(got.single() is Answer.Seen, "$got")
            val errors = win.appSettings().first { it.name == "Notify · errors" }
            readerAwait("the browser's row reached the glasses") { services.onLoop { errors.current() } == "off" }
            // while the PC raises the notices, the phone's provider events raise nothing here
            p.event("done", "c0ffee01", "A made-up download (1080p)")
            assertEquals(emptyList(), services.notices.filter { "done ·" in it })
            win.detach()
        } finally {
            rig.close()
        }
    }
}
