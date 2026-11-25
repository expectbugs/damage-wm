package wm.damage.core.app

/**
 * Music's motion update, stage L (`private/build085/MM-lean-spec.md` §4.3): the PC–phone wall-clock difference, read
 * from the S1 link's ping — the PC's clock in its pong, this device's at the ping and the pong. When the PC plays, the
 * phone places the song on the glasses from the PC's `now` extrapolated on its own clock, so this difference is in the
 * picture uncorrected; the line measures it (a correction is built only if it shows more than ≈ 20 ms).
 *
 * One sample is PC time − this device's time at the ping's midpoint, within ± half its round trip; the best of the last
 * [KEEP] (the shortest round trip) is said the first time and whenever it moves by [SAY_MS] or more. One reader (the
 * app lane's reading thread).
 */
class ClockSkew {
    private val recent = ArrayDeque<Pair<Long, Long>>()   // (difference, round trip)
    private var said: Long? = null

    /** The line to journal for a pong carrying [pcMs], its ping sent at [sentMs] and answered at [gotMs] (this device's
     *  clock), or null when there is nothing new to say. */
    fun sample(pcMs: Long, sentMs: Long, gotMs: Long): String? {
        val rtt = gotMs - sentMs
        if (rtt < 0) return null
        recent.addLast(pcMs - (sentMs + rtt / 2) to rtt)
        while (recent.size > KEEP) recent.removeFirst()
        val (best, bestRtt) = recent.minByOrNull { it.second } ?: return null
        val s = said
        if (s != null && kotlin.math.abs(best - s) < SAY_MS) return null
        said = best
        return "clocks: the PC's wall clock is ${if (best >= 0) "+" else ""}$best ms from this device's (± ${bestRtt / 2} ms — the best of the " +
            "last ${recent.size} ping(s), round trip $bestRtt ms); a song the PC plays is placed on the glasses with it uncorrected" +
            if (kotlin.math.abs(best) > CORRECT_MS) " — over the $CORRECT_MS ms past which a correction is owed (MM-lean-spec §4.3)" else ""
    }

    companion object {
        const val KEEP = 8
        const val SAY_MS = 10L
        const val CORRECT_MS = 20L
    }
}
