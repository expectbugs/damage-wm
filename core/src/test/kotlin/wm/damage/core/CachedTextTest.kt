package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
import wm.damage.core.comp.GlyphAtlas
import wm.damage.core.geom.LintError
import wm.damage.core.geom.FidAllocator
import wm.damage.core.geom.FidTracker
import wm.damage.core.geom.Rect
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
import wm.damage.core.transport.Emit
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §40 — text through the firmware's texture cache. The atlas is
 * rendered by the host's own rasterizer, uploaded chunk by chunk when the
 * link is idle, and from then on every plane-0 string the frame recorded
 * ships as one clear plus mode-14 draws — when, and only when, the recorded
 * draws reproduce the composed pixels exactly. Belief equals glass
 * throughout, the simulator drawing the cached glyphs the way the firmware
 * does (`GlassFirmwareSim.renderCached`).
 */
class CachedTextTest {

    private class SpyTransport(private val inner: Transport) : Transport by inner {
        val flushes = ArrayList<FlushRequest>()
        override suspend fun submit(flush: FlushRequest): Long {
            synchronized(flushes) { flushes.add(flush) }
            return inner.submit(flush)
        }
        fun all() = synchronized(flushes) { flushes.toList() }
    }

    private class Rig(scope: CoroutineScope, val text: CachedText, vararg windows: DamageWindow) {
        val tmp = Files.createTempDirectory("damage-cached")
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
            assertTrue(cond(), "$what — live=${shell.cachedFontsLive} active=${shell.cachedTextActive} flushes=${all().size} labels=${all().map { it.label }.takeLast(6)}")
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
    }

    /** Rows whose lens is two lines of text — the shape of every list. */
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

