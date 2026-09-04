package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.holdem.HoldemTable
import wm.damage.core.windows.games.holdem.Street
import wm.damage.core.windows.games.kit.ActionLevel
import wm.damage.core.windows.games.kit.Rng
import wm.damage.core.windows.games.kit.Seats

/**
 * The Hold'em ENGINE (`HOLDEM.md` M3, §13.1) — no UI anywhere in this file.
 * The engine is provably right before a pixel rests on it.
 *
 * The state is an action LOG replayed from (seed, handNo, start stacks, button)
 * — so "a resumed hand is the same hand" is not a claim here, it is the
 * mechanism, and these tests check the mechanism holds.
 */
class HoldemEngineTest {

    private fun who(n: Int) = (0 until n).map {
        Seats.Occupant("p$it", "P$it", human = it == 0)
    }

    private fun table(n: Int = 6, stack: Int = 200, seed: Long = 7,
        spec: HoldemRules.Table = HoldemRules.Table.REGULAR, button: Int = 0) =
        HoldemTable.start(spec, seed, who(n), IntArray(n) { stack }, button)

    /** Every chip at the table. Once a hand has SETTLED the winnings are back
     *  in the stacks and `pot` is the hand's history, not money still owed —
     *  counting both double-counts the whole pot. */
    private fun total(t: HoldemTable): Int {
        val v = t.view()
        return v.seats.sumOf { it.stack } + if (v.result == null) v.pot else 0
    }

    // ================================================================ blinds & order
    @Test
    fun blindsAndActionOrderSixHanded() {
        val t = table(button = 0)
        val v = t.view()
        assertEquals(1, v.sb)
        assertEquals(2, v.bb)
        assertEquals(1, v.sbSeat, "the small blind sits left of the button")
        assertEquals(2, v.bbSeat)
        assertEquals(1, v.seats[1].committed)
        assertEquals(2, v.seats[2].committed)
        assertEquals(3, v.toAct, "under the gun acts first preflop")
        assertEquals(2, v.currentBet)
    }

    @Test
    fun headsUpTheButtonPostsTheSmallBlindActsFirstPreflopAndLastPostflop() {
        val t = table(n = 2, button = 0)
        var v = t.view()
        assertEquals(0, v.sbSeat, "heads-up the BUTTON posts the small blind")
        assertEquals(1, v.bbSeat)
        assertEquals(0, v.toAct, "and acts first preflop")
        t.act(ActionLevel.Kind.CALL)          // button limps
        t.act(ActionLevel.Kind.CHECK)         // big blind checks its option
        v = t.view()
        assertEquals(Street.FLOP, v.street)
        assertEquals(1, v.toAct, "postflop the big blind acts first — the button is last")
    }

    @Test
    fun postflopTheSmallBlindActsFirst() {
        val t = table(button = 0)
        t.act(ActionLevel.Kind.FOLD)          // 3
        t.act(ActionLevel.Kind.FOLD)          // 4
        t.act(ActionLevel.Kind.FOLD)          // 5
        t.act(ActionLevel.Kind.FOLD)          // 0 (button)
        t.act(ActionLevel.Kind.CALL)          // 1 (SB) completes
        t.act(ActionLevel.Kind.CHECK)         // 2 (BB) checks its option
        val v = t.view()
        assertEquals(Street.FLOP, v.street)
        assertEquals(1, v.toAct, "the small blind acts first postflop")
        assertEquals(4, v.pot)
    }

    @Test
    fun theBigBlindGetsItsOptionWhenEveryoneLimps() {
        val t = table(button = 0)
        for (s in listOf(3, 4, 5, 0, 1)) t.act(ActionLevel.Kind.CALL)
        val v = t.view()
        assertEquals(Street.PREFLOP, v.street, "the round cannot close before the BB has acted")
        assertEquals(2, v.toAct)
        assertTrue(t.legalActions().any { it.kind == ActionLevel.Kind.CHECK })
        assertTrue(t.legalActions().any { it.kind == ActionLevel.Kind.RAISE })
    }

