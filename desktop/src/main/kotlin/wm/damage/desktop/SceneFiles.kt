package wm.damage.desktop

import java.time.LocalDateTime
import java.time.ZoneId
import wm.damage.core.windows.files.FEntry
import wm.damage.core.windows.files.FLocation
import wm.damage.core.windows.files.FStat
import wm.damage.core.windows.files.FTrashEntry
import wm.damage.core.windows.files.FilesProvider
import wm.damage.core.windows.files.TextChunk

/**
 * The snapshot judge's Files world (build S1-2 J0; build S1-3 close): a pinned list of locations (pinned totals and
 * free space), a made-up root listing, and — once [shown] is set — a made-up folder and two made-up trash entries,
 * every date pinned, so every Files scene is the same picture in every run and on every machine. Nothing the scenes
 * reach reads the live machine: before S1-3's close the locations were the real provider's (`/proc/mounts`, the home
 * directory, each volume's free space) and Root listed the real `/` (names, sizes and mtimes, today's as a clock time),
 * which moved `01`, `06`, `09`, `09f`, `09j`, `11`, `12`, `13` and `78` between days. [real] still answers the trash
 * operations and any path outside this world (none in the scripted scenes). Harness only: nothing here is a product
 * path. [booksDir] is the Books row's path (the config's, as the windows are given it).
 */
class SceneFiles(private val real: FilesProvider, private val booksDir: String) : FilesProvider by real {
    /** Set by the script before it walks the scene folder and the trash (read off the shell loop). */
    @Volatile var shown = false
    /** Text reads of [NOTES] answered: the viewer's scene waits for its one chunk to have been served. */
    @Volatile var textServed = 0

    /** How many locations the last listing answered once [shown]: the scene waits for the window to show that many. */
    @Volatile var shownLocations = 0

    private val pinned = listOf(
        FLocation("Root", ROOT, "root", DISK_TOTAL, DISK_FREE),
        FLocation("Home", HOME, "home", DISK_TOTAL, DISK_FREE),
        FLocation("Downloads", "$HOME/Downloads", "downloads", DISK_TOTAL, DISK_FREE),
        FLocation("Books", booksDir, "books", DISK_TOTAL, DISK_FREE),
        FLocation("damagewm", "$HOME/damagewm", "project", DISK_TOTAL, DISK_FREE),
        FLocation("archive", "/mnt/archive", "mount", 4L shl 40, 900L shl 30),
        FLocation("usb-stick", "/run/media/glassuser/usb-stick", "mount", 64L shl 30, 51L shl 30),
    )

    override fun locations(): List<FLocation> =
        if (!shown) pinned
        else (pinned + FLocation("Scene", DIR, "mount") + FLocation("Trash", TRASH_DIR, "trash"))
            .also { shownLocations = it.size }

    /** No copy of its own: the pinned list is answered at once. */
    override fun cachedLocations(): List<FLocation>? = null

    override fun list(dir: String, showHidden: Boolean): List<FEntry> = LISTINGS[dir] ?: real.list(dir, showHidden)

    override fun cachedList(dir: String, showHidden: Boolean): List<FEntry>? =
        if (dir in LISTINGS) null else real.cachedList(dir, showHidden)

    override fun stat(path: String): FStat {
        val e = entryAt(path) ?: return real.stat(path)
        return FStat(e.size, e.mtimeMs, "rw-r--r--", "glassuser", e.dir)
    }

    override fun du(path: String): Long =
        entryAt(path)?.size ?: LISTINGS[path]?.sumOf { it.size } ?: real.du(path)

    override fun readText(path: String, offset: Long, maxBytes: Int): TextChunk {
        if (path != NOTES) return real.readText(path, offset, maxBytes)
        val bytes = NOTES_TEXT.toByteArray(Charsets.UTF_8)
        val from = offset.coerceIn(0L, bytes.size.toLong()).toInt()
        val to = minOf(bytes.size, from + maxBytes)
        textServed++
        return TextChunk(String(bytes, from, to - from, Charsets.UTF_8), to < bytes.size, bytes.size.toLong(),
            (to - from).toLong())
    }

    override fun thumb(path: String, sizePx: Int) = if (entryAt(path) != null) null else real.thumb(path, sizePx)

    override fun trashList(): List<FTrashEntry> = if (shown) TRASHED else real.trashList()

    private fun entryAt(path: String): FEntry? =
        LISTINGS[path.substringBeforeLast('/')]?.firstOrNull { it.name == path.substringAfterLast('/') }

    companion object {
        const val DIR = "/srv/scene"
        const val TRASH_DIR = "/srv/scene-trash"
        const val NOTES = "$DIR/notes.txt"
        /** The Root location's made-up path, listed from [ROOT_ENTRIES] (`12-files-browse`, `13-files-menu`). */
        const val ROOT = "/srv/scene-root"
        private const val HOME = "/home/glassuser"
        private const val DISK_TOTAL = 2L shl 40
        private const val DISK_FREE = 600L shl 30

        /** A pinned day long past: `fmtMtime` draws a date ("Mar 14"), never today's clock. */
        private fun at(month: Int, day: Int, hour: Int): Long =
            LocalDateTime.of(2026, month, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val ENTRIES = listOf(
            FEntry("notes.txt", false, 1_310, at(3, 14, 10)),
            FEntry("packing-list.txt", false, 642, at(2, 2, 9)),
            FEntry("route.gpx", false, 88_400, at(1, 20, 18)),
        )

        /** Folders, and a few files whose types each draw their own theme icon. */
        val ROOT_ENTRIES = listOf(
            FEntry("bin", true, 0, at(9, 10, 8)),
            FEntry("boot", true, 0, at(10, 31, 21)),
            FEntry("dev", true, 0, at(10, 2, 7)),
            FEntry("etc", true, 0, at(10, 16, 12)),
            FEntry("home", true, 0, at(8, 3, 16)),
            FEntry("lib", true, 0, at(9, 10, 8)),
            FEntry("mnt", true, 0, at(6, 22, 11)),
            FEntry("opt", true, 0, at(7, 5, 14)),
            FEntry("srv", true, 0, at(5, 19, 10)),
            FEntry("usr", true, 0, at(8, 14, 9)),
            FEntry("var", true, 0, at(4, 4, 13)),
            FEntry("backup.tar.gz", false, 2_254_857_830, at(10, 12, 3)),
            FEntry("manual.pdf", false, 4_831_838, at(6, 28, 19)),
            FEntry("photo.jpg", false, 3_355_443, at(7, 19, 17)),
            FEntry("track.flac", false, 32_505_856, at(3, 8, 22)),
        )

        /** The made-up folders by path: listed, stat'ed and summed from here, never from the disk. */
        private val LISTINGS = mapOf(DIR to ENTRIES, ROOT to ROOT_ENTRIES)

        val TRASHED = listOf(
            FTrashEntry("t2", "old-draft.txt", "$DIR/old-draft.txt", false, 2_048, at(4, 9, 15)),
            FTrashEntry("t1", "screenshots", "$DIR/screenshots", true, 0, at(4, 2, 11)),
        )

        /** Paragraphs one line each: the viewer wraps them to its own width. */
        val NOTES_TEXT = listOf(
            "Trip notes",
            "Leave early on Saturday so the drive is done before the heat. Fill the water tank the night before " +
                "and check the tyres at the first stop.",
            "Things still to buy: a spare fuse set, two litres of oil, matches, and a new map of the forest roads.",
            "The campsite at the lake takes no bookings; arrive before noon for a place near the water.",
        ).joinToString("\n\n", postfix = "\n")
    }
}
