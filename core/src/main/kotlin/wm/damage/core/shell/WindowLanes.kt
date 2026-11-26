package wm.damage.core.shell

import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.motion.Motion
import wm.damage.core.motion.MotionProgram
import wm.damage.core.wire.CfwModes

/**
 * Music's motion update, stage L (`private/build085/MM-lean-spec.md` §4.1–§4.3; his answers, `MM-decisions.md`): a
 * window's HELD LANES — rects the glasses animate on their own clock while the window keeps painting as today.
 *
 * **What a window declares** ([DamageWindow.lanes], [LaneSpec]): while it is in front and no list of it is bound, its
 * lanes' rects (nominal, at the window's plane), a key whose change means "plan again" (for Music the track, the play
 * state, a seek, the output, the layout), the chain's period and steps, the pictures its programs draw, and a planner
 * that gives one stretch's program (built through the Motion DSL only) and its four parameters from the phone time its
 * step 1 is visible at.
 *
 * **What the chain is** ([WindowLanes]): one version-2 OWN program a stretch, every lane in it, no LAND — so nothing is
 * written into the shadow and the window's picture under the lane stays there (`FIRMWARE.md` §7.3). The first stretch is
 * a PLAY in slot 0; each next one a PLAY-AFTER into the other slot naming the drawing one (§7.7), sent when the drawing
 * stretch has half its length left, with its records written and proven first (the stage lane and the cache ledger, as
 * the notice's programs are). Slots 0 and 1 in turn: the levels with lanes have no bound list and no wrap; 2 and 3 stay
 * the notice's and the transitions' (`CLAUDE.md`'s four slots). Every stretch carries the same track areas, so a
 * PLAY-AFTER always covers the owned rect of the one it follows (§7.11).
 *
 * **Held still** (`Compositor.laneHold`): while a chain is open the compositor holds the lanes' cells — never dirty, never
 * sent — so no write meets an owning lane and belief keeps the picture the glasses' shadow holds under it; the flush
 * names the two slots as held, so the transport's CLOSE-before-a-write rule leaves them open for a write that meets
 * their meeting box but not their owned rects (a cached glyph run beside the bar reads as reaching the panel's edges).
 *
 * **Ended:** a changed key, the window leaving the front, an overlay, a notice box over a lane, the wheel, a transition, a
 * bound list, Silent Mode, a lease lapse, a keyframe, a session start, RIGHT's closed or refused body, the mirror no
 * longer holding the chain open, a PLAY-AFTER that landed late — the chain is dropped and the lanes' cells are released
 * ([takeRelease] → `Compositor.laneRelease`): written from the truth in the next frame, whose write meets the lane, so
 * the transport CLOSEs whatever of it the mirror still holds. That frame holds no slot: no chain starts in the pump that
 * ended one, nor while a frame the budget cut leaves those cells for a later one ([Env.written]) — the spec's cancel
 * order (§4.1); the next chain starts at the first pump after, the window in front with its lanes wanted. **Fallback:** [REFUSALS_MAX] refusals in a session (RIGHT's, or a PLAY the mirror never
 * held open) → off for the session, said once; the window paints its own frames as below stage 5.
 *
 * Loop-confined (the shell's), and free of the shell: every input is an argument ([Env], the transport's events), so
 * the chain runs against the firmware model alone in `MusicLaneFuzzTest`.
 */
class LaneSpec(
    /** Anything whose change means "plan again". */
    val key: Any,
    /** The lanes' rects, nominal, on the damage grid; each inside one plane region. */
    val rects: List<Rect>,
    /** The chain's period in OS ticks (`CfwModes.MOTION_PERIODS`) and steps a stretch (1..255). */
    val period: Int,
    val steps: Int,
    /** The pictures the programs draw (v2 image records), placed by the shell at the start of each area of the slice. */
    val statics: List<Gray8>,
    /** One stretch's program and parameters ([LaneAt]). */
    val plan: (LaneAt) -> LanePlan,
) {
    init {
        if (rects.isEmpty() || rects.any { it.w <= 0 || it.h <= 0 }) throw LintError("a lane set needs rects: $rects")
        if (period !in CfwModes.MOTION_PERIODS) throw LintError("a lane's period $period is not ${CfwModes.MOTION_PERIODS}")
        if (steps !in 1..Motion.MAX_STEPS) throw LintError("a lane's stretch of $steps steps is not 1..${Motion.MAX_STEPS}")
    }
}

