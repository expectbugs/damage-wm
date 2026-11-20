package wm.damage.core.motion

import wm.damage.core.comp.GlyphAtlas
import wm.damage.core.comp.TextDraw
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Pack
import wm.damage.core.text.FontSpec
import wm.damage.core.wire.TextureCache

/**
 * Build C3, stage DM-T: Encode's cell lists from a screen's text runs (`FIRMWARE.md` §7.6, §7.11; the DM spec §3).
 * Context for the reader: Adam's own glasses on his own firmware fork; display-rendering work.
 *
 * A screen's text runs are what [wm.damage.core.comp.CachedText] records while the screen is painted once through its
 * relay into a scratch: each run a string of codes the atlas packs, its pen, its face and its level. Every glyph of a run
 * becomes one cell at the box the atlas's own draw puts it in (the pen advanced by each glyph's width and the face's
 * kerning, as `CachedText.blit` does); cells are grouped by face and by the plane's disparity (one track's offsets serve
 * one list), and each group cut into lists of at most [MAX_CELLS]. A list's alphabet is its face's packed codes among
 * A–Z a–z 0–9 — every one a real glyph in the one font its cells use (§7.11); fewer than [NOISE_MIN_CODES] of them and the
 * list has no noise (the alphabet is then never drawn). Every box covers its glyph and the face's widest alphabet glyph,
 * so a cell's fill repaints every pixel the cell drew at the step before (§7.2's fold condition over the still dim).
 *
 * Pure functions: a screen that does not fit (more than [MAX_LISTS] lists, the lists past the room they are given) is a
 * value the caller reads ([Screen.NoFit], [Laid] null with its reason), never a throw its loop sees.
 */
object CellLists {
    /** §7.6: a cell list holds 1..512 cells. */
    const val MAX_CELLS = 512
    /** A half holds its first track (the dim, or the arriving picture) and at most seven cell tracks (§5.4: 8 tracks). */
    const val MAX_LISTS = 7
    /** §7.6: an alphabet holds 1..224 codes. */
    const val MAX_ALPHABET = 224
    /** The DM spec §3: a face with fewer real alphabet codes than this draws no noise. */
    const val NOISE_MIN_CODES = 4
    /** The codes an alphabet may take (the spec: A–Z a–z 0–9). */
    val ALNUM: List<Int> = (('A'..'Z') + ('a'..'z') + ('0'..'9')).map { it.code }

    /** One face as the atlas holds it: its font table, its alphabet (its packed codes among [ALNUM]), the width of its
     *  widest alphabet glyph and its box height (every glyph of a face shares it). */
    class Face(val spec: FontSpec, val font: Motion.Font, val alphabet: IntArray, val widestAlpha: Int, val lineH: Int)

    /** One glyph of a run: its box corner on the composed picture (before the lens's disparity), its code, its width and
     *  its level (0..15). */
    class Glyph(val x: Int, val y: Int, val code: Int, val w: Int, val level: Int)

    /** The glyphs of one face at one disparity (a list's track carries its offsets: −d on the left lens, +d on the right). */
    class Group(val face: Face, val disparity: Int, val glyphs: List<Glyph>)

    sealed class Screen {
        /** The screen's lists: each a group's glyphs, at most [MAX_CELLS]. */
        class Cells(val lists: List<Group>) : Screen() {
            val cells: Int get() = lists.sumOf { it.glyphs.size }
        }
        class NoFit(val why: String) : Screen()
    }

    /** [face] as [entry] holds it: a v2 table (224 entries), the glyph records of every packed code. Null for a v1 face. */
    fun face(spec: FontSpec, entry: GlyphAtlas.Entry): Face? {
        val f = entry.font
        if (!f.v2 || f.glyphOffsets.size != TextureCache.LAST_CHAR2 - TextureCache.FIRST_CHAR + 1) return null
        val glyphs = HashMap<Int, Motion.Glyph>()
        for (i in f.glyphOffsets.indices) {
            val ch = (TextureCache.FIRST_CHAR + i).toChar()
            if (!entry.packs(ch) || ch == ' ') continue
            val img = entry.images[i]
            glyphs.getOrPut(f.glyphOffsets[i]) { Motion.Glyph(f.glyphOffsets[i] / 4, img.w, img.h, img.encode().size - 2) }
        }
        val font = Motion.Font(f.tableOffset / 4, IntArray(f.glyphOffsets.size) { f.glyphOffsets[it] / 4 }, glyphs.values.toList())
        val alphabet = ALNUM.filter { entry.packs(it.toChar()) }.toIntArray()
        val widest = alphabet.maxOfOrNull { entry.images[it - TextureCache.FIRST_CHAR].w } ?: 0
        return Face(spec, font, alphabet, widest, entry.lineH)
    }

