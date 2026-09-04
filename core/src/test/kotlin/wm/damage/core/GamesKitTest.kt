package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.windows.games.kit.Card
import wm.damage.core.windows.games.kit.CardArt
import wm.damage.core.windows.games.kit.Deck
import wm.damage.core.windows.games.kit.HandEval
import wm.damage.core.windows.games.kit.HandFan
import wm.damage.core.windows.games.kit.Money
import wm.damage.core.windows.games.kit.Pots
import wm.damage.core.windows.games.kit.Rank
import wm.damage.core.windows.games.kit.Rng
import wm.damage.core.windows.games.kit.Suit
import wm.damage.core.windows.games.kit.TableLayout

/**
 * The card kit (`HOLDEM.md` §6, §13.1) — and the SIDE-POT ORACLE (§13.2).
 *
 * The oracle is the `LensOracleTest` pattern applied to poker: an independent
 * authority proves the model rather than us reading our own implementation and
 * agreeing with ourselves. `pokerkit` (MIT, Toronto CPSRG) settled thousands of
 * randomised multi-way all-in hands in a scratch venv; what is committed is its
 * VERDICTS — `core/src/test/resources/holdem/{sidepots,hands}.json`, a corpus we
 * own outright, so this test needs no Python and no network. The generator is
 * `research/gen_sidepots.py`; nothing third-party enters the repo.
 */
class GamesKitTest {

    private fun resource(name: String): JsonObject {
        val s = javaClass.getResourceAsStream("/holdem/$name")
            ?: error("the $name corpus is missing from the test resources")
        return Json.parseToJsonElement(s.bufferedReader().readText()).jsonObject
    }

    // ================================================================ Rng
    @Test
    fun counterRngIsStatelessAndKeyOrderMatters() {
        // the §11.2 contract: a value is a pure function of its key
        assertEquals(Rng.hash(7, 1, 2, 3), Rng.hash(7, 1, 2, 3))
        // (1,0) and (0,1) must NOT collide — seat 1's first decision is not
        // seat 0's second
        assertTrue(Rng.hash(7, 1, 0) != Rng.hash(7, 0, 1))
        assertTrue(Rng.hash(7, 1) != Rng.hash(8, 1))
        // a stream replays from its key
        val a = Rng.stream(99, 1).let { s -> List(20) { s.nextLong() } }
        val b = Rng.stream(99, 1).let { s -> List(20) { s.nextLong() } }
        assertEquals(a, b)
        // and is not degenerate
        assertEquals(20, a.toSet().size)
    }

    @Test
    fun rngIntIsUniformEnoughToShuffleWith() {
        val counts = IntArray(52)
        val s = Rng.Stream(12345)
        repeat(52_000) { counts[s.nextInt(52)]++ }
        val lo = counts.min()
        val hi = counts.max()
        assertTrue(lo > 850 && hi < 1150, "int(52) is lumpy: min=$lo max=$hi")
    }

    // ================================================================ Cards
    @Test
    fun theShuffleIsAPermutationAndIsSeeded() {
        val d = Deck.shuffled(4242)
        assertEquals(52, d.size)
        assertEquals(52, d.toSet().size)
        assertEquals(Deck.ordered.toSet(), d.toSet())
        assertEquals(d, Deck.shuffled(4242), "the same seed must be the same deck")
        assertTrue(d != Deck.shuffled(4243))
        assertTrue(d != Deck.ordered, "a shuffle that returns the ordered deck is not a shuffle")
    }

    @Test
    fun cardCodesRoundTrip() {
        for (c in Deck.ordered) {
            assertEquals(c, Card.of(c.code))
            assertEquals(c, Card.ofIndex(c.index))
        }
        assertFailsWith<IllegalArgumentException> { Card.of("Xs") }
        assertFailsWith<IllegalArgumentException> { Card.of("Ax") }
    }

    // ================================================================ HandEval
    private fun cards(vararg s: String) = s.map { Card.of(it) }

