package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.windows.games.holdem.Equity
import wm.damage.core.windows.games.holdem.HoldemBot
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.holdem.HoldemTable
import wm.damage.core.windows.games.kit.ActionLevel
import wm.damage.core.windows.games.kit.Card
import wm.damage.core.windows.games.kit.Rng
import wm.damage.core.windows.games.kit.Seats
import wm.damage.core.windows.games.roster.Character

/**
 * Equity, the bot's decision model and the properties `HOLDEM.md` §8 asks it
 * to have — decent-casual play, character that survives the noise, and 🔴 **no
 * tells** (verdict 37).
 */
class HoldemBotTest {

    private fun who(n: Int) = (0 until n).map { Seats.Occupant("p$it", "P$it", human = false) }

    private fun character(id: String, t: Character.Traits) =
        Character(id, id.uppercase(), t, generalWealth = 1000, livesTotal = 3)

    private val balanced = Character.Traits(0.5, 0.5, 0.15, 0.5, 0.4, 0.0, 0.5, 0.5, 0.7)

    // ================================================================ equity
    @Test
    fun equityRanksTheHandsTheWayAPlayerWould() {
        fun eq(hole: String, board: String, opp: Int, n: Int = 3000): Double {
            val h = hole.chunked(2).map { Card.of(it) }
            val b = if (board.isEmpty()) emptyList() else board.chunked(2).map { Card.of(it) }
            return Equity.estimate(h, b, opp, n, Rng.stream(1, hole.hashCode().toLong()))
        }
        val aa = eq("AsAh", "", 1)
        val kk = eq("KsKh", "", 1)
        val t2 = eq("7s2h", "", 1)
        assertTrue(aa > 0.8, "aces heads-up should be over 80%, got $aa")
        assertTrue(aa > kk && kk > t2, "AA > KK > 72o, got $aa / $kk / $t2")
        // more opponents, less equity
        assertTrue(eq("AsAh", "", 5) < aa)
        // a made flush on the river is close to a lock heads-up
        val flush = eq("AsKs", "QsJs2s", 1)
        assertTrue(flush > 0.85, "a nut flush on the flop should be dominant, got $flush")
        // a hand that is already beaten by the board is not
        val dead = eq("2c3d", "AsKsQsJsTs", 1)
        assertTrue(dead < 0.6, "playing the board can only chop, got $dead")
    }

    @Test
    fun theHandClassTableIsDeterministicAndCoversEveryClass() {
        // the preflop table is what makes the background economy affordable:
        // it must be the same number on every host
        val a = Equity.preflop(listOf(Card.of("As"), Card.of("Ks")), 3)
        val b = Equity.preflop(listOf(Card.of("Ad"), Card.of("Kd")), 3)
        assertEquals(a, b, "the same hand CLASS must give the same number")
        assertTrue(Equity.preflop(listOf(Card.of("As"), Card.of("Kh")), 3) != a,
            "suited and offsuit are different classes")
        // every class parses, including the ten
        for (r1 in wm.damage.core.windows.games.kit.Rank.entries)
            for (r2 in wm.damage.core.windows.games.kit.Rank.entries) {
                if (r2.v > r1.v) continue
                for (suited in listOf(true, false)) {
                    if (r1 == r2 && suited) continue
                    val cls = if (r1 == r2) "${r1.label}${r2.label}"
                    else "${r1.label}${r2.label}${if (suited) "s" else "o"}"
                    val rep = Equity.representative(cls)
                    assertEquals(2, rep.size)
                    assertEquals(cls, Equity.handClass(rep), "class '$cls' did not round-trip")
                }
            }
    }