    /** A lens whose text sits on a GREY box: the proof must refuse it. */
    private class BoxedWindow(private val tx: TextRasterizer) : DamageWindow("boxed", "Boxed", IconKind.FILES) {
        private val model = ListModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        override fun view(): WindowView = WindowView.ListView(model, { 30 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 5, "Row $i", f, Level.BODY) },
            paintLens = { g, r, i ->
                g.fillRect(r.x + 40, r.y + 4, 300, 28, Level.DIM)
                tx.draw(g, r.x + 44, r.y + 8, "Row $i on a box", f, Level.HEAD)
            },
            onCommit = {})
        override fun summary() = Summary("30 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private fun drawTexts(f: FlushRequest) = f.ops.count { it is DisplayOp.DrawText }
    private fun bytesOf(f: FlushRequest): Int = f.ops.sumOf {
        when (it) {
            is DisplayOp.Keyframe -> it.payload.size
            is DisplayOp.Delta -> it.payload.size
            is DisplayOp.StereoPair -> it.payload.size
            is DisplayOp.DrawText -> 9 + it.text.size
            is DisplayOp.DrawImage -> 8
            is DisplayOp.CacheWrite -> it.payload.size
            is DisplayOp.Copy -> 0
            is DisplayOp.CopyPair -> 0
        }
    }

    @Test
    fun theAtlasGoesUpWhenIdleAndTheLensShipsAsDrawsWithBeliefEqualToGlass(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(GlyphyText())
            val rig = Rig(scope, text, RowsWindow(text))
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            assertTrue(rig.all().none { it.label == "ATLAS" }, "off by default: no atlas goes up")

            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the atlas uploads and the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            val uploads = rig.all().filter { it.label == "ATLAS" }
            assertTrue(uploads.isNotEmpty(), "chunks went up as their own flushes")
            assertTrue(uploads.all { it.ops.size == 1 && it.ops[0] is DisplayOp.CacheWrite }, "one mode-12 message per flush")
            assertTrue(uploads.all { bytesOf(it) <= 3072 }, "each chunk small enough to yield to a gesture")
            rig.settle("after the upload")
            rig.assertGlassMatchesBelief("after the upload's repaint")

            val n0 = rig.all().size
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch")
            val notch = rig.all().drop(n0)
            val lensFlush = notch.firstOrNull { drawTexts(it) > 0 }
            assertTrue(lensFlush != null, "the lens repaint ships as mode-14 draws: ${notch.map { f -> f.ops.map { it::class.simpleName } }}")
            assertTrue(bytesOf(lensFlush!!) < 400, "the lens costs its characters, not its pixels: ${bytesOf(lensFlush)} B")
            rig.assertGlassMatchesBelief("after the notch")
            repeat(5) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            rig.settle("spin")
            rig.assertGlassMatchesBelief("after the spin")
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }
            rig.settle("back")
            rig.assertGlassMatchesBelief("after scrolling back")

            // off again: draws stop, pixels resume, still exact
            rig.shell.updateSettings { it.copy(cachedText = "off") }
            rig.until("off") { !rig.shell.cachedTextActive }
            val n1 = rig.all().size
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch off")
            assertTrue(rig.all().drop(n1).all { drawTexts(it) == 0 }, "off: no draws")
            rig.assertGlassMatchesBelief("after the off notch")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /** §41: turning the row off and on again used to leave the cache dark —
     *  the glasses still held every font, nothing new was there to upload, and
     *  the grow returned before re-attaching the compositor (2026-09-06 on
     *  glass: two flips, no draws until an accident uploaded 7 KB). */
    @Test
    fun turningCachedTextOffAndOnAgainResumesDrawsWithoutAnotherUpload(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(GlyphyText())
            val rig = Rig(scope, text, RowsWindow(text))
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the atlas uploads") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the upload")
            val live = rig.shell.cachedFontsLive
            val uploads = rig.all().count { it.label == "ATLAS" }
            rig.shell.updateSettings { it.copy(cachedText = "off") }
            rig.until("off") { !rig.shell.cachedTextActive }
            rig.settle("off")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("on again: the fonts the glasses hold are live at once") {
                rig.shell.cachedTextActive && rig.shell.cachedFontsLive == live
            }
            rig.settle("on again")
            assertEquals(uploads, rig.all().count { it.label == "ATLAS" }, "nothing went up again: the glasses held it all")
            val n0 = rig.all().size
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch")
            assertTrue(rig.all().drop(n0).any { drawTexts(it) > 0 }, "draws resumed: ${rig.all().drop(n0).map { f -> f.ops.map { it::class.simpleName } }}")
            rig.assertGlassMatchesBelief("after the notch with the cache back on")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun textOverANonBlackBackgroundStaysPixelsAndStaysExact(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(GlyphyText())
            val rig = Rig(scope, text, BoxedWindow(text))
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("boxed", null) }
            rig.settle("open boxed")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the upload")
            val n0 = rig.all().size
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            val notch = rig.all().drop(n0)
            val lensDraws = notch.filter { f -> f.ops.any { it is DisplayOp.DrawText && it.y in rig.shell.layout.lens.y..rig.shell.layout.lens.bottom } }
            assertTrue(lensDraws.isEmpty(), "the lens text sits on a grey box: the proof refuses the cached path — ${lensDraws.map { f -> f.ops.map { it::class.simpleName } }}")
            rig.assertGlassMatchesBelief("after the notches")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theAtlasRendersAdvanceBoxesAndTheBlitMatchesTheFirmwaresLut() {
        val base = GlyphyText()
        val atlas = GlyphAtlas(base)
        val spec = FontSpec(Face.SYSTEM, 18)
        assertTrue(atlas.add(spec))
        val e = atlas.entry(spec)!!
        assertEquals(base.measure("A", spec), e.images['A'.code - 32].w, "a glyph box is its advance wide")
        assertEquals(base.metrics(spec).let { it.ascent + it.descent }, e.lineH, "and the face's ink tall")
        assertEquals(base.measure("Row 7", spec), e.font.measure("Row 7"), "mode 14 lays the string out at the same width")
        // the blit: level HEAD (12) scales a full-level pixel to 12, a half one to 6
        val g = Gray8(64, 32)
        CachedText.blit(g, 0, 0, "A", e, Level.HEAD)
        var ink = 0
        for (v in g.pix) if (v.toInt() != 0) ink++
        assertTrue(ink > 0, "the glyph landed")
        for (v in g.pix) assertTrue((v.toInt() and 0xFF) % 17 == 0 && (v.toInt() and 0xFF) / 17 <= 12, "every pixel is a LUT level × 17, at most the top: ${v.toInt() and 0xFF}")
        val chunks = atlas.takeUpload()
        assertTrue(chunks.isNotEmpty() && atlas.takeUpload().isEmpty(), "the upload is taken once; nothing is left until the atlas grows")
        atlas.forgetUpload()
        assertEquals(chunks.size, atlas.takeUpload().size, "a lapse sends everything again")
    }

    @Test
    fun aCacheWriteNeverRidesABatch() {
        val flush = FlushRequest(listOf(DisplayOp.CacheWrite(byteArrayOf(12, 0, 0, 1, 0, 7)),
            DisplayOp.Delta(Rect(0, 0, 8, 2), byteArrayOf(0), 0)), 1)
        assertFailsWith<LintError> { Emit.encode(flush, FidAllocator(), FidTracker(), 3) }
    }
}
