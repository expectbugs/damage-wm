package wm.damage.core.windows.music

import java.security.MessageDigest
import wm.damage.core.util.Log

/**
 * Every query the music system runs against Postgres `g2cc` (`MUSIC.md`
 * §9.1 — tables verified 2026-09-02: tracks · track_meta · playlists ·
 * playlist_tracks · lyrics · play_history · player_state). Damage is the
 * only writer from now on; migrations are ADDITIVE only and recorded in
 * `damage_schema`. The SQL here re-states the G2CC facts in our own words
 * (`music.ts`, `resolver.ts`, `playlists.ts`, `lyrics.ts` read for
 * semantics, never pasted).
 */
class MusicDb(private val db: Db, private val libraryDirs: List<String>) {

    /** A track's file facts — the cache key and the transcoder's input. */
    class TrackFile(val id: Int, val path: String, val mtimeMs: Long, val title: String, val artist: String,
        val album: String, val durMs: Int) {
        val ext: String get() = path.substringAfterLast('.', "").lowercase()
    }

    // ------------------------------------------------------------------ migrations
    /** Additive, idempotent, recorded. Safe next to G2CC's still-running
     *  server (its own migration runner only adds tables/columns). */
    fun migrate() {
        db.exec("CREATE TABLE IF NOT EXISTS damage_schema (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())")
        val applied = db.query("SELECT name FROM damage_schema").map { it.str("name") }.toHashSet()
        for ((name, sql) in MIGRATIONS) {
            if (name in applied) continue
            db.tx { t ->
                for (stmt in sql) t.exec(stmt)
                t.exec("INSERT INTO damage_schema (name) VALUES (?)", name)
            }
            Log.i("music-db", "migration '$name' applied")
        }
    }

