package wm.damage.core

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.Contracts
import wm.damage.core.app.Expect
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.WorkingCopy
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.content.RemoteContent
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.reader.ReaderAnchor
import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.reader.ReaderGlasses
import wm.damage.core.windows.reader.ReaderMigration

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 — the proof of the spec's §4 (dec. A.3): Reader's first converted start loses no place and
 * replaces none silently. Both stores hold places for three books — one only on the phone, one newer on the phone, one
 * newer on the PC — and both deploy orders run; after each, every book's place in the PC record is the newer of the two
 * old records, the phone's working copy shows the same, and each losing place produced exactly one answer naming both
 * values. A fourth case writes a web place after the PC's conversion: the phone's older migrated place loses and is
 * said. Then: a second start migrates nothing, a downgrade's later writes are offered again, the bookmark goes to its
 * book. Everything runs on loopback port 0 and in temp directories.
 */
class ReaderMigrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = ReaderRig(scope)
    private val notes = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        rig.close()
        scope.cancel()
    }

    // the old stamps (real time in the past: a store clamps a stamp to its own clock)
    private val base = System.currentTimeMillis() - 10 * 3_600_000L
    private val t0 = base
    private val t1 = base + 3_600_000L          // the PC's b
    private val t2 = base + 2 * 3_600_000L      // the phone's b: newer
    private val t3 = base + 3 * 3_600_000L      // the phone's c
    private val t4 = base + 4 * 3_600_000L      // the PC's c: newer

    private fun store(name: String, places: Map<String, Pair<Int, Long>>): Persistence {
        val s = Persistence(rig.dir.resolve(name))
        for ((book, v) in places) s.tryApplyRemote("window.reader.book.$book", buildJsonObject { put("off", v.first) }, v.second)
        return s
    }

    private fun pcStore() = store("pc-state.json", mapOf("b" to (100 to t1), "c" to (300 to t4)))
    private fun phoneStore() = store("phone-state.json", mapOf("a" to (10 to t0), "b" to (200 to t2), "c" to (50 to t3)))

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun watched(wc: WorkingCopy): ReaderHeard = ReaderHeard().also { h -> wc.executor.call { wc.watch(ReaderCore.WINDOW, h) } }

    private fun copyOffset(wc: WorkingCopy, book: String): Int? = wc.executor.call { stateOffset(wc.stateOf(ReaderCore.WINDOW), book) }

    /** Every book's place on the PC is the newer old record, the phone shows the same, and c's loss is said once
     *  with both values. */
    private suspend fun assertNewerWins(h: AppHost, wc: WorkingCopy, heard: ReaderHeard) {
        val want = mapOf("a" to 10, "b" to 200, "c" to 300)
        readerAwait("the PC record holds the newer place of each book") { want.all { (k, v) -> rig.placeOffset(h, k) == v } }
        readerAwait("the phone's copy shows the same") { want.all { (k, v) -> copyOffset(wc, k) == v } }
        readerAwait("c's loss answered") { heard.losses.isNotEmpty() }
        val losses = heard.losses
        assertEquals(1, losses.size, "exactly one place lost: $losses")
        val loss = losses.single().second.losses.single()
        assertEquals(ReaderCore.placeItem("c"), loss.item)
        assertEquals(300, anchorOffset(loss.standing), "the answer names the standing place")
        assertEquals(50, anchorOffset(loss.mine), "and the phone's own")
        assertEquals("migration", loss.by, "the standing place is the PC's own migrated one")
    }

    @Test
    fun theNewPcFirst_thenTheNewApk_theNewerPlaceWinsEveryBook(): Unit = runBlocking {
        val h = rig.pc()
        val r = ReaderMigration.onHost(h, pcStore()) { notes += it }
        assertEquals(2, r.plan.places)
        assertTrue(r.ledgerWritten)
        // the PC's own places stand at rev 0 with their old stamps, by the migration
        assertEquals(0L, rig.place(h, "b")!!.rev)
        assertEquals(t1, rig.place(h, "b")!!.at)
        assertEquals("migration", rig.place(h, "b")!!.by)
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val heard = watched(wc)
        val p = ReaderMigration.onCopy(wc, phoneStore()) { notes += it }
        assertEquals(3, p.plan.places)
        assertNewerWins(h, wc, heard)
        assertTrue(notes.any { it.startsWith("pc: 2 place(s)") } && notes.any { it.startsWith("phone: 3 place(s)") }, "$notes")
    }

    @Test
    fun theNewApkFirst_withTheOldPc_thenThePc_theNewerPlaceWinsEveryBook(): Unit = runBlocking {
        val port = freePort()
        // the old PC: its content port answers, but refuses the app lane in band
        val old = ContentHostServer(LocalContent(Files.createDirectories(rig.books)), port, ReaderRig.TOKEN, app = null,
            bindAddress = InetAddress.getLoopbackAddress())
        old.start()
        val wc = rig.phone(port)
        val heard = watched(wc)
        ReaderMigration.onCopy(wc, phoneStore()) { notes += it }
        readerAwait("the phone learns the PC is on another build") {
            (wc.client?.contractState as? Contracts.State.Known)?.oldPc == true
        }
        assertEquals("PC and phone on different builds for Reader", wc.client?.contractState?.line(ReaderCore.WINDOW))
        // the phone reads on from its copy: its own places, waiting
        assertEquals(mapOf("a" to 10, "b" to 200, "c" to 50), listOf("a", "b", "c").associateWith { copyOffset(wc, it) })
        assertEquals(3, wc.queue.waiting)
        old.close()
        // the PC is upgraded: its own records migrate at its start, then its lane appears on the same port
        val h = rig.pc()
        ReaderMigration.onHost(h, pcStore()) { notes += it }
        rig.serve(h, port)
        assertNewerWins(h, wc, heard)
    }

    @Test
    fun aWebPlaceAfterThePcsConversion_thePhonesOlderMigratedPlaceLosesAndIsSaid(): Unit = runBlocking {
        val h = rig.pc()
        ReaderMigration.onHost(h, pcStore()) { notes += it }
        // read at the desk on the web after the PC's conversion: b moves on (rev 1)
        assertTrue(rig.otherDevicePlace(h, "web", "w1", 1, "b", 150) is Answer.Seen)
        assertEquals(1L, rig.place(h, "b")!!.rev)
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val heard = watched(wc)
        ReaderMigration.onCopy(wc, phoneStore()) { notes += it }
        readerAwait("both losses answered") { heard.losses.size >= 2 }
        val byItem = heard.losses.flatMap { it.second.losses }.associateBy { it.item }
        val b = byItem.getValue(ReaderCore.placeItem("b"))
        assertEquals(150, anchorOffset(b.standing), "never applied silently: the web's place stands")
        assertEquals(200, anchorOffset(b.mine))
        assertEquals("web", b.by)
        assertEquals(150, rig.placeOffset(h, "b"))
        readerAwait("the phone shows the standing place") { copyOffset(wc, "b") == 150 }
        assertEquals(10, rig.placeOffset(h, "a"))
        assertEquals(2, heard.losses.size, "one answer per losing place: ${heard.losses}")
    }

    @Test
    fun aSecondStartMigratesNothing(): Unit = runBlocking {
        val h = rig.pc()
        val pcStore = pcStore()
        val phoneStore = phoneStore()
        ReaderMigration.onHost(h, pcStore) { notes += it }
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        ReaderMigration.onCopy(wc, phoneStore) { notes += it }
        readerAwait("the phone's queue drained") { wc.queue.waiting == 0 }
        val seq = h.seq
        assertEquals(emptyList(), ReaderMigration.plan(pcStore).offers, "the PC's ledger holds what it offered")
        assertEquals(emptyList(), ReaderMigration.plan(phoneStore).offers, "the phone's ledger holds what it offered")
        val again = ReaderMigration.onCopy(wc, phoneStore) { notes += it }
        assertEquals(0, again.plan.offers.size)
        assertEquals(0, wc.queue.waiting)
        assertEquals(0, ReaderMigration.onHost(h, pcStore) { notes += it }.plan.offers.size)
        assertEquals(seq, h.seq, "nothing reached the PC's record")
        // a store reopened from disk says the same (the ledger was saved with the copy)
        val reread = Persistence(rig.dir.resolve("phone-state.json")).also { it.load() }
        assertEquals(emptyList(), ReaderMigration.plan(reread).offers)
    }

    @Test
    fun aDowngradesLaterWritesAreOfferedAgain_andJudged(): Unit = runBlocking {
        val h = rig.pc()
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val heard = watched(wc)
        val phoneStore = phoneStore()
        ReaderMigration.onCopy(wc, phoneStore) { notes += it }
        readerAwait("migrated") { rig.placeOffset(h, "b") == 200 }
        // back on the older build: it reads on in b and c and writes today's shape; a is untouched
        phoneStore.put("window.reader.book.b", buildJsonObject { put("off", 400) })
        phoneStore.put("window.reader.book.c", buildJsonObject { put("off", 60) })
        // meanwhile the web read c on, after the conversion (rev 1)
        assertTrue(rig.otherDevicePlace(h, "web", "w1", 1, "c", 333) is Answer.Seen)
        val plan = ReaderMigration.plan(phoneStore)
        assertEquals(setOf(ReaderCore.placeItem("b"), ReaderCore.placeItem("c")), plan.offers.flatMap { it.targets }.toSet(),
            "only what the older build wrote since is offered again")
        assertTrue(plan.offers.all { it.expect is Expect.PreConversion })
        ReaderMigration.onCopy(wc, phoneStore) { notes += it }
        readerAwait("b's later place applied") { rig.placeOffset(h, "b") == 400 }
        readerAwait("c's later place judged and said") { heard.losses.any { it.second.losses.any { l -> l.item == ReaderCore.placeItem("c") } } }
        assertEquals(333, rig.placeOffset(h, "c"), "a place written after the conversion is not replaced silently")
    }

    @Test
    fun aDowngradesPlace_isJudgedByWhatThisDeviceLastKnew_appliedWhereTheRecordStillHoldsIt_lostAndSaidWhereAnotherWrote(): Unit = runBlocking {
        // fix round 1, ruling 1.3: on the new build he read b and c on (rev 1 by the phone, and the presenter's ledger
        // knows both record places); back on the older build he read both further; meanwhile the web moved c
        val h = rig.pc()
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val heard = watched(wc)
        val phoneStore = phoneStore()
        ReaderMigration.onCopy(wc, phoneStore) { notes += it }
        readerAwait("migrated") { rig.placeOffset(h, "c") == 50 && wc.queue.waiting == 0 }
        readOn(wc, "b", 200, 250)
        readOn(wc, "c", 50, 70)
        readerAwait("read on the new build") { rig.placeOffset(h, "b") == 250 && rig.placeOffset(h, "c") == 70 }
        assertEquals(1L, rig.place(h, "b")!!.rev)
        val known = listOf("a", "b", "c").associateWith { rig.place(h, it)!!.value }
        for ((id, off) in mapOf("a" to 10, "b" to 250, "c" to 70)) phoneStore.put("window.reader.book.$id", buildJsonObject { put("off", off) })
        phoneStore.put(ReaderMigration.LEDGER_KEY, ReaderMigration.ledgerOf(mapOf("a" to 10, "b" to 250, "c" to 70), "", -1, 5, false, 0, known))
        // the older build reads on; the web moves c after that
        phoneStore.put("window.reader.book.b", buildJsonObject { put("off", 400) })
        phoneStore.put("window.reader.book.c", buildJsonObject { put("off", 90) })
        assertTrue(rig.otherDevicePlace(h, "web", "w1", 1, "c", 333) is Answer.Seen)
        val plan = ReaderMigration.plan(phoneStore)
        assertEquals(mapOf(ReaderCore.placeItem("b") to Expect.Value(known.getValue("b")), ReaderCore.placeItem("c") to Expect.Value(known.getValue("c"))),
            plan.offers.associate { it.targets.single() to it.expect }, "offered expecting what this device last knew")
        ReaderMigration.onCopy(wc, phoneStore) { notes += it }
        readerAwait("b, which no one moved since, applied") { rig.placeOffset(h, "b") == 400 }
        readerAwait("c's loss said") { heard.losses.isNotEmpty() }
        val loss = heard.losses.single().second.losses.single()
        assertEquals(ReaderCore.placeItem("c"), loss.item)
        assertEquals(333, anchorOffset(loss.standing))
        assertEquals("web", loss.by)
        assertEquals(90, anchorOffset(loss.mine))
        assertEquals(333, rig.placeOffset(h, "c"), "the web's later place is not replaced")
    }

    // ---- fix round 1, ruling 1.5: a migration loss is said on the glasses, once, with both places ----

    private fun saidLosses(services: ReaderServices) = services.notices.filter { "was not applied" in it }

    @Test
    fun aMigrationLoss_isSaidOnTheGlassesOnce_withBothPlaces(): Unit = runBlocking {
        val h = rig.pc()
        ReaderMigration.onHost(h, pcStore()) { notes += it }
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val services = ReaderServices()
        rig.phoneGlasses(wc, srv.boundPort, services)                // registered and listening (ruling 1.5 (a))
        ReaderMigration.onCopy(wc, phoneStore()) { notes += it }
        readerAwait("the loss is said") { saidLosses(services).isNotEmpty() }
        readerAwait("every offer answered") { wc.queue.waiting == 0 }
        delay(200)
        val said = saidLosses(services).single()
        assertTrue(said.startsWith("reader: an earlier build read on to character 300 (") &&
            "this place, character 50, was not applied" in said, said)
    }

    @Test
    fun aMigrationLossAnsweredBeforeThePresenterRegisters_isKeptWithTheCopy_andSaidOnceWhenItDoes(): Unit = runBlocking {
        val h = rig.pc()
        ReaderMigration.onHost(h, pcStore()) { notes += it }
        val srv = rig.serve(h)
        val first = rig.phone(srv.boundPort, "p")
        ReaderMigration.onCopy(first, phoneStore()) { notes += it }
        readerAwait("every offer answered while no presenter listens") { first.queue.waiting == 0 && rig.placeOffset(h, "b") == 200 }
        val copy = rig.dir.resolve("p").resolve("workcopy").resolve(WorkingCopy.COPY_FILE)
        readerAwait("the loss is kept with the copy") { Files.exists(copy) && "\"unheard\"" in Files.readString(copy) }
        first.close()                                                 // the phone's process ends; another starts
        delay(100)
        val wc = rig.phone(srv.boundPort, "p")
        val services = ReaderServices()
        val core = ReaderCore(RemoteContent("127.0.0.1", srv.boundPort, ReaderRig.TOKEN,
            Files.createDirectories(rig.dir.resolve("bookcache"))), scope)
        val src = rig.keep(LocalWindowSource(core, wc))
        val g = ReaderGlasses(FakeText(), core, src, scope, device = wc.device)
        wc.executor.call {}               // the kept loss has reached the presenter, before the shell gave it its services
        assertEquals(emptyList(), saidLosses(services))
        g.onRegistered(services)
        readerAwait("said at the registration") { saidLosses(services).isNotEmpty() }
        delay(200)
        val said = saidLosses(services).single()
        assertTrue("an earlier build read on to character 300" in said && "this place, character 50, was not applied" in said, said)
    }

    /**
     * Fix round 2, ruling 1.2: a loss handed to a presenter that has not said it yet (it listens, and no session has
     * run) stays with the copy; the phone's process ends, and the next process says it, once. Before, the copy let go
     * of a loss as soon as a watcher of its window took it.
     */
    @Test
    fun aLossHandedToAPresenterThatHasNotSaidIt_isSaidAfterTheProcessEnds(): Unit = runBlocking {
        val h = rig.pc()
        ReaderMigration.onHost(h, pcStore()) { notes += it }
        val srv = rig.serve(h)
        val first = rig.phone(srv.boundPort, "p")
        val core0 = ReaderCore(RemoteContent("127.0.0.1", srv.boundPort, ReaderRig.TOKEN,
            Files.createDirectories(rig.dir.resolve("bookcache"))), scope)
        ReaderGlasses(FakeText(), core0, rig.keep(LocalWindowSource(core0, first)), scope, device = first.device)   // never registered
        ReaderMigration.onCopy(first, phoneStore()) { notes += it }
        readerAwait("every offer answered") { first.queue.waiting == 0 && rig.placeOffset(h, "b") == 200 }
        val copy = rig.dir.resolve("p").resolve("workcopy").resolve(WorkingCopy.COPY_FILE)
        readerAwait("the loss is kept with the copy") { Files.exists(copy) && "\"unheard\"" in Files.readString(copy) }
        first.close()                                                 // the phone's process ends before it was said
        val wc = rig.phone(srv.boundPort, "p")
        val services = ReaderServices()
        rig.phoneGlasses(wc, srv.boundPort, services)
        readerAwait("said in the next process") { saidLosses(services).isNotEmpty() }
        delay(200)
        assertEquals(1, saidLosses(services).size, "${services.notices}")
    }

    /**
     * Fix round 2, ruling 1.2: a kept loss ends at the saying, and one said is not handed again while the process
     * lives — not to the next presenter of a stack rebuild over the same working copy — and leaves the saved copy.
     */
    @Test
    fun aLossSaidOnce_isNotHandedAgainToTheNextPresenter_andLeavesTheSavedCopy(): Unit = runBlocking {
        val h = rig.pc()
        ReaderMigration.onHost(h, pcStore()) { notes += it }
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val services = ReaderServices()
        val g = rig.phoneGlasses(wc, srv.boundPort, services)
        ReaderMigration.onCopy(wc, phoneStore()) { notes += it }
        readerAwait("the loss is said") { saidLosses(services).isNotEmpty() }
        readerAwait("every offer answered") { wc.queue.waiting == 0 }
        wc.executor.call {}
        g.detach()                                                    // a stack rebuild: a new source and presenter
        val again = ReaderServices()
        rig.phoneGlasses(wc, srv.boundPort, again)
        wc.executor.call {}
        wc.executor.call {}
        delay(200)
        assertEquals(emptyList(), saidLosses(again), "said once while the process lives")
        assertEquals(1, saidLosses(services).size, "${services.notices}")
        wc.close()
        val copy = rig.dir.resolve("phone").resolve("workcopy").resolve(WorkingCopy.COPY_FILE)
        assertTrue("\"unheard\"" !in Files.readString(copy), "the saved copy keeps no loss that was said")
    }

    /**
     * Fix round 2, ruling 1.3 (F1a's Found 1, the session-start double): the phone's process restarts with the migrated
     * op unsent, so the queue loads it frozen, and the session start's own save sends the same place as a new op. The
     * lane comes up and both lose with the same two places: one notice.
     */
    @Test
    fun theMigratedOpAndTheSessionStartsOwnOp_bothLose_theLossIsSaidOnce(): Unit = runBlocking {
        val h = rig.pc()
        assertTrue(rig.otherDevicePlace(h, "web-3fa9c1", "w1", 1, "c", 300) is Answer.Seen)
        val port = freePort()
        val first = rig.phone(port)                                   // the PC away at the install
        ReaderMigration.onCopy(first, phoneStore()) { notes += it }
        val qf = rig.dir.resolve("phone").resolve("workcopy").resolve(WorkingCopy.QUEUE_FILE)
        readerAwait("the offers are on disk") { Files.exists(qf) && Files.readString(qf).let { q -> listOf("a", "b", "c").all { "\"place.$it\"" in q } } }
        first.close()                                                 // the phone's process ends
        val wc = rig.phone(port)
        val services = ReaderServices()
        val g = rig.phoneGlasses(wc, port, services)
        services.onLoop {
            g.restoreSubState("book.c", buildJsonObject { put("off", 50) })
            g.restoreState(buildJsonObject { put("level", "LIBRARY") })
            g.saveSubState()                                          // the session start's own save
        }
        wc.executor.call {}
        assertEquals(2, wc.queue.ops().count { it.targets == listOf(ReaderCore.placeItem("c")) }, "the frozen op and a new one")
        rig.serve(h, port)
        readerAwait("every op answered") { wc.queue.waiting == 0 }
        readerAwait("the loss is said") { saidLosses(services).isNotEmpty() }
        delay(300)
        val said = saidLosses(services)
        assertEquals(1, said.size, "one loss of c, said once: $said")
        assertTrue("a browser read on to character 300" in said.single() && "this place, character 50" in said.single(), said.single())
    }

    // ---- fix round 1, ruling 1.1: a record held on both stores (the 0.66 sync copied it; the PC's copy is stamped up
    // to 2 s later), and a place read on the new build while the migration op waits ----

    private val synced = base + 5 * 3_600_000L
    private fun syncedPc() = store("pc-x.json", mapOf("x" to (100 to synced + 1_500)))
    private fun syncedPhone() = store("phone-x.json", mapOf("x" to (100 to synced)))
    private fun readOn(wc: WorkingCopy, book: String, from: Int, to: Int) = wc.executor.call {
        wc.submit(ReaderCore.WINDOW, ReaderCore.placeDraft(book, ReaderAnchor(to), Expect.Value(JsonPrimitive(ReaderAnchor(from).format()))))
    }

    @Test
    fun aRecordOnBothStores_thePcsCopyLater_theSameValue_nothingLostNothingSaid(): Unit = runBlocking {
        val h = rig.pc()
        ReaderMigration.onHost(h, syncedPc()) { notes += it }
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val heard = watched(wc)
        ReaderMigration.onCopy(wc, syncedPhone()) { notes += it }
        readerAwait("the phone's queue drained") { wc.queue.waiting == 0 && heard.answers.isNotEmpty() }
        assertEquals(100, rig.placeOffset(h, "x"))
        assertEquals("migration", rig.place(h, "x")!!.by, "the PC's own copy stands, unchanged")
        assertEquals(emptyList(), heard.losses, "nothing lost, nothing said")
    }

    @Test
    fun aPlaceReadBeforeTheLaneIsUp_joinsTheMigrationOp_andStandsOnThePc_nothingSaid(): Unit = runBlocking {
        val h = rig.pc()
        ReaderMigration.onHost(h, syncedPc()) { notes += it }
        val port = freePort()                                         // the PC out of reach at the install
        val wc = rig.phone(port)
        val heard = watched(wc)
        ReaderMigration.onCopy(wc, syncedPhone()) { notes += it }
        readOn(wc, "x", 100, 180)                                     // he reads on the new build
        assertEquals(1, wc.queue.waiting, "the place joined the unsent migration op")
        val joined = wc.queue.ops().single()
        assertTrue((joined.expect as Expect.PreConversion).at >= rig.now.get(), "it takes the time of the joining place: ${joined.expect}")
        rig.serve(h, port)
        readerAwait("the phone's place stands on the PC") { rig.placeOffset(h, "x") == 180 }
        readerAwait("answered") { wc.queue.waiting == 0 && heard.answers.isNotEmpty() }
        assertEquals(emptyList(), heard.losses, "nothing said: the phone's place is the newer one")
        assertEquals(180, copyOffset(wc, "x"))
    }

    @Test
    fun aWebPlaceAfterTheConversion_isNeverReplacedByAJoinedMigrationOp_whateverItsStamp(): Unit = runBlocking {
        val h = rig.pc()
        ReaderMigration.onHost(h, syncedPc()) { notes += it }
        assertTrue(rig.otherDevicePlace(h, "web", "w1", 1, "x", 150) is Answer.Seen)
        val port = freePort()
        val wc = rig.phone(port)
        val heard = watched(wc)
        ReaderMigration.onCopy(wc, syncedPhone()) { notes += it }
        rig.now.addAndGet(60_000)                                     // the joining place is later than the web's
        readOn(wc, "x", 100, 180)
        rig.serve(h, port)
        readerAwait("the loss is answered") { heard.losses.isNotEmpty() }
        val loss = heard.losses.single().second.losses.single()
        assertEquals(150, anchorOffset(loss.standing))
        assertEquals("web", loss.by)
        assertEquals(180, anchorOffset(loss.mine))
        assertEquals(150, rig.placeOffset(h, "x"), "a place written after the conversion is never replaced by a pre-conversion op")
    }

    @Test
    fun aSessionStartsOwnSendOfAnUnchangedPlace_keepsTheOldStamp_theNewerPcPlaceStillWins(): Unit = runBlocking {
        // the presenter's first save point sends every place it holds; one that equals the waiting migration op is the
        // same place, not a read on this build: the PC's newer old place (c) still wins, said
        val port = freePort()
        val wc = rig.phone(port)
        val heard = watched(wc)
        ReaderMigration.onCopy(wc, phoneStore()) { notes += it }
        wc.executor.call {
            wc.submit(ReaderCore.WINDOW, ReaderCore.placeDraft("c", ReaderAnchor(50),
                ReaderCore.expectSeen(wc.stateOf(ReaderCore.WINDOW).itemNamed(ReaderCore.placeItem("c")))))
        }
        assertEquals(Expect.PreConversion(t3), wc.queue.ops().single { it.targets == listOf(ReaderCore.placeItem("c")) }.expect)
        val h = rig.pc()
        ReaderMigration.onHost(h, pcStore()) { notes += it }
        rig.serve(h, port)
        assertNewerWins(h, wc, heard)
    }

    @Test
    fun theBookmarkGoesToItsBook_orIsDroppedAndSaid(): Unit = runBlocking {
        val s = Persistence(rig.dir.resolve("bm.json"))
        s.put("window.reader", buildJsonObject {
            put("level", "BOOK"); put("bookId", "b"); put("bookmark", 77); put("scrollLines", 3)
        })
        val plan = ReaderMigration.plan(s)
        val bm = plan.offers.single { it.targets == listOf(ReaderCore.bookmarkItem("b")) }
        assertEquals(ReaderCore.MIGRATE, bm.action)
        assertEquals(77, ReaderAnchor.parse(bm.result.jsonPrimitive.content)!!.offset)
        assertTrue(bm.expect is Expect.PreConversion)
        assertEquals(JsonPrimitive(3), plan.offers.single { it.targets == listOf(ReaderCore.SCROLL_LINES) }.result,
            "the glasses' rows come from the same record")
        // on the PC it lands under its book
        val h = rig.pc()
        ReaderMigration.onHost(h, s) { notes += it }
        val items = h.read(ReaderCore.WINDOW).third.items
        assertEquals(77, anchorOffset(items["rec.reader.bookmark.b"]?.value))
        assertEquals(JsonPrimitive(3), items["rec.reader.glasses.scrollLines"]?.value)
        // one offset with no book to give it to: dropped, and said
        val s2 = Persistence(rig.dir.resolve("bm2.json"))
        s2.put("window.reader", buildJsonObject { put("level", "LIBRARY"); put("bookId", ""); put("bookmark", 55) })
        val p2 = ReaderMigration.plan(s2)
        assertTrue(p2.offers.none { it.targets.any { t -> t.startsWith("bookmark") } })
        assertEquals(1, p2.dropped.size)
        val h2 = rig.pc("pc2")
        val r2 = ReaderMigration.onHost(h2, s2) { notes += it }
        assertTrue(r2.line("pc").contains("dropped: the bookmark at character 55"), r2.line("pc"))
        assertTrue(notes.any { "dropped: the bookmark at character 55" in it })
    }

    @Test
    fun thePresentersOwnCopyIsNotOfferedAgain(): Unit = runBlocking {
        // what the converted presenter writes at its save points (the copy and its ledger) is never migration input
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        assertTrue(g.open("book:${ids[0]}"))
        readerAwait("opens") { g.title().contains("p.") }
        val store = Persistence(rig.dir.resolve("own.json"))
        services.onLoop {
            g.doc().topLine = 30
            store.put("window.reader", g.saveState())
            for ((k, v) in g.saveSubState()) store.put("window.reader.$k", v)
        }
        assertTrue(store.get("window.reader.book.${ids[0]}") != null, "the copy holds the place in today's shape")
        assertEquals(emptyList(), ReaderMigration.plan(store).offers, "the ledger beside it says this build wrote it")
    }
}
