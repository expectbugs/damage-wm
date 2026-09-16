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
        private fun adv(font: FontSpec) = maxOf(4, font.sizePx / 2)
        override fun measure(text: String, font: FontSpec): Int = text.length * adv(font)
        override fun metrics(font: FontSpec) = FontMetrics(font.sizePx, 4, font.sizePx + 6)
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
     *  faces change is the eviction's whole reason. */
    private class SwitchRows(private val tx: TextRasterizer, @Volatile var fonts: List<FontSpec>) :
        DamageWindow("rows", "Rows", IconKind.FILES) {
        private val model = ListModel()
        override fun view(): WindowView = WindowView.ListView(model, { 30 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 4, "Row $i text", fonts[i % fonts.size], Level.BODY) },
            paintLens = { g, r, i -> tx.draw(g, r.x + 44, r.y + 6, "Row $i", fonts[i % fonts.size], Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("30 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(scope: CoroutineScope, val text: CachedText, val win: SwitchRows, contract: Int = 0) {
        val tmp = Files.createTempDirectory("damage-repack")
        val sim = GlassFirmwareSim().also { if (contract > 0) it.damageContract = contract }
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val journalPath = tmp.resolve("journal.jsonl")
        val shell = Shell(text, transport, Persistence(tmp.resolve("state.json")), journalPath, scope)

        init { shell.register(win) }

        fun atlasNotes(): List<String> = if (!Files.exists(journalPath)) emptyList()
            else Files.readAllLines(journalPath).filter { it.contains("\"kind\":\"atlas\"") }

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

        suspend fun until(what: String, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < 15_000) delay(10)
            assertTrue(cond(), "$what — live=${shell.cachedFontsLive} notes=${atlasNotes().takeLast(8)}")
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

    /**
     * 2026-09-16 review. A repack keeps the same `GlyphAtlas` object, puts every record at a NEW
     * offset and rewinds `ackedBytes` to the guard — so the read-back gate of §62.3 item 4 /
     * §63.1, keyed on the atlas's identity plus the acked watermark its last answer covered,
     * matched the repacked layout against a mark on a byte axis that no longer exists and let the
     * whole thing go live unproven. That is the §63.1 defect one call site along, and a repack is
     * where it costs most: every offset has moved, so a refused write draws the wrong glyphs.
     *
     * The gate is owed by an EPOCH now, and this is the observable: the repacked layout asks the
     * glasses again. Without the fix the count does not move.
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
            // and it keeps serving: a few more notches, still exact
            repeat(4) { rig.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP); rig.settle("back $it") }
            rig.assertGlassMatchesBelief("after scrolling back")
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
        assertTrue(a.add(spec), "the face packs")
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
