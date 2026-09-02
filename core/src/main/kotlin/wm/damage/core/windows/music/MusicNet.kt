package wm.damage.core.windows.music

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.net.RemoteWin
import wm.damage.core.net.WinService
import wm.damage.core.util.Log

/**
 * The Music provider over the §16.10 window channel (`{"t":"win","win":
 * "music"}` on the content port, `MUSIC.md` §6.3): [MusicService] adapts the
 * host's [LocalMusicLibrary] to the wire; [RemoteMusicLibrary] is the phone
 * side — the catalog cached on disk (Browse works with the PC down), art /
 * viz / lyrics cached beside it, a VERSION cursor over the catalog, and the
 * first use of the PUSH slice: catalog bumps and grab progress arrive as
 * unsolicited frames instead of a poll.
 */
private val json = musicJson

class MusicService(private val lib: MusicLibrary) : WinService {
    private val pushers = CopyOnWriteArrayList<WinService.Push>()

    private val listener = object : MusicLibrary.Listener {
        override fun catalogChanged(c: Catalog) {
            for (p in pushers) p.send("catalog", buildJsonObject { put("v", c.version) })
        }
        override fun ytJob(j: YtJob) {
            for (p in pushers) p.send("yt", json.encodeToJsonElement(YtJob.serializer(), j).jsonObject)
        }
        override fun state(line: String) {
            for (p in pushers) p.send("state", buildJsonObject { put("line", line) })
        }
    }

    init { lib.addListener(listener) }

    override fun attached(push: WinService.Push) { pushers.addIfAbsent(push) }
    override fun detached(push: WinService.Push) { pushers.remove(push) }

