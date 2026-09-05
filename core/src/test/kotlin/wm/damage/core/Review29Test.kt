package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.Draw
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/**
 * The fourth whole-codebase review (`HANDOFF.md` §29) — pins for the
 * verified defects.
 */
class Review29Test {

    /** A rasterizer whose ink FOLLOWS THE SIZE the way the real faces do —
     *  the chrome sizes carry Clear Sans's MEASURED ascent and descent
     *  (2026-09-04, the real AWT rasterizer: 13 → 16 + 4, 17 → 20 + 5,
     *  18 → 21 + 6, 21 → 25 + 7), the rest scale the same way — and draws
     *  every string as a solid block of its full ink, so a row's descenders
     *  are pixels a test can count. */
    private class ScaledFake : TextRasterizer {
        private val measured = mapOf(11 to (13 to 4), 12 to (15 to 4), 13 to (16 to 4), 14 to (17 to 5),
            15 to (18 to 5), 16 to (19 to 5), 17 to (20 to 5), 18 to (21 to 6), 21 to (25 to 7))
        private fun asc(f: FontSpec) = measured[f.sizePx]?.first ?: Math.round(f.sizePx * 7.0 / 6).toInt()
        private fun desc(f: FontSpec) = measured[f.sizePx]?.second ?: Math.round(f.sizePx / 3.0).toInt()
        override fun measure(text: String, font: FontSpec): Int = text.length * (font.sizePx / 2)
        override fun metrics(font: FontSpec) = FontMetrics(asc(font), desc(font), asc(font) + desc(font))
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
            if (text.isBlank()) return
            surface.fillRect(x, y, measure(text, font), asc(font) + desc(font), level)
        }
        override fun covers(text: String, font: FontSpec) = true
    }

    /** A list window drawn the way every list in the shell draws: the row's
     *  text at +5 in the 18 px row face, the lens's name at +8 in its bold
     *  and the second line below its MEASURED ink. */
    private class RowsWindow(base: TextRasterizer) : DamageWindow("rows", "Rows", IconKind.FILES) {
        private val tx = styledText(base)
        private val model = ListModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        private val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        fun ink(spec: FontSpec) = tx.metrics(spec).let { it.ascent + it.descent }
        override fun view(): WindowView = WindowView.ListView(model, { 12 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 5, "Row $i", f, Level.BODY) },
            paintLens = { g, r, i ->
                tx.draw(g, r.x + 44, r.y + 8, "Row $i", fB, Level.HEAD)
                tx.draw(g, r.x + 44, Draw.lineBelow(tx, fB, r.y + 8, r.y + 34), "detail", f, Level.BODY)
            },
            onCommit = {})
        override fun title() = "rows"
        override fun summary() = Summary("12 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private suspend fun settle(shell: Shell, what: String) {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 20_000) delay(10)
        assertTrue(shell.isQuiescent(), "the shell did not settle: $what")
    }

    private suspend fun await(what: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 20_000) delay(20)
        assertTrue(cond(), "did not converge: $what")
    }

    /**
     * §29 #1 — the list rhythm follows the measured ink. The design's 32 px
     * row held Clear Sans 18's 27 px exactly under the rows' 5 px offset; at
     * 115 % the face inks 32 and the row directly above the lens lost its
     * descenders to the lens fill (seen live: the `$` and the comma of
     * "$1,000" cut flat at the lens rule). The row pitch and the lens band
     * now grow from the ink, and a window's second lens line sits below the
     * first line's ink instead of at a constant.
     */
    @Test
    fun theRowAboveTheLensKeepsItsInkUpTheLadder(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("r29-rows")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = ScaledFake()
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(text, transport, Persistence(tmp.resolve("state.json")), null, scope)
            val win = RowsWindow(text)
            shell.register(win)
            shell.start()
            settle(shell, "boot")
            // 100 % is the design, pixel for pixel
            assertEquals(Layout.ROW_H, shell.layout.rowH)
            assertEquals(Layout.LENS_H, shell.layout.lensH)

            assertTrue(shell.postSync("shell.settings", ShellSettings(fontScale = 1.3).toJson(), System.currentTimeMillis()))
            await("130 % applied") { shell.settings.fontScale == 1.3 }
            shell.postGesture(EvenHubMsg.EV_CLICK)                 // Main row 0 = the window
            await("the window opens") { shell.currentWindowId() == "rows" }
            repeat(2) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            settle(shell, "two notches down")

            val l = shell.layout
            val ink = win.ink(FontSpec(Face.SYSTEM, 18))
            assertTrue(ink > 27, "the fake's 18 px ink grows with the scale (was $ink)")
            assertTrue(l.rowH >= 5 + ink, "the row pitch holds the row face's ink: rowH=${l.rowH} ink=$ink")
            assertTrue(l.lensH >= 2 * ink + 10, "the lens holds two lines: lensH=${l.lensH} ink=$ink")
            assertTrue(l.rowH % 2 == 0 && l.lensH % 2 == 0, "on the damage grid")
            assertTrue(l.rowsAbove >= 1 && l.rowsBelow >= 1, "rows on both sides at 480 (${l.rowsAbove}/${l.rowsBelow})")

            // the row directly above the lens: its text block, painted at +5,
            // must be WHOLE right up to the lens — on the unfixed tree the
            // lens fill cut it at the old 32 px row boundary
            val g = shell.comp.composed
            val x = l.content.x + 40 + 2
            var y = l.lens.y - 1
            var lit = 0
            while (y >= l.content.y && g[x, y] != 0) { lit++; y-- }
            assertTrue(lit >= ink, "the row above the lens shows all its ink ($lit of $ink rows)")

            // the lens's two lines: both whole, inside the band, at most the
            // design's 2 px overlap between them
            val lx = l.content.x + 44 + 2
            val litLens = (l.lens.y + 2 until l.lens.bottom - 2).count { yy -> g[lx, yy] != 0 }
            assertTrue(litLens >= 2 * ink - 2, "two whole lens lines ($litLens rows lit, ink $ink)")
            // and nothing of the lens leaks into the first row below it
            assertEquals(0, g[lx, l.lens.bottom], "no lens ink past the band")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /** §29 #1, the geometry half: a grown rhythm stays grid-legal and inside
     *  the content area at every height mode. */
    @Test
    fun aGrownRhythmIsLegalAtEveryHeight() {
        for (h in ShellSettings.HEIGHTS) for ((rowH, lensH) in listOf(32 to 64, 38 to 74, 40 to 80, 46 to 86)) {
            val l = Layout(rowH = rowH, lensH = lensH).withHeightMode(h, wm.damage.core.geom.VPos.TOP)
            assertEquals(rowH, l.rowH); assertEquals(lensH, l.lensH)
            assertTrue(wm.damage.core.geom.Geometry.checkRect(l.lens, "lens").isEmpty(), "lens at $h: ${l.lens}")
            assertTrue(l.content.contains(l.lens), "lens inside the content at $h")
            val top = l.content.y + Layout.CONTENT_PAD + l.rowsAbove * rowH
            assertTrue(top <= l.lens.y, "rows above end at or before the lens at $h")
            assertTrue(l.lens.bottom + l.rowsBelow * rowH <= l.content.bottom - Layout.CONTENT_PAD,
                "rows below end inside the pad at $h")
        }
    }

    /** §29 #2 — the ladder's labels: 1.15 × 100 is 114.999… and toInt printed
     *  "114%" on the Global and per-app Font size rows. */
    @Test
    fun theFontSizeRowPrintsTheLadderStep() {
        assertEquals(listOf("85%", "100%", "115%", "130%"), ShellSettings.SCALES.map { ShellSettings.scaleLabel(it) })
    }

    /**
     * §29 #4 (the live walk) — the Brightness row's ladder had no way back to
     * auto: a notch left auto at the stored level and the manual ladder ended
     * at 0 %, so a brightness touched once on the glasses stayed manual for
     * good. One notch below 0 % is auto again.
     */
    @Test
    fun brightnessReturnsToAutoBelowZero(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("r29-bright")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
            shell.start()
            settle(shell, "boot")
            assertTrue(shell.settings.brightnessAuto, "auto by default")
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Main: the only row is Settings
            await("Settings opens") { shell.currentWindowId() == "settings" }
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Global
            settle(shell, "global")
            shell.postGesture(EvenHubMsg.EV_CLICK)          // row 0 = Brightness: adjust
            settle(shell, "adjust")
            shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)     // nothing sits below auto
            settle(shell, "below auto")
            assertTrue(shell.settings.brightnessAuto, "a notch down from auto stays auto")
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)  // a notch up leaves auto at the stored level
            settle(shell, "manual")
            assertTrue(!shell.settings.brightnessAuto, "a notch up leaves auto")
            val level = shell.settings.brightness
            assertTrue(level in 0..100)
            repeat(level / 5) { shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }   // down to the floor
            settle(shell, "to the floor")
            assertEquals(0, shell.settings.brightness, "the ladder floors at 0 %")
            assertTrue(!shell.settings.brightnessAuto, "0 % is still manual")
            shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)     // one below the floor
            settle(shell, "below the floor")
            assertTrue(shell.settings.brightnessAuto, "one notch below 0 % is auto again")
            shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)     // and it stays there — no flip-flop
            settle(shell, "still auto")
            assertTrue(shell.settings.brightnessAuto, "auto is the foot of the ladder")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * §29 #5 (the live walk) — the custom-amount keyboard's title said "raise
     * to" whatever the table held; over a checked-through flop the row it came
     * from says "Bet →" and the confirm it leads to says "Bet". The verb now
     * follows the bet on the table, as the sizing rows already did.
     */
    @Test
    fun theCustomAmountKeyboardNamesTheActionOnTheTable(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("r29-kb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
            val win = wm.damage.core.windows.games.GamesWindow(FakeText(), scope)
            win.roster.worldSeed = 20260905L
            shell.register(win)
            shell.start()
            settle(shell, "boot")
            shell.services.runOnShell { win.appSettings().first { it.name == "Bot pace" }.apply("instant") }
            shell.postGesture(EvenHubMsg.EV_CLICK)                  // Main row 0 = Games
            await("Games opens") { shell.currentWindowId() == "games" }
            shell.postGesture(EvenHubMsg.EV_CLICK)                  // Hold'em → tables
            await("tables") { win.levelName == "TABLES" }
            shell.postGesture(EvenHubMsg.EV_CLICK)                  // Regular → the buy-in confirm
            await("buy-in confirm") { shell.menuIsOpen }
            val sit = shell.menuLabels.indexOfFirst { it.startsWith("Sit down") }
            repeat((sit - shell.menuCursor).coerceAtLeast(0)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            shell.postGesture(EvenHubMsg.EV_CLICK)
            await("the table runs") { win.tableRunning }
            // walk decisions until one offers a BET (nobody has bet yet), and
            // there ask for the custom amount — checking through the others
            var titles = ArrayList<String>()
            var found = false
            for (decision in 0 until 12) {
                await("decision $decision") { win.isMyTurn || win.handIsComplete || !win.tableRunning }
                if (win.handIsComplete) { shell.postGesture(EvenHubMsg.EV_CLICK); settle(shell, "deal"); continue }
                if (!win.isMyTurn) break
                shell.postGesture(EvenHubMsg.EV_CLICK)              // the action level
                await("action menu") { shell.menuIsOpen }
                val sizing = shell.menuLabels.indexOfFirst { it.startsWith("Bet") || it.startsWith("Raise") }
                val betting = shell.menuLabels.any { it.startsWith("Bet") }
                repeat((sizing - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                shell.postGesture(EvenHubMsg.EV_CLICK)              // the sizing ladder
                await("sizing") { shell.menuIsOpen && shell.menuLabels.any { it == "Custom" } }
                val custom = shell.menuLabels.indexOf("Custom")
                repeat((custom - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                shell.postGesture(EvenHubMsg.EV_CLICK)
                await("the keyboard") { shell.keyboardIsOpen }
                val title = shell.keyboardTitle ?: ""
                titles.add(title)
                val want = if (betting) "bet " else "raise to "
                assertTrue(title.startsWith(want), "the keyboard names the action on the table: '$title' for a ${if (betting) "bet" else "raise"}")
                shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)       // ROW level: cancels (draft kept)
                settle(shell, "keyboard cancel")
                if (shell.keyboardIsOpen) {                         // it was at the KEY level: once more
                    shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                    settle(shell, "keyboard cancel 2")
                }
                assertTrue(!shell.keyboardIsOpen && win.levelName == "TABLE", "back on the table (${win.levelName})")
                if (betting) { found = true; break }
                // facing a bet: take the contextual row (fold) to move on
                shell.postGesture(EvenHubMsg.EV_CLICK)
                await("action menu again") { shell.menuIsOpen }
                repeat((0 - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                shell.postGesture(EvenHubMsg.EV_CLICK)              // row 0 → its confirm
                await("confirm") { shell.menuIsOpen && shell.menuLabels.firstOrNull() == "Cancel" }
                shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                shell.postGesture(EvenHubMsg.EV_CLICK)
                settle(shell, "acted")
            }
            assertTrue(found, "no decision with a bet available in 12 tries (titles seen: $titles)")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * §29 #6 (the live walk) — the shell loop caught Exceptions only. An Error
     * out of a handler (a NoClassDefFoundError, when the jar under a running
     * instance was rewritten in place) ended the loop: the display froze on
     * its last frame while the transport kept the lease renewed and the
     * status still read "running". The loop now survives an Error, loudly.
     */
    @Test
    fun theShellLoopSurvivesAnErrorInAHandler(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("r29-loop")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
            shell.register(RowsWindow(FakeText()))
            shell.start()
            settle(shell, "boot")
            shell.services.runOnShell { throw NoClassDefFoundError("a class the rewritten jar no longer holds") }
            // the loop is still there to take the next gesture
            shell.postGesture(EvenHubMsg.EV_CLICK)
            await("the loop still serves input after the Error") { shell.currentWindowId() == "rows" }
            settle(shell, "after the error")
            assertTrue(shell.statusLine.startsWith("ERROR"), "the status says it (was '${shell.statusLine}')")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * §29 #7 (the live walk) — the context menu under a bigger chrome face:
     * the box was a fixed 248 px, so at 120 % "Fold and leave" read
     * "Fold and ▸"; and a detail cut at its HEAD by the tail-keeping fit
     * ("nothing queued" → "othing queued") carried no mark at all. The box
     * follows the row face now, and a head cut gets the mark on that edge.
     */
    @Test
    fun theMenuBoxFollowsTheFaceAndMarksAHeadCut() {
        val base = ScaledFake()
        fun paintAt(scale: Double, detail: String): Triple<wm.damage.core.shell.MenuSurface, Gray8, Rect> {
            val text = wm.damage.core.text.StyledText(base) { wm.damage.core.text.StyleTransform(scale = scale).apply(it) }
            val m = wm.damage.core.shell.MenuSurface(text)
            m.openWith(wm.damage.core.shell.MenuSurface.Spec("music",
                listOf(wm.damage.core.shell.MenuSurface.Item("Resume", detail), wm.damage.core.shell.MenuSurface.Item("Next")),
                onCommit = {}))
            val g = Gray8(640, 480)
            val box = m.paint(g, Layout())!!
            return Triple(m, g, box)
        }
        val (_, _, b100) = paintAt(1.0, "ok")
        assertEquals(wm.damage.core.shell.MenuSurface.W, b100.w, "the design's width at 100 %")
        val (_, _, b120) = paintAt(1.2, "ok")
        assertTrue(b120.w > b100.w && b120.w % 4 == 0 && b120.w <= 608 - 16, "the box grows with the face (${b120.w})")
        // a detail wider than half the box is cut at its head: the mark sits
        // just left of what remains; a short detail has nothing there
        val text = wm.damage.core.text.StyledText(base) { it }
        val fDetail = FontSpec(Face.SYSTEM, 13)
        fun markInk(g: Gray8, box: Rect, shown: String): Int {
            val x = box.right - 8 - text.measure(shown, fDetail) - 4
            var ink = 0
            // the row band only: the box's rules span its whole width
            for (y in (box.y + 22) until (box.bottom - 2)) for (xx in (x - 6)..x) if (g[xx, y] != 0) ink++
            return ink
        }
        val long = "a detail far wider than the half box it is capped to"
        val (_, gCut, bCut) = paintAt(1.0, long)
        // what the tail-keeping fit leaves: the widest suffix within half the box
        val max = bCut.w / 2 - 12
        var shown = long
        while (text.measure(shown, fDetail) > max) shown = shown.substring(1)
        assertTrue(shown.length < long.length, "the detail was cut")
        assertTrue(markInk(gCut, bCut, shown) > 0, "a head-cut detail carries the mark on its cut edge")
        val (_, gOk, bOk) = paintAt(1.0, "ok")
        assertEquals(0, markInk(gOk, bOk, "ok"), "an uncut detail carries no mark")
    }

    /** A transport whose LEFT arm refuses the first image write — the warmup
     *  — after the lease was acquired, and whose disconnect marks the link
     *  down BEFORE it yields, so a write that races it is a write to a dead
     *  link. */
    private class WarmupRefusingTransport(val glass: GlassFirmwareSim, scope: CoroutineScope) :
        CfwTransportBase(scope, "refuse") {
        override val instant: Boolean get() = true
        @Volatile var refused = false
        @Volatile var linkDown = false
        /** (arm, written-while-link-up) for every write after the refusal. */
        val after = CopyOnWriteArrayList<Pair<Arm, Boolean>>()

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() {
            linkDown = true          // before the first suspension, like a real link teardown
            delay(50)
        }
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            if (refused) {
                after.add(arm to !linkDown)
                if (linkDown) throw IllegalStateException("$arm write on a torn-down link")
            } else if (arm == Arm.LEFT && (packet[6].toInt() and 0xFF) == EvenHubMsg.SID) {
                // the first EvenHub-sid write to LEFT is the warmup keyframe:
                // the prelude and the CREATE ride RIGHT, the lease rides the
                // settings sid (a black keyframe packs into one small packet,
                // so a size test would never see it)
                refused = true
                throw IllegalStateException("warmup refused")
            }
            glass.write(arm, packet, nowMs())
        }
    }

    /**
     * §29 #3 — a failed start releases the lease BEFORE tearing the link
     * down, as stop() does. The rollback used to enqueue the release and
     * disconnect at once: the write raced the teardown, so it either never
     * reached the wire or hit a dead link and logged a "control lane error"
     * fault for a write nobody expected to work.
     */
    @Test
    fun aFailedStartReleasesTheLeaseBeforeDisconnecting(): Unit = runBlocking {
        val exec = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + exec.asCoroutineDispatcher())
        try {
            val t = WarmupRefusingTransport(GlassFirmwareSim(), scope)
            val faults = CopyOnWriteArrayList<String>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.Fault) faults.add("${it.what}: ${it.detail}") }
            }
            val warmup = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))
            val err = runCatching { t.start(warmup) }.exceptionOrNull()
            assertNotNull(err, "the refused warmup fails start()")
            assertTrue(t.refused, "the warmup write was the one refused")
            delay(300)      // anything still racing the teardown would land here
            val released = t.after.filter { it.second }.map { it.first }.toSet()
            assertEquals(setOf(Arm.LEFT, Arm.RIGHT), released,
                "the lease release reached BOTH arms while the link was up (writes after the refusal: ${t.after})")
            assertTrue(faults.none { it.startsWith("control") }, "no control-lane fault for a write to a dead link: $faults")
            assertTrue(!t.state.value.started && !t.state.value.leaseHeld)
        } finally {
            scope.cancel()
            exec.shutdownNow()
        }
    }
}
