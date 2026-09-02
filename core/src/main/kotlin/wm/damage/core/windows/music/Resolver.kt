package wm.damage.core.windows.music

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/**
 * A fuzzy request → a playable queue, in three lanes (`MUSIC.md` §9.3 and
 * verdict 5). The semantics are G2CC's `server/src/resolver.ts`, read for
 * facts and written here as our own code; `Rules` already holds the
 * post-processing and `MusicDb` the SQL, so this file is the lane order, the
 * plan contract, and the honest detail line.
 *
 * **Lane 1 — deterministic, instant, no network.** random → exact artist →
 * exact album → exact playlist name → the tag vocabulary → plain token
 * search. Explicit asks (artist / album / playlist / search) keep 'spoken
 * word'; the discovery lanes (random / vocab) drop it. Albums and playlists
 * play whole, in their own order; everything else shuffles and caps.
 *
 * **Lane 2 — the language model** ([llm], `ClaudeOneShot`): a strict-JSON
 * plan over the library's REAL tag vocabulary, which lane 1's SQL then
 * executes. Short of the requested size it blends in lane 3's neighbours.
 *
 * **Lane 3 — the embedding** ([embed] + [qdrant]): the request as a vector,
 * cosine neighbours in rank order.
 *
 * Fallback discipline (the whole point of the ladder): a lane that fails
 * says so in one log line and hands off to the next — a model or an embedder
 * that stops answering must never mean no music. It ends at an honest empty,
 * never at a guess and never at YouTube. A DATABASE failure is different: a
 * thrown query propagates, because the window must render "the library is
 * unreachable" rather than "no match".
 */
