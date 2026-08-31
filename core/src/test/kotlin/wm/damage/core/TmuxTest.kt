package wm.damage.core

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.WindowView
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.windows.tmux.LocalTmuxProvider
import wm.damage.core.windows.tmux.PaneFrame
import wm.damage.core.windows.tmux.RemoteTmuxProvider
import wm.damage.core.windows.tmux.Sgr
import wm.damage.core.windows.tmux.TermRender
import wm.damage.core.windows.tmux.TmuxConfig
import wm.damage.core.windows.tmux.TmuxHostCfg
import wm.damage.core.windows.tmux.TmuxProvider
import wm.damage.core.windows.tmux.TmuxSessionInfo
import wm.damage.core.windows.tmux.TmuxTarget
import wm.damage.core.windows.tmux.TmuxWinInfo
import wm.damage.core.windows.tmux.TmuxWindow

private const val E = "\u001B"

/** Size-aware fake mono rasterizer — the fit math needs measure/metrics that
 *  actually scale with sizePx (FakeText's fixed 8/16 would bypass it). */
private class MonoFake : TextRasterizer {
    fun advance(f: FontSpec) = maxOf(1, f.sizePx * 6 / 10)
    override fun measure(text: String, font: FontSpec): Int = text.length * advance(font)
    override fun metrics(font: FontSpec) = FontMetrics(
        font.sizePx, maxOf(1, font.sizePx * 3 / 10), maxOf(2, font.sizePx * 13 / 10))
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        val a = advance(font)
        for ((i, ch) in text.withIndex()) {
            if (ch != ' ') surface.fillRect(x + i * a, y + 1, maxOf(1, a - 1), maxOf(1, font.sizePx - 2), level)
        }
    }
    override fun covers(text: String, font: FontSpec) = text.none { it.code > 0x2FFF }
}

class SgrTest {

    @Test
    fun coloursAttributesAndDefaultsParsePerCell() {
        val p = Sgr.parse(listOf("${E}[31mred${E}[0m ok ${E}[1mB${E}[7mR"), 12)
        val r = p.rows[0]
        assertEquals('r'.code, r.cp[0])
        assertEquals(4, r.fg[0].toInt(), "red -> luminance 61 -> level 4 (floored at 3)")
        assertEquals('o'.code, r.cp[4])
        assertEquals(Sgr.FG_DEFAULT, r.fg[4].toInt(), "reset restores the reading level")
        val bIx = 7
        assertEquals('B'.code, r.cp[bIx])
        assertTrue(r.flags[bIx].toInt() and Sgr.BOLD != 0)
        assertTrue(r.flags[bIx + 1].toInt() and Sgr.REVERSE != 0, "7 stacks onto bold")
        assertEquals(0, p.skippedEscapes)
    }

    @Test
    fun extendedColoursMapByLuminance() {
        val p = Sgr.parse(listOf("${E}[38;5;255mX${E}[38;2;255;255;255mY${E}[38;5;16mZ"), 8)
        val r = p.rows[0]
        assertEquals(14, r.fg[0].toInt(), "gray-ramp 255 = 238 -> 14")
        assertEquals(15, r.fg[1].toInt(), "truecolour white -> 15")
        assertEquals(3, r.fg[2].toInt(), "cube black floors at 3: readable beats faithful")
    }

    @Test
    fun wideGlyphsOwnTwoColumnsAndOscIsSkippedLoudly() {
        val p = Sgr.parse(listOf("${E}]0;title\u0007A汉B"), 8)
        val r = p.rows[0]
        assertEquals(1, p.skippedEscapes, "the OSC was counted, not silently eaten")
        assertEquals('A'.code, r.cp[0])
        assertEquals(0x6C49, r.cp[1])
        assertEquals(Sgr.CONT, r.cp[2], "the wide glyph marks its second column")
        assertEquals('B'.code, r.cp[3])
    }

    @Test
    fun stripRemovesEveryEscapeAndKeepsText() {
        assertEquals("red ok", Sgr.strip("${E}[31mred${E}[0m ok"))
        assertEquals("plain", Sgr.strip("plain"))
    }
}

