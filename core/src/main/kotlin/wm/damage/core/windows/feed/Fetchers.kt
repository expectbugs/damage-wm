package wm.damage.core.windows.feed

import java.io.IOException
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.w3c.dom.Element
import wm.damage.core.util.Log

/**
 * The per-kind fetchers (FEED.md §2, probed live 2026-09-09). Each turns one
 * source's feed into [Item]s and NOTHING else — images, article text,
 * comments and archives are fetched on demand by the engine. Every parse
 * refuses what it does not recognise and says so: a format change is a loud
 * source state line, never a quietly empty list.
 */
interface Fetcher {
    /** [known] = the items this engine already holds for the source, so a
     *  fetcher that walks by number (xkcd) skips what it has. */
    fun fetch(src: SourceCfg, http: FeedHttp, known: List<Item>, nowMs: Long): List<Item>
}

object Fetchers {
    fun forKind(kind: SourceKind): Fetcher? = when (kind) {
        SourceKind.REDDIT -> RedditAtom
        SourceKind.SLASHDOT -> SlashdotRss
        SourceKind.XKCD -> XkcdFetcher
        SourceKind.SMBC -> SmbcFetcher
        SourceKind.RSS -> RssFetcher
        SourceKind.EIGHTBIT -> null                 // an archive, not a feed (EightBit)
    }

    /** A feed answer that is not 200 is a refusal with its status — said, never parsed. */
    fun body(r: HttpReplyB, what: String): ByteArray {
        if (r.status != 200) throw IOException("$what answered HTTP ${r.status}")
        if (r.body.isEmpty()) throw IOException("$what answered an empty body")
        return r.body
    }
}

// ================================================================== Reddit (Atom)
/**
 * `https://www.reddit.com/r/<sub>/.rss` — Atom (FEED.md §2.1). Per entry:
 * `id` (t3_…), `title`, `link href` (the permalink), `author/name`,
 * `published`, `category term` (the subreddit), `media:thumbnail url`, and
 * `content` holding "submitted by … [link] [comments]" HTML — the `[link]`
 * anchor is where the post points; a text post carries its selftext in a
 * `div.md` before it. Comments per post: `<permalink>.rss`, flat.
 */
object RedditAtom : Fetcher {
    private val IMAGE_HOSTS = setOf("i.redd.it", "preview.redd.it", "i.imgur.com")
    private val VIDEO_HOSTS = setOf("v.redd.it", "youtube.com", "www.youtube.com", "youtu.be", "m.youtube.com",
        "twitch.tv", "www.twitch.tv", "clips.twitch.tv", "streamable.com", "gfycat.com", "redgifs.com", "www.redgifs.com")

    fun url(sub: String) = "https://www.reddit.com/r/${sub.trim().removePrefix("r/")}/.rss"

    override fun fetch(src: SourceCfg, http: FeedHttp, known: List<Item>, nowMs: Long): List<Item> {
        val r = http.get(url(src.sub))
        return parse(Fetchers.body(r, "Reddit r/${src.sub}"), src.id)
    }

    fun parse(bytes: ByteArray, sourceId: String): List<Item> {
        val root = Xml.parse(bytes)
        if (Xml.local(root) != "feed") throw IOException("Reddit format changed: root <${root.nodeName}>, not <feed>")
        val out = ArrayList<Item>()
        for (e in Xml.children(root, "entry")) {
            val entryId = Xml.text(e, "id")
            val title = Extract.title(Xml.text(e, "title"))
            val permalink = Xml.child(e, "link")?.let { Xml.attr(it, "href") } ?: ""
            if (entryId.isEmpty() || permalink.isEmpty()) throw IOException("Reddit format changed: an entry without id/link")
            val author = Xml.child(e, "author")?.let { Xml.text(it, "name") }?.removePrefix("/u/") ?: ""
            val published = FeedDates.parse(Xml.text(e, "published").ifEmpty { Xml.text(e, "updated") })
            val sub = Xml.child(e, "category")?.let { Xml.attr(it, "term") } ?: ""
            val thumb = Xml.child(e, "thumbnail")?.let { Xml.attr(it, "url") } ?: ""
            val content = Xml.text(e, "content")
            var target = ""
            var bodyHtml = ""
            var summary = ""
            if (content.isNotEmpty()) {
                val doc = Jsoup.parseBodyFragment(content, permalink)
                for (a in doc.select("a")) {
                    if (a.text().trim() == "[link]") target = a.attr("abs:href")
                }
                val md = doc.selectFirst("div.md")
                if (md != null) {
                    bodyHtml = md.html()
                    summary = md.text().trim().take(300)
                }
            }
            // a gallery post points back at reddit.com/gallery/…: the feed's
            // 640 px preview of its first image is what can be shown
            val gallery = target.contains("reddit.com/gallery/")
            val kind = when {
                target.isEmpty() || sameLink(target, permalink) -> ItemKind.TEXT
                gallery -> if (thumb.isNotEmpty()) ItemKind.IMAGE else ItemKind.LINK
                isImage(target) -> ItemKind.IMAGE
                isVideo(target) -> ItemKind.VIDEO
                else -> ItemKind.LINK
            }
            out.add(Item(
                id = FeedIds.id(sourceId, entryId), source = sourceId, title = title, link = permalink,
                author = author, publishedMs = published, summary = summary, body = bodyHtml, kind = kind,
                target = if (kind == ItemKind.TEXT) permalink else target,
                comments = -1, commentsUrl = permalink.trimEnd('/') + "/.rss",
                image = if (kind == ItemKind.IMAGE) (if (gallery) thumb else target) else "",
                extra = if (sub.isNotEmpty()) "r/$sub" else "",
            ))
        }
        return out
    }

