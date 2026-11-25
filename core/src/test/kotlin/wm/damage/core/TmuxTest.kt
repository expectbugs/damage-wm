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
import wm.damage.core.windows.tmux.FlowRender
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
 *  actually scale with sizePx (FakeText's fixed 8/16 would sidestep it). */
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
        assertEquals(608, tall.cellX(80) - tall.cellX(0), "80 columns SPAN the full width (fractional pitch)")
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
        val cx = spec.cellX(3)
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

// ------------------------------------------------------------------ flow
/** MonoFake plus a record of every draw — wrap/coverage assertions read it. */
private class RecFake : TextRasterizer {
    val drawn = java.util.concurrent.CopyOnWriteArrayList<Pair<String, FontSpec>>()
    fun advance(f: FontSpec) = maxOf(1, f.sizePx * 6 / 10)
    override fun measure(text: String, font: FontSpec): Int = text.length * advance(font)
    override fun metrics(font: FontSpec) = FontMetrics(
        font.sizePx, maxOf(1, font.sizePx * 3 / 10), maxOf(2, font.sizePx * 13 / 10))
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        drawn.add(text to font)
        val a = advance(font)
        for ((i, ch) in text.withIndex()) {
            if (ch != ' ') surface.fillRect(x + i * a, y + 1, maxOf(1, a - 1), maxOf(1, font.sizePx - 2), level)
        }
    }
    override fun covers(text: String, font: FontSpec) = true
}

class FlowRenderTest {

    private fun frame(lines: List<String>) =
        PaneFrame(lines, 80, 22, cursorX = 0, cursorY = 0,
            cursorVisible = false, alternate = false, capturedAtMs = 0)

    @Test
    fun parseRunsSplitsOnStyleAndCountsSkips() {
        var skipped = 0
        val runs = Sgr.parseRuns("${E}]0;title\u0007plain ${E}[1mbold${E}[0m tail") { skipped++ }
        assertEquals(1, skipped, "the OSC was counted, not silently eaten")
        assertEquals(listOf("plain ", "bold", " tail"), runs.map { it.text })
        assertTrue(runs[1].flags and Sgr.BOLD != 0)
        assertEquals(0, runs[0].flags)
        assertEquals(Sgr.FG_DEFAULT, runs[2].fg, "reset restores the reading level")
    }

    @Test
    fun wrapKeepsEveryCharacterAndPreservesIndentation() {
        val rec = RecFake()
        val fr = FlowRender(rec)
        val long = "    indented " + "word ".repeat(40).trim()
        fr.renderTail(Gray8(640, 480), Rect(16, 34, 320, 416), frame(listOf(long)))
        val texts = rec.drawn.map { it.first }
        assertTrue(texts.size > 1, "the long line wrapped into ${texts.size} pieces")
        assertEquals(long.replace(" ", ""), texts.joinToString("").replace(" ", ""),
            "NO TRUNCATION: every character survived the wrap")
        assertTrue(texts.first().startsWith("    indented"),
            "terminal indentation is preserved verbatim: '${texts.first()}'")
    }

    @Test
    fun ruleLinesCollapseToDrawnRules() {
        val rec = RecFake()
        val fr = FlowRender(rec)
        val g = Gray8(640, 480)
        fr.renderTail(g, Rect(16, 34, 608, 416), frame(listOf("above", "─".repeat(40), "below")))
        assertTrue(rec.drawn.none { "─" in it.first }, "no glyphs drawn for the rule line")
        // the rule slot carries a DIM fill spanning the flow width
        val dim = wm.damage.core.gfx.Level.DIM
        var ruleRow = -1
        for (y in 34 until 450) if (g[100, y].toInt() and 0xFF == dim && g[500, y].toInt() and 0xFF == dim) { ruleRow = y; break }
        assertTrue(ruleRow > 0, "a drawn rule spans the width where the ─ line was")
    }

