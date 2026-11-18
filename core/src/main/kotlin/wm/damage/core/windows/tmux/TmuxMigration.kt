package wm.damage.core.windows.tmux

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.AppHost
import wm.damage.core.app.Migrate
import wm.damage.core.shell.Persistence

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B15: Tmux's rows from before its conversion — the PC store's `window.tmux` blob in APK 0.68's shape
 * (`TmuxWindow.saveState`: `alerts`, `context`, `height` only for a size of its own, `muted` a list of "host/session")
 * — become `rec.tmux.alerts`, `glasses.context`, `glasses.size` and one `mute.<host>/<session>` per entry, where the
 * record holds none of them ([Migrate], at the PC's two process starts before any presenter reads the record). The
 * level, the target and `paceMs` stay local.
 */
object TmuxMigration {
    val FIELDS: List<Migrate.Field> = listOf(
        Migrate.Field("alerts", TmuxCore.ALERTS) { v -> TmuxCore.bool(v)?.let { JsonPrimitive(it) } },
        Migrate.Field("context", TmuxCore.CONTEXT) { v -> TmuxCore.bool(v)?.let { JsonPrimitive(it) } },
        Migrate.Field("height", TmuxCore.SIZE) { v -> TmuxCore.sizeOf(v)?.takeIf { it != 0 }?.let { JsonPrimitive(it) } },
    )

    /** One mute per `muted` entry; and the old blob's absent `height` is Global (it wrote one only for a size of its
     *  own), so Size becomes 0 there. */
    fun derived(blob: JsonObject): List<Pair<String, JsonElement>> = buildList {
        for (e in blob["muted"] as? JsonArray ?: JsonArray(emptyList())) {
            val k = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if ('/' in k) add(TmuxCore.MUTE + k to JsonPrimitive(true))
        }
        if (blob["height"] == null) add(TmuxCore.SIZE to JsonPrimitive(0))
    }

    val MIGRATE = Migrate(TmuxCore.WINDOW, FIELDS, TmuxCore.MIGRATE, ::derived)

    /** The PC's migration (both host builders, at the process start, before any presenter reads the record). */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit) = MIGRATE.onHost(host, store, note)
}
