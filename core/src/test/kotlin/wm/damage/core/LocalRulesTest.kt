package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wm.damage.core.geom.Rect
import wm.damage.core.local.BoundList
import wm.damage.core.local.LocalRules
import wm.damage.core.local.LocalRules.Stock
import wm.damage.core.local.LocalRules.Verdict
import wm.damage.core.local.Taps
import wm.damage.core.motion.Motion
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.TextureCache

/**
 * Build C2, the phone's rules as unit tests (`FIRMWARE.md` §6.10's list): the tap rule's condition (4) at n = 0, 1, 2 and
 * after a refused scroll; the per-batch answer (COMMIT of the newest only when every report since the slot's last answer
 * was adopted, nothing else moved the row and no drawing sub meets the areas; DISCARD from A + 1 otherwise; a tap's
 * report never committed); SETTLE from S_low; a delayed scroll message after a SETTLE; P5's count in both orders of
 * `C2-focus.md` 1(a) items 1–2; `tapsLive` and its clears (with BIND in force, decisions M.4); one test per `snapback` and
 * `tapdrop` cause. Written from §6.7's text; the rules' own model is not consulted.
 */
class LocalRulesTest {
    companion object {
        val BAND = Rect(64, 120, 32, 8)
        val recs = IntArray(10) { 64 + 10 * it }
        private val sources = recs.mapIndexed { i, o -> Motion.Source.record(o, TextureCache.Image2(32, 8, ByteArray(256) { (2 + i).toByte() }).encode()) }
        val scroll = Motion.program(200, sources, bit10 = true) {
            track(BAND, Motion.Backdrop.Level(0)) { draw(sources[0], BAND.x, BAND.y, n = 3, prefs = listOf(Motion.Pref(0, Motion.Field.SRC))) }
        }
        val press = Motion.program(260, sources, bit10 = true) {
            track(BAND, Motion.Backdrop.Level(15)) { draw(sources[0], BAND.x, BAND.y, n = 2, opt = 0x20, prefs = listOf(Motion.Pref(0, Motion.Field.SRC))) }
        }
        val P = listOf(CfwModes.BindParam(CfwModes.KIND_ITEM, 0), CfwModes.BindParam(0, 0), CfwModes.BindParam(0, 0), CfwModes.BindParam(0, 0))
        fun list(count: Int = 8, mark: Int = 1) = BoundList(0, 0, count, 8, mark, 400, IntArray(count) { recs[it] }, listOf(
            BoundList.Entry(0, CfwModes.EV_UPROLL, +1, 16, scroll.at, P, scroll),
            BoundList.Entry(1, CfwModes.EV_DOWNROLL, -1, 16, scroll.at, P, scroll),
            BoundList.Entry(2, CfwModes.EV_TAP, 0, 16, press.at, P, press)))
        fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    }

