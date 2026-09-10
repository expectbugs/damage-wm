package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import wm.damage.core.windows.feed.*

/**
 * `--feed-check [DIR] [live]` (`FEED.md` §4): the engine end to end through
 * the REAL image decoder — parse → store → extract → strips — over the
 * captured fixtures by default (offline, deterministic), or against the
 * live sites with `live` (one paced fetch per configured source, read-only,
 * everything in a temp directory that is deleted after). The
 * `--music-check` shape; exit 1 on any failure.
 */
object FeedCheck {
    private val failures = ArrayList<String>()

    private fun check(what: String, ok: Boolean) {
        println("  ${if (ok) "PASS" else "FAIL"}  $what")
        if (!ok) failures.add(what)
    }

    /** deflate(packed nibbles) — a PROXY for the wire (the compositor ships RLE+zlib of the frame). */
    private fun deflated(s: Strip): Int {
        val d = Deflater(6)
        d.setInput(s.packed); d.finish()
        val buf = ByteArray(s.packed.size + 64)
        var n = 0
        while (!d.finished()) n += d.deflate(buf, 0, buf.size)
        d.end()
        return n
    }

    private fun stripLine(label: String, s: Strip): String =
        "$label ${s.w}x${s.h} · ${s.levels} levels · ${if (s.inverted) "inverted" else "as-is"} · " +
            "ink ${"%.1f".format(java.util.Locale.ROOT, Strips.ink(s) * 100)} % · packed ${s.packed.size / 1024} KB · deflate ${deflated(s) / 1024} KB"

