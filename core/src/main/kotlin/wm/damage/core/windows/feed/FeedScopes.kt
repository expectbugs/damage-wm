package wm.damage.core.windows.feed

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.Change
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.Paged
import wm.damage.core.app.ProgramHandler
import wm.damage.core.app.ProgramResult
import wm.damage.core.app.Record
import wm.damage.core.app.WindowState

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B2 (`CONTRACT.md` §3, §8, §9.3): Feed's PC-served parts on the `app` lane, made once per process
 * by the PC's two host builders over the PC's engine.
 * - [table] (its own describe executor): `desc/feed` — the sources with their counts and health, the actions, the
 *   rows — and `feed/src:<id>/page:<n>`, a source's items 50 at a time at the source's version. Read from the engine's
 *   memory: quick.
 * - [reading] (another executor, so a slow site never holds the source list, nor Reader's, Torrents' or Files'
 *   parts): an article (`feed/item:<src>:<id>`, Damage's own text with its spans), its comments, a comic (by item or
 *   by number) and the archive around an episode. Each fetches what it needs at view (one page fetch at a time for
 *   an archive picture not yet known); the fetch supervisor (B4) bounds a site that stops answering.
 * - [program]: refresh and browse (outbound, never queued).
 * Nothing here reaches the glasses: their presenter keeps today's provider calls.
 */
