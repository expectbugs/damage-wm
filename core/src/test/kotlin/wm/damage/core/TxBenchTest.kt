package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.comp.CachedText
import wm.damage.core.geom.LintError
import wm.damage.core.gfx.IconKind
import wm.damage.core.motion.BoxFace
import wm.damage.core.motion.CellLists
import wm.damage.core.motion.Motion
import wm.damage.core.motion.TxBench
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.MainLocalCheck
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.StagedRecord
import wm.damage.core.shell.TxBenchRun
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.transport.effects
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.TextureCache

/**
 * C3 stage TXB: the probe `txbench` (`private/c3-design/TX-spec.md` §1.2) — its six programs as built, the run's PLAYs
 * and every ending's hand-back of the room, and the whole probe on the shell over the firmware model. Context for the
 * reader: Adam's own glasses on his own firmware fork; display-rendering work.
 */
class TxBenchTest {
    private val fx = MainLocalCheck.effectsAt(true)!!
    /** His session's room (M-glass, the OF spec's journal figures): Main's staging ends at 158,432; the top 64 KiB starts
     *  at 196,608 on 256 KiB. */
    private val at = 158_432
    private val end = 256 * 1024 - CfwModes.STAGE_RESERVE

    private val specA = FontSpec(Face.SYSTEM, 20)
    private val specB = FontSpec(Face.LIST, 24, bold = true)
    /** A glyph image per code, text-like: [w]×[h], a stem with an anti-aliased edge where the code puts it and a bar
     *  across the middle, 0 elsewhere (distinct records). */
    private fun img(code: Int, w: Int, h: Int) = TextureCache.Image(w, h, ByteArray(w * h) { p ->
        val x = p % w; val y = p / w; val s = code % (w - 2)
        when { x == s || x == s + 1 -> 15; x == s + 2 -> 7; y == h / 2 && y > 2 -> 12; else -> 0 }.toByte() })
    private val images: (FontSpec) -> List<TextureCache.Image> = { s ->
        val (w, h) = if (s == specA) 9 to 20 else 12 to 24
        List(224) { img(it + 32, w + it % 3, h) }
    }
    /** Two rows' text: 40 glyphs in A, 25 in B. */
    private val glyphs = List(40) { specA to CellLists.Glyph(0, 0, 'a'.code + it % 26, 9, 3 + it % 13) } +
        List(25) { specB to CellLists.Glyph(0, 0, 'A'.code + it % 26, 12, 15) }

    private fun bench(): TxBench.Bench {
        val (faces, cells) = TxBench.cells(glyphs, images)
        return TxBench.lay(faces, cells, TxBench.picture(faces, cells), at, end, fx)
    }

    /**
     * The six programs as §1.2 sets them: one layer of cells inside the area (C3 TXBF), each at its glyph's size; every record inside the room
     * and 4-byte aligned; each program one track over rows 352..479 with backdrop level 0 (§5.5 refuses a lone `none`),
     * lvl0 = lvl1 = 0, T 8; B1 moves the cells one area's height down (every top at or past the area's bottom), B1a one up
     * (every bottom at or above its top); B0 names the one cell, B3 the filled list, B4 DRAWs the image record at the
     * area's corner; every first read range inside the probe's own bytes (C3 DM-2b's rule D1); every PLAY slot 2 at P 128,
     * under the step budget.
     */
    @Test
    fun theSixProgramsAreLaidInTheRoomAsTheSpecSetsThem_TXB() {
        val (faces, cells) = TxBench.cells(glyphs, images)
        assertTrue(cells.size in 1..TxBench.CELLS, "${cells.size} cells")
        assertEquals(listOf(specA, specB).size, faces.size)
        for (c in cells) {
            val g = faces[c.face].glyphs[faces[c.face].codes.indexOf(c.code)]
            assertTrue(TxBench.AREA.contains(wm.damage.core.geom.Rect(c.x, c.y, g.w, g.h)), "cell at (${c.x}, ${c.y}) ${g.w}x${g.h} inside ${TxBench.AREA}")
        }
        val b = bench()
        assertTrue(b.phases.all { ph -> ph.all { (o, _) -> o % 4 == 0 && o >= at } } && b.end <= end, "records inside [$at, $end): ${b.at}..${b.end}")
        println("TXB size (test glyphs): phase A ${b.bytes(0)} B to ${b.end(0)}, phase B ${b.bytes(1)} B to ${b.end(1)}; room ${end - at} B")
        val room = at until end
        val lists = (b.programs[3].tracks[0].segments[0].cells!!).lists
        assertEquals(listOf(cells.size, cells.size, 1), lists.map { it.cells.size })
        assertTrue(lists[0].cells.all { it.fill == Motion.FILL_NONE && it.old == it.new } && lists[1].cells.all { it.fill == 0 && it.old == it.new })
        assertEquals(listOf(32, 32), lists[2].cells.single().let { listOf(it.old, it.new) })
        for ((i, p) in b.programs.withIndex()) {
            val t = p.tracks.single()
            assertEquals(TxBench.AREA, t.areaL)
            assertEquals(0, t.backdrop.code, "${TxBench.NAMES[i]}'s backdrop: level 0")
            val s = t.segments.single()
            assertEquals(listOf(0, 0, TxBench.T), listOf(s.lvl0, s.lvl1, s.steps), "${TxBench.NAMES[i]}: level 0 over T steps")
            val r = p.firstRange(IntArray(4))
            val ph = if (i < TxBench.PHASE_B) 0 else 1
            assertTrue(r.first in room && r.last < b.end(ph), "${TxBench.NAMES[i]}'s first read range $r inside its phase's records ${b.at}..${b.end(ph)}")
            assertTrue(b.phases[ph].any { (o, w) -> o == p.at * 4 && w.contentEquals(p.bytes) }, "${TxBench.NAMES[i]} is written in phase ${"AB"[ph]}")
            val play = b.play(i, 0x9000 + i)
            assertEquals(listOf(TxBench.SLOT, TxBench.PERIOD), listOf(play[2].toInt() and 0xFF, play[3].toInt() and 0xFF), "${TxBench.NAMES[i]}'s PLAY: slot and period")
        }
        val segs = b.programs.map { it.tracks[0].segments[0] }
        assertEquals(listOf(Motion.GLYPHS, Motion.GLYPHS, Motion.GLYPHS, Motion.GLYPHS, Motion.GLYPHS, Motion.DRAW), segs.map { it.verb })
        assertEquals(listOf(2, 0, 0, 0, 1), segs.take(5).map { it.aux0 }, "B0 the one cell, B1/B1a/B2 the cells, B3 the filled cells")
        assertTrue(lists[0].cells.all { it.by + segs[1].y >= TxBench.AREA.bottom }, "B1: every cell wholly below the area")
        assertTrue(lists[0].cells.all { it.by + it.bh + segs[2].y <= TxBench.AREA.y }, "B1a: every cell wholly above the area")
        assertTrue(segs[3].y == 0 && segs[4].y == 0)
        assertEquals(listOf(TxBench.AREA.x, TxBench.AREA.y), listOf(segs[5].xL, segs[5].y), "B4 at the area's corner")
        assertEquals(listOf(b.end(0) - b.at, b.end(1) - b.at), listOf(b.blob(0).size, b.blob(1).size))
    }

