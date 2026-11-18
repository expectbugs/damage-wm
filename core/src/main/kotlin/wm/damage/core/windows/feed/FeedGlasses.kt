package wm.damage.core.windows.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import wm.damage.core.app.Answer
import wm.damage.core.app.Draft
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.MemoryRecord
import wm.damage.core.app.Op
import wm.damage.core.app.Peer
import wm.damage.core.app.Record
import wm.damage.core.app.SourceListener
import wm.damage.core.app.WindowSource
import wm.damage.core.app.WindowState
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.IconNames
import wm.damage.core.gfx.IconPaint
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import wm.damage.core.shell.ActivationSource
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
import wm.damage.core.windows.feed.FeedCore.Flag

/** The name the window had before its conversion, kept for the sites that name its type (the test sites, the tools). */
typealias FeedWindow = FeedGlasses

/**
 * The compatibility factory (build S1-3 B6): Feed as it was built before its conversion — a [FeedCore] over a record of
 * its own in memory, and the glasses' presenter over that. The test sites, `--selfcheck` and the tools build Feed this
 * way; the hosts build the presenter over the PC's record or the phone's working copy, where the PC raises the notices.
 */
fun FeedWindow(text: TextRasterizer, provider: FeedProvider, bg: CoroutineScope,
               clock: () -> Long = { System.currentTimeMillis() }): FeedGlasses {
    val core = FeedCore()
    return FeedGlasses(text, provider, bg, core, LocalWindowSource(core, MemoryRecord(FeedCore.ACTIONS)), clock)
}

/**
 * FEED — `FEED.md` (design settled with Adam 2026-09-09): Reddit popular,
 * Slashdot, xkcd, SMBC and the 8-Bit Theater archive, on glass.
 *
 *   SOURCES (list, root) ─tap─▶ ITEMS (list) ─tap─▶ ARTICLE / COMIC (doc) ─tap─▶ ACTIONS (list) ─▶ COMMENTS (doc)
 *      │ wrap-end "Feed" → root MENU        │ wrap-end "<source>" → source MENU (Browse…, recents, Pin, Mark all read)
 *      └─ the archive row ─tap─▶ BINGE (endless doc) ─tap─▶ BINGE ACTIONS
 *      root MENU → FLAGGED (list)
 *
 * The Reader grammar: one tap OPENS; actions live on the Document's tap and
 * on each list's wrap-end row. Everything the provider does is off-loop;
 * every completion applies through [ShellServices.runOnShell] behind a
 * sequence, so a late answer never replaces what the user moved to.
 *
 * Build S1-3 B6 (`CONTRACT.md` §11.1, §2.6): the glasses' presenter of Feed's [core]. Read marks, flags, `seen`, pins,
 * browse recents, the archive's and the open article's places and the shared and glasses rows are the core's record
 * items, read through [source] (the PC's record, or the phone's working copy); the fields below stay this presenter's
 * optimistic state, and what input changes goes to the source as ops where it changes today (an open, Flag, Mark
 * unread, Mark all read, Pin, a browse's recents, a Settings commit, a list load's `seen`) — the field first, the op
 * after, so the first flush is today's; the two places at the shell's save point. An entry takes the record's place
 * when it is newer than this view's own input there; another device's move never moves an open view. The PC raises the
 * notices (`FeedCore`); this presenter draws the record's through its face. Its local `window.feed` keeps the view.
 */