    private fun sameLink(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')

    private fun host(u: String): String = try { URI(u).host?.lowercase() ?: "" } catch (e: Exception) { "" }

    fun isImage(u: String): Boolean {
        val h = host(u)
        if (h in IMAGE_HOSTS) return true
        val path = try { URI(u).path?.lowercase() ?: "" } catch (e: Exception) { u.lowercase() }
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".gif") || path.endsWith(".webp")
    }

    fun isVideo(u: String): Boolean = host(u) in VIDEO_HOSTS

    /** `<permalink>.rss`: entries are the comments, flat, HTML in `content`. */
    fun parseComments(bytes: ByteArray): List<Comment> {
        val root = Xml.parse(bytes)
        if (Xml.local(root) != "feed") throw IOException("Reddit comments format changed: root <${root.nodeName}>")
        val out = ArrayList<Comment>()
        val entries = Xml.children(root, "entry")
        // the first entry is the post itself (its id starts with t3_); the rest are comments (t1_)
        for (e in entries) {
            val id = Xml.text(e, "id")
            if (id.startsWith("t3_")) continue
            val author = Xml.child(e, "author")?.let { Xml.text(it, "name") }?.removePrefix("/u/") ?: ""
            val published = FeedDates.parse(Xml.text(e, "published").ifEmpty { Xml.text(e, "updated") })
            val html = Xml.text(e, "content")
            val md = try { Jsoup.parseBodyFragment(html).selectFirst("div.md") } catch (ex: Exception) { null }
            val text = (md?.let { blockText(it) } ?: Extract.text(html)).trim()
            if (text.isEmpty()) continue
            out.add(Comment(author, published, text, 0))
        }
        return out
    }

    /** Paragraph breaks kept as newlines so a long comment wraps as it was written. */
    private fun blockText(md: org.jsoup.nodes.Element): String {
        val ps = md.select("p, li, blockquote, pre")
        if (ps.isEmpty()) return md.text()
        return ps.joinToString("\n") { it.text().trim() }.replace(Regex("\n{2,}"), "\n")
    }
}

// ================================================================== Slashdot (RSS 1.0 / RDF)
/**
 * `https://rss.slashdot.org/Slashdot/slashdot<Section>` — RDF (FEED.md §2.2):
 * `<item rdf:about>` with `title`, `link`, `description` (the summary,
 * HTML), `dc:creator`, `dc:date`, `slash:section`, `slash:comments` (the
 * count), `slash:department`. The section table was probed 2026-09-09.
 */
object SlashdotRss : Fetcher {
    /** The feeds that exist (200 on 2026-09-09); the others answer 404. */
    val SECTIONS = listOf("Main", "Apple", "AskSlashdot", "Developers", "Games", "Hardware", "IT",
        "Linux", "Mobile", "Politics", "Science", "Search")

    fun url(section: String) = "https://rss.slashdot.org/Slashdot/slashdot${section.trim().ifEmpty { "Main" }}"

    override fun fetch(src: SourceCfg, http: FeedHttp, known: List<Item>, nowMs: Long): List<Item> {
        val r = http.get(url(src.section))
        return parse(Fetchers.body(r, "Slashdot ${src.section.ifEmpty { "Main" }}"), src.id)
    }

