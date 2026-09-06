package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.DocModel
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.Slide
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §40 — time to first visible change (`DESIGN.md` §8.6). A
 * gesture's answer is priced by its FIRST flush: on the phone path ~70 ms
 * plus ~120 ms per KB. The 0.32 walk measured a list notch at 830–860 ms to
 * its first change (the lens repaint rode the first flush), a Reader notch
 * at 221–625 ms (a 1.2–4.3 KB strip), a tmux history notch at 352–645 ms
 * (one 2.4–4.3 KB flush). Now the first flush is the translation — copies
 * and, where the strip is worth a flush of its own, a blank strip — and the
 * heavy bytes follow one message on. Belief equals glass throughout: the
 * split changes WHEN pixels go out, never what the glass ends up showing.
 */
class FirstFlushTest {

    private companion object {
        const val LINE = "the quick brown fox jumps over the lazy dog and keeps going down the page"
    }

    /** Records every flush the shell submits, in order. */
    private class SpyTransport(private val inner: Transport) : Transport by inner {
        val flushes = ArrayList<FlushRequest>()
        override suspend fun submit(flush: FlushRequest): Long {
            synchronized(flushes) { flushes.add(flush) }
            return inner.submit(flush)
        }
        fun count() = synchronized(flushes) { flushes.size }
        fun at(i: Int) = synchronized(flushes) { flushes[i] }
    }

    private fun bytesOf(f: FlushRequest): Int = f.ops.sumOf {
        when (it) {
            is DisplayOp.Keyframe -> it.payload.size
            is DisplayOp.Delta -> it.payload.size
            is DisplayOp.StereoPair -> it.payload.size
            is DisplayOp.Copy -> 0
            is DisplayOp.DrawText -> 9 + it.text.size
            is DisplayOp.DrawImage -> 8
            is DisplayOp.CacheWrite -> it.payload.size
        }
    }

    private fun opsText(f: FlushRequest): String = f.ops.joinToString(" ") {
        when (it) {
            is DisplayOp.Keyframe -> "KF(${it.payload.size}B)"
            is DisplayOp.Delta -> "D${it.box}(${it.payload.size}B)"
            is DisplayOp.StereoPair -> "SP${it.left}(${it.payload.size}B)"
            is DisplayOp.Copy -> "C${it.src}->${it.dst}"
            is DisplayOp.DrawText -> "T(${it.x},${it.y})"
            is DisplayOp.DrawImage -> "I(${it.x},${it.y})"
            is DisplayOp.CacheWrite -> "CW(${it.payload.size}B)"
        }
    }

    private fun deltasTouching(f: FlushRequest, r: Rect): Int = f.ops.count {
        (it is DisplayOp.Delta && it.box.overlaps(r)) ||
            (it is DisplayOp.StereoPair && (it.left.overlaps(r) || it.right.overlaps(r)))
    }

    private class Rig(scope: CoroutineScope, vararg windows: DamageWindow) {
        val tmp = Files.createTempDirectory("damage-first")
        val sim = GlassFirmwareSim()
        val spy = SpyTransport(SimTransport(sim, scope, SimTransport.Timing(instant = true)))
        val shell = Shell(GlyphyText(), spy, Persistence(tmp.resolve("state.json")), null, scope)

