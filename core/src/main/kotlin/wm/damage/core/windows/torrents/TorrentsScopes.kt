package wm.damage.core.windows.torrents

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.Change
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.Paged
import wm.damage.core.app.ProgramHandler
import wm.damage.core.app.ProgramResult
import wm.damage.core.app.Record
import wm.damage.core.app.Viewers
import wm.damage.core.app.WindowState
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q4 (the spec's §3.1, §3.3, §2.2–§2.4, §5 rows T1, T2, V1–V3; `CONTRACT.md` §8, §9.3): Torrents'
 * PC-served parts on the `app` lane, made once per process by the PC's two host builders over the PC's provider.
 * - [table] (its own describe executor): `desc/torrents` — the description, with the transfer table, the session and
 *   its speed samples — and `torrents/t:<hash>`, a transfer's files and trackers (level view).
 * - [tracker] (an executor of its own, so a tracker page that takes seconds holds neither the table nor Reader's
 *   parts): `torrents/categories`, the paged listings `torrents/listing:<cat|newest>/sort:<s>/page:<n>` and
 *   `torrents/search:<query, percent-encoded>/sort:<s>/page:<n>` (count unknown, endless as the glasses treat them),
 *   `torrents/tl:<fid>` and `torrents/account` — all at level full (the tracker acts under his account, C§4.2).
 * - [program]: the ops into qBittorrent, the tracker and `xdg-open` (never queued; Sent or Refused, decisions A.7).
 * - [watch]: the pace per viewer — a web page in front (`need: focus`) polls at its own Poll as `web:<conn>`; its end,
 *   its page hidden or its connection gone quiet releases it (the win channel keeps its one `remote` key, A.3).
 * Nothing here reaches the glasses: their presenter keeps today's provider calls and paces (A.3, A.4).
 */
class TorrentsScopes(
    private val host: AppHost,
    private val provider: TorrentsProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
    tableOn: Executor? = null,
    trackerOn: Executor? = null,
) : AutoCloseable {
    /** Torrents' core over this PC's live source: the record's items, the actions, the description. */
    val core = TorrentsCore { live() }

    private val ownPools = ArrayList<ExecutorService>()
    private val tableEx: Executor = tableOn ?: pool("torrents-describe")
    private val trackerEx: Executor = trackerOn ?: pool("torrents-tracker")

    /** The session's speed samples for the web's graph (§5 T2: memory only; the poll thread writes, a reader copies). */
    private val speeds = ArrayDeque<TorrentsLive.Speed>()
    /** When the provider's state line stopped being healthy (its first unhealthy line), or null while healthy. */
    @Volatile private var since: Long? = null
    /** The tracker's last answer: "ok", its failure in words, or null before anything asked it. */
    @Volatile private var trackerState: String? = null

    private val tableListeners = CopyOnWriteArrayList<() -> Unit>()
    private val trackerListeners = CopyOnWriteArrayList<() -> Unit>()

    /** The tracker's listings, a page at a time; a moved version describes the held pages again (C§9.3). */
    private val paged = Paged().also { p -> p.addListener { for (l in trackerListeners) l() } }

    private fun pool(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }.also { ownPools += it }

    /** The PC's live source as the core reads it. */
    fun live(): TorrentsLive =
        TorrentsLive(provider.snapshot(), provider.stateLine(), synchronized(speeds) { speeds.toList() }, since, trackerState)

    private val listener = object : TorrentsProvider.Listener {
        override fun snapshot(s: Snapshot) {
            synchronized(speeds) {
                if (speeds.lastOrNull()?.at == s.atMs) return
                speeds.addLast(TorrentsLive.Speed(s.atMs, s.session.dlSpeed, s.session.upSpeed))
                while (speeds.size > SPEEDS) speeds.removeFirst()
            }
            for (l in tableListeners) l()
        }

        override fun event(e: TorrentEvent) {}

        override fun state(line: String) {
            val was = since
            since = if (line.isEmpty()) null else was ?: clock()
            if ((was == null) != (since == null)) for (l in tableListeners) l()
        }
    }

    /** At a process start (both PC host builders): listens to the provider's snapshots and state line. */
    fun start() {
        provider.addListener(listener)
    }

    override fun close() {
        provider.removeListener(listener)
        for (p in ownPools) p.shutdown()
    }

    private fun state(): WindowState {
        val (_, _, e) = host.read(TorrentsCore.WINDOW)
        return WindowState(TorrentsCore.WINDOW, e.items)
    }

    // ---- the table: `desc/torrents`, `torrents/t:<hash>` ----

    val table: AppNet.Described = object : AppNet.Described {
        override fun gate(scope: String): AppNet.Gate? = when {
            scope == DESC -> AppNet.Gate(TorrentsCore.WINDOW, Levels.VIEW)
            hashOf(scope) != null -> AppNet.Gate(TorrentsCore.WINDOW, Levels.VIEW)
            else -> null
        }

        override fun describe(scope: String): JsonObject? = when {
            scope == DESC -> core.describe(state())
            else -> hashOf(scope)?.let { detail(it) }
        }

        /** The description follows every poll (the table, the speeds) and the record's rows; a transfer's files
         *  follow the polls (their progress). */
        override fun follows(scope: String, c: Change?): Boolean =
            if (c == null) true
            else scope == DESC && c.effect.items.keys.any { it.startsWith(Record.prefix(TorrentsCore.WINDOW)) }

        override fun addListener(l: () -> Unit) {
            tableListeners += l
        }

        override val describeOn: Executor get() = tableEx
    }

    /** A transfer's files and trackers, or null when qBittorrent's list (as last read) holds no such hash. */
    private fun detail(hash: String): JsonObject? {
        if (provider.snapshot()?.transfers?.none { it.hash == hash } != false) return null
        val d = provider.detail(hash)
        return buildJsonObject {
            put("hash", d.hash)
            put("files", buildJsonArray {
                for (f in d.files) add(buildJsonObject {
                    put("name", f.name); put("size", f.size); put("progress", f.progress); put("priority", f.priority)
                })
            })
            put("comment", d.comment)
            put("trackers", buildJsonArray { for (t in d.trackers) add(JsonPrimitive(t)) })
        }
    }

    // ---- the tracker: categories, listings, a torrent's page, the account ----

    val tracker: AppNet.Described = object : AppNet.Described {
        override fun gate(scope: String): AppNet.Gate? = when {
            scope == CATEGORIES || scope == ACCOUNT || fidOf(scope) != null || listingOf(scope) != null ->
                AppNet.Gate(TorrentsCore.WINDOW, Levels.FULL)
            // any other scope under Torrents' name is answered here as naming nothing (never as another window's)
            scope.startsWith("${TorrentsCore.WINDOW}/") -> AppNet.Gate(TorrentsCore.WINDOW, Levels.VIEW)
            else -> null
        }

        override fun describe(scope: String): JsonObject? {
            if (scope == CATEGORIES) return buildJsonObject {
                put("categories", buildJsonArray {
                    for (c in provider.tlCategories()) add(buildJsonObject { put("id", c.id); put("group", c.group); put("name", c.name) })
                })
            }
            if (scope == ACCOUNT) return tracked { account(provider.tlAccount()) }
            fidOf(scope)?.let { fid -> return tracked { page(provider.tlDetail(fid)) } }
            val l = listingOf(scope) ?: return null           // a scope this window does not name
            return paged.describe(l.key, l.n) {
                val p = tracked {
                    if (l.query != null) provider.tlSearch(l.query, l.n + 1, l.sort) else provider.tlBrowse(l.cat, l.n + 1, l.sort)
                }
                val per = if (p.perPage > 0) p.perPage else p.items.size
                val from = l.n.toLong() * per
                val more = if (p.total >= 0) from + p.items.size < p.total else p.items.isNotEmpty()
                Paged.Read(p.items.map(::row), p.total.takeIf { it >= 0 }, more, from)
            }
        }

        override fun follows(scope: String, c: Change?): Boolean = false

        override fun addListener(l: () -> Unit) {
            trackerListeners += l
        }

        override val describeOn: Executor get() = trackerEx

        override fun version(scope: String): Long? = listingOf(scope)?.let { paged.version(it.key) }
    }

    /** A tracker call, its answer kept as the tracker's health ("ok" or its failure in words). */
    private inline fun <T> tracked(call: () -> T): T = try {
        call().also { trackerState = "ok" }
    } catch (e: Exception) {
        trackerState = e.message ?: e::class.simpleName ?: "failed"
        throw e
    }

    private fun row(it: TlItem): JsonObject = buildJsonObject {
        put("id", it.fid)
        put("kind", "tracker")
        put("title", it.name)
        put("fields", buildJsonObject {
            put("size", it.size)
            put("seeders", it.seeders)
            put("leechers", it.leechers)
            put("snatched", it.snatched)
            put("added", it.addedAt)
            put("category", it.categoryId)
            put("freeleech", it.freeleech)
            put("tags", buildJsonArray { for (t in it.tags) add(JsonPrimitive(t)) })
        })
    }

    private fun page(d: TlDetail): JsonObject = buildJsonObject {
        put("fid", d.fid)
        put("name", d.name)
        put("category", d.category)
        put("size", d.size)
        put("seeders", d.seeders)
        put("leechers", d.leechers)
        put("snatched", d.snatched)
        put("added", d.added)
        put("uploader", d.uploader)
        put("tags", buildJsonArray { for (t in d.tags) add(JsonPrimitive(t)) })
        put("description", d.description)
        put("nfo", d.nfo)
        put("files", buildJsonArray { for (f in d.files) add(buildJsonObject { put("name", f.name); put("size", f.size) }) })
        put("url", d.url)
        put("freeleech", d.tags.any { it.equals("FREELEECH", ignoreCase = true) })
    }

    private fun account(a: TlAccount): JsonObject = buildJsonObject {
        put("user", a.user)
        put("uploaded", a.uploaded)
        put("downloaded", a.downloaded)
        put("ratio", a.ratio)
        put("points", a.points)
        put("klass", a.klass)
    }

    // ---- program ops (the spec's §2.4, §3.1; decisions A.7) ----

    /** Each op into qBittorrent, the tracker or `xdg-open`, run on the window's pool: Sent when the call returned,
     *  Refused in the program's own words when it threw (`AppNet` says the throw). Targets are checked against
     *  qBittorrent's list as last read, so a stale page never acts on a hash that has gone. */
    val program = ProgramHandler { op, _ -> act(op) }

    private fun act(op: Op): ProgramResult {
        val t = op.targets
        if (t.isEmpty()) return ProgramResult.Refused("${op.action} names no target")
        return when (op.action) {
            TorrentsCore.START, TorrentsCore.STOP, TorrentsCore.RECHECK, TorrentsCore.DELETE, TorrentsCore.DELETE_FILES -> {
                val known = provider.snapshot()?.transfers?.mapTo(HashSet()) { it.hash }
                    ?: return ProgramResult.Refused("qBittorrent's list has not been read yet")
                val gone = t.filter { it !in known }
                if (gone.isNotEmpty()) return ProgramResult.Refused("not in qBittorrent's list now: ${gone.joinToString()}")
                when (op.action) {
                    TorrentsCore.START -> provider.start(t)
                    TorrentsCore.STOP -> provider.stop(t)
                    TorrentsCore.RECHECK -> provider.recheck(t)
                    TorrentsCore.DELETE -> provider.delete(t, false)
                    else -> provider.delete(t, true)
                }
                ProgramResult.Sent()
            }
            TorrentsCore.ADD, TorrentsCore.ADD_STOPPED -> {
                val fid = t.singleOrNull()?.takeIf { FID.matches(it) }
                    ?: return ProgramResult.Refused("an add names one torrent of the tracker by its number")
                val name = tracked { provider.tlAdd(fid, op.action == TorrentsCore.ADD_STOPPED) }
                ProgramResult.Sent(buildJsonObject { put("name", name) })
            }
            TorrentsCore.OPEN_ON_PC -> {
                val target = t.singleOrNull() ?: return ProgramResult.Refused("open on the PC names one transfer or one torrent page")
                val what = openTarget(target) ?: return ProgramResult.Refused("nothing to open for '$target' in qBittorrent's list")
                provider.openOnPc(what)
                ProgramResult.Sent()
            }
            else -> ProgramResult.Refused("Torrents has no program action '${op.action}'", wm.damage.core.app.Answer.UNSUPPORTED)
        }
    }

    /** What `openOnPc` opens: a transfer's payload (`t:<hash>`, the path qBittorrent reports — never a path a page
     *  names) or a tracker page (`tl:<fid>`, the page's address as the glasses' add menu opens it). */
    private fun openTarget(target: String): String? = when {
        target.startsWith("t:") -> provider.snapshot()?.transfers?.firstOrNull { it.hash == target.removePrefix("t:") }
            ?.contentPath?.takeIf { it.isNotEmpty() }
        target.startsWith("tl:") -> target.removePrefix("tl:").takeIf { FID.matches(it) }
            ?.let { "https://www.torrentleech.org/torrent/$it" }   // `TorrentsGlasses.openAddMenu`'s own address
        else -> null
    }

    /** Main's card (the registry's running half; `AppNet.serve`). */
    fun card(): JsonObject = TorrentsCore.card(live())

    // ---- the pace per viewer (the spec's §2.3, §5 T1) ----

    /**
     * [focus] (the provider's `setFocusedBy`) is told `web:<conn>` for every connection whose Torrents viewers change:
     * focused at the fastest pace a `focus` need asks, released when it has none — its `unsub`, its page hidden, its
     * connection's end or its pings stopping (the viewers' own rule). Told on the executor.
     */
    fun watch(viewers: Viewers, focus: (who: String, focused: Boolean, paceMs: Long) -> Unit) {
        viewers.addListener { v, _, _ ->
            if (v.window != TorrentsCore.WINDOW) return@addListener
            var need: Viewers.Need? = null
            for (x in viewers.of(TorrentsCore.WINDOW, clock())) if (x.conn == v.conn) need = Viewers.stronger(need, x.need)
            val who = "$WEB${v.conn}"
            try {
                if (need?.kind == Viewers.Need.FOCUS) focus(who, true, need.paceMs ?: TorrentsPaces.DEFAULT_MS)
                else focus(who, false, 0)
            } catch (e: Exception) {
                Log.e("torrents", "the pace of $who could not be set", e)
            }
        }
    }

    /** A page scope of the tracker, parsed: its listing key (`listing:<c>/sort:<s>` or `search:<q>/sort:<s>`), page,
     *  and what it asks the tracker. */
    private data class Listing(val key: String, val n: Int, val cat: Int?, val query: String?, val sort: String)

    private fun listingOf(scope: String): Listing? {
        val (w, key, n) = Paged.parse(scope) ?: return null
        if (w != TorrentsCore.WINDOW) return null
        val m = LISTING.matchEntire(key) ?: return null
        val (kind, src, sort) = m.destructured
        if (sort !in TorrentLeech.SORTS) return null
        return if (kind == "listing") {
            val cat = if (src == "newest") null else src.toIntOrNull()?.takeIf { it > 0 } ?: return null
            Listing(key, n, cat, null, sort)
        } else {
            val q = decode(src)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            Listing(key, n, null, q, sort)
        }
    }

    companion object {
        const val DESC = "desc/torrents"
        const val CATEGORIES = "torrents/categories"
        const val ACCOUNT = "torrents/account"
        const val DETAIL = "torrents/t:"
        const val PAGE = "torrents/tl:"
        /** A web viewer's key in the provider's focus table. */
        const val WEB = "web:"
        /** The session's speed samples kept for the graph (the spec's §3.1: the last 150). */
        const val SPEEDS = 150

        private val HASH = Regex("^[A-Za-z0-9]{1,64}$")
        private val FID = Regex("^[0-9]{1,12}$")
        private val LISTING = Regex("^(listing|search):([^/]+)/sort:([a-z]+)$")

        fun hashOf(scope: String): String? = scope.takeIf { it.startsWith(DETAIL) }?.removePrefix(DETAIL)?.takeIf { HASH.matches(it) }

        fun fidOf(scope: String): String? = scope.takeIf { it.startsWith(PAGE) }?.removePrefix(PAGE)?.takeIf { FID.matches(it) }

        /** A search's words from its scope: the page encodes them as a URI component (a `/` or a space would split
         *  the scope); a `+` stays a plus. Null when the encoding is broken. */
        fun decode(s: String): String? = try {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
