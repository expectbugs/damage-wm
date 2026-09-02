package wm.damage.core.windows.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.content.LocalContent
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.IconNames
import wm.damage.core.gfx.IconPaint
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.ImageDecoder
import wm.damage.core.gfx.Level
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.DocModel
import wm.damage.core.shell.Draw
import wm.damage.core.shell.HostSetting
import wm.damage.core.shell.KeyboardSurface
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MenuSurface
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.WindowView
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.text.Wrap
import wm.damage.core.util.Log

/**
 * FILES — the file manager, Adam's chosen first conversion (2026-09-01).
 * Design settled in-session: G2CC's shape with the graphical wave — a
 * LOCATIONS root list (with capacity bars), a browser where **tap = context
 * menu with Open first** (uniform for every entry — two taps into a folder,
 * misfire-safe by §1.7), theme icons on every row, a thumbnail lens, in-app
 * viewers for text / images / PDF (dual-mode, auto-defaulted), the clipboard
 * slot for Copy/Cut→Paste-here, trash with Restore and an on-glass permanent
 * delete behind a double confirm, rename/mkdir via typed text, and "Open on
 * PC". G2CC `files.ts` read for facts only: the /proc/mounts rule, the
 * dirent lesson, the one-op-at-a-time rule, the stale-swap guards.
 *
 * Threading: providers are called off-loop ([bg]); every state mutation
 * applies through [ShellServices.runOnShell]; [navSeq] guards stale
 * completions; [opBusy] enforces one filesystem op at a time, loudly.
 */