    // ------------------------------------------------------------------ the catalog
    /** A cheap fingerprint of everything the catalog is built from: a change
     *  anywhere bumps it; the phone's version cursor compares strings. */
    fun catalogVersion(): String {
        val r = db.query(
            """SELECT (SELECT count(*) FROM tracks) AS n,
                      (SELECT coalesce(max(extract(epoch FROM indexed_at)),0)::bigint FROM tracks) AS ti,
                      (SELECT coalesce(max(extract(epoch FROM updated_at)),0)::bigint FROM track_meta) AS tm,
                      (SELECT count(*) FROM track_meta) AS nm,
                      (SELECT coalesce(max(extract(epoch FROM updated_at)),0)::bigint FROM playlists) AS tp,
                      (SELECT count(*) FROM playlists) AS np,
                      (SELECT count(*) FROM playlist_tracks) AS npt,
                      (SELECT coalesce(max(extract(epoch FROM fetched_at)),0)::bigint FROM lyrics) AS tl,
                      (SELECT count(*) FROM lyrics WHERE found) AS nl,
                      (SELECT coalesce(max(id),0) FROM play_history) AS ph""").first()
        val s = listOf("n", "ti", "tm", "nm", "tp", "np", "npt", "tl", "nl", "ph").joinToString("|") { r.str(it) }
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return d.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun folderOf(path: String): String {
        val dir = path.substringBeforeLast('/', "")
        for (root in libraryDirs) {
            val r = root.trimEnd('/')
            if (dir == r) return ""
            if (dir.startsWith("$r/")) return dir.removePrefix("$r/")
        }
        return dir
    }

    /** The whole catalog (~3 k rows, one query + four small ones). */
    fun catalog(version: String = catalogVersion(), hasArt: (Int, String) -> Boolean = { _, _ -> false }): Catalog {
        val rows = db.query(
            """SELECT t.id, t.path, t.title, coalesce(t.artist,'') AS artist, coalesce(t.album,'') AS album,
                      coalesce(t.dur_ms,0) AS dur_ms, coalesce(t.track_no,0) AS track_no, coalesce(t.disc_no,0) AS disc_no,
                      extract(epoch FROM t.indexed_at)::bigint AS added,
                      m.genres, m.styles, m.moods, coalesce(m.energy,0) AS energy, coalesce(m.year,0) AS year,
                      coalesce(m.vocals,'') AS vocals, coalesce(m.dupe_cluster,0) AS dupe_cluster,
                      EXISTS (SELECT 1 FROM lyrics l WHERE l.found AND (l.track_id = t.id OR
                              (lower(l.artist) = lower(coalesce(t.artist,'')) AND lower(l.track) = lower(t.title)
                               AND l.duration_s = round(coalesce(t.dur_ms,0) / 1000.0)::int))) AS has_lyrics
               FROM tracks t LEFT JOIN track_meta m ON m.track_id = t.id
               ORDER BY t.id""")
        val tracks = rows.map { r ->
            val path = r.str("path")
            TrackMeta(
                id = r.int("id"), title = r.str("title"), artist = r.str("artist"), album = r.str("album"),
                durMs = r.int("dur_ms"), trackNo = r.int("track_no"), discNo = r.int("disc_no"), year = r.int("year"),
                genres = r.list("genres"), moods = r.list("moods"), styles = r.list("styles"),
                energy = r.int("energy"), vocals = r.str("vocals"), hasLyrics = r.bool("has_lyrics"),
                hasArt = hasArt(r.int("id"), path), dupeCluster = r.int("dupe_cluster"),
                folder = folderOf(path), addedAt = r.long("added"), ext = path.substringAfterLast('.', "").lowercase(),
            )
        }
        // artists + albums group CASE-INSENSITIVELY (the library has real
        // case-duplicates); the display name is the most common spelling
        fun canon(names: List<String>): String = names.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: names.first()
        val artists = tracks.filter { it.artist.isNotEmpty() }.groupBy { it.artist.lowercase() }.map { (_, ts) ->
            Artist(canon(ts.map { it.artist }), ts.size, ts.map { it.album.lowercase() }.filter { it.isNotEmpty() }.toSet().size)
        }.sortedBy { it.name.lowercase() }
        val albums = tracks.filter { it.album.isNotEmpty() }.groupBy { it.album.lowercase() }.map { (_, ts) ->
            val artistNames = ts.map { it.artist }.filter { it.isNotEmpty() }
            val artist = if (artistNames.isEmpty()) "" else {
                val distinct = artistNames.map { it.lowercase() }.toSet()
                if (distinct.size == 1) canon(artistNames) else "Various"
            }
            Album(canon(ts.map { it.album }), artist, ts.size, ts.map { it.year }.filter { it > 0 }.minOrNull() ?: 0)
        }.sortedBy { it.name.lowercase() }
        return Catalog(
            version = version, generatedAt = System.currentTimeMillis(),
            tracks = tracks, artists = artists, albums = albums,
            playlists = playlists(), vocab = vocab(), recent = recentIds(100),
        )
    }

    fun vocab(): List<VocabTerm> {
        val out = ArrayList<VocabTerm>()
        for ((col, kind) in listOf("genres" to "genre", "moods" to "mood", "styles" to "style")) {
            for (r in db.query("SELECT term, count(*) AS n FROM (SELECT unnest(coalesce($col,'{}')) AS term FROM track_meta) x GROUP BY 1 ORDER BY n DESC, term")) {
                out.add(VocabTerm(r.str("term"), kind, r.int("n")))
            }
        }
        return out
    }

    /** Distinct track ids from play_history, newest first. */
    fun recentIds(limit: Int): List<Int> {
        val seen = LinkedHashSet<Int>()
        for (r in db.query("SELECT track_id FROM play_history ORDER BY id DESC LIMIT ?", limit * 4)) {
            seen.add(r.int("track_id"))
            if (seen.size >= limit) break
        }
        return seen.toList()
    }

    /** The per-field vocabulary the LLM lane is handed (top terms by count)
     *  plus the LIVE vocals values. */
    fun vocabFields(): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        for (col in listOf("genres", "styles", "moods")) {
            out[col] = db.query("SELECT term FROM (SELECT unnest(coalesce($col,'{}')) AS term, count(*) AS n FROM track_meta GROUP BY 1) x ORDER BY n DESC, term LIMIT 50")
                .map { it.str("term") }
        }
        out["vocals"] = db.query("SELECT DISTINCT lower(vocals) AS v FROM track_meta WHERE vocals IS NOT NULL ORDER BY 1").map { it.str("v") }
        return out
    }

