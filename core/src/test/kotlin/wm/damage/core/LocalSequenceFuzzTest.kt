package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import wm.damage.core.local.LocalRules
import wm.damage.core.local.Taps
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm

/**
 * Build C2's sequence fuzz (`FIRMWARE.md` §6.10; spec §5, decisions B.6): random interleavings of notches, taps, lost,
 * duplicated and late reports, LEFT handling a gesture alone, the rubber band's dropped and delayed stock messages,
 * re-stagings, covers and windows, link drops, `Taps` switches, lost and refused mode-27 messages and the word held at a
 * fire on one lens — through [LocalRules] and the batch composer against the simulator's two lenses ([LocalHarness]) —
 * and after every sequence the eight invariants:
 *  (1) every act is on the row RIGHT showed (its cursor at the tap's fire, under the staging its mark names); a click
 *      acted on as today is the stated residual — counted, not failed;
 *  (2) at rest each lens's panel and shadow equal the phone's truth;
 *  (3) both lenses' cursors on the phone's row after a SETTLE (the list in front);
 *  (4) at rest: no owes, u 0, no burst open, no slot owing an answer, S_heard not ahead of RIGHT's S;
 *  (5) no message of the phone's refused (the refusals the harness induced kept apart; a sequence that lost or had
 *      refused a mode-27 message is counted, not failed — the phone's later messages may then name what the lens lacks);
 *  (6) with nothing lost from then on and the list uncovered, of two taps a round trip apart the second is acted on;
 *  (7) under the same conditions, of two notches a round trip apart away from the edge, the second is adopted and
 *      committed, and its local is never stopped before its COMMIT;
 *  (8) each gesture moves the phone's row at most once.
 * Every seeded case (spec §5's S1–S17, and S18 from the stage beside this one) is a scripted sequence here; each one is
 * watched to fail on the rule as it stood through `research/mutate_core.py` (`private/build072/work-DP2/seeded.json`).
 */
class LocalSequenceFuzzTest {

    private var debug = false

    class Verdict(val failed: List<String>, val counted: Map<String, Int>, val stats: String = "")

    // ----------------------------------------------------------------------------------------- the invariants
    /** (1)–(5) and (8) at rest; [lossy]: a mode-27 message was lost or refused this sequence. */
    fun atRest(h: LocalHarness, failed: MutableList<String>, counted: MutableMap<String, Int>, seeded: Boolean = false) {
        val before = failed.size
        h.rest()
        // (1)
        for (a in h.acts) {
            if (a.viaRule) { if (a.rightId != a.id) failed += "(1) gesture ${a.gesture}: acted on identity ${a.id}, RIGHT showed ${a.rightId}" }
            else if (a.rightId != null && a.rightId != a.id) counted["(1) residual ${a.taps.word}"] = (counted["(1) residual ${a.taps.word}"] ?: 0) + 1
        }
        // (2) — strict for every shape: a lens numbered behind RIGHT (counted apart before decisions U.2) fails too
        val sR = h.sim.bindForTest(Arm.RIGHT).seq
        for (m in h.truthMismatches()) failed += "(2) $m"
        if (h.maxRefusalStreak >= 3) failed += "(5) three refused flushes in a row (the shell sleeps on that): ${h.maxRefusalStreak}"
        // (3)
        if (!h.covered && !h.window) {
            val (l, r) = h.cursors()
            if (l != h.row || r != h.row) failed += "(3) cursors L $l R $r, phone's row ${h.row}"
        }
        // (4)
        val rr = h.rules
        if (rr.owesScroll + rr.owesClick + rr.owesAny != 0 || rr.u != 0) failed += "(4) owes ${rr.owesScroll}/${rr.owesClick}/${rr.owesAny} u ${rr.u}"
        if (rr.burstOpen) failed += "(4) a burst still open"
        if (rr.slotOwed(0)) failed += "(4) slot 0 still owes an answer"
        if (LocalRules.after(rr.sHeard, sR)) failed += "(4) S_heard ${rr.sHeard} ahead of RIGHT's S $sR"
        // (5)
        if (h.refusals.isNotEmpty()) {
            if (h.lossyMessages == 0) failed += "(5) refused: ${h.refusals.distinct().joinToString("; ")}"
            else counted["(5) after a lost or refused mode 27"] = (counted["(5) after a lost or refused mode 27"] ?: 0) + 1
        }
        // (8)
        for ((g, n) in h.moves) if (n > 1) failed += "(8) gesture $g moved the row $n times"
        // (2)'s cause, read directly (decisions AA.1): a COMMIT never rides a batch whose drawing meets its areas
        if (h.commitOverDraw.isNotEmpty()) failed += "(2) a COMMIT rode a batch with a drawing sub inside its areas at ${h.commitOverDraw}"
        // the fix round's row for the lead (`C2-DP2-built.md`, the fix round, row A): after a COMMIT that did not land on
        // RIGHT alone, RIGHT's shadow lacks END and nothing reports it — its (1) and RIGHT-side (2) are counted apart in
        // random sequences; every other failure stays a failure, and a seeded case counts nothing apart
        if (!seeded && h.commitMissedRight) {
            val mine = failed.drop(before)
            val rowA = mine.filter { it.startsWith("(2) RIGHT") || it.startsWith("(1)") }
            if (rowA.isNotEmpty()) {
                counted["row A: a batch that did not land on RIGHT alone"] = (counted["row A: a batch that did not land on RIGHT alone"] ?: 0) + 1
                repeat(mine.size) { failed.removeAt(failed.size - 1) }
                failed += mine - rowA.toSet()
            }
        }
    }

