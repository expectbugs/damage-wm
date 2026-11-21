package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.motion.BoxFace
import wm.damage.core.motion.Motion
import wm.damage.core.motion.MotionProgram
import wm.damage.core.motion.Wheel
import wm.damage.core.motion.WheelCheck
import wm.damage.core.shell.MainStaging
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * C3 stage DW-0 (`private/c3-design/C3-DW-spec.md` §2, §7.1; decisions BD): the switcher's wheel as the glasses draw it,
 * pinned offline on the simulator's model (modeled). Context for the reader: Adam's own glasses on his own firmware fork;
 * nothing here contacts a device or binds anything. The stage-4 bytes are `WheelStage4Test`'s.
 *
 * Faces: [FakeText] (the design's band of 88 and neighbour box of 20), [BoxFace] (a band of 98, a box of 26),
 * [ScalingText] (a band of 102, a box of 30) — the default face and two larger chrome faces — and [OddFace], whose 25 rows
 * of neighbour ink put the row below on an odd row as Adam's own face does (the desktop's Clear Sans: 239 at 352).
 */
class WheelTest {
    /** FakeText with a neighbour face of 25 rows of ink (17 + 8, as the desktop's Clear Sans at 17 px). */
    private class OddFace : TextRasterizer {
        private val f = FakeText()
        override fun measure(text: String, font: FontSpec) = f.measure(text, font)
        override fun metrics(font: FontSpec) = if (font.sizePx == 17) FontMetrics(17, 8, 27) else f.metrics(font)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) = f.draw(surface, x, y, text, font, level)
        override fun covers(text: String, font: FontSpec) = true
    }
    private val faces: List<Pair<String, TextRasterizer>> = listOf("FakeText" to FakeText(), "BoxFace" to BoxFace, "ScalingText" to ScalingText(), "OddFace" to OddFace())
    /** Windows carrying the dirty tick in the END pins (the switcher's entries 1, 3 and 6). */
    private val dirty = setOf(0, 2, 5)
    private val ds = listOf(0, 4, 8, 16)
    private val cache = WheelCheck.CACHE

    private fun level(b: ByteArray, x: Int, y: Int): Int {
        val v = b[y * (Geometry.PANEL_W / 2) + (x shr 1)].toInt() and 0xFF
        return if (x and 1 == 0) v shr 4 else v and 0x0F
    }

    /** Pixels of the 4-bit [nibbles] inside [area] whose level is not [want]'s. */
    private fun differ(nibbles: ByteArray, want: Gray8, area: Rect): Int {
        var n = 0
        for (y in area.y until area.bottom) for (x in area.x until area.right) if (level(nibbles, x, y) != Pack.level(want[x, y])) n++
        return n
    }

    /**
     * END = the switcher's paint, and the start pose = its paint before: for both programs at every cursor, n = 3..10, d in
     * {0, 4, 8, 16}, the four faces, three windows dirty, both lenses — END drawn on the model ([Motion.endOnModel]) over the lens's picture at
     * c is the compositor's per-lens truth of the switcher at c' over the area, pixel for pixel; the start pose (every
     * track's first segment at j = 0, [Wheel.pictureOnModel]) is its truth at c. Every notch's program passes what a SET
     * and a COMMIT check offline ([Motion.checkBound], [Motion.commit]: bound, the step budget).
     */
    @Test
    fun theEndOfEveryNotchIsTheSwitchersPaintAndItsStartThePaintBefore_DW0() {
        var ends = 0
        for ((fname, face) in faces) for (n in 3..10) for (d in ds) {
            val rig = WheelCheck.Rig(face, 352, n, d, dirty = dirty)
            val block = rig.block()
            val c4 = block.cache(cache)
            for (c in 0 until n) for (step in intArrayOf(1, -1)) {
                val prog = block.program(step)
                val p = block.params(step, c)
                val to = (c + step).mod(n)
                Motion.checkBound(prog)
                Motion.commit(prog, 0, 1, 2, to, p)
                for (left in listOf(true, false)) {
                    val what = "$fname n $n d $d step $step $c→$to ${if (left) "left" else "right"}"
                    val base = rig.panel(c, left)
                    val end = Motion.endOnModel(prog, p, c4, base, base, left)
                    assertEquals(0, differ(end, rig.truth(to, left), block.geo.area(left)), "$what: px where END is not the switcher's paint at $to")
                    val start = Wheel.pictureOnModel(prog, p, c4, base, left, 0)
                    assertEquals(0, differ(start, rig.truth(c, left), block.geo.area(left)), "$what: px where the start pose is not the switcher's paint at $c")
                    ends++
                }
            }
        }
        println("WheelTest END and start: $ends lens-notches, 0 px off")
    }

    /** `Switcher` = None: one step, and that step is the switcher's paint at the new cursor. */
    @Test
    fun noneLandsTheDetentInOneStep_DW0() {
        for ((fname, face) in faces) for (n in listOf(3, 9)) for (d in listOf(0, 8)) {
            val rig = WheelCheck.Rig(face, 352, n, d, dirty = dirty)
            val block = rig.block(none = true)
            assertEquals(1, block.t, "None: T 1")
            assertEquals(rig.block().progSlot, block.progSlot, "None's programs sit in the drum's slot")
            val c4 = block.cache(cache)
            for (c in 0 until n) for (step in intArrayOf(1, -1)) {
                val prog = block.program(step)
                assertEquals(1, prog.T)
                val p = block.params(step, c)
                for (left in listOf(true, false)) {
                    val end = Motion.endOnModel(prog, p, c4, rig.panel(c, left), rig.panel(c, left), left)
                    assertEquals(0, differ(end, rig.truth((c + step).mod(n), left), block.geo.area(left)), "$fname None n $n d $d step $step from $c left=$left")
                }
            }
        }
    }

    /** A track's drawn rect at instance step [k] on one lens, from its segments after substitution (§5.4's state rule and
     *  eased values, §6.6's and §7.5's height, window and anchor): its centre (x, y). */
    private fun centre(segs: List<MotionProgram.Resolved>, k: Int, left: Boolean): Pair<Double, Double> {
        var before = 0
        var s = segs.last(); var j = s.steps
        for (sg in segs) { if (k <= before + sg.steps) { s = sg; j = k - before; break }; before += sg.steps }
        val e = Motion.easeE(s.ease, s.steps)[j]
        val x = Motion.eased(if (left) s.xL else s.xR, if (left) s.dxL else s.dxR, e)
        val y = Motion.eased(s.y, s.dy, e)
        val rec = s.record!!
        val hSrc = if (s.aux1 != 0) s.aux1 else rec.h
        val hOut = if (s.h0 != 0 || s.h1 != 0) Motion.eased(s.h0, s.h1 - s.h0, e) else hSrc
        val top = Motion.anchoredY(y, hSrc, hOut, s.anchor and 3)
        return (x + rec.w / 2.0) to (top + hOut / 2.0)
    }

    /**
     * What moves, moves together: at the middle step every mover's centre (both axes, both lenses) has gone the fraction
     * of its travel one ease-out over T gives (± 1 px), and at every step within 1.5 px — through the swap too, where a
     * cell changes form. The largest deviations are printed. Three faces, d in {0, 4, 8, 16}, both programs.
     */
    @Test
    fun whatMovesMovesTogether_DW0() {
        var worstMid = 0.0; var worstAny = 0.0; var worstAt = ""
        for ((fname, face) in faces) for (d in ds) {
            val rig = WheelCheck.Rig(face, 352, 9, d)
            val block = rig.block()
            val t = block.t
            val eo = Motion.easeE(Motion.Ease.EaseOut, t)
            for (step in intArrayOf(1, -1)) {
                val segs = block.program(step).resolve(block.params(step, 4), staticOnly = false)
                for ((ti, ts) in segs.withIndex()) for (left in listOf(true, false)) {
                    val (x0, y0) = centre(ts, 0, left); val (xT, yT) = centre(ts, t, left)
                    for (k in 0..t) {
                        val (x, y) = centre(ts, k, left)
                        val dev = maxOf(Math.abs(x - (x0 + (xT - x0) * eo[k] / 4096.0)), Math.abs(y - (y0 + (yT - y0) * eo[k] / 4096.0)))
                        if (dev > worstAny) { worstAny = dev; worstAt = "$fname d $d step $step track $ti k $k left=$left" }
                        assertTrue(dev <= 1.5, "$fname d $d step $step track $ti ${if (left) "left" else "right"}: at step $k its centre is $dev px off the shared ease-out (the rounding of up to three eased terms stays under 1.5)")
                        if (k == t / 2) {
                            worstMid = maxOf(worstMid, dev)
                            assertTrue(dev <= 1.0, "$fname d $d step $step track $ti ${if (left) "left" else "right"}: at the middle step $k its centre is $dev px off the shared ease-out")
                        }
                    }
                }
            }
        }
        println("WheelTest together: the largest deviation from the shared ease-out ${"%.2f".format(worstMid)} px at the middle step, ${"%.2f".format(worstAny)} px at any step ($worstAt)")
    }

    /** The swap at half the travel: the swapping cells change form at n1 — the first step whose ease-out reaches 2048 —
     *  the centre leaving draws its centre form (aux0 0, aux1 hC) through step n1 and its neighbour form (aux0 hC, aux1
     *  hN) from n1 + 1; the neighbour arriving the reverse; the brightness carried through the swap (decisions BK (3)). */
    @Test
    fun theFormsSwapAtHalfTheTravel_DW0() {
        for ((fname, face) in faces) for (d in ds) {
            val rig = WheelCheck.Rig(face, 352, 9, d)
            val block = rig.block()
            val g = block.geo
            val eo = Motion.easeE(Motion.Ease.EaseOut, block.t)
            for (step in intArrayOf(1, -1)) {
                val tracks = block.program(step).resolve(block.params(step, 4), staticOnly = false)
                val x = tracks[2]; val y = tracks[3]
                val n1 = x[0].steps
                assertTrue(eo[n1] >= 2048 && eo[n1 - 1] < 2048, "$fname d $d: the swap at the first step past half the travel ($n1 of ${block.t})")
                assertEquals(listOf(0 to g.hC, g.hC to g.hN), x.map { it.aux0 to it.aux1 }, "$fname d $d step $step: the centre leaving, C then N")
                assertEquals(listOf(g.hC to g.hN, 0 to g.hC), y.map { it.aux0 to it.aux1 }, "$fname d $d step $step: the neighbour arriving, N then C")
                assertEquals(n1, y[0].steps, "both swap at n1")
                // decisions BK (3): the brightness is carried through the swap — the centre form scaled to the neighbour form's own
                // level, the neighbour form unscaled, on both the cell that leaves the centre and the one that arrives
                assertEquals(listOf(Wheel.LEVEL_SWAP_CENTRE, Wheel.LEVEL_SWAP_NEIGHBOUR), listOf(x[0].lvl1, y[0].lvl1), "the leaving centre and the arriving neighbour at n1")
                assertEquals(listOf(Wheel.LEVEL_SWAP_NEIGHBOUR, Wheel.LEVEL_SWAP_CENTRE), listOf(x[1].lvl0, y[1].lvl0), "the forms that take over start where those ended")
                assertEquals(wm.damage.core.shell.Switcher.NEIGHBOUR_LEVEL, Wheel.LEVEL_SWAP_CENTRE, "the centre form's swap level is the neighbour form's own")
            }
        }
    }

    /**
     * The area holds only the wheel: over the area the truth is the same whatever window is behind (two different ones),
     * at every cursor, on both lenses — the stage-5 margins and the band's plane region cut the window behind out of it;
     * and outside the area nothing changes across a detent.
     */
    @Test
    fun theAreaHoldsOnlyTheWheel_DW0() {
        var checked = 0
        for ((_, face) in faces.take(2)) for (d in ds) for (n in listOf(3, 9)) {
            val a = WheelCheck.Rig(face, 352, n, d, behind = 0)
            val b = WheelCheck.Rig(face, 352, n, d, behind = 1)
            for (c in 0 until n) for (left in listOf(true, false)) {
                val area = a.geo.area(left)
                var diff = 0; var outside = 0
                val ta = a.truth(c, left); val tb = b.truth(c, left); val tn = a.truth((c + 1).mod(n), left)
                for (yy in 0 until Geometry.PANEL_H) for (xx in 0 until Geometry.PANEL_W) {
                    val inArea = xx >= area.x && xx < area.right && yy >= area.y && yy < area.bottom
                    if (inArea && ta[xx, yy] != tb[xx, yy]) diff++
                    if (!inArea && ta[xx, yy] != tn[xx, yy]) outside++
                }
                assertEquals(0, diff, "d $d n $n c $c left=$left: px of the area that show the window behind")
                assertEquals(0, outside, "d $d n $n c $c left=$left: px outside the area that change across a detent")
                checked++
            }
        }
        println("WheelTest area: $checked lens-cursors, the window behind nowhere in the area")
    }

    /**
     * The block (the DW spec §3.1; measured on the PC's renderer with the test faces, M-PC): every cell in a slot of its own
     * size with slack and one spare slot of the largest, the item table's slot one entry larger, the programs' slot holding the drum's two
     * programs and tables with slack, its size the same at any base; inside 16 KiB for his nine windows. Prints the bytes,
     * the period and steps, and each program's largest C over every cursor beside the spec's §2.5.
     */
    @Test
    fun theBlocksLayoutBytesTimingAndBudget_DW0() {
        val lines = ArrayList<String>()
        for ((fname, face) in faces) for (d in ds) {
            val rig = WheelCheck.Rig(face, 352, 9, d)
            val block = rig.block()
            val g = block.geo
            assertEquals(Wheel.block(0, g, block.cells, WheelCheck.effects()).size, block.size, "$fname d $d: the size does not hang on the base")
            // the offer's 16 KiB (O6, DW-1's) for the faces of the design's size; ScalingText's glyphs are solid boxes (a stress
            // case, printed): his own face is measured by `desktop --motion-check` (`wheel-staging.txt`)
            if (fname != "ScalingText") assertTrue(block.size <= 16 * 1024, "$fname d $d: ${block.describe()} — past 16 KiB")
            assertEquals(block.cells.map { MainStaging.slack(it.encoded.size) }, block.cellSlots.toList(), "$fname: each cell's slot its size with slack")
            assertEquals(block.cellSlots.toList(), (block.cellOffs.toList() + block.spareOff).zipWithNext().map { (x, y) -> y - x }, "$fname: the slots back to back, the spare last")
            assertEquals(block.cellSlots.max(), block.spareSlot, "$fname: the spare holds the largest cell")
            assertTrue(block.itemSlot >= 2 * (block.n + 1), "$fname: the item table's slot holds one entry more")
            val progs = block.tables.sumOf { (it.record.size + 3) / 4 * 4 } + (block.down.bytes.size + 3) / 4 * 4 + (block.up.bytes.size + 3) / 4 * 4
            assertTrue(block.progSlot >= progs, "$fname: the programs' slot holds them")
            assertEquals(block.down.at * 4, block.tables.last().off4 * 4 + (block.tables.last().record.size + 3) / 4 * 4, "$fname: the programs after their tables")
            val worst = intArrayOf(1, -1).associateWith { st -> (0 until block.n).maxOf { c -> block.program(st).cost(block.params(st, c)) } }
            for (c in worst.values) assertTrue(c * 100 <= Motion.BUDGET_CAP * 80, "$fname d $d: C $c past 80 % of the cap")
            // what the writes come to deflated, one message (at most `Shell.STAGE_CHUNK` bytes of data) at a time (an estimate:
            // the transport's own framing aside)
            val deflated = block.writes().sumOf { (_, b) -> b.toList().chunked(wm.damage.core.shell.Shell.STAGE_CHUNK).sumOf { part ->
                java.util.zip.Deflater(9).let { z -> z.setInput(part.toByteArray()); z.finish(); val buf = ByteArray(part.size + 64); val k = z.deflate(buf); z.end(); k } } }
            lines += "$fname d $d: area ${g.areaPx} px a track; ${"%.2f".format(MainStaging.fastStepMs(Wheel.stepPx(g), Wheel.walkBytes(g, block.cells)))} ms a step " +
                "(${Wheel.stepPx(g)} px written, ${Wheel.walkBytes(g, block.cells)} B decoded); P ${block.period} T ${block.t} = " +
                "${"%.0f".format(block.t * block.period * 1000.0 / 1024)} ms; C down ${"%,d".format(worst[1])} u (${"%.1f".format(worst[1]!! * 100.0 / Motion.BUDGET_CAP)} %), " +
                "up ${"%,d".format(worst[-1])} u (${"%.1f".format(worst[-1]!! * 100.0 / Motion.BUDGET_CAP)} %); ${block.describe()}; deflated ${deflated} B"
        }
        println("WheelTest block (modeled timing and budget; bytes M-PC with the test faces)\n" + lines.joinToString("\n"))
    }

    /**
     * A face whose neighbour row below falls on an odd row (Adam's own): at stage 5 the row sits on the even row above it
     * (the icon and the name were there already; the dirty tick moves up one row), so one neighbour form serves both rows
     * and END is the paint (the END pin, [OddFace]); below stage 5 the paint is today's — the tick at the odd row's own
     * offset. A wheel of two entries is not offered.
     */
    @Test
    fun aRowOnAnOddRowSitsOnTheEvenRowAtStage5AndTodaysBelow_DW0() {
        val odd = OddFace()
        val rig = WheelCheck.Rig(odd, 352, 5, 8, dirty = setOf(0, 1, 2, 3))
        val l = rig.layout
        val r4 = rig.sw.rest(l); val r5 = rig.sw.rest(l, stage5 = true)
        assertEquals(1, r4.yB % 2, "the odd face puts the row below on an odd row at stage 4 ($r4)")
        assertEquals(r4.yB - 1, r5.yB, "at stage 5 on the even row above it")
        assertEquals(r5.yB, rig.geo.yB)
        assertNull(Wheel.refusal(rig.geo, 5))
        rig.restAt(1)
        // the dirty tick of the row below (the switcher's entry 2 at cursor 1): at stage 4 from yB + 4, at stage 5 from yB + 3
        val g4 = Gray8(Geometry.PANEL_W, Geometry.PANEL_H).also { rig.sw.paint(it, l, 0) }
        val g5 = Gray8(Geometry.PANEL_W, Geometry.PANEL_H).also { rig.sw.paint(it, l, 0, stage5 = true) }
        val diff = (0 until Geometry.PANEL_H).filter { y -> (0 until Geometry.PANEL_W).any { x -> g4[x, y] != g5[x, y] } }
        assertEquals(listOf(r4.yB + 3, r4.yB + 11), diff, "the two paints differ only where the tick (rows y + 4 .. y + 11) moved up one row")
        val two = WheelCheck.Rig(FakeText(), 352, 3, 8)
        assertNull(Wheel.refusal(two.geo, 3))
        assertNotNull(Wheel.refusal(two.geo, 2), "two entries: not offered")
        assertFailsWith<LintError> { Wheel.block(0, two.geo, two.cells().take(2), WheelCheck.effects()) }
    }
}
