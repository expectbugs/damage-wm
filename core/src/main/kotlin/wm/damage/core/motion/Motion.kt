package wm.damage.core.motion

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.wire.CfwModes

/**
 * Build C1's Motion DSL (spec §6.3): a motion program as `FIRMWARE.md` §5.4 lays it out, the parameters it takes, the
 * end state the firmware writes into the shadow (§5.6), and the three mode-26 messages (§5.5). Written from §5's text
 * and the lead's decisions on it (`C1-decisions.md`), never from the fork's C or the simulator's model, so that the
 * pin which compares this encoder with `firmware/make_vectors.py`'s is a comparison of two readings.
 *
 * Only what §5 and the C1 row ask: no scheduler, no input preemption, no Global `Motion` row (those are later pieces).
 * Nothing here sends: the transport sends a program only with ENGINE in force and a test program asked for by hand.
 *
 * The builder refuses, loudly ([LintError]), anything §5.5 would refuse that it can decide offline: counts, lengths,
 * areas, a `none` backdrop not inside an earlier area, levels and extents along every eased step, a table whose n or
 * last value is wrong, T past 255. What depends on the parameters is decided where they are known ([play], [end]).
 */
object Motion {
    /** A4's 30 Hz (§5.3): one step every 34 OS ticks. */
    const val PERIOD_30HZ = 34
    const val MAX_TRACKS = 8
    const val MAX_SEGMENTS = 8
    const val MAX_STEPS = 255
    const val BACKDROP_SELF = 0x20
    const val BACKDROP_NONE = 0xFF
    const val SEGMENT_BYTES = 32

    const val WAIT = 0; const val FILL = 1; const val DRAW = 2; const val REVEAL = 3
    const val KIND_RECORD = 0; const val KIND_SELF = 2; const val KIND_LEVEL = 3

    /** §5.4: `pref` bits 2–6, the field a parameter replaces. */
    enum class Field(val code: Int) {
        SRC(0), XL(1), XR(2), X(3), Y(4), DXL(5), DXR(6), DX(7), DY(8), LVL0(9), LVL1(10), H0(11), H1(12), STEPS(13)
    }

    /** One parameter use: P[param] (0..3) replaces [field]. */
    data class Pref(val param: Int, val target: Field) {
        init { if (param !in 0..3) throw LintError("a motion parameter is P0..P3, not P$param") }
        val byte: Int get() = 0x80 or (target.code shl 2) or param
    }

    /** §5.4's eases. A table is a record of its own at [off4]: `n u8 · 0 · E[1..n] s16`, E[n] = 4096. */
    sealed class Ease(val code: Int) {
        object Linear : Ease(0)
        object EaseOut : Ease(1)
        class Table(val off4: Int, val e: IntArray) : Ease(2) {
            init {
                if (off4 !in 0..0xFFFF) throw LintError("an easing table at off4 $off4 is outside the u16 field")
                if (e.size !in 1..MAX_STEPS) throw LintError("an easing table holds 1..255 values, not ${e.size}")
                if (e.any { it !in Short.MIN_VALUE..Short.MAX_VALUE }) throw LintError("an easing table value is not an s16")
                if (e.last() != 4096) throw LintError("an easing table must end at 4096 (E[n] = ${e.last()}) — the firmware refuses it (5)")
            }
            val record: ByteArray get() = ByteArray(2 + 2 * e.size).also { b ->
                b[0] = e.size.toByte()
                e.forEachIndexed { i, v -> b[2 + 2 * i] = (v and 0xFF).toByte(); b[3 + 2 * i] = ((v shr 8) and 0xFF).toByte() }
            }
        }
        companion object {
            val linear: Ease get() = Linear
            val easeOut: Ease get() = EaseOut
            fun table(off4: Int, e: IntArray): Ease = Table(off4, e)
        }
    }

