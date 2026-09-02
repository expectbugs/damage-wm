package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.geom.VPos
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.KeyboardSurface
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellServices
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.wire.EvenHubMsg

/**
 * The §4.8 keyboard (Adam's design, 2026-09-01): the layout invariants
 * (every row sums to 12 units, every printable ASCII character reachable,
 * first keys harmless, Enter/Clear never at a rest position), the two-stage
 * grammar (row → key, wrap on both axes, stay-in-row after typing, back to
 * row, cancel keeps the draft), the modifiers, the caret editing, and the
 * shell wiring (open on request only, commit closes-then-runs, the wheel
 * displaces it with the draft kept, a replica line commits through it).
 */
class KeyboardTest {

    private fun keys(layout: String, symbols: Boolean) =
        KeyboardSurface.buildRows(layout, symbols, emptyList())

    @Test
    fun everyRowSumsToTwelveUnitsInEveryLayerAndLayout() {
        for (layout in KeyboardSurface.LAYOUTS) for (sym in listOf(false, true)) {
            for ((i, r) in keys(layout, sym).withIndex()) {
                assertEquals(KeyboardSurface.UNITS, r.sumOf { it.span }, "$layout sym=$sym row $i")
            }
        }
        // a requester row shares the units too (5 keys: 2+2+2+2+4)
        val extra = (1..5).map { KeyboardSurface.ExtraKey("k$it", "id$it") }
        val rows = KeyboardSurface.buildRows("qwerty", false, extra)
        assertEquals(6, rows.size)
        assertEquals(KeyboardSurface.UNITS, rows.last().sumOf { it.span })
    }

    @Test
    fun everyPrintableAsciiIsReachable() {
        for (layout in KeyboardSurface.LAYOUTS) {
            val reachable = HashSet<Char>()
            for (sym in listOf(false, true)) for (r in keys(layout, sym)) for (k in r) {
                when (k.kind) {
                    KeyboardSurface.Kind.CHAR -> { reachable.add(k.ch[0]); reachable.add(k.ch.uppercase()[0]) }
                    KeyboardSurface.Kind.SPACE -> reachable.add(' ')
                    else -> {}
                }
            }
            val missing = (0x20..0x7E).map { it.toChar() }.filter { it !in reachable }
            assertTrue(missing.isEmpty(), "$layout cannot type: $missing")
        }
    }

    @Test
    fun restPositionsAreHarmlessAndEnterClearSitAtRowEnds() {
        for (layout in KeyboardSurface.LAYOUTS) for (sym in listOf(false, true)) {
            for ((i, r) in keys(layout, sym).withIndex()) {
                val first = r.first().kind
                assertTrue(first in setOf(KeyboardSurface.Kind.CHAR, KeyboardSurface.Kind.SHIFT, KeyboardSurface.Kind.SYMBOLS),
                    "$layout sym=$sym row $i rests on ${first}")
                for ((j, k) in r.withIndex()) {
                    if (k.kind == KeyboardSurface.Kind.ENTER || k.kind == KeyboardSurface.Kind.CLEAR) {
                        assertEquals(r.size - 1, j, "$layout sym=$sym row $i: ${k.kind} must be last")
                    }
                }
            }
        }
    }

    private fun opened(initial: String = "", layout: String = "qwerty",
        extra: List<KeyboardSurface.ExtraKey> = emptyList()): Pair<KeyboardSurface, MutableList<String>> {
        val log = ArrayList<String>()
        val kb = KeyboardSurface(FakeText())
        kb.openWith(KeyboardSurface.Spec("search", initial, extra,
            onCommit = { log.add("commit:$it") }, onCancel = { log.add("cancel:$it") },
            onExtra = { log.add("extra:$it") }), layout)
        return kb to log
    }

    /** Walk the highlight to [ch] in the current layer and tap it. */
    private fun KeyboardSurface.type(ch: String) {
        val rs = rows()
        val ri = rs.indexOfFirst { r -> r.any { it.kind == KeyboardSurface.Kind.CHAR && it.ch == ch } }
        assertTrue(ri >= 0, "'$ch' is not in the current layer")
        goRow(ri)
        goKey(rs[ri].indexOfFirst { it.kind == KeyboardSurface.Kind.CHAR && it.ch == ch })
        tap()
    }

