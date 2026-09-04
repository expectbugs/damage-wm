package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.games.GamesWindow
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.wire.EvenHubMsg

/**
 * The 2026-09-05 whole-codebase review — one pin per verified finding, each
 * failing without its fix (`Review20260903Test` is the precedent).
 */
class Review20260905Test {

    private class Rig(tmp: Path) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val win = GamesWindow(FakeText(), scope)

        init { shell.register(win) }

        suspend fun start() {
            shell.start()
            shell.postGesture(EvenHubMsg.EV_CLICK)      // Main row 0 = Games
            settle()
        }

        suspend fun stop() { shell.stop(); scope.cancel() }

        suspend fun settle() {
            val t0 = System.currentTimeMillis()
            while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 20_000) delay(10)
            assertTrue(shell.isQuiescent(), "the shell did not settle")
        }

        suspend fun await(what: String, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < 20_000) delay(20)
            assertTrue(cond(), "did not converge: $what (title=${win.title()}, menu=${shell.menuLabels})")
        }

        suspend fun tap() { shell.postGesture(EvenHubMsg.EV_CLICK); settle() }
        suspend fun back() { shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK); settle() }
        suspend fun down() { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); settle() }

        /** The open menu's row BY NAME (the 2026-09-03 rule). */
        suspend fun menuPick(label: String) {
            assertTrue(shell.menuIsOpen, "no menu is open (wanted '$label')")
            val i = shell.menuLabels.indexOfFirst { it == label || it.startsWith(label) }
            assertTrue(i >= 0, "menu row '$label' not in ${shell.menuLabels}")
            repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { down() }
            tap()
        }

        suspend fun sitDown() {
            await("the games root") { win.title() == "games" }
            tap()
            await("the table list") { win.title() == "hold'em" }
            tap()
            await("the buy-in confirm") { shell.menuIsOpen }
            menuPick("Sit down")
            await("the table") { win.tableRunning }
        }
    }

    /**
     * #1 — A CASH-OUT IS NOT A TOTAL LOSS.
     *
     * `finishTournament` recorded Adam's lifetime net as `prize − myStake`.
     * A cash-out (§10.2, verdict 11) had already moved his chips into the
     * bankroll and `winner != seat`, so the whole buy-in was booked as lost:
     * leaving a table with your stack read on the character page exactly like
     * busting out with nothing. The bots' own net has always been
     * `won − (stake + fee)`; Adam's now matches it, cashed-out chips and the
     * visible fee included.
     */
    @Test
    fun cashingOutCreditsTheChipsItTookOffTheTable(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-review-cashout")
        val r = Rig(tmp)
        try {
            r.start()
            val cashBefore = r.win.bankroll.cash
            r.sitDown()
            val entry = HoldemRules.Table.REGULAR.entry!!
            val fee = HoldemRules.fee(entry)
            assertEquals(cashBefore - entry - fee, r.win.bankroll.cash, "the door cost left the bankroll")

            r.await("your turn") { r.win.isMyTurn }
            r.tap()                                        // the action level
            r.await("the action menu") { r.shell.menuIsOpen }
            r.menuPick("Cash out")
            r.await("the cash-out confirm") { r.shell.menuIsOpen }
            r.menuPick(r.shell.menuLabels.first { it != "Cancel" })
            r.await("the hand settles after the fold") { r.win.handIsComplete }
            val chips = r.win.myStack
            assertTrue(chips > 0, "a fold keeps the rest of the stack, got $chips")
            r.tap()                                        // deal → the cash-out fires
            r.await("the table is handed over") { !r.win.tableRunning }

            val cashAfter = r.win.bankroll.cash
            assertEquals(cashBefore - entry - fee + chips, cashAfter,
                "the cashed-out chips reached the bankroll")
            val net = r.win.roster.get("you")!!.career.lifetimeNet
            assertEquals(chips - entry - fee, net,
                "lifetime net must be what came back minus what went in at the door " +
                    "(chips $chips, entry $entry, fee $fee) — a cash-out booked as a total loss " +
                    "is the defect this pins")
            assertTrue(net > -entry, "leaving with chips cannot read worse than busting out")
        } finally {
            r.stop()
            tmp.toFile().deleteRecursively()
        }
    }
}
