package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.shell.Shell
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Slide
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.text.Wrap
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/** A deterministic monospace rasterizer: every glyph is [advance] px wide and
 *  paints a solid block — enough for layout logic without platform fonts. */
class FakeText(private val advance: Int = 8) : TextRasterizer {
    override fun measure(text: String, font: FontSpec): Int = text.length * advance
    override fun metrics(font: FontSpec) = FontMetrics(12, 4, 16)
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        for ((i, ch) in text.withIndex()) {
            if (ch != ' ') surface.fillRect(x + i * advance, y + 2, advance - 2, 10, level)
        }
    }
    override fun covers(text: String, font: FontSpec) = true
}

class SlideEquivalenceTest {
    /** After any sequence of steps and retargets, the slid band must equal a
     *  direct paint of the target state (the Slide.kt doc contract). */
    @Test
    fun slideEndsByteIdenticalToDirectPaint() {
        val comp = Compositor()
        val region = Rect(16, 50, 320, 160)
        var offsetRows = 0
        fun paintSlice(g: Gray8, y0: Int, h: Int) {
            g.fillRect(0, 0, g.w, g.h, 0)
            var slot = Math.floorDiv(y0, 32)
            while (slot * 32 < y0 + h) {
                val row = offsetRows + slot
                g.fillRect(4, slot * 32 - y0 + 2, 200, 28, ((row % 15) + 1) * 17)
                slot++
            }
        }
        val g = comp.composed
        val slide = Slide(comp, region) { s, y0, h -> paintSlice(s, y0, h) }
        // paint the initial state
        val init = Gray8(region.w, region.h)
        paintSlice(init, 0, region.h)
        g.blit(init, Rect(0, 0, region.w, region.h), region.x, region.y)

        // scroll down 2 rows, retarget +1 mid-flight, then 1 up
        offsetRows = 2; slide.retarget(64)
        slide.step(g)
        offsetRows = 3; slide.retarget(32)
        while (slide.step(g)) { /* run to settle */ }
        offsetRows = 2; slide.retarget(-32)
        while (slide.step(g)) { }
        assertEquals(0, slide.offsetPx)

        val direct = Gray8(region.w, region.h)
        paintSlice(direct, 0, region.h)
        val actual = Gray8(region.w, region.h)
        actual.blit(g, region, 0, 0)
        assertContentEquals(direct.pix, actual.pix, "slide end state != direct paint")
    }

    @Test
    fun hugeBacklogJumpCuts() {
        val comp = Compositor()
        val region = Rect(0, 0, 64, 64)
        var v = 0
        val slide = Slide(comp, region) { g, _, _ -> g.fillRect(0, 0, g.w, g.h, v) }
        v = 9 * 17
        slide.retarget(1000)          // way past the region height
        assertTrue(!slide.step(comp.composed))
        assertEquals(0, slide.offsetPx)
        assertEquals(9 * 17, comp.composed[10, 10])
    }
}

class WrapEdgeTest {
    private val t = FakeText(advance = 8)
    private val f = FontSpec(Face.SYSTEM, 16)

    @Test
    fun oversizeWordTerminatesAndKeepsEveryChar() {
        // width fits 3 chars; a 10-char word must break into ceil(10/3) lines
        val lines = Wrap.wrap("abcdefghij", f, t, 24)
        assertEquals("abcdefghij", lines.joinToString(""))
        assertTrue(lines.all { it.length <= 3 })
    }

    @Test
    fun widthNarrowerThanOneGlyphStillTerminates() {
        // 4 px wide, 8 px glyphs: each char ships oversize, one per line
        val lines = Wrap.wrap("abc", f, t, 4)
        assertEquals(listOf("a", "b", "c"), lines)
    }

    @Test
    fun zeroWidthIsLoud() {
        assertFailsWith<LintError> { Wrap.wrap("hi", f, t, 0) }
    }
}

