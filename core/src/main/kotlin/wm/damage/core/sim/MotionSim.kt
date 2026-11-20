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
 *
 * Build C3 (`FIRMWARE.md` §7, from its text alone) on a build whose [effects] answers true (DamageCaps bit 11):
 * version-2 records ([walk]: the 8-B header with OWN and LAND, 40-B segments, verbs 4–6, kind and backdrop `new`, pref
 * 14–17, the per-verb field rules, the context and in-place rules, the none-backdrop rule of §7.2), the owning instance
 * (§7.3: [Instance.owns], meeting by the owned rect, [jobRects] for the held present, LAND, no END), `new`, the two-axis
 * DRAW and the source window (§7.5), PLAY-AFTER (§7.7: [playAfter], the plan's holds, the hook's waiting test on the
 * states as the present began, the deferred empty), the step budget (§7.12: [cost], [budgetOk]) and telemetry 40.
 * [EffectsSim] draws PIECES, SHRINK and GLYPHS. EFFECTS (flag bit 6) is read once by the caller per message or fire and
 * passed in as `armed`. The fast DRAW path is not modeled (its pixels are C2's, §7.12), nor the late step (§7.10: the
 * model has no thread; the pure plan, so `W`, is unchanged). C3's READINGs are marked "READING (C3)".
 */
class MotionSim(private val scaled: () -> Boolean = { false }, private val effects: () -> Boolean = { false }) {

    /** Who walks a program (§7.2 "where a program may run"): a PLAY, a PLAY-AFTER and a RETARGET; the bound ones a SET,
     *  a fire, a COMMIT (no `self`, no OWN, no `new`, no in-place track). */
    enum class Op { PLAY, AFTER, RETARGET, SET, FIRE, COMMIT; val bound: Boolean get() = this == SET || this == FIRE || this == COMMIT }

    /** A 4bpp source: [w]×[h] levels, row-major. A record also keeps its RLE tokens (each one's last pixel + 1 and its
     *  bytes) for §7.12's L. */
    class Img(val w: Int, val h: Int, val px: ByteArray, val tokEnd: IntArray = IntArray(0), val tokBytes: IntArray = IntArray(0)) {
        /** §7.12's L: the bytes of the tokens that hold a pixel of rows [r0, r1). */
        fun bytesOfRows(r0: Int, r1: Int): Long {
            var sum = 0L
            var start = 0
            val lo = r0 * w; val hi = r1 * w
            for (i in tokEnd.indices) { if (start < hi && tokEnd[i] > lo) sum += tokBytes[i]; start = tokEnd[i] }
            return sum
        }
        /** Rows [r0, r0 + n) as a source of their own (§7.5's source window). */
        fun rows(r0: Int, n: Int): Img = Img(w, n, px.copyOfRange(r0 * w, (r0 + n) * w))
    }

    /** A half-open byte range of the texture cache: `[lo, hi)`. */
    data class Span(val lo: Int, val hi: Int) {
        fun meets(o: Span): Boolean = lo < o.hi && o.lo < hi
    }

    /** One segment after substitution (§5.4; a version-2 segment's 40 B, §7.2), with what the walk found for it. */
    class Seg(val raw: ByteArray) {
        val v2 = raw.size == SEG2_BYTES
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
        /** §7.2: a version-2 segment's `w0 · w1 · aux0 · aux1` (u16; 0 in a version-1 segment). */
        var w0 = if (v2) u16(raw, 32) else 0; var w1 = if (v2) u16(raw, 34) else 0
        var aux0 = if (v2) u16(raw, 36) else 0; var aux1 = if (v2) u16(raw, 38) else 0
        /** §6.6: h0 and h1 both 0 is unscaled. */
        val isScaled: Boolean get() = h0 != 0 || h1 != 0
        /** §7.5: w0 and w1 both 0 is unscaled (DRAW and SHRINK). */
        val isWScaled: Boolean get() = w0 != 0 || w1 != 0
        /** E[0..n] once the values check has passed; the uploaded table's E[1..n] once the walk has read it. */
        var e = IntArray(0)
        var tableE: IntArray? = null
        /** §7.4 PIECES under stagger: the ease over n' = steps − stagger (E[0..n']). */
        var eStag: IntArray? = null
        /** A record source (kind 0 of DRAW or REVEAL). */
        var image: Img? = null
        /** What a DRAW of a record draws: the record, or its source window's rows (§7.5). */
        var source: Img? = null
        /** §7.12's L of a record source: the bytes of the tokens holding a pixel of the source's rows. */
        var tokens = 0L
        /** §7.6: a GLYPHS segment's walked cell list or rain record. */
        var cells: EffectsSim.CellList? = null
        var rain: EffectsSim.Rain? = null
        /** §5.4's F for a REVEAL: the source's height (edges 0/1) or width (2/3). */
        var extentMax = 0
        val moves: Boolean get() = verb == DRAW || verb == REVEAL
        /** §7.2: a source that reads the shadow (DRAW, REVEAL or PIECES of kind 4). */
        val readsNew: Boolean get() = kind == KIND_NEW && (verb == DRAW || verb == REVEAL || verb == PIECES)
        /** §7.2's in-place verbs a program record's own bytes decide (PIECES kind 0, SHRINK); rain's reveal is its record's. */
        val inPlaceVerb: Boolean get() = (verb == PIECES && kind == KIND_OLD) || verb == SHRINK
    }

    class Track(val rectL: Rect, val rectR: Rect, val backdrop: Int, val perLens: Boolean, val segs: List<Seg>) {
        val bbox: Rect = rectL.union(rectR)
        val usesSelf: Boolean get() = backdrop == BD_SELF || segs.any { it.moves && it.kind == KIND_SELF }
        val usesNew: Boolean get() = backdrop == BD_NEW || segs.any { it.readsNew }
        /** §7.2: in place — a segment is PIECES kind 0, SHRINK, or a rain whose record has reveal 1 or 2 (decided once
         *  the walk has read the rain record). */
        val inPlace: Boolean get() = segs.any { it.inPlaceVerb || (it.verb == GLYPHS && (it.rain?.reveal ?: 0) != 0) }
        fun rect(left: Boolean) = if (left) rectL else rectR
    }

    /** A program walked and substituted: its tracks, T, and the first read range (§5.6); a version-2 record's flags. */
    class Prog(val tracks: List<Track>, val first: Span, val version: Int = 1, val own: Boolean = false, val land: Boolean = false) {
        val T: Int = tracks.maxOf { t -> t.segs.sumOf { it.steps } }
        /** §7.3: the owned rect on a lens — the bounding box of the tracks' rects there. */
        fun owned(left: Boolean): Rect = tracks.map { it.rect(left) }.reduce { a, b -> a.union(b) }
        /** §7.3 (as ruled, `C3-decisions.md` H): the MEETING BOX — the bounding box of all the tracks' pairs, over both
         *  lenses — decides every meeting on both lenses alike; the held present and LAND use each lens's own [owned]. */
        val ownedBoth: Rect get() = owned(true).union(owned(false))
    }

    /** The values a track shows at one step, on this lens (§5.11 row 1's "last drawn values"); [h] a scaled DRAW's
     *  eased H (§6.6), 0 for an unscaled one; [w] a two-axis DRAW's eased W (§7.5), 0 for an unscaled width. */
    data class Vals(val x: Int, val y: Int, val lvl: Int, val e: Int, val h: Int = 0, val w: Int = 0)

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
        /** §7.3 (build C3): the owns mark — set by a PLAY or PLAY-AFTER of an OWN program; cleared by LAND and on the
         *  stock path; every close or emptying ends it with the slot (meaningful only while the slot is not empty). */
        var owns = false
        /** §7.7: opened by a PLAY-AFTER naming slot [after]; the recorded (slot, tag, ordinal) of the owning instance it
         *  waits on, cleared at its first drawn step (null: nothing recorded). */
        var after = -1
        var rec: Triple<Int, Int, Int>? = null
        /** §7.12: this instance's cost C, kept from its walk (u, 64-bit). */
        var cost = 0L
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
    fun validate(cache: ByteArray?, prog4: Int, p: IntArray, op: Op = Op.PLAY, armed: Boolean = false): Any {
        val prog = walk(cache, prog4, p, op, armed) ?: return -REF_RECORD
        for (t in prog.tracks) if (!rectOk(t.rectL) || !rectOk(t.rectR) ||
            (t.perLens && (t.rectL.w != t.rectR.w || t.rectL.h != t.rectR.h || t.rectL.y != t.rectR.y))) return -REF_BOUNDS
        // READING: "inside an earlier track's area" — one earlier track whose two rects each contain this track's
        // (the other reading: each lens's rect inside some earlier track's, not necessarily the same one)
        prog.tracks.forEachIndexed { i, t ->
            if (prog.version == 1) {
                if (t.backdrop == BD_NONE && prog.tracks.take(i).none { o ->
                        o.backdrop != BD_NONE && o.rectL.contains(t.rectL) && o.rectR.contains(t.rectR) }) return -REF_BOUNDS
            } else if (!paintsWhole(t) && !(prog.own && revealKeeps(t)) && prog.tracks.take(i).none { o ->
                    paintsWhole(o) && o.rectL.contains(t.rectL) && o.rectR.contains(t.rectR) }) return -REF_BOUNDS
        }
        if (!valuesOk(prog, op)) return -REF_VALUE
        return prog
    }

