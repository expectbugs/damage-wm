package wm.damage.core.motion

import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.geom.VPos
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.MainLocalCheck
import wm.damage.core.shell.MainStaging
import wm.damage.core.shell.Shell
import wm.damage.core.shell.Switcher
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.DamageMsg

/**
 * C3 stage DW-0: the wheel ([Wheel]) on the simulator's model — the rig `WheelTest` pins it on and the strips `desktop
 * --motion-check` writes for the lead's look before Adam's. Context for the reader: Adam's own glasses on his own firmware
 * fork; nothing here contacts a device or binds anything — a notch is the phone's PLAY of the program into slot 0, as a
 * fire would draw it (a later notch closes the earlier instance and draws from its own start, the DW spec F3).
 */
object WheelCheck {
    /** His windows as the switcher lists them (the built windows' names and icons; Main is the switcher's own first entry),
     *  and a tenth for n = 10. */
    val WINDOWS: List<Pair<String, IconKind>> = listOf("Reader" to IconKind.READER, "Tmux" to IconKind.TERMINAL, "Files" to IconKind.FILES,
        "Torrents" to IconKind.TORRENTS, "Music" to IconKind.MUSIC, "Games" to IconKind.GAMES, "Feed" to IconKind.FEED,
        "Settings" to IconKind.SETTINGS, "Window 9" to IconKind.FILES)

    /** A bit-11 build at stage 5 (`FIRMWARE.md` §7.9: features 0xdff) and the 256 KiB cache his glasses run. */
    const val FEATURES = 0xdff
    const val CACHE = CfwModes.CACHE2_MAX_RIDERS
    private val FLAGS = DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE or DamageMsg.FLAG_BIND or DamageMsg.FLAG_EFFECTS

    /** The one gate a version-2 record is built through, as the transport's state would give it at stage 5. */
    fun effects(): Motion.Effects = Motion.effects(DamageMsg.Caps(DamageMsg.CONTRACT, FEATURES), FLAGS)

    /**
     * The open wheel of [n] entries (Main and [n] − 1 of [WINDOWS], the first the current window) at [height] with its
     * neighbour rows at disparity [d] (Global `Depth`; the stage-5 margin), over a window whose rows differ by [behind], the
     * windows at the indices in [dirty] carrying the dirty tick:
     * the planes `Shell.updatePlanes` gives it at stage 5 (both bars and the content at d, `Switcher.planes` with the
     * margin d; none at 0), and the per-lens truth the compositor renders under them.
     */
    class Rig(text: TextRasterizer, val height: Int, val n: Int, val d: Int, val behind: Int = 0, dirty: Set<Int> = emptySet()) {
        val layout: Layout = Layout().let { if (height >= Geometry.PANEL_H) it else it.withHeightMode(height, VPos.TOP) }
        val comp = Compositor()
        val sw = Switcher(text)
        private val windows: List<DamageWindow> = WINDOWS.take(n - 1).mapIndexed { i, (name, icon) ->
            MainLocalCheck.SampleRow("w$i", name, icon, DamageWindow.Summary(name)).also { it.dirty = i in dirty } }
        init {
            if (n !in 3..WINDOWS.size + 1) throw LintError("a rig of $n entries (3..${WINDOWS.size + 1})")
            sw.openWith(windows[0], windows)
        }
        val entries: List<Switcher.Entry> get() = sw.entriesInOrder
        val geo: Wheel.Geo = Wheel.geo(sw, layout, d)

        private fun planes(): List<Compositor.PlaneRegion> = if (d == 0) emptyList() else listOf(
            Compositor.PlaneRegion(Rect(layout.topBar.x, layout.topBar.y, layout.topBar.w, Layout.TOP_H + Layout.DIV_H), d),
            Compositor.PlaneRegion(Rect(layout.statusBar.x, layout.bottomDivider.y, layout.statusBar.w, Layout.DIV_H + Layout.STATUS_H), d),
            Compositor.PlaneRegion(layout.content, d)) + sw.planes(layout, d, d)

        /** The window behind: rows across the whole content width, their levels and lengths by [behind]. */
        fun paintBehind(g: Gray8) {
            val c = layout.content
            var y = c.y
            var i = 0
            while (y + 4 <= c.bottom) {
                g.fillRect(c.x + (i * 7 + behind * 5) % 24, y, c.w - 24, 4, (((i + behind * 3) % 14) + 1) * 17)
                y += 6; i++
            }
        }

        /** The switcher at rest on [c] (a notch's spin stepped to its end). */
        fun restAt(c: Int) {
            sw.scroll(c - sw.cursor)
            while (sw.stepSpin()) {}
        }