    /** (6): two taps a round trip apart, nothing lost; the second acted on. */
    fun tapLiveness(h: LocalHarness, failed: MutableList<String>) {
        quiet(h)
        h.gesture(0, answer = true, band = "none")
        h.runUntil(h.now + 300)
        val g2 = h.gesture(0, answer = true, band = "none")
        h.rest()
        if (h.acts.none { it.gesture == g2 }) failed += "(6) the second of two taps was not acted on (taps ${h.rules.taps.word}; " +
            "verdicts ${h.verdicts.filter { it.first == g2 }.map { it.second }}; notes ${h.notes.takeLast(4)})"
    }

    /** (7): two notches a round trip apart, away from the edge; the second adopted and committed, never stopped first. */
    fun scrollLiveness(h: LocalHarness, failed: MutableList<String>) {
        quiet(h)
        val dir = if (h.row + 2 < h.ids.size) 1 else 2
        h.gesture(dir, answer = true, band = "none")
        h.runUntil(h.now + 400)
        val stopped = h.stoppedBeforeCommit.size
        val g2 = h.gesture(dir, answer = true, band = "none")
        h.rest()
        val s = h.fires[g2]?.sR
        val v = h.verdicts.filter { it.first == g2 }.map { it.second }
        if (v.none { it is LocalRules.Verdict.Adopt }) failed += "(7) the second notch was not adopted: $v; notes ${h.notes.takeLast(4)}"
        else if (s == null || s !in h.commitsSent) failed += "(7) the second notch (S $s) was adopted but not committed"
        if (h.stoppedBeforeCommit.size > stopped) failed += "(7) a local stopped before its COMMIT: ${h.stoppedBeforeCommit.drop(stopped)}"
    }

    private fun quiet(h: LocalHarness) {
        h.lose115 = 0.0; h.dup115 = 0.0; h.bandDropScroll = 0.0
        h.dropAnswer = 0.0; h.actOpensWindow = 0.0; h.lose27 = null; h.refuse27On = null; h.refuseTailOn = null; h.loseAnswerOn = null
        h.uncover(); h.back()
        if (h.badge) h.toggleBadge()                 // a draw over the band is not the staged picture: every notch DISCARDs
        h.rest()
    }