    private fun KeyboardSurface.goRow(ri: Int) {
        if (stage == KeyboardSurface.Stage.KEY) back()
        while (row != ri) scroll(1)
    }

    /** Enter the row (if needed) and scroll to key [ki] — through the wrap
     *  when that is the short way, like a user would. */
    private fun KeyboardSurface.goKey(ki: Int) {
        if (stage == KeyboardSurface.Stage.ROW) tap()
        val n = rows()[row].size
        val fwd = (ki - key).mod(n)
        val bwd = (key - ki).mod(n)
        if (fwd <= bwd) repeat(fwd) { scroll(1) } else repeat(bwd) { scroll(-1) }
        assertEquals(ki, key)
    }

    private fun KeyboardSurface.tapKind(kind: KeyboardSurface.Kind) {
        val rs = rows()
        val ri = rs.indexOfFirst { r -> r.any { it.kind == kind } }
        assertTrue(ri >= 0, "$kind not in the current layer")
        goRow(ri)
        goKey(rs[ri].indexOfFirst { it.kind == kind })
        tap()
    }

    @Test
    fun grammarOpensOnHomeRowRestsOnFirstKeyAndStaysInRowAfterTyping() {
        val (kb, _) = opened()
        assertEquals(KeyboardSurface.Stage.ROW, kb.stage)
        assertEquals(KeyboardSurface.HOME_ROW, kb.row)
        kb.tap()
        assertEquals(KeyboardSurface.Stage.KEY, kb.stage)
        assertEquals(0, kb.key)                       // rest on the row's first key
        kb.tap()                                      // types 'a'
        assertEquals("a", kb.draft)
        assertEquals(KeyboardSurface.Stage.KEY, kb.stage)   // stays in the row (Adam)
        kb.scroll(1); kb.tap()                        // 's'
        assertEquals("as", kb.draft)
        assertTrue(kb.back())                         // KEY -> ROW
        assertEquals(KeyboardSurface.Stage.ROW, kb.stage)
        assertFalse(kb.back())                        // ROW -> cancel wanted
    }

    @Test
    fun wrapsOnBothAxesSoNoKeyIsFartherThanHalfARow() {
        val (kb, _) = opened()
        val rows = kb.rows().size
        kb.scroll(-1); kb.scroll(-1); kb.scroll(-1)   // up from the home row through the top
        assertEquals((KeyboardSurface.HOME_ROW - 3).mod(rows), kb.row)
        kb.goRow(1)                                   // qwerty row
        kb.tap()
        kb.scroll(-1)                                 // back from 'q' wraps to the row's end
        assertEquals(kb.rows()[1].size - 1, kb.key)
    }

    @Test
    fun typesAWordWithShiftSymbolsSpaceAndBackspace() {
        val (kb, log) = opened()
        kb.tapKind(KeyboardSurface.Kind.SHIFT)        // one-shot
        assertEquals(1, kb.shift)
        kb.type("u")                                  // capitalized, shift released
        assertEquals("U", kb.draft)
        assertEquals(0, kb.shift)
        kb.type("b"); kb.type("u"); kb.type("n"); kb.type("t"); kb.type("u")
        kb.tapKind(KeyboardSurface.Kind.SPACE)
        kb.type("2"); kb.type("6")
        kb.tapKind(KeyboardSurface.Kind.BACKSPACE)
        assertEquals("Ubuntu 2", kb.draft)
        kb.tapKind(KeyboardSurface.Kind.SYMBOLS)
        assertTrue(kb.symbols)
        kb.type("&")
        kb.tapKind(KeyboardSurface.Kind.SYMBOLS)      // back to letters
        assertFalse(kb.symbols)
        kb.type(".")
        assertEquals("Ubuntu 2&.", kb.draft)
        // caps lock: two taps, stays on across keys, third tap releases
        kb.tapKind(KeyboardSurface.Kind.SHIFT); kb.tapKind(KeyboardSurface.Kind.SHIFT)
        assertEquals(2, kb.shift)
        kb.type("o"); kb.type("k")
        assertEquals("Ubuntu 2&.OK", kb.draft)
        kb.tapKind(KeyboardSurface.Kind.SHIFT)
        assertEquals(0, kb.shift)
        // Enter commits the draft
        val t = run { kb.goRow(2); kb.goKey(kb.rows()[2].size - 1); kb.tap() }
        assertTrue(t is KeyboardSurface.Tap.Commit && t.text == "Ubuntu 2&.OK")
        assertTrue(log.isEmpty())                     // the shell runs the callbacks, not the surface
    }