/** Where and when one stretch is planned: its program at [at4] (off4), any table it names from [tables4] on (both inside
 *  its area), the statics placed as [statics], the lanes at disparity [d] (left lens x − d, right x + d), built through
 *  [fx]; its step 1 visible at phone time [stepOneMs], each step [stepMs] after the one before, [steps] steps. */
class LaneAt(val at4: Int, val tables4: Int, val statics: List<Motion.Source.Record>, val d: Int, val fx: Motion.Effects,
             val stepOneMs: Long, val stepMs: Double, val steps: Int)

/** A stretch's program and its parameters P0..P3. */
class LanePlan(val program: MotionProgram, val p: IntArray)

/** The stage lane's key for area [area] of the slice (not a Page staging strip, never in the ring: the lanes' own books). */
class LaneStageKey(area: Int) : StageKey(LaneStageKey::class, area, 1, 0, 0) {
    override fun toString() = "the held lanes' area $firstLine"
}

class WindowLanes(private val say: (String) -> Unit) {

    /** One pump's view: [spec] null = no lanes wanted now ([why] says why, for the journal); [region] the slice
     *  (`CfwModes.musicRegion`, null where the session has none); [fx] the EFFECTS gate's token (null: not in force);
     *  [written] whether a released area's cells have all been written since (`Compositor.laneWritten`: the frame that
     *  carried them was assembled); [tagIn] the mirror's tag in a slot on RIGHT (`LensPanels.motionTagIn`), read once a
     *  slot a pump. */
    class Env(val nowMs: Long, val spec: LaneSpec?, val why: String?, val d: Int, val region: IntRange?, val fx: Motion.Effects?,
              val written: (Pair<Rect, Rect>) -> Boolean, val tagIn: (Int) -> Int?)

    private class Stretch(val n: Int, val slot: Int, val tag: Int, val area: Int, val stepOneMs: Long, val after: Boolean, val cost: Long) {
        var flush: Long? = null
        /** Phone time its message landed (the write's end and its answer's midpoint), and when its step 1 showed. */
        var landedMs: Long? = null
        var startMs: Long? = null
        var seen = false
    }

    private class Chain(val key: Any, val areas: List<Pair<Rect, Rect>>, val period: Int, val steps: Int, val d: Int) {
        val stretches = ArrayList<Stretch>()
        var n = 0
        val lenMs: Double get() = steps * stepMs
        val stepMs: Double get() = period * 1000.0 / 1024.0
    }

    private val areas = arrayOfNulls<StagedRecord>(AREAS)
    private var lastArea = AREAS - 1
    private var chain: Chain? = null
    private var tags = 0
    private var strikes = 0
    private var send: ByteArray? = null
    private var sendFor: Stretch? = null
    private val release = ArrayList<Pair<Rect, Rect>>()
    /** Released areas handed to the compositor ([takeRelease]) whose cells no frame has written yet: no chain starts while
     *  one is here, so the frames that write them hold nothing (the first review's finding 1). */
    private val unwritten = ArrayList<Pair<Rect, Rect>>()

    /** Why the lanes are off for this session (null: not off). */
    var off: String? = null
        private set
    /** When the next stretch is due to be sent (phone clock), or null. */
    var wakeAt: Long? = null
        private set

