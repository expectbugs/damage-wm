package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.music.*
import wm.damage.core.windows.reader.Epub

/**
 * The 2026-09-03 whole-codebase review — one pin per verified finding.
 * Each test FAILED before its fix; the comment names what it caught.
 */
class Review20260903Test {

    // ------------------------------------------------------------------ F1
    private fun shellPlanes(d: Int = 8): List<Compositor.PlaneRegion> {
        val l = Layout()
        val cd = minOf(d + 4, Layout.CONTENT_INSET_X)
        return listOf(
            Compositor.PlaneRegion(Rect(l.topBar.x, l.topBar.y, l.topBar.w, Layout.TOP_H + Layout.DIV_H), cd),
            Compositor.PlaneRegion(Rect(l.statusBar.x, l.bottomDivider.y, l.statusBar.w, Layout.DIV_H + Layout.STATUS_H), cd),
            Compositor.PlaneRegion(l.content, d),
            Compositor.PlaneRegion(l.lens, 0),
        )
    }

    /** Every pixel a delta covers must belong to the delta's own plane. */
    private fun assertOnItsOwnPlane(comp: Compositor, op: DisplayOp.Delta, where: String) {
        for (y in op.box.y until op.box.bottom step Geometry_Y) for (x in op.box.x until op.box.right step Geometry_X) {
            var pd = 0
            for (p in comp.planes.asReversed()) if (p.rect.contains(Rect(x, y, Geometry_X, Geometry_Y))) { pd = p.disparity; break }
            assertEquals(op.disparity, pd,
                "$where: delta ${op.box} ships d=${op.disparity} but ($x,$y) is plane d=$pd — " +
                    "those pixels land at the wrong shift on BOTH lenses, outside the scanned area, " +
                    "with belief and glass agreeing on the wrong thing")
        }
    }

    private fun drain(comp: Compositor, where: String, budget: Int = 5) {
        var n = 0
        while ((comp.hasPending || comp.needsKeyframe) && n++ < 40) {
            val f = comp.assembleFlush(budget) ?: break
            for (op in f.ops) if (op is DisplayOp.Delta) assertOnItsOwnPlane(comp, op, "$where flush$n")
        }
    }

    /** partition() step 1 merged the two plane-0 GUTTER rects into one
     *  full-width delta that carried the content plane at d=0. */
    @Test
    fun aFlatDeltaNeverCarriesAnotherPlanesPixels() {
        val l = Layout()
        val comp = Compositor()
        comp.planes = shellPlanes()
        comp.damageAll(); drain(comp, "seed")
        val g = comp.composed
        // both gutters + both bars + content + lens at once, over the budget
        g.fillRect(0, 60, 8, 100, 200); comp.damage(Rect(0, 60, 8, 100))
        g.fillRect(632, 60, 8, 100, 200); comp.damage(Rect(632, 60, 8, 100))
        g.fillRect(l.titleCell, 100); comp.damage(l.titleCell)
        g.fillRect(l.opCell, 100); comp.damage(l.opCell)
        g.fillRect(l.content.x + 20, l.content.y + 20, 200, 40, 130)
        comp.damage(Rect(l.content.x + 20, l.content.y + 20, 200, 40))
        g.fillRect(l.lens.x + 20, l.lens.y + 8, 200, 20, 200)
        comp.damage(Rect(l.lens.x + 20, l.lens.y + 8, 200, 20))
        drain(comp, "gutter+content")
    }

    /** coarsen() unions REMAINDER rects by row bands and knew nothing about
     *  the plane map: scattered gutter damage produced full-width d=0 bands. */
    @Test
    fun coarseningTheRemainderNeverCrossesAPlane() {
        val comp = Compositor()
        comp.planes = shellPlanes()
        comp.damageAll(); drain(comp, "seed")
        val g = comp.composed
        for (i in 0 until 40) {
            val y = 40 + i * 8
            g.fillRect(0, y, 8, 2, 200); comp.damage(Rect(0, y, 8, 2))
            g.fillRect(632, y, 8, 2, 200); comp.damage(Rect(632, y, 8, 2))
        }
        drain(comp, "scattered gutter")
    }

