package wm.damage.core.windows.tmux

import kotlinx.serialization.Serializable

/**
 * The tmux content model (TMUX.md): what the provider layer serves the window,
 * shaped for the wire (the remote provider ships these verbatim).
 *
 * The glasses are a viewer/controller of the REAL tmux server via DISCRETE
 * commands — never a `-C` attach, never an emulator: tmux renders, we read
 * its grid (lineage: G2CC server/src/tmux.ts, the Phase-5 safety shape).
 */

/** One host the provider reaches: local ("" ssh) or over ssh (TMUX.md
 *  verdict 1: multi-host fans out from the content host — no Damage deploys
 *  elsewhere). */
@Serializable
data class TmuxHostCfg(
    val name: String,                 // display label ("beardos", "slappy")
    val ssh: String = "",             // "" = run tmux locally; else the ssh target
    val sshPort: Int = 22,            // slappy listens on 80 (global CLAUDE.md)
)

@Serializable
data class TmuxSessionInfo(
    val host: String,                 // TmuxHostCfg.name
    val name: String,
    val windows: Int,
    val attached: Boolean,
    /** #{session_activity} — unix seconds of last activity. */
    val activity: Long,
    /** The wait-pattern matched the pane's tail: this session wants input. */
    val waiting: Boolean,
    /** Last non-empty tail line, escapes stripped — the sessions-list lens. */
    val lastLine: String,
)

@Serializable
data class TmuxWinInfo(val idx: Int, val name: String, val active: Boolean, val bell: Boolean)

/** A concrete pane to view/drive: session's active window (window = -1) or a
 *  specific window's active pane. The `=name:` target form is load-bearing —
 *  exact-session match, never a same-named window (G2CC tmux.ts, verified on
 *  tmux 3.5a; the claude/claude2 lesson of 2026-06-14). */
@Serializable
data class TmuxTarget(val host: String, val session: String, val window: Int = -1) {
    /** The tmux -t argument. */
    val t: String get() = if (window < 0) "=$session:" else "=$session:$window"
    val label: String get() = if (window < 0) session else "$session:$window"
}

/** One captured pane state. [lines] keep their SGR escapes (`capture-pane -e`)
 *  — parsing happens where rendering happens. The capture asks for a few rows
 *  of history above the visible pane (`-S -ctx`); [rows] is the pane height,
 *  so `lines.size - rows` (>= 0) is how many context rows arrived — tmux
 *  clamps at what exists, and an alternate-screen pane simply has none. */
@Serializable
data class PaneFrame(
    val lines: List<String>,
    val cols: Int,
    val rows: Int,
    val cursorX: Int,
    val cursorY: Int,
    val cursorVisible: Boolean,
    val alternate: Boolean,
    val capturedAtMs: Long,
) {
    val contextRows: Int get() = maxOf(0, lines.size - rows)
}

/** Config served WITH the session list so the phone needs no config of its
 *  own: quick keys (tmux key names), snippets (sent literal + Enter), and the
 *  waiting-for-input tail patterns. Defaults tuned for the Claude approval
 *  loop; overridable in ~/.damage/config.json on the content host. */
@Serializable
data class TmuxConfig(
    val quickKeys: List<String> = DEFAULT_QUICK_KEYS,
    val snippets: List<String> = DEFAULT_SNIPPETS,
    val waitPatterns: List<String> = DEFAULT_WAIT_PATTERNS,
) {
    companion object {
        /** TMUX.md verdict 4 — approval-loop order; Left/Right added on
         *  Adam's ask (2026-08-31, menu/readline navigation). */
        val DEFAULT_QUICK_KEYS = listOf(
            "Enter", "y", "n", "1", "2", "3", "Escape", "C-c", "Up", "Down", "Left", "Right", "Tab", "q",
        )

        /** G2CC's one-tap slash commands (windows/terminal.ts), carried as the
         *  snippet defaults — sent literal + Enter. */
        val DEFAULT_SNIPPETS = listOf(
            "/clear", "/compact", "/resume", "/cost", "/model", "/status", "/config",
            "/agents", "/review", "/help", "/exit",
        )

        /** Regexes run over the pane's last non-blank tail lines (stripped).
         *  A match = "waiting for input". Tuned for Claude Code's permission
         *  prompt and menus; config replaces the whole list. */
        val DEFAULT_WAIT_PATTERNS = listOf(
            "Do you want", "\\by/n\\b", "\\[y/N\\]", "\\[Y/n\\]",
            "press Enter", "\u276F\\s*$", "\u23F5\u23F5",
        )
    }
}

/**
 * The provider seam — three implementations, the Content triple's shape
 * (§10.1: content = PC): [LocalTmuxProvider] (exec, PC/laptop-direct),
 * the host side served over the content port, and the phone's remote client.
 *
 * Blocking one-shot methods throw on failure — callers surface the message
 * (NO SILENT FAILURES); pushes arrive on provider threads, listeners hop to
 * the shell loop themselves.
 */
interface TmuxProvider : AutoCloseable {
    interface Listener {
        /** The session list + config, pushed on change and on (re)connect. */
        fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig)

        /** A new capture of the pane this listener subscribed to. */
        fun frame(target: TmuxTarget, frame: PaneFrame)

        /** A session ENTERED the waiting state (edge, not level). */
        fun alert(session: TmuxSessionInfo)

        /** Provider health for the window's staleness surface: "" = healthy. */
        fun state(state: String)
    }

    fun addListener(l: Listener)
    fun removeListener(l: Listener)

    /** Watch [target]'s pane (500 ms pacing, pushed on change); null stops
     *  this listener's watch. One target per listener. */
    fun subscribe(l: Listener, target: TmuxTarget?)

    /** Send tmux KEY NAMES to the target's pane. Keys reach ONE explicitly
     *  chosen target only (the G2CC rule, kept verbatim). */
    fun sendKeys(target: TmuxTarget, keys: List<String>)

    /** Send LITERAL text (`send-keys -l --`), no key-name interpretation. */
    fun sendLiteral(target: TmuxTarget, text: String)

    /** Scrollback: visible pane + up to [lines] of history, escapes included
     *  (callers strip for the reading view). tmux clamps to what exists. */
    fun history(target: TmuxTarget, lines: Int): List<String>

    fun windows(target: TmuxTarget): List<TmuxWinInfo>

    /** Create a detached session on [host]; returns its (auto-)name. */
    fun newSession(host: String): String

    fun killSession(target: TmuxTarget)
    fun renameSession(target: TmuxTarget, newName: String)

    /** Make [idx] the session's active window — INVASIVE (an attached PC
     *  client sees the switch), so explicit-action only (TMUX.md). */
    fun selectWindow(target: TmuxTarget, idx: Int)

    /** Resize the target's window grid — the "Fit pane to glass" action.
     *  Invasive for the same reason; explicit-action only. */
    fun resizeWindow(target: TmuxTarget, cols: Int, rows: Int)
}

/** Session-name hygiene (lineage: G2CC terminal.ts cleanSessionName): single
 *  token, letters/digits/-/_ only, at least one alphanumeric. */
object TmuxNames {
    fun clean(raw: String): String? {
        val name = raw.trim().replace(Regex("\\s+"), "-").replace(Regex("[^A-Za-z0-9_-]"), "")
        return if (name.any { it.isLetterOrDigit() }) name else null
    }
}