    /** The per-lens rects the compositor holds while a chain is open; empty otherwise. */
    val hold: List<Pair<Rect, Rect>> get() = chain?.areas ?: emptyList()
    /** The slots a flush leaves open for the lanes' owners (`FlushRequest.held`): only while a chain is open, which is
     *  never in the pump whose frame writes an ended chain's cells ([start]). */
    val heldSlots: Set<Int> get() = if (chain != null) HELD else emptySet()
    /** The areas' records, for the stage lane's and the ledger's books. */
    val records: List<StagedRecord> get() = areas.filterNotNull()
    val open: Boolean get() = chain != null
    /** The open chain's tags in its slots (tests and the journal). */
    val openTags: List<Pair<Int, Int>> get() = chain?.stretches?.filter { it.flush != null }?.map { it.slot to it.tag } ?: emptyList()

    /** The record the stage lane should write next, or null. */
    fun wanted(): StagedRecord? = areas.firstOrNull { it?.state == StagedRecord.State.WANTED }

    /** The lanes' cells to release before this pump's frame (consumed). */
    fun takeRelease(): List<Pair<Rect, Rect>>? = if (release.isEmpty()) null else release.toList().also { unwritten += it; release.clear() }

    /** The PLAY or PLAY-AFTER to submit alone after this pump's frame (consumed); [sent] or [notSent] follows. */
    fun takeSend(): ByteArray? = send.also { send = null }

    fun sent(id: Long) { sendFor?.flush = id; sendFor = null }

    fun notSent(why: String) { sendFor = null; end("its message was not submitted ($why)", true) }

    // ------------------------------------------------------------------------------------------------- the pump
    fun pump(env: Env) {
        wakeAt = null
        unwritten.removeAll { env.written(it) }
        // a message made in an earlier pump and never taken (that pump ended before its send step) is stale: planned for
        // a place that has passed
        if (send != null) { send = null; sendFor = null; end("its message did not go in the pump that made it", true) }
        val c = chain
        if (c != null) {
            // the mirror's view, one read a slot: a landed stretch still open in its slot (drawing, waiting, or a done owner)
            val landed = c.stretches.filter { it.landedMs != null }
            val inFlight = c.stretches.any { it.landedMs == null }
            val held = landed.filter { s -> env.tagIn(s.slot) == s.tag }
            for (s in held) s.seen = true
            if (landed.isNotEmpty() && !inFlight && held.isEmpty()) {
                val never = landed.none { it.seen }
                if (never) strike("the mirror never held its PLAY open")
                end(if (never) "the mirror never held its PLAY open" else "the mirror holds it open no longer (closed by another PLAY, a release or a keyframe)", true)
            }
        }
        val spec = env.spec
        val why = when {
            spec == null -> env.why ?: "no lanes wanted"
            env.region == null -> "no slice for them in a session without BIND at 256 KiB"
            env.fx == null -> "EFFECTS is not in force"
            off != null -> "off for this session ($off)"
            else -> null
        }
        if (why != null) { if (chain != null) end(why, true); return }
        spec!!
        chain?.let { ch ->
            val moved = when {
                ch.key != spec.key -> "its key changed"
                ch.d != env.d -> "the depth changed"
                ch.period != spec.period || ch.steps != spec.steps -> "its period or steps changed"
                ch.areas != perLens(spec, env.d) -> "its rects changed"
                else -> null
            }
            if (moved != null) end(moved, true)
        }
        val ch = chain
        if (ch == null) start(env, spec) else next(env, spec, ch)
    }

    private fun perLens(spec: LaneSpec, d: Int) = spec.rects.map { it.translate(-d, 0) to it.translate(d, 0) }