    /** Refused, said: records past the room's end; no glyph at all. */
    @Test
    fun aRoomTooSmallOrNoTextIsRefused_TXB() {
        val (faces, cells) = TxBench.cells(glyphs, images)
        val e = assertFailsWith<LintError> { TxBench.lay(faces, cells, TxBench.picture(faces, cells), at, at + 8_000, fx) }
        assertTrue("past the room's end" in e.message!!, e.message)
        // phase B alone too large: an image past the room with phase A inside it
        val big = TextureCache.Image2(TxBench.AREA.w, TxBench.AREA.h, ByteArray(TxBench.AREA.w * TxBench.AREA.h) { (it % 2 * 15).toByte() })
        val eB = assertFailsWith<LintError> { TxBench.lay(faces, cells, big, at, end, fx) }
        assertTrue("phase B" in eB.message!!, eB.message)
        assertFailsWith<LintError> { TxBench.cells(emptyList(), images) }
    }

    /** C3 TXBF (GI 2; reader a's finding 1): one layer of cells — they fill the area once and stop where the next glyph
     *  would go back to the top: no two boxes meet, so B2 draws exactly the ink of B4's one opaque area, and R_pic =
     *  (B2 − B0)/(B4 − B0) compares like with like; X_off and X_text divide by the run's own N. */
    @Test
    fun theCellsFillTheAreaOnceAndStop_TXBF() {
        val (faces, cells) = TxBench.cells(glyphs, images)
        val boxes = cells.map { c -> faces[c.face].glyphs[faces[c.face].codes.indexOf(c.code)].let { g -> wm.damage.core.geom.Rect(c.x, c.y, g.w, g.h) } }
        assertTrue(cells.size in 1 until TxBench.CELLS, "one layer, fewer than a list's most: ${cells.size} cells")
        for (i in boxes.indices) for (j in 0 until i) assertNull(boxes[i].intersect(boxes[j]), "cells $j and $i meet: ${boxes[j]} ${boxes[i]}")
        // the glyph after the last: no room left on its line nor on a new line below
        val (s, g) = glyphs[cells.size % glyphs.size]
        val im = images(s)[g.code - TextureCache.FIRST_CHAR]
        val last = boxes.last()
        val pitch = glyphs.maxOf { (sp, gl) -> images(sp)[gl.code - TextureCache.FIRST_CHAR].h }
        val ny = if (last.x + last.w + im.w > TxBench.AREA.right) last.y + pitch else last.y
        assertTrue(ny + im.h > TxBench.AREA.bottom, "the area is full after ${cells.size} cells (the next glyph at y $ny, ${im.h} high)")
        println("TXBF: ${cells.size} cells in one layer on the test glyphs")
    }

