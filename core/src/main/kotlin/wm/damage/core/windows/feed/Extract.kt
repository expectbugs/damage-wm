package wm.damage.core.windows.feed

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * The article extractor (FEED.md §3.6) — our own scorer on jsoup, in the
 * spirit of Mozilla's Readability but none of its code: every paragraph
 * with enough text scores its parent (and half its grandparent) by length
 * and punctuation, link-heavy containers are penalised, the best container
 * wins, and its content is walked in order into [Block]s. Empty when the
 * page yields less than a screen of prose — the caller then shows the feed's
 * own text (the "floor"), never a blank page.
 */
object Extract {

    private const val MIN_PARAGRAPH = 25
    private const val MIN_ARTICLE = 200
    private val DROP = setOf("script", "style", "noscript", "nav", "header", "footer", "aside", "form",
        "iframe", "svg", "button", "select", "input", "textarea", "template")
    private val BLOCK_HEADINGS = setOf("h1", "h2", "h3", "h4")
    private val NOISE = Regex("(?i)comment|share|social|sidebar|widget|footer|nav|menu|promo|related|newsletter|cookie|banner|advert|breadcrumb|popup|subscribe")

    fun article(html: String, baseUrl: String): List<Block> {
        val doc = try { Jsoup.parse(html, baseUrl) } catch (e: Exception) { return emptyList() }
        for (t in DROP) doc.select(t).remove()
        // candidates: an element earns points from every paragraph it holds
        val points = HashMap<Element, Double>()
        for (p in doc.select("p, pre, blockquote")) {
            val txt = p.text().trim()
            if (txt.length < MIN_PARAGRAPH) continue
            var s = 1.0 + minOf(3.0, txt.length / 100.0) + txt.count { it == ',' || it == '.' } * 0.25
            val parent = p.parent() ?: continue
            points[parent] = (points[parent] ?: 0.0) + s
            val grand = parent.parent()
            if (grand != null) points[grand] = (points[grand] ?: 0.0) + s / 2
        }
        if (points.isEmpty()) return emptyList()
        var best: Element? = null
        var bestScore = 0.0
        for ((el, pts) in points) {
            val ld = linkDensity(el)
            var score = pts * (1.0 - ld)
            val hint = (el.className() + " " + el.id())
            if (NOISE.containsMatchIn(hint)) score *= 0.5
            if (el.tagName() == "article" || el.tagName() == "main") score *= 1.25
            if (score > bestScore) { bestScore = score; best = el }
        }
        val top = best ?: return emptyList()
        val blocks = ArrayList<Block>()
        walk(top, blocks, baseUrl)
        val prose = blocks.filter { it.kind != "img" }.sumOf { it.text.length }
        return if (prose < MIN_ARTICLE) emptyList() else dedupe(blocks)
    }

    /** A feed's own fragment (a selftext, a description): kept as it is
     *  written — paragraphs, headings, lists, quotes, code, images. */
    fun fragment(html: String, baseUrl: String): List<Block> {
        if (html.isBlank()) return emptyList()
        val doc = try { Jsoup.parseBodyFragment(html, baseUrl) } catch (e: Exception) { return emptyList() }
        for (t in DROP) doc.select(t).remove()
        val blocks = ArrayList<Block>()
        walk(doc.body(), blocks, baseUrl)
        return dedupe(blocks)
    }

    /** Plain text of an HTML fragment, whitespace normalised. */
    fun text(html: String): String =
        if (html.isBlank()) "" else try { Jsoup.parseBodyFragment(html).text().trim() } catch (e: Exception) { html.trim() }

    /** Entities decoded, tags dropped, one line. */
    fun title(s: String): String = text(s).replace(Regex("\\s+"), " ").trim()

    private fun linkDensity(el: Element): Double {
        val all = el.text().length
        if (all == 0) return 0.0
        val links = el.select("a").sumOf { it.text().length }
        return (links.toDouble() / all).coerceIn(0.0, 1.0)
    }

