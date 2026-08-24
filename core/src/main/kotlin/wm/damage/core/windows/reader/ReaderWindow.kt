package wm.damage.core.windows.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
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
 * Persistence stores the reading position as a CHARACTER OFFSET, not a line
 * number, so it survives font-scale and width changes (§9.1: restore everything
 * the user could see or was doing — mode included).
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
    /** Reading position per book id, as CHARACTER offsets — remembered across
     *  book switches and restarts alike (§9.1). */
    private val offsets = HashMap<String, Int>()
    private var bookmarkOffset = -1
    private var openingId: String? = null
    private var services: ShellServices? = null

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
    ) {
        data class Line(val text: String, val offset: Int, val heading: Boolean)

        val pages: Int get() = maxOf(1, (lines.size + linesPerPage - 1) / linesPerPage)
        fun pageOf(line: Int): Int = line / linesPerPage + 1
        fun lineAtOffset(off: Int): Int {
            val i = lines.binarySearch { it.offset.compareTo(off) }
            return if (i >= 0) i else (-i - 2).coerceIn(0, lines.size - 1)
        }
    }

    // ------------------------------------------------------------------ contract
    override fun view(): WindowView = when (level) {
        Level_.LIBRARY -> WindowView.ListView(libModel, { libraryRows().size },
            ::paintLibRow, ::paintLibLens, ::commitLibrary)
        Level_.BOOK -> {
            val b = book
            if (b == null) {
                WindowView.ListView(libModel, { libraryRows().size }, ::paintLibRow, ::paintLibLens, ::commitLibrary)
            } else WindowView.DocView(docModel, { b.lines.size }, lineH,
                { g, i, r -> paintBookLine(g, b, i, r) }, { level = Level_.ACTIONS })
        }
        Level_.ACTIONS -> WindowView.ListView(actModel, { actions().size },
            ::paintActRow, ::paintActLens, ::commitAction)
    }

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
            progress = pct,
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
        Level_.LIBRARY -> false
    }

    private fun rememberPosition() {
        val b = book ?: return
        offsets[b.meta.id] = b.lines[docModel.topLine.coerceIn(0, b.lines.size - 1)].offset
    }

    override fun onActivate(ctx: ShellServices) {
        services = ctx
        if (library.isEmpty()) refreshLibrary()
    }

    override fun onFontScaleChanged(scale: Double) {
        // Re-wrap the open book at the new scale, preserving the reading
        // position by character offset — clipped overlong lines would violate
        // NO TRUNCATION otherwise.
        val b = book ?: return
        rememberPosition()
        val keep = b.lines[docModel.topLine.coerceIn(0, b.lines.size - 1)].offset
        bg.launch(Dispatchers.IO) {
            try {
                val re = layoutBook(b.meta, b.book)
                book = re
                docModel.topLine = re.lineAtOffset(keep)
            } catch (e: Exception) {
                Log.e("reader", "re-layout after font change failed", e)
            }
            services?.requestRender(this@ReaderWindow)
        }
    }

    // ------------------------------------------------------------------ library
    private fun libraryRows(): List<BookMeta> = library

    private fun refreshLibrary() {
        libraryState = "loading"
        bg.launch(Dispatchers.IO) {
            try {
                val lib = content.library()
                library = lib
                libraryState = if (lib.isEmpty()) "no books found" else "ok"
            } catch (e: Exception) {
                Log.e("reader", "library scan failed", e)
                libraryState = "library error: ${e.message}"
            }
            services?.requestRender(this@ReaderWindow)
        }
    }

    private fun paintLibRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val b = library.getOrNull(i) ?: return
        drawFit(g, r.x + 32, r.y + 5, b.title, Level.BODY, fRow, r.w - 200)
        val kb = "${b.bytes / 1024}K"
        drawRight(g, r.right - 24, r.y + 8, kb, Level.DIM, fSmall)
        if (text.measure(b.title, fRow) > r.w - 200) Icons.tri(g, r.right - 12, r.y + 11, 11, Level.DIM)
    }

    private fun paintLibLens(g: Gray8, r: Rect, i: Int) {
        val b = library.getOrNull(i) ?: return
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.READER, Level.HEAD)
        drawFit(g, r.x + 44, r.y + 6, b.title, Level.HEAD, FontSpec(Face.SYSTEM, 18, bold = true), r.w - 60)
        val sub = listOf(b.author, "${b.bytes / 1024} KB")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        drawFit(g, r.x + 44, r.y + 34, if (openingId == b.id) "opening..." else sub, Level.BODY, fRow, r.w - 60)
    }

    private fun commitLibrary(i: Int) {
        val meta = library.getOrNull(i) ?: return
        if (openingId != null) return
        openingId = meta.id
        services?.setOperation("fetching book")
        bg.launch(Dispatchers.IO) {
            try {
                // copy-on-open caching happens inside the provider (RemoteContent)
                val path = content.openBook(meta.id)
                services?.setOperation("laying out")
                val loaded = layoutBook(meta, Epub.load(path))
                rememberPosition()               // the book being left, if any
                book = loaded
                docModel.topLine = loaded.lineAtOffset(offsets[meta.id] ?: 0)
                level = Level_.BOOK
                services?.setOperation("reading")
            } catch (e: Exception) {
                Log.e("reader", "open ${meta.title} failed", e)
                services?.notifyInternal("reader", "could not open ${meta.title}: ${e.message}")
                services?.setOperation("idle")
            } finally {
                openingId = null
                services?.requestRender(this@ReaderWindow)
            }
        }
    }

    /** Wrap the whole book once per (width, scale) — a few seconds for a long
     *  novel, done off the shell loop with the op cell narrating. */
    private fun layoutBook(meta: BookMeta, b: Epub.Book): Loaded {
        val width = 640 - 2 * 16 - 12 - 32     // content minus rail minus margins
        val lines = ArrayList<Loaded.Line>(b.text.length / 40)
        var offset = 0
        for (para in b.text.split("\n\n")) {
            val heading = para.length < 60 && para == para.uppercase() && para.any { it.isLetter() }
            for (l in Wrap.wrap(para, if (heading) fBodyB else fBody, text, width)) {
                lines.add(Loaded.Line(l, offset + para.indexOf(l.take(12)).coerceAtLeast(0), heading))
            }
            lines.add(Loaded.Line("", offset + para.length, false))
            offset += para.length + 2
        }
        if (lines.isNotEmpty() && lines.last().text.isEmpty()) lines.removeAt(lines.size - 1)
        val perPage = maxOf(1, (416 - 32) / lineH)
        return Loaded(meta, b, lines, perPage)
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
        text.draw(g, (r.x + 240) / 4 * 4, (r.y + 5) / 2 * 2, detail, fRow, Level.BODY)
    }

    private fun paintActLens(g: Gray8, r: Rect, i: Int) {
        val (name, detail) = actions()[i]
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.READER, Level.HEAD)
        text.draw(g, (r.x + 44) / 4 * 4, (r.y + 8) / 2 * 2, name, FontSpec(Face.SYSTEM, 18, bold = true), Level.HEAD)
        text.draw(g, (r.x + 44) / 4 * 4, (r.y + 34) / 2 * 2, detail, fRow, Level.BODY)
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
            "Library" -> level = Level_.LIBRARY
        }
    }

    // ------------------------------------------------------------------ persist
    override fun saveState(): JsonObject = buildJsonObject {
        rememberPosition()
        put("level", level.name)
        put("libCursor", libModel.cursor)
        put("bookId", book?.meta?.id ?: "")
        put("bookmark", bookmarkOffset)
        put("offsets", buildJsonObject { for ((k, v) in offsets) put(k, v) })
    }

    override fun restoreState(state: JsonObject) {
        libModel.cursor = state["libCursor"]?.jsonPrimitive?.intOrNull ?: 0
        bookmarkOffset = state["bookmark"]?.jsonPrimitive?.intOrNull ?: -1
        (state["offsets"] as? JsonObject)?.let { o ->
            for ((k, v) in o) v.jsonPrimitive.intOrNull?.let { offsets[k] = it }
        }
        val id = state["bookId"]?.jsonPrimitive?.contentOrNull
        val lvl = state["level"]?.jsonPrimitive?.contentOrNull
        // Restoring MODE, not just position (§9.1): reopen the book we were in.
        if (!id.isNullOrEmpty() && (lvl == "BOOK" || lvl == "ACTIONS")) {
            bg.launch(Dispatchers.IO) {
                try {
                    val meta = content.library().firstOrNull { it.id == id } ?: return@launch
                    library = content.library()
                    libraryState = "ok"
                    val loaded = layoutBook(meta, Epub.load(content.openBook(id)))
                    book = loaded
                    docModel.topLine = loaded.lineAtOffset(offsets[id] ?: 0)
                    level = Level_.BOOK
                    services?.requestRender(this@ReaderWindow)
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
