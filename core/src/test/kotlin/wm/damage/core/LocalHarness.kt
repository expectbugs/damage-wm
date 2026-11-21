package wm.damage.core

import java.util.PriorityQueue
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Zl
import wm.damage.core.local.BoundList
import wm.damage.core.local.LocalRules
import wm.damage.core.local.Taps
import wm.damage.core.motion.Motion
import wm.damage.core.motion.MotionProgram
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.TextureCache

/**
 * Build C2's sequence fuzz harness (`FIRMWARE.md` §6.10; spec §5): the phone's rules ([LocalRules]) and a batch composer
 * driving the simulator's two lenses through its hook model, over a modeled link — LEFT handles every gesture one
 * inter-lens answer before RIGHT (a dropped answer: LEFT alone); RIGHT's 115 precedes its gesture's stock message on one
 * FIFO uplink (§6.1); the rubber band drops or delays a scroll message and drops a click; a 115 may be lost or
 * duplicated; the image lane is a FIFO downlink to both lenses, where a mode-27 sub-message may not land, or be refused
 * on one lens (the word held). Time is the lenses' OS tick, read as one millisecond on the phone's clock (I).
 *
 * The list: [n0] rows of distinct solid pictures (identity k is level 2 + k) shown in one band, a scroll program per
 * direction (DRAW of the item at c', kind 1) and a press program (the row drawn inverse, then HOLD). The phone's
 * compositor stands in for `Compositor`: a region diff driven by what changed in the truth since the last batch, its
 * model with each planned COMMIT's END applied first — the END's areas diffed too — and, with a COMMIT planned, every
 * region meeting its areas (by the region's real rect) held back to the next batch (P3/P10 as decisions AA.1 rule it).
 *
 * Every hook a seeded case needs is here; the invariants are [LocalSequenceFuzzTest]'s.
 */
