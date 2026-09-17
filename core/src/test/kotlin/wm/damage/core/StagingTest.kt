package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import wm.damage.core.shell.CacheLedger
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.DocModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.StageKey
import wm.damage.core.shell.StageRing
import wm.damage.core.shell.StagedRecord
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.draw2
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg

/**
 * Page staging (`HANDOFF.md` §66): on a contract-2 session with cached text on, the focused
 * document's next strips are written to the cache's reserve off the gesture path, proven by the
 * generation count, and a notch ships one mode-17 draw under a clip instead of its strip's pixels
 * — belief equal to glass throughout. The pins below are the design pass's gates, each measured on
 * the observable it names: the ops a notch's flushes carry, the registry's counts, the journal's
 * notes, the offsets on the wire. Each was watched to fail with the feature absent (the `Page
 * staging` row off): the draws are then not there, and the assertions say so.
 */
class StagingTest {

    private companion object {
        const val LINE = "the quick brown fox jumps over the lazy dog and keeps going down the page"
        const val RESERVE_START = 160 * 1024 - CfwModes.STAGE_RESERVE
    }

    /** Records every flush; optionally rewrites a STAGE write's offset past the cache, so the model
     *  acks it (the ack precedes the decode) and refuses it (reason 5) — the silent failure the
     *  generation count exists to catch. */
    private class SpyTransport(private val inner: Transport) : Transport by inner {
        val flushes = ArrayList<FlushRequest>()
        @Volatile var breakStageWrites = false
        @Volatile var refuseStageSubmits = false
        override suspend fun submit(flush: FlushRequest): Long {
            var f = flush
            if (flush.label == "STAGE" && refuseStageSubmits) throw IllegalStateException("test: the lane refuses the write")
            if (flush.label == "STAGE" && breakStageWrites) f = flush.copy(ops = flush.ops.map { op ->
                if (op is DisplayOp.CacheWrite2) {
                    val p = op.payload.copyOf()
                    p[1] = 0xFF.toByte(); p[2] = 0xFF.toByte()      // off4 = 0xFFFF: 256 KiB, past the 160 KiB cache
                    DisplayOp.CacheWrite2(p)
                } else op
            })
            synchronized(flushes) { flushes.add(f) }
            return inner.submit(f)
        }
        fun all() = synchronized(flushes) { flushes.toList() }
    }

    /** Two hundred lines of glyph-like text in one face; the layout key is swappable from the test. */
    private class Doc(private val tx: TextRasterizer) : DamageWindow("doc", "Doc", IconKind.READER) {
        val model = DocModel()
        @Volatile var layoutKey: Any = Any()
        private val f = FontSpec(Face.SYSTEM, 18)
        override fun view() = WindowView.DocView(model, { 200 }, 24,
            { g, i, r -> tx.draw(g, r.x + 4, r.y + 4, "line $i " + LINE.substring(i % 7), f, ((i % 10) + 3) * 17) },
            {}, stepLines = { 3 }, contentKey = { layoutKey })
        override fun summary() = Summary("doc")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(scope: CoroutineScope) {
        val tmp = Files.createTempDirectory("damage-staging")
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        val text = CachedText(GlyphyText())
        val spy = SpyTransport(SimTransport(sim, scope, SimTransport.Timing(instant = true)))
        val shell = Shell(text, spy, Persistence(tmp.resolve("state.json")), tmp.resolve("journal.jsonl"), scope)
        val doc = Doc(text)

        init { shell.register(doc) }

        fun all() = spy.all()
        fun notes(kind: String): List<String> =
            if (!Files.exists(tmp.resolve("journal.jsonl"))) emptyList()
            else Files.readAllLines(tmp.resolve("journal.jsonl")).filter { it.contains("\"kind\":\"$kind\"") }

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) return
                delay(5)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }

