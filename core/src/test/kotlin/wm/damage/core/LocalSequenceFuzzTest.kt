package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import wm.damage.core.local.LocalRules
import wm.damage.core.local.Taps
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.DamageMsg

/**
 * Build C2's sequence fuzz (`FIRMWARE.md` §6.10; spec §5, decisions B.6): random interleavings of notches, taps, lost,
 * duplicated and late reports, LEFT handling a gesture alone, the rubber band's dropped and delayed stock messages,
 * re-stagings, covers and windows, link drops, `Taps` switches, lost and refused mode-27 messages and the word held at a
 * fire on one lens — through [LocalRules] and the batch composer against the simulator's two lenses ([LocalHarness]) —
 * and after every sequence the eight invariants:
 *  (1) every act is on the row RIGHT showed (its cursor at the tap's fire, under the staging its mark names); a click
 *      acted on as today is the stated residual — counted, not failed; so is a tap RIGHT took inside a wrap's held wait,
 *      whose cursor a DISCARD set ahead of the frame held behind the wrap (S33's shape, declared by the B083 fix stage:
 *      his taps come ≥ 696 ms after a scroll message, `FIRMWARE.md` §6.1) — per tap, never for a sequence;
 *  (2) at rest each lens's panel and shadow equal the phone's truth;
 *  (3) both lenses' cursors on the phone's row after a SETTLE (the list in front);
 *  (4) at rest: no owes but those a carry holds (C1: the scroll owes a lone SETTLE left open, for one band each), no
 *      burst open, no slot owing an answer, S_heard not ahead of RIGHT's S (u is not read: since decisions BB it counts the
 *      unreported stock messages since S_heard last advanced, kept across a SETTLE);
 *  (5) no message of the phone's refused (the refusals the harness induced kept apart; a sequence that lost or had
 *      refused a mode-27 message is counted, not failed — the phone's later messages may then name what the lens lacks);
 *  (6) with nothing lost from then on and the list uncovered, of two taps a round trip apart the second is acted on;
 *  (7) under the same conditions, of two notches a round trip apart away from the edge, the second is adopted and
 *      committed, and its local is never stopped before its COMMIT;
 *  (8) each gesture moves the phone's row at most once;
 *  (9) the row at rest equals the ring's net count (B083 fix stage, item 1; [LocalHarness.ringRow] states the rule): a
 *      notch lost or applied twice fails here even when the phone and both lenses agree on the row. In random sequences
 *      the count is first adjusted by the exact per-gesture declarations ([declarations], B083 v2 item 4); a seeded case
 *      declares nothing;
 * (10) a notch made with a window or a cover in front after that front's CLEAR landed on RIGHT (no 115), its stock message
 *      reaching the phone while that front still stands, is applied there — counted per front and compared at rest, never
 *      declared (B083 v3, decisions BO.3: the window's own notch the joined carry swallowed, the focused read's 1).
 * Every seeded case (spec §5's S1–S17, and S18 from the stage beside this one) is a scripted sequence here; each one is
 * watched to fail on the rule as it stood through `research/mutate_core.py` (`private/build072/work-DP2/seeded.json`).
 */
class LocalSequenceFuzzTest {

    private var debug = false

    class Verdict(val failed: List<String>, val counted: Map<String, Int>, val stats: String = "")

    /** B083 fix stage (item 2): S33's shape, a declared residual since the cursor wait was removed. */
    val s33Key = "(1) S33: a tap inside a wrap's held wait acted on the row its cursor named"

    // ------------------------------------------------- B083 v2, item 4: (9)'s exact per-gesture declarations
    /** One part of a gesture's difference from the ring's count: its class ([key]), the rows the phone's row moved for it
     *  beyond the count ([rows]: −step for a step never applied, +step for one applied twice, what an adoption moved beyond
     *  the steps it carries), and where that happened (time and order). (9) judges the row against the count adjusted by
     *  every part of the sequence; a key starting [UNTAGGED] is a failure however the count comes out. */
    class Decl(val key: String, val g: Int, val rows: Int, val t: Long, val n: Long)
    val UNTAGGED = "(9) untagged"
    val j3Key = "(9) J3: a fire from a stale cursor after an edge, adopted off the count"
    val staleKey = "(9) a fire from a stale cursor, adopted off the count, after"
    val lostEdgeKey = "(9) a lost report of a fire that did not draw, never applied"
    val lostDrewKey = "(9) a lost report of a fire that drew, never applied: its message"
    val swallowedKey = "(9) an untaken notch's message paid by an owe"
    private fun signed(d: Int, size: Int): Int { val m = d.mod(size); return if (m > size / 2) m - size else m }

    /** Every part of every gesture's difference from the ring's count in [h]'s sequence, each with its class: the phone's
     *  moves ([LocalHarness.phoneMoves]) against the count ([LocalHarness.ringCountedAt]). An adoption (or a tap's
     *  correction) carries the steps of the notches in its gap that RIGHT drew and the phone had not applied yet. */
    fun declarations(h: LocalHarness): List<Decl> {
        val out = ArrayList<Decl>()
        val applied = HashMap<Int, MutableList<LocalHarness.Move>>()
        for (m in h.phoneMoves) {
            val step = h.stepOf(m.g)
            val d = signed(m.after - m.before, m.size)
            val v = m.v
            if (v is LocalRules.Verdict.Adopt || v is LocalRules.Verdict.Act) {
                var expected = if (v is LocalRules.Verdict.Adopt) (step ?: 0) else 0
                var carried = 0
                for (gk in m.carries) if (applied[gk].isNullOrEmpty()) { val sk = h.stepOf(gk)!!; expected += sk; carried += sk; applied.getOrPut(gk) { ArrayList() } += m }
                if (v is LocalRules.Verdict.Adopt && step != null) applied.getOrPut(m.g) { ArrayList() } += m
                val err = signed(d - expected, m.size)
                if (err != 0) out += Decl(staleKeyOf(h, m, carried), m.g, err, m.t, m.n)
            } else if (v is LocalRules.Verdict.Apply && step != null && m.after != m.before) applied.getOrPut(m.g) { ArrayList() } += m
        }
        for ((g, f) in h.fires) {
            val step = h.stepOf(g) ?: continue
            val counted = h.ringCountedAt(g)
            val a = applied[g].orEmpty()
            if (counted != null && a.isEmpty()) out += Decl(missingKeyOf(h, g, f), g, -step, counted.first, counted.second)
            for (m in a.drop(if (counted != null) 1 else 0)) out += Decl(extraKeyOf(h, g, m), g, step, m.t, m.n)
        }
        return out
    }

    /** An adoption off the count: RIGHT's cursor at its fire stood apart from the phone's row at its report by more than
     *  the lost notches it carries. Named by its root ([rootOf]): an edge applied as today (J3), an unreported stock
     *  message, a refused fire applied as today, a tap's DISCARD. */
    private fun staleKeyOf(h: LocalHarness, m: LocalHarness.Move, carried: Int): String {
        val f = h.fires[m.g] ?: return "$UNTAGGED: an adoption of a gesture the harness did not fire"
        val r = rootOf(h, f, m.n, 0) ?: return "$UNTAGGED: an adoption off the count with nothing moving the phone's row alone since RIGHT's last reset"
        val via = if (r.endsWith(VIA)) VIA else ""
        return when (r.removeSuffix(VIA)) {
            "edge" -> j3Key + via
            "unreported" -> "$staleKey an unreported stock message$via"
            "as-today" -> "$staleKey a report P3 applied as today$via"
            "refused" -> "$staleKey a refused fire applied as today$via"
            else -> "$staleKey a tap's DISCARD that set RIGHT back"
        }
    }
    private val VIA = " (through a later reset that set RIGHT back)"

