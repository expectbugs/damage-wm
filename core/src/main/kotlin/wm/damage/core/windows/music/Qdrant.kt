package wm.damage.core.windows.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.util.Http

/**
 * The `g2cc_music` collection over Qdrant's HTTP API (`MUSIC.md` §9.1: point
 * id == track id, payload track_id/artist/title, 384-dim cosine). Three
 * calls: search (the embedding lane), recommend (radio), retrieve (seed
 * hygiene — recommend refuses the WHOLE call when any positive id has no
 * point, and a fresh grab is playable long before its embed pass lands).
 * Endpoint shapes read from Qdrant's REST reference; NO TIMEOUTS (Qdrant
 * answers in milliseconds on loopback; a stalled daemon is the caller's
 * liveness surface).
 */
class Qdrant(baseUrl: String, private val collection: String) {
    private val base = baseUrl.trimEnd('/')

    private fun post(path: String, body: JsonObject): JsonObject {
        val r = Http.request("POST", "$base/collections/$collection$path",
            mapOf("Accept" to "application/json"), body.toString().toByteArray(Charsets.UTF_8), "application/json")
        if (r.status != 200) throw IllegalStateException("qdrant $path HTTP ${r.status}: ${r.text().take(200)}")
        return musicJson.parseToJsonElement(r.text()).jsonObject
    }

    private fun ids(o: JsonObject): List<Int> =
        (o["result"] as? JsonArray)?.mapNotNull { it.jsonObject["payload"]?.jsonObject?.get("track_id")?.jsonPrimitive?.intOrNull } ?: emptyList()

    /** Cosine neighbours of [vector], ranked; track ids from the payload. */
    fun search(vector: List<Double>, limit: Int): List<Int> = ids(post("/points/search", buildJsonObject {
        put("vector", buildJsonArray { for (v in vector) add(JsonPrimitive(v)) })
        put("limit", limit)
        put("with_payload", true)
    }))

    /** Which of [ids] exist as points. */
    fun present(ids: List<Int>): List<Int> {
        if (ids.isEmpty()) return emptyList()
        val o = post("/points", buildJsonObject { put("ids", buildJsonArray { for (i in ids) add(JsonPrimitive(i)) }) })
        return (o["result"] as? JsonArray)?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull } ?: emptyList()
    }

    /** Nearest neighbours of the [positive] points (ranked). */
    fun recommend(positive: List<Int>, limit: Int): List<Int> {
        if (positive.isEmpty()) return emptyList()
        return ids(post("/points/recommend", buildJsonObject {
            put("positive", buildJsonArray { for (i in positive) add(JsonPrimitive(i)) })
            put("limit", limit)
            put("with_payload", true)
        }))
    }

    /** Collection facts for `--music-check`: points and the vector size. */
    fun info(): Pair<Long, Int> {
        val r = Http.request("GET", "$base/collections/$collection", mapOf("Accept" to "application/json"))
        if (r.status != 200) throw IllegalStateException("qdrant collection HTTP ${r.status}: ${r.text().take(200)}")
        val res = musicJson.parseToJsonElement(r.text()).jsonObject["result"]?.jsonObject ?: throw IllegalStateException("qdrant: no result")
        val points = res["points_count"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
        val size = res["config"]?.jsonObject?.get("params")?.jsonObject?.get("vectors")?.jsonObject?.get("size")?.jsonPrimitive?.intOrNull ?: 0
        return points to size
    }
}
