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
import kotlinx.coroutines.flow.map
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
        /** This many stage-check readings taken after the first STAGE write read the generation one
         *  short — the decode still on its way, as the glass shows it (§66.1: the bump lands up to a
         *  second after the ack; the model bumps at once). */
        @Volatile var shortStageChecks = 0
        /** With [lagEveryStageWrite], every STAGE write sets [lagging]: stage-check readings read one
         *  short until the test clears it — a decode that lags its ack for as long as the test says. */
        @Volatile var lagEveryStageWrite = false
        @Volatile var lagging = false
        @Volatile private var stageWritten = false
        // ---- 0.64: the count's reader outside the Reader, the lanes under a reading, a lost STAGE write
        /** This many `cache count` readings taken after the first ATLAS write read one short (never the base). */
        @Volatile var shortCountReads = 0
        @Volatile private var atlasWritten = false
        /** 0.64 R1: this many `cache count` readings from now on are NOT answered — null at once, as the
         *  transport answers when its first write fails or its session is ending — and when each was asked. */
        val nullCountReads = java.util.concurrent.atomic.AtomicInteger(0)
        val countAskedAtMs = java.util.concurrent.CopyOnWriteArrayList<Long>()
        /** While set, every ATLAS submit re-arms [shortCountReads] to 1: the first `cache count` reading after
         *  each atlas message reads one short — the glass's lag (the bump lands up to a second after the
         *  ack, a reading is back in about 40 ms), which the model does not have. */
        @Volatile var shortCountAfterEveryAtlas = false
        /** While set and not complete, a `stage check` reading waits before it is asked. */
        @Volatile var holdStageChecks: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val stageChecksAsked = java.util.concurrent.atomic.AtomicInteger(0)
        /** 1-based ordinals of STAGE flushes whose completion the shell sees as not ok (the ack never came). */
        @Volatile var failStageDone: Set<Int> = emptySet()
        /** P5 item 5: called with each STAGE flush before it reaches the transport (its writes not yet out). */
        @Volatile var onStageSubmit: ((FlushRequest) -> Unit)? = null
        private val failIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        private var stageN = 0
        /** "count" when a reading is answered, "stage" when a STAGE flush is submitted — in order. */
        private val traceLog = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun trace(): List<String> = traceLog.toList()
        override val events: kotlinx.coroutines.flow.Flow<wm.damage.core.transport.TransportEvent> = inner.events.map { ev ->
            if (ev is wm.damage.core.transport.TransportEvent.FlushDone && ev.id in failIds) ev.copy(ok = false, error = "test: the ack never came") else ev
        }
        override suspend fun telemetry(label: String): wm.damage.core.wire.DamageMsg.Telemetry? {
            if (label == "stage check") { stageChecksAsked.incrementAndGet(); holdStageChecks?.await() }
            if (label == "cache count") {
                countAskedAtMs.add(System.currentTimeMillis())
                if (nullCountReads.getAndUpdate { maxOf(0, it - 1) } > 0) return null
            }
            val t = inner.telemetry(label)
            traceLog += "count"
            if (t == null) return null
            val gen = t.cacheGen
            if (label == "cache count" && atlasWritten && gen != null && shortCountReads > 0) {
                shortCountReads--
                return wm.damage.core.wire.DamageMsg.Telemetry(t.fields + (17 to gen - 1))
            }
            if (label != "stage check" || !stageWritten || gen == null) return t
            if (lagging) return wm.damage.core.wire.DamageMsg.Telemetry(t.fields + (17 to gen - 1))
            if (shortStageChecks <= 0) return t
            shortStageChecks--
            return wm.damage.core.wire.DamageMsg.Telemetry(t.fields + (17 to gen - 1))
        }
        override suspend fun submit(flush: FlushRequest): Long {
            var f = flush
            if (flush.label == "STAGE") { stageWritten = true; if (lagEveryStageWrite) lagging = true }
            if (flush.label == "STAGE" && refuseStageSubmits) throw IllegalStateException("test: the lane refuses the write")
            if (flush.label == "STAGE" && breakStageWrites) f = flush.copy(ops = flush.ops.map { op ->
                if (op is DisplayOp.CacheWrite2) {
                    val p = op.payload.copyOf()
                    p[1] = 0xFF.toByte(); p[2] = 0xFF.toByte()      // off4 = 0xFFFF: 256 KiB, past the 160 KiB cache
                    DisplayOp.CacheWrite2(p)
                } else op
            })
            synchronized(flushes) { flushes.add(f) }
            if (flush.label == "ATLAS") { atlasWritten = true; if (shortCountAfterEveryAtlas) shortCountReads = 1 }
            if (flush.label != "STAGE") return inner.submit(f)
            onStageSubmit?.invoke(f)
            traceLog += "stage"
            val fail = synchronized(this) { ++stageN in failStageDone }
            val id = inner.submit(f)
            if (fail) failIds.add(id)
            return id
        }
        fun all() = synchronized(flushes) { flushes.toList() }
    }

    /** Two hundred lines of glyph-like text in one face; the layout key is swappable from the test. */
    private class Doc(private val tx: TextRasterizer) : DamageWindow("doc", "Doc", IconKind.READER) {
        val model = DocModel()
        @Volatile var layoutKey: Any = Any()
        /** Text appended to chosen lines (characters no other line holds). */
        @Volatile var extra: Map<Int, String> = emptyMap()
        /** Lines per notch (a strip is one notch's lines): 3, or more where a test needs a record of two writes. */
        @Volatile var step = 3
        private val f = FontSpec(Face.SYSTEM, 18)
        override fun view() = WindowView.DocView(model, { 200 }, 24,
            { g, i, r -> tx.draw(g, r.x + 4, r.y + 4, "line $i " + LINE.substring(i % 7) + (extra[i] ?: ""), f, ((i % 10) + 3) * 17) },
            {}, stepLines = { step }, contentKey = { layoutKey })
        override fun summary() = Summary("doc")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(scope: CoroutineScope, timing: SimTransport.Timing = SimTransport.Timing(instant = true)) {
        val tmp = Files.createTempDirectory("damage-staging")
        val sim = GlassFirmwareSim().also { it.damageContract = 2 }
        val text = CachedText(GlyphyText())
        val simT = SimTransport(sim, scope, timing)
        val spy = SpyTransport(simT)
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

        /** Decided on the answer that ended the wait ([waitUntil]; 0.64 R1, review 1a RD-7). */
        suspend fun until(what: String, maxMs: Long = 10_000, cond: () -> Boolean) = waitUntil(maxMs, 5, {
            "$what — proven=${shell.stagedProvenCount} resident=${shell.stagedResidentCount} " +
                "lines=${shell.stagedProvenLines} live=${shell.cachedFontsLive.size} stage notes=${notes("stage").takeLast(4)} " +
                "atlas notes=${notes("atlas").takeLast(3)}" }, cond)

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

        /** Everything posted to the shell before this call has been handled, and its pump has run. */
        suspend fun barrier() {
            val d = kotlinx.coroutines.CompletableDeferred<Unit>()
            shell.services.runOnShell { d.complete(Unit) }
            d.await()
        }
        fun cacheNotices(): List<wm.damage.core.shell.Notifications.Notice> =
            (listOfNotNull(shell.notifications.current) + shell.notifications.queued()).filter { it.source == "DAMAGE · cache" }
        /** Dismiss whatever box is up (a tap on a focused box), and the ones queued behind it. */
        suspend fun dismissNotices() {
            val t0 = System.currentTimeMillis()
            // decided on the read that ended the loop (RD-7's class): a box raised after it is not this wait's
            var up = shell.notifications.current
            while (up != null && System.currentTimeMillis() - t0 < 20_000) {
                if (shell.notifications.focused && !shell.notifications.animating) shell.postGesture(EvenHubMsg.EV_CLICK)
                delay(60)
                up = shell.notifications.current
            }
            assertNull(up, "the boxes were dismissed")
            settle("after the dismissal")
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

    /**
     * P5 item 5, the staged-strip case (wait-no-escape finding 2's corrected condition): a staged strip is ONE flush
     * of several cache writes, each holding a window slot, and tracked as the stage's flush — not among the display
     * flushes in flight. Its two writes' acks lost leave the reserve holding with no display flush in flight; a
     * yield keyed on display flushes never comes. Fixed (keyed on the slots the room check reads): two idle ticks,
     * the held repaint goes out, its ack releases the two writes ("acked first") and the stage flush fails.
     */
    @Test
    fun aStagedStripWhoseAcksAreLostDoesNotHoldTheOtherFrames(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope, SimTransport.Timing(ackMs = 5, bytesPerSec = 5_000_000.0, instant = false))
        try {
            rig.doc.step = 4                     // a strip of four lines: one record of two writes (3,064 B each at most)
            val drop = java.util.concurrent.atomic.AtomicInteger(0)
            val writes = java.util.concurrent.atomic.AtomicInteger(0)
            rig.simT.notifyFilter = { _, p -> !(isImageAckOnly(p) && drop.getAndUpdate { maxOf(0, it - 1) } > 0) }
            // the FIRST staged strip's writes lose their acks (set before its submit; nothing else is in flight then:
            // a strip is written only into an empty window)
            rig.spy.onStageSubmit = { f -> if (writes.compareAndSet(0, f.ops.size)) drop.set(f.ops.size) }
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.settle("open doc")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the first strip's writes are out with their acks lost: writes=${writes.get()} inFlight=${rig.simT.state.value.inFlight}") {
                writes.get() > 0 && drop.get() == 0 && rig.simT.state.value.inFlight == writes.get()
            }
            assertEquals(2, writes.get(), "the staged strip went out in two writes (the case the pin reads): ${rig.notes("stage").takeLast(3)}")
            val held = rig.all().size
            nonInputRepaint(rig.shell, 2)
            assertTrue(rig.all().size == held && "pending" in rig.shell.quiescenceReport(),
                "the repaint is held by the reserve: ${rig.shell.quiescenceReport()}")
            rig.shell.postIdleTick(); rig.shell.postIdleTick()
            rig.until("the held repaint went out past the reserve: ${rig.all().size - held} flush(es)") { rig.all().size > held }
            rig.until("the strip's two writes are released by the later ack and its flush fails") {
                rig.notes("stage").any { "acked first" in it } || (Files.exists(rig.tmp.resolve("journal.jsonl")) &&
                    Files.readAllLines(rig.tmp.resolve("journal.jsonl")).any { "\"ev\":\"done\"" in it && "acked first" in it })
            }
            assertTrue(Files.readAllLines(rig.tmp.resolve("journal.jsonl")).none { "counter cycled" in it }, "released by a later ack, not the counter")
            rig.simT.notifyFilter = null
            rig.settle("after the repair")
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
            // waited for, not read once: `stageClear` publishes the count before it writes its note, so a
            // single read of the journal right after the count reads 0 can come a few hundred
            // microseconds early (one miss in sixty runs on the 0.64 P2 tree, cause not proven — this
            // is the candidate that fits the rate). If the registry was empty BEFORE the row went off
            // (the other candidate) the note never comes and this still fails, saying so.
            rig.until("the clear says why: cached text went off") { rig.notes("stage").any { "cached text went off" in it } }
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

    /**
     * `HANDOFF.md` §73: a reading that comes back short inside the settle window is asked again on
     * its own pump, half a second on. The verdict used to set the pace and schedule nothing, so on a
     * quiet link the re-read waited for the 5 s idle tick and the strip sat acked and not drawable
     * that long — and on glass the FIRST reading after a write is normally short. The pin is the gap
     * between a `pending` note and the stage note after it, twice in a row: the second re-read is a
     * whole idle period after the first on a tree without the pump.
     *
     * On a PACED link, because that is where the defect lives: an `instant` transport runs its
     * keepalive and battery poll every 50–60 ms, each answer is a shell message, every message is
     * followed by a pump — and the re-read then happens on time by accident (this pin stayed green
     * with the pump removed until the rig's link was given real pacing).
     */
    @Test
    fun aPendingReadingIsAskedAgainOnItsOwnPumpNotTheIdleTick(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope, SimTransport.Timing(ackMs = 5, bytesPerSec = 5_000_000.0, instant = false))
        try {
            rig.spy.shortStageChecks = 2
            rig.upWithTheDocStaged()
            fun t(line: String) = Regex("\"t\":(\\d+)").find(line)!!.groupValues[1].toLong()
            val stage = rig.notes("stage")
            val pending = stage.withIndex().filter { "pending" in it.value }
            assertEquals(2, pending.size, "two readings came back short inside the settle window: $stage")
            for ((i, line) in pending) {
                val next = stage.getOrNull(i + 1) ?: throw AssertionError("nothing followed the pending reading: $stage")
                val gap = t(next) - t(line)
                assertTrue(gap < 2_000, "the re-read came ${gap} ms after the pending reading — its own pump is half a second: $stage")
            }
            assertTrue(stage.any { "proved" in it }, "and the record was proven: $stage")
            assertTrue(rig.notes("stage").none { "shortfall" in it || "never landed" in it }, "a pending reading counts for nothing")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /**
     * `HANDOFF.md` §73: a reading still short AFTER the settle window is asked again one settle
     * window later, on a pump of its own. Two guards, each with its own observable:
     *  - the wait is the settle window, not the 500 ms re-read pace — the second settled short
     *    reading is the one that makes a shortfall, so at 500 ms a decode 2.5 s behind its ack is
     *    called lost and its record dropped. The test pumps the shell 0.7–1.7 s after the short
     *    reading while the decode still lags: nothing may be read then;
     *  - the re-read comes when the window ends, not with the 5 s idle tick. Measured on two
     *    records in a row: without the pump one re-read can land on time by the tick's phase.
     * The decode lags from each STAGE write until 1.8 s after the shell's settled short reading
     * of it, then the count is right. On a paced link (see the pin above).
     */
    @Test
    fun aShortReadingIsAskedAgainASettleWindowLaterOnItsOwnPump(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope, SimTransport.Timing(ackMs = 5, bytesPerSec = 5_000_000.0, instant = false))
        try {
            rig.spy.lagEveryStageWrite = true
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.settle("open doc")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            fun t(line: String) = Regex("\"t\":(\\d+)").find(line)!!.groupValues[1].toLong()
            fun shorts() = rig.notes("stage").filter { "short after the settle window" in it }
            var handled = 0
            var pokes = 0
            val t0 = System.currentTimeMillis()
            while (handled < 2 && System.currentTimeMillis() - t0 < 40_000) {
                val s = shorts()
                if (s.size > handled) {
                    val at = t(s[handled])
                    // pump the shell inside the paced window, the decode still lagging
                    while (System.currentTimeMillis() < at + 1_700) {
                        if (System.currentTimeMillis() >= at + 700) { rig.shell.services.runOnShell { }; pokes++ }
                        delay(100)
                    }
                    while (System.currentTimeMillis() < at + 1_800) delay(5)
                    rig.spy.lagging = false                  // the decode landed: the count is right from here
                    handled++
                    rig.until("the record is proven after its settled short reading", maxMs = 15_000) {
                        rig.notes("stage").count { "proved" in it } >= handled
                    }
                } else delay(5)
            }
            val stage = rig.notes("stage")
            assertEquals(2, handled, "two records each had a settled short reading: $stage")
            assertTrue(pokes >= 10, "the shell was pumped inside both windows ($pokes)")
            val idx = stage.withIndex().filter { "short after the settle window" in it.value }
            assertEquals(2, idx.size, "one settled short reading per record, never a second: $stage")
            for ((i, line) in idx) {
                val next = stage.getOrNull(i + 1) ?: throw AssertionError("nothing followed the short reading: $stage")
                val gap = t(next) - t(line)
                assertTrue("proved" in next, "the reading after a settled short one proves the record: $next")
                assertTrue(gap >= CacheLedger.SETTLE_MS, "nothing is read inside the settle window after a short reading (${gap} ms): $stage")
                assertTrue(gap < CacheLedger.SETTLE_MS + 600, "the re-read came ${gap} ms after the short reading — its own pump is the settle window: $stage")
            }
            assertTrue(stage.none { "shortfall" in it || "never landed" in it || "dropped" in it }, "a decode 3.9 s behind its ack loses nothing: $stage")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /**
     * `HANDOFF.md` §73: a strip whose render shows a face characters it has not drawn is not written
     * with the host's pixels for them. The render is the atlas's cue: the face gains the glyphs
     * first, and the strip is rendered again and written with them packed. Written at once, the
     * record stops matching the page the moment the glyphs are promoted — a staged notch lost, the
     * stale record resident in its place, and its cache writes spent for nothing (three strips of
     * ~14 KB per promotion of the book's face on the simulator walk of 2026-11-09). The observable
     * is the order in the journal: the face's gain, then the strip's ONE write. (This rasterizer's
     * host glyphs equal its cached ones, so the proof at the notch cannot tell here; AWT's do not.)
     * Mutation watched: the deferral removed (the strip is submitted before the gain).
     */
    @Test
    fun aStripThatShowsAFaceNewCharactersIsWrittenAfterTheFaceGainsThem(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.doc.extra = mapOf(23 to " QXZ")          // first shown by the strip of lines 22..24, two strips ahead of the page
            rig.upWithTheDocStaged()
            rig.until("the strip with the new characters is staged and proven") { rig.shell.stagedProvenLines.any { it.first == 22 } }
            rig.settle("staged")
            val lines = Files.readAllLines(rig.tmp.resolve("journal.jsonl"))
            val gained = lines.indexOfFirst { "gained glyphs" in it && "QXZ" in it }
            val writes = lines.withIndex().filter { "\"kind\":\"stage\"" in it.value && "lines 22..24" in it.value && "submitted" in it.value }.map { it.index }
            assertTrue(gained >= 0, "the face gained the strip's characters: ${rig.notes("atlas").takeLast(5)}")
            assertEquals(1, writes.size, "the strip was written once: ${rig.notes("stage")}")
            assertTrue(writes[0] > gained, "and only after the face had gained what it shows (the gain is journal line $gained, the write ${writes[0]})")
            // the two notches up to it ship staged records, the second the strip in question
            repeat(2) { notch ->
                rig.until("strip for notch $notch staged") { rig.shell.stagedProvenLines.any { it.first == rig.nextExposed() } }
                rig.settle("before notch $notch")
                val n0 = rig.all().size
                rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $notch")
                val flushes = rig.all().drop(n0).filter { it.label != "STAGE" }
                assertTrue(flushes.flatMap { it.ops }.any { it is DisplayOp.DrawImage2 }, "notch $notch shipped a staged draw: ${flushes.map(::shape)}")
                rig.assertGlassMatchesBelief("after notch $notch")
            }
            assertTrue(Files.readAllLines(rig.tmp.resolve("journal.jsonl")).none { "stage-proof" in it }, "no staged record failed its proof")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /** The paced re-read's pump is posted only to a running shell: a stopped shell's queue has no
     *  reader, a message posted to it is counted and never handled, and the shell then reads as
     *  busy to every wait that asks (`isQuiescent`) until some later session drains it.
     *  Mutation watched: the pump posted whatever the shell's state. */
    @Test
    fun aPacedReReadPostsNothingToAStoppedShell(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope, SimTransport.Timing(ackMs = 5, bytesPerSec = 5_000_000.0, instant = false))
        try {
            rig.spy.lagEveryStageWrite = true
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.settle("open doc")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("a reading came back short inside the settle window: a re-read is paced") { rig.notes("stage").any { "pending" in it } }
            rig.shell.stop()
            assertTrue("queued=" !in rig.shell.quiescenceReport(), "the shell stopped with nothing queued: ${rig.shell.quiescenceReport()}")
            delay(CacheLedger.REREAD_PACE_MS + 400)          // the pace ends here, with the shell stopped
            assertTrue("queued=" !in rig.shell.quiescenceReport(), "nothing was posted to the stopped shell: ${rig.shell.quiescenceReport()}")
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
            // 0.64: a staged strip's shortfall is said as a notice too (the same class as the atlas's). The
            // box takes focus after its grace, so it is dismissed before the notches below
            val box = rig.cacheNotices().firstOrNull() ?: throw AssertionError("the shortfall raised no notice: ${rig.shell.notifications.current}")
            assertEquals("cache/short", box.thread)
            assertTrue(box.body.startsWith("the glasses acknowledged ") && "staged strip(s) staged again" in box.body, box.body)
            // 0.64 R1 (review 1a, RC-3): the verdict that switches staging off says so in a box of its own
            // class. Three shortfalls a few seconds apart are ONE episode of `cache/short` — posted, updated
            // at the second, and the third (not a power of two) shown nowhere: the body that says
            // "staging is off" was only ever in the journal.
            val off = rig.cacheNotices().filter { it.thread == "cache/staging" }
            assertEquals(1, off.size, "the verdict that switched staging off is a notice of its own: ${rig.cacheNotices()}")
            assertTrue(off[0].body.startsWith("the glasses acknowledged ") && off[0].body.endsWith("staging is off for this session"), off[0].body)
            assertTrue(rig.cacheNotices().none { it.thread == "cache/short" && "staging is off" in it.body }, "one box per verdict: ${rig.cacheNotices()}")
            // (an atlas message read in a short interval — the first notice's own glyphs — goes once more with
            // it: the count cannot say which of an interval's messages was the short one)
            rig.dismissNotices()
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

    /**
     * RC-3, part two (review 1a). The same three strip shortfalls with the glass's lag on every atlas
     * message: the first reading after each one is one short (pending, asked again half a second on).
     * A strip shortfall whose interval holds atlas messages sends those once more — and until this
     * round the strip was staged again INSIDE the re-send's pending pace (the staging lane had no hold
     * on an open count). The strip's own lost write was then read in one interval with re-sends that
     * had been counted, and the verdict said `cache/lost`: "short again after a re-send … nothing more
     * is sent" about writes that landed (watched on the tree before: the second shortfall read
     * "1 short after a re-send (2 re-sent, 2 other write(s) read with it)").
     * Now no strip is taken while a shortfall's re-send is owed or unjudged: the re-send is judged by
     * itself first. Staging still reaches "off for the session" — the hold releases each time.
     * (Still there, and not this pin's: the session's FIRST strip goes out inside the first upload's
     * own pending pace, so the first shortfall's interval holds that upload and sends it once more.)
     * Mutation watched: the hold removed from `pumpStage`.
     */
    @Test
    fun noStripIsStagedWhileAReSendIsUnjudgedSoNoCountedWriteIsCalledLost(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.spy.breakStageWrites = true
            rig.spy.shortCountAfterEveryAtlas = true
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.settle("open doc")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.until("three strip shortfalls: staging switches itself off", maxMs = 60_000) { rig.notes("stage").any { "off for the session" in it } }
            rig.barrier(); rig.barrier()
            assertTrue(rig.cacheNotices().none { it.thread == "cache/lost" }, "no write that was counted is called lost: ${rig.cacheNotices().filter { it.thread == "cache/lost" }}")
            assertTrue(rig.notes("atlas").none { "count short again" in it }, "no re-send is called short again: ${rig.notes("atlas").filter { "count short again" in it }}")
            // the scene's shape: a re-send was made, and judged by itself between two strips (without one this pin measures nothing)
            assertTrue(rig.notes("atlas").any { "\"detail\":\"re-send counted" in it }, "a re-send was counted between the strips: ${rig.notes("atlas").takeLast(6)}")
            assertEquals(1, rig.cacheNotices().count { it.thread == "cache/staging" }, "staging went off, said once: ${rig.cacheNotices()}")
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

    // ---- 0.64: the count is read wherever the shell is (`Shell.pumpLedger`)

    /**
     * S2. At Main, with no window open, a reading that comes back short inside the settle window is
     * asked again on its own pump, half a second on — until 0.64 no reading was asked at Main at
     * all (the reader needed a focused document). On a paced link, where a missing pump shows (see
     * `aPendingReadingIsAskedAgainOnItsOwnPumpNotTheIdleTick`). The notes are the count's own
     * (kind `atlas`): no strip is involved.
     * Mutation watched: the pump removed from the pending verdict (the re-read waits for the idle tick).
     */
    @Test
    fun aPendingReadingAtMainIsAskedAgainOnItsOwnPump(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope, SimTransport.Timing(ackMs = 5, bytesPerSec = 5_000_000.0, instant = false))
        try {
            rig.spy.shortCountReads = 2
            rig.shell.start(); rig.settle("start")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the first upload is judged by the count, at Main") { rig.notes("atlas").any { "\"detail\":\"count ok" in it } }
            fun t(line: String) = Regex("\"t\":(\\d+)").find(line)!!.groupValues[1].toLong()
            val counts = rig.notes("atlas").filter { "\"detail\":\"count " in it }
            val pending = counts.withIndex().filter { "count pending — the cache count" in it.value }
            assertEquals(2, pending.size, "two readings came back short inside the settle window: $counts")
            for ((i, line) in pending) {
                val next = counts.getOrNull(i + 1) ?: throw AssertionError("nothing followed the pending reading: $counts")
                val gap = t(next) - t(line)
                // 1.5 s, not the older pin's 2 s: without the pump the re-read here came 2,044 ms on (watched)
                assertTrue(gap < 1_500, "the re-read came $gap ms after the pending reading — its own pump is half a second: $counts")
            }
            assertTrue(rig.notes("stage").none { "pending" in it }, "no strip is involved: these are not staging's notes: ${rig.notes("stage")}")
            assertTrue(counts.none { "count short" in it }, "a pending reading counts for nothing: $counts")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /**
     * RC-2 (review 1a). A reading of the count that is NOT ANSWERED — the transport's null: its first
     * write failed, or its session is ending — is asked again one pace later (2 s) on a pump of its
     * own. Until this round the null set no pace, and the loop pumps after every message: the same
     * question was asked again within milliseconds, and three unanswered readings — the bound that
     * switches the count and Page staging off for the session — were one event, not three.
     * The observable is the gap between the unanswered ask and the next one: no sooner than the pace
     * (a lower bound: a slow machine can only widen it), and well before anything else pumps the
     * shell (so the pump at the pace's end exists). On a paced link, where a missing pump shows, and in a SECOND
     * session — started with the row already on, so no settings save lands near the pace's end to
     * pump the shell by accident (the save tick is 2 s after a row change: the pending pin above read
     * exactly that under its own mutation).
     * Mutations watched: the pace's test removed from `tryLedgerRead` (the gap is milliseconds); the
     * pump removed from the unanswered branch (the re-ask came 3,168 ms on, with the session's next message).
     */
    @Test
    fun anUnansweredReadingIsAskedAgainAPaceLaterOnItsOwnPump(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope, SimTransport.Timing(ackMs = 5, bytesPerSec = 5_000_000.0, instant = false))
        try {
            fun oks() = rig.notes("atlas").count { "\"detail\":\"count ok" in it }
            rig.shell.start(); rig.settle("start")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the first upload is judged by the count, at Main") { oks() > 0 }
            rig.settle("judged")
            rig.shell.stop()
            val ok0 = oks()
            val asked0 = rig.spy.countAskedAtMs.size
            val unread0 = rig.notes("atlas").count { "count unread" in it }
            rig.spy.nullCountReads.set(1)                    // the next session's base: its first ask is not answered
            rig.shell.start()
            rig.until("the second session's upload is judged after all", maxMs = 20_000) { oks() > ok0 }
            val asks = rig.spy.countAskedAtMs.drop(asked0)
            assertTrue(asks.size >= 2, "the unanswered reading was asked again: $asks")
            val gap = asks[1] - asks[0]
            assertTrue(gap >= 1_900, "an unanswered reading is not asked again inside its pace: the next ask came $gap ms later — ${rig.notes("atlas").takeLast(4)}")
            // 2,800, not the idle tick's 5,000: watched with the pump removed, the re-ask came 3,168 ms on —
            // the next thing that pumps a quiet shell in this scene, about 3 s after the session's start;
            // with the pump it is the pace plus 20 ms
            assertTrue(gap < 2_800, "and it is asked again when the pace ends, on its own pump, not by whatever pumps the shell next: $gap ms")
            val unread = rig.notes("atlas").filter { "count unread" in it }.drop(unread0)
            assertEquals(1, unread.size, "one unanswered reading, said once: $unread")
            assertTrue("asked again in 2000 ms" in unread[0], unread[0])
            assertTrue(rig.notes("atlas").none { "count off" in it } && rig.notes("stage").none { "off for the session" in it },
                "one unanswered reading switches nothing off: ${rig.notes("atlas").takeLast(3)}")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /**
     * S3. The count's reading is the cache's, not staging's: a clear of the registry (here the Page
     * staging row going off and on) no longer drops a reading on its way, and so no longer releases
     * the write lanes under it. Until 0.64 `stageClear` cleared the flag, and the row coming back
     * wrote a strip while the reading that spoke for the writes before it was still out.
     * Mutation watched: `stageClear` clearing the reading's flag again (a STAGE write leaves under it).
     */
    @Test
    fun aStageClearDoesNotReleaseTheLanesUnderAReading(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            rig.spy.holdStageChecks = gate
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.settle("open doc")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("a strip is acked and its reading is out, held") { rig.spy.stageChecksAsked.get() >= 1 }
            rig.settle("the reading held")
            fun cacheWrites() = rig.all().count { it.label == "STAGE" || it.label == "ATLAS" }
            val n = cacheWrites()
            val clears = rig.shell.stageClears
            rig.shell.updateSettings { it.copy(pageStaging = "off") }
            rig.until("the row off clears the registry") { rig.shell.stageClears > clears && rig.shell.stagedResidentCount == 0 }
            rig.shell.updateSettings { it.copy(pageStaging = "on") }
            rig.barrier(); delay(300); rig.barrier()
            assertEquals(n, cacheWrites(), "no cache write while the reading is on its way: ${rig.notes("stage").takeLast(3)}")
            gate.complete(Unit); rig.spy.holdStageChecks = null
            rig.until("staging resumes after the reading and a strip is proven") { rig.shell.stagedProvenCount > 0 }
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /**
     * S4. After a STAGE write whose ack never came (what landed is unknown) the count's base is read
     * BEFORE the next strip is written: `pumpStage`'s backstop, the twin of `pumpAtlas`'s. Without
     * it the strip went out against a count that could only re-base, and was then dropped at that
     * re-base and written a third time.
     * Mutation watched: the base backstop removed from `pumpStage` (no reading between the lost write and the next).
     */
    @Test
    fun aLostStageWriteIsReBasedBeforeTheNextStrip(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.spy.failStageDone = setOf(1)
            rig.upWithTheDocStaged()
            assertTrue(rig.notes("stage").any { "failed" in it && "goes again later" in it }, "the first strip's write was lost: ${rig.notes("stage").take(4)}")
            val t = rig.spy.trace()
            val lost = t.indexOf("stage")
            val next = t.withIndex().first { it.index > lost && it.value == "stage" }.index
            assertTrue(t.subList(lost + 1, next).contains("count"), "a reading of the count stands between the lost write and the next strip: ${t.take(next + 2)}")
            assertTrue(rig.notes("stage").none { "re-based the generation count" in it }, "so no acked strip had to go again at a late re-base: ${rig.notes("stage")}")
            rig.shell.stop()
        } finally {
            scope.cancel()
            rig.tmp.toFile().deleteRecursively()
        }
    }

    /**
     * F1 of the 0.64 design review: staging's three shortfalls count only an interval that held a
     * staged strip's write. With the count read everywhere an atlas-only shortfall is the usual
     * kind — at Main, in a list — and three of them in a session must not switch Page staging off
     * (`aWriteTheGlassesRefusedIsNeverDrawnAndStagingGivesUpBounded` still reaches "off" at three
     * strips). Three atlas-only shortfalls at Main here, each from the `cache=miscount` probe on the
     * simulator; then the document opens and a strip is staged and proven.
     * Mutation watched: the lane test removed from the count (staging is off before the document opens).
     */
    @Test
    fun threeAtlasOnlyShortfallsDoNotSwitchPageStagingOff(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rig = Rig(scope)
        try {
            rig.shell.start(); rig.settle("start")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.until("the first upload is judged") { rig.notes("atlas").any { "\"detail\":\"count ok" in it } }
            repeat(3) { i ->
                rig.until("nothing unread before shortfall ${i + 1}") { rig.shell.cacheCountUnjudged == 0L }
                rig.spy.devProbe("cache", "miscount")
                rig.until("atlas-only shortfall ${i + 1}", maxMs = 20_000) { rig.notes("atlas").count { "count short — RIGHT's generation is" in it } == i + 1 }
                rig.until("its re-send is counted", maxMs = 20_000) { rig.notes("atlas").count { "\"detail\":\"re-send counted" in it } == i + 1 }
            }
            assertTrue(rig.notes("stage").none { "off for the session" in it }, "three atlas-only shortfalls leave staging on: ${rig.notes("stage")}")
            rig.dismissNotices()
            rig.shell.services.runOnShell { rig.shell.services.openWindow("doc", null) }
            rig.until("the document's next strip is staged and proven", maxMs = 20_000) { rig.shell.stagedProvenLines.any { it.first == rig.nextExposed() } }
            assertTrue(rig.notes("stage").none { "off for the session" in it })
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
        assertTrue(sf is CacheLedger.Verdict.Shortfall && sf.missing == 1L && !sf.atlasInvolved && sf.atlasWrites == 0L && sf.fromAcked == 5L && sf.toAcked == 7L, "$sf")
        assertEquals(0L, l.unjudged, "a shortfall re-bases: nothing is left to judge")
        // re-based at 13: the bump that landed late reads as an extra one — re-based, nothing dropped
        l.writeAcked(1, atlas = true)
        val fo = l.answer(16, 8, 3, settled = true)
        assertTrue(fo is CacheLedger.Verdict.Foreign && fo.extra == 2L && fo.toAcked == 8L, "$fo")
        assertEquals(16L, l.baseGen)
        // an atlas chunk in a shortfall's interval implicates the atlas
        l.writeAcked(1, atlas = true)
        assertTrue(l.answer(16, 9, 4, settled = true) is CacheLedger.Verdict.Short)
        val sf2 = l.answer(16, 9, 4, settled = true)
        assertTrue(sf2 is CacheLedger.Verdict.Shortfall && sf2.atlasInvolved && sf2.atlasWrites == 1L, "$sf2")
        // a lost write: the next reading can only re-base
        l.writeAcked(1, atlas = false); l.writeLost()
        assertEquals(1L, l.unjudged, "a write acked past the last agreed reading is owed one")
        val lostBase = l.answer(17, 10, 4, settled = true)
        assertTrue(lostBase is CacheLedger.Verdict.Base && !lostBase.first, "a base after a lost write is not the session's first")
        assertEquals(0L, l.unjudged)
        assertTrue(l.answer(17, 10, 4, settled = true) is CacheLedger.Verdict.Proven)
        // 0.64: a layout replaced under unjudged writes — the next reading can only re-base, whatever it reads
        l.writeAcked(2, atlas = true); l.rebaseNext()
        assertTrue(l.unsure)
        assertTrue(l.answer(18, 12, 6, settled = true) is CacheLedger.Verdict.Base, "one short of two, and no shortfall: it re-bases")
        assertTrue(l.answer(18, 12, 6, settled = true) is CacheLedger.Verdict.Proven)
        l.reset(); assertNull(l.baseGen); assertEquals(0L, l.acked)
        val firstBase = l.answer(3, 0, 0, settled = false)
        assertTrue(firstBase is CacheLedger.Verdict.Base && firstBase.first)
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