    // ================================================================ betting rules
    @Test
    fun minimumRaiseIsTheLastFullRaiseIncrement() {
        val t = table(stack = 1000, button = 0)
        assertEquals(4, t.minRaiseTo(), "preflop the minimum raise is to 2 big blinds")
        t.act(ActionLevel.Kind.RAISE, 6)                        // to 6 — a raise of 4
        assertEquals(10, t.minRaiseTo(), "the increment is now 4, so the next raise is to 10")
        assertFailsWith<IllegalStateException> { t.act(ActionLevel.Kind.RAISE, 9) }
        t.act(ActionLevel.Kind.RAISE, 20)                       // increment 14
        assertEquals(34, t.minRaiseTo())
    }

    @Test
    fun anAllInForLessThanAFullRaiseDoesNotReopenTheBetting() {
        // §12 item 4. Three players: A bets 100, B is all-in for 140 (an
        // increment of 40 against a 100 raise), C called already. A may CALL
        // the extra 40 but may NOT re-raise; C, who had not acted since, may.
        val stacks = intArrayOf(1000, 140, 1000)
        val t = HoldemTable.start(HoldemRules.Table.REGULAR, 11, who(3), stacks, button = 0)
        // seats: 0 button, 1 SB, 2 BB. Preflop order 0, 1, 2
        t.act(ActionLevel.Kind.RAISE, 100)                      // seat 0 raises to 100
        assertEquals(1, t.view().toAct)
        t.act(ActionLevel.Kind.ALL_IN)                          // seat 1 all-in for 140
        assertEquals(140, t.view().currentBet)
        assertEquals(2, t.view().toAct)
        assertTrue(t.canRaise(), "seat 2 has not acted since the last full raise — it may re-raise")
        t.act(ActionLevel.Kind.CALL)
        assertEquals(0, t.view().toAct)
        assertTrue(!t.canRaise(),
            "seat 0 already acted and faces an INCOMPLETE all-in — call or fold only")
        assertTrue(t.legalActions().none {
            it.kind == ActionLevel.Kind.RAISE || it.kind == ActionLevel.Kind.BET
        })
        assertFailsWith<IllegalStateException> { t.act(ActionLevel.Kind.RAISE, 300) }
        t.act(ActionLevel.Kind.CALL)
        assertEquals(Street.FLOP, t.view().street)
    }

    @Test
    fun aFullAllInRaiseDoesReopenIt() {
        val stacks = intArrayOf(1000, 300, 1000)
        val t = HoldemTable.start(HoldemRules.Table.REGULAR, 11, who(3), stacks, button = 0)
        t.act(ActionLevel.Kind.RAISE, 100)                      // seat 0 to 100 (increment 98)
        t.act(ActionLevel.Kind.ALL_IN)                          // seat 1 all-in 300 — a FULL raise
        t.act(ActionLevel.Kind.FOLD)                            // seat 2
        assertEquals(0, t.view().toAct)
        assertTrue(t.canRaise(), "a full raise reopens the betting")
    }

    @Test
    fun theShortestAllInIsAlwaysAvailable() {
        val stacks = intArrayOf(1000, 1000, 3)
        val t = HoldemTable.start(HoldemRules.Table.REGULAR, 13, who(3), stacks, button = 0)
        // seat 2 is the big blind with 3 chips: it posted 2 and has 1 left
        t.act(ActionLevel.Kind.RAISE, 50)
        t.act(ActionLevel.Kind.FOLD)
        assertEquals(2, t.view().toAct)
        val acts = t.legalActions()
        assertTrue(acts.any { it.kind == ActionLevel.Kind.ALL_IN }, "call all-in must be offered: $acts")
        t.act(ActionLevel.Kind.CALL)
        // the hand runs itself out from here (nobody else can act), so the
        // stack already carries the result: what is checked is the COMMITMENT
        assertTrue(t.view().seats[2].allIn)
        assertEquals(3, t.view().seats[2].contributed, "the short stack is in for all three chips")
        assertTrue(t.handComplete)
    }

    @Test
    fun aCheckFacingABetIsRefusedLoudly() {
        val t = table()
        t.act(ActionLevel.Kind.RAISE, 10)
        assertFailsWith<IllegalStateException> { t.act(ActionLevel.Kind.CHECK) }
    }

