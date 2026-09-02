package wm.damage.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import wm.damage.core.util.Log
import wm.damage.core.windows.music.HttpLike
import wm.damage.core.windows.music.HttpReply
import wm.damage.core.windows.music.LrcSanity
import wm.damage.core.windows.music.LyricsFetch
import wm.damage.core.windows.music.MusicDb

/**
 * `LyricsFetch` (`MUSIC.md` §9.4 + verdict 24). Nothing here touches the
 * network, a real ffprobe, Postgres or the phone: ffprobe is a shell script in
 * a disposable temp dir, and every HTTP source answers through an injected
 * [HttpLike] that records what it was asked — except one case that runs the
 * REAL `Http` against a loopback `com.sun.net.httpserver` so the default seam
 * is exercised too (the `TorrentsTest` pattern).
 */
class LyricsFetchTest {

    private val temps = ArrayList<Path>()
    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, msg -> logs.add("${level.name} $tag $msg") }

    @After
    fun cleanup() {
        Log.removeSink(sink)
        for (d in temps) {
            if (!Files.exists(d)) continue
            Files.walk(d).use { st -> st.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
        }
        temps.clear()
    }

    private fun tempDir(): Path = Files.createTempDirectory("damage-lyrics-").also { temps.add(it) }

    private fun captureLogs() { logs.clear(); Log.addSink(sink) }

    // ------------------------------------------------------------------ fakes

    /** A stand-in ffprobe: ignores its arguments, prints [json] on stdout. */
    private fun fakeFfprobe(dir: Path, json: String): String {
        val f = dir.resolve("ffprobe-fake.sh")
        Files.write(f, "#!/bin/sh\ncat <<'DAMAGE_EOF'\n$json\nDAMAGE_EOF\n".toByteArray())
        f.toFile().setExecutable(true)
        return f.toString()
    }

    private val NO_TAGS = """{"streams":[{}],"format":{}}"""

    /** A track file that really exists, so the local sources actually run. */
    private fun track(dir: Path, artist: String = "Radiohead", title: String = "Creep",
        album: String = "Pablo Honey", durMs: Int = 239_000, name: String = "creep.flac"): MusicDb.TrackFile {
        val f = dir.resolve(name)
        if (!Files.exists(f)) Files.write(f, ByteArray(0))
        return MusicDb.TrackFile(7, f.toString(), 1_700_000_000_000L, title, artist, album, durMs)
    }

    private class FakeHttp(val routes: List<Pair<String, (String) -> HttpReply>>, val clock: () -> Long) : HttpLike {
        val calls = CopyOnWriteArrayList<Pair<String, Long>>()
        override fun request(method: String, url: String, headers: Map<String, String>): HttpReply {
            calls.add(url to clock())
            check(headers["User-Agent"] == LyricsFetch.UA) { "every source must name Damage: ${headers["User-Agent"]}" }
            check(headers["User-Agent"]!!.all { it.code in 32..126 }) { "the User-Agent must be plain ASCII" }
            for ((frag, fn) in routes) if (url.contains(frag)) return fn(url)
            return HttpReply(404, """{"message":"no route in the fake for $url"}""")
        }
    }

    private class FakeClock {
        var now = 10_000L
        val read: () -> Long = { now }
        val pause: (Long) -> Unit = { now += it }
    }

    private fun ok(body: String) = HttpReply(200, body)

    private val LRC_TEXT = "[00:12.34]When you were here before\n[00:15.00]Couldn't look you in the eye"

    private fun lrclibRow(artist: String, title: String, durS: Double, synced: String?, plain: String?) =
        """{"id":1,"trackName":"$title","artistName":"$artist","albumName":"A","duration":$durS,""" +
            """"instrumental":false,"plainLyrics":${plain?.let { jstr(it) } ?: "null"},""" +
            """"syncedLyrics":${synced?.let { jstr(it) } ?: "null"}}"""

    private fun jstr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    // ================================================================== 1. tags

    @Test
    fun `embedded stream tags answer first`() {
        val dir = tempDir()
        // an Ogg keeps its vorbiscomments per STREAM — a format-only probe
        // would read none of them (the real-file shape, 2026-09-02)
        val probe = """{"streams":[{"tags":{"TITLE":"We All Become","UNSYNCEDLYRICS":"When you speak I hear silence\r\nEvery word a defiance"}}],"format":{}}"""
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, probe), lrclib = false, netease = false)
        val got = assertNotNull(f.fetch(track(dir)))
        assertEquals("tags", got.source)
        assertNull(got.synced)
        assertTrue(got.plain!!.startsWith("When you speak I hear silence"))
        assertTrue(got.plain!!.contains("Every word a defiance"), "no truncation of a multi-line tag")
    }

    @Test
    fun `a stamped tag is synced and a plain tag rides along`() {
        val dir = tempDir()
        val probe = """{"streams":[{"tags":{"lyrics-eng":${jstr(LRC_TEXT)}}}],"format":{"tags":{"UNSYNCEDLYRICS":"plain words"}}}"""
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, probe), lrclib = false, netease = false)
        val got = assertNotNull(f.fetch(track(dir)))
        assertEquals("tags", got.source)
        assertEquals(LRC_TEXT, got.synced)
        assertEquals("plain words", got.plain)
    }

    @Test
    fun `an ffprobe that does not run is loud and blocks a durable miss`() {
        val dir = tempDir()
        val f = LyricsFetch(ffprobe = dir.resolve("no-such-ffprobe").toString(), lrclib = false, netease = false)
        captureLogs()
        val e = assertFailsWith<IllegalStateException> { f.fetch(track(dir)) }
        assertTrue(e.message!!.contains("NOT a durable miss"), e.message!!)
        assertTrue(logs.any { it.startsWith("ERROR") && it.contains("did not answer") }, logs.toString())
    }

    // ================================================================== 2. the sidecar

    @Test
    fun `an lrc beside the track is used and handed through untouched`() {
        val dir = tempDir()
        val t = track(dir)
        Files.write(dir.resolve("creep.lrc"), LRC_TEXT.toByteArray())
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), lrclib = false, netease = false)
        val got = assertNotNull(f.fetch(t))
        assertEquals("lrc", got.source)
        assertEquals(LRC_TEXT, got.synced)
        assertNull(got.plain)
    }

    @Test
    fun `a txt beside the track is plain, and a long one is not truncated`() {
        val dir = tempDir()
        val t = track(dir)
        val long = (1..500).joinToString("\n") { "line $it of a very long lyric sheet" }
        Files.write(dir.resolve("creep.txt"), long.toByteArray())
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), lrclib = false, netease = false)
        val got = assertNotNull(f.fetch(t))
        assertEquals("lrc", got.source)
        assertNull(got.synced)
        assertEquals(long, got.plain)
        assertEquals(500, got.plain!!.lines().size)
    }

    // ================================================================== 3. LRCLIB

    @Test
    fun `lrclib get answers with synced lyrics`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf("/api/get" to { _ -> ok(lrclibRow("Radiohead", "Creep", 239.0, LRC_TEXT, "plain")) }), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), netease = false, http = http,
            lrclibBase = "https://lrclib.test", clock = clock.read, pause = clock.pause)
        val got = assertNotNull(f.fetch(track(dir)))
        assertEquals("lrclib", got.source)
        assertEquals(LRC_TEXT, got.synced)
        val url = http.calls.single().first
        assertTrue(url.contains("artist_name=Radiohead"), url)
        assertTrue(url.contains("track_name=Creep"), url)
        assertTrue(url.contains("album_name=Pablo%20Honey"), url)
        assertTrue(url.contains("duration=239"), url)
    }

    @Test
    fun `a 404 is a durable miss and the search endpoint is tried, best duration wins`() {
        val dir = tempDir()
        val clock = FakeClock()
        val rows = "[" + lrclibRow("Radiohead", "Creep (Acoustic)", 259.0, "[00:01.00]wrong take", null) + "," +
            lrclibRow("Radiohead", "Creep", 238.0, LRC_TEXT, null) + "]"
        val http = FakeHttp(listOf(
            "/api/get" to { _ -> HttpReply(404, """{"message":"Failed to find specified track","name":"TrackNotFound","statusCode":404}""") },
            "/api/search" to { _ -> ok(rows) },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), netease = false, http = http,
            lrclibBase = "https://lrclib.test", clock = clock.read, pause = clock.pause)
        val got = assertNotNull(f.fetch(track(dir)))
        assertEquals("lrclib", got.source)
        assertEquals(LRC_TEXT, got.synced, "the 238 s row is within +-3 s of 239 s; the 259 s take is not")
        assertEquals(2, http.calls.size)
    }

    @Test
    fun `an instrumental row is a miss, not a hit`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf(
            "/api/get" to { _ -> ok("""{"id":1,"trackName":"Creep","artistName":"Radiohead","duration":239.0,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}""") },
            "/api/search" to { _ -> ok("[]") },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), netease = false, http = http,
            lrclibBase = "https://lrclib.test", clock = clock.read, pause = clock.pause)
        captureLogs()
        assertNull(f.fetch(track(dir)), "every source was asked and had nothing — a durable miss")
        assertTrue(logs.any { it.contains("instrumental") }, logs.toString())
    }

    @Test
    fun `a 500 is transient, the chain moves on, and the reason is logged`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf(
            "/api/get" to { _ -> HttpReply(500, "upstream busy") },
            "/api/search/pc" to { _ -> ok(neteaseSearchBody()) },
            "/api/song/lyric" to { _ -> ok("""{"code":200,"lrc":{"version":1,"lyric":${jstr(LRC_TEXT)}}}""") },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), http = http,
            lrclibBase = "https://lrclib.test", neteaseBase = "https://netease.test",
            clock = clock.read, pause = clock.pause)
        captureLogs()
        val got = assertNotNull(f.fetch(track(dir)))
        assertEquals("netease", got.source, "a 5xx is transient — the next source answers")
        assertTrue(logs.any { it.startsWith("ERROR") && it.contains("lrclib") && it.contains("500") }, logs.toString())
    }

    @Test
    fun `when every source only faulted, fetch throws instead of reporting a miss`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf(
            "/api/get" to { _ -> HttpReply(429, "slow down") },
            "/api/search/pc" to { _ -> ok("""{"code":-462,"message":"bind a phone number"}""") },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), http = http,
            lrclibBase = "https://lrclib.test", neteaseBase = "https://netease.test",
            clock = clock.read, pause = clock.pause)
        val e = assertFailsWith<IllegalStateException> { f.fetch(track(dir)) }
        assertTrue(e.message!!.contains("2 source(s) did not answer"), e.message!!)
        assertTrue(e.message!!.contains("429"), e.message!!)
        assertTrue(e.message!!.contains("-462"), e.message!!)
        assertTrue(e.message!!.contains("do not record it as one"), e.message!!)
    }

    // ================================================================== 4. NetEase

    private fun neteaseSearchBody() =
        """{"code":200,"result":{"songs":[
           {"id":186016,"name":"Creep","duration":238640,"artists":[{"name":"Radiohead","id":99384}],"album":{"name":"Pablo Honey"}},
           {"id":999,"name":"Creep (Live)","duration":300000,"artists":[{"name":"Radiohead"}],"album":{"name":"Live"}}]}}"""

    @Test
    fun `netease search then song lyric`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf(
            "/api/search/pc" to { _ -> ok(neteaseSearchBody()) },
            "/api/song/lyric" to { _ -> ok("""{"code":200,"lrc":{"version":1,"lyric":${jstr(LRC_TEXT)}},"klyric":{"lyric":""}}""") },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), lrclib = false, http = http,
            neteaseBase = "https://netease.test", clock = clock.read, pause = clock.pause)
        val got = assertNotNull(f.fetch(track(dir)))
        assertEquals("netease", got.source)
        assertEquals(LRC_TEXT, got.synced)
        assertEquals(2, http.calls.size)
        assertTrue(http.calls[0].first.contains("s=Radiohead%20Creep"), http.calls[0].first)
        assertTrue(http.calls[1].first.contains("id=186016"), "the 238.6 s song, not the 300 s live take")
        assertTrue(http.calls[1].first.contains("lv=1") && http.calls[1].first.contains("kv=1") && http.calls[1].first.contains("tv=-1"))
    }

    @Test
    fun `a refusal code from netease is a fault, not an empty result`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf("/api/search/pc" to { _ -> ok("""{"code":-462,"message":"bind a phone number"}""") }), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), lrclib = false, http = http,
            neteaseBase = "https://netease.test", clock = clock.read, pause = clock.pause)
        val e = assertFailsWith<IllegalStateException> { f.fetch(track(dir)) }
        assertTrue(e.message!!.contains("netease search refused"), e.message!!)
    }

    // ================================================================== 5. Musixmatch

    @Test
    fun `musixmatch is off by default and is never called`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf(
            "/api/get" to { _ -> HttpReply(404, "{}") },
            "/api/search?" to { _ -> ok("[]") },
            "/api/search/pc" to { _ -> ok("""{"code":200,"result":{"songs":[]}}""") },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), http = http,
            lrclibBase = "https://lrclib.test", neteaseBase = "https://netease.test",
            clock = clock.read, pause = clock.pause)
        assertNull(f.fetch(track(dir)))
        assertTrue(http.calls.none { it.first.contains("musixmatch") }, http.calls.toString())
    }

    @Test
    fun `a musixmatch route that stops working says so and the chain carries on`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf(
            "token.get" to { _ -> ok("""{"message":{"header":{"status_code":401,"hint":"captcha"}}}""") },
            "/api/get" to { _ -> HttpReply(404, "{}") },
            "/api/search?" to { _ -> ok("[]") },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), netease = false, musixmatch = true, http = http,
            lrclibBase = "https://lrclib.test", musixmatchBase = "https://mxm.test/ws/1.1",
            clock = clock.read, pause = clock.pause)
        captureLogs()
        val e = assertFailsWith<IllegalStateException> { f.fetch(track(dir)) }
        assertTrue(e.message!!.contains("musixmatch route stopped working: HTTP 200, status_code 401 (captcha)"), e.message!!)
        assertTrue(logs.any { it.startsWith("ERROR") && it.contains("musixmatch route stopped working") }, logs.toString())
        assertEquals(2, http.calls.count { it.first.contains("token.get") }, "one paced re-ask, then it reports")
    }

    @Test
    fun `musixmatch richsync becomes enhanced LRC`() {
        val f = LyricsFetch(lrclib = false, netease = false)
        val raw = """[{"ts":10.5,"te":12.0,"l":[{"c":"When","o":0.0},{"c":" you","o":0.4}],"x":"When you"},
                      {"ts":72.25,"te":74.0,"l":[{"c":"again","o":0.0}],"x":"again"}]"""
        val lrc = assertNotNull(f.richsyncToLrc(raw))
        assertEquals("[00:10.50] <00:10.50> When <00:10.90>  you \n[01:12.25] <01:12.25> again", lrc)
        assertTrue(LrcSanity.hasStamps(lrc))
    }

    @Test
    fun `a musixmatch token is fetched once and reused`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf(
            "token.get" to { _ -> ok("""{"message":{"header":{"status_code":200},"body":{"user_token":"tok123"}}}""") },
            "track.search" to { _ -> ok("""{"message":{"header":{"status_code":200},"body":{"track_list":[
                {"track":{"track_id":42,"track_name":"Creep","artist_name":"Radiohead","track_length":239}}]}}}""") },
            "track.richsync.get" to { _ -> ok("""{"message":{"header":{"status_code":404}}}""") },
            "track.subtitle.get" to { _ -> ok("""{"message":{"header":{"status_code":200},"body":{"subtitle":{"subtitle_body":${jstr(LRC_TEXT)}}}}}""") },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), lrclib = false, netease = false, musixmatch = true,
            http = http, musixmatchBase = "https://mxm.test/ws/1.1", clock = clock.read, pause = clock.pause)
        val got = assertNotNull(f.fetch(track(dir)))
        assertEquals("musixmatch", got.source)
        assertEquals(LRC_TEXT, got.synced)
        assertEquals(1, http.calls.count { it.first.contains("token.get") })
        assertTrue(http.calls.any { it.first.contains("usertoken=tok123") })
    }

    // ================================================================== search

    @Test
    fun `search labels every candidate and puts the best first`() {
        val dir = tempDir()
        val clock = FakeClock()
        val rows = "[" +
            lrclibRow("Nirvana", "Something in the Way", 232.0, null, "underneath the bridge") + "," +
            lrclibRow("Radiohead", "Creep", 239.0, LRC_TEXT, null) + "]"
        val http = FakeHttp(listOf("/api/search" to { _ -> ok(rows) }), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), netease = false, http = http,
            lrclibBase = "https://lrclib.test", clock = clock.read, pause = clock.pause)
        val out = f.search(track(dir), "radiohead creep")
        assertEquals(2, out.size)
        assertEquals("Radiohead — Creep · 3:59 · lrclib", out[0].label)
        assertEquals("Nirvana — Something in the Way · 3:52 · lrclib", out[1].label)
        assertEquals(LRC_TEXT, out[0].synced)
        assertTrue(http.calls.single().first.contains("q=radiohead%20creep"), http.calls.single().first)
    }

    @Test
    fun `search caps the list at ten, best first`() {
        val dir = tempDir()
        val clock = FakeClock()
        val rows = (1..15).joinToString(",", "[", "]") { lrclibRow("Band $it", "Song $it", 200.0 + it, null, "words $it") }
        val http = FakeHttp(listOf("/api/search" to { _ -> ok(rows) }), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), netease = false, http = http,
            lrclibBase = "https://lrclib.test", clock = clock.read, pause = clock.pause)
        val out = f.search(track(dir), "band 3 song 3")
        assertEquals(10, out.size)
        assertEquals("Band 3 — Song 3 · 3:23 · lrclib", out[0].label)
    }

    @Test
    fun `search offers what is on disk too`() {
        val dir = tempDir()
        val t = track(dir)
        Files.write(dir.resolve("creep.lrc"), LRC_TEXT.toByteArray())
        val clock = FakeClock()
        val rows = "[" + lrclibRow("Band 1", "Song 1", 201.0, null, "words 1") + "," +
            lrclibRow("Band 2", "Song 2", 202.0, null, "words 2") + "]"
        val http = FakeHttp(listOf("/api/search" to { _ -> ok(rows) }), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), netease = false, http = http,
            lrclibBase = "https://lrclib.test", clock = clock.read, pause = clock.pause)
        val out = f.search(t, "band 1 song 1")
        assertEquals(3, out.size)
        assertEquals("Band 1 — Song 1 · 3:21 · lrclib", out[0].label)
        val disk = out.single { it.source == "lrc" }
        assertEquals("Radiohead — Creep · 3:59 · lrc", disk.label, "a manual search can always get back to the file on disk")
        assertEquals(LRC_TEXT, disk.synced)
    }

    @Test
    fun `an empty search query is refused loudly`() {
        val dir = tempDir()
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), lrclib = false, netease = false)
        assertFailsWith<IllegalArgumentException> { f.search(track(dir), "   ") }
    }

    // ================================================================== pacing

    @Test
    fun `two calls to one source are at least 350 ms apart`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf(
            "/api/get" to { _ -> HttpReply(404, "{}") },
            "/api/search" to { _ -> ok("[" + lrclibRow("Radiohead", "Creep", 239.0, LRC_TEXT, null) + "]") },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), netease = false, http = http,
            lrclibBase = "https://lrclib.test", clock = clock.read, pause = clock.pause)
        assertNotNull(f.fetch(track(dir)))
        assertEquals(2, http.calls.size)
        val gap = http.calls[1].second - http.calls[0].second
        assertTrue(gap >= 350, "two lrclib calls must be paced 350 ms apart, saw $gap ms")
    }

    @Test
    fun `pacing is per source, so a different source does not wait`() {
        val dir = tempDir()
        val clock = FakeClock()
        val http = FakeHttp(listOf(
            "/api/get" to { _ -> HttpReply(404, "{}") },
            "/api/search?" to { _ -> ok("[]") },
            "/api/search/pc" to { _ -> ok(neteaseSearchBody()) },
            "/api/song/lyric" to { _ -> ok("""{"code":200,"lrc":{"lyric":${jstr(LRC_TEXT)}}}""") },
        ), clock.read)
        val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), http = http,
            lrclibBase = "https://lrclib.test", neteaseBase = "https://netease.test",
            clock = clock.read, pause = clock.pause)
        assertNotNull(f.fetch(track(dir)))
        val lrclibSecond = http.calls.filter { it.first.contains("lrclib.test") }[1].second
        val neteaseFirst = http.calls.first { it.first.contains("netease.test") }.second
        assertEquals(0L, neteaseFirst - lrclibSecond, "the first netease call has no lrclib debt to pay")
    }

    // ================================================================== the real Http seam

    @Test
    fun `the default http seam really talks to a server`() {
        val dir = tempDir()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = CopyOnWriteArrayList<String>()
        fun reply(x: HttpExchange, status: Int, body: String) {
            val b = body.toByteArray()
            x.sendResponseHeaders(status, b.size.toLong())
            x.responseBody.use { it.write(b) }
        }
        server.createContext("/api/get") { x ->
            seen.add(x.requestURI.toString() + " ua=" + (x.requestHeaders.getFirst("User-Agent") ?: ""))
            reply(x, 200, lrclibRow("Radiohead", "Creep", 239.0, LRC_TEXT, null))
        }
        server.start()
        try {
            // no `http =` argument: the real wm.damage.core.util.Http runs
            val f = LyricsFetch(ffprobe = fakeFfprobe(dir, NO_TAGS), netease = false,
                lrclibBase = "http://127.0.0.1:${server.address.port}")
            val got = assertNotNull(f.fetch(track(dir)))
            assertEquals("lrclib", got.source)
            assertEquals(LRC_TEXT, got.synced)
            assertTrue(seen.single().contains("ua=" + LyricsFetch.UA), seen.toString())
        } finally {
            server.stop(0)
        }
    }

    // ================================================================== the pure helpers

    @Test
    fun `LrcSanity reads stamps, labels and durations`() {
        assertTrue(LrcSanity.hasStamps("[00:12.34]hello"))
        assertTrue(LrcSanity.hasStamps("[01:02]hello"))
        assertTrue(LrcSanity.hasStamps("[123:45.678]hello"))
        assertTrue(!LrcSanity.hasStamps("hello there"))
        assertTrue(!LrcSanity.hasStamps("[verse 1]"))
        assertEquals("3:59", LrcSanity.mmss(239_000))
        assertEquals("0:07", LrcSanity.mmss(7_400))
        assertEquals("?:??", LrcSanity.mmss(0))
        assertEquals("Radiohead — Creep · 3:59 · lrclib", LrcSanity.label("Radiohead", "Creep", 239_000, "lrclib"))
        assertEquals("Creep · ?:?? · tags", LrcSanity.label("", "Creep", 0, "tags"))
        assertEquals("00:10.50", LrcSanity.stamp(10.5))
        assertEquals("01:12.25", LrcSanity.stamp(72.25))
        assertEquals("00:00.00", LrcSanity.stamp(-1.0))
        assertEquals(1.0, LrcSanity.score("radiohead creep", "Creep Radiohead"), 1e-9)
        assertEquals(0.0, LrcSanity.score("radiohead creep", ""), 1e-9)
    }
}
