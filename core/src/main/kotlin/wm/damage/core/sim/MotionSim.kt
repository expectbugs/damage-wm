package wm.damage.core.sim

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.wire.Pb

/**
 * `FIRMWARE.md` §5 (build C1): the motion engine of ONE lens as the simulator models it — mode 26's validator and its
 * three subs (§5.5), program records with their substitution and eases (§5.4), the per-step paint, END and its CRC
 * (§5.6), the `self` captures, close-on-meet, the thread's plan as a pure function (§5.2), the counters and notify
 * 114's bodies (§5.8; build C1's decisions A.2 and A.11). Written from §5's text and the lead's decisions on it alone,
 * never from the fork's C, so that the conformance vectors compare two readings of one contract.
 *
 * What the model leaves out, on purpose. It has one thread: the thread is a call ([GlassFirmwareSim.conformanceWake])
 * whose gate try and queue put always succeed, and the engine's busy word is kept only for its observable effect (a
 * hook that finds it held folds its step). Nothing is timed: notify field 9 must be carried and carries 0, and
 * telemetry field 34 is left out (a field omitted when its value is not known, as field 15 is). The order of the
 * checks is the contract's, since the reason recorded is.
 *
 * Where §5's text allows two readings, the reading this model takes is marked "READING" in a comment, with the
 * other one: each is a question for the vectors' meeting, not a settled fact.
 *
 * Build C2 (`FIRMWARE.md` §6, from its text alone): the local instance a bound fire opens ([openLocal]: no END, tag S,
 * HOLD at step T), the fire's close set ([closeSet], [closeForFire]), mode 26's subs 3–5 ([commit], [discard],
 * [settle]) and outcome 3, the walk that refuses `self` (5), and DRAW's h0/h1/anchor with the H rule (§6.6) on a build
 * whose [scaled] answers true (DamageCaps bit 10). [BindSim] holds the table and the hook.
 */
class MotionSim(private val scaled: () -> Boolean = { false }) {

    /** A 4bpp source: [w]×[h] levels, row-major. */
    class Img(val w: Int, val h: Int, val px: ByteArray)

    /** A half-open byte range of the texture cache: `[lo, hi)`. */
    data class Span(val lo: Int, val hi: Int) {
        fun meets(o: Span): Boolean = lo < o.hi && o.lo < hi
    }

    /** One segment after substitution (§5.4), with what the walk found for it. */
    class Seg(val raw: ByteArray) {
        val verb = u8(raw, 0)
        var steps = u8(raw, 1)
        val ease = u8(raw, 2)
        val kind = u8(raw, 3)
        var src = u16(raw, 4)
        var xL = s16(raw, 6); var xR = s16(raw, 8); var y = s16(raw, 10)
        var dxL = s16(raw, 12); var dxR = s16(raw, 14); var dy = s16(raw, 16)
        var lvl0 = u8(raw, 18); var lvl1 = u8(raw, 19)
        val opt = u8(raw, 20)
        val table = u16(raw, 22)
        var h0 = u16(raw, 24); var h1 = u16(raw, 26)
        val anchor = u8(raw, 28)
        /** §6.6: h0 and h1 both 0 is unscaled. */
        val isScaled: Boolean get() = h0 != 0 || h1 != 0
        /** E[0..n] once the values check has passed; the uploaded table's E[1..n] once the walk has read it. */
        var e = IntArray(0)
        var tableE: IntArray? = null
        /** A record source (kind 0 of DRAW or REVEAL). */
        var image: Img? = null
        /** §5.4's F for a REVEAL: the source's height (edges 0/1) or width (2/3). */
        var extentMax = 0
        val moves: Boolean get() = verb == DRAW || verb == REVEAL
    }

    class Track(val rectL: Rect, val rectR: Rect, val backdrop: Int, val perLens: Boolean, val segs: List<Seg>) {
        val bbox: Rect = rectL.union(rectR)
        val usesSelf: Boolean get() = backdrop == BD_SELF || segs.any { it.moves && it.kind == KIND_SELF }
        fun rect(left: Boolean) = if (left) rectL else rectR
    }

    /** A program walked and substituted: its tracks, T, and the first read range (§5.6). */
    class Prog(val tracks: List<Track>, val first: Span) {
        val T: Int = tracks.maxOf { t -> t.segs.sumOf { it.steps } }
    }

    /** The values a track shows at one step, on this lens (§5.11 row 1's "last drawn values"); [h] a scaled DRAW's
     *  eased H (§6.6), 0 for an unscaled one. */
    data class Vals(val x: Int, val y: Int, val lvl: Int, val e: Int, val h: Int = 0)

    /** §5.11 row 1: one instance slot. */
    class Instance(val slot: Int, val tag: Int, val period: Int, val prog4: Int, var prog: Prog, val cap: Span?, val caps: Array<Img?>) {
        var t0 = 0L
        var d = 0
        var drawing = true
        var ordinal = 0
        var endCrc = 0L
        var drawn = 0
        var folded = 0
        /** Per track: what this lens last drew (seeded with the start state, decision A.13). */
        var last: Array<Vals?> = arrayOfNulls(prog.tracks.size)
        /** Per track: a RETARGET's restart — its one moving segment eased from these values (§5.5). */
        var from: Array<Vals?> = arrayOfNulls(prog.tracks.size)
        /** §6.4: opened by a bound fire, never written into the shadow; HOLDs at step T. Cleared only by a COMMIT's
         *  adoption (the slot's emptying ends the instance) — a stock-path present turns it done and leaves it. */
        var local = false
        val T: Int get() = prog.T
    }

    // ------------------------------------------------------------------------------------------------- state
    val slots = arrayOfNulls<Instance>(SLOTS)
    /** §5.2: the thread exists from the first accepted PLAY of a boot and is never deleted (field 30 bit 8). */
    var threadExists = false
    /** §5.8 field 31: the last notify sequence taken (one per body, since boot). */
    var sequence = 0L
    /** Field 32: the thread's successful puts (decision A.11). */
    var engineFrames = 0L
    /** Field 33: steps passed over + failed tries + failed puts + lost claims (decision A.11). */
    var foldsSinceBoot = 0L
    /** §5.3: at most one engine refresh outstanding — set by the thread, cleared by the copy hook on every run. */
    var outstanding = false
    /** §5.11 row 3, the engine's busy word, reduced to what one thread can observe: held or not. */
    var wordHeld = false
        private set
    /** Build C1's rulings R1.1 and B-N13 (`C1-R2-decisions.md` §B): every close of an open instance (drawing or done)
     *  — a CLOSE, a PLAY's own closes, a live mode 6's, a cache write that meets it — sets the panel's stale mark, so
     *  the next present goes whole; [close] owes it here and the lens takes it when the engine word is let down
     *  ([takeStaleMark]). */
    private var staleMarkOwed = false
    fun takeStaleMark(): Boolean = staleMarkOwed.also { staleMarkOwed = false }
    /** Notify bodies: the hook's owed record per slot (§5.8), the ui_task's captures per slot and its one refusal
     *  (decision A.2), and every body this lens SENT, in send order (RIGHT only sends; the vectors' "N"). */
    private val owed = arrayOfNulls<ByteArray>(SLOTS)
    private val uiCaptures = arrayOfNulls<ByteArray>(SLOTS)
    /** §6.5: a COMMIT's outcome-3 body per slot, sent after the slots' close captures. READING: §5.8's "slots in order,
     *  then the refusal" names closes and a refusal only; an outcome-3 body is kept apart from its slot's close capture
     *  (a COMMIT may close what else was in its slot), so neither replaces the other, and goes after every close. */
    private val uiCommits = arrayOfNulls<ByteArray>(SLOTS)
    private var uiRefusal: ByteArray? = null
    /** Every notify this lens SENT, in send order, with its field: 114 bodies and (build C2, [BindSim]) 115 bodies. */
    val sent = ArrayList<Pair<Int, ByteArray>>()
    /** RIGHT's END CRC of the last accepted COMMIT (§6.5), for the mirror's record. */
    var lastCommitCrc = 0L
        private set

