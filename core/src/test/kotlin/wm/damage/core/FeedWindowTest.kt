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
 * confirm; the identity cursor under an inserting refresh; the persistence
 * round-trip; the deep links; MAIN presents the root and the switcher
 * resumes. Build S1-3 B6: the read marks, flags, recents and the archive's
 * place are the record's (read through the window's source); the notices
 * are the PC core's (`FeedCoreTest`); two pins for S1-3 J §6's defects —
 * a restart brings the open article back at its place, and leaving the
 * archive while a page loads does not stop it loading more.
 */
class FeedWindowTest {

    private object Probe : wm.damage.core.shell.ShellServices {
        override fun requestRender(window: wm.damage.core.shell.DamageWindow) {}
        override fun setOperation(op: String) {}
        override fun notifyInternal(source: String, body: String, urgent: Boolean, appId: String?, thread: String, target: String?, answer: Boolean) {}
        override fun openWindow(id: String, target: String?): Boolean = false
        override fun runOnShell(action: () -> Unit) = action()
        override fun docContentWidth(): Int = 596
        override fun docContentHeight(): Int = 416
    }

    private var dump: () -> String = { "" }

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        waitUntil(ms, 20, { "did not converge: $what [${dump()}]" }) { cond() }
    }

    private class Rig(tmp: Path, val feed: ScriptedFeed = ScriptedFeed(), provider: FeedProvider = feed,
                      record: wm.damage.core.app.MemoryRecord? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val win = if (record == null) FeedWindow(FakeText(), provider, scope) else FeedCore().let { core ->
            FeedGlasses(FakeText(), provider, scope, core, wm.damage.core.app.LocalWindowSource(core, record))
        }
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
    /** B6: the record as the window's source holds it. */
    private fun readOf(r: Rig, src: String) = FeedCore.readOf(r.win.source.state(), src)
    private fun rec(r: Rig, name: String) = r.win.source.state().itemNamed(name)?.takeIf { !it.gone }?.value

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
            awaitTrue("read on open, in the record") { firstId in readOf(r, "popular") }
            r.tap()                                            // the document's tap = actions
            awaitTrue("actions") { r.win.levelDepth() == 4 }
            r.tap()                                            // Comments (row 0, enabled for Reddit)
            awaitTrue("comments") { r.win.title() == "comments" && r.feed.ops.any { it == "comments:$firstId" } }
            assertEquals(5, r.win.levelDepth())
            r.back()
            awaitTrue("back to actions") { r.win.levelDepth() == 4 }
            r.down()                                           // Flag
            r.tap()
            awaitTrue("flagged and back on the article") { r.win.levelDepth() == 3 && FeedCore.flags(r.win.source.state()).containsKey(firstId) }
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
            // the strip and its alt text do not fit above the bar: one notch UP from the top
            // wraps onto the bar; 3296 is the latest, so it lands on prev
            awaitTrue("panning first") { r.win.comicFocusLabel() == null }
            r.up()
            awaitTrue("the bar under focus, on prev at the latest strip") { r.win.comicFocusLabel() == "prev" }
            r.tap()                                            // a tap flips
            awaitTrue("the previous strip opens") { r.win.title() == "xkcd 3295" && r.feed.ops.any { it == "comicAt:xkcd:3295" } }
            r.up()
            awaitTrue("now next is where the bar opens") { r.win.comicFocusLabel() == "next" }
            r.down(5)                                          // next → prev → random → first → latest → menu
            awaitTrue("menu under focus") { r.win.comicFocusLabel() == "menu" }
            r.tap()
            awaitTrue("comic actions") { r.win.levelDepth() == 4 }
            // the cursor rests on the first row that can act — Flag, since a comic has no comments
            awaitTrue("the cursor rests past the dim Comments row") { s(r, "actCursor") == "1" }
            r.back(); awaitTrue("back on the strip") { r.win.title() == "xkcd 3295" }
            // the focus stays where it was (menu); first goes to strip 1 through the numbered archive
            awaitTrue("still on menu") { r.win.comicFocusLabel() == "menu" }
            r.up(2)                                            // menu → latest → first
            awaitTrue("first under focus") { r.win.comicFocusLabel() == "first" }
            r.tap()
            awaitTrue("strip 1 opens") { r.win.title() == "xkcd 1" && r.feed.ops.any { it == "comicAt:xkcd:1" } }
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
            // the save point sends the archive's place to the record (a jump is an input)
            awaitTrue("the place in the record") { state(r); FeedCore.bingeOf(rec(r, "binge.8bt"))?.ep == 7 }
            r.back()
            awaitTrue("root") { r.win.title() == "feed" }
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
            awaitTrue("the recent in the record") { FeedCore.strings(rec(r, "recent.reddit"))?.firstOrNull() == "linux" }
            // Mark all read confirms first
            r.up()
            r.tap()
            menu(r, "Mark all read")
            awaitTrue("the confirm") { r.shell.menuIsOpen && r.shell.menuTitle!!.startsWith("Mark all") }
            r.back()                                           // cancel
            awaitTrue("cancelled") { !r.shell.menuIsOpen }
            assertTrue(readOf(r, "r:linux").isEmpty(), "nothing marked on cancel")
            r.tap(); menu(r, "Mark all read")
            r.down(); r.tap()
            awaitTrue("all read, one list in the record") { readOf(r, "r:linux").size == 8 }
            // leaving an unpinned browse forgets it
            r.back()
            awaitTrue("root") { r.win.title() == "feed" }
            awaitTrue("forgotten") { r.feed.ops.any { it == "forget:r:linux" } }
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }

    @Test
    fun theRecordAndTheViewRoundTrip(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-records")
        val record = wm.damage.core.app.MemoryRecord(FeedCore.ACTIONS)
        val r = Rig(tmp, record = record)
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            r.tap(); awaitTrue("popular") { r.win.title() == "popular" && s(r, "itemCursorId") != null }
            val a = s(r, "itemCursorId")!!
            r.tap(); awaitTrue("article") { r.win.title() == "article" }
            awaitTrue("read on open") { a in readOf(r, "popular") }
            // the round trip: a new window over the same record restores the same level and item
            r.tap(); awaitTrue("actions") { r.win.levelDepth() == 4 }
            val main = r.win.saveState()
            r.stop()
            val r2 = Rig(tmp, record = record)
            r2.win.restoreState(main)
            r2.shell.start()
            r2.win.onActivate(Probe, ActivationSource.RESTORE)
            awaitTrue("the item re-opens through its list") { r2.feed.ops.any { it == "article:$a" } }
            awaitTrue("the read mark survived (the record's)") { a in readOf(r2, "popular") }
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
            // on the shell's loop, as a deep link arrives (a test-thread call raced the list's answer for the re-open)
            suspend fun open(t: String): Boolean {
                val got = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
                r.shell.services.runOnShell { got.set(r.win.open(t)) }
                awaitTrue("open $t ran") { got.get() != null }
                return got.get()!!
            }
            assertTrue(open("src:slashdot"))
            awaitTrue("slashdot list") { r.win.title() == "slashdot" }
            val it = r.feed.items("xkcd", 2, 1).items[0]
            assertTrue(open("item:xkcd:${it.id}"))
            awaitTrue("the strip") { r.win.title() == "xkcd ${it.num}" }
            assertTrue(open("binge:8bt"))
            awaitTrue("the archive") { r.win.title().startsWith("8bt") }
            assertFalse(open("src:nope"))
            assertFalse(open("binge:popular"))
            assertFalse(open("garbage"))
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }

    /** Into the first item of `popular` (the article level); returns its id. */
    private suspend fun intoAnArticle(r: Rig): String {
        r.start()
        waitUntil(20_000, 20, { "root (${r.win.title()})" }) { r.win.title() == "feed" }
        r.tap(); waitUntil(20_000, 20, { "popular (${r.win.title()})" }) { r.win.title() == "popular" && s(r, "itemCursorId") != null }
        r.tap(); waitUntil(20_000, 20, { "the article (${r.win.title()})" }) { r.win.title() == "article" }
        return s(r, "openItemId")!!
    }

    /** P7 item 5 (OS-3), step 1: a session start on the same instance (every link end, Silent wake and rebuild)
     *  demoted an open article to its list and armed a re-open the instance did not need — the article level
     *  was gone from the next save as well. Kept now: same level, the held article, no second fetch. */
    @Test
    fun anOpenArticleSurvivesASessionRestartOfTheSameInstance(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-keep")
        val r = Rig(tmp)
        try {
            val id = intoAnArticle(r)
            r.shell.stop()
            r.shell.start()
            var seen = ""
            waitUntil(10_000, 20, { "the article level after the restart (is '$seen')" }) {
                seen = "${r.win.title()} depth ${r.win.levelDepth()}"; seen == "article depth 3" }
            assertEquals(1, r.feed.ops.count { it == "article:$id" }, "fetched once: ${r.feed.ops}")
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }

    /** P7 item 5 (OS-3), step 2: on a fresh process with Feed not the focused window, the record's article is
     *  armed for re-open; Main → Feed → tap the source then opened that article by itself (the arm was cleared
     *  only by its consumer). An explicit navigation clears it. */
    @Test
    fun aRestoredArticleDoesNotOpenItselfAfterAnExplicitNavigation(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-nav")
        val r = Rig(tmp)
        try {
            intoAnArticle(r)
            r.shell.services.runOnShell { r.shell.services.openWindow("settings", null) }
            waitUntil(20_000, 20, { "settings focused" }) { r.shell.currentWindowId() == "settings" }
        } finally { r.stop() }
        val r2 = Rig(tmp)                                          // a fresh process on the same store
        try {
            r2.shell.start()
            waitUntil(20_000, 20, { "settings restored (${r2.shell.currentWindowId()})" }) { r2.shell.currentWindowId() == "settings" && r2.shell.isQuiescent() }
            r2.back()                                              // Settings' root → Main
            waitUntil(20_000, 20, { "main (${r2.shell.currentWindowId()})" }) { r2.shell.currentWindowId() == null && r2.shell.isQuiescent() }
            r2.tap()                                               // Main's row 0 = Feed, a MAIN entry
            waitUntil(20_000, 20, { "feed's root (${r2.win.title()})" }) { r2.shell.currentWindowId() == "feed" && r2.win.title() == "feed" }
            r2.tap()                                               // the source the cursor kept: popular
            var depth = 0
            waitUntil(20_000, 20, { "the list landed (depth $depth, ${r2.win.title()})" }) {
                depth = r2.win.levelDepth(); depth >= 3 || (depth == 2 && s(r2, "itemCursorId") != null) }
            assertEquals(2, depth, "the list, not the record's article: ${r2.feed.ops}")
            assertTrue(r2.feed.ops.none { it.startsWith("article:") }, "no article fetched: ${r2.feed.ops}")
        } finally { r2.stop(); tmp.toFile().deleteRecursively() }
    }

    /** P7 item 5 (OS-3), step 3: a record that names an item this instance does not hold, while its list is
     *  loaded (a peer's record applied between sessions): the re-open waited for a list load that `onActivate`
     *  never asked for (it loads only an EMPTY list), so the window sat at the list. It reloads now. */
    @Test
    fun aRecordNamingAnotherItemReopensItAtTheSessionStart(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-other")
        val r = Rig(tmp)
        try {
            intoAnArticle(r)
            r.shell.stop()
            val other = r.feed.items("popular", 1, 1).items[0].id
            val rec = r.store.get("window.feed")!!
            r.store.put("window.feed", kotlinx.serialization.json.JsonObject(rec + ("openItemId" to kotlinx.serialization.json.JsonPrimitive(other))))
            r.shell.start()
            var seen = ""
            waitUntil(10_000, 20, { "the record's item opened (is '$seen'; ${r.feed.ops})" }) {
                seen = "${r.win.title()} depth ${r.win.levelDepth()}"; seen == "article depth 3" && r.feed.ops.contains("article:$other") }
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }

    /** Pin for S1-3 J §6 (1) (scenes 79a/79b): a restart brought the open article back near its top — the restored
     *  place was applied to the "loading" layout (title, byline, placeholder), clamped there and spent before the
     *  article's lines existed. Now the record's place waits for the article. */
    @Test
    fun aRestartBringsTheOpenArticleBackAtItsPlace(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-place")
        val record = wm.damage.core.app.MemoryRecord(FeedCore.ACTIONS)
        val r = Rig(tmp, record = record)
        val item16 = FeedIds.id("popular", "t3_p16")
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            r.tap(); awaitTrue("popular") { r.win.title() == "popular" && r.win.itemsLoaded() }
            r.down(16); awaitTrue("item 16 under the cursor") { s(r, "itemCursorId") == item16 }
            r.tap(); awaitTrue("the article") { r.win.title() == "article" && r.feed.ops.contains("article:$item16") && r.win.placeNow() != null }
            // notch by notch, each at rest, until the top is past the headline (the picture may lengthen the page late)
            awaitTrue("the article's picture asked") { r.feed.ops.any { it.startsWith("image:") } }
            var p1: FeedCore.Place? = null
            for (k in 0 until 8) {
                awaitTrue("at rest") { r.shell.isQuiescent() }
                p1 = r.win.placeNow()
                if (p1 != null && p1.b >= 1) break
                r.down(1)
            }
            assertTrue((p1?.b ?: 0) >= 1, "the page never left its headline: $p1")
            awaitTrue("the save point sent the place") { state(r); FeedCore.placeOf(rec(r, FeedCore.PLACE)) == p1 }
            r.stop()
            assertEquals(p1, FeedCore.placeOf(rec(r, FeedCore.PLACE)), "the stop's save sent no other place")
            val r2 = Rig(tmp, feed = ScriptedFeed(nowMs = r.feed.nowMs), record = record)
            try {
                r2.shell.start()
                awaitTrue("the article restored") { r2.win.title() == "article" && r2.feed.ops.contains("article:$item16") }
                awaitTrue("at its place (was ${p1}, is ${r2.win.placeNow()})") { r2.win.placeNow() == p1 }
            } finally { r2.stop() }
        } finally { tmp.toFile().deleteRecursively() }
    }

    /** A feed whose lists fail while [down] (the phone away: its channel's line set, every request refused). */
    private class Away(val real: ScriptedFeed) : FeedProvider by real {
        @Volatile var down = false
        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        override fun items(sourceId: String, offset: Int, limit: Int): ItemPage =
            if (down) { failed.incrementAndGet(); throw IllegalStateException("PC not answering") } else real.items(sourceId, offset, limit)
    }

    /** S1-3 fix B2: away from home the channel's line says it once ("PC unreachable since H:MM", in the title); a
     *  list that cannot load raises no notice of its own at each 5 s retry (each one a chrome flush for nothing). */
    @Test
    fun awayAListThatCannotLoadRaisesNoNoticeAtEachRetry(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-away")
        val feed = ScriptedFeed()
        val away = Away(feed)
        val r = Rig(tmp, feed = feed, provider = away)
        dump = { "title='${r.win.title()}' failed=${away.failed.get()}" }
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            feed.setState("PC unreachable since 14:02")
            away.down = true
            r.tap()                                            // popular: its list cannot load
            awaitTrue("the load failed") { away.failed.get() >= 1 }
            delay(300)
            assertFalse("PC not answering" in r.win.title(), "no notice while the line says it: '${r.win.title()}'")
            assertTrue(r.win.title().endsWith("· ! PC unreachable since 14:02"), r.win.title())
        } finally {
            r.stop()
            tmp.toFile().deleteRecursively()
        }
    }

    /** A feed whose archive's page 2 waits for [release] the first time it is asked (a slow site). */
    private class SlowPage(val real: ScriptedFeed) : FeedProvider by real {
        val asked2 = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = java.util.concurrent.CountDownLatch(1)
        override fun bingeEpisode(sourceId: String, num: Int, width: Int, levels: Int, mode: LineArt): EpisodePack {
            if (num == 2 && asked2.incrementAndGet() == 1) gate.await()
            return real.bingeEpisode(sourceId, num, width, levels, mode)
        }
    }

    /** Pin for S1-3 J §6 (2): leaving the archive while a page loaded left `bingeLoading` set (the late answer returns
     *  early on the sequence `back()` bumped), so the archive entered again never loaded another page until a restart. */
    @Test
    fun leavingTheArchiveWhileAPageLoadsDoesNotStopItLoadingMore(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-binge-stuck")
        val feed = ScriptedFeed()
        val slow = SlowPage(feed)
        val r = Rig(tmp, feed = feed, provider = slow)
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            r.down(4); r.tap()
            awaitTrue("the archive at page 1") { r.win.title() == "8bt 1" && feed.ops.contains("episode:8bt:1") }
            r.down(12)
            awaitTrue("page 2 asked, its answer held") { slow.asked2.get() == 1 }
            r.back()
            awaitTrue("root while page 2 loads") { r.win.title() == "feed" }
            r.tap()
            awaitTrue("the archive entered again") { r.win.title().startsWith("8bt") }
            slow.gate.countDown()                              // the late answer lands and is dropped (its sequence)
            r.down(2)
            awaitTrue("page 2 asked again (asked ${slow.asked2.get()})") { slow.asked2.get() >= 2 }
        } finally { slow.gate.countDown(); r.stop(); tmp.toFile().deleteRecursively() }
    }

    /** Pin (lane F, found by the snapshot's restart scenes): the presenter read the record before its archive fields
     *  existed (Kotlin runs initialisers in order), so a record that already held an archive's place — every restart —
     *  ended the build with a NullPointerException. The archive now opens where the record says. */
    @Test
    fun aPresenterBuiltOverARecordHoldingTheArchivesPlaceStartsThere(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-binge-rec")
        val record = wm.damage.core.app.MemoryRecord(FeedCore.ACTIONS)
        val other = wm.damage.core.app.LocalWindowSource(FeedCore(), record)
        // its strip made at the glasses' width (the shell's document column at full size); read at the entry, not
        // at the build, when the width is not known yet (the snapshot's 80b showed the page's top instead)
        other.act(FeedCore.bingeDraft("8bt", FeedCore.Binge(3, null, 2, 564), at = 1L))
        wm.damage.core.app.MemoryRecord.SHARED.call {}
        val r = Rig(tmp, record = record)
        try {
            r.start()
            awaitTrue("root") { r.win.title() == "feed" }
            r.down(4); r.tap()
            awaitTrue("the archive opens at the record's page and strip (${r.win.bingePlaceNow()})") {
                r.win.title() == "8bt 3" && r.feed.ops.contains("episode:8bt:3") && r.win.bingePlaceNow() == (3 to 2)
            }
        } finally { r.stop(); tmp.toFile().deleteRecursively() }
    }
}
