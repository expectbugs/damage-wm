package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wm.damage.core.geom.Layout
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.windows.games.holdem.HoldemRules
import wm.damage.core.windows.games.holdem.HoldemTable
import wm.damage.core.windows.games.kit.ActionLevel
import wm.damage.core.windows.games.kit.CardArt
import wm.damage.core.windows.games.kit.Money
import wm.damage.core.windows.games.kit.Seats
import wm.damage.core.windows.games.kit.Suit
import wm.damage.core.windows.games.roster.Background
import wm.damage.core.windows.games.roster.Character
import wm.damage.core.windows.games.roster.Roster

/**
 * The FIRST LIVE SESSION of the Games window (2026-09-04): the whole stack
 * running under the byte-exact simulator, driven over the browser replica the
 * way a user drives the ring. Every test here reproduces something that was
 * only visible on a real render at true 1× — the kind of defect a unit test
 * cannot see because nothing it asserts is wrong.
 */
class GamesLive20260904Test {

    // ================================================================ L15
    @Test
    fun everySuitPipIsOneConnectedShape() {
        // the spade and club stems were drawn from the pip's baseline upward
        // by h/5 while the leaf/lobes ended at 0.82h and 0.86h; integer
        // truncation opened a one-row gap and the stem read as a second blob
        // sitting under the pip at EVERY ladder rung.
        for (s in CardArt.Size.LADDER) {
            for (px in listOf(s.bigPip, s.pipPx)) {
                for (suit in Suit.entries) {
                    val g = Gray8(px + 4, px + 4)
                    CardArt.pip(g, 2, 2, px, px, suit, Level.HEAD)
                    assertEquals(1, components(g),
                        "$suit at ${px}px draws ${components(g)} pieces — a pip is one shape")
                }
            }
        }
    }

    @Test
    fun theConnectivityDetectorIsNotVacuous() {
        // a detector that always answers 1 would have passed the defect it was
        // written for
        val g = Gray8(20, 20)
        g.fillRect(2, 2, 4, 4, Level.HEAD)
        assertEquals(1, components(g))
        g.fillRect(12, 12, 4, 4, Level.HEAD)
        assertEquals(2, components(g), "two separated blobs must read as two")
    }

