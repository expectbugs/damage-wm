package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.util.Http
import wm.damage.core.windows.music.Art
import wm.damage.core.windows.music.AudioProfile
import wm.damage.core.windows.music.Catalog
import wm.damage.core.windows.music.Db
import wm.damage.core.windows.music.Lyrics
import wm.damage.core.windows.music.MediaCache
import wm.damage.core.windows.music.MediaServer
import wm.damage.core.windows.music.MusicDb
import wm.damage.core.windows.music.MusicLibrary
import wm.damage.core.windows.music.MusicService
import wm.damage.core.windows.music.Playlist
import wm.damage.core.windows.music.RemoteMusicLibrary
import wm.damage.core.windows.music.ResolvedQueue
import wm.damage.core.windows.music.Rules
import wm.damage.core.windows.music.TrackMeta
import wm.damage.core.windows.music.TrackRef
import wm.damage.core.windows.music.VizData
import wm.damage.core.windows.music.YtJob
import wm.damage.core.windows.music.YtResult

/**
 * MUSIC (MUSIC.md §10) — M1: profiles + the cache-key mapping (the legacy
 * G2CC cache read in place), the media endpoint's Range handling over a
 * real loopback socket, the VizData binary round trip, the queue rules
 * (sfx/spoken/dedupe/spread/cap), the catalog JSON, the SQL layer's
 * shapes over a recording fake, and the remote library over a real
 * loopback content host — catalog version cursor, disk caches, blobs,
 * and the PUSH slice (a catalog bump and a grab's progress arriving
 * unsolicited).
 */
class MusicTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(20)
        assertTrue(cond(), "did not converge: $what")
    }

    // =========================================================== profiles + cache keys
    @Test
    fun audioProfilesNameParseAndTheLegacyCacheMapping() {
        assertEquals("high-mono-loudnorm", AudioProfile.DEFAULT.name)
        assertEquals(128, AudioProfile.DEFAULT.kbps)
        assertEquals(192, AudioProfile(AudioProfile.Quality.HIGH, 2, true).kbps)
        assertEquals("standard-mono-loudnorm", AudioProfile.LEGACY.name)
        assertEquals(96, AudioProfile.LEGACY.kbps)
        assertEquals("lossless", AudioProfile(AudioProfile.Quality.LOSSLESS, 2, false).name)
        assertEquals("passthrough", AudioProfile(AudioProfile.Quality.LOSSLESS).codec)
        for (p in listOf(AudioProfile.DEFAULT, AudioProfile.LEGACY, AudioProfile(AudioProfile.Quality.SAVER, 2, false),
            AudioProfile(AudioProfile.Quality.LOSSLESS, 2, false))) {
            assertEquals(p.quality, AudioProfile.parse(p.name)!!.quality, p.name)
            assertEquals(p.name, AudioProfile.parse(p.name)!!.name)
        }
        assertNull(AudioProfile.parse("bogus"))
        assertNull(AudioProfile.parse("high-mono"))

        val tmp = Files.createTempDirectory("damage-music-cache")
        try {
            val legacy = tmp.resolve("legacy").also { Files.createDirectories(it) }
            val root = tmp.resolve("ours")
            val cache = MediaCache(root, legacy)
            // the SAME key shape as G2CC's mediaFileFor: <id>-<mtime>-<sha1(path)[:8]>.opus
            val t = MusicDb.TrackFile(1000, "/home/user/Music/Library/A/x.flac", 1477288148766L, "x", "A", "", 1000)
            val key = cache.keyFor(t)
            assertTrue(key.startsWith("1000-1477288148766-") && key.length == "1000-1477288148766-".length + 8, key)
            val sha = java.security.MessageDigest.getInstance("SHA-1").digest(t.path.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
            assertEquals("1000-1477288148766-$sha", key)
            // absent everywhere: our own directory is the target
            assertFalse(cache.isCached(t, AudioProfile.LEGACY))
            assertEquals(root.resolve("standard-mono-loudnorm").resolve("$key.opus"), cache.pathFor(t, AudioProfile.LEGACY))
            // present in the legacy dir: read in place, for the legacy profile ONLY
            Files.write(legacy.resolve("$key.opus"), byteArrayOf(1, 2, 3))
            assertTrue(cache.isCached(t, AudioProfile.LEGACY))
            assertEquals(legacy.resolve("$key.opus"), cache.pathFor(t, AudioProfile.LEGACY))
            assertFalse(cache.isCached(t, AudioProfile.DEFAULT))
            assertEquals(root.resolve("high-mono-loudnorm").resolve("$key.opus"), cache.pathFor(t, AudioProfile.DEFAULT))
            // the ffmpeg argument list per profile
            val args = cache.ffmpegArgs(t, AudioProfile.DEFAULT, tmp.resolve("o.part"))
            assertTrue(args.containsAll(listOf("-ac", "1", "-c:a", "libopus", "-b:a", "128k", "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-f", "ogg")), args.toString())
            val flat = cache.ffmpegArgs(t, AudioProfile(AudioProfile.Quality.SAVER, 2, false), tmp.resolve("o.part"))
            assertTrue("-af" !in flat && flat.containsAll(listOf("-ac", "2", "-b:a", "48k")), flat.toString())
            // a stale .part is swept at construction
            Files.createDirectories(root.resolve("high-mono-loudnorm"))
            Files.write(root.resolve("high-mono-loudnorm").resolve("stale.opus.part"), byteArrayOf(0))
            MediaCache(root, legacy)
            assertFalse(Files.exists(root.resolve("high-mono-loudnorm").resolve("stale.opus.part")))
            assertEquals("audio/flac", cache.mimeFor(t, AudioProfile(AudioProfile.Quality.LOSSLESS, 2, false)))
            assertEquals("audio/ogg", cache.mimeFor(t, AudioProfile.DEFAULT))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // =========================================================== the media endpoint
    @Test
    fun mediaEndpointServesWholeAndRangedBytesAndRefusesBadTokens() {
        val tmp = Files.createTempDirectory("damage-music-media")
        val port = freePort()
        try {
            val file = tmp.resolve("t.opus")
            val bytes = ByteArray(100_000) { (it % 251).toByte() }
            Files.write(file, bytes)
            val server = MediaServer(port, "tok") { id, p ->
                if (id != 7) throw IllegalStateException("track $id is not in the library")
                file to "audio/ogg"
            }
            server.start()
            try {
                val whole = Http.request("GET", "http://127.0.0.1:$port/track/7?token=tok&profile=high-mono-loudnorm")
                assertEquals(200, whole.status)
                assertEquals("audio/ogg", whole.contentType)
                assertEquals("bytes", whole.header("Accept-Ranges"))
                assertTrue(whole.body.contentEquals(bytes))
                val part = Http.request("GET", "http://127.0.0.1:$port/track/7?token=tok", mapOf("Range" to "bytes=10-19"))
                assertEquals(206, part.status)
                assertEquals("bytes 10-19/100000", part.header("Content-Range"))
                assertTrue(part.body.contentEquals(bytes.copyOfRange(10, 20)))
                val tail = Http.request("GET", "http://127.0.0.1:$port/track/7?token=tok", mapOf("Range" to "bytes=99990-"))
                assertEquals(206, tail.status)
                assertEquals(10, tail.body.size)
                val suffix = Http.request("GET", "http://127.0.0.1:$port/track/7?token=tok", mapOf("Range" to "bytes=-5"))
                assertEquals(206, suffix.status)
                assertTrue(suffix.body.contentEquals(bytes.copyOfRange(99_995, 100_000)))
                // a MALFORMED range answers the whole file (never 416 — ExoPlayer treats it as fatal)
                val bad = Http.request("GET", "http://127.0.0.1:$port/track/7?token=tok", mapOf("Range" to "bytes=abc"))
                assertEquals(200, bad.status)
                assertEquals(100_000, bad.body.size)
                val past = Http.request("GET", "http://127.0.0.1:$port/track/7?token=tok", mapOf("Range" to "bytes=200000-300000"))
                assertEquals(200, past.status)
                assertEquals(403, Http.request("GET", "http://127.0.0.1:$port/track/7?token=nope").status)
                assertEquals(404, Http.request("GET", "http://127.0.0.1:$port/track/8?token=tok").status)
                assertEquals(400, Http.request("GET", "http://127.0.0.1:$port/track/7?token=tok&profile=weird").status)
                val head = Http.request("HEAD", "http://127.0.0.1:$port/track/7?token=tok")
                assertEquals(200, head.status)
                assertEquals("100000", head.header("Content-Length"))
                // a 0-byte file never answers 206 (a suffix range would have said "bytes 0--1/0")
                Files.write(file, ByteArray(0))
                val empty = Http.request("GET", "http://127.0.0.1:$port/track/7?token=tok", mapOf("Range" to "bytes=-5"))
                assertEquals(200, empty.status); assertEquals(0, empty.body.size)
            } finally {
                server.close()
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // =========================================================== viz data
    @Test
    fun vizDataRoundTripsAndAnswersLevelsBeatsAndRms() {
        val bands = 24
        val frames = 50
        val packed = ByteArray((frames * bands + 1) / 2)
        for (i in 0 until frames * bands) {
            val n = (i * 7) % 16
            if (i and 1 == 0) packed[i shr 1] = (n shl 4).toByte() else packed[i shr 1] = (packed[i shr 1].toInt() or n).toByte()
        }
        val rms = ByteArray(13) { 0x5A.toByte() }
        val v = VizData(20, bands, frames, packed, 25, rms, intArrayOf(0, 500, 1000, 1500))
        val b = v.encode()
        val d = VizData.decode(b)
        assertEquals(20, d.fps); assertEquals(bands, d.bands); assertEquals(frames, d.frameCount); assertEquals(25, d.rmsCount)
        assertEquals(4, d.beatsMs.size)
        for (f in 0 until frames) for (k in 0 until bands) assertEquals(((f * bands + k) * 7) % 16, d.level(f, k))
        assertEquals(5, d.rmsAt(0)); assertEquals(10, d.rmsAt(20)); assertEquals(5, d.rmsAt(10_000))   // clamped to slot 24 (even → high nibble)
        assertEquals(20, d.frameAt(1000)); assertEquals(0, d.level(-5, 0))
        assertEquals(-1L, d.sinceBeat(-1)); assertEquals(0L, d.sinceBeat(500)); assertEquals(250L, d.sinceBeat(1750))
        assertFailsWith<IllegalArgumentException> { VizData.decode(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { VizData.decode(b.copyOfRange(0, b.size - 3)) }
    }

    // =========================================================== the rules
    @Test
    fun queueRulesExcludeDedupeSpreadAndCap() {
        fun c(id: Int, artist: String, path: String, cluster: Int? = null, styles: List<String> = emptyList()) =
            Rules.Cand(id, "t$id", artist, "", 1000, path, styles = styles, dupeCluster = cluster)
        val rows = listOf(
            c(1, "A", "/m/a1.mp3"), c(2, "A", "/m/a2.flac", cluster = 9), c(3, "A", "/m/Archive/a3.flac", cluster = 9),
            c(4, "B", "/m/b.mp3", styles = listOf("Sound Effect")), c(5, "C", "/m/c.ogg", styles = listOf("Spoken Word")),
            c(6, "D", "/m/d.mp3", cluster = 9),
        )
        // sfx out always (unless named); spoken out on discovery lanes; one per cluster, flac beats mp3, non-archive wins the tie
        val disc = Rules.postProcess(rows, Rules.Opts(shuffle = false, excludeSpoken = true, cap = null, requestLc = "metal"))
        assertEquals(listOf(1, 2), disc.map { it.id })
        val explicit = Rules.postProcess(rows, Rules.Opts(shuffle = false, excludeSpoken = false, cap = null, requestLc = "c"))
        assertEquals(listOf(1, 2, 5), explicit.map { it.id })
        val sfx = Rules.postProcess(rows, Rules.Opts(shuffle = false, excludeSpoken = true, cap = null, requestLc = "wurm sound effects"))
        assertTrue(sfx.any { it.id == 4 })
        val capped = Rules.postProcess(rows, Rules.Opts(shuffle = false, excludeSpoken = false, cap = 2, requestLc = "x"))
        assertEquals(2, capped.size)
        // the spread: many A's and one B never leaves two A's adjacent when avoidable
        val many = (1..6).map { c(it, "A", "/m/$it.mp3") } + c(7, "B", "/m/7.mp3") + c(8, "C", "/m/8.mp3")
        val spread = Rules.artistSpreadShuffle(many, { it.artist }, java.util.Random(4))
        assertEquals(8, spread.size)
        assertEquals(many.map { it.id }.toSet(), spread.map { it.id }.toSet())
        // dedupe is order-independent on a tie
        val a = Rules.dedupeClusters(listOf(c(2, "A", "/m/a2.flac", 9), c(6, "D", "/m/d.flac", 9)))
        val b = Rules.dedupeClusters(listOf(c(6, "D", "/m/d.flac", 9), c(2, "A", "/m/a2.flac", 9)))
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(listOf("hard", "metal"), Rules.tokens("Play some hard metal stuff."))
        assertTrue(Rules.tokens("play something random").isEmpty())
        assertEquals("100\\%", Rules.escapeLike("100%"))
        assertEquals(5, Rules.fidelityRank("/x/y.FLAC"))
    }

    // =========================================================== catalog JSON
    @Test
    fun catalogEncodesDecodesAndIndexes() {
        val c = Catalog("v1", 1L, listOf(TrackMeta(1, "One", "A", "Al", 1000, genres = listOf("rock")), TrackMeta(2, "Two")),
            playlists = listOf(Playlist(3, "P", count = 2)))
        val d = Catalog.decode(c.encode())
        assertEquals(2, d.tracks.size)
        assertEquals("One", d.track(1)!!.title)
        assertEquals(listOf("rock"), d.track(1)!!.genres)
        assertEquals(TrackRef(1, "One", "A", "Al", 1000), d.track(1)!!.ref())
        assertNull(d.track(9))
        // an older peer's blob (missing fields) still decodes
        val old = Catalog.decode("""{"version":"v0","tracks":[{"id":5,"title":"Old"}]}""".toByteArray())
        assertEquals("Old", old.track(5)!!.title)
    }

    // =========================================================== the SQL layer over a recording fake
    private class FakeDb : Db {
        val calls = ArrayList<Pair<String, List<Any?>>>()
        var answer: (String) -> List<Db.Row> = { emptyList() }
        override fun query(sql: String, vararg args: Any?): List<Db.Row> { calls.add(sql to args.toList()); return answer(sql) }
        override fun exec(sql: String, vararg args: Any?): Int { calls.add(sql to args.toList()); return 1 }
        override fun <T> tx(block: (Db) -> T): T = block(this)
    }

    @Test
    fun sqlLayerShapesItsQueriesAndRefusesAdaptiveEdits() {
        val db = FakeDb()
        val m = MusicDb(db, listOf("/home/user/Music"))
        // search: one LIKE group per token, escaped
        db.answer = { emptyList() }
        m.search("pink 100%")
        val (sql, args) = db.calls.last()
        assertTrue(sql.contains("LIKE ?") && sql.count { it == '?' } == 9, sql)
        assertEquals(listOf("%pink%", "%pink%", "%pink%", "%pink%", "%100\\%%", "%100\\%%", "%100\\%%", "%100\\%%", 200), args)
        assertTrue(m.search("   ").isEmpty())
        // a plan → SQL with the union-of-terms overlap and the order words
        m.planCands(MusicDb.Plan(genres = listOf("Metal"), energyMin = 7, order = "least_recent", exclude = listOf("sound effect")), 25)
        val (psql, pargs) = db.calls.last()
        assertTrue(psql.contains("NULLS FIRST") && psql.contains("NOT EXISTS") && psql.contains("m.energy >= ?"), psql)
        assertTrue((pargs[0] as Db.TextArr).v == listOf("metal"))
        assertTrue(m.planCands(MusicDb.Plan(), 25).isEmpty())
        // rows map: text[] arrives as a List, dupe_cluster null stays null
        db.answer = { listOf(Db.Row(mapOf("id" to 4, "path" to "/home/user/Music/Library/A/x.flac", "title" to "X", "artist" to "A", "album" to "",
            "dur_ms" to 1000, "genres" to listOf("rock"), "styles" to null, "moods" to null, "dupe_cluster" to null))) }
        val cands = m.candsByIds(listOf(4))
        assertEquals(listOf("rock"), cands[0].genres)
        assertNull(cands[0].dupeCluster)
        assertEquals(TrackRef(4, "X", "A", "", 1000), cands[0].ref())
        // the adaptive guard
        db.answer = { s -> if (s.contains("FOR UPDATE")) listOf(Db.Row(mapOf("id" to 1, "adaptive" to true))) else emptyList() }
        val e = assertFailsWith<IllegalStateException> { m.setPlaylistTracks(1, listOf(1, 2)) }
        assertTrue(e.message!!.contains("adaptive"))
        val e2 = assertFailsWith<IllegalStateException> { m.savePlaylist("Rock", listOf(1), overwrite = true) }
        assertTrue(e2.message!!.contains("adaptive"))
        assertFailsWith<IllegalArgumentException> { m.savePlaylist("  ", listOf(1), false) }
        assertFailsWith<IllegalArgumentException> { m.savePlaylist("x", emptyList(), false) }
        // an existing name without overwrite is refused (the window asks twice first)
        db.answer = { s -> if (s.contains("FOR UPDATE")) listOf(Db.Row(mapOf("id" to 1, "adaptive" to false))) else emptyList() }
        assertFailsWith<IllegalStateException> { m.savePlaylist("Rock", listOf(1), overwrite = false) }
        // migrations: the recording table is created, each pending one applied and recorded
        db.calls.clear()
        db.answer = { emptyList() }
        m.migrate()
        assertTrue(db.calls.any { it.first.contains("CREATE TABLE IF NOT EXISTS damage_schema") })
        assertTrue(db.calls.any { it.first.contains("ADD COLUMN IF NOT EXISTS track_id") })
        assertTrue(db.calls.any { it.first.startsWith("INSERT INTO damage_schema") && it.second == listOf<Any?>("lyrics-source-trackid-1") })
        // already applied: nothing runs
        db.calls.clear()
        db.answer = { s -> if (s.contains("FROM damage_schema")) listOf(Db.Row(mapOf("name" to "lyrics-source-trackid-1"))) else emptyList() }
        m.migrate()
        assertFalse(db.calls.any { it.first.contains("ADD COLUMN") })
        // lyrics: the track link first, the legacy key second
        db.calls.clear()
        db.answer = { s -> if (s.contains("track_id = ?")) emptyList()
            else listOf(Db.Row(mapOf("synced" to "[00:01.00] hi", "plain" to null, "found" to true, "source" to ""))) }
        val ly = m.lyrics(MusicDb.TrackFile(4, "/p.flac", 0, "Song", "Art", "", 123_400))
        assertNotNull(ly); assertEquals("lrclib", ly!!.source); assertTrue(ly.found)
        assertEquals(listOf<Any?>("Art", "Song", 123), db.calls.last().second)
        assertEquals(Art.pack(byteArrayOf(0, 255.toByte(), 128.toByte()), 3).toList(), listOf(0x0F.toByte(), 0x80.toByte()).toList())
    }

    // =========================================================== the remote library over a loopback host
    private class FakeLibrary : MusicLibrary {
        val listeners = CopyOnWriteArrayList<MusicLibrary.Listener>()
        val ops = CopyOnWriteArrayList<String>()
        @Volatile var cat = Catalog("v1", 1L, listOf(TrackMeta(1, "One", "A", "Al", 1000, hasLyrics = true), TrackMeta(2, "Two", "B")),
            playlists = listOf(Playlist(3, "P", count = 2)))
        override fun stateLine() = ""
        override fun catalog() = cat
        override fun refreshCatalog() { ops.add("refresh") }
        override fun search(q: String) = listOf(TrackRef(1, "One", "A"))
        override fun ask(request: String) = ResolvedQueue(listOf(TrackRef(2, "Two")), "vocab", request, "lane vocab: 1")
        override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int) = listOf(TrackRef(2, "Two"))
        override fun randomLibrary(n: Int, exclude: List<Int>) = listOf(TrackRef(1, "One"), TrackRef(2, "Two")).filter { it.id !in exclude }.take(n)
        override fun playlists() = cat.playlists
        override fun playlistTracks(id: Int) = listOf(TrackRef(1, "One"), TrackRef(2, "Two"))
        override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean): Playlist { ops.add("save:$name:$trackIds:$overwrite"); return Playlist(4, name, count = trackIds.size) }
        override fun renamePlaylist(id: Int, name: String) { ops.add("rename:$id:$name") }
        override fun deletePlaylist(id: Int) { ops.add("delete:$id") }
        override fun setPlaylistTracks(id: Int, trackIds: List<Int>) { if (id == 9) throw IllegalStateException("adaptive playlist — its rule manages the rows"); ops.add("set:$id:$trackIds") }
        override fun lyrics(trackId: Int) = if (trackId == 1) Lyrics("lrclib", "[00:01.00] hello", null) else null
        override fun searchLyrics(trackId: Int, query: String) = listOf(Lyrics("netease", "[00:02.00] x", null, "A — $query"))
        override fun setLyrics(trackId: Int, choice: Lyrics) { ops.add("lyrics.set:$trackId:${choice.source}") }
        override fun art(trackId: Int, px: Int): ByteArray? = if (trackId == 1) ByteArray(px * px / 2) { 0x77 } else null
        override fun viz(trackId: Int): VizData? = if (trackId == 1) VizData(20, 4, 2, byteArrayOf(0x12, 0x34, 0x56, 0x78), 2, byteArrayOf(0x9A.toByte()), intArrayOf(100)) else null
        override fun ytSearch(q: String) = listOf(YtResult("abc", "Video $q", "chan", 200, "https://youtube.com/watch?v=abc"))
        override fun ytGrab(id: String): String {
            ops.add("grab:$id")
            for (l in listeners) l.ytJob(YtJob("job1", "Video", "downloading", 40))
            return "job1"
        }
        override fun ytStatus(job: String) = YtJob(job, "Video", "done", 100, 7)
        override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) { ops.add("played:$trackId:$completed:$skipped") }
        override fun streamUrl(trackId: Int, profile: AudioProfile) = "local"
        override fun pretranscode(profile: AudioProfile) = "building for ${profile.name}"
        override fun rescan() = "rescan: 0 files"
        override fun addListener(l: MusicLibrary.Listener) { listeners.addIfAbsent(l) }
        override fun removeListener(l: MusicLibrary.Listener) { listeners.remove(l) }
        override fun setFocused(focused: Boolean, paceMs: Long) {}
        fun bump() {
            cat = cat.copy(version = "v2", tracks = cat.tracks + TrackMeta(3, "Three"))
            for (l in listeners) l.catalogChanged(cat)
        }
    }

    @Test
    fun remoteLibraryCursorsTheCatalogCachesBlobsAndReceivesPushes(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-music-remote")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val books = tmp.resolve("books").also { Files.createDirectories(it) }
        val fake = FakeLibrary()
        val host = ContentHostServer(LocalContent(books), port, "tok", win = mapOf("music" to MusicService(fake)))
        host.start()
        val cacheDir = tmp.resolve("music")
        val remote = RemoteMusicLibrary("127.0.0.1", port, "tok", 7404, cacheDir, scope)
        val jobs = CopyOnWriteArrayList<YtJob>()
        val cats = CopyOnWriteArrayList<String>()
        remote.addListener(object : MusicLibrary.Listener {
            override fun catalogChanged(c: Catalog) { cats.add(c.version) }
            override fun ytJob(j: YtJob) { jobs.add(j) }
        })
        try {
            awaitTrue("the catalog arrives on attach") { remote.catalog().version == "v1" }
            assertEquals(2, remote.catalog().tracks.size)
            assertTrue(Files.isRegularFile(cacheDir.resolve("catalog.json")))
            assertEquals("", remote.stateLine())
            // the cursor: unchanged → no blob, the version stays
            val before = cats.size
            remote.refreshCatalog()
            assertEquals(before, cats.size)
            // the host's catalog bumps → a PUSH → the remote refetches by itself
            fake.bump()
            awaitTrue("the pushed bump refetched the catalog") { remote.catalog().version == "v2" && remote.catalog().tracks.size == 3 }
            // ops ride the channel; blobs come back typed
            assertEquals("One", remote.search("o")[0].title)
            assertEquals("vocab", remote.ask("metal").lane)
            assertEquals(listOf(2), remote.similar(listOf(1), emptyList(), 5).map { it.id })
            assertEquals(listOf(2), remote.randomLibrary(5, listOf(1)).map { it.id })
            assertEquals("P", remote.playlists()[0].name)
            assertEquals(2, remote.playlistTracks(3).size)
            assertEquals(4, remote.savePlaylist("New", listOf(1, 2), false).id)
            assertTrue(fake.ops.contains("save:New:[1, 2]:false"))
            remote.renamePlaylist(4, "Newer"); remote.deletePlaylist(4); remote.setPlaylistTracks(3, listOf(2, 1))
            assertTrue(fake.ops.containsAll(listOf("rename:4:Newer", "delete:4", "set:3:[2, 1]")))
            val refused = assertFailsWith<IllegalStateException> { remote.setPlaylistTracks(9, listOf(1)) }
            assertTrue(refused.message!!.contains("adaptive"))
            // lyrics: found ones cache on disk; a miss does not
            val ly = remote.lyrics(1)
            assertNotNull(ly); assertEquals("lrclib", ly!!.source)
            assertTrue(Files.isRegularFile(cacheDir.resolve("lyrics").resolve("1.json")))
            assertNull(remote.lyrics(2))
            assertEquals("A — hello", remote.searchLyrics(1, "hello")[0].label)
            remote.setLyrics(2, Lyrics("netease", "[00:02.00] x")); assertTrue(fake.ops.contains("lyrics.set:2:netease"))
            // art + viz blobs, cached (a miss is remembered too)
            val art = remote.art(1, 56)
            assertNotNull(art); assertEquals(56 * 56 / 2, art!!.size)
            assertTrue(Files.isRegularFile(cacheDir.resolve("art").resolve("1-56.gray")))
            assertNull(remote.art(2, 56)); assertTrue(Files.isRegularFile(cacheDir.resolve("art").resolve("2.none")))
            val viz = remote.viz(1)
            assertNotNull(viz); assertEquals(4, viz!!.bands); assertEquals(1, viz.level(0, 0)); assertEquals(100, viz.beatsMs[0])
            assertTrue(Files.isRegularFile(cacheDir.resolve("viz").resolve("1.viz")))
            assertNull(remote.viz(2))
            // YouTube: search, a grab whose progress is PUSHED, status
            assertEquals("Video q", remote.ytSearch("q")[0].title)
            assertEquals("job1", remote.ytGrab("abc"))
            awaitTrue("the grab's progress arrived as a push") { jobs.any { it.job == "job1" && it.phase == "downloading" && it.percent == 40 } }
            assertEquals(7, remote.ytStatus("job1").trackId)
            remote.played(1, 0, 1000, true, false); assertTrue(fake.ops.contains("played:1:true:false"))
            assertTrue(remote.streamUrl(1, AudioProfile.DEFAULT).startsWith("http://127.0.0.1:7404/track/1?token=tok&profile=high-mono-loudnorm"))
            assertTrue(remote.pretranscode(AudioProfile.DEFAULT).startsWith("building"))
            assertTrue(remote.rescan().startsWith("rescan"))
            // a NEW remote over the same cache starts from disk before the host answers
            val remote2 = RemoteMusicLibrary("127.0.0.1", freePort(), "tok", 7404, cacheDir, scope)
            assertEquals("v2", remote2.catalog().version)
            assertTrue(remote2.stateLine().isNotEmpty())
            remote2.close()
        } finally {
            remote.close()
            host.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
