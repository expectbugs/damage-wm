package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Pack
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowLanes
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.windows.music.Mode
import wm.damage.core.windows.music.MusicWindow
import wm.damage.core.windows.music.PlayState
import wm.damage.core.windows.music.SimMusicPlayer
import wm.damage.core.windows.music.TrackMeta
import wm.damage.core.windows.music.TrackRef
import wm.damage.core.wire.EvenHubMsg

/**
 * Music's motion update, stage L, through the real shell, Music's presenter and the firmware model at ladder stage 5 on a
 * 256 KiB session (and at stage 4, where nothing of it runs): the bar on Now Playing is a held lane — its area written
 * and counted, a chain open in slots 0 and 1, the glasses growing the bar while the phone writes nothing into it (the
 * lens's shadow under the bar stays the chain's start while the window's own bar moves on), the elapsed time painted
 * each second — and a pause ends it, the panel then the truth everywhere. One clock: the player's, the window's and the
 * model's engine read the same one.
 */
class MusicLaneShellTest {

    private class Lib : wm.damage.core.windows.music.MusicLibrary {
        val tracks = listOf(
            TrackMeta(1, "Short", "A", "B", 40_000, 4, 1, 2020, listOf("rock")),
            TrackMeta(2, "Shorter", "A", "B", 15_000, 6, 1, 2020, listOf("rock")),
        )
        val cat = wm.damage.core.windows.music.Catalog("c1", 1, tracks)
        override fun stateLine() = ""
        override fun catalog() = cat
        override fun refreshCatalog() {}
        override fun search(q: String) = emptyList<TrackRef>()
        override fun ask(request: String) = wm.damage.core.windows.music.ResolvedQueue()
        override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int) = emptyList<TrackRef>()
        override fun randomLibrary(n: Int, exclude: List<Int>) = emptyList<TrackRef>()
        override fun playlists() = emptyList<wm.damage.core.windows.music.Playlist>()
        override fun playlistTracks(id: Int) = emptyList<TrackRef>()
        override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean) = wm.damage.core.windows.music.Playlist(1, name)
        override fun renamePlaylist(id: Int, name: String) {}
        override fun deletePlaylist(id: Int) {}
        override fun setPlaylistTracks(id: Int, trackIds: List<Int>) {}
        override fun lyrics(trackId: Int): wm.damage.core.windows.music.Lyrics? = null
        override fun searchLyrics(trackId: Int, query: String) = emptyList<wm.damage.core.windows.music.Lyrics>()
        override fun setLyrics(trackId: Int, choice: wm.damage.core.windows.music.Lyrics) {}
        override fun art(trackId: Int, px: Int): ByteArray? = ByteArray(px * px / 2) { 0x77 }
        override fun viz(trackId: Int): wm.damage.core.windows.music.VizData? = null
        override fun ytSearch(q: String) = emptyList<wm.damage.core.windows.music.YtResult>()
        override fun ytGrab(id: String) = "j"
        override fun ytStatus(job: String) = wm.damage.core.windows.music.YtJob(job)
        override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) {}
        override fun streamUrl(trackId: Int, profile: wm.damage.core.windows.music.AudioProfile) = "sim://$trackId"
        override fun pretranscode(profile: wm.damage.core.windows.music.AudioProfile) = ""
        override fun rescan() = ""
        override fun addListener(l: wm.damage.core.windows.music.MusicLibrary.Listener) {}
        override fun removeListener(l: wm.damage.core.windows.music.MusicLibrary.Listener) {}
        override fun setFocused(focused: Boolean, paceMs: Long) {}
    }

    private class Rig(stage: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-mml")
        /** The one clock the player, the window and the engine read. */
        val clock: () -> Long = { System.currentTimeMillis() }
        val sim = GlassFirmwareSim().also { it.damageContract = 2; it.damageFeatures = 0xdff }
        val t = StartRig(sim, scope, clock = clock, c1StageFile = tmp.resolve("c1-stage").also { f ->
            Files.writeString(f, wm.damage.core.transport.LadderRecord(stage = stage, sessionKib = 256).encode()) })
        val journal = tmp.resolve("journal.jsonl")
        val ct = wm.damage.core.comp.CachedText(DigitText())
        val shell = Shell(ct, t, Persistence(tmp.resolve("state.json")), journal, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val lib = Lib()
        val player = SimMusicPlayer(lib, clock)
        /** Music draws with the shell's text, as the hosts hand it (cached glyph runs while Cached text is on — the time
         *  digits beside the bar go as such, which the transport reads as reaching the panel's edges). */
        val win = MusicWindow(ct, lib, player, scope, clock = clock)

        init {
            shell.register(win)
            t.devProbe("c1", "$stage")
            scope.launch { while (true) { val now = clock(); for (a in Arm.entries) sim.conformanceWake(a, now); delay(4) } }
        }

        fun notes(kind: String): List<String> = if (!Files.exists(journal)) emptyList() else Files.readAllLines(journal)
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").substringBefore("\"") }

        suspend fun until(what: String, maxMs: Long = 30_000, cond: () -> Boolean) =
            waitUntil(maxMs, 5, { "$what — lane ${notes("lane").takeLast(5)}; ${onLoop { shell.lanesForTest() }}; ${shell.quiescenceReport()}" }, cond)

        fun <T> onLoop(block: () -> T): T {
            val out = java.util.concurrent.CompletableFuture<T>()
            shell.services.runOnShell { out.complete(block()) }
            return out.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }

        /** Up at 480 with Cached text on, Music open at Now Playing, a track playing. */
        suspend fun up() {
            shell.start(); until("start") { shell.isQuiescent() }
            shell.updateSettings { it.copy(heightMode = 480, cachedText = "on") }
            until("the atlas goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
            until("the idle lanes at rest") { onLoop { shell.noticeLaneDoneForTest() && !shell.cacheWorkInFlight } }
            shell.postGesture(EvenHubMsg.EV_CLICK)
            until("music open") { shell.currentWindowId() == "music" }
            player.playQueue(lib.tracks.map { it.ref() }, 0, Mode.QUEUE, "test")
            until("playing") { player.state.play == PlayState.PLAYING }
        }

        fun laneTagIn(arm: Arm): Int? = (0..1).firstNotNullOfOrNull { s -> sim.motionTagIn(arm, s)?.takeIf { it and 0xF000 == WindowLanes.TAG } }

        /** Open the Music menu at Now Playing and take the row labelled [label] (by name, never by counting notches). */
        suspend fun menuPick(label: String) {
            shell.postGesture(EvenHubMsg.EV_CLICK)
            until("the Music menu") { shell.menuIsOpen && shell.isQuiescent() }
            val i = shell.menuLabels.indexOfFirst { it == label || it.startsWith(label) }
            assertTrue(i >= 0, "menu row '$label' not in ${shell.menuLabels}")
            repeat((i - shell.menuCursor).mod(shell.menuLabels.size)) { shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }
            until("the cursor on '$label'") { shell.menuCursor == i }
            shell.postGesture(EvenHubMsg.EV_CLICK)
        }

        /** The elapsed digits on RIGHT's panel: left of the bar on its row ([bar] is RIGHT's rect; the state glyph left of
         *  them is still while the song plays). How many times they changed in [ms], or — [first] — when they first did. */
        suspend fun digitChanges(bar: Rect, ms: Long, first: Boolean = false): Int {
            val box = Rect(maxOf(0, bar.x - 60), bar.y - 8, 56, 20)
            val stride = (Geometry.PANEL_W + 1) / 2
            fun cut(): ByteArray { val p = sim.right.panel.copyOf(); val o = java.io.ByteArrayOutputStream()
                for (y in box.y until box.bottom) o.write(p, y * stride + box.x / 2, (box.w + 1) / 2); return o.toByteArray() }
            var last = cut(); var n = 0
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                delay(10)
                val c = cut()
                if (!c.contentEquals(last)) { n++; last = c; if (first) return n }
            }
            return n
        }

        suspend fun stop() { shell.stop(); scope.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** [FakeText] whose digits differ — a box whose height follows the digit — so the elapsed time's repaint each second
     *  changes pixels and goes out (as a cached glyph run while Cached text is on). With [FakeText] every digit is the same
     *  box: a time-text repaint changed nothing and sent nothing, and no gate here could see the time text at all. */
    private class DigitText : wm.damage.core.text.TextRasterizer by FakeText() {
        override fun draw(surface: wm.damage.core.gfx.Gray8, x: Int, y: Int, text: String, font: wm.damage.core.text.FontSpec, level: Int) {
            for ((i, ch) in text.withIndex()) if (ch != ' ') {
                val h = if (ch.isDigit()) 1 + (ch - '0') else 10
                surface.fillRect(x + i * 8, y + 12 - h, 6, h, level)
            }
        }
    }

    /** The decoded level at (x, y) of a packed lens buffer. */
    private fun lv(packed: ByteArray, x: Int, y: Int): Int { val b = packed[y * ((Geometry.PANEL_W + 1) / 2) + (x shr 1)].toInt() and 0xFF; return if (x and 1 == 0) b shr 4 else b and 0x0F }

    /** The lit pixels of a bar row in a packed buffer: the run from [x0] at [lit]. */
    private fun litRun(packed: ByteArray, x0: Int, w: Int, y: Int, lit: Int): Int = (0 until w).takeWhile { lv(packed, x0 + it, y) == lit }.count()

    @Test
    fun theBarIsAHeldLaneTheGlassesGrowWhileThePhoneWritesNothingIntoIt(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            assertTrue(r.onLoop { r.shell.services.lanesInForce() }, "stage 5 on 256 KiB: lanes in force")
            r.until("a chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null && r.laneTagIn(Arm.LEFT) != null }
            val bar = assertNotNull(r.onLoop { r.shell.comp.laneHold.firstOrNull() }, "the compositor holds the lane").second   // RIGHT's rect
            val y = bar.y + bar.h / 2
            // the chain's start: RIGHT's shadow under the bar is the picture the chain started over, and stays so
            val shadow0 = r.sim.right.shadow.copyOf()
            val lit0 = litRun(shadow0, bar.x, bar.w, y, 8)
            delay(2_500)                                         // a 40-s song on a ≈ 400-px bar: ≈ 10 px a second
            val shadow1 = r.sim.right.shadow.copyOf()
            val panel1 = r.sim.right.panel.copyOf()
            for (x in bar.x until bar.right) assertEquals(lv(shadow0, x, y), lv(shadow1, x, y), "no write into the lane at x $x while it is held")
            val litPanel = litRun(panel1, bar.x, bar.w, y, 8)
            assertTrue(litPanel >= lit0 + 15, "the glasses grew the bar: $lit0 px at the start, $litPanel px on RIGHT's panel 2.5 s on")
            // the window's own bar (the truth) moved on with it, within a few pixels of the lens's
            val truthLit = r.onLoop { val c = r.shell.comp.expectedLens(false); c }   // belief: still the start
            assertEquals(lit0, (0 until bar.w).takeWhile { truthLit[bar.x + it, y] / 17 == 8 }.count(), "belief keeps the start under the lane")
            // the chain's start is said in the journal (the time text each second: theElapsedTimeTicksEachSecond…)
            assertTrue(r.notes("lane").any { "a chain starts" in it }, "said: ${r.notes("lane")}")
            // a seek: the chain starts again from the song's new place (a new tag, the bar written from the truth first)
            val tagBefore = r.laneTagIn(Arm.RIGHT)
            val chainsBefore = r.notes("lane").count { "a chain starts" in it }
            r.player.seekTo(30_000)
            // at once — not at the old chain's next PLAY-AFTER, half a stretch on (≈ 12 s here)
            r.until("a new chain after the seek", maxMs = 3_000) { r.notes("lane").count { "a chain starts" in it } > chainsBefore && r.laneTagIn(Arm.RIGHT).let { it != null && it != tagBefore } }
            assertTrue(r.notes("lane").any { "its key changed" in it }, "the seek changed the key: ${r.notes("lane").takeLast(4)}")
            r.until("the seek's bar on the panel") { litRun(r.sim.right.panel.copyOf(), bar.x, bar.w, y, 8) >= bar.w * 7 / 10 }
            // a pause ends the chain: nothing of it open, the panel the truth under the bar
            r.player.pause()
            r.until("the chain closed on both lenses") { r.laneTagIn(Arm.RIGHT) == null && r.laneTagIn(Arm.LEFT) == null && !r.onLoop { r.shell.lanesOpenForTest } }
            r.until("settled") { r.shell.isQuiescent() }
            delay(300)
            val panel2 = r.sim.right.panel.copyOf()
            val belief = r.onLoop { r.shell.comp.expectedLens(false).copy() }
            for (x in bar.x until bar.right) assertEquals(belief[x, y] / 17, lv(panel2, x, y), "paused: the panel is belief at x $x")
            val truthNow = r.onLoop { Pack.level(r.shell.comp.composed[bar.x + 8 + 8, y]) }
            assertTrue(truthNow == 8 || truthNow == 1, "the window's continuous bar at the paused place")
        } finally { r.stop() }
    }

    /** The elapsed time each second after a level is pushed over Now Playing and popped (the first review's finding 2,
     *  `MM-decisions.md` "Stage L, review pass 1" item 2): the push ended the time text's run (its next tick found another
     *  level on top) and the lanes' hook starts it again — RIGHT's panel changes at the digits twice in 2.5 s. */
    @Test
    fun theElapsedTimeTicksEachSecondAfterALevelIsPushedAndPopped(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.until("a chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null }
            val bar = assertNotNull(r.onLoop { r.shell.comp.laneHold.firstOrNull() }, "the compositor holds the lane").second
            val before = r.digitChanges(bar, 2_200)              // the control: the run before any push
            r.menuPick("Queue")
            r.until("the queue level") { r.win.levelDepth() == 2 && r.shell.isQuiescent() }
            delay(1_500)                                         // past a second: the run's next tick sees the level
            r.shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
            r.until("back at Now Playing") { r.win.levelDepth() == 1 && r.shell.isQuiescent() }
            val composes0 = r.onLoop { r.shell.contentComposesForTest() }
            val changes = r.digitChanges(bar, 2_500)
            val composes = r.onLoop { r.shell.contentComposesForTest() } - composes0
            assertTrue(changes >= 2, "the elapsed digits changed $changes time(s) on RIGHT's panel in 2.5 s after the pop (before the push: $before; " +
                "content composed $composes time(s) meanwhile; ${r.onLoop { r.shell.lanesForTest() }}; lane ${r.notes("lane").takeLast(8)}; local ${r.notes("local").takeLast(4)})")
        } finally { r.stop() }
    }

    /** A chain ended by the menu opening over Now Playing: as the menu settles nothing of the chain is open on RIGHT, and
     *  the bar beside the menu is the window's own, moved on from the chain's start — not the start picture the shadow
     *  held under the chain. The tap comes just after a time-text repaint landed. Measured (MM-LF): this holds with the
     *  release's write removed too (row `MML-release-cells` not caught) — something else in that path writes the bar;
     *  it is the menu path's outcome, not a pin of the release. */
    @Test
    fun theMenuOverNowPlayingEndsTheChainAndTheBarIsWrittenFromTheTruth(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.until("a chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null && r.laneTagIn(Arm.LEFT) != null }
            val bar = assertNotNull(r.onLoop { r.shell.comp.laneHold.firstOrNull() }, "the compositor holds the lane").second
            val y = bar.y + bar.h / 2
            val lit0 = litRun(r.sim.right.shadow.copyOf(), bar.x, bar.w, y, 8)
            delay(3_000)                                         // ≈ 10 px a second: the window's bar moves on
            r.digitChanges(bar, 1_500, first = true)             // a time-text repaint has just landed
            val t0 = System.currentTimeMillis()
            r.shell.postGesture(EvenHubMsg.EV_CLICK)
            r.until("the menu up and settled") { r.shell.menuIsOpen && r.shell.isQuiescent() }
            val panel = r.sim.right.panel.copyOf()
            val tag = r.laneTagIn(Arm.RIGHT)
            val took = System.currentTimeMillis() - t0
            assertTrue(took < 900, "the menu settled ${took} ms after the tap — past the next time-text repaint, the check cannot tell")
            assertEquals(null, tag, "nothing of the chain open on RIGHT as the menu settles")
            val lit = litRun(panel, bar.x, bar.w, y, 8)
            assertTrue(lit >= lit0 + 15, "the bar beside the menu is the window's own: $lit px lit on RIGHT's panel, $lit0 at the chain's start")
        } finally { r.stop() }
    }

    /** Music Mode at a chain restart (the second review's finding 1, `MM-decisions.md` "Stage L, review pass 2" item 1).
     *  While the chain runs, the window's own bar (the truth) moves on each second with the time line, and no byte goes
     *  into the lane — RIGHT's shadow under it stays the chain's start. Then a seek to 0:30 of 0:40 arrives as one from
     *  another surface does: the player moves on its own thread and the window hears of it a message later, so a pump can
     *  come between (here the seek runs inside a message on the loop, whose own pump follows before the window's state
     *  message). Once the new chain is open, RIGHT's shadow under the bar — what the chain's end wrote, which the new chain
     *  plays over — holds the seek's place, not the card's paint at the song's start nor the place before the seek. */
    @Test
    fun aChainRestartInMusicModeWritesTheCurrentPlaceUnderTheLane(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.menuPick("Music Mode")
            r.until("Music Mode") { r.shell.exclusiveMode && r.shell.isQuiescent() }
            r.until("Music Mode's chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null && r.laneTagIn(Arm.LEFT) != null &&
                r.onLoop { r.shell.comp.laneHold.isNotEmpty() } }
            val (barL, bar) = r.onLoop { r.shell.comp.laneHold.first() }
            val y = bar.y + bar.h / 2
            val nominalX = (barL.x + bar.x) / 2
            fun truthLit() = r.onLoop { (0 until bar.w).takeWhile { Pack.level(r.shell.comp.composed[nominalX + it, y]) == 6 }.count() }
            val shadow0 = r.sim.right.shadow.copyOf()
            val truth0 = truthLit()
            delay(2_600)                                         // a 40-s song: ≈ bar.w / 40 px a second, two time lines
            val shadow1 = r.sim.right.shadow.copyOf()
            for (x in bar.x until bar.right) assertEquals(lv(shadow0, x, y), lv(shadow1, x, y), "no write into the lane at x $x while it is held")
            val truth1 = truthLit()
            assertTrue(truth1 >= truth0 + bar.w / 40, "the window's own bar moved on with the time line: $truth0 px lit, $truth1 px 2.6 s on (bar ${bar.w} px)")
            val tagBefore = r.laneTagIn(Arm.RIGHT)
            val chainsBefore = r.notes("lane").count { "a chain starts" in it }
            r.onLoop { r.player.seekTo(30_000) }
            r.until("a new chain after the seek", maxMs = 3_000) { r.notes("lane").count { "a chain starts" in it } > chainsBefore &&
                r.laneTagIn(Arm.RIGHT).let { it != null && it != tagBefore } }
            val under = litRun(r.sim.right.shadow.copyOf(), bar.x, bar.w, y, 6)
            assertTrue(under >= bar.w * 7 / 10, "under the new chain RIGHT's shadow holds the seek's place: $under px lit of ${bar.w} " +
                "(0:30 of 0:40 is ${bar.w * 3 / 4}; before the seek ${truth1}) — lane ${r.notes("lane").takeLast(4)}")
        } finally { r.stop() }
    }

    /** A stall while the song plays (the second review's finding 3, `MM-decisions.md` "Stage L, review pass 2" item 3): the
     *  PC stops giving the stream data, so the place stands while the state stays PLAYING. The chain ends once and none
     *  starts over 5 s of it (each start is a release frame and a PLAY); when the place moves again the chain starts. */
    @Test
    fun aStallWhilePlayingStartsNoChainUntilThePlaceMoves(): Unit = runBlocking {
        val r = Rig(stage = 5)
        try {
            r.up()
            r.until("a chain open on the glasses") { r.laneTagIn(Arm.RIGHT) != null }
            delay(1_200)
            val ends0 = r.notes("lane").count { "the chain ends" in it }
            r.player.pcStopsServing()
            assertEquals(PlayState.PLAYING, r.player.state.play, "the song still plays")
            delay(1_500)                                         // a pump has seen the place stand (the time line each second)
            val starts0 = r.notes("lane").count { "a chain starts" in it }
            delay(5_000)
            val starts = r.notes("lane").count { "a chain starts" in it } - starts0
            val ends = r.notes("lane").count { "the chain ends" in it } - ends0
            assertEquals(0, starts, "no chain starts over 5 s of the stall — lane ${r.notes("lane").takeLast(6)}")
            assertTrue(ends <= 1, "the chain ended once at most: $ends — lane ${r.notes("lane").takeLast(6)}")
            assertFalse(r.onLoop { r.shell.lanesOpenForTest }, "no chain open while the place stands")
            r.player.pcServes = true                             // data again: the place moves
            r.until("a chain again once the place moves", maxMs = 3_000) { r.notes("lane").count { "a chain starts" in it } > starts0 && r.laneTagIn(Arm.RIGHT) != null }
            // once: not a chain on the zero before the stall and a second at the next paint (the time line's each second)
            val after = r.notes("lane").count { "a chain starts" in it }
            delay(2_500)
            assertEquals(after, r.notes("lane").count { "a chain starts" in it }, "one start when the place moves — lane ${r.notes("lane").takeLast(6)}")
        } finally { r.stop() }
    }

    /** Stage 4 (no EFFECTS): nothing of the lanes — not in force, no lane tag ever, Music's blocks as before. */
    @Test
    fun belowStage5NothingOfTheLanesRuns(): Unit = runBlocking {
        val r = Rig(stage = 4)
        try {
            r.up()
            delay(1_500)
            assertFalse(r.onLoop { r.shell.services.lanesInForce() }, "stage 4: not in force")
            assertEquals(null, r.laneTagIn(Arm.RIGHT), "no lane tag")
            assertTrue(r.onLoop { r.shell.comp.laneHold.isEmpty() && !r.shell.lanesOpenForTest }, "nothing held")
            assertTrue(r.notes("lane").isEmpty(), "nothing said: ${r.notes("lane")}")
        } finally { r.stop() }
    }
}