    /** §7.2's none-backdrop rule for a version-2 record: a track paints its whole area when its backdrop is a level,
     *  `self` or `new`, or it is in place. */
    private fun paintsWhole(t: Track) = t.backdrop != BD_NONE || t.inPlace

    /** §7.2 (as ruled, `C3-decisions.md` H): the exception in an OWN program — a track whose segments are WAITs and exactly
     *  one REVEAL, whose ease never falls (two REVEALs in one such track are not excepted: 2). */
    private fun revealKeeps(t: Track) = t.segs.filter { it.verb != WAIT }.let { m -> m.size == 1 && m[0].verb == REVEAL && nonDecreasing(m[0]) }

    /** §7.2: ease 0 or 1, or a table whose E[0..n] never falls (E[0] = 0). */
    private fun nonDecreasing(s: Seg): Boolean {
        if (s.ease != EASE_TABLE) return true
        val t = s.tableE ?: return true
        var prev = 0
        for (v in t) { if (v < prev) return false; prev = v }
        return true
    }

    private fun rectOk(r: Rect) = r.w > 0 && r.h > 0 && r.right <= Geometry.PANEL_W && r.bottom <= Geometry.PANEL_H

    /** §5.4's record walk with substitution: null for any fault of structure, of a named record or of a table (5).
     *  Decision A.9: reserved bytes, `anchor` and opt bits a verb does not use must be 0; enumerations (version,
     *  counts, backdrop, perLens, verb, ease, kind, a pref's field) are structure. Build C2: a bound [op] refuses a `self`
     *  backdrop or source (a fire, a COMMIT, a SET: §6.2 step 10, §6.3, §6.5); [ownOnly] stops after the record's own
     *  bytes — what §6.3's SET checks — and names no record or table (what the parameters name is checked at a fire). */
    private fun walk(cache: ByteArray?, prog4: Int, p: IntArray, op: Op, armed: Boolean, ownOnly: Boolean = false): Prog? {
        val off = prog4 * 4
        if (cache == null || off + 4 > cache.size) return null
        // §7.2: a version-2 record is malformed (5) unless EFFECTS is armed (read once by the message or fire: [armed]);
        // on a build without bit 11 it is an unknown version, as before
        val version = u8(cache, off)
        val v2 = version == 2 && effects() && armed
        if (version != 1 && !v2) return null
        val nTracks = u8(cache, off + 1)
        val end = off + u16(cache, off + 2)
        val head = if (v2) 8 else 4
        if (nTracks !in 1..MAX_TRACKS || end > cache.size || end < off + head) return null
        var own = false; var land = false
        if (v2) {
            // §7.2: flags bit 0 OWN, bit 1 LAND (needs OWN), bits 2–7 zero; `0 u8 · 0 u16` reserved (A.9: 5)
            val flags = u8(cache, off + 4)
            if (flags and 0xFC != 0 || u8(cache, off + 5) != 0 || u16(cache, off + 6) != 0) return null
            own = flags and 1 != 0; land = flags and 2 != 0
            if (land && !own) return null
            // §7.2 "where a program may run": an OWN program only by PLAY or PLAY-AFTER — a SET, a fire, a COMMIT: 5
            if (own && op.bound) return null
        }
        val segBytes = if (v2) SEG2_BYTES else SEG_BYTES
        var pos = off + head
        val tracks = ArrayList<Track>(nTracks)
        repeat(nTracks) {
            if (pos + 4 > end) return null
            val nSegs = u8(cache, pos); val bd = u8(cache, pos + 1); val pl = u8(cache, pos + 2)
            if (nSegs !in 1..MAX_SEGS || !(bd in 0..15 || bd == BD_SELF || bd == BD_NONE || (v2 && bd == BD_NEW)) || pl > 1 || u8(cache, pos + 3) != 0) return null
            val areaBytes = if (pl == 1) 16 else 8
            if (pos + 4 + areaBytes > end) return null
            val rL = rectAt(cache, pos + 4)
            val rR = if (pl == 1) rectAt(cache, pos + 12) else rL
            pos += 4 + areaBytes
            val segs = ArrayList<Seg>(nSegs)
            repeat(nSegs) {
                if (pos + segBytes > end) return null
                val s = Seg(cache.copyOfRange(pos, pos + segBytes))
                if (!structureOk(s)) return null
                for (pr in intArrayOf(u8(s.raw, 30), u8(s.raw, 31))) if (pr and 0x80 != 0) substitute(s, (pr shr 2) and 0x1F, p[pr and 3])
                // §7.2 ("every check runs after substitution", §5.4): a version-2 segment's unused fields
                if (v2 && !fieldsOk(s)) return null
                segs += s
                pos += segBytes
            }
            tracks += Track(rL, rR, bd, pl == 1, segs)
        }
        if (pos != end) return null                            // §5.4: parsing must end exactly at `length`
        // build C2: a fire, a COMMIT and a SET refuse `self` (5); C3's context rules (§7.2, each 5): `self` never in an
        // OWN program; `new` only in one; PIECES kind 0 and SHRINK only in one (rain's reveal is decided at its record)
        for (t in tracks) {
            if ((op.bound || own) && t.usesSelf) return null
            if (!own && (t.usesNew || t.segs.any { it.inPlaceVerb })) return null
        }
        if (ownOnly) return Prog(tracks, Span(off, end), version, own, land)
        var lo = off; var hi = end
        for (t in tracks) for (s in t.segs) {
            if (s.moves && s.kind == KIND_RECORD) {
                val (img, recEnd) = record(cache, s.src * 4) ?: return null
                s.image = img
                s.source = img
                s.tokens = img.bytesOfRows(0, img.h)
                // §7.5 the source window (DRAW v2, kind 0), after substitution, decided here at the record (5): aux1 = 0
                // is the whole record and then aux0 must be 0; else aux0 + aux1 ≤ h in 32 bits
                if (s.v2 && s.verb == DRAW) {
                    if (s.aux1 == 0) { if (s.aux0 != 0) return null }
                    else {
                        if (s.aux0.toLong() + s.aux1 > img.h) return null
                        s.source = img.rows(s.aux0, s.aux1)
                        s.tokens = img.bytesOfRows(s.aux0, s.aux0 + s.aux1)
                    }
                }
                lo = minOf(lo, s.src * 4); hi = maxOf(hi, recEnd)
            }
            // §7.6: the records a GLYPHS segment names, walked whole (5); a rain with reveal 1 or 2 outside an OWN
            // program is 5 here, where the rain record is walked
            if (s.verb == GLYPHS) {
                val reads = EffectsSim.Reads(lo, hi)
                if (s.kind == GLYPH_CELLS) s.cells = EffectsSim.walkCells(cache, s.src * 4, s.aux0, reads) ?: return null
                else {
                    val r = EffectsSim.walkRain(cache, s.src * 4, reads) ?: return null
                    if (r.reveal != 0 && !own) return null
                    s.rain = r
                }
                lo = reads.lo; hi = reads.hi
            }
            // build C1's ruling R1.3: ease 2 names a table whatever the verb — a FILL's or a WAIT's is walked (5) and
            // counted in the first read range too, though neither uses an eased value
            if (s.ease == EASE_TABLE) {
                val tOff = s.table * 4
                if (tOff + 2 > cache.size) return null
                val n = u8(cache, tOff)
                // decision A.9: n ≠ steps and E[n] ≠ 4096 are 5; the table's reserved byte is required 0. §7.4: under
                // a PIECES stagger the n compared is steps − stagger, and with stagger ≥ steps that comparison alone is
                // skipped (the table is still walked). READING (C3): "with stagger" reads aux1 >> 8 on either layer
                // (layer old's stagger is 12 at the values, after this)
                val stagger = if (s.verb == PIECES) s.aux1 shr 8 else 0
                val nWant = if (stagger >= s.steps) -1 else s.steps - stagger
                if (n < 1 || u8(cache, tOff + 1) != 0 || tOff + 2 + 2 * n > cache.size || (nWant >= 0 && n != nWant)) return null
                val e = IntArray(n) { s16(cache, tOff + 2 + 2 * it) }
                if (e[n - 1] != 4096) return null
                s.tableE = e
                lo = minOf(lo, tOff); hi = maxOf(hi, tOff + 2 + 2 * n)
            }
        }
        // §7.2: an in-place track's backdrop is none and its ease never falls (5). READING (C3): every non-WAIT segment of
        // an in-place track is checked (the text names "its non-WAIT segment's" ease; two of them are 12 at the values)
        for (t in tracks) if (t.inPlace && (t.backdrop != BD_NONE || t.segs.any { it.verb != WAIT && !nonDecreasing(it) })) return null
        return Prog(tracks, Span(lo, hi), version, own, land)
    }

