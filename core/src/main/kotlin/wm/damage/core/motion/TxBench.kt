package wm.damage.core.motion

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.text.FontSpec
import wm.damage.core.wire.TextureCache

/**
 * C3 stage TXB (`private/c3-design/TX-spec.md` §1.2): the probe `txbench`'s six test programs — what drawing a row's text
 * costs on the glasses, read from field 9 of RIGHT's notify 114 (the instance's longest step, µs; the transport's `motion`
 * note says it as `stepUs`). Context for the reader: Adam's own glasses on his own firmware fork; display-rendering work.
 * Every program is drawn at level 0 over an area the mirror holds at level 0, so nothing on the lens changes.
 *
 * The records come in two phases laid from the same offset, each part 4-byte aligned, so the room holds the larger of
 * the two, not their sum (his room is about 38 KB, `C3-TXB-built.md`). Phase A, for B0–B3: per face its private glyph
 * copies (the atlas's images, a code the cells use naming its own copy, every other entry the face's first copy, never
 * read) and its 224-entry table — C3 DM-2b's private faces, so every read stays inside the probe's own bytes; one
 * alphabet (32: noise 0 draws none); the three cell lists — the cells inside [AREA] (fill none), the same cells with
 * fill 0, one cell of code 32 — and their table; B0–B3. Phase B, for B4, written over phase A once B3 has played: the
 * cells' ink as one v2 image record, and B4.
 *
 * Each program: version 2, one track over [AREA] with backdrop level 0 (`FIRMWARE.md` §5.5's walk refuses a lone `none`
 * backdrop, 2 — the spec's "backdrop none" is not playable; the level-0 fill is the same work in all six, so it falls out
 * of every difference), T = [T] at P = [PERIOD], lvl0 = lvl1 = 0 (a LUT of level 0: every lit run is written, §7.6):
 * B0 the one cell (the intercept); B1 the cells moved wholly below the area; B1a wholly above it; B2 inside it; B3 inside
 * with fill 0; B4 the image record DRAWn at the area's corner (a version-2 instance runs the fast path, §7.12).
 */
object TxBench {
    /** §1.2's programs, in the order they play. */
    val NAMES: List<String> = listOf("B0", "B1", "B1a", "B2", "B3", "B4")
    /** Each program plays this many times. */
    const val PLAYS = 5
    const val T = 8
    /** The largest period (128 ticks, 125 ms a step): no fold at any step under 125 ms (§1.2). */
    const val PERIOD = 128
    /** The notice's and a transition's out half's slot (`CLAUDE.md`'s four slots): neither may be up while it plays. */
    const val SLOT = 2
    /** A cell list's most (§7.6): the cells stop here if the area is not full first (C3 TXBF: one layer). */
    const val CELLS = 512
    /** The track's area: the rows below 352, full width (§1.2). */
    val AREA = Rect(0, 352, Geometry.PANEL_W, Geometry.PANEL_H - 352)
    /** Each program's y offset: B1 one area's height down (every cell's top at or past the area's bottom), B1a one up. */
    private val DY = intArrayOf(0, AREA.h, -AREA.h, 0, 0, 0)

    /** One face's private copies: its codes ascending, each with its v1 glyph record `[w][h][RLE]` and levels; [spec] the
     *  face it copies (for the start note; null where a test builds one by hand). */
    class Face(val codes: IntArray, val glyphs: List<TextureCache.Image>, val spec: FontSpec? = null)

    /** One cell: its box corner on the panel (inside [AREA]), its face (an index into the faces), its code and level. */
    class Cell(val x: Int, val y: Int, val face: Int, val code: Int, val level: Int)

    /** The index of the first program of phase B (B4): programs before it read phase A's records. */
    const val PHASE_B = 5