    // ----------------------------------------------------------------------------------------- random sequences
    fun randomSequence(seed: Long, events: Int = 40): Verdict {
        val r = java.util.Random(seed)
        val taps = Taps.entries[r.nextInt(3)]
        val h = LocalHarness(seed, taps)
        if (debug) h.trace = { println(it) }
        h.lose115 = r.nextDouble() * 0.15; h.dup115 = r.nextDouble() * 0.05
        h.bandDropScroll = r.nextDouble()              // inside a band: dropped, else queued to a band's end (§6.1)
        h.dropAnswer = r.nextDouble() * 0.1; h.actOpensWindow = r.nextDouble() * 0.5
        val n = 1 + r.nextInt(events)
        repeat(n) {
            val x = r.nextInt(100)
            h.trace?.invoke("[${h.now}] drive $x" + when { x < 50 -> ""; x < 55 -> " cover/uncover"; x < 58 -> " back"; x < 62 -> " restage"
                x < 66 -> " taps"; x < 68 -> " session start"; x < 72 -> " lose a mode 27"; x < 73 -> " refuse a mode 27"; x < 74 -> " an answer refused or lost"; x < 76 -> " word held"
                x < 79 -> " badge"; x < 82 -> " tick"; x < 90 -> " telemetry"; else -> " wait" })
            when {
                x < 36 -> h.gesture(1 + r.nextInt(2))
                x < 50 -> h.gesture(0)
                x < 55 -> if (h.covered) h.uncover() else h.cover()
                x < 58 -> h.back()
                x < 62 -> h.restage()
                x < 66 -> h.switchTaps(Taps.entries[r.nextInt(3)])
                x < 68 -> h.start(fresh = r.nextBoolean())
                x < 72 -> h.lose27 = listOf("set", "clear", "list")[r.nextInt(3)] to listOf(setOf(Arm.LEFT), setOf(Arm.RIGHT), setOf(Arm.LEFT, Arm.RIGHT))[r.nextInt(3)]
                x < 73 -> h.refuse27On = if (r.nextBoolean()) Arm.LEFT else Arm.RIGHT
                x < 74 -> if (r.nextBoolean()) h.refuseTailOn = if (r.nextBoolean()) Arm.LEFT else Arm.RIGHT
                    else h.loseAnswerOn = listOf(setOf(Arm.LEFT), setOf(Arm.RIGHT), setOf(Arm.LEFT, Arm.RIGHT))[r.nextInt(3)]
                x < 76 -> h.gesture(r.nextInt(3), holdWordOn = if (r.nextBoolean()) Arm.LEFT else Arm.RIGHT)
                x < 79 -> h.toggleBadge()
                x < 82 -> h.tick()
                x < 90 -> h.readTelemetry()
                else -> h.runUntil(h.now + 300 + r.nextInt(500))
            }
            h.runUntil(h.now + 30 + r.nextInt(370))
        }
        val failed = ArrayList<String>()
        val counted = HashMap<String, Int>()
        h.trace?.invoke("[${h.now}] --- quiet, then the invariants at rest")
        quiet(h)
        atRest(h, failed, counted)
        tapLiveness(h, failed)
        scrollLiveness(h, failed)
        // row A's first trigger (declined, declared): does a batch lost on RIGHT alone heal once COMMITs land again?
        if (h.commitMissedRight) {
            val right = h.truthMismatches().filter { it.startsWith("RIGHT") }
            val k = if (right.isEmpty()) "row A, after the liveness COMMITs: RIGHT agrees" else "row A, after the liveness COMMITs: RIGHT still differs"
            counted[k] = (counted[k] ?: 0) + 1
        }
        for ((g, k) in h.moves) if (k > 1) failed += "(8) gesture $g moved the row $k times (liveness)"
        if (h.commitOverDraw.isNotEmpty()) failed += "(2) a COMMIT rode a batch with a drawing sub inside its areas at ${h.commitOverDraw} (liveness)"
        // decisions AA.1: the answers that are a COMMIT with the areas' drawing held back — each a DISCARD under the rule before
        if (h.heldCommits > 0) {
            counted["AA.1: sequences with a COMMIT that was a DISCARD before"] = (counted["AA.1: sequences with a COMMIT that was a DISCARD before"] ?: 0) + 1
            counted["AA.1: such COMMITs"] = (counted["AA.1: such COMMITs"] ?: 0) + h.heldCommits
        }
        if (h.telemetryAsked > 0) counted["AA.2: sequences with a read asked at a SETTLE"] = (counted["AA.2: sequences with a read asked at a SETTLE"] ?: 0) + 1
        val stats = "outcome %08x keyframes ${h.keyframes} ".format(h.outcome.value) + "taps ${h.rules.taps.word} events $n sent ${h.sent} commits ${h.commitsSent.size} held ${h.heldCommits} acts ${h.acts.size} " +
            "verdicts ${h.verdicts.groupingBy { it.second::class.simpleName }.eachCount()} counts ${h.rules.counts}"
        return Verdict(failed.distinct(), counted, stats)
    }

