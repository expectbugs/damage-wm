package wm.damage.core.app

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.JsonElement
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2, as L1 M1 made it: a converted window's rows from before its conversion become record items
 * once, on the PC only. The PC store's `window.<id>` blob holds them (the phone synced it there until the conversion
 * took the key off the channel). At the PC's process start, before any presenter reads the record, each field whose
 * record item the record holds none of is written as a `migrate` op of [DEVICE], stamped with the blob's stamp as its
 * input time; an item the record holds stands, so a second start writes nothing and no ledger is kept.
 */
class Migrate(
    val window: String,
    /** The blob's fields that migrate, each with its record item and how its value is read. */
    val fields: List<Field>,
    /** The window's migrate action (declared with the position kind, `ReaderCore.MIGRATE`'s precedent). */
    val action: String = ACTION,
) {
    /** One register field: [name] in the blob, [item] the record item it becomes, [read] its value as the record
     *  holds it (null: nothing to migrate — absent, or not a value of that row). */
    data class Field(val name: String, val item: String, val read: (JsonElement) -> JsonElement?)

    /** The ops [store]'s blob makes, for the items the record does not hold ([held]: record keys). */
    fun offers(store: Persistence, held: Set<String>): List<Draft> {
        val key = "window.$window"
        val blob = store.get(key) ?: return emptyList()
        val stamp = store.stamp(key)
        if (stamp <= 0L) return emptyList()            // a baseline of defaults no one edited (S1-1's R2#16)
        return fields.mapNotNull { f ->
            if (Record.key(window, f.item) in held) null
            else blob[f.name]?.let(f.read)?.let { v -> Draft(action, listOf(f.item), v, at = stamp) }
        }
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
