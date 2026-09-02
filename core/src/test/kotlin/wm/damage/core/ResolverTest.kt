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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.windows.music.ClaudeOneShot
import wm.damage.core.windows.music.Db
import wm.damage.core.windows.music.EmbedQuery
import wm.damage.core.windows.music.MusicDb
import wm.damage.core.windows.music.MusicProc
import wm.damage.core.windows.music.Qdrant
import wm.damage.core.windows.music.Resolver

/**
 * The resolver's three lanes (`MUSIC.md` §9.3) over a recording fake
 * database, an injected model lambda and a loopback Qdrant — plus the two
 * subprocess collaborators against shell scripts.
 *
 * Nothing here reaches the network, Postgres, Qdrant, `claude`, python or
 * the phone: the fake DB routes by SQL substring and answers fixed rows, the
 * "model" is a lambda, Qdrant is a `com.sun.net.httpserver` on 127.0.0.1,
 * and the two subprocesses are throwaway `/bin/sh` scripts in a temp
 * directory that record what they were handed.
 */
class ResolverTest {

    // =========================================================== the fake library
    private fun row(
        id: Int, title: String, artist: String = "", album: String = "",
        path: String = "/home/user/Music/Library/$id.flac",
        genres: List<String> = emptyList(), styles: List<String> = emptyList(), moods: List<String> = emptyList(),
        cluster: Int? = null, durMs: Int = 200_000,
    ): Db.Row = Db.Row(mapOf(
        "id" to id, "title" to title, "artist" to artist, "album" to album, "dur_ms" to durMs, "path" to path,
        "genres" to genres, "styles" to styles, "moods" to moods, "dupe_cluster" to cluster))

    /** Routes by SQL substring the way `MusicTest`'s FakeDb does, one slot
     *  per `MusicDb` method the resolver calls, and records every call so a
     *  test can assert on the parameters a lane actually bound. */
    private class FakeDb : Db {
        val calls = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        var artist: List<Db.Row> = emptyList()
        var album: List<Db.Row> = emptyList()
        var playlist: List<Db.Row> = emptyList()
        var vocabRows: List<Db.Row> = emptyList()
        var searchRows: List<Db.Row> = emptyList()
        var randomRows: List<Db.Row> = emptyList()
        var planRows: List<Db.Row> = emptyList()
        var byIds: List<Db.Row> = emptyList()
        var clusters: List<Int> = emptyList()
        var vocabTerms: Set<String> = emptySet()
        var fieldTerms: List<String> = listOf("metal", "ambient")
        /** Set to make a query throw — the "the library is unreachable" path. */
        var fault: String? = null

        fun sqlFor(fragment: String): String? = calls.map { it.first }.firstOrNull { it.contains(fragment) }
        fun argsFor(fragment: String): List<Any?> = calls.first { it.first.contains(fragment) }.second

        override fun query(sql: String, vararg args: Any?): List<Db.Row> {
            calls.add(sql to args.toList())
            fault?.let { throw IllegalStateException(it) }
            return when {
                sql.contains("AS ok") -> listOf(Db.Row(mapOf("ok" to (args[0].toString() in vocabTerms))))
                sql.contains("ORDER BY n DESC, term LIMIT 50") -> fieldTerms.map { Db.Row(mapOf("term" to it)) }
                sql.contains("SELECT DISTINCT lower(vocals)") -> listOf(Db.Row(mapOf("v" to "clean")))
                sql.contains("SELECT DISTINCT dupe_cluster") -> clusters.map { Db.Row(mapOf("dupe_cluster" to it)) }
                sql.contains("WHERE lower(t.artist) = ?") -> artist
                sql.contains("WHERE lower(t.album) = ?") -> album
                sql.contains("FROM playlists p") -> playlist
                sql.contains("WHERE t.id = ANY(?)") -> byIds
                sql.contains("term ILIKE") -> vocabRows
                sql.contains("LIKE ?") -> searchRows
                sql.contains("ORDER BY random() LIMIT ?") && !sql.contains("WHERE") -> randomRows
                sql.contains("JOIN track_meta m ON m.track_id = t.id WHERE") -> planRows
                else -> throw AssertionError("unrouted SQL: $sql")
            }
        }

