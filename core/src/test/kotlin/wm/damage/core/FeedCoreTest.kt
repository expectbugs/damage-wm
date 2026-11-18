package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.Levels
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.NoticeRecord
import wm.damage.core.app.Notices
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Windows
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.feed.FeedCore
import wm.damage.core.windows.feed.ScriptedFeed

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, pieces B1 and B2 (`CONTRACT.md` §7.2, §12): Feed's core on a real PC record — its notices raised once, on
 * the PC: a first sight writes `seen` (device `pc`) and raises nothing, a new item raises one notice while its Notify
 * row is on and none while it is absent (off); the record's Fetch and Keep reach the engine; the registry line takes
 * Feed's keys off the sync channel. A scripted feed and a record in a temp directory; no site is reached.
 */
class FeedCoreTest {
    private val dir = Files.createTempDirectory("damage-feed-core")
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-feed-core")).also { it.open() }
    private var n = 0L

    @AfterTest
    fun tearDown() {
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    private fun drain() = repeat(6) { host.executor.call {} }

    private fun notice(id: String): NoticeRecord? {
        drain()
        return host.read(Notices.WINDOW).third.items["rec.notices.$id"]?.let { Notices.parse(id, it.value) }
    }

    private fun item(name: String) = host.read(FeedCore.WINDOW).third.items["rec.feed.$name"]

    /** Another device sets a row on the PC record (a browser's `set`). */
    private fun set(row: String, v: JsonPrimitive) {
        val got = ArrayList<Answer>()
        host.act(Op("web-00feed", OpId.Queued("w1", ++n), FeedCore.WINDOW, FeedCore.A_SET, listOf(row), v, at = System.currentTimeMillis())) { got += it }
        host.executor.call {}
        assertTrue(got.single() is Answer.Seen, "$got")
    }

    @Test
    fun aFirstSightRaisesNothing_aNewItemOneNoticeWhileItsRowIsOn_noneWhileItIsAbsent() {
        val feed = ScriptedFeed(nowMs = 1_763_000_000_000L)
        val core = FeedCore(feed)
        core.startOnPc(host, NoticeCore(host)) { _, _ -> }
        // a first sight: `seen` written as the PC's, nothing raised
        val first = feed.fireNew("popular", "The first one")
        drain()
        val seen = item("seen.popular")
        assertEquals(JsonPrimitive(first.publishedMs), seen?.value, "the first sight's baseline")
        assertEquals(Notices.PC, seen?.by)
        assertNull(notice("feed:popular"), "a first sight is a baseline, never a burst of notices")
        // the row absent (= off, FEED.md verdict 13): a new item raises nothing
        feed.fireNew("popular", "A quiet one")
        assertNull(notice("feed:popular"))
        // the row on: the unread items newer than the floor, one notice (the quiet one was never announced)
        set(FeedCore.NOTIFY_REDDIT, JsonPrimitive(true))
        feed.fireNew("popular", "A loud one")
        val r = notice("feed:popular")
        assertEquals("2 new · popular · A loud one", r?.body)
        assertEquals("feed", r?.app)
        assertEquals("src:popular", r?.target)
        assertEquals("Feed", r?.gatedBy)
        assertEquals("1", r?.occ)
        // the next new item alone: one notice naming it, the item's target
        val next = feed.fireNew("popular", "The next one")
        val r2 = notice("feed:popular")
        assertEquals("popular · The next one", r2?.body)
        assertEquals("item:popular:${next.id}", r2?.target)
        assertEquals("2", r2?.occ)
        // S1-3 fix B1: a subreddit browsed once (on a page) and not pinned raises nothing, however much it gets
        val browsed = feed.browse(wm.damage.core.windows.feed.SourceKind.REDDIT, "made-up")
        feed.fireNew(browsed.id, "A browsed one")
        feed.fireNew(browsed.id, "Another browsed one")
        assertNull(notice("feed:${browsed.id}"), "a browsed source that is not pinned is quiet")
        // pinned, it is one of his sources: its first sight is the baseline, its next new item a notice
        set(FeedCore.PIN + browsed.id, JsonPrimitive(true))
        feed.fireNew(browsed.id, "A pinned one")
        assertNull(notice("feed:${browsed.id}"), "its first sight")
        feed.fireNew(browsed.id, "The next pinned one")
        assertEquals("r/made-up · The next pinned one", notice("feed:${browsed.id}")?.body)
        core.stop()
    }

    @Test
    fun theRecordsFetchAndKeepReachTheEngine_andTheRegistryLineTakesFeedOffTheSyncChannel() {
        val feed = ScriptedFeed()
        val core = FeedCore(feed)
        val told = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Long>>()
        core.startOnPc(host, NoticeCore(host)) { f, k -> told += f to k }
        assertEquals(listOf(15 * 60_000L to 30 * 86_400_000L), told.toList(), "the defaults at the start")
        set(FeedCore.FETCH, JsonPrimitive(60))
        drain()
        assertEquals(60 * 60_000L to 30 * 86_400_000L, told.last())
        set(FeedCore.KEEP, JsonPrimitive(7))
        drain()
        assertEquals(60 * 60_000L to 7 * 86_400_000L, told.last())
        core.stop()
        // the registry: converted (its keys never sync), its program ops and levels (C§4.2)
        assertTrue(Windows.converted(FeedCore.WINDOW))
        assertTrue(!Persistence.syncable("window.feed.src.popular") && !Persistence.syncable("window.feed"))
        assertTrue(Windows.isProgram(FeedCore.WINDOW, FeedCore.A_REFRESH) && Windows.isProgram(FeedCore.WINDOW, FeedCore.A_BROWSE))
        assertEquals(Levels.VIEW, Windows.minLevel(FeedCore.WINDOW))
        assertEquals(Levels.VIEW, FeedCore.levelOf(FeedCore.A_READ))
        assertEquals(Levels.CONTROL, FeedCore.levelOf(FeedCore.A_FLAG))
        assertEquals(Levels.FULL, FeedCore.levelOf(FeedCore.A_BROWSE))
    }
}
