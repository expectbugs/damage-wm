package wm.damage.core.windows.music

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import wm.damage.core.util.Exec
import wm.damage.core.util.Http
import wm.damage.core.util.Log

/** What any lyric source hands back: the status line and the body, nothing else. */
class HttpReply(val status: Int, val body: String)

/**
 * The one seam every network lyric source goes through (method · url ·
 * headers → status + body), so a test injects canned replies without a
 * socket. The real implementation is [Http] (HttpURLConnection, NO TIMEOUTS).
 */
fun interface HttpLike {
    fun request(method: String, url: String, headers: Map<String, String>): HttpReply
}

/**
 * MUSIC §9.4 + verdict 24 — the lyric fetch chain, first hit wins, every
 * source keyless and individually toggleable:
 *
 *   1. embedded tags (ffprobe: `LYRICS`, `UNSYNCEDLYRICS`, `lyrics-<lang>`)
 *   2. `<track>.lrc` beside the file (`<track>.txt` as plain)
 *   3. LRCLIB `/api/get` then `/api/search`
 *   4. NetEase's public search + `song/lyric`
 *   5. the unofficial Musixmatch desktop-token route (off by default)
 *
 * The library's own `lyrics` table is checked by the caller BEFORE this class
 * runs; nothing here touches Postgres.
 *
 * Three rules shape the failure handling:
 *  - **A miss and a failure are different facts.** `null` means every enabled
 *    source was asked and genuinely had nothing — the caller may cache that
 *    durable negative. A source that did not *answer* (a fault, a 5xx, a 429,
 *    a refused NetEase code) is collected and, when nothing was found, thrown
 *    with every reason. G2CC learned this the hard way: one LRCLIB outage
 *    once marked tracks lyric-less forever (`server/src/lyrics.ts`, review
 *    2026-07-05).
 *  - **NO SILENT FAILURES.** Every source that stops working is logged with
 *    its reason and reaches the caller; the Musixmatch route in particular
 *    reports "musixmatch route stopped working: HTTP …" and the chain
 *    continues to the next source.
 *  - **NO TIMEOUTS.** The only waiting here is *pacing* — at least
 *    [paceMs] between two calls to the same source, a plain last-call clock.
 *
 * Wire-format lineage (facts only, our own code — the clean-room rule):
 *  - LRCLIB: `/home/user/G2CC/server/src/lyrics.ts` and
 *    `/home/user/G2CC/audio/enrich/passes/lyrics.py`; field names and the
 *    404 body re-verified live 2026-09-02.
 *  - NetEase and Musixmatch endpoint shapes: the open-source `syncedlyrics`
 *    project (MIT), `providers/netease.py` and `providers/musixmatch.py`,
 *    read 2026-09-02; the NetEase search cookie and the Musixmatch
 *    `x-mxm-token-guid` cookie are interoperability facts from the same
 *    source, re-checked live the same day.
 */