    /** What DRAW and REVEAL draw: a v2 image record already in the cache, the area's own pixels, or a solid level. */
    sealed class Source {
        /** A v2 image record at [off4] of [w]×[h], [bytes] long in all (its header and RLE: the read range's end). */
        class Record(val off4: Int, val w: Int, val h: Int, val bytes: Int) : Source() {
            init {
                if (off4 !in 0..0xFFFF) throw LintError("a record at off4 $off4 is outside the u16 field")
                if (w !in 1..CfwModes.MAX_IMAGE2_W || h !in 1..CfwModes.MAX_IMAGE2_H || bytes < 5) throw LintError("a v2 record of ${w}x$h, $bytes B is not one")
            }
        }
        object Self : Source()
        class Level(val level: Int, val w: Int, val h: Int) : Source() {
            init { if (level !in 0..15 || w < 1 || h < 1) throw LintError("a level source $level of ${w}x$h is not one") }
        }
        companion object {
            /** A record from its bytes as the cache holds them: `[w u16][h u16][RLE]`. */
            fun record(off4: Int, rec: ByteArray): Record = Record(off4, (rec[0].toInt() and 0xFF) or ((rec[1].toInt() and 0xFF) shl 8),
                (rec[2].toInt() and 0xFF) or ((rec[3].toInt() and 0xFF) shl 8), rec.size)
            val self: Source get() = Self
            fun level(l: Int, w: Int, h: Int): Source = Level(l, w, h)
        }
    }

    sealed class Backdrop(val code: Int) {
        class Level(level: Int) : Backdrop(level) { init { if (level !in 0..15) throw LintError("a backdrop level is 0..15, not $level") } }
        object Self : Backdrop(BACKDROP_SELF)
        object None : Backdrop(BACKDROP_NONE)
    }

    /** REVEAL's edge (`opt` bits 0–1). */
    enum class Edge(val code: Int) { TOP(0), BOTTOM(1), LEFT(2), RIGHT(3) }

    /** One segment as the builder took it (before any parameter). */
    class Segment internal constructor(val verb: Int, val steps: Int, val ease: Ease, val kind: Int, val src: Int,
        val xL: Int, val xR: Int, val y: Int, val dxL: Int, val dxR: Int, val dy: Int, val lvl0: Int, val lvl1: Int,
        val opt: Int, val prefs: List<Pref>, val source: Source?) {
        fun bytes(): ByteArray {
            val b = ByteArray(SEGMENT_BYTES)
            b[0] = verb.toByte(); b[1] = steps.toByte(); b[2] = ease.code.toByte(); b[3] = kind.toByte()
            put16(b, 4, src); put16(b, 6, xL); put16(b, 8, xR); put16(b, 10, y); put16(b, 12, dxL); put16(b, 14, dxR); put16(b, 16, dy)
            b[18] = lvl0.toByte(); b[19] = lvl1.toByte(); b[20] = opt.toByte()
            put16(b, 22, (ease as? Ease.Table)?.off4 ?: 0)
            // 24..27 h0 h1 and 28 anchor stay 0 in C1 (§5.4); 21 and 29 are reserved
            prefs.getOrNull(0)?.let { b[30] = it.byte.toByte() }
            prefs.getOrNull(1)?.let { b[31] = it.byte.toByte() }
            return b
        }
    }

    class Track internal constructor(val areaL: Rect, val areaR: Rect, val perLens: Boolean, val backdrop: Backdrop, val segments: List<Segment>) {
        fun area(left: Boolean) = if (left) areaL else areaR
        val usesSelf: Boolean get() = backdrop is Backdrop.Self || segments.any { it.verb >= DRAW && it.kind == KIND_SELF }
    }

    class TrackBuilder internal constructor(private val perLens: Boolean, private val area: Rect) {
        internal val segments = ArrayList<Segment>()

        fun wait(n: Int) = add(Segment(WAIT, n, Ease.Linear, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(), null))

        /** FILL (§5.4): the area filled with [level] (its `kind`, which no parameter replaces). */
        fun fill(level: Int, n: Int) {
            if (level !in 0..15) throw LintError("FILL's level is 0..15, not $level — refused (12)")
            add(Segment(FILL, n, Ease.Linear, level, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(), null))
        }

