package wm.damage.core.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.5 and §5 F4; `CONTRACT.md` §9.7 item 2): S1-1's migration made generic for
 * the register fields of one window's local blob, `window.<id>` in the host's store. At the window's first converted
 * start each host offers each field it holds, once, as a `migrate` op stamped with the blob's stamp as its input time
 * (L1 C1; the blob has one stamp for all its fields): the PC applies it unless it holds a later input. The ledger
 * `window.<id>.conv`, beside the blob in the same store, holds what was offered, so a later start offers only what an
 * older build wrote since — nothing at a second start. Each run says one `migrate` note.
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

    val copyKey: String get() = "window.$window"
    val ledgerKey: String get() = "$copyKey.$LEDGER"

    /** What a start offers, and the ledger written once the offers have landed. */
    data class Plan(val offers: List<Draft>, val ledger: JsonObject, val stamp: Long)

    /** What one host's migration did, for its `migrate` note: [refused] names each offer the PC did not take. */
    data class Result(val plan: Plan, val refused: List<String>, val ledgerWritten: Boolean)

    /** The plan for [store]'s blob against its ledger. Reads the store, writes nothing. */
    fun plan(store: Persistence): Plan {
        val ledger = store.get(ledgerKey) ?: JsonObject(emptyMap())
        val had = (ledger[ROWS] as? JsonObject) ?: JsonObject(emptyMap())
        val blob = store.get(copyKey)
        val stamp = store.stamp(copyKey)
        val now = LinkedHashMap<String, JsonElement>(had)
        val offers = ArrayList<Draft>()
        // a stamp of 0 is a baseline of defaults no one edited (S1-1's R2#16): nothing in it to migrate
        if (blob != null && stamp > 0L) for (f in fields) {
            val v = blob[f.name]?.let(f.read) ?: continue
            now[f.item] = v
            if (had[f.item] == v) continue
            offers += Draft(action, listOf(f.item), v, at = stamp)
        }
        val next = buildJsonObject {
            put("v", 1)
            put(ROWS, JsonObject(now))
        }
        return Plan(offers, next, stamp)
    }

    /**
     * The PC's migration at its process start, before any presenter reads the record: every offer goes to [host] as
     * [DEVICE]'s op on [STREAM] and is answered on its executor; this waits for each answer (the record answers even
     * when it could not open: "retry"). The ledger is written once every offer was taken; a "retry" leaves it unwritten
     * and the next start offers again. [note] gets the `migrate` line.
     */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit): Result {
        val plan = plan(store)
        if (plan.offers.isEmpty()) return Result(plan, emptyList(), false)
        val answers = ConcurrentHashMap<Int, Answer>()
        val done = CountDownLatch(plan.offers.size)
        for ((i, d) in plan.offers.withIndex()) {
            host.act(Op(DEVICE, OpId.Queued(STREAM, i + 1L), window, d.action, d.targets, d.result, d.params, at = d.at!!)) { a ->
                answers[i] = a
                done.countDown()
            }
        }
        done.await()
        val refused = plan.offers.indices.mapNotNull { i ->
            (answers[i] as? Answer.Refused)?.let { "${plan.offers[i].targets.first()} refused: ${it.reason}" }
        }
        val retried = plan.offers.indices.count { (answers[it] as? Answer.Refused)?.code == Answer.RETRY }
        val wrote = retried == 0 && writeLedger(store, plan.ledger)
        return Result(plan, refused, wrote).also { say(note, line("pc", it)) }
    }

    /**
     * The phone's migration at a stack's start, once the window's presenter listens: every offer goes into the
     * working copy's queue (numbered and applied to the view on its executor before this returns); the PC answers when
     * the lane carries it. The ledger is then written in the store.
     */
    fun onCopy(copy: RecordBackend, store: Persistence, note: (String) -> Unit): Result {
        val plan = plan(store)
        if (plan.offers.isEmpty()) return Result(plan, emptyList(), false)
        copy.executor.call { for (d in plan.offers) copy.submit(window, d) }
        val wrote = writeLedger(store, plan.ledger)
        return Result(plan, emptyList(), wrote).also { say(note, line("phone", it)) }
    }

    /** The `migrate` note's words. */
    fun line(host: String, r: Result): String = buildString {
        append("$host: $window: ${r.plan.offers.size} field(s) offered (${r.plan.offers.joinToString { it.targets.first() }}), " +
            "each written unless the record holds a later input")
        for (l in r.refused) append("; not taken: $l")
        append(if (r.ledgerWritten) "; ledger written" else "; ledger NOT written (offered again at the next start)")
    }

    private fun writeLedger(store: Persistence, ledger: JsonObject): Boolean {
        store.put(ledgerKey, ledger)
        val ok = store.save()
        if (!ok) Log.e(TAG, "the $window ledger could not be saved; the next start offers the same fields again " +
            "(each is judged by its value and changes nothing it already holds)")
        return ok
    }

    private fun say(note: (String) -> Unit, line: String) {
        Log.i(TAG, line)
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
        const val LEDGER = "conv"
        private const val ROWS = "rows"
    }
}