    fun parse(bytes: ByteArray, sourceId: String): List<Item> {
        val root = Xml.parse(bytes)
        val items = Xml.descendants(root, "item")
        if (items.isEmpty() && Xml.local(root) != "RDF") throw IOException("Slashdot format changed: root <${root.nodeName}>")
        val out = ArrayList<Item>()
        for (it in items) {
            val title = Extract.title(Xml.text(it, "title"))
            val link = Xml.text(it, "origLink").ifEmpty { Xml.text(it, "link") }.ifEmpty { Xml.attr(it, "rdf:about") }
            if (title.isEmpty() || link.isEmpty()) throw IOException("Slashdot format changed: an item without title/link")
            val desc = Xml.text(it, "description")
            val summary = Extract.text(desc).take(400)
            val key = link.substringBefore('?')
            out.add(Item(
                id = FeedIds.id(sourceId, key), source = sourceId, title = title, link = key,
                author = Xml.text(it, "creator"), publishedMs = FeedDates.parse(Xml.text(it, "date")),
                summary = summary, body = desc, kind = ItemKind.LINK, target = key,
                comments = Xml.text(it, "comments").toIntOrNull() ?: -1, commentsUrl = key,
                extra = Xml.text(it, "section"),
            ))
        }
        return out
    }

    private val NOT_A_SOURCE = listOf("slashdot.org", "fsdn.com", "slashdotmedia.com", "twitter.com", "facebook.com", "x.com")

    /** The story's source: the first link in the story body that leaves Slashdot
     *  (measured 2026-09-09: the RSS description carries only share links; the
     *  page's `div.body` links the source in its prose). "" when there is none. */
    fun sourceFrom(html: String, pageUrl: String): String {
        val doc = Jsoup.parse(html, pageUrl)
        val body = doc.selectFirst("div.body") ?: doc.selectFirst("article") ?: return ""
        for (a in body.select("a[href]")) {
            val u = a.attr("abs:href")
            if (!u.startsWith("http")) continue
            val host = try { URI(u).host?.lowercase() ?: "" } catch (e: Exception) { continue }
            if (NOT_A_SOURCE.any { host == it || host.endsWith(".$it") }) continue
            return u
        }
        return ""
    }

    /** What a story page holds of its thread: the comments it rendered (the top
     *  of the thread by score, with their depth from the `commtree` nesting) and
     *  the ids of the ones it did not — hidden below the threshold or listed in
     *  `D2.noshow_comments` — which [fetchComments] asks `ajax.pl` for. Also
     *  the discussion id that call needs. Measured 2026-09-09 (FEED.md §8.2). */
    class Thread(val comments: List<Comment>, val missing: List<String>, val discussionId: String, val host: String)

    fun parseThread(html: String, pageUrl: String): Thread {
        val doc = Jsoup.parse(html, pageUrl)
        val out = ArrayList<Comment>()
        val missing = ArrayList<String>()
        val listing = doc.selectFirst("#commentlisting")
        if (listing != null) {
            for (li in listing.select("li[id^=tree_]")) {
                val cid = li.id().removePrefix("tree_")
                val depth = li.parents().count { it.tagName() == "li" && it.id().startsWith("tree_") }
                val body = doc.selectFirst("#comment_body_$cid")
                if (body == null || li.hasClass("hidden")) { missing.add(cid); continue }
                out.add(commentNode(doc, cid, depth, body))
            }
        }
        Regex("""D2\.noshow_comments\(\[([0-9,\s]*)\]\)""").find(html)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { missing.addAll(it) }
        val did = Regex("""D2\.discussion_id\((\d+)\)""").find(html)?.groupValues?.get(1) ?: ""
        val host = try { URI(pageUrl).host ?: "slashdot.org" } catch (e: Exception) { "slashdot.org" }
        return Thread(out, missing.distinct(), did, host)
    }

    private fun commentNode(doc: org.jsoup.nodes.Document, cid: String, depth: Int, body: org.jsoup.nodes.Element): Comment {
        val title = doc.selectFirst("#comment_link_$cid")?.text()?.trim() ?: ""
        val score = doc.selectFirst("#comment_score_$cid")?.text()?.let { it.trim().removePrefix("(").removeSuffix(")").removePrefix("Score:").trim() } ?: ""
        val top = doc.selectFirst("#comment_top_$cid")
        val by = top?.selectFirst(".details .by")?.text()?.trim()?.removePrefix("by")?.trim() ?: ""
        val text = body.select("p, br").let { _ ->
            // paragraphs as newlines; a bare body is one paragraph
            val ps = body.select("p")
            if (ps.isEmpty()) body.text().trim() else ps.joinToString("\n") { it.text().trim() }.replace(Regex("\n{2,}"), "\n")
        }
        return Comment(by, 0L, text, depth, title = title, score = score)
    }

