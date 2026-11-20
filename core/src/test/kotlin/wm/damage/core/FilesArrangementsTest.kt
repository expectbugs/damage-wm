package wm.damage.core

import java.net.InetAddress
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Record
import wm.damage.core.app.WorkingCopy
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.MenuSurface
import wm.damage.core.shell.WindowView
import wm.damage.core.windows.files.FilesCore
import wm.damage.core.windows.files.FilesGlasses
import wm.damage.core.windows.files.FilesPc
import wm.damage.core.windows.files.FilesService
import wm.damage.core.windows.files.RemoteFilesProvider

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (`CONTRACT.md` §11.1; the spec's §4.2, §5 F2, F3, F5): Files in the phone's arrangement — the
 * glasses' presenter over the phone's working copy, fed by the `app` lane from a PC record on loopback, its filesystem
 * calls through the window channel into the PC's slotted provider (`FilesService`). The Sort row set on the glasses
 * reaches the PC record; a sort or hidden changed on another device keeps the browse cursor on the same entry NAME
 * (the wrong-file class); a cut pasted from the phone's glasses is cleared on every screen by the PC's core. The
 * files are under the test's own temp root.
 */
class FilesArrangementsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rig = ReaderRig(scope)
    private val tree = FilesTree()

    @AfterTest
    fun tearDown() {
        rig.close()
        tree.close()
        scope.cancel()
    }

    /** The shell's services with a menu that is answered by the test (the glasses' own menu path). */
    private class MenuServices : ReaderServices() {
        val menus = CopyOnWriteArrayList<MenuSurface.Spec>()
        override fun openMenu(spec: MenuSurface.Spec, owner: DamageWindow?): Boolean {
            menus += spec
            return true
        }
    }

    private class Arranged(val h: AppHost, val pc: FilesPc, val wc: WorkingCopy, val g: FilesGlasses, val services: MenuServices,
                           val remote: RemoteFilesProvider)

    /** The phone's arrangement. Its folder listings go through the Files window channel ([RemoteFilesProvider]), a
     *  connection of its own beside the app lane: it is returned with that channel UP (FILES, 2026-11-20: a test that
     *  waited only for the lane opened a folder before the channel had connected whenever the JVM was warm for the lane
     *  and not for the channel; the listing then failed as "not reachable" and nothing listed it again). [winPort]:
     *  the port the window channel dials instead of the PC's own server, with no wait (a test that brings it up). */
    private suspend fun arrange(winPort: Int? = null): Arranged {
        val h = rig.pc()
        val pc = FilesPc(h, tree.files, tree.books.toString(), null, null)
        val net = AppNet(h)
        val srv = ContentHostServer(LocalContent(java.nio.file.Files.createDirectories(rig.books)), 0, ReaderRig.TOKEN, app = net,
            bindAddress = InetAddress.getLoopbackAddress(), win = mapOf("files" to FilesService(pc.slotted)))
        srv.start()
        rig.keep(AutoCloseable { srv.close(); net.close() })
        val wc = rig.phone(srv.boundPort)
        val core = FilesCore()
        val src = rig.keep(LocalWindowSource(core, wc))
        var onChannel: FilesGlasses? = null                          // wired as the phone wires it (`ShellService`)
        val remote = rig.keep(RemoteFilesProvider("127.0.0.1", winPort ?: srv.boundPort, ReaderRig.TOKEN, scope,
            onState = { s -> if (s.isEmpty()) onChannel?.channelUp() }))
        val services = MenuServices()
        val g = FilesGlasses(FakeText(), core, src, remote, scope, device = wc.device).also { it.onRegistered(services) }
        onChannel = g
        if (winPort == null) readerAwait("the Files window channel is up: ${remote.stateLine()}") { remote.stateLine().isEmpty() }
        return Arranged(h, pc, wc, g, services, remote)
    }

    private var n = 0L

    /** Another device writes Files' row [target] on the PC record. */
    private fun elsewhere(h: AppHost, action: String, target: String, value: kotlinx.serialization.json.JsonElement) {
        val got = CopyOnWriteArrayList<wm.damage.core.app.Answer>()
        h.act(Op("web-q5", OpId.Queued("w-q5", ++n), FilesCore.WINDOW, action, listOf(target), value, at = System.currentTimeMillis())) { got += it }
        h.executor.call {}
        assertTrue(got.single() is wm.damage.core.app.Answer.Seen, "${got.single()}")
    }

    private fun item(h: AppHost, name: String) = h.read(FilesCore.WINDOW).third.items[Record.key(FilesCore.WINDOW, name)]

    /** Waits (a test's bound) for [cond], saying the presenter's title and summary when it does not come; decided on the
     *  answer that ended the wait ([waitUntil], the harness rule). */
    private suspend fun await(a: Arranged, what: String, cond: () -> Boolean) =
        waitUntil(20_000, 20, { "timed out waiting for: $what [title '${a.g.title()}', summary ${a.g.summary()}, " +
            "window channel '${a.remote.stateLine()}', notices ${a.services.notices}]" }) { cond() }

    private fun Arranged.cursor(i: Int) = services.onLoop { (g.view() as WindowView.ListView).model.cursor = i }

    private fun Arranged.cursorName(): String? = services.onLoop { g.browseCursorName() }

    /** Pin (the spec's Q5: "a sort changed elsewhere leaves the cursor on another file"; §5 F5). */
    @Test
    fun aSortOrHiddenChangedElsewhereKeepsTheCursorOnTheSameName(): Unit = runBlocking {
        val a = arrange()
        tree.file("w/a.txt", "1")
        tree.file("w/b.txt", "x".repeat(300))
        tree.file("w/c.txt", "y".repeat(20))
        tree.file("w/.z", "z".repeat(100))                       // by size it would come before c.txt
        val dir = tree.home.resolve("w").toString()
        readerAwait("the lane is up with the same Files build") { a.wc.client?.contractState?.shared(FilesCore.WINDOW) == true }
        assertTrue(a.services.onLoop { a.g.open("path:$dir/") })
        await(a, "the folder is listed") { a.g.summary().detail == "0 folders · 3 files" }
        a.cursor(2)
        assertEquals("c.txt", a.cursorName())
        // the shared sort moves at the desk: by size, c.txt is the second row now
        elsewhere(a.h, "sort", "sort", JsonPrimitive("size"))
        readerAwait("the phone's copy holds the new sort") { FilesCore.sortOf(a.g.source.state()) == "size" }
        // by size c.txt is the second row: the re-seat moves the cursor there on the shell loop (L1 A-N8: a wait on the
        // thing it names, not a fixed 300 ms)
        await(a, "the cursor re-seated on the second row") { a.services.onLoop { (a.g.view() as WindowView.ListView).model.cursor } == 1 }
        assertEquals("c.txt", a.cursorName(), "the cursor stays on the same file, never on the index")
        // hidden on at the desk: the folder is listed again and the cursor follows its name there
        elsewhere(a.h, "hidden", "hidden", JsonPrimitive(true))
        await(a, "relisted with the dotfile") { a.g.summary().detail == "0 folders · 4 files" }
        assertEquals("c.txt", a.cursorName())
    }

    /** Pin (FILES, ruled 2026-11-20), from the test above's order-dependent miss: the lane is up, the Files window
     *  channel is not yet, and the folder is opened. Its listing fails at once ("not reachable": logged, and on the
     *  glasses the This-folder row's line) while the summary says the channel's own line. When the channel comes up
     *  the folder is listed again by itself, once (before the ruling it stayed on "0 folders · 0 files" until he moved:
     *  the state that test timed out on). */
    @Test
    fun aFolderOpenedBeforeTheWindowChannelIsUp_isListedWhenItComesUp(): Unit = runBlocking {
        val winPort = java.net.ServerSocket(0).use { it.localPort }              // nothing serves it yet
        val a = arrange(winPort)
        tree.file("w/a.txt", "1")
        val dir = tree.home.resolve("w").toString()
        val failed = CopyOnWriteArrayList<String>()
        val sink = wm.damage.core.util.Log.Sink { _, tag, message -> if (tag == "files" && message.startsWith("list $dir failed")) failed += message }
        wm.damage.core.util.Log.addSink(sink)
        try {
            readerAwait("the lane is up") { a.wc.client?.contractState?.shared(FilesCore.WINDOW) == true }
            assertTrue(a.remote.stateLine().isNotEmpty(), "the window channel is not up yet: '${a.remote.stateLine()}'")
            assertTrue(a.services.onLoop { a.g.open("path:$dir/") })
            await(a, "the listing failed") { failed.isNotEmpty() }
            assertEquals(a.remote.stateLine(), a.services.onLoop { a.g.summary().line }, "the summary says the channel's line")
            val win = ContentHostServer(LocalContent(java.nio.file.Files.createDirectories(rig.books)), winPort, ReaderRig.TOKEN,
                bindAddress = InetAddress.getLoopbackAddress(), win = mapOf("files" to FilesService(a.pc.slotted)))
            win.start()
            rig.keep(win)
            await(a, "listed once the channel is up") { a.services.onLoop { a.g.summary().detail } == "0 folders · 1 files" }
            assertEquals("", a.remote.stateLine())
            assertEquals(1, failed.size, "the failed listing was not tried again while the channel was down: ${failed.toList()}")
            assertEquals(emptyList(), a.services.notices.toList())
        } finally {
            wm.damage.core.util.Log.removeSink(sink)
        }
    }

    /** Pin (FILES, ruled 2026-11-20): a folder the PC answers with an error (the channel up, nothing cached) says "not
     *  listed" in its summary, never "0 folders · 0 files" as if it were empty; his next look lists it as before. */
    @Test
    fun aFolderThePcCannotList_saysNotListed_neverAnEmptyFolder(): Unit = runBlocking {
        val a = arrange()
        val dir = tree.home.resolve("gone").toString()                       // never made: the PC's listing refuses it
        assertTrue(a.services.onLoop { a.g.open("path:$dir/") })
        await(a, "said as not listed") { a.services.onLoop { a.g.summary().detail } == FilesGlasses.NOT_LISTED }
        tree.dir("gone")
        assertTrue(a.services.onLoop { a.g.open("path:$dir/") })               // his next look lists it, as before
        await(a, "listed on the next look") { a.services.onLoop { a.g.summary().detail } == "0 folders · 0 files" }
    }

    /** The wrong-file class swept into the trash: newest first, so an entry trashed elsewhere shifts every index; a
     *  relist keeps the cursor on the entry it was on. */
    @Test
    fun aTrashRelistKeepsTheCursorOnTheSameEntry(): Unit = runBlocking {
        val services = MenuServices()
        val w = wm.damage.core.windows.files.FilesWindow(FakeText(), tree.files, scope).also { it.onRegistered(services) }
        val first = tree.file("t/first.txt")
        val second = tree.file("t/second.txt")
        tree.files.trash(first)
        Thread.sleep(5)
        tree.files.trash(second)                                   // the trash: second, first
        services.onLoop { w.onActivate(services, wm.damage.core.shell.ActivationSource.MAIN) }
        readerAwait("the locations show the trash") { services.onLoop { w.summary().line } == "3 locations" }
        services.onLoop { (w.view() as WindowView.ListView).onCommit(2) }    // Home, Books, Trash
        readerAwait("the trash is listed") { services.onLoop { w.summary().line } == "trash · 2" }
        services.onLoop { (w.view() as WindowView.ListView).model.cursor = 1 }
        assertEquals("first.txt", services.onLoop { w.trashCursorName() })
        Thread.sleep(5)
        tree.files.trash(tree.file("t/third.txt"))                  // elsewhere: it comes first, every index shifts
        services.onLoop { w.onActivate(services, wm.damage.core.shell.ActivationSource.SWITCHER) }   // the trash relists
        readerAwait("relisted") { services.onLoop { w.summary().line } == "trash · 3" }
        assertEquals("first.txt", services.onLoop { w.trashCursorName() }, "the cursor stays on the entry, never on the index")
    }

    /** Pin (L1 R1 RS FIX 1): a presenter started over a record that holds this device's own Sort, Hidden, Size and cut
     *  (written before a restart: Files keeps no local copy of them) shows them. */
    @Test
    fun aRestartedPresenterShowsThisDevicesOwnRows(): Unit = runBlocking {
        val cut = tree.file("r/a.txt", "1")
        tree.file("r/b.txt", "x".repeat(300))
        tree.file("r/.z", "z")
        val dir = tree.home.resolve("r").toString()
        val core = FilesCore()
        val rec = wm.damage.core.app.MemoryRecord(FilesCore.ACTIONS)               // writes as this device (`local`)
        val src = rig.keep(LocalWindowSource(core, rec))
        for (d in listOf(FilesCore.sortDraft("size"), FilesCore.hiddenDraft(true), FilesCore.sizeDraft(352),
            FilesCore.clipDraft("cut", cut))) src.act(d)                              // the presenter before the restart
        rec.executor.call {}
        val services = MenuServices()
        val g = FilesGlasses(FakeText(), core, src, wm.damage.core.windows.files.SlottedFiles(tree.files,
            wm.damage.core.windows.files.FilesJobs()), scope).also { it.onRegistered(services) }
        assertEquals(352, g.preferredHeight, "the Size row")
        assertTrue(services.onLoop { g.open("path:$dir/") })
        readerAwait("listed with the dotfile (Hidden)") { services.onLoop { g.summary().detail } == "0 folders · 3 files" }
        assertTrue(services.onLoop { g.title() }.endsWith("cut: a.txt"), "the cut: ${services.onLoop { g.title() }}")
        services.onLoop { g.view().let { v -> (v as WindowView.ListView).onCommit(v.rowCount() - 1) } }
        assertEquals("size", services.menus.last().items.single { it.label == "Sort" }.detail, "the Sort row")
    }

    /** Pin (L1 R1 B7, read on the surface): a Stats on the glasses that the supervisor ends frees the glasses — they
     *  show its words — while the stalled call stays in its own thread. */
    @Test
    fun aStatsTheSupervisorEndsFreesTheGlasses(): Unit = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val stalled = object : wm.damage.core.windows.files.FilesProvider by tree.files {
            override fun du(path: String): Long {
                entered.countDown()
                assertTrue(gate.await(30, java.util.concurrent.TimeUnit.SECONDS), "the test's own bound on its held du")
                return 0
            }
        }
        var now = 1_000_000_000L
        val jobs = wm.damage.core.windows.files.FilesJobs { now }
        val services = MenuServices()
        val w = wm.damage.core.windows.files.FilesWindow(FakeText(), wm.damage.core.windows.files.SlottedFiles(stalled, jobs), scope)
            .also { it.onRegistered(services) }
        tree.file("s/a.txt")
        val dir = tree.home.resolve("s").toString()
        assertTrue(services.onLoop { w.open("path:$dir/") })
        readerAwait("listed") { services.onLoop { w.summary().detail } == "0 folders · 1 files" }
        services.onLoop { w.view().let { v -> (v as WindowView.ListView).onCommit(v.rowCount() - 1) } }   // This folder
        val menu = services.menus.last()
        services.onLoop { menu.onCommit(menu.items.indexOfFirst { it.label == "Stats" }) }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            now += wm.damage.core.windows.files.FilesJobs.READ_ONLY_MS
            jobs.supervise()
            readerAwait("the glasses show the supervisor's words") {
                services.onLoop { w.title() } == "ended by the supervisor: no progress for 10 min"
            }
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun theGlassesSortRowReachesThePcRecord_andACutPastedThereIsClearedByThePcsCore(): Unit = runBlocking {
        val a = arrange()
        val src = tree.file("p/a.txt")
        val dest = tree.dir("p/into")
        val dir = tree.home.resolve("p").toString()
        readerAwait("the lane is up") { a.wc.client?.contractState?.shared(FilesCore.WINDOW) == true }
        assertTrue(a.services.onLoop { a.g.open("path:$dir/") })
        await(a, "the folder is listed") { a.g.summary().detail == "1 folders · 1 files" }
        // the This-folder row's menu, Sort: the glasses' own path
        a.services.onLoop { a.g.view().let { v -> (v as WindowView.ListView).onCommit(v.rowCount() - 1) } }
        val menu = a.services.menus.last()
        a.services.onLoop { menu.onCommit(menu.items.indexOfFirst { it.label == "Sort" }) }
        readerAwait("the glasses' sort reached the PC record") { item(a.h, FilesCore.SORT)?.value == JsonPrimitive("size") }
        assertEquals("phone", item(a.h, FilesCore.SORT)!!.by)
        // Cut a.txt on the glasses (its menu's Cut), then Paste here into the folder `into` (its menu)
        val rows = { a.services.onLoop { (a.g.view() as WindowView.ListView) } }
        a.services.onLoop { rows().onCommit(1) }                       // a.txt (after the folder `into`)
        val fileMenu = a.services.menus.last()
        a.services.onLoop { fileMenu.onCommit(fileMenu.items.indexOfFirst { it.label == "Cut" }) }
        readerAwait("the cut reached the PC record") { FilesCore.clipValue(item(a.h, FilesCore.CLIP)?.value) == ("cut" to src) }
        a.services.onLoop { rows().onCommit(0) }                       // the folder `into`
        val dirMenu = a.services.menus.last()
        a.services.onLoop { dirMenu.onCommit(dirMenu.items.indexOfFirst { it.label == "Paste here" }) }
        readerAwait("moved on the PC") { java.nio.file.Files.exists(tree.home.resolve("p/into/a.txt")) }
        readerAwait("the PC's core cleared the clipboard") { item(a.h, FilesCore.CLIP)?.value == JsonNull }
        assertEquals(FilesPc.DEVICE, item(a.h, FilesCore.CLIP)!!.by)
        readerAwait("the glasses' title lost its cut cell") { !a.services.onLoop { a.g.title() }.contains("cut:") }
        assertTrue(dest.isNotEmpty())
    }
}
