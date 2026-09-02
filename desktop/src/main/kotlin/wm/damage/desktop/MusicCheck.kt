package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import wm.damage.core.windows.music.AudioProfile
import wm.damage.core.windows.music.MusicDb
import wm.damage.core.windows.music.Rules

/**
 * `--music-check` (`MUSIC.md` §10): a read-only pass (bar the additive schema
 * migration the service applies at start) against the real Postgres / Qdrant / cache — counts, the catalog build, a sample query per
 * lane that exists, the cache-key mapping for 20 random tracks against the
 * legacy cache, one art extraction. The `--epub-check` shape; not part of
 * `:core:test`. Exit 1 on any failure.
 */
object MusicCheck {
    private val failures = ArrayList<String>()

    private fun check(what: String, ok: Boolean) {
        println("  ${if (ok) "PASS" else "FAIL"}  $what")
        if (!ok) failures.add(what)
    }

    fun run(cfg: Config): Nothing {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lib = cfg.musicLibrary(scope)
            val db = lib.db
            val t0 = System.currentTimeMillis()
            val counts = db.counts()
            println("postgres ${cfg.musicDb} over ${cfg.musicSocketDir}: " + counts.entries.joinToString(" · ") { "${it.key} ${it.value}" })
            check("tracks table has rows", (counts["tracks"] ?: 0) > 0)
            // the ONE write this pass makes: the additive schema migration the
            // service applies at every start anyway (a column + an index on
            // `lyrics`), recorded once in damage_schema — said here, never hidden
            db.migrate()
            println("  additive migrations applied/recorded: ${MusicDb.MIGRATIONS.joinToString { it.first }}")
            val v = db.catalogVersion()
            val t1 = System.currentTimeMillis()
            val cat = db.catalog(v)
            val t2 = System.currentTimeMillis()
            val blob = cat.encode()
            println("catalog $v: ${cat.tracks.size} tracks · ${cat.artists.size} artists · ${cat.albums.size} albums · " +
                "${cat.playlists.size} playlists · ${cat.vocab.size} vocab terms · ${cat.recent.size} recent · " +
                "${blob.size / 1024} KB JSON · version ${t1 - t0} ms · build ${t2 - t1} ms")
            check("catalog builds", cat.tracks.isNotEmpty())
            check("catalog round-trips its JSON", wm.damage.core.windows.music.Catalog.decode(blob).tracks.size == cat.tracks.size)
            check("every track has a title", cat.tracks.all { it.title.isNotEmpty() })
            val withLyrics = cat.tracks.count { it.hasLyrics }
            println("  $withLyrics tracks carry lyrics · ${cat.tracks.count { it.hasArt }} likely have art · ${cat.tracks.count { it.folder.isEmpty() }} at the root")
            // a sample search
            val q = cat.tracks.firstOrNull { it.artist.isNotEmpty() }?.artist?.split(' ')?.first() ?: "a"
            val hits = db.search(q)
            println("  search \"$q\": ${hits.size} hits (${hits.take(3).joinToString(" · ") { Rules.Cand::title.get(it) }})")
            check("search answers", hits.isNotEmpty())
            // lane-1 style random with the rules
            val rnd = lib.randomLibrary(25)
            println("  library random: ${rnd.size} tracks (${rnd.take(3).joinToString(" · ") { it.title }})")
            check("library random answers with the rules applied", rnd.isNotEmpty())
            // vocab lane check
            val term = cat.vocab.firstOrNull { it.kind == "genre" }?.term
            if (term != null) {
                val byTerm = db.candsByTerm(term)
                println("  moods & genres \"$term\": ${byTerm.size} tracks")
                check("a vocab term lists its tracks", byTerm.isNotEmpty())
            }
            // playlists
            val pls = db.playlists()
            println("  playlists: ${pls.size} (${pls.count { it.adaptive }} adaptive) — " + pls.take(3).joinToString(" · ") { "${it.name} (${it.count})" })
            if (pls.isNotEmpty()) check("a playlist lists its tracks", db.playlistTracks(pls.first().id).size == pls.first().count)
            // cache-key mapping for 20 random tracks against the legacy cache
            val files = db.trackFiles()
            val sample = files.shuffled().take(20)
            var legacyHits = 0
            for (t in sample) if (lib.cache.isCached(t, AudioProfile.LEGACY)) legacyHits++
            println("  legacy cache (${cfg.musicLegacyCache}): $legacyHits of ${sample.size} sampled tracks map to an existing file")
            check("the legacy cache key mapping holds for the sample", legacyHits >= sample.size * 3 / 4)
            check("the default profile's directory is ours", lib.cache.dirFor(AudioProfile.DEFAULT).startsWith(Path.of(cfg.musicCache)))
            // one art extraction (writes only into our own art cache dir)
            val artTrack = cat.tracks.firstOrNull { it.hasArt } ?: cat.tracks.first()
            val ta = System.currentTimeMillis()
            val art = try { lib.art(artTrack.id, 56) } catch (e: Exception) { println("  art: ${e.message}"); null }
            println("  art for #${artTrack.id} \"${artTrack.title}\": ${if (art == null) "none" else "${art.size} B packed"} in ${System.currentTimeMillis() - ta} ms")
            // lyrics from the table only (no fetch chain in this pass)
            val lyTrack = cat.tracks.firstOrNull { it.hasLyrics }
            if (lyTrack != null) {
                val ly = db.lyrics(db.trackFile(lyTrack.id)!!)
                println("  lyrics for #${lyTrack.id} \"${lyTrack.title}\": ${if (ly == null) "none" else if (ly.synced != null) "synced ${ly.synced!!.lines().size} lines" else "plain"}")
                check("a track flagged hasLyrics reads its lyrics", ly != null && ly.found)
            }
            // Qdrant
            try {
                val qd = wm.damage.core.windows.music.Qdrant(cfg.musicQdrant, cfg.musicQdrantCollection)
                val (points, dim) = qd.info()
                println("  qdrant ${cfg.musicQdrantCollection}: $points points · $dim-dim")
                check("qdrant collection is populated with 384-dim vectors", points > 0 && dim == 384)
                val seed = cat.recent.firstOrNull() ?: cat.tracks.first().id
                val sim = lib.similar(listOf(seed), emptyList(), 5)
                println("  radio from #$seed: ${sim.joinToString(" · ") { it.title }}")
                check("recommend answers for a seed", sim.isNotEmpty())
            } catch (e: Exception) {
                check("qdrant reachable (${e.message})", false)
            }
            println("  media endpoint would bind :${cfg.mediaPort}; viz dir ${lib.vizDir} holds ${Files.list(lib.vizDir).use { it.count() }} blobs")
        } catch (e: Throwable) {
            e.printStackTrace()
            failures.add("music-check crashed: $e")
        } finally {
            scope.cancel()
        }
        println()
        if (failures.isEmpty()) { println("music-check: ALL CHECKS PASS"); kotlin.system.exitProcess(0) }
        println("music-check: ${failures.size} FAILURE(S):")
        for (f in failures) println("  - $f")
        kotlin.system.exitProcess(1)
    }
}
