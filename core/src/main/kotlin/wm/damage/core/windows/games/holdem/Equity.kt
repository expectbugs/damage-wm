package wm.damage.core.windows.games.holdem

import wm.damage.core.windows.games.kit.Card
import wm.damage.core.windows.games.kit.Deck
import wm.damage.core.windows.games.kit.HandEval
import wm.damage.core.windows.games.kit.Rank
import wm.damage.core.windows.games.kit.Rng
import wm.damage.core.windows.games.kit.Suit

/**
 * Monte-Carlo equity (`HOLDEM.md` §8): the chance this hand is the best one at
 * showdown against [opponents] random holdings, given the board so far.
 *
 * Two fidelities, exactly as the design allows: the LIVE table runs the full
 * rollout count, and BACKGROUND games (§7.5) run a cheap one — *"fidelity drops
 * only for games nobody sees."* Preflop the cheap mode reads a lazily-built
 * table over the 169 hand classes instead of rolling out at all, which is what
 * makes 2–3 background tournaments per hand of Adam's affordable.
 *
 * Determinism is the caller's: every estimate takes an [Rng.Stream] keyed by
 * (tournamentSeed, handNo, seat, decisionNo), so a resumed decision is
 * bit-identical (§11.2).
 */
object Equity {

    const val LIVE_ROLLOUTS = 2_000
    const val CHEAP_ROLLOUTS = 200
    private const val TABLE_ROLLOUTS = 600

    /**
     * The share of the pot this hand expects at showdown: a win counts 1, an
     * n-way tie counts 1/n. Ties matter — treating a chop as a loss makes
     * every bot fold too much on a paired board.
     */
    fun estimate(hole: List<Card>, board: List<Card>, opponents: Int, rollouts: Int,
        rng: Rng.Stream): Double {
        require(hole.size == 2) { "hold'em hole cards come in twos, got ${hole.size}" }
        if (opponents <= 0) return 1.0
        if (board.isEmpty() && rollouts <= CHEAP_ROLLOUTS) return preflop(hole, opponents)

        val known = HashSet<Int>(hole.size + board.size)
        for (c in hole) known.add(c.index)
        for (c in board) known.add(c.index)
        val rest = ArrayList<Card>(52 - known.size)
        for (c in Deck.ordered) if (c.index !in known) rest.add(c)

        val need = 5 - board.size                       // board cards still to come
        val draw = need + 2 * opponents
        require(draw <= rest.size) { "not enough cards left for $opponents opponents" }

        val pool = rest.toTypedArray()
        var share = 0.0
        val mine = ArrayList<Card>(7)
        val theirs = ArrayList<Card>(7)
        repeat(rollouts) {
            // partial Fisher-Yates over the pool: only the first `draw` cards
            // are needed, and shuffling the whole 45 every rollout is the
            // difference between 30 ms and 300 ms a decision
            for (i in 0 until draw) {
                val j = i + rng.nextInt(pool.size - i)
                val t = pool[i]; pool[i] = pool[j]; pool[j] = t
            }
            mine.clear()
            mine.addAll(hole)
            mine.addAll(board)
            for (i in 0 until need) mine.add(pool[i])
            val my = HandEval.score(mine)
            var better = 0
            var equal = 0
            for (o in 0 until opponents) {
                theirs.clear()
                theirs.add(pool[need + 2 * o])
                theirs.add(pool[need + 2 * o + 1])
                theirs.addAll(board)
                for (i in 0 until need) theirs.add(pool[i])
                val s = HandEval.score(theirs)
                if (s > my) { better++; break }
                if (s == my) equal++
            }
            if (better == 0) share += 1.0 / (1 + equal)
        }
        return share / rollouts
    }

    /** The 169 preflop classes: "AKs", "AKo", "77". */
    fun handClass(hole: List<Card>): String {
        val a = hole.maxByOrNull { it.rank.v }!!
        val b = hole.minByOrNull { it.rank.v }!!
        val suited = if (a.suit == b.suit) "s" else "o"
        return if (a.rank == b.rank) "${a.rank.label}${b.rank.label}"
        else "${a.rank.label}${b.rank.label}$suited"
    }

    private val table = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /**
     * Cached preflop equity for a hand class against [opponents] random hands.
     * Built ON DEMAND — 169 × 5 entries computed eagerly would cost more than
     * the whole background economy it exists to make cheap — and cached for
     * the life of the process. Deterministic in the class, so two hosts agree.
     */
    fun preflop(hole: List<Card>, opponents: Int): Double {
        val key = "${handClass(hole)}/$opponents"
        table[key]?.let { return it }
        // a representative pair of that class, and a FIXED key: the same class
        // always yields the same number, on the phone and on the desktop
        val rep = representative(handClass(hole))
        val v = estimate(rep, emptyList(), opponents, TABLE_ROLLOUTS,
            Rng.stream(PREFLOP_SEED, key.hashCode().toLong()))
        table[key] = v
        return v
    }

    /** Test/harness reach: how many classes have been computed so far. */
    fun preflopCacheSize(): Int = table.size

    /** A concrete pair standing for a hand class. The class string is walked
     *  rank-label first so "10" never reads as a "1", and the trailing `s`/`o`
     *  decides the suits. */
    internal fun representative(cls: String): List<Card> {
        val ranks = ArrayList<Rank>(2)
        var i = 0
        while (i < cls.length && ranks.size < 2) {
            if (cls[i] == 's' || cls[i] == 'o') break
            val two = if (i + 2 <= cls.length) Rank.ofLabel(cls.substring(i, i + 2)) else null
            if (two != null) { ranks.add(two); i += 2; continue }
            val one = Rank.ofLabel(cls.substring(i, i + 1))
                ?: throw IllegalArgumentException("unparsable hand class '$cls' at $i")
            ranks.add(one)
            i += 1
        }
        require(ranks.size == 2) { "hand class '$cls' does not name two ranks" }
        val suited = cls.endsWith("s")
        return listOf(Card(ranks[0], Suit.SPADES),
            Card(ranks[1], if (suited) Suit.SPADES else Suit.HEARTS))
    }

    private const val PREFLOP_SEED = 0x1EAF_C0DEL
}