    // ------------------------------------------------------------------ tracks
    fun trackFile(id: Int): TrackFile? = db.query(
        "SELECT id, path, mtime_ms, title, coalesce(artist,'') AS artist, coalesce(album,'') AS album, coalesce(dur_ms,0) AS dur_ms FROM tracks WHERE id = ?", id)
        .firstOrNull()?.let { TrackFile(it.int("id"), it.str("path"), it.long("mtime_ms"), it.str("title"), it.str("artist"), it.str("album"), it.int("dur_ms")) }

    fun trackFiles(): List<TrackFile> = db.query(
        "SELECT id, path, mtime_ms, title, coalesce(artist,'') AS artist, coalesce(album,'') AS album, coalesce(dur_ms,0) AS dur_ms FROM tracks ORDER BY id")
        .map { TrackFile(it.int("id"), it.str("path"), it.long("mtime_ms"), it.str("title"), it.str("artist"), it.str("album"), it.int("dur_ms")) }

    fun trackByPath(path: String): TrackFile? = db.query(
        "SELECT id, path, mtime_ms, title, coalesce(artist,'') AS artist, coalesce(album,'') AS album, coalesce(dur_ms,0) AS dur_ms FROM tracks WHERE path = ?", path)
        .firstOrNull()?.let { TrackFile(it.int("id"), it.str("path"), it.long("mtime_ms"), it.str("title"), it.str("artist"), it.str("album"), it.int("dur_ms")) }

    private val CAND_COLS = "t.id, t.path, t.title, coalesce(t.artist,'') AS artist, coalesce(t.album,'') AS album, coalesce(t.dur_ms,0) AS dur_ms, m.genres, m.styles, m.moods, m.dupe_cluster"

    private fun cand(r: Db.Row) = Rules.Cand(
        r.int("id"), r.str("title"), r.str("artist"), r.str("album"), r.int("dur_ms"), r.str("path"),
        r.list("genres"), r.list("styles"), r.list("moods"), r.raw("dupe_cluster")?.let { r.int("dupe_cluster") },
    )

    /** Tokenized search: every token must match artist, album, title or path;
     *  ordered artist → album → path so results group into play order. */
    fun search(q: String, limit: Int = 200): List<Rules.Cand> {
        val tokens = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val conds = tokens.joinToString(" AND ") {
            "(lower(coalesce(t.artist,'')) LIKE ? OR lower(coalesce(t.album,'')) LIKE ? OR lower(t.title) LIKE ? OR lower(t.path) LIKE ?)"
        }
        val args = ArrayList<Any?>()
        for (t in tokens) { val p = "%${Rules.escapeLike(t)}%"; repeat(4) { args.add(p) } }
        args.add(limit)
        return db.query("SELECT $CAND_COLS FROM tracks t LEFT JOIN track_meta m ON m.track_id = t.id WHERE $conds ORDER BY t.artist NULLS LAST, t.album NULLS LAST, t.path LIMIT ?", *args.toTypedArray())
            .map(::cand)
    }

    fun randomCands(limit: Int): List<Rules.Cand> =
        db.query("SELECT $CAND_COLS FROM tracks t LEFT JOIN track_meta m ON m.track_id = t.id ORDER BY random() LIMIT ?", limit).map(::cand)

    fun candsByArtist(artistLc: String): List<Rules.Cand> =
        db.query("SELECT $CAND_COLS FROM tracks t LEFT JOIN track_meta m ON m.track_id = t.id WHERE lower(t.artist) = ? ORDER BY t.album NULLS LAST, t.path", artistLc).map(::cand)