        init { for (w in windows) shell.register(w) }

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) return
                delay(10)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(640, 480)
            for (y in 0 until 480) for (x in 0 until 640) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        fun assertGlassMatchesBelief(what: String) {
            for (left in booleanArrayOf(true, false)) {
                val e = shell.comp.expectedLens(left); val g = lens(left)
                var diffs = 0
                for (y in 0 until 480) for (x in 0 until 640) {
                    val q = minOf(15, (e[x, y] + 8) / 17) * 17
                    if (q != g[x, y]) diffs++
                }
                assertEquals(0, diffs, "$what: ${if (left) "L" else "R"} belief != glass ($diffs px)")
            }
        }
    }

    private class RowsWindow : DamageWindow("rows", "Rows", IconKind.FILES) {
        private val model = ListModel()
        override fun view(): WindowView = WindowView.ListView(model, { 30 },
            paintRow = { g, i, r, _ -> g.fillRect(r.x + 8, r.y + 6, 80 + (i * 37) % 300, 14, ((i % 12) + 2) * 17) },
            paintLens = { g, r, i ->
                // the lens is the HEAVY part of a notch: a bold title and a detail line
                g.fillRect(r.x + 8, r.y + 6, 400, 20, 15 * 17)
                g.fillRect(r.x + 8, r.y + 30, 300 + (i * 53) % 200, 16, ((i % 10) + 3) * 17)
            },
            onCommit = {})
        override fun summary() = Summary("30 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** Lines of glyph-like text (many short runs per row, as real text
     *  compresses) — solid boxes would compress to nothing and hide the
     *  weight of a fill. */
    private class DocWindow : DamageWindow("doc", "Doc", IconKind.READER) {
        private val doc = DocModel()
        private val tx = GlyphyText()
        private val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 18)
        override fun view() = WindowView.DocView(doc, { 200 }, 24,
            { g, i, r -> tx.draw(g, r.x + 4, r.y + 4, "line $i " + LINE.substring(i % 7), f, ((i % 10) + 3) * 17) },
            {}, stepLines = { 3 })
        override fun summary() = Summary("doc")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** A column of lines the window shifts itself — the terminal's shape. */
    private class CanvasWindow : DamageWindow("canvas", "Canvas", IconKind.TERMINAL) {
        var top = 0
        private val tx = GlyphyText()
        private val f = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 16)
        override fun view() = WindowView.CanvasView(
            paint = { g, r ->
                g.fillRect(r.x, r.y, r.w, r.h, 0)
                var y = r.y
                var line = top
                while (y + 20 <= r.bottom) {
                    tx.draw(g, r.x + 4, y + 3, "$line: " + LINE.substring(line % 11), f, ((line % 9) + 4) * 17)
                    y += 20; line++
                }
            },
            onScroll = { d -> top = maxOf(0, top + d * 4) },
        )
        override fun summary() = Summary("canvas")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    @Test
    fun aListNotchSendsTheTranslationFirstAndTheLensSecond(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope, RowsWindow())
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            assertEquals("rows", rig.shell.currentWindowId())
            val lens = rig.shell.layout.lens
            val n0 = rig.spy.count()
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch")
            assertTrue(rig.spy.count() >= n0 + 2, "a notch is at least two flushes now (${rig.spy.count() - n0})")
            val first = rig.spy.at(n0)
            val second = rig.spy.at(n0 + 1)
            assertTrue(first.ops.any { it is DisplayOp.Copy }, "the first flush carries the band copies: ${first.ops.map { it::class.simpleName }}")
            assertEquals(0, deltasTouching(first, lens), "the lens repaint is NOT in the first flush")
            assertTrue(bytesOf(first) < 500, "the first flush is small: ${bytesOf(first)} B")
            assertTrue(deltasTouching(second, lens) > 0, "the lens repaint rides the second flush: ${second.ops.map { it::class.simpleName }}")
            rig.assertGlassMatchesBelief("after the notch")
            // a spin of three: still correct, the lens painted for the last cursor
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            rig.settle("spin")
            rig.assertGlassMatchesBelief("after the spin")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aDocumentNotchSendsTheCopyAndABlankStripFirstAndTheFillSecond(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope, DocWindow())
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.settle("open doc")
            val n0 = rig.spy.count()
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch")
            assertTrue(rig.spy.count() >= n0 + 2, "a notch is at least two flushes (${rig.spy.count() - n0})")
            val first = rig.spy.at(n0)
            val second = rig.spy.at(n0 + 1)
            assertTrue(first.ops.any { it is DisplayOp.Copy }, "the first flush is the translation: ${first.ops.map { it::class.simpleName }}")
            // 3 lines × 24 px = 72 px: the first ease-out step is a 36 px strip
            // (> SPLIT_FILL_PX), sent blank — a uniform run compresses to nothing
            assertTrue(bytesOf(first) < 400, "the blank strip costs almost nothing: ${bytesOf(first)} B — ${opsText(first)}")
            assertTrue(bytesOf(second) > bytesOf(first) * 2, "the fill is the heavy flush: ${bytesOf(first)} then ${bytesOf(second)} B")
            rig.assertGlassMatchesBelief("after the notch")
            // the second notch, with the planner's starvation fixed (§40): the
            // FIRST notch after a window opens used to ship the whole band
            // because the input echo's eleven pieces left the content plane a
            // share of one rect — both notches must read the same
            val n1 = rig.spy.count()
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch 2")
            val first2 = rig.spy.at(n1)
            assertTrue(first2.ops.any { it is DisplayOp.Copy } && bytesOf(first2) < 400,
                "the second notch's first flush is the translation too: ${bytesOf(first2)} B — ${opsText(first2)}")
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            rig.settle("spin")
            rig.assertGlassMatchesBelief("after the spin")
            repeat(2) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }
            rig.settle("back")
            rig.assertGlassMatchesBelief("after scrolling back")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aCanvasNotchSendsTheCopyAndABlankStripFirstAndTheFillSecond(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rig = Rig(scope, CanvasWindow())
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("canvas", null) }
            rig.settle("open canvas")
            val n0 = rig.spy.count()
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch")
            assertTrue(rig.spy.count() >= n0 + 2, "a notch is at least two flushes (${rig.spy.count() - n0})")
            val first = rig.spy.at(n0)
            val second = rig.spy.at(n0 + 1)
            assertTrue(first.ops.any { it is DisplayOp.Copy }, "the detector's translation leads: ${first.ops.map { it::class.simpleName }}")
            assertTrue(bytesOf(first) < 400, "the blank strip costs almost nothing: ${bytesOf(first)} B — ${opsText(first)}")
            assertTrue(bytesOf(second) > bytesOf(first) * 2, "the fill is the heavy flush: ${bytesOf(first)} then ${bytesOf(second)} B")
            rig.assertGlassMatchesBelief("after the notch")
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            rig.settle("spin")
            rig.assertGlassMatchesBelief("after the spin")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------ the slide's frame rule
    private fun frames(distance: Int, n: Int?, regionH: Int = 160): List<Int> {
        val comp = Compositor()
        val region = Rect(16, 50, 320, regionH)
        val slide = Slide(comp, region) { g, _, _ -> g.fillRect(0, 0, g.w, g.h, 0) }
        slide.frames = n
        slide.retarget(distance)
        val steps = ArrayList<Int>()
        var guard = 0
        while (slide.active && guard++ < 64) {
            val before = slide.offsetPx
            slide.fillDeferred(comp.composed)
            if (slide.offsetPx != 0) slide.step(comp.composed)
            if (slide.offsetPx != before) steps.add(kotlin.math.abs(before - slide.offsetPx))
        }
        return steps
    }

    @Test
    fun theFrameRuleResamplesTheEaseOut() {
        assertEquals(listOf(16, 8, 8), frames(32, null), "auto: the halving rule with its 8 px floor (a list row)")
        assertEquals(listOf(32), frames(32, 1), "off: one frame")
        assertEquals(listOf(16, 16), frames(32, 2))
        assertEquals(listOf(16, 8, 4, 4), frames(32, 4))
        assertEquals(listOf(16, 8, 4, 2, 2), frames(32, 8), "the 2 px grid ends a 32 px slide in five")
        assertEquals(listOf(50, 26, 12, 8, 4), frames(100, null), "auto on a 100 px document notch")
        assertEquals(listOf(50, 26, 12, 6, 4, 2), frames(100, 12), "twelve asked, six possible on the grid")
        assertEquals(listOf(50, 50), frames(100, 2))
        for (n in listOf(null, 1, 2, 4, 8, 12)) for (d in listOf(8, 32, 72, 100)) {
            assertEquals(d, frames(d, n).sum(), "frames=$n distance=$d: the steps cover the distance exactly")
        }
    }

    @Test
    fun theSettingClampsToItsLadder() {
        assertEquals("auto", ShellSettings().slideFrames)
        assertEquals(null, ShellSettings().slideFrameCount())
        assertEquals(1, ShellSettings(slideFrames = "off").slideFrameCount())
        assertEquals(12, ShellSettings(slideFrames = "12").slideFrameCount())
        assertEquals("auto", ShellSettings(slideFrames = "3").clamped().slideFrames, "off the ladder → auto")
        assertEquals(listOf("off", "2", "4", "auto", "8", "12"), ShellSettings.SLIDE_FRAMES)
    }
}
