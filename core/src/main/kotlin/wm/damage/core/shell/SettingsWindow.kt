package wm.damage.core.shell

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Icons
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
 * reverts, and double-tap climbs back out level by level.
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
) : DamageWindow("settings", "Settings", IconKind.SETTINGS) {

    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val fRowB = FontSpec(Face.SYSTEM, 18, bold = true)
    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)

    private val catModel = ListModel()
    private val model = ListModel()
    /** Which category is open: −1 = the category list. */
    private var openCat = -1
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
    )

    private inner class Cat(val name: String, val entries: List<Entry>)

    private val entries: List<Entry> = listOf(
        Entry("Brightness", { if (get().brightnessAuto) "auto" else "${get().brightness}%" }, { s, d ->
            if (s.brightnessAuto) s.copy(brightnessAuto = false)
            else s.copy(brightness = (s.brightness + d * 5).coerceIn(0, 100))
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
        // MUSIC.md verdict 21: the APK-wide switch for phone notifications
        // (errors go to the glasses and the log; the foreground one stays)
        Entry("Phone notifications", { onOff(get().phoneNotifications) }, { s, d -> s.copy(phoneNotifications = d > 0) }),
        // §4.8 the keyboard's layout
        Entry("Keyboard", { get().keyboardLayout }, { s, d ->
            val ks = ShellSettings.KEYBOARDS
            s.copy(keyboardLayout = ks[(ks.indexOf(s.keyboardLayout).coerceAtLeast(0) + d).mod(ks.size)])
        }),
    )

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
        out.add(Cat("Global", entries + host().map(::hostEntry)))
        for ((app, rows) in apps()) out.add(Cat(app, rows.map(::hostEntry)))
        return out
    }

    /** The open category's rows; an out-of-range [openCat] (an app gone since
     *  the state was saved) drops back to the category list. */
    private fun entriesAt(): List<Entry> {
        val cs = cats()
        if (openCat >= cs.size) openCat = -1
        return if (openCat < 0) emptyList() else cs[openCat].entries
    }

    override fun view(): WindowView {
        entriesAt()                       // clamps a stale openCat first
        if (openCat < 0) return WindowView.ListView(
            catModel,
            rowCount = { cats().size },
            paintRow = { g, i, r, _ -> paintCatRow(g, i, r) },
            paintLens = { g, r, i -> paintCatLens(g, r, i) },
            onCommit = { i ->
                openCat = i
                model.cursor = 0          // cursor rest on entry (§1.7)
            },
        )
        return WindowView.ListView(
            model,
            rowCount = { entriesAt().size },
            paintRow = { g, i, r, _ -> paintRow(g, i, r) },
            paintLens = { g, r, i -> paintLens(g, r, i) },
            onCommit = { i ->
                val e = entriesAt().getOrNull(i) ?: return@ListView
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
        adjusting = null
        revertTo = null
        stagedSize = null
        stagedHost = null
        openCat = -1
        catModel.cursor = 0
    }

    override fun title(): String =
        if (openCat < 0) "" else cats().getOrNull(openCat)?.name?.lowercase() ?: ""

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
        if (openCat >= 0) {
            openCat = -1                 // climb out of the directory; the
            return true                  // category cursor stays where it was
        }
        return false
    }

    override fun levelDepth(): Int = 1 + (if (openCat >= 0) 1 else 0) + (if (adjusting != null) 1 else 0)

    val isAdjusting: Boolean get() = adjusting != null

    private fun paintCatRow(g: Gray8, i: Int, r: Rect) {
        val c = cats().getOrNull(i) ?: return
        text.draw(g, r.x + 40, (r.y + 5) / 2 * 2, c.name, fRow, Level.BODY)
        drawRight(g, r.right - 24, (r.y + 8) / 2 * 2, "${c.entries.size}", fSmall, Level.DIM)
    }

    private fun paintCatLens(g: Gray8, r: Rect, i: Int) {
        val c = cats().getOrNull(i) ?: return
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.SETTINGS, Level.HEAD)
        text.draw(g, r.x + 44, (r.y + 8) / 2 * 2, c.name, fRowB, Level.HEAD)
        text.draw(g, r.x + 44, (r.y + 34) / 2 * 2,
            "${c.entries.size} settings · tap to open", FontSpec(Face.SYSTEM, 14), Level.DIM)
    }

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
        Draw.fit(g, text, r.x + 40, r.y + 7, Draw.dynamic(text, e.name, fSmall), Level.DIM, fSmall, 232)
        val vf = valueFont(e)
        Draw.fit(g, text, r.x + 280, r.y + 5, Draw.dynamic(text, e.value(), vf), Level.BODY, vf,
            r.right - 24 - (r.x + 280))
    }

    private fun paintLens(g: Gray8, r: Rect, i: Int) {
        val e = entriesAt().getOrNull(i) ?: return
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.SETTINGS, Level.HEAD)
        text.draw(g, r.x + 44, (r.y + 8) / 2 * 2, e.name, fRowB, Level.HEAD)
        val vf = valueFont(e)
        // right-aligned while it fits beside the name; past that it is drawn
        // FROM the name's end with the drawn continuation mark, so it can
        // never walk left over the name or off the row (the Main lens shape)
        val v = Draw.dynamic(text, e.value(), vf)
        val nameEnd = r.x + 44 + text.measure(e.name, fRowB) + 16
        val vMax = r.right - 16 - nameEnd
        if (text.measure(v, vf) <= vMax) {
            text.draw(g, (r.right - 16 - text.measure(v, vf)) / 4 * 4, (r.y + 8) / 2 * 2, v, vf, Level.BODY)
        } else {
            Draw.fit(g, text, nameEnd, r.y + 8, v, Level.BODY, vf, vMax)
        }
        val hint = if (adjusting != null) "scroll adjusts live · tap keeps · double-tap reverts"
        else "tap to adjust"
        text.draw(g, r.x + 44, (r.y + 34) / 2 * 2, hint, FontSpec(Face.SYSTEM, 14), Level.DIM)
    }

    override fun summary() = Summary("global · reader · …")

    override fun saveState(): JsonObject = buildJsonObject {
        put("catCursor", catModel.cursor)
        put("openCat", openCat)
        put("cursor", model.cursor)
    }

    override fun restoreState(state: JsonObject) {
        val cs = cats()
        catModel.cursor = (state["catCursor"]?.jsonPrimitive?.int ?: 0).coerceIn(0, maxOf(0, cs.size - 1))
        openCat = (state["openCat"]?.jsonPrimitive?.int ?: -1).coerceIn(-1, cs.size - 1)
        val n = entriesAt().size
        model.cursor = (state["cursor"]?.jsonPrimitive?.int ?: 0).coerceIn(0, maxOf(0, n - 1))
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