    @Test
    fun fontSizeActuallyChangesTheFlowOutput() {
        // THE regression that started the rework: on the grid, a Font-size
        // change compensated to zero. In flow it must change the pixels.
        val fake = MonoFake()
        fun render(scale: Double): Gray8 {
            val fr = FlowRender(wm.damage.core.text.StyledText(fake) {
                wm.damage.core.text.StyleTransform(scale = scale).apply(it)
            })
            val g = Gray8(640, 480)
            fr.renderTail(g, Rect(16, 34, 608, 416), frame(List(10) { "row $it text" }))
            return g
        }
        val a = render(1.0)
        val b = render(1.15)
        assertTrue(a.pix.indices.any { a.pix[it] != b.pix[it] },
            "Font size CHANGES the terminal text (the grid could not)")
    }

    @Test
    fun tailShowsTheNewestLinesBottomMost() {
        val rec = RecFake()
        val fr = FlowRender(rec)
        // far more lines than fit: the TAIL must be what renders
        fr.renderTail(Gray8(640, 480), Rect(16, 34, 608, 416), frame(List(200) { "line $it" }))
        val texts = rec.drawn.map { it.first }
        assertTrue(texts.contains("line 199"), "the newest line is shown")
        assertFalse(texts.contains("line 0"), "the oldest overflowed off the top")
    }

    @Test
    fun historyOffsetsClampAndTheRailAppears() {
        val rec = RecFake()
        val fr = FlowRender(rec)
        val g = Gray8(640, 480)
        val lines = List(120) { "history $it" }
        val hv = fr.renderHistory(g, Rect(16, 34, 608, 416), lines, offset = 10_000)
        assertTrue(hv.offset == hv.maxOffset && hv.maxOffset > 0, "offset clamps to the oldest view")
        assertTrue(rec.drawn.any { it.first.startsWith("history 0") }, "the oldest lines show at max offset")
        var rail = 0
        for (y in 34 until 450) if (g[16 + 608 - 6, y].toInt() != 0) rail++
        assertTrue(rail > 0, "the position rail is drawn")
    }
}

// ------------------------------------------------------------------ provider
private class FakeExec : LocalTmuxProvider.TmuxExec {
    @Volatile var tail = listOf("\$ build ok", "\$ ")
    @Volatile var capture = "80\t22\t2\t1\t1\t0\nhello world\nline two"
    @Volatile var failKill = false
    /** S1-3 fix C3: while set, a capture waits for it (an exec in flight). */
    @Volatile var captureGate: java.util.concurrent.CountDownLatch? = null
    val captureStarted = java.util.concurrent.atomic.AtomicInteger()
    val scripts = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun run(host: TmuxHostCfg, script: String): String {
        scripts.add(script)
        if (script.contains("display-message") && script.contains("alternate_on")) { captureStarted.incrementAndGet(); captureGate?.await() }
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
        waitUntil(deadlineMs, 10, { "condition within ${deadlineMs}ms" }) { cond() }
    }

