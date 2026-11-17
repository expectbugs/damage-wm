package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.bastion.AlertRule
import wm.damage.core.bastion.Decision
import wm.damage.core.bastion.Direction
import wm.damage.core.bastion.EvalState
import wm.damage.core.bastion.Guard
import wm.damage.core.bastion.GuardRead
import wm.damage.core.bastion.Reading
import wm.damage.core.bastion.RuleEval
import wm.damage.core.bastion.RuleKind
import wm.damage.core.bastion.RulesFile

/**
 * Build W, package E (the lead's decisions F.1 and F.2): the rules file's `when` and the kind `change`, parsed and
 * judged. Pure: [RuleEval.step] with the guard and the acknowledge time handed in. A guard that ends resets the
 * rule's clocks and baseline and clears its alert; a guard with no reading neither arms nor clears; a change rule
 * raises at `at` or more in its direction held `forMin` and takes its baseline at arming and again after an
 * acknowledge. Made-up entity ids only.
 */
class ChangeRuleTest {

    private val guard = Guard("binary_sensor.away_mode", "on", equals = true)

    private fun change(at: Double = 3.0, direction: Direction = Direction.ANY, forMin: Int = 0, g: Guard? = null) =
        AlertRule("leak", "sensor.fresh_tank", RuleKind.CHANGE, at = at, forMin = forMin, text = "Fresh tank", guard = g,
            direction = direction)

    private fun s(state: String) = Reading.State(state)

    private fun parse(rule: String) = RulesFile.parse("""{"rules":[$rule]}""")

    private fun refusedWhy(rule: String): String {
        val load = parse(rule)
        assertEquals(emptyList(), load.rules, "refused: $rule")
        return load.refused.single().why
    }

    @Test
    fun theWhenAndTheChangeKindAreParsedAndCheckedField() {
        val ok = parse("""
            {"id":"leak","entity":"sensor.fresh_tank","kind":"change","at":3,"text":"Fresh tank",
             "when":{"entity":"damage.phone","equals":"away"}},
            {"id":"drop","entity":"sensor.grey_tank","kind":"change","at":"2.5","direction":"down","forMin":2,"text":"Grey tank"},
            {"id":"sump","entity":"sensor.sump_level","kind":"above","at":5,"clearAt":3,"text":"Sump high",
             "when":{"entity":"switch.sump_pump","notEquals":"on"}}
        """.trimIndent())
        assertEquals(emptyList(), ok.refused)
        val (leak, drop, sump) = ok.rules
        assertEquals(Guard("damage.phone", "away", equals = true), leak.guard)
        assertEquals(Direction.ANY, leak.direction)
        assertEquals(Direction.DOWN, drop.direction)
        assertEquals(listOf("sensor.fresh_tank"), leak.hubEntities, "a built-in guard is not read from the hub")
        assertEquals(2.5, drop.at)
        assertNull(drop.guard)
        assertEquals(Guard("switch.sump_pump", "on", equals = false), sump.guard)
        assertEquals(listOf("sensor.sump_level", "switch.sump_pump"), sump.hubEntities, "a hub guard is read too")

        val base = """"id":"r","entity":"sensor.fresh_tank","kind":"change","text":"x""""
        assertTrue("at above 0" in refusedWhy("{$base,\"at\":0}"))
        assertTrue("at missing" in refusedWhy("{$base}"))
        assertTrue("clearAt does not apply" in refusedWhy("{$base,\"at\":3,\"clearAt\":1}"))
        assertTrue("direction" in refusedWhy("{$base,\"at\":3,\"direction\":\"sideways\"}"))
        assertTrue("direction does not apply" in refusedWhy(
            """{"id":"r","entity":"sensor.fresh_tank","kind":"above","at":5,"clearAt":3,"direction":"up","text":"x"}"""),
            "direction is the change kind's only")
        val w = """"id":"r","entity":"sensor.fresh_tank","kind":"above","at":5,"clearAt":3,"text":"x""""
        assertTrue("exactly one" in refusedWhy("{$w,\"when\":{\"entity\":\"switch.a\",\"equals\":\"on\",\"notEquals\":\"off\"}}"))
        assertTrue("exactly one" in refusedWhy("{$w,\"when\":{\"entity\":\"switch.a\"}}"))
        assertTrue("does not apply" in refusedWhy("{$w,\"when\":{\"entity\":\"switch.a\",\"equals\":\"on\",\"forMin\":1}}"))
        assertTrue("no reading" in refusedWhy("{$w,\"when\":{\"entity\":\"switch.a\",\"equals\":\"unavailable\"}}"))
        assertTrue("no reading" in refusedWhy("{$w,\"when\":{\"entity\":\"damage.phone\",\"equals\":\"unknown\"}}"))
        assertTrue("home or away" in refusedWhy("{$w,\"when\":{\"entity\":\"damage.phone\",\"equals\":\"work\"}}"))
        assertTrue("not one of Damage's own" in refusedWhy("{$w,\"when\":{\"entity\":\"damage.car\",\"equals\":\"on\"}}"))
        assertTrue("usable only in a when" in refusedWhy(
            """{"id":"r","entity":"damage.phone","kind":"equals","value":"away","text":"x"}"""))
    }

