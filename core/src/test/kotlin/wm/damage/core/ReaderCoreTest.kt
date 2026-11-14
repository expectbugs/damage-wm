package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.app.Answer
import wm.damage.core.app.Expect
import wm.damage.core.app.Item
import wm.damage.core.app.Kind
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Record
import wm.damage.core.app.WindowState
import wm.damage.core.content.ContentProvider
import wm.damage.core.content.LocalContent
import wm.damage.core.shell.WindowView
import wm.damage.core.windows.reader.ReaderAnchor
import wm.damage.core.windows.reader.ReaderCore

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 — Reader's core (`CONTRACT.md` §12): its description, `resolve`, and each action as the PC's
 * record judges it — the place (a position), the bookmark (a register, one per book: it gained a book id), the book
 * read most recently (arrival order, never a loss), the glasses' rows, reset progress (a removal, cancel first as built).
 */
class ReaderCoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = ReaderRig(scope)

    @AfterTest
    fun tearDown() {
        rig.close()
        scope.cancel()
    }

    private fun item(name: String, v: kotlinx.serialization.json.JsonElement, by: String = "local", rev: Long = 1) =
        Record.key(ReaderCore.WINDOW, name) to Item(v, rev, by, 1L)

    private fun ask(h: wm.damage.core.app.AppHost, device: String, n: Long, action: String, target: String,
                    result: kotlinx.serialization.json.JsonElement, expect: Expect): Answer {
        val got = java.util.concurrent.CopyOnWriteArrayList<Answer>()
        h.act(Op(device, OpId.Queued("s-$device", n), ReaderCore.WINDOW, action, listOf(target), result, expect)) { got += it }
        h.executor.call {}
        return got.single()
    }

    @Test
    fun theDescriptionNamesTheShelfTheActionsAndTheGlassesRows(): Unit = runBlocking {
        rig.writeBooks()
        Files.createDirectories(rig.books.resolve("series"))
        Files.writeString(rig.books.resolve("series/gamma.txt"), "GAMMA\n\nOne paragraph.\n")
        val core = ReaderCore(LocalContent(rig.books), scope)
        val done = java.util.concurrent.CountDownLatch(1)
        core.addShelfListener { _, _ -> done.countDown() }
        core.refreshLibrary()
        done.await()
        val alpha = core.library.first { it.title.equals("alpha", true) }.id
        val st = WindowState(ReaderCore.WINDOW, mapOf(
            item(ReaderCore.placeItem(alpha), JsonPrimitive(ReaderAnchor(1234).format())),
            item(ReaderCore.bookmarkItem(alpha), JsonPrimitive(ReaderAnchor(99).format())),
            item(ReaderCore.RECENT, JsonPrimitive(alpha)),
            item(ReaderCore.SCROLL_LINES, JsonPrimitive(3)),
        ), emptyMap())
        val d = core.describe(st)
        assertEquals("reader", d["id"]!!.jsonPrimitive.content)
        assertEquals("view", d["min"]!!.jsonPrimitive.content)
        val shelf = d["shelf"]!!.jsonObject
        assertEquals(listOf("series"), shelf["folders"]!!.jsonArray.map { it.jsonPrimitive.content })
        val books = shelf["books"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, books.size, "the root's own books; the folder's are under it")
        val a = books.first { it["id"]!!.jsonPrimitive.content == alpha }
        for (k in listOf("id", "title", "author", "bytes", "folder", "fraction")) assertTrue(k in a, "book field $k: $a")
        assertEquals(JsonNull, a["fraction"], "the fraction waits for the reader index (P5)")
        assertEquals(1234, ReaderAnchor.parse(a["place"]!!.jsonPrimitive.content)!!.offset)
        assertEquals(alpha, d["recent"]!!.jsonPrimitive.content)
        assertEquals(listOf("open", "place", "bookmark", "jump", "rescan", "reset"),
            d["actions"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val settings = d["settings"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["value"]!! }
        assertEquals(JsonPrimitive(3), settings[ReaderCore.SCROLL_LINES])
        assertEquals(JsonPrimitive(false), settings[ReaderCore.SCROLL_ACCEL], "a row the record lacks shows its default")
        assertEquals(JsonPrimitive(0), settings[ReaderCore.SIZE])
        // levels by address
        val folder = core.level(st, "folder:series")!!
        assertEquals(listOf("gamma"), folder["books"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content.lowercase() })
        assertEquals(alpha, core.level(st, "book:$alpha")!!["id"]!!.jsonPrimitive.content)
        assertNull(core.level(st, "book:nope"))
        assertNull(core.level(st, "folder:nope"))
        assertNull(core.level(st, "chapter:1"))
    }

    @Test
    fun resolveFindsABook_asksTheSourceItselfBeforeGone_andIgnoresOtherAddresses(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val inner = LocalContent(rig.books)
        val freshAsks = AtomicInteger()
        val counting = object : ContentProvider {
            override fun library() = inner.library()
            override fun library(fresh: Boolean): List<wm.damage.core.content.BookMeta> {
                if (fresh) freshAsks.incrementAndGet()
                return inner.library()
            }
            override fun openBook(id: String) = inner.openBook(id)
            override fun state() = ""
        }
        val core = ReaderCore(counting, scope)
        val r = core.resolve("book:${ids[0]}")
        assertIs<ReaderCore.Resolved.Book>(r)
        assertEquals(ids[0], r.meta.id)
        assertEquals(0, freshAsks.get(), "a hit needs no fresh listing")
        assertEquals(ReaderCore.Resolved.Gone, core.resolve("book:no-such-book"))
        assertEquals(1, freshAsks.get(), "a miss asks the source itself before it says gone")
        assertNull(core.resolve("mail:whatever"))
        assertNull(core.resolve("book:"))
    }

    @Test
    fun eachActionAsTheRecordJudgesIt(): Unit = runBlocking {
        assertEquals(Kind.POSITION, ReaderCore.ACTIONS.spec("reader", ReaderCore.PLACE)!!.kind)
        assertEquals(Kind.POSITION, ReaderCore.ACTIONS.spec("reader", ReaderCore.MIGRATE)!!.kind)
        for (a in listOf(ReaderCore.BOOKMARK, ReaderCore.SET, ReaderCore.OPEN))
            assertEquals(Kind.REGISTER, ReaderCore.ACTIONS.spec("reader", a)!!.kind, a)
        assertEquals(Kind.REMOVE, ReaderCore.ACTIONS.spec("reader", ReaderCore.RESET)!!.kind)
        assertNull(ReaderCore.ACTIONS.spec("reader", "jump"), "jump moves the view; the place follows at the save point")
        assertNull(ReaderCore.ACTIONS.spec("music", ReaderCore.PLACE))

        val h = rig.pc()
        val p10 = JsonPrimitive(ReaderAnchor(10).format())
        val p20 = JsonPrimitive(ReaderAnchor(20).format())
        // the place: applies on what was seen, loses once another device moved it
        assertIs<Answer.Seen>(ask(h, "phone", 1, ReaderCore.PLACE, ReaderCore.placeItem("a"), p10, Expect.Value(JsonNull)))
        assertIs<Answer.Seen>(ask(h, "web", 1, ReaderCore.PLACE, ReaderCore.placeItem("a"), p20, Expect.Value(p10)))
        val lost = ask(h, "phone", 2, ReaderCore.PLACE, ReaderCore.placeItem("a"), JsonPrimitive(ReaderAnchor(11).format()), Expect.Value(p10))
        assertIs<Answer.Lost>(lost)
        assertEquals(p20, lost.standing)
        // the book read most recently: arrival order, two devices opening different books never lose
        assertIs<Answer.Seen>(ask(h, "phone", 3, ReaderCore.OPEN, ReaderCore.RECENT, JsonPrimitive("a"), Expect.Any))
        assertIs<Answer.Seen>(ask(h, "web", 2, ReaderCore.OPEN, ReaderCore.RECENT, JsonPrimitive("b"), Expect.Any))
        assertEquals("b", h.read("reader").third.items["rec.reader.recent"]!!.value.jsonPrimitive.content)
        // a glasses row
        assertIs<Answer.Seen>(ask(h, "phone", 4, ReaderCore.SET, ReaderCore.SCROLL_LINES, JsonPrimitive(3), Expect.Value(JsonNull)))
        // reset progress: a removal expecting the revision seen
        val rev = rig.place(h, "a")!!.rev
        assertIs<Answer.Lost>(ask(h, "phone", 5, ReaderCore.RESET, ReaderCore.placeItem("a"), JsonNull, Expect.Rev(rev - 1)))
        assertIs<Answer.Seen>(ask(h, "phone", 6, ReaderCore.RESET, ReaderCore.placeItem("a"), JsonNull, Expect.Rev(rev)))
        assertTrue(rig.place(h, "a")!!.gone)
        // after a reset the device that knows it writes anew (expecting Any); a place written before it is an edit of
        // a removed item and loses
        assertIs<Answer.Lost>(ask(h, "web", 3, ReaderCore.PLACE, ReaderCore.placeItem("a"), p10, Expect.Value(p20)))
        assertEquals(Expect.Any, ReaderCore.expectSeen(rig.place(h, "a")))
        assertIs<Answer.Seen>(ask(h, "phone", 7, ReaderCore.PLACE, ReaderCore.placeItem("a"), p10, ReaderCore.expectSeen(rig.place(h, "a"))))
    }

    @Test
    fun aBookmarkBelongsToItsBook(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        assertTrue(g.open("book:${ids[0]}"))
        readerAwait("the first book opens") { g.title().contains("p.") }
        services.onLoop {
            g.doc().topLine = 12
            (g.view() as WindowView.DocView).onTap()                       // the actions level
            val i = g.actionLabels().indexOf("Bookmark here")
            (g.view() as WindowView.ListView).onCommit(i)
            g.saveSubState()                                                // the save point sends it
        }
        readerAwait("the bookmark reached the record under its book") {
            h.read("reader").third.items[Record.key("reader", ReaderCore.bookmarkItem(ids[0]))] != null
        }
        assertNull(h.read("reader").third.items.keys.firstOrNull { it == "rec.reader.bookmark" }, "never one for every book")
        val off = anchorOffset(h.read("reader").third.items[Record.key("reader", ReaderCore.bookmarkItem(ids[0]))]!!.value)
        assertTrue(off != null && off > 0)
        // the second book has none
        services.onLoop { (g.view() as WindowView.DocView).onTap(); (g.view() as WindowView.ListView).onCommit(g.actionLabels().indexOf("Library")) }
        assertTrue(g.open("book:${ids[1]}"))
        readerAwait("the second book opens") { g.title().contains("p.") && g.title().lowercase().contains("beta") }
        assertEquals(-1, services.onLoop { g.saveState()["bookmark"]!!.jsonPrimitive.intOrNull }, "no bookmark in the second book")
    }

    @Test
    fun resetProgressOffersCancelFirst_andRemovesTheRecordsPlace(): Unit = runBlocking {
        val ids = rig.writeBooks()
        val h = rig.pc()
        val services = ReaderServices()
        val g = rig.pcGlasses(h, services)
        assertTrue(g.open("book:${ids[0]}"))
        readerAwait("opens") { g.title().contains("p.") }
        services.onLoop { g.doc().topLine = 8; g.saveSubState() }
        readerAwait("the place is in the record") { rig.placeOffset(h, ids[0]) != null }
        val row = g.appSettings().single { it.name == "Reset progress" }
        val options = services.onLoop { row.options() }
        assertEquals("cancel", options.first(), "cancel first, as built")
        services.onLoop { row.apply("cancel") }
        h.executor.call {}
        assertTrue(rig.placeOffset(h, ids[0]) != null, "cancel changes nothing")
        services.onLoop { row.apply(options[1]) }
        readerAwait("the record's place is removed") { rig.place(h, ids[0])?.gone == true }
        assertEquals("local", rig.place(h, ids[0])!!.by)
        assertTrue(services.onLoop { g.saveSubState() }["book.${ids[0]}"] == null, "and the local copy's")
        assertEquals("library", g.title(), "the open book closed: a first open again")
    }

    @Test
    fun theRecordHelpersReadOnlyLivePlaces() {
        val st = WindowState(ReaderCore.WINDOW, mapOf(
            item(ReaderCore.placeItem("a"), JsonPrimitive(ReaderAnchor(5).format())),
            Record.key(ReaderCore.WINDOW, ReaderCore.placeItem("b")) to Item(JsonNull, 2, "web", 1L, gone = true),
            item(ReaderCore.placeItem("c"), JsonPrimitive("not a place")),
        ), emptyMap())
        assertEquals(mapOf("a" to 5), ReaderCore.places(st).mapValues { it.value.offset })
        assertNull(ReaderCore.placeOf(st, "b"))
        assertNull(ReaderCore.placeOf(st, "c"))
    }
}
