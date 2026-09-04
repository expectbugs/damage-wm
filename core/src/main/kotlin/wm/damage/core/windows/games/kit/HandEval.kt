package wm.damage.core.windows.games.kit

/**
 * Poker hand evaluation — 5-from-7, shared by every poker variant
 * (`HOLDEM.md` §6). Direct evaluation from rank/suit counts rather than the
 * 21-combination walk: a Monte-Carlo decision runs thousands of these per
 * bot action (§8), so the inner loop matters.
 *
 * The score is a single comparable Int, larger is better:
 *
 * ```
 * [category 4 bits][k1 4][k2 4][k3 4][k4 4][k5 4]      = 24 bits
 * ```
 *
 * Kickers are rank VALUES (2..14), most significant first, padded with 0 where
 * a category needs fewer than five. Two hands compare with `<`; equality is a
 * genuine tie and splits the pot (`Pots`).
 */
object HandEval {

    enum class Category(val label: String) {
        HIGH_CARD("high card"), PAIR("a pair"), TWO_PAIR("two pair"),
        TRIPS("three of a kind"), STRAIGHT("a straight"), FLUSH("a flush"),
        FULL_HOUSE("a full house"), QUADS("four of a kind"), STRAIGHT_FLUSH("a straight flush");
    }

    /** The five ranks a category is padded to. */
    private const val K = 5

    fun score(cards: List<Card>): Int {
        require(cards.size in 5..7) { "hand evaluation needs 5..7 cards, got ${cards.size}" }
        val rankCount = IntArray(15)
        val suitCount = IntArray(4)
        // per suit, the bitmask of ranks present (bit r = rank value r)
        val suitMask = IntArray(4)
        var mask = 0
        for (c in cards) {
            rankCount[c.rank.v]++
            suitCount[c.suit.ordinal]++
            suitMask[c.suit.ordinal] = suitMask[c.suit.ordinal] or (1 shl c.rank.v)
            mask = mask or (1 shl c.rank.v)
        }

        val flushSuit = (0..3).firstOrNull { suitCount[it] >= 5 }
        if (flushSuit != null) {
            val fm = suitMask[flushSuit]
            val sfHigh = straightHigh(fm)
            if (sfHigh > 0) return pack(Category.STRAIGHT_FLUSH, sfHigh)
        }

        // rank multiplicities, high to low
        var quad = 0
        val trips = ArrayList<Int>(2)
        val pairs = ArrayList<Int>(3)
        for (v in 14 downTo 2) when (rankCount[v]) {
            4 -> if (quad == 0) quad = v
            3 -> trips.add(v)
            2 -> pairs.add(v)
        }

        if (quad != 0) {
            val kicker = (14 downTo 2).firstOrNull { it != quad && rankCount[it] > 0 } ?: 0
            return pack(Category.QUADS, quad, kicker)
        }
        if (trips.isNotEmpty() && (trips.size > 1 || pairs.isNotEmpty())) {
            val three = trips[0]
            val pair = if (trips.size > 1) maxOf(trips[1], pairs.firstOrNull() ?: 0) else pairs[0]
            return pack(Category.FULL_HOUSE, three, pair)
        }
        if (flushSuit != null) {
            val top = topRanks(suitMask[flushSuit], 5)
            return pack(Category.FLUSH, *top)
        }
        val stHigh = straightHigh(mask)
        if (stHigh > 0) return pack(Category.STRAIGHT, stHigh)
        if (trips.isNotEmpty()) {
            val three = trips[0]
            val ks = topRanksExcluding(rankCount, 2, three)
            return pack(Category.TRIPS, three, *ks)
        }
        if (pairs.size >= 2) {
            val hi = pairs[0]
            val lo = pairs[1]
            val kicker = (14 downTo 2).firstOrNull { it != hi && it != lo && rankCount[it] > 0 } ?: 0
            return pack(Category.TWO_PAIR, hi, lo, kicker)
        }
        if (pairs.size == 1) {
            val p = pairs[0]
            val ks = topRanksExcluding(rankCount, 3, p)
            return pack(Category.PAIR, p, *ks)
        }
        return pack(Category.HIGH_CARD, *topRanks(mask, 5))
    }

    /** The high card of the best straight in [mask], or 0. Ace plays low for
     *  the wheel (A-2-3-4-5), which scores as a FIVE-high straight. */
    private fun straightHigh(mask: Int): Int {
        // the wheel: treat the ace as a 1 by copying bit 14 down to bit 1
        val m = if (mask and (1 shl 14) != 0) mask or 2 else mask
        for (high in 14 downTo 5) {
            var all = true
            for (d in 0 until 5) if (m and (1 shl (high - d)) == 0) { all = false; break }
            if (all) return high
        }
        return 0
    }

    private fun topRanks(mask: Int, n: Int): IntArray {
        val out = IntArray(n)
        var i = 0
        for (v in 14 downTo 2) {
            if (i == n) break
            if (mask and (1 shl v) != 0) out[i++] = v
        }
        return out
    }

    private fun topRanksExcluding(rankCount: IntArray, n: Int, vararg exclude: Int): IntArray {
        val out = IntArray(n)
        var i = 0
        for (v in 14 downTo 2) {
            if (i == n) break
            if (v in exclude || rankCount[v] == 0) continue
            out[i++] = v
        }
        return out
    }

    private fun pack(cat: Category, vararg kickers: Int): Int {
        require(kickers.size <= K) { "at most $K kickers, got ${kickers.size}" }
        var v = cat.ordinal
        for (i in 0 until K) {
            v = (v shl 4) or (kickers.getOrElse(i) { 0 })
        }
        return v
    }

    fun categoryOf(score: Int): Category = Category.entries[(score ushr (4 * K)) and 0xF]

    fun kickersOf(score: Int): List<Int> = (K - 1 downTo 0).map { (score ushr (4 * it)) and 0xF }

    /** A short human phrase for the showdown line ("a flush, ace high"). */
    fun describe(score: Int): String {
        val cat = categoryOf(score)
        val k = kickersOf(score)
        fun name(v: Int) = if (v == 0) "" else Rank.of(v).let {
            when (it) {
                Rank.ACE -> "ace"; Rank.KING -> "king"; Rank.QUEEN -> "queen"; Rank.JACK -> "jack"
                else -> it.label
            }
        }
        return when (cat) {
            HandEval.Category.STRAIGHT_FLUSH ->
                if (k[0] == 14) "a royal flush" else "a straight flush, ${name(k[0])} high"
            HandEval.Category.QUADS -> "four ${name(k[0])}s"
            HandEval.Category.FULL_HOUSE -> "${name(k[0])}s full of ${name(k[1])}s"
            HandEval.Category.FLUSH -> "a flush, ${name(k[0])} high"
            HandEval.Category.STRAIGHT -> "a straight, ${name(k[0])} high"
            HandEval.Category.TRIPS -> "three ${name(k[0])}s"
            HandEval.Category.TWO_PAIR -> "${name(k[0])}s and ${name(k[1])}s"
            HandEval.Category.PAIR -> "a pair of ${name(k[0])}s"
            HandEval.Category.HIGH_CARD -> "${name(k[0])} high"
        }
    }
}
