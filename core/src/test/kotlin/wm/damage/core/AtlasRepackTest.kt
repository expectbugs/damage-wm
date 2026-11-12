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
import kotlinx.coroutines.flow.filter
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
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §47 (2026-09-12): a full texture cache EVICTS the faces the
 * current window is not drawing and repacks, instead of leaving every new
 * face as pixels for the rest of the session (the tmux window's mono faces
 * on 2026-09-12, from 12:59 on). The glasses are rewritten from the guard
 * up with every font off the live set meanwhile, and belief equals glass
 * throughout — the simulator writes and draws the cache as the firmware
 * does.
 */
class AtlasRepackTest {

    /** Glyph bytes that DIFFER per size and per character and do not RLE
     *  away — seeded noise per glyph — for faces of 24 px and up (the test's
     *  own), so three of them fill the 64 KiB; the chrome's smaller faces
     *  stay solid boxes and cheap, as real faces are next to these. */
    private class SizedText : TextRasterizer {
        /** Sizes whose ink box reads two rows taller from now on (a face whose metrics moved under
         *  an atlas that already holds some of its glyphs: the rest cannot be rendered to match). */
        @Volatile var taller: Set<Int> = emptySet()
        private fun adv(font: FontSpec) = maxOf(4, font.sizePx / 2)
        override fun measure(text: String, font: FontSpec): Int = text.length * adv(font)
        override fun metrics(font: FontSpec) = FontMetrics(font.sizePx + (if (font.sizePx in taller) 2 else 0), 4, font.sizePx + 6)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
            val a = adv(font)
            val h = font.sizePx + 4
            for ((i, ch) in text.withIndex()) {
                if (ch == ' ') continue
                val x0 = x + i * a
                if (font.sizePx < NOISY_FROM_PX) {
                    surface.fillRect(x0, y + 2, a - 2, 3 + ch.code % 9, level)
                    continue
                }
                val rnd = java.util.Random((ch.code * 131L + font.sizePx) * 7919L)
                for (yy in 0 until h) for (xx in 0 until a - 1) {
                    val on = rnd.nextInt(5) < 2
                    if (on) surface[x0 + xx, y + yy] = level      // Gray8 ignores out-of-range sets
                }
            }
        }
        override fun covers(text: String, font: FontSpec) = true

