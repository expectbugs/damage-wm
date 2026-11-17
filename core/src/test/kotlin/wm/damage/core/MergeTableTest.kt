package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import wm.damage.core.app.Answer
import wm.damage.core.app.Effect
import wm.damage.core.app.Expect
import wm.damage.core.app.Item
import wm.damage.core.app.Merge
import wm.damage.core.app.Record
import wm.damage.core.app.Verdict

/**
 * Build S1-1, package P1 (`CONTRACT.md` §2.4, the one merge rule; dec. A.3, A.9): one test per queued kind row of
 * C§2.4's table — create, register, position, order, remove, whole collection, edit of a removed item — plus "a
 * same-value op succeeds and changes nothing", "no op is applied twice" and "`Any` never loses". The judge is pure;
 * the last tests go through the host, the path an op really takes. Made-up window `t` and ids only.
 */
class MergeTableTest {
    private val rig = RecordRig()

    @After
    fun tearDown() = rig.cleanUp()

    private val NOW = 5_000L
    private fun rec(vararg items: Pair<String, Item>) =
        Record().apply { apply(Effect(items.associate { (k, v) -> "rec.t.$k" to v })) }

    private fun judge(r: Record, o: wm.damage.core.app.Op): Verdict {
        val spec = TEST_ACTIONS.spec(o.window, o.action)!!
        assertNull(Merge.check(spec, o), "the op's shape")
        return Merge.judge(r, spec, o, NOW)
    }

    // ---- create ----

    // ---- register ----

    @Test
    fun aRegisterAppliesOnTheValueSeenAndLosesToAThirdValue() {
        val r = rec("flag" to Item(j("a"), 1, "desk", 100))
        val ok = judge(r, op("phone", "s1", 1, "set", listOf("flag"), j("b"), Expect.Value(j("a"))))
        assertEquals(Verdict.Applied(Effect(mapOf("rec.t.flag" to Item(j("b"), 2, "phone", NOW)))), ok)

        val r2 = rec("flag" to Item(j("c"), 3, "desk", 200))
        val lost = judge(r2, op("phone", "s1", 1, "set", listOf("flag"), j("b"), Expect.Value(j("a"))))
        assertIs<Verdict.Lost>(lost)
        val loss = lost.losses.single()
        assertEquals("flag", loss.item)
        assertEquals(j("c"), loss.standing)
        assertEquals("desk", loss.by)
        assertEquals(200L, loss.at)
        assertEquals(j("b"), loss.mine)
        // the standing entry is unchanged but for its "not applied" mark
        val marked = lost.effect.items["rec.t.flag"]!!
        assertEquals(Item(j("c"), 3, "desk", 200), marked.copy(notApplied = null))
        assertEquals(j("b"), marked.notApplied!!.mine)
        assertEquals("phone", marked.notApplied!!.by)
    }

    @Test
    fun aRecurringItemsTickLosesWhenItsOccurrenceIsNoLongerOpen() {
        fun v(occ: String, done: Boolean) = buildJsonObject { put("occ", occ); put("done", done) }
        val r = rec("water" to Item(v("o2", false), 4, "desk", 100))
        assertIs<Verdict.Applied>(judge(r, op("phone", "s1", 1, "set", listOf("water"), v("o2", true), Expect.Occurrence("o2"))))
        val lost = judge(r, op("phone", "s1", 1, "set", listOf("water"), v("o1", true), Expect.Occurrence("o1")))
        assertIs<Verdict.Lost>(lost)
        assertEquals(v("o2", false), lost.losses.single().standing)
    }

    @Test
    fun oneTargetLosingLeavesTheOthersApplied() {
        val r = rec("a" to Item(j("unread"), 1, "desk", 1), "b" to Item(j("read"), 2, "desk", 2), "c" to Item(j("x"), 1, "desk", 3))
        val v = judge(r, op("phone", "s1", 1, "set", listOf("a", "b", "c"), j("read"), Expect.Value(j("unread"))))
        assertIs<Verdict.Lost>(v)
        assertEquals(listOf("c"), v.losses.map { it.item })
        assertEquals(Item(j("read"), 2, "phone", NOW), v.effect.items["rec.t.a"])
        assertNull(v.effect.items["rec.t.b"], "b already held what the op makes: untouched")
    }

