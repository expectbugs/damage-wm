package wm.damage.core.windows.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wm.damage.core.geom.Geometry
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
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.text.Wrap
import wm.damage.core.util.Log

/**
 * MUSIC — `MUSIC.md` (design settled with Adam 2026-09-02, 29 verdicts):
 * the G2CC music system taken over whole, played on the PHONE, on glass.
 *
 *   QUEUE (List, root; the lens is the Now Playing CARD) ──tap row──▶ row MENU
 *      │ wrap-end row = the Music MENU (§8.2)
 *      ├─ BROWSE → ARTISTS → ARTIST (albums + all) → tracks · ALBUMS → ALBUM ·
 *      │   MOODS & GENRES → term · PLAYLISTS → PLAYLIST · COLLECTIONS (folders) ·
 *      │   RECENT · SEARCH (keyboard) → RESULTS · YOUTUBE (keyboard) → results → grab
 *      ├─ LYRICS (canvas: the scheduler advances the line; scroll nudges the
 *      │   per-output offset) · SEEK (list) · VOLUME (canvas, live) · TRACK INFO (doc)
 *      └─ MUSIC MODE (exclusive, §8.3) ── double-tap ──▶ QUEUE
 *
 * Playback truth lives in the [player] (the phone's, or the desktop's
 * mirror of its synced record); library truth in the [library] (local on
 * the PC, the channel + caches on the phone). Every provider/player call is
 * off-loop; every completion applies through `runOnShell` behind a sequence
 * guard; the queue cursor follows its row's IDENTITY (`qid`).
 */
