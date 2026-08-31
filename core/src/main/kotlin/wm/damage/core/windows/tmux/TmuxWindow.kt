package wm.damage.core.windows.tmux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.HostSetting
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.WindowView
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.util.Log

/**
 * TMUX — watch and drive real tmux sessions from the glasses (TMUX.md, all
 * verdicts locked 2026-08-31). Levels, mapped onto the §1 grammar:
 *
 *   SESSIONS (list)  every host's sessions, waiting-first; wrap-to-end rows
 *                    create a session per host (auto-named g2-N)
 *   LIVE (canvas)    the pane as FLOWED TEXT (2026-08-31, the grid
 *                    retirement — Adam: "it's just text ... embrace our
 *                    ability to present the terminal better"): logical lines
 *                    (`-J`), wrapped at our width through the per-app
 *                    font/size/style, SGR -> levels, rule lines drawn as
 *                    rules, a tail cursor marker; the GRID (TermRender)
 *                    survives only for alternate-screen TUIs. Scroll-up
 *                    enters history, tap descends to keys
 *   HISTORY (canvas) a FROZEN scrollback snapshot (G2CC's lesson: history
 *                    does not shift under the reader) through the SAME flow —
 *                    same face, size, width, colours (Adam 2026-08-31: no
 *                    font/size switch when scrolling); notches move 5 display
 *                    lines, and the notch that reaches the live edge RETURNS
 *                    TO LIVE; double-tap returns too
 *   KEYS (list)      the quick dozen (verdict 4) + Snippets/Type/Windows/
 *                    Session…; every send drops back to LIVE to watch it run
 *   plus SNIPPETS, WINDOWS (view any window non-invasively), SESSION_ACTIONS
 *   (mute · fit-to-glass · select-window · rename · kill), KILL_CONFIRM and
 *   TYPE_CONFIRM (typed text ALWAYS stages here before anything runs).
 *
 * Keys reach ONE explicitly opened target only (the G2CC rule). Every
 * provider failure lands in the one-shot [notice] riding the title (their D3
 * lesson: send failures must show ON GLASS, not in a log).
 */
