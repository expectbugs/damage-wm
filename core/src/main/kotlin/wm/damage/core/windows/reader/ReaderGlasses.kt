package wm.damage.core.windows.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.Answer
import wm.damage.core.app.Expect
import wm.damage.core.app.Item
import wm.damage.core.app.Op
import wm.damage.core.app.SourceListener
import wm.damage.core.app.WindowSource
import wm.damage.core.app.WindowState
import wm.damage.core.content.BookMeta
import wm.damage.core.content.ContentProvider
import wm.damage.core.geom.Rect
import wm.damage.core.shell.AnchorCursor
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.IconPaint
import wm.damage.core.gfx.Level
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.Draw
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

/** The name the window had before its conversion, kept for the sites that name its type (the test sites, `--selfcheck`). */
typealias ReaderWindow = ReaderGlasses

/**
 * The compatibility factory (build S1-1 P4, the spec's §2 P4): Reader as it was built before its conversion — a
 * [ReaderCore] over [content], a source over a record of its own in memory, and the glasses' presenter over that. The
 * test sites, `--selfcheck` and the snapshot judge's first arrangement build Reader this way; the hosts build the core
 * on their own record (the PC's, or the phone's working copy).
 */
fun ReaderWindow(
    text: TextRasterizer,
    content: ContentProvider,
    bg: CoroutineScope,
    images: wm.damage.core.gfx.ImageDecoder? = null,
): ReaderGlasses {
    val core = ReaderCore(content, bg)
    return ReaderGlasses(text, core, wm.damage.core.app.LocalWindowSource(core, wm.damage.core.app.MemoryRecord(ReaderCore.ACTIONS)), bg, images)
}

/**
 * READER on the glasses — the ereader window's presenter. Three levels: library (list) -> book (document,
 * Alegreya per the locked §Type table, endless scroll) -> actions (list, the
 * §4.6 wrap-to-end pattern: tap in the document descends to actions).
 *
 * Threading: IO/layout work runs on [bg]; every STATE MUTATION it produces is
 * applied through [ShellServices.runOnShell], so the loop never observes a torn
 * book/topLine/level combination (review round 1). Reading positions are
 * per-book CHARACTER offsets — monotonic by construction, so restore lands on
 * the right line — and survive book switches, font rescales and restarts (§9.1).
 *
 * Build S1-1 P4 (`CONTRACT.md` §2.4, §2.6, §11.1): the shelf, each book's place and bookmark and the glasses' Reader
 * rows are Reader's [core]'s, read through [source] (the PC's record, or the phone's working copy). The fields below
 * stay as this presenter's optimistic state; what it changed goes to the source as ops at today's save points
 * ([saveSubState]: 2 s after input, on leaving the book, at a stop — dec. A.10). Another device's place for the open
 * book never moves the page (dec. A.2): an entry (a session start, the switcher, a restore, an open) takes it, and
 * meanwhile the actions level offers `Go there`. The glasses' row (level, cursors, the open book, the folder) stays
 * in the local `window.reader` keys in today's shape, unsynced (dec. A.11); those keys double as the copy an older
 * build reads after a downgrade, with the `conv` sub-record beside them saying what this build wrote (the migration's
 * ledger, `ReaderMigration`).
 */
