package wm.damage.core.app

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (`CONTRACT.md` §2.4, §9.7 item 1): the PC record — Damage's own records of every converted
 * window, one entry per item `rec.<window>.<item>` = {value, by, at}. Written only by the core executor (`AppHost`),
 * from effects that are absolute, so replaying an effect the record already holds changes nothing. L1 C4: no
 * collections (a later ordered list is one register value); L1 C1: no revisions and no "not applied" marks.
 */

/**
 * One item: its value, the device that wrote it ([by]) and the time of the input that made it ([at], ms; a migrated
 * item keeps its old stamp). [gone] = removed: the entry stays, so an older input that arrives later does not bring
 * it back (the latest input wins, `Merge`).
 */
data class Item(
    val value: JsonElement,
    val by: String,
    val at: Long,
    val gone: Boolean = false,
)

/** What an op wrote: the whole new state of every entry it touched, keyed `rec.<window>.<name>`. Absolute, never a
 *  delta: applying it again changes nothing (the log's replay over a save that already holds it). */
data class Effect(val items: Map<String, Item> = emptyMap()) {
    fun isEmpty() = items.isEmpty()

    companion object {
        val NONE = Effect()
    }
}

/** Read access to the record, for the judge. */
fun interface RecordView {
    fun item(key: String): Item?
}

/** The record in memory. Owned by the core executor: no lock, no other writer (C§2.2, C§14 (b)). */
class Record : RecordView {
    private val items = HashMap<String, Item>()

    override fun item(key: String): Item? = items[key]

    fun apply(e: Effect) {
        items.putAll(e.items)
    }

    /** A copy of the map (the entries are immutable), for a save or a snapshot. */
    fun copyItems(): Map<String, Item> = HashMap(items)

    /** Every item of [window], keyed by the full key. */
    fun window(window: String): Effect {
        val p = prefix(window)
        return Effect(items.filterKeys { it.startsWith(p) })
    }

    val size: Int get() = items.size

    companion object {
        /** A window's name: lowercase letters, digits, `_` and `-`, starting with a letter, so the key's first two
         *  dots are unambiguous. */
        private val WINDOW = Regex("[a-z][a-z0-9_-]{0,31}")

        fun validWindow(window: String) = WINDOW.matches(window)

        fun prefix(window: String) = "rec.$window."

        /** The key of item [name] in [window]. */
        fun key(window: String, name: String) = prefix(window) + name
    }
}

/** The JSON forms of the record's entries and effects — the one definition `record.json` and the log both use. */
object RecordJson {
    fun item(i: Item): JsonObject = buildJsonObject {
        if (!i.gone) put("v", i.value) else put("gone", true)
        put("by", i.by)
        put("at", i.at)
    }

    /** L1 C1: a 0.67 entry's `rev` and `na` are not read. */
    fun item(j: JsonObject): Item {
        val gone = (j["gone"] as? JsonPrimitive)?.booleanOrNull == true
        return Item(
            value = if (gone) JsonNull else j["v"] ?: throw IllegalArgumentException("item: no v"),
            by = OpJson.str(j, "by"),
            at = OpJson.num(j, "at"),
            gone = gone,
        )
    }

    fun items(m: Map<String, Item>): JsonObject = buildJsonObject { for ((k, v) in m.toSortedMap()) put(k, item(v)) }

    fun items(j: JsonObject): Map<String, Item> = j.mapValues { (k, v) ->
        item(v as? JsonObject ?: throw IllegalArgumentException("item $k: not an object"))
    }

    fun effect(e: Effect): JsonObject = buildJsonObject {
        if (e.items.isNotEmpty()) put("items", items(e.items))
    }

    /** L1 C4: a 0.67 line's `colls` (always empty in his log) is not read. */
    fun effect(j: JsonObject): Effect = Effect((j["items"] as? JsonObject)?.let { items(it) } ?: emptyMap())
}
