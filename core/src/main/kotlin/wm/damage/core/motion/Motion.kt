package wm.damage.core.motion

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.sim.EffectsSim
import wm.damage.core.sim.MotionSim
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg

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
 *
 * Build C3 (spec DP1, `FIRMWARE.md` §7.2–§7.6, §7.12; from §7's text): **version-2 records** ([program2]) — the 8-B
 * header with OWN and LAND, the 40-B segment, PIECES, SHRINK and GLYPHS, the source and backdrop `new`, pref 14–17,
 * DRAW's two axes and its source window, and the GLYPHS tables ([Font], [Alphabet], [CellList], [CellTable], [Rain]).
 * A version-2 record is built only through [effects], the one gate (EFFECTS in force: ladder stage 5 on a DamageCaps
 * bit-11 build). Everything §7.2 refuses that the phone can decide is refused here, with the section in the message,
 * the step budget (§7.12) included ([MotionProgram.cost], [stepSum]); [endOnModel] is the picture a program leaves at
 * step T, drawn by the simulator's renderers (in-place tracks, `new` and GLYPHS have no §4 op to say it in).
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

    // ---- build C3, `FIRMWARE.md` §7.2
    /** §7.2: a version-2 record's header and segment. */
    const val HEADER2_BYTES = 8
    const val SEGMENT2_BYTES = 40
    /** §7.2 verbs 4–6. */
    const val PIECES = 4; const val SHRINK = 5; const val GLYPHS = 6
    /** §7.2: kind 4 `new` (DRAW, REVEAL, PIECES layer new); PIECES kind 0 is layer old (in place). */
    const val KIND_NEW = 4
    const val LAYER_OLD = 0; const val LAYER_NEW = KIND_NEW
    /** §7.2 GLYPHS kinds. */
    const val CELLS = 0; const val RAIN = 1
    /** §7.2 backdrop `new`: this lens's track area of the live shadow. */
    const val BACKDROP_NEW = 0x21
    /** §7.2 header flags. */
    const val FLAG_OWN = 0x01; const val FLAG_LAND = 0x02
    /** §7.4 PIECES models (aux1 & 3). */
    const val UNIFORM = 0; const val RADIAL = 1; const val RANDOM = 2
    /** §7.6: a cell's fill byte for "none". */
    const val FILL_NONE = 0xFF
    /** §7.12: the step budget's cap, in u (the largest period, 128 ticks). */
    const val BUDGET_CAP = 8_000_000L
    /** C3 decisions AF: the most glyph bytes a GLYPHS program's walk may make the glasses read (≈ 0.25 s on the ui_task at
     *  the measured walk rate of 0.25 µs a byte; the firmware itself bounds no walk, and a cell list naming tens of fonts of
     *  the largest glyphs would walk for seconds). Refused offline, with the bytes counted ([MotionProgram.walkBytes]). */
    const val WALK_BYTES_MAX = 1_000_000L
    /** §7.5: the two-axis and vertical scale's range. */
    private val SCALE = 1..2048

    /** §5.4: `pref` bits 2–6, the field a parameter replaces; §7.2 adds 14–17 on a version-2 record. */
    enum class Field(val code: Int) {
        SRC(0), XL(1), XR(2), X(3), Y(4), DXL(5), DXR(6), DX(7), DY(8), LVL0(9), LVL1(10), H0(11), H1(12), STEPS(13),
        W0(14), W1(15), AUX0(16), AUX1(17)
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
            /** §7.2: E[0..n] never falls (E[0] = 0) — what an in-place track's ease must be. */
            val nonDecreasing: Boolean get() = e.indices.all { i -> e[i] >= (if (i == 0) 0 else e[i - 1]) }
        }
        companion object {
            val linear: Ease get() = Linear
            val easeOut: Ease get() = EaseOut
            fun table(off4: Int, e: IntArray): Ease = Table(off4, e)
        }
    }

    /** What DRAW and REVEAL draw: a v2 image record already in the cache, the area's own pixels, a solid level, or
     *  (version 2, OWN programs) this lens's track area of the live shadow. */
    sealed class Source {
        /** A v2 image record at [off4] of [w]×[h], [bytes] long in all (its header and RLE: the read range's end).
         *  [data], when given, is the record as the cache holds it: §7.12's L of a source window is read from its
         *  tokens (without it a window counts the whole record's tokens, an upper bound). */
        class Record(val off4: Int, val w: Int, val h: Int, val bytes: Int, val data: ByteArray? = null) : Source() {
            init {
                if (off4 !in 0..0xFFFF) throw LintError("a record at off4 $off4 is outside the u16 field")
                if (w !in 1..CfwModes.MAX_IMAGE2_W || h !in 1..CfwModes.MAX_IMAGE2_H || bytes < 5) throw LintError("a v2 record of ${w}x$h, $bytes B is not one")
            }
            private val tokens: List<IntArray>? by lazy { data?.let { rleTokens(it, 4, w * h) } }
            /** §7.12's L: the bytes of the tokens that hold a pixel of rows [r0, r1). */
            fun tokenBytes(r0: Int = 0, r1: Int = h): Long {
                val t = tokens ?: return (bytes - 4).toLong()
                var sum = 0L; var start = 0
                for ((end, n) in t) { if (start < r1 * w && end > r0 * w) sum += n; start = end }
                return sum
            }
        }
        object Self : Source()
        /** Build C3 (§7.3): the source `new`. */
        object New : Source()
        class Level(val level: Int, val w: Int, val h: Int) : Source() {
            init { if (level !in 0..15 || w < 1 || h < 1) throw LintError("a level source $level of ${w}x$h is not one") }
        }
        companion object {
            /** A record from its bytes as the cache holds them: `[w u16][h u16][RLE]`. */
            fun record(off4: Int, rec: ByteArray): Record = Record(off4, (rec[0].toInt() and 0xFF) or ((rec[1].toInt() and 0xFF) shl 8),
                (rec[2].toInt() and 0xFF) or ((rec[3].toInt() and 0xFF) shl 8), rec.size, rec)
            val self: Source get() = Self
            val new: Source get() = New
            fun level(l: Int, w: Int, h: Int): Source = Level(l, w, h)
        }
    }

    sealed class Backdrop(val code: Int) {
        class Level(level: Int) : Backdrop(level) { init { if (level !in 0..15) throw LintError("a backdrop level is 0..15, not $level") } }
        object Self : Backdrop(BACKDROP_SELF)
        /** Build C3 (§7.2): 0x21 `new`, this lens's track area of the live shadow (OWN programs only). */
        object New : Backdrop(BACKDROP_NEW)
        object None : Backdrop(BACKDROP_NONE)
    }

    /** REVEAL's edge (`opt` bits 0–1). */
    enum class Edge(val code: Int) { TOP(0), BOTTOM(1), LEFT(2), RIGHT(3) }

    // ------------------------------------------------------------------------------- build C3: the GLYPHS tables
    /** §7.6: a v1 glyph record `[w u8][h u8][RLE]` already in the cache (the atlas packs them): its size and its RLE
     *  length (the token bytes after `[w][h]`, §7.12). */
    class Glyph(val off4: Int, val w: Int, val h: Int, val rle: Int) {
        init {
            if (off4 !in 0..0xFFFF) throw LintError("a glyph record at off4 $off4 is outside the u16 field")
            if (w !in 1..255 || h !in 1..255 || rle < 1) throw LintError("a glyph record of ${w}x$h with $rle B of RLE is not one (§7.6: walked at the PLAY, 5)")
        }
        companion object {
            /** A glyph record from its bytes; malformed (a stream that does not end at w·h pixels): refused (5). */
            fun of(off4: Int, rec: ByteArray): Glyph {
                if (rec.size < 3) throw LintError("a glyph record of ${rec.size} B is not one (§7.6, 5)")
                val w = rec[0].toInt() and 0xFF; val h = rec[1].toInt() and 0xFF
                val t = rleTokens(rec, 2, w * h) ?: throw LintError("the glyph record at off4 $off4 does not decode to its ${w}x$h pixels (§7.6: walked at the PLAY, 5)")
                return Glyph(off4, w, h, t.sumOf { it[1] })
            }
        }
    }

    /** §7.6 and §4: a font — a 224-entry v2 table at [off4]; code c (32..255) names entry c − 32, an off4 to a glyph
     *  record. [entries] are the 224 off4s as written; [glyphs] the records they name that the builder may check — a
     *  code whose glyph is not among them cannot be named (the walk reads it, and the builder could not judge it). */
    class Font(val off4: Int, val entries: IntArray, glyphs: List<Glyph>) {
        init {
            if (off4 !in 0..0xFFFF) throw LintError("a font table at off4 $off4 is outside the u16 field")
            if (entries.size != 224 || entries.any { it !in 0..0xFFFF }) throw LintError("a font table holds 224 u16 off4s (§7.6, §4), not ${entries.size}")
        }
        private val byOff = glyphs.associateBy { it.off4 }
        /** The glyph [code] names (32..255; codes 0 and 32 name none, §7.6 — null). */
        fun glyph(code: Int): Glyph? {
            if (code == 0 || code == 32) return null
            if (code !in 33..255) throw LintError("code $code names no font entry (32..255, §7.6) — refused (5)")
            return byOff[entries[code - 32]] ?: throw LintError("code $code names the glyph at off4 ${entries[code - 32]} of the font at $off4, " +
                "which the builder was not given — it cannot check the walk (§7.6, 5)")
        }
        val bytes: ByteArray get() = ByteArray(448).also { b -> entries.forEachIndexed { i, o -> put16(b, 2 * i, o) } }
        /** The bytes the walk reads for [codes]: the font table and each code's glyph record (codes 0 and 32 add nothing,
         *  §7.6). "Every font named" is read as the whole 448-B table — the larger of its two readings (the entries
         *  read, or the table), so a range the phone keeps clear holds under either. */
        internal fun reads(codes: Iterable<Int>, add: (Int, Int) -> Unit) {
            for (c in codes) { val g = glyph(c) ?: continue; add(off4 * 4, off4 * 4 + 448); add(g.off4 * 4, g.off4 * 4 + 2 + g.rle) }
        }
    }

    /** §7.6: an alphabet — `n u8 (1..224) · codes` (each 32..255), padded to 4 B. */
    class Alphabet(val off4: Int, val codes: IntArray) {
        init {
            if (off4 !in 0..0xFFFF) throw LintError("an alphabet at off4 $off4 is outside the u16 field")
            if (codes.size !in 1..224) throw LintError("an alphabet holds 1..224 codes, not ${codes.size} (§7.6, 5)")
            if (codes.any { it !in 32..255 }) throw LintError("an alphabet's codes are 32..255 (§7.6, 5): ${codes.toList()}")
        }
        val bytes: ByteArray get() = pad4(byteArrayOf(codes.size.toByte()) + ByteArray(codes.size) { codes[it].toByte() })
        /** The codes that name a glyph (32 draws nothing, §7.6). */
        val drawn: List<Int> get() = codes.filter { it != 32 }
    }

    /** §7.6: one cell — `bx s16 · by s16 · bw u8 · bh u8 · font off4 u16 · old u8 · new u8 · fill u8 · opt u8`; old
     *  and new 0 or 32..255, fill 0..15 or [FILL_NONE], opt the glyph's level (bits 0–3) and inverse (bit 5). */
    class Cell(val bx: Int, val by: Int, val bw: Int, val bh: Int, val font: Font, val old: Int, val new: Int,
               val fill: Int = FILL_NONE, val level: Int = 15, val inverse: Boolean = false) {
        init {
            if (bx !in Short.MIN_VALUE..Short.MAX_VALUE || by !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("a cell's bx $bx / by $by are not s16")
            if (bw !in 0..255 || bh !in 0..255) throw LintError("a cell's bw $bw / bh $bh are not u8")
            for (c in intArrayOf(old, new)) if (c != 0 && c !in 32..255) throw LintError("a cell's code $c is not 0 or 32..255 (§7.6, 5)")
            if (fill != FILL_NONE && fill !in 0..15) throw LintError("a cell's fill $fill is not 0..15 or none (§7.6, 5)")
            if (level !in 0..15) throw LintError("a cell's level $level is not 0..15")
            font.glyph(old); font.glyph(new)
        }
        val bytes: ByteArray get() = ByteArray(12).also { b ->
            put16(b, 0, bx); put16(b, 2, by); b[4] = bw.toByte(); b[5] = bh.toByte(); put16(b, 6, font.off4)
            b[8] = old.toByte(); b[9] = new.toByte(); b[10] = fill.toByte(); b[11] = (level or (if (inverse) 0x20 else 0)).toByte()
        }
    }

    /** §7.6: a cell list — `count u16 (1..512) · alphabet off4 u16 · noise u8 · 0 · 0 · 0`, then its cells. Every code
     *  the alphabet holds must name a glyph in every font its cells use (the walk reads each). */
    class CellList(val off4: Int, val alphabet: Alphabet, val noise: Int, val cells: List<Cell>) {
        init {
            if (off4 !in 0..0xFFFF) throw LintError("a cell list at off4 $off4 is outside the u16 field")
            if (cells.size !in 1..512) throw LintError("a cell list holds 1..512 cells, not ${cells.size} (§7.6, 5)")
            if (noise !in 0..255) throw LintError("a cell list's noise $noise is not a u8")
            for (f in cells.map { it.font }.distinctBy { it.off4 }) for (c in alphabet.drawn) f.glyph(c)
        }
        val bytes: ByteArray get() {
            val b = ByteArray(8 + 12 * cells.size)
            put16(b, 0, cells.size); put16(b, 2, alphabet.off4); b[4] = noise.toByte()
            cells.forEachIndexed { i, c -> c.bytes.copyInto(b, 8 + 12 * i) }
            return b
        }
    }

    /** §7.6: a cell table — `n u16 (1..1024) · 0 u16 · off4[n] u16`, padded to 4 B; a CELLS segment's aux0 picks a list. */
    class CellTable(val off4: Int, val lists: List<CellList>) {
        init {
            if (off4 !in 0..0xFFFF) throw LintError("a cell table at off4 $off4 is outside the u16 field")
            if (lists.size !in 1..1024) throw LintError("a cell table names 1..1024 lists, not ${lists.size} (§7.6, 5)")
        }
        val bytes: ByteArray get() = pad4(ByteArray(4 + 2 * lists.size).also { b -> put16(b, 0, lists.size); lists.forEachIndexed { i, l -> put16(b, 4 + 2 * i, l.off4) } })
    }

    /** §7.6: a rain record (16 B) — `cols u8 (1..128) · trail u8 (1..32) · cw u8 · ch u8 (1..255) · font off4 u16 ·
     *  alphabet off4 u16 · headLvl u8 · tailLvl u8 · reveal u8 (0..2) · fillLvl u8 · spread u8 · flicker u8 (1..255) ·
     *  0 u16`. With reveal 1 or 2 its track is in place (§7.2: OWN programs only). */
    class Rain(val off4: Int, val cols: Int, val trail: Int, val cw: Int, val ch: Int, val font: Font, val alphabet: Alphabet,
               val headLvl: Int = 15, val tailLvl: Int = 3, val reveal: Int = 0, val fillLvl: Int = 0, val spread: Int = 0, val flicker: Int = 1) {
        init {
            if (off4 !in 0..0xFFFF) throw LintError("a rain record at off4 $off4 is outside the u16 field")
            if (cols !in 1..128 || trail !in 1..32 || cw !in 1..255 || ch !in 1..255) throw LintError("a rain record's cols $cols, trail $trail, cw $cw, ch $ch are out of range (§7.6, 5)")
            if (headLvl !in 0..15 || tailLvl !in 0..15 || fillLvl !in 0..15) throw LintError("a rain record's levels are 0..15 (§7.6, 5)")
            if (reveal !in 0..2 || spread !in 0..255 || flicker !in 1..255) throw LintError("a rain record's reveal $reveal (0..2), spread $spread or flicker $flicker (1..255) is out of range (§7.6, 5)")
            for (c in alphabet.drawn) font.glyph(c)
        }
        val bytes: ByteArray get() = byteArrayOf(cols.toByte(), trail.toByte(), cw.toByte(), ch.toByte(), 0, 0, 0, 0,
            headLvl.toByte(), tailLvl.toByte(), reveal.toByte(), fillLvl.toByte(), spread.toByte(), flicker.toByte(), 0, 0)
            .also { put16(it, 4, font.off4); put16(it, 6, alphabet.off4) }
    }

    // --------------------------------------------------------------------------------- build C3: the one gate
    /** Proof that EFFECTS is in force this session (`FIRMWARE.md` §7.11 "version 2 only at stage 5"): the only way to
     *  build a version-2 record ([program2]). Got from [effects] alone. It proves one moment, the build it was asked
     *  for (decisions W, D-6): a builder never keeps one — a session restart, a hold-back or a hand disarm leaves a kept
     *  token building records the glasses refuse (5). */
    class Effects internal constructor()

    /** The one gate every builder of a version-2 record calls (C3-DP1's reading 4, ruled: the transport cannot tell a
     *  record inside a cache write, so nothing builds one below stage 5): DamageCaps bit 11 reported and EFFECTS (flag
     *  bit 6) in force — the transport's state, as its own effects gate reads it. Otherwise a [LintError]: the glasses
     *  refuse a version-2 record while EFFECTS is clear (§7.2, 5). **The rule (decisions W, D-6):** a builder calls it at
     *  each build, from the transport's state as it then is (`caps`, `flagsInForce`), and never keeps the token. */
    fun effects(caps: DamageMsg.Caps?, flagsInForce: Int): Effects {
        if (caps == null || caps.contract < DamageMsg.CONTRACT || !caps.has(DamageMsg.FEATURE_EFFECTS))
            throw LintError("§7.11: version 2 only at stage 5 — this build reports no effects (DamageCaps bit 11; caps $caps)")
        if (flagsInForce and DamageMsg.FLAG_EFFECTS == 0)
            throw LintError("§7.2: a version-2 record is refused (5) while EFFECTS is clear (flags 0x${flagsInForce.toString(16)}) — version 2 only at stage 5 (§7.11)")
        return Effects()
    }

    /** One segment as the builder took it (before any parameter). [h0], [h1] and [anchor] (build C2, §6.6) are DRAW's
     *  only, and 0 unless the program is built for a bit-10 build. Build C3: [w0], [w1], [aux0], [aux1] (a version-2
     *  segment's last 8 B) and what a GLYPHS segment names. */
    class Segment internal constructor(val verb: Int, val steps: Int, val ease: Ease, val kind: Int, val src: Int,
        val xL: Int, val xR: Int, val y: Int, val dxL: Int, val dxR: Int, val dy: Int, val lvl0: Int, val lvl1: Int,
        val opt: Int, val prefs: List<Pref>, val source: Source?, val h0: Int = 0, val h1: Int = 0, val anchor: Int = 0,
        val w0: Int = 0, val w1: Int = 0, val aux0: Int = 0, val aux1: Int = 0, val cells: CellTable? = null, val rain: Rain? = null) {
        fun bytes(v2: Boolean = false): ByteArray {
            val b = ByteArray(if (v2) SEGMENT2_BYTES else SEGMENT_BYTES)
            b[0] = verb.toByte(); b[1] = steps.toByte(); b[2] = ease.code.toByte(); b[3] = kind.toByte()
            put16(b, 4, src); put16(b, 6, xL); put16(b, 8, xR); put16(b, 10, y); put16(b, 12, dxL); put16(b, 14, dxR); put16(b, 16, dy)
            b[18] = lvl0.toByte(); b[19] = lvl1.toByte(); b[20] = opt.toByte()
            put16(b, 22, (ease as? Ease.Table)?.off4 ?: 0)
            // 24..27 h0 h1 and 28 anchor (§5.4's layout; 0 in C1, §6.6 gives them meaning on a bit-10 build); 21 and 29
            // are reserved
            put16(b, 24, h0); put16(b, 26, h1); b[28] = anchor.toByte()
            prefs.getOrNull(0)?.let { b[30] = it.byte.toByte() }
            prefs.getOrNull(1)?.let { b[31] = it.byte.toByte() }
            // §7.2: a version-2 segment is §5.4's 32 B, then w0 w1 aux0 aux1 u16
            if (v2) { put16(b, 32, w0); put16(b, 34, w1); put16(b, 36, aux0); put16(b, 38, aux1) }
            return b
        }
        /** §7.2: PIECES layer old, SHRINK, or a rain whose record has reveal 1 or 2. */
        val inPlace: Boolean get() = (verb == PIECES && kind == LAYER_OLD) || verb == SHRINK || (verb == GLYPHS && (rain?.reveal ?: 0) != 0)
    }

    class Track internal constructor(val areaL: Rect, val areaR: Rect, val perLens: Boolean, val backdrop: Backdrop, val segments: List<Segment>) {
        fun area(left: Boolean) = if (left) areaL else areaR
        val usesSelf: Boolean get() = backdrop is Backdrop.Self || segments.any { (it.verb == DRAW || it.verb == REVEAL) && it.kind == KIND_SELF }
        /** Build C3 (§7.2): a `new` source (DRAW, REVEAL, PIECES layer new) or backdrop; a rain with reveal 1 reads the
         *  shadow too. */
        val usesNew: Boolean get() = backdrop is Backdrop.New || segments.any { (it.verb in DRAW..PIECES && it.kind == KIND_NEW) || it.rain?.reveal == 1 }
        val inPlace: Boolean get() = segments.any { it.inPlace }
        /** §5.5's pair form: the bounding box of the track's rects over both lenses. */
        val box: Rect get() = areaL.union(areaR)
    }

    class TrackBuilder internal constructor(private val perLens: Boolean, private val area: Rect, private val bit10: Boolean = false,
                                            private val v2: Boolean = false) {
        internal val segments = ArrayList<Segment>()

        fun wait(n: Int) = add(Segment(WAIT, n, Ease.Linear, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(), null))

        /** FILL (§5.4): the area filled with [level] (its `kind`, which no parameter replaces). */
        fun fill(level: Int, n: Int) {
            if (level !in 0..15) throw LintError("FILL's level is 0..15, not $level — refused (12)")
            add(Segment(FILL, n, Ease.Linear, level, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(), null))
        }

        /** DRAW (§5.4): [src] a record or `self` at (x + off(dx), y + off(dy)), its level eased lvl0 → lvl1; [opt] bits 4
         *  (transparent) and 5 (inverse) only. [xR]/[dxR] only on a per-lens track (they default to the left lens's).
         *  Build C2 (§6.6), on a program built for a bit-10 build only: the output height eased from [h0] to [h1] (both 0
         *  = unscaled, or both 1..2048 with every eased H inside 1..2048), placed by [anchor] (0 top · 1 centre · 2 bottom
         *  of the source's own height). A scaled `self` is not built: END could not express it as one §4 op.
         *  Build C3 (§7.5), version 2 only: `new` as [src] (OWN programs), the width eased from [w0] to [w1] (both 0 or
         *  both 1..2048) placed by [hAnchor] (0 left · 1 centre · 2 right), and a record's source window — its rows
         *  [windowFirst] .. [windowFirst] + [windowRows] − 1 ([windowRows] 0: the whole record). */
        fun draw(src: Source, x: Int, y: Int, dx: Int = 0, dy: Int = 0, lvl0: Int = 15, lvl1: Int = 15, ease: Ease = Ease.Linear,
                 n: Int, opt: Int = 0, xR: Int? = null, dxR: Int? = null, prefs: List<Pref> = emptyList(),
                 h0: Int = 0, h1: Int = 0, anchor: Int = 0, w0: Int = 0, w1: Int = 0, hAnchor: Int = 0, windowFirst: Int = 0, windowRows: Int = 0) {
            if (src is Source.Level) throw LintError("DRAW draws a record or self, not a level")
            if (opt and 0x30.inv() != 0) throw LintError("DRAW's opt takes bits 4 and 5 only, not 0x${opt.toString(16)}")
            onePair(xR, dxR)
            val scaled = h0 != 0 || h1 != 0 || prefs.any { it.target == Field.H0 || it.target == Field.H1 }
            if (!bit10 && (scaled || anchor != 0))
                throw LintError("h0/h1/anchor must be 0 in C1 (§5.4) — a scaled DRAW needs a program built for a bit-10 build (§6.6)")
            if (anchor !in 0..2) throw LintError("DRAW's anchor $anchor is not 0..2 — refused (5)")
            if (h0 !in 0..0xFFFF || h1 !in 0..0xFFFF) throw LintError("DRAW's h0 $h0 / h1 $h1 are not u16")
            val wide = w0 != 0 || w1 != 0 || prefs.any { it.target == Field.W0 || it.target == Field.W1 }
            val window = windowFirst != 0 || windowRows != 0 || prefs.any { it.target == Field.AUX0 || it.target == Field.AUX1 }
            if (!v2 && (src is Source.New || wide || hAnchor != 0 || window))
                throw LintError("`new`, w0/w1, the horizontal anchor and the source window are version 2's (§7.2, §7.5) — a version-1 record cannot carry them")
            if (hAnchor !in 0..2) throw LintError("DRAW's horizontal anchor $hAnchor is not 0..2 — refused (5, §7.2)")
            if (w0 !in 0..0xFFFF || w1 !in 0..0xFFFF || windowFirst !in 0..0xFFFF || windowRows !in 0..0xFFFF) throw LintError("DRAW's w0/w1/window are not u16")
            if (scaled && src is Source.Self) throw LintError("a scaled DRAW of `self` is not built: END could not draw the capture scaled as one §4 op")
            if (wide && src is Source.Self) throw LintError("§7.5: `self` only unscaled in both axes — refused (12)")
            if (window && src !is Source.Record) throw LintError("§7.5: with kind 2 or 4 aux0 and aux1 must be 0 — a source window is a record's (5)")
            val kind = when (src) { is Source.Self -> KIND_SELF; is Source.New -> KIND_NEW; else -> KIND_RECORD }
            add(Segment(DRAW, n, ease, kind, (src as? Source.Record)?.off4 ?: 0,
                x, if (perLens) xR ?: x else 0, y, dx, if (perLens) dxR ?: dx else 0, dy, lvl0, lvl1, opt, prefs, src, h0, h1,
                anchor or (hAnchor shl 2), w0, w1, windowFirst, windowRows))
        }

        /** REVEAL (§5.4): [src] wiped or slid in from [edge]. The source draws as it is (lvl0 = lvl1 = 15 sent, opt bits 4–5
         *  0: one picture under both readings of REVEAL's options, `C1-D2-built.md` R4). Build C3: `new` (version 2, OWN
         *  programs; its extent is the area's); a version-2 REVEAL sends lvl0 = lvl1 = 0 — §7.2's table names no level for
         *  it, so the record carries none. */
        fun reveal(src: Source, edge: Edge, slide: Boolean = false, ease: Ease = Ease.Linear, n: Int, x: Int, y: Int,
                   xR: Int? = null, prefs: List<Pref> = emptyList()) {
            onePair(xR, null)
            if (src is Source.New && !v2) throw LintError("REVEAL of `new` is version 2's (§7.2)")
            val (kind, s, w, h) = when (src) {
                is Source.Record -> listOf(KIND_RECORD, src.off4, 0, 0)
                is Source.Self -> listOf(KIND_SELF, 0, 0, 0)
                is Source.New -> listOf(KIND_NEW, 0, 0, 0)
                is Source.Level -> listOf(KIND_LEVEL, src.level, src.w, src.h)
            }
            val lvl = if (v2) 0 else 15
            add(Segment(REVEAL, n, ease, kind, s, x, if (perLens) xR ?: x else 0, y, w, 0, h, lvl, lvl,
                edge.code or (if (slide) 4 else 0), prefs, src))
        }

        /** Build C3, PIECES (§7.4), version 2: the area cut into [gw] × [gh] pieces, displaced by [model] ([UNIFORM],
         *  [RADIAL], [RANDOM]) with the factor eased from [f0] to [f1] (in 1/4096) — [layer] [LAYER_OLD] moves the
         *  panel's own picture in place (vacated pixels [vacate]), [LAYER_NEW] draws the shadow's pieces over the
         *  backdrop through the eased level ([transparent]: a source 0 not drawn; [reseed]: the random model's hash
         *  takes the step; [stagger] delays each piece by up to that many steps). [seed] is h0; [mixTag] (opt bit 7)
         *  exclusive-ors the instance's tag into it. */
        fun pieces(layer: Int, n: Int, ease: Ease = Ease.Linear, gw: Int = 1, gh: Int = 1, model: Int = UNIFORM, stagger: Int = 0,
                   x: Int = 0, y: Int = 0, dx: Int = 0, dy: Int = 0, xR: Int? = null, dxR: Int? = null, lvl0: Int = 15, lvl1: Int = 15,
                   seed: Int = 0, f0: Int = 0, f1: Int = 4096, vacate: Int = 0, transparent: Boolean = false, reseed: Boolean = false,
                   mixTag: Boolean = false, prefs: List<Pref> = emptyList()) {
            v2Only("PIECES")
            onePair(xR, dxR)
            if (layer != LAYER_OLD && layer != LAYER_NEW) throw LintError("PIECES' kind is 0 (old) or 4 (new), not $layer — refused (5, §7.2)")
            if (layer == LAYER_OLD && (transparent || reseed)) throw LintError("§7.4: layer old's opt bits 4 and 6 are refused (5)")
            if (model !in 0..2) throw LintError("§7.4: PIECES' model is 0..2 — model 3 is refused (5)")
            if (gw !in 0..0xFF || gh !in 0..0xFF || stagger !in 0..0xFF) throw LintError("PIECES' grid ${gw}x$gh and stagger $stagger are bytes of aux0 and aux1")
            if (f0 !in Short.MIN_VALUE..Short.MAX_VALUE || f1 !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("PIECES' f0 $f0 / f1 $f1 are s16")
            if (seed !in 0..0xFFFF) throw LintError("PIECES' seed $seed is a u16 (h0)")
            usesOnly("PIECES", prefs, Field.H1)
            val opt = (if (transparent) 0x10 else 0) or (if (reseed) 0x40 else 0) or (if (mixTag) 0x80 else 0)
            add(Segment(PIECES, n, ease, layer, if (layer == LAYER_OLD) vacate else 0, x, if (perLens) xR ?: x else 0, y, dx,
                if (perLens) dxR ?: dx else 0, dy, lvl0, lvl1, opt, prefs, null, h0 = seed, w0 = f0 and 0xFFFF, w1 = f1 and 0xFFFF,
                aux0 = gw or (gh shl 8), aux1 = model or (stagger shl 8)))
        }

        /** Build C3, SHRINK (§7.5), version 2, in place: the area's picture shrunk to (W, H) — each eased from (w0, h0)
         *  to (w1, h1), an axis with both 0 unscaled — rows and columns dropped in the bit-reversed order, placed by
         *  [anchor] (0 top · 1 centre · 2 bottom) and [hAnchor] (0 left · 1 centre · 2 right); vacated pixels [vacate];
         *  the kept pixels through G of the eased level (§7.4). */
        fun shrink(n: Int, ease: Ease = Ease.Linear, w0: Int = 0, w1: Int = 0, h0: Int = 0, h1: Int = 0, anchor: Int = 0, hAnchor: Int = 0,
                   vacate: Int = 0, lvl0: Int = 15, lvl1: Int = 15, prefs: List<Pref> = emptyList()) {
            v2Only("SHRINK")
            if (anchor !in 0..2 || hAnchor !in 0..2) throw LintError("SHRINK's anchors $anchor / $hAnchor are not 0..2 — refused (5, §7.2)")
            if (listOf(w0, w1, h0, h1).any { it !in 0..0xFFFF }) throw LintError("SHRINK's w0/w1/h0/h1 are u16")
            // xR and dxR are unused fields (5) only on a per-lens track: on one rect no rule reads them (the meeting's ruling R)
            usesOnly("SHRINK", prefs, Field.XL, Field.X, Field.Y, Field.DXL, Field.DX, Field.DY, Field.AUX0, Field.AUX1,
                *(if (perLens) arrayOf(Field.XR, Field.DXR) else emptyArray()))
            add(Segment(SHRINK, n, ease, 0, vacate, 0, 0, 0, 0, 0, 0, lvl0, lvl1, 0, prefs, null, h0, h1, anchor or (hAnchor shl 2), w0, w1))
        }

        /** Build C3, GLYPHS kind 0 CELLS (§7.6), version 2: the cell list [index] of [table] (aux0; a parameter may
         *  set it, pref [Field.AUX0]), offset by (x + off(dx), y + off(dy)), its level eased lvl0 → lvl1. */
        fun cells(table: CellTable, index: Int = 0, n: Int, ease: Ease = Ease.Linear, x: Int = 0, y: Int = 0, dx: Int = 0, dy: Int = 0,
                  xR: Int? = null, dxR: Int? = null, lvl0: Int = 15, lvl1: Int = 15, seed: Int = 0, mixTag: Boolean = false,
                  prefs: List<Pref> = emptyList()) {
            v2Only("GLYPHS")
            onePair(xR, dxR)
            if (index !in 0..0xFFFF || seed !in 0..0xFFFF) throw LintError("CELLS' index $index and seed $seed are u16")
            usesOnly("GLYPHS", prefs, Field.SRC, Field.H1, Field.W0, Field.W1, Field.AUX1)
            add(Segment(GLYPHS, n, ease, CELLS, table.off4, x, if (perLens) xR ?: x else 0, y, dx, if (perLens) dxR ?: dx else 0, dy,
                lvl0, lvl1, if (mixTag) 0x80 else 0, prefs, null, h0 = seed, aux0 = index, cells = table))
        }

        /** Build C3, GLYPHS kind 1 RAIN (§7.6), version 2: [rain]'s columns, offset by (x + off(dx), y + off(dy)). */
        fun rain(rain: Rain, n: Int, ease: Ease = Ease.Linear, x: Int = 0, y: Int = 0, dx: Int = 0, dy: Int = 0, xR: Int? = null,
                 dxR: Int? = null, lvl0: Int = 15, lvl1: Int = 15, seed: Int = 0, mixTag: Boolean = false, prefs: List<Pref> = emptyList()) {
            v2Only("GLYPHS")
            onePair(xR, dxR)
            if (seed !in 0..0xFFFF) throw LintError("RAIN's seed $seed is a u16 (h0)")
            usesOnly("GLYPHS RAIN", prefs, Field.SRC, Field.H1, Field.W0, Field.W1, Field.AUX0, Field.AUX1)
            add(Segment(GLYPHS, n, ease, RAIN, rain.off4, x, if (perLens) xR ?: x else 0, y, dx, if (perLens) dxR ?: dx else 0, dy,
                lvl0, lvl1, if (mixTag) 0x80 else 0, prefs, null, h0 = seed, rain = rain))
        }

        private fun v2Only(verb: String) { if (!v2) throw LintError("$verb is a version-2 verb (§7.2) — a version-1 record refuses it (5)") }

        /** §7.2: a field a verb 4–6 does not use must be 0 after substitution (5) — so no parameter may name one. */
        private fun usesOnly(verb: String, prefs: List<Pref>, vararg unused: Field) {
            prefs.firstOrNull { it.target in unused }?.let { throw LintError("$verb does not use ${it.target} (§7.2) — a parameter there is refused (5) unless it is 0") }
        }

        private fun onePair(xR: Int?, dxR: Int?) {
            if (!perLens && (xR != null || dxR != null)) throw LintError("xR/dxR on a one-rect track (area $area) would be ignored — give a pair")
        }

        private fun add(s: Segment) {
            if (s.steps !in 1..MAX_STEPS && s.prefs.none { it.target == Field.STEPS }) throw LintError("a segment's steps are 1..255, not ${s.steps}")
            if (s.prefs.size > 2) throw LintError("a segment carries two parameter uses, not ${s.prefs.size}")
            if (!v2 && s.prefs.any { it.target.code >= Field.W0.code }) throw LintError("pref codes 14–17 are version 2's (§7.2) — a version-1 record refuses them (5)")
            if (s.prefs.any { it.target == Field.W0 || it.target == Field.W1 || it.target == Field.AUX0 || it.target == Field.AUX1 } && s.verb in WAIT..REVEAL && s.verb != DRAW)
                throw LintError("w0, w1, aux0 and aux1 are not used by verb ${s.verb} (§7.2) — a parameter there is refused (5) unless it is 0")
            val hOk = when (s.verb) { DRAW -> bit10; SHRINK -> true; PIECES, GLYPHS -> s.prefs.none { it.target == Field.H1 }; else -> false }
            if (s.prefs.any { it.target == Field.H0 || it.target == Field.H1 } && !hOk)
                throw LintError(if (!bit10) "h0/h1 must be 0 in C1 (§5.4): no parameter may set them"
                    else "h0/h1 are DRAW's (§6.6), SHRINK's (§7.5) and the seed of PIECES and GLYPHS (h0, §7.2): a parameter setting them on verb ${s.verb} is refused (12)")
            for (v in listOf(s.xL, s.xR, s.y, s.dxL, s.dxR, s.dy)) if (v !in Short.MIN_VALUE..Short.MAX_VALUE) throw LintError("a segment coordinate $v is not an s16")
            if (s.lvl0 !in 0..255 || s.lvl1 !in 0..255) throw LintError("a segment level is a u8")
            if (s.src !in 0..0xFFFF) throw LintError("a segment's src ${s.src} is not a u16")
            if (s.ease is Ease.Table && s.ease.e.size != s.steps && !(s.verb == PIECES && s.aux1 shr 8 != 0))
                throw LintError("an easing table of ${s.ease.e.size} values for a segment of ${s.steps} steps — the firmware refuses it (5)")
            segments += s
        }
    }

    class ProgramBuilder internal constructor(private val bit10: Boolean = false, private val v2: Boolean = false) {
        internal val tracks = ArrayList<Track>()
        /** A track over one [area] on both lenses. */
        fun track(area: Rect, backdrop: Backdrop, build: TrackBuilder.() -> Unit) = add(area, area, false, backdrop, build)
        /** A per-lens track: [left] on the left lens, [right] on the right (one size, one row range). */
        fun track(left: Rect, right: Rect, backdrop: Backdrop, build: TrackBuilder.() -> Unit) = add(left, right, true, backdrop, build)
        private fun add(l: Rect, r: Rect, perLens: Boolean, backdrop: Backdrop, build: TrackBuilder.() -> Unit) {
            if (backdrop is Backdrop.New && !v2) throw LintError("the backdrop `new` (0x21) is version 2's (§7.2)")
            val b = TrackBuilder(perLens, l, bit10, v2).apply(build)
            if (b.segments.size !in 1..MAX_SEGMENTS) throw LintError("a track holds 1..8 segments, not ${b.segments.size}")
            tracks += Track(l, r, perLens, backdrop, b.segments.toList())
        }
    }

    /** A program placed at [at] (off4) in the cache. [records]: the image records a `src` parameter may name — the
     *  builder checks a substituted record only against these, and refuses one it was not given. [bit10] (build C2): the
     *  program is for a bit-10 build, so DRAW may carry h0/h1/anchor (§6.6); a C1 build refuses them (12, 5). */
    fun program(at: Int, records: List<Source.Record> = emptyList(), bit10: Boolean = false, build: ProgramBuilder.() -> Unit): MotionProgram {
        if (at !in 0..0xFFFF) throw LintError("a program at off4 $at is outside the u16 field")
        val tracks = ProgramBuilder(bit10).apply(build).tracks.toList()
        if (tracks.size !in 1..MAX_TRACKS) throw LintError("a program holds 1..8 tracks, not ${tracks.size}")
        for ((i, t) in tracks.withIndex()) {
            areasOk(i, t)
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

    /**
     * Build C3 (`FIRMWARE.md` §7.2): a version-2 program at [at] — built only through [effects] (EFFECTS in force), with
     * [own] (bit 0 OWN, §7.3: the instance holds its area; only a PLAY or PLAY-AFTER may open it) and [land] (bit 1,
     * needs OWN: step T copies the shadow in). Refused here, each with its section: the header's flags, every verb's
     * kinds, opt bits, anchors and unused fields, the context rules (`self` never in an OWN program; `new` and in-place
     * tracks only in one), the in-place track's rules (backdrop none, an ease that never falls, one non-WAIT segment,
     * the first and only in-place track), the areas and the none-backdrop rule, and every value no parameter changes;
     * what a parameter changes is decided at [play], [playAfter], [retarget] and [commit] (and the step budget there).
     */
    fun program2(at: Int, effects: Effects, own: Boolean = false, land: Boolean = false, records: List<Source.Record> = emptyList(),
                 build: ProgramBuilder.() -> Unit): MotionProgram {
        @Suppress("UNUSED_VARIABLE") val gate = effects                    // the type is the gate: only [effects] makes one
        if (at !in 0..0xFFFF) throw LintError("a program at off4 $at is outside the u16 field")
        if (land && !own) throw LintError("§7.2: LAND needs OWN — refused (5)")
        val tracks = ProgramBuilder(bit10 = true, v2 = true).apply(build).tracks.toList()
        if (tracks.size !in 1..MAX_TRACKS) throw LintError("a program holds 1..8 tracks, not ${tracks.size}")
        for ((i, t) in tracks.withIndex()) {
            areasOk(i, t)
            if (own && t.usesSelf) throw LintError("§7.2: track $i uses `self` — never in an OWN program (5)")
            if (!own && t.usesNew) throw LintError("§7.2: track $i reads `new` (a source, the backdrop or a rain's reveal 1) — only in an OWN program (5)")
            if (!own && t.inPlace) throw LintError("§7.2: track $i is in place (PIECES layer old, SHRINK, a rain with reveal 1 or 2) — only in an OWN program (5)")
        }
        val ip = tracks.indices.filter { tracks[it].inPlace }
        for (i in ip) {
            val t = tracks[i]
            if (t.backdrop !is Backdrop.None) throw LintError("§7.2: in-place track $i's backdrop is not none — refused (5)")
            if (t.segments.any { it.verb != WAIT && !nonDecreasing(it.ease) }) throw LintError("§7.2: in-place track $i has an ease that falls — refused (5)")
            if (t.segments.count { it.verb != WAIT } != 1) throw LintError("§7.2: in-place track $i holds WAITs and exactly one other segment — refused (12)")
        }
        if (ip.isNotEmpty() && ip != listOf(0)) throw LintError("§7.2: the in-place track is the program's first and only in-place track (tracks $ip) — refused (12)")
        // §7.2's none-backdrop rule for a version-2 record (2)
        for ((i, t) in tracks.withIndex()) if (!paintsWhole(t) && !(own && revealKeeps(t)) &&
                tracks.take(i).none { o -> paintsWhole(o) && o.areaL.contains(t.areaL) && o.areaR.contains(t.areaR) })
            throw LintError("§7.2: track $i's `none` backdrop does not paint its whole area and is not inside one earlier track that does" +
                (if (own) " (nor is it WAITs and one REVEAL whose ease never falls)" else "") + " — refused (2)")
        val body = ArrayList<Byte>()
        for (t in tracks) {
            body += listOf(t.segments.size, t.backdrop.code, if (t.perLens) 1 else 0, 0).map { it.toByte() }
            for (r in if (t.perLens) listOf(t.areaL, t.areaR) else listOf(t.areaL)) body += rectBytes(r).toList()
            for (s in t.segments) body += s.bytes(v2 = true).toList()
        }
        val length = HEADER2_BYTES + body.size
        if (length > 0xFFFF) throw LintError("a program of $length B does not fit its u16 length")
        val flags = (if (own) FLAG_OWN else 0) or (if (land) FLAG_LAND else 0)
        val bytes = byteArrayOf(2, tracks.size.toByte(), (length and 0xFF).toByte(), (length shr 8).toByte(), flags.toByte(), 0, 0, 0) + body.toByteArray()
        val tables = tracks.flatMap { t -> t.segments.mapNotNull { it.ease as? Ease.Table } }.distinctBy { it.off4 }
        val glyphTables = LinkedHashMap<Int, ByteArray>()
        for (t in tracks) for (s in t.segments) {
            s.cells?.let { c -> glyphTables[c.off4] = c.bytes; for (l in c.lists) { glyphTables[l.off4] = l.bytes; glyphTables[l.alphabet.off4] = l.alphabet.bytes } }
            s.rain?.let { r -> glyphTables[r.off4] = r.bytes; glyphTables[r.alphabet.off4] = r.alphabet.bytes }
        }
        val prog = MotionProgram(at, bytes, tracks, tables.map { it.off4 to it.record } + glyphTables.toList(), records.associateBy { it.off4 },
            version = 2, own = own, land = land)
        prog.resolve(IntArray(4), staticOnly = true)
        // decisions AF: the walk bound, on the program whatever its parameters pick (every CELLS segment at its largest list)
        prog.walkBytes().let { b -> if (b > WALK_BYTES_MAX) throw LintError("decisions AF: this program's GLYPHS walk would make the glasses read " +
            "$b B of fonts and glyph records, past the $WALK_BYTES_MAX B bound (≈ ${b / 4000} ms on the ui_task at 0.25 µs a byte) — refused offline") }
        return prog
    }

    private fun areasOk(i: Int, t: Track) {
        for (r in listOf(t.areaL, t.areaR)) if (r.w <= 0 || r.h <= 0 || r.x < 0 || r.y < 0 || r.right > Geometry.PANEL_W || r.bottom > Geometry.PANEL_H)
            throw LintError("track $i's area $r is empty or leaves the panel — refused (2)")
        if (t.perLens && (t.areaL.w != t.areaR.w || t.areaL.h != t.areaR.h || t.areaL.y != t.areaR.y))
            throw LintError("track $i's pair ${t.areaL} / ${t.areaR} is not one size and one row range — refused (2)")
    }

    /** §7.2: a track paints its whole area when its backdrop is a level, `self` or `new`, or it is in place. */
    private fun paintsWhole(t: Track) = t.backdrop !is Backdrop.None || t.inPlace

    /** §7.2's exception in an OWN program: WAITs and exactly one REVEAL, whose ease never falls. */
    private fun revealKeeps(t: Track) = t.segments.filter { it.verb != WAIT }.let { m -> m.size == 1 && m[0].verb == REVEAL && nonDecreasing(m[0].ease) }

    private fun nonDecreasing(e: Ease) = e !is Ease.Table || e.nonDecreasing

    /** Mode 26 PLAY of [program] (§5.5) with [p]: the parameters' checks run first (a refusal the phone can foresee
     *  is never sent); [cap4] must hold [MotionProgram.captureBytes] when a track uses `self`. Build C3: a version-2
     *  program's step budget (§7.12) — its C with every instance in [others] that can still draw after the PLAY's
     *  closes ([stepSum]) — at most the cap, else refused (12). C3 X3: [cost] the program's C under [p] where the caller
     *  keeps it (a transition's plan), else worked out here. */
    fun play(program: MotionProgram, slot: Int, period: Int, tag: Int, cap4: Int, p: IntArray, others: List<Drawing> = emptyList(),
             cost: Long? = null): ByteArray {
        program.resolve(p, staticOnly = false)
        if (program.captureBytes > 0) {
            val cap = cap4 * 4 until cap4 * 4 + program.captureBytes
            val first = program.firstRange(p)
            if (cap.first < first.last + 1 && first.first < cap.last + 1)
                throw LintError("the capture range [${cap.first}, ${cap.last + 1}) meets the program's first read range — refused (5)")
        }
        if (program.version == 2) budget(others + Drawing(cost ?: program.cost(p)), "PLAY")
        return CfwModes.motionPlay(slot, period, program.at, tag, cap4, p)
    }

    /** Build C3, mode 26 sub 8 PLAY-AFTER (§7.7) of [program] in [slot], its in half after the owner in [after]: the
     *  program carries OWN (12); the parameters' checks; the step budget with [others] — [waitsOn] the index in
     *  [others] of the drawing owner it waits on (its slot `after`), −1 when that slot holds none drawing (§7.12). C3 X3:
     *  [cost] as [play]'s. */
    fun playAfter(program: MotionProgram, slot: Int, period: Int, tag: Int, after: Int, p: IntArray,
                  others: List<Drawing> = emptyList(), waitsOn: Int = -1, cost: Long? = null): ByteArray {
        if (program.version != 2 || !program.own) throw LintError("§7.7: a PLAY-AFTER's program carries OWN — refused (12)")
        program.resolve(p, staticOnly = false)
        budget(others + Drawing(cost ?: program.cost(p), waitsOn), "PLAY-AFTER")
        return CfwModes.motionPlayAfter(slot, period, program.at, tag, after, p)
    }

    fun retarget(program: MotionProgram, slot: Int, p: IntArray, others: List<Drawing> = emptyList()): ByteArray {
        if (!program.tracks.all { t -> t.segments.size == 1 || (t.segments.size == 2 && t.segments[0].verb == WAIT && t.segments[1].verb != WAIT) })
            throw LintError("RETARGET needs at most one non-WAIT segment after an optional leading WAIT on every track — refused (12)")
        // §7.2 on a bit-11 build: the shape rule also refuses an OWN program, PIECES, SHRINK, GLYPHS and `new` (12)
        if (program.own || program.tracks.any { t -> t.usesNew || t.segments.any { it.verb >= PIECES } })
            throw LintError("§7.2: RETARGET's shape refuses an OWN program, PIECES, SHRINK, GLYPHS and a `new` source or backdrop (12)")
        program.resolve(p, staticOnly = false)
        if (program.version == 2) budget(others + Drawing(program.cost(p)), "RETARGET")
        return CfwModes.motionRetarget(slot, p)
    }

    fun close(slot: Int): ByteArray = CfwModes.motionClose(slot)

    /** Build C2: mode 26 COMMIT of [program] (§6.5) for the report [s] at [cursor] of [list] — the parameters' checks run
     *  first, as a PLAY's (COMMIT walks the program and checks every step's values, with the H rule; a refusal the phone
     *  can foresee is never sent). Build C3: a bound program ([checkBound]) and, for version 2, its own C at most the
     *  cap (§7.12: a COMMIT opens no instance). */
    fun commit(program: MotionProgram, slot: Int, s: Int, list: Int, cursor: Int, p: IntArray): ByteArray {
        checkBound(program)
        program.resolve(p, staticOnly = false)
        if (program.version == 2) budget(listOf(Drawing(program.cost(p))), "COMMIT")
        return CfwModes.motionCommit(slot, s, list, cursor, program.at, p)
    }

    /** Build C3 (§7.2 "where a program may run"): what a SET, a fire and a COMMIT refuse (5) of a program — OWN, and
     *  `self` (build C2); `new` and in-place tracks are OWN programs' already. The SET builders call it before naming
     *  [program]. */
    fun checkBound(program: MotionProgram): MotionProgram {
        if (program.own) throw LintError("§7.2: an OWN program runs only by PLAY or PLAY-AFTER — a SET, a fire or a COMMIT refuses it (5)")
        if (program.tracks.any { it.usesSelf }) throw LintError("§6.2/§7.2: a SET, a fire or a COMMIT refuses `self` (5)")
        return program
    }

    // ----------------------------------------------------------------------------------- build C3: the budget
    /** §7.12: one instance that can draw after a message or fire — its C (kept from its walk) and the index, in the
     *  same list, of the instance it waits on (a PLAY-AFTER whose `after` slot still holds that one's recorded triple;
     *  −1: none). */
    data class Drawing(val cost: Long, val waitsOn: Int = -1)

    /** §7.12's sum, unsigned 64-bit: over the instances that wait on none, f(i) = max(C_i, Σ f(w) over the instances w
     *  waiting on i) — an instance and its waiters never draw in one present, waiters on one instance draw together.
     *  A cycle of waits (an instance waiting on itself included) is refused loudly: on the glasses only a reused tag
     *  makes one, which the phone never sends (§7.11), so a cycle here is a defect of the caller, not a program to price. */
    fun stepSum(d: List<Drawing>): Long {
        for (i in d.indices) {
            var j = i
            var n = 0
            while (d[j].waitsOn in d.indices) {
                j = d[j].waitsOn
                if (++n > d.size) throw LintError("§7.12: instance $i of ${d.size} waits in a cycle — a defect of the caller (the phone never reuses a tag, §7.11)")
            }
        }
        fun f(i: Int): Long = maxOf(d[i].cost, d.indices.filter { d[it].waitsOn == i }.sumOf { f(it) })
        return d.indices.filter { d[it].waitsOn !in d.indices }.sumOf { f(it) }
    }

    private fun budget(d: List<Drawing>, what: String) {
        val sum = stepSum(d)
        if (sum > BUDGET_CAP) throw LintError("§7.12: the step budget at this $what is $sum u over ${d.size} instance(s), past the cap $BUDGET_CAP — refused (12)")
    }

    // ------------------------------------------------------------------------------------------------- END
    /** One op of END (§5.4's closing paragraph): a §4 sub-message the batch carries as it is, or a track's capture. */
    sealed class EndOp {
        /** Mode 20 (the track's area), mode 21 or mode 17, single or per-lens, ready for a mode-8 batch. */
        class Sub(val bytes: ByteArray) : EndOp()
        /** Track [track]'s capture — its area on each lens as the truth held it when the PLAY ran ([areaL], [areaR]) —
         *  drawn at (xL | xR, y) with [options] (null: the levels as they are), under the clip before it. */
        data class Capture(val track: Int, val areaL: Rect, val areaR: Rect, val xL: Int, val xR: Int, val y: Int, val options: Int?) : EndOp()
    }

    /** END(program, P) as §4 ops, in track order: per track its area's clip, its backdrop, then its final state —
     *  so the player applies to the compositor's truth exactly what the firmware writes into the shadow. A version-2
     *  program's END is [endOnModel]'s (its verbs have no §4 op). */
    fun end(program: MotionProgram, p: IntArray): List<EndOp> {
        if (program.version == 2) throw LintError("a version-2 program's END is drawn on the model (Motion.endOnModel): PIECES, SHRINK, GLYPHS, `new`, the two axes and the window have no §4 op")
        val segs = program.resolve(p, staticOnly = false)
        val out = ArrayList<EndOp>()
        program.tracks.forEachIndexed { i, t ->
            val (aL, aR) = t.areaL to t.areaR
            out += EndOp.Sub(if (aL == aR) CfwModes.clip(aL) else CfwModes.clipPair(aL, aR))
            when (val b = t.backdrop) {
                is Backdrop.Level -> out += EndOp.Sub(fillOf(aL, aR, b.code))
                is Backdrop.Self -> out += EndOp.Capture(i, aL, aR, aL.x, aR.x, aL.y, null)
                is Backdrop.None, is Backdrop.New -> {}
            }
            // at step T every track has run all its segments: it holds its last non-WAIT segment's final state
            val s = segs[i].lastOrNull { it.verb != WAIT } ?: return@forEachIndexed
            val xL = s.xL + (if (s.verb == DRAW) s.dxL else 0)
            val xR = (if (t.perLens) s.xR else s.xL) + (if (s.verb == DRAW) (if (t.perLens) s.dxR else s.dxL) else 0)
            val y = s.y + (if (s.verb == DRAW) s.dy else 0)
            when {
                s.verb == FILL -> out += EndOp.Sub(fillOf(aL, aR, s.kind))
                s.kind == KIND_SELF -> out += EndOp.Capture(i, aL, aR, xL, xR, y, if (s.verb == DRAW) (s.opt and 0x30) or s.lvl1 else null)
                s.kind == KIND_RECORD && s.verb == DRAW && s.h1 != 0 -> {
                    // build C2 (§6.6): a scaled DRAW at rest is the record H = h1 rows tall (v(n) = h0 + (h1 − h0) exactly),
                    // placed by its anchor — mode 28 under the area's clip, as the lens draws its step T
                    val r = s.record ?: throw LintError("track $i's record at off4 ${s.src} was not named to the builder")
                    val hOut = s.h1
                    val yOut = anchoredY(y, r.h, hOut, s.anchor)
                    if (Rect(xL, yOut, r.w, hOut).intersect(aL) == null && Rect(xR, yOut, r.w, hOut).intersect(aR) == null) return@forEachIndexed
                    val opt = (s.opt and 0x30) or s.lvl1
                    out += EndOp.Sub(if (xL == xR) CfwModes.drawScaled(s.src, xL, yOut, opt, hOut, r.w)
                        else CfwModes.drawScaledPair(s.src, xL, xR, yOut, opt, hOut, r.w))
                }
                s.kind == KIND_RECORD -> {
                    val r = s.record ?: throw LintError("track $i's record at off4 ${s.src} was not named to the builder")
                    // a draw that cannot reach its area paints nothing: no message (an unscaled DRAW's anchor moves it by
                    // (h − h) = 0, §6.6)
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

    /**
     * Build C3: the picture [program] leaves at step T on one lens, drawn by the simulator's model (`MotionSim`,
     * `EffectsSim` — the renderers the conformance vectors check), so a program's END can be compared with a window's
     * paint. [cache] holds the image records, fonts and glyph records the program names (its own records — the program
     * and its tables — are written into a copy of it here); [base] is the panel (640 × 480, 4bpp packed,
     * 320 B a row) where the program draws — for a bound or non-OWN program the shadow END is written into, for an OWN
     * program the framebuffer its first drawn step finds (§7.2 "The base": an in-place track's step T is a function of
     * that picture and T alone, whatever presents fell between); [shadow] is the live shadow `new`, LAND and rain's
     * reveal 1 read, and the truth a `self` capture takes. [tag] is the instance's (opt bit 7 mixes it into a seed). An
     * OWN program with LAND leaves this lens's owned rect the shadow's. Returns a new panel; [base] is not changed.
     */
    fun endOnModel(program: MotionProgram, p: IntArray, cache: ByteArray, base: ByteArray, shadow: ByteArray, left: Boolean, tag: Int = 0): ByteArray =
        stepOnModel(program, p, cache, base, shadow, left, tag, -1)

    /** C3 DM-1: [endOnModel]'s picture at step [k] of a bound program (1..T; −1 its last) — what one step paints over
     *  [base], every track in order at that step (a bound program is absolute: §7.2). For the step pins of Crush and Flip. */
    fun stepOnModel(program: MotionProgram, p: IntArray, cache: ByteArray, base: ByteArray, shadow: ByteArray, left: Boolean, tag: Int = 0, k: Int): ByteArray {
        program.resolve(p, staticOnly = false)
        val stride = Geometry.PANEL_W / 2
        val model = MotionSim(scaled = { true }, effects = { true })
        // the program's own records (the program, its easing tables, its GLYPHS tables) go into a copy of [cache], so the
        // model walks exactly what this program writes; [cache] holds the rest (image records, fonts, glyphs)
        val withProg = cache.copyOf()
        for ((o4, b) in program.records) b.copyInto(withProg, o4 * 4)
        val v = model.validate(withProg, program.at, p, MotionSim.Op.PLAY, armed = true)
        val prog = v as? MotionSim.Prog
            ?: throw LintError("the model refuses the program the DSL built (reason ${-(v as Int)}) — the DSL's checks and the model's walk disagree, or [cache] does not hold what the program names")
        val out = base.copyOf()
        if (k < 0 && prog.own && prog.land) { EffectsSim.copy(shadow, out, stride, prog.owned(left)); return out }
        if (k >= 0 && (prog.own || k !in 1..prog.T)) throw LintError("a step picture is a bound program's at 1..${prog.T}, not $k")
        val caps = Array(prog.tracks.size) { i -> prog.tracks[i].takeIf { it.usesSelf }?.rect(left)?.let { r -> MotionSim.Img(r.w, r.h, EffectsSim.grab(shadow, stride, r)) } }
        model.paint(MotionSim.Instance(0, tag, PERIOD_30HZ, program.at, prog, null, caps), if (k < 0) prog.T else k, out, stride, left, shadow, 0)
        return out
    }

    private fun fillOf(l: Rect, r: Rect, level: Int) = if (l == r) CfwModes.fill(l, level) else CfwModes.fillPair(l, r, level)

    private fun rectBytes(r: Rect) = ByteArray(8).also { put16(it, 0, r.x); put16(it, 2, r.y); put16(it, 4, r.w); put16(it, 6, r.h) }

    internal fun put16(b: ByteArray, at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }

    private fun pad4(b: ByteArray) = if (b.size % 4 == 0) b else b + ByteArray(4 - b.size % 4)

    /** §4's RLE stream at [start] walked to exactly [pixels]: per token its end pixel and its bytes (one byte
     *  `count << 4 | level`; count 0 takes the next byte, 0 again the u16 after it); null when a token is empty, the
     *  stream overruns [pixels] or leaves [b]. */
    internal fun rleTokens(b: ByteArray, start: Int, pixels: Int): List<IntArray>? {
        val out = ArrayList<IntArray>()
        var n = 0; var pos = start
        while (n < pixels) {
            if (pos >= b.size) return null
            var cnt = (b[pos].toInt() and 0xFF) shr 4; var used = 1
            if (cnt == 0) {
                if (pos + 1 >= b.size) return null
                cnt = b[pos + 1].toInt() and 0xFF; used = 2
                if (cnt == 0) { if (pos + 3 >= b.size) return null; cnt = (b[pos + 2].toInt() and 0xFF) or ((b[pos + 3].toInt() and 0xFF) shl 8); used = 4 }
            }
            if (cnt == 0 || cnt > pixels - n) return null
            n += cnt; pos += used
            out += intArrayOf(n, used)
        }
        return out
    }

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

    /** §6.6: where a DRAW of a source [h] rows tall drawn [hOut] rows tall starts — anchor 0 top (y) · 1 centre
     *  (`y + ((h − H) >> 1)`, arithmetic shift) · 2 bottom (`y + h − H`). */
    fun anchoredY(y: Int, h: Int, hOut: Int, anchor: Int): Int = when (anchor) {
        1 -> y + ((h - hOut) shr 1)
        2 -> y + h - hOut
        else -> y
    }
}

/**
 * A program record (§5.4; build C3: version 2, §7.2) placed at [at] (off4), with the easing tables it names. What the
 * phone writes for it is [records]: the program and its tables (the image records it draws are the caller's, already in
 * the cache; a version-2 program's cell tables, cell lists, alphabets and rain records are its tables too — the fonts
 * and glyph records are the atlas's).
 */
class MotionProgram internal constructor(val at: Int, val bytes: ByteArray, val tracks: List<Motion.Track>, val tables: List<Pair<Int, ByteArray>>,
    private val known: Map<Int, Motion.Source.Record>, val version: Int = 1, val own: Boolean = false, val land: Boolean = false) {
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

    /** Build C3 (§7.3): the owned rect on a lens — the bounding box of the tracks' rects there; the meeting box is
     *  [meetingBox], the one box over both lenses. */
    fun owned(left: Boolean): Rect = tracks.map { it.area(left) }.reduce { a, b -> a.union(b) }
    val meetingBox: Rect get() = owned(true).union(owned(false))

    fun stepsWith(p: IntArray): Int = resolve(p, staticOnly = false).maxOf { segs -> segs.sumOf { it.steps } }

    /** The first read range (§5.6) under [p], as a byte range: the program, every record its segments name, every table;
     *  build C3: a GLYPHS segment's cell table, the list its index picks, the alphabet, each font entry and glyph record
     *  its codes name, or the rain record and what it names. */
    fun firstRange(p: IntArray): IntRange {
        var lo = at * 4; var hi = at * 4 + bytes.size
        fun add(a: Int, b: Int) { lo = minOf(lo, a); hi = maxOf(hi, b) }
        for (segs in resolve(p, staticOnly = false)) for (s in segs) {
            s.record?.let { r -> add(s.src * 4, s.src * 4 + r.bytes) }
            (s.ease as? Motion.Ease.Table)?.let { tb -> add(tb.off4 * 4, tb.off4 * 4 + 2 + 2 * tb.e.size) }
            s.cells?.let { c ->
                add(c.off4 * 4, c.off4 * 4 + 4 + 2 * c.lists.size)
                val l = c.lists[s.aux0]
                add(l.off4 * 4, l.off4 * 4 + 8 + 12 * l.cells.size)
                add(l.alphabet.off4 * 4, l.alphabet.off4 * 4 + l.alphabet.bytes.size)     // padding included: the larger reading
                for (cell in l.cells) cell.font.reads(listOf(cell.old, cell.new) + l.alphabet.codes.toList(), ::add)
            }
            s.rain?.let { r -> add(r.off4 * 4, r.off4 * 4 + 16); add(r.alphabet.off4 * 4, r.alphabet.off4 * 4 + r.alphabet.bytes.size); r.font.reads(r.alphabet.codes.toList(), ::add) }
        }
        return lo until hi
    }

    /**
     * C3 decisions AF: the glyph bytes the glasses read at this program's walk (§7.6) — per GLYPHS segment, every font
     * the walk reads an entry from, counted whole (448 B), and every glyph record a code names in it (`[w][h]` and its RLE),
     * each (font, code) pair once a segment. §7.6 walks a pair once a walk for a program's first four fonts and once a
     * segment past them: counted once a segment for every font, the larger of the two. A CELLS segment counts its largest
     * list (a parameter may pick any); codes 0 and 32 name no record and a font named only through them is not read.
     */
    fun walkBytes(): Long {
        var total = 0L
        for (t in tracks) for (s in t.segments) {
            val reads: List<List<Pair<Motion.Font, Collection<Int>>>> = when {
                s.cells != null -> s.cells.lists.map { l ->
                    l.cells.groupBy { it.font.off4 }.values.map { cs -> cs[0].font to (cs.flatMap { listOf(it.old, it.new) } + l.alphabet.codes.toList()).toSet() } }
                s.rain != null -> listOf(listOf(s.rain.font to s.rain.alphabet.codes.toSet()))
                else -> continue
            }
            total += reads.maxOf { fonts -> fonts.sumOf { (f, codes) ->
                val glyphs = codes.mapNotNull { f.glyph(it) }
                if (glyphs.isEmpty()) 0L else 448L + glyphs.sumOf { 2L + it.rle }
            } }
        }
        return total
    }

    /** Tests only (C3 X3's pin): how many times [cost] was worked out for this program. */
    internal var costsWorked = 0
        private set

    /**
     * Build C3, §7.12: this program's cost C under [p], in u, unsigned 64-bit — over its tracks 1,024 + the backdrop's
     * term + the largest of its segments' terms, every term from both lenses' rects (A the larger area, Wa and Ha the
     * larger width and height); with LAND its owned rect's area (the larger lens's). Computed for a version-1 program
     * too (it counts in a sum, though the budget never refuses one).
     */
    fun cost(p: IntArray = IntArray(4)): Long {
        costsWorked++
        val segs = resolve(p, staticOnly = false)
        var c = 0L
        tracks.forEachIndexed { i, t ->
            val a = maxOf(t.areaL.w.toLong() * t.areaL.h, t.areaR.w.toLong() * t.areaR.h)
            val wa = maxOf(t.areaL.w, t.areaR.w).toLong(); val ha = maxOf(t.areaL.h, t.areaR.h).toLong()
            val bd = when (t.backdrop) { is Motion.Backdrop.None -> 0L; is Motion.Backdrop.Self -> 10 * a; else -> a }
            c += 1024 + bd + (segs[i].maxOfOrNull { segCost(it, a, wa, ha) } ?: 0L)
        }
        if (land) c += maxOf(owned(true).let { it.w.toLong() * it.h }, owned(false).let { it.w.toLong() * it.h })
        return c
    }

    private fun segCost(s: Resolved, a: Long, wa: Long, ha: Long): Long {
        val wide = s.w0 != 0 || s.w1 != 0                                        // "a width scales when w0 or w1 is not 0"
        return when (s.verb) {
            Motion.WAIT -> 0L
            Motion.FILL -> a
            Motion.DRAW, Motion.REVEAL -> when (s.kind) {
                Motion.KIND_SELF -> 10 * a
                Motion.KIND_NEW -> if (s.verb == Motion.DRAW) (if (wide) 10 * a else 3 * a) else a
                Motion.KIND_LEVEL -> a
                else -> {
                    val r = s.record ?: throw LintError("the record at off4 ${s.src} was not named to the builder — its L cannot be counted (§7.12)")
                    val l = if (s.verb == Motion.DRAW && s.aux1 != 0) r.tokenBytes(s.aux0, s.aux0 + s.aux1) else r.tokenBytes()
                    if (s.verb == Motion.DRAW && wide) 26 * l + 10 * a + r.w.toLong() * ha else 26 * l + 4 * a
                }
            }
            Motion.PIECES -> 3 * a
            Motion.SHRINK -> if (wide) 13 * a else 3 * a
            else -> s.cells?.let { tb ->
                val l = tb.lists[s.aux0]
                l.cells.sumOf { cell ->
                    val gs = (listOf(cell.old, cell.new) + l.alphabet.codes.toList()).mapNotNull { cell.font.glyph(it) }
                    minOf(cell.bw.toLong(), wa) * minOf(cell.bh.toLong(), ha) + 16L * (gs.maxOfOrNull { it.rle } ?: 0) +
                        10L * (gs.maxOfOrNull { it.w * it.h } ?: 0) + 64
                }
            } ?: s.rain!!.let { r ->
                val cell = r.cw.toLong() * r.ch
                val gs = r.alphabet.drawn.mapNotNull { r.font.glyph(it) }
                val g = if (gs.isEmpty()) 0L else 16L * gs.maxOf { it.rle } + 10 * cell
                a + r.cols.toLong() * r.trail * (cell + 64 + g)
            }
        }
    }

    /** One segment after substitution. */
    internal class Resolved(val verb: Int, var steps: Int, val ease: Motion.Ease, val kind: Int, var src: Int, var xL: Int, var xR: Int,
        var y: Int, var dxL: Int, var dxR: Int, var dy: Int, var lvl0: Int, var lvl1: Int, val opt: Int, var record: Motion.Source.Record?,
        var h0: Int = 0, var h1: Int = 0, val anchor: Int = 0, var w0: Int = 0, var w1: Int = 0, var aux0: Int = 0, var aux1: Int = 0,
        val cells: Motion.CellTable? = null, val rain: Motion.Rain? = null, val touched: Set<Motion.Field> = emptySet())

    /** Every segment with [p] substituted, checked as §5.5's values position does (12) and the record walk (5). With
     *  [staticOnly] only what no parameter can change is checked (a field a parameter replaces is skipped). */
    internal fun resolve(p: IntArray, staticOnly: Boolean): List<List<Resolved>> {
        if (p.size != 4) throw LintError("a PLAY carries four parameters, not ${p.size}")
        val out = tracks.mapIndexed { ti, t -> t.segments.map { s ->
            val touched = s.prefs.map { it.target }.toSet()
            val r = Resolved(s.verb, s.steps, s.ease, s.kind, s.src, s.xL, s.xR, s.y, s.dxL, s.dxR, s.dy, s.lvl0, s.lvl1, s.opt,
                s.source as? Motion.Source.Record, s.h0, s.h1, s.anchor, s.w0, s.w1, s.aux0, s.aux1, s.cells, s.rain, touched)
            if (!staticOnly) for (pr in s.prefs) {
                val v = p[pr.param]
                when (pr.target) {
                    Motion.Field.SRC -> { r.src = v and 0xFFFF; if (r.kind == Motion.KIND_RECORD && (r.verb == Motion.DRAW || r.verb == Motion.REVEAL)) r.record = known[r.src] }
                    Motion.Field.XL -> r.xL = v; Motion.Field.XR -> r.xR = v; Motion.Field.X -> { r.xL = v; r.xR = v }
                    Motion.Field.Y -> r.y = v
                    Motion.Field.DXL -> r.dxL = v; Motion.Field.DXR -> r.dxR = v; Motion.Field.DX -> { r.dxL = v; r.dxR = v }
                    Motion.Field.DY -> r.dy = v
                    Motion.Field.LVL0 -> r.lvl0 = v and 0xFF; Motion.Field.LVL1 -> r.lvl1 = v and 0xFF
                    Motion.Field.STEPS -> r.steps = v and 0xFF
                    // build C2 (§6.6; the builder lets only a bit-10 DRAW name them): u16 fields take the s16's 16 bits
                    Motion.Field.H0 -> r.h0 = v and 0xFFFF; Motion.Field.H1 -> r.h1 = v and 0xFFFF
                    // build C3 (§7.2): u16 fields take the whole value
                    Motion.Field.W0 -> r.w0 = v and 0xFFFF; Motion.Field.W1 -> r.w1 = v and 0xFFFF
                    Motion.Field.AUX0 -> r.aux0 = v and 0xFFFF; Motion.Field.AUX1 -> r.aux1 = v and 0xFFFF
                }
            }
            // §7.2 as ruled at the meeting (`C3-decisions.md` R): on a track that is not per-lens no rule reads xR or dxR
            // (a parameter may still name them) — the glasses use xL and dxL on both lenses, and so does every check below
            if (!t.perLens) { r.xR = r.xL; r.dxR = r.dxL }
            check(ti, r, touched, staticOnly)
            r
        } }
        if (!staticOnly || tracks.none { t -> t.segments.any { s -> s.prefs.any { it.target == Motion.Field.STEPS } } }) {
            val t = out.maxOf { segs -> segs.sumOf { it.steps } }
            if (t > Motion.MAX_STEPS) throw LintError("T = $t steps passes 255 — refused (12)")
        }
        if (version == 2) inPlaceValues(out, staticOnly)
        return out
    }

    /** §7.2's values position for the in-place track (track 0 when there is one; its shape and place are the builder's):
     *  a rain in place keeps dxL = dxR = dy = 0, and no later track meets its area while it moves pixels (12). */
    private fun inPlaceValues(out: List<List<Resolved>>, staticOnly: Boolean) {
        if (!tracks[0].inPlace) return
        val s = out[0].single { it.verb != Motion.WAIT }
        val geo = setOf(Motion.Field.DXL, Motion.Field.DXR, Motion.Field.DX, Motion.Field.DY)
        if (s.verb == Motion.GLYPHS && !(staticOnly && s.touched.any { it in geo }) && (s.dxL != 0 || s.dxR != 0 || s.dy != 0))
            throw LintError("§7.2: a rain track in place has dxL = dxR = dy = 0 — refused (12)")
        if (staticOnly && s.touched.any { it in geo || it == Motion.Field.W0 || it == Motion.Field.W1 || it == Motion.Field.H0 || it == Motion.Field.H1 || it == Motion.Field.AUX1 }) return
        val moves = when (s.verb) {
            Motion.PIECES -> !(s.w1.toShort().toInt() == 0 || ((s.aux1 and 3) != Motion.RADIAL && s.dxL == 0 && s.dxR == 0 && s.dy == 0))
            Motion.SHRINK -> s.w0 != 0 || s.w1 != 0 || s.h0 != 0 || s.h1 != 0
            else -> false
        }
        val later = tracks.drop(1).indexOfFirst { it.box.overlaps(tracks[0].box) }
        if (moves && later >= 0) throw LintError("§7.2: track ${later + 1} meets the in-place track's area while it moves pixels — refused (12)")
    }

    private fun check(ti: Int, r: Resolved, touched: Set<Motion.Field>, staticOnly: Boolean) {
        fun skip(vararg f: Motion.Field) = staticOnly && f.any { it in touched }
        if (!skip(Motion.Field.STEPS) && r.steps !in 1..Motion.MAX_STEPS) throw LintError("track $ti: a segment of ${r.steps} steps — refused (12)")
        if (r.verb == Motion.FILL && r.kind !in 0..15) throw LintError("track $ti: FILL level ${r.kind} — refused (12)")
        if ((r.verb == Motion.DRAW || r.verb == Motion.REVEAL) && r.kind == Motion.KIND_RECORD && r.record == null && !skip(Motion.Field.SRC))
            throw LintError("track $ti: a parameter names the record at off4 ${r.src}, which the builder was not given — it cannot check it")
        if (staticOnly && Motion.Field.STEPS in touched) return                // the eases below need the steps
        val stagger = if (r.verb == Motion.PIECES) r.aux1 shr 8 else 0
        if (r.verb == Motion.PIECES && skip(Motion.Field.AUX1)) return          // the table's n and every level read stagger
        // §7.4: with stagger the table's n is steps − stagger (either layer); stagger ≥ steps skips only that comparison
        val tableN = if (stagger == 0) r.steps else r.steps - stagger
        if (r.ease is Motion.Ease.Table && (stagger < r.steps || stagger == 0) && r.ease.e.size != tableN)
            throw LintError("track $ti: a table of ${r.ease.e.size} for ${if (stagger == 0) "${r.steps} steps" else "steps − stagger = $tableN"} — refused (5)")
        // E[0..steps]; under a PIECES stagger an uploaded table holds n' = steps − stagger entries and is read at
        // min(j, n'), E[n'] = 4096 standing for every later step (§7.4) — the only table whose n is not the steps here
        val e = if (r.ease is Motion.Ease.Table && r.ease.e.size != r.steps)
            IntArray(r.steps + 1) { j -> if (j == 0) 0 else r.ease.e[minOf(j, r.ease.e.size) - 1] } else Motion.easeE(r.ease, r.steps)
        fun levelsBad(ee: IntArray) = !skip(Motion.Field.LVL0, Motion.Field.LVL1) && ee.any { Motion.eased(r.lvl0, r.lvl1 - r.lvl0, it) !in 0..15 }
        if (r.verb == Motion.DRAW && levelsBad(e)) throw LintError("track $ti: DRAW's level leaves 0..15 along its ease (lvl ${r.lvl0} → ${r.lvl1}) — refused (12)")
        // build C2's H rule (§6.6 as ruled 2026-11-18): h0 and h1 both 0, or both inside 1..2048 and every step's eased H
        // inside 1..2048 — else 12, at a PLAY, a COMMIT and a fire alike
        if (r.verb == Motion.DRAW && !skip(Motion.Field.H0, Motion.Field.H1) && (r.h0 != 0 || r.h1 != 0)) {
            if (r.h0 !in CfwModes.VSCALE_H || r.h1 !in CfwModes.VSCALE_H)
                throw LintError("track $ti: DRAW's h0 ${r.h0} / h1 ${r.h1} are not both 0 or both 1..2048 — refused (12)")
            if (e.any { Motion.eased(r.h0, r.h1 - r.h0, it) !in CfwModes.VSCALE_H })
                throw LintError("track $ti: DRAW's eased H leaves 1..2048 (h ${r.h0} → ${r.h1}) — refused (12)")
        }
        if (r.verb == Motion.DRAW && version == 2) draw2(ti, r, e, ::skip)
        if (r.verb == Motion.REVEAL) {
            if (r.kind == Motion.KIND_LEVEL && (r.src > 15 || r.dxL < 1 || r.dy < 1)) throw LintError("track $ti: a level source ${r.src} of ${r.dxL}x${r.dy} — refused (12)")
            val f = when (r.kind) {
                Motion.KIND_RECORD -> r.record?.let { if ((r.opt and 3) <= 1) it.h else it.w }
                Motion.KIND_SELF, Motion.KIND_NEW -> tracks[ti].areaL.let { if ((r.opt and 3) <= 1) it.h else it.w }
                else -> if ((r.opt and 3) <= 1) r.dy else r.dxL
            }
            if (f != null && e.any { Motion.eased(0, f, it) !in 0..f }) throw LintError("track $ti: REVEAL's extent leaves 0..$f along its ease — refused (12)")
        }
        if (r.verb == Motion.PIECES) pieces(ti, r, stagger, e, ::skip)
        if (r.verb == Motion.SHRINK) {
            val a = tracks[ti].areaL
            if (!skip(Motion.Field.W0, Motion.Field.W1) && (r.w0 != 0 || r.w1 != 0) && !(r.w1 <= r.w0 && r.w0 <= a.w))
                throw LintError("track $ti: SHRINK's w0 ${r.w0} / w1 ${r.w1} are not 0 ≤ w1 ≤ w0 ≤ Wa ${a.w} (§7.5) — refused (12)")
            if (!skip(Motion.Field.H0, Motion.Field.H1) && (r.h0 != 0 || r.h1 != 0) && !(r.h1 <= r.h0 && r.h0 <= a.h))
                throw LintError("track $ti: SHRINK's h0 ${r.h0} / h1 ${r.h1} are not 0 ≤ h1 ≤ h0 ≤ Ha ${a.h} (§7.5) — refused (12)")
            if (!skip(Motion.Field.SRC) && r.src > 15) throw LintError("track $ti: SHRINK's vacate level ${r.src} is not 0..15 (§7.5) — refused (12)")
            if (levelsBad(e)) throw LintError("track $ti: SHRINK's level leaves 0..15 along its ease (§7.2: every eased level) — refused (12)")
        }
        if (r.verb == Motion.GLYPHS) {
            if (levelsBad(e)) throw LintError("track $ti: GLYPHS' level leaves 0..15 along its ease (§7.2) — refused (12)")
            val tb = r.cells
            if (tb != null && !skip(Motion.Field.AUX0)) {
                if (r.aux0 >= tb.lists.size) throw LintError("track $ti: CELLS' index ${r.aux0} is at or past the cell table's n ${tb.lists.size} (§7.6) — refused (5)")
                if (r.steps - tb.lists[r.aux0].noise < 1) throw LintError("track $ti: CELLS' n − N = ${r.steps} − ${tb.lists[r.aux0].noise} < 1 (§7.6) — refused (12)")
            }
        }
    }

    /** §7.5 DRAW version 2 after substitution: w0, w1 both 0 or both 1..2048 with every step's W inside 1..2048 (12);
     *  the source window inside the record (5, at the walk — never a SET's: that is [Motion.checkBound]'s caller's). */
    private fun draw2(ti: Int, r: Resolved, e: IntArray, skip: (Array<out Motion.Field>) -> Boolean) {
        if (!skip(arrayOf(Motion.Field.W0, Motion.Field.W1)) && (r.w0 != 0 || r.w1 != 0)) {
            // E[0] = 0 and E[n] = 4096 for every ease, so W(0) = w0 and W(n) = w1: this one test is "both 1..2048 with every
            // step's W inside" (one 0 and one not leaves it at an end); the pin watch found a separate end test redundant
            if (e.any { Motion.eased(r.w0, r.w1 - r.w0, it) !in 1..2048 })
                throw LintError("track $ti: DRAW's W (w0 ${r.w0} → w1 ${r.w1}) leaves 1..2048 along its ease — both 0, or both 1..2048 with every step's W inside (§7.5) — refused (12)")
        }
        val rec = r.record
        if (r.kind == Motion.KIND_RECORD && rec != null && !skip(arrayOf(Motion.Field.AUX0, Motion.Field.AUX1, Motion.Field.SRC))) {
            if (r.aux1 == 0 && r.aux0 != 0) throw LintError("track $ti: a source window of 0 rows names first row ${r.aux0} — aux1 = 0 means the whole record and then aux0 is 0 (§7.5) — refused (5)")
            if (r.aux0.toLong() + r.aux1 > rec.h) throw LintError("track $ti: the source window rows ${r.aux0} + ${r.aux1} leave the record's ${rec.h} rows (§7.5) — refused (5)")
        }
    }

    /** §7.4's values (12) and enumerations (5) for a PIECES segment after substitution. */
    private fun pieces(ti: Int, r: Resolved, stagger: Int, e: IntArray, skip: (Array<out Motion.Field>) -> Boolean) {
        val gw = r.aux0 and 0xFF; val gh = r.aux0 shr 8; val model = r.aux1 and 3
        val f0 = r.w0.toShort().toInt(); val f1 = r.w1.toShort().toInt()
        val g = Motion.Field.AUX0; val m = Motion.Field.AUX1
        val off = arrayOf(Motion.Field.DXL, Motion.Field.DXR, Motion.Field.DX, Motion.Field.DY)
        fun no(why: String, code: Int): Nothing = throw LintError("track $ti: PIECES $why (§7.4) — refused ($code)")
        if (!skip(arrayOf(m))) { if (model == 3) no("model 3", 5); if (r.aux1 and 0xFC != 0) no("aux1 bits 2–7 0x${(r.aux1 and 0xFC).toString(16)}", 5) }
        if (!skip(arrayOf(g)) && (gw !in 1..64 || gh !in 1..64 || gw * gh > 256)) no("grid ${gw}x$gh (each 1..64, at most 256 pieces)", 12)
        if (!skip(arrayOf(g, m)) && model == Motion.UNIFORM && r.aux0 != 0x0101) no("uniform with aux0 0x${r.aux0.toString(16)} (must be 0x0101)", 12)
        if (!skip(arrayOf(m, *off)) && model == Motion.RADIAL && (r.dxL != 0 || r.dxR != 0 || r.dy != 0)) no("radial with a displacement", 12)
        if (stagger != 0 && r.kind != Motion.LAYER_NEW) no("stagger on layer old", 12)
        if (stagger != 0 && !skip(arrayOf(Motion.Field.STEPS)) && r.steps - stagger < 1) no("stagger $stagger with ${r.steps} steps (n' = steps − stagger ≥ 1)", 12)
        if (r.kind == Motion.LAYER_NEW && !skip(arrayOf(Motion.Field.SRC)) && r.src != 0) no("layer new with src ${r.src} (must be 0)", 12)
        if (r.kind == Motion.LAYER_OLD) {
            if (!skip(arrayOf(Motion.Field.SRC)) && r.src > 15) no("layer old's vacate level ${r.src}", 12)
            if (!skip(arrayOf(Motion.Field.W0)) && f0 != 0) no("layer old with f0 $f0 (must be 0)", 12)
            if (!skip(arrayOf(m, Motion.Field.W1)) && model == Motion.RADIAL && f1 < 0) no("layer old radial with f1 $f1 < 0", 12)
            if (!skip(arrayOf(g, m, Motion.Field.DY)) && model == Motion.RANDOM && (gw != 1 || r.dy != 0)) no("layer old random needs gw = 1 and dy = 0", 12)
        }
        // every eased level 0..15 (12); under stagger an uploaded table (n' entries) is read at min(t, n') ([e] is so
        // built); ease 0 and 1 are eased over the segment's steps as DRAW's
        if (!skip(arrayOf(Motion.Field.LVL0, Motion.Field.LVL1)) && e.any { Motion.eased(r.lvl0, r.lvl1 - r.lvl0, it) !in 0..15 })
            no("level leaves 0..15 along its ease (lvl ${r.lvl0} → ${r.lvl1})", 12)
    }
}
