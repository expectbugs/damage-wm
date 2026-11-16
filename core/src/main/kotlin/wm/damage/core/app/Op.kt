package wm.damage.core.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (the build's spec §2 P1; `CONTRACT.md` §2.4, §4.4, §9.2): the op every interface sends the
 * PC, what it expects to find, and the answer it gets. The JSON forms here are the one definition the operation log
 * (`OpLog`) writes and the `app` lane (P2) carries.
 */

/** How an op is numbered (C§2.4): a queued op carries its stream and `n`; a never-queued one its connection and `k`. */
sealed class OpId {
    /** [stream] is a random 64-bit value minted when a client store first exists, carried as text (a browser's
     *  numbers hold 53 bits); [n] starts at 1 and never repeats in the stream. */
    data class Queued(val stream: String, val n: Long) : OpId()

    /** Live, outbound and guarded ops, and every op into an outside program (S1-2 Q2, `Program.kt`): no place in a
     *  stream or its gap check. */
    data class Conn(val conn: Long, val k: Long) : OpId()
}

/** What an op expects to find (C§2.4's table; dec. A.3 and A.9). */
sealed class Expect {
    /** The value the device saw (a register, a position); [v] is `null` for "nothing there". */
    data class Value(val v: JsonElement) : Expect()

    /** The revision seen (an order move, a removal, a whole collection). */
    data class Rev(val r: Long) : Expect()

    /** A tick, untick or snooze of a recurring item: the occurrence that was open (the value's `occ` field). */
    data class Occurrence(val o: String) : Expect()

    /** A place migrated from a host's records before Reader's conversion, with its old stamp (dec. A.3). */
    data class PreConversion(val at: Long) : Expect()

    /** Arrival order, never a loss: the "most recent" family (dec. A.9), and every create. */
    object Any : Expect() {
        override fun toString() = "Any"
    }
}

/**
 * One op (C§2.4): who sent it and its number, the window and action, the items it writes, what it made and what it
 * expected. [result] is what the op made, never a recipe; [base] is the device's cursor when it was made. The
 * contract's `iface` field arrives with the interface table (S1-5).
 */
data class Op(
    val device: String,
    val id: OpId,
    val window: String,
    val action: String,
    val targets: List<String>,
    val result: JsonElement = JsonNull,
    val expect: Expect = Expect.Any,
    val params: JsonObject = JsonObject(emptyMap()),
    val base: Long? = null,
) {
    /** The queued numbering, or null for a never-queued op. */
    val queued: OpId.Queued? get() = id as? OpId.Queued
}

/** One target the op did not change (C§2.4): the standing value, who set it and when, and the value it would have
 *  made. [gone] = the item was removed (the "edit of a removed item" row). */
data class Loss(
    val item: String,
    val standing: JsonElement,
    val by: String,
    val at: Long,
    val mine: JsonElement,
    val gone: Boolean = false,
)

/** The answer to an op (C§4.4). */
sealed class Answer {
    /** The PC has the op in its order; for an op into an outside program (S1-2 Q2), the program's call returned —
     *  what it made travels beside the answer (`Ans.result`). */
    object Sent : Answer() {
        override fun toString() = "Sent"
    }

    /** The state shows the effect. [again] = the op was handled before and its answer is no longer held: "already
     *  handled", read the current state (C§2.4 numbering). */
    data class Seen(val again: Boolean = false) : Answer()

    /** Refused, with the reason in words. [code]: `retry` (not taken; send the same op again), `gap` (send from
     *  the number named), `invalid` / `unknown` (taken and refused for good), `unsupported`; on the `app` lane also
     *  `contract` (not taken: the PC and this device are on different builds for the window; the op waits until
     *  they match) and `ended` (an op of a connection that has ended: never queued, never taken). */
    data class Refused(val reason: String, val code: String) : Answer()

    /** At least one target was not changed; every target that could apply did. */
    data class Lost(val losses: List<Loss>) : Answer() {
        init {
            require(losses.isNotEmpty()) { "a Lost answer names at least one loss" }
        }
        val standing: JsonElement get() = losses.first().standing
        val by: String get() = losses.first().by
        val at: Long get() = losses.first().at
    }

    companion object {
        const val RETRY = "retry"
        const val GAP = "gap"
        const val INVALID = "invalid"
        const val UNKNOWN = "unknown"
        const val UNSUPPORTED = "unsupported"
        const val CONTRACT = "contract"
        const val ENDED = "ended"
        /** Build S1-1 P6 (C§10.4): the device's level does not reach the op, subscription or blob; for a queued op
         *  the number is taken (refused for good), so the stream goes on. */
        const val LEVEL = "level"
        /** Build S1-2 Q2 (decisions A.7): an op into an outside program that the program refused or that threw;
         *  the reason is the program's own words. Never queued, never resent. */
        const val PROGRAM = "program"
    }
}

/** The JSON forms of [Op], [Expect] and [Answer]: the one definition of what the log holds and the lane carries. A
 *  form that does not decode throws [IllegalArgumentException] naming the field. */
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
        put("expect", expect(o.expect))
        if (o.params.isNotEmpty()) put("params", o.params)
        o.base?.let { put("base", it) }
    }

    fun op(j: JsonObject): Op {
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
            expect = expect(j["expect"] as? JsonObject ?: throw IllegalArgumentException("op: expect")),
            params = j["params"] as? JsonObject ?: JsonObject(emptyMap()),
            base = (j["base"] as? JsonPrimitive)?.longOrNull,
        )
    }

    fun expect(e: Expect): JsonObject = buildJsonObject {
        when (e) {
            is Expect.Value -> put("value", e.v)
            is Expect.Rev -> put("rev", e.r)
            is Expect.Occurrence -> put("occ", e.o)
            is Expect.PreConversion -> put("pre", e.at)
            Expect.Any -> put("any", true)
        }
    }

    fun expect(j: JsonObject): Expect = when {
        j.containsKey("value") -> Expect.Value(j["value"]!!)
        j.containsKey("rev") -> Expect.Rev(num(j, "rev"))
        j.containsKey("occ") -> Expect.Occurrence(str(j, "occ"))
        j.containsKey("pre") -> Expect.PreConversion(num(j, "pre"))
        (j["any"] as? JsonPrimitive)?.booleanOrNull == true -> Expect.Any
        else -> throw IllegalArgumentException("expect: none of value, rev, occ, pre, any")
    }

    fun answer(a: Answer): JsonObject = buildJsonObject {
        when (a) {
            Answer.Sent -> put("a", "sent")
            is Answer.Seen -> { put("a", "seen"); if (a.again) put("again", true) }
            is Answer.Refused -> { put("a", "refused"); put("reason", a.reason); put("code", a.code) }
            is Answer.Lost -> {
                put("a", "lost")
                put("losses", buildJsonArray {
                    for (l in a.losses) add(buildJsonObject {
                        put("item", l.item)
                        put("standing", l.standing)
                        put("by", l.by)
                        put("at", l.at)
                        put("mine", l.mine)
                        if (l.gone) put("gone", true)
                    })
                })
            }
        }
    }

    fun answer(j: JsonObject): Answer = when (str(j, "a")) {
        "sent" -> Answer.Sent
        "seen" -> Answer.Seen((j["again"] as? JsonPrimitive)?.booleanOrNull == true)
        "refused" -> Answer.Refused(str(j, "reason"), str(j, "code"))
        "lost" -> Answer.Lost(j["losses"]!!.jsonArray.map {
            val l = it.jsonObject
            Loss(str(l, "item"), l["standing"] ?: JsonNull, str(l, "by"), num(l, "at"), l["mine"] ?: JsonNull,
                (l["gone"] as? JsonPrimitive)?.boolean == true)
        })
        else -> throw IllegalArgumentException("answer: unknown kind ${j["a"]}")
    }

    internal fun str(j: JsonObject, k: String): String =
        (j[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw IllegalArgumentException("field $k: not text")

    internal fun num(j: JsonObject, k: String): Long =
        (j[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("field $k: not a whole number")

    /** A whole number, or null when [e] is absent or not one. */
    internal fun longOf(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
