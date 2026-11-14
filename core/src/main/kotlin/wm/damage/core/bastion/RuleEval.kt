package wm.damage.core.bastion

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. This decides, for one rule and one reading of his own home hub, whether its
 * alert raises, clears or holds (build W, R6 §2 "Kinds", P3). Pure: the clock is a parameter, so a
 * table of cases pins it (`RuleEvalTest`), the `StandbyPolicy` shape.
 *
 * - ABOVE raises at v ≥ `at` held `forMin`, clears at v ≤ `clearAt`; BELOW mirrors it. Between the
 *   two the decision holds (hysteresis), and a reading that does not meet the raise condition
 *   restarts the `forMin` clock.
 * - EQUALS / NOT_EQUALS compare the state string with `value`. `unavailable` and `unknown` are no
 *   reading for them (unless `value` is one of them): a hub restart or a bridge dropping out is
 *   UNAVAILABLE's to say, and must not clear a raised alert only to raise it again a minute later.
 * - UNAVAILABLE raises when the state is `unavailable` or `unknown` for `forMin` (> 0), and clears on
 *   any other state.
 * - STALE raises when the entity's report stamp (`last_reported`, else `last_updated`) is unchanged
 *   for `forMin`, timed on the PC's clock from when the PC first saw that stamp (one clock, no skew;
 *   blind for `forMin` after a PC start), and clears when the stamp moves.
 * - A numeric rule's non-numeric state, or no answer this tick ([Reading.None]), is NO READING: the
 *   decision holds and nothing is reset ("no reading since HH:MM", [EvalState.noReadingSince]).
 * - CHANGE (package E, dec. F.2) raises when the value differs from a baseline by `at` or more in the
 *   rule's direction, held `forMin`, and clears when it no longer does (no band: the rule has no
 *   `clearAt`). Without `withinMin` the baseline is the first usable reading with none held (at
 *   arming: the guard turning true, or the first reading of a rule that has none), and it is re-taken
 *   at the first reading after the alert is acknowledged — so the alert clears, and the next change
 *   raises a new episode. With `withinMin` the caller hands the value from that many minutes ago
 *   ([Sample], from its ring); none = blind, the decision holds.
 * - A guard ([AlertRule.guard], dec. F.1) is judged first ([GuardRead]): ENDED resets every clock and
 *   the baseline and clears a raised alert whatever the reading; NO_READING holds everything (nothing
 *   arms, nothing clears); HOLDS judges the rule as above.
 */
enum class Decision { HOLD, RAISE, CLEAR }

/** What the watcher has for one entity this tick. */
sealed class Reading {
    /** No answer: the hub is not answering, the request is in flight, the entity was not found. */
    object None : Reading()

    class State(val state: String, val lastReported: String?, val lastUpdated: String?) : Reading()

    companion object {
        fun of(s: HaState): Reading = State(s.state, s.lastReported, s.lastUpdated)
    }
}

/** The guard of a rule this tick (dec. F.1); an unguarded rule is always [HOLDS]. */
enum class GuardRead { HOLDS, ENDED, NO_READING }

/** One reading kept for a `withinMin` comparison: the number, the state as read (for the alert's words) and when. */
class Sample(val value: Double, val text: String, val at: Long)

/** One rule's evaluation state between ticks (the per-rule clocks, R6 §7). In memory, except a CHANGE rule's
 *  baseline, which the watcher keeps in `alerts.json` (dec. F.2): a restart starts the clocks from zero with
 *  [seeded], the baseline as it was saved. */