    // ---- position ----

    @Test
    fun aPositionLosesWhenAnotherDeviceMovedItAndReadingOnNeverLosesTwice() {
        val r = rec("book.x" to Item(j("ch9"), 5, "desk", 300))
        val lost = judge(r, op("phone", "s1", 7, "place", listOf("book.x"), j("ch12"), Expect.Value(j("ch8"))))
        assertIs<Verdict.Lost>(lost)
        assertEquals(j("ch9"), lost.losses.single().standing)
        r.apply(lost.effect)
        // the view was given the standing place: its next input expects that, and applies (the mark cleared)
        val next = judge(r, op("phone", "s1", 8, "place", listOf("book.x"), j("ch10"), Expect.Value(j("ch9"))))
        assertEquals(Item(j("ch10"), 6, "phone", NOW), next.effect.items["rec.t.book.x"])
    }

    @Test
    fun aPreConversionPlaceIsJudgedByItsOldStamp() {
        fun pre(at: Long, v: String) = op("phone", "s1", 1, "place", listOf("book.x"), j(v), Expect.PreConversion(at))
        // absent: applies, staying in the pre-conversion world (rev 0, the old stamp)
        assertEquals(Item(j("p"), 0, "phone", 50), judge(rec(), pre(50, "p")).effect.items["rec.t.book.x"])
        // rev 0 with an older stamp: applies
        val older = rec("book.x" to Item(j("q"), 0, "migration", 40))
        assertEquals(Item(j("p"), 0, "phone", 50), judge(older, pre(50, "p")).effect.items["rec.t.book.x"])
        // rev 0 with a newer stamp: loses, said with the standing place
        val newer = judge(rec("book.x" to Item(j("q"), 0, "migration", 60)), pre(50, "p"))
        assertIs<Verdict.Lost>(newer)
        assertEquals(j("q"), newer.losses.single().standing)
        // rev > 0 (written after the conversion: the web, a PC-direct session): loses
        val after = judge(rec("book.x" to Item(j("w"), 1, "desk", 10)), pre(50, "p"))
        assertIs<Verdict.Lost>(after)
        assertEquals("desk", after.losses.single().by)
    }

    // ---- order ----

    // ---- remove ----

    @Test
    fun aRemovalAppliesOnTheRevisionSeenAndLosesWhenWrittenSince() {
        val r = rec("a" to Item(j("A"), 4, "desk", 1), "b" to Item(j("B"), 1, "desk", 1))
        val ok = judge(r, op("phone", "s1", 1, "drop", listOf("a"), JsonNull, Expect.Rev(4)))
        assertEquals(Item(JsonNull, 5, "phone", NOW, gone = true), ok.effect.items["rec.t.a"])
        val lost = judge(r, op("phone", "s1", 1, "drop", listOf("a"), JsonNull, Expect.Rev(3)))
        assertIs<Verdict.Lost>(lost)
        r.apply(ok.effect)
        assertEquals(Verdict.Same, judge(r, op("desk", "s2", 1, "drop", listOf("a"), JsonNull, Expect.Rev(4))), "already removed")
    }

    // ---- whole collection ----

    // ---- edit of a removed item ----

    @Test
    fun anEditOfARemovedItemLosesNamingTheRemoval() {
        val r = rec("water" to Item(JsonNull, 3, "desk", 1402, gone = true))
        val lost = judge(r, op("phone", "s1", 1, "set", listOf("water"), j("done"), Expect.Value(j("open"))))
        assertIs<Verdict.Lost>(lost)
        val loss = lost.losses.single()
        assertTrue(loss.gone)
        assertEquals("desk", loss.by)
        assertEquals(1402L, loss.at)
        assertTrue(lost.effect.items["rec.t.water"]!!.gone, "the item stays removed")
        // a device that saw nothing there does not bring a removed item back either
        val back = judge(r, op("phone", "s1", 1, "set", listOf("water"), j("new"), Expect.Value(JsonNull)))
        assertIs<Verdict.Lost>(back)
        assertTrue(back.losses.single().gone)
    }

    // ---- the three sentences ----

