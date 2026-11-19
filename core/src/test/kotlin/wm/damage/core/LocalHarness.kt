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
class LocalHarness(val seed: Long, taps0: Taps = Taps.REPORT, val n0: Int = 8, val freshAtStarts: Boolean = true) {
    val rnd = java.util.Random(seed)
    val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x5FF; it.clockIsOsTick = true }
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
        const val T_SCROLL = 3
        const val T_PRESS = 2
        const val W = 640; const val H = 480
        fun level(id: Int) = 2 + id
        const val ARMED = (1 shl 2) or (1 shl 3) or (1 shl 4)
        const val BAND_MS = 200L
        /** A telemetry read's round trip, fixed (no draw from the sequence's random stream). */
        const val READ_MS = 60L
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
                draw(recSources[0], BAND.x, BAND.y, n = T_SCROLL, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
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
                draw(recSources[0], BAND.x, BAND.y, n = T_SCROLL, prefs = listOf(Motion.Pref(0, Motion.Field.SRC)))
            }
        }
        put(scroll2.bytes)
        cacheWrites += write19(scroll2.at, scroll2.bytes)
        cacheWrites += write19(scroll.at, scroll.bytes)
        cacheWrites += write19(press.at, press.bytes)
    }

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
            BoundList.Entry(0, CfwModes.EV_UPROLL, +1, PERIOD, sp().at, p, sp()),
            BoundList.Entry(1, CfwModes.EV_DOWNROLL, -1, PERIOD, sp().at, p, sp()),
            BoundList.Entry(2, CfwModes.EV_TAP, 0, PERIOD, press.at, p, press)))
    }

    private fun sp() = if (scrollAlt) scroll2 else scroll

    private fun itemTable(ids: IntArray) = ByteArray(2 * ids.size).also { b -> ids.forEachIndexed { i, id -> b[2 * i] = (recs[id] and 0xFF).toByte(); b[2 * i + 1] = (recs[id] shr 8).toByte() } }

    /** The phone's truth (levels, one per pixel; the list is flat, so both lenses alike). */
    fun truth(): ByteArray {
        val t = ByteArray(W * H)
        fun fill(r: Rect, l: Int) { for (y in r.y until r.bottom) for (x in r.x until r.right) t[y * W + x] = l.toByte() }
        fill(BAND, if (covered || window) MODAL else level(ids[row]))
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
               var shownR: Int? = null)

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
            if (e != null && e.t <= until && e.t <= nextWake) { q.poll(); now = maxOf(now, e.t); e.run(); drainSent(); continue }
            if (nextWake > until) { now = until; break }
            now = nextWake
            wakeBoth()
        }
    }

    private fun wakeBoth() {
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
                if (slot == WRAP_SLOT && outcome in 0..1) up(now) { wrapOpen = false }
                up(now) { rules.body114(slot, tag, outcome, m[10]?.toInt()) }
                // a refused COMMIT names its S as the tag: the mirror took it, so the transport calls it a divergence
                // and the shell keyframes (`CfwTransportBase.onMotionNotify`); a DISCARD's or SETTLE's names none
                if (outcome == 2 && m.containsKey(3) && slot in 0..3) up(now) { keyframe("a COMMIT refused on RIGHT") }
            }
            if (field == 115) {
                val a = DamageMsg.Applied(m)
                val g = a.s?.let { bySR[it] } ?: -1
                if (loseNext115 > 0) { loseNext115--; continue }
                if (rnd.nextDouble() < lose115) continue
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
        // a scripted [band] is taken as given; otherwise the band is RIGHT's own state, decided at RIGHT's fire below
        val bandNow = band
        fun input(arm: Arm) {
            val b = sim.bindForTest(arm)
            val before = b.seq
            if (holdWordOn == arm) sim.motionForTest(arm).holdWordForTest(true)
            if (arm == Arm.RIGHT) f.shownR = shownOnRight()
            sim.conformanceInput(arm, dev, id.toLong(), 0, now)
            if (holdWordOn == arm) sim.motionForTest(arm).holdWordForTest(false)
            sim.conformanceStockCalls(arm)
            val s = if (b.seq != before) b.seq else null
            if (arm == Arm.LEFT) f.sL = s else { f.sR = s; s?.let { bySR[it] = g }; f.cursorR = b.lists[0].cursor; f.markR = b.lists[0].mark }
            if (arm == Arm.LEFT && (covered || window) && sim.motionForTest(arm).slots.any { it?.local == true && it.tag == s }) localsUnderCover++
        }
        trace?.invoke("[$now] gesture g$g kind $kind answer $answer band $bandNow hold $holdWordOn")
        input(Arm.LEFT)
        drainSentLeftOnly()
        if (!answer) return g
        val tR = now + lensGap()
        at(tR) {
            input(Arm.RIGHT)
            drainSent()
            // stock's own message, after the 115 (one FIFO, §6.1): a scroll message after the exchange, a click in the turn
            val st = if (tap) LocalRules.Stock.CLICK else if (id == CfwModes.EV_UPROLL) LocalRules.Stock.NEXT else LocalRules.Stock.PREV
            val extra: Long? = when {
                bandNow == "drop" -> null
                bandNow == "delay" -> bandDelay()
                bandNow?.startsWith("delay:") == true -> bandNow.removePrefix("delay:").toLong()
                bandNow != null -> 0L
                else -> band(tap)
            }
            if (extra != null) up(now, extra, scroll = !tap) { phoneStock(st, g) }
        }
        return g
    }

    /** RIGHT's rubber band (§6.1), modeled: a notch that finds no band running sends its scroll message at once and starts
     *  a band of [BAND_MS]; one inside a band either sends none ([bandDropScroll]) or queues it to a band's end, one per
     *  band end, each starting the next band; a single tap inside a band sends no click. Returns the message's extra delay,
     *  or null for none. (I: the band's length and which of drop and queue happens are not measured.) */
    private var bandUntil = 0L
    fun band(tap: Boolean): Long? {
        if (tap) return if (now < bandUntil) null else 0L
        if (now >= bandUntil) { bandUntil = now + BAND_MS; return 0L }
        if (rnd.nextDouble() < bandDropScroll) return null
        val at = bandUntil
        bandUntil = at + BAND_MS
        return at - now
    }

    private fun drainSentLeftOnly() { sim.conformanceSent(Arm.LEFT) }

    // ---- the phone's side of the link
    private fun move(g: Int, newRow: Int) { if (newRow != row) { moves[g] = (moves[g] ?: 0) + 1; row = newRow } }

    private fun phoneReport(a: DamageMsg.Applied, g: Int) {
        beforeReport?.let { beforeReport = null; it() }
        if (g >= 0 && g !in reportHeardAt) reportHeardAt[g] = now
        val v = rules.report(a, row, now)
        trace?.invoke("[$now] phone 115 g$g ${a.describe()} row $row → $v")
        verdicts += g to v
        apply(v, g, a)
        afterReport?.let { afterReport = null; it() }
        pumpSoon()
    }

    private fun phoneStock(st: LocalRules.Stock, g: Int) {
        val v = rules.stock(st, row, now)
        // decisions BC: a stock message applied as today whose own report was heard before a SETTLE that went before it —
        // an echo later than its burst's SETTLE (its owe was cleared there); the fuzz counts the (8) it causes apart
        if (v is LocalRules.Verdict.Apply && reportHeardAt[g]?.let { lastSettleAt > it } == true) lateEchoes += g
        trace?.invoke("[$now] phone stock g$g $st row $row → $v")
        verdicts += g to v
        apply(v, g, null)
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
    fun cover() { if (covered || window) return; covered = true; rules.front(null, row); pumpSoon() }
    fun uncover() { if (!covered) return; covered = false; if (!window) rules.front(bound, row); pumpSoon() }
    fun openWindow() {
        if (window || covered) return
        // decisions AW.3, as the shell does it: a window over the list while a COMMITted instance still draws in the slot —
        // a CLOSE of the slot goes first, alone, ahead of the window's frame
        val inst = sim.motionForTest(Arm.RIGHT).slots[0]
        if (inst != null && inst.drawing && inst.local && inst.tag in commitsSent) { emit(listOf(CfwModes.motionClose(0)), emptyList()); windowCuts++ }
        window = true; rules.front(null, row); pumpSoon()
    }
    /** Windows that cut a COMMITted motion (AW.3), for the fuzz's counted shapes. */
    var windowCuts = 0
    /** Decisions AZ.4: the wrap's PLAYs sent; whether the phone holds one open (until RIGHT's body for it or a keyframe);
     *  the batches that closed one to draw in its areas. */
    var wrapPlays = 0
    var wrapOpen = false
    var wrapCuts = 0
    /** Decisions BC: when the phone handled each gesture's report; when it last sent a SETTLE; the gestures whose stock
     *  message came after a SETTLE that followed their report and was applied as today. */
    val reportHeardAt = HashMap<Int, Long>()
    var lastSettleAt = Long.MIN_VALUE
    val lateEchoes = HashSet<Int>()
    fun back() { if (!window) return; window = false; if (!covered) rules.front(bound, row); pumpSoon() }
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
    /** The mode-27 subs of [kind] ("set", "clear", "list") in the next batch that carries one do not land on [arms] (the
     *  first of them, or every one with [lose27All]). */
    var lose27: Pair<String, Set<Arm>>? = null
    var lose27All = false
    /** The next batch carrying a mode 27 is refused on [arm] (its word held at the sub). */
    var refuse27On: Arm? = null
    /** The next batch carrying any mode-27 or mode-26 sub 3..5 is refused on [arm] at its first such sub. */
    var refuseTailOn: Arm? = null
    /** The next batch carrying a COMMIT, DISCARD or SETTLE does not land on [loseAnswerOn] (LEFT among them: the write is
     *  lost and its ack fails; RIGHT alone: the inter-lens forward is lost, which nobody hears). */
    var loseAnswerOn: Set<Arm>? = null

    private var lastDown = 0L
    private fun sendDown(image: ByteArray, perArm: Map<Arm, ByteArray?> = emptyMap(), inducedOn: Arm? = null) {
        val arrive = maxOf(lastDown + 1, now + downLat())
        lastDown = arrive
        sent++
        at(arrive) {
            for (arm in listOf(Arm.LEFT, Arm.RIGHT)) {
                val img = if (perArm.containsKey(arm)) perArm[arm] else image
                if (img == null) continue
                deliver(arm, img, inducedOn == arm)
            }
        }
    }

    private fun deliver(arm: Arm, img: ByteArray, induce: Boolean) {
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
        sim.conformanceMessage(arm, img, now)
        if (induce) sim.motionForTest(arm).holdWordForTest(false)
        val refusedNow = lens.refSeen
        if (!refusedNow) { lens.refSeen = kept[0] as Boolean; lens.refMode = kept[1] as Int; lens.refReason = kept[2] as Int; lens.refSeq = kept[3] as Long }
        trace?.invoke("[$now] $arm gets ${subsOf(img).joinToString(" ") { it.take(3).joinToString("") { b -> "%02x".format(b) } }}" +
            (if (refusedNow) " REFUSED mode ${lens.refMode and 0x7F} reason ${lens.refReason}" else "") + if (induce) " (induced)" else "")
        if (refusedNow) {
            val r = "$arm mode ${lens.refMode and 0x7F} reason ${lens.refReason}"
            if (induce) induced += r else refusals += r
            // the image lane writes LEFT (`CfwTransportBase.writeImage`), so the flush's ack is LEFT's [I]: a refusal there
            // fails the flush — the shell rolls it back and, for a flush that carried the rules' messages, sends the table
            if (arm == Arm.LEFT) up(now) { failed(img, "refused ($r)") }
            refusalStreak = if (arm == Arm.LEFT) refusalStreak + 1 else refusalStreak
            maxRefusalStreak = maxOf(maxRefusalStreak, refusalStreak)
        } else if (arm == Arm.LEFT) refusalStreak = 0
        drainSent()
        traceOutcome()
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
        if (rules.settleDue(now) && changed.isEmpty() && !rules.owed) {
            val subs = rules.loneSettle(row, now)
            if (subs.isNotEmpty()) emit(subs, emptyList())
            return
        }
        if (changed.isEmpty() && !rules.owed) { rules.settleAt()?.let { s -> if (s > now) at(s) { pump() } }; return }
        val plan = rules.plan(row)
        val m2 = model.copyOf()
        val dirty = HashSet(changed)
        for (c in plan) { paintEnd(m2, c); dirty += BAND; dirty += BADGE }
        // decisions AZ.4 (the shell's `mainWrapPlan`): an edge at an end with the row now the other end — the scroll's PLAY
        // in slot 1 from the end to the row past it, END (the wrapped row's record) into the model, its areas held
        val wrap = rules.wrapPlan(row)
        val wrapPlay = wrap?.let { w ->
            val prog = w.list.entries.first { it.step == w.step }.program!!
            val p = intArrayOf(recs[ids[w.cPrime.mod(ids.size)]], 0, 0, 0)
            paint(m2, BAND, 0); paint(m2, BAND, level(ids[w.cPrime.mod(ids.size)])); dirty += BAND; dirty += BADGE
            Motion.play(prog, WRAP_SLOT, PERIOD, 0xB000 + (wrapPlays and 0xFFF), 0, p)
        }
        val wrapAreas = wrap?.list?.areas ?: emptyList()
        // decisions AA.1: with a COMMIT planned, a region meeting its areas is not drawn in this batch; what still differs
        // from the truth once END is in the model goes in the next one
        val hold = if (plan.isEmpty() && wrap == null) emptySet() else regions.filter { r -> plan.any { c -> LocalRules.meets(listOf(r), listOf(r), c.areas) } ||
            LocalRules.meets(listOf(r), listOf(r), wrapAreas) }.toSet()
        var subs = diff(m2, t, dirty - hold)
        // the transport's rule (`CfwTransportBase.withMotionCloses`): a batch drawing inside the wrap's instance while the
        // phone holds it open CLOSEs it first
        if (wrapOpen && subs.any { it.second.overlaps(BAND) }) { subs = listOf(CfwModes.motionClose(WRAP_SLOT) to BAND) + subs; wrapOpen = false; wrapCuts++ }
        held = (dirty intersect hold).filter { differs(m2, t, it) }.toSet()
        if (held.isNotEmpty()) heldBatches++
        val tail = listOfNotNull(wrapPlay) + rules.tail(row, now, emptySet(), frame = subs.isNotEmpty(), wrapped = wrapPlay != null)
        if (wrapPlay != null) { wrapOpen = true; wrapPlays++ }
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
        for (s in tail) if (s[0].toInt() == 27 && s[1].toInt() == 2) stagings[s[11].toInt() and 0xFF] = ids.copyOf()
        emit(tail, subs.map { it.first })
        if (held.isNotEmpty()) pumpSoon()
        rules.settleAt()?.let { s -> if (s > now) at(s) { pump() } }
    }

    private fun emit(tail: List<ByteArray>, draw: List<ByteArray>) {
        val all = LocalRules.batchOrder(draw, tail)
        if (all.isEmpty()) return
        lastBatch = all
        for (s in tail) if (s[0].toInt() == 26 && s[1].toInt() == 3) commitsSent += (s[3].toInt() and 0xFF) or ((s[4].toInt() and 0xFF) shl 8)
        if (tail.any { it[0].toInt() == 26 && it[1].toInt() == 5 }) lastSettleAt = now
        fun image(subs: List<ByteArray>): ByteArray? = when {
            subs.isEmpty() -> null
            subs.size == 1 && (subs[0][0].toInt() == 26 || subs[0][0].toInt() == 27) -> subs[0]
            else -> CfwModes.batch(subs, engine = true, bind = true)
        }
        val per = HashMap<Arm, ByteArray?>()
        val l27 = lose27
        if (l27 != null) {
            val code = when (l27.first) { "set" -> 0; "clear" -> 1; else -> 2 }
            val idx = all.indices.filter { all[it][0].toInt() == 27 && all[it][1].toInt() == code }.let { if (lose27All) it else it.take(1) }
            if (idx.isNotEmpty()) {
                lose27 = null; lossyMessages++
                for (a in l27.second) per[a] = image(all.filterIndexed { i, _ -> i !in idx })
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
        sendDown(image(all)!!, per, inducedOn)
    }

    // ---- telemetry
    private var lastRef: Triple<Int, Int, Long>? = null
    private val telem = ArrayList<ByteArray>()
    fun readTelemetry() {
        telem.clear()
        sim.conformanceControl(Arm.RIGHT, DamageMsg.OP_TELEMETRY, 9, now)
        val f = AaFrame.Reassembler { }.offer(telem.single()) ?: return
        val t = DamageMsg.parseTelemetry(f.payload) ?: return
        val ref = t.refusal
        val rec = ref?.let { Triple(it.mode and 0x7F, it.reason, it.seq) }
        val fresh = rec != null && rec != lastRef
        lastRef = rec
        rules.telemetry(t.boundIds, if (fresh && rec!!.first == 27) ref else null,
            if (fresh && rec!!.first == 8 && rec.second == 14) ref else null)
        pumpSoon()
    }

    // ---- a session start (P1): keyframe, the arming, the start's bind batch
    fun start(fresh: Boolean) {
        q.clear(); lastUp = now; lastScrollUp = now; lastDown = now; pumpAt = Long.MAX_VALUE
        if (fresh) for (a in Arm.entries) { sim.conformanceLease(a, false, now); sim.conformanceLease(a, true, now) }
        for (a in Arm.entries) sim.conformanceControl(a, DamageMsg.OP_FLAGS_SET, ARMED, now)
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