data class EvalState(
    /** The alert is up (RAISED or ACKED in the store). */
    val raised: Boolean = false,
    /** PC time the raise condition was first seen true with no false reading since; null = not true now. */
    val trueSince: Long? = null,
    /** STALE: the report stamp last seen, and the PC time it was first seen. */
    val stamp: String? = null,
    val stampSeenAt: Long = 0,
    /** PC time the readings stopped being usable; null while they are. */
    val noReadingSince: Long? = null,
    /** CHANGE without `withinMin`: the baseline as read (a finite number's text) and the PC time it was taken;
     *  null = none held (the next usable reading arms the rule). */
    val baseline: String? = null,
    val baselineAt: Long = 0,
    /** PC time the guard stopped giving a reading (dec. F.1: the state line says so); null while it gives one. */
    val guardNoReadingSince: Long? = null,
) {
    companion object {
        /** After a restart: the store says whether the alert is up and gives a CHANGE rule's saved baseline; every
         *  clock starts again. */
        fun seeded(raised: Boolean, baseline: String? = null, baselineAt: Long = 0) =
            EvalState(raised = raised, baseline = baseline, baselineAt = if (baseline == null) 0 else baselineAt)
    }
}

object RuleEval {

    private val ABSENT = setOf("unavailable", "unknown")

    private enum class Cond { RAISE, CLEAR, BETWEEN, NO_READING }

    /**
     * One rule, one reading. [guard]: the rule's guard this tick (HOLDS for an unguarded rule). [past]: a
     * `withinMin` CHANGE rule's reading from that many minutes ago, null = blind. [ackedAt]: when a CHANGE rule's
     * alert was acknowledged, 0 when it is not ACKED now — a baseline older than it is re-taken.
     */
    fun step(
        rule: AlertRule, prior: EvalState, reading: Reading, now: Long,
        guard: GuardRead = GuardRead.HOLDS, past: Sample? = null, ackedAt: Long = 0L,
    ): Pair<EvalState, Decision> {
        when (guard) {
            // the guard ended: every clock and the baseline start again from nothing, and a raised alert clears
            GuardRead.ENDED -> return EvalState() to (if (prior.raised) Decision.CLEAR else Decision.HOLD)
            // the guard has no reading: nothing arms, nothing clears, nothing is reset
            GuardRead.NO_READING -> return prior.copy(guardNoReadingSince = prior.guardNoReadingSince ?: now) to Decision.HOLD
            GuardRead.HOLDS -> {}
        }
        val open = if (prior.guardNoReadingSince == null) prior else prior.copy(guardNoReadingSince = null)
        return when (rule.kind) {
            RuleKind.STALE -> stale(rule, open, reading, now)
            RuleKind.CHANGE -> change(rule, open, reading, now, past, ackedAt)
            else -> level(rule, open, reading, now)
        }
    }

    private fun level(rule: AlertRule, prior: EvalState, reading: Reading, now: Long): Pair<EvalState, Decision> {
        val cond = if (reading is Reading.State) judge(rule, reading.state) else Cond.NO_READING
        return when (cond) {
            Cond.NO_READING -> prior.copy(noReadingSince = prior.noReadingSince ?: now) to Decision.HOLD
            Cond.RAISE -> {
                val since = prior.trueSince ?: now
                val next = prior.copy(trueSince = since, noReadingSince = null)
                if (!prior.raised && now - since >= rule.forMs) next.copy(raised = true) to Decision.RAISE
                else next to Decision.HOLD
            }
            Cond.BETWEEN, Cond.CLEAR -> {
                // a reading that does not meet the raise condition restarts the forMin clock
                val reset = prior.copy(trueSince = null, noReadingSince = null)
                if (cond == Cond.CLEAR && prior.raised) reset.copy(raised = false) to Decision.CLEAR
                else reset to Decision.HOLD
            }
        }
    }

