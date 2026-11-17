package wm.damage.core

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
 * Build S1-1, fix round 1, ruling 1.8 (review 1 A's finding 9), as L1 C1 leaves it: the save a session start makes
 * after the restore (`Shell.startLocked` asks each window's sub-records to learn which keys it holds) runs Reader's
 * `sendChanges`, which records every local place as sent. A local place with no input time of its own (one an older
 * build wrote) is older than any place the record holds, so it never overwrites another device's; and a later save
 * point with no input sends nothing. A real shell over the firmware simulator; the phone's working copy and the PC's
 * record on loopback port 0 and temp directories.
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
    fun aStaleLocalPlaceAfterASessionStart_neverOverwritesAnotherDevicesPlace(): Unit = runBlocking {
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
        rig.serve(h, lanePort)                                            // the lane comes up: the local place is older
        readerAwait("the local place answered") { wc.queue.waiting == 0 }
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
     * Fix round 2, ruling 1.1 (a), as L1 C1 leaves it: after that the phone's process ends (an APK install, a reboot, a
     * stack rebuild) before he turns a page or takes `Go there`. The next process restores the book's local place, the
     * session start takes the browser's newer place, its save sends nothing, and the book opens at it.
     */
    @Test
    fun aStaleLocalPlace_thenAPhoneRestart_sendsNothing_andTheBookOpensAtTheStandingPlace(): Unit = runBlocking {
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
        rig.serve(h, lanePort)                                            // the lane comes up: the local place is older
        readerAwait("the local place answered") { first.queue.waiting == 0 }
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
        // the first process's last answered op may be sent again (its queue line's `done` can miss the writer's end):
        // applied twice, it changes nothing
        assertEquals(9_000, rig.placeOffset(h, ids[0]), "the browser's place is not replaced by the phone's older one")
        assertEquals("web-3fa9c1", rig.place(h, ids[0])!!.by)
        readerAwait("the book opens at the standing place (line ${g2.doc().topLine})") { g2.doc().topLine > 0 }
        val started = h.seq
        shell2.stop()                                                     // the next save point, no input
        delay(300)
        h.executor.call {}
        assertEquals(9_000, rig.placeOffset(h, ids[0]))
        assertEquals("web-3fa9c1", rig.place(h, ids[0])!!.by)
        assertEquals(started, h.seq, "nothing was sent at the save point")
        assertTrue(seq <= started)
        assertEquals(0, wc.queue.waiting)
    }
}