    val anyOpen: Boolean get() = slots.any { it != null }
    val anyDrawing: Boolean get() = slots.any { it?.drawing == true }

    fun claim() { check(!wordHeld) { "the engine word claimed twice: the model has one thread" }; wordHeld = true }
    fun letDown() { wordHeld = false }

    /** Test seam: hold the word as another task would, so a present's hook loses its claim. */
    fun holdWordForTest(held: Boolean) { wordHeld = held }

    // ------------------------------------------------------------------------------------- §5.5 the validator
    /**
     * The program at [prog4] with parameters [p], checked in §5.5's order from "the program record and every record
     * and table it names" on: the walk (5), the areas (2), the `none` backdrops (2), the values (12). Returns the
     * program, or the reason as a negative number.
     */
    fun validate(cache: ByteArray?, prog4: Int, p: IntArray, noSelf: Boolean = false): Any {
        val prog = walk(cache, prog4, p, noSelf) ?: return -REF_RECORD
        for (t in prog.tracks) if (!rectOk(t.rectL) || !rectOk(t.rectR) ||
            (t.perLens && (t.rectL.w != t.rectR.w || t.rectL.h != t.rectR.h || t.rectL.y != t.rectR.y))) return -REF_BOUNDS
        // READING: "inside an earlier track's area" — one earlier track whose two rects each contain this track's
        // (the other reading: each lens's rect inside some earlier track's, not necessarily the same one)
        prog.tracks.forEachIndexed { i, t ->
            if (t.backdrop == BD_NONE && prog.tracks.take(i).none { o ->
                    o.backdrop != BD_NONE && o.rectL.contains(t.rectL) && o.rectR.contains(t.rectR) }) return -REF_BOUNDS
        }
        if (!valuesOk(prog)) return -REF_VALUE
        return prog
    }

    private fun rectOk(r: Rect) = r.w > 0 && r.h > 0 && r.right <= Geometry.PANEL_W && r.bottom <= Geometry.PANEL_H

    /** §5.4's record walk with substitution: null for any fault of structure, of a named record or of a table (5).
     *  Decision A.9: reserved bytes, `anchor` and opt bits a verb does not use must be 0; enumerations (version,
     *  counts, backdrop, perLens, verb, ease, kind, a pref's field) are structure. Build C2: [noSelf] refuses a `self`
     *  backdrop or source (a fire, a COMMIT, a SET: §6.2 step 10, §6.3, §6.5); [ownOnly] stops after the record's own
     *  bytes — what §6.3's SET checks — and names no record or table (what the parameters name is checked at a fire). */
    private fun walk(cache: ByteArray?, prog4: Int, p: IntArray, noSelf: Boolean = false, ownOnly: Boolean = false): Prog? {
        val off = prog4 * 4
        if (cache == null || off + 4 > cache.size) return null
        if (u8(cache, off) != 1) return null
        val nTracks = u8(cache, off + 1)
        val end = off + u16(cache, off + 2)
        if (nTracks !in 1..MAX_TRACKS || end > cache.size || end < off + 4) return null
        var pos = off + 4
        val tracks = ArrayList<Track>(nTracks)
        repeat(nTracks) {
            if (pos + 4 > end) return null
            val nSegs = u8(cache, pos); val bd = u8(cache, pos + 1); val pl = u8(cache, pos + 2)
            if (nSegs !in 1..MAX_SEGS || !(bd in 0..15 || bd == BD_SELF || bd == BD_NONE) || pl > 1 || u8(cache, pos + 3) != 0) return null
            val areaBytes = if (pl == 1) 16 else 8
            if (pos + 4 + areaBytes > end) return null
            val rL = rectAt(cache, pos + 4)
            val rR = if (pl == 1) rectAt(cache, pos + 12) else rL
            pos += 4 + areaBytes
            val segs = ArrayList<Seg>(nSegs)
            repeat(nSegs) {
                if (pos + SEG_BYTES > end) return null
                val s = Seg(cache.copyOfRange(pos, pos + SEG_BYTES))
                if (!structureOk(s)) return null
                for (pr in intArrayOf(u8(s.raw, 30), u8(s.raw, 31))) if (pr and 0x80 != 0) substitute(s, (pr shr 2) and 0x1F, p[pr and 3])
                segs += s
                pos += SEG_BYTES
            }
            tracks += Track(rL, rR, bd, pl == 1, segs)
        }
        if (pos != end) return null                            // §5.4: parsing must end exactly at `length`
        if (noSelf && tracks.any { it.usesSelf }) return null
        if (ownOnly) return Prog(tracks, Span(off, end))
        var lo = off; var hi = end
        for (t in tracks) for (s in t.segs) {
            if (s.moves && s.kind == KIND_RECORD) {
                val (img, recEnd) = record(cache, s.src * 4) ?: return null
                s.image = img
                lo = minOf(lo, s.src * 4); hi = maxOf(hi, recEnd)
            }
            // build C1's ruling R1.3: ease 2 names a table whatever the verb — a FILL's or a WAIT's is walked (5) and
            // counted in the first read range too, though neither uses an eased value
            if (s.ease == EASE_TABLE) {
                val tOff = s.table * 4
                if (tOff + 2 > cache.size) return null
                val n = u8(cache, tOff)
                // decision A.9: n ≠ steps and E[n] ≠ 4096 are 5; the table's reserved byte is required 0
                if (n < 1 || u8(cache, tOff + 1) != 0 || tOff + 2 + 2 * n > cache.size || n != s.steps) return null
                val e = IntArray(n) { s16(cache, tOff + 2 + 2 * it) }
                if (e[n - 1] != 4096) return null
                s.tableE = e
                lo = minOf(lo, tOff); hi = maxOf(hi, tOff + 2 + 2 * n)
            }
        }
        return Prog(tracks, Span(lo, hi))
    }