    // ----------------------------------------------------------------------------------------- the seeded cases
    /** A scripted sequence with fixed delays: [script] drives it, then every invariant at rest and, unless [live] is
     *  false, the two liveness checks; [extra] adds the case's own observable. Fails with every invariant that broke. */
    private fun seeded(taps: Taps, live: Boolean = true, rows: Int = 3, script: (LocalHarness) -> Unit, extra: (LocalHarness) -> List<String> = { emptyList() }) {
        val h = LocalHarness(42, taps)
        h.fixed = true
        if (System.getenv("LOCAL_FUZZ_TRACE") != null) h.trace = { println(it) }
        repeat(rows) { h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 600) }
        h.rest()
        val before = h.moves.keys.toSet()
        script(h)
        val failed = ArrayList<String>()
        val counted = HashMap<String, Int>()
        atRest(h, failed, counted, seeded = true)
        failed += extra(h)
        if (live) { tapLiveness(h, failed); scrollLiveness(h, failed) }
        for ((g, n) in h.moves) if (n > 1 && g !in before) failed += "(8) gesture $g moved the row $n times"
        assertTrue(failed.isEmpty(), failed.distinct().joinToString("\n"))
    }

    private fun notch(h: LocalHarness, kind: Int = 1, answer: Boolean = true, band: String = "none", hold: Arm? = null) =
        h.gesture(kind, answer = answer, band = band, holdWordOn = hold)

    /** S1 — `C2-focus.md` 1(a) 1: a lost 115 overtaken. S1's report lost and its scroll message held a band, S2's report
     *  before it: S2 adopted two rows on; the two scroll messages pay its owe and the gap's. Pairing a stock message with
     *  the latest unpaired report applies S2's own message as a third row. */
    @Test fun seededS1_aLostReportOvertaken() = seeded(Taps.REPORT, script = { h ->
        h.loseNext115 = 1
        notch(h, band = "delay:81")                     // S1's scroll message arrives between S2's report and S2's message
        h.runUntil(h.now + 60)
        notch(h, band = "delay:30")
        h.rest()
    })

    /** S2 — 1(a) 5: a DISCARD's batch refused on LEFT (its word held at the first tail sub). After the frame, the frame is
     *  in LEFT's shadow and the next present shows it; before the frame, LEFT never gets the frame. */
    @Test fun seededS2_aDiscardRefusedOnLeftLosesOnlyThePresent() = seeded(Taps.REPORT, script = { h ->
        h.loseNext115 = 1                               // the scroll message is unreported: the row moves, a DISCARD answers
        // on RIGHT, whose refusal nobody hears (the image lane's ack is LEFT's, and a DISCARD's refusal body names no tag):
        // on LEFT the refused flush's ack rolls the frame back and paints it again, which hides the order
        h.refuseTailOn = Arm.RIGHT
        notch(h)
        h.rest()
    })

    /** S3 — 1(b) 1: a fire crossing the burst's end. A notch fired just before the lone end-of-burst message lands: SETTLE
     *  leaves it (held for less than `age`) and its COMMIT adopts it; the blind DISCARD(S_heard + 1) stops it first. */
    @Test fun seededS3_aFireCrossingTheBurstsEnd() = seeded(Taps.REPORT, script = { h ->
        val t0 = h.now
        notch(h)
        h.runUntil(t0 + 470)
        notch(h)
        h.rest()
    }, extra = { h -> if (h.stoppedBeforeCommit.isNotEmpty()) listOf("(7) a local stopped before its COMMIT: ${h.stoppedBeforeCommit}") else emptyList() })

    /** S4 — 1(b) 2: S taken at the match on both lenses. LEFT's word held at one fire (14 there, a draw on RIGHT); then a
     *  notch under a cover before its CLEAR lands, answered by DISCARD(A + 1). Numbered alike, LEFT's local is reached. */
    @Test fun seededS4_bothLensesNumberEveryMatchAlike() = seeded(Taps.REPORT, script = { h ->
        notch(h, hold = Arm.LEFT)
        h.rest()
        h.cover()
        notch(h)
        h.rest()
        h.uncover()
        h.rest()
    })

    /** S5 — 1(b) 3: a gesture LEFT alone handled at idle, ended by the next idle batch's SETTLE. */
    @Test fun seededS5_aLeftOnlyGestureAtIdle() = seeded(Taps.REPORT, script = { h ->
        notch(h, answer = false)
        h.rest()
    })

    /** S6 — 1(d) 5: the first mode 27 after a release point empties the lists too (a fresh acquire, the cursor elsewhere). */
    @Test fun seededS6_theEmptyTableEmptiesTheLists() = seeded(Taps.REPORT, rows = 5, script = { h ->
        h.start(fresh = true)
        h.runUntil(h.now + 200)
        notch(h)
        h.rest()
    })

    /** S7 — 1(e): LIST is total. LEFT one ahead (a LEFT-only notch), then a re-stage dropping the last row: LEFT's cursor
     *  out of range is lost and the SETTLE sets it; refused, LEFT keeps the old staging. */
    @Test fun seededS7_aListIsAlwaysApplied() = seeded(Taps.REPORT, rows = 6, script = { h ->
        notch(h, answer = false)
        h.runUntil(h.now + 5)
        h.restage("bottom-")
        h.rest()
        h.restage("top+")
        h.rest()
    })

    /** S8 — `C2-amend-focus.md` Defect A: a report adopted, a cover, a second report not adopted, both answered by one
     *  batch. DISCARD(A + 1) answers both and the burst closes; 2b's DISCARD at the newest not-adopted S left the first
     *  unanswered and no SETTLE ever followed (a LEFT-only gesture after it then stays). */
    @Test fun seededS8_everyReportBeforeABatchIsAnsweredByIt() = seeded(Taps.REPORT, script = { h ->
        h.pumpDelay = 60
        h.afterReport = { h.cover(); notch(h) }
        notch(h)
        h.runUntil(h.now + 300)
        h.pumpDelay = 3
        h.rest()
        h.uncover()
        h.rest()
        notch(h, answer = false)
        h.rest()
    })

    /** S9 — Defect B: two quick notches up from the top row: the second an edge that leaves the first's local open on the
     *  lens. DISCARD(A + 1) stops it; DISCARD at the newest not-adopted S leaves the top row shown over the wrapped row,
     *  and a tap there acts on a row he does not see. */
    @Test fun seededS9_anEdgeDoesNotLeaveTheOlderLocalOpen() = seeded(Taps.REPORT, rows = 1, script = { h ->
        h.pumpDelay = 80                                // both reports handled before one batch (a busy link)
        h.afterReport = { h.pumpDelay = 80 }
        notch(h, kind = 2)
        h.runUntil(h.now + 60)
        notch(h, kind = 2)
        h.runUntil(h.now + 100)
        h.pumpDelay = 3
        h.runUntil(h.now + 60)
        h.gesture(0, answer = true, band = "none")
        h.rest()
    })

    /** S10 — Defect C (ii): a cover opening between a report and its answer. The cover's draw meets the slot's areas, so
     *  the answer is a DISCARD; a COMMIT would write END over the cover. */
    @Test fun seededS10_noCommitInABatchThatDrawsOverTheList() = seeded(Taps.REPORT, script = { h ->
        h.afterReport = { h.cover() }                   // after the adoption, before its answer's batch
        notch(h)
        h.rest()
    })

    /** S12 — `C2-F-focus.md` B1: `glasses` → `report` whose SET does not land: the lens keeps the press form; the first
     *  tap's form disagrees and the tap entry's SET is sent again, so the second tap is acted on. */
    @Test fun seededS12_aSwitchWhoseSetDidNotLand() = seeded(Taps.GLASSES, script = { h ->
        h.lose27 = "set" to setOf(Arm.LEFT, Arm.RIGHT)
        h.switchTaps(Taps.REPORT)
        h.rest()
    })

    /** S13 — `C2-D1-built.md` 0.3 (1): a re-stage whose LIST does not land: the next report's mark is stale and the LIST
     *  goes again. */
    @Test fun seededS13_aStaleMarkResendsTheList() = seeded(Taps.REPORT, script = { h ->
        h.lose27 = "list" to setOf(Arm.LEFT, Arm.RIGHT)
        h.restage("top+")
        h.rest()
    })

    /** S14 — 0.3 (2a): a return whose scroll-entry SETs do not land: the first unreported scroll message re-sends them. */
    @Test fun seededS14_lostScrollSetsComeBack() = seeded(Taps.REPORT, script = { h ->
        h.cover(); h.rest()
        h.lose27 = "set" to setOf(Arm.LEFT, Arm.RIGHT); h.lose27All = true
        h.uncover()
        h.rest()
    })

    /** S15 — 0.3 (2b): a program moved to fresh space whose SETs do not land: the lens fires the old program, the report's
     *  fields differ from the prediction and the entry's SET goes again. */
    @Test fun seededS15_aProgramChangeWhoseSetDidNotLand() = seeded(Taps.REPORT, script = { h ->
        h.lose27 = "set" to setOf(Arm.LEFT, Arm.RIGHT); h.lose27All = true
        h.reprogram()
        h.rest()
    })

    /** S16 — D1 §1 item 3: `glasses` → `phone` whose CLEAR does not land: the first tap 115 (an entry the phone does not
     *  hold) re-sends the CLEAR; the next tap is a plain click, acted on. */
    @Test fun seededS16_aTapReportUnderPhoneClearsTheEntry() = seeded(Taps.GLASSES, script = { h ->
        h.lose27 = "clear" to setOf(Arm.LEFT, Arm.RIGHT)
        h.switchTaps(Taps.PHONE)
        h.rest()
    })

    /** S17 — D1 filling (2): a cover whose CLEARs do not land: the first notch under it draws the list's motion over the
     *  cover, and its report (an entry the phone does not hold) re-sends the CLEAR — the later notches draw nothing. */
    @Test fun seededS17_aLostCoverClearIsRepaired() = seeded(Taps.REPORT, live = false, script = { h ->
        h.lose27 = "clear" to setOf(Arm.LEFT, Arm.RIGHT); h.lose27All = true
        h.cover()
        h.runUntil(h.now + 200)
        repeat(4) { notch(h); h.runUntil(h.now + 300) }
        h.uncover()
        h.rest()
    }, extra = { h -> if (h.localsUnderCover > 1) listOf("S17: ${h.localsUnderCover} locals drawn under the cover (the first one only is allowed)") else emptyList() })

    /** S18 (stage DMS's finding, 2026-11-18; decisions AA.1) — the answer judges a drawing sub by the rect it really writes:
     *  (a) a draw outside the slot's areas (the clock) rides the COMMIT's own batch and the local stands to it; (b) a draw
     *  that really crosses the areas (the badge) does not: the answer is still a COMMIT, the badge goes one batch later,
     *  and belief equals both lenses at rest. */
    @Test fun seededS18a_aDrawOutsideTheAreasKeepsTheCommit() = seeded(Taps.REPORT, live = false, script = { h ->
        h.beforeReport = { h.tick() }
        val g = notch(h)
        h.rest()
        val s = h.fires[g]?.sR
        assertTrue(s != null && s in h.commitsSent, "S18a: the answer to S $s is a COMMIT (commits ${h.commitsSent})")
        assertTrue(h.stoppedBeforeCommit.isEmpty(), "S18a: ${h.stoppedBeforeCommit}")
        val with = h.commitBatches.first { it.first == s }.second
        assertTrue(with.any { it.overlaps(LocalHarness.CLOCK) }, "S18a: the clock's draw rides the COMMIT's batch (beside it: $with)")
    })

    @Test fun seededS18b_aDrawCrossingTheAreasWaitsForTheNextBatch() = seeded(Taps.REPORT, live = false, script = { h ->
        h.beforeReport = { h.toggleBadge() }
        val g = notch(h)
        h.rest()
        val s = h.fires[g]?.sR
        assertTrue(s != null && s in h.commitsSent, "S18b: the answer to S $s is a COMMIT (commits ${h.commitsSent})")
        val with = h.commitBatches.first { it.first == s }.second
        assertTrue(with.none { it.overlaps(LocalHarness.BAND) }, "S18b: nothing inside the areas rides the COMMIT's batch (beside it: $with)")
        assertTrue(h.heldBatches > 0, "S18b: the badge was held back to the next batch")
        assertTrue(h.truthMismatches().isEmpty(), "S18b: belief equals both lenses with the badge on: ${h.truthMismatches()}")
        h.toggleBadge(); h.rest()
    })

    /** S19 (this stage's finding, kept after the rulings): a cover's batch refused on LEFT (its word held at the first
     *  CLEAR) leaves LEFT holding the list's entries under the cover. The refused flush's ack (the image lane's is LEFT's)
     *  makes the phone send the whole table again — under a cover, CLEAR 0xFF — so LEFT stops matching; without it LEFT
     *  draws the list's motion over the cover at every notch (no riding message covers an entry). */
    @Test fun seededS19_aRefusedTableMessageSendsTheTableAgain() = seeded(Taps.REPORT, live = false, script = { h ->
        h.refuse27On = Arm.LEFT
        h.cover()
        h.runUntil(h.now + 200)
        repeat(4) { notch(h); h.runUntil(h.now + 300) }
        h.uncover()
        h.rest()
    }, extra = { h -> if (h.localsUnderCover > 1) listOf("S19: LEFT drew ${h.localsUnderCover} locals under the cover (one at most)") else emptyList() })

    /** S20 (this stage's fuzz, seed 100100028): a tap that opens a window and a return before the window's batch goes —
     *  the CLEARs owed for the list are superseded by the return (the lens still holds the entries). Sent anyway, they
     *  leave the list in front with no entry on the glasses and nothing that would ever re-send one. */
    @Test fun seededS20_aReturnSupersedesAnUnsentClear() = seeded(Taps.REPORT, script = { h ->
        h.actOpensWindow = 1.0
        h.pumpDelay = 40
        h.gesture(0, answer = true, band = "none")
        h.runUntil(h.now + 35)
        h.actOpensWindow = 0.0
        h.back()
        h.pumpDelay = 3
        h.rest()
    })

    /** S21 (decisions U.1): a LIST lost on one lens alone (a re-stage), then idle batches and notches. The batch that
     *  carried the lost LIST may lose its own present on that lens (its SETTLE names a cursor past the old count); from
     *  the next answer on, every answer carries its list's LIST before it, so the lens holds the current count when the
     *  answer is checked, refuses nothing more, keeps presenting, and both lenses agree at the next SETTLE. RIGHT is the
     *  lens nobody hears from (the image lane writes LEFT, so the flush's ack is LEFT's [I]; RIGHT's refused DISCARD or
     *  SETTLE sends a body naming no tag, which is no divergence): without the riding LIST it refuses every idle SETTLE
     *  until a report's mark shows it. LEFT's refusal fails the flush's ack, so the table goes again anyway (S19). */
    private fun listLostOn(arm: Arm) = seeded(Taps.REPORT, rows = 7, script = { h ->
        h.lose27 = "list" to setOf(arm)
        h.restage("top+")
        h.runUntil(h.now + 150)                         // the re-stage's batch lands (its own present may go on that lens)
        refusedBefore = h.refusals.count { it.startsWith(arm.name) }
        h.rest()
        notch(h, kind = 2); h.runUntil(h.now + 400); notch(h)
        h.rest()
    }, extra = { h -> h.refusals.filter { it.startsWith(arm.name) }.drop(refusedBefore).let {
        if (it.isEmpty()) emptyList() else listOf("S21: $arm refused ${it.size} more after the re-stage's batch: ${it.distinct()}") } })
    private var refusedBefore = 0

    @Test fun seededS21_aListLostOnRightHealsAtTheNextAnswer() = listLostOn(Arm.RIGHT)
    @Test fun seededS21L_aListLostOnLeftHealsAtTheNextAnswer() = listLostOn(Arm.LEFT)

    /** S22a (decisions U.2): a SET landing between LEFT's and RIGHT's handling of one notch (a return from a cover with a
     *  notch inside the inter-lens skew) leaves LEFT numbered one behind; a later notch whose report is lost is answered
     *  by DISCARD, which must reach LEFT's local too. With the S the text first named (A + 1), LEFT keeps it held. */
    @Test fun seededS22a_aSetBetweenTheLensesLeavesLeftBehind() = seeded(Taps.REPORT, script = { h ->
        h.cover(); h.rest()
        val t0 = h.now
        h.uncover()                                     // its batch: pumped at +3, lands at +28
        h.runUntil(t0 + 24)
        notch(h)                                        // LEFT at +24 (no entry yet), RIGHT at +32 (the entry landed)
        h.runUntil(h.now + 500)
        h.loseNext115 = 1
        notch(h)
        h.rest()
    })

    /** S22b (decisions U.2): a scroll entry's SET lost on LEFT alone (a return): LEFT never fires that direction, falls
     *  behind, and its local from the other direction must still be reached by the DISCARD that answers a lost report. */
    @Test fun seededS22b_aSetLostOnLeftLeavesLeftBehind() = seeded(Taps.REPORT, script = { h ->
        h.cover(); h.rest()
        h.lose27 = "set" to setOf(Arm.LEFT)               // the first SET: entry 0, id 4 (the next row)
        h.uncover(); h.rest()
        notch(h, kind = 1); h.runUntil(h.now + 500)     // RIGHT alone numbers it
        h.loseNext115 = 1
        notch(h, kind = 2)                              // both fire; LEFT's tag is one behind RIGHT's
        h.rest()
    })

    /** S23 (row A's second trigger, ruled): a batch whose riding LIST is refused 14 on RIGHT with a COMMIT behind it. The
     *  COMMIT never runs there and no notify says so; RIGHT's refusal record names mode 27 at the next telemetry read, and
     *  the phone sends the table again AND asks for one keyframe, which brings RIGHT back to the belief. Without the
     *  keyframe RIGHT keeps the pre-gesture row while the phone believes the new one. */
    @Test fun seededS23_aMode27RefusedOnRightKeyframes() = seeded(Taps.REPORT, live = false, script = { h ->
        h.refuse27On = Arm.RIGHT
        val g = notch(h)
        h.runUntil(h.now + 120)                          // the COMMIT's batch lands: refused on RIGHT at its LIST
        val s = h.fires[g]?.sR
        assertTrue(s != null && s in h.commitsSent, "S23: the answer was a COMMIT (commits ${h.commitsSent})")
        assertTrue(h.induced.any { it.startsWith("RIGHT mode 27") }, "S23: RIGHT refused the batch at its LIST (${h.induced})")
        h.readTelemetry()
        h.rest()
    }, extra = { h -> if (h.keyframes == 0) listOf("S23: no keyframe was asked") else emptyList() })

    /** S24 (decisions AA.2): the riding LIST refused 14 on RIGHT with a COMMIT behind it, and no telemetry read until the
     *  minute's. The COMMIT's outcome-3 body never comes, so the burst's lone SETTLE asks for ONE read; its record names
     *  mode 27 and P1 sends the table again with one keyframe: RIGHT equals the belief within a round after the SETTLE.
     *  Without the read RIGHT keeps the pre-gesture row until the minute's read. */
    @Test fun seededS24_aRefusedTailOnRightIsSeenAtTheSettle() = seeded(Taps.REPORT, live = false, script = { h ->
        h.refuse27On = Arm.RIGHT
        val t0 = h.now
        val g = notch(h)
        h.runUntil(t0 + 120)
        val s = h.fires[g]?.sR
        assertTrue(s != null && s in h.commitsSent, "S24: the answer was a COMMIT (commits ${h.commitsSent})")
        assertTrue(h.induced.any { it.startsWith("RIGHT mode 27") }, "S24: RIGHT refused the batch at its LIST (${h.induced})")
        h.runUntil(t0 + LocalRules.Q_MS + 400)            // the lone SETTLE, the read's round trip, the keyframe landing
        val right = h.truthMismatches().filter { it.startsWith("RIGHT") }
        assertTrue(h.telemetryAsked == 1, "S24: one read asked at the SETTLE (${h.telemetryAsked})")
        assertTrue(right.isEmpty(), "S24: RIGHT equals the belief within a round after the SETTLE: $right")
        h.rest()
    })

    /** The coordinator's point 2 (DMS's finding): the transport's CLOSE rule reads only its mirror's instances, and a local
     *  instance never enters the mirror — the mirror applies the phone's bytes and no input (a COMMIT there opens nothing),
     *  and P9 sends no PLAY into a bound slot. So a cached draw outside a local's areas can never close it. */
    @Test fun theMirrorHoldsNoLocalInstanceSoTheCloseRuleNeverMeetsOne() {
        val h = LocalHarness(5, Taps.REPORT)
        h.fixed = true
        val mirror = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x5FF; it.clockIsOsTick = true }
        h.sim.imageObserver = { a, img -> if (a == Arm.RIGHT) { mirror.conformanceMessage(Arm.LEFT, img, h.now); mirror.conformanceMessage(Arm.RIGHT, img, h.now) } }
        for (a in Arm.entries) { mirror.conformanceLease(a, true, h.now); mirror.conformanceControl(a, wm.damage.core.wire.DamageMsg.OP_FLAGS_SET, LocalHarness.ARMED, h.now) }
        repeat(4) { h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 60) }
        h.rest()
        assertTrue(h.commitsSent.isNotEmpty())
        for (a in Arm.entries) assertTrue(mirror.motionWindows(a).isEmpty(), "$a: the mirror holds no instance after the COMMITs")
    }

    @Test
    fun smoke() {
        val v = randomSequence(7, events = 10)
        println("smoke: ${v.stats}")
        assertTrue(v.failed.isEmpty(), v.failed.joinToString("\n"))
        assertTrue("commits 0" !in v.stats && "acts 0" !in v.stats, "the harness drove nothing: ${v.stats}")
    }

    /** Decisions U.2's question, whether any outcome still depends on P7's S_low: `LOCAL_FUZZ_SIG=<file>` writes one line
     *  per sequence — its seed and the digest of every picture both lenses showed in the band and the clock and what their
     *  slots held, after every message and engine step — for comparing two builds' runs. Passes when the variable is unset. */
    @Test
    fun outcomeDigests() {
        val out = System.getenv("LOCAL_FUZZ_SIG") ?: return
        val n = System.getenv("LOCAL_FUZZ_N")?.toInt() ?: 60
        val lines = ArrayList<String>()
        for (base in listOf(1L, 1001L)) for (i in 0 until n) {
            val seed = base * 100_000 + i
            val v = randomSequence(seed)
            lines += "$seed ${v.stats.substringBefore(" taps")} ${if (v.failed.isEmpty()) "ok" else "FAILED"}"
        }
        java.nio.file.Files.write(java.nio.file.Paths.get(out), lines)
    }

    /** One seed with its whole record (`LOCAL_FUZZ_SEED=<seed>`): for reading a failure. Passes when the variable is not set. */
    @Test
    fun oneSeedVerbose() {
        val seed = System.getenv("LOCAL_FUZZ_SEED")?.toLong() ?: return
        debug = true
        val v = randomSequence(seed)
        println("seed $seed: ${v.stats}\n" + v.failed.joinToString("\n"))
    }

    /** Seeds 1 and 1001 (spec §5): `LOCAL_FUZZ_N=1000` per seed base for the instrument's run (2,000 sequences); 60 in the gate. */
    @Test
    fun randomSequencesHoldTheEightInvariants() {
        val n = System.getenv("LOCAL_FUZZ_N")?.toInt() ?: 60
        val fails = ArrayList<String>()
        val counted = HashMap<String, Int>()
        for (base in listOf(1L, 1001L)) for (i in 0 until n) {
            val seed = base * 100_000 + i
            val v = try { randomSequence(seed) } catch (e: Throwable) { Verdict(listOf("threw: $e"), emptyMap()) }
            for (f in v.failed) fails += "seed $seed: $f"
            for ((k, c) in v.counted) counted[k] = (counted[k] ?: 0) + c
        }
        println("sequence fuzz: ${2 * n} sequences, ${fails.size} failures; counted $counted")
        if (fails.isNotEmpty()) fail(fails.take(30).joinToString("\n") + "\n(${fails.size} in all)")
    }
}