    @Test
    fun theGuardArmsResetsAndClears() {
        val rule = AlertRule("sump", "sensor.sump_level", RuleKind.ABOVE, at = 5.0, clearAt = 3.0, forMin = 2,
            text = "Sump high", guard = guard)
        var st = EvalState()
        // held: the forMin clock runs from minute 0
        st = RuleEval.step(rule, st, s("6"), 0, GuardRead.HOLDS).first
        st = RuleEval.step(rule, st, s("6"), MIN, GuardRead.HOLDS).first
        // the guard ends at minute 1.5: the clock resets, nothing raises at minute 2
        val (ended, d1) = RuleEval.step(rule, st, s("6"), MIN + MIN / 2, GuardRead.ENDED)
        assertEquals(Decision.HOLD, d1)
        assertNull(ended.trueSince, "the guard's end resets the forMin clock")
        // back: the clock starts again from minute 3, so minute 4 is not yet two minutes
        st = RuleEval.step(rule, ended, s("6"), 3 * MIN, GuardRead.HOLDS).first
        val (_, d2) = RuleEval.step(rule, st, s("6"), 4 * MIN, GuardRead.HOLDS)
        assertEquals(Decision.HOLD, d2)
        val (up, d3) = RuleEval.step(rule, st, s("6"), 5 * MIN, GuardRead.HOLDS)
        assertEquals(Decision.RAISE, d3)
        // the guard ends with the alert up: it clears whatever the reading (here none at all)
        val (down, d4) = RuleEval.step(rule, up, Reading.None, 6 * MIN, GuardRead.ENDED)
        assertEquals(Decision.CLEAR, d4)
        assertEquals(EvalState(), down, "every clock starts again")
    }

    @Test
    fun aGuardWithNoReadingNeitherArmsNorClears() {
        val rule = AlertRule("sump", "sensor.sump_level", RuleKind.ABOVE, at = 5.0, clearAt = 3.0, forMin = 1,
            text = "Sump high", guard = guard)
        // not raised, condition true for ten minutes, guard no reading: nothing arms
        var st = EvalState()
        for (m in 0..10) {
            val (next, d) = RuleEval.step(rule, st, s("9"), m * MIN, GuardRead.NO_READING)
            assertEquals(Decision.HOLD, d, "minute $m")
            st = next
        }
        assertNull(st.trueSince, "no clock ran while the guard had no reading")
        assertEquals(0L, st.guardNoReadingSince, "the state line's since")
        // raised, the value falls below clearAt, guard no reading: nothing clears
        val raised = EvalState(raised = true, trueSince = 0)
        val (held, d) = RuleEval.step(rule, raised, s("1"), 20 * MIN, GuardRead.NO_READING)
        assertEquals(Decision.HOLD, d)
        assertTrue(held.raised)
        // the reading returns: judged at once, and the since is gone
        val (back, d2) = RuleEval.step(rule, held, s("1"), 21 * MIN, GuardRead.HOLDS)
        assertEquals(Decision.CLEAR, d2)
        assertNull(back.guardNoReadingSince)
    }

    @Test
    fun aChangeRaisesAtAtInItsDirectionAndHoldsForMin() {
        fun run(rule: AlertRule, values: List<String>): List<Decision> {
            var st = EvalState()
            return values.mapIndexed { i, v ->
                val (next, d) = RuleEval.step(rule, st, s(v), i * MIN)
                st = next
                d
            }
        }
        val H = Decision.HOLD
        val R = Decision.RAISE
        val C = Decision.CLEAR
        // the first reading is the baseline (100); 2.9 below is not a change of 3; 3 below is
        assertEquals(listOf(H, H, R, H, C), run(change(), listOf("100", "97.1", "97", "96", "98")))
        assertEquals(listOf(H, R), run(change(), listOf("100", "103")), "any: up counts")
        assertEquals(listOf(H, H, H), run(change(direction = Direction.DOWN), listOf("100", "103", "110")), "down only")
        assertEquals(listOf(H, R), run(change(direction = Direction.DOWN), listOf("100", "97")))
        assertEquals(listOf(H, H, R), run(change(direction = Direction.UP), listOf("100", "90", "103")), "up only")
        // decimal readings: 4.1 - 1.1 reaches 3 although binary subtraction lands a hair under (2.9999999999999996)
        assertEquals(listOf(H, R), run(change(), listOf("1.1", "4.1")))
        // forMin 2: raised two minutes after the change was first seen, and a reading back inside restarts it
        assertEquals(listOf(H, H, H, H, H, H, R), run(change(forMin = 2), listOf("100", "96", "96", "99", "96", "96", "96")))
        // a non-numeric reading holds the decision and keeps the baseline
        var st = RuleEval.step(change(), EvalState(), s("100"), 0).first
        st = RuleEval.step(change(), st, s("unavailable"), MIN).first
        assertEquals("100", st.baseline)
        assertEquals(Decision.RAISE, RuleEval.step(change(), st, s("96"), 2 * MIN).second)
    }