        override fun exec(sql: String, vararg args: Any?): Int { calls.add(sql to args.toList()); return 1 }
        override fun <T> tx(block: (Db) -> T): T = block(this)
    }

    private fun musicDb(f: FakeDb) = MusicDb(f, listOf("/home/user/Music"))

    /** Fixed seed: every shuffled lane's membership is asserted, and the two
     *  ordered lanes are asserted on order, so the seed only has to be stable. */
    private fun resolver(
        f: FakeDb, qdrant: Qdrant? = null, embed: ((String) -> List<Double>)? = null,
        llm: ((String, String) -> String)? = null, queueSize: Int = 25,
    ) = Resolver(musicDb(f), qdrant, embed, llm, queueSize, "opus", java.util.Random(42))

    // =========================================================== lane 1
    @Test
    fun laneOneRandomExcludesSoundEffectsAndSpokenWordAndCaps() {
        val f = FakeDb()
        f.randomRows = (1..40).map { i ->
            when {
                i == 1 -> row(i, "Wurm hit", styles = listOf("sound effect"))
                i == 2 -> row(i, "IT interlude", genres = listOf("spoken word"))
                else -> row(i, "T$i", artist = "A${i % 7}")
            }
        }
        val r = resolver(f, queueSize = 10)

        val q = r.ask("surprise me")
        assertEquals("random", q.lane)
        assertEquals("random mix", q.label)
        assertEquals(10, q.tracks.size)
        assertEquals("lane random: 10 tracks", q.detail)
        assertFalse(q.tracks.any { it.id == 1 || it.id == 2 }, "sfx and spoken word must not survive the random lane")
        // the over-fetch is cap*4
        assertEquals(listOf<Any?>(40), f.argsFor("ORDER BY random()"))

        // no content tokens left after the stopwords is ALSO the random lane
        assertEquals("random", r.ask("play something random").lane)
        assertEquals("random", r.ask("play me some good music please").lane)
        assertEquals("random", r.ask("").lane)
    }

    @Test
    fun laneOneArtistShufflesKeepsSpokenWordAndDedupesByCluster() {
        val f = FakeDb()
        f.artist = listOf(
            row(1, "One", "Pink Floyd", "Meddle"),
            row(2, "Interlude", "Pink Floyd", "Meddle", genres = listOf("spoken word")),
            // one dupe cluster, two fidelities: the FLAC has to win
            row(3, "Echoes", "Pink Floyd", "Meddle", path = "/home/user/Music/Library/echoes.mp3", cluster = 9),
            row(4, "Echoes", "Pink Floyd", "Meddle", path = "/home/user/Music/Library/echoes.flac", cluster = 9),
        )
        val q = resolver(f).ask("Pink Floyd")
        assertEquals("artist", q.lane)
        assertEquals("Pink Floyd", q.label)
        assertEquals(setOf(1, 2, 4), q.tracks.map { it.id }.toSet())
        assertEquals("lane artist \"Pink Floyd\": 4 in library → 3 queued", q.detail)
        // the lane binds the LOWER-CASED request
        assertEquals(listOf<Any?>("pink floyd"), f.argsFor("WHERE lower(t.artist) = ?"))
    }

    @Test
    fun laneOneAlbumKeepsItsOrderAndIsNotCapped() {
        val f = FakeDb()
        f.album = (1..30).map { row(it, "T$it", "A", "The Wall", path = "/m/%02d.flac".format(it)) }
        val q = resolver(f, queueSize = 25).ask("The Wall")
        assertEquals("album", q.lane)
        assertEquals((1..30).toList(), q.tracks.map { it.id }, "an album plays whole, in its own order")
        assertEquals("lane album \"The Wall\": 30 tracks in order", q.detail)
    }

    @Test
    fun laneOnePlaylistKeepsItsStoredOrderAndIsNotCapped() {
        val f = FakeDb()
        f.playlist = (1..28).map { row(it, "T$it", "A$it") }
        val q = resolver(f, queueSize = 25).ask("Chill")
        assertEquals("playlist", q.lane)
        assertEquals((1..28).toList(), q.tracks.map { it.id })
        assertEquals("lane playlist \"Chill\": 28 tracks in order", q.detail)
    }