    /** §6.3 SET (build C2): the program record's own structure at [prog4] — header, tracks, segments, enumerations and
     *  reserved bytes — and no `self` (5). Nothing it names is read. */
    fun ownStructureOk(cache: ByteArray?, prog4: Int): Boolean = walk(cache, prog4, IntArray(4), noSelf = true, ownOnly = true) != null

    private fun structureOk(s: Seg): Boolean {
        // §6.6 (build C2): DRAW's anchor is 0 top · 1 centre · 2 bottom; another anchor is an enumeration outside the
        // build's range (5, the decisions on the build spec B.2). READING: on every other verb the anchor stays one of
        // the zeros §5.5 requires (5), as in C1 — §6.6 gives it a meaning for DRAW only
        val anchorOk = if (s.verb == DRAW && scaled()) u8(s.raw, 28) <= ANCHOR_BOTTOM else u8(s.raw, 28) == 0
        if (s.verb > REVEAL || u8(s.raw, 21) != 0 || u8(s.raw, 29) != 0 || !anchorOk) return false
        for (pr in intArrayOf(u8(s.raw, 30), u8(s.raw, 31))) if (pr and 0x80 != 0 && ((pr shr 2) and 0x1F) > PREF_STEPS) return false
        // build C1's ruling R1.3: `ease` is checked on every segment (0..2, else 5), whatever the verb
        if (s.ease > EASE_TABLE) return false
        // decision A.9 lists what must be 0; a field a verb does not use and A.9 does not list is ignored (`kind` is
        // read only by the verbs that use it)
        return when (s.verb) {
            DRAW -> (s.kind == KIND_RECORD || s.kind == KIND_SELF) && s.opt and DRAW_OPT.inv() == 0
            // READING (§5.4 gives DRAW's options, not REVEAL's): REVEAL uses opt bits 0-2 only and draws its source
            // as it is; the other reading ramps it as DRAW does and lets bits 4/5 through
            REVEAL -> (s.kind == KIND_RECORD || s.kind == KIND_SELF || s.kind == KIND_LEVEL) && s.opt and REVEAL_OPT.inv() == 0
            else -> s.opt == 0                                 // FILL and WAIT use no opt bit
        }
    }

    /** §5.4 Parameters: "u8 fields take the low byte"; s16 fields take the parameter; u16 fields its 16 bits. */
    private fun substitute(s: Seg, field: Int, v: Int) {
        when (field) {
            0 -> s.src = v and 0xFFFF
            1 -> s.xL = v; 2 -> s.xR = v; 3 -> { s.xL = v; s.xR = v }
            4 -> s.y = v
            5 -> s.dxL = v; 6 -> s.dxR = v; 7 -> { s.dxL = v; s.dxR = v }
            8 -> s.dy = v
            9 -> s.lvl0 = v and 0xFF; 10 -> s.lvl1 = v and 0xFF
            11 -> s.h0 = v and 0xFFFF; 12 -> s.h1 = v and 0xFFFF
            13 -> s.steps = v and 0xFF
        }
    }

    /** §5.5 "every step's values in range: levels 0..15, extents 0..F, h0/h1 = 0, T ≤ 255" (12), decision A.9's list
     *  (a level source's level, width and height too). READING: a segment's `steps` of 0 is a value (12). */
    private fun valuesOk(prog: Prog): Boolean {
        for (t in prog.tracks) {
            for (s in t.segs) {
                if (s.steps < 1) return false
                // §5.5 "h0/h1 = 0"; §6.6 on a bit-10 build, DRAW only: both 0, or both inside 1..2048 and every step's
                // eased H inside 1..2048 (h0 = 0 with h1 ≠ 0, or the reverse, is 12: C2-decisions I.4). Another verb
                // keeps C1's h0/h1 = 0 (§6.6 is DRAW's; I.7)
                if ((s.h0 != 0 || s.h1 != 0) && (s.verb != DRAW || !scaled())) return false
                when (s.verb) {
                    FILL -> { if (s.kind > 15) return false; s.e = IntArray(s.steps + 1) }
                    DRAW -> {
                        s.e = easeE(s.ease, s.steps, s.tableE)
                        if (s.e.any { eased(s.lvl0, s.lvl1 - s.lvl0, it) !in 0..15 }) return false
                        // §6.6: "a scaled DRAW's source is a record — `self` with h0/h1 ≠ 0 is 12 … at a PLAY, a COMMIT
                        // and a fire alike" (C2-decisions M.2); a RETARGET whose parameters scale it too. At a COMMIT and
                        // a fire the walk's `self` 5 decides first, so the 12 is reached at a PLAY and a RETARGET
                        if (s.isScaled && s.kind == KIND_SELF) return false
                        if (s.isScaled && (s.h0 !in 1..H_MAX || s.h1 !in 1..H_MAX)) return false
                        if (s.isScaled && (1..s.steps).any { eased(s.h0, s.h1 - s.h0, s.e[it]) !in 1..H_MAX }) return false
                    }
                    REVEAL -> {
                        if (s.kind == KIND_LEVEL && (s.src > 15 || s.dxL < 1 || s.dy < 1)) return false
                        val (w, h) = when (s.kind) { KIND_RECORD -> s.image!!.w to s.image!!.h; KIND_SELF -> t.rectL.w to t.rectL.h; else -> s.dxL to s.dy }
                        s.extentMax = if ((s.opt and 3) <= 1) h else w
                        s.e = easeE(s.ease, s.steps, s.tableE)
                        if (s.e.any { eased(0, s.extentMax, it) !in 0..s.extentMax }) return false
                    }
                    else -> s.e = IntArray(s.steps + 1)
                }
            }
        }
        return prog.T <= T_MAX
    }

    /** Decision A.5: the `self` tracks' captures, in track order, each 4-byte aligned, from `cap·4`. Per track its
     *  offset (−1: no capture), and the capture range. Null when no track uses `self`. */
    fun captureLayout(prog: Prog, cap4: Int): Pair<IntArray, Span>? {
        if (prog.tracks.none { it.usesSelf }) return null
        val offs = IntArray(prog.tracks.size) { -1 }
        var cur = cap4 * 4
        var first = true
        prog.tracks.forEachIndexed { i, t ->
            if (!t.usesSelf) return@forEachIndexed
            if (!first) cur = (cur + 3) and 3.inv()
            first = false
            offs[i] = cur
            cur += ((t.rectL.w + 1) / 2) * t.rectL.h
        }
        return offs to Span(cap4 * 4, cur)
    }

