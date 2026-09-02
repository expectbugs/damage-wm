package wm.damage.core.windows.torrents

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import wm.damage.core.util.Http
import wm.damage.core.util.Log

/**
 * The TorrentLeech adapter — Adam's tracker (TORRENTS.md §2, probed live
 * 2026-09-01). Facts it stands on, all observed rather than remembered:
 *
 *  - login is a form POST to `/user/account/login/` that answers a 302 home
 *    with a session cookie; a failed login shows the form again;
 *  - browse and search are ONE JSON endpoint the site's own UI uses,
 *    `/torrents/browse/list/[query/<q>/][categories/<ids>/]orderby/<f>/order/desc/page/<n>`
 *    → `{numFound, perPage, page, torrentList:[{fid, filename, name,
 *    addedTimestamp, categoryID, size, completed, seeders, leechers, tags,
 *    download_multiplier, imdbID, rating, genres, …}]}`;
 *  - the torrent page `/torrent/<fid>` is HTML with a Torrent Info table
 *    (`#torrentinfo`), the description (`.torrent-info-details`), the NFO
 *    (`#nfo_text`), the files table (`#torrent-files-panel`) and the
 *    download link `/download/<fid>/<filename>`;
 *  - the profile page carries uploaded / downloaded / ratio / TL points /
 *    class. It also shows the passkey — this adapter reads the five stats
 *    and never stores the page.
 *
 * This is an internal, undocumented API. **Every parse refuses what it does
 * not recognize** ([TlException] "format changed: …") — a markup change is
 * a loud notice on glass, never a quietly empty list.
 */
