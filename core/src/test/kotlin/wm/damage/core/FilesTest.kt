package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.net.RemoteWin
import wm.damage.core.net.WinService
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.files.FEntry
import wm.damage.core.windows.files.FLocation
import wm.damage.core.windows.files.FilesProvider
import wm.damage.core.windows.files.FilesService
import wm.damage.core.windows.files.FilesWindow
import wm.damage.core.windows.files.LocalFilesProvider
import wm.damage.core.windows.files.RemoteFilesProvider
import wm.damage.core.wire.EvenHubMsg

/**
 * The Files window (Adam's chosen first conversion, 2026-09-01): the
 * tap-is-a-context-menu grammar, the This-folder wrap row, the clipboard
 * slot, trash → restore, typed rename, viewer position persistence — and the
 * §16.10 window channel it rides (loopback request/blob/failure, and the
 * whole provider remoted through a real server).
 */
class FilesTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private var dump: () -> String = { "" }

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(25)
        assertTrue(cond(), "did not converge: $what [state: ${dump()}]")
    }

    /** A deterministic world: one SC root (+ Trash when non-empty). */
    private class Harness(tmp: Path) {
        val root: Path = tmp.resolve("sc-root")
        val books: Path = tmp.resolve("books")
        val trash: Path = tmp.resolve("trash")
        val local = LocalFilesProvider(books, trash, decoder = null,
            mountsFile = tmp.resolve("no-mounts"))
        val provider = object : FilesProvider by local {
            override fun locations(): List<FLocation> =
                listOf(FLocation("SC", root.toString(), "mount", 1_000_000, 500_000)) +
                    local.locations().filter { it.kind == "trash" }
        }

        init {
            Files.createDirectories(root.resolve("sub"))
            Files.createDirectories(books)
            Files.writeString(root.resolve("sub").resolve("inner.txt"), "inner file\n")
            Files.writeString(root.resolve("a.txt"),
                (1..120).joinToString("\n") { "line $it of the a file" } + "\n")
        }
    }

    private class Rig(tmp: Path) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val h = Harness(tmp)
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val win = FilesWindow(FakeText(), h.provider, scope)

        init {
            shell.register(win)
        }

        suspend fun start() {
            shell.start()
            shell.postGesture(EvenHubMsg.EV_CLICK)     // Main cursor 0 = Files
        }

        suspend fun stop() {
            shell.stop()
            scope.cancel()
        }

        fun tap() = shell.postGesture(EvenHubMsg.EV_CLICK)
        fun back() = shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        fun down() = shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        fun up() = shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
    }

    @Test
    fun twoTapNavigationAndMenuGrammar(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-files-nav")
        val r = Rig(tmp)
        try {
            r.start()
            awaitTrue("locations listed") { r.win.summary().line == "1 locations" }
            r.tap()                                     // enter SC
            awaitTrue("browse lists the root") { r.win.title().endsWith("sc-root") }
            awaitTrue("entries arrive") { r.win.summary().detail.contains("1 folders") }

            // Adam's grammar: tap = context menu with Open first; two taps enter
            r.tap()
            awaitTrue("menu opens on the folder") { r.shell.menuIsOpen }
            r.tap()                                     // Open (cursor rests on it)
            awaitTrue("descended into sub") { r.win.title().endsWith("/sub") }
            assertFalse(r.shell.menuIsOpen)

            // double-tap ascends and restores the parent cursor
            r.back()
            awaitTrue("back at the root") { r.win.title().endsWith("sc-root") }

            // menu cancel: open on a row, double-tap closes, nothing navigates
            r.tap()
            awaitTrue("menu open") { r.shell.menuIsOpen }
            r.back()
            awaitTrue("menu cancelled") { !r.shell.menuIsOpen }
            assertTrue(r.win.title().endsWith("sc-root"), "cancel must not navigate")

            // back out to locations, then to Main
            r.back()
            awaitTrue("locations again") { r.win.summary().line.contains("locations") }
            r.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun textViewerScrollAndPersistence(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-files-view")
        val r = Rig(tmp)
        try {
            r.start()
            awaitTrue("locations") { r.win.summary().line == "1 locations" }
            r.tap()
            awaitTrue("entries") { r.win.summary().detail.contains("1 folders") }
            r.down()                                    // cursor: sub → a.txt
            r.tap()
            awaitTrue("menu") { r.shell.menuIsOpen }
            r.tap()                                     // Open
            awaitTrue("text viewer open") { r.win.title() == "a.txt" }
            awaitTrue("content loaded") {
                (r.win.saveState()["viewTop"]?.jsonPrimitive?.intOrNull ?: -1) == 0
            }
            repeat(6) { r.down() }
            // all six notches × the 5-line step — wait for the FINAL position,
            // not the first processed notch (the earlier read raced the queue)
            awaitTrue("scrolled to 30") {
                (r.win.saveState()["viewTop"]?.jsonPrimitive?.intOrNull ?: 0) == 30
            }
            val top = 30
            r.stop()

            // §9.1 + the continuity substrate: a new shell restores the SAME
            // file at the SAME line
            val r2 = Rig(tmp)
            dump = { "title='${r2.win.title()}' state=${r2.win.saveState()}" }
            r2.shell.start()
            awaitTrue("viewer restored") { r2.win.title() == "a.txt" }
            awaitTrue("position restored") {
                (r2.win.saveState()["viewTop"]?.jsonPrimitive?.intOrNull ?: -1) == top
            }
            r2.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun clipboardCopyPasteAndTrashRestore(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-files-ops")
        val r = Rig(tmp)
        dump = { "title='${r.win.title()}' menu=${r.shell.menuIsOpen} summary='${r.win.summary().line}/${r.win.summary().detail}' state=${r.win.saveState()}" }
        try {
            r.start()
            awaitTrue("locations") { r.win.summary().line == "1 locations" }
            r.tap()
            awaitTrue("entries") { r.win.summary().detail.contains("1 folders") }

            // copy a.txt: rows [sub, a.txt, ThisFolder]
            r.down()
            r.tap()
            awaitTrue("menu opened for a.txt") { r.shell.menuIsOpen && r.shell.menuTitle == "a.txt" }
            repeat(2) { r.down() }                      // Open→OpenOnPC→Copy
            r.tap()
            awaitTrue("clip armed") { r.win.title().contains("copy: a.txt") }

            // paste into sub via the folder menu ("Paste here" after Cut)
            r.up()                                      // cursor back to sub
            r.tap()
            awaitTrue("folder menu") { r.shell.menuIsOpen }
            repeat(4) { r.down() }                      // Open,OpenOnPC,Copy,Cut → Paste here
            r.tap()
            awaitTrue("pasted into sub") { Files.exists(r.h.root.resolve("sub").resolve("a.txt")) }

            // trash the original: a.txt menu → wrap up to Delete → confirm
            r.down()                                    // sub → a.txt
            r.tap()
            awaitTrue("menu") { r.shell.menuIsOpen }
            r.up()                                      // wrap to Delete (last row)
            r.tap()
            awaitTrue("confirm menu") { r.shell.menuIsOpen }
            r.down()                                    // Cancel → Move to Trash
            r.tap()
            awaitTrue("moved to trash") { !Files.exists(r.h.root.resolve("a.txt")) }
            awaitTrue("manifest has it") { r.h.local.trashList().any { it.name == "a.txt" } }

            // back to locations: Trash appears; restore it
            r.back()
            awaitTrue("locations show trash") { r.win.summary().line == "2 locations" }
            r.down()                                    // SC → Trash
            r.tap()
            awaitTrue("trash listed") { r.win.summary().line.startsWith("trash") }
            r.tap()
            awaitTrue("trash menu") { r.shell.menuIsOpen }
            r.tap()                                     // Restore (row 0)
            awaitTrue("restored") { Files.exists(r.h.root.resolve("a.txt")) }
            r.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun typedRenameConfirms(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-files-rename")
        val r = Rig(tmp)
        try {
            r.start()
            awaitTrue("locations") { r.win.summary().line == "1 locations" }
            r.tap()
            awaitTrue("entries") { r.win.summary().detail.contains("1 folders") }
            r.down()                                    // a.txt
            r.tap()
            awaitTrue("menu") { r.shell.menuIsOpen }
            repeat(4) { r.down() }                      // → Rename
            r.tap()
            awaitTrue("rename armed (menu closed)") { !r.shell.menuIsOpen }
            r.transport.injectText("b.txt")
            awaitTrue("confirm menu for the typed name") { r.shell.menuIsOpen }
            // nothing renamed before the confirm
            assertTrue(Files.exists(r.h.root.resolve("a.txt")))
            r.down()                                    // Cancel → Apply
            r.tap()
            awaitTrue("renamed") { Files.exists(r.h.root.resolve("b.txt")) &&
                !Files.exists(r.h.root.resolve("a.txt")) }
            r.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun menuDefersNotifications(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-files-menu-notice")
        val r = Rig(tmp)
        try {
            r.start()
            awaitTrue("locations") { r.win.summary().line == "1 locations" }
            r.tap()
            awaitTrue("entries") { r.win.summary().detail.contains("1 folders") }
            r.tap()
            awaitTrue("menu open") { r.shell.menuIsOpen }
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice(
                "SMS · TEST", "t", "waits behind the menu", "12:00"))
            delay(300)
            assertFalse(r.shell.notifications.active, "a notice must WAIT behind the menu (decision 6)")
            // an EMERGENCY cancels the menu and shows FIRST — ahead of the
            // parked ordinary box (R2#3: addLast seated the ordinary box
            // ahead of the alert, inverting "the emergency shows now")
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice(
                "WEA · ALERT", "e", "tornado warning", "12:01", emergency = true))
            awaitTrue("the emergency cancels the menu") { !r.shell.menuIsOpen }
            // poll for the FINAL state: the test thread can observe the
            // transient mid-handleNotice seat (the parked box) between the
            // menu close and the requeue+post on the loop
            awaitTrue("the EMERGENCY shows first, not the parked ordinary box") {
                r.shell.notifications.current?.emergency == true
            }
            assertTrue(r.shell.notifications.queued().any { it.source.startsWith("SMS") },
                "the parked ordinary box waits BEHIND the emergency")
            r.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---------------------------------------------------- the window channel

    @Test
    fun winChannelRoundTripBlobAndFailure(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-winnet")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        var host: ContentHostServer? = null
        var ch: RemoteWin? = null
        try {
            val echo = object : WinService {
                override fun request(op: String, args: kotlinx.serialization.json.JsonObject): WinService.Answer =
                    when (op) {
                        "echo" -> WinService.Answer(buildJsonObject {
                            put("said", args["say"]!!.jsonPrimitive.content)
                        })
                        "blob" -> WinService.Answer(buildJsonObject { put("n", 3) },
                            byteArrayOf(1, 2, 3))
                        else -> throw IllegalArgumentException("nope: $op")
                    }
            }
            host = ContentHostServer(LocalContent(tmp), port, "tok", win = mapOf("echo" to echo))
            host.start()
            ch = RemoteWin("127.0.0.1", port, "tok", "echo", scope, retryPacingMs = 200)
            awaitTrue("channel up") { ch!!.stateLine.isEmpty() }

            val a = ch.request("echo", buildJsonObject { put("say", "hi") })
            assertEquals("hi", a.data["said"]!!.jsonPrimitive.content)

            val b = ch.request("blob")
            assertEquals(3, b.data["n"]!!.jsonPrimitive.intOrNull)
            assertTrue(b.blob!!.contentEquals(byteArrayOf(1, 2, 3)))

            val err = try { ch.request("bad"); null } catch (e: Exception) { e.message }
            assertEquals("nope: bad", err, "a refused op carries its reason")
        } finally {
            ch?.close()
            host?.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun remoteFilesProviderOverARealServer(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-files-remote")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        var host: ContentHostServer? = null
        var remote: RemoteFilesProvider? = null
        try {
            val h = Harness(tmp)
            host = ContentHostServer(LocalContent(tmp), port, "tok",
                win = mapOf("files" to FilesService(h.provider)))
            host.start()
            remote = RemoteFilesProvider("127.0.0.1", port, "tok", scope)
            awaitTrue("channel up") { remote!!.stateLine().isEmpty() }

            assertEquals(listOf("SC"), remote.locations().map { it.label })
            val listed = remote.list(h.root.toString(), false)
            assertEquals(setOf("sub", "a.txt"), listed.map(FEntry::name).toSet())

            val chunk = remote.readText(h.root.resolve("a.txt").toString(), 0, 64)
            assertTrue(chunk.text.startsWith("line 1 of"))
            assertTrue(chunk.more)

            val id = remote.trash(h.root.resolve("a.txt").toString())
            assertFalse(Files.exists(h.root.resolve("a.txt")))
            assertEquals(h.root.resolve("a.txt").toString(), remote.restore(id))
            assertTrue(Files.exists(h.root.resolve("a.txt")))

            val refused = try { remote.list("/no/such/dir-xyz", false); null } catch (e: Exception) { e.message }
            assertTrue(refused != null && refused.contains("not a directory"),
                "a bad dir refuses with its reason (was: $refused)")
        } finally {
            remote?.close()
            host?.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
