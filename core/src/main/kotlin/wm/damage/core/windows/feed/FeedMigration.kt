package wm.damage.core.windows.feed

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.AppHost
import wm.damage.core.app.Migrate
import wm.damage.core.geom.Layout
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.feed.FeedCore.Companion as C

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B7 (L1 M1's shape, `Migrate`): Feed's state from before its conversion becomes record items where
 * the record holds none of them, on the PC only, at its process start before any presenter reads the record. From the
 * PC store's synced copies: `window.feed` → `recent.reddit`, `recent.slashdot`, `pin.<id>` per pinned id, `fetch`,
 * `keep`, the three `notify.*`, `glasses.size`, `glasses.art.*`, `glasses.levels`; `window.feed.src.<id>` →
 * `read.<id>` (the list as it stands), `flag.<item>` per flag, `seen.<id>`; `window.feed.binge.<id>` → `binge.<id>`
 * (its strip at the PC glasses' width). Dropped: `pcLossSec`. Not migrated: `docTop` (a wrapped-line index; the next
 * notch writes the shared place). Left local: the view's state and `images`.
 */
object FeedMigration {
    /** The width the glasses' archive strips are cut at on the PC (the document column of the default layout). */
    val PC_WIDTH: Int = Layout().contentInner.w - 32

    private fun strings(v: JsonElement): List<String> = C.strings(v)?.distinct() ?: emptyList()

    private fun list(l: List<String>): JsonElement? = l.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map { s -> JsonPrimitive(s) }) }

    private fun art(v: JsonElement): JsonElement? =
        C.text(v)?.let { s -> LineArt.entries.firstOrNull { it.name == s } }?.let { JsonPrimitive(it.name.lowercase()) }

    val MIGRATE = Migrate(C.WINDOW, listOf(
        Migrate.Field("recentSubs", C.RECENT + "reddit") { v -> list(strings(v).take(C.RECENTS_MAX)) },
        Migrate.Field("recentSections", C.RECENT + "slashdot") { v -> list(strings(v).take(C.RECENTS_MAX)) },
        Migrate.Field("fetchMin", C.FETCH) { v -> C.int(v)?.takeIf { it in C.FETCHES.values }?.let { JsonPrimitive(it) } },
        Migrate.Field("keepDays", C.KEEP) { v -> C.int(v)?.takeIf { it in C.KEEPS.values }?.let { JsonPrimitive(it) } },
        Migrate.Field("notifyReddit", C.NOTIFY_REDDIT) { v -> C.bool(v)?.let { JsonPrimitive(it) } },
        Migrate.Field("notifySlashdot", C.NOTIFY_SLASHDOT) { v -> C.bool(v)?.let { JsonPrimitive(it) } },
        Migrate.Field("notifyComics", C.NOTIFY_COMICS) { v -> C.bool(v)?.let { JsonPrimitive(it) } },
        // the old blob writes `height` only for a size of its own (absent = global)
        Migrate.Field("height", C.SIZE) { v -> C.sizeOf(v)?.takeIf { it != 0 }?.let { JsonPrimitive(it) } },
        Migrate.Field("artXkcd", C.ART_XKCD, ::art),
        Migrate.Field("artSmbc", C.ART_SMBC, ::art),
        Migrate.Field("artBinge", C.ART_BINGE, ::art),
        Migrate.Field("comicLevels", C.LEVELS) { v -> C.levelsOf(v)?.let { JsonPrimitive(it) } },
    ), C.MIGRATE,
        many = listOf(Migrate.Many("pinned") { v -> strings(v).associate { id -> (C.PIN + id) to JsonPrimitive(true) } }),
        subs = listOf(
            Migrate.Sub("src.") { id, b -> source(id, b) },
            Migrate.Sub("binge.") { id, b ->
                val ep = C.int(b["ep"])?.takeIf { it > 0 } ?: return@Sub emptyMap()
                mapOf((C.BINGE + id) to FeedCore.Binge(ep, null, C.int(b["strip"])?.coerceAtLeast(0) ?: 0, PC_WIDTH).json())
            },
        ))

    /** One source's record (`saveSubState`'s `src.<id>`): its read list, its flags, its seen stamp. */
    private fun source(id: String, b: JsonObject): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        list(strings(b["read"] ?: JsonArray(emptyList())).takeLast(C.READ_MAX))?.let { out[C.READ + id] = it }
        (b["flags"] as? JsonObject)?.forEach { (item, f) -> C.flagOf(f, id)?.let { out[C.FLAG + item] = it.json() } }
        C.long(b["seen"])?.takeIf { it > 0 }?.let { out[C.SEEN + id] = JsonPrimitive(it) }
        return out
    }

    /** The PC's migration (both host builders, at the process start, before any presenter reads the record). */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit) = MIGRATE.onHost(host, store, note)
}