    fun candsByAlbum(albumLc: String): List<Rules.Cand> =
        db.query("SELECT $CAND_COLS FROM tracks t LEFT JOIN track_meta m ON m.track_id = t.id WHERE lower(t.album) = ? ORDER BY t.path", albumLc).map(::cand)

    fun candsByPlaylistName(nameLc: String): List<Rules.Cand> =
        db.query("""SELECT $CAND_COLS FROM playlists p JOIN playlist_tracks pt ON pt.playlist_id = p.id
                    JOIN tracks t ON t.id = pt.track_id LEFT JOIN track_meta m ON m.track_id = t.id
                    WHERE lower(p.name) = ? ORDER BY pt.position""", nameLc).map(::cand)

    fun candsByIds(ids: List<Int>): List<Rules.Cand> {
        if (ids.isEmpty()) return emptyList()
        return db.query("SELECT $CAND_COLS FROM tracks t LEFT JOIN track_meta m ON m.track_id = t.id WHERE t.id = ANY(?)", Db.IntArr(ids)).map(::cand)
    }

    /** Does [term] occur anywhere in the genre/style/mood vocabulary? */
    fun vocabHas(term: String): Boolean = db.query(
        """SELECT EXISTS (SELECT 1 FROM track_meta m2, unnest(coalesce(m2.genres,'{}') || coalesce(m2.styles,'{}') || coalesce(m2.moods,'{}')) term
           WHERE term ILIKE '%' || ? || '%') AS ok""", Rules.escapeLike(term)).first().bool("ok")

    /** Tracks matching ALL [terms] across the union of the three tag columns. */
    fun candsByVocab(terms: List<String>, limit: Int = 800): List<Rules.Cand> {
        if (terms.isEmpty()) return emptyList()
        val conds = terms.joinToString(" AND ") {
            "EXISTS (SELECT 1 FROM unnest(coalesce(m.genres,'{}') || coalesce(m.styles,'{}') || coalesce(m.moods,'{}')) term WHERE term ILIKE '%' || ? || '%')"
        }
        val args = terms.map { Rules.escapeLike(it) } + limit
        return db.query("SELECT $CAND_COLS FROM tracks t JOIN track_meta m ON m.track_id = t.id WHERE $conds LIMIT ?", *args.toTypedArray()).map(::cand)
    }

    /** Tracks carrying ANY of [terms] exactly (the Moods & genres browse). */
    fun candsByTerm(term: String): List<Rules.Cand> = db.query(
        """SELECT $CAND_COLS FROM tracks t JOIN track_meta m ON m.track_id = t.id
           WHERE EXISTS (SELECT 1 FROM unnest(coalesce(m.genres,'{}') || coalesce(m.styles,'{}') || coalesce(m.moods,'{}')) x WHERE lower(x) = ?)
           ORDER BY t.artist NULLS LAST, t.album NULLS LAST, t.path""", term.lowercase()).map(::cand)

    /** The LLM plan (§9.3 lane 2) as SQL: each provided list must match (AND
     *  across lists, OR within, over the union of the three tag columns);
     *  energy/bpm ranges; vocals/artists exact. */
    class Plan(
        val genres: List<String> = emptyList(), val styles: List<String> = emptyList(), val moods: List<String> = emptyList(),
        val energyMin: Int? = null, val energyMax: Int? = null, val bpmMin: Double? = null, val bpmMax: Double? = null,
        val vocals: List<String> = emptyList(), val artists: List<String> = emptyList(), val exclude: List<String> = emptyList(),
        val order: String = "shuffle", val size: Int? = null,
    ) {
        val hasFilter: Boolean get() = genres.isNotEmpty() || styles.isNotEmpty() || moods.isNotEmpty() ||
            energyMin != null || energyMax != null || bpmMin != null || bpmMax != null || vocals.isNotEmpty() || artists.isNotEmpty()
    }

