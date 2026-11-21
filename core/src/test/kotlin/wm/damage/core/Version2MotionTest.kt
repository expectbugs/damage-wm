package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.motion.Motion
import wm.damage.core.motion.Motion.Backdrop
import wm.damage.core.motion.Motion.Ease
import wm.damage.core.motion.Motion.Edge
import wm.damage.core.motion.Motion.Field
import wm.damage.core.motion.Motion.Pref
import wm.damage.core.motion.Motion.Source
import wm.damage.core.motion.MotionProgram
import wm.damage.core.sim.MotionSim
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.TextureCache

/**
 * Build C3, stage DP1b (`FIRMWARE.md` §7.2–§7.6, §7.12): the Motion DSL's version-2 records against two other readings —
 * the `v5-*` vector inputs `firmware/make_vectors.py` wrote (every program record and every table they name, rebuilt
 * through the DSL from its decoded fields, byte for byte) and the simulator's walk (`MotionSim.validate`, written by
 * another stage): what the DSL builds the walk accepts, on the vectors' programs and on a seeded random set, with the
 * same §7.12 cost. Then the DSL's refusals, one row per rule of §7.2, the one gate, the budget's sum and END on the model.
 */
class Version2MotionTest {

    private val fx = Motion.effects(DamageMsg.Caps(2, 0xdff), 0x7c)
    private fun model() = MotionSim(scaled = { true }, effects = { true })