    /** A rules instance at stage 4 with [l] in front at [row], its start batch sent (S_heard 0, armed at uptime 100). */
    private class R(taps: Taps = Taps.REPORT, val l: BoundList = list(), row: Int = 3) {
        val notes = ArrayList<Pair<String, String>>()
        val keyframes = ArrayList<String>()
        val r = LocalRules({ k, d -> notes += k to d }, { keyframes += it })
        var row = row
        var now = 10_000L
        var mark = 0
        init {
            r.sessionStart(true, 0, 100, l, row, taps)
            val start = r.tail(row, now)
            mark = start.first { it[0].toInt() == 27 && it[1].toInt() == 2 }[11].toInt() and 0xFF
        }
        /** A 115 from RIGHT: a scroll that drew lands at [cursor] with the phone's own prediction unless [p] says otherwise. */
        fun scroll(s: Int, cursor: Int, id: Int = CfwModes.EV_UPROLL, outcome: Int = 0, reason: Int? = null, m: Int = mark,
                   p: IntArray? = if (outcome == 0) intArrayOf(recs[cursor.coerceIn(0, 9)], 0, 0, 0) else null, list: Int = 0): Verdict {
            val f = HashMap<Int, Long>(mapOf(1 to s.toLong(), 2 to 4L, 3 to id.toLong(), 4 to (if (id == CfwModes.EV_UPROLL) 0L else 1L),
                5 to outcome.toLong(), 6 to (reason ?: 0).toLong(), 7 to list.toLong(), 8 to cursor.toLong(), 9 to 0L, 15 to 200L, 17 to m.toLong()))
            if (p != null) { for (i in 0..3) f[10 + i] = (p[i] and 0xFFFF).toLong(); f[14] = scroll.at.toLong() }
            return r.report(DamageMsg.Applied(f), row, now).also { applyV(it, if (id == CfwModes.EV_UPROLL) 1 else -1) }
        }
        fun tap(s: Int, cursor: Int, outcome: Int = 0, reason: Int? = null, m: Int = mark, press: Boolean = false): Verdict {
            val f = HashMap<Int, Long>(mapOf(1 to s.toLong(), 2 to 4L, 3 to 0L, 5 to outcome.toLong(), 6 to (reason ?: 0).toLong(), 15 to 200L))
            if (reason != 3 && reason != 14) { f[4] = 2L; f[7] = 0L; f[8] = cursor.toLong(); f[9] = 0L; f[17] = m.toLong() }
            if (press) { f[10] = recs[cursor].toLong(); f[11] = 0; f[12] = 0; f[13] = 0; f[14] = LocalRulesTest.press.at.toLong() }
            return r.report(DamageMsg.Applied(f), row, now).also { applyV(it, 0) }
        }
        fun stock(k: Stock): Verdict = r.stock(k, row, now).also { applyV(it, if (k == Stock.NEXT) 1 else if (k == Stock.PREV) -1 else 0) }
        fun applyV(v: Verdict, step: Int) { when (v) {
            is Verdict.Adopt -> row = v.row
            is Verdict.Apply -> if (v.type != EvenHubMsg.EV_CLICK) row = (row + step + l.count) % l.count
            is Verdict.Act -> row = v.row
            else -> {} } }
        fun batch(denied: Set<Int> = emptySet()): List<ByteArray> = r.tail(row, now, denied)
        fun answer(b: List<ByteArray>) = b.filter { it[0].toInt() == 26 && it[1].toInt() in 3..4 }
    }

    private fun isCommit(b: ByteArray, s: Int) = b[1].toInt() == 3 && u16(b, 3) == s
    /** A DISCARD from [s] — named less [LocalRules.WIDE] on the wire (decisions U.2). */
    private fun isDiscard(b: ByteArray, s: Int, cursor: Int? = null) = b[1].toInt() == 4 && u16(b, 3) == ((s - LocalRules.WIDE) and 0xFFFF) && (cursor == null || u16(b, 6) == cursor)

    // ------------------------------------------------------------------------------- the tap rule's condition (4)
    @Test
    fun tapRuleCondition4AtN0() {
        val t = R(row = 3)
        assertEquals(Verdict.Act(3), t.tap(1, 3), "n = 0, the cursor on the phone's row: acted on")
        val u = R(row = 3)
        assertEquals(Verdict.Nothing, u.tap(1, 4), "n = 0, one row off: dropped (moved)")
        assertTrue(u.notes.any { it.first == "tapdrop" && "cause=moved" in it.second })
    }

    @Test
    fun tapRuleCondition4AtN1AndN2() {
        val t = R(row = 3)
        assertEquals(Verdict.Act(4), t.tap(2, 4), "S 1 missing (n = 1): a cursor one step away is the lost scroll's — R := 4, acted on")
        val u = R(row = 3)
        assertEquals(Verdict.Nothing, u.tap(2, 5), "n = 1: two steps away is not explained")
        val v = R(row = 3)
        assertEquals(Verdict.Act(5), v.tap(3, 5), "n = 2: two steps away is explained")
        val w = R(row = 3)
        w.stock(Stock.NEXT)                                     // an unreported scroll message stood for one missing report
        assertEquals(4, w.row)
        assertEquals(Verdict.Act(5), w.tap(3, 5), "g 2, u 1: n = 1, the cursor one step from the phone's row (4) — acted on")
    }

