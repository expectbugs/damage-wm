package wm.damage.core.shell

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The WM's global settings — DESIGN.md §4.2's table. Scope is the window
 * manager, not any app. Persisted through the same store as window state.
 */
@Serializable
data class ShellSettings(
    /** Panel brightness 0..100 (sid 0x09) — distinct from the content ramp. */
    val brightness: Int = 60,
    val brightnessAuto: Boolean = true,

    /** Height mode (§4.2 "Size", revised 2026-08-31): one of [HEIGHTS] —
     *  288 / 352 / 416 / 480 — and always TOP-aligned. Adam: the top is
     *  always visible; it is the BOTTOM that goes under occlusion when the
     *  glasses ride high, so the vertical-position setting was useless and
     *  is retired (an old persisted "vpos" key is ignored). */
    val heightMode: Int = 480,

    /** Content disparity d on the 4 px ladder (§3): content parks far at +d;
     *  0 disables stereo entirely (calibrate at first light). */
    val depth: Int = 8,

    /** Presence: the resting-state ink floor (§4.2) — 0 = rows vanish at rest,
     *  1 = dim names stay. */
    val presence: Int = 1,

    /** Global text scale on the [SCALES] ladder — since 2026-08-31 it scales
     *  CHROME AND MAIN too, not just content (Adam's ask; per-app scale
     *  overrides it for that app's content). */
    val fontScale: Double = 1.0,

    /** The global face for chrome + Main, by Faces label (2026-08-31 — a
     *  recorded REVERSAL of §Type's "the system face is not negotiable":
     *  Adam asked for it, previewed in each candidate's own face). */
    val fontFace: String = "Clear Sans",

    /** Global style force for chrome + Main: "default" keeps the design's own
     *  weights (HEAD accents stay bold); regular/bold/italic FORCE that style
     *  everywhere chrome draws. */
    val fontStyle: String = "default",

    /** Per-app typography + depth, keyed by window id (2026-08-31): face,
     *  scale and style default to the app's own design; depth defaults to 8
     *  so app content pops FORWARD of the global-depth chrome. */
    val appStyles: Map<String, AppStyle> = emptyMap(),

    /** Silent-mode clock size (2026-09-01 Adam): "large" = the original
     *  seven-segment readout, "medium" = a smaller seven-segment, "small" =
     *  the title bar clock's exact size and position. Additive field — an
     *  older build ignores it. */
    val silentClock: String = "large",

    /** The §4.8 keyboard's layout (2026-09-01): "qwerty" (default) or "abc".
     *  Additive field — an older build ignores it. */
    val keyboardLayout: String = "qwerty",

    /** Head tracking — default OFF (§7.1: "that would get old FAST"). */
    val headTracking: Boolean = false,

    /** Long-press (§1.2, revised 2026-08-30): OFF (the default) = a bare
     *  long-press is a no-op everywhere — it is the most common accidental
     *  press by far, all day, gloves worst — and the switcher opens by the
     *  §1.3 chord (long-press, then double-tap inside the window). SWITCHER =
     *  the direct open (and a focused notice's dismiss-unread), the
     *  pre-revision grammar. */
    val longPress: LongPress = LongPress.OFF,

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

    /** MUSIC.md verdict 21 (2026-09-02): the APK stops posting errors to the
     *  phone — they go to the glasses' notices and the log; the one
     *  permanent foreground notification stays. OFF by default. Additive. */
    val phoneNotifications: Boolean = false,
) {
    enum class BatteryAlert { OFF, ON, ESCALATING }
    enum class LongPress { OFF, SWITCHER }

    /** One app's typography + depth overrides ("default" = the app's own
     *  design; scale 0.0 = follow the global scale). */
    @Serializable
    data class AppStyle(
        val face: String = "default",
        val scale: Double = 0.0,
        val style: String = "default",
        val depth: Int = 8,
    ) {
        fun clamped(): AppStyle = copy(
            face = if (face == "default" || wm.damage.core.text.Faces.byLabel(face) != null) face else "default",
            scale = if (scale == 0.0) 0.0 else SCALES.minByOrNull { kotlin.math.abs(it - scale) } ?: 0.0,
            style = if (style in STYLES) style else "default",
            depth = ((depth / 4).coerceIn(0, 4)) * 4,
        )
    }

    fun appStyle(id: String): AppStyle = appStyles[id] ?: AppStyle()

    fun withAppStyle(id: String, f: (AppStyle) -> AppStyle): ShellSettings =
        copy(appStyles = appStyles + (id to f(appStyle(id)).clamped()))

    fun toJson(): JsonObject = Json.encodeToJsonElement(serializer(), this).jsonObject

    /** Persisted files can rot or be hand-edited: every ranged field clamps
     *  back into its legal domain instead of flowing raw toward the wire. */
    fun clamped(): ShellSettings = copy(
        brightness = brightness.coerceIn(0, 100),
        heightMode = HEIGHTS.minByOrNull { kotlin.math.abs(it - heightMode) } ?: 480,
        depth = ((depth / 4).coerceIn(0, 4)) * 4,
        presence = presence.coerceIn(0, 1),
        fontScale = SCALES.minByOrNull { kotlin.math.abs(it - fontScale) } ?: 1.0,
        fontFace = if (wm.damage.core.text.Faces.byLabel(fontFace) != null) fontFace else "Clear Sans",
        fontStyle = if (fontStyle in STYLES) fontStyle else "default",
        appStyles = appStyles.mapValues { it.value.clamped() },
        silentClock = if (silentClock in SILENT_CLOCKS) silentClock else "large",
        keyboardLayout = if (keyboardLayout in KEYBOARDS) keyboardLayout else "qwerty",
    )

    companion object {
        /** The four sizes (2026-08-31): 288 smallest → 480 largest, 64 px
         *  steps, all on the ×2 grid, all tall enough for the chrome. */
        val HEIGHTS = listOf(288, 352, 416, 480)

        /** The text-scale ladder (2026-08-31; 0.85 is the measured §Type
         *  ~20 % byte saver, the upper steps are legibility). */
        val SCALES = listOf(0.85, 1.0, 1.15, 1.3)

        /** A ladder step as the Settings rows print it. Math.round, never
         *  toInt: 1.15 × 100 is 114.999… in binary and the Global row read
         *  "114%" for the ladder's 115 % step (review §29). */
        fun scaleLabel(scale: Double): String = "${Math.round(scale * 100)}%"

        /** Style forces; "default" (per-app only) keeps the app's own flags. */
        val STYLES = listOf("default", "regular", "bold", "italic")

        /** Silent-clock sizes (§1.5, 2026-09-01). */
        val SILENT_CLOCKS = listOf("large", "medium", "small")

        /** Keyboard layouts (§4.8, 2026-09-01). */
        val KEYBOARDS = listOf("qwerty", "abc")
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
