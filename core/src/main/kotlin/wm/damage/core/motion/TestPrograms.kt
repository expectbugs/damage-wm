package wm.damage.core.motion

import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.shell.Shell
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.TextureCache

/**
 * Build C1 (spec §3 D3b, decision A.15): the feel session's four test programs, written with the [Motion] DSL — a list
 * slide with ease-out and a bounce table, a popover reveal and cover, Main's recede and advance, a card flip as three
 * cached images (`FIRMWARE.md` §5.1: A3 is three images swapped). Context for the reader: Adam's own glasses on his own
 * firmware fork; these play only when he asks for one by hand (`probe motion=NAME`, ENGINE in force: the ladder's stage
 * 3 on a build that reports DamageCaps bit 7) and in `desktop --motion-check DIR`, which plays them on the simulator and
 * writes a strip per program. Never from the shell's paint.
 *
 * Each program is laid out in the top [CfwModes.STAGE_RESERVE] bytes of the session's cache (the staged-strip reserve):
 * its easing table, its image records and the program record together (the first read range stays tight, §5.6), then
 * the `self` captures after them, 4-byte aligned, so the capture range never meets the first. A write there is another
 * writer's (the transport says so, `CfwTransportBase.sendMotion`), so the shell drops its atlas for that session.
 *
 * [TestProgram.stage] is the frame the desktop tool keyframes before a program plays; on the glasses a program plays
 * over whatever the lens shows (a `self` track takes the live content).
 */
object TestPrograms {
    /** The names `probe motion=NAME` and the tool take, in the tool's order: C1's four, then Main's pan at k = 1, 2, 3
     *  rows each side of the band and over the whole list (build C2, stage DM0). */
    val NAMES: List<String> = listOf("list", "popover", "recede", "flip", "mainpan1", "mainpan2", "mainpan3", "mainpanall")

    /** The transport's `motion` note once a test program's PLAY is acknowledged (build C1's ruling R1.16): the shell
     *  puts END into its belief of both lenses on it ([TestProgram.paintEnd]). */
    const val PLAYED = "test program played: "

    fun playedNote(tp: TestProgram): String = "$PLAYED${tp.name} (${tp.describe()})"

    /** The test program a `motion` note's [detail] says has been played, or null when it says nothing of the kind. */
    fun playedName(detail: String): String? =
        if (!detail.startsWith(PLAYED)) null else detail.removePrefix(PLAYED).substringBefore(' ').takeIf { it in NAMES }

