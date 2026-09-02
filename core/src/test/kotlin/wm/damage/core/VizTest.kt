package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.gfx.Pack
import wm.damage.core.windows.music.BarsViz
import wm.damage.core.windows.music.ScopeViz
import wm.damage.core.windows.music.Viz
import wm.damage.core.windows.music.VizData
import wm.damage.core.windows.music.VizRenderer

/**
 * The Music visualizer renderers (`Viz.kt`, MUSIC.md §3.8/§8.3/§12).
 *
 * These are pure pixel functions, so everything the hardware cares about can be
 * proved offline: the strip stays inside the rect the window lent it, the rect
 * it reports back is one the firmware would accept (an unaligned or oversized
 * mode-3 box is refused in silence and the previous frame stays up), the same
 * inputs give the same bytes — which is what lets a repaint after a window
 * switch match a continuous run — and the ink each renderer promises is the ink
 * it actually spends.
 */
class VizTest {

    // ------------------------------------------------------------------ fixtures

    /** The four shell heights' strips (MUSIC.md §8.3: 608 x 48…96, 576 wide at
     *  the narrow end) plus the smallest strip the module accepts. */
    private val strips = listOf(
        Rect(16, 0, 608, 48),      // 288
        Rect(16, 120, 608, 56),    // 352
        Rect(32, 240, 576, 72),    // 416
        Rect(16, 384, 608, 96),    // 480
        Rect(0, 0, Viz.MIN_W, Viz.MIN_H),
        Rect(18, 101, 602, 50),    // deliberately off-grid: the module snaps inward
    )

    /** Pack 0–15 levels into the frame-major nibble table VizData carries. */
    private fun nibbles(v: IntArray): ByteArray {
        val out = ByteArray((v.size + 1) / 2)
        for (i in v.indices) {
            val n = v[i].coerceIn(0, 15)
            out[i shr 1] = if (i and 1 == 0) (n shl 4).toByte()
            else (out[i shr 1].toInt() or n).toByte()
        }
        return out
    }

    /**
     * A synthetic track: [seconds] of [bands] bands at [fps], an RMS slot every
     * 20 ms and a beat every 500 ms. `loud = true` pins every level at 15, which
     * is the worst case the ink budgets have to survive; `loud = false` walks a
     * repeating ramp so neighbouring bands and frames differ.
     */
    private fun synth(fps: Int = 20, bands: Int = 24, seconds: Int = 5, loud: Boolean = false): VizData {
        val frames = fps * seconds
        val f = IntArray(frames * bands) { i ->
            if (loud) 15 else ((i / bands) * 5 + (i % bands) * 3) % 16
        }
        val slots = seconds * 50
        val rms = IntArray(slots) { if (loud) 15 else (it * 7) % 16 }
        val beats = IntArray(seconds * 2) { it * 500 }
        return VizData(fps, bands, frames, nibbles(f), slots, nibbles(rms), beats)
    }

    /** One band, one spike at frame 10, silence either side — for the gravity model. */
    private fun spike(): VizData {
        val frames = 100
        val f = IntArray(frames) { if (it == 10) 15 else 0 }
        return VizData(20, 1, frames, nibbles(f), 0, ByteArray(0), IntArray(0))
    }

    private fun paintAlone(v: VizRenderer, r: Rect, d: VizData?, ms: Long): Gray8 {
        val g = Gray8(r.w, r.h)
        v.paint(g, Rect(0, 0, r.w, r.h), d, ms)
        return g
    }

    // ------------------------------------------------------------------ the contract

    @Test
    fun everyRendererPaintsInsideItsRectOnly() {
        val d = synth()
        for (v in Viz.ALL) for (r in strips) {
            val g = Gray8(640, 480)
            g.clear(200)                                  // a sentinel the renderer must not touch
            v.paint(g, r, d, 2345L)
            for (y in 0 until g.h) for (x in 0 until g.w) {
                if (x >= r.x && x < r.right && y >= r.y && y < r.bottom) continue
                assertEquals(200, g[x, y], "${v.name} painted outside $r at ($x,$y)")
            }
        }
    }

    @Test
    fun everyRendererReturnsOneAlignedRectInsideItsStrip() {
        val d = synth()
        for (v in Viz.ALL) for (r in strips) for (ms in listOf(0L, 137L, 2500L, 4990L)) {
            val g = Gray8(640, 480)
            val got = v.paint(g, r, d, ms)
            assertTrue(r.contains(got), "${v.name} at $r reported $got, which is not inside the strip")
            assertEquals(emptyList(), Geometry.checkRect(got, "${v.name} damage"),
                "${v.name} reported a box the firmware would refuse without a word")
        }
    }