    @Test
    fun caretEditingLeftRightDelClear() {
        val (kb, _) = opened("abc")
        kb.tapKind(KeyboardSurface.Kind.LEFT)         // caret before 'c'
        kb.type("x")
        assertEquals("abxc", kb.draft)
        kb.tapKind(KeyboardSurface.Kind.DEL)          // deletes forward: 'c'
        assertEquals("abx", kb.draft)
        kb.tapKind(KeyboardSurface.Kind.RIGHT)        // at the end already: no-op
        kb.type("y")
        assertEquals("abxy", kb.draft)
        kb.tapKind(KeyboardSurface.Kind.CLEAR)
        assertEquals("", kb.draft)
    }

    @Test
    fun abcLayoutTypesTooAndAnUnknownLayoutFallsBackToQwerty() {
        val (kb, _) = opened(layout = "abc")
        kb.type("z"); kb.type("a")
        assertEquals("za", kb.draft)
        val (kb2, _) = opened(layout = "dvorak")
        assertEquals("q", kb2.rows()[1][0].ch)
    }

    @Test
    fun liveKeysWrapOntoTwoRowsAndMoreThanTwelveAreRefusedLoudly() {
        val eight = (1..8).map { KeyboardSurface.ExtraKey("k$it", "id$it") }
        val rows = KeyboardSurface.buildRows("qwerty", false, eight)
        assertEquals(7, rows.size)                          // 5 + two live rows of 4
        assertEquals(4, rows[5].size); assertEquals(4, rows[6].size)
        assertTrue(rows[5].all { it.span == 3 } && rows[6].all { it.span == 3 })
        val twelve = (1..12).map { KeyboardSurface.ExtraKey("k$it", "id$it") }
        assertEquals(7, KeyboardSurface.buildRows("qwerty", false, twelve).size)
        val thirteen = (1..13).map { KeyboardSurface.ExtraKey("k$it", "id$it") }
        val e = kotlin.test.assertFailsWith<IllegalArgumentException> { KeyboardSurface.buildRows("qwerty", false, thirteen) }
        assertTrue(e.message!!.contains("at most"), e.message)
        // the surface fits every height with two live rows too
        val (kb, _) = opened(extra = twelve)
        for (h in listOf(288, 352, 416, 480)) {
            val l = Layout().withHeightMode(h, VPos.TOP)
            val box = kb.rect(l)!!
            assertTrue(box.y >= l.content.y && box.bottom <= l.content.bottom, "7 rows fit at $h: $box in ${l.content}")
        }
    }