class FilesWindow(
    private val text: TextRasterizer,
    private val provider: FilesProvider,
    private val bg: CoroutineScope,
    private val decoder: ImageDecoder? = null,
    /** Reader hand-off: paths under this dir resolve to library ids. Learned
     *  from the provider's Books location on refresh (the phone side has no
     *  local knowledge of the PC's path), seeded here for laptop-direct. */
    initialBooksDir: String = "",
) : DamageWindow("files", "Files", IconKind.FILES) {

    private var booksDir: String = initialBooksDir

    private val tx = styledText(text)

    private enum class Level_ { LOCATIONS, BROWSE, TRASH, VIEW }
    private enum class Sort { NAME, SIZE, MTIME }
    private enum class ClipVerb { COPY, CUT }
    private enum class NameFor { RENAME, MKDIR }

    private var level = Level_.LOCATIONS
    private val locModel = ListModel()
    private val browseModel = ListModel()
    private val trashModel = ListModel()
    private val docModel = DocModel()

    private var services: ShellServices? = null
    private var locations: List<FLocation> = emptyList()
    private var location: FLocation? = null
    private var cwd: String = ""
    private var entries: List<FEntry> = emptyList()
    private var listState = ""                       // "" ok · "listing…" · error text
    private var trashEntries: List<FTrashEntry> = emptyList()
    /** Ascend restores the parent's cursor (path → cursor). */
    private val browseStack = ArrayList<Pair<String, Int>>()

    private var sort = Sort.NAME
    private var showHidden = false
    private var clip: Pair<ClipVerb, String>? = null   // verb, absolute path
    private var nameArmed: Pair<NameFor, String>? = null
    /** §4.8 keyboard drafts kept across a cancel, per (purpose, target). */
    private val nameDrafts = HashMap<String, String>()

    /** One filesystem op at a time (G2CC rule) — a second request is refused
     *  loudly, never queued invisibly. */
    private var opBusy = false
    private var navSeq = 0
    /** Stats/PDF probes guard themselves WITHOUT bumping [navSeq] — bumping it
     *  stranded in-flight listings (review 2026-09-01 Fi#5). */
    private var sideSeq = 0
    private var statSeq = 0
    /** Ascend's restored cursor, re-applied when the listing lands (R2#20d). */
    private var pendingBrowseCursor: Int? = null
    /** A `path:` deep link's file name: the cursor lands on it when the
     *  listing arrives (Torrents → "Open in Files", 2026-09-01). */
    private var pendingSelectName: String? = null

    /** The Reader hand-off prefix and the title's ~-shortening both come from
     *  the HOST's own home (review Fi#15: the phone's local user.home mangled
     *  PC paths). Learned from the locations listing. */
    private var hostHome = ""

    /** A short-lived title notice (review Fi#11: consuming it on first read
     *  made every success message vanish within one chrome sync — the shell
     *  reads title() every frame). A scheduled UI display window, the §4.5
     *  grace's class — nothing waits on it. */
    private var notice: String? = null
    private var noticeUntil = 0L

    private fun setNotice(s: String) {
        notice = s
        noticeUntil = System.currentTimeMillis() + 4_000
        services?.requestRender(this)
    }

    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fMono = FontSpec(Face.MONO, 16)
    private val fBody = FontSpec(Face.SYSTEM, 17)

    override val needs = setOf(Need.HOST)

    // =============================================================== contract
    override fun view(): WindowView = when (level) {
        Level_.LOCATIONS -> WindowView.ListView(locModel, { locations.size.coerceAtLeast(1) },
            ::paintLocRow, ::paintLocLens, ::commitLocation)
        Level_.BROWSE -> WindowView.ListView(browseModel, { rows().size },
            ::paintBrowseRow, ::paintBrowseLens, ::commitBrowse)
        Level_.TRASH -> WindowView.ListView(trashModel, { trashEntries.size.coerceAtLeast(1) },
            ::paintTrashRow, ::paintTrashLens, ::commitTrash)
        Level_.VIEW -> viewer?.docView() ?: WindowView.ListView(browseModel, { rows().size },
            ::paintBrowseRow, ::paintBrowseLens, ::commitBrowse)
    }

    override fun title(): String {
        notice?.let { n ->
            if (System.currentTimeMillis() < noticeUntil) return n
            notice = null
        }
        val clipNote = clip?.let { (v, p) ->
            " · ${if (v == ClipVerb.CUT) "cut" else "copy"}: ${p.substringAfterLast('/')}"
        } ?: ""
        return when (level) {
            Level_.LOCATIONS -> "locations"
            Level_.TRASH -> "trash"
            Level_.BROWSE -> shortPath(cwd) + clipNote
            Level_.VIEW -> viewer?.titleLine() ?: ""
        }
    }

    override fun summary(): Summary {
        val st = provider.stateLine()
        if (st.isNotEmpty()) return Summary(st)
        return when (level) {
            Level_.LOCATIONS -> Summary("${locations.size} locations", more = locations.size > 1)
            Level_.TRASH -> Summary("trash · ${trashEntries.size}")
            Level_.BROWSE -> Summary(shortPath(cwd),
                detail = "${entries.count { it.dir }} folders · ${entries.count { !it.dir }} files")
            Level_.VIEW -> Summary(viewer?.name ?: "", detail = shortPath(cwd))
        }
    }

    override fun levelDepth(): Int = when (level) {
        Level_.LOCATIONS -> 1
        Level_.TRASH -> 2
        Level_.BROWSE -> 2 + browseStack.size
        Level_.VIEW -> 3 + browseStack.size
    }

    override fun back(): Boolean {
        pendingOpenView = null            // user navigation supersedes a pending restore (R4#1)
        return when (level) {
        Level_.VIEW -> {
            viewer = null
            level = Level_.BROWSE
            // a restored/synced viewer's cwd may never have been listed
            // (safe-empty rows + a "listing" note with nothing in flight —
            // R7#1): list it now
            if (entries.isEmpty()) refreshList()
            true
        }
        Level_.TRASH -> { level = Level_.LOCATIONS; refreshLocations(); true }
        Level_.BROWSE -> {
            val loc = location
            if (loc != null && cwd != loc.path && browseStack.isNotEmpty()) {
                val (parent, cursor) = browseStack.removeAt(browseStack.size - 1)
                cwd = parent
                browseModel.cursor = cursor
                // survive the listing gap (R2#20d): a scroll while rows() is
                // the lone placeholder wraps the cursor to 0 via mod(1) — the
                // restore re-applies when the listing lands
                pendingBrowseCursor = cursor
                pendingSelectName = null  // an ascend during a deep link's listing gap (R2-K5)
                entries = emptyList()     // Fi#4
                listState = "listing"
                nameArmed = null          // Fi#16
                refreshList()
                true
            } else if (loc != null && cwd != loc.path) {
                // stack lost (a restore): ascend by path
                cwd = cwd.substringBeforeLast('/').ifEmpty { "/" }
                browseModel.cursor = 0
                pendingSelectName = null  // (R2-K5)
                entries = emptyList()     // Fi#4
                listState = "listing"
                nameArmed = null          // Fi#16
                refreshList()
                true
            } else {
                level = Level_.LOCATIONS
                refreshLocations()
                true
            }
        }
        Level_.LOCATIONS -> false
        }
    }

    override fun onRegistered(ctx: ShellServices) { services = ctx }

    /** §16.1 deep link: `path:<absolute>` opens the browser at that folder,
     *  or at the file's folder with the cursor on the file (Torrents' "Open
     *  in Files" at a payload path). Resolved by the host when the listing
     *  arrives; a path that does not list shows the provider's error. */
    override fun open(target: String): Boolean {
        if (!target.startsWith("path:")) return false
        val given = target.removePrefix("path:")
        if (!given.startsWith("/")) return false
        // a trailing slash says "a folder" outright (a dotted folder name
        // would otherwise read as a file); "/" itself is the root (R2-K6)
        val isDir = given.endsWith("/")
        val raw = given.trimEnd('/').ifEmpty { "/" }
        val hasExt = !isDir && raw != "/" && raw.substringAfterLast('/').contains('.')
        val dir = if (hasExt) raw.substringBeforeLast('/').ifEmpty { "/" } else raw
        pendingOpenView = null
        pendingBrowseCursor = null        // an abandoned ascend must not steer the linked folder (R3-K4)
        viewer = null
        nameArmed = null
        location = locations.firstOrNull { l ->
            l.kind != "trash" && (dir == l.path || dir.startsWith(l.path.trimEnd('/') + "/"))   // a real ancestor, not a prefix (review K9)
        } ?: FLocation("Path", dir, "path", 0, 0)
        browseStack.clear()
        cwd = dir
        browseModel.cursor = 0
        entries = emptyList()
        listState = "listing"
        pendingSelectName = if (hasExt) raw.substringAfterLast('/') else null
        level = Level_.BROWSE
        refreshList()
        return true
    }

    override fun onActivate(ctx: ShellServices) {
        services = ctx
        when (level) {
            Level_.LOCATIONS -> refreshLocations()
            // while a restore's pdf open is in flight, hold the refresh: its
            // ++navSeq cancelled the open it was restoring (R4#1) — but the
            // stale rows still clear (R6#2, the boot door of the same class)
            Level_.BROWSE -> if (pendingOpenView == null) refreshList()
            else { entries = emptyList(); listState = "listing" }
            Level_.TRASH -> refreshTrash()
            Level_.VIEW -> {}
        }
    }

    override fun onLayoutChanged() {
        // a style change swaps the face behind tx: cached glyph coverage is
        // for the OLD face (R2#17) — stale entries pass tofu or '?' real glyphs
        coverCache.clear()
        viewer?.relayout()
    }

    override fun onFontScaleChanged(scale: Double) {
        coverCache.clear()
        viewer?.relayout()
    }

    override fun onTypedText(line: String): Boolean {
        val armed = nameArmed ?: return false
        nameArmed = null
        val name = line.trim()
        if (name.isEmpty()) {
            // consuming the arm on a blank line and then reporting "does not
            // accept typed text" mis-blamed the window (review Fi#16)
            setNotice("empty name — ${if (armed.first == NameFor.RENAME) "rename" else "new folder"} cancelled")
            return true
        }
        val (what, target) = armed
        // every user-content string in a menu goes through dn() (R2#18):
        // MenuSurface draws raw, and non-Latin names were silent tofu
        val verb = if (what == NameFor.RENAME) "Rename '${dn(target.substringAfterLast('/'))}' to" else "New folder"
        services?.openMenu(MenuSurface.Spec("$verb '${dn(name)}'?",
            listOf(MenuSurface.Item("Cancel"), MenuSurface.Item("Apply", detail = dn(name))),
            onCommit = { idx ->
                if (idx == 1) runOp(if (what == NameFor.RENAME) "renaming" else "creating folder") {
                    if (what == NameFor.RENAME) provider.rename(target, name)
                    else provider.mkdir(target, name)
                    null
                }
            }))
        return true
    }

    // ============================================================== locations
    private fun refreshLocations() {
        val seq = ++navSeq
        listState = "listing"
        services?.setOperation("listing locations")
        bg.launch(Dispatchers.IO) {
            val (locs, err) = try {
                provider.locations() to null
            } catch (e: Exception) {
                Log.e("files", "locations failed", e)
                emptyList<FLocation>() to (e.message ?: "locations failed")
            }
            services?.runOnShell {
                if (seq != navSeq) return@runOnShell
                if (err == null) {
                    locations = locs
                    // the Reader hand-off prefix comes from the HOST's own
                    // Books location (the phone cannot know the PC's path)
                    locs.firstOrNull { it.kind == "books" }?.let { booksDir = it.path }
                    locs.firstOrNull { it.kind == "home" }?.let { hostHome = it.path }
                }
                listState = err ?: ""
                if (err != null) setNotice(err)
                services?.setOperation("idle")
                services?.requestRender(this@FilesWindow)
            }
        }
    }

    private fun locIcon(kind: String): List<String> = when (kind) {
        "home" -> IconNames.HOME
        "root" -> IconNames.ROOT
        "downloads" -> IconNames.DOWNLOADS
        "books" -> IconNames.BOOKS
        "project" -> IconNames.PROJECT
        "trash" -> IconNames.TRASH
        else -> IconNames.MOUNT
    }

    private fun paintLocRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val l = locations.getOrNull(i) ?: run {
            if (i == 0) Draw.fit(g, tx, r.x + 32, r.y + 5, listState.ifEmpty { "no locations" },
                Level.REST, fRow, r.w - 64)
            return
        }
        IconPaint.draw(g, services?.icons(), locIcon(l.kind), r.x + 8, r.y + 6, 20, IconKind.FILES, Level.DIM)
        Draw.fit(g, tx, r.x + 40, r.y + 5, l.label, Level.BODY, fRow, 180)
        // the capacity bar — turtle's squeeze visible the moment Files opens
        if (l.totalBytes > 0) {
            val used = 1.0 - l.freeBytes.toDouble() / l.totalBytes
            Icons.blocks(g, r.right - 232, r.y + 12, 112, 8, used, n = 10, level = Level.of(5))
            Draw.right(g, tx, r.right - 24, r.y + 8, "${fmtBytes(l.freeBytes)} free", Level.DIM, fSmall)
        }
    }

    private fun paintLocLens(g: Gray8, r: Rect, i: Int) {
        val l = locations.getOrNull(i) ?: return
        IconPaint.draw(g, services?.icons(), locIcon(l.kind), r.x + 8, r.y + 4, 56, IconKind.FILES, Level.HEAD)
        Draw.fit(g, tx, r.x + 72, r.y + 6, l.label, Level.HEAD, FontSpec(Face.SYSTEM, 18, bold = true), r.w - 88)
        val det = if (l.totalBytes > 0)
            "${l.path} · ${fmtBytes(l.freeBytes)} free of ${fmtBytes(l.totalBytes)}"
        else l.path
        Draw.fit(g, tx, r.x + 72, r.y + 32, det, Level.BODY, fBody, r.w - 88)
        if (l.totalBytes > 0) {
            // below the detail line's descenders (the first render crowded them)
            val used = 1.0 - l.freeBytes.toDouble() / l.totalBytes
            Icons.blocks(g, r.x + 72, r.y + 56, 240, 4, used, n = 12, level = Level.of(6))
        }
    }

    private fun commitLocation(i: Int) {
        pendingBrowseCursor = null   // an abandoned ascend must not steer this folder (R3d#7)
        pendingSelectName = null     // nor a deep link's file steer another folder (review K9)
        pendingOpenView = null       // user navigation supersedes a pending restore (R5#1)
        val l = locations.getOrNull(i) ?: return
        location = l
        if (l.kind == "trash") {
            level = Level_.TRASH
            trashModel.cursor = 0
            refreshTrash()
            return
        }
        cwd = l.path
        browseStack.clear()
        browseModel.cursor = 0            // cursor rest (§1.7)
        entries = emptyList()             // never show the OLD folder's rows (Fi#4)
        listState = "listing"
        nameArmed = null                  // navigation disarms a pending name (Fi#16)
        level = Level_.BROWSE
        refreshList()
    }

    // ================================================================= browse
    /** Sorted rows: dirs first, then the chosen order, plus the wrap-to-end
     *  "This folder" pseudo-row (§4.6's wrap-to-end idiom). */
    /** Cached by (entries identity, sort) — rows() is called per row PAINT,
     *  and re-sorting a few-thousand-entry directory dozens of times per
     *  repaint on the shell loop was real work (R3s#8). [entries] is only
     *  ever replaced wholesale, so identity is the right key. */
    private var rowsKeyEntries: List<FEntry>? = null
    private var rowsKeySort: Sort? = null
    private var rowsCache: List<FEntry?> = listOf(null)

    private fun rows(): List<FEntry?> {
        if (rowsKeyEntries !== entries || rowsKeySort != sort) {
            val cmp: Comparator<FEntry> = when (sort) {
                Sort.NAME -> compareBy { it.name.lowercase() }
                Sort.SIZE -> compareByDescending { it.size }
                Sort.MTIME -> compareByDescending { it.mtimeMs }
            }
            rowsCache = entries.sortedWith(compareByDescending<FEntry> { it.dir }.then(cmp)) +
                listOf<FEntry?>(null)                // null = the This-folder row
            rowsKeyEntries = entries
            rowsKeySort = sort
        }
        return rowsCache
    }

    private fun refreshList() {
        val seq = ++navSeq
        val dir = cwd
        listState = "listing"
        services?.setOperation("listing")
        bg.launch(Dispatchers.IO) {
            val (list, err) = try {
                provider.list(dir, showHidden) to null
            } catch (e: Exception) {
                Log.e("files", "list $dir failed", e)
                emptyList<FEntry>() to (e.message ?: "listing failed")
            }
            services?.runOnShell {
                if (seq != navSeq || cwd != dir) return@runOnShell
                // an error must not leave the PREVIOUS folder's rows live and
                // commit-able under the new title (review Fi#4)
                entries = if (err == null) list else emptyList()
                listState = err ?: ""
                if (err != null) { setNotice(err); pendingSelectName = null }
                services?.setOperation("idle")
                val n = rows().size
                pendingBrowseCursor?.let {
                    browseModel.cursor = it.coerceIn(0, n - 1)
                    pendingBrowseCursor = null
                }
                pendingSelectName?.let { name ->
                    val idx = rows().indexOfFirst { it?.name == name }
                    if (idx >= 0) browseModel.cursor = idx
                    else setNotice("$name is not in this folder")   // said, never a silent miss (review K9)
                    pendingSelectName = null
                }
                if (browseModel.cursor >= n) browseModel.cursor = 0
                services?.requestRender(this@FilesWindow)
            }
        }
    }

    private fun paintBrowseRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val r0 = rows()
        if (i !in r0.indices) return
        val e = r0[i]
        if (e == null) {                              // the This-folder row
            g.fillRect(r.x + 8, r.y + 14, 4, 4, Level.DIM)
            g.fillRect(r.x + 16, r.y + 14, 4, 4, Level.DIM)
            g.fillRect(r.x + 24, r.y + 14, 4, 4, Level.DIM)
            Draw.fit(g, tx, r.x + 40, r.y + 5, "This folder", Level.DIM, fRow, r.w - 200)
            if (listState.isNotEmpty()) Draw.right(g, tx, r.right - 24, r.y + 8, listState, Level.REST, fSmall)
            return
        }
        IconPaint.drawFile(g, services?.icons(), e.name, e.dir, r.x + 8, r.y + 6, 20, Level.DIM)
        val nameMax = r.w - 40 - 176
        Draw.fit(g, tx, r.x + 40, r.y + 5, dn(e.name), if (e.dir) Level.HEAD else Level.BODY, fRow, nameMax)
        if (!e.dir) Draw.right(g, tx, r.right - 96, r.y + 8, fmtBytes(e.size), Level.DIM, fSmall)
        Draw.right(g, tx, r.right - 24, r.y + 8, fmtMtime(e.mtimeMs), Level.DIM, fSmall)
    }

    // lens thumbnails: a tiny LRU, filled off-loop, identity-guarded
    private val thumbs = object : LinkedHashMap<String, Gray8?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Gray8?>) = size > 24
    }
    private val thumbsInFlight = HashSet<String>()
    private val thumbRetryAt = HashMap<String, Long>()

    private fun isImageName(n: String) = n.substringAfterLast('.', "").lowercase() in
        setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

    private fun lensThumb(path: String): Gray8? {
        thumbs[path]?.let { return it }
        if (path in thumbs) return null               // known undecodable
        thumbRetryAt[path]?.let {
            if (System.currentTimeMillis() < it) return null else thumbRetryAt.remove(path)
        }
        if (thumbsInFlight.add(path)) {
            bg.launch(Dispatchers.IO) {
                val (t, failed) = try { provider.thumb(path, 56) to false } catch (e: Exception) {
                    Log.w("files", "thumb $path failed: ${e.message} — retrying after pacing")
                    null to true
                }
                services?.runOnShell {
                    thumbsInFlight.remove(path)
                    if (failed) {
                        // a link blip is not a property of the file (review
                        // Fi#10) — pace a retry instead of caching "no thumb".
                        // And REPAINT when the pacing expires (the R2#10
                        // class): a parked cursor otherwise never retries.
                        thumbRetryAt[path] = System.currentTimeMillis() + 10_000
                        bg.launch {
                            kotlinx.coroutines.delay(10_000)
                            services?.runOnShell { services?.requestRender(this@FilesWindow) }
                        }
                    } else {
                        thumbs[path] = t
                    }
                    services?.requestRender(this@FilesWindow)
                }
            }
        }
        return null
    }

    private fun paintBrowseLens(g: Gray8, r: Rect, i: Int) {
        val r0 = rows()
        if (i !in r0.indices) return
        val e = r0[i]
        val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        if (e == null) {                              // This folder
            IconPaint.draw(g, services?.icons(), listOf("folder-open", "folder"), r.x + 8, r.y + 4, 56,
                IconKind.FILES, Level.HEAD)
            Draw.fit(g, tx, r.x + 72, r.y + 6, "This folder", Level.HEAD, fB, r.w - 88)
            Draw.fit(g, tx, r.x + 72, r.y + 34,
                "new folder · paste · sort: ${sort.name.lowercase()} · hidden: ${if (showHidden) "on" else "off"}",
                Level.BODY, fBody, r.w - 88)
            return
        }
        val path = pathOf(e)
        val thumb = if (!e.dir && isImageName(e.name)) lensThumb(path) else null
        if (thumb != null) IconPaint.blit(g, thumb, r.x + 8, r.y + 4, Level.HEAD)
        else IconPaint.drawFile(g, services?.icons(), e.name, e.dir, r.x + 8, r.y + 4, 56, Level.HEAD)
        Draw.fit(g, tx, r.x + 72, r.y + 6, dn(e.name), Level.HEAD, fB, r.w - 88)
        val kind = if (e.dir) "folder" else e.name.substringAfterLast('.', "file").lowercase()
        val det = if (e.dir) "$kind · ${fmtMtime(e.mtimeMs)}"
        else "${fmtBytes(e.size)} · ${fmtMtime(e.mtimeMs)} · $kind"
        Draw.fit(g, tx, r.x + 72, r.y + 34, det, Level.BODY, fBody, r.w - 88)
    }

    private fun pathOf(e: FEntry): String = if (cwd == "/") "/${e.name}" else "$cwd/${e.name}"

    // ------------------------------------------------------------- the menus
    private fun commitBrowse(i: Int) {
        val r0 = rows()
        if (i !in r0.indices) return
        val e = r0[i]
        if (e == null) { openThisFolderMenu(); return }
        openEntryMenu(e)
    }

    /** Tap = context menu, Open first (Adam's design): uniform for every
     *  entry, misfire-safe — a stray tap opens a cancellable menu. Destructive
     *  rows sit last (§1.7: never at rest, never index 0/1). */
    private fun openEntryMenu(e: FEntry) {
        val path = pathOf(e)
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String = "", act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail))
            acts.add(act)
        }
        add("Open") { openEntry(e) }
        val readerId = readerIdFor(path)
        if (readerId != null) add("Open in Reader") {
            if (services?.openWindow("reader", "book:$readerId") != true) {
                setNotice("Reader is not available")
            }
        }
        add("Open on PC") { runOp("opening on PC") { provider.openOnPc(path); "opened on the PC" } }
        add("Copy") { clip = ClipVerb.COPY to path; services?.requestRender(this) }
        add("Cut") { clip = ClipVerb.CUT to path; services?.requestRender(this) }
        if (e.dir && clip != null) add("Paste here", dn(clip!!.second.substringAfterLast('/'))) { paste(path) }
        add("Rename") { askName(NameFor.RENAME, path, e.name) }
        add("Stats") { openStats(path, e) }
        add("Delete", "to trash") { confirmTrash(path, e.name) }
        services?.openMenu(MenuSurface.Spec(dn(e.name), items, onCommit = { idx -> acts.getOrNull(idx)?.invoke() }))
    }

    private fun openThisFolderMenu() {
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String = "", act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail))
            acts.add(act)
        }
        add("New folder") { askName(NameFor.MKDIR, cwd, "") }
        if (clip != null) add("Paste here", dn(clip!!.second.substringAfterLast('/'))) { paste(cwd) }
        add("Sort", sort.name.lowercase()) {
            sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]
            services?.requestRender(this)
        }
        add("Hidden files", if (showHidden) "on" else "off") {
            showHidden = !showHidden
            refreshList()
        }
        add("Refresh") { refreshList() }
        add("Stats", "this folder") { openStats(cwd, null) }
        services?.openMenu(MenuSurface.Spec(dn(shortPath(cwd)), items,
            onCommit = { idx -> acts.getOrNull(idx)?.invoke() }))
    }

    /** Rename / new folder through the §4.8 keyboard (2026-09-01), pre-filled
     *  with the current name; the commit runs the SAME confirm path a replica
     *  line takes ([onTypedText]), and a cancel keeps the draft for the next
     *  ask. The arm stays set either way, so a replica line still works. */
    private fun askName(what: NameFor, target: String, initial: String) {
        nameArmed = what to target
        val key = "${what.name}:$target"
        val opened = services?.openKeyboard(KeyboardSurface.Spec(
            title = if (what == NameFor.RENAME) "rename" else "new folder",
            initial = nameDrafts[key] ?: initial,
            onCommit = { line ->
                nameDrafts.remove(key)
                if (nameArmed == null) nameArmed = what to target   // a relist may have disarmed
                onTypedText(line)
            },
            onCancel = { d -> nameDrafts[key] = d },
        ), owner = this) == true
        if (!opened) setNotice("type the ${if (what == NameFor.RENAME) "new" else "folder"} name (phone strip / replica)")
        services?.requestRender(this)
    }

    private fun openEntry(e: FEntry) {
        pendingOpenView = null            // user navigation supersedes a pending restore (R4#1)
        pendingSelectName = null
        val path = pathOf(e)
        if (e.dir) {
            browseStack.add(cwd to browseModel.cursor)
            cwd = path
            browseModel.cursor = 0        // cursor rest (§1.7)
            entries = emptyList()         // Fi#4
            listState = "listing"
            nameArmed = null              // Fi#16
            refreshList()
            return
        }
        nameArmed = null                  // Fi#16, viewer entries too (R3s note)
        val ext = e.name.substringAfterLast('.', "").lowercase()
        when {
            isImageName(e.name) -> openImage(path, e.name)
            ext == "pdf" -> openPdf(path, e.name)
            ext == "epub" -> {
                val id = readerIdFor(path)
                if (id != null && services?.openWindow("reader", "book:$id") == true) return
                setNotice("not in the Reader library — Open on PC instead")
                services?.requestRender(this)
            }
            else -> openText(path, e.name)
        }
    }

    private fun readerIdFor(path: String): String? {
        if (booksDir.isEmpty() || !path.lowercase().endsWith(".epub") &&
            !path.lowercase().endsWith(".txt")) return null
        if (!path.startsWith("$booksDir/")) return null
        return LocalContent.idForPathString(path)
    }

    private fun confirmTrash(path: String, name: String) {
        services?.openMenu(MenuSurface.Spec("Delete '${dn(name)}'?",
            listOf(MenuSurface.Item("Cancel"), MenuSurface.Item("Move to Trash", detail = "restorable")),
            onCommit = { idx -> if (idx == 1) runOp("trashing") { provider.trash(path); "moved to trash" } }))
    }

    private fun paste(destDir: String) {
        val took = clip ?: return
        val (verb, src) = took
        // the clip slot clears in the LOOP completion (R3s#9), never in the
        // IO-side op — title() reads it every chrome sync — and only if it
        // still holds the pair THIS op consumed (R4#9: a clip re-armed during
        // a multi-second move must survive the old completion)
        runOp(if (verb == ClipVerb.COPY) "copying" else "moving",
            onSuccess = { if (verb == ClipVerb.CUT && clip == took) clip = null }) {
            val dest = if (verb == ClipVerb.COPY) provider.copy(src, destDir) else provider.move(src, destDir)
            "→ ${shortPath(dest)}"
        }
    }

    private fun openStats(path: String, e: FEntry?) {
        if (opBusy) {
            setNotice("busy — one operation at a time")
            return
        }
        opBusy = true                     // du can walk a cold HDD (Fi#6)
        // its OWN counter (R2#8): sharing sideSeq with openPdf let a Stats tap
        // silently discard an in-flight pdfInfo (a restore that never happened)
        val seq = ++statSeq               // NOT navSeq: listings stay live (Fi#5)
        val fromLevel = level
        services?.setOperation("stat")
        bg.launch(Dispatchers.IO) {
            val lines = try {
                val st = provider.stat(path)
                val size = if (st.dir) provider.du(path) else st.size
                listOf(
                    MenuSurface.Item("Size", fmtBytes(size)),
                    MenuSurface.Item("Modified", fmtMtime(st.mtimeMs)),
                    MenuSurface.Item("Mode", st.mode.ifEmpty { "-" }),
                    MenuSurface.Item("Owner", st.owner.ifEmpty { "-" }),
                    MenuSurface.Item("Kind", if (st.dir) "folder" else "file"),
                )
            } catch (ex: Exception) {
                Log.e("files", "stat $path failed", ex)
                services?.runOnShell {
                    opBusy = false
                    if (seq == statSeq) setNotice(ex.message ?: "stat failed")
                    services?.setOperation("idle")
                    services?.requestRender(this@FilesWindow)
                }
                return@launch
            }
            services?.runOnShell {
                opBusy = false
                services?.setOperation("idle")
                if (seq != statSeq) return@runOnShell
                // the menu shows only when Files still owns the screen at the
                // SAME level; every other case — level changed, user on Main
                // or another window, wheel open — delivers the answer as a
                // notice instead of losing it (Fi#6 + R2#8: the level check
                // alone opened the stats menu over other windows, and a
                // refused openMenu vanished with a log line)
                val shown = level == fromLevel &&
                    services?.openMenu(MenuSurface.Spec(
                        dn(e?.name ?: shortPath(path)), lines, onCommit = { }),
                        owner = this@FilesWindow) == true
                if (!shown) {
                    services?.notifyInternal("files",
                        "${e?.name ?: shortPath(path)}: " + lines.joinToString(" · ") { "${it.label} ${it.detail}" })
                }
            }
        }
    }

    /** One provider op at a time, narrated, loud on failure; a non-null
     *  return becomes the title notice. Refreshes whatever level shows.
     *  [onSuccess] applies view-facing follow-up state ON THE LOOP (R3s#9 —
     *  mutating it inside [op] runs on IO, the round-1 reader-race class). */
    private fun runOp(verb: String, onSuccess: (() -> Unit)? = null, op: () -> String?) {
        if (opBusy) {
            setNotice("busy — one operation at a time")
            services?.requestRender(this)
            return
        }
        opBusy = true
        services?.setOperation(verb)
        bg.launch(Dispatchers.IO) {
            val (msg, err) = try {
                op() to null
            } catch (e: Exception) {
                Log.e("files", "$verb failed", e)
                null to (e.message ?: "$verb failed")
            }
            services?.runOnShell {
                opBusy = false
                services?.setOperation("idle")
                if (err != null) {
                    setNotice(err)
                    services?.notifyInternal("files", "$verb failed: $err")
                } else {
                    if (msg != null) setNotice(msg)
                    onSuccess?.invoke()
                }
                when (level) {
                    Level_.BROWSE -> refreshList()
                    Level_.TRASH -> refreshTrash()
                    Level_.LOCATIONS -> refreshLocations()
                    Level_.VIEW -> services?.requestRender(this@FilesWindow)
                }
            }
        }
    }

    // ================================================================== trash
    private fun refreshTrash() {
        val seq = ++navSeq
        services?.setOperation("listing trash")
        bg.launch(Dispatchers.IO) {
            val (list, err) = try {
                provider.trashList() to null
            } catch (e: Exception) {
                emptyList<FTrashEntry>() to (e.message ?: "trash listing failed")
            }
            services?.runOnShell {
                if (seq != navSeq) return@runOnShell
                if (err == null) trashEntries = list else setNotice(err)
                services?.setOperation("idle")
                if (trashModel.cursor >= trashEntries.size) trashModel.cursor = 0
                services?.requestRender(this@FilesWindow)
            }
        }
    }

    private fun paintTrashRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val e = trashEntries.getOrNull(i) ?: run {
            if (i == 0) Draw.fit(g, tx, r.x + 32, r.y + 5, "trash is empty", Level.REST, fRow, r.w - 64)
            return
        }
        IconPaint.drawFile(g, services?.icons(), e.name, e.dir, r.x + 8, r.y + 6, 20, Level.DIM)
        Draw.fit(g, tx, r.x + 40, r.y + 5, dn(e.name), Level.BODY, fRow, r.w - 40 - 176)
        Draw.right(g, tx, r.right - 24, r.y + 8, fmtMtime(e.atMs), Level.DIM, fSmall)
    }

    private fun paintTrashLens(g: Gray8, r: Rect, i: Int) {
        val e = trashEntries.getOrNull(i) ?: return
        IconPaint.drawFile(g, services?.icons(), e.name, e.dir, r.x + 8, r.y + 4, 56, Level.HEAD)
        Draw.fit(g, tx, r.x + 72, r.y + 6, dn(e.name), Level.HEAD, FontSpec(Face.SYSTEM, 18, bold = true), r.w - 88)
        Draw.fit(g, tx, r.x + 72, r.y + 34, "was ${dn(shortPath(e.origPath))}", Level.BODY, fBody, r.w - 88)
    }

    private fun commitTrash(i: Int) {
        val e = trashEntries.getOrNull(i) ?: return
        services?.openMenu(MenuSurface.Spec(dn(e.name), listOf(
            MenuSurface.Item("Restore", detail = dn(shortPath(e.origPath.substringBeforeLast('/')))),
            MenuSurface.Item("Stats", detail = fmtBytes(e.size)),
            MenuSurface.Item("Delete forever", detail = "cannot be undone"),
        ), onCommit = { idx ->
            when (idx) {
                0 -> runOp("restoring") { "restored to ${shortPath(provider.restore(e.id))}" }
                1 -> services?.openMenu(MenuSurface.Spec(dn(e.name), listOf(
                    MenuSurface.Item("Size", fmtBytes(e.size)),
                    MenuSurface.Item("Was", dn(e.origPath)),
                    MenuSurface.Item("Trashed", fmtMtime(e.atMs)),
                ), onCommit = { }))
                2 -> services?.openMenu(MenuSurface.Spec("Delete '${dn(e.name)}' FOREVER?", listOf(
                    // the one unrecoverable op: Cancel at rest, an info spacer,
                    // the act at index 2 (§1.7's never-0/1, kept even here)
                    MenuSurface.Item("Cancel"),
                    MenuSurface.Item("This cannot be undone", enabled = false),
                    MenuSurface.Item("Delete forever"),
                ), onCommit = { j -> if (j == 2) runOp("deleting forever") { provider.purge(e.id); "gone" } }))
            }
        }))
    }

    // ================================================================ viewers
    /** Every viewer is a DocView: text as wrapped lines, images and PDF pages
     *  as whole-line STRIPS (the Reader-images trick) — scrolling, slides and
     *  damage all ride the existing machinery. */
    private inner class Viewer(
        val path: String,
        val name: String,
        val mode: String,                             // text | image | pdftext | pdfpage
    ) {
        var lines: List<String> = emptyList()         // text modes
        var mono = false
        var loadedBytes = 0L
        var totalBytes = 0L
        var more = false
        var loading = false
        var carry = ""                                // partial trailing line

        var strips: MutableList<Gray8> = ArrayList()  // image/pdfpage: whole-strip images
        var pdfPages = 0
        var pdfNextPage = 1
        /** Strip index each loaded PDF page begins at — the title's page. */
        val pageStarts = ArrayList<Int>()
        val stripH = 32

        var lineH = 24
        /** Paced retry after a transient chunk/page failure (Fi#3) — never a
         *  silent end-of-file, never an unpaced retry storm. */
        var retryAtMs = 0L

        /** The retry is DEMAND-driven from paint, so something must repaint
         *  when the pacing expires (R2#10): a parked viewer otherwise showed
         *  "retrying" forever without retrying. One delayed render per
         *  failure; paint re-evaluates demand as usual. */
        fun scheduleRetryPaint() {
            bg.launch {
                kotlinx.coroutines.delay(RETRY_PACING_MS)
                services?.runOnShell {
                    if (viewer === this@Viewer) services?.requestRender(this@FilesWindow)
                }
            }
        }

        fun titleLine(): String = when (mode) {
            "pdfpage" -> {
                val at = pageStarts.indexOfLast { it <= docModel.topLine }.coerceAtLeast(0) + 1
                "$name · p.$at of $pdfPages"
            }
            else -> name
        }

        fun font(): FontSpec = if (mono) fMono else fBody

        fun relayout() {
            // width or style changed: re-derive from the raw content
            if (mode == "text" || mode == "pdftext") {
                val raw = rawText ?: return
                layoutText(raw, append = false)
            }
        }

        var rawText: String? = null                   // accumulated source text

        private fun wrapRaw(raw: String, f: FontSpec, width: Int): List<String> {
            val wrapped = ArrayList<String>()
            for (srcLine in raw.split('\n')) {
                val s = sanitize(srcLine, mono)
                if (s.isEmpty()) { wrapped.add(""); continue }
                wrapped.addAll(Wrap.wrap(s, f, tx, width))
            }
            return wrapped
        }

        private fun deriveLineH(f: FontSpec) {
            val m = tx.metrics(f)
            lineH = (((m.ascent + m.descent + 3) / 2) * 2).coerceAtLeast(18)
        }

        /** Full (re)layout — width/style changes, whole-text opens. Keeps the
         *  reading position (a restore sets it before content arrives). */
        fun layoutText(raw: String, append: Boolean) {
            rawText = raw
            val f = font()
            deriveLineH(f)
            val keep = docModel.topLine
            lines = wrapRaw(raw, f, (services?.docContentWidth() ?: 560) - 16)
            docModel.topLine = keep.coerceIn(0, maxOf(0, lines.size - 1))
        }

        /** Incremental append: wrap only the NEW text — a chunked read of a
         *  big log must not re-wrap everything per chunk (quadratic). */
        fun appendText(newRaw: String) {
            rawText = (rawText ?: "") + "\n" + newRaw
            val f = font()
            deriveLineH(f)
            lines = lines + wrapRaw(newRaw, f, (services?.docContentWidth() ?: 560) - 16)
            applyRestoreTop(done = !more)
        }

        /** A restored reading position waits for enough content (§9.1) — the
         *  first paint's clamp must not zero it while chunks/pages stream in. */
        var restoreTop = 0
        fun applyRestoreTop(done: Boolean) {
            if (restoreTop <= 0) return
            val n = if (mode == "image" || mode == "pdfpage") strips.size else lines.size
            if (n > restoreTop || done) {
                docModel.topLine = restoreTop.coerceIn(0, maxOf(0, n - 1))
                if (n > restoreTop || done) restoreTop = 0
            } else if (n > 0) {
                docModel.topLine = n - 1     // ride the loading edge toward it
            }
        }

        fun docView(): WindowView.DocView = when (mode) {
            "image", "pdfpage" -> WindowView.DocView(docModel,
                { strips.size + (if (mode == "pdfpage" && pdfNextPage <= pdfPages) 1 else 0) },
                stripH, { g, i, r -> paintStrip(g, i, r) }, { /* tap: nothing below */ },
                stepLines = { 5 })
            else -> WindowView.DocView(docModel,
                { lines.size + (if (more) 1 else 0) }, lineH,
                { g, i, r -> paintTextLine(g, i, r) }, { },
                stepLines = { 5 })
        }

        fun paintTextLine(g: Gray8, i: Int, r: Rect) {
            if (i >= lines.size) {
                val waiting = !loading && System.currentTimeMillis() < retryAtMs
                Draw.fit(g, tx, r.x + 16, r.y + 2,
                    if (loading) "loading more…" else if (waiting) "read failed — retrying" else "· · ·",
                    Level.REST, fSmall, r.w - 32)
                if (!loading && more && !waiting) loadNextChunk()
                return
            }
            if (more && !loading && i > lines.size - 60 &&
                System.currentTimeMillis() >= retryAtMs) loadNextChunk()
            val line = lines[i]
            if (line.isEmpty()) return
            val m = tx.metrics(font())
            val off = ((lineH - (m.ascent + m.descent)) / 2).coerceAtLeast(0)
            tx.draw(g, (r.x + 16) / 4 * 4, (r.y + off) / 2 * 2, line, font(), Level.BODY)
        }

        fun paintStrip(g: Gray8, i: Int, r: Rect) {
            val strip = strips.getOrNull(i)
            val waiting = !loading && System.currentTimeMillis() < retryAtMs
            if (strip == null) {
                Draw.fit(g, tx, r.x + 16, r.y + 8,
                    if (loading) "loading page $pdfNextPage…" else if (waiting) "page failed — retrying" else "",
                    Level.REST, fSmall, r.w - 32)
                if (mode == "pdfpage" && !loading && !waiting && pdfNextPage <= pdfPages) loadNextPdfPage()
                return
            }
            if (mode == "pdfpage" && !loading && !waiting && pdfNextPage <= pdfPages &&
                i > strips.size - 20) loadNextPdfPage()
            val x = ((r.x + (r.w - strip.w).coerceAtLeast(0) / 2) / 4) * 4
            g.blit(strip, Rect(0, 0, strip.w, stripH), x, r.y)
        }

        // ------------------------------------------------------ text chunking
        fun loadNextChunk() {
            if (loading || !more) return
            loading = true
            val off = loadedBytes
            bg.launch(Dispatchers.IO) {
                val (chunk, err) = try {
                    provider.readText(path, off, CHUNK) to null
                } catch (e: Exception) {
                    null to (e.message ?: "read failed")
                }
                services?.runOnShell {
                    loading = false
                    if (viewer !== this@Viewer) return@runOnShell
                    if (chunk == null) {
                        // a transient read failure must not read as the END
                        // of the file (review Fi#3): keep [more], pace the
                        // retry, and say it — a 2 s PC blip must not silently
                        // half-show a log for the rest of the session
                        Log.e("files", "text chunk of $path failed: $err")
                        setNotice(err ?: "read failed — retrying")
                        retryAtMs = System.currentTimeMillis() + RETRY_PACING_MS
                        scheduleRetryPaint()
                    } else {
                        // advance by what the provider actually READ (R2#2):
                        // re-encoding the decoded text inflates every invalid
                        // byte to a 3-byte U+FFFD, drifting the offset past
                        // reality and eventually past EOF on mixed binary logs
                        loadedBytes = off + chunk.bytesRead
                        totalBytes = chunk.totalBytes
                        more = chunk.more
                        // hold back the trailing partial line; the next chunk
                        // completes it (a mid-line chunk cut must not show as
                        // a break that is not in the file)
                        val whole = carry + chunk.text
                        val cut = if (chunk.more) whole.lastIndexOf('\n') else whole.length
                        val usable = if (cut < 0) "" else whole.substring(0, cut)
                        carry = if (cut < 0) whole else whole.substring(minOf(cut + 1, whole.length))
                        val add = usable + (if (!chunk.more && carry.isNotEmpty()) "\n$carry" else "")
                        if (rawText == null) {
                            // a first chunk with no newline yet contributes
                            // nothing — laying out "" fabricated a blank first
                            // line the file does not contain (review Fi#13)
                            if (add.isNotEmpty() || !chunk.more) {
                                layoutText(add, append = true)
                                applyRestoreTop(done = !more)
                            }
                        } else if (add.isNotEmpty()) appendText(add)
                        if (!chunk.more) carry = ""
                    }
                    services?.requestRender(this@FilesWindow)
                }
            }
        }

        fun loadNextPdfPage() {
            if (loading || pdfNextPage > pdfPages) return
            loading = true
            val page = pdfNextPage
            val width = ((services?.docContentWidth() ?: 560) - 16) / 4 * 4
            bg.launch(Dispatchers.IO) {
                val (png, err) = try {
                    provider.pdfPage(path, page, width) to null
                } catch (e: Exception) {
                    null to (e.message ?: "page $page failed")
                }
                val g = png?.let { decoder?.decode(it) }
                services?.runOnShell {
                    loading = false
                    if (viewer !== this@Viewer) return@runOnShell
                    if (g == null) {
                        // keep our place and retry after pacing (Fi#3) — the
                        // old skip-to-end silently dropped every later page
                        Log.e("files", "pdf page $page of $path failed: $err")
                        setNotice(err ?: "page $page failed — retrying")
                        retryAtMs = System.currentTimeMillis() + RETRY_PACING_MS
                        scheduleRetryPaint()
                    } else {
                        pageStarts.add(strips.size)
                        appendStrips(g)
                        pdfNextPage = page + 1
                        applyRestoreTop(done = pdfNextPage > pdfPages)
                    }
                    services?.requestRender(this@FilesWindow)
                }
            }
        }

        fun appendStrips(d: ImageDecoder.Decoded) {
            val w = (d.w / 4) * 4
            var y = 0
            while (y < d.h) {
                val s = Gray8(w, stripH)
                for (yy in 0 until minOf(stripH, d.h - y)) for (xx in 0 until w) {
                    val v = d.gray[(y + yy) * d.w + xx].toInt() and 0xFF
                    s[xx, yy] = ((v + 8) / 17).coerceAtMost(15) * 17
                }
                strips.add(s)
                y += stripH
            }
        }
    }

    private var viewer: Viewer? = null
    /** A restored reading position waiting for its viewer (Fi#7) — consumed
     *  at Viewer creation by every open path, async ones included; bound to
     *  [pendingViewFor]'s file so an unrelated open cannot inherit it. */
    private var pendingViewTop = 0
    private var pendingViewFor: String? = null
    /** A RESTORE's async pdf open in flight (path, name, mode) — R4#1: while
     *  set, (a) saveState reports the VIEW being restored, not the transient
     *  BROWSE (a freshen mid-window otherwise re-stamped BROWSE and closed
     *  the PEER's open PDF), and (b) the automatic refreshes (onActivate,
     *  restoreStateLive) hold off — their ++navSeq deterministically
     *  cancelled the very open the restore launched. */
    private var pendingOpenView: Triple<String, String, String>? = null

    private fun consumePendingTop(v: Viewer) {
        if (pendingViewFor != null && pendingViewFor != v.path) return   // not this file (R3d#8)
        v.restoreTop = pendingViewTop
        pendingViewTop = 0
        pendingViewFor = null
    }

    private val coverCache = HashMap<String, Boolean>()

    /** Viewer text is arbitrary bytes: tabs expand, controls and glyphs the
     *  face cannot draw become a visible '?' (never a tofu box, never a
     *  refused draw — the §Type rule at the content boundary). */
    private fun sanitize(s: String, mono: Boolean): String {
        val f = if (mono) fMono else fBody
        return buildString(s.length) {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    cp == '\t'.code -> append("    ")
                    cp == '\r'.code -> {}
                    cp < 0x20 -> append('?')
                    cp in 0x20..0x7E -> append(cp.toChar())
                    else -> {
                        val ok = coverCache.getOrPut("$cp:$mono") {
                            tx.covers(String(Character.toChars(cp)), f)
                        }
                        if (ok) append(String(Character.toChars(cp))) else append('?')
                    }
                }
            }
        }
    }

    private fun openText(path: String, name: String) {
        val v = Viewer(path, name, "text")
        v.mono = name.substringAfterLast('.', "").lowercase() in MONO_EXTS
        v.more = true
        consumePendingTop(v)
        viewer = v
        docModel.topLine = 0
        level = Level_.VIEW
        v.loadNextChunk()
    }

    private fun openImage(path: String, name: String) {
        val v = Viewer(path, name, "image")
        consumePendingTop(v)
        viewer = v
        docModel.topLine = 0
        level = Level_.VIEW
        v.loading = true
        services?.setOperation("loading image")
        bg.launch(Dispatchers.IO) {
            val (bytes, err) = try {
                provider.readBytes(path, 24 shl 20) to null
            } catch (e: Exception) {
                null to (e.message ?: "read failed")
            }
            val d = bytes?.let { decoder?.decode(it) }
            val scaled = d?.let { fitToWidth(it, ((services?.docContentWidth() ?: 560) - 16) / 4 * 4) }
            services?.runOnShell {
                v.loading = false
                services?.setOperation("idle")
                if (viewer !== v) return@runOnShell
                if (scaled == null) {
                    setNotice(err ?: "not a decodable image")
                    level = Level_.BROWSE
                    viewer = null
                    if (entries.isEmpty()) refreshList()   // R7#2b: never land on fake-empty rows
                } else {
                    v.appendStrips(scaled)
                    v.applyRestoreTop(done = true)
                }
                services?.requestRender(this@FilesWindow)
            }
        }
    }

    /** Downscale to [width] keeping aspect (never upscale) — box average. */
    private fun fitToWidth(d: ImageDecoder.Decoded, width: Int): ImageDecoder.Decoded {
        if (d.w <= width) return d
        val w = width
        val h = maxOf(2, (d.h.toLong() * w / d.w).toInt() / 2 * 2)
        val out = ByteArray(w * h)
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
                out[y * w + x] = (sum / n).toByte()
            }
        }
        return ImageDecoder.Decoded(w, h, out)
    }

    private fun openPdf(path: String, name: String) {
        services?.setOperation("reading pdf")
        val seq = ++sideSeq
        val nav = navSeq
        val fromLevel = level
        val fromViewer = viewer
        bg.launch(Dispatchers.IO) {
            val (info, err) = try {
                provider.pdfInfo(path) to null
            } catch (e: Exception) {
                null to (e.message ?: "pdf info failed")
            }
            services?.runOnShell {
                if (seq != sideSeq) return@runOnShell
                if (pendingOpenView?.first == path) pendingOpenView = null   // resolved either way (R4#1)
                services?.setOperation("idle")
                // apply-only-if-undisturbed (R3s#1, the Reader #B8 shape): a
                // slow pdfInfo over a stalled link must not yank the user out
                // of a file or folder they went to meanwhile — every other
                // async open got this guard; the one that CREATES the viewer
                // had none
                if (nav != navSeq || level != fromLevel || viewer !== fromViewer) {
                    Log.i("files", "pdf open of $name superseded by user activity — dropped")
                    setNotice("opening $name cancelled — you moved on")
                    pendingViewTop = 0
                    pendingViewFor = null
                    services?.requestRender(this@FilesWindow)
                    return@runOnShell
                }
                if (info == null) {
                    Log.e("files", "pdfinfo of $path failed: $err")
                    setNotice(err ?: "could not read the PDF")
                    // a restore that dies here must not leave its position
                    // armed for the NEXT unrelated open (R2#9); and the held
                    // refresh must run NOW (R5#1c) — the gate skipped it while
                    // the intent was armed, so the browse rows may be stale
                    pendingViewTop = 0
                    pendingViewFor = null
                    if (level == Level_.BROWSE) refreshList()
                    services?.requestRender(this@FilesWindow)
                    return@runOnShell
                }
                // dual mode, auto-defaulted (settled 2026-09-01): text-heavy
                // PDFs read as real typography; scans go to page rasters
                if (info.textChars > 500) openPdfText(path, name) else openPdfPages(path, name, info.pages)
            }
        }
    }

    private fun openPdfText(path: String, name: String) {
        val v = Viewer(path, name, "pdftext")
        consumePendingTop(v)
        viewer = v
        docModel.topLine = 0
        level = Level_.VIEW
        v.loading = true
        services?.setOperation("extracting text")
        bg.launch(Dispatchers.IO) {
            val (raw, err) = try {
                provider.pdfText(path) to null
            } catch (e: Exception) {
                null to (e.message ?: "pdftotext failed")
            }
            services?.runOnShell {
                v.loading = false
                services?.setOperation("idle")
                if (viewer !== v) return@runOnShell
                if (raw == null) {
                    Log.e("files", "pdftotext of $path failed: $err")
                    setNotice(err ?: "text extraction failed")
                    level = Level_.BROWSE
                    viewer = null
                    if (entries.isEmpty()) refreshList()   // R7#2b
                } else {
                    v.layoutText(raw, append = false)
                    v.applyRestoreTop(done = true)
                }
                services?.requestRender(this@FilesWindow)
            }
        }
    }

    private fun openPdfPages(path: String, name: String, pages: Int) {
        val v = Viewer(path, name, "pdfpage")
        v.pdfPages = pages
        consumePendingTop(v)
        viewer = v
        docModel.topLine = 0
        level = Level_.VIEW
        v.loadNextPdfPage()
    }

    // ================================================================ persist
    override fun saveState(): JsonObject = buildJsonObject {
        put("level", level.name)
        put("locCursor", locModel.cursor)
        put("browseCursor", browseModel.cursor)
        put("trashCursor", trashModel.cursor)
        put("locationPath", location?.path ?: "")
        put("locationKind", location?.kind ?: "")
        put("cwd", cwd)
        put("sort", sort.name)
        put("hidden", showHidden)
        put("clipVerb", clip?.first?.name ?: "")
        put("clipPath", clip?.second ?: "")
        val v = viewer
        val pending = pendingOpenView
        if (v == null && pending != null) {
            // a restore's pdf open is in flight (R4#1): report the VIEW being
            // restored — saving the transient BROWSE re-stamped it over the
            // peer's real state
            put("level", Level_.VIEW.name)
            put("viewPath", pending.first)
            put("viewName", pending.second)
            put("viewMode", pending.third)
            put("viewTop", pendingViewTop)
        } else {
            put("viewPath", v?.path ?: "")
            put("viewName", v?.name ?: "")
            put("viewMode", v?.mode ?: "")
            put("viewTop", docModel.topLine)
        }
    }

    /** A LIVE-synced record needs the refresh boot gets from onActivate
     *  (R3d#2): applying a new cwd over the OLD folder's rows let a menu
     *  commit act on `pathOf(stale row)` — a same-named file in the new
     *  folder would be the wrong file. */
    override fun restoreStateLive(state: JsonObject) {
        restoreState(state)
        when (level) {
            // R4#1: while the record's own pdf open is in flight the level is
            // transiently BROWSE — refreshing would ++navSeq and cancel it.
            // The OLD rows still clear either way (R6#2): a commit against
            // them would resolve pathOf() under the record's NEW cwd — the
            // wrong-file class. Safe-empty until the viewer lands (or the
            // failure path's refresh runs).
            Level_.BROWSE -> if (pendingOpenView == null) {
                entries = emptyList(); listState = "listing"; refreshList()
            } else {
                entries = emptyList(); listState = "listing"
            }
            Level_.TRASH -> refreshTrash()
            Level_.LOCATIONS -> refreshLocations()
            Level_.VIEW -> {
                // the restore path reopens through the normal opens — but the
                // OLD folder's rows behind the viewer still clear (R7#2): a
                // back() would otherwise commit pathOf(stale row) under the
                // record's NEW cwd; back()'s empty-refresh relists
                entries = emptyList(); listState = "listing"
            }
        }
        services?.requestRender(this)
    }

    override fun restoreState(state: JsonObject) {
        // any incoming record supersedes a pending pdf-restore intent (R5#1):
        // without this, a live-applied BROWSE/TRASH record left the stale
        // intent armed — the gate then skipped the refresh (no navSeq bump),
        // the in-flight completion applied "undisturbed", and saveState
        // re-stamped the OLD view over the peer's newer record (the R4#1
        // ping-pong, direction reversed). The pdfpage branch re-arms below.
        pendingOpenView = null
        pendingBrowseCursor = null        // a record's listing must not apply a stale ascend's steer (R4-K2)
        pendingSelectName = null
        locModel.cursor = state["locCursor"]?.jsonPrimitive?.intOrNull ?: 0
        browseModel.cursor = state["browseCursor"]?.jsonPrimitive?.intOrNull ?: 0
        trashModel.cursor = state["trashCursor"]?.jsonPrimitive?.intOrNull ?: 0
        sort = state["sort"]?.jsonPrimitive?.contentOrNull?.let {
            try { Sort.valueOf(it) } catch (e: Exception) { Sort.NAME }
        } ?: Sort.NAME
        showHidden = state["hidden"]?.jsonPrimitive?.booleanOrNull ?: false
        val cv = state["clipVerb"]?.jsonPrimitive?.contentOrNull ?: ""
        val cp = state["clipPath"]?.jsonPrimitive?.contentOrNull ?: ""
        clip = if (cv.isNotEmpty() && cp.isNotEmpty()) {
            try { ClipVerb.valueOf(cv) to cp } catch (e: Exception) { null }
        } else null
        val lvl = state["level"]?.jsonPrimitive?.contentOrNull
        val locPath = state["locationPath"]?.jsonPrimitive?.contentOrNull ?: ""
        val savedCwd = state["cwd"]?.jsonPrimitive?.contentOrNull ?: ""
        if (locPath.isNotEmpty()) {
            location = FLocation(locPath.substringAfterLast('/').ifEmpty { locPath },
                locPath, state["locationKind"]?.jsonPrimitive?.contentOrNull ?: "mount")
        }
        when (lvl) {
            Level_.BROWSE.name -> if (savedCwd.isNotEmpty()) { cwd = savedCwd; level = Level_.BROWSE }
            Level_.TRASH.name -> level = Level_.TRASH
            Level_.VIEW.name -> {
                if (savedCwd.isNotEmpty()) { cwd = savedCwd }
                // reopen the viewer at its position (§9.1: MODE, not just
                // place) — through the normal open path, then restore the line
                val vPath = state["viewPath"]?.jsonPrimitive?.contentOrNull ?: ""
                val vName = state["viewName"]?.jsonPrimitive?.contentOrNull ?: ""
                val vMode = state["viewMode"]?.jsonPrimitive?.contentOrNull ?: ""
                val vTop = state["viewTop"]?.jsonPrimitive?.intOrNull ?: 0
                if (vPath.isNotEmpty() && vName.isNotEmpty()) {
                    level = Level_.BROWSE
                    // consumed by whichever open path CREATES the viewer —
                    // pdfpage creates it inside an async completion, so a
                    // direct write here hit null and lost the page (Fi#7).
                    // Bound to ITS file (R3d#8): a different file the user
                    // opens meanwhile must not inherit the position.
                    pendingViewTop = vTop
                    pendingViewFor = vPath
                    when (vMode) {
                        "text" -> openText(vPath, vName)
                        "image" -> openImage(vPath, vName)
                        "pdftext" -> openPdfText(vPath, vName)
                        "pdfpage" -> {
                            // the one ASYNC create: level stays BROWSE until
                            // pdfInfo lands, so the in-flight intent is state
                            pendingOpenView = Triple(vPath, vName, "pdfpage")
                            openPdf(vPath, vName)
                        }
                    }
                } else level = Level_.BROWSE
            }
        }
    }

    // ================================================================ helpers
    private fun shortPath(p: String): String {
        // the HOST's home, learned from the locations listing — the phone's
        // local user.home ("/" under ART) mangled every PC path (Fi#15)
        val home = hostHome.ifEmpty { System.getProperty("user.home") ?: "" }
        return if (home.length > 1 && p.startsWith(home)) "~" + p.removePrefix(home) else p
    }

    /** Display form of an untrusted file name: glyphs the face cannot draw
     *  become a visible '?' — never tofu, never a refused draw (Fi#12). */
    private fun dn(s: String) = sanitize(s, false)

    override fun appSettings(): List<HostSetting> = emptyList()

    companion object {
        private const val CHUNK = 128 * 1024
        private const val RETRY_PACING_MS = 5_000L
        private val MONO_EXTS = setOf("log", "json", "xml", "conf", "cfg", "ini", "toml", "yaml",
            "yml", "sh", "bash", "py", "kt", "kts", "c", "h", "cpp", "rs", "go", "java", "js",
            "ts", "html", "htm", "css", "sql", "ebuild", "gradle", "properties", "service", "csv")
    }
}