class TmuxWindow(
    private val text: TextRasterizer,
    private val provider: TmuxProvider,
    private val bg: CoroutineScope,
) : DamageWindow("tmux", "Tmux", IconKind.TERMINAL) {

    private enum class Level_ { SESSIONS, LIVE, HISTORY, KEYS, SNIPPETS, WINDOWS, SESSION_ACTIONS, KILL_CONFIRM, TYPE_CONFIRM }

    private var level = Level_.SESSIONS
    private val sesModel = ListModel()
    private val keysModel = ListModel()
    private val snipModel = ListModel()
    private val winModel = ListModel()
    private val actModel = ListModel()
    private val confirmModel = ListModel()

    /** Every measure/draw goes through the per-app style (Style.kt). */
    private val tx = styledText(text)
    /** The FLOW view (2026-08-31, the grid retirement): normal panes render
     *  as wrapped typographic text; [renderer] (the grid) survives only as
     *  the alternate-screen fallback — a full-screen TUI needs its lattice. */
    private val flow = FlowRender(tx)
    private val renderer = TermRender(tx)
    private var services: ShellServices? = null

    // ---- provider-pushed state (mutated on the shell loop only) ----
    private var sessions: List<TmuxSessionInfo> = emptyList()
    private var cfg = TmuxConfig()
    private var provState = ""
    private var target: TmuxTarget? = null
    private var frame: PaneFrame? = null

    // ---- history (frozen at entry; rendered through the LIVE fit — same
    // face, size and width as the pane, an offset back from the live edge) ----
    private var histRaw: List<String>? = null
    private var histLoading = false
    private var histOffset = 0
    private var histMax = 0

    // ---- windows level ----
    private var wins: List<TmuxWinInfo> = emptyList()

    // ---- typed text (verdict 1: via the replicas; always confirm-to-run) ----
    private enum class TypedPurpose { SEND, RENAME }
    private var typed: String? = null
    private var typedPurpose = TypedPurpose.SEND
    private var typedReturn = Level_.LIVE
    /** Rename armed from Session… — the NEXT typed line is the new name. */
    private var renameArmed = false

    // ---- settings (the Settings window's Tmux category — verdict 6) ----
    private var wantContext = true            // grid fallback only since the flow rework
    private var alertsOn = true               // verdict 3: on for all + mute
    private var heightPref: Int? = 480        // TMUX.md §3.3: 480 is the design point
    /** Capture pacing (2026-08-31, "1 s configurable"): pushed to the provider
     *  on apply and on restore, so the host polls at the chosen cadence. */
    private var paceMs = 1_000L
    private val muted = LinkedHashSet<String>()   // "host/session"

    /** One-shot failure/notice text riding the title (G2CC D3). */
    private var notice: String? = null

    private val fRow = FontSpec(Face.MONO, 18)
    private val fSmall = FontSpec(Face.MONO, 14)
    private val fRead = FontSpec(Face.MONO, 16)
    private val fBig = FontSpec(Face.MONO, 18, bold = true)

    override val preferredHeight: Int? get() = heightPref
    override val needs: Set<Need> = setOf(Need.HOST)

    private fun mutedKey(s: TmuxSessionInfo) = "${s.host}/${s.name}"
    private fun mutedKey(t: TmuxTarget) = "${t.host}/${t.session}"

    private val listener = object : TmuxProvider.Listener {
        override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) {
            onShell {
                this@TmuxWindow.sessions = sessions
                this@TmuxWindow.cfg = cfg
                dirty = alertsOn && sessions.any { it.waiting && mutedKey(it) !in muted }
                if (level == Level_.SESSIONS) services?.requestRender(this@TmuxWindow)
            }
        }

        override fun frame(target: TmuxTarget, frame: PaneFrame) {
            onShell {
                if (target != this@TmuxWindow.target) return@onShell
                this@TmuxWindow.frame = frame
                if (level == Level_.LIVE) services?.requestRender(this@TmuxWindow)
            }
        }

        override fun alert(session: TmuxSessionInfo) {
            onShell {
                if (!alertsOn || mutedKey(session) in muted) return@onShell
                dirty = true
                services?.notifyInternal("tmux",
                    "${session.name}${hostSuffix(session.host)} wants input: ${session.lastLine.take(80)}")
            }
        }

        override fun state(state: String) {
            onShell {
                if (provState != state) {
                    provState = state
                    if (level == Level_.LIVE || level == Level_.SESSIONS) services?.requestRender(this@TmuxWindow)
                }
            }
        }
    }

    /** Every provider push hops to the shell loop before touching state the
     *  view reads — the Reader threading rule (review round 1's reader race). */
    private fun onShell(action: () -> Unit) {
        services?.runOnShell(action) ?: action()
    }

    private fun hostSuffix(host: String): String =
        if (hosts().size <= 1) "" else " · $host"

    private fun hosts(): List<String> {
        val fromSessions = sessions.map { it.host }.distinct()
        return fromSessions.ifEmpty { listOf("local") }
    }

    override fun onRegistered(ctx: ShellServices) {
        services = ctx
        provider.addListener(listener)   // alerts live from registration on
    }

    /** Unhook from the provider — a rebuilt stack replaces this window while
     *  the provider outlives it (the LensView.detach precedent): a dead
     *  window's listener must not keep receiving pushes. */
    fun detach() {
        provider.subscribe(listener, null)
        provider.removeListener(listener)
    }

    override fun onActivate(ctx: ShellServices) {
        services = ctx
        if (level == Level_.LIVE || level == Level_.HISTORY || level == Level_.KEYS) resubscribe()
    }

    override fun onDeactivate() {
        provider.subscribe(listener, null)   // parked windows hold no capture loop (G2CC's orphan-poll lesson)
    }

    override fun onLayoutChanged() {
        renderer.invalidate()
        flow.invalidate()
    }

    override fun onFontScaleChanged(scale: Double) {
        renderer.invalidate()
        flow.invalidate()
    }

    private fun resubscribe() {
        provider.subscribe(listener, target)
    }

    // ------------------------------------------------------------------ views
    override fun view(): WindowView = when (level) {
        Level_.SESSIONS -> sessionsView()
        Level_.LIVE -> WindowView.CanvasView(
            paint = { g, r -> paintLive(g, r) },
            onScroll = { d -> if (d < 0) enterHistory() },   // scroll-up = time; down at live = no-op
            onTap = { level = Level_.KEYS; keysModel.cursor = 0 },
        )
        Level_.HISTORY -> WindowView.CanvasView(
            paint = { g, r -> paintHistory(g, r) },
            onScroll = { d -> histScroll(d) },
            onTap = { level = Level_.KEYS; keysModel.cursor = 0 },
        )
        Level_.KEYS -> listView(keysModel, { keysRows().size }, { g, i, r, dim -> paintPlainRow(g, keysRows()[i], i, r, dim) },
            { g, r, i -> paintPlainLens(g, r, keysRows().getOrElse(i) { "" }) }, { i -> keysCommit(i) })
        Level_.SNIPPETS -> listView(snipModel, { cfg.snippets.size + 1 },
            { g, i, r, dim -> paintPlainRow(g, snipRows()[i], i, r, dim) },
            { g, r, i -> paintPlainLens(g, r, snipRows().getOrElse(i) { "" }) }, { i -> snipCommit(i) })
        Level_.WINDOWS -> listView(winModel, { wins.size },
            { g, i, r, dim -> paintWinRow(g, i, r, dim) },
            { g, r, i -> paintPlainLens(g, r, wins.getOrNull(i)?.let { "${it.idx}: ${it.name}" } ?: "") },
            { i -> winCommit(i) })
        Level_.SESSION_ACTIONS -> listView(actModel, { actionRows().size },
            { g, i, r, dim -> paintPlainRow(g, actionRows()[i], i, r, dim) },
            { g, r, i -> paintPlainLens(g, r, actionRows().getOrElse(i) { "" }) }, { i -> actionCommit(i) })
        Level_.KILL_CONFIRM -> listView(confirmModel, { 2 },
            { g, i, r, dim -> paintPlainRow(g, killRows()[i], i, r, dim) },
            { g, r, i -> paintPlainLens(g, r, killRows().getOrElse(i) { "" }) }, { i -> killCommit(i) })
        Level_.TYPE_CONFIRM -> listView(confirmModel, { 2 },
            { g, i, r, dim -> paintPlainRow(g, typeRows()[i], i, r, dim) },
            { g, r, _ -> paintTypedLens(g, r) }, { i -> typeCommit(i) })
    }

    private fun listView(model: ListModel, count: () -> Int,
        row: (Gray8, Int, Rect, Boolean) -> Unit, lens: (Gray8, Rect, Int) -> Unit,
        commit: (Int) -> Unit): WindowView.ListView =
        WindowView.ListView(model, count, row, lens, { i -> notice = null; commit(i) })

    // ------------------------------------------------------------------ sessions
    private fun sessionsView(): WindowView.ListView {
        val hostList = hosts()
        return listView(sesModel, { sessions.size + hostList.size },
            { g, i, r, dim -> paintSessionRow(g, i, r, dim) },
            { g, r, i -> paintSessionLens(g, r, i) },
            { i -> sessionsCommit(i) })
    }

    private fun paintSessionRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val hostList = hosts()
        if (i >= sessions.size) {
            val host = hostList.getOrElse(i - sessions.size) { "local" }
            paintPlainRow(g, "+ new session${hostSuffix(host)}", i, r, dim)
            return
        }
        val s = sessions[i]
        val lv = if (dim) Level.DIM else Level.BODY
        if (s.waiting) g.fillRect(r.x + 8, r.y + r.h / 2 - 4, 8, 8, if (dim) Level.MID else Level.HOT)
        else if (s.attached) g.fillRect(r.x + 10, r.y + r.h / 2 - 3, 6, 6, Level.DIM)
        drawFit(g, r.x + 28, r.y + 5, s.name, if (s.waiting && !dim) Level.HEAD else lv, fRow, r.w - 200)
        drawRight(g, r.right - 16, r.y + 8,
            "${s.windows}w${hostSuffix(s.host)} · ${age(s.activity)}", Level.DIM, fSmall)
    }

    private fun paintSessionLens(g: Gray8, r: Rect, i: Int) {
        if (i >= sessions.size) {
            val host = hosts().getOrElse(i - sessions.size) { "local" }
            drawFit(g, r.x + 16, r.y + 6, "+ new session${hostSuffix(host)}", Level.HEAD, fBig, r.w - 32)
            drawFit(g, r.x + 16, r.y + 34, "tap to create g2-N, detached", Level.BODY, fSmall, r.w - 32)
            return
        }
        val s = sessions[i]
        val head = s.name + hostSuffix(s.host) + (if (s.waiting) " — wants input" else "")
        drawFit(g, r.x + 16, r.y + 6, head, if (s.waiting) Level.HOT else Level.HEAD, fBig, r.w - 32)
        drawFit(g, r.x + 16, r.y + 34, sanitize(s.lastLine).ifEmpty { "(no output)" }, Level.BODY, fSmall, r.w - 32)
    }

    private fun sessionsCommit(i: Int) {
        if (i < sessions.size) {
            open(TmuxTarget(sessions[i].host, sessions[i].name))
            return
        }
        val host = hosts().getOrElse(i - sessions.size) { "local" }
        busy("creating a session on $host") {
            val name = provider.newSession(host)
            onShell { open(TmuxTarget(host, name)) }
        }
    }

    private fun open(t: TmuxTarget) {
        target = t
        frame = null
        level = Level_.LIVE
        renameArmed = false
        resubscribe()
        services?.requestRender(this)
    }

    // ------------------------------------------------------------------ live
    private fun paintLive(g: Gray8, rect: Rect) {
        g.fillRect(rect, Level.BG)
        val f = frame
        if (f == null) {
            drawFit(g, rect.x + 16, rect.y + 12, "capturing ${target?.label ?: "?"}…", Level.BODY, fRow, rect.w - 32)
            if (provState.isNotEmpty()) {
                drawFit(g, rect.x + 16, rect.y + 44, provState, Level.DIM, fSmall, rect.w - 32)
            }
            return
        }
        // FLOW for normal panes (the terminal is text); the GRID only when a
        // full-screen TUI owns the pane and flowed text would lie about it
        if (f.alternate) renderer.render(g, rect, f, wantContext)
        else flow.renderTail(g, rect, f)
        if (provState.isNotEmpty()) {
            // the staleness surface (§10.5): the view stays, the trouble is SAID
            drawFit(g, rect.x + 16, rect.bottom - 20, provState, Level.HOT, fSmall, rect.w - 32)
        }
    }

    // ------------------------------------------------------------------ history
    private fun enterHistory() {
        val t = target ?: return
        if (histLoading) return
        histLoading = true
        histOffset = HIST_STEP           // one notch into the past
        level = Level_.HISTORY
        services?.requestRender(this)
        busy("capturing scrollback") {
            try {
                val raw = provider.history(t, HISTORY_LINES)
                onShell {
                    histLoading = false
                    if (level != Level_.HISTORY) return@onShell   // left while capturing: stay left
                    histRaw = raw
                    services?.requestRender(this)
                }
            } catch (e: Exception) {
                // a failed capture must NOT strand histLoading=true — that froze
                // the whole window (every scroll early-returns on the flag) until
                // a restart, exactly what Adam hit after a connectivity blip
                // (2026-08-31). Fall back to LIVE; busy() shows the notice.
                onShell {
                    histLoading = false
                    if (level == Level_.HISTORY) level = Level_.LIVE
                }
                throw e
            }
        }
    }

    /** Scroll IS time both ways: up goes older, down toward now — and the
     *  notch that reaches the live edge RETURNS TO LIVE (the terminal's own
     *  wheel grammar; the whole reason history is a canvas, not a doc). */
    private fun histScroll(d: Int) {
        if (histLoading) return
        if (d < 0) {
            histOffset = (histOffset + HIST_STEP).coerceAtMost(maxOf(histMax, HIST_STEP))
        } else {
            histOffset -= HIST_STEP
            if (histOffset <= 0) {
                histRaw = null
                histOffset = 0
                level = Level_.LIVE
            }
        }
    }

    private fun paintHistory(g: Gray8, rect: Rect) {
        val raw = histRaw
        g.fillRect(rect, Level.BG)
        if (histLoading || raw == null) {
            drawFit(g, rect.x + 16, rect.y + 12, "capturing scrollback…", Level.DIM, fRow, rect.w - 32)
            return
        }
        // history IS the flow (scrollback is normal-screen text even when the
        // live pane runs a TUI): same face, size and wrap as the live view
        val hv = flow.renderHistory(g, rect, raw, histOffset)
        histOffset = hv.offset
        histMax = hv.maxOffset
    }

    // ------------------------------------------------------------------ keys
    private fun prettyKey(k: String): String = when (k) {
        "C-c" -> "Ctrl-C"; "Escape" -> "Esc"; else -> k
    }

    private fun keysRows(): List<String> =
        cfg.quickKeys.map { prettyKey(it) } + listOf("Snippets…", "Type (phone/PC keyboard)", "Windows…", "Session…")

    private fun keysCommit(i: Int) {
        val t = target ?: return
        val qk = cfg.quickKeys
        when {
            i < qk.size -> {
                val key = qk[i]
                level = Level_.LIVE       // watch it run (G2CC review 2026-07-05)
                busy("sending ${prettyKey(key)}") { provider.sendKeys(t, listOf(key)) }
            }
            i == qk.size -> { level = Level_.SNIPPETS; snipModel.cursor = 0 }
            i == qk.size + 1 -> {
                notice = "type on the phone strip, browser page or desktop preview — it stages here"
                services?.requestRender(this)
            }
            i == qk.size + 2 -> {
                busy("listing windows") {
                    val w = provider.windows(t)
                    onShell { wins = w; winModel.cursor = 0; level = Level_.WINDOWS; services?.requestRender(this) }
                }
            }
            else -> { level = Level_.SESSION_ACTIONS; actModel.cursor = 0 }
        }
    }

    private fun snipRows(): List<String> = cfg.snippets + listOf("‹ back")

    private fun snipCommit(i: Int) {
        val t = target ?: return
        if (i >= cfg.snippets.size) { level = Level_.KEYS; return }
        val s = cfg.snippets[i]
        level = Level_.LIVE
        busy("sending $s") {
            provider.sendLiteral(t, s)
            provider.sendKeys(t, listOf("Enter"))
        }
    }

    // ------------------------------------------------------------------ windows
    private fun paintWinRow(g: Gray8, i: Int, r: Rect, dim: Boolean) {
        val w = wins.getOrNull(i) ?: return
        val viewing = target?.window == w.idx || (target?.window == -1 && w.active)
        if (w.active) g.fillRect(r.x + 10, r.y + r.h / 2 - 3, 6, 6, Level.MID)
        drawFit(g, r.x + 28, r.y + 5, "${w.idx}: ${w.name}", if (dim) Level.DIM else Level.BODY, fRow, r.w - 160)
        val marks = buildList {
            if (viewing) add("viewing")
            if (w.bell) add("bell")
        }.joinToString(" · ")
        if (marks.isNotEmpty()) drawRight(g, r.right - 16, r.y + 8, marks, Level.DIM, fSmall)
    }

    private fun winCommit(i: Int) {
        val t = target ?: return
        val w = wins.getOrNull(i) ?: return
        // VIEW the window — non-invasive (TMUX.md: select-window is an explicit
        // Session… action; viewing targets `=session:idx` without selecting)
        target = t.copy(window = if (w.active) -1 else w.idx)
        frame = null
        level = Level_.LIVE
        resubscribe()
    }

    // ------------------------------------------------------------------ session actions
    private fun actionRows(): List<String> {
        val t = target ?: return listOf("‹ back")
        val mutedNow = mutedKey(t) in muted
        return buildList {
            add(if (mutedNow) "Alerts: muted — tap to unmute" else "Alerts: on — tap to mute")
            add("Fit pane to glass (${FIT_COLS}×${FIT_ROWS}) — resizes the real window")
            if (t.window >= 0) add("Select window ${t.window} in tmux — the PC view switches too")
            add("Rename (type the new name)")
            add("Kill session…")
            add("‹ back")
        }
    }

    private fun actionCommit(i: Int) {
        val t = target ?: return
        val rows = actionRows()
        val row = rows.getOrNull(i) ?: return
        if (row == "‹ back") { level = Level_.KEYS; return }
        when {
            rows[i].startsWith("Alerts:") -> {
                val k = mutedKey(t)
                if (!muted.remove(k)) muted.add(k)
                services?.requestRender(this)
            }
            rows[i].startsWith("Fit pane") -> {
                level = Level_.LIVE
                busy("fitting the pane") { provider.resizeWindow(t, FIT_COLS, FIT_ROWS) }
            }
            rows[i].startsWith("Select window") -> {
                level = Level_.LIVE
                busy("selecting the window") {
                    provider.selectWindow(t, t.window)
                    onShell { target = t.copy(window = -1); resubscribe() }
                }
            }
            rows[i].startsWith("Rename") -> {
                renameArmed = true
                notice = "type the new name on the phone strip, browser page or desktop preview"
                services?.requestRender(this)
            }
            rows[i].startsWith("Kill") -> { confirmModel.cursor = 1; level = Level_.KILL_CONFIRM }
        }
    }

    private fun killRows(): List<String> {
        val t = target
        return listOf("KILL '${t?.session}'${hostSuffix(t?.host ?: "")} — tap to confirm", "cancel")
    }

    private fun killCommit(i: Int) {
        val t = target ?: return
        if (i != 0) { level = Level_.SESSION_ACTIONS; return }
        level = Level_.SESSIONS
        target = null
        provider.subscribe(listener, null)
        busy("ending the session") { provider.killSession(t) }
    }

    // ------------------------------------------------------------------ typed text
    override fun onTypedText(line: String): Boolean {
        if (target == null || level == Level_.SESSIONS) return false
        typed = line
        typedPurpose = if (renameArmed) TypedPurpose.RENAME else TypedPurpose.SEND
        renameArmed = false
        typedReturn = if (level == Level_.TYPE_CONFIRM) typedReturn else level
        confirmModel.cursor = 0
        level = Level_.TYPE_CONFIRM
        return true   // staged behind the confirm — nothing has run
    }

    private fun typeRows(): List<String> = listOf(
        if (typedPurpose == TypedPurpose.RENAME) "Rename the session — tap to apply"
        else "Run it (sends + Enter) — tap to confirm",
        "discard",
    )

    private fun paintTypedLens(g: Gray8, r: Rect) {
        val head = if (typedPurpose == TypedPurpose.RENAME) "rename → " else "type → "
        drawFit(g, r.x + 16, r.y + 6, head + (target?.label ?: ""), Level.HEAD, fSmall, r.w - 32)
        drawFit(g, r.x + 16, r.y + 26, sanitizeKeepMono(typed ?: ""), Level.BODY, fRow, r.w - 32)
    }

    private fun typeCommit(i: Int) {
        val t = target ?: return
        val line = typed
        typed = null
        if (i != 0 || line == null) { level = typedReturn; return }
        if (typedPurpose == TypedPurpose.RENAME) {
            level = Level_.SESSION_ACTIONS
            busy("renaming") {
                provider.renameSession(t, line)
                onShell {
                    val clean = TmuxNames.clean(line) ?: return@onShell
                    target = t.copy(session = clean)
                    resubscribe()
                }
            }
        } else {
            level = Level_.LIVE
            busy("typing into ${t.label}") {
                provider.sendLiteral(t, line)
                provider.sendKeys(t, listOf("Enter"))   // always-run on confirm (G2CC, Adam 2026-06-18)
            }
        }
    }

    // ------------------------------------------------------------------ chrome
    override fun title(): String {
        // §4.1: the Title is "what is inside this window right now" — the
        // chrome already draws the window NAME, so never repeat "Tmux" here
        // (the Reader precedent: "library", not "Reader · library")
        val base = when (level) {
            Level_.SESSIONS -> "sessions"
            Level_.LIVE -> target?.label ?: "sessions"
            Level_.HISTORY -> "${target?.label} · history −${histOffset}"
            Level_.KEYS -> "${target?.label} · keys"
            Level_.SNIPPETS -> "snippets"
            Level_.WINDOWS -> "${target?.session} · windows"
            Level_.SESSION_ACTIONS -> "${target?.label} · session"
            Level_.KILL_CONFIRM -> "kill?"
            Level_.TYPE_CONFIRM -> "confirm"
        }
        return notice?.let { "$base · ! $it" } ?: base
    }

    override fun summary(): Summary {
        val waiting = sessions.filter { it.waiting }
        return Summary(
            line = when {
                sessions.isEmpty() -> if (provState.isEmpty()) "no sessions" else provState
                waiting.isEmpty() -> "${sessions.size} session${if (sessions.size == 1) "" else "s"}"
                else -> "${waiting.joinToString(", ") { it.name }} waiting"
            },
            detail = target?.let { "viewing ${it.label}" } ?: "",
            more = waiting.isNotEmpty(),
        )
    }

    override fun levelDepth(): Int = when (level) {
        Level_.SESSIONS -> 1
        Level_.LIVE -> 2
        Level_.HISTORY, Level_.KEYS -> 3
        Level_.SNIPPETS, Level_.WINDOWS, Level_.SESSION_ACTIONS -> 4
        Level_.KILL_CONFIRM, Level_.TYPE_CONFIRM -> 5
    }

    override fun back(): Boolean = when (level) {
        Level_.SESSIONS -> false
        Level_.LIVE -> {
            target = null
            frame = null
            provider.subscribe(listener, null)
            level = Level_.SESSIONS
            true
        }
        Level_.HISTORY -> { histRaw = null; histLoading = false; histOffset = 0; level = Level_.LIVE; true }
        Level_.KEYS -> { level = Level_.LIVE; true }
        Level_.SNIPPETS, Level_.WINDOWS -> { level = Level_.KEYS; true }
        Level_.SESSION_ACTIONS -> { renameArmed = false; level = Level_.KEYS; true }
        Level_.KILL_CONFIRM -> { level = Level_.SESSION_ACTIONS; true }
        Level_.TYPE_CONFIRM -> { typed = null; level = typedReturn; true }
    }

    // ------------------------------------------------------------------ settings
    private val settingsRows: List<HostSetting> by lazy {
        listOf(
            HostSetting("Update", PACES.keys.toList(),
                { PACES.entries.firstOrNull { it.value == paceMs }?.key ?: "1 s" },
                { v -> paceMs = PACES[v] ?: 1_000L; provider.setCapturePacing(paceMs) }),
            HostSetting("Context rows", listOf("on", "off"),
                { if (wantContext) "on" else "off" },
                { wantContext = it == "on"; renderer.invalidate() }),
            HostSetting("Alerts", listOf("on", "off"),
                { if (alertsOn) "on" else "off" },
                { alertsOn = it == "on"; if (!alertsOn) dirty = false }),
            HostSetting("Size", listOf("global") + wm.damage.core.shell.ShellSettings.HEIGHTS.map { "$it" },
                { heightPref?.toString() ?: "global" },
                { heightPref = it.toIntOrNull() }),
        )
    }

    override fun appSettings(): List<HostSetting> = settingsRows

    // ------------------------------------------------------------------ persistence
    override fun saveState(): JsonObject = buildJsonObject {
        put("level", if (target != null) "live" else "sessions")
        target?.let {
            put("host", it.host)
            put("session", it.session)
            put("window", it.window)
        }
        put("context", wantContext)
        put("alerts", alertsOn)
        put("paceMs", paceMs)
        heightPref?.let { put("height", it) }
        putJsonArray("muted") { muted.forEach { add(JsonPrimitive(it)) } }
    }

    override fun restoreState(state: JsonObject) {
        wantContext = (state["context"] as? JsonPrimitive)?.content != "false"
        alertsOn = (state["alerts"] as? JsonPrimitive)?.content != "false"
        paceMs = ((state["paceMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 1_000L)
            .coerceIn(250, 30_000)
        provider.setCapturePacing(paceMs)     // the host polls at the chosen cadence
        heightPref = (state["height"] as? JsonPrimitive)?.content?.toIntOrNull()
        muted.clear()
        (state["muted"] as? kotlinx.serialization.json.JsonArray)?.forEach {
            muted.add(it.jsonPrimitive.content)
        }
        val session = (state["session"] as? JsonPrimitive)?.content
        if ((state["level"] as? JsonPrimitive)?.content == "live" && session != null) {
            target = TmuxTarget(
                (state["host"] as? JsonPrimitive)?.content ?: "local",
                session,
                (state["window"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
            )
            level = Level_.LIVE   // deeper levels restore to the live grid; history re-captures
        }
    }

    // ------------------------------------------------------------------ helpers
    /** Run a provider call off-loop; failures land in the title notice ON
     *  GLASS (G2CC D3 — send failures used to be log-only). */
    private fun busy(what: String, op: () -> Unit) {
        bg.launch {
            try {
                op()
                onShell { services?.requestRender(this@TmuxWindow) }
            } catch (e: Exception) {
                Log.e("tmux", "$what failed: ${e.message}")
                onShell {
                    notice = "$what failed: ${e.message?.take(90)}"
                    services?.requestRender(this@TmuxWindow)
                }
            }
        }
    }

    private fun age(activityUnix: Long): String {
        if (activityUnix <= 0) return ""
        val s = maxOf(0, System.currentTimeMillis() / 1000 - activityUnix)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            else -> "${s / 86_400}d"
        }
    }

    /** List rows draw through the shared faces; arbitrary pane text is capped
     *  to Latin-1 there (a '·' per swapped glyph) — the GRID is where content
     *  renders true, with the per-glyph coverage fallback. */
    private fun sanitize(s: String): String = buildString(s.length) {
        for (ch in s) append(if (ch.code in 0x20..0x7E) ch else '·')
    }

    /** History lines keep what JetBrains Mono can draw (box drawing included);
     *  only genuinely uncovered glyphs become '·'. */
    private fun sanitizeKeepMono(s: String): String = buildString(s.length) {
        for (ch in s) {
            append(when {
                ch.code in 0x20..0x7E -> ch
                tx.covers(ch.toString(), fRead) -> ch
                else -> '·'
            })
        }
    }

    private fun drawFit(g: Gray8, x: Int, y: Int, s: String, lv: Int, f: FontSpec, maxW: Int) {
        var str = s
        if (tx.measure(str, f) > maxW) {
            var n = str.length
            while (n > 0 && tx.measure(str.take(n), f) > maxW) n--
            str = str.take(n)
        }
        tx.draw(g, x / 4 * 4, y / 2 * 2, str, f, lv)
    }

    private fun drawRight(g: Gray8, xRight: Int, y: Int, s: String, lv: Int, f: FontSpec) {
        tx.draw(g, (xRight - tx.measure(s, f)) / 4 * 4, y / 2 * 2, s, f, lv)
    }

    private fun paintPlainRow(g: Gray8, label: String, @Suppress("UNUSED_PARAMETER") i: Int, r: Rect, dim: Boolean) {
        drawFit(g, r.x + 16, r.y + 5, label, if (dim) Level.DIM else Level.BODY, fRow, r.w - 32)
    }

    private fun paintPlainLens(g: Gray8, r: Rect, label: String) {
        drawFit(g, r.x + 16, r.y + 14, label, Level.HEAD, fBig, r.w - 32)
    }

    companion object {
        const val HISTORY_LINES = 1000
        /** Display lines per notch in history — the Reader's 5-per-notch feel. */
        const val HIST_STEP = 5

        /** Settings → Tmux → Update: capture pacing choices (default 1 s —
         *  the flow rework's cadence; pushes still happen only on change). */
        val PACES = linkedMapOf("0.5 s" to 500L, "1 s" to 1_000L, "2 s" to 2_000L, "5 s" to 5_000L)
        /** "Fit pane to glass": 64 columns reads at ~9.5 px cells in the 608
         *  content width; 22 rows fit every height mode. Explicit action only
         *  — it resizes the REAL tmux window (TMUX.md verdict 2). */
        const val FIT_COLS = 64
        const val FIT_ROWS = 22
    }
}
