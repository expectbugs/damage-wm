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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.Contracts
import wm.damage.core.app.Expect
import wm.damage.core.app.HostBackend
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.Notices
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.WorkingCopy
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.torrents.TorrentsCore
import wm.damage.core.windows.torrents.TorrentsGlasses
import wm.damage.core.windows.torrents.TorrentsMigration

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q4 (the spec's §3.4, §5 F4; `CONTRACT.md` §9.7 item 2): Torrents' records from before its
 * conversion — the local blob's search recents, Notify rows and Size — become record items once per host, in either
 * deploy order (S1-1's `ReaderMigrationTest` shape): the newer blob wins each row and the loser is said once on the
 * glasses; a row set in a browser after the PC's conversion is never replaced by an older blob; a second start offers
 * nothing, and the presenter's own ledger keeps it so while a row the record does not hold yet is offered again. The PC
 * record and the phone's working copy on loopback port 0, the blobs in temp directories, a scripted provider.
 */
class TorrentsMigrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = ReaderRig(scope)
    private val notes = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        rig.close()
        scope.cancel()
    }

    // the old stamps (real time in the past: a store clamps a stamp to its own clock)
    private val older = System.currentTimeMillis() - 10 * 3_600_000L
    private val newer = older + 3_600_000L

    private fun blob(name: String, stamp: Long, recents: List<String>, done: Boolean, errors: Boolean, height: Int?): Persistence =
        Persistence(rig.dir.resolve(name)).also {
            it.tryApplyRemote("window.torrents", buildJsonObject {
                put("level", "TRANSFERS")
                put("recents", buildJsonArray { for (q in recents) add(JsonPrimitive(q)) })
                put("notifyDone", done)
                put("notifyErrors", errors)
                put("pollMs", 5_000)
                height?.let { h -> put("height", h) }
            }, stamp)
        }

    private fun item(h: AppHost, row: String) = h.read(TorrentsCore.WINDOW).third.items["rec.torrents.$row"]
    private fun recents(vararg q: String): JsonElement = JsonArray(q.map { JsonPrimitive(it) })

    /** The phone's presenter over its working copy, as `ShellService` builds it; its notices land in [services]. */
    private fun phoneGlasses(wc: WorkingCopy, services: ReaderServices, p: TestTorrents = TestTorrents()): TorrentsGlasses {
        val core = TorrentsCore()
        return TorrentsGlasses(FakeText(), p, scope, core, rig.keep(LocalWindowSource(core, wc)),
            recordNotices = { wc.shares(core.window) && wc.shares(Notices.WINDOW) }, device = wc.device)
            .also { it.onRegistered(services) }
    }

    private fun lossLines(s: ReaderServices) = s.notices.filter { "not applied" in it }

    @Test
    fun thePcFirst_thenTheApk_theNewerBlobWinsEachRow_andTheLoserIsSaidOnce(): Unit = runBlocking {
        val h = rig.pc()
        val pc = TorrentsMigration.onHost(h, blob("pc.json", newer, listOf("pc search"), done = false, errors = true, height = 352)) { notes += it }
        assertTrue(pc.ledgerWritten)
        assertEquals(JsonPrimitive(false), item(h, "notify.done")?.value)
        assertEquals(JsonPrimitive(352), item(h, "glasses.size")?.value)
        assertEquals(newer, item(h, "notify.done")?.at, "a migrated row keeps the old stamp")
        assertEquals("migration", item(h, "notify.done")?.by)
        // until the install, the old phone's rows do not reach the PC (its keys no longer sync); then the new APK
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val services = ReaderServices()
        val g = phoneGlasses(wc, services)
        TorrentsMigration.onCopy(wc, blob("phone.json", older, listOf("phone search"), done = true, errors = true, height = null)) { notes += it }
        readerAwait("both losses said on the glasses") { lossLines(services).size >= 2 }
        delay(300)
        val said = lossLines(services)
        assertEquals(2, said.size, "one line per losing row (errors was the same on both): $said")
        assertTrue(said.any { it.startsWith("torrent: not applied — Notify · done: you on (an earlier build ") && "standing off, set by an earlier build" in it }, "$said")
        assertTrue(said.any { "Recent searches: you phone search" in it && "standing pc search" in it }, "$said")
        // the PC's newer rows stand, and the glasses show them
        assertEquals(JsonPrimitive(false), item(h, "notify.done")?.value)
        assertEquals(recents("pc search"), item(h, "recents")?.value)
        readerAwait("the glasses show the standing row") { services.onLoop { g.appSettings().first { it.name == "Notify · done" }.current() } == "off" }
        assertTrue(notes.any { it.startsWith("pc: torrents: 4 field(s) offered") } && notes.any { it.startsWith("phone: torrents: 3 field(s) offered") }, "$notes")
        g.detach()
    }

    /** Pin (the spec's Q4: "a browser's row after the PC's conversion replaced by an older blob"). */
    @Test
    fun aBrowsersRowAfterThePcsConversionIsNeverReplacedByAnOlderBlob(): Unit = runBlocking {
        val h = rig.pc()
        TorrentsMigration.onHost(h, blob("pc.json", older, emptyList(), done = true, errors = true, height = null)) { notes += it }
        // set in a browser after the PC's conversion
        val got = ArrayList<Answer>()
        h.act(Op("web-00aa01", OpId.Queued("w1", 1), TorrentsCore.WINDOW, TorrentsCore.SET, listOf(TorrentsCore.NOTIFY_DONE),
            JsonPrimitive(false), Expect.Value(JsonPrimitive(true)))) { got += it }
        h.executor.call {}
        assertTrue(got.single() is Answer.Seen, "$got")
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val services = ReaderServices()
        val g = phoneGlasses(wc, services)
        TorrentsMigration.onCopy(wc, blob("phone.json", newer, emptyList(), done = true, errors = true, height = null)) { notes += it }
        readerAwait("the phone's offer answered") { wc.queue.waiting == 0 && lossLines(services).isNotEmpty() }
        assertEquals(JsonPrimitive(false), item(h, "notify.done")?.value, "the row set in the browser stands")
        assertEquals("web-00aa01", item(h, "notify.done")?.by)
        assertTrue(lossLines(services).single().contains("standing off, set in a browser"), "${lossLines(services)}")
        g.detach()
    }

    /** Pins (the spec's Q4: "a second start migrates again"; the presenter's ledger, its two directions). */
    @Test
    fun aSecondStartOffersNothing_thePresentersLedgerKeepsItSo_andARowTheRecordLacksIsOfferedAgain(): Unit = runBlocking {
        val h = rig.pc()
        val store = blob("pc.json", older, listOf("pc search"), done = false, errors = true, height = null)
        assertEquals(3, TorrentsMigration.onHost(h, store) { notes += it }.plan.offers.size)
        val reread = { Persistence(rig.dir.resolve("pc.json")).also { it.load() } }
        assertTrue(TorrentsMigration.MIGRATE.plan(reread()).offers.isEmpty(), "the ledger holds what was offered")
        // the PC's glasses presenter over the record: the row changed on the glasses, then its save point as the shell
        // makes it (the blob, then the sub-record beside it)
        val services = ReaderServices()
        val core = TorrentsCore()
        val src = rig.keep(LocalWindowSource(core, HostBackend(h, "local", "glasses-test")))
        val g = TorrentsGlasses(FakeText(), TestTorrents(), scope, core, src)
        g.onRegistered(services)
        val s1 = reread()
        services.onLoop { g.restoreState(s1.get("window.torrents")!!) }
        services.onLoop { g.appSettings().first { it.name == "Notify · done" }.apply("on") }
        readerAwait("the glasses' row reached the record") { item(h, "notify.done")?.value == JsonPrimitive(true) }
        services.onLoop {
            s1.put("window.torrents", g.saveState())
            for ((k, v) in g.saveSubState()) s1.put("window.torrents.$k", v)
        }
        assertTrue(s1.save())
        assertTrue(TorrentsMigration.MIGRATE.plan(reread()).offers.isEmpty(), "the presenter's own write is not offered again")
        // a row the record does not hold (as after a first start whose offers were not taken): offered again
        val lacking = Persistence(rig.dir.resolve("lacking.json"))
        val st = src.state()
        val blob = buildJsonObject { put("recents", JsonArray(emptyList())); put("notifyDone", true); put("notifyErrors", true); put("height", 288) }
        lacking.put("window.torrents", blob)
        lacking.put("window.torrents.${TorrentsMigration.LEDGER}", TorrentsMigration.ledgerOf(blob, st))
        assertEquals(listOf("glasses.size"), TorrentsMigration.MIGRATE.plan(lacking).offers.map { it.targets.single() },
            "Size 288 is not in the record yet: offered at the next start")
        g.detach()
    }
}
