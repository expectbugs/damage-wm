package wm.damage.core

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
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
import wm.damage.core.windows.torrents.SessionStats
import wm.damage.core.windows.torrents.Snapshot
import wm.damage.core.windows.torrents.TorrentsCore

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q4 (the spec's §3.1, §3.5; `CONTRACT.md` §7.2 item 1, §12): Torrents' core on a real PC record —
 * its notices raised once, on the PC, into the notice record (the provider's ring replayed at the core's attach, each
 * event at most once, the app's Notify row read at the raise), its registry line (the keys leave the sync channel; the
 * program actions and their levels), and the summary Main's row and the web card share. A scripted provider
 * (`TestTorrents`) and a record in a temp directory; nothing reaches qBittorrent or the tracker.
 */
class TorrentsCoreTest {
    private val dir = Files.createTempDirectory("damage-torrents-core")
    private val host = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-torrents-core")).also { it.open() }

    @AfterTest
    fun tearDown() {
        host.close()
        host.executor.shutdown()
        dir.toFile().deleteRecursively()
    }

    private fun notice(id: String): NoticeRecord? {
        repeat(4) { host.executor.call {} }           // the raise, its answer and the record's publish
        return host.read(Notices.WINDOW).third.items["rec.notices.$id"]?.let { Notices.parse(id, it.value) }
    }

    /** Pin (the spec's Q4: "events before the core listened are lost"): the provider's first poll runs in its own
     *  init, so its events can be in the ring before the core attaches. */
    @Test
    fun eventsBeforeTheCoreListenedAreRaisedOnce_andAnEventSeenTwiceIsRaisedOnce() {
        val p = TestTorrents()
        p.event("done", "c0ffee02", "Another made-up release", deliver = false)    // the first poll, before the attach
        val core = TorrentsCore()
        core.attachNotices(p, p.epoch, NoticeCore(host))
        val r = notice("torrent:c0ffee02")
        assertEquals("done · Another made-up release", r?.body, "the event the ring held was raised at the attach")
        assertEquals("torrents", r?.app)
        assertEquals("t:c0ffee02", r?.target)
        assertEquals("Torrents", r?.gatedBy)
        assertEquals(Notices.RAISED, r?.state)
        assertEquals("1", r?.occ)
        // the same event handed again (a listener and the replay meeting) raises nothing more
        val again = p.eventsSince(0, p.epoch).single()
        for (l in p.listeners) l.event(again)
        assertEquals("1", notice("torrent:c0ffee02")?.occ, "one occurrence")
        // an error later, through the listener: the second notice
        p.event("error", "c0ffee03", "A made-up stopped one")
        assertEquals("error · A made-up stopped one", notice("torrent:c0ffee03")?.body)
        // added / removed are no notices
        p.event("added", "c0ffee09", "a made-up add")
        assertNull(notice("torrent:c0ffee09"))
        core.detachNotices()
        assertTrue(p.listeners.isEmpty())
    }

    @Test
    fun theNotifyRowsAreReadAtTheRaise() {
        val p = TestTorrents()
        TorrentsCore().attachNotices(p, p.epoch, NoticeCore(host))
        // Notify · errors set off in a browser
        val got = ArrayList<Answer>()
        host.act(Op("web-00aa01", OpId.Queued("s1", 1), TorrentsCore.WINDOW, TorrentsCore.SET, listOf(TorrentsCore.NOTIFY_ERRORS),
            JsonPrimitive(false), at = System.currentTimeMillis())) { got += it }
        host.executor.call {}
        assertTrue(got.single() is Answer.Seen, "$got")
        p.event("error", "c0ffee03", "A made-up stopped one")
        p.event("done", "c0ffee01", "A made-up download (1080p)")
        assertNull(notice("torrent:c0ffee03"), "Notify · errors is off: no error notice")
        assertEquals("done · A made-up download (1080p)", notice("torrent:c0ffee01")?.body, "Notify · done is on (absent = on)")
    }

    /** Pin (the spec's Q4: "the torrents keys still sync"), with the registry line's other facts. */
    @Test
    fun theRegistryLineTakesTheKeysOffTheSyncChannel_andNamesTheProgramActionsAndLevels() {
        assertFalse(Persistence.syncable("window.torrents"), "Torrents' blob is the glasses' local row now")
        // the merge with Q5: Files is converted too (FilesCoreTest pins `window.files` off the channel); Music is not
        assertTrue(Persistence.syncable("window.music"), "an unconverted window still syncs")
        assertEquals(1, Windows.VERSIONS["torrents"], "contract version 1 (S1-2 R0 §2)")
        for (a in listOf("start", "stop", "recheck", "delete", "deleteFiles", "add", "addStopped", "openOnPc"))
            assertTrue(Windows.isProgram("torrents", a), "$a goes into an outside program")
        for (a in listOf("recent", "set", "migrate")) assertFalse(Windows.isProgram("torrents", a), "$a is a record op")
        assertEquals(Levels.CONTROL, Windows.LEVELS.need("torrents", "delete"))
        assertEquals(Levels.FULL, Windows.LEVELS.need("torrents", "deleteFiles"))
        assertEquals(Levels.FULL, Windows.LEVELS.need("torrents", "recent"), "only a full device can search")
        assertEquals(Levels.CONTROL, Windows.LEVELS.need("torrents", "set"))
        assertEquals(Levels.VIEW, Windows.minLevel("torrents"))
    }

    @Test
    fun theSummaryIsTheGlassesMainRow() {
        val p = TestTorrents()
        val s = TorrentsCore.summary(p.snap(), "")
        assertEquals("1 downloading · 1.2 MB/s", s.line)
        assertEquals("1 seeding · 1 stopped · 1 under a week", s.detail)
        assertEquals(0.47, s.progress)
        assertTrue(s.more)
        assertEquals(TorrentsCore.Sum("qBittorrent unreachable 12s", "1 seeding · 1 stopped · 1 under a week"),
            TorrentsCore.summary(p.snap(), "qBittorrent unreachable 12s"), "the state line replaces line 1, nothing else shown")
        assertEquals(TorrentsCore.Sum("connecting", ""), TorrentsCore.summary(null, ""))
        val idle = Snapshot(1, 1, 0, emptyList(), SessionStats(), 0)
        assertEquals("idle", TorrentsCore.summary(idle, "").line)
    }
}
