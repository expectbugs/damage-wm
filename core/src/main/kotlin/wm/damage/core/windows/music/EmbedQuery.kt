package wm.damage.core.windows.music

import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import wm.damage.core.util.Log

/**
 * The resolver's lane-3 embedder (`MUSIC.md` §9.3): request text in, the
 * 384-dim vector out, produced by the SAME pinned model that built the
 * Qdrant collection. Query and collection must share the model or the cosine
 * ranking means nothing — which is why this runs the library's own
 * `enrich.embed_query` module rather than embedding anything ourselves.
 *
 * Interface facts from `/home/user/G2CC/audio/enrich/embed_query.py`
 * (read-only, our own code here): the module reads the raw request from
 * stdin, writes ONLY a JSON array of numbers to stdout (its model-load
 * chatter is diverted to stderr on purpose), and exits non-zero with the
 * reason on stderr. Run it from the `audio/` directory as `-m
 * enrich.embed_query` so the package resolves.
 *
 * ~3.5 s cold, model-load dominated (measured by G2CC 2026-08-05). That is
 * fine and there is NO TIMEOUT: this is the seconds-class fallback lane, and
 * a stalled embedder is the caller's liveness surface, not a deadline here.
 */
class EmbedQuery(private val python: String, private val audioDir: Path) {

    /** Blocks for as long as the embedder takes. Throws with the reason on a
     *  non-zero exit or on anything that is not a vector — never a silent
     *  empty answer, which the ranking could not tell from "no neighbours". */
    fun embed(text: String): List<Double> {
        val r = MusicProc.run(listOf(python, "-m", "enrich.embed_query"), audioDir.toFile(), text, tag = "embed")
        if (r.stderr.isNotBlank()) Log.d(TAG, "embed_query stderr:\n${r.stderr}")
        if (r.code != 0) {
            Log.e(TAG, "embed_query exit ${r.code}; stderr:\n${r.stderr}")
            throw IllegalStateException("embed_query exit ${r.code}: ${MusicProc.head(r.stderr)}")
        }
        val body = r.stdout.trim()
        if (body.isEmpty()) throw IllegalStateException("embed_query returned nothing (exit 0): ${MusicProc.head(r.stderr)}")
        val parsed = try {
            musicJson.parseToJsonElement(body)
        } catch (e: Exception) {
            throw IllegalStateException("embed_query output is not JSON (${e.message}): ${MusicProc.head(body)}")
        }
        val arr = parsed as? JsonArray ?: throw IllegalStateException("embed_query returned a non-vector: ${MusicProc.head(body)}")
        if (arr.isEmpty()) throw IllegalStateException("embed_query returned an empty vector")
        val v = arr.map { el ->
            val p = el as? JsonPrimitive ?: throw IllegalStateException("embed_query returned a non-vector (a nested value)")
            // cast at the boundary: a JSON number may arrive quoted
            val d = (if (p.isString) p.content.trim().toDoubleOrNull() else p.doubleOrNull)
                ?: throw IllegalStateException("embed_query returned a non-number in the vector: ${p.content.take(40)}")
            if (!d.isFinite()) throw IllegalStateException("embed_query returned a non-finite number in the vector: $d")
            d
        }
        Log.d(TAG, "embed_query: ${v.size}-dim vector for ${text.length} chars")
        return v
    }

    companion object {
        private const val TAG = "music-embed"
    }
}
