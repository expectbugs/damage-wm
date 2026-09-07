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
import wm.damage.core.comp.CachedText
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §41 — the texture cache on EVERY plane, and the keyframe
 * that seeds only the screen plane. A cached draw is flat (modes 13/14
 * ignore the lens bit), so a rect on a depth plane ships as a flat clear
 * widened by the disparity, the draws at nominal x, and one per-lens copy
 * (mode 9's stereo form) that slides each lens's copy to its own x. The
 * proof is in lens space and belief equals glass throughout, the simulator
 * copying rects and drawing cached glyphs the way the firmware does.
 */
class PlaneCacheTest {

    private class SpyTransport(private val inner: Transport) : Transport by inner {
        val flushes = ArrayList<FlushRequest>()
        override suspend fun submit(flush: FlushRequest): Long {
            synchronized(flushes) { flushes.add(flush) }
            return inner.submit(flush)
        }
        fun all() = synchronized(flushes) { flushes.toList() }
    }

    private class Rig(scope: CoroutineScope, val text: CachedText, vararg windows: DamageWindow) {
        val tmp = Files.createTempDirectory("damage-planecache")
        val sim = GlassFirmwareSim()
        val spy = SpyTransport(SimTransport(sim, scope, SimTransport.Timing(instant = true)))
        val shell = Shell(text, spy, Persistence(tmp.resolve("state.json")), null, scope)

        init { for (w in windows) shell.register(w) }

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) return
                delay(10)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }

        suspend fun until(what: String, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < 10_000) delay(10)
            assertTrue(cond(), "$what — live=${shell.cachedFontsLive} active=${shell.cachedTextActive} flushes=${all().size}")
        }

        fun all() = spy.all()

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
                var diffs = 0; var first: String? = null
                for (y in 0 until 480) for (x in 0 until 640) {
                    val q = minOf(15, (e[x, y] + 8) / 17) * 17
                    if (q != g[x, y]) { diffs++; if (first == null) first = "($x,$y) belief $q glass ${g[x, y]}" }
                }
                assertEquals(0, diffs, "$what: ${if (left) "L" else "R"} belief != glass ($diffs px, first $first)")
            }
        }

        suspend fun cacheOn() {
            shell.updateSettings { it.copy(cachedText = "on") }
            until("the atlas uploads and the fonts go live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            settle("after the upload")
            assertGlassMatchesBelief("after the upload's repaint")
        }
    }

    private class RowsWindow(private val tx: TextRasterizer) : DamageWindow("rows", "Rows", IconKind.FILES) {
        private val model = ListModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        private val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        override fun view(): WindowView = WindowView.ListView(model, { 30 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 5, "Row $i and its detail text", f, Level.BODY) },
            paintLens = { g, r, i ->
                tx.draw(g, r.x + 44, r.y + 8, "Row $i", fB, Level.HEAD)
                tx.draw(g, r.x + 44, r.y + 34, "the detail line of row $i", f, Level.BODY)
            },
            onCommit = {})
        override fun summary() = Summary("30 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private fun draws(f: FlushRequest) = f.ops.count { it is DisplayOp.DrawText }
    private fun pairs(f: FlushRequest) = f.ops.count { it is DisplayOp.CopyPair }
    private fun pixelBytes(f: FlushRequest): Int = f.ops.sumOf {
        when (it) {
            is DisplayOp.Delta -> it.payload.size
            is DisplayOp.StereoPair -> it.payload.size
            is DisplayOp.Keyframe -> it.payload.size
            else -> 0
        }
    }
    private fun shape(f: FlushRequest) = f.ops.joinToString(" ") {
        when (it) {
            is DisplayOp.Keyframe -> "KF(${it.payload.size})"
            is DisplayOp.Delta -> "D${it.box}(${it.payload.size},d${it.disparity})"
            is DisplayOp.StereoPair -> "SP(${it.payload.size})"
            is DisplayOp.Copy -> "C"
            is DisplayOp.CopyPair -> "CP${it.srcL}>${it.dstL}"
            is DisplayOp.DrawText -> "T(${it.x},${it.y})"
            is DisplayOp.DrawImage -> "I"
            is DisplayOp.CacheWrite -> "CW"
        }
    }

    /** Rows at depth 8 (the default ladder: content 8, lens 4, bars 8) ship
     *  as draws plus a per-lens copy — every plane, not just the screen's. */
    @Test
    fun textOnADepthPlaneShipsAsDrawsPlusOnePerLensCopyWithBeliefEqualToGlass(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(GlyphyText())
            val rig = Rig(scope, text, RowsWindow(text))
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            assertEquals(8, rig.shell.comp.planes.lastOrNull { it.rect == rig.shell.layout.content }?.disparity, "rows sit on plane 8")
            rig.cacheOn()

            val n0 = rig.all().size
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch")
            val notch = rig.all().drop(n0)
            val withPairs = notch.filter { pairs(it) > 0 && draws(it) > 0 }
            assertTrue(withPairs.isNotEmpty(), "a depth-plane rect shipped as draws + a per-lens copy: ${notch.map(::shape)}")
            rig.assertGlassMatchesBelief("after the notch")
            // the strips the slide paints into a temp are recorded too (§41):
            // the band's row text arrives as draws, not pixels
            assertTrue(notch.any { f -> f.ops.any { it is DisplayOp.DrawText && it.y >= rig.shell.layout.content.y && it.y < rig.shell.layout.lens.y } ||
                f.ops.any { it is DisplayOp.DrawText && it.y >= rig.shell.layout.lens.bottom } },
                "a row strip shipped as draws: ${notch.map(::shape)}")
            repeat(6) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            rig.settle("spin")
            rig.assertGlassMatchesBelief("after the spin")
            repeat(4) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }
            rig.settle("back")
            rig.assertGlassMatchesBelief("after scrolling back")
            // a whole settled notch costs a fraction of its pixels
            val n1 = rig.all().size
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("priced notch")
            val priced = rig.all().drop(n1)
            val pixels = priced.sumOf(::pixelBytes)
            assertTrue(pixels < 600, "a notch's pixel bytes are the clears and the blanks, not the text: $pixels B in ${priced.map(::shape)}")
            rig.assertGlassMatchesBelief("after the priced notch")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /** At every depth on the ladder, with an app on its own plane too, the
     *  cache serves and the oracle holds. */
    @Test
    fun everyDepthOnTheLadderServesTheCacheAndKeepsBeliefEqualToGlass(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(GlyphyText())
            val rig = Rig(scope, text, RowsWindow(text))
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.cacheOn()
            for (d in listOf(0, 4, 12, 16)) {
                rig.shell.updateSettings { it.copy(depth = d) }; rig.settle("depth $d")
                rig.assertGlassMatchesBelief("after depth $d")
                val n0 = rig.all().size
                repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
                rig.settle("notches at $d")
                val flushes = rig.all().drop(n0)
                assertTrue(flushes.any { draws(it) > 0 }, "depth $d: draws shipped: ${flushes.map(::shape)}")
                if (d != 0) assertTrue(flushes.any { pairs(it) > 0 }, "depth $d: a per-lens copy shipped: ${flushes.map(::shape)}")
                else assertTrue(flushes.none { pairs(it) > 0 }, "depth 0: flat draws need no copy: ${flushes.map(::shape)}")
                rig.assertGlassMatchesBelief("after notches at depth $d")
            }
            // the app on its own plane under a different global one
            rig.shell.updateSettings { s -> s.copy(depth = 16).withAppStyle("rows") { it.copy(depth = 4) } }
            rig.settle("rows at 4 under 16")
            rig.assertGlassMatchesBelief("rows at 4 under 16")
            val n0 = rig.all().size
            repeat(2) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }
            rig.settle("notches at 4/16")
            assertTrue(rig.all().drop(n0).any { pairs(it) > 0 && draws(it) > 0 }, "rows at 4: draws + copy: ${rig.all().drop(n0).map(::shape)}")
            rig.assertGlassMatchesBelief("after notches at 4 under 16")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /** Rows whose lens carries a drawn icon (no theme source): the drawn set
     *  renders once per kind and size and rides the cache as an image. */
    private class IconRowsWindow(private val tx: TextRasterizer) : DamageWindow("icons", "Icons", IconKind.FILES) {
        private val model = ListModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        override fun view(): WindowView = WindowView.ListView(model, { 30 },
            paintRow = { g, i, r, _ ->
                wm.damage.core.gfx.IconPaint.draw(g, null, listOf("nothing"), r.x + 8, r.y + 4, 20, IconKind.READER, Level.BODY)
                tx.draw(g, r.x + 40, r.y + 5, "Row $i", f, Level.BODY)
            },
            paintLens = { g, r, i ->
                wm.damage.core.gfx.IconPaint.draw(g, null, listOf("nothing"), r.x + 8, r.y + 4, 40, IconKind.FILES, Level.HEAD)
                tx.draw(g, r.x + 60, r.y + 8, "Row $i", f, Level.HEAD)
            },
            onCommit = {})
        override fun summary() = Summary("30 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** §41: icons ride the cache as mode-13 images — the drawn set and theme
     *  bitmaps alike — packed once drawn twice, after the fonts. */
    @Test
    fun iconsShipAsCachedImagesWithBeliefEqualToGlass(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(GlyphyText())
            val rig = Rig(scope, text, IconRowsWindow(text))
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("icons", null) }
            rig.settle("open icons")
            rig.cacheOn()
            // the lens icon has been drawn at least twice by now (open + the
            // repaint the upload triggers); a notch or two packs and uploads it
            var seen = false
            for (i in 0 until 6) {
                val n0 = rig.all().size
                rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $i")
                rig.assertGlassMatchesBelief("after notch $i")
                if (rig.all().drop(n0).any { f -> f.ops.any { it is DisplayOp.DrawImage } }) { seen = true; break }
            }
            assertTrue(seen, "an icon shipped as a mode-13 draw: ${rig.all().takeLast(8).map(::shape)}")
            repeat(4) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }
            rig.settle("back")
            rig.assertGlassMatchesBelief("after scrolling back with icons cached")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /** §41: a keyframe seeds the screen plane only — with everything on
     *  depth planes it is a few hundred bytes, and the planes follow once. */
    @Test
    fun aKeyframeSeedsOnlyTheScreenPlaneAndThePlanesFollowOnce(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(GlyphyText())
            val rig = Rig(scope, text, RowsWindow(text))
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            val n0 = rig.all().size
            rig.shell.services.runOnShell { rig.shell.comp.requestKeyframe(); rig.shell.comp.damage(rig.shell.layout.content) }
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            rig.settle("keyframe")
            val kf = rig.all().drop(n0).firstOrNull { f -> f.ops.any { it is DisplayOp.Keyframe } }
            assertTrue(kf != null, "a keyframe went out: ${rig.all().drop(n0).map(::shape)}")
            val payload = (kf!!.ops.first { it is DisplayOp.Keyframe } as DisplayOp.Keyframe).payload.size
            assertTrue(payload < 400, "the seed is black where the planes are (bars 8, rows 8, lens 4): $payload B")
            rig.assertGlassMatchesBelief("after the keyframe and its planes")
            // and at depth 0 the keyframe IS the frame
            rig.shell.updateSettings { it.copy(depth = 0) }; rig.settle("depth 0")
            val n1 = rig.all().size
            rig.shell.services.runOnShell { rig.shell.comp.requestKeyframe(); rig.shell.comp.damage(rig.shell.layout.content) }
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            rig.settle("flat keyframe")
            val kf0 = rig.all().drop(n1).firstOrNull { f -> f.ops.any { it is DisplayOp.Keyframe } }
            assertTrue(kf0 != null, "a flat keyframe went out")
            val flat = (kf0!!.ops.first { it is DisplayOp.Keyframe } as DisplayOp.Keyframe).payload.size
            assertTrue(flat > payload * 3, "at depth 0 the keyframe carries the frame: $flat B vs $payload B")
            rig.assertGlassMatchesBelief("after the flat keyframe")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }
}
