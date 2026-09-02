package wm.damage.core.windows.music

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import wm.damage.core.util.Log

/**
 * The PC side of [MusicLibrary] (`MUSIC.md` §5/§9): Postgres through
 * [MusicDb], Qdrant, the files, the transcode cache + media endpoint, art,
 * the visualizer blobs, and — wired by the host — the resolver lanes, the
 * lyric fetch chain, yt-dlp and the enrichment package. Library truth lives
 * here; every call blocks and is made from background coroutines.
 *
 * The catalog is CACHED: `catalog()` is a field read (a paint may ask);
 * `refreshCatalog()` compares the cheap DB fingerprint and rebuilds on a
 * change, paced by the callers (the window on activation, the service on a
 * driver's version cursor, the scan/ingest paths after they write).
 */
class LocalMusicLibrary(
    val db: MusicDb,
    val cache: MediaCache,
    val art: Art,
    val vizDir: Path,
    private val qdrant: Qdrant?,
    private val scan: LibraryScan,
    private val scope: CoroutineScope,
    /** The media endpoint's URL for a LOCAL caller (the mirror never plays). */
    private val mediaUrl: (Int, AudioProfile) -> String,
    private val queueSize: Int = 25,
) : MusicLibrary {

    // wired by the host as the leaf modules land (§5); null = said loudly
    @Volatile var resolver: AskResolver? = null
    @Volatile var lyricsFetch: LyricsFetcher? = null
    /** The chain per sources choice — the host wires a factory; the
     *  instances are kept (each paces its own remote calls). */
    @Volatile var lyricsFetchFactory: ((netease: Boolean, musixmatch: Boolean) -> LyricsFetcher)? = null
    @Volatile private var lyricsSources = "lrclib+local"
    private val fetchers = HashMap<String, LyricsFetcher>()
    @Volatile var youtube: YtClient? = null
    @Volatile var ingester: Ingester? = null
    /** Where a grab lands (`<libraryDirs[0]>/YouTube`). */
    @Volatile var youtubeDir: Path? = null

    private val listeners = CopyOnWriteArrayList<MusicLibrary.Listener>()
    @Volatile private var cat: Catalog = Catalog.EMPTY
    @Volatile private var catVersion = ""
    @Volatile private var state = ""
    private val refreshLock = Any()
    @Volatile private var lastCheckMs = 0L

    init { Files.createDirectories(vizDir) }

    override fun stateLine(): String = state
    override fun catalog(): Catalog = cat

    private fun setState(s: String) {
        if (state == s) return
        state = s
        for (l in listeners) try { l.state(s) } catch (e: Exception) { Log.e("music", "state listener", e) }
    }

    /** Rebuild when the fingerprint moved; at most one check per [MIN_CHECK_MS]
     *  unless [force]. Loud on a DB failure (the state line carries it). */
    fun refreshCatalog(force: Boolean) {
        synchronized(refreshLock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastCheckMs < MIN_CHECK_MS && cat.tracks.isNotEmpty()) return
            lastCheckMs = now
            try {
                val v = db.catalogVersion()
                if (v == catVersion && cat.tracks.isNotEmpty()) { setState(""); return }
                val t0 = System.currentTimeMillis()
                art.beginCatalog()
                val c = db.catalog(v) { id, path, mtime -> art.likelyHas(cache.keyFor(MusicDb.TrackFile(id, path, mtime, "", "", "", 0)), path) }
                cat = c
                catVersion = v
                setState("")
                Log.i("music", "catalog $v: ${c.tracks.size} tracks · ${c.artists.size} artists · ${c.albums.size} albums · ${c.playlists.size} playlists in ${System.currentTimeMillis() - t0} ms")
                for (l in listeners) try { l.catalogChanged(c) } catch (e: Exception) { Log.e("music", "catalog listener", e) }
            } catch (e: Exception) {
                Log.e("music", "catalog refresh failed", e)
                setState("library: ${e.message}")
                throw e
            }
        }
    }

    override fun refreshCatalog() = refreshCatalog(force = false)

    override fun search(q: String): List<TrackRef> = db.search(q).map { it.ref() }

    override fun ask(request: String): ResolvedQueue {
        val r = resolver ?: throw IllegalStateException("the resolver is not wired on this host")
        return r.ask(request)
    }

    override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int): List<TrackRef> {
        val q = qdrant ?: throw IllegalStateException("Qdrant is not configured on this host")
        val seeds = trackIds.takeLast(5)
        val embedded = q.present(seeds)
        if (embedded.isEmpty()) {
            Log.w("music", "radio: none of the seed tracks $seeds are embedded yet (fresh grabs?) — no fill this round")
            return emptyList()
        }
        val ex = HashSet(exclude)
        ex.addAll(db.recentHistoryIds(50))
        val ids = q.recommend(embedded, maxOf(n * 4, 40)).filter { it !in ex }
        if (ids.isEmpty()) return emptyList()
        val bad = db.clustersOf(ex.toList())
        val byId = db.candsByIds(ids).associateBy { it.id }
        val ranked = ids.mapNotNull { byId[it] }.filter { it.dupeCluster == null || it.dupeCluster !in bad }
        return Rules.postProcess(ranked, Rules.Opts(shuffle = false, excludeSpoken = true, cap = n, requestLc = "radio"))
    }

    override fun recent(n: Int): List<Int> = db.recentIds(n)

    override fun randomLibrary(n: Int, exclude: List<Int>): List<TrackRef> {
        val ex = exclude.toHashSet()
        val rows = db.randomCands(maxOf(n * 4, 100)).filter { it.id !in ex }
        return Rules.postProcess(rows, Rules.Opts(shuffle = true, excludeSpoken = true, cap = n, requestLc = "random"))
    }

    // ------------------------------------------------------------------ playlists
    override fun playlists(): List<Playlist> = db.playlists()
    override fun playlistTracks(id: Int): List<TrackRef> = db.playlistTracks(id)
    override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean): Playlist =
        db.savePlaylist(name, trackIds, overwrite).also { changed() }
    override fun renamePlaylist(id: Int, name: String) { db.renamePlaylist(id, name); changed() }
    override fun deletePlaylist(id: Int) { db.deletePlaylist(id); changed() }
    override fun setPlaylistTracks(id: Int, trackIds: List<Int>) { db.setPlaylistTracks(id, trackIds); changed() }

    /** Something we wrote moved the fingerprint: refresh off-loop, soon. */
    private fun changed() {
        scope.launch(Dispatchers.IO) { try { refreshCatalog(force = true) } catch (e: Exception) { /* logged inside */ } }
    }

    // ------------------------------------------------------------------ lyrics
    private fun file(id: Int): MusicDb.TrackFile = db.trackFile(id) ?: throw IllegalStateException("track $id is not in the library")

    override fun setLyricsSources(sources: String) { lyricsSources = sources }

    private fun fetcher(): LyricsFetcher? {
        val fac = lyricsFetchFactory ?: return lyricsFetch
        val key = lyricsSources
        return synchronized(fetchers) {
            fetchers.getOrPut(key) { fac(key.contains("netease") || key.contains("musixmatch"), key.contains("musixmatch")) }
        }
    }

    override fun lyrics(trackId: Int): Lyrics? {
        val t = file(trackId)
        val stored = db.lyrics(t)
        if (stored != null && stored.found) return stored
        // a durable negative from OUR chain stands until the sources widen
        if (stored != null && stored.source.startsWith("none:") && stored.source == "none:$lyricsSources") return null
        val f = fetcher() ?: return stored?.takeIf { it.found }
        val fetched = try { f.fetch(t) } catch (e: Exception) {
            // a source FAULTED (not a miss): say so, record nothing durable
            Log.w("music", "lyrics for track $trackId: ${e.message}")
            throw e
        }
        db.setLyrics(t, fetched ?: Lyrics(source = "none:$lyricsSources"))
        if (fetched != null) changed()      // a new hasLyrics bit; a negative changes no catalog field
        return fetched
    }

    override fun searchLyrics(trackId: Int, query: String): List<Lyrics> {
        val f = fetcher() ?: throw IllegalStateException("the lyric sources are not wired on this host")
        return f.search(file(trackId), query)
    }

    override fun setLyrics(trackId: Int, choice: Lyrics) {
        db.setLyrics(file(trackId), choice)
        changed()
    }

    // ------------------------------------------------------------------ art + viz
    override fun art(trackId: Int, px: Int): ByteArray? {
        val t = file(trackId)
        return art.get(cache.keyFor(t), t.path, px)
    }

    fun vizPath(t: MusicDb.TrackFile): Path = vizDir.resolve(cache.keyFor(t) + ".viz")

    /** The cached blob at once; a missing one is BUILT IN THE BACKGROUND (a
     *  librosa run takes seconds — never inline on the sequential window
     *  channel, review 2026-09-03) and announced through [Listener.vizReady]. */
    override fun viz(trackId: Int): VizData? {
        val t = file(trackId)
        val p = vizPath(t)
        if (Files.exists(p)) {
            try { return VizData.decode(Files.readAllBytes(p)) } catch (e: Exception) {
                Log.w("music", "viz blob for track $trackId unreadable — rebuilding: ${e.message}")
                Files.deleteIfExists(p)
            }
        }
        buildVizAsync(t)
        return null
    }

    private val vizBuilding = ConcurrentHashMap.newKeySet<Int>()

    private fun buildVizAsync(t: MusicDb.TrackFile) {
        val ing = ingester ?: return
        if (!vizBuilding.add(t.id)) return
        scope.launch(Dispatchers.IO) {
            try {
                if (buildViz(t, ing) != null) for (l in listeners) try { l.vizReady(t.id) } catch (e: Exception) { Log.e("music", "viz listener", e) }
            } finally { vizBuilding.remove(t.id) }
        }
    }

    /** Blocking build + cache write; null when the tool produced nothing. */
    fun buildViz(t: MusicDb.TrackFile, ingester: Ingester? = this.ingester): VizData? {
        val ing = ingester ?: return null
        val b = try { ing.viz(t) } catch (e: Exception) { Log.w("music", "viz for track ${t.id}: ${e.message}"); null } ?: return null
        val v = try { VizData.decode(b) } catch (e: Exception) { Log.w("music", "viz for track ${t.id} undecodable: ${e.message}"); return null }
        try {
            val tmp = vizPath(t).resolveSibling(vizPath(t).fileName.toString() + ".${System.nanoTime()}.tmp")
            Files.write(tmp, b)
            Files.move(tmp, vizPath(t), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Log.w("music", "viz cache write failed for track ${t.id}: ${e.message}")
        }
        return v
    }

    // ------------------------------------------------------------------ YouTube + ingest
    private val jobs = ConcurrentHashMap<String, YtJob>()
    private val jobSeq = AtomicLong(0)

    override fun ytSearch(q: String): List<YtResult> {
        val y = youtube ?: throw IllegalStateException("yt-dlp is not wired on this host")
        return y.search(q, 10)
    }

    private fun jobUpdate(j: YtJob) {
        jobs[j.job] = j
        for (l in listeners) try { l.ytJob(j) } catch (e: Exception) { Log.e("music", "yt listener", e) }
    }

    override fun ytGrab(id: String): String {
        val y = youtube ?: throw IllegalStateException("yt-dlp is not wired on this host")
        val jobId = "yt${jobSeq.incrementAndGet()}-${System.currentTimeMillis() / 1000}"
        jobUpdate(YtJob(jobId, phase = "queued"))
        scope.launch(Dispatchers.IO) {
            var j = jobs[jobId] ?: return@launch
            try {
                j = j.copy(phase = "downloading"); jobUpdate(j)
                val path = y.grab(id) { pct -> jobUpdate(jobs[jobId]!!.copy(percent = pct)) }
                j = jobs[jobId]!!.copy(phase = "indexing", percent = 100, title = path.fileName.toString().substringBeforeLast('.')); jobUpdate(j)
                scan.scan()
                val t = db.trackByPath(path.toString()) ?: throw IllegalStateException("the grabbed file did not index ($path) — see the scan log")
                j = jobs[jobId]!!.copy(trackId = t.id, title = t.title); jobUpdate(j)
                refreshCatalog(force = true)
                j = jobs[jobId]!!.copy(phase = "transcoding"); jobUpdate(j)
                try { cache.fileFor(t, AudioProfile.DEFAULT) } catch (e: Exception) { Log.w("music", "grab transcode: ${e.message}") }
                val ing = ingester
                if (ing != null) {
                    j = jobs[jobId]!!.copy(phase = "enriching"); jobUpdate(j)
                    try { ing.enrich(t.id) { ph -> jobUpdate(jobs[jobId]!!.copy(phase = "enriching: $ph")) } }
                    catch (e: Exception) { Log.e("music", "enrichment for track ${t.id} failed (the track stays playable)", e) }
                    buildViz(t, ing)
                } else Log.w("music", "no ingester wired — track ${t.id} indexed without enrichment")
                j = jobs[jobId]!!.copy(phase = "lyrics"); jobUpdate(j)
                try { lyrics(t.id) } catch (e: Exception) { Log.w("music", "lyrics for track ${t.id}: ${e.message}") }
                refreshCatalog(force = true)
                jobUpdate(jobs[jobId]!!.copy(phase = "done", percent = 100))
            } catch (e: Exception) {
                Log.e("music", "YouTube grab $jobId failed", e)
                jobUpdate(jobs[jobId]!!.copy(phase = "failed", error = e.message ?: e.toString()))
            }
        }
        return jobId
    }

    override fun ytStatus(job: String): YtJob = jobs[job] ?: throw IllegalStateException("no such grab job '$job' (a host restart forgets its jobs)")

    // ------------------------------------------------------------------ history + stream
    override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) {
        db.played(trackId, startedMs, endedMs, completed, skipped, "damage")
    }

    override fun streamUrl(trackId: Int, profile: AudioProfile): String = mediaUrl(trackId, profile)

    /** The media endpoint's resolver: the cached (or freshly transcoded) file + MIME. */
    fun resolveMedia(trackId: Int, profile: AudioProfile): Pair<Path, String> {
        val t = file(trackId)
        return cache.fileFor(t, profile) to cache.mimeFor(t, profile)
    }

    override fun pretranscode(profile: AudioProfile): String = cache.pretranscode(profile, { db.trackFiles() })

    override fun rescan(): String {
        val s = scan.scan()
        refreshCatalog(force = true)
        return "rescan: $s"
    }

    override fun addListener(l: MusicLibrary.Listener) {
        if (!listeners.addIfAbsent(l)) return
        try { l.state(state) } catch (e: Exception) { Log.e("music", "listener", e) }
    }
    override fun removeListener(l: MusicLibrary.Listener) { listeners.remove(l) }
    override fun setFocused(focused: Boolean, paceMs: Long) {}

    companion object {
        const val MIN_CHECK_MS = 30_000L
    }
}