    // ------------------------------------------------------------------------------------------- the three subs
    /** PLAY's checks past the walk and its act (§5.5): the capture range (5), then the closes, the captures, END into
     *  the shadow and its CRC, the instance opened. Returns 0 or the reason. [tick] is this lens's OS tick. */
    fun play(cache: ByteArray?, shadow: ByteArray, stride: Int, left: Boolean, slot: Int, period: Int, prog4: Int, tag: Int,
             cap4: Int, p: IntArray, tick: Long, uptime: Long): Int {
        val v = validate(cache, prog4, p)
        val prog = v as? Prog ?: return -(v as Int)
        val layout = captureLayout(prog, cap4)
        if (layout != null && (layout.second.hi > cache!!.size || layout.second.meets(prog.first))) return REF_RECORD
        // READING: one pass over the slots in slot order closes each instance that is this slot's, meets this one's
        // areas, or meets the capture range (the other reading: three passes in the order §5.5 lists them) — the
        // order the sequences are taken in
        for (i in 0 until SLOTS) {
            val o = slots[i] ?: continue
            if (i == slot || o.prog.tracks.any { a -> prog.tracks.any { b -> a.bbox.overlaps(b.bbox) } } ||
                (layout != null && (o.prog.first.meets(layout.second) || o.cap?.meets(layout.second) == true))) close(i, uptime)
        }
        val caps = arrayOfNulls<Img>(prog.tracks.size)
        if (layout != null) prog.tracks.forEachIndexed { i, t ->
            val off = layout.first[i]
            if (off < 0) return@forEachIndexed
            val r = t.rect(left)
            val packed = tightPack(shadow, stride, r)
            packed.copyInto(cache!!, off)
            caps[i] = unpack(cache, off, r.w, r.h)
        }
        val inst = Instance(slot, tag, period, prog4, prog, layout?.second, caps)
        paint(inst, inst.T, shadow, stride, left)              // §5.6: END into the shadow at the PLAY
        inst.endCrc = endCrc(shadow, stride, prog, left)
        inst.t0 = tick and U32
        inst.last = Array(prog.tracks.size) { startVals(inst, it, left) }
        slots[slot] = inst
        threadExists = true
        return 0
    }

    /** RETARGET (§5.5): an open instance only (16); the shape rule's 12 right after the empty-slot test, decided from
     *  the instance's validated program (a parameter cannot change a verb or a segment count) — build C1's ruling
     *  R1.9; then the walk with the new parameters and the checks after it, the new first range not meeting the
     *  capture range (5). */
    fun retarget(cache: ByteArray?, shadow: ByteArray, stride: Int, left: Boolean, slot: Int, p: IntArray, tick: Long): Int {
        val inst = slots[slot] ?: return REF_NO_INSTANCE
        if (!inst.prog.tracks.all { retargetable(it) }) return REF_VALUE
        val v = validate(cache, inst.prog4, p)
        val prog = v as? Prog ?: return -(v as Int)
        if (inst.cap != null && prog.first.meets(inst.cap)) return REF_RECORD
        // each moving segment from the state this lens last drew (the start state when nothing is drawn yet, A.13)
        // to the new final state. Build C1's ruling R1.2: a track with a leading WAIT runs the WAIT's steps again
        // holding that state, then moves from it ([vals] eases the track's one non-WAIT segment from `from`, and
        // [stateAt] holds that segment at j = 0 through the WAIT)
        val from = Array(prog.tracks.size) { i ->
            if (prog.tracks[i].segs.firstOrNull { it.verb != WAIT }?.moves == true) inst.last[i] else null
        }
        inst.prog = prog
        inst.from = from
        paint(inst, inst.T, shadow, stride, left)              // END(prog, new P)
        inst.endCrc = endCrc(shadow, stride, prog, left)
        inst.t0 = tick and U32; inst.d = 0; inst.drawing = true; inst.ordinal++
        inst.drawn = 0; inst.folded = 0
        inst.last = Array(prog.tracks.size) { startVals(inst, it, left) }
        return 0
    }

    /** "at most one non-WAIT segment after an optional leading WAIT": the shapes [W], [X], [W, X] only — no trailing
     *  WAIT (ruling R1.9). */
    private fun retargetable(t: Track): Boolean = when (t.segs.size) {
        1 -> true
        2 -> t.segs[0].verb == WAIT && t.segs[1].verb != WAIT
        else -> false
    }

    /** CLOSE (§5.5): empties the slot; a drawing instance's close is captured as outcome 1, a done one sends none.
     *  True when it ended an open instance (drawing or done); every such close owes the panel its stale mark (R1.1,
     *  B-N13) — the one site every close of the four kinds passes through. */
    fun close(slot: Int, uptime: Long): Boolean {
        val o = slots[slot] ?: return false
        if (o.drawing) uiCaptures[slot] = body(++sequence, slot, o.tag, OUTCOME_CLOSED, o, 0, uptime)
        slots[slot] = null
        staleMarkOwed = true
        return true
    }

    /** CLOSE of every slot in slot order (0xFF, and a live mode 6); true when it ended any open instance. */
    fun closeAll(uptime: Long): Boolean {
        var ended = false
        for (i in 0 until SLOTS) if (close(i, uptime)) ended = true
        return ended
    }

    /** §5.6: a cache write (12, 19, 25) whose entries meet an open instance's read ranges closes it first. */
    fun closeMeeting(writes: List<Span>, uptime: Long) {
        for (i in 0 until SLOTS) {
            val o = slots[i] ?: continue
            if (writes.any { w -> w.lo < w.hi && (w.meets(o.prog.first) || o.cap?.meets(w) == true) }) close(i, uptime)
        }
    }

    /** A release point (§5.6; mode 11 by decision A.7): every instance ends, no notify. */
    fun endAll() {
        check(!wordHeld) { "a release point while the engine word is held: the model has one thread" }
        slots.fill(null)
    }

    /** Decision A.2: a refusal's body, outcome 2: fields 1, 2 and 3 when the message holds them, 4, 10, 11. The slot
     *  is the byte after a PLAY's, RETARGET's or CLOSE's sub; an unknown sub (3..255) defines no slot, so its body
     *  carries none (build C1's ruling R1.8); the tag only a PLAY holds. */
    fun captureRefusal(msg: ByteArray, reason: Int, uptime: Long) {
        val sub = if (msg.size >= 2) msg[1].toInt() and 0xFF else -1
        // build C2 (§6.5): on a bit-10 build subs 3–5 are known — each names a slot after its sub (0xFF as sent); field 3
        // for a COMMIT only, whose S names one instance's tag (a DISCARD's or SETTLE's S is a bound: C2-decisions I.3)
        val known = if (scaled()) 0..5 else 0..2
        uiRefusal = Pb.cat(Pb.v(1, ++sequence),
            if (sub in known && msg.size >= 3) Pb.v(2, msg[2].toInt() and 0xFF) else ByteArray(0),
            if (sub == 0 && msg.size >= 8) Pb.v(3, u16(msg, 6))
            else if (sub == 3 && sub in known && msg.size >= 5) Pb.v(3, u16(msg, 3)) else ByteArray(0),
            Pb.v(4, OUTCOME_REFUSED), Pb.v(10, reason), Pb.v(11, uptime))
    }

    /** The ui_task's sends at the end of the image worker (decision A.2): slot order, then (build C2) the COMMITs'
     *  outcome-3 bodies in slot order, then the refusal. */
    fun takeUiSends(): List<ByteArray> {
        val out = uiCaptures.filterNotNull() + uiCommits.filterNotNull() + listOfNotNull(uiRefusal)
        uiCaptures.fill(null); uiCommits.fill(null); uiRefusal = null
        return out
    }

