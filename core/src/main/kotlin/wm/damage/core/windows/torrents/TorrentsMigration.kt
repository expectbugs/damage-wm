package wm.damage.core.windows.torrents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.Migrate
import wm.damage.core.app.RecordBackend
import wm.damage.core.app.WindowState
import wm.damage.core.shell.Persistence

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q4 (the spec's §3.4, §5 F4; `CONTRACT.md` §9.7 item 2): Torrents' records from before its
 * conversion become record items once per host — the local `window.torrents` blob's `recents`, `notifyDone`,
 * `notifyErrors` and `height` become `rec.torrents.recents`, `notify.done`, `notify.errors` and `glasses.size` — on
 * Q2's generic migration (`Migrate`: each field offered once as a `migrate` op expecting `PreConversion(the blob's
 * stamp)`, the ledger `window.torrents.conv` beside the blob). `pollMs` stays local (a device row, decisions A.10).
 * The PC migrates at its process start before any presenter reads the record (both host builders); the phone at a
 * stack's start once the presenter listens, its offers going into the working copy's queue (a loss is said by the
 * presenter). Either deploy order works: an op of a window the PC does not share yet waits in the queue.
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

    /** The ledger's sub-record name beside the blob (`window.torrents.conv`). */
    const val LEDGER: String = Migrate.LEDGER

    /** The PC's migration (both host builders, at the process start, before any presenter reads the record). */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit): Migrate.Result = MIGRATE.onHost(host, store, note)

    /** The phone's migration (at a stack's start, once Torrents' presenter listens). */
    fun onCopy(copy: RecordBackend, store: Persistence, note: (String) -> Unit): Migrate.Result = MIGRATE.onCopy(copy, store, note)

    /**
     * The ledger a presenter writes beside its blob at each save point: of what this build writes into [blob] (today's
     * shape, the copy an older build reads after a downgrade), the fields the record already holds — [state]'s value,
     * or the row's default where it holds none. A later start then offers only what an older build wrote there since;
     * a field the record does not hold yet (an offer that was not taken, an op still on its way) stays out, so the
     * next start offers it again instead of losing it.
     */
    fun ledgerOf(blob: JsonObject, state: WindowState): JsonObject = buildJsonObject {
        put("v", 1)
        put("rows", buildJsonObject {
            for (f in FIELDS) {
                val v: JsonElement = blob[f.name]?.let(f.read) ?: continue
                val held = state.itemNamed(f.item)?.takeIf { !it.gone }?.value ?: TorrentsCore.DEFAULTS[f.item]
                if (held == v) put(f.item, v)
            }
        })
    }
}