    /** What put RIGHT's cursor at [f]'s fire apart from the phone's row before the move numbered [untilN]: the phone's
     *  moves alone (verdicts applied as today) after the phone decided the last message that reset RIGHT's cursor before
     *  the fire — an edge first, then an unreported stock message, then a report P3 applied as today (followed back to its
     *  own root: it was refused for the same reason), then a refused fire. With none, that reset itself landed after a fire
     *  the phone adopted after deciding it and set RIGHT back across it (the later DISCARD of J3's description): its root is
     *  what the reset answered, the phone's moves alone or a tap between the reset before it and its decision. */
    private fun rootOf(h: LocalHarness, f: LocalHarness.Fire, untilN: Long, depth: Int): String? {
        val k = f.resetsAtFireR
        val decided = if (k > 0) h.rightResetAt[k - 1] else Long.MIN_VALUE
        fun alone(from: Long, until: (LocalHarness.Move) -> Boolean) =
            h.phoneMoves.filter { it.t > from && until(it) && it.v is LocalRules.Verdict.Apply && it.after != it.before }
        fun pick(apart: List<LocalHarness.Move>): String? {
            if (apart.any { it.by115 && it.outcome == DamageMsg.APPLIED_EDGE }) return "edge"
            if (apart.any { !it.by115 }) return "unreported"
            val asToday = apart.filter { it.by115 && it.outcome == DamageMsg.APPLIED_DREW }
            if (asToday.isNotEmpty()) {
                if (depth < 4) for (a in asToday) { val r = h.fires[a.g]?.let { rootOf(h, it, a.n, depth + 1) }; if (r != null && r != "as-today") return r }
                return "as-today"
            }
            return if (apart.isNotEmpty()) "refused" else null
        }
        pick(alone(decided) { it.n < untilN })?.let { return it }
        if (k > 0) {
            val landed = h.rightResetLanded[k - 1]
            val wiped = h.phoneMoves.any { it.t > decided && it.n < untilN && it.v is LocalRules.Verdict.Adopt && it.after != it.before &&
                (h.fires[it.g]?.fireTimeR ?: Long.MAX_VALUE) < landed }
            if (wiped) {
                val before = if (k > 1) h.rightResetAt[k - 2] else Long.MIN_VALUE
                pick(alone(before) { it.t <= decided })?.let { return it.removeSuffix(VIA) + VIA }
                if (h.phoneMoves.any { it.t in (before + 1)..decided && it.v is LocalRules.Verdict.Act }) return "tap"
            }
        }
        return null
    }

    /** A step the ring counts and the phone never applied. */
    private fun missingKeyOf(h: LocalHarness, g: Int, f: LocalHarness.Fire): String {
        val msg = h.phoneMoves.firstOrNull { !it.by115 && it.g == g }
        val how = when { msg == null -> "dropped"; msg.v != LocalRules.Verdict.Nothing -> "applied off the front"; msg.carried -> "paid by a carried owe"; else -> "paid by an owe" }
        return when {
            f.sR == null -> if (msg?.v == LocalRules.Verdict.Nothing) "$swallowedKey${if (msg.carried) " (a carried one)" else ""}" else "$UNTAGGED: an untaken notch never applied ($how)"
            g in h.lost115 -> if (h.rightOutcome[g] == DamageMsg.APPLIED_DREW) "$lostDrewKey $how" else lostEdgeKey
            else -> "$UNTAGGED: a heard report never applied (${h.verdicts.filter { it.first == g }.map { it.second }}, message $how)"
        }
    }

    /** A step the phone applied once more than the ring counts: never declared — a stock message applied after its own
     *  report is BC's shape, which C1 reverses (decisions BM.2): a late echo past its carry fails here, and (8) with it. */
    private fun extraKeyOf(h: LocalHarness, g: Int, m: LocalHarness.Move): String {
        val heard = h.reportHeardAt[g]
        return "$UNTAGGED: " + if (!m.by115 && heard != null && heard < m.t) "a stock message applied after its own report (BC's shape, reversed by C1)"
            else "a step applied twice (${if (m.by115) "by its report" else "by its message"})"
    }

    /** (8): a gesture moves the phone's row at most once (never declared since C1 reversed BC). */
    private fun moved(h: LocalHarness, failed: MutableList<String>, suffix: String) {
        for ((g, n) in h.moves) if (n > 1) failed += "(8) gesture $g moved the row $n times$suffix"
    }

    /** (9): the row at rest against the ring's net count — on the phone; (2) and (3) carry it to both lenses. With
     *  [declared] (random sequences) the count is first adjusted by the sequence's declarations, which are collected
     *  there; a seeded case declares nothing. */
    private fun ringCount(h: LocalHarness, failed: MutableList<String>, suffix: String, declared: MutableSet<Pair<String, Int>>? = null) {
        val want = h.ringRow()
        val decl = if (declared != null) declarations(h) else emptyList()
        val adjusted = if (decl.isEmpty()) want else h.ringRow(decl.map { Triple(it.t, it.n, it.rows) })
        val untagged = decl.filter { it.key.startsWith(UNTAGGED) }
        if (h.row == adjusted && untagged.isEmpty()) { declared?.addAll(decl.map { it.key to it.g }); return }
        failed += "(9) the row at rest is ${h.row}, the ring's count says $want$suffix" +
            if (decl.isEmpty()) "" else " ($adjusted with ${decl.joinToString(" + ") { "${it.key} g${it.g} ${it.rows}" }})"
    }

    // ----------------------------------------------------------------------------------------- the invariants
    /** The declared (class, gesture) pairs of the random sequence being judged. */
    private var declaredNow = HashSet<Pair<String, Int>>()