    @Test
    fun knownRankingsAndTheWheel() {
        fun sc(vararg s: String) = HandEval.score(s.map { Card.of(it) })
        assertEquals(HandEval.Category.STRAIGHT_FLUSH, HandEval.categoryOf(sc("As", "Ks", "Qs", "Js", "Ts")))
        assertEquals(HandEval.Category.QUADS, HandEval.categoryOf(sc("9s", "9h", "9d", "9c", "2s")))
        assertEquals(HandEval.Category.FULL_HOUSE, HandEval.categoryOf(sc("9s", "9h", "9d", "2c", "2s")))
        assertEquals(HandEval.Category.FLUSH, HandEval.categoryOf(sc("As", "Js", "8s", "5s", "2s")))
        assertEquals(HandEval.Category.STRAIGHT, HandEval.categoryOf(sc("9s", "8h", "7d", "6c", "5s")))
        assertEquals(HandEval.Category.TRIPS, HandEval.categoryOf(sc("9s", "9h", "9d", "5c", "2s")))
        assertEquals(HandEval.Category.TWO_PAIR, HandEval.categoryOf(sc("9s", "9h", "5d", "5c", "2s")))
        assertEquals(HandEval.Category.PAIR, HandEval.categoryOf(sc("9s", "9h", "Kd", "5c", "2s")))
        assertEquals(HandEval.Category.HIGH_CARD, HandEval.categoryOf(sc("As", "Jh", "9d", "5c", "2s")))

        // the wheel plays as a FIVE-high straight, and loses to a six-high one
        val wheel = sc("As", "2h", "3d", "4c", "5s")
        assertEquals(HandEval.Category.STRAIGHT, HandEval.categoryOf(wheel))
        assertEquals(5, HandEval.kickersOf(wheel)[0])
        assertTrue(wheel < sc("6s", "2h", "3d", "4c", "5s"))
        // and the steel wheel is a straight FLUSH, not an ace-high flush
        assertEquals(HandEval.Category.STRAIGHT_FLUSH,
            HandEval.categoryOf(sc("As", "2s", "3s", "4s", "5s")))

        // a flush beats a straight; quads beat a full house
        assertTrue(sc("9s", "8h", "7d", "6c", "5s") < sc("As", "Js", "8s", "5s", "2s"))
        assertTrue(sc("9s", "9h", "9d", "2c", "2s") < sc("9s", "9h", "9d", "9c", "2s"))
        // kickers decide
        assertTrue(sc("As", "Ah", "Kd", "5c", "2s") > sc("As", "Ah", "Qd", "5c", "2s"))
        // seven cards pick the best five
        assertEquals(HandEval.Category.FLUSH,
            HandEval.categoryOf(HandEval.score(cards("As", "Js", "8s", "5s", "2s", "9h", "9d"))))
    }

    @Test
    fun handRankingsAgreeWithTheOracleCorpus() {
        val groups = resource("hands.json")["groups"]!!.jsonArray
        assertTrue(groups.size >= 1000, "the hand corpus is too small: ${groups.size}")
        var checked = 0
        for (g in groups) {
            val o = g.jsonObject
            val board = o["board"]!!.jsonArray.map { Card.of(it.jsonPrimitive.content) }
            val holes = o["holes"]!!.jsonArray.map { h -> h.jsonArray.map { Card.of(it.jsonPrimitive.content) } }
            val scores = holes.map { HandEval.score(it + board) }
            val best = scores.max()
            val mine = scores.indices.filter { scores[it] == best }
            val theirs = o["winners"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(theirs, mine, "winner mismatch on board ${board.map { it.code }} holes ${holes.map { h -> h.map { it.code } }}")
            // the WHOLE ordering, strongest first, as groups of ties
            val order = o["order"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.int } }
            val myOrder = scores.indices.sortedByDescending { scores[it] }
                .groupBy { scores[it] }.toSortedMap(reverseOrder()).values.map { it.sorted() }
            assertEquals(order, myOrder, "ordering mismatch on board ${board.map { it.code }}")
            checked++
        }
        println("hand oracle: $checked groups agreed")
    }

    // ================================================================ Pots
    @Test
    fun foldedPlayersChipsStillFormPots() {
        // §12 item 1 — the classic defect is counting only live players
        val e = listOf(
            Pots.Entry(0, 100, folded = false),
            Pots.Entry(1, 100, folded = false),
            Pots.Entry(2, 40, folded = true),
        )
        val pots = Pots.build(e)
        assertEquals(240, pots.sumOf { it.amount }, "the folder's 40 is dead money IN the pot")
        // two levels: 40×3 with contenders {0,1}, then 60×2 with {0,1} — the
        // same contenders, so they merge into one
        assertEquals(1, pots.size)
        assertEquals(listOf(0, 1), pots[0].contenders)
    }

