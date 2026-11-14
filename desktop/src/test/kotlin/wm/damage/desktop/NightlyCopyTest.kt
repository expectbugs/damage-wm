package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import wm.damage.core.bastion.AlertState
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionWatcher

/**
 * Build W, package B: the nightly copy of the PC's state files (R6 T17 as amended by the lead's decision A.2).
 * Temp folders only. Pinned: ONLY the allowlist is copied — no journal, `phone-journal/`, `trash/`, `config.json`,
 * cache (the music cache configured inside the data directory under another name included), icon, cookie, lock or
 * APK; the feed from a configured `feedDataDir` and the rules from a configured `haRulesFile`; a missing folder is
 * never created or written (W19); only `damage-YYYYMMDD-HHMMSS` folders are deleted, oldest first, and a foreign
 * `damage-notes` never is (W18); a `.partial` is not counted as a copy; an unchanged file is linked, a changed one
 * copied; a failed run touches no older copy and raises `watcher.copy`, which the next good copy clears; the daily
 * slot and the one catch-up.
 */
class NightlyCopyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = AtomicLong(LocalDateTime.of(2026, 11, 13, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class World(root: Path) {
        val data: Path = root.resolve("data")
        val feed: Path = root.resolve("elsewhere/feed")
        val rules: Path = root.resolve("conf/my-rules.json")
        val backup: Path = root.resolve("backup")

        fun write(p: Path, text: String) {
            Files.createDirectories(p.parent)
            Files.writeString(p, text)
        }

        init {
            for (n in listOf("state.json", "state.json.bad", "torrents.json", "uncaught-pending.txt",
                    // never copied
                    "journal.jsonl", "journal-20261101-000000.jsonl", "config.json", "tl-cookies.json", "instance.lock",
                    "damage-wm.apk", "state.json.tmp", "damage.log")) write(data.resolve(n), "content of $n")
            for (n in listOf("phone-journal/journal.jsonl", "trash/manifest.json", "media-cache/a.ogg",
                    "tunes/b.mp3", "icons/i.png", "feed/sources/default.json")) write(data.resolve(n), "content of $n")
            for (n in listOf("sources/s.json", "binge/b.json", "articles/a.json", "comments/c.json", "images/i.bin",
                    "strips/s.dstr")) write(feed.resolve(n), "content of $n")
            write(rules, "{\"rules\":[]}")
        }
    }

    private fun copy(w: World, store: AlertStore, keep: Int = 14) = NightlyCopy(w.data, w.backup, keep, LocalTime.of(3, 30),
        w.rules, w.feed, store, scope, clock::get, ZoneOffset.UTC)

    private fun filesIn(dir: Path): Set<String> = Files.walk(dir).use { st ->
        st.filter { Files.isRegularFile(it) }.map { dir.relativize(it).toString() }.toList().toSet()
    }

    /** The allowlist and nothing else (dec. A.2); the feed and the rules from their configured places. */
    @Test
    fun onlyTheStateFilesAreCopied() {
        val w = World(Files.createTempDirectory("copy-allow"))
        Files.createDirectories(w.backup)
        val store = AlertStore(w.data.resolve("alerts.json"))
        val c = copy(w, store)
        assertTrue(c.copyNow("test"))
        val made = c.copies().single()
        assertEquals(setOf("state.json", "state.json.bad", "torrents.json", "alerts.json", "uncaught-pending.txt",
            "bastion-rules.json", "feed/sources/s.json", "feed/binge/b.json"), filesIn(made),
            "the copy holds the allowlist only: no journal, phone-journal, trash, config, cache, icon, cookie, lock or APK")
        assertEquals("{\"rules\":[]}", Files.readString(made.resolve("bastion-rules.json")), "the configured rules file")
        assertEquals("content of sources/s.json", Files.readString(made.resolve("feed/sources/s.json")),
            "the feed from the configured feedDataDir, not <dataDir>/feed")
    }

    /** Fix round 1 (review 1, finding 5): a writer's temp file the walk listed is moved into place before its copy;
     *  it is skipped and named in the run's summary, the copy goes on and nothing is raised. */
    @Test
    fun aFileGoneBeforeItsCopyIsSkippedAndSaid() {
        val w = World(Files.createTempDirectory("copy-vanish"))
        Files.createDirectories(w.backup)
        val tmp = w.feed.resolve("sources/s.json.tmp")
        w.write(tmp, "half written")
        val store = AlertStore(w.data.resolve("alerts.json"))
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sink = wm.damage.core.util.Log.Sink { _, _, m -> lines += m }
        wm.damage.core.util.Log.addSink(sink)
        try {
            val c = copy(w, store)
            c.beforeCopying = { src ->
                if (src == w.feed.resolve("sources")) Files.move(tmp, w.feed.resolve("sources/s.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
            assertTrue(c.copyNow("test"), "the copy goes on: $lines")
            val made = c.copies().single()
            assertEquals("half written", Files.readString(made.resolve("feed/sources/s.json")), "the file moved into place")
            assertFalse(Files.exists(made.resolve("feed/sources/s.json.tmp")), "the gone one is not in the copy")
            assertTrue(lines.any { "nightly copy (test)" in it && "gone before their copy and skipped: feed/sources/s.json.tmp" in it },
                "the run's summary names it: $lines")
            assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.COPY), "no alert for a file that moved")
        } finally {
            wm.damage.core.util.Log.removeSink(sink)
        }
    }

    /** W19: a missing folder (an unmounted disk) is never created or written; the failure is raised, and the next
     *  good copy clears it. */
    @Test
    fun aMissingFolderIsNeverCreated() {
        val w = World(Files.createTempDirectory("copy-missing"))
        val store = AlertStore(w.data.resolve("alerts.json"))
        val c = copy(w, store)
        assertFalse(c.copyNow("test"))
        assertFalse(Files.exists(w.backup), "the configured folder is never created")
        val alert = store.table().single { it.id == BastionWatcher.COPY }
        assertTrue("${w.backup}" in alert.text && "never created" in alert.text && alert.builtIn, alert.text)
        Files.createDirectories(w.backup)
        assertTrue(c.copyNow("test"))
        assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.COPY), "the next good copy clears the alert")
    }

    /** W18: keep N deletes only `damage-YYYYMMDD-HHMMSS` folders, oldest first, and stale `.partial` ones; a foreign
     *  folder or file is never touched, and a `.partial` is not a copy. An unchanged file is linked, a changed one
     *  copied. */
    @Test
    fun keepDeletesOnlyByNameOldestFirstAndLinksWhatIsUnchanged() {
        val w = World(Files.createTempDirectory("copy-keep"))
        for (n in listOf("damage-20250101-000000", "damage-20250102-000000", "damage-20250103-000000",
                "damage-notes", "damage-20250104-000000.partial", "damage-2025-old")) Files.createDirectories(w.backup.resolve(n))
        w.write(w.backup.resolve("notes.txt"), "his own file")
        w.write(w.backup.resolve("damage-notes/keep.txt"), "his own folder")
        val store = AlertStore(w.data.resolve("alerts.json"))
        val c = copy(w, store, keep = 3)
        assertEquals(3, c.copies().size, "a .partial and foreign names are not copies")
        assertTrue(c.copyNow("test"))
        val first = c.copies().last()
        clock.addAndGet(1_000)
        // torrents.json changes between the copies; state.json does not
        Files.writeString(w.data.resolve("torrents.json"), "changed")
        Files.setLastModifiedTime(w.data.resolve("torrents.json"), FileTime.fromMillis(clock.get()))
        assertTrue(c.copyNow("test"))
        val second = c.copies().last()
        assertEquals(listOf("damage-20250103-000000", first.fileName.toString(), second.fileName.toString()),
            c.copies().map { it.fileName.toString() }, "keep 3: the oldest copies deleted first")
        assertTrue(Files.isSameFile(first.resolve("state.json"), second.resolve("state.json")), "an unchanged file is linked")
        assertFalse(Files.isSameFile(first.resolve("torrents.json"), second.resolve("torrents.json")), "a changed one is copied")
        assertEquals("changed", Files.readString(second.resolve("torrents.json")))
        assertTrue(w.backup.resolve("damage-notes").isDirectory() && Files.exists(w.backup.resolve("damage-notes/keep.txt")),
            "a foreign damage-notes folder is never deleted")
        assertTrue(Files.exists(w.backup.resolve("notes.txt")) && w.backup.resolve("damage-2025-old").isDirectory(),
            "nothing outside the pattern is touched")
        assertFalse(Files.exists(w.backup.resolve("damage-20250104-000000.partial")), "a stale .partial is removed")
    }

    /** A failed run touches no older copy; its `.partial` is removed after the next good copy. */
    @Test
    fun aFailureTouchesNoOlderCopy() {
        val w = World(Files.createTempDirectory("copy-fail"))
        Files.createDirectories(w.backup)
        val store = AlertStore(w.data.resolve("alerts.json"))
        val c = copy(w, store)
        assertTrue(c.copyNow("test"))
        val older = c.copies().single()
        val before = filesIn(older).associateWith { Files.readString(older.resolve(it)) }
        clock.addAndGet(1_000)
        val locked = w.data.resolve("torrents.json")
        Files.writeString(locked, "changed, then unreadable")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        try {
            assertFalse(c.copyNow("test"), "an unreadable source fails the run")
            assertEquals(AlertState.RAISED, store.stateOf(BastionWatcher.COPY))
            assertEquals(listOf(older), c.copies(), "no new copy counts")
            assertEquals(before, filesIn(older).associateWith { Files.readString(older.resolve(it)) }, "the older copy is untouched")
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"))
        }
        clock.addAndGet(1_000)
        assertTrue(c.copyNow("test"))
        assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.COPY))
        Files.list(w.backup).use { st ->
            assertTrue(st.toList().none { NightlyCopy.PARTIAL.matches(it.fileName.toString()) }, "the failed run's .partial is gone")
        }
    }

    /** The daily slot at `backupAt` (local wall clock) and the one catch-up 10 min after a start. */
    @Test
    fun theDailySlotAndOneCatchUp() {
        val w = World(Files.createTempDirectory("copy-when"))
        Files.createDirectories(w.backup)
        val store = AlertStore(w.data.resolve("alerts.json"))
        fun at(d: Int, h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 11, d, h, m, s).toInstant(ZoneOffset.UTC).toEpochMilli()
        val c = copy(w, store)                               // started 2026-11-13 12:00, no copy yet
        assertNull(c.due(at(13, 12, 1)), "no slot has come and no catch-up was planned")
        assertNull(c.due(at(14, 3, 29, 59)))
        assertEquals("daily", c.due(at(14, 3, 30)))
        assertNull(c.due(at(14, 3, 31)), "a slot is taken once")
        assertEquals("daily", c.due(at(16, 4, 0)), "a clock that jumped past two slots copies once")
        assertNull(c.due(at(16, 4, 1)))

        clock.set(at(13, 12, 0))
        val fresh = copy(w, store)
        fresh.planCatchUp()                                  // no copy in the folder: one catch-up
        assertNull(fresh.due(at(13, 12, 9, 59)))
        assertEquals("catch-up", fresh.due(at(13, 12, 10)))
        assertNull(fresh.due(at(13, 12, 11)), "one catch-up only")

        Files.createDirectories(w.backup.resolve("damage-20261112-110000"))      // 25 h old: no catch-up
        val recent = copy(w, store)
        recent.planCatchUp()
        assertNull(recent.due(at(13, 12, 10)))
        Files.delete(w.backup.resolve("damage-20261112-110000"))
        Files.createDirectories(w.backup.resolve("damage-20261112-090000"))      // 27 h old: a catch-up
        val stale = copy(w, store)
        stale.planCatchUp()
        assertEquals("catch-up", stale.due(at(13, 12, 10)))
    }
}
