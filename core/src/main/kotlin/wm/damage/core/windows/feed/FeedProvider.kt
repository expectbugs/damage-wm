package wm.damage.core.windows.feed

/**
 * FEED.md §3.6 — the provider seam the window reads through. The engine
 * itself is one ([FeedEngine], on the PC), the phone's client to the PC's
 * engine is another ([RemoteFeedProvider]; build S1-3 B5 retired the phone's
 * own engine and the switch between the two). Everything here is called OFF
 * the shell loop except [sources], [stateLine] and [engineName], which read
 * cached state.
 */
interface FeedProvider : AutoCloseable {
    interface Listener {
        /** A source's item list changed (a fetch landed, a transient source appeared). */
        fun changed(sourceId: String, version: Long)
        /** Provider-wide health for the window's staleness surface: "" = healthy. */
        fun state(line: String)
    }

    /** "" = healthy; else the link's staleness (`PC unreachable since 14:02`). */
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
    /** A comic source numbered like xkcd: its whole archive's range (1..latest), else null. */
    fun comicRange(sourceId: String): IntRange?
    /** The strip numbered [num] of such a source, fetched if the list does not hold it; null when
     *  the number does not exist (xkcd 404). */
    fun comicAt(sourceId: String, num: Int): Item?
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
    /** B2 (the web's comic): a comic's facts with SMBC's bonus panel learned, no strip prepared. */
    fun comicItem(itemId: String): Item? = item(itemId)
    /** B2 (the web's archive): episode [num] with its picture's URL, found on its page if the index lacks it. */
    fun bingeResolve(sourceId: String, num: Int): Episode =
        bingeIndex(sourceId).firstOrNull { it.num == num } ?: throw IllegalArgumentException("no page $num")
    /** B2 (FeedBlobs): an original picture's bytes, the engine's cached copy or one fetch. */
    fun original(url: String): ByteArray = throw UnsupportedOperationException("this host keeps no original pictures")

    override fun close() {}
}