    fun run(cfg: Config, live: Boolean, fixtures: Path): Nothing {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-feed-check")
        try {
            if (live) liveRun(cfg, scope, tmp) else fixtureRun(scope, tmp, fixtures)
        } catch (e: Throwable) {
            e.printStackTrace()
            failures.add("feed check ended early: $e")
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
        if (failures.isNotEmpty()) {
            println("feed-check: ${failures.size} FAILURE(S):")
            for (f in failures) println("  - $f")
            kotlin.system.exitProcess(1)
        }
        println("feed-check: all checks passed")
        kotlin.system.exitProcess(0)
    }

    // ------------------------------------------------------------ fixtures
    private fun fixtureRun(scope: CoroutineScope, tmp: Path, dir: Path) {
        // gradle's `run` works from the module directory, the jar from wherever it
        // was started: look at the given path, then one level up
        val found = listOf(dir, Path.of("..").resolve(dir)).firstOrNull { Files.isDirectory(it) }
            ?: throw IllegalStateException("fixtures not found at $dir — run from the repo root or pass the directory")
        return fixtureRunAt(scope, tmp, found.toAbsolutePath().normalize())
    }

    private fun fixtureRunAt(scope: CoroutineScope, tmp: Path, dir: Path) {
        fun fx(name: String): ByteArray = Files.readAllBytes(dir.resolve(name))
        val png = fx("xkcd-3200.png")
        val routes = HashMap<String, Pair<Int, ByteArray>>()
        val headers = HashMap<String, Map<String, List<String>>>()
        routes[RedditAtom.url("popular")] = 200 to fx("reddit-popular.atom.xml")
        routes[SlashdotRss.url("Main")] = 200 to fx("slashdot-main.rdf.xml")
        routes[XkcdFetcher.latestUrl()] = 200 to fx("xkcd-latest.json")
        for (n in (3296 - XkcdFetcher.WINDOW + 1) until 3296) routes[XkcdFetcher.url(n)] = 404 to "gone".toByteArray()
        routes["https://imgs.xkcd.com/comics/fault_taunting_2x.png"] = 404 to ByteArray(0)
        routes["https://imgs.xkcd.com/comics/fault_taunting.png"] = 200 to png
        routes[SmbcFetcher.URL] = 200 to fx("smbc.rss.xml")
        routes["https://www.smbc-comics.com/comic/club-2"] = 200 to fx("smbc-comic-page.html")
        routes["https://www.smbc-comics.com/comics/1788823653-20260909.png"] = 200 to png
        routes["https://www.smbc-comics.com/comics/178882955220260909after.png"] = 200 to png
        routes[EightBit.pageUrl(1)] = 200 to fx("eightbit-rest-page1.json")
        headers[EightBit.pageUrl(1)] = mapOf("X-WP-TotalPages" to listOf("1"))
        routes["https://www.nuklearpower.com/2001/03/02/episode-001-were-going-where/"] = 200 to fx("eightbit-episode-001.html")
        routes["https://www.nuklearpower.com/comics/8-bit-theater/010302.jpg"] = 200 to png
        val http = FeedHttp { url ->
            val r = routes[url] ?: (404 to "no fixture for $url".toByteArray())
            HttpReplyB(r.first, headers[url] ?: emptyMap(), r.second)
        }
        println("fixtures from $dir")
        val engine = FeedEngine(SourceCfg.DEFAULTS, FeedStore(tmp), http, AwtImages(), scope,
            prefetchArticles = false, engineLabel = "check", runLoop = false)
        for (s in SourceCfg.DEFAULTS) {
            val t0 = System.currentTimeMillis()
            engine.fetchNow(s.id)
            val st = engine.sources().first { it.id == s.id }
            println("${s.name}: ${st.count} items · v${st.version} · ${if (st.state.isEmpty()) "ok" else st.state} · ${System.currentTimeMillis() - t0} ms" +
                (if (st.binge) " · ${st.bingeTotal} pages" else ""))
            check("${s.name} fetched cleanly", st.state.isEmpty())
            if (!st.binge) for (it in engine.items(s.id, 0, 3).items) println("    ${it.kind.name.lowercase().padEnd(5)} ${it.title.take(70)}")
        }
        check("Reddit popular parsed 25 entries", engine.sources().first { it.id == "popular" }.count == 25)
        check("Slashdot parsed 15 stories", engine.sources().first { it.id == "slashdot" }.count == 15)
        check("SMBC parsed 20 strips", engine.sources().first { it.id == "smbc" }.count == 20)
        check("8-Bit Theater indexed the first page", engine.sources().first { it.id == "8bt" }.bingeTotal >= 95)
        // the real decoder: xkcd 3200 through the comic path
        val xk = engine.items("xkcd", 0, 1).items[0]
        val pack = engine.comic(xk.id, 596, 16, LineArt.AUTO)
        println(stripLine("xkcd ${xk.num} '${xk.title}':", pack.strip))
        check("xkcd fits the 596 column at 0.81x (596x180)", pack.strip.w == 596 && pack.strip.h == 180)
        check("xkcd line art is drawn in light (inverted)", pack.strip.inverted)
        check("inverted xkcd is mostly unlit", Strips.ink(pack.strip) < 0.2)
        val four = engine.comic(xk.id, 596, 4, LineArt.AUTO)
        println(stripLine("xkcd ${xk.num} at 4 levels:", four.strip))
        val sm = engine.items("smbc", 0, 1).items[0]
        val sp = engine.comic(sm.id, 596, 16, LineArt.AUTO)
        println(stripLine("SMBC '${sm.title}':", sp.strip) + (sp.bonus?.let { " · bonus ${it.w}x${it.h}" } ?: " · no bonus"))
        check("SMBC bonus panel found on the page", sp.bonus != null)
        val ep = engine.bingeEpisode("8bt", 1, 596, 16, LineArt.AUTO)
        println(stripLine("8BT episode ${ep.episode.num} '${ep.episode.title}':", ep.strip))
        check("8BT episode image learned from the page", ep.episode.image.endsWith("010302.jpg"))
        // the extractor over the two captured pages (HTML samples, not articles)
        for ((name, url) in listOf("eightbit-episode-1224.html" to "https://www.nuklearpower.com/2010/03/20/x/", "smbc-comic-page.html" to "https://www.smbc-comics.com/comic/club-2")) {
            val blocks = Extract.article(fx(name).toString(Charsets.UTF_8), url)
            println("extract $name: ${blocks.size} blocks (${blocks.count { it.kind == "img" }} images) — a comic page has little prose, so few or none is right")
        }
        val cs = RedditAtom.parseComments(fx("reddit-post-comments.atom.xml"))
        println("comments fixture: ${cs.size} comments · first by ${cs[0].author}")
        check("comments parse", cs.size == 27)
        engine.close()
    }

    // ------------------------------------------------------------ live
    private fun liveRun(cfg: Config, scope: CoroutineScope, tmp: Path) {
        println("LIVE: one paced fetch per configured source, user agent '${cfg.feedUserAgent}', files under $tmp (deleted after)")
        val http = PacedHttp(RealFeedHttp(cfg.feedUserAgent))
        val engine = FeedEngine(cfg.feedSources, FeedStore(tmp), http, AwtImages(), scope,
            prefetchArticles = false, engineLabel = "check", runLoop = false)
        for (s in cfg.feedSources) {
            val t0 = System.currentTimeMillis()
            engine.fetchNow(s.id)
            val st = engine.sources().first { it.id == s.id }
            println("${s.name}: ${st.count} items · ${if (st.state.isEmpty()) "ok" else st.state} · ${System.currentTimeMillis() - t0} ms" +
                (if (st.binge) " · ${st.bingeTotal} pages" else ""))
            check("${s.name} fetched cleanly", st.state.isEmpty())
            if (!st.binge) for (it in engine.items(s.id, 0, 3).items) println("    ${it.kind.name.lowercase().padEnd(5)} ${it.title.take(70)}  [${it.extra} ${FeedFmt.age(it.publishedMs)}]")
        }
        fun comicOf(id: String, label: String) {
            val src = engine.sources().firstOrNull { it.id == id } ?: return
            if (src.count == 0) return
            val it = engine.items(id, 0, 1).items[0]
            val t0 = System.currentTimeMillis()
            try {
                val pack = engine.comic(it.id, 596, 16, LineArt.AUTO)
                println(stripLine("$label '${it.title}':", pack.strip) + (pack.bonus?.let { " · bonus ${it.w}x${it.h}" } ?: "") + " · ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) { check("$label strip: ${e.message}", false) }
        }
        comicOf("xkcd", "xkcd"); comicOf("smbc", "SMBC")
        val bt = engine.sources().firstOrNull { it.binge }
        if (bt != null && bt.bingeTotal > 0) {
            val t0 = System.currentTimeMillis()
            try {
                val ep = engine.bingeEpisode(bt.id, 1, 596, 16, LineArt.AUTO)
                println(stripLine("8BT episode 1 '${ep.episode.title}':", ep.strip) + " · ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) { check("8BT episode 1: ${e.message}", false) }
        }
        // one article and one comment thread from the first source that has them
        for (st in engine.sources()) {
            if (st.binge || st.count == 0) continue
            val link = engine.items(st.id, 0, 30).items.firstOrNull { it.kind == ItemKind.LINK } ?: continue
            val t0 = System.currentTimeMillis()
            try {
                val a = engine.article(link.id)
                println("article '${link.title.take(50)}': ${a.blocks.size} blocks · ${if (a.extracted) "extracted" else "floor: ${a.note}"} · ${System.currentTimeMillis() - t0} ms")
                a.blocks.take(2).forEach { println("    ${it.kind}: ${it.text.take(90)}") }
            } catch (e: Exception) { println("article '${link.title.take(50)}': ${e.message}") }
            if (link.commentsUrl.isNotEmpty()) {
                try {
                    val cs = engine.comments(link.id)
                    println("comments: ${cs.size} · first by ${cs.firstOrNull()?.author ?: "-"}")
                } catch (e: RateLimited) { println("comments: ${e.message} (the pace — expected right after the listing)") }
                catch (e: Exception) { check("comments: ${e.message}", false) }
            }
            break
        }
        engine.close()
    }
}