        /** DRAW (§5.4): [src] a record or `self` at (x + off(dx), y + off(dy)), its level eased lvl0 → lvl1; [opt] bits 4
         *  (transparent) and 5 (inverse) only. [xR]/[dxR] only on a per-lens track (they default to the left lens's). */
        fun draw(src: Source, x: Int, y: Int, dx: Int = 0, dy: Int = 0, lvl0: Int = 15, lvl1: Int = 15, ease: Ease = Ease.Linear,
                 n: Int, opt: Int = 0, xR: Int? = null, dxR: Int? = null, prefs: List<Pref> = emptyList()) {
            if (src is Source.Level) throw LintError("DRAW draws a record or self, not a level")
            if (opt and 0x30.inv() != 0) throw LintError("DRAW's opt takes bits 4 and 5 only, not 0x${opt.toString(16)}")
            onePair(xR, dxR)
            add(Segment(DRAW, n, ease, if (src is Source.Self) KIND_SELF else KIND_RECORD, (src as? Source.Record)?.off4 ?: 0,
                x, if (perLens) xR ?: x else 0, y, dx, if (perLens) dxR ?: dx else 0, dy, lvl0, lvl1, opt, prefs, src))
        }

        /** REVEAL (§5.4): [src] wiped or slid in from [edge]. The source draws as it is (lvl0 = lvl1 = 15 sent, opt bits 4–5
         *  0: one picture under both readings of REVEAL's options, `C1-D2-built.md` R4). */
        fun reveal(src: Source, edge: Edge, slide: Boolean = false, ease: Ease = Ease.Linear, n: Int, x: Int, y: Int,
                   xR: Int? = null, prefs: List<Pref> = emptyList()) {
            onePair(xR, null)
            val (kind, s, w, h) = when (src) {
                is Source.Record -> listOf(KIND_RECORD, src.off4, 0, 0)
                is Source.Self -> listOf(KIND_SELF, 0, 0, 0)
                is Source.Level -> listOf(KIND_LEVEL, src.level, src.w, src.h)
            }
            add(Segment(REVEAL, n, ease, kind, s, x, if (perLens) xR ?: x else 0, y, w, 0, h, 15, 15,
                edge.code or (if (slide) 4 else 0), prefs, src))
        }

        private fun onePair(xR: Int?, dxR: Int?) {
            if (!perLens && (xR != null || dxR != null)) throw LintError("xR/dxR on a one-rect track (area $area) would be ignored — give a pair")
        }

        private fun add(s: Segment) {
            if (s.steps !in 1..MAX_STEPS && s.prefs.none { it.target == Field.STEPS }) throw LintError("a segment's steps are 1..255, not ${s.steps}")
            if (s.prefs.size > 2) throw LintError("a segment carries two parameter uses, not ${s.prefs.size}")
            if (s.prefs.any { it.target == Field.H0 || it.target == Field.H1 }) throw LintError("h0/h1 must be 0 in C1 (§5.4): no parameter may set them")
            for (v in listOf(s.xL, s.xR, s.y, s.dxL, s.dxR, s.dy)) if (v !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("a segment coordinate $v is not an s16")
            if (s.lvl0 !in 0..255 || s.lvl1 !in 0..255) throw LintError("a segment level is a u8")
            if (s.ease is Ease.Table && s.ease.e.size != s.steps) throw LintError("an easing table of ${s.ease.e.size} values for a segment of ${s.steps} steps — the firmware refuses it (5)")
            segments += s
        }
    }

    class ProgramBuilder internal constructor() {
        internal val tracks = ArrayList<Track>()
        /** A track over one [area] on both lenses. */
        fun track(area: Rect, backdrop: Backdrop, build: TrackBuilder.() -> Unit) = add(area, area, false, backdrop, build)
        /** A per-lens track: [left] on the left lens, [right] on the right (one size, one row range). */
        fun track(left: Rect, right: Rect, backdrop: Backdrop, build: TrackBuilder.() -> Unit) = add(left, right, true, backdrop, build)
        private fun add(l: Rect, r: Rect, perLens: Boolean, backdrop: Backdrop, build: TrackBuilder.() -> Unit) {
            val b = TrackBuilder(perLens, l).apply(build)
            if (b.segments.size !in 1..MAX_SEGMENTS) throw LintError("a track holds 1..8 segments, not ${b.segments.size}")
            tracks += Track(l, r, perLens, backdrop, b.segments.toList())
        }
    }

