package wm.damage.core.windows.torrents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import wm.damage.core.util.Http

/**
 * qBittorrent Web API **2.11** (the 5.x line) over loopback. Every path and
 * every key here was read from the 5.1.4 source
 * (`src/webui/api/torrentscontroller.cpp`, `synccontroller.cpp`,
 * `serialize/serialize_torrent.h`, `appcontroller.cpp`, `authcontroller.cpp`)
 * — 5.x renamed pause/resume to **stop/start** and `pausedX` to `stoppedX`;
 * the older names do not exist on this server.
 *
 * Credentials are optional: on beardos the WebUI bypasses auth for loopback
 * clients (`WebUI\LocalHostAuth=false`), so [user] is empty and a 403 is
 * reported as such. The `Referer` header satisfies the CSRF check for
 * requests that carry it; the host-header validation accepts the loopback
 * address itself.
 */
class QbtClient(baseUrl: String, private val user: String = "", private val pass: String = "") {

    class QbtException(msg: String) : java.io.IOException(msg)

    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var sid: String? = null

    private fun call(path: String, form: Map<String, String>? = null,
        multipart: Pair<String, ByteArray>? = null, retry: Boolean = true): Http.Response {
        val url = "$base/api/v2/$path"
        val headers = HashMap<String, String>()
        headers["Referer"] = base
        sid?.let { headers["Cookie"] = "SID=$it" }
        val r = when {
            multipart != null -> Http.request("POST", url, headers, multipart.second, multipart.first)
            form != null -> Http.request("POST", url, headers,
                Http.formEncode(form).toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded")
            else -> Http.request("GET", url, headers)
        }
        if (r.status == 403 && retry && user.isNotEmpty()) {
            login()
            return call(path, form, multipart, retry = false)
        }
        if (r.status != 200) {
            throw QbtException("qBittorrent $path: HTTP ${r.status}" +
                r.text().lineSequence().firstOrNull()?.take(120)?.let { if (it.isEmpty()) "" else " — $it" })
        }
        return r
    }

    /** `auth/login`: username/password → the SID cookie. Five failures ban
     *  the IP for an hour (the server's rule), so this is never retried in a loop. */
    fun login() {
        val r = Http.request("POST", "$base/api/v2/auth/login", mapOf("Referer" to base),
            Http.formEncode(mapOf("username" to user, "password" to pass)).toByteArray(Charsets.UTF_8),
            "application/x-www-form-urlencoded")
        if (r.status != 200 || r.text().trim() != "Ok.") throw QbtException("qBittorrent login refused (HTTP ${r.status}: ${r.text().trim().take(60)})")
        sid = r.setCookies().firstOrNull { it.startsWith("SID=") }?.substringAfter("SID=")?.substringBefore(';')
            ?: throw QbtException("qBittorrent login answered Ok. without a SID cookie")
    }

    fun webapiVersion(): String = call("app/webapiVersion").text().trim()
    fun version(): String = call("app/version").text().trim()

    /** `sync/maindata` with rid=0 — always a FULL update, deliberately: one
     *  request carries the transfer list and the session line, and a full
     *  answer for a few dozen torrents on loopback costs nothing while the
     *  incremental merge would be a bug surface (TORRENTS.md §4). */
    fun maindata(): JsonObject = parseObject(call("sync/maindata?rid=0").text(), "sync/maindata")

    private fun parseObject(s: String, what: String): JsonObject = try {
        json.parseToJsonElement(s).jsonObject
    } catch (e: Exception) {
        throw QbtException("qBittorrent $what: not a JSON object (${s.take(80)})")
    }

    /** The transfer list + session stats from one maindata answer. */
    fun transfers(version: String = ""): Pair<List<Transfer>, SessionStats> {
        val md = maindata()
        val out = ArrayList<Transfer>()
        val torrents = md["torrents"]?.jsonObject ?: JsonObject(emptyMap())
        for ((hash, v) in torrents) {
            val o = v as? JsonObject ?: continue
            out.add(transferOf(hash, o))
        }
        val ss = md["server_state"]?.jsonObject ?: JsonObject(emptyMap())
        val stats = SessionStats(
            dlSpeed = ss.long("dl_info_speed"), upSpeed = ss.long("up_info_speed"),
            dlSession = ss.long("dl_info_data"), upSession = ss.long("up_info_data"),
            allDl = ss.long("alltime_dl"), allUl = ss.long("alltime_ul"),
            freeSpace = ss.long("free_space_on_disk", -1), ratio = ss.str("global_ratio"),
            peers = ss.int("total_peer_connections"), status = ss.str("connection_status"),
            version = version,
        )
        return out to stats
    }

    /** One `torrents/info` row (also the shape of a maindata torrent entry,
     *  keyed by hash) → [Transfer]. Keys per serialize_torrent.h. */
    fun transferOf(hash: String, o: JsonObject): Transfer = Transfer(
        hash = o.str("hash").ifEmpty { hash },
        name = o.str("name"),
        state = o.str("state"),
        progress = o.dbl("progress"),
        size = o.long("size"),
        downloaded = o.long("downloaded"),
        uploaded = o.long("uploaded"),
        dlSpeed = o.long("dlspeed"),
        upSpeed = o.long("upspeed"),
        eta = o.long("eta"),
        ratio = o.dbl("ratio"),
        seeds = o.int("num_seeds"),
        seedsTotal = o.int("num_complete"),
        peers = o.int("num_leechs"),
        peersTotal = o.int("num_incomplete"),
        addedOn = o.long("added_on"),
        completedOn = o.long("completion_on"),
        seedingTime = o.long("seeding_time"),
        savePath = o.str("save_path"),
        contentPath = o.str("content_path"),
        category = o.str("category"),
        tags = o.str("tags"),
        tracker = o.str("tracker"),
    )

    fun detail(hash: String): TransferDetail {
        val props = parseObject(call("torrents/properties?hash=$hash").text(), "torrents/properties")
        val files = ArrayList<TFile>()
        val fa = try {
            json.parseToJsonElement(call("torrents/files?hash=$hash").text()).jsonArray
        } catch (e: QbtException) {
            throw e
        } catch (e: Exception) {
            throw QbtException("qBittorrent torrents/files: not a JSON array")
        }
        for (f in fa) {
            val o = f as? JsonObject ?: continue
            files.add(TFile(o.str("name"), o.long("size"), o.dbl("progress"), o.int("priority")))
        }
        val trackers = ArrayList<String>()
        try {
            for (t in json.parseToJsonElement(call("torrents/trackers?hash=$hash").text()).jsonArray) {
                val o = t as? JsonObject ?: continue
                val u = o.str("url")
                if (u.startsWith("http") || u.startsWith("udp")) trackers.add(u)
            }
        } catch (e: Exception) {
            // trackers are decoration on the detail page; their absence is not a failure
        }
        return TransferDetail(hash, files, comment = props.str("comment"),
            createdOn = props.long("creation_date"), pieces = props.int("pieces_num"),
            pieceSize = props.long("piece_size"), trackers = trackers)
    }

    private fun hashes(hs: List<String>): Map<String, String> = mapOf("hashes" to hs.joinToString("|"))

    fun start(hs: List<String>) { call("torrents/start", hashes(hs)) }
    fun stop(hs: List<String>) { call("torrents/stop", hashes(hs)) }
    fun recheck(hs: List<String>) { call("torrents/recheck", hashes(hs)) }
    fun delete(hs: List<String>, withFiles: Boolean) {
        call("torrents/delete", hashes(hs) + ("deleteFiles" to withFiles.toString()))
    }

    /** `torrents/add` with the torrent file as multipart data; the server
     *  answers `Ok.` or `Fails.` (the latter also for a duplicate). */
    fun add(torrent: ByteArray, fileName: String, stopped: Boolean, savePath: String? = null) {
        val fields = LinkedHashMap<String, String>()
        fields["stopped"] = stopped.toString()
        if (!savePath.isNullOrEmpty()) fields["savepath"] = savePath
        val mp = Http.multipart(fields, "torrents", fileName, torrent, "application/x-bittorrent")
        val r = call("torrents/add", multipart = mp)
        val t = r.text().trim()
        if (t != "Ok.") throw QbtException("qBittorrent refused the torrent ($t)")
    }

    // ------------------------------------------------------------ JSON helpers
    private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)
    private fun JsonObject.str(k: String): String = prim(k)?.contentOrNull ?: ""
    private fun JsonObject.long(k: String, d: Long = 0): Long =
        prim(k)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: d
    private fun JsonObject.int(k: String, d: Int = 0): Int =
        prim(k)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: d
    private fun JsonObject.dbl(k: String, d: Double = 0.0): Double = prim(k)?.doubleOrNull ?: d
    @Suppress("unused")
    private fun JsonObject.bool(k: String, d: Boolean = false): Boolean = prim(k)?.booleanOrNull ?: d
    @Suppress("unused")
    private fun JsonElement.asObject(): JsonObject = this.jsonObject
}