    /** The refresh hook's sends after a refresh: the owed records, slot order. */
    fun takeOwed(): List<ByteArray> = owed.filterNotNull().also { owed.fill(null) }

    // ----------------------------------------------------------------------------------------- the copy hook
    /** What one run of the copy hook did: whether it drew a step (that present goes whole) and whether one was an
     *  instance's last (the stale mark, decision A.12). */
    data class Hook(val drew: Boolean, val lastStep: Boolean)

    /**
     * The copy hook's engine function (§5.2, §5.3): [draws] is false on the stock path, which draws no step and turns
     * every drawing instance done with no notify. It clears the outstanding mark on every run, claimed or not.
     */
    /** Tests only (G4, decisions BF): a step's drawing time this lens reports in its bodies' field 9, which the model does
     *  not otherwise know (0: none, as before). When it is longer than an instance's period, the step after step 1 is
     *  passed over once per instance — the one fold per motion measured on his glasses when the steps outgrew P. */
    @Volatile var stepUsForTest: Long = 0L

    private fun slowFor(o: Instance): Boolean = stepUsForTest > 0L && stepUsForTest * 1024L > o.period * 1_000_000L

    fun hookPresent(fb: ByteArray, stride: Int, left: Boolean, tick: Long, draws: Boolean, uptime: Long, preserve: Boolean = false): Hook {
        outstanding = false
        if (!anyDrawing) return Hook(false, false)
        if (wordHeld) { foldsSinceBoot++; return Hook(false, false) }   // a lost claim folds the step; never waits
        if (!draws) { for (o in slots) if (o != null) o.drawing = false; return Hook(false, false) }
        var last = false
        for (o in slots) {
            if (o == null || !o.drawing) continue
            val k = dueStep(o, tick).let { k0 -> if (slowFor(o) && o.d == 1 && k0 == 2 && o.T > 2) 3 else k0 }
            // §5.3: the preserve path draws an instance only when k > d (its framebuffer still holds step d — a HOLD's
            // step T too, §6.4); the direct path redraws every drawing one over the shadow it has just copied
            if (preserve && k <= o.d) continue
            // decision A.11: field 6 counts distinct steps (k > d), field 7 the steps passed over
            if (k > o.d) { o.drawn++; val skipped = k - o.d - 1; o.folded += skipped; foldsSinceBoot += skipped }
            paint(o, k, fb, stride, left)
            o.d = k
            o.last = Array(o.prog.tracks.size) { trackVals(o, it, k, left) }
            // §6.4 HOLD: at step T a local instance stays drawing at d = T and owes no 114 (nothing has ended)
            if (k == o.T && !o.local) {
                o.drawing = false
                owed[o.slot] = body(++sequence, o.slot, o.tag, OUTCOME_DREW, o, 0, uptime)
                last = true
            }
        }
        return Hook(true, last)
    }

    private fun dueStep(o: Instance, tick: Long): Int =
        minOf(o.T.toLong(), 1 + ((tick - o.t0) and U32) / o.period).toInt()

    /** The thread's plan at [tick] for this lens's table (§5.2). */
    fun plan(tick: Long, failed: Boolean = false): Long =
        plan(tick, slots.map { o -> o?.let { PlanSlot(it.drawing, it.t0, it.period, it.T, it.d) } }, outstanding, failed)

    // --------------------------------------------------------------------------------------------- the paint
    /** §5.4 "Per step, per track": every track's whole area, backdrop then state, in track order. */
    fun paint(o: Instance, k: Int, buf: ByteArray, stride: Int, left: Boolean) {
        o.prog.tracks.forEachIndexed { i, t ->
            val area = t.rect(left)
            when (t.backdrop) {
                BD_NONE -> {}
                BD_SELF -> blit(buf, stride, o.caps[i]!!, area.x, area.y, area, null)
                else -> fill(buf, stride, area, t.backdrop)
            }
            val (s, j) = stateAt(t, k) ?: return@forEachIndexed
            val v = vals(o, i, s, j, left)
            when (s.verb) {
                FILL -> fill(buf, stride, area, s.kind)
                // the level is 0..15 here: [vals] clamps a RETARGET's overshoot (ruling R1.10)
                DRAW -> {
                    val opts = (s.opt and 0x30) or v.lvl
                    if (!s.isScaled) blit(buf, stride, if (s.kind == KIND_SELF) o.caps[i]!! else s.image!!, v.x, v.y, area, opts)
                    // §6.6: a record H rows tall (a scaled `self` is refused 12), placed by the anchor (H ≥ 1 by the H
                    // rule and the restart's clamp; 0 would draw no row)
                    else if (v.h > 0) blitScaled(buf, stride, s.image!!, v.x, anchoredY(v.y, s.image!!.h, v.h, s.anchor), v.h, area, opts)
                }
                REVEAL -> reveal(buf, stride, o, i, s, v, area)
            }
        }
    }

    /** §5.4 REVEAL: the source at rest at (x, y); wipe shows its e rows or columns at the edge, slide moves it in
     *  from the edge by F − e. */
    private fun reveal(buf: ByteArray, stride: Int, o: Instance, i: Int, s: Seg, v: Vals, area: Rect) {
        val src = when (s.kind) {
            KIND_RECORD -> s.image!!
            KIND_SELF -> o.caps[i]!!
            else -> Img(s.dxL, s.dy, ByteArray(s.dxL * s.dy) { s.src.toByte() })
        }
        val f = s.extentMax; val e = v.e; val slide = s.opt and 4 != 0
        if (e <= 0) return
        var x = v.x; var y = v.y
        val vis = when (s.opt and 3) {
            0 -> { if (slide) y -= f - e; Rect(v.x, v.y, src.w, e) }
            1 -> { if (slide) y += f - e; Rect(v.x, v.y + f - e, src.w, e) }
            2 -> { if (slide) x -= f - e; Rect(v.x, v.y, e, src.h) }
            else -> { if (slide) x += f - e; Rect(v.x + f - e, v.y, e, src.h) }
        }
        vis.intersect(area)?.let { blit(buf, stride, src, x, y, it, null) }
    }

    /** §5.4: "A track holds the state it reached: before its first non-WAIT segment, that segment's state at j = 0;
     *  after a segment, its final state; tracks shorter than T hold to the end." Null: nothing but the backdrop. */
    private fun stateAt(t: Track, k: Int): Pair<Seg, Int>? {
        var before = 0
        var prev: Seg? = null
        for (s in t.segs) {
            if (k <= before + s.steps) {
                if (s.verb != WAIT) return s to (k - before)
                return prev?.let { it to it.steps } ?: t.segs.firstOrNull { it.verb != WAIT }?.let { it to 0 }
            }
            before += s.steps
            if (s.verb != WAIT) prev = s
        }
        return prev?.let { it to it.steps }
    }