class MusicWindow(
    private val text: TextRasterizer,
    private val library: MusicLibrary,
    private val player: MusicPlayer,
    private val bg: CoroutineScope,
    /** The desktop mirror: needs the host, cannot play. */
    private val mirror: Boolean = false,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DamageWindow("music", "Music", IconKind.MUSIC) {

    private val tx = styledText(text)

    private enum class Kind { QUEUE, BROWSE, ARTISTS, ARTIST, ALBUMS, ALBUM, VOCAB, TERM, PLAYLISTS, PLAYLIST, FOLDER, RECENT, RESULTS, YT, INFO, LYRICS, SEEK, VOLUME }

    /** One level on the back stack. Rows derive from the catalog (cheap,
     *  cached per catalog version + player state) or from an async load. */
    private inner class Frame(val kind: Kind, val key: String = "") {
        val model = ListModel()
        val doc = DocModel()
        var rowsCache: List<Row>? = null
        var rowsKey: Any? = null
        var tracks: List<TrackRef>? = null
        var yt: List<YtResult>? = null
        var loading = false
        var error = ""
        var seq = 0
        var edit = false                        // PLAYLIST: edit mode (reorder/remove)
        var pendingCursor: Int? = null
        var pendingQid: Long? = null
        var pendingTop: Int? = null
        var lines: List<DocLine>? = null        // INFO
    }

    private sealed class Row {
        class Entry(val e: QueueEntry, val idx: Int) : Row()
        class Track(val t: TrackRef, val pos: Int = 0, val qid: Long = 0) : Row()
        class Text(val label: String, val detail: String = "", val icons: List<String> = emptyList(), val lensLine: String = "", val act: () -> Unit) : Row()
        class ArtistRow(val a: Artist) : Row()
        class AlbumRow(val a: Album) : Row()
        class TermRow(val v: VocabTerm) : Row()
        class PlaylistRow(val p: Playlist) : Row()
        class FolderRow(val name: String, val path: String, val count: Int) : Row()
        class YtRow(val r: YtResult) : Row()
        object Menu : Row()
        object Empty : Row()
        object Loading : Row()
    }

    private class DocLine(val s: String, val f: FontSpec, val lv: Int, val indent: Int = 0)

    private val stack = ArrayList<Frame>().apply { add(Frame(Kind.QUEUE)) }
    private val top: Frame get() = stack.last()
    private val root: Frame get() = stack.first()

    private var services: ShellServices? = null
    private var registered = false
    private var active = false
    private var exclusive = false
    private var st: PlayerState = PlayerState()
    private var stateLine = ""
    private var catalogVersion = ""
    private var notice: String? = null
    private var noticeUntil = 0L
    private var opBusy = false
    private var needsReload = false

    // drafts (kept on cancel — verdict: the keyboard keeps its draft)
    private var askDraft = ""
    private var searchDraft = ""
    private var ytDraft = ""
    private var nameDraft = ""
    private var lyricsDraft = ""

    // settings (window-owned; the player owns volume/boost/hold/output/profile/sleep/prefetch/fallback)
    private var notifyTrack = true
    private var notifyQueueEnd = true
    private var notifyRoute = true
    private var notifyPc = false
    private var notifyYt = true
    private var notifyPlaylist = false
    private var defaultMode = Mode.SHUFFLE
    private var lyricsOffsets = HashMap<String, Int>()     // per output id, ms
    private var lyricsSources = "lrclib+local"
    private var vizName = "Off"
    private var vizRate = 8
    private var mmCard = true
    private var mmLyrics = true
    private var mmViz = false
    private var mmPeek = false
    private var mmClock = true
    private var mmLink = true
    private var heightPref: Int? = null
    private var normalization = true
    private var channels = 1
    private var quality = AudioProfile.Quality.HIGH

    // art + lyrics caches (window-side, per track id)
    private val artCache = HashMap<Int, Gray8?>()
    private val artPending = HashSet<Int>()
    private var lyricsFor = -1
    private var lyricsParsed: LyricsSync.Parsed? = null
    private var lyricsPlain: List<List<String>>? = null
    private var lyricsPlainPage = 0
    private var lyricsState = ""                       // "" · "loading" · "none" · error
    private var lyricsSeq = 0
    private var lyricsGen = 0
    private var lyricsLineShown = -2

    private val ytJobs = LinkedHashMap<String, YtJob>()
    private var pendingGrabOffer: YtJob? = null

    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)
    private val fBody = FontSpec(Face.SYSTEM, 15)
    private val fHead = FontSpec(Face.SYSTEM, 18, bold = true)
    private val fLyric = FontSpec(Face.SYSTEM, 22)
    private val fLyricDim = FontSpec(Face.SYSTEM, 18)
    private val fBig = FontSpec(Face.SYSTEM, 36, bold = true)

    override val needs: Set<Need> = if (mirror) setOf(Need.HOST) else emptySet()
    override val preferredHeight: Int? get() = heightPref ?: 480

    // ================================================================ small helpers
    private fun onShell(action: () -> Unit) { services?.runOnShell(action) ?: action() }
    private fun dn(s: String, f: FontSpec = fRow): String = Draw.dynamic(tx, s, f)
    private fun setNotice(s: String) {
        notice = s
        noticeUntil = clock() + 4_000
        services?.requestRender(this)
    }
    private fun render() { services?.requestRender(this) }
    private val cat: Catalog get() = library.catalog()
    private fun profile(): AudioProfile = AudioProfile(quality, channels, normalization)
    private fun outputKey(): String = st.output.ifEmpty { Output.AUTO }
    private fun lyricsOffset(): Int = lyricsOffsets[outputKey()] ?: 0

    // ================================================================ provider + player glue
    private val libListener = object : MusicLibrary.Listener {
        override fun catalogChanged(c: Catalog) { onShell { catalogVersion = c.version; invalidateRows(); render() } }
        override fun ytJob(j: YtJob) { onShell { handleYtJob(j) } }
        override fun state(line: String) { onShell { if (stateLine != line) { stateLine = line; render() } } }
    }

    private val playerListener = object : MusicPlayer.Listener {
        override fun state(s: PlayerState) { onShell { applyState(s) } }
        override fun tick(posMs: Long) { onShell { onTick(posMs) } }
        override fun event(e: PlayerEvent) { onShell { handleEvent(e) } }
    }

    private var lastCardRepaint = 0L

    private fun applyState(s: PlayerState) {
        val before = st
        st = s
        // the queue cursor follows its row's IDENTITY across a reorder
        val r = root
        val rowsBefore = r.rowsCache
        val onQid = (rowsBefore?.getOrNull(r.model.cursor) as? Row.Entry)?.e?.qid
        val onMenu = rowsBefore != null && r.model.cursor == rowsBefore.size - 1 && rowsBefore.size > 1
        invalidateRows()
        val after = rows(r)
        val want = r.pendingQid
        r.pendingQid = null
        val trackChanged = before.entry?.qid != s.entry?.qid
        val userMoved = userMovedCursor
        val rest = when {
            want != null && after.any { (it as? Row.Entry)?.e?.qid == want } -> after.indexOfFirst { (it as? Row.Entry)?.e?.qid == want }
            r.pendingCursor != null -> r.pendingCursor!!.coerceIn(0, maxOf(0, after.size - 1)).also { r.pendingCursor = null }
            onMenu -> after.size - 1
            trackChanged && !userMoved -> currentRowIndex(after)      // the cursor RESTS on the current entry (§8.1)
            onQid != null -> after.indexOfFirst { (it as? Row.Entry)?.e?.qid == onQid }.takeIf { it >= 0 } ?: r.model.cursor.coerceIn(0, maxOf(0, after.size - 1))
            else -> r.model.cursor.coerceIn(0, maxOf(0, after.size - 1))
        }
        if (userMoved && !(trackChanged && !userMoved)) r.model.cursor = rest else setRootCursor(rest)
        if (trackChanged) { lyricsGen++; if (top.kind == Kind.LYRICS) loadLyrics() }
        if (s.problem.isNotEmpty() && s.problem != before.problem) setNotice(s.problem)
        if (active || exclusive) render()
    }

    /** The queue cursor the WINDOW last set: a cursor elsewhere means the
     *  user scrolled away, and a track change must not yank it back under
     *  them (it rests on the current entry on ENTRY, §8.1). */
    private var cursorSetByMe = -1
    private val userMovedCursor: Boolean get() = root.model.cursor != cursorSetByMe
    private fun setRootCursor(i: Int) { root.model.cursor = i; cursorSetByMe = i }

    private fun currentRowIndex(rows: List<Row>): Int = rows.indexOfFirst { (it as? Row.Entry)?.idx == st.index }.coerceAtLeast(0)

    private fun onTick(posMs: Long) {
        // the card repaints on a coarse pace (5 s while focused — a lens
        // repaint is a few hundred bytes; 1 Hz would be a 10 % link duty)
        val now = clock()
        if (top.kind == Kind.QUEUE && active && now - lastCardRepaint >= CARD_PACE_MS) { lastCardRepaint = now; render() }
        if (top.kind == Kind.SEEK && active) render()
        if (top.kind == Kind.LYRICS) scheduleLyricFlush()
        if (exclusive) exclusiveTick(posMs)
    }

    private fun handleEvent(e: PlayerEvent) {
        when (e) {
            is PlayerEvent.TrackChange -> {
                lastCardRepaint = clock()
                // the card shows the change while the window is on screen; the notice is for everywhere else
                if (notifyTrack && !active && !exclusive) {
                    dirty = true
                    services?.notifyInternal("music", "playing · ${dn(Fmt.titleArtist(e.track), fBody)}", appId = id, thread = "track", target = "t:${e.track.id}")
                }
            }
            PlayerEvent.QueueEnd -> if (notifyQueueEnd) { dirty = true; services?.notifyInternal("music", "queue ended", appId = id, thread = "queue") }
            is PlayerEvent.RouteLost -> if (notifyRoute) { dirty = true; services?.notifyInternal("music", "paused — ${dn(e.detail, fBody)}", appId = id, thread = "route") }
            is PlayerEvent.Error -> { setNotice(dn(e.detail, fSmall)); if (!active && !exclusive) services?.notifyInternal("music", dn(e.detail, fBody), appId = id, thread = "error") }
            is PlayerEvent.LimiterUndone -> setNotice("phone lowered the volume — restored to ${e.restoredTo}%")
            is PlayerEvent.LimiterKeeps -> services?.notifyInternal("music", dn(e.detail, fBody), appId = id, thread = "limiter")
            PlayerEvent.BoostOff -> setNotice("boost off")
            is PlayerEvent.BoostLoud -> setNotice("max volume and boost ${e.boost}% — loud")
            is PlayerEvent.SleepEnded -> services?.notifyInternal("music", dn(e.detail, fBody), appId = id, thread = "sleep")
            is PlayerEvent.BackendChanged -> if (e.automatic) services?.notifyInternal("music", "PC unreachable — switched to Spotify", appId = id, thread = "backend")
                else setNotice(if (e.backend == Backend.SPOTIFY) "Spotify on the phone" else "back to the PC library")
            is PlayerEvent.PcUnreachable -> if (notifyPc) services?.notifyInternal("music", "PC unreachable", appId = id, thread = "pc")
        }
        if (active) render()
    }

    private fun handleYtJob(j: YtJob) {
        ytJobs[j.job] = j
        while (ytJobs.size > 20) ytJobs.remove(ytJobs.keys.first())
        when {
            j.done -> {
                if (notifyYt) { dirty = true; services?.notifyInternal("music", "added · ${dn(j.title, fBody)}", appId = id, thread = "yt:${j.job}", target = "t:${j.trackId}") }
                pendingGrabOffer = j
                offerGrabbed()
            }
            j.failed -> { if (notifyYt) { dirty = true; services?.notifyInternal("music", "YouTube grab failed · ${dn(j.error, fBody)}", appId = id, thread = "yt:${j.job}") } }
            else -> setNotice("${j.phase}${if (j.percent in 1..99) " ${j.percent}%" else ""} · ${dn(j.title.ifEmpty { "YouTube" }, fSmall)}")
        }
    }

    /** The new track is offered Play now / Play next (§8.1) — as a menu when
     *  this window is on screen, else it waits for the next activation. */
    private fun offerGrabbed() {
        val j = pendingGrabOffer ?: return
        if (!active || exclusive) return
        val t = cat.track(j.trackId)?.ref() ?: TrackRef(j.trackId, j.title)
        val shown = services?.openMenu(MenuSurface.Spec("added '${j.title}'", listOf(
            MenuSurface.Item("Later"), MenuSurface.Item("Play now"), MenuSurface.Item("Play next"), MenuSurface.Item("Append")),
            onCommit = { i -> when (i) { 1 -> player.playQueue(listOf(t), 0, Mode.QUEUE, j.title); 2 -> player.playNext(listOf(t)); 3 -> player.append(listOf(t)) } }),
            owner = this) == true
        if (shown) pendingGrabOffer = null
    }

    fun detach() {
        active = false
        registered = false
        library.removeListener(libListener)
        player.removeListener(playerListener)
        player.setFocused(false)
    }

    override fun onRegistered(ctx: ShellServices) {
        services = ctx
        if (!registered) {
            registered = true
            library.addListener(libListener)
            player.addListener(playerListener)
        }
        st = player.state
        catalogVersion = cat.version
    }

    override fun onActivate(ctx: ShellServices) {
        services = ctx
        active = true
        player.setFocused(true)
        library.setFocused(true, CARD_PACE_MS)
        st = player.state
        invalidateRows()
        if (top.kind == Kind.QUEUE) setRootCursor(root.pendingCursor ?: currentRowIndex(rows(root)))
        bg.launch(Dispatchers.IO) { try { library.refreshCatalog() } catch (e: Exception) { Log.w("music", "catalog refresh: ${e.message}") } }
        if (needsReload) { needsReload = false; reloadTop() }
        if (top.kind == Kind.LYRICS) loadLyrics()
        offerGrabbed()
    }

    override fun onDeactivate() {
        active = false
        player.setFocused(false)
        library.setFocused(false, 0)
    }

    private fun reloadTop() {
        val f = top
        when (f.kind) {
            Kind.PLAYLIST, Kind.RESULTS, Kind.YT -> if (f.tracks == null && f.yt == null) load(f)
            Kind.LYRICS -> loadLyrics()
            else -> {}
        }
    }

    // ================================================================ contract
    override fun view(): WindowView {
        val f = top
        return when (f.kind) {
            Kind.INFO -> WindowView.DocView(f.doc, { infoLines(f).size }, lineH(fBody), { g, i, r -> infoLines(f).getOrNull(i)?.let { paintDocLine(g, it, r) } },
                { openTrackMenu(cat.track(f.key.toIntOrNull() ?: -1)?.ref() ?: TrackRef(f.key.toIntOrNull() ?: 0, f.key)) }, stepLines = { 5 })
            Kind.LYRICS -> { demandLyrics(); WindowView.CanvasView(::paintLyrics, onScroll = ::nudgeLyrics, onTap = ::openLyricsMenu) }
            Kind.VOLUME -> WindowView.CanvasView(::paintVolume, onScroll = { d -> player.setVolume(st.volume + d * 5, "ring"); render() }, onTap = { back(); render() })
            else -> {
                demandArt()
                WindowView.ListView(f.model, { rows(f).size }, { g, i, r, dim -> paintRow(f, g, i, r) }, { g, r, i -> paintLens(f, g, r, i) }, { i -> commit(f, i) })
            }
        }
    }

    override fun title(): String {
        val n = notice
        if (n != null && clock() < noticeUntil) return n
        val f = top
        return when (f.kind) {
            Kind.QUEUE -> if (st.queue.isEmpty()) "music" else "queue ${st.index + 1}/${st.queue.size}"
            Kind.BROWSE -> "browse"
            Kind.ARTISTS -> "artists"
            Kind.ARTIST -> dn(f.key, fSmall)
            Kind.ALBUMS -> "albums"
            Kind.ALBUM -> dn(f.key, fSmall)
            Kind.VOCAB -> "moods & genres"
            Kind.TERM -> dn(f.key, fSmall)
            Kind.PLAYLISTS -> "playlists"
            Kind.PLAYLIST -> dn((cat.playlists.firstOrNull { it.id == f.key.toIntOrNull() }?.name ?: "playlist") + (if (f.edit) " · edit" else ""), fSmall)
            Kind.FOLDER -> if (f.key.isEmpty()) "collections" else dn(f.key.substringAfterLast('/'), fSmall)
            Kind.RECENT -> "recent"
            Kind.RESULTS -> "\"${dn(f.key, fSmall)}\""
            Kind.YT -> "youtube"
            Kind.INFO -> "track"
            Kind.LYRICS -> "lyrics"
            Kind.SEEK -> "seek"
            Kind.VOLUME -> "volume"
        }
    }

    override fun summary(): Summary {
        val s = st
        val t = s.track
        val line = when {
            stateLine.isNotEmpty() && s.play != PlayState.PLAYING -> stateLine
            s.backend == Backend.SPOTIFY -> "Spotify · phone" + (s.spotify?.title?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
            mirror && s.queue.isEmpty() && s.problem.isNotEmpty() -> "player: phone needed"
            s.play == PlayState.PLAYING && t != null -> "playing · ${Fmt.titleArtist(t)}"
            s.play == PlayState.PAUSED && t != null -> "paused · ${t.title}"
            s.queue.isNotEmpty() -> "${s.queue.size} queued · staged"
            else -> "idle"
        }
        val detail = when {
            t == null -> stateLine
            else -> listOf(t.album, "q ${s.index + 1}/${s.queue.size}", s.mode.label).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val progress = if (s.play != PlayState.STOPPED && s.durMs > 0) (player.positionMs().toDouble() / s.durMs).coerceIn(0.0, 1.0) else null
        return Summary(dn(line, fBody), detail = dn(detail, fSmall), more = s.queue.isNotEmpty(), progress = progress)
    }

    override fun levelDepth(): Int = stack.size

    override fun back(): Boolean {
        if (stack.size <= 1) return false
        val f = stack.removeAt(stack.size - 1)
        f.seq++
        if (f.loading) services?.setOperation("idle")
        if (f.kind == Kind.LYRICS) lyricsGen++
        if (top.kind == Kind.QUEUE) { root.model.cursor = root.model.cursor.coerceIn(0, maxOf(0, rows(root).size - 1)) }
        return true
    }

    override fun onLayoutChanged() { for (f in stack) f.lines = null; lyricsLineShown = -2 }
    override fun onFontScaleChanged(scale: Double) { onLayoutChanged() }

    /** A replica-typed line = an Ask, staged behind a confirm (typed text
     *  always stages; the keyboard's own Enter IS the confirm on glass). */
    override fun onTypedText(line: String): Boolean {
        val q = line.trim()
        if (q.isEmpty()) return false
        val shown = services?.openMenu(MenuSurface.Spec("ask: $q", listOf(MenuSurface.Item("Cancel"), MenuSurface.Item("Ask", "plays the answer")),
            onCommit = { i -> if (i == 1) runAsk(q) }), owner = this) == true
        if (!shown) setNotice("typed line not delivered — open Music first")
        return shown
    }

    /** §16.1: `t:<id>` (the queue row if queued, else Track info) · `pl:<id>` · `mode:music` · `yt:<job>`. */
    override fun open(target: String): Boolean {
        when {
            target.startsWith("t:") -> {
                // resolve BEFORE touching the stack: a refused target leaves the
                // levels as they were (the half-open-keyboard lesson)
                val tid = target.removePrefix("t:").toIntOrNull() ?: return false
                val i = rows(root).indexOfFirst { (it as? Row.Entry)?.e?.track?.id == tid }
                if (i < 0 && cat.track(tid) == null && st.queue.none { it.track.id == tid }) return false
                for (f in stack.drop(1)) f.seq++
                stack.subList(1, stack.size).clear()
                if (i >= 0) { root.model.cursor = i; cursorSetByMe = -1; return true }   // a deliberate jump: a later track change leaves it
                push(Frame(Kind.INFO, "$tid"))
                return true
            }
            target.startsWith("pl:") -> {
                val pid = target.removePrefix("pl:").toIntOrNull() ?: return false
                if (cat.playlists.none { it.id == pid }) return false
                for (f in stack.drop(1)) f.seq++
                stack.subList(1, stack.size).clear()
                push(Frame(Kind.BROWSE)); push(Frame(Kind.PLAYLISTS)); openPlaylist(pid)
                return true
            }
            target == "mode:music" -> return enterMusicMode()
            target.startsWith("yt:") -> {
                val j = ytJobs[target.removePrefix("yt:")] ?: return false
                if (j.done && j.trackId > 0) return open("t:${j.trackId}")
                setNotice("${j.phase} · ${dn(j.title, fSmall)}")
                return true
            }
        }
        return false
    }

    // ================================================================ frames + rows
    private fun push(f: Frame) {
        stack.add(f)
        f.model.cursor = when (f.kind) {
            Kind.SEEK -> 3          // "+10 s": a harmless rest (§1.7)
            else -> 0
        }
        if (f.kind == Kind.PLAYLIST || f.kind == Kind.RESULTS || f.kind == Kind.YT) load(f)
        if (f.kind == Kind.LYRICS) { lyricsGen++; loadLyrics() }
    }

    private fun invalidateRows() { for (f in stack) f.rowsCache = null }

    private fun rowsKey(f: Frame): Any = listOf(catalogVersion, f.kind, f.key, f.edit, f.loading, f.error, f.tracks?.size, f.yt?.size,
        if (f.kind == Kind.QUEUE) st.queue.size * 31 + st.index else 0, if (f.kind == Kind.QUEUE) st.queue.hashCode() else 0)

    private fun rows(f: Frame): List<Row> {
        val k = rowsKey(f)
        val c = f.rowsCache
        if (c != null && f.rowsKey == k) return c
        val out = buildRows(f)
        f.rowsCache = out
        f.rowsKey = k
        return out
    }

    private fun tracksOf(f: Frame): List<TrackRef> = when (f.kind) {
        Kind.ARTIST -> cat.tracks.filter { it.artist.equals(f.key, true) }.sortedWith(compareBy({ it.album.lowercase() }, { it.discNo }, { it.trackNo }, { it.title.lowercase() })).map { it.ref() }
        Kind.ALBUM -> cat.tracks.filter { it.album.equals(f.key, true) }.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title.lowercase() })).map { it.ref() }
        Kind.TERM -> cat.tracks.filter { t -> t.genres.any { it.equals(f.key, true) } || t.moods.any { it.equals(f.key, true) } || t.styles.any { it.equals(f.key, true) } }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.discNo }, { it.trackNo })).map { it.ref() }
        Kind.FOLDER -> cat.tracks.filter { it.folder == f.key }.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title.lowercase() })).map { it.ref() }
        Kind.RECENT -> cat.recent.mapNotNull { cat.track(it)?.ref() }
        Kind.PLAYLIST, Kind.RESULTS -> f.tracks ?: emptyList()
        else -> emptyList()
    }

    private fun buildRows(f: Frame): List<Row> {
        val out = ArrayList<Row>()
        when (f.kind) {
            Kind.QUEUE -> {
                if (st.queue.isEmpty()) out.add(Row.Empty)
                else st.queue.forEachIndexed { i, e -> out.add(Row.Entry(e, i)) }
                out.add(Row.Menu)
            }
            Kind.BROWSE -> {
                out.add(Row.Text("Artists", "${cat.artists.size}", listOf("system-users", "avatar-default"), "every artist, alphabetical") { push(Frame(Kind.ARTISTS)) })
                out.add(Row.Text("Albums", "${cat.albums.size}", listOf("media-optical", "audio-x-generic"), "every album") { push(Frame(Kind.ALBUMS)) })
                out.add(Row.Text("Moods & genres", "${cat.vocab.size}", listOf("emblem-favorite", "tag"), "the library's own words") { push(Frame(Kind.VOCAB)) })
                out.add(Row.Text("Playlists", "${cat.playlists.size}", listOf("audio-x-playlist", "view-list-details"), "manual and adaptive") { push(Frame(Kind.PLAYLISTS)) })
                out.add(Row.Text("Collections", "", listOf("folder-music", "folder"), "the library's folders") { push(Frame(Kind.FOLDER, "")) })
                out.add(Row.Text("Recent", "${cat.recent.size}", listOf("document-open-recent", "appointment-soon"), "what played lately") { push(Frame(Kind.RECENT)) })
                out.add(Row.Text("Search", "keyboard", listOf("edit-find", "system-search"), "titles, artists, albums") { openSearch() })
                out.add(Row.Text("YouTube", "keyboard", listOf("youtube", "video-x-generic"), "search, pick, grab into the library") { openYtSearch() })
                out.add(Row.Menu)
            }
            Kind.ARTISTS -> { cat.artists.forEach { out.add(Row.ArtistRow(it)) }; out.add(Row.Menu) }
            Kind.ARTIST -> {
                val all = tracksOf(f)
                out.add(Row.Text("All tracks", "${all.size}", listOf("audio-x-generic"), "the whole artist") { openTrackSetMenu(all, f.key) })
                cat.albums.filter { a -> cat.tracks.any { it.album.equals(a.name, true) && it.artist.equals(f.key, true) } }.forEach { out.add(Row.AlbumRow(it)) }
                out.add(Row.Menu)
            }
            Kind.ALBUMS -> { cat.albums.forEach { out.add(Row.AlbumRow(it)) }; out.add(Row.Menu) }
            Kind.VOCAB -> { cat.vocab.forEach { out.add(Row.TermRow(it)) }; out.add(Row.Menu) }
            Kind.PLAYLISTS -> {
                cat.playlists.forEach { out.add(Row.PlaylistRow(it)) }
                if (cat.playlists.isEmpty()) out.add(Row.Text("no playlists yet", "", listOf("audio-x-playlist"), "save the queue from the menu") { openMenu() })
                out.add(Row.Menu)
            }
            Kind.PLAYLIST -> {
                val ts = f.tracks
                when {
                    f.loading && ts == null -> out.add(Row.Loading)
                    f.error.isNotEmpty() && ts == null -> out.add(Row.Text("failed: ${f.error}", "tap to retry", emptyList(), "the playlist could not be read") { load(f) })
                    ts != null -> {
                        if (ts.isNotEmpty()) out.add(Row.Text("Play at random", "${ts.size}", listOf("media-playlist-shuffle", "audio-x-generic"), "the house shuffle") { playSet(ts, Mode.SHUFFLE, playlistName(f)) })
                        ts.forEachIndexed { i, t -> out.add(Row.Track(t, i + 1)) }
                        if (ts.isEmpty()) out.add(Row.Text("empty playlist", "", emptyList(), "add the current track from the menu") { openPlaylistMenu(f) })
                    }
                }
                out.add(Row.Menu)
            }
            Kind.FOLDER -> {
                val prefix = if (f.key.isEmpty()) "" else f.key + "/"
                val subs = LinkedHashMap<String, Int>()
                for (t in cat.tracks) {
                    if (f.key.isEmpty() && t.folder.isEmpty()) continue
                    if (!t.folder.startsWith(prefix) || t.folder == f.key) continue
                    val sub = t.folder.removePrefix(prefix).substringBefore('/')
                    subs[sub] = (subs[sub] ?: 0) + 1
                }
                subs.entries.sortedBy { it.key.lowercase() }.forEach { out.add(Row.FolderRow(it.key, if (prefix.isEmpty()) it.key else prefix + it.key, it.value)) }
                tracksOf(f).forEach { out.add(Row.Track(it)) }
                if (out.isEmpty()) out.add(Row.Text("empty folder", "", emptyList(), "") {})
                out.add(Row.Menu)
            }
            Kind.RECENT -> {}
            Kind.TERM -> { tracksOf(f).forEach { out.add(Row.Track(it)) }; out.add(Row.Menu) }
            Kind.ALBUM -> { tracksOf(f).forEach { out.add(Row.Track(it, it.let { tr -> cat.track(tr.id)?.trackNo ?: 0 })) }; out.add(Row.Menu) }
            Kind.RESULTS -> {
                val ts = f.tracks
                when {
                    f.loading && ts == null -> out.add(Row.Loading)
                    f.error.isNotEmpty() && ts == null -> out.add(Row.Text("failed: ${f.error}", "tap to retry", emptyList(), "") { load(f) })
                    ts != null -> {
                        ts.forEach { out.add(Row.Track(it)) }
                        if (ts.isEmpty()) out.add(Row.Text("no results", "", emptyList(), "nothing in the library matched") {})
                        out.add(Row.Text("Search YouTube for this", "", listOf("youtube", "video-x-generic"), "grab it into the library") { ytDraft = f.key; openYtSearch(f.key) })
                    }
                }
                out.add(Row.Menu)
            }
            Kind.YT -> {
                val ys = f.yt
                when {
                    f.loading && ys == null -> out.add(Row.Loading)
                    f.error.isNotEmpty() && ys == null -> out.add(Row.Text("failed: ${f.error}", "tap to retry", emptyList(), "") { load(f) })
                    ys != null -> { ys.forEach { out.add(Row.YtRow(it)) }; if (ys.isEmpty()) out.add(Row.Text("no results", "", emptyList(), "") {}) }
                }
                out.add(Row.Menu)
            }
            Kind.SEEK -> {
                for ((label, ms) in SEEK_ROWS) out.add(Row.Text(label, "", emptyList(), "tap to seek") { if (ms == Long.MIN_VALUE) player.seekTo(0) else player.seekBy(ms); render() })
                out.add(Row.Text("Back", "", emptyList(), "") { back(); render() })
            }
            else -> {}
        }
        if (f.kind == Kind.RECENT) { tracksOf(f).forEach { out.add(Row.Track(it)) }; if (out.isEmpty()) out.add(Row.Text("nothing played yet", "", emptyList(), "") {}); out.add(Row.Menu) }
        return out
    }

    private fun playlistName(f: Frame): String = cat.playlists.firstOrNull { it.id == f.key.toIntOrNull() }?.name ?: "playlist"

    /** Async frame content (playlist rows, search results, YouTube results),
     *  seq-guarded: a late answer never replaces what the user moved on to. */
    private fun load(f: Frame) {
        val seq = ++f.seq
        f.loading = true
        f.error = ""
        f.rowsCache = null
        services?.setOperation(when (f.kind) { Kind.YT -> "youtube"; Kind.RESULTS -> "searching"; else -> "playlist" })
        bg.launch(Dispatchers.IO) {
            val res: Result<Any> = try {
                Result.success(when (f.kind) {
                    Kind.PLAYLIST -> library.playlistTracks(f.key.toInt())
                    Kind.RESULTS -> library.search(f.key)
                    Kind.YT -> library.ytSearch(f.key)
                    else -> emptyList<TrackRef>()
                })
            } catch (e: Exception) {
                Log.e("music", "${f.kind} '${f.key}' failed", e)
                Result.failure(e)
            }
            onShell {
                if (seq != f.seq) return@onShell
                services?.setOperation("idle")
                f.loading = false
                res.onSuccess { v ->
                    @Suppress("UNCHECKED_CAST")
                    if (f.kind == Kind.YT) f.yt = v as List<YtResult> else f.tracks = v as List<TrackRef>
                    f.error = ""
                    f.pendingCursor?.let { c -> f.model.cursor = c.coerceIn(0, maxOf(0, rows(f).size - 1)); f.pendingCursor = null }
                }.onFailure { e ->
                    f.error = e.message ?: "${f.kind} failed"
                    setNotice(dn(f.error, fSmall))
                }
                f.rowsCache = null
                render()
            }
        }
    }

    // ================================================================ painting
    private fun rowLabel(r: Row): Pair<String, String> = when (r) {
        is Row.Entry -> Fmt.titleArtist(r.e.track) to Fmt.mmss(r.e.track.durMs.toLong())
        is Row.Track -> (if (r.pos > 0) "${r.pos}. " else "") + Fmt.titleArtist(r.t) to Fmt.mmss(r.t.durMs.toLong())
        is Row.Text -> r.label to r.detail
        is Row.ArtistRow -> r.a.name to "${r.a.tracks}"
        is Row.AlbumRow -> (if (r.a.artist.isNotEmpty()) "${r.a.name} — ${r.a.artist}" else r.a.name) to "${r.a.tracks}"
        is Row.TermRow -> r.v.term to "${r.v.count}"
        is Row.PlaylistRow -> r.p.name to "${r.p.count}${if (r.p.adaptive) " adaptive" else ""}"
        is Row.FolderRow -> r.name to "${r.count}"
        is Row.YtRow -> r.r.title to Fmt.mmss(r.r.durS * 1000L)
        Row.Menu -> "Music" to ""
        Row.Empty -> "Nothing queued — tap for Browse" to ""
        Row.Loading -> "loading" to ""
    }

    private fun rowIcons(r: Row): List<String> = when (r) {
        is Row.Entry, is Row.Track -> listOf("audio-x-generic", "multimedia-audio-player")
        is Row.Text -> r.icons
        is Row.ArtistRow -> listOf("system-users", "avatar-default")
        is Row.AlbumRow -> listOf("media-optical", "audio-x-generic")
        is Row.TermRow -> listOf("emblem-favorite", "tag")
        is Row.PlaylistRow -> listOf("audio-x-playlist", "view-list-details")
        is Row.FolderRow -> listOf("folder-music", "folder")
        is Row.YtRow -> listOf("youtube", "video-x-generic")
        else -> IconNames.forKind(IconKind.MUSIC)
    }

    /** The drawn transport glyphs (UI symbols are drawn, never typed). */
    private fun playGlyph(g: Gray8, x: Int, y: Int, lv: Int) = g.fillPolygon(intArrayOf(x, x + 10, x), intArrayOf(y, y + 5, y + 10), lv)
    private fun pauseGlyph(g: Gray8, x: Int, y: Int, lv: Int) { g.fillRect(x, y, 4, 10, lv); g.fillRect(x + 6, y, 4, 10, lv) }
    private fun stopGlyph(g: Gray8, x: Int, y: Int, lv: Int) = g.fillRect(x, y, 10, 10, lv)
    private fun stateGlyph(g: Gray8, x: Int, y: Int, lv: Int) = when (st.play) {
        PlayState.PLAYING -> playGlyph(g, x, y, lv)
        PlayState.PAUSED -> pauseGlyph(g, x, y, lv)
        PlayState.STOPPED -> stopGlyph(g, x, y, lv)
    }

    private fun paintRow(f: Frame, g: Gray8, i: Int, r: Rect) {
        val row = rows(f).getOrNull(i) ?: return
        val (label, detail) = rowLabel(row)
        val lv = when (row) {
            Row.Menu, Row.Loading -> Level.DIM
            is Row.Entry -> if (row.idx == st.index) Level.HEAD else Level.BODY
            Row.Empty -> Level.DIM
            else -> Level.BODY
        }
        if (row is Row.Entry && row.idx == st.index) stateGlyph(g, r.x + 12, r.y + 11, Level.MID)
        else IconPaint.draw(g, services?.icons(), rowIcons(row), r.x + 8, r.y + 6, 20, IconKind.MUSIC, Level.DIM)
        val dw = if (detail.isEmpty()) 0 else tx.measure(dn(detail, fSmall), fSmall) + 16
        Draw.fit(g, tx, r.x + 40, r.y + 5, dn(label), lv, fRow, r.w - 64 - dw)
        if (detail.isNotEmpty()) Draw.right(g, tx, r.right - 24, r.y + 8, dn(detail, fSmall), Level.DIM, fSmall)
        if (row === Row.Menu || row is Row.FolderRow || row is Row.ArtistRow || row is Row.AlbumRow || row is Row.TermRow || row is Row.PlaylistRow) {
            if (detail.isEmpty()) Icons.tri(g, r.right - 36, r.y + 10, 11, Level.DIM)
        }
    }

    private fun paintLens(f: Frame, g: Gray8, r: Rect, i: Int) {
        val row = rows(f).getOrNull(i) ?: return
        if (row is Row.Entry) { paintCard(g, r, row.e, row.idx); return }
        if (row is Row.Track && st.entry?.track?.id == row.t.id && st.queue.isNotEmpty()) {
            val e = st.entry!!; paintCard(g, r, e, st.index); return
        }
        val (label, detail) = rowLabel(row)
        IconPaint.draw(g, services?.icons(), rowIcons(row), r.x + 8, r.y + 4, 56, IconKind.MUSIC, Level.HEAD)
        Draw.fit(g, tx, r.x + 72, r.y + 6, dn(label, fHead), Level.HEAD, fHead, r.w - 88)
        val line2 = when (row) {
            is Row.Track -> listOf(row.t.album.ifEmpty { row.t.artist }, Fmt.mmss(row.t.durMs.toLong()), lensExtra(row.t)).filter { it.isNotEmpty() }.joinToString(" · ")
            is Row.Text -> row.lensLine.ifEmpty { detail }
            is Row.ArtistRow -> "${row.a.tracks} tracks · ${row.a.albums} albums"
            is Row.AlbumRow -> listOf(row.a.artist, "${row.a.tracks} tracks", if (row.a.year > 0) "${row.a.year}" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            is Row.TermRow -> "${row.v.kind} · ${row.v.count} tracks"
            is Row.PlaylistRow -> "${row.p.count} tracks · ${if (row.p.adaptive) "adaptive (rule-managed)" else row.p.origin}"
            is Row.FolderRow -> "${row.count} tracks inside"
            is Row.YtRow -> "${row.r.channel} · ${Fmt.mmss(row.r.durS * 1000L)} · tap to grab"
            Row.Menu -> menuLensLine()
            Row.Empty -> dn(stateLine, fBody).ifEmpty { "Ask, Browse or a bud tap starts it" }
            Row.Loading -> "asking the host"
            else -> ""
        }
        Draw.fit(g, tx, r.x + 72, r.y + 32, dn(line2, fBody), Level.BODY, fBody, r.w - 88)
    }

    private fun lensExtra(t: TrackRef): String {
        val m = cat.track(t.id) ?: return ""
        return listOf(m.genres.firstOrNull() ?: "", if (m.hasLyrics) "lyrics" else "").filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun menuLensLine(): String = when {
        stateLine.isNotEmpty() -> dn(stateLine, fBody)
        st.backend == Backend.SPOTIFY -> "Spotify on the phone · tap for the menu"
        else -> "pause · next · ask · browse · lyrics · mode · music mode"
    }

    private fun linkBadge(): String = when {
        st.backend == Backend.SPOTIFY -> "Spotify"
        mirror -> if (stateLine.isEmpty()) "PC · phone plays" else dn(stateLine, fSmall)
        !st.pcLink.up -> "PC down ${Fmt.ageShort(st.pcLink.ageS(clock()))}"
        stateLine.isNotEmpty() -> dn(stateLine, fSmall)
        else -> "PC"
    }

    /** The Now Playing CARD — the lens (§8.1): art · title · artist — album ·
     *  a 12-block bar + m:ss / m:ss on the current entry (i/n + the mode on
     *  the others) · the state glyph · the link badge · the boost badge. */
    private fun paintCard(g: Gray8, r: Rect, e: QueueEntry, idx: Int) {
        val t = e.track
        val art = artCache[t.id]
        if (art != null) g.blit(art, Rect(0, 0, art.w, art.h), r.x + 4, r.y + 4)
        else IconPaint.draw(g, services?.icons(), IconNames.forKind(IconKind.MUSIC), r.x + 8, r.y + 4, 56, IconKind.MUSIC, Level.HEAD)
        val x0 = r.x + 68
        val badge = linkBadge()
        val bw = tx.measure(badge, fSmall) + 12
        Draw.fit(g, tx, x0, r.y + 4, dn(t.title, fHead), Level.HEAD, fHead, r.w - 68 - 8 - bw)
        Draw.right(g, tx, r.right - 8, r.y + 6, badge, if (st.pcLink.up && stateLine.isEmpty()) Level.DIM else Level.MID, fSmall)
        val boostBadge = if (st.boost > 100) "+${st.boost}%" else if (st.sleep.kind != Sleep.Kind.OFF) "sleep ${st.sleep.label(clock())}" else ""
        val bw2 = if (boostBadge.isEmpty()) 0 else tx.measure(boostBadge, fSmall) + 12
        Draw.fit(g, tx, x0, r.y + 26, dn(Fmt.artistAlbum(t).ifEmpty { "—" }, fBody), Level.BODY, fBody, r.w - 68 - 8 - bw2)
        if (boostBadge.isNotEmpty()) Draw.right(g, tx, r.right - 8, r.y + 28, boostBadge, Level.MID, fSmall)
        val y3 = r.y + 48
        if (idx == st.index) {
            stateGlyph(g, x0, y3, Level.MID)
            val dur = st.durMs.takeIf { it > 0 } ?: t.durMs.toLong()
            val pos = player.positionMs().coerceIn(0, maxOf(0, dur))
            Icons.blocks(g, x0 + 16, y3 + 2, 144, 6, if (dur > 0) pos.toDouble() / dur else 0.0, n = 12, level = Level.of(6))
            Draw.fit(g, tx, x0 + 172, y3 - 2, "${Fmt.mmss(pos)} / ${Fmt.mmss(dur)}", Level.DIM, fSmall, r.w - 68 - 172 - 8)
        } else {
            Draw.fit(g, tx, x0, y3 - 2, "${idx + 1}/${st.queue.size} · ${st.mode.label}${if (idx > st.index) " · up next" else ""}", Level.DIM, fSmall, r.w - 76)
        }
    }

    /** Demand art for the cursor's and the current entry — from view() on
     *  the loop, never from a paint (the L1 class). */
    private fun demandArt() {
        val want = ArrayList<Int>()
        st.entry?.track?.id?.let { want.add(it) }
        (rows(top).getOrNull(top.model.cursor) as? Row.Entry)?.e?.track?.id?.let { want.add(it) }
        for (id in want.distinct()) {
            if (artCache.containsKey(id) || id in artPending) continue
            artPending.add(id)
            bg.launch(Dispatchers.IO) {
                val a = try { library.art(id, 56) } catch (e: Exception) { Log.w("music", "art for $id: ${e.message}"); null }
                onShell {
                    artPending.remove(id)
                    artCache[id] = a?.let { Art.unpack(it, 56, 56) }
                    if (artCache.size > 64) artCache.keys.take(16).forEach { artCache.remove(it) }
                    if (a != null && (active || exclusive)) render()
                }
            }
        }
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

    private fun infoLines(f: Frame): List<DocLine> {
        f.lines?.let { return it }
        val out = ArrayList<DocLine>()
        val width = (services?.docContentWidth() ?: 560) - 32
        fun head(s: String) = out.add(DocLine(dn(s, fHead), fHead, Level.HEAD))
        fun line(s: String, fs: FontSpec = fBody, lv: Int = Level.BODY) { for (w in Wrap.wrap(dn(s, fs), fs, tx, width)) out.add(DocLine(w, fs, lv)) }
        val id = f.key.toIntOrNull() ?: -1
        val m = cat.track(id)
        val q = st.queue.firstOrNull { it.track.id == id }?.track
        if (m == null && q == null) line("this track is not in the catalog", lv = Level.DIM)
        else {
            head(m?.title ?: q!!.title)
            line(listOf(m?.artist ?: q?.artist ?: "", m?.album ?: q?.album ?: "").filter { it.isNotEmpty() }.joinToString(" — "))
            if (m != null) {
                line(listOf(if (m.year > 0) "${m.year}" else "", if (m.trackNo > 0) "track ${m.trackNo}${if (m.discNo > 0) " · disc ${m.discNo}" else ""}" else "",
                    Fmt.mmss(m.durMs.toLong()), m.ext.uppercase()).filter { it.isNotEmpty() }.joinToString(" · "), fSmall, Level.DIM)
                if (m.genres.isNotEmpty()) line("genres: " + m.genres.joinToString(", "))
                if (m.moods.isNotEmpty()) line("moods: " + m.moods.joinToString(", "))
                if (m.styles.isNotEmpty()) line("styles: " + m.styles.joinToString(", "))
                if (m.energy > 0 || m.vocals.isNotEmpty()) line(listOf(if (m.energy > 0) "energy ${m.energy}/10" else "", m.vocals).filter { it.isNotEmpty() }.joinToString(" · "))
                line(listOf(if (m.hasLyrics) "lyrics" else "no lyrics", if (m.folder.isNotEmpty()) m.folder else "").filter { it.isNotEmpty() }.joinToString(" · "), fSmall, Level.DIM)
            }
            out.add(DocLine("", fBody, Level.BODY))
            line("tap for Play now · Play next · Append · Add to playlist", fSmall, Level.DIM)
        }
        f.lines = out
        return out
    }

    // ================================================================ commits + menus
    private fun commit(f: Frame, i: Int) {
        when (val row = rows(f).getOrNull(i) ?: return) {
            is Row.Entry -> openEntryMenu(row.e, row.idx)
            is Row.Track -> if (f.kind == Kind.PLAYLIST && f.edit) openEditRowMenu(f, row) else openTrackMenu(row.t, f)
            is Row.Text -> row.act()
            is Row.ArtistRow -> push(Frame(Kind.ARTIST, row.a.name))
            is Row.AlbumRow -> push(Frame(Kind.ALBUM, row.a.name))
            is Row.TermRow -> push(Frame(Kind.TERM, row.v.term))
            is Row.PlaylistRow -> openPlaylist(row.p.id)
            is Row.FolderRow -> push(Frame(Kind.FOLDER, row.path))
            is Row.YtRow -> confirmGrab(row.r)
            Row.Menu -> when (f.kind) { Kind.PLAYLIST -> openPlaylistMenu(f); else -> openMenu() }
            Row.Empty -> push(Frame(Kind.BROWSE))
            Row.Loading -> {}
        }
    }

    private fun items(vararg pairs: Pair<MenuSurface.Item, () -> Unit>): Pair<List<MenuSurface.Item>, List<() -> Unit>> =
        pairs.map { it.first } to pairs.map { it.second }

    private fun menu(title: String, rows: List<Pair<MenuSurface.Item, () -> Unit>>, owner: Boolean = false) {
        val shown = services?.openMenu(MenuSurface.Spec(title, rows.map { it.first }, onCommit = { i -> rows.getOrNull(i)?.second?.invoke() }),
            owner = if (owner) this else null) == true
        if (!shown) setNotice("menu not available here")
    }

    private fun item(label: String, detail: String = "", enabled: Boolean = true, act: () -> Unit) = MenuSurface.Item(label, detail, enabled) to act

    /** Tap on a queue row (§8.1): Play from here · Play next · Remove (never
     *  the current) · Move up · Move down · Add to playlist… · Track info. */
    private fun openEntryMenu(e: QueueEntry, idx: Int) {
        val current = idx == st.index
        val rows = ArrayList<Pair<MenuSurface.Item, () -> Unit>>()
        rows.add(item(if (current) (if (st.play == PlayState.PLAYING) "Pause" else "Play") else "Play from here") { if (current) player.toggle() else player.playFrom(e.qid) })
        rows.add(item("Track info") { push(Frame(Kind.INFO, "${e.track.id}")) })
        if (!current) rows.add(item("Play next", "after the current") { player.move(e.qid, st.index + 1 - idx + (if (idx < st.index) 0 else 0)); render() })
        rows.add(item("Move up", enabled = idx > 0) { player.move(e.qid, -1) })
        rows.add(item("Move down", enabled = idx < st.queue.size - 1) { player.move(e.qid, 1) })
        rows.add(item("Add to playlist") { openAddToPlaylist(listOf(e.track)) })
        rows.add(item("Lyrics") { push(Frame(Kind.LYRICS)) })
        if (!current) rows.add(item("Remove", "from the queue") { player.remove(e.qid) })
        menu(e.track.title, rows)
    }

    /** A browse row's track: Play now · Play next · Append · Replace queue · Add to playlist… · Track info. */
    private fun openTrackMenu(t: TrackRef, f: Frame? = null) {
        val rows = ArrayList<Pair<MenuSurface.Item, () -> Unit>>()
        rows.add(item("Play now", "starts it") { player.playQueue(listOf(t), 0, Mode.QUEUE, t.title) })
        rows.add(item("Play next") { player.playNext(listOf(t)); setNotice("queued next") })
        rows.add(item("Append", "end of the queue") { player.append(listOf(t)); setNotice("appended") })
        if (f != null && f.kind != Kind.INFO) {
            val set = tracksOf(f)
            if (set.size > 1) rows.add(item("Play from here", "${set.size} tracks") { val i = set.indexOfFirst { it.id == t.id }.coerceAtLeast(0); player.playQueue(set, i, Mode.QUEUE, frameLabel(f)) })
        }
        rows.add(item("Add to playlist") { openAddToPlaylist(listOf(t)) })
        rows.add(item("Track info") { push(Frame(Kind.INFO, "${t.id}")) })
        rows.add(item("Replace queue", "this track only") { confirmReplace(listOf(t), t.title) })
        menu(t.title, rows)
    }

    private fun frameLabel(f: Frame): String = when (f.kind) {
        Kind.PLAYLIST -> playlistName(f)
        Kind.FOLDER -> f.key.substringAfterLast('/').ifEmpty { "collections" }
        Kind.RECENT -> "recent"
        Kind.RESULTS -> "\"${f.key}\""
        else -> f.key
    }

    /** A whole set (an artist, an album, a term, a folder): Play now · Play at random · Play next · Append · Replace queue · Add to playlist… */
    private fun openTrackSetMenu(set: List<TrackRef>, label: String) {
        if (set.isEmpty()) { setNotice("nothing here"); return }
        menu(label, listOf(
            item("Play now", "${set.size} in order") { playSet(set, Mode.QUEUE, label) },
            item("Play at random", "${set.size}") { playSet(set, Mode.SHUFFLE, label) },
            item("Play next") { player.playNext(set); setNotice("${set.size} queued next") },
            item("Append") { player.append(set); setNotice("${set.size} appended") },
            item("Add to playlist") { openAddToPlaylist(set) },
            item("Replace queue", "${set.size} tracks") { confirmReplace(set, label) },
        ))
    }

    private fun playSet(set: List<TrackRef>, mode: Mode, label: String) {
        if (st.play == PlayState.PLAYING && st.queue.isNotEmpty()) { confirmReplace(set, label, mode); return }
        player.playQueue(set, 0, mode, label)
        setNotice("playing ${set.size} · ${dn(label, fSmall)}")
    }

    /** Replace-queue-while-playing confirms (§8.1). */
    private fun confirmReplace(set: List<TrackRef>, label: String, mode: Mode = defaultMode) {
        if (st.play != PlayState.PLAYING || st.queue.isEmpty()) { player.playQueue(set, 0, mode, label); return }
        menu("replace the queue?", listOf(
            item("Cancel") {},
            item("Replace", "${st.queue.size} queued now → ${set.size}") { player.playQueue(set, 0, mode, label); setNotice("playing ${set.size} · ${dn(label, fSmall)}") },
        ))
    }

    /** The Music MENU (§8.2, the wrap-end row): the cursor-rest order. */
    private fun openMenu() {
        val rows = ArrayList<Pair<MenuSurface.Item, () -> Unit>>()
        val playing = st.play == PlayState.PLAYING
        rows.add(item(if (playing) "Pause" else "Resume", if (st.queue.isEmpty()) "nothing queued" else "") { player.toggle() })
        rows.add(item("Next") { player.next() })
        rows.add(item("Previous", "3 s in restarts") { player.prev() })
        rows.add(item("Volume", "${st.volume}%") { push(Frame(Kind.VOLUME)) })
        rows.add(item("Ask", "keyboard") { openAsk() })
        rows.add(item("Browse") { push(Frame(Kind.BROWSE)) })
        rows.add(item("Playlists", "${cat.playlists.size}") { push(Frame(Kind.PLAYLISTS)) })
        rows.add(item("Moods & genres") { push(Frame(Kind.VOCAB)) })
        rows.add(item("Search", "keyboard") { openSearch() })
        val nextMode = Mode.entries[(st.mode.ordinal + 1) % Mode.entries.size]
        rows.add(item("Mode: ${st.mode.label}", "next: ${nextMode.label}") { player.setMode(nextMode); setNotice("mode: ${nextMode.label}") })
        rows.add(item("Lyrics") { push(Frame(Kind.LYRICS)) })
        rows.add(item("Seek") { push(Frame(Kind.SEEK)) })
        rows.add(item("Save queue as playlist", "keyboard", enabled = st.queue.isNotEmpty()) { openSaveAs() })
        rows.add(item("Music Mode", "double-tap exits") { enterMusicMode() })
        rows.add(item("Output", st.outputs.firstOrNull { it.id == st.output }?.name ?: st.output) { openOutputMenu() })
        rows.add(item("Sleep", st.sleep.label(clock())) { openSleepMenu() })
        rows.add(item("Shuffle the rest", enabled = st.queue.size > 2) { player.shuffleRest(); setNotice("shuffled") })
        rows.add(item("Clear queue", enabled = st.queue.isNotEmpty()) { confirmClear() })
        rows.add(item("Stop", "restarts from the top next time") { player.stop() })
        if (st.backend == Backend.SPOTIFY) rows.add(item("Back to PC library", if (st.pcLink.up) "the PC is reachable" else "PC down") { player.backToPc() })
        else rows.add(item("Switch to Spotify", "on the phone") { player.setBackend(Backend.SPOTIFY) })
        menu("music", rows)
    }

    private fun confirmClear() {
        menu("clear the queue?", listOf(item("Cancel") {}, item("Clear", "${st.queue.size} tracks") { player.clear(); setNotice("queue cleared") }))
    }

    private fun openOutputMenu() {
        val outs = player.outputs()
        menu("output", outs.map { o -> item(o.name, if (o.id == st.output) "current" else o.kind) { player.setOutput(o.id); setNotice("output: ${dn(o.name, fSmall)}") } })
    }

    private fun openSleepMenu() {
        menu("sleep", Sleep.CHOICES.map { c -> item(c, if (st.sleep.label(clock()).startsWith(c.substringBefore(' '))) "current" else "") { player.setSleep(Sleep.fromChoice(c, clock())); setNotice("sleep: $c") } })
    }

    // ---------------------------------------------------------------- playlists
    private fun openPlaylist(id: Int) {
        push(Frame(Kind.PLAYLIST, "$id"))
    }

    private fun playlistOf(f: Frame): Playlist? = cat.playlists.firstOrNull { it.id == f.key.toIntOrNull() }

    /** The playlist's own menu (its wrap-end row): Play · Play at random ·
     *  Add current · Rename… · Edit · Save queue over this · Delete. */
    private fun openPlaylistMenu(f: Frame) {
        val p = playlistOf(f) ?: run { setNotice("this playlist is gone"); return }
        val ts = f.tracks ?: emptyList()
        val rows = ArrayList<Pair<MenuSurface.Item, () -> Unit>>()
        rows.add(item("Play", "${ts.size} in order", enabled = ts.isNotEmpty()) { playSet(ts, Mode.QUEUE, p.name) })
        rows.add(item("Play at random", enabled = ts.isNotEmpty()) { playSet(ts, Mode.SHUFFLE, p.name) })
        rows.add(item("Play next", enabled = ts.isNotEmpty()) { player.playNext(ts); setNotice("${ts.size} queued next") })
        rows.add(item("Append", enabled = ts.isNotEmpty()) { player.append(ts); setNotice("${ts.size} appended") })
        rows.add(item("Add current", st.track?.title ?: "nothing playing", enabled = st.track != null && !p.adaptive) {
            val t = st.track ?: return@item
            runOp("adding") { library.setPlaylistTracks(p.id, ts.map { it.id } + t.id); f.tracks = null; "added to ${p.name}" }
        })
        rows.add(item("Rename", "keyboard", enabled = !p.adaptive) { openRename(p) })
        rows.add(item(if (f.edit) "Done editing" else "Edit", if (p.adaptive) "adaptive: its rule manages the rows" else "reorder · remove", enabled = !p.adaptive) { f.edit = !f.edit; f.rowsCache = null; render() })
        rows.add(item("Save queue over this", "asks twice", enabled = !p.adaptive && st.queue.isNotEmpty()) { confirmSaveOver(p.name) })
        rows.add(item("Delete", "2 confirms", enabled = !p.adaptive) { confirmDeletePlaylist(p) })
        if (p.adaptive) rows.add(item("adaptive playlist", "edits refused — its rule manages the rows", enabled = false) {})
        menu(p.name, rows)
    }

    private fun openEditRowMenu(f: Frame, row: Row.Track) {
        val p = playlistOf(f) ?: return
        val ts = f.tracks ?: return
        val i = row.pos - 1
        fun set(newList: List<TrackRef>) = runOp("saving") { library.setPlaylistTracks(p.id, newList.map { it.id }); f.tracks = newList; f.rowsCache = null; "saved" }
        menu(row.t.title, listOf(
            item("Play now") { player.playQueue(listOf(row.t), 0, Mode.QUEUE, row.t.title) },
            item("Move up", enabled = i > 0) { set(ts.toMutableList().apply { val x = removeAt(i); add(i - 1, x) }) },
            item("Move down", enabled = i < ts.size - 1) { set(ts.toMutableList().apply { val x = removeAt(i); add(i + 1, x) }) },
            item("Remove from playlist") { set(ts.filterIndexed { k, _ -> k != i }) },
        ))
    }

    private fun openAddToPlaylist(tracks: List<TrackRef>) {
        val pls = cat.playlists.filter { !it.adaptive }
        val rows = ArrayList<Pair<MenuSurface.Item, () -> Unit>>()
        rows.add(item("New playlist", "keyboard") { openSaveAs(tracks) })
        for (p in pls) rows.add(item(p.name, "${p.count}") {
            runOp("adding") {
                val have = library.playlistTracks(p.id).map { it.id }
                library.setPlaylistTracks(p.id, have + tracks.map { it.id }.filter { it !in have })
                "added ${tracks.size} to ${p.name}"
            }
        })
        menu("add to playlist", rows)
    }

    private fun confirmDeletePlaylist(p: Playlist) {
        menu("delete '${p.name}'?", listOf(item("Cancel") {}, item("Continue", "asks once more") {
            services?.openMenu(MenuSurface.Spec("really delete '${p.name}'?", listOf(MenuSurface.Item("Cancel"),
                MenuSurface.Item("this cannot be undone", enabled = false), MenuSurface.Item("Delete", detail = "${p.count} rows")),
                onCommit = { j -> if (j == 2) runOp("deleting") { library.deletePlaylist(p.id); if (top.kind == Kind.PLAYLIST) back(); "deleted ${p.name}" } }))
        }))
    }

    private fun openRename(p: Playlist) {
        val opened = services?.openKeyboard(KeyboardSurface.Spec(title = "rename playlist", initial = p.name,
            onCommit = { n -> if (n.isBlank()) setNotice("empty name") else runOp("renaming") { library.renamePlaylist(p.id, n.trim()); "renamed to ${n.trim()}" } }),
            owner = this) == true
        if (!opened) setNotice("the keyboard is not available here")
    }

    /** Save the queue (or [tracks]) under a typed name; an existing name is asked twice (§8.1). */
    private fun openSaveAs(tracks: List<TrackRef>? = null) {
        val set = tracks ?: st.queue.map { it.track }
        if (set.isEmpty()) { setNotice("nothing to save"); return }
        val opened = services?.openKeyboard(KeyboardSurface.Spec(title = "playlist name", initial = nameDraft,
            onCommit = { n ->
                nameDraft = ""
                val name = n.trim()
                if (name.isEmpty()) { setNotice("empty name"); return@Spec }
                val existing = cat.playlists.firstOrNull { it.name.equals(name, true) }
                if (existing == null) runOp("saving") { library.savePlaylist(name, set.map { it.id }, false); savedNotice(name) }
                else if (existing.adaptive) setNotice("'$name' is adaptive — pick another name")
                else confirmSaveOver(name, set)
            }, onCancel = { d -> nameDraft = d }), owner = this) == true
        if (!opened) setNotice("the keyboard is not available here")
    }

    private fun savedNotice(name: String): String {
        if (notifyPlaylist) services?.notifyInternal("music", "playlist saved · ${dn(name, fBody)}", appId = id, thread = "playlist")
        return "saved $name"
    }

    private fun confirmSaveOver(name: String, set: List<TrackRef> = st.queue.map { it.track }) {
        menu("'$name' exists — save over it?", listOf(item("Cancel") {}, item("Continue", "asks once more") {
            services?.openMenu(MenuSurface.Spec("really replace '$name'?", listOf(MenuSurface.Item("Cancel"),
                MenuSurface.Item("its rows are replaced", enabled = false), MenuSurface.Item("Replace", detail = "${set.size} tracks")),
                onCommit = { j -> if (j == 2) runOp("saving") { library.savePlaylist(name, set.map { it.id }, true); savedNotice(name) } }))
        }))
    }

    // ---------------------------------------------------------------- ask / search / youtube (the keyboard)
    private fun openAsk() {
        val opened = services?.openKeyboard(KeyboardSurface.Spec(title = "ask for music", initial = askDraft,
            onCommit = { q -> askDraft = ""; if (q.isBlank()) setNotice("empty ask") else runAsk(q.trim()) },
            onCancel = { d -> askDraft = d }), owner = this) == true
        if (!opened) setNotice("the keyboard is not available here — type on the phone strip")
    }

    /** The three lanes: the honest which-lane line rides the title, then the queue plays. */
    private fun runAsk(q: String) {
        if (opBusy) { setNotice("busy — one operation at a time"); return }
        opBusy = true
        services?.setOperation("asking")
        bg.launch(Dispatchers.IO) {
            val r = try { Result.success(library.ask(q)) } catch (e: Exception) { Log.e("music", "ask '$q' failed", e); Result.failure(e) }
            onShell {
                opBusy = false
                services?.setOperation("idle")
                r.onSuccess { rq ->
                    if (rq.tracks.isEmpty()) setNotice(dn(rq.detail.ifEmpty { "no library match — try Search, then YouTube" }, fSmall))
                    else {
                        setNotice(dn("${rq.lane}: ${rq.tracks.size} · ${rq.label}", fSmall))
                        player.playQueue(rq.tracks, 0, if (rq.lane == "album" || rq.lane == "playlist") Mode.QUEUE else defaultMode, rq.label)
                        Log.i("music", "ask \"$q\": ${rq.detail}")
                    }
                }.onFailure { e -> setNotice(dn(e.message ?: "ask failed", fSmall)) }
                render()
            }
        }
    }

    private fun openSearch() {
        val opened = services?.openKeyboard(KeyboardSurface.Spec(title = "search the library", initial = searchDraft,
            onCommit = { q -> searchDraft = ""; if (q.isBlank()) setNotice("empty search") else push(Frame(Kind.RESULTS, q.trim())) },
            onCancel = { d -> searchDraft = d }), owner = this) == true
        if (!opened) setNotice("the keyboard is not available here")
    }

    private fun openYtSearch(initial: String = ytDraft) {
        val opened = services?.openKeyboard(KeyboardSurface.Spec(title = "search youtube", initial = initial,
            onCommit = { q -> ytDraft = ""; if (q.isBlank()) setNotice("empty search") else push(Frame(Kind.YT, q.trim())) },
            onCancel = { d -> ytDraft = d }), owner = this) == true
        if (!opened) setNotice("the keyboard is not available here")
    }

    /** A grab is an outbound act: it stages a confirm (never the first hit unasked — verdict 7). */
    private fun confirmGrab(r: YtResult) {
        menu("grab '${r.title}'?", listOf(item("Cancel") {}, item("Grab and add", "${r.channel} · ${Fmt.mmss(r.durS * 1000L)}") {
            runOp("grabbing") { val job = library.ytGrab(r.id); ytJobs[job] = YtJob(job, r.title, "queued"); "grab started · ${r.title}" }
        }))
    }

    // ---------------------------------------------------------------- one op at a time
    private fun runOp(verb: String, op: () -> String?) {
        if (opBusy) { setNotice("busy — one operation at a time"); return }
        opBusy = true
        services?.setOperation(verb)
        bg.launch(Dispatchers.IO) {
            val r = try { Result.success(op()) } catch (e: Exception) { Log.e("music", "$verb failed", e); Result.failure(e) }
            onShell {
                opBusy = false
                services?.setOperation("idle")
                r.onSuccess { msg -> if (msg != null) setNotice(dn(msg, fSmall)); invalidateRows(); if (top.kind == Kind.PLAYLIST && top.tracks == null) load(top) }
                    .onFailure { e -> setNotice(dn(e.message ?: "$verb failed", fSmall)) }
                render()
            }
        }
    }

    // ================================================================ lyrics (§3.7 — the scheduler runs from the player's real position)
    private fun demandLyrics() {
        val tid = st.track?.id ?: return
        if (lyricsFor != tid && lyricsState != "loading") loadLyrics()
    }

    private fun loadLyrics() {
        val t = st.track ?: run { lyricsFor = -1; lyricsParsed = null; lyricsPlain = null; lyricsState = "none"; return }
        if (lyricsFor == t.id && lyricsState != "" && lyricsState != "loading") return
        val seq = ++lyricsSeq
        lyricsFor = t.id
        lyricsState = "loading"
        lyricsParsed = null; lyricsPlain = null; lyricsPlainPage = 0; lyricsLineShown = -2
        bg.launch(Dispatchers.IO) {
            val r = try { Result.success(library.lyrics(t.id)) } catch (e: Exception) { Log.w("music", "lyrics for ${t.id}: ${e.message}"); Result.failure(e) }
            onShell {
                if (seq != lyricsSeq) return@onShell
                r.onSuccess { ly ->
                    when {
                        ly == null || !ly.found -> lyricsState = "none"
                        !ly.synced.isNullOrBlank() -> {
                            val p = LyricsSync.parse(ly.synced!!)
                            if (p.isEmpty) { lyricsPlain = LyricsSync.pages(ly.synced!!, 12); lyricsState = "plain" }
                            else { lyricsParsed = p; lyricsState = "synced" }
                        }
                        else -> { lyricsPlain = LyricsSync.pages(ly.plain ?: "", 12); lyricsState = "plain" }
                    }
                }.onFailure { e -> lyricsState = "failed: ${e.message}" }
                if (top.kind == Kind.LYRICS) { render(); scheduleLyricFlush() }
                if (exclusive) render()
            }
        }
    }

    /** Arm the next line's repaint AHEAD of its stamp (the display latency
     *  is modeled at the measured `60 + bytes/50` — a lyric repaint is a
     *  few hundred bytes); generation-stamped, one arm at a time. */
    private fun scheduleLyricFlush() {
        val p = lyricsParsed ?: return
        if (st.play != PlayState.PLAYING) return
        val gen = ++lyricsGen
        val delay = LyricsSync.nextFlushDelay(p.lines, player.positionMs(), lyricsOffset().toLong(), LYRIC_DISPLAY_MS) ?: return
        bg.launch {
            delay(delay)
            onShell {
                if (gen != lyricsGen) return@onShell
                if (top.kind == Kind.LYRICS || exclusive) render()
                if (st.play == PlayState.PLAYING) scheduleLyricFlush()
            }
        }
    }

    private fun currentLyricLine(): Int {
        val p = lyricsParsed ?: return -1
        return LyricsSync.lineAt(p.lines, LyricsSync.heardPos(player.positionMs(), lyricsOffset().toLong()))
    }

    private fun paintLyrics(g: Gray8, r: Rect) {
        g.fillRect(r, Level.BG)
        val lh = lineH(fLyric)
        val lhDim = lineH(fLyricDim)
        val x = r.x + 16
        val w = r.w - 32 - 12
        val t = st.track
        when {
            t == null -> Draw.fit(g, tx, x, r.y + 16, "nothing playing", Level.DIM, fBody, w)
            lyricsState == "loading" -> Draw.fit(g, tx, x, r.y + 16, "looking up lyrics", Level.DIM, fBody, w)
            lyricsState == "none" -> {
                Draw.fit(g, tx, x, r.y + 16, "no lyrics for this track", Level.DIM, fBody, w)
                Draw.fit(g, tx, x, r.y + 16 + lhDim, "tap: search lyrics", Level.DIM, fSmall, w)
            }
            lyricsState.startsWith("failed") -> Draw.fit(g, tx, x, r.y + 16, dn(lyricsState, fBody), Level.DIM, fBody, w)
            lyricsState == "plain" -> {
                val pages = lyricsPlain ?: emptyList()
                val page = pages.getOrNull(lyricsPlainPage.coerceIn(0, maxOf(0, pages.size - 1))) ?: emptyList()
                var y = r.y + 12
                for (line in page) {
                    for (wl in Wrap.wrap(dn(line, fLyricDim), fLyricDim, tx, w)) {
                        if (y + lhDim > r.bottom - 8) break
                        tx.draw(g, x / 4 * 4, y / 2 * 2, wl, fLyricDim, Level.BODY); y += lhDim
                    }
                }
                Draw.right(g, tx, r.right - 20, r.bottom - 22, "page ${lyricsPlainPage + 1}/${maxOf(1, pages.size)} · scroll", Level.DIM, fSmall)
            }
            else -> {
                val p = lyricsParsed ?: return
                val cur = currentLyricLine()
                lyricsLineShown = cur
                val slots = maxOf(3, (r.h - 24) / lhDim)
                val before = if (slots >= 5) 2 else 1
                val range = LyricsSync.window(p.lines, maxOf(0, cur), before, slots)
                var y = r.y + 12
                for (i in range) {
                    val l = p.lines[i]
                    val isCur = i == cur
                    val f = if (isCur) fLyric else fLyricDim
                    val lv = when { isCur -> Level.HEAD; i < cur -> Level.DIM; else -> Level.BODY }
                    val wrapped = Wrap.wrap(dn(l.text.ifEmpty { "…" }.let { if (it == "…") "-" else it }, f), f, tx, w)
                    for (wl in wrapped) {
                        if (y + (if (isCur) lh else lhDim) > r.bottom - 8) break
                        tx.draw(g, x / 4 * 4, y / 2 * 2, wl, f, lv); y += if (isCur) lh else lhDim
                    }
                }
                val off = lyricsOffset()
                Draw.right(g, tx, r.right - 20, r.bottom - 22,
                    (if (cur < 0) "before the first line" else "${cur + 1}/${p.lines.size}") + (if (off != 0) " · offset ${if (off > 0) "+" else ""}$off ms" else ""), Level.DIM, fSmall)
            }
        }
    }

    /** Scroll on the lyrics: a synced track nudges the per-output offset by
     *  ±50 ms (calibration, remembered per device); plain pages turn. */
    private fun nudgeLyrics(delta: Int) {
        if (lyricsState == "plain") {
            val n = lyricsPlain?.size ?: 1
            lyricsPlainPage = (lyricsPlainPage + delta).coerceIn(0, maxOf(0, n - 1))
            render(); return
        }
        if (lyricsParsed == null) return
        val k = outputKey()
        val v = ((lyricsOffsets[k] ?: 0) + delta * 50).coerceIn(-2000, 2000)
        lyricsOffsets[k] = v
        setNotice("lyrics offset ${if (v > 0) "+" else ""}$v ms · ${dn(k, fSmall)}")
        scheduleLyricFlush()
        render()
    }

    private fun openLyricsMenu() {
        val t = st.track ?: run { setNotice("nothing playing"); return }
        menu("lyrics", listOf(
            item("Search lyrics", "keyboard") { openLyricsSearch(t) },
            item("Reset offset", "${lyricsOffset()} ms") { lyricsOffsets.remove(outputKey()); scheduleLyricFlush(); render() },
            item("Reload") { lyricsFor = -1; loadLyrics() },
            item("Track info") { push(Frame(Kind.INFO, "${t.id}")) },
        ))
    }

    private fun openLyricsSearch(t: TrackRef) {
        val opened = services?.openKeyboard(KeyboardSurface.Spec(title = "search lyrics", initial = lyricsDraft.ifEmpty { Fmt.titleArtist(t) },
            onCommit = { q ->
                lyricsDraft = ""
                if (q.isBlank()) { setNotice("empty search"); return@Spec }
                if (opBusy) { setNotice("busy"); return@Spec }
                opBusy = true
                services?.setOperation("lyrics")
                bg.launch(Dispatchers.IO) {
                    val r = try { Result.success(library.searchLyrics(t.id, q.trim())) } catch (e: Exception) { Result.failure(e) }
                    onShell {
                        opBusy = false
                        services?.setOperation("idle")
                        r.onSuccess { cands ->
                            if (cands.isEmpty()) { setNotice("no lyrics found"); return@onSuccess }
                            val rows = cands.map { c -> item(c.label.ifEmpty { c.source }, if (c.synced != null) "synced" else "plain") {
                                runOp("saving lyrics") { library.setLyrics(t.id, c); lyricsFor = -1; loadLyrics(); "lyrics: ${c.source}" }
                            } }
                            val shown = services?.openMenu(MenuSurface.Spec("lyrics found", rows.map { it.first }, onCommit = { i -> rows.getOrNull(i)?.second?.invoke() }), owner = this@MusicWindow) == true
                            if (!shown) setNotice("${cands.size} candidates — open Lyrics again")
                        }.onFailure { e -> setNotice(dn(e.message ?: "search failed", fSmall)) }
                    }
                }
            }, onCancel = { d -> lyricsDraft = d }), owner = this) == true
        if (!opened) setNotice("the keyboard is not available here")
    }

    // ================================================================ volume (canvas: scroll adjusts live, tap keeps)
    private fun paintVolume(g: Gray8, r: Rect) {
        g.fillRect(r, Level.BG)
        val cx = r.x + r.w / 2
        val label = "${st.volume}%"
        val lw = tx.measure(label, fBig)
        tx.draw(g, Geometry.snapX(cx - lw / 2), Geometry.snapY(r.y + r.h / 2 - 40), label, fBig, Level.HEAD)
        Icons.blocks(g, Geometry.snapX(cx - 160), Geometry.snapY(r.y + r.h / 2 + 8), 320, 10, st.volume / 100.0, n = 20, level = Level.of(7))
        val sub = listOf("scroll adjusts · tap keeps", if (st.boost > 100) "boost +${st.boost}%" else "", if (!st.holdVolume) "hold my volume off" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        Draw.fit(g, tx, Geometry.snapX(cx - 160), r.y + r.h / 2 + 28, sub, Level.DIM, fSmall, 320)
    }

    // ================================================================ Music Mode (§8.3; the shell's exclusive mode lands with M3)
    private fun enterMusicMode(): Boolean {
        val ok = services?.enterExclusive(this) == true
        if (!ok) setNotice("Music Mode is not available on this host")
        return ok
    }

    override fun onExclusive(on: Boolean) {
        exclusive = on
        if (on) { player.setFocused(true); loadLyrics() } else if (!active) player.setFocused(false)
    }

    private fun exclusiveTick(posMs: Long) { /* the surfaces repaint on their own pacing — M3 */ }

    // ================================================================ settings (§8.4)
    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private val settingsRows: List<HostSetting> by lazy {
        listOf(
            HostSetting("Notify · track change", listOf("on", "off"), { onOff(notifyTrack) }, { notifyTrack = it == "on" }),
            HostSetting("Notify · queue end", listOf("on", "off"), { onOff(notifyQueueEnd) }, { notifyQueueEnd = it == "on" }),
            HostSetting("Notify · route loss", listOf("on", "off"), { onOff(notifyRoute) }, { notifyRoute = it == "on" }),
            HostSetting("Notify · PC unreachable", listOf("on", "off"), { onOff(notifyPc) }, { notifyPc = it == "on" }),
            HostSetting("Notify · YouTube", listOf("on", "off"), { onOff(notifyYt) }, { notifyYt = it == "on" }),
            HostSetting("Notify · playlist saved", listOf("on", "off"), { onOff(notifyPlaylist) }, { notifyPlaylist = it == "on" }),
            HostSetting("Volume", (0..100 step 5).map { "$it%" }, { "${st.volume}%" }, { v -> player.setVolume(v.removeSuffix("%").toIntOrNull() ?: st.volume, "settings") }),
            HostSetting("Volume boost", BOOSTS.map { if (it == 100) "off" else "$it%" }, { if (st.boost <= 100) "off" else "${st.boost}%" },
                { v -> player.setBoost(if (v == "off") 100 else v.removeSuffix("%").toIntOrNull() ?: 100) }),
            HostSetting("Hold my volume", listOf("on", "off"), { onOff(st.holdVolume) }, { player.setHoldVolume(it == "on") }),
            HostSetting("Output", { player.outputs().map { it.name } }, { st.outputs.firstOrNull { it.id == st.output }?.name ?: st.output },
                { v -> player.outputs().firstOrNull { it.name == v }?.let { player.setOutput(it.id) } }, null),
            HostSetting("Quality", AudioProfile.Quality.entries.map { it.label }, { quality.label },
                { v -> AudioProfile.Quality.entries.firstOrNull { it.label == v }?.let { quality = it; player.setProfile(profile()) } }),
            HostSetting("Channels", listOf("mono", "stereo"), { if (channels >= 2) "stereo" else "mono" }, { v -> channels = if (v == "stereo") 2 else 1; player.setProfile(profile()) }),
            HostSetting("Normalization", listOf("on", "off"), { onOff(normalization) }, { v -> normalization = v == "on"; player.setProfile(profile()) }),
            HostSetting("Default mode", Mode.entries.map { it.label }, { defaultMode.label }, { v -> Mode.entries.firstOrNull { it.label == v }?.let { defaultMode = it } }),
            HostSetting("Prefetch", listOf("1", "2", "3", "5", "10"), { "${prefetchPref}" }, { v -> prefetchPref = v.toIntOrNull() ?: 3; player.setPrefetch(prefetchPref) }),
            HostSetting("Lyrics offset", (-500..500 step 50).map { "${if (it > 0) "+" else ""}$it ms" }, { "${if (lyricsOffset() > 0) "+" else ""}${lyricsOffset()} ms" },
                { v -> lyricsOffsets[outputKey()] = v.removeSuffix(" ms").replace("+", "").toIntOrNull() ?: 0 }),
            HostSetting("Lyrics sources", LYRICS_SOURCES, { lyricsSources }, { lyricsSources = it }),
            HostSetting("Visualizer", VIZ_NAMES, { vizName }, { vizName = it }),
            HostSetting("Visualizer rate", listOf("4", "8", "12"), { "$vizRate" }, { vizRate = it.toIntOrNull() ?: 8 }),
            HostSetting("Music Mode · card", listOf("on", "off"), { onOff(mmCard) }, { mmCard = it == "on" }),
            HostSetting("Music Mode · lyrics", listOf("on", "off"), { onOff(mmLyrics) }, { mmLyrics = it == "on" }),
            HostSetting("Music Mode · visualizer", listOf("on", "off"), { onOff(mmViz) }, { mmViz = it == "on" }),
            HostSetting("Music Mode · queue peek", listOf("on", "off"), { onOff(mmPeek) }, { mmPeek = it == "on" }),
            HostSetting("Music Mode · clock", listOf("on", "off"), { onOff(mmClock) }, { mmClock = it == "on" }),
            HostSetting("Music Mode · PC link", listOf("on", "off"), { onOff(mmLink) }, { mmLink = it == "on" }),
            HostSetting("Spotify fallback", listOf("auto", "never"), { if (spotifyFallbackPref) "auto" else "never" }, { v -> spotifyFallbackPref = v == "auto"; player.setSpotifyFallback(spotifyFallbackPref) }),
            HostSetting("Sleep", Sleep.CHOICES, { st.sleep.label(clock()).let { l -> Sleep.CHOICES.firstOrNull { c -> l.startsWith(c.substringBefore(' ')) } ?: "off" } },
                { v -> player.setSleep(Sleep.fromChoice(v, clock())) }),
            HostSetting("Pre-transcode library", listOf("no", "start"), { "no" }, { v -> if (v == "start") runOp("pre-transcode") { library.pretranscode(profile()) } }),
            HostSetting("Rescan library", listOf("no", "start"), { "no" }, { v -> if (v == "start") runOp("rescan") { library.rescan() } }),
            HostSetting("Size", listOf("global") + ShellSettings.HEIGHTS.map { "$it" }, { heightPref?.toString() ?: "480" },
                { heightPref = it.toIntOrNull() }),
        )
    }
    private var prefetchPref = 3
    private var spotifyFallbackPref = true

    override fun appSettings(): List<HostSetting> = settingsRows

    // the exclusive-mode painters read these (M3)
    internal fun mmSurfaces(): List<String> = listOfNotNull(
        "card".takeIf { mmCard }, "lyrics".takeIf { mmLyrics }, "viz".takeIf { mmViz }, "peek".takeIf { mmPeek }, "clock".takeIf { mmClock }, "link".takeIf { mmLink })
    internal fun vizSetting(): Pair<String, Int> = vizName to vizRate

    // ================================================================ persistence
    override fun saveState(): JsonObject = buildJsonObject {
        putJsonArray("stack") {
            for (f in stack) add(buildJsonObject {
                put("kind", f.kind.name); put("key", f.key); put("cursor", f.model.cursor); put("top", f.doc.topLine); put("edit", f.edit)
                if (f.kind == Kind.QUEUE) (rows(f).getOrNull(f.model.cursor) as? Row.Entry)?.e?.qid?.let { put("qid", it) }
            })
        }
        put("askDraft", askDraft); put("searchDraft", searchDraft); put("ytDraft", ytDraft); put("nameDraft", nameDraft); put("lyricsDraft", lyricsDraft)
        put("notifyTrack", notifyTrack); put("notifyQueueEnd", notifyQueueEnd); put("notifyRoute", notifyRoute)
        put("notifyPc", notifyPc); put("notifyYt", notifyYt); put("notifyPlaylist", notifyPlaylist)
        put("defaultMode", defaultMode.name)
        put("lyricsOffsets", buildJsonObject { for ((k, v) in lyricsOffsets) put(k, v) })
        put("lyricsSources", lyricsSources); put("viz", vizName); put("vizRate", vizRate)
        put("mmCard", mmCard); put("mmLyrics", mmLyrics); put("mmViz", mmViz); put("mmPeek", mmPeek); put("mmClock", mmClock); put("mmLink", mmLink)
        put("quality", quality.name); put("channels", channels); put("normalization", normalization)
        put("prefetch", prefetchPref); put("spotifyFallback", spotifyFallbackPref)
        put("lyricsPlainPage", lyricsPlainPage)
        heightPref?.let { put("height", it) }
    }

    override fun restoreState(state: JsonObject) {
        for (f in stack) f.seq++
        stack.clear()
        stack.add(Frame(Kind.QUEUE))
        (state["stack"] as? JsonArray)?.forEachIndexed { i, el ->
            try {
                val o = el.jsonObject
                val kind = Kind.entries.firstOrNull { it.name == o["kind"]?.jsonPrimitive?.contentOrNull } ?: return@forEachIndexed
                val f = if (i == 0 && kind == Kind.QUEUE) stack[0] else Frame(kind, o["key"]?.jsonPrimitive?.contentOrNull ?: "")
                if (f !== stack[0]) { if (kind == Kind.QUEUE) return@forEachIndexed; stack.add(f) }
                f.pendingCursor = o["cursor"]?.jsonPrimitive?.intOrNull
                f.pendingQid = o["qid"]?.jsonPrimitive?.longOrNull
                f.doc.topLine = o["top"]?.jsonPrimitive?.intOrNull ?: 0
                f.edit = o["edit"]?.jsonPrimitive?.booleanOrNull ?: false
                f.model.cursor = f.pendingCursor ?: 0
            } catch (e: Exception) { Log.w("music", "stored level unreadable — dropped: ${e.message}") }
        }
        askDraft = state["askDraft"]?.jsonPrimitive?.contentOrNull ?: ""
        searchDraft = state["searchDraft"]?.jsonPrimitive?.contentOrNull ?: ""
        ytDraft = state["ytDraft"]?.jsonPrimitive?.contentOrNull ?: ""
        nameDraft = state["nameDraft"]?.jsonPrimitive?.contentOrNull ?: ""
        lyricsDraft = state["lyricsDraft"]?.jsonPrimitive?.contentOrNull ?: ""
        notifyTrack = state["notifyTrack"]?.jsonPrimitive?.booleanOrNull ?: true
        notifyQueueEnd = state["notifyQueueEnd"]?.jsonPrimitive?.booleanOrNull ?: true
        notifyRoute = state["notifyRoute"]?.jsonPrimitive?.booleanOrNull ?: true
        notifyPc = state["notifyPc"]?.jsonPrimitive?.booleanOrNull ?: false
        notifyYt = state["notifyYt"]?.jsonPrimitive?.booleanOrNull ?: true
        notifyPlaylist = state["notifyPlaylist"]?.jsonPrimitive?.booleanOrNull ?: false
        defaultMode = state["defaultMode"]?.jsonPrimitive?.contentOrNull?.let { n -> Mode.entries.firstOrNull { it.name == n } } ?: Mode.SHUFFLE
        lyricsOffsets = HashMap<String, Int>().apply {
            (state["lyricsOffsets"] as? JsonObject)?.forEach { (k, v) -> v.jsonPrimitive.intOrNull?.let { put(k, it) } }
        }
        lyricsSources = state["lyricsSources"]?.jsonPrimitive?.contentOrNull?.takeIf { it in LYRICS_SOURCES } ?: "lrclib+local"
        vizName = state["viz"]?.jsonPrimitive?.contentOrNull?.takeIf { it in VIZ_NAMES } ?: "Off"
        vizRate = state["vizRate"]?.jsonPrimitive?.intOrNull?.takeIf { it in listOf(4, 8, 12) } ?: 8
        mmCard = state["mmCard"]?.jsonPrimitive?.booleanOrNull ?: true
        mmLyrics = state["mmLyrics"]?.jsonPrimitive?.booleanOrNull ?: true
        mmViz = state["mmViz"]?.jsonPrimitive?.booleanOrNull ?: false
        mmPeek = state["mmPeek"]?.jsonPrimitive?.booleanOrNull ?: false
        mmClock = state["mmClock"]?.jsonPrimitive?.booleanOrNull ?: true
        mmLink = state["mmLink"]?.jsonPrimitive?.booleanOrNull ?: true
        quality = state["quality"]?.jsonPrimitive?.contentOrNull?.let { n -> AudioProfile.Quality.entries.firstOrNull { it.name == n } } ?: AudioProfile.Quality.HIGH
        channels = state["channels"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..2 } ?: 1
        normalization = state["normalization"]?.jsonPrimitive?.booleanOrNull ?: true
        prefetchPref = state["prefetch"]?.jsonPrimitive?.intOrNull ?: 3
        spotifyFallbackPref = state["spotifyFallback"]?.jsonPrimitive?.booleanOrNull ?: true
        lyricsPlainPage = state["lyricsPlainPage"]?.jsonPrimitive?.intOrNull ?: 0
        heightPref = state["height"]?.jsonPrimitive?.intOrNull?.takeIf { it in ShellSettings.HEIGHTS }
        if (opBusy) services?.setOperation("idle")
        opBusy = false
        lyricsSeq++; lyricsGen++
        lyricsFor = -1; lyricsParsed = null; lyricsPlain = null; lyricsState = ""
        invalidateRows()
        // a restored queue cursor resolves NOW when the player's record is at hand (a boot
        // or keeper restart would otherwise wait for the next state push)
        root.pendingQid?.let { q -> rows(root).indexOfFirst { (it as? Row.Entry)?.e?.qid == q }.takeIf { it >= 0 }?.let { setRootCursor(it); root.pendingQid = null; root.pendingCursor = null } }
        needsReload = top.kind in listOf(Kind.PLAYLIST, Kind.RESULTS, Kind.YT, Kind.LYRICS)
    }

    override fun restoreStateLive(state: JsonObject) {
        restoreState(state)
        if (active) { needsReload = false; reloadTop() }
    }

    /** The player's synced record (§6.2): `window.music.player`. */
    override fun saveSubState(): Map<String, JsonObject> = mapOf("player" to player.persist())

    override fun restoreSubState(subKey: String, state: JsonObject) {
        if (subKey != "player" || state.isEmpty()) return
        player.restore(state)
        // the Sim/Android players apply on their own thread and push a state;
        // the mirror applies inline — either way the rows re-derive
        invalidateRows()
        if (active) render()
    }

    companion object {
        const val CARD_PACE_MS = 5_000L
        /** The modeled display cost of a lyric repaint (`60 + bytes/50`, a few hundred bytes). */
        const val LYRIC_DISPLAY_MS = 70L
        val BOOSTS = listOf(100, 150, 200, 300, 400)
        val LYRICS_SOURCES = listOf("lrclib+local", "+netease", "+musixmatch")
        val VIZ_NAMES = listOf("Off", "Bars", "Scope", "Pulse", "Meter")
        val SEEK_ROWS = listOf("-5 min" to -300_000L, "-30 s" to -30_000L, "-10 s" to -10_000L, "+10 s" to 10_000L, "+30 s" to 30_000L, "+5 min" to 300_000L, "Restart" to Long.MIN_VALUE)
    }
}