    /** (1)–(5) and (8)–(10) at rest. */
    fun atRest(h: LocalHarness, failed: MutableList<String>, counted: MutableMap<String, Int>, seeded: Boolean = false,
               /** S33's residual counted apart: every random sequence, and the one seeded case that pins it. */
               residualS33: Boolean = !seeded) {
        val before = failed.size
        h.rest()
        // (1)
        for (a in h.acts) {
            if (residualS33 && a.viaRule && a.rightId != a.id && a.gesture in h.tapNearWrap) counted[s33Key] = (counted[s33Key] ?: 0) + 1
            else if (a.viaRule) { if (a.rightId != a.id) failed += "(1) gesture ${a.gesture}: acted on identity ${a.id}, RIGHT showed ${a.rightId}" }
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
        if (rr.owesUncarried(h.now) != 0) failed += "(4) owes ${rr.owesScroll}/${rr.owesClick}/${rr.owesAny} no carry holds (u ${rr.u})"
        if (rr.burstOpen) failed += "(4) a burst still open"
        if (rr.slotOwed(0)) failed += "(4) slot 0 still owes an answer"
        if (LocalRules.after(rr.sHeard, sR)) failed += "(4) S_heard ${rr.sHeard} ahead of RIGHT's S $sR"
        // (5)
        if (h.refusals.isNotEmpty()) {
            if (h.lossyMessages == 0) failed += "(5) refused: ${h.refusals.distinct().joinToString("; ")}"
            else counted["(5) after a lost or refused mode 27"] = (counted["(5) after a lost or refused mode 27"] ?: 0) + 1
        }
        // (8)
        moved(h, failed, "")
        // (9)
        ringCount(h, failed, "", if (seeded) null else declaredNow)
        // (10): a front's own notch the phone did not apply is a notch lost there. Decisions BQ, BR: in random sequences the
        // one declared shape ([LocalHarness.frontDeclared]: its notch paid by an owe of a burst that joined no carry, open
        // when the front took the front or opened while it stood) lowers the front's expected count by that notch; every
        // other notch is judged; a seeded case declares nothing
        for ((kind, made) in h.frontMade) {
            val applied = h.frontApplied[kind] ?: 0
            val decl = h.frontDeclared.entries.filter { it.value.first == kind }
            val undeclared = h.frontMissed.filter { "($kind," in it } + if (seeded) decl.map { "g${it.key} ($kind, ${it.value.second})" } else emptyList()
            if (undeclared.isNotEmpty() || applied != made - (if (seeded) 0 else decl.size))
                failed += "(10) $kind: ${made - applied} of its own $made notch(es) not applied there: $undeclared" +
                    if (!seeded && decl.isNotEmpty()) " (declared: ${decl.map { "g${it.key}" }})" else ""
        }
        if (!seeded) for ((g, d) in h.frontDeclared) declaredNow += d.second to g
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

    /** (10)'s reach, counted (not failures): the fronts' own notches the sequence made and judged. */
    private fun frontChecked(h: LocalHarness, counted: MutableMap<String, Int>) {
        for ((kind, made) in h.frontMade) counted["front notches judged by (10): $kind"] = (counted["front notches judged by (10): $kind"] ?: 0) + made
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
        declaredNow = HashSet()
        val counted0 = ArrayList<String>()
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
        for (k in counted0) counted[k] = (counted[k] ?: 0) + 1
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
        moved(h, failed, " (liveness)")
        ringCount(h, failed, " (liveness)", declaredNow)
        for ((k, _) in declaredNow) counted[k] = (counted[k] ?: 0) + 1
        h.trace?.invoke("declared: " + declarations(h).joinToString("; ") { "${it.key} g${it.g} ${it.rows} at ${it.t}" })
        if (h.ringLostAtStart.isNotEmpty()) counted["(9) not counted: notches in flight at a session start"] = h.ringLostAtStart.size
        frontChecked(h, counted)
        if (h.commitOverDraw.isNotEmpty()) failed += "(2) a COMMIT rode a batch with a drawing sub inside its areas at ${h.commitOverDraw} (liveness)"
        // decisions AA.1: the answers that are a COMMIT with the areas' drawing held back — each a DISCARD under the rule before
        if (h.heldCommits > 0) {
            counted["AA.1: sequences with a COMMIT that was a DISCARD before"] = (counted["AA.1: sequences with a COMMIT that was a DISCARD before"] ?: 0) + 1
            counted["AA.1: such COMMITs"] = (counted["AA.1: such COMMITs"] ?: 0) + h.heldCommits
        }
        if (h.telemetryAsked > 0) counted["AA.2: sequences with a read asked at a SETTLE"] = (counted["AA.2: sequences with a read asked at a SETTLE"] ?: 0) + 1
        // decisions AW.3: windows that opened over a COMMITted motion still drawing, cut by a CLOSE ahead of their frame
        if (h.windowCuts > 0) counted["AW.3: windows that cut a COMMITted motion"] = (counted["AW.3: windows that cut a COMMITted motion"] ?: 0) + h.windowCuts
        // decisions AZ.4: the wraps animated from the phone, and those a later batch's drawing in the list closed first
        if (h.wrapPlays > 0) counted["AZ.4: wrap PLAYs"] = (counted["AZ.4: wrap PLAYs"] ?: 0) + h.wrapPlays
        if (h.wrapCuts > 0) counted["AZ.4: wraps closed by a draw in the list"] = (counted["AZ.4: wraps closed by a draw in the list"] ?: 0) + h.wrapCuts
        val stats = "outcome %08x keyframes ${h.keyframes} ".format(h.outcome.value) + "taps ${h.rules.taps.word} events $n sent ${h.sent} commits ${h.commitsSent.size} held ${h.heldCommits} acts ${h.acts.size} " +
            "verdicts ${h.verdicts.groupingBy { it.second::class.simpleName }.eachCount()} counts ${h.rules.counts}"
        return Verdict(failed.distinct(), counted, stats)
    }

    // --------------------------------------------------------------- the fifth base: his session's profile
    /**
     * B083 v2 (decisions BM.4): sequences drawn from his recorded fast-scroll session (11-19 22:30–23:23, APK 0.81;
     * `private/build083/B083-real.md` and its `reports-081.txt`, measured from his journal). Each parameter and its source:
     *  - his clumps and the gaps between: of his 356 report-to-report uptime gaps (ticks taken as ms, 2.4 % apart) the 55
     *    of 12 or less make 30 clumps (sizes 2 ×19, 3 ×4, 4 ×4, 5, 6, 8), drawn as one with probability 30/301, its own
     *    gaps 0–12 ms; the gaps between clumps and single notches as their histogram: 4 in 13–30, 23 in 30–60, 8 in
     *    60–100, 15 in 100–150, 3 in 150–200, 8 in 200–300, 77 in 300–450, 95 in 450–700, 36 in 700–1000, 19 in
     *    1000–1500 (a gap over 1.5 s ends a burst: 13 of them);
     *  - a burst's length: his 14 bursts (2, 1, 45, 101, 73, 8, 11, 11, 7, 11, 71, 7, 7, 2), drawn with replacement and cut
     *    at [HIS_BURST_CAP] (run time; a cut burst still crosses the list's end four times), 1–3 bursts a sequence;
     *  - direction: his 278 NEXT and 77 PREV with 67 changes of direction in 355 notches, as a two-state chain (NEXT → PREV
     *    0.1205 a notch, PREV → NEXT 0.435: PREV's share 0.217, 0.189 changes a notch);
     *  - Main's 8 rows (his edges come from the list's end: 74 of 355);
     *  - the rubber band as one queue: a message meeting a band is queued to its end, one per band of 225 ms (his run of
     *    eight all came, 194–240 ms apart; the floor 224 ms); one sent with no band running is dropped with [HIS_DROP],
     *    fitted to his owes never paid, 162 at the SETTLEs in 355 notches (45.6 per 100): the model gives about 45 per 100,
     *    and about 2.3 late echoes per 100 against his 13 in 355 (3.7). Where his drops really fall is not known (BC's 9
     *    isolated gestures all had their message, measured): the fit puts them where his two counts need them;
     *  - no lost, duplicated or late 115 (gap 0 in the session and every older pull), no LEFT-only fire, no lost or refused
     *    mode 27, no word held, no cover: none is in his data;
     *  - taps at his rate (2 in 357 reports), 700 ms after the notch before (his taps come ≥ 696 ms after a scroll message,
     *    0 of 467 within 690 ms) and 225 ms or more before the next (his S495 → S496), an act opening a window half the
     *    time (his one did), Back after it and the next notch 225 ms or more after Back (not measured: a notch racing the
     *    return's table, which the profile drew at first, is a start race);
     *  - a real start (a wake's re-bind; 2 in his 13 bursts) between bursts with probability 0.15, the next burst after it
     *    has rested: no start race beyond a real start; `Taps · report` (his setting).
     * Modeled throughout: the rates it gives belong to this model of his session.
     */
    private val CARRY_MAX = "profile: carryMaxMs"

    private object His {
        /** The gaps between his clumps and single notches (the 301 of 356 over 12 ticks, bursts' 13 over 1.5 s left out). */
        val GAP_BINS = longArrayOf(13, 30, 60, 100, 150, 200, 300, 450, 700, 1000, 1500)
        val GAP_COUNTS = intArrayOf(4, 23, 8, 15, 3, 8, 77, 95, 36, 19)
        /** His 30 clumps (consecutive fires 12 ticks apart or less): their sizes; a clump's own gaps 0–12 ms. */
        val CLUMPS = intArrayOf(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 6, 8)
        const val P_CLUMP = 30.0 / 301
        val BURSTS = intArrayOf(2, 1, 45, 101, 73, 8, 11, 11, 7, 11, 71, 7, 7, 2)
        const val HIS_BURST_CAP = 32
        const val HIS_DROP = 0.57
        const val BAND = 225L
        const val N_TO_P = 0.1205; const val P_TO_N = 0.435
        const val P_TAP = 2.0 / 357
        const val P_START = 0.15
        fun gap(r: java.util.Random): Long {
            var k = r.nextInt(GAP_COUNTS.sum())
            var i = 0
            while (k >= GAP_COUNTS[i]) { k -= GAP_COUNTS[i]; i++ }
            return GAP_BINS[i] + r.nextInt((GAP_BINS[i + 1] - GAP_BINS[i]).toInt())
        }
    }

    fun hisSessionSequence(seed: Long): Verdict {
        val r = java.util.Random(seed)
        val h = LocalHarness(seed, Taps.REPORT)
        declaredNow = HashSet()
        if (debug) h.trace = { println(it) }
        h.bandMs = His.BAND; h.bandDropScroll = 0.0; h.dropScrollIdle = His.HIS_DROP; h.actOpensWindow = 0.5
        h.rest()                                        // the start's table lands first: no start race beyond a real start
        var dir = 1
        var notches = 0
        repeat(1 + r.nextInt(3)) { b ->
            if (b > 0 && r.nextDouble() < His.P_START) { h.trace?.invoke("[${h.now}] his: a real start"); h.start(fresh = false); h.rest() }
            var left = His.BURSTS[r.nextInt(His.BURSTS.size)].coerceAtMost(His.HIS_BURST_CAP)
            while (left > 0) {
                // his taps came ≥ 696 ms after a scroll message (0 of 467 within 690 ms, `FIRMWARE.md` §6.1, measured)
                if (r.nextDouble() < His.P_TAP) {
                    h.runUntil(h.now + 700); h.gesture(0); h.runUntil(h.now + maxOf(225L, His.gap(r)))
                    if (h.window) { h.runUntil(h.now + 800); h.back(); h.runUntil(h.now + maxOf(225L, His.gap(r))) }
                    continue
                }
                val k = if (r.nextDouble() < His.P_CLUMP) His.CLUMPS[r.nextInt(His.CLUMPS.size)].coerceAtMost(left) else 1
                repeat(k) { i ->
                    dir = if (dir == 1) (if (r.nextDouble() < His.N_TO_P) 2 else 1) else (if (r.nextDouble() < His.P_TO_N) 1 else 2)
                    h.gesture(dir); notches++; left--
                    if (i < k - 1) h.runUntil(h.now + r.nextInt(13))
                }
                h.runUntil(h.now + His.gap(r))
            }
            h.rest()
        }
        val failed = ArrayList<String>()
        val counted = HashMap<String, Int>()
        h.trace?.invoke("[${h.now}] --- the invariants at rest")
        // the profile's own measures, beside his (the 0.81 counter `owesAtSettle` held what C1 now splits: owes cleared at a
        // SETTLE, carried owes that ran out, carried owes a late echo paid)
        val c = h.rules.counts
        counted["profile: notches"] = notches
        counted["profile: owes never paid or paid late"] = (c["owesAtSettle"] ?: 0) + (c["owesExpired"] ?: 0) + (c["lateEcho"] ?: 0) +
            (if (h.rules.owesCarriedUntilMs != Long.MIN_VALUE) h.rules.owesScroll + h.rules.owesAny else 0)   // the last carry, still running
        for (k in listOf("lateEcho", "lateEchoUnpaid", "lateEchoOtherFront", "owesExpired", "unreported", "snapback.lost-report", "snapback.edge", "repair")) counted["profile: $k"] = c[k] ?: 0
        // decisions BO.2: the longest carry that had ended by the last burst's rest (aggregated by its maximum, not summed)
        counted[CARRY_MAX] = c["carryMaxMs"] ?: 0
        // decisions BO.4: the late echoes as the harness knows them, beside the rules' `lateEcho` (which cannot tell a
        // message's report)
        counted["profile: late echoes (harness)"] = lateEchoes(h).size
        atRest(h, failed, counted)
        tapLiveness(h, failed)
        scrollLiveness(h, failed)
        moved(h, failed, " (liveness)")
        ringCount(h, failed, " (liveness)", declaredNow)
        for ((k, _) in declaredNow) counted[k] = (counted[k] ?: 0) + 1
        h.trace?.invoke("declared: " + declarations(h).joinToString("; ") { "${it.key} g${it.g} ${it.rows} at ${it.t}" })
        frontChecked(h, counted)
        // J3's adjustments in steps of its own gesture: −1 a notch adopted where the phone stood, −2 adopted a row back, …
        for (d in declarations(h)) if (d.key.startsWith(j3Key)) h.stepOf(d.g)?.let { st -> val key = "profile: J3 adjusted by ${d.rows * st} step(s)"; counted[key] = (counted[key] ?: 0) + 1 }
        if (h.ringLostAtStart.isNotEmpty()) counted["(9) not counted: notches in flight at a session start"] = h.ringLostAtStart.size
        return Verdict(failed.distinct(), counted, "notches $notches counts ${h.rules.counts}")
    }

    /** The fifth base, his session's profile (`LOCAL_FUZZ_HIS_N` sequences, 60 in the gate; base 5): every invariant, the
     *  declared classes with their counts and seeds, and the profile's own measures beside his. Fails on a failure no
     *  declaration explains. */
    @Test
    fun hisSessionProfile() {
        val n = System.getenv("LOCAL_FUZZ_HIS_N")?.toInt() ?: 60
        val fails = ArrayList<String>()
        val counted = java.util.TreeMap<String, Int>()
        val declaredSeeds = java.util.TreeMap<String, MutableList<Long>>()
        // decisions BQ: the gate-sized run also runs the profile's declared classes
        val seeds = (0 until n).map { 5L * 100_000 + it } + if (System.getenv("LOCAL_FUZZ_HIS_N") == null) HIS_GATE_SEEDS else emptyList()
        for (seed in seeds) {
            val v = try { hisSessionSequence(seed) } catch (e: Throwable) { Verdict(listOf("threw: $e"), emptyMap()) }
            for (f in v.failed) fails += "seed $seed: $f"
            for ((k, c) in v.counted) counted[k] = if (k == CARRY_MAX) maxOf(counted[k] ?: 0, c) else (counted[k] ?: 0) + c
            for (k in v.counted.keys) if (k.startsWith("(9)") || k.startsWith("(10)") || k.startsWith("(1) S33") || k.startsWith("row A")) declaredSeeds.getOrPut(k) { ArrayList() } += seed
        }
        for (f in fails) println("HISFAIL $f")
        val notches = (counted["profile: notches"] ?: 0).coerceAtLeast(1)
        fun per100(k: String) = "%.1f".format(100.0 * (counted[k] ?: 0) / notches)
        println("his profile: ${seeds.size} sequences, ${counted["profile: notches"]} notches; the longest carry ${counted[CARRY_MAX]} ms; per 100 notches: owes never paid or paid late ${per100("profile: owes never paid or paid late")} " +
            "(his 45.6), late echoes paid by a carry ${per100("profile: lateEcho")} (the harness's own count ${per100("profile: late echoes (harness)")}; his 13 late echoes: 3.7), lateEchoUnpaid ${per100("profile: lateEchoUnpaid")}, " +
            "unreported ${per100("profile: unreported")}, lost-report snap-backs ${per100("profile: snapback.lost-report")}, edge answers ${per100("profile: snapback.edge")}, repairs ${per100("profile: repair")}")
        println("his declared: " + declaredSeeds.entries.joinToString("; ") { (k, s) -> "$k ${counted[k]} gesture(s) in ${s.size} sequence(s): ${s.take(12).joinToString()}" })
        println("his profile counted: $counted; ${fails.size} failures")
        if (fails.isNotEmpty()) fail(fails.take(30).joinToString("\n") + "\n(${fails.size} in all)")
    }

    /** Seeds of the fifth base with their whole record (`LOCAL_FUZZ_HIS_SEED=<seed>[,…]`). Passes when unset. */
    @Test
    fun hisSeedVerbose() {
        val seeds = System.getenv("LOCAL_FUZZ_HIS_SEED")?.split(',')?.map { it.trim().toLong() } ?: return
        debug = true
        for (seed in seeds) {
            println("===== seed $seed")
            val v = hisSessionSequence(seed)
            println("seed $seed: ${v.stats}\n" + v.failed.joinToString("\n"))
        }
        debug = false
    }

    // ----------------------------------------------------------------------------------------- the seeded cases
    /** A scripted sequence with fixed delays: [script] drives it, then every invariant at rest and, unless [live] is
     *  false, the two liveness checks; [extra] adds the case's own observable. Fails with every invariant that broke. */
    private fun seeded(taps: Taps, live: Boolean = true, rows: Int = 3, residualS33: Boolean = false, script: (LocalHarness) -> Unit,
                       extra: (LocalHarness) -> List<String> = { emptyList() }) {
        val h = LocalHarness(42, taps)
        h.fixed = true
        if (System.getenv("LOCAL_FUZZ_TRACE") != null) h.trace = { println(it) }
        repeat(rows) { h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 600) }
        h.rest()
        val before = h.moves.keys.toSet()
        script(h)
        val failed = ArrayList<String>()
        val counted = HashMap<String, Int>()
        atRest(h, failed, counted, seeded = true, residualS33 = residualS33)
        failed += extra(h)
        if (live) { tapLiveness(h, failed); scrollLiveness(h, failed) }
        for ((g, n) in h.moves) if (n > 1 && g !in before) failed += "(8) gesture $g moved the row $n times"
        ringCount(h, failed, " (after the liveness checks)")
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
    /** S25 (decisions AZ.4; G1 item 5's shape on his glasses): a notch to the last row, a notch past the end 6 ms later
     *  (the wrap), and a notch 10 ticks after the wrap's PLAY lands: the wrap is a phone PLAY in slot 1 beside the DISCARD
     *  naming row 0, the next notch's fire closes it and is adopted from row 0, and every invariant holds at rest. */
    @Test fun seededS25_aWrapIsAnimatedAndTheNextNotchClosesIt() = seeded(Taps.REPORT, rows = 0, script = { h ->
        repeat(h.ids.size - 2) { h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 600) }
        h.rest()
        h.gesture(1, answer = true, band = "none")
        h.runUntil(h.now + 6)
        h.gesture(1, answer = true, band = "none")
        var played = false
        repeat(400) { if (!played) { h.runUntil(h.now + 1); played = h.sim.motionForTest(Arm.RIGHT).slots[LocalHarness.WRAP_SLOT] != null } }
        h.runUntil(h.now + 10)                          // inside its T × P = 48 ticks
        h.gesture(1, answer = true, band = "none")
        h.rest()
    }, extra = { h -> listOfNotNull(
        if (h.wrapPlays != 1) "the wrap was played ${h.wrapPlays} times" else null,
        if (h.row != 1) "the row is ${h.row}, not 1 (the wrap to 0, then one notch)" else null,
        if (h.bodies114.none { it.first == LocalHarness.WRAP_SLOT && it.third == 1 }) "the next notch's fire did not close the wrap's instance on RIGHT: ${h.bodies114.filter { it.first == LocalHarness.WRAP_SLOT }}" else null) })

    /** S26 (decisions AZ.4): two notches past the last row handled before one answer (a busy link) — the phone's row moved
     *  twice (to 0, then 1), so no PLAY across the wrap (its END would be row 0): the DISCARD names row 1 as today. */
    @Test fun seededS26_twoEdgesInOneAnswerAreNotAnimated() = seeded(Taps.REPORT, rows = 0, script = { h ->
        repeat(h.ids.size - 1) { h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 600) }
        h.rest()
        h.pumpDelay = 80                                // both reports handled before one batch
        h.afterReport = { h.pumpDelay = 80 }
        h.gesture(1, answer = true, band = "none")
        h.runUntil(h.now + 20)
        h.gesture(1, answer = true, band = "none")
        h.runUntil(h.now + 100)
        h.pumpDelay = 3
        h.rest()
    }, extra = { h -> listOfNotNull(
        if (h.wrapPlays != 0) "a wrap was played ${h.wrapPlays} time(s) with the row moved twice" else null,
        if (h.row != 1) "the row is ${h.row}, not 1" else null) })

