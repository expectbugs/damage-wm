package wm.damage.core.shell

/**
 * Low glasses battery, said once per discharge (`DESIGN.md` §9.3; `HANDOFF.md` §75.4 calls 1-3; Adam's
 * ruling of 2026-09-21: below 20 %, armed again by a charging reading or one of 25 % or more).
 *
 * In memory only: not cleared at a session boundary, a lease lapse or a rebuild, and not persisted — a new
 * `Shell` (a process start, the phone's stack rebuilt on a target switch) starts open and says it once more
 * below 20 %, as the ruling accepts. Called from the shell's loop (`handleTransport`) and from the thread
 * that delivers a reading while no session runs (`keepWhileDown`), hence the lock.
 */
class BatteryLatch {
    enum class Verdict {
        /** Nothing to say. */
        NONE,
        /** Below [LOW_PCT], not charging, not said yet this discharge, and somebody can be told: say it. The
         *  latch is closed from here until it is re-armed. */
        SAY,
        /** A charging reading or one of [REARM_PCT] or more arrived while the latch was closed: it is open
         *  again (the host withdraws what it showed). */
        REOPENED,
    }

    private var closed = false

    /** One reading. [canTell]: whether anybody could be told right now — the host has an alert path of
     *  its own, or the shell can paint. With nobody to tell, a low reading leaves the latch OPEN (the
     *  once-per-discharge notice is not spent on nobody) and the next reading tries again. */
    @Synchronized
    fun reading(pct: Int, charging: Boolean, canTell: Boolean): Verdict = when {
        charging || pct >= REARM_PCT -> if (closed) { closed = false; Verdict.REOPENED } else Verdict.NONE
        pct < LOW_PCT && !closed && canTell -> { closed = true; Verdict.SAY }
        else -> Verdict.NONE
    }

    companion object {
        /** Adam's ruling, 2026-09-21: "below 20 %". */
        const val LOW_PCT = 20
        /** The same ruling: armed again by a reading of 25 % or more (or any charging reading). */
        const val REARM_PCT = 25
    }
}
