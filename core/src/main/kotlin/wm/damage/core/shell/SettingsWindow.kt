package wm.damage.core.shell

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.IconPaint
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * ⚙ Settings — Main's last list entry, scope = the window manager (§4.2).
 * Three levels since 2026-08-31 (Adam: categories must be DIRECTORIES, "it
 * takes way way too long to scroll through 50 different things"):
 * the CATEGORY list ("Global", then one per app contributing rows via
 * [DamageWindow.appSettings]) → that category's rows → the ADJUST level
 * where scrolling a value previews LIVE ("you cannot pick a comfortable
 * disparity from a number, you pick it by looking"): tap keeps, double-tap
 * reverts, and double-tap climbs back out level by level. C3 AS (2026-11-21): in
 * `Animations` alone one more list level sits between a category's rows and the
 * adjust level — a tier's row opens that tier's rows (its sub menu).
 */
class SettingsWindow(
    private val text: TextRasterizer,
    private val get: () -> ShellSettings,
    private val apply: (ShellSettings) -> Unit,
    /** Rows the HOST adds after the §4.2 table (HANDOFF.md §8.2): the display
     *  target on the phone and the desktop. Read on every render. */
    private val host: () -> List<HostSetting> = { emptyList() },
    /** Per-app categories: (window name, its rows), read on every render. */
    private val apps: () -> List<Pair<String, List<HostSetting>>> = { emptyList() },
    /** Build C2 (§6.3): the `Local input` row's state in words, as the transport says it ("" before a session). */
    private val localInput: () -> String = { "" },
    /** Build C2 (§6.8's lift): a tap on `Local input` while it is held back re-arms it. Build C3 (§7.9 as ruled,
     *  decisions W, D-2): it lifts the hold-back and keeps the stored stage — `probe local=rearm`. */
    private val localRearm: () -> Unit = {},
    /** Build C2 (decisions AF.0): the build reports DamageCaps bit 10 — `Taps` and `Local input` are shown only then (on
     *  any other firmware they would be rows that do nothing). */
    private val bindBuild: () -> Boolean = { true },
    /** Build ANIM (AV): the motion in force at the height in force, in words ("10 × 45 ms": steps × period), for `Frames`. */
    private val motionHint: () -> String = { "" },
    /** Build C3, stage DP2: ladder stage 5 is in force (DamageCaps bit 11, EFFECTS armed) — the transition tiers' rows
     *  are shown only then (anywhere else a Select, a Back and the clock are cuts, and the rows would do nothing). */
    private val effectsInForce: () -> Boolean = { false },
    /** C3 DM-1 (D3): what plays for the `Scroll effect` row's choice when it is not its own program ("plays Slide" for
     *  Crush below stage 5, "plays its height turn" for Flip), "" otherwise — the row keeps his choice and says it. */
    private val scrollPlays: () -> String = { "" },
) : DamageWindow("settings", "Settings", IconKind.SETTINGS) {

    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val fRowB = FontSpec(Face.SYSTEM, 18, bold = true)
    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)

    private val catModel = ListModel()
    private val model = ListModel()
    /** C3 AS: the cursor inside a tier's sub menu ([openSub]); [model] keeps the page's, so Back lands on the tier's row. */
    private val subModel = ListModel()
    /** Which category is open: −1 = the category list. */
    private var openCat = -1
    /** C3 AS: the tier row whose sub menu is open (one of the open category's rows), null = that category's own rows. */
    private var openSub: Entry? = null
    private var adjusting: Entry? = null
    private var revertTo: ShellSettings? = null
    /** Size relayouts + keyframes, so it is the one setting that must NOT
     *  apply per notch (§4.2): staged while adjusting, applied on tap.
     *  Heights only since 2026-08-31 — always TOP-aligned, vpos retired. */
    private var stagedSize: Int? = null
    /** A host row stages its choice while adjusting and applies on tap, like
     *  Size: applying may restart the whole stack. */
    private var stagedHost: String? = null

    private inner class Entry(
        val name: String,
        val value: () -> String,
        val step: ((ShellSettings, Int) -> ShellSettings)?,
        val hostRow: HostSetting? = null,
        /** A row a tap acts on (no adjusting): build C2's `Local input`. */
        val tap: (() -> Unit)? = null,
        /** C3 AS: a tier's row on the `Animations` page — a tap opens these rows as one more list level. */
        val sub: List<Entry>? = null,
    )

    private inner class Cat(val name: String, val entries: List<Entry>)

    private val entries: List<Entry> = listOf(
        Entry("Brightness", { if (get().brightnessAuto) "auto" else "${get().brightness}%" }, { s, d ->
            // ONE ladder, auto at its foot: auto · 0 % · 5 % · … · 100 %. A
            // notch up from auto leaves it at the stored level; a notch down
            // from 0 % is auto again; nothing sits below auto. It used to leave
            // auto on ANY notch and end at 0 % with no way back, so a
            // brightness touched once on the glasses stayed manual for good
            // (review §29, the live walk)
            when {
                s.brightnessAuto -> if (d > 0) s.copy(brightnessAuto = false) else s
                d < 0 && s.brightness <= 0 -> s.copy(brightnessAuto = true)
                else -> s.copy(brightness = (s.brightness + d * 5).coerceIn(0, 100))
            }
        }),
        Entry("Size", {
            val st = stagedSize
            if (st != null) "$st (tap applies)" else "${get().heightMode}"
        }, null),
        Entry("Depth", { "d=${get().depth}" }, { s, d ->
            // the 4 px ladder: 0/4/8/12/16 (§3.2)
            s.copy(depth = ((s.depth / 4 + d).coerceIn(0, 4)) * 4)
        }),
        Entry("Presence", { if (get().presence == 0) "away at rest" else "dim at rest" }, { s, d ->
            s.copy(presence = (s.presence + d).coerceIn(0, 1))
        }),
        Entry("Head tracking", { if (get().headTracking) "on" else "off (default)" }, { s, d ->
            s.copy(headTracking = d > 0)
        }),
        // §1.2 revised 2026-08-30: off = bare long-press is a no-op and the
        // §1.3 chord opens the switcher; switcher = the direct open
        Entry("Long-press", {
            if (get().longPress == ShellSettings.LongPress.OFF) "off (default)" else "switcher"
        }, { s, d ->
            s.copy(longPress = if (d > 0) ShellSettings.LongPress.SWITCHER else ShellSettings.LongPress.OFF)
        }),
        // build C2 (`FIRMWARE.md` §6.3, §6.7): what a ring tap on a bound list does on the glasses — beside Long-press
        Entry("Taps", {
            val t = get().taps
            t.word + (if (t == wm.damage.core.local.Taps.REPORT) " (default)" else "") +
                (if (localInput().startsWith("held back")) " (no effect: local input held back, stage 3 armed)"
                 else if (localInput() != "on") " (no effect: this firmware / stage)" else "")
        }, { s, d ->
            val ts = wm.damage.core.local.Taps.entries
            s.copy(taps = ts[(s.taps.ordinal + d).coerceIn(0, ts.size - 1)])
        }),
        // §6.3: its state in words; a tap re-arms it while it is held back (§6.8's lift). It changes nothing else.
        Entry("Local input", { localInput().ifEmpty { "no session" } }, null, tap = {
            if (localInput().startsWith("held back")) localRearm()
        }),
        Entry("Battery alert", { get().batteryAlert.name.lowercase() }, { s, d ->
            s.copy(batteryAlert = ShellSettings.BatteryAlert.entries[
                (s.batteryAlert.ordinal + d).mod(ShellSettings.BatteryAlert.entries.size)])
        }),
        Entry("Profiler", { if (get().profiler) "on" else "off" }, { s, d -> s.copy(profiler = d > 0) }),
        Entry("Diag overlay", { if (get().diagOverlay) "on (bring-up)" else "off" }, { s, d ->
            s.copy(diagOverlay = d > 0)
        }),
        // Notification toggles live in each APP's category (Adam, 2026-09-01 —
        // `WINDOWS.md` §1); Global keeps only the WM's own events
        Entry("Notify · Damage", { onOff(get().notifyDamage) }, { s, d -> s.copy(notifyDamage = d > 0) }),
        // the one shared notice-policy row (build W, dec. B.2; CONTRACT.md §7.3, §14 (b)); in this build it holds
        // the bus watcher's notices and "home unreachable" only (QuietHours); S1-2's notice record applies it to
        // every notice — nothing about it moves then. Cycles like Keyboard.
        Entry("Quiet hours", { QuietHours.label(get().quietHours) }, { s, d ->
            val qs = ShellSettings.QUIET_HOURS
            s.copy(quietHours = qs[(qs.indexOf(s.quietHours).coerceAtLeast(0) + d).mod(qs.size)])
        }),
        // MUSIC.md verdict 21: the APK-wide switch for phone notifications
        // (errors go to the glasses and the log; the foreground one stays)
        Entry("Phone notifications", { onOff(get().phoneNotifications) }, { s, d -> s.copy(phoneNotifications = d > 0) }),
        // §4.8 the keyboard's layout
        Entry("Keyboard", { get().keyboardLayout }, { s, d ->
            val ks = ShellSettings.KEYBOARDS
            s.copy(keyboardLayout = ks[(ks.indexOf(s.keyboardLayout).coerceAtLeast(0) + d).mod(ks.size)])
        }),
    )

    /** Build ANIM (decisions AV, AW): the `Animations` category — a bound list's notch motion; shown with `Taps` on a
     *  build that reports local input (bit 10). K1-1 (`MOTION.md` §7): the Scroll tier's rows — `Scroll effect` (one, no
     *  randomness), `Scroll length`, `Scroll frames` (the stored key stays `frames`). The page reads in `MOTION.md` §7's
     *  tier order (decisions BW (3)): Scroll, the transition tiers at stage 5, then Popups ([popupEntries]). */
    private val scrollEntries: List<Entry> = listOf(
        Entry("Scroll effect", {
            val id = get().scrollEffect
            (wm.damage.core.motion.ScrollEffect.byId(id) ?: wm.damage.core.motion.ScrollEffect.DEFAULT).word +
                scrollPlays().let { if (it.isEmpty()) "" else " ($it)" } + if (id == ShellSettings().scrollEffect) " (default)" else ""
        }, { s, d ->
            val es = ShellSettings.SCROLL_EFFECTS
            s.copy(scrollEffect = es[(es.indexOf(s.scrollEffect).coerceAtLeast(0) + d).mod(es.size)])
        }),
        Entry("Scroll length", { "${get().scrollLength} ms" + if (get().scrollLength == ShellSettings().scrollLength) " (default)" else "" }, { s, d ->
            val ls = ShellSettings.SCROLL_LENGTHS
            s.copy(scrollLength = ls[(ls.indexOf(s.scrollLength).coerceAtLeast(0) + d).coerceIn(0, ls.size - 1)])
        }),
        Entry("Scroll frames", {
            val f = get().frames
            (if (f == 0) "auto" else "$f") + motionHint().let { if (it.isEmpty()) "" else " ($it)" } +
                if (f == ShellSettings().frames) " (default)" else ""
        }, { s, d ->
            val fs = ShellSettings.FRAME_COUNTS
            s.copy(frames = fs[(fs.indexOf(s.frames).coerceAtLeast(0) + d).coerceIn(0, fs.size - 1)])
        }),
    )

    /** Build K1-4a: the notice box's arrival (the timer stays 2.0 s); a notice picks among the styles that are on. The
     *  Popups tier, last on the page as `MOTION.md` §7 orders the tiers; every row says "(default)" at its default as
     *  Scroll's and the tiers' rows do (decisions AM (3), BW (3)). */
    private val popupEntries: List<Entry> = listOf(
        Entry("Popup length", { "${get().popupLength} ms" + if (get().popupLength == ShellSettings().popupLength) " (default)" else "" }, { s, d ->
            val ls = ShellSettings.SCROLL_LENGTHS
            s.copy(popupLength = ls[(ls.indexOf(s.popupLength).coerceAtLeast(0) + d).coerceIn(0, ls.size - 1)])
        }),
        Entry("Popup frames", { get().popupFrames.let { (if (it == 0) "auto" else "$it") + if (it == ShellSettings().popupFrames) " (default)" else "" } }, { s, d ->
            val fs = ShellSettings.FRAME_COUNTS
            s.copy(popupFrames = fs[(fs.indexOf(s.popupFrames).coerceAtLeast(0) + d).coerceIn(0, fs.size - 1)])
        }),
        Entry("Popup · Unfurl", { get().popupUnfurl.let { onOff(it) + if (it == ShellSettings().popupUnfurl) " (default)" else "" } }, { s, d -> s.copy(popupUnfurl = d > 0) }),
        Entry("Popup · Fade", { get().popupFade.let { onOff(it) + if (it == ShellSettings().popupFade) " (default)" else "" } }, { s, d -> s.copy(popupFade = d > 0) }),
        Entry("Popup · Slide", { get().popupSlide.let { onOff(it) + if (it == ShellSettings().popupSlide) " (default)" else "" } }, { s, d -> s.copy(popupSlide = d > 0) }),
    )

    /** Build C3, stage DP2 (`MOTION.md` §7): per transition tier its length, its frames (both halves) and one checkbox per
     *  effect — a random pick among the checked at each use, none checked = None. C3 X2T item 7: the
     *  tiers named as his plan names them ([wm.damage.core.motion.Transitions.Tier.title]); every row says "(default)" at
     *  its default as Scroll's rows do; `Length` says what really plays when the period's clamp changes it. C3 AS: each
     *  tier is one row of the page (between Scroll and Popups) and these rows are its sub menu. */
    private val transitionTiers: List<Entry> = wm.damage.core.motion.Transitions.Tier.entries.map { t ->
        Entry(t.title, { checked(wm.damage.core.motion.Transitions.effectsOf(t).filter { it.id in get().effectsOf(t) }.map { it.label }) }, null, sub = listOf(
            Entry("${t.title} length", {
                val l = get().lengthOf(t)
                "$l ms" + (wm.damage.core.motion.Transitions.clampedPlayMs(l, get().framesOf(t))?.let { " (plays $it ms)" } ?: "") +
                    if (l == ShellSettings().lengthOf(t)) " (default)" else ""
            }, { s, d ->
                val ls = ShellSettings.SCROLL_LENGTHS
                s.withTier(t, length = ls[(ls.indexOf(s.lengthOf(t)).coerceAtLeast(0) + d).coerceIn(0, ls.size - 1)])
            }),
            Entry("${t.title} frames", {
                val f = get().framesOf(t)
                val tm = wm.damage.core.motion.Transitions.timing(get().lengthOf(t), f)
                (if (f == 0) "auto" else "$f") + " (${tm.steps} × ${"%.0f".format(tm.period * 1000.0 / 1024)} ms a half)" +
                    if (f == ShellSettings().framesOf(t)) " (default)" else ""
            }, { s, d ->
                val fs = ShellSettings.FRAME_COUNTS
                s.withTier(t, frames = fs[(fs.indexOf(s.framesOf(t)).coerceAtLeast(0) + d).coerceIn(0, fs.size - 1)])
            }),
        ) + wm.damage.core.motion.Transitions.effectsOf(t).map { e ->
            Entry("${t.title} · ${e.label}", {
                val on = e.id in get().effectsOf(t)
                onOff(on) + if (on == (e.id in ShellSettings().effectsOf(t))) " (default)" else ""
            }, { s, d ->
                s.withTier(t, effects = ShellSettings.tierEffects(t, if (d > 0) s.effectsOf(t) + e.id else s.effectsOf(t) - e.id))
            })
        })
    }

    /** C3 AS (his word, 2026-11-21: "a sub menu for each tier, rather than dozens of options in one enormous list"): the
     *  `Animations` page — one row per tier shown at this stage, in `MOTION.md` §7's order, its value what is set inside,
     *  a tap opening the tier's rows. No row is global: every row belongs to a tier. A row added to a tier's list
     *  ([scrollEntries], [transitionTiers], [popupEntries]) is in that tier's sub menu; there is no second list. */
    private val scrollTier = Entry("Scroll", {
        (wm.damage.core.motion.ScrollEffect.byId(get().scrollEffect) ?: wm.damage.core.motion.ScrollEffect.DEFAULT).word
    }, null, sub = scrollEntries)
    private val popupTier = Entry("Popups", {
        checked(listOfNotNull("Unfurl".takeIf { get().popupUnfurl }, "Fade".takeIf { get().popupFade }, "Slide".takeIf { get().popupSlide }))
    }, null, sub = popupEntries)

    /** The name a row is drawn with: inside a tier's sub menu (the title already names the tier) its [Entry.name] less the
     *  tier row's name, or that name without its plural "s" (Popups' "Popup"), and the separator after it — "Select ·
     *  Warp" reads "Warp", "Scroll effect" "Effect"; elsewhere the name. [Entry.name] itself stays: rows are found by it. */
    private fun label(e: Entry): String {
        val t = openSub?.name ?: return e.name
        val p = listOf(t, t.removeSuffix("s")).firstOrNull { e.name.startsWith("$it ") } ?: return e.name
        return e.name.removePrefix(p).trimStart(' ', '·').replaceFirstChar { it.uppercase() }
    }

    /** A checkbox tier's row value: the one effect checked, how many are, or None (nothing plays: it lands at once). */
    private fun checked(on: List<String>): String = when (on.size) { 0 -> "None"; 1 -> on[0]; else -> "${on.size} on" }

    /** Build C3 (DP2): the list level the shell's transitions read — the category list, a category's rows or (C3 AS) a
     *  tier's sub menu; entering and leaving the adjust level changes one row, not the screen, so it is no level here. */
    internal fun listLevel(): Int = 1 + (if (openCat >= 0) 1 else 0) + (if (openSub != null) 1 else 0)

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun hostEntry(h: HostSetting) = Entry(h.name, {
        val st = stagedHost
        if (st != null && adjusting?.hostRow === h) "$st (tap applies)" else h.current()
    }, null, hostRow = h)

    /** The option a host row is SHOWING (staged while adjusting, else the
     *  current one) — what [HostSetting.optionFont] previews. */
    private fun shownOption(e: Entry): String? = e.hostRow?.let { h ->
        if (adjusting?.hostRow === h) stagedHost ?: h.current() else h.current()
    }

    private fun valueFont(e: Entry): FontSpec =
        e.hostRow?.optionFont?.let { of -> shownOption(e)?.let(of) } ?: fRow

    /** The directories (2026-08-31): Global = the §4.2 rows + the host's
     *  rows, then one per app that contributes rows. */
    private fun cats(): List<Cat> {
        val out = ArrayList<Cat>()
        out.add(Cat("Global", (if (bindBuild()) entries else entries.filter { it.name !in BIND_ROWS }) + host().map(::hostEntry)))
        if (bindBuild()) out.add(Cat("Animations", listOf(scrollTier) + (if (effectsInForce()) transitionTiers else emptyList()) + popupTier))
        for ((app, rows) in apps()) out.add(Cat(app, rows.map(::hostEntry)))
        return out
    }

    /** Test introspection: the Global category's row names as shown now. */
    internal fun globalRowNamesForTest(): List<String> = cats().first().entries.map { it.name }
    /** Test introspection (build ANIM): the categories' names, and one category's rows with their values as shown — with
     *  [tier] (C3 AS), the rows of that tier's sub menu instead. */
    internal fun categoryNamesForTest(): List<String> = cats().map { it.name }
    internal fun rowsForTest(cat: String, tier: String? = null): List<Pair<String, String>> =
        cats().firstOrNull { it.name == cat }?.entries?.let { es -> if (tier == null) es else es.firstOrNull { it.name == tier }?.sub }
            ?.map { it.name to it.value() } ?: emptyList()

    /** The open category's rows; an out-of-range [openCat] (an app gone since
     *  the state was saved) drops back to the category list. */
    private fun pageAt(): List<Entry> {
        val cs = cats()
        if (openCat >= cs.size) openCat = -1
        return if (openCat < 0) emptyList() else cs[openCat].entries
    }

    /** The rows of the list shown: the open tier's sub menu, else the open category's rows. A tier no longer on the page
     *  (stage 5 no longer in force) drops back to the page. */
    private fun entriesAt(): List<Entry> {
        val page = pageAt()
        val s = openSub ?: return page
        if (s !in page) { openSub = null; return page }
        return s.sub!!
    }

    override fun view(): WindowView {
        entriesAt()                       // clamps a stale openCat and openSub first
        if (openCat < 0) return WindowView.ListView(
            catModel,
            rowCount = { cats().size },
            paintRow = { g, i, r, _ -> paintCatRow(g, i, r) },
            paintLens = { g, r, i -> paintCatLens(g, r, i) },
            onCommit = { i ->
                openCat = i
                openSub = null
                model.cursor = 0          // cursor rest on entry (§1.7)
            },
        )
        return WindowView.ListView(
            if (openSub != null) subModel else model,
            rowCount = { entriesAt().size },
            paintRow = { g, i, r, _ -> paintRow(g, i, r) },
            paintLens = { g, r, i -> paintLens(g, r, i) },
            onCommit = { i ->
                val e = entriesAt().getOrNull(i) ?: return@ListView
                // C3 AS: a tier's row opens its sub menu, the cursor at rest on its first row (§1.7)
                e.sub?.let { openSub = e; subModel.cursor = 0; return@ListView }
                e.tap?.let { it(); return@ListView }
                if (e.step != null || e.name == "Size" || e.hostRow != null) {
                    adjusting = e
                    revertTo = get()
                    if (e.name == "Size" && e.hostRow == null) stagedSize = get().heightMode
                    e.hostRow?.let { stagedHost = it.current() }
                }
            },
        )
    }

    /** Adam's rule, 2026-09-04 (`HOLDEM.md` §3): Main shows the window's ROOT.
     *  Settings has categories, so its root is the category list — leaving it
     *  inside "Music" because that is where it was last is the same surprise
     *  the rule exists to remove. */
    override fun onActivate(ctx: ShellServices, from: ActivationSource) {
        if (from != ActivationSource.MAIN) return
        clearAdjust()
        openCat = -1
        openSub = null
        catModel.cursor = 0
    }

    /** Drop any half-finished adjust. Shared by the MAIN entry and the
     *  `cat:` deep link so the two cannot drift apart: a STAGED value (Size,
     *  a host row) is simply cancelled, and a row that applies live per notch
     *  keeps what it last previewed — which is what "scroll changes it" means
     *  for Brightness (§4.2). */
    private fun clearAdjust() {
        adjusting = null
        revertTo = null
        stagedSize = null
        stagedHost = null
    }

    /** Does this row STAGE its value (apply on the tap) rather than preview it
     *  live per notch? The two callers that decide it — [onCommit]'s staging
     *  and [adjustStep] — read the same shape, so the hint reads it too rather
     *  than keeping a third copy that can drift. */
    private fun stages(e: Entry): Boolean = e.hostRow != null || e.name == "Size"

    /**
     * §16.1 deep link: `cat:<name>` opens one category directly, so a window
     * can offer "my settings" as a row and actually deliver them. Matched
     * case-insensitively against the directory (`Global`, `Reader`, `Games`…).
     * False when the category does not exist — the shell says so loudly and
     * Settings stays where it was.
     */
    override fun open(target: String): Boolean {
        val want = target.removePrefix("cat:").trim()
        if (want.isEmpty() || !target.startsWith("cat:")) return false
        val i = cats().indexOfFirst { it.name.equals(want, ignoreCase = true) }
        if (i < 0) return false
        clearAdjust()
        openCat = i
        openSub = null
        catModel.cursor = i
        model.cursor = 0
        return true
    }

    /** The category's name; in a tier's sub menu (C3 AS) the category's and the tier's: "animations · close / back". */
    override fun title(): String {
        val c = (if (openCat < 0) null else cats().getOrNull(openCat))?.name?.lowercase() ?: return ""
        return openSub?.let { "$c · ${it.name.lowercase()}" } ?: c
    }

    /** While adjusting, scroll steps the live value (Size only STAGES — §4.2:
     *  it previews on settle, not per notch). Returns true when consumed. */
    fun onScrollAdjust(delta: Int): Boolean {
        val e = adjusting ?: return false
        e.hostRow?.let { h ->
            val opts = h.options()
            if (opts.isEmpty()) return true
            val i = opts.indexOf(stagedHost ?: h.current()).coerceAtLeast(0)
            stagedHost = opts[(i + delta).mod(opts.size)]
            return true
        }
        if (e.name == "Size") {
            // the four TOP-aligned heights (2026-08-31): scroll walks the list
            val h = stagedSize ?: get().heightMode
            val i = ShellSettings.HEIGHTS.indexOf(h).coerceAtLeast(0)
            stagedSize = ShellSettings.HEIGHTS[(i + delta).coerceIn(0, ShellSettings.HEIGHTS.size - 1)]
            return true
        }
        val step = e.step ?: return false
        apply(step(get(), delta))
        return true
    }

    /** Tap while adjusting = keep (Size applies its staged value NOW — the one
     *  ~1.1 s relayout, on settle as designed). Returns true when consumed. */
    fun onTapAdjust(): Boolean {
        val e = adjusting ?: return false
        e.hostRow?.let { h ->
            val choice = stagedHost
            stagedHost = null
            adjusting = null
            revertTo = null
            if (choice != null && choice != h.current()) h.apply(choice)
            return true
        }
        if (e.name == "Size") {
            stagedSize?.let { h -> apply(get().copy(heightMode = h)) }
            stagedSize = null
        }
        adjusting = null
        revertTo = null
        return true
    }

    override fun back(): Boolean {
        val r = revertTo
        if (adjusting != null) {
            adjusting = null
            stagedSize = null
            stagedHost = null
            if (r != null) apply(r)      // double-tap reverts the live preview
            revertTo = null
            return true
        }
        if (openSub != null) {
            openSub = null               // C3 AS: back to the page, its cursor
            return true                  // still on the tier's row
        }
        if (openCat >= 0) {
            openCat = -1                 // climb out of the directory; the
            return true                  // category cursor stays where it was
        }
        return false
    }

    override fun levelDepth(): Int = listLevel() + (if (adjusting != null) 1 else 0)

    val isAdjusting: Boolean get() = adjusting != null

    /** C3 SC1 (the lead's part CD): the row being adjusted STAGES its value (Size, a host row: applied on the tap), so the
     *  shell composes the content after its notch; any other row's notch goes through [apply], whose repaint is the
     *  notch's one. Read before [onScrollAdjust] (a notch never changes which row is adjusted). */
    val adjustingStages: Boolean get() = adjusting?.let(::stages) == true

    private fun paintCatRow(g: Gray8, i: Int, r: Rect) {
        val c = cats().getOrNull(i) ?: return
        text.draw(g, r.x + 40, (r.y + 5) / 2 * 2, c.name, fRow, Level.BODY)
        drawRight(g, r.right - 24, (r.y + 8) / 2 * 2, "${settingsIn(c.entries)}", fSmall, Level.DIM)
    }

    private fun paintCatLens(g: Gray8, r: Rect, i: Int) {
        val c = cats().getOrNull(i) ?: return
        IconPaint.drawKind(g, IconKind.SETTINGS, r.x + 12, r.y + 10, 24, Level.HEAD)
        text.draw(g, r.x + 44, (r.y + 8) / 2 * 2, c.name, fRowB, Level.HEAD)
        text.draw(g, r.x + 44, Draw.lineBelow(text, fRowB, r.y + 8, r.y + 34) / 2 * 2,
            "${settingsIn(c.entries)} settings · tap to open", FontSpec(Face.SYSTEM, 14), Level.DIM)
    }

    /** The settings a list holds, a tier's row counting the rows of its sub menu (C3 AS): `Animations` says 36 at stage 5
     *  as it did when it was one list. */
    private fun settingsIn(es: List<Entry>): Int = es.sumOf { it.sub?.size ?: 1 }

    private fun drawRight(g: Gray8, xRight: Int, y: Int, s: String, f: FontSpec, lv: Int) {
        text.draw(g, (xRight - text.measure(s, f)) / 4 * 4, y, s, f, lv)
    }

    private fun paintRow(g: Gray8, i: Int, r: Rect) {
        val e = entriesAt().getOrNull(i) ?: run {
            wm.damage.core.util.Log.w("settings", "row $i beyond ${entriesAt().size} entries — blank row"); return
        }
        // both columns are FITTED to their own cell: a row value is dynamic
        // (an Output device's product name, a host row's answer) and an
        // unbounded draw put ink past the content rect the kit damages —
        // undamaged composed ink that shows up on the next keyframe out of
        // nowhere (review 2026-09-02)
        Draw.fit(g, text, r.x + 40, r.y + 7, Draw.dynamic(text, label(e), fSmall), Level.DIM, fSmall, 232)
        val vf = valueFont(e)
        Draw.fit(g, text, r.x + 280, r.y + 5, Draw.dynamic(text, e.value(), vf), Level.BODY, vf,
            r.right - 24 - (r.x + 280))
    }

    private fun paintLens(g: Gray8, r: Rect, i: Int) {
        val e = entriesAt().getOrNull(i) ?: return
        IconPaint.drawKind(g, IconKind.SETTINGS, r.x + 12, r.y + 10, 24, Level.HEAD)
        val name = label(e)
        text.draw(g, r.x + 44, (r.y + 8) / 2 * 2, name, fRowB, Level.HEAD)
        val vf = valueFont(e)
        // right-aligned while it fits beside the name; past that it is drawn
        // FROM the name's end with the drawn continuation mark, so it can
        // never walk left over the name or off the row (the Main lens shape)
        val v = Draw.dynamic(text, e.value(), vf)
        val nameEnd = r.x + 44 + text.measure(name, fRowB) + 16
        val vMax = r.right - 16 - nameEnd
        if (text.measure(v, vf) <= vMax) {
            text.draw(g, (r.right - 16 - text.measure(v, vf)) / 4 * 4, (r.y + 8) / 2 * 2, v, vf, Level.BODY)
        } else {
            Draw.fit(g, text, nameEnd, r.y + 8, v, Level.BODY, vf, vMax)
        }
        // 🔴 a STAGED row does not move until the tap, so it must not claim
        // it does (review §30, the live walk): scrolling Size three notches
        // left the panel at 480 under a line reading "scroll adjusts live",
        // which is the shell describing a program that is not running. The
        // value already carries "(tap applies)"; the hint now agrees with it.
        val hint = when {
            // C3 AS: a tier's row opens a list, in a category's words
            e.sub != null -> "${e.sub.size} settings · tap to open"
            // build C2 (decisions AF.9): `Local input` is not adjusted — a tap re-arms it only while it is held back
            e.tap != null -> if (localInput().startsWith("held back")) "tap re-arms" else "state only"
            adjusting == null -> "tap to adjust"
            stages(e) -> "scroll picks · tap applies · double-tap reverts"
            else -> "scroll adjusts live · tap keeps · double-tap reverts"
        }
        text.draw(g, r.x + 44, Draw.lineBelow(text, fRowB, r.y + 8, r.y + 34) / 2 * 2, hint, FontSpec(Face.SYSTEM, 14), Level.DIM)
    }

    private companion object {
        /** Build C2's two rows, shown only on a build that reports bit 10 (decisions AF.0). */
        val BIND_ROWS = setOf("Taps", "Local input")
    }

    override fun summary() = Summary("global · reader · …")

    override fun saveState(): JsonObject = buildJsonObject {
        put("catCursor", catModel.cursor)
        put("openCat", openCat)
        put("cursor", model.cursor)
        // C3 AS: the open tier by name, "" = none (a record without it reads none); by name, not place, since the page's
        // rows depend on the stage, which a restore at a start may not know yet
        put("openSub", openSub?.name ?: "")
        put("subCursor", subModel.cursor)
    }

    override fun restoreState(state: JsonObject) {
        val cs = cats()
        catModel.cursor = (state["catCursor"]?.jsonPrimitive?.int ?: 0).coerceIn(0, maxOf(0, cs.size - 1))
        openCat = (state["openCat"]?.jsonPrimitive?.int ?: -1).coerceIn(-1, cs.size - 1)
        val page = pageAt()
        model.cursor = (state["cursor"]?.jsonPrimitive?.int ?: 0).coerceIn(0, maxOf(0, page.size - 1))
        openSub = state["openSub"]?.jsonPrimitive?.contentOrNull?.let { n -> page.firstOrNull { it.name == n && it.sub != null } }
        subModel.cursor = (state["subCursor"]?.jsonPrimitive?.int ?: 0).coerceIn(0, maxOf(0, (openSub?.sub?.size ?: 1) - 1))
    }
}

/**
 * A Settings row the host supplies (HANDOFF.md §8.2 "Host-supplied Settings
 * rows"): [options] cycle while adjusting, the choice is staged, and [apply]
 * runs on the tap that keeps it — ON THE SHELL LOOP, so a host whose apply
 * restarts the stack must hand the work to its own thread and return.
 */
data class HostSetting(
    val name: String,
    /** A SUPPLIER since 2026-08-31: rows like Reader's "Reset progress" have
     *  options that only exist at adjust time (the opened books). */
    val options: () -> List<String>,
    val current: () -> String,
    val apply: (String) -> Unit,
    /** Font rows (2026-08-31): the spec an OPTION previews in — "each option
     *  displayed using that option's font" (Adam). Return raw=true specs so
     *  the live transforms cannot restyle the candidate. Null = row font. */
    val optionFont: ((String) -> FontSpec?)? = null,
) {
    constructor(name: String, options: List<String>, current: () -> String, apply: (String) -> Unit) :
        this(name, { options }, current, apply, null)
}
