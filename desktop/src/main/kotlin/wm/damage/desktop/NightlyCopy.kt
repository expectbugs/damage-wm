package wm.damage.desktop

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wm.damage.core.bastion.AlertState
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionWatcher
import wm.damage.core.bastion.Severity
import wm.damage.core.util.Log
import wm.damage.core.util.UncaughtRecord

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. This is the PC's nightly copy of its own state files to a folder Adam names
 * (build W, R6 §6; the lead's decision A.2), so a lost disk or a bad write costs at most a day.
 *
 * **What is copied — these and nothing else** (dec. A.2): from the data directory `state.json`,
 * `state.json.bad`, `torrents.json`, `alerts.json` and `uncaught-pending.txt`; the rules file (at the
 * configured `haRulesFile`) as `bastion-rules.json`; the feed's `sources/` and `binge/` folders (the
 * feed directory resolved from `feedDataDir`, `FeedStore`) as `feed/sources/` and `feed/binge/`. A
 * source that does not exist is skipped and listed in the run's line. Journals, `phone-journal/`,
 * `trash/`, `config.json`, the caches, the cookies, the lock and the staged APK are never copied:
 * the list above is an allowlist, so nothing is excluded by name.
 *
 * **How** (R6 §6): into `<backupDir>/damage-YYYYMMDD-HHMMSS.partial/`, renamed to
 * `damage-YYYYMMDD-HHMMSS` when whole. A file with the previous copy's size and modification time
 * is hard-linked to it, else copied with its attributes; a failed link falls back to a copy, said
 * once per run. **The configured folder is never created**: a missing one refuses the copy (an
 * unmounted disk's empty mount point must not take the copy onto the root disk). The usable space
 * is checked first. After a good copy, folders named exactly `damage-YYYYMMDD-HHMMSS` beyond
 * `backupKeep` are deleted, oldest first, and stale `damage-YYYYMMDD-HHMMSS.partial` ones; nothing
 * else in the folder is ever touched.
 *
 * **When:** wall-clock pacing at ticks of [TICK_MS] or less: a copy at `backupAt` each day, and one
 * catch-up [CATCH_UP_MS] after a start when the newest copy is older than [STALE_MS] (or there is
 * none). A run still going when the next is due is said ("copy running since HH:MM") and the due
 * one is skipped; nothing is cancelled. NO TIMEOUTS: the ticks are pacing on a clock.
 *
 * **Loud:** a failed run is a `Log.e` and the built-in alert [BastionWatcher.COPY] (it reaches his
 * glasses and phone through the alert channel); the next good copy clears it. Older copies are never
 * touched by a failed run.
 */
class NightlyCopy(
    private val dataDir: Path,
    private val backupDir: Path,
    private val keep: Int,
    private val at: LocalTime,
    private val rulesPath: Path,
    private val feedDir: Path,
    private val store: AlertStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    init {
        require(keep >= 1) { "backupKeep $keep is below 1" }
    }

    private val startedAt = clock()

    /** A test seam: runs after a source's files are listed and before they are read (null in production). */
    internal var beforeCopying: ((source: Path) -> Unit)? = null
    /** The last daily slot this instance handled (a slot that had passed at start is the catch-up's). */
    private var lastSlot: LocalDateTime = slotAtOrBefore(clock())
    /** When the one catch-up is due; null when none is (or it has run). */
    private var catchUpAt: Long? = null
    /** When the running copy started; 0 = none running. */
    private val runningSince = AtomicLong(0)

    /** One source: where it is read and where it lands inside the copy. */
    class Source(val from: Path, val to: String)

    /** The allowlist (dec. A.2), resolved from the configured paths. */
    fun sources(): List<Source> = listOf(
        Source(dataDir.resolve("state.json"), "state.json"),
        Source(dataDir.resolve("state.json.bad"), "state.json.bad"),
        Source(dataDir.resolve("torrents.json"), "torrents.json"),
        Source(dataDir.resolve("alerts.json"), "alerts.json"),
        Source(dataDir.resolve(UncaughtRecord.PENDING), UncaughtRecord.PENDING),
        Source(rulesPath, "bastion-rules.json"),
        Source(feedDir.resolve("sources"), "feed/sources"),
        Source(feedDir.resolve("binge"), "feed/binge"),
    )

    /** Starts the ticks. The catch-up is decided now, from what the folder holds. */
    fun start() {
        planCatchUp()
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    due(clock())?.let { launchRun(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "a nightly copy tick failed; the next tick runs as usual", e)
                }
                delay(TICK_MS)              // pacing on the wall clock, not a timeout
            }
        }
    }

    /** The one catch-up after a start: due [CATCH_UP_MS] after it when the newest copy is older than [STALE_MS], or
     *  there is none (a missing folder counts as none: the catch-up then fails loudly). */
    internal fun planCatchUp() {
        val newest = try { copies().lastOrNull() } catch (e: Exception) { null }
        val newestAt = newest?.let { stampOf(it.fileName.toString()) }
        if (newestAt == null || clock() - newestAt > STALE_MS) {
            catchUpAt = startedAt + CATCH_UP_MS
            Log.i(TAG, "nightly copy: " + (if (newest == null) "no copy in $backupDir yet" else "the newest copy is ${newest.fileName}") +
                " - one copy ${CATCH_UP_MS / 60_000} min after this start, then daily at $at")
        } else {
            Log.i(TAG, "nightly copy: daily at $at into $backupDir (newest ${newest?.fileName}), keeping $keep")
        }
    }

    /** Whether a copy is due at [now], and why ("daily" / "catch-up"); null when none is. Consumes the slot. */
    internal fun due(now: Long): String? {
        val c = catchUpAt
        if (c != null && now >= c) {
            catchUpAt = null
            lastSlot = maxOf(lastSlot, slotAtOrBefore(now))
            return "catch-up"
        }
        val slot = slotAtOrBefore(now)
        if (slot > lastSlot) {
            lastSlot = slot
            return "daily"
        }
        return null
    }

    private fun launchRun(why: String) {
        val now = clock()
        if (!runningSince.compareAndSet(0, now)) {
            Log.w(TAG, "nightly copy ($why) skipped: the copy running since ${hhmm(runningSince.get())} has not ended")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                copyNow(why)
            } finally {
                runningSince.set(0)
            }
        }
    }

    /** One copy, now, on the caller's thread. True when a good copy was made. Never throws: a failure is said
     *  and raises [BastionWatcher.COPY]. */
    fun copyNow(why: String = "asked"): Boolean = try {
        val name = copyOnce()
        if (store.stateOf(BastionWatcher.COPY) != AlertState.CLEAR) store.clear(BastionWatcher.COPY)
        Log.i(TAG, "nightly copy ($why): $name")
        true
    } catch (e: Exception) {
        val what = "${e::class.simpleName}: ${e.message ?: "no detail"}"
        Log.e(TAG, "nightly copy ($why) into $backupDir failed - older copies are untouched: $what", e)
        val text = "Nightly copy failed: $what. Older copies are untouched."
        val up = store.table().firstOrNull { it.id == BastionWatcher.COPY }
        if (up != null && up.text != text) store.clear(BastionWatcher.COPY)
        store.raise(BastionWatcher.COPY, Severity.NORMAL, text, "", builtIn = true)
        false
    }

    /** The copy itself; returns the summary. Throws on any failure (the caller says it). */
    private fun copyOnce(): String {
        if (!Files.isDirectory(backupDir)) {
            throw IOException("the folder $backupDir does not exist (a disk not mounted?) - it is never created")
        }
        val present = ArrayList<Source>()
        val absent = ArrayList<String>()
        for (s in sources()) if (Files.exists(s.from, LinkOption.NOFOLLOW_LINKS)) present += s else absent += s.to
        val need = present.sumOf { sizeOf(it.from) }
        val usable = Files.getFileStore(backupDir).usableSpace
        if (need > usable) throw IOException("not enough space in $backupDir: the copy needs $need B, $usable B usable")

        val previous = copies().lastOrNull()
        var name = "damage-" + STAMP.format(Instant.ofEpochMilli(clock()).atZone(zone))
        // two copies inside one second (a test, or a catch-up beside a daily slot) take the next free second
        var bump = 1L
        while (Files.exists(backupDir.resolve(name)) || Files.exists(backupDir.resolve("$name.partial"))) {
            name = "damage-" + STAMP.format(Instant.ofEpochMilli(clock() + 1000 * bump++).atZone(zone))
        }
        val partial = backupDir.resolve("$name.partial")
        Files.createDirectory(partial)          // never createDirectories: the folder itself must already exist
        var files = 0
        var linked = 0
        var bytes = 0L
        var linkFailSaid = false
        // a file that is gone between being listed and being read (a writer's temp file moved into place, fix round
        // 1): skipped and named in the summary; the copy goes on. Every read of a SOURCE file is in this class: the
        // walk's own attribute read, the size and time compare, the copy
        val vanished = ArrayList<String>()
        for (s in present) {
            val files0 = ArrayList<Pair<Path, Path>>()
            if (Files.isDirectory(s.from, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(s.from, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.createDirectories(partial.resolve(s.to).resolve(s.from.relativize(dir).toString()))
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) files0 += file to Path.of(s.to, s.from.relativize(file).toString())
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        if (exc !is NoSuchFileException) throw exc
                        vanished += Path.of(s.to, s.from.relativize(file).toString()).toString()
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                files0 += s.from to Path.of(s.to)
            }
            beforeCopying?.invoke(s.from)
            for ((from, rel) in files0) {
                val dest = partial.resolve(rel.toString())
                dest.parent?.let { Files.createDirectories(it) }
                try {
                    val prev = previous?.resolve(rel.toString())
                    var done = false
                    if (prev != null && Files.isRegularFile(prev, LinkOption.NOFOLLOW_LINKS) &&
                        Files.size(prev) == Files.size(from) && sameTime(prev, from)) {
                        try {
                            Files.createLink(dest, prev)
                            linked++
                            done = true
                        } catch (e: Exception) {
                            if (!linkFailSaid) {
                                linkFailSaid = true
                                Log.w(TAG, "nightly copy: a hard link in $backupDir failed (${e::class.simpleName}: ${e.message}) " +
                                    "- unchanged files are copied whole this run")
                            }
                        }
                    }
                    if (!done) {
                        Files.copy(from, dest, StandardCopyOption.COPY_ATTRIBUTES)
                        bytes += Files.size(dest)
                    }
                    files++
                } catch (e: NoSuchFileException) {
                    if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) throw e      // not the source going: a real failure
                    Files.deleteIfExists(dest)
                    vanished += rel.toString()
                }
            }
        }
        val final = backupDir.resolve(name)
        Files.move(partial, final, StandardCopyOption.ATOMIC_MOVE)
        val removed = prune(final)
        if (vanished.isNotEmpty()) Log.i(TAG, "nightly copy: gone before their copy and skipped: ${vanished.joinToString()}")
        return "$name: $files file(s), $linked linked, $bytes B copied" +
            (if (absent.isNotEmpty()) "; absent and skipped: ${absent.joinToString()}" else "") +
            (if (vanished.isNotEmpty()) "; gone before their copy and skipped: ${vanished.joinToString()}" else "") +
            (if (removed.isNotEmpty()) "; removed ${removed.joinToString()}" else "")
    }

    /** After a good copy: copies beyond [keep], oldest first, and stale `.partial` folders — by NAME PATTERN only. */
    private fun prune(justMade: Path): List<String> {
        val removed = ArrayList<String>()
        val all = copies()
        for (old in all.take(maxOf(0, all.size - keep))) {
            if (old == justMade) continue
            deleteTree(old)
            removed += old.fileName.toString()
        }
        Files.list(backupDir).use { st ->
            for (p in st.toList()) if (PARTIAL.matches(p.fileName.toString()) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(p)
                removed += p.fileName.toString()
            }
        }
        return removed
    }

    /** The folder's copies (`damage-YYYYMMDD-HHMMSS`, directories only), oldest first. A `.partial` is not one. */
    fun copies(): List<Path> = Files.list(backupDir).use { st ->
        st.toList().filter { COPY.matches(it.fileName.toString()) && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .sortedBy { it.fileName.toString() }
    }

    /** The two files' modification times at MICROSECOND precision: a copy with its attributes keeps only
     *  microseconds on this JDK (measured on beardos' xfs, 2026-11-13: the source's 14:32:14.578125415 became
     *  14:32:14.578125000), so a nanosecond compare would never link. */
    private fun sameTime(a: Path, b: Path): Boolean =
        Files.getLastModifiedTime(a, LinkOption.NOFOLLOW_LINKS).to(TimeUnit.MICROSECONDS) ==
            Files.getLastModifiedTime(b, LinkOption.NOFOLLOW_LINKS).to(TimeUnit.MICROSECONDS)

    /** What [p] holds in regular files; a file gone since it was listed counts nothing (the copy skips it). */
    private fun sizeOf(p: Path): Long {
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return try { Files.size(p) } catch (e: NoSuchFileException) { 0L }
        var sum = 0L
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) sum += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                if (exc !is NoSuchFileException) throw exc
                return FileVisitResult.CONTINUE
            }
        })
        return sum
    }

    /** Deletes [root] and what is inside it; a link inside is removed as a link, never followed. */
    private fun deleteTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun slotAtOrBefore(ms: Long): LocalDateTime {
        val now = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
        val today = now.toLocalDate().atTime(at)
        return if (today <= now) today else today.minusDays(1)
    }

    private fun stampOf(name: String): Long? = try {
        LocalDateTime.parse(name.removePrefix("damage-"), STAMP).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    private fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms).atZone(zone))

    companion object {
        private const val TAG = "backup"
        /** The longest pause between two looks at the clock. */
        const val TICK_MS = 60_000L
        /** The one catch-up after a start, when the newest copy is older than [STALE_MS]. */
        const val CATCH_UP_MS = 10 * 60_000L
        const val STALE_MS = 26 * 3_600_000L
        val COPY = Regex("^damage-\\d{8}-\\d{6}$")
        val PARTIAL = Regex("^damage-\\d{8}-\\d{6}\\.partial$")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    }
}
