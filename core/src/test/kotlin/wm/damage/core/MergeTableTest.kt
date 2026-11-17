package wm.damage.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import wm.damage.core.app.Answer
import wm.damage.core.app.Effect
import wm.damage.core.app.Item
import wm.damage.core.app.Merge
import wm.damage.core.app.Record
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * L1 C1 (the lead's ruling on L0, 2026-11-17): the one merge rule as a table — each target is written unless the
 * record holds a later input; an equal value changes nothing; an equal time goes to the op that arrives later; a
 * removal is an entry; the PC bounds an input's time by its own clock. Pure judge calls, then the PC's apply path.
 */
class MergeTableTest {
    private val rig = RecordRig()

    @After
    fun tearDown() = rig.cleanUp()

    private fun rec(vararg items: Pair<String, Item>) = Record().apply { apply(Effect(items.associate { (k, v) -> "rec.t.$k" to v })) }

    private fun judge(r: Record, o: wm.damage.core.app.Op): Effect {
        val spec = TEST_ACTIONS.spec("t", o.action)!!
        assertEquals(null, Merge.check(spec, o))
        return Merge.judge(r, spec, o)
    }

    @Test
    fun theLatestInputWins_whateverTheOrderOfArrival() {
        val r = rec("x" to Item(j("desk"), "web-1", 2_000))
        assertEquals(Effect.NONE, judge(r, op("phone", "s1", 1, "place", listOf("x"), j("glasses"), at = 1_000)),
            "a place read earlier (the glasses offline) does not overwrite a later one set at the desk")
        assertEquals(Item(j("glasses"), "phone", 3_000), judge(r, op("phone", "s1", 2, "place", listOf("x"), j("glasses"), at = 3_000)).items["rec.t.x"])
        assertEquals(Item(j("phone"), "phone", 2_000), judge(r, op("phone", "s1", 3, "set", listOf("x"), j("phone"), at = 2_000)).items["rec.t.x"],
            "an equal time: the op that arrives later wins")
        assertEquals(Item(j("new"), "phone", 5), judge(rec(), op("phone", "s1", 4, "set", listOf("y"), j("new"), at = 5)).items["rec.t.y"],
            "an item the record lacks is written")
    }

    @Test
    fun anEqualValueChangesNothing_soApplyingTwiceChangesNothing() {
        val r = rec("x" to Item(j("a"), "desk", 10))
        assertEquals(Effect.NONE, judge(r, op("phone", "s1", 1, "set", listOf("x"), j("a"), at = 20)), "its writer and time stand")
        val o = op("phone", "s1", 2, "set", listOf("x"), j("b"), at = 20)
        r.apply(judge(r, o))
        assertEquals(Effect.NONE, judge(r, o), "the same op again")
    }

    @Test
    fun eachTargetIsJudgedOnItsOwn() {
        val r = rec("a" to Item(j("A"), "desk", 50), "b" to Item(j("B"), "desk", 5))
        val e = judge(r, op("phone", "s1", 1, "set", listOf("a", "b"), j("P"), at = 10))
        assertEquals(setOf("rec.t.b"), e.items.keys)
    }

    @Test
    fun aRemovalIsAnEntry_soAnOlderEditArrivingLaterDoesNotBringItBack() {
        val r = rec("a" to Item(j("A"), "desk", 10))
        val gone = judge(r, op("phone", "s1", 1, "drop", listOf("a"), JsonNull, at = 30))
        assertEquals(Item(JsonNull, "phone", 30, gone = true), gone.items["rec.t.a"])
        r.apply(gone)
        assertEquals(Effect.NONE, judge(r, op("desk", "d", 1, "set", listOf("a"), j("late"), at = 20)), "older than the removal")
        assertEquals(Effect.NONE, judge(r, op("desk", "d", 2, "drop", listOf("a"), JsonNull, at = 40)), "already removed")
        assertEquals(j("again"), judge(r, op("desk", "d", 3, "set", listOf("a"), j("again"), at = 50)).items["rec.t.a"]!!.value,
            "a later edit writes it again")
        assertNotNull(judge(rec(), op("phone", "s1", 2, "drop", listOf("never"), JsonNull, at = 1)).items["rec.t.never"],
            "removing an item the record lacks leaves the entry, so an older write arriving later stays out")
    }

    @Test
    fun thePcBoundsAnInputsTimeByItsOwnClock_andLogsEveryOp() {
        val h = rig.opened()
        rig.now = 1_000L
        assertEquals(Answer.Seen, rig.ask(h, op("tv", "w1", 1, "set", listOf("x"), j("ahead"), at = 61_000)))
        assertEquals(1_000L, rig.item(h, "x")!!.at, "a clock a minute fast is held to the PC's")
        rig.now = 2_000L
        assertEquals(Answer.Seen, rig.ask(h, op("phone", "s1", 1, "set", listOf("x"), j("later"), at = 2_000)))
        assertEquals(Item(j("later"), "phone", 2_000), rig.item(h, "x"), "so a later input elsewhere still wins")
        val seq = h.seq
        assertEquals(Answer.Seen, rig.ask(h, op("phone", "s1", 1, "set", listOf("x"), j("later"), at = 2_000)), "a resend")
        assertEquals(seq + 1, h.seq, "it takes a line of its own, and changes nothing")
        assertEquals(Item(j("later"), "phone", 2_000), rig.item(h, "x"))
    }

    @Test
    fun theShapeIsChecked() {
        val spec = TEST_ACTIONS.spec("t", "set")!!
        assertNotNull(Merge.check(spec, op("phone", "s1", 1, "set", emptyList(), j("v"))))
        assertNotNull(Merge.check(spec, op("phone", "s1", 1, "set", listOf("a", "a"), j("v"))))
        assertNotNull(Merge.check(spec, op("phone", "s1", 1, "set", listOf(" "), j("v"))))
        val conn = wm.damage.core.app.Op("phone", wm.damage.core.app.OpId.Conn(1, 1), "t", "set", listOf("a"), j("v"), at = 1)
        assertTrue(Merge.check(spec, conn)!!.contains("queued"))
        assertEquals(null, Merge.check(spec, op("phone", "s1", 1, "set", listOf("a"), buildJsonObject { put("k", 1) })))
    }
}
