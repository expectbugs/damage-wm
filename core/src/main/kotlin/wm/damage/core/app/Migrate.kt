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
 * Build S1-2, package Q2 (the spec's §2.5 and §5 F4; `CONTRACT.md` §9.7 item 2): S1-1's migration
 * (`ReaderMigration`) made generic for the register fields of one window's local blob, `window.<id>` in the host's
 * store. At the window's first converted start each host offers each field it holds, once, as a `migrate` op
 * expecting `PreConversion(the blob's stamp)` (the blob has one stamp for all its fields): the PC applies it when it
 * holds nothing newer from before the conversion and nothing written since; otherwise the op loses and is said. The
 * ledger `window.<id>.conv`, beside the blob in the same store, holds what was offered, so a later start offers only
 * what an older build wrote since — nothing at a second start. Each run says one `migrate` note. Reader's own
 * migration is not touched.
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

    /** What one host's migration did, for its `migrate` note. */
    data class Result(val plan: Plan, val applied: Int, val lost: List<String>, val notTaken: Int, val ledgerWritten: Boolean)

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
            offers += Draft(action, listOf(f.item), v, Expect.PreConversion(stamp))
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
        if (plan.offers.isEmpty()) return Result(plan, 0, emptyList(), 0, false)
        val answers = ConcurrentHashMap<Int, Answer>()
        val done = CountDownLatch(plan.offers.size)
        var n = host.highWater(StreamKey(DEVICE, STREAM))
        for ((i, d) in plan.offers.withIndex()) {
            host.act(Op(DEVICE, OpId.Queued(STREAM, ++n), window, d.action, d.targets, d.result, d.expect, d.params)) { a ->
                answers[i] = a
                done.countDown()
            }
        }
        done.await()
        var applied = 0
        var notTaken = 0
        val lost = ArrayList<String>()
        for ((i, d) in plan.offers.withIndex()) when (val a = answers[i]) {
            is Answer.Lost -> for (l in a.losses) lost += "${l.item} stays ${l.standing} (${l.by}), the copy's ${l.mine} (stamp ${plan.stamp})"
            is Answer.Refused -> {
                notTaken++
                lost += "${d.targets.first()} refused: ${a.reason}"
            }
            else -> applied++
        }
        val retried = plan.offers.indices.count { (answers[it] as? Answer.Refused)?.code == Answer.RETRY }
        val wrote = retried == 0 && writeLedger(store, plan.ledger)
        return Result(plan, applied, lost, notTaken, wrote).also { say(note, line("pc", it)) }
    }

    /**
     * The phone's migration at a stack's start, once the window's presenter listens: every offer goes into the
     * working copy's queue (numbered and applied to the view on its executor before this returns); the PC answers when
     * the lane carries it, and a loss is said by the presenter as any other. The ledger is then written in the store.
     */
    fun onCopy(copy: RecordBackend, store: Persistence, note: (String) -> Unit): Result {
        val plan = plan(store)
        if (plan.offers.isEmpty()) return Result(plan, 0, emptyList(), 0, false)
        copy.executor.call { for (d in plan.offers) copy.submit(window, d) }
        val wrote = writeLedger(store, plan.ledger)
        return Result(plan, 0, emptyList(), 0, wrote).also { say(note, line("phone", it)) }
    }

    /** The `migrate` note's words. */
    fun line(host: String, r: Result): String = buildString {
        append("$host: $window: ${r.plan.offers.size} field(s) offered (${r.plan.offers.joinToString { it.targets.first() }})")
        if (r.applied + r.lost.size > 0) append("; applied or already held ${r.applied}, not applied ${r.lost.size - r.notTaken}, not taken ${r.notTaken}")
        for (l in r.lost) append("; not applied: $l")
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
        /** The PC's migration writes as this device, on this stream (the values `ReaderMigration` uses: one stream
         *  for every window's migration, numbered on from its high-water; the starts run one after another). */
        const val DEVICE = "migration"
        const val STREAM = "migration"
        const val ACTION = "migrate"
        const val LEDGER = "conv"
        private const val ROWS = "rows"
    }
}
