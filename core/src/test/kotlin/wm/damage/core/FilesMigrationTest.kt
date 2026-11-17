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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.Contracts
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Record
import wm.damage.core.app.WorkingCopy
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.files.FilesCore
import wm.damage.core.windows.files.FilesMigration

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.4 — "the same four cases as §3.4" — and §5 F4; `CONTRACT.md` §9.7 item 2):
 * Files' old local records (`clipVerb` + `clipPath`, `sort`, `hidden`, `height` of `window.files`) become record items
 * once per host. Both deploy orders end with the newer blob's values on the PC and the phone, the older one's losses
 * said once; a row set in a browser after the PC's conversion is never replaced by an older blob; a second start
 * offers nothing. Loopback port 0 and temp directories only.
 */
class FilesMigrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = ReaderRig(scope)
    private val notes = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        rig.close()
        scope.cancel()
    }

    private val base = System.currentTimeMillis() - 10 * 3_600_000L
    private val older = base
    private val newer = base + 3_600_000L

    /** A store whose `window.files` blob is today's shape, stamped [at]. */
    private fun store(name: String, at: Long, sort: String, hidden: Boolean, clip: Pair<String, String>?, height: Int?): Persistence {
        val s = Persistence(rig.dir.resolve(name))
        s.tryApplyRemote("window.files", buildJsonObject {
            put("level", "BROWSE"); put("cwd", "/made/up")
            put("sort", sort); put("hidden", hidden)
            put("clipVerb", clip?.first ?: ""); put("clipPath", clip?.second ?: "")
            height?.let { put("height", it) }
        }, at)
        return s
    }

    // the PC's blob is the newer: sort by size, hidden on, a cut on the clipboard; the phone's older: by time, a height
    private fun pcStore() = store("pc-state.json", newer, "SIZE", true, "CUT" to "/made/up/a.txt", null)
    private fun phoneStore() = store("phone-state.json", older, "MTIME", false, null, 416)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Heard : wm.damage.core.app.SourceListener {
        val answers = CopyOnWriteArrayList<Pair<Op, Answer>>()
        override fun answered(op: Op, answer: Answer) { answers += op to answer }
    }

    private fun watched(wc: WorkingCopy) = Heard().also { h -> wc.executor.call { wc.watch(FilesCore.WINDOW, h) } }

    private fun value(h: AppHost, name: String): JsonElement? = h.read(FilesCore.WINDOW).third.items[Record.key(FilesCore.WINDOW, name)]?.value

    private fun copyValue(wc: WorkingCopy, name: String): JsonElement? =
        wc.executor.call { wc.stateOf(FilesCore.WINDOW).itemNamed(name)?.value }

    /** The newer blob's rows on the PC and the phone; the phone's sort and hidden are older and change nothing (L1 C1),
     *  its height applies. */
    private suspend fun assertNewerWins(h: AppHost, wc: WorkingCopy, heard: Heard) {
        val want = mapOf(FilesCore.SORT to JsonPrimitive("size"), FilesCore.HIDDEN to JsonPrimitive(true),
            FilesCore.CLIP to FilesCore.clipJson("cut", "/made/up/a.txt"), FilesCore.SIZE to JsonPrimitive(416))
        readerAwait("the PC record holds the newer value of each row") { want.all { (k, v) -> value(h, k) == v } }
        readerAwait("the phone's copy shows the same") { want.all { (k, v) -> copyValue(wc, k) == v } }
        readerAwait("the phone's offers answered") { heard.answers.size >= 3 }
        assertTrue(heard.answers.all { it.second is Answer.Seen }, "${heard.answers}")
    }

    @Test
    fun thePcFirst_thenTheApk_theNewerBlobWinsEachRow(): Unit = runBlocking {
        val h = rig.pc()
        val r = FilesMigration.onHost(h, pcStore()) { notes += it }
        assertEquals(3, r.plan.offers.size, "${r.plan.offers}")
        assertTrue(r.ledgerWritten)
        assertEquals("migration", h.read(FilesCore.WINDOW).third.items[Record.key(FilesCore.WINDOW, FilesCore.SORT)]!!.by)
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val heard = watched(wc)
        val p = FilesMigration.onCopy(wc, phoneStore()) { notes += it }
        assertEquals(3, p.plan.offers.size, "the phone offers sort, hidden and its height (no clipboard): ${p.plan.offers}")
        assertNewerWins(h, wc, heard)
        assertTrue(notes.any { it.startsWith("pc: files: 3 field(s)") } && notes.any { it.startsWith("phone: files: 3 field(s)") }, "$notes")
    }

    @Test
    fun aSortSetInABrowserAfterThePcsConversionIsNeverReplacedByAnOlderBlob(): Unit = runBlocking {
        val h = rig.pc()
        FilesMigration.onHost(h, pcStore()) { notes += it }
        // the browser sets the sort after the conversion
        val got = CopyOnWriteArrayList<Answer>()
        h.act(Op("web-q5", OpId.Queued("w1", 1), FilesCore.WINDOW, FilesCore.SORT_ROW, listOf(FilesCore.SORT), JsonPrimitive("name"),
            at = System.currentTimeMillis())) { got += it }
        h.executor.call {}
        assertTrue(got.single() is Answer.Seen)
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        val heard = watched(wc)
        FilesMigration.onCopy(wc, store("phone-new.json", newer + 3_600_000L, "MTIME", true, null, null)) { notes += it }
        readerAwait("the phone's offers answered") { wc.queue.waiting == 0 && heard.answers.size >= 2 }
        assertEquals(JsonPrimitive("name"), value(h, FilesCore.SORT), "the browser's later row stands over the older blob")
        assertEquals("web-q5", h.read(FilesCore.WINDOW).third.items[Record.key(FilesCore.WINDOW, FilesCore.SORT)]!!.by)
        readerAwait("the phone shows the standing row") { copyValue(wc, FilesCore.SORT) == JsonPrimitive("name") }
    }

    /** Pin (the spec's Q5: "a second start migrates again"). */
    @Test
    fun aSecondStartOffersNothing(): Unit = runBlocking {
        val h = rig.pc()
        val pcStore = pcStore()
        val phoneStore = phoneStore()
        FilesMigration.onHost(h, pcStore) { notes += it }
        val srv = rig.serve(h)
        val wc = rig.phone(srv.boundPort)
        FilesMigration.onCopy(wc, phoneStore) { notes += it }
        readerAwait("the phone's queue drained") { wc.queue.waiting == 0 }
        val seq = h.seq
        assertEquals(emptyList(), FilesMigration.of(pcStore).plan(pcStore).offers, "the PC's ledger holds what it offered")
        assertEquals(emptyList(), FilesMigration.of(phoneStore).plan(phoneStore).offers, "the phone's ledger holds what it offered")
        assertEquals(0, FilesMigration.onCopy(wc, phoneStore) { notes += it }.plan.offers.size)
        assertEquals(0, FilesMigration.onHost(h, pcStore) { notes += it }.plan.offers.size)
        assertEquals(seq, h.seq, "nothing reached the PC's record")
        val reread = Persistence(rig.dir.resolve("phone-state.json")).also { it.load() }
        assertEquals(emptyList(), FilesMigration.of(reread).plan(reread).offers)
    }
}
