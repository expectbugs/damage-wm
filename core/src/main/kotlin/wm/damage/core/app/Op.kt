package wm.damage.core.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (the build's spec §2 P1; `CONTRACT.md` §2.4, §4.4, §9.2): the op every interface sends the
 * PC and the answer it gets. The JSON forms here are the one definition the operation log (`OpLog`) writes and the
 * `app` lane (P2) carries. L1 C1 (the lead's ruling on L0, 2026-11-17): an op carries its result and [Op.at], the time
 * of the input on the device that made it; the latest input wins (`Merge`), so no op expects anything.
 */

/** How an op is named (C§2.4): a queued op carries its stream and `n`; a never-queued one its connection and `k`. L1 C1:
 *  the id only matches an answer to its op; the PC keeps no numbers (applying an op twice changes nothing). */
sealed class OpId {
    /** [stream] is a random 64-bit value minted when a client store first exists, carried as text (a browser's
     *  numbers hold 53 bits); [n] counts the stream's ops. */
    data class Queued(val stream: String, val n: Long) : OpId()

    /** Live, outbound and guarded ops, and every op into an outside program (S1-2 Q2, `Program.kt`): never queued. */
    data class Conn(val conn: Long, val k: Long) : OpId()
}

/**
 * One op (C§2.4): who sent it and its id, the window and action, the items it writes and what it made. [result] is
 * what the op made, never a recipe; [at] is the time of the input that made it, on the device's clock (ms since the
 * epoch); [base] is the device's cursor when it was made. The contract's `iface` field arrives with the interface
 * table (S1-5).
 */
data class Op(
    val device: String,
    val id: OpId,
    val window: String,
    val action: String,
    val targets: List<String>,
    val result: JsonElement = JsonNull,
    val params: JsonObject = JsonObject(emptyMap()),
    val at: Long,
    val base: Long? = null,
) {
    /** The queued numbering, or null for a never-queued op. */
    val queued: OpId.Queued? get() = id as? OpId.Queued
}

/** The answer to an op (C§4.4). */
sealed class Answer {
    /** The PC has the op in its order; for an op into an outside program (S1-2 Q2), the program's call returned —
     *  what it made travels beside the answer (`Ans.result`). */
    object Sent : Answer() {
        override fun toString() = "Sent"
    }

    /** The op is in the record: the state shows the latest input for each target, which is this op's unless a later
     *  input stands (L1 C1: nothing is said about it). [again] is read from older answers and never set. */
    data class Seen(val again: Boolean = false) : Answer()

    /** Refused, with the reason in words. [code]: `retry` (not taken; send the same op again), `invalid` /
     *  `unknown` (refused for good), `unsupported`; on the `app` lane also `contract` (not taken: the PC and this
     *  device are on different builds for the window; the op waits until they match) and `ended` (an op of a
     *  connection that has ended: never queued, never taken). */
    data class Refused(val reason: String, val code: String) : Answer()

    companion object {
        const val RETRY = "retry"
        const val INVALID = "invalid"
        const val UNKNOWN = "unknown"
        const val UNSUPPORTED = "unsupported"
        const val CONTRACT = "contract"
        const val ENDED = "ended"
        /** Build S1-1 P6 (C§10.4): the device's level does not reach the op, subscription or blob (refused for good). */
        const val LEVEL = "level"
        /** Build S1-2 Q2 (decisions A.7): an op into an outside program that the program refused or that threw;
         *  the reason is the program's own words. Never queued, never resent. */
        const val PROGRAM = "program"
    }
}

/** The JSON forms of [Op] and [Answer]: the one definition of what the log holds and the lane carries. A form that
 *  does not decode throws [IllegalArgumentException] naming the field. */
object OpJson {
    fun op(o: Op): JsonObject = buildJsonObject {
        put("device", o.device)
        when (val id = o.id) {
            is OpId.Queued -> { put("stream", id.stream); put("n", id.n) }
            is OpId.Conn -> { put("conn", id.conn); put("k", id.k) }
        }
        put("window", o.window)
        put("action", o.action)
        put("targets", buildJsonArray { for (t in o.targets) add(JsonPrimitive(t)) })
        put("result", o.result)
        put("at", o.at)
        if (o.params.isNotEmpty()) put("params", o.params)
        o.base?.let { put("base", it) }
    }

    /**
     * An op from its JSON form. L1 C1, tolerant of what 0.67 wrote (its `oplog.jsonl`, the phone's `queue.jsonl`, a
     * page not yet reloaded): `expect` is not read; an op without `at` takes [defaultAt] — the load time of a queue,
     * the arrival time on the PC — except a migrated place (`expect: {pre: stamp}`), which keeps its old stamp.
     */
    fun op(j: JsonObject, defaultAt: Long = 0L): Op {
        val id = when {
            j["stream"] != null -> OpId.Queued(str(j, "stream"), num(j, "n"))
            j["conn"] != null -> OpId.Conn(num(j, "conn"), num(j, "k"))
            else -> throw IllegalArgumentException("op: neither stream nor conn")
        }
        val targets = (j["targets"] as? JsonArray ?: throw IllegalArgumentException("op: targets"))
            .map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("op: a target") }
        return Op(
            device = str(j, "device"),
            id = id,
            window = str(j, "window"),
            action = str(j, "action"),
            targets = targets,
            result = j["result"] ?: JsonNull,
            params = j["params"] as? JsonObject ?: JsonObject(emptyMap()),
            at = longOf(j["at"]) ?: longOf((j["expect"] as? JsonObject)?.get("pre")) ?: defaultAt,
            base = (j["base"] as? JsonPrimitive)?.longOrNull,
        )
    }

    fun answer(a: Answer): JsonObject = buildJsonObject {
        when (a) {
            Answer.Sent -> put("a", "sent")
            is Answer.Seen -> { put("a", "seen"); if (a.again) put("again", true) }
            is Answer.Refused -> { put("a", "refused"); put("reason", a.reason); put("code", a.code) }
        }
    }

    fun answer(j: JsonObject): Answer = when (str(j, "a")) {
        "sent" -> Answer.Sent
        "seen" -> Answer.Seen((j["again"] as? JsonPrimitive)?.booleanOrNull == true)
        "refused" -> Answer.Refused(str(j, "reason"), str(j, "code"))
        // L1 C1: a 0.67 log line's loss answer — the op was handled and the state showed the standing value
        "lost" -> Answer.Seen()
        else -> throw IllegalArgumentException("answer: unknown kind ${j["a"]}")
    }

    internal fun str(j: JsonObject, k: String): String =
        (j[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw IllegalArgumentException("field $k: not text")

    internal fun num(j: JsonObject, k: String): Long =
        (j[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("field $k: not a whole number")

    /** A whole number, or null when [e] is absent or not one. */
    internal fun longOf(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