class ReaderGlasses(
    private val text: TextRasterizer,
    val core: ReaderCore,
    val source: WindowSource,
    private val bg: CoroutineScope,
    /** Platform image decoding (2026-08-31, ebook images) — null (tests, a
     *  host without one) degrades every image to a visible placeholder line. */
    private val images: wm.damage.core.gfx.ImageDecoder? = null,
    /** This device's name in the record (`local` on the PC, `phone` on the phone): a write by it is its own echo. */
    private val device: String = wm.damage.core.app.Peer.LOCAL.device,
    /** "PC and phone on different builds for Reader" while the two builds differ (the phone), else null. */
    private val buildsLine: () -> String? = { null },
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) : DamageWindow("reader", "Reader", IconKind.READER) {
    private val content: ContentProvider get() = core.content

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
     *  (archive/REFINEMENT.md §3a, 2026-08-31): a folder is a row, tap descends,
     *  double-tap ascends. */
    private var folder = ""
    /** Lines per ring notch inside a book (archive/REFINEMENT.md §3b). Default 5 and
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
        // S1-1 P4: the record's place goes too (a removal expecting the revision this view sees); one the record does
        // not hold (a place only in the local copy) has nothing to remove there
        val seen = source.state().itemNamed(ReaderCore.placeItem(id))
        if (seen != null && !seen.gone) source.act(ReaderCore.resetDraft(id, seen.rev))
        sentOff.remove(id)
        expectFor[id] = Expect.Any
        confirmed[id] = JsonNull
        // resetting the OPEN book also closes it: saveState's rememberPosition
        // would re-track it on the next save, and a reset book should count as
        // a first open again (the chapter picker included)
        if (book?.meta?.id == id) {
            movedPlace = null
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
    /** S1-1 P4: one bookmark per book (C§12: the bookmark gains a book id; it was one offset for every book). */
    private val bookmarks = HashMap<String, Int>()
    private var openingId: String? = null

    // ---- S1-1 P4: this view's knowledge of the record (shell loop only) ----
    /** Per book, the place offset last sent to the source (or taken from it): a save point sends a place that moved
     *  since. Absent = nothing sent, so a place held only locally goes at the next save point. */
    private val sentOff = HashMap<String, Int>()
    /** Per book, what the next place op expects to find (C§2.4 position): the place this view last took or sent; the
     *  standing place after a loss, so reading on never loses twice. Absent = nothing there ([Expect.Value] null). */
    private val expectFor = HashMap<String, Expect>()
    /** Per book, the record's place this view knows to be its own or taken (JsonNull = known removed). A record place
     *  that is neither is another device's: an entry takes it, and the open book offers `Go there` until then. Kept in
     *  the `conv` sub-record, so it holds across a process restart. */
    private val confirmed = HashMap<String, JsonElement>()
    /** Another device's place for the OPEN book, not taken (dec. A.2): the actions level's `Go there` row. */
    private var movedPlace: ReaderAnchor? = null
    /** The glasses' Reader rows and bookmarks as last sent to the source (or taken from it). */
    private var sentRows: Triple<Int, Boolean, Int>? = null
    private val sentMarks = HashMap<String, Int>()
    private val libCursorAnchor = AnchorCursor()
    private val actCursorAnchor = AnchorCursor()
    /** §72 (2026-11-08): the one book restore in flight, and its generation. A newer restore
     *  cancels the older, and a result whose generation is not the latest is dropped. */
    @Volatile private var restoreJob: Job? = null
    private val restoreGen = java.util.concurrent.atomic.AtomicInteger(0)
    private var services: ShellServices? = null
    private var wrappedWidth = 0

    /** Reader content face: Alegreya (locked). The line box is MEASURED per
     *  layout ([lineBox]) — the face's ascent + descent plus [LINE_LEADING],
     *  on the 2 px grid: 30 rows at the design size (Alegreya's x-height
     *  normalisation lands the em at ~20 px, whose ink is 28 rows).
     *  ⚠ It was a constant 30 until review §28 #2, with a guard that REFUSED
     *  any layout whose ink exceeded it — and at 115 % the ink is 34, at
     *  130 % 36, so two of the four rungs of the size ladder could not open a
     *  book at all ("could not open …" on every title). Before that it was
     *  24, which chopped descenders (2026-08-31): the scroll path renders
     *  each line into a buffer exactly one box tall. */
    private val fBody = FontSpec(Face.READER, 17)
    private val fBodyB = FontSpec(Face.READER, 17, bold = true)
    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fRow = FontSpec(Face.SYSTEM, 18)

    /** The line box for the CURRENT metrics: the taller of the two weights'
     *  ascent + descent, plus the leading, rounded UP to the 2 px grid (§2.4
     *  r7 — a doc slide shifts whole line boxes by mode 9). */
    private fun lineBox(): Int {
        val ink = listOf(fBody, fBodyB).maxOf { f -> tx.metrics(f).let { it.ascent + it.descent } }
        return (ink + LINE_LEADING + 1) / 2 * 2
    }

    private class Loaded(
        val meta: BookMeta,
        val book: Epub.Book,
        val lines: List<Line>,
        val linesPerPage: Int,
        val width: Int,
        /** The font scale the wrap was measured at (round 4 #10). */
        val scale: Double,
        /** The line box this layout was measured for — the view, the image
         *  strips and the paint all read it from here, so a rescale mid-read
         *  cannot draw a new box over an old layout. */
        val lineH: Int,
        /** Decoded, scaled, 16-level-quantized ebook images (2026-08-31),
         *  each padded to whole line boxes so image LINES blit exact strips. */
        val imgs: List<Gray8> = emptyList(),
        /** [ReaderGlasses.layoutEpoch] when the wrap started. */
        val epoch: Int = 0,
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
            else WindowView.DocView(docModel, { b.lines.size }, b.lineH,
                { g, i, r -> paintBookLine(g, b, i, r) }, { level = Level_.ACTIONS },
                { scrollLines }, { scrollAccel },
                // §66: a Loaded is one layout — every line's pixels are fixed for its life, and a
                // relayout (width, scale, line box) or another book is a new object
                contentKey = { b })
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
        IconPaint.drawKind(g, IconKind.READER, r.x + 12, r.y + 10, 24, Level.HEAD)
        val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        drawFit(g, r.x + 44, r.y + 6, name, Level.HEAD, fB, r.w - 60)
        drawFit(g, r.x + 44, Draw.lineBelow(tx, fB, r.y + 6, r.y + 34), "tap to start here", Level.BODY, fRow, r.w - 60)
    }

    /**
     * 🔴 An EMPTY shelf still has one row (review §30, the Files defect one
     * window over): `ContentKit.paintList` returns immediately on a count of
     * zero, so the library level drew a cleared band and NOTHING else — no
     * message, no lens — for every state before the first scan lands, for a
     * library with no books, and for a scan that failed. Main's row said why
     * the whole time; the window did not.
     */
    private fun libView() = WindowView.ListView(libModel,
        { shelf().let { it.folders.size + it.books.size }.coerceAtLeast(1) },
        ::paintLibRow, ::paintLibLens, ::commitLibrary)

    private fun shelfIsEmpty(): Boolean = shelf().let { it.folders.isEmpty() && it.books.isEmpty() }

    /** What the placeholder says under "No books": the scan's own words. */
    private fun libEmptyWhy(): String = when {
        folder.isNotEmpty() -> "this folder is empty now · double-tap for the shelf"
        libraryState == "loading" -> "the shelf is being read · tap to look again"
        libraryState == "ok" -> "nothing in the library · tap to scan again"
        else -> "$libraryState · tap to scan again"
    }

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
        // S1-1 P4 (C§9.7 item 2): while the PC and this device run different builds for Reader, the detail says so
        // (each host then reads from its own copy) — in place of the author: the one thing to know
        val builds = buildsLine()
        val b = book ?: return Summary(
            when (libraryState) {
                "ok" -> "${library.size} books"
                "loading" -> "library loading"
                else -> libraryState
            },
            detail = builds ?: "",
            more = library.size > 1,
        )
        val page = b.pageOf(docModel.topLine)
        val pct = docModel.topLine.toDouble() / maxOf(1, b.lines.size - 1)
        return Summary(
            "${b.meta.title} · p.$page/${b.pages} · ${(pct * 100).toInt()}%",
            detail = builds ?: if (b.book.author.isEmpty()) b.meta.title else b.book.author,
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
        attach(ctx)
        // the Main lens read "library loading" from boot until the window
        // was first opened — a claim about a scan nobody had started (review
        // §28 #5). Scan now; the activation re-scan below is skipped while
        // this one is still in flight. §72: and so is this one — the keeper
        // registers again on EVERY start attempt, and the PC's standby used
        // to walk the whole shelf once per failed start
        if (!scanRunning()) refreshLibrary()
    }

    override fun onActivate(ctx: ShellServices, from: ActivationSource) {
        attach(ctx)
        // Adam's rule, 2026-09-04 (HOLDEM.md §3): from MAIN the window shows
        // its ROOT — the shelf — while the reading position stays exactly
        // where it is (it lives in the per-book sub-records, so tapping the
        // book again lands on the same line). Every other source resumes.
        // S1-1 P4 (dec. A.2): every other source is an entry, and takes another device's place for the open book
        if (from == ActivationSource.MAIN) goRoot() else enterOpenBook()
        // always re-scan on activation (round 3 R2): the scan is what keeps
        // the host-link banner truthful, and the shelf stays up while it runs
        // — unless one is in flight already (the registration scan)
        if (!scanRunning()) refreshLibrary()
    }

    /**
     * MAIN entry: up to the SHELF ROOT. The folder resets too — `levelDepth()`
     * counts a subfolder as a level below the library, so leaving one open
     * would land Main's entry at depth 2 and one double-tap would ascend the
     * folder instead of leaving the window. The open book and its reading
     * position are untouched; they live in the per-book sub-records.
     */
    private fun goRoot() {
        folder = ""
        libModel.cursor = 0
        if (level == Level_.LIBRARY) return
        if (level == Level_.BOOK || level == Level_.ACTIONS) rememberPosition()
        if (level == Level_.CHAPTERS && !chaptersReturnToBook) {
            // the FIRST-OPEN picker: no position exists yet, and back() from
            // here cancels the open — do exactly that
            book = null
            openingId = null
        }
        chaptersReturnToBook = false
        level = Level_.LIBRARY
        services?.setOperation("idle")
    }

    private var fontScale = 1.0
    /** Bumped every time the shell says the typography — or the layout metric (`HANDOFF.md` §73) —
     *  changed. A wrap captures it when it STARTS (`layoutBook` runs off the loop) and
     *  [ensureCurrentLayout] compares: width and scale alone do not move when the Cached text
     *  row or a face does, and a book whose open or restore was in flight then landed wrapped by
     *  the old measure, with nothing to re-derive it (`relayoutOpenBook` had no book yet). */
    @Volatile private var layoutEpoch = 0

    override fun onFontScaleChanged(scale: Double) {
        fontScale = scale
        layoutEpoch++
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
        if (b.width != (services?.docContentWidth() ?: b.width) || b.scale != fontScale || b.epoch != layoutEpoch) relayoutOpenBook()
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
                    // fix round 1, ruling 1.2: the new line's start is not a place he made — a relayout sends nothing
                    seat(re)
                    ensureCurrentLayout()
                    services?.requestRender(this@ReaderGlasses)
                }
            } catch (e: Exception) {
                Log.e("reader", "re-layout failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ library
    /** §72: the library scan in flight is the core's own job ([ReaderCore.scanRunning]): a completion lost at a
     *  session's end (drained behind a Shutdown) must not stop every later scan (the second review's finding). */
    private fun scanRunning(): Boolean = core.scanRunning()

    /** §72: the content source found a changed listing (a book added on the PC): scan again,
     *  unless a scan is already on its way. Any thread. */
    fun libraryChanged() {
        if (!scanRunning()) refreshLibrary()
    }

    private fun refreshLibrary() {
        libraryState = "loading"
        core.refreshLibrary()       // a failed re-scan keeps the shelf we have (the core's)
    }

    /** The core's scan result, on its thread: keyed (§72) — a listing supersedes the older ones; while no session
     *  is live only the latest is held. */
    private val onShelf: (List<BookMeta>, String) -> Unit = { lib, state ->
        services?.runOnShell("reader.library") {
            library = lib
            libraryState = state
            if (level == Level_.LIBRARY) libCursorAnchor.resolve(shelfIds())?.let { libModel.cursor = it }
            services?.requestRender(this@ReaderGlasses)
        }
    }

    /** The source's changes and answers, on the core executor: handed to the shell loop (keyed for changes — the state
     *  is whole, the newest suffices). */
    private val onSource = object : SourceListener {
        override fun changed(state: WindowState, keys: Set<String>) {
            services?.runOnShell("reader.source") {
                if (fromSource(source.state(), entry = false)) services?.requestRender(this@ReaderGlasses)
            }
        }

        override fun answered(op: Op, answer: Answer) {
            // fix round 2, ruling 1.2: a loss stays with this view until it has been said (one before the shell's
            // services, one whose post a Shutdown's drain dropped, one posted while no session runs)
            val s = synchronized(unsaid) {
                if (answer is Answer.Lost) unsaid[op.id] = op to answer
                services
            }
            if (s == null) {
                // fix round 2, ruling 1.7: a refusal for good is never told to no one (no notice is kept for it)
                if (answer is Answer.Refused && answer.code != Answer.RETRY && answer.code != Answer.GAP)
                    Log.e("reader", "the record refused ${op.action} of ${op.targets} before the shell started: ${answer.reason}")
                return
            }
            if (answer is Answer.Lost) sayUnsaid(s) else s.runOnShell { onAnswer(op, answer) }
        }
    }

    /** Losses handed to this view and not yet said (fix round 1, ruling 1.5; round 2, ruling 1.2). */
    private val unsaid = LinkedHashMap<wm.damage.core.app.OpId, Pair<Op, Answer>>()

    /** Posts every loss not yet said, now and at each session start ([attach]); the first run of one says it and only
     *  then tells the source, which stops keeping it; a later run finds it gone. */
    private fun sayUnsaid(s: ShellServices) {
        for ((op, a) in synchronized(unsaid) { unsaid.values.toList() }) s.runOnShell {
            if (synchronized(unsaid) { unsaid.remove(op.id) } != null) {
                onAnswer(op, a)
                source.said(op)
            }
        }
    }

    /** At the registration and at every session start (the shell registers its windows again at each). */
    private fun attach(ctx: ShellServices) {
        synchronized(unsaid) { services = ctx }
        sayUnsaid(ctx)
    }

    init {
        core.addShelfListener(onShelf)
        source.addListener(onSource)
    }

    /** At a stack's end: the core and the source outlive this presenter on the PC (one per process). */
    fun detach() {
        core.removeShelfListener(onShelf)
        source.removeListener(onSource)
    }

    /** The library level's row ids, as the cursor's anchor names them: folders first, then books (the paint order). */
    private fun shelfIds(): List<String> = shelf().let { s -> s.folders.map { "folder:$it" } + s.books.map { "book:${it.id}" } }

    private fun paintLibRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val s = shelf()
        if (shelfIsEmpty()) return                 // the lens carries the placeholder
        if (i < s.folders.size) {
            val name = s.folders[i]
            IconPaint.drawKind(g, IconKind.FILES, r.x + 4, r.y + 7, 18, Level.DIM)
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
        if (shelfIsEmpty()) {
            IconPaint.drawKind(g, IconKind.READER, r.x + 12, r.y + 10, 24, Level.HEAD)
            drawFit(g, r.x + 44, r.y + 6, "No books", Level.HEAD, fB, r.w - 60)
            drawFit(g, r.x + 44, Draw.lineBelow(tx, fB, r.y + 6, r.y + 34),
                libEmptyWhy(), Level.BODY, fRow, r.w - 60)
            return
        }
        if (i < s.folders.size) {
            val name = s.folders[i]
            IconPaint.drawKind(g, IconKind.FILES, r.x + 12, r.y + 10, 24, Level.HEAD)
            drawFit(g, r.x + 44, r.y + 6, name, Level.HEAD, fB, r.w - 60)
            drawFit(g, r.x + 44, Draw.lineBelow(tx, fB, r.y + 6, r.y + 34), "${countUnder(name)} books · tap to open", Level.BODY, fRow, r.w - 60)
            return
        }
        val b = s.books.getOrNull(i - s.folders.size) ?: return
        IconPaint.drawKind(g, IconKind.READER, r.x + 12, r.y + 10, 24, Level.HEAD)
        drawFit(g, r.x + 44, r.y + 6, b.title, Level.HEAD, fB, r.w - 60)
        val sub = listOf(b.author, "${b.bytes / 1024} KB")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        val line2 = if (openingId == b.id) "opening..." else sub
        drawFit(g, r.x + 44, Draw.lineBelow(tx, fB, r.y + 6, r.y + 34), line2, Level.BODY, fRow, r.w - 60)
    }

    private fun commitLibrary(i: Int) {
        val s = shelf()
        // the placeholder row: a tap asks again rather than doing nothing at
        // all (review §30 — no silent no-ops)
        if (shelfIsEmpty()) {
            // read the branch BEFORE mutating `folder`, or climbing out of a
            // top-level folder reports the other branch's message
            val climbing = folder.isNotEmpty()
            if (climbing) { folder = folder.substringBeforeLast('/', ""); libModel.cursor = 0 }
            else if (!scanRunning()) refreshLibrary()
            // short enough for the status bar's op cell to say it WHOLE: the
            // cell is 128 px and "reading the shelf again" arrived as
            // "reading the ▸" (review §30, seen live)
            services?.setOperation(if (climbing) "back to the shelf" else "rescanning")
            services?.requestRender(this)
            return
        }
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
                    // S1-1 P4: an open is an entry (dec. A.2): another device's place for this book is taken now
                    takeShared(meta.id)
                    movedPlace = null
                    book = loaded
                    // the book read most recently anywhere (C§12 open; arrival order, dec. A.9)
                    source.act(ReaderCore.openDraft(meta.id))
                    val saved = offsets[meta.id]
                    docModel.topLine = loaded.lineAtOffset(saved ?: 0)
                    seat(loaded)
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
                    services?.requestRender(this@ReaderGlasses)
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
                        services?.notifyInternal("reader", "could not open ${meta.title}: ${e.message}", answer = true)
                    }   // else: the user already abandoned this open — logged above
                    services?.requestRender(this@ReaderGlasses)
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
        val epoch = layoutEpoch          // before the first measure: a change during the wrap makes this layout stale
        // The line box follows the face at THIS scale (§28 #2). The descender
        // guard stays as a check on the arithmetic: a box that cannot hold
        // its own ink would be clipped by the scroll path's per-line buffers,
        // and that is refused LOUDLY rather than chopped on glass.
        val lineH = lineBox()
        for (f in listOf(fBody, fBodyB)) {
            val m = tx.metrics(f)
            if (m.ascent + m.descent > lineH) throw wm.damage.core.geom.LintError(
                "Reader line box $lineH px cannot hold ${f.face} ascent ${m.ascent} + descent ${m.descent} — " +
                    "descenders would be chopped (the box is measured; this is a metrics defect)")
        }
        val lines = ArrayList<Loaded.Line>(b.text.length / 40)
        val imgs = ArrayList<Gray8>()
        var paraStart = 0
        for (para in b.text.split("\n\n")) {
            // an image paragraph (2026-08-31): becomes whole-line strips so
            // scrolling, slides, damage and character offsets all just work
            val imgPath = Epub.imagePath(para)
            if (imgPath != null) {
                if (!layoutImage(b, imgPath, paraStart, width, lineH, lines, imgs)) {
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
        return Loaded(meta, b, lines, perPage, width, scale, lineH, imgs, epoch)
    }

    private var warnedNoDecoder = false

    /** Decode, grayscale, downscale to the text column, quantize to the 16
     *  levels (NO dithering — the standing rule) and pad to whole line boxes.
     *  False = no image (the caller adds a visible placeholder line). */
    private fun layoutImage(
        b: Epub.Book, zipPath: String, offset: Int, width: Int, lineH: Int,
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
            g.blit(im, Rect(0, line.imgRow * b.lineH, im.w, b.lineH), x, r.y)
            return
        }
        if (line.text.isEmpty()) return
        val f = if (line.heading) fBodyB else fBody
        val lv = if (line.heading) Level.HEAD else Level.BODY
        // baseline from the REAL metrics, centred in the box (2026-08-31 —
        // the hardcoded +2 assumed the glyphs fit, which is exactly what
        // chopped the descenders when they did not)
        val m = tx.metrics(f)
        val off = ((b.lineH - (m.ascent + m.descent)) / 2).coerceAtLeast(0)
        // the book's own prose is dynamic text too: substitute-not-tofu, and
        // FIT it — the wrap measured the raw string, so a substituted glyph of a
        // different width must be marked rather than pushed past the line rect
        // (review 2026-09-03). maxW is exactly the wrap width (docContentWidth).
        drawFit(g, r.x + 16, r.y + off, line.text, lv, f, r.w - 32)
    }

    // ------------------------------------------------------------------ actions
    private fun actions(): List<Pair<String, String>> {
        val b = book ?: return listOf("Library" to "back to the shelf")
        val pct = (docModel.topLine * 100 / maxOf(1, b.lines.size - 1))
        val bm = bookmarks[b.meta.id]
        // S1-1 P4 (dec. A.2): while another device's place for this book stands, untaken, `Go there` comes first
        val go = goThere(b)?.let { m -> listOf("Go there" to "p.${b.pageOf(b.lineAtOffset(m.offset))} · ${movedWho()}") }
        return (go ?: emptyList()) + listOf(
            "Resume" to "back to the page",
            "Chapters" to "${b.book.chapters.size}",
            "Jump forward" to "+10%",
            "Jump back" to "-10%",
            "Bookmark here" to "p.${b.pageOf(docModel.topLine)} · $pct%",
            "Go to bookmark" to if (bm != null && bm >= 0) "saved" else "none set",
            "Library" to "back to the shelf",
        )
    }

    /** Test and status introspection: the actions level's row labels, in order. */
    fun actionLabels(): List<String> = actions().map { it.first }

    /** Test introspection: the actions level's rows, label and detail. */
    internal fun actionRows(): List<Pair<String, String>> = actions()

    /** Test introspection: the width the open book was laid out for (0 = no book). */
    internal fun laidOutWidth(): Int = book?.width ?: 0

    /** Another device's place for the open book [b], when it is not where the page already is. */
    private fun goThere(b: Loaded): ReaderAnchor? =
        movedPlace?.takeIf { m -> b.lineAtOffset(m.offset) != docModel.topLine.coerceIn(0, b.lines.size - 1) }

    private var movedBy = ""
    private var movedAt = 0L

    private fun movedWho(): String = "${who(movedBy)} ${hhmm(movedAt)}"

    private fun paintActRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        // getOrNull, like every other row painter here: the action list SHRINKS
        // when the book closes, and a paint that throws leaves the content
        // half-drawn with no damage hint (the L1 class)
        val (name, detail) = actions().getOrNull(i) ?: return
        tx.draw(g, (r.x + 32) / 4 * 4, (r.y + 7) / 2 * 2, name, fSmall, Level.DIM)
        drawFit(g, r.x + 240, r.y + 5, detail, Level.BODY, fRow, r.right - 24 - (r.x + 240))
    }

    private fun paintActLens(g: Gray8, r: Rect, i: Int) {
        val (name, detail) = actions().getOrNull(i) ?: return
        IconPaint.drawKind(g, IconKind.READER, r.x + 12, r.y + 10, 24, Level.HEAD)
        val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        tx.draw(g, (r.x + 44) / 4 * 4, (r.y + 8) / 2 * 2, name, fB, Level.HEAD)
        drawFit(g, r.x + 44, Draw.lineBelow(tx, fB, r.y + 8, r.y + 34), detail, Level.BODY, fRow, r.w - 60)
    }

    private fun commitAction(i: Int) {
        val b = book ?: run { level = Level_.LIBRARY; return }
        when (actions().getOrNull(i)?.first) {
            "Go there" -> {
                // dec. A.2: committing it takes the shared place
                goThere(b)?.let { m -> take(b.meta.id, m, JsonPrimitive(m.format())) }
                movedPlace = null
                level = Level_.BOOK
            }
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
                // per book from S1-1 (C§12); it reaches the record at the next save point
                bookmarks[b.meta.id] = b.lines[docModel.topLine.coerceIn(0, b.lines.size - 1)].offset
                level = Level_.BOOK
            }
            "Go to bookmark" -> {
                bookmarks[b.meta.id]?.takeIf { it >= 0 }?.let { docModel.topLine = b.lineAtOffset(it) }
                level = Level_.BOOK
            }
            "Library" -> {
                rememberPosition()
                level = Level_.LIBRARY
                services?.setOperation("idle")
            }
        }
    }

    // ------------------------------------------------------------------ the record (S1-1 P4)
    /**
     * The record's state into this view: another device's write is taken where nothing on screen moves — every book
     * but the open one, the glasses' rows, the bookmarks — and for the open book only at an [entry]; meanwhile the
     * open book's moved place is offered (`Go there`). This device's own writes are only noted ([confirmed]): the
     * view holds them already, or newer. True when something drawn changed.
     */
    private fun fromSource(st: WindowState, entry: Boolean): Boolean {
        var drawn = false
        val openId = book?.meta?.id
        val p = wm.damage.core.app.Record.key(ReaderCore.WINDOW, "place.")
        for ((k, it) in st.items) if (k.startsWith(p)) {
            val id = k.removePrefix(p)
            drawn = placeFrom(id, it, take = entry || id != openId) || drawn
        }
        val b = wm.damage.core.app.Record.key(ReaderCore.WINDOW, "bookmark.")
        for ((k, it) in st.items) if (k.startsWith(b) && it.by != device) {
            val id = k.removePrefix(b)
            val v: JsonElement = if (it.gone) JsonNull else it.value
            if (sentMarkVal[id] == v) continue
            sentMarkVal[id] = v
            val a = ReaderAnchor.parse((v as? JsonPrimitive)?.takeIf { p2 -> p2.isString }?.content)
            if (a == null) bookmarks.remove(id) else bookmarks[id] = a.offset
            drawn = drawn || (id == openId && level == Level_.ACTIONS)
        }
        for (row in listOf(ReaderCore.SCROLL_LINES, ReaderCore.SCROLL_ACCEL, ReaderCore.SIZE)) {
            val it = st.itemNamed(row) ?: continue
            if (it.by == device || it.gone || sentRowVal[row] == it.value) continue
            sentRowVal[row] = it.value
            setRow(row, it.value)
        }
        return drawn
    }

    /** One book's place from the record. [take] = it may move this view (an entry, or a book not on screen). */
    private fun placeFrom(id: String, it: Item, take: Boolean): Boolean {
        val v: JsonElement = if (it.gone) JsonNull else it.value
        if (it.by == device) {
            confirmed[id] = v
            // the record holds this device's place: another device's place offered for the open book has gone from it
            // (R1 2.2's class, the focused read's S1). A loss after this offers it again (lost()).
            if (movedPlace == null || book?.meta?.id != id) return false
            movedPlace = null
            return level == Level_.ACTIONS
        }
        if (confirmed[id] == v) return false
        val openNow = book?.meta?.id == id
        if (!take) {
            // dec. A.2: the open book's page stays; the actions level offers the place
            val a = ReaderAnchor.parse((v as? JsonPrimitive)?.takeIf { p -> p.isString }?.content)
            movedPlace = a
            movedBy = it.by
            movedAt = it.at
            return level == Level_.ACTIONS
        }
        if (it.gone) {
            confirmed[id] = JsonNull
            offsets.remove(id)
            sentOff.remove(id)
            expectFor[id] = Expect.Any
            if (openNow) {
                // a reset elsewhere, met at an entry: the open book closes and counts as a first open again (the
                // local reset's semantics)
                movedPlace = null
                book = null
                docModel.topLine = 0
                level = Level_.LIBRARY
                services?.setOperation("idle")
                Log.i("reader", "progress of the open book was reset by ${who(it.by)} — back to the shelf")
                return true
            }
            return false
        }
        val a = ReaderAnchor.parse((v as? JsonPrimitive)?.takeIf { p -> p.isString }?.content) ?: run {
            Log.w("reader", "the record's place for $id is not a place ($v) — kept as it was")
            return false
        }
        take(id, a, v)
        return openNow
    }

    /** Take [a] (the record's [v]) as this view's place for [id]; the open book's page moves to it. */
    private fun take(id: String, a: ReaderAnchor, v: JsonElement) {
        confirmed[id] = v
        offsets[id] = a.offset
        sentOff[id] = a.offset
        expectFor[id] = Expect.Value(v)
        val b = book
        if (b != null && b.meta.id == id) {
            movedPlace = null
            val line = b.lineAtOffset(a.offset)
            if (line != docModel.topLine) docModel.topLine = line
            seat(b)
        }
    }

    /**
     * The open book [b]'s page was just set from its place by an entry (an open, a restore, a place taken): the place
     * becomes the start of the line the page shows — what the next save point would record — and that is not a change
     * this view makes, so it is not sent. Without this a taken place went back to the record a few characters earlier
     * at the next save point: a write no input made.
     */
    private fun seat(b: Loaded) {
        val id = b.meta.id
        val off = offsets[id] ?: return
        val lineOff = b.lines.getOrNull(docModel.topLine.coerceIn(0, b.lines.size - 1))?.offset ?: return
        if (lineOff == off) return
        if (sentOff[id] == off) sentOff[id] = lineOff
        offsets[id] = lineOff
    }

    /** An entry into [id] (an open, a restore): another device's place for it is taken now. */
    private fun takeShared(id: String) {
        val it = source.state().itemNamed(ReaderCore.placeItem(id)) ?: return
        placeFrom(id, it, take = true)
    }

    /** An entry while a book is open (the switcher, a restore, a session start on this instance). */
    private fun enterOpenBook() {
        val id = book?.meta?.id ?: return
        takeShared(id)
        movedPlace = null
    }

    private fun setRow(row: String, v: JsonElement) {
        val p = v as? JsonPrimitive ?: return
        when (row) {
            ReaderCore.SCROLL_LINES -> p.intOrNull?.let { scrollLines = it.coerceIn(1, 8) }
            ReaderCore.SCROLL_ACCEL -> p.booleanOrNull?.let { scrollAccel = it }
            ReaderCore.SIZE -> p.intOrNull?.let { h ->
                heightPref = if (h == 0) null else wm.damage.core.shell.ShellSettings.HEIGHTS.minByOrNull { kotlin.math.abs(it - h) }
            }
        }
    }

    private fun rowValues(): Map<String, JsonElement> = mapOf(
        ReaderCore.SCROLL_LINES to JsonPrimitive(scrollLines),
        ReaderCore.SCROLL_ACCEL to JsonPrimitive(scrollAccel),
        ReaderCore.SIZE to JsonPrimitive(heightPref ?: 0),
    )

    private val sentRowVal = HashMap<String, JsonElement>()
    private val sentMarkVal = HashMap<String, JsonElement>()

    /** What this view changed since it last sent, as ops (dec. A.10: at today's save points only). Each returns at
     *  once; the source answers later ([onAnswer]). */
    private fun sendChanges() {
        val st = source.state()
        val b = book
        for ((id, off) in offsets) {
            if (sentOff[id] == off) continue
            val at = if (b != null && b.meta.id == id) ReaderAnchor.at(b.book.text, off) else ReaderAnchor(off)
            source.act(ReaderCore.placeDraft(id, at, expectFor[id] ?: ReaderCore.expectSeen(st.itemNamed(ReaderCore.placeItem(id)))))
            sentOff[id] = off
            expectFor[id] = Expect.Value(JsonPrimitive(at.format()))
        }
        for ((id, off) in bookmarks) {
            val at = if (b != null && b.meta.id == id) ReaderAnchor.at(b.book.text, off) else ReaderAnchor(off)
            val v = JsonPrimitive(at.format())
            val sent = sentMarkVal[id]
            if (sent != null && ReaderAnchor.parse((sent as? JsonPrimitive)?.contentOrNull)?.offset == off) continue
            if (sent == null && ReaderCore.bookmarkOf(st, id)?.offset == off) {
                sentMarkVal[id] = st.itemNamed(ReaderCore.bookmarkItem(id))!!.value
                continue
            }
            val expect = sent?.let { Expect.Value(it) } ?: ReaderCore.expectSeen(st.itemNamed(ReaderCore.bookmarkItem(id)))
            source.act(ReaderCore.bookmarkDraft(id, at, expect))
            sentMarkVal[id] = v
        }
        for ((row, v) in rowValues()) {
            val sent = sentRowVal[row]
            if (sent == v) continue
            val held = st.itemNamed(row)
            if (sent == null && (held?.value ?: DEFAULT_ROWS[row]) == v) {
                sentRowVal[row] = v       // the record (or its default) already says so
                continue
            }
            source.act(ReaderCore.setDraft(row, v, sent?.let { Expect.Value(it) } ?: ReaderCore.expectSeen(held)))
            sentRowVal[row] = v
        }
    }

    /** An answer to one of this view's ops, on the shell loop (C§4.4). A loss is said under Reader's name as the
     *  answer to the input that made it (dec. A.2), with both values. */
    private fun onAnswer(op: Op, a: Answer) {
        if (op.window != ReaderCore.WINDOW) return
        val target = op.targets.firstOrNull() ?: return
        when (a) {
            is Answer.Lost -> for (loss in a.losses) lost(op, loss.item, loss)
            is Answer.Seen -> if (target.startsWith("place.")) confirmed[target.removePrefix("place.")] = op.result
            is Answer.Refused -> {
                // not taken (`retry`: the log could not be written; `gap`: numbered behind one that was not): the next
                // save point sends it again — the PC's own presenter has no queue to send it from. Fix round 2, ruling
                // 1.4: it changed nothing, so that send expects what this op expected (unless the view has taken or
                // sent another place since), and nothing is said. Any other refusal is for good, and said.
                forgetSent(target)
                if (a.code == Answer.RETRY || a.code == Answer.GAP) {
                    val id = target.removePrefix("place.")
                    if (target.startsWith("place.") && expectFor[id] == Expect.Value(op.result)) expectFor[id] = op.expect
                } else {
                    Log.e("reader", "the record refused ${op.action} of $target: ${a.reason}")
                    services?.notifyInternal("reader", "${op.action} not saved: ${a.reason}", answer = true)
                }
            }
            Answer.Sent -> {}
        }
    }

    private fun forgetSent(target: String) {
        when {
            target.startsWith("place.") -> sentOff.remove(target.removePrefix("place."))
            target.startsWith("bookmark.") -> sentMarkVal.remove(target.removePrefix("bookmark."))
            else -> sentRowVal.remove(target)
        }
    }

    private fun lost(op: Op, target: String, loss: wm.damage.core.app.Loss) {
        val standing = if (loss.gone) JsonNull else loss.standing
        val whoAt = "${who(loss.by)} (${hhmm(loss.at)})"
        var say = true
        val body = when {
            target.startsWith("place.") -> {
                val id = target.removePrefix("place.")
                val mine = placeWords(id, loss.mine)
                // fix round 2, ruling 1.3: said once per (book, standing place, this place) — the migrated op in flight
                // and a session start's own op of the same place lose with the same two
                say = placeLossesSaid.add(Triple(id, placeKey(standing), placeKey(loss.mine)))
                while (placeLossesSaid.size > 1_000) placeLossesSaid.remove(placeLossesSaid.first())
                // C§2.4: the view has been given the standing place, so its next input expects it and applies. Fix
                // round 2, ruling 1.1: it has not TAKEN it (the page stays, dec. A.2; a book not open keeps its place
                // until the change comes), so [confirmed] — and the ledger's `known` — keep what the view holds: an
                // entry or the change then takes the standing place, and a restart seeds nothing from it
                expectFor[id] = if (loss.gone) Expect.Any else Expect.Value(standing)
                if (op.action == ReaderCore.RESET) {
                    confirmed[id] = standing      // a lost reset takes the standing place (below)
                    ReaderAnchor.parse((standing as? JsonPrimitive)?.contentOrNull)?.let { s ->
                        offsets[id] = s.offset
                        sentOff[id] = s.offset
                    }
                    "progress not reset: ${who(loss.by)} read on (${hhmm(loss.at)})"
                } else if (loss.gone) {
                    "progress of this book was reset by $whoAt; this place, $mine, was not applied"
                } else if (standing == JsonNull) {
                    // the record holds no place for the book (a restored record): the next input sends it again
                    "this place, $mine, was not applied: the PC holds no place for this book now; reading on sends it again"
                } else {
                    if (book?.meta?.id == id) {
                        movedPlace = ReaderAnchor.parse((standing as? JsonPrimitive)?.contentOrNull)
                        movedBy = loss.by
                        movedAt = loss.at
                    }
                    "${who(loss.by)} read on to ${placeWords(id, standing)} (${hhmm(loss.at)}); this place, $mine, was not applied"
                }
            }
            target.startsWith("bookmark.") -> {
                val id = target.removePrefix("bookmark.")
                sentMarkVal[id] = standing
                val s = ReaderAnchor.parse((standing as? JsonPrimitive)?.contentOrNull)
                if (s == null) bookmarks.remove(id) else bookmarks[id] = s.offset
                "bookmark not applied: set by $whoAt"
            }
            else -> {
                sentRowVal[target] = standing
                if (!loss.gone) setRow(target, standing)
                "Reader ${target.removePrefix("glasses.")} not applied: $standing, set by $whoAt"
            }
        }
        if (say) {
            Log.w("reader", "not applied ($target): $body")
            services?.notifyInternal("reader", body, answer = true)
        } else Log.i("reader", "a loss of $target said already, not said again: $body")
        services?.requestRender(this)
    }

    /** The place losses said (ruling 1.3), the last 1,000. */
    private val placeLossesSaid = LinkedHashSet<Triple<String, String, String>>()

    /** A place's identity in [placeLossesSaid]: its character offset (an anchor's quoted text aside). */
    private fun placeKey(v: JsonElement): String = ReaderAnchor.parse((v as? JsonPrimitive)?.contentOrNull)?.offset?.toString() ?: v.toString()

    /** A place in words for a notice: its page in the open book, else its character offset. */
    private fun placeWords(id: String, v: JsonElement): String {
        val a = ReaderAnchor.parse((v as? JsonPrimitive)?.contentOrNull) ?: return "none"
        val b = book
        return if (b != null && b.meta.id == id) "p.${b.pageOf(b.lineAtOffset(a.offset))}" else "character ${a.offset}"
    }

    private fun who(device: String): String = when (device) {
        wm.damage.core.app.Peer.LOCAL.device -> "the PC"
        wm.damage.core.app.Peer.PHONE.device -> "the phone"
        ReaderMigration.DEVICE -> "an earlier build"
        "" -> "another device"
        // fix round 1, ruling 1.4: a browser's device id is `web-` and six hex digits (`web/Devices.kt`)
        else -> if (device.startsWith("web-")) "a browser" else device
    }

    private fun hhmm(at: Long): String =
        java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalTime().let { "%d:%02d".format(it.hour, it.minute) }

    // ------------------------------------------------------------------ persist
    /** The glasses' row (dec. A.11: local, unsynced, today's shape — also the copy an older build reads). */
    override fun saveState(): JsonObject = buildJsonObject {
        rememberPosition()
        put("level", level.name)
        put("libCursor", libModel.cursor)
        put("actCursor", actModel.cursor)
        // the shared index-to-anchor helper (C§11.1): the row each cursor rests on, beside the index
        put("libAnchor", AnchorCursor.saveAnchor(if (level == Level_.LIBRARY) shelfIds() else emptyList(), libModel.cursor))
        put("actAnchor", AnchorCursor.saveAnchor(if (level == Level_.ACTIONS) actions().map { it.first } else emptyList(), actModel.cursor))
        val openId = book?.meta?.id ?: ""
        put("bookId", openId)
        put("bookmark", bookmarks[openId] ?: -1)
        put("folder", folder)
        put("scrollLines", scrollLines)
        put("scrollAccel", scrollAccel)
        put("heightPref", heightPref ?: 0)   // 0 = global
        // per-book positions moved to SUB-RECORDS (§16.4a, 2026-09-01);
        // the whole-map is still written for an older build's restore
        put("offsets", buildJsonObject { for ((k, v) in offsets) put(k, v) })
    }

    /**
     * The save point (dec. A.10): what this view changed goes to the source as ops, then the local copy in today's
     * shape — one record per book (§16.4a) — and `conv`, the record of what this build wrote into that copy (the
     * migration's ledger: a later start offers again only what an older build wrote there since) with the record's
     * places this view knows as its own or taken.
     */
    override fun saveSubState(): Map<String, JsonObject> {
        rememberPosition()
        sendChanges()
        val out = LinkedHashMap<String, JsonObject>()
        for ((id, off) in offsets) out["book.$id"] = buildJsonObject { put("off", off) }
        out[ReaderMigration.LEDGER] = ReaderMigration.ledgerOf(
            offsets, book?.meta?.id ?: "", bookmarks[book?.meta?.id ?: ""] ?: -1, scrollLines, scrollAccel,
            heightPref ?: 0, confirmed)
        return out
    }

    /** A per-book record at a session start (the local copy; Reader's keys no longer sync, S1-1). An empty blob is
     *  the removal tombstone: the map entry goes, and the OPEN book closes so it counts as a first open again. The
     *  ledger brings back which record places this view knew as its own or taken (a process restart). */
    override fun restoreSubState(subKey: String, state: JsonObject) {
        if (subKey == ReaderMigration.LEDGER) {
            val st = source.state()
            for ((id, v) in ReaderMigration.knownOf(state)) if (id !in confirmed) {
                confirmed[id] = v
                // the next place expects what the record holds: what this view knew, when the record still holds it;
                // otherwise (another device moved it — an entry takes it — or a restored record lost it) it is not
                // seeded here, and the next save point sends the local place expecting the record's own
                val held = st.itemNamed(ReaderCore.placeItem(id))?.let { if (it.gone) JsonNull else it.value }
                if (held != v) continue
                ReaderAnchor.parse((v as? JsonPrimitive)?.contentOrNull)?.let { a ->
                    sentOff.putIfAbsent(id, a.offset)
                    expectFor.putIfAbsent(id, Expect.Value(v))
                }
            }
            return
        }
        if (!subKey.startsWith("book.")) return
        val id = subKey.removePrefix("book.")
        val off = state["off"]?.jsonPrimitive?.intOrNull
        if (off == null) {
            if (offsets.remove(id) != null && book?.meta?.id == id) {
                book = null
                docModel.topLine = 0
                level = Level_.LIBRARY
                services?.setOperation("idle")
                Log.i("reader", "progress of the open book was reset — back to the shelf")
            }
            return
        }
        offsets[id] = off
        val b = book
        // any level with this book loaded (review 2026-09-01 F9): parked at
        // ACTIONS/CHAPTERS, a stale topLine would be re-stamped by the next
        // rememberPosition and revert the newer progress
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
                // the core's lookup (S1-1 P4): §72, a cached listing that misses asks the source itself
                (core.resolve(target) as? ReaderCore.Resolved.Book)?.meta
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
                    services?.notifyInternal("reader", "couldn't open book $id — not in the library", answer = true)
                } else if (!startOpen(meta)) {
                    services?.notifyInternal("reader", "couldn't open ${meta.title} — another book is opening", answer = true)
                }
                services?.requestRender(this@ReaderGlasses)
            }
        }
        return true
    }

    override fun restoreState(state: JsonObject) {
        libModel.cursor = state["libCursor"]?.jsonPrimitive?.intOrNull ?: 0
        actModel.cursor = state["actCursor"]?.jsonPrimitive?.intOrNull ?: 0
        libCursorAnchor.restore(state["libAnchor"]?.jsonPrimitive?.contentOrNull)
        actCursorAnchor.restore(state["actAnchor"]?.jsonPrimitive?.contentOrNull)
        folder = state["folder"]?.jsonPrimitive?.contentOrNull ?: ""
        scrollLines = (state["scrollLines"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 8)
        scrollAccel = state["scrollAccel"]?.jsonPrimitive?.booleanOrNull ?: false
        heightPref = when (val h = state["heightPref"]?.jsonPrimitive?.intOrNull) {
            null -> // legacy key from the same-day earlier shape: true meant full panel
                if (state["fullHeight"]?.jsonPrimitive?.booleanOrNull == true) 480 else null
            0 -> null
            else -> wm.damage.core.shell.ShellSettings.HEIGHTS.minByOrNull { kotlin.math.abs(it - h) }
        }
        val id = state["bookId"]?.jsonPrimitive?.contentOrNull
        val lvl = state["level"]?.jsonPrimitive?.contentOrNull
        // the copy's one bookmark belongs to the book it names (C§12); the record's, when it holds one, wins below
        state["bookmark"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 && !id.isNullOrEmpty() }?.let { bookmarks.putIfAbsent(id!!, it) }
        // The legacy whole-map (`offsets`) is no longer read here: it is the migration's input (`ReaderMigration`,
        // dec. A.13), and the per-book records restored before this call hold every place.
        // S1-1 P4: a session start is an entry (dec. A.2): the record's state comes in, the open book's place included
        fromSource(source.state(), entry = true)
        if (library.isNotEmpty() && level == Level_.LIBRARY) libCursorAnchor.resolve(shelfIds())?.let { libModel.cursor = it }
        // P7 item 6 (OS-5): a session start on the SAME instance with this book open (every link end, Silent wake and
        // rebuild) ran the job below — the library, the file, the parse and the whole layout, on IO — and `apply` then
        // dropped the result ("superseded"). The open book is the restore; nothing to do (the style path's sibling, §73)
        if (book?.meta?.id == id && (level == Level_.BOOK || level == Level_.ACTIONS)) {
            movedPlace = null
            return
        }
        // Restoring MODE, not just position (§9.1): reopen the book we were in,
        // at the exact LEVEL we were in (ACTIONS included — losing the level is
        // the Tmux failure class §9.1 #1 names).
        if (!id.isNullOrEmpty() && (lvl == "BOOK" || lvl == "ACTIONS")) {
            // §72 (2026-11-08): ONE book restore in flight. A newer restore (the next session's,
            // a keeper retry's, a live sync apply's) cancels this one between its steps, and a
            // result that finished anyway is dropped by generation. Every failed start of the
            // PC's standby used to leave one more whole laid-out book queued behind a loop that
            // never ran (5.4 MB a time in the measurement; the PC's heap was gone in 845).
            val gen = restoreGen.incrementAndGet()
            restoreJob?.cancel()
            restoreJob = bg.launch(Dispatchers.IO) {
                try {
                    val lib = content.library()
                    ensureActive()
                    val meta = lib.firstOrNull { it.id == id } ?: run {
                        Log.w("reader", "restore: book $id is no longer in the library — starting at the shelf")
                        return@launch
                    }
                    val parsed = Epub.load(content.openBook(id))
                    ensureActive()
                    val loaded = layoutBook(meta, parsed)
                    ensureActive()
                    // apply-only-if-idle (#B8): a slow restore must never yank
                    // the user out of something they started doing meanwhile
                    fun apply() {
                        if (level != Level_.LIBRARY || openingId != null || book != null) {
                            Log.i("reader", "restore of $id superseded by user activity — dropped")
                            return
                        }
                        library = lib
                        libraryState = "ok"
                        // a restore is an entry (dec. A.2): the record's place for the book, when another device moved it
                        takeShared(id)
                        movedPlace = null
                        book = loaded
                        val saved = offsets[id]
                        if (saved == null && loaded.book.chapters.size >= 2) {
                            // the position vanished while we were reopening (a
                            // progress-RESET racing this restore — review
                            // 2026-09-01 F9): honor the reset
                            // — first-open chapter picker, not page 1 in BOOK
                            docModel.topLine = 0
                            level = Level_.CHAPTERS
                            chaptersReturnToBook = false
                            chapModel.cursor = 0
                            services?.setOperation("pick a chapter")
                        } else {
                            docModel.topLine = loaded.lineAtOffset(saved ?: 0)
                            seat(loaded)
                            level = if (lvl == "ACTIONS") Level_.ACTIONS else Level_.BOOK
                            if (level == Level_.ACTIONS) actCursorAnchor.resolve(actions().map { it.first })?.let { actModel.cursor = it }
                            services?.setOperation("reading")
                        }
                        ensureCurrentLayout()
                    }
                    // keyed (§72): while no session is live only the latest restore's result is held
                    services?.runOnShell("reader.restore") {
                        if (gen != restoreGen.get()) return@runOnShell   // a newer restore owns the book
                        apply()
                        services?.requestRender(this@ReaderGlasses)
                    } ?: run {
                        // services not attached yet (restore precedes activation):
                        // apply directly — the shell loop has not started reading
                        if (gen == restoreGen.get()) apply()
                    }
                } catch (e: CancellationException) {
                    throw e     // superseded by a newer restore: not a failure
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
     *  silent-clip paths (chapter and action rows).
     *
     *  Every string here is DYNAMIC — book titles, authors, chapter names out
     *  of an EPUB's own toc — so it goes through Draw.dynamic like Main, the
     *  notification box and every other surface: a glyph Alegreya or Clear Sans
     *  cannot draw becomes a visible '?' and one log line, never silent tofu
     *  (review 2026-09-03: the Reader was the one window still drawing raw). */
    private fun drawFit(g: Gray8, x: Int, y: Int, s: String, lv: Int, f: FontSpec, maxW: Int) {
        wm.damage.core.shell.Draw.fit(g, tx, x, y, wm.damage.core.shell.Draw.dynamic(tx, s, f), lv, f, maxW)
    }

    private fun drawRight(g: Gray8, xRight: Int, y: Int, s: String, lv: Int, f: FontSpec) {
        wm.damage.core.shell.Draw.right(g, tx, xRight, y, wm.damage.core.shell.Draw.dynamic(tx, s, f), lv, f)
    }

    companion object {
        /** Rows of leading in a line box beyond the face's own ink. */
        const val LINE_LEADING = 2

        /** The glasses' Reader rows when the record holds none (today's defaults). */
        private val DEFAULT_ROWS: Map<String, JsonElement> = mapOf(
            ReaderCore.SCROLL_LINES to JsonPrimitive(ReaderCore.DEFAULT_SCROLL_LINES),
            ReaderCore.SCROLL_ACCEL to JsonPrimitive(false),
            ReaderCore.SIZE to JsonPrimitive(0),
        )
    }
}