class TermRenderTest {

    private fun frame(rows: Int = 22, cols: Int = 80, ctx: Int = 0, cursorY: Int = 2): PaneFrame {
        val lines = ArrayList<String>()
        repeat(ctx) { lines.add("ctx line $it") }
        repeat(rows) { lines.add("row $it content") }
        return PaneFrame(lines, cols, rows, cursorX = 3, cursorY = cursorY,
            cursorVisible = true, alternate = false, capturedAtMs = 0)
    }

    @Test
    fun fitScalesToTheRectAtBothHeightModes() {
        val t = TermRender(MonoFake())
        val tall = t.fitFor(Rect(16, 34, 608, 416), 80, 22, 0)
        assertTrue(tall.cellW * 80 <= 608, "80 columns fit the width at 480 mode")
        assertTrue(tall.cellH * 22 <= 416, "22 rows fit the height")
        t.invalidate()
        val short = t.fitFor(Rect(16, 34, 608, 224), 80, 22, 0)
        assertTrue(short.cellH * 22 <= 224, "the 288 height mode still fits the whole pane")
        assertTrue(short.sizePx <= tall.sizePx, "less height, smaller type")
    }

    @Test
    fun contextRowsAppearOnlyInSpareHeight() {
        val t = TermRender(MonoFake())
        val tall = t.fitFor(Rect(16, 34, 608, 416), 80, 22, 12)
        assertTrue(tall.contextShown > 0, "480 mode has spare height for context")
        t.invalidate()
        val tiny = t.fitFor(Rect(16, 34, 608, 224), 80, 22, 12)
        // whatever fits is honest; it must never shrink the live pane
        assertTrue(tiny.cellH * 22 <= 224)
    }

    @Test
    fun renderInksTheGridAndInvertsTheCursorCell() {
        val t = TermRender(MonoFake())
        val g = Gray8(640, 480)
        val rect = Rect(16, 34, 608, 416)
        val spec = t.render(g, rect, frame(ctx = 6), wantContext = true)
        var ink = 0
        for (y in rect.y until rect.bottom) for (x in rect.x until rect.right) {
            if (g[x, y].toInt() != 0) ink++
        }
        assertTrue(ink > 500, "the grid rendered ($ink lit px)")
        // the cursor cell: a solid BODY-level fill at (3, cursorY=2) of the live pane
        val ctxPx = spec.contextShown * spec.cellH + if (spec.contextShown > 0) 2 else 0
        val cx = spec.x0 + 3 * spec.cellW
        val cy = spec.y0 + ctxPx + 2 * spec.cellH
        assertEquals(wm.damage.core.gfx.Level.BODY, g[cx, cy].toInt() and 0xFF, "cursor cell inverted")
    }

    @Test
    fun aTrimmedShortCaptureTopAligns() {
        val t = TermRender(MonoFake())
        val g = Gray8(640, 480)
        val rect = Rect(16, 34, 608, 416)
        // 22-row pane whose capture came back with 3 lines (blank tail trimmed)
        val f = PaneFrame(listOf("a", "b", "c"), 80, 22, 0, 0, false, false, 0)
        val spec = t.render(g, rect, f, wantContext = true)
        var topInk = 0
        for (y in spec.y0 until spec.y0 + spec.cellH) for (x in rect.x until rect.right) {
            if (g[x, y].toInt() != 0) topInk++
        }
        assertTrue(topInk > 0, "line 0 renders at pane row 0, not shifted by phantom blanks")
    }
}

