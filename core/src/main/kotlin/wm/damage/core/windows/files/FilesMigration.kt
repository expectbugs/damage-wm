package wm.damage.core.windows.files

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import wm.damage.core.app.AppHost
import wm.damage.core.app.Migrate
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.ShellSettings

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5, as L1 M1 made it: Files' rows from before its conversion become record items where the record
 * holds none of them ([Migrate], on the PC only): the PC store's `window.files` blob's `clipVerb` + `clipPath` →
 * `clip`, `sort` → `sort` (upper case before, the record's lower case), `hidden` → `hidden`, `height` →
 * `glasses.size`. A value that is not one of its row's is not offered (nothing to migrate).
 */
object FilesMigration {
    /** The migration for [store]'s blob: the clipboard's two fields are one item, read together. */
    fun of(store: Persistence): Migrate {
        val blob = store.get("window.${FilesCore.WINDOW}")
        val clipPath = (blob?.get("clipPath") as? JsonPrimitive)?.contentOrNull ?: ""
        return Migrate(FilesCore.WINDOW, listOf(
            Migrate.Field("clipVerb", FilesCore.CLIP) { v -> clip(v, clipPath) },
            Migrate.Field("sort", FilesCore.SORT) { v -> sort(v) },
            Migrate.Field("hidden", FilesCore.HIDDEN) { v -> (v as? JsonPrimitive)?.booleanOrNull?.let { JsonPrimitive(it) } },
            Migrate.Field("height", FilesCore.SIZE) { v ->
                (v as? JsonPrimitive)?.intOrNull?.takeIf { it in ShellSettings.HEIGHTS }?.let { JsonPrimitive(it) }
            },
        ), FilesCore.MIGRATE)
    }

    /** The PC's migration at its process start, before any presenter reads the record (`Migrate.onHost`). */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit) = of(store).onHost(host, store, note)

    private fun clip(verb: JsonElement, path: String): JsonElement? {
        val v = when ((verb as? JsonPrimitive)?.contentOrNull) {
            "COPY" -> "copy"
            "CUT" -> "cut"
            else -> return null
        }
        return if (path.startsWith("/")) FilesCore.clipJson(v, path) else null
    }

    private fun sort(v: JsonElement): JsonElement? =
        (v as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf { it in FilesCore.SORTS }?.let { JsonPrimitive(it) }
}