    @Test
    fun laneOneVocabNeedsEveryTokenInTheVocabularyAndDropsSpokenWord() {
        val f = FakeDb()
        f.vocabTerms = setOf("hard", "metal")
        f.vocabRows = listOf(
            row(1, "Riff", "A", genres = listOf("metal")),
            row(2, "Talk", "B", styles = listOf("spoken word")),
            row(3, "Boom", "C", styles = listOf("sound effect")),
        )
        f.searchRows = listOf(row(99, "Fallback", "Z"))

        val q = resolver(f).ask("play some hard metal stuff")
        assertEquals("vocab", q.lane)
        assertEquals("hard metal", q.label)
        assertEquals(listOf(1), q.tracks.map { it.id })
        assertEquals("lane vocab [hard, metal]: 3 matched → 1 queued", q.detail)

        // one token outside the vocabulary and the whole lane steps aside
        val f2 = FakeDb()
        f2.vocabTerms = setOf("metal")
        f2.vocabRows = listOf(row(1, "Riff", "A"))
        f2.searchRows = listOf(row(99, "Fallback", "Z"))
        val q2 = resolver(f2).ask("wall metal")
        assertEquals("search", q2.lane)
        assertFalse(f2.calls.any { it.first.contains("term ILIKE") && !it.first.contains("AS ok") },
            "the vocab candidate query must not run when a token is outside the vocabulary")
        // the first miss ends the checking — 'metal' is never asked about
        assertEquals(1, f2.calls.count { it.first.contains("AS ok") })
    }

    @Test
    fun laneOneSearchKeepsSpokenWordAndSearchesTheContentTokensOnly() {
        val f = FakeDb()
        f.searchRows = listOf(
            row(1, "Wish You Were Here", "Pink Floyd"),
            row(2, "Radio chatter", "GTA", genres = listOf("spoken word")),
        )
        val q = resolver(f).ask("Play some wish you were here.")
        assertEquals("search", q.lane)
        assertEquals(setOf(1, 2), q.tracks.map { it.id }.toSet(), "an explicit ask keeps spoken word")
        assertEquals("lane search \"Play some wish you were here.\": 2 hits → 2 queued", q.detail)
        // trailing punctuation is off the token and the stopwords are gone;
        // the LIKE patterns are what the lane actually bound
        val args = f.argsFor("LIKE ?")
        assertEquals(listOf("%wish%", "%wish%", "%wish%", "%wish%", "%you%", "%you%", "%you%", "%you%",
            "%were%", "%were%", "%were%", "%were%", "%here%", "%here%", "%here%", "%here%", Resolver.SEARCH_LIMIT), args)
    }

    @Test
    fun laneOneSaysSoWhenEverythingMatchedIsExcludedContent() {
        val f = FakeDb()
        f.artist = listOf(
            row(1, "Hit", "Wurm", styles = listOf("sound effect")),
            row(2, "Thud", "Wurm", genres = listOf("sound effects")),
        )
        val q = resolver(f).ask("Wurm")
        assertEquals("artist", q.lane)
        assertTrue(q.tracks.isEmpty())
        assertEquals("lane artist \"Wurm\": 2 in library → 0 queued — all matches are excluded content (sound effects / spoken word)", q.detail)

        // naming them opts back in
        val q2 = resolver(f).ask("Wurm sound effects")
        assertEquals("artist", q2.lane)
        assertEquals(setOf(1, 2), q2.tracks.map { it.id }.toSet())
    }

    @Test
    fun aThrownQueryReachesTheCallerInsteadOfLookingLikeNoMatch() {
        val f = FakeDb()
        f.fault = "the library is unreachable"
        val e = assertFailsWith<IllegalStateException> { resolver(f).ask("anything") }
        assertEquals("the library is unreachable", e.message)
    }

    // =========================================================== lane 2
    /** Lane 1 finds nothing for this request, so lane 2 gets its turn. */
    private fun laneOneMisses(): FakeDb = FakeDb().apply { vocabTerms = emptySet() }

