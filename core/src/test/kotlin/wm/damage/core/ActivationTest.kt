package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * The activation-source rule — Adam's general steer of 2026-09-04
 * (`HOLDEM.md` §3, verdict 35): **switcher = resume, Main = the window's root
 * list.** These are the shell-plumbing halves; each window's own root/resume
 * behaviour is pinned beside that window's other tests.
 */
class ActivationTest {

    /** A window with two levels that records every activation source it saw. */
    private class Probe(id: String) : DamageWindow(id, id.replaceFirstChar { it.uppercase() }, IconKind.FILES) {
        val seen = ArrayList<ActivationSource>()
        var level = 0
        val model = ListModel()
        var deepValue = 0        // per-item state: MAIN must not touch it

        override fun view(): WindowView = WindowView.ListView(model, { 2 },
            { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> },
            { level = 1 })

        override fun title(): String = if (level == 0) "root" else "deep"
        override fun summary() = Summary(title())
        override fun levelDepth(): Int = level + 1
        override fun back(): Boolean = if (level > 0) { level = 0; true } else false

        override fun onActivate(ctx: ShellServices, from: ActivationSource) {
            seen.add(from)
            if (from == ActivationSource.MAIN) level = 0        // the root, deep state kept
        }

        override fun open(target: String): Boolean {
            level = 1
            return true
        }

        override fun saveState(): JsonObject = buildJsonObject {
            put("level", level)
            put("deep", deepValue)
        }

        override fun restoreState(state: JsonObject) {
            level = state["level"]?.jsonPrimitive?.intOrNull ?: 0
            deepValue = state["deep"]?.jsonPrimitive?.intOrNull ?: 0
        }
    }

