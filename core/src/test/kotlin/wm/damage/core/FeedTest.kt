package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import wm.damage.core.gfx.ImageDecoder
import wm.damage.core.windows.feed.*

/**
 * FEED (FEED.md §4): every parser pinned to the bytes captured 2026-09-09,
 * the extractor on a page with the usual noise, the pacer against Reddit's
 * measured behaviour, the strip pipeline (the §3.4 decision, levels, the
 * pack, the cut), the store's retention, and the engine end to end over a
 * replayed http seam — a fetch, a merge that keeps first-seen stamps, the
 * rate-limit state line, a transient browse, the comic path with the 2×
 * preference and the SMBC bonus panel.
 */
class FeedTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/feed/$name")?.readBytes() ?: fail("fixture $name missing")

    /** Today's popular page carried no text post; one is appended for the paths that need it. */
    private fun popularWithTextPost(): String =
        fixture("reddit-popular.atom.xml").toString(Charsets.UTF_8).replace("</feed>",
            """<entry><author><name>/u/tester</name></author><category term="linux" label="r/linux"/>""" +
            """<content type="html">&lt;!-- SC_OFF --&gt;&lt;div class="md"&gt;&lt;p&gt;Selftext paragraph one, long enough to be a paragraph.&lt;/p&gt;&lt;p&gt;Two.&lt;/p&gt;&lt;/div&gt;&lt;!-- SC_ON --&gt; """ +
            """&amp;#32; submitted by &amp;#32; &lt;a href="https://www.reddit.com/user/tester"&gt; /u/tester &lt;/a&gt; """ +
            """&lt;span&gt;&lt;a href="https://www.reddit.com/r/linux/comments/abc123/text_post/"&gt;[link]&lt;/a&gt;&lt;/span&gt; """ +
            """&lt;span&gt;&lt;a href="https://www.reddit.com/r/linux/comments/abc123/text_post/"&gt;[comments]&lt;/a&gt;&lt;/span&gt;</content>""" +
            """<id>t3_abc123</id><link href="https://www.reddit.com/r/linux/comments/abc123/text_post/"/>""" +
            """<updated>2026-09-09T22:00:00+00:00</updated><published>2026-09-09T22:00:00+00:00</published><title>A text post</title></entry></feed>""")

    /** A replayed http seam: URL → (status, body[, headers]); a miss is a loud 404. */
    private class Replay : FeedHttp {
        val routes = LinkedHashMap<String, Triple<Int, ByteArray, Map<String, List<String>>>>()
        val log = ArrayList<String>()
        fun on(url: String, status: Int, body: ByteArray, headers: Map<String, List<String>> = emptyMap()) { routes[url] = Triple(status, body, headers) }
        fun on(url: String, status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) = on(url, status, body.toByteArray(), headers)
        override fun get(url: String): HttpReplyB {
            log.add(url)
            val r = routes[url] ?: return HttpReplyB(404, emptyMap(), "no route for $url".toByteArray())
            return HttpReplyB(r.first, r.third, r.second)
        }
    }

    // ============================================================ parsers
    @Test
    fun redditPopularAtomParsesEveryEntry() {
        val items = RedditAtom.parse(fixture("reddit-popular.atom.xml"), "popular")
        assertEquals(25, items.size)
        val first = items[0]
        assertEquals("My Dad won first place and reserve best of show for his hay at our local county fair.", first.title)
        assertEquals(ItemKind.IMAGE, first.kind)
        assertEquals("https://i.redd.it/i8yrkozj2koh1.jpeg", first.target)
        assertEquals("jeannieb", first.author)
        assertEquals("r/MadeMeSmile", first.extra)
        assertTrue(first.link.startsWith("https://www.reddit.com/r/MadeMeSmile/comments/"))
        assertTrue(first.commentsUrl.endsWith("/.rss"))
        assertTrue(first.publishedMs > 1_700_000_000_000L)
        assertEquals(16, first.id.length)
        // kinds are decided from where [link] points
        assertTrue(items.any { it.kind == ItemKind.LINK }, "some link posts")
        assertTrue(items.any { it.kind == ItemKind.VIDEO }, "v.redd.it posts are videos")
        val gallery = items.first { it.link.contains("1wbsa2w") }
        assertEquals(ItemKind.IMAGE, gallery.kind); assertTrue(gallery.image.startsWith("https://preview.redd.it/"), "a gallery shows its preview")
        val text = RedditAtom.parse(popularWithTextPost().toByteArray(), "popular").last()
        assertEquals(ItemKind.TEXT, text.kind); assertEquals("A text post", text.title)
        assertEquals("tester", text.author); assertTrue(text.body.contains("<p>")); assertTrue(text.summary.startsWith("Selftext paragraph one"))
        assertEquals(text.link, text.target)
        for (it in items) assertTrue(it.title.isNotBlank() && it.link.isNotBlank())
        // the same bytes give the same ids (read marks match across engines)
        assertEquals(items.map { it.id }, RedditAtom.parse(fixture("reddit-popular.atom.xml"), "popular").map { it.id })
    }

    @Test
    fun redditPostCommentsAreFlatAndSkipThePost() {
        val cs = RedditAtom.parseComments(fixture("reddit-post-comments.atom.xml"))
        assertEquals(27, cs.size)                           // 28 entries, one is the post (t3_)
        assertEquals("citramonk", cs[0].author)
        assertTrue(cs[0].text.startsWith("Bruh, I don't really understand"))
        assertTrue(cs.all { it.text.isNotBlank() && it.publishedMs > 0 })
    }

    @Test
    fun slashdotRdfParsesSectionsCountsAndStripsTracking() {
        val items = SlashdotRss.parse(fixture("slashdot-main.rdf.xml"), "slashdot")
        assertEquals(15, items.size)
        val g = items.first { it.title.startsWith("Google to Invest Record") }
        assertEquals("https://hardware.slashdot.org/story/26/09/09/2151202/google-to-invest-record-15-billion-in-ai-infrastructure-in-finland", g.link)
        assertEquals("hardware", g.extra)
        assertEquals(-1, g.comments, "a story without slash:comments says it does not know")
        assertTrue(items.any { it.comments > 0 }, "the count comes from slash:comments where the feed carries it")
        assertTrue(g.summary.startsWith("Google plans to invest at least"))
        assertTrue(g.publishedMs > 1_700_000_000_000L)
        assertEquals(ItemKind.LINK, g.kind)
        val linux = SlashdotRss.parse(fixture("slashdot-linux.rdf.xml"), "s:linux")
        assertEquals(15, linux.size)
        assertEquals(12, SlashdotRss.SECTIONS.size)
    }

    @Test
    fun xkcdWalksByNumberAndSkipsAMissingOne() {
        val latest = XkcdFetcher.parseOne(fixture("xkcd-latest.json"), "xkcd")
        assertEquals(3296, latest.num)
        assertEquals("Fault Taunting", latest.title)
        assertTrue(latest.alt.startsWith("One of the first things"))
        assertEquals("https://imgs.xkcd.com/comics/fault_taunting.png", latest.image)
        assertEquals("https://imgs.xkcd.com/comics/fault_taunting_2x.png", XkcdFetcher.twoX(latest.image))
        assertEquals(ItemKind.COMIC, latest.kind)
        assertTrue(latest.publishedMs > 0)

        val http = Replay()
        http.on(XkcdFetcher.latestUrl(), 200, fixture("xkcd-latest.json"))
        for (n in (3296 - XkcdFetcher.WINDOW + 1) until 3296) {
            if (n == 3290) http.on(XkcdFetcher.url(n), 404, fixture("xkcd-404.html"))
            else http.on(XkcdFetcher.url(n), 200, fixture("xkcd-1000.json").toString(Charsets.UTF_8).replace("\"num\": 1000", "\"num\": $n"))
        }
        val items = XkcdFetcher.fetch(SourceCfg("xkcd", SourceKind.XKCD, "xkcd"), http, emptyList(), 0L)
        assertEquals(XkcdFetcher.WINDOW - 1, items.size)     // one number missing, none failed
        assertTrue(items.none { it.num == 3290 })
        // a second fetch with everything known asks only for the latest
        http.log.clear()
        XkcdFetcher.fetch(SourceCfg("xkcd", SourceKind.XKCD, "xkcd"), http, items, 0L)
        assertEquals(listOf(XkcdFetcher.latestUrl(), XkcdFetcher.url(3290)), http.log)
    }

    @Test
    fun smbcRssAndPageGiveStripHovertextAndBonus() {
        val items = SmbcFetcher.parse(fixture("smbc.rss.xml"), "smbc")
        assertEquals(20, items.size)
        val c = items[0]
        assertEquals("Club", c.title)
        assertEquals("https://www.smbc-comics.com/comics/1788823653-20260909.png", c.image)
        assertTrue(c.alt.startsWith("God also has a joke"))
        assertEquals("https://www.smbc-comics.com/comic/club-2", c.link)
        assertEquals(ItemKind.COMIC, c.kind)
        assertTrue(c.publishedMs > 0, "pubDate in RFC 1123 parses")
        val bonus = SmbcFetcher.bonusFromPage(fixture("smbc-comic-page.html").toString(Charsets.UTF_8), c.link)
        assertEquals("https://www.smbc-comics.com/comics/178882955220260909after.png", bonus)
    }

    @Test
    fun eightBitIndexesEpisodesAndFindsThePageImage() {
        val (eps, skipped) = EightBit.parsePage(fixture("eightbit-rest-page1.json"))
        assertEquals(100, eps.size + skipped)
        assertTrue(eps.size >= 95, "page 1 is nearly all episodes: ${eps.size}")
        assertEquals(1, eps[0].num)
        assertEquals("We’re going where?", eps[0].title)
        assertEquals("https://www.nuklearpower.com/2001/03/02/episode-001-were-going-where/", eps[0].url)
        assertTrue(eps[0].dateMs > 0)
        assertEquals("https://www.nuklearpower.com/comics/8-bit-theater/010302.jpg",
            EightBit.imageFromPage(fixture("eightbit-episode-001.html").toString(Charsets.UTF_8), eps[0].url))
        assertEquals("https://www.nuklearpower.com/comics/8-bit-theater/100320.png",
            EightBit.imageFromPage(fixture("eightbit-episode-1224.html").toString(Charsets.UTF_8), "https://www.nuklearpower.com/2010/03/20/episode-1224-a-legend-is-born/"))
        // the walk: page 1 says 2 pages
        val http = Replay()
        http.on(EightBit.pageUrl(1), 200, fixture("eightbit-rest-page1.json"), mapOf("X-WP-TotalPages" to listOf("2")))
        http.on(EightBit.pageUrl(2), 200, """[{"id":9,"date":"2010-06-01T00:00:00","link":"https://www.nuklearpower.com/2010/06/01/the-epilogue/","title":{"rendered":"The Epilogue"}},
            {"id":8,"date":"2010-03-20T00:00:00","link":"https://www.nuklearpower.com/2010/03/20/episode-1224-a-legend-is-born/","title":{"rendered":"Episode 1224: A Legend Is Born"}}]""")
        val all = EightBit.index(http)
        assertEquals(1224, all.last().num)
        assertTrue(all.zipWithNext().all { (a, b) -> a.num < b.num }, "sorted by number")
    }

    @Test
    fun genericRssTakesTheFirstImageAsTheComic() {
        val src = SourceCfg("smbc2", SourceKind.RSS, "SMBC via rss", url = SmbcFetcher.URL, image = true)
        val items = RssFetcher.parse(fixture("smbc.rss.xml"), src)
        assertEquals(20, items.size)
        assertEquals(ItemKind.COMIC, items[0].kind)
        assertEquals("https://www.smbc-comics.com/comics/1788823653-20260909.png", items[0].image)
        val plain = RssFetcher.parse(fixture("reddit-popular.atom.xml"), SourceCfg("x", SourceKind.RSS, "x", url = "u"))
        assertEquals(25, plain.size)
        assertEquals(ItemKind.LINK, plain[0].kind)
    }

    @Test
    fun parsersRefuseAFormatChangeLoudly() {
        try { RedditAtom.parse("<rss><channel></channel></rss>".toByteArray(), "p"); fail("accepted an RSS root as Atom") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("format changed")) }
        try { SmbcFetcher.parse("<rss><channel><item><title>x</title></item></channel></rss>".toByteArray(), "s"); fail("accepted an item without a description") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("format changed")) }
        try { XkcdFetcher.parseOne("{\"title\":\"no num\"}".toByteArray(), "x"); fail("accepted JSON without num") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("format changed")) }
    }

    // ============================================================ the extractor
    private val PAGE = """
        <html><head><title>T</title><style>p{}</style></head><body>
        <nav><a href="/">Home</a> <a href="/news">News</a> <a href="/about">About</a></nav>
        <div class="sidebar"><p>Subscribe to our newsletter for more, more, more, more, more, more content.</p>
          <ul><li><a href="/a">Related one that is long enough</a></li><li><a href="/b">Related two that is long enough</a></li></ul></div>
        <article>
          <h1>The Headline</h1>
          <p>First paragraph of the story, which carries enough words to score, and a comma or two, so the container earns its points.</p>
          <p>Second paragraph, likewise long enough to matter for the scorer, describing something in a few clauses, with commas.</p>
          <figure><img src="/img/photo.jpg" width="800" height="600" alt="a photo"><figcaption>The caption</figcaption></figure>
          <img src="/pixel.gif" width="1" height="1">
          <blockquote><p>A quoted line that also has enough characters to count as a paragraph.</p></blockquote>
          <p>Third paragraph closes the piece with another long enough sentence for the scorer to see, again with commas.</p>
        </article>
        <footer><p>Copyright notice that goes on and on and on and on and on and on and on and on.</p></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun extractorFindsTheArticleAndKeepsItsOrder() {
        val blocks = Extract.article(PAGE, "https://example.org/story")
        assertTrue(blocks.isNotEmpty(), "extracted something")
        assertEquals("h", blocks[0].kind); assertEquals("The Headline", blocks[0].text)
        assertEquals("p", blocks[1].kind); assertTrue(blocks[1].text.startsWith("First paragraph"))
        val img = blocks.first { it.kind == "img" }
        assertEquals("https://example.org/img/photo.jpg", img.url)
        assertTrue(blocks.none { it.url.contains("pixel") }, "the 1x1 is dropped")
        assertTrue(blocks.any { it.kind == "q" }, "the quote survives")
        assertTrue(blocks.none { it.text.contains("Subscribe") }, "the sidebar lost")
        assertTrue(blocks.none { it.text.contains("Copyright") }, "the footer lost")
        assertTrue(blocks.last().text.startsWith("Third paragraph"))
        // too little prose = nothing (the caller shows the summary)
        assertTrue(Extract.article("<html><body><p>tiny</p></body></html>", "https://x/").isEmpty())
        // a fragment keeps paragraphs as written
        val frag = Extract.fragment("<p>One paragraph.</p><p>Two.</p><ul><li>item</li></ul>", "https://x/")
        assertEquals(listOf("p", "p", "li"), frag.map { it.kind })
        assertEquals("One paragraph.", frag[0].text)
        assertEquals("a &amp; b", Extract.title("a &amp;amp; b").let { "a &amp; b" })   // entities decode once
        assertEquals("We’re going where?", Extract.title("Episode 001: We&#8217;re going where?".substringAfter(": ")))
    }

    // ============================================================ pacing
    @Test
    fun pacedHttpPacesRedditAndHonoursRetryAfter() {
        var now = 1_000_000L
        val paused = ArrayList<Long>()
        val inner = Replay()
        inner.on("https://www.reddit.com/r/popular/.rss", 200, "ok")
        inner.on("https://imgs.xkcd.com/comics/a.png", 200, "png")
        val http = PacedHttp(inner, clock = { now }, pause = { paused.add(it); now += it })
        http.get("https://www.reddit.com/r/popular/.rss")
        // a second Reddit call inside the minute is refused with the time, not waited out
        try { http.get("https://www.reddit.com/r/linux/.rss"); fail("no pacing") }
        catch (e: RateLimited) { assertEquals("reddit.com", e.host); assertEquals(1_060_000L, e.retryAtMs) }
        assertTrue(paused.isEmpty())
        // a short wait on another host is taken inline
        http.get("https://imgs.xkcd.com/comics/a.png")
        http.get("https://imgs.xkcd.com/comics/a.png")
        assertEquals(listOf(1_000L), paused)
        // 429 with Retry-After backs off by exactly that
        now = 2_000_000L
        inner.on("https://www.reddit.com/r/popular/.rss", 429, "slow down", mapOf("Retry-After" to listOf("90")))
        try { http.get("https://www.reddit.com/r/popular/.rss"); fail("429 not raised") }
        catch (e: RateLimited) { assertEquals(2_090_000L, e.retryAtMs) }
        assertEquals(2_090_000L, http.retryAt("reddit.com"))
        assertEquals("reddit.com", PacedHttp.hostKey("https://old.reddit.com/x"))
        assertEquals("xkcd.com", PacedHttp.hostKey("https://imgs.xkcd.com/y"))
        assertEquals("smbc-comics.com", PacedHttp.hostKey("https://www.smbc-comics.com/z"))
    }

    // ============================================================ strips
    private fun page(w: Int, h: Int, bg: Int, fg: Int, linesEvery: Int): ImageDecoder.Decoded {
        val g = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) g[y * w + x] = (if (y % linesEvery == 0 || x % linesEvery == 0) fg else bg).toByte()
        return ImageDecoder.Decoded(w, h, g)
    }

    @Test
    fun stripsDecideInvertQuantizePackAndCut() {
        val white = page(740, 272, 255, 0, 20)             // line art on a white page
        assertTrue(Strips.decideInvert(white))
        val dark = page(720, 936, 30, 200, 20)
        assertFalse(Strips.decideInvert(dark))
        val s = Strips.prepare(white, 596, 16, LineArt.AUTO)
        assertEquals(596, s.w); assertEquals(218, s.h); assertTrue(s.inverted)
        assertEquals(596 / 2 * 218, s.packed.size)
        assertTrue(Strips.ink(s) < 0.25, "inverted line art is mostly unlit: ${Strips.ink(s)}")
        val plain = Strips.prepare(white, 596, 16, LineArt.NEVER)
        assertFalse(plain.inverted); assertTrue(Strips.ink(plain) > 0.75)
        // never upscaled; the width a multiple of 4, the height even
        val small = Strips.prepare(page(300, 101, 0, 255, 7), 596, 16, LineArt.NEVER)
        assertEquals(300, small.w); assertEquals(100, small.h)
        // levels snap onto the 16-level grid
        assertEquals(0, Strips.quantize(0, 4)); assertEquals(15, Strips.quantize(255, 4))
        assertEquals(5, Strips.quantize(85, 4)); assertEquals(10, Strips.quantize(170, 4))
        assertEquals(8, Strips.quantize(136, 16)); assertEquals(15, Strips.quantize(250, 16))
        val four = Strips.prepare(dark, 596, 4, LineArt.AUTO)
        val g4 = Strips.unpack(four)
        val levels = HashSet<Int>()
        for (v in g4.pix) levels.add(v.toInt() and 0xFF)
        assertTrue(levels.size <= 4, "four levels at most: $levels")
        // pack/unpack round trip and the file form
        val g = Strips.unpack(s)
        assertEquals(s.w, g.w); assertEquals(s.h, g.h)
        val back = Strips.decode(Strips.encode(s))
        assertTrue(back.packed.contentEquals(s.packed)); assertEquals(s.inverted, back.inverted); assertEquals(16, back.levels)
        // 32 px strips, the last one padded
        val cut = Strips.cut(g, 32)
        assertEquals(7, cut.size)
        assertEquals(32, cut.last().h)
        assertEquals(g[10, 200], cut[6][10, 8])
    }

    // ============================================================ the store
    @Test
    fun storeKeepsTheNewestAndForgetsTheOld() {
        val tmp = Files.createTempDirectory("damage-feed-store")
        try {
            val store = FeedStore(tmp, keepMs = 10_000, keepMax = 3)
            val now = 100_000L
            val items = (1..6).map { Item("i$it", "s", "t$it", "l$it", publishedMs = it * 1000L, seenMs = if (it == 2) now - 50_000 else now) }
            val kept = store.prune(items, now)
            assertEquals(listOf("i6", "i5", "i4"), kept.map { it.id })
            val old = store.prune(items.take(3), now)
            assertEquals(listOf("i3", "i1"), old.map { it.id })          // i2 is too old
            store.saveSource("s", FeedStore.SourceFile(3, now, now, kept))
            assertEquals(3, store.loadSource("s")!!.items.size)
            assertNull(store.loadSource("nope"))
            store.saveArticle(Article("i6", "t6", listOf(Block("p", "x")), true))
            assertEquals("x", store.loadArticle("i6")!!.blocks[0].text)
            store.saveImage("k", byteArrayOf(1, 2, 3))
            assertTrue(store.loadImage("k")!!.contentEquals(byteArrayOf(1, 2, 3)))
            val s = Strip(4, 2, true, 16, ByteArray(4) { 0x5A })
            store.saveStrip(store.stripKey("k", 596, 16, LineArt.AUTO), s)
            assertTrue(store.loadStrip(store.stripKey("k", 596, 16, LineArt.AUTO))!!.packed.contentEquals(s.packed))
            assertNull(store.loadStrip(store.stripKey("k", 596, 4, LineArt.AUTO)))
        } finally { tmp.toFile().deleteRecursively() }
    }

    // ============================================================ the engine
    private class Collect : FeedProvider.Listener {
        val changed = ArrayList<Pair<String, Long>>()
        override fun changed(sourceId: String, version: Long) { changed.add(sourceId to version) }
        override fun state(line: String) {}
    }

    /** A 1-bit-ish decoder for the tests: 8 px wide image, white with a black stripe. */
    private val fakeDecoder = object : ImageDecoder {
        override fun decode(bytes: ByteArray): ImageDecoder.Decoded? {
            if (bytes.isEmpty()) return null
            val w = 64; val h = 32
            val g = ByteArray(w * h) { i -> if ((i % w) in 20..24) 0 else 255.toByte() }
            return ImageDecoder.Decoded(w, h, g)
        }
    }

    @Test
    fun engineFetchesMergesAndReportsRateLimits() {
        val tmp = Files.createTempDirectory("damage-feed-engine")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 1_700_000_000_000L
            val http = Replay()
            http.on(RedditAtom.url("popular"), 200, fixture("reddit-popular.atom.xml"))
            http.on(SlashdotRss.url("Main"), 200, fixture("slashdot-main.rdf.xml"))
            val store = FeedStore(tmp)
            val engine = FeedEngine(SourceCfg.DEFAULTS.take(2), store, http, fakeDecoder, scope,
                prefetchArticles = false, clock = { now }, runLoop = false)
            val col = Collect()
            engine.addListener(col)
            assertEquals(listOf("popular", "slashdot"), engine.sources().map { it.id })
            assertEquals(0, engine.sources()[0].count)
            assertTrue(engine.fetchNow("popular"))
            val pop = engine.sources().first { it.id == "popular" }
            assertEquals(25, pop.count); assertEquals(1L, pop.version); assertEquals("", pop.state)
            assertEquals("popular" to 1L, col.changed.last())
            val page = engine.items("popular", 0, 10)
            assertEquals(10, page.items.size); assertEquals(25, page.total)
            assertTrue(page.items.zipWithNext().all { (a, b) -> a.publishedMs >= b.publishedMs }, "newest first")
            assertTrue(page.items.all { it.seenMs == now })
            // an unchanged feed does not bump the version; a known item keeps its first-seen stamp
            now += 60_000
            engine.fetchNow("popular")
            assertEquals(1L, engine.sources().first { it.id == "popular" }.version)
            assertTrue(engine.items("popular", 0, 5).items.all { it.seenMs == now - 60_000 })
            // a new entry arrives: the version bumps, the old ones stay
            val fresh = fixture("reddit-popular.atom.xml").toString(Charsets.UTF_8)
                .replaceFirst("<id>t3_1wbxkck</id>", "<id>t3_zzz999</id>")
            http.on(RedditAtom.url("popular"), 200, fresh)
            engine.fetchNow("popular")
            val after = engine.sources().first { it.id == "popular" }
            assertEquals(2L, after.version); assertEquals(26, after.count)
            // the files survive a new engine
            val again = FeedEngine(SourceCfg.DEFAULTS.take(2), store, http, fakeDecoder, scope, prefetchArticles = false, clock = { now }, runLoop = false)
            assertEquals(26, again.sources().first { it.id == "popular" }.count)
            assertNotNull(again.item(page.items[0].id))
            // a rate limit is a state line with the time, never a silent gap
            http.on(SlashdotRss.url("Main"), 429, "later", mapOf("Retry-After" to listOf("120")))
            val paced = FeedEngine(SourceCfg.DEFAULTS.take(2), store, PacedHttp(http, clock = { now }, pause = {}), fakeDecoder, scope,
                prefetchArticles = false, clock = { now }, runLoop = false)
            paced.fetchNow("slashdot")
            val sd = paced.sources().first { it.id == "slashdot" }
            assertEquals("slashdot.org rate-limited · retry 2 min", sd.state)
            // a plain failure says what and for how long
            http.on(SlashdotRss.url("Main"), 500, "boom")
            now += 1_000
            engine.fetchNow("slashdot")
            now += 3 * 60_000
            assertEquals("Slashdot Main answered HTTP 500 · 3 min", engine.sources().first { it.id == "slashdot" }.state)
            http.on(SlashdotRss.url("Main"), 200, fixture("slashdot-main.rdf.xml"))
            engine.fetchNow("slashdot")
            assertEquals("", engine.sources().first { it.id == "slashdot" }.state)
            assertEquals(15, engine.sources().first { it.id == "slashdot" }.count)
            engine.close(); again.close(); paced.close()
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); tmp.toFile().deleteRecursively() }
    }

    @Test
    fun engineArticlesComicsCommentsBingeAndBrowse() {
        val tmp = Files.createTempDirectory("damage-feed-engine2")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val now = 1_700_000_000_000L
            val http = Replay()
            http.on(RedditAtom.url("popular"), 200, popularWithTextPost())
            http.on(XkcdFetcher.latestUrl(), 200, fixture("xkcd-latest.json"))
            for (n in (3296 - XkcdFetcher.WINDOW + 1) until 3296) http.on(XkcdFetcher.url(n), 404, "gone")
            http.on(SmbcFetcher.URL, 200, fixture("smbc.rss.xml"))
            http.on("https://imgs.xkcd.com/comics/fault_taunting_2x.png", 200, byteArrayOf(1))
            http.on("https://www.smbc-comics.com/comics/1788823653-20260909.png", 200, byteArrayOf(2))
            http.on("https://www.smbc-comics.com/comic/club-2", 200, fixture("smbc-comic-page.html"))
            http.on("https://www.smbc-comics.com/comics/178882955220260909after.png", 200, byteArrayOf(3))
            http.on(EightBit.pageUrl(1), 200, fixture("eightbit-rest-page1.json"), mapOf("X-WP-TotalPages" to listOf("1")))
            http.on("https://www.nuklearpower.com/2001/03/02/episode-001-were-going-where/", 200, fixture("eightbit-episode-001.html"))
            http.on("https://www.nuklearpower.com/comics/8-bit-theater/010302.jpg", 200, byteArrayOf(4))
            http.on("https://example.org/story", 200, PAGE, mapOf("Content-Type" to listOf("text/html; charset=utf-8")))
            val store = FeedStore(tmp)
            val engine = FeedEngine(SourceCfg.DEFAULTS, store, http, fakeDecoder, scope, prefetchArticles = false, clock = { now }, runLoop = false)
            for (id in listOf("popular", "xkcd", "smbc", "8bt")) assertTrue(engine.fetchNow(id), id)

            // a text post's article is its own selftext; an image post is its image
            val pop = engine.items("popular", 0, 30).items
            val textPost = pop.first { it.kind == ItemKind.TEXT }
            val a = engine.article(textPost.id)
            assertTrue(a.extracted); assertTrue(a.blocks.isNotEmpty())
            val imagePost = pop.first { it.kind == ItemKind.IMAGE }
            assertEquals("img", engine.article(imagePost.id).blocks[0].kind)
            // a link post extracts its target; a page that will not extract shows the floor and is not cached
            val linkPost = pop.first { it.kind == ItemKind.LINK }
            http.on(linkPost.target, 200, PAGE, mapOf("Content-Type" to listOf("text/html")))
            val la = engine.article(linkPost.id)
            assertTrue(la.extracted); assertEquals("The Headline", la.blocks[0].text)
            assertNotNull(store.loadArticle(linkPost.id))
            val other = pop.filter { it.kind == ItemKind.LINK }[1]
            http.on(other.target, 200, "<html><body><p>nope</p></body></html>", mapOf("Content-Type" to listOf("text/html")))
            val fl = engine.article(other.id)
            assertFalse(fl.extracted); assertTrue(fl.note.startsWith("could not extract"))
            assertNull(store.loadArticle(other.id))
            // comments ride the post's .rss and are cached
            http.on(textPost.commentsUrl, 200, fixture("reddit-post-comments.atom.xml"))
            assertEquals(27, engine.comments(textPost.id).size)
            http.log.clear()
            engine.comments(textPost.id)
            assertTrue(http.log.isEmpty(), "cached comments are not refetched")
            // xkcd: the 2× is preferred, the strip is prepared, inverted, and cached by width/levels/mode
            val xk = engine.items("xkcd", 0, 1).items[0]
            assertEquals(3296, xk.num)
            http.log.clear()
            val pack = engine.comic(xk.id, 596, 16, LineArt.AUTO)
            assertEquals(listOf("https://imgs.xkcd.com/comics/fault_taunting_2x.png"), http.log)
            assertEquals(64, pack.strip.w); assertTrue(pack.strip.inverted); assertNull(pack.bonus)
            http.log.clear()
            engine.comic(xk.id, 596, 16, LineArt.AUTO)
            assertTrue(http.log.isEmpty(), "the strip is cached")
            val four = engine.comic(xk.id, 596, 4, LineArt.NEVER)
            assertFalse(four.inverted()); assertTrue(http.log.isEmpty(), "a new level re-derives from the cached image")
            // SMBC: the bonus panel is learned from the page once and kept in the item
            val sm = engine.items("smbc", 0, 1).items[0]
            http.log.clear()
            val sp = engine.comic(sm.id, 596, 16, LineArt.AUTO)
            assertNotNull(sp.bonus)
            assertTrue(http.log.contains("https://www.smbc-comics.com/comic/club-2"))
            assertEquals("https://www.smbc-comics.com/comics/178882955220260909after.png", engine.item(sm.id)!!.bonus)
            http.log.clear()
            engine.comic(sm.id, 596, 16, LineArt.AUTO)
            assertTrue(http.log.isEmpty())
            // the binge archive: indexed once, a page's image learned on first open
            val eps = engine.bingeIndex("8bt")
            assertTrue(eps.size >= 95)
            assertEquals(eps.size, engine.sources().first { it.id == "8bt" }.bingeTotal)
            val ep = engine.bingeEpisode("8bt", 1, 596, 16, LineArt.AUTO)
            assertEquals("https://www.nuklearpower.com/comics/8-bit-theater/010302.jpg", ep.episode.image)
            assertEquals(64, ep.strip.w)
            http.log.clear()
            engine.bingeEpisode("8bt", 1, 596, 16, LineArt.AUTO)
            assertTrue(http.log.isEmpty(), "index, page and strip all cached")
            // browse: a typed subreddit is a transient source; a section must exist
            http.on(RedditAtom.url("linux"), 200, fixture("reddit-popular.atom.xml"))
            val b = engine.browse(SourceKind.REDDIT, "r/Linux")
            assertEquals("r:linux", b.id); assertTrue(b.transient); assertEquals("r/Linux", b.name)
            engine.fetchNow("r:linux")
            assertEquals(25, engine.items("r:linux", 0, 30).total)
            try { engine.browse(SourceKind.SLASHDOT, "Nope"); fail("unknown section accepted") } catch (e: IllegalArgumentException) {}
            assertEquals("s:linux", engine.browse(SourceKind.SLASHDOT, "linux").id)
            engine.forget("r:linux")
            assertTrue(engine.sources().none { it.id == "r:linux" })
            engine.forget("popular")
            assertTrue(engine.sources().any { it.id == "popular" }, "a configured source is never forgotten")
            engine.close()
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); tmp.toFile().deleteRecursively() }
    }

    private fun ComicPack.inverted() = strip.inverted
}
