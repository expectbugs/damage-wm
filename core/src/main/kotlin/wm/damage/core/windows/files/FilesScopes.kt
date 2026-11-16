package wm.damage.core.windows.files

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.AppNet
import wm.damage.core.app.Change
import wm.damage.core.app.Levels
import wm.damage.core.app.Paged
import wm.damage.core.app.Record
import wm.damage.core.app.WindowState
import wm.damage.core.content.LocalContent
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1 "Description … Levels", §2.2, §5 V2; `CONTRACT.md` §3.2, §9.2, §10.4): Files'
 * PC-served scopes on the `app` lane, each at level full (Files is full as a whole: below it nothing is served):
 *  - `desc/files` — the description (locations, the job slot, actions, the Size row), described again on a job change
 *    (progress at most once a second, [FilesJobs]) and on a change of Files' records;
 *  - `files/folder:<path>/page:<n>` — a folder's listing a page at a time (500 rows), sorted and filtered on the PC by
 *    the shared sort and hidden rows; the listing is read whole and paged here ([Paged]), so its version moves when a
 *    read differs; at a job's end each folder held is read again (its version moves if the job changed it), and a sort
 *    or hidden change describes every page again;
 *  - `files/trash` — the trash's entries;
 *  - `files/text:<path>/at:<offset>` — today's 128 KB text chunk;
 *  - `files/pdf:<path>` — the PDF's facts.
 * The path is raw in the scope; its suffix is read from the LAST `/page:` or `/at:` (a name never holds `/`). Every
 * description runs on this provider's own describer, so a cold disk never holds Reader's part or Main's cards.
 */
