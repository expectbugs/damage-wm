package wm.damage.core.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1: the one merge rule of `CONTRACT.md` §2.4 — "an op names what it expects to find, and
 * carries its result, not its recipe. The PC applies queued ops in the order they reach it. An op applies when the PC
 * still holds what it expected, or already holds what the op would make (a same-value op succeeds and changes
 * nothing). Otherwise it loses, and the loss is said on the item." No window has a variant. "No op is ever applied
 * twice" is the stream's high-water (`Streams`), checked before the judge is asked.
 */

/** The queued kinds of C§2.4's table. "Edit of a removed item" is the register and position rows meeting a removed
 *  item; "log under a lease" and the never-queued kinds are not judged here (not in S1-1). */
enum class Kind { CREATE, REGISTER, POSITION, ORDER, REMOVE, WHOLE }

/** What a window's action does to the record: its kind, and for CREATE, ORDER and WHOLE the collection it works on
 *  (a create without one makes loose items). Declared by the window's core, never carried by the op. */
data class ActionSpec(val kind: Kind, val coll: String? = null)

/** The actions every converted window declares; null = no such action. */
fun interface ActionTable {
    fun spec(window: String, action: String): ActionSpec?
}

/** The judge's verdict. [Lost] carries the "not applied" marks it writes and every target of the op that did apply. */
sealed class Verdict {
    abstract val effect: Effect

    data class Applied(override val effect: Effect) : Verdict()
    object Same : Verdict() {
        override val effect: Effect get() = Effect.NONE
        override fun toString() = "Same"
    }
    data class Lost(override val effect: Effect, val losses: List<Loss>) : Verdict()
}

object Merge {
    /**
     * Why [op] cannot be judged under [spec], in words, or null when its shape is right: the expectation its kind
     * takes, the targets, the result's form, and a created id minted by this op (`<stream>.<n>.<k>`, C§2.4).
     */
    fun check(spec: ActionSpec, op: Op): String? {
        if (!Record.validWindow(op.window)) return "window name \"${op.window}\" is not one"
        if (op.targets.isEmpty()) return "no target"
        if (op.targets.any { it.isBlank() }) return "a blank target"
        if (op.targets.toSet().size != op.targets.size) return "a target named twice"
        val q = op.queued ?: return "a ${spec.kind} op must be queued (stream and n)"
        val e = op.expect
        return when (spec.kind) {
            Kind.CREATE -> when {
                e != Expect.Any -> "a create expects nothing (Any)"
                op.result !is JsonArray || (op.result as JsonArray).size != op.targets.size ->
                    "a create carries one value per target"
                op.targets.withIndex().any { (k, t) -> !t.endsWith("${q.stream}.${q.n}.$k") } ->
                    "a created id is not <stream>.<n>.<k> of this op"
                else -> null
            }
            Kind.REGISTER -> if (e is Expect.Value || e is Expect.Occurrence || e == Expect.Any) null
                else "a register expects a value, an occurrence or Any"
            Kind.POSITION -> if (e is Expect.Value || e is Expect.PreConversion || e == Expect.Any) null
                else "a position expects a value, a pre-conversion stamp or Any"
            Kind.REMOVE -> if (e is Expect.Rev) null else "a removal expects a revision"
            Kind.ORDER -> when {
                e !is Expect.Rev -> "a move expects a revision"
                spec.coll == null -> "a move needs its collection"
                op.targets.size != 1 -> "a move has one target"
                !(op.result == JsonNull || (op.result as? JsonPrimitive)?.isString == true) ->
                    "a move's result is the id it goes after, or null for first"
                (op.result as? JsonPrimitive)?.content == op.targets[0] && op.result != JsonNull ->
                    "an item cannot go after itself"
                else -> null
            }
            Kind.WHOLE -> {
                val r = op.result as? JsonObject
                val order = r?.get("order") as? JsonArray
                when {
                    e !is Expect.Rev -> "a whole write expects the collection's revision"
                    spec.coll == null || op.targets != listOf(spec.coll) -> "a whole write targets its collection"
                    order == null || order.any { (it as? JsonPrimitive)?.isString != true } ->
                        "a whole write's result is {order: [ids], items: {id: value}}"
                    order.toSet().size != order.size -> "an id named twice in the order"
                    r["items"] != null && r["items"] !is JsonObject -> "a whole write's items is an object"
                    else -> null
                }
            }
        }
    }

