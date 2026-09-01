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
import wm.damage.core.shell.MenuSurface
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sync.RemoteSync
import wm.damage.core.sync.SyncPeer
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.files.LocalFilesProvider
import wm.damage.core.wire.EvenHubMsg

/**
 * Regression pins for the 2026-09-01 review round 1: the UTF-8 chunk seam,
 * the §16.2 back-to-caller hand-off, menu-over-menu hygiene, the
 * virgin-device freshen guard, the tombstone everReported guard, and the
 * Files viewer continuity gate (save on shell A → sync → restore on B).
 */
class ReviewRound1Test {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(25)
        assertTrue(cond(), "did not converge: $what")
    }

    // ------------------------------------------------------------ Fi#2: UTF-8 seam

    @Test
    fun readTextNeverSplitsAUtf8Character(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-utf8")
        try {
            val p = LocalFilesProvider(tmp.resolve("b"), tmp.resolve("t"), null,
                mountsFile = tmp.resolve("nm"))
            // 3-byte chars (€, U+20AC): any maxBytes not divisible by 3 lands
            // mid-character without the boundary trim
            val content = "€".repeat(2000)                       // 6000 bytes
            val f = tmp.resolve("euro.txt")
            Files.writeString(f, content)
            var off = 0L
            val sb = StringBuilder()
            var guard = 0
            while (true) {
                val c = p.readText(f.toString(), off, 1000)      // 1000 % 3 != 0
                assertFalse(c.text.contains('�'),
                    "a chunk seam produced a replacement char at offset $off")
                sb.append(c.text)
                assertTrue(c.bytesRead > 0, "progress at offset $off")
                off += c.bytesRead
                if (!c.more) break
                assertTrue(guard++ < 50, "chunking runs away")
            }
            assertEquals(content, sb.toString(), "reassembled text differs from the file")
            assertEquals(6000L, off, "consumed byte count drifted")

            // R2#1: a COMPLETE 4-byte character ending exactly at the chunk
            // boundary — the cap-3 walk stripped its continuations and left
            // the bare lead (four replacement chars across the seam)
            val emoji = "😀"                                      // U+1F600, 4 bytes
            val f4 = tmp.resolve("emoji.txt")
            Files.writeString(f4, emoji.repeat(500))              // 2000 bytes
            var off4 = 0L
            val sb4 = StringBuilder()
            var g4 = 0
            while (true) {
                val c = p.readText(f4.toString(), off4, 100)      // 100 % 4 == 0: boundary case
                assertFalse(c.text.contains('�'),
                    "a 4-byte char at the seam was mangled at offset $off4")
                sb4.append(c.text)
                off4 += c.bytesRead
                if (!c.more) break
                assertTrue(g4++ < 50, "4-byte chunking runs away")
            }
            assertEquals(emoji.repeat(500), sb4.toString(), "4-byte reassembly differs")

            // R2#2's provider side: invalid bytes must advance by what was
            // READ, not by the re-encoded (inflated) size — bytesRead is the
            // contract the window now trusts
            val junk = ByteArray(300) { 0x80.toByte() }           // bare continuations
            val fj = tmp.resolve("junk.bin")
            Files.write(fj, junk)
            var offJ = 0L
            var gJ = 0
            while (true) {
                val c = p.readText(fj.toString(), offJ, 64)
                assertTrue(c.bytesRead > 0, "junk read makes progress at $offJ")
                offJ += c.bytesRead
                assertTrue(offJ <= 300, "offset drifted past EOF (was the re-encode bug)")
                if (!c.more) break
                assertTrue(gJ++ < 50, "junk chunking runs away")
            }
            assertEquals(300L, offJ, "junk consumed byte count")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------- harness: two tiny windows

    private class MenuWindow(id: String) : DamageWindow(id, id.uppercase(), IconKind.FILES) {
        var svc: ShellServices? = null
        var closedCount = 0
        override fun view() = wm.damage.core.shell.WindowView.ListView(ListModel(), { 1 },
            { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> },
            onCommit = { openMenu("first") })
        fun openMenu(title: String) {
            svc?.openMenu(MenuSurface.Spec(title,
                listOf(MenuSurface.Item("Open"), MenuSurface.Item("Other")),
                onCommit = { }, onClose = { closedCount++ }))
        }
        override fun onRegistered(ctx: ShellServices) { svc = ctx }
        override fun summary() = Summary(id)
        override fun saveState(): JsonObject = buildJsonObject { }
        override fun restoreState(state: JsonObject) {}
        override fun open(target: String): Boolean = true
    }

    private class Rig(tmp: java.nio.file.Path, vararg windows: DamageWindow) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
            SimTransport.Timing(instant = true)), store, null, scope)

        init {
            for (w in windows) shell.register(w)
        }
    }

    // ----------------------------------------------------- S-F1: menu over menu

    @Test
    fun openMenuOverOpenMenuClosesTheFirstProperly(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-menu2")
        val w = MenuWindow("mw")
        val r = Rig(tmp, w)
        try {
            r.shell.start()
            r.shell.postGesture(EvenHubMsg.EV_CLICK)             // into the window
            r.shell.postGesture(EvenHubMsg.EV_CLICK)             // its commit opens menu 1
            awaitTrue("menu 1 open") { r.shell.menuIsOpen && r.shell.menuTitle == "first" }
            // a second openMenu (an async Stats landing) must CLOSE the first
            // — running its onClose — not capture its pixels as "under"
            r.shell.services.runOnShell { w.openMenu("second") }
            awaitTrue("menu 2 replaced menu 1") { r.shell.menuTitle == "second" }
            awaitTrue("menu 1's onClose ran") { w.closedCount == 1 }
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // cancel menu 2
            awaitTrue("menu 2 closed") { !r.shell.menuIsOpen }
            // poll, don't assert instantly: the menu closes BEFORE onClose
            // runs, and the test thread can win that window (R3 flake)
            awaitTrue("menu 2's onClose ran") { w.closedCount == 2 }
            r.shell.stop()
        } finally {
            r.scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------- S-F4: back to the caller

    @Test
    fun handOffBackReturnsToTheCaller(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-handoff")
        val a = object : DamageWindow("wina", "WinA", IconKind.FILES) {
            var svc: ShellServices? = null
            override fun view() = wm.damage.core.shell.WindowView.ListView(ListModel(), { 1 },
                { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> },
                onCommit = { svc?.openWindow("winb", "item:1") })
            override fun onRegistered(ctx: ShellServices) { svc = ctx }
            override fun summary() = Summary("a")
            override fun saveState(): JsonObject = buildJsonObject { }
            override fun restoreState(state: JsonObject) {}
        }
        var bOpened: String? = null
        val b = object : DamageWindow("winb", "WinB", IconKind.READER) {
            override fun view() = wm.damage.core.shell.WindowView.ListView(ListModel(), { 1 },
                { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> }, { })
            override fun summary() = Summary("b")
            override fun saveState(): JsonObject = buildJsonObject { }
            override fun restoreState(state: JsonObject) {}
            override fun open(target: String): Boolean { bOpened = target; return true }
        }
        val r = Rig(tmp, a, b)
        try {
            r.shell.start()
            r.shell.postGesture(EvenHubMsg.EV_CLICK)             // Main → WinA
            awaitTrue("in A") { r.shell.currentWindowId() == "wina" }
            r.shell.postGesture(EvenHubMsg.EV_CLICK)             // A hands off to B
            awaitTrue("handed to B at the target") {
                r.shell.currentWindowId() == "winb" && bOpened == "item:1"
            }
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // back from B's root
            awaitTrue("§16.2: back returns to the CALLER") { r.shell.currentWindowId() == "wina" }
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)      // and then out to Main
            awaitTrue("then Main") { r.shell.currentWindowId() == null }
            r.shell.stop()
        } finally {
            r.scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // -------------------------------------- Sub#F2: the virgin-freshen guard

    @Test
    fun virginStoreDoesNotOutstampThePeer(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-virgin")
        val w = MenuWindow("mw")
        val r = Rig(tmp, w)                                       // EMPTY store
        try {
            r.shell.start()
            awaitTrue("running") { r.shell.isQuiescent() }
            // a peer's settings arrive: the virgin device must APPLY them, not
            // freshen its defaults over them with a newer stamp
            val remote = ShellSettings(depth = 16).toJson()
            assertTrue(r.shell.postSync("shell.settings", remote, System.currentTimeMillis() - 60_000))
            awaitTrue("the peer's settings applied despite the older stamp") {
                r.shell.settings.depth == 16
            }
            r.shell.stop()
        } finally {
            r.scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // --------------------------------- Sub#F2c/d: the tombstone reported guard

    @Test
    fun tombstoneSweepSkipsKeysTheWindowNeverReported(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-tomb")
        try {
            // a synced sub-record exists for a window whose build reports NO
            // sub-state (an older build, or a failed restore): saving must NOT
            // tombstone it — that would sync a fresh-stamped removal of data
            val seed = Persistence(tmp.resolve("state.json"))
            seed.put("window.mw.book.x", buildJsonObject { put("off", 42) })
            seed.save()

            val w = MenuWindow("mw")                              // saveSubState = empty
            val r = Rig(tmp, w)
            r.shell.start()
            awaitTrue("running") { r.shell.isQuiescent() }
            r.shell.stop()                                        // saveAll runs

            val check = Persistence(tmp.resolve("state.json"))
            check.load()
            assertEquals(42, check.get("window.mw.book.x")?.get("off")?.jsonPrimitive?.intOrNull,
                "an unreported sub-record was tombstoned")
            r.scope.cancel()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // --------------------------------------- Files viewer continuity (D-A gate)

    @Test
    fun filesViewerPositionFollowsAcrossShells(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-files-continuity")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        var host: ContentHostServer? = null
        var client: RemoteSync? = null
        try {
            val root = tmp.resolve("root")
            Files.createDirectories(root)
            Files.writeString(root.resolve("log.txt"),
                (1..200).joinToString("\n") { "line $it" } + "\n")
            val lp = LocalFilesProvider(tmp.resolve("b"), tmp.resolve("t"), null,
                mountsFile = tmp.resolve("nm"))
            val provider = object : wm.damage.core.windows.files.FilesProvider by lp {
                override fun locations() = listOf(
                    wm.damage.core.windows.files.FLocation("R", root.toString(), "mount", 1, 1))
            }

            // shell A: open the file, scroll, stop
            val storeA = Persistence(tmp.resolve("a.json"))
            run {
                val shA = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                    SimTransport.Timing(instant = true)), storeA, null, scope)
                val winA = wm.damage.core.windows.files.FilesWindow(FakeText(), provider, scope)
                shA.register(winA)
                shA.start()
                shA.postGesture(EvenHubMsg.EV_CLICK)              // Files
                awaitTrue("locations") { winA.summary().line == "1 locations" }
                shA.postGesture(EvenHubMsg.EV_CLICK)              // R
                awaitTrue("listing") { winA.summary().detail.contains("1 files") }
                shA.postGesture(EvenHubMsg.EV_CLICK)              // menu on log.txt
                awaitTrue("menu") { shA.menuIsOpen }
                shA.postGesture(EvenHubMsg.EV_CLICK)              // Open
                awaitTrue("viewer") { winA.title() == "log.txt" }
                repeat(4) { shA.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                awaitTrue("scrolled to 20") {
                    (winA.saveState()["viewTop"]?.jsonPrimitive?.intOrNull ?: 0) == 20
                }
                shA.stop()
            }

            // sync A → B
            val storeB = Persistence(tmp.resolve("b.json"))
            host = ContentHostServer(LocalContent(tmp), port, "tok", sync = SyncPeer(storeA))
            host.start()
            client = RemoteSync("127.0.0.1", port, "tok", scope, SyncPeer(storeB), retryPacingMs = 200)
            awaitTrue("window.files reached B") { storeB.get("window.files") != null }

            // shell B: the SAME file at the SAME line — Adam's 100% bar
            val shB = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope,
                SimTransport.Timing(instant = true)), storeB, null, scope)
            val winB = wm.damage.core.windows.files.FilesWindow(FakeText(), provider, scope)
            shB.register(winB)
            shB.start()
            awaitTrue("B restores the viewer") { winB.title() == "log.txt" }
            awaitTrue("B is at A's line") {
                (winB.saveState()["viewTop"]?.jsonPrimitive?.intOrNull ?: -1) == 20
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