    @Test
    fun laneTwoRunsThePlanAndNamesTheModelInItsDetailLine() {
        val f = laneOneMisses()
        f.planRows = (1..5).map { row(it, "T$it", "A$it", genres = listOf("metal")) }
        val seen = ArrayList<Pair<String, String>>()
        val q = resolver(f, llm = { s, p -> seen.add(s to p); "```json\n{\"genres\":[\"Metal\"],\"energy\":7,\"order\":\"newest\",\"size\":3}\n```" })
            .ask("brutal riffage")

        assertEquals("llm", q.lane)
        assertEquals("brutal riffage", q.label)
        assertEquals(3, q.tracks.size)
        assertEquals("lane llm (opus): 5 matched → 3 queued", q.detail)

        // the plan reached the SQL normalized: lower-cased terms, energy
        // widened to ±2, the named order, and the candidate floor
        val psql = f.sqlFor("m.energy >= ?")!!
        assertTrue(psql.contains("t.indexed_at DESC"), psql)
        val pargs = f.argsFor("m.energy >= ?")
        assertEquals(listOf("metal"), (pargs[0] as Db.TextArr).v)
        assertEquals(5, pargs[1])
        assertEquals(9, pargs[2])
        assertEquals(100, pargs.last(), "a small plan still fetches the candidate floor")

        // the payload the model was handed
        assertEquals(1, seen.size)
        assertEquals(Resolver.PLAN_SYSTEM, seen[0].first)
        val payload = seen[0].second
        assertTrue(payload.contains("\"request\":\"brutal riffage\""), payload)
        assertTrue(payload.contains("\"genres\":[\"metal\",\"ambient\"]"), payload)
        assertTrue(payload.contains("\"vocals\":[\"clean\"]"), payload)
        assertTrue(payload.contains("\"orders\":[\"shuffle\",\"least_recent\",\"newest\"]"), payload)
        assertTrue(payload.contains("\"defaultSize\":25"), payload)
    }

    @Test
    fun laneTwoBlendsInEmbeddingNeighboursWhenItIsShortOfTheAskedSize() {
        val f = laneOneMisses()
        f.planRows = listOf(row(1, "One", "A", genres = listOf("metal")), row(2, "Two", "B", genres = listOf("metal")))
        // the neighbours the fill will draw on; 3 shares a cluster with 1
        f.byIds = listOf(row(7, "N7", "C"), row(8, "N8", "D"), row(9, "N9", "E", cluster = 4))
        f.clusters = listOf(4)          // the primary already owns cluster 4
        FakeQdrant(listOf(7, 9, 8)).use { qd ->
            val q = resolver(f, qdrant = qd.client(), embed = { listOf(0.1, 0.2) },
                llm = { _, _ -> "{\"genres\":[\"metal\"],\"size\":4}" }).ask("brutal riffage")
            assertEquals("llm", q.lane)
            assertEquals(setOf(1, 2), q.tracks.take(2).map { it.id }.toSet(), "the plan's own rows come first")
            assertEquals(listOf(7, 8), q.tracks.drop(2).map { it.id },
                "the neighbours fill in rank order, and 9 is dropped for the cluster the plan already owns")
            assertEquals("lane llm (opus): 2 matched → 4 queued (2 embedding-blended)", q.detail)
        }
    }

    @Test
    fun anyLaneTwoFailureFallsThroughToTheEmbeddingLane() {
        fun run(llm: (String, String) -> String): String {
            val f = laneOneMisses()
            f.planRows = listOf(row(1, "One", "A", genres = listOf("metal")))
            f.byIds = listOf(row(7, "N7", "C"))
            return FakeQdrant(listOf(7)).use { qd ->
                resolver(f, qdrant = qd.client(), embed = { listOf(0.1) }, llm = llm).ask("brutal riffage").lane
            }
        }
        assertEquals("embedding", run { _, _ -> "I could not think of anything." }, "non-JSON")
        assertEquals("embedding", run { _, _ -> throw IllegalStateException("claude one-shot exit 1: boom") }, "a throw")
        assertEquals("embedding", run { _, _ -> "{\"order\":\"newest\"}" }, "a plan with no filter in it")
        assertEquals("embedding", run { _, _ -> "[1,2,3]" }, "JSON that is not an object")
        assertEquals("embedding", run { _, _ -> "" }, "an empty answer")

        // a plan that matches nothing also steps aside
        val f = laneOneMisses()
        f.planRows = emptyList()
        f.byIds = listOf(row(7, "N7", "C"))
        FakeQdrant(listOf(7)).use { qd ->
            assertEquals("embedding", resolver(f, qdrant = qd.client(), embed = { listOf(0.1) },
                llm = { _, _ -> "{\"genres\":[\"metal\"]}" }).ask("brutal riffage").lane)
        }
    }