    /** A rasterizer that refuses malformed UTF-16 — a lone surrogate reaching
     *  measure/draw is the R3-K5 defect made loud. */
    private class StrictText : wm.damage.core.text.TextRasterizer {
        private val inner = FakeText()
        private fun check(t: String) {
            var i = 0
            while (i < t.length) {
                val c = t[i]
                if (Character.isHighSurrogate(c)) { require(i + 1 < t.length && Character.isLowSurrogate(t[i + 1])) { "lone high surrogate at $i" }; i += 2 }
                else { require(!Character.isLowSurrogate(c)) { "lone low surrogate at $i in '${t.take(12)}'" }; i++ }
            }
        }
        override fun measure(text: String, font: wm.damage.core.text.FontSpec): Int { return inner.measure(text, font) }
        override fun metrics(font: wm.damage.core.text.FontSpec) = inner.metrics(font)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: wm.damage.core.text.FontSpec, level: Int) {
            check(text); inner.draw(surface, x, y, text, font, level)
        }
        override fun covers(text: String, font: wm.damage.core.text.FontSpec) = true
    }

    @Test
    fun aPannedEmojiDraftNeverStartsInsideASurrogatePair() {
        val kb = KeyboardSurface(StrictText())
        kb.openWith(KeyboardSurface.Spec("search", "\ud83d\ude00".repeat(80), onCommit = {}), "qwerty")
        val g = Gray8(640, 480)
        kb.paint(g, Layout())                            // the pan lands on a pair boundary or the draw throws
        repeat(3) { kb.tapKind(KeyboardSurface.Kind.LEFT) }
        kb.paint(g, Layout())
    }

    /** A rasterizer that covers ASCII only — the phone's face for a CJK name. */
    private class AsciiOnlyText : wm.damage.core.text.TextRasterizer {
        private val inner = FakeText()
        override fun measure(text: String, font: wm.damage.core.text.FontSpec) = inner.measure(text, font)
        override fun metrics(font: wm.damage.core.text.FontSpec) = inner.metrics(font)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: wm.damage.core.text.FontSpec, level: Int) {
            for (ch in text) require(ch.code in 0x20..0x7E) { "a glyph the face cannot draw reached the rasterizer: U+%04X".format(ch.code) }
            inner.draw(surface, x, y, text, font, level)
        }
        override fun covers(text: String, font: wm.damage.core.text.FontSpec) = text.all { it.code in 0x20..0x7E }
    }

    @Test
    fun uncoveredGlyphsDisplayAsQuestionMarksWithoutMovingTheCaret() {
        val kb = KeyboardSurface(AsciiOnlyText())
        kb.openWith(KeyboardSurface.Spec("rename \u4e2d\u6587", "\u65e5\u672c\ud83d\ude00x", onCommit = {}), "qwerty")
        val l = Layout()
        val g = Gray8(640, 480)
        kb.paint(g, l)                                   // draws '?' forms, never throws
        kb.tapKind(KeyboardSurface.Kind.LEFT)            // caret moves by CODE POINT over the emoji
        kb.tapKind(KeyboardSurface.Kind.LEFT)
        kb.type("a")
        assertEquals("\u65e5\u672ca\ud83d\ude00x", kb.draft)  // the draft keeps the real characters
        kb.paint(g, l)
    }

    /** The x of the caret bar (a HEAD-level 2 px column) in the text line, or -1. */
    private fun caretX(g: Gray8, kb: KeyboardSurface, l: Layout): Int {
        val box = kb.rect(l)!!
        for (x in box.x until box.right) {
            var head = 0
            for (y in box.y + 8 until box.y + 40) if (g[x, y] == wm.damage.core.gfx.Level.HEAD) head++
            if (head >= 12) return x
        }
        return -1
    }

    @Test
    fun aLongPromptIsFittedAndTheDraftStillPaints() {
        val (kb, _) = opened("draft")
        kb.close()
        kb.openWith(KeyboardSurface.Spec("type -> " + "x".repeat(200), "draft", onCommit = {}), "qwerty")
        val g = Gray8(640, 480)
        val l = Layout()
        val box = kb.paint(g, l)!!
        for (y in 0 until 480) for (x in 0 until 640) {
            if (!(x in box.x until box.right && y in box.y until box.bottom)) assertEquals(0, g[x, y], "ink outside the box at $x,$y")
        }
        // the caret painted, to the right of the fitted prompt's third of the line
        val cx = caretX(g, kb, l)
        assertTrue(cx > box.x + KeyboardSurface.UNITS * KeyboardSurface.UNIT / 3, "caret at $cx")
    }

    @Test
    fun aPannedDraftKeepsTheCaretInsideTheFittedTextNeverPastTheMark() {
        val (kb, _) = opened("a".repeat(80))
        repeat(10) { kb.tapKind(KeyboardSurface.Kind.LEFT) }     // the caret 10 glyphs before the end
        val g = Gray8(640, 480)
        val l = Layout()
        val box = kb.paint(g, l)!!
        val cx = caretX(g, kb, l)
        val right = box.x + (box.w - KeyboardSurface.UNITS * KeyboardSurface.UNIT) / 2 / 4 * 4 + KeyboardSurface.UNITS * KeyboardSurface.UNIT
        // Draw.fit keeps 14 px for its mark at the line's end and the caret sits
        // at or before the last drawn glyph — so it never enters the mark zone
        assertTrue(cx in 0..(right - 18), "the caret ($cx) sits inside the fitted text, before the tail's mark (right=$right)")
    }

    @Test
    fun tooManyLiveKeysAreRefusedBeforeAnythingOpensAndHarmlessKeysHeadEachRow() {
        val kb = KeyboardSurface(FakeText())
        kb.openWith(KeyboardSurface.Spec("first", "kept", onCommit = {}), "qwerty")
        val thirteen = (1..13).map { KeyboardSurface.ExtraKey("k$it", "id$it") }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            kb.openWith(KeyboardSurface.Spec("x", "", thirteen, onCommit = {}), "qwerty")
        }
        // the refused spec touched nothing: the surface still holds the first one (R3-K6)
        assertTrue(kb.open)
        assertEquals("first", kb.current()?.title)
        assertEquals("kept", kb.draft)
        kb.close()
        // a config whose harmless keys are not first: both rows still rest on one
        val mixed = listOf("Enter", "C-c", "C-d", "C-z", "C-l", "Escape", "Tab").map {
            KeyboardSurface.ExtraKey(it, it, harmless = it == "Escape" || it == "Tab")
        }
        val rows = KeyboardSurface.buildRows("qwerty", false, mixed)
        assertEquals(7, rows.size)
        assertEquals("Escape", rows[5][0].id)
        assertEquals("Tab", rows[6][0].id)
        assertEquals(7, rows[5].size + rows[6].size)
        assertEquals(KeyboardSurface.UNITS, rows[5].sumOf { it.span }); assertEquals(KeyboardSurface.UNITS, rows[6].sumOf { it.span })
    }

    @Test
    fun aRequesterRowIsLiveAndStaysOpen() {
        val extra = listOf(KeyboardSurface.ExtraKey("Esc", "Escape"), KeyboardSurface.ExtraKey("Tab", "Tab"))
        val (kb, _) = opened(extra = extra)
        kb.goRow(5)
        kb.tap()
        val t = kb.tap()
        assertTrue(t is KeyboardSurface.Tap.Extra && t.id == "Escape")
        assertTrue(kb.open)
    }

    @Test
    fun paintsInsideItsBoxAtEveryHeightAndOnTheGrid() {
        val (kb, _) = opened("a rather long draft that will have to pan sideways to keep its caret visible")
        for (h in listOf(288, 352, 416, 480)) {
            val l = Layout().withHeightMode(h, VPos.TOP)
            val box = kb.rect(l)!!
            assertTrue(box.y >= l.content.y && box.bottom <= l.content.bottom, "fits at $h: $box in ${l.content}")
            assertEquals(0, box.x % 4); assertEquals(0, box.y % 2); assertEquals(0, box.w % 4); assertEquals(0, box.h % 2)
            val g = Gray8(640, 480)
            val painted = kb.paint(g, l)!!
            assertEquals(box, painted)
            // nothing outside the box (the paint is a hole: it may only write inside)
            for (y in 0 until 480) for (x in 0 until 640) {
                if (!(x in box.x until box.right && y in box.y until box.bottom)) {
                    assertEquals(0, g[x, y], "ink outside the keyboard box at $x,$y (h=$h)")
                }
            }
        }
    }

    // ------------------------------------------------------------ the shell wiring
    /** A window whose only job is to ask for the keyboard on tap. */
    private class Asker : DamageWindow("asker", "Asker", IconKind.FILES) {
        val model = ListModel()
        var services: ShellServices? = null
        val log = ArrayList<String>()
        var draft = ""
        var extraFirst = false
        var tooMany = false
        override fun onRegistered(ctx: ShellServices) { services = ctx }
        override fun view(): WindowView = WindowView.ListView(model, { 1 }, { _, _, _, _ -> }, { _, _, _ -> }, {
            val extra = when {
                tooMany -> (1..13).map { KeyboardSurface.ExtraKey("k$it", "id$it") }
                extraFirst -> listOf(KeyboardSurface.ExtraKey("Esc", "Escape"))
                else -> emptyList()
            }
            val ok = services?.openKeyboard(KeyboardSurface.Spec("ask", draft, extra,
                onCommit = { log.add("commit:$it") },
                onCancel = { draft = it; log.add("cancel:$it") },
                onExtra = { log.add("extra:$it") }), owner = this)
            log.add("opened:$ok")
        })
        override fun summary() = Summary("asker")
        override fun saveState(): JsonObject = buildJsonObject { }
        override fun restoreState(state: JsonObject) {}
    }

    private class Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-kb")
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
        val win = Asker()
        init { shell.register(win) }
        suspend fun start() { shell.start(); shell.postGesture(EvenHubMsg.EV_CLICK) }   // Main row 0 = Asker
        suspend fun stop() { shell.stop(); scope.cancel(); tmp.toFile().deleteRecursively() }
        fun g(t: Int) = shell.postGesture(t)
    }

    private suspend fun awaitTrue(what: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 20_000) delay(20)
        assertTrue(cond(), "did not converge: $what")
    }

    @Test
    fun shellOpensOnRequestTypesCommitsAndKeepsDraftOnCancel(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            awaitTrue("asker focused") { r.shell.isQuiescent() }
            r.g(EvenHubMsg.EV_CLICK)                              // ask
            awaitTrue("keyboard open") { r.shell.keyboardIsOpen }
            assertEquals("ask", r.shell.keyboardTitle)
            // home row → 'a' → 's' (stay in row) → back to ROW → cancel: draft kept
            r.g(EvenHubMsg.EV_CLICK); r.g(EvenHubMsg.EV_CLICK)
            r.g(EvenHubMsg.EV_SCROLL_BOTTOM); r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("typed 'as'") { r.shell.keyboardDraft() == "as" }
            r.g(EvenHubMsg.EV_DOUBLE_CLICK)                       // KEY → ROW
            r.g(EvenHubMsg.EV_DOUBLE_CLICK)                       // ROW → cancel
            // the surface closes BEFORE the cancel callback runs (a re-ask from
            // the callback needs it closed), so wait for the callback itself
            awaitTrue("cancelled with the draft") { r.win.log.contains("cancel:as") }
            assertFalse(r.shell.keyboardIsOpen)
            assertEquals("as", r.win.draft)
            // reopen: the draft is pre-filled; Enter (end of the home row) commits
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("reopened with the draft") { r.shell.keyboardIsOpen && r.shell.keyboardDraft() == "as" }
            r.g(EvenHubMsg.EV_CLICK)                              // enter the home row
            r.g(EvenHubMsg.EV_SCROLL_TOP)                         // wrap: 'a' → Enter (last key)
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("committed") { r.win.log.contains("commit:as") }
            assertFalse(r.shell.keyboardIsOpen)
            r.stop()
        } catch (e: Throwable) { r.stop(); throw e }
    }

    @Test
    fun wheelDisplacesTheKeyboardWithTheDraftKeptAndReplicaLineCommitsThroughIt(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            awaitTrue("asker focused") { r.shell.isQuiescent() }
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("keyboard open") { r.shell.keyboardIsOpen }
            r.g(EvenHubMsg.EV_CLICK); r.g(EvenHubMsg.EV_CLICK)   // 'a'
            awaitTrue("typed") { r.shell.keyboardDraft() == "a" }
            // the §1.3 chord: long-press then double-tap opens the wheel OVER it
            r.g(EvenHubMsg.EV_RING_LONG_PRESS); r.g(EvenHubMsg.EV_DOUBLE_CLICK)
            awaitTrue("wheel displaced the keyboard, draft kept") { r.win.log.contains("cancel:a") }
            assertFalse(r.shell.keyboardIsOpen)
            assertEquals("a", r.win.draft)
            r.g(EvenHubMsg.EV_DOUBLE_CLICK)                       // cancel the wheel
            awaitTrue("wheel closed") { r.shell.isQuiescent() && !r.shell.keyboardIsOpen }
            // reopen; a replica line lands as the draft and commits
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("open again") { r.shell.keyboardIsOpen }
            r.transport.injectText("typed on the phone")
            awaitTrue("replica line committed") { r.win.log.contains("commit:typed on the phone") }
            assertFalse(r.shell.keyboardIsOpen)
            r.stop()
        } catch (e: Throwable) { r.stop(); throw e }
    }

    @Test
    fun liveRowKeysFireWhileOpenAndTheStackDepthCountsTheKeyboard(): Unit = runBlocking {
        val r = Rig()
        try {
            r.win.extraFirst = true
            r.start()
            awaitTrue("asker focused") { r.shell.isQuiescent() }
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("keyboard open") { r.shell.keyboardIsOpen }
            r.g(EvenHubMsg.EV_SCROLL_TOP)                         // home row → up wraps… go the short way
            r.g(EvenHubMsg.EV_SCROLL_TOP); r.g(EvenHubMsg.EV_SCROLL_TOP)   // rows: 2 → 1 → 0 → 5 (the live row)
            r.g(EvenHubMsg.EV_CLICK); r.g(EvenHubMsg.EV_CLICK)   // enter the row, tap Esc
            awaitTrue("live key fired") { r.win.log.contains("extra:Escape") }
            assertTrue(r.shell.keyboardIsOpen)
            r.g(EvenHubMsg.EV_DOUBLE_CLICK); r.g(EvenHubMsg.EV_DOUBLE_CLICK)
            awaitTrue("closed") { !r.shell.keyboardIsOpen }
            r.stop()
        } catch (e: Throwable) { r.stop(); throw e }
    }

    @Test
    fun aShellStopHandsTheDraftBackAndTheKeyboardIsGoneAfterRestart(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            awaitTrue("asker focused") { r.shell.isQuiescent() }
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("keyboard open") { r.shell.keyboardIsOpen }
            r.g(EvenHubMsg.EV_CLICK); r.g(EvenHubMsg.EV_CLICK)   // 'a'
            awaitTrue("typed") { r.shell.keyboardDraft() == "a" }
            r.shell.stop()                                        // the keeper's link-edge restart shape
            assertTrue(r.win.log.contains("cancel:a"), "the draft went back to its requester on stop: ${r.win.log}")
            assertFalse(r.shell.keyboardIsOpen)
            r.shell.start()
            awaitTrue("restarted") { r.shell.isQuiescent() }
            assertFalse(r.shell.keyboardIsOpen)
            r.stop()
        } catch (e: Throwable) { r.stop(); throw e }
    }

    @Test
    fun theShellRefusesTooManyLiveKeysWithoutTouchingTheSurface(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            awaitTrue("asker focused") { r.shell.isQuiescent() }
            r.g(EvenHubMsg.EV_CLICK)                              // a valid ask: open
            awaitTrue("open") { r.shell.keyboardIsOpen }
            r.g(EvenHubMsg.EV_CLICK); r.g(EvenHubMsg.EV_CLICK)   // 'a' typed
            awaitTrue("typed") { r.shell.keyboardDraft() == "a" }
            // a second ask with 13 live keys is refused BEFORE the open surface is touched (R4-K4)
            r.win.tooMany = true
            var refused: Boolean? = null
            r.shell.services.runOnShell {
                refused = !r.shell.services.openKeyboard(KeyboardSurface.Spec("x", "",
                    (1..13).map { KeyboardSurface.ExtraKey("k$it", "id$it") }, onCommit = {}), owner = r.win)
            }
            awaitTrue("refused") { refused == true }
            assertTrue(r.shell.keyboardIsOpen)
            assertEquals("a", r.shell.keyboardDraft())
            assertTrue(r.win.log.none { it.startsWith("cancel:") }, r.win.log.toString())
            r.stop()
        } catch (e: Throwable) { r.stop(); throw e }
    }

    @Test
    fun aMenuCannotOpenOverTheKeyboard(): Unit = runBlocking {
        val r = Rig()
        try {
            r.start()
            awaitTrue("asker focused") { r.shell.isQuiescent() }
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("keyboard open") { r.shell.keyboardIsOpen }
            var refused: Boolean? = null
            r.shell.services.runOnShell {
                refused = !r.shell.services.openMenu(wm.damage.core.shell.MenuSurface.Spec("x",
                    listOf(wm.damage.core.shell.MenuSurface.Item("a")), onCommit = {}))
            }
            awaitTrue("menu refused") { refused == true }
            assertTrue(r.shell.keyboardIsOpen)
            assertFalse(r.shell.menuIsOpen)
            r.stop()
        } catch (e: Throwable) { r.stop(); throw e }
    }
}