class LyricsFetch(
    private val ffprobe: String = "ffprobe",
    private val lrclib: Boolean = true,
    private val netease: Boolean = true,
    private val musixmatch: Boolean = false,
    private val http: HttpLike = HttpLike { m, u, h -> Http.request(m, u, h).let { HttpReply(it.status, it.text()) } },
    // ---- extensions past the §9.4 signature: test seams with real defaults
    private val lrclibBase: String = "https://lrclib.net",
    private val neteaseBase: String = "https://music.163.com",
    private val musixmatchBase: String = "https://apic-desktop.musixmatch.com/ws/1.1",
    private val paceMs: Long = 350L,
    /** How many manual-search candidates a source that needs a second call
     *  per result (NetEase, Musixmatch) is allowed to fetch. */
    private val searchDepth: Int = 3,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : LyricsFetcher {

    // ================================================================== the chain

    override fun fetch(t: MusicDb.TrackFile): Lyrics? {
        val who = who(t)
        val fails = ArrayList<String>()
        for ((name, fn) in fetchChain(t)) {
            val got = one(name, fails, fn) ?: continue
            if (!got.found) continue
            Log.i(TAG, "lyrics for $who from $name (${if (!got.synced.isNullOrBlank()) "synced" else "plain"})")
            return got
        }
        if (fails.isNotEmpty()) throw IllegalStateException(
            "no lyrics for $who and ${fails.size} source(s) did not answer — this is NOT a durable miss, " +
                "do not record it as one: ${fails.joinToString("; ")}")
        Log.i(TAG, "no lyrics for $who from any enabled source — a durable miss")
        return null
    }

    override fun search(t: MusicDb.TrackFile, query: String): List<Lyrics> {
        val q = query.trim()
        if (q.isEmpty()) throw IllegalArgumentException("a lyric search needs a query")
        val fails = ArrayList<String>()
        val cands = ArrayList<Cand>()

        // The two local sources answer the TRACK, not the query — they are
        // offered anyway (they cost nothing and a manual search must always be
        // able to get back to what is on disk) and are ranked against the query
        // like every other candidate, so an unrelated query sinks them.
        one("tags", fails) { fromTags(t) }?.let { cands += local(it, t) }
        one("lrc", fails) { fromSidecar(t) }?.let { cands += local(it, t) }

        if (lrclib) cands += many("lrclib", fails) { searchLrclib(q) }
        if (netease) cands += many("netease", fails) { searchNetease(q) }
        if (musixmatch) cands += many("musixmatch", fails) { searchMusixmatch(q) }

        val seen = HashSet<String>()
        val ranked = cands
            .filter { it.found }
            .filter { seen.add(it.source + "\u0000" + (it.synced ?: "") + "\u0000" + (it.plain ?: "")) }
            .sortedByDescending { rank(q, it, t) }
            .take(SEARCH_MAX)
            .map { it.toLyrics() }
        if (ranked.isEmpty() && fails.isNotEmpty()) throw IllegalStateException(
            "lyric search for \"$q\" found nothing and ${fails.size} source(s) did not answer: ${fails.joinToString("; ")}")
        Log.i(TAG, "lyric search \"$q\": ${ranked.size} candidate(s)")
        return ranked
    }

    private fun fetchChain(t: MusicDb.TrackFile): List<Pair<String, () -> Lyrics?>> {
        val out = ArrayList<Pair<String, () -> Lyrics?>>()
        fun add(name: String, fn: () -> Lyrics?) { out.add(name to fn) }
        add("tags") { fromTags(t) }
        add("lrc") { fromSidecar(t) }
        if (lrclib) add("lrclib") { fromLrclib(t) }
        if (netease) add("netease") { fromNetease(t) }
        if (musixmatch) add("musixmatch") { fromMusixmatch(t) }
        return out
    }

    /** One source, one answer. A fault is LOUD and recorded, never swallowed:
     *  the chain carries on to the next source and [fetch] throws at the end
     *  if nothing was found, so a transient outage never becomes a cached
     *  "this track has no lyrics". */
    private fun one(name: String, fails: MutableList<String>, body: () -> Lyrics?): Lyrics? =
        try {
            body()
        } catch (e: Exception) {
            val why = "$name: ${e.message ?: e::class.simpleName ?: "no reason given"}"
            Log.e(TAG, "lyric source did not answer — $why")
            fails.add(why)
            null
        }

    private fun many(name: String, fails: MutableList<String>, body: () -> List<Cand>): List<Cand> =
        try {
            body()
        } catch (e: Exception) {
            val why = "$name: ${e.message ?: e::class.simpleName ?: "no reason given"}"
            Log.e(TAG, "lyric source did not answer — $why")
            fails.add(why)
            emptyList()
        }

    // ================================================================== 1. embedded tags

    /**
     * ffprobe's FORMAT and STREAM tags — Ogg keeps its vorbiscomments per
     * stream, so a format-only probe reads none of them (verified against a
     * library .ogg carrying `UNSYNCEDLYRICS`, 2026-09-02). Same invocation
     * shape as [LibraryScan.probe], with the whole tag map asked for because
     * ID3 `USLT` lands as `lyrics-<lang>` and the key set is open.
     */
    private fun fromTags(t: MusicDb.TrackFile): Lyrics? {
        val p = pathOf(t) ?: return null
        if (!Files.isRegularFile(p)) {
            Log.w(TAG, "no file at ${t.path} — embedded tags skipped for ${who(t)}")
            return null
        }
        val r = Exec.run(listOf(ffprobe, "-v", "error", "-show_entries", "format_tags:stream_tags", "-of", "json", t.path))
        if (r.code != 0) throw IllegalStateException("ffprobe rc=${r.code}: ${r.stderr.take(200).trim()}")
        val j = json("ffprobe", r.stdout.toString(Charsets.UTF_8)).obj()
            ?: throw IllegalStateException("ffprobe did not return a JSON object")
        val tags = LinkedHashMap<String, String>()
        j["streams"].arr()?.forEach { st -> st.obj()?.get("tags").obj()?.forEach { (k, v) -> v.str()?.let { tags[k.lowercase(Locale.ROOT)] = it } } }
        j["format"].obj()?.get("tags").obj()?.forEach { (k, v) -> v.str()?.let { tags[k.lowercase(Locale.ROOT)] = it } }

        var synced: String? = null
        var plain: String? = null
        for ((k, raw) in tags) {
            if (!isLyricTag(k)) continue
            val v = LrcSanity.text(raw) ?: continue
            if (LrcSanity.hasStamps(v)) { if (synced == null) synced = v } else if (plain == null) plain = v
        }
        if (synced == null && plain == null) return null
        return Lyrics(source = "tags", synced = synced, plain = plain)
    }

    // ================================================================== 2. the sidecar file

    /** `<base>.lrc` beside the track (stamps ⇒ synced), `<base>.txt` as plain.
     *  Read whole — no truncation, however long the file is. */
    private fun fromSidecar(t: MusicDb.TrackFile): Lyrics? {
        val p = pathOf(t) ?: return null
        val dir = p.parent ?: return null
        val base = p.fileName?.toString()?.substringBeforeLast('.') ?: return null
        var synced: String? = null
        var plain: String? = null
        for (ext in LRC_EXTS) {
            val f = dir.resolve("$base.$ext")
            if (!Files.isRegularFile(f)) continue
            val body = LrcSanity.text(readWhole(f)) ?: continue
            if (LrcSanity.hasStamps(body)) {
                synced = body
            } else {
                Log.w(TAG, "$f carries no [mm:ss] stamps — handed over as plain text")
                plain = body
            }
            break
        }
        for (ext in TXT_EXTS) {
            if (plain != null) break
            val f = dir.resolve("$base.$ext")
            if (!Files.isRegularFile(f)) continue
            plain = LrcSanity.text(readWhole(f))
            break
        }
        if (synced == null && plain == null) return null
        return Lyrics(source = "lrc", synced = synced, plain = plain)
    }

    // ================================================================== 3. LRCLIB

    /**
     * `/api/get` keyed by artist/track/album/duration, then `/api/search` for
     * a best duration match within ±3 s. 200 and 404 are durable facts; every
     * other status is transient and is reported, never cached (the review note
     * in `lyrics.ts` — a 5xx once became a permanent negative).
     */
    private fun fromLrclib(t: MusicDb.TrackFile): Lyrics? {
        val artist = t.artist.trim()
        val title = t.title.trim()
        if (artist.isEmpty() || title.isEmpty()) {
            Log.w(TAG, "track #${t.id} has no artist/title — LRCLIB skipped")
            return null
        }
        val get = LinkedHashMap<String, String>()
        get["artist_name"] = artist
        get["track_name"] = title
        if (t.album.isNotBlank()) get["album_name"] = t.album.trim()
        val durS = durKey(t.durMs)
        if (durS > 0) get["duration"] = durS.toString()

        val r = call("lrclib", "GET", "$lrclibBase/api/get?" + qs(get), jsonHeaders())
        when (r.status) {
            200 -> lrclibRow(json("lrclib", r.body).obj())?.let { return it.toLyrics() }
            404 -> Log.i(TAG, "LRCLIB has no row for ${who(t)} — a durable miss for this source")
            else -> throw IllegalStateException("lrclib /api/get HTTP ${r.status}: ${r.body.take(200).trim()}")
        }

        // Second chance: the search endpoint returns whole rows (lyrics
        // included), so no follow-up call is needed — verified 2026-09-02.
        val s = call("lrclib", "GET", "$lrclibBase/api/search?" + qs(mapOf("track_name" to title, "artist_name" to artist)), jsonHeaders())
        if (s.status != 200) throw IllegalStateException("lrclib /api/search HTTP ${s.status}: ${s.body.take(200).trim()}")
        val rows = json("lrclib", s.body).arr()
            ?: throw IllegalStateException("lrclib /api/search did not return a list: ${s.body.take(200).trim()}")
        val cands = rows.mapNotNull { lrclibRow(it.obj()) }.filter { it.found }
        val best = pick(cands, t)
        if (best == null) {
            Log.i(TAG, "LRCLIB search had ${cands.size} row(s) but none within ±3 s of ${who(t)} — a miss for this source")
            return null
        }
        return best.toLyrics()
    }

    /** One LRCLIB row → a candidate. Instrumental / empty rows are a miss. */
    private fun lrclibRow(o: JsonObject?): Cand? {
        if (o == null) return null
        val synced = LrcSanity.text(o["syncedLyrics"].str())
        val plain = LrcSanity.text(o["plainLyrics"].str())
        if (synced == null && plain == null) {
            val why = if (o["instrumental"].str()?.equals("true", true) == true) "instrumental" else "no lyric text"
            Log.i(TAG, "LRCLIB row \"${o["artistName"].str()} — ${o["trackName"].str()}\": $why")
            return null
        }
        // duration arrives as a JSON number of SECONDS and has been seen with
        // a fractional part (238.690612) — cast at the boundary.
        val durMs = ((o["duration"].str()?.toDoubleOrNull() ?: 0.0) * 1000.0).toInt()
        return Cand("lrclib", o["artistName"].str() ?: "", o["trackName"].str() ?: "", durMs, synced, plain)
    }

    private fun searchLrclib(q: String): List<Cand> {
        val r = call("lrclib", "GET", "$lrclibBase/api/search?" + qs(mapOf("q" to q)), jsonHeaders())
        if (r.status != 200) throw IllegalStateException("lrclib /api/search HTTP ${r.status}: ${r.body.take(200).trim()}")
        val rows = json("lrclib", r.body).arr()
            ?: throw IllegalStateException("lrclib /api/search did not return a list: ${r.body.take(200).trim()}")
        return rows.mapNotNull { lrclibRow(it.obj()) }
    }

    // ================================================================== 4. NetEase

    /**
     * The public pair `api/search/pc` (type 1 = songs) then
     * `api/song/lyric?lv=1&kv=1&tv=-1`. The three-cookie header is what makes
     * the search answer at all: without it the reply is a well-formed body
     * carrying `code: -462` ("bind a phone number"), which is a REFUSAL, not
     * an empty result — so it is reported as a source that did not answer and
     * the chain moves on. Observed both ways from this network 2026-09-02;
     * the endpoint is rate-limited per address.
     */
    private fun fromNetease(t: MusicDb.TrackFile): Lyrics? {
        val term = term(t) ?: run {
            Log.w(TAG, "track #${t.id} has no artist/title — NetEase skipped")
            return null
        }
        val songs = neteaseSearch(term, 10)
        if (songs.isEmpty()) {
            Log.i(TAG, "NetEase has no song for ${who(t)} — a durable miss for this source")
            return null
        }
        val best = pick(songs, t) ?: run {
            Log.i(TAG, "NetEase returned ${songs.size} song(s) but none close enough to ${who(t)} — a miss for this source")
            return null
        }
        neteaseLyric(best)
        return if (best.found) best.toLyrics() else null
    }

    private fun searchNetease(q: String): List<Cand> {
        val songs = neteaseSearch(q, 10).sortedByDescending { LrcSanity.score(q, "${it.artist} ${it.title}") }
        val out = ArrayList<Cand>()
        for (c in songs.take(searchDepth)) {
            neteaseLyric(c)
            if (c.found) out += c
        }
        if (songs.size > searchDepth) {
            Log.i(TAG, "NetEase: ${songs.size} song(s) matched \"$q\"; lyrics read for the best $searchDepth (one paced call each)")
        }
        return out
    }

    private fun neteaseSearch(term: String, limit: Int): List<Cand> {
        val url = "$neteaseBase/api/search/pc?" + qs(mapOf("limit" to limit.toString(), "type" to "1", "offset" to "0", "s" to term))
        val r = call("netease", "GET", url, neteaseHeaders())
        if (r.status != 200) throw IllegalStateException("netease search HTTP ${r.status}: ${r.body.take(200).trim()}")
        val o = json("netease", r.body).obj() ?: throw IllegalStateException("netease search did not return a JSON object")
        val code = o["code"].str()?.toDoubleOrNull()?.toInt()
        if (code != 200) throw IllegalStateException(
            "netease search refused: code $code ${o["message"].str() ?: ""}".trim() + " (the public endpoint is rate-limited per address)")
        val songs = o["result"].obj()?.get("songs").arr() ?: return emptyList()
        return songs.mapNotNull { s ->
            val so = s.obj() ?: return@mapNotNull null
            val id = so["id"].str() ?: return@mapNotNull null
            val artist = so["artists"].arr()?.mapNotNull { it.obj()?.get("name").str() }?.joinToString(", ") ?: ""
            Cand("netease", artist, so["name"].str() ?: "", so["duration"].str()?.toDoubleOrNull()?.toInt() ?: 0, null, null, id)
        }
    }

    /** Fills [c] in place: `lrc.lyric` is the LRC body, `klyric.lyric` the
     *  karaoke (word) body when the track has one. */
    private fun neteaseLyric(c: Cand) {
        val url = "$neteaseBase/api/song/lyric?" + qs(mapOf("id" to c.id, "lv" to "1", "kv" to "1", "tv" to "-1"))
        val r = call("netease", "GET", url, neteaseHeaders())
        if (r.status != 200) throw IllegalStateException("netease song/lyric HTTP ${r.status}: ${r.body.take(200).trim()}")
        val o = json("netease", r.body).obj() ?: throw IllegalStateException("netease song/lyric did not return a JSON object")
        val code = o["code"].str()?.toDoubleOrNull()?.toInt()
        if (code != 200) throw IllegalStateException("netease song/lyric refused: code $code ${o["message"].str() ?: ""}".trim())
        val body = LrcSanity.text(o["lrc"].obj()?.get("lyric").str())
            ?: LrcSanity.text(o["klyric"].obj()?.get("lyric").str())
        if (body == null) {
            Log.i(TAG, "NetEase song ${c.id} carries no lyric text")
            return
        }
        if (LrcSanity.hasStamps(body)) c.synced = body else c.plain = body
    }

    // ================================================================== 5. Musixmatch

    /**
     * The unofficial desktop-app route: `token.get` for a short-lived user
     * token, `track.search`, then `track.richsync.get` (word stamps — the
     * "much better synced" goal of verdict 6) falling back to
     * `track.subtitle.get` (line stamps). Keyless, and expected to stop
     * working one day; when it does, the reason is reported as
     * "musixmatch route stopped working: HTTP …" and the chain continues.
     *
     * ⚠ MEASURED vs MODELED: the endpoint shapes are the `syncedlyrics`
     * reference and the token endpoint's reply framing was seen live
     * 2026-09-02, but that address is currently answered with
     * `status_code 401, hint captcha`, so the search / richsync / subtitle
     * decoding here is exercised only by canned bodies in the tests, never
     * yet against the live service.
     */
    private fun fromMusixmatch(t: MusicDb.TrackFile): Lyrics? {
        val term = term(t) ?: run {
            Log.w(TAG, "track #${t.id} has no artist/title — Musixmatch skipped")
            return null
        }
        val hits = mxmSearch(term)
        if (hits.isEmpty()) {
            Log.i(TAG, "Musixmatch has no track for ${who(t)} — a durable miss for this source")
            return null
        }
        val best = pick(hits, t) ?: run {
            Log.i(TAG, "Musixmatch returned ${hits.size} track(s) but none close enough to ${who(t)} — a miss for this source")
            return null
        }
        mxmLyrics(best)
        return if (best.found) best.toLyrics() else null
    }

    private fun searchMusixmatch(q: String): List<Cand> {
        val hits = mxmSearch(q).sortedByDescending { LrcSanity.score(q, "${it.artist} ${it.title}") }
        val out = ArrayList<Cand>()
        for (c in hits.take(searchDepth)) {
            mxmLyrics(c)
            if (c.found) out += c
        }
        return out
    }

    private fun mxmSearch(term: String): List<Cand> {
        // softStatus: "nothing matched" is an ordinary answer here, not a
        // route that stopped working
        val body = mxmCall("track.search", linkedMapOf("q" to term, "page_size" to "5", "page" to "1"), softStatus = true)
        val list = body["track_list"].arr() ?: return emptyList()
        return list.mapNotNull { e ->
            val tr = e.obj()?.get("track").obj() ?: return@mapNotNull null
            val id = tr["track_id"].str() ?: return@mapNotNull null
            val lenS = tr["track_length"].str()?.toDoubleOrNull() ?: 0.0
            Cand("musixmatch", tr["artist_name"].str() ?: "", tr["track_name"].str() ?: "", (lenS * 1000).toInt(), null, null, id)
        }
    }

    /** Word stamps first, line stamps second. Either may be absent. */
    private fun mxmLyrics(c: Cand) {
        val rich = mxmCall("track.richsync.get", linkedMapOf("track_id" to c.id), softStatus = true)
        val richBody = rich["richsync"].obj()?.get("richsync_body").str()
        if (!richBody.isNullOrBlank()) {
            val lrc = richsyncToLrc(richBody)
            if (lrc != null) { c.synced = lrc; return }
        }
        val sub = mxmCall("track.subtitle.get", linkedMapOf("track_id" to c.id, "subtitle_format" to "lrc"), softStatus = true)
        val text = LrcSanity.text(sub["subtitle"].obj()?.get("subtitle_body").str())
        if (text == null) {
            Log.i(TAG, "Musixmatch track ${c.id} carries neither a richsync nor a subtitle body")
            return
        }
        if (LrcSanity.hasStamps(text)) c.synced = text else c.plain = text
    }

    /**
     * One call on the desktop route. [softStatus] means "this track simply
     * has no such body" is an ordinary answer (an empty body is returned),
     * while a token or transport failure is still loud.
     */
    private fun mxmCall(action: String, params: LinkedHashMap<String, String>, softStatus: Boolean = false): JsonObject {
        params["app_id"] = MXM_APP_ID
        params["usertoken"] = mxmToken()
        params["t"] = clock().toString()
        val r = call("musixmatch", "GET", "$musixmatchBase/$action?" + qs(params), mxmHeaders())
        if (r.status != 200) throw IllegalStateException(
            "musixmatch route stopped working: HTTP ${r.status} on $action — ${r.body.take(160).trim()}")
        val msg = json("musixmatch", r.body).obj()?.get("message").obj()
            ?: throw IllegalStateException("musixmatch route stopped working: HTTP ${r.status} on $action returned no message envelope")
        val code = msg["header"].obj()?.get("status_code").str()?.toDoubleOrNull()?.toInt() ?: -1
        if (code != 200) {
            val hint = msg["header"].obj()?.get("hint").str()?.let { " ($it)" } ?: ""
            if (softStatus && (code == 404 || code == 402)) {
                Log.i(TAG, "Musixmatch $action: status_code $code$hint — nothing of that kind for this track")
                return JsonObject(emptyMap())
            }
            throw IllegalStateException("musixmatch route stopped working: HTTP ${r.status} on $action, status_code $code$hint")
        }
        return msg["body"].obj() ?: JsonObject(emptyMap())
    }

    private var mxmToken: String? = null
    private var mxmTokenAt = 0L

    /** A user token, re-used for its short life. One paced re-ask on a
     *  refusal (the reference does the same), then the reason is reported. */
    private fun mxmToken(): String {
        val held = mxmToken
        if (held != null && clock() - mxmTokenAt < MXM_TOKEN_LIFE_MS) return held
        var why = "no reply"
        for (attempt in 1..2) {
            val url = "$musixmatchBase/token.get?" + qs(linkedMapOf(
                "user_language" to "en", "app_id" to MXM_APP_ID, "t" to clock().toString()))
            val r = call("musixmatch", "GET", url, mxmHeaders())
            if (r.status != 200) {
                why = "HTTP ${r.status} — ${r.body.take(160).trim()}"
            } else {
                val msg = json("musixmatch", r.body).obj()?.get("message").obj()
                val code = msg?.get("header").obj()?.get("status_code").str()?.toDoubleOrNull()?.toInt() ?: -1
                val hint = msg?.get("header").obj()?.get("hint").str()?.let { " ($it)" } ?: ""
                val tok = msg?.get("body").obj()?.get("user_token").str()
                if (code == 200 && !tok.isNullOrBlank() && tok != "UpgradeOnlyUrlPlease") {
                    mxmToken = tok
                    mxmTokenAt = clock()
                    return tok
                }
                why = "HTTP ${r.status}, status_code $code$hint"
            }
            if (attempt == 1) {
                Log.w(TAG, "musixmatch token.get: $why — one paced re-ask")
                pause(paceMs * 4)
            }
        }
        throw IllegalStateException("musixmatch route stopped working: $why on token.get")
    }

    /**
     * The richsync body is a JSON string of `{ts, te, l:[{c,o}], x}` entries;
     * it becomes enhanced LRC — `[mm:ss.cc] <mm:ss.cc> word …` — which is what
     * `LyricsSync` parses. Shape from `syncedlyrics/providers/musixmatch.py`.
     */
    internal fun richsyncToLrc(raw: String): String? {
        val arr = json("musixmatch richsync", raw).arr() ?: return null
        val sb = StringBuilder()
        for (e in arr) {
            val o = e.obj() ?: continue
            val ts = o["ts"].str()?.toDoubleOrNull() ?: continue
            sb.append('[').append(LrcSanity.stamp(ts)).append("] ")
            o["l"].arr()?.forEach { w ->
                val wo = w.obj() ?: return@forEach
                val c = wo["c"].str() ?: return@forEach
                val off = wo["o"].str()?.toDoubleOrNull() ?: 0.0
                sb.append('<').append(LrcSanity.stamp(ts + off)).append("> ").append(c).append(' ')
            }
            sb.append('\n')
        }
        return LrcSanity.text(sb.toString())
    }

    // ================================================================== plumbing

    /** At least [paceMs] between two calls to the SAME source, measured on a
     *  plain last-call clock. This is pacing, not a time bound on any I/O:
     *  nothing here ever gives up on a reply. */
    private val paceLock = Any()
    private val lastCall = HashMap<String, Long>()

    private fun paced(source: String) {
        val wait = synchronized(paceLock) {
            val now = clock()
            val last = lastCall[source]
            val w = if (last == null) 0L else paceMs - (now - last)
            // the slot is reserved before the wait, so a second thread paces
            // behind this one instead of alongside it
            lastCall[source] = if (w > 0) now + w else now
            w
        }
        if (wait > 0) {
            Log.d(TAG, "$source paced — waiting $wait ms before the next call")
            pause(wait)
        }
    }

    private fun call(source: String, method: String, url: String, headers: Map<String, String>): HttpReply {
        paced(source)
        val reply = try {
            http.request(method, url, headers)
        } catch (e: Exception) {
            throw IllegalStateException("$source $method ${redact(url)} did not complete: ${e.message ?: e::class.simpleName}", e)
        }
        Log.d(TAG, "$source HTTP ${reply.status} ${redact(url)}")
        return reply
    }

    private fun jsonHeaders() = mapOf("User-Agent" to UA, "Accept" to "application/json")

    // The cookie set the public search answers to; without it the reply is a
    // valid body carrying code -462. Values from `syncedlyrics`
    // providers/netease.py (MIT), re-checked live 2026-09-02.
    private fun neteaseHeaders() = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
        "Cookie" to "NMTID=00OAVK3xqDG726ITU6jopU6jF2yMk0AAAGCO8l1BA; " +
            "_ntes_nnid=0db6667097883aa9596ecfe7f188c3ec,1659122833973; " +
            "_ntes_nuid=0db6667097883aa9596ecfe7f188c3ec",
    )

    // apic-desktop answers a request without this cookie by redirecting, so
    // the connection never produces a body (seen 2026-09-02); with it the
    // reply is a normal JSON envelope.
    private fun mxmHeaders() = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
        "Cookie" to "x-mxm-token-guid=",
    )

    /** A candidate from any source, filled in as the lyric body arrives. */
    private class Cand(
        val source: String, val artist: String, val title: String, val durMs: Int,
        var synced: String? = null, var plain: String? = null, val id: String = "",
    ) {
        val found: Boolean get() = !synced.isNullOrBlank() || !plain.isNullOrBlank()
        fun toLyrics() = Lyrics(source = source, synced = synced, plain = plain,
            label = LrcSanity.label(artist, title, durMs, source))
    }

    private fun local(l: Lyrics, t: MusicDb.TrackFile) =
        Cand(l.source, t.artist, t.title, t.durMs, l.synced, l.plain)

    /**
     * The automatic pick: duration inside ±3 s when the track's duration is
     * known, otherwise a name match at least as good as the reference's 0.65.
     * Returning null is a MISS, never a wrong-song hit.
     */
    private fun pick(cands: List<Cand>, t: MusicDb.TrackFile): Cand? {
        if (cands.isEmpty()) return null
        val term = term(t) ?: return null
        val scored = cands.sortedByDescending { LrcSanity.score(term, "${it.artist} ${it.title}") }
        if (t.durMs > 0) {
            val near = scored.filter { it.durMs > 0 && Math.abs(it.durMs - t.durMs) <= DUR_SLACK_MS }
            if (near.isNotEmpty()) return near.first()
            // no duration anywhere in the list: fall through to the name gate
            if (scored.any { it.durMs > 0 }) return null
        }
        val best = scored.first()
        return if (LrcSanity.score(term, "${best.artist} ${best.title}") >= NAME_FLOOR) best else null
    }

    /** Manual-search ordering: how well the candidate answers the typed query,
     *  nudged by duration closeness and by carrying stamps at all. */
    private fun rank(query: String, c: Cand, t: MusicDb.TrackFile): Double {
        val name = LrcSanity.score(query, "${c.artist} ${c.title}")
        val dur = when {
            t.durMs <= 0 || c.durMs <= 0 -> 0.0
            Math.abs(c.durMs - t.durMs) <= DUR_SLACK_MS -> 1.0
            else -> Math.max(0.0, 1.0 - Math.abs(c.durMs - t.durMs) / 30_000.0)
        }
        return 0.70 * name + 0.20 * dur + 0.10 * (if (!c.synced.isNullOrBlank()) 1.0 else 0.0)
    }

    private fun pathOf(t: MusicDb.TrackFile): Path? =
        try {
            Path.of(t.path)
        } catch (e: Exception) {
            throw IllegalStateException("track #${t.id} has an unusable path \"${t.path}\": ${e.message}", e)
        }

    private fun readWhole(f: Path): String {
        val s = Files.readAllBytes(f).toString(Charsets.UTF_8)
        // a byte-order mark being STRIPPED, never drawn — lint:allow-symbols
        return if (s.startsWith("\uFEFF")) s.substring(1) else s
    }

    private fun json(source: String, body: String): JsonElement =
        try {
            musicJson.parseToJsonElement(body)
        } catch (e: Exception) {
            throw IllegalStateException("$source did not return JSON (${e.message}): ${body.take(200).trim()}", e)
        }

    private fun who(t: MusicDb.TrackFile) = "\"${t.artist.ifBlank { "(no artist)" }} — ${t.title.ifBlank { "(no title)" }}\""

    private fun term(t: MusicDb.TrackFile): String? =
        listOf(t.artist.trim(), t.title.trim()).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }

    private fun durKey(durMs: Int): Int = if (durMs <= 0) 0 else Math.round(durMs / 1000.0).toInt()

    private fun qs(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> Http.pathEncode(k) + "=" + Http.pathEncode(v) }

    private fun redact(url: String) = url.replace(Regex("usertoken=[^&]*"), "usertoken=<held>")

    private fun isLyricTag(k: String): Boolean =
        k in LYRIC_TAG_KEYS || k.startsWith("lyrics-") || k.startsWith("lyrics_") ||
            k.startsWith("lyrics:") || k.startsWith("unsyncedlyrics-") || k.startsWith("syncedlyrics-")

    companion object {
        const val TAG = "music-lyrics"

        /** Plain ASCII on purpose — request headers are latin-1 on the wire and
         *  a typographic dash in a User-Agent ends every call (the G2CC note in
         *  `audio/enrich/passes/lyrics.py`). No address, no account. */
        const val UA = "DamageWM/1.0 (personal smart-glasses music window)"

        const val MXM_APP_ID = "web-desktop-app-v1.0"
        const val MXM_TOKEN_LIFE_MS = 600_000L

        /** LRCLIB itself matches within a couple of seconds; ±3 s is the gate
         *  `MUSIC.md` §9.4 sets for picking a search row. */
        const val DUR_SLACK_MS = 3_000
        const val NAME_FLOOR = 0.65
        const val SEARCH_MAX = 10

        private val LRC_EXTS = listOf("lrc", "LRC", "Lrc")
        private val TXT_EXTS = listOf("txt", "TXT")
        private val LYRIC_TAG_KEYS = setOf(
            "lyrics", "lyric", "unsyncedlyrics", "unsynced_lyrics", "unsynced lyrics",
            "syncedlyrics", "synced_lyrics", "synced lyrics", "uslt",
        )
    }
}