    /** `POST /ajax.pl op=comments_fetch` for the ids the page left out. The
     *  answer is a JS object literal, not JSON: `{ eval_first: "…", html: {
     *  comment_<cid>: "<html>", … } }` — read with a small scanner. */
    fun fetchMissing(http: FeedHttp, thread: Thread, cids: List<String>): List<Comment> {
        if (cids.isEmpty() || thread.discussionId.isEmpty()) return emptyList()
        val r = http.post("https://${thread.host}/ajax.pl", mapOf(
            "op" to "comments_fetch", "discussion_id" to thread.discussionId, "cids" to cids.joinToString(","),
            "threshold" to "-1", "highlightthresh" to "4", "abbreviated" to "", "read_comments" to "", "pieces" to "",
        ), mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to "https://${thread.host}/"))
        if (r.status != 200) throw java.io.IOException("Slashdot comments answered HTTP ${r.status}")
        return parseFetched(r.text())
    }

    fun parseFetched(js: String): List<Comment> {
        val out = ArrayList<Comment>()
        val re = Regex("""comment_(\d+):\s*"((?:[^"\\]|\\.)*)"""")
        for (m in re.findAll(js)) {
            val cid = m.groupValues[1]
            val html = unescapeJs(m.groupValues[2])
            val doc = Jsoup.parseBodyFragment(html)
            val body = doc.selectFirst("#comment_body_$cid") ?: doc.selectFirst("#comment_body_") ?: continue
            // the fetched block carries the cid only in its ids when the server fills them
            out.add(commentNode(doc, cid, 0, body))
        }
        return out
    }

    private fun unescapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> {}
                    '/' -> sb.append('/'); '"' -> sb.append('"'); '\\' -> sb.append('\\')
                    'u' -> if (i + 5 < s.length) { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 } else sb.append(n)
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}

// ================================================================== xkcd (JSON)
/**
 * `https://xkcd.com/info.0.json` (latest) and `/<n>/info.0.json` (FEED.md
 * §2.3): `num`, `safe_title`, `alt`, `img`, `day/month/year`. Walked by
 * number from the latest down over [WINDOW] strips; a missing number (404 is
 * famously absent) is skipped and logged, never a source failure.
 */
object XkcdFetcher : Fetcher {
    const val WINDOW = 30
    private val json = Json { ignoreUnknownKeys = true }

    fun latestUrl() = "https://xkcd.com/info.0.json"
    fun url(num: Int) = "https://xkcd.com/$num/info.0.json"

    /** The 2× variant's URL, tried first when a strip is fetched (recent strips have one). */
    fun twoX(img: String): String? {
        val dot = img.lastIndexOf('.')
        if (dot <= 0 || img.contains("_2x.")) return null
        return img.substring(0, dot) + "_2x" + img.substring(dot)
    }

    override fun fetch(src: SourceCfg, http: FeedHttp, known: List<Item>, nowMs: Long): List<Item> {
        val latest = parseOne(Fetchers.body(http.get(latestUrl()), "xkcd latest"), src.id)
        val byNum = known.associateBy { it.num }
        val out = ArrayList<Item>()
        out.add(latest)
        var missing = 0
        for (n in (latest.num - 1) downTo maxOf(1, latest.num - WINDOW + 1)) {
            val have = byNum[n]
            if (have != null) { out.add(have); continue }
            val r = http.get(url(n))
            if (r.status == 404) { missing++; Log.i("feed-xkcd", "xkcd $n does not exist — skipped"); continue }
            out.add(parseOne(Fetchers.body(r, "xkcd $n"), src.id))
        }
        return out
    }

