package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.games.GamesWindow
import wm.damage.core.wire.EvenHubMsg

/**
 * The 2026-09-04 whole-codebase review (`HANDOFF.md` §28) — pins for the
 * verified defects.
 */
class Review28Test {

    private class Rig(tmp: Path) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val win = GamesWindow(FakeText(), scope)

        init {
            win.roster.worldSeed = 20260905L
            shell.register(win)
        }

        suspend fun start() {
            shell.start()
            shell.postGesture(EvenHubMsg.EV_CLICK)      // Main row 0 = Games
            settle()
        }

        suspend fun stop() {
            shell.stop()
            scope.cancel()
        }

        suspend fun settle(what: String = "") {
            val t0 = System.currentTimeMillis()
            while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 20_000) delay(10)
            assertTrue(shell.isQuiescent(), "the shell did not settle $what")
        }

        suspend fun await(what: String, ms: Long = 20_000, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < ms) delay(20)
            assertTrue(cond(), "did not converge: $what (title=${win.title()} level=${win.levelName})")
        }

        suspend fun tap() { shell.postGesture(EvenHubMsg.EV_CLICK); settle() }
        suspend fun back() { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); settle() }
        suspend fun down() { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); settle() }

        suspend fun menuPick(label: String) {
            assertTrue(shell.menuIsOpen, "no menu is open (wanted '$label')")
            val i = shell.menuLabels.indexOfFirst { it == label || it.startsWith(label) }
            assertTrue(i >= 0, "menu row '$label' not in ${shell.menuLabels}")
            repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { down() }
            tap()
        }

        suspend fun sitDown() {
            await("the games root") { win.title() == "games" }
            tap()                                     // Hold'em -> the table select
            await("the table list") { win.title() == "hold'em" }
            tap()                                     // Regular
            await("the buy-in confirm") { shell.menuIsOpen }
            menuPick("Sit down")
            await("the table") { win.levelDepth() == 2 && win.title().startsWith("hold'em ·") }
        }

        /** Take the contextual give-up row (Check or Fold) through its confirm.
         *  The pace is set to its slowest first: the test leaves the table
         *  INSIDE the pace that follows, and 600 ms is a narrow window on a
         *  loaded machine — the precondition below would then fail loudly for
         *  a reason that has nothing to do with the defect. */
        suspend fun giveUp() {
            shell.services.runOnShell { win.appSettings().first { it.name == "Bot pace" }.apply("1.5 s") }
            await("your turn") { win.isMyTurn }
            tap()
            await("the action level") { shell.menuIsOpen }
            menuPick(shell.menuLabels.first { it.startsWith("Fold") || it.startsWith("Check") })
            await("the confirm") { shell.menuIsOpen && shell.menuLabels.firstOrNull() == "Cancel" }
            down()
            tap()
            await("the action reached the engine") { !shell.menuIsOpen }
        }
    }

    /**
     * §28 #G1 — the pacer STALLED after a back-and-return inside one bot's
     * pace. `back()` bumped the pacer generation; re-entering the table found
     * `thinking` still set and did nothing; the stale decision then cleared
     * `thinking` and, seeing the bumped generation, returned without pumping.
     * Nothing scheduled the next decision: the table sat on "Roy G. …" until a
     * tap, and that tap SKIPPED the pacing for the rest of the street. Every
     * other invalidation (deactivating the window, a restore) had the same
     * hole. The fix: whoever invalidates the pacer also clears `thinking`, and
     * a superseded completion never touches it.
     */
    @Test
    fun backingOutAndReturningMidDecisionDoesNotStallTheTable(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-r28-pacer")
        val r = Rig(tmp)
        try {
            r.start()
            r.sitDown()
            r.giveUp()
            // the bots are deciding now, on the 600 ms pace; a stalled hand
            // would already be complete or already back on Adam, which would
            // make this test vacuous — so the precondition is asserted
            assertTrue(!r.win.isMyTurn && !r.win.handIsComplete && r.win.tableRunning,
                "precondition: a bot must be deciding when the window is left")
            r.back()                                          // TABLE -> GAMES, mid-decision
            r.await("back at the games root") { r.win.levelName == "GAMES" }
            r.tap()                                           // Hold'em -> straight back to the table
            r.await("the table again") { r.win.levelName == "TABLE" }
            // the stale decision lands within its pace; the table must keep
            // moving on its own afterwards — without a tap, without a scroll
            r.await("the hand keeps moving after the return", ms = 15_000) {
                r.win.isMyTurn || r.win.handIsComplete || !r.win.tableRunning
            }
        } finally {
            r.stop()
        }
    }

    /** The same stall through the window's own activation hooks — the
     *  switcher path: leave for another window and come back inside a pace. */
    @Test
    fun deactivatingAndReactivatingMidDecisionDoesNotStallTheTable(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-r28-pacer2")
        val r = Rig(tmp)
        try {
            r.start()
            r.sitDown()
            r.giveUp()
            assertTrue(!r.win.isMyTurn && !r.win.handIsComplete && r.win.tableRunning,
                "precondition: a bot must be deciding when the window is left")
            r.shell.services.runOnShell {
                r.win.onDeactivate()
                r.win.onActivate(r.shell.services, ActivationSource.SWITCHER)
            }
            r.await("the hand keeps moving after the reactivation", ms = 15_000) {
                r.win.isMyTurn || r.win.handIsComplete || !r.win.tableRunning
            }
        } finally {
            r.stop()
        }
    }
}