    /**
     * The cells of a screen whose text runs are [runs]: [entryOf] the atlas's entry of a face the glasses hold (null:
     * none — its runs give no cells), [kernOf] its kerning, [disparityOf] the plane disparity of a run's rect; only glyphs
     * whose box meets [area] count. More than [MAX_LISTS] lists: [Screen.NoFit].
     */
    fun screen(runs: List<TextDraw>, entryOf: (FontSpec) -> GlyphAtlas.Entry?, kernOf: (FontSpec) -> (Char, Char) -> Int,
               disparityOf: (Rect) -> Int, area: Rect): Screen {
        val faces = HashMap<FontSpec, Face?>()
        val groups = LinkedHashMap<Pair<FontSpec, Int>, ArrayList<Glyph>>()
        for (d in runs) {
            val entry = entryOf(d.spec) ?: continue
            val face = faces.getOrPut(d.spec) { face(d.spec, entry) } ?: continue
            val kern = kernOf(d.spec)
            val level = Pack.level(d.level)
            val out = groups.getOrPut(d.spec to disparityOf(d.rect)) { ArrayList() }
            var px = d.x
            var prev: Char? = null
            for (ch in d.text) {
                prev?.let { px += kern(it, ch) }        // `CachedText.blit`'s pen: the adjust, then the glyph's width
                prev = ch
                val i = ch.code - TextureCache.FIRST_CHAR
                if (i !in entry.images.indices) break
                val w = entry.images[i].w
                if (ch != ' ' && entry.packs(ch) && Rect(px, d.y, maxOf(w, face.widestAlpha), face.lineH).overlaps(area))
                    out += Glyph(px, d.y, ch.code, w, level)
                px += w
            }
        }
        val lists = ArrayList<Group>()
        for ((key, gs) in groups) {
            if (gs.isEmpty()) continue
            val face = faces[key.first]!!
            for (from in gs.indices step MAX_CELLS) lists += Group(face, key.second, gs.subList(from, minOf(gs.size, from + MAX_CELLS)).toList())
        }
        if (lists.size > MAX_LISTS) return Screen.NoFit("its text needs ${lists.size} cell lists (${groups.size} face and plane groups, " +
            "${lists.sumOf { it.glyphs.size }} cells) — a half holds $MAX_LISTS")
        return Screen.Cells(lists)
    }

    /** A half's lists laid out in the cache: [table] (its lists in [screen]'s order; null for a screen with no text the
     *  glasses hold — the half then draws no cells), the lists' disparities in that order, and the bytes from [at4] — the
     *  table, each list, each face's alphabet once. */
    class Laid(val table: Motion.CellTable?, val disparities: List<Int>, val at4: Int, val blob: ByteArray) {
        val end4: Int get() = at4 + blob.size / 4
    }

    /**
     * [screen]'s lists as a half draws them, laid out from [at4] in at most [room] bytes ([Laid] null and [why] said when
     * they do not fit). [out]: the leaving screen's cells — each glyph shown, then noise, then nothing (new 0: its box keeps
     * the fill over the dim); else the arriving screen's — masked (32), noise, then the backdrop (32 draws nothing and is
     * not filled once settled). Every box filled with 0. [steps] sets the noise: ⌊steps / 3⌋, at least 1 (n − N ≥ 1, §7.6).
     */
    fun lay(screen: Screen.Cells, at4: Int, room: Int, steps: Int, out: Boolean, why: (String) -> Unit = {}): Laid? {
        if (screen.lists.isEmpty()) return Laid(null, emptyList(), at4, ByteArray(0))
        val noise = if (steps >= 2) maxOf(1, steps / 3) else 0
        var off = at4 + words(4 + 2 * screen.lists.size)
        val alphabets = LinkedHashMap<FontSpec, Motion.Alphabet>()
        for (g in screen.lists) alphabets.getOrPut(g.face.spec) {
            Motion.Alphabet(off, if (g.face.alphabet.isEmpty()) intArrayOf(32) else g.face.alphabet).also { off += words(it.bytes.size) }
        }
        val lists = screen.lists.map { g ->
            val a = alphabets.getValue(g.face.spec)
            val cells = g.glyphs.map { c ->
                Motion.Cell(c.x, c.y, maxOf(c.w, g.face.widestAlpha), g.face.lineH, g.face.font,
                    old = if (out) c.code else 32, new = if (out) 0 else 32, fill = 0, level = c.level)
            }
            val n = if (g.face.alphabet.size >= NOISE_MIN_CODES) noise else 0
            Motion.CellList(off, a, n, cells).also { off += words(it.bytes.size) }
        }
        val size = (off - at4) * 4
        if (size > room) { why("its lists are $size B, past the $room B they may take"); return null }
        val table = Motion.CellTable(at4, lists)
        val blob = ByteArray(size)
        table.bytes.copyInto(blob, 0)
        for (a in alphabets.values) a.bytes.copyInto(blob, (a.off4 - at4) * 4)
        for (l in lists) l.bytes.copyInto(blob, (l.off4 - at4) * 4)
        return Laid(table, screen.lists.map { it.disparity }, at4, blob)
    }

    /** The bytes [screen]'s lists take as [lay] writes them (out or in alike: only codes differ), 0 for a screen with no
     *  cells or one that does not fit — what the shell places in the stage reserve before the half is built. */
    fun bytes(screen: Screen?): Int = (screen as? Screen.Cells)?.let { lay(it, 0, Int.MAX_VALUE, 3, out = true)?.blob?.size } ?: 0

    private fun words(bytes: Int) = (bytes + 3) / 4
}