    // ================================================================ hands
    @Test
    fun everyHandConservesChips() {
        var t = table(n = 6, stack = 200, seed = 99)
        val start = total(t)
        var hands = 0
        while (hands < 200) {
            playRandomHand(t, Rng.stream(4242, hands.toLong()))
            assertEquals(start, total(t), "chips leaked on hand ${t.handNo}")
            if (!t.nextHand()) break
            assertEquals(start, total(t), "chips leaked settling hand ${t.handNo}")
            hands++
        }
        assertTrue(hands > 0)
    }

    @Test
    fun aTournamentShrinksSixToOne() {
        val t = table(n = 6, stack = 200, seed = 5)
        val start = total(t)
        var hands = 0
        var seen = 6
        while (hands < 3000) {
            playRandomHand(t, Rng.stream(1234, hands.toLong()))
            val goingOn = t.nextHand()
            val active = t.activeSeats().size
            assertTrue(active <= seen, "the table grew from $seen to $active — there is no re-entry")
            seen = active
            hands++
            if (!goingOn) break
        }
        assertEquals(1, t.activeSeats().size, "a sit-and-go ends with one player holding every chip")
        assertNotNull(t.winner())
        assertEquals(start, t.view().seats.sumOf { it.stack },
            "the winner holds every chip that entered")
        // finishing places are 1..6 with no gaps
        val places = (0 until 6).map { t.finishPlace(it) }
        assertEquals(listOf(2, 3, 4, 5, 6).toSet(), places.filterNotNull().toSet())
        assertEquals(1, places.count { it == null }, "exactly one player has not finished: the winner")
    }

