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
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.draw2
import wm.damage.core.wire.EvenHubMsg

/**
 * `FIRMWARE.md` §4, the phone side of contract 2 against the simulator's model of a Phase 2
 * build: DRAW2 in force before the first paint, the atlas a v2 layout over the session's cache,
 * a rect of text on a depth plane as one stereo base delta plus per-lens draws (no widening,
 * no copy), the seams and a reseed as fills, kerning as adjust bytes, a short flush's rows as
 * a present hint — and belief equal to glass through all of it, the model keeping only the
 * hinted rows on the panel exactly as the firmware's partial refresh would.
 */
class Contract2Test {

    /** A rasterizer with real glyph shapes and a pair kerning the wire has to carry. */
    class KernText(private val advance: Int = 8) : TextRasterizer {
        override fun measure(text: String, font: FontSpec): Int {
            var w = 0
            var prev: Char? = null
            for (ch in text) { prev?.let { w += kern(it, ch, font) }; w += advance; prev = ch }
            return w
        }
        override fun metrics(font: FontSpec) = FontMetrics(12, 4, 16)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
            var px = x
            var prev: Char? = null
            for (ch in text) {
                prev?.let { px += kern(it, ch, font) }
                prev = ch
                if (ch != ' ') surface.fillRect(px, y + 2, advance - 2, 3 + ch.code % 9, level)
                px += advance
            }
        }
        override fun covers(text: String, font: FontSpec) = text.all { it.code < 256 && it.code != 127 }
        /** A vowel after a consonant tucks in a pixel; "To" tucks two. */
        override fun kern(a: Char, b: Char, font: FontSpec): Int = when {
            a == 'T' && b == 'o' -> -2
            b in "aeiou" && a !in "aeiou " -> -1
            else -> 0
        }
    }

    private class SpyTransport(private val inner: Transport) : Transport by inner {
        val flushes = ArrayList<FlushRequest>()
        override suspend fun submit(flush: FlushRequest): Long {
            synchronized(flushes) { flushes.add(flush) }
            return inner.submit(flush)
        }
        fun all() = synchronized(flushes) { flushes.toList() }
    }

    private class Rows(private val tx: TextRasterizer) : DamageWindow("rows", "Rows", IconKind.FILES) {
        private val model = ListModel()
        private val f = FontSpec(Face.SYSTEM, 18)
        private val fB = FontSpec(Face.SYSTEM, 18, bold = true)
        override fun view(): WindowView = WindowView.ListView(model, { 30 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 5, "Row $i: To open the door, use the key", f, Level.BODY) },
            paintLens = { g, r, i ->
                tx.draw(g, r.x + 44, r.y + 8, "Row $i", fB, Level.HEAD)
                tx.draw(g, r.x + 44, r.y + 34, "the detail line of row $i — café", f, Level.BODY)
            },
            onCommit = {})
        override fun summary() = Summary("30 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(scope: CoroutineScope, contract: Int = 2) {
        val tmp = Files.createTempDirectory("damage-contract2")
        val sim = GlassFirmwareSim().also { it.damageContract = contract }
        val text = CachedText(KernText())
        val spy = SpyTransport(SimTransport(sim, scope, SimTransport.Timing(instant = true)))
        val shell = Shell(text, spy, Persistence(tmp.resolve("state.json")), tmp.resolve("journal.jsonl"), scope)

        init { shell.register(Rows(text)) }

        fun all() = spy.all()
        fun notes(kind: String): List<String> =
            if (!Files.exists(tmp.resolve("journal.jsonl"))) emptyList()
            else Files.readAllLines(tmp.resolve("journal.jsonl")).filter { it.contains("\"kind\":\"$kind\"") }

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

        /** Up on the rows window with a v2 atlas live. */
        suspend fun upWithRowsAndTheAtlas() {
            shell.start(); settle("start")
            assertTrue(spy.state.value.draw2, "DRAW2 is in force before the first paint: features 0x${spy.state.value.damageFeatures.toString(16)} flags 0x${spy.state.value.flagsInForce.toString(16)}")
            assertEquals(160 * 1024, spy.state.value.cacheSize, "the session's cache is the 160 KiB budget")
            shell.services.runOnShell { shell.services.openWindow("rows", null) }
            settle("open rows")
            shell.updateSettings { it.copy(cachedText = "on") }
            until("the v2 atlas uploads and the fonts go live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            settle("after the upload")
            assertTrue(notes("atlas").any { "built for contract 2" in it }, "the atlas was built for contract 2: ${notes("atlas").take(3)}")
            assertTrue(sim.cacheSize(wm.damage.core.transport.Arm.RIGHT) == 160 * 1024, "the model allocated the 160 KiB cache on the first mode-19 write")
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
                var diffs = 0; var first: String? = null
                for (y in 0 until 480) for (x in 0 until 640) {
                    val q = minOf(15, (e[x, y] + 8) / 17) * 17
                    if (q != g[x, y]) { diffs++; if (first == null) first = "($x,$y) belief $q glass ${g[x, y]}" }
                }
                assertEquals(0, diffs, "$what: ${if (left) "L" else "R"} belief != glass ($diffs px, first $first)")
            }
        }
    }

    private fun shape(f: FlushRequest) = f.ops.joinToString(" ") {
        when (it) {
            is DisplayOp.Keyframe -> "KF(${it.payload.size})"
            is DisplayOp.Delta -> "D${it.box}(${it.payload.size},d${it.disparity})"
            is DisplayOp.StereoPair -> "SP(${it.payload.size})"
            is DisplayOp.Copy -> "C"
            is DisplayOp.CopyPair -> "CP"
            is DisplayOp.DrawText -> "T(${it.x},${it.y})"
            is DisplayOp.DrawImage -> "I"
            is DisplayOp.CacheWrite -> "CW"
            is DisplayOp.DrawText2 -> "T2(${it.xL}/${it.xR},${it.y})"
            is DisplayOp.DrawImage2 -> "I2(${it.xL}/${it.xR},${it.y})"
            is DisplayOp.Fill -> "F${it.left}=${it.level}"
            is DisplayOp.Clip -> "CL"
            is DisplayOp.PresentHint -> "H${it.y0}-${it.y1}"
            is DisplayOp.CacheWrite2 -> "CW2"
        }
    }

    @Test
    fun rowsOnADepthPlaneShipAsPerLensDrawsWithNoCopyAndBeliefEqualsGlass(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithRowsAndTheAtlas()
            assertEquals(8, rig.shell.comp.planes.lastOrNull { it.rect == rig.shell.layout.content }?.disparity, "rows sit on plane 8")
            rig.assertGlassMatchesBelief("after the upload's repaint")
            val n0 = rig.all().size
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch")
            val notch = rig.all().drop(n0)
            val perLens = notch.flatMap { it.ops }.filterIsInstance<DisplayOp.DrawText2>().filter { it.xL != it.xR }
            assertTrue(perLens.isNotEmpty(), "the depth-plane text shipped as per-lens draws: ${notch.map(::shape)}")
            // the rows sit at depth 8 (a 16 px spread), the lens band's own text one notch nearer (4: 8 px)
            assertTrue(perLens.all { it.xR - it.xL == 16 || it.xR - it.xL == 8 } && perLens.any { it.xR - it.xL == 16 },
                "each lens's x is the nominal one shifted by its plane's disparity: ${perLens.map { "${it.xL}/${it.xR}" }}")
            assertTrue(notch.none { f -> f.ops.any { it is DisplayOp.CopyPair || it is DisplayOp.DrawText } }, "no v1 copy or flat draw on contract 2: ${notch.map(::shape)}")
            // a list notch touches most of the panel (the copy), so it ships with the full refresh; with
            // the hint's ceiling lifted every flush hints, and the model — which keeps only the hinted
            // rows on the panel, as the firmware's partial refresh does — proves the rows cover every change
            rig.assertGlassMatchesBelief("after the notch")
            rig.shell.comp.hintMaxRows = 480
            val n1 = rig.all().size
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("hinted notch")
            val hinted = rig.all().drop(n1)
            assertTrue(hinted.isNotEmpty() && hinted.all { f -> f.ops.any { it is DisplayOp.PresentHint } }, "every flush hints its rows: ${hinted.map(::shape)}")
            rig.assertGlassMatchesBelief("after the hinted notch (the model keeps the hinted rows only)")
            repeat(6) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            rig.settle("spin")
            rig.assertGlassMatchesBelief("after the spin")
            repeat(4) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP) }
            rig.settle("back")
            rig.assertGlassMatchesBelief("after scrolling back")
            // every flush's misses, from the journal (the compositor's summary is the last assemble's only)
            val misses = Files.readAllLines(rig.tmp.resolve("journal.jsonl")).filter { "\"ev\":\"submit\"" in it }
                .mapNotNull { Regex("\"cacheMiss\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }.filter { it.isNotEmpty() }
            assertTrue(misses.none { "proof=" in it || "edge=" in it }, "no context proof or edge miss on contract 2, in any flush: $misses")
            assertTrue(rig.all().drop(1).none { f -> f.ops.any { it is DisplayOp.StereoPair } }, "every black box is a fill on contract 2: ${rig.all().drop(1).filter { f -> f.ops.any { it is DisplayOp.StereoPair } }.map(::shape)}")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun kerningRidesAsAdjustBytesAndTheModelAppliesThem(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithRowsAndTheAtlas()
            // the first paint went as pixels before the atlas was live, and a notch redraws only the
            // runs whose pixels changed; a reseed (the height switch) redraws every run through the cache
            val n0 = rig.all().size
            rig.shell.updateSettings { it.copy(heightMode = 352) }
            rig.settle("reseed")
            val draws = rig.all().drop(n0).flatMap { it.ops }.filterIsInstance<DisplayOp.DrawText2>()
            assertTrue(draws.isNotEmpty(), "the reseed drew every run through the cache: ${rig.all().drop(n0).map(::shape)}")
            // "To" kerns −2 (adjust byte 9), a consonant-vowel pair −1 (byte 10)
            assertTrue(draws.any { d -> d.text.any { it.toInt() == 9 } && d.text.any { it.toInt() == 10 } },
                "the adjust bytes ride the strings: ${draws.map { d -> d.text.map { it.toInt() } }.take(3)}")
            // Latin-1 through the 224-entry table: "café" is one cached run, no host-drawn character
            assertTrue(draws.any { d -> d.text.any { (it.toInt() and 0xFF) == 0xE9 } },
                "the é rides as its Latin-1 code: ${draws.map { d -> d.text.joinToString("") { b -> (b.toInt() and 0xFF).let { c -> if (c in 32..255) c.toChar().toString() else "<$c>" } } }}")
            rig.assertGlassMatchesBelief("kerned text, both lenses")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aHeightSwitchReseedsWithAFillAndDrawsInsteadOfAKeyframe(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithRowsAndTheAtlas()
            val n0 = rig.all().size
            rig.shell.updateSettings { it.copy(heightMode = 352) }
            rig.settle("height 352")
            val since = rig.all().drop(n0)
            val full = Rect(0, 0, 640, 480)
            assertTrue(since.any { f -> f.ops.any { it is DisplayOp.Fill && it.left == full && it.level == 0 } }, "the reseed opens with a whole-panel fill: ${since.map(::shape)}")
            assertTrue(since.none { f -> f.ops.any { it is DisplayOp.Keyframe } }, "no mode-6 keyframe on contract 2: ${since.map(::shape)}")
            val first = since.first()
            assertTrue(first.ops.sumOf { op -> when (op) { is DisplayOp.Delta -> op.payload.size; is DisplayOp.StereoPair -> op.payload.size; else -> 0 } } < 3000,
                "the first flush after the switch is light: ${shape(first)}")
            rig.assertGlassMatchesBelief("after the height switch")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** 2026-09-15 review: the recorder kerns on every atlas, so the v1 path (the installed Phase 1
     *  build) must carry the same adjust bytes and re-render with them — it laid out and proved
     *  with none, every kerned string missed its proof and went as pixels. */
    @Test
    fun onAContractOneBuildKernedTextStillShipsAsCachedDrawsWithItsAdjustBytes(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope, contract = 1)
        try {
            rig.shell.start(); rig.settle("start")
            assertTrue(!rig.spy.state.value.draw2, "the Phase 1 build has no v2 ops")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the v1 atlas uploads and the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the upload")
            val n0 = rig.all().size
            rig.shell.updateSettings { it.copy(heightMode = 352) }         // a keyframe on v1: every run repaints through the cache
            rig.settle("height switch")
            val draws = rig.all().drop(n0).flatMap { it.ops }.filterIsInstance<DisplayOp.DrawText>()
            assertTrue(draws.any { d -> d.text.any { it.toInt() == 9 || it.toInt() == 10 } },
                "kerned strings ship as mode-14 draws carrying their adjust bytes: ${rig.all().drop(n0).map(::shape)} misses ${rig.shell.comp.cacheMissSummary()}")
            rig.assertGlassMatchesBelief("kerned v1 draws, both lenses")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** 2026-09-15 review: on a v2 atlas the soft hyphen and the C1 controls are the host's (the platform
     *  draws them as nothing; the table could only hold the tofu box), and a run holds at most 128 codes
     *  so its string with an adjust between every pair stays inside the u8 length. */
    @Test
    fun theSoftHyphenAndTheControlsStayTheHostsAndRunsFitTheirLength() {
        val base = KernText()
        val ct = CachedText(base)
        val atlas = wm.damage.core.comp.GlyphAtlas(base, v2 = true, capacity = 160 * 1024)
        ct.atlas = atlas
        val f = FontSpec(Face.SYSTEM, 18)
        assertTrue(atlas.add(f), "the face packs")
        ct.live = setOf(f)
        val g = Gray8(640, 480)
        ct.target = g
        ct.draw(g, 10, 20, "co\u00ADop\u0085x", f, Level.BODY)
        assertEquals(listOf("co", "op", "x"), ct.frameDraws().map { it.text }, "the soft hyphen and NEL split the runs")
        ct.endFrame()
        val long = "ta".repeat(150)                                        // 300 codes, a kern between every pair
        ct.draw(g, 0, 60, long, f, Level.BODY)
        val runs = ct.frameDraws()
        assertTrue(runs.all { it.text.length <= 128 }, "runs of at most 128 codes (2 x 128 - 1 = 255 B with adjusts): ${runs.map { it.text.length }}")
        for (r in runs) wm.damage.core.wire.TextureCache.layout(r.text, atlas.entry(f)!!.font, ct.kernOf(f))   // fits its u8 length, or throws
    }

    /** The recorder on a v2 atlas: a string is its cacheable runs (Latin-1 but DEL) and the
     *  characters between them the host draws; every run is recorded with its kerned width. */
    @Test
    fun theRecorderSplitsRunsAtCharactersOutsideLatinOne() {
        val base = KernText()
        val ct = CachedText(base)
        val atlas = wm.damage.core.comp.GlyphAtlas(base, v2 = true, capacity = 160 * 1024)
        ct.atlas = atlas
        val f = FontSpec(Face.SYSTEM, 18)
        assertTrue(atlas.add(f), "the face packs")
        ct.live = setOf(f)
        val g = Gray8(640, 480)
        ct.target = g
        ct.draw(g, 10, 20, "the row — café", f, Level.BODY)
        val runs = ct.frameDraws().map { it.text to it.x }
        // "the row " is 8 advances less the "he" and "ro" kerns, then the dash's own advance
        assertEquals(listOf("the row " to 10, " café" to 10 + 8 * 8 - 2 + 8), runs, "two cached runs around the host-drawn dash, the second placed after the dash's advance and the kerns")
    }
}