// ------------------------------------------------------------------ provider
private class FakeExec : LocalTmuxProvider.TmuxExec {
    @Volatile var tail = listOf("\$ build ok", "\$ ")
    @Volatile var capture = "80\t22\t2\t1\t1\t0\nhello world\nline two"
    @Volatile var failKill = false
    val scripts = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun run(host: TmuxHostCfg, script: String): String {
        scripts.add(script)
        return when {
            script.contains("list-sessions") && script.contains(LocalTmuxProvider.TAIL_MARK) ->
                "claude\t2\t1\t1700000000\nspare\t1\t0\t1600000000\n" +
                    "${LocalTmuxProvider.TAIL_MARK}claude\n" + tail.joinToString("\n") + "\n" +
                    "${LocalTmuxProvider.TAIL_MARK}spare\nidle\n"
            script.contains("display-message") -> capture
            script.contains("capture-pane") && script.contains("-S -1000") -> "old stuff\nmore history"
            script.contains("list-windows") -> "0\tzsh\t1\t0\n1\tclaude\t0\t1"
            script.contains("kill-session") ->
                if (failKill) throw IllegalStateException("can't find session") else ""
            else -> ""
        }
    }
}

class TmuxProviderTest {

    private fun await(deadlineMs: Long = 5_000, cond: () -> Boolean) = runBlocking {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < deadlineMs) delay(10)
        assertTrue(cond(), "condition within ${deadlineMs}ms")
    }

    @Test
    fun statusParsesSessionsTailsAndWaitingAndAlertsFireOnTheEdgeOnly(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val exec = FakeExec()
        val p = LocalTmuxProvider(listOf(TmuxHostCfg("local")), TmuxConfig(), scope, exec,
            statusPacingMs = 40, capturePacingMs = 40)
        val statuses = java.util.concurrent.CopyOnWriteArrayList<List<TmuxSessionInfo>>()
        val alerts = java.util.concurrent.CopyOnWriteArrayList<TmuxSessionInfo>()
        p.addListener(object : TmuxProvider.Listener {
            override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) { statuses.add(sessions) }
            override fun frame(target: TmuxTarget, frame: PaneFrame) {}
            override fun alert(session: TmuxSessionInfo) { alerts.add(session) }
            override fun state(state: String) {}
        })
        try {
            await { statuses.any { it.size == 2 } }
            val s = statuses.last { it.size == 2 }
            assertEquals(listOf("claude", "spare"), s.map { it.name }, "activity-recent first")
            assertTrue(s[0].attached)
            assertFalse(s[0].waiting)
            assertEquals("$", s[0].lastLine, "the lens shows the last non-blank tail line, trimmed")
            assertTrue(alerts.isEmpty())
            // Claude asks for permission -> the WAITING edge fires exactly once
            exec.tail = listOf("Do you want to make this edit?", "❯ 1. Yes")
            await { alerts.size == 1 }
            delay(200)
            assertEquals(1, alerts.size, "level held, no repeat alert")
            assertEquals("claude", alerts[0].name)
            await { statuses.last().first { it.name == "claude" }.waiting }
        } finally {
            p.close()
            scope.cancel()
        }
    }

    @Test
    fun subscriptionPushesFramesOnChangeOnly(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val exec = FakeExec()
        val p = LocalTmuxProvider(listOf(TmuxHostCfg("local")), TmuxConfig(), scope, exec,
            statusPacingMs = 5_000, capturePacingMs = 30)
        val frames = java.util.concurrent.CopyOnWriteArrayList<PaneFrame>()
        val l = object : TmuxProvider.Listener {
            override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) {}
            override fun frame(target: TmuxTarget, frame: PaneFrame) { frames.add(frame) }
            override fun alert(session: TmuxSessionInfo) {}
            override fun state(state: String) {}
        }
        p.addListener(l)
        p.subscribe(l, TmuxTarget("local", "claude"))
        try {
            await { frames.size == 1 }
            val f = frames[0]
            assertEquals(80, f.cols)
            assertEquals(22, f.rows)
            assertEquals(2, f.cursorX)
            assertTrue(f.cursorVisible)
            assertEquals(listOf("hello world", "line two"), f.lines)
            delay(150)
            assertEquals(1, frames.size, "an unchanged pane pushes nothing")
            exec.capture = "80\t22\t2\t1\t1\t0\nhello world\nline THREE"
            await { frames.size == 2 }
        } finally {
            p.close()
            scope.cancel()
        }
    }

    @Test
    fun literalSendsAreQuotedForTheShell(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val exec = FakeExec()
        val p = LocalTmuxProvider(listOf(TmuxHostCfg("local")), TmuxConfig(), scope, exec,
            statusPacingMs = 60_000, capturePacingMs = 60_000)
        try {
            p.sendLiteral(TmuxTarget("local", "claude"), "echo 'hi'; rm -i x")
            val sent = exec.scripts.last()
            assertTrue("send-keys" in sent && "-l --" in sent, sent)
            assertTrue("""'echo '\''hi'\''; rm -i x'""" in sent, "single-quote escaping: $sent")
            assertTrue("'=claude:'" in sent, "the exact-session target form")
        } finally {
            p.close()
            scope.cancel()
        }
    }
}

