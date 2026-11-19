package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.geom.VPos
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.motion.BoxFace
import wm.damage.core.motion.Motion
import wm.damage.core.shell.ContentKit
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MainSeqPlan
import wm.damage.core.shell.MainStaging
import wm.damage.core.shell.MainSurface
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.Pb

/**
 * Build C2, stage DMS (`C2-DMS-brief.md`; spec §6.4): Main's rows staged from Main's own painters ([MainStaging]).
 * Context for the reader: Adam's own glasses on his own firmware fork; nothing here contacts a device — the glasses are
 * the firmware model ([GlassFirmwareSim]) driven message by message.
 *
 * The pin that matters: END of each pan program, at every cursor and in both directions, is Main's own paint at the
 * moved cursor inside the program's areas, pixel for pixel — the compositor's own truth of `ContentKit.paintList` over
 * `MainSurface` under the planes the shell gives Main at Global `Depth` 8 — at all four heights; and the shadow the
 * firmware model writes at that PLAY is what [MainStaging.paintEnd] puts into belief. The text is [BoxFace] (half-level
 * edges: raw values that are not a level's, so a belief painted from the records' levels instead of the pictures' raw
 * pixels would differ from the truth byte for byte).
 */
class MainStagingTest {

    private class Row(id: String, name: String, icon: IconKind, private val s: DamageWindow.Summary) : DamageWindow(id, name, icon) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = s
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private val rows: List<DamageWindow> = listOf(
        Row("reader", "Reader", IconKind.READER, DamageWindow.Summary("The Long Way Home", detail = "chapter 12 of 31", progress = 0.41)),
        Row("tmux", "Tmux", IconKind.TERMINAL, DamageWindow.Summary("3 sessions", detail = "build: idle 4 min")),
        Row("files", "Files", IconKind.FILES, DamageWindow.Summary("Documents", detail = "24 items", more = true)),
        Row("torrents", "Torrents", IconKind.TORRENTS, DamageWindow.Summary("2 active, 3.1 MB/s", detail = "debian-13.iso 64%", progress = 0.64)),
        Row("music", "Music", IconKind.MUSIC, DamageWindow.Summary("Night Drive - Low Tide", detail = "2:31 / 4:05", more = true, progress = 0.62)),
        Row("games", "Games", IconKind.GAMES, DamageWindow.Summary("Hold'em, 6 left", detail = "stack 1,840")),
        Row("feed", "Feed", IconKind.FEED, DamageWindow.Summary("12 new", detail = "4 sources", more = true)),
        Row("settings", "Settings", IconKind.SETTINGS, DamageWindow.Summary("Global and 8 apps")),
    )

    /** Main at [height] as the shell draws it: the planes `Shell.updatePlanes` gives Main at Global `Depth` [depth]
     *  (both bars and the content at d, the lens one notch nearer), `ContentKit.paintList` over `MainSurface`. */
    private inner class Main(val height: Int, val depth: Int = 8, val n: Int = rows.size) {
        val layout: Layout = Layout().let { if (height >= Geometry.PANEL_H) it else it.withHeightMode(height, VPos.TOP) }
        val comp = Compositor()
        val kit = ContentKit(comp)
        val surface = MainSurface(BoxFace, { rows.take(n) }, {})
        val view = surface.view()
        val bandD = maxOf(0, depth - 4)

        init {
            // `updatePlanes`: no region at all at Depth 0 (the remainder is the screen plane)
            if (depth != 0) comp.planes = listOf(
                Compositor.PlaneRegion(Rect(layout.topBar.x, layout.topBar.y, layout.topBar.w, Layout.TOP_H + Layout.DIV_H), depth),
                Compositor.PlaneRegion(Rect(layout.statusBar.x, layout.bottomDivider.y, layout.statusBar.w, Layout.DIV_H + Layout.STATUS_H), depth),
                Compositor.PlaneRegion(layout.content, depth),
            ) + (if (bandD != depth) listOf(Compositor.PlaneRegion(layout.lens, bandD)) else emptyList())
        }

        fun staging(cacheSize: Int = CfwModes.CACHE2_MAX) = MainStaging.build(layout, view, kit, depth, bandD, cacheSize)

        private val truths = HashMap<Int, Pair<Gray8, Gray8>>()

        /** The compositor's per-lens truth of Main with the cursor at [c]. */
        fun truth(c: Int, left: Boolean): Gray8 = truths.getOrPut(c) {
            view.model.cursor = c
            comp.composed.clear()
            kit.resetRail()
            kit.paintList(comp.composed, layout, view)
            if (comp.needsKeyframe) { comp.assembleFlush(Geometry.rectBudget(1)); kit.paintList(comp.composed, layout, view) }  // the keyframe path renders no truth
            comp.assembleFlush(Geometry.rectBudget(1))           // renders the truth under the plane map
            comp.truthForTest(true).copy() to comp.truthForTest(false).copy()
        }.let { if (left) it.first else it.second }
    }