        suspend fun until(what: String, maxMs: Long = 10_000, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < maxMs) delay(5)
            assertTrue(cond(), "$what — proven=${shell.stagedProvenCount} resident=${shell.stagedResidentCount} " +
                "lines=${shell.stagedProvenLines} live=${shell.cachedFontsLive.size} stage notes=${notes("stage").takeLast(4)} " +
                "atlas notes=${notes("atlas").takeLast(3)}")
        }

        /** Up on the document with the v2 atlas live and the first strips staged. */
        suspend fun upWithTheDocStaged() {
            shell.start(); settle("start")
            assertTrue(spy.state.value.draw2, "DRAW2 in force")
            shell.services.runOnShell { shell.services.openWindow("doc", null) }
            settle("open doc")
            shell.updateSettings { it.copy(cachedText = "on") }
            until("the atlas uploads and the fonts go live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            until("the next strip is staged and proven") { shell.stagedProvenLines.any { it.first == nextExposed() } }
            settle("staged")
        }

        /** The first line the next down-notch exposes: the visible lines end at top + 16 (384 / 24). */
        fun nextExposed(): Int = doc.model.topLine + visibleLines()
        fun visibleLines(): Int = (shell.layout.content.h - 32) / 24
        fun region(): Rect = Rect(shell.layout.content.x, shell.layout.content.y + 16, shell.layout.content.w - 12, visibleLines() * 24)

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

    private fun bytesOf(f: FlushRequest): Int = f.ops.sumOf {
        when (it) {
            is DisplayOp.Keyframe -> it.payload.size
            is DisplayOp.Delta -> it.payload.size
            is DisplayOp.StereoPair -> it.payload.size
            is DisplayOp.Copy, is DisplayOp.CopyPair -> 0
            is DisplayOp.DrawText -> 9 + it.text.size
            is DisplayOp.DrawImage -> 8
            is DisplayOp.CacheWrite -> it.payload.size
            is DisplayOp.DrawText2 -> 11 + it.text.size
            is DisplayOp.DrawImage2 -> 10
            is DisplayOp.Fill -> 18
            is DisplayOp.Clip -> 17
            is DisplayOp.PresentHint -> 5
            is DisplayOp.CacheWrite2 -> it.payload.size
        }
    }

    private fun shape(f: FlushRequest) = f.ops.joinToString(" ") {
        when (it) {
            is DisplayOp.Keyframe -> "KF(${it.payload.size})"
            is DisplayOp.Delta -> "D${it.box}(${it.payload.size},d${it.disparity})"
            is DisplayOp.StereoPair -> "SP(${it.payload.size})"
            is DisplayOp.Copy -> "C${it.src}->${it.dst}"
            is DisplayOp.CopyPair -> "CP"
            is DisplayOp.DrawText -> "T"
            is DisplayOp.DrawImage -> "I"
            is DisplayOp.CacheWrite -> "CW"
            is DisplayOp.DrawText2 -> "T2(${it.xL}/${it.xR},${it.y})"
            is DisplayOp.DrawImage2 -> "I2(${it.xL}/${it.xR},${it.y} ${it.w}x${it.h})"
            is DisplayOp.Fill -> "F"
            is DisplayOp.Clip -> "CL${it.left}"
            is DisplayOp.PresentHint -> "H${it.y0}-${it.y1}"
            is DisplayOp.CacheWrite2 -> "CW2(${it.payload.size})"
        }
    }

    /** The (offset, length) of every mode-19 write in [f], from the bytes on the wire. */
    private fun writesOf(f: FlushRequest): List<Pair<Int, Int>> = f.ops.filterIsInstance<DisplayOp.CacheWrite2>().flatMap { op ->
        val p = op.payload
        val out = ArrayList<Pair<Int, Int>>()
        var pos = 1
        while (pos + 4 <= p.size) {
            val off4 = (p[pos].toInt() and 0xFF) or ((p[pos + 1].toInt() and 0xFF) shl 8)
            val len = (p[pos + 2].toInt() and 0xFF) or ((p[pos + 3].toInt() and 0xFF) shl 8)
            out.add(off4 * 4 to len)
            pos += 4 + len
        }
        out
    }

    @Test
    fun aNotchWithAStagedStripShipsOneDrawUnderAClipAndNoPixelsOverTheBand(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheDocStaged()
            rig.assertGlassMatchesBelief("staged, before the notch")
            val region = rig.region()
            repeat(3) { notch ->
                rig.until("strip for notch $notch staged") { rig.shell.stagedProvenLines.any { it.first == rig.nextExposed() } }
                rig.settle("before notch $notch")
                val n0 = rig.all().size
                val shipped0 = rig.shell.stagedRectsShipped
                rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $notch")
                val flushes = rig.all().drop(n0).filter { it.label != "STAGE" }
                assertTrue(flushes.isNotEmpty(), "the notch flushed")
                val draws = flushes.flatMap { it.ops }.filterIsInstance<DisplayOp.DrawImage2>()
                assertTrue(draws.isNotEmpty(), "notch $notch shipped a staged draw: ${flushes.map(::shape)}")
                assertTrue(draws.all { it.xR - it.xL == 16 && it.w == region.w }, "each draw is the record placed per lens at the content plane's depth (8): ${draws}")
                assertTrue(flushes.all { f -> f.ops.filterIsInstance<DisplayOp.Clip>().size >= 2 || f.ops.none { it is DisplayOp.DrawImage2 } },
                    "every staged draw sits between a clip and its reset: ${flushes.map(::shape)}")
                val pixelsOverBand = flushes.flatMap { it.ops }.filterIsInstance<DisplayOp.Delta>().filter { it.box.overlaps(region) }
                assertTrue(pixelsOverBand.isEmpty(), "no pixel delta over the document band on notch $notch: ${pixelsOverBand.map { it.box }} in ${flushes.map(::shape)}")
                assertTrue(flushes.flatMap { it.ops }.none { it is DisplayOp.DrawText2 && it.y in region.y until region.bottom }, "no text draws over the band either (the strip is one record): ${flushes.map(::shape)}")
                val first = flushes.first()
                assertTrue(first.ops.any { it is DisplayOp.Copy }, "the first flush carries the translation: ${shape(first)}")
                assertTrue(bytesOf(first) < 200, "the first flush is small: ${bytesOf(first)} B — ${shape(first)}")
                assertTrue(rig.shell.stagedRectsShipped > shipped0, "the shell counted the staged rects")
                rig.assertGlassMatchesBelief("after notch $notch")
            }
            // back up: the strip behind is staged too
            rig.until("the strip behind is staged") { rig.shell.stagedProvenLines.any { it.first == rig.doc.model.topLine - 3 } }
            val n1 = rig.all().size
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP); rig.settle("back")
            val back = rig.all().drop(n1).filter { it.label != "STAGE" }
            assertTrue(back.flatMap { it.ops }.any { it is DisplayOp.DrawImage2 }, "the notch back drew the staged strip: ${back.map(::shape)}")
            rig.assertGlassMatchesBelief("after the notch back")
            assertTrue(rig.notes("stage").any { "proved" in it }, "the journal says what was proven: ${rig.notes("stage").take(5)}")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** At Global depth 0 there is no content plane: the remainder's dirty cells are planned as coarsened
     *  rects, so a notch's planned rect may span the band AND the rail beside it. The primitive ships the
     *  covered band and `emitDelta` sends the rest as pixels in the same pass. This pin is the observable
     *  at depth 0 (flat staged draws, no pixels over the band, no swollen batch, belief = glass); it did
     *  not reproduce the `--selfcheck` stall of 2026-09-16 at `cache-book-depth-0` (a rate, cause read
     *  from the self-check's own log). */
    @Test
    fun aNotchAtDepthZeroShipsTheStagedBandAndTheRestAsPixelsAndSettles(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheDocStaged()
            rig.shell.updateSettings { it.copy(depth = 0) }
            rig.settle("depth 0")
            assertTrue(rig.shell.comp.planes.isEmpty(), "no plane at depth 0: ${rig.shell.comp.planes}")
            rig.assertGlassMatchesBelief("at depth 0")
            val region = rig.region()
            repeat(3) { notch ->
                rig.until("strip for notch $notch staged") { rig.shell.stagedProvenLines.any { it.first == rig.nextExposed() } }
                rig.settle("before notch $notch")
                val n0 = rig.all().size
                rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $notch at depth 0")
                val flushes = rig.all().drop(n0).filter { it.label != "STAGE" }
                val draws = flushes.flatMap { it.ops }.filterIsInstance<DisplayOp.DrawImage2>()
                assertTrue(draws.isNotEmpty() && draws.all { it.xL == it.xR && it.w == region.w }, "the band shipped as flat staged draws: ${flushes.map(::shape)}")
                assertTrue(flushes.flatMap { it.ops }.filterIsInstance<DisplayOp.Delta>().none { it.box.overlaps(region) },
                    "no pixel delta over the band (the rail beside it is a delta of its own): ${flushes.map(::shape)}")
                assertTrue(flushes.all { it.ops.size < 40 }, "no batch swollen by a rect served twice: ${flushes.map { it.ops.size }}")
                rig.assertGlassMatchesBelief("after notch $notch at depth 0")
            }
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun withTheRowOffANotchShipsAsBeforeAndNothingIsStaged(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.shell.start(); rig.settle("start")
            rig.shell.updateSettings { it.copy(pageStaging = "off") }
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.settle("open doc")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("live")
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            assertEquals(0, rig.shell.stagedResidentCount, "nothing staged with the row off")
            assertEquals(0L, rig.shell.stagedRectsShipped)
            assertTrue(rig.all().none { it.label == "STAGE" }, "no staging traffic with the row off")
            assertTrue(rig.all().flatMap { it.ops }.none { it is DisplayOp.DrawImage2 && it.w > 255 }, "no record draw with the row off")
            assertTrue(rig.all().flatMap { it.ops }.any { it is DisplayOp.DrawText2 }, "the notch's strip went as text draws, as before")
            rig.assertGlassMatchesBelief("after the notches")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theClearsEmptyTheRegistryAndStagingResumesAfterEach(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheDocStaged()
            // (1) cached text off: the reserve is the atlas path's
            rig.shell.updateSettings { it.copy(cachedText = "off") }
            rig.until("cached text off drops the records") { rig.shell.stagedResidentCount == 0 }
            assertTrue(rig.notes("stage").any { "cached text went off" in it }, rig.notes("stage").toString())
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("staged again after cached text came back") { rig.shell.stagedProvenCount > 0 }
            // (2) another layout: the old records can never be shown again
            rig.doc.layoutKey = Any()
            rig.shell.services.runOnShell { rig.shell.services.requestRender(rig.doc) }
            rig.until("a relayout drops the records of the old layout") { rig.notes("stage").any { "another layout" in it } }
            rig.until("staged again for the new layout") { rig.shell.stagedProvenLines.any { it.first == rig.nextExposed() } }
            // (3) the row off, then on
            val clears = rig.shell.stageClears
            rig.shell.updateSettings { it.copy(pageStaging = "off") }
            rig.until("the row off drops the records") { rig.shell.stagedResidentCount == 0 && rig.shell.stageClears > clears }
            rig.shell.updateSettings { it.copy(pageStaging = "on") }
            rig.until("staged again with the row on") { rig.shell.stagedProvenCount > 0 }
            // (4) a session boundary: nothing carries over, and the new session stages afresh
            val clears2 = rig.shell.stageClears
            rig.shell.stop()
            rig.shell.start(); rig.settle("restart")
            assertTrue(rig.shell.stageClears > clears2, "the session start cleared the registry")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.until("staged again in the new session") { rig.shell.stagedProvenCount > 0 }
            rig.settle("restaged")
            val n0 = rig.all().size
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch")
            assertTrue(rig.all().drop(n0).flatMap { it.ops }.any { it is DisplayOp.DrawImage2 }, "the new session's notch drew a staged strip")
            rig.assertGlassMatchesBelief("after the new session's notch")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aWriteTheGlassesRefusedIsNeverDrawnAndStagingGivesUpBounded(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.spy.breakStageWrites = true
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.settle("open doc")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            // each shortfall waits out the settle window (2 s) and two paced settled readings, then the
            // record is staged again; three of those switch staging off
            rig.until("the shortfall is found and staging switches itself off", maxMs = 45_000) { rig.notes("stage").any { "off for the session" in it } }
            rig.settle("off")
            assertTrue(rig.notes("stage").any { "never landed" in it }, "the generation count named the missing writes: ${rig.notes("stage")}")
            assertEquals(0, rig.shell.stagedProvenCount, "nothing was ever proven")
            assertTrue(rig.shell.cachedTextActive, "the atlas is untouched by a shortfall (§66.1: a shortfall costs staging, never the fonts)")
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            assertEquals(0L, rig.shell.stagedRectsShipped, "no draw from a record the glasses never took")
            assertTrue(rig.all().flatMap { it.ops }.none { it is DisplayOp.DrawImage2 && it.w > 255 }, "no record draw on the wire")
            rig.assertGlassMatchesBelief("after the notches (pixels and text draws)")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aWriteTheLaneRefusesGoesAgainThenStagingGivesUpBounded(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.spy.refuseStageSubmits = true
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.settle("open doc")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.until("three refused submits switch staging off") { rig.notes("stage").any { "off for the session" in it } }
            assertEquals(2, rig.notes("stage").count { "goes again later" in it }, "the first two went again: ${rig.notes("stage")}")
            assertEquals(0, rig.shell.stagedResidentCount)
            repeat(2) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            rig.assertGlassMatchesBelief("after the notches")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theAtlasLaysOutBelowTheReserveAndTheRecordsLandAboveIt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.upWithTheDocStaged()
            assertTrue(rig.notes("atlas").any { "built for contract 2 (per-lens draws, 96 KiB)" in it },
                "the atlas is laid out over the cache minus the reserve: ${rig.notes("atlas").take(3)}")
            val atlasWrites = rig.all().filter { it.label == "ATLAS" }.flatMap(::writesOf)
            val stageWrites = rig.all().filter { it.label == "STAGE" }.flatMap(::writesOf)
            assertTrue(atlasWrites.isNotEmpty() && stageWrites.isNotEmpty(), "both kinds of write went out")
            assertTrue(atlasWrites.all { (off, len) -> off + len <= RESERVE_START }, "every atlas write stays below ${RESERVE_START}: ${atlasWrites.filter { it.first + it.second > RESERVE_START }}")
            assertTrue(stageWrites.all { (off, len) -> off >= RESERVE_START && off + len <= 160 * 1024 && off % 4 == 0 }, "every record write lands in the reserve, 4-byte aligned: ${stageWrites.filter { it.first < RESERVE_START }}")
            assertTrue(stageWrites.all { (_, len) -> len <= Shell.STAGE_CHUNK }, "record writes are chunked")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    // ---- the pure parts

    private fun rec(first: Int, bytes: Int): StagedRecord =
        StagedRecord(StageKey("k", first, 3, 24, 596), Gray8(4, 4), ByteArray(bytes))

    @Test
    fun theRingWrapsEvictsTheUnpinnedAndRefusesToEvictAPinnedRecord() {
        val ring = StageRing(1000, 1000 + 100)
        val a = rec(0, 40); val b = rec(3, 40)
        assertEquals(emptyList(), ring.place(a) { false }); assertEquals(1000, a.off)
        assertEquals(emptyList(), ring.place(b) { false }); assertEquals(1040, b.off)
        // the third does not fit at the pointer: it wraps to the start and evicts a
        val c = rec(6, 40)
        assertEquals(listOf(a), ring.place(c) { false })
        assertEquals(1000, c.off); assertEquals(-1, a.off)
        assertEquals(listOf(b, c).toSet(), ring.resident.toSet())
        // a fourth would evict b at the pointer or c at the start; both pinned: refused, nothing changes
        val d = rec(9, 40)
        assertNull(ring.place(d) { it === b || it === c })
        assertEquals(-1, d.off); assertEquals(1040, b.off); assertEquals(1000, c.off); assertEquals(2, ring.resident.size)
        // with only b pinned it goes at the start, evicting c
        assertEquals(listOf(c), ring.place(d) { it === b })
        assertEquals(1000, d.off); assertEquals(-1, c.off)
        val c2 = rec(6, 40)
        // sizes are padded to 4 and bounded by the reserve
        assertEquals(44, rec(12, 41).size4)
        assertNull(ring.place(rec(12, 101)) { false })
        ring.free(b); assertEquals(-1, b.off); assertEquals(listOf(d), ring.resident)
        ring.clear(); assertEquals(-1, d.off); assertTrue(ring.resident.isEmpty())
        assertEquals(-1, c2.off)
    }

    @Test
    fun theLedgerProvesByCountingWaitsOutTheSettleWindowAndKeepsRecordsOnAnExtraBump() {
        val l = CacheLedger()
        assertTrue(l.answer(gen = 7, sendAcked = 0, sendAtlasAcked = 0, settled = true) is CacheLedger.Verdict.Base)
        l.writeAcked(2, atlas = true); l.writeAcked(3, atlas = false)
        // a matching count proves whether or not the window has passed
        val proven = l.answer(gen = 12, sendAcked = 5, sendAtlasAcked = 2, settled = false)
        assertTrue(proven is CacheLedger.Verdict.Proven && proven.upTo == 5L, "$proven")
        // one write's bump not there yet: pending inside the window, short after it, a shortfall on the
        // second settled short reading
        l.writeAcked(2, atlas = false)
        assertTrue(l.answer(13, 7, 2, settled = false) is CacheLedger.Verdict.Pending)
        assertTrue(l.answer(13, 7, 2, settled = false) is CacheLedger.Verdict.Pending)
        assertEquals(0, l.shortReads, "a pending reading counts for nothing")
        assertTrue(l.answer(13, 7, 2, settled = true) is CacheLedger.Verdict.Short)
        val sf = l.answer(13, 7, 2, settled = true)
        assertTrue(sf is CacheLedger.Verdict.Shortfall && sf.missing == 1L && !sf.atlasInvolved && sf.fromAcked == 5L && sf.toAcked == 7L, "$sf")
        // re-based at 13: the bump that landed late reads as an extra one — re-based, nothing dropped
        l.writeAcked(1, atlas = true)
        val fo = l.answer(16, 8, 3, settled = true)
        assertTrue(fo is CacheLedger.Verdict.Foreign && fo.extra == 2L && fo.toAcked == 8L, "$fo")
        assertEquals(16L, l.baseGen)
        // an atlas chunk in a shortfall's interval implicates the atlas
        l.writeAcked(1, atlas = true)
        assertTrue(l.answer(16, 9, 4, settled = true) is CacheLedger.Verdict.Short)
        val sf2 = l.answer(16, 9, 4, settled = true)
        assertTrue(sf2 is CacheLedger.Verdict.Shortfall && sf2.atlasInvolved, "$sf2")
        // a lost write: the next reading can only re-base
        l.writeAcked(1, atlas = false); l.writeLost()
        assertTrue(l.answer(17, 10, 4, settled = true) is CacheLedger.Verdict.Base)
        assertTrue(l.answer(17, 10, 4, settled = true) is CacheLedger.Verdict.Proven)
        l.reset(); assertNull(l.baseGen); assertEquals(0L, l.acked)
    }

    @Test
    fun aRecordKeyIsTheLayoutObjectAndItsLines() {
        val a = Any(); val b = Any()
        assertEquals(StageKey(a, 10, 3, 24, 596), StageKey(a, 10, 3, 24, 596))
        assertTrue(StageKey(a, 10, 3, 24, 596) != StageKey(b, 10, 3, 24, 596))
        assertTrue(StageKey(a, 10, 3, 24, 596) != StageKey(a, 13, 3, 24, 596))
        assertTrue(StageKey(a, 10, 3, 24, 596) != StageKey(a, 10, 3, 30, 596))
        val r = rec(10, 8)
        assertTrue(r.covers(10) && r.covers(12) && !r.covers(13) && !r.covers(9))
        assertNotNull(r.key)
    }
}
