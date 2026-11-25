package wm.damage.core.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-5 (stage G for Games, stage F for Music): a window's settings row that is one record item, described once —
 * the glasses' words and options, and the rows `desc/settings` carries to the Settings page (C§6).
 */

/** One stored row: [id] its record item, [label] the glasses' words, [scope] `glasses` or `shared`, [options] its
 *  values with their words in the glasses' order, [default] the value an absent item reads as. */
data class StoredRow(val id: String, val label: String, val scope: String, val options: List<Pair<JsonElement, String>>,
                     val default: JsonElement) {
    val type: String get() = if (options.all { (it.first as? JsonPrimitive)?.booleanOrNull != null }) "bool" else "choice"

    /** [v] when it is one of the options, else the default. */
    fun valueOf(v: JsonElement?): JsonElement = options.firstOrNull { it.first == v }?.first ?: default

    fun labelOf(v: JsonElement?): String = valueOf(v).let { x -> options.first { it.first == x }.second }

    companion object {
        /** On and off, [onFirst] deciding which the glasses list first. */
        fun onOff(onFirst: Boolean): List<Pair<JsonElement, String>> =
            if (onFirst) listOf(JsonPrimitive(true) to "on", JsonPrimitive(false) to "off")
            else listOf(JsonPrimitive(false) to "off", JsonPrimitive(true) to "on")

        /** [rows] as the record holds them in [state] (C§6): `{id, label, scope, type, options[{id, label}], value,
         *  default, level}`, each at level control. */
        fun describe(rows: List<StoredRow>, state: WindowState): JsonArray = buildJsonArray {
            for (r in rows) add(buildJsonObject {
                put("id", r.id); put("label", r.label); put("scope", r.scope); put("type", r.type)
                put("options", buildJsonArray { for ((v, l) in r.options) add(buildJsonObject { put("id", v); put("label", l) }) })
                put("value", r.valueOf(state.itemNamed(r.id)?.takeIf { !it.gone }?.value))
                put("default", r.default)
                put("level", Levels.CONTROL)
            })
        }
    }
}
