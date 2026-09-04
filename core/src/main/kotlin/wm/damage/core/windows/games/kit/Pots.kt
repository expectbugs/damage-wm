package wm.damage.core.windows.games.kit

/**
 * Pot construction and settlement — `HOLDEM.md` §12, implemented from the
 * published rules (Robert's Rules of Poker; the TDA rules agree) rather than
 * from memory, and differential-tested against an independent MIT
 * implementation whose verdicts are committed as a corpus we own
 * (`core/src/test/resources/holdem/sidepots.json`, §13.2). No third-party code
 * is in the repo — the clean-room section of `CLAUDE.md` governs.
 *
 * The four places implementations actually go wrong, each handled here and
 * each with its own test:
 *
 * 1. 🔴 **Folded players' chips still form pots.** Counting only live players
 *    is the classic defect — a folded player's dead money is in the pot.
 * 2. 🔴 **The uncalled portion of a bet returns BEFORE pots are formed.** Bet
 *    $100, one caller for $30, $70 comes back. The most common real bug.
 * 3. **Odd chips on a split** go to the first live player clockwise from the
 *    button, one chip at a time.
 * 4. An all-in for less than a full raise does not reopen the betting — that
 *    is a betting rule and lives in `HoldemRules`, not here.
 */
object Pots {

    /** One player's total contribution to THIS hand. */
    data class Entry(val seat: Int, val amount: Int, val folded: Boolean)

    /** [contenders] are seats eligible to win this pot: unfolded, and in for
     *  at least this level. */
    data class SidePot(val amount: Int, val contenders: List<Int>)

    /** What the uncalled-bet return moves, before any pot is built. */
    data class Uncalled(val seat: Int, val amount: Int)

    /**
     * Rule 2. The largest total contribution beyond the second largest was
     * never called by anybody, so it comes back. Returns null when the top two
     * are level (the normal case).
     */
    fun uncalled(entries: List<Entry>): Uncalled? {
        if (entries.size < 2) {
            val only = entries.firstOrNull() ?: return null
            return if (only.amount > 0) Uncalled(only.seat, only.amount) else null
        }
        val sorted = entries.sortedByDescending { it.amount }
        val top = sorted[0]
        val second = sorted[1].amount
        // a TIE at the top means the bet was called — nothing to return
        if (top.amount <= second) return null
        return Uncalled(top.seat, top.amount - second)
    }

    /**
     * Rule 1 + the side-pot construction (§12). [entries] must already have
     * had [uncalled] applied — build() takes contributions at face value.
     *
     * ```
     * levels = sorted distinct contributions, capped at the maximum
     * for each level, from prev:
     *     pot        = Σ over ALL players of (min(c, level) − min(c, prev))
     *     contenders = unfolded players whose contribution ≥ level
     * ```
     */
    fun build(entries: List<Entry>): List<SidePot> {
        val live = entries.filter { it.amount > 0 }
        if (live.isEmpty()) return emptyList()
        val levels = live.map { it.amount }.distinct().sorted()
        val out = ArrayList<SidePot>(levels.size)
        var prev = 0
        for (level in levels) {
            var amount = 0
            for (e in live) amount += minOf(e.amount, level) - minOf(e.amount, prev)
            val contenders = live.filter { !it.folded && it.amount >= level }.map { it.seat }
            if (amount > 0) {
                if (contenders.isEmpty()) {
                    // every contender to this level folded: the money is real
                    // and must not vanish — it rides into the previous pot,
                    // whose contenders were in for less. With no previous pot
                    // it means everyone folded, which the caller settles as an
                    // uncontested win before ever reaching here.
                    if (out.isNotEmpty()) {
                        val last = out.removeAt(out.size - 1)
                        out.add(SidePot(last.amount + amount, last.contenders))
                    } else {
                        out.add(SidePot(amount, live.map { it.seat }))
                    }
                } else {
                    out.add(SidePot(amount, contenders))
                }
            }
            prev = level
        }
        // merge adjacent pots with the SAME contender set — three players all
        // in for different amounts where the middle one folded produces two
        // pots nobody can tell apart
        val merged = ArrayList<SidePot>(out.size)
        for (p in out) {
            val last = merged.lastOrNull()
            if (last != null && last.contenders == p.contenders) {
                merged[merged.size - 1] = SidePot(last.amount + p.amount, last.contenders)
            } else merged.add(p)
        }
        return merged
    }

    /**
     * Settle [pots] against showdown [scores] (seat → [HandEval] score; a seat
     * absent from the map cannot win). [order] is the seat order clockwise
     * from the button — the FIRST entry is the seat immediately left of the
     * button, which is where odd chips start (rule 3).
     *
     * Returns seat → chips won. Total chips out always equals total chips in.
     */
    fun settle(pots: List<SidePot>, scores: Map<Int, Int>, order: List<Int>): Map<Int, Int> {
        val won = HashMap<Int, Int>()
        for (p in pots) {
            val eligible = p.contenders.filter { scores.containsKey(it) }
            if (eligible.isEmpty()) {
                // no contender showed a hand: the pot cannot be awarded here.
                // Loud rather than silently dropped — this is a caller bug.
                throw IllegalStateException(
                    "side pot of ${p.amount} has no contender with a score " +
                        "(contenders=${p.contenders}, scored=${scores.keys})")
            }
            val best = eligible.maxOf { scores.getValue(it) }
            val winners = eligible.filter { scores.getValue(it) == best }
            val share = p.amount / winners.size
            var odd = p.amount - share * winners.size
            for (w in winners) won[w] = (won[w] ?: 0) + share
            // rule 3: odd chips one at a time, first live player clockwise
            // from the button. Winners not in `order` (a caller that passed a
            // partial order) sort last, deterministically by seat.
            val byPosition = winners.sortedWith(
                compareBy({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
            var i = 0
            while (odd > 0) {
                val w = byPosition[i % byPosition.size]
                won[w] = (won[w] ?: 0) + 1
                odd--
                i++
            }
        }
        return won
    }

    /** The whole settlement in one call: return the uncalled bet, build the
     *  pots, award them. [returns] receives the uncalled amount separately so
     *  the caller can show it ("$70 back"). */
    data class Settlement(val uncalled: Uncalled?, val pots: List<SidePot>, val won: Map<Int, Int>) {
        val total: Int get() = pots.sumOf { it.amount }
    }

    fun settleHand(entries: List<Entry>, scores: Map<Int, Int>, order: List<Int>): Settlement {
        val back = uncalled(entries)
        val adjusted = if (back == null) entries
        else entries.map { if (it.seat == back.seat) it.copy(amount = it.amount - back.amount) else it }
        val pots = build(adjusted)
        val won = settle(pots, scores, order)
        return Settlement(back, pots, won)
    }
}