    /** A program placed at [at] (off4) in the cache. [records]: the image records a `src` parameter may name — the
     *  builder checks a substituted record only against these, and refuses one it was not given. */
    fun program(at: Int, records: List<Source.Record> = emptyList(), build: ProgramBuilder.() -> Unit): MotionProgram {
        if (at !in 0..0xFFFF) throw LintError("a program at off4 $at is outside the u16 field")
        val tracks = ProgramBuilder().apply(build).tracks.toList()
        if (tracks.size !in 1..MAX_TRACKS) throw LintError("a program holds 1..8 tracks, not ${tracks.size}")
        for ((i, t) in tracks.withIndex()) {
            for (r in listOf(t.areaL, t.areaR)) if (r.w <= 0 || r.h <= 0 || r.x < 0 || r.y < 0 || r.right > Geometry.PANEL_W || r.bottom > Geometry.PANEL_H)
                throw LintError("track $i's area $r is empty or leaves the panel — refused (2)")
            if (t.perLens && (t.areaL.w != t.areaR.w || t.areaL.h != t.areaR.h || t.areaL.y != t.areaR.y))
                throw LintError("track $i's pair ${t.areaL} / ${t.areaR} is not one size and one row range — refused (2)")
            // the stricter of the two readings (`C1-D2-built.md` R7): ONE earlier track whose two rects hold both of
            // this track's, so a program this accepts is accepted under either
            if (t.backdrop is Backdrop.None && tracks.take(i).none { o -> o.backdrop !is Backdrop.None && o.areaL.contains(t.areaL) && o.areaR.contains(t.areaR) })
                throw LintError("track $i's `none` backdrop is not inside an earlier track's area whose backdrop is not none — refused (2)")
        }
        val body = ArrayList<Byte>()
        for (t in tracks) {
            body += listOf(t.segments.size, t.backdrop.code, if (t.perLens) 1 else 0, 0).map { it.toByte() }
            for (r in if (t.perLens) listOf(t.areaL, t.areaR) else listOf(t.areaL)) body += rectBytes(r).toList()
            for (s in t.segments) body += s.bytes().toList()
        }
        val length = 4 + body.size
        if (length > 0xFFFF) throw LintError("a program of $length B does not fit its u16 length")
        val bytes = byteArrayOf(1, tracks.size.toByte(), (length and 0xFF).toByte(), (length shr 8).toByte()) + body.toByteArray()
        val tables = tracks.flatMap { t -> t.segments.mapNotNull { it.ease as? Ease.Table } }.distinctBy { it.off4 }
        val prog = MotionProgram(at, bytes, tracks, tables.map { it.off4 to it.record }, records.associateBy { it.off4 })
        prog.resolve(IntArray(4), staticOnly = true)                          // every check a parameter cannot change
        return prog
    }

    /** Mode 26 PLAY of [program] (§5.5) with [p]: the parameters' checks run first (a refusal the phone can foresee
     *  is never sent); [cap4] must hold [MotionProgram.captureBytes] when a track uses `self`. */
    fun play(program: MotionProgram, slot: Int, period: Int, tag: Int, cap4: Int, p: IntArray): ByteArray {
        program.resolve(p, staticOnly = false)
        if (program.captureBytes > 0) {
            val cap = cap4 * 4 until cap4 * 4 + program.captureBytes
            val first = program.firstRange(p)
            if (cap.first < first.last + 1 && first.first < cap.last + 1)
                throw LintError("the capture range [${cap.first}, ${cap.last + 1}) meets the program's first read range — refused (5)")
        }
        return CfwModes.motionPlay(slot, period, program.at, tag, cap4, p)
    }

    fun retarget(program: MotionProgram, slot: Int, p: IntArray): ByteArray {
        if (!program.tracks.all { t -> t.segments.size == 1 || (t.segments.size == 2 && t.segments[0].verb == WAIT && t.segments[1].verb != WAIT) })
            throw LintError("RETARGET needs at most one non-WAIT segment after an optional leading WAIT on every track — refused (12)")
        program.resolve(p, staticOnly = false)
        return CfwModes.motionRetarget(slot, p)
    }

