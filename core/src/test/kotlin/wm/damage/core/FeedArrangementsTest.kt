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
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Record
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.feed.FeedCore
import wm.damage.core.windows.feed.FeedGlasses
import wm.damage.core.windows.feed.ScriptedFeed

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, pieces B2 and B6 (`CONTRACT.md` §7.2, §11.1): Feed's glasses presenter in its two arrangements. On the PC
 * a new item is raised once by the core into the record and the shell shows today's box from it. On the phone the read
 * marks and rows are the PC record's: an open on the glasses reaches the PC, a row set in a browser reaches the
 * glasses. A scripted feed, a shell on the simulator, a PC record and the `app` lane on loopback port 0, temp
 * directories; no site is reached.
 */
class FeedArrangementsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("damage-feed-arr")
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-feed-arr")).also { it.open() }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun onThePc_aNewItemIsRaisedOnceByTheCore_andShownAsTodaysBox(): Unit = runBlocking {
        val feed = ScriptedFeed()
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)),
            Persistence(dir.resolve("state.json")), null, scope)
        val mirror = NoticeMirror(HostBackend(host, "local", "notices-test"))
        shell.noticeInput = mirror
        mirror.attach(shell.noticeSink)
        val core = FeedCore(feed).also { it.startOnPc(host, NoticeCore(host)) { _, _ -> } }
        val win = FeedGlasses(FakeText(), feed, scope, core, LocalWindowSource(core, HostBackend(host, "local", "glasses-test")))
        shell.register(win)
        shell.start()
        waitUntil(10_000, 10, { "shell did not settle" }) { shell.isQuiescent() }
        // the row set on the glasses: the field, then its op into the record the core reads
        shell.services.runOnShell { win.appSettings().first { it.name == "Notify · Reddit" }.apply("on") }
        waitUntil(10_000, 10, { "the row did not reach the record" }) {
            FeedCore.bool(host.read(FeedCore.WINDOW).third.items[Record.key(FeedCore.WINDOW, FeedCore.NOTIFY_REDDIT)]?.value) == true
        }
        feed.fireNew("popular", "The first one")                  // a first sight: the baseline, nothing raised
        repeat(4) { host.executor.call {} }
        val next = feed.fireNew("popular", "A made-up new story")
        waitUntil(10_000, 10, { "no box" }) { shell.notifications.active }
        waitUntil(10_000, 10, { "shell did not settle" }) { shell.isQuiescent() }
        val cur = assertNotNull(shell.notifications.current)
        assertEquals("DAMAGE · feed", cur.source, "today's source")
        assertEquals("popular · A made-up new story", cur.body, "today's words, through the window's face")
        assertEquals("feed", cur.appId)
        assertEquals("item:popular:${next.id}", cur.target)
        assertEquals(0, shell.notifications.queueDepth, "one box")
        assertTrue(win.dirty, "the record's notice marks the window dirty, as today's raise did")
        shell.stop()
        win.detach()
        core.stop()
    }

    @Test
    fun onThePhone_theReadMarksAndTheRowsAreThePcRecords(): Unit = runBlocking {
        val rig = ReaderRig(scope)
        try {
            val h = rig.pc()
            val srv = rig.serve(h)
            val wc = rig.phone(srv.boundPort)
            val services = ReaderServices()
            val feed = ScriptedFeed()
            val core = FeedCore()
            val win = FeedGlasses(FakeText(), feed, scope, core, rig.keep(LocalWindowSource(core, wc)), device = wc.device)
            win.onRegistered(services)
            readerAwait("the PC shares Feed") { wc.shares(core.window) }
            // an open on the glasses: read on open, the source's list to the PC
            val it = feed.items("popular", 2, 1).items[0]
            services.onLoop { assertTrue(win.open("item:popular:${it.id}")) }
            readerAwait("the read mark reached the PC record") {
                FeedCore.strings(h.read(FeedCore.WINDOW).third.items[Record.key(FeedCore.WINDOW, "read.popular")]?.value) == listOf(it.id)
            }
            // a row set in a browser reaches the glasses' row
            val got = ArrayList<Answer>()
            h.act(Op("web-00feed", OpId.Queued("w1", 1), FeedCore.WINDOW, FeedCore.A_SET, listOf(FeedCore.NOTIFY_SLASHDOT),
                JsonPrimitive(true), at = rig.now.get())) { got += it }
            h.executor.call {}
            assertTrue(got.single() is Answer.Seen, "$got")
            val row = win.appSettings().first { it.name == "Notify · Slashdot" }
            readerAwait("the browser's row reached the glasses") { services.onLoop { row.current() } == "on" }
            win.detach()
        } finally {
            rig.close()
        }
    }
}