    /** C§2.4: "an op applies when the PC ... already holds what the op would make (a same-value op succeeds and
     *  changes nothing)". The device saw an older value; the PC holds the one the op makes: no loss, no write. */
    @Test
    fun aSameValueOpSucceedsAndChangesNothing() {
        val r = rec("book.x" to Item(j("ch9"), 5, "desk", 300))
        assertEquals(Verdict.Same, judge(r, op("phone", "s1", 1, "place", listOf("book.x"), j("ch9"), Expect.Value(j("ch2")))))
        assertEquals(Verdict.Same, judge(r, op("phone", "s1", 1, "set", listOf("book.x"), j("ch9"), Expect.Value(j("ch2")))))
        // through the host: answered Seen, the item byte-identical, no change published with patches
        val h = rig.opened()
        assertEquals(Answer.Seen(), rig.ask(h, op("desk", "d", 1, "place", listOf("book.x"), j("ch9"), Expect.Value(JsonNull))))
        val before = rig.item(h, "book.x")
        val changes = ArrayList<wm.damage.core.app.Change>()
        h.addListener { changes += it }
        assertEquals(Answer.Seen(), rig.ask(h, op("phone", "s1", 1, "place", listOf("book.x"), j("ch9"), Expect.Value(j("ch2")))))
        assertEquals(before, rig.item(h, "book.x"))
        assertTrue(changes.single().effect.isEmpty())
    }

    /** C§2.4: "No op is ever applied twice": a resent op is answered from the record after the item moved on. */
    @Test
    fun noOpIsAppliedTwice() {
        val h = rig.opened()
        val first = op("phone", "s1", 1, "set", listOf("flag"), j("b"), Expect.Value(JsonNull))
        assertEquals(Answer.Seen(), rig.ask(h, first))
        assertEquals(Answer.Seen(), rig.ask(h, op("desk", "d", 1, "set", listOf("flag"), j("c"), Expect.Value(j("b")))))
        val seq = h.seq
        assertEquals(Answer.Seen(), rig.ask(h, first), "answered as it was the first time")
        assertEquals(Item(j("c"), 2, "desk", rig.now), rig.item(h, "flag"))
        assertEquals(seq, h.seq, "no line, no change")
    }

    /** Dec. A.9: "the book read most recently" is `Expect.Any` — arrival order, never a loss, even over a removal. */
    @Test
    fun anyNeverLoses() {
        val r = rec("recent" to Item(j("book.a"), 8, "desk", 1), "gone" to Item(JsonNull, 2, "desk", 1, gone = true))
        val v = judge(r, op("phone", "s1", 1, "set", listOf("recent"), j("book.b")))
        assertEquals(Item(j("book.b"), 9, "phone", NOW), v.effect.items["rec.t.recent"])
        assertIs<Verdict.Applied>(judge(r, op("phone", "s1", 1, "set", listOf("gone"), j("back"))))
        assertEquals(Verdict.Same, judge(r, op("phone", "s1", 1, "set", listOf("recent"), j("book.a"))))
    }

    @Test
    fun theKindsRefuseTheWrongExpectation() {
        fun bad(action: String, e: Expect, result: kotlinx.serialization.json.JsonElement = JsonNull, t: String = "x") =
            assertNotNull(Merge.check(TEST_ACTIONS.spec("t", action)!!, op("p", "s", 1, action, listOf(t), result, e)), "$action $e")
        bad("set", Expect.Rev(1))
        bad("set", Expect.PreConversion(1))
        bad("place", Expect.Rev(1))
        bad("drop", Expect.Value(JsonNull))
        bad("move", Expect.Value(JsonNull))
        bad("move", Expect.Rev(1), j("x"))
        bad("replace", Expect.Rev(1), JsonObject(emptyMap()), "queue")
        bad("replace", Expect.Rev(1), buildJsonObject { put("order", JsonArray(listOf(j("a")))) }, "other")
        bad("add", Expect.Rev(1), JsonArray(listOf(JsonPrimitive(1))), "s.1.0")
        assertNotNull(Merge.check(TEST_ACTIONS.spec("t", "set")!!, op("p", "s", 1, "set", listOf("a", "a"), j("v"))))
    }
}
