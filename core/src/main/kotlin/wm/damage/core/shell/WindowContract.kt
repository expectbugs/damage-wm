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

    /** The per-app typography override (Style.kt, 2026-08-31), SET BY THE
     *  SHELL from Settings → <app> → Font/Font size/Font style. Windows route
     *  EVERY FontSpec they measure or draw with through this — their local
     *  draw helpers are the natural chokepoint — so wrap and render always
     *  agree. Defaults to the identity (the app's own design). */
    var styleTransform: (wm.damage.core.text.FontSpec) -> wm.damage.core.text.FontSpec = { it }

    /** A rasterizer view through THIS window's live [styleTransform] — the
     *  one chokepoint a window routes its measuring and drawing through. */
    protected fun styledText(base: wm.damage.core.text.TextRasterizer): wm.damage.core.text.TextRasterizer =
        wm.damage.core.text.StyledText(base) { styleTransform(it) }

    /** What this window needs to be fully functional (§10.5). The shell marks
     *  it unavailable/stale in Main when a need is unmet, and says so. */
    open val needs: Set<Need> = emptySet()

    /** §2 per-app height (REFINEMENT.md, 2026-08-31): the height mode this
     *  window prefers (e.g. 480 for Reader), or null to follow the global
     *  Size setting. Applied when the window takes FOCUS — commit, never a
     *  switcher preview (§4.3 rule 1) — at the cost of one keyframe. */
    open val preferredHeight: Int? get() = null

    /** Rows this window contributes to the Settings window under its own
     *  category (Adam, 2026-08-31: Settings organized by category — Global,
     *  then one per app). Same contract as host rows: options cycle while
     *  adjusting, the choice is staged, [HostSetting.apply] runs on the tap
     *  that keeps it, on the shell loop. Return STABLE instances — the
     *  Settings window matches its staged row by identity. */
    open fun appSettings(): List<HostSetting> = emptyList()

    /** Full persistence (§9.1): EVERYTHING the user could see or was doing —
     *  mode, offsets, cursor, open level. The WM owns calling these. */
    abstract fun saveState(): JsonObject
    abstract fun restoreState(state: JsonObject)

    /** The LIVE-sync arrival of this window's main record — same contract as
     *  [restoreState] (the default delegates), but the window may treat the
     *  incoming record as the NEWER authority where boot restore must not
     *  (R2#7: Reader's transitional legacy-offsets merge needs skip-if-present
     *  at boot and overwrite on live apply — LWW already ruled the incoming
     *  record newer than the stored main key). */
    open fun restoreStateLive(state: JsonObject) = restoreState(state)

    /**
     * Per-ITEM state records (EXPLOSION §16.4a, agreed 2026-09-01): sub-key →
     * blob, saved and SYNCED individually as `window.<id>.<subKey>` so two
     * drivers touching DIFFERENT items (two books, two games) never clobber
     * each other under last-write-wins — a whole-blob record would. Sub-keys
     * are stable item identifiers ("book.<id>"). The shell writes a TOMBSTONE
     * (an empty object) for any stored sub-key this map no longer contains, so
     * removals (a progress reset) sync too; [restoreSubState] receiving an
     * empty object treats it as removal. Keep it cheap — called on every save.
     */
    open fun saveSubState(): Map<String, JsonObject> = emptyMap()

    /** One sub-record arriving — at restore (before the loop runs) or LIVE
     *  from sync (on the loop; the shell repaints after when this window is
     *  focused). An empty [state] is the removal tombstone. */
    open fun restoreSubState(subKey: String, state: JsonObject) {}

    /**
     * §16.1 deep link (agreed 2026-09-01): open this window AT [target] — an
     * OPAQUE per-window string (Mail: a message id · Reader: "book:<id>").
     * Called on the shell loop, AFTER activation, on the COMMIT path only —
     * never for a switcher preview (§4.3 rule 1). The window synthesizes its
     * internal level path so back behaves as if navigated by hand. Return
     * false when unsupported or unresolvable (the item is gone): the shell
     * reports it loudly and the window stays at its root.
     */
    open fun open(target: String): Boolean = false

    /** Called once at shell start for EVERY registered window, before any
     *  restore — the services handle for background completions. */
    open fun onRegistered(ctx: ShellServices) {}

    /** Lifecycle. Preview is a RENDER, never an ACTIVATION (§4.3 rule 1):
     *  activate() runs ONLY on commit — never while the switcher previews. */
    open fun onActivate(ctx: ShellServices) {}
    open fun onDeactivate() {}

    /** The shell layout changed (size mode / safe rect): re-derive any cached
     *  wraps — stale widths would overrun line rects (§2.2b, NO TRUNCATION). */
    open fun onLayoutChanged() {}

    /** Back one level inside the window; false = already at root (the shell
     *  then pops to Main). */
    open fun back(): Boolean = false

    /** How deep the user currently is inside this window (levels on the back
     *  stack) — feeds the bottom divider's depth segments (§4.6). */
    open fun levelDepth(): Int = 1

    /** The global content font scale changed (§4.2 Settings): re-derive any
     *  cached text layout, or overlong lines would clip — NO TRUNCATION. */
    open fun onFontScaleChanged(scale: Double) {}

    /** A typed LINE arrived from a replica (phone strip, browser page,
     *  desktop preview — Transport.injectText). Return true if this window
     *  ACCEPTED it (staged behind its own confirm — text must never run
     *  without one); false lets the shell refuse loudly instead of a typed
     *  line vanishing (the G2CC F10 lesson: an invisible pending state
     *  ambushes later). Only the FOCUSED window is offered text. */
    open fun onTypedText(line: String): Boolean = false

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
        /** Lines moved per ring notch — the window's setting (REFINEMENT.md
         *  §3b, 2026-08-31: one line per notch was far too little in a book;
         *  coarse steps are the design because cost is ack-dominated). */
        val stepLines: () -> Int = { 1 },
        /** Firmware-style scroll acceleration (§0's reversal, 2026-08-30):
         *  fast successive notches in one direction multiply the step. The
         *  ramp itself lives in the shell (Shell.docAccelFactor). */
        val accel: () -> Boolean = { false },
    ) : WindowView

    /** The window owns everything, including damage (Tmux's live grid is the
     *  first user). Input hooks are optional: a canvas without [onScroll] or
     *  [onTap] simply ignores that gesture, and double-tap stays the shell's
     *  back — the §1 grammar is not negotiable per window. */
    class CanvasView(
        val paint: (g: Gray8, rect: Rect) -> Unit,
        val onScroll: ((delta: Int) -> Unit)? = null,
        val onTap: (() -> Unit)? = null,
    ) : WindowView
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

    /** Raise an internal notification (§4.5). The §16.5 signature (2026-09-01):
     *  [thread] is the coalescing key (same source+thread replaces/merges),
     *  [appId] names the window a tap opens, [target] deep-links INTO it
     *  (§16.1) — tap = commit + activate + open(target). */
    fun notifyInternal(source: String, body: String, urgent: Boolean = false,
        appId: String? = null, thread: String = "", target: String? = null)

    /**
     * §16.11 floating context menu (Adam's Files design, 2026-09-01: "a tap
     * should work like a right-click"). Opens [spec] at plane 0 over the
     * focused window's content; scroll moves its cursor, tap commits (the
     * spec's onCommit runs after the menu closed), double-tap cancels. The
     * cursor opens on row 0 — put the primary action (Open) there and keep
     * destructive rows off 0/1 (§1.7). LOOP-ONLY, WINDOW mode only.
     *
     * Returns whether the menu actually SHOWED (R2#8): the shell refuses
     * outside WINDOW mode, under the switcher — and, when [owner] is given,
     * whenever [owner] is not the focused window (an ASYNC completion must
     * never open its menu over someone else's content). A false return is the
     * caller's cue to deliver the answer another way (a notice).
     */
    fun openMenu(spec: MenuSurface.Spec, owner: DamageWindow? = null): Boolean {
        wm.damage.core.util.Log.w("shell", "openMenu: not supported by this host")
        return false
    }

    /** The host's theme-icon source (2026-09-01, Adam: use the desktop theme's
     *  icons "for everything in DamageWM that uses icons"), or null — callers
     *  fall back to the drawn set. */
    fun icons(): wm.damage.core.gfx.IconSource? = null

    /**
     * §16.2 window hand-off (agreed 2026-09-01): focus window [id] — a full
     * COMMIT (recency, activation, notices marked read) — and, when [target]
     * is given, open it there via [DamageWindow.open]; an unresolvable target
     * is reported loudly and the window stays at its root. Pushes the back
     * stack, so double-tap returns to the caller. LOOP-ONLY: call from
     * gesture/commit handlers, or route through [runOnShell].
     * Returns false when no such window is registered.
     */
    fun openWindow(id: String, target: String? = null): Boolean

    /** Run [action] ON THE SHELL LOOP. Background work (IO, layout) computes
     *  off-loop, then applies its state mutations here — windows' view-facing
     *  fields are read by the loop every frame, and mutating them from an IO
     *  coroutine was review round 1's biggest reader race. */
    fun runOnShell(action: () -> Unit)

    /** The current document content width in px (safe-rect relative, §2.2b) —
     *  windows must wrap against THIS, never a hardcoded 640-derived number. */
    fun docContentWidth(): Int

    /** The current content HEIGHT in px, same rule — a window positioning a
     *  DocView at its end (Tmux history's live edge) derives the visible-line
     *  count from this, never from a 480-derived constant. */
    fun docContentHeight(): Int
}
