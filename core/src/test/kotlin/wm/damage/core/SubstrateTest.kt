package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import wm.damage.core.content.LocalContent
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.geom.Rect
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.reader.ReaderWindow
import wm.damage.core.wire.EvenHubMsg

/**
 * The §16.4 state substrate (agreed 2026-09-01): per-item SUB-RECORDS and their
 * removal, the device store's start paths since S1's close (S1-5 §4.8: a store
 * an older build wrote, no file, a file that does not parse, a second start),
 * Reader's per-book split with legacy migration, and the §16.1 deep link.
 */
class SubstrateTest {

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        waitUntil(ms, 25, { "did not converge: $what" }) { cond() }
    }

    private class ItemsWindow : DamageWindow("items", "Items", IconKind.FILES) {
        val items = LinkedHashMap<String, Int>()
        val restoredSubs = ArrayList<String>()
        /** R2#5 harness: a sub-key whose restore THROWS (an unreadable blob). */
        var failRestoreOf: String? = null
        override fun view() = WindowView.ListView(ListModel(), { 1 },
            { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> }, {})
        override fun summary() = Summary("items")
        override fun saveState(): JsonObject = buildJsonObject { put("main", 1) }
        override fun restoreState(state: JsonObject) {}
        override fun saveSubState(): Map<String, JsonObject> =
            items.entries.associate { (k, v) -> k to buildJsonObject { put("v", v) } }
        override fun restoreSubState(subKey: String, state: JsonObject) {
            if (subKey == failRestoreOf) throw IllegalStateException("poisoned restore of $subKey")
            restoredSubs.add(subKey)
            val v = state["v"]?.jsonPrimitive?.intOrNull
            if (v == null) items.remove(subKey) else items[subKey] = v
        }
    }

    // ------------------------------------------------------------- the store

    private fun itemsShell(store: Persistence, scope: CoroutineScope, w: ItemsWindow = ItemsWindow()) =
        Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), store, null, scope)
            .also { it.register(w) }

    /** The file's records as written: key → (value, t). */
    private fun recordsOf(f: java.nio.file.Path): Map<String, Pair<JsonObject, Long>> =
        Json.parseToJsonElement(Files.readString(f)).jsonObject["records"]!!.jsonObject.mapValues { (_, e) ->
            e.jsonObject["v"]!!.jsonObject to e.jsonObject["t"]!!.jsonPrimitive.long
        }

    /** S1-5 §4.8: a store the last build wrote — stamps, a stamp-0 baseline, a removal's empty mark, a settings field
     *  this build does not know — opens unchanged: what was saved is restored, and every record is written back as it
     *  was (value and time). A second start over it writes nothing new. No conversion runs. */
    @Test
    fun aStoreTheLastBuildWroteOpensUnchanged_restores_andASecondStartWritesNothing(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-old")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val f = tmp.resolve("state.json")
            Files.writeString(f, """{"__v":2,"records":{""" +
                """"shell.settings":{"v":{"depth":16,"aFieldOfALaterBuild":42},"t":1700000000000},""" +
                """"window.items":{"v":{"main":1},"t":0},""" +
                """"window.items.a":{"v":{"v":7},"t":1700000000500},""" +
                """"window.items.gone":{"v":{},"t":1700000001000}}}""")
            val before = recordsOf(f)
            val w = ItemsWindow()
            val sh = itemsShell(Persistence(f), scope, w)
            sh.start()
            awaitTrue("what was saved is shown") { sh.settings.depth == 16 && w.items == mapOf("a" to 7) }
            assertTrue("gone" in w.restoredSubs, "an older build's removal mark reaches the window as a removal")
            sh.stop()
            val first = recordsOf(f)
            for ((k, r) in before) assertEquals(r, first[k], "'$k' was written back as it was")
            assertTrue((first.keys - before.keys).none { it.startsWith("window.items") || it == "shell.settings" },
                "nothing was added for the window or the settings: ${first.keys - before.keys}")
            // a second start over the same file: nothing changes
            val sh2 = itemsShell(Persistence(f), scope)
            sh2.start()
            awaitTrue("the second start runs") { sh2.isQuiescent() }
            sh2.stop()
            assertEquals(first, recordsOf(f), "a second start writes nothing new")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /** No file: the defaults, and the first stop writes a store of the current shape. */
    @Test
    fun aStartWithNoFileShowsTheDefaultsAndWritesItsStore(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-none")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val f = tmp.resolve("state.json")
            val store = Persistence(f)
            store.load()
            assertTrue(store.keysWithPrefix("").isEmpty(), "no file: nothing loaded")
            val sh = itemsShell(store, scope)
            sh.start()
            awaitTrue("running") { sh.isQuiescent() }
            assertEquals(ShellSettings(), sh.settings)
            sh.stop()
            assertEquals(2L, Json.parseToJsonElement(Files.readString(f)).jsonObject["__v"]!!.jsonPrimitive.long)
            assertEquals(JsonObject(mapOf("main" to kotlinx.serialization.json.JsonPrimitive(1))), recordsOf(f)["window.items"]?.first)
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /** A file that does not parse: kept byte for byte as `.bad` (nothing of it is removed or rewritten), the shell
     *  starts on the defaults, and its stop writes a new store beside it; a later session start over the same store
     *  does not read the file again. */
    @Test
    fun aStartWhoseFileDoesNotParseKeepsItAsBad_andShowsTheDefaults(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-bad")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val f = tmp.resolve("state.json")
            val bytes = """{"__v":2,"records":{"shell.settings":{"v":{"depth":16},"t":1""".toByteArray()   // cut short
            Files.write(f, bytes)
            val store = Persistence(f)
            val sh = itemsShell(store, scope)
            sh.start()
            awaitTrue("running") { sh.isQuiescent() }
            assertEquals(ShellSettings(), sh.settings, "the defaults are shown")
            assertEquals(null, sh.currentWindowId(), "on Main")
            val bad = tmp.resolve("state.json.bad")
            assertTrue(bytes.contentEquals(Files.readAllBytes(bad)), "the unreadable file is kept as it was")
            sh.stop()
            assertTrue(recordsOf(f).containsKey("window.items"), "the stop wrote a new store")
            sh.start()                                      // a session start over the same store: memory is kept
            awaitTrue("running again") { sh.isQuiescent() }
            sh.stop()
            assertTrue(bytes.contentEquals(Files.readAllBytes(bad)), "the second session start left the kept file alone")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------ sub-records

    @Test
    fun subRecordsSaveRestoreAndRemove(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-subs")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = tmp.resolve("state.json")

            // session 1: two items, saved as individual records
            run {
                val p = Persistence(store)
                val w = ItemsWindow()
                w.items["book.a"] = 1
                w.items["book.b"] = 2
                val sh = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                    SimTransport.Timing(instant = true)), p, null, scope)
                sh.register(w)
                sh.start()
                sh.stop()
                assertEquals(1, p.get("window.items.book.a")!!["v"]!!.jsonPrimitive.intOrNull)
                assertEquals(2, p.get("window.items.book.b")!!["v"]!!.jsonPrimitive.intOrNull)
            }

            // session 2: both restored via restoreSubState; remove b; save →
            // b's record is removed from the store
            run {
                val p = Persistence(store)
                val w = ItemsWindow()
                val sh = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                    SimTransport.Timing(instant = true)), p, null, scope)
                sh.register(w)
                sh.start()
                awaitTrue("both sub-records restored") { w.items == mapOf("book.a" to 1, "book.b" to 2) }
                w.items.remove("book.b")
                sh.stop()
                assertEquals(null, p.get("window.items.book.b"), "a removed item is removed, not left stale")
            }

            // session 3: a restored, b gone
            run {
                val p = Persistence(store)
                val w = ItemsWindow()
                val sh = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                    SimTransport.Timing(instant = true)), p, null, scope)
                sh.register(w)
                sh.start()
                awaitTrue("a restored, b removed") { w.items == mapOf("book.a" to 1) }
                assertFalse("book.b" in w.restoredSubs, "nothing of b is left to restore")
                sh.stop()
            }
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------ the reader

    private fun writeBook(dir: java.nio.file.Path, name: String = "book.txt") {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), buildString {
            appendLine("CHAPTER I")
            appendLine()
            for (i in 1..200) {
                appendLine("Paragraph $i. The compositor batches all damage into one atomic flush.")
                appendLine()
            }
        })
    }

    @Test
    fun readerSplitsPerBookAndMigratesLegacyOffsets(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-reader")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            writeBook(tmp.resolve("books"))
            val content = LocalContent(tmp.resolve("books"))
            val id = content.library().single().id

            // build S1-1 P4 (dec. A.13): the shadow shape — one record per book, in today's shape — is still what
            // the presenter writes (the local copy an older build reads); a record restored comes back out whole
            val reader = ReaderWindow(FakeText(), content, scope)
            reader.restoreSubState("book.$id", buildJsonObject { put("off", 1234) })
            reader.restoreSubState("book.gone-book", buildJsonObject { put("off", 7) })
            reader.restoreState(buildJsonObject { put("level", "LIBRARY") })
            val subs = reader.saveSubState()
            assertEquals(1234, subs["book.$id"]!!["off"]!!.jsonPrimitive.intOrNull)
            assertEquals(7, subs["book.gone-book"]!!["off"]!!.jsonPrimitive.intOrNull)
            // the legacy whole-map still writes, for an older build's restore
            assertTrue(reader.saveState()["offsets"] != null)

            // the removal tombstone drops the entry
            val r2 = ReaderWindow(FakeText(), content, scope)
            r2.restoreSubState("book.$id", buildJsonObject { put("off", 999) })
            r2.restoreSubState("book.$id", JsonObject(emptyMap()))
            assertEquals(null, r2.saveSubState()["book.$id"])
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    private class Services : ShellServices {
        val notices = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun requestRender(window: DamageWindow) {}
        override fun setOperation(op: String) {}
        override fun notifyInternal(source: String, body: String, urgent: Boolean,
            appId: String?, thread: String, target: String?, answer: Boolean) { notices.add("$source: $body") }
        override fun openWindow(id: String, target: String?): Boolean = false
        override fun runOnShell(action: () -> Unit) = action()
        override fun docContentWidth(): Int = 560
        override fun docContentHeight(): Int = 384
    }

    @Test
    fun readerDeepLinkOpensBook(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-deeplink")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            writeBook(tmp.resolve("books"))
            val content = LocalContent(tmp.resolve("books"))
            val id = content.library().single().id
            val reader = ReaderWindow(FakeText(), content, scope)
            reader.onRegistered(Services())

            assertFalse(reader.open("mail:whatever"), "a foreign scheme is unsupported")
            assertTrue(reader.open("book:$id"), "a valid book target is accepted")
            awaitTrue("the deep-linked book opens") { reader.title().contains("p.") }

            // an unresolvable id is LOUD, not silent
            val r2 = ReaderWindow(FakeText(), content, scope)
            val s2 = Services()
            r2.onRegistered(s2)
            assertTrue(r2.open("book:no-such-id"))
            awaitTrue("the miss is reported") { s2.notices.any { it.contains("no-such-id") } }
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedSubRestoreInALaterSessionNeverRemoves(): Unit = runBlocking {
        // R2#5: the keeper restarts the SAME Shell all day — a key restored in
        // session N whose restore THROWS in session N+1 must not stay
        // "reported" from N, or saveAll removes the real record
        val tmp = Files.createTempDirectory("damage-substrate-sessions")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = Persistence(tmp.resolve("state.json"))
            val sh = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                SimTransport.Timing(instant = true)), store, null, scope)
            val w = ItemsWindow()
            sh.register(w)
            // session 1: the window holds and reports "a"
            w.items["a"] = 7
            sh.start()
            sh.stop()
            assertEquals(7, store.get("window.items.a")!!["v"]!!.jsonPrimitive.intOrNull,
                "session 1 saved the sub-record")
            // session 2 (same instance): the restore of "a" throws
            w.items.clear()
            w.failRestoreOf = "a"
            sh.start()
            sh.stop()
            val rec = store.get("window.items.a")
            assertTrue(rec != null && rec.isNotEmpty(),
                "a failed restore in a later session removed the real record (R2#5)")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveAllNeverReEncodesTheStoredSettingsRecord(): Unit = runBlocking {
        // R2#4: a stored settings record survives save ticks BYTE-IDENTICAL —
        // a field this build's encoder does not emit is kept
        val tmp = Files.createTempDirectory("damage-substrate-echo")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = Persistence(tmp.resolve("state.json"))
            // a field OUR encoder does not emit — the version-shape-drift stand-in
            val peer = buildJsonObject { put("fontScale", 1.0); put("newerFieldWeDontKnow", 42) }
            store.put("shell.settings", peer)
            val sh = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                SimTransport.Timing(instant = true)), store, null, scope)
            sh.register(ItemsWindow())
            sh.start()
            sh.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // activity → a save
            sh.stop()                                        // saveAll runs
            assertEquals(peer, store.get("shell.settings"), "saveAll re-encoded the stored settings record (the F4 echo)")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun deepLinkWhileAnOpenIsInFlightRefusesHonestly(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-busy")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            writeBook(tmp.resolve("books"))
            val inner = LocalContent(tmp.resolve("books"))
            val gate = java.util.concurrent.CountDownLatch(1)
            val gated = object : wm.damage.core.content.ContentProvider {
                override fun library() = inner.library()
                override fun openBook(id: String): java.nio.file.Path {
                    gate.await()                       // held until the test releases it
                    return inner.openBook(id)
                }
                override fun state() = ""
            }
            val id = inner.library().single().id
            val reader = ReaderWindow(FakeText(), gated, scope)
            reader.onRegistered(Services())

            assertTrue(reader.open("book:$id"), "the first deep link is accepted")
            // openingId is set synchronously before open() returns, so this is
            // deterministic: the second link must refuse, never claim success
            // while doing nothing (review 2026-09-01 F7)
            assertFalse(reader.open("book:$id"), "a second link while one is in flight refuses")

            gate.countDown()
            awaitTrue("the first open still completes after the refusal") {
                reader.title().contains("p.")
            }
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // -------------------------------------------------------- the continuity gate

    // `continuityBookPositionFollowsAcrossShells` is retired (build S1-1 P4, dec. A.13): a book's place no longer
    // crosses the sync channel; `ReaderArrangementsTest` holds the place across devices through the PC record.

    /** A shell over [content] with the Reader registered, its store in [tmp]; notify settings from [settings]. */
    private fun readerShell(tmp: java.nio.file.Path, content: wm.damage.core.content.ContentProvider, scope: CoroutineScope,
                            settings: ShellSettings? = null): Pair<Shell, ReaderWindow> {
        val store = Persistence(tmp.resolve("state.json")).also { p -> if (settings != null) p.put("shell.settings", settings.toJson()) }
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), store, null, scope)
        val reader = ReaderWindow(FakeText(), content, scope)
        shell.register(reader)
        return shell to reader
    }

    /** P7 item 5's sibling, item 6 (OS-5): a session start on the same instance with a book open restored it
     *  again — the library, the book's file, its parse and its whole layout, on IO, then dropped as "superseded"
     *  (seen once in the saved phone log, 2026-11-09 12:06). Through a real stop and start: no second open. */
    @Test
    fun aSessionStartWithTheBookOpenDoesNotOpenItAgain(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-reader-restart")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            writeBook(tmp.resolve("books"))
            val inner = LocalContent(tmp.resolve("books"))
            val opens = java.util.concurrent.atomic.AtomicInteger()
            val counting = object : wm.damage.core.content.ContentProvider {
                override fun library() = inner.library()
                override fun openBook(id: String): java.nio.file.Path { opens.incrementAndGet(); return inner.openBook(id) }
                override fun state() = ""
            }
            val id = inner.library().single().id
            val (shell, reader) = readerShell(tmp, counting, scope)
            shell.start()
            shell.services.runOnShell { shell.services.openWindow(reader.id, "book:$id") }
            waitUntil(20_000, 20, { "the book is open (${reader.title()})" }) { reader.title().contains("p.") && shell.isQuiescent() }
            val before = opens.get()
            shell.stop()
            shell.start()
            waitUntil(20_000, 20, { "the restart settled: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
            delay(1_000)   // the restore's job runs on IO after the start; this book's parse takes milliseconds
            assertEquals(before, opens.get(), "the open book was opened again at the session start")
            assertTrue(reader.title().contains("p."), "the book is still open: ${reader.title()}")
            shell.stop()
        } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** P7 item 2 (OS-2), one of the Reader's answers: a deep link to a book the library does not hold is an answer
     *  to Adam's own gesture — shown with Global `Notify · Damage` off. */
    @Test
    fun aBookNotInTheLibraryIsAnAnswerShownWithNotifyDamageOff(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-reader-answer")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            writeBook(tmp.resolve("books"))
            val (shell, _) = readerShell(tmp, LocalContent(tmp.resolve("books")), scope, ShellSettings(notifyDamage = false))
            shell.start()
            shell.services.runOnShell { shell.services.openWindow("reader", "book:nope") }
            waitUntil(20_000, 20, { "the answer is up (${shell.notifications.current})" }) {
                shell.notifications.current?.body?.startsWith("couldn't open book nope") == true }
            shell.stop()
        } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
    }
}
