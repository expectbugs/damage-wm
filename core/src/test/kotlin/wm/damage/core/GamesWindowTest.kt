package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
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
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.games.GamesWindow
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.wire.EvenHubMsg

/**
 * The GAMES window through the real shell (`HOLDEM.md` M4/M6): the grammar,
 * the four heights, the confirm policy, persistence and the §3 activation
 * rule. The engine's own correctness is `HoldemEngineTest`; this is the
 * window's.
 */
class GamesWindowTest {

    private class Rig(tmp: Path, val height: Int = 480) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val win = GamesWindow(FakeText(), scope)

        init {
            if (height != 480) store.put("shell.settings", ShellSettings(heightMode = height).toJson())
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
            assertTrue(cond(), "did not converge: $what (title=${win.title()})")
        }

        suspend fun tap() { shell.postGesture(EvenHubMsg.EV_CLICK); settle() }
        suspend fun back() { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); settle() }
        suspend fun down() { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); settle() }
        suspend fun up() { shell.postGesture(EvenHubMsg.EV_SCROLL_TOP); settle() }

        /** Pick the open menu's row by NAME — never by counting notches, so a
         *  new row cannot silently change what a test selects. */
        suspend fun menuPick(label: String) {
            assertTrue(shell.menuIsOpen, "no menu is open (wanted '$label')")
            val i = shell.menuLabels.indexOfFirst { it == label || it.startsWith(label) }
            assertTrue(i >= 0, "menu row '$label' not in ${shell.menuLabels}")
            repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { down() }
            tap()
        }

        /** Sit down at [spec] and settle onto the table. */
        suspend fun sitDown(spec: HoldemRules.Table = HoldemRules.Table.REGULAR) {
            await("the games root") { win.title() == "games" }
            tap()                                     // Hold'em -> the table select
            await("the table list") { win.title() == "hold'em" }
            repeat(spec.ordinal) { down() }
            tap()
            await("the buy-in confirm") { shell.menuIsOpen }
            menuPick("Sit down")
            await("the table") { win.levelDepth() == 2 && win.title().startsWith("hold'em ·") }
        }
    }

    // ================================================================ live
    /** The 2026-09-04 live session: coming in from Main presents the root
     *  LIST — from its first row, not from wherever the cursor was left. */
    @Test
    fun comingFromMainLandsOnTheFirstRootRow(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-root")
        val r = Rig(tmp)
        try {
            r.start()
            r.down(); r.down()                        // Bankroll
            r.tap()
            r.await("the bankroll level") { r.win.levelDepth() == 2 }
            r.back(); r.back()                        // out to Main
            r.await("main") { r.win.levelDepth() == 1 }
            r.tap()                                   // back into Games from Main
            r.await("the games root") { r.win.title() == "games" }
            assertEquals("Hold'em", r.win.rootRow,
                "activation from Main presents the root list from its first row")
        } finally {
            r.stop()
        }
    }

    /** The 2026-09-04 live session: the Hand-history document draws one small
     *  line per entry, so the taller header needs a spacer under it or the
     *  first entry crowds its descenders. */
    @Test
    fun theHandHistoryHeaderKeepsItsSpacer(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-hist")
        val r = Rig(tmp)
        try {
            r.start()
            r.sitDown()
            val lines = r.win.historyDocLines
            assertTrue(lines.size >= 2, "the history document is a header plus a body")
            assertTrue(lines[0].startsWith("hand 1"), "line 0 is the header, got '${lines[0]}'")
            assertEquals("", lines[1], "line 1 is the spacer under the header")
            assertTrue(lines.drop(2).none { it.isEmpty() },
                "only the header carries a spacer: $lines")
        } finally {
            r.stop()
        }
    }

    // ================================================================ grammar
    @Test
    fun theRootListSittingDownAndTheTableGrammar(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-grammar")
        val r = Rig(tmp)
        try {
            r.start()
            assertEquals("games", r.win.title())
            assertEquals(1_000, r.win.bankroll.cash, "the base bankroll is \$1,000 (verdict 13)")
            r.sitDown()
            // the entry AND its visible fee left the bankroll (verdict 24)
            assertEquals(1_000 - 200 - 10, r.win.bankroll.cash)

            // a canvas, and double-tap backs OUT of it without cashing out
            assertTrue(r.win.view() is WindowView.CanvasView)
            r.back()
            assertEquals("games", r.win.title())
            assertTrue(r.win.summary().line.startsWith("Hold'em"),
                "the table is still running: ${r.win.summary().line}")
            // and back down lands on the table again, not the chooser
            r.tap()
            await(r, "back at the table") { r.win.levelDepth() == 2 && r.win.title().startsWith("hold'em ·") }
            r.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun everyActionConfirmsAndTheCursorRestsOnCancel(): Unit = runBlocking {
        // 🔴 verdicts 32 and 33: EVERY action confirms, cursor on Cancel, and
        // the contextual Check/Fold row is NOT exempt
        val tmp = Files.createTempDirectory("damage-games-confirm")
        val r = Rig(tmp)
        try {
            r.start()
            r.sitDown()
            await(r, "your turn") { r.win.isMyTurn }
            r.settle()
            r.tap()                                    // open the action level
            await(r, "the action menu") { r.shell.menuIsOpen }
            val rows = r.shell.menuLabels
            assertTrue(rows[0] == "Check" || rows[0] == "Fold",
                "row 0 must be the contextual give-up row, got $rows")
            assertEquals(0, r.shell.menuCursor, "the menu opens on row 0")
            // the wrap-end window rows, ordered so one notch UP from rest is
            // harmless — Cash out is never last
            assertEquals("Hand history", rows.last(), "one notch up from rest must be harmless: $rows")
            assertTrue(rows.contains("Cash out"))
            r.tap()                                    // commit row 0
            await(r, "the confirm") { r.shell.menuIsOpen }
            assertEquals("Cancel", r.shell.menuLabels[0], "the cursor must rest on Cancel")
            assertEquals(0, r.shell.menuCursor)
            assertTrue(r.shell.menuLabels[1].startsWith("Confirm · "),
                "the confirm names the exact action: ${r.shell.menuLabels}")
            // cancelling does nothing at all
            val handBefore = r.win.summary().detail
            r.back()
            await(r, "the menu closed") { !r.shell.menuIsOpen }
            assertEquals(handBefore, r.win.summary().detail, "cancel must not act")
            r.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ================================================================ heights
    @Test
    fun theTablePaintsInsideItsBandsAtAllFourHeights(): Unit = runBlocking {
        for (h in ShellSettings.HEIGHTS) {
            val tmp = Files.createTempDirectory("damage-games-h$h")
            val r = Rig(tmp, height = h)
            try {
                r.start()
                r.sitDown()
                val layout = Layout(Rect(0, 0, 640, h))
                val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
                (r.win.view() as WindowView.CanvasView).paint(g, layout.content)
                // nothing outside the content area
                for (y in 0 until g.h) for (x in 0 until g.w) {
                    if (x in layout.content.x until layout.content.right &&
                        y in layout.content.y until layout.content.bottom) continue
                    assertEquals(0, g[x, y], "h=$h: ink at ($x,$y) escaped the content area")
                }
                // and something actually drew
                val lit = g.pix.count { it.toInt() != 0 }
                assertTrue(lit > 500, "h=$h drew almost nothing ($lit px)")
                val ink = lit.toDouble() / (layout.content.w * layout.content.h)
                println("games table ink at h=$h: ${"%.1f".format(ink * 100)}%")
                // §9.2's design target is ≈8 % at 288 and ≈10 % at 480; the
                // canvas budget is the window's own call but a table at three
                // times the target is a defect, not a style
                assertTrue(ink < 0.30, "h=$h lights ${"%.1f".format(ink * 100)}% of the content")
                // the depth region is legal and inside the content (§9.2)
                for ((rect, plane) in r.win.contentPlanes(layout.content)) {
                    assertEquals(emptyList(), Geometry.checkRect(rect, "hole plane"))
                    assertTrue(layout.content.contains(rect), "the hole plane $rect escapes ${layout.content}")
                    assertEquals(0, plane, "the hole cards come forward to plane 0")
                }
                r.stop()
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
    }

    // ================================================================ persistence
    @Test
    fun theWholeWorldSurvivesAShellRestart(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-persist")
        val r = Rig(tmp)
        try {
            r.start()
            r.sitDown()
            await(r, "your turn") { r.win.isMyTurn }
            r.settle()
            val cashBefore = r.win.bankroll.cash
            val summaryBefore = r.win.summary().line
            val charCount = r.win.roster.characters.size
            val worldSeed = r.win.roster.worldSeed
            r.shell.stop()
            r.scope.cancel()

            // a fresh shell over the same store
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store2 = Persistence(tmp.resolve("state.json"))
            val t2 = SimTransport(GlassFirmwareSim(), scope2, SimTransport.Timing(instant = true))
            val shell2 = Shell(FakeText(), t2, store2, null, scope2)
            val win2 = GamesWindow(FakeText(), scope2)
            shell2.register(win2)
            shell2.start()
            val t0 = System.currentTimeMillis()
            while (!shell2.isQuiescent() && System.currentTimeMillis() - t0 < 20_000) delay(10)
            assertEquals(cashBefore, win2.bankroll.cash, "the bankroll did not survive")
            assertEquals(worldSeed, win2.roster.worldSeed, "the world seed did not survive")
            assertEquals(charCount, win2.roster.characters.size, "the roster did not survive")
            assertEquals(summaryBefore, win2.summary().line, "the live table did not survive")
            assertEquals(2, win2.levelDepth(), "the restored level is the table")
            shell2.stop()
            scope2.cancel()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun mainEntryShowsTheGamesListAndKeepsTheLiveTable(): Unit = runBlocking {
        // Adam's general rule (HOLDEM.md §3, verdict 35), for the window it
        // was written for
        val tmp = Files.createTempDirectory("damage-games-activation")
        val r = Rig(tmp)
        try {
            r.start()
            r.sitDown()
            assertEquals(2, r.win.levelDepth())

            // the switcher RESUMES the live table
            r.win.onDeactivate()
            r.win.onActivate(ProbeServices, ActivationSource.SWITCHER)
            r.settle()
            assertEquals(2, r.win.levelDepth(), "the switcher must resume the table")

            // MAIN lands on the Games list, and the tournament is untouched
            r.win.onDeactivate()
            r.win.onActivate(ProbeServices, ActivationSource.MAIN)
            r.settle()
            assertEquals("games", r.win.title(), "Main entry must show the Games list")
            assertEquals(1, r.win.levelDepth())
            assertTrue(r.win.summary().line.startsWith("Hold'em"),
                "the running tournament must survive a Main entry: ${r.win.summary().line}")
            r.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ================================================================ economy
    @Test
    fun theEntryFeeLeavesTheBankrollAndTheRefillCostsALoserCount(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-money")
        val r = Rig(tmp)
        try {
            r.start()
            assertEquals(1_000, r.win.bankroll.cash)
            // 🔴 a recorded consequence of verdicts 13, 16 and 24 together: the
            // base bankroll is $1,000 and Big Boy's entry is $1,000 PLUS its
            // visible $50 fee, so a fresh bankroll cannot buy into Big Boy. You
            // have to win at Regular first. That is the fee doing its job, not
            // an off-by-one — recorded here so a later change has to argue
            // with a test rather than with a memory.
            await(r, "the games root") { r.win.title() == "games" }
            r.tap()
            await(r, "the table list") { r.win.title() == "hold'em" }
            r.down()
            r.tap()
            r.settle()
            assertFalse(r.shell.menuIsOpen, "Big Boy must refuse a fresh \$1,000 bankroll")
            assertTrue(r.win.title().contains("more than you have"), r.win.title())

            r.up()
            r.tap()
            await(r, "the Regular confirm") { r.shell.menuIsOpen }
            r.menuPick("Sit down")
            await(r, "the table") { r.win.tableRunning }
            assertEquals(1_000 - 210, r.win.bankroll.cash)
            assertEquals(10, r.win.bankroll.feesPaid)
            r.win.bankroll.refill()
            assertEquals(1_000, r.win.bankroll.cash)
            assertEquals(1, r.win.bankroll.loserCount, "a refill costs one Loser Count (verdict 14)")
            r.stop()
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private object ProbeServices : wm.damage.core.shell.ShellServices {
        override fun requestRender(window: wm.damage.core.shell.DamageWindow) {}
        override fun setOperation(op: String) {}
        override fun notifyInternal(source: String, body: String, urgent: Boolean,
            appId: String?, thread: String, target: String?) {}
        override fun openWindow(id: String, target: String?): Boolean = false
        override fun runOnShell(action: () -> Unit) = action()
        override fun docContentWidth(): Int = 560
        override fun docContentHeight(): Int = 384
    }

    private suspend fun await(r: Rig, what: String, cond: () -> Boolean) = r.await(what, cond = cond)
}