/** A rasterizer whose ink FOLLOWS the requested size, the way the real faces
 *  do — `FakeText` answers 12+4 rows whatever the size, which is exactly the
 *  blind spot the §28 line-box and state-line defects hid in. */
class ScalingText : wm.damage.core.text.TextRasterizer {
    private fun asc(f: wm.damage.core.text.FontSpec) = Math.ceil(f.sizePx * 1.25).toInt()
    private fun desc(f: wm.damage.core.text.FontSpec) = Math.ceil(f.sizePx * 0.42).toInt()
    private fun adv(f: wm.damage.core.text.FontSpec) = maxOf(4, Math.ceil(f.sizePx * 0.6).toInt())
    override fun measure(text: String, font: wm.damage.core.text.FontSpec): Int = text.length * adv(font)
    override fun metrics(font: wm.damage.core.text.FontSpec) =
        wm.damage.core.text.FontMetrics(asc(font), desc(font), asc(font) + desc(font) + 2)
    override fun draw(surface: wm.damage.core.gfx.Gray8, x: Int, y: Int, text: String,
        font: wm.damage.core.text.FontSpec, level: Int) {
        // every glyph fills its whole ascent + descent box, so ink lands
        // exactly where the metrics promise it will
        val a = adv(font)
        for ((i, ch) in text.withIndex()) {
            if (ch != ' ') surface.fillRect(x + i * a, y, a - 1, asc(font) + desc(font), level)
        }
    }
    override fun covers(text: String, font: wm.damage.core.text.FontSpec) = true
}

class Review28ScaleTest {