    private fun start(env: Env, spec: LaneSpec) {
        if (sendFor != null) return
        // the cancel order (spec §4.1; the first review's finding 1): an ended chain's cells are written by a frame that
        // holds no slot, so the transport CLOSEs every instance of it the mirror still holds there — the one waiting in
        // slot 1 too, which the next chain's PLAY (slot 0, the new rect) would not reach. Not in the pump that ended a
        // chain (its frame comes next: asked back at once), nor while a later frame is to write the cells (its
        // completion pumps again)
        if (release.isNotEmpty()) { wakeAt = env.nowMs; return }
        if (unwritten.isNotEmpty()) return
        val stepOne = env.nowMs + SEND_MS
        val stepMs = spec.period * 1000.0 / 1024.0
        val (area, plan) = place(env, spec, stepOne, stepMs, chainArea = null) ?: return
        val tag = nextTag()
        val cost = try { plan.program.cost(plan.p) } catch (e: LintError) { strike("its program is refused offline (${e.message})"); return }
        val bytes = try { Motion.play(plan.program, SLOT_A, spec.period, tag, 0, plan.p, cost = cost) }
            catch (e: LintError) { strike("its PLAY is refused offline (${e.message})"); return }
        val c = Chain(spec.key, perLens(spec, env.d), spec.period, spec.steps, env.d)
        val s = Stretch(++c.n, SLOT_A, tag, area, stepOne, false, cost)
        c.stretches += s
        chain = c
        send = bytes; sendFor = s
        say("a chain starts: tag 0x%04x in slot %d, area %d, %d steps at P %d (%.0f ms a stretch), %d lane(s) at disparity %d".format(
            tag, SLOT_A, area, spec.steps, spec.period, c.lenMs, spec.rects.size, env.d))
    }

    private fun next(env: Env, spec: LaneSpec, ch: Chain) {
        if (sendFor != null) return
        val last = ch.stretches.last()
        val start = last.startMs ?: return                     // its message has not landed yet
        val due = start + Math.round(ch.lenMs)                 // the next stretch's step 1 if its PLAY-AFTER lands in time
        val sendAt = due - Math.round(ch.lenMs / 2)
        if (env.nowMs < sendAt) { wakeAt = sendAt; return }
        val stepOne = maxOf(due, env.nowMs + SEND_MS)
        val (area, plan) = place(env, spec, stepOne, ch.stepMs, chainArea = last.area) ?: return
        val slot = if (last.slot == SLOT_A) SLOT_B else SLOT_A
        val tag = nextTag()
        val cost = try { plan.program.cost(plan.p) } catch (e: LintError) { strike("its program is refused offline (${e.message})"); end("its next stretch is refused offline", true); return }
        // §7.12: the step budget with the drawing stretch it waits on (an instance and its waiter never draw in one present)
        val bytes = try { Motion.playAfter(plan.program, slot, spec.period, tag, last.slot, plan.p, listOf(Motion.Drawing(last.cost)), 0, cost) }
            catch (e: LintError) { strike("its PLAY-AFTER is refused offline (${e.message})"); end("its next stretch is refused offline", true); return }
        val s = Stretch(++ch.n, slot, tag, area, stepOne, true, cost)
        ch.stretches += s
        while (ch.stretches.size > 2) ch.stretches.removeAt(0)   // the one before last is emptied by the last's first step
        send = bytes; sendFor = s
    }

    /**
     * The area for the stretch planned at [stepOne]: one already holding exactly these bytes (the chain's own first — a
     * bar's program is the same every stretch of a song), and it is used once its write is proven; else the next area no
     * open stretch reads, its record placed for the stage lane. Null while that record is not proven yet, and when no area
     * is free (the drawing and the waiting stretches read two of the three).
     */
    private fun place(env: Env, spec: LaneSpec, stepOne: Long, stepMs: Double, chainArea: Int?): Pair<Int, LanePlan>? {
        val region = env.region!!
        val order = listOfNotNull(chainArea) + (0 until AREAS).filter { it != chainArea }
        for (i in order) {
            val rec = areas[i] ?: continue
            val (plan, blob) = build(env, spec, region, i, stepOne, stepMs) ?: return null
            if (rec.encoded.contentEquals(blob)) return if (rec.state == StagedRecord.State.PROVEN) i to plan else null
        }
        // an area whose write is on its way or waits for its count is not written over; one only placed (WANTED) is
        val busy = areas.indices.filter { areas[it]?.state == StagedRecord.State.SENT || areas[it]?.state == StagedRecord.State.ACKED }
        val reading = chain?.stretches?.map { it.area }?.toSet() ?: emptySet()
        val i = (1..AREAS).map { (lastArea + it) % AREAS }.firstOrNull { it !in reading && it !in busy } ?: return null
        val (_, blob) = build(env, spec, region, i, stepOne, stepMs) ?: return null
        for (j in areas.indices) if (areas[j]?.state == StagedRecord.State.WANTED) areas[j] = null   // placed for a key no longer wanted
        areas[i] = StagedRecord(LaneStageKey(i), Gray8(1, 1), blob).also { it.off = region.first + i * AREA_BYTES }
        lastArea = i
        say("area $i written for its programs (${blob.size} B at ${region.first + i * AREA_BYTES})")
        return null
    }