    // ================================================================ escalation
    @Test
    fun blindsEscalateAtExactlyTheTwentyHandBoundary() {
        assertEquals(0, HoldemRules.levelOf(0))
        assertEquals(0, HoldemRules.levelOf(19))
        assertEquals(1, HoldemRules.levelOf(20))
        assertEquals(2, HoldemRules.levelOf(40))
        assertEquals(20, HoldemRules.handsToNextLevel(0))
        assertEquals(1, HoldemRules.handsToNextLevel(19))

        // Regular: +$1 a level
        assertEquals(listOf(1, 2, 3, 4, 5), (0..4).map { HoldemRules.Table.REGULAR.sbAt(it) })
        // Big Boy: +$1 until the SB passes $10, then doubling (verdict 18)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 40, 80, 160, 320),
            (0..14).map { HoldemRules.Table.BIG_BOY.sbAt(it) })
        // Unlimited: doubling from $5
        assertEquals(listOf(5, 10, 20, 40, 80, 160), (0..5).map { HoldemRules.Table.UNLIMITED.sbAt(it) })
        // BB is always twice SB
        for (s in HoldemRules.Table.entries) for (l in 0..30) {
            assertEquals(2 * s.sbAt(l), s.bbAt(l))
            assertTrue(s.sbAt(l) > 0, "$s level $l blinds went non-positive — a doubling overflow")
        }
    }

    @Test
    fun theTableUsesTheLevelForItsHandNumber() {
        val t = table(n = 3, stack = 100_000, seed = 3)
        var hands = 0
        while (t.handNo < 21 && hands < 400) {
            playRandomHand(t, Rng.stream(88, hands.toLong()))
            if (!t.nextHand()) break
            hands++
        }
        val v = t.view()
        assertEquals(HoldemRules.levelOf(v.handNo), v.level)
        if (v.handNo >= 20) assertEquals(2, v.sb, "level 1 at Regular is a $2 small blind")
    }

    // ================================================================ the fee
    @Test
    fun theEntryFeeIsFivePercentWithADollarFloor() {
        assertEquals(10, HoldemRules.fee(200))
        assertEquals(50, HoldemRules.fee(1000))
        assertEquals(1, HoldemRules.fee(1))
        assertEquals(1, HoldemRules.fee(10))
        assertEquals(5, HoldemRules.fee(100))
        // rounded UP: $1 is the chip denomination
        assertEquals(6, HoldemRules.fee(101))
        // verdict 15's derivation
        assertEquals(11, HoldemRules.cheapestSeat())
    }

    // ================================================================ persistence
    @Test
    fun aResumedHandIsTheSameHand() {
        // §11.2, and the property the Tmux Focus-mode failure taught this
        // project to demand: not a reshuffle wearing the old pot's clothes
        val t = table(n = 5, stack = 300, seed = 20260904)
        val rng = Rng.stream(77, 0)
        repeat(4) { if (t.view().toAct != null) randomAct(t, rng) }
        val before = t.view()
        val blob = t.toJson()
        val t2 = HoldemTable.load(blob) ?: error("the record would not replay")
        val after = t2.view()
        assertEquals(before.handNo, after.handNo)
        assertEquals(before.street, after.street)
        assertEquals(before.toAct, after.toAct)
        assertEquals(before.pot, after.pot)
        assertEquals(before.currentBet, after.currentBet)
        assertEquals(before.board.map { it.code }, after.board.map { it.code })
        assertEquals(before.seats.map { it.cards.map { c -> c.code } },
            after.seats.map { it.cards.map { c -> c.code } },
            "the resumed hand dealt different cards")
        assertEquals(before.seats.map { it.stack }, after.seats.map { it.stack })
        assertEquals(before.history, after.history)
        assertEquals(blob.toString(), t2.toJson().toString(), "the record did not round-trip")
    }

    @Test
    fun aRecordThatWillNotReplayIsRefusedRatherThanGuessedAt() {
        val t = table(n = 3)
        t.act(ActionLevel.Kind.CALL)
        val bad = kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in t.toJson()) {
                if (k == "log") put("log", kotlinx.serialization.json.buildJsonArray {
                    // seat 2 could not have acted first
                    add(kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive(2))
                        add(kotlinx.serialization.json.JsonPrimitive("CALL"))
                        add(kotlinx.serialization.json.JsonPrimitive(2))
                    })
                }) else put(k, v)
            }
        }
        assertNull(HoldemTable.load(bad), "a log that does not replay must be refused")
    }

    @Test
    fun theWholeTournamentSurvivesASaveAndReloadEveryHand() {
        var t = table(n = 4, stack = 150, seed = 31337)
        val start = total(t)
        var hands = 0
        while (hands < 2000) {
            playRandomHand(t, Rng.stream(555, hands.toLong()))
            val reloaded = HoldemTable.load(t.toJson()) ?: error("hand ${t.handNo} would not reload")
            assertEquals(t.view().result?.won, reloaded.view().result?.won)
            t = reloaded
            assertEquals(start, total(t))
            if (!t.nextHand()) break
            t = HoldemTable.load(t.toJson()) ?: error("the between-hands record would not reload")
            hands++
        }
        assertEquals(1, t.activeSeats().size)
    }

    // ================================================================ cash out
    @Test
    fun cashingOutReturnsTheStackAndLeavesTheTableRunning() {
        val t = table(n = 4, stack = 200, seed = 4242)
        playRandomHand(t, Rng.stream(9, 0))
        t.nextHand()
        val mine = t.stackOf(0)
        val chips = t.cashOut(0)
        assertEquals(mine, chips)
        assertTrue(!t.inPlay(0))
        assertEquals(3, t.activeSeats().size, "the table plays on without you (§5.1)")
        // and it can still be finished
        var guard = 0
        while (guard++ < 2000) {
            playRandomHand(t, Rng.stream(9, guard.toLong()))
            if (!t.nextHand()) break
        }
        assertEquals(1, t.activeSeats().size)
    }

    @Test
    fun cashingOutMidHandIsRefused() {
        val t = table(n = 3)
        t.act(ActionLevel.Kind.CALL)
        assertFailsWith<IllegalStateException> { t.cashOut(1) }
    }

    // ================================================================ helpers
    private fun randomAct(t: HoldemTable, rng: Rng.Stream) {
        val acts = t.legalActions()
        if (acts.isEmpty()) return
        val pick = acts[rng.nextInt(acts.size)]
        when (pick.kind) {
            ActionLevel.Kind.BET, ActionLevel.Kind.RAISE -> {
                val min = t.minRaiseTo()
                val max = t.maxRaiseTo()
                val to = if (max <= min) max else min + rng.nextInt(max - min + 1)
                t.act(pick.kind, to)
            }
            else -> t.act(pick.kind)
        }
    }

    private fun playRandomHand(t: HoldemTable, rng: Rng.Stream) {
        var guard = 0
        while (t.view().toAct != null && guard++ < 500) randomAct(t, rng)
        assertTrue(t.handComplete, "the hand never finished (${t.view().street})")
    }
}