        private val truths = HashMap<Int, Pair<Gray8, Gray8>>()

        /** The compositor's per-lens truth with the wheel at rest on [c]. */
        fun truth(c: Int, left: Boolean): Gray8 = truths.getOrPut(c) {
            comp.planes = planes()
            restAt(c)
            fun paint() { comp.composed.clear(); paintBehind(comp.composed); sw.paint(comp.composed, layout, d, stage5 = true); comp.damage(Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H)) }
            paint()
            if (comp.needsKeyframe) { comp.assembleFlush(Geometry.rectBudget(1)); paint() }   // the keyframe path renders no truth
            comp.assembleFlush(Geometry.rectBudget(1))
            comp.truthForTest(true).copy() to comp.truthForTest(false).copy()
        }.let { if (left) it.first else it.second }

        /** [truth] as the lens holds it (4bpp, 320 B a row). */
        fun panel(c: Int, left: Boolean): ByteArray = Pack.rect(truth(c, left), Rect(0, 0, Geometry.PANEL_W, Geometry.PANEL_H))

        fun cells(relay: (Gray8, () -> Unit) -> Unit = { _, b -> b() }): List<Wheel.Cell> = entries.map { Wheel.cell(sw, layout, geo, it, relay) }

        /** The block as its first tenant from the end of Main's region would lie (decisions BD; DW-1 places it). */
        fun block(motion: MainStaging.ScrollMotion = MainStaging.ScrollMotion.DEFAULT, none: Boolean = false, cells: List<Wheel.Cell> = cells()): Wheel.Block {
            val end = CfwModes.mainRegion(CACHE, bind = true).last + 1
            val size = Wheel.block(0, geo, cells, effects(), motion, none).size
            return Wheel.block((end - size) and 3.inv(), geo, cells, effects(), motion, none)
        }
    }

    // -------------------------------------------------------------------------------------------------- the strips
    class Frame(val label: String, val tick: Long, val l: ByteArray, val r: ByteArray)

    /** One strip: its frames in rows (a row a notch for a burst), the region they are cropped to, the lines of its text. */
    class Strip(val name: String, val about: String, val crop: Rect, val rows: List<List<Frame>>, val lines: List<String>, val failures: List<String>)

    /** The ring's least scroll gap (`FIRMWARE.md` §6.1, M: 224 ms) in OS ticks, the burst's pace. */
    const val NOTCH_TICKS = 230L
    /** The burst's pause between the six down and the six up. */
    const val PAUSE_TICKS = 820L

    /**
     * [notches] (step +1 or −1, and the ticks after the notch before it: the first's is ignored) played from cursor [c0] on
     * a fresh simulator of a bit-11 build at stage 5 with his 256 KiB cache: the lease, the flags, each lens's truth at
     * [c0] as a keyframe, the block's writes, then per notch the PLAY of its program into slot 0 with its parameters (step 1
     * drawn by the PLAY's own present) and a wake at every later step's tick; a frame after every step. Checks, each a
     * failure: every message taken by both lenses; after the last step each lens shows END (its shadow), and over the area
     * it is the switcher's paint at the last cursor.
     */
    fun play(rig: Rig, block: Wheel.Block, name: String, about: String, c0: Int, notches: List<Pair<Int, Long>>): Strip {
        val failures = ArrayList<String>()
        val lines = ArrayList<String>()
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = FEATURES; it.clockIsOsTick = true }
        var now = 1000L
        fun send(what: String, m: ByteArray) {
            for (arm in Arm.entries) if (!sim.conformanceMessage(arm, m, now)) failures += "$arm refused $what (refusal ${sim.refusalRecord(arm)})"
        }
        for (arm in Arm.entries) {
            sim.conformanceLease(arm, true, now)
            sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, CACHE / 1024, now)
            sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, FLAGS, now)
        }
        for (arm in Arm.entries) if (!sim.conformanceMessage(arm, CfwModes.keyframe(Zl.encodeCfw(rig.panel(c0, arm == Arm.LEFT))), now))
            failures += "$arm refused its keyframe"
        for ((off, bytes) in block.writes()) {
            var pos = 0
            while (pos < bytes.size) {
                val len = minOf(Shell.STAGE_CHUNK, bytes.size - pos)
                send("the write at ${off + pos}", CfwModes.cacheUpdate2(listOf(CfwModes.CacheWrite2((off + pos) / 4, bytes.copyOfRange(pos, pos + len))), CACHE))
                pos += len
            }
        }
        val rows = ArrayList<List<Frame>>()
        rows += listOf(Frame("before (c $c0)", now, sim.snapshot(Arm.LEFT), sim.snapshot(Arm.RIGHT)))
        var c = c0
        now = 2000L
        var tPlay = now
        notches.forEachIndexed { i, (step, after) ->
            if (i > 0) tPlay += after
            val row = ArrayList<Frame>()
            val prog = block.program(step)
            val p = block.params(step, c)
            now = tPlay
            send("notch ${i + 1}'s PLAY", Motion.play(prog, 0, block.period, i + 1, 0, p))
            row += Frame("notch ${i + 1} step 1", now, sim.snapshot(Arm.LEFT), sim.snapshot(Arm.RIGHT))
            val next = notches.getOrNull(i + 1)?.let { tPlay + it.second } ?: Long.MAX_VALUE
            for (k in 2..block.t) {
                val at = tPlay + (k - 1).toLong() * block.period
                if (at >= next) break
                now = at
                for (arm in Arm.entries) sim.conformanceWake(arm, now)
                row += Frame("notch ${i + 1} step $k", now, sim.snapshot(Arm.LEFT), sim.snapshot(Arm.RIGHT))
            }
            lines += "notch ${i + 1}: step ${if (step > 0) "+1 (down)" else "-1 (up)"} from c $c at tick $tPlay, P0..P3 ${p.joinToString()}, ${row.size} frame(s)"
            c = (c + step).mod(block.n)
            rows += row
        }
        for (arm in Arm.entries) {
            if (sim.panelCrc32(arm) != sim.shadowCrc32(arm)) failures += "$arm shows something other than END (its shadow) after the last step"
            val want = rig.truth(c, arm == Arm.LEFT)
            val got = sim.snapshot(arm)
            val a = block.geo.area(arm == Arm.LEFT)
            var off = 0
            for (y in a.y until a.bottom) for (x in a.x until a.right) {
                val b = got[y * (Geometry.PANEL_W / 2) + (x shr 1)].toInt() and 0xFF
                if ((if (x and 1 == 0) b shr 4 else b and 0x0F) != Pack.level(want[x, y])) off++
            }
            if (off != 0) failures += "$arm: $off px of the area are not the switcher's paint at c $c after the last step"
        }
        val g = block.geo
        val crop = Rect(maxOf(0, g.panel.x - 2 * g.d - 8), g.panel.y - 8, g.panel.w + 4 * g.d + 16, g.panel.h + 16)
        return Strip(name, about, crop, rows, lines, failures)
    }

    /**
     * The strips the lead looks at before Adam (the DW spec §7.2): at height 352 with the [text] given, for d in [ds] and n in
     * [ns], one notch down and one up from the middle of the list and the two across the wrap (`wheel-d<D>-n<N>-<notch>`), and
     * at d 8 and n 9 his sequence: six notches down at the ring's pace, a pause, six up (`wheel-d8-n9-burst`). Also the block's
     * bytes and timing per (d, n), and his nine windows' cells.
     */
    fun strips(text: TextRasterizer, ds: List<Int> = listOf(0, 8), ns: List<Int> = listOf(3, 5, 9), height: Int = 352): Pair<List<Strip>, List<String>> {
        val out = ArrayList<Strip>()
        val table = ArrayList<String>()
        for (d in ds) for (n in ns) {
            val rig = Rig(text, height, n, d)
            val block = rig.block()
            table += "d $d n $n: ${block.describe()}; ${rig.geo}"
            val mid = n / 2
            fun one(kind: String, c0: Int, step: Int) {
                out += play(rig, block, "wheel-d$d-n$n-$kind", "the wheel at d $d, $n entries: $kind from c $c0", c0, listOf(step to 0L))
            }
            one("down", mid, 1); one("up", mid, -1); one("wrap-down", n - 1, 1); one("wrap-up", 0, -1)
        }
        val rig = Rig(text, height, 9, 8)
        val block = rig.block()
        val burst = List(6) { 1 to NOTCH_TICKS } + listOf(-1 to PAUSE_TICKS) + List(5) { -1 to NOTCH_TICKS }
        out += play(rig, block, "wheel-d8-n9-burst", "his sequence at d 8, nine entries: six notches down ${NOTCH_TICKS} ticks apart " +
            "(the ring's least gap, 224 ms), a pause of $PAUSE_TICKS ticks, six up", 1, burst)
        table += "his nine windows' cells (the drawn icons): ${rig.cells().joinToString { "${it.entry.name} ${it.encoded.size} B" }}"
        return out to table
    }
}