    /** C3 TXBF (GI 4; reader b's finding 1): the start note says which faces the cells came from, of how many the list
     *  draws, and how many characters were skipped because their face has no atlas entry, by name — Global painted on
     *  the test face with every face it draws in the atlas, then with one left out. No refusal. */
    @Test
    fun theStartNoteSaysWhichFacesTheCellsCameFrom_TXBF() {
        val seen = LinkedHashSet<FontSpec>()
        val spy = object : wm.damage.core.text.TextRasterizer by BoxFace {
            override fun draw(surface: wm.damage.core.gfx.Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) { seen += font; BoxFace.draw(surface, x, y, text, font, level) }
        }
        wm.damage.core.shell.WindowListCheck.WindowRig(spy, 352, wm.damage.core.shell.WindowListCheck.Sample.GLOBAL).let { r -> for (i in 0 until r.rows) {
            wm.damage.core.shell.ListStaging.paintStripRow(r.view, r.layout, i) { _, b -> b() }; wm.damage.core.shell.ListStaging.paintBandForm(r.view, r.kit, r.layout, i) { _, b -> b() } } }
        assertTrue(seen.size >= 2, "Global draws several faces: $seen")
        for (out in listOf(null, seen.first())) {
            val ct = CachedText(BoxFace).also { c ->
                c.atlas = wm.damage.core.comp.GlyphAtlas(BoxFace, v2 = true, capacity = CfwModes.CACHE2_MAX).also { a -> for (f in seen) if (f != out) a.add(f, wm.damage.core.comp.GlyphAtlas.allCodes(true)) }
                c.live = seen - setOfNotNull(out)
            }
            val r = wm.damage.core.shell.WindowListCheck.WindowRig(ct, 352, wm.damage.core.shell.WindowListCheck.Sample.GLOBAL)
            val said = Said()
            TxBenchRun { k, d -> said.notes += k to d }.start("global", TxBenchRun.bench(r.view, r.kit, r.layout, ct, at, end, fx), listOf(Any(), null))
            val note = said.notes.single().second
            println("TXBF faces (${if (out == null) "all in the atlas" else "one left out"}): $note")
            val (used, of) = assertNotNull(Regex("from (\\d+) of the (\\d+) face").find(note), note).destructured
            assertEquals(seen.size, of.toInt(), "of how many the list draws: $note")
            if (out == null) assertTrue(used.toInt() in 1..seen.size && "skipped" !in note, note)
            else assertTrue(used.toInt() in 1 until seen.size && Regex("\\d+ character\\(s\\) skipped").containsMatchIn(note) &&
                "${out.sizePx}" in note.substringAfter("no live atlas entry for "), "the face left out named: $note")
        }
    }

    private class Said { val notes = ArrayList<Pair<String, String>>(); fun ended() = notes.filter { it.first == "txbench" && it.second.startsWith("ended") } }

    private fun notify(tag: Int, outcome: String = "drew its last step", slot: Int = TxBench.SLOT, us: Int = 12_000) =
        "seq 9 slot $slot tag 0x%04x $outcome ordinal 1 drawn 8 folded 0 stepUs $us".format(tag)

    private fun proven(r: TxBenchRun) { for (x in r.records) x.state = StagedRecord.State.PROVEN }

    /**
     * The run as §1.2 plays it: nothing before every record is PROVEN; then one PLAY at a time — the next only once RIGHT
     * reported the last step of the one before — B0 ×5, B1 ×5 … B3 ×5, then phase B's records (WANTED, from the same
     * offset; no PLAY until they are counted), then B4 ×5, each named in a `test program played` note when its
     * flush is acknowledged; a report of another tag or another slot moves nothing; after the thirtieth report it ends
     * "done" with every step time by program, and its records leave the books.
     */
    @Test
    fun theRunPlaysThirtyOneAtATimeAndHandsTheRoomBack_TXB() {
        val said = Said()
        val r = TxBenchRun { k, d -> said.notes += k to d }
        val room = listOf<Any?>(Any(), null)
        r.start("global", bench(), room)
        assertTrue(r.records.isNotEmpty() && r.records.all { it.state == StagedRecord.State.WANTED && it.encoded.size <= TxBenchRun.RECORD_MAX })
        assertNull(r.pump(null, room, null) { null }, "no PLAY before the records are counted")
        proven(r)
        val names = ArrayList<String>()
        val phaseA = r.records.map { it.off }
        for (n in 0 until TxBench.NAMES.size * TxBench.PLAYS) {
            if (n == TxBench.PHASE_B * TxBench.PLAYS) {
                assertNull(r.pump(null, room, null) { null }, "phase B: its records first")
                assertTrue(r.records.isNotEmpty() && r.records.all { it.state == StagedRecord.State.WANTED } && r.records[0].off == phaseA[0],
                    "phase B's records, WANTED, over phase A's from the same offset")
                assertTrue(said.notes.last().second.startsWith("phase B"))
                assertNull(r.pump(null, room, null) { null }, "no PLAY before they are counted")
                proven(r)
            }
            val play = assertNotNull(r.pump(null, room, null) { null }, "PLAY $n")
            val tag = (play[6].toInt() and 0xFF) or ((play[7].toInt() and 0xFF) shl 8)
            assertNull(r.pump(null, room, tag) { null }, "one PLAY at a time")
            r.sent(100L + n); r.flushDone(100L + n, true, null)
            names += said.notes.last().second.substringAfter("txbench ").substringBefore(' ')
            assertTrue(!r.notify(notify(tag + 1)) && !r.notify(notify(tag, slot = 0)), "another tag or slot moves nothing")
            assertTrue(r.notify(notify(tag, us = 1000 + n)))
        }
        assertEquals(TxBench.NAMES.flatMap { n -> List(TxBench.PLAYS) { n } }, names)
        assertNull(r.pump(null, room, null) { null })
        assertTrue(!r.running && r.records.isEmpty())
        val e = said.ended().single().second
        assertTrue(e.startsWith("ended — done; 30 of 30") && "B4 1025, 1026, 1027, 1028, 1029" in e, e)
    }

