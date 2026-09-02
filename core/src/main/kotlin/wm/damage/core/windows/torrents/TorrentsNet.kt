package wm.damage.core.windows.torrents

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
 * The Torrents provider over the §16.10 window channel (`{"t":"win","win":
 * "torrents"}` on the content port): [TorrentsService] adapts the host's
 * [LocalTorrentsProvider] to the wire; [RemoteTorrentsProvider] is the phone
 * side, polling `snap` on its own pacing (focused / idle) with a VERSION
 * cursor — an unchanged snapshot answers with no blob — and replaying the
 * events it missed by sequence within the host's epoch (a host restart's
 * new epoch is replayed from its start to a phone that was connected before;
 * a phone's first contact adopts the current sequence).
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class TorrentsService(private val p: TorrentsProvider) : WinService {

    override fun request(op: String, args: JsonObject): WinService.Answer {
        fun s(k: String): String = args[k]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("missing '$k'")
        fun i(k: String, d: Int) = args[k]?.jsonPrimitive?.intOrNull ?: d
        fun l(k: String, d: Long) = args[k]?.jsonPrimitive?.longOrNull ?: d
        fun b(k: String, d: Boolean) = args[k]?.jsonPrimitive?.booleanOrNull ?: d
        fun hashes(): List<String> = (args["hashes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw IllegalArgumentException("missing 'hashes'")
        return when (op) {
            "snap" -> {
                val v = l("v", -1)
                val since = l("since", -1)
                val ep = l("epoch", 0)
                val lp = p as? LocalTorrentsProvider
                if (lp != null) lp.setFocusedBy("remote", b("focused", false), l("pace", 2_000))
                else p.setFocused(b("focused", false), l("pace", 2_000))
                val snap = p.snapshot()
                val sameEpoch = snap != null && ep == snap.epoch
                val events = if (sameEpoch && since >= 0) p.eventsSince(since, ep) else emptyList()
                // a driver asking from before the ring's oldest entry has lost
                // events — say so (review 2026-09-01 P8), never pretend
                val oldest = lp?.oldestSeq() ?: 0L
                val truncated = sameEpoch && since >= 0 && oldest > 0 && since < oldest - 1
                if (truncated) Log.w("torrents", "driver asked for events since $since but the ring starts at $oldest — some were lost")
                // changed on a new EPOCH too (P6): a restarted host restarts
                // its versions, and version 1 of epoch 2 is not version 1 of epoch 1
                val changed = snap != null && (snap.version != v || snap.epoch != ep)
                val data = buildJsonObject {
                    put("state", p.stateLine())
                    put("has", snap != null)
                    put("changed", changed)
                    put("truncated", truncated)
                    if (snap != null) {
                        put("v", snap.version); put("epoch", snap.epoch); put("lastSeq", snap.lastSeq)
                    }
                    put("events", json.encodeToJsonElement(ListSerializer(TorrentEvent.serializer()), events))
                }
                val blob = if (changed) json.encodeToString(Snapshot.serializer(), snap!!).toByteArray(Charsets.UTF_8) else null
                WinService.Answer(data, blob)
            }
            "detail" -> WinService.Answer(blob = json.encodeToString(TransferDetail.serializer(), p.detail(s("hash"))).toByteArray(Charsets.UTF_8))
            "start" -> { p.start(hashes()); WinService.Answer() }
            "stop" -> { p.stop(hashes()); WinService.Answer() }
            "recheck" -> { p.recheck(hashes()); WinService.Answer() }
            "delete" -> { p.delete(hashes(), b("withFiles", false)); WinService.Answer() }
            "tlcats" -> WinService.Answer(blob = json.encodeToString(ListSerializer(TlCategory.serializer()), p.tlCategories()).toByteArray(Charsets.UTF_8))
            "tlbrowse" -> WinService.Answer(blob = json.encodeToString(TlPage.serializer(),
                p.tlBrowse(i("cat", 0).takeIf { it > 0 }, i("page", 1), args["sort"]?.jsonPrimitive?.contentOrNull ?: "added")).toByteArray(Charsets.UTF_8))
            "tlsearch" -> WinService.Answer(blob = json.encodeToString(TlPage.serializer(),
                p.tlSearch(s("q"), i("page", 1), args["sort"]?.jsonPrimitive?.contentOrNull ?: "added")).toByteArray(Charsets.UTF_8))
            "tldetail" -> WinService.Answer(blob = json.encodeToString(TlDetail.serializer(), p.tlDetail(s("fid"))).toByteArray(Charsets.UTF_8))
            "tladd" -> WinService.Answer(buildJsonObject { put("name", p.tlAdd(s("fid"), b("stopped", false))) })
            "tlaccount" -> WinService.Answer(json.encodeToJsonElement(TlAccount.serializer(), p.tlAccount()).jsonObject)
            "open" -> { p.openOnPc(s("target")); WinService.Answer() }
            else -> throw IllegalArgumentException("unknown torrents op '$op'")
        }
    }

    /** The driver's channel ended: its focus must not keep the host at the
     *  fast pace forever (review 2026-09-01 P4). */
    override fun detached() {
        (p as? LocalTorrentsProvider)?.setFocusedBy("remote", false, 0)
    }
}

class RemoteTorrentsProvider(
    host: String, port: Int, token: String, private val scope: CoroutineScope,
    private val idlePaceMs: Long = 15_000,
    private val onState: (String) -> Unit = {},
) : TorrentsProvider {

    private val listeners = CopyOnWriteArrayList<TorrentsProvider.Listener>()
    @Volatile private var snap: Snapshot? = null
    @Volatile private var hostState = ""
    @Volatile private var chanState = "connecting to $host"
    private var lastSeq = -1L
    private var epoch = 0L
    private val events = ArrayDeque<TorrentEvent>()
    @Volatile private var focused = false
    @Volatile private var pace = 2_000L
    @Volatile private var wakeFlag = false
    @Volatile private var running = true

    // declared AFTER every field it touches: RemoteWin launches its connect
    // loop in its constructor and the state hook can run at once (review
    // 2026-09-01 P7 — the tmux provider's ordering)
    private val ch = RemoteWin(host, port, token, "torrents", scope, onState = { s ->
        chanState = s
        // the channel just came up (or back): poll at once instead of waiting
        // out the idle pacing — a freshly attached phone wants its list now
        if (s.isEmpty()) wakeFlag = true
        pushState()
    })

    private val loop: Job = scope.launch(Dispatchers.IO) {
        while (scope.isActive && running) {
            wakeFlag = false              // before the poll (P5): a wake during it shortens the wait
            try {
                pollOnce()
            } catch (e: Exception) {
                // one answer this build cannot read must never end the loop
                // for the life of the provider (review 2026-09-01 P8)
                Log.w("torrents-remote", "host answer not understood: ${e.message}")
                hostState = "host answer not understood"
                pushState()
            }
            val until = System.currentTimeMillis() + (if (focused) pace else idlePaceMs)
            while (scope.isActive && running && !wakeFlag && System.currentTimeMillis() < until) delay(200)
        }
    }

    private fun args(vararg kv: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in kv) when (v) {
            null -> {}
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Boolean -> put(k, v)
            is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
            else -> throw IllegalArgumentException("arg $k: ${v::class}")
        }
    }

    /** One `snap` round: the host says whether the snapshot changed (blob)
     *  and which events happened since our sequence. Serialized: the loop
     *  and a caller's explicit poll must not interleave their cursor updates
     *  (R2-P1). */
    @Synchronized
    fun pollOnce() {
        val cur = snap
        val a = try {
            ch.request("snap", args("v" to (cur?.version ?: -1L), "since" to lastSeq, "epoch" to epoch,
                "focused" to focused, "pace" to pace))
        } catch (e: Exception) {
            return              // the channel's own state line says why (PC unreachable Ns)
        }
        hostState = a.data["state"]?.jsonPrimitive?.contentOrNull ?: ""
        val blob = a.data["changed"]?.jsonPrimitive?.booleanOrNull == true && a.blob != null
        var s = cur
        if (blob) {
            s = try {
                json.decodeFromString(Snapshot.serializer(), a.blob!!.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w("torrents-remote", "snapshot undecodable: ${e.message}")
                hostState = "host snapshot not understood"    // said, never silently stale (P8)
                null
            }
            if (s != null) snap = s
        }
        val evs = try {
            (a.data["events"] as? JsonArray)?.let { json.decodeFromJsonElement(ListSerializer(TorrentEvent.serializer()), it) }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w("torrents-remote", "events undecodable: ${e.message}")
            hostState = "host events not understood"
            emptyList()
        }
        if (a.data["truncated"]?.jsonPrimitive?.booleanOrNull == true) {
            Log.w("torrents-remote", "the host's event ring no longer holds everything since $lastSeq — some announcements were lost")
        }
        val hostEpoch = a.data["epoch"]?.jsonPrimitive?.longOrNull ?: 0L
        val hostLast = a.data["lastSeq"]?.jsonPrimitive?.longOrNull ?: -1L
        if (hostEpoch != 0L && hostEpoch != epoch) {
            if (epoch == 0L) {
                // our FIRST contact: take the host's current sequence, no
                // replay of its history — a phone app restart must not
                // re-show days of old announcements (TORRENTS.md §3.2)
                lastSeq = hostLast
            } else {
                // the HOST restarted under us (a new epoch): its fresh log
                // holds only what finished while it was down (the baseline,
                // deduplicated by its announced file) and what happened since
                // — replay it from the start on the next poll (review
                // 2026-09-01 P3), which the wake below makes immediate
                lastSeq = 0L
                wakeFlag = true
            }
            epoch = hostEpoch
        }
        val fresh = evs.filter { it.seq > lastSeq }.sortedBy { it.seq }
        for (e in fresh) {
            lastSeq = e.seq
            synchronized(events) {
                events.addLast(e)
                while (events.size > LocalTorrentsProvider.EVENT_CAP) events.removeFirst()
            }
        }
        pushState()
        for (l in listeners) {
            try {
                if (blob && s != null) l.snapshot(s)
                for (e in fresh) l.event(e)
            } catch (e: Exception) {
                Log.e("torrents-remote", "listener failed", e)
            }
        }
    }

    private fun pushState() {
        val line = stateLine()
        try { onState(line) } catch (e: Exception) { Log.e("torrents-remote", "state hook", e) }
        for (l in listeners) try { l.state(line) } catch (e: Exception) { Log.e("torrents-remote", "state listener", e) }
    }

    /** The channel's staleness first (PC unreachable Ns), else the host's own
     *  line (qBittorrent unreachable Ns), else healthy. */
    override fun stateLine(): String = chanState.ifEmpty { hostState }
    override fun snapshot(): Snapshot? = snap
    override fun addListener(l: TorrentsProvider.Listener) {
        listeners.add(l)
        snap?.let { s -> try { l.snapshot(s) } catch (e: Exception) { Log.e("torrents-remote", "listener", e) } }
        try { l.state(stateLine()) } catch (e: Exception) { Log.e("torrents-remote", "listener", e) }
    }
    override fun removeListener(l: TorrentsProvider.Listener) { listeners.remove(l) }
    override fun setFocused(focused: Boolean, paceMs: Long) {
        this.focused = focused
        this.pace = paceMs.coerceAtLeast(500)
        wakeFlag = true
    }
    override fun refresh() { wakeFlag = true }
    override fun eventsSince(seq: Long, epoch: Long): List<TorrentEvent> {
        if (epoch != this.epoch) return emptyList()
        synchronized(events) { return events.filter { it.seq > seq } }
    }

    private fun blobOf(a: RemoteWin.Answer, what: String): String =
        (a.blob ?: throw IllegalStateException("no $what came back")).toString(Charsets.UTF_8)

    override fun detail(hash: String): TransferDetail =
        json.decodeFromString(TransferDetail.serializer(), blobOf(ch.request("detail", args("hash" to hash)), "detail"))
    override fun start(hashes: List<String>) { ch.request("start", args("hashes" to hashes)); refresh() }
    override fun stop(hashes: List<String>) { ch.request("stop", args("hashes" to hashes)); refresh() }
    override fun recheck(hashes: List<String>) { ch.request("recheck", args("hashes" to hashes)); refresh() }
    override fun delete(hashes: List<String>, withFiles: Boolean) {
        ch.request("delete", args("hashes" to hashes, "withFiles" to withFiles)); refresh()
    }
    override fun tlCategories(): List<TlCategory> =
        json.decodeFromString(ListSerializer(TlCategory.serializer()), blobOf(ch.request("tlcats"), "categories"))
    override fun tlBrowse(categoryId: Int?, page: Int, sort: String): TlPage =
        json.decodeFromString(TlPage.serializer(), blobOf(ch.request("tlbrowse",
            args("cat" to (categoryId ?: 0), "page" to page, "sort" to sort)), "listing"))
    override fun tlSearch(query: String, page: Int, sort: String): TlPage =
        json.decodeFromString(TlPage.serializer(), blobOf(ch.request("tlsearch",
            args("q" to query, "page" to page, "sort" to sort)), "search results"))
    override fun tlDetail(fid: String): TlDetail =
        json.decodeFromString(TlDetail.serializer(), blobOf(ch.request("tldetail", args("fid" to fid)), "torrent page"))
    override fun tlAdd(fid: String, stopped: Boolean): String =
        ch.request("tladd", args("fid" to fid, "stopped" to stopped)).data["name"]?.jsonPrimitive?.contentOrNull ?: fid
    override fun tlAccount(): TlAccount =
        json.decodeFromJsonElement(TlAccount.serializer(), ch.request("tlaccount").data)
    override fun openOnPc(target: String) { ch.request("open", args("target" to target)) }

    override fun close() {
        running = false
        loop.cancel()
        ch.close()
    }
}
