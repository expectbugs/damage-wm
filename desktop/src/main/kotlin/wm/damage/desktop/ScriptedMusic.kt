package wm.damage.desktop

import java.util.concurrent.CopyOnWriteArrayList
import wm.damage.core.windows.music.Album
import wm.damage.core.windows.music.Artist
import wm.damage.core.windows.music.AudioProfile
import wm.damage.core.windows.music.Catalog
import wm.damage.core.windows.music.Lyrics
import wm.damage.core.windows.music.MusicLibrary
import wm.damage.core.windows.music.Playlist
import wm.damage.core.windows.music.ResolvedQueue
import wm.damage.core.windows.music.TrackMeta
import wm.damage.core.windows.music.TrackRef
import wm.damage.core.windows.music.VizData
import wm.damage.core.windows.music.VocabTerm
import wm.damage.core.windows.music.YtJob
import wm.damage.core.windows.music.YtResult

/**
 * A deterministic music LIBRARY for --selfcheck and --snapshot: a small
 * catalog (three artists, four albums, a few moods, two playlists, some
 * play history), synced lyrics for one track, drawn art, synthetic viz
 * data, canned YouTube results and a grab whose job progresses when the
 * harness says so. No Postgres, no network. Pairs with `SimMusicPlayer`.
 */
class ScriptedMusic : MusicLibrary {
    private val listeners = CopyOnWriteArrayList<MusicLibrary.Listener>()
    val ops = CopyOnWriteArrayList<String>()
    var version = "s1"

    private val tracks = ArrayList<TrackMeta>().apply {
        var id = 1
        fun add(title: String, artist: String, album: String, dur: Int, genres: List<String>, moods: List<String>, no: Int, folder: String, lyrics: Boolean = false, year: Int = 2001) {
            add(TrackMeta(id++, title, artist, album, dur, no, 1, year, genres, moods, emptyList(), 6, "male", lyrics, false, 0, folder, 1_700_000_000L + id * 3600L, "flac"))
        }
        add("Time", "Pink Floyd", "The Dark Side of the Moon", 413_000, listOf("rock", "progressive rock"), listOf("reflective"), 4, "Library/Pink Floyd/The Dark Side of the Moon", lyrics = true, year = 1973)
        add("Money", "Pink Floyd", "The Dark Side of the Moon", 382_000, listOf("rock", "progressive rock"), listOf("sardonic"), 6, "Library/Pink Floyd/The Dark Side of the Moon", year = 1973)
        add("Us and Them", "Pink Floyd", "The Dark Side of the Moon", 469_000, listOf("rock"), listOf("melancholic"), 7, "Library/Pink Floyd/The Dark Side of the Moon", year = 1973)
        add("Wish You Were Here", "Pink Floyd", "Wish You Were Here", 334_000, listOf("rock"), listOf("wistful"), 4, "Library/Pink Floyd/Wish You Were Here", year = 1975)
        add("Bohemian Rhapsody", "Queen", "A Night at the Opera", 355_000, listOf("rock", "glam rock"), listOf("dramatic"), 11, "Library/Queen/A Night at the Opera", year = 1975)
        add("Death on Two Legs", "Queen", "A Night at the Opera", 223_000, listOf("rock"), listOf("angry"), 1, "Library/Queen/A Night at the Opera", year = 1975)
        add("Dragula", "Rob Zombie", "Hellbilly Deluxe", 222_000, listOf("metal", "industrial metal"), listOf("aggressive", "driving"), 3, "Library/Rob Zombie/Hellbilly Deluxe", year = 1998)
        add("Superbeast", "Rob Zombie", "Hellbilly Deluxe", 220_000, listOf("metal"), listOf("aggressive"), 2, "Library/Rob Zombie/Hellbilly Deluxe", year = 1998)
        add("Living Dead Girl", "Rob Zombie", "Hellbilly Deluxe", 200_000, listOf("metal", "industrial metal"), listOf("driving"), 4, "Library/Rob Zombie/Hellbilly Deluxe", year = 1998)
        add("Dungeon Theme", "OC ReMix", "Collections", 180_000, listOf("vgm"), listOf("dark", "atmospheric"), 0, "Collections/VGM")
        add("Town Theme", "OC ReMix", "Collections", 150_000, listOf("vgm"), listOf("cheerful"), 0, "Collections/VGM")
        add("Ambient Rain", "", "", 600_000, listOf("ambient"), listOf("calm"), 0, "Unsorted")
    }