    /**
     * §28 #2 — the Reader's 30 px line box was a constant with a guard that
     * REFUSED any layout whose measured ink exceeded it. Alegreya 17 inks 28
     * rows at 100 % (measured on the real rasterizer, 2026-09-04), 34 at
     * 115 % and 36 at 130 %: two of the four rungs of the size ladder could
     * not open a book at all — "could not open …" on every title, from a
     * LintError thrown off-loop. The box now follows the face.
     */
    @Test
    fun aBookOpensAtTheTopOfTheSizeLadder(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-r28-reader")
        val books = tmp.resolve("books")
        Files.createDirectories(books)
        Files.writeString(books.resolve("r28.txt"), buildString {
            appendLine("CHAPTER I")
            appendLine()
            repeat(120) { appendLine("Paragraph $it. The line box follows the face, and the face follows the ladder.\n") }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        store.put("shell.settings", wm.damage.core.shell.ShellSettings(fontScale = 1.3).toJson())
        val text = ScalingText()
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(text, transport, store, null, scope)
        val reader = wm.damage.core.windows.reader.ReaderWindow(text, wm.damage.core.content.LocalContent(books), scope)
        shell.register(reader)
        try {
            shell.start()
            suspend fun settle() {
                val t0 = System.currentTimeMillis()
                while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 20_000) delay(10)
            }
            suspend fun await(what: String, cond: () -> Boolean) {
                val t0 = System.currentTimeMillis()
                while (!cond() && System.currentTimeMillis() - t0 < 20_000) delay(20)
                assertTrue(cond(), "did not converge: $what (title=${reader.title()})")
            }
            settle()
            assertTrue(shell.settings.fontScale == 1.3, "the rig runs at 130 %")
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Main row 0 = Reader
            await("the library") { reader.title() == "library" }
            await("the shelf is scanned") { reader.summary().line == "1 books" }
            shell.postGesture(EvenHubMsg.EV_CLICK)          // open the one book
            await("the book opens at 130 %") { reader.title().contains("p.") }
            // and the box it laid out with holds the face: the view's line
            // height is at least the measured ink, on the 2 px grid
            val v = reader.view() as wm.damage.core.shell.WindowView.DocView
            val ink = text.metrics(wm.damage.core.text.FontSpec(wm.damage.core.text.Face.READER, 22))
                .let { it.ascent + it.descent }
            assertTrue(v.lineHeight >= ink && v.lineHeight % 2 == 0,
                "line box ${v.lineHeight} must hold $ink rows of ink on the 2 px grid")
        } finally {
            shell.stop()
            scope.cancel()
        }
    }

    /**
     * §28 #3 — Tmux's staleness line sat at `rect.bottom - 20` under a face
     * whose ink is exactly 20 rows at 100 %: at 115 % it ran 2 rows past the
     * content rect and at 130 % 5 — ink outside the damaged rect, the class
     * §27 named. It is placed from the measured ink now.
     */
    @Test
    fun theTmuxStateLineStaysInsideItsRectAtEveryScale(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-r28-tmux")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        store.put("shell.settings", wm.damage.core.shell.ShellSettings(fontScale = 1.3).toJson())
        val text = ScalingText()
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(text, transport, store, null, scope)
        val listeners = java.util.concurrent.CopyOnWriteArrayList<wm.damage.core.windows.tmux.TmuxProvider.Listener>()
        val provider = object : wm.damage.core.windows.tmux.TmuxProvider {
            override fun addListener(l: wm.damage.core.windows.tmux.TmuxProvider.Listener) { listeners.add(l) }
            override fun removeListener(l: wm.damage.core.windows.tmux.TmuxProvider.Listener) { listeners.remove(l) }
            override fun subscribe(l: wm.damage.core.windows.tmux.TmuxProvider.Listener, target: wm.damage.core.windows.tmux.TmuxTarget?) {}
            override fun sendKeys(target: wm.damage.core.windows.tmux.TmuxTarget, keys: List<String>) {}
            override fun sendLiteral(target: wm.damage.core.windows.tmux.TmuxTarget, text: String) {}
            override fun history(target: wm.damage.core.windows.tmux.TmuxTarget, lines: Int): List<String> = emptyList()
            override fun windows(target: wm.damage.core.windows.tmux.TmuxTarget) = emptyList<wm.damage.core.windows.tmux.TmuxWinInfo>()
            override fun newSession(host: String): String = "g2-1"
            override fun killSession(target: wm.damage.core.windows.tmux.TmuxTarget) {}
            override fun renameSession(target: wm.damage.core.windows.tmux.TmuxTarget, newName: String) {}
            override fun selectWindow(target: wm.damage.core.windows.tmux.TmuxTarget, idx: Int) {}
            override fun resizeWindow(target: wm.damage.core.windows.tmux.TmuxTarget, cols: Int, rows: Int) {}
            override fun close() {}
        }
        val win = wm.damage.core.windows.tmux.TmuxWindow(text, provider, scope)
        shell.register(win)
        try {
            shell.start()
            suspend fun settle() {
                val t0 = System.currentTimeMillis()
                while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 20_000) delay(10)
            }
            suspend fun await(what: String, cond: () -> Boolean) {
                val t0 = System.currentTimeMillis()
                while (!cond() && System.currentTimeMillis() - t0 < 20_000) delay(20)
                assertTrue(cond(), "did not converge: $what (title=${win.title()})")
            }
            settle()
            val session = wm.damage.core.windows.tmux.TmuxSessionInfo("beardos", "claude", 1, false,
                System.currentTimeMillis() / 1000, false, "waiting on you")
            for (l in listeners) l.status(listOf(session), wm.damage.core.windows.tmux.TmuxConfig())
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Main row 0 = Tmux
            await("the sessions list") { win.title() == "sessions" }
            shell.postGesture(EvenHubMsg.EV_CLICK)          // open it → live
            await("the live view") { win.title() == "claude" }
            val target = wm.damage.core.windows.tmux.TmuxTarget("beardos", "claude")
            for (l in listeners) l.frame(target, wm.damage.core.windows.tmux.PaneFrame(
                (1..8).map { "line $it of the pane" }, 80, 24, 0, 8, true, false, System.currentTimeMillis()))
            for (l in listeners) l.state("slappy unreachable 12s")
            settle()
            // paint the live canvas straight into a scratch surface: every
            // lit pixel must sit INSIDE the rect the window is handed
            val v = win.view() as wm.damage.core.shell.WindowView.CanvasView
            val g = wm.damage.core.gfx.Gray8(640, 480)
            val rect = wm.damage.core.geom.Rect(16, 100, 608, 200)
            v.paint(g, rect)
            var below = 0
            for (y in rect.bottom until 480) for (x in 0 until 640) if (g[x, y] != 0) below++
            assertTrue(below == 0, "the tmux state line inked $below pixels below its content rect at 130 %")
            var inside = 0
            for (y in rect.bottom - 40 until rect.bottom) for (x in rect.x until rect.right) if (g[x, y] != 0) inside++
            assertTrue(inside > 0, "the state line was not drawn at all")
        } finally {
            shell.stop()
            scope.cancel()
        }
    }
}

