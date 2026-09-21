package wm.damage.core.shell

/**
 * The status cell's vocabulary and the fault episodes behind its notices
 * (`DESIGN.md` §4.4, `HANDOFF.md` §66.2 and §69 — Adam's ruling, 2026-09-16:
 * "errors should come up as a notification, not a truncated status bar line
 * that hides inputs and can't be cleared").
 *
 * The cell shows a STATE: one word from a fixed list, DERIVED on every chrome
 * sync from conditions that each have their own lifecycle — never a stored
 * string, so nothing has to clear it and one state's exit cannot clear
 * another's word. Every EVENT is a notice instead ([FaultEpisodes]).
 */
enum class ShellState(val word: String) {
    // in priority order: the first that holds is the word
    NO_LINK("no link"),
    SILENT("silent"),
    REBUILDING("rebuild"),
    NO_LEASE("no lease"),
    HALTED("halted"),
    FAULT("fault"),
    LINK_SLOW("link slow"),
    OK("ok");

    companion object {
        /** The conditions are read ONCE by the caller (one link-state snapshot
         *  per chrome sync) and handed in; this only ranks them. */
        fun of(connected: Boolean, silent: Boolean, rebuilding: Boolean, leaseHeld: Boolean,
               halted: Boolean, faultOpen: Boolean, linkSlow: Boolean): ShellState = when {
            !connected -> NO_LINK
            silent -> SILENT               // the lease is released on purpose (§36): never "no lease"
            rebuilding -> REBUILDING
            !leaseHeld -> NO_LEASE
            halted -> HALTED
            faultOpen -> FAULT
            linkSlow -> LINK_SLOW
            else -> OK
        }

        val WORDS: List<String> = entries.map { it.word }
    }
}

/** The input echo's vocabulary (§9.2). Fixed, so the status cell can be
 *  MEASURED for it (`Chrome.statusCellWidth`) and no echo is ever cut.
 *
 *  The words are SHORT on purpose (Adam, 2026-09-20, from the real rasterizer's
 *  numbers, `HANDOFF.md` §69): `rebuild`, `dbl` and `rel` keep the cell at 140 px
 *  in Clear Sans 16 (132 was the design's, the op cell gives the 8) and inside
 *  the bar in all four faces at every chrome size; `rebuilding`, `double` and
 *  `release` made it 164 and left JetBrains Mono at 120 % no room at all. */
object InputEcho {
    const val TAP = "tap"
    const val DOUBLE = "dbl"
    const val UP = "up"
    const val DOWN = "down"
    const val HOLD = "hold"
    const val RELEASE = "rel"

    /** An event type the shell has no word for: two digits at most, so the
     *  widest form is known ([UNKNOWN_FORMS] is what the cell is measured for). */
    fun unknown(type: Int): String = if (type in 0..99) "ev$type" else "ev??"

    val WORDS = listOf(TAP, DOUBLE, UP, DOWN, HOLD, RELEASE)
    /** Every shape [unknown] can take, for the measure. */
    val UNKNOWN_FORMS: List<String> = (0..9).map { "ev$it$it" } + "ev??"
}

/**
 * One notice per fault class per EPISODE.
 *
 * An episode opens at a class's first occurrence and closes once the class
 * has been quiet for [quietMs] (a test may shorten it, like the shell's
 * other pacing knobs). Inside an episode a repeat never posts a new notice:
 * it updates the one box's count at ×2, ×4, ×8 … (a fault on every
 * pump costs ~ten repaints per thousand occurrences, and chrome-sized ones),
 * the phone hears an urgent class ONCE, and a box Adam dismissed stays
 * dismissed until a new episode ([mute]).
 *
 * The envelope (`FORK.md` §3.8): (a) [clear] at every shell start, a quiet
 * period closes the rest; (b) no wait — the close is read from timestamps on
 * the pump, a pacing decision; (c) keyed on the CLASS, never the text (an
 * exception's message differs per occurrence); (d) loop-only, like every
 * other piece of shell state; (f) every fault site goes through
 * `Shell.raiseFault`, the only caller.
 */
class FaultEpisodes(private val quietMs: () -> Long = { QUIET_MS }) {

    enum class Action { POST, UPDATE, NONE }

    /** What the shell does with one occurrence. [ring] = tell the phone now
     *  (the first urgent occurrence of the episode). */
    data class Verdict(val action: Action, val count: Int, val ring: Boolean)

    private class Episode(var lastAt: Long, var count: Int, var rung: Boolean, var muted: Boolean = false)

    private val open = HashMap<String, Episode>()

    fun raise(cls: String, urgent: Boolean, nowMs: Long): Verdict {
        val e = open[cls]
        if (e == null || nowMs - e.lastAt >= quietMs()) {
            open[cls] = Episode(nowMs, 1, urgent)
            return Verdict(Action.POST, 1, ring = urgent)
        }
        e.lastAt = nowMs
        e.count++
        val ring = urgent && !e.rung
        if (ring) e.rung = true
        val milestone = e.count and (e.count - 1) == 0          // a power of two
        return Verdict(if (milestone && !e.muted) Action.UPDATE else Action.NONE, e.count, ring)
    }

    /** Adam dismissed [cls]'s box: the rest of this episode updates nothing on the glasses
     *  (the journal and the log still take every occurrence). A new episode is news again. */
    fun mute(cls: String) { open[cls]?.muted = true }

    /** Close every episode quiet for [quietMs]; true when any is still open. */
    fun anyOpen(nowMs: Long): Boolean {
        open.values.removeAll { nowMs - it.lastAt >= quietMs() }
        return open.isNotEmpty()
    }

    /** When the oldest open episode may close — the pump's next look; null with none open. */
    fun nextCloseAt(): Long? = open.values.minOfOrNull { it.lastAt + quietMs() }

    fun clear() = open.clear()

    companion object {
        const val QUIET_MS = 60_000L
    }
}
