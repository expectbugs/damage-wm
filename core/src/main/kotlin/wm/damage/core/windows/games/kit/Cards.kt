package wm.damage.core.windows.games.kit

/**
 * The card primitives — `HOLDEM.md` §6. **Nothing in `kit/` knows what Hold'em
 * is**; this is the layer blackjack and the trick-takers reuse (verdict 2).
 *
 * Suit carries `red` because verdict 7 puts the colour on the CARD BODY, not
 * the pip: a black-suit card is an unfilled outline, a red-suit card is a
 * filled mid-grey. Adam: *"A wireframe-esque card vs a filled mid-grey-level
 * card is easy to tell the difference in colour regardless of what is behind
 * the transparency in real life."*
 */
enum class Suit(val code: Char, val red: Boolean, val label: String) {
    SPADES('s', false, "spades"),
    HEARTS('h', true, "hearts"),
    DIAMONDS('d', true, "diamonds"),
    CLUBS('c', false, "clubs"),
}

enum class Rank(val v: Int, val label: String) {
    TWO(2, "2"), THREE(3, "3"), FOUR(4, "4"), FIVE(5, "5"), SIX(6, "6"),
    SEVEN(7, "7"), EIGHT(8, "8"), NINE(9, "9"), TEN(10, "10"),
    JACK(11, "J"), QUEEN(12, "Q"), KING(13, "K"), ACE(14, "A");

    companion object {
        private val byV = entries.associateBy { it.v }
        fun of(v: Int): Rank = byV[v] ?: throw IllegalArgumentException("no rank with value $v")

        /** Accepts the card's own label ("10") and the one-character shorthand
         *  the test corpus and every poker tool write ("T"). The label a card
         *  DRAWS stays "10" — that is what is printed on a real one. */
        fun ofLabel(s: String): Rank? = when (s) {
            "T", "t" -> TEN
            else -> entries.firstOrNull { it.label == s || it.label == s.uppercase() }
        }
    }
}

/** One card. [code] is the two/three-character shorthand the corpus uses ("As", "Td"). */
data class Card(val rank: Rank, val suit: Suit) : Comparable<Card> {
    val code: String get() = rank.label + suit.code

    /** 0..51, rank-major — the deck's ordered index. */
    val index: Int get() = (rank.v - 2) * 4 + suit.ordinal

    override fun compareTo(other: Card): Int = rank.v - other.rank.v
    override fun toString(): String = code

    companion object {
        fun of(code: String): Card {
            require(code.length >= 2) { "card code '$code' is too short" }
            val r = Rank.ofLabel(code.dropLast(1))
                ?: throw IllegalArgumentException("bad rank in card code '$code'")
            val s = Suit.entries.firstOrNull { it.code == code.last() }
                ?: throw IllegalArgumentException("bad suit in card code '$code'")
            return Card(r, s)
        }

        fun ofIndex(i: Int): Card {
            require(i in 0..51) { "card index $i out of range" }
            return Card(Rank.of(i / 4 + 2), Suit.entries[i % 4])
        }
    }
}

/**
 * A deck. The shuffle is SEEDED (§6): persistence and testability both fall
 * out of it — the live table stores `tournamentSeed` and `handNo`, and the
 * exact 52 cards are re-derived every time rather than saved.
 */
object Deck {

    val ordered: List<Card> = (0..51).map { Card.ofIndex(it) }

    /**
     * Fisher–Yates driven by the counter RNG. Deterministic in [seed]; the
     * same seed is the same deck on the phone, the desktop and in a test.
     */
    fun shuffled(seed: Long): List<Card> {
        val a = ordered.toMutableList()
        val rng = Rng.Stream(Rng.mix(seed))
        for (i in a.indices.reversed()) {
            if (i == 0) break
            val j = rng.nextInt(i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        return a
    }
}