    /** §5.4's eased values at local step [j]: `v(j) = v0 + ((D·E[j] + 2048) >> 12)`; a RETARGET's restart eases its
     *  moving segment from the values this lens last drew to the new final ones. */
    private fun vals(o: Instance, i: Int, s: Seg, j: Int, left: Boolean): Vals {
        val t = o.prog.tracks[i]
        val x0 = if (left || !t.perLens) s.xL else s.xR
        val dx = if (left || !t.perLens) s.dxL else s.dxR
        val e = s.e[j]
        // a RETARGET's restart eases the track's one non-WAIT segment (after an optional leading WAIT, ruling R1.2)
        val f = o.from[i]?.takeIf { t.segs.firstOrNull { it.verb != WAIT } === s }
        // build C1's ruling R1.10: a restart eased by an overshooting table can take a level or an extent out of its
        // range between two in-range ends — it is clamped (0..15, 0..F); the validator holds a program's own path in
        // range, so the clamp changes nothing else. The clamped value is what this lens draws, and so what it last drew.
        // §6.6: a scaled DRAW's H eased from h0 to h1; on RETARGET's restart path from the H this lens last drew, clamped
        // to 1..2048 as levels are (R1.10). READING: a restart from an unscaled draw eases from the source's own height
        val h = if (s.verb != DRAW || !s.isScaled) 0
                else if (f == null) eased(s.h0, s.h1 - s.h0, e)
                else { val from = if (f.h > 0) f.h else s.image!!.h
                       eased(from, s.h1 - from, e).coerceIn(1, H_MAX) }
        return when (s.verb) {
            DRAW -> if (f == null) Vals(eased(x0, dx, e), eased(s.y, s.dy, e), eased(s.lvl0, s.lvl1 - s.lvl0, e).coerceIn(0, 15), 0, h)
                    else Vals(eased(f.x, x0 + dx - f.x, e), eased(f.y, s.y + s.dy - f.y, e), eased(f.lvl, s.lvl1 - f.lvl, e).coerceIn(0, 15), 0, h)
            REVEAL -> Vals(x0, s.y, 15, (if (f == null) eased(0, s.extentMax, e) else eased(f.e, s.extentMax - f.e, e)).coerceIn(0, s.extentMax))
            else -> Vals(0, 0, if (s.verb == FILL) s.kind else 0, 0)
        }
    }

    private fun trackVals(o: Instance, i: Int, k: Int, left: Boolean): Vals? =
        stateAt(o.prog.tracks[i], k)?.let { (s, j) -> vals(o, i, s, j, left) }

    /** Decision A.13: the start state — the first non-WAIT segment at j = 0. */
    private fun startVals(o: Instance, i: Int, left: Boolean): Vals? =
        o.prog.tracks[i].segs.firstOrNull { it.verb != WAIT }?.let { vals(o, i, it, 0, left) }

    private fun fill(buf: ByteArray, stride: Int, r: Rect, level: Int) {
        for (yy in r.y until r.bottom) for (xx in r.x until r.right) setNib(buf, stride, xx, yy, level)
    }

    /** A source at (x0, y0), clipped to [clip]; [options] null draws the levels as they are, else §2's v1 LUT
     *  (level bits 0-3, bit 4 transparent tested on the source, bit 5 inverse). */
    private fun blit(buf: ByteArray, stride: Int, img: Img, x0: Int, y0: Int, clip: Rect, options: Int?) {
        val top = (options ?: 15) and 0x0F
        val inverse = options != null && options and 0x20 != 0
        val transparent = options != null && options and 0x10 != 0
        val yA = maxOf(clip.y, y0); val yB = minOf(clip.bottom, y0 + img.h)
        val xA = maxOf(clip.x, x0); val xB = minOf(clip.right, x0 + img.w)
        for (yy in yA until yB) for (xx in xA until xB) {
            val c = img.px[(yy - y0) * img.w + (xx - x0)].toInt()
            if (transparent && c == 0) continue
            setNib(buf, stride, xx, yy, if (options == null) c else ((if (inverse) 15 - c else c) * top) / 15)
        }
    }

    /** §6.6: [img] drawn [hOut] rows tall at (x0, y0) — output row r takes source row [scaledRow] — columns as they
     *  are, clipped to [clip], with DRAW's options (the v1 LUT, as [blit]). */
    private fun blitScaled(buf: ByteArray, stride: Int, img: Img, x0: Int, y0: Int, hOut: Int, clip: Rect, options: Int) {
        val top = options and 0x0F
        val inverse = options and 0x20 != 0
        val transparent = options and 0x10 != 0
        val yA = maxOf(clip.y, y0); val yB = minOf(clip.bottom, y0 + hOut)
        val xA = maxOf(clip.x, x0); val xB = minOf(clip.right, x0 + img.w)
        for (yy in yA until yB) {
            val row = scaledRow(yy - y0, img.h, hOut) * img.w
            for (xx in xA until xB) {
                val c = img.px[row + (xx - x0)].toInt()
                if (transparent && c == 0) continue
                setNib(buf, stride, xx, yy, ((if (inverse) 15 - c else c) * top) / 15)
            }
        }
    }

    // ---------------------------------------------------------------------------- build C2: the local instance
    /** §6.2 step 11: the fire's close set — the entry's [slot] and every open instance whose areas meet [prog]'s, in
     *  slot order. */
    fun closeSet(slot: Int, prog: Prog): List<Int> = (0 until SLOTS).filter { i ->
        val o = slots[i]
        o != null && (i == slot || meets(o.prog, prog))
    }

    /** §6.2 step 11: one close of the fire's close pass — a local instance of the fire's own slot silently whatever
     *  its state (nothing waits on its body, §6.5), any other as [close] does (a drawing one's outcome-1 body). */
    fun closeForFire(i: Int, fireSlot: Int, uptime: Long) {
        val o = slots[i] ?: return
        if (i == fireSlot && o.local) { slots[i] = null; staleMarkOwed = true } else close(i, uptime)
    }

    /** §6.2 step 12, §6.4: the local instance — tag [tag] (the fire's S), t0 the fire's tick, d 0, the entry's period,
     *  its first read range from the walk, no capture range, nothing written into the shadow (END ordinal 0, CRC 0). */
    fun openLocal(slot: Int, tag: Int, period: Int, prog4: Int, prog: Prog, tick: Long, left: Boolean) {
        val inst = Instance(slot, tag, period, prog4, prog, null, arrayOfNulls(prog.tracks.size))
        inst.local = true
        inst.t0 = tick and U32
        inst.last = Array(prog.tracks.size) { startVals(inst, it, left) }
        slots[slot] = inst
    }

