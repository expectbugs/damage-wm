package wm.damage.core

import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.WindowView
import wm.damage.core.windows.reader.ReaderAnchor
import wm.damage.core.windows.reader.ReaderCore

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4, as L1 C1 rules it — a book's place on the glasses, the latest input winning: an unsent place
 * coalesces with the next, taking its place and its input time; another device's newer place leaves the page where he
 * reads and the actions level offers `Go there`; reading on is a later input and applies, nothing said; an entry takes
 * another device's newer place, never this device's own older one, nor one older than this view's own input.
 */
class ReaderPositionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = ReaderRig(scope)

    @AfterTest
    fun tearDown() {
        rig.close()
        scope.cancel()
    }

    private suspend fun openFirst(g: wm.damage.core.windows.reader.ReaderGlasses, id: String) {
        assertTrue(g.open("book:$id"))
        readerAwait("the book opens (${g.title()})") { g.title().contains("p.") }
    }

    @Test
    fun anUnsentPlaceCoalescesWithTheNext_takingItsPlaceAndItsInputTime(): Unit = runBlocking {
        val wc = rig.phone(ServerSocket(0).use { it.localPort })       // the PC away: nothing is sent
        wc.executor.call {
            wc.submit(ReaderCore.WINDOW, ReaderCore.placeDraft("a", ReaderAnchor(10), 1_000L))
            wc.submit(ReaderCore.WINDOW, ReaderCore.placeDraft("a", ReaderAnchor(20), 2_000L))
        }
        val ops = wc.queue.ops()
        assertEquals(1, ops.size, "one op for the item: $ops")
        assertEquals(20, anchorOffset(ops[0].result), "the latest place is carried")
        assertEquals(2_000L, ops[0].at, "with the latest input's time")
    }

    @Test
    fun anotherDevicesPlaceLeavesThePage_offersGoThere_andReadingOnApplies(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        openFirst(g, ids[0])
        services.onLoop { g.doc().topLine = 10 }
        services.onLoop { g.saveSubState() }                              // the save point
        readerAwait("the glasses' place reached the record") { rig.placeOffset(h, ids[0]) != null }
        val mine = rig.placeOffset(h, ids[0])!!
        assertEquals("local", rig.place(h, ids[0])!!.by)
        // a save point with nothing moved sends nothing (dec. A.10: today's save points, and only what changed)
        val seq = h.seq
        services.onLoop { g.saveSubState() }
        h.executor.call {}
        assertEquals(seq, h.seq, "nothing moved, nothing sent")

        // the phone reads on, far ahead
        assertTrue(rig.otherDevicePlace(h, "phone", "p1", 1, ids[0], 9_000) is Answer.Seen)
        readerAwait("Go there is offered first (${services.onLoop { g.actionLabels() }})") { services.onLoop { g.actionLabels() }.firstOrNull() == "Go there" }
        assertEquals(10, g.doc().topLine, "the page stays where he reads")

        // reading on here is the later input: it applies, and nothing is said
        services.onLoop { g.doc().topLine = 12 }
        services.onLoop { g.saveSubState() }
        readerAwait("reading on applies") { rig.placeOffset(h, ids[0]).let { it != null && it != 9_000 } }
        assertNotEquals(mine, rig.placeOffset(h, ids[0]))
        assertEquals("local", rig.place(h, ids[0])!!.by)
        h.executor.call {}
        readerAwait("Go there is no longer offered") { services.onLoop { g.actionLabels() }.firstOrNull() != "Go there" }
        assertEquals(emptyList(), services.notices)
    }

    /**
     * Fix round 1, ruling 1.2: while another device's place stands for the open book, a relayout (the text column's
     * width moves: a height or safe-area change; a font change takes the same path) sends no place and raises no
     * notice (it would move the record back to the glasses' page with no input); the next real input applies; a
     * relayout after it sends nothing either. Topline 12 and 15 are the second line of a paragraph in the rig's books,
     * whose start moves with the width.
     */
    private suspend fun relayoutsSendNothing(g: wm.damage.core.windows.reader.ReaderGlasses, services: ReaderServices,
                                             h: wm.damage.core.app.AppHost, id: String, idle: suspend () -> Unit) {
        services.onLoop { g.doc().topLine = 12 }
        services.onLoop { g.saveSubState() }
        readerAwait("the glasses' place reached the record") { rig.placeOffset(h, id) != null }
        idle()
        assertTrue(rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, id, 9_000) is Answer.Seen)
        readerAwait("Go there is offered") { services.onLoop { g.actionLabels() }.firstOrNull() == "Go there" }
        suspend fun relayout(width: Int) {
            val before = rig.placeOffset(h, id)
            services.width = width
            services.onLoop { g.onLayoutChanged() }
            readerAwait("the book is wrapped for $width") { services.onLoop { g.laidOutWidth() } == width }
            val seq = h.seq
            services.onLoop { g.saveSubState() }                      // the next save point, no input on Reader
            idle()
            delay(200)
            assertEquals(seq, h.seq, "a relayout at width $width sent a place")
            assertEquals(before, rig.placeOffset(h, id), "the record keeps its place")
        }
        relayout(400)
        assertEquals(9_000, rig.placeOffset(h, id), "the other device's place stands")
        // the next real input applies
        services.onLoop { g.doc().topLine = 15 }
        services.onLoop { g.saveSubState() }
        readerAwait("reading on applies") { rig.placeOffset(h, id) != 9_000 }
        idle()
        relayout(560)
        assertEquals(emptyList(), services.notices)
    }

    @Test
    fun aRelayoutSendsNoPlace_andSaysNothing_onThePc(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        openFirst(g, ids[0])
        relayoutsSendNothing(g, services, h, ids[0]) { h.executor.call {} }
    }

    @Test
    fun aRelayoutSendsNoPlace_andSaysNothing_onThePhone(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val services = ReaderServices()
        val g = rig.phoneGlasses(wc, srv.boundPort, services)
        readerAwait("the lane is up") { wc.client?.contractState?.shared(ReaderCore.WINDOW) == true }
        openFirst(g, ids[0])
        relayoutsSendNothing(g, services, h, ids[0]) { readerAwait("the phone's queue drained") { wc.queue.waiting == 0 } }
    }

    /**
     * The last stage, the focused read's S1 (R1 2.2's class on the glasses: an item this device wrote clears the
     * offer): a browser moves the place and `Go there` is offered; reading on applies, so the record now holds this
     * device's place and the other device's is gone from it — `Go there` is no longer offered. [idle] drains the path
     * from the record to the presenter, so each check is one evaluation.
     */
    private suspend fun anOwnPlaceAppliedEndsTheOffer(g: wm.damage.core.windows.reader.ReaderGlasses, services: ReaderServices,
                                                      h: wm.damage.core.app.AppHost, id: String, me: String, idle: suspend () -> Unit) {
        services.onLoop { g.doc().topLine = 10 }
        services.onLoop { g.saveSubState() }
        readerAwait("the glasses' place reached the record") { rig.placeOffset(h, id) != null }
        idle()
        assertTrue(rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, id, 9_000) is Answer.Seen)
        readerAwait("Go there is offered") { services.onLoop { g.actionLabels() }.firstOrNull() == "Go there" }
        services.onLoop { g.doc().topLine = 14 }
        services.onLoop { g.saveSubState() }
        readerAwait("reading on applies") { rig.placeOffset(h, id) != 9_000 }
        idle()
        assertEquals(me, rig.place(h, id)!!.by, "the record holds this device's place")
        assertNotEquals("Go there", services.onLoop { g.actionLabels() }.firstOrNull(),
            "the other device's place is no longer in the record: ${services.onLoop { g.actionRows() }}")
    }

    @Test
    fun anOwnPlaceAppliedEndsTheOffer_onThePc(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        openFirst(g, ids[0])
        anOwnPlaceAppliedEndsTheOffer(g, services, h, ids[0], "local") { h.executor.call {} }
    }

    @Test
    fun anOwnPlaceAppliedEndsTheOffer_onThePhone(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val services = ReaderServices()
        val g = rig.phoneGlasses(wc, srv.boundPort, services)
        readerAwait("the lane is up") { wc.client?.contractState?.shared(ReaderCore.WINDOW) == true }
        openFirst(g, ids[0])
        anOwnPlaceAppliedEndsTheOffer(g, services, h, ids[0], wc.device) {
            readerAwait("the phone's queue drained") { wc.queue.waiting == 0 }
            wc.executor.call {}
        }
    }

    @Test
    fun aBrowserIsNamedAsABrowser_inGoThere(): Unit = runBlocking {
        // fix round 1, ruling 1.4: a web device's id is `web-` and six hex digits
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        openFirst(g, ids[0])
        services.onLoop { g.doc().topLine = 10 }
        services.onLoop { g.saveSubState() }
        readerAwait("saved") { rig.placeOffset(h, ids[0]) != null }
        assertTrue(rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, ids[0], 9_000) is Answer.Seen)
        readerAwait("offered") { services.onLoop { g.actionLabels() }.firstOrNull() == "Go there" }
        val row = services.onLoop { g.actionRows() }.first()
        assertTrue(" · a browser " in row.second && "web-" !in row.second, "the Go there row: $row")
    }

    @Test
    fun goThereTakesTheSharedPlace(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        openFirst(g, ids[0])
        services.onLoop { g.doc().topLine = 10 }
        services.onLoop { g.saveSubState() }
        readerAwait("saved") { rig.placeOffset(h, ids[0]) != null }
        assertTrue(rig.otherDevicePlace(h, "phone", "p1", 1, ids[0], 9_000) is Answer.Seen)
        readerAwait("offered") { services.onLoop { g.actionLabels() }.firstOrNull() == "Go there" }
        services.onLoop { (g.view() as WindowView.DocView).onTap() }                           // the actions level
        services.onLoop { (g.view() as WindowView.ListView).onCommit(0) }                      // Go there
        assertTrue(g.doc().topLine > 10, "the page moved to the shared place (${g.doc().topLine})")
        assertTrue(services.onLoop { g.actionLabels() }.first() != "Go there", "no longer offered")
        services.onLoop { g.saveSubState() }
        delay(200)
        assertEquals(9_000, rig.placeOffset(h, ids[0]), "taking a place writes nothing back")
        services.onLoop { g.doc().topLine += 2 }
        services.onLoop { g.saveSubState() }
        readerAwait("the next input applies") { rig.placeOffset(h, ids[0]) != 9_000 }
        assertEquals(emptyList(), services.notices)
    }

    @Test
    fun anEntryTakesAnotherDevicesPlace_neverThisDevicesOwnOlderOne(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        openFirst(g, ids[0])
        services.onLoop { g.doc().topLine = 10 }
        services.onLoop { g.saveSubState() }
        readerAwait("saved") { rig.placeOffset(h, ids[0]) != null }
        // read on past the last save point, then an entry (the switcher): the record holds this device's own older
        // place, which is not another device's — the page stays
        services.onLoop { g.doc().topLine = 20 }
        services.onLoop { g.onActivate(services, ActivationSource.SWITCHER) }
        assertEquals(20, g.doc().topLine, "an entry never takes this device's own older place")
        // another device moves it: the next entry takes it
        assertTrue(rig.otherDevicePlace(h, "phone", "p1", 1, ids[0], 9_000) is Answer.Seen)
        readerAwait("the change reached the source") { stateOffset(g.source.state(), ids[0]) == 9_000 }
        assertEquals(20, g.doc().topLine, "no input, no move")
        services.onLoop { g.onActivate(services, ActivationSource.SWITCHER) }
        assertTrue(g.doc().topLine > 20, "the entry took the shared place (${g.doc().topLine})")
        services.onLoop { g.saveSubState() }
        delay(200)
        assertEquals(9_000, rig.placeOffset(h, ids[0]), "the entry wrote nothing back")
    }

    /**
     * Fix round 2, ruling 1.4 (review 2-A's finding 3), as L1 C1 leaves it: the PC's log refuses one line. The place op
     * it refuses (`retry`) changed nothing and is not said; the op behind it is taken on its own (no numbering); the
     * next save point sends the refused place again, and it applies.
     */
    @Test
    fun afterARetry_theNextSavePointSendsThePlaceAgain_andNothingIsSaid(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val failNext = java.util.concurrent.atomic.AtomicBoolean(false)
        val h = wm.damage.core.app.AppHost(java.nio.file.Files.createDirectories(rig.dir.resolve("pc")), wm.damage.core.app.AppHost.WINDOWS,
            wm.damage.core.app.CoreExecutor("damage-core-reader-test"), clock = { rig.now.get() },
            logSync = { if (failNext.getAndSet(false)) throw java.io.IOException("no space left on device (test)") else it.force(true) })
        h.open()
        rig.keep(AutoCloseable { h.close(); h.executor.shutdown() })
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        openFirst(g, ids[0])
        services.onLoop { g.doc().topLine = 10 }
        services.onLoop { g.saveSubState() }
        readerAwait("saved") { rig.placeOffset(h, ids[0]) != null }
        val first = rig.placeOffset(h, ids[0])
        // he reads on, and the local copy holds a place for the other book: two ops at the next save point
        services.onLoop { g.doc().topLine = 12; g.restoreSubState("book.${ids[1]}", kotlinx.serialization.json.buildJsonObject { put("off", 500) }) }
        failNext.set(true)
        // both ops are handed over before the first is judged (the executor held through the save point)
        val go = java.util.concurrent.CountDownLatch(1)
        h.executor.execute("held by the test") { go.await() }
        services.onLoop { g.saveSubState() }
        go.countDown()
        h.executor.call {}
        h.executor.call {}
        assertEquals(first, rig.placeOffset(h, ids[0]), "the refused op changed nothing")
        assertEquals(500, rig.placeOffset(h, ids[1]), "the op behind it applied on its own")
        services.onLoop { g.saveSubState() }                               // the next save point sends it again
        h.executor.call {}
        h.executor.call {}
        assertEquals(emptyList(), services.notices, "a retry is not said")
        assertNotEquals(first, rig.placeOffset(h, ids[0]), "the resent place applied")
    }

    @Test
    fun aStartNeverTakesThisDevicesOwnOlderPlace(): Unit = runBlocking {
        // an earlier run of this device wrote its place to the record, then read on and stopped before the next place
        // landed; this run has the local copy with that later input's time: the record's place is this device's own
        val ids = rig.writeBooks()
        val h = rig.pc()
        val t = rig.now.get()
        assertTrue(rig.otherDevicePlace(h, "local", "an-earlier-run", 1, ids[0], 3_000, at = t - 60_000) is Answer.Seen)
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        services.onLoop {
            g.restoreSubState("book.${ids[0]}", kotlinx.serialization.json.buildJsonObject { put("off", 3_300); put("at", t - 30_000) })
            g.restoreState(kotlinx.serialization.json.buildJsonObject { put("level", "LIBRARY") })
        }
        assertTrue(g.open("book:${ids[0]}"))
        readerAwait("opens") { g.title().contains("p.") }
        val kept = services.onLoop { g.saveSubState() }["book.${ids[0]}"]!!["off"]!!.jsonPrimitive.intOrNull!!
        assertTrue(kept > 3_200, "the local copy's later place is kept, not the record's own older one ($kept)")
        readerAwait("and it goes to the record") { rig.placeOffset(h, ids[0]) == kept }
    }

    /** L1 C1, in place of S1-1's ledger: after a restart the local copy's later place (its input time beside it)
     *  outranks another device's older place: the start does not take it, and the place goes to the record. */
    @Test
    fun aRestartKeepsALaterLocalPlace_overAnotherDevicesOlderOne(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val t = rig.now.get()
        assertTrue(rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, ids[0], 9_000, at = t - 60_000) is Answer.Seen)
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        services.onLoop {
            g.restoreSubState("book.${ids[0]}", kotlinx.serialization.json.buildJsonObject { put("off", 9_100); put("at", t - 30_000) })
            g.restoreState(kotlinx.serialization.json.buildJsonObject { put("level", "LIBRARY") })
        }
        assertTrue(g.open("book:${ids[0]}"))
        readerAwait("opens") { g.title().contains("p.") }
        val kept = services.onLoop { g.saveSubState() }["book.${ids[0]}"]!!["off"]!!.jsonPrimitive.intOrNull!!
        assertTrue(kept > 9_000, "the entry does not take the older place of another device ($kept)")
        readerAwait("the later place goes to the record") { rig.placeOffset(h, ids[0]) == kept }
        assertEquals(emptyList(), services.notices)
    }

    /**
     * L1 C1, the case the ruling names (SPINE §9's first worry): he reads on the glasses while the phone cannot reach
     * the PC, then reads at the desk, then the phone reconnects. The glasses' places, older inputs, do not overwrite
     * the desk's; the open book offers `Go there` for it; nothing is said.
     */
    @Test
    fun placesReadOfflineDoNotOverwriteALaterPlaceSetAtTheDesk(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val booksSrv = rig.serve(h)                                       // the books (the lane on another port, away)
        val lanePort = ServerSocket(0).use { it.localPort }
        val wc = rig.phone(lanePort)
        val services = ReaderServices()
        val g = rig.phoneGlasses(wc, booksSrv.boundPort, services)
        openFirst(g, ids[0])
        services.onLoop { g.doc().topLine = 20 }
        services.onLoop { g.saveSubState() }                              // read offline: queued on the phone
        readerAwait("queued") { wc.queue.ops().any { it.action == ReaderCore.PLACE } }
        rig.now.addAndGet(60_000)                                         // a minute later, at the desk
        assertTrue(rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, ids[0], 9_000) is Answer.Seen)
        rig.serve(h, lanePort)                                            // the phone reaches the PC again
        readerAwait("the phone's queue drained") { wc.queue.waiting == 0 }
        h.executor.call {}
        assertEquals(9_000, rig.placeOffset(h, ids[0]), "the desk's later place stands")
        assertEquals("web-3fa9c1", rig.place(h, ids[0])!!.by)
        readerAwait("Go there is offered") { services.onLoop { g.actionLabels() }.firstOrNull() == "Go there" }
        assertEquals(20, g.doc().topLine, "the page stays where he read")
        assertEquals(emptyList(), services.notices)
    }
}