    // ================================================================ modulate
    @Test
    fun scaredMoneyTightensAShortStackAndHeadroomLoosensABigOne() {
        // §8's deliberate MISTAKE: short stacks tightening is realistic and
        // theoretically wrong, and modelling it is the point
        val t = balanced.copy(discipline = 0.1)
        val rng = Rng.stream(1, 1)
        val deep = HoldemBot.modulate(t, HoldemBot.Spot(40.0, 1.0, 6, 0.0, 0.0, 0.5), Rng.stream(1, 1))
        val short = HoldemBot.modulate(t, HoldemBot.Spot(6.0, 1.0, 6, 0.0, 0.0, 0.5), Rng.stream(1, 1))
        assertTrue(short.tightness > deep.tightness,
            "an undisciplined short stack must tighten: ${short.tightness} vs ${deep.tightness}")
        val big = HoldemBot.modulate(t, HoldemBot.Spot(40.0, 2.5, 6, 0.0, 0.0, 0.5), Rng.stream(1, 1))
        assertTrue(big.tightness < deep.tightness, "a big stack loosens")
        assertTrue(big.aggression > deep.aggression, "and pushes harder")
        // a DISCIPLINED player barely moves — the trait doing double duty as
        // personality and skill
        val pro = balanced.copy(discipline = 0.95)
        val proShort = HoldemBot.modulate(pro, HoldemBot.Spot(6.0, 1.0, 6, 0.0, 0.0, 0.5), Rng.stream(1, 1))
        val proDeep = HoldemBot.modulate(pro, HoldemBot.Spot(40.0, 1.0, 6, 0.0, 0.0, 0.5), Rng.stream(1, 1))
        assertTrue(proShort.tightness - proDeep.tightness < short.tightness - deep.tightness)
        assertTrue(rng.nextDouble() >= 0.0)
    }

    @Test
    fun tiltIsSignedSoTwoCharactersReactOppositelyToTheSameBadRun() {
        val rock = balanced.copy(tiltSign = 0.9, moodiness = 0.9)
        val maniac = balanced.copy(tiltSign = -0.9, moodiness = 0.9)
        val bad = HoldemBot.Spot(30.0, 1.0, 6, mood = -1.0, form = 0.0, position = 0.5)
        val calm = HoldemBot.Spot(30.0, 1.0, 6, mood = 0.0, form = 0.0, position = 0.5)
        val rockTilt = HoldemBot.modulate(rock, bad, Rng.stream(2, 1))
        val rockCalm = HoldemBot.modulate(rock, calm, Rng.stream(2, 1))
        val manTilt = HoldemBot.modulate(maniac, bad, Rng.stream(2, 1))
        val manCalm = HoldemBot.modulate(maniac, calm, Rng.stream(2, 1))
        assertTrue(rockTilt.tightness > rockCalm.tightness, "a tight-passive tilt shuts down")
        assertTrue(manTilt.tightness < manCalm.tightness, "a loose-aggressive tilt opens up")
        assertTrue(manTilt.aggression > manCalm.aggression)
    }

    @Test
    fun consistencyIsTheNoiseAmplitudeSoAnErraticPlayerIsReliablyErratic() {
        val steady = balanced.copy(consistency = 0.98)
        val wild = balanced.copy(consistency = 0.02)
        fun spread(t: Character.Traits): Double {
            val vs = (0 until 400).map {
                HoldemBot.modulate(t, HoldemBot.Spot(30.0, 1.0, 6, 0.0, 0.0, 0.5),
                    Rng.stream(9, it.toLong())).tightness
            }
            val m = vs.average()
            return kotlin.math.sqrt(vs.sumOf { (it - m) * (it - m) } / vs.size)
        }
        val a = spread(steady)
        val b = spread(wild)
        assertTrue(b > a * 3, "the erratic player must swing far more: $b vs $a")
        assertTrue(a < 0.02, "the steady player must be nearly fixed: $a")
    }

    // ================================================================ no tells
    @Test
    fun sizingDoesNotLeakHandStrength() {
        // 🔴 verdict 37: a size that tracked strength would be a planted tell.
        // Two decisions with the same dials, the same pot and the same RNG key
        // but very different equities must bet the SAME amount.
        val d = HoldemBot.Dials(0.5, 0.6, 0.2, 0.5, 0.0)
        fun size(eq: Double): Int = HoldemBot.policy(eq, 100, 0, 0, 500, 20, 500,
            canRaise = true, d = d, bbDepth = 25.0, rng = Rng.stream(5, 5)).to
        val strong = size(0.95)
        val good = size(0.72)
        assertTrue(strong > 0 && good > 0, "both should bet")
        assertEquals(strong, good, "the bet size must not follow the hand")
    }