    @Test
    fun theReportedRectHoldsEveryPixelTheRendererCanLight() {
        // The rect is the shell's ONE mode-3 delta box for the surface. If a
        // frame lit anything outside it, that pixel would never be pushed and
        // the strip would carry a stale mark for as long as the surface is up.
        val d = synth()
        for (v in Viz.ALL) for (r in strips) {
            val g = Gray8(640, 480)
            var reported: Rect? = null
            for (ms in 0L..5000L step 97L) {
                val got = v.paint(g, r, d, ms)
                if (reported == null) reported = got
                assertEquals(reported, got, "${v.name} moved its damage box between frames")
                for (y in r.y until r.bottom) for (x in r.x until r.right) {
                    if (g[x, y] == Level.BG) continue
                    assertTrue(x >= got.x && x < got.right && y >= got.y && y < got.bottom,
                        "${v.name} lit ($x,$y) outside the $got it reported")
                }
            }
        }
    }

    @Test
    fun everyRendererIsDeterministic() {
        val d = synth()
        for (v in Viz.ALL) for (r in strips) {
            val a = Gray8(640, 480)
            val b = Gray8(640, 480)
            b.clear(200)                                  // different prior content
            v.paint(a, r, d, 1234L)
            v.paint(b, r, d, 1234L)
            assertTrue(a.regionEquals(b, r),
                "${v.name} at $r depends on what was under it — the clear is not doing its job")
            val c = Gray8(640, 480)
            v.paint(c, r, d, 1234L)
            assertTrue(a.pix.contentEquals(c.pix), "${v.name} at $r is not byte-for-byte repeatable")
        }
    }

    @Test
    fun inkStaysUnderBudget() {
        val loud = synth(loud = true)
        for (v in Viz.ALL) for (r in strips) {
            var worst = 0.0
            for (ms in 0L..4990L step 43L) {
                val ink = Pack.inkFraction(paintAlone(v, r, loud, ms))
                if (ink > worst) worst = ink
            }
            assertTrue(worst <= v.inkBudget,
                "${v.name} spends ${"%.3f".format(worst)} ink at ${r.w}x${r.h}, over its " +
                    "promised ${v.inkBudget}")
            assertTrue(worst > 0.05,
                "${v.name} at ${r.w}x${r.h} drew almost nothing for a full-scale signal " +
                    "(${"%.3f".format(worst)}) — the budget is not being tested")
        }
    }

    @Test
    fun nullAndShortDataPaintTheRestingForm() {
        // A track with one frame and one RMS slot, and an empty beat list: the
        // shapes every renderer has to survive while the blob is still arriving.
        val short = VizData(20, 24, 1, nibbles(IntArray(24) { 9 }), 1, nibbles(intArrayOf(9)), IntArray(0))
        val empty = VizData(20, 24, 0, ByteArray(0), 0, ByteArray(0), IntArray(0))
        for (v in Viz.ALL) for (r in strips) for (d in listOf(null, short, empty)) {
            for (ms in listOf(0L, 20L, 100_000L)) {
                val g = paintAlone(v, r, d, ms)
                val ink = Pack.inkFraction(g)
                assertTrue(ink > 0.0,
                    "${v.name} went dark for ${if (d == null) "null" else "short"} data — the " +
                        "resting form is what says the strip is alive and empty")
                assertTrue(ink <= v.inkBudget, "${v.name} resting form is over budget")
            }
        }
    }

    @Test
    fun aFrameTableShorterThanItsHeaderClaimsDoesNotEndThePaint() {
        // A truncated blob is a real arrival state, and the contract is that it
        // paints what it holds rather than stopping the frame.
        val lying = VizData(20, 24, 500, ByteArray(40), 250, ByteArray(4), IntArray(0))
        for (v in Viz.ALL) {
            val ink = Pack.inkFraction(paintAlone(v, Rect(16, 0, 608, 48), lying, 3000L))
            assertTrue(ink > 0.0 && ink <= v.inkBudget, "${v.name} on a truncated blob: ink $ink")
        }
    }

    @Test
    fun beforeTheTrackStartsNothingIsInvented() {
        // A negative position happens (a seek that lands before zero, a lyric
        // offset applied to the first bar). Every renderer must draw exactly the
        // resting form there — not frame 0 smeared backwards, which is what the
        // clamping accessors on VizData would give.
        val d = synth()
        for (v in Viz.ALL) for (r in strips) {
            val withData = paintAlone(v, r, d, -500L)
            val withNone = paintAlone(v, r, null, -500L)
            assertTrue(withData.pix.contentEquals(withNone.pix),
                "${v.name} at $r drew something before the track started")
        }
    }