    fun close(slot: Int): ByteArray = CfwModes.motionClose(slot)

    /** One op of END (§5.4's closing paragraph): a §4 sub-message the batch carries as it is, or a track's capture. */
    sealed class EndOp {
        /** Mode 20 (the track's area), mode 21 or mode 17, single or per-lens, ready for a mode-8 batch. */
        class Sub(val bytes: ByteArray) : EndOp()
        /** Track [track]'s capture — its area on each lens as the truth held it when the PLAY ran ([areaL], [areaR]) —
         *  drawn at (xL | xR, y) with [options] (null: the levels as they are), under the clip before it. */
        data class Capture(val track: Int, val areaL: Rect, val areaR: Rect, val xL: Int, val xR: Int, val y: Int, val options: Int?) : EndOp()
    }

    /** END(program, P) as §4 ops, in track order: per track its area's clip, its backdrop, then its final state —
     *  so the player applies to the compositor's truth exactly what the firmware writes into the shadow. */
    fun end(program: MotionProgram, p: IntArray): List<EndOp> {
        val segs = program.resolve(p, staticOnly = false)
        val out = ArrayList<EndOp>()
        program.tracks.forEachIndexed { i, t ->
            val (aL, aR) = t.areaL to t.areaR
            out += EndOp.Sub(if (aL == aR) CfwModes.clip(aL) else CfwModes.clipPair(aL, aR))
            when (val b = t.backdrop) {
                is Backdrop.Level -> out += EndOp.Sub(fillOf(aL, aR, b.code))
                is Backdrop.Self -> out += EndOp.Capture(i, aL, aR, aL.x, aR.x, aL.y, null)
                is Backdrop.None -> {}
            }
            // at step T every track has run all its segments: it holds its last non-WAIT segment's final state
            val s = segs[i].lastOrNull { it.verb != WAIT } ?: return@forEachIndexed
            val xL = s.xL + (if (s.verb == DRAW) s.dxL else 0)
            val xR = (if (t.perLens) s.xR else s.xL) + (if (s.verb == DRAW) (if (t.perLens) s.dxR else s.dxL) else 0)
            val y = s.y + (if (s.verb == DRAW) s.dy else 0)
            when {
                s.verb == FILL -> out += EndOp.Sub(fillOf(aL, aR, s.kind))
                s.kind == KIND_SELF -> out += EndOp.Capture(i, aL, aR, xL, xR, y, if (s.verb == DRAW) (s.opt and 0x30) or s.lvl1 else null)
                s.kind == KIND_RECORD -> {
                    val r = s.record ?: throw LintError("track $i's record at off4 ${s.src} was not named to the builder")
                    // a draw that cannot reach its area paints nothing: no message
                    if (Rect(xL, y, r.w, r.h).intersect(aL) == null && Rect(xR, y, r.w, r.h).intersect(aR) == null) return@forEachIndexed
                    val opt = if (s.verb == DRAW) (s.opt and 0x30) or s.lvl1 else 15
                    out += EndOp.Sub(if (xL == xR) CfwModes.drawImage2(s.src, xL, y, opt, r.w, r.h)
                        else CfwModes.drawImage2Pair(s.src, xL, xR, y, opt, r.w, r.h))
                }
                else -> {                                                       // REVEAL of a level, at rest
                    val l = Rect(xL, y, s.dxL, s.dy).intersect(aL)
                    val r = Rect(xR, y, s.dxL, s.dy).intersect(aR)
                    if (l == null && r == null) return@forEachIndexed
                    if (l == null || r == null || l.w != r.w || l.h != r.h || l.y != r.y)
                        throw LintError("track $i's level source meets its two areas in different rects — no one §4 fill draws it")
                    out += EndOp.Sub(fillOf(l, r, s.src))
                }
            }
        }
        return out
    }

    private fun fillOf(l: Rect, r: Rect, level: Int) = if (l == r) CfwModes.fill(l, level) else CfwModes.fillPair(l, r, level)