class Review28MainLensTest {

    /**
     * §28 #5 — the Main lens read "library loading" and "0 locations" from
     * boot until each window was first OPENED: neither scanned at
     * registration, so Main summarised a scan nobody had started and a list
     * nobody had asked for. Both scan quietly at registration now.
     */
    @Test
    fun readerAndFilesSummariseTheirRootsBeforeTheyAreEverOpened(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-r28-lens")
        val books = tmp.resolve("books")
        Files.createDirectories(books)
        Files.writeString(books.resolve("one.txt"), "CHAPTER I\n\nA line.\n")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
        val reader = wm.damage.core.windows.reader.ReaderWindow(FakeText(), wm.damage.core.content.LocalContent(books), scope)
        val files = wm.damage.core.windows.files.FilesWindow(FakeText(),
            wm.damage.core.windows.files.LocalFilesProvider(books, tmp.resolve("trash"), null,
                mountsFile = tmp.resolve("no-mounts")), scope, null)
        // Tmux first, so Main's cursor rests on a window that is NOT one of
        // the two under test: neither is activated by the start
        val listeners = java.util.concurrent.CopyOnWriteArrayList<wm.damage.core.windows.tmux.TmuxProvider.Listener>()
        val tmux = wm.damage.core.windows.tmux.TmuxWindow(FakeText(), object : wm.damage.core.windows.tmux.TmuxProvider {
            override fun addListener(l: wm.damage.core.windows.tmux.TmuxProvider.Listener) { listeners.add(l) }
            override fun removeListener(l: wm.damage.core.windows.tmux.TmuxProvider.Listener) { listeners.remove(l) }
            override fun subscribe(l: wm.damage.core.windows.tmux.TmuxProvider.Listener, target: wm.damage.core.windows.tmux.TmuxTarget?) {}
            override fun sendKeys(target: wm.damage.core.windows.tmux.TmuxTarget, keys: List<String>) {}
            override fun sendLiteral(target: wm.damage.core.windows.tmux.TmuxTarget, text: String) {}
            override fun history(target: wm.damage.core.windows.tmux.TmuxTarget, lines: Int): List<String> = emptyList()
            override fun windows(target: wm.damage.core.windows.tmux.TmuxTarget) = emptyList<wm.damage.core.windows.tmux.TmuxWinInfo>()
            override fun newSession(host: String): String = "g2-1"
            override fun killSession(target: wm.damage.core.windows.tmux.TmuxTarget) {}
            override fun renameSession(target: wm.damage.core.windows.tmux.TmuxTarget, newName: String) {}
            override fun selectWindow(target: wm.damage.core.windows.tmux.TmuxTarget, idx: Int) {}
            override fun resizeWindow(target: wm.damage.core.windows.tmux.TmuxTarget, cols: Int, rows: Int) {}
            override fun close() {}
        }, scope)
        shell.register(tmux)
        shell.register(reader)
        shell.register(files)
        try {
            shell.start()
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 20_000 &&
                !(reader.summary().line == "1 books" && files.summary().line.endsWith("locations") &&
                    !files.summary().line.startsWith("0 "))) delay(20)
            assertTrue(shell.currentWindowId() == null, "the start must land on Main, opening neither window")
            assertTrue(reader.summary().line == "1 books",
                "the Reader's Main lens must count the shelf before the window is opened: '${reader.summary().line}'")
            assertTrue(!files.summary().line.startsWith("0 ") && files.summary().line.endsWith("locations"),
                "the Files Main lens must count the locations before the window is opened: '${files.summary().line}'")
        } finally {
            shell.stop()
            scope.cancel()
        }
    }
}

