package wm.damage.core.shell

import kotlinx.serialization.json.JsonObject
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.geom.Rect

/**
 * What a window declares to the shell — DESIGN.md §4.6's contract table:
 * mode (via its current view), title, summary, icon, dirty, state blob, actions
 * (a LEVEL reached by wrapping, not a region), and what it NEEDS (§10.5).
 */
abstract class DamageWindow(val id: String, val name: String, val icon: IconKind) {

    /** The current level's view. A window may change mode per level (Reader:
     *  library list -> book document -> actions list). */
    abstract fun view(): WindowView

    /** Top-bar context: "what is inside this window right now" (§4.1). */
    open fun title(): String = ""

    /**
     * Main's dashboard line. MUST be cheap and side-effect-free — called for
     * every window on every Main render; never spawn work here (§4.6, G2CC's
     * hard-won preview()/view() split).
     */
    abstract fun summary(): Summary

    /** Wants attention: the top divider's ticks + switcher dirty tick (§4.1). */
    var dirty: Boolean = false

    /** What this window needs to be fully functional (§10.5). The shell marks
     *  it unavailable/stale in Main when a need is unmet, and says so. */
    open val needs: Set<Need> = emptySet()

    /** Full persistence (§9.1): EVERYTHING the user could see or was doing —
     *  mode, offsets, cursor, open level. The WM owns calling these. */
    abstract fun saveState(): JsonObject
    abstract fun restoreState(state: JsonObject)

    /** Lifecycle. Preview is a RENDER, never an ACTIVATION (§4.3 rule 1):
     *  activate() runs ONLY on commit — never while the switcher previews. */
    open fun onActivate(ctx: ShellServices) {}
    open fun onDeactivate() {}

    /** Back one level inside the window; false = already at root (the shell
     *  then pops to Main). */
    open fun back(): Boolean = false

    /** How deep the user currently is inside this window (levels on the back
     *  stack) — feeds the bottom divider's depth segments (§4.6). */
    open fun levelDepth(): Int = 1

    /** The global content font scale changed (§4.2 Settings): re-derive any
     *  cached text layout, or overlong lines would clip — NO TRUNCATION. */
    open fun onFontScaleChanged(scale: Double) {}

    data class Summary(
        val line: String,
        val detail: String = "",
        val more: Boolean = false,          // draws the ▸ continuation mark
        val progress: Double? = null,       // coarse block bar when present
    )

    enum class Need { HOST, PHONE_APIS, BLE }
}

/** The three content modes declare who owns damage (§4.6). List and Document
 *  are WM-driven (nearly free); Canvas hands damage to the window. */
sealed interface WindowView {
    /** Fixed-cursor panning list — the lens is a WM primitive (§4.2/§4.6). */
    class ListView(
        val model: ListModel,
        val rowCount: () -> Int,
        /** Paint one row's content into [rect] (level pre-chosen by the kit). */
        val paintRow: (g: Gray8, index: Int, rect: Rect, dim: Boolean) -> Unit,
        /** Paint the lens band (2 lines) for the focused row. */
        val paintLens: (g: Gray8, rect: Rect, index: Int) -> Unit,
        /** Tap on the focused row. */
        val onCommit: (index: Int) -> Unit,
    ) : WindowView

    /** Endless-scroll document (mode 8 { mode 9 shift + mode 3 fill }). */
    class DocView(
        val model: DocModel,
        val lineCount: () -> Int,
        val lineHeight: Int,
        val paintLine: (g: Gray8, line: Int, rect: Rect) -> Unit,
        /** Tap descends to the window's actions level (§4.6). */
        val onTap: () -> Unit,
    ) : WindowView

    /** The window owns everything, including damage. Not used by stage 1. */
    class CanvasView(val paint: (g: Gray8, rect: Rect) -> Unit) : WindowView
}

class ListModel {
    var cursor: Int = 0
}

class DocModel {
    var topLine: Int = 0
}

/** What the shell offers a window on activation. */
interface ShellServices {
    fun requestRender(window: DamageWindow)
    fun setOperation(op: String)
    fun notifyInternal(source: String, body: String, urgent: Boolean = false)
}