    fun parseOne(bytes: ByteArray, sourceId: String): Item {
        val o = try { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject } catch (e: Exception) {
            throw IOException("xkcd format changed: not a JSON object (${e.message})")
        }
        val num = o["num"]?.jsonPrimitive?.intOrNull ?: throw IOException("xkcd format changed: no 'num'")
        val img = o["img"]?.jsonPrimitive?.contentOrNull ?: throw IOException("xkcd format changed: no 'img'")
        val title = Extract.title(o["safe_title"]?.jsonPrimitive?.contentOrNull ?: o["title"]?.jsonPrimitive?.contentOrNull ?: "#$num")
        val alt = o["alt"]?.jsonPrimitive?.contentOrNull ?: ""
        val y = o["year"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val m = o["month"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val d = o["day"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return Item(
            id = FeedIds.id(sourceId, "xkcd:$num"), source = sourceId, title = title, link = "https://xkcd.com/$num/",
            publishedMs = FeedDates.day(y, m, d), summary = alt, kind = ItemKind.COMIC,
            image = img, alt = alt, num = num,
        )
    }
}

// ================================================================== SMBC (RSS 2.0)
/**
 * `https://www.smbc-comics.com/comic/rss` (FEED.md §2.5): items with
 * `title` ("Saturday Morning Breakfast Cereal - <name>"), `link`, `pubDate`,
 * and a `description` holding the strip's `<img>` and a `Hovertext:`
 * paragraph. The bonus panel sits on the comic page in a hidden
 * `#aftercomic` div — fetched when the comic is first opened.
 */
object SmbcFetcher : Fetcher {
    const val URL = "https://www.smbc-comics.com/comic/rss"

    override fun fetch(src: SourceCfg, http: FeedHttp, known: List<Item>, nowMs: Long): List<Item> {
        val fresh = parse(Fetchers.body(http.get(URL), "SMBC"), src.id)
        // keep a bonus URL already learned for an item the feed still lists
        val bonus = known.associate { it.id to it.bonus }
        return fresh.map { it -> if (it.bonus.isEmpty() && !bonus[it.id].isNullOrEmpty()) it.copy(bonus = bonus[it.id]!!) else it }
    }

    fun parse(bytes: ByteArray, sourceId: String): List<Item> {
        val root = Xml.parse(bytes)
        val items = Xml.descendants(root, "item")
        if (items.isEmpty()) throw IOException("SMBC format changed: no <item> in the feed")
        val out = ArrayList<Item>()
        for (it in items) {
            val rawTitle = Xml.text(it, "title")
            val title = Extract.title(rawTitle.substringAfter(" - ", rawTitle))
            val link = Xml.text(it, "link")
            val desc = Xml.text(it, "description")
            if (link.isEmpty() || desc.isEmpty()) throw IOException("SMBC format changed: an item without link/description")
            val doc = Jsoup.parseBodyFragment(desc, link)
            val img = doc.selectFirst("img")?.attr("abs:src") ?: throw IOException("SMBC format changed: no image in '$title'")
            var hover = ""
            for (p in doc.select("p")) {
                val t = p.text()
                if (t.startsWith("Hovertext:")) hover = t.removePrefix("Hovertext:").trim()
            }
            out.add(Item(
                id = FeedIds.id(sourceId, link), source = sourceId, title = title, link = link,
                publishedMs = FeedDates.parse(Xml.text(it, "pubDate")), summary = hover, kind = ItemKind.COMIC,
                image = img, alt = hover,
            ))
        }
        return out
    }

    /** The comic page's hidden bonus panel (`#aftercomic img`), or "" when the page has none. */
    fun bonusFromPage(html: String, pageUrl: String): String {
        val doc = Jsoup.parse(html, pageUrl)
        val img = doc.selectFirst("#aftercomic img") ?: return ""
        return img.attr("abs:src")
    }
}

// ================================================================== generic RSS 2.0 / Atom
/** A later title (FEED.md §3.7 `kind: "rss"`): RSS 2.0 or Atom; with
 *  `image: true` the entry's first image is the strip and its alt/title the
 *  hover text. */
object RssFetcher : Fetcher {
    override fun fetch(src: SourceCfg, http: FeedHttp, known: List<Item>, nowMs: Long): List<Item> {
        if (src.url.isEmpty()) throw IOException("source '${src.id}' has no url")
        return parse(Fetchers.body(http.get(src.url), src.name), src)
    }

    fun parse(bytes: ByteArray, src: SourceCfg): List<Item> {
        val root = Xml.parse(bytes)
        val out = ArrayList<Item>()
        val atom = Xml.local(root) == "feed"
        val entries = if (atom) Xml.children(root, "entry") else Xml.descendants(root, "item")
        if (entries.isEmpty()) throw IOException("${src.name}: no entries in the feed")
        for (e in entries) {
            val title = Extract.title(Xml.text(e, "title"))
            val link = if (atom) {
                Xml.children(e, "link").firstOrNull { Xml.attr(it, "rel").let { r -> r.isEmpty() || r == "alternate" } }
                    ?.let { Xml.attr(it, "href") } ?: ""
            } else Xml.text(e, "link")
            val guid = if (atom) Xml.text(e, "id") else Xml.text(e, "guid")
            val key = guid.ifEmpty { link }
            if (key.isEmpty()) throw IOException("${src.name}: an entry without id or link")
            val html = if (atom) Xml.text(e, "content").ifEmpty { Xml.text(e, "summary") }
                else Xml.text(e, "encoded").ifEmpty { Xml.text(e, "description") }
            val date = if (atom) Xml.text(e, "published").ifEmpty { Xml.text(e, "updated") }
                else Xml.text(e, "pubDate").ifEmpty { Xml.text(e, "date") }
            val author = if (atom) Xml.child(e, "author")?.let { Xml.text(it, "name") } ?: "" else Xml.text(e, "creator").ifEmpty { Xml.text(e, "author") }
            var image = ""
            var alt = ""
            if (src.image && html.isNotEmpty()) {
                val img = Jsoup.parseBodyFragment(html, link).selectFirst("img")
                if (img != null) { image = img.attr("abs:src"); alt = img.attr("title").ifEmpty { img.attr("alt") } }
            }
            out.add(Item(
                id = FeedIds.id(src.id, key), source = src.id, title = title, link = link, author = author,
                publishedMs = FeedDates.parse(date), summary = Extract.text(html).take(300), body = html,
                kind = if (image.isNotEmpty()) ItemKind.COMIC else ItemKind.LINK, target = link,
                image = image, alt = alt,
            ))
        }
        return out
    }
}

// ================================================================== 8-Bit Theater (WordPress archive)
/**
 * FEED.md §2.4 — the archive: `wp-json/wp/v2/posts?categories=4&per_page=100
 * &order=asc&orderby=date&_fields=id,date,link,title` over its pages
 * (`X-WP-TotalPages`); episodes are the posts titled `Episode NNN: …`, the
 * rest are skipped and counted. Each episode page carries one comic image,
 * `<img src="…/comics/8-bit-theater/YYMMDD.(jpg|png)">`.
 */
object EightBit {
    const val BASE = "https://www.nuklearpower.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val EPISODE = Regex("^Episode\\s+0*(\\d+)\\s*:\\s*(.*)$")

    fun pageUrl(page: Int) = "$BASE/wp-json/wp/v2/posts?categories=4&per_page=100&page=$page&order=asc&orderby=date&_fields=id,date,link,title"

    /** Walk every page once; ~14 requests, paced by the http seam. */
    fun index(http: FeedHttp): List<Episode> {
        val out = ArrayList<Episode>()
        var page = 1
        var totalPages = 1
        var skipped = 0
        while (page <= totalPages) {
            val r = http.get(pageUrl(page))
            val body = Fetchers.body(r, "8-Bit Theater archive page $page")
            if (page == 1) totalPages = r.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
            val (eps, skip) = parsePage(body)
            out.addAll(eps)
            skipped += skip
            page++
        }
        if (skipped > 0) Log.i("feed-8bt", "archive index: ${out.size} episodes, $skipped posts in the category are not episodes")
        return out.sortedBy { it.num }
    }

    /** One REST page → its episodes and the count of non-episode posts. */
    fun parsePage(body: ByteArray): Pair<List<Episode>, Int> {
        val arr = try { json.parseToJsonElement(body.toString(Charsets.UTF_8)) as JsonArray } catch (e: Exception) {
            throw IOException("8-Bit Theater format changed: not a JSON array (${e.message})")
        }
        val eps = ArrayList<Episode>()
        var skipped = 0
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val rawTitle = (o["title"] as? JsonObject)?.get("rendered")?.jsonPrimitive?.contentOrNull ?: ""
            val title = Extract.title(rawTitle)
            val m = EPISODE.find(title)
            if (m == null) { skipped++; continue }
            val link = o["link"]?.jsonPrimitive?.contentOrNull ?: continue
            val date = FeedDates.parse(o["date"]?.jsonPrimitive?.contentOrNull ?: "")
            eps.add(Episode(m.groupValues[1].toInt(), m.groupValues[2].trim(), date, link))
        }
        return eps to skipped
    }

    /** The one comic image on an episode page. */
    fun imageFromPage(html: String, pageUrl: String): String {
        val doc = Jsoup.parse(html, pageUrl)
        for (img in doc.select("img")) {
            val src = img.attr("abs:src")
            if (src.contains("/comics/8-bit-theater/")) return src
        }
        throw IOException("8-Bit Theater format changed: no comic image on $pageUrl")
    }
}