/**
 * The small, pure helpers the fetch chain needs. The LRC PARSER is not here —
 * it lands in `LyricsSync.kt`; raw LRC text passes through this file
 * untouched apart from an outer trim.
 */
object LrcSanity {

    /** `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss:xx]`, and the 3-digit minute forms. */
    private val STAMP = Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]")

    fun hasStamps(s: String?): Boolean = s != null && STAMP.containsMatchIn(s)

    /** Trimmed, or null when there is nothing there. Never truncates. */
    fun text(s: String?): String? = s?.trim()?.ifEmpty { null }

    /** `m:ss`, and `?:??` when the duration is unknown. */
    fun mmss(durMs: Int): String {
        if (durMs <= 0) return "?:??"
        val total = durMs / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /** `mm:ss.cc` — the LRC stamp form, seconds in. Rounded on the whole
     *  centisecond count, not on the fractional part: `10.9 s` is 89.999… of a
     *  centisecond in binary floating point and would otherwise stamp `.89`. */
    fun stamp(seconds: Double): String {
        val t = if (seconds < 0) 0.0 else seconds
        val cs = Math.round(t * 100.0)
        return String.format(Locale.ROOT, "%02d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100)
    }

    /** `<artist> — <title> · m:ss · <source>` (`MUSIC.md` §9.4). */
    fun label(artist: String, title: String, durMs: Int, source: String): String {
        val head = if (artist.isBlank()) title.ifBlank { "(untitled)" } else "$artist — ${title.ifBlank { "(untitled)" }}"
        return "$head · ${mmss(durMs)} · $source"
    }

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun tokens(s: String): Set<String> =
        s.lowercase(Locale.ROOT).split(SPLIT).filter { it.isNotEmpty() }.toSet()

    /** Token overlap in [0,1] — deterministic, no locale surprises, and good
     *  enough to keep a wrong song out (the reference gates at 65 % too). */
    fun score(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val shared = ta.count { it in tb }
        return 2.0 * shared / (ta.size + tb.size)
    }
}

// ------------------------------------------------------------------ json reading
// File-private, so a sibling module in this package may define its own.

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** Every scalar as a String — JSON numbers and booleans included, because the
 *  wire lies about types (LRCLIB's `duration` is a float, NetEase's ids are
 *  numbers, Musixmatch's `status_code` arrives as a number). */
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
