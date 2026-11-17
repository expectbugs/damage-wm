package wm.damage.core.windows.reader

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.ActionSpec
import wm.damage.core.app.ActionTable
import wm.damage.core.app.Draft
import wm.damage.core.app.Item
import wm.damage.core.app.Kind
import wm.damage.core.app.WindowCore
import wm.damage.core.app.WindowState
import wm.damage.core.content.BookMeta
import wm.damage.core.content.ContentProvider
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 (`CONTRACT.md` §11.1, §12; the spec's §2 P4): Reader's core — the shelf and its scan, the
 * record items (each book's place and bookmark, the book read most recently, the glasses' Reader rows), the actions
 * and the description. The glasses' presenter (`ReaderGlasses`) reads it through its `WindowSource`; the web
 * presenter (P7) reads the same description. No pixels here.
 */

/**
 * Reader's core over [content] (`LocalContent` on the PC, `RemoteContent` on the phone). The shelf scan runs on [bg];
 * its result goes to every shelf listener (a presenter hands it to its own loop). The record items are read from a
 * [WindowState]; the ops that write them are built by the companion's drafts. [index] is the PC's reader index (P5):
 * each scan's shelf is handed to it, and the description takes each book's fraction, cover and chapters from it; null
 * (the phone, a tool) leaves them absent.
 */
class ReaderCore(
    val content: ContentProvider,
    private val bg: CoroutineScope,
    val index: ReaderIndex? = null,
) : WindowCore {
    override val window: String = WINDOW

    /** The shelf as the last scan found it, and its state ("loading", "ok", "no books found", or the error). */
    @Volatile var library: List<BookMeta> = emptyList()
        private set
    @Volatile var libraryState: String = "loading"
        private set

    /** §72: the scan in flight, if any. "In flight" is the scan's own job, not a flag its completion clears: a
     *  completion lost at a session's end must not stop every later scan. */
    @Volatile private var scanJob: Job? = null
    private val shelfListeners = CopyOnWriteArrayList<(List<BookMeta>, String) -> Unit>()

    fun scanRunning(): Boolean = scanJob?.isActive == true

    /** [l] gets every scan's result (the listing and its state), on the scan's thread. */
    fun addShelfListener(l: (List<BookMeta>, String) -> Unit) {
        shelfListeners += l
    }

    fun removeShelfListener(l: (List<BookMeta>, String) -> Unit) {
        shelfListeners -= l
    }

    /** Scan the shelf on [bg] (moved from `ReaderWindow.refreshLibrary`). A failed scan keeps the shelf it had. */
    @Synchronized
    fun refreshLibrary() {
        val keep = library
        scanJob = bg.launch(Dispatchers.IO) {
            var lib: List<BookMeta> = keep
            var state: String
            try {
                lib = content.library()
                state = if (lib.isEmpty()) "no books found" else "ok"
            } catch (e: Exception) {
                Log.e("reader", "library scan failed", e)
                state = "library error: ${e.message}"
            }
            library = lib
            libraryState = state
            // P5: the index walks the shelf this scan found (one book at a time, its own job); a failed scan keeps it
            if (state == "ok") index?.update(lib)
            for (l in shelfListeners) {
                try {
                    l(lib, state)
                } catch (e: Exception) {
                    Log.e("reader", "a shelf listener failed", e)
                }
            }
        }
    }

    /** §72: the content source found a changed listing (a book added on the PC): scan again, unless a scan is
     *  already on its way. Any thread. */
    fun libraryChanged() {
        if (!scanRunning()) refreshLibrary()
    }

    /** What `book:<id>` names (the lookup half of the old `ReaderWindow.open`). */
    sealed class Resolved {
        data class Book(val meta: BookMeta) : Resolved()
        object Gone : Resolved() {
            override fun toString() = "Gone"
        }
    }

    /**
     * Resolve [target] (`book:<id>`), or null when it is not a Reader address. Blocking (a listing, maybe fresh from
     * the source): call it off the shell loop. §72: a cached listing may predate a book just added on the PC, so a
     * miss asks the source itself before answering [Resolved.Gone]. A failed listing throws (the caller says it).
     */
    fun resolve(target: String): Resolved? {
        if (!target.startsWith("book:")) return null
        val id = target.removePrefix("book:")
        if (id.isEmpty()) return null
        val meta = content.library().firstOrNull { it.id == id }
            ?: content.library(fresh = true).firstOrNull { it.id == id }
        return meta?.let { Resolved.Book(it) } ?: Resolved.Gone
    }

    // ---- the description (C§3.1, C§12) ----

    override fun describe(state: WindowState): JsonObject = buildJsonObject {
        put("id", WINDOW)
        put("name", "Reader")
        put("min", "view")
        put("state", libraryState)
        put("shelf", shelf(state, ""))
        recent(state)?.let { put("recent", it) }
        put("actions", buildJsonArray {
            for (a in ACTION_LIST) add(buildJsonObject {
                put("id", a.id); put("kind", a.kind); put("level", a.level); put("label", a.label)
                a.confirm?.let { put("confirm", it) }
            })
        })
        put("settings", buildJsonArray {
            add(setting(SCROLL_LINES, "Scroll step", scrollLines(state) ?: DEFAULT_SCROLL_LINES))
            add(setting(SCROLL_ACCEL, "Scroll accel", scrollAccel(state) ?: false))
            add(setting(SIZE, "Size", size(state) ?: 0))
        })
    }

    /** `shelf` (the root), `folder:<path>`, or `book:<id>`; null when [addr] names nothing on the shelf. */
    override fun level(state: WindowState, addr: String): JsonObject? = when {
        addr == "shelf" -> shelf(state, "")
        addr.startsWith("folder:") -> addr.removePrefix("folder:").takeIf { f -> library.any { inFolder(it.folder, f) } }
            ?.let { shelf(state, it) }
        addr.startsWith("book:") -> library.firstOrNull { it.id == addr.removePrefix("book:") }?.let { bookLevel(state, it) }
        else -> null
    }

    /** The file of [book] on the shelf as the last scan found it, or null (the documents' lookup, `ReaderDocs`). */
    fun pathOf(book: String): java.nio.file.Path? =
        library.firstOrNull { it.id == book }?.let { java.nio.file.Path.of(it.file) }

    /** A book's own level (C§12 "shelf → chapters → book"): the book, and its chapters `{title, anchor}` with their
     *  count when the index holds the book. */
    private fun bookLevel(state: WindowState, b: BookMeta): JsonObject {
        val e = index?.entry(b.file, b.bytes)?.takeIf { it.error == null } ?: return book(state, b)
        return JsonObject(book(state, b) + mapOf(
            "chapters" to buildJsonArray {
                for (c in e.chapters) add(buildJsonObject {
                    put("title", c.title)
                    put("anchor", ReaderAnchor(c.offset).format())
                })
            },
            "length" to JsonPrimitive(e.textLength),
        ))
    }

    private fun inFolder(folder: String, f: String) = folder == f || folder.startsWith("$f/")

    private fun shelf(state: WindowState, folder: String): JsonObject = buildJsonObject {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val subs = sortedSetOf<String>()
        val books = ArrayList<BookMeta>()
        for (b in library) when {
            b.folder == folder -> books += b
            folder.isEmpty() && b.folder.isNotEmpty() -> subs += b.folder.substringBefore('/')
            b.folder.startsWith(prefix) -> subs += b.folder.removePrefix(prefix).substringBefore('/')
        }
        put("folder", folder)
        put("folders", buildJsonArray { for (s in subs) add(JsonPrimitive(s)) })
        put("books", buildJsonArray { for (b in books.sortedBy { it.title.lowercase() }) add(book(state, b)) })
    }

    private fun book(state: WindowState, b: BookMeta): JsonObject = buildJsonObject {
        put("id", b.id)
        put("title", b.title)
        put("author", b.author)
        put("bytes", b.bytes)
        put("folder", b.folder)
        // the fraction is the place over the book's text length, which only the PC's reader index holds (P5)
        val e = index?.entry(b.file, b.bytes)
        val place = placeOf(state, b.id)
        put("fraction", e?.fraction(place)?.let { JsonPrimitive(it) } ?: JsonNull)
        // P7: the text length, so a page computes the progress from the record's place as it moves
        e?.takeIf { it.error == null && it.textLength > 0 }?.let { put("length", it.textLength) }
        e?.coverRef?.let { put("cover", EpubDoc.blobId(b.id, it)) }
        e?.error?.let { put("unreadable", it) }
        place?.let { put("place", it.format()) }
        bookmarkOf(state, b.id)?.let { put("bookmark", it.format()) }
    }

    private fun setting(id: String, label: String, value: Any): JsonObject = buildJsonObject {
        put("id", id); put("label", label); put("scope", "glasses"); put("level", "control")
        when (value) {
            is Int -> put("value", value)
            is Boolean -> put("value", value)
            else -> put("value", value.toString())
        }
    }

    /** One action as the description lists it (C§4.1, C§12; the levels as the spec's §6 rules them). */
    data class ActionDesc(val id: String, val kind: String, val level: String, val label: String, val confirm: String? = null)

    companion object {
        const val WINDOW = "reader"

        // the actions (the table below gives each its kind)
        /** A book's place: written only by input, at the glasses' save points (dec. A.10). */
        const val PLACE = "place"
        /** A book's bookmark (one per book: the bookmark gains a book id, C§12). */
        const val BOOKMARK = "bookmark"
        /** Open a book: sets the book read most recently ([RECENT], arrival order, dec. A.9). */
        const val OPEN = "open"
        /** A glasses Reader row ([SCROLL_LINES], [SCROLL_ACCEL], [SIZE]). */
        const val SET = "set"
        /** Reset progress: the book's place removed (cancel first, as built). */
        const val RESET = "reset"

        // the record items
        const val RECENT = "recent"
        const val SCROLL_LINES = "glasses.scrollLines"
        const val SCROLL_ACCEL = "glasses.scrollAccel"
        /** The glasses' Reader height: 0 = the Global height, else one of the four sizes. */
        const val SIZE = "glasses.size"
        const val DEFAULT_SCROLL_LINES = 5

        fun placeItem(book: String) = "place.$book"
        fun bookmarkItem(book: String) = "bookmark.$book"

        /** The level [action] needs (S1-1 P6, C§10.4; the spec's §6): open and the place are reading effects (view);
         *  a bookmark, a glasses row and reset progress are control. Null: no such action. */
        fun levelOf(action: String): String? = when (action) {
            OPEN, PLACE -> wm.damage.core.app.Levels.VIEW
            BOOKMARK, SET, RESET -> wm.damage.core.app.Levels.CONTROL
            else -> null
        }

        /** Reader's actions as the record judges them (C§2.4). */
        val ACTIONS = ActionTable { w, a ->
            if (w != WINDOW) null else when (a) {
                PLACE -> ActionSpec(Kind.POSITION)
                BOOKMARK, SET, OPEN -> ActionSpec(Kind.REGISTER)
                RESET -> ActionSpec(Kind.REMOVE)
                else -> null
            }
        }

        /** The actions a presenter offers (jump moves the view, then the place at the next save point; rescan asks
         *  the core for a new scan and writes nothing). */
        val ACTION_LIST = listOf(
            ActionDesc(OPEN, "v", "view", "Open"),
            ActionDesc(PLACE, "r", "view", "Place"),
            ActionDesc(BOOKMARK, "r", "control", "Bookmark here"),
            ActionDesc("jump", "v", "view", "Jump"),
            ActionDesc("rescan", "r", "view", "Rescan"),
            ActionDesc(RESET, "i", "control", "Reset progress", confirm = "cancel first"),
        )

        private fun live(state: WindowState, name: String): Item? = state.itemNamed(name)?.takeIf { !it.gone }

        private fun anchor(v: JsonElement?): ReaderAnchor? = (v as? JsonPrimitive)?.takeIf { it.isString }?.let {
            ReaderAnchor.parse(it.content)
        }

        /** [book]'s place in the record, or null (none, removed, or not a place). */
        fun placeOf(state: WindowState, book: String): ReaderAnchor? = anchor(live(state, placeItem(book))?.value)

        fun bookmarkOf(state: WindowState, book: String): ReaderAnchor? = anchor(live(state, bookmarkItem(book))?.value)

        /** Every book's place the record holds, by book id. */
        fun places(state: WindowState): Map<String, ReaderAnchor> {
            val p = wm.damage.core.app.Record.key(WINDOW, "place.")
            val out = HashMap<String, ReaderAnchor>()
            for ((k, it) in state.items) if (k.startsWith(p) && !it.gone) anchor(it.value)?.let { a -> out[k.removePrefix(p)] = a }
            return out
        }

        /** The book read most recently anywhere, or null. */
        fun recent(state: WindowState): String? =
            (live(state, RECENT)?.value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        fun scrollLines(state: WindowState): Int? =
            (live(state, SCROLL_LINES)?.value as? JsonPrimitive)?.intOrNull?.coerceIn(1, 8)

        fun scrollAccel(state: WindowState): Boolean? = (live(state, SCROLL_ACCEL)?.value as? JsonPrimitive)?.booleanOrNull

        fun size(state: WindowState): Int? = (live(state, SIZE)?.value as? JsonPrimitive)?.intOrNull

        // ---- the drafts a presenter sends (C§2.4: the result; L1 C1: its input time when the presenter knows it) ----

        /** [book]'s place [place], from the input at [inputAt] (null: the source stamps its clock). */
        fun placeDraft(book: String, place: ReaderAnchor, inputAt: Long? = null) =
            Draft(PLACE, listOf(placeItem(book)), JsonPrimitive(place.format()), at = inputAt)

        fun bookmarkDraft(book: String, place: ReaderAnchor) =
            Draft(BOOKMARK, listOf(bookmarkItem(book)), JsonPrimitive(place.format()))

        fun openDraft(book: String) = Draft(OPEN, listOf(RECENT), JsonPrimitive(book))

        fun setDraft(row: String, value: JsonElement) = Draft(SET, listOf(row), value)

        /** Reset progress of [book]: its place removed. */
        fun resetDraft(book: String) = Draft(RESET, listOf(placeItem(book)), JsonNull)
    }
}