// ------------------------------------------------------------------ the wire
class TmuxNetTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun remoteProviderGetsStatusFramesAndRoundTripsRequests(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val exec = FakeExec()
        val local = LocalTmuxProvider(listOf(TmuxHostCfg("local")), TmuxConfig(), scope, exec,
            statusPacingMs = 40, capturePacingMs = 30)
        val host = ContentHostServer(LocalContent(java.nio.file.Path.of("/nonexistent-books")),
            port, "tok", tmux = local)
        host.start()
        val remote = RemoteTmuxProvider("127.0.0.1", port, "tok", scope, retryPacingMs = 100)
        val statuses = java.util.concurrent.CopyOnWriteArrayList<List<TmuxSessionInfo>>()
        val frames = java.util.concurrent.CopyOnWriteArrayList<PaneFrame>()
        val l = object : TmuxProvider.Listener {
            override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) { statuses.add(sessions) }
            override fun frame(target: TmuxTarget, frame: PaneFrame) { frames.add(frame) }
            override fun alert(session: TmuxSessionInfo) {}
            override fun state(state: String) {}
        }
        remote.addListener(l)
        try {
            val t0 = System.currentTimeMillis()
            while (statuses.none { it.size == 2 } && System.currentTimeMillis() - t0 < 5_000) delay(10)
            assertTrue(statuses.any { it.size == 2 }, "the session list crossed the wire")
            remote.subscribe(l, TmuxTarget("local", "claude"))
            while (frames.isEmpty() && System.currentTimeMillis() - t0 < 5_000) delay(10)
            assertTrue(frames.isNotEmpty(), "a frame crossed the wire")
            assertEquals(listOf("hello world", "line two"), frames[0].lines)
            val wins = remote.windows(TmuxTarget("local", "claude"))
            assertEquals(listOf(0, 1), wins.map { it.idx })
            assertTrue(wins[1].bell)
            // a refusal crosses as a THROWN reason, not silence
            exec.failKill = true
            val err = runCatching { remote.killSession(TmuxTarget("local", "claude")) }.exceptionOrNull()
            assertTrue(err != null && "can't find session" in "${err.message}", "$err")
        } finally {
            remote.close()
            host.close()
            local.close()
            scope.cancel()
        }
    }
}

// ------------------------------------------------------------------ window
private class FakeProvider : TmuxProvider {
    val listeners = java.util.concurrent.CopyOnWriteArrayList<TmuxProvider.Listener>()
    val subscribed = java.util.concurrent.CopyOnWriteArrayList<TmuxTarget?>()
    val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
    var histAnswer = (1..40).map { "history line $it" }

