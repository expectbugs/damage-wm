package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.windows.feed.*

/**
 * FEED (`FEED.md` §4): the window grammar over the scripted provider through
 * a real shell — the source list, an item list, an article and its actions,
 * comments, the flag and the Flagged list, a comic, the archive (next,
 * previous, jump), Browse through the keyboard, Mark all read behind its
 * confirm; the identity cursor under an inserting refresh; the notice
 * gated on its row; the §3.5 records (union of read marks, no empty blob);
 * the persistence round-trip; the deep links; MAIN presents the root and
 * the switcher resumes.
 */
class FeedWindowTest {

    private object Probe : wm.damage.core.shell.ShellServices {
        override fun requestRender(window: wm.damage.core.shell.DamageWindow) {}
        override fun setOperation(op: String) {}
        override fun notifyInternal(source: String, body: String, urgent: Boolean, appId: String?, thread: String, target: String?) {}
        override fun openWindow(id: String, target: String?): Boolean = false
        override fun runOnShell(action: () -> Unit) = action()
        override fun docContentWidth(): Int = 596
        override fun docContentHeight(): Int = 416
    }

    private var dump: () -> String = { "" }

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(20)
        assertTrue(cond(), "did not converge: $what [${dump()}]")
    }

    private class Rig(tmp: Path, val feed: ScriptedFeed = ScriptedFeed()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val win = FeedWindow(FakeText(), feed, scope)
        init { shell.register(win) }
        suspend fun start() { shell.start(); shell.postGesture(EvenHubMsg.EV_CLICK) }   // Main cursor 0 = Feed
        suspend fun stop() { shell.stop(); scope.cancel() }
        fun tap() = shell.postGesture(EvenHubMsg.EV_CLICK)
        fun back() = shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        fun down(n: Int = 1) = repeat(n) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
        fun up(n: Int = 1) = repeat(n) { shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }
    }

    /** Commit the open menu's row LABELLED [label] — by name, never by counting. */
    private suspend fun menu(r: Rig, label: String) {
        awaitTrue("a menu is open for '$label'") { r.shell.menuIsOpen }
        val i = r.shell.menuLabels.indexOfFirst { it == label || it.startsWith(label) }
        assertTrue(i >= 0, "menu row '$label' not in ${r.shell.menuLabels}")
        r.down((i - r.shell.menuCursor).mod(r.shell.menuLabels.size))
        r.tap()
    }

    private fun state(r: Rig): JsonObject = r.win.saveState()
    private fun s(r: Rig, k: String) = state(r)[k]?.jsonPrimitive?.contentOrNull

    @Test
    fun sourcesItemsArticleActionsCommentsAndFlag(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-win")
        val r = Rig(tmp)
        dump = { "title='${r.win.title()}' depth=${r.win.levelDepth()} menu=${r.shell.menuIsOpen}/${r.shell.menuLabels} state=${state(r)}" }
        try {
            r.start()
            awaitTrue("the source list") { r.win.title() == "feed" && r.win.summary().detail.contains("sources") || r.win.summary().line.contains("new") }
            assertEquals(1, r.win.levelDepth())
            r.tap()                                            // popular
            awaitTrue("popular opens") { r.win.title() == "popular" && s(r, "itemCursorId") != null }
            assertEquals(2, r.win.levelDepth())
            val firstId = s(r, "itemCursorId")!!
            val first = r.feed.item(firstId)!!
            assertEquals(ItemKind.IMAGE, first.kind)
            // one tap opens (the Reader grammar) and marks read on open
            r.tap()
            awaitTrue("the image post opens as an article") { r.win.title() == "article" }
            assertEquals(3, r.win.levelDepth())
            awaitTrue("the article arrived") { r.feed.ops.any { it == "article:$firstId" } }
            val sub = r.win.saveSubState()
            assertTrue(sub["src.popular"]!!["read"]!!.jsonArray.any { it.jsonPrimitive.content == firstId }, "read on open")
            r.tap()                                            // the document's tap = actions
            awaitTrue("actions") { r.win.levelDepth() == 4 }
            r.tap()                                            // Comments (row 0, enabled for Reddit)
            awaitTrue("comments") { r.win.title() == "comments" && r.feed.ops.any { it == "comments:$firstId" } }
            assertEquals(5, r.win.levelDepth())
            r.back()
            awaitTrue("back to actions") { r.win.levelDepth() == 4 }
            r.down()                                           // Flag
            r.tap()
            awaitTrue("flagged and back on the article") { r.win.levelDepth() == 3 && r.win.saveSubState()["src.popular"]!!["flags"]!!.jsonObject.containsKey(firstId) }
            r.back()
            awaitTrue("back to the list") { r.win.title() == "popular" }
            // the flagged list from the root menu
            r.back()
            awaitTrue("root") { r.win.title() == "feed" }
            r.up()                                             // wrap to the Feed menu row
            r.tap()
            menu(r, "Flagged")
            awaitTrue("the flagged list") { r.win.title() == "flagged" }
            r.tap()
            awaitTrue("a flagged item opens") { r.win.title() == "article" }
            r.back()
            awaitTrue("back returns to Flagged") { r.win.title() == "flagged" }
            r.back()
            awaitTrue("root again") { r.win.title() == "feed" }
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }

    @Test
    fun comicsAndTheArchive(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-comic")
        val r = Rig(tmp)
        dump = { "title='${r.win.title()}' depth=${r.win.levelDepth()} ops=${r.feed.ops.takeLast(4)} state=${state(r)}" }
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            r.down(2)                                          // popular → slashdot → xkcd
            r.tap()
            awaitTrue("xkcd list") { r.win.title() == "xkcd" && s(r, "itemCursorId") != null }
            r.tap()
            awaitTrue("the strip opens with its number in the title") { r.win.title() == "xkcd 3296" }
            // the strip is asked at the shell's document column (564 at full width), 16 levels, the auto policy
            awaitTrue("the strip was asked at the document column, 16 levels, auto") { r.feed.ops.any { it.startsWith("comic:") && it.endsWith(":${Probe.docContentWidth() - 32}:16:AUTO") } }
            r.tap()
            awaitTrue("comic actions") { r.win.levelDepth() == 4 }
            // the cursor rests on the first row that can act — Flag, since a
            // comic has no comments — so Next item is two notches down
            awaitTrue("the cursor rests past the dim Comments row") { s(r, "actCursor") == "1" }
            r.down(2)                                          // Flag → Mark read → Next item
            r.tap()
            awaitTrue("next strip opens") { r.win.title() == "xkcd 3295" }
            r.back(); awaitTrue("list") { r.win.title() == "xkcd" }
            r.back(); awaitTrue("root") { r.win.title() == "feed" }
            r.down(2)                                          // xkcd → smbc → 8bt
            r.tap()
            awaitTrue("the archive opens at page 1") { r.win.title() == "8bt 1" && r.feed.ops.any { it == "episode:8bt:1" } }
            assertEquals(2, r.win.levelDepth())
            // scrolling to the end demands the next page
            r.down(12)
            awaitTrue("page 2 was demanded") { r.feed.ops.any { it == "episode:8bt:2" } }
            r.tap()                                            // binge actions
            awaitTrue("binge actions") { r.win.levelDepth() == 3 }
            r.down(2)                                          // Next → Previous → Jump
            r.tap()
            awaitTrue("the keyboard asks for a number") { r.shell.keyboardIsOpen }
            r.transport.injectText("7")
            awaitTrue("page 7 opens") { r.win.title() == "8bt 7" && r.feed.ops.any { it == "episode:8bt:7" } }
            val pos = r.win.saveSubState()["binge.8bt"]!!
            assertEquals(7, pos["ep"]!!.jsonPrimitive.content.toInt())
            r.back()
            awaitTrue("root") { r.win.title() == "feed" }
            // the row says where the archive is
            assertTrue(r.win.saveSubState().containsKey("binge.8bt"))
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }

    @Test
    fun browseThroughTheKeyboardMarkAllReadAndTheIdentityCursor(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-browse")
        val r = Rig(tmp)
        dump = { "title='${r.win.title()}' depth=${r.win.levelDepth()} menu=${r.shell.menuLabels} ops=${r.feed.ops.takeLast(4)}" }
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            r.tap()
            awaitTrue("popular") { r.win.title() == "popular" && s(r, "itemCursorId") != null }
            r.down(3)
            val third = r.feed.items("popular", 3, 1).items[0].id
            awaitTrue("three notches down") { s(r, "itemCursorId") == third }
            val onId = third
            // a refresh inserts a row above: the cursor stays on its item
            val loadsBefore = r.feed.ops.count { it == "items:popular:0:50" }
            r.feed.fireNew("popular")
            awaitTrue("the list reloaded with the new row") { r.feed.ops.count { it == "items:popular:0:50" } > loadsBefore }
            delay(200)
            assertEquals(onId, s(r, "itemCursorId"), "the cursor follows its item, not its index")
            // the wrap-end menu: Browse a subreddit through the keyboard
            var guard = 0
            while (s(r, "itemMenu") != "true" && guard++ < 60) { r.up(); delay(30) }
            awaitTrue("the menu row") { s(r, "itemMenu") == "true" }
            r.tap()
            menu(r, "Browse a subreddit")
            awaitTrue("keyboard") { r.shell.keyboardIsOpen && r.shell.keyboardTitle == "subreddit" }
            r.transport.injectText("linux")
            awaitTrue("the browsed list opens") { r.win.title() == "r/linux" && r.feed.ops.any { it == "browse:REDDIT:linux" } }
            assertEquals("linux", state(r)["recentSubs"]!!.jsonArray[0].jsonPrimitive.content)
            // Mark all read confirms first
            r.up()
            r.tap()
            menu(r, "Mark all read")
            awaitTrue("the confirm") { r.shell.menuIsOpen && r.shell.menuTitle!!.startsWith("Mark all") }
            r.back()                                           // cancel
            awaitTrue("cancelled") { !r.shell.menuIsOpen }
            assertTrue(r.win.saveSubState()["src.r:linux"]?.get("read")?.jsonArray?.isEmpty() != false, "nothing marked on cancel")
            r.tap(); menu(r, "Mark all read")
            r.down(); r.tap()
            awaitTrue("all read") { (r.win.saveSubState()["src.r:linux"]?.get("read")?.jsonArray?.size ?: 0) == 8 }
            // leaving an unpinned browse forgets it
            r.back()
            awaitTrue("root") { r.win.title() == "feed" }
            awaitTrue("forgotten") { r.feed.ops.any { it == "forget:r:linux" } }
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }

    @Test
    fun noticesAreGatedOnTheRowAndCoalescePerSource(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-notify")
        val r = Rig(tmp)
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            r.tap(); awaitTrue("popular seen") { r.win.title() == "popular" && s(r, "itemCursorId") != null }
            r.back(); awaitTrue("root") { r.win.title() == "feed" }
            r.feed.fireNew("popular", "Quiet one")
            delay(300)
            assertFalse(r.shell.notifications.active, "off by default (verdict 13)")
            r.win.appSettings().first { it.name == "Notify · Reddit" }.apply("on")
            r.feed.fireNew("popular", "Loud one")
            awaitTrue("a notice for the new item") { r.shell.notifications.active }
            assertTrue(r.win.dirty)
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }

    @Test
    fun recordsUnionReadMarksNeverWriteEmptyBlobsAndRoundTrip(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-records")
        val r = Rig(tmp)
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            assertTrue(r.win.saveSubState().isEmpty(), "nothing read = no record (§25 #8)")
            r.tap(); awaitTrue("popular") { r.win.title() == "popular" && s(r, "itemCursorId") != null }
            val a = s(r, "itemCursorId")!!
            r.tap(); awaitTrue("article") { r.win.title() == "article" }
            // a peer read another item of the same source: the union keeps both
            val peer = r.feed.items("popular", 5, 1).items[0].id
            r.win.restoreSubState("src.popular", kotlinx.serialization.json.buildJsonObject {
                put("read", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(peer))))
                put("seen", kotlinx.serialization.json.JsonPrimitive(0L))
            })
            val read = r.win.saveSubState()["src.popular"]!!["read"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(a in read && peer in read, "union: $read")
            // the round trip: a new window restores the same level and item
            r.tap(); awaitTrue("actions") { r.win.levelDepth() == 4 }
            val main = r.win.saveState()
            val subs = r.win.saveSubState()
            r.stop()
            val r2 = Rig(tmp)
            r2.win.restoreState(main)
            for ((k, v) in subs) r2.win.restoreSubState(k, v)
            r2.shell.start()
            r2.win.onActivate(Probe, ActivationSource.RESTORE)
            awaitTrue("the item re-opens through its list") { r2.feed.ops.any { it == "article:$a" } }
            awaitTrue("the read mark survived") { r2.win.saveSubState()["src.popular"]!!["read"]!!.jsonArray.any { it.jsonPrimitive.content == a } }
            // MAIN presents the root; the switcher resumes
            r2.win.onActivate(Probe, ActivationSource.MAIN)
            assertEquals(1, r2.win.levelDepth())
            r2.stop()
        } finally { tmp.toFile().deleteRecursively() }
    }

    @Test
    fun deepLinksResolveEveryForm(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-links")
        val r = Rig(tmp)
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            r.win.onActivate(Probe, ActivationSource.DEEP_LINK)
            assertTrue(r.win.open("src:slashdot"))
            awaitTrue("slashdot list") { r.win.title() == "slashdot" }
            val it = r.feed.items("xkcd", 2, 1).items[0]
            assertTrue(r.win.open("item:xkcd:${it.id}"))
            awaitTrue("the strip") { r.win.title() == "xkcd ${it.num}" }
            assertTrue(r.win.open("binge:8bt"))
            awaitTrue("the archive") { r.win.title().startsWith("8bt") }
            assertFalse(r.win.open("src:nope"))
            assertFalse(r.win.open("binge:popular"))
            assertFalse(r.win.open("garbage"))
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }
}
