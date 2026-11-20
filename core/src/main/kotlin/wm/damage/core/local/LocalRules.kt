package wm.damage.core.local

import wm.damage.core.geom.Rect
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg

/** The `Taps` setting (`FIRMWARE.md` §6.7): `phone` binds no tap; `report` (the default) a report-only tap entry;
 *  `glasses` the press program. It has effect only on a bit-10 build at stage 4. */
enum class Taps {
    PHONE, REPORT, GLASSES;
    val word: String get() = name.lowercase()
    companion object { fun of(w: String): Taps? = entries.firstOrNull { it.word == w } }
}

/** What a surface offers the rules (`FIRMWARE.md` §6.7, "the bound list is the list in front"): the list it has staged
 *  on the glasses while it is in front (null: nothing staged), its row, and P3's one working-copy op. Main's from stage
 *  DM; nothing implements it before. */
interface BoundSurface {
    fun bound(): BoundList?
    fun row(): Int
    /** row := [row] — an adoption (P3) or a tap's correction (the tap rule (4)): no motion of the phone's own, the lens drew it. */
    fun setRow(row: Int)
    /** A re-stage's cursor shift from [from] to [to] (the LIST's shift: the row at the cursor's index in [to] minus its
     *  index in [from]); 0 when the rows did not move. */
    fun shift(from: BoundList, to: BoundList): Int = 0
}

/**
 * Build C2, the phone's rules P1–P11 and the tap rule (`FIRMWARE.md` §6.7), as one state machine with no clock, no
 * thread and no I/O of its own: the shell hands it, in arrival order (P2), every notify-115 report ([report]) and every
 * stock scroll message and click ([stock]) before it handles them, each telemetry read ([telemetry]), each session start
 * ([sessionStart]), the list in front ([front]) and `Taps` ([taps]); it answers what the shell does with each (a
 * [Verdict]) and, for every batch the shell sends while BIND is in force, the batch's tail of mode-27 and mode-26
 * sub-messages ([plan], then [tail]) — which go AFTER the batch's drawing subs (§6.5's batch order). Nothing here names
 * Main: a bound list is a [BoundList] (spec §6.7).
 *
 * The row of the list in front is the shell's; every entry point takes it, and a row that changed since the rules last
 * saw it, other than by an adoption or a tap's correction they ordered, is "something else moved the row" (P3).
 *
 * The six questions (`FORK.md` §3.8) for the state here are `FIRMWARE.md` §6.11's phone rows; in short: every field is
 * cleared at a session start; nothing waits (the lone SETTLE is due by the shell's pacing tick, [settleDue]); a message
 * that does not land is the table repair's ([TableRepair]) or a divergence's (a refusal body, a keyframe); it is read
 * and written on the shell's loop only.
 */