    fun planCands(p: Plan, limit: Int): List<Rules.Cand> {
        val conds = ArrayList<String>()
        val args = ArrayList<Any?>()
        fun overlap(terms: List<String>): String {
            args.add(Db.TextArr(terms.map { it.lowercase() }))
            return "EXISTS (SELECT 1 FROM unnest(coalesce(m.genres,'{}') || coalesce(m.styles,'{}') || coalesce(m.moods,'{}')) term WHERE lower(term) = ANY(?))"
        }
        if (p.genres.isNotEmpty()) conds.add(overlap(p.genres))
        if (p.styles.isNotEmpty()) conds.add(overlap(p.styles))
        if (p.moods.isNotEmpty()) conds.add(overlap(p.moods))
        if (p.exclude.isNotEmpty()) conds.add("NOT " + overlap(p.exclude))
        p.energyMin?.let { conds.add("m.energy >= ?"); args.add(it) }
        p.energyMax?.let { conds.add("m.energy <= ?"); args.add(it) }
        p.bpmMin?.let { conds.add("m.bpm >= ?"); args.add(it) }
        p.bpmMax?.let { conds.add("m.bpm <= ?"); args.add(it) }
        if (p.vocals.isNotEmpty()) { conds.add("lower(coalesce(m.vocals,'')) = ANY(?)"); args.add(Db.TextArr(p.vocals.map { it.lowercase() })) }
        if (p.artists.isNotEmpty()) { conds.add("lower(coalesce(t.artist,'')) = ANY(?)"); args.add(Db.TextArr(p.artists.map { it.lowercase() })) }
        if (conds.isEmpty()) return emptyList()
        val order = when (p.order) {
            "least_recent" -> "(SELECT max(ph.started_at) FROM play_history ph WHERE ph.track_id = t.id) NULLS FIRST"
            "newest" -> "t.indexed_at DESC"
            else -> "random()"
        }
        args.add(limit)
        return db.query("SELECT $CAND_COLS FROM tracks t JOIN track_meta m ON m.track_id = t.id WHERE ${conds.joinToString(" AND ")} ORDER BY $order LIMIT ?", *args.toTypedArray()).map(::cand)
    }

    /** Dupe clusters of [ids] (for the radio exclusion set). */
    fun clustersOf(ids: List<Int>): Set<Int> {
        if (ids.isEmpty()) return emptySet()
        return db.query("SELECT DISTINCT dupe_cluster FROM track_meta WHERE track_id = ANY(?) AND dupe_cluster IS NOT NULL", Db.IntArr(ids))
            .map { it.int("dupe_cluster") }.toSet()
    }

    // ------------------------------------------------------------------ playlists
    fun playlists(): List<Playlist> = db.query(
        """SELECT p.id, p.name, p.origin, (p.rule IS NOT NULL) AS adaptive, count(pt.track_id) AS n,
                  extract(epoch FROM p.updated_at)::bigint AS updated
           FROM playlists p LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
           GROUP BY p.id ORDER BY p.updated_at DESC""").map {
        Playlist(it.int("id"), it.str("name"), it.str("origin"), it.bool("adaptive"), it.int("n"), it.long("updated"))
    }

    fun playlist(id: Int): Playlist? = playlists().firstOrNull { it.id == id }

    fun playlistTracks(id: Int): List<TrackRef> = db.query(
        """SELECT t.id, t.title, coalesce(t.artist,'') AS artist, coalesce(t.album,'') AS album, coalesce(t.dur_ms,0) AS dur_ms
           FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id WHERE pt.playlist_id = ? ORDER BY pt.position""", id)
        .map { TrackRef(it.int("id"), it.str("title"), it.str("artist"), it.str("album"), it.int("dur_ms")) }