    override fun request(op: String, args: JsonObject): WinService.Answer {
        fun s(k: String): String = args[k]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("missing '$k'")
        fun i(k: String, d: Int) = args[k]?.jsonPrimitive?.intOrNull ?: d
        fun l(k: String, d: Long) = args[k]?.jsonPrimitive?.longOrNull ?: d
        fun b(k: String, d: Boolean) = args[k]?.jsonPrimitive?.booleanOrNull ?: d
        fun ids(k: String): List<Int> = (args[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
        fun refs(list: List<TrackRef>) = WinService.Answer(blob = json.encodeToString(ListSerializer(TrackRef.serializer()), list).toByteArray(Charsets.UTF_8))
        fun profile(): AudioProfile = args["profile"]?.jsonPrimitive?.contentOrNull?.let { AudioProfile.parse(it) } ?: AudioProfile.DEFAULT
        return when (op) {
            "catalog" -> {
                // the version cursor: an unchanged catalog answers with no
                // blob; a refresh runs first when the driver asks for one
                if (b("refresh", false)) lib.refreshCatalog()
                val c = lib.catalog()
                val have = args["v"]?.jsonPrimitive?.contentOrNull ?: ""
                val changed = c.version != have || c.tracks.isEmpty() && have.isNotEmpty()
                WinService.Answer(buildJsonObject { put("v", c.version); put("changed", changed); put("state", lib.stateLine()) },
                    if (changed) c.encode() else null)
            }
            "search" -> refs(lib.search(s("q")))
            "ask" -> WinService.Answer(blob = json.encodeToString(ResolvedQueue.serializer(), lib.ask(s("q"))).toByteArray(Charsets.UTF_8))
            "similar" -> refs(lib.similar(ids("ids"), ids("exclude"), i("n", 10)))
            "random" -> refs(lib.randomLibrary(i("n", 25), ids("exclude")))
            "playlists" -> WinService.Answer(blob = json.encodeToString(ListSerializer(Playlist.serializer()), lib.playlists()).toByteArray(Charsets.UTF_8))
            "playlist" -> refs(lib.playlistTracks(i("id", 0)))
            "playlist.save" -> WinService.Answer(json.encodeToJsonElement(Playlist.serializer(),
                lib.savePlaylist(s("name"), ids("ids"), b("overwrite", false))).jsonObject)
            "playlist.rename" -> { lib.renamePlaylist(i("id", 0), s("name")); WinService.Answer() }
            "playlist.delete" -> { lib.deletePlaylist(i("id", 0)); WinService.Answer() }
            "playlist.set" -> { lib.setPlaylistTracks(i("id", 0), ids("ids")); WinService.Answer() }
            "lyrics" -> {
                val ly = lib.lyrics(i("id", 0))
                WinService.Answer(buildJsonObject { put("has", ly != null) },
                    ly?.let { json.encodeToString(Lyrics.serializer(), it).toByteArray(Charsets.UTF_8) })
            }
            "lyrics.search" -> WinService.Answer(blob = json.encodeToString(ListSerializer(Lyrics.serializer()),
                lib.searchLyrics(i("id", 0), s("q"))).toByteArray(Charsets.UTF_8))
            "lyrics.sources" -> { lib.setLyricsSources(s("sources")); WinService.Answer() }
            "lyrics.set" -> {
                val choice = json.decodeFromString(Lyrics.serializer(), s("lyrics"))
                lib.setLyrics(i("id", 0), choice); WinService.Answer()
            }
            "art" -> {
                val a = lib.art(i("id", 0), i("px", 56))
                WinService.Answer(buildJsonObject { put("has", a != null) }, a)
            }
            "viz" -> {
                val v = lib.viz(i("id", 0))
                WinService.Answer(buildJsonObject { put("has", v != null) }, v?.encode())
            }
            "yt.search" -> WinService.Answer(blob = json.encodeToString(ListSerializer(YtResult.serializer()), lib.ytSearch(s("q"))).toByteArray(Charsets.UTF_8))
            "yt.grab" -> WinService.Answer(buildJsonObject { put("job", lib.ytGrab(s("id"))) })
            "yt.status" -> WinService.Answer(json.encodeToJsonElement(YtJob.serializer(), lib.ytStatus(s("job"))).jsonObject)
            "played" -> { lib.played(i("id", 0), l("started", 0), l("ended", 0), b("completed", false), b("skipped", false)); WinService.Answer() }
            "pretranscode" -> WinService.Answer(buildJsonObject { put("line", lib.pretranscode(profile())) })
            "rescan" -> WinService.Answer(buildJsonObject { put("line", lib.rescan()) })
            else -> throw IllegalArgumentException("unknown music op '$op'")
        }
    }
}

/**
 * The phone side. `catalog()` is the in-memory copy (loaded from disk at
 * construction, so Browse works before the PC answers); `refreshCatalog()`
 * asks with the version cursor, paced by the caller. Art, viz and lyrics
 * are cached per track under [cacheDir]; the stream URL points at the PC's
 * media endpoint on [mediaPort]. The channel's staleness line comes first
 * (`PC unreachable 40s`), the host's own second.
 */
class RemoteMusicLibrary(
    private val host: String,
    port: Int,
    private val token: String,
    private val mediaPort: Int,
    private val cacheDir: Path,
    private val scope: CoroutineScope,
    private val onState: (String) -> Unit = {},
) : MusicLibrary {

    private val listeners = CopyOnWriteArrayList<MusicLibrary.Listener>()
    @Volatile private var cat: Catalog = Catalog.EMPTY
    @Volatile private var hostState = ""
    @Volatile private var chanState = "connecting to $host"
    @Volatile private var hostVersion = ""
    private val refreshLock = Any()
    @Volatile private var running = true

    private val catPath = cacheDir.resolve("catalog.json")

    init {
        Files.createDirectories(cacheDir.resolve("art"))
        Files.createDirectories(cacheDir.resolve("viz"))
        Files.createDirectories(cacheDir.resolve("lyrics"))
        if (Files.isRegularFile(catPath)) try {
            cat = Catalog.decode(Files.readAllBytes(catPath))
            Log.i("music-remote", "catalog ${cat.version} from the cache: ${cat.tracks.size} tracks")
        } catch (e: Exception) {
            Log.w("music-remote", "cached catalog unreadable — starting empty: ${e.message}")
        }
    }

    // declared AFTER every field it touches (the Torrents P7 ordering)
    private val ch = RemoteWin(host, port, token, "music", scope, onState = { s ->
        chanState = s
        pushState()
        // the channel just came up (or back): ask for the catalog now, off
        // the channel's own thread
        if (s.isEmpty()) scope.launch(Dispatchers.IO) { try { refreshCatalog() } catch (e: Exception) { /* said by the state line */ } }
    }, onPush = { op, args, _ ->
        when (op) {
            "catalog" -> {
                val v = args["v"]?.jsonPrimitive?.contentOrNull ?: ""
                hostVersion = v
                if (v != cat.version) scope.launch(Dispatchers.IO) { try { refreshCatalog() } catch (e: Exception) { /* said */ } }
            }
            "yt" -> try {
                val j = json.decodeFromJsonElement(YtJob.serializer(), args)
                for (l in listeners) try { l.ytJob(j) } catch (e: Exception) { Log.e("music-remote", "yt listener", e) }
            } catch (e: Exception) { Log.w("music-remote", "yt push undecodable: ${e.message}") }
            "state" -> { hostState = args["line"]?.jsonPrimitive?.contentOrNull ?: ""; pushState() }
            else -> Log.w("music-remote", "unknown push '$op' ignored")
        }
    })

    private fun pushState() {
        val line = stateLine()
        try { onState(line) } catch (e: Exception) { Log.e("music-remote", "state hook", e) }
        for (l in listeners) try { l.state(line) } catch (e: Exception) { Log.e("music-remote", "state listener", e) }
    }

    override fun stateLine(): String = chanState.ifEmpty { hostState }
    override fun catalog(): Catalog = cat

    private fun args(vararg kv: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in kv) when (v) {
            null -> {}
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Boolean -> put(k, v)
            is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it as Int) }))
            else -> throw IllegalArgumentException("arg $k: ${v::class}")
        }
    }

    private fun blobOf(a: RemoteWin.Answer, what: String): ByteArray =
        a.blob ?: throw IllegalStateException("no $what came back")

    private fun refs(a: RemoteWin.Answer, what: String): List<TrackRef> =
        json.decodeFromString(ListSerializer(TrackRef.serializer()), blobOf(a, what).toString(Charsets.UTF_8))

    /** The version cursor: our version goes up, a changed catalog comes back
     *  as a blob and is cached on disk. Serialized. */
    override fun refreshCatalog() {
        synchronized(refreshLock) {
            val a = try {
                ch.request("catalog", args("v" to cat.version))
            } catch (e: Exception) {
                throw IllegalStateException(stateLine().ifEmpty { e.message ?: "catalog request failed" })
            }
            hostState = a.data["state"]?.jsonPrimitive?.contentOrNull ?: ""
            val changed = a.data["changed"]?.jsonPrimitive?.booleanOrNull == true
            hostVersion = a.data["v"]?.jsonPrimitive?.contentOrNull ?: hostVersion
            pushState()
            if (!changed || a.blob == null) return
            val c = Catalog.decode(a.blob)
            cat = c
            try {
                val tmp = catPath.resolveSibling("catalog.json.${System.nanoTime()}.tmp")
                Files.write(tmp, a.blob)
                Files.move(tmp, catPath, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Log.e("music-remote", "catalog cache write failed (${e.message}) — kept in memory only")
            }
            Log.i("music-remote", "catalog ${c.version}: ${c.tracks.size} tracks")
            for (l in listeners) try { l.catalogChanged(c) } catch (e: Exception) { Log.e("music-remote", "catalog listener", e) }
        }
    }

    override fun search(q: String): List<TrackRef> = refs(ch.request("search", args("q" to q)), "search results")
    override fun ask(request: String): ResolvedQueue =
        json.decodeFromString(ResolvedQueue.serializer(), blobOf(ch.request("ask", args("q" to request)), "answer").toString(Charsets.UTF_8))
    override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int): List<TrackRef> =
        refs(ch.request("similar", args("ids" to trackIds, "exclude" to exclude, "n" to n)), "neighbours")
    override fun randomLibrary(n: Int, exclude: List<Int>): List<TrackRef> =
        refs(ch.request("random", args("n" to n, "exclude" to exclude)), "random tracks")
    override fun playlists(): List<Playlist> =
        json.decodeFromString(ListSerializer(Playlist.serializer()), blobOf(ch.request("playlists"), "playlists").toString(Charsets.UTF_8))
    override fun playlistTracks(id: Int): List<TrackRef> = refs(ch.request("playlist", args("id" to id)), "playlist")
    override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean): Playlist =
        json.decodeFromJsonElement(Playlist.serializer(), ch.request("playlist.save", args("name" to name, "ids" to trackIds, "overwrite" to overwrite)).data)
    override fun renamePlaylist(id: Int, name: String) { ch.request("playlist.rename", args("id" to id, "name" to name)) }
    override fun deletePlaylist(id: Int) { ch.request("playlist.delete", args("id" to id)) }
    override fun setPlaylistTracks(id: Int, trackIds: List<Int>) { ch.request("playlist.set", args("id" to id, "ids" to trackIds)) }

    private fun lyricsPath(id: Int) = cacheDir.resolve("lyrics").resolve("$id.json")

    override fun lyrics(trackId: Int): Lyrics? {
        val p = lyricsPath(trackId)
        if (Files.isRegularFile(p)) try {
            return json.decodeFromString(Lyrics.serializer(), Files.readString(p)).takeIf { it.found }
        } catch (e: Exception) { Log.w("music-remote", "cached lyrics for $trackId unreadable: ${e.message}") }
        val a = ch.request("lyrics", args("id" to trackId))
        val has = a.data["has"]?.jsonPrimitive?.booleanOrNull == true
        val ly = if (has && a.blob != null) json.decodeFromString(Lyrics.serializer(), a.blob.toString(Charsets.UTF_8)) else null
        if (ly != null && ly.found) try { Files.writeString(p, json.encodeToString(Lyrics.serializer(), ly)) } catch (e: Exception) { Log.w("music-remote", "lyrics cache write: ${e.message}") }
        return ly
    }

    override fun searchLyrics(trackId: Int, query: String): List<Lyrics> =
        json.decodeFromString(ListSerializer(Lyrics.serializer()), blobOf(ch.request("lyrics.search", args("id" to trackId, "q" to query)), "lyric candidates").toString(Charsets.UTF_8))

    override fun setLyricsSources(sources: String) {
        try { ch.request("lyrics.sources", args("sources" to sources)) } catch (e: Exception) { Log.w("music-remote", "lyric sources not sent: ${e.message}") }
    }

    override fun setLyrics(trackId: Int, choice: Lyrics) {
        ch.request("lyrics.set", args("id" to trackId, "lyrics" to json.encodeToString(Lyrics.serializer(), choice)))
        try { Files.writeString(lyricsPath(trackId), json.encodeToString(Lyrics.serializer(), choice)) } catch (e: Exception) { Log.w("music-remote", "lyrics cache write: ${e.message}") }
    }

    override fun art(trackId: Int, px: Int): ByteArray? {
        val p = cacheDir.resolve("art").resolve("$trackId-$px.gray")
        val none = cacheDir.resolve("art").resolve("$trackId.none")
        if (Files.isRegularFile(p)) return Files.readAllBytes(p)
        if (Files.isRegularFile(none)) return null
        val a = ch.request("art", args("id" to trackId, "px" to px))
        val has = a.data["has"]?.jsonPrimitive?.booleanOrNull == true
        try {
            if (has && a.blob != null) Files.write(p, a.blob) else Files.writeString(none, "")
        } catch (e: Exception) { Log.w("music-remote", "art cache write: ${e.message}") }
        return if (has) a.blob else null
    }

    override fun viz(trackId: Int): VizData? {
        val p = cacheDir.resolve("viz").resolve("$trackId.viz")
        if (Files.isRegularFile(p)) try { return VizData.decode(Files.readAllBytes(p)) } catch (e: Exception) {
            Log.w("music-remote", "cached viz for $trackId unreadable — refetching: ${e.message}"); Files.deleteIfExists(p)
        }
        val a = ch.request("viz", args("id" to trackId))
        val has = a.data["has"]?.jsonPrimitive?.booleanOrNull == true
        if (!has || a.blob == null) return null
        val v = VizData.decode(a.blob)
        try { Files.write(p, a.blob) } catch (e: Exception) { Log.w("music-remote", "viz cache write: ${e.message}") }
        return v
    }

    override fun ytSearch(q: String): List<YtResult> =
        json.decodeFromString(ListSerializer(YtResult.serializer()), blobOf(ch.request("yt.search", args("q" to q)), "YouTube results").toString(Charsets.UTF_8))
    override fun ytGrab(id: String): String = ch.request("yt.grab", args("id" to id)).data["job"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalStateException("the host started no job")
    override fun ytStatus(job: String): YtJob = json.decodeFromJsonElement(YtJob.serializer(), ch.request("yt.status", args("job" to job)).data)

    override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) {
        ch.request("played", args("id" to trackId, "started" to startedMs, "ended" to endedMs, "completed" to completed, "skipped" to skipped))
    }

    override fun streamUrl(trackId: Int, profile: AudioProfile): String =
        "http://$host:$mediaPort/track/$trackId?token=${java.net.URLEncoder.encode(token, "UTF-8")}&profile=${profile.name}"

    override fun pretranscode(profile: AudioProfile): String =
        ch.request("pretranscode", args("profile" to profile.name)).data["line"]?.jsonPrimitive?.contentOrNull ?: ""
    override fun rescan(): String = ch.request("rescan").data["line"]?.jsonPrimitive?.contentOrNull ?: ""

    override fun addListener(l: MusicLibrary.Listener) {
        if (!listeners.addIfAbsent(l)) return
        try { l.state(stateLine()) } catch (e: Exception) { Log.e("music-remote", "listener", e) }
    }
    override fun removeListener(l: MusicLibrary.Listener) { listeners.remove(l) }
    override fun setFocused(focused: Boolean, paceMs: Long) {}

    override fun close() {
        running = false
        ch.close()
    }
}