class Resolver(
    private val db: MusicDb,
    private val qdrant: Qdrant?,
    private val embed: ((String) -> List<Double>)?,
    private val llm: ((system: String, payload: String) -> String)?,
    private val queueSize: Int = 25,
    /** Named in the lane-2 detail line so a queue says which model built it. */
    private val llmModel: String = "opus",
    /** Injectable so the shuffle is reproducible under test. */
    private val random: java.util.Random = java.util.Random(),
) : AskResolver {

    override fun ask(request: String): ResolvedQueue {
        val q = request.trim()
        val qLc = q.lowercase()
        val cap = maxOf(1, queueSize)
        val tokens = Rules.tokens(q)

        lane1(q, qLc, cap, tokens)?.let { return it }
        lane2(q, qLc, cap)?.let { return it }
        lane3(q, qLc, cap)?.let { return it }
        Log.i(TAG, "no lane answered \"$q\" — honest empty")
        return ResolvedQueue(emptyList(), "empty", q, "no library match for \"$q\" (all lanes)")
    }

    private fun opts(shuffle: Boolean, excludeSpoken: Boolean, cap: Int?, requestLc: String) =
        Rules.Opts(shuffle, excludeSpoken, cap, requestLc, random)

    // ================================================================= lane 1
    private fun lane1(q: String, qLc: String, cap: Int, tokens: List<String>): ResolvedQueue? {
        // ---- random: the literal words, or a request with no content left
        //      after the stopwords ("play something random" → no tokens).
        if (Rules.RANDOM_RE.matches(q) || tokens.isEmpty()) {
            // over-fetch: the exclusions and the dedupe shrink the set before the cap
            val rows = db.randomCands(cap * 4)
            val tracks = Rules.postProcess(rows, opts(shuffle = true, excludeSpoken = true, cap = cap, requestLc = qLc))
            return ResolvedQueue(tracks, "random", "random mix", "lane random: ${tracks.size} tracks")
        }

        // ---- exact artist: an explicit ask, so spoken word is KEPT
        db.candsByArtist(qLc).let { rows ->
            if (rows.isNotEmpty()) {
                val tracks = Rules.postProcess(rows, opts(shuffle = true, excludeSpoken = false, cap = cap, requestLc = qLc))
                return ResolvedQueue(tracks, "artist", q,
                    "lane artist \"$q\": ${rows.size} in library → ${tracks.size} queued${Rules.exclusionNote(rows.size, tracks.size)}")
            }
        }

        // ---- exact album: natural order, the whole album
        db.candsByAlbum(qLc).let { rows ->
            if (rows.isNotEmpty()) {
                val tracks = Rules.postProcess(rows, opts(shuffle = false, excludeSpoken = false, cap = null, requestLc = qLc))
                return ResolvedQueue(tracks, "album", q,
                    "lane album \"$q\": ${tracks.size} tracks in order${Rules.exclusionNote(rows.size, tracks.size)}")
            }
        }

        // ---- exact playlist name: stored order, the whole playlist
        db.candsByPlaylistName(qLc).let { rows ->
            if (rows.isNotEmpty()) {
                val tracks = Rules.postProcess(rows, opts(shuffle = false, excludeSpoken = false, cap = null, requestLc = qLc))
                return ResolvedQueue(tracks, "playlist", q,
                    "lane playlist \"$q\": ${tracks.size} tracks in order${Rules.exclusionNote(rows.size, tracks.size)}")
            }
        }

        // ---- the vocabulary lane: EVERY content token has to exist somewhere
        //      in the genre/style/mood vocabulary, then match all of them.
        //      `all` stops at the first miss — fewer queries, same verdict.
        if (tokens.all { db.vocabHas(it) }) {
            val rows = db.candsByVocab(tokens)
            if (rows.isNotEmpty()) {
                val tracks = Rules.postProcess(rows, opts(shuffle = true, excludeSpoken = true, cap = cap, requestLc = qLc))
                return ResolvedQueue(tracks, "vocab", tokens.joinToString(" "),
                    "lane vocab [${tokens.joinToString(", ")}]: ${rows.size} matched → ${tracks.size} queued${Rules.exclusionNote(rows.size, tracks.size)}")
            }
        }

        // ---- plain token search over artist/album/title/path: an explicit
        //      ask, so spoken word is KEPT (a direct title hit must never
        //      queue nothing). The tokens are the stopword-filtered ones.
        val rows = db.search(tokens.joinToString(" "), SEARCH_LIMIT)
        if (rows.isNotEmpty()) {
            val tracks = Rules.postProcess(rows, opts(shuffle = true, excludeSpoken = false, cap = cap, requestLc = qLc))
            return ResolvedQueue(tracks, "search", q,
                "lane search \"$q\": ${rows.size} hits → ${tracks.size} queued${Rules.exclusionNote(rows.size, tracks.size)}")
        }
        return null
    }

    // ================================================================= lane 2
    private fun lane2(q: String, qLc: String, cap: Int): ResolvedQueue? {
        val model = llm ?: run { Log.i(TAG, "lane llm unavailable (no model wired) — skipping to the embedding lane"); return null }

        // the vocabulary is a DB read: a failure here is a library failure
        val vocab = db.vocabFields()
        val payload = buildJsonObject {
            put("request", q)
            put("vocabulary", buildJsonObject {
                for (k in VOCAB_FIELDS) put(k, buildJsonArray { for (t in vocab[k].orEmpty()) add(JsonPrimitive(t)) })
            })
            put("orders", buildJsonArray { for (o in ORDERS) add(JsonPrimitive(o)) })
            put("defaultSize", cap)
        }.toString()

        // Only the model call is guarded — ANY failure of it is a lane
        // failure, logged once, and the embedding lane answers instead.
        val raw = try {
            model(PLAN_SYSTEM, payload)
        } catch (e: Exception) {
            Log.e(TAG, "lane llm failed (falling to the embedding lane)", e)
            return null
        }
        val plan = parsePlan(raw)
        if (plan == null) {
            Log.w(TAG, "lane llm returned an unusable plan (falling to the embedding lane): ${raw.trim().take(200)}")
            return null
        }
        Log.i(TAG, "lane llm plan: ${describe(plan)}")

        val size = clampSize(plan.size, cap)
        val rows = db.planCands(plan, maxOf(size * 4, PLAN_FLOOR))
        if (rows.isEmpty()) {
            Log.i(TAG, "lane llm plan matched nothing — falling to the embedding lane")
            return null
        }
        // A plan that explicitly asks for spoken word keeps it: the model's
        // stated intent outranks the discovery default.
        val plannedSpoken = (plan.genres + plan.styles).any { it in Rules.SPOKEN_TERMS }
        var tracks = Rules.postProcess(rows, opts(
            shuffle = plan.order == "shuffle", excludeSpoken = !plannedSpoken, cap = size, requestLc = qLc))
        if (tracks.isEmpty()) {
            Log.i(TAG, "lane llm plan matched ${rows.size} but nothing survived the exclusions — falling to the embedding lane")
            return null
        }

        var blended = 0
        if (tracks.size < size) {
            // the filter results are primary; the embedding lane fills to size
            val fillRaw = embeddingCands(q, size * 2)
            if (fillRaw != null && fillRaw.isNotEmpty()) {
                val fill = Rules.postProcess(fillRaw, opts(shuffle = false, excludeSpoken = true, cap = size * 2, requestLc = qLc))
                val byId = fillRaw.associateBy { it.id }
                val have = tracks.mapTo(HashSet()) { it.id }
                // one member per dupe cluster has to hold ACROSS the blend
                // boundary too, not only inside each half
                val usedClusters = db.clustersOf(tracks.map { it.id }).toHashSet()
                val out = ArrayList(tracks)
                for (t in fill) {
                    if (out.size >= size) break
                    if (!have.add(t.id)) continue
                    val cluster = byId[t.id]?.dupeCluster
                    if (cluster != null && !usedClusters.add(cluster)) continue
                    out.add(t)
                    blended++
                }
                tracks = out
            }
        }

        val blend = if (blended > 0) " ($blended embedding-blended)" else ""
        return ResolvedQueue(tracks, "llm", q,
            "lane llm ($llmModel): ${rows.size} matched → ${tracks.size} queued$blend${Rules.exclusionNote(rows.size, tracks.size)}")
    }

    // ================================================================= lane 3
    private fun lane3(q: String, qLc: String, cap: Int): ResolvedQueue? {
        val ranked = embeddingCands(q, cap) ?: return null
        if (ranked.isEmpty()) {
            Log.i(TAG, "lane embedding found no neighbours for \"$q\"")
            return null
        }
        // rank order is the whole point — no shuffle; a discovery lane, so
        // spoken word is excluded
        val tracks = Rules.postProcess(ranked, opts(shuffle = false, excludeSpoken = true, cap = cap, requestLc = qLc))
        if (tracks.isEmpty()) {
            Log.i(TAG, "lane embedding: ${ranked.size} neighbours, all excluded content")
            return null
        }
        return ResolvedQueue(tracks, "embedding", q, "lane embedding: top-${tracks.size} cosine neighbours (ranked)")
    }

    /**
     * [text] → vector → cosine neighbours → library rows in RANK order, NOT
     * yet post-processed. null = the lane is unwired or its subprocess/HTTP
     * half failed (said loudly); an empty list = it worked and found nothing.
     * The row fetch is a DB read and is deliberately unguarded.
     */
    private fun embeddingCands(text: String, want: Int): List<Rules.Cand>? {
        val e = embed ?: run { Log.i(TAG, "lane embedding unavailable (no embedder wired)"); return null }
        val qd = qdrant ?: run { Log.i(TAG, "lane embedding unavailable (no Qdrant wired)"); return null }
        val ids = try {
            val vector = e(text)
            if (vector.isEmpty()) throw IllegalStateException("the embedder returned an empty vector")
            qd.search(vector, maxOf(want * 3, NEIGHBOUR_FLOOR))
        } catch (ex: Exception) {
            Log.e(TAG, "lane embedding failed", ex)
            return null
        }
        if (ids.isEmpty()) return emptyList()
        val byId = db.candsByIds(ids).associateBy { it.id }
        // ids come back ranked; keep that order and drop anything the
        // collection knows but the library no longer has
        return ids.mapNotNull { byId[it] }
    }

    private fun describe(p: MusicDb.Plan): String = buildString {
        fun part(name: String, v: List<String>) { if (v.isNotEmpty()) append("$name=${v.joinToString("/")} ") }
        part("genres", p.genres); part("styles", p.styles); part("moods", p.moods)
        part("vocals", p.vocals); part("artists", p.artists); part("exclude", p.exclude)
        if (p.energyMin != null || p.energyMax != null) append("energy=${p.energyMin}..${p.energyMax} ")
        if (p.bpmMin != null || p.bpmMax != null) append("bpm=${p.bpmMin}..${p.bpmMax} ")
        append("order=${p.order}")
        if (p.size != null) append(" size=${p.size}")
    }

    companion object {
        private const val TAG = "music-resolver"

        /** resolver.ts's search LIMIT — far above any real ask at 3 k tracks. */
        const val SEARCH_LIMIT = 400

        /** A plan never fetches fewer than this many candidates before the
         *  exclusions and the dedupe cut it down. */
        const val PLAN_FLOOR = 100

        /** Nor asks Qdrant for fewer neighbours than this. */
        const val NEIGHBOUR_FLOOR = 50

        val ORDERS: List<String> = listOf("shuffle", "least_recent", "newest")

        /** The payload's vocabulary keys — the same names `MusicDb.vocabFields`
         *  returns, so a missing one reaches the model as an empty list rather
         *  than as an invented term. */
        val VOCAB_FIELDS: List<String> = listOf("genres", "styles", "moods", "vocals")

        /**
         * The lane-2 system prompt. The JSON schema is a contract with
         * `MusicDb.Plan` — every key here maps to a column the plan SQL can
         * actually filter on; the vocabulary rule is what keeps the model
         * from inventing tags the library does not carry (an invented term
         * ANDs the whole plan down to no rows).
         */
        val PLAN_SYSTEM: String =
            "You turn a fuzzy music request into a strict JSON filter over a personal music library. " +
                "Answer with ONE JSON object and nothing else — no prose, no markdown fences. " +
                "Schema (every key optional): " +
                "{\"genres\":[..],\"styles\":[..],\"moods\":[..],\"energy\":{\"min\":1,\"max\":10},\"bpm\":{\"min\":n,\"max\":n}," +
                "\"vocals\":[..],\"artists\":[..],\"exclude\":[..]," +
                "\"order\":\"shuffle\"|\"least_recent\"|\"newest\",\"size\":n}. " +
                "For genres, styles, moods, vocals and exclude use ONLY terms from the vocabulary lists in the " +
                "payload: those are the library's real tags, and any other term matches no rows at all. " +
                "A request such as \"something I have not heard in a while\" means order \"least_recent\". " +
                "Order defaults to shuffle. Leave out any key you have no basis for. " +
                "energy runs 1-10, where 10 is the most intense."

        /**
         * Parse and shape-validate a one-shot reply into a plan. Tolerates a
         * fenced block; anything else non-conforming, or a plan with no
         * filter in it at all, returns null and the caller falls to the next
         * lane. Public and pure so the tests can exercise it without ever
         * running a model.
         */
        fun parsePlan(raw: String): MusicDb.Plan? {
            var s = raw.trim()
            s = FENCE_OPEN.replace(s, "")
            s = FENCE_CLOSE.replace(s, "")
            val parsed = try {
                musicJson.parseToJsonElement(s.trim())
            } catch (e: Exception) {
                return null
            }
            val o = parsed as? JsonObject ?: return null

            val energy = number(o["energy"])?.let { e ->
                // a bare number is a target, not a bound: widen it by ±2
                maxOf(1.0, e - 2) to minOf(10.0, e + 2)
            } ?: range(o["energy"])
            val bpm = range(o["bpm"])
            val order = (o["order"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in ORDERS } ?: "shuffle"
            val size = number(o["size"])?.let { floor(it).toInt() }

            val plan = MusicDb.Plan(
                genres = strList(o["genres"]), styles = strList(o["styles"]), moods = strList(o["moods"]),
                energyMin = energy?.first?.roundToInt(), energyMax = energy?.second?.roundToInt(),
                bpmMin = bpm?.first, bpmMax = bpm?.second,
                vocals = strList(o["vocals"]), artists = strList(o["artists"]), exclude = strList(o["exclude"]),
                order = order, size = size,
            )
            // exclude alone is not a filter — it would select the whole
            // library minus a few tags, which is the random lane's job
            return if (plan.hasFilter) plan else null
        }

        /** An absent size means the caller's default; anything else is held
         *  to a sane queue length. */
        fun clampSize(size: Int?, fallback: Int): Int = if (size == null) fallback else size.coerceIn(1, 100)

        private val FENCE_OPEN = Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE)
        private val FENCE_CLOSE = Regex("\\s*```$")

        /** A JSON array of strings, lower-cased and trimmed, empties dropped.
         *  Normalizing BEFORE the emptiness check matters: `["  "]` is not a
         *  filter, and treating it as one builds a condition that can never
         *  be true. A non-array, or an array holding anything but strings,
         *  is no filter at all. */
        private fun strList(e: JsonElement?): List<String> {
            val a = e as? JsonArray ?: return emptyList()
            val out = ArrayList<String>(a.size)
            for (x in a) {
                val p = x as? JsonPrimitive ?: return emptyList()
                if (!p.isString) return emptyList()
                val t = p.content.lowercase().trim()
                if (t.isNotEmpty()) out.add(t)
            }
            return out
        }

        /** A finite number, cast at the boundary — a model may quote it. */
        private fun number(e: JsonElement?): Double? {
            val p = e as? JsonPrimitive ?: return null
            val d = (if (p.isString) p.content.trim().toDoubleOrNull() else p.doubleOrNull) ?: return null
            return if (d.isFinite()) d else null
        }

        /** `{"min":n,"max":n}` with either half optional; null when neither
         *  is a usable number. */
        private fun range(e: JsonElement?): Pair<Double?, Double?>? {
            val o = e as? JsonObject ?: return null
            val min = number(o["min"])
            val max = number(o["max"])
            return if (min == null && max == null) null else min to max
        }
    }
}