    // ------------------------------------------------------------------ F2
    /** The silent box is 200 px but its body was wrapped to the 248 px WINDOW
     *  box: the overflow painted outside the box — cut on the glass with no
     *  mark, and undamaged ink in `composed` nothing would ever send. */
    @Test
    fun theSilentNoticeBodyStaysInsideItsBox() {
        val text = FakeText()
        val n = Notifications(text)
        val l = Layout()
        val notice = Notifications.Notice("SMS · X", "t",
            "a body long enough to overrun the narrow silent box by a wide margin", "12:00")
        n.post(notice, l)
        repeat(6) { n.stepUnfurl(l, silent = true) }
        val g = Gray8(640, 480)
        val box = n.paint(g, l, silent = true)!!
        var outside = 0
        for (y in 0 until 480) for (x in 0 until 640) {
            if (g[x, y] != 0 && !(x >= box.x && x < box.right && y >= box.y && y < box.bottom)) outside++
        }
        assertEquals(0, outside, "the silent notice painted $outside px outside its own damaged box")
    }

    // ------------------------------------------------------------------ F3
    /** `&#146;` and friends are Windows-1252, not C1 controls: the real shelf
     *  held 14,315 of them, every one a tofu box no font can draw. */
    @Test
    fun cp1252NumericReferencesBecomeRealCharacters() {
        val html = "<p>&#147;It&#146;s here&#148; &#151; she said&#133;</p>"
        val t = Epub.htmlToText(html)
        assertTrue('’' in t, "&#146; must become U+2019, was ${t.map { "%04X".format(it.code) }}")
        assertTrue('“' in t && '”' in t, "&#147;/&#148; must become curly double quotes")
        assertTrue('—' in t, "&#151; must become an em dash")
        assertTrue('…' in t, "&#133; must become an ellipsis")
        for (c in t) assertFalse(c.code in 0x80..0x9F, "a C1 control survived: U+%04X".format(c.code))
    }

    /** The characters NONE of the locked faces carry are folded, not drawn.
     *  The C1 range is the big one: the real shelf holds 14,315 of them —
     *  cp1252 punctuation transcoded as latin-1 long before we saw it, sitting
     *  as literal `C2 97` bytes in the middle of sentences. */
    @Test
    fun theExtractorFoldsWhatNoLockedFaceCanDraw() {
        val tmp = Files.createTempDirectory("damage-fold")
        try {
            val f = tmp.resolve("b.txt")
            // exactly the shelf's shape: a real U+2019 beside a mojibake U+0097
            Files.writeString(f, "mother\u2019s barrel\u0097rubies\u0097and re\u2011enter the\u2060 room\n")
            val b = Epub.load(f)
            assertFalse(b.text.any { it.code in 0x80..0x9F },
                "C1 controls must fold through Windows-1252: '${b.text}'")
            assertTrue("barrel\u2014rubies\u2014and" in b.text, "U+0097 is an em dash: '${b.text}'")
            assertTrue('\u2019' in b.text, "correctly-encoded characters are untouched")
            assertFalse('\u2011' in b.text, "U+2011 must fold to '-' (no locked face has it)")
            assertTrue("re-enter" in b.text, "the fold keeps the word readable: '${b.text}'")
            assertFalse('\u2060' in b.text, "zero-width formatters are dropped, not drawn as '?'")
        } finally { tmp.toFile().deleteRecursively() }
    }