class TorrentLeech(
    private val user: String,
    private val pass: String,
    /** Where the session cookies persist between service restarts (0600). */
    private val cookieFile: Path?,
    private val base: String = "https://www.torrentleech.org",
) {
    class TlException(msg: String) : java.io.IOException(msg)

    @Serializable
    private data class CookieJar(val cookies: Map<String, String> = emptyMap(), val savedAt: Long = 0)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cookies = LinkedHashMap<String, String>()
    private val lock = Any()
    /** Re-logins are PACED (R2-P3): a site that answers a page instead of the
     *  listing for a while (maintenance) must not get a fresh login per retry. */
    private var lastLoginAt = 0L

    init {
        cookieFile?.let { f ->
            if (Files.isRegularFile(f)) try {
                cookies.putAll(json.decodeFromString(CookieJar.serializer(), Files.readString(f)).cookies)
            } catch (e: Exception) {
                Log.w("torrentleech", "cookie jar unreadable — logging in fresh: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- session
    private fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun absorbCookies(r: Http.Response) {
        var changed = false
        for (sc in r.setCookies()) {
            val parts = sc.split(';')
            val kv = parts[0]
            val k = kv.substringBefore('=').trim()
            val v = kv.substringAfter('=', "").trim()
            if (k.isEmpty()) continue
            // a deletion (Max-Age=0 / an expiry in the past) removes the cookie
            // instead of re-sending it forever (review 2026-09-01 C15)
            val deleted = parts.drop(1).any { a ->
                val t = a.trim()
                (t.startsWith("Max-Age=", ignoreCase = true) && (t.substringAfter('=').trim().toLongOrNull() ?: 1L) <= 0L) ||
                    (t.startsWith("Expires=", ignoreCase = true) && expiresInPast(t.substringAfter('=').trim()))
            }
            if (deleted) {
                if (cookies.remove(k) != null) changed = true
            } else if (cookies[k] != v) { cookies[k] = v; changed = true }
        }
        if (changed) saveJar()
    }

    private fun expiresInPast(s: String): Boolean = try {
        // PHP's Netscape form ("Thu, 01-Jan-1970 00:00:01 GMT") differs from
        // RFC 1123 only by the hyphens (R2-P5)
        val norm = s.trim().replace(Regex("(\\d{2})-([A-Za-z]{3})-(\\d{4})"), "$1 $2 $3")
        java.time.ZonedDateTime.parse(norm, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().isBefore(java.time.Instant.now())
    } catch (e: Exception) {
        Log.w("torrentleech", "cookie Expires not understood: '$s'")
        false
    }

    private fun saveJar() {
        val f = cookieFile ?: return
        try {
            Files.createDirectories(f.parent)
            val tmp = f.resolveSibling(f.fileName.toString() + ".tmp")
            // created 0600 from the first byte (review 2026-09-01 C14) — the
            // session cookie is never world-readable, not even for a moment
            try {
                Files.deleteIfExists(tmp)
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } catch (e: UnsupportedOperationException) {
                // a filesystem without POSIX permissions (the phone never runs this side)
            }
            Files.writeString(tmp, json.encodeToString(CookieJar.serializer(),
                CookieJar(LinkedHashMap(cookies), System.currentTimeMillis())))
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Log.w("torrentleech", "cookie jar not saved: ${e.message}")
        }
    }

    private val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"

    private fun headers(): Map<String, String> {
        val h = HashMap<String, String>()
        h["User-Agent"] = ua           // the probe's UA — what the site served without a challenge
        h["Accept"] = "text/html,application/json;q=0.9,*/*;q=0.8"
        if (cookies.isNotEmpty()) h["Cookie"] = cookieHeader()
        return h
    }

    /** Log in: a 302 away from the login page with cookies = success. */
    fun login() {
        if (user.isEmpty() || pass.isEmpty()) throw TlException("TorrentLeech credentials are not configured")
        synchronized(lock) {
            lastLoginAt = System.currentTimeMillis()
            cookies.clear()
            val body = Http.formEncode(mapOf("username" to user, "password" to pass)).toByteArray(Charsets.UTF_8)
            val r = Http.request("POST", "$base/user/account/login/", headers(), body,
                "application/x-www-form-urlencoded", followRedirects = false)
            absorbCookies(r)
            val loc = r.header("Location") ?: ""
            val ok = r.status == 302 && !loc.contains("login", ignoreCase = true) && cookies.isNotEmpty()
            if (!ok) throw TlException("TorrentLeech login failed (HTTP ${r.status}${if (loc.isNotEmpty()) " -> $loc" else ""})")
            Log.i("torrentleech", "logged in as $user")
        }
    }

    private fun looksLoggedOut(r: Http.Response): Boolean {
        if (r.status == 302 || r.status == 301) {
            val loc = r.header("Location") ?: ""
            return loc.contains("login", ignoreCase = true)
        }
        if (r.status == 200 && r.contentType.contains("text/html", ignoreCase = true)) {
            // the WHOLE body (review 2026-09-01 C6): the login form may sit
            // past any prefix on a page with a long head
            val body = r.text()
            return body.contains("user/account/login", ignoreCase = true) &&
                body.contains("name=\"password\"", ignoreCase = true)
        }
        return r.status == 401 || r.status == 403
    }

    /** GET with the session; a logged-out answer triggers ONE login and one
     *  retry. [wantJson]: an HTML answer on the JSON endpoint is a session
     *  refused (the site shows a page, never a JSON error) — the same one retry. */
    private fun get(path: String, retry: Boolean = true, wantJson: Boolean = false): Http.Response {
        synchronized(lock) {
            if (cookies.isEmpty()) login()
            val r = Http.request("GET", base + path, headers(), followRedirects = false)
            absorbCookies(r)
            val htmlOnJson = wantJson && r.status == 200 && r.contentType.contains("text/html", ignoreCase = true)
            if (looksLoggedOut(r) || htmlOnJson) {
                if (!retry) throw TlException("TorrentLeech session refused after re-login ($path)")
                // a GENUINE logout (the login form, a redirect to it) is answered
                // with one login per request; a page that is NOT the login form
                // (maintenance) gets a login at most once a minute and is
                // otherwise reported as what it is (R2-P3)
                val genuine = looksLoggedOut(r)
                val paced = System.currentTimeMillis() - lastLoginAt < RELOGIN_PACING_MS
                if (!genuine && paced) throw TlException(
                    "TorrentLeech answered a page in place of the listing (logged out, or down for maintenance)")
                Log.i("torrentleech", if (genuine) "session expired - logging in again" else "a page in place of the listing - logging in again")
                login()
                return get(path, retry = false, wantJson = wantJson)
            }
            if (r.status != 200) throw TlException("TorrentLeech $path: HTTP ${r.status}")
            return r
        }
    }

    // ---------------------------------------------------------------- listing
    /** Browse ([query] null) or search — the same endpoint, the same rows. */
    fun list(query: String?, categoryId: Int?, page: Int, sort: String): TlPage {
        val sb = StringBuilder("/torrents/browse/list/")
        if (!query.isNullOrBlank()) sb.append("query/").append(Http.pathEncode(query.trim())).append('/')
        if (categoryId != null && categoryId > 0) sb.append("categories/").append(categoryId).append('/')
        val s = if (sort in SORTS) sort else "added"
        sb.append("orderby/").append(s).append("/order/desc/page/").append(page.coerceAtLeast(1))
        val r = get(sb.toString(), wantJson = true)
        val body = r.text()
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw TlException("TorrentLeech format changed: the listing is not JSON (${body.take(60).trim()})")
        }
        val list = root["torrentList"] as? JsonArray
            ?: throw TlException("TorrentLeech format changed: no torrentList in the listing")
        val items = list.map { el ->
            val o = el as? JsonObject
                ?: throw TlException("TorrentLeech format changed: a listing row is not an object")
            itemOf(o)
        }
        return TlPage(items, root.int("page", page), root.int("perPage", 35), root.int("numFound", items.size))
    }

    private fun itemOf(o: JsonObject): TlItem {
        val fid = o.str("fid")
        val name = o.str("name")
        // a row without its identity is drift, refused loudly — never a
        // quietly shorter page (review 2026-09-01 C2)
        if (fid.isEmpty() || name.isEmpty()) throw TlException(
            "TorrentLeech format changed: a listing row without fid/name (keys ${o.keys.take(12)})")
        val tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val mult = o.int("download_multiplier", 1)
        return TlItem(
            fid = fid, name = name, filename = o.str("filename"), categoryId = o.int("categoryID"),
            size = o.long("size"), seeders = o.int("seeders"), leechers = o.int("leechers"),
            snatched = o.int("completed"), addedAt = o.str("addedTimestamp"), tags = tags,
            freeleech = mult == 0 || tags.any { it.equals("FREELEECH", ignoreCase = true) },
            imdb = o.str("imdbID"), rating = o.dbl("rating"), genres = o.str("genres"),
        )
    }

    // ---------------------------------------------------------------- detail
    fun pageUrl(fid: String): String = "$base/torrent/$fid"

    fun detail(fid: String): TlDetail {
        // comments go first (review 2026-09-01 C1): the live page carries a
        // commented-out TEMPLATE of the NFO block before the real one, and a
        // raw landmark search found the template
        val html = Html.stripComments(get("/torrent/${Http.pathEncode(fid)}").text())
        val info = Html.element(html, "id", "torrentinfo")
            ?: throw TlException("TorrentLeech format changed: the Torrent Info table is missing on /torrent/$fid")
        val rows = Html.tableRows(info)
        fun row(label: String): String =
            rows.firstOrNull { it.isNotEmpty() && it[0].trim().trimEnd(':').equals(label, ignoreCase = true) }
                ?.getOrNull(1)?.trim() ?: ""
        // the heading is `torrentnameid`; `torrentName` is a modal's copy of
        // it on the live page (review 2026-09-01 C10) — the heading first
        val name = Html.element(html, "id", "torrentnameid")?.let { Html.text(it).trim() }?.ifEmpty { null }
            ?: Html.element(html, "id", "torrentName")?.let { Html.text(it).trim() }?.ifEmpty { null }
            ?: throw TlException("TorrentLeech format changed: no torrent name on /torrent/$fid")
        val category = row("Category")
        val added = row("Added").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        val size = row("Size")
        val snatched = row("Downloaded").filter { it.isDigit() }.toIntOrNull() ?: 0
        val uploader = row("Uploader").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.substringBefore("Thank")?.trim() ?: ""
        // one tag per line on the page — tags carry spaces ("Adobe Acrobat
        // Professional", review 2026-09-01 C5)
        val tags = row("Tags").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val seeders = row("Seeders").filter { it.isDigit() }.toIntOrNull() ?: 0
        val leechers = row("Leechers").filter { it.isDigit() }.toIntOrNull() ?: 0
        // the description block carries the rating widget on the live page
        // ("0 / Your Rating") — dropped before the text (R2-P7)
        val description = Html.element(html, "class", "torrent-info-details")
            ?.let { Html.without(it, "class", "imdb-info") }?.let { Html.without(it, "id", "user_rating") }
            ?.let { Html.text(it) }?.trim()?.replace(Regex("\n{3,}"), "\n\n") ?: ""
        val nfo = Html.element(html, "id", "nfo_text")?.let { Html.text(it, pre = true) }?.trim() ?: ""
        val files = ArrayList<TlFile>()
        Html.element(html, "id", "torrent-files-panel")?.let { panel ->
            for (r in Html.tableRows(panel)) {
                if (r.size < 2) continue
                val n = r[0].trim()
                val s = r[1].trim()
                if (n.equals("Filename", ignoreCase = true) || n.isEmpty()) continue
                files.add(TlFile(n, s))
            }
        }
        val fl = html.contains("freeleech", ignoreCase = true) && (tags.any { it.equals("FREELEECH", true) } ||
            Regex("class=\"[^\"]*freeleech[^\"]*\"", RegexOption.IGNORE_CASE).containsMatchIn(html))
        return TlDetail(fid, name, category, size, seeders, leechers, snatched, added, uploader,
            tags.filter { !it.equals("FREELEECH", true) } + (if (fl) listOf("FREELEECH") else emptyList()),
            description, nfo, files, pageUrl(fid))
    }

    /** The `.torrent` bytes for [fid]: the download link is read from the
     *  page (its filename segment is part of the URL), then fetched with the
     *  session. Anything that is not a bencoded dictionary is refused. */
    fun download(fid: String): Pair<String, ByteArray> {
        val html = Html.stripComments(get("/torrent/${Http.pathEncode(fid)}").text())
        val href = Regex("href=\"(/download/${Regex.escape(fid)}/[^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: throw TlException("TorrentLeech format changed: no download link on /torrent/$fid")
        // path-segment decoding: %XX only — a '+' in a release name is a plus,
        // not a space (review 2026-09-01 C4)
        val fileName = Html.percentDecode(href.substringAfterLast('/'))
        val r = get(href)
        val bytes = r.body
        if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte() ||
            !String(bytes, 0, minOf(bytes.size, 400), Charsets.ISO_8859_1).contains("announce")) {
            throw TlException("TorrentLeech did not answer with a torrent file for $fid (${r.contentType})")
        }
        return fileName to bytes
    }

    // ---------------------------------------------------------------- account
    fun account(): TlAccount {
        val text = Html.text(get("/profile/${Http.pathEncode(user)}").text())
        fun grab(re: String): String = Regex(re, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim() ?: ""
        val up = grab("uploaded:\\s*([^\\n]+)")
        val down = grab("downloaded:\\s*([^\\n]+)")
        val ratio = grab("ratio:\\s*([^\\n]+)")
        val points = grab("TL Points:\\s*([^\\n]+)")
        val klass = grab("\\bClass\\s+([^\\n]+)")
        // the three that matter must all be there — a partial page is drift,
        // not a quiet stats row with holes
        if (up.isEmpty() || down.isEmpty() || ratio.isEmpty()) throw TlException(
            "TorrentLeech format changed: the profile page lost uploaded/downloaded/ratio")
        return TlAccount(user, up, down, ratio, points, klass)
    }

    companion object {
        val SORTS = listOf("added", "seeders", "size", "name")
        /** A re-login is attempted at most once a minute (R2-P3). */
        const val RELOGIN_PACING_MS = 60_000L

        /** The site's category tree — read from its JS bundle 2026-09-01
         *  (40 categories in 9 groups). An id outside this table is shown
         *  as its number, never dropped. */
        val CATEGORIES: List<TlCategory> = listOf(
            TlCategory(8, "Movies", "Cam"), TlCategory(9, "Movies", "TS/TC"),
            TlCategory(11, "Movies", "DVDRip/DVDScreener"), TlCategory(37, "Movies", "WEBRip"),
            TlCategory(43, "Movies", "HDRip"), TlCategory(14, "Movies", "BlurayRip"),
            TlCategory(12, "Movies", "DVD-R"), TlCategory(13, "Movies", "Bluray"),
            TlCategory(47, "Movies", "4K"), TlCategory(15, "Movies", "Boxsets"),
            TlCategory(29, "Movies", "Documentaries"),
            TlCategory(26, "TV", "Episodes"), TlCategory(32, "TV", "Episodes HD"), TlCategory(27, "TV", "Boxsets"),
            TlCategory(17, "Games", "PC"), TlCategory(42, "Games", "Mac"), TlCategory(18, "Games", "XBOX"),
            TlCategory(19, "Games", "XBOX360"), TlCategory(40, "Games", "XBOXONE"), TlCategory(20, "Games", "PS2"),
            TlCategory(21, "Games", "PS3"), TlCategory(39, "Games", "PS4"), TlCategory(49, "Games", "PS5"),
            TlCategory(22, "Games", "PSP"), TlCategory(28, "Games", "Wii"), TlCategory(30, "Games", "Nintendo DS"),
            TlCategory(48, "Games", "Nintendo Switch"),
            TlCategory(23, "Apps", "PC-ISO"), TlCategory(24, "Apps", "Mac"), TlCategory(25, "Apps", "Mobile"),
            TlCategory(33, "Apps", "0-day"),
            TlCategory(38, "Education", "Education"),
            TlCategory(34, "Animation", "Anime"), TlCategory(35, "Animation", "Cartoons"),
            TlCategory(45, "Books", "EBooks"), TlCategory(46, "Books", "Comics"),
            TlCategory(31, "Music", "Audio"), TlCategory(16, "Music", "Music videos"),
            TlCategory(36, "Foreign", "Movies"), TlCategory(44, "Foreign", "TV Series"),
        )

        fun category(id: Int): TlCategory? = CATEGORIES.firstOrNull { it.id == id }
    }

    // ------------------------------------------------------------ JSON helpers
    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)
    private fun JsonObject.str(k: String): String = prim(k)?.contentOrNull ?: ""
    private fun JsonObject.long(k: String, d: Long = 0): Long =
        prim(k)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.toLongOrNull() } ?: d
    private fun JsonObject.int(k: String, d: Int = 0): Int =
        prim(k)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() } ?: d
    private fun JsonObject.dbl(k: String, d: Double = 0.0): Double =
        prim(k)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: d
}

/**
 * A minimal HTML reader — enough for a known page's landmarks, with the
 * JDK only (core runs on the phone too). Not a browser: no scripts, no CSS,
 * a nesting count by tag name to find an element's end.
 */
object Html {

    private val blockTags = setOf("p", "br", "div", "tr", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "pre", "ul", "ol")

    /** HTML comments removed — a commented-out template must never satisfy
     *  a landmark search (review 2026-09-01 C1). */
    fun stripComments(html: String): String =
        Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(html, "")

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** `%XX` decoding only: a URL path segment keeps its '+' (the form
     *  decoder's plus-is-space rule does not apply to paths). Exactly two hex
     *  digits, never a signed number (R2-P6). */
    fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3; continue
            }
            val b = c.toString().toByteArray(Charsets.UTF_8)
            out.write(b, 0, b.size)
            i++
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /** The outer HTML of the first element whose [attr] equals [value]. */
    fun element(html: String, attr: String, value: String): String? {
        val re = Regex("<([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*\\b$attr\\s*=\\s*\"([^\"]*)\"[^>]*>", RegexOption.IGNORE_CASE)
        var from = 0
        while (true) {
            val m = re.find(html, from) ?: return null
            val attrValues = m.groupValues[2].split(' ').map { it.trim() }
            val hit = if (attr.equals("class", ignoreCase = true)) value in attrValues else m.groupValues[2] == value
            if (!hit) { from = m.range.last + 1; continue }
            val tag = m.groupValues[1].lowercase()
            val start = m.range.first
            val end = closeOf(html, tag, m.range.last + 1) ?: return html.substring(start)
            return html.substring(start, end)
        }
    }

    /** [fragment] with the first element whose [attr] equals [value] removed. */
    fun without(fragment: String, attr: String, value: String): String {
        val el = element(fragment, attr, value) ?: return fragment
        return fragment.replaceFirst(el, "")
    }

    /** The index just past the close tag matching an open tag of [tag]
     *  whose content starts at [from]. */
    private fun closeOf(html: String, tag: String, from: Int): Int? {
        val open = Regex("<$tag\\b[^>]*>", RegexOption.IGNORE_CASE)
        val close = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE)
        var depth = 1
        var i = from
        while (i < html.length) {
            val o = open.find(html, i)
            val c = close.find(html, i) ?: return null
            if (o != null && o.range.first < c.range.first) {
                if (!o.value.endsWith("/>")) depth++
                i = o.range.last + 1
            } else {
                depth--
                i = c.range.last + 1
                if (depth == 0) return i
            }
        }
        return null
    }

    /** Rows of the first table in [fragment]: each row = its cells' text. */
    fun tableRows(fragment: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        val rowRe = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRe = Regex("<t[dh]\\b[^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (r in rowRe.findAll(fragment)) {
            val cells = cellRe.findAll(r.groupValues[1]).map { text(it.groupValues[1]).trim() }.toList()
            if (cells.isNotEmpty()) out.add(cells)
        }
        return out
    }

    /** Tags stripped, entities decoded, block boundaries as newlines, runs
     *  of blanks collapsed ([pre] keeps the author's line breaks and spaces). */
    fun text(html: String, pre: Boolean = false): String {
        val sb = StringBuilder(html.length)
        var i = 0
        var inScript = false
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                val end = html.indexOf('>', i)
                if (end < 0) break
                val tagBody = html.substring(i + 1, end).trim()
                val name = tagBody.trimStart('/').takeWhile { it.isLetterOrDigit() }.lowercase()
                if (name == "script" || name == "style") inScript = !tagBody.startsWith("/")
                if (name in blockTags) sb.append('\n')
                if (name == "td" || name == "th") sb.append(' ')      // cells read as one line
                i = end + 1
                continue
            }
            if (!inScript) sb.append(c)
            i++
        }
        val decoded = decode(sb.toString())
        if (pre) return decoded.lines().joinToString("\n") { it.trimEnd() }
        return decoded.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n")
    }

    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "hellip" to "...", "mdash" to "-", "ndash" to "-", "rsquo" to "'", "lsquo" to "'",
        "rdquo" to "\"", "ldquo" to "\"", "copy" to "(c)", "reg" to "(R)", "trade" to "(TM)")

    fun decode(s: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        fun chars(cp: Int?): String? = cp?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
        when {
            e.startsWith("#x") -> chars(e.substring(2).toIntOrNull(16)) ?: m.value
            e.startsWith("#") -> chars(e.substring(1).toIntOrNull()) ?: m.value
            else -> entities[e.lowercase()] ?: m.value
        }
    }
}
