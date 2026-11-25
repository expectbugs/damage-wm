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
                   val ring: Boolean = false,
                   /** C3 FZ5: the press's steps — the fuzz's short press by default; the shell's `MainStaging.PRESS_T` (3, at
                    *  the entries' period) in the fuzz's real timings. */
                   val tPress: Int = T_PRESS,
                   /** C3 DM-1 (S6): the list switches between the two pitch forms mid-session ([pitchSwitch]) — its scroll
                    *  programs also draw a ruler into [PROBE] by a kind-2 parameter (one pixel a row, pitch −1 with the ruler
                    *  as it is, +1 with it reversed), so a lens whose pitch and entries disagree shows it (the probe shows the
                    *  row index; the truth's [probeLevel]). Plain lists only: a ring fire reads kind 2 at the virtual row,
                    *  which a static ruler cannot hold across the harness's re-stagings (Main's strip does: its margins). */
                   val pitchProbe: Boolean = false,
                   /** K1-2 stage 4b: glasses list 1 beside Main — a window's own bound list (list 1, slot 0, entries 3–5, its
                    *  tap entry report-only, pitch the row height for every effect; `FIRMWARE.md` §6.7, the K1-2 spec §7). Only on
                    *  a stage-5 build ([ring]; decisions BC O2); every list-1 choice is drawn from [l1Rnd], so a sequence without
                    *  it draws exactly what it drew before. */
                   val list1: Boolean = false,
                   /** C3 DW-2a: the switcher's wheel as glasses list 2 (decisions DP 3: a harness list — list 2, slot 0, entries 6
                    *  and 7, the tap report-only at the rules' own number for it, 0; a ring; pitch 0; `Wheel.Block.boundList`).
                    *  Only on a stage-5 build ([ring]; the wheel binds at stage 5 only); every wheel choice is drawn from
                    *  [w2Rnd], so a sequence without it draws exactly what it drew before. */
                   val wheel: Boolean = false) {
    val rnd = java.util.Random(seed)
    /** K1-2 stage 4b: list 1's own draws (its window's rows, the lane's delay, a ring staging, a Select's level). */
    val l1Rnd = java.util.Random(seed * 31 + 17)
    /** C3 DW-2a: the wheel's own draws (its rows and order, the lane's count, a commit's window, a notice's kind). */
    val w2Rnd = java.util.Random(seed * 37 + 29)
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
        /** C3 DM-1: where the pitch probe's ruler shows the cursor's row (one pixel row, four wide; outside the band). */
        val PROBE = Rect(200, 120, 4, 1)
        /** The probe's level for row [row] (1..14: never 0, the backdrop a wrong pitch leaves). */
        fun probeLevel(row: Int) = 1 + row % 14
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
        // C3 DM-1: the pitch probe's rulers — row r at level probeLevel(r), and the same reversed
        val rulers = if (!pitchProbe) emptyList() else listOf(false, true).map { rev ->
            val b = TextureCache.Image2(PROBE.w, IDS, ByteArray(PROBE.w * IDS) { i -> probeLevel(if (rev) IDS - 1 - i / PROBE.w else i / PROBE.w).toByte() }).encode()
            val o = put(b); cacheWrites += write19(o, b); Motion.Source.record(o, b)
        }
        /** The probe's track: the ruler drawn at y ← P1 (kind 2), so the probe pixel shows ruler row y0 − y. */
        fun Motion.ProgramBuilder.probe(rev: Boolean) {
            if (pitchProbe) track(PROBE, Motion.Backdrop.Level(0)) { draw(rulers[if (rev) 1 else 0], PROBE.x, 0, n = tScroll, prefs = listOf(Motion.Pref(1, Motion.Field.Y))) }
        }
        val sAt = at4
        scroll = Motion.program(sAt, recSources, bit10 = true) {
            track(BAND, Motion.Backdrop.Level(0)) {
                draw(recSources[0], BAND.x, BAND.y, n = tScroll, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
            }
            probe(false)
        }
        put(scroll.bytes)
        val pAt = at4
        press = Motion.program(pAt, recSources, bit10 = true) {
            track(BAND, Motion.Backdrop.Level(15)) {
                draw(recSources[0], BAND.x, BAND.y, n = tPress, opt = 0x20, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
            }
        }
        put(press.bytes)
        val s2At = at4
        scroll2 = Motion.program(s2At, recSources, bit10 = true) {
            track(BAND, Motion.Backdrop.Level(0)) {
                draw(recSources[0], BAND.x, BAND.y, n = tScroll, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
            }
            probe(false)
        }
        put(scroll2.bytes)
        cacheWrites += write19(scroll2.at, scroll2.bytes)
        if (pitchProbe) {
            // C3 DM-1: the other pitch form's program (+1: the ruler reversed), already in the cache as a re-make leaves it
            val bAt = at4
            scrollB = Motion.program(bAt, recSources, bit10 = true) {
                track(BAND, Motion.Backdrop.Level(0)) {
                    draw(recSources[0], BAND.x, BAND.y, n = tScroll, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
                }
                probe(true)
            }
            put(scrollB.bytes)
            cacheWrites += write19(scrollB.at, scrollB.bytes)
        }
        cacheWrites += write19(scroll.at, scroll.bytes)
        cacheWrites += write19(press.at, press.bytes)
        notice = wm.damage.core.motion.PopupPrograms.timer(at4, NOTICE_GEO)
        put(notice.bytes)
        cacheWrites += write19(notice.at, notice.bytes)
        noticeCap4 = at4; at4 += (notice.captureBytes + 3) / 4 + 1          // the capture's room, written by each PLAY
        // K1-2 stage 4b: list 1's two item tables (a new staging takes the other one), after everything else so that a
        // sequence without list 1 lays out byte for byte as before
        if (list1) { table1At[0] = put(ByteArray(2 * IDS)); table1At[1] = put(ByteArray(2 * IDS)) }
        // C3 DW-2a: the wheel's two item tables, after list 1's for the same reason
        if (wheel) { table2At[0] = put(ByteArray(2 * IDS)); table2At[1] = put(ByteArray(2 * IDS)) }
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
    /** C3 FZ5: the notice timer's period — [PERIOD] by default (the fuzz's short motion); the shell's
     *  (`PopupPrograms.TIMER_PERIOD`, 128 at every stage) in the fuzz's real timings. */
    var noticePeriod = PERIOD

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
        // C3 DM-1: with the pitch probe the scroll entries' P1 is the ruler's y by kind 2 — pitch −1 with the ruler as it is
        // (y = PROBE.y − c'), +1 with it reversed (y = PROBE.y − (IDS − 1) + c'): the probe shows row c' in either form
        val ps = if (!pitchProbe) p else listOf(p[0], CfwModes.BindParam(CfwModes.KIND_PITCH, if (pitchAlt) PROBE.y - (IDS - 1) else PROBE.y), p[2], p[3])
        val pitch = if (!pitchProbe) BAND.h else if (pitchAlt) 1 else -1
        val sc = if (pitchProbe && pitchAlt) scrollB else sp()
        return BoundList(0, 0, ids.size, pitch, mark and 0xFF, at4, items, listOf(
            BoundList.Entry(0, CfwModes.EV_UPROLL, +1, period, sc.at, ps, sc),
            BoundList.Entry(1, CfwModes.EV_DOWNROLL, -1, period, sc.at, ps, sc),
            BoundList.Entry(2, CfwModes.EV_TAP, 0, period, press.at, p, press)), ring = ring)
    }

    private fun sp() = if (scrollAlt) scroll2 else scroll

    // ------------------------------------------------------------------------------------------- K1-2 stage 4b: list 1
    /** The window in front has its own list ([ids1], [row1]): shown in the band, bound as list 1 when its staging is held
     *  and counted ([l1Bound]), uncovered and no transition runs. False: a window without a list, a cover as before. */
    var win1 = false
    /** The window list's staging is held and counted: it is bound whenever its window is in front, uncovered, no run. */
    var l1Bound = false
    /** A transition's run (the K1-2 spec §5.3, modeled): the band shows the run's frames ([MODAL]), nothing is bound, a
     *  notch is applied as today to the arriving view; the arriving list is bound at its end when its staging is held. */
    var trans = false
    private var transGen = 0
    var ids1 = IntArray(0)
    var row1 = 0
    /** The window (a view's identity) in front or last in front, and the one whose staging is held (−1 none). */
    var win1Key = -1
    var staged1Key = -1
    private var keySeq = 0
    private var staged1Ids = IntArray(0)
    private var mark1Base = 100
    val table1At = IntArray(2)
    private var table1Slot = 0
    /** List 1's effect form: its scroll entries play [scroll2] (an effect change re-makes the programs alone, decisions CV). */
    var l1Alt = false
    /** The staging is a ring list (LIST-RING: its paint wraps, `MainStaging.ringFits`); drawn per staging on a ring build. */
    var ring1 = false
    var bound1: BoundList? = null
    /** When the window list's lane may bind a new staging (its rows painted, written and read; drawn per staging). */
    private var ready1At = Long.MAX_VALUE
    /** Probability that a tap acted on list 1 is a Select into a new level (a transition leaving list 1). */
    var actSelects = 0.0
    /** Gesture → the number of times it moved list 1's row ((8) per list). */
    val moves1 = HashMap<Int, Int>()
    /** Per event class of list 1: how many times this sequence reached it (the stage's counted reach, not failures). */
    val l1Reach = java.util.TreeMap<String, Int>()
    fun reach(k: String) { l1Reach[k] = (l1Reach[k] ?: 0) + 1 }
    private var lastL1FireAt = Long.MIN_VALUE / 2

    /** The row the shell hands the rules in a list-1 sequence (`Shell.localRow`, 3119): the row of the list `slot0` holds —
     *  Main's, or the window list's while its staging is held for the view — and −1 when it holds none (a cover, a run, a
     *  window whose list has no staging of its own: its row is not read). Two lists make this exact: the rules keep a row
     *  per list and credit a row handed with nothing in front to the list last in front (AF.2), so a row handed for the
     *  wrong surface lands on the other list. Without list 1, Main's row in every case, as the harness always handed it. */
    fun rowNow(): Int = when {
        // C3 DW-2a (decisions DP 3): the wheel's case first, as `Shell.slot0` (3222) decides it — the switcher's cursor while
        // the wheel is offered, −1 while it is open and not (an open wheel is a cover: neither Main nor a window list under it)
        wheelOpen -> if (l2Bound) row2 else -1
        !list1 -> row
        covered || trans -> -1
        window -> if (win1 && l1Bound && staged1Key == win1Key) row1 else -1
        else -> row
    }
    fun idsNow(): IntArray = if (wheelOpen) ids2 else if (window && win1) ids1 else ids
    /** The window list is what the band shows (its window in front, uncovered, no run, no wheel over it). */
    fun l1Shows() = window && win1 && !covered && !trans && !wheelOpen
    /** A notch applied as today moves the window list's row: its window in front, uncovered (a run's arriving view too). */
    private fun l1View() = window && win1 && !covered && !wheelOpen
    private fun bandLevel(): Int = when { wheelOpen -> level(ids2[row2]); l1Shows() -> level(ids1[row1]); covered || window || trans -> MODAL; else -> level(ids[row]) }

    fun boundFor1(ids: IntArray, mark: Int, at4: Int): BoundList {
        val items = IntArray(ids.size) { recs[ids[it]] }
        val p = listOf(CfwModes.BindParam(CfwModes.KIND_ITEM, 0), CfwModes.BindParam(0, 0), CfwModes.BindParam(0, 0), CfwModes.BindParam(0, 0))
        val sc = if (l1Alt) scroll2 else scroll
        // the window form (K1-2 S2 §7, 3a): entries 3–5, slot 0, pitch the row height for every effect, no press
        return BoundList(1, 0, ids.size, BAND.h, mark and 0xFF, at4, items, listOf(
            BoundList.Entry(3, CfwModes.EV_UPROLL, +1, period, sc.at, p, sc),
            BoundList.Entry(4, CfwModes.EV_DOWNROLL, -1, period, sc.at, p, sc),
            BoundList.Entry(5, CfwModes.EV_TAP, 0, period, CfwModes.PROG_NONE, p, null)), ring = ring1)
    }

    /** The lane's time from a new view to its binding (rows painted, written, the count read back — modeled: 100–700 ms). */
    private fun laneDelay(): Long = 100L + l1Rnd.nextInt(600)

    /** A new staging of the window list's view ([ids1]): its item table into the other slot at [writeAt], a new mark, a
     *  ring staging drawn on a ring build; bound by the lane from [readyAt]. */
    private fun stage1New(writeAt: Long, readyAt: Long) {
        table1Slot = 1 - table1Slot
        staged1Ids = ids1.copyOf()
        val tbl = write19(table1At[table1Slot], itemTable(staged1Ids))
        if (writeAt <= now) sendDown(tbl) else at(writeAt) { sendDown(tbl) }
        mark1Base++
        ring1 = ring && staged1Ids.size >= 2 && l1Rnd.nextBoolean()
        if (ring1) reach("a ring list 1 staged")
        bound1 = boundFor1(staged1Ids, mark1Base, table1At[table1Slot])
        staged1Key = win1Key
        l1Bound = false
        ready1At = readyAt
        at(readyAt) { pumpSoon() }
    }

    /** A new window's view (another app, or a new level): 2–8 distinct rows, the cursor on its first. */
    private fun newView1(writeAt: Long, readyAt: Long) {
        win1Key = ++keySeq
        val pool = (0 until IDS).toMutableList()
        java.util.Collections.shuffle(pool, l1Rnd)
        ids1 = pool.take(2 + l1Rnd.nextInt(7)).toIntArray()
        row1 = 0
        stage1New(writeAt, readyAt)
    }

    /** What is in front changed in the rules' terms (a message is a front's own only while that front stands). */
    private fun frontChanged() { frontGen++; noteFrontInBurst() }

    /** Slot 0 changing lists (pin 13's shape): the leaving list's local still drawing on RIGHT. */
    private fun slot0Changes() {
        val i = sim.motionForTest(Arm.RIGHT).slots[0]
        if (i != null && i.local && i.drawing) reach("slot 0 changed lists with a local of the leaving list drawing on RIGHT")
    }

    /** The window list's lane (`pumpWindowList`, modeled): it binds a held staging of the view in front when it is ready,
     *  uncovered and no transition runs. */
    private fun lane1() {
        if (!list1 || !window || !win1 || covered || trans || wheelOpen || l1Bound || staged1Key != win1Key || now < ready1At) return
        l1Bound = true
        frontChanged(); slot0Changes()
        rules.front(bound1, row1)
        reach("the lane bound a new staging of list 1")
    }

    /** A window with its own list comes in front of Main: the same window again (its staging kept: no LIST, decisions CX
     *  "kept across Main") or another one (a new staging, bound by the lane); with a transition's run when [run] > 0. */
    fun openWindowList(same: Boolean, run: Long) {
        if (window || covered || trans || wheelOpen) return
        val inst = sim.motionForTest(Arm.RIGHT).slots[0]
        if (inst != null && inst.drawing && inst.local && inst.tag in commitsSent) { emit(listOf(CfwModes.motionClose(0)), emptyList()); windowCuts++ }
        window = true; win1 = true
        val kept = same && staged1Key >= 0 && staged1Key == win1Key && l1Bound
        if (!kept) { newView1(now + run, now + run + laneDelay()); reach("window change: Main → list 1, a new staging") }
        else reach("window change: Main → list 1, its staging kept")
        frontChanged()
        if (run > 0) { startRun(run); reach("transition Main → list 1"); rules.front(null, rowNow()) }
        else if (kept) { slot0Changes(); rules.front(bound1, row1) }
        else rules.front(null, rowNow())
        pumpSoon()
    }

    private fun startRun(run: Long) {
        trans = true
        val g = ++transGen
        at(now + run) { if (trans && transGen == g) endRun() }
    }

    /** The run's end: the arriving front is bound when its staging is held (Main's always), else the lane binds it later. */
    private fun endRun() {
        trans = false
        frontChanged()
        if (!covered && !window) { slot0Changes(); rules.front(bound, row) }
        else if (!covered && window && win1 && l1Bound && staged1Key == win1Key) { slot0Changes(); rules.front(bound1, row1) }
        pumpSoon()
    }

    /** A Select from bound list 1 into a new level (the K1-2 spec §5.3, decisions DA (c) 1 (i)): the list leaves the front
     *  for the run (its CLEARs on the out half), the new level is its arriving view, staged after the run. */
    private fun selectFrom1() {
        val run = 200L + l1Rnd.nextInt(600)
        frontChanged()
        startRun(run)
        newView1(now + run, now + run + laneDelay())
        rules.front(null, rowNow())
        reach("transition leaving list 1: a Select into a new level")
        pumpSoon()
    }

    /** List 1's view changes its count (a row added or removed): a new staging, a new mark — in place with the cursor's
     *  shift (P9's form; with no burst open, the list in front) or withdrawn at once and staged again by the lane. */
    fun count1(): Boolean {
        if (!list1 || !window || !win1 || trans || staged1Key != win1Key) return false
        val add = ids1.size < 8 && (ids1.size <= 2 || l1Rnd.nextBoolean())
        val oldId = ids1[row1]
        val newIds = if (add) intArrayOf((0 until IDS).first { it !in ids1 }) + ids1
            else if (row1 < ids1.size - 1 && l1Rnd.nextBoolean()) ids1.copyOfRange(0, ids1.size - 1) else ids1.copyOfRange(1, ids1.size)
        val newRow = newIds.indexOf(oldId).let { if (it < 0) 0 else it }
        val inPlace = rules.front?.list == 1 && !rules.burstOpen && l1Rnd.nextBoolean()
        if (inPlace) {
            val shift = newRow - row1
            ids1 = newIds; row1 = newRow
            stage1New(now, now)
            l1Bound = true
            rules.front(bound1, row1, shift)
            reach("count change of bound list 1: in place (a new mark, the cursor's shift)")
        } else {
            val wasFront = rules.front?.list == 1
            ids1 = newIds; row1 = newRow
            l1Bound = false
            if (wasFront) { frontChanged(); rules.front(null, rowNow()) }
            stage1New(now, now + laneDelay())
            reach(if (wasFront) "count change of bound list 1: withdrawn, staged again by the lane" else "count change of unbound list 1: staged again")
        }
        pumpSoon()
        return true
    }

    /** An effect change (decisions CV): the window list keeps its staging and its programs are made again — SETs alone,
     *  with no burst open and the list in front (`Shell`'s pans switch); elsewhere the new form goes at its next binding. */
    fun effect1(): Boolean {
        if (!list1 || rules.burstOpen || bound1 == null) return false
        l1Alt = !l1Alt
        val p = if (l1Alt) scroll2 else scroll
        sendDown(write19(p.at, p.bytes))
        val b = bound1!!
        bound1 = boundFor1(staged1Ids, b.mark, b.items4)
        if (rules.front?.list == 1) { rules.front(bound1, row1); reach("effect change of bound list 1: SETs alone, the staging kept") }
        else reach("effect change with list 1 not in front")
        pumpSoon()
        return true
    }

    /** List 1 withdrawn while its window stays (the wheel's block meets it, a row form changes): unbound at once, staged
     *  again below by the lane (a new place, a new mark). */
    fun withdraw1(): Boolean {
        if (rules.front?.list != 1) return false
        l1Bound = false
        frontChanged()
        rules.front(null, rowNow())
        stage1New(now, now + laneDelay())
        reach("withdrawal of bound list 1")
        pumpSoon()
        return true
    }

    /** One list-1 event of the random driver, drawn from [r] (the sequence's list-1 stream). Returns its name. */
    fun list1Event(r: java.util.Random): String {
        val y = r.nextInt(100)
        return when {
            y < 20 -> { val same = r.nextBoolean(); val run = if (r.nextInt(10) < 4) 200L + r.nextInt(500) else 0L
                if (!window && !covered && !trans && !wheelOpen) { openWindowList(same, run); "open a window list (${if (same) "same" else "another"}, run $run)" } else "open a window list (not now)" }
            y < 32 -> { val run = if (r.nextBoolean()) 200L + r.nextInt(500) else 0L; if (window && win1) { back(run); "back from list 1 (run $run)" } else { back(); "back" } }
            y < 44 -> if (count1()) "count change of list 1" else "count change (not now)"
            y < 52 -> if (effect1()) "effect change" else "effect change (not now)"
            y < 60 -> if (withdraw1()) "withdraw list 1" else "withdraw (not now)"
            y < 68 -> if (window && restage()) { reach("Main's covered re-make under ${if (win1) "list 1" else "a window"}"); "Main's covered re-make" } else "Main's re-make (not now)"
            y < 80 -> { if (covered) { uncover(); "uncover" } else { val f = rules.front?.list; cover()
                if (covered && f == 1) { reach(if (noticeTimer) "a notice over bound list 1" else "a cover over bound list 1 (adjust, menu)")
                    if (now - lastL1FireAt < 300) reach("a cover over bound list 1 with a notch of it in flight") }
                "cover" } }
            else -> {
                // a notch racing a change of what is in front (pins 7 and 13's shapes): the change 0–40 ms after the notch
                val kind = 1 + r.nextInt(2); val d = r.nextInt(41).toLong(); val k = r.nextInt(4)
                val f = rules.front?.list
                gesture(kind)
                at(now + d) {
                    val what = when {
                        f == 0 && !window && !covered && !trans -> { openWindowList(true, 0L); "the window list opened" }
                        f == 1 && k == 0 -> { if (count1()) "a count change" else "nothing" }
                        f == 1 && k == 1 -> { if (withdraw1()) "a withdrawal" else "nothing" }
                        f == 1 && k == 2 -> { val c = covered; cover(); if (!c && covered) "a cover" else "nothing" }
                        f == 1 && window && win1 -> { back(); "Back" }
                        else -> "nothing"
                    }
                    if (what != "nothing") reach("a notch on list ${f} racing $what")
                }
                "a notch racing a front change (list $f, +$d ms)"
            }
        }
    }

    // ------------------------------------------------------------------------------------------- C3 DW-2a: list 2, the wheel
    /** The switcher is open (the chord): the wheel's list in front while it is offered ([l2Bound]), else nothing — an open
     *  wheel is a cover (`Shell.slot0`, 3222; decisions DA (c) 4), its spin the phone's. */
    var wheelOpen = false
    /** The wheel is offered: the item table of this open's order written and counted (`Shell.wheelNotOffered`, 7075). */
    var l2Bound = false
    /** The switcher's entries in this open's order (identities, 3–9 of them) and its cursor. */
    var ids2 = IntArray(0)
    var row2 = 0
    private var staged2Ids = IntArray(0)
    private var mark2Base = 200
    val table2At = IntArray(2)
    private var table2Slot = 0
    var bound2: BoundList? = null
    /** When the lane's write of the staged order is counted — the offer's condition (drawn per staging: 100–700 ms). */
    private var ready2At = Long.MAX_VALUE
    /** What the open covered ("main", "list1", "window"): a cancel returns there. */
    private var wheelFrom = "main"
    /** An ordinary notice that came while the wheel was open, or was on screen at its open: shown at the close
     *  (`Shell.commitSwitcher`'s `showNextIfIdle`). */
    private var noticeWaits = false
    /** Gesture → the number of times it moved the wheel's row ((8) per list). */
    val moves2 = HashMap<Int, Int>()
    /** Per event class of the wheel: how many times this sequence reached it (the stage's counted reach, not failures). */
    val w2Reach = java.util.TreeMap<String, Int>()
    fun reach2(k: String) { w2Reach[k] = (w2Reach[k] ?: 0) + 1 }
    private var lastL2FireAt = Long.MIN_VALUE / 2

    /** List 2 as `Wheel.Block.boundList` (`Wheel.kt` 237) stages it: entries 6 (UPROLL, +1) and 7 (DOWNROLL, −1) at the list's
     *  period with the item lookup, no tap staged (the rules number the report-only tap 0, `LocalRules.tapEntry`), pitch 0,
     *  a ring. Its pictures are the band's (its own pixels are `--local-check`'s and `WheelTest`'s, decisions DP 3). */
    fun boundFor2(ids: IntArray, mark: Int, at4: Int): BoundList {
        val items = IntArray(ids.size) { recs[ids[it]] }
        val p = listOf(CfwModes.BindParam(CfwModes.KIND_ITEM, 0), CfwModes.BindParam(0, 0), CfwModes.BindParam(0, 0), CfwModes.BindParam(0, 0))
        return BoundList(2, 0, ids.size, 0, mark and 0xFF, at4, items, listOf(
            BoundList.Entry(6, CfwModes.EV_UPROLL, +1, period, scroll.at, p, scroll),          // `Shell.WHEEL_ENTRIES` (6, 7)
            BoundList.Entry(7, CfwModes.EV_DOWNROLL, -1, period, scroll.at, p, scroll)), ring = true)
    }

    /** The wheel's lane from a new order to its count (the block's item table written and read back — modeled: 100–700 ms). */
    private fun laneDelay2(): Long = 100L + w2Rnd.nextInt(600)

    /** The switcher's entries change (a window opened or closed elsewhere): 3–9 distinct identities in a new order. */
    private fun newEntries2() {
        val pool = (0 until IDS).toMutableList()
        java.util.Collections.shuffle(pool, w2Rnd)
        ids2 = pool.take(3 + w2Rnd.nextInt(7)).toIntArray()
    }

    /** A new staging of the wheel's order ([ids2]): its item table into the other slot now, a new mark; offered from
     *  [readyAt]. Never while the wheel is offered (a new order comes at a commit or between opens). */
    private fun stage2New(readyAt: Long) {
        table2Slot = 1 - table2Slot
        staged2Ids = ids2.copyOf()
        sendDown(write19(table2At[table2Slot], itemTable(staged2Ids)))
        mark2Base++
        bound2 = boundFor2(staged2Ids, mark2Base, table2At[table2Slot])
        ready2At = readyAt
        at(readyAt) { pumpSoon() }
    }

    /** A COMMITted local of slot 0 still drawing on RIGHT is closed first, alone, ahead of the frame that draws over it (the
     *  transport's rule; decisions AW.3's form, as [openWindow] does it). */
    private fun closeSlot0First() {
        val inst = sim.motionForTest(Arm.RIGHT).slots[0]
        if (inst != null && inst.drawing && inst.local && inst.tag in commitsSent) { emit(listOf(CfwModes.motionClose(0)), emptyList()); windowCuts++ }
    }

    /** The chord opens the switcher (`Shell.openSwitcher`, 2398) over Main, a window (with its own list or not) or a cover —
     *  a menu or an adjust level is displaced, a notice on screen goes back to the queue and is shown at the close; never
     *  inside a transition's run. Offered at the open when its order's staging is counted ("bound at the open": the CLEARs of
     *  the list that was in front, the SETs, LIST-RING, the DISCARD naming the cursor), else phone-drawn and bound mid-open
     *  by [lane2]. [newEntries]: the entries changed since the last open (a new n and order). */
    fun openWheel(newEntries: Boolean = false): Boolean {
        if (!wheel || wheelOpen || trans) return false
        if (covered) {
            covered = false
            if (noticeTimer) { noticeWaits = true; reach2("an open over a notice (back to the queue)") } else reach2("an open over a cover (displaced)")
        }
        closeSlot0First()
        wheelFrom = if (!window) "main" else if (win1) "list1" else "window"
        if (ids2.isEmpty() || newEntries) { newEntries2(); stage2New(now + laneDelay2()); reach2("the entries changed between opens (a new n and order)") }
        row2 = if (ids2.size > 1) 1 else 0
        wheelOpen = true
        frontChanged()
        reach2("an open over $wheelFrom")
        if (now >= ready2At) { l2Bound = true; slot0Changes(); rules.front(bound2, row2); reach2("an open bound at the open") }
        else { l2Bound = false; rules.front(null, -1); reach2("an open phone-drawn (its order's item table not counted yet)") }
        pumpSoon()
        return true
    }

    /** The wheel's offer (`Shell.wheelOfferPoll`): bound mid-open once its order's item table is counted — the return rule's
     *  batch (the SETs, LIST-RING, the DISCARD naming the cursor). */
    private fun lane2() {
        if (!wheel || !wheelOpen || l2Bound || now < ready2At) return
        l2Bound = true
        frontChanged(); slot0Changes()
        rules.front(bound2, row2)
        reach2("the wheel bound mid-open")
    }

    /** The switcher closes onto [to] ("main"; "list1": the window whose list is staged, kept across the wheel; "list1 new":
     *  another window's list, staged by the lane; "window": one without a list): a COMMITted local still drawing closed
     *  first, the wheel's entries CLEARed, the arriving list's SETs (its LIST only where its staging changed under the
     *  wheel) and the DISCARD naming its row; no transition (the amendment's §4.1). A notice that waited is shown next. */
    private fun closeWheel(to: String) {
        closeSlot0First()
        val wasBound = l2Bound
        wheelOpen = false; l2Bound = false
        frontChanged()
        val dest = if (to.startsWith("list1") && !list1) "window" else to
        when (dest) {
            "main" -> { window = false; win1 = false; slot0Changes(); rules.front(bound, row) }
            "window" -> { window = true; win1 = false; rules.front(null, rowNow()) }
            else -> {
                window = true; win1 = true
                if (dest == "list1 new" || staged1Key < 0) newView1(now, now + laneDelay())
                if (l1Bound && staged1Key == win1Key) { slot0Changes(); rules.front(bound1, row1) } else rules.front(null, rowNow())
            }
        }
        reach2("a close onto ${if (dest == "list1 new") "another window's list" else dest}${if (wasBound) "" else " (the wheel unbound)"}")
        if (noticeWaits) { noticeWaits = false; cover(); if (covered) reach2("a notice that waited behind the wheel shown at its close") }
        pumpSoon()
    }

    /** A cancel (a long press or a double tap inside the wheel, `Shell.cancelSwitcher`): back to what the open covered. */
    fun cancelWheel(): Boolean {
        if (!wheelOpen) return false
        reach2("a cancel")
        closeWheel(wheelFrom)
        return true
    }

    /** A commit to row [r] (`Shell.commitSwitcher`, 2421): the entry's window by its identity — Main, the window list kept,
     *  another window's list, a window without one — then the switcher's recency order (the committed entry first) staged
     *  again: an open before its count is phone-drawn ("a commit, then an open within ≈ 1 s", the amendment's §5). */
    private fun commitWheel(r: Int) {
        val id = ids2[r]
        closeWheel(when (id % 4) { 0 -> "main"; 1 -> "list1"; 2 -> "list1 new"; else -> "window" })
        ids2 = intArrayOf(id) + ids2.filter { it != id }
        stage2New(now + laneDelay2())
    }

    /** A tap acted on the wheel's row: the commit. Its tap entry is report-only (no press). */
    private fun act2(g: Int, r: Int, viaRule: Boolean, rowBefore: Int = r) {
        acts += Act(g, ids2[r], viaRule, fires[g]?.shownR, rules.taps, rowBefore, r)
        reach2(if (viaRule) "a commit by the tap rule" else "a commit as today (the stock click)")
        commitWheel(r)
    }

    private fun move2(g: Int, newRow: Int) { if (newRow != row2) { moves2[g] = (moves2[g] ?: 0) + 1; row2 = newRow } }

    /** One wheel event of the random driver, drawn from [r] (the sequence's wheel stream; decisions DP 2). Closed: an open
     *  (a new n and order one time in five), one racing a notch 0–40 ms after it, one with its CLEARs lost on a lens. Open:
     *  a cancel, a tap (a commit), a notch racing the close, a close with its CLEARs lost, a commit then a re-open inside the
     *  count, a notice, a session start, a notch. Returns its name. */
    fun wheelEvent(r: java.util.Random): String {
        val y = r.nextInt(100)
        val arms = listOf(setOf(Arm.LEFT), setOf(Arm.RIGHT), setOf(Arm.LEFT, Arm.RIGHT))
        if (!wheelOpen) return when {
            y < 60 -> if (openWheel(r.nextInt(5) == 0)) "open the wheel" else "open the wheel (not now)"
            y < 80 -> { val kind = 1 + r.nextInt(2); val d = r.nextInt(41).toLong()
                gesture(kind)
                at(now + d) { if (openWheel()) reach2("an open racing a notch (0–40 ms after it)") }
                "a notch, the wheel opened +$d ms" }
            else -> { val a = arms[r.nextInt(3)]
                if (!trans) { lose27 = "clear" to a; if (openWheel()) reach2("an open with its CLEARs lost on ${a.joinToString("+")}") }
                "the wheel opened, its CLEARs lost" }
        }
        return when {
            y < 18 -> { cancelWheel(); "cancel the wheel" }
            y < 36 -> { gesture(0); "a tap in the wheel" }
            y < 50 -> { val kind = 1 + r.nextInt(2); val d = r.nextInt(41).toLong(); val tap = r.nextBoolean()
                gesture(kind)
                at(now + d) { if (wheelOpen) { reach2("a notch racing the close (0–40 ms before it)"); if (tap) gesture(0) else cancelWheel() } }
                "a notch, then ${if (tap) "a tap" else "a cancel"} +$d ms" }
            y < 60 -> { val a = arms[r.nextInt(3)]
                lose27 = "clear" to a; reach2("a close with its CLEARs lost on ${a.joinToString("+")}")
                if (r.nextBoolean()) { gesture(0); "a tap in the wheel, its close's CLEARs lost" } else { cancelWheel(); "cancel the wheel, its CLEARs lost" } }
            y < 70 -> { val d = 100L + r.nextInt(900)
                gesture(0)
                at(now + d) { if (openWheel()) reach2("a re-open 100–1000 ms after a tap in the wheel") }
                "a tap in the wheel, a re-open +$d ms" }
            y < 80 -> { cover(); "a notice with the wheel open" }
            y < 86 -> { reach2("a session start with the wheel open"); start(fresh = r.nextBoolean()); "a session start with the wheel open" }
            else -> { gesture(1 + r.nextInt(2)); "a notch in the wheel" }
        }
    }

    private fun itemTable(ids: IntArray) = ByteArray(2 * ids.size).also { b -> ids.forEachIndexed { i, id -> b[2 * i] = (recs[id] and 0xFF).toByte(); b[2 * i + 1] = (recs[id] shr 8).toByte() } }

    /** The phone's truth (levels, one per pixel; the list is flat, so both lenses alike). */
    fun truth(): ByteArray {
        val t = ByteArray(W * H)
        fun fill(r: Rect, l: Int) { for (y in r.y until r.bottom) for (x in r.x until r.right) t[y * W + x] = l.toByte() }
        fill(BAND, bandLevel())
        if (covered && noticeTimer) fill(NOTICE_GEO.rule, wm.damage.core.motion.PopupPrograms.BAR_LEVEL)
        if (badge && !covered && !window && !trans && !wheelOpen) fill(BADGE, BADGE_LEVEL)
        fill(CLOCK, 1 + clock % 13)
        if (pitchProbe) fill(PROBE, probeLevel(row))
        return t
    }

    var model = ByteArray(W * H)
    private var lastTruth = ByteArray(W * H)

    // ------------------------------------------------------------------------------------------- what is recorded
    class Act(val gesture: Int, val id: Int, val viaRule: Boolean, val rightId: Int?, val taps: Taps,
              /** C3 X2DM1a (decisions CA (5)): the phone's row before the act's verdict moved it, and the row acted on. */
              val rowBefore: Int = -1, val row: Int = -1)
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
                *  SHADOW shows in the band (C3 FZ5, part BO (B): read without the badge's cells); the row RIGHT's cursor names
                *  after the fire. */
               var awaitsPresentR: Boolean = false, var shadowR: Int? = null, var cursorIdR: Int? = null,
               /** C3 FZ5 (part BO (A)): at RIGHT's fire, the end of the latest wait for a motion's end on the glasses (the
                *  harness's carried hold: an adopted motion's or the wrap's). */
               var holdEndR: Long = Long.MIN_VALUE,
               /** K1-2 stage 4b: the list whose entry RIGHT's fire matched (0 without list 1). */
               var listR: Int = 0)

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
    private fun mainInFront() = !covered && !window && !wheelOpen

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
            if (arm == Arm.RIGHT) { f.awaitsPresentR = listRefusedPresentR == sim.right.presentSeq; f.shadowR = bandIdIn(sim.right.shadow, BADGE) }
            if (arm == Arm.RIGHT && !tap) { scrollFiresR++; if (sim.motionForTest(arm).slots.any { it != null && it.slot <= WRAP_SLOT && it.drawing && it.d < it.T }) scrollFiresInMotionR++ }
            // K1-2 stage 4b: the list the lens's entry for this event names (list 1's or Main's; 0 without list 1)
            // C3 DW-2a: or the wheel's (list 2)
            val lf = if (!list1 && !wheel) 0 else (b.entries.firstOrNull { it != null && it.eventId == id }?.list ?: 0)
            sim.conformanceInput(arm, dev, id.toLong(), 0, now)
            if (holdWordOn == arm) sim.motionForTest(arm).holdWordForTest(false)
            sim.conformanceStockCalls(arm)
            val s = if (b.seq != before) b.seq else null
            if (arm == Arm.LEFT) f.sL = s else { f.sR = s; s?.let { bySR[it] = g }; f.cursorR = b.lists[lf].cursor; f.markR = b.lists[lf].mark }
            if (arm == Arm.RIGHT) f.cursorIdR = (if (lf == 1) staged1Ids else if (lf == 2) staged2Ids else ids).getOrNull(b.lists[lf].cursor)
            if (arm == Arm.RIGHT) f.listR = lf
            if (arm == Arm.RIGHT && lf == 1 && s != null) lastL1FireAt = now
            if (arm == Arm.RIGHT && lf == 2 && s != null) lastL2FireAt = now
            if (arm == Arm.RIGHT && carryHold) f.holdEndR = deferUntil
            // C3 DW-2a: the wheel unbound is a cover too; bound, it is the list in front
            if (arm == Arm.LEFT && (covered || (wheelOpen && rules.front?.list != 2) || (!wheelOpen && window && rules.front?.list != 1)) &&
                sim.motionForTest(arm).slots.any { it?.local == true && it.tag == s }) localsUnderCover++
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
                if (!b.live || b.entries.none { it != null && it.eventId == id }) frontNotchAt[g] = (if (wheelOpen) "wheel" else if (window && win1) "window list" else if (window) "window" else "cover") to frontGen
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
               val carried: Boolean, val outcome: Int?, val size: Int, val carries: List<Int> = emptyList(),
               /** K1-2 stage 4b: the list whose row the verdict moved (1: the window list's; (9) counts Main's alone). */
               val list: Int = 0)
    val phoneMoves = ArrayList<Move>()
    /** The notches in an adoption's (or a tap correction's) gap that RIGHT drew and still held at its fire — the move
     *  carries them; the ring hears them there (B083 v2), as the verdict comes, before anything it opens. */
    private fun carries(g: Int, v: LocalRules.Verdict, a: DamageMsg.Applied, heardBefore: Int): List<Int> {
        val out = ArrayList<Int>()
        val s = a.s ?: return out
        if (v !is LocalRules.Verdict.Adopt && v !is LocalRules.Verdict.Act) return out
        var k = (heardBefore + 1) and 0xFFFF
        while (k != s && LocalRules.after(s, k)) {
            // K1-2 stage 4b: a fire of the same list only (a list-1 fire's step is never in a Main adoption, nor the reverse)
            bySR[k]?.let { gk -> if (rightOutcome[gk] == DamageMsg.APPLIED_DREW && ringStep[gk] != null && fires[gk]?.resetsAtFireR == fires[g]?.resetsAtFireR &&
                fires[gk]?.listR == (a.list ?: 0)) out += gk }
            k = (k + 1) and 0xFFFF
        }
        for (gk in out) ringHeard(gk, "carrying adoption")
        return out
    }
    private fun record(g: Int, by115: Boolean, v: LocalRules.Verdict, before: Int, carried: Boolean, a: DamageMsg.Applied?, carries: List<Int> = emptyList(),
                       onL1: Boolean = false, before1: Int = row1, onL2: Boolean = false, before2: Int = row2) {
        phoneMoves += if (onL2) Move(g, now, ringN++, by115, v, before2, row2, carried, a?.outcome, ids2.size, carries, list = 2)
            else if (onL1) Move(g, now, ringN++, by115, v, before1, row1, carried, a?.outcome, ids1.size, carries, list = 1)
            else Move(g, now, ringN++, by115, v, before, row, carried, a?.outcome, ids.size, carries)
    }
    /** K1-2 stage 4b: the verdict acts on the window list's row (an adoption or an act while list 1 is the rules' front; a
     *  verdict applied as today while its window is in front, uncovered), not Main's. */
    private fun onL1(v: LocalRules.Verdict): Boolean = list1 && when (v) {
        is LocalRules.Verdict.Adopt, is LocalRules.Verdict.Act -> rules.front?.list == 1
        is LocalRules.Verdict.Apply -> l1View()
        else -> false
    }
    /** C3 DW-2a: the verdict acts on the wheel's row (an adoption or act while list 2 is the rules' front; a verdict applied
     *  as today while the wheel is open — the phone's spin, or its commit), ahead of every other list. */
    private fun onL2(v: LocalRules.Verdict): Boolean = wheel && when (v) {
        is LocalRules.Verdict.Adopt, is LocalRules.Verdict.Act -> rules.front?.list == 2
        is LocalRules.Verdict.Apply -> wheelOpen
        else -> false
    }
    private fun carriedNow() = !rules.burstOpen && rules.owesCarriedUntilMs != Long.MIN_VALUE

    private fun phoneReport(a: DamageMsg.Applied, g: Int) {
        beforeReport?.let { beforeReport = null; it() }
        if (g >= 0 && g !in reportHeardAt) reportHeardAt[g] = now
        if (g >= 0) ringHeard(g, "115")
        val before = row; val before1 = row1; val before2 = row2; val carried = carriedNow(); val heardBefore = rules.sHeard
        val opening = !rules.burstOpen
        val fl = rules.front?.list; val markNow = a.list?.let { rules.markOf(it) }
        val v = rules.report(a, rowNow(), now)
        gapOf(g, a.s, heardBefore)
        if (v is LocalRules.Verdict.Adopt && a.wrapped) wrapAdopted += g        // C3 DM-0: a ring fire across the wrap, adopted
        if (opening && rules.burstOpen) { burstFrom = now; burstS0 = heardBefore; burstSeq++ }
        trace?.invoke("[$now] phone 115 g$g ${a.describe()} row ${rowNow()} → $v")
        verdicts += g to v
        val l2 = onL2(v)
        val l1 = !l2 && onL1(v)
        // (C3 DW-2a: a fire RIGHT drew on Main's list that the phone applied to the wheel is the same shape)
        if ((list1 && l1 || l2) && g >= 0 && fires[g]?.listR == 0 && fires[g]?.sR != null) crossHeld = true
        if (list1) {
            // pins 13 and 7 reached by a sequence (the K1-2 spec §8): a report of the other list in slot 0, a fire on list 1's old mark
            if (a.list == 0 && fl == 1) reach("pin 13: a list-0 report answered with list 1 in front ($v)")
            if (a.list == 1 && fl == 0) reach("pin 13: a list-1 report answered with Main in front ($v)")
            if (a.list == 1 && fl == 1 && a.mark != null && a.mark != markNow && a.outcome == DamageMsg.APPLIED_DREW) reach("pin 7: a fire on list 1's old mark ($v)")
            if (l1 && v is LocalRules.Verdict.Adopt) { reach("a notch adopted on list 1"); if (a.wrapped) reach("a wrap notch adopted on ring list 1") }
        }
        if (wheel) {
            // C3 DW-2a: a report of one list answered with another in front (pin 13's shape, the wheel's halves), a fire on
            // the wheel's old mark, and the wheel's adoptions
            if (a.list == 2 && fl != 2) reach2("a list-2 report answered with ${fl?.let { "list $it" } ?: "nothing"} in front ($v)")
            if (a.list != null && a.list != 2 && fl == 2) reach2("a list-${a.list} report answered with the wheel in front ($v)")
            if (a.list == 2 && fl == 2 && a.mark != null && a.mark != markNow && a.outcome == DamageMsg.APPLIED_DREW) reach2("a fire on the wheel's old mark ($v)")
            if (l2 && v is LocalRules.Verdict.Adopt) { reach2("a notch adopted on the wheel"); if (a.wrapped) reach2("a wrap notch adopted on the wheel") }
            if (a.isTap && a.list == 2 && v == LocalRules.Verdict.Nothing) reach2("a tap of the wheel's entry not acted on (${if (fl == 2) "the wheel in front" else "after its close"})")
        }
        val carries = carries(g, v, a, heardBefore)
        apply(v, g, a)
        record(g, true, v, before, carried, a, carries, l1, before1, l2, before2)
        afterReport?.let { afterReport = null; it() }
        pumpSoon()
    }

    private fun phoneStock(st: LocalRules.Stock, g: Int) {
        if (!st.click) ringHeard(g, "stock")
        val before = row; val before1 = row1; val before2 = row2; val carried = carriedNow()
        val os = rules.owesScroll; val oa = rules.owesAny
        val v = rules.stock(st, rowNow(), now)
        val l2 = onL2(v)
        val l1 = !l2 && onL1(v)
        if ((list1 && l1 || l2) && v is LocalRules.Verdict.Apply && fires[g]?.listR == 0 && fires[g]?.sR != null) crossHeld = true
        if (l2 && v is LocalRules.Verdict.Apply) reach2(if (st.click) "a click applied as today with the wheel open (a commit)" else "a notch applied as today with the wheel open (the phone's spin)")
        if (rules.owesAny < oa) stockPaidAny += g
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
        record(g, false, v, before, carried, null, onL1 = l1, before1 = before1, onL2 = l2, before2 = before2)
        pumpSoon()
    }

    private fun apply(v: LocalRules.Verdict, g: Int, a: DamageMsg.Applied?) {
        // C3 DW-2a: a verdict on the wheel moves its row (an adoption, the phone's spin) or commits (an act, a click as today)
        if (onL2(v)) when (v) {
            is LocalRules.Verdict.Adopt -> move2(g, v.row)
            is LocalRules.Verdict.Apply -> when (v.type) {
                EvenHubMsg.EV_CLICK -> act2(g, row2, viaRule = false)
                EvenHubMsg.EV_SCROLL_BOTTOM -> move2(g, (row2 + 1) % ids2.size)
                EvenHubMsg.EV_SCROLL_TOP -> move2(g, (row2 - 1 + ids2.size) % ids2.size)
            }
            is LocalRules.Verdict.Act -> { val was = row2; move2(g, v.row); act2(g, v.row, viaRule = true, rowBefore = was) }
            else -> {}
        // K1-2 stage 4b: a verdict on the window list moves its row (an adoption or act on list 1; applied as today with its
        // window in front); Main's exactly as before otherwise
        } else if (onL1(v)) when (v) {
            is LocalRules.Verdict.Adopt -> move1(g, v.row)
            is LocalRules.Verdict.Apply -> when (v.type) {
                EvenHubMsg.EV_CLICK -> if (l1Shows()) act1(g, row1, viaRule = false)
                EvenHubMsg.EV_SCROLL_BOTTOM -> move1(g, (row1 + 1) % ids1.size)
                EvenHubMsg.EV_SCROLL_TOP -> move1(g, (row1 - 1 + ids1.size) % ids1.size)
            }
            is LocalRules.Verdict.Act -> { val was = row1; move1(g, v.row); act1(g, v.row, viaRule = true, rowBefore = was) }
            else -> {}
        } else when (v) {
            is LocalRules.Verdict.Adopt -> move(g, v.row)
            is LocalRules.Verdict.Apply -> when (v.type) {
                EvenHubMsg.EV_CLICK -> if (!covered && !window) act(g, row, viaRule = false)
                EvenHubMsg.EV_SCROLL_BOTTOM -> if (!covered && !window) move(g, (row + 1) % ids.size)
                EvenHubMsg.EV_SCROLL_TOP -> if (!covered && !window) move(g, (row - 1 + ids.size) % ids.size)
            }
            is LocalRules.Verdict.Act -> { val was = row; move(g, v.row); act(g, v.row, viaRule = true, rowBefore = was) }
            LocalRules.Verdict.Nothing -> {}
        }
    }

    private fun move1(g: Int, newRow: Int) { if (newRow != row1) { moves1[g] = (moves1[g] ?: 0) + 1; row1 = newRow } }

    var actOpensWindow = 0.0
    private fun act(g: Int, r: Int, viaRule: Boolean, rowBefore: Int = r) {
        val f = fires[g]
        val rightId = f?.shownR
        acts += Act(g, ids[r], viaRule, rightId, rules.taps, rowBefore, r)
        if (rnd.nextDouble() < actOpensWindow) openWindow()
    }
    /** K1-2 stage 4b: a tap acted on the window list's row (its tap entry is report-only: no press); a Select into a new
     *  level with probability [actSelects]. */
    private fun act1(g: Int, r: Int, viaRule: Boolean, rowBefore: Int = r) {
        acts += Act(g, ids1[r], viaRule, fires[g]?.shownR, rules.taps, rowBefore, r)
        reach("a tap acted on list 1")
        if (rules.front?.list == 1 && l1Rnd.nextDouble() < actSelects) selectFrom1()
    }

    // ---- covers, windows, re-stages, switches (the shell's own changes, each in the batch that draws it)
    /** K1-2 stage 4b: a cover (an adjust level, a menu, a notice) may also stand over a window's own list; no cover in a run. */
    fun cover() {
        // C3 DW-2a (the amendment's §4.1): with the wheel open a menu or an adjust level does not open; an ordinary notice
        // waits behind it (shown at the close), an emergency one cancels it and shows
        if (wheelOpen) {
            if (!noticeTimer) return
            if (w2Rnd.nextInt(4) != 0) { noticeWaits = true; reach2("an ordinary notice waits behind the wheel"); return }
            reach2("an emergency notice cancels the wheel"); noticeWaits = true; cancelWheel(); return
        }
        if (covered || trans || (window && !win1)) return; covered = true; frontGen++; noteFrontInBurst(); rules.front(null, rowNow()); if (noticeTimer) noticeOwed = true; pumpSoon() }
    fun uncover() { if (!covered) return; covered = false; frontGen++
        if (!window) rules.front(bound, row) else if (win1 && l1Bound && staged1Key == win1Key) { noteFrontInBurst(); rules.front(bound1, row1) }
        pumpSoon() }
    fun openWindow() {
        if (window || covered || wheelOpen) return
        // K1-2 stage 4b: in a list-1 sequence an act's window has its own list most of the time
        if (list1 && !trans && l1Rnd.nextDouble() < 0.6) { openWindowList(l1Rnd.nextBoolean(), if (l1Rnd.nextInt(10) < 4) 200L + l1Rnd.nextInt(500) else 0L); return }
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
    /** C3 FZ5 (part BO (D)): per report, the gestures whose S lay in its gap (RIGHT took them; their 115 never came
     *  first); and the gestures whose stock message paid an owe of either kind. */
    val gapGestures = HashMap<Int, List<Int>>()
    val stockPaidAny = HashSet<Int>()
    private fun gapOf(g: Int, s: Int?, heardBefore: Int) {
        if (s == null || g < 0) return
        val gs = ArrayList<Int>()
        var k = (heardBefore + 1) and 0xFFFF
        while (k != s && LocalRules.after(s, k)) { bySR[k]?.let { gs += it }; k = (k + 1) and 0xFFFF }
        if (gs.isNotEmpty()) gapGestures[g] = gs
    }
    /** C3 FZ5: the staging holds a wrap record (true: stage 4's plain list, the default). False: a plain list at stage 5,
     *  where no staging holds one (`MainStaging`: `noWrapRecord` when `ring`, and a list whose paint does not wrap has
     *  none) — an edge goes as the phone's frames, as on a ring list here. */
    var wrapRecord = true
    /** C3 FZ5: scroll notches RIGHT fired, and those fired while a motion of the list (slot 0 or the wrap's) was still
     *  drawing on RIGHT (a step still to come) — the reach of the motion's length; read only, no draw. */
    var scrollFiresR = 0
    var scrollFiresInMotionR = 0
    /** [run] (K1-2 stage 4b, list-1 sequences): Back with a transition's run — Main bound at its end. The window list's
     *  view and staging are kept (decisions CX: kept across Main). */
    fun back(run: Long = 0L) {
        if (wheelOpen) { cancelWheel(); return }     // C3 DW-2a: a double tap inside the wheel cancels it (`Shell` 1777)
        if (!window) return
        val from1 = win1
        window = false; win1 = false; frontGen++
        if (from1) reach("return: list 1 → Main")
        if (run > 0 && list1 && !covered && !trans) { if (from1) reach("transition list 1 → Main (Back with a run)"); startRun(run); rules.front(null, row) }
        else if (!covered && !trans) { if (from1) slot0Changes(); rules.front(bound, row) }
        pumpSoon()
    }
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
        // K1-2 stage 4b: in a list-1 sequence Main is also made again under a window (Main's covered re-make): its LIST goes
        // when Main is next in front
        if (rules.burstOpen || covered || trans || (window && !list1)) return false
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
        if (!window && !wheelOpen) rules.front(bound, row, shift)       // C3 DW-2a: under the wheel too, its LIST at the return
        pumpSoon()
        return true
    }

    /** The scroll program rewritten into fresh space and SET again (P9: "a program through fresh space and a SET"); the
     *  staging is the same, so no LIST. */
    lateinit var scroll2: MotionProgram
    var scrollAlt = false
    /** C3 DM-1 (S6): the other pitch form's program ([pitchProbe]); [pitchAlt] the form in force; switches made. */
    lateinit var scrollB: MotionProgram
    var pitchAlt = false
    var pitchSwitches = 0
    /** C3 DM-1 (S6): the list switched between the two pitch forms, as K1-1's re-make switches when the effect changes (in
     *  idle, the list in front): the other form's program (in the cache already) and the list made again at the other
     *  pitch with the same identities and mark base — the rules owe its LIST (a fresh mark) ahead of the SETs in one
     *  batch, and the slot's DISCARD naming the row. */
    fun pitchSwitch(): Boolean {
        if (!pitchProbe || rules.burstOpen || covered || window) return false
        pitchAlt = !pitchAlt
        bound = boundFor(ids, markBase, tableAt[tableSlot])
        rules.front(bound, row)
        pitchSwitches++
        pumpSoon()
        return true
    }

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
    /** C3 FZ5 (part BO (B)): [bandIdIn] with [hole]'s cells left out (the badge drawn over the row). */
    fun bandIdIn(p: ByteArray, hole: Rect): Int {
        var l0 = -1
        for (y in BAND.y until BAND.bottom) for (x in BAND.x until BAND.right) {
            if (x >= hole.x && x < hole.right && y >= hole.y && y < hole.bottom) continue
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
        val resets0 = rightResets
        sim.conformanceMessage(arm, img, now)
        if (induce) sim.motionForTest(arm).holdWordForTest(false)
        if (arm == Arm.RIGHT && sim.bindForTest(arm).lists[0].cursor != cursor0) { rightResets++; rightResetAt += decided; rightResetLanded += now }
        else if (arm == Arm.RIGHT && !lens.refSeen && setBackUnderShift(img)) { rightResets++; rightResetAt += decided; rightResetLanded += now }
        // K1-2 stage 4b: a notch the phone applied to list 1 that the lens drew on list 0 ([crossHeld]) can cancel another
        // step there, so a DISCARD or SETTLE naming Main then sets RIGHT's list-0 cursor with its value unchanged — a reset
        // all the same (the carry rule's premise: a lost step still held); only after such a fire, so Main's own classes
        // read their resets as before
        else if (crossHeld && arm == Arm.RIGHT && !lens.refSeen && namesMainCursor(img)) { rightResets++; rightResetAt += decided; rightResetLanded += now }
        if (arm == Arm.RIGHT && rightResets != resets0) crossHeld = false
        val refusedNow = lens.refSeen
        if (!refusedNow) { lens.refSeen = kept[0] as Boolean; lens.refMode = kept[1] as Int; lens.refReason = kept[2] as Int; lens.refSeq = kept[3] as Long }
        trace?.invoke("[$now] $arm gets ${subsOf(img).joinToString(" ") { it.take(3).joinToString("") { b -> "%02x".format(b) } }}" +
            (if (refusedNow) " REFUSED mode ${lens.refMode and 0x7F} reason ${lens.refReason}" else "") + if (induce || tagged) " (induced)" else "")
        if (refusedNow) {
            val r = "$arm mode ${lens.refMode and 0x7F} reason ${lens.refReason}"
            if (induce || tagged) induced += r else refusals += r
            if (arm == Arm.RIGHT) rightRefusals += RightRefusal(lens.refMode and 0x7F, lens.refReason, lens.refSeq, induce || tagged, batch)
            // THE HARNESS'S CHOICE, NOT THE GLASSES' BEHAVIOUR (C3 X4M0, decisions BI (3)): a refusal on LEFT fails the flush
            // here — the shell rolls it back and, for a flush that carried the rules' messages, sends the table. On the glasses
            // the image success ack is sent before the decode is queued (`CLAIMS.md`, the row "The image success ack is sent
            // BEFORE the decode/present step is queued", V), so a sub-message refused on LEFT fails no ack and nothing tells the
            // phone; only a message LOST on LEFT fails the flush. Kept (decisions BI (3)): LEFT-only refusals of the rules'
            // messages have no producer beyond the start's arming race; what heals such a LIST on glass is the riding LIST
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
    /** K1-2 stage 4b: since RIGHT's last reset, a fire RIGHT drew on list 0 was applied by the phone to list 1. */
    private var crossHeld = false
    /** K1-2 stage 4b: [img] carries a DISCARD or SETTLE naming list 0 (byte 5 the list, as [setBackUnderShift] reads it). */
    private fun namesMainCursor(img: ByteArray): Boolean =
        subsOf(img).any { it[0].toInt() == 26 && it.size > 5 && (it[1].toInt() == 4 || it[1].toInt() == 5) && it[5].toInt() == 0 }
    /** Part BH: RIGHT's present count when the newest message it was handed was refused at the induced LIST after a SET. */
    private var listRefusedPresentR: Long? = null
    /** [img] carries a SET ahead of its refused LIST (the one [lose27] "list" gave count 0). */
    private fun setsBeforeRefusedList(img: ByteArray): Boolean {
        val subs = subsOf(img)
        val k = subs.indexOfFirst { it[0].toInt() == 27 && (it[1].toInt() == 2 || it[1].toInt() == CfwModes.BIND_LIST_RING) && it.size > 6 && it[5].toInt() == 0 && it[6].toInt() == 0 }
        return k > 0 && subs.take(k).any { it[0].toInt() == 27 && it[1].toInt() == 0 }
    }
    /** C3 FZ5 (seed 700697 at both real timings): RIGHT's cursor came out of [img] at the value it went in with although the
     *  message's LIST moved it by a non-zero shift — a DISCARD or SETTLE behind that LIST set it back, a reset of RIGHT's
     *  cursor that the value alone does not show (a lens-only draw's step it held is gone). Called only when the value did
     *  not change. */
    private fun setBackUnderShift(img: ByteArray): Boolean {
        val subs = subsOf(img)
        val k = subs.indexOfFirst { it[0].toInt() == 27 && (it[1].toInt() == 2 || it[1].toInt() == CfwModes.BIND_LIST_RING) && it.size > 8 && it[2].toInt() == 0 &&
            ((it[7].toInt() and 0xFF) or (it[8].toInt() shl 8)).toShort().toInt() != 0 }
        return k >= 0 && subs.drop(k + 1).any { it[0].toInt() == 26 && it.size > 5 && (it[1].toInt() == 4 || it[1].toInt() == 5) && it[5].toInt() == 0 }
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
    private val regions = listOfNotNull(BAND, BADGE, CLOCK, PROBE.takeIf { pitchProbe })

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
        if (pitchProbe) paint(m, PROBE, probeLevel(c.cursor))            // C3 DM-1: the ruler at the cursor
    }

    /** The drawing subs that make [m] equal [t] over [dirty] (in place on [m]), with their real rects. */
    private fun diff(m: ByteArray, t: ByteArray, dirty: Set<Rect>): List<Pair<ByteArray, Rect>> {
        val out = ArrayList<Pair<ByteArray, Rect>>()
        for (r in regions) {
            if (r !in dirty || !differs(m, t, r)) continue
            if (r == BAND) {
                val l = bandLevel()
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
        lane1()
        lane2()
        val rowF = rowNow()                             // K1-2 stage 4b: the row of the list in front (Main's without list 1)
        val t = truth()
        if (rollbackAll) { rollbackAll = false; java.util.Arrays.fill(model, 0x7F.toByte()); java.util.Arrays.fill(lastTruth, 0x7F.toByte()) }
        val changed = regions.filter { differs(t, lastTruth, it) }.toMutableSet()
        changed += held
        val noticeNow = noticeOwed && covered && noticeTimer
        if (rules.settleDue(now) && changed.isEmpty() && !rules.owed && !noticeNow) {
            val subs = rules.loneSettle(rowF, now)
            if (subs.isNotEmpty()) { loneSettles += now; emit(subs, emptyList()) }
            return
        }
        if (changed.isEmpty() && !rules.owed && !noticeNow) { rules.settleAt()?.let { s -> if (s > now) at(s) { pump() } }; return }
        val plan = rules.plan(rowF)
        val m2 = model.copyOf()
        val dirty = HashSet(changed)
        for (c in plan) { paintEnd(m2, c); dirty += BAND; dirty += BADGE; if (pitchProbe) dirty += PROBE }
        // decisions AZ.4 (the shell's `mainWrapPlan`): an edge at an end with the row now the other end — the scroll's PLAY
        // in slot 1 from the end to the row past it, END (the wrapped row's record) into the model, its areas held. None on
        // a ring list (C3 X2M0: a ring staging has no wrap pan; an edge there goes as the phone's frames)
        // K1-2 stage 4b: no wrap record or slot-1 PLAY for a window list (the K1-2 spec §8, Not built)
        val wrap = rules.wrapPlan(rowF)?.takeIf { !ring && wrapRecord && it.list.list == 0 }
        val wrapPlay = wrap?.let { w ->
            val prog = w.list.entries.first { it.step == w.step }.program!!
            val p = intArrayOf(recs[ids[w.cPrime.mod(ids.size)]], 0, 0, 0)
            paint(m2, BAND, 0); paint(m2, BAND, level(ids[w.cPrime.mod(ids.size)])); dirty += BAND; dirty += BADGE
            // C3 DM-1: the probe's ruler at the wrapped row (the phone's own PLAY: its parameters at c' mod n)
            if (pitchProbe) { w.list.paramsAt(w.cPrime.mod(ids.size), w.list.entries.first { it.step == w.step })?.let { p[1] = it[1] }
                paint(m2, PROBE, probeLevel(w.cPrime.mod(ids.size))); dirty += PROBE }
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
            Motion.play(notice, wm.damage.core.motion.PopupPrograms.SLOT, noticePeriod, 0xC000 + (noticePlays and 0xFFF), noticeCap4, IntArray(4))
        } else null
        held = (dirty intersect hold).filter { differs(m2, t, it) }.toSet()
        if (held.isNotEmpty()) heldBatches++
        val tail = listOfNotNull(wrapPlay) + rules.tail(rowF, now, emptySet(), frame = subs.isNotEmpty(), wrapped = wrapPlay != null) +
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
        // K1-2 stage 4b: a start ends a transition's run (its frames are gone with the keyframe); the lane's wake is put back
        if (trans) { trans = false; frontGen++ }
        if (list1 && ready1At != Long.MAX_VALUE && !l1Bound) at(maxOf(now, ready1At)) { pumpSoon() }
        // C3 DW-2a: a start clears the wheel's offer (`Shell.localSessionStart`, 3298); it is offered again once the block
        // is counted again — an open wheel left open across the start is phone-drawn and binds mid-open
        if (wheel && bound2 != null) { l2Bound = false; ready2At = maxOf(ready2At, now + laneDelay2()); at(ready2At) { pumpSoon() } }
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
            if (list1 && staged1Key >= 0) sim.conformanceMessage(a, write19(table1At[table1Slot], itemTable(staged1Ids)), now)
            if (wheel && bound2 != null) sim.conformanceMessage(a, write19(table2At[table2Slot], itemTable(staged2Ids)), now)
        }
        drainSent()
        model = t.copyOf(); lastTruth = t.copyOf(); held = emptySet()
        val s0 = if (fresh) 0 else sim.bindForTest(Arm.RIGHT).seq
        val front0 = if (covered || trans || wheelOpen) null else if (!window) bound else if (win1 && l1Bound && staged1Key == win1Key) bound1 else null
        rules.sessionStart(true, s0, now, front0, rowNow(), rules.taps)
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
        // C3 FZ5: the lone SETTLE goes in this first span — Q after the last report; Q is the floor at the short motion and
        // follows the motion in force otherwise ([LocalRules.qFor]), so the span keeps its slack past Q (the same 600 at Q's floor)
        runUntil(now + 600 + maxOf(0L, rules.q - LocalRules.Q_MS))
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

    fun cursors(list: Int = 0): Pair<Int, Int> = sim.bindForTest(Arm.LEFT).lists[list].cursor to sim.bindForTest(Arm.RIGHT).lists[list].cursor
}
