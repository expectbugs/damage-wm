package wm.damage.core.windows.games.holdem

import wm.damage.core.windows.games.kit.Money

/**
 * The rule set, implemented from the published authority — Robert's Rules of
 * Poker and the TDA rules (`HOLDEM.md` verdict 36, §12). Prose describes, code
 * runs; where an authority exists this project reads it rather than deriving
 * from memory. What is taken is the RULES TEXT and a test corpus; no
 * third-party implementation enters the repo.
 *
 * This file holds the parts that are pure arithmetic — the blind ladder, the
 * entry fee, minimum bets and raises. The betting state machine is
 * `HoldemTable`; the pot maths is `kit/Pots`.
 */
object HoldemRules {

    /** Verdict 10. */
    const val MAX_SEATS = 6

    /** Verdict 17: escalation is HAND-based, never a clock. With always-on
     *  persistence a timer would escalate while the glasses sit in their case. */
    const val HANDS_PER_LEVEL = 20

    /** Verdict 24: a VISIBLE entry fee, not a hidden rake, and it applies to
     *  Adam too. 5 % of the buy-in, rounded up, with a $1 floor — $1 is the
     *  chip denomination and Unlimited accepts tiny entries. */
    fun fee(entry: Int): Int {
        // Long arithmetic: an absurd entry (a hand-edited bankroll near
        // Int.MAX) overflowed `entry * 5` and came back as a $1 fee
        val f = (entry.toLong() * 5 + 99) / 100
        return f.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** The three tables (§5.2). */
    enum class Table(
        val id: String,
        val label: String,
        /** Fixed entry, or null for "any" (Unlimited). */
        val entry: Int?,
        val baseSb: Int,
    ) {
        REGULAR("regular", "Regular", 200, 1),
        BIG_BOY("bigboy", "Big Boy", 1_000, 1),
        UNLIMITED("unlimited", "Unlimited", null, 5);

        /**
         * The small blind at [level] (0-based, one level per
         * [HANDS_PER_LEVEL] hands). BB is always 2 × SB.
         *
         *  - Regular: `+$1` a level — 1, 2, 3, 4 …
         *  - Big Boy: `+$1` until the SB passes $10, then doubling — 1…10, 20,
         *    40, 80 … which preserves the 500bb-deep early game a $1,000 entry
         *    is for and still finishes (verdict 18).
         *  - Unlimited: doubling from $5 — 5, 10, 20, 40 …
         */
        fun sbAt(level: Int): Int {
            val l = level.coerceAtLeast(0)
            return when (this) {
                REGULAR -> baseSb + l
                BIG_BOY -> if (l < 10) baseSb + l else doubling(10, l - 9)
                UNLIMITED -> doubling(baseSb, l)
            }
        }

        fun bbAt(level: Int): Int = 2 * sbAt(level)

        /** The ladder as text, for the table-select level. */
        fun ladder(): String = (0 until 6).joinToString(" ") { Money.fmt(sbAt(it)) }

        companion object {
            fun byId(s: String): Table? = entries.firstOrNull { it.id == s }
        }
    }

    /** A blind level that keeps doubling forever would overflow Int and the
     *  stacks would go negative in silence. It saturates instead — long before
     *  that any table has one player left. */
    private fun saturatingDouble(v: Int): Int = if (v > Int.MAX_VALUE / 4) v else v * 2

    /** [base] doubled [times], SATURATING and with the loop bounded. 32 rounds
     *  saturate any Int, so a hand-edited `handNo` of two billion asks for a
     *  hundred million iterations inside a paint and gets 32 (review pass 5). */
    private fun doubling(base: Int, times: Int): Int {
        var v = base
        repeat(times.coerceIn(0, 32)) { v = saturatingDouble(v) }
        return v
    }

    fun levelOf(handNo: Int): Int = handNo / HANDS_PER_LEVEL

    /** Hands remaining at this level — the table's "blinds up in N" line. */
    fun handsToNextLevel(handNo: Int): Int = HANDS_PER_LEVEL - handNo % HANDS_PER_LEVEL

    /**
     * §12 item 4, and the one rule most often got wrong: **an all-in for less
     * than a full raise does not reopen the betting.** A player who has
     * already acted and is facing an incomplete all-in may call or fold, but
     * not raise; a player who has not yet acted keeps the full right.
     *
     * [raiseTo] is a total for the street. Returns whether it constitutes a
     * FULL raise, which is what resets everyone else's right to act again.
     */
    fun isFullRaise(raiseTo: Int, currentBet: Int, lastRaiseSize: Int): Boolean =
        raiseTo - currentBet >= lastRaiseSize

    /** The smallest legal raise TOTAL for the street. */
    fun minRaiseTo(currentBet: Int, lastRaiseSize: Int): Int = currentBet + lastRaiseSize

    /** Verdict 15's derivation, kept as one so it stays correct if the
     *  Unlimited blinds ever move: with no table running, the cheapest seat in
     *  the whole game is Unlimited with a tiny stack — its big blind plus the
     *  fee's $1 floor. */
    fun cheapestSeat(): Int = Table.UNLIMITED.bbAt(0) + fee(1)
}

/** The streets of a hand, plus the two terminal states. */
enum class Street(val label: String, val boardCards: Int) {
    PREFLOP("preflop", 0),
    FLOP("flop", 3),
    TURN("turn", 4),
    RIVER("river", 5),
    /** Betting is over; the hand is settled and waiting for a tap (verdict 29). */
    SHOWDOWN("showdown", 5);

    fun next(): Street = when (this) {
        PREFLOP -> FLOP
        FLOP -> TURN
        TURN -> RIVER
        RIVER -> SHOWDOWN
        SHOWDOWN -> SHOWDOWN
    }
}