    override fun addListener(l: TmuxProvider.Listener) { listeners.add(l) }
    override fun removeListener(l: TmuxProvider.Listener) { listeners.remove(l) }
    override fun subscribe(l: TmuxProvider.Listener, target: TmuxTarget?) { subscribed.add(target) }
    override fun sendKeys(target: TmuxTarget, keys: List<String>) { sent.add("keys:${target.label}:${keys.joinToString("+")}") }
    override fun sendLiteral(target: TmuxTarget, text: String) { sent.add("lit:${target.label}:$text") }
    override fun history(target: TmuxTarget, lines: Int): List<String> = histAnswer
    override fun windows(target: TmuxTarget): List<TmuxWinInfo> =
        listOf(TmuxWinInfo(0, "zsh", true, false), TmuxWinInfo(1, "claude", false, true))
    override fun newSession(host: String): String { sent.add("new:$host"); return "g2-1" }
    override fun killSession(target: TmuxTarget) { sent.add("kill:${target.label}") }
    override fun renameSession(target: TmuxTarget, newName: String) { sent.add("ren:${target.label}:$newName") }
    override fun selectWindow(target: TmuxTarget, idx: Int) { sent.add("sel:${target.label}:$idx") }
    override fun resizeWindow(target: TmuxTarget, cols: Int, rows: Int) { sent.add("fit:${target.label}:${cols}x$rows") }
    override fun close() {}

    fun pushStatus(vararg s: TmuxSessionInfo) {
        for (l in listeners) l.status(s.toList(), TmuxConfig())
    }
    fun pushFrame(t: TmuxTarget, f: PaneFrame) {
        for (l in listeners) l.frame(t, f)
    }
    fun pushAlert(s: TmuxSessionInfo) {
        for (l in listeners) l.alert(s)
    }
}

private class FakeServices : ShellServices {
    var renders = 0
    val notices = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun requestRender(window: DamageWindow) { renders++ }
    override fun setOperation(op: String) {}
    override fun notifyInternal(source: String, body: String, urgent: Boolean) { notices.add("$source: $body") }
    override fun runOnShell(action: () -> Unit) = action()
    override fun docContentWidth(): Int = 560
}

class TmuxWindowTest {

    private fun session(name: String, waiting: Boolean = false) =
        TmuxSessionInfo("local", name, 1, attached = false, activity = 1, waiting = waiting,
            lastLine = "tail of $name")

    private fun frame() = PaneFrame(List(22) { "row $it" }, 80, 22, 0, 0, true, false, 0)

