package wm.damage.core.windows.torrents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.IconNames
import wm.damage.core.gfx.IconPaint
import wm.damage.core.gfx.Icons
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
 * TORRENTS — `TORRENTS.md` (design settled with Adam 2026-09-01): the
 * qBittorrent transfers on beardos and his TorrentLeech account, on glass.
 *
 *   TRANSFERS (list) ──tap──▶ transfer MENU ──Details──▶ DETAILS (doc) ──tap──▶ actions
 *      │ wrap-end row = the Torrents MENU: Browse · Search (keyboard) · recents ·
 *      │   Filter · Sort · Seeding < 1 week · Refresh · Stats
 *      └▶ CATEGORIES (list) ──▶ LISTING (list, endless pages) ──▶ TORRENT (doc) ──tap──▶ Add
 *
 * The periphery stays still: rows carry a quantized block bar and a state
 * word, the live numbers live in the lens. Everything the provider does is
 * off-loop; every completion applies through [ShellServices.runOnShell] and
 * is guarded by a sequence so a late answer never replaces what the user
 * moved on to (the Reader/Files discipline).
 */
class TorrentsWindow(
    private val text: TextRasterizer,
    private val provider: TorrentsProvider,
    private val bg: CoroutineScope,
) : DamageWindow("torrents", "Torrents", IconKind.TORRENTS) {

    private val tx = styledText(text)

    private enum class Level_ { TRANSFERS, DETAILS, CATEGORIES, LISTING, TORRENT }
    private enum class Filter(val label: String) {
        ALL("all"), DOWNLOADING("downloading"), SEEDING("seeding"), STOPPED("stopped"),
        ERRORS("errors"), UNDER_WEEK("seeding < 1 week")
    }
    private enum class Sort(val label: String) { ACTIVITY("activity"), NAME("name"), ADDED("added"), PROGRESS("progress"), SIZE("size") }

    /** A LISTING row: an item, the loading pseudo-row, or the wrap-end menu. */
    private sealed class LRow {
        class Item(val it: TlItem) : LRow()
        object Loading : LRow()
        object Menu : LRow()
    }

    /** One pre-wrapped document line. */
    private class DocLine(val s: String, val f: FontSpec, val lv: Int, val indent: Int = 0)

    private var level = Level_.TRANSFERS
    private val transModel = ListModel()
    private val catModel = ListModel()
    private val listModel = ListModel()
    private val docModel = DocModel()
    private val tlDocModel = DocModel()

    private var services: ShellServices? = null
    private var snap: Snapshot? = null
    private var stateLine = ""
    private var filter = Filter.ALL
    private var sort = Sort.ACTIVITY

    // DETAILS
    private var openHash: String? = null
    private var detail: TransferDetail? = null
    private var detailState = ""                      // "" · "loading" · error text
    private var detailSeq = 0

    // BROWSE
    private var listingCat: Int? = null
    private var listingQuery: String? = null
    private var listingFromTransfers = false
    private val listing = ArrayList<TlItem>()
    private var listingTotal = -1
    private var listingPage = 0
    private var listingLoading = false
    private var listingState = ""
    private var listingRetryAt = 0L
    private var listSeq = 0
    private var tlSort = "added"
    private var openFid: String? = null
    private var tlDetail: TlDetail? = null
    private var tlDetailState = ""
    private var tlSeq = 0
    private var searchDraft = ""
    private val recents = ArrayDeque<String>()

    // settings
    private var notifyDone = true
    private var notifyErrors = true
    private var pollMs = 2_000L
    private var heightPref: Int? = null

    /** The lens' 8-column speed history per hash (down while downloading, up while seeding). */
    private val speedHist = HashMap<String, LongArray>()
    private val histIdx = HashMap<String, Int>()

    private var opBusy = false
    private var notice: String? = null
    private var noticeUntil = 0L
    private var needsReload = false
    /** Focused (between onActivate and onDeactivate): the only time the
     *  provider may run at the focused pace (review 2026-09-01 W5). */
    private var active = false
    /** Restored positions wait for their content (review W4): the first
     *  paint clamps a cursor/top against an EMPTY list or a two-line
     *  placeholder document, so they are re-applied when the bytes land. */
    private var pendingTransHash: String? = null
    private var pendingTransIndex: Int? = null
    private var pendingListCursor: Int? = null
    private var pendingDocTop: Int? = null
    private var pendingTlDocTop: Int? = null

    private val fRow = FontSpec(Face.LIST, 18)
    private val fSmall = FontSpec(Face.LIST, 13, bold = true)
    private val fBody = FontSpec(Face.LIST, 17)
    private val fHead = FontSpec(Face.LIST, 18, bold = true)
    private val fMono = FontSpec(Face.MONO, 15)

    override val needs = setOf(Need.HOST)
    override val preferredHeight: Int? get() = heightPref

    private fun setNotice(s: String) {
        notice = s
        noticeUntil = System.currentTimeMillis() + 4_000
        services?.requestRender(this)
    }

    private fun onShell(action: () -> Unit) {
        services?.runOnShell(action) ?: action()
    }

    private fun dn(s: String, f: FontSpec = fRow): String = Draw.dynamic(tx, s, f)

    /** Menu-bound strings go RAW: MenuSurface sanitizes every title, label
     *  and detail against its own rasterizer and the spec it draws with
     *  (review 2026-09-01 R2-W6 — this window cannot know the chrome face). */
    private fun dm(s: String): String = s

    // ================================================================ provider glue
    private val listener = object : TorrentsProvider.Listener {
        override fun snapshot(s: Snapshot) {
            onShell {
                applySnapshot(s)
                if (level == Level_.TRANSFERS || level == Level_.DETAILS) services?.requestRender(this@TorrentsWindow)
            }
        }

        override fun event(e: TorrentEvent) {
            onShell { handleEvent(e) }
        }

        override fun state(line: String) {
            onShell {
                if (stateLine != line) {
                    stateLine = line
                    services?.requestRender(this@TorrentsWindow)
                }
            }
        }
    }

    /** A new snapshot: the list is LIVE (a finishing download moves groups,
     *  an add grows it), so the cursor follows the ROW IT WAS ON — the same
     *  transfer by hash, or the wrap-end menu row — never a bare index that
     *  now points at whoever slid underneath (the selfcheck caught this: an
     *  add under the menu row moved the menu away from the cursor). */
    private fun applySnapshot(s: Snapshot) {
        if (snap === s) return            // the same object twice (registration seeds it, the listener pushes it — W12)
        val before = rows()
        // an EMPTY list's only row is the menu row: the first real snapshot
        // must land the cursor on the first transfer, not chase the menu row
        // to the end (the first build did exactly that)
        val onMenu = before.size > 1 && transModel.cursor >= before.size - 1
        val onHash = before.getOrNull(transModel.cursor)?.hash
        snap = s
        updateHistory(s)
        val after = rows()
        val want = pendingTransHash
        val wantIdx = pendingTransIndex
        if (s.transfers.isNotEmpty()) {
            // ONE shot (R2-W3): a restored row that is not in this snapshot
            // (filtered out, gone) must not steer the cursor minutes later
            pendingTransHash = null
            pendingTransIndex = null
        }
        transModel.cursor = when {
            want != null && after.any { it?.hash == want } -> after.indexOfFirst { it?.hash == want }
            wantIdx != null && s.transfers.isNotEmpty() -> wantIdx.coerceIn(0, after.size - 1)
            onMenu -> after.size - 1
            onHash != null -> after.indexOfFirst { it?.hash == onHash }.takeIf { it >= 0 }
                ?: transModel.cursor.coerceIn(0, after.size - 1)
            else -> transModel.cursor.coerceIn(0, after.size - 1)
        }
        applyPendingDocTop()
    }

    /** A restored document position waits for BOTH the transfer (the header)
     *  and its file list — applied against the placeholder it would be
     *  clamped away (R2-W4). */
    private fun applyPendingDocTop() {
        val top = pendingDocTop ?: return
        val h = openHash ?: return
        if (currentTransfer(h) != null && detail != null) {
            docModel.topLine = top
            pendingDocTop = null
            detailCache = null
        }
    }

    /** The stack is going away while the provider lives on (the desktop's
     *  process-wide provider): stop listening and release the focused pace,
     *  or a dead shell's queue is fed every poll (review 2026-09-01 P1). */
    fun detach() {
        active = false
        provider.setFocused(false, pollMs)
        provider.removeListener(listener)
    }

    private fun updateHistory(s: Snapshot) {
        val live = HashSet<String>()
        for (t in s.transfers) {
            live.add(t.hash)
            val h = speedHist.getOrPut(t.hash) { LongArray(HIST) }
            val i = histIdx[t.hash] ?: 0
            h[i] = if (t.finished) t.upSpeed else t.dlSpeed
            histIdx[t.hash] = (i + 1) % HIST
        }
        speedHist.keys.retainAll(live)
        histIdx.keys.retainAll(live)
    }

    /** The §16.5 source `torrent`: done / error, gated on the window's own
     *  rows (Settings → Torrents), deep-linked to the transfer. */
    private fun handleEvent(e: TorrentEvent) {
        when (e.kind) {
            "done" -> if (notifyDone) {
                dirty = true
                services?.notifyInternal("torrent", "done · ${dn(e.name)}", appId = id, thread = e.hash, target = "t:${e.hash}")
            }
            "error" -> if (notifyErrors) {
                dirty = true
                services?.notifyInternal("torrent", "error · ${dn(e.name)}", appId = id, thread = e.hash, target = "t:${e.hash}")
            }
            else -> {}
        }
        if (level == Level_.TRANSFERS) services?.requestRender(this)
    }

    override fun onRegistered(ctx: ShellServices) {
        services = ctx
        provider.addListener(listener)
        // the listener's first push lands through runOnShell, which a shell
        // that is not running yet may not carry: seed from the cached
        // snapshot directly (cheap by contract), on the registering thread
        provider.snapshot()?.let { applySnapshot(it) }
    }

    override fun onActivate(ctx: ShellServices) {
        services = ctx
        active = true
        provider.snapshot()?.let { s -> if (snap !== s) applySnapshot(s) }
        provider.setFocused(true, pollMs)
        provider.refresh()
        if (needsReload) {
            needsReload = false
            when (level) {
                Level_.DETAILS -> openHash?.let { loadDetail(it) }
                Level_.LISTING -> if (listing.isEmpty()) loadNextPage()
                Level_.TORRENT -> openFid?.let { loadTorrent(it) }
                else -> {}
            }
        }
    }

    override fun onDeactivate() {
        active = false
        provider.setFocused(false, pollMs)
    }

    // ================================================================ contract
    override fun view(): WindowView = when (level) {
        Level_.TRANSFERS -> WindowView.ListView(transModel, { rows().size },
            ::paintTransferRow, ::paintTransferLens, ::commitTransfer)
        Level_.DETAILS -> WindowView.DocView(docModel, { detailLines().size }, lineH(fBody),
            ::paintDetailLine, {
                val t = openHash?.let { currentTransfer(it) }
                when {
                    t != null -> openTransferMenu(t, fromDetails = true)
                    snap == null -> setNotice("still connecting to the host")
                    else -> setNotice("this transfer is gone")
                }
            },
            stepLines = { 5 })
        Level_.CATEGORIES -> WindowView.ListView(catModel, { catRows().size },
            ::paintCatRow, ::paintCatLens, ::commitCategory)
        Level_.LISTING -> WindowView.ListView(listModel, { listingRows().size },
            ::paintListingRow, ::paintListingLens, ::commitListing)
        Level_.TORRENT -> WindowView.DocView(tlDocModel, { tlLines().size }, lineH(fBody),
            ::paintTlLine, ::openAddMenu, stepLines = { 5 })
    }

    override fun title(): String {
        val n = notice
        if (n != null && System.currentTimeMillis() < noticeUntil) return n
        return when (level) {
            Level_.TRANSFERS -> if (filter == Filter.ALL) "transfers" else filter.label
            Level_.DETAILS -> "details"
            Level_.CATEGORIES -> "browse"
            Level_.LISTING -> listingQuery?.let { "\"${dn(it, fSmall)}\"" }
                ?: (listingCat?.let { TorrentLeech.category(it)?.name?.lowercase() ?: "category $it" } ?: "newest")
            Level_.TORRENT -> "torrent"
        }
    }

    override fun summary(): Summary {
        if (stateLine.isNotEmpty()) return Summary(stateLine, detail = counts())
        val s = snap ?: return Summary("connecting")
        val dl = s.transfers.filter { it.downloading && !it.stopped }
        val seeding = s.transfers.count { it.finished }
        val line = when {
            dl.isNotEmpty() -> "${dl.size} downloading · ${Fmt.speed(s.session.dlSpeed)}"
            seeding > 0 -> "$seeding seeding · ${Fmt.speed(s.session.upSpeed)} up"
            s.transfers.isEmpty() -> "idle"
            else -> "${s.transfers.size} transfers"
        }
        val lead = dl.maxByOrNull { it.progress }
        return Summary(line, detail = counts(), more = s.transfers.isNotEmpty(), progress = lead?.progress)
    }

    private fun counts(): String {
        val s = snap ?: return ""
        val parts = ArrayList<String>()
        val seeding = s.transfers.count { it.finished }
        val stopped = s.transfers.count { it.stopped }
        val errors = s.transfers.count { it.error }
        val week = s.transfers.count { it.underAWeek }
        if (seeding > 0) parts.add("$seeding seeding")
        if (stopped > 0) parts.add("$stopped stopped")
        if (errors > 0) parts.add("$errors errors")
        if (week > 0) parts.add("$week under a week")
        return parts.joinToString(" · ")
    }

    override fun levelDepth(): Int = when (level) {
        Level_.TRANSFERS -> 1
        Level_.DETAILS, Level_.CATEGORIES -> 2
        Level_.LISTING -> if (listingFromTransfers) 2 else 3
        Level_.TORRENT -> if (listingFromTransfers) 3 else 4
    }

    override fun back(): Boolean = when (level) {
        Level_.TRANSFERS -> false
        Level_.DETAILS -> {
            level = Level_.TRANSFERS
            if (detailState == "loading") services?.setOperation("idle")   // the abandoned load's word (R2-W9)
            detailSeq++
            true
        }
        Level_.CATEGORIES -> { level = Level_.TRANSFERS; true }
        Level_.LISTING -> {
            listSeq++
            // a page in flight is abandoned here: its flags must not outlive
            // it or a re-entered listing says "loading more" forever (R2-W8)
            if (listingLoading) services?.setOperation("idle")
            listingLoading = false
            listingState = ""
            listingRetryAt = 0L
            level = if (listingFromTransfers) Level_.TRANSFERS else Level_.CATEGORIES
            true
        }
        Level_.TORRENT -> {
            level = Level_.LISTING
            if (tlDetailState == "loading") services?.setOperation("idle")
            tlSeq++
            true
        }
    }

    override fun onLayoutChanged() { invalidateDocs() }
    override fun onFontScaleChanged(scale: Double) { invalidateDocs() }

    private fun invalidateDocs() {
        detailCache = null
        tlCache = null
    }

    /** A typed line from a replica searches the tracker (never destructive). */
    override fun onTypedText(line: String): Boolean {
        val q = line.trim()
        if (q.isEmpty()) return false
        runSearch(q)
        return true
    }

    /** §16.1: `t:<hash>` → the transfer's details; `tl:<fid>` → the tracker page. */
    override fun open(target: String): Boolean {
        when {
            target.startsWith("t:") -> {
                val hash = target.removePrefix("t:")
                if (hash.isEmpty()) return false
                val s = snap
                if (s != null && s.transfers.none { it.hash == hash }) return false
                filter = Filter.ALL
                level = Level_.TRANSFERS
                val idx = rows().indexOfFirst { it?.hash == hash }
                if (idx >= 0) transModel.cursor = idx
                openDetails(hash)
                return true
            }
            target.startsWith("tl:") -> {
                val fid = target.removePrefix("tl:")
                if (fid.isEmpty()) return false
                if (level != Level_.LISTING) {
                    listingFromTransfers = true
                    // a fresh, EMPTY listing under the page (R2-W8): back lands on
                    // "Newest" loading from page 1, never on stale flags
                    listingCat = null; listingQuery = null
                    listing.clear(); listingTotal = -1; listingPage = 0
                    listingLoading = false; listingState = ""; listingRetryAt = 0L
                    listSeq++
                    listModel.cursor = 0
                    level = Level_.LISTING
                }
                openTorrent(fid)
                return true
            }
        }
        return false
    }

    // ================================================================ transfers
    private var rowsKeySnap: Snapshot? = null
    private var rowsKeyFilter: Filter? = null
    private var rowsKeySort: Sort? = null
    private var rowsCache: List<Transfer?> = listOf(null)

    /** Filtered + sorted transfers plus the wrap-end MENU row (null). Cached
     *  by snapshot identity — rows() runs per row paint. */
    private fun rows(): List<Transfer?> {
        val s = snap
        if (rowsKeySnap !== s || rowsKeyFilter != filter || rowsKeySort != sort) {
            val all = s?.transfers ?: emptyList()
            val f = all.filter {
                when (filter) {
                    Filter.ALL -> true
                    Filter.DOWNLOADING -> it.downloading && !it.stopped
                    Filter.SEEDING -> it.finished && !it.stopped
                    Filter.STOPPED -> it.stopped
                    Filter.ERRORS -> it.error
                    Filter.UNDER_WEEK -> it.underAWeek
                }
            }
            val sorted = when (sort) {
                Sort.ACTIVITY -> f.sortedWith(compareBy<Transfer> { activityRank(it) }
                    .thenByDescending { if (it.downloading) it.progress else 0.0 }
                    .thenByDescending { it.completedOn }.thenByDescending { it.addedOn })
                Sort.NAME -> f.sortedBy { it.name.lowercase() }
                Sort.ADDED -> f.sortedByDescending { it.addedOn }
                Sort.PROGRESS -> f.sortedByDescending { it.progress }
                Sort.SIZE -> f.sortedByDescending { it.size }
            }
            rowsCache = sorted + listOf<Transfer?>(null)
            rowsKeySnap = s
            rowsKeyFilter = filter
            rowsKeySort = sort
        }
        return rowsCache
    }

    /** Errors first (they need attention), then downloads, checking, seeds, stopped. */
    private fun activityRank(t: Transfer): Int = when {
        t.error -> 0
        t.downloading && !t.stopped -> 1
        t.checking -> 2
        t.finished && !t.stopped -> 3
        else -> 4
    }

    private fun currentTransfer(hash: String): Transfer? = snap?.transfers?.firstOrNull { it.hash == hash }

    private fun stateWord(t: Transfer): String = when {
        t.error -> "error"
        t.checking -> "checking"
        t.stopped -> "stopped"
        t.state == "metaDL" || t.state == "forcedMetaDL" -> "metadata"
        t.state == "queuedDL" || t.state == "queuedUP" -> "queued"
        t.state == "stalledDL" -> "stalled"
        t.downloading -> Fmt.speed(t.dlSpeed)
        filter == Filter.UNDER_WEEK -> "${Fmt.dur(t.seedingTime)} seeded"
        t.finished -> "seeding"
        else -> t.state
    }

    private fun iconNames(t: Transfer): List<String> {
        val ext = t.name.substringAfterLast('.', "").lowercase()
        val known = ext.length in 2..4 && ext.all { it.isLetterOrDigit() } && ext != t.name.lowercase()
        val byFile = if (known) IconNames.forFile(t.name, false) else emptyList()
        return byFile.filter { it != "text-x-generic" } + listOf("application-x-bittorrent", "folder-download")
    }

    private fun paintTransferRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val t = rows().getOrNull(i) ?: run {
            // the wrap-end MENU row — the state line when there is nothing else to show
            val n = snap?.transfers?.size ?: 0
            val label = if (n == 0) dn(stateLine).ifEmpty { if (snap == null) "connecting" else "no transfers" } else "Torrents"
            IconPaint.draw(g, services?.icons(), IconNames.forKind(IconKind.TORRENTS), r.x + 8, r.y + 6, 20, IconKind.TORRENTS, Level.DIM)
            Draw.fit(g, tx, r.x + 40, r.y + 5, label, Level.DIM, fRow, r.w - 64)
            Icons.tri(g, r.right - 36, r.y + 10, 11, Level.DIM)
            return
        }
        IconPaint.draw(g, services?.icons(), iconNames(t), r.x + 8, r.y + 6, 20, IconKind.TORRENTS, Level.DIM)
        val lv = if (t.error) Level.HEAD else Level.BODY
        Draw.fit(g, tx, r.x + 40, r.y + 5, dn(t.name), lv, fRow, r.w - 40 - 244)
        Icons.blocks(g, r.right - 236, r.y + 12, 112, 8, t.progress, n = 10, level = Level.of(5))
        // the state word: right-aligned in its 96 px column, fitted with the
        // mark when a font override makes it wider (review 2026-09-01 W18)
        val word = stateWord(t)
        val ww = tx.measure(word, fSmall)
        if (ww <= 96) Draw.right(g, tx, r.right - 24, r.y + 8, word, Level.DIM, fSmall)
        else Draw.fit(g, tx, r.right - 120, r.y + 8, word, Level.DIM, fSmall, 96)
    }

    private fun lensDetail(t: Transfer): String = when {
        t.error -> "error · ${Fmt.pct(t.progress)} · ${Fmt.bytes(t.size)} · ${t.state}"
        t.checking -> "${stateWord(t)} · ${Fmt.pct(t.progress)} · ${Fmt.bytes(t.size)}"
        t.stopped -> "stopped · ${Fmt.pct(t.progress)} · ${Fmt.bytes(t.size)} · ratio ${Fmt.ratio(t.ratio)}"
        t.downloading -> "${Fmt.pct(t.progress)} · ${Fmt.speed(t.dlSpeed)} down · ${Fmt.speed(t.upSpeed)} up · ${Fmt.eta(t.eta)} left · ${t.peers} peers"
        t.finished -> "seeding · ratio ${Fmt.ratio(t.ratio)} · ${Fmt.dur(t.seedingTime)} seeded" +
            (if (t.underAWeek) " · ${Fmt.dur(Transfer.WEEK_S - t.seedingTime)} to a week" else "") +
            " · ${Fmt.speed(t.upSpeed)} up"
        else -> "${t.state} · ${Fmt.pct(t.progress)} · ${Fmt.bytes(t.size)}"
    }

    private fun paintTransferLens(g: Gray8, r: Rect, i: Int) {
        val t = rows().getOrNull(i) ?: run {
            IconPaint.draw(g, services?.icons(), IconNames.forKind(IconKind.TORRENTS), r.x + 8, r.y + 4, 56, IconKind.TORRENTS, Level.HEAD)
            Draw.fit(g, tx, r.x + 72, r.y + 6, "Torrents", Level.HEAD, fHead, r.w - 88)
            val n = snap?.transfers?.size ?: 0
            val line = dn(stateLine, fBody).ifEmpty { if (snap == null) "connecting to the host" else "$n transfers · tap for the menu" }
            Draw.fit(g, tx, r.x + 72, r.y + 32, line, Level.BODY, fBody, r.w - 88)
            return
        }
        IconPaint.draw(g, services?.icons(), iconNames(t), r.x + 8, r.y + 4, 56, IconKind.TORRENTS, Level.HEAD)
        Draw.fit(g, tx, r.x + 72, r.y + 6, dn(t.name, fHead), Level.HEAD, fHead, r.w - 88)
        Draw.fit(g, tx, r.x + 72, r.y + 32, lensDetail(t), Level.BODY, fBody, r.w - 88 - 56)
        Icons.blocks(g, r.x + 72, r.y + 56, 240, 4, t.progress, n = 12, level = Level.of(6))
        // the 8-column speed history at the right edge: wide-and-short, 2 px steps
        val h = speedHist[t.hash]
        if (h != null) {
            val start = histIdx[t.hash] ?: 0
            val max = h.maxOrNull() ?: 0L
            if (max > 0) {
                val x0 = r.right - 8 - HIST * 6
                for (k in 0 until HIST) {
                    val v = h[(start + k) % HIST]
                    val bh = ((v * 12 / max).toInt() / 2 * 2).coerceIn(0, 12)
                    if (bh > 0) g.fillRect(x0 + k * 6, r.y + 58 - bh, 4, bh, Level.of(6))
                    else g.fillRect(x0 + k * 6, r.y + 56, 4, 2, Level.FAINT)
                }
            }
        }
    }

    private fun commitTransfer(i: Int) {
        val t = rows().getOrNull(i) ?: run { openTorrentsMenu(); return }
        openTransferMenu(t, fromDetails = false)
    }

    /** Tap on a transfer = its context menu, Details first (§1.7: the
     *  destructive rows sit last, behind confirms). */
    private fun openTransferMenu(t: Transfer, fromDetails: Boolean) {
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String = "", act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail))
            acts.add(act)
        }
        // row 0 is harmless either way (§1.7): Details from the list, a
        // Refresh of the file list from the document (review 2026-09-01 W10)
        if (!fromDetails) add("Details") { openDetails(t.hash) }
        else add("Refresh", "the file list") { loadDetail(t.hash) }
        if (t.stopped || t.error) add("Start") { runOp("starting") { provider.start(listOf(t.hash)); "started" } }
        else add("Stop") { runOp("stopping") { provider.stop(listOf(t.hash)); "stopped" } }
        add("Recheck") { runOp("rechecking") { provider.recheck(listOf(t.hash)); "rechecking" } }
        if (t.contentPath.isNotEmpty()) {
            add("Open in Files", "the payload") {
                if (services?.openWindow("files", "path:${t.contentPath}") != true) setNotice("Files is not available")
            }
            add("Open on PC") { runOp("opening on PC") { provider.openOnPc(t.contentPath); "opened on the PC" } }
        }
        add("Delete", "keep files") { confirmDelete(t, withFiles = false) }
        add("Delete + files", "2 confirms") { confirmDelete(t, withFiles = true) }
        services?.openMenu(MenuSurface.Spec(dm(t.name), items, onCommit = { idx -> acts.getOrNull(idx)?.invoke() }))
    }

    private fun confirmDelete(t: Transfer, withFiles: Boolean) {
        val name = dm(t.name)
        if (!withFiles) {
            services?.openMenu(MenuSurface.Spec("Delete '$name'?",
                listOf(MenuSurface.Item("Cancel"), MenuSurface.Item("Delete", detail = "files stay")),
                onCommit = { idx -> if (idx == 1) runOp("deleting") { provider.delete(listOf(t.hash), false); "deleted" } }))
            return
        }
        services?.openMenu(MenuSurface.Spec("Delete '$name' AND its files?",
            listOf(MenuSurface.Item("Cancel"), MenuSurface.Item("Continue", detail = "asks once more")),
            onCommit = { idx ->
                // the unrecoverable act sits at index 2 behind a disabled
                // spacer — never index 0/1 (WINDOWS.md §1; the Files purge shape)
                if (idx == 1) services?.openMenu(MenuSurface.Spec("Really delete the files?",
                    listOf(MenuSurface.Item("Cancel"), MenuSurface.Item("this cannot be undone", enabled = false),
                        MenuSurface.Item("Delete files", detail = "unrecoverable")),
                    onCommit = { j -> if (j == 2) runOp("deleting with files") { provider.delete(listOf(t.hash), true); "deleted with files" } }))
            }))
    }

    private fun openTorrentsMenu() {
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String = "", act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail))
            acts.add(act)
        }
        add("Browse TorrentLeech") { openCategories() }
        add("Search TorrentLeech", "keyboard") { openSearch() }
        for (q in recents.take(5)) add("Search", dm(q)) { listingFromTransfers = true; openListing(null, q) }
        add("Filter", filter.label) {
            filter = Filter.entries[(filter.ordinal + 1) % Filter.entries.size]
            transModel.cursor = 0
            services?.requestRender(this)
        }
        add("Sort", sort.label) {
            sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]
            transModel.cursor = 0
            services?.requestRender(this)
        }
        add("Seeding < 1 week", "${snap?.transfers?.count { it.underAWeek } ?: 0}") {
            filter = Filter.UNDER_WEEK
            transModel.cursor = 0
            services?.requestRender(this)
        }
        add("Refresh") { provider.refresh(); setNotice("refreshing") }
        add("Stats") { openStats() }
        services?.openMenu(MenuSurface.Spec("torrents", items, onCommit = { idx -> acts.getOrNull(idx)?.invoke() }))
    }

    // ================================================================ details
    private fun openDetails(hash: String) {
        openHash = hash
        level = Level_.DETAILS
        docModel.topLine = 0
        detail = null
        detailCache = null
        loadDetail(hash)
    }

    private fun loadDetail(hash: String) {
        val seq = ++detailSeq
        detailState = "loading"
        services?.setOperation("details")
        bg.launch(Dispatchers.IO) {
            val (d, err) = try {
                provider.detail(hash) to null
            } catch (e: Exception) {
                Log.e("torrents", "detail $hash failed", e)
                null to (e.message ?: "details failed")
            }
            onShell {
                if (seq != detailSeq) return@onShell   // superseded: back() or a newer load owns the op word (R2-W9)
                services?.setOperation("idle")
                if (err != null) {
                    detailState = err
                    setNotice(err)
                } else {
                    detailState = ""
                    detail = d
                }
                detailCache = null
                applyPendingDocTop()
                services?.requestRender(this@TorrentsWindow)
            }
        }
    }

    private var detailCache: List<DocLine>? = null
    private var detailCacheKey: Long = -1

    private fun detailLines(): List<DocLine> {
        val key = (snap?.version ?: -1L) * 7919 + (detail?.hashCode() ?: 0)
        val c = detailCache
        if (c != null && detailCacheKey == key) return c
        val out = ArrayList<DocLine>()
        val width = (services?.docContentWidth() ?: 560) - 32
        val t = openHash?.let { currentTransfer(it) }
        fun head(s: String) = out.add(DocLine(dn(s, fHead), fHead, Level.HEAD))
        fun line(s: String, f: FontSpec = fBody, lv: Int = Level.BODY, indent: Int = 0) {
            for (w in Wrap.wrap(dn(s, f), f, tx, width - indent)) out.add(DocLine(w, f, lv, indent))
        }
        if (t == null) {
            line(if (snap == null) "connecting" else "this transfer is gone", lv = Level.DIM)
        } else {
            head(t.name)
            line("${stateWord(t)} · ${Fmt.pct(t.progress)} of ${Fmt.bytes(t.size)}")
            line("down ${Fmt.speed(t.dlSpeed)} · up ${Fmt.speed(t.upSpeed)}" +
                (if (t.downloading && !t.stopped) " · ${Fmt.eta(t.eta)} left" else ""))
            line("ratio ${Fmt.ratio(t.ratio)} · ${Fmt.bytes(t.downloaded)} received · ${Fmt.bytes(t.uploaded)} sent")
            line("${t.seeds} seeds of ${t.seedsTotal} · ${t.peers} peers of ${t.peersTotal}")
            line("added ${Fmt.age(t.addedOn)} ago" +
                (if (t.completedOn > 0) " · finished ${Fmt.age(t.completedOn)} ago · ${Fmt.dur(t.seedingTime)} seeded" else ""))
            if (t.underAWeek) line("${Fmt.dur(Transfer.WEEK_S - t.seedingTime)} to a week of seeding", lv = Level.HEAD)
            line(t.savePath.ifEmpty { "-" }, fSmall, Level.DIM)
            if (t.tracker.isNotEmpty()) line(t.tracker.substringAfter("://").substringBefore('/'), fSmall, Level.DIM)
            if (t.category.isNotEmpty() || t.tags.isNotEmpty()) line(listOf(t.category, t.tags).filter { it.isNotEmpty() }.joinToString(" · "), fSmall, Level.DIM)
            out.add(DocLine("", fBody, Level.BODY))
            val d = detail
            when {
                d != null -> {
                    head("Files (${d.files.size})")
                    for (f in d.files) line("${f.name} · ${Fmt.bytes(f.size)} · ${Fmt.pct(f.progress)}", fBody, Level.BODY, 16)
                    if (d.comment.isNotEmpty()) { out.add(DocLine("", fBody, Level.BODY)); line(d.comment, fSmall, Level.DIM) }
                }
                detailState == "loading" -> line("loading the file list", lv = Level.DIM)
                detailState.isNotEmpty() -> line("file list failed: $detailState", lv = Level.DIM)
            }
        }
        detailCache = out
        detailCacheKey = key
        return out
    }

    private fun lineH(f: FontSpec): Int {
        val m = tx.metrics(f)
        return (((m.ascent + m.descent + 3) / 2) * 2).coerceAtLeast(18)
    }

    private fun paintDocLine(g: Gray8, l: DocLine, r: Rect) {
        if (l.s.isEmpty()) return
        val m = tx.metrics(l.f)
        val off = ((r.h - (m.ascent + m.descent)) / 2).coerceAtLeast(0)
        Draw.fit(g, tx, r.x + 16 + l.indent, r.y + off, l.s, l.lv, l.f, r.w - 32 - l.indent)
    }

    private fun paintDetailLine(g: Gray8, i: Int, r: Rect) {
        detailLines().getOrNull(i)?.let { paintDocLine(g, it, r) }
    }

    // ================================================================ browse
    private fun openCategories() {
        level = Level_.CATEGORIES
        catModel.cursor = 0
    }

    /** Rows: Newest (null) · the 40 categories · the wrap-end menu. The table
     *  is core's constant — never a provider call from a paint (on the phone
     *  that was a blocking channel request on the loop, review W1). */
    private val catRowsCache: List<Any?> by lazy { listOf<Any?>(null) + TorrentLeech.CATEGORIES + listOf(LRow.Menu) }
    private fun catRows(): List<Any?> = catRowsCache

    private fun groupIcons(group: String): List<String> = when (group) {
        "Movies" -> listOf("video-x-generic", "applications-multimedia")
        "TV", "Foreign" -> listOf("video-television", "video-x-generic")
        "Games" -> listOf("applications-games", "input-gaming")
        "Apps" -> listOf("application-x-executable", "applications-other")
        "Books" -> listOf("accessories-ebook-reader", "x-office-document")
        "Music" -> listOf("audio-x-generic", "multimedia-audio-player")
        "Education" -> listOf("applications-education", "applications-science")
        "Animation" -> listOf("applications-graphics", "video-x-generic")
        else -> listOf("application-x-bittorrent")
    }

    private fun paintCatRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        when (val c = catRows().getOrNull(i)) {
            null -> {
                IconPaint.draw(g, services?.icons(), listOf("view-refresh", "folder-download"), r.x + 8, r.y + 6, 20, IconKind.TORRENTS, Level.DIM)
                Draw.fit(g, tx, r.x + 40, r.y + 5, "Newest", Level.BODY, fRow, r.w - 64)
            }
            is TlCategory -> {
                IconPaint.draw(g, services?.icons(), groupIcons(c.group), r.x + 8, r.y + 6, 20, IconKind.TORRENTS, Level.DIM)
                val gw = tx.measure(c.group, fSmall) + 12
                tx.draw(g, (r.x + 40) / 4 * 4, (r.y + 9) / 2 * 2, c.group, fSmall, Level.DIM)
                Draw.fit(g, tx, r.x + 40 + gw / 4 * 4, r.y + 5, c.name, Level.BODY, fRow, r.w - 64 - gw)
            }
            else -> {
                Draw.fit(g, tx, r.x + 40, r.y + 5, "Browse", Level.DIM, fRow, r.w - 64)
                Icons.tri(g, r.right - 36, r.y + 10, 11, Level.DIM)
            }
        }
    }

    private fun paintCatLens(g: Gray8, r: Rect, i: Int) {
        when (val c = catRows().getOrNull(i)) {
            null -> {
                IconPaint.draw(g, services?.icons(), listOf("view-refresh", "folder-download"), r.x + 8, r.y + 4, 56, IconKind.TORRENTS, Level.HEAD)
                Draw.fit(g, tx, r.x + 72, r.y + 6, "Newest", Level.HEAD, fHead, r.w - 88)
                Draw.fit(g, tx, r.x + 72, r.y + 32, "every category, newest first", Level.BODY, fBody, r.w - 88)
            }
            is TlCategory -> {
                IconPaint.draw(g, services?.icons(), groupIcons(c.group), r.x + 8, r.y + 4, 56, IconKind.TORRENTS, Level.HEAD)
                Draw.fit(g, tx, r.x + 72, r.y + 6, "${c.group} · ${c.name}", Level.HEAD, fHead, r.w - 88)
                Draw.fit(g, tx, r.x + 72, r.y + 32, "tap to browse, newest first", Level.BODY, fBody, r.w - 88)
            }
            else -> {
                Draw.fit(g, tx, r.x + 72, r.y + 6, "Browse", Level.HEAD, fHead, r.w - 88)
                Draw.fit(g, tx, r.x + 72, r.y + 32, "search · recent searches · account", Level.BODY, fBody, r.w - 88)
            }
        }
    }

    private fun commitCategory(i: Int) {
        when (val c = catRows().getOrNull(i)) {
            null -> { listingFromTransfers = false; openListing(null, null) }
            is TlCategory -> { listingFromTransfers = false; openListing(c.id, null) }
            else -> openBrowseMenu()
        }
    }

    private fun openListing(cat: Int?, query: String?) {
        listingCat = cat
        listingQuery = query
        listing.clear()
        listingTotal = -1
        listingPage = 0
        listingLoading = false
        listingState = ""
        listingRetryAt = 0L
        listSeq++
        listModel.cursor = 0
        level = Level_.LISTING
        loadNextPage()
    }

    private val listingMore: Boolean get() = listingTotal < 0 || listing.size < listingTotal

    private var listingRowsKey: Triple<Int, Boolean, Boolean>? = null
    private var listingRowsCache: List<LRow> = emptyList()

    /** Cached by (size, loading, more/state) — called per row paint (R2-W1). */
    private fun listingRows(): List<LRow> {
        val tail = listingLoading || listingMore || listingState.isNotEmpty()
        val key = Triple(listing.size, listingLoading, tail)
        if (listingRowsKey != key) {
            val out = ArrayList<LRow>(listing.size + 2)
            for (it in listing) out.add(LRow.Item(it))
            if (tail) out.add(LRow.Loading)
            out.add(LRow.Menu)
            listingRowsCache = out
            listingRowsKey = key
        }
        return listingRowsCache
    }

    /** Demand the next page from the CURSOR's position — never from a row
     *  being painted: the panning list wraps its tail rows ABOVE the cursor,
     *  so a paint-time demand from the Loading row fetched every page of a
     *  category while the cursor sat on row 0 (R2-W1, the L1 class). */
    private fun demandPageIfNear() {
        if (listingLoading || !listingMore) return
        if (listModel.cursor >= listing.size - 8) loadNextPage()   // honours the 5 s pacing itself
    }

    private fun loadNextPage() {
        if (listingLoading || !listingMore) return
        if (System.currentTimeMillis() < listingRetryAt) return
        listingLoading = true
        val seq = listSeq
        val page = listingPage + 1
        val cat = listingCat
        val q = listingQuery
        val sortNow = tlSort
        services?.setOperation(if (q != null) "searching" else "browsing")
        bg.launch(Dispatchers.IO) {
            val (p, err) = try {
                (if (q != null) provider.tlSearch(q, page, sortNow) else provider.tlBrowse(cat, page, sortNow)) to null
            } catch (e: Exception) {
                Log.e("torrents", "listing page $page failed", e)
                null to (e.message ?: "listing failed")
            }
            onShell {
                if (seq != listSeq) return@onShell    // superseded; openListing/back reset the flags (R2-W8/W9)
                services?.setOperation("idle")
                listingLoading = false
                if (err != null || p == null) {
                    listingState = err ?: "listing failed"
                    listingRetryAt = System.currentTimeMillis() + RETRY_PACING_MS
                    setNotice(listingState)
                    scheduleRetryPaint(seq)
                } else {
                    listingState = ""
                    // the site pages by its own perPage; a short page means the end
                    listing.addAll(p.items.filter { n -> listing.none { it.fid == n.fid } })
                    listingPage = page
                    listingTotal = if (p.items.isEmpty()) listing.size else maxOf(p.total, listing.size)
                    if (p.items.size < p.perPage) listingTotal = listing.size
                    pendingListCursor?.let { c ->              // the restored row, once it exists (W4)
                        if (c < listing.size) { listModel.cursor = c; pendingListCursor = null }
                    }
                }
                services?.requestRender(this@TorrentsWindow)
            }
        }
    }

    private fun scheduleRetryPaint(seq: Int) {
        bg.launch {
            kotlinx.coroutines.delay(RETRY_PACING_MS)
            onShell { if (seq == listSeq && level == Level_.LISTING) services?.requestRender(this@TorrentsWindow) }
        }
    }

    private fun catNameOf(id: Int): String = TorrentLeech.category(id)?.name ?: "category $id"
    private fun groupOf(id: Int): String = TorrentLeech.category(id)?.group ?: ""

    private fun ageOf(it: TlItem): String {
        // "2026-09-02 01:19:25" in UTC (the listing says userTimeZone: UTC)
        return try {
            val t = java.time.LocalDateTime.parse(it.addedAt.trim().replace(' ', 'T'))
            Fmt.age(t.toEpochSecond(java.time.ZoneOffset.UTC)) + " ago"
        } catch (e: Exception) {
            it.addedAt
        }
    }

    /** A small drawn up/down arrow for seeders/leechers (UI symbols are drawn, never typed). */
    private fun arrow(g: Gray8, x: Int, y: Int, up: Boolean, lv: Int) {
        if (up) g.fillPolygon(intArrayOf(x, x + 8, x + 4), intArrayOf(y + 8, y + 8, y), lv)
        else g.fillPolygon(intArrayOf(x, x + 8, x + 4), intArrayOf(y, y, y + 8), lv)
    }

    private fun paintListingRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val rows = listingRows()
        when (val row = rows.getOrNull(i)) {
            is LRow.Item -> {
                val it = row.it
                demandPageIfNear()
                IconPaint.draw(g, services?.icons(), groupIcons(groupOf(it.categoryId)), r.x + 8, r.y + 6, 20, IconKind.TORRENTS, Level.DIM)
                Draw.fit(g, tx, r.x + 40, r.y + 5, dn(it.name), Level.BODY, fRow, r.w - 40 - 236)
                var x = r.right - 24
                Draw.right(g, tx, x, r.y + 8, Fmt.bytes(it.size), Level.DIM, fSmall); x -= 84
                Draw.right(g, tx, x, r.y + 8, "${it.leechers}", Level.DIM, fSmall); x -= tx.measure("${it.leechers}", fSmall) + 12
                arrow(g, x / 4 * 4, r.y + 12, up = false, Level.DIM); x -= 12
                Draw.right(g, tx, x, r.y + 8, "${it.seeders}", Level.DIM, fSmall); x -= tx.measure("${it.seeders}", fSmall) + 12
                arrow(g, x / 4 * 4, r.y + 12, up = true, Level.DIM); x -= 16
                if (it.freeleech) Draw.right(g, tx, x, r.y + 8, "FL", Level.MID, fSmall)
            }
            is LRow.Loading -> {
                val waiting = !listingLoading && listingState.isNotEmpty() && System.currentTimeMillis() < listingRetryAt
                val s = when {
                    listingLoading -> if (listing.isEmpty()) (if (listingQuery != null) "searching" else "loading") else "loading more"
                    waiting -> "failed - retrying: ${dn(listingState)}"
                    listingState.isNotEmpty() -> "failed - retrying now: ${dn(listingState)}"
                    else -> "more"
                }
                Draw.fit(g, tx, r.x + 40, r.y + 5, s, Level.REST, fRow, r.w - 64)
                // the paced retry lives HERE too (review W7): an empty listing
                // has no item row to demand from — but only when the CURSOR is
                // on this row or the listing is empty (R2-W1)
                if (listing.isEmpty() || listModel.cursor >= listing.size) demandPageIfNear()
            }
            is LRow.Menu -> {
                Draw.fit(g, tx, r.x + 40, r.y + 5, "Browse", Level.DIM, fRow, r.w - 64)
                Icons.tri(g, r.right - 36, r.y + 10, 11, Level.DIM)
            }
            null -> {}
        }
    }

    private fun paintListingLens(g: Gray8, r: Rect, i: Int) {
        when (val row = listingRows().getOrNull(i)) {
            is LRow.Item -> {
                val it = row.it
                IconPaint.draw(g, services?.icons(), groupIcons(groupOf(it.categoryId)), r.x + 8, r.y + 4, 56, IconKind.TORRENTS, Level.HEAD)
                Draw.fit(g, tx, r.x + 72, r.y + 4, dn(it.name, fHead), Level.HEAD, fHead, r.w - 88)
                val l2 = "${Fmt.bytes(it.size)} · ${it.seeders} seeds · ${it.leechers} peers · ${it.snatched} done · ${ageOf(it)}" +
                    (if (it.freeleech) " · FL" else "")
                Draw.fit(g, tx, r.x + 72, r.y + 28, l2, Level.BODY, fBody, r.w - 88)
                val l3 = (listOf(catNameOf(it.categoryId)) + it.tags).joinToString(" · ")
                Draw.fit(g, tx, r.x + 72, r.y + 48, dn(l3, fSmall), Level.DIM, fSmall, r.w - 88)
            }
            is LRow.Loading -> {
                Draw.fit(g, tx, r.x + 72, r.y + 6, if (listingLoading) "loading" else "more", Level.HEAD, fHead, r.w - 88)
                Draw.fit(g, tx, r.x + 72, r.y + 32,
                    if (listingTotal >= 0) "${listing.size} of $listingTotal loaded" else "asking TorrentLeech", Level.BODY, fBody, r.w - 88)
            }
            is LRow.Menu -> {
                Draw.fit(g, tx, r.x + 72, r.y + 6, "Browse", Level.HEAD, fHead, r.w - 88)
                Draw.fit(g, tx, r.x + 72, r.y + 32, "search · sort ($tlSort) · refresh · account", Level.BODY, fBody, r.w - 88)
            }
            null -> {}
        }
    }

    private fun commitListing(i: Int) {
        when (val row = listingRows().getOrNull(i)) {
            is LRow.Item -> openTorrent(row.it.fid)
            is LRow.Loading -> { listingRetryAt = 0L; listingState = ""; loadNextPage() }
            is LRow.Menu -> openBrowseMenu()
            null -> {}
        }
    }

    private fun openBrowseMenu() {
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String = "", act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail))
            acts.add(act)
        }
        add("Search TorrentLeech", "keyboard") { openSearch() }
        for (q in recents.take(5)) add("Search", q) { openListing(null, q) }
        if (level == Level_.LISTING) {
            // rows that act on a listing exist only inside one (R2-W7)
            add("Sort", tlSort) {
                val i = TorrentLeech.SORTS.indexOf(tlSort)
                tlSort = TorrentLeech.SORTS[(i + 1) % TorrentLeech.SORTS.size]
                openListing(listingCat, listingQuery)
            }
            add("Refresh") { openListing(listingCat, listingQuery) }
        }
        add("Account", "TorrentLeech") { openStats() }
        services?.openMenu(MenuSurface.Spec("browse", items, onCommit = { idx -> acts.getOrNull(idx)?.invoke() }))
    }

    // ================================================================ search (the §4.8 keyboard)
    private fun openSearch() {
        val opened = services?.openKeyboard(KeyboardSurface.Spec(
            title = "search torrentleech", initial = searchDraft,
            onCommit = { q ->
                searchDraft = ""
                if (q.isBlank()) setNotice("empty search") else runSearch(q.trim())
            },
            onCancel = { d -> searchDraft = d },
        ), owner = this) == true
        if (!opened) setNotice("the keyboard is not available here — type on the phone strip")
    }

    private fun runSearch(q: String) {
        recents.remove(q)
        recents.addFirst(q)
        while (recents.size > RECENTS) recents.removeLast()
        if (level == Level_.TRANSFERS || level == Level_.DETAILS) listingFromTransfers = true
        openListing(null, q)
    }

    // ================================================================ the torrent page
    private fun openTorrent(fid: String) {
        openFid = fid
        level = Level_.TORRENT
        tlDocModel.topLine = 0
        tlDetail = null
        tlCache = null
        loadTorrent(fid)
    }

    private fun loadTorrent(fid: String) {
        val seq = ++tlSeq
        tlDetailState = "loading"
        services?.setOperation("torrent page")
        bg.launch(Dispatchers.IO) {
            val (d, err) = try {
                provider.tlDetail(fid) to null
            } catch (e: Exception) {
                Log.e("torrents", "torrent page $fid failed", e)
                null to (e.message ?: "torrent page failed")
            }
            onShell {
                if (seq != tlSeq) return@onShell      // superseded (R2-W9)
                services?.setOperation("idle")
                if (err != null) {
                    tlDetailState = err
                    setNotice(err)
                } else {
                    tlDetailState = ""
                    tlDetail = d
                }
                tlCache = null
                pendingTlDocTop?.let { tlDocModel.topLine = it; pendingTlDocTop = null }   // W4
                services?.requestRender(this@TorrentsWindow)
            }
        }
    }

    private var tlCache: List<DocLine>? = null

    private fun tlLines(): List<DocLine> {
        tlCache?.let { return it }
        val out = ArrayList<DocLine>()
        val width = (services?.docContentWidth() ?: 560) - 32
        fun head(s: String) = out.add(DocLine(dn(s, fHead), fHead, Level.HEAD))
        fun line(s: String, f: FontSpec = fBody, lv: Int = Level.BODY, indent: Int = 0) {
            if (s.isEmpty()) { out.add(DocLine("", f, lv)); return }
            for (w in Wrap.wrap(dn(s, f), f, tx, width - indent)) out.add(DocLine(w, f, lv, indent))
        }
        val d = tlDetail
        val item = openFid?.let { f -> listing.firstOrNull { it.fid == f } }
        if (d == null) {
            head(item?.name ?: "torrent")
            line(when {
                tlDetailState == "loading" -> "loading the torrent page"
                tlDetailState.isNotEmpty() -> "failed: $tlDetailState"   // line() runs it through dn()
                else -> ""
            }, lv = Level.DIM)
        } else {
            head(d.name)
            line(listOf(d.category, d.size, if (d.tags.any { it.equals("FREELEECH", true) }) "FREELEECH" else "")
                .filter { it.isNotEmpty() }.joinToString(" · "))
            line("${d.seeders} seeders · ${d.leechers} leechers · ${d.snatched} snatched")
            line(listOf(d.added, if (d.uploader.isNotEmpty()) "by ${d.uploader}" else "").filter { it.isNotEmpty() }.joinToString(" · "), fSmall, Level.DIM)
            val tags = d.tags.filter { !it.equals("FREELEECH", true) }
            if (tags.isNotEmpty()) line(tags.joinToString(" · "), fSmall, Level.DIM)
            line("tap for Add · Add stopped · Open on PC", fSmall, Level.DIM)
            if (d.description.isNotEmpty()) {
                line("")
                head("Description")
                for (para in d.description.split('\n')) line(para.trim())
            }
            if (d.nfo.isNotEmpty()) {
                line("")
                head("NFO")
                for (nl in d.nfo.split('\n')) line(nl.trimEnd(), fMono, Level.BODY)
            }
            if (d.files.isNotEmpty()) {
                line("")
                head("Files (${d.files.size})")
                for (f in d.files) line("${f.name} · ${f.size}", fBody, Level.BODY, 16)
            }
        }
        tlCache = out
        return out
    }

    private fun paintTlLine(g: Gray8, i: Int, r: Rect) {
        tlLines().getOrNull(i)?.let { paintDocLine(g, it, r) }
    }

    private fun openAddMenu() {
        val fid = openFid ?: return
        val d = tlDetail
        val name = dm(d?.name ?: listing.firstOrNull { it.fid == fid }?.name ?: fid)
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String = "", act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail))
            acts.add(act)
        }
        add("Add to qBittorrent", "starts now") { confirmAdd(fid, name, stopped = false) }
        add("Add stopped", "added, not started") { confirmAdd(fid, name, stopped = true) }
        add("Open on PC", "the TorrentLeech page") {
            val url = d?.url ?: "https://www.torrentleech.org/torrent/$fid"
            runOp("opening on PC") { provider.openOnPc(url); "opened on the PC" }
        }
        services?.openMenu(MenuSurface.Spec(name, items, onCommit = { idx -> acts.getOrNull(idx)?.invoke() }))
    }

    /** Add is an outbound act: it stages a confirm (§16.11). */
    private fun confirmAdd(fid: String, name: String, stopped: Boolean) {
        services?.openMenu(MenuSurface.Spec("Add '$name'?",
            listOf(MenuSurface.Item("Cancel"), MenuSurface.Item(if (stopped) "Add stopped" else "Add", detail = "to ~/Downloads")),
            onCommit = { idx ->
                if (idx == 1) runOp("adding") { "added · " + provider.tlAdd(fid, stopped) }   // runOp sanitizes the notice on the loop
            }))
    }

    // ================================================================ stats
    private fun openStats() {
        if (opBusy) { setNotice("busy — one operation at a time"); return }
        opBusy = true
        services?.setOperation("stats")
        val fromLevel = level
        val s = snap                      // captured ON the loop (review W14)
        val st = stateLine
        bg.launch(Dispatchers.IO) {
            val lines = ArrayList<MenuSurface.Item>()
            if (s != null) {
                lines.add(MenuSurface.Item("Down", Fmt.speed(s.session.dlSpeed)))
                lines.add(MenuSurface.Item("Up", Fmt.speed(s.session.upSpeed)))
                lines.add(MenuSurface.Item("Session", "${Fmt.bytes(s.session.dlSession)} / ${Fmt.bytes(s.session.upSession)}"))
                lines.add(MenuSurface.Item("All time", "${Fmt.bytes(s.session.allDl)} / ${Fmt.bytes(s.session.allUl)}"))
                lines.add(MenuSurface.Item("Ratio", s.session.ratio.ifEmpty { "-" }))
                if (s.session.freeSpace >= 0) lines.add(MenuSurface.Item("Free space", Fmt.bytes(s.session.freeSpace)))
                lines.add(MenuSurface.Item("Peers", "${s.session.peers} · ${s.session.status}"))
                lines.add(MenuSurface.Item("Seeding < 1 week", "${s.transfers.count { it.underAWeek }}"))
                if (s.session.version.isNotEmpty()) lines.add(MenuSurface.Item("qBittorrent", s.session.version))
            } else {
                lines.add(MenuSurface.Item("qBittorrent", st.ifEmpty { "no snapshot yet" }))
            }
            // the tracker's strings are sanitized on the loop below (dm is loop-only)
            val raw = ArrayList<Pair<String, String>>()
            try {
                val a = provider.tlAccount()
                raw.add("TL uploaded" to a.uploaded)
                raw.add("TL downloaded" to a.downloaded)
                raw.add("TL ratio" to a.ratio)
                if (a.points.isNotEmpty()) raw.add("TL points" to a.points)
                if (a.klass.isNotEmpty()) raw.add("TL class" to a.klass)
            } catch (e: Exception) {
                Log.w("torrents", "TorrentLeech account: ${e.message}")
                raw.add("TorrentLeech" to (e.message ?: "account failed"))
            }
            onShell {
                for ((k, v) in raw) lines.add(MenuSurface.Item(k, dm(v)))
                opBusy = false
                services?.setOperation("idle")
                val shown = level == fromLevel &&
                    services?.openMenu(MenuSurface.Spec("stats", lines, onCommit = { }), owner = this@TorrentsWindow) == true
                if (!shown) {
                    services?.notifyInternal("torrent", "stats: " + lines.joinToString(" · ") { "${it.label} ${it.detail}" })
                }
            }
        }
    }

    // ================================================================ ops
    /** One provider op at a time, narrated, loud on failure; the returned
     *  string becomes the title notice. */
    private fun runOp(verb: String, op: () -> String?) {
        if (opBusy) {
            setNotice("busy — one operation at a time")
            return
        }
        opBusy = true
        services?.setOperation(verb)
        bg.launch(Dispatchers.IO) {
            val (msg, err) = try {
                op() to null
            } catch (e: Exception) {
                Log.e("torrents", "$verb failed", e)
                null to (e.message ?: "$verb failed")
            }
            onShell {
                opBusy = false
                services?.setOperation("idle")
                if (err != null) {
                    setNotice(err)
                    services?.notifyInternal("torrent", "$verb failed: ${dn(err, fSmall)}")
                } else if (msg != null) {
                    setNotice(dn(msg, fSmall))
                }
                provider.refresh()
                services?.requestRender(this@TorrentsWindow)
            }
        }
    }

    // ================================================================ settings
    private val settingsRows: List<HostSetting> by lazy {
        listOf(
            HostSetting("Notify · done", listOf("on", "off"),
                { if (notifyDone) "on" else "off" }, { notifyDone = it == "on" }),
            HostSetting("Notify · errors", listOf("on", "off"),
                { if (notifyErrors) "on" else "off" }, { notifyErrors = it == "on" }),
            HostSetting("Poll", PACES.keys.toList(),
                { PACES.entries.firstOrNull { it.value == pollMs }?.key ?: "2 s" },
                // applied from Settings, i.e. while this window is NOT focused:
                // the new pace takes effect on the next focus (review W5)
                { v -> pollMs = PACES[v] ?: 2_000L; if (active) provider.setFocused(true, pollMs) }),
            HostSetting("Size", listOf("global") + wm.damage.core.shell.ShellSettings.HEIGHTS.map { "$it" },
                { heightPref?.toString() ?: "global" },
                { heightPref = it.toIntOrNull() }),
        )
    }

    override fun appSettings(): List<HostSetting> = settingsRows

    // ================================================================ persistence
    override fun saveState(): JsonObject = buildJsonObject {
        put("level", level.name)
        put("cursor", transModel.cursor)
        rows().getOrNull(transModel.cursor)?.hash?.let { put("cursorHash", it) }   // the row, not the index (R2-W2)
        put("catCursor", catModel.cursor)
        put("listCursor", listModel.cursor)
        put("docTop", docModel.topLine)
        put("tlDocTop", tlDocModel.topLine)
        put("filter", filter.name)
        put("sort", sort.name)
        openHash?.let { put("openHash", it) }
        listingCat?.let { put("listingCat", it) }
        listingQuery?.let { put("listingQuery", it) }
        put("listingFromTransfers", listingFromTransfers)
        openFid?.let { put("openFid", it) }
        put("tlSort", tlSort)
        put("searchDraft", searchDraft)
        putJsonArray("recents") { recents.forEach { add(JsonPrimitive(it)) } }
        put("notifyDone", notifyDone)
        put("notifyErrors", notifyErrors)
        put("pollMs", pollMs)
        heightPref?.let { put("height", it) }
    }

    override fun restoreState(state: JsonObject) {
        level = state["level"]?.jsonPrimitive?.contentOrNull?.let { n -> Level_.entries.firstOrNull { it.name == n } } ?: Level_.TRANSFERS
        transModel.cursor = state["cursor"]?.jsonPrimitive?.intOrNull ?: 0
        catModel.cursor = state["catCursor"]?.jsonPrimitive?.intOrNull ?: 0
        listModel.cursor = state["listCursor"]?.jsonPrimitive?.intOrNull ?: 0
        docModel.topLine = state["docTop"]?.jsonPrimitive?.intOrNull ?: 0
        tlDocModel.topLine = state["tlDocTop"]?.jsonPrimitive?.intOrNull ?: 0
        filter = state["filter"]?.jsonPrimitive?.contentOrNull?.let { n -> Filter.entries.firstOrNull { it.name == n } } ?: Filter.ALL
        sort = state["sort"]?.jsonPrimitive?.contentOrNull?.let { n -> Sort.entries.firstOrNull { it.name == n } } ?: Sort.ACTIVITY
        openHash = state["openHash"]?.jsonPrimitive?.contentOrNull
        listingCat = state["listingCat"]?.jsonPrimitive?.intOrNull
        listingQuery = state["listingQuery"]?.jsonPrimitive?.contentOrNull
        listingFromTransfers = state["listingFromTransfers"]?.jsonPrimitive?.booleanOrNull ?: false
        openFid = state["openFid"]?.jsonPrimitive?.contentOrNull
        tlSort = state["tlSort"]?.jsonPrimitive?.contentOrNull?.takeIf { it in TorrentLeech.SORTS } ?: "added"
        searchDraft = state["searchDraft"]?.jsonPrimitive?.contentOrNull ?: ""
        recents.clear()
        state["recents"]?.let { arr ->
            try { for (e in arr.jsonArray) e.jsonPrimitive.contentOrNull?.let { recents.addLast(it) } } catch (e: Exception) {
                Log.w("torrents", "recent searches unreadable in the stored record — dropped: ${e.message}")
            }
        }
        notifyDone = state["notifyDone"]?.jsonPrimitive?.booleanOrNull ?: true
        notifyErrors = state["notifyErrors"]?.jsonPrimitive?.booleanOrNull ?: true
        pollMs = state["pollMs"]?.jsonPrimitive?.longOrNull?.takeIf { it in PACES.values } ?: 2_000L
        heightPref = state["height"]?.jsonPrimitive?.intOrNull?.takeIf { it in wm.damage.core.shell.ShellSettings.HEIGHTS }
        // a restored open level reloads its content on activation (the mode
        // restores now, the bytes when the window is looked at — §9.1)
        if (level == Level_.DETAILS && openHash == null) level = Level_.TRANSFERS
        if (level == Level_.TORRENT && openFid == null) level = Level_.LISTING
        if (level == Level_.LISTING && listingCat == null && listingQuery == null && !listingFromTransfers) {
            // "newest" browse — fine, it reloads
        }
        listing.clear()
        listingTotal = -1
        listingPage = 0
        listingLoading = false
        listingState = ""
        detail = null
        detailState = ""
        tlDetail = null
        tlDetailState = ""
        // an in-flight answer for the PREVIOUS item must not land on the
        // restored one (review W3 — the Files R4/R5 class)
        detailSeq++; listSeq++; tlSeq++
        // positions re-apply when their content lands (W4): the first paint
        // would clamp them against an empty list or a placeholder document.
        // The transfers cursor restores by the ROW it was on (its hash) — the
        // details' hash only when the details ARE the level (R2-W2) — with
        // the saved index as the fallback for a row that is gone
        pendingTransHash = if (level == Level_.DETAILS) openHash
            else state["cursorHash"]?.jsonPrimitive?.contentOrNull
        pendingTransIndex = transModel.cursor
        pendingListCursor = listModel.cursor.takeIf { level == Level_.LISTING || level == Level_.TORRENT }
        listingRetryAt = 0L
        pendingDocTop = docModel.topLine.takeIf { level == Level_.DETAILS && it > 0 }
        pendingTlDocTop = tlDocModel.topLine.takeIf { level == Level_.TORRENT && it > 0 }
        invalidateDocs()
        needsReload = level != Level_.TRANSFERS && level != Level_.CATEGORIES
    }

    /** A live-synced record (§16.4): the same restore, then the reload the
     *  boot path leaves to activation happens NOW — the window may be the
     *  focused one and would otherwise sit on a header with no file list
     *  until the user leaves and returns (review 2026-09-01 W2). */
    override fun restoreStateLive(state: JsonObject) {
        val keepDetail = detail?.takeIf { it.hash == state["openHash"]?.jsonPrimitive?.contentOrNull }
        val keepPage = tlDetail?.takeIf { it.fid == state["openFid"]?.jsonPrimitive?.contentOrNull }
        restoreState(state)
        if (active) provider.setFocused(true, pollMs)            // a synced Poll change applies now (R2-W15)
        // the same item's content survives the apply: only the position moves
        if (keepDetail != null) { detail = keepDetail; detailState = ""; applyPendingDocTop() }
        if (keepPage != null) { tlDetail = keepPage; tlDetailState = ""; pendingTlDocTop?.let { tlDocModel.topLine = it; pendingTlDocTop = null } }
        // an UNFOCUSED window reloads on its next activation, not on every
        // peer save (R2-W5): a tracker page per save, and op words painted
        // onto whoever is focused, were the cost of reloading blind
        if (services == null || !active) return
        needsReload = false
        when (level) {
            Level_.DETAILS -> if (keepDetail == null) openHash?.let { loadDetail(it) }
            Level_.LISTING -> loadNextPage()
            Level_.TORRENT -> if (keepPage == null) openFid?.let { loadTorrent(it) }
            else -> {}
        }
    }

    companion object {
        const val HIST = 8
        const val RECENTS = 10
        const val RETRY_PACING_MS = 5_000L
        val PACES = linkedMapOf("1 s" to 1_000L, "2 s" to 2_000L, "5 s" to 5_000L)
    }
}