    private var playlists = mutableMapOf(
        1 to (Playlist(1, "Hard Stuff", "manual", false, 3, 1_700_000_000L) to listOf(7, 8, 9)),
        2 to (Playlist(2, "Game Music", "rule", true, 2, 1_700_000_100L) to listOf(10, 11)),
    )
    private var nextPlaylist = 3
    val history = ArrayList<String>()
    var grabPhases: List<YtJob> = emptyList()
    private var lyricsSet = HashMap<Int, Lyrics>()

    private fun byId(id: Int) = tracks.firstOrNull { it.id == id }
    private fun ref(id: Int): TrackRef = byId(id)!!.ref()

    private fun buildCatalog(): Catalog {
        val artists = tracks.filter { it.artist.isNotEmpty() }.groupBy { it.artist }.map { (a, ts) -> Artist(a, ts.size, ts.map { it.album }.toSet().size) }.sortedBy { it.name }
        val albums = tracks.filter { it.album.isNotEmpty() }.groupBy { it.album }.map { (al, ts) -> Album(al, ts.map { it.artist }.toSet().singleOrNull() ?: "Various", ts.size, ts.first().year) }.sortedBy { it.name }
        val vocab = ArrayList<VocabTerm>()
        for ((kind, pick) in listOf("genre" to { t: TrackMeta -> t.genres }, "mood" to { t: TrackMeta -> t.moods })) {
            tracks.flatMap(pick).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.forEach { vocab.add(VocabTerm(it.key, kind, it.value)) }
        }
        return Catalog(version, 1L, tracks, artists, albums, playlists.values.map { it.first }, vocab, listOf(7, 1, 5))
    }

    @Volatile private var cat = buildCatalog()

    private fun bump() {
        version = "s" + (version.drop(1).toInt() + 1)
        cat = buildCatalog()
        for (l in listeners) l.catalogChanged(cat)
    }