    // ------------------------------------------------------------------ F4
    /** A rasterizer that refuses one character, like a real face refusing a
     *  glyph it has no cmap entry for. */
    private class PickyText(private val refuse: Char) : TextRasterizer {
        override fun measure(text: String, font: FontSpec) = text.length * 8
        override fun metrics(font: FontSpec) = FontMetrics(12, 4, 16)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
            drawn.add(text)
            for ((i, ch) in text.withIndex()) if (ch != ' ') surface.fillRect(x + i * 8, y + 2, 6, 10, level)
        }
        override fun covers(text: String, font: FontSpec) = refuse !in text
        val drawn = ArrayList<String>()
    }

    /** The Reader was the one window still drawing dynamic text raw: an
     *  uncoverable glyph was silent tofu on the glass with no log. */
    @Test
    fun theReaderSubstitutesGlyphsTheFaceCannotDraw() = runBlocking {
        val tmp = Files.createTempDirectory("damage-reader-dyn")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val books = tmp.resolve("books")
            Files.createDirectories(books)
            Files.writeString(books.resolve("boוk.txt"), "a line with ו in it\n\nand another line\n")
            val text = PickyText('ו')
            val w = wm.damage.core.windows.reader.ReaderWindow(
                text, wm.damage.core.content.LocalContent(books), scope, null)
            val store = Persistence(tmp.resolve("s.json"))
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(text, transport, store, null, scope)
            shell.register(w)
            shell.start()
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_CLICK)     // enter Reader
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 6_000 &&
                !w.summary().line.contains("book")) delay(20)
            shell.postGesture(wm.damage.core.wire.EvenHubMsg.EV_CLICK)     // open it
            while (System.currentTimeMillis() - t0 < 10_000 && !w.title().contains("p.")) delay(20)
            while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 15_000) delay(10)
            assertTrue(text.drawn.isNotEmpty(), "nothing was drawn")
            val raw = text.drawn.filter { 'ו' in it }
            assertTrue(raw.isEmpty(), "the Reader handed the rasterizer glyphs it cannot draw: $raw")
            shell.stop()
        } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    // ------------------------------------------------------------------ F9
    /** The FLOW renderer drew terminal output raw: the live panes on this
     *  machine carry glyphs JetBrains Mono has no entry for (Claude Code's own
     *  TUI marks), and they were silent tofu. Files' viewer already sanitized
     *  at wrap time; the flow did not. */
    @Test
    fun theFlowRendererSubstitutesGlyphsTheMonoFaceCannotDraw() {
        val text = PickyText('\u23BF')
        val fr = wm.damage.core.windows.tmux.FlowRender(text)
        val g = Gray8(640, 480)
        fr.renderTail(g, Rect(16, 34, 608, 300),
            wm.damage.core.windows.tmux.PaneFrame(
                lines = listOf("  \u23BF  Read(overview.md)", "plain output"),
                cols = 80, rows = 24, cursorX = 0, cursorY = 0,
                cursorVisible = false, alternate = false, capturedAtMs = 0L))
        val raw = text.drawn.filter { '\u23BF' in it }
        assertTrue(raw.isEmpty(), "the flow handed the rasterizer glyphs it cannot draw: $raw")
        assertTrue(text.drawn.any { '?' in it }, "the missing glyph must show as a visible '?': ${text.drawn}")
    }

    // ------------------------------------------------------------------ F5
    /** A RESTORED stack loads only its top: backing into a restored PLAYLIST
     *  level found it empty forever, showing one bare menu row. */
    @Test
    fun aRestoredDeeperLevelLoadsOnTheWayBack() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lib = TinyLib()
            val win = MusicWindow(FakeText(), lib, SimMusicPlayer(lib), scope)
            win.restoreState(buildJsonObject {
                putJsonArray("stack") {
                    add(buildJsonObject { put("kind", "NOWPLAYING"); put("key", ""); put("cursor", 0) })
                    add(buildJsonObject { put("kind", "PLAYLIST"); put("key", "7"); put("cursor", 0) })
                    add(buildJsonObject { put("kind", "INFO"); put("key", "1"); put("cursor", 0) })
                }
            })
            assertEquals(3, win.levelDepth())
            assertTrue(win.back())                      // INFO -> PLAYLIST
            val t0 = System.currentTimeMillis()
            var rows = 0
            while (System.currentTimeMillis() - t0 < 5_000) {
                rows = (win.view() as WindowView.ListView).rowCount()
                if (rows > 1) break
                delay(20)
            }
            assertTrue(rows > 1, "the restored playlist level never loaded — $rows row(s), " +
                "just the bare menu row")
        } finally { scope.cancel() }
    }

    // ------------------------------------------------------------------ F6
    /** The desktop mirror holds an EMPTY player record until the phone's first
     *  push: publishing it fresh-stamped a REMOVAL of the phone's real queue
     *  into a syncable key. */
    @Test
    fun theMirrorNeverPublishesAnEmptyPlayerRecord() = runBlocking {
        val tmp = Files.createTempDirectory("damage-mirror-tomb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lib = TinyLib()
            val win = MusicWindow(FakeText(), lib, MirrorMusicPlayer(), scope, mirror = true)
            assertTrue(win.saveSubState().isEmpty(),
                "an empty player record must not be reported at all: ${win.saveSubState()}")
            val store = Persistence(tmp.resolve("state.json"))
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, store, null, scope)
            shell.register(MusicWindow(FakeText(), lib, MirrorMusicPlayer(), scope, mirror = true))
            shell.start()
            val t0 = System.currentTimeMillis()
            while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 10_000) delay(10)
            shell.stop()
            assertEquals(null, store.record("window.music.player"),
                "the mirror wrote a fresh-stamped tombstone into a SYNCABLE key")
        } finally { scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    // ------------------------------------------------------------------ F7
    /** The quiet notice says "scroll here to raise it"; doing so left the
     *  once-per-run latch set, so a later drop played silently in silence. */
    @Test
    fun raisingTheVolumeArmsTheQuietNoticeAgain() {
        var now = 1_000_000L
        val lib = TinyLib()
        val p = SimMusicPlayer(lib, { now })
        val ev = ArrayList<PlayerEvent>()
        p.addListener(object : MusicPlayer.Listener {
            override fun state(s: PlayerState) {}
            override fun tick(posMs: Long) {}
            override fun event(e: PlayerEvent) { ev.add(e) }
        })
        p.setVolume(5, "test")
        p.playQueue(lib.tracks.map { it.ref() }, 0, Mode.QUEUE, "q")
        assertEquals(1, ev.count { it is PlayerEvent.QuietStream }, "the first quiet start says so")
        p.setVolume(60, "ring")          // exactly what the notice asks for
        p.setVolume(4, "ring")
        ev.clear()
        p.next()
        assertEquals(1, ev.count { it is PlayerEvent.QuietStream },
            "after a raise the notice must arm again — it stayed latched for the whole run")
    }

    // ------------------------------------------------------------------ helpers
    private class TinyLib : MusicLibrary {
        val tracks = listOf(
            TrackMeta(1, "One", "A", "Al", 200_000, 1, 1, 2000, listOf("rock"), emptyList(), folder = "L"),
            TrackMeta(2, "Two", "A", "Al", 200_000, 2, 1, 2000, listOf("rock"), emptyList(), folder = "L"),
        )
        @Volatile var cat = Catalog("c1", 1, tracks, listOf(Artist("A", 2, 1)),
            listOf(Album("Al", "A", 2, 2000)), listOf(Playlist(7, "P", "manual", false, 2)),
            listOf(VocabTerm("rock", "genre", 2)), listOf(1))
        override fun stateLine() = ""
        override fun catalog() = cat
        override fun refreshCatalog() {}
        override fun search(q: String) = tracks.map { it.ref() }
        override fun ask(request: String) = ResolvedQueue(emptyList(), "empty", request, "none")
        override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int) = emptyList<TrackRef>()
        override fun randomLibrary(n: Int, exclude: List<Int>) = emptyList<TrackRef>()
        override fun playlists() = cat.playlists
        override fun playlistTracks(id: Int) = tracks.map { it.ref() }
        override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean) = Playlist(9, name, count = trackIds.size)
        override fun renamePlaylist(id: Int, name: String) {}
        override fun deletePlaylist(id: Int) {}
        override fun setPlaylistTracks(id: Int, trackIds: List<Int>) {}
        override fun lyrics(trackId: Int) = null
        override fun searchLyrics(trackId: Int, query: String) = emptyList<Lyrics>()
        override fun setLyrics(trackId: Int, choice: Lyrics) {}
        override fun art(trackId: Int, px: Int): ByteArray? = null
        override fun viz(trackId: Int) = null
        override fun ytSearch(q: String) = emptyList<YtResult>()
        override fun ytGrab(id: String) = "j"
        override fun ytStatus(job: String) = YtJob(job, "g", "done", 100, 1)
        override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) {}
        override fun streamUrl(trackId: Int, profile: AudioProfile) = "sim://$trackId"
        override fun pretranscode(profile: AudioProfile) = "b"
        override fun rescan() = "r"
        override fun addListener(l: MusicLibrary.Listener) {}
        override fun removeListener(l: MusicLibrary.Listener) {}
        override fun setFocused(focused: Boolean, paceMs: Long) {}
    }

    private companion object {
        const val Geometry_X = 4
        const val Geometry_Y = 2
    }
}
