package wm.damage.core.bastion

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import wm.damage.core.util.Http

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. This class lets the PC READ his own home hub (Home Assistant's REST API), so a
 * rule can raise a notice on his glasses and phone. It never writes to the hub.
 *
 * "Only reads" is held in code, not by convention (build W, R6 §2, P1):
 * - ONE request site, [get], and it passes the literal `"GET"` with no body; there is no method or
 *   body parameter anywhere. Lint RO001 (`tools/lint.py`) refuses a second `Http.request(` in this
 *   package, a non-literal method, a `"POST"`/`"PUT"`/`"PATCH"`/`"DELETE"` literal and any other
 *   HTTP client (`openConnection`, `HttpURLConnection`, `java.net.http`).
 * - The paths are `/api/` (is the hub up) and `/api/states/<entity>` for the entities the rules
 *   name, and nothing else: any other path throws [PathRefused] before a request is made. An entity
 *   id must match [ENTITY] (`domain.object_id`, lower case; the format is inferred), so no `/`,
 *   `?` or `..` can reach a path.
 * - A 401 or 403 LATCHES ([TokenRefused]): every later call throws it with no request, until a
 *   restart reads the token file again. The hub counts failed logins toward an address ban
 *   (inferred), and a 10 s poll would get there quickly; the shape of `QbtClient`'s login latch.
 *
 * The token lives only in this object's private header map and the `Authorization` header it
 * sends: never in a URL, an exception text, a log line or [toString] (this is a plain class for
 * that reason, not a data class). The constructor refuses a token with a space, a line break, a
 * control or a non-ASCII character, because the JDK's header check quotes the VALUE it refuses in
 * its exception text. Every exception here names the path and the HTTP status only — never a body
 * or a header.
 *
 * NO TIMEOUTS (`CLAUDE.md`): [Http] sets none; a stalled request is the watcher's liveness to
 * narrate (R6 §2 "Liveness without timeouts"). `Connection: close` keeps a request from reusing a
 * pooled socket to a hub that has since powered off (inferred: such a socket can sit in TCP
 * retransmission for minutes; the JDK lets `Connection: close` through its restricted headers).
 */
class HaReader(baseUrl: String, token: String, entities: Collection<String>) {

    /** The hub has no such entity (404): renamed or removed. The watcher raises `watcher.entity.<id>`. */
    class NotFound(val entity: String) : IOException("hub: no entity $entity (HTTP 404)")

    /** The hub refused the token (401 or 403). Latched until restart. */
    class TokenRefused(val status: Int) :
        IOException("hub refused the token (HTTP $status) - check the token file and restart")

    /** A path outside `/api/` and the rules' entities: thrown before any request. */
    class PathRefused(path: String) :
        IllegalArgumentException("hub path refused (only /api/ and the rules' entities are read): $path")

    private val base = baseUrl.trimEnd('/')
    private val headers: Map<String, String>
    /** Every path this reader may ask for: [HEALTH] and one [STATES] path per rule entity. */
    private val allowed: Set<String>
    /** 0 = no refusal seen; else the status of the 401/403 that latched (see the class comment). */
    @Volatile private var refusedStatus = 0
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(tokenTextOk(token)) {
            "hub token refused: empty, or a space, line break, control or non-ASCII character in the text (one token expected)"
        }
        val bad = entities.filterNot { ENTITY.matches(it) }
        require(bad.isEmpty()) { "hub entity ids refused (expected domain.object_id): ${bad.joinToString()}" }
        allowed = entities.mapTo(HashSet()) { STATES + it }.also { it.add(HEALTH) }
        headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json", "Connection" to "close")
    }

    /** `GET /api/`: returns when the hub answers 200; throws otherwise. */
    fun health() {
        get(HEALTH)
    }

    /** `GET /api/states/<entity>` → its state, `last_reported` and `last_updated` (both strings, compared as
     *  given: the PC times staleness on its own clock, R6 §2 "Kinds"). */
    fun state(entity: String): HaState {
        val path = STATES + entity
        val r = get(path)
        val o = try {
            json.parseToJsonElement(r.text()) as JsonObject
        } catch (e: Exception) {
            throw IOException("hub $path: the answer is not a JSON object (${e::class.simpleName})")
        }
        val state = (o["state"] as? JsonPrimitive)?.contentOrNull
            ?: throw IOException("hub $path: the answer carries no state")
        val answered = (o["entity_id"] as? JsonPrimitive)?.contentOrNull
        if (answered != null && answered != entity) throw IOException("hub $path: the answer names another entity")
        return HaState(
            entity, state,
            (o["last_reported"] as? JsonPrimitive)?.contentOrNull,
            (o["last_updated"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    /** THE request site (lint RO001: the only `Http.request(` in this package, method the literal GET). */
    private fun get(path: String): Http.Response {
        if (refusedStatus != 0) throw TokenRefused(refusedStatus)
        if (path !in allowed) throw PathRefused(path)
        val r = Http.request("GET", base + path, headers)
        when (r.status) {
            200 -> return r
            401, 403 -> {
                refusedStatus = r.status
                throw TokenRefused(r.status)
            }
            404 -> if (path.startsWith(STATES)) throw NotFound(path.substring(STATES.length))
        }
        throw IOException("hub $path: HTTP ${r.status}")
    }

    override fun toString(): String = "HaReader($base, ${allowed.size - 1} entities)"

    /** What [readTokenFile] found. [Ok.toString] says the length, never the text. */
    sealed class TokenRead {
        class Ok(val token: String) : TokenRead() {
            override fun toString(): String = "Ok(${token.length} characters)"
        }
        /** No file at the path. */
        object Missing : TokenRead()
        /** The file holds only white space. */
        object Empty : TokenRead()
        /** The file could not be read; [cls] is the exception's CLASS name only (its text may quote content). */
        class Unreadable(val cls: String) : TokenRead() {
            override fun toString(): String = "Unreadable($cls)"
        }
        /** The trimmed text is not one token: a space, a line break, a control or a non-ASCII character inside
         *  it (a pasted `Bearer …` line, two lines). Refused here because the JDK quotes a header value it
         *  refuses in its exception text. */
        object Malformed : TokenRead()
    }

    companion object {
        const val HEALTH = "/api/"
        const val STATES = "/api/states/"
        /** A hub entity id: `domain.object_id`, lower-case letters, digits and `_` (the format is inferred). */
        val ENTITY = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")

        /** Printable ASCII without a space: what a bearer token is made of (the hub's are JWTs). */
        private fun tokenTextOk(t: String): Boolean = t.isNotEmpty() && t.all { it in '!'..'~' }

        /**
         * Reads the hub's token from a file Adam already keeps (dec. A.6; config key `haTokenFile`). The text is
         * trimmed (a leading byte-order mark too). Never logs, and nothing it returns or throws carries the
         * text: the caller says the PATH and the case.
         */
        fun readTokenFile(path: Path): TokenRead {
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: NoSuchFileException) {
                return TokenRead.Missing
            } catch (e: Exception) {
                return TokenRead.Unreadable(e::class.simpleName ?: "Exception")
            }
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                return TokenRead.Unreadable(e::class.simpleName ?: "CharacterCodingException")
            }
            val t = text.trimStart('\uFEFF').trim()
            if (t.isEmpty()) return TokenRead.Empty
            if (!tokenTextOk(t)) return TokenRead.Malformed
            return TokenRead.Ok(t)
        }
    }
}

/** One entity's state as the hub answered it. `lastReported`/`lastUpdated` are the hub's own strings. */
data class HaState(val entity: String, val state: String, val lastReported: String?, val lastUpdated: String?)