    /** Every ending is said once and hands the room back (its records leave the books, no PLAY follows): a reason from the
     *  shell, the room changed, slot 2 holding another tag, ink in the area, a PLAY not submitted, its flush failed, RIGHT
     *  closing or refusing it, and a direct end (input, keyframe, session, a second probe). */
    @Test
    fun everyEndingHandsTheRoomBack_TXB() {
        val endings: List<Pair<String, (TxBenchRun, List<Any?>) -> Unit>> = listOf(
            "why" to { r, room -> r.pump("a notice is up", room, null) { null } },
            "room" to { r, _ -> r.pump(null, listOf(Any(), null), null) { null } },
            "level" to { r, room -> r.pump(null, room, null) { "ink" } },
            "tagIn" to { r, room -> r.pump(null, room, null) { null }; r.sent(5)
                assertNull(r.pump(null, room, 0x1234) { null }); assertTrue(r.running, "another tag before the PLAY's ack: the mirror may lag")
                r.flushDone(5, true, null); r.pump(null, room, 0x1234) { null } },
            "notSent" to { r, room -> r.pump(null, room, null) { null }; r.notSent("the link ended") },
            "flush" to { r, room -> r.pump(null, room, null) { null }; r.sent(7); r.flushDone(7, false, "lost") },
            "closed" to { r, room -> repeat(TxBenchRun.CLOSED_MAX + 1) { r.pump(null, room, null) { null }; r.notify(notify(TxBenchRun.TAG + it, "closed while drawing")) } },
            "refused" to { r, room -> r.pump(null, room, null) { null }; r.notify("seq 9 slot 2 tag 0x%04x refused (12)".format(TxBenchRun.TAG)) },
            "end" to { r, _ -> r.end("an input event") })
        for ((what, act) in endings) {
            val said = Said()
            val r = TxBenchRun { k, d -> said.notes += k to d }
            val room = listOf<Any?>(Any(), null)
            r.start("main", bench(), room)
            proven(r)
            act(r, room)
            assertTrue(!r.running && r.records.isEmpty() && r.wanted() == null && r.closeOwed, "$what: the room handed back, slot 2's CLOSE owed")
            assertEquals(1, said.ended().size, "$what: said once — ${said.notes}")
            assertNull(r.pump(null, room, null) { null }, "$what: no PLAY after the end")
            r.end("again"); assertEquals(1, said.ended().size, "$what: an end with no run says nothing")
        }
    }