class Review28PaintTest {

    private fun scaled(s: Double) = wm.damage.core.text.StyledText(ScalingText()) {
        wm.damage.core.text.StyleTransform(scale = s).apply(it)
    }

    /**
     * §28 #7 — the menu box's rhythm was three constants (an 18 px title
     * band, a 24 px row pitch, the box height from both) sized for the chrome
     * face at 100 %. Under the global font scale the title's baseline fell
     * below its own rule and the last row's ink ran past the box's bottom —
     * outside the menu's damage rect. Both follow the measured face now.
     */
    @Test
    fun aScaledMenuKeepsItsRowsInsideTheBoxAndItsTitleAboveTheRule() {
        val text = scaled(1.2)
        val menu = wm.damage.core.shell.MenuSurface(text)
        menu.openWith(wm.damage.core.shell.MenuSurface.Spec("title",
            listOf(wm.damage.core.shell.MenuSurface.Item("One"), wm.damage.core.shell.MenuSurface.Item("Two"),
                wm.damage.core.shell.MenuSurface.Item("Three")), onCommit = {}))
        val l = wm.damage.core.geom.Layout(wm.damage.core.geom.Rect(0, 0, 640, 480))
        val g = wm.damage.core.gfx.Gray8(640, 480)
        val box = menu.paint(g, l)!!
        var below = 0
        for (y in box.bottom until 480) for (x in 0 until 640) if (g[x, y] != 0) below++
        assertTrue(below == 0, "a scaled menu inked $below pixels below its own box")
        // The title's BASELINE must not fall past the rule. Since §30 the
        // title is placed from its measured ascent so the baseline lands ON
        // the band's last row, and the rule is painted BEFORE it — so the
        // rule is located past the title's own columns (a box-glyph fake
        // covers everything it draws over, descent included).
        val fTitle = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 13, bold = true)
        val titleW = text.measure("TITLE", fTitle)
        var ruleY = -1
        for (y in box.y + 4 until box.bottom) {
            if ((box.x + 12 + titleW until box.right - 12).all { x -> g[x, y] == wm.damage.core.gfx.Level.FAINT }) { ruleY = y; break }
        }
        assertTrue(ruleY >= 0, "no title rule found in the box")
        val titleTop = (box.y + 2 until box.bottom).first { y ->
            (box.x + 8 until box.x + 8 + titleW).any { x -> g[x, y] == wm.damage.core.gfx.Level.DIM }
        }
        val baseline = titleTop + text.metrics(fTitle).ascent
        assertTrue(baseline <= ruleY, "the title's baseline ($baseline) falls past its rule ($ruleY)")
    }

    /**
     * §28 #9 — the table's status band was a 24 px constant. Under a per-app
     * scale the 33 px status line spilled into the hole-card plane below it
     * and was drawn shifted: a cut through the text on every frame. The band
     * and your line are sized from the measured ink now; at 100 % the
     * measured values are the design numbers.
     */
    @Test
    fun aScaledStatusLineStaysOutOfTheHolePlane() {
        val text = scaled(1.3)
        val view = wm.damage.core.windows.games.holdem.HoldemView(text)
        val who = (0 until 6).map { wm.damage.core.windows.games.kit.Seats.Occupant("c$it", "Seat $it", human = it == 0) }
        val table = wm.damage.core.windows.games.holdem.HoldemTable.start(
            wm.damage.core.windows.games.holdem.HoldemRules.Table.REGULAR, 7L, who, IntArray(6) { 200 })
        val v = table.view()
        val g = wm.damage.core.gfx.Gray8(640, 480)
        val content = wm.damage.core.geom.Layout(wm.damage.core.geom.Rect(0, 0, 640, 480)).content
        view.paint(g, content, wm.damage.core.windows.games.holdem.HoldemView.Model(
            v = v, spec = table.spec, mySeat = 0, revealed = 0, cast = emptyMap(), cursor = -1,
            showStats = true, archetypes = false,
            handsToLevel = wm.damage.core.windows.games.holdem.HoldemRules.handsToNextLevel(v.handNo)))
        val t = view.layout!!
        val fStatusBig = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 17, bold = true)
        val ink = text.metrics(fStatusBig).let { it.ascent + it.descent }
        assertTrue(t.status.h >= ink, "the status band ${t.status.h} cannot hold its ${ink} px line")
        // the hole plane starts two rows above the hole band (HoldemView.holePlane):
        // nothing the status band draws may reach it
        var lit = 0
        for (y in t.hole.y - 2 until t.hole.y) for (x in t.status.x until t.status.right) if (g[x, y] != 0) lit++
        assertTrue(lit == 0, "status ink ($lit px) reached the hole plane at 130 %")
        // and at 100 % the bands are the design's own numbers
        val v100 = wm.damage.core.windows.games.holdem.HoldemView(ScalingText())
        v100.paint(wm.damage.core.gfx.Gray8(640, 480), content, wm.damage.core.windows.games.holdem.HoldemView.Model(
            v = v, spec = table.spec, mySeat = 0, revealed = 0, cast = emptyMap(), cursor = -1,
            showStats = true, archetypes = false, handsToLevel = 20))
        assertTrue(v100.layout!!.status.h >= 24 && v100.layout!!.yourLine.h >= 24,
            "the 100 % bands must be at least the design's 24 px")
    }

    /** §28 #8 — the tmux status script's tail must drop blank rows first
     *  (see LocalTmuxProvider.STATUS_SCRIPT): `capture-pane -p` prints the
     *  whole visible pane, trailing empties included. */
    @Test
    fun theTmuxStatusTailSkipsBlankRows() {
        val script = wm.damage.core.windows.tmux.LocalTmuxProvider.STATUS_SCRIPT
        assertTrue(script.contains("grep -v '^[[:space:]]*\$' | tail -5"),
            "the per-session tail must filter blank rows before taking the last five:\n$script")
    }
}