class LocalRules(private val note: (kind: String, detail: String) -> Unit = { _, _ -> },
                 /** The rules ask the compositor for ONE keyframe ([why] for the journal): the tap rule's (5) divergence (§6.7)
                  *  and a refusal record naming mode 27 (decisions on DP2's fix round: the batch's later subs did not run on
                  *  RIGHT, so its shadow is not what the phone believes). */
                 private val keyframe: (why: String) -> Unit = {},
                 /** The rules ask for ONE telemetry read of RIGHT (the existing read; decisions AA.2): at a burst's lone
                  *  SETTLE a COMMIT sent since the last SETTLE has had no outcome-3 body, so a refused tail may lie behind
                  *  it — the record then names mode 27 and [telemetry] does the rest. */
                 private val readTelemetry: (why: String) -> Unit = {}) {

    companion object {
        /** SETTLE's `age` (§6.6 of the build spec, the lead's decision B.10): 256 OS ticks, ≈ 250 ms. */
        const val AGE_TICKS = 256
        /** Q's floor, the lone SETTLE's delay after a burst's last report, on the phone's clock (decision B.10); Q itself
         *  follows the motion in force ([q], decisions AW.2). */
        const val Q_MS = 450L

        /** AW.2: Q for [list]'s pan — max([Q_MS], its length T × P + the [AGE_TICKS] hold of its last step + one period),
         *  so the lone SETTLE ends the last notch's instance and nothing released by it cuts a last step. */
        fun qFor(list: BoundList?): Long {
            val e = list?.entries?.firstOrNull { !it.isTap && it.program != null } ?: return Q_MS
            val ticks = e.program!!.T.toLong() * e.period + AGE_TICKS + e.period
            return maxOf(Q_MS, (ticks * 1000 + 1023) / 1024)
        }
        /** The rubber band's length, modeled (§6.1: ≈ 200 ms plus one LVGL period; I): a tap 115 this soon after the
         *  last scroll message is counted apart when no click follows (§6.10). */
        const val BAND_MS = 200L
        /** C1 (decisions BM.2): the rubber band sends a queued scroll message one band after another (§6.1; his run of
         *  eight came 194–240 ms apart, the floor 224 ms in 565 messages, measured): the carry's length per owe left open
         *  at a burst's lone SETTLE. */
        const val BAND_DRAIN_MS = 250L

        private fun s16(v: Int): Int = v.toShort().toInt()
        /** Ruled on stage DP2's report (`C2-decisions.md` U.2): DISCARD and SETTLE name their S less this, so the lens's
         *  signed 16-bit "at or after" covers a lens numbered behind RIGHT by up to 16,383 matched events (a SET that
         *  landed between the two lenses' handling of one gesture, a mode 27 lost on LEFT alone). On RIGHT every local
         *  older than the answer's own S is already answered; SETTLE's `age` still protects a fresh fire. */
        const val WIDE = 0x4000
        /** A slot's answer owed because its list came back to the front (decisions AF.2): a DISCARD naming the row, counted
         *  as a re-bind, not a snap-back. */
        const val REBIND = "rebind"

        /** A newer than b, signed 16-bit (§6.5's sequence compare). */
        fun after(a: Int, b: Int): Boolean = s16(a - b) > 0

        /** §6.5's batch order: every mode-26 sub 3..5 and mode 27 ([tail]) after every drawing sub-message ([draw]) — a
         *  refusal of theirs then loses only the batch's present, never the frame (§6.5; `C2-focus.md` 1(a) 5). */
        fun batchOrder(draw: List<ByteArray>, tail: List<ByteArray>): List<ByteArray> = draw + tail

        /** "A drawing sub of the batch meets the slot's areas" (P3; since AA.1 such a sub waits for the next batch instead
         *  of turning the COMMIT): each sub judged by the REAL rect it writes on each
         *  lens ([left], [right] — the compositor's own touched rects, a cached glyph run's cell included, never the
         *  transport's reading of a cached draw as reaching the panel's edges). */
        fun meets(left: List<Rect>, right: List<Rect>, areas: List<Pair<Rect, Rect>>): Boolean =
            areas.any { (al, ar) -> left.any { it.overlaps(al) } || right.any { it.overlaps(ar) } }
    }

    /** What the shell does with one report or stock message. */
    sealed class Verdict {
        /** An echo, a stale report, a dropped tap: nothing. */
        object Nothing : Verdict() { override fun toString() = "Nothing" }
        /** P3: row := [row] (one working-copy op, no animation of its own: the lens drew it). */
        data class Adopt(val row: Int) : Verdict()
        /** The gesture applied as today ([type] an `EvenHubMsg.EV_*`, from the ring). */
        data class Apply(val type: Int) : Verdict()
        /** The tap rule: act on [row] of the list in front, the row moved there first when it differs. */
        data class Act(val row: Int) : Verdict()
    }

    /** A stock message (§6.1): id 4 → the next row, id 5 → the one before, the click. */
    enum class Stock(val type: Int, val click: Boolean) {
        NEXT(EvenHubMsg.EV_SCROLL_BOTTOM, false), PREV(EvenHubMsg.EV_SCROLL_TOP, false), CLICK(EvenHubMsg.EV_CLICK, true);
        companion object {
            fun of(type: Int): Stock? = entries.firstOrNull { it.type == type }
        }
    }

    /** A planned COMMIT (P3): the compositor applies [list]'s END for ([prog4], [p]) to a copy of its model before the
     *  diff and holds back the drawing that meets [areas] to the next batch (P10, AA.1). */
    class Commit(val slot: Int, val s: Int, val list: BoundList, val cursor: Int, val prog4: Int, val p: IntArray,
                 /** When the adopted fire's motion has ended on the glasses at the latest, on the phone's clock: the newest
                  *  report's arrival plus T × P and one period (decisions AF.7 — the compositor sends nothing it held back
                  *  inside the areas before then). */
                 val motionEndsMs: Long = 0L) {
        val areas: List<Pair<Rect, Rect>> get() = list.areas
    }

    // ---------------------------------------------------------------------------------------------------- state
    /** BIND in force this session (bit 10, stage 4, armed): nothing is planned or sent otherwise (P1, P9). */
    var active = false; private set
    var taps = Taps.REPORT; private set
    /** P1, P8: the last S heard; the arming uptime (OS ticks). */
    var sHeard = 0; private set
    var armUptime = 0L; private set
    /** P5: the echo owes per kind and of either kind — each for a stock message still to come, cleared at a SETTLE (the
     *  scroll owes a burst's lone SETTLE leaves open are carried, C1: [owesCarriedUntil]) — and [u], the stock messages that arrived unreported for the bound list since S_heard last advanced (scroll messages
     *  and clicks alike: a gap says how many reports were missed, never their kinds). Decisions BB: [u] is kept across a
     *  SETTLE and cleared only when S_heard advances, where a gap is charged against it first and only the remainder
     *  becomes owes — a message that already came and was applied as today is owed nothing. */
    var owesScroll = 0; private set
    /** [owesScroll] by the direction of the report that owes it (its stock type): a scroll message pays an owe of its own
     *  direction, else one of either kind, else it is unreported (B083 fix stage, the focused read's 4 — a message of the
     *  other direction is a notch the lens did not report, never another notch's echo: the fuzz's seed 100100487). */
    private val owesDir = HashMap<Int, Int>()
    private fun owesDirInc(type: Int?) { if (type != null) owesDir[type] = (owesDir[type] ?: 0) + 1 }
    private fun owesDirDec(type: Int?) { if (type != null) owesDir[type] = ((owesDir[type] ?: 0) - 1).coerceAtLeast(0) }
    var owesClick = 0; private set
    var owesAny = 0; private set
    var u = 0; private set
    /** The tap rule's `tapsLive`: RIGHT has shown it holds the tap entry this session (and BIND is in force, M.4). */
    var tapsLive = false; private set
    /** P7: a burst is open from a report until the lone SETTLE. */
    var burstOpen = false; private set
    /** Q in force (AW.2): the last list in front's ([qFor]); the burst's SETTLE, the re-stages released by it (P9) and
     *  the burst's end all read it. */
    var q: Long = Q_MS
        private set
    private var lastReportMs = 0L

    /** C1 (decisions BM.2; `B083-real.md`: his late echoes): the scroll owes — of either kind too — a burst's lone SETTLE
     *  leaves open stay open, by direction, until this time on the phone's clock, one [BAND_DRAIN_MS] each (the rubber
     *  band's queue sends one message per band end, §6.1), while the bound list stays in front: a late echo then pays its
     *  owe instead of moving the row a second time. [Long.MIN_VALUE]: none carried. Read at every stock message, at a new
     *  burst's first report and at every SETTLE ([expireOwes]); cleared at a session start, BIND read off and the list
     *  leaving the front. No message waits on it. */
    private var owesCarriedUntil = Long.MIN_VALUE
    /** C4: the last lone SETTLE's carry window as it was set, kept when the carry is cleared early (the list left the
     *  front): a scroll message unpaid inside it is counted `lateEchoUnpaid`. */
    private var carryWindowUntil = Long.MIN_VALUE
    /** For the harnesses: the carry's end (MIN: none). */
    val owesCarriedUntilMs: Long get() = owesCarriedUntil

    /** C1: the scroll owes outside a burst that no carry holds go — a carry that ran out or whose list left the front,
     *  or owes left with none. A burst's own owes go only at its SETTLE. */
    private fun expireOwes(nowMs: Long) {
        if (burstOpen) return
        if (owesScroll + owesAny > 0 && nowMs < owesCarriedUntil && front != null) return
        dropScrollOwes()
    }
    private fun dropScrollOwes() {
        if (owesCarriedUntil != Long.MIN_VALUE && owesScroll + owesAny > 0) count("owesExpired", owesScroll + owesAny)
        owesScroll = 0; owesAny = 0; owesDir.clear(); owesCarriedUntil = Long.MIN_VALUE
    }
    /** The owes open at [nowMs] that no carry holds (the fuzz's invariant (4) at rest): a carried owe, live or run out
     *  before the next event clears it, is not counted. */
    fun owesUncarried(nowMs: Long): Int = owesClick + if (!burstOpen && owesCarriedUntil != Long.MIN_VALUE) 0 else owesScroll + owesAny

    /** The list in front, uncovered (null: covered, another window, nothing bound). */
    var front: BoundList? = null; private set
    private var rowSeen = -1
    /** The last row the shell gave for each list (a DISCARD of a slot whose list is covered names it). */
    private val lastRow = HashMap<Int, Int>()
    /** The list last in front (−1 none this session): its row is kept current while it is withdrawn (AF.2). */
    private var lastFront = -1

    /** An entry the phone holds on the glasses: SET sent, no CLEAR since. */
    class Held(val list: Int, val entry: BoundList.Entry)
    private val held = HashMap<Int, Held>()
    /** The mark of the last LIST sent per list (the wire mark field 17 is compared with); −1 none this session. */
    private val wireMark = IntArray(CfwModes.BIND_LISTS) { -1 }
    /** The staging each list's wire mark stands for (its BoundList), so a re-stage is seen. */
    private val staged = arrayOfNulls<BoundList>(CfwModes.BIND_LISTS)
    private var markCounter = 0

    /** P3's per-slot answer state: A, the reports handled since the last answer, whether anything else moved the row. */
    private class Handled(val s: Int, val adopted: Boolean, val tap: Boolean, val list: Int, val cursor: Int, val prog4: Int,
                          val p: IntArray?, val atMs: Long, val cause: String,
                          /** The report's event id (decisions AZ.4: an edge's step is its entry's). */
                          val eventId: Int = -1)
    private class Slot(var a: Int) {
        val handled = ArrayList<Handled>()
        var moved: String? = null            // why the row moved other than by an adoption (null: it did not)
        var discard: String? = null          // an answer forced to DISCARD by a click (glasses, no report)
        var list = -1
        val owed: Boolean get() = handled.isNotEmpty() || moved != null || discard != null
    }
    private val slots = HashMap<Int, Slot>()

    /** Table messages owed by a start, a cover, a return, a re-stage, a switch or a refusal record, in stream order. */
    private sealed class Pending {
        object ClearAll : Pending()
        class Clear(val entry: Int) : Pending()
        class Set(val list: BoundList, val entry: BoundList.Entry) : Pending()
        class ListMsg(val list: BoundList, val shift: Int, val fresh: Boolean) : Pending()
    }
    private val pending = ArrayList<Pending>()
    /** Mode-8/14 (decisions M.5): every table object a batch touched since the last telemetry read. */
    private val touchedSinceRead = LinkedHashSet<Any>()

    val repair = TableRepair()

    // ---- counting (§6.7 Counting; the `local` note)
    val counts = java.util.TreeMap<String, Int>()
    private fun count(k: String, n: Int = 1) { counts[k] = (counts[k] ?: 0) + n }
    private var tapAwaitingClick: Long? = null          // a tap 115's receipt, until its click or the next tap / SETTLE
    private var lastScrollMsgMs = Long.MIN_VALUE / 2

    // ------------------------------------------------------------------------------------------ session (P1)
    /**
     * A session start. [bind]: BIND in force (bit 10, stage 4, armed) — else the rules are idle and send nothing (today's
     * start, byte for byte). [sHeard0]: 0 on a fresh acquire, field 36 on a carried lease; [armUptime]: field 2 of the
     * reply to the FLAGS_SET that armed BIND. The start's bind batch (CLEAR 0xFF, the SETs of the list in front, its LIST
     * with a fresh mark) is owed to the first batch after the warmup keyframe, which closed every instance.
     */
    fun sessionStart(bind: Boolean, sHeard0: Int, armUptime: Long, front: BoundList?, row: Int, taps: Taps) {
        this.taps = taps
        active = bind
        sHeard = sHeard0 and 0xFFFF; this.armUptime = armUptime
        owesScroll = 0; owesClick = 0; owesAny = 0; u = 0; owesDir.clear()
        owesCarriedUntil = Long.MIN_VALUE; carryWindowUntil = Long.MIN_VALUE
        tapsLive = false; burstOpen = false; bindFlag = true
        slots.clear(); held.clear(); pending.clear(); touchedSinceRead.clear(); lastRow.clear(); lastFront = front?.list ?: -1
        wireMark.fill(-1); staged.fill(null)
        repair.sessionStart()
        tapAwaitingClick = null
        discardSinceSettle.clear(); unconfirmed.clear(); committed.clear()
        this.front = if (bind) front else null
        if (front != null) q = qFor(front)
        rowSeen = row
        if (!bind) return
        pending += Pending.ClearAll
        front?.let { f -> for (e in entriesOf(f)) pending += Pending.Set(f, e); pending += Pending.ListMsg(f, 0, fresh = true) }
        count("starts")
    }

    /** The link or the session ended: nothing more is planned until the next start. */
    fun sessionEnd() { active = false; front = null; pending.clear() }

    /** BIND in the flags in force, as the last telemetry record (or the transport's state) shows it: `tapsLive` needs it
     *  as well as field 39's bit (decisions M.4). Whether anything is SENT is the shell's gate (BIND in force and the
     *  lease held, P9), not this. */
    private var bindFlag = true
    /** RIGHT's read of BIND. Off (a hand FLAGS_CLEAR inside a burst's wait): the burst can no longer close by its SETTLE
     *  (no rule sends one while unbound), so it is closed here — else the save, a re-stage's pieces and the period waited
     *  on it until the next session (the focused read of B082, N1). Its scroll owes go with it (C1: no SETTLE can come). */
    fun bindInForce(on: Boolean) { bindFlag = on; if (!on) { tapsLive = false; burstOpen = false; dropScrollOwes() } }

    // ---------------------------------------------------------------------------------------- the list in front
    /** The entries the phone SETs for [list] under the current `Taps` (§6.7): its scroll entries, and the tap entry in
     *  the form `Taps` asks — none (`phone`), report-only (`report`), the press program (`glasses`; report-only when the
     *  list stages no press). */
    fun entriesOf(list: BoundList): List<BoundList.Entry> = list.entries.filter { !it.isTap } + listOfNotNull(tapEntry(list))

    fun tapEntry(list: BoundList): BoundList.Entry? {
        if (taps == Taps.PHONE) return null
        val press = list.entries.firstOrNull { it.isTap }
        if (taps == Taps.GLASSES && press != null && !press.reportOnly) return press
        val no = press?.entry ?: (0 until CfwModes.BIND_ENTRIES).first { n -> list.entries.none { it.entry == n } }
        return BoundList.Entry(no, CfwModes.EV_TAP, 0, press?.period ?: 34, CfwModes.PROG_NONE,
            press?.params ?: List(4) { CfwModes.BindParam(CfwModes.KIND_ARG, 0) }, null)
    }

    /**
     * The shell's list in front changed — a cover opened or closed, another window, a re-stage of the same list (a new
     * [BoundList], its [shift] the cursor's move in the new staging) — in the batch that draws it (P9, "the bound list is
     * the list in front"): CLEARs of the old list's entries, SETs of the new one's, a LIST for a staging the glasses do
     * not hold. [row] is the row in the new list.
     */
    fun front(list: BoundList?, row: Int, shift: Int = 0) {
        val old = front
        front = list
        if (list != null) { lastRow[list.list] = row; lastFront = list.list; q = qFor(list) }
        rowSeen = row
        if (!active) return
        // decisions AF.2: every return of a list to the front (a cover's, a withdrawal's, the first offer after a start)
        // names the phone's CURRENT row in the batch that binds it again, a burst open or not — the slot's answer in that
        // batch is a DISCARD naming it (a notch applied as today while the list was away moved the row, not the lens's
        // cursor); it is a re-bind, not a snap-back
        if (list != null && row >= 0 && (old == null || old.list != list.list)) slot(list.slot).let { st -> st.list = list.list; st.moved = st.moved ?: REBIND }
        if (old != null && (list == null || list.list != old.list)) {
            // C1: a carry ends when its list leaves the front — a late echo then reaching a window is that window's notch
            // as today (decisions BM.2: counted, `lateEchoUnpaid`, not ruled)
            if (!burstOpen) dropScrollOwes()
            // the old list leaves the front: CLEAR every entry the phone holds for it (and any SET still owed)
            pending.removeAll { it is Pending.Set && it.list.list == old.list }
            for ((no, h) in held.toList()) if (h.list == old.list) pending += Pending.Clear(no)
        }
        if (list == null) return
        // a CLEAR owed for this list's entries and not yet sent is superseded: the lens still holds them (the class of a
        // pending message overtaken before it went — the cover's SETs are dropped above, a switch's below)
        val mine = entriesOf(list).map { it.entry }.toSet()
        pending.removeAll { it is Pending.Clear && it.entry in mine && held[it.entry]?.list == list.list }
        val was = staged[list.list]
        if (was == null || was.items4 != list.items4 || was.count != list.count || was.pitch != list.pitch || was.mark != list.mark) {
            // a staging the glasses do not hold (P9: a row's index or identity changed, a new mark): a LIST; a program's
            // change alone is its SET (below), a row's picture updated in place keeps its mark and sends nothing
            if (old != null && old.list == list.list) slot(list.slot).moved = slot(list.slot).moved ?: "restaged"
            pending += Pending.ListMsg(list, if (was == null) 0 else shift, fresh = true)
        } else staged[list.list] = list
        // SETs for every entry the glasses do not hold in its current form
        for (e in entriesOf(list)) if (!holds(list.list, e)) pending += Pending.Set(list, e)
    }

    /** `Taps` changed (§6.7 Switching): only the tap entry's SET in its new form, or its CLEAR; nothing else. */
    fun taps(t: Taps) {
        if (t == taps) return
        taps = t
        val f = front ?: return
        if (!active) return
        val e = tapEntry(f)
        // a tap entry's SET or CLEAR still owed is superseded by this switch: decide from what the lens holds
        val no = e?.entry ?: f.entries.firstOrNull { it.isTap }?.entry ?: held.entries.firstOrNull { it.value.list == f.list && it.value.entry.isTap }?.key
        pending.removeAll { (it is Pending.Set && it.entry.isTap) || (it is Pending.Clear && it.entry == no) }
        if (e == null) held.entries.firstOrNull { it.value.list == f.list && it.value.entry.isTap }?.let { pending += Pending.Clear(it.key) }
        else if (!holds(f.list, e)) pending += Pending.Set(f, e)
    }

    private fun holds(list: Int, e: BoundList.Entry): Boolean {
        val h = held[e.entry] ?: return false
        return h.list == list && sameEntry(h.entry, e)
    }

    private fun sameEntry(a: BoundList.Entry, b: BoundList.Entry) = a.entry == b.entry && a.eventId == b.eventId &&
        a.step == b.step && a.period == b.period && a.prog4 == b.prog4 && a.params == b.params

    /** The entry the phone holds for [eventId] on [list] (SET sent, no CLEAR since), or null. */
    fun heldFor(list: Int, eventId: Int): BoundList.Entry? =
        held.values.firstOrNull { it.list == list && it.entry.eventId == eventId }?.entry
    fun holdsEntry(entry: Int): Boolean = held.containsKey(entry)
    fun heldEntry(entry: Int): Held? = held[entry]
    fun markOf(list: Int): Int = if (list in 0 until CfwModes.BIND_LISTS) wireMark[list] else -1

    // ----------------------------------------------------------------------------- reports and stock messages
    private fun observe(row: Int) {
        val f = front
        if (f != null && row != rowSeen && rowSeen >= 0) {
            val s = slot(f.slot)
            if (s.moved == null) s.moved = "moved"
        }
        rowSeen = row
        if (f != null) lastRow[f.list] = row
        // decisions AF.2: the shell hands the surface's row while it is uncovered, offered or not — a withdrawn list's row
        // stays current, so a DISCARD naming it (a report answered while it is away) names where the frame is
        else if (row >= 0 && lastFront >= 0) lastRow[lastFront] = row
    }

    /** A slot first heard of: A is S_heard as it stands before the report that names it (the session start's, or the last
     *  one heard — the answer's wide bound covers every local of the session before it, decisions U.2). */
    private fun slot(n: Int, heard: Int = sHeard): Slot = slots.getOrPut(n) { Slot(heard) }

    /**
     * One notify-115 report, in arrival order (P2), with the phone's row of the list in front. P8, then P5's count, then
     * the table repair's evidence, then P3 (a scroll) or the tap rule (a tap).
     */
    fun report(a: DamageMsg.Applied, row: Int, nowMs: Long): Verdict {
        observe(row)
        if (!active) return Verdict.Nothing
        val s = a.s ?: return Verdict.Nothing.also { note("local", "a 115 without S — ignored: ${a.describe()}") }
        val heardBefore = sHeard
        val tap = a.isTap
        // P8: not after S_heard, or before this session's arming
        val up = a.uptimeTicks
        if (!after(s, sHeard) || (up != null && (up - armUptime).toInt() < 0)) {
            count("stale")
            note("local", "stale 115 dropped (P8): S $s, S_heard $sHeard" + (up?.let { ", uptime $it, armed at $armUptime" } ?: ""))
            if (tap) tapdrop("stale", a, row)
            return Verdict.Nothing
        }
        // C1: a new burst's first report first ends a carry that ran out (one still running joins this burst's owes)
        expireOwes(nowMs)
        // P5: the gap g, the owes it adds (n: the gap less the unreported messages that already came since S_heard last
        // advanced, decisions BB — those were applied as today, whatever SETTLE came between), then this report's own owe
        val g = s16(s - sHeard - 1).coerceAtLeast(0)
        val n = (g - u).coerceAtLeast(0)
        owesAny += n
        count("gap", g)
        u = 0
        sHeard = s
        val ownType = if (tap) null else a.eventId?.let { BoundList.stockEvent(it) }
        if (tap) owesClick++ else { owesScroll++; owesDirInc(ownType) }
        burstOpen = true
        lastReportMs = nowMs
        if (tap) {
            tapNoClick()
            tapAwaitingClick = nowMs
            tapsLive = bindFlag                      // RIGHT has shown it holds the tap entry this session
        }
        // the slot and list (a 3 or 14 body carries neither: the entry the phone holds for its event id names them)
        val f = front
        val heldE = (a.entry?.let { held[it] }) ?: f?.let { fl -> a.eventId?.let { id -> heldFor(fl.list, id)?.let { held[it.entry] } } }
        val listId = a.list ?: heldE?.list ?: f?.list ?: -1
        val slotNo = a.slot ?: f?.takeIf { it.list == listId }?.slot ?: return Verdict.Nothing.also {
            // decisions AF.5 (P4): a refused 3 or 14 body names no list, and with none in front it is nobody's to answer —
            // its own owe is taken back, so its stock message is applied as today (the gesture on a cover is not lost)
            if (tap) owesClick-- else { owesScroll--; owesDirDec(ownType) }
            note("local", "a 115 naming no slot the phone knows — not answered; its stock message goes as today: ${a.describe()}")
        }
        val st = slot(slotNo, heardBefore)
        st.list = listId
        repair.onReport(a, this)
        return if (tap) tapRule(a, st, n, row, nowMs) else scrollRule(a, st, n, row, nowMs)
    }

    private fun scrollRule(a: DamageMsg.Applied, st: Slot, n: Int, row: Int, nowMs: Long): Verdict {
        val s = a.s!!
        val id = a.eventId ?: -1
        val type = BoundList.stockEvent(id) ?: return Verdict.Nothing
        val f = front
        fun asToday(cause: String): Verdict {
            st.handled += Handled(s, false, false, a.list ?: st.list, a.cursor ?: -1, -1, null, nowMs, cause, id)
            return Verdict.Apply(type)
        }
        when (a.outcome) {
            DamageMsg.APPLIED_REFUSED -> {
                val r = a.reason ?: 0
                if (r == 5 || r == 12) note("local", "a fire refused $r (P4): ${a.describe()}")
                return asToday(if (r == 5 || r == 12 || r == 18) "restaged" else "link")
            }
            DamageMsg.APPLIED_EDGE -> return asToday("edge")
            DamageMsg.APPLIED_DREW -> {}
            else -> return asToday("refused")
        }
        // P3: in front, the mark, the prediction, the bound
        if (f == null || a.list != f.list) return asToday("focus")
        if (a.mark != wireMark[f.list]) return asToday("restaged")
        val e = heldFor(f.list, id) ?: f.entryFor(id) ?: return asToday("restaged")
        val c = a.cursor ?: return asToday("restaged")
        // the prediction is the staging's own, before or after a band form's switch until that write is acknowledged
        // (decisions X.2, `BoundList.names`); the COMMIT carries the report's own prog and P, whose pictures the phone holds
        if (!f.names(c, e, a.prog, a.params)) return asToday("restaged")
        if (kotlin.math.abs(c - row) > (n + 1) * kotlin.math.abs(e.step)) return asToday("lost-report")
        st.handled += Handled(s, true, false, f.list, c, a.prog!!, a.params!!, nowMs, "")
        rowSeen = c
        lastRow[f.list] = c
        return Verdict.Adopt(c)
    }

    private fun tapRule(a: DamageMsg.Applied, st: Slot, n: Int, row: Int, nowMs: Long): Verdict {
        val s = a.s!!
        fun drop(cause: String): Verdict {
            st.handled += Handled(s, false, true, a.list ?: st.list, a.cursor ?: -1, -1, null, nowMs, cause)
            tapdrop(cause, a, row)
            return Verdict.Nothing
        }
        if (a.outcome == DamageMsg.APPLIED_REFUSED && (a.reason == 3 || a.reason == 14)) return drop("refused-${a.reason}")
        val f = front
        if (f == null || a.list != f.list) return drop("focus")                                   // (2)
        if (a.mark != wireMark[f.list]) return drop("restaged")                                   // (3)
        val c = a.cursor ?: return drop("lost")
        if (c == BoundList.LOST) return drop("lost")
        if (c != row && kotlin.math.abs(c - row) > n * f.maxStep) return drop("moved")            // (4)
        val p = a.params
        if (p != null) {                                                                          // (5)
            // the phone's own tap entry under `Taps` now: under `report` and `phone` it has no program, so carried fields
            // cannot equal its prediction (a lens still on the press form, `C2-F-focus.md` B1)
            val e = tapEntry(f)?.takeIf { !it.reportOnly }
            if (e == null || !f.names(c, e, a.prog, p)) {
                note("divergence", "a tap 115's program or parameters differ from the phone's prediction at cursor $c (tap rule 5): ${a.describe()}")
                count("divergence")
                keyframe("a tap 115's program or parameters differ from the prediction (tap rule 5)")
                return drop("item")
            }
        }
        st.handled += Handled(s, false, true, f.list, c, -1, null, nowMs, "tap")
        count("tapActed")
        if (c != row) count("tapActedCorrected")
        if (a.outcome == DamageMsg.APPLIED_REFUSED && a.reason == 17 || p == null) count("tapActedNoPress")
        rowSeen = c
        lastRow[f.list] = c
        return Verdict.Act(c)
    }

    private fun tapdrop(cause: String, a: DamageMsg.Applied, row: Int) {
        count("tapdrop.$cause")
        val l = a.list
        note("tapdrop", "cause=$cause S=${a.s} list=${l ?: "-"} cursor=${a.cursor ?: "-"} row=$row mark=${a.mark ?: "-"} " +
            "markNow=${l?.let { markOf(it) } ?: "-"} taps=${taps.word}")
    }

    /**
     * One stock scroll message or click from the ring, before the shell handles it (P5, the click): consumed as an
     * echo, applied as today, or dropped.
     */
    fun stock(kind: Stock, row: Int, nowMs: Long): Verdict {
        observe(row)
        if (!active) return Verdict.Apply(kind.type)
        if (!kind.click) lastScrollMsgMs = nowMs
        expireOwes(nowMs)                                       // C1: a carry that ran out pays nothing
        // C4: a scroll message paid by an owe the lone SETTLE carried — a late echo of the band's queue
        val carried = !burstOpen && owesCarriedUntil != Long.MIN_VALUE
        if (kind.click && owesClick > 0) { owesClick--; clickArrived(); return Verdict.Nothing }
        // B083 fix stage (the focused read's 4): a scroll message pays an owe of its own direction, else one of either kind,
        // else it is unreported (seed 100100487: a notch made under a cover, its message held back by the rubber band past
        // the uncover, paid a later notch's owe of the other direction, whose own message then moved the row a second time)
        if (!kind.click && (owesDir[kind.type] ?: 0) > 0) { owesScroll--; owesDirDec(kind.type); if (carried) count("lateEcho"); return Verdict.Nothing }
        if (owesAny > 0) { owesAny--; if (kind.click) clickArrived() else if (carried) count("lateEcho"); return Verdict.Nothing }
        // C4: unpaid inside the last lone SETTLE's carry window — the list no longer in front, or no carried owe of its
        // direction left
        if (!kind.click && !burstOpen && nowMs < carryWindowUntil) count("lateEchoUnpaid")
        val f = front
        if (!kind.click) {
            val e = f?.let { fl -> heldFor(fl.list, CfwModes.EV_UPROLL) ?: heldFor(fl.list, CfwModes.EV_DOWNROLL) }
            if (f == null || e == null) return Verdict.Apply(kind.type)                 // unbound: as today
            u++
            val st = slot(f.slot)
            st.list = f.list
            st.moved = st.moved ?: "lost-report"
            count("unreported")
            repair.onUnreportedScroll(f.list, this)
            return Verdict.Apply(kind.type)
        }
        val tapHeld = f?.let { heldFor(it.list, CfwModes.EV_TAP) } != null
        if (f == null || !tapHeld || !tapsLive) return Verdict.Apply(kind.type)        // no tap matched: as today, not in u
        return when (taps) {
            Taps.GLASSES -> {
                val st = slot(f.slot)
                st.list = f.list
                st.discard = "no-report"
                count("tapdrop.no-report")
                note("tapdrop", "cause=no-report S=- list=${f.list} cursor=- row=$row mark=${markOf(f.list)} markNow=${markOf(f.list)} taps=${taps.word}")
                Verdict.Nothing
            }
            else -> { u++; Verdict.Apply(kind.type) }
        }
    }

    /** A tap 115 whose click has not come (§6.10: one within a band of the last scroll message is counted apart). */
    private fun tapNoClick() {
        tapAwaitingClick?.let { t -> count(if (t - lastScrollMsgMs <= BAND_MS) "tapNoClickInBand" else "tapNoClick") }
        tapAwaitingClick = null
    }

    private fun clickArrived() {
        if (tapAwaitingClick != null) { count("tapClick"); tapAwaitingClick = null }
    }

    /**
     * A RIGHT telemetry read (P1, the tap rule): field 39 (null: not carried) and a new refusal record naming mode 27
     * ([refusal27]) or a batch dropped whole ([batch14], mode 8 reason 14 — decisions M.5).
     */
    fun telemetry(boundIds: Long?, refusal27: DamageMsg.Refusal?, batch14: DamageMsg.Refusal? = null) {
        if (!active) { tapsLive = false; return }
        if (boundIds != null) tapsLive = bindFlag && boundIds and 1L != 0L
        if (refusal27 != null) {
            note("repair", "evidence=refusal mode=27 reason=${refusal27.reason} object=table")
            count("repair.table")
            wholeTable()
            // RIGHT refused a mode 27 and sent no notify: what came after it in that batch — a COMMIT's END among it —
            // did not run there, so RIGHT's shadow is not what the phone believes (row A's second trigger, ruled): one keyframe
            count("keyframe")
            keyframe("RIGHT's refusal record names mode 27 (reason ${refusal27.reason}): the rest of that batch did not run there")
        } else if (batch14 != null && touchedSinceRead.isNotEmpty()) {
            note("repair", "evidence=refusal mode=8 reason=14 object=batch(${touchedSinceRead.joinToString(",")})")
            count("repair.batch")
            for (o in touchedSinceRead.toList()) when (o) {
                is Int -> { val f = front; val h = held[o]
                    if (h != null && f != null && h.list == f.list) pending += Pending.Set(f, h.entry) else if (h == null) pending += Pending.Clear(o) }
                is String -> { val f = front; val l = o.removePrefix("list").toInt()
                    if (f != null && f.list == l) pending += Pending.ListMsg(f, 0, fresh = false) }
            }
        }
        touchedSinceRead.clear()
    }

    /**
     * A flush that carried the rules' messages was refused by a lens (its image ack failed, `[I]`: the transport cannot
     * say which sub-message or lens). Marked as the conservative form of a place `FIRMWARE.md` §6 is silent on
     * (`C2-DP2-built.md` §0): a lens that missed a LIST (lost, or refused there for a shared reason) refuses every later
     * COMMIT, DISCARD and SETTLE whose cursor lies outside the count it holds (12), and each refusal ends that lens's
     * batch before its present — so its panel stops updating, every idle batch carrying a SETTLE; RIGHT's sticky refusal
     * record then names mode 26, not 27, so P1's re-send never fires, and LEFT's is never read at all. The answer is P1's:
     * the whole table again in the next batch (CLEAR 0xFF, the SETs, the LIST with a fresh mark), which the batch's SETTLE
     * then realigns. Idempotent: a refusal for any other reason costs one table's bytes.
     */
    fun tableAgain(why: String) {
        if (!active) return
        note("repair", "evidence=\"$why\" object=table")
        count("repair.table")
        wholeTable()
    }

    /** P1's whole table again: CLEAR 0xFF, the SETs, the LIST with a fresh mark. */
    private fun wholeTable() {
        pending.clear()
        pending += Pending.ClearAll
        front?.let { f -> for (e in entriesOf(f)) pending += Pending.Set(f, e); pending += Pending.ListMsg(f, 0, fresh = true) }
    }

    // ------------------------------------------------------------------------------------------------ the batch
    /** True when the next batch must carry something of the rules' even with nothing to draw: an owed answer, a table
     *  message, a repair. */
    val owed: Boolean get() = active && (pending.isNotEmpty() || slots.values.any { it.owed } || repair.owedNow())

    /** P7: the lone SETTLE is due — a burst open, Q after its last report, every report answered. */
    fun settleDue(nowMs: Long): Boolean = active && burstOpen && nowMs - lastReportMs >= q && slots.values.none { it.owed }

    /** When the lone SETTLE falls due ([settleDue]), on the phone's clock; null with no burst open. */
    fun settleAt(): Long? = if (active && burstOpen) lastReportMs + q else null

    /** The lists a batch's tail will carry a LIST for. */
    private fun listsInTail(): Set<Int> = pending.filterIsInstance<Pending.ListMsg>().map { it.list.list }.toSet()

    /**
     * P3, the first half of a batch's answer: the slots whose answer would be a COMMIT — every report of the slot handled
     * since its last answer adopted (none a tap), nothing else moved its row, no LIST of its list in this batch. The
     * compositor applies each one's END to its model before the diff, holds back what meets the areas to the next batch
     * (AA.1), and hands back the slots it cannot follow ([tail]'s `denied`: no END painter, or a keyframe's batch).
     */
    fun plan(row: Int): List<Commit> {
        observe(row)
        if (!active) return emptyList()
        val lists = listsInTail()
        val out = ArrayList<Commit>()
        for ((n, st) in slots) {
            val h = st.handled.lastOrNull() ?: continue
            if (st.moved != null || st.discard != null || st.handled.any { !it.adopted || it.tap } || st.list in lists) continue
            val bl = front?.takeIf { it.list == h.list && it.slot == n } ?: continue
            val e = bl.entries.firstOrNull { it.prog4 == h.prog4 } ?: bl.previous?.entries?.firstOrNull { it.prog4 == h.prog4 }
            val stepsMs = e?.program?.let { (it.T + 1).toLong() * e.period * 1000L / 1024 } ?: 0L
            out += Commit(n, h.s, bl, h.cursor, h.prog4, h.p!!, h.atMs + stepsMs)
        }
        return out
    }

    /** Decisions AZ.4: the wrap a batch animates from the phone — the bound slot of the list in front owes its answer,
     *  its newest report is an edge at one end of the list ([from], the lens's cursor, which an edge leaves), and the
     *  phone's row is now the other end (the edge's stock move applied: Main's wrap). [cPrime] is the row just past the
     *  end (−1 or the count), [step] the entry's. The answer stays the DISCARD naming the row ([tail]); the shell puts the
     *  pan's PLAY ahead of it in a free slot when the list's staging wraps. */
    class Wrap(val slot: Int, val list: BoundList, val step: Int, val from: Int, val cPrime: Int)

    fun wrapPlan(row: Int): Wrap? {
        observe(row)
        if (!active) return null
        val f = front ?: return null
        val st = slots[f.slot]?.takeIf { it.owed && it.discard == null } ?: return null
        val h = st.handled.lastOrNull() ?: return null
        if (h.adopted || h.tap || h.cause != "edge" || h.list != f.list) return null
        val e = f.entryFor(h.eventId)?.takeIf { !it.isTap && it.step != 0 } ?: return null
        val cPrime = h.cursor + e.step
        if ((cPrime != -1 && cPrime != f.count) || row != cPrime.mod(f.count)) return null
        return Wrap(f.slot, f, e.step, h.cursor, cPrime)
    }

    /**
     * The batch's tail, after its drawing subs (§6.5): the table messages owed (start, cover, return, re-stage, switch,
     * refusal), the table repair's re-sends not already carried, each owed slot's answer (a LIST before its slot's
     * answer), and — while no burst is open — one SETTLE. [denied]: planned COMMITs the compositor turned to DISCARD (no
     * END painter, or the batch is a keyframe). [frame]: the batch draws something (for the notes).
     * Everything returned is taken as sent: the shell sends it in this batch or not at all (a rolled-back flush keyframes,
     * and the keyframe's start re-sends the table).
     */
    fun tail(row: Int, nowMs: Long, denied: Set<Int> = emptySet(), frame: Boolean = true, wrapped: Boolean = false): List<ByteArray> {
        observe(row)
        if (!active) return emptyList()
        val out = ArrayList<ByteArray>()
        val listed = HashSet<Int>()
        val setEntries = HashSet<Int>()
        val clearedEntries = HashSet<Int>()
        // 1. the owed table messages, in the order they were owed
        for (p in pending) when (p) {
            is Pending.ClearAll -> { out += CfwModes.bindClear(CfwModes.BIND_ALL); held.clear(); clearedEntries.addAll(0 until CfwModes.BIND_ENTRIES) }
            is Pending.Clear -> { out += CfwModes.bindClear(p.entry); held.remove(p.entry); clearedEntries += p.entry; touchedSinceRead += p.entry
                if (front?.let { f -> tapEntryNo(f) == p.entry } != false) tapsLive = false }
            is Pending.Set -> { out += p.list.set(p.entry); held[p.entry.entry] = Held(p.list.list, p.entry); setEntries += p.entry.entry; touchedSinceRead += p.entry.entry }
            is Pending.ListMsg -> { out += listMsg(p.list, p.shift, p.fresh); listed += p.list.list }
        }
        val hadClearAll = pending.any { it is Pending.ClearAll }
        pending.clear()
        if (hadClearAll) tapsLive = false
        // 2. the table repair's re-sends (the 5th kind waits for a batch that carries a SETTLE)
        val settleHere = !burstOpen
        takeRepairs(out, settleHere, listed, setEntries, clearedEntries)
        // 3. the answers, one per owed slot
        for ((n, st) in slots.toSortedMap()) {
            if (!st.owed) continue
            val h = st.handled.lastOrNull()
            val commit = h != null && st.moved == null && st.discard == null && st.handled.all { it.adopted && !it.tap } &&
                st.list !in listed && n !in denied && front?.let { it.list == h.list && it.slot == n } == true
            val list = st.list.takeIf { it in 0 until CfwModes.BIND_LISTS }
            if (commit) {
                ridingList(out, h!!.list, listed)
                out += CfwModes.motionCommit(n, h.s, h.list, h.cursor, h.prog4, h.p!!)
                count("commit")
                unconfirmed += n to h.s
                committed += n to h.s
            } else {
                val cur = if (list != null && front?.list == list) row else list?.let { lastRow[it] }
                val named = if (list != null && cur != null && cur >= 0) list else CfwModes.BIND_ALL
                if (named != CfwModes.BIND_ALL) ridingList(out, named, listed)
                out += CfwModes.motionDiscard(n, (st.a + 1 - WIDE) and 0xFFFF, named, if (named == CfwModes.BIND_ALL) 0 else cur!!)
                discardSinceSettle += n
                snapback(st, n, row, nowMs, frame, denied, wrapped)
            }
            st.a = sHeard
            st.handled.clear(); st.moved = null; st.discard = null
        }
        // 4. the idle batch's SETTLE (P7): while no burst is open, every batch carries one
        if (settleHere) { front?.let { ridingList(out, it.list, listed) }; out += settle(row, nowMs) }
        return out
    }

    /**
     * Ruled on stage DP2's report (`C2-decisions.md` U.1): every COMMIT, DISCARD or SETTLE that names a list rides behind
     * that list's LIST with shift 0 — its current staging and mark, idempotent, 12 B — unless the batch carries a LIST
     * for it already. A lens that missed a LIST (lost, or refused there, which nobody hears from LEFT) then holds the
     * current count and mark whenever an answer is checked, so no answer is refused for a stale count and the miss heals
     * at the next answer with no evidence needed. Added after the answer is decided: it never turns a COMMIT (P3's "a
     * batch with a LIST for the slot's list" means a NEW staging).
     */
    private fun ridingList(out: MutableList<ByteArray>, list: Int, listed: MutableSet<Int>) {
        if (list !in 0 until CfwModes.BIND_LISTS || list in listed) return
        val f = staged[list]?.takeIf { wireMark[list] >= 0 } ?: return
        out += listMsg(f, 0, fresh = false)
        listed += list
        count("ridingList")
    }

    /** The table repair's re-sends for this batch, each journaled ([TableRepair.take]). */
    private fun takeRepairs(out: MutableList<ByteArray>, settleHere: Boolean, listed: MutableSet<Int>, setEntries: Set<Int>, clearedEntries: Set<Int>) {
        for (o in repair.take(this, settleHere, listed, setEntries, clearedEntries)) when (o) {
            is TableRepair.Owed.Clear -> { out += CfwModes.bindClear(o.entry); held.remove(o.entry); touchedSinceRead += o.entry
                if (front?.let { tapEntryNo(it) } == o.entry) tapsLive = false }
            is TableRepair.Owed.Set -> { val f = front!!; val e = entriesOf(f).first { it.entry == o.entry }
                out += f.set(e); held[e.entry] = Held(f.list, e); touchedSinceRead += e.entry }
            is TableRepair.Owed.ScrollSets -> { val f = front!!
                for (e in entriesOf(f).filter { !it.isTap }) { out += f.set(e); held[e.entry] = Held(f.list, e); touchedSinceRead += e.entry } }
        }
    }

    private fun tapEntryNo(f: BoundList): Int? = tapEntry(f)?.entry ?: f.entries.firstOrNull { it.isTap }?.entry

    private fun listMsg(f: BoundList, shift: Int, fresh: Boolean): ByteArray {
        if (fresh || wireMark[f.list] < 0) { markCounter = (markCounter + 1) and 0xFF; wireMark[f.list] = markCounter }
        staged[f.list] = f
        touchedSinceRead += "list${f.list}"
        return CfwModes.bindList(f.list, f.items4, f.count, shift, f.pitch, wireMark[f.list])
    }

    private fun snapback(st: Slot, n: Int, row: Int, nowMs: Long, frame: Boolean, denied: Set<Int>, wrapped: Boolean = false) {
        // AF.2: a return names the row — with an adopted report before its batch too (AL.6, R4: nothing was discarded)
        if (st.moved == REBIND && st.handled.none { !it.adopted && !it.tap }) { count(REBIND); return }
        if (st.handled.isNotEmpty() && st.handled.all { it.tap }) return            // a tap's DISCARD: its tapdrop or act says it
        val first = st.handled.firstOrNull { !it.adopted && !it.tap }
        val cause = first?.cause ?: st.moved ?: st.discard ?: if (n in denied) "focus" else "lost-report"
        val h = first ?: st.handled.firstOrNull()
        count("snapback.$cause")
        note("snapback", "cause=$cause S=${h?.s ?: "-"} list=${st.list} cursor=${h?.cursor ?: "-"} row=$row " +
            "mark=${front?.let { markOf(it.list) } ?: "-"} markNow=${if (st.list >= 0) markOf(st.list) else "-"} " +
            "ms=${h?.let { nowMs - it.atMs } ?: "-"} frame=${if (frame) "yes" else "no"}" + if (wrapped) " wrap=play" else "")
        if (wrapped) count("wrapPlay")
    }

    /** The lone SETTLE (P7), Q after the last report with every 115 answered, alone in its batch. */
    fun loneSettle(row: Int, nowMs: Long): List<ByteArray> {
        observe(row)
        if (!settleDue(nowMs)) return emptyList()
        burstOpen = false
        // decisions AA.2: a COMMIT of this burst whose outcome-3 body has not come — its batch may have been refused on
        // RIGHT at a tail sub that sends no notify (the riding LIST): one read now, not the minute's
        if (unconfirmed.isNotEmpty()) {
            count("commitUnconfirmed")
            readTelemetry("no outcome-3 body for COMMIT ${unconfirmed.joinToString { "slot ${it.first} S ${it.second}" }} by the burst's SETTLE")
        }
        val out = ArrayList<ByteArray>()
        // the 5th kind's re-sends ride the batch that carries the next SETTLE
        val listed = HashSet<Int>()
        takeRepairs(out, true, listed, HashSet(), HashSet())
        front?.let { ridingList(out, it.list, listed) }
        out += settle(row, nowMs, lone = true)
        return out
    }

    private fun settle(row: Int, nowMs: Long, lone: Boolean = false): ByteArray {
        val f = front
        // decisions U.2: S_heard + 1 less 0x4000 — with the wide bound, P7's S_low decided no outcome in 2,000 sequences
        // (stage DP2's fix round), so it is gone
        val m = CfwModes.motionSettle(CfwModes.MOTION_ALL, (sHeard + 1 - WIDE) and 0xFFFF, f?.list ?: CfwModes.BIND_ALL, if (f != null) row else 0, AGE_TICKS)
        // C1: the lone SETTLE carries the scroll owes it leaves open while the list is in front; the owes a SETTLE clears
        // are counted here, a carried one when its carry ends unpaid (`owesExpired`) or a late echo pays it (`lateEcho`)
        val carry = lone && f != null && owesScroll + owesAny > 0
        val cleared = owesClick + if (carry || (!lone && owesCarriedUntil != Long.MIN_VALUE)) 0 else owesScroll + owesAny
        if (cleared > 0) count("owesAtSettle", cleared)
        tapNoClick()
        // the click owes go (a click inside a rubber band is dropped, never queued: §6.1); the scroll owes go unless
        // carried (a stock message still owed may never come: the rubber band drops some); u stays until S_heard advances
        // (decisions BB: the next burst's first report charges its gap against the messages that already came)
        owesClick = 0
        if (lone) { owesCarriedUntil = if (carry) nowMs + (owesScroll + owesAny) * BAND_DRAIN_MS else Long.MIN_VALUE; carryWindowUntil = owesCarriedUntil }
        expireOwes(nowMs)
        repair.settleSent()
        discardSinceSettle.clear()
        unconfirmed.clear(); committed.clear()
        count("settles")
        return m
    }

    /** One re-send of the table repair, journaled (`repair`, §6.5). */
    internal fun repairNote(detail: String) { count("repair"); note("repair", detail) }

    /** Slots answered with a DISCARD since the last SETTLE sent: a local stop RIGHT reports after a SETTLE is the SETTLE's. */
    private val discardSinceSettle = HashSet<Int>()
    /** COMMITs sent since the last SETTLE, (slot, S), whose outcome-3 body has not come from RIGHT (AA.2): cleared by that
     *  body, at each SETTLE sent and at a session start. */
    private val unconfirmed = HashSet<Pair<Int, Int>>()
    /** COMMITs sent since the last SETTLE, (slot, S): a close of one is not counted as a snap-back (AF.9). Cleared at each
     *  SETTLE sent and at a session start. */
    private val committed = HashSet<Pair<Int, Int>>()

    /**
     * One notify-114 body from RIGHT in a bound slot (the shell hands every one): a stop (outcome 1) of a local instance
     * with no DISCARD sent to its slot since the last SETTLE is the SETTLE's — `snapback cause=burst-end`; a refusal
     * (outcome 2) is a COMMIT, DISCARD or SETTLE refused — `snapback cause=refused` (C1's divergence path keyframes).
     * A body naming a slot no list is bound to is not the rules'.
     */
    fun body114(slot: Int, tag: Int, outcome: Int, reason: Int?) {
        if (!active) return
        if (outcome == DamageMsg.OUTCOME_COMMITTED) unconfirmed.remove(slot to tag)
        val st = slots[slot] ?: return
        val why = when {
            outcome == DamageMsg.OUTCOME_REFUSED -> "refused"
            // decisions AF.9: a COMMITted instance (ordinary on the lens) closed by the next fire of a fast burst is no snap-back
            outcome == 1 && (slot to tag) in committed -> { count("closedCommitted"); return }
            outcome == 1 && slot !in discardSinceSettle -> "burst-end"
            else -> return
        }
        count("snapback.$why")
        note("snapback", "cause=$why S=$tag list=${st.list} cursor=- row=$rowSeen mark=- markNow=${if (st.list >= 0) markOf(st.list) else "-"} " +
            "ms=- frame=no" + (reason?.let { " reason=$it" } ?: ""))
    }

    /** The `local` note's detail at a telemetry read (§6.5): RIGHT's fields and the session's counts. */
    fun localNote(t: Map<Int, Long>): String = "35=${t[35] ?: "-"} 36=${t[36] ?: "-"} 38=${t[38] ?: "-"} 39=${t[39] ?: "-"} " +
        "taps=${taps.word} " + counts.entries.joinToString(" ") { "${it.key}=${it.value}" }

    /** For the harnesses: every slot's A and whether it owes an answer. */
    fun slotA(n: Int): Int? = slots[n]?.a
    fun slotOwed(n: Int): Boolean = slots[n]?.owed == true
}