    private fun rawDiffer(a: Gray8, b: Gray8, areas: List<Rect>): Int {
        var n = 0
        for (r in areas) for (y in r.y until r.bottom) for (x in r.x until r.right) if (a[x, y] != b[x, y]) n++
        return n
    }

    /** A GlassFirmwareSim on contract 2 with DRAW2 and ENGINE, a 160 KiB cache, and [ms]'s records written. */
    private fun simWith(ms: MainStaging): GlassFirmwareSim {
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0x1ff; it.clockIsOsTick = true }
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, 1000)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, ms.cacheSize / 1024, 1000)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE, 1000)
            for (b in ms.blobs) {
                var pos = 0
                while (pos < b.bytes.size) {
                    val len = minOf(wm.damage.core.shell.Shell.STAGE_CHUNK, b.bytes.size - pos)
                    val m = CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2((b.off + pos) / 4, b.bytes.copyOfRange(pos, pos + len))), ms.cacheSize)
                    assertTrue(sim.conformanceMessage(arm, m, 1000), "${b.name} at ${b.off + pos}: ${sim.refusalRecord(arm)}")
                    pos += len
                }
            }
        }
        return sim
    }

    private fun shadowLevels(sim: GlassFirmwareSim, arm: Arm): IntArray {
        val sh = if (arm == Arm.LEFT) sim.left.shadow else sim.right.shadow
        return IntArray(640 * 480) { i -> val b = sh[(i / 640) * 320 + (i % 640) / 2].toInt() and 0xFF; if (i % 2 == 0) b shr 4 else b and 0x0F }
    }

    /**
     * The pin (spec DM): at each of Main's four heights, for each width and both directions, at EVERY cursor a notch can
     * land on (a notch across the wrap is an edge: no program plays there), on each lens:
     * - END painted onto Main at the cursor before the notch is the compositor's truth of Main at the moved cursor
     *   inside the program's areas, byte for byte (so belief equals truth there, raw);
     * - the start state (every track's first segment at j = 0) is Main as the lens shows it — no jump at step 1;
     * - played on the firmware model over that lens's picture, the PLAY writes into the shadow exactly END's levels
     *   everywhere, every step is drawn and none folded, and after the last step the lens shows the shadow.
     * At 352 also on a 256 KiB cache, where the band forms' offsets pass 32767 and P1 / P2 go out as negative s16.
     */
    @Test
    fun endOfEveryPanAtEveryCursorIsMainsOwnPaintAtTheMovedCursor_DMS() {
        val full = Rect(0, 0, 640, 480)
        for ((h, size) in listOf(288, 352, 416, 480).map { it to CfwModes.CACHE2_MAX } + (352 to CfwModes.CACHE2_MAX_RIDERS)) {
            val m = Main(h)
            val ms = m.staging(size)
            val sim = simWith(ms)
            var t = 2000L
            var tag = 0
            for (pan in ms.pans) for (cPrime in 0 until ms.rows) {
                val c = cPrime - pan.step
                if (c !in 0 until ms.rows) { assertNull(ms.params(pan, cPrime), "$h k${pan.k} step ${pan.step}: c' $cPrime from $c is an edge"); continue }
                val p = assertNotNull(ms.params(pan, cPrime))
                val what = "$h (${size / 1024} KiB) k${pan.k} step ${pan.step} $c→$cPrime"
                tag++
                for (arm in Arm.entries) {
                    val left = arm == Arm.LEFT
                    val before = m.truth(c, left)
                    val end = before.copy().also { ms.paintEnd(pan, p, left, it) }
                    assertEquals(0, rawDiffer(end, m.truth(cPrime, left), pan.areas(left)), "$what $arm: px where END is not Main's paint at $cPrime")
                    val start = before.copy().also { ms.paintStart(pan, p, left, it) }
                    assertEquals(0, rawDiffer(start, before, pan.areas(left)), "$what $arm: px where the start state is not Main at $c")
                    assertTrue(sim.conformanceMessage(arm, CfwModes.keyframe(Zl.encodeCfw(Pack.rect(before, full))), t))
                    assertTrue(sim.conformanceMessage(arm, Motion.play(pan.program, 0, ms.period, tag, 0, p), t), "$what $arm: ${sim.refusalRecord(arm)}")
                    val sh = shadowLevels(sim, arm)
                    var off = 0
                    for (i in sh.indices) if (sh[i] != Pack.level(end.pix[i].toInt() and 0xFF)) off++
                    assertEquals(0, off, "$what $arm: px where the shadow the PLAY wrote is not END (what belief takes)")
                }
                for (k in 2..ms.t) for (arm in Arm.entries) sim.conformanceWake(arm, t + (k - 1) * ms.period)    // decisions V: the staging's P and T
                val drew = sim.conformanceNotifies(Arm.RIGHT).map { DamageMsg.parseMotion(Pb.l(DamageMsg.MOTION_FIELD, it))!! }
                    .filter { it.tag == tag && it.outcome == DamageMsg.OUTCOME_DREW }
                assertEquals(listOf(ms.t.toLong() to 0L), drew.map { it.drawn to it.folded }, "$what: RIGHT's notify, every step drawn, none folded")
                for (arm in Arm.entries) assertEquals(sim.shadowCrc32(arm), sim.panelCrc32(arm), "$what $arm: the lens shows END after the last step")
                t += 300
            }
        }
    }

    /** The wrap: where Main wraps (288 and 352 with eight rows) the rows a pan shows past either end are the other end's
     *  — the strip's margins — and the pin above holds at the first and the last row; where it does not wrap (416, 480)
     *  those slots are blank. The cursors at the ends are exercised there; this pins that the margins are what makes
     *  it hold (the strip is the margins plus the rows, at the pitch). */
    @Test
    fun theStripCarriesMainsRowsPastEitherEndAsMarginRows_DMS() {
        for (h in listOf(288, 352, 416, 480)) {
            val m = Main(h)
            val ms = m.staging()
            val l = m.layout
            assertEquals((l.rowsAbove + ms.rows + l.rowsBelow) * l.rowH, ms.strip.raw.h, "$h: the strip's height")
            assertEquals(l.content.w - Layout.RAIL_W, ms.strip.raw.w, "$h: the strip's width (the rail aside)")
            val wraps = ms.rows > l.rowsAbove + l.rowsBelow + 1
            val lastRowInMargin = ms.strip.raw.let { g -> (0 until l.rowH).any { y -> (0 until g.w).any { x -> g[x, (l.rowsAbove - 1) * l.rowH + y] != 0 } } }
            assertEquals(wraps, lastRowInMargin, "$h: the margin row before row 0 holds the last row exactly where Main wraps")
        }
    }

    /** The staging fits a 160 and a 256 KiB session's reserve at every height, every PLAY it can play passes the
     *  offline checks (`Motion.play`), its tracks never meet one another, and its first read range lies inside what it
     *  writes; the widths cap at what the height shows (288 shows two rows each side). The numbers go to the report. */
    @Test
    fun theStagingFitsTheReserveOfA160And256KiBSession_DMS() {
        for (size in listOf(CfwModes.CACHE2_MAX, CfwModes.CACHE2_MAX_RIDERS)) for (h in listOf(288, 352, 416, 480)) {
            val m = Main(h)
            val ms = m.staging(size)
            assertEquals(size - CfwModes.STAGE_RESERVE, ms.start, "$h at ${size / 1024}: the staging starts at the reserve")
            assertTrue(ms.end <= size, "$h at ${size / 1024}: ends at ${ms.end}")
            for (pan in ms.pans) {
                for (cPrime in 0 until ms.rows) {
                    val p = ms.params(pan, cPrime) ?: continue
                    Motion.play(pan.program, 0, ms.period, 1, 0, p)
                    val first = pan.program.firstRange(p)
                    assertTrue(first.first >= ms.start && first.last < ms.end, "$h k${pan.k}: the first read range $first inside ${ms.start}..${ms.end}")
                }
                for (left in listOf(true, false)) for (i in pan.areas(left).indices) for (j in pan.areas(left).indices)
                    if (i < j) assertNull(pan.areas(left)[i].intersect(pan.areas(left)[j]), "$h k${pan.k}: tracks $i and $j meet")
            }
            assertEquals(if (h == 288) 2 else 3, ms.pan(3, 1).kA, "$h: k 3 capped at the rows shown")
            if (size == CfwModes.CACHE2_MAX) println("DMS-numbers $h: ${ms.bytes} B at ${ms.start}..${ms.end} (strip ${ms.blobs.first().bytes.size} B " +
                "${ms.strip.raw.w}x${ms.strip.raw.h}, bands ${ms.blobs.drop(1).dropLast(1).map { it.bytes.size }}, programs ${ms.blobs.last().bytes.size} B); " +
                ms.pans.filter { it.step == 1 }.joinToString { p -> "k${p.k} ${p.kA}/${p.kB}: ${p.areaPx} px " + p.areasL.joinToString("+") { "${it.w}x${it.h}@${it.x},${it.y}" } })
        }
    }

    /** At Global `Depth` 0 (no planes: list and band on one plane) and at 16, the same pin at Adam's height, every cursor. */
    @Test
    fun thePinHoldsOnOnePlaneAndAtTheDeepestDepth_DMS() {
        for (d in listOf(0, 16)) {
            val m = Main(352, depth = d)
            val ms = m.staging()
            for (pan in ms.pans) for (cPrime in 0 until ms.rows) {
                val p = ms.params(pan, cPrime) ?: continue
                for (left in listOf(true, false)) {
                    val end = m.truth(cPrime - pan.step, left).copy().also { ms.paintEnd(pan, p, left, it) }
                    assertEquals(0, rawDiffer(end, m.truth(cPrime, left), pan.areas(left)), "depth $d k${pan.k} step ${pan.step} → $cPrime ${if (left) "L" else "R"}")
                }
            }
        }
    }

    /** A short list (three rows, no wrap at 352): the pans play between its rows, an edge past them. */
    @Test
    fun aShortListPlaysBetweenItsRowsAndEdgesPastThem_DMS() {
        val m = Main(352, n = 3)
        val ms = m.staging()
        assertEquals(3, ms.rows)
        val down = ms.pan(2, 1)
        assertNull(ms.params(down, 0), "a notch down onto row 0 comes from row −1: an edge")
        for (cPrime in 1..2) for (left in listOf(true, false)) {
            val end = m.truth(cPrime - 1, left).copy().also { ms.paintEnd(down, ms.params(down, cPrime)!!, left, it) }
            assertEquals(0, rawDiffer(end, m.truth(cPrime, left), down.areas(left)), "→ $cPrime")
        }
        assertEquals(listOf(2 to 1, 1 to 0, 0 to 1, 1 to 2), MainSeqPlan.plan(listOf(2), 3, MainSeqPlan.Pace()).map { it.from to it.to })
    }

    /** The test's plan, as Adam asked it: per width six notches up from the last row a constant apart, a pause, six down
     *  one at a time; between widths a pause. */
    @Test
    fun thePlanIsSixUpFastAPauseSixDownSlowPerWidth_DMS() {
        val pace = MainSeqPlan.Pace()
        val plan = MainSeqPlan.plan(listOf(1, 2, 3), 8, pace)
        assertEquals(36, plan.size)
        for ((ki, k) in listOf(1, 2, 3).withIndex()) {
            val part = plan.subList(ki * 12, ki * 12 + 12)
            assertTrue(part.all { it.k == k })
            assertEquals((7 downTo 2).map { it to it - 1 } + (1..6).map { it to it + 1 }, part.map { it.from to it.to })
            assertEquals(listOf(if (ki == 0) 0L else pace.betweenMs) + List(5) { pace.fastMs } + listOf(pace.pauseMs) + List(5) { pace.slowMs }, part.map { it.afterMs })
        }
        assertEquals(listOf(280L, 2_000L, 1_000L, 3_000L, 100L), listOf(pace.fastMs, pace.pauseMs, pace.slowMs, pace.betweenMs, pace.followUpMs))
    }

    /** The run reads RIGHT's notify-114 from the transport's `motion` note: its form is [DamageMsg.Motion.describe]'s. */
    @Test
    fun theNotifyIsReadFromTheMotionNotesOwnForm_DMS() {
        fun body(vararg f: Pair<Int, Long>) = DamageMsg.Motion(mapOf(*f)).describe()
        assertEquals(MainSeqPlan.Notify(0xD003, "drew", 6, 0, 24500),
            MainSeqPlan.parseNotify(body(1 to 9L, 2 to 0L, 3 to 0xD003L, 4 to 0L, 5 to 0L, 6 to 6L, 7 to 0L, 8 to 0x1234L, 9 to 24500L, 11 to 99L)))
        assertEquals(MainSeqPlan.Notify(0xD004, "closed", 3, 1, 18000),
            MainSeqPlan.parseNotify(body(1 to 10L, 2 to 0L, 3 to 0xD004L, 4 to 1L, 5 to 0L, 6 to 3L, 7 to 1L, 8 to 0L, 9 to 18000L, 11 to 99L)))
        val refused = MainSeqPlan.parseNotify(body(1 to 11L, 2 to 0L, 3 to 0xD005L, 4 to 2L, 10 to 15L, 11 to 99L))
        assertEquals(0xD005, refused?.tag)
        assertTrue(refused!!.outcome.startsWith("refused"), refused.outcome)
        assertNull(MainSeqPlan.parseNotify("test program sent (list: …)"))
    }
}