    @Test
    fun tapRuleCondition4AfterARefusedScroll() {
        val t = R(row = 3)
        t.scroll(1, 3, outcome = 2, reason = 14)                // heard and refused: the phone applies it as today → row 4
        assertEquals(4, t.row)
        assertEquals(Verdict.Nothing, t.tap(2, 3), "no gap: the lens's cursor 3 against row 4 is not explained — dropped")
        assertTrue(t.notes.any { it.first == "tapdrop" && "cause=moved" in it.second })
    }

    // ---------------------------------------------------------------------------------------- the per-batch answer
    @Test
    fun commitOfTheNewestOnlyWhenEveryReportWasAdopted() {
        val t = R(row = 3)
        assertEquals(Verdict.Adopt(4), t.scroll(1, 4))
        assertEquals(Verdict.Adopt(5), t.scroll(2, 5))
        val plan = t.r.plan(t.row)
        assertEquals(listOf(2), plan.map { it.s }, "one COMMIT planned, of the newest")
        val b = t.answer(t.batch())
        assertEquals(1, b.size); assertTrue(isCommit(b[0], 2), "COMMIT(2)")
        // a mixed set: one adopted, one not → DISCARD from A + 1, naming the row
        assertEquals(Verdict.Adopt(6), t.scroll(3, 6))
        t.scroll(4, 7, m = 99)                                  // a stale mark: applied as today → row 7
        val d = t.answer(t.batch())
        assertEquals(1, d.size); assertTrue(isDiscard(d[0], 3, 7), "DISCARD(A + 1 = 3, row 7)")
        assertEquals(4, t.r.slotA(0))
    }

    @Test
    fun anythingElseMovingTheRowTurnsTheAnswerToDiscard() {
        val t = R(row = 3)
        assertEquals(Verdict.Adopt(4), t.scroll(1, 4))
        t.row = 6                                               // the phone's own input moved the row
        val d = t.answer(t.batch())
        assertTrue(isDiscard(d.single(), 1, 6))
    }

    @Test
    fun aDrawingSubMeetingTheAreasTurnsTheCommitToDiscard() {
        val t = R(row = 3)
        t.scroll(1, 4)
        val plan = t.r.plan(t.row).single()
        assertTrue(LocalRules.meets(listOf(Rect(80, 118, 8, 4)), emptyList(), plan.areas), "a draw crossing the band meets")
        assertFalse(LocalRules.meets(listOf(Rect(400, 20, 64, 16)), listOf(Rect(400, 20, 64, 16)), plan.areas), "a cached row outside does not")
        val d = t.answer(t.batch(denied = setOf(0)))
        assertTrue(isDiscard(d.single(), 1, 4))
    }

    @Test
    fun aTapsReportIsNeverCommitted() {
        val t = R(row = 3)
        assertEquals(Verdict.Act(3), t.tap(1, 3))
        assertTrue(t.r.plan(t.row).isEmpty())
        assertTrue(isDiscard(t.answer(t.batch()).single(), 1, 3))
    }

    @Test
    fun aBatchWithAListForTheSlotsListAnswersWithDiscardAfterIt() {
        val t = R(row = 3)
        t.scroll(1, 4)
        t.r.front(list(mark = 7), 5, shift = 1)                 // a re-stage: a LIST rides this batch
        t.row = 5
        assertTrue(t.r.plan(t.row).isEmpty())
        val b = t.batch()
        val li = b.indexOfFirst { it[0].toInt() == 27 && it[1].toInt() == 2 }
        val di = b.indexOfFirst { it[0].toInt() == 26 && it[1].toInt() == 4 }
        assertTrue(li in 0 until di, "the LIST before the DISCARD")
        assertEquals(5, u16(b[di], 6), "naming the row in the LIST's staging")
    }