class FeedGlasses(
    private val text: TextRasterizer,
    private val provider: FeedProvider,
    private val bg: CoroutineScope,
    val core: FeedCore,
    val source: WindowSource,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** This device's name in the record (`local` on the PC, `phone` on the phone): a write by it is its own echo. */
    private val device: String = Peer.LOCAL.device,
) : DamageWindow("feed", "Feed", IconKind.FEED) {

    private val tx = styledText(text)

    private enum class Level_ { SOURCES, ITEMS, ARTICLE, COMIC, ACTIONS, COMMENTS, BINGE, BINGE_ACTIONS, FLAGGED }

    /** One document line: text in a font at a level, or a strip of an image. */
    private class DocLine(
        val s: String = "", val f: FontSpec? = null, val lv: Int = Level.BODY, val indent: Int = 0,
        val strip: Gray8? = null, val stripW: Int = 0,
        /** The block this line came from (article relayouts keep the top by it). */
        val block: Int = -1, val sub: Int = 0,
        /** B2 (FeedAnchor): where this line's text starts in its block's text (an image's strip: its index). */
        val off: Int = 0,
    )

    /** An ITEMS row: an item, the loading pseudo-row, or the wrap-end menu row. */
    private sealed class IRow {
        class It(val item: Item) : IRow()
        object Loading : IRow()
        object Menu : IRow()
    }

    /** A SOURCES row: a source, or the wrap-end menu row. */
    private sealed class SRow {
        class Src(val s: SourceStatus) : SRow()
        object Menu : SRow()
    }

    /** §3.5 — a source's reading record: read ids (bounded), flags with enough
     *  of the item to list it after retention pruned it, the newest stamp
     *  the list was seen at. */
    private class Reading {
        val read = LinkedHashSet<String>()
        val flags = LinkedHashMap<String, Flag>()
        var seenMs = 0L
    }

    private class EpLoaded(val ep: Episode, val strips: List<Gray8>, val stripW: Int) {
        /** header + strips + a spacer */
        val lines: Int get() = 1 + strips.size + 1
    }

    // ------------------------------------------------------------ state
    private var level = Level_.SOURCES
    private val srcModel = ListModel()
    private val itemModel = ListModel()
    private val actModel = ListModel()
    private val bingeActModel = ListModel()
    private val flagModel = ListModel()
    private val docModel = DocModel()
    private val commentsModel = DocModel()
    private val bingeModel = DocModel()

    private var services: ShellServices? = null
    private var registered = false
    private var active = false
    private var stateLine = ""
    private var sources: List<SourceStatus> = emptyList()
    private val newCount = HashMap<String, Int>()
    private val reading = HashMap<String, Reading>()
    private val bingePos = HashMap<String, Pair<Int, Int>>()          // comic → (episode, strip)
    private val pinned = LinkedHashSet<String>()
    private val recentSubs = ArrayDeque<String>()
    private val recentSections = ArrayDeque<String>()

    // ITEMS
    private var openSource: String? = null
    private val items = ArrayList<Item>()
    private var itemsTotal = 0
    private var itemsVersion = -1L
    private var itemsLoading = false
    private var itemsState = ""
    private var itemsRetryAt = 0L
    private var itemsSeq = 0
    private var pendingItemCursorId: String? = null
    private var pendingItemMenu = false
    private var pendingOpenItemId: String? = null

    // ARTICLE / COMIC
    private var openItem: Item? = null
    private var fromFlagged = false
    private var article: Article? = null
    private var articleState = ""            // "" · "loading" · an error
    private var articleSeq = 0
    private val articleImages = HashMap<String, Gray8>()     // url → the whole prepared image
    private val articleImageFailed = HashMap<String, String>()
    private var comicPack: ComicPack? = null
    /** The comic canvas: the top line shown, the bar button under focus (-1 = panning),
     *  a flip in flight, and the numbered archive's range when the source has one. */
    private var comicTop = 0
    private var comicFocus = -1
    private var comicSeq = 0
    private var comicRange: IntRange? = null
    private var comicRangeFor: String? = null
    private var docLines: List<DocLine> = emptyList()
    private var docLineH = 26
    private var docWidthKey = -1
    private var pendingComicTop: Int? = null

    // COMMENTS
    private var comments: List<Comment>? = null
    private var commentsState = ""
    private var commentsSeq = 0
    private var commentLines: List<DocLine> = emptyList()
    private var commentLineH = 22
    private var pendingCommentsTop: Int? = null

    // BINGE
    private var bingeSource: String? = null
    private var episodes: List<Episode> = emptyList()
    private val loaded = ArrayList<EpLoaded>()
    private var bingeLoading = false
    private var bingeState = ""
    private var bingeSeq = 0
    private var bingeIndexLoading = false
    private var pendingBingeAnchor: Pair<Int, Int>? = null
    private var bingeLineMap: List<Pair<Int, Int>> = emptyList()      // line → (loaded index, line within)

    // settings
    private var images = true
    private var artXkcd = LineArt.AUTO
    private var artSmbc = LineArt.AUTO
    private var artBinge = LineArt.AUTO
    private var comicLevels = 16
    private var fetchMin = 15
    private var keepDays = 30
    private var notifyReddit = false
    private var notifySlashdot = false
    private var notifyComics = false
    private var heightPref: Int? = null

    private var notice: String? = null
    private var noticeUntil = 0L

    private val fRow = FontSpec(Face.LIST, 18)
    private val fSmall = FontSpec(Face.LIST, 13, bold = true)
    private val fHead = FontSpec(Face.LIST, 18, bold = true)
    private val fBody = FontSpec(Face.LIST, 17)
    // the Reader's sizes (17): the per-app Font size row scales them, "default" = the global
    private val fRead = FontSpec(Face.READER, 17)
    private val fReadB = FontSpec(Face.READER, 17, bold = true)
    private val fReadI = FontSpec(Face.READER, 17, italic = true)
    private val fMono = FontSpec(Face.MONO, 15)

    override val preferredHeight: Int? get() = heightPref

    /** The source id under the root cursor — for harnesses that walk by NAME, never by counting. */
    fun rootRowId(): String? = (srcRows().getOrNull(srcModel.cursor) as? SRow.Src)?.s?.id
    /** The level's name — for harnesses. */
    val levelName: String get() = level.name
    /** The open list has rows — a harness clicks nothing before this is true
     *  (a click on the loading row does nothing, honestly). */
    fun itemsLoaded(): Boolean = items.isNotEmpty()

    // ------------------------------------------------------------ helpers
    private fun setNotice(s: String) {
        notice = s
        noticeUntil = clock() + 4_000
        services?.requestRender(this)
    }

    private fun onShell(action: () -> Unit) { services?.runOnShell(action) ?: action() }
    /** §72: a push whose newest instance supersedes the older ones — held, the latest per key, while no session is live. */
    private fun onShell(key: String, action: () -> Unit) { services?.runOnShell("feed.$key", action) ?: action() }

    private fun dn(s: String, f: FontSpec = fRow): String = Draw.dynamic(tx, s, f)

    private fun lineHOf(f: FontSpec): Int = (((Draw.ink(tx, f) + 5) / 2) * 2).coerceAtLeast(18)

    private fun contentW(): Int = services?.docContentWidth() ?: 596

    private fun reading(id: String): Reading = reading.getOrPut(id) { Reading() }

    private fun sourceOf(id: String): SourceStatus? = sources.firstOrNull { it.id == id }

    private fun kindOf(sourceId: String): SourceKind? = sourceOf(sourceId)?.kind

    private fun artFor(sourceId: String): LineArt = when (kindOf(sourceId)) {
        SourceKind.XKCD -> artXkcd
        SourceKind.SMBC -> artSmbc
        SourceKind.EIGHTBIT -> artBinge
        else -> LineArt.AUTO
    }

    private fun notifyFor(kind: SourceKind?): Boolean = when (kind) {
        SourceKind.REDDIT -> notifyReddit
        SourceKind.SLASHDOT -> notifySlashdot
        SourceKind.XKCD, SourceKind.SMBC, SourceKind.RSS -> notifyComics
        else -> false
    }

    private fun isRead(it: Item): Boolean = reading[it.source]?.read?.contains(it.id) == true
    private fun isFlagged(it: Item): Boolean = reading[it.source]?.flags?.containsKey(it.id) == true

    /** [item] read or not ([action]: `read` on an open, `markRead`/`unread` from its actions); the source's whole list
     *  goes to the record when it moved. */
    private fun markRead(item: Item, read: Boolean, action: String) {
        val r = reading(item.source)
        val moved = if (read) r.read.add(item.id).also { while (r.read.size > READ_CAP) r.read.remove(r.read.first()) }
            else r.read.remove(item.id)
        recount(item.source, if (openSource == item.source) items else null)
        if (moved) send(FeedCore.readDraft(action, item.source, r.read))
    }

    private fun toggleFlag(it: Item) {
        val r = reading(it.source)
        if (r.flags.remove(it.id) == null) {
            val f = Flag(it.title, it.link, it.source, it.publishedMs, it.kind, clock())
            r.flags[it.id] = f
            send(FeedCore.flagDraft(it.id, f))
        } else send(FeedCore.unflagDraft(it.id))
    }

    /** "new" = newer than the stamp the list was last seen at and not read. */
    private fun recount(sourceId: String, page: List<Item>?) {
        if (page == null) return
        val r = reading(sourceId)
        newCount[sourceId] = page.count { it.publishedMs > r.seenMs && it.id !in r.read }
    }

    // ================================================================ provider glue
    private val listener = object : FeedProvider.Listener {
        override fun changed(sourceId: String, version: Long) {
            // off the channel's thread: the peek is a provider call
            bg.launch(Dispatchers.IO) { peek(sourceId) }
        }

        override fun state(line: String) {
            onShell("state") {
                if (stateLine != line) {
                    stateLine = line
                    services?.requestRender(this@FeedGlasses)
                }
            }
        }
    }

    /** A source changed: refresh the cached statuses, count what is new and
     *  reload the open list (B2: the PC raises the notices, `FeedCore`). */
    private fun peek(sourceId: String) {
        val statuses = try { provider.sources() } catch (e: Exception) { Log.w("feed", "sources: ${e.message}"); return }
        val page = try { provider.items(sourceId, 0, PEEK) } catch (e: Exception) { null }
        onShell("peek.$sourceId") {
            sources = statuses
            if (page != null) {
                recount(sourceId, page.items)
                if (openSource == sourceId && level != Level_.SOURCES && page.version != itemsVersion) reloadItems()
            }
            services?.requestRender(this@FeedGlasses)
        }
    }

    /** B2: the record's Feed notice, drawn as this window's own raise drew it — the body through this window's face,
     *  the window marked dirty. On the shell loop. */
    private fun formatRecordNotice(rec: wm.damage.core.app.NoticeRecord): String {
        dirty = true
        services?.requestRender(this)
        return dn(rec.body)
    }

    override fun onRegistered(ctx: ShellServices) {
        services = ctx
        ctx.noticeFormatter(id, ::formatRecordNotice)
        if (!registered) { registered = true; provider.addListener(listener) }
        stateLine = provider.stateLine()
        sources = try { provider.sources() } catch (e: Exception) { emptyList() }
    }

    /** The stack is going away while the provider lives on: stop listening (B6: and the source, which outlives this
     *  presenter on the PC). */
    fun detach() {
        active = false
        registered = false
        provider.removeListener(listener)
        source.removeListener(onSource)
        services?.noticeFormatter(id, null)
    }

    // ================================================================ the record (B6)
    /** Per record item (its name): the value this view last sent or took — its own echo is not taken again. */
    private val known = HashMap<String, JsonElement>()

    /** An op of this view's input, after its field (C§2.3: it returns at once). */
    private fun send(d: Draft) {
        known[d.targets.first()] = d.result
        source.act(if (d.at == null) d.copy(at = source.now()) else d)
    }

    /** The record's state into this view: another device's write is taken, and this device's own where this view holds
     *  none (a restart); the two places only at an entry ([enterPlace], [takeBinge]). True when something drawn moved. */
    private fun fromSource(st: WindowState): Boolean {
        var drawn = false
        val pre = Record.prefix(FeedCore.WINDOW)
        for ((k, it) in st.items) {
            val name = k.removePrefix(pre)
            val v: JsonElement = if (it.gone) JsonNull else it.value
            if (name == FeedCore.PLACE) continue
            if (name.startsWith(FeedCore.BINGE)) { drawn = bingeFrom(name.removePrefix(FeedCore.BINGE), it) || drawn; continue }
            if (known[name] == v || (it.by == device && name in known)) continue
            known[name] = v
            drawn = take(name, v) || drawn
        }
        return drawn
    }

    /** One record item into this view's fields; true when it moves what this window draws. */
    private fun take(name: String, v: JsonElement): Boolean {
        when {
            name.startsWith(FeedCore.READ) -> {
                val src = name.removePrefix(FeedCore.READ)
                val r = reading(src)
                r.read.clear()
                r.read.addAll(FeedCore.strings(v).orEmpty().takeLast(READ_CAP))
                recount(src, if (openSource == src) items else null)
                return true
            }
            name.startsWith(FeedCore.FLAG) -> {
                val id = name.removePrefix(FeedCore.FLAG)
                for (r in reading.values) r.flags.remove(id)
                FeedCore.flagOf(v)?.let { f -> reading(f.source).flags[id] = f }
                return true
            }
            name.startsWith(FeedCore.SEEN) -> {
                val src = name.removePrefix(FeedCore.SEEN)
                val r = reading(src)
                r.seenMs = maxOf(r.seenMs, FeedCore.long(v) ?: 0L)
                recount(src, if (openSource == src) items else null)
                return true
            }
            name.startsWith(FeedCore.PIN) -> {
                val src = name.removePrefix(FeedCore.PIN)
                if (FeedCore.bool(v) == true) pinned.add(src) else pinned.remove(src)
                return true
            }
            name == FeedCore.RECENT + "reddit" -> { recentSubs.clear(); recentSubs.addAll(FeedCore.strings(v).orEmpty().take(RECENTS)) }
            name == FeedCore.RECENT + "slashdot" -> { recentSections.clear(); recentSections.addAll(FeedCore.strings(v).orEmpty().take(RECENTS)) }
            name == FeedCore.FETCH -> FeedCore.int(v)?.takeIf { it in FETCHES.values }?.let { fetchMin = it }
            name == FeedCore.KEEP -> FeedCore.int(v)?.takeIf { it in KEEPS.values }?.let { keepDays = it }
            name == FeedCore.NOTIFY_REDDIT -> notifyReddit = FeedCore.bool(v) ?: false
            name == FeedCore.NOTIFY_SLASHDOT -> notifySlashdot = FeedCore.bool(v) ?: false
            name == FeedCore.NOTIFY_COMICS -> notifyComics = FeedCore.bool(v) ?: false
            name == FeedCore.SIZE -> FeedCore.sizeOf(v)?.let { h ->
                val was = heightPref
                heightPref = if (h == 0) null else h
                return was != heightPref
            }
            name == FeedCore.ART_XKCD || name == FeedCore.ART_SMBC || name == FeedCore.ART_BINGE -> {
                val a = FeedCore.artOf(v) ?: LineArt.AUTO
                val was = when (name) { FeedCore.ART_XKCD -> artXkcd; FeedCore.ART_SMBC -> artSmbc; else -> artBinge }
                if (a == was) return false
                when (name) { FeedCore.ART_XKCD -> artXkcd = a; FeedCore.ART_SMBC -> artSmbc = a; else -> artBinge = a }
                invalidateDocs()
                return true
            }
            name == FeedCore.LEVELS -> {
                val lv = FeedCore.levelsOf(v) ?: 16
                if (lv == comicLevels) return false
                comicLevels = lv
                invalidateDocs()
                return true
            }
        }
        return false
    }

    /** The source's changes and answers (the core executor): handed to the shell loop, keyed for changes (the state is
     *  whole, the newest suffices). */
    private val onSource = object : SourceListener {
        override fun changed(state: WindowState, keys: Set<String>) {
            services?.runOnShell("feed.source") {
                if (fromSource(source.state())) services?.requestRender(this@FeedGlasses)
            }
        }

        override fun answered(op: Op, answer: Answer) {
            if (answer !is Answer.Refused) return
            val line = "${op.action} of ${op.targets.joinToString()} not saved: ${answer.reason}"
            val s = services
            if (s == null) { Log.e("feed", "$line (before the shell started)"); return }
            s.runOnShell {
                // not taken: it changed nothing; the view's field stays and the next change sends it again
                known.remove(op.targets.firstOrNull())
                Log.w("feed", line)
                setNotice(dn(line, fSmall))
            }
        }
    }


    override fun onActivate(ctx: ShellServices, from: ActivationSource) {
        services = ctx
        active = true
        if (from == ActivationSource.MAIN) goRoot()
        refreshSources()
        provider.setFocused(openSource)
        // a restored level below the top loads on the way back (§25 #7)
        when (level) {
            Level_.ITEMS, Level_.ACTIONS, Level_.ARTICLE, Level_.COMIC, Level_.COMMENTS ->
                if (items.isEmpty() && openSource != null && !itemsLoading) loadItems(0)
                // P7 item 5 (OS-3), step 3: a record naming an item this instance does not hold, while its list is
                // loaded — the re-open's one consumer is a list load, which the line above asks only of an empty list
                else if (pendingOpenItemId != null && items.isNotEmpty()) reloadItems()
            Level_.BINGE, Level_.BINGE_ACTIONS -> if (loaded.isEmpty() && !bingeLoading) ensureBinge()
            else -> {}
        }
        if ((level == Level_.ARTICLE || level == Level_.ACTIONS || level == Level_.COMMENTS) && article == null && articleState.isEmpty()) openItem?.let { loadArticle(it) }
        if (level == Level_.COMIC && comicPack == null && articleState.isEmpty()) openItem?.let { loadComic(it) }
        if (level == Level_.COMMENTS && comments == null && commentsState.isEmpty()) openItem?.let { loadComments(it) }
    }

    override fun onDeactivate() {
        active = false
        provider.setFocused(null)
    }

    private fun refreshSources() {
        bg.launch(Dispatchers.IO) {
            val s = try { provider.sources() } catch (e: Exception) { return@launch }
            onShell {
                sources = s
                services?.requestRender(this@FeedGlasses)
            }
            for (st in s) if (st.id !in newCount && !st.binge && st.count > 0) peek(st.id)
        }
    }

    /** MAIN entry: the source list, the window's root (the reading position
     *  of everything below stays in its records). */
    private fun goRoot() {
        clearPendingOpen()
        forgetUnpinned()
        level = Level_.SOURCES
        services?.setOperation("idle")
    }

    // ================================================================ the contract
    override fun view(): WindowView = when (level) {
        Level_.SOURCES -> WindowView.ListView(srcModel, { srcRows().size }, ::paintSrcRow, ::paintSrcLens, ::commitSource)
        Level_.ITEMS -> {
            demandItemsIfNear()
            WindowView.ListView(itemModel, { itemRows().size }, ::paintItemRow, ::paintItemLens, ::commitItem)
        }
        Level_.ARTICLE -> {
            ensureDocLines()
            WindowView.DocView(docModel, { docLines.size }, docLineH, ::paintDocLine, { openActions() }, stepLines = { 5 })
        }
        Level_.COMIC -> {
            ensureDocLines()
            WindowView.CanvasView(::paintComic, ::comicScroll, ::comicTap)
        }
        Level_.ACTIONS -> WindowView.ListView(actModel, { actions().size }, ::paintActRow, ::paintActLens, ::commitAction)
        Level_.COMMENTS -> {
            ensureCommentLines()
            WindowView.DocView(commentsModel, { commentLines.size }, commentLineH, ::paintCommentLine, { level = Level_.ACTIONS }, stepLines = { 5 })
        }
        Level_.BINGE -> {
            demandBingeIfNear()
            WindowView.DocView(bingeModel, { bingeLineCount() }, STRIP_H, ::paintBingeLine, { level = Level_.BINGE_ACTIONS; bingeActModel.cursor = 0 }, stepLines = { 5 })
        }
        Level_.BINGE_ACTIONS -> WindowView.ListView(bingeActModel, { bingeActions().size }, ::paintBingeActRow, ::paintBingeActLens, ::commitBingeAction)
        Level_.FLAGGED -> WindowView.ListView(flagModel, { flagRows().size }, ::paintFlagRow, ::paintFlagLens, ::commitFlag)
    }

    override fun title(): String {
        val n = notice
        if (n != null && clock() < noticeUntil) return n
        val base = when (level) {
            Level_.SOURCES -> "feed"
            Level_.ITEMS -> openSource?.let { sourceOf(it)?.name?.lowercase() ?: it } ?: "feed"
            Level_.ARTICLE -> "article"
            Level_.COMIC -> openItem?.let { comicTitle(it) } ?: "comic"
            Level_.ACTIONS -> openItem?.let { sourceOf(it.source)?.name?.lowercase() ?: "actions" } ?: "actions"
            Level_.COMMENTS -> "comments"
            Level_.BINGE -> "8bt " + (bingePosNow()?.first?.toString() ?: "")
            Level_.BINGE_ACTIONS -> "8bt"
            Level_.FLAGGED -> "flagged"
        }
        // the staleness surface reaches every level (§30): the source list
        // says it in its lens; every other level carries it here
        if (level != Level_.SOURCES && stateLine.isNotEmpty()) return "$base · ! ${dn(stateLine, fSmall)}"
        return base
    }

    private fun comicTitle(it: Item): String = when (kindOf(it.source)) {
        SourceKind.XKCD -> "xkcd ${it.num}"
        SourceKind.SMBC -> "smbc"
        else -> sourceOf(it.source)?.name?.lowercase() ?: "comic"
    }

    override fun summary(): Summary {
        val total = newCount.values.sum()
        val parts = sources.filter { (newCount[it.id] ?: 0) > 0 }.take(3).joinToString(" · ") { "${it.name.lowercase()} ${newCount[it.id]}" }
        if (stateLine.isNotEmpty()) return Summary(stateLine, detail = parts.ifEmpty { "${sources.size} sources" })
        if (sources.isEmpty()) return Summary("connecting", detail = "no sources yet")
        val line = if (total > 0) "$total new" else "nothing new"
        return Summary(line, detail = parts.ifEmpty { "${sources.size} sources" }, more = total > 0)
    }

    override fun levelDepth(): Int = when (level) {
        Level_.SOURCES -> 1
        Level_.ITEMS, Level_.FLAGGED, Level_.BINGE -> 2
        Level_.ARTICLE, Level_.COMIC, Level_.BINGE_ACTIONS -> 3
        Level_.ACTIONS -> 4
        Level_.COMMENTS -> 5
    }

    override fun back(): Boolean {
        clearPendingOpen()
        when (level) {
            Level_.SOURCES -> return false
            Level_.ITEMS -> { forgetUnpinned(); level = Level_.SOURCES; services?.setOperation("idle") }
            Level_.ARTICLE, Level_.COMIC -> {
                articleSeq++
                level = if (fromFlagged) Level_.FLAGGED else Level_.ITEMS
                services?.setOperation("idle")
            }
            Level_.ACTIONS -> level = if (comicPack != null || openItem?.kind == ItemKind.COMIC) Level_.COMIC else Level_.ARTICLE
            Level_.COMMENTS -> { commentsSeq++; level = Level_.ACTIONS }
            Level_.BINGE -> { newBingeSeq(); level = Level_.SOURCES; services?.setOperation("idle") }
            Level_.BINGE_ACTIONS -> level = Level_.BINGE
            Level_.FLAGGED -> level = Level_.SOURCES
        }
        return true
    }

    override fun onLayoutChanged() { invalidateDocs() }
    override fun onFontScaleChanged(scale: Double) { invalidateDocs() }

    private fun invalidateDocs() {
        docWidthKey = -1
        commentLines = emptyList()
        articleImages.clear()          // a width change wants the strips at the new width
        articleImageFailed.clear()
        val anchor = bingePosNow()
        newBingeSeq()
        loaded.clear()
        bingeLineMap = emptyList()
        if (anchor != null) pendingBingeAnchor = anchor
        if (level == Level_.BINGE || level == Level_.BINGE_ACTIONS) ensureBinge()
        if (level == Level_.COMIC) openItem?.let { comicPack = null; loadComic(it) }
    }

    override fun open(target: String): Boolean {
        when {
            target.startsWith("src:") -> {
                val id = target.removePrefix("src:")
                if (sourceOf(id) == null) return false
                fromFlagged = false
                level = Level_.SOURCES
                srcModel.cursor = srcRows().indexOfFirst { (it as? SRow.Src)?.s?.id == id }.coerceAtLeast(0)
                openItems(id)
                return true
            }
            target.startsWith("item:") -> {
                // a browsed source's id holds a ':' (`r:linux`); an item id never does
                val rest = target.removePrefix("item:")
                val src = rest.substringBeforeLast(':', "")
                val itemId = rest.substringAfterLast(':', "")
                if (src.isEmpty() || itemId.isEmpty()) return false
                if (sourceOf(src) == null) return false
                fromFlagged = false
                level = Level_.SOURCES
                srcModel.cursor = srcRows().indexOfFirst { (it as? SRow.Src)?.s?.id == src }.coerceAtLeast(0)
                openItems(src)
                val known = items.firstOrNull { it.id == itemId }
                if (known != null) openOne(known) else pendingOpenItemId = itemId
                return true
            }
            target.startsWith("binge:") -> {
                val id = target.removePrefix("binge:")
                val s = sourceOf(id) ?: return false
                if (!s.binge) return false
                level = Level_.SOURCES
                srcModel.cursor = srcRows().indexOfFirst { (it as? SRow.Src)?.s?.id == id }.coerceAtLeast(0)
                openBinge(id)
                return true
            }
        }
        return false
    }

    // ================================================================ SOURCES
    private fun srcRows(): List<SRow> {
        val out = ArrayList<SRow>()
        for (s in sources) if (!s.transient || s.id in pinned || s.id == openSource) out.add(SRow.Src(s))
        out.add(SRow.Menu)
        return out
    }

    private fun srcDetail(s: SourceStatus, now: Long): String {
        if (s.state.isNotEmpty()) return s.state
        if (s.binge) {
            val pos = bingePos[s.id]
            return if (s.bingeTotal == 0) "archive not indexed yet"
                else if (pos == null) "${s.bingeTotal} pages · from the start" else "page ${pos.first} of ${s.bingeTotal}"
        }
        val n = newCount[s.id] ?: 0
        val age = if (s.lastOkMs > 0) "fetched ${FeedFmt.age(s.lastOkMs, now)} ago" else if (s.lastFetchMs > 0) "fetching" else "not fetched yet"
        return "${s.count} items · ${if (n > 0) "$n new" else "nothing new"} · $age"
    }

    private fun paintSrcRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        when (val row = srcRows().getOrNull(i)) {
            is SRow.Src -> {
                val s = row.s
                IconPaint.draw(g, services?.icons(), IconNames.forFeedSource(s.kind), r.x + 8, r.y + 6, 20, IconKind.FEED, Level.DIM)
                val n = newCount[s.id] ?: 0
                val f = if (n > 0) fHead else fRow
                Draw.fit(g, tx, r.x + 40, r.y + 5, dn(s.name, f), if (n > 0) Level.BODY else Level.DIM, f, r.w - 40 - 140)
                val tail = when {
                    s.state.isNotEmpty() -> "!"
                    s.binge -> bingePos[s.id]?.let { "p.${it.first}" } ?: ""
                    n > 0 -> "$n new"
                    else -> ""
                }
                if (tail.isNotEmpty()) Draw.right(g, tx, r.right - 24, r.y + 8, tail, if (s.state.isNotEmpty()) Level.HEAD else Level.DIM, fSmall)
            }
            is SRow.Menu -> {
                IconPaint.draw(g, services?.icons(), IconNames.forKind(IconKind.FEED), r.x + 8, r.y + 6, 20, IconKind.FEED, Level.DIM)
                Draw.fit(g, tx, r.x + 40, r.y + 5, if (sources.isEmpty()) dn(stateLine).ifEmpty { "connecting" } else "Feed", Level.DIM, fRow, r.w - 64)
                Icons.tri(g, r.right - 36, r.y + 10, 11, Level.DIM)
            }
            null -> {}
        }
    }

    private fun paintSrcLens(g: Gray8, r: Rect, i: Int) {
        val y2 = Draw.lineBelow(tx, fHead, r.y + 6, r.y + 32)
        when (val row = srcRows().getOrNull(i)) {
            is SRow.Src -> {
                val s = row.s
                IconPaint.draw(g, services?.icons(), IconNames.forFeedSource(s.kind), r.x + 8, r.y + 4, 56, IconKind.FEED, Level.HEAD)
                Draw.fit(g, tx, r.x + 72, r.y + 6, dn(s.name, fHead), Level.HEAD, fHead, r.w - 88)
                Draw.fit(g, tx, r.x + 72, y2, dn(srcDetail(s, clock()), fBody), if (s.state.isNotEmpty()) Level.HEAD else Level.BODY, fBody, r.w - 88)
            }
            is SRow.Menu -> {
                IconPaint.draw(g, services?.icons(), IconNames.forKind(IconKind.FEED), r.x + 8, r.y + 4, 56, IconKind.FEED, Level.HEAD)
                Draw.fit(g, tx, r.x + 72, r.y + 6, "Feed", Level.HEAD, fHead, r.w - 88)
                val line = dn(stateLine, fBody).ifEmpty {
                    val flagged = reading.values.sumOf { it.flags.size }
                    "${sources.size} sources · $flagged flagged · tap for the menu"
                }
                Draw.fit(g, tx, r.x + 72, y2, line, Level.BODY, fBody, r.w - 88)
            }
            null -> {}
        }
    }

    private fun commitSource(i: Int) {
        when (val row = srcRows().getOrNull(i)) {
            is SRow.Src -> if (row.s.binge) openBinge(row.s.id) else openItems(row.s.id)
            else -> openRootMenu()
        }
    }

    private fun openRootMenu() {
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String = "", enabled: Boolean = true, act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail, enabled)); acts.add(act)
        }
        add("Refresh all") { bg.launch(Dispatchers.IO) { try { provider.refresh(null) } catch (e: Exception) { Log.w("feed", "refresh: ${e.message}") } }; setNotice("refreshing") }
        val flagged = reading.values.sumOf { it.flags.size }
        add("Flagged", "$flagged", enabled = flagged > 0) { level = Level_.FLAGGED; flagModel.cursor = 0 }
        add("Settings", "Feed") { if (services?.openWindow("settings", "cat:Feed") != true) setNotice("Settings is not available") }
        services?.openMenu(MenuSurface.Spec("feed", items, onCommit = { idx -> acts.getOrNull(idx)?.invoke() }))
    }

    // ================================================================ ITEMS
    private fun openItems(sourceId: String) {
        clearPendingOpen()        // `open("item:…")` arms its own re-open AFTER this call
        if (openSource != sourceId) {
            forgetUnpinned(except = sourceId)
            openSource = sourceId
            items.clear()
            itemsTotal = 0
            itemsVersion = -1
            itemsState = ""
            itemsRetryAt = 0L
            itemsSeq++
            itemsLoading = false
            itemModel.cursor = 0
        }
        level = Level_.ITEMS
        fromFlagged = false
        provider.setFocused(sourceId)
        if (items.isEmpty()) loadItems(0) else reloadItems()
    }

    private fun itemRows(): List<IRow> {
        val out = ArrayList<IRow>(items.size + 2)
        for (it in items) out.add(IRow.It(it))
        if (items.size < itemsTotal || (items.isEmpty() && itemsLoading)) out.add(IRow.Loading)
        out.add(IRow.Menu)
        return out
    }

    private fun demandItemsIfNear() {
        if (!active || itemsLoading || items.size >= itemsTotal) return
        if (clock() < itemsRetryAt) return
        if (itemModel.cursor >= items.size - 8) loadItems(items.size)
    }

    /** The open list from the top, at least the loaded length: the cursor
     *  follows its row's IDENTITY through whatever the fetch inserted. */
    private fun reloadItems() {
        if (itemsLoading) return
        loadItems(0, limit = maxOf(PAGE, items.size))
    }

    private fun loadItems(offset: Int, limit: Int = PAGE) {
        val src = openSource ?: return
        if (itemsLoading) return
        itemsLoading = true
        val seq = itemsSeq
        services?.setOperation(if (items.isEmpty()) "loading" else "loading more")
        bg.launch(Dispatchers.IO) {
            val (page, err) = try { provider.items(src, offset, limit) to null } catch (e: Exception) {
                Log.w("feed", "items $src@$offset: ${e.message}")
                null to (e.message ?: "load failed")
            }
            onShell {
                if (seq != itemsSeq) return@onShell
                itemsLoading = false
                services?.setOperation("idle")
                if (page == null) {
                    itemsState = err ?: "load failed"
                    itemsRetryAt = clock() + RETRY_MS
                    setNotice(itemsState)
                    scheduleRetryPaint(seq)
                } else {
                    itemsState = ""
                    val cursorId = (itemRows().getOrNull(itemModel.cursor) as? IRow.It)?.item?.id
                    val onMenu = items.isNotEmpty() && itemModel.cursor >= itemRows().size - 1
                    if (offset == 0) {
                        val fresh = ArrayList(page.items)
                        for (old in items) if (fresh.none { it.id == old.id }) fresh.add(old)   // beyond the reloaded prefix
                        items.clear(); items.addAll(fresh)
                    } else {
                        for (n in page.items) if (items.none { it.id == n.id }) items.add(n)
                    }
                    itemsTotal = maxOf(page.total, items.size)
                    itemsVersion = page.version
                    val rows = itemRows()
                    val want = pendingItemCursorId; pendingItemCursorId = null
                    val wantMenu = pendingItemMenu; pendingItemMenu = false
                    itemModel.cursor = when {
                        wantMenu || onMenu -> rows.size - 1
                        want != null && rows.any { (it as? IRow.It)?.item?.id == want } -> rows.indexOfFirst { (it as? IRow.It)?.item?.id == want }
                        cursorId != null -> rows.indexOfFirst { (it as? IRow.It)?.item?.id == cursorId }.takeIf { it >= 0 } ?: itemModel.cursor.coerceIn(0, rows.size - 1)
                        else -> itemModel.cursor.coerceIn(0, rows.size - 1)
                    }
                    // the list was seen: everything shown counts as seen (verdict 13's floor)
                    val r = reading(src)
                    val newest = items.maxOfOrNull { it.publishedMs } ?: 0L
                    if (newest > r.seenMs) { r.seenMs = newest; send(FeedCore.seenDraft(src, newest)) }
                    recount(src, items)
                    val pend = pendingOpenItemId
                    if (pend != null) {
                        val it = items.firstOrNull { it.id == pend }
                        if (it != null) {
                            pendingOpenItemId = null
                            val restored = restoredLevel      // read before `openOne`, which clears it
                            openOne(it)
                            // the restored level below the article (§9.1: mode included)
                            when (restored) {
                                Level_.ACTIONS -> openActions()
                                Level_.COMMENTS -> openComments(it)
                                else -> {}
                            }
                            restoredLevel = null
                        }
                        else if (items.size < itemsTotal && active) loadItems(items.size)
                        else { pendingOpenItemId = null; setNotice("that item is gone") }
                    }
                }
                services?.requestRender(this@FeedGlasses)
            }
        }
    }

    private fun scheduleRetryPaint(seq: Int) {
        bg.launch {
            kotlinx.coroutines.delay(RETRY_MS)
            onShell {
                if (seq == itemsSeq && level == Level_.ITEMS) {
                    itemsRetryAt = 0L
                    if (items.isEmpty()) loadItems(0) else demandItemsIfNear()
                    services?.requestRender(this@FeedGlasses)
                }
            }
        }
    }

    private fun itemTail(it: Item, now: Long): String {
        val age = FeedFmt.age(it.publishedMs, now)
        return when (kindOf(it.source)) {
            SourceKind.XKCD -> "#${it.num} · $age"
            SourceKind.SMBC, SourceKind.RSS -> age
            SourceKind.REDDIT -> listOf(if (it.comments >= 0) "${it.comments} cmts" else "", age, it.extra).filter { it.isNotEmpty() }.joinToString(" · ")
            SourceKind.SLASHDOT -> listOf(if (it.comments >= 0) "${it.comments} cmts" else "", age, it.extra).filter { it.isNotEmpty() }.joinToString(" · ")
            else -> age
        }
    }

    /** A small drawn flag at the row's left — judged at 1× beside real titles. */
    private fun flagMark(g: Gray8, x: Int, y: Int, lv: Int) {
        g.fillRect(x, y, 2, 14, lv)
        g.fillPolygon(intArrayOf(x + 2, x + 12, x + 2), intArrayOf(y, y + 4, y + 8), lv)
    }

    private fun paintItemRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        when (val row = itemRows().getOrNull(i)) {
            is IRow.It -> {
                val it = row.item
                val read = isRead(it)
                if (isFlagged(it)) flagMark(g, r.x + 12, r.y + 9, Level.MID)
                val tail = itemTail(it, clock())
                val tw = if (tail.isEmpty()) 0 else tx.measure(tail, fSmall) + 16
                Draw.fit(g, tx, r.x + 32, r.y + 5, dn(it.title), if (read) Level.DIM else Level.BODY, fRow, r.w - 32 - 24 - tw)
                if (tail.isNotEmpty()) Draw.right(g, tx, r.right - 24, r.y + 8, tail, Level.DIM, fSmall)
            }
            is IRow.Loading -> {
                val waiting = !itemsLoading && itemsState.isNotEmpty() && clock() < itemsRetryAt
                val s = when {
                    itemsLoading -> if (items.isEmpty()) "loading" else "loading more"
                    waiting -> "failed - retrying: ${dn(itemsState)}"
                    itemsState.isNotEmpty() -> "failed - retrying now: ${dn(itemsState)}"
                    else -> "more"
                }
                Draw.fit(g, tx, r.x + 32, r.y + 5, s, Level.REST, fRow, r.w - 56)
            }
            is IRow.Menu -> {
                val src = openSource?.let { sourceOf(it) }
                val label = if (items.isEmpty() && !itemsLoading) (src?.state?.let { dn(it) }?.ifEmpty { null } ?: "nothing yet") else (src?.name ?: "source")
                Draw.fit(g, tx, r.x + 32, r.y + 5, label, Level.DIM, fRow, r.w - 56)
                Icons.tri(g, r.right - 36, r.y + 10, 11, Level.DIM)
            }
            null -> {}
        }
    }

    private fun itemDetail(it: Item, now: Long): String {
        val src = sourceOf(it.source)?.name ?: it.source
        val domain = try { java.net.URI(it.target.ifEmpty { it.link }).host?.removePrefix("www.") ?: "" } catch (e: Exception) { "" }
        val bits = ArrayList<String>()
        bits.add(src)
        if (it.author.isNotEmpty()) bits.add(it.author)
        if (it.kind == ItemKind.LINK && domain.isNotEmpty()) bits.add(domain)
        if (it.kind == ItemKind.IMAGE) bits.add("image")
        if (it.kind == ItemKind.VIDEO) bits.add("video")
        bits.add(FeedFmt.age(it.publishedMs, now).ifEmpty { "" })
        if (it.comments >= 0) bits.add("${it.comments} comments")
        if (isFlagged(it)) bits.add("flagged")
        return bits.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun paintItemLens(g: Gray8, r: Rect, i: Int) {
        val y2 = Draw.lineBelow(tx, fHead, r.y + 6, r.y + 32)
        when (val row = itemRows().getOrNull(i)) {
            is IRow.It -> {
                val it = row.item
                // the title wrapped to the two lens lines when it does not fit one
                val w = r.w - 32
                val t = dn(it.title, fHead)
                if (tx.measure(t, fHead) <= w) {
                    Draw.fit(g, tx, r.x + 16, r.y + 6, t, Level.HEAD, fHead, w)
                    Draw.fit(g, tx, r.x + 16, y2, dn(itemDetail(it, clock()), fBody), Level.BODY, fBody, w)
                } else {
                    val lines = Wrap.wrap(t, fHead, tx, w)
                    Draw.fit(g, tx, r.x + 16, r.y + 6, lines[0], Level.HEAD, fHead, w)
                    val rest = lines.drop(1).joinToString(" ")
                    Draw.fit(g, tx, r.x + 16, y2, rest, Level.HEAD, fHead, w)
                }
            }
            is IRow.Loading -> {
                Draw.fit(g, tx, r.x + 16, r.y + 6, if (itemsLoading) "loading" else "more", Level.HEAD, fHead, r.w - 32)
                Draw.fit(g, tx, r.x + 16, y2, dn(itemsState, fBody).ifEmpty { "${items.size} of $itemsTotal" }, Level.BODY, fBody, r.w - 32)
            }
            is IRow.Menu -> {
                val src = openSource?.let { sourceOf(it) }
                IconPaint.draw(g, services?.icons(), src?.let { IconNames.forFeedSource(it.kind) } ?: IconNames.forKind(IconKind.FEED), r.x + 8, r.y + 4, 56, IconKind.FEED, Level.HEAD)
                Draw.fit(g, tx, r.x + 72, r.y + 6, dn(src?.name ?: "source", fHead), Level.HEAD, fHead, r.w - 88)
                val line = src?.let { dn(srcDetail(it, clock()), fBody) } ?: ""
                Draw.fit(g, tx, r.x + 72, y2, if (line.isEmpty()) "tap for the menu" else "$line · tap for the menu", Level.BODY, fBody, r.w - 88)
            }
            null -> {}
        }
    }

    private fun commitItem(i: Int) {
        when (val row = itemRows().getOrNull(i)) {
            is IRow.It -> openOne(row.item)
            is IRow.Loading -> { if (!itemsLoading) { itemsRetryAt = 0L; loadItems(items.size) } }
            else -> openSourceMenu()
        }
    }

    /** One tap opens (the Reader grammar): the article or the comic, read on open (verdict 6). */
    private fun openOne(it: Item) {
        clearPendingOpen()
        openItem = it
        markRead(it, true, FeedCore.A_READ)
        docModel.topLine = 0             // a place the entry takes waits in pendingPlace for the article's lines
        enterPlace(it)
        comicTop = pendingComicTop ?: 0
        comicFocus = -1
        pendingComicTop = null
        article = null
        comicPack = null
        articleState = ""
        articleImages.clear()
        articleImageFailed.clear()
        docWidthKey = -1
        comments = null
        commentsState = ""
        if (it.kind == ItemKind.COMIC) { level = Level_.COMIC; loadComic(it) }
        else { level = Level_.ARTICLE; loadArticle(it) }
    }

    private fun openSourceMenu() {
        val src = openSource?.let { sourceOf(it) } ?: return
        val items = ArrayList<MenuSurface.Item>()
        val acts = ArrayList<() -> Unit>()
        fun add(label: String, detail: String = "", enabled: Boolean = true, act: () -> Unit) {
            items.add(MenuSurface.Item(label, detail, enabled)); acts.add(act)
        }
        add("Refresh") { bg.launch(Dispatchers.IO) { try { provider.refresh(src.id) } catch (e: Exception) { Log.w("feed", "refresh: ${e.message}") } }; setNotice("refreshing ${src.name}") }
        val unread = this.items.count { !isRead(it) }
        add("Mark all read", "$unread unread", enabled = unread > 0) { confirmMarkAllRead(src) }
        when (src.kind) {
            SourceKind.REDDIT -> {
                add("Browse a subreddit", "keyboard") { openSubredditKeyboard() }
                for (r in recentSubs.take(RECENTS)) add("r/$r") { browse(SourceKind.REDDIT, r) }
            }
            SourceKind.SLASHDOT -> {
                add("Browse a section", "${SlashdotRss.SECTIONS.size} sections") { openSectionMenu() }
                for (r in recentSections.take(RECENTS)) add(r.lowercase(), "section") { browse(SourceKind.SLASHDOT, r) }
            }
            else -> {}
        }
        if (src.transient) {
            if (src.id in pinned) add("Unpin", "drop from the list") { pinned.remove(src.id); send(FeedCore.pinDraft(src.id, false)); setNotice("unpinned") }
            else add("Pin", "keep in the list") { pinned.add(src.id); send(FeedCore.pinDraft(src.id, true)); setNotice("pinned ${src.name}") }
        }
        val flagged = reading.values.sumOf { it.flags.size }
        add("Flagged", "$flagged", enabled = flagged > 0) { level = Level_.FLAGGED; flagModel.cursor = 0 }
        services?.openMenu(MenuSurface.Spec(src.name.lowercase(), items, onCommit = { idx -> acts.getOrNull(idx)?.invoke() }))
    }

    private fun confirmMarkAllRead(src: SourceStatus) {
        services?.openMenu(MenuSurface.Spec("Mark all of ${src.name} read?",
            listOf(MenuSurface.Item("Cancel"), MenuSurface.Item("Mark read", detail = "${items.size} items")),
            onCommit = { idx ->
                if (idx == 1) {
                    val r = reading(src.id)
                    for (it in items) r.read.add(it.id)
                    while (r.read.size > READ_CAP) r.read.remove(r.read.first())
                    recount(src.id, items)
                    send(FeedCore.readDraft(FeedCore.A_MARK_ALL, src.id, r.read))
                    setNotice("all read")
                }
            }))
    }

    private fun openSubredditKeyboard() {
        val opened = services?.openKeyboard(KeyboardSurface.Spec(
            title = "subreddit", initial = "",
            onCommit = { q -> if (q.isBlank()) setNotice("no subreddit typed") else browse(SourceKind.REDDIT, q.trim()) },
        ), owner = this) == true
        if (!opened) setNotice("the keyboard is not available here")
    }

    private fun openSectionMenu() {
        val secs = SlashdotRss.SECTIONS
        services?.openMenu(MenuSurface.Spec("slashdot sections", secs.map { MenuSurface.Item(it.lowercase()) },
            onCommit = { idx -> secs.getOrNull(idx)?.let { browse(SourceKind.SLASHDOT, it) } }))
    }

    private fun browse(kind: SourceKind, name: String) {
        services?.setOperation("browsing")
        bg.launch(Dispatchers.IO) {
            val (st, err) = try { provider.browse(kind, name) to null } catch (e: Exception) { null to (e.message ?: "browse failed") }
            val statuses = try { provider.sources() } catch (e: Exception) { sources }
            onShell {
                services?.setOperation("idle")
                if (st == null) { setNotice(err ?: "browse failed"); return@onShell }
                sources = statuses
                val recents = if (kind == SourceKind.REDDIT) recentSubs else recentSections
                val key = if (kind == SourceKind.REDDIT) name.removePrefix("r/").lowercase() else name
                recents.remove(key); recents.addFirst(key)
                while (recents.size > RECENTS) recents.removeLast()
                send(FeedCore.recentDraft(if (kind == SourceKind.REDDIT) "reddit" else "slashdot", recents.toList()))
                openItems(st.id)
            }
        }
    }

    /** A browsed source that was not pinned goes when its list is left. */
    private fun forgetUnpinned(except: String? = null) {
        val gone = sources.filter { it.transient && it.id !in pinned && it.id != except }
        if (gone.isEmpty()) return
        for (s in gone) { newCount.remove(s.id); bg.launch(Dispatchers.IO) { try { provider.forget(s.id) } catch (e: Exception) { Log.w("feed", "forget: ${e.message}") } } }
        sources = sources.filter { g -> gone.none { it.id == g.id } }
        if (openSource != null && gone.any { it.id == openSource } && except == null) { openSource = null; items.clear(); itemsTotal = 0 }
    }

    // ================================================================ ARTICLE
    private fun loadArticle(it: Item) {
        val seq = ++articleSeq
        articleState = "loading"
        services?.setOperation("loading article")
        bg.launch(Dispatchers.IO) {
            val (a, err) = try { provider.article(it.id) to null } catch (e: Exception) {
                Log.w("feed", "article '${it.title.take(40)}': ${e.message}")
                null to (e.message ?: "article failed")
            }
            onShell {
                if (seq != articleSeq) return@onShell
                services?.setOperation("idle")
                if (a == null) {
                    articleState = err ?: "article failed"
                    setNotice(articleState)
                } else {
                    articleState = ""
                    article = a
                    if (a.note.isNotEmpty() && !a.extracted) setNotice(a.note)
                }
                docWidthKey = -1
                services?.requestRender(this@FeedGlasses)
            }
        }
    }

    private fun loadArticleImage(url: String, seq: Int) {
        if (url in articleImages || url in articleImageFailed || !articleImagePending.add(url)) return
        val w = contentW() - 32
        bg.launch(Dispatchers.IO) {
            val (s, err) = try { provider.image(url, w, comicLevels, LineArt.AUTO) to null } catch (e: Exception) { null to (e.message ?: "image failed") }
            onShell {
                articleImagePending.remove(url)
                if (seq != articleSeq) return@onShell
                if (s == null) articleImageFailed[url] = err ?: "image failed" else articleImages[url] = Strips.unpack(s)
                relayoutKeepingTop()
                services?.requestRender(this@FeedGlasses)
            }
        }
    }

    /** Rebuild the document lines and land the top on the same block/line. */
    private fun relayoutKeepingTop() {
        val old = docLines.getOrNull(docModel.topLine)
        // a taken place inside a picture that was still loading: placed again now it has its strips, unless input moved
        // the page since
        val refine = placeRefine?.takeIf { it.second == docModel.topLine }?.first
        placeRefine = null
        docWidthKey = -1
        ensureDocLines()
        if (refine != null) {
            seatPlace(refine)
            placeKnown = currentPlace()
        } else if (old != null && old.block >= 0) {
            val idx = docLines.indexOfFirst { it.block == old.block && it.sub == old.sub }
            if (idx >= 0) docModel.topLine = idx
        }
        docModel.topLine = docModel.topLine.coerceIn(0, maxOf(0, docLines.size - 1))
    }

    private val articleImagePending = HashSet<String>()

    private fun ensureDocLines() {
        val key = contentW() * 31 + (if (level == Level_.COMIC) 1 else 0)
        if (docWidthKey == key && docLines.isNotEmpty()) return
        docWidthKey = key
        docLines = if (level == Level_.COMIC) comicLines() else articleLines()
        // images are demanded HERE, on the loop's view() — never from a paint
        // — and again after a relayout cleared them for the new width
        val a = article
        if (level == Level_.ARTICLE && a != null && images) {
            for (b in a.blocks) if (b.kind == "img" && b.url !in articleImages && b.url !in articleImageFailed) loadArticleImage(b.url, articleSeq)
        }
        docModel.topLine = docModel.topLine.coerceIn(0, maxOf(0, docLines.size - 1))
        // B6, and S1-3 J §6 (1): the place an entry took is applied once the ARTICLE's lines exist — applied to the
        // "loading" layout (title, byline, placeholder) it was clamped to its top and spent, so a restart brought the
        // article back near its top; the place shown then is what this view knows (not an input: nothing is sent)
        if (level == Level_.ARTICLE && a != null && !placeSeated) {
            pendingPlace?.takeIf { it.item == openItem?.id }?.let { p -> seatPlace(p) }
            pendingPlace = null
            placeKnown = currentPlace()
            placeSeated = true
        }
    }

    // ---- the open article's place (B1 `place`, FeedAnchor) ----

    /** The place an entry took from the record, waiting for the article's lines. */
    private var pendingPlace: FeedCore.Place? = null
    /** The place this view last sent, took or showed at an entry: a save point sends only a move from it. */
    private var placeKnown: FeedCore.Place? = null
    /** False until the open article's lines first exist (the entry's place applied, the shown one known). */
    private var placeSeated = true
    /** Per item, this view's last input on its place (the time its op carried). */
    private val placeInputAt = HashMap<String, Long>()

    /** An entry into [it] (an open, a restore): the record's place for it, when newer than this view's own input. */
    private fun enterPlace(it: Item) {
        pendingPlace = null
        placeSeated = it.kind == ItemKind.COMIC
        val rec = source.state().itemNamed(FeedCore.PLACE)?.takeIf { r -> !r.gone } ?: return
        val p = FeedCore.placeOf(rec.value)?.takeIf { p -> p.item == it.id } ?: return
        if (rec.at <= (placeInputAt[it.id] ?: Long.MIN_VALUE)) return
        pendingPlace = p
    }

    /** A taken place inside a picture whose strips have not arrived, and the line it was put on. */
    private var placeRefine: Pair<FeedCore.Place, Int>? = null

    /** The page put at taken place [p]; a place inside a picture still loading is placed again when it arrives. */
    private fun seatPlace(p: FeedCore.Place) {
        val line = FeedAnchor.line(anchorLines(), p.b, p.o) ?: return
        docModel.topLine = line
        val l = docLines[line]
        if (l.block == p.b && l.strip == null && article?.blocks?.getOrNull(p.b)?.kind == "img" && p.o > 0) placeRefine = p to line
    }

    private fun anchorLines(): List<FeedAnchor.Line> =
        docLines.map { l -> if (l.sub >= 998) FeedAnchor.Line(-1, 0) else FeedAnchor.Line(l.block, l.off) }

    /** The open article's place now: the block and offset of the first block line from the top. */
    private fun currentPlace(): FeedCore.Place? {
        val it = openItem ?: return null
        val a = article ?: return null
        val (b, o) = FeedAnchor.at(anchorLines(), docModel.topLine) ?: return null
        return FeedCore.Place(it.id, b, o, a.ver)
    }

    /** For harnesses: the open article's place as this view shows it. */
    fun placeNow(): FeedCore.Place? = if (placeSeated) currentPlace() else null

    /** For harnesses: the archive's (episode, strip) under the top line. */
    fun bingePlaceNow(): Pair<Int, Int>? = if (loaded.isEmpty()) null else bingePosNow()

    /** [s] wrapped into lines of [block]; each knows where its text starts in the block's text ([lead] characters of
     *  [s] are not the block's: the list item's dash). */
    private fun wrapTo(s: String, f: FontSpec, width: Int, lv: Int, indent: Int, block: Int, out: MutableList<DocLine>, lead: Int = 0) {
        val t = Draw.dynamic(tx, s, f)
        val lines = Wrap.wrap(t, f, tx, width - indent)
        val offs = FeedAnchor.offsets(t, lines)
        for ((sub, line) in lines.withIndex()) out.add(DocLine(line, f, lv, indent, block = block, sub = sub, off = (offs[sub] - lead).coerceAtLeast(0)))
    }

    private fun byline(it: Item): String = itemDetail(it, clock())

    private fun articleLines(): List<DocLine> {
        val it = openItem ?: return emptyList()
        val width = contentW() - 32
        val out = ArrayList<DocLine>()
        docLineH = lineHOf(fRead)
        wrapTo(it.title, fReadB, width, Level.HEAD, 0, -1, out)
        out.add(DocLine(dn(byline(it), fSmall), fSmall, Level.DIM))
        out.add(DocLine())
        val a = article
        if (a == null) {
            out.add(DocLine(if (articleState == "loading" || articleState.isEmpty()) "loading the article" else "failed: ${dn(articleState, fSmall)}",
                fSmall, if (articleState == "loading" || articleState.isEmpty()) Level.REST else Level.HOT))
            if (it.summary.isNotEmpty()) { out.add(DocLine()); wrapTo(it.summary, fRead, width, Level.BODY, 0, -1, out) }
            return out
        }
        if (!a.extracted && a.note.isNotEmpty()) out.add(DocLine(dn(a.note, fSmall), fSmall, Level.DIM))
        for ((bi, b) in a.blocks.withIndex()) {
            when (b.kind) {
                "h" -> { out.add(DocLine()); wrapTo(b.text, fReadB, width, Level.HEAD, 0, bi, out) }
                "p" -> { wrapTo(b.text, fRead, width, Level.BODY, 0, bi, out); out.add(DocLine(block = bi, sub = 999)) }
                "li" -> wrapTo("- " + b.text, fRead, width, Level.BODY, 16, bi, out, lead = 2)
                "q" -> { wrapTo(b.text, fReadI, width, Level.BODY, 24, bi, out); out.add(DocLine(block = bi, sub = 999)) }
                "pre" -> {
                    var at = 0
                    for ((li, line) in b.text.split('\n').withIndex()) {
                        out.add(DocLine(Draw.dynamic(tx, line.replace("\t", "    "), fMono), fMono, Level.BODY, 8, block = bi, sub = li, off = at))
                        at += line.length + 1
                    }
                    out.add(DocLine(block = bi, sub = 999))
                }
                "img" -> {
                    if (!images) continue
                    val im = articleImages[b.url]
                    when {
                        im != null -> {
                            val strips = Strips.cut(im, docLineH)
                            for ((si, s) in strips.withIndex()) out.add(DocLine(strip = s, stripW = im.w, block = bi, sub = si, off = si))
                            if (b.text.isNotEmpty()) out.add(DocLine(dn(b.text, fSmall), fSmall, Level.DIM, block = bi, sub = 998))
                            out.add(DocLine(block = bi, sub = 999))
                        }
                        b.url in articleImageFailed -> out.add(DocLine("image not shown: ${dn(articleImageFailed[b.url] ?: "", fSmall)}", fSmall, Level.DIM, block = bi))
                        else -> out.add(DocLine("image loading", fSmall, Level.REST, block = bi))
                    }
                }
            }
        }
        if (a.blocks.isEmpty()) out.add(DocLine("(nothing to show)", fSmall, Level.DIM))
        return out
    }

    private fun comicLines(): List<DocLine> {
        val it = openItem ?: return emptyList()
        val width = contentW() - 32
        val out = ArrayList<DocLine>()
        docLineH = STRIP_H
        val src = sourceOf(it.source)
        val head = when (src?.kind) { SourceKind.XKCD -> "#${it.num} · ${it.title}"; else -> it.title }
        out.add(DocLine(dn(head, fHead), fHead, Level.HEAD))
        out.add(DocLine(dn(listOfNotNull(src?.name, FeedFmt.age(it.publishedMs, clock()).takeIf { s -> s.isNotEmpty() }?.let { s -> "$s ago" }).joinToString(" · "), fSmall), fSmall, Level.DIM))
        val p = comicPack
        if (p == null) {
            out.add(DocLine(if (articleState == "loading" || articleState.isEmpty()) "loading the strip" else "failed: ${dn(articleState, fSmall)}",
                fSmall, if (articleState == "loading" || articleState.isEmpty()) Level.REST else Level.HOT))
        } else {
            val g = Strips.unpack(p.strip)
            for (s in Strips.cut(g, STRIP_H)) out.add(DocLine(strip = s, stripW = g.w))
        }
        if (it.alt.isNotEmpty()) { out.add(DocLine()); wrapTo(it.alt, fReadI, width, Level.BODY, 0, -1, out) }
        val bonus = p?.bonus
        if (bonus != null) {
            out.add(DocLine())
            val g = Strips.unpack(bonus)
            for (s in Strips.cut(g, STRIP_H)) out.add(DocLine(strip = s, stripW = g.w))
        }
        out.add(DocLine())
        return out
    }

    private fun paintDocLine(g: Gray8, i: Int, r: Rect) {
        val line = docLines.getOrNull(i) ?: return
        val strip = line.strip
        if (strip != null) {
            val x = ((r.x + (r.w - line.stripW).coerceAtLeast(0) / 2) / 4) * 4
            g.blit(strip, Rect(0, 0, strip.w, minOf(strip.h, r.h)), x, r.y)
            return
        }
        if (line.s.isEmpty()) return
        val f = line.f ?: fRead
        val m = tx.metrics(f)
        val off = ((r.h - (m.ascent + m.descent)) / 2).coerceAtLeast(0)
        Draw.fit(g, tx, r.x + 16 + line.indent, r.y + off, line.s, line.lv, f, r.w - 32 - line.indent)
    }

    // ================================================================ COMIC
    private fun loadComic(it: Item) {
        if (comicRangeFor != it.source) {
            comicRange = null; comicRangeFor = it.source
            bg.launch(Dispatchers.IO) {
                val r = try { provider.comicRange(it.source) } catch (e: Exception) { null }
                onShell { if (comicRangeFor == it.source) { comicRange = r; services?.requestRender(this@FeedGlasses) } }
            }
        }
        val seq = ++articleSeq
        articleState = "loading"
        services?.setOperation("loading strip")
        val w = contentW()
        val lv = comicLevels
        val mode = artFor(it.source)
        bg.launch(Dispatchers.IO) {
            val (p, err) = try { provider.comic(it.id, w, lv, mode) to null } catch (e: Exception) {
                Log.w("feed", "comic '${it.title.take(40)}': ${e.message}")
                null to (e.message ?: "strip failed")
            }
            onShell {
                if (seq != articleSeq) return@onShell
                services?.setOperation("idle")
                if (p == null) { articleState = err ?: "strip failed"; setNotice(articleState) }
                else { articleState = ""; comicPack = p; if (p.item.bonus != openItem?.bonus) openItem = p.item }
                docWidthKey = -1
                ensureDocLines()
                // a strip that fits the screen rests on the bar so a tap flips again (xkcd's homepage)
                if (comicFocus < 0 && docLines.size <= comicVisible()) comicFocus = firstEnabledButton()
                services?.requestRender(this@FeedGlasses)
            }
        }
    }

    // ================================================================ the comic canvas
    /** The bar's buttons, in traversal order: a notch down from the strip's end
     *  — or one notch UP from its top, the list grammar's wrap — lands on
     *  `next`, the flip most taps want (Adam, 2026-09-09: "previous, first,
     *  last, random, menu, and next buttons on the main screen … like the xkcd
     *  homepage"). A strip that fits above the bar rests on it at once. */
    private val BAR = listOf("next", "prev", "random", "first", "latest", "menu")

    /** The bar button under focus, or null while panning — for harnesses. */
    fun comicFocusLabel(): String? = BAR.getOrNull(comicFocus)

    private fun comicVisible(): Int = ((services?.docContentHeight() ?: 416) - BAR_H) / STRIP_H

    /** Where this strip sits among its neighbours: numbered (xkcd's archive) or by list position. */
    private class Neighbours(val hasNext: Boolean, val hasPrev: Boolean, val hasRandom: Boolean, val atFirst: Boolean, val atLatest: Boolean)

    private fun neighbours(): Neighbours {
        val it = openItem ?: return Neighbours(false, false, false, true, true)
        val range = comicRange
        if (range != null && it.num > 0) {
            return Neighbours(hasNext = (it.num < range.last), hasPrev = (it.num > range.first), hasRandom = (range.last > range.first),
                atFirst = (it.num <= range.first), atLatest = (it.num >= range.last))
        }
        val idx = items.indexOfFirst { x -> x.id == it.id }
        val older = idx >= 0 && (idx < items.size - 1 || items.size < itemsTotal)
        return Neighbours(hasNext = idx > 0, hasPrev = older, hasRandom = items.size >= 2,
            atFirst = idx == items.size - 1 && items.size >= itemsTotal, atLatest = idx == 0)
    }

    private fun buttonEnabled(i: Int): Boolean {
        val n = neighbours()
        return when (BAR[i]) {
            "next" -> n.hasNext
            "prev" -> n.hasPrev
            "random" -> n.hasRandom
            "first" -> !n.atFirst && (comicRange != null || items.isNotEmpty())
            "latest" -> !n.atLatest && (comicRange != null || items.isNotEmpty())
            else -> true
        }
    }

    private fun firstEnabledButton(): Int = BAR.indices.firstOrNull { buttonEnabled(it) } ?: BAR.lastIndex

    private fun paintComic(g: Gray8, r: Rect) {
        // a canvas owns EVERYTHING in its rect, the background included: the
        // first build painted over the list it replaced (the snapshot showed
        // the item rows through the strip, 2026-09-09 evening)
        g.fillRect(r, Level.BG)
        ensureDocLines()
        val barY = r.bottom - BAR_H
        var y = r.y
        var i = comicTop
        while (y + STRIP_H <= barY && i < docLines.size) {
            paintDocLine(g, i, Rect(r.x, y, r.w, STRIP_H))
            y += STRIP_H; i++
        }
        // the bar: six labels in six slots; the focused one bright with a mark under it
        val slot = r.w / BAR.size
        for ((k, label) in BAR.withIndex()) {
            val enabled = buttonEnabled(k)
            val lv = when { k == comicFocus -> Level.HEAD; enabled -> Level.DIM; else -> Level.FAINT }
            val w = tx.measure(label, fSmall)
            val x = (r.x + k * slot + (slot - w) / 2) / 4 * 4
            tx.draw(g, x, (barY + 8) / 2 * 2, label, fSmall, lv)
            if (k == comicFocus) g.fillRect(x, barY + 26, w / 2 * 2, 2, Level.HEAD)
        }
    }

    private fun comicScroll(delta: Int) {
        val visible = comicVisible()
        val maxTop = maxOf(0, docLines.size - visible)
        if (delta > 0) {
            if (comicFocus < 0) {
                if (comicTop < maxTop) comicTop = (comicTop + 5).coerceAtMost(maxTop)
                else comicFocus = firstEnabledButton()
            } else {
                val next = (comicFocus + 1 until BAR.size).firstOrNull { buttonEnabled(it) }
                if (next != null) comicFocus = next
            }
        } else if (delta < 0) {
            if (comicFocus < 0) {
                // the list grammar's wrap (DESIGN §4.6): one notch up from the top lands on the bar
                if (comicTop > 0) comicTop = (comicTop - 5).coerceAtLeast(0) else comicFocus = firstEnabledButton()
            } else {
                val prev = (comicFocus - 1 downTo 0).firstOrNull { buttonEnabled(it) }
                if (prev != null) comicFocus = prev
                else if (docLines.size > visible || comicTop > 0) { comicFocus = -1; comicTop = (comicTop - 5).coerceAtLeast(0) }
            }
        }
        services?.requestRender(this)
    }

    private fun comicTap() {
        if (comicFocus < 0) { openActions(); return }
        when (val b = BAR[comicFocus]) {
            "menu" -> openActions()
            else -> if (buttonEnabled(comicFocus)) flip(b) else setNotice("$b: nothing there")
        }
    }

    /** A flip: by number through a numbered archive (missing numbers skipped),
     *  by list position otherwise; the answer opens as the strip, read on open. */
    private fun flip(button: String) {
        val cur = openItem ?: return
        val range = comicRange
        val src = cur.source
        val seq = ++comicSeq
        if (range != null && cur.num > 0) {
            val n = neighbours()
            val step = when (button) { "next" -> 1; "prev" -> -1; else -> 0 }
            val target = when (button) {
                "next" -> if (n.hasNext) cur.num + 1 else return
                "prev" -> if (n.hasPrev) cur.num - 1 else return
                "first" -> range.first
                "latest" -> range.last
                "random" -> kotlin.random.Random.nextInt(range.first, range.last + 1)
                else -> return
            }
            services?.setOperation("flipping")
            bg.launch(Dispatchers.IO) {
                var num = target
                var found: Item? = null
                var tries = 0
                while (found == null && tries < 6 && num in range) {
                    found = try { provider.comicAt(src, num) } catch (e: Exception) { Log.w("feed", "comic $num: ${e.message}"); null }
                    if (found == null) num = if (button == "random") kotlin.random.Random.nextInt(range.first, range.last + 1) else num + (if (step == 0) 1 else step)
                    tries++
                }
                onShell {
                    if (seq != comicSeq) return@onShell
                    services?.setOperation("idle")
                    if (found == null) setNotice("$button: nothing there") else openOne(found)
                    services?.requestRender(this@FeedGlasses)
                }
            }
        } else {
            val idx = items.indexOfFirst { x -> x.id == cur.id }
            val target = when (button) {
                "next" -> items.getOrNull(idx - 1)
                "prev" -> items.getOrNull(idx + 1)
                "first" -> items.lastOrNull()
                "latest" -> items.firstOrNull()
                "random" -> if (items.size >= 2) items.filter { x -> x.id != cur.id }.random() else null
                else -> null
            }
            if (target == null) {
                // an older strip beyond the loaded page: fetch the page, then flip
                if (button == "prev" && items.size < itemsTotal && !itemsLoading) { loadItems(items.size); setNotice("loading more") }
                else setNotice("$button: nothing there")
                return
            }
            itemModel.cursor = itemRows().indexOfFirst { (it as? IRow.It)?.item?.id == target.id }.coerceAtLeast(0)
            openOne(target)
        }
    }

    // ================================================================ ACTIONS
    private class Act(val name: String, val detail: String, val enabled: Boolean = true)

    private fun nextItem(): Item? {
        val cur = openItem ?: return null
        val idx = items.indexOfFirst { it.id == cur.id }
        return if (idx >= 0) items.getOrNull(idx + 1) else null
    }

    private fun actions(): List<Act> {
        val it = openItem ?: return listOf(Act("Back to list", ""))
        val out = ArrayList<Act>()
        val canComments = it.commentsUrl.isNotEmpty()
        out.add(Act("Comments", when {
            canComments && it.comments >= 0 -> "${it.comments}"
            canComments -> "reddit"
            it.comments >= 0 -> "not reachable · ${it.comments} on the site"
            else -> "not reachable"
        }, enabled = canComments))
        out.add(Act(if (isFlagged(it)) "Unflag" else "Flag", "read later"))
        out.add(Act(if (isRead(it)) "Mark unread" else "Mark read", ""))
        if (it.kind != ItemKind.COMIC) {
            val next = nextItem()
            out.add(Act("Next item", next?.let { dn(it.title) } ?: "none", enabled = next != null))
        }
        if (articleState.isNotEmpty() && articleState != "loading") out.add(Act("Reload", dn(articleState, fSmall)))
        out.add(Act(if (it.kind == ItemKind.COMIC) "Back to strip" else "Back to list", ""))
        return out
    }

    private fun openActions() {
        level = Level_.ACTIONS
        actModel.cursor = actions().indexOfFirst { it.enabled }.coerceAtLeast(0)
    }

    private fun paintActRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val a = actions().getOrNull(i) ?: return
        val lv = if (a.enabled) Level.DIM else Level.FAINT
        tx.draw(g, (r.x + 32) / 4 * 4, (r.y + 7) / 2 * 2, a.name, fSmall, lv)
        Draw.fit(g, tx, r.x + 200, r.y + 5, a.detail, if (a.enabled) Level.BODY else Level.REST, fRow, r.right - 24 - (r.x + 200))
    }

    private fun paintActLens(g: Gray8, r: Rect, i: Int) {
        val a = actions().getOrNull(i) ?: return
        IconPaint.drawKind(g, IconKind.FEED, r.x + 12, r.y + 10, 24, if (a.enabled) Level.HEAD else Level.DIM)
        tx.draw(g, (r.x + 44) / 4 * 4, (r.y + 8) / 2 * 2, a.name, fHead, if (a.enabled) Level.HEAD else Level.DIM)
        Draw.fit(g, tx, r.x + 44, Draw.lineBelow(tx, fHead, r.y + 8, r.y + 34), a.detail, Level.BODY, fBody, r.w - 60)
    }

    private fun commitAction(i: Int) {
        val a = actions().getOrNull(i) ?: return
        val it = openItem
        if (!a.enabled) { setNotice("${a.name.lowercase()}: ${a.detail.ifEmpty { "not available" }}"); return }
        when (a.name) {
            "Comments" -> if (it != null) openComments(it)
            "Flag", "Unflag" -> if (it != null) { toggleFlag(it); setNotice(if (isFlagged(it)) "flagged" else "unflagged"); back() }
            "Mark unread", "Mark read" -> if (it != null) {
                val read = a.name == "Mark read"
                markRead(it, read, if (read) FeedCore.A_MARK_READ else FeedCore.A_UNREAD); setNotice(a.name.lowercase()); back()
            }
            "Next item" -> nextItem()?.let { n ->
                itemModel.cursor = itemRows().indexOfFirst { (it as? IRow.It)?.item?.id == n.id }.coerceAtLeast(0)
                openOne(n)
            }
            "Reload" -> if (it != null) { if (it.kind == ItemKind.COMIC) { level = Level_.COMIC; loadComic(it) } else { level = Level_.ARTICLE; loadArticle(it) } }
            "Back to list" -> { articleSeq++; level = if (fromFlagged) Level_.FLAGGED else Level_.ITEMS; services?.setOperation("idle") }
            "Back to strip" -> level = Level_.COMIC
        }
    }

    // ================================================================ COMMENTS
    private fun openComments(it: Item) {
        level = Level_.COMMENTS
        commentsModel.topLine = pendingCommentsTop ?: 0
        pendingCommentsTop = null
        if (comments == null) loadComments(it)
    }

    private fun loadComments(it: Item) {
        val seq = ++commentsSeq
        commentsState = "loading"
        commentLines = emptyList()
        services?.setOperation("loading comments")
        bg.launch(Dispatchers.IO) {
            val (cs, err) = try { provider.comments(it.id) to null } catch (e: RateLimited) {
                null to "reddit rate-limited · retry ${FeedFmt.dur(maxOf(0L, (e.retryAtMs - clock() + 999) / 1000))}"
            } catch (e: Exception) { null to (e.message ?: "comments failed") }
            onShell {
                if (seq != commentsSeq) return@onShell
                services?.setOperation("idle")
                if (cs == null) { commentsState = err ?: "comments failed"; setNotice(commentsState) }
                else { commentsState = ""; comments = cs }
                commentLines = emptyList()
                services?.requestRender(this@FeedGlasses)
            }
        }
    }

    private fun ensureCommentLines() {
        if (commentLines.isNotEmpty()) return
        val width = contentW() - 32
        val out = ArrayList<DocLine>()
        commentLineH = lineHOf(fBody)
        val cs = comments
        val it = openItem
        if (it != null) {
            out.add(DocLine(dn(it.title, fHead), fHead, Level.HEAD))
            val own = Extract.fragment(it.body, it.link).ifEmpty { if (it.summary.isNotEmpty()) listOf(Block("p", it.summary)) else emptyList() }
            if (own.isNotEmpty()) {
                out.add(DocLine(dn(byline(it), fSmall), fSmall, Level.DIM))
                for (b in own) when (b.kind) {
                    "img" -> {}
                    "h" -> wrapTo(b.text, fReadB, width, Level.HEAD, 0, -1, out)
                    else -> wrapTo(b.text, fRead, width, Level.BODY, if (b.kind == "q") 24 else 0, -1, out)
                }
                out.add(DocLine())
            }
        }
        if (cs == null) {
            out.add(DocLine(if (commentsState == "loading") "loading comments" else "failed: ${dn(commentsState, fSmall)}", fSmall,
                if (commentsState == "loading") Level.REST else Level.HOT))
        } else if (cs.isEmpty()) {
            out.add(DocLine("no comments yet", fSmall, Level.DIM))
        } else {
            out.add(DocLine("${cs.size} comments", fSmall, Level.DIM))
            for (c in cs) {
                val indent = (c.depth.coerceIn(0, 4)) * 16
                out.add(DocLine())
                val head = listOf(c.author, c.score, FeedFmt.age(c.publishedMs, clock())).filter { it.isNotEmpty() }.joinToString(" · ")
                if (c.title.isNotEmpty() && !c.title.startsWith("Re:")) out.add(DocLine(dn(c.title, fHead), fHead, Level.HEAD, indent))
                if (head.isNotEmpty()) out.add(DocLine(dn(head, fSmall), fSmall, Level.DIM, indent))
                for (para in c.text.split('\n')) if (para.isNotBlank()) wrapTo(para, fBody, width, Level.BODY, indent, -1, out)
            }
        }
        out.add(DocLine())
        commentLines = out
        val top = pendingCommentsTop
        if (top != null && cs != null) { commentsModel.topLine = top.coerceIn(0, out.size - 1); pendingCommentsTop = null }
        commentsModel.topLine = commentsModel.topLine.coerceIn(0, maxOf(0, out.size - 1))
    }

    private fun paintCommentLine(g: Gray8, i: Int, r: Rect) {
        val line = commentLines.getOrNull(i) ?: return
        if (line.s.isEmpty()) return
        val f = line.f ?: fBody
        val m = tx.metrics(f)
        val off = ((r.h - (m.ascent + m.descent)) / 2).coerceAtLeast(0)
        Draw.fit(g, tx, r.x + 16 + line.indent, r.y + off, line.s, line.lv, f, r.w - 32 - line.indent)
    }

    // ================================================================ BINGE
    /**
     * A new archive sequence: the load in flight with the old one is ended with it. S1-3 J §6 (2): `back()` and a
     * relayout bumped the sequence and left `bingeLoading` set — the late answer returns early on the sequence, so the
     * archive entered again never loaded another page until a restart. Every bump goes through here.
     */
    private fun newBingeSeq() {
        bingeSeq++
        bingeLoading = false
    }

    private fun openBinge(sourceId: String) {
        if (bingeSource != sourceId) {
            bingeSource = sourceId
            newBingeSeq()
            loaded.clear()
            bingeLineMap = emptyList()
            episodes = emptyList()
            bingeState = ""
            bingeModel.topLine = 0
        }
        level = Level_.BINGE
        provider.setFocused(sourceId)
        takeBinge(sourceId)
        ensureBinge()
    }

    private fun ensureBinge() {
        val src = bingeSource ?: return
        if (episodes.isEmpty()) { loadBingeIndex(src); return }
        if (loaded.isEmpty() && !bingeLoading) {
            // a place taken from the record is read at this width now (the record's intake ran before the shell said it)
            val taken = bingeTaken.remove(src)?.let { b -> anchorOf(b).also { pendingBingeFrac = it.second }.first }
            val anchor = pendingBingeAnchor ?: taken ?: bingePos[src] ?: (episodes.first().num to 0)
            pendingBingeAnchor = anchor
            bingeSeat = true                         // an entry's place: shown, not sent
            loadEpisode(src, anchor.first, where = ANCHOR)
        }
    }

    // ---- the archive's place (B1 `binge.<src>`) ----

    /** Per source, the place this view last sent, took or showed at an entry: a save point sends only a move from it. */
    private val bingeKnown = HashMap<String, Pair<Int, Int>>()
    /** Per source, this view's last input on its place (the time its op carried). */
    private val bingeInputAt = HashMap<String, Long>()
    /** Per source, another device's place taken for the source list and the next entry, read at the width then. */
    private val bingeTaken = HashMap<String, FeedCore.Binge>()
    /** The anchor's fraction into its episode, for a place made at another width (the web's, another glasses'). */
    private var pendingBingeFrac: Double? = null
    /** The anchor being loaded is an entry's (shown, not sent); a jump's is an input. */
    private var bingeSeat = false

    /** The glasses' strip of a record place: its own where it was made at this width, else from its fraction. */
    private fun anchorOf(b: FeedCore.Binge): Pair<Pair<Int, Int>, Double?> =
        if (b.s != null && (b.w == contentW() || b.f == null)) (b.ep to b.s) to null else (b.ep to 0) to b.f

    /** Another device's place for [src], newer than this view's own input: taken for the source list, never moving an
     *  open archive (C§2.6). True when the list's line moved. */
    private fun bingeFrom(src: String, it: wm.damage.core.app.Item): Boolean {
        if (it.gone || (it.by == device && src in bingeKnown)) return false
        if (it.at <= (bingeInputAt[src] ?: Long.MIN_VALUE)) return false
        if (bingeSource == src && (level == Level_.BINGE || level == Level_.BINGE_ACTIONS)) return false
        val b = FeedCore.bingeOf(it.value) ?: return false
        bingeTaken[src] = b
        val pos = b.ep to (b.s ?: 0)
        if (bingePos[src] == pos) return false
        bingePos[src] = pos
        return true
    }

    /** An entry into [src]'s archive: the record's place, when newer than this view's own input and not where it is. */
    private fun takeBinge(src: String) {
        val it = source.state().itemNamed(FeedCore.BINGE + src)?.takeIf { r -> !r.gone } ?: return
        if (it.at <= (bingeInputAt[src] ?: Long.MIN_VALUE)) return
        val b = FeedCore.bingeOf(it.value) ?: return
        val (pos, frac) = anchorOf(b)
        bingeTaken.remove(src)
        if (bingeKnown[src] == pos || (loaded.isNotEmpty() && bingePosNow() == pos)) return
        bingePos[src] = pos
        bingeKnown[src] = pos
        pendingBingeFrac = frac
        if (loaded.isNotEmpty()) {
            newBingeSeq()
            loaded.clear()
            bingeLineMap = emptyList()
        }
        pendingBingeAnchor = pos
    }

    /** At the save point: the open article's and the archive's places, each sent when input moved it (L1 C1: stamped
     *  with that input's time — the save point follows the input within one save period). */
    private fun sendPositions() {
        val it = openItem
        if (it != null && placeSeated && it.kind != ItemKind.COMIC &&
            (level == Level_.ARTICLE || level == Level_.ACTIONS || level == Level_.COMMENTS)) {
            val p = currentPlace()
            if (p != null && p != placeKnown) {
                placeKnown = p
                val at = source.now()
                placeInputAt[p.item] = at
                send(FeedCore.placeDraft(p, at))
            }
        }
        rememberBingePos()
        val src = bingeSource ?: return
        if (loaded.isEmpty() || bingeLoading) return
        val cur = bingePosNow() ?: return
        if (bingeKnown[src] == cur) return
        bingeKnown[src] = cur
        val at = source.now()
        bingeInputAt[src] = at
        val strips = loaded.firstOrNull { e -> e.ep.num == cur.first }?.strips?.size ?: 0
        val f = if (strips > 0) Math.round(cur.second.toDouble() / strips * 100) / 100.0 else 0.0
        send(FeedCore.bingeDraft(src, FeedCore.Binge(cur.first, f, cur.second, contentW()), at))
    }

    private fun loadBingeIndex(src: String) {
        if (bingeIndexLoading) return
        bingeIndexLoading = true
        val seq = bingeSeq
        services?.setOperation("indexing archive")
        bg.launch(Dispatchers.IO) {
            val (eps, err) = try { provider.bingeIndex(src) to null } catch (e: Exception) { null to (e.message ?: "index failed") }
            onShell {
                bingeIndexLoading = false
                if (seq != bingeSeq) return@onShell
                services?.setOperation("idle")
                if (eps == null || eps.isEmpty()) { bingeState = err ?: "the archive is empty"; setNotice(bingeState) }
                else { bingeState = ""; episodes = eps; ensureBinge() }
                services?.requestRender(this@FeedGlasses)
            }
        }
    }

    private fun nextEpisodeNum(after: Int): Int? = episodes.firstOrNull { it.num > after }?.num
    private fun prevEpisodeNum(before: Int): Int? = episodes.lastOrNull { it.num < before }?.num

    private fun demandBingeIfNear() {
        if (!active || bingeLoading || bingeSource == null || loaded.isEmpty()) return
        val visible = (services?.docContentHeight() ?: 416) / STRIP_H
        val total = bingeLineCount()
        val src = bingeSource!!
        if (bingeModel.topLine + visible >= total - visible) {
            nextEpisodeNum(loaded.last().ep.num)?.let { loadEpisode(src, it, where = BACK); return }
        }
        if (bingeModel.topLine < visible) {
            prevEpisodeNum(loaded.first().ep.num)?.let { loadEpisode(src, it, where = FRONT) }
        }
    }

    private fun loadEpisode(src: String, num: Int, where: Int) {
        if (bingeLoading) return
        bingeLoading = true
        val seq = bingeSeq
        val w = contentW()
        val lv = comicLevels
        val mode = artFor(src)
        services?.setOperation("loading page $num")
        bg.launch(Dispatchers.IO) {
            val (p, err) = try { provider.bingeEpisode(src, num, w, lv, mode) to null } catch (e: Exception) {
                Log.w("feed", "episode $num: ${e.message}")
                null to (e.message ?: "page failed")
            }
            onShell {
                if (seq != bingeSeq) return@onShell
                bingeLoading = false
                services?.setOperation("idle")
                if (p == null) { bingeState = err ?: "page $num failed"; setNotice(bingeState); services?.requestRender(this@FeedGlasses); return@onShell }
                bingeState = ""
                val g = Strips.unpack(p.strip)
                val ep = EpLoaded(p.episode, Strips.cut(g, STRIP_H), g.w)
                when (where) {
                    ANCHOR -> {
                        loaded.clear(); loaded.add(ep)
                        rebuildBingeMap()
                        val a = pendingBingeAnchor; pendingBingeAnchor = null
                        val frac = pendingBingeFrac; pendingBingeFrac = null
                        val strip = if (a != null && frac != null) (frac * ep.strips.size).toInt().coerceIn(0, maxOf(0, ep.strips.size - 1)) else a?.second ?: 0
                        // a page's first strip shows its header line too; deeper in, the strip itself tops the screen
                        bingeModel.topLine = if (a != null && a.first == num && strip > 0) (1 + strip).coerceIn(0, ep.lines - 1) else 0
                        if (bingeSeat) { bingePosNow()?.let { bingeKnown[src] = it }; bingeSeat = false }
                    }
                    BACK -> { loaded.add(ep); while (loaded.size > LOADED_CAP) { val d = loaded.removeAt(0); bingeModel.topLine = (bingeModel.topLine - d.lines).coerceAtLeast(0) }; rebuildBingeMap() }
                    FRONT -> { loaded.add(0, ep); bingeModel.topLine += ep.lines; while (loaded.size > LOADED_CAP) loaded.removeAt(loaded.size - 1); rebuildBingeMap() }
                }
                bingeModel.topLine = bingeModel.topLine.coerceIn(0, maxOf(0, bingeLineCount() - 1))
                rememberBingePos()
                services?.requestRender(this@FeedGlasses)
            }
        }
    }

    private fun rebuildBingeMap() {
        val map = ArrayList<Pair<Int, Int>>()
        for ((li, ep) in loaded.withIndex()) for (k in 0 until ep.lines) map.add(li to k)
        bingeLineMap = map
    }

    private fun bingeLineCount(): Int = bingeLineMap.size + 1      // + the tail line (loading / end)

    /** The (episode, strip) under the top line. */
    private fun bingePosNow(): Pair<Int, Int>? {
        val m = bingeLineMap.getOrNull(bingeModel.topLine) ?: return loaded.firstOrNull()?.let { it.ep.num to 0 }
        val ep = loaded.getOrNull(m.first) ?: return null
        return ep.ep.num to (m.second - 1).coerceIn(0, maxOf(0, ep.strips.size - 1))
    }

    private fun rememberBingePos() {
        val src = bingeSource ?: return
        bingePosNow()?.let { bingePos[src] = it; bingeTaken.remove(src) }
    }

    private fun paintBingeLine(g: Gray8, i: Int, r: Rect) {
        val m = bingeLineMap.getOrNull(i)
        if (m == null) {
            val s = when {
                bingeLoading -> "loading the next page"
                bingeState.isNotEmpty() -> "failed: ${dn(bingeState, fSmall)}"
                loaded.isNotEmpty() && nextEpisodeNum(loaded.last().ep.num) == null -> "the end"
                episodes.isEmpty() -> if (bingeIndexLoading) "indexing the archive" else "no archive"
                else -> "more"
            }
            Draw.fit(g, tx, r.x + 16, r.y + 8, s, if (bingeState.isNotEmpty()) Level.HOT else Level.REST, fSmall, r.w - 32)
            return
        }
        val ep = loaded.getOrNull(m.first) ?: return
        when {
            m.second == 0 -> {
                val head = dn("Episode ${ep.ep.num} · ${ep.ep.title}", fHead)
                Draw.fit(g, tx, r.x + 16, r.y + 6, head, Level.HEAD, fHead, r.w - 32)
            }
            m.second <= ep.strips.size -> {
                val s = ep.strips[m.second - 1]
                val x = ((r.x + (r.w - ep.stripW).coerceAtLeast(0) / 2) / 4) * 4
                g.blit(s, Rect(0, 0, s.w, minOf(s.h, r.h)), x, r.y)
            }
            else -> {}   // the spacer
        }
    }

    private fun bingeActions(): List<Act> {
        val cur = bingePosNow()?.first
        val total = episodes.size
        return listOf(
            Act("Next episode", cur?.let { nextEpisodeNum(it)?.let { n -> "episode $n" } } ?: "none", enabled = cur != null && nextEpisodeNum(cur) != null),
            Act("Previous episode", cur?.let { prevEpisodeNum(it)?.let { n -> "episode $n" } } ?: "none", enabled = cur != null && prevEpisodeNum(cur) != null),
            Act("Jump to episode", "keyboard · 1 to ${episodes.lastOrNull()?.num ?: 0}", enabled = total > 0),
            Act("First", episodes.firstOrNull()?.let { "episode ${it.num}" } ?: "", enabled = total > 0),
            Act("Latest", episodes.lastOrNull()?.let { "episode ${it.num}" } ?: "", enabled = total > 0),
            Act("Back", cur?.let { "episode $it of $total" } ?: ""),
        )
    }

    private fun paintBingeActRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val a = bingeActions().getOrNull(i) ?: return
        tx.draw(g, (r.x + 32) / 4 * 4, (r.y + 7) / 2 * 2, a.name, fSmall, if (a.enabled) Level.DIM else Level.FAINT)
        Draw.fit(g, tx, r.x + 220, r.y + 5, a.detail, if (a.enabled) Level.BODY else Level.REST, fRow, r.right - 24 - (r.x + 220))
    }

    private fun paintBingeActLens(g: Gray8, r: Rect, i: Int) {
        val a = bingeActions().getOrNull(i) ?: return
        IconPaint.drawKind(g, IconKind.FEED, r.x + 12, r.y + 10, 24, if (a.enabled) Level.HEAD else Level.DIM)
        tx.draw(g, (r.x + 44) / 4 * 4, (r.y + 8) / 2 * 2, a.name, fHead, if (a.enabled) Level.HEAD else Level.DIM)
        Draw.fit(g, tx, r.x + 44, Draw.lineBelow(tx, fHead, r.y + 8, r.y + 34), a.detail, Level.BODY, fBody, r.w - 60)
    }

    private fun jumpTo(num: Int) {
        val src = bingeSource ?: return
        if (episodes.none { it.num == num }) { setNotice("no episode $num"); return }
        newBingeSeq()
        bingeSeat = false                            // a jump is an input: its place is sent at the save point
        pendingBingeFrac = null
        loaded.clear()
        bingeLineMap = emptyList()
        pendingBingeAnchor = num to 0
        level = Level_.BINGE
        loadEpisode(src, num, where = ANCHOR)
    }

    private fun commitBingeAction(i: Int) {
        val a = bingeActions().getOrNull(i) ?: return
        if (!a.enabled) { setNotice("${a.name.lowercase()}: ${a.detail.ifEmpty { "not available" }}"); return }
        val cur = bingePosNow()?.first
        when (a.name) {
            "Next episode" -> cur?.let { nextEpisodeNum(it) }?.let { jumpTo(it) }
            "Previous episode" -> cur?.let { prevEpisodeNum(it) }?.let { jumpTo(it) }
            "Jump to episode" -> {
                val opened = services?.openKeyboard(KeyboardSurface.Spec(
                    title = "episode number", initial = "",
                    onCommit = { s -> val n = s.trim().toIntOrNull(); if (n == null) setNotice("not a number") else jumpTo(n) },
                ), owner = this) == true
                if (!opened) setNotice("the keyboard is not available here")
            }
            "First" -> episodes.firstOrNull()?.let { jumpTo(it.num) }
            "Latest" -> episodes.lastOrNull()?.let { jumpTo(it.num) }
            "Back" -> level = Level_.BINGE
        }
    }

    // ================================================================ FLAGGED
    private fun flagRows(): List<Flag> = reading.values.flatMap { it.flags.values }.sortedByDescending { it.atMs }

    private fun paintFlagRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val f = flagRows().getOrNull(i) ?: run {
            Draw.fit(g, tx, r.x + 32, r.y + 5, if (flagRows().isEmpty()) "nothing flagged" else "Feed", Level.DIM, fRow, r.w - 56)
            return
        }
        flagMark(g, r.x + 12, r.y + 9, Level.MID)
        val tail = sourceOf(f.source)?.name?.lowercase() ?: f.source
        val tw = tx.measure(tail, fSmall) + 16
        Draw.fit(g, tx, r.x + 32, r.y + 5, dn(f.title), Level.BODY, fRow, r.w - 32 - 24 - tw)
        Draw.right(g, tx, r.right - 24, r.y + 8, tail, Level.DIM, fSmall)
    }

    private fun paintFlagLens(g: Gray8, r: Rect, i: Int) {
        val y2 = Draw.lineBelow(tx, fHead, r.y + 6, r.y + 32)
        val f = flagRows().getOrNull(i) ?: run {
            Draw.fit(g, tx, r.x + 16, r.y + 6, "Flagged", Level.HEAD, fHead, r.w - 32)
            Draw.fit(g, tx, r.x + 16, y2, "${flagRows().size} items", Level.BODY, fBody, r.w - 32)
            return
        }
        Draw.fit(g, tx, r.x + 16, r.y + 6, dn(f.title, fHead), Level.HEAD, fHead, r.w - 32)
        Draw.fit(g, tx, r.x + 16, y2, dn("${sourceOf(f.source)?.name ?: f.source} · flagged ${FeedFmt.age(f.atMs, clock())} ago", fBody), Level.BODY, fBody, r.w - 32)
    }

    private fun commitFlag(i: Int) {
        val f = flagRows().getOrNull(i) ?: return
        services?.setOperation("opening")
        bg.launch(Dispatchers.IO) {
            val it = try { provider.item(f.title.let { _ -> flagItemId(f) }) } catch (e: Exception) { null }
            onShell {
                services?.setOperation("idle")
                fromFlagged = true
                if (it != null) { openOne(it); return@onShell }
                // retention pruned it: read the page itself
                val synthetic = Item(flagItemId(f), f.source, f.title, f.link, publishedMs = f.publishedMs, kind = f.kind, target = f.link)
                openItem = synthetic
                comicPack = null; article = null; articleState = "loading"; docWidthKey = -1
                docModel.topLine = 0
                enterPlace(synthetic)
                level = Level_.ARTICLE
                val seq = ++articleSeq
                bg.launch(Dispatchers.IO) {
                    val (a, err) = try { provider.articleByUrl(f.link, f.title) to null } catch (e: Exception) { null to (e.message ?: "article failed") }
                    onShell {
                        if (seq != articleSeq) return@onShell
                        if (a == null) { articleState = err ?: "article failed"; setNotice(articleState) } else { articleState = ""; article = a }
                        docWidthKey = -1
                        services?.requestRender(this@FeedGlasses)
                    }
                }
            }
        }
    }

    private fun flagItemId(f: Flag): String = reading[f.source]?.flags?.entries?.firstOrNull { it.value === f }?.key ?: ""

    // ================================================================ settings
    /** Settings → Feed (B6: `PC loss` gone with the phone's own engine, B5): every row but Images is a record row,
     *  its field set first and its op after; Images is this device's. */
    private val settingsRows: List<HostSetting> by lazy {
        val arts = listOf("auto", "never", "always")
        fun row(item: String, v: JsonElement) = send(FeedCore.setDraft(item, v))
        fun artRow(name: String, item: String, get: () -> LineArt, set: (LineArt) -> Unit) = HostSetting(name, arts,
            { get().name.lowercase() }, { v ->
                set(LineArt.entries.first { it.name.equals(v, ignoreCase = true) }); invalidateDocs()
                row(item, JsonPrimitive(v.lowercase()))
            })
        listOf(
            HostSetting("Size", listOf("global") + wm.damage.core.shell.ShellSettings.HEIGHTS.map { "$it" },
                { heightPref?.toString() ?: "global" }, { heightPref = it.toIntOrNull(); row(FeedCore.SIZE, JsonPrimitive(heightPref ?: 0)) }),
            HostSetting("Images", listOf("on", "off"), { if (images) "on" else "off" }, { images = it == "on"; docWidthKey = -1 }),
            artRow("xkcd art", FeedCore.ART_XKCD, { artXkcd }) { artXkcd = it },
            artRow("SMBC art", FeedCore.ART_SMBC, { artSmbc }) { artSmbc = it },
            artRow("8-Bit art", FeedCore.ART_BINGE, { artBinge }) { artBinge = it },
            HostSetting("Comic levels", listOf("16", "8", "4"), { "$comicLevels" }, {
                comicLevels = it.toIntOrNull() ?: 16; invalidateDocs(); row(FeedCore.LEVELS, JsonPrimitive(comicLevels))
            }),
            HostSetting("Fetch", FETCHES.keys.toList(), { FETCHES.entries.firstOrNull { it.value == fetchMin }?.key ?: "15 min" },
                { fetchMin = FETCHES[it] ?: 15; row(FeedCore.FETCH, JsonPrimitive(fetchMin)) }),
            HostSetting("Keep", KEEPS.keys.toList(), { KEEPS.entries.firstOrNull { it.value == keepDays }?.key ?: "30 d" },
                { keepDays = KEEPS[it] ?: 30; row(FeedCore.KEEP, JsonPrimitive(keepDays)) }),
            HostSetting("Notify · Reddit", listOf("off", "on"), { if (notifyReddit) "on" else "off" },
                { notifyReddit = it == "on"; row(FeedCore.NOTIFY_REDDIT, JsonPrimitive(notifyReddit)) }),
            HostSetting("Notify · Slashdot", listOf("off", "on"), { if (notifySlashdot) "on" else "off" },
                { notifySlashdot = it == "on"; row(FeedCore.NOTIFY_SLASHDOT, JsonPrimitive(notifySlashdot)) }),
            HostSetting("Notify · comics", listOf("off", "on"), { if (notifyComics) "on" else "off" },
                { notifyComics = it == "on"; row(FeedCore.NOTIFY_COMICS, JsonPrimitive(notifyComics)) }),
        )
    }

    override fun appSettings(): List<HostSetting> = settingsRows

    // ================================================================ persistence
    /** The view (per interface: B1's "everything else"); the record's rows are not copied here. The save point sends
     *  the two places first ([sendPositions]). */
    override fun saveState(): JsonObject = buildJsonObject {
        sendPositions()
        put("level", level.name)
        (srcRows().getOrNull(srcModel.cursor) as? SRow.Src)?.s?.id?.let { put("srcCursorId", it) }
        if (srcRows().isNotEmpty() && srcModel.cursor >= srcRows().size - 1) put("srcMenu", true)
        openSource?.let { put("openSource", it) }
        (itemRows().getOrNull(itemModel.cursor) as? IRow.It)?.item?.id?.let { put("itemCursorId", it) }
        if (items.isNotEmpty() && itemModel.cursor >= itemRows().size - 1) put("itemMenu", true)
        openItem?.let { put("openItemId", it.id); put("openItemSource", it.source) }
        put("fromFlagged", fromFlagged)
        put("actCursor", actModel.cursor)
        put("bingeActCursor", bingeActModel.cursor)
        put("flagCursor", flagModel.cursor)
        put("comicTop", comicTop)
        put("commentsTop", commentsModel.topLine)
        bingeSource?.let { put("bingeSource", it) }
        put("images", images)
    }

    override fun restoreState(state: JsonObject) {
        fun s(k: String) = state[k]?.jsonPrimitive?.contentOrNull
        fun i(k: String, d: Int) = state[k]?.jsonPrimitive?.intOrNull ?: d
        fun b(k: String, d: Boolean) = state[k]?.jsonPrimitive?.booleanOrNull ?: d
        // P7 item 5 (OS-3), step 1: a session start on the SAME instance (a link end, a Silent wake, a rebuild) whose
        // record names the item this instance holds open, with no re-open armed, keeps the level, the item and its
        // content — the re-open path below demoted the article to its list and fetched it again. One read of each.
        val heldOpen = openItem
        val keepHeld = pendingOpenItemId == null && heldOpen != null &&
            heldOpen.id == s("openItemId") && heldOpen.source == s("openItemSource")
        level = s("level")?.let { n -> Level_.entries.firstOrNull { it.name == n } } ?: Level_.SOURCES
        openSource = s("openSource")
        val restoredSrc = s("srcCursorId")
        pendingItemCursorId = s("itemCursorId")
        pendingItemMenu = b("itemMenu", false)
        val itemId = s("openItemId")
        val itemSrc = s("openItemSource")
        fromFlagged = b("fromFlagged", false)
        actModel.cursor = i("actCursor", 0)
        bingeActModel.cursor = i("bingeActCursor", 0)
        flagModel.cursor = i("flagCursor", 0)
        if (!keepHeld) {                 // a held article keeps its own positions (they are what the record says)
            pendingComicTop = i("comicTop", 0).takeIf { it > 0 }
            pendingCommentsTop = i("commentsTop", 0).takeIf { it > 0 }
        }
        bingeSource = s("bingeSource")
        images = b("images", true)
        // the source cursor by identity, once the sources are known
        if (restoredSrc != null) srcModel.cursor = srcRows().indexOfFirst { (it as? SRow.Src)?.s?.id == restoredSrc }.coerceAtLeast(0)
        if (b("srcMenu", false)) srcModel.cursor = maxOf(0, srcRows().size - 1)
        // an open item is re-resolved when its list loads (a restored level
        // below the top loads on the way back — §25 #7)
        if (!keepHeld && itemId != null && itemSrc != null && (level == Level_.ARTICLE || level == Level_.COMIC || level == Level_.ACTIONS || level == Level_.COMMENTS)) {
            openSource = openSource ?: itemSrc
            openItem = Item(itemId, itemSrc, "", "", kind = if (level == Level_.COMIC) ItemKind.COMIC else ItemKind.LINK)
            pendingOpenItemId = itemId
            level = Level_.ITEMS       // re-opened through the list once it lands (and then the restored level is not lost: see loadItems)
            restoredLevel = s("level")?.let { n -> Level_.entries.firstOrNull { it.name == n } }
        }
    }

    /** A restore that lands on ARTICLE/COMIC/ACTIONS/COMMENTS: the list
     *  loads first, the item re-opens, then this level is restored. */
    private var restoredLevel: Level_? = null

    /** P7 item 5 (OS-3), step 2: every explicit navigation (`goRoot`, `back`, `openItems`, `openOne`) ends a re-open
     *  the restore armed — its one consumer, a list load, otherwise opened the old article by itself later. */
    private fun clearPendingOpen() { pendingOpenItemId = null; restoredLevel = null }

    // last in the class: every field `fromSource` reaches is initialised by now (a restart's record holds the places)
    init {
        fromSource(source.state())
        source.addListener(onSource)
    }

    companion object {
        const val PAGE = 50
        const val PEEK = 60
        const val RECENTS = 5
        const val READ_CAP = 600
        const val STRIP_H = Strips.STRIP_H
        const val BAR_H = 32
        const val LOADED_CAP = 6
        const val RETRY_MS = 5_000L
        private const val ANCHOR = 0
        private const val BACK = 1
        private const val FRONT = 2
        val FETCHES = linkedMapOf("5 min" to 5, "15 min" to 15, "30 min" to 30, "60 min" to 60)
        val KEEPS = linkedMapOf("7 d" to 7, "30 d" to 30, "90 d" to 90)
    }
}
