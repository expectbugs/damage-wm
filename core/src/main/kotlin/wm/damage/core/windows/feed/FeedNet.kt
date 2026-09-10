package wm.damage.core.windows.feed

import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * The Feed provider over the §16.10 window channel (`{"t":"win","win":
 * "feed"}` on the content port — FEED.md §3.6): [FeedService] adapts the
 * PC's engine to the wire; [RemoteFeedProvider] is the phone's client. The
 * item lists, articles, comments and the archive index ride as JSON blobs;
 * strips ride as the packed 4-bit rows the compositor wants, deflated, with
 * their dimensions in the answer's data. Changes reach the phone as `changed`
 * pushes, so a list repaints only when its source moved.
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

object FeedWire {
    fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(6)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(b.size / 4 + 64)
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return out.toByteArray()
    }

    fun inflate(b: ByteArray, expected: Int): ByteArray {
        val i = Inflater()
        i.setInput(b)
        val out = ByteArray(expected)
        var off = 0
        while (off < expected && !i.finished()) {
            val n = i.inflate(out, off, expected - off)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            off += n
        }
        i.end()
        if (off != expected) throw IllegalStateException("strip inflated to $off B, expected $expected")
        return out
    }

    fun stripHeader(prefix: String, s: Strip, deflated: ByteArray): Map<String, Any> = mapOf(
        "${prefix}W" to s.w, "${prefix}H" to s.h, "${prefix}Inv" to s.inverted, "${prefix}Lv" to s.levels, "${prefix}Len" to deflated.size,
    )

    fun stripFrom(d: JsonObject, prefix: String, blob: ByteArray, offset: Int): Pair<Strip, Int> {
        val w = d["${prefix}W"]?.jsonPrimitive?.intOrNull ?: throw IllegalStateException("no ${prefix}W in the answer")
        val h = d["${prefix}H"]?.jsonPrimitive?.intOrNull ?: 0
        val inv = d["${prefix}Inv"]?.jsonPrimitive?.booleanOrNull ?: false
        val lv = d["${prefix}Lv"]?.jsonPrimitive?.intOrNull ?: 16
        val len = d["${prefix}Len"]?.jsonPrimitive?.intOrNull ?: 0
        require(offset + len <= blob.size) { "strip bytes past the blob (${offset + len} > ${blob.size})" }
        val packed = inflate(blob.copyOfRange(offset, offset + len), w / 2 * h)
        return Strip(w, h, inv, lv, packed) to (offset + len)
    }
}

class FeedService(private val p: FeedProvider) : WinService {
    private val pushers = CopyOnWriteArrayList<WinService.Push>()

    private val listener = object : FeedProvider.Listener {
        override fun changed(sourceId: String, version: Long) {
            for (push in pushers) push.send("changed", buildJsonObject { put("source", sourceId); put("version", version) })
        }
        override fun state(line: String) {
            for (push in pushers) push.send("state", buildJsonObject { put("line", line) })
        }
    }

    override fun attached(push: WinService.Push) {
        pushers.add(push)
        p.addListener(listener)          // idempotent on every provider
    }

    override fun detached(push: WinService.Push) {
        pushers.remove(push)
        if (pushers.isEmpty()) { p.removeListener(listener); p.setFocused(null) }
    }

    private fun jsonBlob(s: String) = s.toByteArray(Charsets.UTF_8)