    /** The laid records: [phases] (A, B), each its writes (offset, bytes) in write order from [at]; the six [programs]
     *  in [NAMES]' order. */
    class Bench(val at: Int, val phases: List<List<Pair<Int, ByteArray>>>, val programs: List<MotionProgram>, val cells: Int, val faces: Int) {
        /** C3 TXBF (GI 4): what the cells came from — the faces used, of how many the list draws, the characters skipped
         *  for a face the atlas has no entry for — set by the survey's caller ([wm.damage.core.shell.TxBenchRun.bench]),
         *  said in the start note; empty where no list was surveyed. */
        var source = ""
        /** Phase [k]'s end; [end] the larger. */
        fun end(k: Int): Int = phases[k].maxOf { (o, b) -> o + b.size }
        val end: Int get() = phases.indices.maxOf { end(it) }
        /** Phase [k]'s bytes. */
        fun bytes(k: Int): Int = phases[k].sumOf { it.second.size }
        /** Phase [k]'s writes in place from [at] to its end, the alignment gaps zero: what goes into the cache. */
        fun blob(k: Int): ByteArray = ByteArray(end(k) - at).also { b -> for ((o, w) in phases[k]) w.copyInto(b, o - at) }
        /** Program [i]'s PLAY with [tag] (the budget checked, `Motion.play`). */
        fun play(i: Int, tag: Int): ByteArray = Motion.play(programs[i], SLOT, PERIOD, tag, 0, IntArray(4))
    }

    /**
     * The probe's faces and cells from a list's packed glyphs in paint order ([glyphs]: `CellLists.glyphs` of its rows'
     * runs; [images] each face's atlas images by code − 32): taken in turn, again from the first once all are used, each at
     * its own size, set like text across [AREA] (a line one tallest glyph high) until the next glyph would not fit below
     * the last line, or [CELLS]. One layer (C3 TXBF, reader a's finding 1): no two boxes meet, so B2 draws exactly the ink
     * of B4's one opaque area and R_pic = (B2 − B0)/(B4 − B0) compares like with like; X_off and X_text divide by the
     * run's own N (the start note says it). Refused: no glyph, or one larger than the area.
     */
    fun cells(glyphs: List<Pair<FontSpec, CellLists.Glyph>>, images: (FontSpec) -> List<TextureCache.Image>): Pair<List<Face>, List<Cell>> {
        if (glyphs.isEmpty()) throw LintError("the list draws no text the atlas holds")
        val specs = glyphs.map { it.first }.distinct()
        val img = { s: FontSpec, code: Int -> images(s)[code - TextureCache.FIRST_CHAR] }
        val pitch = glyphs.maxOf { (s, g) -> img(s, g.code).h }
        glyphs.firstOrNull { (s, g) -> img(s, g.code).let { it.w > AREA.w || it.h > AREA.h } }?.let { throw LintError("a glyph of ${it.second.code} is larger than the area $AREA") }
        val out = ArrayList<Cell>(CELLS)
        var x = AREA.x; var y = AREA.y
        fill@ while (out.size < CELLS) for ((s, g) in glyphs) {
            if (out.size == CELLS) break
            val im = img(s, g.code)
            if (x + im.w > AREA.right) { x = AREA.x; y += pitch }
            if (y + im.h > AREA.bottom) break@fill        // the area is full: one layer
            out += Cell(x, y, specs.indexOf(s), g.code, g.level)
            x += im.w
        }
        // only the faces the cells reach (one layer may end before a later face's first glyph): a face with no cells
        // would have no copy for its table's unused entries
        val used = specs.indices.filter { i -> out.any { it.face == i } }
        val faces = used.map { i ->
            val codes = out.filter { it.face == i }.map { it.code }.distinct().sorted().toIntArray()
            Face(codes, codes.map { img(specs[i], it) }, specs[i])
        }
        return faces to out.map { c -> Cell(c.x, c.y, used.indexOf(c.face), c.code, c.level) }
    }

    /** The cells' ink as the glasses draw them lit, in list order (a glyph's 0 pixels never drawn; a level-L cell draws a
     *  source level c as ⌊c·L / 15⌋, `cfw_texture_make_lut`): B4's picture, [AREA]'s size. */
    fun picture(faces: List<Face>, cells: List<Cell>): TextureCache.Image2 {
        val out = ByteArray(AREA.w * AREA.h)
        for (c in cells) {
            val f = faces[c.face]
            val g = f.glyphs[f.codes.indexOf(c.code)]
            for (yy in 0 until g.h) for (xx in 0 until g.w) {
                val v = g.levels[yy * g.w + xx].toInt()
                val px = c.x + xx - AREA.x; val py = c.y + yy - AREA.y
                if (v != 0 && px in 0 until AREA.w && py in 0 until AREA.h) out[py * AREA.w + px] = (v * c.level / 15).toByte()
            }
        }
        return TextureCache.Image2(AREA.w, AREA.h, out)
    }