    @Test
    fun theUncalledPortionReturnsBeforePotsAreFormed() {
        // §12 item 2, in the design record's own words: bet $100, one caller
        // for $30, $70 comes back
        val e = listOf(Pots.Entry(0, 100, false), Pots.Entry(1, 30, false))
        val back = Pots.uncalled(e)!!
        assertEquals(0, back.seat)
        assertEquals(70, back.amount)
        val s = Pots.settleHand(e, mapOf(0 to 10, 1 to 5), listOf(0, 1))
        assertEquals(60, s.total)
        assertEquals(60, s.won[0])
        assertEquals(null, s.won[1])
        // a CALLED bet returns nothing
        assertEquals(null, Pots.uncalled(listOf(Pots.Entry(0, 30, false), Pots.Entry(1, 30, false))))
    }

    @Test
    fun oddChipsGoClockwiseFromTheButtonOneAtATime() {
        // §12 item 3. Three-way chop of 302: 100 each and two odd chips, which
        // go to the first two live players clockwise from the button — NOT
        // both to the same seat, and not to the lowest seat number.
        val pots = listOf(Pots.SidePot(302, listOf(0, 1, 2)))
        val won = Pots.settle(pots, mapOf(0 to 5, 1 to 5, 2 to 5), order = listOf(2, 0, 1))
        assertEquals(101, won[2], "the seat left of the button gets the first odd chip")
        assertEquals(101, won[0], "the next one clockwise gets the second")
        assertEquals(100, won[1])
        assertEquals(302, won.values.sum())
    }

    @Test
    fun anAllInForLessThanAFullRaiseIsAPotQuestionNotAPotBug() {
        // §12 item 4 lives in HoldemRules; what Pots owes is that a short
        // all-in still builds the right two pots
        val e = listOf(
            Pots.Entry(0, 100, false),      // caller
            Pots.Entry(1, 60, false),       // short all-in
            Pots.Entry(2, 100, false),      // caller
        )
        val pots = Pots.build(e)
        assertEquals(2, pots.size)
        assertEquals(180, pots[0].amount)
        assertEquals(listOf(0, 1, 2), pots[0].contenders)
        assertEquals(80, pots[1].amount)
        assertEquals(listOf(0, 2), pots[1].contenders)
    }

    @Test
    fun aSidePotWhoseContendersAllFoldedRidesIntoThePotBelowIt() {
        // the money is real and must not vanish
        val e = listOf(
            Pots.Entry(0, 100, folded = true),
            Pots.Entry(1, 100, folded = true),
            Pots.Entry(2, 60, folded = false),
        )
        val pots = Pots.build(e)
        assertEquals(260, pots.sumOf { it.amount })
        assertEquals(listOf(2), pots.last().contenders)
        val won = Pots.settle(pots, mapOf(2 to 1), listOf(0, 1, 2))
        assertEquals(260, won[2])
    }

    @Test
    fun sidePotsAgreeWithTheOracleCorpus() {
        val scenarios = resource("sidepots.json")["scenarios"]!!.jsonArray
        assertTrue(scenarios.size >= 1000, "the side-pot corpus is too small: ${scenarios.size}")
        var exact = 0
        var conserved = 0
        for (sc in scenarios) {
            val o = sc.jsonObject
            val seed = o["seed"]!!.jsonPrimitive.int
            val seats = o["seats"]!!.jsonArray.map { it.jsonObject }
            val board = o["board"]!!.jsonArray.map { Card.of(it.jsonPrimitive.content) }
            val order = o["order"]!!.jsonArray.map { it.jsonPrimitive.int }
            val payout = o["payout"]!!.jsonArray.map { it.jsonPrimitive.int }
            val entries = seats.mapIndexed { i, s ->
                Pots.Entry(i, s["contrib"]!!.jsonPrimitive.int, s["folded"]!!.jsonPrimitive.boolean)
            }
            val live = entries.filter { !it.folded }.map { it.seat }
            val scores: Map<Int, Int> = if (board.size == 5) {
                live.associateWith { i ->
                    val hole = seats[i]["hole"]!!.jsonArray.map { Card.of(it.jsonPrimitive.content) }
                    HandEval.score(hole + board)
                }
            } else {
                // no showdown: everyone but one folded, so that seat takes it
                assertEquals(1, live.size, "scenario $seed has a short board but ${live.size} live seats")
                mapOf(live[0] to 0)
            }
            val s = Pots.settleHand(entries, scores, order)
            val mine = IntArray(entries.size)
            for ((seat, v) in s.won) mine[seat] += v
            s.uncalled?.let { mine[it.seat] += it.amount }

            assertEquals(entries.sumOf { it.amount }, mine.sum(),
                "scenario $seed does not conserve chips")
            conserved++
            val odd = o["oddChips"]!!.jsonPrimitive.int
            if (odd == 0) {
                assertEquals(payout, mine.toList(),
                    "scenario $seed payout mismatch (contrib=${entries.map { it.amount }} " +
                        "folded=${entries.map { it.folded }} board=${board.map { it.code }})")
                exact++
            } else {
                // the ONE thing not taken from the oracle: pokerkit hands a
                // pot's whole remainder to the first winner in its list, where
                // Robert's Rules spread the odd chips one at a time from the
                // button. Each misplaced chip moves +1 onto one seat and −1 off
                // another, so the total absolute difference is bounded by 2×.
                val drift = payout.indices.sumOf { kotlin.math.abs(mine[it] - payout[it]) }
                assertTrue(drift <= 2 * odd,
                    "scenario $seed drifts $drift chips with only $odd odd chips in play " +
                        "(mine=${mine.toList()} oracle=$payout)")
            }
        }
        println("side-pot oracle: $conserved scenarios conserved, $exact compared payout-exact")
    }

