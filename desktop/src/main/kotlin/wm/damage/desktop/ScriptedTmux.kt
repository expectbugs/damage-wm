package wm.damage.desktop

import wm.damage.core.windows.tmux.PaneFrame
import wm.damage.core.windows.tmux.TmuxConfig
import wm.damage.core.windows.tmux.TmuxProvider
import wm.damage.core.windows.tmux.TmuxSessionInfo
import wm.damage.core.windows.tmux.TmuxTarget
import wm.damage.core.windows.tmux.TmuxWinInfo

/**
 * A deterministic tmux provider for --selfcheck and --snapshot: canned
 * sessions, a rich SGR frame (colours, bold, reverse, a rule, a prompt,
 * context rows), recorded sends. No exec, no radio, no real tmux —
 * the -L-socket rule's stronger cousin.
 */
class ScriptedTmux : TmuxProvider {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<TmuxProvider.Listener>()
    val sent = java.util.concurrent.CopyOnWriteArrayList<String>()

    val sessions = listOf(
        TmuxSessionInfo("beardos", "claude", 2, attached = true,
            activity = System.currentTimeMillis() / 1000 - 90, waiting = false,
            lastLine = "\$ the compositor batches all damage"),
        TmuxSessionInfo("beardos", "build", 1, attached = false,
            activity = System.currentTimeMillis() / 1000 - 3600, waiting = false,
            lastLine = "BUILD SUCCESSFUL in 28s"),
    )

    private val e = "\u001B"

    /** 6 context rows + a 22-row pane: bold header, diff colours, reverse
     *  status bar, a box rule, a ❯ prompt with the cursor on it. */
    fun frame(): PaneFrame {
        val lines = ArrayList<String>()
        repeat(6) { lines.add("ctx $it: earlier output scrolls dimly above the pane") }
        lines.add("$e[1mDamage build — :core:test$e[0m")
        lines.add("")
        lines.add("$e[32m+ 140 tests completed$e[0m")
        lines.add("$e[31m- 0 failed$e[0m")
        lines.add("$e[7m main  damage-wm  28s $e[0m")
        lines.add("─".repeat(64))
        for (i in 1..13) lines.add("row $i · mode-8 batches ride one flush per frame")
        lines.add("")
        lines.add("$e[38;5;245m❯$e[0m ")
        return PaneFrame(lines, 80, 22, cursorX = 2, cursorY = 21,
            cursorVisible = true, alternate = false, capturedAtMs = 0)
    }

    override fun addListener(l: TmuxProvider.Listener) {
        listeners.add(l)
        l.status(sessions, TmuxConfig())
        l.state("")
    }

    override fun removeListener(l: TmuxProvider.Listener) {
        listeners.remove(l)
    }

    override fun subscribe(l: TmuxProvider.Listener, target: TmuxTarget?) {
        sent.add("sub:${target?.label}")
        if (target != null) l.frame(target, frame())
    }

    override fun sendKeys(target: TmuxTarget, keys: List<String>) {
        sent.add("keys:${target.label}:${keys.joinToString("+")}")
    }

    override fun sendLiteral(target: TmuxTarget, text: String) {
        sent.add("lit:${target.label}:$text")
    }

    override fun history(target: TmuxTarget, lines: Int): List<String> =
        (1..120).map { "history $it · long content stays long, it wraps and never disappears" }

    override fun windows(target: TmuxTarget): List<TmuxWinInfo> =
        listOf(TmuxWinInfo(0, "zsh", true, false), TmuxWinInfo(1, "claude", false, false))

    override fun newSession(host: String): String {
        sent.add("new:$host")
        return "g2-1"
    }

    override fun setCapturePacing(ms: Long) { sent.add("pace:$ms") }
    override fun killSession(target: TmuxTarget) { sent.add("kill:${target.label}") }
    override fun renameSession(target: TmuxTarget, newName: String) { sent.add("ren:$newName") }
    override fun selectWindow(target: TmuxTarget, idx: Int) { sent.add("sel:$idx") }
    override fun resizeWindow(target: TmuxTarget, cols: Int, rows: Int) { sent.add("fit:${cols}x$rows") }
    override fun close() {}

    fun pushAlert() {
        val s = sessions[0].copy(waiting = true, lastLine = "Do you want to make this edit?")
        for (l in listeners) l.alert(s)
    }
}