    @Test
    fun laneTwoIsSkippedWithNoModelWired() {
        val f = laneOneMisses()
        f.byIds = listOf(row(7, "N7", "C"))
        FakeQdrant(listOf(7)).use { qd ->
            assertEquals("embedding", resolver(f, qdrant = qd.client(), embed = { listOf(0.1) }).ask("brutal riffage").lane)
        }
        assertNull(f.sqlFor("ORDER BY n DESC, term LIMIT 50"), "no model wired means the vocabulary is never fetched")
    }

    // =========================================================== the plan parser
    @Test
    fun thePlanParserNormalizesAndRefusesWhatCannotFilter() {
        // a bare energy is a target, widened by ±2 and clamped to 1..10
        assertEquals(5 to 9, Resolver.parsePlan("{\"energy\":7}")!!.let { it.energyMin to it.energyMax })
        assertEquals(1 to 3, Resolver.parsePlan("{\"energy\":1}")!!.let { it.energyMin to it.energyMax })
        assertEquals(8 to 10, Resolver.parsePlan("{\"energy\":10}")!!.let { it.energyMin to it.energyMax })
        // an explicit range keeps its halves, either of which may be absent
        Resolver.parsePlan("{\"energy\":{\"min\":3}}")!!.let { assertEquals(3, it.energyMin); assertNull(it.energyMax) }
        Resolver.parsePlan("{\"bpm\":{\"min\":90,\"max\":140.5}}")!!.let { assertEquals(90.0, it.bpmMin); assertEquals(140.5, it.bpmMax) }
        // an empty range object is no filter at all
        assertNull(Resolver.parsePlan("{\"energy\":{}}"))

        // lists are lower-cased, trimmed, and emptied of blanks
        val p = Resolver.parsePlan("{\"genres\":[\" Heavy Metal \",\"\"],\"styles\":[\"  \"],\"moods\":[\"Angry\"]}")!!
        assertEquals(listOf("heavy metal"), p.genres)
        assertTrue(p.styles.isEmpty(), "a list of blanks is not a filter")
        assertEquals(listOf("angry"), p.moods)
        // a list holding anything but strings is discarded whole
        assertNull(Resolver.parsePlan("{\"genres\":[\"metal\",7]}"))
        assertNull(Resolver.parsePlan("{\"genres\":\"metal\"}"))

        // order: only the three the plan SQL knows; anything else is shuffle
        assertEquals("least_recent", Resolver.parsePlan("{\"genres\":[\"metal\"],\"order\":\"least_recent\"}")!!.order)
        assertEquals("shuffle", Resolver.parsePlan("{\"genres\":[\"metal\"],\"order\":\"sideways\"}")!!.order)
        assertEquals("shuffle", Resolver.parsePlan("{\"genres\":[\"metal\"]}")!!.order)

        // numbers cast at the boundary — a model may quote them
        assertEquals(10, Resolver.parsePlan("{\"genres\":[\"metal\"],\"size\":\"10\"}")!!.size)
        assertEquals(4, Resolver.parsePlan("{\"genres\":[\"metal\"],\"size\":4.9}")!!.size)
        assertNull(Resolver.parsePlan("{\"genres\":[\"metal\"],\"size\":\"lots\"}")!!.size)

        // a fenced block, with or without the language tag
        assertNotNull(Resolver.parsePlan("```json\n{\"genres\":[\"metal\"]}\n```"))
        assertNotNull(Resolver.parsePlan("```\n{\"genres\":[\"metal\"]}\n```"))
        assertNotNull(Resolver.parsePlan("  {\"artists\":[\"Tool\"]}  "))

        // nothing to filter on
        assertNull(Resolver.parsePlan("{}"))
        assertNull(Resolver.parsePlan("{\"order\":\"newest\",\"size\":9}"))
        assertNull(Resolver.parsePlan("{\"exclude\":[\"sound effect\"]}"), "exclude alone would select nearly everything")
        assertNull(Resolver.parsePlan("not json at all"))
        assertNull(Resolver.parsePlan("[1,2]"))
        assertNull(Resolver.parsePlan(""))

        // the size clamp the lane applies around it
        assertEquals(25, Resolver.clampSize(null, 25))
        assertEquals(1, Resolver.clampSize(0, 25))
        assertEquals(1, Resolver.clampSize(-5, 25))
        assertEquals(100, Resolver.clampSize(4_000, 25))
    }