    private suspend fun settle(shell: Shell) {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 10_000) delay(10)
        assertTrue(shell.isQuiescent(), "shell did not settle")
    }

    @Test
    fun mainSaysMainSwitcherSaysSwitcherAndBootSaysRestore(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-activation")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = Persistence(tmp.resolve("state.json"))
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, store, null, scope)
            val a = Probe("proba")
            val b = Probe("probb")
            shell.register(a)
            shell.register(b)
            shell.start()
            settle(shell)

            // Main → probb (row 1) so both windows are in the recency list,
            // then back out to Main
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
            assertEquals("probb", shell.currentWindowId())
            assertEquals(listOf(ActivationSource.MAIN), b.seen)
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            settle(shell)

            // Main → proba (row 0)
            shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
            assertEquals("proba", shell.currentWindowId())
            assertEquals(listOf(ActivationSource.MAIN), a.seen)

            // go deep, then away through the switcher and back through it
            shell.postGesture(EvenHubMsg.EV_CLICK)          // the list commit → level 1
            settle(shell)
            a.deepValue = 42
            assertEquals("deep", a.title())

            // the switcher's cursor opens on the most recent INACTIVE (probb)
            shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
            assertEquals("probb", shell.currentWindowId())
            assertEquals(ActivationSource.SWITCHER, b.seen.last())

            // back to proba through the switcher: it RESUMES the deep level
            shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
            assertEquals("proba", shell.currentWindowId())
            assertEquals(listOf(ActivationSource.MAIN, ActivationSource.SWITCHER), a.seen)
            assertEquals("deep", a.title(), "the switcher must RESUME, not reset")

            // out to Main, then in again: the ROOT, with the item state kept
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)   // deep → root
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)   // root → Main
            settle(shell)
            assertEquals(null, shell.currentWindowId())
            // put it back deep without touching Main's entry path
            a.level = 1
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Main's cursor is still on proba (row 0)
            settle(shell)
            assertEquals("proba", shell.currentWindowId())
            assertEquals(ActivationSource.MAIN, a.seen.last())
            assertEquals("root", a.title(), "Main entry lands on the window's ROOT")
            assertEquals(42, a.deepValue, "Main entry must not discard per-item state")

            // a notice tap is a DEEP LINK, not a Main entry
            shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)   // out to Main
            settle(shell)
            shell.postNotice(wm.damage.core.shell.Notifications.Notice(
                source = "test", thread = "t", body = "ping", timeHHMM = "12:00",
                appId = "probb", target = "x"))
            settle(shell)
            val t0 = System.currentTimeMillis()
            while (!shell.notifications.focused && System.currentTimeMillis() - t0 < 10_000) delay(20)
            assertTrue(shell.notifications.focused, "the notice never took the gesture focus")
            shell.postGesture(EvenHubMsg.EV_CLICK)          // tap the box
            settle(shell)
            assertEquals("probb", shell.currentWindowId())
            assertEquals(ActivationSource.DEEP_LINK, b.seen.last())

            shell.stop()

            // a fresh shell over the same store: RESTORE, and it resumes
            val transport2 = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell2 = Shell(FakeText(), transport2, Persistence(tmp.resolve("state.json")), null, scope)
            val c = Probe("probb")
            shell2.register(Probe("proba"))
            shell2.register(c)
            shell2.start()
            settle(shell2)
            assertEquals(listOf(ActivationSource.RESTORE), c.seen)
            assertEquals("deep", c.title(), "boot restore resumes the stored level")
            shell2.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun mainEntryOnTheFocusedWindowStillGoesToItsRoot(): Unit = runBlocking {
        // openWindow() on the ALREADY-focused window short-circuits (§4.3): the
        // navigation Main means must still happen, or a hand-off back to the
        // window you are in would silently do nothing.
        val tmp = Files.createTempDirectory("damage-activation-self")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("s.json")), null, scope)
            val a = Probe("proba")
            shell.register(a)
            shell.start()
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Main → proba
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)          // → deep
            settle(shell)
            assertEquals("deep", a.title())
            a.onActivate(shellServicesProbe(), ActivationSource.MAIN)
            assertEquals("root", a.title())
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun readerFromMainShowsTheShelfAndKeepsThePagePosition(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-activation-reader")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val books = tmp.resolve("books")
            Files.createDirectories(books)
            Files.writeString(books.resolve("book.txt"), buildString {
                appendLine("CHAPTER I")
                appendLine()
                for (i in 1..200) { appendLine("Paragraph $i. The compositor batches damage."); appendLine() }
            })
            val content = wm.damage.core.content.LocalContent(books)
            val svc = shellServicesProbe()
            val reader = wm.damage.core.windows.reader.ReaderWindow(FakeText(), content, scope)
            reader.onRegistered(svc)
            reader.onActivate(svc, ActivationSource.SWITCHER)
            val t0 = System.currentTimeMillis()
            while (reader.title().startsWith("library") && reader.summary().line.contains("loading") &&
                System.currentTimeMillis() - t0 < 10_000) delay(20)
            // open the one book (the first-open chapter picker, then the page)
            (reader.view() as WindowView.ListView).onCommit(0)
            while (reader.title() == "opening" && System.currentTimeMillis() - t0 < 10_000) delay(20)
            if (reader.title().endsWith("chapters")) (reader.view() as WindowView.ListView).onCommit(0)
            while (!reader.title().contains("p.") && System.currentTimeMillis() - t0 < 10_000) delay(20)
            assertTrue(reader.title().contains("p."), "the book never opened: ${reader.title()}")
            val doc = reader.view() as WindowView.DocView
            doc.model.topLine = 40
            val id = content.library().single().id

            // MAIN: the shelf — and the reading position is kept, so tapping
            // the book again lands on the same line
            reader.onActivate(svc, ActivationSource.MAIN)
            assertTrue(reader.title().startsWith("library"), "Main entry must land on the shelf")
            val off = reader.saveSubState()["book.$id"]?.get("off")?.jsonPrimitive?.intOrNull
            assertTrue(off != null && off > 0, "the reading offset was discarded by a Main entry (off=$off)")

            // SWITCHER from there resumes the shelf (that IS where it is now)
            reader.onActivate(svc, ActivationSource.SWITCHER)
            assertTrue(reader.title().startsWith("library"))
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    private fun shellServicesProbe(): ShellServices = object : ShellServices {
        override fun requestRender(window: DamageWindow) {}
        override fun setOperation(op: String) {}
        override fun notifyInternal(source: String, body: String, urgent: Boolean,
            appId: String?, thread: String, target: String?) {}
        override fun openWindow(id: String, target: String?): Boolean = false
        override fun runOnShell(action: () -> Unit) = action()
        override fun docContentWidth(): Int = 560
        override fun docContentHeight(): Int = 384
    }
}