    private fun judge(rule: AlertRule, state: String): Cond = when (rule.kind) {
        RuleKind.ABOVE -> {
            val v = state.toDoubleOrNull()
            when {
                v == null || !v.isFinite() -> Cond.NO_READING
                v >= rule.at!! -> Cond.RAISE
                v <= rule.clearAt!! -> Cond.CLEAR
                else -> Cond.BETWEEN
            }
        }
        RuleKind.BELOW -> {
            val v = state.toDoubleOrNull()
            when {
                v == null || !v.isFinite() -> Cond.NO_READING
                v <= rule.at!! -> Cond.RAISE
                v >= rule.clearAt!! -> Cond.CLEAR
                else -> Cond.BETWEEN
            }
        }
        RuleKind.EQUALS, RuleKind.NOT_EQUALS -> when {
            state in ABSENT && rule.value !in ABSENT -> Cond.NO_READING
            (state == rule.value) == (rule.kind == RuleKind.EQUALS) -> Cond.RAISE
            else -> Cond.CLEAR
        }
        RuleKind.UNAVAILABLE -> if (state in ABSENT) Cond.RAISE else Cond.CLEAR
        RuleKind.STALE -> error("stale is judged by its stamp")
        RuleKind.CHANGE -> error("change is judged against its baseline")
    }

    /** A usable number from a state, or null (no reading). */
    fun number(state: String): Double? = state.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Whether [value] differs from [base] by the rule's `at` or more in its direction. A difference within a
     *  billionth of `at` counts as reaching it (decimal readings do not subtract exactly in binary). */
    fun changed(rule: AlertRule, base: Double, value: Double): Boolean {
        val d = value - base
        val size = when (rule.direction) {
            Direction.ANY -> kotlin.math.abs(d)
            Direction.UP -> d
            Direction.DOWN -> -d
        }
        val at = rule.at!!
        return size >= at - at * 1e-9
    }

    private fun change(
        rule: AlertRule, prior: EvalState, reading: Reading, now: Long, past: Sample?, ackedAt: Long,
    ): Pair<EvalState, Decision> {
        val text = (reading as? Reading.State)?.state
        val v = text?.let { number(it) }
            ?: return prior.copy(noReadingSince = prior.noReadingSince ?: now) to Decision.HOLD
        var p = prior.copy(noReadingSince = null)
        val base: Double
        if (rule.withinMin == 0) {
            val held = p.baseline?.let { number(it) }
            // arming (none held, or a saved one that is not a number) or re-taken after an acknowledge
            if (held == null || (ackedAt > 0L && p.baselineAt < ackedAt)) {
                p = p.copy(baseline = text, baselineAt = now)
                base = v
            } else {
                base = held
            }
        } else {
            base = past?.value ?: return p to Decision.HOLD          // blind: nothing from withinMin ago yet
        }
        return if (changed(rule, base, v)) {
            val since = p.trueSince ?: now
            val next = p.copy(trueSince = since)
            if (!p.raised && now - since >= rule.forMs) next.copy(raised = true) to Decision.RAISE
            else next to Decision.HOLD
        } else {
            val reset = p.copy(trueSince = null)
            if (p.raised) reset.copy(raised = false) to Decision.CLEAR else reset to Decision.HOLD
        }
    }

    private fun stale(rule: AlertRule, prior: EvalState, reading: Reading, now: Long): Pair<EvalState, Decision> {
        // last_reported moves on every report, even an unchanged value; last_updated only when the value or
        // an attribute changes — so it is read first (R6 §2)
        val stamp = (reading as? Reading.State)?.let { it.lastReported ?: it.lastUpdated }
            ?: return prior.copy(noReadingSince = prior.noReadingSince ?: now) to Decision.HOLD
        if (prior.stamp == null) {
            // first sight since start: the clock starts here; a seeded raised alert stays up until the stamp moves
            return prior.copy(stamp = stamp, stampSeenAt = now, noReadingSince = null) to Decision.HOLD
        }
        if (stamp != prior.stamp) {
            val fresh = prior.copy(stamp = stamp, stampSeenAt = now, noReadingSince = null)
            return if (prior.raised) fresh.copy(raised = false) to Decision.CLEAR else fresh to Decision.HOLD
        }
        val same = prior.copy(noReadingSince = null)
        return if (!prior.raised && now - prior.stampSeenAt >= rule.forMs) same.copy(raised = true) to Decision.RAISE
        else same to Decision.HOLD
    }
}
