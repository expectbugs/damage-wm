package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import wm.damage.core.bastion.AlertRule
import wm.damage.core.bastion.Decision
import wm.damage.core.bastion.Decision.CLEAR
import wm.damage.core.bastion.Decision.HOLD
import wm.damage.core.bastion.Decision.RAISE
import wm.damage.core.bastion.EvalState
import wm.damage.core.bastion.Reading
import wm.damage.core.bastion.RuleEval
import wm.damage.core.bastion.RuleKind

/**
 * Build W, package A (R6 T5): the evaluator over tables of readings, one per minute on an injected clock. It
 * fails when a flap between `clearAt` and `at` raises twice, a false reading does not restart `forMin`,
 * unavailable flapping shorter than `forMin` raises, or a non-numeric state clears. Made-up entity ids only.
 */
class RuleEvalTest {

    private fun rule(kind: RuleKind, at: Double? = null, clearAt: Double? = null, value: String? = null, forMin: Int = 0) =
        AlertRule("r", "sensor.fresh_tank", kind, at, clearAt, value, forMin, "text")

    private fun s(state: String) = Reading.State(state)

    /** Runs [readings] one minute apart from minute 0; returns each step's decision. */
    private fun run(rule: AlertRule, readings: List<Reading>, start: EvalState = EvalState()): List<Decision> {
        var st = start
        return readings.mapIndexed { i, r ->
            val (next, d) = RuleEval.step(rule, st, r, i * MIN)
            st = next
            d
        }
    }

    @Test
    fun aFlapInsideTheHysteresisBandRaisesOnce() {
        val above = rule(RuleKind.ABOVE, at = 80.0, clearAt = 70.0)
        assertEquals(listOf(RAISE, HOLD, HOLD, HOLD, HOLD, HOLD, CLEAR, HOLD, RAISE),
            run(above, listOf("85", "75", "82", "71", "85", "79.9", "70", "75", "80").map { s(it) }))
        val below = rule(RuleKind.BELOW, at = 10.0, clearAt = 20.0)
        assertEquals(listOf(HOLD, RAISE, HOLD, HOLD, HOLD, CLEAR),
            run(below, listOf("15", "10", "15", "9", "19.5", "20").map { s(it) }))
    }

    @Test
    fun aFalseReadingRestartsForMin() {
        val above = rule(RuleKind.ABOVE, at = 80.0, clearAt = 70.0, forMin = 5)
        // minutes 0-3 true, minute 4 false (inside the band), minutes 5.. true: the clock starts again at 5
        val readings = List(4) { s("85") } + s("78") + List(7) { s("85") }
        val d = run(above, readings)
        assertEquals(10, d.indexOf(RAISE), "raised at minute ${d.indexOf(RAISE)}: $d")
        assertEquals(1, d.count { it == RAISE })
        // forMin delays the raise only: the clear is at once
        assertEquals(CLEAR, run(above, List(6) { s("85") } + s("60")).last())
    }

    @Test
    fun unavailableFlappingShorterThanForMinNeverRaises() {
        val un = rule(RuleKind.UNAVAILABLE, forMin = 5)
        val flapping = List(6) { listOf(s("unavailable"), s("unavailable"), s("unknown"), s("12")) }.flatten()
        assertEquals(0, run(un, flapping).count { it == RAISE }, "a bridge that drops out briefly raised")
        val d = run(un, List(6) { s("unavailable") } + s("on"))
        assertEquals(listOf(HOLD, HOLD, HOLD, HOLD, HOLD, RAISE, CLEAR), d)
        assertEquals(listOf(HOLD, CLEAR), run(un, listOf(s("unavailable"), s("on")), EvalState.seeded(true)))
    }

    @Test
    fun aNonNumericStateHoldsAndNeverClears() {
        val above = rule(RuleKind.ABOVE, at = 80.0, clearAt = 70.0)
        val d = run(above, listOf(s("85"), s("unavailable"), Reading.None, s("abc"), s("NaN"), s("85"), s("60")))
        assertEquals(listOf(RAISE, HOLD, HOLD, HOLD, HOLD, HOLD, CLEAR), d)
        // "no reading since" is kept from the first unusable reading and dropped by the next usable one
        var st = RuleEval.step(above, EvalState(), s("85"), 0).first
        st = RuleEval.step(above, st, s("unavailable"), 1 * MIN).first
        st = RuleEval.step(above, st, Reading.None, 2 * MIN).first
        assertEquals(1 * MIN, st.noReadingSince)
        st = RuleEval.step(above, st, s("75"), 3 * MIN).first
        assertNull(st.noReadingSince)
    }

    @Test
    fun equalsAndNotEqualsCompareTheStateString() {
        val eq = rule(RuleKind.EQUALS, value = "on")
        assertEquals(listOf(HOLD, RAISE, HOLD, HOLD, CLEAR), run(eq, listOf("off", "on", "unavailable", "on", "off").map { s(it) }))
        val ne = rule(RuleKind.NOT_EQUALS, value = "on")
        assertEquals(listOf(HOLD, RAISE, HOLD, CLEAR), run(ne, listOf("on", "off", "unknown", "on").map { s(it) }))
        val eqUn = rule(RuleKind.EQUALS, value = "unavailable")
        assertEquals(listOf(RAISE, CLEAR), run(eqUn, listOf("unavailable", "on").map { s(it) }))
    }

    @Test
    fun aSeededRaisedAlertHoldsUntilItsConditionEnds() {
        val above = rule(RuleKind.ABOVE, at = 80.0, clearAt = 70.0, forMin = 5)
        assertEquals(listOf(HOLD, HOLD, CLEAR), run(above, listOf(s("85"), s("75"), s("60")), EvalState.seeded(true)))
    }

    private companion object {
        const val MIN = 60_000L
    }
}
