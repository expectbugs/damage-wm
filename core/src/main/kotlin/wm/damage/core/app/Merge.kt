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
 *  item; "log under a lease" and the never-queued kinds are not judged here (not in S1-1). L1 C4: no create, order or
 *  whole kinds (no window declared one); a later ordered list is one register value. */
enum class Kind { REGISTER, POSITION, REMOVE }

/** What a window's action does to the record: its kind. Declared by the window's core, never carried by the op. */
data class ActionSpec(val kind: Kind)

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
     * takes and the targets.
     */
    fun check(spec: ActionSpec, op: Op): String? {
        if (!Record.validWindow(op.window)) return "window name \"${op.window}\" is not one"
        if (op.targets.isEmpty()) return "no target"
        if (op.targets.any { it.isBlank() }) return "a blank target"
        if (op.targets.toSet().size != op.targets.size) return "a target named twice"
        op.queued ?: return "a ${spec.kind} op must be queued (stream and n)"
        val e = op.expect
        return when (spec.kind) {
            Kind.REGISTER -> if (e is Expect.Value || e is Expect.Occurrence || e == Expect.Any) null
                else "a register expects a value, an occurrence or Any"
            Kind.POSITION -> if (e is Expect.Value || e is Expect.PreConversion || e == Expect.Any) null
                else "a position expects a value, a pre-conversion stamp or Any"
            Kind.REMOVE -> if (e is Expect.Rev) null else "a removal expects a revision"
        }
    }

    /**
     * The verdict on [op] (already [check]ed) against [rec], at PC time [now]. Pure: it reads [rec] and returns the
     * effect; nothing is written until the op's log line has landed (`AppHost`). Each target is judged on its own:
     * those that can apply do, each that cannot is marked and named in the losses.
     */
    fun judge(rec: RecordView, spec: ActionSpec, op: Op, now: Long): Verdict {
        val items = LinkedHashMap<String, Item>()
        val losses = ArrayList<Loss>()
        when (spec.kind) {
            Kind.REGISTER, Kind.POSITION -> for (t in op.targets) value(rec, op, t, now, items, losses)
            Kind.REMOVE -> for (t in op.targets) remove(rec, op, t, now, items, losses)
        }
        val effect = Effect(items)
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

    private fun remove(rec: RecordView, op: Op, target: String, now: Long,
                       items: MutableMap<String, Item>, losses: MutableList<Loss>) {
        val key = Record.key(op.window, target)
        val cur = rec.item(key)
        if (cur == null || cur.gone) return                               // already removed
        if (cur.rev != (op.expect as Expect.Rev).r) {
            lose(key, cur, op, now, items, losses)
            return
        }
        items[key] = Item(JsonNull, cur.rev + 1, op.device, now, gone = true)
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
