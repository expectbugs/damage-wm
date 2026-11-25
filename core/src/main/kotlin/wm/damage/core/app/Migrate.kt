package wm.damage.core.app

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2, as L1 M1 made it: a converted window's rows from before its conversion become record items
 * once, on the PC only. The PC store's `window.<id>` blob holds them (the phone synced it there until the conversion
 * took the key off the channel). At the PC's process start, before any presenter reads the record, each field whose
 * record item the record holds none of is written as a `migrate` op of [DEVICE], stamped with the blob's stamp as its
 * input time; an item the record holds stands, so a second start writes nothing and no ledger is kept.
 * Build S1-3 B7: a field may become several items ([Many]: Feed's pinned ids, one `pin.<id>` each), and the blob's
 * sub-keys (`window.<id>.<sub>`, each stamped on its own) may become items too ([Sub]: Feed's per-source records).
 */
class Migrate(
    val window: String,
    /** The blob's fields that migrate, each with its record item and how its value is read. */
    val fields: List<Field>,
    /** The window's migrate action (declared with the position kind). */
    val action: String = ACTION,
    /** S1-3 B15: items read from the whole blob rather than one field each (Tmux's `muted` list → one
     *  `mute.<host>/<name>` per entry), as (record item, value); taken only where the record holds none. */
    val derived: (JsonObject) -> List<Pair<String, JsonElement>> = { emptyList() },
    /** Fields that become several items each. */
    val many: List<Many> = emptyList(),
    /** Sub-keys that become items, each stamped with its own key's stamp. */
    val subs: List<Sub> = emptyList(),
    /** S1-5 S: the store key the blob is read from (Settings': `shell.settings`). */
    val key: String = "window.$window",
    /** S1-5 S: the input time every offer is stamped with (the PC's clock), in place of the blob's stamp; null = the
     *  stamp, and a blob stamped 0 (a baseline of defaults no one edited) offers nothing. */
    val at: (() -> Long)? = null,
) {
    /** One register field: [name] in the blob, [item] the record item it becomes, [read] its value as the record
     *  holds it (null: nothing to migrate — absent, or not a value of that row). */
    data class Field(val name: String, val item: String, val read: (JsonElement) -> JsonElement?)

    /** One field [name] that becomes the items [read] names (item → value; empty: nothing to migrate). */
    data class Many(val name: String, val read: (JsonElement) -> Map<String, JsonElement>)

    /** The sub-keys `window.<id>.<prefix><sub>`: each blob becomes the items [read] names from its `<sub>`. */
    data class Sub(val prefix: String, val read: (sub: String, blob: kotlinx.serialization.json.JsonObject) -> Map<String, JsonElement>)

    /** The ops [store]'s blob makes, for the items the record does not hold ([held]: record keys). */
    fun offers(store: Persistence, held: Set<String>): List<Draft> {
        val out = ArrayList<Draft>()
        fun offer(item: String, v: JsonElement, at: Long) {
            if (Record.key(window, item) !in held && out.none { it.targets.first() == item }) out += Draft(action, listOf(item), v, at = at)
        }
        val blob = store.get(key)
        val stamp = at?.invoke() ?: store.stamp(key)
        if (blob != null && stamp > 0L) {             // stamp 0: a baseline of defaults no one edited (S1-1's R2#16)
            for (f in fields) blob[f.name]?.let(f.read)?.let { v -> offer(f.item, v, stamp) }
            for (m in many) blob[m.name]?.let(m.read)?.forEach { (item, v) -> offer(item, v, stamp) }
            for ((item, v) in derived(blob)) offer(item, v, stamp)
        }
        for (sub in subs) for (k in store.keysWithPrefix("$key.${sub.prefix}")) {
            val b = store.get(k)?.takeIf { it.isNotEmpty() } ?: continue        // an empty blob is a removal's tombstone
            val at = store.stamp(k).takeIf { it > 0L } ?: continue
            for ((item, v) in sub.read(k.removePrefix("$key.${sub.prefix}"), b)) offer(item, v, at)
        }
        return out
    }

    /** The PC's pass at its process start: each offer is handed to [host] and answered on its executor (the record
     *  answers even when its log is closed: "retry", and the next start offers it again). [note] gets one line. */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit) {
        val offers = offers(store, host.read(window).third.items.keys)
        if (offers.isEmpty()) return
        val refused = ConcurrentLinkedQueue<String>()
        val done = CountDownLatch(offers.size)
        for ((i, d) in offers.withIndex()) {
            host.act(Op(DEVICE, OpId.Queued(STREAM, i + 1L), window, d.action, d.targets, d.result, d.params, at = d.at!!)) { a ->
                if (a is Answer.Refused) refused += "${d.targets.first()} (${a.reason})"
                done.countDown()
            }
        }
        done.await()
        val line = "$window: ${offers.size} row(s) the record did not hold, written from the PC store's copy " +
            "(${offers.joinToString { it.targets.first() }})" + (if (refused.isEmpty()) "" else "; not taken: " + refused.joinToString())
        if (refused.isEmpty()) Log.i(TAG, line) else Log.e(TAG, line)
        try {
            note(line)
        } catch (e: Exception) {
            Log.e(TAG, "the migrate note could not be written", e)
        }
    }

    companion object {
        private const val TAG = "migrate"
        /** The PC's migration writes as this device, on this stream (the values S1-1's Reader migration used). */
        const val DEVICE = "migration"
        const val STREAM = "migration"
        const val ACTION = "migrate"
    }
}