    override fun stateLine() = ""
    override fun catalog(): Catalog = cat
    override fun refreshCatalog() { ops.add("refresh") }
    override fun search(q: String): List<TrackRef> {
        ops.add("search:$q")
        val toks = q.lowercase().split(' ').filter { it.isNotEmpty() }
        return tracks.filter { t -> toks.all { k -> t.title.lowercase().contains(k) || t.artist.lowercase().contains(k) || t.album.lowercase().contains(k) } }.map { it.ref() }
    }
    override fun ask(request: String): ResolvedQueue {
        ops.add("ask:$request")
        val toks = request.lowercase().split(' ')
        val hits = tracks.filter { t -> toks.any { k -> t.genres.contains(k) || t.moods.contains(k) } }
        return if (hits.isEmpty()) ResolvedQueue(emptyList(), "empty", request, "no library match for \"$request\" (all lanes)")
        else ResolvedQueue(hits.map { it.ref() }, "vocab", toks.joinToString(" "), "lane vocab [${toks.joinToString(", ")}]: ${hits.size} matched → ${hits.size} queued")
    }
    override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int): List<TrackRef> = tracks.filter { it.id !in exclude }.take(n).map { it.ref() }
    override fun randomLibrary(n: Int, exclude: List<Int>): List<TrackRef> = tracks.filter { it.id !in exclude }.shuffled(java.util.Random(7)).take(n).map { it.ref() }
    override fun playlists(): List<Playlist> = playlists.values.map { it.first }
    override fun playlistTracks(id: Int): List<TrackRef> = playlists[id]?.second?.map(::ref) ?: throw IllegalStateException("no playlist $id")
    override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean): Playlist {
        ops.add("save:$name:$trackIds:$overwrite")
        val existing = playlists.values.firstOrNull { it.first.name.equals(name, true) }
        if (existing != null && !overwrite) throw IllegalStateException("a playlist named \"$name\" exists")
        val id = existing?.first?.id ?: nextPlaylist++
        val p = Playlist(id, name, "manual", false, trackIds.size, System.currentTimeMillis() / 1000)
        playlists[id] = p to trackIds
        bump()
        return p
    }
    override fun renamePlaylist(id: Int, name: String) { ops.add("rename:$id:$name"); playlists[id]?.let { playlists[id] = it.first.copy(name = name) to it.second; bump() } }
    override fun deletePlaylist(id: Int) { ops.add("delete:$id"); playlists.remove(id); bump() }
    override fun setPlaylistTracks(id: Int, trackIds: List<Int>) {
        val p = playlists[id] ?: throw IllegalStateException("no playlist $id")
        if (p.first.adaptive) throw IllegalStateException("adaptive playlist — its rule manages the rows")
        ops.add("set:$id:$trackIds")
        playlists[id] = p.first.copy(count = trackIds.size) to trackIds
        bump()
    }
    override fun lyrics(trackId: Int): Lyrics? = lyricsSet[trackId] ?: if (trackId == 1) Lyrics("lrclib", LRC) else null
    override fun searchLyrics(trackId: Int, query: String): List<Lyrics> = listOf(Lyrics("netease", LRC, null, "Pink Floyd — Time · 6:53 · netease"))
    override fun setLyrics(trackId: Int, choice: Lyrics) { ops.add("lyrics.set:$trackId"); lyricsSet[trackId] = choice }
    /** Drawn art: a diagonal gradient with a bright disc — packed 4-bit. */
    override fun art(trackId: Int, px: Int): ByteArray? {
        if (trackId % 3 == 0) return null
        val g = ByteArray(px * px)
        for (y in 0 until px) for (x in 0 until px) {
            val dx = x - px / 2; val dy = y - px / 2
            val disc = dx * dx + dy * dy < (px / 3) * (px / 3)
            g[y * px + x] = (if (disc) 200 + (trackId * 7) % 50 else (x + y) * 90 / px).coerceIn(0, 255).toByte()
        }
        return wm.damage.core.windows.music.Art.pack(g, px * px)
    }
    override fun viz(trackId: Int): VizData? {
        val bands = 24; val fps = 20; val frames = 20 * 420          // seven minutes: longer than any scripted track
        val packed = ByteArray((frames * bands + 1) / 2)
        for (i in 0 until frames * bands) {
            val f = i / bands; val b = i % bands
            val n = ((8 + 7 * Math.sin(f * 0.3 + b * 0.5)).toInt()).coerceIn(0, 15)
            if (i and 1 == 0) packed[i shr 1] = (n shl 4).toByte() else packed[i shr 1] = (packed[i shr 1].toInt() or n).toByte()
        }
        val rmsN = 420 * 50
        val rms = ByteArray((rmsN + 1) / 2)
        for (i in 0 until rmsN) { val n = ((8 + 7 * Math.sin(i * 0.1)).toInt()).coerceIn(0, 15); if (i and 1 == 0) rms[i shr 1] = (n shl 4).toByte() else rms[i shr 1] = (rms[i shr 1].toInt() or n).toByte() }
        return VizData(fps, bands, frames, packed, rmsN, rms, IntArray(840) { it * 500 })
    }
    override fun ytSearch(q: String): List<YtResult> {
        ops.add("yt:$q")
        return (1..5).map { YtResult("vid$it", "$q — result $it", "Channel $it", 200 + it * 15, "https://www.youtube.com/watch?v=vid$it") }
    }
    override fun ytGrab(id: String): String {
        ops.add("grab:$id")
        val job = "job-$id"
        for (l in listeners) l.ytJob(YtJob(job, "grab $id", "downloading", 10))
        return job
    }
    /** The harness finishes a grab: the track appears in the catalog. */
    fun finishGrab(id: String) {
        val job = "job-$id"
        val t = TrackMeta(tracks.size + 1, "Grabbed $id", "YouTube", "", 240_000, 0, 0, 2026, listOf("rock"), emptyList(), emptyList(), 5, "", false, false, 0, "YouTube", System.currentTimeMillis() / 1000, "opus")
        tracks.add(t)
        bump()
        for (l in listeners) l.ytJob(YtJob(job, t.title, "done", 100, t.id))
    }
    override fun ytStatus(job: String): YtJob = YtJob(job, "grab", "downloading", 50)
    override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) { history.add("$trackId:$completed:$skipped") }
    override fun streamUrl(trackId: Int, profile: AudioProfile): String = "sim://track/$trackId?profile=${profile.name}"
    override fun pretranscode(profile: AudioProfile): String = "building 0 of ${tracks.size} for ${profile.name}"
    override fun rescan(): String = "rescan: ${tracks.size} files"
    override fun addListener(l: MusicLibrary.Listener) { if (listeners.addIfAbsent(l)) l.state("") }
    override fun removeListener(l: MusicLibrary.Listener) { listeners.remove(l) }
    override fun setFocused(focused: Boolean, paceMs: Long) { ops.add("focus:$focused") }

    companion object {
        val LRC = """[ar:Pink Floyd]
[ti:Time]
[00:01.00] Ticking away the moments that make up a dull day
[00:05.00] Fritter and waste the hours in an offhand way
[00:09.00] Kicking around on a piece of ground in your home town
[00:13.00] Waiting for someone or something to show you the way
[00:17.00] Tired of lying in the sunshine, staying home to watch the rain
[00:21.00] You are young and life is long, and there is time to kill today
[00:25.00] And then one day you find ten years have got behind you
[00:29.00] No one told you when to run, you missed the starting gun
"""
    }
}
