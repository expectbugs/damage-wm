package wm.damage.core.windows.feed

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wm.damage.core.gfx.ImageDecoder
import wm.damage.core.util.Log

/**
 * FEED.md §3.6 — THE engine, pure Kotlin, the same class on the PC (always)
 * and on the phone (while the PC is unreachable): the configured sources →
 * the per-kind fetchers on a pacer → the store → items on demand, articles
 * extracted on demand (and ahead, on the PC), comics prepared as strips,
 * the binge archive indexed once, comments cached a quarter hour.
 *
 * Threading: every call here blocks and is made OFF the shell loop; the
 * pacer runs one coroutine per due source so a stalled host holds up only
 * its own source. Pacing and liveness, never timeouts. Reading state (read
 * marks, positions) is NOT here — it lives in the shell's synced store so
 * both engines see one truth (§3.5).
 */
class FeedEngine(
    configured: List<SourceCfg>,
    private val store: FeedStore,
    private val http: FeedHttp,
    private val decoder: ImageDecoder?,
    private val scope: CoroutineScope,
    /** Feeds' cadence (the `Fetch` row); comics check hourly regardless (verdict 11). */
    fetchMs: Long = 15 * 60_000L,
    private val comicsMs: Long = 3_600_000L,
    /** The PC extracts the newest articles ahead of a tap; the phone on demand only (battery). */
    private val prefetchArticles: Boolean = true,
    private val engineLabel: String = "PC",
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Tests drive [fetchNow] by hand instead. */
    runLoop: Boolean = true,
) : FeedProvider {

    private class SourceState(val cfg: SourceCfg, @Volatile var file: FeedStore.SourceFile, val transient: Boolean) {
        @Volatile var fetching = false
        @Volatile var nextDueMs = 0L
        @Volatile var failLine = ""
        @Volatile var failSinceMs = 0L
        @Volatile var retryAtMs = 0L
        @Volatile var retryHost = ""
        @Volatile var retryStepMs = 60_000L
        @Volatile var bingeTotal = 0
    }

    @Volatile private var fetchMs: Long = fetchMs
    private val sources = LinkedHashMap<String, SourceState>()
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<FeedProvider.Listener>()
    @Volatile private var focusedId: String? = null
    @Volatile private var running = true
    @Volatile private var wake = false
    private val loop: Job?

    init {
        for (c in configured) add(c, transient = false)
        loop = if (runLoop) scope.launch(Dispatchers.IO) { loop() } else null
    }

    private fun add(cfg: SourceCfg, transient: Boolean): SourceState {
        val s = SourceState(cfg, store.loadSource(cfg.id) ?: FeedStore.SourceFile(), transient)
        if (cfg.binge) s.bingeTotal = store.loadEpisodes(cfg.id)?.episodes?.size ?: 0
        synchronized(lock) { sources[cfg.id] = s }
        return s
    }

    // ================================================================ the pacer
    private suspend fun loop() {
        while (scope.isActive && running) {
            wake = false
            val now = clock()
            val due = synchronized(lock) { sources.values.filter { !it.fetching && now >= it.nextDueMs } }
            for (s in due) {
                s.fetching = true
                scope.launch(Dispatchers.IO) {
                    try { fetchSource(s) } finally { s.fetching = false }
                }
            }
            val until = clock() + 1_000
            while (scope.isActive && running && !wake && clock() < until) delay(100)
        }
    }

    /** One fetch of [sourceId] on the calling thread (tests, one-shot tools). */
    fun fetchNow(sourceId: String): Boolean {
        val s = synchronized(lock) { sources[sourceId] } ?: return false
        if (s.fetching) return false
        s.fetching = true
        try { fetchSource(s) } finally { s.fetching = false }
        return true
    }

    private fun interval(cfg: SourceCfg): Long = when (cfg.kind) {
        SourceKind.XKCD, SourceKind.SMBC -> comicsMs
        SourceKind.RSS -> if (cfg.image) comicsMs else fetchMs
        SourceKind.EIGHTBIT -> 7L * 86_400_000
        else -> fetchMs
    }

    private fun fetchSource(s: SourceState) {
        val now = clock()
        val cfg = s.cfg
        try {
            if (cfg.kind == SourceKind.EIGHTBIT) {
                ensureIndex(s, force = false)
                s.file = s.file.copy(lastFetchMs = now, lastOkMs = clock())
                store.saveSource(cfg.id, s.file)
                ok(s)
                s.nextDueMs = clock() + interval(cfg)
                notifyChanged(s)
                return
            }
            val fetcher = Fetchers.forKind(cfg.kind) ?: throw IllegalStateException("no fetcher for ${cfg.kind}")
            val fresh = fetcher.fetch(cfg, http, s.file.items, now)
            val changed = merge(s, fresh, now)
            ok(s)
            s.nextDueMs = clock() + interval(cfg)
            notifyChanged(s)
            if (changed && prefetchArticles) prefetch(s)
        } catch (e: RateLimited) {
            s.retryAtMs = e.retryAtMs
            s.retryHost = e.host
            s.nextDueMs = e.retryAtMs
            s.file = s.file.copy(lastFetchMs = now)
            Log.w("feed", "${cfg.name}: ${e.host} rate-limited — retry in ${(e.retryAtMs - clock()) / 1000} s")
            notifyChanged(s)
        } catch (e: Exception) {
            if (s.failSinceMs == 0L) s.failSinceMs = now
            s.failLine = (e.message ?: e.toString()).take(96)
            s.file = s.file.copy(lastFetchMs = now)
            s.nextDueMs = clock() + s.retryStepMs
            s.retryStepMs = minOf(s.retryStepMs * 2, interval(cfg))
            Log.w("feed", "${cfg.name}: fetch failed — ${s.failLine} (retry in ${s.retryStepMs / 1000} s)")
            notifyChanged(s)
        }
    }

    private fun ok(s: SourceState) {
        if (s.failSinceMs != 0L) Log.i("feed", "${s.cfg.name}: fetching again after ${FeedFmt.dur((clock() - s.failSinceMs) / 1000)}")
        s.failSinceMs = 0L
        s.failLine = ""
        s.retryAtMs = 0L
        s.retryStepMs = 60_000L
    }

    /** Fresh items join the known ones (a feed that rotates fast keeps its
     *  history up to retention); a known item keeps its first-seen stamp
     *  and anything learned since (a bonus URL). True when the list changed. */
    private fun merge(s: SourceState, fresh: List<Item>, now: Long): Boolean {
        val known = s.file.items.associateBy { it.id }
        val merged = LinkedHashMap<String, Item>()
        for (f in fresh) {
            val k = known[f.id]
            merged[f.id] = if (k == null) f.copy(seenMs = now)
                else f.copy(seenMs = if (k.seenMs == 0L) now else k.seenMs, bonus = f.bonus.ifEmpty { k.bonus })
        }
        for ((id, k) in known) if (id !in merged) merged[id] = k
        val pruned = store.prune(merged.values.toList(), now)
        val changed = pruned != s.file.items
        s.file = s.file.copy(
            version = if (changed) s.file.version + 1 else s.file.version,
            lastFetchMs = now, lastOkMs = clock(), items = pruned,
        )
        store.saveSource(s.cfg.id, s.file)
        return changed
    }

    private fun prefetch(s: SourceState) {
        val newest = s.file.items.filter { it.kind == ItemKind.LINK || it.kind == ItemKind.TEXT }.take(PREFETCH)
        for (it in newest) {
            if (!running) return
            if (store.loadArticle(it.id) != null) continue
            try { article(it.id) } catch (e: Exception) { Log.d("feed", "prefetch '${it.title.take(40)}': ${e.message}") }
        }
    }

    private fun notifyChanged(s: SourceState) {
        for (l in listeners) try { l.changed(s.cfg.id, s.file.version) } catch (e: Exception) { Log.e("feed", "listener", e) }
    }

    // ================================================================ the provider face
    override fun stateLine(): String = ""
    override fun engineName(): String = engineLabel
    override fun addListener(l: FeedProvider.Listener) {
        if (!listeners.addIfAbsent(l)) return
        try { l.state("") } catch (e: Exception) { Log.e("feed", "listener", e) }
    }
    override fun removeListener(l: FeedProvider.Listener) { listeners.remove(l) }
    override fun setFocused(sourceId: String?) { focusedId = sourceId }

    private fun status(s: SourceState): SourceStatus {
        val now = clock()
        val state = when {
            s.retryAtMs > now -> "${s.retryHost} rate-limited · retry ${FeedFmt.dur((s.retryAtMs - now + 999) / 1000)}"
            s.failLine.isNotEmpty() -> "${s.failLine} · ${FeedFmt.dur((now - s.failSinceMs) / 1000)}"
            else -> ""
        }
        val f = s.file
        return SourceStatus(
            id = s.cfg.id, kind = s.cfg.kind, name = s.cfg.name, binge = s.cfg.binge, transient = s.transient,
            count = f.items.size, version = f.version, lastFetchMs = f.lastFetchMs, lastOkMs = f.lastOkMs,
            state = state, bingeTotal = s.bingeTotal, newestMs = f.items.maxOfOrNull { it.publishedMs } ?: 0L,
        )
    }

    override fun sources(): List<SourceStatus> = synchronized(lock) { sources.values.map { status(it) } }

    private fun source(id: String): SourceState =
        synchronized(lock) { sources[id] } ?: throw IllegalArgumentException("no such source '$id'")

    private fun sorted(items: List<Item>): List<Item> =
        items.sortedWith(compareByDescending<Item> { it.publishedMs }.thenByDescending { it.seenMs }.thenBy { it.id })

    override fun items(sourceId: String, offset: Int, limit: Int): ItemPage {
        val s = source(sourceId)
        val all = sorted(s.file.items)
        val from = offset.coerceIn(0, all.size)
        return ItemPage(all.subList(from, minOf(all.size, from + limit.coerceAtLeast(0))), all.size, s.file.version)
    }

    override fun item(itemId: String): Item? {
        val all = synchronized(lock) { sources.values.toList() }
        for (s in all) s.file.items.firstOrNull { it.id == itemId }?.let { return it }
        return null
    }

    // ================================================================ articles
    override fun article(itemId: String): Article {
        store.loadArticle(itemId)?.let { return it }
        val it = item(itemId) ?: throw IllegalArgumentException("no such item")
        val a = buildArticle(it)
        if (a.extracted) store.saveArticle(a)          // a floor is not cached: the next open tries again
        return a
    }

    private fun floor(it: Item, note: String): Article {
        val blocks = Extract.fragment(it.body, it.link).ifEmpty {
            if (it.summary.isNotEmpty()) listOf(Block("p", it.summary)) else emptyList()
        }
        return Article(it.id, it.title, blocks, extracted = false, note = note)
    }

    private fun buildArticle(it: Item): Article = when (it.kind) {
        ItemKind.TEXT -> Article(it.id, it.title,
            Extract.fragment(it.body, it.link).ifEmpty { listOf(Block("p", it.summary.ifEmpty { "(no text)" })) }, extracted = true)
        ItemKind.IMAGE -> Article(it.id, it.title, listOf(Block("img", it.title, it.image.ifEmpty { it.target })), extracted = true)
        ItemKind.VIDEO -> Article(it.id, it.title, listOf(Block("p", "video · not shown")), extracted = true, note = "video")
        ItemKind.COMIC -> Article(it.id, it.title, listOfNotNull(if (it.alt.isNotEmpty()) Block("p", it.alt) else null), extracted = true)
        ItemKind.LINK -> {
            val url = it.target.ifEmpty { it.link }
            try {
                val r = http.get(url)
                when {
                    r.status != 200 -> floor(it, "HTTP ${r.status} · showing the summary")
                    !isHtml(r) -> floor(it, "not a web page · showing the summary")
                    else -> {
                        val blocks = Extract.article(r.text(), url)
                        if (blocks.isEmpty()) floor(it, "could not extract · showing the summary")
                        else Article(it.id, it.title, blocks, extracted = true)
                    }
                }
            } catch (e: RateLimited) {
                throw e
            } catch (e: Exception) {
                floor(it, "${(e.message ?: "fetch failed").take(60)} · showing the summary")
            }
        }
    }

    private fun isHtml(r: HttpReplyB): Boolean {
        val ct = r.header("Content-Type")?.lowercase() ?: ""
        if (ct.isEmpty()) return true                     // say nothing about what you do not know
        return ct.contains("html") || ct.contains("xml")
    }

    // ================================================================ comics
    override fun comic(itemId: String, width: Int, levels: Int, mode: LineArt): ComicPack {
        var it = item(itemId) ?: throw IllegalArgumentException("no such item")
        if (it.image.isEmpty()) throw IllegalStateException("'${it.title}' has no image")
        val twoX = if (source(it.source).cfg.kind == SourceKind.XKCD) XkcdFetcher.twoX(it.image) else null
        val strip = stripFor(it.image, twoX, width, levels, mode)
        var bonus: Strip? = null
        if (source(it.source).cfg.kind == SourceKind.SMBC) {
            if (it.bonus.isEmpty()) it = learnBonus(it)
            if (it.bonus.isNotEmpty()) bonus = try { stripFor(it.bonus, null, width, levels, mode) } catch (e: Exception) {
                Log.w("feed", "SMBC bonus panel for '${it.title}' not shown: ${e.message}"); null
            }
        }
        return ComicPack(it, strip, bonus)
    }

    /** The SMBC bonus panel's URL comes from the comic page; learned once and kept in the item. */
    private fun learnBonus(it: Item): Item {
        val r = http.get(it.link)
        if (r.status != 200) { Log.w("feed", "SMBC page for '${it.title}' answered HTTP ${r.status} — no bonus panel"); return it }
        val url = SmbcFetcher.bonusFromPage(r.text(), it.link)
        if (url.isEmpty()) return it
        val s = source(it.source)
        synchronized(lock) {
            val updated = s.file.items.map { x -> if (x.id == it.id) x.copy(bonus = url) else x }
            s.file = s.file.copy(items = updated)
            store.saveSource(s.cfg.id, s.file)
        }
        return it.copy(bonus = url)
    }

    private fun stripFor(url: String, tryFirst: String?, width: Int, levels: Int, mode: LineArt): Strip {
        val key = FeedStore.imageKey(url)
        val sk = store.stripKey(key, width, levels, mode)
        store.loadStrip(sk)?.let { return it }
        val bytes = store.loadImage(key) ?: fetchImage(url, tryFirst).also { store.saveImage(key, it) }
        val dec = decoder ?: throw IllegalStateException("no image decoder on this host")
        val d = dec.decode(bytes) ?: throw IOException("image did not decode: $url")
        val strip = Strips.prepare(d, width, levels, mode)
        store.saveStrip(sk, strip)
        return strip
    }

    private fun fetchImage(url: String, tryFirst: String?): ByteArray {
        if (tryFirst != null) {
            val r = http.get(tryFirst)
            if (r.status == 200 && r.body.isNotEmpty()) return r.body
        }
        val r = http.get(url)
        if (r.status != 200) throw IOException("image answered HTTP ${r.status}: $url")
        if (r.body.isEmpty()) throw IOException("image answered an empty body: $url")
        return r.body
    }

    // ================================================================ the binge archive
    private fun ensureIndex(s: SourceState, force: Boolean): List<Episode> {
        val cached = store.loadEpisodes(s.cfg.id)
        val now = clock()
        if (cached != null && !force && now - cached.atMs < INDEX_TTL_MS && cached.episodes.isNotEmpty()) {
            s.bingeTotal = cached.episodes.size
            return cached.episodes
        }
        val eps = try { EightBit.index(http) } catch (e: Exception) {
            if (cached != null && cached.episodes.isNotEmpty()) {
                Log.w("feed", "8-Bit Theater index refresh failed — keeping the ${cached.episodes.size} known pages: ${e.message}")
                return cached.episodes
            }
            throw e
        }
        // keep image URLs already learned
        val known = cached?.episodes?.associate { it.num to it.image } ?: emptyMap()
        val merged = eps.map { e -> if (e.image.isEmpty() && !known[e.num].isNullOrEmpty()) e.copy(image = known[e.num]!!) else e }
        store.saveEpisodes(s.cfg.id, FeedStore.EpisodesFile(now, merged))
        s.bingeTotal = merged.size
        Log.i("feed", "8-Bit Theater: ${merged.size} pages indexed")
        return merged
    }

    override fun bingeIndex(sourceId: String): List<Episode> {
        val s = source(sourceId)
        if (!s.cfg.binge) throw IllegalArgumentException("'${s.cfg.name}' is not an archive")
        return ensureIndex(s, force = false)
    }

    override fun bingeEpisode(sourceId: String, num: Int, width: Int, levels: Int, mode: LineArt): EpisodePack {
        val s = source(sourceId)
        val eps = ensureIndex(s, force = false)
        var ep = eps.firstOrNull { it.num == num } ?: throw IllegalArgumentException("no page $num")
        if (ep.image.isEmpty()) {
            val r = http.get(ep.url)
            if (r.status != 200) throw IOException("page $num answered HTTP ${r.status}")
            val img = EightBit.imageFromPage(r.text(), ep.url)
            ep = ep.copy(image = img)
            val f = store.loadEpisodes(sourceId)
            if (f != null) store.saveEpisodes(sourceId, f.copy(episodes = f.episodes.map { if (it.num == num) ep else it }))
        }
        return EpisodePack(ep, stripFor(ep.image, null, width, levels, mode))
    }

    // ================================================================ comments
    override fun comments(itemId: String): List<Comment> {
        val it = item(itemId) ?: throw IllegalArgumentException("no such item")
        val cached = store.loadComments(itemId)
        val now = clock()
        if (cached != null && now - cached.atMs < COMMENTS_TTL_MS) return cached.comments
        if (it.commentsUrl.isEmpty()) throw IllegalStateException("comments are not reachable for this source")
        val r = http.get(it.commentsUrl)
        if (r.status != 200) throw IOException("comments answered HTTP ${r.status}")
        val list = RedditAtom.parseComments(r.body)
        store.saveComments(itemId, FeedStore.CommentsFile(now, list))
        return list
    }

    // ================================================================ refresh, browse
    override fun refresh(sourceId: String?) {
        synchronized(lock) {
            for (s in sources.values) if (sourceId == null || s.cfg.id == sourceId) s.nextDueMs = 0L
        }
        wake = true
    }

    override fun browse(kind: SourceKind, name: String): SourceStatus {
        val n = name.trim().removePrefix("r/").removePrefix("/")
        if (n.isEmpty()) throw IllegalArgumentException("nothing to browse")
        val cfg = when (kind) {
            SourceKind.REDDIT -> SourceCfg("r:" + n.lowercase(), kind, "r/$n", sub = n.lowercase())   // any case reaches the same feed
            SourceKind.SLASHDOT -> {
                val section = SlashdotRss.SECTIONS.firstOrNull { it.equals(n, ignoreCase = true) }
                    ?: throw IllegalArgumentException("no Slashdot section '$n'")
                SourceCfg("s:" + section.lowercase(), kind, section.lowercase(), section = section)
            }
            else -> throw IllegalArgumentException("browse: $kind has nothing to browse")
        }
        val s = synchronized(lock) { sources[cfg.id] } ?: add(cfg, transient = true)
        s.nextDueMs = 0L
        wake = true
        return status(s)
    }

    override fun image(url: String, width: Int, levels: Int, mode: LineArt): Strip =
        stripFor(url, null, width, levels, mode)

    override fun articleByUrl(url: String, title: String): Article {
        val key = "url:" + FeedStore.imageKey(url)
        store.loadArticle(key)?.let { return it }
        val r = http.get(url)
        val blocks = if (r.status == 200 && isHtml(r)) Extract.article(r.text(), url) else emptyList()
        val a = if (blocks.isEmpty()) Article(key, title, listOf(Block("p", "could not extract this page (HTTP ${r.status})")), extracted = false, note = "could not extract")
            else Article(key, title, blocks, extracted = true)
        if (a.extracted) store.saveArticle(a)
        return a
    }

    override fun configure(fetchMs: Long, keepMs: Long, pcLossMs: Long) {
        this.fetchMs = fetchMs.coerceAtLeast(60_000L)
        store.keepMs = keepMs.coerceAtLeast(86_400_000L)
    }

    override fun forget(sourceId: String) {
        val s = synchronized(lock) { sources[sourceId] } ?: return
        if (!s.transient) return
        synchronized(lock) { sources.remove(sourceId) }
        store.deleteSource(sourceId)
    }

    override fun close() {
        running = false
        loop?.cancel()
    }

    companion object {
        const val PREFETCH = 10
        const val COMMENTS_TTL_MS = 15 * 60_000L
        const val INDEX_TTL_MS = 7L * 86_400_000
    }
}