    @Test
    fun statusParsesSessionsTailsAndWaitingAndAlertsFireOnTheEdgeOnly(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val exec = FakeExec()
        val p = LocalTmuxProvider(listOf(TmuxHostCfg("local")), TmuxConfig(), scope, exec,
            statusPacingMs = 40, capturePacingMs = 40)
        val statuses = java.util.concurrent.CopyOnWriteArrayList<List<TmuxSessionInfo>>()
        val alerts = java.util.concurrent.CopyOnWriteArrayList<TmuxSessionInfo>()
        val heldAtAlert = java.util.concurrent.CopyOnWriteArrayList<TmuxSessionInfo?>()
        p.addListener(object : TmuxProvider.Listener {
            override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) { statuses.add(sessions) }
            override fun frame(target: TmuxTarget, frame: PaneFrame) {}
            override fun alert(session: TmuxSessionInfo) {
                alerts.add(session)
                heldAtAlert.add(statuses.lastOrNull()?.firstOrNull { it.name == session.name })
            }
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
            // S1-3 fix A5: the status that carries the waiting session (and its line) goes out before the alert, so a
            // presenter holds the line the alert's notice no longer carries
            assertEquals(true, heldAtAlert[0]?.waiting, "a status holding claude waiting preceded its alert: ${heldAtAlert[0]}")
            assertEquals(alerts[0].lastLine, heldAtAlert[0]?.lastLine)
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

    /** S1-3 fix C3: a viewer that subscribes while a capture of its pane is running gets that capture — before, the
     *  capture went only to the subscribers of its tick's start, and an idle pane never pushed again. */
    @Test
    fun aViewerJoiningDuringACaptureGetsItsFirstPicture(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val exec = FakeExec()
        val p = LocalTmuxProvider(listOf(TmuxHostCfg("local")), TmuxConfig(), scope, exec,
            statusPacingMs = 60_000, capturePacingMs = 250)
        fun viewer(into: MutableList<PaneFrame>) = object : TmuxProvider.Listener {
            override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) {}
            override fun frame(target: TmuxTarget, frame: PaneFrame) { into.add(frame) }
            override fun alert(session: TmuxSessionInfo) {}
            override fun state(state: String) {}
        }
        val a = java.util.concurrent.CopyOnWriteArrayList<PaneFrame>()
        val b = java.util.concurrent.CopyOnWriteArrayList<PaneFrame>()
        val t = TmuxTarget("local", "claude")
        try {
            val first = viewer(a)
            p.subscribe(first, t)
            await { a.size == 1 }
            val gate = java.util.concurrent.CountDownLatch(1).also { exec.captureGate = it }
            val started = exec.captureStarted.get()
            await { exec.captureStarted.get() > started }                  // a capture of the idle pane in flight
            p.subscribe(viewer(b), t)                                     // a second viewer joins during it
            exec.captureGate = null
            gate.countDown()
            await { b.size == 1 }
            delay(600)                                                    // two more ticks of an unchanged pane
            assertEquals(1, b.size, "its first picture, once")
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
            val sent = exec.scripts.last { "send-keys" in it }   // the first status tick may follow it
            assertTrue("send-keys" in sent && "-l --" in sent, sent)
            assertTrue("""'echo '\''hi'\''; rm -i x'""" in sent, "single-quote escaping: $sent")
            assertTrue("'=claude:'" in sent, "the exact-session target form")
        } finally {
            p.close()
            scope.cancel()
        }
    }

    /** S1-3 B13: a terminal's bytes in ONE exec and one tmux process, in order — printable runs literal, control bytes
     *  by value; the ssh argv carries the two keepalive options; the control client attaches with `ignore-size`. */
    @Test
    fun rawBytesGoInOneExecAsLiteralAndHexRuns_andSshKeepsItsLinkAlive(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val exec = FakeExec()
        val p = LocalTmuxProvider(listOf(TmuxHostCfg("local"), TmuxHostCfg("far", ssh = "far.example", sshPort = 80)),
            TmuxConfig(), scope, exec, statusPacingMs = 60_000, capturePacingMs = 60_000)
        try {
            val before = exec.scripts.size
            p.sendRaw(TmuxTarget("local", "claude"), "ls -la\r\u001b[Aé")
            val mine = exec.scripts.drop(before).filter { "send-keys" in it }
            assertEquals(1, mine.size, "one exec per op: $mine")
            assertEquals("tmux send-keys -t '=claude:' -l -- 'ls -la' ';' send-keys -t '=claude:' -H 0d 1b ';' " +
                "send-keys -t '=claude:' -l -- '[Aé'", mine.single())
            assertEquals(listOf(LocalTmuxProvider.RawRun(true, "a"), LocalTmuxProvider.RawRun(false, "\t\u007f")),
                LocalTmuxProvider.rawRuns("a\t\u007f"))
            val ssh = LocalTmuxProvider.sshArgv(TmuxHostCfg("far", ssh = "far.example", sshPort = 80))
            for (o in listOf("ServerAliveInterval=15", "ServerAliveCountMax=4", "BatchMode=yes", "ConnectTimeout=5"))
                assertTrue(o in ssh, "ssh carries $o: $ssh")
            assertEquals(listOf("tmux", "-C", "attach-session", "-t", "=claude:", "-f", "ignore-size"), p.controlArgv("local", "claude"))
            assertEquals("'tmux' '-C' 'attach-session' '-t' '=claude:' '-f' 'ignore-size'", p.controlArgv("far", "claude")!!.last())
            assertEquals(null, p.controlArgv("nowhere", "claude"))
            // the web's colours: indexes, true colour, flags; default colours absent
            val runs = Sgr.colourRuns("\u001b[1;31mred\u001b[0m plain \u001b[38;2;1;2;3;48;5;200mx")
            assertEquals(listOf("red", " plain ", "x"), runs.map { it.text })
            assertEquals(1, runs[0].fg); assertEquals(Sgr.BOLD, runs[0].flags)
            assertEquals(Sgr.DEFAULT_COLOUR, runs[1].fg)
            assertEquals(Sgr.RGB or 0x010203, runs[2].fg); assertEquals(200, runs[2].bg)
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
            // capture pacing crosses the wire to the host's provider (tpace)
            remote.setCapturePacing(2_000)
            val t1 = System.currentTimeMillis()
            while (local.capturePacing != 2_000L && System.currentTimeMillis() - t1 < 5_000) delay(10)
            assertEquals(2_000L, local.capturePacing, "the Update setting reaches the host's poll loop")
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
    @Volatile var histFails = false

    override fun addListener(l: TmuxProvider.Listener) { listeners.add(l) }
    override fun removeListener(l: TmuxProvider.Listener) { listeners.remove(l) }
    override fun subscribe(l: TmuxProvider.Listener, target: TmuxTarget?) { subscribed.add(target) }
    override fun sendKeys(target: TmuxTarget, keys: List<String>) { sent.add("keys:${target.label}:${keys.joinToString("+")}") }
    override fun sendLiteral(target: TmuxTarget, text: String) { sent.add("lit:${target.label}:$text") }
    override fun history(target: TmuxTarget, lines: Int): List<String> {
        if (histFails) throw IllegalStateException("PC unreachable 12s")
        return histAnswer
    }
    override fun windows(target: TmuxTarget): List<TmuxWinInfo> =
        listOf(TmuxWinInfo(0, "zsh", true, false), TmuxWinInfo(1, "claude", false, true))
    override fun newSession(host: String): String { sent.add("new:$host"); return "g2-1" }
    override fun killSession(target: TmuxTarget) { sent.add("kill:${target.label}") }
    override fun renameSession(target: TmuxTarget, newName: String) { sent.add("ren:${target.label}:$newName") }
    override fun selectWindow(target: TmuxTarget, idx: Int) { sent.add("sel:${target.label}:$idx") }
    override fun resizeWindow(target: TmuxTarget, cols: Int, rows: Int) { sent.add("fit:${target.label}:${cols}x$rows") }
    override fun setCapturePacing(ms: Long) { sent.add("pace:$ms") }
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
    /** (appId, thread, target) of every notice, for the deep-link pin. */
    val links = java.util.concurrent.CopyOnWriteArrayList<Triple<String?, String, String?>>()
    override fun requestRender(window: DamageWindow) { renders++ }
    override fun setOperation(op: String) {}
    override fun notifyInternal(source: String, body: String, urgent: Boolean,
        appId: String?, thread: String, target: String?, answer: Boolean) { notices.add("$source: $body"); links.add(Triple(appId, thread, target)) }
    override fun openWindow(id: String, target: String?): Boolean = false
    override fun runOnShell(action: () -> Unit) = action()
    override fun docContentWidth(): Int = 560
    override fun docContentHeight(): Int = 384
}

class TmuxWindowTest {

    private fun session(name: String, waiting: Boolean = false) =
        TmuxSessionInfo("local", name, 1, attached = false, activity = 1, waiting = waiting,
            lastLine = "tail of $name")

    private fun frame() = PaneFrame(List(22) { "row $it" }, 80, 22, 0, 0, true, false, 0)

    private fun await(deadlineMs: Long = 5_000, cond: () -> Boolean) = runBlocking {
        waitUntil(deadlineMs, 10, { "condition within ${deadlineMs}ms" }) { cond() }
    }

    private fun build(): Triple<TmuxWindow, FakeProvider, FakeServices> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val p = FakeProvider()
        val w = TmuxWindow(MonoFake(), p, scope)
        val svc = FakeServices()
        w.onRegistered(svc)
        return Triple(w, p, svc)
    }

    /** APK 0.65, P6 item 2 (the loop-cost sweep's LC-1, its source half): a status push asks for a render only when
     *  what the session list shows changed — the sessions, the config, or an age the list prints (`age()` reads the
     *  clock at paint time). The PC's `statusLoop` sends every 2.8 s, changed or not. Unfixed: every push asks. */
    @Test
    fun aStatusPushThatChangesNothingShownAsksForNoRender() {
        val (w, p, svc) = build()
        w.onActivate(svc, wm.damage.core.shell.ActivationSource.SWITCHER)
        p.pushStatus(session("alpha"))
        val first = svc.renders
        p.pushStatus(session("alpha"))                       // the same list; its age (days since 1970) the same
        assertEquals(first, svc.renders, "an unchanged push asks for no render")
        p.pushStatus(session("alpha", waiting = true))
        assertEquals(first + 1, svc.renders, "a changed list asks for one")
        val fresh = TmuxSessionInfo("local", "alpha", 1, attached = false, activity = System.currentTimeMillis() / 1000 - 5,
            waiting = true, lastLine = "tail of alpha")
        p.pushStatus(fresh)
        val aged = svc.renders
        Thread.sleep(1_100)                                   // the age the row prints moves from "5s" to "6s" or later
        p.pushStatus(fresh)
        assertEquals(aged + 1, svc.renders, "the same list with a moved age asks for one")
    }

    @Test
    fun deepLevelParkAndReturnReArmsTheSubscription() {
        // R6#1: the subscription keys to the TARGET, not the level — parking
        // at a DEEP level (Session…) unsubscribed, and onActivate's old
        // three-level gate re-armed nothing: back to LIVE = a silently
        // frozen pane
        val (w, p, svc) = build()
        w.onActivate(svc, wm.damage.core.shell.ActivationSource.SWITCHER)
        p.pushStatus(session("claude"))
        (w.view() as WindowView.ListView).onCommit(0)      // open → LIVE, subscribed
        (w.view() as WindowView.CanvasView).onTap!!()      // → KEYS
        val keys = w.view() as WindowView.ListView
        keys.onCommit(TmuxConfig.DEFAULT_QUICK_KEYS.size + 3)   // → Session…
        w.onDeactivate()                                    // park: unsubscribes
        assertEquals(null, p.subscribed.last(), "parking unsubscribes")
        w.onActivate(svc, wm.damage.core.shell.ActivationSource.SWITCHER)                                   // return, still deep
        assertEquals(TmuxTarget("local", "claude"), p.subscribed.last(),
            "focus re-arms the subscription while a target exists (R6#1)")
    }

    @Test
    fun mainEntryShowsTheSessionListAndReleasesTheCapture() {
        // Adam, 2026-09-04 (HOLDEM.md §3): "going to Tmux from Main should
        // present a list of sessions, but Switcher to Tmux should go directly
        // into the last session where I left off".
        val (w, p, svc) = build()
        w.onActivate(svc, wm.damage.core.shell.ActivationSource.SWITCHER)
        p.pushStatus(session("alpha"), session("claude"))
        (w.view() as WindowView.ListView).onCommit(1)      // open claude → LIVE
        assertEquals(TmuxTarget("local", "claude"), p.subscribed.last())
        assertEquals("claude", w.title())

        // the switcher RESUMES the pane
        w.onDeactivate()
        w.onActivate(svc, wm.damage.core.shell.ActivationSource.SWITCHER)
        assertEquals("claude", w.title(), "the switcher must resume the session")
        assertEquals(TmuxTarget("local", "claude"), p.subscribed.last())

        // Main lands on the session list, with the capture released and the
        // cursor on the session we came out of (one tap back down)
        w.onDeactivate()
        w.onActivate(svc, wm.damage.core.shell.ActivationSource.MAIN)
        assertEquals("sessions", w.title(), "Main entry must show the session list")
        assertEquals(1, w.levelDepth(), "MAIN entry lands at depth 1 in every window")
        assertEquals(null, p.subscribed.last(), "the session list holds no capture loop")
        (w.view() as WindowView.ListView).let { v ->
            assertEquals(1, v.model.cursor, "the cursor rests on the session we left")
        }
    }

    @Test
    fun theGrammarWalksSessionsLiveHistoryKeysAndBack() {
        val (w, p, _) = build()
        p.pushStatus(session("claude"), session("spare"))
        val sessions = w.view() as WindowView.ListView
        assertEquals(3, sessions.rowCount(), "2 sessions + 1 new-session row")
        sessions.onCommit(0)
        assertEquals("claude", w.title())
        assertEquals(listOf<TmuxTarget?>(TmuxTarget("local", "claude")), p.subscribed.toList())
        p.pushFrame(TmuxTarget("local", "claude"), frame())
        val live = w.view() as WindowView.CanvasView
        val g = Gray8(640, 480)
        live.paint(g, Rect(16, 34, 608, 416))
        var ink = 0
        for (y in 34 until 450) for (x in 16 until 624) if (g[x, y].toInt() != 0) ink++
        assertTrue(ink > 200, "the live grid painted")
        // scroll-up = time: the frozen history arrives, rendered through the
        // LIVE fit (same face/size/width), one notch into the past
        live.onScroll!!(-1)
        assertTrue(w.title().contains("history"))
        await { runCatching {
            val hv = w.view() as WindowView.CanvasView
            val hg = Gray8(640, 480)
            hv.paint(hg, Rect(16, 34, 608, 416))
            var hink = 0
            for (y in 34 until 450) for (x in 16 until 624) if (hg[x, y].toInt() != 0) hink++
            hink > 200
        }.getOrDefault(false) }
        // the notch toward now at the live edge RETURNS TO LIVE seamlessly
        (w.view() as WindowView.CanvasView).onScroll!!(1)
        assertEquals("claude", w.title(), "scroll-down at the live edge returns to live")
        live.onScroll!!(-1)   // re-enter, then back out via double-tap
        assertTrue(w.title().contains("history"))
        assertTrue(w.back(), "back leaves history")
        // tap descends to keys; a quick key sends and drops back to LIVE
        (w.view() as WindowView.CanvasView).onTap!!()
        val keys = w.view() as WindowView.ListView
        assertEquals(TmuxConfig.DEFAULT_QUICK_KEYS.size + 4, keys.rowCount())
        keys.onCommit(1)   // "y"
        await { p.sent.any { it == "keys:claude:y" } }
        assertEquals("claude", w.title(), "sending returns to the live view")
        assertTrue(w.back(), "live -> sessions")
        assertEquals("sessions", w.title())
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
        assertEquals("claude", w.title(), "watching it run")
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
        assertEquals("claude:1", w.title())
        assertTrue(p.subscribed.last() == TmuxTarget("local", "claude", 1))
        assertTrue(p.sent.none { it.startsWith("sel:") }, "viewing never select-windows")
        // session actions: mute, then end with confirm
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
        assertEquals("sessions", w.title(), "back at sessions after the kill")
    }

    @Test
    fun alternateScreenFramesFallBackToTheGrid() {
        // flow pads 16 px — column 0 never inks the pad; the GRID (the
        // alternate-screen fallback) draws column 0 at the rect edge
        val (w, p, _) = build()
        p.pushStatus(session("claude"))
        (w.view() as WindowView.ListView).onCommit(0)
        fun inkInPad(alternate: Boolean): Int {
            p.pushFrame(TmuxTarget("local", "claude"),
                PaneFrame(List(22) { "Xrow $it" }, 80, 22, 0, 0, true, alternate, 0))
            val g = Gray8(640, 480)
            (w.view() as WindowView.CanvasView).paint(g, Rect(16, 34, 608, 416))
            var ink = 0
            for (y in 34 until 450) for (x in 16 until 30) if (g[x, y].toInt() != 0) ink++
            return ink
        }
        assertEquals(0, inkInPad(alternate = false), "flow keeps its 16 px pad clear")
        assertTrue(inkInPad(alternate = true) > 0, "an alternate-screen TUI renders through the grid")
    }

    @Test
    fun updatePacingRowAppliesToTheProviderAndPersists() {
        val (w, p, _) = build()
        val row = w.appSettings().first { it.name == "Update" }
        assertEquals("1 s", row.current(), "the flow rework's default cadence")
        row.apply("2 s")
        assertTrue(p.sent.contains("pace:2000"), "the choice reaches the provider")
        val blob = w.saveState()
        val (w2, p2, _) = build()
        w2.restoreState(blob)
        assertTrue(p2.sent.contains("pace:2000"), "restore re-asserts the chosen pacing on the host")
        assertEquals("2 s", w2.appSettings().first { it.name == "Update" }.current())
    }

    @Test
    fun aFailedHistoryCaptureNeverStrandsTheWindow() {
        // the 2026-08-31 freeze: histLoading stayed true after a failed
        // capture, and every later scroll early-returned on it — the window
        // looked stalled until a restart
        val (w, p, _) = build()
        p.pushStatus(session("claude"))
        (w.view() as WindowView.ListView).onCommit(0)
        p.pushFrame(TmuxTarget("local", "claude"), frame())
        p.histFails = true
        (w.view() as WindowView.CanvasView).onScroll!!(-1)
        await { w.title().startsWith("claude") && w.title().contains("failed") }
        // and the window is NOT stranded: the next scroll-up works
        p.histFails = false
        (w.view() as WindowView.CanvasView).onScroll!!(-1)
        await { w.title().contains("history") }
    }

    /** Review §29 (the live walk): a tap on the alert's box lands in the live view of the session that wants input.
     *  S1-3 B14: the box is the PC's record notice (`TmuxCore` raises it, `TmuxCoreTest` pins its body, thread and
     *  target); the presenter raises none, and its deep link still opens that session. */
    @Test
    fun anAlertIsNotRaisedByThePresenter_andItsDeepLinkOpensTheSession() {
        val (w, p, svc) = build()
        p.pushStatus(session("claude"), session("build"))
        p.pushAlert(session("claude", waiting = true))
        assertTrue(svc.notices.none { "wants input" in it }, "the PC raises the alert, never the presenter: ${svc.notices}")
        assertTrue(w.dirty, "a waiting edge still marks the window")
        assertTrue(w.open("session:local:build"), "the deep link resolves")
        assertEquals(TmuxTarget("local", "build"), p.subscribed.last())
        assertTrue(w.title().startsWith("build"), "the live view of the session that wants input (was '${w.title()}')")
        assertFalse(w.open("bogus:x"), "an unknown target is refused, loudly upstream")
        assertFalse(w.open("session:nocolon"), "a malformed target is refused")
    }

    @Test
    fun theOpenSessionRoundTripsAndTheRowsComeFromTheRecord() {
        val (w, p, _) = build()
        p.pushStatus(session("claude"))
        // the view's own state: target + pace survive; deep levels restore to LIVE
        (w.view() as WindowView.ListView).onCommit(0)
        (w.view() as WindowView.CanvasView).onTap!!()
        val blob = w.saveState()
        assertEquals(setOf("level", "host", "session", "window", "paceMs"), blob.keys,
            "S1-3 B14: the blob keeps this view's state only (Alerts, mutes, Context rows and Size are the record's)")
        val (w2, p2, _) = build()
        w2.restoreState(blob)
        p2.pushStatus(session("claude"))
        assertEquals("claude", w2.title(), "restores to the live grid")
        assertEquals(480, w2.preferredHeight, "the design-point height default")
        // a row changed on the glasses goes to the record (field first, op after), and back from it
        w2.appSettings().first { it.name == "Size" }.apply("352")
        assertEquals(352, w2.preferredHeight)
        await { wm.damage.core.windows.tmux.TmuxCore.size(w2.source.state()) == 352 }
    }
}

/** MonoFake's metrics with no ink: the shell's chrome (the title, the input-echo cell) draws nothing, so the 5 s
 *  idle tick — which flushes chrome-only changes such as the echo moving from UP to TAP — can never move the flush
 *  count, and the count reads the window's content alone. */
private class BlankText : TextRasterizer {
    override fun measure(text: String, font: FontSpec): Int = text.length * maxOf(1, font.sizePx * 6 / 10)
    override fun metrics(font: FontSpec) = FontMetrics(
        font.sizePx, maxOf(1, font.sizePx * 3 / 10), maxOf(2, font.sizePx * 13 / 10))
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {}
    override fun covers(text: String, font: FontSpec) = true
}

/** Build Q T1 (Adam, 2026-11-17): "if I'm in the scroll back function in tmux, and I single tap, nothing happens".
 *  A swipe read as a tap opened the keys; backing out of them landed on live and lost his place. Driven through the
 *  real shell on the simulator, so "nothing" is measured as the flushes the glasses would receive. */
class TmuxScrollbackTapTest {

    @Test
    fun aTapInScrollbackChangesNothingAndADoubleTapIsLiveAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = java.nio.file.Files.createTempDirectory("damage-tmux-tap")
        try {
            val p = FakeProvider()
            p.histAnswer = (1..200).map { "history line $it" }
            val rec = RecFake()
            val w = TmuxWindow(rec, p, scope)
            val transport = wm.damage.core.transport.SimTransport(wm.damage.core.sim.GlassFirmwareSim(), scope,
                wm.damage.core.transport.SimTransport.Timing(instant = true))
            val shell = wm.damage.core.shell.Shell(BlankText(), transport,
                wm.damage.core.shell.Persistence(tmp.resolve("state.json")), null, scope, HELD_CLOCK)
            shell.register(w)
            shell.start()
            /** ONE evaluation per wait (§27.6). */
            suspend fun settle(what: String, cond: () -> Boolean = { true }) =
                waitUntil(30_000, 5, { "$what: ${shell.quiescenceReport()} title='${w.title()}'" }) {
                    shell.isQuiescent() && cond()
                }
            settle("boot")
            shell.services.runOnShell { shell.services.openWindow("tmux", null) }
            settle("tmux focused") { shell.currentWindowId() == "tmux" }
            p.pushStatus(TmuxSessionInfo("local", "claude", 1, attached = false, activity = 1, waiting = false,
                lastLine = "tail"))
            settle("the session list") { w.title() == "sessions" }
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_CLICK)
            settle("live") { w.title() == "claude" }
            p.pushFrame(TmuxTarget("local", "claude"), PaneFrame(List(22) { "row $it" }, 80, 22, 0, 0, true, false, 0))
            settle("the live frame painted") { rec.drawn.any { it.first.startsWith("row ") } }
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_TOP)          // scroll-up IS scrollback
            settle("the scrollback painted") { rec.drawn.any { it.first.startsWith("history line") } }
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_TOP)          // one notch further back: a place
            settle("two notches back") { w.title().contains("history −10") }
            val place = w.title()
            val flushes = shell.flushesSubmitted

            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_CLICK)
            settle("the tap in scrollback")
            assertEquals(place, w.title(), "the tap leaves the view and its place as they were")
            assertEquals(3, w.levelDepth(), "still in scrollback, not the keys")
            assertEquals(flushes, shell.flushesSubmitted, "the tap sends no frame")
            assertTrue(p.sent.none { it.startsWith("keys:") || it.startsWith("lit:") }, "no key reached the session")

            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_DOUBLE_CLICK)
            settle("the double tap") { w.title() == "claude" }
            assertEquals(2, w.levelDepth(), "a double tap is the live view again")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
