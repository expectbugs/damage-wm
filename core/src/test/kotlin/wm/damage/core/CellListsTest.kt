package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.comp.TextDraw
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.IconKind
import wm.damage.core.motion.CellLists
import wm.damage.core.motion.Motion
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.DocModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.transport.Arm
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.TextureCache

/**
 * C3 stage DM-T, stage 1: Encode's cell lists from a screen's text runs ([CellLists]) and the GLYPHS walk bound
 * (decisions AF, `Motion.WALK_BYTES_MAX`). Context for the reader: Adam's own glasses on his own firmware fork; nothing
 * here contacts a device — the shell runs against the firmware model behind [SimTransport] with cached text on, and the
 * runs are the ones its `CachedText` records when the screen is painted once through the relay into a scratch.
 */
class CellListsTest {
    private class Doc(id: String, val ct: CachedText) : DamageWindow(id, "Page", IconKind.READER) {
        private val model = DocModel()
        /** A dense page: 24-px lines of 45 glyphs (the DM spec §3's "dense" screen). */
        override fun view(): WindowView = WindowView.DocView(model, { 200 }, 24,
            { g, i, r -> ct.draw(g, r.x, r.y, line(i), PAGE, 255) }, {}, stepLines = { 3 })
        fun line(i: Int) = ("Line $i of the page holds a sentence of text ok").take(45).padEnd(45, 'x')
        override fun summary() = Summary("a page")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig(val height: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-cells")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xdff }
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val ct = CachedText(FakeText())
        val shell = Shell(ct, t, Persistence(tmp.resolve("state.json")), tmp.resolve("journal.jsonl"), scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val doc = Doc("page", ct)
        init {
            shell.register(doc)
            t.devProbe("c1", "5")
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }
        suspend fun until(what: String, cond: () -> Boolean) = waitUntil(30_000, 5, { "$what — ${shell.quiescenceReport()}" }, cond)
        suspend fun settle(what: String) = until("$what: settled") { shell.isQuiescent() }
        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        suspend fun up() {
            shell.start(); settle("start")
            shell.updateSettings { it.copy(heightMode = height, cachedText = "on") }
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            settle("up")
        }
        /** The screen's cells once every face it draws is held by the glasses (a face drawn for the first time is packed in
         *  idle; the capture is taken again until it records no new face). */
        suspend fun cells(what: String): Pair<List<TextDraw>, CellLists.Screen> {
            var last: Pair<List<TextDraw>, CellLists.Screen>? = null
            until("$what: every face it draws held") {
                if (!shell.isQuiescent()) false else {
                    val c = onLoop { shell.screenCellsForTest() }
                    last = c
                    c.first.isNotEmpty() && c.first.all { shell.cachedFontsLive.contains(it.spec) }
                }
            }
            return last!!
        }
        fun close() { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** The lists of [s] as a half lays them out from a stage reserve of 32 KiB, both halves. */
    private fun assertLimits(what: String, s: CellLists.Screen.Cells, rig: Rig) {
        assertTrue(s.lists.size in 1..CellLists.MAX_LISTS, "$what: ${s.lists.size} lists")
        for (g in s.lists) {
            assertTrue(g.glyphs.size in 1..CellLists.MAX_CELLS, "$what: a list of ${g.glyphs.size} cells")
            assertTrue(g.face.alphabet.size <= CellLists.MAX_ALPHABET, "$what: an alphabet of ${g.face.alphabet.size}")
            // the alphabet: the face's packed codes among A–Z a–z 0–9, each a real glyph (§7.11)
            val e = assertNotNull(rig.onLoop { rig.shell.atlasEntryForTest(g.face.spec) }, "$what: the face ${g.face.spec} is held")
            assertEquals(CellLists.ALNUM.filter { e.packs(it.toChar()) }, g.face.alphabet.toList(), "$what: the alphabet of ${g.face.spec}")
            assertEquals(g.face.alphabet.maxOfOrNull { e.images[it - TextureCache.FIRST_CHAR].w } ?: 0, g.face.widestAlpha)
        }
        for (out in listOf(true, false)) {
            val laid = assertNotNull(CellLists.lay(s, 1000, 32 * 1024, 5, out) { fail("$what: $it") })
            for ((i, l) in laid.table.lists.withIndex()) {
                val g = s.lists[i]
                assertEquals(if (g.face.alphabet.size >= CellLists.NOISE_MIN_CODES) 1 else 0, l.noise, "$what: noise ⌊5/3⌋ where 4 codes or more")
                for ((c, gl) in l.cells.zip(g.glyphs)) {
                    // the box covers the glyph and the face's widest alphabet glyph; out: the code then 0; in: masked
                    assertTrue(c.bw >= gl.w && c.bw >= g.face.widestAlpha && c.bh == g.face.lineH, "$what: a box of ${c.bw}×${c.bh} for a ${gl.w}-px glyph")
                    assertEquals(if (out) gl.code to 0 else 32 to 32, c.old to c.new)
                    assertEquals(0, c.fill)
                }
            }
            assertEquals(laid.blob.size % 4, 0)
        }
    }

    /** Stage 1: the cells of Main, a dense page and Settings at the four heights fit the limits — 512 cells a list, 7
     *  lists a half, 224 codes an alphabet — and every glyph a recorded run draws is one cell at the box the atlas's draw
     *  puts it (its pixels in the run's rect). */
    @Test
    fun theScreensOfTheHarnessAreCellListsInsideTheLimitsAtEveryHeight_DMT() = runBlocking {
        for (h in listOf(288, 352, 416, 480)) {
            val rig = Rig(h)
            try {
                rig.up()
                val shown = ArrayList<String>()
                for (screen in listOf("main", "page", "settings")) {
                    if (screen != "main") { rig.shell.services.runOnShell { rig.shell.services.openWindow(screen, null) }; rig.until("$screen opens") { rig.onLoop { rig.shell.currentWindowId() } == screen } }
                    val (runs, cells) = rig.cells("$screen at $h")
                    val s = cells as? CellLists.Screen.Cells ?: fail("$screen at $h: ${(cells as CellLists.Screen.NoFit).why}")
                    assertLimits("$screen at $h", s, rig)
                    // every glyph of every run is a cell, inside its run's rect
                    val glyphs = runs.sumOf { r -> r.text.count { it != ' ' } }
                    assertEquals(glyphs, s.cells, "$screen at $h: one cell per glyph the runs draw")
                    for (g in s.lists) for (c in g.glyphs) assertTrue(runs.any { r -> r.y == c.y && c.x >= r.x && c.x <= r.rect.right }, "$screen at $h: a cell at (${c.x}, ${c.y}) on no run")
                    if (screen == "page") {
                        assertTrue(s.cells >= 5 * 44, "$screen at $h: a dense page's cells (${s.cells})")
                        assertEquals((s.cells + 511) / 512, s.lists.size, "$screen at $h: one face, one plane — ${s.cells} cells in lists of 512")
                    }
                    shown += "$screen ${s.cells} cells in ${s.lists.size} list(s) ${s.lists.map { "${it.face.spec.face}/${it.face.spec.sizePx}@${it.disparity}:${it.glyphs.size}" }}"
                }
                println("DM-T cells at $h: " + shown.joinToString("; "))
            } finally { rig.close() }
        }
    }

    /** Stage 1: a screen that does not fit is a value — more lists than a half holds is [CellLists.Screen.NoFit], lists
     *  past their room a null with the reason — never a throw. */
    @Test
    fun aScreenThatDoesNotFitIsAValueTheCallerReads_DMT() {
        val e = synthetic()
        val spec = FontSpec(Face.SYSTEM, 18)
        // eight disparities of one face: eight groups, one more than a half holds
        val runs = (0 until 8).map { i -> TextDraw(Rect(20, 20 + 24 * i, 200, 18), "ABCabc012", spec, 20, 20 + 24 * i, 255) }
        val no = CellLists.screen(runs, { e }, { { _, _ -> 0 } }, { r -> 4 * ((r.y - 20) / 24) }, Rect(0, 0, 640, 480))
        assertTrue(no is CellLists.Screen.NoFit && "8 cell lists" in no.why, "8 groups: $no")
        // 2,000 glyphs of one face: four lists (512 each but the last)
        val many = (0 until 40).map { i -> TextDraw(Rect(0, 10 * i, 640, 18), "A".repeat(50), spec, 0, 10 * i, 255) }
        val s = CellLists.screen(many, { e }, { { _, _ -> 0 } }, { 0 }, Rect(0, 0, 640, 480)) as CellLists.Screen.Cells
        assertEquals(listOf(512, 512, 512, 464), s.lists.map { it.glyphs.size })
        var why = ""
        assertEquals(null, CellLists.lay(s, 1000, 1024, 5, out = true) { why = it })
        assertTrue("past the 1024 B" in why, why)
        // a face with fewer than four alphabet codes: noise 0, its alphabet the one it has
        val few = synthetic(codes = "AB")
        val f = CellLists.screen(listOf(TextDraw(Rect(0, 0, 40, 18), "ABAB", spec, 0, 0, 255)), { few }, { { _, _ -> 0 } }, { 0 }, Rect(0, 0, 640, 480)) as CellLists.Screen.Cells
        val laid = CellLists.lay(f, 1000, 4096, 5, out = true)!!
        assertEquals(0, laid.table.lists[0].noise)
        assertEquals(listOf('A'.code, 'B'.code), laid.table.lists[0].alphabet.codes.toList())
    }

    /** Decisions AF, the walk bound: a CELLS program whose list names 64 fonts of the largest glyphs (255 × 255, a byte a
     *  pixel) is refused offline, the bytes said; a list of real cells passes with its figure. */
    @Test
    fun aGlyphWalkPastTheBoundIsRefusedOffline_AF() {
        val fx = Motion.effects(DamageMsg.Caps(2, 0xdff), DamageMsg.FLAG_DRAW2 or DamageMsg.FLAG_ENGINE or DamageMsg.FLAG_EFFECTS)
        fun program(fonts: Int, w: Int, rle: Int): Motion.ProgramBuilder.() -> Unit = {
            val cells = (0 until fonts).map { i ->
                val g = Motion.Glyph(20000 + i, w, w, rle)
                val font = Motion.Font(10000 + 112 * i, IntArray(224) { g.off4 }, listOf(g))
                Motion.Cell(10 + i, 10, w, w, font, 'A'.code, 0, fill = 0)
            }
            val alphabet = Motion.Alphabet(30000, intArrayOf('A'.code))
            val table = Motion.CellTable(30100, listOf(Motion.CellList(30200, alphabet, 1, cells)))
            track(Rect(0, 0, 640, 288), Motion.Backdrop.Level(0)) { cells(table, 0, 5) }
        }
        val e = try { Motion.program2(1024, fx, build = program(64, 255, 255 * 255)); null } catch (x: LintError) { x }
        assertNotNull(e, "64 fonts of the largest glyphs: refused")
        val want = 64L * (448 + 2 + 255 * 255)
        assertTrue("$want B" in e.message!! && "decisions AF" in e.message!!, e.message)
        // sixteen such fonts are past the 1 MB bound too: 16 × 65,475 B
        assertTrue(try { Motion.program2(1024, fx, build = program(16, 255, 255 * 255)); false } catch (x: LintError) { "decisions AF" in x.message!! })
        // a real list: one face, 62 codes of ≈ 30 B
        val ok = Motion.program2(1024, fx, build = program(1, 12, 30))
        println("AF: one face of 12×12 glyphs walks ${ok.walkBytes()} B (bound ${Motion.WALK_BYTES_MAX} B)")
        assertEquals(448L + 2 + 30, ok.walkBytes())
    }

    /** A face of the given codes, 8 × 18 glyphs, packed as the atlas packs one. */
    private fun synthetic(codes: String = "ABCabc012"): wm.damage.core.comp.GlyphAtlas.Entry {
        val glyphs = codes.associate { it to TextureCache.Image(8, 18, ByteArray(8 * 18) { 9 }) }
        val tofu = TextureCache.Image(6, 18, ByteArray(6 * 18) { 5 })
        val b = TextureCache.Builder(v2 = true, capacity = 160 * 1024)
        val font = b.addFont(glyphs, tofu)
        val n = font.glyphOffsets.size
        return wm.damage.core.comp.GlyphAtlas.Entry(font, Array(n) { glyphs[(32 + it).toChar()] ?: tofu }, 18, glyphs, tofu,
            b.content().size, BooleanArray(n) { (32 + it).toChar() in glyphs }, b.content().size)
    }

    private companion object { val PAGE = FontSpec(Face.READER, 20) }
}
