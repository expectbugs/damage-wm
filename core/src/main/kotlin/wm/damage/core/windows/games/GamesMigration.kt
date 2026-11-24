package wm.damage.core.windows.games

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.AppHost
import wm.damage.core.app.Migrate
import wm.damage.core.shell.Persistence
import wm.damage.core.windows.games.holdem.HoldemTable

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-5, stage G (the lean spec's §4.4): Games' state from before its conversion — the PC store's synced
 * `window.games` blob and its sub-keys `bankroll`, `world`, `table` and `char.<id>`, in APK 0.84's shapes — becomes
 * record items where the record holds none ([Migrate], at the PC's process starts before any presenter reads the
 * record): the eight rows by field, each sub-key as its item, and the table with the blob's tournament figures folded
 * in. A table whose record will not replay is not carried (the presenter said the same of it). A second start writes
 * nothing.
 */
object GamesMigration {
    private fun bool(v: JsonElement): JsonElement? = GamesCore.bool(v)?.let { JsonPrimitive(it) }

    /** A row's value from the blob, kept only when it is one of the row's options. */
    private fun row(id: String, v: JsonElement?): JsonElement? = GamesCore.ROW.getValue(id).options.firstOrNull { it.first == v }?.first

    val FIELDS: List<Migrate.Field> = listOf(
        Migrate.Field("confirm", GamesCore.CONFIRM) { v -> row(GamesCore.CONFIRM, v) },
        Migrate.Field("paceMs", GamesCore.PACE) { v -> GamesCore.long(v)?.let { row(GamesCore.PACE, JsonPrimitive(it)) } },
        Migrate.Field("dealAnim", GamesCore.DEAL, ::bool),
        Migrate.Field("archetypes", GamesCore.ARCHETYPES, ::bool),
        Migrate.Field("notifyBust", GamesCore.NOTIFY_BUST, ::bool),
        Migrate.Field("notifyWin", GamesCore.NOTIFY_WIN, ::bool),
        Migrate.Field("notifyReturn", GamesCore.NOTIFY_RETURN, ::bool),
        Migrate.Field("height", GamesCore.SIZE) { v -> GamesCore.int(v)?.let { row(GamesCore.SIZE, JsonPrimitive(it)) } },
    )

    /** One sub-key's items: [main] is the window blob, whose tournament figures go into the table's item. */
    fun sub(name: String, blob: JsonObject, main: JsonObject?): Map<String, JsonElement> = when {
        name == GamesCore.BANKROLL || name == GamesCore.WORLD || name.startsWith(GamesCore.CHAR) -> mapOf(name to blob)
        name == GamesCore.TABLE -> HoldemTable.load(blob)?.let { t ->
            val m = main ?: JsonObject(emptyMap())
            mapOf(GamesCore.TABLE to GamesCore.tableJson(GamesCore.Tournament(t,
                (GamesCore.int(m["mySeat"]) ?: 0).coerceIn(0, t.occupants.size - 1), GamesCore.int(m["myStake"]) ?: 0,
                (GamesCore.int(m["myCashedOut"]) ?: 0).coerceAtLeast(0), (GamesCore.int(m["myFee"]) ?: 0).coerceAtLeast(0),
                GamesCore.castStakeOf(m["castStake"]), (GamesCore.int(m["bgOwed"]) ?: 0).coerceIn(0, 8),
                GamesCore.bool(m["cashOutPending"]) ?: false)))
        } ?: emptyMap()
        else -> emptyMap()
    }

    /** The migration over [store] (its sub-keys read with the blob beside them). */
    fun of(store: Persistence): Migrate = Migrate(GamesCore.WINDOW, FIELDS, GamesCore.MIGRATE,
        subs = listOf(Migrate.Sub("") { name, blob -> sub(name, blob, store.get("window.${GamesCore.WINDOW}")) }))

    /** The PC's migration (both host builders, at the process start, before any presenter reads the record). */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit) = of(store).onHost(host, store, note)
}
