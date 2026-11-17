package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.app.Item
import wm.damage.core.app.Record
import wm.damage.core.app.WindowState
import wm.damage.core.content.BookMeta
import wm.damage.core.content.LocalContent
import wm.damage.core.windows.reader.Epub
import wm.damage.core.windows.reader.ReaderAnchor
import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.reader.ReaderDocs
import wm.damage.core.windows.reader.ReaderIndex

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P5 (the spec's §2 P5 and §3's "reader index" row; decision B.1): the PC's reader index — one book
 * at a time, no image bytes, kept on disk, a moved file read again — and the documents the PC keeps (the last two).
 */
class ReaderIndexTest {
    private val tmp: Path = Files.createTempDirectory("damage-readerindex")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() {
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    private val books: Path = Files.createDirectories(tmp.resolve("books"))

    private fun meta(p: Path) = BookMeta(LocalContent.idFor(p), p.fileName.toString(), "", Files.size(p), p.toString())

    private fun twoChapters(p: Path, words: String = "Alpha beta."): Path = EpubFixture.write(p,
        listOf("c1.xhtml" to "<h1>One</h1><p>$words</p><img src=\"img/a.png\"/>", "c2.xhtml" to "<p>Second chapter.</p>"),
        images = mapOf("img/a.png" to EpubFixture.noise(20_000), "img/cover.png" to byteArrayOf(5)),
        titles = listOf("First", "Second"), coverMeta = "img1")

    private suspend fun awaitTrue(what: String, cond: () -> Boolean) {
        waitUntil(20_000, 10, { "did not converge: $what" }) { cond() }
    }

    @Test
    fun anEntryHoldsTheTextLengthChaptersAndCover() = runBlocking {
        val p = twoChapters(books.resolve("a.epub"))
        val today = Epub.load(p)
        val idx = ReaderIndex(tmp.resolve("reader-index.json"), scope)
        idx.update(listOf(meta(p)))
        awaitTrue("the book is indexed") { idx.entry(p.toString(), Files.size(p)) != null }
        val e = idx.entry(p.toString(), Files.size(p))!!
        assertNull(e.error)
        assertEquals(today.text.length, e.textLength)
        assertEquals(today.chapters.map { it.title to it.offset }, e.chapters.map { it.title to it.offset })
        assertEquals("OEBPS/img/cover.png", e.cover)
        assertEquals(1, e.coverRef, "the cover after the text's one image")
        assertEquals(2, e.images)
        assertEquals(0.0, e.fraction(null))
        assertEquals(0.5, e.fraction(ReaderAnchor(today.text.length / 2)) ?: -1.0, 0.01)
        assertEquals(1.0, e.fraction(ReaderAnchor(today.text.length * 3)), "a place past the end reads as the end")
        assertNull(idx.entry(p.toString(), Files.size(p) + 1), "another size is another file")
    }

    @Test
    fun noImageBytesAreRead() = runBlocking {
        val p = twoChapters(books.resolve("a.epub"))
        val length = Epub.load(p).text.length
        assertTrue(EpubFixture.spoilEntry(p, "OEBPS/img/a.png"), "the fixture's image can no longer be read")
        val idx = ReaderIndex(null, scope)
        idx.update(listOf(meta(p)))
        awaitTrue("the book is indexed") { idx.snapshot()[p.toString()] != null }
        val e = idx.snapshot()[p.toString()]!!
        assertNull(e.error, "the index never reads an image's bytes, so a spoiled image does not stop it: ${e.error}")
        assertEquals(length, e.textLength)
        assertEquals(1, e.coverRef)
    }

    @Test
    fun aStaleEntryIsRecomputed() = runBlocking {
        val p = twoChapters(books.resolve("a.epub"))
        val idx = ReaderIndex(tmp.resolve("reader-index.json"), scope)
        val computed = CopyOnWriteArrayList<ReaderIndex.Entry>()
        idx.addListener { computed += it }
        idx.update(listOf(meta(p)))
        awaitTrue("the first walk") { computed.size == 1 }
        val first = computed[0]
        // the same file again: nothing is read
        idx.update(listOf(meta(p)))
        val marker = books.resolve("marker.txt").also { Files.writeString(it, "Marker.") }
        idx.update(listOf(meta(p), meta(marker)))
        awaitTrue("the marker is indexed") { computed.any { it.path == marker.toString() } }
        assertEquals(listOf(p.toString(), marker.toString()), computed.map { it.path }, "an entry that stands is not read again")
        // the book rewritten (another text, another time): read again
        twoChapters(p, "Alpha beta gamma delta, a longer first chapter.")
        Files.setLastModifiedTime(p, FileTime.fromMillis(first.mtime + 5_000))
        idx.update(listOf(meta(p), meta(marker)))
        awaitTrue("the moved book is read again") { computed.size == 3 }
        val again = idx.snapshot()[p.toString()]!!
        assertEquals(Epub.load(p).text.length, again.textLength)
        assertTrue(again.textLength > first.textLength)
        // the same size, a new time: read again too (the identity is path, size and time)
        Files.setLastModifiedTime(p, FileTime.fromMillis(first.mtime + 9_000))
        idx.update(listOf(meta(p), meta(marker)))
        awaitTrue("a new time alone is read again") { computed.size == 4 }
    }

    @Test
    fun aBookThatFailsIsListedWithItsReason_andItsFractionAbsent() = runBlocking {
        val bad = books.resolve("broken.epub").also { Files.writeString(it, "not a zip") }
        val good = twoChapters(books.resolve("good.epub"))
        val idx = ReaderIndex(null, scope)
        val core = ReaderCore(LocalContent(books), scope, idx)
        core.refreshLibrary()
        awaitTrue("both indexed") { idx.snapshot().size == 2 }
        assertNotNull(idx.snapshot()[bad.toString()]!!.error)
        val shelf = core.describe(WindowState(ReaderCore.WINDOW, emptyMap()))["shelf"]!!.jsonObject["books"]!!.jsonArray
            .associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        val b = shelf[LocalContent.idFor(bad)]!!.jsonObject
        assertEquals(JsonNull, b["fraction"])
        assertTrue("unreadable" in b, "the reason is listed: $b")
        val g = shelf[LocalContent.idFor(good)]!!.jsonObject
        assertEquals(0.0, g["fraction"]!!.jsonPrimitive.doubleOrNull, "an unread book is at its start")
    }

    @Test
    fun theDescriptionTakesFractionCoverAndChapters() = runBlocking {
        val p = twoChapters(books.resolve("a.epub"))
        val len = Epub.load(p).text.length
        val idx = ReaderIndex(null, scope)
        val core = ReaderCore(LocalContent(books), scope, idx)
        core.refreshLibrary()
        awaitTrue("indexed") { idx.snapshot().isNotEmpty() }
        val id = LocalContent.idFor(p)
        val st = WindowState(ReaderCore.WINDOW, mapOf(
            Record.key(ReaderCore.WINDOW, ReaderCore.placeItem(id)) to Item(JsonPrimitive(ReaderAnchor(len / 4).format()), 1, "local", 1L),
        ))
        val card = core.describe(st)["shelf"]!!.jsonObject["books"]!!.jsonArray.single().jsonObject
        assertEquals(0.25, card["fraction"]!!.jsonPrimitive.doubleOrNull!!, 0.01)
        assertEquals("reader.$id.1", card["cover"]!!.jsonPrimitive.content)
        val level = core.level(st, "book:$id")!!
        val chapters = level["chapters"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("First", "Second"), chapters.map { it["title"]!!.jsonPrimitive.content })
        assertEquals(Epub.load(p).chapters.map { it.offset },
            chapters.map { ReaderAnchor.parse(it["anchor"]!!.jsonPrimitive.content)!!.offset })
        assertEquals(len, level["length"]!!.jsonPrimitive.content.toInt())
        // a core without the index (the phone): no fraction, no chapters, as before
        val bare = ReaderCore(LocalContent(books), scope)
        val done = java.util.concurrent.CountDownLatch(1)
        bare.addShelfListener { _, _ -> done.countDown() }
        bare.refreshLibrary()
        done.await()
        assertEquals(JsonNull, bare.describe(st)["shelf"]!!.jsonObject["books"]!!.jsonArray.single().jsonObject["fraction"])
        assertNull(bare.level(st, "book:$id")!!["chapters"])
    }

    @Test
    fun theIndexIsKeptOnDisk_readOnceAtStart_andABookGoneLeavesIt() = runBlocking {
        val file = tmp.resolve("data/reader-index.json")
        val a = twoChapters(books.resolve("a.epub"))
        val b = books.resolve("b.txt").also { Files.writeString(it, "Bee.") }
        val idx = ReaderIndex(file, scope)
        idx.update(listOf(meta(a), meta(b)))
        awaitTrue("written") { Files.exists(file) && ReaderIndex(file, scope).snapshot().size == 2 }
        val again = ReaderIndex(file, scope)
        val computed = CopyOnWriteArrayList<ReaderIndex.Entry>()
        again.addListener { computed += it }
        assertEquals(idx.snapshot(), again.snapshot(), "the file holds the index")
        // a walk over a shelf without b: nothing read, b leaves the index and the file
        again.update(listOf(meta(a)))
        awaitTrue("b left the file") { ReaderIndex(file, scope).snapshot().keys == setOf(a.toString()) }
        assertEquals(emptyList(), computed, "nothing was read again")
        // another extractor's index: every book is read again (its offsets are not this text's)
        Files.writeString(file, Files.readString(file).replace("\"extractor\":${ReaderAnchor.EXTRACTOR}", "\"extractor\":0"))
        assertTrue(ReaderIndex(file, scope).snapshot().isEmpty(), "another extractor's entries are not taken: ${Files.readString(file)}")
        // a file that is not an index: started empty, said, and written whole at the next walk
        Files.writeString(file, "{ not json")
        val fresh = ReaderIndex(file, scope)
        assertTrue(fresh.snapshot().isEmpty())
        fresh.update(listOf(meta(a)))
        awaitTrue("rewritten") { ReaderIndex(file, scope).snapshot().keys == setOf(a.toString()) }
    }

    @Test
    fun theLastTwoParsedBooksAreKept() {
        val ps = (0 until 3).map { twoChapters(books.resolve("b$it.epub")) }
        val byId = ps.associateBy { LocalContent.idFor(it) }
        val docs = ReaderDocs({ byId[it] })
        val ids = ps.map { LocalContent.idFor(it) }
        val d0 = docs.doc(ids[0])!!
        docs.doc(ids[1]); docs.doc(ids[2])
        assertEquals(listOf(ids[1], ids[2]), docs.heldBooks(), "the last two")
        assertTrue(docs.doc(ids[0]) !== d0, "the first was let go and is parsed again")
        assertEquals(listOf(ids[2], ids[0]), docs.heldBooks())
        val held = docs.doc(ids[2])!!
        assertTrue(docs.doc(ids[2]) === held, "a held book is not parsed again")
        Files.setLastModifiedTime(ps[2], FileTime.fromMillis(Files.getLastModifiedTime(ps[2]).toMillis() + 7_000))
        assertTrue(docs.doc(ids[2]) !== held, "a moved file is parsed again")
        assertNull(docs.doc("0000000000000000"), "not on the shelf")
        val part = docs.part(ids[0], 1)!!
        assertEquals("Second", part["title"]!!.jsonPrimitive.content)
        assertNull(docs.part(ids[0], 2))
    }

    /** Fix round 1, ruling 2.5: the shelf scan a PC process start makes, and the index walk after it, run on their own
     *  threads (`Dispatchers.IO`) — never on the core executor that judges every op (nor the shell loop): called FROM
     *  the executor, `refreshLibrary` returns while the listing is still being read, and neither the listing nor the
     *  walk runs on the executor's thread. */
    @Test
    fun theShelfScanAndTheIndexWalkNeverRunOnTheCoreExecutor() {
        twoChapters(books.resolve("a.epub"))
        val inner = LocalContent(books)
        val listedOn = java.util.concurrent.atomic.AtomicReference<Thread>()
        val walkedOn = java.util.concurrent.atomic.AtomicReference<Thread>()
        val release = java.util.concurrent.CountDownLatch(1)
        val content = object : wm.damage.core.content.ContentProvider by inner {
            override fun library(): List<BookMeta> {
                listedOn.set(Thread.currentThread())
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)   // a test's bound: the listing holds a while
                return inner.library()
            }
        }
        val idx = ReaderIndex(null, scope).also { it.addListener { _ -> walkedOn.compareAndSet(null, Thread.currentThread()) } }
        val core = ReaderCore(content, scope, idx)
        val exec = wm.damage.core.app.CoreExecutor("damage-core-scan-test")
        try {
            val coreThread = exec.call { Thread.currentThread() }
            val t0 = System.nanoTime()
            exec.call { core.refreshLibrary() }
            val heldMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue(heldMs < 2_000, "refreshLibrary held the core executor ${heldMs} ms (the listing ran on it)")
            release.countDown()
            runBlocking { awaitTrue("the shelf listed and the book indexed") { walkedOn.get() != null } }
            assertTrue(listedOn.get() !== coreThread, "the listing ran on the core executor")
            assertTrue(walkedOn.get() !== coreThread, "the index walk ran on the core executor")
        } finally {
            release.countDown()
            exec.shutdown()
        }
    }
}