    /** Create, or REPLACE by name when [overwrite] (the window asks twice
     *  first). An adaptive playlist's name is refused — its rule would blow
     *  the snapshot away on the next refresh. */
    fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean, origin: String = "manual", request: String? = null): Playlist {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "playlist name is empty" }
        require(trackIds.isNotEmpty()) { "refusing to save an empty playlist" }
        val id = db.tx { t ->
            val existing = t.query("SELECT id, (rule IS NOT NULL) AS adaptive FROM playlists WHERE lower(name) = lower(?) FOR UPDATE", trimmed).firstOrNull()
            val pid: Int
            if (existing != null) {
                if (existing.bool("adaptive")) throw IllegalStateException("\"$trimmed\" is an adaptive playlist (rule-managed) — pick another name")
                if (!overwrite) throw IllegalStateException("a playlist named \"$trimmed\" exists")
                pid = existing.int("id")
                t.exec("UPDATE playlists SET origin = ?, request = ?, updated_at = now() WHERE id = ?", origin, request, pid)
                t.exec("DELETE FROM playlist_tracks WHERE playlist_id = ?", pid)
            } else {
                pid = t.query("INSERT INTO playlists (name, origin, request) VALUES (?, ?, ?) RETURNING id", trimmed, origin, request).first().int("id")
            }
            trackIds.forEachIndexed { i, tid -> t.exec("INSERT INTO playlist_tracks (playlist_id, position, track_id) VALUES (?, ?, ?)", pid, i, tid) }
            pid
        }
        Log.i("music-db", "playlist \"$trimmed\" ${if (overwrite) "saved" else "created"} (id $id, ${trackIds.size} tracks)")
        return playlist(id) ?: Playlist(id, trimmed, origin, false, trackIds.size)
    }

    fun renamePlaylist(id: Int, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "playlist name is empty" }
        val clash = db.query("SELECT id FROM playlists WHERE lower(name) = lower(?) AND id <> ?", trimmed, id)
        if (clash.isNotEmpty()) throw IllegalStateException("a playlist named \"$trimmed\" exists")
        val n = db.exec("UPDATE playlists SET name = ?, updated_at = now() WHERE id = ?", trimmed, id)
        if (n == 0) throw IllegalStateException("playlist $id is gone")
    }

    fun deletePlaylist(id: Int) {
        val n = db.exec("DELETE FROM playlists WHERE id = ?", id)
        if (n == 0) throw IllegalStateException("playlist $id is gone")
    }

    fun setPlaylistTracks(id: Int, trackIds: List<Int>) {
        db.tx { t ->
            val row = t.query("SELECT (rule IS NOT NULL) AS adaptive FROM playlists WHERE id = ? FOR UPDATE", id).firstOrNull()
                ?: throw IllegalStateException("playlist $id is gone")
            if (row.bool("adaptive")) throw IllegalStateException("adaptive playlist — its rule manages the rows")
            t.exec("DELETE FROM playlist_tracks WHERE playlist_id = ?", id)
            trackIds.forEachIndexed { i, tid -> t.exec("INSERT INTO playlist_tracks (playlist_id, position, track_id) VALUES (?, ?, ?)", id, i, tid) }
            t.exec("UPDATE playlists SET updated_at = now() WHERE id = ?", id)
        }
    }

    // ------------------------------------------------------------------ lyrics
    private fun durKey(durMs: Int): Int = if (durMs <= 0) 0 else Math.round(durMs / 1000.0).toInt()

    /** The table's row for a track: by the additive `track_id` link first,
     *  then by the legacy (artist, title, duration) key. */
    fun lyrics(t: TrackFile): Lyrics? {
        val byId = db.query("SELECT synced, plain, found, coalesce(source,'') AS source FROM lyrics WHERE track_id = ? ORDER BY fetched_at DESC LIMIT 1", t.id).firstOrNull()
        val r = byId ?: db.query(
            "SELECT synced, plain, found, coalesce(source,'') AS source FROM lyrics WHERE lower(artist) = lower(?) AND lower(track) = lower(?) AND duration_s = ?",
            t.artist, t.title, durKey(t.durMs)).firstOrNull() ?: return null
        if (!r.bool("found")) return Lyrics(source = r.str("source").ifEmpty { "lrclib" }, synced = null, plain = null)
        return Lyrics(source = r.str("source").ifEmpty { "lrclib" }, synced = r.strOrNull("synced")?.takeIf { it.isNotBlank() },
            plain = r.strOrNull("plain")?.takeIf { it.isNotBlank() })
    }

    /** A negative or positive result, keyed BOTH ways (the legacy key stays
     *  valid for G2CC's readers; the track link is ours). */
    fun setLyrics(t: TrackFile, l: Lyrics?) {
        val found = l?.found == true
        db.exec(
            """INSERT INTO lyrics (artist, track, duration_s, synced, plain, found, source, track_id, fetched_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
               ON CONFLICT (lower(artist), lower(track), duration_s)
               DO UPDATE SET synced = EXCLUDED.synced, plain = EXCLUDED.plain, found = EXCLUDED.found,
                             source = EXCLUDED.source, track_id = EXCLUDED.track_id, fetched_at = now()""",
            t.artist.ifEmpty { "(unknown)" }, t.title, durKey(t.durMs), l?.synced, l?.plain, found, l?.source ?: "", t.id)
    }

    // ------------------------------------------------------------------ history
    fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean, source: String) {
        db.exec("INSERT INTO play_history (track_id, started_at, ended_at, completed, skipped, source) VALUES (?, to_timestamp(? / 1000.0), to_timestamp(? / 1000.0), ?, ?, ?)",
            trackId, startedMs, endedMs, completed, skipped, source)
    }

    fun recentHistoryIds(limit: Int): List<Int> =
        db.query("SELECT track_id FROM play_history ORDER BY id DESC LIMIT ?", limit).map { it.int("track_id") }

    // ------------------------------------------------------------------ the library walk (scan)
    fun knownFiles(): Map<String, Pair<Int, Long>> =
        db.query("SELECT id, path, mtime_ms FROM tracks").associate { it.str("path") to (it.int("id") to it.long("mtime_ms")) }

    fun upsertTrack(path: String, title: String, artist: String?, album: String?, durMs: Int?, mtimeMs: Long, trackNo: Int?, discNo: Int?): Int =
        db.query(
            """INSERT INTO tracks (path, title, artist, album, dur_ms, mtime_ms, track_no, disc_no) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (path) DO UPDATE SET title = EXCLUDED.title, artist = EXCLUDED.artist, album = EXCLUDED.album,
                 dur_ms = EXCLUDED.dur_ms, mtime_ms = EXCLUDED.mtime_ms, track_no = EXCLUDED.track_no, disc_no = EXCLUDED.disc_no,
                 indexed_at = now() RETURNING id""",
            path, title, artist, album, durMs, mtimeMs, trackNo, discNo).first().int("id")

    /** Conditional on the snapshot path: a row that moved mid-scan (an ingest
     *  filing) is never deleted by the walk that missed its old path. */
    fun deleteVanished(id: Int, path: String): Int = db.exec("DELETE FROM tracks WHERE id = ? AND path = ?", id, path)

    fun counts(): Map<String, Long> = listOf("tracks", "track_meta", "playlists", "playlist_tracks", "lyrics", "play_history")
        .associateWith { db.query("SELECT count(*) AS n FROM $it").first().long("n") } +
        mapOf("lyrics_synced" to db.query("SELECT count(*) AS n FROM lyrics WHERE synced IS NOT NULL AND synced <> ''").first().long("n"))

    companion object {
        /** Additive migrations, applied once each and recorded in `damage_schema`. */
        val MIGRATIONS: List<Pair<String, List<String>>> = listOf(
            "lyrics-source-trackid-1" to listOf(
                "ALTER TABLE lyrics ADD COLUMN IF NOT EXISTS source text",
                "ALTER TABLE lyrics ADD COLUMN IF NOT EXISTS track_id integer REFERENCES tracks(id) ON DELETE SET NULL",
                "CREATE INDEX IF NOT EXISTS lyrics_track_idx ON lyrics (track_id)",
            ),
        )
    }
}
