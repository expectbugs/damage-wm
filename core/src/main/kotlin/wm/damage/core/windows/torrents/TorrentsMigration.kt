package wm.damage.core.windows.torrents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.AppHost
import wm.damage.core.app.Migrate
import wm.damage.core.shell.Persistence

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q4, as L1 M1 made it: Torrents' rows from before its conversion — the PC store's
 * `window.torrents` blob's `recents`, `notifyDone`, `notifyErrors` and `height` — become `rec.torrents.recents`,
 * `notify.done`, `notify.errors` and `glasses.size` where the record holds none of them ([Migrate], at the PC's two
 * process starts before any presenter reads the record). `pollMs` stays local (a device row, decisions A.10).
 */
object TorrentsMigration {
    /** The blob's fields and the record items they become, each read as the record holds it (null: nothing to
     *  migrate — absent, empty, or not a value of that row). */
    val FIELDS: List<Migrate.Field> = listOf(
        Migrate.Field("recents", TorrentsCore.RECENTS) { v ->
            TorrentsCore.recentsOf(v)?.takeIf { it.isNotEmpty() }?.let { l -> JsonArray(l.map { JsonPrimitive(it) }) }
        },
        Migrate.Field("notifyDone", TorrentsCore.NOTIFY_DONE) { v -> TorrentsCore.bool(v)?.let { JsonPrimitive(it) } },
        Migrate.Field("notifyErrors", TorrentsCore.NOTIFY_ERRORS) { v -> TorrentsCore.bool(v)?.let { JsonPrimitive(it) } },
        // the old blob writes `height` only for a size of its own (absent = global)
        Migrate.Field("height", TorrentsCore.SIZE) { v ->
            TorrentsCore.sizeOf(v)?.takeIf { it != 0 }?.let { JsonPrimitive(it) }
        },
    )

    val MIGRATE = Migrate(TorrentsCore.WINDOW, FIELDS, TorrentsCore.MIGRATE)

    /** The PC's migration (both host builders, at the process start, before any presenter reads the record). */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit) = MIGRATE.onHost(host, store, note)
}