    private fun rectBytes(r: Rect) = ByteArray(8).also { put16(it, 0, r.x); put16(it, 2, r.y); put16(it, 4, r.w); put16(it, 6, r.h) }

    internal fun put16(b: ByteArray, at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }

    /** §5.4's E[j]: E[0] = 0; linear ⌊4096·j/n⌋; ease-out 4096 − ⌊4096·(n−j)²/n²⌋; a table's E[1..n]. */
    fun easeE(ease: Ease, n: Int): IntArray = IntArray(n + 1) { j ->
        when {
            j == 0 -> 0
            ease is Ease.Table -> ease.e[j - 1]
            ease is Ease.EaseOut -> 4096 - ((4096L * (n - j) * (n - j)) / (n.toLong() * n)).toInt()
            else -> ((4096L * j) / n).toInt()
        }
    }

    /** §5.4: `v(j) = v0 + ((D·E[j] + 2048) >> 12)`, a signed 32-bit product, `>>` arithmetic. */
    fun eased(v0: Int, d: Int, e: Int): Int = v0 + ((d * e + 2048) shr 12)
}

/**
 * A program record (§5.4) placed at [at] (off4), with the easing tables it names. What the phone writes for it is
 * [records]: the program and its tables (the image records it draws are the caller's, already in the cache).
 */
class MotionProgram internal constructor(val at: Int, val bytes: ByteArray, val tracks: List<Motion.Track>, val tables: List<Pair<Int, ByteArray>>,
    private val known: Map<Int, Motion.Source.Record>) {
    /** The records to write, (off4, bytes): the program, then its tables. */
    val records: List<Pair<Int, ByteArray>> get() = listOf(at to bytes) + tables

    /** Decision A.5's capture plan: per track the capture's size (0: the track takes no capture). */
    val captureSizes: IntArray = IntArray(tracks.size) { i -> if (tracks[i].usesSelf) ((tracks[i].areaL.w + 1) / 2) * tracks[i].areaL.h else 0 }

    /** The capture range's length from `cap·4`: the `self` tracks' captures in track order, each 4-byte aligned. */
    val captureBytes: Int = run {
        var off = 0
        var first = true
        for (n in captureSizes) { if (n == 0) continue; if (!first) off = (off + 3) and 3.inv(); first = false; off += n }
        off
    }

    /** T from the segments' own steps (a `steps` parameter changes it: [stepsWith]). */
    val T: Int get() = tracks.maxOf { t -> t.segments.sumOf { it.steps } }

    fun stepsWith(p: IntArray): Int = resolve(p, staticOnly = false).maxOf { segs -> segs.sumOf { it.steps } }

    /** The first read range (§5.6) under [p], as a byte range: the program, every record its segments name, every table. */
    fun firstRange(p: IntArray): IntRange {
        var lo = at * 4; var hi = at * 4 + bytes.size
        for (segs in resolve(p, staticOnly = false)) for (s in segs) {
            s.record?.let { r -> lo = minOf(lo, s.src * 4); hi = maxOf(hi, s.src * 4 + r.bytes) }
            (s.ease as? Motion.Ease.Table)?.let { tb -> lo = minOf(lo, tb.off4 * 4); hi = maxOf(hi, tb.off4 * 4 + 2 + 2 * tb.e.size) }
        }
        return lo until hi
    }

    /** One segment after substitution. */
    internal class Resolved(val verb: Int, var steps: Int, val ease: Motion.Ease, val kind: Int, var src: Int, var xL: Int, var xR: Int,
        var y: Int, var dxL: Int, var dxR: Int, var dy: Int, var lvl0: Int, var lvl1: Int, val opt: Int, var record: Motion.Source.Record?)

    /** Every segment with [p] substituted, checked as §5.5's values position does (12) and the record walk (5). With
     *  [staticOnly] only what no parameter can change is checked (a field a parameter replaces is skipped). */
    internal fun resolve(p: IntArray, staticOnly: Boolean): List<List<Resolved>> {
        if (p.size != 4) throw LintError("a PLAY carries four parameters, not ${p.size}")
        val out = tracks.mapIndexed { ti, t -> t.segments.map { s ->
            val r = Resolved(s.verb, s.steps, s.ease, s.kind, s.src, s.xL, s.xR, s.y, s.dxL, s.dxR, s.dy, s.lvl0, s.lvl1, s.opt,
                s.source as? Motion.Source.Record)
            val touched = HashSet<Motion.Field>()
            for (pr in s.prefs) {
                touched += pr.target
                if (staticOnly) continue
                val v = p[pr.param]
                when (pr.target) {
                    Motion.Field.SRC -> { r.src = v and 0xFFFF; if (r.kind == Motion.KIND_RECORD && r.verb >= Motion.DRAW) r.record = known[r.src] }
                    Motion.Field.XL -> r.xL = v; Motion.Field.XR -> r.xR = v; Motion.Field.X -> { r.xL = v; r.xR = v }
                    Motion.Field.Y -> r.y = v
                    Motion.Field.DXL -> r.dxL = v; Motion.Field.DXR -> r.dxR = v; Motion.Field.DX -> { r.dxL = v; r.dxR = v }
                    Motion.Field.DY -> r.dy = v
                    Motion.Field.LVL0 -> r.lvl0 = v and 0xFF; Motion.Field.LVL1 -> r.lvl1 = v and 0xFF
                    Motion.Field.STEPS -> r.steps = v and 0xFF
                    Motion.Field.H0, Motion.Field.H1 -> {}
                }
            }
            check(ti, r, touched, staticOnly)
            r
        } }
        if (!staticOnly || tracks.none { t -> t.segments.any { s -> s.prefs.any { it.target == Motion.Field.STEPS } } }) {
            val t = out.maxOf { segs -> segs.sumOf { it.steps } }
            if (t > Motion.MAX_STEPS) throw LintError("T = $t steps passes 255 — refused (12)")
        }
        return out
    }

    private fun check(ti: Int, r: Resolved, touched: Set<Motion.Field>, staticOnly: Boolean) {
        fun skip(vararg f: Motion.Field) = staticOnly && f.any { it in touched }
        if (!skip(Motion.Field.STEPS) && r.steps !in 1..Motion.MAX_STEPS) throw LintError("track $ti: a segment of ${r.steps} steps — refused (12)")
        if (r.verb == Motion.FILL && r.kind !in 0..15) throw LintError("track $ti: FILL level ${r.kind} — refused (12)")
        if (r.verb >= Motion.DRAW && r.kind == Motion.KIND_RECORD && r.record == null && !skip(Motion.Field.SRC))
            throw LintError("track $ti: a parameter names the record at off4 ${r.src}, which the builder was not given — it cannot check it")
        if (staticOnly && Motion.Field.STEPS in touched) return                // the eases below need the steps
        val e = Motion.easeE(r.ease, r.steps)
        if (r.ease is Motion.Ease.Table && r.ease.e.size != r.steps) throw LintError("track $ti: a table of ${r.ease.e.size} for ${r.steps} steps — refused (5)")
        if (r.verb == Motion.DRAW && !skip(Motion.Field.LVL0, Motion.Field.LVL1) && e.any { Motion.eased(r.lvl0, r.lvl1 - r.lvl0, it) !in 0..15 })
            throw LintError("track $ti: DRAW's level leaves 0..15 along its ease (lvl ${r.lvl0} → ${r.lvl1}) — refused (12)")
        if (r.verb == Motion.REVEAL) {
            if (r.kind == Motion.KIND_LEVEL && (r.src > 15 || r.dxL < 1 || r.dy < 1)) throw LintError("track $ti: a level source ${r.src} of ${r.dxL}x${r.dy} — refused (12)")
            val f = when (r.kind) {
                Motion.KIND_RECORD -> r.record?.let { if ((r.opt and 3) <= 1) it.h else it.w }
                Motion.KIND_SELF -> tracks[ti].areaL.let { if ((r.opt and 3) <= 1) it.h else it.w }
                else -> if ((r.opt and 3) <= 1) r.dy else r.dxL
            }
            if (f != null && e.any { Motion.eased(0, f, it) !in 0..f }) throw LintError("track $ti: REVEAL's extent leaves 0..$f along its ease — refused (12)")
        }
    }
}
