package wm.damage.core.windows.feed

import java.util.concurrent.CopyOnWriteArrayList
import wm.damage.core.gfx.ImageDecoder

/**
 * A deterministic feed provider for the harnesses and the tests (`FEED.md`
 * §4): five sources with a seeded set of items, articles, comments, synthetic
 * strips (line art on a white page for xkcd, colour-on-colour for the rest),
 * a twelve-page archive, browse/forget, and scripted events — [fireNew]
 * adds an item under the cursor and fires `changed`; [setState] flips the
 * staleness line; [fallback] flips the switch. No network, no clock.
 */
class ScriptedFeed(
    /** Stamps are relative to this, so the scenes' ages read the same every day. */
    val nowMs: Long = System.currentTimeMillis(),
) : FeedProvider {
    private val listeners = CopyOnWriteArrayList<FeedProvider.Listener>()
    val ops = CopyOnWriteArrayList<String>()
    @Volatile private var state = ""
    @Volatile private var fallbackOn = false
    private val versions = HashMap<String, Long>()
    private val itemsBy = LinkedHashMap<String, ArrayList<Item>>()
    private val cfg = LinkedHashMap<String, SourceCfg>()
    private val transient = HashSet<String>()
    private val redditTitles = listOf(
        "My Dad won first place and reserve best of show for his hay at our local county fair",
        "TIL a drinks vendor in Germany cheated a bottle recycling scheme for years",
        "Netflix using egregious methods to keep subscriptions active",
        "Steam adds an age check for R18 games in Australia", "The new foldable phone bends the other way",
        "What is a Linux thing you wish you had learned sooner?", "A cat sits exactly where the sun is",
        "Kernel 7.1 drops the last of the old scheduler", "I built a trebuchet in my garage and regret nothing",
        "Photo of a thunderstorm over the plains tonight",
    )

    init {
        for (c in SourceCfg.DEFAULTS) cfg[c.id] = c
        itemsBy["popular"] = ArrayList((0 until 30).map { i -> reddit(i) })
        itemsBy["slashdot"] = ArrayList((0 until 15).map { i -> slashdot(i) })
        itemsBy["xkcd"] = ArrayList((0 until 12).map { i -> xkcd(3296 - i, i) })
        itemsBy["smbc"] = ArrayList((0 until 12).map { i -> smbc(i) })
        itemsBy["8bt"] = ArrayList()
        for (k in itemsBy.keys) versions[k] = 1
    }


    private fun reddit(i: Int): Item {
        val kind = when (i % 5) { 0, 3 -> ItemKind.IMAGE; 1 -> ItemKind.LINK; 2 -> ItemKind.TEXT; else -> ItemKind.VIDEO }
        val link = "https://www.reddit.com/r/popular/comments/p$i/post_$i/"
        return Item(
            id = FeedIds.id("popular", "t3_p$i"), source = "popular", title = redditTitles[i % redditTitles.size] + (if (i >= redditTitles.size) " ($i)" else ""),
            link = link, author = "user$i", publishedMs = nowMs - i * 1_800_000L,
            summary = if (kind == ItemKind.TEXT) "A text post's first paragraph, long enough to matter." else "",
            body = if (kind == ItemKind.TEXT) "<p>A text post's first paragraph, long enough to matter.</p><p>And a second one.</p>" else "",
            kind = kind, target = when (kind) { ItemKind.IMAGE -> "https://i.redd.it/img$i.jpeg"; ItemKind.LINK -> "https://example.org/story/$i"; ItemKind.VIDEO -> "https://v.redd.it/v$i"; else -> link },
            comments = 12 + i * 7, commentsUrl = "$link.rss", image = if (kind == ItemKind.IMAGE) "https://i.redd.it/img$i.jpeg" else "",
            extra = listOf("r/linux", "r/pics", "r/todayilearned", "r/memes")[i % 4], seenMs = nowMs,
        )
    }

    private fun slashdot(i: Int): Item = Item(
        id = FeedIds.id("slashdot", "story$i"), source = "slashdot",
        title = listOf("Google to Invest Record \$15 Billion In AI Infrastructure In Finland", "Apple's New iPhone Camera Mode Promises to Prove Your Photo Isn't AI",
            "Firefox 150 Ships With a Built-In Feed Reader", "Gentoo Turns 27", "Ask Slashdot: What Happened to the Desktop?")[i % 5] + (if (i >= 5) " ($i)" else ""),
        link = "https://slashdot.org/story/26/09/09/$i/x", author = "BeauHD", publishedMs = nowMs - i * 3_600_000L,
        summary = "Google plans to invest at least \$15 billion in AI infrastructure in Finland through 2028, its largest single investment in Europe.",
        body = "<p>Google plans to invest at least \$15 billion in AI infrastructure in Finland through 2028, its largest single investment in Europe.</p>",
        kind = ItemKind.LINK, target = "https://slashdot.org/story/26/09/09/$i/x", comments = if (i % 3 == 0) -1 else 4 + i * 9,
        extra = listOf("hardware", "mobile", "linux", "science")[i % 4], seenMs = nowMs,
    )

    private fun xkcd(num: Int, i: Int): Item = Item(
        id = FeedIds.id("xkcd", "xkcd:$num"), source = "xkcd", title = listOf("Fault Taunting", "Semaphore", "Asteroid Mission", "Chemical Formula")[i % 4],
        link = "https://xkcd.com/$num/", publishedMs = nowMs - i * 2L * 86_400_000, kind = ItemKind.COMIC,
        image = "https://imgs.xkcd.com/comics/$num.png", alt = "One of the first things they teach you is to NEVER play with the geology toy over a mantle hotspot.", num = num, seenMs = nowMs,
    )

    private fun smbc(i: Int): Item = Item(
        id = FeedIds.id("smbc", "smbc$i"), source = "smbc", title = listOf("Club", "Major", "Conscious", "Seized")[i % 4],
        link = "https://www.smbc-comics.com/comic/c$i", publishedMs = nowMs - i * 86_400_000L, kind = ItemKind.COMIC,
        image = "https://www.smbc-comics.com/comics/$i.png", alt = "God also has a joke about how both oblivion and eternal recurrence are terrifying.",
        bonus = "https://www.smbc-comics.com/comics/${i}after.png", seenMs = nowMs,
    )

    /** A synthetic strip: line art on a white page (inverts under AUTO) or
     *  colour blocks on a mid background (does not). */
    fun strip(seed: Int, width: Int, height: Int, levels: Int, mode: LineArt, lineArt: Boolean): Strip {
        val w = width
        val h = height
        val g = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (lineArt) {
                val on = (x + seed) % 23 == 0 || (y + seed) % 19 == 0 || ((x / 40 + y / 40 + seed) % 7 == 0 && (x % 40 < 3))
                if (on) 0 else 255
            } else {
                val block = ((x / 48) + (y / 48) + seed) % 5
                listOf(60, 120, 180, 90, 200)[block]
            }
            g[y * w + x] = v.toByte()
        }
        return Strips.prepare(ImageDecoder.Decoded(w, h, g), width, levels, mode)
    }

    val episodes: List<Episode> = (1..12).map { n -> Episode(n, listOf("We're going where?", "Why is he in the lead anyway?", "A legend is born")[n % 3], nowMs - (13 - n) * 86_400_000L * 30, "https://www.nuklearpower.com/ep/$n", "https://www.nuklearpower.com/comics/$n.png") }

    override fun stateLine(): String = state
    override fun engineName(): String = if (fallbackOn) "phone" else "PC"
    override fun addListener(l: FeedProvider.Listener) { if (listeners.addIfAbsent(l)) l.state(state) }
    override fun removeListener(l: FeedProvider.Listener) { listeners.remove(l) }
    override fun setFocused(sourceId: String?) { ops.add("focus:$sourceId") }

    private fun status(c: SourceCfg): SourceStatus {
        val list = itemsBy[c.id] ?: emptyList()
        return SourceStatus(c.id, c.kind, c.name, c.binge, c.id in transient, list.size, versions[c.id] ?: 1L, nowMs - 240_000, nowMs - 240_000,
            if (c.id == "slashdot" && slashdotDown) "Slashdot Main answered HTTP 503 · 3 min" else "",
            bingeTotal = if (c.binge) episodes.size else 0, newestMs = list.maxOfOrNull { it.publishedMs } ?: 0L)
    }

    @Volatile var slashdotDown = false

    override fun sources(): List<SourceStatus> = cfg.values.map { status(it) }

    override fun items(sourceId: String, offset: Int, limit: Int): ItemPage {
        ops.add("items:$sourceId:$offset:$limit")
        val all = (itemsBy[sourceId] ?: throw IllegalArgumentException("no such source '$sourceId'")).sortedByDescending { it.publishedMs }
        val from = offset.coerceIn(0, all.size)
        return ItemPage(all.subList(from, minOf(all.size, from + limit)), all.size, versions[sourceId] ?: 1L)
    }

    override fun item(itemId: String): Item? = itemsBy.values.flatten().firstOrNull { it.id == itemId }

    override fun article(itemId: String): Article {
        ops.add("article:$itemId")
        val it = item(itemId) ?: throw IllegalArgumentException("no such item")
        return when (it.kind) {
            ItemKind.TEXT -> Article(itemId, it.title, Extract.fragment(it.body, it.link), true)
            ItemKind.IMAGE -> Article(itemId, it.title, listOf(Block("img", it.title, it.image)), true)
            ItemKind.VIDEO -> Article(itemId, it.title, listOf(Block("p", "video · not shown")), true, "video")
            ItemKind.COMIC -> Article(itemId, it.title, listOf(Block("p", it.alt)), true)
            ItemKind.LINK -> Article(itemId, it.title, listOf(
                Block("h", it.title),
                Block("p", "First paragraph of the story, which carries enough words to score, and a comma or two, so the container earns its points and the reader has something to read on a small panel."),
                Block("img", "a photo", "https://example.org/img/${it.id}.jpg"),
                Block("p", "Second paragraph, likewise long enough to matter, describing something in a few clauses, with commas, and a closing thought that runs to the end of the line and past it."),
                Block("q", "A quoted line that also has enough characters to count as a paragraph on its own."),
                Block("li", "the first point"), Block("li", "the second point"),
                Block("p", "Third paragraph closes the piece with another long enough sentence for the scorer to see, again with commas, and again with a tail."),
            ), true)
        }
    }

    override fun comic(itemId: String, width: Int, levels: Int, mode: LineArt): ComicPack {
        ops.add("comic:$itemId:$width:$levels:$mode")
        val it = item(itemId) ?: throw IllegalArgumentException("no such item")
        val xk = it.source == "xkcd"
        val main = strip(it.num + it.id.hashCode() % 7, minOf(width, 596), if (xk) 218 else 730, levels, mode, lineArt = xk)
        val bonus = if (it.bonus.isNotEmpty()) strip(3, 360, 360, levels, mode, lineArt = false) else null
        return ComicPack(it, main, bonus)
    }

    override fun bingeIndex(sourceId: String): List<Episode> { ops.add("index:$sourceId"); return episodes }

    override fun bingeEpisode(sourceId: String, num: Int, width: Int, levels: Int, mode: LineArt): EpisodePack {
        ops.add("episode:$sourceId:$num")
        val ep = episodes.firstOrNull { it.num == num } ?: throw IllegalArgumentException("no page $num")
        return EpisodePack(ep, strip(num, minOf(width, 596), 774, levels, mode, lineArt = false))
    }

    override fun comments(itemId: String): List<Comment> {
        ops.add("comments:$itemId")
        val it = item(itemId) ?: throw IllegalArgumentException("no such item")
        if (it.commentsUrl.isEmpty()) throw IllegalStateException("comments are not reachable for this source")
        return (0 until 6).map { i -> Comment("commenter$i", nowMs - i * 600_000L, "Comment number $i says something ordinary about the post, at a length that wraps once or twice on the panel.\nA second paragraph of it.", 0) }
    }

    override fun refresh(sourceId: String?) { ops.add("refresh:$sourceId") }

    override fun browse(kind: SourceKind, name: String): SourceStatus {
        ops.add("browse:$kind:$name")
        val id = when (kind) {
            SourceKind.REDDIT -> "r:" + name.removePrefix("r/").lowercase()
            SourceKind.SLASHDOT -> "s:" + (SlashdotRss.SECTIONS.firstOrNull { it.equals(name, true) } ?: throw IllegalArgumentException("no Slashdot section '$name'")).lowercase()
            else -> throw IllegalArgumentException("nothing to browse")
        }
        if (id !in cfg) {
            cfg[id] = if (kind == SourceKind.REDDIT) SourceCfg(id, kind, "r/${name.removePrefix("r/")}", sub = name) else SourceCfg(id, kind, id.removePrefix("s:"), section = id.removePrefix("s:"))
            itemsBy[id] = ArrayList((0 until 8).map { i -> reddit(i + 40).copy(id = FeedIds.id(id, "t3_b$i"), source = id, extra = if (kind == SourceKind.REDDIT) "r/${name.removePrefix("r/")}" else id.removePrefix("s:")) })
            versions[id] = 1
            transient.add(id)
        }
        return status(cfg[id]!!)
    }

    override fun forget(sourceId: String) {
        ops.add("forget:$sourceId")
        if (sourceId in transient) { cfg.remove(sourceId); itemsBy.remove(sourceId); transient.remove(sourceId) }
    }

    override fun image(url: String, width: Int, levels: Int, mode: LineArt): Strip {
        ops.add("image:$url")
        return strip(url.hashCode() % 11, minOf(width, 400), 200, levels, mode, lineArt = url.hashCode() % 2 == 0)
    }

    override fun articleByUrl(url: String, title: String): Article {
        ops.add("articleByUrl:$url")
        return Article("url:" + FeedIds.sha1(url).take(20), title, listOf(Block("p", "The page at $url, extracted after retention forgot the item, with enough words to read.")), true)
    }

    override fun configure(fetchMs: Long, keepMs: Long, pcLossMs: Long) { ops.add("configure:$fetchMs:$keepMs:$pcLossMs") }
    override fun fallbackActive(): Boolean = fallbackOn
    override fun backToPc() { ops.add("backToPc"); fallbackOn = false; setState("") }

    // ------------------------------------------------------------ scripted events
    /** A new item lands at the top of [sourceId]: the version bumps and `changed` fires. */
    fun fireNew(sourceId: String, title: String = "Breaking: a brand new item"): Item {
        val list = itemsBy[sourceId] ?: throw IllegalArgumentException("no such source")
        val it = reddit(900 + list.size).copy(id = FeedIds.id(sourceId, "new${list.size}"), source = sourceId, title = title,
            publishedMs = (list.maxOfOrNull { x -> x.publishedMs } ?: nowMs) + 60_000, kind = ItemKind.LINK)
        list.add(0, it)
        versions[sourceId] = (versions[sourceId] ?: 1L) + 1
        for (l in listeners) l.changed(sourceId, versions[sourceId]!!)
        return it
    }

    fun setState(line: String) { state = line; for (l in listeners) l.state(line) }

    /** The phone engine took over (the PC unreachable): the state line and the switch. */
    fun fallback(on: Boolean) { fallbackOn = on; setState(if (on) "" else "") }
}