    /** §6.3 SET (build C2): the program record's own structure at [prog4] — header, tracks, segments, enumerations and
     *  reserved bytes — and no `self` (5). Nothing it names is read. Build C3 (§7.2): also no OWN, no `new`, no PIECES
     *  kind 0 or SHRINK, a version-2 record only with EFFECTS [armed]. */
    fun ownStructureOk(cache: ByteArray?, prog4: Int, armed: Boolean = false): Boolean =
        walk(cache, prog4, IntArray(4), Op.SET, armed, ownOnly = true) != null

    private fun structureOk(s: Seg): Boolean {
        // §6.6 (build C2): DRAW's anchor is 0 top · 1 centre · 2 bottom; another anchor is an enumeration outside the
        // build's range (5, the decisions on the build spec B.2). READING: on every other verb the anchor stays one of
        // the zeros §5.5 requires (5), as in C1 — §6.6 gives it a meaning for DRAW only. §7.2 (C3): a version-2 DRAW's
        // and SHRINK's anchor has bits 0–1 vertical and 2–3 horizontal; 3 on either axis, or bits 4–7, is 5
        val a = u8(s.raw, 28)
        val anchorOk = when {
            s.v2 && (s.verb == DRAW || s.verb == SHRINK) -> a and 3 != 3 && (a shr 2) and 3 != 3 && a and 0xF0 == 0
            s.verb == DRAW && scaled() -> a <= ANCHOR_BOTTOM
            else -> a == 0
        }
        if (s.verb > (if (s.v2) GLYPHS else REVEAL) || u8(s.raw, 21) != 0 || u8(s.raw, 29) != 0 || !anchorOk) return false
        for (pr in intArrayOf(u8(s.raw, 30), u8(s.raw, 31))) if (pr and 0x80 != 0 && ((pr shr 2) and 0x1F) > (if (s.v2) PREF_AUX1 else PREF_STEPS)) return false
        // build C1's ruling R1.3: `ease` is checked on every segment (0..2, else 5), whatever the verb
        if (s.ease > EASE_TABLE) return false
        // decision A.9 lists what must be 0; a field a verb does not use and A.9 does not list is ignored (`kind` is
        // read only by the verbs that use it). §7.2: the kinds and opt bits of verbs 4–6, and `new` (4) on DRAW/REVEAL
        return when (s.verb) {
            DRAW -> (s.kind == KIND_RECORD || s.kind == KIND_SELF || (s.v2 && s.kind == KIND_NEW)) && s.opt and DRAW_OPT.inv() == 0
            // READING (§5.4 gives DRAW's options, not REVEAL's): REVEAL uses opt bits 0-2 only and draws its source
            // as it is; the other reading ramps it as DRAW does and lets bits 4/5 through
            REVEAL -> (s.kind == KIND_RECORD || s.kind == KIND_SELF || s.kind == KIND_LEVEL || (s.v2 && s.kind == KIND_NEW)) &&
                s.opt and REVEAL_OPT.inv() == 0
            // §7.2/§7.4: PIECES opt bits 4, 6, 7; layer old: bits 4 and 6 are 5
            PIECES -> (s.kind == KIND_OLD && s.opt and 0x7F == 0) || (s.kind == KIND_NEW && s.opt and 0x2F == 0)
            SHRINK -> s.kind == 0 && s.opt == 0
            GLYPHS -> (s.kind == GLYPH_CELLS || s.kind == GLYPH_RAIN) && s.opt and 0x7F == 0
            else -> s.opt == 0                                 // FILL and WAIT use no opt bit
        }
    }

    /** §7.2's field rules for a version-2 segment, after substitution (5): a field verbs 4–6 do not list, and w0, w1,
     *  aux0, aux1 for every verb that does not list them, must be 0 (§5.4's "ignored" stands for verbs 0–3 otherwise).
     *  PIECES' model 3 and aux1 bits 2–7 (§7.4); DRAW of `self` or `new` takes no source window (§7.5). READING (C3):
     *  RAIN's aux0 ("RAIN: 0" in the table) must be 0; xL/xR, listed for PIECES and GLYPHS, are not required 0 on a
     *  track that is not per-lens. */
    private fun fieldsOk(s: Seg): Boolean = when (s.verb) {
        WAIT, FILL, REVEAL -> s.w0 == 0 && s.w1 == 0 && s.aux0 == 0 && s.aux1 == 0
        DRAW -> s.kind == KIND_RECORD || (s.aux0 == 0 && s.aux1 == 0)
        PIECES -> s.h1 == 0 && s.anchor == 0 && s.aux1 and 3 != 3 && s.aux1 and 0xFC == 0
        SHRINK -> s.xL == 0 && s.xR == 0 && s.y == 0 && s.dxL == 0 && s.dxR == 0 && s.dy == 0 && s.aux0 == 0 && s.aux1 == 0
        else -> s.h1 == 0 && s.w0 == 0 && s.w1 == 0 && s.aux1 == 0 && s.anchor == 0 && (s.kind == GLYPH_CELLS || s.aux0 == 0)
    }