    @Test
    fun scopePutsTheNewestColumnAtTheRightEdge() {
        assertEquals(3040L, (608 / ScopeViz.COL_PITCH) * ScopeViz.MS_PER_COL,
            "a 608 px strip should hold ~3 s of envelope; changing the pitch changes the span")

        // Silence everywhere except the 40 ms that end at posMs.
        val slots = 250
        val rms = IntArray(slots) { if (it == 149 || it == 150) 15 else 0 }
        val d = VizData(20, 24, 0, ByteArray(0), slots, nibbles(rms), IntArray(0))
        val r = Rect(16, 0, 608, 48)
        val g = paintAlone(ScopeViz, r, d, 3000L)

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        for (y in 0 until g.h) for (x in 0 until g.w) {
            if (g[x, y] < Level.MID) continue             // the FAINT zero line is not the wave
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        assertEquals(r.w - ScopeViz.COL_W, minX, "the lit column does not start one column in from the right")
        assertEquals(r.w - 1, maxX, "the newest column is not flush against the right edge")

        // ...and the same burst two seconds in the past is nowhere near the edge.
        val older = paintAlone(ScopeViz, r, d, 5000L)
        for (y in 0 until older.h) for (x in r.w - ScopeViz.COL_W until r.w)
            assertTrue(older[x, y] < Level.MID, "a two-second-old burst is still at the right edge")
    }

    @Test
    fun barsFallUnderGravityAndTheCapHoldsLonger() {
        val d = spike()
        val r = Rect(16, 0, 608, 48)
        val onBeat = Pack.inkFraction(paintAlone(BarsViz, r, d, 500L))    // frame 10
        val soonAfter = Pack.inkFraction(paintAlone(BarsViz, r, d, 600L)) // frame 12
        val longAfter = Pack.inkFraction(paintAlone(BarsViz, r, d, 1500L))// frame 30
        val floorOnly = Pack.inkFraction(paintAlone(BarsViz, r, null, 500L))
        assertTrue(onBeat > soonAfter, "the bar did not fall (ink $onBeat -> $soonAfter)")
        assertTrue(soonAfter > longAfter, "the bar did not keep falling (ink $soonAfter -> $longAfter)")
        assertTrue(longAfter > floorOnly,
            "the peak cap should still be up a second later — it falls at 5 levels/s, not 32")
    }

    @Test
    fun aStripTooSmallOrOffTheSurfaceIsRefusedLoudly() {
        val g = Gray8(640, 480)
        val d = synth()
        for (v in Viz.ALL) {
            assertFailsWith<LintError>("${v.name} accepted a strip narrower than it can draw") {
                v.paint(g, Rect(0, 0, Viz.MIN_W - 4, 48), d, 0L)
            }
            assertFailsWith<LintError>("${v.name} accepted a strip shorter than it can draw") {
                v.paint(g, Rect(0, 0, 608, Viz.MIN_H - 2), d, 0L)
            }
            assertFailsWith<LintError>("${v.name} accepted a strip hanging off the surface") {
                v.paint(g, Rect(100, 440, 608, 48), d, 0L)
            }
            assertFailsWith<LintError>("${v.name} accepted a strip with no extent") {
                v.paint(g, Rect(0, 0, 0, 0), d, 0L)
            }
        }
    }

    @Test
    fun theSettingsRowMapsOntoTheRenderers() {
        assertEquals(listOf("Off", "Bars", "Scope", "Pulse", "Meter"), Viz.NAMES)
        for (v in Viz.ALL) {
            assertEquals(v, Viz.byName(v.name))
            assertEquals(v, Viz.byName(v.name.lowercase()), "settings strings round-trip case-insensitively")
            assertEquals(v, Viz.byName(" ${v.name} "))
        }
        assertNull(Viz.byName("Off"), "Off is the absence of a renderer, not one of them")
        assertNull(Viz.byName("Spectrogram"), "an unknown name from a newer build is not a renderer")
        assertEquals(48, Viz.stripHeight(288))
        assertEquals(56, Viz.stripHeight(352))
        assertEquals(72, Viz.stripHeight(416))
        assertEquals(96, Viz.stripHeight(480))
        for (h in listOf(288, 352, 416, 480)) {
            val sh = Viz.stripHeight(h)
            assertEquals(0, sh % Geometry.Y_STEP, "the strip height must sit on the damage grid")
            assertTrue(sh in Viz.MIN_H..h, "the strip must fit the panel it is drawn on")
        }
    }
}
