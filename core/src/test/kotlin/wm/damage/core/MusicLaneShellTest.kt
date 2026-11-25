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
        val ct = wm.damage.core.comp.CachedText(FakeText())
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

        suspend fun stop() { shell.stop(); scope.cancel(); tmp.toFile().deleteRecursively() }
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
            // the elapsed time went out each second (its own small flushes)
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
