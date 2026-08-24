package wm.damage.core.shell

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.geom.Rect
import wm.damage.core.geom.VPos
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Icons
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * ⚙ Settings — Main's last list entry, scope = the window manager (§4.2).
 * Two levels: the settings list, and an ADJUST level where scrolling a value
 * previews LIVE ("you cannot pick a comfortable disparity from a number, you
 * pick it by looking"): tap keeps, double-tap reverts.
 */
class SettingsWindow(
    private val text: TextRasterizer,
    private val get: () -> ShellSettings,
    private val apply: (ShellSettings) -> Unit,
) : DamageWindow("settings", "Settings", IconKind.SETTINGS) {

    private val fRow = FontSpec(Face.SYSTEM, 18)
    private val fRowB = FontSpec(Face.SYSTEM, 18, bold = true)
    private val fSmall = FontSpec(Face.SYSTEM, 13, bold = true)

    private val model = ListModel()
    private var adjusting: Entry? = null
    private var revertTo: ShellSettings? = null
    /** Size relayouts + keyframes (~1.1 s), so it is the one setting that must
     *  NOT apply per notch (§4.2): staged while adjusting, applied on tap. */
    private var stagedSize: Pair<Int, VPos>? = null

    private inner class Entry(
        val name: String,
        val value: () -> String,
        val step: ((ShellSettings, Int) -> ShellSettings)?,
    )

    private val entries: List<Entry> = listOf(
        Entry("Brightness", { if (get().brightnessAuto) "auto" else "${get().brightness}%" }, { s, d ->
            if (s.brightnessAuto) s.copy(brightnessAuto = false)
            else s.copy(brightness = (s.brightness + d * 5).coerceIn(0, 100))
        }),
        Entry("Size", {
            val st = stagedSize
            if (st != null) "${st.first} · ${st.second.name.lowercase()} (tap applies)"
            else "${get().heightMode} · ${get().vpos.name.lowercase()}"
        }, null),
        Entry("Depth", { "d=${get().depth}" }, { s, d ->
            // the 4 px ladder: 0/4/8/12/16 (§3.2)
            s.copy(depth = ((s.depth / 4 + d).coerceIn(0, 4)) * 4)
        }),
        Entry("Presence", { if (get().presence == 0) "away at rest" else "dim at rest" }, { s, d ->
            s.copy(presence = (s.presence + d).coerceIn(0, 1))
        }),
        Entry("Font size", { "${(get().fontScale * 100).toInt()}%" }, { s, d ->
            s.copy(fontScale = if (d > 0) 1.0 else 0.85)
        }),
        Entry("Head tracking", { if (get().headTracking) "on" else "off (default)" }, { s, d ->
            s.copy(headTracking = d > 0)
        }),
        Entry("Battery alert", { get().batteryAlert.name.lowercase() }, { s, d ->
            s.copy(batteryAlert = ShellSettings.BatteryAlert.entries[
                (s.batteryAlert.ordinal + d).mod(ShellSettings.BatteryAlert.entries.size)])
        }),
        Entry("Profiler", { if (get().profiler) "on" else "off" }, { s, d -> s.copy(profiler = d > 0) }),
        Entry("Diag overlay", { if (get().diagOverlay) "on (bring-up)" else "off" }, { s, d ->
            s.copy(diagOverlay = d > 0)
        }),
        Entry("Notify · SMS", { onOff(get().notifySms) }, { s, d -> s.copy(notifySms = d > 0) }),
        Entry("Notify · Mail", { onOff(get().notifyMail) }, { s, d -> s.copy(notifyMail = d > 0) }),
        Entry("Notify · Music", { onOff(get().notifyMusic) }, { s, d -> s.copy(notifyMusic = d > 0) }),
    )

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    override fun view(): WindowView = WindowView.ListView(
        model,
        rowCount = { entries.size },
        paintRow = { g, i, r, _ -> paintRow(g, i, r) },
        paintLens = { g, r, i -> paintLens(g, r, i) },
        onCommit = { i ->
            val e = entries[i]
            if (e.step != null || e.name == "Size") {
                adjusting = e
                revertTo = get()
                if (e.name == "Size") stagedSize = get().heightMode to get().vpos
            }
        },
    )

    /** While adjusting, scroll steps the live value (Size only STAGES — §4.2:
     *  it previews on settle, not per notch). Returns true when consumed. */
    fun onScrollAdjust(delta: Int): Boolean {
        val e = adjusting ?: return false
        if (e.name == "Size") {
            val (h, v) = stagedSize ?: (get().heightMode to get().vpos)
            stagedSize = when {
                delta > 0 && h == 288 -> 480 to v
                delta < 0 && h == 480 -> 288 to v
                else -> h to VPos.entries[(v.ordinal + delta).mod(VPos.entries.size)]
            }
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
        if (e.name == "Size") {
            stagedSize?.let { (h, v) -> apply(get().copy(heightMode = h, vpos = v)) }
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
            if (r != null) apply(r)      // double-tap reverts the live preview
            revertTo = null
            return true
        }
        return false
    }

    override fun levelDepth(): Int = if (adjusting != null) 2 else 1

    val isAdjusting: Boolean get() = adjusting != null

    private fun paintRow(g: Gray8, i: Int, r: Rect) {
        val e = entries[i]
        text.draw(g, r.x + 40, (r.y + 7) / 2 * 2, e.name, fSmall, Level.DIM)
        text.draw(g, r.x + 280, (r.y + 5) / 2 * 2, e.value(), fRow, Level.BODY)
    }

    private fun paintLens(g: Gray8, r: Rect, i: Int) {
        val e = entries[i]
        Icons.draw(g, r.x + 12, r.y + 10, 24, 24, IconKind.SETTINGS, Level.HEAD)
        text.draw(g, r.x + 44, (r.y + 8) / 2 * 2, e.name, fRowB, Level.HEAD)
        val v = e.value()
        text.draw(g, r.right - 16 - text.measure(v, fRow), (r.y + 8) / 2 * 2, v, fRow, Level.BODY)
        val hint = if (adjusting != null) "scroll adjusts live · tap keeps · double-tap reverts"
        else "tap to adjust"
        text.draw(g, r.x + 44, (r.y + 34) / 2 * 2, hint, FontSpec(Face.SYSTEM, 14), Level.DIM)
    }

    override fun summary() = Summary("brightness · size · depth · presence")

    override fun saveState(): JsonObject = buildJsonObject { put("cursor", model.cursor) }

    override fun restoreState(state: JsonObject) {
        model.cursor = state["cursor"]?.jsonPrimitive?.int ?: 0
    }
}
