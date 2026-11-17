package wm.damage.core

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.content.RemoteContent
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.reader.ReaderGlasses

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, fix round 1, ruling 1.8 (review 1 A's finding 9): the save a session start makes after the restore
 * (`Shell.startLocked` asks each window's sub-records to learn which keys it holds) runs Reader's `sendChanges`, which
 * records every local place as sent. That is what keeps a later loss for the open book from being overwritten: the
 * loss gives the view the standing place as its next expectation, and without that record the next save point would
 * send the phone's older place expecting it, and apply, after the notice had said "not applied". A real shell over the
 * firmware simulator; the phone's working copy and the PC's record on loopback port 0 and temp directories.
 */
class ReaderSessionStartTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = ReaderRig(scope)

    @AfterTest
    fun tearDown() {
        rig.close()
        scope.cancel()
    }

    @Test
    fun aLossForTheOpenBookAfterASessionStart_isNotOverwrittenAtTheNextSavePoint(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val books = rig.keep(ContentHostServer(LocalContent(rig.books), 0, ReaderRig.TOKEN, app = null,
            bindAddress = InetAddress.getLoopbackAddress()).also { it.start() })
        val lanePort = ServerSocket(0).use { it.localPort }              // the PC's lane: out of reach at first
        val h = rig.pc()
        // read at the desk on the web after the PC's conversion
        assertEquals(Answer.Seen(), rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, ids[0], 9_000))
        val store = Persistence(rig.dir.resolve("phone-state.json"))
        store.put("window.reader.book.${ids[0]}", buildJsonObject { put("off", 0) })
        store.put("window.reader", buildJsonObject { put("level", "BOOK"); put("bookId", ids[0]) })
        val wc = rig.phone(lanePort)
        val core = ReaderCore(RemoteContent("127.0.0.1", books.boundPort, ReaderRig.TOKEN,
            Files.createDirectories(rig.dir.resolve("bookcache"))), scope)
        val g = ReaderGlasses(FakeText(), core, rig.keep(LocalWindowSource(core, wc)), scope, device = wc.device)
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), store, null, scope)
        shell.register(g)
        shell.start()                                                     // the session start: restore, then its save
        readerAwait("the open book is restored (${g.title()})") { g.title().contains("p.") }
        rig.serve(h, lanePort)                                            // the lane comes up: the migrated place loses
        readerAwait("the migrated place answered") { wc.queue.waiting == 0 }
        readerAwait("Go there is offered") { g.actionLabels().firstOrNull() == "Go there" }
        val seq = h.seq
        shell.stop()                                                      // the next save point, no input on Reader
        delay(300)
        h.executor.call {}
        assertEquals(9_000, rig.placeOffset(h, ids[0]), "the standing place is not overwritten by the phone's older one")
        assertEquals("web-3fa9c1", rig.place(h, ids[0])!!.by)
        assertEquals(seq, h.seq, "nothing was sent at the save point")
        assertEquals(0, wc.queue.waiting)
    }

    /**
     * Fix round 2, ruling 1.1 (a) (review 2-A's finding 1, its case a): after that loss the phone's process ends (an
     * APK install, a reboot, a stack rebuild) before he turns a page or takes `Go there`. The next process restores the
     * book's local place and the ledger, the session start's save runs, and nothing is sent: the browser's place stands
     * and the book opens at it. Before, the loss recorded the standing place as taken, the ledger carried it as known,
     * the restart seeded the next send's expectation from it, and the session start sent the phone's losing place
     * expecting the browser's — applied, with no input and nothing said.
     */
    @Test
    fun aLossForTheOpenBook_thenAPhoneRestart_sendsNothing_andTheBookOpensAtTheStandingPlace(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val books = rig.keep(ContentHostServer(LocalContent(rig.books), 0, ReaderRig.TOKEN, app = null,
            bindAddress = InetAddress.getLoopbackAddress()).also { it.start() })
        val lanePort = ServerSocket(0).use { it.localPort }
        val h = rig.pc()
        assertEquals(Answer.Seen(), rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, ids[0], 9_000))
        val store = Persistence(rig.dir.resolve("phone-state.json"))
        store.put("window.reader.book.${ids[0]}", buildJsonObject { put("off", 0) })
        store.put("window.reader", buildJsonObject { put("level", "BOOK"); put("bookId", ids[0]) })
        fun presenter(wc: wm.damage.core.app.WorkingCopy): ReaderGlasses {
            val core = ReaderCore(RemoteContent("127.0.0.1", books.boundPort, ReaderRig.TOKEN,
                Files.createDirectories(rig.dir.resolve("bookcache"))), scope)
            return ReaderGlasses(FakeText(), core, rig.keep(LocalWindowSource(core, wc)), scope, device = wc.device)
        }
        val first = rig.phone(lanePort)
        val g = presenter(first)
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), store, null, scope)
        shell.register(g)
        shell.start()
        readerAwait("the open book is restored (${g.title()})") { g.title().contains("p.") }
        rig.serve(h, lanePort)                                            // the lane comes up: the migrated place loses
        readerAwait("the migrated place answered") { first.queue.waiting == 0 }
        readerAwait("Go there is offered") { g.actionLabels().firstOrNull() == "Go there" }
        shell.stop()                                                      // a save point: the copy and the ledger
        g.detach()
        first.close()                                                     // the phone's process ends; the next starts
        h.executor.call {}
        val seq = h.seq
        val wc = rig.phone(lanePort)
        val g2 = presenter(wc)
        val shell2 = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), store, null, scope)
        shell2.register(g2)
        shell2.start()                                                    // restore, then the session start's save
        readerAwait("the book is restored (${g2.title()})") { g2.title().contains("p.") }
        readerAwait("the lane is up") { wc.client?.contractState?.shared(ReaderCore.WINDOW) == true }
        readerAwait("the phone's queue drained") { wc.queue.waiting == 0 }
        h.executor.call {}
        assertEquals(9_000, rig.placeOffset(h, ids[0]), "the browser's place is not replaced by the phone's losing one")
        assertEquals(seq, h.seq, "nothing was sent at the session start")
        readerAwait("the book opens at the standing place (line ${g2.doc().topLine})") { g2.doc().topLine > 0 }
        shell2.stop()                                                     // the next save point, no input
        delay(300)
        h.executor.call {}
        assertEquals(9_000, rig.placeOffset(h, ids[0]))
        assertEquals("web-3fa9c1", rig.place(h, ids[0])!!.by)
        assertEquals(seq, h.seq, "nothing was sent")
        assertEquals(0, wc.queue.waiting)
    }

    /**
     * Fix round 2, ruling 1.2 (review 2-A's findings 2 and 5): a kept loss ends at the SAYING, not at the hand-off. A
     * loss handed to Reader while the shell handles a Shutdown is posted behind it, and the loop's drain drops the post;
     * the view keeps the loss until it has been said, so the next session says it — once, and not again at a later
     * session or after a stack rebuild over the same source while the process lives. A real shell over the firmware
     * simulator and the PC's own record in a temp directory; the saying is read from the presenter's log line, which its
     * notice call follows. The order is made as `QueueWhileDownTest` makes it: the loop held inside one message, the
     * stop's Shutdown queued behind it (an undispatched launch runs `stop()` past its post), the loss behind that.
     */
    @Test
    fun aLossHandedOverWhileTheShellShutsDown_isSaidAtTheNextSession_once(): Unit = runBlocking { lossBehindTheShutdown(rebuild = false) }

    /** The same, with a stack rebuild before the next session (the PC's source outlives its stacks): the next
     *  presenter is handed the loss its predecessor never said, and says it once. */
    @Test
    fun aLossHandedOverWhileTheShellShutsDown_isSaidByTheNextStack_once(): Unit = runBlocking { lossBehindTheShutdown(rebuild = true) }

    private suspend fun lossBehindTheShutdown(rebuild: Boolean) {
        val ids = rig.writeBooks()
        val h = rig.pc()
        assertEquals(Answer.Seen(), rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, ids[1], 9_000))
        val said = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sink = wm.damage.core.util.Log.Sink { _, tag, m -> if (tag == "reader" && "not applied (place.${ids[1]})" in m) said += m }
        wm.damage.core.util.Log.addSink(sink)
        try {
            val core = ReaderCore(LocalContent(rig.books), scope)
            val src = rig.keep(LocalWindowSource(core, wm.damage.core.app.HostBackend(h, "local", "glasses")))
            val store = Persistence(rig.dir.resolve("pc-state.json"))
            fun stack(): Pair<ReaderGlasses, Shell> {
                val g = ReaderGlasses(FakeText(), core, src, scope)
                val sh = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), store, null, scope)
                sh.register(g)
                return g to sh
            }
            val (g, shell) = stack()
            shell.start()
            val inLoop = kotlinx.coroutines.CompletableDeferred<Unit>()
            val go = java.util.concurrent.CountDownLatch(1)
            shell.services.runOnShell { inLoop.complete(Unit); go.await() }      // the loop stays inside this message
            inLoop.await()
            val stopping = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { shell.stop() }
            // an op of this view that expected nothing there: it loses, and its answer is posted behind the Shutdown
            g.source.act(ReaderCore.placeDraft(ids[1], wm.damage.core.windows.reader.ReaderAnchor(200),
                wm.damage.core.app.Expect.Value(kotlinx.serialization.json.JsonNull)))
            h.executor.call {}
            h.executor.call {}                                                // the op judged, its answer handed on
            go.countDown()
            stopping.join()
            assertEquals(emptyList(), said, "the post behind the Shutdown was drained, not run")
            val next = if (rebuild) { g.detach(); stack().second } else shell
            next.start()                                                      // the next session
            readerAwait("said at the next session") { said.isNotEmpty() }
            next.stop()
            next.start()                                                      // a later session
            delay(300)
            next.stop()
            if (!rebuild) g.detach()                                          // a stack rebuild over the same source
            val (_, shell2) = stack()
            shell2.start()
            delay(300)
            shell2.stop()
            assertEquals(1, said.size, "said once while the process lives: $said")
        } finally {
            wm.damage.core.util.Log.removeSink(sink)
        }
    }
}