class TransportGateTest {
    /** Stock firmware answers the settings READ with no EVENCFW field: the
     *  gate must refuse LOUDLY, not hang (review round 1). */
    @Test
    fun stockFirmwareIsRefusedLoudly(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            sim.capabilityString = "not-a-cfw"
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            assertFailsWith<LintError> { t.start(byteArrayOf(6, 0, 0)) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingRequiredTokenIsRefused(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            sim.capabilityString = "EVENCFW/8 img576 imgz rle wakelease"   // no img640/directfb/fbguard
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val e = assertFailsWith<LintError> { t.start(byteArrayOf(6, 0, 0)) }
            assertTrue("img640" in (e.message ?: ""))
        } finally {
            scope.cancel()
        }
    }

    /** Three flushes pipeline through and complete in order with no faults. */
    @Test
    fun flushesPipelineAndComplete(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val done = ArrayList<Long>()
            var faults = 0
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect {
                    if (it is TransportEvent.FlushDone) synchronized(done) {
                        assertTrue(it.ok, "flush ${it.id} failed: ${it.error}")
                        done.add(it.id)
                    }
                    if (it is TransportEvent.Fault && it.what != "warmup") faults++
                }
            }
            val g = Gray8(640, 480)
            t.start(wm.damage.core.gfx.Zl.encodeCfw(wm.damage.core.gfx.Pack.rect(g, Rect(0, 0, 640, 480))))
            // keyframe then two deltas
            t.submit(FlushRequest(listOf(DisplayOp.Keyframe(
                wm.damage.core.gfx.Zl.encodeCfw(wm.damage.core.gfx.Pack.rect(g, Rect(0, 0, 640, 480))))),
                1, wide = true))
            g.fillRect(100, 100, 40, 20, 8 * 17)
            val p = wm.damage.core.gfx.Zl.encodeCfw(wm.damage.core.gfx.Pack.rect(g, Rect(100, 100, 40, 20)))
            t.submit(FlushRequest(listOf(DisplayOp.Delta(Rect(100, 100, 40, 20), p)), 2))
            t.submit(FlushRequest(listOf(DisplayOp.Delta(Rect(200, 100, 40, 20), p)), 3))
            val t0 = System.currentTimeMillis()
            while (synchronized(done) { done.size } < 3 && System.currentTimeMillis() - t0 < 10_000) delay(10)
            assertEquals(listOf(1L, 2L, 3L), synchronized(done) { done.toList() })
            assertEquals(0, faults)
        } finally {
            scope.cancel()
        }
    }
}

class ShellPersistenceGateTest {
    /** §9.1 #4 verbatim: switch away, switch back, the composed CONTENT is
     *  byte-identical — turning "I had to push for this repeatedly" into a
     *  test that fails loudly. */
    @Test
    fun switchAwayAndBackIsByteIdentical(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-gate")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sim = GlassFirmwareSim()
            val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
            val text = FakeText()
            val clock = Shell.LocalClock(12, 0, "12:00", "PM")
            val shell = Shell(text, transport, Persistence(tmp.resolve("s.json")), null, scope) { clock }
            shell.start()
            settle(shell)

            // into Settings (the only always-present window) and one scroll deep
            shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)     // wrap up to Settings
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)          // enter Settings
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)  // move its cursor
            settle(shell)

            val content = shell.layout.content
            val before = Gray8(content.w, content.h)
            before.blit(shell.comp.composed, content, 0, 0)

            // away via the switcher: with [Main, Settings(current)] the cursor
            // opens on Main; tap commits it
            shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
            assertEquals(null, shell.currentWindowId(), "switcher commit should have landed on Main")
            // and back: from Main the cursor opens on the most recent inactive
            shell.postGesture(EvenHubMsg.EV_RING_LONG_PRESS)
            settle(shell)
            shell.postGesture(EvenHubMsg.EV_CLICK)
            settle(shell)
            assertEquals("settings", shell.currentWindowId(), "test could not navigate back to Settings")

            val after = Gray8(content.w, content.h)
            after.blit(shell.comp.composed, content, 0, 0)
            assertContentEquals(before.pix, after.pix,
                "switch-away-and-back changed the composed content (§9.1 regression)")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    private suspend fun settle(shell: Shell) {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 10_000) delay(10)
        assertTrue(shell.isQuiescent(), "shell did not settle")
    }
}
