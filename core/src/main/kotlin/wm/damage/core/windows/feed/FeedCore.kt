package wm.damage.core.windows.feed

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.ActionSpec
import wm.damage.core.app.ActionTable
import wm.damage.core.app.AppHost
import wm.damage.core.app.Draft
import wm.damage.core.app.HostBackend
import wm.damage.core.app.Item as RecordItem
import wm.damage.core.app.Kind
import wm.damage.core.app.Levels
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.Notices
import wm.damage.core.app.Record
import wm.damage.core.app.WindowCore
import wm.damage.core.app.WindowState
import wm.damage.core.shell.ShellSettings
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, pieces B1 and B2 (`CONTRACT.md` §2.1, §3, §4, §7, §12's Feed row): Feed's core. What a reader does —
 * the read ids per source, flags, the newest stamp a list showed, pins, browse recents, the open article's place, the
 * archive's place and the shared and glasses rows — lives in the PC record as `rec.feed.…`; the sources, items,
 * articles, comics and the archive stay the engine's (source state). The core names every action with its kind and
 * level, builds the description from the record and the PC's engine, raises the new-item notices ONCE, on the PC, and
 * hands the record's Fetch and Keep to the engine. The glasses' presenter (`FeedGlasses`) reads the record through its
 * `WindowSource`; the web page reads the same description (`FeedScopes`). No pixels here.
 */

/**
 * Feed's core. [provider] is the PC's engine (null on the phone, whose presenter reads the sources from its own
 * provider over the window channel): the description carries the sources and counts only where it is given.
 */
class FeedCore(private val provider: FeedProvider? = null, private val clock: () -> Long = { System.currentTimeMillis() }) : WindowCore {
    override val window: String = WINDOW

    override fun describe(state: WindowState): JsonObject = describe(state, provider, clock())

    override fun level(state: WindowState, addr: String): JsonObject? = null

    // ---- the PC's part: notices once, and the engine's Fetch and Keep (B2 "Notices, once") ----

    /** Each source's announced floor (memory: raised from the record's `seen` and by each announce; a PC restart may
     *  repeat one notice — accepted, `CLAUDE.md`'s three tests). */
    private val floor = ConcurrentHashMap<String, Long>()
    @Volatile private var pc: Pc? = null

    private class Pc(val host: AppHost, val notices: NoticeCore, val backend: HostBackend, val provider: FeedProvider)

    private val events = object : FeedProvider.Listener {
        override fun changed(sourceId: String, version: Long) = peek(sourceId)
        override fun state(line: String) {}
    }

    /**
     * At a PROCESS START, in the PC's two host builders only (never per stack), after the migration: listens to the
     * engine's changes and raises a source's new items into the notice record; hands the record's Fetch and Keep to
     * [configure] now and at each change of either.
     */
    fun startOnPc(host: AppHost, notices: NoticeCore, configure: (fetchMs: Long, keepMs: Long) -> Unit) {
        val p = provider ?: throw IllegalStateException("Feed's notices need the PC's engine")
        check(pc == null) { "Feed's core is started once per process" }
        pc = Pc(host, notices, HostBackend(host, Notices.PC, STREAM), p)
        val keys = setOf(Record.key(WINDOW, FETCH), Record.key(WINDOW, KEEP))
        fun apply() {
            val st = WindowState(WINDOW, host.read(WINDOW).third.items)
            try {
                configure(fetchMin(st) * 60_000L, keepDays(st) * 86_400_000L)
            } catch (e: Exception) {
                Log.e(TAG, "the engine did not take Fetch and Keep", e)
            }
        }
        host.executor.call { apply() }
        host.addListener { c -> if (c.effect.items.keys.any { it in keys }) apply() }
        p.addListener(events)
    }

    /** At a process end (tests): the engine is no longer listened to. */
    fun stop() {
        pc?.provider?.removeListener(events)
    }

    /** A source changed (the engine's thread): its first page is read here, the rule runs on the executor. */
    private fun peek(sourceId: String) {
        val p = pc ?: return
        val src = try {
            p.provider.sources().firstOrNull { it.id == sourceId }
        } catch (e: Exception) {
            Log.w(TAG, "sources: ${e.message}")
            null
        } ?: return
        if (src.binge) return                                  // the archive raises nothing
        val page = try {
            p.provider.items(sourceId, 0, PEEK).items
        } catch (e: Exception) {
            Log.w(TAG, "items of $sourceId for its notice: ${e.message}")
            return
        }
        p.host.executor.execute("feed announce $sourceId") { announce(p, src, page) }
    }

    /** Today's `FeedWindow.announce` rule, on the executor: a first sight writes `seen` and raises nothing; else the
     *  unread items newer than `max(seen, floor)` are one notice, raised only while the source's Notify row is on. */
    private fun announce(p: Pc, src: SourceStatus, page: List<Item>) {
        val newest = page.maxOfOrNull { it.publishedMs } ?: return
        val st = WindowState(WINDOW, p.host.read(WINDOW).third.items)
        val seen = seenOf(st, src.id)
        if (seen == null) {
            floor[src.id] = maxOf(floor[src.id] ?: 0L, newest)
            p.backend.submit(WINDOW, seenDraft(src.id, newest))
            return
        }
        if (notifyFor(st, src.kind) != true) return             // absent = off (FEED.md verdict 13)
        val read = readOf(st, src.id).toHashSet()
        val f = maxOf(seen, floor[src.id] ?: 0L)
        val fresh = page.filter { it.publishedMs > f && it.id !in read }.sortedByDescending { it.publishedMs }
        if (fresh.isEmpty()) return
        val head = fresh.first()
        floor[src.id] = head.publishedMs
        val body = if (fresh.size == 1) "${src.name} · ${head.title}" else "${fresh.size} new · ${src.name} · ${head.title}"
        val target = if (fresh.size == 1) "item:${src.id}:${head.id}" else "src:${src.id}"
        try {
            p.notices.raise(NOTICE_SOURCE, src.id, body, WINDOW, target, GATED_BY)
        } catch (e: Exception) {
            Log.e(TAG, "the notice of ${src.id} could not be raised", e)
        }
    }

    /** A flag's facts (B1 `{t, l, s, p, k, a}`, so a pruned item still lists). */
    data class Flag(val title: String, val link: String, val source: String, val publishedMs: Long, val kind: ItemKind, val atMs: Long) {
        fun json(): JsonObject = buildJsonObject {
            put("t", title); put("l", link); put("s", source); put("p", publishedMs); put("k", kind.name); put("a", atMs)
        }
    }
    /** The open article's place (B1 `{item, b, o, v}`): block index, offset in the block's text, extractor version. */
    data class Place(val item: String, val b: Int, val o: Int, val v: Int) {
        fun json(): JsonObject = buildJsonObject { put("item", item); put("b", b); put("o", o); put("v", v) }
    }
    /** The archive's place (B1 `{ep, f, s, w}`): episode, fraction into it (the web's), strip (the glasses', used
     *  only where [w] equals the glasses' width). */
    data class Binge(val ep: Int, val f: Double?, val s: Int?, val w: Int?) {
        fun json(): JsonObject = buildJsonObject {
            put("ep", ep)
            if (f == null) put("f", JsonNull) else put("f", f)
            if (s == null) put("s", JsonNull) else put("s", s)
            if (w == null) put("w", JsonNull) else put("w", w)
        }
    }

    companion object {
        private const val TAG = "feed"
        const val WINDOW = "feed"
        /** The PC's own writes (a first sight's `seen`) go in this stream as device [Notices.PC]. */
        const val STREAM = "feed"
        /** The first page a count and an announce read (today's `FeedWindow.PEEK`). */
        const val PEEK = 60
        const val READ_MAX = 600
        const val RECENTS_MAX = 5

        // the record items (B1)
        const val READ = "read."
        const val FLAG = "flag."
        const val SEEN = "seen."
        const val PLACE = "place"
        const val BINGE = "binge."
        const val PIN = "pin."
        const val RECENT = "recent."
        const val FETCH = "fetch"
        const val KEEP = "keep"
        const val NOTIFY_REDDIT = "notify.reddit"
        const val NOTIFY_SLASHDOT = "notify.slashdot"
        const val NOTIFY_COMICS = "notify.comics"
        /** The glasses' Feed height: 0 = the Global height, else one of the four sizes. */
        const val SIZE = "glasses.size"
        const val ART_XKCD = "glasses.art.xkcd"
        const val ART_SMBC = "glasses.art.smbc"
        const val ART_BINGE = "glasses.art.binge"
        const val LEVELS = "glasses.levels"

        /** The notice's source and category: today's (`DAMAGE · feed`, gated by Settings → Feed). */
        const val NOTICE_SOURCE = "feed"
        const val GATED_BY = "Feed"

        // the actions (the page's ids, `S1-3-P-built.md` A)
        const val A_READ = "read"
        const val A_MARK_READ = "markRead"
        const val A_UNREAD = "unread"
        const val A_MARK_ALL = "markAllRead"
        const val A_SEEN = "seen"
        const val A_FLAG = "flag"
        const val A_UNFLAG = "unflag"
        const val A_PIN = "pin"
        const val A_UNPIN = "unpin"
        const val A_RECENT = "recent"
        const val A_SET = "set"
        const val A_PLACE = "place"
        const val A_BINGE = "binge"
        const val A_REFRESH = "refresh"
        const val A_BROWSE = "browse"
        /** A register's value from before Feed's conversion, stamped with its old time (B7). */
        const val MIGRATE = "migrate"

        /** The program ops (outbound: they go to the sites, C§4.2), never queued. */
        val PROGRAM: Set<String> = setOf(A_REFRESH, A_BROWSE)

        /** The level [action] needs: the reading effects view (C§4.2), a reader's other marks and the rows control, a
         *  browse's recents and the program ops full. Null: no such action. */
        fun levelOf(action: String): String? = when (action) {
            A_READ, A_SEEN, A_PLACE, A_BINGE -> Levels.VIEW
            A_MARK_READ, A_UNREAD, A_MARK_ALL, A_FLAG, A_UNFLAG, A_PIN, A_UNPIN, A_SET, MIGRATE -> Levels.CONTROL
            A_RECENT, A_REFRESH, A_BROWSE -> Levels.FULL
            else -> null
        }

        /** Feed's record actions as the record judges them (C§2.4). */
        val ACTIONS = ActionTable { w, a ->
            if (w != WINDOW) null else when (a) {
                A_READ, A_MARK_READ, A_UNREAD, A_MARK_ALL, A_SEEN, A_FLAG, A_PIN, A_UNPIN, A_RECENT, A_SET -> ActionSpec(Kind.REGISTER)
                A_UNFLAG -> ActionSpec(Kind.REMOVE)
                A_PLACE, A_BINGE, MIGRATE -> ActionSpec(Kind.POSITION)
                else -> null
            }
        }

        private data class ActionDesc(val id: String, val label: String, val kind: String, val into: String, val targets: String,
                                      val params: JsonObject? = null)

        private val ACTION_LIST: List<ActionDesc> = listOf(
            ActionDesc(A_READ, "Read", "r", "record", "one"),
            ActionDesc(A_MARK_READ, "Mark read", "r", "record", "one"),
            ActionDesc(A_UNREAD, "Mark unread", "r", "record", "one"),
            ActionDesc(A_MARK_ALL, "Mark all read", "r", "record", "one"),
            ActionDesc(A_SEEN, "Seen", "register", "record", "one"),
            ActionDesc(A_FLAG, "Flag", "r", "record", "one"),
            ActionDesc(A_UNFLAG, "Unflag", "r", "record", "one"),
            ActionDesc(A_PIN, "Pin", "r", "record", "one"),
            ActionDesc(A_UNPIN, "Unpin", "r", "record", "one"),
            ActionDesc(A_RECENT, "Keep the browse", "register", "record", "one"),
            ActionDesc(A_SET, "Set a row", "register", "record", "one"),
            ActionDesc(A_PLACE, "The article's place", "register", "record", "one"),
            ActionDesc(A_BINGE, "The archive's place", "register", "record", "one"),
            ActionDesc(A_REFRESH, "Refresh", "o", "program", "many"),
            // params {kind: reddit | slashdot, name}: a subreddit is typed; a Slashdot section is one of these (the
            // page's `browseBox` reads them)
            ActionDesc(A_BROWSE, "Browse", "o", "program", "one", buildJsonObject {
                put("choice", buildJsonObject {
                    put("options", buildJsonArray {
                        for (sec in SlashdotRss.SECTIONS) add(buildJsonObject { put("id", sec); put("label", sec.lowercase()) })
                    })
                })
            }),
        )

        // ---- the settings rows (today's Settings → Feed, `PC loss` gone: B5) ----

        val FETCHES = linkedMapOf("5 min" to 5, "15 min" to 15, "30 min" to 30, "60 min" to 60)
        val KEEPS = linkedMapOf("7 d" to 7, "30 d" to 30, "90 d" to 90)
        val ARTS = listOf("auto", "never", "always")
        val LEVEL_CHOICES = listOf(16, 8, 4)

        // ---- the record's items, read ----

        private fun live(state: WindowState, name: String): RecordItem? = state.itemNamed(name)?.takeIf { !it.gone }

        fun bool(v: JsonElement?): Boolean? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        fun int(v: JsonElement?): Int? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        fun long(v: JsonElement?): Long? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        fun text(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /** A list value of strings (read ids, recents), or null when it is not one. */
        fun strings(v: JsonElement?): List<String>? =
            (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotEmpty() } }

        /** [src]'s read ids, newest last (empty when the record holds none). */
        fun readOf(state: WindowState, src: String): List<String> = strings(live(state, READ + src)?.value) ?: emptyList()

        fun seenOf(state: WindowState, src: String): Long? = long(live(state, SEEN + src)?.value)

        fun pinned(state: WindowState, src: String): Boolean = bool(live(state, PIN + src)?.value) == true

        fun recents(state: WindowState, kind: String): List<String> =
            strings(live(state, RECENT + kind)?.value)?.distinct()?.take(RECENTS_MAX) ?: emptyList()

        fun fetchMin(state: WindowState): Int = int(live(state, FETCH)?.value)?.takeIf { it in FETCHES.values } ?: 15
        fun keepDays(state: WindowState): Int = int(live(state, KEEP)?.value)?.takeIf { it in KEEPS.values } ?: 30
        fun notify(state: WindowState, row: String): Boolean? = bool(live(state, row)?.value)

        /** The Notify row a source kind answers to (today's `notifyFor`), read; null = absent (off). */
        fun notifyFor(state: WindowState, kind: SourceKind): Boolean? = when (kind) {
            SourceKind.REDDIT -> notify(state, NOTIFY_REDDIT)
            SourceKind.SLASHDOT -> notify(state, NOTIFY_SLASHDOT)
            SourceKind.XKCD, SourceKind.SMBC, SourceKind.RSS -> notify(state, NOTIFY_COMICS)
            SourceKind.EIGHTBIT -> false
        }

        fun sizeOf(v: JsonElement?): Int? = int(v)?.takeIf { it == 0 || it in ShellSettings.HEIGHTS }
        fun artOf(v: JsonElement?): LineArt? = text(v)?.let { s -> LineArt.entries.firstOrNull { it.name.equals(s, true) } }
        fun levelsOf(v: JsonElement?): Int? = int(v)?.takeIf { it in LEVEL_CHOICES }


        fun flagOf(v: JsonElement?, src: String = ""): Flag? {
            val o = v as? JsonObject ?: return null
            return Flag(text(o["t"]) ?: "", text(o["l"]) ?: "", text(o["s"]) ?: src, long(o["p"]) ?: 0L,
                text(o["k"])?.let { k -> ItemKind.entries.firstOrNull { it.name.equals(k, true) } } ?: ItemKind.LINK, long(o["a"]) ?: 0L)
        }

        /** Every flag the record holds, by item id. */
        fun flags(state: WindowState): Map<String, Flag> {
            val p = Record.key(WINDOW, FLAG)
            val out = LinkedHashMap<String, Flag>()
            for ((k, it) in state.items) if (k.startsWith(p) && !it.gone) flagOf(it.value)?.let { f -> out[k.removePrefix(p)] = f }
            return out
        }


        fun placeOf(v: JsonElement?): Place? {
            val o = v as? JsonObject ?: return null
            val item = text(o["item"])?.takeIf { it.isNotEmpty() } ?: return null
            return Place(item, int(o["b"])?.coerceAtLeast(0) ?: 0, int(o["o"])?.coerceAtLeast(0) ?: 0, int(o["v"]) ?: 0)
        }


        fun bingeOf(v: JsonElement?): Binge? {
            val o = v as? JsonObject ?: return null
            val ep = int(o["ep"])?.takeIf { it > 0 } ?: return null
            return Binge(ep, (o["f"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.coerceIn(0.0, 1.0),
                int(o["s"])?.coerceAtLeast(0), int(o["w"]))
        }

        // ---- the drafts a presenter sends (C§2.4: the result; the latest input wins, L1 C1) ----

        /** [action] on [src]'s whole read list (newest last; the oldest let go past [READ_MAX]). */
        fun readDraft(action: String, src: String, list: Collection<String>, at: Long? = null) =
            Draft(action, listOf(READ + src), JsonArray(list.toList().takeLast(READ_MAX).map { JsonPrimitive(it) }), at = at)

        fun seenDraft(src: String, ms: Long, at: Long? = null) = Draft(A_SEEN, listOf(SEEN + src), JsonPrimitive(ms), at = at)
        fun flagDraft(id: String, f: Flag, at: Long? = null) = Draft(A_FLAG, listOf(FLAG + id), f.json(), at = at)
        fun unflagDraft(id: String, at: Long? = null) = Draft(A_UNFLAG, listOf(FLAG + id), JsonNull, at = at)
        fun pinDraft(src: String, on: Boolean, at: Long? = null) = Draft(if (on) A_PIN else A_UNPIN, listOf(PIN + src), JsonPrimitive(on), at = at)
        fun recentDraft(kind: String, list: List<String>, at: Long? = null) =
            Draft(A_RECENT, listOf(RECENT + kind), JsonArray(list.take(RECENTS_MAX).map { JsonPrimitive(it) }), at = at)
        fun setDraft(row: String, value: JsonElement, at: Long? = null) = Draft(A_SET, listOf(row), value, at = at)
        fun placeDraft(p: Place, at: Long?) = Draft(A_PLACE, listOf(PLACE), p.json(), at = at)
        fun bingeDraft(src: String, b: Binge, at: Long?) = Draft(A_BINGE, listOf(BINGE + src), b.json(), at = at)

        // ---- the description (B2 `desc/feed`) ----

        /** A source's new count: unread items of its first page newer than its `seen` (today's `recount`). */
        fun newCount(state: WindowState, src: String, page: List<Item>): Int {
            val seen = seenOf(state, src) ?: 0L
            val read = readOf(state, src).toHashSet()
            return page.count { it.publishedMs > seen && it.id !in read }
        }

        /** Each listed source's new count (the binge has none), from its first page. */
        fun counts(state: WindowState, provider: FeedProvider, sources: List<SourceStatus>): Map<String, Int> {
            val out = LinkedHashMap<String, Int>()
            for (s in sources) {
                if (s.binge || s.count == 0) continue
                val page = try { provider.items(s.id, 0, PEEK).items } catch (e: Exception) { continue }
                out[s.id] = newCount(state, s.id, page)
            }
            return out
        }

        /** A source's health as values (C§8: no age baked into text): ok, limited (until `retryAtMs`, by `host`), or
         *  failing (since `failSinceMs`, the engine's own line). */
        fun health(s: SourceStatus, now: Long): JsonObject = buildJsonObject {
            when {
                s.retryAtMs > now -> {
                    put("state", "limited"); put("retryAtMs", s.retryAtMs)
                    if (s.retryHost.isNotEmpty()) put("host", s.retryHost)
                }
                s.failLine.isNotEmpty() -> {
                    put("state", "failing"); put("failLine", s.failLine)
                    if (s.failSinceMs > 0) put("failSinceMs", s.failSinceMs)
                }
                else -> put("state", "ok")
            }
        }

        private fun summaryOf(sources: List<SourceStatus>, counts: Map<String, Int>, now: Long, away: Boolean): JsonObject = buildJsonObject {
            val total = counts.values.sum()
            val parts = sources.filter { (counts[it.id] ?: 0) > 0 }.take(3).joinToString(" · ") { "${it.name.lowercase()} ${counts[it.id]}" }
            put("line", when { away -> "the sources are the PC's"; sources.isEmpty() -> "connecting"; total > 0 -> "$total new"; else -> "nothing new" })
            put("detail", parts.ifEmpty { "${sources.size} sources" })
            put("more", total > 0)
            put("counts", buildJsonObject { put("new", total); put("sources", sources.size) })
            put("asOf", now)
        }

        /** The description from [state] and the PC's engine (absent on the phone: the record's rows only). */
        fun describe(state: WindowState, provider: FeedProvider?, now: Long): JsonObject = buildJsonObject {
            val sources = try { provider?.sources() } catch (e: Exception) { null }
            val counts = if (provider != null && sources != null) counts(state, provider, sources) else emptyMap()
            put("id", WINDOW)
            put("name", "Feed")
            put("icon", "feed")
            put("min", Levels.VIEW)
            put("title", "feed")
            when {
                provider == null -> { put("availability", "away"); put("reason", "the sources are the PC's") }
                sources == null -> { put("availability", "waiting"); put("reason", "the engine did not list its sources") }
                else -> put("availability", "live")
            }
            put("summary", summaryOf(sources.orEmpty(), counts, now, provider == null))
            put("sources", buildJsonArray {
                for (s in sources.orEmpty()) add(buildJsonObject {
                    put("id", s.id); put("kind", s.kind.name); put("name", s.name); put("binge", s.binge)
                    put("transient", s.transient); put("pinned", pinned(state, s.id)); put("count", s.count)
                    put("newCount", counts[s.id] ?: 0); put("version", s.version); put("lastOkMs", s.lastOkMs)
                    put("lastFetchMs", s.lastFetchMs); put("newestMs", s.newestMs); put("health", health(s, now))
                    if (s.binge) put("bingeTotal", s.bingeTotal)
                })
            })
            put("actions", buildJsonArray {
                for (a in ACTION_LIST) add(buildJsonObject {
                    put("id", a.id); put("label", a.label); put("kind", a.kind); put("level", levelOf(a.id) ?: Levels.FULL)
                    put("into", a.into); put("targets", a.targets)
                    a.params?.let { put("params", it) }
                })
            })
            put("settings", settings(state))
        }

        private fun choice(id: String, label: String, scope: String, options: List<Pair<JsonElement, String>>, value: JsonElement, level: String) =
            buildJsonObject {
                put("id", id); put("label", label); put("scope", scope); put("type", "choice")
                put("options", buildJsonArray { for ((o, l) in options) add(buildJsonObject { put("id", o); put("label", l) }) })
                put("value", value); put("level", level)
            }

        /** The rows (C§6), in Settings → Feed's order: Size, Images (this device's), the three art rows and Comic
         *  levels (the glasses'), Fetch and Keep, the three Notify rows (shared; absent = off). */
        fun settings(state: WindowState): JsonArray = buildJsonArray {
            add(choice(SIZE, "Size (glasses)", "glasses",
                listOf(JsonPrimitive(0) to "global") + ShellSettings.HEIGHTS.map { JsonPrimitive(it) to "$it" },
                JsonPrimitive(sizeOf(live(state, SIZE)?.value) ?: 0), Levels.CONTROL))
            add(buildJsonObject {
                put("id", "images"); put("label", "Images"); put("scope", "device"); put("type", "bool")
                put("value", true); put("level", Levels.VIEW)
            })
            for ((row, label) in listOf(ART_XKCD to "xkcd art", ART_SMBC to "SMBC art", ART_BINGE to "8-Bit art"))
                add(choice(row, label, "glasses", ARTS.map { JsonPrimitive(it) to it },
                    JsonPrimitive((artOf(live(state, row)?.value) ?: LineArt.AUTO).name.lowercase()), Levels.CONTROL))
            add(choice(LEVELS, "Comic levels", "glasses", LEVEL_CHOICES.map { JsonPrimitive(it) to "$it" },
                JsonPrimitive(levelsOf(live(state, LEVELS)?.value) ?: 16), Levels.CONTROL))
            add(choice(FETCH, "Fetch", "shared", FETCHES.map { (l, m) -> JsonPrimitive(m) to l }, JsonPrimitive(fetchMin(state)), Levels.CONTROL))
            add(choice(KEEP, "Keep", "shared", KEEPS.map { (l, d) -> JsonPrimitive(d) to l }, JsonPrimitive(keepDays(state)), Levels.CONTROL))
            for ((row, label) in listOf(NOTIFY_REDDIT to "Notify · Reddit", NOTIFY_SLASHDOT to "Notify · Slashdot", NOTIFY_COMICS to "Notify · comics"))
                add(buildJsonObject {
                    put("id", row); put("label", label); put("scope", "shared"); put("type", "bool")
                    put("value", notify(state, row) ?: false); put("level", Levels.CONTROL)
                })
        }

        /** Main's card (C§3.1; the page's home): the summary the glasses' Main row shows. */
        fun card(state: WindowState, provider: FeedProvider?, now: Long): JsonObject = buildJsonObject {
            val sources = try { provider?.sources() } catch (e: Exception) { null }
            val counts = if (provider != null && sources != null) counts(state, provider, sources) else emptyMap()
            put("window", WINDOW)
            put("name", "Feed")
            put("icon", "feed")
            put("min", Levels.VIEW)
            put("availability", if (sources == null) "waiting" else "live")
            if (sources == null) put("reason", "the engine did not list its sources")
            val s = summaryOf(sources.orEmpty(), counts, now, provider == null)
            put("summary", buildJsonObject { put("line", s["line"]!!); put("detail", s["detail"]!!); put("more", s["more"]!!) })
        }
    }
}