    // =========================================================== lane 3
    @Test
    fun laneThreeKeepsTheCosineRankAndDropsWhatTheLibraryExcludes() {
        val f = laneOneMisses()
        // the library hands the rows back in id order; the RANK is Qdrant's
        f.byIds = listOf(
            row(5, "Five", "B"),
            row(7, "Seven", "A"),
            row(8, "Eight", "C", genres = listOf("spoken word")),
            row(9, "Nine", "D"),
        )
        val embedded = ArrayList<String>()
        FakeQdrant(listOf(7, 8, 9, 5, 404)).use { qd ->
            val q = resolver(f, qdrant = qd.client(), embed = { embedded.add(it); listOf(0.5, 0.25) }, queueSize = 3)
                .ask("rainy tuesday afternoon")
            assertEquals("embedding", q.lane)
            assertEquals("rainy tuesday afternoon", q.label)
            assertEquals(listOf(7, 9, 5), q.tracks.map { it.id },
                "rank order, spoken word out, an id the library no longer has skipped, capped")
            assertEquals("lane embedding: top-3 cosine neighbours (ranked)", q.detail)
            assertEquals(listOf("rainy tuesday afternoon"), embedded)
            // the neighbour floor: max(want*3, 50)
            assertEquals(50, qd.lastLimit)
            assertEquals(listOf(0.5, 0.25), qd.lastVector)
        }
    }

    @Test
    fun anEmbedderOrQdrantFailureIsALaneFailureNotAThrow() {
        val f = laneOneMisses()
        val q = resolver(f, qdrant = Qdrant("http://127.0.0.1:1", "g2cc_music"), embed = { throw IllegalStateException("embed_query exit 2: no model") })
            .ask("rainy tuesday afternoon")
        assertEquals("empty", q.lane)
        assertEquals("no library match for \"rainy tuesday afternoon\" (all lanes)", q.detail)

        // an empty vector is a failure too, never a silent zero-neighbour answer
        val q2 = resolver(f, qdrant = Qdrant("http://127.0.0.1:1", "g2cc_music"), embed = { emptyList() })
            .ask("rainy tuesday afternoon")
        assertEquals("empty", q2.lane)
    }

    @Test
    fun theChainEndsAtAnHonestEmptyWithNoLanesWired() {
        val f = laneOneMisses()
        val q = resolver(f).ask("something that is not here")
        assertEquals("empty", q.lane)
        assertTrue(q.tracks.isEmpty())
        assertEquals("something that is not here", q.label)
        assertEquals("no library match for \"something that is not here\" (all lanes)", q.detail)
    }

