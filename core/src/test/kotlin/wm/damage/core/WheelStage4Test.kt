package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.geom.Geometry
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * C3 stage DW-0 (the DW spec §7.1 "stage 4 unchanged"): below stage 5 the switcher's paint and every byte the shell sends
 * for it are the tree's before DW. Context for the reader: Adam's own glasses on his own firmware fork; nothing here
 * contacts a device — the glasses are the simulator's model behind the sim transport.
 *
 * A shell on a bit-11 build (DamageCaps 0xdff) held at ladder stage 4, Global `Depth` 8, at heights 352 and 480, five
 * windows open: the wheel opened by the chord, a notch down, a notch up, three notches up (the third wraps), the commit,
 * the wheel opened again and cancelled — each act settled before the next. Every image message either lens took from the
 * open on (but the rules' idle SETTLE, whose count rides on how long an act waits) is compared, in order, with
 * `wheelwire/stage4-h<height>.txt`, recorded on the integration head before DW-0 (`b5a7bdfb`) by this test's first run (a
 * missing file is written under `build/wheelwire/` and the test fails), over a paced link so the flushes fall alike at
 * every run (with instant acks they did not: the preview's flush and a spin frame raced).
 */
class WheelStage4Test {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp: Path = Files.createTempDirectory("wheel-stage4")
    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private class Win(id: String, name: String, icon: IconKind) : DamageWindow(id, name, icon) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = Summary("summary of $name", detail = "detail")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** The acts at [height]: each act's label line, then the image messages (lens, hex) it caused. */
    private suspend fun acts(height: Int): List<String> {
        val dir = Files.createTempDirectory(tmp, "s")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xDFF }
        val out = java.util.concurrent.CopyOnWriteArrayList<String>()
        val on = java.util.concurrent.atomic.AtomicBoolean(false)
        val bound = java.util.concurrent.atomic.AtomicBoolean(false)
        sim.imageObserver = { a, img ->
            if (subs(img).any { it.size > 1 && it[0].toInt() == 27 && it[1].toInt() == 0 }) bound.set(true)   // a SET: Main's list bound
            // the rules' idle SETTLE naming no list (mode 26 sub 5, list 0xFF) is left out: it goes on a pace after the shell
            // falls idle, so how many fall inside an act depends on how long the act waits (seen moving between runs on the
            // unchanged tree); it is the bound list's, not the switcher's
            val idleSettle = img.size == 10 && img[0].toInt() == 26 && img[1].toInt() == 5 && img[5] == 0xFF.toByte()
            if (on.get() && !idleSettle) out += "${a.name} ${img.hex()}"
        }
        // a paced link (100 ms a message's ack, 400 KB/s): the loop's work between two acks is far shorter than an ack, so the
        // flushes fall the same way at every run (with instant acks the preview's flush and a spin frame raced)
        val t = SimTransport(sim, scope, SimTransport.Timing(ackMs = 100, bytesPerSec = 400_000.0))
        t.devProbe("c1", "4")
        val p = Persistence(dir.resolve("s.json"))
        p.put("shell.settings", ShellSettings(heightMode = height, depth = 8, cachedText = "on").toJson())
        val shell = Shell(CachedText(FakeText()), t, p, null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val kinds = listOf(IconKind.READER, IconKind.TERMINAL, IconKind.FILES, IconKind.MUSIC, IconKind.FEED)
        for (i in 0 until 5) shell.register(Win("w$i", "Window $i", kinds[i]))
        shell.start()
        // settled: quiescent, and on one loop sample no cache work in flight, no count unjudged, the atlas's extensions
        // all live, and no message more for 300 ms (each decided on one evaluation)
        suspend fun settle(what: String) {
            repeat(400) {
                waitUntil(30_000, 10, { "$what: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
                val n0 = out.size
                val rest = shell.sampleIdle { !shell.cacheWorkInFlight && shell.cacheCountUnjudged == 0L && shell.atlasExtensionsLive == shell.atlasExtensionsQueued }
                kotlinx.coroutines.delay(300)
                if (rest == true && shell.isQuiescent() && out.size == n0) return
            }
            fail("$what: the shell never came to rest: ${shell.quiescenceReport()}")
        }
        waitUntil(30_000, 10, { "Main's list bound at stage 4" }) { shell.isQuiescent() && bound.get() }
        for (i in 0 until 5) { shell.services.runOnShell { shell.services.openWindow("w$i", null) }; settle("open w$i") }
        on.set(true)
        suspend fun act(label: String, vararg gestures: Int) {
            out += "# $label"
            for (g in gestures) shell.postGesture(g)
            settle(label)
        }
        act("open (the chord)", EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, EvenHubMsg.EV_DOUBLE_CLICK)
        assertTrue(shell.switcherIsOpen, "$height: the chord opens the wheel")
        act("a notch down", EvenHubMsg.EV_SCROLL_BOTTOM)
        act("a notch up", EvenHubMsg.EV_SCROLL_TOP)
        act("up 1", EvenHubMsg.EV_SCROLL_TOP)
        act("up 2", EvenHubMsg.EV_SCROLL_TOP)
        act("up 3, the wrap", EvenHubMsg.EV_SCROLL_TOP)
        act("commit", EvenHubMsg.EV_CLICK)
        assertTrue(!shell.switcherIsOpen, "$height: the tap commits")
        act("open again", EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, EvenHubMsg.EV_DOUBLE_CLICK)
        assertTrue(shell.switcherIsOpen, "$height: the chord opens the wheel again")
        act("cancel", EvenHubMsg.EV_DOUBLE_CLICK)
        assertTrue(!shell.switcherIsOpen, "$height: the double tap cancels")
        on.set(false)
        shell.stop()
        return out.toList()
    }

    private fun subs(img: ByteArray): List<ByteArray> {
        if (img.isEmpty() || img[0].toInt() != 8) return listOf(img)
        val out = ArrayList<ByteArray>()
        var i = 2
        repeat(img[1].toInt() and 0xFF) {
            val n = (img[i].toInt() and 0xFF) or ((img[i + 1].toInt() and 0xFF) shl 8)
            out += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n
        }
        return out
    }

    private fun file(name: String): List<String>? = javaClass.getResource("/wheelwire/$name")?.readText()?.lines()?.filter { it.isNotBlank() }

    @Test
    fun theSwitchersBytesBelowStage5AreTheTreesBeforeDw_DW0(): Unit = runBlocking {
        val missing = ArrayList<String>()
        for (h in listOf(352, 480)) {
            val got = acts(h)
            val name = "stage4-h$h.txt"
            val want = file(name) ?: run {
                val o = Paths.get("build", "wheelwire").also { Files.createDirectories(it) }.resolve(name)
                Files.write(o, got)
                missing += "no recording $name: this run's messages written to ${o.toAbsolutePath()}"
                got
            }
            assertTrue(got.count { !it.startsWith("#") } > 20, "$h: the acts sent images (${got.size} lines)")
            if (want != got) {
                val i = want.indices.firstOrNull { it >= got.size || want[it] != got[it] } ?: got.size
                fail("$h: the switcher's messages at stage 4 are not the tree's before DW: first difference at line ${i + 1} of ${want.size} " +
                    "(recorded ${want.getOrNull(i)?.take(120)}, now ${got.getOrNull(i)?.take(120)})")
            }
            assertEquals(want, got)
        }
        if (missing.isNotEmpty()) fail(missing.joinToString("\n"))
    }

    /** A window whose content is rows across the whole content width (so the columns beside the wheel's panel hold ink). */
    private class Striped(id: String) : DamageWindow(id, "Striped $id", IconKind.FILES) {
        override fun view(): WindowView = WindowView.CanvasView({ g, r -> var y = r.y; while (y + 4 <= r.bottom) { g.fillRect(r.x, y, r.w, 4, 9 * 17); y += 8 } })
        override fun summary() = Summary("rows")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /** FakeText with a neighbour face of 25 rows of ink and a name face of 32 (as the desktop's Clear Sans at 17 and 21 px
     *  bold): the row below on an odd row, a centre band of 98. */
    private class OddFace : wm.damage.core.text.TextRasterizer {
        private val f = FakeText()
        override fun measure(text: String, font: wm.damage.core.text.FontSpec) = f.measure(text, font)
        override fun metrics(font: wm.damage.core.text.FontSpec) = when (font.sizePx) {
            17 -> wm.damage.core.text.FontMetrics(17, 8, 27)
            21 -> wm.damage.core.text.FontMetrics(24, 8, 34)              // the name's 32 rows of ink: a band of 98, as his face's
            else -> f.metrics(font)
        }
        override fun draw(surface: wm.damage.core.gfx.Gray8, x: Int, y: Int, text: String, font: wm.damage.core.text.FontSpec, level: Int) = f.draw(surface, x, y, text, font, level)
        override fun covers(text: String, font: wm.damage.core.text.FontSpec) = true
    }

    /** What the shell's loop holds with the wheel open: the plane map, the composed px of the 8 columns either side of the
     *  panel that hold ink, the panel, the composed rows of the panel's lower part that hold the dirty tick's columns, and per
     *  lens (left, right) the x offsets at which the glass shows the composed rows of the centre icon. */
    private class Open(val planes: List<wm.damage.core.comp.Compositor.PlaneRegion>, val lit: Int, val pn: wm.damage.core.geom.Rect,
                       val tick: List<Int>, val iconOffsets: List<Map<Int, Int>>)

    /** The centre icon's 56 rows and columns on [OddFace] (a band of 98 from the panel's y + 38; `Switcher.paintCentre`
     *  draws it at the band's top + 4, centred), and the x offset at which [lens] (4bpp, 320 B a row) shows each of its
     *  composed rows that holds ink: the first lit column within 16 of the icon on the glass less the first in [composed]. */
    private fun iconOffsets(composed: wm.damage.core.gfx.Gray8, lens: ByteArray, pn: wm.damage.core.geom.Rect): Map<Int, Int> {
        val x0 = pn.x + (pn.w - 56) / 2
        val y0 = pn.y + 38 + 4
        fun glass(x: Int, y: Int) = (lens[y * (Geometry.PANEL_W / 2) + (x shr 1)].toInt() and 0xFF).let { if (x and 1 == 0) it shr 4 else it and 0x0F }
        val out = LinkedHashMap<Int, Int>()
        for (y in y0 until y0 + 56) {
            val c = (x0 until x0 + 56).firstOrNull { wm.damage.core.gfx.Pack.level(composed[it, y]) != 0 } ?: continue
            val g = (x0 - 16 until x0 + 56 + 16).firstOrNull { glass(it, y) != 0 } ?: Int.MIN_VALUE
            out[y] = g - c
        }
        return out
    }

    /** What the shell's loop holds with the wheel open at [stage] (five striped windows, the one below the cursor dirty). */
    private suspend fun openWheel(stage: Int): Open {
        val dir = Files.createTempDirectory(tmp, "s$stage")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xDFF }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        t.devProbe("c1", "$stage")
        val p = Persistence(dir.resolve("s.json"))
        p.put("shell.settings", ShellSettings(heightMode = 352, depth = 8, cachedText = "on").toJson())
        val shell = Shell(CachedText(OddFace()), t, p, null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val ws = (0 until 5).map { Striped("w$it") }
        for (w in ws) shell.register(w)
        shell.start()
        suspend fun settle(what: String) = waitUntil(30_000, 10, { "$what: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        settle("start")
        for (w in ws) { shell.services.runOnShell { shell.services.openWindow(w.id, null) }; settle("open ${w.id}") }
        ws[2].dirty = true                        // the wheel opens on w3 (the most recent inactive); w2 is the row below
        for (g in listOf(EvenHubMsg.EV_RING_LONG_PRESS, EvenHubMsg.EV_RING_LONG_PRESS_RELEASE, EvenHubMsg.EV_DOUBLE_CLICK)) shell.postGesture(g)
        settle("the wheel")
        assertTrue(shell.switcherIsOpen, "stage $stage: the chord opens the wheel")
        var seen: Open? = null
        repeat(200) {
            if (seen == null) { settle("sample"); seen = shell.sampleIdle {
                val pn = shell.layout.switcherPanel
                val g = shell.comp.composed
                var lit = 0
                for (y in pn.y until pn.bottom) for (x in (pn.x - 8 until pn.x) + (pn.right until pn.right + 8)) if (g[x, y] != 0) lit++
                // the dirty tick of the row below: 3 px wide, right of its name, in the panel's lower part
                val tick = (pn.y + pn.h / 2 until pn.bottom - 2).filter { y -> (pn.x + 100 until pn.right - 4).any { x -> g[x, y] != 0 && g[x - 1, y] == 0 && g[x + 3, y] == 0 && g[x + 1, y] != 0 && g[x + 2, y] != 0 } }
                val offsets = listOf(Arm.LEFT, Arm.RIGHT).map { arm -> iconOffsets(g, synchronized(sim) { sim.snapshot(arm) }, pn) }
                Open(shell.comp.planes, lit, pn, tick, offsets)
            } }
        }
        shell.stop()
        return seen ?: fail("stage $stage: the shell never sat idle for a sample")
    }

    /**
     * C3 DW-0 (the DW spec §2.6, decisions BD's O2), the shell's half, on a face that puts the row below on an odd row (as
     * his own does) with that row's window dirty, over a window with rows beside the panel: at stage 5 the composed columns
     * of Global `Depth` either side of the panel are black, the plane map holds the panel with them at that depth and the
     * centre band at the screen plane as painted, and the row below sits on the even row (its dirty tick from yB − 1 + 4); at
     * stage 4 the window's rows stand beside the panel, the map is the panel without margins with the band as painted (since
     * X1W; before, the design's 44 / 88), and the tick hangs from the odd row as before.
     */
    @Test
    fun atStage5TheShellClearsTheMarginsAndMapsThePanelWithThemAndStage4IsTodays_DW0(): Unit = runBlocking {
        val o5 = openWheel(5)
        val o4 = openWheel(4)
        val p5 = o5.planes; val lit5 = o5.lit; val p4 = o4.planes; val lit4 = o4.lit
        val pn = o5.pn
        assertEquals(0, lit5, "stage 5: the margins beside the panel are black")
        assertTrue(lit4 > 0, "stage 4: the window's rows stand beside the panel ($lit4 px)")
        assertTrue(p5.any { it.rect == wm.damage.core.geom.Rect(pn.x - 8, pn.y, pn.w + 16, pn.h) && it.disparity == 8 }, "stage 5: the panel with its margins at Depth 8: $p5")
        assertTrue(p5.any { it.rect == wm.damage.core.geom.Rect(pn.x, pn.y + 38, pn.w, 98) && it.disparity == 0 }, "stage 5: the centre band at the screen plane as painted (98 rows from 38): $p5")
        assertTrue(p4.any { it.rect == pn && it.disparity == 8 } && p4.any { it.rect == wm.damage.core.geom.Rect(pn.x, pn.y + 38, pn.w, 98) && it.disparity == 0 } &&
            p4.none { it.rect == wm.damage.core.geom.Rect(pn.x - 8, pn.y, pn.w + 16, pn.h) }, "stage 4: the panel without margins at Depth 8, the band as painted (X1W): $p4")
        val yB = minOf(pn.bottom - 24, pn.bottom - 2 - 25)             // the odd face's row below
        assertEquals(1, yB % 2)
        assertEquals((yB + 4 until yB + 12).toList(), o4.tick, "stage 4: the tick hangs from the odd row, as before")
        assertEquals((yB + 3 until yB + 11).toList(), o5.tick, "stage 5: the row on the even row above it, the tick one row up")
    }

    /**
     * C3 X1W (decisions BM (7)): the centre band's plane region is the band as painted at every stage, so on a face whose band
     * is not the design's 88 rows ([OddFace]: 98, as his desktop face's) the centre icon is drawn at one plane — at Global
     * `Depth` 8, on each lens the glass shows every inked row of the centre icon at one x offset from the composed picture, at
     * stage 4 and at stage 5, and the map's band region is the band's 98 rows from the panel's y + 38 at both. Before X1W the
     * map held the band at the design's fixed 44 / 88 rows below stage 5: the icon's top two rows (the panel's y + 42 and 43)
     * lay outside it and were drawn at the panel's plane, 8 px over on each lens from the rest of the icon.
     */
    @Test
    fun theCentreIconIsDrawnAtOnePlaneOnEachLensAtEveryStage_X1W(): Unit = runBlocking {
        for (stage in listOf(4, 5)) {
            val o = openWheel(stage)
            val pn = o.pn
            for ((i, offs) in o.iconOffsets.withIndex()) {
                val lens = if (i == 0) "left" else "right"
                assertTrue(offs.size >= 40, "stage $stage, $lens lens: the centre icon's rows hold ink (${offs.size} rows)")
                val by = offs.entries.groupBy({ it.value }, { it.key }).mapValues { (_, ys) -> "rows ${ys.min() - pn.y}..${ys.max() - pn.y} of the panel (${ys.size})" }
                assertEquals(1, by.size, "stage $stage, $lens lens: the centre icon's rows are shown at more than one x offset (offset to rows: $by)")
            }
            assertTrue(o.planes.any { it.rect == wm.damage.core.geom.Rect(pn.x, pn.y + 38, pn.w, 98) && it.disparity == 0 },
                "stage $stage: the band's region is the band as painted (98 rows from the panel's y + 38): ${o.planes}")
        }
    }
}