    /** [faces], [cells] and [image] laid from byte [at] in two phases (see the class), refused where either runs past
     *  [end]: the records and programs. */
    fun lay(faces: List<Face>, cells: List<Cell>, image: TextureCache.Image2, at: Int, end: Int, fx: Motion.Effects): Bench {
        if (at % 4 != 0) throw LintError("the probe's records at $at are not 4-byte aligned")
        if (cells.size !in 1..CELLS || faces.isEmpty()) throw LintError("the probe lays 1..$CELLS cells in at least one face, not ${cells.size} in ${faces.size}")
        var writes = ArrayList<Pair<Int, ByteArray>>()
        val phaseA = writes
        var off = at
        fun put(b: ByteArray) { writes += off to b; off = (off + b.size + 3) and 3.inv() }
        val fonts = faces.map { f ->
            val own = f.glyphs.map { im -> im.encode().let { rec -> Motion.Glyph.of(off / 4, rec).also { put(rec) } } }
            val byCode = f.codes.indices.associate { f.codes[it] to own[it] }
            Motion.Font(off / 4, IntArray(224) { (byCode[it + TextureCache.FIRST_CHAR] ?: own[0]).off4 }, own).also { put(it.bytes) }
        }
        val alphabet = Motion.Alphabet(off / 4, intArrayOf(32)).also { put(it.bytes) }
        fun list(cs: List<Motion.Cell>) = Motion.CellList(off / 4, alphabet, 0, cs).also { put(it.bytes) }
        fun cellsWith(fill: Int) = cells.map { c ->
            val font = fonts[c.face]
            val g = font.glyph(c.code) ?: throw LintError("code ${c.code} names no glyph")
            Motion.Cell(c.x, c.y, g.w, g.h, font, c.code, c.code, fill, c.level)
        }
        val lists = listOf(list(cellsWith(Motion.FILL_NONE)), list(cellsWith(0)), list(listOf(Motion.Cell(AREA.x, AREA.y, 0, 0, fonts[0], 32, 32))))
        val table = Motion.CellTable(off / 4, lists).also { put(it.bytes) }
        val programs = ArrayList<MotionProgram>()
        for (i in 0 until PHASE_B) programs += program(i, off / 4, table, null, fx).also { put(it.bytes) }
        writes = ArrayList(); off = at
        val rec = image.encode().let { b -> Motion.Source.record(off / 4, b).also { put(b) } }
        for (i in PHASE_B until NAMES.size) programs += program(i, off / 4, table, rec, fx).also { put(it.bytes) }
        val bench = Bench(at, listOf(phaseA, writes), programs, cells.size, faces.size)
        for (k in bench.phases.indices) if (bench.end(k) > end)
            throw LintError("the probe's phase ${"AB"[k]} records take ${bench.end(k) - at} B from $at, past the room's end $end (${end - at} B)")
        return bench
    }

    /** Program [i] of [NAMES] at [at4] over [table] (lists: 0 the cells, 1 the cells with fill 0, 2 the one cell) and
     *  [image] (B4's, phase B) — through the Motion DSL alone. */
    fun program(i: Int, at4: Int, table: Motion.CellTable, image: Motion.Source.Record?, fx: Motion.Effects): MotionProgram =
        Motion.program2(at4, fx, records = listOfNotNull(image.takeIf { NAMES[i] == "B4" })) {
            track(AREA, Motion.Backdrop.Level(0)) {
                when (NAMES[i]) {
                    "B4" -> draw(image ?: throw LintError("B4 needs its image record"), x = AREA.x, y = AREA.y, lvl0 = 0, lvl1 = 0, n = T)
                    else -> cells(table, index = when (NAMES[i]) { "B0" -> 2; "B3" -> 1; else -> 0 }, n = T, y = DY[i], lvl0 = 0, lvl1 = 0)
                }
            }
        }
}
