package wm.damage.core.windows.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
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
import wm.damage.core.shell.HostSetting
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
    /** Platform image decoding (2026-08-31, ebook images) — null (tests, a
     *  host without one) degrades every image to a visible placeholder line. */
    private val images: wm.damage.core.gfx.ImageDecoder? = null,
) : DamageWindow("reader", "Reader", IconKind.READER) {

    /** Every measure/draw goes through the per-app style (Style.kt) — wrap
     *  and render agree by construction. */
    private val tx = styledText(text)

    private enum class Level_ { LIBRARY, CHAPTERS, BOOK, ACTIONS }

    private var level = Level_.LIBRARY
    private val libModel = ListModel()
    private val docModel = DocModel()
    private val actModel = ListModel()
    private val chapModel = ListModel()
    /** Where the chapter picker returns on back: false = it was the first-open
     *  picker (back cancels the open, to the library); true = it was opened
     *  from the actions level (back resumes the page). Double-tap ALWAYS
     *  backsteps — Adam, 2026-08-31: "start from the beginning" is row 0 of
     *  the list, never a gesture meaning. */
    private var chaptersReturnToBook = false

    private var library: List<BookMeta> = emptyList()
    private var libraryState = "loading"
    /** Current library folder ("" = shelf root) — folders are folders
     *  (REFINEMENT.md §3a, 2026-08-31): a folder is a row, tap descends,
     *  double-tap ascends. */
    private var folder = ""
    /** Lines per ring notch inside a book (REFINEMENT.md §3b). Default 5 and
     *  acceleration OFF since 2026-08-31: Adam tried the ramp on glass and
     *  called it too uneven — "lets just default the scrolling to 5 lines per
     *  notch - configurable". Both live in the Settings window's Reader
     *  category now (appSettings), not in the book's actions level. */
    private var scrollLines = 5
    private var scrollAccel = false

    /** §2 per-app height (revised 2026-08-31): null = "global" — the default
     *  for every per-app shadow of a global setting (Adam: "one of the
     *  per-app options (the default) should be 'use global setting'") — or
     *  one of ShellSettings.HEIGHTS to override it for Reader only. Adam's
     *  own state is migrated to 480 (he reads full-panel). */
    private var heightPref: Int? = null
    override val preferredHeight: Int? get() = heightPref

    /** The Settings window's "Reader" category (Adam, 2026-08-31: Settings
     *  organized by category — Global, then one per app). STABLE instances:
     *  the Settings window matches its staged row by identity. */
    private val settingsRows: List<HostSetting> by lazy {
        listOf(
            HostSetting("Scroll step", (1..8).map { "$it line${if (it == 1) "" else "s"}" },
                { "$scrollLines line${if (scrollLines == 1) "" else "s"}" },
                { scrollLines = it.takeWhile { c -> c.isDigit() }.toIntOrNull()?.coerceIn(1, 8) ?: 5 }),
            HostSetting("Scroll accel", listOf("off", "on"),
                { if (scrollAccel) "on" else "off" }, { scrollAccel = it == "on" }),
            HostSetting("Size", listOf("global") + wm.damage.core.shell.ShellSettings.HEIGHTS.map { "$it" },
                { heightPref?.toString() ?: "global" },
                { heightPref = it.toIntOrNull() }),   // "global" parses to null
            // Reset progress (2026-08-31): scroll through the books that hold
            // a saved position, tap to clear that one — the standard adjust
            // grammar (double-tap cancels). Options are computed at adjust
            // time, which is what the supplier form of HostSetting exists for.
            HostSetting("Reset progress", { listOf("cancel") + trackedTitles() },
                { "${offsets.size} book${if (offsets.size == 1) "" else "s"} tracked" },
                { choice -> if (choice != "cancel") resetProgress(choice) }),
        )
    }

    /** Titles of books with a saved reading position (id shown when a tracked
     *  book has left the library — resettable either way). */
    private fun trackedTitles(): List<String> =
        offsets.keys.map { id -> library.firstOrNull { it.id == id }?.title ?: id }.sorted()

    private fun resetProgress(title: String) {
        val id = library.firstOrNull { it.title == title }?.id
            ?: offsets.keys.firstOrNull { it == title }   // the id-shown fallback row
            ?: run { Log.w("reader", "reset progress: no tracked book called '$title'"); return }
        offsets.remove(id)
        // resetting the OPEN book also closes it: saveState's rememberPosition
        // would re-track it on the next save, and a reset book should count as
        // a first open again (the chapter picker included)
        if (book?.meta?.id == id) {
            bookmarkOffset = -1
            book = null
            docModel.topLine = 0
            level = Level_.LIBRARY
            services?.requestRender(this)
        }
        Log.i("reader", "progress reset for '$title'")
    }

    override fun appSettings(): List<HostSetting> = settingsRows
    private var book: Loaded? = null
    /** Reading position per book id, as CHARACTER offsets (§9.1). */
    private val offsets = HashMap<String, Int>()
    private var bookmarkOffset = -1
    private var openingId: String? = null
    private var services: ShellServices? = null
    private var wrappedWidth = 0

    /** Reader content face: Alegreya (locked). Line height 30 px — even (§2.4
     *  r7). ⚠ Was 24, which CHOPPED DESCENDERS (2026-08-31): Alegreya's
     *  x-height normalisation lands the em at ~20 px, whose ascent+descent is
     *  28 rows — five more than a 24 px box drawn 2 px down could hold, and
     *  the scroll path renders each line into a buffer exactly one box tall.
     *  The face and size stay exactly as they were; only the box grew. */
    private val fBody = FontSpec(Face.READER, 17)
    private val fBodyB = FontSpec(Face.READER, 17, bold = true)
    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val lineH = 30

    private class Loaded(
        val meta: BookMeta,
        val book: Epub.Book,
        val lines: List<Line>,
        val linesPerPage: Int,
        val width: Int,
        /** The font scale the wrap was measured at (round 4 #10). */
        val scale: Double,
        /** Decoded, scaled, 16-level-quantized ebook images (2026-08-31),
         *  each padded to whole line boxes so image LINES blit exact strips. */
        val imgs: List<Gray8> = emptyList(),
    ) {
        data class Line(
            val text: String, val offset: Int, val heading: Boolean,
            /** ≥0: this line is strip [imgRow] of [Loaded.imgs][img]. */
            val img: Int = -1, val imgRow: Int = 0,
        )

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
        Level_.CHAPTERS -> {
            if (book == null) libView()
            else WindowView.ListView(chapModel, { chapterRows().size },
                ::paintChapRow, ::paintChapLens, ::commitChapter)
        }
        Level_.BOOK -> {
            val b = book
            if (b == null) libView()
            else WindowView.DocView(docModel, { b.lines.size }, lineH,
                { g, i, r -> paintBookLine(g, b, i, r) }, { level = Level_.ACTIONS },
                { scrollLines }, { scrollAccel })
        }
        Level_.ACTIONS -> WindowView.ListView(actModel, { actions().size },
            ::paintActRow, ::paintActLens, ::commitAction)
    }

    /** Row 0 = "From the beginning", then the book's chapters (§ the toc). */
    private fun chapterRows(): List<String> {
        val b = book ?: return emptyList()
        return listOf("From the beginning") + b.book.chapters.map { it.title }
    }

    private fun commitChapter(i: Int) {
        val b = book ?: run { level = Level_.LIBRARY; return }
        docModel.topLine =
            if (i == 0) 0
            else b.lineAtOffset(b.book.chapters.getOrNull(i - 1)?.offset ?: 0)
        level = Level_.BOOK
        services?.setOperation("reading")
    }

    private fun paintChapRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val rows = chapterRows()
        val name = rows.getOrNull(i) ?: return
        drawFit(g, r.x + 32, r.y + 5, name, Level.BODY, fRow, r.w - 120)
        if (i > 0) drawRight(g, r.right - 24, r.y + 8, "$i", Level.DIM, fSmall)
    }

    private fun paintChapLens(g: Gray8, r: Rect, i: Int) {
        val name = chapterRows().getOrNull(i) ?: return
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.READER, Level.HEAD)
        drawFit(g, r.x + 44, r.y + 6, name, Level.HEAD, FontSpec(Face.SYSTEM, 18, bold = true), r.w - 60)
        drawFit(g, r.x + 44, r.y + 34, "tap to start here", Level.BODY, fRow, r.w - 60)
    }

    private fun libView() = WindowView.ListView(libModel,
        { shelf().let { it.folders.size + it.books.size } },
        ::paintLibRow, ::paintLibLens, ::commitLibrary)

    /** The current folder's view: subfolders first (sorted), then the books
     *  directly in it. O(library) per call — cheap at library scale. */
    private data class Shelf(val folders: List<String>, val books: List<BookMeta>)

    private fun shelf(): Shelf {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val subs = sortedSetOf<String>()
        val books = ArrayList<BookMeta>()
        for (b in library) {
            if (b.folder == folder) { books.add(b); continue }
            if (b.folder.startsWith(prefix)) subs.add(b.folder.removePrefix(prefix).substringBefore('/'))
        }
        return Shelf(subs.toList(), books)
    }

    /** Books anywhere under [sub] (for the folder row's count). */
    private fun countUnder(sub: String): Int {
        val full = if (folder.isEmpty()) sub else "$folder/$sub"
        return library.count { it.folder == full || it.folder.startsWith("$full/") }
    }

    override fun title(): String = when (level) {
        Level_.LIBRARY -> if (folder.isEmpty()) "library" else "library · $folder"
        Level_.CHAPTERS -> book?.let { "${it.meta.title} · chapters" } ?: "opening"
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

    private fun folderDepth(): Int = if (folder.isEmpty()) 0 else folder.count { it == '/' } + 1

    override fun levelDepth(): Int = when (level) {
        Level_.LIBRARY -> 1 + folderDepth()
        Level_.CHAPTERS -> 2 + folderDepth()
        Level_.BOOK -> 2 + folderDepth()
        Level_.ACTIONS -> 3 + folderDepth()
    }

    override fun back(): Boolean = when (level) {
        Level_.ACTIONS -> { level = Level_.BOOK; true }
        Level_.CHAPTERS -> {
            // double-tap always backsteps (Adam, 2026-08-31): from the
            // first-open picker it cancels the open back to the shelf; from
            // the actions route it resumes the page unchanged
            if (chaptersReturnToBook) {
                level = Level_.BOOK
            } else {
                book = null
                openingId = null
                level = Level_.LIBRARY
                services?.setOperation("idle")
            }
            true
        }
        Level_.BOOK -> {
            rememberPosition()
            level = Level_.LIBRARY
            services?.setOperation("idle")
            true
        }
        Level_.LIBRARY -> {
            if (folder.isNotEmpty()) {
                // ascend one folder; cursor rests on a harmless cell (§1.7)
                folder = folder.substringBeforeLast('/', "")
                libModel.cursor = 0
                true
            } else {
                // backing out of the library also cancels an in-flight open: the
                // completion must not yank the user back into a book they left
                openingId = null
                false
            }
        }
    }

    private fun rememberPosition() {
        val b = book ?: return
        // no position exists while the first-open picker is up: recording one
        // would mark the book opened and skip the picker after a restart
        if (level == Level_.CHAPTERS && !chaptersReturnToBook) return
        offsets[b.meta.id] = b.lines[docModel.topLine.coerceIn(0, b.lines.size - 1)].offset
    }

    override fun onRegistered(ctx: ShellServices) {
        services = ctx
    }

    override fun onActivate(ctx: ShellServices) {
        services = ctx
        // always re-scan on activation (round 3 R2): the scan is what keeps
        // the host-link banner truthful, and the shelf stays up while it runs
        refreshLibrary()
    }

    private var fontScale = 1.0

    override fun onFontScaleChanged(scale: Double) {
        fontScale = scale
        relayoutOpenBook()
    }

    override fun onLayoutChanged() {
        // safe rect / height mode changed: the wrap width changed with it —
        // stale wraps would overrun the line rects (NO TRUNCATION, §2.2b)
        val b = book ?: return
        if (services?.docContentWidth() != b.width) relayoutOpenBook()
    }

    /** A layout that just landed was measured for an older width or scale
     *  (the setting moved while it was wrapping): re-wrap once more rather
     *  than draw lines that overrun their rects (round 4 #10). */
    private fun ensureCurrentLayout() {
        val b = book ?: return
        if (b.width != (services?.docContentWidth() ?: b.width) || b.scale != fontScale) relayoutOpenBook()
    }

    private var layoutGen = 0

    private fun relayoutOpenBook() {
        val b = book ?: return
        rememberPosition()
        val keep = offsets[b.meta.id] ?: 0
        val gen = ++layoutGen
        bg.launch(Dispatchers.IO) {
            try {
                val re = layoutBook(b.meta, b.book)
                services?.runOnShell {
                    // identity guard (#B8): a late completion must not replace a
                    // book the user has since switched to; and of two racing
                    // relayouts of the SAME book only the NEWEST request applies
                    // (round 3 R1: first-completion-wins kept the stale wrap)
                    if (book !== b || gen != layoutGen) return@runOnShell
                    book = re
                    // the CURRENT offset, not the pre-wrap capture: a synced
                    // position applied mid-wrap must survive the completion
                    // (review 2026-09-01 F8 — the stale capture then
                    // out-stamped the peer's progress via rememberPosition)
                    docModel.topLine = re.lineAtOffset(offsets[b.meta.id] ?: keep)
                    ensureCurrentLayout()
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
        val keep = library          // a failed re-scan keeps the shelf we have
        bg.launch(Dispatchers.IO) {
            var lib: List<BookMeta> = keep
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
        val s = shelf()
        if (i < s.folders.size) {
            val name = s.folders[i]
            Icons.draw(g, r.x + 4, r.y + 7, 18, 18, IconKind.FILES, Level.DIM)
            drawFit(g, r.x + 32, r.y + 5, name, Level.BODY, fRow, r.w - 200)
            drawRight(g, r.right - 24, r.y + 8, "${countUnder(name)}", Level.DIM, fSmall)
            return
        }
        val b = s.books.getOrNull(i - s.folders.size) ?: return
        // Draw.fit marks a cut itself (§16.11) — no second mark needed
        drawFit(g, r.x + 32, r.y + 5, b.title, Level.BODY, fRow, r.w - 200)
        drawRight(g, r.right - 24, r.y + 8, "${b.bytes / 1024}K", Level.DIM, fSmall)
    }

    private fun paintLibLens(g: Gray8, r: Rect, i: Int) {
        val s = shelf()
        val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        if (i < s.folders.size) {
            val name = s.folders[i]
            Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.FILES, Level.HEAD)
            drawFit(g, r.x + 44, r.y + 6, name, Level.HEAD, fB, r.w - 60)
            drawFit(g, r.x + 44, r.y + 34, "${countUnder(name)} books · tap to open", Level.BODY, fRow, r.w - 60)
            return
        }
        val b = s.books.getOrNull(i - s.folders.size) ?: return
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.READER, Level.HEAD)
        drawFit(g, r.x + 44, r.y + 6, b.title, Level.HEAD, fB, r.w - 60)
        val sub = listOf(b.author, "${b.bytes / 1024} KB")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val line2 = if (openingId == b.id) "opening..." else sub
        drawFit(g, r.x + 44, r.y + 34, line2, Level.BODY, fRow, r.w - 60)
    }

    private fun commitLibrary(i: Int) {
        val s = shelf()
        if (i < s.folders.size) {
            val name = s.folders[i]
            folder = if (folder.isEmpty()) name else "$folder/$name"
            libModel.cursor = 0            // cursor rest discipline (§1.7)
            services?.requestRender(this)
            return
        }
        val meta = s.books.getOrNull(i - s.folders.size) ?: return
        if (!startOpen(meta)) {
            // a tap while another book is opening must SAY so, not no-op
            // (R2#20f — the deep-link path already reports; this one didn't)
            services?.setOperation("still opening the previous book…")
        }
    }

    /** Open [meta] — the commit path and the §16.1 deep link share it.
     *  False = another open is in flight (the caller reports it; a silent
     *  no-op here made a deep link claim success — review 2026-09-01 F7). */
    private fun startOpen(meta: BookMeta): Boolean {
        if (openingId != null) return false
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
                    if (openingId != meta.id) {
                        // user backed out — cancelled. The op cell must not keep
                        // narrating a load that no longer matters (round 3 R3),
                        // but only if nothing NEWER is loading or reading
                        // (round 4 #9)
                        if (openingId == null && level == Level_.LIBRARY) services?.setOperation("idle")
                        return@runOnShell
                    }
                    openingId = null
                    book = loaded
                    val saved = offsets[meta.id]
                    docModel.topLine = loaded.lineAtOffset(saved ?: 0)
                    // FIRST open of a book with real chapters: pick where to
                    // start (2026-08-31). Row 0 is "From the beginning";
                    // double-tap backs out to the shelf, never a shortcut.
                    if (saved == null && loaded.book.chapters.size >= 2) {
                        level = Level_.CHAPTERS
                        chaptersReturnToBook = false
                        chapModel.cursor = 0
                        services?.setOperation("pick a chapter")
                    } else {
                        level = Level_.BOOK
                        services?.setOperation("reading")
                    }
                    ensureCurrentLayout()
                    services?.requestRender(this@ReaderWindow)
                }
            } catch (e: Exception) {
                Log.e("reader", "open ${meta.title} failed", e)
                // a cached copy whose BYTES cannot be read (a cut or damaged
                // file) is dropped so the next attempt refetches (round 3 R5);
                // a deterministic parse failure is kept — refetching the same
                // bytes cannot change the outcome (round 4 #2)
                if (e is java.io.IOException) {
                    try { content.invalidate(meta.id) } catch (i: Exception) {
                        Log.w("reader", "cache invalidate failed: ${i.message}")
                    }
                }
                services?.runOnShell {
                    if (openingId == meta.id) {
                        openingId = null
                        services?.setOperation("idle")
                        services?.notifyInternal("reader", "could not open ${meta.title}: ${e.message}")
                    }   // else: the user already abandoned this open — logged above
                    services?.requestRender(this@ReaderWindow)
                }
            }
        }
        return true
    }

    /**
     * Wrap the whole book once per (width, scale) — off the shell loop, with
     * the op cell narrating. Line offsets advance CUMULATIVELY so they are
     * monotonic (binary-search-safe); an indexOf-based mapping drifted on
     * repeated prefixes (review round 1).
     */
    private fun layoutBook(meta: BookMeta, b: Epub.Book): Loaded {
        val width = (services?.docContentWidth() ?: 560).coerceAtLeast(120)
        val scale = fontScale
        // The descender guard (2026-08-31): a document face whose vertical
        // extent exceeds its line box gets clipped by the scroll path's
        // per-line buffers — LOUDLY refuse the layout instead of chopping
        // glyphs on glass. Checked for both weights at the current scale.
        for (f in listOf(fBody, fBodyB)) {
            val m = tx.metrics(f)
            if (m.ascent + m.descent > lineH) throw wm.damage.core.geom.LintError(
                "Reader line box $lineH px cannot hold ${f.face} ascent ${m.ascent} + descent ${m.descent} — " +
                    "descenders would be chopped (grow lineH or shrink the face)")
        }
        val lines = ArrayList<Loaded.Line>(b.text.length / 40)
        val imgs = ArrayList<Gray8>()
        var paraStart = 0
        for (para in b.text.split("\n\n")) {
            // an image paragraph (2026-08-31): becomes whole-line strips so
            // scrolling, slides, damage and character offsets all just work
            val imgPath = Epub.imagePath(para)
            if (imgPath != null) {
                if (!layoutImage(b, imgPath, paraStart, width, lines, imgs)) {
                    lines.add(Loaded.Line("[image: ${imgPath.substringAfterLast('/')}]", paraStart, false))
                }
                lines.add(Loaded.Line("", paraStart + para.length, false))
                paraStart += para.length + 2
                continue
            }
            val heading = para.length < 60 && para == para.uppercase() && para.any { it.isLetter() }
            // Walk the source as the wrapped lines consume it. A soft break eats
            // one ' ' (or one '\n' at a sub-paragraph boundary); a HARD break
            // inside an oversize word eats nothing — the old `+1 per line` drifted
            // past the paragraph end on hard breaks and broke the monotonicity
            // the binary search depends on (review round 2 #B10).
            var pos = 0
            for (l in Wrap.wrap(para, if (heading) fBodyB else fBody, tx, width)) {
                while (pos < para.length && !para.startsWith(l, pos) &&
                    (para[pos] == ' ' || para[pos] == '\n')) pos++
                lines.add(Loaded.Line(l, paraStart + minOf(pos, para.length), heading))
                pos += l.length
            }
            lines.add(Loaded.Line("", paraStart + para.length, false))
            paraStart += para.length + 2          // the "\n\n" separator
        }
        if (lines.isNotEmpty() && lines.last().text.isEmpty() && lines.last().img < 0)
            lines.removeAt(lines.size - 1)
        // page numbers count against the LIVE content height (§2.2b: nothing
        // hardcodes a 480-derived number) — 384 is only the no-services default
        val perPage = maxOf(1, (services?.docContentHeight() ?: 384) / lineH)
        return Loaded(meta, b, lines, perPage, width, scale, imgs)
    }

    private var warnedNoDecoder = false

    /** Decode, grayscale, downscale to the text column, quantize to the 16
     *  levels (NO dithering — the standing rule) and pad to whole line boxes.
     *  False = no image (the caller adds a visible placeholder line). */
    private fun layoutImage(
        b: Epub.Book, zipPath: String, offset: Int, width: Int,
        lines: MutableList<Loaded.Line>, imgs: MutableList<Gray8>,
    ): Boolean {
        val dec = images ?: run {
            if (!warnedNoDecoder) {
                warnedNoDecoder = true
                Log.w("reader", "no image decoder on this host — ebook images shown as placeholders")
            }
            return false
        }
        val bytes = b.images[zipPath] ?: return false      // Epub logged why
        val d = dec.decode(bytes) ?: run {
            Log.w("reader", "image '$zipPath' did not decode — placeholder shown")
            return false
        }
        if (d.w <= 0 || d.h <= 0) return false
        val maxW = ((width - 32).coerceAtLeast(16) / 4) * 4
        val maxH = 832                                     // ~2 full-height screens
        val s = minOf(1.0, maxW.toDouble() / d.w, maxH.toDouble() / d.h)
        val w = (((d.w * s).toInt()).coerceAtLeast(4) / 4) * 4
        val hInk = (((d.h * s).toInt()).coerceAtLeast(2) / 2) * 2
        val rows = (hInk + lineH - 1) / lineH
        val g8 = Gray8(w, rows * lineH)                    // pad rows stay level 0
        boxSample(d, g8, w, hInk)
        imgs.add(g8)
        val idx = imgs.size - 1
        for (r in 0 until rows) lines.add(Loaded.Line("", offset, false, img = idx, imgRow = r))
        return true
    }

    /** Box-average downscale + luminance is already gray at the seam; each
     *  output pixel lands ON a 16-level value so the compositor's diff sees
     *  stable pixels. */
    private fun boxSample(d: wm.damage.core.gfx.ImageDecoder.Decoded, out: Gray8, w: Int, h: Int) {
        for (y in 0 until h) {
            val sy0 = y * d.h / h
            val sy1 = maxOf(sy0 + 1, (y + 1) * d.h / h)
            for (x in 0 until w) {
                val sx0 = x * d.w / w
                val sx1 = maxOf(sx0 + 1, (x + 1) * d.w / w)
                var sum = 0
                var n = 0
                for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                    sum += d.gray[sy * d.w + sx].toInt() and 0xFF
                    n++
                }
                out[x, y] = ((sum / n + 8) / 17).coerceAtMost(15) * 17
            }
        }
    }

    private fun paintBookLine(g: Gray8, b: Loaded, i: Int, r: Rect) {
        val line = b.lines.getOrNull(i) ?: return
        if (line.img >= 0) {
            val im = b.imgs.getOrNull(line.img) ?: return
            val x = ((r.x + (r.w - im.w).coerceAtLeast(0) / 2) / 4) * 4
            g.blit(im, Rect(0, line.imgRow * lineH, im.w, lineH), x, r.y)
            return
        }
        if (line.text.isEmpty()) return
        val f = if (line.heading) fBodyB else fBody
        val lv = if (line.heading) Level.HEAD else Level.BODY
        // baseline from the REAL metrics, centred in the box (2026-08-31 —
        // the hardcoded +2 assumed the glyphs fit, which is exactly what
        // chopped the descenders when they did not)
        val m = tx.metrics(f)
        val off = ((lineH - (m.ascent + m.descent)) / 2).coerceAtLeast(0)
        tx.draw(g, (r.x + 16) / 4 * 4, (r.y + off) / 2 * 2, line.text, f, lv)
    }

    // ------------------------------------------------------------------ actions
    private fun actions(): List<Pair<String, String>> {
        val b = book ?: return listOf("Library" to "back to the shelf")
        val pct = (docModel.topLine * 100 / maxOf(1, b.lines.size - 1))
        return listOf(
            "Resume" to "back to the page",
            "Chapters" to "${b.book.chapters.size}",
            "Jump forward" to "+10%",
            "Jump back" to "-10%",
            "Bookmark here" to "p.${b.pageOf(docModel.topLine)} · $pct%",
            "Go to bookmark" to if (bookmarkOffset >= 0) "saved" else "none set",
            "Library" to "back to the shelf",
        )
    }

    private fun paintActRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val (name, detail) = actions()[i]
        tx.draw(g, (r.x + 32) / 4 * 4, (r.y + 7) / 2 * 2, name, fSmall, Level.DIM)
        drawFit(g, r.x + 240, r.y + 5, detail, Level.BODY, fRow, r.right - 24 - (r.x + 240))
    }

    private fun paintActLens(g: Gray8, r: Rect, i: Int) {
        val (name, detail) = actions()[i]
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.READER, Level.HEAD)
        tx.draw(g, (r.x + 44) / 4 * 4, (r.y + 8) / 2 * 2, name, FontSpec(Face.SYSTEM, 18, bold = true), Level.HEAD)
        drawFit(g, r.x + 44, r.y + 34, detail, Level.BODY, fRow, r.w - 60)
    }

    private fun commitAction(i: Int) {
        val b = book ?: run { level = Level_.LIBRARY; return }
        when (actions()[i].first) {
            "Resume" -> level = Level_.BOOK
            "Chapters" -> {
                level = Level_.CHAPTERS
                chaptersReturnToBook = true   // back resumes the page
                chapModel.cursor = 0
            }
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
                services?.setOperation("idle")
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
        put("folder", folder)
        put("scrollLines", scrollLines)
        put("scrollAccel", scrollAccel)
        put("heightPref", heightPref ?: 0)   // 0 = global
        // per-book positions moved to SUB-RECORDS (§16.4a, 2026-09-01):
        // `window.reader.book.<id>` each — two devices reading DIFFERENT books
        // no longer clobber each other's positions under LWW.
        // ⚠ TRANSITIONAL: the legacy whole-map still writes so a peer on the
        // pre-sub-record build (the installed 0.15 APK) and this build keep
        // ONE main-record shape — dropping it mid-fleet made every save
        // re-stamp against the other side's shape forever (review 2026-09-01
        // F3). Remove once the phone runs ≥0.16.
        put("offsets", buildJsonObject { for ((k, v) in offsets) put(k, v) })
    }

    /** §16.4a: one record per book — the sub-key is the stable book id. */
    override fun saveSubState(): Map<String, JsonObject> {
        rememberPosition()
        return offsets.entries.associate { (id, off) ->
            "book.$id" to buildJsonObject { put("off", off) }
        }
    }

    /** A per-book record — at restore or LIVE from sync. An empty blob is the
     *  removal tombstone (a progress reset on the peer): mirror the local
     *  reset's semantics — the map entry goes, and the OPEN book closes so it
     *  counts as a first open again. A live position for the OPEN book moves
     *  the page — the "continue on the next device" experience (§16.4). */
    override fun restoreSubState(subKey: String, state: JsonObject) {
        if (!subKey.startsWith("book.")) return
        val id = subKey.removePrefix("book.")
        val off = state["off"]?.jsonPrimitive?.intOrNull
        if (off == null) {
            if (offsets.remove(id) != null && book?.meta?.id == id) {
                bookmarkOffset = -1
                book = null
                docModel.topLine = 0
                level = Level_.LIBRARY
                services?.setOperation("idle")
                Log.i("reader", "progress of the open book was reset on the peer — back to the shelf")
            }
            return
        }
        offsets[id] = off
        val b = book
        // any level with this book loaded (review 2026-09-01 F9): parked at
        // ACTIONS/CHAPTERS, a stale topLine would be re-stamped by the next
        // rememberPosition and revert the peer's newer progress
        if (b != null && b.meta.id == id) {
            val line = b.lineAtOffset(off)
            if (line != docModel.topLine) docModel.topLine = line
        }
    }

    /** §16.1 deep link: "book:<id>" opens that library book (Files hand-off is
     *  the first consumer). Resolution is ASYNC (the library may need a scan);
     *  a failed resolution surfaces through the same loud open-failure path
     *  commitLibrary uses — never silently. */
    override fun open(target: String): Boolean {
        if (!target.startsWith("book:")) return false
        val id = target.removePrefix("book:")
        if (id.isEmpty()) return false
        // an in-flight open refuses HONESTLY (false → the shell reports it);
        // claiming success while doing nothing was review 2026-09-01 F7
        library.firstOrNull { it.id == id }?.let { return startOpen(it) }
        // library not loaded (a hand-off right after activation): resolve it
        // off-loop, then open — guarded like every async completion here
        if (openingId != null) return false
        openingId = id
        services?.setOperation("finding book")
        bg.launch(Dispatchers.IO) {
            val meta = try {
                content.library().firstOrNull { it.id == id }
            } catch (e: Exception) {
                Log.e("reader", "deep-link library scan failed", e)
                null
            }
            services?.runOnShell {
                if (openingId != id) {
                    // abandoned scan: the op cell must not keep saying
                    // "finding book" (R3s#10) — same shape as startOpen's
                    if (openingId == null && level == Level_.LIBRARY) services?.setOperation("idle")
                    return@runOnShell
                }
                openingId = null
                if (meta == null) {
                    services?.setOperation("idle")
                    services?.notifyInternal("reader", "couldn't open book $id — not in the library")
                } else if (!startOpen(meta)) {
                    services?.notifyInternal("reader", "couldn't open ${meta.title} — another book is opening")
                }
                services?.requestRender(this@ReaderWindow)
            }
        }
        return true
    }

    /** Transitional (remove with the legacy offsets field at phone ≥ 0.16):
     *  marks the legacy-map merge as live-authoritative — see R2#7 below. */
    private var liveMapApply = false

    override fun restoreStateLive(state: JsonObject) {
        liveMapApply = true
        try { restoreState(state) } finally { liveMapApply = false }
    }

    override fun restoreState(state: JsonObject) {
        libModel.cursor = state["libCursor"]?.jsonPrimitive?.intOrNull ?: 0
        actModel.cursor = state["actCursor"]?.jsonPrimitive?.intOrNull ?: 0
        bookmarkOffset = state["bookmark"]?.jsonPrimitive?.intOrNull ?: -1
        folder = state["folder"]?.jsonPrimitive?.contentOrNull ?: ""
        scrollLines = (state["scrollLines"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 8)
        scrollAccel = state["scrollAccel"]?.jsonPrimitive?.booleanOrNull ?: false
        heightPref = when (val h = state["heightPref"]?.jsonPrimitive?.intOrNull) {
            null -> // legacy key from the same-day earlier shape: true meant full panel
                if (state["fullHeight"]?.jsonPrimitive?.booleanOrNull == true) 480 else null
            0 -> null
            else -> wm.damage.core.shell.ShellSettings.HEIGHTS.minByOrNull { kotlin.math.abs(it - h) }
        }
        // legacy migration (pre-§16.4a stores and older peers). At BOOT the
        // whole-map key seeds only books the sub-records have not already
        // restored (same-epoch dual-write: sub-records are the newer
        // authority). On a LIVE apply (R2#7) the incoming map already WON
        // last-write-wins for the main key, and an old peer writes only the
        // map — skip-if-present let a stale local entry beat the peer's newer
        // position and then re-stamp it backwards. Accepted residual: with
        // two ACTIVE shells an old peer's map save can carry a book it did
        // not touch (the Sub#F4 dual-live boundary); dies with the
        // transitional field at phone ≥ 0.16.
        (state["offsets"] as? JsonObject)?.let { o ->
            for ((k, v) in o) if (liveMapApply || k !in offsets) {
                v.jsonPrimitive.intOrNull?.let { offsets[k] = it }
            }
            // the OPEN book's screen position re-seats like restoreSubState's
            // (R3d#1): without it, the next rememberPosition re-stamps the
            // STALE line over the just-applied newer offset and the
            // regression syncs fleet-wide
            if (liveMapApply) book?.let { b ->
                offsets[b.meta.id]?.let { off ->
                    val line = b.lineAtOffset(off)
                    if (line != docModel.topLine) docModel.topLine = line
                }
            }
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
                    val meta = lib.firstOrNull { it.id == id } ?: run {
                        Log.w("reader", "restore: book $id is no longer in the library — starting at the shelf")
                        return@launch
                    }
                    val loaded = layoutBook(meta, Epub.load(content.openBook(id)))
                    // apply-only-if-idle (#B8): a slow restore must never yank
                    // the user out of something they started doing meanwhile
                    fun apply() {
                        if (level != Level_.LIBRARY || openingId != null || book != null) {
                            Log.i("reader", "restore of $id superseded by user activity — dropped")
                            return
                        }
                        library = lib
                        libraryState = "ok"
                        book = loaded
                        val saved = offsets[id]
                        if (saved == null && loaded.book.chapters.size >= 2) {
                            // the position vanished while we were reopening (a
                            // synced progress-RESET tombstone racing this
                            // restore — review 2026-09-01 F9): honor the reset
                            // — first-open chapter picker, not page 1 in BOOK
                            docModel.topLine = 0
                            level = Level_.CHAPTERS
                            chaptersReturnToBook = false
                            chapModel.cursor = 0
                            services?.setOperation("pick a chapter")
                        } else {
                            docModel.topLine = loaded.lineAtOffset(saved ?: 0)
                            level = if (lvl == "ACTIONS") Level_.ACTIONS else Level_.BOOK
                            services?.setOperation("reading")
                        }
                        ensureCurrentLayout()
                    }
                    services?.runOnShell {
                        apply()
                        services?.requestRender(this@ReaderWindow)
                    } ?: run {
                        // services not attached yet (restore precedes activation):
                        // apply directly — the shell loop has not started reading
                        apply()
                    }
                } catch (e: Exception) {
                    Log.e("reader", "restore of book $id failed — starting at the library", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    /** Through the shared kit (§16.11, 2026-09-01): a cut is ADVERTISED with
     *  the drawn mark, never silent. The explicit tri calls some rows add on
     *  measured overflow stay (they mark at a custom spot); this closes the
     *  silent-clip paths (chapter and action rows). */
    private fun drawFit(g: Gray8, x: Int, y: Int, s: String, lv: Int, f: FontSpec, maxW: Int) {
        wm.damage.core.shell.Draw.fit(g, tx, x, y, s, lv, f, maxW)
    }

    private fun drawRight(g: Gray8, xRight: Int, y: Int, s: String, lv: Int, f: FontSpec) {
        wm.damage.core.shell.Draw.right(g, tx, xRight, y, s, lv, f)
    }
}
