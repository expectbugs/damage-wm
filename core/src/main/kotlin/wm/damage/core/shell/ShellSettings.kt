package wm.damage.core.shell

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import wm.damage.core.geom.VPos

/**
 * The WM's global settings — DESIGN.md §4.2's table. Scope is the window
 * manager, not any app. Persisted through the same store as window state.
 */
@Serializable
data class ShellSettings(
    /** Panel brightness 0..100 (sid 0x09) — distinct from the content ramp. */
    val brightness: Int = 60,
    val brightnessAuto: Boolean = true,

    /** Height mode (§4.2 "Size"): 480 full or 288 band, plus vertical position. */
    val heightMode: Int = 480,
    val vpos: VPos = VPos.CENTRE,

    /** Content disparity d on the 4 px ladder (§3): content parks far at +d;
     *  0 disables stereo entirely (calibrate at first light). */
    val depth: Int = 8,

    /** Presence: the resting-state ink floor (§4.2) — 0 = rows vanish at rest,
     *  1 = dim names stay. */
    val presence: Int = 1,

    /** Content scale: 1.0 or the measured ~20 % saver 0.85 (§Type). */
    val fontScale: Double = 1.0,

    /** Head tracking — default OFF (§7.1: "that would get old FAST"). */
    val headTracking: Boolean = false,

    /** Battery alert mode for the <=20 % pulse (§4.1). */
    val batteryAlert: BatteryAlert = BatteryAlert.ON,

    /** Status-bar profiler + mode-7 diagnostic overlay (§9.2). Overlay stays ON
     *  through bring-up; flags are treated as hard errors either way. */
    val profiler: Boolean = false,
    val diagOverlay: Boolean = true,

    /** Notification sources — filtered, unlike G2CC (§4.5: the filter is what
     *  makes focus-stealing tolerable). */
    val notifySms: Boolean = true,
    val notifyMail: Boolean = true,
    val notifyMusic: Boolean = true,
    val notifyDamage: Boolean = true,
) {
    enum class BatteryAlert { OFF, ON, ESCALATING }

    fun toJson(): JsonObject = Json.encodeToJsonElement(serializer(), this).jsonObject

    /** Persisted files can rot or be hand-edited: every ranged field clamps
     *  back into its legal domain instead of flowing raw toward the wire. */
    fun clamped(): ShellSettings = copy(
        brightness = brightness.coerceIn(0, 100),
        heightMode = if (heightMode < (288 + 480) / 2) 288 else 480,
        depth = ((depth / 4).coerceIn(0, 4)) * 4,
        presence = presence.coerceIn(0, 1),
        fontScale = if (fontScale < 0.925) 0.85 else 1.0,
    )

    companion object {
        fun fromJson(o: JsonObject?): ShellSettings =
            if (o == null) ShellSettings()
            else try {
                Json { ignoreUnknownKeys = true }.decodeFromJsonElement(serializer(), o).clamped()
            } catch (e: Exception) {
                wm.damage.core.util.Log.e("settings", "unreadable settings — defaults", e)
                ShellSettings()
            }
    }
}