    // ------------------------------------------------------------------------------------------ SETTLE (P7)
    /** P7 as ruled on DP2's report (decisions U.2): SETTLE names S_heard + 1 less 0x4000 (S_low decided no outcome once
     *  the bound was wide, so it is gone), its list's LIST riding before it (U.1). */
    @Test
    fun settleNamesSHeardPlusOneLessWide() {
        val t = R(row = 3)
        t.scroll(1, 4); t.batch()                               // COMMIT(1)
        t.r.report(DamageMsg.Applied(mapOf(1 to 3L, 2 to 4L, 3 to 0L, 5 to 2L, 6 to 14L, 15 to 200L)), t.row, t.now)
        t.batch()                                               // S 2 lost; S 3 a tap refused 14: the slot's DISCARD
        t.now += LocalRules.Q_MS
        assertTrue(t.r.settleDue(t.now))
        val lone = t.r.loneSettle(t.row, t.now)
        val s = lone.last()
        assertEquals(5, s[1].toInt())
        assertTrue(lone.first()[0].toInt() == 27 && lone.first()[1].toInt() == 2 && lone.first()[7].toInt() == 0 && lone.first()[8].toInt() == 0,
            "the list's LIST with shift 0 rides before the SETTLE (decisions U.1)")
        assertEquals((4 - LocalRules.WIDE) and 0xFFFF, u16(s, 3), "S_heard + 1 (4) less 0x4000")
        assertEquals(4, u16(s, 6), "the phone's row")
        assertEquals(LocalRules.AGE_TICKS, u16(s, 8))
    }

    @Test
    fun noLoneSettleWhileAReportIsUnansweredAndEveryIdleBatchCarriesOne() {
        val t = R(row = 3)
        t.scroll(1, 4)
        t.now += LocalRules.Q_MS + 1
        assertFalse(t.r.settleDue(t.now), "the 115 is not answered yet")
        assertTrue(t.batch().none { it[0].toInt() == 26 && it[1].toInt() == 5 }, "a burst is open: no SETTLE in its batches")
        assertTrue(t.r.settleDue(t.now))
        t.r.loneSettle(t.row, t.now)
        assertTrue(t.batch().any { it[0].toInt() == 26 && it[1].toInt() == 5 }, "idle: every batch carries one")
    }