    override fun request(op: String, args: JsonObject): WinService.Answer {
        fun s(k: String): String = args[k]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("missing '$k'")
        fun sOpt(k: String): String? = args[k]?.jsonPrimitive?.contentOrNull
        fun i(k: String, d: Int) = args[k]?.jsonPrimitive?.intOrNull ?: d
        fun l(k: String, d: Long) = args[k]?.jsonPrimitive?.longOrNull ?: d
        fun mode() = sOpt("mode")?.let { m -> LineArt.entries.firstOrNull { it.name == m } } ?: LineArt.AUTO
        return when (op) {
            "sources" -> WinService.Answer(buildJsonObject {
                put("state", p.stateLine()); put("engine", p.engineName())
            }, blob = jsonBlob(json.encodeToString(ListSerializer(SourceStatus.serializer()), p.sources())))
            "items" -> WinService.Answer(blob = jsonBlob(json.encodeToString(ItemPage.serializer(), p.items(s("source"), i("offset", 0), i("limit", 50)))))
            "item" -> {
                val it = p.item(s("id"))
                WinService.Answer(buildJsonObject { put("has", it != null) }, blob = it?.let { jsonBlob(json.encodeToString(Item.serializer(), it)) })
            }
            "article" -> WinService.Answer(blob = jsonBlob(json.encodeToString(Article.serializer(), p.article(s("id")))))
            "articleUrl" -> WinService.Answer(blob = jsonBlob(json.encodeToString(Article.serializer(), p.articleByUrl(s("url"), sOpt("title") ?: ""))))
            "comic" -> {
                val pack = p.comic(s("id"), i("width", 564), i("levels", 16), mode())
                val a = FeedWire.deflate(pack.strip.packed)
                val b = pack.bonus?.let { FeedWire.deflate(it.packed) }
                val data = buildJsonObject {
                    put("item", json.encodeToString(Item.serializer(), pack.item))
                    for ((k, v) in FeedWire.stripHeader("s", pack.strip, a)) putAny(k, v)
                    if (b != null && pack.bonus != null) for ((k, v) in FeedWire.stripHeader("b", pack.bonus, b)) putAny(k, v)
                }
                WinService.Answer(data, blob = if (b != null) a + b else a)
            }
            "image" -> {
                val st = p.image(s("url"), i("width", 564), i("levels", 16), mode())
                val a = FeedWire.deflate(st.packed)
                WinService.Answer(buildJsonObject { for ((k, v) in FeedWire.stripHeader("s", st, a)) putAny(k, v) }, blob = a)
            }
            "index" -> WinService.Answer(blob = jsonBlob(json.encodeToString(ListSerializer(Episode.serializer()), p.bingeIndex(s("source")))))
            "episode" -> {
                val ep = p.bingeEpisode(s("source"), i("num", 1), i("width", 564), i("levels", 16), mode())
                val a = FeedWire.deflate(ep.strip.packed)
                WinService.Answer(buildJsonObject {
                    put("episode", json.encodeToString(Episode.serializer(), ep.episode))
                    for ((k, v) in FeedWire.stripHeader("s", ep.strip, a)) putAny(k, v)
                }, blob = a)
            }
            "comments" -> WinService.Answer(blob = jsonBlob(json.encodeToString(ListSerializer(Comment.serializer()), p.comments(s("id")))))
            "refresh" -> { p.refresh(sOpt("source")); WinService.Answer() }
            "browse" -> {
                val kind = SourceKind.entries.firstOrNull { it.name == s("kind") } ?: throw IllegalArgumentException("unknown kind")
                WinService.Answer(blob = jsonBlob(json.encodeToString(SourceStatus.serializer(), p.browse(kind, s("name")))))
            }
            "forget" -> { p.forget(s("source")); WinService.Answer() }
            "focus" -> { p.setFocused(sOpt("source")); WinService.Answer() }
            "configure" -> { p.configure(l("fetchMs", 900_000), l("keepMs", 30L * 86_400_000), l("pcLossMs", 60_000)); WinService.Answer() }
            else -> throw IllegalArgumentException("unknown feed op '$op'")
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAny(k: String, v: Any) {
        when (v) {
            is Int -> put(k, v)
            is Boolean -> put(k, v)
            is String -> put(k, v)
            is Long -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}

/**
 * The phone's client to the PC's engine. The status list is cached — the
 * window's `sources()` and `summary()` must be cheap — and refreshed when
 * the channel comes up, on every `changed` push, and once a minute while a
 * source is focused (the state lines carry countdowns).
 */
class RemoteFeedProvider(
    host: String, port: Int, token: String, private val scope: CoroutineScope,
    private val onState: (String) -> Unit = {},
    private val refreshMs: Long = 60_000,
) : FeedProvider {
    private val listeners = CopyOnWriteArrayList<FeedProvider.Listener>()
    @Volatile private var cached: List<SourceStatus> = emptyList()
    @Volatile private var hostState = ""
    @Volatile private var chanState = "connecting to $host"
    @Volatile private var focused: String? = null
    @Volatile private var running = true
    @Volatile private var wakeFlag = false
    @Volatile var downSinceMs = 0L
        private set

    private val ch = RemoteWin(host, port, token, "feed", scope,
        onState = { s ->
            chanState = s
            if (s.isEmpty()) { downSinceMs = 0L; wakeFlag = true } else if (downSinceMs == 0L) downSinceMs = System.currentTimeMillis()
            pushState()
        },
        onPush = { op, args, _ ->
            when (op) {
                "changed" -> {
                    val src = args["source"]?.jsonPrimitive?.contentOrNull ?: ""
                    val v = args["version"]?.jsonPrimitive?.longOrNull ?: 0L
                    wakeFlag = true
                    scope.launch(Dispatchers.IO) { for (l in listeners) try { l.changed(src, v) } catch (e: Exception) { Log.e("feed-remote", "listener", e) } }
                }
                "state" -> { hostState = args["line"]?.jsonPrimitive?.contentOrNull ?: ""; pushState() }
                else -> Log.w("feed-remote", "unknown push '$op' ignored")
            }
        })

    private val loop: Job = scope.launch(Dispatchers.IO) {
        while (scope.isActive && running) {
            wakeFlag = false
            if (chanState.isEmpty()) refreshSources()
            val until = System.currentTimeMillis() + (if (focused != null) refreshMs else refreshMs * 5)
            while (scope.isActive && running && !wakeFlag && System.currentTimeMillis() < until) delay(200)
        }
    }

    private fun refreshSources() {
        try {
            val a = ch.request("sources")
            hostState = a.data["state"]?.jsonPrimitive?.contentOrNull ?: ""
            cached = json.decodeFromString(ListSerializer(SourceStatus.serializer()), blobOf(a, "sources"))
            pushState()
        } catch (e: Exception) {
            // the channel's own state line says why; nothing else to say here
        }
    }

    private fun pushState() {
        val line = stateLine()
        try { onState(line) } catch (e: Exception) { Log.e("feed-remote", "state hook", e) }
        for (l in listeners) try { l.state(line) } catch (e: Exception) { Log.e("feed-remote", "state listener", e) }
    }

    private fun args(vararg kv: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in kv) when (v) {
            null -> {}
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Boolean -> put(k, v)
            else -> put(k, v.toString())
        }
    }

    private fun blobOf(a: RemoteWin.Answer, what: String): String =
        (a.blob ?: throw IllegalStateException("no $what came back")).toString(Charsets.UTF_8)

    override fun stateLine(): String = chanState.ifEmpty { hostState }
    override fun engineName(): String = "PC"
    override fun addListener(l: FeedProvider.Listener) {
        if (!listeners.addIfAbsent(l)) return
        try { l.state(stateLine()) } catch (e: Exception) { Log.e("feed-remote", "listener", e) }
    }
    override fun removeListener(l: FeedProvider.Listener) { listeners.remove(l) }
    override fun setFocused(sourceId: String?) {
        focused = sourceId
        scope.launch(Dispatchers.IO) { try { ch.request("focus", args("source" to sourceId)) } catch (e: Exception) { /* the state line says */ } }
        wakeFlag = true
    }
    override fun sources(): List<SourceStatus> = cached
    override fun items(sourceId: String, offset: Int, limit: Int): ItemPage =
        json.decodeFromString(ItemPage.serializer(), blobOf(ch.request("items", args("source" to sourceId, "offset" to offset, "limit" to limit)), "items"))
    override fun item(itemId: String): Item? {
        val a = ch.request("item", args("id" to itemId))
        return if (a.data["has"]?.jsonPrimitive?.booleanOrNull == true && a.blob != null) json.decodeFromString(Item.serializer(), a.blob.toString(Charsets.UTF_8)) else null
    }
    override fun article(itemId: String): Article =
        json.decodeFromString(Article.serializer(), blobOf(ch.request("article", args("id" to itemId)), "article"))
    override fun articleByUrl(url: String, title: String): Article =
        json.decodeFromString(Article.serializer(), blobOf(ch.request("articleUrl", args("url" to url, "title" to title)), "article"))
    override fun comic(itemId: String, width: Int, levels: Int, mode: LineArt): ComicPack {
        val a = ch.request("comic", args("id" to itemId, "width" to width, "levels" to levels, "mode" to mode.name))
        val blob = a.blob ?: throw IllegalStateException("no strip came back")
        val item = json.decodeFromString(Item.serializer(), a.data["item"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("no item in the answer"))
        val (strip, off) = FeedWire.stripFrom(a.data, "s", blob, 0)
        val bonus = if (a.data.containsKey("bW")) FeedWire.stripFrom(a.data, "b", blob, off).first else null
        return ComicPack(item, strip, bonus)
    }
    override fun image(url: String, width: Int, levels: Int, mode: LineArt): Strip {
        val a = ch.request("image", args("url" to url, "width" to width, "levels" to levels, "mode" to mode.name))
        return FeedWire.stripFrom(a.data, "s", a.blob ?: throw IllegalStateException("no image came back"), 0).first
    }
    override fun bingeIndex(sourceId: String): List<Episode> =
        json.decodeFromString(ListSerializer(Episode.serializer()), blobOf(ch.request("index", args("source" to sourceId)), "index"))
    override fun bingeEpisode(sourceId: String, num: Int, width: Int, levels: Int, mode: LineArt): EpisodePack {
        val a = ch.request("episode", args("source" to sourceId, "num" to num, "width" to width, "levels" to levels, "mode" to mode.name))
        val ep = json.decodeFromString(Episode.serializer(), a.data["episode"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("no episode in the answer"))
        return EpisodePack(ep, FeedWire.stripFrom(a.data, "s", a.blob ?: throw IllegalStateException("no strip came back"), 0).first)
    }
    override fun comments(itemId: String): List<Comment> =
        json.decodeFromString(ListSerializer(Comment.serializer()), blobOf(ch.request("comments", args("id" to itemId)), "comments"))
    override fun refresh(sourceId: String?) { ch.request("refresh", args("source" to sourceId)) }
    override fun browse(kind: SourceKind, name: String): SourceStatus {
        val st = json.decodeFromString(SourceStatus.serializer(), blobOf(ch.request("browse", args("kind" to kind.name, "name" to name)), "browse"))
        refreshSources()
        return st
    }
    override fun forget(sourceId: String) { ch.request("forget", args("source" to sourceId)); refreshSources() }
    override fun configure(fetchMs: Long, keepMs: Long, pcLossMs: Long) {
        ch.request("configure", args("fetchMs" to fetchMs, "keepMs" to keepMs, "pcLossMs" to pcLossMs))
    }

    /** One status refresh now (tests, a switch). */
    fun refreshNow() = refreshSources()

    override fun close() {
        running = false
        loop.cancel()
        ch.close()
    }
}

/**
 * FEED.md §3.6, verdict 15 — the phone reads the PC's engine while the
 * channel is up; when its staleness passes [pcLossMs] the phone's own engine
 * starts its pacer and serves; switchback is DELIBERATE (the root menu's
 * `Back to PC` row → [backToPc]). Listeners hear from the ACTIVE side only,
 * plus the channel's state line, and a switch announces every source as
 * changed so the open list reloads from the new side.
 */
class SwitchingFeedProvider(
    private val remote: RemoteFeedProvider,
    private val local: FeedProvider,
    private val scope: CoroutineScope,
    pcLossMs: Long = 60_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** The phone engine parks while the PC serves: pause(true) on a switchback, pause(false) on a switch. */
    private val onLocalActive: (Boolean) -> Unit = {},
    private val onSwitched: (toLocal: Boolean) -> Unit = {},
) : FeedProvider {
    @Volatile var pcLossMs: Long = pcLossMs
    @Volatile private var localActive = false
    private val listeners = CopyOnWriteArrayList<FeedProvider.Listener>()
    @Volatile private var running = true

    private val remoteListener = object : FeedProvider.Listener {
        override fun changed(sourceId: String, version: Long) {
            adoptFromPc()
            if (!localActive) for (l in listeners) l.changed(sourceId, version)
        }
        override fun state(line: String) { for (l in listeners) l.state(stateLine()) }
    }

    /** The PC's configured list reaches the phone engine (FEED.md §3.7). */
    private fun adoptFromPc() {
        val engine = local as? FeedEngine ?: return
        val cfgs = remote.sources().mapNotNull { it.cfg }
        if (cfgs.isNotEmpty()) engine.adopt(cfgs)
    }
    private val localListener = object : FeedProvider.Listener {
        override fun changed(sourceId: String, version: Long) { if (localActive) for (l in listeners) l.changed(sourceId, version) }
        override fun state(line: String) {}
    }

    init {
        remote.addListener(remoteListener)
        local.addListener(localListener)
        onLocalActive(false)
        scope.launch(Dispatchers.IO) {
            while (scope.isActive && running) {
                val down = remote.downSinceMs
                if (!localActive && down != 0L && clock() - down >= this@SwitchingFeedProvider.pcLossMs) switchToLocal()
                delay(1_000)
            }
        }
    }

    private fun switchToLocal() {
        Log.w("feed", "PC unreachable for ${(clock() - remote.downSinceMs) / 1000} s — the phone engine is fetching")
        onLocalActive(true)              // the engine resumes BEFORE it serves
        localActive = true
        try { onSwitched(true) } catch (e: Exception) { Log.e("feed", "switch hook", e) }
        for (l in listeners) {
            try { l.state(stateLine()) } catch (e: Exception) { Log.e("feed", "listener", e) }
            for (s in local.sources()) try { l.changed(s.id, s.version) } catch (e: Exception) { Log.e("feed", "listener", e) }
        }
    }

    override fun backToPc() {
        if (!localActive) return
        localActive = false
        onLocalActive(false)
        Log.i("feed", "back to the PC engine")
        remote.refreshNow()
        try { onSwitched(false) } catch (e: Exception) { Log.e("feed", "switch hook", e) }
        for (l in listeners) {
            try { l.state(stateLine()) } catch (e: Exception) { Log.e("feed", "listener", e) }
            for (s in remote.sources()) try { l.changed(s.id, s.version) } catch (e: Exception) { Log.e("feed", "listener", e) }
        }
    }

    private fun active(): FeedProvider = if (localActive) local else remote

    override fun fallbackActive(): Boolean = localActive
    override fun pcDownLine(): String {
        val down = remote.downSinceMs
        return if (down == 0L) "" else "PC down ${FeedFmt.dur((clock() - down) / 1000)}"
    }
    /** On the phone engine the link's staleness is not the window's staleness — the engine is healthy. */
    override fun stateLine(): String = if (localActive) local.stateLine() else remote.stateLine()
    override fun engineName(): String = if (localActive) "phone" else "PC"
    override fun addListener(l: FeedProvider.Listener) {
        if (!listeners.addIfAbsent(l)) return
        try { l.state(stateLine()) } catch (e: Exception) { Log.e("feed", "listener", e) }
    }
    override fun removeListener(l: FeedProvider.Listener) { listeners.remove(l) }
    override fun setFocused(sourceId: String?) { active().setFocused(sourceId) }
    override fun sources(): List<SourceStatus> = active().sources()
    override fun items(sourceId: String, offset: Int, limit: Int): ItemPage = active().items(sourceId, offset, limit)
    override fun item(itemId: String): Item? = active().item(itemId)
    override fun article(itemId: String): Article = active().article(itemId)
    override fun articleByUrl(url: String, title: String): Article = active().articleByUrl(url, title)
    override fun comic(itemId: String, width: Int, levels: Int, mode: LineArt): ComicPack = active().comic(itemId, width, levels, mode)
    override fun image(url: String, width: Int, levels: Int, mode: LineArt): Strip = active().image(url, width, levels, mode)
    override fun bingeIndex(sourceId: String): List<Episode> = active().bingeIndex(sourceId)
    override fun bingeEpisode(sourceId: String, num: Int, width: Int, levels: Int, mode: LineArt): EpisodePack = active().bingeEpisode(sourceId, num, width, levels, mode)
    override fun comments(itemId: String): List<Comment> = active().comments(itemId)
    override fun refresh(sourceId: String?) = active().refresh(sourceId)
    override fun browse(kind: SourceKind, name: String): SourceStatus = active().browse(kind, name)
    override fun forget(sourceId: String) = active().forget(sourceId)
    override fun configure(fetchMs: Long, keepMs: Long, pcLossMs: Long) {
        this.pcLossMs = pcLossMs.coerceAtLeast(5_000)
        try { local.configure(fetchMs, keepMs, pcLossMs) } catch (e: Exception) { Log.w("feed", "configure (phone): ${e.message}") }
        try { remote.configure(fetchMs, keepMs, pcLossMs) } catch (e: Exception) { /* the PC is told when it is back */ }
    }

    override fun close() {
        running = false
        remote.removeListener(remoteListener)
        local.removeListener(localListener)
        remote.close()
        local.close()
    }
}
