package wm.damage.core.windows.games.kit

/**
 * COUNTER-BASED randomness — `HOLDEM.md` §11.2, and the reason the whole game
 * persists in a few hundred bytes.
 *
 * There is no mutable stream whose position has to be saved. Every random
 * value is a pure function of an explicit key:
 *
 * ```
 * deck(handNo)                  = shuffle(derive(tournamentSeed, handNo))
 * botDecision(seat, decisionNo) = Rng(tournamentSeed, handNo, seat, decisionNo)
 * backgroundGame(n)             = derive(worldSeed, gameNo)
 * ```
 *
 * ⇒ a resumed hand is the SAME hand and a resumed bot is the SAME bot. That is
 * the property the Tmux Focus-mode failure taught this project to demand: a
 * re-roll on resume is a reshuffle wearing the old pot's clothes.
 *
 * The mixer is splitmix64 (Steele/Lea/Flood, public-domain algorithm — the
 * constants are the published ones and the code is ours).
 */
object Rng {

    private const val GOLDEN = -0x61c8864680b583ebL      // 0x9E3779B97F4A7C15
    private const val M1 = -0x40a7b892e31b1a47L          // 0xBF58476D1CE4E5B9
    private const val M2 = -0x6b2fb644ecceee15L          // 0x94D049BB133111EB

    /** One splitmix64 finalizer round. */
    fun mix(x: Long): Long {
        var z = x + GOLDEN
        z = (z xor (z ushr 30)) * M1
        z = (z xor (z ushr 27)) * M2
        return z xor (z ushr 31)
    }

    /**
     * The keyed hash every draw goes through: a 64-bit value that depends on
     * [seed] and every counter, in order. Folding through [mix] between
     * counters (rather than xor-ing them together) means (1,0) and (0,1) are
     * different keys — the collision that would make seat 1's first decision
     * and seat 0's second decision the same roll.
     */
    fun hash(seed: Long, vararg counters: Long): Long {
        var h = mix(seed)
        for (c in counters) h = mix(h xor (c * GOLDEN))
        return h
    }

    /** A uniform double in [0,1). */
    fun double(seed: Long, vararg counters: Long): Double =
        (hash(seed, *counters) ushr 11) * (1.0 / (1L shl 53))

    /** A uniform int in [0,[bound]). */
    fun int(bound: Int, seed: Long, vararg counters: Long): Int {
        require(bound > 0) { "bound must be positive, got $bound" }
        return ((hash(seed, *counters) ushr 1) % bound).toInt()
    }

    /**
     * A re-derivable SEQUENCE from one key. It carries a counter, but nothing
     * about it is ever persisted: rebuild it from the same key and it replays
     * exactly — which is what a Monte-Carlo rollout or a shuffle needs, where
     * one decision draws thousands of values.
     */
    class Stream(private val key: Long) {
        private var i = 0L

        fun nextLong(): Long = Rng.hash(key, i++)

        /** [0,1). */
        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

        /** [0,[bound]) — the modulo bias at these bounds (≤ 52 cards, ≤ 1000
         *  weights) is ~2^-58 and irrelevant; stated rather than hidden. */
        fun nextInt(bound: Int): Int {
            require(bound > 0) { "bound must be positive, got $bound" }
            return ((nextLong() ushr 1) % bound).toInt()
        }

        /** A gaussian-ish value in [-1,1], mean 0 — three draws averaged. It is
         *  not a true normal and does not pretend to be; it is the shape the
         *  trait jitter wants (clustered around 0, bounded). */
        fun nextNoise(): Double = (nextDouble() + nextDouble() + nextDouble()) / 1.5 - 1.0
    }

    /** The stream for a key tuple. */
    fun stream(seed: Long, vararg counters: Long): Stream = Stream(hash(seed, *counters))
}
