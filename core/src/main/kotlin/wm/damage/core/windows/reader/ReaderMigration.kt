package wm.damage.core.windows.reader

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.Answer
import wm.damage.core.app.Draft
import wm.damage.core.app.Expect
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.RecordBackend
import wm.damage.core.app.StreamKey
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 (the spec's §4 "The first converted start — no place lost"; dec. A.3): each host turns its own
 * Reader records from before the conversion — each book's place, the bookmark, the glasses' Reader rows, all in the
 * local store's `window.reader` keys — into ops judged by their old stamps, once.
 */

/**
 * The one-time migration (`migrateOnce`), and its ledger. Every place, bookmark and row in the local copy becomes an
 * op expecting [Expect.PreConversion] with the copy key's stamp: the PC applies it when it holds nothing newer from
 * before the conversion and nothing written since (C§2.4 as dec. A.3 extends it), else the op loses and is said.
 *
 * The ledger is the `window.reader.conv` record beside the copy: what the converted build last wrote into the copy
 * (each book's offset, the bookmark and its book, the rows), and the record places the glasses' view knows as its own
 * or taken. A start offers only what differs from it: nothing at a second start; after a downgrade, exactly what the
 * older build wrote since, so places read on it are judged, not dropped. An older build never touches the ledger (it
 * restores only `book.` records and never removes a record it did not report). The ledger is written in the same
 * store as the copy, so the two are never apart on disk.
 */
object ReaderMigration {
    /** The PC's migration writes as this device (`by` on what it applies). */
    const val DEVICE = "migration"
    /** The PC's migration stream: its numbers continue from the record's high-water for it. */
    const val STREAM = "migration"
    /** The ledger's sub-key: `window.reader.conv`. */
    const val LEDGER = "conv"
    const val COPY = "window.reader"
    const val LEDGER_KEY = "$COPY.$LEDGER"
    private const val BOOK = "$COPY.book."

    /** What a start offers. [ledger] is written once the offers have landed. */
    data class Plan(
        val offers: List<Draft>,
        val ledger: JsonObject,
        val places: Int,
        val bookmarks: Int,
        val rows: Int,
        /** Bookmarks dropped: one offset with no book to give it to (said in the note). */
        val dropped: List<String>,
    )

    /** The plan for [store]'s copy against its ledger. Pure: reads the store, writes nothing. */
    fun plan(store: Persistence): Plan {
        val ledger = store.get(LEDGER_KEY) ?: JsonObject(emptyMap())
        val had = placesOf(ledger)
        val known = knownOf(ledger)
        // fix round 1, ruling 1.3: a place an older build wrote after a downgrade is judged by what this device last
        // knew of the record — it applies when the record still holds that, and loses (said) when another device wrote
        // since. Where the ledger knows no record value for the book (the first conversion; a removal), its old stamp
        fun judgedBy(id: String, stamp: Long): Expect =
            known[id]?.takeIf { it !is JsonNull }?.let { Expect.Value(it) } ?: Expect.PreConversion(stamp)
        val placesNow = LinkedHashMap<String, Int>()
        val offers = ArrayList<Draft>()
        var places = 0
        for (key in store.keysWithPrefix(BOOK).sorted()) {
            val id = key.removePrefix(BOOK)
            val off = store.get(key)?.get("off")?.jsonPrimitive?.intOrNull ?: continue   // a tombstone is not a place
            if (off < 0 || id.isEmpty()) continue
            placesNow[id] = off
            if (had[id] == off) continue
            offers += ReaderCore.placeDraft(id, ReaderAnchor(off), judgedBy(id, store.stamp(key)))
            places++
        }
        val main = store.get(COPY)
        val mainStamp = store.stamp(COPY)
        var bookmarks = 0
        var rows = 0
        val dropped = ArrayList<String>()
        var bookId = ledger["bookId"]?.jsonPrimitive?.contentOrNull ?: ""
        var bookmark = ledger["bookmark"]?.jsonPrimitive?.intOrNull ?: -1
        val rowsNow = LinkedHashMap<String, JsonElement>()
        for (r in ROW_FIELDS.values) ledger[r]?.let { rowsNow[r] = it }
        // a stamp of 0 is a baseline of defaults no one edited (R2#16): nothing in it to migrate
        if (main != null && mainStamp > 0L) {
            // the legacy whole-map (pre-§16.4a; dec. A.13): a book with no record of its own takes its entry
            (main["offsets"] as? JsonObject)?.let { o ->
                for ((id, v) in o.entries.sortedBy { it.key }) {
                    if (store.get(BOOK + id) != null) continue
                    val off = (v as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: continue
                    placesNow[id] = off
                    if (had[id] == off) continue
                    offers += ReaderCore.placeDraft(id, ReaderAnchor(off), judgedBy(id, mainStamp))
                    places++
                }
            }
            val bid = main["bookId"]?.jsonPrimitive?.contentOrNull ?: ""
            val bm = main["bookmark"]?.jsonPrimitive?.intOrNull ?: -1
            if (bm >= 0 && (bm != bookmark || bid != bookId)) {
                if (bid.isEmpty()) {
                    dropped += "the bookmark at character $bm names no book (none was open when it was saved)"
                } else {
                    offers += Draft(ReaderCore.MIGRATE, listOf(ReaderCore.bookmarkItem(bid)),
                        JsonPrimitive(ReaderAnchor(bm).format()), Expect.PreConversion(mainStamp))
                    bookmarks++
                }
            }
            bookId = bid
            bookmark = bm
            for ((field, row) in ROW_FIELDS) {
                val v = rowValue(field, main) ?: continue
                if (ledger[row] == v) {
                    rowsNow[row] = v
                    continue
                }
                offers += Draft(ReaderCore.MIGRATE, listOf(row), v, Expect.PreConversion(mainStamp))
                rowsNow[row] = v
                rows++
            }
        }
        val next = buildJsonObject {
            put("v", 1)
            put("places", buildJsonObject { for ((id, off) in placesNow) put(id, off) })
            put("bookId", bookId)
            put("bookmark", bookmark)
            for ((r, v) in rowsNow) put(r, v)
            put("known", (ledger["known"] as? JsonObject) ?: JsonObject(emptyMap()))
        }
        return Plan(offers, next, places, bookmarks, rows, dropped)
    }

    /** The copy's row fields (`window.reader`) and the record items they become. */
    private val ROW_FIELDS = linkedMapOf(
        "scrollLines" to ReaderCore.SCROLL_LINES,
        "scrollAccel" to ReaderCore.SCROLL_ACCEL,
        "heightPref" to ReaderCore.SIZE,
    )

    private fun rowValue(field: String, main: JsonObject): JsonElement? {
        val p = main[field] as? JsonPrimitive ?: return null
        return when (field) {
            "scrollLines" -> p.intOrNull?.coerceIn(1, 8)?.let { JsonPrimitive(it) }
            "scrollAccel" -> p.booleanOrNull?.let { JsonPrimitive(it) }
            "heightPref" -> p.intOrNull?.let { JsonPrimitive(it) }
            else -> null
        }
    }

    /** The ledger the glasses' presenter writes at each save point: what it wrote into the copy in the same save. */
    fun ledgerOf(
        offsets: Map<String, Int>, bookId: String, bookmark: Int, scrollLines: Int, scrollAccel: Boolean,
        heightPref: Int, known: Map<String, JsonElement>,
    ): JsonObject = buildJsonObject {
        put("v", 1)
        put("places", buildJsonObject { for ((id, off) in offsets.entries.sortedBy { it.key }) put(id, off) })
        put("bookId", bookId)
        put("bookmark", bookmark)
        put(ReaderCore.SCROLL_LINES, scrollLines)
        put(ReaderCore.SCROLL_ACCEL, scrollAccel)
        put(ReaderCore.SIZE, heightPref)
        put("known", buildJsonObject { for ((id, v) in known.entries.sortedBy { it.key }) put(id, v) })
    }

    /** The record places the ledger names as known (JsonNull = known removed). */
    fun knownOf(ledger: JsonObject): Map<String, JsonElement> =
        (ledger["known"] as? JsonObject)?.toMap() ?: emptyMap()

    private fun placesOf(ledger: JsonObject): Map<String, Int> =
        (ledger["places"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { k to it } }?.toMap()
            ?: emptyMap()

    /** What one host's migration did, for its `migrate` journal note. */
    data class Result(val plan: Plan, val applied: Int, val same: Int, val lost: List<String>, val notTaken: Int,
                      val ledgerWritten: Boolean) {
        fun line(host: String): String = buildString {
            append("$host: ")
            if (plan.offers.isEmpty() && plan.dropped.isEmpty()) {
                append("nothing to migrate (the ledger holds every Reader record of the local copy)")
                return@buildString
            }
            append("${plan.places} place(s), ${plan.bookmarks} bookmark(s), ${plan.rows} row(s) offered")
            if (applied + same + lost.size + notTaken > 0)
                append("; applied or already held $applied, not applied ${lost.size - notTaken}, not taken $notTaken")
            for (l in lost) append("; not applied: $l")
            for (d in plan.dropped) append("; dropped: $d")
            append(if (ledgerWritten) "; ledger written" else "; ledger NOT written (offered again at the next start)")
        }
    }

    /**
     * The PC's migration at its process start, before any presenter reads the record: every offer goes to [host] as
     * [DEVICE]'s op and is answered on its executor (each op gets exactly one answer; this waits for them, with no
     * bound: the record answers even when it could not open — "retry"). The ledger is written once every offer was
     * taken; any "retry" leaves it unwritten, so the next start offers again (an op the record already holds is
     * judged the same value and changes nothing). [note] gets the `migrate` line.
     */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit): Result {
        val plan = plan(store)
        if (plan.offers.isEmpty()) {
            val wrote = plan.dropped.isNotEmpty() && writeLedger(store, plan.ledger)
            return Result(plan, 0, 0, emptyList(), 0, wrote).also { if (plan.dropped.isNotEmpty()) say(note, it.line("pc")) }
        }
        val answers = ConcurrentHashMap<Int, Answer>()
        val done = CountDownLatch(plan.offers.size)
        var n = host.highWater(StreamKey(DEVICE, STREAM))
        for ((i, d) in plan.offers.withIndex()) {
            val op = Op(DEVICE, OpId.Queued(STREAM, ++n), ReaderCore.WINDOW, d.action, d.targets, d.result, d.expect, d.params)
            host.act(op) { a ->
                answers[i] = a
                done.countDown()
            }
        }
        done.await()
        var applied = 0
        var same = 0
        var notTaken = 0
        val lost = ArrayList<String>()
        for ((i, d) in plan.offers.withIndex()) when (val a = answers[i]) {
            is Answer.Lost -> for (l in a.losses) lost += "${l.item} stays ${l.standing} (${l.by}), the copy's ${l.mine} (stamp ${(d.expect as? Expect.PreConversion)?.at})"
            is Answer.Refused -> {
                notTaken++
                lost += "${d.targets.first()} refused: ${a.reason}"
            }
            is Answer.Seen -> applied++
            else -> same++
        }
        val retried = plan.offers.indices.count { (answers[it] as? Answer.Refused)?.code == Answer.RETRY }
        val wrote = retried == 0 && writeLedger(store, plan.ledger)
        return Result(plan, applied, same, lost, notTaken, wrote).also { say(note, it.line("pc")) }
    }

    /**
     * The phone's migration at a stack's start, once Reader's presenter is registered and listens (fix round 1,
     * ruling 1.5 (a)): every offer goes into the working copy's queue ([copy] numbers it, applies it to the view and
     * hands it to the queue's writer, all on its executor, before this returns); the PC answers when the lane carries
     * it, and a loss is said by the glasses' presenter as any other (one answered while no presenter listens is kept
     * for the next, `WorkingCopy`). The ledger is then written in the same store as the copy. A queue line that could
     * not be written is kept in memory and tried again by the queue; a process that ends first leaves the place in the
     * copy, and the presenter sends a place the record does not hold at its first save point.
     */
    fun onCopy(copy: RecordBackend, store: Persistence, note: (String) -> Unit): Result {
        val plan = plan(store)
        if (plan.offers.isNotEmpty()) copy.executor.call { for (d in plan.offers) copy.submit(ReaderCore.WINDOW, d) }
        val wrote = (plan.offers.isNotEmpty() || plan.dropped.isNotEmpty()) && writeLedger(store, plan.ledger)
        return Result(plan, 0, 0, emptyList(), 0, wrote).also {
            if (plan.offers.isNotEmpty() || plan.dropped.isNotEmpty()) say(note, it.line("phone"))
        }
    }

    private fun writeLedger(store: Persistence, ledger: JsonObject): Boolean {
        store.put(LEDGER_KEY, ledger)
        val ok = store.save()
        if (!ok) Log.e("migrate", "the Reader ledger could not be saved; the next start offers the same records again " +
            "(each is judged by its value and changes nothing it already holds)")
        return ok
    }

    private fun say(note: (String) -> Unit, line: String) {
        Log.i("migrate", line)
        try {
            note(line)
        } catch (e: Exception) {
            Log.e("migrate", "the migrate note could not be written", e)
        }
    }
}
