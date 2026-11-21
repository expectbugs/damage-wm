package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wm.damage.core.geom.Rect
import wm.damage.core.local.BoundList
import wm.damage.core.local.EngineRefusals
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
        fun list(count: Int = 8, mark: Int = 1, ring: Boolean = false, params: List<CfwModes.BindParam> = P) =
            BoundList(0, 0, count, 8, mark, 400, IntArray(count) { recs[it] }, listOf(
                BoundList.Entry(0, CfwModes.EV_UPROLL, +1, 16, scroll.at, params, scroll),
                BoundList.Entry(1, CfwModes.EV_DOWNROLL, -1, 16, scroll.at, params, scroll),
                BoundList.Entry(2, CfwModes.EV_TAP, 0, 16, press.at, P, press)), ring = ring)
        /** C3 DM-0: a parameter set with a kind-2 value (P1 = c' × pitch, or v × pitch on a ring fire that wrapped). */
        val PK2 = listOf(CfwModes.BindParam(CfwModes.KIND_ITEM, 0), CfwModes.BindParam(CfwModes.KIND_PITCH, 0), CfwModes.BindParam(0, 0), CfwModes.BindParam(0, 0))
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
            mark = start.first { it[0].toInt() == 27 && (it[1].toInt() == 2 || it[1].toInt() == CfwModes.BIND_LIST_RING) }[11].toInt() and 0xFF
        }
        /** A 115 from RIGHT: a scroll that drew lands at [cursor] with the phone's own prediction unless [p] says otherwise.
         *  [wrapped]: field 18 = 1 (a ring fire across the wrap, C3 DM-0). */
        fun scroll(s: Int, cursor: Int, id: Int = CfwModes.EV_UPROLL, outcome: Int = 0, reason: Int? = null, m: Int = mark,
                   p: IntArray? = if (outcome == 0) intArrayOf(recs[cursor.coerceIn(0, 9)], 0, 0, 0) else null, list: Int = 0,
                   wrapped: Boolean = false): Verdict {
            val f = HashMap<Int, Long>(mapOf(1 to s.toLong(), 2 to 4L, 3 to id.toLong(), 4 to (if (id == CfwModes.EV_UPROLL) 0L else 1L),
                5 to outcome.toLong(), 6 to (reason ?: 0).toLong(), 7 to list.toLong(), 8 to cursor.toLong(), 9 to 0L, 15 to 200L, 17 to m.toLong()))
            if (p != null) { for (i in 0..3) f[10 + i] = (p[i] and 0xFFFF).toLong(); f[14] = scroll.at.toLong() }
            if (wrapped) f[18] = 1L
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

    /** A LIST of either form (mode 27 sub 2 or sub 4). */
    private fun isList(b: ByteArray) = b[0].toInt() == 27 && (b[1].toInt() == 2 || b[1].toInt() == CfwModes.BIND_LIST_RING)
    private fun isListRing(b: ByteArray) = b[0].toInt() == 27 && b[1].toInt() == CfwModes.BIND_LIST_RING && b.size == 13 && b[12].toInt() == 1

    /** C3 DM-0 (S4, `FIRMWARE.md` §7.11): on a ring list a wrap notch's report — cursor the other end, field 18 = 1, its
     *  kind-2 parameter at the virtual row v — is adopted: P3 measures the distance modulo the count and predicts at v; the
     *  answer is the COMMIT of the report's own parameters naming the absolute cursor. Both ways. A report whose kind-2
     *  value is c' × pitch (not v × pitch) is not the prediction (`restaged`). The tap rule's (4) is modular too. */
    @Test
    fun aWrapNotchOnARingListIsAdoptedModuloTheCountAndPredictedAtV_DM0() {
        val t = R(l = list(ring = true, params = PK2), row = 7)
        // down from the last row: v = 8, P1 = 8 × pitch 8 = 64
        assertEquals(Verdict.Adopt(0), t.scroll(1, 0, wrapped = true, p = intArrayOf(recs[0], 64, 0, 0)), "${t.notes}")
        val b = t.batch()
        val c = b.indexOfFirst { isCommit(it, 1) }
        assertTrue(c >= 0 && u16(b[c], 6) == 0, "the COMMIT names the absolute cursor 0: ${b.map { it.joinToString("") { x -> "%02x".format(x) } }}")
        assertTrue(isListRing(b[c - 1]), "the LIST riding before the answer is LIST-RING with flags 1 (S5)")
        // up from row 0: v = −1, P1 = −8
        assertEquals(Verdict.Adopt(7), t.scroll(2, 7, id = CfwModes.EV_DOWNROLL, wrapped = true, p = intArrayOf(recs[7], -8, 0, 0)), "${t.notes}")
        assertTrue(t.batch().any { isCommit(it, 2) })
        // the prediction is at v, not at c': a wrapped report carrying c' × pitch names another staging
        val w = R(l = list(ring = true, params = PK2), row = 7)
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), w.scroll(1, 0, wrapped = true, p = intArrayOf(recs[0], 0, 0, 0)))
        assertTrue(w.r.repair.owedObjects().isNotEmpty(), "the table repair took it as evidence (it calls the same prediction): ${w.r.repair.owedObjects()}")
        // the tap rule's (4): a tap at the other end after a lost wrap notch (n = 1) is one row away, corrected and acted on
        val tp = R(l = list(ring = true), row = 7)
        assertEquals(Verdict.Act(0), tp.tap(2, 0), "${tp.notes}")
        // C3 X2M0 (decisions AZ (2)): an edge reported at the end of a ring list — a lens state no single lost or refused
        // message leaves on RIGHT — is answered as at 416 and 480: applied as today, the DISCARD naming the wrapped row
        // behind the riding LIST-RING; the shell's ring staging gives no wrap pan, so its frames draw the row
        val ed = R(l = list(ring = true), row = 7)
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), ed.scroll(1, 7, outcome = DamageMsg.APPLIED_EDGE), "${ed.notes}")
        val eb = ed.batch()
        val d = eb.indexOfFirst { it[0].toInt() == 26 && it[1].toInt() == 4 && u16(it, 6) == 0 }
        assertTrue(d > 0 && isListRing(eb[d - 1]), "the DISCARD naming row 0 behind a LIST-RING: ${eb.map { it.joinToString("") { x -> "%02x".format(x) } }}")
    }

    /** C3 DM-0 (S5, the class): on a ring list EVERY LIST the rules write is LIST-RING with flags 1 — the start's, the one
     *  riding before a COMMIT, a DISCARD, an idle SETTLE and the lone SETTLE, the whole table again (a refusal record
     *  naming 27, one naming 26 through the gate, a refused flush) and a re-staging's. A plain LIST clears RING on the
     *  glasses (§7.8). (The batch-14 re-send writes no LIST since C3 X3M0, decisions BB (1).) */
    @Test
    fun everyListTheRulesWriteForARingListIsListRing_DM0() {
        val t = R(l = list(ring = true), row = 3)
        val all = ArrayList<ByteArray>()
        fun take(b: List<ByteArray>, what: String) { assertTrue(b.any { isList(it) }, "$what: a LIST rides"); all += b }
        t.r.sessionStart(true, 0, 100, t.l, 3, Taps.REPORT); take(t.batch(), "the start")
        t.scroll(1, 4); take(t.batch(), "a COMMIT")
        t.scroll(2, 6); take(t.batch(), "a DISCARD")                   // a jump the gap does not explain
        t.now += 5_000; take(t.r.loneSettle(t.row, t.now), "the lone SETTLE")
        take(t.batch(), "an idle SETTLE")
        t.r.tableAgain("a refused flush"); take(t.batch(), "the table again")
        t.r.telemetry(null, DamageMsg.Refusal(27, 12, 1)); take(t.batch(), "a refusal record naming 27")
        t.r.telemetry(null, null, null, wm.damage.core.local.EngineRefusals.Repair(DamageMsg.Refusal(26, 12, 2), "COMMIT slot 0 ×1")); take(t.batch(), "the gate's repair of a 26")
        t.r.front(list(ring = true, mark = 2), t.row); take(t.batch(), "a re-staging")
        val lists = all.filter { isList(it) }
        assertTrue(lists.isNotEmpty() && lists.all { isListRing(it) }, "every LIST is LIST-RING flags 1: ${lists.map { it.joinToString("") { x -> "%02x".format(x) } }}")
    }

    /** Build C3 (decisions W, D-8): a tap refused 19 — an owner in its close set (§7.3), refusal 17's twin (§7.14) — is
     *  acted on and counted `tapActedNoPress` as 17 is, though its body carries the press's parameters (fields 10–14). */
    @Test
    fun aTapRefused19CountsAsNoPressLike17_7_3() {
        for (reason in listOf(17, 19)) {
            val t = R(taps = Taps.GLASSES, row = 3)
            assertEquals(Verdict.Act(3), t.tap(1, 3, outcome = DamageMsg.APPLIED_REFUSED, reason = reason, press = true), "reason $reason: ${t.notes}")
            assertEquals(1, t.r.counts["tapActedNoPress"] ?: 0, "reason $reason: ${t.r.counts}")
        }
    }

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

    /** The focused read of B082 (N1): BIND cleared by hand inside a burst's wait — no SETTLE can close the burst any more
     *  (none is sent unbound), so the read that says BIND is off closes it; else the save, a re-stage's pieces and the
     *  period waited on it until the next session. */
    @Test
    fun aBurstIsClosedWhenBindGoes_N1() {
        val t = R(row = 3)
        t.scroll(1, 4); t.batch()
        assertTrue(t.r.burstOpen, "a burst open after the report")
        t.r.bindInForce(false)
        assertTrue(!t.r.burstOpen, "closed once BIND is no longer in force")
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
        t.now += t.r.q
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
        t.now += t.r.q + 1
        assertFalse(t.r.settleDue(t.now), "the 115 is not answered yet")
        assertTrue(t.batch().none { it[0].toInt() == 26 && it[1].toInt() == 5 }, "a burst is open: no SETTLE in its batches")
        assertTrue(t.r.settleDue(t.now))
        t.r.loneSettle(t.row, t.now)
        assertTrue(t.batch().any { it[0].toInt() == 26 && it[1].toInt() == 5 }, "idle: every batch carries one")
    }

    /** C1 (decisions BM.2; his late echoes, `B083-real.md`): the scroll owe a burst's lone SETTLE leaves open is carried
     *  for one band, 250 ms, while the list is in front, so S 1's scroll message, which the band still held, pays it —
     *  one notch from row 3 ends on row 4, nothing moves the row again, no DISCARD. Once the carry has run out a scroll
     *  message with no owe is unreported as before: applied as today, u 1, the slot's DISCARD in its frame's batch.
     *  Watched to fail with the carry off (row 5 after one notch). The times are literal (B083 v3, decisions BO.3): the
     *  row does not move with [LocalRules.BAND_DRAIN_MS], so a carry too long fails it as one too short does. */
    @Test
    fun aDelayedScrollMessageAfterASettleIsPaidByTheCarriedOwe_C1() {
        val t = R(row = 3)
        t.scroll(1, 4); t.batch()
        t.now += t.r.q
        t.r.loneSettle(t.row, t.now)                            // the band still holds S 1's scroll message
        t.now += 249
        assertEquals(Verdict.Nothing, t.stock(Stock.NEXT), "inside the carry: the late echo pays its owe")
        assertEquals(4, t.row, "one notch from row 3, one row")
        assertEquals(0, t.r.u)
        assertEquals(1, t.r.counts["lateEcho"])
        assertTrue(t.answer(t.batch()).isEmpty(), "nothing moved the row: no answer")
        // past the carry: unreported, as before
        val v = R(row = 3)
        v.scroll(1, 4); v.batch()
        v.now += v.r.q
        v.r.loneSettle(v.row, v.now)
        v.now += 250
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), v.stock(Stock.NEXT), "the carry ran out: applied as today")
        assertEquals(5, v.row)
        assertEquals(1, v.r.u)
        assertEquals(1, v.r.counts["owesExpired"])
        val d = v.answer(v.batch())
        assertTrue(isDiscard(d.single(), 2, 5), "it moved the row: the slot's DISCARD in its frame's batch")
        assertTrue(v.notes.any { it.first == "snapback" && "cause=lost-report" in it.second })
    }

    /** C1's envelope (`FORK.md` §3.8): the carry holds owes by direction (C4 counts a message of the other direction
     *  `lateEchoUnpaid`), ends when the list leaves the front (a late echo then reaching the window above it is that
     *  window's notch, counted), at BIND read off and at a session start; click owes clear at the SETTLE as before. */
    @Test
    fun theCarryHoldsByDirectionAndEndsWhereItsListGoes_C1() {
        fun settled(): R { val t = R(row = 3); t.scroll(1, 4); t.batch(); t.now += t.r.q; t.r.loneSettle(t.row, t.now); return t }
        val a = settled()
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_TOP), a.stock(Stock.PREV), "a PREV meets only a NEXT owe: unreported")
        assertEquals(1, a.r.counts["lateEchoUnpaid"])
        assertEquals(Verdict.Nothing, a.stock(Stock.NEXT), "the NEXT's late echo still pays its own")
        val b = settled()
        b.r.front(null, b.row)                                  // a cover: the list leaves the front
        assertEquals(0, b.r.owesScroll, "the carry ends with its list in front")
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), b.stock(Stock.NEXT), "unbound: as today, for what is in front")
        assertEquals(1, b.r.counts["lateEchoUnpaid"])
        assertEquals(1, b.r.counts["owesExpired"])
        val c = settled()
        c.r.bindInForce(false)
        assertEquals(0, c.r.owesScroll, "BIND read off ends the carry")
        val d = settled()
        d.r.sessionStart(true, 0, 100, d.l, d.row, Taps.REPORT)
        assertEquals(0, d.r.owesScroll, "a session start ends the carry")
        val e = R(row = 3)
        e.tap(1, 3); e.batch(); e.now += e.r.q; e.r.loneSettle(e.row, e.now)
        assertEquals(0, e.r.owesClick, "click owes are not carried")
    }

    /** The carry's end in literal numbers (B083 v3, decisions BO.3; the focused read's 3): two owes left open at the lone
     *  SETTLE are carried 500 ms — a late echo at +480 ms pays one, a scroll message at +501 ms finds the carry run out and
     *  is applied as an unreported notch. Fails with the window too long (20 times: the +501 ms message is paid) as with it
     *  too short or off. */
    @Test
    fun aCarryOfTwoOwesEndsFiveHundredMsAfterTheSettle_C1() {
        val t = R(row = 3)
        t.scroll(1, 4); t.scroll(2, 5); t.batch()               // two notches adopted; the band holds both messages
        t.now += t.r.q
        t.r.loneSettle(t.row, t.now)
        t.now += 480
        assertEquals(Verdict.Nothing, t.stock(Stock.NEXT), "+480 ms: a late echo pays a carried owe")
        t.now += 21
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), t.stock(Stock.NEXT), "+501 ms: the carry ran out, the message is unreported")
        assertEquals(6, t.row)
        assertEquals(1, t.r.counts["owesExpired"], "the second owe ran out unpaid")
    }

    /** B083 v3 (decisions BO.1; the focused read's f3): a carry still running when a tap's report opens a burst joins it,
     *  and the act opens a window — the list leaves the front inside that burst, and the carry ends there as it does with
     *  no burst open, so a notch in the window after its CLEAR landed on RIGHT (no 115) is applied there, as on 0.82.
     *  Watched to fail on `1fbd543a` (`Nothing`: the joined owe swallowed the window's notch). */
    @Test
    fun aCarryEndsWhenItsListLeavesTheFrontInsideATapsBurst_BO1() {
        val t = R(row = 3)
        t.scroll(1, 4); t.batch()                               // S 1 adopted; its scroll message never comes (the band)
        t.now += t.r.q
        t.r.loneSettle(t.row, t.now)                            // one NEXT owe carried for 250 ms
        t.now += 150
        assertEquals(Verdict.Act(4), t.tap(2, 4), "a tap inside the carry")
        assertTrue(t.r.burstOpen)
        assertEquals(1, t.r.owesScroll, "the carry joined the tap's burst")
        assertEquals(Verdict.Nothing, t.stock(Stock.CLICK), "the tap's click pays its own owe")
        t.r.front(null, -1); t.r.tail(-1, t.now)                // the act opened a window: the pump's sync, the burst open
        assertEquals(0, t.r.owesScroll, "the carry ends with its list leaving the front, inside the burst too")
        assertEquals(1, t.r.counts["owesExpired"])
        t.now += 250                                            // a notch in the window, RIGHT's entries CLEARed: no 115
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), t.r.stock(Stock.NEXT, -1, t.now), "the window's own notch is applied there")
    }

    /** `carryMaxMs` (B083 v3, decisions BO.2; counting only): the longest carry of the session, from the lone SETTLE that
     *  began it to its end — the window running out (two owes: 500 ms, one of them paid on the way), the payment that left
     *  no scroll owe (one owe paid at +120 ms: shorter, the count stays), a carry a new burst joined running on through that
     *  burst and its SETTLE's new window (100 ms + Q + 500 ms). */
    @Test
    fun carryMaxMsIsTheLongestCarryFromItsSettleToItsEnd_BO2() {
        val t = R(row = 3)
        t.scroll(1, 4); t.scroll(2, 5); t.batch()
        t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        t.now += 100
        assertEquals(Verdict.Nothing, t.stock(Stock.NEXT), "one late echo")
        t.now += 600; t.batch()                                 // an idle batch past the window: the carry ran out at +500
        assertEquals(500, t.r.counts["carryMaxMs"], "ran out: lone SETTLE to its window's end")
        t.scroll(3, 6); t.batch(); t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        t.now += 120
        assertEquals(Verdict.Nothing, t.stock(Stock.NEXT), "the late echo pays the last owe: that carry ends at +120 ms")
        assertEquals(500, t.r.counts["carryMaxMs"], "a shorter carry leaves the longest")
        val v = R(row = 3)
        v.scroll(1, 4); v.batch(); v.now += v.r.q; v.r.loneSettle(v.row, v.now)
        v.now += 100
        v.scroll(2, 5); v.batch()                               // a new burst joins the running carry
        v.now += v.r.q; v.r.loneSettle(v.row, v.now)            // two owes carried again: 500 ms
        v.now += 600; v.batch()
        assertEquals(100 + v.r.q.toInt() + 500, v.r.counts["carryMaxMs"], "a joined carry runs on to its last window's end")
    }

    /** `lateEcho` inside the next burst (B083 v3, decisions BO.4; counting only): a carried NEXT owe joined by a new burst
     *  whose own report owes a NEXT too. The burst's own owe is paid first (a report's echo comes within tens of ms of it),
     *  so the first NEXT message is counted as its echo and the second, which pays beyond the burst's own owes, as the
     *  late echo: one late echo inside the next burst. A joined owe whose message never comes is not counted. */
    @Test
    fun aLateEchoPaidInsideTheNextBurstIsCounted_BO4() {
        val t = R(row = 3)
        t.scroll(1, 4); t.batch(); t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        t.now += 100
        t.scroll(2, 5); t.batch()                               // a new burst joins the carried NEXT owe
        assertTrue(t.r.burstOpen)
        assertEquals(Verdict.Nothing, t.stock(Stock.NEXT), "the first NEXT: the burst's own echo")
        assertEquals(null, t.r.counts["lateEcho"], "the burst's own owe is paid first")
        assertEquals(Verdict.Nothing, t.stock(Stock.NEXT), "the second NEXT pays the joined owe")
        assertEquals(1, t.r.counts["lateEcho"], "a late echo inside the next burst is counted")
        assertEquals(5, t.row)
        // a joined owe whose message never comes: the burst's own echo is not a late echo
        val v = R(row = 3)
        v.scroll(1, 4); v.batch(); v.now += v.r.q; v.r.loneSettle(v.row, v.now)
        v.now += 100
        v.scroll(2, 5); v.batch()
        assertEquals(Verdict.Nothing, v.stock(Stock.NEXT))
        assertEquals(null, v.r.counts["lateEcho"], "only the burst's own echo came")
    }

    /** `lateEchoOtherFront` (B083 v3, decisions BO.4 — BM.2's "counted first", counting only): a scroll message reaching
     *  another front inside the window of a carry that the front's change dropped — a window opened inside a tap's burst
     *  (+200 ms, counted; +300 ms, past the 250 ms window, not), and a cover with no burst open; a message reaching the
     *  carry's own list again after Back is not counted. Whether it is the late echo or the front's own notch is not told
     *  apart: it is applied there as today. */
    @Test
    fun aScrollMessageReachingAnotherFrontInsideADroppedCarryIsCounted_BO4() {
        val t = R(row = 3)
        t.scroll(1, 4); t.batch(); t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        t.now += 100
        t.tap(2, 4); t.stock(Stock.CLICK)
        t.r.front(null, -1); t.r.tail(-1, t.now)                // the act opened a window: the carry is dropped
        t.now += 100
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), t.r.stock(Stock.NEXT, -1, t.now))
        assertEquals(1, t.r.counts["lateEchoOtherFront"], "+200 ms, the window in front: counted")
        t.now += 100
        t.r.stock(Stock.NEXT, -1, t.now)
        assertEquals(1, t.r.counts["lateEchoOtherFront"], "+300 ms: past the carry's window")
        val c = R(row = 3)
        c.scroll(1, 4); c.batch(); c.now += c.r.q; c.r.loneSettle(c.row, c.now)
        c.r.front(null, c.row)                                  // a cover, no burst open
        c.now += 100
        c.stock(Stock.NEXT)
        assertEquals(1, c.r.counts["lateEchoOtherFront"], "under a cover: counted")
        c.r.front(c.l, c.row); c.batch()                        // Main again
        c.now += 50
        c.stock(Stock.NEXT)
        assertEquals(1, c.r.counts["lateEchoOtherFront"], "the carry's own list in front again: not another front")
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

    /** B083 fix stage (item 4; the focused read's 4, the fuzz's seed 100100487): a scroll message pays an owe of its own
     *  direction, else one of either kind, else it is unreported. A NEXT reported and adopted, then a PREV message no report
     *  owns (a notch RIGHT did not take): unreported — applied as today, counted in u — not the NEXT's echo; the NEXT's own
     *  message pays its owe. Paid by the PREV, the NEXT's own message was unreported as well: the NEXT moved the row twice. */
    @Test
    fun aScrollMessagePaysAnOweOfItsOwnDirection_B083F4() {
        val t = R(row = 3)
        assertEquals(Verdict.Adopt(4), t.scroll(1, 4), "a NEXT adopted: one NEXT owe")
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_TOP), t.stock(Stock.PREV), "a PREV with no owe of its direction: unreported")
        assertEquals(1, t.r.u, "the PREV counted in u, not taken as the NEXT's echo")
        assertEquals(Verdict.Nothing, t.stock(Stock.NEXT), "the NEXT's own message pays its owe")
        assertEquals(3, t.row, "a NEXT and a PREV: the row where it began")
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
        R().let { it.scroll(1, 4); it.batch(); it.now += it.r.q; it.r.loneSettle(it.row, it.now)
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

    /** C3 X3M0 (decisions BB (1), a removal): a record naming a batch dropped whole, read while an adopted notch's answer
     *  is owed, re-sends the entries it carried and no LIST of its own — the answer stays the COMMIT, behind the LIST that
     *  rides before every answer (the list's current mark). With the LIST re-sent as a table object the batch carried a
     *  LIST for the slot's list, P3 made the answer a DISCARD and the note said `snapback cause=lost-report`. */
    @Test
    fun aBatchDroppedWholeReadWhileAnAnswerIsOwedKeepsTheCommit_X3M0() {
        for (ring in listOf(false, true)) {
            val t = R(l = list(ring = ring), row = 3)
            assertEquals(Verdict.Adopt(4), t.scroll(1, 4), "ring $ring: ${t.notes}")
            t.r.telemetry(null, null, DamageMsg.Refusal(8, 14, 9))           // the start's batch is in the span
            val b = t.batch()
            val c = b.indexOfFirst { it[0].toInt() == 26 && isCommit(it, 1) }
            assertTrue(c > 0, "ring $ring: the answer is the COMMIT: ${b.map { it.joinToString("") { x -> "%02x".format(x) } }}")
            assertTrue(b.none { it[0].toInt() == 26 && it[1].toInt() == 4 }, "ring $ring: no DISCARD")
            assertTrue(isList(b[c - 1]) && (b[c - 1][11].toInt() and 0xFF) == t.mark && b.count { isList(it) } == 1,
                "ring $ring: one LIST, the riding one with the current mark, right before the COMMIT")
            assertTrue(t.notes.none { it.first == "snapback" }, "ring $ring: no snap-back: ${t.notes}")
            assertTrue(b.any { it.contentEquals(t.l.set(t.l.entries[0])) }, "ring $ring: the entries the dropped batch carried go again")
        }
    }

    /** The lead's ruling A1 on FR-083, the batch-14 re-send's span (its class): a batch carrying the press SET, then two
     *  reads answered before RIGHT handled it (500 ms apart, the cache count's pace), then the read that shows the batch
     *  dropped whole — the SET is still in the span and goes again. Without the floor the first read had emptied the
     *  span and nothing was re-sent. Once a read has passed AND [EngineRefusals.SPAN_FLOOR_MS], the object leaves. */
    @Test
    fun aBatchDroppedWholeReadTwoReadsLaterStillResendsWhatItCarried_A1() {
        val t = R(row = 3)
        t.now += 10_000; t.r.telemetry(null, null, nowMs = t.now)            // the start's subs: read once, and long ago
        t.r.taps(Taps.GLASSES); t.batch()                                    // a SET of the tap entry in its press form
        t.now += 500; t.r.telemetry(null, null, nowMs = t.now)
        t.now += 500; t.r.telemetry(null, null, nowMs = t.now)
        t.now += 300; t.r.telemetry(null, null, DamageMsg.Refusal(8, 14, 9), nowMs = t.now)
        val b = t.batch()
        assertTrue(b.any { it.contentEquals(t.l.set(t.l.entries[2])) }, "the press SET again, two reads later")
        assertTrue(b.none { it.contentEquals(t.l.set(t.l.entries[0])) || it.contentEquals(t.l.set(t.l.entries[1])) },
            "not the start's scroll SETs, read once and past the floor: ${t.notes.filter { it.first == "repair" }}")
        // the floor is a floor: a read past it, then a batch dropped whole re-sends nothing of that batch
        t.now += EngineRefusals.SPAN_FLOOR_MS; t.r.telemetry(null, null, nowMs = t.now)
        t.now += 100; t.r.telemetry(null, null, DamageMsg.Refusal(8, 14, 10), nowMs = t.now)
        assertTrue(t.batch().none { it.contentEquals(t.l.set(t.l.entries[2])) }, "out of the span once a read and the floor have passed")
    }

    /** The lead's ruling A3 on FR-083: no repair at a read whose flags show BIND gone (a release point on RIGHT; the shell
     *  hands the read's flags to `bindInForce` before `telemetry`) — a fresh record naming 26 with a batch behind it, one
     *  naming 27, a batch dropped whole: no keyframe asked, nothing queued, each journaled. The control: with BIND in force
     *  again the same kind of record is repaired. */
    @Test
    fun noRepairAtAReadWhoseFlagsShowBindGone_A3() {
        val t = R(row = 3)
        t.r.taps(Taps.GLASSES); t.batch()                                    // a table object in the batch-14 span
        t.r.bindInForce(false)
        t.r.telemetry(null, null, refusal26 = EngineRefusals.Repair(DamageMsg.Refusal(26, 3, 7), "CLOSE slot 2 ×1"))
        t.r.telemetry(null, DamageMsg.Refusal(27, 3, 8))
        t.r.telemetry(null, null, DamageMsg.Refusal(8, 14, 9))
        assertEquals(emptyList<String>(), t.keyframes, "no keyframe asked with BIND gone")
        assertFalse(t.r.owed, "nothing queued with BIND gone")
        assertEquals(3, t.notes.count { it.first == "repair" && "object=none: BIND is not in force" in it.second }, "each said: ${t.notes}")
        t.r.bindInForce(true)
        t.r.telemetry(null, null, refusal26 = EngineRefusals.Repair(DamageMsg.Refusal(26, 14, 10), "CLOSE slot 2 ×1"))
        assertEquals(1, t.keyframes.size, "with BIND in force: repaired")
        assertTrue(t.r.owed, "the table queued")
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
        t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        val idle = t.batch()
        assertEquals(listOf(27 to 2, 26 to 5), idle.map { (it[0].toInt() and 0xFF) to it[1].toInt() }, "an idle batch: the LIST, then the SETTLE")
        t.r.front(null, 3)                                      // covered: the SETTLE names no list, so no LIST rides
        val covered = t.batch().filter { it[0].toInt() == 26 || (it[0].toInt() == 27 && it[1].toInt() == 2) }
        assertEquals(listOf(26 to 5), covered.map { (it[0].toInt() and 0xFF) to it[1].toInt() })
    }

    /** Stage DI (decisions X.2 and Z, `BoundList.names`): Main's band form switched by one item-table write — a new list,
     *  the same mark and table place, whose `previous` is the list before until that write is acknowledged. The switch
     *  sends no table message. A report from a fire before the switch names the old form in its P: a notch onto that row is
     *  adopted and its COMMIT carries the report's own P (the phone holds that picture), a pressed tap there is acted on;
     *  the new form is named too; a P naming neither is not adopted. */
    @Test
    fun aReportNamingTheFormBeforeASwitchIsAdoptedWithItsOwnP_DI() {
        val l1 = list()
        val moved = IntArray(8) { recs[it] }.also { it[4] = recs[9] }     // row 4's band form moved to another slot
        val l2 = BoundList(0, 0, 8, 8, l1.mark, l1.items4, moved, l1.entries).also { it.previous = l1 }
        val t = R(Taps.GLASSES, l = l1, row = 3)
        t.r.front(l2, t.row)
        val sw = t.batch()
        assertTrue(sw.none { it[0].toInt() == 27 && it[1].toInt() in 0..1 }, "a band switch sends no SET or CLEAR")
        assertTrue(sw.filter { it[0].toInt() == 27 && it[1].toInt() == 2 }.all { (it[11].toInt() and 0xFF) == t.mark }, "nor a LIST with a fresh mark (the idle SETTLE's riding LIST names the current one)")
        assertEquals(Verdict.Adopt(4), t.scroll(1, 4, p = intArrayOf(recs[4], 0, 0, 0)), "the old form at row 4: adopted")
        val c = t.answer(t.batch()).single()
        assertEquals(3, c[1].toInt(), "a COMMIT")
        assertEquals(recs[4], u16(c, 10), "the COMMIT carries the report's own P0 (the old form's slot), not the new list's")
        t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        assertEquals(Verdict.Act(4), t.tap(2, 4, press = true), "a pressed tap naming the old form: acted on")
        t.batch()
        t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        assertEquals(Verdict.Adopt(3), t.scroll(3, 3, id = CfwModes.EV_DOWNROLL))
        t.batch(); t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        assertEquals(Verdict.Adopt(4), t.scroll(4, 4, p = intArrayOf(recs[9], 0, 0, 0)), "the new form at row 4: adopted")
        t.batch(); t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        assertEquals(Verdict.Adopt(3), t.scroll(5, 3, id = CfwModes.EV_DOWNROLL))
        t.batch(); t.now += t.r.q; t.r.loneSettle(t.row, t.now)
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), t.scroll(6, 4, p = intArrayOf(recs[7], 0, 0, 0)), "a P naming neither form: not adopted")
    }

    /** Decisions AF.2 (review V1Db §2, its test turned into the pin): a list withdrawn while Main stays in front (the
     *  shell hands the surface's own row, offered or not); a 115 fired before the CLEAR landed is applied as today, and its
     *  DISCARD names the CURRENT row; a second notch after the CLEAR moves the row as today; the list's return inside the
     *  burst names the current row again (a DISCARD after its SETs), so the next fire starts where the frame is. */
    @Test
    fun aWithdrawnListsAnswersAndItsReturnNameTheCurrentRow_AF2() {
        val l = list()
        val r = LocalRules()
        var now = 10_000L
        r.sessionStart(true, 0, 100, l, 3, Taps.REPORT)
        val mark = r.tail(3, now).first { it[0].toInt() == 27 && it[1].toInt() == 2 }[11].toInt() and 0xFF
        var row = 3
        r.front(null, row)                                          // withdrawn: Main in front, nothing offered
        r.tail(row, now)                                            // the CLEAR batch
        now += 40
        val f = HashMap<Int, Long>(mapOf(1 to 1L, 2 to 0xFFL, 3 to CfwModes.EV_UPROLL.toLong(), 4 to 0L, 5 to 0L, 6 to 0L,
            7 to 0L, 8 to 4L, 9 to 0L, 15 to 200L, 17 to mark.toLong()))
        for (i in 0..3) f[10 + i] = (intArrayOf(recs[4], 0, 0, 0)[i] and 0xFFFF).toLong(); f[14] = scroll.at.toLong()
        assertTrue(r.report(DamageMsg.Applied(f), row, now) is Verdict.Apply, "applied as today (focus)")
        row = 4
        assertEquals(Verdict.Nothing, r.stock(Stock.NEXT, row, now + 5), "its echo consumed")
        val d = r.tail(row, now + 10).first { it[0].toInt() == 26 && it[1].toInt() == 4 }
        assertEquals(4, u16(d, 6), "the DISCARD names the current row")
        assertTrue(r.stock(Stock.NEXT, row, now + 20) is Verdict.Apply, "a notch after the CLEAR landed: as today")
        row = 5
        now += 150
        r.front(l, row)                                             // the return, inside the burst
        val back = r.tail(row, now)
        val di = back.indexOfFirst { it[0].toInt() == 26 && it[1].toInt() == 4 }
        assertTrue(di > back.indexOfLast { it[0].toInt() == 27 && it[1].toInt() == 0 }, "the return batch names the row after its SETs: ${back.map { "${it[0]}/${it[1]}" }}")
        assertEquals(5, u16(back[di], 6), "...the current row")
        assertEquals(0, r.counts["snapback.rebind"] ?: 0, "a re-bind is not a snap-back")
    }

    /** Decisions AL.6 (R4): a list's return whose batch follows an adopted report (the withdrawal's CLEAR superseded, the lens
     *  still holding the entries) is a re-bind, not a snap-back. */
    @Test
    fun aReturnAfterAnAdoptedReportIsARebindNotASnapback_AL6() {
        val l = list()
        val notes = ArrayList<Pair<String, String>>()
        val r = LocalRules({ k, d -> notes += k to d })
        val now = 10_000L
        r.sessionStart(true, 0, 100, l, 3, Taps.REPORT)
        val mark = r.tail(3, now).first { it[0].toInt() == 27 && it[1].toInt() == 2 }[11].toInt() and 0xFF
        r.front(null, 3)                                            // withdrawn; its CLEAR not sent yet
        r.front(l, 3)                                               // back: the CLEAR superseded, the slot marked for its re-bind
        val f = HashMap<Int, Long>(mapOf(1 to 1L, 2 to 0xFFL, 3 to CfwModes.EV_UPROLL.toLong(), 4 to 0L, 5 to 0L, 6 to 0L,
            7 to 0L, 8 to 4L, 9 to 0L, 15 to 200L, 17 to mark.toLong()))
        for (i in 0..3) f[10 + i] = (intArrayOf(recs[4], 0, 0, 0)[i] and 0xFFFF).toLong(); f[14] = scroll.at.toLong()
        assertEquals(Verdict.Adopt(4), r.report(DamageMsg.Applied(f), 3, now + 40), "adopted on the returned list")
        r.tail(4, now + 50)
        assertEquals(0, r.counts["snapback.rebind"] ?: 0, "not a snap-back: ${r.counts}; ${notes.filter { it.first == "snapback" }}")
        assertTrue(notes.none { it.first == "snapback" }, "no snap-back noted: ${notes.filter { it.first == "snapback" }}")
    }

    /** Decisions AW.2: Q follows the motion in force — max(450 ms, T × P + the 256-tick hold of the last step + one period)
     *  for the list in front: at 352 at 450 ms Auto (T 10 at P 46) 745 ms, with the fixed rule's T 5 520 ms; a short
     *  pan keeps the 450 ms floor. */
    @Test
    fun qFollowsTheMotionInForce_AW2() {
        val rig = wm.damage.core.shell.MainLocalCheck.MainRig(wm.damage.core.motion.BoxFace, 352, wm.damage.core.shell.MainLocalCheck.sampleRows())
        val region = CfwModes.mainRegion(CfwModes.CACHE2_MAX, true)
        val long = rig.staging(CfwModes.CACHE2_MAX, region, wm.damage.core.shell.MainStaging.ScrollMotion(450, 0)).boundList(1)
        val fixed = rig.staging(CfwModes.CACHE2_MAX, region, motion = null).boundList(1)
        assertEquals(745L, LocalRules.qFor(long), "T 10 at P 46")
        assertEquals(520L, LocalRules.qFor(fixed), "T 5 at P 46")
        assertEquals(LocalRules.Q_MS, LocalRules.qFor(list()), "a short pan keeps the floor")
        val r = LocalRules()
        r.sessionStart(true, 0, 100, long, 0, Taps.REPORT)
        assertEquals(745L, r.q, "Q in force from the list at the start")
        r.front(fixed, 0)
        assertEquals(520L, r.q, "and from the list in front")
    }

    /** Decisions AF.5: a refused 3 or 14 body names no list; with none in front its stock message is applied as today. */
    @Test
    fun aRefusedReportNamingNoListLeavesItsStockMessage_AF5() {
        val t = R(row = 3)
        t.r.front(null, 3)                                          // a cover
        t.batch()
        val f = HashMap<Int, Long>(mapOf(1 to 1L, 2 to 0xFFL, 3 to CfwModes.EV_UPROLL.toLong(), 5 to 2L, 6 to 14L, 15 to 200L))
        assertEquals(Verdict.Nothing, t.r.report(DamageMsg.Applied(f), 3, t.now))
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), t.r.stock(Stock.NEXT, 3, t.now + 5), "the scroll message goes as today")
    }

    /** Decisions AF.9: a COMMITted instance closed by the next fire (outcome 1 for its tag) is not a snap-back. */
    @Test
    fun aClosedCommittedInstanceIsNoSnapback_AF9() {
        val t = R(row = 3)
        t.scroll(1, 4)
        assertTrue(t.answer(t.batch()).single()[1].toInt() == 3, "a COMMIT")
        t.r.body114(0, 1, 1, null)                                  // closed by the next fire of a fast burst
        assertEquals(0, t.r.counts.filterKeys { it.startsWith("snapback") }.values.sum(), "no snap-back: ${t.r.counts}")
        assertEquals(1, t.r.counts["closedCommitted"])
    }

    @Test
    fun belowStage4NothingIsPlannedOrSent() {
        val r = LocalRules()
        r.sessionStart(false, 0, 0, list(), 3, Taps.REPORT)
        assertEquals(Verdict.Apply(EvenHubMsg.EV_SCROLL_BOTTOM), r.stock(Stock.NEXT, 3, 0))
        assertTrue(r.tail(3, 0).isEmpty() && r.plan(3).isEmpty() && !r.owed)
    }
}