class Review28SwitcherTest {
    /**
     * §28 #11 — the wheel's centre band was a constant 88 px, exactly what
     * Clear Sans 21 bold needs at 100 %; under the global font scale the
     * name's baseline fell below the lower rule and the lower neighbour's
     * descenders ran past the panel — outside the wheel's damage rect.
     */
    @Test
    fun aScaledSwitcherStaysInsideItsPanel(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-r28-switcher")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = wm.damage.core.text.StyledText(ScalingText()) {
                wm.damage.core.text.StyleTransform(scale = 1.2).apply(it)
            }
            val a = GamesWindow(FakeText(), scope)
            val b = wm.damage.core.windows.reader.ReaderWindow(FakeText(), wm.damage.core.content.LocalContent(tmp), scope)
            val sw = wm.damage.core.shell.Switcher(text)
            sw.openWith(a, listOf(a, b))
            val l = wm.damage.core.geom.Layout(wm.damage.core.geom.Rect(0, 0, 640, 480))
            val g = wm.damage.core.gfx.Gray8(640, 480)
            val p = sw.paint(g, l)
            var outside = 0
            for (y in 0 until 480) for (x in 0 until 640) {
                val inside = x >= p.x && x < p.right && y >= p.y && y < p.bottom
                if (g[x, y] != 0 && !inside) outside++
            }
            assertTrue(outside == 0, "the scaled switcher inked $outside pixels outside its panel $p")
        } finally {
            scope.cancel()
        }
    }
}