    @Test
    fun aDelayedScrollMessageAfterASettleIsAppliedAsUnreported() {
        val t = R(row = 3)
        t.scroll(1, 4); t.batch()
        t.now += LocalRules.Q_MS
        t.r.loneSettle(t.row, t.now)                            // owes cleared, the band still held S 1's scroll message
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), t.stock(Stock.NEXT), "no owe left: applied as today")
        assertEquals(5, t.row)
        assertEquals(1, t.r.u)
        val d = t.answer(t.batch())
        assertTrue(isDiscard(d.single(), 2, 5), "it moved the row: the slot's DISCARD in its frame's batch")
        assertTrue(t.notes.any { it.first == "snapback" && "cause=moved" in it.second || "cause=lost-report" in it.second })
    }

    // ------------------------------------------------------------------------------------------ P5, both orders
    @Test
    fun aLostReportOvertakenCountsTwoNotchesInBothOrders() {
        // 1(a) item 1: 115(S2) before S1's scroll message
        val a = R(row = 3)
        assertEquals(Verdict.Adopt(5), a.scroll(2, 5), "g 1, n 1: two steps explained")
        assertEquals(Verdict.Nothing, a.stock(Stock.NEXT), "S1's scroll message pays S2's owe")
        assertEquals(Verdict.Nothing, a.stock(Stock.NEXT), "S2's pays the gap's")
        assertEquals(5, a.row, "two notches, two rows")
        // 1(a) item 2: S1's scroll message first
        val b = R(row = 3)
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), b.stock(Stock.NEXT), "unreported: applied, u 1")
        assertEquals(4, b.row)
        assertEquals(Verdict.Adopt(5), b.scroll(2, 5), "g 1, u 1: n 0, one step from row 4")
        assertEquals(Verdict.Nothing, b.stock(Stock.NEXT))
        assertEquals(5, b.row)
        assertEquals(0, b.r.owesScroll + b.r.owesAny)
    }

    @Test
    fun staleReportsAreDropped_P8() {
        val t = R(row = 3)
        t.scroll(2, 4)
        assertEquals(Verdict.Nothing, t.scroll(2, 4), "a duplicate: not after S_heard")
        val f = mapOf(1 to 9L, 2 to 4L, 3 to 4L, 4 to 0L, 5 to 0L, 7 to 0L, 8 to 5L, 9 to 0L, 15 to 50L, 17 to t.mark.toLong())
        assertEquals(Verdict.Nothing, t.r.report(DamageMsg.Applied(f), t.row, t.now), "before the arming's uptime")
        assertTrue(t.notes.count { "stale" in it.second } >= 2)
    }

    // ------------------------------------------------------------------------------------------ the click and tapsLive
    @Test
    fun anUnowedClickIsReadAgainstTapsLive() {
        val t = R(Taps.GLASSES, row = 3)
        assertEquals(Verdict.Apply(EvenHubMsg.EV_CLICK), t.stock(Stock.CLICK), "tapsLive false: as today, not in u")
        assertEquals(0, t.r.u)
        t.r.telemetry(1L or (1L shl 4) or (1L shl 5), null)
        assertTrue(t.r.tapsLive)
        assertEquals(Verdict.Nothing, t.stock(Stock.CLICK), "glasses, tapsLive: dropped (no-report)")
        assertTrue(t.notes.any { it.first == "tapdrop" && "cause=no-report" in it.second })
        assertTrue(t.answer(t.batch()).single()[1].toInt() == 4, "and the slot's DISCARD")
        val u = R(Taps.REPORT, row = 3)
        u.r.telemetry(1L, null)
        assertEquals(Verdict.Apply(EvenHubMsg.EV_CLICK), u.stock(Stock.CLICK), "report, tapsLive: as today")
        assertEquals(1, u.r.u, "and counted in u")
    }

    @Test
    fun tapsLiveClears() {
        val t = R(Taps.REPORT)
        t.r.telemetry(1L, null); assertTrue(t.r.tapsLive)
        t.r.telemetry(0x30L, null); assertFalse(t.r.tapsLive, "a read without bit 0")
        t.tap(1, 3); t.batch()
        t.r.telemetry(1L, null); assertTrue(t.r.tapsLive)
        t.r.taps(Taps.PHONE); t.batch(); assertFalse(t.r.tapsLive, "CLEAR of the tap entry sent")
        t.r.taps(Taps.REPORT); t.batch(); t.r.telemetry(1L, null); assertTrue(t.r.tapsLive)
        t.r.bindInForce(false); assertFalse(t.r.tapsLive, "BIND not in force (M.4)")
        t.r.telemetry(1L, null); assertFalse(t.r.tapsLive, "field 39's bit alone does not make it true without BIND")
        val u = R(Taps.REPORT); u.r.telemetry(1L, null)
        u.r.sessionStart(true, 0, 100, u.l, 3, Taps.REPORT); assertFalse(u.r.tapsLive, "a session start")
    }

    // ------------------------------------------------------------------------------------------ the causes
    @Test
    fun eachTapdropCause() {
        fun cause(v: R) = v.notes.last { it.first == "tapdrop" }.second.substringAfter("cause=").substringBefore(' ')
        R().let { it.tap(1, 3); it.tap(1, 3); assertEquals("stale", cause(it)) }
        R().let { it.r.front(null, 3); it.tap(1, 3); assertEquals("focus", cause(it)) }
        R().let { it.tap(1, 3, m = 77); assertEquals("restaged", cause(it)) }
        R().let { it.tap(1, 5); assertEquals("moved", cause(it)) }
        R().let { it.tap(1, BoundList.LOST, outcome = 2, reason = 18); assertEquals("lost", cause(it)) }
        R(Taps.GLASSES).let { val f = HashMap(mapOf(1 to 1L, 2 to 4L, 3 to 0L, 4 to 2L, 5 to 0L, 7 to 0L, 8 to 3L, 9 to 0L, 15 to 200L, 17 to it.mark.toLong(),
            10 to recs[5].toLong(), 11 to 0L, 12 to 0L, 13 to 0L, 14 to press.at.toLong()))
            it.r.report(DamageMsg.Applied(f), it.row, it.now); assertEquals("item", cause(it))
            assertTrue(it.notes.any { n -> n.first == "divergence" }); assertEquals(1, it.keyframes.size, "a divergence: one keyframe") }
        R().let { it.tap(1, 3, outcome = 2, reason = 3); assertEquals("refused-3", cause(it)) }
        R().let { it.tap(1, 3, outcome = 2, reason = 14); assertEquals("refused-14", cause(it)) }
        R(Taps.GLASSES).let { it.r.telemetry(1L, null); it.stock(Stock.CLICK); assertEquals("no-report", cause(it)) }
    }

    @Test
    fun eachSnapbackCause() {
        fun cause(v: R): String { v.batch(); return v.notes.last { it.first == "snapback" }.second.substringAfter("cause=").substringBefore(' ') }
        R().let { it.scroll(2, 6); assertEquals("lost-report", cause(it)) }               // two steps from row 3 with n 1: not explained
        R().let { it.scroll(1, 4, m = 9); assertEquals("restaged", cause(it)) }
        R().let { it.scroll(1, 4, p = intArrayOf(1, 2, 3, 4)); assertEquals("restaged", cause(it)) }
        R().let { it.r.front(null, 3); it.scroll(1, 4); assertEquals("focus", cause(it)) }
        R().let { it.scroll(1, 4, outcome = 1); assertEquals("edge", cause(it)) }
        R().let { it.scroll(1, 4, outcome = 2, reason = 3); assertEquals("link", cause(it)) }
        R().let { it.scroll(1, 4); it.batch(denied = setOf(0))     // a drawing sub met the areas: the COMMIT turned
            assertEquals("focus", it.notes.last { n -> n.first == "snapback" }.second.substringAfter("cause=").substringBefore(' ')) }
        R().let { it.scroll(1, 4); it.batch(); it.now += LocalRules.Q_MS; it.r.loneSettle(it.row, it.now)
            it.r.body114(0, 1, 1, null)                                 // RIGHT reports the SETTLE stopping a local
            assertEquals("burst-end", it.notes.last { n -> n.first == "snapback" }.second.substringAfter("cause=").substringBefore(' ')) }
        R().let { it.scroll(1, 4); it.batch(); it.r.body114(0, 1, 2, 12)  // the COMMIT refused
            assertEquals("refused", it.notes.last { n -> n.first == "snapback" }.second.substringAfter("cause=").substringBefore(' ')) }
    }

    // ------------------------------------------------------------------------------------------ refusal records
    @Test
    fun aRefusalRecordNamingMode27SendsTheWholeTableAgain() {
        val t = R(row = 3)
        t.r.telemetry(null, DamageMsg.Refusal(27, 14, 5))
        val b = t.batch()
        assertTrue(b.first().contentEquals(CfwModes.bindClear(CfwModes.BIND_ALL)))
        assertEquals(3, b.count { it[0].toInt() == 27 && it[1].toInt() == 0 }, "every SET")
        val list = b.single { it[0].toInt() == 27 && it[1].toInt() == 2 }
        assertTrue((list[11].toInt() and 0xFF) != t.mark, "a fresh mark")
        assertEquals(1, t.keyframes.size, "and ONE keyframe: the rest of that batch did not run on RIGHT (row A, ruled)")
    }

    @Test
    fun aBatchDroppedWholeResendsTheTableSubsItCarried_M5() {
        val t = R(row = 3)
        t.r.telemetry(null, null)                               // the start's subs judged
        t.r.taps(Taps.GLASSES); t.batch()                       // a SET of the tap entry in its press form
        t.r.telemetry(null, null, DamageMsg.Refusal(8, 14, 9))
        val b = t.batch()
        assertTrue(b.any { it.contentEquals(t.l.set(t.l.entries[2])) }, "the press SET again")
    }

    @Test
    fun aRefusedFlushSendsTheWholeTableAgain() {
        val t = R(row = 3)
        t.batch()
        t.r.tableAgain("test")
        val b = t.batch()
        assertTrue(b.first().contentEquals(CfwModes.bindClear(CfwModes.BIND_ALL)))
        assertTrue(b.any { it[0].toInt() == 27 && it[1].toInt() == 2 }, "the LIST, with a fresh mark")
        assertTrue(b.last()[0].toInt() == 26 && b.last()[1].toInt() == 5, "and the idle SETTLE after it, which realigns the cursor")
        assertTrue(t.notes.any { it.first == "repair" && "object=table" in it.second })
    }

    /** Decisions U.1: every COMMIT, DISCARD or SETTLE naming a list rides behind that list's LIST with shift 0 (its
     *  current mark), and the riding LIST never turns a COMMIT into a DISCARD. U.2: DISCARD's and SETTLE's S less 0x4000. */
    @Test
    fun everyAnswerNamingAListRidesBehindItsList_U1_U2() {
        val t = R(row = 3)
        t.scroll(1, 4)
        val b = t.batch()
        val li = b.indexOfFirst { it[0].toInt() == 27 && it[1].toInt() == 2 }
        val ci = b.indexOfFirst { it[0].toInt() == 26 && it[1].toInt() == 3 }
        assertTrue(li in 0 until ci, "the LIST before the COMMIT, and the answer still a COMMIT")
        assertEquals(0, u16(b[li], 7), "shift 0"); assertEquals(t.mark, b[li][11].toInt() and 0xFF, "the current mark, not a fresh one")
        t.scroll(2, 5, m = 99)                                  // not adopted: a DISCARD, its LIST before it
        val d = t.batch()
        val dl = d.indexOfFirst { it[0].toInt() == 27 && it[1].toInt() == 2 }
        val di = d.indexOfFirst { it[0].toInt() == 26 && it[1].toInt() == 4 }
        assertTrue(dl in 0 until di)
        assertEquals((2 - LocalRules.WIDE) and 0xFFFF, u16(d[di], 3), "DISCARD from A + 1 less 0x4000")
        t.now += LocalRules.Q_MS; t.r.loneSettle(t.row, t.now)
        val idle = t.batch()
        assertEquals(listOf(27 to 2, 26 to 5), idle.map { (it[0].toInt() and 0xFF) to it[1].toInt() }, "an idle batch: the LIST, then the SETTLE")
        t.r.front(null, 3)                                      // covered: the SETTLE names no list, so no LIST rides
        val covered = t.batch().filter { it[0].toInt() == 26 || (it[0].toInt() == 27 && it[1].toInt() == 2) }
        assertEquals(listOf(26 to 5), covered.map { (it[0].toInt() and 0xFF) to it[1].toInt() })
    }

    @Test
    fun belowStage4NothingIsPlannedOrSent() {
        val r = LocalRules()
        r.sessionStart(false, 0, 0, list(), 3, Taps.REPORT)
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), r.stock(Stock.NEXT, 3, 0))
        assertTrue(r.tail(3, 0).isEmpty() && r.plan(3).isEmpty() && !r.owed)
    }
}