    /** A play closed by a frame meeting its area (§5.6's CLOSE first: any cached text run counts to the panel's bottom-right,
     *  so any cached text Main flushes does — the clock, the status cell, a row) is played again — the same program, a new tag, said — and counts nothing; a fourth
     *  close of one play running ends the run (above); a play drawn starts the count again. */
    @Test
    fun aPlayClosedByAFrameIsPlayedAgain_TXB() {
        val said = Said()
        val r = TxBenchRun { k, d -> said.notes += k to d }
        val room = listOf<Any?>(Any(), null)
        r.start("main", bench(), room)
        proven(r)
        val p1 = assertNotNull(r.pump(null, room, null) { null })
        assertTrue(r.notify(notify(TxBenchRun.TAG, "closed while drawing")))
        assertTrue(r.running && "closed by a frame meeting its area" in said.notes.last().second && "played again (1 of 3 for this play" in said.notes.last().second, "${said.notes.last()}")
        val p2 = assertNotNull(r.pump(null, room, null) { null }, "played again")
        assertEquals(u16(p1, 4), u16(p2, 4), "the same program")
        assertEquals(TxBenchRun.TAG + 1, u16(p2, 6), "a new tag")
        r.sent(1); r.flushDone(1, true, null)
        assertTrue("txbench B0 (play 1 of 5" in said.notes.last().second, "still B0's first play: ${said.notes.last()}")
        // two more closes, then drawn: the count starts again — three closes of the next play still play it again
        for (k in 1..2) { assertTrue(r.notify(notify(TxBenchRun.TAG + k, "closed while drawing"))); assertNotNull(r.pump(null, room, null) { null }) }
        assertTrue(r.notify(notify(TxBenchRun.TAG + 3)))
        for (k in 4..6) { assertNotNull(r.pump(null, room, null) { null }); assertTrue(r.notify(notify(TxBenchRun.TAG + k, "closed while drawing"))) }
        assertTrue(r.running, "three closes of the second play: still running")
        assertNotNull(r.pump(null, room, null) { null }); r.notify(notify(TxBenchRun.TAG + 7, "closed while drawing"))
        assertTrue(!r.running && "4 times running" in said.ended().single().second && "6 play(s) closed" in said.ended().single().second, "${said.ended()}")
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /**
     * `v5-txb-bench` (`make_vectors.py`'s `vectors_v5_txb`, inputs for the fork's fill) holds the product's bytes: the
     * product's [TxBench.cells] given the vector's glyph sequence sets the vector's cells (one layer), and [TxBench.lay] given its
     * faces and cells writes, from the key's offset, exactly the bytes step 0's cache writes leave there — glyph copies,
     * tables, alphabet, lists, image and the six programs at the key's off4s. A difference is a finding, never an edit.
     */
    @Test
    fun vTxbBenchHoldsTheProductsBytes_TXB() {
        var d: java.nio.file.Path? = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null && !Files.isDirectory(d.resolve("firmware/vectors"))) d = d.parent
        val vec = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(d!!.resolve("firmware/vectors/v5-txb-bench.json"))).jsonObject
        // the cache after step 0 (phase A) and after the later step that writes again (phase B)
        val caches = ArrayList<ByteArray>()
        val cache = ByteArray(256 * 1024)
        for (st in vec["steps"]!!.jsonArray) {
            var wrote = false
            for (op in st.jsonObject["ops"]!!.jsonArray) {
                val h = op.jsonObject["msg"]?.jsonPrimitive?.content ?: continue
                val m = ByteArray(h.length / 2) { h.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
                if (m[0].toInt() == 19) { for ((o, b) in ShipVectors.cacheWrites(m)) b.copyInto(cache, o); wrote = true }
            }
            if (wrote) caches += cache.copyOf()
        }
        assertEquals(2, caches.size, "two steps write records: phase A, then phase B")
        val k = vec["txb"]!!.jsonObject
        fun ki(key: String) = k[key]!!.jsonPrimitive.int
        assertEquals(listOf(TxBench.T, TxBench.PERIOD, TxBench.SLOT), listOf(ki("t"), ki("period"), ki("slot")))
        assertEquals(listOf(TxBench.AREA.x, TxBench.AREA.y, TxBench.AREA.w, TxBench.AREA.h), k["area"]!!.jsonArray.map { it.jsonPrimitive.int })
        val specs = listOf(FontSpec(Face.SYSTEM, 20), FontSpec(Face.LIST, 26))
        val byFace = k["faces"]!!.jsonArray.map { f -> f.jsonArray.map { g -> g.jsonArray.let { a ->
            val lv = a[3].jsonPrimitive.content
            a[0].jsonPrimitive.int to TextureCache.Image(a[1].jsonPrimitive.int, a[2].jsonPrimitive.int, ByteArray(lv.length / 2) { lv.substring(2 * it, 2 * it + 2).toInt(16).toByte() }) } } }
        val cells = k["cells"]!!.jsonArray.map { c -> c.jsonArray.map { it.jsonPrimitive.int }.let { TxBench.Cell(it[0], it[1], it[2], it[3], it[4]) } }
        // the vector's paint order: each face's codes in turn, level 4 + i
        val seq = byFace.withIndex().flatMap { (f, gs) -> gs.map { (code, _) -> specs[f] to CellLists.Glyph(0, 0, code, 0, 0) } }
            .mapIndexed { i, (s, g) -> s to CellLists.Glyph(0, 0, g.code, 0, 4 + i) }
        val (faces, laid) = TxBench.cells(seq) { s -> val gs = byFace[specs.indexOf(s)].toMap(); List(224) { gs[it + 32] ?: TextureCache.Image(1, 1, ByteArray(1)) } }
        assertEquals(cells.map { listOf(it.x, it.y, it.face, it.code, it.level) }, laid.map { listOf(it.x, it.y, it.face, it.code, it.level) }, "TxBench.cells sets the vector's cells")
        val at = ki("at"); val ends = listOf(ki("endA"), ki("endB"))
        val b = TxBench.lay(faces, cells, TxBench.picture(faces, cells), at, ends.max(), fx)
        assertEquals(k["programs"]!!.jsonArray.map { it.jsonPrimitive.int }, b.programs.map { it.at }, "the six programs' off4s")
        for (ph in 0..1) {
            assertEquals(ends[ph], b.end(ph), "phase ${"AB"[ph]}'s end")
            val blob = b.blob(ph); val c = caches[ph]
            assertTrue(blob.contentEquals(c.copyOfRange(at, ends[ph])), "v5-txb-bench's phase ${"AB"[ph]} records are TxBench.lay's byte for byte (first difference at " +
                "${(at until ends[ph]).firstOrNull { blob[it - at] != c[it] }})")
        }
    }