    private fun await(deadlineMs: Long = 5_000, cond: () -> Boolean) = runBlocking {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < deadlineMs) delay(10)
        assertTrue(cond(), "condition within ${deadlineMs}ms")
    }

    private fun build(): Triple<TmuxWindow, FakeProvider, FakeServices> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val p = FakeProvider()
        val w = TmuxWindow(MonoFake(), p, scope)
        val svc = FakeServices()
        w.onRegistered(svc)
        return Triple(w, p, svc)
    }

    @Test
    fun theGrammarWalksSessionsLiveHistoryKeysAndBack() {
        val (w, p, _) = build()
        p.pushStatus(session("claude"), session("spare"))
        val sessions = w.view() as WindowView.ListView
        assertEquals(3, sessions.rowCount(), "2 sessions + 1 new-session row")
        sessions.onCommit(0)
        assertEquals("Tmux · claude", w.title())
        assertEquals(listOf<TmuxTarget?>(TmuxTarget("local", "claude")), p.subscribed.toList())
        p.pushFrame(TmuxTarget("local", "claude"), frame())
        val live = w.view() as WindowView.CanvasView
        val g = Gray8(640, 480)
        live.paint(g, Rect(16, 34, 608, 416))
        var ink = 0
        for (y in 34 until 450) for (x in 16 until 624) if (g[x, y].toInt() != 0) ink++
        assertTrue(ink > 200, "the live grid painted")
        // scroll-up = time: the frozen history arrives and opens at the live edge
        live.onScroll!!(-1)
        await { (w.view() as? WindowView.DocView)?.lineCount?.invoke()?.let { it > 1 } == true }
        val doc = w.view() as WindowView.DocView
        assertTrue(doc.lineCount() >= 40, "the scrollback wrapped in")
        assertTrue(w.title().contains("history"))
        assertTrue(w.back(), "back leaves history")
        // tap descends to keys; a quick key sends and drops back to LIVE
        (w.view() as WindowView.CanvasView).onTap!!()
        val keys = w.view() as WindowView.ListView
        assertEquals(TmuxConfig.DEFAULT_QUICK_KEYS.size + 4, keys.rowCount())
        keys.onCommit(1)   // "y"
        await { p.sent.any { it == "keys:claude:y" } }
        assertEquals("Tmux · claude", w.title(), "sending returns to the live view")
        assertTrue(w.back(), "live -> sessions")
        assertEquals("Tmux", w.title())
        assertEquals(null, p.subscribed.last(), "leaving live unsubscribes")
        assertFalse(w.back(), "sessions is the root")
    }

    @Test
    fun typedTextStagesAConfirmAndRunsLiteralPlusEnter() {
        val (w, p, _) = build()
        p.pushStatus(session("claude"))
        assertFalse(w.onTypedText("ls"), "no session open: refused, the shell narrates")
        (w.view() as WindowView.ListView).onCommit(0)
        assertTrue(w.onTypedText("echo hi"), "staged behind the confirm")
        assertTrue(w.title().contains("confirm"))
        assertTrue(p.sent.none { it.startsWith("lit:") }, "NOTHING ran before the confirm")
        (w.view() as WindowView.ListView).onCommit(0)
        await { p.sent.contains("lit:claude:echo hi") && p.sent.contains("keys:claude:Enter") }
        assertEquals("Tmux · claude", w.title(), "watching it run")
        // a discarded line runs nothing
        assertTrue(w.onTypedText("rm -rf /"))
        (w.view() as WindowView.ListView).onCommit(1)
        assertTrue(p.sent.none { "rm -rf" in it }, "discard means discard")
    }

    @Test
    fun sessionManagementWindowsKillAndMute() {
        val (w, p, svc) = build()
        p.pushStatus(session("claude"))
        (w.view() as WindowView.ListView).onCommit(0)
        (w.view() as WindowView.CanvasView).onTap!!()
        val keys = w.view() as WindowView.ListView
        keys.onCommit(TmuxConfig.DEFAULT_QUICK_KEYS.size + 2)   // Windows…
        await { w.title().contains("windows") }
        val wins = w.view() as WindowView.ListView
        assertEquals(2, wins.rowCount())
        wins.onCommit(1)   // view window 1 non-invasively
        assertEquals("Tmux · claude:1", w.title())
        assertTrue(p.subscribed.last() == TmuxTarget("local", "claude", 1))
        assertTrue(p.sent.none { it.startsWith("sel:") }, "viewing never select-windows")
        // session actions: mute, then kill with confirm
        (w.view() as WindowView.CanvasView).onTap!!()
        (w.view() as WindowView.ListView).onCommit(TmuxConfig.DEFAULT_QUICK_KEYS.size + 3)   // Session…
        val act = w.view() as WindowView.ListView
        act.onCommit(0)    // Alerts: mute
        p.pushAlert(session("claude", waiting = true))
        assertTrue(svc.notices.none { "wants input" in it }, "muted session raises no notice")
        val rows = (w.view() as WindowView.ListView)
        val killIx = 4     // Alerts, Fit, Select (window 1 viewed), Rename, KILL
        rows.onCommit(killIx)
        assertTrue(w.title().contains("kill"), w.title())
        (w.view() as WindowView.ListView).onCommit(0)
        await { p.sent.any { it.startsWith("kill:claude") } }
        assertEquals("Tmux", w.title(), "back at sessions after the kill")
    }

    @Test
    fun alertsNotifyUnmutedSessionsAndPersistenceRoundTrips() {
        val (w, p, svc) = build()
        p.pushStatus(session("claude"))
        p.pushAlert(session("claude", waiting = true))
        assertEquals(1, svc.notices.count { "wants input" in it })
        assertTrue(w.dirty)
        // persistence: target + settings survive; deep levels restore to LIVE
        (w.view() as WindowView.ListView).onCommit(0)
        (w.view() as WindowView.CanvasView).onTap!!()
        val blob = w.saveState()
        val (w2, p2, _) = build()
        w2.restoreState(blob)
        p2.pushStatus(session("claude"))
        assertEquals("Tmux · claude", w2.title(), "restores to the live grid")
        assertEquals(480, w2.preferredHeight, "the design-point height default")
    }
}
