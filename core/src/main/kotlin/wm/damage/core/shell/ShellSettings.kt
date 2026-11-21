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
     *  scale and style default to the app's own design; depth defaults to
     *  [GLOBAL_DEPTH] — the app's content follows the Global `Depth` row
     *  unless the app says otherwise (Adam, 2026-09-06: "Depth should always
     *  affect everything on the screen"; the per-app row moves only that
     *  app's content, never the bars). */
    val appStyles: Map<String, AppStyle> = emptyMap(),

    /** Silent-mode clock size (2026-09-01 Adam): "large" = the original
     *  seven-segment readout, "medium" = a smaller seven-segment, "small" =
     *  the title bar clock's exact size and position. Additive field — an
     *  older build ignores it. */
    val silentClock: String = "large",

    /** The §4.8 keyboard's layout (2026-09-01): "qwerty" (default) or "abc".
     *  Additive field — an older build ignores it. */
    val keyboardLayout: String = "qwerty",
    /** Frames per notch for list and document slides (2026-09-06, `HANDOFF.md`
     *  §37/§40 — Adam): one of [SLIDE_FRAMES]. "off" snaps in one copy and
     *  one strip, a number is the ease-out resampled to at most that many
     *  frames, "auto" (the default) is the halving rule with its 8 px floor
     *  (list 3, doc 5). He tests the feel himself; the default stays. The
     *  wheel keeps its own 4 / 2-on-a-slow-link rule (§6.3). Additive. */
    val slideFrames: String = "auto",
    /** How a slide's big strip lands (2026-09-06, `HANDOFF.md` §41.9 —
     *  Adam: "Auto"): "split" sends a strip past `Slide.SPLIT_FILL_PX` BLANK
     *  with the translation and fills it next flush (the glass moves ~70 ms
     *  after the notch; a black band shows until the fill lands — ~610 ms
     *  measured when the fill is pixels); "whole" paints the strip in the
     *  same flush (no band; the first change waits for the strip's bytes);
     *  "auto" (the default) splits only while the texture cache is off —
     *  with the cache live the strip is ~200 B of draws and the split buys
     *  nothing. Additive. */
    val slideFill: String = "auto",
    /** Text through the firmware's texture cache (2026-09-06, `HANDOFF.md`
     *  §40; Adam: adopt it as far as it goes): "off" (the default until it
     *  has been seen on glass) or "on" — the session's fonts are uploaded
     *  once per lease and every plane-0 string ships as a mode-14 draw
     *  instead of its pixels. Additive. */
    val cachedText: String = "off",
    /** Page staging (`HANDOFF.md` §66, 2026-09-16 — Adam: on by default): on a contract-2 session
     *  with cached text on, the focused document's next and previous strips are uploaded to the
     *  cache's reserve off the gesture path and a notch ships one mode-17 draw instead of its
     *  strip. "off" for the A/B. Additive. */
    val pageStaging: String = "on",
    /** The radio's connection priority (2026-09-12, `HANDOFF.md` §47): "high"
     *  (the default — Android's 11.25–15 ms interval, the regime every
     *  measured fast flush ran on) or "balanced" (30–50 ms — for the
     *  on-glass experiment of whether the ~50-minute arm rebuilds follow the
     *  fast parameters). The phone asks for it at connect and asks AGAIN,
     *  paced, whenever the glasses move the link to slower parameters.
     *  Additive. */
    val linkPriority: String = "high",

    /** Head tracking — default OFF (§7.1: "that would get old FAST"). */
    val headTracking: Boolean = false,

    /** Long-press (§1.2, revised 2026-08-30): OFF (the default) = a bare
     *  long-press is a no-op everywhere — it is the most common accidental
     *  press by far, all day, gloves worst — and the switcher opens by the
     *  §1.3 chord (long-press, then double-tap inside the window). SWITCHER =
     *  the direct open (and a focused notice's dismiss-unread), the
     *  pre-revision grammar. */
    val longPress: LongPress = LongPress.OFF,

    /** Build C2 (`FIRMWARE.md` §6.7, §6.3): `Taps` — `phone` · `report` (the default, Adam 2026-11-18) · `glasses`: what a
     *  ring tap on a bound list does on the glasses. On the PC record with the phone's working copy, as every row; it has
     *  effect only on a bit-10 build at stage 4. Additive: an old record reads `report`. */
    val taps: wm.damage.core.local.Taps = wm.damage.core.local.Taps.REPORT,

    /** Build ANIM (decisions AV, AW): the `Animations` category — how long one notch's motion on a bound list takes
     *  (`Scroll length`, ms) and its steps (`Frames`, 0 = Auto: as many as fit at the fitted period). On the PC record with
     *  the phone's working copy, as `taps`; additive: an older record reads the defaults, an older reader ignores them. */
    val scrollLength: Int = 300,
    val frames: Int = 0,
    /** K1-1 (`MOTION.md` §7): `Scroll effect` — the Scroll tier's one effect (no randomness), a [wm.damage.core.motion.
     *  ScrollEffect] id: `none` · `depth` (the default, today's) · `slide` · `fall`. Additive, as the two above. */
    val scrollEffect: String = "depth",
    /** Build K1-4a (`MOTION.md` §7's Popups tier; spec §2): the notice box's arrival — its length (ms) and steps (0 =
     *  Auto: as many as the glasses draw in that length), and the styles a notice picks among at random (none = the box
     *  appears at once). The focus timer stays 2.0 s whatever these say (his ruling). Fade and Slide are drawn by the
     *  glasses' engine; Unfurl is the phone's frames, as before. Additive, as `scrollLength`. */
    val popupLength: Int = 300,
    val popupFrames: Int = 0,
    val popupUnfurl: Boolean = true,
    val popupFade: Boolean = false,
    val popupSlide: Boolean = false,

    /** Build C3, stage DP2 (`MOTION.md` §7): the full-screen transitions' tiers — Select (a tap that opens), Back (a
     *  window or a level left; Select's effects reversed in time) and Lock (the shell's silent clock entered and left) —
     *  each with its length (ms), its frames (both halves; 0 = Auto) and the effects checked (`Transitions.Effect` ids;
     *  a random pick among them at each use, none = None). Shown and played only where ladder stage 5 is in force.
     *  Additive: an older record reads the defaults, an older reader ignores them. */
    val selectLength: Int = 450,
    val selectFrames: Int = 10,
    val selectEffects: List<String> = listOf("depth"),
    val backLength: Int = 450,
    val backFrames: Int = 10,
    val backEffects: List<String> = listOf("depth"),
    val lockLength: Int = 450,
    val lockFrames: Int = 10,
    val lockEffects: List<String> = listOf("fade"),

    /** Battery alert mode for the <=20 % pulse (§4.1). */
    val batteryAlert: BatteryAlert = BatteryAlert.ON,

    /** Status-bar profiler + mode-7 diagnostic overlay (§9.2). Overlay stays ON
     *  through bring-up; flags are treated as hard errors either way. */
    val profiler: Boolean = false,
    val diagOverlay: Boolean = true,

    /** Notification sources — filtered, unlike G2CC (§4.5: the filter is what
     *  makes focus-taking tolerable). */
    val notifySms: Boolean = true,
    val notifyMail: Boolean = true,
    val notifyMusic: Boolean = true,
    val notifyDamage: Boolean = true,

    /** Build W (the lead's decision B.3): the bus alert watcher's glasses notices — `Notify · alerts` (a raise and
     *  its repeats) and `Notify · cleared` — rows of the host category "Bastion" (`bastion/BastionRows.kt`), SHARED
     *  (synced as `shell.settings`). The phone's own `Phone · alerts` and `Home unreachable` rows are the phone's
     *  preferences, never here. Additive: an old record reads the defaults. */
    val notifyAlerts: Boolean = true,
    val notifyCleared: Boolean = true,

    /** Build W (dec. B.2; `CONTRACT.md` §7.3, §14 (b)): the ONE shared quiet-hours row, in Global beside
     *  `Notify · Damage` — a [QuietHours] preset id, default `off`. This build applies it to the bus watcher's
     *  notices and "home unreachable" only; step S1-2's notice record applies it to every notice. */
    val quietHours: String = QuietHours.OFF,

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
        val depth: Int = GLOBAL_DEPTH,
    ) {
        fun clamped(): AppStyle = copy(
            face = if (face == "default" || wm.damage.core.text.Faces.byLabel(face) != null) face else "default",
            scale = if (scale == 0.0) 0.0 else SCALES.minByOrNull { kotlin.math.abs(it - scale) } ?: 0.0,
            style = if (style in STYLES) style else "default",
            depth = if (depth < 0) GLOBAL_DEPTH else ((depth / 4).coerceIn(0, 4)) * 4,
        )
    }

    /** The per-app depth that means "follow the Global row" (§3.1, 2026-09-06). */
    fun appDepthOf(id: String): Int = appStyle(id).depth.let { if (it < 0) depth else it }

    fun appStyle(id: String): AppStyle = appStyles[id] ?: AppStyle()

    /** [slideFrames] as `Slide.frames` wants it: null = auto, 1 = off, else N. */
    fun slideFrameCount(): Int? = when (slideFrames) {
        "auto" -> null
        "off" -> 1
        else -> slideFrames.toIntOrNull()
    }

    /** Build C3 (DP2): a transition tier's length, frames and checked effects, and the same with one of them changed. */
    fun lengthOf(t: wm.damage.core.motion.Transitions.Tier): Int = when (t) {
        wm.damage.core.motion.Transitions.Tier.SELECT -> selectLength; wm.damage.core.motion.Transitions.Tier.BACK -> backLength
        wm.damage.core.motion.Transitions.Tier.LOCK -> lockLength
    }
    fun framesOf(t: wm.damage.core.motion.Transitions.Tier): Int = when (t) {
        wm.damage.core.motion.Transitions.Tier.SELECT -> selectFrames; wm.damage.core.motion.Transitions.Tier.BACK -> backFrames
        wm.damage.core.motion.Transitions.Tier.LOCK -> lockFrames
    }
    fun effectsOf(t: wm.damage.core.motion.Transitions.Tier): List<String> = when (t) {
        wm.damage.core.motion.Transitions.Tier.SELECT -> selectEffects; wm.damage.core.motion.Transitions.Tier.BACK -> backEffects
        wm.damage.core.motion.Transitions.Tier.LOCK -> lockEffects
    }
    fun withTier(t: wm.damage.core.motion.Transitions.Tier, length: Int = lengthOf(t), frames: Int = framesOf(t),
                 effects: List<String> = effectsOf(t)): ShellSettings = when (t) {
        wm.damage.core.motion.Transitions.Tier.SELECT -> copy(selectLength = length, selectFrames = frames, selectEffects = effects)
        wm.damage.core.motion.Transitions.Tier.BACK -> copy(backLength = length, backFrames = frames, backEffects = effects)
        wm.damage.core.motion.Transitions.Tier.LOCK -> copy(lockLength = length, lockFrames = frames, lockEffects = effects)
    }

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
        slideFrames = if (slideFrames in SLIDE_FRAMES) slideFrames else "auto",
        scrollLength = SCROLL_LENGTHS.minByOrNull { kotlin.math.abs(it - scrollLength) } ?: 300,
        frames = if (frames in FRAME_COUNTS) frames else 0,
        scrollEffect = if (scrollEffect in SCROLL_EFFECTS) scrollEffect else "depth",
        popupLength = SCROLL_LENGTHS.minByOrNull { kotlin.math.abs(it - popupLength) } ?: 300,
        popupFrames = if (popupFrames in FRAME_COUNTS) popupFrames else 0,
        selectLength = SCROLL_LENGTHS.minByOrNull { kotlin.math.abs(it - selectLength) } ?: 450,
        selectFrames = if (selectFrames in FRAME_COUNTS) selectFrames else 10,
        selectEffects = storedEffects(wm.damage.core.motion.Transitions.Tier.SELECT, selectEffects),
        backLength = SCROLL_LENGTHS.minByOrNull { kotlin.math.abs(it - backLength) } ?: 450,
        backFrames = if (backFrames in FRAME_COUNTS) backFrames else 10,
        backEffects = storedEffects(wm.damage.core.motion.Transitions.Tier.BACK, backEffects),
        lockLength = SCROLL_LENGTHS.minByOrNull { kotlin.math.abs(it - lockLength) } ?: 450,
        lockFrames = if (lockFrames in FRAME_COUNTS) lockFrames else 10,
        lockEffects = storedEffects(wm.damage.core.motion.Transitions.Tier.LOCK, lockEffects),
        slideFill = if (slideFill in SLIDE_FILLS) slideFill else "auto",
        cachedText = if (cachedText in CACHED_TEXT) cachedText else "off",
        pageStaging = if (pageStaging in PAGE_STAGING) pageStaging else "on",
        linkPriority = if (linkPriority in LINK_PRIORITIES) linkPriority else "high",
        quietHours = if (quietHours in QUIET_HOURS) quietHours else QuietHours.OFF,
    )

    companion object {
        /** The four sizes (2026-08-31): 288 smallest → 480 largest, 64 px
         *  steps, all on the ×2 grid, all tall enough for the chrome. */
        val HEIGHTS = listOf(288, 352, 416, 480)
        /** [AppStyle.depth] sentinel: the app's content follows the Global row. */
        const val GLOBAL_DEPTH = -1
        /** The 4 px disparity ladder (§3.2). */
        val DEPTHS = listOf(0, 4, 8, 12, 16)

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
        /** Slide frames per notch (§40, 2026-09-06): the Global row's order. */
        val SLIDE_FRAMES = listOf("off", "2", "4", "auto", "8", "12")
        /** Build ANIM (AV): the `Scroll length` ladder (ms) and the `Frames` choices (0 = Auto). */
        val SCROLL_LENGTHS = listOf(225, 300, 450, 600, 800)
        val FRAME_COUNTS = listOf(0, 4, 6, 8, 10, 12, 16)
        /** K1-1: the `Scroll effect` row's order (the stored ids). */
        val SCROLL_EFFECTS: List<String> = wm.damage.core.motion.ScrollEffect.entries.map { it.id }
        /** Build C3 (DP2): a tier's checked effects as stored — the ids that tier offers, once each, in its rows' order. */
        fun tierEffects(t: wm.damage.core.motion.Transitions.Tier, ids: List<String>): List<String> =
            wm.damage.core.motion.Transitions.effectsOf(t).map { it.id }.filter { it in ids }
        /** C3 DM-T: a stored value as read — [tierEffects]; a value that named effects but none this build draws reads the
         *  tier's defaults (an empty one stays None, his choice). Never a refusal he sees. */
        fun storedEffects(t: wm.damage.core.motion.Transitions.Tier, ids: List<String>): List<String> =
            tierEffects(t, ids).ifEmpty { if (ids.isEmpty()) emptyList() else wm.damage.core.motion.Transitions.defaultsOf(t).map { it.id } }
        val SLIDE_FILLS = listOf("auto", "split", "whole")
        /** The texture-cache switch (§40). */
        val CACHED_TEXT = listOf("off", "on")
        val PAGE_STAGING = listOf("off", "on")
        /** The radio's connection priority (§47, 2026-09-12): the Global row's order. */
        val LINK_PRIORITIES = listOf("high", "balanced")
        /** The quiet-hours presets' stored ids, in the Global row's order (build W, dec. B.2). */
        val QUIET_HOURS: List<String> = QuietHours.IDS
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