    // ================================================================ play
    @Test
    fun aBotTournamentFinishesAndConservesChips() {
        val n = 6
        val cast = (0 until n).map { character("c$it", Character.Traits.roll(4242, it.toLong())) }
        val t = HoldemTable.start(HoldemRules.Table.REGULAR, 20260904, who(n), IntArray(n) { 200 })
        val start = 200 * n
        var hands = 0
        while (hands < 5000) {
            var guard = 0
            while (t.view().toAct != null && guard++ < 400) {
                HoldemBot.play(t, t.view().toAct!!, cast[t.view().toAct!!], Equity.CHEAP_ROLLOUTS)
            }
            assertTrue(t.handComplete, "hand ${t.handNo} stalled at ${t.view().street}")
            assertEquals(start, t.view().seats.sumOf { it.stack }, "chips leaked on hand ${t.handNo}")
            hands++
            if (!t.nextHand()) break
        }
        assertEquals(1, t.activeSeats().size, "the sit-and-go must end")
        assertTrue(hands in 3..2000, "a 6-max sit-and-go ran $hands hands")
        println("bot tournament: $hands hands, winner ${t.winner()}")
    }

    @Test
    fun aResumedBotIsTheSameBot() {
        // §11.2's second property. Play a few decisions, save, reload, and let
        // the SAME characters keep going: the two logs must be identical.
        val n = 4
        val cast = (0 until n).map { character("c$it", Character.Traits.roll(77, it.toLong())) }
        fun run(reloadAfter: Int): List<String> {
            var t = HoldemTable.start(HoldemRules.Table.REGULAR, 99, who(n), IntArray(n) { 300 })
            var acts = 0
            var guard = 0
            while (guard++ < 200 && t.view().toAct != null) {
                if (acts == reloadAfter) t = HoldemTable.load(t.toJson())!!
                val s = t.view().toAct!!
                HoldemBot.play(t, s, cast[s], Equity.CHEAP_ROLLOUTS)
                acts++
            }
            return t.view().history
        }
        assertEquals(run(-1), run(2), "a save/restore changed what the bots did")
        assertEquals(run(-1), run(4))
    }

    @Test
    fun tightAndLooseCharactersPlayVisiblyDifferently() {
        // verdict 20's "depth and feel real" is only true if the dials reach
        // the table. Two tables of clones, one very tight and one very loose:
        // the loose table must see far more flops.
        fun vpip(t: Character.Traits): Double {
            val n = 6
            val cast = (0 until n).map { character("x", t) }
            var voluntary = 0
            var chances = 0
            var table = HoldemTable.start(HoldemRules.Table.REGULAR, 5150, who(n), IntArray(n) { 5000 })
            repeat(60) { hand ->
                var guard = 0
                val acted = HashSet<Int>()
                while (table.view().toAct != null && guard++ < 200) {
                    val v = table.view()
                    val s = v.toAct!!
                    val first = acted.add(s) && v.street == wm.damage.core.windows.games.holdem.Street.PREFLOP
                    val d = HoldemBot.play(table, s, cast[s], Equity.CHEAP_ROLLOUTS)
                    if (first) {
                        chances++
                        if (d.kind != ActionLevel.Kind.FOLD && d.kind != ActionLevel.Kind.CHECK) voluntary++
                    }
                }
                if (!table.nextHand()) return@repeat
            }
            return if (chances == 0) 0.0 else voluntary.toDouble() / chances
        }
        val tight = vpip(balanced.copy(tightness = 0.95, aggression = 0.2, bluffFreq = 0.02))
        val loose = vpip(balanced.copy(tightness = 0.05, aggression = 0.8, bluffFreq = 0.4))
        println("VPIP tight=$tight loose=$loose")
        assertTrue(loose > tight + 0.15, "the dials must reach the table: tight=$tight loose=$loose")
    }

    @Test
    fun aBotNeverFoldsWhenCheckingIsFree() {
        // not a style, a bug wearing one — the conform step exists for it
        val n = 3
        val cast = (0 until n).map { character("c$it", balanced.copy(tightness = 0.98)) }
        val t = HoldemTable.start(HoldemRules.Table.REGULAR, 1, who(n), IntArray(n) { 400 })
        var folds = 0
        var guard = 0
        while (guard++ < 3000) {
            val v = t.view()
            val s = v.toAct ?: break
            val free = v.currentBet - v.seats[s].committed <= 0
            val d = HoldemBot.play(t, s, cast[s], Equity.CHEAP_ROLLOUTS)
            if (free && d.kind == ActionLevel.Kind.FOLD) folds++
            if (t.handComplete && !t.nextHand()) break
        }
        assertEquals(0, folds, "a bot folded a free check")
    }
}
