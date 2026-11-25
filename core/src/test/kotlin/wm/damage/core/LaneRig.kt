package wm.damage.core

import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Zl
import wm.damage.core.motion.Motion
import wm.damage.core.shell.LaneAt
import wm.damage.core.shell.LanePlan
import wm.damage.core.shell.LaneSpec
import wm.damage.core.shell.StagedRecord
import wm.damage.core.shell.WindowLanes
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb
import wm.damage.core.windows.music.MusicMotion

/**
 * Music's motion update, stage L: the held lanes' chain ([WindowLanes]) against the firmware model alone, on one virtual
 * clock (the phone's ms; the lenses' OS ticks are ms × 1.024) — no shell, no real time, no player. The rig is the shell's
 * glue in miniature: it pumps the chain, writes an area's record on both lenses and proves it, sends the chain's
 * PLAY / PLAY-AFTER to both lenses after an uplink delay and answers it after a downlink delay, carries RIGHT's motion
 * bodies back, and sends each pump's frame as the transport does ([frame]: its CLOSE-before-a-write rule on the lane's
 * slots, leaving open the slots the chain holds when the frame is submitted). A frame writes the cells of a chain that
 * ended (the release, or what a frame before left of it: [splitNext]) and, once a second while the song plays, the
 * time text — a cached glyph run, which the transport reads as reaching from its origin to the panel's edges. Two
 * layouts ([layout]): Now Playing's bar with the digits left of it, Music Mode's with the time line right of it. A song
 * plays from [songZero] on the phone's clock; [heard] is the equation's place.
 *
 * [record]: every message and wake the lenses take is kept as a conformance vector's ops (both lenses at one tick, so
 * a recorded run has fixed delays: [fixed]); a recorded run sends no time text (the vectors are the chain's shapes).
 */