    /** S27 (decisions BB; the fuzz's seed 100100988 on G2): two notches whose reports are lost — their scroll messages come
     *  and are applied as today — then the burst's SETTLE; a notch later finds the gap of 2. Those two messages already
     *  came, so no echo owe is left for them: a click 50 ms after that notch (`Taps · phone`: no tap entry, the click acts
     *  as today) is acted on. Charged in full, the gap's two owes swallowed it. */
    @Test fun seededS27_aGapFoundAfterASettleOwesNothingForMessagesThatCame() {
        var tap = -1
        seeded(Taps.PHONE, script = { h ->
            h.loseNext115 = 2
            h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 300)
            h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 300)
            h.rest()
            h.gesture(1, answer = true, band = "none")
            h.runUntil(h.now + 50)
            tap = h.gesture(0, answer = true, band = "none")
            h.rest()
        }, extra = { h -> listOfNotNull(
            if (h.acts.none { it.gesture == tap }) "the click 50 ms after the gap's report was not acted on (owes ${h.rules.owesAny}; " +
                "verdicts ${h.verdicts.filter { it.first == tap }.map { it.second }})" else null,
            if (h.rules.counts["gap"] != 2) "the gap counted ${h.rules.counts["gap"]}, not 2 (the shape did not happen)" else null) })
    }

    /** S30 (B083, 2c; his look at 0.81: edge reports in a burst at Main's wrap): two notches past the end 20 ms apart, the
     *  second an edge too (the wrap's DISCARD has not landed): the wrap's PLAY draws whole on both lenses — the second
     *  edge's frame waits for its end, as the shell's compositor holds it (AF.7), instead of closing it after a step — and
     *  the list then shows the burst's row. */
    @Test fun seededS30_aSecondEdgeDoesNotCloseTheWrap() = seeded(Taps.REPORT, rows = 7, script = { h ->
        h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 20)
        h.gesture(1, answer = true, band = "none")
        h.rest()
    }, extra = { h -> listOfNotNull(
        if (h.wrapPlays != 1) "${h.wrapPlays} wrap PLAYs (the shape did not happen)" else null,
        if (h.wrapCuts != 0 || h.bodies114.any { it.first == LocalHarness.WRAP_SLOT && it.third == 1 }) "the wrap closed while drawing: cuts ${h.wrapCuts}, bodies ${h.bodies114.filter { it.first == LocalHarness.WRAP_SLOT }}" else null,
        if (h.row != 1) "the row is ${h.row}, not 1" else null) })

    /** S33 (B083, 2c; the fuzz's seed 101032 once the harness held the wrap's cells as the shell does) — since the B083 fix
     *  stage the DECLARED RESIDUAL: a notch past the end (the wrap) and, 15 ms later, a notch back that the lens draws
     *  before the wrap's PLAY lands — the phone moves back (`lost-report`), its frame waits behind the wrap, and its
     *  DISCARD goes at once (the cursor wait that held it lost the notches of an edge burst, the focused read's 1). A tap
     *  100 ms after the first notch acts on row 7, the row the lens's cursor names, while RIGHT's panel still shows the
     *  wrap's row 0. Counted under (1)'s S33 key, per tap; the row at rest is the ring's ((9)). */
    @Test fun seededS33_aTapInsideTheWrapsWaitIsTheDeclaredResidual() = seeded(Taps.REPORT, rows = 7, residualS33 = true, script = { h ->
        h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 15)
        h.gesture(2, answer = true, band = "none"); h.runUntil(h.now + 85)
        h.gesture(0, answer = true, band = "none")
        h.rest()
    }, extra = { h -> listOfNotNull(
        if (h.wrapPlays != 1) "${h.wrapPlays} wrap PLAYs (the shape did not happen)" else null,
        if (h.rules.counts["snapback.lost-report"] == null) "no lost-report answer (the shape did not happen): ${h.rules.counts}" else null,
        if (h.acts.none { it.viaRule && it.gesture in h.tapNearWrap && it.id == h.ids[7] && it.rightId == h.ids[0] })
            "the residual's shape did not happen (acts ${h.acts.map { "g${it.gesture} id ${it.id} RIGHT ${it.rightId}" }})" else null) })

    /** S34, S35 (B083 fix stage, item 2; the focused read's 1): k notches past the last row, the later ones edges too (the
     *  wrap's DISCARD has not landed), then one notch more inside the wrap's held wait. With the later edges' DISCARD held
     *  for the wait, that notch fired from the wrap's row 0 and was adopted at the burst's row: k = 2 lost one notch, k = 3
     *  two (the row moved back on a forward notch). The ring says row k; (9) reads it. */
    private fun edgeBurstThenANotch(k: Int, gap: Long) = seeded(Taps.REPORT, rows = 7, script = { h ->
        val t0 = h.now
        repeat(k) { h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + gap) }
        h.runUntil(t0 + 70)
        h.gesture(1, answer = true, band = "none")
        h.rest()
    }, extra = { h -> listOfNotNull(
        if (h.wrapPlays != 1) "${h.wrapPlays} wrap PLAYs (the shape did not happen)" else null,
        if (h.row != k) "the row is ${h.row}, not $k (the ring's count)" else null) })
    @Test fun seededS34_twoEdgesThenANotchInTheWrapsWait() = edgeBurstThenANotch(2, 20)
    @Test fun seededS35_threeEdgesThenANotchInTheWrapsWait() = edgeBurstThenANotch(3, 10)

    /** A notch whose scroll message the band drops leaves its owe open at the burst's lone SETTLE, which carries it (C1);
     *  a tap [tapAfterMs] after the notch, inside the carry: its report opens a burst, which joins the carry, and its act
     *  opens a window (the list leaves the front inside that burst). Returns (the carry's time left at the tap, the tap). */
    private fun tapInsideACarry(h: LocalHarness, tapAfterMs: Long): Pair<Long, Int> {
        val t0 = h.now
        h.gesture(1, answer = true, band = "drop")
        h.runUntil(t0 + tapAfterMs)
        val left = if (h.rules.burstOpen || h.rules.owesCarriedUntilMs == Long.MIN_VALUE) -1L else h.rules.owesCarriedUntilMs - h.now
        h.actOpensWindow = 1.0
        val tap = h.gesture(0, answer = true, band = "none")
        return left to tap
    }

    /** S36 (B083 v3, decisions BO.1; the focused read's f3): [tapInsideACarry] — then, 300 ms after the tap, a notch in the
     *  window after its CLEAR landed on RIGHT (no 115). The carry ends with its list leaving the front, inside the tap's
     *  burst too, so the window's notch is applied there, as on 0.82; on the tree before BO.1 the joined owe swallowed it
     *  ((10)). Watched to fail on `1fbd543a`. */
    @Test fun seededS36_aWindowsNotchInsideTheTapsBurstIsApplied() {
        var left = -1L; var tap = -1; var gw = -1; var windowAtFire = false
        seeded(Taps.REPORT, script = { h ->
            tapInsideACarry(h, h.rules.q + 150).let { left = it.first; tap = it.second }
            h.runUntil(h.now + 300)
            h.actOpensWindow = 0.0
            gw = h.gesture(1, answer = true, band = "none")
            windowAtFire = h.window
            h.runUntil(h.now + 1500)
            h.back()
            h.rest()
        }, extra = { h -> listOfNotNull(
            if (left < 50) "the carry had ${left} ms left at the tap (the shape did not happen)" else null,
            if (!windowAtFire || h.fires[gw]?.sR != null) "the notch was not a window's own (window $windowAtFire, RIGHT's S ${h.fires[gw]?.sR}): the shape did not happen" else null,
            if (h.verdicts.filter { it.first == gw }.map { it.second } != listOf(LocalRules.Verdict.Apply(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM)))
                "the window's own notch: ${h.verdicts.filter { it.first == gw }.map { it.second }} (tap g$tap: ${h.verdicts.filter { it.first == tap }.map { it.second }})" else null) })
    }

    /** S38 (B083 v3, decisions BO.1; the focused read's f6): [tapInsideACarry] — then Back 200 ms after the tap, inside its
     *  burst, and a notch 5 ms after Back, racing the return's SETs: neither lens holds an entry yet, so no 115; its stock
     *  message reaches the phone with Main in front and is applied as an unreported notch (the ring counts it). On the tree
     *  before BO.1 the joined owe swallowed it: a notch lost on Main ((9)), the three agreeing on the row. Watched to fail
     *  on `1fbd543a`. */
    @Test fun seededS38_aNotchRacingTheReturnInsideTheTapsBurstIsApplied() {
        var left = -1L; var gm = -1; var burstAtNotch = false
        seeded(Taps.REPORT, script = { h ->
            left = tapInsideACarry(h, h.rules.q + 150).first
            h.runUntil(h.now + 200)
            h.actOpensWindow = 0.0
            h.back()
            h.runUntil(h.now + 5)
            burstAtNotch = h.rules.burstOpen
            gm = h.gesture(1, answer = true, band = "none")
            h.rest()
        }, extra = { h -> listOfNotNull(
            if (left < 50 || !burstAtNotch) "the carry had ${left} ms left at the tap, the tap's burst open at the notch: $burstAtNotch (the shape did not happen)" else null,
            if (h.fires[gm]?.sR != null) "RIGHT took the notch after Back (S ${h.fires[gm]?.sR}): the shape did not happen" else null,
            if (h.verdicts.filter { it.first == gm }.map { it.second } != listOf(LocalRules.Verdict.Apply(wm.damage.core.wire.EvenHubMsg.EV_SCROLL_BOTTOM)))
                "the notch after Back: ${h.verdicts.filter { it.first == gm }.map { it.second }}" else null) })
    }

    @Test fun seededS24_aRefusedTailOnRightIsSeenAtTheSettle() = seeded(Taps.REPORT, live = false, script = { h ->
        h.refuse27On = Arm.RIGHT
        val t0 = h.now
        val g = notch(h)
        h.runUntil(t0 + 120)
        val s = h.fires[g]?.sR
        assertTrue(s != null && s in h.commitsSent, "S24: the answer was a COMMIT (commits ${h.commitsSent})")
        assertTrue(h.induced.any { it.startsWith("RIGHT mode 27") }, "S24: RIGHT refused the batch at its LIST (${h.induced})")
        h.runUntil(t0 + h.rules.q + 400)            // the lone SETTLE, the read's round trip, the keyframe landing
        val right = h.truthMismatches().filter { it.startsWith("RIGHT") }
        assertTrue(h.telemetryAsked == 1, "S24: one read asked at the SETTLE (${h.telemetryAsked})")
        assertTrue(right.isEmpty(), "S24: RIGHT equals the belief within a round after the SETTLE: $right")
        h.rest()
    })

    // ------------------------------------------------------------------------------------------- B083-real J-cases
    /** B083-real (`private/build083/B083-real.md`): the shapes his 0.81 session of 11-19 22:57–23:22 shows, run as [seeded]
     *  runs a case, returning what the invariants say instead of asserting none fail. A J-case that pins a defect asserts
     *  the defect is seen (its name ends `_showsDefect`), so a correction turns it red and the case is then rewritten to
     *  assert the cure. Everything here is the harness's model (modeled), not the glass. */
    private fun observed(taps: Taps, rows: Int, script: (LocalHarness) -> Unit): Pair<LocalHarness, List<String>> {
        val h = LocalHarness(42, taps)
        h.fixed = true
        if (System.getenv("LOCAL_FUZZ_TRACE") != null) h.trace = { println(it) }
        repeat(rows) { h.gesture(1, answer = true, band = "none"); h.runUntil(h.now + 600) }
        h.rest()
        val before = h.moves.keys.toSet()
        script(h)
        val failed = ArrayList<String>()
        atRest(h, failed, HashMap(), seeded = true)
        scrollLiveness(h, failed)
        for ((g, n) in h.moves) if (n > 1 && g !in before) failed += "(8) gesture $g moved the row $n times"
        ringCount(h, failed, "")
        println("J-case: row ${h.row}, ring ${h.ringRow()}, counts ${h.rules.counts.filterKeys { it in J_KEYS }}, failed ${failed.distinct()}")
        return h to failed.distinct()
    }
    private val J_KEYS = setOf("unreported", "lateEcho", "lateEchoUnpaid", "owesExpired", "owesAtSettle", "settles", "snapback.lost-report",
        "snapback.edge", "repair", "commit")

    /** J1 — his 23:17:20.163–21.731 (the run of eight; also 22:57:53.983, 23:16:40.458, 23:17:07.877, 23:17:26.480): RIGHT
     *  takes a clump of notches back to back (his S694–S701: eight fires inside 39 ticks, four drawn and four edges at the
     *  list's end) and the rubber band queues their scroll messages, one per band end (§6.1; the harness's band, 200 ms,
     *  every message queued, none dropped). The queue drains past the burst's lone SETTLE. On 0.82 that SETTLE cleared
     *  the owes and each late echo was applied as unreported: the phone's row ran past the ring's count, one row per late
     *  echo, and each answer's DISCARD took both lenses with it. Since C1 the SETTLE carries the owes it leaves open (one
     *  band each, the list in front) and the late echoes pay them: the row at rest is the ring's. ASSUMED where his
     *  journal does not say: which reports his eight messages belonged to (their directions match S698–S705), the queue's
     *  length and its drain rate (his eight came 194–240 ms apart). Watched to fail with the carry off. */
    @Test fun j1_lateEchoesAfterTheLoneSettlePayTheCarriedOwes() {
        val (h, failed) = observed(Taps.REPORT, rows = 3, script = { h ->
            h.bandDropScroll = 0.0
            for (d in longArrayOf(10, 9, 7, 8, 1, 2, 2, 0)) { h.gesture(1, answer = true); h.runUntil(h.now + d) }
            h.rest()
        })
        lateEchoesPaid("J1", h, failed)
    }

    /** The late echoes as the harness knows them (each message's own gesture): scroll messages that reached the phone
     *  after the lone SETTLE that followed their own report. */
    private fun lateEchoes(h: LocalHarness) = h.phoneMoves.filter { m -> !m.by115 && h.stepOf(m.g) != null &&
        h.reportHeardAt[m.g]?.let { r -> h.loneSettles.any { it > r && it <= m.t } } == true }

    /** J1's and J2's judgment, read from the harness before the rules' counts (B083 v3, decisions BO.5): the shape is a
     *  scroll message reaching the phone after the lone SETTLE that followed its own report (a late echo); the defect is
     *  one of them moving the phone's row (a second move of its gesture, 0.82's rules); the cure pays each one with a
     *  carried owe, every one counted `lateEcho`. */
    private fun lateEchoesPaid(name: String, h: LocalHarness, failed: List<String>) {
        val late = lateEchoes(h)
        assertTrue(late.isNotEmpty(), "$name: no scroll message reached the phone after its burst's lone SETTLE (the shape did not happen)")
        val twice = late.filter { it.after != it.before }
        assertTrue(twice.isEmpty(), "$name: ${twice.size} of ${late.size} late echo(es) moved the row a second time " +
            "(gestures ${twice.joinToString { "g${it.g} ${it.before}→${it.after}" }}): $failed; ${h.rules.counts}")
        assertTrue(failed.isEmpty(), "$name: $failed")
        assertEquals(late.size, h.rules.counts["lateEcho"] ?: 0, "$name: every late echo counted `lateEcho` (${h.rules.counts})")
    }

    /** J2 — his 22:57:53.983 and 23:16:40.458: a clump's last queued echo arrives just after its burst's lone SETTLE, and
     *  the next notch (a new burst) comes right after it. On 0.82 the echo was unreported (its owe went at the SETTLE) and
     *  the new notch's own message paid the new owe: the row ended one past the ring's count. Since C1 the carried owes
     *  pay the late echoes, the one still running at the new burst's first report joining its owes: the row at rest is
     *  the ring's. ASSUMED: the clump of five (his were the reports of the burst before, S537–S541 and S564–S566), the
     *  second notch 650 ms after the clump. Watched to fail with the carry off. */
    @Test fun j2_aLateEchoAcrossTheNextBurstsFirstReportPaysACarriedOwe() {
        val (h, failed) = observed(Taps.REPORT, rows = 0, script = { h ->
            h.bandDropScroll = 0.0
            repeat(5) { h.gesture(1, answer = true); h.runUntil(h.now + 6) }
            h.runUntil(h.now + 650)
            h.gesture(1, answer = true)
            h.rest()
        })
        lateEchoesPaid("J2", h, failed)
        assertTrue((h.rules.counts["unreported"] ?: 0) == 0, "J2: a stock message applied as unreported: ${h.rules.counts}")
    }

    /** J3 — his 23:16:36.266–37.126 (and 22:57:51.008–52.251, 23:16:38.446–39.084, 23:17:15.157–15.902): notches past the
     *  list's end, the first answered at once by the wrap (`ms=3`), the later edges answered 250–345 ms after their reports
     *  (`ms`), every answer reaching the lenses about 330 ms after it is decided (the wrap's measured start, BI); the next
     *  drawn notch fires at +705 ms from cursor 0 (his S555, `cursor 1`). With two edges the notch is adopted one row
     *  behind the ring's count: a notch lost with no snap-back. ASSUMED: the 330 ms is put on the link (where it sits —
     *  the loop, the radio or RIGHT's lane — is not in his journal; 0.82's transport times say), the later answers'
     *  decision lag is a 400 ms pump. */
    @Test fun j3_twoEdgesWhoseLaterDiscardLandsAfterTheNextNotch_showsDefect() {
        val (h, failed) = observed(Taps.REPORT, rows = 7, script = { h -> edgesThenANotch(h, longArrayOf(0, 7)) })
        assertTrue(failed.any { it.startsWith("(9)") }, "J3: the row at rest equals the ring's count (the notch was not lost): $failed")
        // B083 v2 (item 4): the declared J3 class explains it exactly — one notch adopted a step behind, nothing else
        val d = declarations(h)
        assertTrue(d.size == 1 && d[0].key == j3Key && d[0].rows == -1 && h.ringRow(d.map { Triple(it.t, it.n, it.rows) }) == h.row, "J3: ${d.map { "${it.key} g${it.g} ${it.rows}" }}")
    }

    /** J3b — J3 with his four edges (0, +18, +35, +212 ms): the notch is a `lost-report` snap-back (the lens jumps from row 1
     *  to the phone's row 4, as his did) and the row at rest is the ring's: what he sees is the jump, not a lost notch. */
    @Test fun j3b_fourEdgesThenTheNotchIsASnapBackAndTheCountHolds() {
        val (h, failed) = observed(Taps.REPORT, rows = 7, script = { h -> edgesThenANotch(h, longArrayOf(0, 18, 35, 212)) })
        assertTrue((h.rules.counts["snapback.lost-report"] ?: 0) > 0, "J3b: no lost-report answer (the shape did not happen): ${h.rules.counts}")
        assertTrue(failed.none { it.startsWith("(9)") }, "J3b: $failed")
    }

    private fun edgesThenANotch(h: LocalHarness, at: LongArray) {
        h.downExtraMs = 330
        val t0 = h.now
        // the first edge is answered at once (its 115 at +28, the pump 3 ms later: the wrap); every later answer is decided
        // 400 ms after its event (his `ms` of 250–345 for the later edges)
        var lagged = false
        fun lag() { if (!lagged) { h.runUntil(t0 + 32); h.pumpDelay = 400; lagged = true } }
        for (t in at) {
            if (t >= 32) lag()
            h.runUntil(t0 + t)
            h.gesture(1, answer = true, band = "none")
        }
        lag()
        h.runUntil(t0 + 705)
        h.gesture(1, answer = true, band = "none")
        h.runUntil(h.now + 5)
        h.pumpDelay = 3; h.downExtraMs = 0
        h.rest()
    }

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

    /** Seeds with their whole record (`LOCAL_FUZZ_SEED=<seed>[,<seed>…]`): for reading a failure. Passes when the variable is
     *  not set. */
    @Test
    fun oneSeedVerbose() {
        val seeds = System.getenv("LOCAL_FUZZ_SEED")?.split(',')?.map { it.trim().toLong() } ?: return
        debug = true
        for (seed in seeds) {
            println("===== seed $seed")
            val v = randomSequence(seed)
            println("seed $seed: ${v.stats}\n" + v.failed.joinToString("\n"))
        }
    }

    /** Decisions BQ: the smallest set of the four bases' seeds (at 2,000 a base) holding one sequence of each declared class
     *  of (9) and (10), run beside the gate's 60 + 60 so that a change turning a declared shape into an undeclared failure
     *  shows in the gate, not only at 8,000 (an exact search over the 12 classes of BQ: 8 seeds, one of them — 100100059, a
     *  stale-cursor adoption after an unreported stock message through a later reset — already the gate's own, index 59
     *  of base 1001; BR's two classes add two). */
    private val GATE_SEEDS = listOf(
        100101194L,   // (9) a stale-cursor adoption after a tap's DISCARD that set RIGHT back
        424201353L,   // (9) J3
        101265L,      // (9) an untaken notch's message paid by an owe; a lost report of a fire that drew, its message dropped
        100100313L,   // (9) a stale-cursor adoption after a refused fire applied as today
        100100382L,   // (9) a lost report of a fire that drew, its message paid by a carried owe
        100100778L,   // (9) a stale-cursor adoption after an unreported message; a lost report of a fire that did not draw;
                      //     one that drew, its message paid by an owe
        100101280L,   // (10) both classes: a front's notch paid by its burst's own owe — a dropped echo, a lost report's gap owe
        100216L,      // (10) the same with the burst opened under the front (decisions BR): a dropped echo
        101573L)      // (10) the same with the burst opened under the front: a lost report's gap owe
    /** The fifth base's declared classes, which its first 60 sequences do not hold: J3 and J3 through a later reset, both
     *  in 500854. */
    private val HIS_GATE_SEEDS = listOf(500854L)

    /** Seeds 1 and 1001 (spec §5): `LOCAL_FUZZ_N=1000` per seed base for the instrument's run (2,000 sequences); 60 in the
     *  gate, with [GATE_SEEDS]. */
    @Test
    fun randomSequencesHoldTheEightInvariants() {
        val n = System.getenv("LOCAL_FUZZ_N")?.toInt() ?: 60
        // `LOCAL_FUZZ_BASES=7,4242`: other seed bases for an instrument's extra run (G3); 1 and 1001 otherwise
        val bases = System.getenv("LOCAL_FUZZ_BASES")?.split(',')?.map { it.trim().toLong() } ?: listOf(1L, 1001L)
        val fails = ArrayList<String>()
        val counted = HashMap<String, Int>()
        val declaredSeeds = java.util.TreeMap<String, MutableList<Long>>()
        // `LOCAL_FUZZ_SEEDS=a,b`: exactly these seeds (reading a failure under one rule and another)
        val only = System.getenv("LOCAL_FUZZ_SEEDS")?.split(',')?.map { it.trim().toLong() }
        // decisions BQ: the gate-sized run also runs one sequence of each declared class of (9) and (10)
        val gate = System.getenv("LOCAL_FUZZ_N") == null && System.getenv("LOCAL_FUZZ_BASES") == null
        val seeds = only ?: (bases.flatMap { base -> (0 until n).map { i -> base * 100_000 + i } } + if (gate) GATE_SEEDS else emptyList())
        for (seed in seeds) {
            val v = try { randomSequence(seed) } catch (e: Throwable) { Verdict(listOf("threw: $e"), emptyMap()) }
            for (f in v.failed) fails += "seed $seed: $f"
            for ((k, c) in v.counted) counted[k] = (counted[k] ?: 0) + c
            // the declared shapes (row A, decisions W and BC's refused 27; (9)'s exact classes, B083 v2; S33), with their seeds
            for (k in v.counted.keys) if (k.startsWith("row A: a batch") || k.startsWith("(9)") || k.startsWith("(10)") || k.startsWith("(1) S33")) declaredSeeds.getOrPut(k) { ArrayList() } += seed
        }
        // every failure, one line each, for reading the shapes (the assertion's message keeps the first 30)
        for (f in fails) println("FAIL $f")
        println("declared: " + declaredSeeds.entries.joinToString("; ") { (k, s) -> "$k ${s.size} sequence(s): ${s.joinToString()}" })
        println("sequence fuzz: ${seeds.size} sequences (${if (only != null) "seeds ${only.joinToString()}" else "bases ${bases.joinToString()}"}), ${fails.size} failures; counted $counted")
        if (fails.isNotEmpty()) fail(fails.take(30).joinToString("\n") + "\n(${fails.size} in all)")
    }
}