    /** §5.4 Parameters: "u8 fields take the low byte"; s16 fields take the parameter; u16 fields its 16 bits. §7.2
     *  (C3): 14 w0 · 15 w1 · 16 aux0 · 17 aux1, u16 fields taking the whole value. */
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
            14 -> s.w0 = v and 0xFFFF; 15 -> s.w1 = v and 0xFFFF
            16 -> s.aux0 = v and 0xFFFF; 17 -> s.aux1 = v and 0xFFFF
        }
    }

    /** §5.5 "every step's values in range: levels 0..15, extents 0..F, h0/h1 = 0, T ≤ 255" (12), decision A.9's list
     *  (a level source's level, width and height too). READING: a segment's `steps` of 0 is a value (12). */
    private fun valuesOk(prog: Prog, op: Op = Op.PLAY): Boolean {
        // §7.2's values position, C3's checks first: a PLAY-AFTER's program carries OWN; the in-place track's shape
        // (WAITs and exactly one other segment), place (the first track, the only in-place one), still offsets (a rain
        // in place) and later tracks (none meets its area while it moves pixels)
        if (op == Op.AFTER && !prog.own) return false
        val ip = prog.tracks.indices.filter { prog.tracks[it].inPlace }
        if (ip.isNotEmpty()) {
            if (ip != listOf(0)) return false
            val t = prog.tracks[0]
            val moving = t.segs.filter { it.verb != WAIT }
            if (moving.size != 1) return false
            val s = moving[0]
            if (s.verb == GLYPHS && (s.dxL != 0 || s.dxR != 0 || s.dy != 0)) return false
            if (movesPixels(s) && prog.tracks.drop(1).any { it.bbox.overlaps(t.bbox) }) return false
        }
        for (t in prog.tracks) {
            for (s in t.segs) {
                if (s.steps < 1) return false
                // §5.5 "h0/h1 = 0"; §6.6 on a bit-10 build, DRAW only: both 0, or both inside 1..2048 and every step's
                // eased H inside 1..2048 (h0 = 0 with h1 ≠ 0, or the reverse, is 12: C2-decisions I.4). Another verb
                // keeps C1's h0/h1 = 0 (§6.6 is DRAW's; I.7). §7.2 (C3): PIECES' and GLYPHS' h0 is a seed (their h1 is
                // the walk's), SHRINK's h0 and h1 follow §7.5
                if ((s.h0 != 0 || s.h1 != 0) && s.verb <= REVEAL && (s.verb != DRAW || !scaled())) return false
                when (s.verb) {
                    FILL -> { if (s.kind > 15) return false; s.e = IntArray(s.steps + 1) }
                    DRAW -> {
                        s.e = easeE(s.ease, s.steps, s.tableE)
                        if (s.e.any { eased(s.lvl0, s.lvl1 - s.lvl0, it) !in 0..15 }) return false
                        // §6.6: "a scaled DRAW's source is a record — `self` with h0/h1 ≠ 0 is 12 … at a PLAY, a COMMIT
                        // and a fire alike" (C2-decisions M.2); a RETARGET whose parameters scale it too. At a COMMIT and
                        // a fire the walk's `self` 5 decides first, so the 12 is reached at a PLAY and a RETARGET. §7.5
                        // (C3): `self` only unscaled in both axes (12)
                        if ((s.isScaled || s.isWScaled) && s.kind == KIND_SELF) return false
                        if (s.isScaled && (s.h0 !in 1..H_MAX || s.h1 !in 1..H_MAX)) return false
                        if (s.isScaled && (1..s.steps).any { eased(s.h0, s.h1 - s.h0, s.e[it]) !in 1..H_MAX }) return false
                        // §7.5: w0, w1 both 0 or both 1..2048 with every step's W in 1..2048 (one 0 and one not: 12)
                        if (s.isWScaled && (s.w0 !in 1..H_MAX || s.w1 !in 1..H_MAX)) return false
                        if (s.isWScaled && (1..s.steps).any { eased(s.w0, s.w1 - s.w0, s.e[it]) !in 1..H_MAX }) return false
                    }
                    REVEAL -> {
                        if (s.kind == KIND_LEVEL && (s.src > 15 || s.dxL < 1 || s.dy < 1)) return false
                        val (w, h) = when (s.kind) {
                            KIND_RECORD -> s.image!!.w to s.image!!.h
                            KIND_SELF, KIND_NEW -> t.rectL.w to t.rectL.h       // §7.3: `new` is the area's size
                            else -> s.dxL to s.dy
                        }
                        s.extentMax = if ((s.opt and 3) <= 1) h else w
                        s.e = easeE(s.ease, s.steps, s.tableE)
                        if (s.e.any { eased(0, s.extentMax, it) !in 0..s.extentMax }) return false
                    }
                    PIECES -> if (!piecesOk(t, s)) return false
                    SHRINK -> {
                        // §7.5: w0 = w1 = 0 unscaled, else 0 ≤ w1 ≤ w0 ≤ Wa; h likewise against Ha; src 0..15. READING (C3):
                        // §5.5's "levels 0..15" binds SHRINK's eased level too (it feeds G, §7.4); §7.2 names DRAW, PIECES
                        // and GLYPHS only
                        val a = t.rectL
                        if (s.isWScaled && !(s.w1 <= s.w0 && s.w0 <= a.w)) return false
                        if (s.isScaled && !(s.h1 <= s.h0 && s.h0 <= a.h)) return false
                        if (s.src > 15) return false
                        s.e = easeE(s.ease, s.steps, s.tableE)
                        if (s.e.any { eased(s.lvl0, s.lvl1 - s.lvl0, it) !in 0..15 }) return false
                    }
                    GLYPHS -> {
                        s.e = easeE(s.ease, s.steps, s.tableE)
                        if (s.e.any { eased(s.lvl0, s.lvl1 - s.lvl0, it) !in 0..15 }) return false
                        // §7.6: n − N ≥ 1 with n the segment's steps after substitution (12)
                        if (s.kind == GLYPH_CELLS && s.steps - s.cells!!.noise < 1) return false
                    }
                    else -> s.e = IntArray(s.steps + 1)
                }
            }
        }
        return prog.T <= T_MAX
    }

    /** §7.4's values (12) for a PIECES segment, and its eases: the grid, the model's rules, stagger, the layers' rules,
     *  every eased level. READING (C3): under stagger with an uploaded table (n = steps − stagger), lvl(t) reads the
     *  table at min(t, n') (E[n'] = 4096 holds past it); with ease 0 or 1 it is eased over the segment's steps as DRAW's
     *  (the other reading: the level eased over n' too). */
    private fun piecesOk(t: Track, s: Seg): Boolean {
        val gw = s.aux0 and 0xFF; val gh = s.aux0 shr 8
        val model = s.aux1 and 3; val stagger = s.aux1 shr 8
        val f0 = s.w0.toShort().toInt(); val f1 = s.w1.toShort().toInt()
        if (gw !in 1..64 || gh !in 1..64 || gw * gh > 256) return false
        if (model == EffectsSim.MODEL_UNIFORM && s.aux0 != 0x0101) return false
        if (model == EffectsSim.MODEL_RADIAL && (s.dxL != 0 || s.dxR != 0 || s.dy != 0)) return false
        if (stagger != 0 && (s.kind != KIND_NEW || s.steps - stagger < 1)) return false
        if (s.kind == KIND_NEW && s.src != 0) return false
        if (s.kind == KIND_OLD) {
            if (s.src > 15 || f0 != 0) return false
            if (model == EffectsSim.MODEL_RADIAL && f1 < 0) return false
            if (model == EffectsSim.MODEL_RANDOM && (gw != 1 || s.dy != 0)) return false
        }
        if (stagger == 0) s.e = easeE(s.ease, s.steps, s.tableE)
        else {
            val n1 = s.steps - stagger
            s.eStag = easeE(s.ease, n1, s.tableE)
            s.e = if (s.ease == EASE_TABLE) IntArray(s.steps + 1) { j -> if (j == 0) 0 else s.tableE!![minOf(j, n1) - 1] }
                  else easeE(s.ease, s.steps, null)
        }
        if (s.e.any { eased(s.lvl0, s.lvl1 - s.lvl0, it) !in 0..15 }) return false
        return true
    }

    /** §7.2: an in-place segment moves pixels unless it is PIECES with f1 = 0, PIECES uniform or random with dxL = dxR =
     *  dy = 0, SHRINK with both axes unscaled, or a rain. */
    private fun movesPixels(s: Seg): Boolean = when (s.verb) {
        PIECES -> !(s.w1.toShort().toInt() == 0 ||
                    ((s.aux1 and 3) != EffectsSim.MODEL_RADIAL && s.dxL == 0 && s.dxR == 0 && s.dy == 0))
        SHRINK -> s.isWScaled || s.isScaled
        else -> false
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
             cap4: Int, p: IntArray, tick: Long, uptime: Long, armed: Boolean = false): Int {
        val v = validate(cache, prog4, p, Op.PLAY, armed)
        val prog = v as? Prog ?: return -(v as Int)
        val layout = captureLayout(prog, cap4)
        // READING: one pass over the slots in slot order closes each instance that is this slot's, meets this one's
        // areas, or meets the capture range (the other reading: three passes in the order §5.5 lists them) — the
        // order the sequences are taken in. §7.3 (C3): an owning instance meets by its owned rect
        val closes = (0 until SLOTS).filter { i ->
            val o = slots[i]
            o != null && (i == slot || meets(o, prog) ||
                (layout != null && (o.prog.first.meets(layout.second) || o.cap?.meets(layout.second) == true)))
        }
        // §7.12 (C3): the step budget after the values (12), before the capture range
        val cost = if (effects()) cost(prog) else 0L
        if (effects() && prog.version == 2 && !budgetOk(slot, cost, closes.toSet(), null)) return REF_VALUE
        if (layout != null && (layout.second.hi > cache!!.size || layout.second.meets(prog.first))) return REF_RECORD
        for (i in closes) close(i, uptime)
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
        // §5.6: END into the shadow at the PLAY; §7.3 (C3): an OWN program writes none, its CRC is the shadow's over the
        // areas right after the message's closes, and the instance owns
        if (!prog.own) paint(inst, inst.T, shadow, stride, left)
        inst.endCrc = endCrc(shadow, stride, prog, left)
        inst.owns = prog.own
        inst.cost = cost
        inst.t0 = tick and U32
        inst.last = Array(prog.tracks.size) { startVals(inst, it, left) }
        slots[slot] = inst
        threadExists = true
        return 0
    }

    /**
     * PLAY-AFTER (§7.7, mode 26 sub 8; build C3) past the message's prefix: the walk (5) → areas (2) → none-backdrop
     * (2) → values (12: the program carries OWN first) → the step budget (12, the waiting pair as the larger). Then
     * PLAY's close pass, except that an owning instance in slot [after] is not closed; when [after] holds an owning
     * instance, drawing or done, its (slot, tag, ordinal) is recorded and t0 is the later (signed 32-bit) of now and
     * its t0 + T·P when it draws, now when it is done; otherwise t0 = now. No END (the program is OWN); the END CRC;
     * the instance opens owning. Returns 0 or the reason.
     */
    fun playAfter(cache: ByteArray?, shadow: ByteArray, stride: Int, left: Boolean, slot: Int, period: Int, prog4: Int, tag: Int,
                  after: Int, p: IntArray, tick: Long, uptime: Long, armed: Boolean): Int {
        val v = validate(cache, prog4, p, Op.AFTER, armed)
        val prog = v as? Prog ?: return -(v as Int)
        val closes = (0 until SLOTS).filter { i ->
            val o = slots[i]
            o != null && !(i == after && o.owns) && (i == slot || meets(o, prog))
        }
        val cost = cost(prog)
        val owner = slots[after]?.takeIf { it.owns }
        if (!budgetOk(slot, cost, closes.toSet(), if (owner?.drawing == true) after else null)) return REF_VALUE
        for (i in closes) close(i, uptime)
        val now = tick and U32
        val inst = Instance(slot, tag, period, prog4, prog, null, arrayOfNulls(prog.tracks.size))
        inst.endCrc = endCrc(shadow, stride, prog, left)
        inst.owns = true
        inst.cost = cost
        inst.after = after
        inst.t0 = now
        if (owner != null) {
            inst.rec = Triple(after, owner.tag, owner.ordinal)
            if (owner.drawing) {
                val end = (owner.t0 + owner.T.toLong() * owner.period) and U32
                if (((end - now) and U32).toInt() > 0) inst.t0 = end
            }
        }
        inst.last = Array(prog.tracks.size) { startVals(inst, it, left) }
        slots[slot] = inst
        threadExists = true
        return 0
    }

    // ------------------------------------------------------------------------------------- §7.12 the step budget
    /**
     * §7.12: a program's cost C in u — over its tracks, 1,024 + the backdrop's term + the largest of its segments'
     * terms; a LAND program adds its owned rect's area (the larger lens's). Every term from both lenses' rects (A the
     * larger area, Wa and Ha the larger width and height), unsigned 64-bit.
     */
    fun cost(prog: Prog): Long {
        var c = 0L
        for (t in prog.tracks) {
            val a = maxOf(t.rectL.w.toLong() * t.rectL.h, t.rectR.w.toLong() * t.rectR.h)
            val wa = maxOf(t.rectL.w, t.rectR.w).toLong(); val ha = maxOf(t.rectL.h, t.rectR.h).toLong()
            val bd = when (t.backdrop) { BD_NONE -> 0L; BD_SELF -> 10 * a; else -> a }
            c += 1024 + bd + (t.segs.maxOfOrNull { segCost(it, a, wa, ha) } ?: 0L)
        }
        if (prog.land) c += maxOf(prog.owned(true).w.toLong() * prog.owned(true).h, prog.owned(false).w.toLong() * prog.owned(false).h)
        return c
    }

    private fun segCost(s: Seg, a: Long, wa: Long, ha: Long): Long = when (s.verb) {
        WAIT -> 0L
        FILL -> a
        DRAW -> when (s.kind) {
            KIND_SELF -> 10 * a
            KIND_NEW -> if (s.isWScaled) 10 * a else 3 * a
            else -> if (s.isWScaled) 26 * s.tokens + 10 * a + s.image!!.w.toLong() * ha else 26 * s.tokens + 4 * a
        }
        REVEAL -> when (s.kind) { KIND_LEVEL, KIND_NEW -> a; KIND_SELF -> 10 * a; else -> 26 * s.tokens + 4 * a }
        PIECES -> 3 * a
        SHRINK -> if (s.isWScaled) 13 * a else 3 * a
        else -> s.cells?.let { l ->
            // CELLS: per cell its box (clipped by Wa, Ha) + 16·Lg + 10·g + 64 over the glyphs its old, new and the
            // alphabet's codes name in its font — codes 0 and 32 name no glyph for this sum
            l.cells.sumOf { cell ->
                val gs = (listOf(cell.old, cell.new) + l.alphabet.toList()).filter { it != 0 && it != 32 }.map { l.glyphs[cell.font]!![it]!! }
                minOf(cell.bw.toLong(), wa) * minOf(cell.bh.toLong(), ha) + 16L * (gs.maxOfOrNull { it.rle } ?: 0) +
                    10L * (gs.maxOfOrNull { it.w * it.h } ?: 0) + 64
            }
        } ?: s.rain!!.let { r ->
            // RAIN: A + cols·trail·(11·cw·ch + 16·Lg + 64), taken literally — every column times the trail, whatever
            // R and the area clip. READING (C3; the term and codes 0/32 are with the contract's author, raised by the
            // vector lane): Lg over every code the alphabet names, 32 included — the conservative form (counts more)
            a + r.cols.toLong() * r.trail * (11L * r.cw * r.ch + 16L * (r.glyphs.values.maxOfOrNull { it.rle } ?: 0) + 64)
        }
    }

    /**
     * §7.12's sum: every slot of this lens in state drawing once the operation's [closed] slots are closed, each by the
     * C its walk kept, the instance opened or restarted in [slot] by [c] — except that an instance waiting on another
     * (a PLAY-AFTER whose `after` slot still holds its recorded triple; [newWaitsOn] for the one opened now) and that
     * other count as the larger, and a chain as its largest member. True when the sum is at most the cap. READING (C3):
     * waiters joined into one group count as its largest member even when two wait on one instance (the other reading:
     * two waiters released by one present both count).
     */
    private fun budgetOk(slot: Int, c: Long, closed: Set<Int>, newWaitsOn: Int?): Boolean {
        val cost = LongArray(SLOTS) { i -> slots[i]?.takeIf { i != slot && i !in closed && it.drawing }?.cost ?: -1L }
        cost[slot] = c
        val group = IntArray(SLOTS) { it }
        fun root(x: Int): Int { var r = x; while (group[r] != r) r = group[r]; return r }
        for (i in 0 until SLOTS) {
            if (cost[i] < 0) continue
            val w = if (i == slot) newWaitsOn else slots[i]!!.rec?.let { r ->
                slots[r.first]?.takeIf { it.tag == r.second && it.ordinal == r.third }?.let { r.first } }
            if (w != null && cost[w] >= 0) group[root(i)] = root(w)
        }
        val largest = LongArray(SLOTS)
        for (i in 0 until SLOTS) if (cost[i] >= 0) largest[root(i)] = maxOf(largest[root(i)], cost[i])
        var sum = 0L
        for (v in largest) { sum += v; if (sum > BUDGET_CAP) return false }
        return true
    }

    /** §7.12 at a fire (§6.2 step 10): the local instance's [c] in [slot], the fire's close set closed. Version-1
     *  programs are counted, never refused. */
    fun fireBudgetOk(prog: Prog, slot: Int, c: Long): Boolean =
        prog.version == 1 || budgetOk(slot, c, closeSet(slot, prog).toSet(), null)

    /** RETARGET (§5.5): an open instance only (16); the shape rule's 12 right after the empty-slot test, decided from
     *  the instance's validated program (a parameter cannot change a verb or a segment count) — build C1's ruling
     *  R1.9; then the walk with the new parameters and the checks after it, the new first range not meeting the
     *  capture range (5). Build C3 (§7.2): the shape rule also refuses an OWN program, a PIECES, SHRINK or GLYPHS
     *  segment, a `new` source or backdrop; the step budget after the values (12, the instance unchanged). */
    fun retarget(cache: ByteArray?, shadow: ByteArray, stride: Int, left: Boolean, slot: Int, p: IntArray, tick: Long, armed: Boolean = false): Int {
        val inst = slots[slot] ?: return REF_NO_INSTANCE
        if (!inst.prog.tracks.all { retargetable(it) }) return REF_VALUE
        if (effects() && (inst.prog.own || inst.prog.tracks.any { t -> t.usesNew || t.segs.any { it.verb >= PIECES } })) return REF_VALUE
        val v = validate(cache, inst.prog4, p, Op.RETARGET, armed)
        val prog = v as? Prog ?: return -(v as Int)
        val cost = if (effects()) cost(prog) else 0L
        if (effects() && prog.version == 2 && !budgetOk(slot, cost, emptySet(), null)) return REF_VALUE
        if (inst.cap != null && prog.first.meets(inst.cap)) return REF_RECORD
        inst.cost = cost
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
        // build C3 (§5.5's table): sub 8 PLAY-AFTER is known on a bit-11 build and names its slot. READING (C3): its tag
        // (bytes 6–7, as a PLAY's) is field 3 — "fields 1, 2 and 3 when the message holds them" (A.2); C1's "the tag only
        // a PLAY holds" was written when only a PLAY held one
        val after = effects() && sub == SUB_AFTER
        val known = sub in (if (scaled()) 0..5 else 0..2) || after
        uiRefusal = Pb.cat(Pb.v(1, ++sequence),
            if (known && msg.size >= 3) Pb.v(2, msg[2].toInt() and 0xFF) else ByteArray(0),
            if ((sub == 0 || after) && msg.size >= 8) Pb.v(3, u16(msg, 6))
            else if (sub == 3 && known && msg.size >= 5) Pb.v(3, u16(msg, 3)) else ByteArray(0),
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
     *  instance's last (the stale mark, decision A.12). [whole]: the present goes whole for the engine — an instance
     *  was drawing and the hook drew a step, lost its claim (R1.13) or took the stock path; false when every drawing
     *  instance was passed over (§7.3: an owner with k ≤ d; §7.7: a PLAY-AFTER not yet due), so a hint may stand. On a
     *  build without owners or PLAY-AFTER, [whole] is "an instance was drawing", as before. */
    data class Hook(val drew: Boolean, val lastStep: Boolean, val whole: Boolean = drew)

    /**
     * The copy hook's engine function (§5.2, §5.3): [draws] is false on the stock path, which draws no step and turns
     * every drawing instance done with no notify. It clears the outstanding mark on every run, claimed or not.
     */
    /** Tests only (G4, decisions BF): a step's drawing time this lens reports in its bodies' field 9, which the model does
     *  not otherwise know (0: none, as before). When it is longer than an instance's period, the step after step 1 is
     *  passed over once per instance — the one fold per motion measured on his glasses when the steps outgrew P. */
    @Volatile var stepUsForTest: Long = 0L

    private fun slowFor(o: Instance): Boolean = stepUsForTest > 0L && stepUsForTest * 1024L > o.period * 1_000_000L

    fun hookPresent(fb: ByteArray, stride: Int, left: Boolean, tick: Long, draws: Boolean, uptime: Long, preserve: Boolean = false,
                    shadow: ByteArray? = null): Hook {
        outstanding = false
        val drawingAtEntry = anyDrawing
        // §7.3 (C3): the stock path ends every owns mark, a done owner's too — so a table of done owners still flips
        if (!drawingAtEntry && (draws || slots.none { it?.owns == true })) return Hook(false, false)
        // a lost claim folds the step; never waits. READING (C3): with only done owners open nothing is folded or counted
        if (wordHeld) { if (drawingAtEntry) foldsSinceBoot++; return Hook(false, false, drawingAtEntry) }
        if (!draws) { for (o in slots) if (o != null) { o.drawing = false; o.owns = false }; return Hook(false, false, drawingAtEntry) }
        // §7.7: each slot's waiting test is decided on the slot states as the present began
        val began = slots.map { o -> o?.let { Triple(it.tag, it.ordinal, it.drawing) } }
        var last = false
        var drew = false
        for (o in slots) {
            if (o == null || !o.drawing) continue
            // §7.7 / §5.3 (C3): a PLAY-AFTER whose t0 is ahead, or whose `after` slot held its recorded instance drawing
            // when the present began, has nothing due and is drawn on no path
            if (o.after >= 0 && waits(o, tick, began)) continue
            val k = dueStep(o, tick).let { k0 -> if (slowFor(o) && o.d == 1 && k0 == 2 && o.T > 2) 3 else k0 }
            // §5.3: the preserve path draws an instance only when k > d (its framebuffer still holds step d — a HOLD's
            // step T too, §6.4); the direct path redraws every drawing one over the shadow it has just copied. §7.3 (C3):
            // an owning instance draws only at k > d on every path (its owned rect was not copied)
            if ((preserve || o.owns) && k <= o.d) continue
            // §7.7: at its first drawn step a PLAY-AFTER empties the recorded instance, silently, when its slot still
            // holds that triple and it was done when the present began. READING (C3): the emptying owes no stale mark
            // (this present draws a step and goes whole; the text calls it an emptying, not a close)
            o.rec?.let { r ->
                val a = slots[r.first]
                if (a != null && a.tag == r.second && a.ordinal == r.third && began[r.first]?.third == false) slots[r.first] = null
                o.rec = null
            }
            // decision A.11: field 6 counts distinct steps (k > d), field 7 the steps passed over
            if (k > o.d) { o.drawn++; val skipped = k - o.d - 1; o.folded += skipped; foldsSinceBoot += skipped }
            // §7.3 (C3): with LAND, step T copies the shadow into the owned rect in place of its tracks and ends owning
            if (o.owns && o.prog.land && k == o.T) { EffectsSim.copy(shadow!!, fb, stride, o.prog.owned(left)); o.owns = false }
            else paint(o, k, fb, stride, left, shadow, o.d)
            drew = true
            o.d = k
            o.last = Array(o.prog.tracks.size) { trackVals(o, it, k, left) }
            // §6.4 HOLD: at step T a local instance stays drawing at d = T and owes no 114 (nothing has ended)
            if (k == o.T && !o.local) {
                o.drawing = false
                owed[o.slot] = body(++sequence, o.slot, o.tag, OUTCOME_DREW, o, 0, uptime)
                last = true
            }
        }
        return Hook(drew, last, drew)
    }

    /** §7.7: a PLAY-AFTER waits while its t0 is ahead of now (`(int32_t)(now − t0) < 0`), or after t0 while its `after`
     *  slot holds the recorded (slot, tag, ordinal) in state drawing — [states] as they stood when the present began
     *  (the hook) or as read now (the plan). */
    private fun waits(o: Instance, tick: Long, states: List<Triple<Int, Int, Boolean>?>): Boolean {
        if (((tick - o.t0) and U32).toInt() < 0) return true
        val r = o.rec ?: return false
        val s = states[r.first] ?: return false
        return s.first == r.second && s.second == r.third && s.third
    }

    private fun dueStep(o: Instance, tick: Long): Int =
        minOf(o.T.toLong(), 1 + ((tick - o.t0) and U32) / o.period).toInt()

    /** The thread's plan at [tick] for this lens's table (§5.2). §7.7 (C3): a PLAY-AFTER whose t0 is ahead has its due
     *  time at t0; one that waits on its recorded instance, at now plus its own P. READING (C3; with the contract's
     *  author, raised by the vector lane): a waiting slot's due time joins §5.2's rule as one more candidate — when its
     *  late out half is itself due (k > d) the plan acts (0) or, outstanding, waits the smallest due P; the waiter's
     *  now + P shows in `W` only when nothing is due. */
    fun plan(tick: Long, failed: Boolean = false): Long {
        val now = slots.map { o -> o?.let { Triple(it.tag, it.ordinal, it.drawing) } }
        return plan(tick, slots.map { o -> o?.let {
            val hold = if (it.after < 0 || !waits(it, tick, now)) null
                       else if (((tick - it.t0) and U32).toInt() < 0) it.t0 else (tick + it.period) and U32
            PlanSlot(it.drawing, it.t0, it.period, it.T, it.d, hold) } }, outstanding, failed)
    }

    /** The job's owned rects on this lens (§7.3): the owned rect of every slot that owns — drawing, waiting or done —
     *  read once by `present_shadow` for the present it queues. */
    fun jobRects(left: Boolean): List<Rect> = slots.filter { it?.owns == true }.map { it!!.prog.owned(left) }

    /** Telemetry 40 (§7.9): bits 0–3 the slots that own, bits 4–7 the slots in state drawing, opened by a PLAY-AFTER,
     *  with d = 0 (a closed or emptied slot never shows). */
    fun field40(): Int {
        var v = 0
        for (i in 0 until SLOTS) slots[i]?.let { if (it.owns) v = v or (1 shl i); if (it.drawing && it.after >= 0 && it.d == 0) v = v or (1 shl (4 + i)) }
        return v
    }

    /** §7.3 (as ruled, H): a rebuild of the container ends every owning instance — each closed as a close does (outcome 1
     *  if it was drawing, a done owner sends none; the panel's stale mark owed). True when it ended one. */
    fun closeOwners(uptime: Long): Boolean {
        var ended = false
        for (i in 0 until SLOTS) if (slots[i]?.owns == true && close(i, uptime)) ended = true
        return ended
    }

    /** Whether any slot owns (§7.3: `present_shadow` reads the marks first and claims only when one does). */
    val anyOwns: Boolean get() = slots.any { it?.owns == true }

    // --------------------------------------------------------------------------------------------- the paint
    /** §5.4 "Per step, per track": every track's whole area, backdrop then state, in track order. Build C3: [shadow]
     *  is the live shadow `new` reads (§7.3; null where no `new` can be named — an END, a bound program), [b] the base
     *  step of an in-place track (§7.2: b = d, the step the instance last drew). */
    fun paint(o: Instance, k: Int, buf: ByteArray, stride: Int, left: Boolean, shadow: ByteArray? = null, b: Int = 0) {
        o.prog.tracks.forEachIndexed { i, t ->
            val area = t.rect(left)
            when (t.backdrop) {
                BD_NONE -> {}
                BD_SELF -> blit(buf, stride, o.caps[i]!!, area.x, area.y, area, null)
                BD_NEW -> EffectsSim.copy(shadow!!, buf, stride, area)     // §7.12: a byte copy from the shadow's same place
                else -> fill(buf, stride, area, t.backdrop)
            }
            val (s, j) = stateAt(t, k) ?: return@forEachIndexed
            val v = vals(o, i, s, j, left)
            when (s.verb) {
                FILL -> fill(buf, stride, area, s.kind)
                // the level is 0..15 here: [vals] clamps a RETARGET's overshoot (ruling R1.10)
                DRAW -> {
                    val opts = (s.opt and 0x30) or v.lvl
                    val src = when (s.kind) { KIND_SELF -> o.caps[i]!!; KIND_NEW -> newSource(shadow!!, stride, area); else -> s.source!! }
                    if (!s.isScaled && !s.isWScaled) blit(buf, stride, src, v.x, v.y, area, opts)
                    // §6.6: a record H rows tall (a scaled `self` is refused 12), placed by the anchor (H ≥ 1 by the H
                    // rule and the restart's clamp; 0 would draw no row)
                    else if (!s.v2) { if (v.h > 0) blitScaled(buf, stride, src, v.x, anchoredY(v.y, src.h, v.h, s.anchor), v.h, area, opts) }
                    // §7.5 (C3): two axes — W and H each eased or the source's own, the anchors per axis
                    else {
                        val wOut = if (s.isWScaled) v.w else src.w
                        val hOut = if (s.isScaled) v.h else src.h
                        if (wOut > 0 && hOut > 0) draw2(buf, stride, src, anchoredY(v.x, src.w, wOut, (s.anchor shr 2) and 3),
                            anchoredY(v.y, src.h, hOut, s.anchor and 3), wOut, hOut, area, opts)
                    }
                }
                REVEAL -> reveal(buf, stride, o, i, s, v, area, shadow)
                PIECES -> {
                    val a = piecesArgs(o, t, s, left)
                    if (s.kind == KIND_NEW) EffectsSim.piecesNew(buf, stride, shadow!!, area, a, j, v.lvl, s.opt and 0x10 != 0)
                    else EffectsSim.piecesOld(buf, stride, area, a, stateAt(t, b)!!.second, j, gTable(t, b, k), s.src)
                }
                SHRINK -> {
                    val (wb, hb) = shrinkSize(t, s, b, left); val (wk, hk) = shrinkSize(t, s, k, left)
                    EffectsSim.shrink(buf, stride, area, wb, hb, wk, hk, s.anchor, gTable(t, b, k), s.src)
                }
                GLYPHS -> if (s.kind == GLYPH_CELLS) EffectsSim.cells(buf, stride, area, s.cells!!, seed(o, s), s.steps, j, v.x, v.y, v.lvl)
                          else EffectsSim.rain(buf, stride, shadow, area, s.rain!!, seed(o, s), j, v.e, v.x, v.y, v.lvl)
            }
        }
    }

    /** §7.3 the source `new`: this lens's track area of the live shadow, of the area's width and height. */
    private fun newSource(shadow: ByteArray, stride: Int, area: Rect) = Img(area.w, area.h, EffectsSim.grab(shadow, stride, area))

    /** §7.2: a PIECES or GLYPHS seed — h0 after substitution, exclusive-or the instance's tag with opt bit 7. */
    private fun seed(o: Instance, s: Seg): Int = s.h0 xor (if (s.opt and 0x80 != 0) o.tag else 0)

    private fun piecesArgs(o: Instance, t: Track, s: Seg, left: Boolean): EffectsSim.PiecesArgs {
        val r = left || !t.perLens
        return EffectsSim.PiecesArgs(s.aux0 and 0xFF, s.aux0 shr 8, s.aux1 and 3, s.aux1 shr 8, s.w0.toShort().toInt(), s.w1.toShort().toInt(),
            if (r) s.xL else s.xR, s.y, if (r) s.dxL else s.dxR, s.dy, seed(o, s), s.opt and 0x40 != 0, s.e, s.eStag)
    }

    /** §7.4: G(b → k) for a track — L_t its level at instance step t by §5.4's state rule, L_0 = 15. */
    private fun gTable(t: Track, b: Int, k: Int): IntArray = EffectsSim.gTable(IntArray(k - b + 1) { i ->
        val step = b + i
        if (step == 0) 15 else stateAt(t, step)!!.let { (s, j) -> eased(s.lvl0, s.lvl1 - s.lvl0, s.e[j]) }
    })

    /** §7.5: SHRINK's size (W, H) at instance [step] — (Wa, Ha) at step 0, else W and H eased at the state's local step
     *  (an unscaled axis keeps the area's size). */
    private fun shrinkSize(t: Track, s: Seg, step: Int, left: Boolean): Pair<Int, Int> {
        val a = t.rect(left)
        if (step == 0) return a.w to a.h
        val e = s.e[stateAt(t, step)!!.second]
        return (if (s.isWScaled) eased(s.w0, s.w1 - s.w0, e) else a.w) to (if (s.isScaled) eased(s.h0, s.h1 - s.h0, e) else a.h)
    }

    /** §7.5 the two-axis DRAW: [img] drawn [wOut] × [hOut] at (x0, y0) — output column c takes source column
     *  ⌊(2c + 1)·w / (2W)⌋, output row r source row ⌊(2r + 1)·h / (2H)⌋ — through DRAW's LUT, transparency tested on
     *  the source, only the output pixels inside [clip] visited (§7.12). */
    private fun draw2(buf: ByteArray, stride: Int, img: Img, x0: Int, y0: Int, wOut: Int, hOut: Int, clip: Rect, options: Int) {
        val lut = EffectsSim.lut(options and 0x2F)
        val transparent = options and 0x10 != 0
        val yA = maxOf(clip.y, y0); val yB = minOf(clip.bottom, y0 + hOut)
        val xA = maxOf(clip.x, x0); val xB = minOf(clip.right, x0 + wOut)
        for (yy in yA until yB) {
            val row = scaledRow(yy - y0, img.h, hOut) * img.w
            for (xx in xA until xB) {
                val c = img.px[row + scaledRow(xx - x0, img.w, wOut)].toInt()
                if (transparent && c == 0) continue
                setNib(buf, stride, xx, yy, lut[c])
            }
        }
    }

    /** §5.4 REVEAL: the source at rest at (x, y); wipe shows its e rows or columns at the edge, slide moves it in
     *  from the edge by F − e. §7.3 (C3): REVEAL of `new` reads the area of the live shadow. */
    private fun reveal(buf: ByteArray, stride: Int, o: Instance, i: Int, s: Seg, v: Vals, area: Rect, shadow: ByteArray? = null) {
        val src = when (s.kind) {
            KIND_RECORD -> s.image!!
            KIND_SELF -> o.caps[i]!!
            KIND_NEW -> newSource(shadow!!, stride, area)
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
                else { val from = if (f.h > 0) f.h else s.source!!.h
                       eased(from, s.h1 - from, e).coerceIn(1, H_MAX) }
        // §7.5 (C3): W likewise, restarted from the width this lens last drew, clamped to 1..2048. READING (C3): a restart
        // from an unscaled width eases from the source's own width (as C2's H from the source's own height)
        val w = if (s.verb != DRAW || !s.isWScaled) 0
                else if (f == null) eased(s.w0, s.w1 - s.w0, e)
                else { val from = if (f.w > 0) f.w else s.source!!.w
                       eased(from, s.w1 - from, e).coerceIn(1, H_MAX) }
        return when (s.verb) {
            DRAW -> if (f == null) Vals(eased(x0, dx, e), eased(s.y, s.dy, e), eased(s.lvl0, s.lvl1 - s.lvl0, e).coerceIn(0, 15), 0, h, w)
                    else Vals(eased(f.x, x0 + dx - f.x, e), eased(f.y, s.y + s.dy - f.y, e), eased(f.lvl, s.lvl1 - f.lvl, e).coerceIn(0, 15), 0, h, w)
            REVEAL -> Vals(x0, s.y, 15, (if (f == null) eased(0, s.extentMax, e) else eased(f.e, s.extentMax - f.e, e)).coerceIn(0, s.extentMax))
            // §7.4–§7.6: PIECES' and GLYPHS' offsets and levels eased as DRAW's (never restarted: RETARGET refuses them)
            PIECES, SHRINK, GLYPHS -> Vals(eased(x0, dx, e), eased(s.y, s.dy, e), eased(s.lvl0, s.lvl1 - s.lvl0, e), e)
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
        o != null && (i == slot || meets(o, prog))
    }

    /** §7.3 (build C3): whether a fire's close set holds an owning instance (refused 19, nothing closed, before 17). */
    fun holdsOwner(set: List<Int>): Boolean = set.any { slots[it]?.owns == true }

    /** §6.2 step 11: one close of the fire's close pass — a local instance of the fire's own slot silently whatever
     *  its state (nothing waits on its body, §6.5), any other as [close] does (a drawing one's outcome-1 body). */
    fun closeForFire(i: Int, fireSlot: Int, uptime: Long) {
        val o = slots[i] ?: return
        if (i == fireSlot && o.local) { slots[i] = null; staleMarkOwed = true } else close(i, uptime)
    }

    /** §6.2 step 12, §6.4: the local instance — tag [tag] (the fire's S), t0 the fire's tick, d 0, the entry's period,
     *  its first read range from the walk, no capture range, nothing written into the shadow (END ordinal 0, CRC 0). */
    fun openLocal(slot: Int, tag: Int, period: Int, prog4: Int, prog: Prog, tick: Long, left: Boolean, cost: Long = 0L) {
        val inst = Instance(slot, tag, period, prog4, prog, null, arrayOfNulls(prog.tracks.size))
        inst.local = true
        inst.cost = cost
        inst.t0 = tick and U32
        inst.last = Array(prog.tracks.size) { startVals(inst, it, left) }
        slots[slot] = inst
    }

    /** §6.5 COMMIT, past mode 26's prefix and its values: PLAY's walk (5; `self` 5), areas (2), `none` backdrops (2),
     *  values (12, with §6.6's H rule) — then PLAY's close pass over the OTHER slots, END(prog, P) into the shadow
     *  always and its CRC, the slot's local instance tagged [s] adopted (a holding one turns done, no body), a local
     *  tagged newer left as it is, anything else closed; one outcome-3 body. Returns 0 or the reason. Build C3 (§7.2,
     *  §7.12): a bound program's rules at the walk (5) and values (12), then a version-2 program's own C against the
     *  cap (12); its close pass meets an owner by its owned rect and closes it as any other (§7.3). */
    fun commit(cache: ByteArray?, shadow: ByteArray, stride: Int, left: Boolean, slot: Int, s: Int, prog4: Int, p: IntArray, uptime: Long,
               armed: Boolean = false): Int {
        val v = validate(cache, prog4, p, Op.COMMIT, armed)
        val prog = v as? Prog ?: return -(v as Int)
        if (effects() && prog.version == 2 && cost(prog) > BUDGET_CAP) return REF_VALUE
        for (i in 0 until SLOTS) if (i != slot && slots[i]?.let { meets(it, prog) } == true) close(i, uptime)
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

    /** §7.3 (build C3): an open instance [o] meets a program being opened — an owning instance meets when its meeting
     *  box ([Prog.ownedBoth]) intersects any track pair's box of the other, two owners when their boxes intersect;
     *  otherwise §5.5's. READING (C3): an OWN program being opened (a PLAY or PLAY-AFTER) meets by its box as the owner
     *  it becomes (the other reading: by its tracks until it is open). */
    private fun meets(o: Instance, prog: Prog): Boolean = when {
        o.owns && prog.own -> o.prog.ownedBoth.overlaps(prog.ownedBoth)
        o.owns -> prog.tracks.any { it.bbox.overlaps(o.prog.ownedBoth) }
        prog.own -> o.prog.tracks.any { it.bbox.overlaps(prog.ownedBoth) }
        else -> meets(o.prog, prog)
    }

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

    /** A PlanSlot is what the thread reads of one slot without the word: its state, t0, P, T and d. [hold] (build C3,
     *  §7.7): a PLAY-AFTER with nothing due — its due time (t0 while t0 is ahead, now + P while it waits). */
    data class PlanSlot(val drawing: Boolean, val t0: Long, val period: Int, val T: Int, val d: Int, val hold: Long? = null)

    companion object {
        const val SLOTS = 4
        const val P_MIN = 16
        const val P_MAX = 128
        const val MAX_TRACKS = 8
        const val MAX_SEGS = 8
        const val T_MAX = 255
        const val SEG_BYTES = 32
        /** §7.2 (build C3): a version-2 segment's 40 B. */
        const val SEG2_BYTES = 40
        const val WAIT = 0; const val FILL = 1; const val DRAW = 2; const val REVEAL = 3
        /** §7.2: verbs 4 PIECES · 5 SHRINK · 6 GLYPHS; kinds 0 old (PIECES) · 4 `new`; GLYPHS 0 CELLS · 1 RAIN. */
        const val PIECES = 4; const val SHRINK = 5; const val GLYPHS = 6
        const val KIND_OLD = 0; const val KIND_NEW = 4
        const val GLYPH_CELLS = 0; const val GLYPH_RAIN = 1
        /** §7.2: backdrop 0x21 `new`; pref field 17 aux1, the last of a version-2 record. */
        const val BD_NEW = 0x21
        const val PREF_AUX1 = 17
        /** §7.7: mode 26 sub 8. */
        const val SUB_AFTER = 8
        /** §7.12: the cap of the step budget, 8,000,000 u (the largest period). */
        const val BUDGET_CAP = 8_000_000L
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
                // §7.7 (C3): a PLAY-AFTER not yet due gives its own due time
                if (s.hold != null) { wait = minOf(wait, ((s.hold - now) and U32).toInt().toLong()); continue }
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
            val ends = ArrayList<Int>(); val bytes = ArrayList<Int>()
            val (px, pos) = rle(cache, off + 4, w * h, ends, bytes) ?: return null
            return Img(w, h, px, ends.toIntArray(), bytes.toIntArray()) to pos
        }

        /** The RLE stream at [start] walked to exactly [pixels] (a record's or a glyph's: one token `count<<4 | level`,
         *  count 0 escaping to a byte, 0 again to a u16; a zero run or an overrun is malformed): the levels and the
         *  stream's end; null when it is malformed or leaves the cache. Each token's end pixel and bytes go to [ends] and
         *  [bytes] when given (§7.12's L). */
        fun rle(cache: ByteArray, start: Int, pixels: Int, ends: MutableList<Int>? = null, bytes: MutableList<Int>? = null): Pair<ByteArray, Int>? {
            val px = ByteArray(pixels)
            var n = 0
            var pos = start
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
                ends?.add(n); bytes?.add(used)
            }
            return px to pos
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
