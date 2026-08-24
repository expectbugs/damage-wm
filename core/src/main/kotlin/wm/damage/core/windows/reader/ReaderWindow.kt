package wm.damage.core.windows.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.content.BookMeta
import wm.damage.core.content.ContentProvider
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.DocModel
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.WindowView
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.text.Wrap
import wm.damage.core.util.Log

/**
 * READER — the ereader window. Three levels: library (list) -> book (document,
 * Alegreya per the locked §Type table, endless scroll) -> actions (list, the
 * §4.6 wrap-to-end pattern: tap in the document descends to actions).
 *
 * Threading: IO/layout work runs on [bg]; every STATE MUTATION it produces is
 * applied through [ShellServices.runOnShell], so the loop never observes a torn
 * book/topLine/level combination (review round 1). Reading positions are
 * per-book CHARACTER offsets — monotonic by construction, so restore lands on
 * the right line — and survive book switches, font rescales and restarts (§9.1).
 */
class ReaderWindow(
    private val text: TextRasterizer,
    private val content: ContentProvider,
    private val bg: CoroutineScope,
) : DamageWindow("reader", "Reader", IconKind.READER) {

    private enum class Level_ { LIBRARY, BOOK, ACTIONS }

    private var level = Level_.LIBRARY
    private val libModel = ListModel()
    private val docModel = DocModel()
    private val actModel = ListModel()

    private var library: List<BookMeta> = emptyList()
    private var libraryState = "loading"
    private var book: Loaded? = null
    /** Reading position per book id, as CHARACTER offsets (§9.1). */
    private val offsets = HashMap<String, Int>()
    private var bookmarkOffset = -1
    private var openingId: String? = null
    private var services: ShellServices? = null
    private var wrappedWidth = 0

    /** Reader content face: Alegreya (locked). Line height 24 px — even (§2.4 r7). */
    private val fBody = FontSpec(Face.READER, 17)
    private val fBodyB = FontSpec(Face.READER, 17, bold = true)
    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val lineH = 24

    private class Loaded(
        val meta: BookMeta,
        val book: Epub.Book,
        val lines: List<Line>,
        val linesPerPage: Int,
        val width: Int,
    ) {
        data class Line(val text: String, val offset: Int, val heading: Boolean)

        val pages: Int get() = maxOf(1, (lines.size + linesPerPage - 1) / linesPerPage)
        fun pageOf(line: Int): Int = line.coerceIn(0, lines.size - 1) / linesPerPage + 1
        fun lineAtOffset(off: Int): Int {
            // offsets are monotonic non-decreasing by construction (layoutBook)
            var lo = 0
            var hi = lines.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (lines[mid].offset <= off) lo = mid else hi = mid - 1
            }
            return lo
        }
    }

    // ------------------------------------------------------------------ contract
    override fun view(): WindowView = when (level) {
        Level_.LIBRARY -> libView()
        Level_.BOOK -> {
            val b = book
            if (b == null) libView()
            else WindowView.DocView(docModel, { b.lines.size }, lineH,
                { g, i, r -> paintBookLine(g, b, i, r) }, { level = Level_.ACTIONS })
        }
        Level_.ACTIONS -> WindowView.ListView(actModel, { actions().size },
            ::paintActRow, ::paintActLens, ::commitAction)
    }

    private fun libView() = WindowView.ListView(libModel, { library.size },
        ::paintLibRow, ::paintLibLens, ::commitLibrary)

    override fun title(): String = when (level) {
        Level_.LIBRARY -> "library"
        else -> book?.let { "${it.meta.title} · p.${it.pageOf(docModel.topLine)} of ${it.pages}" } ?: "opening"
    }

    override fun summary(): Summary {
        val b = book ?: return Summary(
            when (libraryState) {
                "ok" -> "${library.size} books"
                "loading" -> "library loading"
                else -> libraryState
            },
            more = library.size > 1,
        )
        val page = b.pageOf(docModel.topLine)
        val pct = docModel.topLine.toDouble() / maxOf(1, b.lines.size - 1)
        return Summary(
            "${b.meta.title} · p.$page/${b.pages} · ${(pct * 100).toInt()}%",
            detail = if (b.book.author.isEmpty()) b.meta.title else b.book.author,
            progress = pct.coerceIn(0.0, 1.0),
        )
    }

    override fun levelDepth(): Int = when (level) {
        Level_.LIBRARY -> 1
        Level_.BOOK -> 2
        Level_.ACTIONS -> 3
    }

    override fun back(): Boolean = when (level) {
        Level_.ACTIONS -> { level = Level_.BOOK; true }
        Level_.BOOK -> {
            rememberPosition()
            level = Level_.LIBRARY
            true
        }
        Level_.LIBRARY -> {
            // backing out of the library also cancels an in-flight open: the
            // completion must not yank the user back into a book they left
            openingId = null
            false
        }
    }

    private fun rememberPosition() {
        val b = book ?: return
        offsets[b.meta.id] = b.lines[docModel.topLine.coerceIn(0, b.lines.size - 1)].offset
    }

    override fun onRegistered(ctx: ShellServices) {
        services = ctx
    }

    override fun onActivate(ctx: ShellServices) {
        services = ctx
        if (library.isEmpty()) refreshLibrary()
    }

    override fun onFontScaleChanged(scale: Double) {
        relayoutOpenBook()
    }

    override fun onLayoutChanged() {
        // safe rect / height mode changed: the wrap width changed with it —
        // stale wraps would overrun the line rects (NO TRUNCATION, §2.2b)
        val b = book ?: return
        if (services?.docContentWidth() != b.width) relayoutOpenBook()
    }

    private fun relayoutOpenBook() {
        val b = book ?: return
        rememberPosition()
        val keep = offsets[b.meta.id] ?: 0
        bg.launch(Dispatchers.IO) {
            try {
                val re = layoutBook(b.meta, b.book)
                services?.runOnShell {
                    book = re
                    docModel.topLine = re.lineAtOffset(keep)
                    services?.requestRender(this@ReaderWindow)
                }
            } catch (e: Exception) {
                Log.e("reader", "re-layout failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ library
    private fun refreshLibrary() {
        libraryState = "loading"
        bg.launch(Dispatchers.IO) {
            var lib: List<BookMeta> = emptyList()
            var state: String
            try {
                lib = content.library()
                state = if (lib.isEmpty()) "no books found" else "ok"
            } catch (e: Exception) {
                Log.e("reader", "library scan failed", e)
                state = "library error: ${e.message}"
            }
            services?.runOnShell {
                library = lib
                libraryState = state
                services?.requestRender(this@ReaderWindow)
            }
        }
    }

    private fun paintLibRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val b = library.getOrNull(i) ?: return
        val maxW = r.w - 200
        drawFit(g, r.x + 32, r.y + 5, b.title, Level.BODY, fRow, maxW)
        drawRight(g, r.right - 24, r.y + 8, "${b.bytes / 1024}K", Level.DIM, fSmall)
        if (text.measure(b.title, fRow) > maxW) Icons.tri(g, r.right - 12, r.y + 11, 11, Level.DIM)
    }

    private fun paintLibLens(g: Gray8, r: Rect, i: Int) {
        val b = library.getOrNull(i) ?: return
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.READER, Level.HEAD)
        val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        val titleMax = r.w - 60
        drawFit(g, r.x + 44, r.y + 6, b.title, Level.HEAD, fB, titleMax)
        if (text.measure(b.title, fB) > titleMax) Icons.tri(g, r.right - 12, r.y + 11, 11, Level.DIM)
        val sub = listOf(b.author, "${b.bytes / 1024} KB")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val line2 = if (openingId == b.id) "opening..." else sub
        drawFit(g, r.x + 44, r.y + 34, line2, Level.BODY, fRow, r.w - 60)
    }

    private fun commitLibrary(i: Int) {
        val meta = library.getOrNull(i) ?: return
        if (openingId != null) return
        openingId = meta.id
        rememberPosition()
        services?.setOperation("fetching book")
        bg.launch(Dispatchers.IO) {
            try {
                // copy-on-open caching happens inside the provider (RemoteContent)
                val path = content.openBook(meta.id)
                services?.setOperation("laying out")
                val loaded = layoutBook(meta, Epub.load(path))
                services?.runOnShell {
                    if (openingId != meta.id) return@runOnShell   // user backed out — cancelled
                    openingId = null
                    book = loaded
                    docModel.topLine = loaded.lineAtOffset(offsets[meta.id] ?: 0)
                    level = Level_.BOOK
                    services?.setOperation("reading")
                    services?.requestRender(this@ReaderWindow)
                }
            } catch (e: Exception) {
                Log.e("reader", "open ${meta.title} failed", e)
                services?.notifyInternal("reader", "could not open ${meta.title}: ${e.message}")
                services?.runOnShell {
                    if (openingId == meta.id) openingId = null
                    services?.setOperation("idle")
                    services?.requestRender(this@ReaderWindow)
                }
            }
        }
    }

    /**
     * Wrap the whole book once per (width, scale) — off the shell loop, with
     * the op cell narrating. Line offsets advance CUMULATIVELY so they are
     * monotonic (binary-search-safe); an indexOf-based mapping drifted on
     * repeated prefixes (review round 1).
     */
    private fun layoutBook(meta: BookMeta, b: Epub.Book): Loaded {
        val width = (services?.docContentWidth() ?: 560).coerceAtLeast(120)
        val lines = ArrayList<Loaded.Line>(b.text.length / 40)
        var paraStart = 0
        for (para in b.text.split("\n\n")) {
            val heading = para.length < 60 && para == para.uppercase() && para.any { it.isLetter() }
            var consumed = 0
            for (l in Wrap.wrap(para, if (heading) fBodyB else fBody, text, width)) {
                lines.add(Loaded.Line(l, paraStart + consumed, heading))
                consumed += l.length + 1          // the collapsed space/break
            }
            lines.add(Loaded.Line("", paraStart + para.length, false))
            paraStart += para.length + 2          // the "\n\n" separator
        }
        if (lines.isNotEmpty() && lines.last().text.isEmpty()) lines.removeAt(lines.size - 1)
        val perPage = maxOf(1, (416 - 32) / lineH)
        return Loaded(meta, b, lines, perPage, width)
    }

    private fun paintBookLine(g: Gray8, b: Loaded, i: Int, r: Rect) {
        val line = b.lines.getOrNull(i) ?: return
        if (line.text.isEmpty()) return
        val f = if (line.heading) fBodyB else fBody
        val lv = if (line.heading) Level.HEAD else Level.BODY
        text.draw(g, (r.x + 16) / 4 * 4, (r.y + 2) / 2 * 2, line.text, f, lv)
    }

    // ------------------------------------------------------------------ actions
    private fun actions(): List<Pair<String, String>> {
        val b = book ?: return listOf("Library" to "back to the shelf")
        val pct = (docModel.topLine * 100 / maxOf(1, b.lines.size - 1))
        return listOf(
            "Resume" to "back to the page",
            "Jump forward" to "+10%",
            "Jump back" to "-10%",
            "Bookmark here" to "p.${b.pageOf(docModel.topLine)} · $pct%",
            "Go to bookmark" to if (bookmarkOffset >= 0) "saved" else "none set",
            "Library" to "back to the shelf",
        )
    }

    private fun paintActRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val (name, detail) = actions()[i]
        text.draw(g, (r.x + 32) / 4 * 4, (r.y + 7) / 2 * 2, name, fSmall, Level.DIM)
        drawFit(g, r.x + 240, r.y + 5, detail, Level.BODY, fRow, r.right - 24 - (r.x + 240))
    }

    private fun paintActLens(g: Gray8, r: Rect, i: Int) {
        val (name, detail) = actions()[i]
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.READER, Level.HEAD)
        text.draw(g, (r.x + 44) / 4 * 4, (r.y + 8) / 2 * 2, name, FontSpec(Face.SYSTEM, 18, bold = true), Level.HEAD)
        drawFit(g, r.x + 44, r.y + 34, detail, Level.BODY, fRow, r.w - 60)
    }

    private fun commitAction(i: Int) {
        val b = book ?: run { level = Level_.LIBRARY; return }
        when (actions()[i].first) {
            "Resume" -> level = Level_.BOOK
            "Jump forward" -> {
                docModel.topLine = (docModel.topLine + b.lines.size / 10).coerceAtMost(b.lines.size - 1)
                level = Level_.BOOK
            }
            "Jump back" -> {
                docModel.topLine = (docModel.topLine - b.lines.size / 10).coerceAtLeast(0)
                level = Level_.BOOK
            }
            "Bookmark here" -> {
                bookmarkOffset = b.lines[docModel.topLine.coerceIn(0, b.lines.size - 1)].offset
                level = Level_.BOOK
            }
            "Go to bookmark" -> {
                if (bookmarkOffset >= 0) docModel.topLine = b.lineAtOffset(bookmarkOffset)
                level = Level_.BOOK
            }
            "Library" -> {
                rememberPosition()
                level = Level_.LIBRARY
            }
        }
    }

    // ------------------------------------------------------------------ persist
    override fun saveState(): JsonObject = buildJsonObject {
        rememberPosition()
        put("level", level.name)
        put("libCursor", libModel.cursor)
        put("actCursor", actModel.cursor)
        put("bookId", book?.meta?.id ?: "")
        put("bookmark", bookmarkOffset)
        put("offsets", buildJsonObject { for ((k, v) in offsets) put(k, v) })
    }

    override fun restoreState(state: JsonObject) {
        libModel.cursor = state["libCursor"]?.jsonPrimitive?.intOrNull ?: 0
        actModel.cursor = state["actCursor"]?.jsonPrimitive?.intOrNull ?: 0
        bookmarkOffset = state["bookmark"]?.jsonPrimitive?.intOrNull ?: -1
        (state["offsets"] as? JsonObject)?.let { o ->
            for ((k, v) in o) v.jsonPrimitive.intOrNull?.let { offsets[k] = it }
        }
        val id = state["bookId"]?.jsonPrimitive?.contentOrNull
        val lvl = state["level"]?.jsonPrimitive?.contentOrNull
        // Restoring MODE, not just position (§9.1): reopen the book we were in,
        // at the exact LEVEL we were in (ACTIONS included — losing the level is
        // the Tmux failure class §9.1 #1 names).
        if (!id.isNullOrEmpty() && (lvl == "BOOK" || lvl == "ACTIONS")) {
            bg.launch(Dispatchers.IO) {
                try {
                    val lib = content.library()
                    val meta = lib.firstOrNull { it.id == id } ?: return@launch
                    val loaded = layoutBook(meta, Epub.load(content.openBook(id)))
                    services?.runOnShell {
                        library = lib
                        libraryState = "ok"
                        book = loaded
                        docModel.topLine = loaded.lineAtOffset(offsets[id] ?: 0)
                        level = if (lvl == "ACTIONS") Level_.ACTIONS else Level_.BOOK
                        services?.requestRender(this@ReaderWindow)
                    } ?: run {
                        // services not attached yet (restore precedes activation):
                        // apply directly — the shell loop has not started reading
                        library = lib
                        libraryState = "ok"
                        book = loaded
                        docModel.topLine = loaded.lineAtOffset(offsets[id] ?: 0)
                        level = if (lvl == "ACTIONS") Level_.ACTIONS else Level_.BOOK
                    }
                } catch (e: Exception) {
                    Log.e("reader", "restore of book $id failed — starting at the library", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun drawFit(g: Gray8, x: Int, y: Int, s: String, lv: Int, f: FontSpec, maxW: Int) {
        var str = s
        if (text.measure(str, f) > maxW) {
            var n = str.length
            while (n > 0 && text.measure(str.take(n), f) > maxW) n--
            str = str.take(n)
        }
        text.draw(g, x / 4 * 4, y / 2 * 2, str, f, lv)
    }

    private fun drawRight(g: Gray8, xRight: Int, y: Int, s: String, lv: Int, f: FontSpec) {
        text.draw(g, (xRight - text.measure(s, f)) / 4 * 4, y / 2 * 2, s, f, lv)
    }
}
