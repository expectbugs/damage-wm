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
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(25)
        assertTrue(cond(), "did not converge: $what")
    }

    private class ItemsWindow : DamageWindow("items", "Items", IconKind.FILES) {
        val items = LinkedHashMap<String, Int>()
        val restoredSubs = ArrayList<String>()
        override fun view() = WindowView.ListView(ListModel(), { 1 },
            { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> }, {})
        override fun summary() = Summary("items")
        override fun saveState(): JsonObject = buildJsonObject { put("main", 1) }
        override fun restoreState(state: JsonObject) {}
        override fun saveSubState(): Map<String, JsonObject> =
            items.entries.associate { (k, v) -> k to buildJsonObject { put("v", v) } }
        override fun restoreSubState(subKey: String, state: JsonObject) {
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
            val reader = ReaderWindow(FakeText(), content, scope)

            // a legacy main record carrying the whole offsets map migrates in
            reader.restoreState(buildJsonObject {
                put("level", "LIBRARY")
                put("offsets", buildJsonObject { put(id, 1234); put("gone-book", 7) })
            })
            val subs = reader.saveSubState()
            assertEquals(1234, subs["book.$id"]!!["off"]!!.jsonPrimitive.intOrNull)
            assertEquals(7, subs["book.gone-book"]!!["off"]!!.jsonPrimitive.intOrNull)
            // TRANSITIONAL (review F3): the legacy map still writes so the
            // 0.15 APK and this build keep one main-record shape — remove
            // this (and saveState's field) once the phone runs ≥0.16
            assertTrue(reader.saveState()["offsets"] != null)

            // a sub-record beats the legacy map on restore order (subs first)
            val r2 = ReaderWindow(FakeText(), content, scope)
            r2.restoreSubState("book.$id", buildJsonObject { put("off", 999) })
            r2.restoreState(buildJsonObject {
                put("level", "LIBRARY")
                put("offsets", buildJsonObject { put(id, 5) })
            })
            assertEquals(999, r2.saveSubState()["book.$id"]!!["off"]!!.jsonPrimitive.intOrNull)

            // the removal tombstone drops the entry
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
            appId: String?, thread: String, target: String?) { notices.add("$source: $body") }
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

    // -------------------------------------------------------- the continuity gate

    @Test
    fun continuityBookPositionFollowsAcrossShells(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-substrate-continuity")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        var host: ContentHostServer? = null
        var client: RemoteSync? = null
        try {
            writeBook(tmp.resolve("books"))
            val content = LocalContent(tmp.resolve("books"))
            val id = content.library().single().id

            // shell A reads to a position and stops (the driving device)
            val storeA = Persistence(tmp.resolve("a.json"))
            run {
                val shA = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                    SimTransport.Timing(instant = true)), storeA, null, scope)
                val readerA = ReaderWindow(FakeText(), content, scope)
                shA.register(readerA)
                shA.start()
                shA.postGesture(EvenHubMsg.EV_CLICK)                    // into Reader
                awaitTrue("A's library") { readerA.summary().line.contains("book") }
                shA.postGesture(EvenHubMsg.EV_CLICK)                    // open the book
                awaitTrue("A opens the book") { readerA.title().contains("p.") }
                repeat(25) { shA.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                awaitTrue("A scrolled") { (readerA.summary().progress ?: 0.0) > 0.0 }
                shA.stop()
            }
            val posA = storeA.get("window.reader.book.$id")!!["off"]!!.jsonPrimitive.intOrNull!!
            assertTrue(posA > 0, "A recorded a real position")

            // the position syncs to store B (PC ⇄ phone shape)
            val storeB = Persistence(tmp.resolve("b.json"))
            host = ContentHostServer(content, port, "tok", sync = SyncPeer(storeA))
            host.start()
            client = RemoteSync("127.0.0.1", port, "tok", scope, SyncPeer(storeB), retryPacingMs = 200)
            awaitTrue("the per-book record reached B") {
                storeB.get("window.reader.book.$id")?.get("off")?.jsonPrimitive?.intOrNull == posA
            }

            // shell B starts on its own store: SAME book, SAME position —
            // "start reading on my glasses, continue on my phone" (Adam, §16.4)
            val shB = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                SimTransport.Timing(instant = true)), storeB, null, scope)
            val readerB = ReaderWindow(FakeText(), content, scope)
            shB.register(readerB)
            shB.start()
            awaitTrue("B restores the open book") { readerB.title().contains("p.") }
            awaitTrue("B is at A's position") {
                readerB.saveSubState()["book.$id"]?.get("off")?.jsonPrimitive?.intOrNull == posA
            }
            shB.stop()
        } finally {
            client?.close()
            host?.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