    /** Emit blocks at block-level elements in document order; text that sits
     *  loose between blocks becomes a paragraph of its own. */
    private fun walk(root: Element, out: MutableList<Block>, baseUrl: String) {
        val loose = StringBuilder()
        fun flushLoose() {
            val t = loose.toString().replace(Regex("\\s+"), " ").trim()
            if (t.length >= MIN_PARAGRAPH) out.add(Block("p", t))
            loose.setLength(0)
        }
        fun visit(n: Node) {
            when (n) {
                is TextNode -> { loose.append(n.text()) }
                is Element -> {
                    val tag = n.tagName()
                    when {
                        tag in DROP -> {}
                        tag in BLOCK_HEADINGS -> { flushLoose(); emitText("h", n.text(), out) }
                        tag == "p" -> { flushLoose(); emitInline(n, out, baseUrl) }
                        tag == "li" -> { flushLoose(); emitText("li", n.text(), out) }
                        tag == "blockquote" -> {
                            flushLoose()
                            val ps = n.select("p")
                            if (ps.isEmpty()) emitText("q", n.text(), out)
                            else for (p in ps) emitText("q", p.text(), out)
                        }
                        tag == "pre" -> { flushLoose(); val t = n.wholeText().trim(); if (t.isNotEmpty()) out.add(Block("pre", t)) }
                        tag == "img" -> { flushLoose(); emitImage(n, out, baseUrl) }
                        tag == "figcaption" -> { flushLoose(); emitText("p", n.text(), out) }
                        tag == "br" -> loose.append(' ')
                        tag == "table" -> { flushLoose(); for (row in n.select("tr")) emitText("p", row.text(), out) }
                        else -> {
                            // a container (div, span, section, article, figure, ul…): descend
                            if (isBlockContainer(tag)) flushLoose()
                            for (c in n.childNodes()) visit(c)
                            if (isBlockContainer(tag)) flushLoose()
                        }
                    }
                }
                else -> {}
            }
        }
        for (c in root.childNodes()) visit(c)
        flushLoose()
    }

    private fun isBlockContainer(tag: String) =
        tag in setOf("div", "section", "article", "main", "figure", "ul", "ol", "dl", "dd", "dt", "td", "tr", "body", "center")

    /** A paragraph: its text, plus any image it holds as its own block. */
    private fun emitInline(p: Element, out: MutableList<Block>, baseUrl: String) {
        val imgs = p.select("img")
        emitText("p", p.text(), out)
        for (img in imgs) emitImage(img, out, baseUrl)
    }

    private fun emitText(kind: String, raw: String, out: MutableList<Block>) {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return
        out.add(Block(kind, t))
    }

    private fun emitImage(img: Element, out: MutableList<Block>, baseUrl: String) {
        var src = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }.ifEmpty { img.attr("src") }
        if (src.isBlank() || src.startsWith("data:")) return
        val w = img.attr("width").toIntOrNull()
        val h = img.attr("height").toIntOrNull()
        if ((w != null && w < 48) || (h != null && h < 48)) return       // pixels, badges, spacers
        val lower = src.lowercase()
        if (lower.contains("pixel") || lower.contains("spacer") || lower.contains("1x1") || lower.contains("/badge")) return
        out.add(Block("img", img.attr("alt").trim(), src))
    }

    /** The same text emitted twice in a row (a container and its only child both scoring) collapses. */
    private fun dedupe(blocks: List<Block>): List<Block> {
        val out = ArrayList<Block>(blocks.size)
        val seenImg = HashSet<String>()
        for (b in blocks) {
            if (b.kind == "img") { if (!seenImg.add(b.url)) continue }
            else if (out.isNotEmpty() && out.last().kind == b.kind && out.last().text == b.text) continue
            out.add(b)
        }
        return out
    }
}