    /** 4-connected components of the lit pixels. */
    private fun components(g: Gray8): Int {
        val seen = Array(g.h) { BooleanArray(g.w) }
        var n = 0
        for (y in 0 until g.h) for (x in 0 until g.w) {
            if (g[x, y] == 0 || seen[y][x]) continue
            n++
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(x to y); seen[y][x] = true
            while (stack.isNotEmpty()) {
                val (cx, cy) = stack.removeLast()
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= g.w || ny >= g.h) continue
                    if (seen[ny][nx] || g[nx, ny] == 0) continue
                    seen[ny][nx] = true
                    stack.addLast(nx to ny)
                }
            }
        }
        return n
    }

    // ================================================================ L16
    @Test
    fun aDrawnChipStackNeverReadsAsPunctuation() {
        // one square bar in front of the word "pot" read as an em-dash and two
        // read as an equals sign between two amounts. The stack is now round
        // chips overlapping by a pixel: two is the floor, and the column is
        // ONE connected shape at every count, which is what stops it looking
        // like two bars with a gap.
        for (amount in listOf(1, 2, 3, 5, 40, 4_000, 400_000)) {
            val g = Gray8(24, 40)
            val h = Money.chipStack(g, 2, 34, 9, amount, 2, Level.MID)
            assertTrue(h >= Money.CHIP_H + Money.CHIP_PITCH,
                "an amount of $amount drew a single chip ($h px)")
            assertTrue(h <= 4 * Money.CHIP_PITCH + Money.CHIP_H,
                "an amount of $amount drew past the five-chip cap ($h px)")
            assertEquals(1, components(g),
                "the stack for $amount is ${components(g)} pieces — chips must overlap")
        }
        val g = Gray8(24, 40)
        assertEquals(0, Money.chipStack(g, 2, 34, 9, 0, 2, Level.MID),
            "nothing in front of a seat draws nothing")
        assertEquals(0, components(g))
        // the per-seat call caps lower so the stack cannot climb into the name
        assertTrue(Money.chipStack(g, 2, 34, 9, 400_000, 2, Level.MID, maxChips = 3) <=
            2 * Money.CHIP_PITCH + Money.CHIP_H)
    }

    // ================================================================ L17
    @Test
    fun theOpponentStripReadsFromYourLeft() {
        // raw seat-index order put the two blinds at opposite ends of the
        // strip on most hands; the strip now starts at the seat on your left,
        // which is also the order they act in.
        val seats = (0 until 6).map {
            Seats.Seat(index = it, who = Seats.Occupant("c$it", "C$it", human = false), stack = 200)
        }
        assertEquals(listOf(3, 4, 5, 0, 1), Seats.strip(seats, 2).map { it.index })
        assertEquals(listOf(1, 2, 3, 4, 5), Seats.strip(seats, 0).map { it.index })
        // a busted seat keeps its cell: the others must not shuffle along
        val out = seats.toMutableList()
        out[4] = out[4].copy(busted = true)
        assertEquals(listOf(3, 4, 5, 0, 1), Seats.strip(out, 2).map { it.index })
        // and the viewer is never in their own strip
        for (v in 0 until 6) assertTrue(Seats.strip(seats, v).none { it.index == v })
        assertEquals(emptyList(), Seats.strip(seats.take(1), 0))
    }

    // ================================================================ R3
    /**
     * A tournament that did NOT resolve must still settle to exactly one
     * winner. `finishPlace(seat) ?: 1` gave first place — and the whole prize
     * — to every seat still in, so a table that stopped early paid the pot to
     * each of them and recorded a win for each (review pass 3, 2026-09-04).
     */
    @Test
    fun anUnresolvedTableStillPaysExactlyOneWinner() {
        val roster = Roster(worldSeed = 99, gameNo = 3)
        val cast = HashMap<Int, Character>()
        val occ = ArrayList<Seats.Occupant>()
        for (i in 0 until 4) {
            val c = Character("c$i", "C$i", Character.Traits.load(null), 1_000, 3)
            roster.put(c)
            cast[i] = c
            occ.add(Seats.Occupant(c.id, c.name, human = false))
        }
        // a table nobody has played a hand of: every seat is still in
        val table = HoldemTable.start(HoldemRules.Table.REGULAR, 7L, occ,
            intArrayOf(200, 200, 200, 200))
        // only the CAST is measured: settle also runs the roster's own births
        // and returns, which are the economy's designed money sources
        val before = cast.values.sumOf { it.bankroll }
        val s = Background.settle(roster, table, cast, HoldemRules.Table.REGULAR,
            prize = 800, fees = 40, hands = 0)
        assertEquals(1, s.places.values.count { it == 1 },
            "exactly one first place, whatever state the table stopped in: ${s.places}")
        assertEquals(before + 800, cast.values.sumOf { it.bankroll },
            "the prize moved exactly once — no seat printed a second copy of it")
        assertEquals(1, cast.values.count { it.career.wins == 1 },
            "one win recorded, not four")
        // and the places are a permutation of 1..4, so no two seats tie
        assertEquals((1..4).toList(), s.places.values.sorted())
    }

    // ================================================================ R4
    /**
     * The hand history and the result line are SENTENCES, and Adam's seat is
     * the second person. Off the glass they read "You checks", "You folds" and
     * "You wins $412" (review pass 4, 2026-09-04) — and the bots' money lines
     * read as headlines ("Rex G. bet $6") rather than as history.
     */
    @Test
    fun theHandHistoryIsWrittenInTheRightPerson() {
        val you = Seats.Occupant("you", "You", human = true)
        val bot = Seats.Occupant("c1", "Rex G.", human = false)
        val t = HoldemTable.start(HoldemRules.Table.REGULAR, 5L, listOf(you, bot),
            intArrayOf(200, 200))
        // check/call the hand down until BOTH seats have spoken on a street
        // where the human checks — heads-up that is the flop at the latest
        var guard = 0
        while (t.view().toAct != null && guard++ < 24) {
            t.act(if (t.view().toCall > 0) ActionLevel.Kind.CALL else ActionLevel.Kind.CHECK)
        }
        val h = t.view().history
        assertTrue(h.any { it.contains("You check") },
            "the human never checked, so the person is untested: $h")
        assertTrue(h.any { it.contains("Rex G. checks") || it.contains("Rex G. calls") },
            "the bot never spoke, so the third person is untested: $h")
        for (line in h) {
            assertFalse(line.contains("You checks") || line.contains("You folds") ||
                line.contains("You calls") || line.contains("You wins") ||
                line.contains("You raises") || line.contains("You bets"),
                "the human takes the second person: '$line'")
            if (line.contains("Rex G.")) assertTrue(
                Regex("Rex G\\. (checks|folds|calls|raises to|bets|is all-in for)").containsMatchIn(line),
                "a bot takes the third person: '$line'")
        }
    }

    // ================================================================ L24
    @Test
    fun theStandingsLensHasRoomForItsThreeLines() {
        // the lens is 64 px with a 2 px rule top and bottom; three lines on a
        // hand-picked 16 px step overlapped each other and ran into the rule.
        // The painter now stacks by MEASURED line heights — this pins that a
        // reasonable ladder actually fits, so nothing is silently dropped.
        val tx = FakeText()
        val h = tx.metrics(wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18, bold = true)).lineHeight +
            tx.metrics(wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 15)).lineHeight +
            tx.metrics(wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 13)).lineHeight
        assertTrue(h + 4 <= Layout.LENS_H,
            "the three lens lines measure $h px in a ${Layout.LENS_H} px lens")
    }
}