    /**
     * The verdict on [op] (already [check]ed) against [rec], at PC time [now]. Pure: it reads [rec] and returns the
     * effect; nothing is written until the op's log line has landed (`AppHost`). Each target is judged on its own:
     * those that can apply do, each that cannot is marked and named in the losses.
     */
    fun judge(rec: RecordView, spec: ActionSpec, op: Op, now: Long): Verdict {
        val items = LinkedHashMap<String, Item>()
        val colls = LinkedHashMap<String, Coll>()
        val losses = ArrayList<Loss>()
        when (spec.kind) {
            Kind.CREATE -> create(rec, spec, op, now, items, colls)
            Kind.REGISTER, Kind.POSITION -> for (t in op.targets) value(rec, op, t, now, items, losses)
            Kind.REMOVE -> for (t in op.targets) remove(rec, spec, op, t, now, items, colls, losses)
            Kind.ORDER -> order(rec, spec, op, now, items, colls, losses)
            Kind.WHOLE -> whole(rec, spec, op, now, items, colls, losses)
        }
        val effect = Effect(items, colls)
        return when {
            losses.isNotEmpty() -> Verdict.Lost(effect, losses)
            effect.isEmpty() -> Verdict.Same
            else -> Verdict.Applied(effect)
        }
    }

    /** Register and position (and their "edit of a removed item" row). */
    private fun value(rec: RecordView, op: Op, target: String, now: Long,
                      items: MutableMap<String, Item>, losses: MutableList<Loss>) {
        val key = Record.key(op.window, target)
        val cur = rec.item(key)
        val result = op.result
        if (cur != null && cur.gone && op.expect != Expect.Any) {
            lose(key, cur, op, now, items, losses)
            return
        }
        val standing = if (cur == null || cur.gone) JsonNull else cur.value
        if (cur != null && !cur.gone && standing == result) return      // already holds what the op would make
        val applies = when (val e = op.expect) {
            Expect.Any -> true
            is Expect.Value -> standing == e.v
            is Expect.Occurrence -> occurrence(standing) == e.o
            is Expect.PreConversion -> cur == null || (cur.rev == 0L && cur.at < e.at)
            is Expect.Rev -> false
        }
        if (!applies) {
            lose(key, cur, op, now, items, losses)
            return
        }
        items[key] = if (op.expect is Expect.PreConversion) {
            // the pre-conversion world stays at rev 0 and is ordered by its old stamps (dec. A.3): a place read on
            // an old build and offered again by the ledger is judged against this one by stamp, not refused
            Item(result, 0L, op.device, (op.expect as Expect.PreConversion).at)
        } else {
            Item(result, (cur?.rev ?: 0L) + 1, op.device, now)
        }
    }

    private fun remove(rec: RecordView, spec: ActionSpec, op: Op, target: String, now: Long,
                       items: MutableMap<String, Item>, colls: MutableMap<String, Coll>, losses: MutableList<Loss>) {
        val key = Record.key(op.window, target)
        val cur = rec.item(key)
        if (cur == null || cur.gone) return                               // already removed
        if (cur.rev != (op.expect as Expect.Rev).r) {
            lose(key, cur, op, now, items, losses)
            return
        }
        items[key] = Item(JsonNull, cur.rev + 1, op.device, now, gone = true)
        spec.coll?.let { name ->
            val ck = Record.key(op.window, name)
            val c = colls[ck] ?: rec.coll(ck) ?: return@let
            if (target in c.order) colls[ck] = c.copy(order = c.order - target)
        }
    }

    private fun create(rec: RecordView, spec: ActionSpec, op: Op, now: Long,
                       items: MutableMap<String, Item>, colls: MutableMap<String, Coll>) {
        val values = op.result as JsonArray
        val made = ArrayList<String>()
        for ((k, t) in op.targets.withIndex()) {
            val key = Record.key(op.window, t)
            if (rec.item(key) != null) continue                          // a replay is a no-op
            items[key] = Item(values[k], 1L, op.device, now)
            made += t
        }
        val name = spec.coll ?: return
        if (made.isEmpty()) return
        val ck = Record.key(op.window, name)
        val c = rec.coll(ck)
        colls[ck] = Coll((c?.order ?: emptyList()) + made, (c?.rev ?: 0L) + 1, op.device, now)
    }