class FilesScopes(
    private val core: FilesCore,
    /** The PC's provider behind the slot (reads pass through). */
    private val files: FilesProvider,
    private val jobs: FilesJobs,
    /** Files' records as the PC holds them now (the shared sort and hidden). */
    private val state: () -> WindowState,
    private val booksDir: () -> String,
    override val describeOn: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "files-describe").apply { isDaemon = true }
    },
) : AppNet.Described {
    private val paged = Paged()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    /** The folders a page was described of (read again at each job's end), at most [KNOWN] of them. */
    private val known = ConcurrentHashMap<String, Long>()

    init {
        jobs.addListener { j ->
            if (j.running) fire() else describeOn.execute {
                // a job's end: each folder held is read again; one the job changed moves its version, and the lane
                // describes every page of it a connection holds again (a folder that no longer lists moves too)
                for (dir in known.keys.toList()) try {
                    paged.describe(listing(dir), 0) { read(dir, 0, unreadableToo = true) }
                } catch (e: Exception) {
                    Log.w(TAG, "after a job, $dir could not be read again: ${e.message}")
                }
                fire()
            }
        }
    }

    override fun gate(scope: String): AppNet.Gate? =
        if (scope == DESC || scope.startsWith(PREFIX)) AppNet.Gate(FilesCore.WINDOW, Levels.FULL) else null

    override fun describe(scope: String): JsonObject? {
        if (scope == DESC) return core.describe(state())
        if (scope == TRASH) return buildJsonObject {
            put("rows", buildJsonArray {
                for (e in files.trashList()) add(buildJsonObject {
                    put("id", e.id); put("name", e.name); put("origPath", e.origPath); put("dir", e.dir)
                    put("size", e.size); put("at", e.atMs)
                })
            })
        }
        Paged.parse(scope)?.let { (w, listing, n) ->
            if (w != FilesCore.WINDOW || !listing.startsWith(FOLDER)) return null
            val dir = listing.removePrefix(FOLDER)
            if (!dir.startsWith("/")) return null
            known[dir] = System.nanoTime()
            if (known.size > KNOWN) known.entries.minByOrNull { it.value }?.let { known.remove(it.key) }
            return paged.describe(listing, n) { read(dir, n) }
        }
        if (scope.startsWith(TEXT)) {
            val i = scope.lastIndexOf(AT)
            if (i < 0) return null
            val path = scope.substring(TEXT.length, i)
            val at = scope.substring(i + AT.length).toLongOrNull()?.takeIf { it >= 0 } ?: return null
            if (!path.startsWith("/")) return null
            val c = files.readText(path, at, CHUNK)
            return buildJsonObject {
                put("text", c.text); put("more", c.more); put("totalBytes", c.totalBytes); put("bytesRead", c.bytesRead)
            }
        }
        if (scope.startsWith(PDF)) {
            val path = scope.removePrefix(PDF)
            if (!path.startsWith("/")) return null
            val p = files.pdfInfo(path)
            return buildJsonObject { put("pages", p.pages); put("textChars", p.textChars) }
        }
        return null
    }

    override fun follows(scope: String, c: Change?): Boolean {
        if (c == null) return scope == DESC || scope == TRASH
        val p = Record.prefix(FilesCore.WINDOW)
        val keys = c.effect.items.keys
        if (scope == DESC) return keys.any { it.startsWith(p) }
        if (scope.startsWith(FOLDER_SCOPE)) return Record.key(FilesCore.WINDOW, FilesCore.SORT) in keys ||
            Record.key(FilesCore.WINDOW, FilesCore.HIDDEN) in keys
        return false
    }

    override fun addListener(l: () -> Unit) {
        listeners += l
    }

    override fun version(scope: String): Long? =
        Paged.parse(scope)?.takeIf { it.first == FilesCore.WINDOW && it.second.startsWith(FOLDER) }?.let { paged.version(it.second) }

    /** Page [n] of [dir], sorted and filtered as the shared rows say; the signature is the whole listing's, so any
     *  change of it moves the version. [unreadableToo]: a folder that no longer lists reads as a change, not a throw. */
    private fun read(dir: String, n: Int, unreadableToo: Boolean = false): Paged.Read {
        val st = state()
        val sort = FilesCore.sortOf(st) ?: "name"
        val hidden = FilesCore.hiddenOf(st) ?: false
        val all = try {
            sorted(files.list(dir, hidden), sort)
        } catch (e: Exception) {
            if (!unreadableToo) throw e
            return Paged.Read(emptyList(), 0, false, signature = "unreadable: ${e.message}")
        }
        val from = n * paged.pageRows
        val books = booksDir()
        val rows = all.drop(from).take(paged.pageRows).map { row(dir, it, books) }
        return Paged.Read(rows, all.size, from + rows.size < all.size,
            signature = Paged.digest("$sort|$hidden|" + all.joinToString("\n") { "${it.name}\t${it.dir}\t${it.size}\t${it.mtimeMs}" }))
    }

    private fun fire() {
        for (l in listeners) try {
            l()
        } catch (e: Exception) {
            Log.e(TAG, "a listener failed", e)
        }
    }

    companion object {
        private const val TAG = "files-scopes"
        const val DESC = "desc/files"
        const val PREFIX = "files/"
        const val TRASH = "files/trash"
        const val FOLDER = "folder:"
        const val FOLDER_SCOPE = "files/folder:"
        const val TEXT = "files/text:"
        const val AT = "/at:"
        const val PDF = "files/pdf:"
        /** Today's text chunk (the glasses' viewer reads the same size). */
        const val CHUNK = 128 * 1024
        private const val KNOWN = 64

        private fun listing(dir: String) = "$FOLDER$dir"

        fun folderScope(dir: String, n: Int) = Paged.scope(FilesCore.WINDOW, listing(dir), n)

        /** The glasses' order: folders first, then the key (size and time descending), ties by name. */
        fun sorted(entries: List<FEntry>, sort: String): List<FEntry> {
            val byName = compareBy<FEntry> { it.name.lowercase() }.thenBy { it.name }
            val key: Comparator<FEntry> = when (sort) {
                "size" -> compareByDescending<FEntry> { it.size }.then(byName)
                "mtime" -> compareByDescending<FEntry> { it.mtimeMs }.then(byName)
                else -> byName
            }
            return entries.sortedWith(compareByDescending<FEntry> { it.dir }.then(key))
        }

        private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "avif")

        /** One row of a folder's page (the spec's §4.1): `{id: name, name, dir, size, mtime, reader?, inline, thumb}`. */
        fun row(dir: String, e: FEntry, booksDir: String): JsonObject = buildJsonObject {
            val path = if (dir == "/") "/${e.name}" else "${dir.trimEnd('/')}/${e.name}"
            val ext = e.name.substringAfterLast('.', "").lowercase()
            put("id", e.name); put("name", e.name); put("dir", e.dir); put("size", e.size); put("mtime", e.mtimeMs)
            if (!e.dir && booksDir.isNotEmpty() && path.startsWith("${booksDir.trimEnd('/')}/") && (ext == "epub" || ext == "txt"))
                put("reader", LocalContent.idForPathString(path))
            val img = !e.dir && ext in IMAGES
            put("inline", img)
            put("thumb", img)
        }
    }
}

/** Main's card for Files (`AppNet.serve`'s `card`): the job running, else the locations the last description read. */
fun filesCard(core: FilesCore, jobs: FilesJobs): JsonObject = buildJsonObject {
    put("window", FilesCore.WINDOW)
    put("name", "Files")
    put("icon", "folder")
    put("min", Levels.FULL)
    put("availability", "live")
    val j = jobs.current()?.takeIf { it.running }
    val locs = core.lastLocations
    put("summary", buildJsonObject {
        put("line", when {
            j != null -> "On the PC: ${j.label}"
            locs == null -> "Files on the PC"
            else -> "${locs.size} locations" + (locs.firstOrNull { it.kind == "home" }?.takeIf { it.totalBytes > 0 }
                ?.let { " · ${fmtBytes(it.freeBytes)} free in ${it.label}" } ?: "")
        })
        j?.fraction?.let { put("progress", JsonPrimitive(it)) }
    })
}
