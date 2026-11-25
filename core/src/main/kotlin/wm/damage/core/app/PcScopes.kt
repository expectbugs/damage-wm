package wm.damage.core.app

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import wm.damage.core.shell.SettingsCore
import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.reader.ReaderDocs

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P7 (the spec's §2 P7 "Kotlin side"; `CONTRACT.md` §3.1, §5, §9.2; `INTERFACES.md` W-01, W-21,
 * W-30): the PC-served scopes the web client reads beside the record —
 *  - `desc/main`: Main's cards (converted windows only: one card, Reader, P§16.3 item 11), each with its summary, and
 *    the device names a page shows for a writer (`who`); described again when Reader's record moves;
 *  - `desc/reader`: Reader's description (the shelf's root, the actions with their levels);
 *  - `reader/<level>`: one of Reader's levels — `shelf`, `folder:<path>`, `book:<id>` (its chapters and text length),
 *    and `book:<id>/part:<n>`, one spine part as `CONTRACT.md` §3.2's document;
 *  - `diag`: the diagnosis (W-21, level full): the record's epoch and `seq`, the live connections, the versions, the PC
 *    log's tail.
 * A page follows places, bookmarks and the book read most recently through the record's own scope (`reader`), so the
 * shelf is not described again at every save point; it is described again when the shelf scan or the reader index
 * moves. A part never changes while its file does not, so it is described once per ask.
 *
 * Build S1-2, package Q2 (the spec's §2.2, §2.5): `desc/main` carries one card per converted window — Reader's from
 * here, every other window's from the card its host builder handed the lane (`AppNet.serve`, [cards]), in the window
 * registry's order — each with its summary from that window's core.
 *
 * Build S1-5, stage W (the lean spec's §6 row W, §5 "The page"): `desc/settings`, every setting in one description —
 * Settings' own rows ([SettingsCore.describe]: Global, Animations by tier, Bastion, each app's style rows) with each
 * converted window's own rows (its registry entry's `rows` hook) first in that app's category, as the glasses' Settings
 * lists an app's own rows before its style rows. Described again when a change touches an item one of its rows reads.
 */