    /** §6.5 COMMIT, past mode 26's prefix and its values: PLAY's walk (5; `self` 5), areas (2), `none` backdrops (2),
     *  values (12, with §6.6's H rule) — then PLAY's close pass over the OTHER slots, END(prog, P) into the shadow
     *  always and its CRC, the slot's local instance tagged [s] adopted (a holding one turns done, no body), a local
     *  tagged newer left as it is, anything else closed; one outcome-3 body. Returns 0 or the reason. */
    fun commit(cache: ByteArray?, shadow: ByteArray, stride: Int, left: Boolean, slot: Int, s: Int, prog4: Int, p: IntArray, uptime: Long): Int {
        val v = validate(cache, prog4, p, noSelf = true)
        val prog = v as? Prog ?: return -(v as Int)
        for (i in 0 until SLOTS) if (i != slot && slots[i]?.let { meets(it.prog, prog) } == true) close(i, uptime)
        val end = Instance(slot, s, 0, prog4, prog, null, arrayOfNulls(prog.tracks.size))
        paint(end, end.T, shadow, stride, left)
        val crc = endCrc(shadow, stride, prog, left)
        lastCommitCrc = crc
        val o = slots[slot]
        var adopted: Instance? = null
        if (o != null && o.local && o.tag == s) {
            adopted = o
            o.local = false
            o.endCrc = crc                                  // its own outcome-0 body at step T carries COMMIT's CRC
            if (o.drawing && o.d >= o.T) o.drawing = false   // a holding one turns done, no body
            staleMarkOwed = true                            // "a close or an adoption sets the stale mark"
        } else if (o != null && !(o.local && seqAfter(o.tag, s) > 0)) close(slot, uptime)
        uiCommits[slot] = Pb.cat(Pb.v(1, ++sequence), Pb.v(2, slot), Pb.v(3, s), Pb.v(4, OUTCOME_COMMITTED), Pb.v(5, 0),
            Pb.v(6, adopted?.drawn ?: 0), Pb.v(7, adopted?.folded ?: 0), Pb.v(8, crc), Pb.v(9, if (adopted != null) stepUsForTest else 0), Pb.v(10, 0), Pb.v(11, uptime))
        return 0
    }

    /** §6.5 DISCARD's act: in each named slot ([slot] or 0xFF all) every local instance tagged at or after [s] —
     *  `(int16_t)(tag − S) ≥ 0` — is stopped (a drawing or holding one with its outcome-1 body, a done one emptied
     *  silently); ordinary instances are untouched. */
    fun discard(slot: Int, s: Int, uptime: Long) {
        for (i in named(slot)) { val o = slots[i] ?: continue; if (o.local && seqAfter(o.tag, s) >= 0) close(i, uptime) }
    }

    /** §6.5 SETTLE's pass: in each named slot each local instance tagged at or after [s] that has held its last step
     *  for at least [age] ticks — d = T and `now − (t0 + (T−1)·P) ≥ age`, an unsigned 32-bit difference — is stopped,
     *  outcome 1. True when it stopped one. READING: "held its last step" is a HOLD — drawing at d = T; a done local is
     *  not holding and is left (DISCARD names a done one, SETTLE does not). */
    fun settle(slot: Int, s: Int, age: Int, tick: Long, uptime: Long): Boolean {
        var stopped = false
        for (i in named(slot)) {
            val o = slots[i] ?: continue
            if (!o.local || !o.drawing || o.d < o.T || seqAfter(o.tag, s) < 0) continue
            if (((tick - (o.t0 + (o.T - 1).toLong() * o.period)) and U32) < age) continue
            close(i, uptime); stopped = true
        }
        return stopped
    }

    /** §6.5 SETTLE's cursor test: a local instance tagged at or after [s] still drawing in a named slot. */
    fun localDrawingFrom(slot: Int, s: Int): Boolean =
        named(slot).any { i -> slots[i]?.let { it.local && it.drawing && seqAfter(it.tag, s) >= 0 } == true }

    private fun named(slot: Int): IntRange = if (slot == 0xFF) 0 until SLOTS else slot..slot

    /** §5.5 "meets": the bounding box of any track's pair in one intersects that of any track's pair in the other. */
    private fun meets(a: Prog, b: Prog): Boolean = a.tracks.any { x -> b.tracks.any { y -> x.bbox.overlaps(y.bbox) } }

    // ------------------------------------------------------------------------------------------ notify bodies
    /** Decision A.2: outcomes 0 and 1 carry fields 1–11 (9, the step-drawing µs, is 0: the model has no clock). */
    private fun body(seq: Long, slot: Int, tag: Int, outcome: Int, o: Instance, reason: Int, uptime: Long): ByteArray =
        Pb.cat(Pb.v(1, seq), Pb.v(2, slot), Pb.v(3, tag), Pb.v(4, outcome), Pb.v(5, o.ordinal), Pb.v(6, o.drawn),
            Pb.v(7, o.folded), Pb.v(8, o.endCrc), Pb.v(9, stepUsForTest), Pb.v(10, reason), Pb.v(11, uptime))

    /** Telemetry field 30: open slots (bits 0-3), drawing (4-7), the thread exists (8). */
    fun field30(): Int {
        var v = if (threadExists) 1 shl 8 else 0
        for (i in 0 until SLOTS) slots[i]?.let { v = v or (1 shl i) or (if (it.drawing) 1 shl (4 + i) else 0) }
        return v
    }

    /** A PlanSlot is what the thread reads of one slot without the word: its state, t0, P, T and d. */
    data class PlanSlot(val drawing: Boolean, val t0: Long, val period: Int, val T: Int, val d: Int)

