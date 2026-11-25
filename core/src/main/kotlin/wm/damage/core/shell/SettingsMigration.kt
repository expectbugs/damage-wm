package wm.damage.core.shell

import kotlinx.serialization.json.JsonObject
import wm.damage.core.app.AppHost
import wm.damage.core.app.Migrate

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-5, stage S (the lean spec's §4.2): Settings' values from before the conversion — the PC store's
 * `shell.settings` (the phone's old sync put it there) (its JSON omits a field at its default) — become record items where the record holds none ([Migrate],
 * at the PC's process starts before any presenter reads the record): each field present as `rec.settings.<field>`, each
 * app's style as `rec.settings.app.<id>`, every value in its full encoding and clamped, stamped with the PC's clock. A
 * field this build no longer stores is not carried; a value that is not one of its field's is not carried. A second
 * start writes nothing.
 */
object SettingsMigration {
    val MIGRATE: Migrate = Migrate(SettingsCore.WINDOW,
        SettingsCore.FIELDS.map { f -> Migrate.Field(f, f) { v -> SettingsCore.itemOf(f, v) } }, SettingsCore.MIGRATE,
        many = listOf(Migrate.Many("appStyles") { v ->
            (v as? JsonObject)?.entries?.mapNotNull { (id, a) ->
                SettingsCore.itemOf(SettingsCore.APP + id, a)?.let { SettingsCore.APP + id to it }
            }?.toMap() ?: emptyMap()
        }),
        key = SettingsCore.STORE_KEY)

    /** The PC's migration (both host builders, at the process start, before any presenter reads the record). */
    fun onHost(host: AppHost, store: Persistence, note: (String) -> Unit) = MIGRATE.onHost(host, store, note)
}