internal class LaneRig(val seed: Long, val period: Int, val steps: Int, val fixed: Boolean = false, val record: Boolean = false) {
    val rnd = java.util.Random(seed)
    val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xDFF; it.clockIsOsTick = true }
    var nowMs = 1_000L
    fun tick(ms: Long = nowMs): Long = ms * 1024 / 1000
    val notes = ArrayList<String>()
    val lanes = WindowLanes { notes += "[$nowMs] $it" }
    val region = CfwModes.musicRegion(CfwModes.CACHE2_MAX_RIDERS, true)!!
    private val fx = Motion.effects(DamageMsg.Caps(2, 0xDFF), FLAGS)

    // ---- the window: a bar playing a song
    var bar = NP_BAR
    var d = 8
    var songZero = 0L
    var durMs = 200_000L
    var playing = true
    var keyGen = 0
    /** A box over the lane (the shell's gate says no lanes). */
    var boxOver = false
    fun heard(t: Long): Long = MusicMotion.heardAt(t, nowMs, place(), playing, 0)
    /** The song's place now: it stands during a stall ([stall]: the stream waits for data, the song still PLAYING). */
    fun place(): Long = stallPlace ?: (nowMs - songZero)
    private var stallPlace: Long? = null
    /** The window's stall rule, the product's own (`MusicGlasses.lanes`, the second review's finding 3). */
    private val still = MusicMotion.StillPlace(wm.damage.core.windows.music.MusicGlasses.SEEK_SLACK_MS)

    fun spec(): LaneSpec? {
        val stalled = still.stalled(nowMs, playing, place())
        if (!playing || boxOver || stalled) return null
        val grow = MusicMotion.barGrowth(bar.w, durMs, period, steps)
        val b = bar; val dur = durMs
        return LaneSpec(listOf(keyGen, b, dur), listOf(b), period, steps, listOf(MusicMotion.barRecord(b.w, b.h, 8))) { at: LaneAt ->
            LanePlan(MusicMotion.barProgram(at.at4, at.fx, at.statics[0], b, at.d, 1, grow, at.steps),
                MusicMotion.barParams(b, at.d, heard(at.stepOneMs), dur, grow, at.steps))
        }
    }

    // ---- the event queue
    private class Ev(val t: Long, val seq: Long, val f: () -> Unit)
    private var seq = 0L
    private val q = java.util.PriorityQueue<Ev>(compareBy<Ev>({ it.t }, { it.seq }))
    fun at(t: Long, f: () -> Unit) { q += Ev(t, seq++, f) }

    // ---- what the rig saw
    /** RIGHT's motion bodies: (slot, tag, outcome, drawn, folded) and when they were handled. */
    val bodies = ArrayList<IntArray>()
    /** Lane messages sent: (tag, slot, sent at, PLAY-AFTER). */
    val sent = ArrayList<LongArray>()
    var closesSent = 0
    var flushes = 0L
    /** A notice tag closed while drawing when no keyframe, release or CLOSE of its slot went: a lane ended it. */
    val noticeClosedByLane = ArrayList<String>()
    private var noticeMayClose = false
    var upExtraNext = 0L
    /** RIGHT's motion bodies are lost on the way (a dropped notification): only the mirror can tell an instance ended. */
    var dropBodies = false

    // ---- the vector's ops
    val steps0 = ArrayList<List<Pair<String, Any>>>()
    private fun rec(vararg ops: Pair<String, Any>) { if (record) steps0 += ops.toList() }

    private fun upLat(): Long = if (fixed) 20 else 10L + rnd.nextInt(30)
    /** The uplink keeps its order (one stream to the lenses): a message lands after every message sent before it. */
    /** When the last message on its way to the lenses lands: the uplink is drained once [nowMs] passes it. */
    var lastUp = 0L
        private set
    /** A session start drops what the old session still had on its way (a new link, a new lease). */
    private var linkGen = 0
    private fun up(extra: Long = 0, f: () -> Unit): Long {
        val t = maxOf(lastUp + 1, nowMs + upLat() + extra); lastUp = t
        val g = linkGen
        at(t) { if (g == linkGen) f() }
        return t
    }
    /** The last deliveries (a failure's trace). */
    val log = ArrayDeque<String>()
    /** Every lane tag sent before a release: none may stay open once the release's frame has landed. */
    val endedTags = HashSet<Int>()
    private fun downLat(): Long = if (fixed) 25 else 15L + rnd.nextInt(35)

    init {
        for (a in Arm.entries) {
            sim.conformanceLease(a, true, tick())
            sim.conformanceControl(a, DamageMsg.OP_CACHE_SIZE, 256, tick())
            sim.conformanceControl(a, DamageMsg.OP_FLAGS_SET, FLAGS, tick())
            sim.conformancePage(a, true)
            sim.conformanceMessage(a, KEYFRAME, tick())
        }
        rec("tick" to tick(), "lease" to "acquire", "cachesize" to 256, "flags" to FLAGS, "page" to true, "msg" to hex(KEYFRAME))
        drain()
        renewLease()
        if (!record) timeText()
    }

    /** The transport renews the lease every 45 s against the firmware's 87.9-s expiry (`CLAUDE.md`); a renewal of a live
     *  lease keeps the cache. (A recorded vector is seconds long: it never reaches one.) */
    private fun renewLease() {
        at(nowMs + 45_000) { val g = linkGen; up { if (g == linkGen) for (a in Arm.entries) sim.conformanceLease(a, true, tick()) }; renewLease() }
    }

    /** Both lenses take [msg] now (the recorded form: one tick, both lenses). */
    fun deliver(msg: ByteArray) {
        log.addLast("[$nowMs] " + hex(msg.copyOf(minOf(msg.size, 12)))); while (log.size > 24) log.removeFirst()
        for (a in Arm.entries) sim.conformanceMessage(a, msg, tick())
        rec("tick" to tick(), "msg" to hex(msg))
        drain()
    }

    private fun drain() {
        sim.conformanceSent(Arm.LEFT)
        for ((field, body) in sim.conformanceSent(Arm.RIGHT)) {
            if (field != 114) continue
            val m = Pb.fields(body).associate { it.field to (it.varint ?: 0L) }
            val slot = m[2]?.toInt() ?: -1; val tag = m[3]?.toInt() ?: -1; val outcome = m[4]?.toInt() ?: -1
            bodies += intArrayOf(slot, tag, outcome, (m[6] ?: -1).toInt(), (m[7] ?: -1).toInt())
            if (tag and 0xF000 == NOTICE_TAG && outcome == 1 && !noticeMayClose) noticeClosedByLane += "[$nowMs] notice 0x%04x closed while drawing".format(tag)
            if (tag and 0xF000 == WindowLanes.TAG && !dropBodies) {
                val st = sent.lastOrNull { it[0].toInt() == tag }
                val word = when (outcome) { 0 -> "drew"; 1 -> "closed"
                    2 -> "refused: reason ${m[10]} (${if (st?.get(3) == 1L) "PLAY-AFTER" else "PLAY"}, song at ${heard(nowMs) / 1000} s of ${durMs / 1000} s)"
                    else -> "outcome $outcome" }
                val drawn = m[6]; val folded = m[7]; val us = m[9]
                at(nowMs + downLat()) { lanes.notify(tag, word, drawn, folded, us); pump() }
            }
        }
    }

    /** No more pumps: what was sent plays out (a recorded vector's end). */
    var frozen = false

    /** The next release's frame is cut by the budget: the lane's cells go in the frame of the pump after (the
     *  compositor's residual), and are unwritten until that frame is submitted. */
    var splitNext = false
    private val residual = ArrayList<Pair<Rect, Rect>>()
    /** Pumps where a chain ended and the next started (the frame between them holding its slots); pumps where the next
     *  started in the pump after the one that ended a chain, with no area written between (the area already proven);
     *  releases whose cells a later frame wrote. */
    var startsInTheEndingPump = 0
    var startsAtTheNextPump = 0
    var splits = 0
    private var endedAtPump = -1L
    private var pumps = 0L
    private var writesAtEnd = 0

    /** The shell's pump: the chain's step, this pump's frame (what it writes, held as the chain holds its slots once its
     *  step is taken), an area's write, a message, the next wake. [paint]: the time text repaints in this pump. */
    fun pump(paint: Boolean = false) {
        if (frozen) return
        pumps++
        val residualNow = residual.toList()
        lanes.pump(WindowLanes.Env(nowMs, spec(), if (boxOver) "a notice box over a lane" else if (!playing) "paused" else null, d, region, fx,
            written = { it !in residual }) { slot ->
            sim.motionTagIn(Arm.RIGHT, slot)
        })
        val held = lanes.heldSlots
        val writes = ArrayList<Pair<Rect, Rect>>()
        writes += residualNow; residual.removeAll(residualNow)
        val ended = lanes.takeRelease()
        ended?.let { rel ->
            endedTags += sent.map { it[0].toInt() }
            endedAtPump = pumps; writesAtEnd = areaWrites()
            if (splitNext) { splitNext = false; splits++; residual += rel; at(nowMs) { pump() } } else writes += rel
        }
        if (paint) writes += digits()
        frame(writes, held, repump = ended != null || residualNow.isNotEmpty())
        lanes.wanted()?.let { r ->
            r.state = StagedRecord.State.SENT
            val w = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(r.off / 4, r.encoded)), CfwModes.CACHE2_MAX_RIDERS)
            val landed = up { deliver(w) }
            at(landed + downLat() + 40) { if (r.state == StagedRecord.State.SENT) { r.state = StagedRecord.State.PROVEN; pump() } }
        }
        lanes.takeSend()?.let { m ->
            val id = ++flushes
            val tag = ((m[6].toInt() and 0xFF) or ((m[7].toInt() and 0xFF) shl 8)).toLong()
            val after = m[1].toInt() == CfwModes.MOTION_PLAY_AFTER
            if (!after && ended != null) startsInTheEndingPump++
            if (!after && ended == null && endedAtPump >= 0 && pumps - endedAtPump <= 3 && areaWrites() == writesAtEnd) startsAtTheNextPump++
            if (!after) endedAtPump = -1
            sent += longArrayOf(tag, m[2].toLong(), nowMs, if (after) 1 else 0)
            lanes.sent(id)
            val extra = upExtraNext
            upExtraNext = 0
            up(extra) {
                deliver(m)
                val back = nowMs + downLat()
                val w = nowMs
                val g = linkGen
                at(back) { if (g == linkGen) { lanes.flushDone(id, true, nowMs, w, back); pump() } }
            }
        }
        lanes.wakeAt?.let { w -> at(w) { pump() } }
    }

    private fun areaWrites() = notes.count { "written for its programs" in it }

    /** The frame of one pump, as `CfwTransportBase.withMotionCloses` sends it (its meet rule, `motionClosesMet`): a CLOSE of each lane slot whose open
     *  instance (its areas on the lens: the owned rect, or the program's areas while waiting) one of [writes] meets, on
     *  either lens, unless the slot is in [held] (`FlushRequest.held`, fixed at the submit); decided when the frame is
     *  encoded, in stream order (the model is the mirror). The notice's slot is left to its own close pass (its grace
     *  under the time text is declined, `MM-decisions.md`). [repump]: the frame carried a lane's cells — its completion
     *  pumps again, as the shell's FlushDone does. */
    private fun frame(writes: List<Pair<Rect, Rect>>, held: Set<Int>, repump: Boolean) {
        if (writes.isEmpty()) return
        val landed = up {
            // the lane's instances on each lens, met by the transport's own rule (the second review's finding 4)
            val open = Arm.entries.associateWith { a -> sim.motionWindows(a).filter { w -> w.slot in 0..1 && sim.motionTagIn(a, w.slot)?.let { it and 0xF000 == WindowLanes.TAG } == true } }
            val slots = wm.damage.core.transport.CfwTransportBase.motionClosesMet(open, writes, held)
            if (slots.isNotEmpty()) { closesSent++; deliver(CfwModes.batch(slots.map { CfwModes.motionClose(it) }, engine = true)) }
        }
        if (repump) at(landed + downLat()) { pump() }
    }

    /** The time text as the transport reads its write: a cached glyph run, from its origin to the panel's edges
     *  (`CfwTransportBase.motionWriteRects`, DrawText2) — Now Playing's elapsed digits left of the bar and above its
     *  row, Music Mode's time line right of its bar. */
    private fun digits(): Pair<Rect, Rect> {
        val (x, y) = if (bar == NP_BAR) (bar.x - 64) to (bar.y - 6) else (bar.right + 12) to (bar.y - 2)
        fun toEdge(x0: Int) = Rect(maxOf(0, x0), y, wm.damage.core.geom.Geometry.PANEL_W - maxOf(0, x0), wm.damage.core.geom.Geometry.PANEL_H - y)
        return toEdge(x - d) to toEdge(x + d)
    }

    /** The time text's once-a-second repaint while the song plays (none in a recorded run). */
    private fun timeText() {
        at((nowMs / 1000 + 1) * 1000) { if (playing) pump(paint = true); timeText() }
    }

    /** Now Playing ↔ Music Mode: the bar moves to the other layout's rect (a new key; the chain ends). */
    fun layout() { bar = if (bar == NP_BAR) MM_BAR else NP_BAR; keyGen++; pump() }

    /** Runs every event due up to [until]; the lenses wake each 8 ticks while an instance is due. */
    fun runUntil(until: Long) {
        while (true) {
            val e = q.peek()
            val next = nextWake()
            if (e != null && e.t <= until && (next == null || e.t <= next)) { q.poll(); nowMs = maxOf(nowMs, e.t); e.f(); continue }
            if (next == null || next > until) { nowMs = until; return }
            nowMs = next
            for (a in Arm.entries) sim.conformanceWake(a, tick())
            rec("tick" to tick(), "wake" to true)
            drain()
        }
    }

    /** The next whole period of the engine while any instance is drawing or waiting (ms), or null. */
    private fun nextWake(): Long? {
        val busy = Arm.entries.any { a -> sim.motionForTest(a).slots.any { it != null && it.drawing } }
        if (!busy) return null
        val stepMs = maxOf(4L, period * 1000L / 1024 / 2)
        return (nowMs / stepMs + 1) * stepMs
    }

    // ---- the events
    fun seek(byMs: Long) { songZero -= byMs; keyGen++; pump() }
    fun pause() { playing = false; pump() }
    fun play() { playing = true; keyGen++; pump() }
    fun track() { stallPlace = null; songZero = nowMs; durMs = 60_000L + rnd.nextInt(300_000); keyGen++; pump() }
    /** The stream stops giving data while the song plays: the place stands ([unstall] moves it on from there). */
    fun stall() { stallPlace = place(); pump() }
    /** Data again: the place moves on from where it stood — the song's zero moved by the stall (a new key, as the window's
     *  zero moving past its paint makes one). */
    fun unstall() { val p = stallPlace ?: return; stallPlace = null; songZero = nowMs - p; keyGen++; pump() }

    /** A keyframe: it closes every instance on both lenses (§5.6); the shell tells the chain. */
    fun keyframe() {
        up { noticeMayClose = true; deliver(KEYFRAME); noticeMayClose = false }   // in stream order, as the shell's flush
        lanes.keyframed(); pump()                                                 // told at its submit, as the shell does
    }

    /** A session start after a release point: the lease again frees the cache and ends every instance. */
    fun session() {
        linkGen++
        noticeMayClose = true
        for (a in Arm.entries) {
            sim.conformanceLease(a, false, tick()); sim.conformanceLease(a, true, tick())
            sim.conformanceControl(a, DamageMsg.OP_CACHE_SIZE, 256, tick())
            sim.conformanceControl(a, DamageMsg.OP_FLAGS_SET, FLAGS, tick())
            sim.conformanceMessage(a, KEYFRAME, tick())
        }
        drain()
        noticeMayClose = false
        noticeWritten = false
        lanes.reset("session start"); pump()
    }

    private var noticeWritten = false
    private var noticeTags = 0
    /** The notice's PLAY in slot 2: [near] its box meets the lane's meeting box (the shell then asks no lanes first). */
    fun notice(near: Boolean) {
        val progs = listOf(Rect(100, 40, 248, 80), Rect(bar.x, bar.y - 30, 248, 40)).mapIndexed { i, box ->
            Motion.program(NOTICE_AT4 + 64 * i) { track(box, Motion.Backdrop.Level(3)) { fill(9, 16) } }
        }
        // written once a session: a write meeting an open instance's read range would close it (§5.6)
        if (!noticeWritten) { val w = CfwModes.cacheUpdate2(progs.flatMap { p -> p.records.map { (o, b) -> CfwModes.CacheWrite2(o, b) } }, CfwModes.CACHE2_MAX_RIDERS); up { deliver(w) }; noticeWritten = true }
        val play = Motion.play(progs[if (near) 1 else 0], 2, 34, NOTICE_TAG + (noticeTags++ and 0xFFF), 0, IntArray(4))
        // a notice's PLAY replaces the one before it in slot 2 (its own close pass, not a lane's)
        up { noticeMayClose = true; deliver(play); noticeMayClose = false }
    }

    /** A PLAY in slot 3 over the bar (a stand-in for anything that closes the chain on the glasses): its close pass ends
     *  the lane's instances on both lenses. */
    fun foreignClose() {
        val prog = Motion.program(NOTICE_AT4 + 128) { track(Rect(bar.x, bar.y, 64, bar.h), Motion.Backdrop.Level(2)) { fill(4, 4) } }
        val w = CfwModes.cacheUpdate2(prog.records.map { (o, b) -> CfwModes.CacheWrite2(o, b) }, CfwModes.CACHE2_MAX_RIDERS)
        up { deliver(w) }
        up { deliver(Motion.play(prog, 3, 34, 0x1234, 0, IntArray(4))) }
    }

    /** The tags sent for chains that have ended (every lane tag sent but the open chain's own). */
    fun endedTagsNow(): Set<Int> = sent.map { it[0].toInt() }.toSet() - lanes.openTags.map { it.second }.toSet()

    /** The notice in slot 2 is still drawing on RIGHT. */
    fun noticeDrawing(): Boolean = sim.motionForTest(Arm.RIGHT).slots.getOrNull(2)?.let { it.drawing && it.tag and 0xF000 == NOTICE_TAG } == true

    /** Lane tags open in slots 0 and 1 on either lens. */
    fun laneTagsOpen(): List<Int> = Arm.entries.flatMap { a -> (0..1).mapNotNull { s -> sim.motionTagIn(a, s)?.takeIf { it and 0xF000 == WindowLanes.TAG } } }

    fun vector(name: String): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("name", kotlinx.serialization.json.JsonPrimitive(name))
        put("contract", kotlinx.serialization.json.JsonPrimitive(2))
        put("start", kotlinx.serialization.json.JsonPrimitive("zero"))
        put("panel", kotlinx.serialization.json.JsonPrimitive(true))
        put("features", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(0xDFF))))
        put("steps", kotlinx.serialization.json.JsonArray(steps0.map { ops ->
            kotlinx.serialization.json.buildJsonObject {
                put("ops", kotlinx.serialization.json.JsonArray(ops.map { (k, v) -> kotlinx.serialization.json.buildJsonObject {
                    put(k, when (v) { is Int -> kotlinx.serialization.json.JsonPrimitive(v); is Long -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Boolean -> kotlinx.serialization.json.JsonPrimitive(v); else -> kotlinx.serialization.json.JsonPrimitive(v.toString()) })
                } }))
                put("expect", kotlinx.serialization.json.JsonObject(emptyMap()))
            }
        }))
    }

    companion object {
        /** Ladder stage 5's flags (`FIRMWARE.md` §7.9): DRAW2, ENGINE, BIND, ZWRITE, EFFECTS. */
        const val FLAGS = 0x7C
        const val NOTICE_TAG = 0xE000
        /** Now Playing's bar and Music Mode's: apart, so a PLAY's close pass for one never meets the other's instances. */
        val NP_BAR = Rect(100, 300, 400, 10)
        val MM_BAR = Rect(220, 120, 200, 6)
        /** The notice's program in the notice region (above the lanes' slice). */
        val NOTICE_AT4 = (CfwModes.noticeRegion(CfwModes.CACHE2_MAX_RIDERS, true)!!.first) / 4
        val KEYFRAME: ByteArray = CfwModes.keyframe(Zl.encodeCfw(ByteArray(320 * 480)))
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