    // ---------------------------------------------------------------------------------------- the probe on the shell
    private class Row(id: String, name: String) : DamageWindow(id, name, IconKind.FILES) {
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 }, { _, _, _, _ -> }, { _, _, _ -> }, {})
        override fun summary() = DamageWindow.Summary("summary of $id")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /**
     * The whole probe on the shell at stage 5 over the firmware model (256 KiB, height 352, Main in front): its records
     * written and counted, thirty PLAYs each named, each reported by RIGHT (none refused by the model's walk), "done", and
     * the room handed back with slot 2 (one CLOSE of its last instance, so the mirror vouches for the area again and a
     * second run starts at once); a second probe while it runs ends it, said; refused, said, for a list that is not one and, at
     * height 480, for Main's own rows in the area (§1.2: the mirror must hold level 0 there).
     */
    @Test
    fun theProbeRunsOnTheShellOverTheModel_TXB(): Unit = runBlocking {
        val rig = ShellRig()
        try {
            val (t, journal, shell) = Triple(rig.t, rig.journal, rig.shell)
            fun <T> onLoop(block: () -> T): T = rig.onLoop(block)
            fun notes(kind: String) = rig.notes(kind)
            suspend fun until(what: String, cond: () -> Boolean) = rig.until(what, cond)
            rig.up()
            assertTrue(t.state.value.effects && t.state.value.cacheSize == 256 * 1024, "stage 5 in force, 256 KiB: ${t.state.value.cacheSize}")
            shell.devProbe("txbench", "main")
            until("the probe ends") { notes("txbench").any { it.startsWith("ended") } || notes("txbench").any { "not started" in it } }
            val e = notes("txbench").first { it.startsWith("ended") || "not started" in it }
            if (!e.startsWith("ended — done; 30 of 30")) {
                // the journal around the end: every line but frames and acks, the last 80 before the ending
                val lines = Files.readAllLines(journal)
                val k = lines.indexOfFirst { "\"kind\":\"txbench\"" in it && "ended" in it }
                val near = lines.subList(maxOf(0, k - 400), k + 1).filter { "\"ev\":\"note\"" in it || "close" in it.lowercase() }.takeLast(80)
                println("TXB shell run — the journal before the ending:\n" + near.joinToString("\n") { it.take(400) })
            }
            assertTrue(e.startsWith("ended — done; 30 of 30"), "${notes("txbench")}")
            val closes = notes("txbench").count { "closed by a frame meeting its area" in it }
            println("TXB shell run: 30 plays reported, $closes closed by a frame and played again (the model's frames, sim)")
            assertEquals(30 + closes, notes("motion").count { it.startsWith("test program played: txbench") })
            until("slot 2 handed back") { notes("txbench").any { it.startsWith("slot 2 handed back: its last instance (tag 0x9") } }
            // refused, said: a list that is not one — asked here, with slot 2 handed back (C3 TXBF: asked after the second run's
            // end it could meet that run's first PLAY still on its way, its instance open over the area: refused for that first)
            shell.devProbe("txbench", "nosuch")
            until("an unknown list refused") { notes("txbench").any { it.startsWith("txbench=nosuch not started — no list 'nosuch'") && "Global" in it } }
            shell.devProbe("txbench", "main")
            until("a second run started") { notes("txbench").count { it.startsWith("started") } == 2 }
            shell.devProbe("txbench", "main")
            until("the second probe ends it") { notes("txbench").any { it.startsWith("ended — a second probe txbench") } }
            // refused, said: at height 480, Main's rows in the area (ink below row 352)
            shell.updateSettings { it.copy(heightMode = 480) }
            until("Main at 480") { shell.isQuiescent() && onLoop { shell.mainBinding.counted } }
            shell.devProbe("txbench", "main")
            until("ink in the area refused") { notes("txbench").count { it.startsWith("txbench=main not started") } == 1 }
            val no = notes("txbench").single { it.startsWith("txbench=main not started") }
            assertTrue("holds ink in row" in no, "at 480 Main's rows are in the area: $no")
            assertEquals(2, notes("txbench").count { it.startsWith("started") }, "nothing started at 480")
        } finally { rig.close() }
    }

    /** The shell at stage 5 over the firmware model, 256 KiB, height 352, Main in front with seven windows and [extra]. */
    private class ShellRig(extra: List<DamageWindow> = emptyList()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp: java.nio.file.Path = Files.createTempDirectory("damage-txb")
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xdff }
        // a stored ladder record at stage 5 with a 256 KiB last session (MainLocalShellTest's kib256 rig): the first start
        // asks 256 KiB, so Main stages into its own region
        val t = StartRig(sim, scope, c1StageFile = tmp.resolve("c1-stage").also { Files.writeString(it, wm.damage.core.transport.LadderRecord(stage = 5, sessionKib = 256).encode()) })
        val journal: java.nio.file.Path = tmp.resolve("journal.jsonl")
        val shell = Shell(CachedText(FakeText()), t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        init {
            for (i in 0 until 7) shell.register(Row("w$i", "Win $i"))
            for (w in extra) shell.register(w)
            scope.launch { while (true) { val now = System.currentTimeMillis(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }
        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }
        suspend fun until(what: String, cond: () -> Boolean) = waitUntil(60_000, 5, { "$what — txbench ${notes("txbench")}; ${shell.quiescenceReport()}" }, cond)
        suspend fun counted(what: String) = until(what) { shell.isQuiescent() && onLoop { shell.mainBinding.counted } }
        suspend fun up() {
            shell.start(); until("start") { shell.isQuiescent() }
            shell.updateSettings { it.copy(heightMode = 352, cachedText = "on") }
            counted("Main staged and counted")
        }
        /** `probe txbench=main`, then its first `started` or `not started` note after the [n] before it. */
        suspend fun probe(n: Int): String {
            shell.devProbe("txbench", "main")
            until("the probe starts or is refused") { notes("txbench").count { it.startsWith("started") || "not started" in it } > n }
            return notes("txbench").filter { it.startsWith("started") || "not started" in it }[n]
        }
        fun close() { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** C3 TXBF: a window whose own view is a list of [n] rows, the first [noisy] drawn as noise in runs of two pixels
     *  across the row (a strip row about a byte per two pixels: a large kept list) and the next one over its first
     *  [partial] pixels, the rest a few marks. */
    private class ListWin(id: String, name: String, val n: Int, val noisy: Int = 0, val partial: Int = 0) : DamageWindow(id, name, IconKind.FILES) {
        val model = ListModel()
        override fun view(): WindowView = WindowView.ListView(model, { n },
            { g, i, r, _ ->
                if (i < noisy || (i == noisy && partial > 0)) for (y in r.y until r.y + r.h) for (x in r.x until r.x + if (i < noisy) r.w else minOf(r.w, partial))
                    g[x, y] = (((i * 73856093) xor ((x shr 1) * 19349663) xor (y * 83492791)) ushr 5 and 15) * 17
                else for (k in 0..(i % 5)) g.fillRect(r.x + 12 + k * 22 + (i * 7) % 40, r.y + 6, 14, 12, 120 + 20 * (k % 4)) },
            { g, r, i -> g.fillRect(r.x + 4, r.y + 4, 8 + (i * 13) % 200, r.h - 8, 255) }, {})
        override fun summary() = DamageWindow.Summary("summary of $id")
        override fun saveState(): JsonObject = JsonObject(emptyMap())
        override fun restoreState(state: JsonObject) {}
    }

    /**
     * C3 TXBF (GI 1; reader a's finding 2): the probe's room with a window's list kept behind Main — the daily state from
     * the first window he opens with a list (a kept list is laid from the wheel's base down). A window with a list opened,
     * then Back: the probe starts, every record it writes below the kept list's start, the start note naming the room's top.
     * Then Scramble on Main (its records laid last in Main's staging) and a larger list kept, its start less than phase A's
     * bytes above Main's staging's end (the first of growing lists that is: the rig's sizes, not his): refused, the words
     * naming the kept list with its offsets, Main's Scroll effect, and saying to choose another.
     */
    @Test
    fun theProbeFindsRoomWithAWindowsListKept_TXBF(): Unit = runBlocking {
        // the first list small; then one noise row and part of the next, growing by about 5 KB (the rig's sizes)
        val wins = listOf(ListWin("lw0", "Listy 0", n = 12)) + listOf(120, 240, 360, 480).mapIndexed { k, p -> ListWin("lw${k + 1}", "Listy ${k + 1}", n = 12, noisy = 1, partial = p) }
        val r = ShellRig(wins)
        try {
            r.up()
            suspend fun keep(w: ListWin): IntRange {
                r.onLoop { r.shell.services.openWindow(w.id, null) }
                r.until("${w.id}'s list staged") { r.shell.isQuiescent() && r.onLoop { r.shell.currentWindowId() == w.id && r.shell.windowListBinding.staging != null } }
                r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
                r.until("back on Main") { r.onLoop { r.shell.currentWindowId() } == null }
                r.counted("Main again")
                return assertNotNull(r.onLoop { r.shell.windowListBinding.staging?.let { it.start until it.end } }, "${w.id}'s list is kept behind Main")
            }
            val kept = keep(wins[0])
            val s = r.probe(0)
            println("TXBF kept list at $kept, Main's staging ends at ${r.onLoop { r.shell.mainBinding.staging?.end }}: $s")
            assertTrue(s.startsWith("started"), "with a window's list kept at $kept the probe starts: $s")
            val (bytesA, endA, endB) = assertNotNull(Regex("phase A (\\d+) B to (\\d+) .*phase B \\d+ B to (\\d+) ").find(s), s).destructured
            assertTrue(maxOf(endA.toInt(), endB.toInt()) <= kept.first, "every record below the kept list's start ${kept.first}: $s")
            val cap = 256 * 1024 - CfwModes.STAGE_RESERVE
            assertTrue((if (kept.first < cap) "the kept list of" else "the cache's top 64 KiB at $cap") in s.substringAfter("(from Main's staging's end to "), "the room's top named: $s")
            r.shell.devProbe("txbench", "main")
            r.until("the second probe ends it") { r.notes("txbench").any { it.startsWith("ended — a second probe") } }
            r.shell.updateSettings { it.copy(scrollEffect = "scramble") }
            r.until("Main staged with Scramble's records") { r.onLoop { r.shell.mainBinding.staging?.recordEffect } != null }
            r.counted("Main with Scramble")
            val es = r.onLoop { r.shell.mainBinding.staging!!.end }
            var tight: IntRange? = null
            for (w in wins.drop(1)) { val k = keep(w); println("TXBF ${w.id}: kept at $k (Main with Scramble ends at $es)"); if (k.first < es + bytesA.toInt()) { tight = k; break } }
            val k = assertNotNull(tight, "a list whose start leaves less than phase A's $bytesA B above Main's staging's end $es")
            val no = r.probe(1)
            println("TXBF Scramble and the list at $k: $no")
            assertTrue("not started" in no && "Scroll effect Scramble" in no && "choose another Scroll effect for Main and send the probe again" in no &&
                "the kept list of" in no && "at ${k.first}..${k.last + 1}" in no, no)
        } finally { r.close() }
    }

    /**
     * C3 TXBF (GI 3; reader a's finding 3): his input ends a run and opens a window through a transition (Depth, stage 5,
     * 256 KiB): the out half's PLAY closes the probe's instance in slot 2, so no CLOSE of slot 2 follows it on RIGHT's wire
     * but the transition's own after its in half, the out half draws its last step, and no hand-back is said.
     */
    @Test
    fun anInputThatOpensAWindowThroughATransitionSendsNoCloseUnderItsOutHalf_TXBF(): Unit = runBlocking {
        val r = TransitionShellTest.Rig(stage = 5, kib256 = true)
        try {
            r.up { s -> wm.damage.core.motion.Transitions.Tier.entries.fold(s) { a, t -> a.withTier(t, effects = listOf("depth")) } }
            r.shell.devProbe("txbench", "main")
            r.until("a probe PLAY acknowledged") { r.notes("motion").any { it.startsWith("test program played: txbench") } || r.notes("txbench").any { "not started" in it || it.startsWith("ended") } }
            assertTrue(r.notes("txbench").none { "not started" in it || it.startsWith("ended") }, "${r.notes("txbench")}")
            r.recording = true
            r.tap()
            r.until("w0 opens") { r.onLoop { r.shell.currentWindowId() } == "w0" }
            r.until("the transition ends") { r.onLoop { r.shell.transitionPhaseForTest() } == null }
            r.settle("Select")
            r.recording = false
            assertTrue(r.notes("txbench").any { it.startsWith("ended — an input event") }, "${r.notes("txbench")}")
            val out = assertNotNull(Regex("tags out 0x([0-9a-f]{4})").find(r.notes("transition").last { it.startsWith("owed: ") }), "${r.notes("transition")}").groupValues[1].toInt(16)
            fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            fun subs(img: ByteArray): List<ByteArray> = if (img[0].toInt() != 8) listOf(img) else ArrayList<ByteArray>().also { o ->
                var i = 2; repeat(img[1].toInt() and 0xFF) { val n = u16(img, i); o += img.copyOfRange(i + 2, i + 2 + n); i += 2 + n } }
            fun isSub(s: ByteArray, sub: Int) = s.size >= 3 && s[0].toInt() == 26 && s[1].toInt() == sub && (s[2].toInt() and 0xFF) == TxBench.SLOT
            val imgs = r.images.filter { it.startsWith("R ") }.map { h -> ByteArray((h.length - 2) / 2) { h.substring(2 + 2 * it, 4 + 2 * it).toInt(16).toByte() } }
            val play = imgs.indexOfFirst { img -> subs(img).any { isSub(it, CfwModes.MOTION_PLAY) && it.size >= 8 && u16(it, 6) == out } }
            val closes = imgs.drop(play + 1).count { img -> subs(img).any { isSub(it, CfwModes.MOTION_CLOSE) } }
            val ns = r.notes("motion").filter { "slot 2 tag 0x%04x ".format(out) in it }
            println("TXBF transition: out tag 0x%04x, its PLAY image $play, CLOSEs of slot 2 after it $closes; $ns; txbench ${r.notes("txbench").takeLast(2)}".format(out))
            assertTrue(play >= 0 && closes <= 1, "after the out half's PLAY, at most the transition's own CLOSE of slot 2: $closes")
            assertTrue(ns.none { "closed while drawing" in it } && ns.any { "drew its last step" in it }, "the out half drew its last step: $ns")
            assertTrue(r.notes("txbench").none { it.startsWith("slot 2 handed back") }, "the out half's PLAY closed the probe's instance: ${r.notes("txbench")}")
        } finally { r.close() }
    }
}
