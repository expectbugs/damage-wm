package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.shell.ActivationSource
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.games.GamesWindow
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.holdem.HoldemTable
import wm.damage.core.windows.games.kit.ActionLevel
import wm.damage.core.windows.games.kit.Money
import wm.damage.core.windows.games.kit.Rng
import wm.damage.core.windows.games.kit.Seats
import wm.damage.core.windows.games.roster.Character
import wm.damage.core.windows.games.roster.Roster
import wm.damage.core.wire.EvenHubMsg

/**
 * The 2026-09-04 review of the Games build. Each test reproduces a defect that
 * was found by reading or by `--games-check`, and fails without its fix — the
 * project's standing discipline (`Review20260903Test` is the precedent).
 */
class GamesReview20260904Test {

    private object Svc : ShellServices {
        override fun requestRender(window: DamageWindow) {}
        override fun setOperation(op: String) {}
        override fun notifyInternal(source: String, body: String, urgent: Boolean,
            appId: String?, thread: String, target: String?) {}
        override fun openWindow(id: String, target: String?): Boolean = false
        override fun runOnShell(action: () -> Unit) = action()
        override fun docContentWidth(): Int = 560
        override fun docContentHeight(): Int = 384
    }

    // ================================================================ #1
    @Test
    fun registrationDoesNotMintAWorldBeforeTheRestoreLands() {
        // `onRegistered` runs BEFORE any sub-record arrives. Populating there
        // minted 35 characters against a fresh clock seed; the restore then
        // overwrote the ids it had and left the rest behind as strangers with
        // full bankrolls — free money on every start.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val w = GamesWindow(FakeText(), scope)
            w.onRegistered(Svc)
            assertEquals(0, w.roster.characters.size,
                "registration must not populate the roster — the restore has not run yet")
            assertEquals(0L, w.roster.worldSeed, "and must not mint a world seed")
            // the restore lands, THEN activation seeds whatever is still missing
            w.restoreSubState("world", Roster(worldSeed = 4242, gameNo = 7, born = 3).toJson())
            w.restoreSubState("char.c0", Roster.birth(4242, 0).toJson())
            w.onActivate(Svc, ActivationSource.RESTORE)
            assertEquals(4242L, w.roster.worldSeed, "the restored world seed must survive activation")
            assertTrue(w.roster.get("c0") != null)
        } finally {
            scope.cancel()
        }
    }

    // ================================================================ #2
    @Test
    fun cashingOutMovesTheButtonOffTheEmptySeat() {
        // heads-up the BUTTON posts the small blind; leaving it on a seat that
        // has left posts a blind for a player who is not in the hand
        val who = (0 until 3).map { Seats.Occupant("p$it", "P$it", human = it == 0) }
        val t = HoldemTable.start(HoldemRules.Table.REGULAR, 11, who, intArrayOf(300, 300, 300),
            button = 0)
        // finish a hand so cashing out is legal
        var guard = 0
        val rng = Rng.stream(1, 1)
        while (t.view().toAct != null && guard++ < 200) {
            val acts = t.legalActions()
            val pick = acts[rng.nextInt(acts.size)]
            if (pick.kind == ActionLevel.Kind.BET || pick.kind == ActionLevel.Kind.RAISE)
                t.act(pick.kind, t.minRaiseTo()) else t.act(pick.kind)
        }
        // the hand is SETTLED and waiting for a tap; cashing out rolls it
        // forward itself
        val btn = t.button
        t.cashOut(btn)
        assertNotEquals(btn, t.button, "the button cannot stay on a seat that has left")
        assertTrue(t.inPlay(t.button), "and it must land on a seat still in the tournament")
        val v = t.view()
        assertTrue(v.seats[btn].committed == 0, "the seat that left posts nothing")
        assertTrue(v.sbSeat != btn && v.bbSeat != btn)
    }

    // ================================================================ #3
    @Test
    fun theBackgroundEconomyNeverSeatsSomebodyAlreadyAtAdamsTable() {
        // seating one character twice debits a second buy-in from a bankroll
        // that is already committed, and puts two threads on one character
        val r = Roster(worldSeed = 99)
        r.ensurePopulation()
        val busy = r.available().take(4).map { it.id }.toSet()
        val seated = r.seat(HoldemRules.Table.REGULAR, 6, key = 1, exclude = busy)
        assertTrue(seated.isNotEmpty())
        assertTrue(seated.none { it.who.id in busy },
            "a character at Adam's table was seated again in the background")
    }

    // ================================================================ #4
    @Test
    fun unlimitedIsOpenToAnyoneWhoCanCoverTheCheapestSeat() {
        // `canAfford` asked for `bankroll >= bankroll + fee`, which is never
        // true: 400 background tournaments ran without one Unlimited game
        val r = Roster(worldSeed = 5)
        val c = Character("x", "X", Character.Traits.load(null), 1_000, 3)
        c.bankroll = HoldemRules.cheapestSeat()
        assertTrue(r.canAfford(c, HoldemRules.Table.UNLIMITED),
            "Unlimited takes any entry — that is the whole point of the table")
        assertFalse(r.canAfford(c, HoldemRules.Table.REGULAR), "Regular still wants its \$210")
        c.bankroll = HoldemRules.cheapestSeat() - 1
        assertFalse(r.canAfford(c, HoldemRules.Table.UNLIMITED))
    }

    // ================================================================ #5
    @Test
    fun unlimitedStakesAreAnAbsoluteRangeNotAFractionOfTheRoll() {
        // verdict 16: "$1,000–$10,000 in $1,000 steps". Reading it as a
        // fraction made rich characters risk half their bankroll, bust
        // constantly, and refill a whole General Wealth each time — the money
        // supply compounded +236 % over 400 tournaments.
        val r = Roster(worldSeed = 7)
        val c = Character("rich", "Rich", Character.Traits.load(null), 5_000, 5)
        for (bank in listOf(1_200, 12_000, 250_000, 4_000_000)) {
            c.bankroll = bank
            for (k in 0 until 40) {
                val stake = r.stakeFor(c, HoldemRules.Table.UNLIMITED, Rng.stream(3, k.toLong()))
                assertTrue(stake in 1_000..10_000,
                    "a \$${bank} roll staked \$${stake} — the range is \$1,000–\$10,000")
                assertEquals(0, stake % 1_000, "and it moves in \$1,000 steps")
                assertTrue(stake + HoldemRules.fee(stake) <= bank, "the fee has to fit too")
            }
        }
        // and a roll under $1,000 brings whatever it has (§5.2's short stack)
        c.bankroll = 640
        val small = r.stakeFor(c, HoldemRules.Table.UNLIMITED, Rng.stream(3, 1))
        assertTrue(small in 1 until 1_000)
        assertTrue(small + HoldemRules.fee(small) <= 640)
    }

    // ================================================================ #6
    @Test
    fun theSeatSortDrawsItsKeyOnceSoTheComparatorIsConsistent() {
        // calling ambition() inside the comparator draws a fresh random number
        // on every comparison: undefined order at best, and TimSort refuses
        // some inputs outright with "comparison method violates its contract"
        val r = Roster(worldSeed = 31337)
        r.ensurePopulation()
        repeat(60) { k ->
            val a = r.seat(HoldemRules.Table.REGULAR, 6, key = k.toLong())
            // put the stakes back so the next draw sees the same room
            for (s in a) s.who.bankroll += s.stake + s.fee
            assertTrue(a.size <= 6)
            assertEquals(a.map { it.who.id }.toSet().size, a.size, "a character was seated twice")
        }
    }

    // ================================================================ #7
    @Test
    fun aFinishedTournamentHasNoNextHandRatherThanAnException() {
        val who = (0 until 2).map { Seats.Occupant("p$it", "P$it", human = false) }
        val t = HoldemTable.start(HoldemRules.Table.REGULAR, 3, who, intArrayOf(20, 200))
        var guard = 0
        val rng = Rng.stream(2, 2)
        while (guard++ < 3_000) {
            val v = t.view()
            if (v.toAct == null) {
                if (!t.nextHand()) break
                continue
            }
            val acts = t.legalActions()
            val pick = acts[rng.nextInt(acts.size)]
            if (pick.kind == ActionLevel.Kind.BET || pick.kind == ActionLevel.Kind.RAISE)
                t.act(pick.kind, t.maxRaiseTo()) else t.act(pick.kind)
        }
        assertEquals(1, t.activeSeats().size)
        // the terminal call must be an answer, not a throw
        assertFalse(t.nextHand())
        assertFalse(t.nextHand())
        assertEquals(220, t.view().seats.sumOf { it.stack }, "the winner holds every chip")
    }

    // ================================================================ #8
    @Test
    fun everyCharacterInTheRoomHasADistinctName() {
        // 50 names against 19 initials is 950 combinations, and 52 draws from
        // 950 collide about three times in four — two "Steve K."s in the
        // standings is a bug in a cast that is meant to be memorable
        for (seed in listOf(1L, 2L, 12345L, -7L)) {
            val r = Roster(worldSeed = seed)
            r.ensurePopulation(Roster.TARGET * 3 / 2)
            val names = r.characters.map { it.name }
            assertEquals(names.size, names.toSet().size,
                "duplicate names in world $seed: " +
                    names.groupingBy { it }.eachCount().filterValues { it > 1 })
        }
    }

    // ================================================================ #9
    @Test
    fun theScoreboardWidthMatchesWhatItDraws() {
        // the lens centres the scoreboard on this number; measuring it by
        // rendering into a throwaway 1x1 surface was both wrong in principle
        // and an allocation on every frame
        val tx = FakeText()
        for (s in listOf(Money.Seg.SMALL, Money.Seg.MEDIUM, Money.Seg.LARGE)) {
            for ((cash, won, lost) in listOf(Triple(0, 0, 0), Triple(1_847, 12, 3),
                Triple(1_234_567, 108, 41))) {
                val g = wm.damage.core.gfx.Gray8(1200, 120)
                val drawn = Money.scoreboard(g, tx, 0, 0, s, cash, won, lost, captions = false)
                assertEquals(drawn, Money.scoreboardWidth(tx, s, cash, won, lost),
                    "the measured width and the drawn width disagree at ${s.w}x${s.h}")
                // and nothing spilled past it
                for (y in 0 until g.h) for (x in drawn + 8 until g.w) {
                    assertEquals(0, g[x, y], "the scoreboard drew past its own width at ($x,$y)")
                }
            }
        }
    }

    // ================================================================ #10
    @Test
    fun theFeeIsOnlyChargedOnceASeatIsReal(): Unit = runBlocking {
        // an empty room refunded the entry but had already booked the fee
        val tmp = Files.createTempDirectory("damage-games-fee")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("s.json")), null, scope)
            val w = GamesWindow(FakeText(), scope)
            shell.register(w)
            shell.start()
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Main → Games
            settle(shell)
            // empty the room: nobody can afford a seat
            for (c in w.roster.characters) c.bankroll = 0
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Hold'em → the table list
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Regular
            settle(shell)
            val i = shell.menuLabels.indexOfFirst { it.startsWith("Sit down") }
            assertTrue(i >= 0, "the confirm did not open: ${shell.menuLabels}")
            repeat(i) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); settle(shell) }
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
            assertFalse(w.tableRunning, "nobody could sit down")
            assertEquals(1_000, w.bankroll.cash, "the entry came back")
            assertEquals(0, w.bankroll.feesPaid, "and the fee was never charged")
            assertEquals(0, w.bankroll.tournamentsPlayed)
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ================================================================ #11
    @Test
    fun adamsStandingsWorthIncludesTheChipsOnTheTable(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-games-worth")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("s.json")), null, scope)
            val w = GamesWindow(FakeText(), scope)
            shell.register(w)
            shell.start()
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)          // → the table list
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Regular
            settle(shell)
            val i = shell.menuLabels.indexOfFirst { it.startsWith("Sit down") }
            repeat(i) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); settle(shell) }
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
            val t0 = System.currentTimeMillis()
            while (!w.isMyTurn && System.currentTimeMillis() - t0 < 20_000) delay(20)
            settle(shell)
            // verdict 25: he is a roster character, so the standings must show
            // the money he actually has — bankroll plus what is on the table,
            // DERIVED, because his stack moves every hand
            assertEquals(w.bankroll.cash + w.myStack, w.myWorth,
                "Adam's standings worth ignored the chips in front of him")
            assertTrue(w.myStack > 0)
            assertTrue(w.myWorth > w.bankroll.cash)
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ================================================================ #12
    @Test
    fun anUncontestedPotDoesNotRunTheBoardOut() {
        // at a real table the dealer stops the moment everyone folds; showing
        // the river of a pot nobody contested invents a card that was never
        // dealt (the first showdown render, 2026-09-04)
        val who = (0 until 3).map { Seats.Occupant("p$it", "P$it", human = false) }
        val t = HoldemTable.start(HoldemRules.Table.REGULAR, 21, who, intArrayOf(300, 300, 300))
        // preflop: everyone folds to the big blind
        t.act(ActionLevel.Kind.FOLD)
        t.act(ActionLevel.Kind.FOLD)
        val v = t.view()
        assertTrue(v.result != null, "the hand is over")
        assertEquals(0, v.board.size, "a pot won preflop has no board at all")
        assertTrue(v.result!!.shown.isEmpty(), "and nobody has to show")

        // and one that ends on the FLOP keeps three
        val t2 = HoldemTable.start(HoldemRules.Table.REGULAR, 22, who, intArrayOf(300, 300, 300))
        t2.act(ActionLevel.Kind.CALL)          // button
        t2.act(ActionLevel.Kind.CALL)          // small blind
        t2.act(ActionLevel.Kind.CHECK)         // big blind's option
        assertEquals(3, t2.view().board.size)
        t2.act(ActionLevel.Kind.BET, t2.minRaiseTo())
        t2.act(ActionLevel.Kind.FOLD)
        t2.act(ActionLevel.Kind.FOLD)
        val v2 = t2.view()
        assertTrue(v2.result != null)
        assertEquals(3, v2.board.size, "a pot won on the flop stops at the flop")

        // a real showdown DOES see all five
        val t3 = HoldemTable.start(HoldemRules.Table.REGULAR, 23, who, intArrayOf(300, 300, 300))
        var guard = 0
        while (t3.view().toAct != null && guard++ < 40) t3.act(
            if (t3.legalActions().any { it.kind == ActionLevel.Kind.CHECK })
                ActionLevel.Kind.CHECK else ActionLevel.Kind.CALL)
        val v3 = t3.view()
        assertEquals(5, v3.board.size, "a contested pot runs the board out")
        assertTrue(v3.result!!.shown.size >= 2, "and the contenders show")
    }

    // ================================================================ #13
    @Test
    fun aRottedTableRecordIsRefusedRatherThanUsed() {
        val who = (0 until 3).map { Seats.Occupant("p$it", "P$it", human = false) }
        val good = HoldemTable.start(HoldemRules.Table.REGULAR, 31, who, intArrayOf(100, 100, 100))
        fun mangle(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
            kotlinx.serialization.json.buildJsonObject {
                for ((k, v) in good.toJson()) put(k, if (k == key) value else v)
            }
        val negStacks = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(-5))
            add(kotlinx.serialization.json.JsonPrimitive(100))
            add(kotlinx.serialization.json.JsonPrimitive(100))
        }
        assertTrue(HoldemTable.load(mangle("stacks", negStacks)) == null,
            "a negative stack must be refused, not carried into the arithmetic")
        val dupOrder = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(1))
            add(kotlinx.serialization.json.JsonPrimitive(1))
            add(kotlinx.serialization.json.JsonPrimitive(0))
        }
        assertTrue(HoldemTable.load(mangle("busted", dupOrder)) == null,
            "two seats cannot share a finishing place")
        assertTrue(HoldemTable.load(good.toJson()) != null, "and a good record still loads")
    }

    // ================================================================ #14
    @Test
    fun theRosterNeverSeatsTheHuman() {
        // verdict 25 puts Adam in the roster; the background economy must not
        // deal him into a game he is not playing
        val r = Roster(worldSeed = 4)
        r.humanId = "you"
        r.put(Character("you", "You", Character.Traits.load(null), 100_000, 99))
        r.ensurePopulation()
        assertTrue(r.available().none { it.id == "you" })
        repeat(20) { k ->
            val seated = r.seat(HoldemRules.Table.REGULAR, 6, key = k.toLong())
            assertTrue(seated.none { it.who.id == "you" }, "the human was seated by the roster")
            for (s in seated) s.who.bankroll += s.stake + s.fee
        }
        // and he is still IN the standings — he is a character, just not one
        // the room deals in
        assertTrue(r.standings().any { it.id == "you" })
    }

    private suspend fun settle(shell: Shell) {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 20_000) delay(10)
        assertTrue(shell.isQuiescent(), "the shell did not settle")
    }
}