    /** A program laid out for one cache: [writes] are (off4, bytes) in write order, [cap4] the capture offset, [images]
     *  the levels of each image record it writes, by off4. */
    class TestProgram internal constructor(val name: String, val about: String, val program: MotionProgram,
        val writes: List<Pair<Int, ByteArray>>, val cap4: Int, val tag: Int, val stage: Gray8, val cacheSize: Int,
        val images: Map<Int, TextureCache.Image2>) {
        val period: Int get() = Motion.PERIOD_30HZ
        val params: IntArray get() = IntArray(4)
        val slot: Int get() = 0
        /** The byte past the last thing this program puts in the cache (records or captures). */
        val end: Int get() = maxOf(writes.maxOf { (o, b) -> o * 4 + b.size }, cap4 * 4 + program.captureBytes)

        fun play(): ByteArray = Motion.play(program, slot, period, tag, cap4, params)

        /** This program with [tag] in its PLAY (build C1's ruling B-N11: the probe gives each play its own tag, so a
         *  second play of one program never takes the first's END record, and a late notify of the first is never
         *  compared with the second's CRC). */
        fun withTag(tag: Int): TestProgram {
            require(tag in 0..0xFFFF) { "a tag is u16: $tag" }
            return TestProgram(name, about, program, writes, cap4, tag, stage, cacheSize, images)
        }

        /** What the transport sends for it: every record as mode-19 writes of at most [Shell.STAGE_CHUNK] bytes (the
         *  staged strips' size: a multiple of 4, under the atlas's messages), then the PLAY. */
        fun messages(): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            for ((off4, bytes) in writes) {
                var pos = 0
                while (pos < bytes.size) {
                    val n = minOf(Shell.STAGE_CHUNK, bytes.size - pos)
                    out += CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2(off4 + pos / 4, bytes.copyOfRange(pos, pos + n))), cacheSize)
                    pos += n
                }
            }
            out += play()
            return out
        }

        fun describe(): String = "$name: T ${program.T} at period $period, slot $slot, tag 0x%04x, ${writes.sumOf { it.second.size }} B of records at %d..%d, %d B of captures at %d"
            .format(tag, writes.minOf { it.first } * 4, writes.maxOf { (o, b) -> o * 4 + b.size }, program.captureBytes, cap4 * 4)

        /**
         * END(program, P) (`FIRMWARE.md` §5.4) painted onto [pic], one lens's picture (8-bit, a level shown as
         * `level·17`) as it was when the PLAY ran: per track in order its backdrop, then its state at step T, clipped
         * to its area — what the firmware writes into that lens's shadow at the PLAY, from the same resolved segments
         * [Motion.end] makes its §4 ops of. A `self` capture is [pic]'s own area as it was, taken for every track
         * before any track paints (the firmware captures, then writes END). Build C1's ruling R1.16: the shell puts
         * this into its belief when the probe's PLAY is acknowledged.
         */
        fun paintEnd(left: Boolean, pic: Gray8) {
            val segs = program.resolve(params, staticOnly = false)
            val caps = program.tracks.map { t -> if (t.usesSelf) capture(pic, t.area(left)) else null }
            program.tracks.forEachIndexed { i, t ->
                val area = t.area(left)
                when (val b = t.backdrop) {
                    is Motion.Backdrop.Level -> fillLevel(pic, area, b.code)
                    is Motion.Backdrop.Self -> blit(pic, caps[i]!!, area.x, area.y, area, null)
                    is Motion.Backdrop.None -> {}
                }
                // at step T every track has run all its segments: it holds its last non-WAIT segment's final state
                val s = segs[i].lastOrNull { it.verb != Motion.WAIT } ?: return@forEachIndexed
                val own = t.perLens && !left
                val draw = s.verb == Motion.DRAW
                val x = (if (own) s.xR else s.xL) + (if (draw) (if (own) s.dxR else s.dxL) else 0)
                val y = s.y + (if (draw) s.dy else 0)
                val options = if (draw) (s.opt and 0x30) or s.lvl1 else null
                when {
                    s.verb == Motion.FILL -> fillLevel(pic, area, s.kind)
                    s.kind == Motion.KIND_SELF -> blit(pic, caps[i]!!, x, y, area, options)
                    s.kind == Motion.KIND_RECORD -> blit(pic, images[s.src]
                        ?: throw LintError("test program $name: no image levels for the record at off4 ${s.src}"), x, y, area, options)
                    // REVEAL of a level source at rest: its rect, of width dxL on both lenses (ruling R1.20)
                    else -> Rect(x, y, s.dxL, s.dy).intersect(area)?.let { fillLevel(pic, it, s.src) }
                }
            }
        }
    }

    /** [area] of [pic] as levels, row-major. */
    private fun capture(pic: Gray8, area: Rect): TextureCache.Image2 =
        TextureCache.Image2(area.w, area.h, ByteArray(area.w * area.h) { p -> Pack.level(pic[area.x + p % area.w, area.y + p / area.w]).toByte() })

    private fun fillLevel(pic: Gray8, r: Rect, level: Int) = pic.fillRect(r, level * 17)

    /** [img] at ([x0], [y0]) clipped to [clip]; [options] null draws the levels as they are, else §2's v1 LUT (level
     *  bits 0–3, bit 4 transparent tested on the source, bit 5 inverse), as the firmware draws a mode-17 record. */
    private fun blit(pic: Gray8, img: TextureCache.Image2, x0: Int, y0: Int, clip: Rect, options: Int?) {
        val top = (options ?: 15) and 0x0F
        val inverse = options != null && options and 0x20 != 0
        val transparent = options != null && options and 0x10 != 0
        for (yy in maxOf(clip.y, y0, 0) until minOf(clip.bottom, y0 + img.h, pic.h))
            for (xx in maxOf(clip.x, x0, 0) until minOf(clip.right, x0 + img.w, pic.w)) {
                val c = img.levels[(yy - y0) * img.w + (xx - x0)].toInt()
                if (transparent && c == 0) continue
                pic[xx, yy] = (if (options == null) c else ((if (inverse) 15 - c else c) * top) / 15) * 17
            }
    }

    /** [name] laid out for a session whose cache holds [cacheSize] bytes; null for a name that is not one. Refuses
     *  (loudly) a cache too small to hold it in its top [CfwModes.STAGE_RESERVE] bytes. */
    fun build(name: String, cacheSize: Int): TestProgram? = build(name, cacheSize, MainPan.HEIGHT)

    /** [build] with Main's pan laid out for Main at [mainHeight] (the tests build every height). */
    internal fun build(name: String, cacheSize: Int, mainHeight: Int): TestProgram? {
        if (name !in NAMES) return null
        if (cacheSize < CfwModes.STAGE_RESERVE || cacheSize > CfwModes.CACHE2_MAX_RIDERS || cacheSize % 4 != 0)
            throw LintError("no test program for a cache of $cacheSize B (a contract-2 session's 64..256 KiB)")
        val lay = Layout(cacheSize - CfwModes.STAGE_RESERVE, cacheSize)
        val tp = when (name) {
            "list" -> list(lay)
            "popover" -> popover(lay)
            "recede" -> recede(lay)
            "flip" -> flip(lay)
            "mainpanall" -> mainPan(lay, name, null, mainHeight)
            else -> mainPan(lay, name, name.removePrefix("mainpan").toInt(), mainHeight)
        }
        if (tp.end > cacheSize) throw LintError("test program ${tp.name} ends at ${tp.end}, past the $cacheSize B cache")
        return tp
    }

    /** A cursor over the reserve: records go back to back, each 4-byte aligned. */
    private class Layout(start: Int, val cacheSize: Int) {
        private var at = start
        val writes = ArrayList<Pair<Int, ByteArray>>()
        val images = HashMap<Int, TextureCache.Image2>()
        fun put(bytes: ByteArray): Int { val off4 = at / 4; writes += off4 to bytes; at = align(at + bytes.size); return off4 }
        /** An image record put in the layout, its levels kept for [TestProgram.paintEnd]. */
        fun record(img: TextureCache.Image2): Motion.Source.Record {
            val bytes = img.encode()
            val off4 = put(bytes)
            images[off4] = img
            return Motion.Source.record(off4, bytes)
        }
        /** The program's offset: the next free one (it is put last, once built at this offset). */
        val next4: Int get() = at / 4
        /** Puts [p] (built at [next4], its tables already put) and returns the capture offset: the next free one. */
        fun program(p: MotionProgram): Int {
            if (p.at != next4) throw LintError("a test program built at off4 ${p.at}, not at the layout's next free ${next4}")
            for ((o, b) in p.tables) if (writes.none { it.first == o && it.second.contentEquals(b) }) throw LintError("the table at off4 $o was not laid out")
            put(p.bytes)
            return next4
        }
        private fun align(n: Int) = (n + 3) and 3.inv()
    }

    private fun lv(n: Int) = n * 17

    /** A v2 image (`FIRMWARE.md` §4: its record is `[w u16][h u16][RLE]`) of a [w]×[h] drawing. */
    private fun image(w: Int, h: Int, draw: Gray8.() -> Unit): TextureCache.Image2 {
        val g = Gray8(w, h).apply(draw)
        return TextureCache.Image2(w, h, ByteArray(w * h) { Pack.level(g.pix[it].toInt() and 0xFF).toByte() })
    }

    private fun stage(draw: Gray8.() -> Unit) = Gray8(Geometry.PANEL_W, Geometry.PANEL_H).apply(draw)

    // ---------------------------------------------------------------------------------------------------- the list
    private const val ROW_H = 40
    private val LIST = Rect(32, 74, 576, 5 * ROW_H)
    private val WORDS = intArrayOf(300, 220, 380, 260, 340, 180, 400, 240)

    /** One list row at ([x], [y]), [w] wide: a bright word, a dim one after it, a divider under them — distinct per [i]. */
    private fun Gray8.row(x: Int, y: Int, w: Int, i: Int) {
        fillRect(x + 16, y + 14, WORDS[i % WORDS.size], 12, lv(10))
        fillRect(x + 28 + WORDS[i % WORDS.size], y + 14, 60, 12, lv(5))
        fillRect(x + 8, y + ROW_H - 2, w - 16, 1, lv(3))
    }

    /** An overshoot table (§5.4: uploaded, E may leave 0..4096, E[n] = 4096): past the row by about an eighth, back. */
    private val BOUNCE = intArrayOf(900, 2300, 3500, 4300, 4620, 4600, 4420, 4220, 4080, 4030, 4070, 4096)

    private fun list(lay: Layout): TestProgram {
        val tableAt = lay.put(Motion.Ease.Table(0, BOUNCE).record)
        val rows = image(LIST.w, 8 * ROW_H) { for (i in 0 until 8) row(0, i * ROW_H, LIST.w, i) }
        val rec = lay.record(rows)
        val prog = Motion.program(lay.next4, listOf(rec)) {
            track(LIST, Motion.Backdrop.Level(0)) {
                // one row up with ease-out, a pause, one more row with the bounce table
                draw(rec, x = LIST.x, y = LIST.y, dy = -ROW_H, ease = Motion.Ease.easeOut, n = 8)
                wait(6)
                draw(rec, x = LIST.x, y = LIST.y - ROW_H, dy = -ROW_H, ease = Motion.Ease.table(tableAt, BOUNCE), n = 12)
            }
        }
        val cap4 = lay.program(prog)
        return TestProgram("list", "a list slides one row with ease-out, pauses, and slides one more with a bounce table",
            prog, lay.writes.toList(), cap4, 0x0C11, stage { for (i in 0 until 5) row(LIST.x, LIST.y + i * ROW_H, LIST.w, i) }, lay.cacheSize, lay.images.toMap())
    }

    // ---------------------------------------------------------------------------------------------------- the popover
    private val POP = Rect(176, 140, 288, 128)

    private fun popover(lay: Layout): TestProgram {
        val box = image(POP.w, POP.h) {
            fillRect(0, 0, w, h, lv(1))
            outlineRect(0, 0, w, h, lv(12), width = 2)
            fillRect(12, 12, 140, 14, lv(13))
            fillRect(12, 50, 200, 12, lv(9))
            fillRect(12, 84, 160, 12, lv(9))
            outlineRect(6, 42, w - 12, 28, lv(15), width = 2)
        }
        val rec = lay.record(box)
        val prog = Motion.program(lay.next4, listOf(rec)) {
            // the box slides in from the top over the content it covers (the area's own pixels as the backdrop) and holds
            track(POP, Motion.Backdrop.Self) {
                reveal(rec, Motion.Edge.TOP, slide = true, ease = Motion.Ease.easeOut, n = 8, x = POP.x, y = POP.y)
                wait(12)
            }
            // then that content comes back down over it: the cover (END is the content as it was)
            track(POP, Motion.Backdrop.None) {
                wait(20)
                reveal(Motion.Source.self, Motion.Edge.TOP, ease = Motion.Ease.easeOut, n = 8, x = POP.x, y = POP.y)
            }
        }
        val cap4 = lay.program(prog)
        return TestProgram("popover", "a popover slides in from the top over the content, holds, and the content wipes back down over it",
            prog, lay.writes.toList(), cap4, 0x0C12, stage { for (i in 0 until 8) row(LIST.x, LIST.y + i * ROW_H, LIST.w, i) }, lay.cacheSize, lay.images.toMap())
    }

    // ---------------------------------------------------------------------------------------------------- Main
    private val MAIN = Rect(152, 116, 336, 184)
    private const val RECEDE = 8           // px per lens: the left lens's copy left, the right's right (farther, `DESIGN.md` §3.3)

    private fun recede(lay: Layout): TestProgram {
        val prog = Motion.program(lay.next4) {
            track(MAIN, MAIN, Motion.Backdrop.Level(0)) {
                draw(Motion.Source.self, x = MAIN.x, xR = MAIN.x, y = MAIN.y, dx = -RECEDE, dxR = RECEDE, lvl0 = 15, lvl1 = 9,
                    ease = Motion.Ease.easeOut, n = 8)
                wait(10)
                draw(Motion.Source.self, x = MAIN.x - RECEDE, xR = MAIN.x + RECEDE, y = MAIN.y, dx = RECEDE, dxR = -RECEDE, lvl0 = 9, lvl1 = 15,
                    ease = Motion.Ease.easeOut, n = 8)
            }
        }
        val cap4 = lay.program(prog)
        return TestProgram("recede", "Main recedes ($RECEDE px per lens, dimming to level 9), holds, and comes forward again",
            prog, lay.writes.toList(), cap4, 0x0C13, stage {
                outlineRect(176, 132, 288, 64, lv(8), width = 2)
                for (k in 0 until 5) fillRect(200 + k * 50, 146, 36, 36, lv(if (k == 2) 6 else 13))
                fillRect(176, 212, 136, 72, lv(3)); fillRect(188, 228, 90, 10, lv(11)); fillRect(188, 252, 60, 10, lv(7))
                fillRect(328, 212, 136, 72, lv(3)); fillRect(340, 228, 100, 10, lv(11)); fillRect(340, 252, 70, 10, lv(7))
            }, lay.cacheSize, lay.images.toMap())
    }

    // ---------------------------------------------------------------------------------------------------- Main's pan
    /**
     * Build C2, stage DM0 (`C2-decisions.md` C; spec §6.4, §9 item 1): one notch down on Main, from the cursor at
     * [MainPan.CURSOR] to the next row, over [MainPan.T] steps at P = 34 — to read field 34 (the step's drawing time)
     * by area on the glasses, and for Adam's first look at Main's own motion. [k] rows each side of the band (null:
     * every row Main shows at [height]). Main's pictures are [MainPan]'s.
     *
     * Three tracks, all per lens: the rows above the band and the rows below it, each one DRAW of the strip (rows 0..7
     * in row form at the pitch) panned one pitch up with ease-out, in the list plane; and the band, where the leaving
     * row's band form goes back to the list plane and dims over [MainPan.SWAP] steps, then the entering row's comes
     * forward from there to the band's plane and brightens. No size change: every DRAW is a record at its own size.
     *
     * Why the strip is two tracks and not one: the band is two pitches tall and holds one row, so the rows below it sit
     * one pitch lower than a stacked strip drawn for the rows above would put them; the strip record is drawn twice,
     * at y one pitch apart, each clipped to its side. The rows pass behind the band's edges (the band is the nearer
     * plane): the leaving row's row form comes out above it, the entering row's goes in below it.
     */
    private fun mainPan(lay: Layout, name: String, k: Int?, height: Int): TestProgram {
        val m = MainPan.at(height)
        val l = m.layout
        val kA = k ?: l.rowsAbove
        val kB = k ?: l.rowsBelow
        if (kA > l.rowsAbove || kB > l.rowsBelow)
            throw LintError("test program $name: $k rows each side of the band, but Main at height $height shows ${l.rowsAbove} above and ${l.rowsBelow} below")
        val c = MainPan.CURSOR
        // where Main's list wraps, a row past either end is the other end's — the strip is rows 0..7 once, so none may be
        // needed (where it does not wrap, a slot past an end is blank, as the strip's backdrop leaves it)
        if (m.wraps && (c - kA < 0 || c + 1 + kB >= m.rows))
            throw LintError("test program $name: Main wraps at height $height, and rows ${c - kA}..${c + 1 + kB} pass the ends of its ${m.rows}")
        val pitch = l.rowH
        val x = l.content.x
        val shift = MainPan.LIST_D - MainPan.BAND_D
        val strip = lay.record(m.strip)
        val leave = lay.record(m.band(c))
        val enter = lay.record(m.band(c + 1))
        val prog = Motion.program(lay.next4, listOf(strip, leave, enter)) {
            // the rows above the band: the strip with row c at the band's top, panned up one pitch
            track(Rect(x - MainPan.LIST_D, l.lens.y - kA * pitch, m.rowW, kA * pitch), Rect(x + MainPan.LIST_D, l.lens.y - kA * pitch, m.rowW, kA * pitch), Motion.Backdrop.Level(0)) {
                draw(strip, x = x - MainPan.LIST_D, xR = x + MainPan.LIST_D, y = l.lens.y - c * pitch, dy = -pitch, ease = Motion.Ease.easeOut, n = MainPan.T)
            }
            // the rows below it: the strip with row c + 1 at the band's bottom, panned up one pitch
            track(Rect(x - MainPan.LIST_D, l.lens.bottom, m.rowW, kB * pitch), Rect(x + MainPan.LIST_D, l.lens.bottom, m.rowW, kB * pitch), Motion.Backdrop.Level(0)) {
                draw(strip, x = x - MainPan.LIST_D, xR = x + MainPan.LIST_D, y = l.lens.bottom - (c + 1) * pitch, dy = -pitch, ease = Motion.Ease.easeOut, n = MainPan.T)
            }
            // the band: its area covers both planes' x on each lens
            track(Rect(x - MainPan.LIST_D, l.lens.y, m.rowW + shift, l.lens.h), Rect(x + MainPan.BAND_D, l.lens.y, m.rowW + shift, l.lens.h), Motion.Backdrop.Level(0)) {
                draw(leave, x = x - MainPan.BAND_D, xR = x + MainPan.BAND_D, y = l.lens.y, dx = -shift, dxR = shift,
                    lvl0 = 15, lvl1 = MainPan.LVL_BACK, ease = Motion.Ease.easeOut, n = MainPan.SWAP)
                draw(enter, x = x - MainPan.LIST_D, xR = x + MainPan.LIST_D, y = l.lens.y, dx = shift, dxR = -shift,
                    lvl0 = MainPan.LVL_BACK, lvl1 = 15, ease = Motion.Ease.easeOut, n = MainPan.T - MainPan.SWAP)
            }
        }
        val cap4 = lay.program(prog)
        val rows = if (k == null) "every row Main shows ($kA above, $kB below)" else "$k row(s) each side"
        return TestProgram(name, "Main at height $height, one notch down (row $c to ${c + 1}): the strip over the band and $rows " +
            "pans one pitch with ease-out; the band's row goes back and dims, the next comes forward and brightens; no size change (text drawn as boxes)",
            prog, lay.writes.toList(), cap4, 0x0C20 + NAMES.indexOf(name) - 3, m.picture(c), lay.cacheSize, lay.images.toMap())
    }

    // ---------------------------------------------------------------------------------------------------- the card
    private val TABLE = Rect(256, 160, 128, 176)
    private const val CARD_W = 96
    private const val CARD_H = 144
    private const val EDGE_W = 16

    private fun Gray8.cardBack(x: Int, y: Int) {
        fillRect(x, y, CARD_W, CARD_H, lv(4))
        outlineRect(x, y, CARD_W, CARD_H, lv(12), width = 2)
        outlineRect(x + 8, y + 8, CARD_W - 16, CARD_H - 16, lv(8))
        for (j in 0 until (CARD_H - 24) / 8) for (i in 0 until (CARD_W - 24) / 8)
            fillRect(x + 12 + i * 8, y + 12 + j * 8, 8, 8, lv(if ((i + j) % 2 == 0) 7 else 5))
    }

    private fun flip(lay: Layout): TestProgram {
        val back = image(CARD_W, CARD_H) { cardBack(0, 0) }
        val edge = image(EDGE_W, CARD_H) { fillRect(0, 0, w, h, lv(6)); outlineRect(0, 0, w, h, lv(12), width = 2) }
        val face = image(CARD_W, CARD_H) {
            fillRect(0, 0, w, h, lv(13))
            outlineRect(0, 0, w, h, lv(6), width = 2)
            fillPolygon(intArrayOf(48, 72, 48, 24), intArrayOf(40, 72, 104, 72), lv(3))
            fillRect(8, 8, 10, 14, lv(3)); fillRect(78, 122, 10, 14, lv(3))
        }
        val rb = lay.record(back)
        val re = lay.record(edge)
        val rf = lay.record(face)
        val cx = TABLE.x + (TABLE.w - CARD_W) / 2
        val cy = TABLE.y + (TABLE.h - CARD_H) / 2
        val prog = Motion.program(lay.next4, listOf(rb, re, rf)) {
            track(TABLE, Motion.Backdrop.Level(1)) {
                draw(rb, x = cx, y = cy, n = 3)
                draw(re, x = cx + (CARD_W - EDGE_W) / 2, y = cy, n = 2)
                draw(rf, x = cx, y = cy, n = 5)
            }
        }
        val cap4 = lay.program(prog)
        return TestProgram("flip", "a card turns over: its back, its edge, its face — three cached images swapped (A3)",
            prog, lay.writes.toList(), cap4, 0x0C14, stage { fillRect(TABLE, lv(1)); cardBack(cx, cy) }, lay.cacheSize, lay.images.toMap())
    }
}