        companion object { const val NOISY_FROM_PX = 24 }
    }

    /** Rows in whichever faces [fonts] holds right now — the window whose
     *  faces change is the eviction's whole reason. A face is packed with what it has drawn
     *  (`HANDOFF.md` §73), so "Row N text" alone is seventeen glyphs a face and four such faces
     *  fill nothing: every row first draws EVERY cacheable code in its face into a scratch surface
     *  through the recorder's relay (noted as drawn, recorded nowhere, on no panel — the path a
     *  staged strip takes), then its own line on the panel: "Row N" and a slice of the codes that
     *  moves with the row, inside the panel, so the rows ship as cached draws and belief = glass
     *  reads glyphs out of the cache on both sides of a repack. */
    private class SwitchRows(private val tx: TextRasterizer, @Volatile var fonts: List<FontSpec>) :
        DamageWindow("rows", "Rows", IconKind.FILES) {
        private val model = ListModel()
        /** Faces that draw only "Row N" until the test says otherwise (the rest draw every code). */
        @Volatile var thin: Set<FontSpec> = emptySet()
        /** While set, every row draws one icon no other surface draws (a kind and size of its own). */
        @Volatile var rowIcon = false
        /** Appended to what the thin face [suffixFace] draws (a character it was not packed with: one small extension). */
        @Volatile var thinSuffix = ""
        @Volatile var suffixFace: FontSpec? = null
        private val scratch = Gray8(8, 8)
        override fun view(): WindowView = WindowView.ListView(model, { 30 },
            paintRow = { g, i, r, _ ->
                if (rowIcon) wm.damage.core.gfx.IconPaint.drawKind(g, IconKind.MUSIC, r.x + 8, r.y + 4, 18, Level.BODY)
                val f = fonts[i % fonts.size]
                if (f in thin) tx.draw(g, r.x + 40, r.y + 4, "Row $i" + (if (f == suffixFace) thinSuffix else ""), f, Level.BODY)
                else {
                    (tx as? CachedText)?.via(scratch, 0, 0, wm.damage.core.geom.Rect(0, 0, 0, 0)) { tx.draw(scratch, 0, 0, EVERY_CODE, f, Level.BODY) }
                    tx.draw(g, r.x + 40, r.y + 4, "Row $i " + ASCII.substring((i * 7) % (ASCII.length - 12)).take(12), f, Level.BODY)
                }
            },
            paintLens = { g, r, i -> tx.draw(g, r.x + 44, r.y + 6, "Row $i", fonts[i % fonts.size], Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("30 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    companion object {
        /** The printable ASCII a v1 table holds too (no space: the slice is drawn after one). */
        val ASCII: String = (33..126).map { it.toChar() }.joinToString("")
        /** Every code a v2 table can hold a glyph for, in order. */
        val EVERY_CODE: String = buildString {
            val b = wm.damage.core.comp.GlyphAtlas.allCodes(true)
            var c = b.nextSetBit(0)
            while (c >= 0) { append(c.toChar()); c = b.nextSetBit(c + 1) }
        }
    }

    /** 0.64, for the count's pin: sees every flush, can break the next ATLAS flushes (the first
     *  write's offset rewritten past the cache: acked, refused whole, never counted) and hold the
     *  count's readings. The shell is shown a mirror that is not exact and none of the mirror's own
     *  decode faults: on glass the phone's mirror applies a well-formed write the glasses dropped
     *  and sees nothing, where this rig's mirror refuses the malformed one as the glasses do. */
    private class CountSpy(private val inner: wm.damage.core.transport.Transport) : wm.damage.core.transport.Transport by inner {
        private val flushes = ArrayList<wm.damage.core.transport.FlushRequest>()
        @Volatile var breakNextAtlas = 0
        @Volatile var holdCounts: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val countsAsked = java.util.concurrent.atomic.AtomicInteger(0)
        override val mirror: wm.damage.core.transport.LensPanels get() = inner.mirror.let { m ->
            object : wm.damage.core.transport.LensPanels by m { override val exact: Boolean get() = false } }
        override val events: kotlinx.coroutines.flow.Flow<wm.damage.core.transport.TransportEvent> = inner.events.filter { ev ->
            !(ev is wm.damage.core.transport.TransportEvent.Fault && ev.what.startsWith("mirror/")) }
        override suspend fun telemetry(label: String): wm.damage.core.wire.DamageMsg.Telemetry? {
            countsAsked.incrementAndGet()
            holdCounts?.await()
            return inner.telemetry(label)
        }
        override suspend fun submit(flush: wm.damage.core.transport.FlushRequest): Long {
            synchronized(flushes) { flushes.add(flush) }
            val brk = flush.label == "ATLAS" && synchronized(this) { if (breakNextAtlas > 0) { breakNextAtlas--; true } else false }
            return inner.submit(if (!brk) flush else flush.copy(ops = flush.ops.map { op ->
                if (op is wm.damage.core.transport.DisplayOp.CacheWrite2) wm.damage.core.transport.DisplayOp.CacheWrite2(
                    op.payload.copyOf().also { it[1] = 0xFF.toByte(); it[2] = 0xFF.toByte() }) else op }))
        }
        fun all() = synchronized(flushes) { flushes.toList() }
        fun atlas() = all().filter { it.label == "ATLAS" }
        fun keyframes() = all().count { f -> f.ops.any { it is wm.damage.core.transport.DisplayOp.Keyframe } }
        /** Data bytes of the ATLAS flushes from index [from] on (the payloads less their headers, near enough: the payload sizes). */
        fun atlasBytes(from: Int) = atlas().drop(from).sumOf { f -> f.ops.sumOf { (it as? wm.damage.core.transport.DisplayOp.CacheWrite2)?.payload?.size ?: 0 } }
    }

    private class Rig(scope: CoroutineScope, val text: CachedText, val win: SwitchRows, contract: Int = 0, spied: Boolean = false) {
        val tmp = Files.createTempDirectory("damage-repack")
        val sim = GlassFirmwareSim().also { if (contract > 0) it.damageContract = contract }
        val spy: CountSpy? = if (spied) CountSpy(SimTransport(sim, scope, SimTransport.Timing(instant = true))) else null
        val transport: wm.damage.core.transport.Transport = spy ?: SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val journalPath = tmp.resolve("journal.jsonl")
        val shell = Shell(text, transport, Persistence(tmp.resolve("state.json")), journalPath, scope)

        init { shell.register(win) }

        fun atlasNotes(): List<String> = if (!Files.exists(journalPath)) emptyList()
            else Files.readAllLines(journalPath).filter { it.contains("\"kind\":\"atlas\"") }

        /** Cached string draws submitted so far (the journal's op records). */
        fun cachedDraws(): Int = if (!Files.exists(journalPath)) 0
            else Files.readAllLines(journalPath).filter { it.contains("\"ev\":\"submit\"") }.sumOf { Regex("\"op\":\"drawtext2?\"").findAll(it).count() }

        /** Read-backs the glasses ANSWERED (`Shell.atlasCheckThenLive` journals one per answer). */
        fun cacheChecks(): Int = if (!Files.exists(journalPath)) 0
            else Files.readAllLines(journalPath).count { it.contains("\"kind\":\"glass\"") && it.contains("atlas check:") }

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) return
                delay(10)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }

        /** Decided on the answer that ended the wait ([waitUntil]; 0.64 R1, review 1a RD-7). */
        suspend fun until(what: String, cond: () -> Boolean) = until({ what }, cond)
        /** The same, with a text that holds a live value: [what] is read when the wait gives up. */
        suspend fun until(what: () -> String, cond: () -> Boolean) = waitUntil(15_000, 10, {
            "${what()} — live=${shell.cachedFontsLive} notes=${atlasNotes().takeLast(8)}" }, cond)

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

    /**
     * 2026-09-16 review. A repack keeps the same `GlyphAtlas` object, puts every record at a NEW
     * offset and rewinds `ackedBytes` to the guard — so the read-back gate of §62.3 item 4 /
     * §63.1, keyed on the atlas's identity plus the acked watermark its last answer covered,
     * matched the repacked layout against a mark on a byte axis that no longer exists and let the
     * whole thing go live unproven. That is the §63.1 defect one call site along, and a repack is
     * where it costs most: every offset has moved, so a refused write draws the wrong glyphs.
     *
     * The gate is owed by an EPOCH now. What this test measures (review 1a, RC-4): the repacked
     * layout asks the glasses again — and no more than that. It no longer tells the epoch from the
     * gate's other terms: since `HANDOFF.md` §73 the gate is also owed by MESSAGES, and
     * `GlyphAtlas.ackedMsgs` is never reset, so the repacked upload's acks put it past the last
     * answer's mark and the read-back is asked again with the epoch term, the four mark resets or
     * the repack's `atlasCheckOwed` call removed (read, not run). The epoch's own pin is on the
     * answer's side: `GlyphSubsetShellTest.aReadBackHeldAcrossALapseIsDroppedByItsEpoch`.
     */
    @Test
    fun aRepackedLayoutIsReadBackAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(SizedText())
            val big = listOf(24, 28, 32, 36).map { FontSpec(Face.SYSTEM, it) }
            val small = FontSpec(Face.SYSTEM, 26)
            val win = SwitchRows(text, big)
            val rig = Rig(scope, text, win, contract = 2)
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the atlas uploads and the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the upload")
            rig.until("the first layout is read back") { rig.cacheChecks() > 0 }
            val checksBefore = rig.cacheChecks()
            assertTrue(rig.atlasNotes().any { "cache is full" in it }, "the four faces must fill the cache")

            win.fonts = listOf(small)
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() + 6) {
                rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                rig.settle("notch $it")
            }
            rig.until("the new face is live after a repack") { small in rig.shell.cachedFontsLive }
            rig.settle("after the repack's upload")
            assertTrue(rig.atlasNotes().any { "repacked:" in it }, "a repack ran")
            rig.until("the repacked layout is read back too") { rig.cacheChecks() > checksBefore }
            rig.assertGlassMatchesBelief("after the repack")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /**
     * 0.64, R1: a repack is a clean boundary for the count. A message acked and not counted just
     * before a forced repack (its reading still out) leaves the books unjudged at the repack — the
     * journal says so and a keyframe puts right whatever a draw took from it (a repack's own
     * repaint sends no difference where belief already equals the truth) — the count re-bases
     * before the new layout's first message, the new layout is judged whole, and nothing is called
     * short or sent again for a message of the layout before: the repacked layout goes up once.
     * Mutations watched: `atlasCheckOwed` without the clear and the re-base (a shortfall is taken
     * across the two layouts: the old message's ranges are asked of the new layout); the keyframe
     * removed from it (no keyframe follows the repack).
     */
    @Test
    fun aRepackIsACleanBoundaryForTheCount(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(SizedText())
            val thin = FontSpec(Face.SYSTEM, 26)
            // a face under this rasterizer's noise threshold: its glyphs are a handful of solid boxes, so
            // a character it gains is a record the cache already holds — a message that only rewrites
            // table entries, which needs no room in a cache that is full
            val tiny = FontSpec(Face.SYSTEM, 20)
            val big = listOf(24, 28, 32, 36).map { FontSpec(Face.SYSTEM, it) }
            val win = SwitchRows(text, big + thin + tiny).also { it.thin = setOf(thin, tiny); it.suffixFace = tiny }
            val rig = Rig(scope, text, win, contract = 2, spied = true)
            val spy = rig.spy!!
            fun count(head: String) = rig.atlasNotes().filter { "\"detail\":\"$head" in it }
            fun errors() = Files.readAllLines(rig.journalPath).filter { "\"kind\":\"error\"" in it }
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the thin faces are live beside the big ones") { thin in rig.shell.cachedFontsLive && tiny in rig.shell.cachedFontsLive }
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("warm $it") }
            rig.until("the first layout is judged by the count") { count("count ok").isNotEmpty() && rig.shell.cacheCountUnjudged == 0L }
            rig.settle("judged")

            // the last message before the repack: the tiny face gains one character — acked, refused
            // whole on the glasses, and its reading of the count is held on its way
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val asked = spy.countsAsked.get()
            val sentBefore = spy.atlas().size
            spy.breakNextAtlas = 1
            spy.holdCounts = gate
            win.thinSuffix = "Q"
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("the notch that reveals Q")
            rig.until("the extension's message is acked and its reading is out") { spy.atlas().size > sentBefore && spy.countsAsked.get() > asked }
            rig.settle("the reading held")
            assertEquals(sentBefore + 1, spy.atlas().size, "one message, not counted: ${rig.atlasNotes().takeLast(4)}")
            val kf = spy.keyframes()
            val before = spy.atlas().size

            // the window now draws ONLY that face, and every code in it: more than the cache has room for
            win.fonts = listOf(thin); win.thin = emptySet()
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() + 8) {
                rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                rig.settle("notch $it")
            }
            assertTrue(rig.atlasNotes().any { "repacked:" in it }, "a repack made the room within the notches: ${rig.atlasNotes().takeLast(8)}")
            assertEquals(1, count("count re-based — 1 atlas message(s) acked before it are not judged (the layout was replaced); keyframing").size,
                "the unjudged message left the books at the repack, said once: ${count("count")}")
            rig.until("a keyframe follows") { spy.keyframes() > kf }
            assertEquals(before, spy.atlas().size, "the new layout waits for the count's reading")
            gate.complete(Unit); spy.holdCounts = null
            rig.until("the face in use holds what it draws") { thin in rig.shell.cachedFontsLive && (text.atlas?.entry(thin)?.packedCount ?: 0) >= 90 }
            rig.until("the new layout is judged whole") { rig.shell.cacheCountUnjudged == 0L && count("count ok").size >= 2 }
            rig.settle("after the repack's upload")
            delay(2 * wm.damage.core.shell.CacheLedger.SETTLE_MS + 800)
            rig.settle("two settle windows on")
            assertTrue(count("count short").isEmpty(), "nothing is called short across the two layouts: ${count("count short")}")
            assertTrue(errors().isEmpty(), "and nothing of the old layout was asked of the new one: ${errors()}")
            assertEquals(null, rig.shell.notifications.current?.takeIf { it.source == "DAMAGE · cache" }, "no notice")
            val layout = text.atlas!!.used
            val sent = spy.atlasBytes(before)
            assertTrue(sent >= layout - 4 && sent < layout * 3 / 2, "the repacked layout went up once: $sent B sent for a layout of $layout B")
            assertEquals(kf + 1, spy.keyframes(), "one keyframe")
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("drawing from it")
            rig.assertGlassMatchesBelief("after the repack")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFullCacheEvictsTheStaleFacesAndRepacksForTheNewWindowsFace(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(SizedText())
            val big = listOf(24, 28, 32, 36).map { FontSpec(Face.SYSTEM, it) }
            val small = FontSpec(Face.SYSTEM, 26)
            val win = SwitchRows(text, big)
            val rig = Rig(scope, text, win)
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the atlas uploads and the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the upload")
            rig.assertGlassMatchesBelief("after the upload")
            // the precondition: these faces fill the cache — at least one stayed pixels
            assertTrue(rig.atlasNotes().any { "cache is full" in it }, "the four faces must fill the cache: ${rig.atlasNotes()}")
            val liveBefore = rig.shell.cachedFontsLive
            assertTrue(liveBefore.isNotEmpty() && small !in liveBefore)

            // the window switches to a face the cache has no room for; its
            // old faces stop being drawn and go stale a window of frames later
            win.fonts = listOf(small)
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() + 6) {
                rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                rig.settle("notch $it")
            }
            rig.until("the new face is live after a repack") { small in rig.shell.cachedFontsLive }
            rig.settle("after the repack's upload")
            assertTrue(rig.atlasNotes().any { "repacked:" in it }, "a repack ran: ${rig.atlasNotes().takeLast(10)}")
            assertTrue(rig.atlasNotes().any { "repacking — " in it && "evicted" in it }, "the eviction was said: ${rig.atlasNotes().takeLast(10)}")
            // the stale faces are gone; the one the window draws is served
            assertTrue(big.none { it in rig.shell.cachedFontsLive }, "the stale faces were evicted: ${rig.shell.cachedFontsLive}")
            rig.assertGlassMatchesBelief("after the repack")
            // and it keeps serving FROM THE CACHE: a few more notches ship cached draws, still exact
            val draws = rig.cachedDraws()
            repeat(4) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP); rig.settle("back $it") }
            assertTrue(rig.cachedDraws() > draws, "the rows are drawn from the repacked cache, not as pixels")
            rig.assertGlassMatchesBelief("after scrolling back")
            // §73, the icons' half of the stale-face rule: the grow whose repack evicted the icons
            // nobody was drawing does not pack them straight back; the ones on screen come back
            // on a cue of their own once they are drawn again (the chrome repaints a frame later)
            fun packed() = rig.atlasNotes().let { n -> n.drop(n.indexOfLast { "repacked:" in it } + 1).filter { " packed, " in it } }
            assertTrue(rig.atlasNotes().any { "repacking — " in it && Regex("and [1-9]\\d* icon\\(s\\) evicted").containsMatchIn(it) }, "the precondition: the repack evicted icons: ${rig.atlasNotes().takeLast(8)}")
            assertTrue("and 0 icon(s) packed" in packed().first(), "the repack's own grow packs no evicted icon back: ${packed()}")
            // decided by frames (the four notches above), not by a wait: a wait lets any other cue do it
            assertTrue(packed().any { Regex("and [1-9]\\d* icon\\(s\\) packed").containsMatchIn(it) }, "the icons drawn again are packed again: ${packed()}")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /**
     * `HANDOFF.md` §73: a face the window is drawing needs glyphs the full cache has no room for.
     * It gets what a new face gets — the faces nobody has drawn for a window of frames are evicted
     * and the cache repacked — instead of staying half packed, every line of it fragments around
     * the host's pixels, until some other face happens to need room. Mutation watched: the evict
     * branch of the extension pass removed (the face keeps its handful of glyphs).
     */
    @Test
    fun aFaceInUseThatNeedsGlyphsEvictsTheStaleFacesLikeANewFaceDoes(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(SizedText())
            val thin = FontSpec(Face.SYSTEM, 26)
            val big = listOf(24, 28, 32).map { FontSpec(Face.SYSTEM, it) }
            val win = SwitchRows(text, big + thin).also { it.thin = setOf(thin) }
            val rig = Rig(scope, text, win)
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the thin face is live beside the big ones") { thin in rig.shell.cachedFontsLive }
            rig.settle("after the upload")
            val before = text.atlas!!.entry(thin)!!.packedCount
            assertTrue(before < 20, "packed with the handful of characters it drew: $before")

            // the window now draws ONLY that face, and every code in it: more glyphs than the cache has room for
            win.fonts = listOf(thin); win.thin = emptySet()
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() + 8) {
                rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
                rig.settle("notch $it")
            }
            // by frames, not by a wait: the retry is a window of frames after the first refusal and
            // runs on the idle pump after a settled notch, so the repack has happened inside the loop
            assertTrue(rig.atlasNotes().any { "repacked:" in it }, "a repack made the room within the notches: ${rig.atlasNotes().takeLast(8)}")
            rig.until({ "the face in use holds what it draws (${text.atlas?.entry(thin)?.packedCount} packed)" }) {
                thin in rig.shell.cachedFontsLive && (text.atlas?.entry(thin)?.packedCount ?: 0) >= 90
            }
            rig.settle("after the repack's upload")
            // and the room it made stays the face's: the evicted faces are not packed straight back
            // (they were, before the pace window on a stale face — the cache was full again at once
            // and the next new characters repacked it again)
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("one more notch")
            assertTrue(big.none { it in rig.shell.cachedFontsLive || text.atlas?.has(it) == true }, "the evicted faces stay out: ${text.atlas?.specs()}")
            val draws = rig.cachedDraws()
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("drawing from it")
            assertTrue(rig.cachedDraws() > draws, "the face in use is drawn from the cache")
            rig.assertGlassMatchesBelief("after the repack")
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP); rig.settle("back $it") }
            rig.assertGlassMatchesBelief("after scrolling back")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /**
     * `HANDOFF.md` §73, from the first review of it: a face whose extension was refused while
     * nobody was drawing it books nothing, and coming back to it draws no NEW character, so no
     * grow is cued — it stayed half packed, with evictable faces all around it, until some other
     * face happened to need room. `pumpAtlas` books the retry when such a face is in use again.
     * Mutation watched: that booking removed (the face keeps its handful of glyphs).
     */
    @Test
    fun aFaceRefusedWhileAwayIsRetriedWhenItIsDrawnAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(SizedText())
            val thin = FontSpec(Face.SYSTEM, 26)
            val other = FontSpec(Face.SYSTEM, 25)
            // two big faces leave room for both small ones and not for a whole 26 px table (~18 KB)
            val big = listOf(28, 32).map { FontSpec(Face.SYSTEM, it) }
            val win = SwitchRows(text, big + thin + other).also { it.thin = setOf(thin, other) }
            val rig = Rig(scope, text, win)
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("both small faces are live beside the big ones") { thin in rig.shell.cachedFontsLive && other in rig.shell.cachedFontsLive }
            rig.settle("after the upload")

            // the face draws every code for ONE notch: refused on the idle pump after it (nothing is
            // stale yet), and the retry is booked a window of frames after its LAST draw — so when it
            // comes the face is no longer in use (a second notch would keep it in use past the retry)
            win.fonts = listOf(thin); win.thin = setOf(other)
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("refused")
            rig.until("refused") { rig.atlasNotes().any { "cannot gain" in it } }
            // then nobody draws it while that retry comes and goes: refused again, away, nothing booked
            win.fonts = listOf(other)
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() * 2 + 6) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("away $it") }
            assertTrue((text.atlas?.entry(thin)?.packedCount ?: 0) < 20, "still the handful it was packed with")
            assertTrue(rig.atlasNotes().none { "repacked:" in it }, "nothing repacked for a face nobody draws: ${rig.atlasNotes().takeLast(6)}")
            // back in use, drawing only what it has drawn before: no new character, no cue of its own
            win.fonts = listOf(thin)
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() + 8) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("back $it") }
            rig.until({ "the face in use was retried and holds what it draws (${text.atlas?.entry(thin)?.packedCount} packed)" }) {
                thin in rig.shell.cachedFontsLive && (text.atlas?.entry(thin)?.packedCount ?: 0) >= 90
            }
            rig.settle("after the repack's upload")
            rig.assertGlassMatchesBelief("after the retry")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /**
     * `HANDOFF.md` §73: a face a repack evicted has no cue of its own when its window comes back —
     * it was seen before and draws no character it has not drawn — so its text stayed pixels until
     * some other face happened to cue a grow. `pumpAtlas` books a retry for a face in use that the
     * atlas does not hold. Here there is room for it at once: it goes back in with no second repack.
     * Mutation watched: the booking limited to refused extensions (the face never comes back).
     */
    @Test
    fun aFaceARepackEvictedGoesBackInWhenItIsDrawnAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(SizedText())
            val big = listOf(24, 28, 32, 36).map { FontSpec(Face.SYSTEM, it) }
            val small = FontSpec(Face.SYSTEM, 26)
            val win = SwitchRows(text, big)
            val rig = Rig(scope, text, win)
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the atlas uploads and the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            rig.settle("after the upload")
            val back = big.first { it in rig.shell.cachedFontsLive }
            win.fonts = listOf(small)
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() + 6) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            rig.until("the new face is live after a repack") { small in rig.shell.cachedFontsLive }
            rig.settle("after the repack's upload")
            assertTrue(text.atlas?.has(back) == false, "the face was evicted: ${text.atlas?.specs()}")
            val seen = text.seenVersion

            // its window comes back: the same rows in the same face, nothing it has not drawn before
            win.fonts = listOf(back)
            repeat(6) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("back $it") }
            assertEquals(seen, text.seenVersion, "the precondition: no face or icon new to the session cued a grow")
            // decided HERE, by frames: the booking is made on the first pump of the first frame that
            // draws the face. A wait would let any first-seen character anywhere cue the grow instead
            // — the chrome's clock shows a new digit when the minute turns, and under the mutation
            // this pin then passed one run in three on a 15 s wait
            assertTrue(text.atlas?.has(back) == true, "the face drawn again is back in the atlas within the notches: ${rig.atlasNotes().takeLast(4)}")
            rig.until("and live once its bytes are up") { back in rig.shell.cachedFontsLive }
            rig.settle("after its upload")
            assertEquals(1, rig.atlasNotes().count { "repacked:" in it }, "there was room: no second repack — ${rig.atlasNotes().takeLast(6)}")
            val draws = rig.cachedDraws()
            rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("drawing from it")
            assertTrue(rig.cachedDraws() > draws, "its rows ship as cached draws again")
            rig.assertGlassMatchesBelief("after the face came back")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /**
     * `HANDOFF.md` §73, the icons' twin of the pin above: an icon a repack evicted is not packed back
     * by that repack's grow (the stale faces' rule), so it needs a cue of its own when it is drawn
     * again — it was seen long ago, and its second use, the only cue an icon ever gave, is past.
     * Every face on screen is resident here, so nothing else asks for a grow. Mutation watched: the
     * look limited to faces (the icon stays pixels).
     */
    @Test
    fun anIconARepackEvictedGoesBackInWhenItIsDrawnAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val text = CachedText(SizedText())
            val kept = FontSpec(Face.SYSTEM, 24)
            val big = listOf(28, 32, 36).map { FontSpec(Face.SYSTEM, it) }
            val small = FontSpec(Face.SYSTEM, 26)
            val win = SwitchRows(text, listOf(kept) + big).also { it.rowIcon = true }
            val rig = Rig(scope, text, win)
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("the atlas uploads and the fonts go live") { rig.shell.cachedTextActive && rig.shell.cachedFontsLive.isNotEmpty() }
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("warm $it") }
            rig.settle("after the upload")
            fun icons() = text.atlas?.imageKeys()?.size ?: 0
            val withIcon = icons()
            assertTrue(withIcon >= 1, "the rows' icon is packed with the chrome's: $withIcon")

            // the rows stop drawing the icon and need a face the full cache has no room for: the
            // repack evicts the three faces nobody draws now, and the icon with them
            win.rowIcon = false
            win.fonts = listOf(kept, small)
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() + 8) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("notch $it") }
            rig.until("the new face is live after a repack") { small in rig.shell.cachedFontsLive && kept in rig.shell.cachedFontsLive }
            rig.settle("after the repack's upload")
            assertTrue(rig.atlasNotes().any { "repacked:" in it }, "a repack ran: ${rig.atlasNotes().takeLast(6)}")
            repeat(3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("settling $it") }
            val without = icons()
            assertTrue(without < withIcon, "the icon nobody drew was evicted and not packed back ($without of $withIcon)")
            assertTrue(text.specsInUse(Shell.ATLAS_RECENT_FRAMES).all { text.atlas?.has(it) == true },
                "the precondition: every face on screen is resident, so no face asks for a grow: ${text.specsInUse(Shell.ATLAS_RECENT_FRAMES)}")

            // the rows draw it again: decided by frames
            win.rowIcon = true
            repeat(4) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("icon back $it") }
            assertEquals(without + 1, icons(), "the icon drawn again is back in the atlas within the notches: ${rig.atlasNotes().takeLast(4)}")
            rig.assertGlassMatchesBelief("with the icon back")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /**
     * `HANDOFF.md` §73: an extension refused because the face's glyphs could not be RENDERED (its
     * ink box no longer matches the glyphs the atlas holds) is not a room refusal. No eviction
     * can cure it, so none is made — "need 0 B" passed to the eviction would have freed "enough"
     * with any stale face and repacked the cache for nothing. Said once; the face keeps what it has.
     * Mutation watched: the `need > 0` condition removed (the stale faces are evicted, a repack runs).
     */
    @Test
    fun aFaceWhoseNewGlyphsCannotBeRenderedEvictsNothing(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sized = SizedText()
            val text = CachedText(sized)
            val thin = FontSpec(Face.SYSTEM, 26)
            val big = listOf(28, 32).map { FontSpec(Face.SYSTEM, it) }
            val win = SwitchRows(text, big + thin).also { it.thin = setOf(thin) }
            val rig = Rig(scope, text, win)
            rig.shell.start(); rig.settle("start")
            rig.shell.services.runOnShell { rig.shell.services.openWindow("rows", null) }
            rig.settle("open rows")
            rig.shell.updateSettings { it.copy(cachedText = "on") }
            rig.until("all three faces are live") { thin in rig.shell.cachedFontsLive && big.all { it in rig.shell.cachedFontsLive } }
            rig.settle("after the upload")
            // only the thin face is drawn until the big ones are stale: evictable, were anything to ask
            win.fonts = listOf(thin)
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() + 3) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("stale $it") }
            val packed = text.atlas!!.entry(thin)!!.packedCount
            // its ink box moves, then it draws every code: the new glyphs cannot be rendered to match
            sized.taller = setOf(26)
            win.thin = emptySet()
            repeat(Shell.ATLAS_RECENT_FRAMES.toInt() + 4) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); rig.settle("asking $it") }
            rig.until("the refusal is said") { rig.atlasNotes().any { "could not be rendered" in it } }
            assertEquals(1, rig.atlasNotes().count { "could not be rendered" in it }, "said once: ${rig.atlasNotes().takeLast(6)}")
            assertTrue(rig.atlasNotes().none { "repacked:" in it || "repacking" in it }, "nothing was evicted for a refusal room cannot cure: ${rig.atlasNotes().takeLast(6)}")
            assertTrue(big.all { text.atlas?.has(it) == true }, "the stale faces are still resident")
            assertEquals(packed, text.atlas!!.entry(thin)!!.packedCount, "the face keeps the glyphs it had")
            rig.assertGlassMatchesBelief("with the face half packed")
            rig.shell.stop()
        } finally {
            scope.cancel()
        }
    }

    /** 2026-09-15, the second review: a repack re-lays the packed bytes out, so chunks handed out
     *  before it hold bytes for offsets that have moved. Their acks used to consume the NEW layout's
     *  chunk ends in order, running the acked watermark ahead of what the glasses hold — and every
     *  font whose table ended below it went live, drawing from bytes that were never sent. */
    @Test
    fun anAckFromTheLayoutARepackReplacedMovesNoWatermark() {
        val spec = FontSpec(Face.SYSTEM, 20)
        val a = wm.damage.core.comp.GlyphAtlas(FakeText())
        assertTrue(a.add(spec, wm.damage.core.comp.GlyphAtlas.allCodes(false)), "the face packs")
        val old = a.uploadGen
        val queued = a.takeUpload(maxMessage = 200)
        assertTrue(queued.size >= 2, "more than one chunk: ${queued.size}")
        a.repack(listOf(spec), emptyList())
        assertTrue(a.uploadGen != old, "the repack is a new layout")
        val fresh = a.takeUpload(maxMessage = 200)
        assertTrue(fresh.isNotEmpty(), "the new layout has chunks of its own")
        val before = a.ackedBytes
        repeat(queued.size) { a.acked(old) }                 // the old layout's acks arrive late
        assertEquals(before, a.ackedBytes, "a late ack of a replaced layout's chunk moves nothing")
        a.acked(a.uploadGen)
        assertTrue(a.ackedBytes > before, "the new layout's own ack moves the watermark")
    }
}
