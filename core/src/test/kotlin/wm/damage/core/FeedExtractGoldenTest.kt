package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import wm.damage.core.windows.feed.Block
import wm.damage.core.windows.feed.Extract
import wm.damage.core.windows.feed.RedditAtom
import wm.damage.core.windows.feed.RssFetcher
import wm.damage.core.windows.feed.SlashdotRss
import wm.damage.core.windows.feed.SourceCfg
import wm.damage.core.windows.feed.SourceKind
import wm.damage.core.windows.feed.Span

/**
 * The extractor's golden file (build S1-3 stage J; the spec's B3). Context for the reader: Damage is Adam's own window
 * manager for his own glasses; Feed shows articles in its own text, and the extractor turns a fetched page (or a feed
 * item's own HTML) into the blocks every surface draws. `extract-golden.txt` beside the fixtures records what the
 * extractor produced for every fixture page and every fixture feed item's body when it was written, block by block
 * (kind, text, and an image's URL). The glasses' picture is drawn from those kinds and texts, so a change to them is a
 * change to the picture: this test fails on any difference and writes what the extractor produces now to
 * `core/build/feed-extract-golden.actual.txt`. A deliberate change (the spans and links of B3, which add to a block and
 * keep its text) shows as the diff of copying that file over the golden one — never an edit by hand.
 */
class FeedExtractGoldenTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/feed/$name")?.readBytes() ?: fail("fixture $name missing")

    /** Every HTML page under the fixtures, with the URL it was fetched from (the one `FeedTest` gives it). The captured
     *  pages are a comic, an archive page, a 404 and a story page whose prose the engine takes elsewhere, so none
     *  yields an article: `article-inline.html` (made up, stage J) is the one that does, with every inline element. */
    private val pages = linkedMapOf(
        "article-inline.html" to "https://example.org/stories/lighthouse-keepers",
        "eightbit-episode-001.html" to "https://www.nuklearpower.com/2001/03/02/episode-001-were-going-where/",
        "eightbit-episode-1224.html" to "https://www.nuklearpower.com/2010/03/20/episode-1224-a-legend-is-born/",
        "slashdot-story-page.html" to
            "https://hardware.slashdot.org/story/26/09/09/2151202/google-to-invest-record-15-billion-in-ai-infrastructure-in-finland",
        "smbc-comic-page.html" to "https://www.smbc-comics.com/comic/club-2",
        "xkcd-404.html" to "https://xkcd.com/3290/info.0.json",
    )

    /** The feeds whose items carry their own HTML (the fragment path), each through its own parser. */
    private fun feedItems(): List<Pair<String, List<wm.damage.core.windows.feed.Item>>> = listOf(
        "reddit-popular.atom.xml" to RedditAtom.parse(fixture("reddit-popular.atom.xml"), "popular"),
        "slashdot-main.rdf.xml" to SlashdotRss.parse(fixture("slashdot-main.rdf.xml"), "slashdot"),
        "slashdot-linux.rdf.xml" to SlashdotRss.parse(fixture("slashdot-linux.rdf.xml"), "s:linux"),
        "smbc.rss.xml" to RssFetcher.parse(fixture("smbc.rss.xml"),
            SourceCfg("rss:smbc", SourceKind.RSS, "smbc", url = "https://www.smbc-comics.com/comic/rss")),
    )

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun StringBuilder.blocks(bs: List<Block>) {
        if (bs.isEmpty()) appendLine("(no blocks)")
        for (b in bs) {
            append(b.kind).append('\t').append(esc(b.text))
            if (b.url.isNotEmpty()) append('\t').append(esc(b.url))
            appendLine()
        }
    }

    /** What the extractor produces now, in the golden file's form. */
    private fun render(): String = buildString {
        appendLine("# The Feed extractor's output on the test fixtures (S1-3 stage J; FeedExtractGoldenTest writes it).")
        appendLine("# One block per line: kind, TAB, text, and TAB url for an image; backslash escapes for tab, newline, CR.")
        for ((name, url) in pages) {
            appendLine("== article $name $url")
            blocks(Extract.article(fixture(name).toString(Charsets.UTF_8), url))
        }
        for ((name, items) in feedItems()) {
            for (it in items) {
                if (it.body.isBlank()) continue
                appendLine("== fragment $name ${it.id}")
                blocks(Extract.fragment(it.body, it.link))
            }
        }
    }

    @Test
    fun everyFixturePageIsInTheGoldenFile() {
        val dir = javaClass.getResource("/feed/")?.toURI()?.let { Paths.get(it) } ?: fail("the fixture directory is missing")
        val html = Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".html") }.sorted().toList() }
        assertEquals(html, pages.keys.sorted(), "a fixture page the golden file does not cover (add it with its URL)")
    }

    @Test
    fun theExtractorStillProducesTheGoldenFile() {
        val now = render()
        val golden = javaClass.getResourceAsStream("/feed/extract-golden.txt")?.readBytes()?.toString(Charsets.UTF_8)
        if (golden == now) return
        val actual: Path = Paths.get("build", "feed-extract-golden.actual.txt").toAbsolutePath()
        Files.createDirectories(actual.parent)
        Files.writeString(actual, now)
        if (golden == null) fail("no golden file: what the extractor produces now is in $actual")
        val a = golden.lines()
        val b = now.lines()
        val i = (0 until maxOf(a.size, b.size)).first { a.getOrNull(it) != b.getOrNull(it) }
        fail("the extractor's output differs from extract-golden.txt at line ${i + 1}:\n" +
            "  golden: ${a.getOrNull(i) ?: "(end of file)"}\n  now:    ${b.getOrNull(i) ?: "(end of file)"}\n" +
            "what it produces now is in $actual (${a.size} golden lines, ${b.size} now)")
    }

    /** S1-3 B3: the spans add to a block and never change its text (the test above holds the texts); a link is http or
     *  https only — no `javascript:`, `mailto:` or relative href becomes one — on every fixture page and item body. */
    @Test
    fun spansJoinToTheBlocksTextAndOnlyHttpBecomesALink() {
        val all = ArrayList<Block>()
        for ((name, url) in pages) all += Extract.article(fixture(name).toString(Charsets.UTF_8), url)
        for ((_, items) in feedItems()) for (it in items) all += Extract.fragment(it.body, it.link)
        for (b in all) if (b.spans.isNotEmpty()) {
            assertEquals(b.text, b.spans.joinToString("") { it.text }, "spans of '${b.text.take(40)}'")
            for (sp in b.spans) assertTrue(sp.href.isEmpty() || sp.href.startsWith("https://") || sp.href.startsWith("http://"), sp.href)
        }
        val inline = Extract.article(fixture("article-inline.html").toString(Charsets.UTF_8), pages.keys.first().let { pages[it]!! })
        val first = inline.first { it.text.startsWith("The first paragraph") }
        assertTrue(first.spans.any { it.text == "emphasis" && it.marks == Span.EM })
        assertTrue(first.spans.any { it.text == "strong words" && it.marks == Span.STRONG })
        assertTrue(first.spans.any { it.text == "link to the keepers' page" && it.href == "https://example.org/keepers" })
        val code = inline.first { it.text.startsWith("Inline code") }
        assertTrue(code.spans.any { it.text == "light.rotate(360)" && it.marks == Span.CODE })
        val links = inline.first { it.text.startsWith("A link that runs script") }
        assertTrue(links.spans.none { it.text == "this one" && it.href.isNotEmpty() }, "javascript: is not a link")
        assertTrue(links.spans.none { it.text == "the keeper" && it.href.isNotEmpty() }, "mailto: is not a link")
        assertTrue(links.spans.any { it.text == "nested emphasis inside a link" && it.marks == Span.EM && it.href == "https://example.org/fog" })
        assertTrue(all.any { it.spans.isNotEmpty() }, "spans exist at all")
    }
}
