package wm.damage.core

import java.net.ServerSocket
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.content.ContentHostServer
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
import wm.damage.core.sync.RemoteSync
import wm.damage.core.sync.SyncPeer
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.reader.ReaderWindow
import wm.damage.core.wire.EvenHubMsg

/**
 * The §16.4 state substrate (agreed 2026-09-01): per-item SUB-RECORDS with
 * tombstoned removals, the merge-on-load §19.4 closure, the store-direct
 * record surviving a shell start, Reader's per-book split with legacy
 * migration, the §16.1 deep link, and the CONTINUITY GATE — save on shell A,
 * sync, restore on shell B, same position (WINDOWS.md checklist item 6).
 */
class SubstrateTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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

    @Test
    fun mergeLoadKeepsNewerInMemoryRecords(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-merge")
        try {
            val f = tmp.resolve("state.json")
            // an older store on disk
            val writer = Persistence(f)
            writer.put("window.w", buildJsonObject { put("v", 1) })
            writer.save()
            val diskStamp = writer.stamp("window.w")

            // a fresh instance receives a store-direct remote record BEFORE it
            // loads (the §19.4 startup race) — load must MERGE, not replace
            val p = Persistence(f)
            assertTrue(p.tryApplyRemote("window.w", buildJsonObject { put("v", 2) }, diskStamp + 500))
            p.load()
            assertEquals(2, p.get("window.w")!!["v"]!!.jsonPrimitive.intOrNull,
                "load() wiped a newer in-memory record — the §19.4 race is open again")
            assertEquals(diskStamp + 500, p.stamp("window.w"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun storeDirectApplyBeforeStartIsLiveAfterStart(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-prestart")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // seed a store with default-ish settings, older
            val seed = Persistence(tmp.resolve("state.json"))
            seed.put("shell.settings", ShellSettings(depth = 4).toJson())
            seed.save()
            delay(5)

            val p = Persistence(tmp.resolve("state.json"))
            val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                SimTransport.Timing(instant = true)), p, null, scope)
            shell.register(ItemsWindow())
            // a peer's record lands store-direct while the shell exists but is
            // not running — postSync refuses, the applier falls back to the store
            assertFalse(shell.postSync("shell.settings", ShellSettings(depth = 16).toJson(),
                System.currentTimeMillis() + 50), "postSync must refuse before start")
            p.tryApplyRemote("shell.settings", ShellSettings(depth = 16).toJson(),
                System.currentTimeMillis() + 50)

            shell.start()
            awaitTrue("the store-direct settings are live") { shell.settings.depth == 16 }
            shell.stop()
            // the first save must NOT have out-stamped the remote record with
            // stale live state — the value in the store is still depth 16
            assertEquals(16, ShellSettings.fromJson(p.get("shell.settings")).depth)
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------ sub-records

    @Test
    fun subRecordsSaveRestoreAndTombstone(): Unit = runBlocking {
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
            // b's record becomes the removal tombstone (empty object)
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
                assertEquals(JsonObject(emptyMap()), p.get("window.items.book.b"),
                    "a removed item must leave a tombstone, not a stale record")
            }

            // session 3: the tombstone restores as a removal
            run {
                val p = Persistence(store)
                val w = ItemsWindow()
                val sh = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                    SimTransport.Timing(instant = true)), p, null, scope)
                sh.register(w)
                sh.start()
                awaitTrue("a restored, b removed") { w.items == mapOf("book.a" to 1) }
                assertTrue("book.b" in w.restoredSubs, "the tombstone must reach restoreSubState")
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

            // the legacy-map half is migration input now: a book with no record of its own takes the map's entry,
            // and a book's own record beats the map
            val store = Persistence(tmp.resolve("state.json"))
            store.put("window.reader.book.$id", buildJsonObject { put("off", 999) })
            store.put("window.reader", buildJsonObject {
                put("level", "LIBRARY")
                put("offsets", buildJsonObject { put(id, 5); put("legacy-only", 42) })
            })
            val plan = wm.damage.core.windows.reader.ReaderMigration.plan(store)
            val offered = plan.offers.filter { it.action == "place" }.associate {
                it.targets.single() to wm.damage.core.windows.reader.ReaderAnchor.parse(it.result.jsonPrimitive.content)!!.offset
            }
            assertEquals(mapOf("place.$id" to 999, "place.legacy-only" to 42), offered)

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
    fun virginStoreBaselinesLoseToRealRecords(): Unit = runBlocking {
        // R2#16: a virgin device's save must not stamp window DEFAULTS over
        // the fleet — startLocked seeds stamp-0 baselines, and a real fleet
        // record beats them even after local save ticks with unchanged values
        val tmp = Files.createTempDirectory("damage-substrate-baseline")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = Persistence(tmp.resolve("state.json"))
            val sh = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                SimTransport.Timing(instant = true)), store, null, scope)
            val w = ItemsWindow()
            sh.register(w)
            sh.start()
            assertTrue(store.get("window.items") != null, "the baseline record exists locally")
            assertEquals(0L, store.stamp("window.items"), "the baseline is stamp 0")
            assertEquals(0L, store.stamp("shell.settings"), "settings baseline is stamp 0")
            sh.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // activity → a save
            sh.stop()                                        // saveAll: value-equal put
            assertEquals(0L, store.stamp("window.items"),
                "an unchanged save re-stamped the baseline — defaults would beat the fleet")
            // the fleet's real record wins
            assertTrue(store.tryApplyRemote("window.items", buildJsonObject { put("main", 9) }, 1_000L),
                "a real record must beat the stamp-0 baseline")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedSubRestoreInALaterSessionNeverTombstones(): Unit = runBlocking {
        // R2#5: the keeper restarts the SAME Shell all day — a key restored in
        // session N whose restore THROWS in session N+1 must not stay
        // "reported" from N, or saveAll tombstones the real record fleet-wide
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
                "a failed restore in a later session tombstoned the real record (R2#5)")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun saveAllNeverReEncodesAPeersSettingsRecord(): Unit = runBlocking {
        // R2#4: a peer-applied settings record must survive local save ticks
        // BYTE-IDENTICAL — re-putting our re-encoding re-stamps it and, across
        // versions with different shapes, ping-pongs restyles forever
        val tmp = Files.createTempDirectory("damage-substrate-echo")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = Persistence(tmp.resolve("state.json"))
            // the peer's record carries a field OUR encoder does not emit —
            // the version-shape-drift stand-in
            val peer = buildJsonObject { put("fontScale", 1.0); put("newerFieldWeDontKnow", 42) }
            assertTrue(store.tryApplyRemote("shell.settings", peer, 5_000L))
            val sh = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                SimTransport.Timing(instant = true)), store, null, scope)
            sh.register(ItemsWindow())
            sh.start()
            sh.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // activity → a save
            sh.stop()                                        // saveAll runs
            val (v, t) = store.record("shell.settings")!!
            assertEquals(peer, v, "saveAll re-encoded the peer's settings record (the F4 echo)")
            assertEquals(5_000L, t, "the peer's stamp was overwritten")
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
