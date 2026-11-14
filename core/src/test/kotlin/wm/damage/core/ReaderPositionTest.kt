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
import wm.damage.core.app.Expect
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.WindowView
import wm.damage.core.windows.reader.ReaderAnchor
import wm.damage.core.windows.reader.ReaderCore

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 — a book's place as `CONTRACT.md` §2.4's position kind and dec. A.2 rule it on the glasses:
 * an unsent place coalesces with the next, keeping the first expectation; a place loses when another device moved it,
 * and the loss is said with both places; the page stays where he reads and the actions level offers `Go there`; an
 * entry takes the shared place — another device's, never this device's own older one; reading on never loses twice.
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
    fun anUnsentPlaceCoalescesWithTheNext_keepingTheFirstExpect(): Unit = runBlocking {
        val wc = rig.phone(ServerSocket(0).use { it.localPort })       // the PC away: nothing is sent
        wc.executor.call {
            wc.submit(ReaderCore.WINDOW, ReaderCore.placeDraft("a", ReaderAnchor(10), Expect.Value(JsonNull)))
            wc.submit(ReaderCore.WINDOW, ReaderCore.placeDraft("a", ReaderAnchor(20), Expect.Value(JsonPrimitive(ReaderAnchor(10).format()))))
        }
        val ops = wc.queue.ops()
        assertEquals(1, ops.size, "one op for the item: $ops")
        assertEquals(Expect.Value(JsonNull), ops[0].expect, "the first expectation is kept")
        assertEquals(20, anchorOffset(ops[0].result), "the latest place is carried")
    }

    @Test
    fun anotherDevicesPlaceLeavesThePage_offersGoThere_theFirstPlaceLosesSaid_andReadingOnNeverLosesTwice(): Unit = runBlocking {
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

        // reading on: the first place from this view loses, and is said with both places
        services.onLoop { g.doc().topLine = 12 }
        services.onLoop { g.saveSubState() }
        readerAwait("the loss is said") { services.notices.any { "was not applied" in it } }
        val said = services.notices.single { "was not applied" in it }
        assertTrue(said.startsWith("reader: the phone read on to p.") && "this place, p." in said, said)
        assertEquals(9_000, rig.placeOffset(h, ids[0]), "the losing place was not applied")
        assertEquals(12, g.doc().topLine, "the page still stays")

        // reading on again applies: the view was given the standing place
        services.onLoop { g.doc().topLine = 14 }
        services.onLoop { g.saveSubState() }
        readerAwait("reading on applies") { rig.placeOffset(h, ids[0]).let { it != null && it != 9_000 } }
        assertNotEquals(mine, rig.placeOffset(h, ids[0]))
        delay(200)
        assertEquals(1, services.notices.count { "was not applied" in it }, "never twice: ${services.notices}")
    }

    /**
     * Fix round 1, ruling 1.2: while another device's place stands for the open book, a relayout (the text column's
     * width moves: a height or safe-area change; a font change takes the same path) sends no place and raises no
     * notice; the next real input loses once and is said, as today; a relayout after that loss sends nothing either
     * (it would apply and move the record back to the glasses' page); reading on then applies. Topline 12 and 15 are
     * the second line of a paragraph in the rig's books, whose start moves with the width.
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
            services.width = width
            services.onLoop { g.onLayoutChanged() }
            readerAwait("the book is wrapped for $width") { services.onLoop { g.laidOutWidth() } == width }
            val seq = h.seq
            services.onLoop { g.saveSubState() }                      // the next save point, no input on Reader
            idle()
            delay(200)
            assertEquals(seq, h.seq, "a relayout at width $width sent a place")
            assertEquals(9_000, rig.placeOffset(h, id), "the record keeps the other device's place")
        }
        relayout(400)
        assertTrue(services.notices.none { "was not applied" in it }, "no notice for a relayout: ${services.notices}")
        // the next real input: loses once, said, as today (dec. A.2)
        services.onLoop { g.doc().topLine = 15 }
        services.onLoop { g.saveSubState() }
        readerAwait("the loss is said") { services.notices.any { "was not applied" in it } }
        relayout(560)
        // reading on applies
        services.onLoop { g.doc().topLine = 18 }
        services.onLoop { g.saveSubState() }
        readerAwait("reading on applies") { rig.placeOffset(h, id) != 9_000 }
        assertEquals(1, services.notices.count { "was not applied" in it }, "${services.notices}")
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
     * offer): a browser moves the place, the first save point loses and `Go there` is offered; reading on applies, so
     * the record now holds this device's place and the other device's is gone from it — `Go there` is no longer
     * offered. [idle] drains the path from the record to the presenter, so each check is one evaluation.
     */
    private suspend fun anOwnPlaceAppliedEndsTheOffer(g: wm.damage.core.windows.reader.ReaderGlasses, services: ReaderServices,
                                                      h: wm.damage.core.app.AppHost, id: String, me: String, idle: suspend () -> Unit) {
        services.onLoop { g.doc().topLine = 10 }
        services.onLoop { g.saveSubState() }
        readerAwait("the glasses' place reached the record") { rig.placeOffset(h, id) != null }
        idle()
        assertTrue(rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, id, 9_000) is Answer.Seen)
        readerAwait("Go there is offered") { services.onLoop { g.actionLabels() }.firstOrNull() == "Go there" }
        services.onLoop { g.doc().topLine = 12 }
        services.onLoop { g.saveSubState() }
        readerAwait("the loss is said") { services.notices.any { "was not applied" in it } }
        idle()
        assertEquals("Go there", services.onLoop { g.actionLabels() }.firstOrNull(), "offered after the loss")
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
    fun aBrowserIsNamedAsABrowser_inGoThereAndTheLossNotice(): Unit = runBlocking {
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
        services.onLoop { g.doc().topLine = 12 }
        services.onLoop { g.saveSubState() }
        readerAwait("the loss is said") { services.notices.any { "was not applied" in it } }
        val said = services.notices.single { "was not applied" in it }
        assertTrue(said.startsWith("reader: a browser read on to p.") && "web-" !in said, said)
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
        assertTrue(services.notices.none { "was not applied" in it }, "${services.notices}")
        services.onLoop { g.doc().topLine += 2 }
        services.onLoop { g.saveSubState() }
        readerAwait("the next input applies") { rig.placeOffset(h, ids[0]) != 9_000 }
        assertTrue(services.notices.none { "was not applied" in it }, "${services.notices}")
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
     * Fix round 2, ruling 1.1 (b): after a lost place the view has NOT taken the standing place (the page stayed, dec.
     * A.2), so an entry takes it (C§2.6) and the save after it sends nothing. Before, the loss recorded the standing
     * place as taken: the entry passed it over, `Go there` was cleared, and the next page turn applied over it.
     */
    private suspend fun aLossThenAnEntry(entry: (wm.damage.core.windows.reader.ReaderGlasses, ReaderServices) -> Unit) {
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
        services.onLoop { g.doc().topLine = 12 }
        services.onLoop { g.saveSubState() }
        readerAwait("the loss is said") { services.notices.any { "was not applied" in it } }
        h.executor.call {}
        val seq = h.seq
        services.onLoop { entry(g, services); g.saveSubState() }
        h.executor.call {}
        assertTrue(g.doc().topLine > 12, "the entry took the standing place (${g.doc().topLine})")
        assertTrue(services.onLoop { g.actionLabels() }.firstOrNull() != "Go there", "taken, so no longer offered")
        assertEquals(seq, h.seq, "nothing was sent after the entry")
        assertEquals(9_000, rig.placeOffset(h, ids[0]))
        assertEquals("web-3fa9c1", rig.place(h, ids[0])!!.by)
    }

    @Test
    fun afterALoss_aSessionStartOnThisInstanceTakesTheStandingPlace(): Unit = runBlocking {
        aLossThenAnEntry { g, _ -> g.restoreState(g.saveState()) }        // a wake, a link edge: the shell's restore
    }

    @Test
    fun afterALoss_aSwitcherEntryTakesTheStandingPlace(): Unit = runBlocking {
        aLossThenAnEntry { g, s -> g.onActivate(s, ActivationSource.SWITCHER) }
    }

    /**
     * Fix round 2, ruling 1.1 (c): while no session is live the shell holds a keyed post (the source's change) and runs
     * an unkeyed one (the answer) at once, so a loss can reach the presenter before the change that made it. A book
     * that is not open then takes the standing place when the change runs, and the next save point sends nothing.
     * Before, the loss recorded the standing place as taken: the change was passed over, the book kept the losing
     * place, and the save point sent it expecting the standing one — applied, with nothing said.
     */
    @Test
    fun aLossAnsweredBeforeItsChange_leavesABookThatIsNotOpenAtTheStandingPlace(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = HoldingServices()
        val g = rig.pcGlasses(h, services)
        services.onLoop {
            g.restoreSubState("book.${ids[1]}", kotlinx.serialization.json.buildJsonObject { put("off", 200) })
            g.restoreState(kotlinx.serialization.json.buildJsonObject { put("level", "LIBRARY") })
        }
        assertTrue(rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, ids[1], 9_000) is Answer.Seen)   // its change: held
        // an op of this device that expected nothing there (the phone's migration op is one): it loses
        g.source.act(ReaderCore.placeDraft(ids[1], ReaderAnchor(200), Expect.Value(JsonNull)))
        h.executor.call {}
        readerAwait("the loss is said") { services.notices.any { "was not applied" in it } }
        services.release()                                                 // the session starts: the change runs
        val off = services.onLoop { g.saveSubState() }["book.${ids[1]}"]!!["off"]!!.jsonPrimitive.intOrNull
        h.executor.call {}
        assertEquals(9_000, off, "the book that is not open holds the standing place")
        assertEquals(9_000, rig.placeOffset(h, ids[1]), "and the losing place was not sent over it")
        assertEquals("web-3fa9c1", rig.place(h, ids[1])!!.by)
    }

    /**
     * Fix round 2, ruling 1.4 (review 2-A's finding 3): the PC's log refuses one line. The place op it refuses
     * (`retry`) and the op numbered behind it (`gap`) took no number and changed nothing, so neither is said, and the
     * next save point sends both again expecting what they expected — they apply. Before, the resend expected the
     * value that was never applied and lost to the view's own older place ("the PC read on to …"), and the `gap` was
     * said as "place not saved: expected N".
     */
    @Test
    fun afterARetryAndAGap_theNextSendExpectsWhatTheOpExpected_andNothingIsSaid(): Unit = runBlocking {
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
        // both ops are numbered before the first is judged (the executor held through the save point), so the second
        // is refused `gap`
        val go = java.util.concurrent.CountDownLatch(1)
        h.executor.execute("held by the test") { go.await() }
        services.onLoop { g.saveSubState() }
        go.countDown()
        h.executor.call {}
        h.executor.call {}
        assertEquals(first, rig.placeOffset(h, ids[0]), "the refused ops changed nothing")
        assertEquals(null, rig.placeOffset(h, ids[1]))
        services.onLoop { g.saveSubState() }                               // the next save point sends both again
        h.executor.call {}
        h.executor.call {}
        assertEquals(emptyList(), services.notices, "a retry or a gap is not said, and the resend does not lose")
        assertNotEquals(first, rig.placeOffset(h, ids[0]), "the resent place applied")
        assertEquals(500, rig.placeOffset(h, ids[1]))
    }

    @Test
    fun aRestartKeepsTheRecordPlacesThisViewHadTaken(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        // the web read on; before the restart this view had taken that place, read a little further and stopped
        // before its last place reached the record
        assertTrue(rig.otherDevicePlace(h, "web", "w1", 1, ids[0], 9_000) is Answer.Seen)
        val taken = rig.place(h, ids[0])!!.value
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        services.onLoop {
            g.restoreSubState("book.${ids[0]}", kotlinx.serialization.json.buildJsonObject { put("off", 9_100) })
            g.restoreSubState(wm.damage.core.windows.reader.ReaderMigration.LEDGER,
                wm.damage.core.windows.reader.ReaderMigration.ledgerOf(mapOf(ids[0] to 9_100), "", -1, 5, false, 0,
                    mapOf(ids[0] to taken)))
            g.restoreState(kotlinx.serialization.json.buildJsonObject { put("level", "LIBRARY") })
        }
        assertTrue(g.open("book:${ids[0]}"))
        readerAwait("opens") { g.title().contains("p.") }
        val kept = services.onLoop { g.saveSubState() }["book.${ids[0]}"]!!["off"]!!.jsonPrimitive.intOrNull!!
        assertTrue(kept > 9_000, "the open is an entry, but the record's place is one this view had taken: kept ($kept)")
        readerAwait("its later place goes to the record") { rig.placeOffset(h, ids[0]) == kept }
        assertTrue(services.notices.none { "was not applied" in it }, "${services.notices}")
    }

    @Test
    fun aStartWithNoLedgerNeverTakesThisDevicesOwnOlderPlace(): Unit = runBlocking {
        // an earlier run of this device wrote its place to the record, then read on and stopped before the next place
        // landed; this run has the local copy and no ledger: the record's place is this device's own (its `by`), older
        val ids = rig.writeBooks()
        val h = rig.pc()
        assertTrue(rig.otherDevicePlace(h, "local", "an-earlier-run", 1, ids[0], 3_000) is Answer.Seen)
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        services.onLoop {
            g.restoreSubState("book.${ids[0]}", kotlinx.serialization.json.buildJsonObject { put("off", 3_300) })
            g.restoreState(kotlinx.serialization.json.buildJsonObject { put("level", "LIBRARY") })
        }
        assertTrue(g.open("book:${ids[0]}"))
        readerAwait("opens") { g.title().contains("p.") }
        val kept = services.onLoop { g.saveSubState() }["book.${ids[0]}"]!!["off"]!!.jsonPrimitive.intOrNull!!
        assertTrue(kept > 3_200, "the local copy's later place is kept, not the record's own older one ($kept)")
        readerAwait("and it goes to the record") { rig.placeOffset(h, ids[0]) == kept }
    }

    @Test
    fun aPlaceTheLedgerKnowsAndTheRecordNoLongerHoldsIsSentWithoutALoss(): Unit = runBlocking {
        // a restored PC record (or a fresh one) lacks a place this view knew: its next place expects what the record
        // holds — nothing — and applies, with no "not applied" said
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        services.onLoop {
            g.restoreSubState("book.${ids[0]}", kotlinx.serialization.json.buildJsonObject { put("off", 3_300) })
            g.restoreSubState(wm.damage.core.windows.reader.ReaderMigration.LEDGER,
                wm.damage.core.windows.reader.ReaderMigration.ledgerOf(mapOf(ids[0] to 3_300), "", -1, 5, false, 0,
                    mapOf(ids[0] to JsonPrimitive(ReaderAnchor(3_000).format()))))
            g.restoreState(kotlinx.serialization.json.buildJsonObject { put("level", "LIBRARY") })
        }
        assertTrue(g.open("book:${ids[0]}"))
        readerAwait("opens") { g.title().contains("p.") }
        val kept = services.onLoop { g.saveSubState() }["book.${ids[0]}"]!!["off"]!!.jsonPrimitive.intOrNull!!
        readerAwait("the place is in the record") { rig.placeOffset(h, ids[0]) == kept }
        delay(100)
        assertTrue(services.notices.none { "was not applied" in it }, "${services.notices}")
    }
}
