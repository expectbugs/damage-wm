package wm.damage.core.windows.games.roster

/**
 * Mood (`HOLDEM.md` §7.2). There is **one** mood value per character, not a
 * mood system and a tilt system — with two readers:
 *
 *  - **In-game it is TILT** — how they play this hand, scaled by `moodiness`
 *    and directed by `tiltSign` (`HoldemBot.modulate`).
 *  - **Between games it is AMBITION** — which table they choose to sit at
 *    (§7.4, `Roster.pickTable`).
 *
 * It moves with results and decays toward baseline. `form` is the slower
 * decayed streak that carries BETWEEN tournaments, so *"Steve's been running
 * hot lately"* is real while traits never drift. **Identity is permanent, mood
 * is not.**
 */
object Mood {

    /** A hand's result nudges mood, scaled by how big it was against the
     *  stack. [delta] is chips won (negative for lost), [stack] the stack it
     *  happened to. */
    fun afterHand(c: Character, delta: Int, stack: Int) {
        if (stack <= 0) return
        val impact = (delta.toDouble() / stack).coerceIn(-1.0, 1.0)
        c.mood = (c.mood * DECAY_HAND + impact * c.traits.moodiness * HAND_WEIGHT).coerceIn(-1.0, 1.0)
    }

    /** A finished tournament moves mood harder and form a little. [place] is
     *  1-based; [of] the field size. */
    fun afterTournament(c: Character, place: Int, of: Int, net: Int) {
        val norm = if (of <= 1) 0.0 else 1.0 - 2.0 * (place - 1).toDouble() / (of - 1)
        c.mood = (c.mood * DECAY_EVENT + norm * c.traits.moodiness * EVENT_WEIGHT).coerceIn(-1.0, 1.0)
        c.form = (c.form * FORM_DECAY + norm * FORM_WEIGHT).coerceIn(-1.0, 1.0)
        if (net != 0) c.career.lifetimeNet += net
    }

    /**
     * 🔑 **Downtime is how mood resets** (§7.3). A high-`moodiness` character
     * hits the ATM and comes straight back — and comes back **still tilted**,
     * because mood carries over a short break. A disciplined one takes a long
     * break and returns at baseline. The trait that brings them back fast is
     * the same trait that makes them lose when they get there, and you can
     * watch the loop run.
     *
     * Returns the number of GAMES they sit out.
     */
    fun downtime(c: Character): Int {
        val t = c.traits
        // impatience is moodiness plus a bad run; discipline is what keeps
        // someone away from the table long enough to cool off
        val impatience = (t.moodiness * 0.6 + (-c.mood).coerceAtLeast(0.0) * 0.4)
        val games = (MAX_DOWNTIME * (1.0 - impatience) * (0.4 + 0.6 * t.discipline)).toInt()
        return games.coerceIn(0, MAX_DOWNTIME)
    }

    /** Mood decays across a break — the shorter the break, the more of it
     *  survives. This is the other half of [downtime]. */
    fun coolOff(c: Character, games: Int) {
        val keep = Math.pow(COOL_PER_GAME, games.toDouble())
        c.mood *= keep
        if (kotlin.math.abs(c.mood) < 0.01) c.mood = 0.0
    }

    private const val DECAY_HAND = 0.93
    private const val HAND_WEIGHT = 0.35
    private const val DECAY_EVENT = 0.55
    private const val EVENT_WEIGHT = 0.75
    private const val FORM_DECAY = 0.82
    private const val FORM_WEIGHT = 0.35
    private const val COOL_PER_GAME = 0.55
    const val MAX_DOWNTIME = 12
}