    // ================================================================ Money
    @Test
    fun moneyFormatsTheWayTheScoreboardReadsIt() {
        assertEquals("$0", Money.fmt(0))
        assertEquals("$847", Money.fmt(847))
        assertEquals("$1,000", Money.fmt(1000))
        assertEquals("$1,234,567", Money.fmt(1234567))
        assertEquals("-$12", Money.fmt(-12))
        assertEquals("$9,999", Money.compact(9999))
        assertEquals("$14k", Money.compact(14_000))
        assertEquals("25bb", Money.bb(500, 20))
        assertEquals("2.5bb", Money.bb(50, 20))
    }

    @Test
    fun theSevenSegmentDisplayRefusesWhatItCannotDraw() {
        val g = Gray8(200, 60)
        Money.digits(g, 0, 0, "1,234", Money.Seg.SMALL)
        assertTrue(g.pix.any { it.toInt() != 0 }, "the digits drew nothing")
        // captions go through the font, never through the segments
        assertFailsWith<IllegalArgumentException> { Money.digits(g, 0, 0, "W", Money.Seg.SMALL) }
    }

    @Test
    fun everySegmentDigitDrawsAndDiffersFromItsNeighbours() {
        val seen = HashMap<String, Int>()
        for (d in 0..9) {
            val g = Gray8(40, 60)
            Money.digits(g, 2, 2, d.toString(), Money.Seg.MEDIUM)
            val key = g.pix.joinToString("") { if (it.toInt() == 0) "0" else "1" }
            assertTrue(g.pix.any { it.toInt() != 0 }, "digit $d drew nothing")
            val prev = seen.put(key, d)
            assertTrue(prev == null, "digits $prev and $d render identically")
        }
    }

    // ================================================================ TableLayout
    @Test
    fun everyHeightRungIsGridLegalAndFitsItsContent() {
        for (h in listOf(288, 352, 416, 480)) {
            val layout = wm.damage.core.geom.Layout(Rect(0, 0, 640, h))
            val t = TableLayout(layout.content, h)
            t.require()
            assertEquals(608, t.board.w, "the canvas gets the full 608 — the 12 px rail is List/Document only")
            // the fans land inside their bands and on the grid
            for (fan in listOf(t.boardFan(5), t.holeFan(2))) {
                for (x in fan.xs) {
                    assertEquals(0, x % 4, "card x $x is off the 4 px grid")
                    assertTrue(x >= t.board.x && x + t.card.w <= t.board.right,
                        "a card at $x escapes the ${t.board.w} px band at h=$h")
                }
            }
            // extra height buys density, never bigger cards below its rung
            assertTrue(t.card.h <= layout.content.h / 2)
        }
        // the ladder is monotone
        val ladder = listOf(288, 352, 416, 480).map { CardArt.Size.forHeight(it) }
        assertEquals(ladder.sortedBy { it.w }, ladder)
        assertEquals(CardArt.Size(48, 66), ladder[0])
        assertEquals(CardArt.Size(72, 100), ladder[3])
    }