    // ------------------------------------------------------------------------------------------------ the vectors
    private fun vectorDir(): Path {
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null && !Files.isDirectory(d.resolve("firmware/vectors"))) d = d.parent
        return d?.resolve("firmware/vectors") ?: fail("firmware/vectors not found")
    }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    private fun params(b: ByteArray, at: Int) = IntArray(4) { s16(b, at + 2 * it) }

    /** The top-level message and, for a batch, each sub-message. */
    private fun subs(m: ByteArray): List<ByteArray> {
        if (m.size < 2 || (m[0].toInt() and 0x7F) != 8) return listOf(m)
        val out = ArrayList<ByteArray>(); var i = 2
        repeat(u8(m, 1)) { if (i + 2 > m.size) return out; val n = u16(m, i); if (i + 2 + n > m.size) return out; out += m.copyOfRange(i + 2, i + 2 + n); i += 2 + n }
        return out
    }

    /** A record the DSL has no way to say (a reserved byte set, a field its verb ignores carrying a value, an
     *  enumeration past the contract's): listed with the reason, never compared. */
    private class Inexpressible(why: String) : Exception(why)
    private fun ex(why: String): Nothing = throw Inexpressible(why)

    /** What one rebuild read from the vector's cache: the program and the tables it names, by kind. */
    private class Rebuilt(val program: MotionProgram, val tables: List<Triple<String, Int, ByteArray>>)

    /**
     * The program record at [prog4] of [cache], decoded field by field into the DSL's own calls (a PIECES grid as
     * gw and gh, a model, a stagger; SHRINK's anchors; a cell list as cells with their fonts...) and built again.
     * [p] the naming message's parameters (null: a SET's, none) — a `src` parameter's record is then known.
     */
    private fun rebuild(cache: ByteArray, prog4: Int, p: IntArray?): Rebuilt {
        val off = prog4 * 4
        if (off + 8 > cache.size) ex("past the cache")
        val version = u8(cache, off)
        if (version != 1 && version != 2) ex("version $version (§7.2: 5)")
        val nTracks = u8(cache, off + 1); val len = u16(cache, off + 2)
        val head = if (version == 2) 8 else 4
        val flags = if (version == 2) u8(cache, off + 4) else 0
        if (version == 2 && (flags and 0xFC != 0)) ex("header flags bits 2–7 0x${(flags and 0xFC).toString(16)} (§7.2: 5)")
        if (version == 2 && (u8(cache, off + 5) != 0 || u16(cache, off + 6) != 0)) ex("the header's reserved bytes (§7.2: 5)")
        val segBytes = if (version == 2) 40 else 32
        class RawTrack(val bd: Int, val pl: Int, val rL: Rect, val rR: Rect, val segs: List<ByteArray>)
        val raw = ArrayList<RawTrack>()
        var pos = off + head
        repeat(nTracks) {
            if (pos + 4 > cache.size) ex("a track past the cache")
            val ns = u8(cache, pos); val bd = u8(cache, pos + 1); val pl = u8(cache, pos + 2)
            if (pl > 1) ex("perLens $pl (5)")
            if (u8(cache, pos + 3) != 0) ex("a track header's reserved byte (5)")
            fun rect(i: Int) = Rect(u16(cache, i), u16(cache, i + 2), u16(cache, i + 4), u16(cache, i + 6))
            val rL = rect(pos + 4); val rR = if (pl == 1) rect(pos + 12) else rL
            pos += 4 + (if (pl == 1) 16 else 8)
            if (pos + ns * segBytes > cache.size) ex("a segment past the cache")
            val segs = List(ns) { val s = cache.copyOfRange(pos, pos + segBytes); pos += segBytes; s }
            raw += RawTrack(bd, pl, rL, rR, segs)
        }
        if (pos - off != len) ex("the length field says $len, the tracks walk ${pos - off} (§5.4: 5)")
        val records = LinkedHashMap<Int, Source.Record>()
        val tables = ArrayList<Triple<String, Int, ByteArray>>()
        fun recordAt(o4: Int): Source.Record? = records[o4] ?: MotionSim.record(cache, o4 * 4)?.let { (_, end) ->
            Source.record(o4, cache.copyOfRange(o4 * 4, end)).also { records[o4] = it } }
        val glyphs = HashMap<Int, Motion.Glyph?>()
        fun glyph(o4: Int) = glyphs.getOrPut(o4) { runCatching { Motion.Glyph.of(o4, cache.copyOfRange(o4 * 4, minOf(cache.size, o4 * 4 + 70_000))) }.getOrNull() }
        val fonts = HashMap<Int, Motion.Font>()
        fun font(o4: Int) = fonts.getOrPut(o4) {
            val e = IntArray(224) { u16(cache, o4 * 4 + 2 * it) }
            Motion.Font(o4, e, e.distinct().mapNotNull { glyph(it) }).also { tables += Triple("font", o4, it.bytes) }
        }
        fun alphabet(o4: Int) = Motion.Alphabet(o4, IntArray(u8(cache, o4 * 4)) { u8(cache, o4 * 4 + 1 + it) }).also { tables += Triple("alphabet", o4, it.bytes) }
        fun cellList(o4: Int): Motion.CellList {
            val b = o4 * 4
            if (u8(cache, b + 5) != 0 || u16(cache, b + 6) != 0) ex("a cell list's reserved bytes (§7.6: 5)")
            val cells = List(minOf(u16(cache, b), 600)) { i ->
                val c = b + 8 + 12 * i
                val opt = u8(cache, c + 11)
                if (opt and 0xD0 != 0) ex("a cell's opt bits 4, 6, 7 (§7.6: 5)")
                Motion.Cell(s16(cache, c), s16(cache, c + 2), u8(cache, c + 4), u8(cache, c + 5), font(u16(cache, c + 6)), u8(cache, c + 8),
                    u8(cache, c + 9), u8(cache, c + 10), opt and 15, opt and 0x20 != 0)
            }
            return Motion.CellList(o4, alphabet(u16(cache, b + 2)), u8(cache, b + 4), cells).also { tables += Triple("cell list", o4, it.bytes) }
        }
        fun cellTable(o4: Int): Motion.CellTable {
            val b = o4 * 4
            if (u16(cache, b + 2) != 0) ex("a cell table's reserved u16 (§7.6: 5)")
            return Motion.CellTable(o4, List(minOf(u16(cache, b), 1100)) { cellList(u16(cache, b + 4 + 2 * it)) }).also { tables += Triple("cell table", o4, it.bytes) }
        }
        fun rainAt(o4: Int): Motion.Rain {
            val b = o4 * 4
            if (u16(cache, b + 14) != 0) ex("a rain record's reserved u16 (§7.6: 5)")
            return Motion.Rain(o4, u8(cache, b), u8(cache, b + 1), u8(cache, b + 2), u8(cache, b + 3), font(u16(cache, b + 4)), alphabet(u16(cache, b + 6)),
                u8(cache, b + 8), u8(cache, b + 9), u8(cache, b + 10), u8(cache, b + 11), u8(cache, b + 12), u8(cache, b + 13)).also { tables += Triple("rain", o4, it.bytes) }
        }
        fun ease(code: Int, t4: Int): Ease = when (code) {
            0 -> { if (t4 != 0) ex("a table field with ease 0 (ignored; the DSL writes 0)"); Ease.Linear }
            1 -> { if (t4 != 0) ex("a table field with ease 1 (ignored; the DSL writes 0)"); Ease.EaseOut }
            2 -> { if (u8(cache, t4 * 4 + 1) != 0) ex("a table's reserved byte (5)")
                   Ease.table(t4, IntArray(u8(cache, t4 * 4)) { s16(cache, t4 * 4 + 2 + 2 * it) }).also { tables += Triple("easing", t4, (it as Ease.Table).record) } }
            else -> ex("ease $code (5)")
        }
        fun Motion.TrackBuilder.segment(s: ByteArray, perLens: Boolean) {
            val verb = u8(s, 0); val n = u8(s, 1); val kind = u8(s, 3); val src = u16(s, 4)
            val xL = s16(s, 6); val xR = s16(s, 8); val y = s16(s, 10); val dxL = s16(s, 12); val dxR = s16(s, 14); val dy = s16(s, 16)
            val lvl0 = u8(s, 18); val lvl1 = u8(s, 19); val opt = u8(s, 20); val h0 = u16(s, 24); val h1 = u16(s, 26); val an = u8(s, 28)
            val w0 = if (version == 2) u16(s, 32) else 0; val w1 = if (version == 2) u16(s, 34) else 0
            val aux0 = if (version == 2) u16(s, 36) else 0; val aux1 = if (version == 2) u16(s, 38) else 0
            if (u8(s, 21) != 0 || u8(s, 29) != 0) ex("a segment's reserved bytes 21/29 (5)")
            val prefs = ArrayList<Pref>()
            for (pb in intArrayOf(u8(s, 30), u8(s, 31))) {
                if (pb and 0x80 == 0) { if (pb != 0) ex("a pref byte 0x${pb.toString(16)} without bit 7 (ignored; the DSL writes 0)"); continue }
                val f = Field.entries.firstOrNull { it.code == (pb shr 2) and 0x1F } ?: ex("pref code ${(pb shr 2) and 0x1F} (§7.2: 5)")
                prefs += Pref(pb and 3, f)
            }
            if (u8(s, 30) and 0x80 == 0 && u8(s, 31) and 0x80 != 0) ex("pref1 used with pref0 unused (the DSL writes uses in order)")
            if (!perLens && (xR != 0 || dxR != 0)) ex("xR/dxR on a one-rect track (ignored by §5.4/§7.2; the DSL writes 0)")
            val e = ease(u8(s, 2), u16(s, 22))
            val xr = if (perLens) xR else null; val dxr = if (perLens) dxR else null
            fun zero(vararg f: Pair<String, Int>) { f.firstOrNull { it.second != 0 }?.let { ex("verb $verb's ${it.first} = ${it.second}, a field it does not use") } }
            fun rec(): Source.Record = recordAt(src) ?: if (prefs.any { it.target == Field.SRC }) Source.Record(src, 1, 1, 5)
                else ex("DRAW/REVEAL names off4 $src, which holds no v2 record (the walk's 5)")
            if (an and 0xF0 != 0) ex("anchor bits 4–7 (§7.2: 5)")
            when (verb) {
                Motion.WAIT -> { zero("kind" to kind, "src" to src, "xL" to xL, "y" to y, "dxL" to dxL, "dy" to dy, "lvl0" to lvl0, "lvl1" to lvl1,
                    "opt" to opt, "h0" to h0, "h1" to h1, "anchor" to an, "w0" to w0, "w1" to w1, "aux0" to aux0, "aux1" to aux1, "prefs" to prefs.size)
                    if (e != Ease.Linear) ex("a WAIT's ease ${e.code} (the DSL writes 0)"); wait(n) }
                Motion.FILL -> { zero("src" to src, "xL" to xL, "y" to y, "dxL" to dxL, "dy" to dy, "lvl0" to lvl0, "lvl1" to lvl1, "opt" to opt, "h0" to h0,
                    "h1" to h1, "anchor" to an, "w0" to w0, "w1" to w1, "aux0" to aux0, "aux1" to aux1, "prefs" to prefs.size)
                    if (e != Ease.Linear) ex("a FILL's ease ${e.code} (the DSL writes 0)"); fill(kind, n) }
                Motion.DRAW -> {
                    val source = when (kind) { 0 -> rec(); 2 -> Source.Self; 4 -> Source.New; else -> ex("DRAW kind $kind (5)") }
                    if (kind != 0) zero("src" to src)
                    draw(source, xL, y, dxL, dy, lvl0, lvl1, e, n, opt, xr, dxr, prefs, h0, h1, an and 3, w0, w1, (an shr 2) and 3, aux0, aux1)
                }
                Motion.REVEAL -> {
                    if (opt and 0xF8 != 0) ex("REVEAL opt bits 3–7 (5)")
                    val want = if (version == 2) 0 else 15
                    if (lvl0 != want || lvl1 != want) ex("REVEAL's lvl0/lvl1 $lvl0/$lvl1 (ignored; the DSL writes $want)")
                    zero("dxR" to dxR, "h0" to h0, "h1" to h1, "anchor" to an, "w0" to w0, "w1" to w1, "aux0" to aux0, "aux1" to aux1)
                    val source = when (kind) { 0 -> rec(); 2 -> Source.Self; 3 -> Source.level(src, dxL, dy); 4 -> Source.New; else -> ex("REVEAL kind $kind (5)") }
                    if (kind != 3) zero("dxL" to dxL, "dy" to dy)
                    if (kind == 2 || kind == 4) zero("src" to src)
                    reveal(source, Edge.entries[opt and 3], opt and 4 != 0, e, n, xL, y, xr, prefs)
                }
                Motion.PIECES -> {
                    if (opt and 0x2F != 0) ex("PIECES opt bits 0–3, 5 (5)")
                    zero("h1" to h1, "anchor" to an)
                    if (aux1 and 0xFC != 0) ex("PIECES aux1 bits 2–7 (§7.4: 5)")
                    if (kind == Motion.LAYER_NEW && src != 0) ex("layer new's src $src (§7.4: 12; the DSL writes 0)")
                    pieces(kind, n, e, aux0 and 0xFF, aux0 shr 8, aux1 and 3, aux1 shr 8, xL, y, dxL, dy, xr, dxr, lvl0, lvl1, h0,
                        w0.toShort().toInt(), w1.toShort().toInt(), if (kind == Motion.LAYER_OLD) src else 0, opt and 0x10 != 0, opt and 0x40 != 0, opt and 0x80 != 0, prefs)
                }
                Motion.SHRINK -> {
                    if (kind != 0) ex("SHRINK kind $kind (5)")
                    zero("opt" to opt, "xL" to xL, "xR" to xR, "y" to y, "dxL" to dxL, "dxR" to dxR, "dy" to dy, "aux0" to aux0, "aux1" to aux1)
                    shrink(n, e, w0, w1, h0, h1, an and 3, (an shr 2) and 3, src, lvl0, lvl1, prefs)
                }
                Motion.GLYPHS -> {
                    if (opt and 0x7F != 0) ex("GLYPHS opt bits 0–6 (5)")
                    zero("h1" to h1, "w0" to w0, "w1" to w1, "aux1" to aux1, "anchor" to an)
                    when (kind) {
                        Motion.CELLS -> cells(cellTable(src), aux0, n, e, xL, y, dxL, dy, xr, dxr, lvl0, lvl1, h0, opt and 0x80 != 0, prefs)
                        Motion.RAIN -> { zero("aux0" to aux0); rain(rainAt(src), n, e, xL, y, dxL, dy, xr, dxr, lvl0, lvl1, h0, opt and 0x80 != 0, prefs) }
                        else -> ex("GLYPHS kind $kind (5)")
                    }
                }
                else -> ex("verb $verb (5)")
            }
        }
        // a `src` parameter's record, known from the naming message
        if (p != null) for (t in raw) for (s in t.segs) for (pb in intArrayOf(u8(s, 30), u8(s, 31)))
            if (pb and 0x80 != 0 && (pb shr 2) and 0x1F == 0 && u8(s, 0) in Motion.DRAW..Motion.REVEAL) recordAt(p[pb and 3] and 0xFFFF)
        fun backdrop(code: Int): Backdrop = when (code) {
            in 0..15 -> Backdrop.Level(code); 0x20 -> Backdrop.Self; 0x21 -> Backdrop.New; 0xFF -> Backdrop.None; else -> ex("backdrop 0x${code.toString(16)} (5)")
        }
        val build: Motion.ProgramBuilder.() -> Unit = {
            for (t in raw) if (t.pl == 1) track(t.rL, t.rR, backdrop(t.bd)) { for (s in t.segs) segment(s, true) }
                           else track(t.rL, backdrop(t.bd)) { for (s in t.segs) segment(s, false) }
        }
        val prog = if (version == 2) Motion.program2(prog4, fx, flags and 1 != 0, flags and 2 != 0, records.values.toList(), build)
                   else Motion.program(prog4, records.values.toList(), bit10 = true, build)
        return Rebuilt(prog, tables)
    }

    private enum class Namer { PLAY, AFTER, COMMIT, SET }

    /** Every program record and table the 28 `v5-*` vectors name — by a PLAY, a PLAY-AFTER, a COMMIT or a SET, batches
     *  unwrapped, against the cache their mode-19 writes have left at that message — rebuilt through the DSL: equal byte
     *  for byte, or listed (a record the DSL cannot say, or one it refuses, with the walk's answer beside it). And at
     *  every naming message the DSL's verdict against the model's walk: the DSL never accepts what the walk refuses, and
     *  where both accept a version-2 program its §7.12 C is the model's. */
    @Test
    fun everyV5ProgramAndTableIsTheDslsByteForByte_7_2() {
        val files = Files.list(vectorDir()).use { s -> s.filter { it.fileName.toString().let { n -> n.startsWith("v5-") && n.endsWith(".json") } }.sorted().toList() }
        // stage X2D (decisions Y, C-3) added eight `v5-x2-*` files beside the 20: every one is read here too; stage X3D the
        // shipped transitions (`v5-ship-*`, their records written as mode 25 where ZWRITE is in force) and `v5-x3-hint`;
        // stage X3T Encode's and Matrix's ship vectors (their cell lists and alphabets, rain records), filled at X5T's
        // comparison — the DSL reads inputs only
        // SV-083: Main's scroll effects and the notice's programs as ship vectors too (their version-1 records, named by the
        // session's SETs and the act's PLAYs and COMMITs)
        val x3t = ShipVectors.specs().count { it.effect.glyphs }
        val sv = ShipVectors.acts().size - ShipVectors.specs().size
        assertEquals(28 + ShipVectors.acts().size + 1, files.size,
            "the 20 v5 vectors, X2D's 8, X3D's ${ShipVectors.specs().size - x3t} ship vectors and v5-x3-hint, X3T's $x3t ship vectors, SV-083's $sv")
        val comparedProg = HashMap<String, Int>(); val comparedTab = HashMap<String, Int>()
        val seenProg = HashSet<String>(); val seenTab = HashSet<String>()
        val inexpressible = LinkedHashMap<String, String>(); val refusedByDsl = LinkedHashMap<String, String>()
        val mismatches = ArrayList<String>(); val dslLooser = ArrayList<String>(); val dslStricter = LinkedHashMap<String, String>(); val costs = ArrayList<String>()
        var verdicts = 0; var bothAccept = 0; var bothRefuse = 0; var costsCompared = 0
        for (f in files) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            val cache = ByteArray(256 * 1024)
            for ((si, st) in vec["steps"]!!.jsonArray.withIndex()) for (op in st.jsonObject["ops"]!!.jsonArray) {
                val h = op.jsonObject["msg"]?.jsonPrimitive?.content ?: continue
                for (m in subs(hex(h))) {
                    val mode = u8(m, 0); val sub = if (m.size > 1) u8(m, 1) else -1
                    if (mode == 19 || mode == 25) { for ((o, d) in ShipVectors.cacheWrites(m)) d.copyInto(cache, o); continue }
                    val (namer, prog4, p) = when {
                        mode == 26 && sub == 0 && m.size == 18 -> Triple(Namer.PLAY, u16(m, 4), params(m, 10))
                        mode == 26 && sub == 8 && m.size == 17 -> Triple(Namer.AFTER, u16(m, 4), params(m, 9))
                        mode == 26 && sub == 3 && m.size == 18 -> Triple(Namer.COMMIT, u16(m, 8), params(m, 10))
                        // a tap entry naming 0xFFFF is report-only (§6.3): no program (the shell's own, in the ship vectors)
                        mode == 27 && sub == 0 && m.size == 23 && u16(m, 9) != 0xFFFF -> Triple(Namer.SET, u16(m, 9), null)
                        else -> continue
                    }
                    val at = "$name step $si ${namer.name} off4 $prog4"
                    val recLen = u16(cache, prog4 * 4 + 2)
                    val recKey = "$name/$prog4/" + cache.copyOfRange(prog4 * 4, minOf(cache.size, prog4 * 4 + maxOf(recLen, 8))).joinToString("") { "%02x".format(it) }
                    val first = seenProg.add(recKey)
                    // the DSL: built (its static checks), then the naming message's own (the parameters', OWN's place)
                    val built: Rebuilt? = try { rebuild(cache, prog4, p) }
                        catch (e: Inexpressible) { if (first) inexpressible[at] = e.message!!; null }
                        catch (e: IndexOutOfBoundsException) { if (first) inexpressible[at] = "a structure it names reads past the cache"; null }
                        catch (e: LintError) { if (first) refusedByDsl[at] = e.message!!; null }
                    val dslOk = built != null && try {
                        when (namer) {
                            Namer.PLAY -> built.program.resolve(p!!, staticOnly = false)
                            Namer.AFTER -> { if (built.program.version != 2 || !built.program.own) throw LintError("§7.7: OWN"); built.program.resolve(p!!, staticOnly = false) }
                            Namer.COMMIT -> { Motion.checkBound(built.program); built.program.resolve(p!!, staticOnly = false) }
                            Namer.SET -> Motion.checkBound(built.program)
                        }
                        true
                    } catch (e: LintError) { if (first) refusedByDsl.putIfAbsent(at, e.message!!); false }
                    if (built != null && first) {
                        val v = if (built.program.version == 2) "v2" else "v1"
                        val want = cache.copyOfRange(prog4 * 4, prog4 * 4 + built.program.bytes.size)
                        if (!want.contentEquals(built.program.bytes)) mismatches += "$at: program differs"
                        else comparedProg[v] = (comparedProg[v] ?: 0) + 1
                    }
                    built?.tables?.forEach { (kind, o4, bytes) ->
                        if (!seenTab.add("$name/$kind/$o4/" + bytes.joinToString("") { "%02x".format(it) })) return@forEach
                        val want = cache.copyOfRange(o4 * 4, o4 * 4 + bytes.size)
                        if (!want.contentEquals(bytes)) mismatches += "$at: the $kind at off4 $o4 differs"
                        else comparedTab[kind] = (comparedTab[kind] ?: 0) + 1
                    }
                    // the model's walk at this message (EFFECTS armed: the gate is tested apart)
                    val sim = model()
                    val modelProg = when (namer) {
                        Namer.SET -> if (sim.ownStructureOk(cache, prog4, armed = true)) Unit else null
                        else -> sim.validate(cache, prog4, p!!, when (namer) { Namer.AFTER -> MotionSim.Op.AFTER; Namer.COMMIT -> MotionSim.Op.COMMIT; else -> MotionSim.Op.PLAY }, armed = true)
                            .takeIf { it is MotionSim.Prog }
                    }
                    verdicts++
                    when {
                        dslOk && modelProg == null -> dslLooser += "$at: the DSL accepts, the walk refuses (${sim.validate(cache, prog4, p ?: IntArray(4), MotionSim.Op.PLAY, armed = true)})"
                        !dslOk && modelProg != null -> dslStricter.putIfAbsent(at, inexpressible[at] ?: refusedByDsl[at] ?: "(an earlier message's reason)")
                        dslOk -> bothAccept++
                        else -> bothRefuse++
                    }
                    if (dslOk && modelProg is MotionSim.Prog && built!!.program.version == 2) {
                        costsCompared++
                        val a = built.program.cost(p!!); val b = sim.cost(modelProg)
                        if (a != b) costs += "$at: the DSL's C $a, the model's $b"
                    }
                }
            }
        }
        println("Version2MotionTest: programs compared byte for byte ${comparedProg.toSortedMap()}, tables ${comparedTab.toSortedMap()}")
        println("  verdicts at $verdicts naming messages: both accept $bothAccept, both refuse $bothRefuse, the DSL stricter ${dslStricter.size}, the DSL looser ${dslLooser.size}; costs compared $costsCompared")
        println("  records the DSL cannot say (${inexpressible.size}):"); inexpressible.forEach { (k, v) -> println("    $k: $v") }
        println("  records the DSL refuses (${refusedByDsl.size}):"); refusedByDsl.forEach { (k, v) -> println("    $k: $v") }
        println("  stricter than the walk (${dslStricter.size}):"); dslStricter.forEach { (k, v) -> println("    $k: $v") }
        assertEquals(emptyList(), mismatches, "byte mismatches")
        assertEquals(emptyList(), dslLooser, "the DSL would emit what the walk refuses")
        assertEquals(emptyList(), costs, "§7.12's C")
        assertTrue((comparedProg["v2"] ?: 0) > 50 && listOf("easing", "cell table", "cell list", "alphabet", "rain", "font").all { (comparedTab[it] ?: 0) > 0 },
            "every kind compared: $comparedProg $comparedTab")
    }

    // ---------------------------------------------------------------------------------- the random set
    /** A cache with two image records, four glyph records, a font naming them, two alphabets: what the random set's
     *  programs name. */
    private class Env {
        val cache = ByteArray(256 * 1024)
        private var next = 64 / 4
        fun put(b: ByteArray): Int { val o = next; b.copyInto(cache, o * 4); next += (b.size + 3) / 4; return o }
        private fun levels(w: Int, h: Int, seed: Int) = ByteArray(w * h) { (((it * 7 + seed) / 5 + seed) % 16).toByte() }
        private fun image(w: Int, h: Int, seed: Int) = TextureCache.Image2(w, h, levels(w, h, seed)).encode().let { b -> Source.record(put(b), b) }
        val imgA = image(40, 30, 3)
        val imgB = image(64, 120, 9)
        val glyphs = listOf(6 to 10, 7 to 12, 5 to 9, 8 to 11).mapIndexed { i, (w, h) ->
            val b = TextureCache.Image(w, h, levels(w, h, i)).encode(); val o = put(b); Motion.Glyph.of(o, b) }
        val font = Motion.Font(0, IntArray(224) { glyphs[it % 4].off4 }, glyphs).let { f -> val o = put(f.bytes); Motion.Font(o, f.entries, glyphs) }
        val alphaA = Motion.Alphabet(put(ByteArray(12)), IntArray(9) { 33 + it }).also { it.bytes.copyInto(cache, it.off4 * 4) }
        val alphaB = Motion.Alphabet(put(ByteArray(4)), intArrayOf(32, 65, 66)).also { it.bytes.copyInto(cache, it.off4 * 4) }
        val records = listOf(imgA, imgB)
    }

    private fun Motion.TrackBuilder.randomSegment(r: java.util.Random, env: Env, area: Rect, k: Int, perLens: Boolean, tables: IntArray, inPlace: Boolean = false) {
        val n = 1 + r.nextInt(12)
        fun lv() = if (r.nextInt(8) == 0) r.nextInt(20) else r.nextInt(16)
        fun ease(steps: Int): Ease = when (r.nextInt(5)) {
            0 -> Ease.EaseOut
            1 -> Ease.table(tables[0].also { tables[0] += 8 }, IntArray(steps) { j -> if (j == steps - 1) 4096 else (4096 * (j + 1)) / steps + (if (r.nextInt(6) == 0) -200 else 0) })
            else -> Ease.Linear
        }
        fun xr() = if (perLens) area.x + r.nextInt(8) else null
        fun pr(vararg f: Field) = if (r.nextInt(4) == 0) listOf(Pref(r.nextInt(4), f[r.nextInt(f.size)])) else emptyList()
        when (if (inPlace) intArrayOf(4, 5, 8)[r.nextInt(3)] else r.nextInt(9)) {
            0 -> wait(n)
            1 -> fill(lv(), n)
            2 -> { val src = when (r.nextInt(4)) { 0 -> Source.New; 1 -> Source.Self; 2 -> env.imgB; else -> env.imgA }
                   val wide = src !is Source.Self && r.nextInt(3) == 0; val tall = src !is Source.Self && r.nextInt(3) == 0
                   val win = src == env.imgB && r.nextInt(2) == 0
                   draw(src, area.x + r.nextInt(30) - 10, area.y + r.nextInt(30) - 10, r.nextInt(40) - 20, r.nextInt(40) - 20, lv(), lv(), ease(n), n,
                       opt = if (r.nextBoolean()) 0x10 else 0, xR = xr(), h0 = if (tall) 1 + r.nextInt(80) else 0, h1 = if (tall) 1 + r.nextInt(80) else 0,
                       anchor = r.nextInt(3), w0 = if (wide) 1 + r.nextInt(80) else 0, w1 = if (wide) r.nextInt(80) else 0, hAnchor = if (src is Source.Self) 0 else r.nextInt(3),
                       windowFirst = if (win) r.nextInt(100) else 0, windowRows = if (win) r.nextInt(40) else 0, prefs = pr(Field.X, Field.Y, Field.DY, Field.LVL1))
            }
            3 -> reveal(when (r.nextInt(3)) { 0 -> Source.New; 1 -> Source.level(lv(), 1 + r.nextInt(40), 1 + r.nextInt(40)); else -> env.imgA },
                    Edge.entries[r.nextInt(4)], r.nextBoolean(), ease(n), n, area.x, area.y, xr())
            4 -> { val model = r.nextInt(3); val layer = if (inPlace || r.nextBoolean()) Motion.LAYER_OLD else Motion.LAYER_NEW
                   val st = if (layer == Motion.LAYER_NEW && r.nextInt(3) == 0) r.nextInt(n) else 0
                   val g = if (model == Motion.UNIFORM) 1 to 1 else (1 + r.nextInt(8)) to (if (model == Motion.RANDOM && layer == Motion.LAYER_OLD) 1 + r.nextInt(4) else 1 + r.nextInt(8))
                   val gw = if (model == Motion.RANDOM && layer == Motion.LAYER_OLD) 1 else g.first
                   pieces(layer, n, if (st != 0 || k == 0) Ease.Linear else ease(n), gw, g.second, model, st, area.x + r.nextInt(20), area.y + r.nextInt(20),
                       if (model == Motion.RADIAL) 0 else r.nextInt(60) - 30, if (model == Motion.RADIAL || (model == Motion.RANDOM && layer == Motion.LAYER_OLD)) 0 else r.nextInt(60) - 30,
                       xR = xr(), lvl0 = lv(), lvl1 = lv(), seed = r.nextInt(65536), f1 = if (r.nextInt(5) == 0) 0 else 4096, vacate = r.nextInt(16),
                       transparent = layer == Motion.LAYER_NEW && r.nextBoolean(), reseed = layer == Motion.LAYER_NEW && r.nextBoolean(), mixTag = r.nextBoolean())
            }
            5 -> shrink(n, if (r.nextBoolean()) Ease.EaseOut else Ease.Linear, w0 = if (r.nextBoolean()) area.w else 0, w1 = if (r.nextBoolean()) r.nextInt(area.w + 1) else 0,
                    h0 = if (r.nextBoolean()) area.h else 0, h1 = r.nextInt(area.h + 1), anchor = r.nextInt(3), hAnchor = r.nextInt(3), vacate = r.nextInt(16), lvl0 = 15, lvl1 = lv())
            6, 7 -> { val lists = List(1 + r.nextInt(3)) { li ->
                          Motion.CellList(tables[1] + li * 64, if (r.nextBoolean()) env.alphaA else env.alphaB, r.nextInt(4), List(1 + r.nextInt(6)) {
                              Motion.Cell(r.nextInt(60), r.nextInt(30), 4 + r.nextInt(10), 6 + r.nextInt(10), env.font, if (r.nextBoolean()) 0 else 40 + r.nextInt(50),
                                  if (r.nextBoolean()) 32 else 60 + r.nextInt(40), if (r.nextBoolean()) Motion.FILL_NONE else r.nextInt(16), r.nextInt(16), r.nextBoolean()) }) }
                      val tb = Motion.CellTable(tables[1] + 63 * 4, lists); tables[1] += 64 * 5
                      cells(tb, r.nextInt(lists.size + 1), n, ease(n), r.nextInt(80), r.nextInt(40), r.nextInt(20), r.nextInt(20), xR = xr(), lvl0 = lv(), lvl1 = lv(),
                          seed = r.nextInt(65536), mixTag = r.nextBoolean(), prefs = pr(Field.AUX0, Field.Y)) }
            else -> { val rr = Motion.Rain(tables[2].also { tables[2] += 8 }, 1 + r.nextInt(20), 1 + r.nextInt(8), 6 + r.nextInt(8), 8 + r.nextInt(8), env.font,
                          if (r.nextBoolean()) env.alphaA else env.alphaB, reveal = if (inPlace) 1 + r.nextInt(2) else if (k == 0) r.nextInt(3) else 0, fillLvl = r.nextInt(16), spread = r.nextInt(5), flicker = 1 + r.nextInt(3))
                      rain(rr, n, Ease.Linear, if (rr.reveal != 0) 0 else r.nextInt(30), 0, 0, 0, lvl0 = lv(), lvl1 = lv(), seed = r.nextInt(65536)) }
        }
    }

    /** "The DSL never emits what the walk refuses" (§7.2): a seeded random set of version-2 programs built through the
     *  DSL's own calls (OWN or not, in-place first tracks, every verb, prefs, tables, cell lists, rain) — each one the
     *  DSL accepts with its parameters, written into a cache, the model's walk accepts too, with the DSL's §7.12 C; and
     *  its END on the model draws. */
    @Test
    fun whatTheDslBuildsTheModelsWalkAccepts_7_2() {
        val r = java.util.Random(70_202)
        var built = 0; var refused = 0
        val bad = ArrayList<String>()
        val reasons = HashMap<String, Int>()
        val verbs = IntArray(7)
        val env = Env()
        repeat(6000) { i ->
            val own = r.nextBoolean(); val land = own && r.nextBoolean()
            val tables = intArrayOf(30_000, 34_000, 26_000)
            val prog = try {
                Motion.program2(20_000, fx, own, land, env.records) {
                    repeat(1 + r.nextInt(3)) { k ->
                        val a = Rect(r.nextInt(400), r.nextInt(300), 20 + r.nextInt(200), 20 + r.nextInt(150))
                        val per = r.nextInt(3) == 0
                        if (k == 0 && own && r.nextBoolean()) {             // an in-place first track: none, a WAIT or not, one in-place segment
                            val ip: Motion.TrackBuilder.() -> Unit = { if (r.nextBoolean()) wait(1 + r.nextInt(4)); randomSegment(r, env, a, 0, per, tables, inPlace = true) }
                            if (per) track(a, a.translate(r.nextInt(8), 0), Backdrop.None, ip) else track(a, Backdrop.None, ip)
                            return@repeat
                        }
                        val bd = when (r.nextInt(5)) { 0 -> Backdrop.New; 1 -> Backdrop.Self; 2 -> Backdrop.None; else -> Backdrop.Level(r.nextInt(16)) }
                        val segs: Motion.TrackBuilder.() -> Unit = { repeat(1 + r.nextInt(2)) { randomSegment(r, env, a, k, per, tables) } }
                        if (per) track(a, a.translate(r.nextInt(8), 0), bd, segs) else track(a, bd, segs)
                    }
                }
            } catch (e: LintError) { refused++; reasons.merge(e.message!!.substringAfter("§").take(4), 1, Int::plus); return@repeat }
            val p = IntArray(4) { if (r.nextBoolean()) r.nextInt(120) - 20 else 0 }
            try { prog.resolve(p, staticOnly = false) } catch (e: LintError) { refused++; return@repeat }
            val cache = env.cache.copyOf()
            for ((o4, b) in prog.records) b.copyInto(cache, o4 * 4)
            val sim = model()
            val op = if (own && r.nextBoolean()) MotionSim.Op.AFTER else MotionSim.Op.PLAY
            val v = sim.validate(cache, prog.at, p, op, armed = true)
            if (v !is MotionSim.Prog) { bad += "#$i: the walk refuses ($v) what the DSL built: own $own land $land, ${prog.tracks.map { t -> t.segments.map { it.verb } }}"; return@repeat }
            if (prog.cost(p) != sim.cost(v)) bad += "#$i: C ${prog.cost(p)} against the model's ${sim.cost(v)}"
            val panel = ByteArray(320 * 480) { (it * 31).toByte() }; val shadow = ByteArray(320 * 480) { (it * 17 + 5).toByte() }
            Motion.endOnModel(prog, p, cache, panel, shadow, r.nextBoolean(), tag = r.nextInt(65536))
            for (t in prog.tracks) for (s in t.segments) verbs[s.verb]++
            built++
        }
        println("Version2MotionTest random set: built $built, refused $refused (by section ${reasons.toSortedMap()}), segments by verb ${verbs.toList()}")
        assertEquals(emptyList(), bad.take(20), "${bad.size} disagreements")
        assertTrue(built >= 600 && verbs.all { it > 30 }, "the set reaches every verb: built $built, ${verbs.toList()}")
    }

    // ------------------------------------------------------------------------------------- the refusals
    private val a = Rect(100, 100, 200, 120)
    private val glyph1 = Motion.Glyph(1000, 6, 10, 20)
    private val font = Motion.Font(1100, IntArray(224) { 1000 }, listOf(glyph1))
    private val alpha = Motion.Alphabet(1300, intArrayOf(65, 66, 67))
    private fun list(o4: Int = 1400, noise: Int = 0, vararg cells: Motion.Cell) =
        Motion.CellList(o4, alpha, noise, cells.toList().ifEmpty { listOf(Motion.Cell(0, 0, 8, 12, font, 0, 65)) })
    private fun table(vararg l: Motion.CellList) = Motion.CellTable(1500, l.toList().ifEmpty { listOf(list()) })
    private fun rainRec(reveal: Int = 0) = Motion.Rain(1600, 4, 3, 8, 12, font, alpha, reveal = reveal)
    private val pic = Source.Record(2000, 40, 30, 400)
    private fun own(build: Motion.ProgramBuilder.() -> Unit) = Motion.program2(3000, fx, own = true, build = build)
    private fun bound(build: Motion.ProgramBuilder.() -> Unit) = Motion.program2(3000, fx, build = build)

    /** One row per refusal of §7.2 (and §7.4–§7.7, §7.12 where §7.2's order names them) the phone can decide: each a
     *  loud [LintError] naming its section — the DSL never emits what the walk refuses. The rows' names are the report's
     *  table. */
    @Test
    fun theDslRefusesWhatSection72Refuses_7_2() {
        val rows = ArrayList<String>()
        fun refused(rule: String, section: String, block: () -> Unit) {
            val e = assertFailsWith<LintError>(rule) { block() }
            assertTrue(section in e.message!!, "$rule: '${e.message}' names $section")
            rows += rule
        }
        fun accepted(rule: String, block: () -> Unit) { block(); rows += "(accepted) $rule" }
        // the gate and the header
        refused("version 2 on a build without bit 11", "§7.11") { Motion.effects(DamageMsg.Caps(2, 0x5ff), 0x7c) }
        refused("version 2 with EFFECTS clear", "§7.2") { Motion.effects(DamageMsg.Caps(2, 0xdff), 0x3c) }
        refused("LAND without OWN", "§7.2") { Motion.program2(3000, fx, land = true) { track(a, Backdrop.Level(1)) { fill(2, 3) } } }
        refused("a version-1 record with PIECES", "§7.2") { Motion.program(3000, bit10 = true) { track(a, Backdrop.Level(1)) { pieces(Motion.LAYER_NEW, 3) } } }
        refused("a version-1 record with pref 14–17", "§7.2") { Motion.program(3000, listOf(pic), bit10 = true) { track(a, Backdrop.Level(1)) { draw(pic, 0, 0, n = 3, prefs = listOf(Pref(0, Field.W0))) } } }
        refused("a version-1 record with the backdrop new", "§7.2") { Motion.program(3000, bit10 = true) { track(a, Backdrop.New) { fill(2, 3) } } }
        // the fields each verb uses, opt bits and anchors (5)
        refused("a parameter into REVEAL's w0", "§7.2") { bound { track(a, Backdrop.Level(1)) { reveal(pic, Edge.TOP, n = 3, x = 100, y = 100, prefs = listOf(Pref(0, Field.W0))) } } }
        refused("a parameter into SHRINK's aux0", "§7.2") { own { track(a, Backdrop.None) { shrink(3, prefs = listOf(Pref(0, Field.AUX0))) } } }
        refused("a parameter into PIECES' h1", "§7.2") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, prefs = listOf(Pref(0, Field.H1))) } } }
        refused("a parameter into GLYPHS' w0", "§7.2") { bound { track(a, Backdrop.Level(0)) { cells(table(), n = 3, prefs = listOf(Pref(0, Field.W0))) } } }
        refused("a parameter into RAIN's aux0", "§7.2") { bound { track(a, Backdrop.Level(0)) { rain(rainRec(), 3, prefs = listOf(Pref(0, Field.AUX0))) } } }
        refused("layer old with opt bit 4", "§7.4") { own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, transparent = true) } } }
        refused("layer old with opt bit 6", "§7.4") { own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, reseed = true) } } }
        refused("PIECES model 3", "§7.4") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, model = 3) } } }
        refused("PIECES kind 2", "§7.2") { own { track(a, Backdrop.Level(0)) { pieces(2, 3) } } }
        refused("DRAW's horizontal anchor 3", "§7.2") { bound { track(a, Backdrop.Level(1)) { draw(pic, 0, 0, n = 3, w0 = 10, w1 = 20, hAnchor = 3) } } }
        refused("SHRINK's anchor 3", "§7.2") { own { track(a, Backdrop.None) { shrink(3, anchor = 3) } } }
        // where a program may run (5)
        refused("`self` in an OWN program", "§7.2") { own { track(a, Backdrop.Self) { wait(3) } } }
        refused("DRAW of `new` outside OWN", "§7.2") { bound { track(a, Backdrop.Level(1)) { draw(Source.New, 100, 100, n = 3) } } }
        refused("REVEAL of `new` outside OWN", "§7.2") { bound { track(a, Backdrop.Level(1)) { reveal(Source.New, Edge.LEFT, n = 3, x = 100, y = 100) } } }
        refused("PIECES layer new outside OWN", "§7.2") { bound { track(a, Backdrop.Level(1)) { pieces(Motion.LAYER_NEW, 3) } } }
        refused("the backdrop `new` outside OWN", "§7.2") { bound { track(a, Backdrop.New) { wait(3) } } }
        refused("PIECES layer old outside OWN", "§7.2") { bound { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3) } } }
        refused("SHRINK outside OWN", "§7.2") { bound { track(a, Backdrop.None) { shrink(3) } } }
        refused("a rain with reveal 2 outside OWN", "§7.2") { bound { track(a, Backdrop.None) { rain(rainRec(2), 3) } } }
        refused("a rain with reveal 1 outside OWN", "§7.2") { bound { track(a, Backdrop.None) { rain(rainRec(1), 3) } } }
        refused("an OWN program in a COMMIT or SET", "§7.2") { Motion.checkBound(own { track(a, Backdrop.Level(1)) { fill(2, 3) } }) }
        refused("an OWN program in a COMMIT", "§7.2") { Motion.commit(own { track(a, Backdrop.Level(1)) { fill(2, 3) } }, 0, 1, 0, 0, IntArray(4)) }
        // the in-place track (5 and 12)
        refused("an in-place track over a backdrop", "§7.2") { own { track(a, Backdrop.Level(1)) { shrink(3, h0 = 120, h1 = 10) } } }
        refused("an in-place track whose ease falls", "§7.2") { own { track(a, Backdrop.None) { shrink(3, Ease.table(900, intArrayOf(3000, 2000, 4096)), h0 = 120, h1 = 10) } } }
        refused("an in-place track with two moving segments", "§7.2") { own { track(a, Backdrop.None) { shrink(3, h0 = 120, h1 = 60); shrink(3, h0 = 60, h1 = 10) } } }
        refused("an in-place track that is not the first", "§7.2") { own { track(a, Backdrop.Level(1)) { fill(2, 3) }; track(a, Backdrop.None) { shrink(3, h0 = 120, h1 = 10) } } }
        refused("a later track meeting a moving in-place track", "§7.2") { own { track(a, Backdrop.None) { shrink(3, h0 = 120, h1 = 10) }; track(Rect(150, 150, 20, 20), Backdrop.Level(3)) { fill(2, 3) } } }
        accepted("a later track over a still in-place track (PIECES f1 = 0)") { own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, f1 = 0, lvl1 = 4) }; track(Rect(150, 150, 20, 20), Backdrop.Level(3)) { fill(2, 3) } } }
        refused("a moving in-place track meeting a later one by a parameter", "§7.2") {
            val pp = own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, dx = 0, prefs = listOf(Pref(0, Field.DX))) }; track(Rect(150, 150, 20, 20), Backdrop.Level(3)) { fill(2, 3) } }
            Motion.play(pp, 0, 34, 1, 0, intArrayOf(12, 0, 0, 0))
        }
        refused("a rain in place that moves", "§7.2") { own { track(a, Backdrop.None) { rain(rainRec(2), 3, dy = 10) } } }
        // areas and the none-backdrop rule (2)
        refused("an area off the panel", "(2)") { bound { track(Rect(600, 100, 100, 10), Backdrop.Level(1)) { fill(2, 3) } } }
        refused("a none backdrop outside a track that paints its whole area", "§7.2") { bound { track(a, Backdrop.None) { draw(pic, 100, 100, n = 3) } } }
        accepted("a none track inside an in-place track (it paints its whole area)") { own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, f1 = 0, lvl1 = 3) }; track(Rect(120, 120, 20, 20), Backdrop.None) { draw(Source.New, 120, 120, n = 3) } } }
        accepted("an OWN track of WAITs and one REVEAL over nothing") { own { track(a, Backdrop.None) { wait(2); reveal(Source.New, Edge.TOP, n = 3, x = 100, y = 100) } } }
        refused("an OWN none track with two REVEALs", "§7.2") { own { track(a, Backdrop.None) { reveal(Source.New, Edge.TOP, n = 3, x = 100, y = 100); reveal(Source.New, Edge.LEFT, n = 3, x = 100, y = 100) } } }
        refused("an OWN none REVEAL whose ease falls", "§7.2") { own { track(a, Backdrop.None) { reveal(Source.New, Edge.TOP, ease = Ease.table(910, intArrayOf(3000, 1000, 4096)), n = 3, x = 100, y = 100) } } }
        // DRAW version 2 (12, 5)
        refused("DRAW w0 0 with w1 5", "§7.5") { bound { track(a, Backdrop.Level(1)) { draw(pic, 100, 100, n = 3, w0 = 0, w1 = 5) } } }
        refused("DRAW w1 2049", "§7.5") { bound { track(a, Backdrop.Level(1)) { draw(pic, 100, 100, n = 3, w0 = 10, w1 = 2049) } } }
        refused("DRAW's eased W leaving 1..2048", "§7.5") { bound { track(a, Backdrop.Level(1)) { draw(pic, 100, 100, n = 3, w0 = 4, w1 = 1, ease = Ease.table(920, intArrayOf(4096, 8192, 4096))) } } }
        refused("DRAW of `self` scaled in width", "§7.5") { bound { track(a, Backdrop.Level(1)) { draw(Source.Self, 100, 100, n = 3, w0 = 10, w1 = 20) } } }
        refused("a source window past the record", "§7.5") { bound { track(a, Backdrop.Level(1)) { draw(pic, 100, 100, n = 3, windowFirst = 20, windowRows = 11) } } }
        refused("a source window of 0 rows at a first row (by a parameter)", "§7.5") {
            Motion.play(bound { track(a, Backdrop.Level(1)) { draw(pic, 100, 100, n = 3, prefs = listOf(Pref(1, Field.AUX0))) } }, 0, 34, 1, 0, intArrayOf(0, 4, 0, 0))
        }
        refused("a source window on `new`", "§7.5") { own { track(a, Backdrop.Level(1)) { draw(Source.New, 100, 100, n = 3, windowRows = 4) } } }
        // PIECES (12, 5)
        refused("a PIECES grid of 0 columns", "§7.4") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, gw = 0, gh = 2, model = Motion.RANDOM) } } }
        refused("a PIECES grid of 17 × 17", "§7.4") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, gw = 17, gh = 17, model = Motion.RANDOM) } } }
        refused("PIECES uniform with a 2 × 1 grid", "§7.4") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, gw = 2, gh = 1) } } }
        refused("PIECES radial with a displacement", "§7.4") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, gw = 2, gh = 2, model = Motion.RADIAL, dx = 4) } } }
        refused("stagger on layer old", "§7.4") { own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 5, gw = 1, gh = 2, model = Motion.RANDOM, stagger = 2) } } }
        refused("stagger at or past the steps", "§7.4") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, stagger = 3) } } }
        refused("a stagger table whose n is the steps", "(5)") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 5, Ease.table(930, intArrayOf(1000, 2000, 3000, 3500, 4096)), stagger = 2) } } }
        accepted("a stagger table of n = steps − stagger") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 5, Ease.table(930, intArrayOf(1000, 2000, 4096)), stagger = 2) } } }
        refused("layer new with a vacate level (src)", "§7.4") { Motion.play(own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, prefs = listOf(Pref(0, Field.SRC))) } }, 0, 34, 1, 0, intArrayOf(3, 0, 0, 0)) }
        refused("layer old with f0 ≠ 0", "§7.4") { own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, f0 = 100) } } }
        refused("layer old radial with f1 < 0", "§7.4") { own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, gw = 2, gh = 2, model = Motion.RADIAL, f1 = -100) } } }
        refused("layer old random with gw 2", "§7.4") { own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, gw = 2, gh = 2, model = Motion.RANDOM) } } }
        refused("a PIECES level leaving 0..15", "§7.4") { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, lvl1 = 16) } } }
        // SHRINK (12)
        refused("SHRINK w1 > w0", "§7.5") { own { track(a, Backdrop.None) { shrink(3, w0 = 100, w1 = 120) } } }
        refused("SHRINK w0 past the area", "§7.5") { own { track(a, Backdrop.None) { shrink(3, w0 = 201, w1 = 10) } } }
        refused("SHRINK's vacate level 16", "§7.5") { own { track(a, Backdrop.None) { shrink(3, h0 = 120, h1 = 10, vacate = 16) } } }
        refused("SHRINK's level leaving 0..15", "§7.2") { own { track(a, Backdrop.None) { shrink(3, h0 = 120, h1 = 10, lvl1 = 17) } } }
        // GLYPHS and its tables (5, 12)
        refused("a CELLS index at n (by a parameter)", "§7.6") { Motion.play(bound { track(a, Backdrop.Level(0)) { cells(table(), n = 3, prefs = listOf(Pref(0, Field.AUX0))) } }, 0, 34, 1, 0, intArrayOf(1, 0, 0, 0)) }
        refused("CELLS with n − N < 1", "§7.6") { bound { track(a, Backdrop.Level(0)) { cells(table(list(noise = 3)), n = 3) } } }
        refused("a GLYPHS level leaving 0..15", "§7.2") { bound { track(a, Backdrop.Level(0)) { cells(table(), n = 3, lvl1 = 16) } } }
        refused("a cell code 31", "§7.6") { Motion.Cell(0, 0, 8, 12, font, 31, 65) }
        refused("a cell fill 16", "§7.6") { Motion.Cell(0, 0, 8, 12, font, 0, 65, fill = 16) }
        refused("a code naming a glyph the builder was not given", "§7.6") { Motion.Cell(0, 0, 8, 12, Motion.Font(1100, IntArray(224) { 1000 + it }, listOf(glyph1)), 0, 66) }
        refused("a cell list of 0 cells", "§7.6") { Motion.CellList(1400, alpha, 0, emptyList()) }
        refused("a cell list of 513 cells", "§7.6") { Motion.CellList(1400, alpha, 0, List(513) { Motion.Cell(0, 0, 8, 12, font, 0, 65) }) }
        refused("a cell table of 0 lists", "§7.6") { Motion.CellTable(1500, emptyList()) }
        refused("an alphabet of 0 codes", "§7.6") { Motion.Alphabet(1300, IntArray(0)) }
        refused("an alphabet holding 31", "§7.6") { Motion.Alphabet(1300, intArrayOf(31, 65)) }
        refused("a rain record of 0 columns", "§7.6") { Motion.Rain(1600, 0, 3, 8, 12, font, alpha) }
        refused("a rain trail of 33", "§7.6") { Motion.Rain(1600, 4, 33, 8, 12, font, alpha) }
        refused("a rain reveal 3", "§7.6") { Motion.Rain(1600, 4, 3, 8, 12, font, alpha, reveal = 3) }
        refused("a rain flicker 0", "§7.6") { Motion.Rain(1600, 4, 3, 8, 12, font, alpha, flicker = 0) }
        refused("a rain level 16", "§7.6") { Motion.Rain(1600, 4, 3, 8, 12, font, alpha, headLvl = 16) }
        // the messages (12)
        refused("a PLAY-AFTER of a program without OWN", "§7.7") { Motion.playAfter(bound { track(a, Backdrop.Level(1)) { fill(2, 3) } }, 1, 34, 1, 0, IntArray(4)) }
        refused("a RETARGET of an OWN program", "§7.2") { Motion.retarget(own { track(a, Backdrop.Level(1)) { draw(pic, 100, 100, n = 3) } }, 0, IntArray(4)) }
        refused("a RETARGET of a GLYPHS program", "§7.2") { Motion.retarget(bound { track(a, Backdrop.Level(0)) { cells(table(), n = 3) } }, 0, IntArray(4)) }
        refused("T past 255", "(12)") { bound { track(a, Backdrop.Level(1)) { fill(1, 200); fill(2, 100) } } }
        // the step budget (12)
        val big = bound { repeat(8) { track(Rect(0, 0, 640, 480), Backdrop.Level(1)) { fill(2, 3) } } }    // 8 × (1,024 + 2 × 307,200)
        assertEquals(8L * (1024 + 2 * 307_200), big.cost())
        accepted("a program of 4.9 M u alone") { Motion.play(big, 0, 34, 1, 0, IntArray(4)) }
        refused("the same with another instance of 3.2 M u drawing", "§7.12") { Motion.play(big, 0, 34, 1, 0, IntArray(4), listOf(Motion.Drawing(3_200_000))) }
        accepted("the same as the in half waiting on an out half of 3.2 M u (the larger counts)") {
            Motion.playAfter(Motion.program2(3000, fx, own = true) { repeat(8) { track(Rect(0, 0, 640, 480), Backdrop.Level(1)) { fill(2, 3) } } }, 1, 34, 1, 0, IntArray(4),
                listOf(Motion.Drawing(3_200_000)), waitsOn = 0)
        }
        refused("two in halves waiting on one out half add", "§7.12") {
            Motion.playAfter(Motion.program2(3000, fx, own = true) { repeat(8) { track(Rect(0, 0, 640, 480), Backdrop.Level(1)) { fill(2, 3) } } }, 1, 34, 1, 0, IntArray(4),
                listOf(Motion.Drawing(1_000_000), Motion.Drawing(3_200_000, waitsOn = 0)), waitsOn = 0)
        }
        accepted("a version-1 program is never refused by the budget") {
            Motion.play(Motion.program(3000) { repeat(8) { track(Rect(0, 0, 640, 480), Backdrop.Level(1)) { fill(2, 3) } } }, 0, 34, 1, 0, IntArray(4), listOf(Motion.Drawing(7_000_000)))
        }
        println("Version2MotionTest refusals: ${rows.size} rows"); rows.forEach { println("  $it") }
    }

    /** §7.2 as ruled at the meeting (`C3-decisions.md` R): on a track that is not per-lens no rule reads xR or dxR. Each
     *  rule that names them, the R field set by a parameter: on one rect the DSL builds and plays the program and the
     *  model's walk takes it; on a per-lens track both refuse it, the DSL naming the section. */
    @Test
    fun onAOneRectTrackNeitherTheDslNorTheModelReadsXrOrDxr_7_2() {
        val env = Env()
        val pair = a.translate(4, 0)
        /** The DSL's refusal (its message, or null) for [build] played with [p], and the model's reason (0: taken) for the
         *  record the DSL built — null when the DSL refused it at the build, so there are no bytes. */
        fun both(p: IntArray, build: () -> MotionProgram): Pair<String?, Int?> {
            val prog = try { build() } catch (e: LintError) { return e.message to null }
            val dsl = try { Motion.play(prog, 0, 34, 1, 0, p); null } catch (e: LintError) { e.message }
            val cache = env.cache.copyOf()
            for ((o4, b) in prog.records) b.copyInto(cache, o4 * 4)
            val v = model().validate(cache, prog.at, p, MotionSim.Op.PLAY, armed = true)
            return dsl to (if (v is MotionSim.Prog) 0 else -(v as Int))
        }
        fun own(build: Motion.ProgramBuilder.() -> Unit) = Motion.program2(3000, fx, own = true, records = env.records, build = build)
        val bad = ArrayList<String>()
        fun row(rule: String, section: String, code: Int, p: IntArray, oneRect: () -> MotionProgram, perLens: () -> MotionProgram) {
            val (dsl1, sim1) = both(p, oneRect)
            if (dsl1 != null) bad += "$rule on one rect: the DSL refuses it: $dsl1"
            if (sim1 != null && sim1 != 0) bad += "$rule on one rect: the model refuses it ($sim1)"
            val (dsl, sim) = both(p, perLens)
            if (dsl == null || section !in dsl || "($code)" !in dsl) bad += "$rule on a per-lens track: the DSL does not refuse it naming $section ($code): $dsl"
            if (sim != null && sim != code) bad += "$rule on a per-lens track: the model's reason $sim, not $code"
        }
        val dxr = listOf(Pref(0, Field.DXR))
        val p = intArrayOf(6, 0, 0, 0)
        // §7.4 radial: dxL, dxR and dy must be 0 (12)
        row("radial PIECES with dxR 6", "§7.4", 12, p,
            { own { track(a, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, gw = 2, gh = 2, model = Motion.RADIAL, prefs = dxr) } } },
            { own { track(a, pair, Backdrop.Level(0)) { pieces(Motion.LAYER_NEW, 3, gw = 2, gh = 2, model = Motion.RADIAL, prefs = dxr) } } })
        // §7.2: a rain track in place has dxL = dxR = dy = 0 (12)
        val rain = Motion.Rain(30_000, 4, 3, 8, 12, env.font, env.alphaA, reveal = 2)
        row("a rain in place with dxR 6", "§7.2", 12, p,
            { own { track(a, Backdrop.None) { rain(rain, 3, prefs = dxr) } } },
            { own { track(a, pair, Backdrop.None) { rain(rain, 3, prefs = dxr) } } })
        // §7.2: a later track may not meet an in-place track while it moves pixels (12); uniform with dxL = dxR = dy = 0 is still
        row("a later track over PIECES uniform with dxR 6", "§7.2", 12, p,
            { own { track(a, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, x = a.x, y = a.y, prefs = dxr) }; track(Rect(150, 150, 20, 20), Backdrop.Level(3)) { fill(2, 3) } } },
            { own { track(a, pair, Backdrop.None) { pieces(Motion.LAYER_OLD, 3, x = a.x, y = a.y, prefs = dxr) }; track(Rect(150, 150, 20, 20), Backdrop.Level(3)) { fill(2, 3) } } })
        // §7.2: SHRINK does not use xR or dxR (5)
        val xrDxr = listOf(Pref(0, Field.XR), Pref(1, Field.DXR))
        row("SHRINK with xR 6 and dxR 3", "§7.2", 5, intArrayOf(6, 3, 0, 0),
            { own { track(a, Backdrop.None) { shrink(3, h0 = 120, h1 = 10, prefs = xrDxr) } } },
            { own { track(a, pair, Backdrop.None) { shrink(3, h0 = 120, h1 = 10, prefs = xrDxr) } } })
        assertEquals(emptyList(), bad, "rows:\n" + bad.joinToString("\n"))
    }

    /** §7.12's sum: 64-bit (no wrap into an accept), waiters on one instance add, a chain counts as its largest; a cycle
     *  of waits is the caller's defect and refused loudly (`C3-decisions.md` AP (3): the phone never builds one). */
    @Test
    fun theStepSumAddsWaitersAndNeverWraps_7_12() {
        assertEquals(10L, Motion.stepSum(listOf(Motion.Drawing(4), Motion.Drawing(6))))
        assertEquals(6L, Motion.stepSum(listOf(Motion.Drawing(4), Motion.Drawing(6, waitsOn = 0))), "an in half and its out half: the larger")
        assertEquals(9L, Motion.stepSum(listOf(Motion.Drawing(4), Motion.Drawing(6, waitsOn = 0), Motion.Drawing(3, waitsOn = 0))), "two waiters add")
        assertEquals(7L, Motion.stepSum(listOf(Motion.Drawing(4), Motion.Drawing(2, waitsOn = 0), Motion.Drawing(7, waitsOn = 1))), "a chain: its largest")
        for ((what, d) in listOf("a two-ring" to listOf(Motion.Drawing(3, waitsOn = 1), Motion.Drawing(4, waitsOn = 0)),
                                 "one waiting on itself" to listOf(Motion.Drawing(4), Motion.Drawing(3, waitsOn = 1)),
                                 "a waiter on a ring" to listOf(Motion.Drawing(5), Motion.Drawing(3, waitsOn = 2), Motion.Drawing(4, waitsOn = 1), Motion.Drawing(2, waitsOn = 1))))
            assertTrue(assertFailsWith<LintError>(what) { Motion.stepSum(d) }.message!!.contains("waits in a cycle"), "$what: refused, saying so")
        assertEquals(2L * 4_296_298_453L, Motion.stepSum(listOf(Motion.Drawing(4_296_298_453L), Motion.Drawing(4_296_298_453L))), "no 32-bit wrap")
    }

    // ------------------------------------------------------------------------------------------ END on the model
    private fun nib(b: ByteArray, x: Int, y: Int) = (b[y * 320 + x / 2].toInt() and 0xFF).let { if (x and 1 == 0) it shr 4 else it and 15 }
    private fun setNib(b: ByteArray, x: Int, y: Int, v: Int) {
        val i = y * 320 + x / 2; val o = b[i].toInt() and 0xFF
        b[i] = (if (x and 1 == 0) (o and 0x0F) or (v shl 4) else (o and 0xF0) or v).toByte()
    }
    private fun panel(seed: Int) = ByteArray(320 * 480).also { b -> for (y in 0 until 480) for (x in 0 until 640) setNib(b, x, y, (x / 3 + y * seed) % 16) }

    /** END on the model against pictures computed here from §7's text, one per new kind: a bound CELLS track settled
     *  (boxes filled, the new glyphs over them), an OWN PIECES layer old that moved the panel's picture by dx (the
     *  vacated columns `src`), a two-axis DRAW of a record at its last width, DRAW of `new` at rest, and LAND. */
    @Test
    fun endOnTheModelIsThePictureAtStepT_7_2() {
        val c = ByteArray(64 * 1024)
        val gLv = ByteArray(6 * 10) { ((it % 5) * 3).toByte() }                 // zeros in it: transparent
        val gBytes = TextureCache.Image(6, 10, gLv).encode(); gBytes.copyInto(c, 1000 * 4)
        val g = Motion.Glyph.of(1000, gBytes)
        val f = Motion.Font(1100, IntArray(224) { 1000 }, listOf(g)); f.bytes.copyInto(c, 1100 * 4)
        val al = Motion.Alphabet(1300, intArrayOf(65, 66)); al.bytes.copyInto(c, 1300 * 4)
        // settled at step T: a box is filled by phase unless the new code is not 0 (§7.6), and code 0 draws nothing
        val cells = listOf(Motion.Cell(10, 4, 8, 12, f, 0, 65, fill = 3), Motion.Cell(30, 4, 8, 12, f, 66, 66, fill = 5), Motion.Cell(50, 4, 8, 12, f, 65, 0, fill = 7))
        val tb = Motion.CellTable(1500, listOf(Motion.CellList(1400, al, 2, cells)))
        val area = Rect(200, 100, 120, 40)
        val glyphProg = bound { track(area, Backdrop.Level(1)) { cells(tb, n = 5, x = 200, y = 100) } }
        for ((o4, b) in glyphProg.records) b.copyInto(c, o4 * 4)
        val shadow = panel(7)
        val end = Motion.endOnModel(glyphProg, IntArray(4), c, shadow, shadow, left = true)
        val want = shadow.copyOf()
        for (y in area.y until area.bottom) for (x in area.x until area.right) setNib(want, x, y, 1)
        for (cell in cells) {
            if (cell.new == 0) for (y in 0 until cell.bh) for (x in 0 until cell.bw) setNib(want, 200 + cell.bx + x, 100 + cell.by + y, cell.fill)
            else for (y in 0 until 10) for (x in 0 until 6) gLv[y * 6 + x].toInt().let { if (it != 0) setNib(want, 200 + cell.bx + x, 100 + cell.by + y, it) }
        }
        assertContentEquals(want, end, "CELLS settled at step T")
        // PIECES layer old, uniform, dx 12 (in place: the base is the framebuffer)
        val base = panel(3)
        val moved = Motion.endOnModel(own { track(area, Backdrop.None) { pieces(Motion.LAYER_OLD, 4, x = 200, y = 100, dx = 12, vacate = 9) } }, IntArray(4), c, base, shadow, left = false)
        val w2 = base.copyOf()
        for (y in area.y until area.bottom) for (x in area.x until area.right) setNib(w2, x, y, if (x < area.x + 12) 9 else nib(base, x - 12, y))
        assertContentEquals(w2, moved, "PIECES layer old at step T: the base moved by dx")
        // a two-axis DRAW of a record at its last width (columns ⌊(2c + 1)·w / (2W)⌋), unscaled height
        val rLv = ByteArray(40 * 6) { (1 + (it % 40) % 15).toByte() }
        val rec = TextureCache.Image2(40, 6, rLv).encode(); rec.copyInto(c, 2000 * 4)
        val pr = Source.record(2000, rec)
        val wide = bound { track(area, Backdrop.Level(0)) { draw(pr, 210, 110, n = 3, w0 = 40, w1 = 13, hAnchor = 1) } }
        val e3 = Motion.endOnModel(wide, IntArray(4), c, shadow, shadow, left = true)
        val w3 = shadow.copyOf()
        for (y in area.y until area.bottom) for (x in area.x until area.right) setNib(w3, x, y, 0)
        val x0 = 210 + ((40 - 13) shr 1)
        for (y in 0 until 6) for (cc in 0 until 13) setNib(w3, x0 + cc, 110 + y, rLv[y * 40 + ((2 * cc + 1) * 40) / 26].toInt())
        assertContentEquals(w3, e3, "a two-axis DRAW at its last width, centred")
        // DRAW of `new` at rest paints the shadow's own picture; LAND leaves the owned rect the shadow's
        val atRest = Motion.endOnModel(own { track(area, Backdrop.Level(4)) { draw(Source.New, 200, 100, n = 3) } }, IntArray(4), c, base, shadow, left = true)
        val w4 = base.copyOf(); for (y in area.y until area.bottom) for (x in area.x until area.right) setNib(w4, x, y, nib(shadow, x, y))
        assertContentEquals(w4, atRest, "DRAW of `new` at rest")
        val landed = Motion.endOnModel(Motion.program2(3000, fx, own = true, land = true) { track(area, Backdrop.Level(4)) { fill(2, 3) }; track(Rect(400, 300, 10, 10), Backdrop.Level(6)) { wait(3) } },
            IntArray(4), c, base, shadow, left = true)
        val w5 = base.copyOf(); for (y in 100 until 310) for (x in 200 until 410) setNib(w5, x, y, nib(shadow, x, y))
        assertContentEquals(w5, landed, "LAND: the owned rect (the tracks' bounding box) is the shadow's")
        assertFailsWith<LintError> { Motion.end(glyphProg, IntArray(4)) }
    }

    /** §7.6's hash, written here from the text (the model's is checked against the contract's table elsewhere). */
    private fun h(x0: Int): Int { var x = x0; x = x xor (x ushr 16); x *= 0x7FEB352D; x = x xor (x ushr 15); x *= 0x846CA68B.toInt(); return x xor (x ushr 16) }
    private fun h2(s: Int, a: Int) = h(s xor h(a xor 0x9E3779B9.toInt()))
    private fun h3(s: Int, a: Int, b: Int) = h(h2(s, a) xor h(b xor 0x85EBCA6B.toInt()))
    /** §7.5's drop order of a size n: t = 0 .. 2^m − 1 with its m low bits reversed, each value below n in turn. */
    private fun dropOrder(n: Int): List<Int> {
        var m = 0; while ((1 shl m) < n) m++
        return (0 until (1 shl m)).map { t -> (0 until m).fold(0) { a, b -> a or (((t shr b) and 1) shl (m - 1 - b)) } }.filter { it < n }
    }

    /** Decisions W, D-7: END on the model for the kinds `endOnTheModelIsThePictureAtStepT_7_2` does not hold — SHRINK,
     *  RAIN, PIECES radial, random, layer new, stagger under a level ramp, the source window, REVEAL of `new` — each picture
     *  computed here from §7's text (§7.4–§7.6, the v1 LUT `lut[i] = i·level / 15`, `CfwModes` line 70's lineage), never
     *  from the model's renderers: each checks that the builder's arguments land in the fields the text reads. */
    @Test
    fun endOnTheModelForTheOtherKinds_7_2() {
        val c = ByteArray(64 * 1024)
        val gLv = ByteArray(6 * 10) { ((it % 5) * 3).toByte() }
        val gBytes = TextureCache.Image(6, 10, gLv).encode(); gBytes.copyInto(c, 1000 * 4)
        val f = Motion.Font(1100, IntArray(224) { 1000 }, listOf(Motion.Glyph.of(1000, gBytes))); f.bytes.copyInto(c, 1100 * 4)
        val al = Motion.Alphabet(1300, intArrayOf(65, 66)); al.bytes.copyInto(c, 1300 * 4)
        val area = Rect(200, 100, 60, 30)
        val base = panel(3); val shadow = panel(7)
        fun end(prog: MotionProgram): ByteArray {
            for ((o4, b) in prog.records) b.copyInto(c, o4 * 4)
            return Motion.endOnModel(prog, IntArray(4), c, base, shadow, left = true)
        }
        fun inArea(x: Int, y: Int) = x >= area.x && x < area.right && y >= area.y && y < area.bottom
        fun over(fill: (Int, Int) -> Int): ByteArray = base.copyOf().also { w -> for (y in area.y until area.bottom) for (x in area.x until area.right) setNib(w, x, y, fill(x, y)) }

        // SHRINK both axes to 20 × 9, rows top-anchored, columns centred, vacate 9: at T from base step 0 the kept rows and
        // columns (the first H and W of each drop order, in increasing index) sit at a + r; the rest is the vacate level
        run {
            val rows = dropOrder(30).take(9).sorted(); val cols = dropOrder(60).take(20).sorted()
            val ax = (60 - 20) shr 1
            val want = over { x, y ->
                val i = x - area.x - ax; val j = y - area.y
                if (i in 0 until 20 && j in 0 until 9) nib(base, area.x + cols[i], area.y + rows[j]) else 9
            }
            assertContentEquals(want, end(own { track(area, Backdrop.None) { shrink(2, w0 = 60, w1 = 20, h0 = 30, h1 = 9, anchor = 0, hAnchor = 1, vacate = 9) } }), "SHRINK at step T")
        }
        // RAIN reveal 2 at offset (3, 2): at T every head is R + trail, so nothing is lit and every cell box of every column
        // above the trail (all R rows) is filled with fillLvl, clipped to the area; R = ⌈30 / 7⌉ = 5
        run {
            val rain = Motion.Rain(1600, cols = 5, trail = 3, cw = 8, ch = 7, font = f, alphabet = al, reveal = 2, fillLvl = 6)
            val want = base.copyOf()
            for (col in 0 until 5) for (r in 0 until 5) for (y in 0 until 7) for (x in 0 until 8) {
                val px = area.x + 3 + col * 8 + x; val py = area.y + 2 + r * 7 + y
                if (inArea(px, py)) setNib(want, px, py, 6)
            }
            assertContentEquals(want, end(own { track(area, Backdrop.None) { rain(rain, 4, x = 3, y = 2) } }), "RAIN reveal 2 at step T")
        }
        // PIECES layer old, radial 3 × 3 about (230, 115), factor 0 → 2048: D_p = ((c − anchor)·f + 2048) >> 12 at f = f1
        fun homes(gw: Int, gh: Int) = (0 until gh).flatMap { j -> (0 until gw).map { i ->
            Rect(area.x + i * area.w / gw, area.y + j * area.h / gh, (i + 1) * area.w / gw - i * area.w / gw, (j + 1) * area.h / gh - j * area.h / gh) } }
        fun inPlace(homes: List<Rect>, d: List<Pair<Int, Int>>, vacate: Int) = over { x, y ->
            val p = homes.indices.reversed().firstOrNull { k -> homes[k].let { r -> x - d[k].first in r.x until r.right && y - d[k].second in r.y until r.bottom } }
            if (p == null) vacate else nib(base, x - d[p].first, y - d[p].second)
        }
        run {
            val hs = homes(3, 3)
            val d = hs.map { r -> ((((r.x + r.right) shr 1) - 230) * 2048 + 2048 shr 12) to ((((r.y + r.bottom) shr 1) - 115) * 2048 + 2048 shr 12) }
            assertContentEquals(inPlace(hs, d, 9), end(own { track(area, Backdrop.None) {
                pieces(Motion.LAYER_OLD, 3, gw = 3, gh = 3, model = Motion.RADIAL, x = 230, y = 115, f0 = 0, f1 = 2048, vacate = 9) } }), "PIECES radial at step T")
        }
        // PIECES layer old, random slices (gw 1, gh 5, dy 0), dx 9, seed 0x1234: rx = H3(seed, p, 0) & 0xFFFF as s16,
        // D_p = ((rx·DX) >> 15, 0) with DX = dx at f = 4096
        run {
            val hs = homes(1, 5)
            val d = hs.indices.map { k -> ((h3(0x1234, k, 0) and 0xFFFF).toShort().toInt() * 9 shr 15) to 0 }
            assertContentEquals(inPlace(hs, d, 2), end(own { track(area, Backdrop.None) {
                pieces(Motion.LAYER_OLD, 3, gh = 5, model = Motion.RANDOM, dx = 9, seed = 0x1234, vacate = 2) } }), "PIECES random slices at step T")
        }
        // PIECES layer new, uniform (one piece), dx 7, dy 3, over backdrop 2: the shadow's area drawn moved by D, clipped
        run {
            val want = over { x, y -> if (inArea(x - 7, y - 3)) nib(shadow, x - 7, y - 3) else 2 }
            assertContentEquals(want, end(own { track(area, Backdrop.Level(2)) { pieces(Motion.LAYER_NEW, 3, dx = 7, dy = 3) } }), "PIECES layer new at step T")
        }
        // PIECES layer new, radial 2 × 2 about (230, 115) under stagger 2, factor 0 → 2048, level 15 → 9: at T every piece's
        // local step is n' (e = 4096) and the level is lvl1; the shadow's pieces in index order through lut(9), over backdrop 2
        run {
            val hs = homes(2, 2)
            val want = over { _, _ -> 2 }
            for (r in hs) {
                val dx = (((r.x + r.right) shr 1) - 230) * 2048 + 2048 shr 12; val dy = (((r.y + r.bottom) shr 1) - 115) * 2048 + 2048 shr 12
                for (y in r.y until r.bottom) for (x in r.x until r.right) if (inArea(x + dx, y + dy)) setNib(want, x + dx, y + dy, nib(shadow, x, y) * 9 / 15)
            }
            assertContentEquals(want, end(own { track(area, Backdrop.Level(2)) {
                pieces(Motion.LAYER_NEW, 6, gw = 2, gh = 2, model = Motion.RADIAL, stagger = 2, x = 230, y = 115, f0 = 0, f1 = 2048, lvl0 = 15, lvl1 = 9, seed = 77) } }),
                "PIECES layer new under stagger and a level ramp at step T")
        }
        // the source window: a record's rows 2..4 drawn at (205, 104), unscaled, over backdrop 0
        run {
            val rLv = ByteArray(40 * 6) { (1 + (it % 40) % 15).toByte() }
            val rec = TextureCache.Image2(40, 6, rLv).encode(); rec.copyInto(c, 2000 * 4)
            val want = over { x, y -> if (x - 205 in 0 until 40 && y - 104 in 0 until 3) rLv[(2 + y - 104) * 40 + x - 205].toInt() else 0 }
            assertContentEquals(want, end(bound { track(area, Backdrop.Level(0)) { draw(Source.record(2000, rec), 205, 104, n = 2, windowFirst = 2, windowRows = 3) } }),
                "a source window at step T")
        }
        // REVEAL of `new` at its corner: at T the whole source shows — the area is the shadow's
        run {
            val want = over { x, y -> nib(shadow, x, y) }
            assertContentEquals(want, end(own { track(area, Backdrop.Level(4)) { reveal(Source.New, Edge.LEFT, n = 3, x = 200, y = 100) } }), "REVEAL of `new` at step T")
        }
    }
}