    private fun build(env: Env, spec: LaneSpec, region: IntRange, i: Int, stepOne: Long, stepMs: Double): Pair<LanePlan, ByteArray>? {
        val base = region.first + i * AREA_BYTES
        val end = base + AREA_BYTES
        var off = base
        val statics = ArrayList<Pair<Int, ByteArray>>()
        val recs = spec.statics.map { g ->
            val enc = TextureCacheImage.encode(g)
            Motion.Source.Record(off / 4, g.w, g.h, enc.size, enc).also { statics += off to enc; off += (enc.size + 3) / 4 * 4 }
        }
        val plan = try { spec.plan(LaneAt(off / 4, (off + PROGRAM_MAX) / 4, recs, env.d, env.fx!!, stepOne, stepMs, spec.steps)) }
            catch (e: LintError) { strike("its program cannot be built (${e.message})"); end("its program cannot be built", true); return null }
        val parts = statics + plan.program.records.map { (o4, b) -> o4 * 4 to b }
        val top = parts.maxOf { (o, b) -> o + b.size }
        if (parts.any { (o, _) -> o < base } || top > end) {
            strike("its records do not fit their area of $AREA_BYTES B (${top - base} B)"); end("its records do not fit", true); return null
        }
        val blob = ByteArray((top - base + 3) / 4 * 4)
        for ((o, b) in parts) System.arraycopy(b, 0, blob, o - base, b.size)
        return plan to blob
    }

    private fun nextTag(): Int = TAG + (tags++ and 0xFFF)

    // ------------------------------------------------------------------------------------------- the transport's words
    /** The flush that carried a stretch's message completed: where its step 1 is on the phone's clock. A PLAY starts
     *  when it lands; a PLAY-AFTER that lands before its predecessor's end starts at that end (`FIRMWARE.md` §7.7), and
     *  one that lands later than [LATE_MS] past the place it was planned for shows the past: the chain starts again. */
    fun flushDone(id: Long, ok: Boolean, nowMs: Long, wroteAtMs: Long, ackAtMs: Long): Boolean {
        val c = chain ?: return false
        val s = c.stretches.firstOrNull { it.flush == id } ?: return false
        val what = if (s.after) "PLAY-AFTER" else "PLAY"
        if (!ok) { end("its $what's flush failed", true); return true }
        val landed = when { wroteAtMs > 0 && ackAtMs > 0 -> (wroteAtMs + ackAtMs) / 2; ackAtMs > 0 -> ackAtMs; else -> nowMs }
        s.landedMs = landed
        if (!s.after) { s.startMs = landed; return true }
        val late = landed - s.stepOneMs
        s.startMs = maxOf(s.stepOneMs, landed)
        if (late > LATE_MS) end("stretch ${s.n}'s PLAY-AFTER landed $late ms after its step 1 was due — it would show the past", true)
        return true
    }

    /** RIGHT's motion body for one of the chain's tags ([MotionNotify]'s form). */
    fun notify(tag: Int, outcome: String, drawn: Long?, folded: Long?, stepUs: Long?) {
        if (tag and 0xF000 != TAG) return
        val c = chain
        val s = c?.stretches?.firstOrNull { it.tag == tag }
        when {
            outcome.startsWith("refused") -> {
                strike("RIGHT refused it (${outcome.removePrefix("refused: ")})")
                if (s != null) end("RIGHT refused stretch ${s.n}", true)
            }
            s == null -> return                                // an ended chain's last word
            outcome == "closed" -> end("stretch ${s.n} was closed on the glasses while drawing (another PLAY met it, a keyframe, a release)", true)
            outcome == "drew" -> say("stretch ${s.n} (tag 0x%04x) drew its last step: drawn %s, folded %s, a step %s µs".format(tag, drawn ?: "-", folded ?: "-", stepUs ?: "-"))
        }
    }

