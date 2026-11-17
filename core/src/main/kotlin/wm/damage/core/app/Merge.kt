package wm.damage.core.app

import kotlinx.serialization.json.JsonNull

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1, as L1 C1 rules it (the lead's ruling on L0, 2026-11-17; his words: "whichever page I'm really
 * on is the one I'm really on"): the one merge rule. An op carries its result and the time of the input that made it
 * ([Op.at]); the PC writes each target unless the item holds a later input. An equal value changes nothing, so
 * applying an op twice changes nothing and the PC keeps no numbers. No window has a variant; a window's own
 * [ApplyRule] may stand in for it (the notice record is ordered by its occurrence, not by time).
 */

/** The queued kinds. A removal writes the item `gone` (an entry, so an older input that arrives later does not bring
 *  it back). L1 C4: no create, order or whole kinds (no window declared one); a later ordered list is one register
 *  value. Register and position judge alike; the phone's queue folds an unsent position into the next one. */
enum class Kind { REGISTER, POSITION, REMOVE }

/** What a window's action does to the record: its kind. Declared by the window's core, never carried by the op. */
data class ActionSpec(val kind: Kind)

/** The actions every converted window declares; null = no such action. */
fun interface ActionTable {
    fun spec(window: String, action: String): ActionSpec?
}

object Merge {
    /** Why [op] cannot be judged under [spec], in words, or null when its shape is right: the window's name, the
     *  targets, and a queued id. */
    fun check(spec: ActionSpec, op: Op): String? {
        if (!Record.validWindow(op.window)) return "window name \"${op.window}\" is not one"
        if (op.targets.isEmpty()) return "no target"
        if (op.targets.any { it.isBlank() }) return "a blank target"
        if (op.targets.toSet().size != op.targets.size) return "a target named twice"
        if (op.queued == null) return "a ${spec.kind} op must be queued (stream and n)"
        return null
    }

    /**
     * The effect of [op] (already [check]ed) on [rec], its input stamped [at] (the PC bounds it by its own clock,
     * `AppHost`): each target is written unless the record holds a later input for it, or already holds what the op
     * would make. On an equal time the op that arrives later wins. [always]: a window's rule ordered it ([Admit.Apply]),
     * so the time is not compared. Pure: it reads [rec]; nothing is written until the op's log line has landed.
     */
    fun judge(rec: RecordView, spec: ActionSpec, op: Op, at: Long = op.at, always: Boolean = false): Effect {
        val items = LinkedHashMap<String, Item>()
        val gone = spec.kind == Kind.REMOVE
        for (t in op.targets) {
            val key = Record.key(op.window, t)
            val cur = rec.item(key)
            val value = if (gone) JsonNull else op.result
            if (cur != null && cur.gone == gone && (gone || cur.value == value)) continue   // already holds it
            if (cur != null && cur.at > at && !always) continue                            // a later input stands
            items[key] = Item(value, op.device, at, gone)
        }
        return Effect(items)
    }
}