    @Test
    fun theTiersTurnFeaturesOnInTheDesignedOrder() {
        fun t(h: Int) = TableLayout(wm.damage.core.geom.Layout(Rect(0, 0, 640, h)).content, h)
        assertTrue(!t(288).showsLastAction && !t(288).showsArc && !t(288).showsHistory)
        assertTrue(t(352).showsLastAction && !t(352).showsArc)
        assertTrue(t(416).showsArc && t(416).showsYourLine && !t(416).showsHistory)
        assertTrue(t(480).showsHistory && t(480).showsChipStacks)
    }

    // ================================================================ CardArt
    @Test
    fun cardsDrawInsideTheirBoxAndTheTwoBodiesDiffer() {
        val tx = FakeText()
        for (s in CardArt.Size.LADDER) {
            val g = Gray8(s.w + 8, s.h + 8)
            CardArt.card(g, tx, 4, 4, s, Card.of("As"))
            // nothing outside the card's own box
            for (y in 0 until g.h) for (x in 0 until g.w) {
                val inside = x in 4 until 4 + s.w && y in 4 until 4 + s.h
                if (!inside) assertEquals(0, g[x, y], "ink at ($x,$y) escaped the ${s.w}x${s.h} card")
            }
            // a black suit is an OUTLINE card, a red suit is FILLED (verdict 7)
            val black = Gray8(s.w, s.h).also { CardArt.card(it, tx, 0, 0, s, Card.of("As")) }
            val red = Gray8(s.w, s.h).also { CardArt.card(it, tx, 0, 0, s, Card.of("Ah")) }
            val blackInk = black.pix.count { it.toInt() != 0 }
            val redInk = red.pix.count { it.toInt() != 0 }
            assertTrue(redInk > blackInk * 2,
                "the filled body must be unmistakable at ${s.w}x${s.h}: black=$blackInk red=$redInk")
            // the body of a black card is see-through in the middle
            assertEquals(0, black[s.w / 2, s.h - s.pad - 2])
        }
    }

    @Test
    fun everySuitPipIsADistinctSilhouette() {
        // the G2CC finding carried forward: suits must read by SHAPE, since
        // red and black both go dark in 16-gray
        val shapes = HashMap<String, Suit>()
        for (suit in Suit.entries) {
            val g = Gray8(40, 40)
            CardArt.pip(g, 2, 2, 36, 36, suit, 255)
            val key = g.pix.joinToString("") { if (it.toInt() == 0) "0" else "1" }
            val prev = shapes.put(key, suit)
            assertTrue(prev == null, "$prev and $suit draw the same silhouette")
            assertTrue(g.pix.count { it.toInt() != 0 } > 100, "$suit's pip is nearly empty")
        }
    }

    @Test
    fun everyRankIndexFitsItsCorner() {
        val tx = FakeText()
        for (s in CardArt.Size.LADDER) for (r in Rank.entries) {
            val g = Gray8(s.w, s.h)
            CardArt.card(g, tx, 0, 0, s, Card(r, Suit.CLUBS))
            // the corner index must stay in its corner: scan the INTERIOR of
            // the top quarter (the border itself reaches the right edge by
            // construction) and require it to end before the card's midline
            var maxX = 0
            for (y in s.stroke until s.h / 4) for (x in s.stroke until s.w - s.stroke) {
                if (g[x, y] != 0) maxX = maxOf(maxX, x)
            }
            assertTrue(maxX <= s.w / 2 + s.pad,
                "${r.label} at ${s.w}x${s.h} overruns its corner (maxX=$maxX, midline=${s.w / 2})")
        }
    }

    @Test
    fun theFanKeepsEveryCornerIndexVisible() {
        val s = CardArt.Size.S480
        // five cards fit outright at 608
        val roomy = HandFan.layout(5, s, 608, 320, 16)
        assertEquals(s.w + 16, roomy.pitch)
        assertTrue(!roomy.overlapped)
        // eight do not, and the pitch shrinks without ever hiding the index
        val tight = HandFan.layout(8, s, 608, 320, 16)
        assertTrue(tight.overlapped)
        assertTrue(tight.pitch >= HandFan.indexWidth(s), "the fan hid a corner index")
        assertEquals(0, tight.pitch % 4)
        for (x in tight.xs) assertEquals(0, x % 4)
    }
}