    /** A keyframe went: it closed every instance on both lenses (§5.6) — the chain's landed stretches are gone. */
    fun keyframed() {
        unwritten.clear()                                      // it wrote every cell, and closed every instance
        val c = chain ?: return
        if (c.stretches.any { it.flush != null }) end("a keyframe closed every instance", false)
    }

    /** A session start (a fresh lease frees the cache and ends every instance), or a cache another writer touched: every
     *  record goes again, the chain is gone, the count starts over. Its cells are released as at every end (the first
     *  review's finding 4): what the hold grew with them is written, and where no lease ended its instances (another
     *  writer, no keyframe after it) the frame that writes them CLOSEs them. */
    fun reset(why: String) {
        if (chain != null) say("the chain is dropped — $why")
        chain?.let { release += it.areas }
        chain = null; send = null; sendFor = null; wakeAt = null
        for (i in areas.indices) areas[i] = null
        strikes = 0; off = null
    }

    /** The slice goes back to Page staging (Reader in front), or a range write met it: the records leave the books. */
    fun yieldSlice(why: String) {
        if (areas.all { it == null }) return
        if (chain != null) end("its slice is taken — $why", true)
        for (i in areas.indices) areas[i] = null
        say("the lanes' records leave the slice — $why")
    }

    private fun end(why: String, releaseCells: Boolean) {
        val c = chain ?: return
        chain = null
        wakeAt = null
        if (sendFor != null && c.stretches.any { it === sendFor }) { send = null; sendFor = null }
        if (releaseCells) release += c.areas
        say("the chain ends after ${c.n} stretch(es) — $why")
    }

    private fun strike(why: String) {
        strikes++
        say("a strike ($strikes of $REFUSALS_MAX this session): $why")
        if (strikes >= REFUSALS_MAX && off == null) {
            off = why
            say("off for this session after $strikes strikes — the window's own frames until the next session (the last: $why)")
        }
    }

    companion object {
        /** The lanes' tags: one a stretch, cycling through 0x1000 (≥ 36 h of 32-s stretches; 4.5 h of 4-s ones), apart
         *  from the wrap's 0xB000, a transition's 0xC000 / 0xD000 and the notice's 0xE000. */
        const val TAG = 0xA000
        const val SLOT_A = 0
        const val SLOT_B = 1
        val HELD = setOf(SLOT_A, SLOT_B)
        /** Three areas in turn (a stretch drawing, the next waiting, the one being written: no write meets an open owner's
         *  read range, §7.11), each its statics, its program and its tables. */
        const val AREAS = 3
        const val AREA_BYTES = 5_440
        /** The largest version-2 program (8 per-lens tracks of 8 segments): where its tables may start. */
        const val PROGRAM_MAX = Motion.HEADER2_BYTES + Motion.MAX_TRACKS * (4 + 16 + Motion.MAX_SEGMENTS * Motion.SEGMENT2_BYTES)
        /** The first stretch's step 1 is planned this long after its PLAY is decided: the phone path's floor for a message
         *  under 500 B on LE 2M (71 ms median, measured, `REMINDER.md`'s table); it lands within the link's spread of it. */
        const val SEND_MS = 70L
        /** A PLAY-AFTER that lands this much past its stretch's planned step 1 restarts the chain (modeled: a bar moves a
         *  pixel in ≈ 125–500 ms; a lyric line's wipe is judged on his face). */
        const val LATE_MS = 100L
        /** Strikes in a session before the lanes are off ("three refusals", spec §4.1). */
        const val REFUSALS_MAX = 3
        init { check(AREAS * AREA_BYTES <= CfwModes.MUSIC_REGION && AREA_BYTES % 4 == 0) }
    }
}