    // =========================================================== the CLI one-shot
    @Test
    fun theOneShotPassesTheVerifiedFlagsScrubsTheEnvironmentAndIsLoudOnFailure() {
        val tmp = Files.createTempDirectory("damage-music-oneshot")
        try {
            val out = tmp.resolve("out").also { Files.createDirectories(it) }
            val bin = script(tmp.resolve("claude"), recorder(out) + "cat \"\$D/stdin\"\n")

            val one = ClaudeOneShot(bin, model = "opus", effort = "low", cwd = tmp)
            val answer = one.run("SYSTEM TEXT", "{\"request\":\"hi\"}")
            assertEquals("{\"request\":\"hi\"}", answer, "stdout is the answer, verbatim")

            assertEquals(listOf("-p", "--tools", "", "--no-session-persistence",
                "--model", "opus", "--effort", "low", "--system-prompt", "SYSTEM TEXT"),
                Files.readAllLines(out.resolve("args")))
            assertEquals("{\"request\":\"hi\"}", Files.readString(out.resolve("stdin")), "the payload goes in on stdin")
            assertEquals(tmp.toRealPath().toString(), Files.readString(out.resolve("cwd")).trim())
            assertEquals("1", Files.readString(out.resolve("automem")))
            // non-vacuous whenever the suite itself runs under Claude Code
            assertEquals("", Files.readString(out.resolve("claudecode")), "CLAUDECODE must not reach the child")

            // --bare is available but off by default: it refuses an OAuth
            // login once ANTHROPIC_API_KEY is scrubbed (measured 2026-09-02)
            ClaudeOneShot(bin, model = "opus", effort = "low", cwd = tmp, bare = true).run("S", "P")
            assertEquals(listOf("-p", "--bare", "--tools", "", "--no-session-persistence",
                "--model", "opus", "--effort", "low", "--system-prompt", "S"),
                Files.readAllLines(out.resolve("args")))

            // the scrub list itself is the contract
            assertEquals(setOf("CLAUDECODE", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ID",
                "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_EXECPATH", "AI_AGENT", "CLAUDE_EFFORT",
                "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_API_KEY"), ClaudeOneShot.SCRUBBED_ENV)
            // and the removal works on a variable that certainly exists
            val echo = script(tmp.resolve("echoHome"), "printf '%s' \"\${HOME-}\"\n")
            assertEquals("", MusicProc.run(listOf(echo), tmp.toFile(), "", setOf("HOME"), tag = "t").stdout)
            assertTrue(MusicProc.run(listOf(echo), tmp.toFile(), "", emptySet(), tag = "t").stdout.isNotEmpty())

            // a non-zero exit carries the reason out
            val bad = script(tmp.resolve("badclaude"), "cat > /dev/null\necho 'model unavailable' >&2\nexit 1\n")
            val e = assertFailsWith<IllegalStateException> {
                ClaudeOneShot(bad, model = "opus", effort = "low", cwd = tmp).run("S", "P")
            }
            assertEquals("claude one-shot exit 1: model unavailable", e.message)

            // exit 0 with nothing to show for it is a failure too
            val silent = script(tmp.resolve("silentclaude"), "cat > /dev/null\nexit 0\n")
            val e2 = assertFailsWith<IllegalStateException> {
                ClaudeOneShot(silent, model = "opus", effort = "low", cwd = tmp).run("S", "P")
            }
            assertTrue(e2.message!!.startsWith("claude one-shot returned nothing (exit 0)"), e2.message!!)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // =========================================================== the embedder
    @Test
    fun theEmbedderRunsTheLibraryModuleAndRefusesAnythingThatIsNotAVector() {
        val tmp = Files.createTempDirectory("damage-music-embed")
        try {
            val out = tmp.resolve("out").also { Files.createDirectories(it) }
            val py = script(tmp.resolve("python"), recorder(out) + "printf '[0.1, -0.25, 3]'\n")

            assertEquals(listOf(0.1, -0.25, 3.0), EmbedQuery(py, tmp).embed("hard metal"))
            assertEquals(listOf("-m", "enrich.embed_query"), Files.readAllLines(out.resolve("args")))
            assertEquals("hard metal", Files.readString(out.resolve("stdin")))
            assertEquals(tmp.toRealPath().toString(), Files.readString(out.resolve("cwd")).trim(),
                "the module only resolves from the audio directory")

            // quoted numbers are cast at the boundary
            val quoted = script(tmp.resolve("py-quoted"), "cat > /dev/null\nprintf '[\"0.5\",\"0.5\"]'\n")
            assertEquals(listOf(0.5, 0.5), EmbedQuery(quoted, tmp).embed("x"))

            // every failure names itself
            val fails = script(tmp.resolve("py-fails"), "cat > /dev/null\necho 'ModuleNotFoundError: enrich' >&2\nexit 2\n")
            assertEquals("embed_query exit 2: ModuleNotFoundError: enrich",
                assertFailsWith<IllegalStateException> { EmbedQuery(fails, tmp).embed("x") }.message)

            val prose = script(tmp.resolve("py-prose"), "cat > /dev/null\nprintf 'loading model...'\n")
            assertTrue(assertFailsWith<IllegalStateException> { EmbedQuery(prose, tmp).embed("x") }
                .message!!.startsWith("embed_query output is not JSON"))

            val obj = script(tmp.resolve("py-obj"), "cat > /dev/null\nprintf '{\"v\":[1]}'\n")
            assertTrue(assertFailsWith<IllegalStateException> { EmbedQuery(obj, tmp).embed("x") }
                .message!!.startsWith("embed_query returned a non-vector"))

            val empty = script(tmp.resolve("py-empty"), "cat > /dev/null\nprintf '[]'\n")
            assertEquals("embed_query returned an empty vector",
                assertFailsWith<IllegalStateException> { EmbedQuery(empty, tmp).embed("x") }.message)

            val words = script(tmp.resolve("py-words"), "cat > /dev/null\nprintf '[\"nan-ish\"]'\n")
            assertTrue(assertFailsWith<IllegalStateException> { EmbedQuery(words, tmp).embed("x") }
                .message!!.startsWith("embed_query returned a non-number"))

            val quiet = script(tmp.resolve("py-quiet"), "cat > /dev/null\n")
            assertTrue(assertFailsWith<IllegalStateException> { EmbedQuery(quiet, tmp).embed("x") }
                .message!!.startsWith("embed_query returned nothing"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theSubprocessRunnerDoesNotStallOnAChildThatFillsItsPipes() {
        val tmp = Files.createTempDirectory("damage-music-proc")
        try {
            // ~256 KiB on each stream, far past the 64 KiB pipe buffer, while
            // a large payload goes in the other way
            val noisy = script(tmp.resolve("noisy"), """
                i=0
                while [ ${'$'}i -lt 4096 ]; do
                  echo 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy'
                  echo 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee' >&2
                  i=${'$'}((i + 1))
                done
                cat > /dev/null
            """.trimIndent() + "\n")
            val payload = "p".repeat(300_000)
            val r = MusicProc.run(listOf(noisy), tmp.toFile(), payload, tag = "noisy")
            assertEquals(0, r.code)
            assertEquals(4096, r.stdout.trim().lines().size)
            assertTrue(r.stderr.isNotEmpty())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // =========================================================== helpers
    /** A shell script that records its args, its cwd, two environment
     *  variables and its stdin under [out]. */
    private fun recorder(out: Path): String =
        "D='" + out.toString() + "'\n" +
            ": > \"\$D/args\"\n" +
            "for a in \"\$@\"; do printf '%s\\n' \"\$a\" >> \"\$D/args\"; done\n" +
            "printf '%s' \"\${CLAUDECODE-}\" > \"\$D/claudecode\"\n" +
            "printf '%s' \"\${CLAUDE_CODE_DISABLE_AUTO_MEMORY-}\" > \"\$D/automem\"\n" +
            "pwd > \"\$D/cwd\"\n" +
            "cat > \"\$D/stdin\"\n"

    private fun script(path: Path, body: String): String {
        Files.write(path, ("#!/bin/sh\n" + body).toByteArray())
        assertTrue(path.toFile().setExecutable(true))
        return path.toString()
    }

    /** Qdrant's `/points/search` over loopback: a fixed ranked id list, with
     *  the vector and limit it was actually asked for recorded. */
    private class FakeQdrant(private val ranked: List<Int>) : AutoCloseable {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        @Volatile var lastLimit: Int = -1
        @Volatile var lastVector: List<Double> = emptyList()

        init {
            server.createContext("/") { x: HttpExchange ->
                val body = x.requestBody.readBytes().toString(Charsets.UTF_8)
                lastLimit = Regex("\"limit\":(\\d+)").find(body)!!.groupValues[1].toInt()
                lastVector = Regex("\"vector\":\\[([^]]*)]").find(body)!!.groupValues[1]
                    .split(",").filter { it.isNotBlank() }.map { it.trim().toDouble() }
                val json = ranked.joinToString(",", "{\"result\":[", "]}") { "{\"id\":$it,\"payload\":{\"track_id\":$it}}" }
                val b = json.toByteArray()
                x.responseHeaders.add("Content-Type", "application/json")
                x.sendResponseHeaders(200, b.size.toLong())
                x.responseBody.use { it.write(b) }
            }
            server.start()
        }

        fun client() = Qdrant("http://127.0.0.1:${server.address.port}", "g2cc_music")
        override fun close() { server.stop(0) }
    }
}
