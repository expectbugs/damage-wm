package wm.damage.core.windows.feed

/**
 * FEED.md §3.6 — the provider seam the window reads through. The engine
 * itself is one ([FeedEngine], the PC always and the phone as its
 * fallback), the phone's client to the PC's engine is another
 * ([RemoteFeedProvider]), and the phone wraps the two in
 * [SwitchingFeedProvider]. Everything here is called OFF the shell loop
 * except [sources], [stateLine] and [engineName], which read cached state.
 */
interface FeedProvider : AutoCloseable {
    interface Listener {
        /** A source's item list changed (a fetch landed, a transient source appeared). */
        fun changed(sourceId: String, version: Long)
        /** Provider-wide health for the window's staleness surface: "" = healthy. */
        fun state(line: String)
    }

    /** "" = healthy; else the link's staleness (`PC unreachable 40 s`). */
    fun stateLine(): String
    /** Which engine is serving — `PC`, `phone`, `local` — for the summary. */
    fun engineName(): String
    fun addListener(l: Listener)
    fun removeListener(l: Listener)

    /** The window is looking at [sourceId] (or at nothing). Pacing is fixed
     *  (verdict 11); a provider may prioritise that source's work. */
    fun setFocused(sourceId: String?)

    /** Every source with its counts and state — CHEAP; empty before the first contact. */
    fun sources(): List<SourceStatus>
    fun items(sourceId: String, offset: Int, limit: Int): ItemPage
    fun item(itemId: String): Item?
    fun article(itemId: String): Article
    fun comic(itemId: String, width: Int, levels: Int, mode: LineArt): ComicPack
    fun bingeIndex(sourceId: String): List<Episode>
    fun bingeEpisode(sourceId: String, num: Int, width: Int, levels: Int, mode: LineArt): EpisodePack
    fun comments(itemId: String): List<Comment>
    /** Fetch now (paced); null = every source. */
    fun refresh(sourceId: String?)
    /** A typed subreddit or a chosen section becomes a transient source (kept while pinned). */
    fun browse(kind: SourceKind, name: String): SourceStatus
    /** Drop a transient source (an unpin). */
    fun forget(sourceId: String)

    /** An article image (or any image by URL) as a strip — the engine's cache applies. */
    fun image(url: String, width: Int, levels: Int, mode: LineArt): Strip
    /** An article for a URL the engine no longer lists (a flagged item that retention pruned). */
    fun articleByUrl(url: String, title: String): Article
    /** The Settings rows that belong below the window (verdicts 11–12 and the
     *  `PC loss` threshold): feeds' cadence, retention, the sustained-loss
     *  threshold a switching provider acts on. */
    fun configure(fetchMs: Long, keepMs: Long, pcLossMs: Long)

    /** The phone's fallback engine is serving (FEED.md §3.6) — the root menu's `Back to PC` row. */
    fun fallbackActive(): Boolean = false
    /** While the fallback serves: how long the PC has been unreachable, or "" once it is back. */
    fun pcDownLine(): String = ""
    /** The deliberate switchback; a no-op where there is nothing to switch. */
    fun backToPc() {}

    override fun close() {}
}