class LocalHarness(val seed: Long, taps0: Taps = Taps.REPORT, val n0: Int = 8, val freshAtStarts: Boolean = true,
                   /** B083 (2d): the scroll program's steps and the entries' period — the fuzz's short motion by default, his
                    *  phone's (T 6 at P 46, 0.81) in the skewed rig. */
                   val tScroll: Int = T_SCROLL, val period: Int = PERIOD,
                   /** C3 DM-0: a bit-11 build (0xdff) at stage 5 with the list bound as a ring list (LIST-RING): a wrap notch
                    *  fires on the lens like any other — the simulator's own model of a ring fire (`BindSim`), no copy here. */
                   val ring: Boolean = false) {
    val rnd = java.util.Random(seed)
    val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = if (ring) 0xDFF else 0x5FF; it.clockIsOsTick = true }
    /** The flags each start arms: stage 4's, and EFFECTS at stage 5 (a ring list needs it, `FIRMWARE.md` §7.8). */
    private val armed = ARMED or (if (ring) DamageMsg.FLAG_EFFECTS else 0)
    var now = 1000L
    val notes = ArrayList<Pair<String, String>>()
    var trace: ((String) -> Unit)? = null
    val rules = LocalRules({ k, d -> notes += k to d; trace?.invoke("  [$now] note $k: $d") }, { why -> keyframe(why) },
        { why -> telemetryAsked++; trace?.invoke("  [$now] telemetry read asked: $why"); at(now + READ_MS) { readTelemetry() } })
    /** Telemetry reads the rules asked for (decisions AA.2). */
    var telemetryAsked = 0

    // ------------------------------------------------------------------------------------------- geometry
    companion object {
        val BAND = Rect(64, 120, 32, 8)
        val BADGE = Rect(88, 120, 8, 8)
        val CLOCK = Rect(400, 20, 16, 4)
        const val IDS = 12
        const val MODAL = 1
        const val BADGE_LEVEL = 14
        const val PERIOD = 16
        /** Decisions AZ.4: the wrap's PLAY slot (`MainStaging.WRAP_SLOT`). */
        const val WRAP_SLOT = 1
        /** Build K1-4a: the notice's timer (`PopupPrograms`) over the band: the box is the band, its top rule 2 rows down,
         *  the bar's run 8 in from each end; flat (both lenses alike). */
        val NOTICE_GEO = wm.damage.core.motion.PopupPrograms.Geo(BAND, 2, 8, 0)
        /** The shell's start allowance for the wrap's motion (`Shell.WRAP_START_MS`). */
        const val WRAP_START = 400L
        const val T_SCROLL = 3
        const val T_PRESS = 2
        const val W = 640; const val H = 480
        fun level(id: Int) = 2 + id
        const val ARMED = (1 shl 2) or (1 shl 3) or (1 shl 4)
        const val BAND_MS = 200L
        /** A telemetry read's round trip, fixed (no draw from the sequence's random stream). */
        const val READ_MS = 60L
        /** A released frame's way to the lens at the most: a pump and the longest downlink (S33's residual window). */
        const val WRAP_FRAME_MS = 60L
    }

    // ------------------------------------------------------------------------------------------- the cache
    private var at4 = 64
    private fun put(bytes: ByteArray): Int { val o = at4; at4 += (bytes.size + 3) / 4 + 1; return o }
    val recs = IntArray(IDS)
    val recBytes = arrayOfNulls<ByteArray>(IDS)
    val recSources = ArrayList<Motion.Source.Record>()
    val tableAt = IntArray(2)
    var tableSlot = 0
    lateinit var scroll: MotionProgram
    lateinit var press: MotionProgram
    private val cacheWrites = ArrayList<ByteArray>()

    private fun layout() {
        for (id in 0 until IDS) {
            val b = TextureCache.Image2(BAND.w, BAND.h, ByteArray(BAND.w * BAND.h) { level(id).toByte() }).encode()
            recBytes[id] = b; recs[id] = put(b); recSources += Motion.Source.record(recs[id], b)
            cacheWrites += write19(recs[id], b)
        }
        tableAt[0] = put(ByteArray(2 * IDS)); tableAt[1] = put(ByteArray(2 * IDS))
        val sAt = at4
        scroll = Motion.program(sAt, recSources, bit10 = true) {
            track(BAND, Motion.Backdrop.Level(0)) {
                draw(recSources[0], BAND.x, BAND.y, n = tScroll, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
            }
        }
        put(scroll.bytes)
        val pAt = at4
        press = Motion.program(pAt, recSources, bit10 = true) {
            track(BAND, Motion.Backdrop.Level(15)) {
                draw(recSources[0], BAND.x, BAND.y, n = T_PRESS, opt = 0x20, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
            }
        }
        put(press.bytes)
        val s2At = at4
        scroll2 = Motion.program(s2At, recSources, bit10 = true) {
            track(BAND, Motion.Backdrop.Level(0)) {
                draw(recSources[0], BAND.x, BAND.y, n = tScroll, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
            }
        }
        put(scroll2.bytes)
        cacheWrites += write19(scroll2.at, scroll2.bytes)
        cacheWrites += write19(scroll.at, scroll.bytes)
        cacheWrites += write19(press.at, press.bytes)
        notice = wm.damage.core.motion.PopupPrograms.timer(at4, NOTICE_GEO)
        put(notice.bytes)
        cacheWrites += write19(notice.at, notice.bytes)
        noticeCap4 = at4; at4 += (notice.captureBytes + 3) / 4 + 1          // the capture's room, written by each PLAY
    }

    // ------------------------------------------------------------------------------------------- build K1-4a: the notice
    /** When set (half the random sequences, by the seed), a cover is the notice box with its focus timer on the engine,
     *  as the shell builds it: the box drawn as the timer's END (MODAL, the bar's run lit), the timer's PLAY in slot 2 in
     *  the batch that draws it and again in every gesture's batch while covered (the restart), closing whatever slot-0 or
     *  slot-1 instance its areas meet (§5.5); a batch drawing in the band while the phone holds it open CLOSEs it first
     *  (the transport's rule), as the uncover's does — the list returning to the front. */
    var noticeTimer = false
    lateinit var notice: MotionProgram
    private var noticeCap4 = 0
    private var noticeOwed = false
    var noticeOpen = false
    /** The tag of the last notice PLAY sent ([wrapTagNow]'s twin): only a notify naming it clears [noticeOpen]. */
    private var noticeTagNow = -1
    var noticePlays = 0
    var noticeCuts = 0

    private fun write19(off4: Int, b: ByteArray) = byteArrayOf(19) + u16(off4) + u16(b.size) + b
    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    // ------------------------------------------------------------------------------------------- the phone
    var ids = IntArray(n0) { it }
    var row = 0
    var covered = false
    var window = false
    var badge = false
    var clock = 0
    var markBase = 1
    lateinit var bound: BoundList
    /** Every staging the phone sent a LIST for, by its wire mark (for invariant (1)). */
    val stagings = HashMap<Int, IntArray>()

    fun boundFor(ids: IntArray, mark: Int, at4: Int): BoundList {
        val items = IntArray(ids.size) { recs[ids[it]] }
        val p = listOf(CfwModes.BindParam(CfwModes.KIND_ITEM, 0), CfwModes.BindParam(0, 0), CfwModes.BindParam(0, 0), CfwModes.BindParam(0, 0))
        return BoundList(0, 0, ids.size, BAND.h, mark and 0xFF, at4, items, listOf(
            BoundList.Entry(0, CfwModes.EV_UPROLL, +1, period, sp().at, p, sp()),
            BoundList.Entry(1, CfwModes.EV_DOWNROLL, -1, period, sp().at, p, sp()),
            BoundList.Entry(2, CfwModes.EV_TAP, 0, period, press.at, p, press)), ring = ring)
    }

    private fun sp() = if (scrollAlt) scroll2 else scroll

    private fun itemTable(ids: IntArray) = ByteArray(2 * ids.size).also { b -> ids.forEachIndexed { i, id -> b[2 * i] = (recs[id] and 0xFF).toByte(); b[2 * i + 1] = (recs[id] shr 8).toByte() } }

    /** The phone's truth (levels, one per pixel; the list is flat, so both lenses alike). */
    fun truth(): ByteArray {
        val t = ByteArray(W * H)
        fun fill(r: Rect, l: Int) { for (y in r.y until r.bottom) for (x in r.x until r.right) t[y * W + x] = l.toByte() }
        fill(BAND, if (covered || window) MODAL else level(ids[row]))
        if (covered && noticeTimer) fill(NOTICE_GEO.rule, wm.damage.core.motion.PopupPrograms.BAR_LEVEL)
        if (badge && !covered && !window) fill(BADGE, BADGE_LEVEL)
        fill(CLOCK, 1 + clock % 13)
        return t
    }

    var model = ByteArray(W * H)
    private var lastTruth = ByteArray(W * H)

    // ------------------------------------------------------------------------------------------- what is recorded
    class Act(val gesture: Int, val id: Int, val viaRule: Boolean, val rightId: Int?, val taps: Taps)
    val acts = ArrayList<Act>()
    /** Gesture → the number of times it moved the phone's row (invariant (8)). */
    val moves = HashMap<Int, Int>()
    /** Per gesture: S taken on each lens, RIGHT's cursor and mark at its fire. */
    class Fire(val g: Int, val tap: Boolean, var sL: Int? = null, var sR: Int? = null, var cursorR: Int? = null, var markR: Int? = null,
               var shownR: Int? = null,
               /** B083 v2 (item 4): how many phone messages had reset RIGHT's cursor before its fire
                *  ([LocalHarness.rightResets]), and the fire's time on RIGHT. */
               var resetsAtFireR: Int = -1, var fireTimeR: Long = -1,
               /** Part BH (invariant (1)'s declared class): at RIGHT's fire, whether the newest message RIGHT was handed is a
                *  batch refused at the harness's induced LIST after its SETs with no present on RIGHT since; the row RIGHT's
                *  SHADOW shows in the band; the row RIGHT's cursor names after the fire. */
               var awaitsPresentR: Boolean = false, var shadowR: Int? = null, var cursorIdR: Int? = null)

    /** The row identity RIGHT's panel shows in the band now (a press drawn inverse reads as its row; -1 anything else). */
    fun shownOnRight(): Int {
        val p = sim.right.panel
        val x = BAND.x + 2; val y = BAND.y + 2
        val b = p[y * 320 + x / 2].toInt() and 0xFF
        val l = if (x % 2 == 0) b shr 4 else b and 0x0F
        val pressed = sim.motionForTest(Arm.RIGHT).slots[0]?.let { it.prog4 == press.at } == true
        val id = if (pressed) 15 - l - 2 else l - 2
        return if (id in 0 until IDS) id else -1
    }
    val fires = HashMap<Int, Fire>()
    val bySR = HashMap<Int, Int>()

    // ---- B083 fix stage, item 1: the ring's count (the fuzz's invariant (9): the row at rest against it)
    /** One entry of the ring's count in the order things happened: a notch counted ([step] ±1), or a re-stage ([to]: the
     *  row mapped by identity, as the phone maps its own). */
    private class RingEv(val t: Long, val n: Long, val step: Int = 0, val to: IntArray? = null, val g: Int = -1)
    private val ringLog = ArrayList<RingEv>()
    private var ringN = 0L
    /** Per scroll gesture: its step (+1 id 4, −1 id 5). */
    private val ringStep = HashMap<Int, Int>()
    /** Per scroll gesture the phone has heard of: what it heard first ("115" or "stock"; "start": nothing before a session
     *  start). */
    private val ringHow = HashMap<Int, String>()
    /** Per scroll gesture RIGHT handled: the time and order of RIGHT's fire and whether Main was in front on the phone then
     *  (for a gesture RIGHT took that the phone never hears of). */
    private val ringAtFire = HashMap<Int, Triple<Long, Long, Boolean>>()
    /** Gestures left out of the count by a session start (in flight there), for the fuzz's count. */
    val ringLostAtStart = ArrayList<Int>()
    private fun mainInFront() = !covered && !window

    /** The phone heard of scroll gesture [g] ([how]): the first time decides whether it counts. */
    private fun ringHeard(g: Int, how: String) {
        val step = ringStep[g] ?: return
        if (g in ringHow) return
        ringHow[g] = how
        if (mainInFront()) ringLog += RingEv(now, ringN++, step, g = g)
        trace?.invoke("[$now] ring: g$g heard first by its $how, ${if (mainInFront()) "counts $step" else "counts nothing (Main not in front)"}")
    }

    /**
     * The row the ring's net count puts the list at (invariant (9)), the rule as built:
     *  - a scroll gesture RIGHT took with an S counts its step (wrapping at the list's ends, as the phone applies an edge)
     *    when Main was in front (no cover, no window) as the phone first heard of it — its 115 or its stock message,
     *    whichever came first; one the phone never heard of (its 115 lost, its message dropped by the band) counts when
     *    Main was in front at RIGHT's fire;
     *  - a scroll gesture RIGHT did not take (no entry there: a start race, a lost SET, a cover) counts its step when its
     *    stock message reached the phone with Main in front;
     *  - a band-dropped message of an untaken notch, a LEFT-only gesture and a tap count nothing;
     *  - a gesture whose 115 was lost is heard by the adoption whose gap carries it, when RIGHT drew it and no phone message
     *    reset RIGHT's cursor between its fire and the adopted one (B083 v2: the phone then holds its step);
     *  - a session start: a gesture RIGHT took that the phone had not heard of by then counts nothing (in flight at the
     *    restart, or lost before it);
     *  - a re-stage maps the ring's row by identity, as the phone maps its own (the identity's new index, 0 when it left).
     */
    fun ringRow(adjust: List<Triple<Long, Long, Int>> = emptyList()): Int {
        val evs = ArrayList(ringEvents())
        // B083 v2 (item 4): the exact declarations' adjustments, each (time, order, rows) where its gesture's move happened
        for ((t, n, d) in adjust) evs += RingEv(t, n, d)
        evs.sortWith(compareBy<RingEv>({ it.t }, { it.n }))
        var ids = IntArray(n0) { it }
        var r = 0
        for (e in evs) {
            if (e.to != null) { r = e.to.indexOf(ids[r]).let { if (it < 0) 0 else it }; ids = e.to }
            else r = (r + e.step).mod(ids.size)
        }
        return r
    }
    /** The ring's count, one entry per counted gesture and per re-stage. */
    private fun ringEvents(): List<RingEv> {
        val evs = ArrayList(ringLog)
        for ((g, at) in ringAtFire) if (g !in ringHow && fires[g]?.sR != null && at.third) evs += RingEv(at.first, at.second, ringStep[g]!!, g = g)
        return evs
    }
    /** Where the ring's count took [g]'s step (time, order), or null when it counts nothing for it. */
    fun ringCountedAt(g: Int): Pair<Long, Long>? = ringEvents().firstOrNull { it.g == g && it.to == null }?.let { it.t to it.n }
    /** A scroll gesture's step (+1 id 4, −1 id 5); null for a tap. */
    fun stepOf(g: Int): Int? = ringStep[g]
    /** B083 v2 (item 4): RIGHT's outcome for each gesture's 115, sent or lost on the way; the gestures whose 115 was lost. */
    val rightOutcome = HashMap<Int, Int>()
    val lost115 = HashSet<Int>()
    /** Phone messages that changed RIGHT's cursor so far: a lens-only draw before one (a lost report's) is gone from it. */
    var rightResets = 0
    /** When the phone decided each message that reset RIGHT's cursor: what moved the phone's row after it is unknown to RIGHT. */
    val rightResetAt = ArrayList<Long>()
    val rightResetLanded = ArrayList<Long>()
    // ---- B083 v3 (decisions BO.3): a front's own notches, the fuzz's invariant (10)
    /** Bumped at every change of what is in front (a cover, a window, Main again): a message is a front's only while that
     *  front stands. */
    private var frontGen = 0
    /** Per scroll gesture RIGHT handled with a window or a cover in front and no scroll entry of its direction on RIGHT
     *  (that front's CLEAR has landed there, so no 115): the front's kind and generation at RIGHT's fire. */
    private val frontNotchAt = HashMap<Int, Pair<String, Int>>()
    /** Per front kind ("window", "cover" — both take the scroll in this model): its own notches whose stock message
     *  reached the phone with it still in front, and those the phone applied; the gestures it did not apply. */
    val frontMade = java.util.TreeMap<String, Int>()
    val frontApplied = java.util.TreeMap<String, Int>()
    val frontMissed = ArrayList<String>()
    /** When each lone SETTLE was sent (J1 and J2 read their late echoes against them). */
    val loneSettles = ArrayList<Long>()

    // ---- decisions BQ, BR: (10)'s declared shape — a front's notch paid by an owe of a burst that joined no carry, the
    // burst open when the front took the front (BQ) or opened while the front stood (BR)
    /** The open burst as the phone's rules hold it: when its first report was handled, and S_heard before it. */
    private var burstFrom = Long.MIN_VALUE
    private var burstS0 = 0
    /** Bursts opened so far: a burst opened while a front stood is one numbered after that front's taking (a window a
     *  tap's act opens is taken in the same instant its report opened or joined a burst, so time cannot tell them). */
    private var burstSeq = 0
    /** Session starts so far (a start ends every burst). */
    private var starts = 0
    /** A burst, for (10)'s declaration: its opening, its S_heard before, and the lone SETTLEs and starts sent by then (the
     *  same burst while none has come since). */
    private class InBurst(val from: Long, val s0: Int, val settles: Int, val starts: Int)
    /** Per front generation: the bursts opened by its taking, and the burst open then if it had joined no carry. */
    private val frontTakenSeq = HashMap<Int, Int>()
    private val frontInBurst = HashMap<Int, InBurst>()
    /** Scroll gestures RIGHT handled whose stock message the band dropped. */
    private val bandDropped = HashSet<Int>()
    /** (10)'s declared notches: gesture → (front kind, class); and the gestures already used to explain one (a dropped
     *  echo or a lost report explains one swallowed notch at most). */
    val frontDeclared = LinkedHashMap<Int, Pair<String, String>>()
    private val explaining = HashSet<Int>()
    private fun noteFrontInBurst() {
        frontTakenSeq[frontGen] = burstSeq
        if (rules.burstOpen && rules.owesCarriedUntilMs == Long.MIN_VALUE) frontInBurst[frontGen] = InBurst(burstFrom, burstS0, loneSettles.size, starts)
    }

    /**
     * (10)'s declaration for front notch [g] the phone did not apply ([os], [oa]: the scroll owes and the owes of either
     * kind before its message) — P5's stated residual reaching a front, in its two forms: the burst open at the swallow
     * joined no carry and either (BQ) was open when the front took the front and is still open, or (BR) opened while the
     * front stood (a 115 for a notch RIGHT took before the front's CLEAR landed — a carry never runs under a front); and
     * the message paid an owe of that burst's own — of its direction, where a report of that direction heard in the burst
     * had its echo dropped by the band; or of either kind, where a report inside the burst's S span was lost (the gap's
     * owe). Each dropped echo or lost report explains one notch at most. A declared notch lowers its front's expected count
     * by that notch; (10) still judges every other notch in that front. Null when the predicate does not hold: the notch
     * is then an undeclared (10) failure (a burst that joined a carry, finding 1's shape, is never declared).
     */
    private fun declared10(g: Int, gen: Int, os: Int, oa: Int): String? {
        val taken = frontTakenSeq[gen] ?: return null
        val same = frontInBurst[gen]?.takeIf { rules.burstOpen && loneSettles.size == it.settles && starts == it.starts }
        val ib = same ?: (if (rules.burstOpen && burstSeq > taken) InBurst(burstFrom, burstS0, loneSettles.size, starts) else null) ?: return null
        val under = if (same == null) " (the burst opened under the front)" else ""
        val step = ringStep[g] ?: return null
        if (rules.owesScroll < os) {
            val src = reportHeardAt.entries.filter { (gk, t) -> t >= ib.from && t <= now && ringStep[gk] == step && gk in bandDropped && gk !in explaining }
                .minByOrNull { it.value }?.key ?: return null
            explaining += src
            return "(10) a front's notch paid by its burst's own owe$under: a notch's echo the band dropped"
        }
        if (rules.owesAny < oa) {
            val src = lost115.filter { gk -> gk !in explaining && fires[gk]?.sR?.let { s -> LocalRules.after(s, ib.s0) && !LocalRules.after(s, rules.sHeard) } == true }
                .minOrNull() ?: return null
            explaining += src
            return "(10) a front's notch paid by its burst's own owe$under: a lost report's gap owe"
        }
        return null
    }

    /** Refusals of the phone's messages (mode, reason, arm), those the harness induced kept apart. */
    val refusals = ArrayList<String>()
    val induced = ArrayList<String>()
    /** RIGHT's 114 bodies: (slot, tag, outcome) in order. */
    val bodies114 = ArrayList<Triple<Int, Int, Int>>()
    /** Verdicts by gesture (the liveness checks read them). */
    val verdicts = ArrayList<Pair<Int, LocalRules.Verdict>>()
    /** COMMITs sent: S. And for each, whether each lens's local was still open when it landed. */
    val commitsSent = ArrayList<Int>()
    val stoppedBeforeCommit = ArrayList<String>()
    /** Locals drawn under a cover (a lost cover CLEAR, S17's count): fires on either lens while covered. */
    var localsUnderCover = 0
    var lossyMessages = 0
    var sent = 0

    // ------------------------------------------------------------------------------------------- the event loop
    private var evSeq = 0L
    private inner class Ev(val t: Long, val run: () -> Unit) { val n = evSeq++ }
    private val q = PriorityQueue<Ev>(compareBy<Ev>({ it.t }, { it.n }))
    fun at(t: Long, f: () -> Unit) { q += Ev(t, f) }

    /** Runs every event due up to [until] (wakes every 8 ticks while anything is due). */
    fun runUntil(until: Long) {
        while (true) {
            val nextWake = ((now / 8) + 1) * 8
            val e = q.peek()
            if (e != null && e.t <= until && e.t <= nextWake) { q.poll(); now = maxOf(now, e.t); e.run(); drainSent(); sampler?.invoke(); continue }
            if (nextWake > until) { now = until; break }
            now = nextWake
            wakeBoth()
            sampler?.invoke()
        }
    }

    private fun wakeBoth() {
        if (sim.motionForTest(Arm.LEFT).anyDrawing || sim.motionForTest(Arm.RIGHT).anyDrawing) what = "engine step"
        var any = false
        for (a in Arm.entries) if (sim.motionForTest(a).anyDrawing) { sim.conformanceWake(a, now); any = true }
        drainSent()
        if (any) traceOutcome()
    }

    /** Scripted cases run with fixed delays (uplink 20, downlink 25, the inter-lens answer 8, a band 200); random ones
     *  draw them. */
    var fixed = false
    private fun upLat() = if (fixed) 20 else 10 + rnd.nextInt(30)
    private fun downLat() = if (fixed) 25 else 15 + rnd.nextInt(35)
    private fun lensGap() = if (fixed) 8 else 4 + rnd.nextInt(8)
    private fun bandDelay() = if (fixed) 200L else 150L + rnd.nextInt(100)
    /** The next [loseNext115] 115s RIGHT sends are lost. */
    var loseNext115 = 0
    /** Called once when the next 115 reaches the phone, before the phone handles it (a cover or a draw landing between a
     *  report and its answer). */
    var beforeReport: (() -> Unit)? = null
    /** Called once after the phone handled the next 115 and before its pump (a cover opening between an adoption and its
     *  answer). */
    var afterReport: (() -> Unit)? = null
    /** The phone's pump runs this long after an event (a busy link holds a batch back). */
    var pumpDelay = 3L

    // ---- the uplink: RIGHT's notifies and stock messages, FIFO
    private var lastUp = 0L
    var lose115 = 0.0; var dup115 = 0.0
    var bandDropScroll = 0.0; var bandDelayScroll = 0.0; var bandDropClick = 0.0
    var dropAnswer = 0.0
    /** The last scroll message's arrival: the rubber band holds scroll messages back in ONE FIFO (§6.1), so a delayed one
     *  delays every later one and none overtakes another; a 115 or a click, put in the hook's own turn, can pass it. */
    private var lastScrollUp = 0L
    fun up(sendAt: Long, extra: Long = 0, scroll: Boolean = false, f: () -> Unit) {
        var arrive = maxOf(lastUp + 1, sendAt + upLat() + extra)
        if (scroll) { arrive = maxOf(arrive, lastScrollUp + 1); lastScrollUp = arrive }
        if (extra == 0L) lastUp = maxOf(lastUp, arrive)
        at(arrive, f)
    }

    private fun drainSent() {
        sim.conformanceSent(Arm.LEFT)
        for ((field, body) in sim.conformanceSent(Arm.RIGHT)) {
            val m = Pb.fields(body).associate { it.field to (it.varint ?: 0L) }
            if (field == 114) {
                val slot = m[2]?.toInt() ?: -1; val tag = if (m.containsKey(3)) m[3]!!.toInt() else -1; val outcome = m[4]?.toInt() ?: -1
                bodies114 += Triple(slot, tag, outcome)
                // decisions BS.2: each flag (and the wrap's release) by the notify of the instance it stands for — the tag
                // of the last PLAY, compared when the notify reaches the phone ([wrapTagNow])
                if (slot == WRAP_SLOT && outcome in 0..1) up(now) {
                    if (tag == wrapTagNow) {
                        wrapOpen = false
                        // B083 (2c), the shell's `localBodyRead`: the wrap drew its last step — the cells held behind it go
                        // now when no later motion holds them
                        if (outcome == 0 && carryHold && deferAreas.isNotEmpty() && deferUntil <= wrapUntil) { deferAreas = emptyList(); pumpSoon() }
                    }
                }
                if (slot == wm.damage.core.motion.PopupPrograms.SLOT && outcome in 0..1) up(now) { if (tag == noticeTagNow) noticeOpen = false }
                up(now) { rules.body114(slot, tag, outcome, m[10]?.toInt()) }
                // a refused COMMIT names its S as the tag: the mirror took it, so the transport calls it a divergence
                // and the shell keyframes (`CfwTransportBase.onMotionNotify`); a DISCARD's or SETTLE's names none
                if (outcome == 2 && m.containsKey(3) && slot in 0..3) up(now) { keyframe("a COMMIT refused on RIGHT") }
            }
            if (field == 115) {
                val a = DamageMsg.Applied(m)
                val g = a.s?.let { bySR[it] } ?: -1
                if (g >= 0) a.outcome?.let { rightOutcome[g] = it }
                if (loseNext115 > 0) { loseNext115--; if (g >= 0) lost115 += g; continue }
                if (rnd.nextDouble() < lose115) { if (g >= 0) lost115 += g; continue }
                up(now) { phoneReport(a, g) }
                if (rnd.nextDouble() < dup115) up(now) { phoneReport(a, g) }
            }
        }
    }

    // ---- gestures
    private var gestureSeq = 0
    var nextStock: ((LocalRules.Stock) -> Boolean)? = null

    /** A ring notch (+1: id 4, the next row; −1: id 5) or a ring tap. [answer]: RIGHT gets the inter-lens answer;
     *  [band]: "drop" / "delay" / null for the stock message; [holdWordOn]: a lens whose engine word is held at the fire. */
    fun gesture(kind: Int, answer: Boolean = rnd.nextDouble() >= dropAnswer, band: String? = null, holdWordOn: Arm? = null, dev: Int = 4): Int {
        val g = ++gestureSeq
        val tap = kind == 0
        val id = when (kind) { 0 -> CfwModes.EV_TAP; 1 -> CfwModes.EV_UPROLL; else -> CfwModes.EV_DOWNROLL }
        val f = Fire(g, tap); fires[g] = f
        if (!tap) ringStep[g] = if (kind == 1) 1 else -1
        // a scripted [band] is taken as given; otherwise the band is RIGHT's own state, decided at RIGHT's fire below
        val bandNow = band
        fun input(arm: Arm) {
            val b = sim.bindForTest(arm)
            val before = b.seq
            if (holdWordOn == arm) sim.motionForTest(arm).holdWordForTest(true)
            if (arm == Arm.RIGHT) { f.shownR = shownOnRight(); f.resetsAtFireR = rightResets; f.fireTimeR = now }
            if (arm == Arm.RIGHT) { f.awaitsPresentR = listRefusedPresentR == sim.right.presentSeq; f.shadowR = bandIdIn(sim.right.shadow) }
            sim.conformanceInput(arm, dev, id.toLong(), 0, now)
            if (holdWordOn == arm) sim.motionForTest(arm).holdWordForTest(false)
            sim.conformanceStockCalls(arm)
            val s = if (b.seq != before) b.seq else null
            if (arm == Arm.LEFT) f.sL = s else { f.sR = s; s?.let { bySR[it] = g }; f.cursorR = b.lists[0].cursor; f.markR = b.lists[0].mark }
            if (arm == Arm.RIGHT) f.cursorIdR = ids.getOrNull(b.lists[0].cursor)
            if (arm == Arm.LEFT && (covered || window) && sim.motionForTest(arm).slots.any { it?.local == true && it.tag == s }) localsUnderCover++
        }
        trace?.invoke("[$now] gesture g$g kind $kind answer $answer band $bandNow hold $holdWordOn")
        if (covered && noticeTimer) { noticeOwed = true; pumpSoon() }           // K1-4a: the input restarts the notice's timer
        // B083 (2d): with a skew, the ring's event reaches each lens on its own — the later one by 0..[skewMax] ms, drawn per
        // notch from the rig's own generator; without one, LEFT now and RIGHT one inter-lens answer later (§6.1), as before
        val (dL, dR) = skewPair() ?: (0L to lensGap().toLong())
        if (dL == 0L) { what = "fire LEFT"; input(Arm.LEFT); drainSentLeftOnly() }
        else at(now + dL) { what = "fire LEFT"; input(Arm.LEFT); drainSentLeftOnly() }
        if (!answer) return g
        val tR = now + dR
        at(tR) {
            what = "fire RIGHT"
            // stock's own message, after the 115 (one FIFO, §6.1): a scroll message after the exchange, a click in the turn
            val st = if (tap) LocalRules.Stock.CLICK else if (id == CfwModes.EV_UPROLL) LocalRules.Stock.NEXT else LocalRules.Stock.PREV
            val extra: Long? = when {
                bandNow == "drop" -> null
                bandNow == "delay" -> bandDelay()
                bandNow?.startsWith("delay:") == true -> bandNow.removePrefix("delay:").toLong()
                bandNow != null -> 0L
                else -> band(tap)
            }
            if (!tap) ringAtFire[g] = Triple(now, ringN++, mainInFront())
            // B083 fix stage (item 2): a tap RIGHT takes while the last wrap's held wait — or the frame it released — can
            // still stand between the lens's cursor and its panel: S33's shape, the declared residual
            else if (wrapPlays > 0 && now < wrapUntil + WRAP_FRAME_MS) tapNearWrap += g
            // B083 v3 (invariant (10)): a notch made with a window or a cover in front whose CLEAR has landed on RIGHT (RIGHT
            // holds no scroll entry of its direction, so it sends no 115) is that front's own
            if (!tap && !mainInFront()) {
                val b = sim.bindForTest(Arm.RIGHT)
                if (!b.live || b.entries.none { it != null && it.eventId == id }) frontNotchAt[g] = (if (window) "window" else "cover") to frontGen
            }
            input(Arm.RIGHT)
            drainSent()
            if (extra != null) up(now, extra, scroll = !tap) { phoneStock(st, g) } else if (!tap) bandDropped += g
        }
        return g
    }

    /** RIGHT's rubber band (§6.1), modeled: a notch that finds no band running sends its scroll message at once and starts
     *  a band of [BAND_MS]; one inside a band either sends none ([bandDropScroll]) or queues it to a band's end, one per
     *  band end, each starting the next band; a single tap inside a band sends no click. Returns the message's extra delay,
     *  or null for none. (I: the band's length and which of drop and queue happens are not measured.) */
    private var bandUntil = 0L
    /** The band's length ([BAND_MS]; the fifth base: 225 ms, his queued messages' spacing). */
    var bandMs = BAND_MS
    /** The fifth base (his session's profile): a scroll message with no band running dropped with this probability (0: the
     *  four bases' band exactly as before, no draw). */
    var dropScrollIdle = 0.0
    fun band(tap: Boolean): Long? {
        if (tap) return if (now < bandUntil) null else 0L
        if (now >= bandUntil) {
            if (dropScrollIdle > 0 && rnd.nextDouble() < dropScrollIdle) return null
            bandUntil = now + bandMs; return 0L
        }
        if (rnd.nextDouble() < bandDropScroll) return null
        val at = bandUntil
        bandUntil = at + bandMs
        return at - now
    }

    private fun drainSentLeftOnly() { sim.conformanceSent(Arm.LEFT) }

    // ---- the phone's side of the link
    private fun move(g: Int, newRow: Int) { if (newRow != row) { moves[g] = (moves[g] ?: 0) + 1; row = newRow } }

    // ---- B083 v2, item 4: what moved the phone's row, for (9)'s exact declarations
    /** One verdict the phone applied: its gesture, from its 115 or its stock message, the row before and after, whether a
     *  carry held owes (C1), the report's outcome, its place in the ring's order, the list's length then, and — for an
     *  adoption or a tap's correction — the gap's notches RIGHT drew and still held at this report's fire (no phone message
     *  reset its cursor between), which the move carries. */
    class Move(val g: Int, val t: Long, val n: Long, val by115: Boolean, val v: LocalRules.Verdict, val before: Int, val after: Int,
               val carried: Boolean, val outcome: Int?, val size: Int, val carries: List<Int> = emptyList())
    val phoneMoves = ArrayList<Move>()
    /** The notches in an adoption's (or a tap correction's) gap that RIGHT drew and still held at its fire — the move
     *  carries them; the ring hears them there (B083 v2), as the verdict comes, before anything it opens. */
    private fun carries(g: Int, v: LocalRules.Verdict, a: DamageMsg.Applied, heardBefore: Int): List<Int> {
        val out = ArrayList<Int>()
        val s = a.s ?: return out
        if (v !is LocalRules.Verdict.Adopt && v !is LocalRules.Verdict.Act) return out
        var k = (heardBefore + 1) and 0xFFFF
        while (k != s && LocalRules.after(s, k)) {
            bySR[k]?.let { gk -> if (rightOutcome[gk] == DamageMsg.APPLIED_DREW && ringStep[gk] != null && fires[gk]?.resetsAtFireR == fires[g]?.resetsAtFireR) out += gk }
            k = (k + 1) and 0xFFFF
        }
        for (gk in out) ringHeard(gk, "carrying adoption")
        return out
    }
    private fun record(g: Int, by115: Boolean, v: LocalRules.Verdict, before: Int, carried: Boolean, a: DamageMsg.Applied?, carries: List<Int> = emptyList()) {
        phoneMoves += Move(g, now, ringN++, by115, v, before, row, carried, a?.outcome, ids.size, carries)
    }
    private fun carriedNow() = !rules.burstOpen && rules.owesCarriedUntilMs != Long.MIN_VALUE

    private fun phoneReport(a: DamageMsg.Applied, g: Int) {
        beforeReport?.let { beforeReport = null; it() }
        if (g >= 0 && g !in reportHeardAt) reportHeardAt[g] = now
        if (g >= 0) ringHeard(g, "115")
        val before = row; val carried = carriedNow(); val heardBefore = rules.sHeard
        val opening = !rules.burstOpen
        val v = rules.report(a, row, now)
        if (v is LocalRules.Verdict.Adopt && a.wrapped) wrapAdopted += g        // C3 DM-0: a ring fire across the wrap, adopted
        if (opening && rules.burstOpen) { burstFrom = now; burstS0 = heardBefore; burstSeq++ }
        trace?.invoke("[$now] phone 115 g$g ${a.describe()} row $row → $v")
        verdicts += g to v
        val carries = carries(g, v, a, heardBefore)
        apply(v, g, a)
        record(g, true, v, before, carried, a, carries)
        afterReport?.let { afterReport = null; it() }
        pumpSoon()
    }

    private fun phoneStock(st: LocalRules.Stock, g: Int) {
        if (!st.click) ringHeard(g, "stock")
        val before = row; val carried = carriedNow()
        val os = rules.owesScroll; val oa = rules.owesAny
        val v = rules.stock(st, row, now)
        trace?.invoke("[$now] phone stock g$g $st row $row → $v (carried $carried)")
        verdicts += g to v
        // invariant (10): a front's own notch, its message reaching the phone while that front still stands, is applied
        // there — or carries decisions BQ's declaration
        frontNotchAt[g]?.let { (kind, gen) ->
            if (!st.click && gen == frontGen && !mainInFront()) {
                frontMade[kind] = (frontMade[kind] ?: 0) + 1
                if (v is LocalRules.Verdict.Apply) frontApplied[kind] = (frontApplied[kind] ?: 0) + 1
                else declared10(g, gen, os, oa)?.let { frontDeclared[g] = kind to it } ?: run {
                    val ib = frontInBurst[gen]
                    frontMissed += "g$g ($kind, $v at $now; front taken in an uncarried burst: ${ib != null}" +
                        (if (ib != null) ", same burst open: ${rules.burstOpen && loneSettles.size == ib.settles && starts == ib.starts}" else "") +
                        ", paid: ${if (rules.owesScroll < os) "a scroll owe" else if (rules.owesAny < oa) "an owe of either kind" else "none"})"
                }
            }
        }
        apply(v, g, null)
        record(g, false, v, before, carried, null)
        pumpSoon()
    }

    private fun apply(v: LocalRules.Verdict, g: Int, a: DamageMsg.Applied?) {
        when (v) {
            is LocalRules.Verdict.Adopt -> move(g, v.row)
            is LocalRules.Verdict.Apply -> when (v.type) {
                EvenHubMsg.EV_CLICK -> if (!covered && !window) act(g, row, viaRule = false)
                EvenHubMsg.EV_SCROLL_BOTTOM -> if (!covered && !window) move(g, (row + 1) % ids.size)
                EvenHubMsg.EV_SCROLL_TOP -> if (!covered && !window) move(g, (row - 1 + ids.size) % ids.size)
            }
            is LocalRules.Verdict.Act -> { move(g, v.row); act(g, v.row, viaRule = true) }
            LocalRules.Verdict.Nothing -> {}
        }
    }

    var actOpensWindow = 0.0
    private fun act(g: Int, r: Int, viaRule: Boolean) {
        val f = fires[g]
        val rightId = f?.shownR
        acts += Act(g, ids[r], viaRule, rightId, rules.taps)
        if (rnd.nextDouble() < actOpensWindow) openWindow()
    }

    // ---- covers, windows, re-stages, switches (the shell's own changes, each in the batch that draws it)
    fun cover() { if (covered || window) return; covered = true; frontGen++; noteFrontInBurst(); rules.front(null, row); if (noticeTimer) noticeOwed = true; pumpSoon() }
    fun uncover() { if (!covered) return; covered = false; frontGen++; if (!window) rules.front(bound, row); pumpSoon() }
    fun openWindow() {
        if (window || covered) return
        // decisions AW.3, as the shell does it: a window over the list while a COMMITted instance still draws in the slot —
        // a CLOSE of the slot goes first, alone, ahead of the window's frame
        val inst = sim.motionForTest(Arm.RIGHT).slots[0]
        if (inst != null && inst.drawing && inst.local && inst.tag in commitsSent) { emit(listOf(CfwModes.motionClose(0)), emptyList()); windowCuts++ }
        window = true; frontGen++; noteFrontInBurst(); rules.front(null, row); pumpSoon()
    }
    /** Windows that cut a COMMITted motion (AW.3), for the fuzz's counted shapes. */
    var windowCuts = 0
    /** Decisions AZ.4: the wrap's PLAYs sent; whether the phone holds one open (until RIGHT's body for it or a keyframe);
     *  the batches that closed one to draw in its areas. */
    var wrapPlays = 0
    var wrapOpen = false
    /** Decisions BS.2 (the 0.83 integration): the tag of the last wrap PLAY sent. An "instance open" flag is cleared only by
     *  a notify naming the instance it stands for, read when the notify reaches the phone: a restart's own PLAY closes the
     *  instance before it (and an older one may draw its last step), and that notify lands after the new PLAY went — read by
     *  slot alone it cleared the flag of the instance still drawing, so the next draw in the band carried no CLOSE (14 of
     *  8,000 fuzz sequences, every one with the notice's timer restarted). The product needs no such flag: its transport
     *  closes by the open instances of its own mirror (`CfwTransportBase.withMotionCloses`, 3871–3895:
     *  `mirrorSim.motionWindows(arm)` at 3873), and the shell's wrap release reads the tag (`Shell.localBodyRead`, 5710:
     *  `ev.tag == wrapTagOut`). */
    private var wrapTagNow = -1
    var wrapCuts = 0
    /** When the phone first handled each gesture's report. */
    val reportHeardAt = HashMap<Int, Long>()
    /** C3 DM-0: the gestures whose ring fire wrapped (field 18) and whose report the phone adopted. */
    val wrapAdopted = ArrayList<Int>()
    fun back() { if (!window) return; window = false; frontGen++; if (!covered) rules.front(bound, row); pumpSoon() }
    fun toggleBadge() { badge = !badge; pumpSoon() }
    fun tick() { clock++; pumpSoon() }
    fun switchTaps(t: Taps) { rules.taps(t); pumpSoon() }

    /** A re-stage: identities move (a row inserted at the top or one removed), the item table to fresh space, a LIST with
     *  a new mark and the cursor's shift (P9: only while no burst is open). */
    fun restage(): Boolean {
        return restage(if (ids.size < IDS && (ids.size <= 4 || rnd.nextBoolean())) "top+" else if (row < ids.size - 1 && rnd.nextBoolean()) "bottom-" else "top-")
    }

    /** A re-stage of a given shape: "top+" a row inserted at the top, "top-" the top row removed, "bottom-" the last one. */
    fun restage(shape: String): Boolean {
        if (rules.burstOpen || covered || window) return false
        if (shape != "top+" && ids.size <= 2) return false
        val oldId = ids[row]
        val newIds = when (shape) {
            "top+" -> intArrayOf((0 until IDS).first { it !in ids }) + ids
            "bottom-" -> if (row == ids.size - 1) return false else ids.copyOfRange(0, ids.size - 1)
            else -> ids.copyOfRange(1, ids.size)
        }
        val newRow = newIds.indexOf(oldId).let { if (it < 0) 0 else it }
        val shift = newRow - row
        tableSlot = 1 - tableSlot
        sendDown(write19(tableAt[tableSlot], itemTable(newIds)))
        ringLog += RingEv(now, ringN++, to = newIds.copyOf())
        ids = newIds
        row = newRow
        markBase++
        bound = boundFor(ids, markBase, tableAt[tableSlot])
        rules.front(bound, row, shift)
        pumpSoon()
        return true
    }

    /** The scroll program rewritten into fresh space and SET again (P9: "a program through fresh space and a SET"); the
     *  staging is the same, so no LIST. */
    lateinit var scroll2: MotionProgram
    var scrollAlt = false
    fun reprogram(): Boolean {
        if (rules.burstOpen || covered || window) return false
        scrollAlt = !scrollAlt
        val p = if (scrollAlt) scroll2 else scroll
        sendDown(write19(p.at, p.bytes))
        bound = boundFor(ids, markBase, tableAt[tableSlot])
        rules.front(bound, row)
        pumpSoon()
        return true
    }

    // ---- the downlink
    /** The mode-27 subs of [kind] ("set", "clear") in the next batch that carries one do not land on [arms] (the first of
     *  them, or every one with [lose27All]). "list" (C3 X3M0, decisions BB (7)): the batch's first LIST is refused on
     *  [arms] — the subs before it run, the LIST and every sub after it do not, no present, the lens's record names
     *  mode 27 (§4) — the shape a LIST refused on one lens for a shared reason leaves; a LIST lost with the subs after it
     *  landing is a state no batch leaves. */
    var lose27: Pair<String, Set<Arm>>? = null
    var lose27All = false
    /** The lenses whose copy of the batch being sent carries the refused LIST ([lose27] "list"). */
    private val listRefusedOn = HashSet<Arm>()
    /** The next batch carrying a mode 27 is refused on [arm] (its word held at the sub). */
    var refuse27On: Arm? = null
    /** The next batch carrying any mode-27 or mode-26 sub 3..5 is refused on [arm] at its first such sub. */
    var refuseTailOn: Arm? = null
    /** The next batch carrying a COMMIT, DISCARD or SETTLE does not land on [loseAnswerOn] (LEFT among them: the write is
     *  lost and its ack fails; RIGHT alone: the inter-lens forward is lost, which nobody hears). */
    var loseAnswerOn: Set<Arm>? = null

    private var lastDown = 0L
    private val lastDownArm = HashMap<Arm, Long>()
    /** B083-real: added to every phone message's way down (both lenses, in order) — his 0.81 journal puts the wrap's start
     *  a median 332 ms after the edge report (`C2-decisions.md` BI, measured), where the harness's link takes 25 ms. */
    var downExtraMs = 0L
    private fun sendDown(image: ByteArray, perArm: Map<Arm, ByteArray?> = emptyMap(), inducedOn: Arm? = null, taggedOn: Set<Arm> = emptySet()) {
        engine.wrote(image)                             // decisions BS.3: the transport's one write path (`writeImage`)
        val arrive = maxOf(lastDown + 1, now + downLat() + downExtraMs)
        lastDown = arrive
        sent++
        val batch = sent
        // A8: the batch that carries the latest repair's whole table (its CLEAR 0xFF) is the repair's own
        if (repairOwed && subsOf(image).any { it.contentEquals(CLEAR_ALL) }) { repairOwed = false; repairBatch = batch }
        val decided = now
        val skew = skewPair()
        if (skew == null) at(arrive) {
            for (arm in listOf(Arm.LEFT, Arm.RIGHT)) {
                val img = if (perArm.containsKey(arm)) perArm[arm] else image
                if (img == null) continue
                deliver(arm, img, inducedOn == arm, decided, batch, arm in taggedOn)
            }
        } else for ((arm, d) in listOf(Arm.LEFT to skew.first, Arm.RIGHT to skew.second)) {
            // B083 (2d): each lens gets the message on its own, the later one by the drawn skew; each lens's stream stays
            // in order
            val img = if (perArm.containsKey(arm)) perArm[arm] else image
            if (img == null) continue
            val t = maxOf((lastDownArm[arm] ?: 0L) + 1, arrive + d)
            lastDownArm[arm] = t
            at(t) { what = "$arm gets ${subsOf(img).joinToString(" ") { kindOf(it) }}"; deliver(arm, img, inducedOn == arm, decided, batch, arm in taggedOn) }
        }
    }

    // ---- B083 (2d): the skewed two-lens rig
    /** The later lens's lag, 0..[skewMax] ms, drawn per notch and per message from [skewRnd]; [leftLate] says which lens is
     *  later. 0 (the default): no skew — the fuzz's link exactly as before. */
    var skewMax = 0
    var leftLate = true
    val skewRnd = java.util.Random(seed * 7 + 13)
    private fun skewPair(): Pair<Long, Long>? {
        if (skewMax <= 0) return null
        val d = skewRnd.nextInt(skewMax + 1).toLong()
        return if (leftLate) d to 0L else 0L to d
    }
    /** What ran last (for what ends a stretch of the two panels differing). */
    var what = ""
    /** Called after every event and every engine wake, with the time ([sampleAt]). */
    var sampler: (() -> Unit)? = null
    fun kindOf(sub: ByteArray): String = when (sub[0].toInt()) {
        26 -> when (sub[1].toInt()) { 0 -> "PLAY"; 2 -> "CLOSE"; 3 -> "COMMIT"; 4 -> "DISCARD"; 5 -> "SETTLE"; else -> "26/${sub[1]}" }
        27 -> when (sub[1].toInt()) { 0 -> "SET"; 1 -> "CLEAR"; 2 -> "LIST"; 4 -> "LIST-RING"; else -> "27/${sub[1]}" }
        6 -> "keyframe"; 19 -> "write"
        else -> "draw"
    }
    /** The row identity [arm]'s panel shows in the band: the band's one level when it is uniform (−1 otherwise, or a level
     *  that names no row: mid-step or the modal). */
    fun shownOn(arm: Arm): Int = bandIdIn((if (arm == Arm.LEFT) sim.left else sim.right).panel)
    /** The row identity a packed 4bpp buffer (a panel or a shadow) shows in the band, as [shownOn]. */
    fun bandIdIn(p: ByteArray): Int {
        var l0 = -1
        for (y in BAND.y until BAND.bottom) for (x in BAND.x until BAND.right) {
            val b = p[y * 320 + x / 2].toInt() and 0xFF
            val l = if (x % 2 == 0) b shr 4 else b and 0x0F
            if (l0 < 0) l0 = l else if (l != l0) return -1
        }
        val id = l0 - 2
        return if (id in 0 until IDS) id else -1
    }
    /** Whether the two panels differ over the band. */
    fun bandsDiffer(): Boolean {
        val a = sim.left.panel; val b = sim.right.panel
        for (y in BAND.y until BAND.bottom) for (x in BAND.x / 2 until BAND.right / 2) if (a[y * 320 + x] != b[y * 320 + x]) return true
        return false
    }

    /** [tagged]: a refusal of [img] is the harness's own (the refused LIST of [lose27] "list") though no word is held. */
    private fun deliver(arm: Arm, img: ByteArray, induce: Boolean, decided: Long = now, batch: Int = -1, tagged: Boolean = false) {
        // invariant (7)'s probe: a COMMIT landing finds its own local still open on RIGHT
        for (s in commitsIn(img)) {
            val g = bySR[s]
            val fr = g?.let { fires[it] }
            val tag = if (arm == Arm.RIGHT) s else fr?.sL
            if (tag != null && fr != null && fr.sL != null && sim.motionForTest(arm).slots.none { it?.local == true && it.tag == tag })
                stoppedBeforeCommit += "$arm S $s"
        }
        val lens = if (arm == Arm.LEFT) sim.left else sim.right
        // the sim's refusal record is sticky until the next refusal (§4) and telemetry reads it: cleared only to see whether
        // THIS message was refused, and put back as it was when it was not
        val kept = listOf(lens.refSeen, lens.refMode, lens.refReason, lens.refSeq)
        lens.refSeen = false
        if (induce) sim.motionForTest(arm).holdWordForTest(true)
        val cursor0 = if (arm == Arm.RIGHT) sim.bindForTest(arm).lists[0].cursor else 0
        sim.conformanceMessage(arm, img, now)
        if (induce) sim.motionForTest(arm).holdWordForTest(false)
        if (arm == Arm.RIGHT && sim.bindForTest(arm).lists[0].cursor != cursor0) { rightResets++; rightResetAt += decided; rightResetLanded += now }
        val refusedNow = lens.refSeen
        if (!refusedNow) { lens.refSeen = kept[0] as Boolean; lens.refMode = kept[1] as Int; lens.refReason = kept[2] as Int; lens.refSeq = kept[3] as Long }
        trace?.invoke("[$now] $arm gets ${subsOf(img).joinToString(" ") { it.take(3).joinToString("") { b -> "%02x".format(b) } }}" +
            (if (refusedNow) " REFUSED mode ${lens.refMode and 0x7F} reason ${lens.refReason}" else "") + if (induce || tagged) " (induced)" else "")
        if (refusedNow) {
            val r = "$arm mode ${lens.refMode and 0x7F} reason ${lens.refReason}"
            if (induce || tagged) induced += r else refusals += r
            if (arm == Arm.RIGHT) rightRefusals += RightRefusal(lens.refMode and 0x7F, lens.refReason, lens.refSeq, induce || tagged, batch)
            // the image lane writes LEFT (`CfwTransportBase.writeImage`), so the flush's ack is LEFT's [I]: a refusal there
            // fails the flush — the shell rolls it back and, for a flush that carried the rules' messages, sends the table
            if (arm == Arm.LEFT) up(now) { failed(img, "refused ($r)") }
            refusalStreak = if (arm == Arm.LEFT) refusalStreak + 1 else refusalStreak
            maxRefusalStreak = maxOf(maxRefusalStreak, refusalStreak)
        } else if (arm == Arm.LEFT) refusalStreak = 0
        // part BH: RIGHT's present count while the newest message it was handed is a batch refused at the induced LIST after
        // its SETs (null otherwise; a cache write is no batch)
        if (arm == Arm.RIGHT && (img[0].toInt() and 0x7F) !in setOf(19, 25))
            listRefusedPresentR = if (tagged && refusedNow && (lens.refMode and 0x7F) == 27 && setsBeforeRefusedList(img)) lens.presentSeq else null
        drainSent()
        traceOutcome()
    }
    /** Part BH: RIGHT's present count when the newest message it was handed was refused at the induced LIST after a SET. */
    private var listRefusedPresentR: Long? = null
    /** [img] carries a SET ahead of its refused LIST (the one [lose27] "list" gave count 0). */
    private fun setsBeforeRefusedList(img: ByteArray): Boolean {
        val subs = subsOf(img)
        val k = subs.indexOfFirst { it[0].toInt() == 27 && (it[1].toInt() == 2 || it[1].toInt() == CfwModes.BIND_LIST_RING) && it.size > 6 && it[5].toInt() == 0 && it[6].toInt() == 0 }
        return k > 0 && subs.take(k).any { it[0].toInt() == 27 && it[1].toInt() == 0 }
    }

    /** A flush whose ack failed (`Shell.completeFlush`): rolled back — what it drew is unknown and painted again from the
     *  truth — and, when it carried the rules' messages, the whole table again (`LocalRules.tableAgain`). */
    private fun failed(img: ByteArray, why: String) {
        rollbackAll = true
        if (subsOf(img).any { (it[0].toInt() == 27) || (it[0].toInt() == 26 && it.size > 1 && it[1].toInt() in 3..5) })
            rules.tableAgain("a flush carrying local-input messages failed: $why")
        pumpSoon()
    }
    /** A batch, or the COMMIT in it, did not land on RIGHT alone this sequence (the fix round's row A for the lead). */
    var commitMissedRight = false
    var refusalStreak = 0
    var maxRefusalStreak = 0
    private var rollbackAll = false

    /** The phone's keyframe (a divergence): the truth whole to both lenses, every instance closed. */
    fun keyframe(why: String) {
        trace?.invoke("[$now] keyframe: $why")
        wrapOpen = false
        noticeOpen = false                              // the keyframe closes every instance; the box shows END until its focus
        deferAreas = emptyList()
        keyframes++
        val t = truth()
        val packed = ByteArray(320 * 480)
        for (y in 0 until H) for (x in 0 until W) { val l = t[y * W + x].toInt(); val i = y * 320 + x / 2
            packed[i] = (if (x % 2 == 0) (packed[i].toInt() and 0x0F) or (l shl 4) else (packed[i].toInt() and 0xF0) or l).toByte() }
        model = t.copyOf(); lastTruth = t.copyOf(); held = emptySet()
        sendDown(CfwModes.keyframe(Zl.encodeCfw(packed)))
    }
    var keyframes = 0

    /** A running digest of what each lens shows in the band and the clock and what its slot holds, after every message
     *  and engine step — two runs whose digests agree showed the same pictures in the same order. */
    val outcome = java.util.zip.CRC32()
    private fun traceOutcome() {
        for (a in Arm.entries) {
            val p = (if (a == Arm.LEFT) sim.left else sim.right).panel
            for (r in listOf(BAND, CLOCK)) for (y in r.y until r.bottom) outcome.update(p, y * 320 + r.x / 2, r.w / 2)
            for (i in sim.motionForTest(a).slots) outcome.update(if (i == null) 0 else (i.tag * 31 + i.d * 7 + (if (i.local) 1 else 0) + (if (i.drawing) 2 else 0)))
        }
    }

    private fun commitsIn(img: ByteArray): List<Int> = subsOf(img).filter { it.size >= 5 && it[0].toInt() == 26 && it[1].toInt() == 3 }
        .map { (it[3].toInt() and 0xFF) or ((it[4].toInt() and 0xFF) shl 8) }

    fun subsOf(img: ByteArray): List<ByteArray> {
        if (img.isEmpty() || img[0].toInt() != 8) return listOf(img)
        val out = ArrayList<ByteArray>()
        var i = 2
        repeat(img[1].toInt() and 0xFF) {
            val n = (img[i].toInt() and 0xFF) or ((img[i + 1].toInt() and 0xFF) shl 8)
            out += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n
        }
        return out
    }

    // ---- the phone's batch composer (the stand-in for `Compositor`)
    private var pumpAt = Long.MAX_VALUE
    fun pumpSoon() { val t = now + pumpDelay; if (t < pumpAt) { pumpAt = t; at(t) { pumpAt = Long.MAX_VALUE; pump() } } }

    /** The regions, in paint order; BADGE lies inside BAND. */
    private val regions = listOf(BAND, BADGE, CLOCK)

    private fun levelAt(t: ByteArray, r: Rect) = t[r.y * W + r.x].toInt()

    private fun differs(a: ByteArray, b: ByteArray, r: Rect): Boolean {
        for (y in r.y until r.bottom) for (x in r.x until r.right) if (a[y * W + x] != b[y * W + x]) return true
        return false
    }

    private fun paint(m: ByteArray, r: Rect, l: Int) { for (y in r.y until r.bottom) for (x in r.x until r.right) m[y * W + x] = l.toByte() }

    /** END of a planned COMMIT on the model: the scroll program's last step — backdrop 0, then the item at the cursor. */
    private fun paintEnd(m: ByteArray, c: LocalRules.Commit) {
        paint(m, BAND, 0)
        val rec = c.p[0]
        val id = recs.indexOf(rec)
        paint(m, BAND, level(id))
    }

    /** The drawing subs that make [m] equal [t] over [dirty] (in place on [m]), with their real rects. */
    private fun diff(m: ByteArray, t: ByteArray, dirty: Set<Rect>): List<Pair<ByteArray, Rect>> {
        val out = ArrayList<Pair<ByteArray, Rect>>()
        for (r in regions) {
            if (r !in dirty || !differs(m, t, r)) continue
            if (r == BAND) {
                val l = if (covered || window) MODAL else level(ids[row])
                out += CfwModes.fill(BAND, l) to BAND; paint(m, BAND, l)
                if (levelAt(t, BADGE) != l) { out += CfwModes.fill(BADGE, levelAt(t, BADGE)) to BADGE; paint(m, BADGE, levelAt(t, BADGE)) }
            } else { val l = levelAt(t, r); out += CfwModes.fill(r, l) to r; paint(m, r, l) }
        }
        return out
    }

    var lastBatch: List<ByteArray> = emptyList()
    /** Regions a riding COMMIT held back that still differ from the truth (decisions AA.1): diffed at the next batch. */
    private var held: Set<Rect> = emptySet()
    /** Batches that held a region back, and batches that carried a COMMIT beside a drawing sub meeting its areas (the
     *  defect AA.1's hold prevents: END written over that frame). */
    var heldBatches = 0
    val commitOverDraw = ArrayList<Int>()
    /** Batches that carried a COMMIT while a region was held back — each one a DISCARD under the rule before AA.1. */
    var heldCommits = 0
    /** Each batch that carried a COMMIT: its S and the rects of the drawing subs beside it. */
    val commitBatches = ArrayList<Pair<Int, List<Rect>>>()

    fun pump() {
        if (!rules.active) return
        val t = truth()
        if (rollbackAll) { rollbackAll = false; java.util.Arrays.fill(model, 0x7F.toByte()); java.util.Arrays.fill(lastTruth, 0x7F.toByte()) }
        val changed = regions.filter { differs(t, lastTruth, it) }.toMutableSet()
        changed += held
        val noticeNow = noticeOwed && covered && noticeTimer
        if (rules.settleDue(now) && changed.isEmpty() && !rules.owed && !noticeNow) {
            val subs = rules.loneSettle(row, now)
            if (subs.isNotEmpty()) { loneSettles += now; emit(subs, emptyList()) }
            return
        }
        if (changed.isEmpty() && !rules.owed && !noticeNow) { rules.settleAt()?.let { s -> if (s > now) at(s) { pump() } }; return }
        val plan = rules.plan(row)
        val m2 = model.copyOf()
        val dirty = HashSet(changed)
        for (c in plan) { paintEnd(m2, c); dirty += BAND; dirty += BADGE }
        // decisions AZ.4 (the shell's `mainWrapPlan`): an edge at an end with the row now the other end — the scroll's PLAY
        // in slot 1 from the end to the row past it, END (the wrapped row's record) into the model, its areas held. None on
        // a ring list (C3 X2M0: a ring staging has no wrap pan; an edge there goes as the phone's frames)
        val wrap = rules.wrapPlan(row)?.takeIf { !ring }
        val wrapPlay = wrap?.let { w ->
            val prog = w.list.entries.first { it.step == w.step }.program!!
            val p = intArrayOf(recs[ids[w.cPrime.mod(ids.size)]], 0, 0, 0)
            paint(m2, BAND, 0); paint(m2, BAND, level(ids[w.cPrime.mod(ids.size)])); dirty += BAND; dirty += BADGE
            Motion.play(prog, WRAP_SLOT, period, 0xB000 + (wrapPlays and 0xFFF), 0, p)
        }
        val wrapAreas = wrap?.list?.areas ?: emptyList()
        // decisions AA.1: with a COMMIT planned, a region meeting its areas is not drawn in this batch; what still differs
        // from the truth once END is in the model goes in the next one. B083 (2c): as the shell's compositor does it (AF.7,
        // `Compositor.deferUntilMs`), the held regions wait for the motion's end on the glasses — a COMMIT's (its report's
        // arrival plus T × P and a period) or the wrap's (its steps and the shell's start allowance) — across batches,
        // not only in the batch that carries the answer; a keyframe ends the wait
        if (deferAreas.isNotEmpty() && now >= deferUntil) deferAreas = emptyList()
        val until = (plan.map { it.motionEndsMs } + listOfNotNull(wrap?.let { now + (tScroll + 1L) * period * 1000 / 1024 + WRAP_START })).maxOrNull()
        if (until != null && until > now) { deferUntil = if (deferAreas.isEmpty()) until else maxOf(deferUntil, until); deferAreas = (deferAreas + plan.flatMap { it.areas } + wrapAreas).distinct() }
        val hold = if (plan.isEmpty() && wrap == null && deferAreas.isEmpty()) emptySet() else regions.filter { r -> plan.any { c -> LocalRules.meets(listOf(r), listOf(r), c.areas) } ||
            LocalRules.meets(listOf(r), listOf(r), wrapAreas) || (carryHold && LocalRules.meets(listOf(r), listOf(r), deferAreas)) }.toSet()
        var subs = diff(m2, t, dirty - hold)
        // the transport's rule (`CfwTransportBase.withMotionCloses`): a batch drawing inside the wrap's instance while the
        // phone holds it open CLOSEs it first
        if (wrapOpen && subs.any { it.second.overlaps(BAND) }) { subs = listOf(CfwModes.motionClose(WRAP_SLOT) to BAND) + subs; wrapOpen = false; wrapCuts++ }
        if (noticeOpen && subs.any { it.second.overlaps(BAND) }) {
            subs = listOf(CfwModes.motionClose(wm.damage.core.motion.PopupPrograms.SLOT) to BAND) + subs; noticeOpen = false; noticeCuts++
        }
        if (!covered || !noticeTimer) noticeOwed = false
        // K1-4a: the notice's PLAY after every drawing sub (the band holds the box's END when it is captured); never in a
        // batch that holds the band back
        val noticePlay = if (noticeOwed && BAND !in (dirty intersect hold)) {
            noticeOwed = false
            Motion.play(notice, wm.damage.core.motion.PopupPrograms.SLOT, PERIOD, 0xC000 + (noticePlays and 0xFFF), noticeCap4, IntArray(4))
        } else null
        held = (dirty intersect hold).filter { differs(m2, t, it) }.toSet()
        if (held.isNotEmpty()) heldBatches++
        val tail = listOfNotNull(wrapPlay) + rules.tail(row, now, emptySet(), frame = subs.isNotEmpty(), wrapped = wrapPlay != null) +
            listOfNotNull(noticePlay)
        if (wrapPlay != null) { wrapOpen = true; wrapTagNow = 0xB000 + (wrapPlays and 0xFFF); wrapPlays++; wrapUntil = deferUntil }
        if (noticePlay != null) { noticeOpen = true; noticeTagNow = 0xC000 + (noticePlays and 0xFFF); noticePlays++ }
        for (c in tail) if (c[0].toInt() == 26 && c[1].toInt() == 3) {
            commitBatches += ((c[3].toInt() and 0xFF) or ((c[4].toInt() and 0xFF) shl 8)) to subs.map { it.second }
            if (held.isNotEmpty()) heldCommits++
        }
        // (read by plain overlap, not by `LocalRules.meets`, so a seeded mutant of that function cannot hide it)
        if (tail.any { it[0].toInt() == 26 && it[1].toInt() == 3 } && plan.any { c -> c.areas.any { (al, ar) -> subs.any { it.second.overlaps(al) || it.second.overlaps(ar) } } })
            commitOverDraw += now.toInt()
        model = m2
        lastTruth = t
        // stagings by wire mark, for invariant (1)
        for (s in tail) if (s[0].toInt() == 27 && (s[1].toInt() == 2 || s[1].toInt() == CfwModes.BIND_LIST_RING)) stagings[s[11].toInt() and 0xFF] = ids.copyOf()
        emit(tail, subs.map { it.first })
        if (held.isNotEmpty()) { if (carryHold && deferAreas.isNotEmpty() && deferUntil > now) at(deferUntil) { pump() } else pumpSoon() }
        rules.settleAt()?.let { s -> if (s > now) at(s) { pump() } }
    }

    /** B083 (2c): the areas whose drawing waits for a motion's end on the glasses, and that end (the shell's deferral). */
    private var deferAreas: List<Pair<Rect, Rect>> = emptyList()
    private var deferUntil = 0L
    /** The end of the wait the last wrap PLAY was given (the shell's `wrapHeldUntil`). */
    private var wrapUntil = 0L
    /** Taps RIGHT took inside the last wrap's held wait or within [WRAP_FRAME_MS] after it (S33's residual, item 2). */
    val tapNearWrap = HashSet<Int>()
    /** The hold carried across batches (the shell's rule, the default); false: held in the answer's batch alone (the
     *  harness before B083 — the skewed rig's 0.82 rule set). */
    var carryHold = true

    private fun emit(tail: List<ByteArray>, draw: List<ByteArray>) {
        val all = LocalRules.batchOrder(draw, tail)
        if (all.isEmpty()) return
        lastBatch = all
        for (s in tail) if (s[0].toInt() == 26 && s[1].toInt() == 3) commitsSent += (s[3].toInt() and 0xFF) or ((s[4].toInt() and 0xFF) shl 8)
        fun image(subs: List<ByteArray>): ByteArray? = when {
            subs.isEmpty() -> null
            subs.size == 1 && (subs[0][0].toInt() == 26 || subs[0][0].toInt() == 27) -> subs[0]
            else -> CfwModes.batch(subs, engine = true, bind = true)
        }
        val per = HashMap<Arm, ByteArray?>()
        val l27 = lose27
        if (l27 != null) {
            val code = when (l27.first) { "set" -> 0; "clear" -> 1; else -> 2 }
            // DM-0: "list" names a LIST of either form (a ring list's is LIST-RING, sub 4)
            val idx = all.indices.filter { all[it][0].toInt() == 27 && (all[it][1].toInt() == code || (code == 2 && all[it][1].toInt() == CfwModes.BIND_LIST_RING)) }.let { if (lose27All) it else it.take(1) }
            if (idx.isNotEmpty()) {
                lose27 = null; lossyMessages++
                if (code == 2) {
                    // the refusal is the simulator's own: that lens's copy carries the LIST with count 0 (values, 12), so it
                    // ends the batch there with no present and records it; kept with the induced refusals (A8 judges them)
                    val k = idx.first()
                    val refused = all.toMutableList().also { it[k] = all[k].copyOf().also { b -> b[5] = 0; b[6] = 0 } }
                    for (a in l27.second) { per[a] = image(refused); listRefusedOn += a }
                    // a COMMIT behind it does not run on RIGHT alone: row A, as for [refuse27On]'s refusal at the same place
                    if (l27.second == setOf(Arm.RIGHT) && all.drop(k + 1).any { it[0].toInt() == 26 && it[1].toInt() == 3 }) commitMissedRight = true
                } else for (a in l27.second) per[a] = image(all.filterIndexed { i, _ -> i !in idx })
            }
        }
        var inducedOn: Arm? = null
        val r27 = refuse27On
        if (r27 != null && tail.any { it[0].toInt() == 27 }) {
            refuse27On = null; inducedOn = r27; lossyMessages++
            // decisions BC: a COMMIT behind the mode 27 RIGHT refuses does not run there (the refused sub ends the batch)
            // — row A of decisions W, as for a lost answer
            val i27 = tail.indexOfFirst { it[0].toInt() == 27 }
            if (r27 == Arm.RIGHT && tail.drop(i27 + 1).any { it[0].toInt() == 26 && it[1].toInt() == 3 }) commitMissedRight = true
        }
        val rt = refuseTailOn
        if (rt != null && tail.isNotEmpty()) { refuseTailOn = null; inducedOn = rt }
        // the fix round's row for the lead: a COMMIT that does not land on RIGHT alone (its batch refused there, or the
        // forward to RIGHT lost) — nothing reports it; counted apart by the fuzz, never by a seeded case
        val commits = tail.any { it[0].toInt() == 26 && it[1].toInt() == 3 }
        // (a COMMIT behind a sub refused on RIGHT: its record names 27 at the next read and the phone keyframes — ruled)
        val la = loseAnswerOn
        if (la != null && tail.any { it[0].toInt() == 26 && it[1].toInt() in 3..5 }) {
            if (la == setOf(Arm.RIGHT)) commitMissedRight = true             // the frame and the answer both missing there
            loseAnswerOn = null; lossyMessages++
            for (a in la) per[a] = null
            if (Arm.LEFT in la) { val img = image(all)!!; up(now + downLat()) { failed(img, "lost") } }
        }
        sendDown(image(all)!!, per, inducedOn, listRefusedOn.toSet())
        listRefusedOn.clear()
    }

    // ---- telemetry
    private var lastRef: Triple<Int, Int, Long>? = null
    private val telem = ArrayList<ByteArray>()
    /** Decisions BS.3: the transport's gate for refusal records naming mode 26 (`CfwTransportBase.bindTelemetry`). */
    val engine = wm.damage.core.local.EngineRefusals { now }
    /** Each read's decision on a record naming mode 26 or 27 (none: no entry), in order. */
    val engineDecisions = ArrayList<wm.damage.core.local.EngineRefusals.Decision>()
    /** A8 (the lead's ruling BV, FX-083): each refusal RIGHT recorded — the record's mode, reason and sequence, whether the
     *  harness induced it, and the number of the message it came on. */
    class RightRefusal(val mode: Int, val reason: Int, val seq: Long, val induced: Boolean, val batch: Int)
    val rightRefusals = ArrayList<RightRefusal>()
    /** A8: the message that carried the latest repair's whole table (−1 until it is sent), and whether one is owed. */
    private var repairBatch = -1
    private var repairOwed = false
    /** A8: each `Standing` decision on a record whose refusal the harness induced on a message that was not the latest
     *  repair's own — the declared shape. */
    val standingDeclared = ArrayList<String>()
    private val CLEAR_ALL = CfwModes.bindClear(CfwModes.BIND_ALL)
    fun readTelemetry() {
        telem.clear()
        sim.conformanceControl(Arm.RIGHT, DamageMsg.OP_TELEMETRY, 9, now)
        val f = AaFrame.Reassembler { }.offer(telem.single()) ?: return
        val t = DamageMsg.parseTelemetry(f.payload) ?: return
        val ref = t.refusal
        val rec = ref?.let { Triple(it.mode and 0x7F, it.reason, it.seq) }
        val fresh = rec != null && rec != lastRef
        lastRef = rec
        // the transport's gate decides both modes (`CfwTransportBase.bindTelemetry`): a record naming 27 is repaired only
        // when it says so (the rule against a standing refusal)
        val e = engine.read(ref, fresh)
        if (e != null) { engineDecisions += e; trace?.invoke("[$now] mode-${e.ref.mode and 0x7F} refusal record: $e") }
        if (e is wm.damage.core.local.EngineRefusals.Repair) { repairOwed = true; repairBatch = -1 }
        if (e is wm.damage.core.local.EngineRefusals.Standing) {
            val r = rightRefusals.lastOrNull { it.mode == e.ref.mode and 0x7F && it.reason == e.ref.reason && it.seq == e.ref.seq }
            if (r != null && r.induced && r.batch != repairBatch) standingDeclared += "[$now] mode ${r.mode} reason ${r.reason} on message ${r.batch} (the repair's: $repairBatch)"
        }
        val repair = e as? wm.damage.core.local.EngineRefusals.Repair
        rules.telemetry(t.boundIds, repair?.takeIf { it.ref.mode and 0x7F == 27 }?.ref,
            if (fresh && rec!!.first == 8 && rec.second == 14) ref else null, repair?.takeIf { it.ref.mode and 0x7F == 26 }, now)
        pumpSoon()
    }

    // ---- a session start (P1): keyframe, the arming, the start's bind batch
    fun start(fresh: Boolean) {
        // the ring's count (item 1): what the restart drops in flight
        for ((g, _) in ringAtFire) if (g !in ringHow && fires[g]?.sR != null) { ringHow[g] = "start"; ringLostAtStart += g
            trace?.invoke("[$now] ring: g$g not heard by the session start — counts nothing") }
        starts++
        engine.sessionStart()                           // the transport's `bindSessionStart`
        repairOwed = false; repairBatch = -1
        q.clear(); lastUp = now; lastScrollUp = now; lastDown = now; pumpAt = Long.MAX_VALUE
        deferAreas = emptyList()                        // the start's keyframe ends every motion's wait
        if (fresh) for (a in Arm.entries) { sim.conformanceLease(a, false, now); sim.conformanceLease(a, true, now) }
        for (a in Arm.entries) sim.conformanceControl(a, DamageMsg.OP_FLAGS_SET, armed, now)
        val t = truth()
        val packed = ByteArray(320 * 480)
        for (y in 0 until H) for (x in 0 until W) { val l = t[y * W + x].toInt(); val i = y * 320 + x / 2
            packed[i] = (if (x % 2 == 0) (packed[i].toInt() and 0x0F) or (l shl 4) else (packed[i].toInt() and 0xF0) or l).toByte() }
        for (a in Arm.entries) {
            sim.conformanceMessage(a, CfwModes.keyframe(Zl.encodeCfw(packed)), now)
            if (fresh) for (w in cacheWrites) sim.conformanceMessage(a, w, now)
            if (fresh || true) sim.conformanceMessage(a, write19(tableAt[tableSlot], itemTable(ids)), now)
        }
        drainSent()
        model = t.copyOf(); lastTruth = t.copyOf(); held = emptySet()
        val s0 = if (fresh) 0 else sim.bindForTest(Arm.RIGHT).seq
        rules.sessionStart(true, s0, now, if (covered || window) null else bound, row, rules.taps)
        noticeOpen = false; if (covered && noticeTimer) noticeOwed = true    // the start's keyframe closed it; the box shown again
        pump()
    }

    init {
        sim.attachListener(object : GlassFirmwareSim.SimDiag {
            override fun event(kind: String, detail: String) {}
            override fun notify(arm: Arm, packet: ByteArray) {
                val f = AaFrame.Reassembler { }.offer(packet)
                if (f != null && Pb.bytesField(f.payload, 111) != null) telem += packet
            }
            override fun panelChanged(arm: Arm) {}
        })
        layout()
        for (a in Arm.entries) { sim.conformanceLease(a, true, now); sim.conformancePage(a, true) }
        bound = boundFor(ids, markBase, tableAt[tableSlot])
        rules.sessionStart(false, 0, 0, null, 0, taps0)
        start(fresh = true)
    }

    // ------------------------------------------------------------------------------------------- at rest
    /** Lets everything outstanding land: the uplink and downlink drain, the lone SETTLE goes, the engine runs out, an idle
     *  frame (the clock) carries a SETTLE past `age`. */
    fun rest() {
        runUntil(now + 600)
        readTelemetry()                                 // the poll reads RIGHT's telemetry (once a minute on the phone)
        runUntil(now + 600)
        tick()
        runUntil(now + 600)
        tick()
        runUntil(now + 400)
    }

    /** Each lens's panel and shadow against the truth (packed 4bpp), as (arm, what, first differing pixel). */
    fun truthMismatches(): List<String> {
        val t = truth()
        val out = ArrayList<String>()
        for (a in Arm.entries) {
            val lens = if (a == Arm.LEFT) sim.left else sim.right
            for ((what, buf) in listOf("panel" to lens.panel, "shadow" to lens.shadow)) {
                var bad: String? = null
                loop@ for (y in 0 until H) for (x in 0 until W) {
                    val b = buf[y * 320 + x / 2].toInt() and 0xFF
                    val v = if (x % 2 == 0) b shr 4 else b and 0x0F
                    if (v != t[y * W + x].toInt()) { bad = "($x,$y) lens $v truth ${t[y * W + x]}"; break@loop }
                }
                if (bad != null) out += "$a $what $bad" + (if (what == "panel") " (slots ${sim.motionForTest(a).slots.mapNotNull { it?.let { i ->
                    "${i.slot}: tag ${i.tag} local ${i.local} d ${i.d}/${i.T} drawing ${i.drawing} prog ${i.prog4}" } }}; S ${sim.bindForTest(a).seq})" else "")
            }
        }
        return out
    }

    fun cursors(): Pair<Int, Int> = sim.bindForTest(Arm.LEFT).lists[0].cursor to sim.bindForTest(Arm.RIGHT).lists[0].cursor
}