class FeedScopes(
    private val host: AppHost,
    private val provider: FeedProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
    tableOn: Executor? = null,
    readingOn: Executor? = null,
) : AutoCloseable {
    /** Feed's core over this PC's engine: the record's items, the actions, the description, the notices. */
    val core = FeedCore(provider, clock)
    /** The pictures the scopes hand out (`feed.<key>`). */
    val blobs = FeedBlobs(provider)

    private val ownPools = ArrayList<ExecutorService>()
    private val tableEx: Executor = tableOn ?: pool("feed-describe")
    private val readingEx: Executor = readingOn ?: pool("feed-reading")
    private val tableListeners = CopyOnWriteArrayList<() -> Unit>()

    private fun pool(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }.also { ownPools += it }

    private val listener = object : FeedProvider.Listener {
        override fun changed(sourceId: String, version: Long) { for (l in tableListeners) l() }
        override fun state(line: String) {}
    }

    /** At a process start (both PC host builders): the source list follows the engine. */
    fun start() {
        provider.addListener(listener)
    }

    override fun close() {
        provider.removeListener(listener)
        for (p in ownPools) p.shutdown()
    }

    private fun state(): WindowState = WindowState(FeedCore.WINDOW, host.read(FeedCore.WINDOW).third.items)

    // ---- the table: `desc/feed`, `feed/src:<id>/page:<n>` ----

    val table: AppNet.Described = object : AppNet.Described {
        override fun gate(scope: String): AppNet.Gate? =
            if (scope == DESC || pageOf(scope) != null) AppNet.Gate(FeedCore.WINDOW, Levels.VIEW) else null

        override fun describe(scope: String): JsonObject? {
            if (scope == DESC) return core.describe(state())
            val (src, n) = pageOf(scope) ?: return null
            if (provider.sources().none { it.id == src }) return null
            val p = provider.items(src, n * PAGE, PAGE)
            val from = n.toLong() * PAGE
            return Paged.page(Paged.Read(p.items.map(::row), p.total, from + p.items.size < p.total, from), from, p.version)
        }

        /** The description follows the engine and the record's Feed items (counts, pins, rows); a page follows its
         *  source's version. */
        override fun follows(scope: String, c: Change?): Boolean =
            scope == DESC && (c == null || c.effect.items.keys.any { it.startsWith(Record.prefix(FeedCore.WINDOW)) })

        override fun addListener(l: () -> Unit) {
            tableListeners += l
        }

        override val describeOn: Executor get() = tableEx

        override fun version(scope: String): Long? =
            pageOf(scope)?.let { (src, _) -> provider.sources().firstOrNull { it.id == src }?.version }
    }

    /** An item as a page's row (the glasses' row and lens facts, typed). */
    private fun row(it: Item): JsonObject = buildJsonObject {
        put("id", it.id)
        put("source", it.source)
        put("title", it.title)
        put("link", it.link)
        put("target", it.target)
        put("author", it.author)
        put("published", it.publishedMs)
        put("itemKind", it.kind.name)
        put("comments", it.comments)
        put("hasComments", it.commentsUrl.isNotEmpty())
        put("extra", it.extra)
        if (it.num > 0) put("num", it.num)
    }

    // ---- reading: an article, its comments, a comic, the archive ----

    val reading: AppNet.Described = object : AppNet.Described {
        override fun gate(scope: String): AppNet.Gate? = when {
            // any other scope under Feed's name is answered here as naming nothing (never as another window's)
            scope.startsWith("${FeedCore.WINDOW}/") && scope != DESC && pageOf(scope) == null -> AppNet.Gate(FeedCore.WINDOW, Levels.VIEW)
            else -> null
        }

        override fun describe(scope: String): JsonObject? {
            val t = scope.removePrefix("${FeedCore.WINDOW}/")
            BINGE.matchEntire(t)?.let { m -> return binge(m.groupValues[1], m.groupValues[2].toInt()) }
            // `<head>:<src>:<id>`: a browsed source's id holds a ':' (`r:linux`), an item id or a number never does
            val head = t.substringBefore(':')
            val rest = t.substringAfter(':', "")
            val src = rest.substringBeforeLast(':', "")
            val id = rest.substringAfterLast(':', "")
            if (src.isEmpty() || id.isEmpty()) return null
            return when (head) {
                "item" -> article(src, id)
                "comments" -> comments(src, id)
                "comic" -> provider.comicItem(id)?.takeIf { it.source == src }?.let { comic(it) }
                "num" -> id.toIntOrNull()?.takeIf { it > 0 }?.let { n -> provider.comicAt(src, n)?.let { comic(it) } }
                else -> null
            }
        }

        override fun follows(scope: String, c: Change?): Boolean = false

        override fun addListener(l: () -> Unit) {}

        override val describeOn: Executor get() = readingEx
    }

    /** An article as `{item, document, extracted, note}`: the item's facts and the document in Damage's own text with
     *  its spans (C§3.2). A flagged item the engine pruned is read from its page (today's glasses' path). */
    private fun article(src: String, id: String): JsonObject? {
        val it = provider.item(id)?.takeIf { it.source == src }
        val a: Article
        val facts: JsonObject
        if (it != null) {
            a = provider.article(id)
            facts = row(it).let { r -> JsonObject(r + ("summary" to JsonPrimitive(it.summary))) }
        } else {
            val f = FeedCore.flags(state())[id]?.takeIf { f -> f.source == src && f.link.isNotEmpty() } ?: return null
            a = provider.articleByUrl(f.link, f.title)
            facts = buildJsonObject {
                put("id", id); put("source", src); put("title", f.title); put("link", f.link); put("target", f.link)
                put("published", f.publishedMs); put("itemKind", f.kind.name); put("comments", -1); put("hasComments", false)
            }
        }
        return buildJsonObject {
            put("item", facts)
            put("document", document("item:$src:$id", a))
            put("extracted", a.extracted)
            put("note", a.note)
        }
    }

    /** C§3.2's document: Feed's block kinds as the contract's, each with its spans (a block without = one plain span). */
    private fun document(id: String, a: Article): JsonObject = buildJsonObject {
        put("kind", "document")
        put("id", id)
        put("extractor", a.ver)
        put("sourceUrl", a.sourceUrl)
        put("blocks", buildJsonArray { for ((i, b) in a.blocks.withIndex()) add(block(i, b)) })
    }

    private fun block(i: Int, b: Block): JsonObject = buildJsonObject {
        put("id", "b$i")
        put("kind", when (b.kind) { "h" -> "heading"; "li" -> "item"; "q" -> "quote"; "pre" -> "mono"; "img" -> "image"; else -> "para" })
        if (b.kind == "h" || b.kind == "li") put("level", 1)
        if (b.kind == "img") {
            put("ref", blobs.idOf(b.url))
            put("alt", b.text)
            put("spans", JsonArray(emptyList()))
            return@buildJsonObject
        }
        put("spans", spans(b.text, b.spans))
    }

    private fun spans(text: String, spans: List<Span>): JsonArray = buildJsonArray {
        for (s in spans.ifEmpty { listOf(Span(text)) }) add(buildJsonObject {
            put("text", s.text)
            put("marks", buildJsonArray {
                if (s.marks and Span.EM != 0) add(JsonPrimitive("em"))
                if (s.marks and Span.STRONG != 0) add(JsonPrimitive("strong"))
                if (s.marks and Span.CODE != 0) add(JsonPrimitive("code"))
                if (s.href.isNotEmpty()) add(JsonPrimitive("link"))
            })
            if (s.href.isNotEmpty()) put("href", s.href)
        })
    }

    /** The comments as a thread: `{entries[{id, author, published, depth, title?, score?, blocks}]}`. */
    private fun comments(src: String, id: String): JsonObject? {
        provider.item(id)?.takeIf { it.source == src } ?: return null
        val cs = provider.comments(id)
        return buildJsonObject {
            put("entries", buildJsonArray {
                for ((i, c) in cs.withIndex()) add(buildJsonObject {
                    put("id", "c$i")
                    put("author", c.author)
                    put("published", c.publishedMs)
                    put("depth", c.depth)
                    if (c.title.isNotEmpty()) put("title", c.title)
                    if (c.score.isNotEmpty()) put("score", c.score)
                    put("blocks", buildJsonArray {
                        for ((k, para) in c.text.split('\n').filter { it.isNotBlank() }.withIndex())
                            add(buildJsonObject { put("id", "b$k"); put("kind", "para"); put("spans", spans(para.trim(), emptyList())) })
                    })
                })
            })
        }
    }

    /** A comic (C§3.4): the item, its original picture, alt text, SMBC's bonus, its number and its neighbours — each a
     *  target to open, xkcd's random a range, or dim with the reason (today's bar). */
    private fun comic(it: Item): JsonObject = buildJsonObject {
        put("item", row(it))
        put("image", if (it.image.isEmpty()) JsonNull else JsonPrimitive(blobs.idOf(it.image)))
        put("alt", it.alt)
        put("bonus", if (it.bonus.isEmpty()) JsonNull else JsonPrimitive(blobs.idOf(it.bonus)))
        if (it.num > 0) put("num", it.num)
        put("neighbours", neighbours(it))
    }

    private fun neighbours(it: Item): JsonObject = buildJsonObject {
        fun to(t: String) = buildJsonObject { put("target", t) }
        fun dim(why: String) = buildJsonObject { put("dim", why) }
        val range = try { provider.comicRange(it.source) } catch (e: Exception) { null }
        if (range != null && it.num > 0) {
            fun num(n: Int) = to("num:${it.source}:$n")
            put("first", if (it.num <= range.first) dim("this is the first") else num(range.first))
            put("prev", if (it.num <= range.first) dim("this is the first") else num(it.num - 1))
            put("random", if (range.last > range.first) buildJsonObject { put("range", buildJsonArray { add(JsonPrimitive(range.first)); add(JsonPrimitive(range.last)) }) } else dim("one strip"))
            put("next", if (it.num >= range.last) dim("this is the latest") else num(it.num + 1))
            put("latest", if (it.num >= range.last) dim("this is the latest") else num(range.last))
            return@buildJsonObject
        }
        // by list position (newest first), as the glasses flip a source that is not numbered
        val all = provider.items(it.source, 0, Int.MAX_VALUE).items
        val i = all.indexOfFirst { x -> x.id == it.id }
        fun item(x: Item?, why: String) = if (x == null) dim(why) else to("item:${x.source}:${x.id}")
        put("first", item(all.lastOrNull()?.takeIf { i >= 0 && i < all.size - 1 }, "this is the oldest kept"))
        put("prev", item(all.getOrNull(i + 1)?.takeIf { i >= 0 }, "this is the oldest kept"))
        val others = all.filter { x -> x.id != it.id }
        // "random", stable per strip: one of the others, chosen by this strip's id
        put("random", item(others.getOrNull(Math.floorMod(it.id.hashCode(), maxOf(1, others.size))), "one strip"))
        put("next", item(all.getOrNull(i - 1)?.takeIf { i > 0 }, "this is the latest"))
        put("latest", item(all.firstOrNull()?.takeIf { i > 0 }, "this is the latest"))
    }

    /** The archive around [ep] (C§3.4's paged sequence): 3 episodes before and 5 after; an episode below the first is
     *  answered around the first. A picture not yet known is found on its page here, one fetch at a time; one that
     *  could not be is `failed` with the reason. */
    private fun binge(src: String, ep: Int): JsonObject? {
        val s = provider.sources().firstOrNull { it.id == src }?.takeIf { it.binge } ?: return null
        val eps = provider.bingeIndex(s.id)
        if (eps.isEmpty()) return buildJsonObject { put("total", 0); put("entries", JsonArray(emptyList())) }
        val at = eps.indexOfFirst { it.num >= ep }.let { if (it < 0) eps.lastIndex else it }
        val window = eps.subList((at - BEFORE).coerceAtLeast(0), (at + AFTER + 1).coerceAtMost(eps.size))
        return buildJsonObject {
            put("total", eps.size)
            put("first", eps.first().num)
            put("last", eps.last().num)
            put("entries", buildJsonArray {
                for (e in window) add(buildJsonObject {
                    put("num", e.num); put("title", e.title); put("date", e.dateMs)
                    val known = if (e.image.isNotEmpty()) e else try {
                        provider.bingeResolve(s.id, e.num)
                    } catch (x: Exception) {
                        put("image", JsonNull); put("state", "failed"); put("reason", x.message ?: x::class.simpleName ?: "failed")
                        return@buildJsonObject
                    }
                    if (known.image.isEmpty()) { put("image", JsonNull); put("state", "failed"); put("reason", "no picture on its page") }
                    else { put("image", blobs.idOf(known.image)); put("state", "ready") }
                })
            })
        }
    }

    // ---- program ops (outbound, B2): refresh, browse ----

    /** Refresh (the sources named, or every one) and browse (`{kind: reddit | slashdot, name}` → the source id), each
     *  Sent when the engine took it, Refused in its words. */
    val program = ProgramHandler { op, _ -> act(op) }

    private fun act(op: Op): ProgramResult = when (op.action) {
        FeedCore.A_REFRESH -> {
            val known = provider.sources().mapTo(HashSet()) { it.id }
            val gone = op.targets.filter { it !in known }
            if (gone.isNotEmpty()) ProgramResult.Refused("no such source now: ${gone.joinToString()}")
            else {
                if (op.targets.isEmpty()) provider.refresh(null) else for (t in op.targets) provider.refresh(t)
                ProgramResult.Sent()
            }
        }
        FeedCore.A_BROWSE -> {
            val kind = when ((op.params["kind"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                "reddit" -> SourceKind.REDDIT
                "slashdot" -> SourceKind.SLASHDOT
                else -> null
            }
            val name = (op.params["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (kind == null || name.isEmpty()) ProgramResult.Refused("a browse names a kind (reddit or slashdot) and a name")
            else ProgramResult.Sent(JsonPrimitive(provider.browse(kind, name).id))
        }
        else -> ProgramResult.Refused("Feed has no program action '${op.action}'", Answer.UNSUPPORTED)
    }

    /** Main's card (the registry's running half; `AppNet.serve`). */
    fun card(): JsonObject = FeedCore.card(state(), provider, clock())

    companion object {
        const val DESC = "desc/feed"
        /** A page of a source's items (today's glasses' page). */
        const val PAGE = 50
        const val BEFORE = 3
        const val AFTER = 5

        private val PAGE_SCOPE = Regex("^feed/src:([^/]+)/page:(\\d{1,6})$")
        private val BINGE = Regex("^binge:([^/]+)/around:(-?\\d{1,7})$")

        /** A page scope as (source, n), or null. */
        fun pageOf(scope: String): Pair<String, Int>? =
            PAGE_SCOPE.matchEntire(scope)?.let { it.groupValues[1] to it.groupValues[2].toInt() }
    }
}