    @Test
    fun theBaselineIsTakenAtArmingAndAgainAfterAnAcknowledge() {
        val rule = change(g = guard)
        // the guard holds from minute 0: the first reading is the baseline
        var st = RuleEval.step(rule, EvalState(), s("100"), 0, GuardRead.HOLDS).first
        assertEquals("100" to 0L, st.baseline to st.baselineAt)
        val (up, d) = RuleEval.step(rule, st, s("94"), 10 * MIN, GuardRead.HOLDS)
        assertEquals(Decision.RAISE, d)
        assertEquals("100", up.baseline, "a raise keeps the baseline")
        // acknowledged at minute 11: the next reading is the new baseline, so the alert clears...
        val (rebased, d2) = RuleEval.step(rule, up, s("94"), 12 * MIN, GuardRead.HOLDS, ackedAt = 11 * MIN)
        assertEquals(Decision.CLEAR, d2)
        assertEquals("94" to 12 * MIN, rebased.baseline to rebased.baselineAt)
        // ...and the same acknowledge does not re-take it again; the next change raises a new episode
        val (again, d3) = RuleEval.step(rule, rebased, s("91"), 13 * MIN, GuardRead.HOLDS, ackedAt = 11 * MIN)
        assertEquals(Decision.RAISE, d3)
        assertEquals("94", again.baseline)
        // the guard ends: the baseline is dropped, and the next arming takes a new one
        val (off, _) = RuleEval.step(rule, again, s("91"), 14 * MIN, GuardRead.ENDED)
        assertNull(off.baseline)
        val armed = RuleEval.step(rule, off, s("80"), 15 * MIN, GuardRead.HOLDS).first
        assertEquals("80" to 15 * MIN, armed.baseline to armed.baselineAt)
        // a seeded (saved) baseline is used, not re-taken
        val seeded = EvalState.seeded(raised = false, baseline = "100", baselineAt = 5)
        assertEquals(Decision.RAISE, RuleEval.step(rule, seeded, s("96"), 20 * MIN, GuardRead.HOLDS).second)
    }

    /** L1 R2 A1: a rules file of the SHAPE of the draft he asked for on 2026-11-13 (21 rules: four `change` rules
     *  guarded on `damage.phone` with a `direction`, one of them `up`; a top-level note beside `rules`) loads with
     *  none refused. Made-up entities and words; the kinds, keys and value types are the draft's. */
    @Test
    fun aRulesFileOfTheDraftsShapeLoadsWithNoneRefused() {
        fun level(i: Int, kind: String, at: Int, clearAt: Int) =
            """{"id":"made-up-$i","entity":"sensor.made_up_$i","kind":"$kind","at":$at,"clearAt":$clearAt,"forMin":5,"text":"Made-up $i","repeatMin":60,"severity":"normal"}"""
        fun state(i: Int, kind: String, extra: String) =
            """{"id":"made-up-$i","entity":"binary_sensor.made_up_$i","kind":"$kind"$extra,"forMin":10,"text":"Made-up $i","repeatMin":0,"severity":"normal"}"""
        fun away(i: Int, direction: String) =
            """{"id":"made-up-$i.change-away","entity":"sensor.made_up_$i","kind":"change","at":3,"direction":"$direction","forMin":10,"when":{"entity":"damage.phone","equals":"away"},"text":"Made-up $i changed while away","repeatMin":0,"severity":"normal"}"""
        val rules = (1..4).map { level(it, "above", 80, 70) } +
            listOf(state(5, "equals", ""","value":"on""""), state(6, "equals", ""","value":"open"""")) +
            (7..13).map { level(it, "below", 20, 30) } +
            (14..16).map { state(it, "unavailable", "") } +
            state(17, "notEquals", ""","value":"home"""") +
            listOf(away(18, "any"), away(19, "any"), away(20, "any"), away(21, "up"))
        val load = RulesFile.parse("""{"rules":[${rules.joinToString(",\n")}],"_pending":"a note kept beside the rules"}""")
        assertNull(load.fileProblem)
        assertEquals(emptyList(), load.refused)
        assertEquals(21, load.rules.size)
        val changes = load.rules.filter { it.kind == RuleKind.CHANGE }
        assertEquals(listOf(Direction.ANY, Direction.ANY, Direction.ANY, Direction.UP), changes.map { it.direction })
        assertTrue(changes.all { it.guard == Guard(AlertRule.PHONE, "away", equals = true) })
        // the `up` rule: a fall (a pump at work) never raises, a rise of `at` does
        val up = changes.last()
        var st = RuleEval.step(up, EvalState(), s("50"), 0).first
        val (fell, d) = RuleEval.step(up, st, s("40"), 11 * MIN)
        assertEquals(Decision.HOLD, d, "a fall is not a change for an up rule")
        st = fell
        st = RuleEval.step(up, st, s("53"), 12 * MIN).first
        assertEquals(Decision.RAISE, RuleEval.step(up, st, s("53"), 22 * MIN).second)
    }

    companion object {
        const val MIN = 60_000L
    }
}