class PcScopes(
    private val host: AppHost,
    private val reader: ReaderCore,
    private val docs: ReaderDocs,
    /** The device table's names by device id (Main's `who`). */
    private val names: () -> Map<String, String> = { emptyMap() },
    /** The diagnosis' own lines (connections, the bundle); null = no diagnosis served. */
    private val diag: (() -> JsonObject)? = null,
    private val minLevel: (String) -> String = AppHost::minLevel,
    /** S1-2 Q2: the other converted windows' cards (`AppNet.cards`), on this provider's describer. */
    private val cards: () -> List<JsonObject> = { emptyList() },
) : AppNet.Described {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        reader.addShelfListener { _, _ -> moved() }
        reader.index?.addListener { moved() }
    }

    private fun moved() {
        for (l in listeners) l()
    }

    override fun addListener(l: () -> Unit) {
        listeners += l
    }

    override fun gate(scope: String): AppNet.Gate? = when {
        // fix round 2, ruling 2.1: the service scans its shelf at its start only; a visit to the shelf, or to Main's
        // card that counts it, asks for a scan (on the scan's own IO job, unless one is running; its listener describes
        // both again). Never from `describe`: the scan's listener calls it, so the scan would repeat without end
        scope == MAIN -> AppNet.Gate(null, Levels.VIEW).also { reader.libraryChanged() }
        scope == READER -> AppNet.Gate(ReaderCore.WINDOW, minLevel(ReaderCore.WINDOW)).also { reader.libraryChanged() }
        scope == READER || scope.startsWith(LEVEL) -> AppNet.Gate(ReaderCore.WINDOW, minLevel(ReaderCore.WINDOW))
        scope == DIAG && diag != null -> AppNet.Gate(null, Levels.FULL)
        scope == SETTINGS -> AppNet.Gate(SettingsCore.WINDOW, Levels.VIEW)
        else -> null
    }

    override fun follows(scope: String, c: Change?): Boolean = when {
        c == null -> scope == MAIN || scope == READER || (scope.startsWith(LEVEL) && partOf(scope) == null)
        // a Settings item, or the item of a window's row (a Games hand or a Reader place moves none)
        scope == SETTINGS -> c.effect.items.keys.any { it.startsWith(Record.prefix(SettingsCore.WINDOW)) || it in rowKeys }
        // the summary names the book read most recently and its place
        else -> scope == MAIN && c.effect.items.keys.any { it.startsWith(Record.prefix(ReaderCore.WINDOW)) }
    }

    override fun describe(scope: String): JsonObject? {
        if (scope == DIAG) return diag?.invoke()?.let { JsonObject(it + base()) }
        val st = state()
        return when {
            scope == MAIN -> main(st)
            scope == READER -> reader.describe(st)
            scope == SETTINGS -> settings()
            scope.startsWith(LEVEL) -> partOf(scope)?.let { (book, n) -> docs.part(book, n)?.let { d ->
                JsonObject(d + ("book" to JsonPrimitive(book)))
            } } ?: reader.level(st, scope.removePrefix(LEVEL))
            else -> null
        }
    }

    private fun state(): WindowState {
        val (_, _, e) = host.read(ReaderCore.WINDOW)
        return WindowState(ReaderCore.WINDOW, e.items)
    }

    private fun stateOf(window: String) = WindowState(window, host.read(window).third.items)

    /** The record items the windows' rows read (each row's id is its item's name). */
    private val rowKeys: Set<String> by lazy {
        Windows.ALL.flatMapTo(HashSet()) { e ->
            e.rows?.invoke(WindowState(e.id, emptyMap()))?.map { Record.key(e.id, (it.jsonObject["id"] as JsonPrimitive).content) }.orEmpty()
        }
    }

    /** `desc/settings`: [SettingsCore.describe] with each window's rows, tagged with their window and item, first in its
     *  app's category. A device's own row (`scope: device`: Torrents' Poll, Feed's Images) is kept by each device, never
     *  on the record, and is left out. */
    private fun settings(): JsonObject {
        val own = SettingsCore.describe(stateOf(SettingsCore.WINDOW))
        val rows = LinkedHashMap<String, List<JsonObject>>()
        for (e in Windows.ALL) {
            val hook = e.rows ?: continue
            rows[e.title] = hook(stateOf(e.id)).map { it.jsonObject }.filter { (it["scope"] as? JsonPrimitive)?.content != "device" }
                .map { r -> JsonObject(r + mapOf("window" to JsonPrimitive(e.id), "item" to (r["item"] ?: r.getValue("id")))) }
        }
        val cats = own.getValue("categories").jsonArray.map { c ->
            val o = c.jsonObject
            val mine = rows.remove((o["name"] as JsonPrimitive).content) ?: return@map o
            JsonObject(o + ("rows" to JsonArray(mine + o.getValue("rows").jsonArray)))
        } + rows.map { (name, rs) -> buildJsonObject { put("name", name); put("rows", JsonArray(rs)) } }
        return JsonObject(own + ("categories" to JsonArray(cats)))
    }

    /** The record's place and the versions, for the diagnosis. */
    private fun base(): Map<String, kotlinx.serialization.json.JsonElement> {
        val (epoch, seq, _) = host.read(ReaderCore.WINDOW)
        return mapOf(
            "epoch" to JsonPrimitive(epoch),
            "seq" to JsonPrimitive(seq),
            "contracts" to buildJsonObject { for ((w, v) in Contracts.MINE.toSortedMap()) put(w, v) },
            "log" to buildJsonArray { for (l in wm.damage.core.util.Log.recent(LOG_TAIL)) add(JsonPrimitive(l)) },
        )
    }

    /** Main's cards (C§3.1 per window: id, name, icon, minimum level, availability, summary). */
    private fun main(st: WindowState): JsonObject = buildJsonObject {
        put("id", "main")
        put("name", "Main")
        put("cards", buildJsonArray {
            add(buildJsonObject {
                put("window", ReaderCore.WINDOW)
                put("name", "Reader")
                put("icon", "book")
                put("min", minLevel(ReaderCore.WINDOW))
                val state = reader.libraryState
                put("availability", availability(state))
                if (state != "ok") put("reason", state)
                put("summary", summary(st))
            })
            for (c in cards()) add(c)
        })
        put("who", buildJsonObject { for ((id, name) in names().toSortedMap()) put(id, name) })
    }

    /** Reader's summary: the book read most recently with its progress, else the shelf's size. */
    private fun summary(st: WindowState): JsonObject = buildJsonObject {
        val shelf = reader.library
        val recent = ReaderCore.recent(st)?.let { id -> shelf.firstOrNull { it.id == id } }
        if (recent == null) {
            put("line", if (shelf.isEmpty()) "no books on the shelf" else "${shelf.size} books")
            return@buildJsonObject
        }
        val fraction = reader.index?.entry(recent.file, recent.bytes)?.fraction(ReaderCore.placeOf(st, recent.id))
        put("book", recent.id)
        put("line", recent.title + (fraction?.let { " · ${Math.round(it * 100)}%" } ?: ""))
        fraction?.let { put("progress", it) }
    }

    companion object {
        const val MAIN = "desc/main"
        const val READER = "desc/reader"
        const val DIAG = "diag"
        /** S1-5 W: every setting (the Settings page). */
        const val SETTINGS = "desc/settings"
        /** A Reader level: `reader/` + the level's address. */
        const val LEVEL = "reader/"
        /** The PC log's lines the diagnosis carries. */
        const val LOG_TAIL = 300

        private val PART = Regex("^reader/book:([0-9a-f]{1,64})/part:(\\d{1,6})$")

        /** Reader's card's availability from its library's state (R-b H1: the health record's `sources` line reads
         *  it too). */
        fun availability(state: String): String = when (state) {
            "ok" -> "live"
            "loading" -> "loading"
            else -> "unavailable"
        }

        /** A part scope as (book, n), or null. */
        fun partOf(scope: String): Pair<String, Int>? =
            PART.matchEntire(scope)?.let { it.groupValues[1] to it.groupValues[2].toInt() }
    }
}