    companion object {
        const val SLOTS = 4
        const val P_MIN = 16
        const val P_MAX = 128
        const val MAX_TRACKS = 8
        const val MAX_SEGS = 8
        const val T_MAX = 255
        const val SEG_BYTES = 32
        const val WAIT = 0; const val FILL = 1; const val DRAW = 2; const val REVEAL = 3
        const val EASE_LINEAR = 0; const val EASE_OUT = 1; const val EASE_TABLE = 2
        const val KIND_RECORD = 0; const val KIND_SELF = 2; const val KIND_LEVEL = 3
        const val BD_SELF = 0x20; const val BD_NONE = 0xFF
        const val PREF_STEPS = 13
        private const val DRAW_OPT = 0x30
        private const val REVEAL_OPT = 0x07
        /** §5.8 field 4. */
        const val OUTCOME_DREW = 0; const val OUTCOME_CLOSED = 1; const val OUTCOME_REFUSED = 2
        /** §6.5 (build C2): outcome 3, committed. */
        const val OUTCOME_COMMITTED = 3
        /** §6.6: H 1..2048; DRAW's anchor 0 top · 1 centre · 2 bottom. */
        const val H_MAX = 2048
        const val ANCHOR_TOP = 0; const val ANCHOR_CENTRE = 1; const val ANCHOR_BOTTOM = 2

        /** §6.6: output row r of an H-row draw takes source row `⌊(2r + 1)·h / (2H)⌋`. */
        fun scaledRow(r: Int, h: Int, hOut: Int): Int = (((2L * r + 1) * h) / (2L * hOut)).toInt()

        /** §6.6: the top row of an H-row draw of an h-row source at y — top `y`, centre `y + ((h − H) >> 1)` (arithmetic),
         *  bottom `y + h − H`. */
        fun anchoredY(y: Int, h: Int, hOut: Int, anchor: Int): Int = when (anchor) {
            ANCHOR_CENTRE -> y + ((h - hOut) shr 1)
            ANCHOR_BOTTOM -> y + h - hOut
            else -> y
        }

        /** §6.5: `(int16_t)(a − b)` — how far 16-bit sequence [a] is after [b], across the wrap. */
        fun seqAfter(a: Int, b: Int): Int = ((a - b) and 0xFFFF).toShort().toInt()
        /** §4's v2 image record: w 1..640, h 1..2048. */
        private const val IMG_MAX_W = 640
        private const val IMG_MAX_H = 2048
        /** The plan's two answers that are not a wait: a step is due now and the thread queues; nothing draws. */
        const val PLAN_ACT = 0L
        const val PLAN_FOREVER = -1L
        const val U32 = 0xFFFFFFFFL
        private const val REF_BOUNDS = 2
        private const val REF_RECORD = 5
        private const val REF_VALUE = 12
        private const val REF_NO_INSTANCE = 16

        /**
         * §5.2's plan, a pure function. Of the drawing slots whose P reads 16..128, with `k = min(T, 1 + ⌊(now − t0) / P⌋)`
         * (an unsigned 32-bit difference): one with k > d is due now — the thread queues ([PLAN_ACT]) unless a
         * refresh is outstanding or the last try or put failed, when it waits one period, the smallest P among the
         * due; otherwise the wait is `max(1, (int32)(due − now))` over the earliest due time `t0 + d·P` (a slot with
         * d ≥ T gives none, so never past step T); with nothing to draw, [PLAN_FOREVER]. READING: "due now with nothing outstanding" is answered 0
         * in the vectors' `W` — the text gives the thread's action, not a wait.
         */
        fun plan(now: Long, slots: List<PlanSlot?>, outstanding: Boolean, failed: Boolean): Long {
            var anyDrawing = false
            var dueP = Int.MAX_VALUE
            var wait = Long.MAX_VALUE
            for (s in slots) {
                // §5.2: "a slot whose P reads outside 16..128 is skipped, and one with d ≥ T has nothing left to draw
                // and gives no due time" — build C2's HOLD (§6.4) is that slot: a table of HOLDs alone waits for ever
                if (s == null || !s.drawing || s.period !in P_MIN..P_MAX || s.d >= s.T) continue
                anyDrawing = true
                val k = minOf(s.T.toLong(), 1 + ((now - s.t0) and U32) / s.period)
                if (k > s.d) { dueP = minOf(dueP, s.period); continue }
                val due = (s.t0 + s.d.toLong() * s.period) and U32
                wait = minOf(wait, ((due - now) and U32).toInt().toLong())
            }
            return when {
                !anyDrawing -> PLAN_FOREVER
                dueP != Int.MAX_VALUE -> if (outstanding || failed) dueP.toLong() else PLAN_ACT
                else -> maxOf(1L, wait)
            }
        }

        /** §5.4 Eases: E[0] = 0; linear ⌊4096·j / n⌋; ease-out 4096 − ⌊4096·(n−j)² / n²⌋; a table its own E[1..n]. */
        fun easeE(ease: Int, n: Int, table: IntArray?): IntArray = IntArray(n + 1) { j ->
            when {
                j == 0 -> 0
                ease == EASE_LINEAR -> ((4096L * j) / n).toInt()
                ease == EASE_OUT -> (4096 - (4096L * (n - j) * (n - j)) / (n.toLong() * n)).toInt()
                else -> table!![j - 1]
            }
        }

        /** §5.4: `v(j) = v0 + ((D · E[j] + 2048) >> 12)`, a signed 32-bit product, `>>` arithmetic. */
        fun eased(v0: Int, d: Int, e: Int): Int = v0 + ((d * e + 2048) shr 12)

        /** §5.6: tight packed rows, (w+1)/2 bytes, high nibble first. */
        fun tightPack(buf: ByteArray, stride: Int, r: Rect): ByteArray {
            val rb = (r.w + 1) / 2
            val out = ByteArray(rb * r.h)
            for (yy in 0 until r.h) for (xx in 0 until r.w) {
                val v = nib(buf, stride, r.x + xx, r.y + yy)
                val i = yy * rb + xx / 2
                out[i] = (if (xx and 1 == 0) v shl 4 else (out[i].toInt() and 0xF0) or v).toByte()
            }
            return out
        }

        /** §5.6: RIGHT's CRC-32 of its shadow over the instance's areas, each tight packed, in track order. */
        fun endCrc(buf: ByteArray, stride: Int, prog: Prog, left: Boolean): Long =
            java.util.zip.CRC32().also { c -> for (t in prog.tracks) c.update(tightPack(buf, stride, t.rect(left))) }.value

        private fun unpack(cache: ByteArray, off: Int, w: Int, h: Int): Img {
            val rb = (w + 1) / 2
            return Img(w, h, ByteArray(w * h) { p ->
                val b = cache[off + (p / w) * rb + (p % w) / 2].toInt() and 0xFF
                (if ((p % w) and 1 == 0) b shr 4 else b and 0x0F).toByte()
            })
        }

        /** A v2 image record at byte [off] (`[w u16][h u16][RLE]`, §4) and the end of its RLE stream, as the walk
         *  finds it; null when it is not one inside the cache. */
        fun record(cache: ByteArray, off: Int): Pair<Img, Int>? {
            if (off + 4 > cache.size) return null
            val w = u16(cache, off); val h = u16(cache, off + 2)
            if (w !in 1..IMG_MAX_W || h !in 1..IMG_MAX_H) return null
            val px = ByteArray(w * h)
            var n = 0
            var pos = off + 4
            while (n < px.size) {
                if (pos >= cache.size) return null
                val op = cache[pos].toInt() and 0xFF
                var cnt = op shr 4
                var used = 1
                if (cnt == 0) {
                    if (pos + 1 >= cache.size) return null
                    cnt = cache[pos + 1].toInt() and 0xFF; used = 2
                    if (cnt == 0) {
                        if (pos + 3 >= cache.size) return null
                        cnt = u16(cache, pos + 2); used = 4
                        if (cnt == 0) return null
                    }
                }
                if (cnt > px.size - n) return null
                repeat(cnt) { px[n++] = (op and 0x0F).toByte() }
                pos += used
            }
            return Img(w, h, px) to pos
        }

        private fun rectAt(b: ByteArray, i: Int) = Rect(u16(b, i), u16(b, i + 2), u16(b, i + 4), u16(b, i + 6))
        fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
        fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
        fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
        private fun nib(buf: ByteArray, stride: Int, x: Int, y: Int): Int {
            val b = buf[y * stride + (x shr 1)].toInt() and 0xFF
            return if (x and 1 == 0) b shr 4 else b and 0x0F
        }
        private fun setNib(buf: ByteArray, stride: Int, x: Int, y: Int, v: Int) {
            val i = y * stride + (x shr 1)
            val b = buf[i].toInt() and 0xFF
            buf[i] = (if (x and 1 == 0) (v shl 4) or (b and 0x0F) else (b and 0xF0) or v).toByte()
        }
    }
}
