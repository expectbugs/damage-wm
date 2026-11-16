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
 * The snapshot judge's Files folder and trash (build S1-2 J0; the spec's §6 "J0"): a made-up folder at a made-up path
 * and two made-up trash entries, every date pinned, so the Files scenes that show a menu, a viewer or the trash are the
 * same pictures in every run and on every machine. Everything else is the [real] provider over the books, as before.
 * Both appear only once [shown] is set — before that the locations are exactly the real provider's, so the scenes
 * rendered earlier (`11-files-locations`) are unchanged. Harness only: nothing here is a product path.
 */
class SceneFiles(private val real: FilesProvider) : FilesProvider by real {
    /** Set by the script before it walks the scene folder and the trash (read off the shell loop). */
    @Volatile var shown = false
    /** Text reads of [NOTES] answered: the viewer's scene waits for its one chunk to have been served. */
    @Volatile var textServed = 0

    /** How many locations the last listing answered once [shown]: the scene waits for the window to show that many. */
    @Volatile var shownLocations = 0

    override fun locations(): List<FLocation> =
        if (!shown) real.locations()
        else (real.locations() + FLocation("Scene", DIR, "mount") + FLocation("Trash", TRASH_DIR, "trash"))
            .also { shownLocations = it.size }

    override fun cachedLocations(): List<FLocation>? = if (shown) null else real.cachedLocations()

    override fun list(dir: String, showHidden: Boolean): List<FEntry> = if (dir == DIR) ENTRIES else real.list(dir, showHidden)

    override fun cachedList(dir: String, showHidden: Boolean): List<FEntry>? =
        if (dir == DIR) null else real.cachedList(dir, showHidden)

    override fun stat(path: String): FStat {
        val e = entryAt(path) ?: return real.stat(path)
        return FStat(e.size, e.mtimeMs, "rw-r--r--", "glassuser", e.dir)
    }

    override fun du(path: String): Long = entryAt(path)?.size ?: if (path == DIR) ENTRIES.sumOf { it.size } else real.du(path)

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
        if (path.substringBeforeLast('/') != DIR) null else ENTRIES.firstOrNull { it.name == path.substringAfterLast('/') }

    companion object {
        const val DIR = "/srv/scene"
        const val TRASH_DIR = "/srv/scene-trash"
        const val NOTES = "$DIR/notes.txt"

        /** A pinned day long past: `fmtMtime` draws a date ("Mar 14"), never today's clock. */
        private fun at(month: Int, day: Int, hour: Int): Long =
            LocalDateTime.of(2026, month, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val ENTRIES = listOf(
            FEntry("notes.txt", false, 1_310, at(3, 14, 10)),
            FEntry("packing-list.txt", false, 642, at(2, 2, 9)),
            FEntry("route.gpx", false, 88_400, at(1, 20, 18)),
        )

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