    private fun order(rec: RecordView, spec: ActionSpec, op: Op, now: Long, items: MutableMap<String, Item>,
                      colls: MutableMap<String, Coll>, losses: MutableList<Loss>) {
        val target = op.targets[0]
        val key = Record.key(op.window, target)
        val ck = Record.key(op.window, spec.coll!!)
        val c = rec.coll(ck)
        val cur = rec.item(key)
        val anchor = (op.result as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (c == null || cur == null || cur.gone || target !in c.order) {
            lose(key, cur, op, now, items, losses)                       // another device removed it
            return
        }
        if (anchor != null && anchor !in c.order) {
            // its anchor entry is gone: said with the anchor's last write when the record still has it
            val a = rec.item(Record.key(op.window, anchor))
            val mark = cur.copy(notApplied = NotApplied(op.result, op.device, now))
            items[key] = mark
            losses += Loss(target, JsonPrimitive(anchor), a?.by ?: cur.by, a?.at ?: cur.at, op.result, gone = true)
            return
        }
        val without = c.order - target
        val at = if (anchor == null) 0 else without.indexOf(anchor) + 1
        val moved = without.subList(0, at) + target + without.subList(at, without.size)
        if (moved == c.order) return                                     // already there
        val seenRev = (op.expect as Expect.Rev).r
        if (cur.rev != seenRev) {
            lose(key, cur, op, now, items, losses)
            return
        }
        items[key] = cur.copy(rev = cur.rev + 1, by = op.device, at = now, notApplied = null)
        colls[ck] = c.copy(order = moved)
    }

    private fun whole(rec: RecordView, spec: ActionSpec, op: Op, now: Long, items: MutableMap<String, Item>,
                      colls: MutableMap<String, Coll>, losses: MutableList<Loss>) {
        val ck = Record.key(op.window, spec.coll!!)
        val c = rec.coll(ck)
        val r = op.result as JsonObject
        val order = (r["order"] as JsonArray).map { (it as JsonPrimitive).content }
        val given = (r["items"] as? JsonObject) ?: JsonObject(emptyMap())
        val held = c?.order ?: emptyList()
        val sameValues = given.all { (id, v) -> rec.item(Record.key(op.window, id)).let { it != null && !it.gone && it.value == v } }
        if (held == order && sameValues) return                          // already holds what the op would make
        if ((c?.rev ?: 0L) != (op.expect as Expect.Rev).r) {
            val standing = JsonArray(held.map { JsonPrimitive(it) })
            colls[ck] = (c ?: Coll(emptyList(), 0L, "", 0L)).copy(notApplied = NotApplied(op.result, op.device, now))
            losses += Loss(spec.coll, standing, c?.by ?: "", c?.at ?: 0L, op.result)
            return
        }
        for (id in held) if (id !in order) {
            val k = Record.key(op.window, id)
            val it = rec.item(k) ?: continue
            if (!it.gone) items[k] = Item(JsonNull, it.rev + 1, op.device, now, gone = true)
        }
        for ((id, v) in given) {
            val k = Record.key(op.window, id)
            val it = rec.item(k)
            if (it == null || it.gone || it.value != v) items[k] = Item(v, (it?.rev ?: 0L) + 1, op.device, now)
        }
        colls[ck] = Coll(order, (c?.rev ?: 0L) + 1, op.device, now)
    }

    /** The loss of one target: its "not applied" mark (the standing entry otherwise unchanged) and the answer's line. */
    private fun lose(key: String, cur: Item?, op: Op, now: Long, items: MutableMap<String, Item>, losses: MutableList<Loss>) {
        val base = cur ?: Item(JsonNull, 0L, "", 0L)
        items[key] = base.copy(notApplied = NotApplied(op.result, op.device, now))
        losses += Loss(key.removePrefix(Record.prefix(op.window)), if (base.gone) JsonNull else base.value,
            base.by, base.at, op.result, gone = base.gone)
    }

    /** A recurring item's open occurrence: its value's `occ` field. */
    private fun occurrence(v: JsonElement): String? =
        ((v as? JsonObject)?.get("occ") as? JsonPrimitive)?.takeIf { it.isString }?.content
}
