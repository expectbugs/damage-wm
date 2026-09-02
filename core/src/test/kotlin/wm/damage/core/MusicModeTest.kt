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
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.music.Mode
import wm.damage.core.windows.music.MusicWindow
import wm.damage.core.windows.music.PlayState
import wm.damage.core.windows.music.SimMusicPlayer
import wm.damage.core.windows.music.TrackMeta
import wm.damage.core.windows.music.TrackRef
import wm.damage.core.wire.EvenHubMsg

/**
 * MUSIC MODE (MUSIC.md §8.3, DESIGN.md §4.9) — M3: the shell's EXCLUSIVE
 * mode through the Music menu: the swallow test (tap / scroll / long-press
 * ignored, the chord cannot fire, double-tap exits), notices still show
 * in the small form and auto-dismiss, the mode persists and restores with
 * the window, one rect per surface on a delta paint, ink under budget at
 * 288 and 480, and the surfaces' per-height layout.
 */
class MusicModeTest {

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(20)
        assertTrue(cond(), "did not converge: $what")
    }

    private class Lib : wm.damage.core.windows.music.MusicLibrary {
        val tracks = listOf(
            TrackMeta(1, "Time", "Pink Floyd", "Dark Side", 413_000, 4, 1, 1973, listOf("rock"), hasLyrics = true),
            TrackMeta(2, "Money", "Pink Floyd", "Dark Side", 382_000, 6, 1, 1973, listOf("rock")),
            TrackMeta(3, "Dragula", "Rob Zombie", "Hellbilly", 222_000, 3, 1, 1998, listOf("metal")),
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
        override fun lyrics(trackId: Int) = if (trackId == 1) wm.damage.core.windows.music.Lyrics("lrclib", "[00:01.00] one\n[00:05.00] two\n[00:09.00] three\n[00:13.00] four") else null
        override fun searchLyrics(trackId: Int, query: String) = emptyList<wm.damage.core.windows.music.Lyrics>()
        override fun setLyrics(trackId: Int, choice: wm.damage.core.windows.music.Lyrics) {}
        override fun art(trackId: Int, px: Int): ByteArray? = ByteArray(px * px / 2) { 0x77 }
        override fun viz(trackId: Int): wm.damage.core.windows.music.VizData? {
            val bands = 24; val frames = 400
            val f = ByteArray((frames * bands + 1) / 2) { (0x86).toByte() }
            return wm.damage.core.windows.music.VizData(20, bands, frames, f, 1000, ByteArray(500) { 0x77 }, intArrayOf(0, 500, 1000))
        }
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

    private class Rig(val tmp: java.nio.file.Path, height: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        var skew = 0L
        val now: () -> Long = { System.currentTimeMillis() + skew }
        val lib = Lib()
        val player = SimMusicPlayer(lib, now)
        val win = MusicWindow(FakeText(), lib, player, scope, clock = now)
        init {
            store.put("shell.settings", ShellSettings(heightMode = height).toJson())
            shell.register(win)
        }
        suspend fun stop() { shell.stop(); scope.cancel() }
        fun g(t: Int) = shell.postGesture(t)
        fun ink(): Double = Pack.inkFraction(shell.comp.composed)
    }

    private fun run(height: Int): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-music-mode-$height")
        val r = Rig(tmp, height)
        try {
            r.shell.start()
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("music open") { r.shell.currentWindowId() == "music" }
            // the Size row pins the window's height (every window at all four sizes)
            r.shell.services.runOnShell { r.win.appSettings().first { it.name == "Size" }.apply("$height") }
            r.shell.services.runOnShell { r.win.appSettings().first { it.name == "Visualizer" }.apply("Bars"); r.win.appSettings().first { it.name == "Music Mode · visualizer" }.apply("on"); r.win.appSettings().first { it.name == "Music Mode · queue peek" }.apply("on") }
            r.player.playQueue(r.lib.tracks.map { it.ref() }, 0, Mode.QUEUE, "test")
            awaitTrue("playing") { r.player.state.play == PlayState.PLAYING }
            // the Music menu → Music Mode (row 13)
            awaitTrue("quiet") { r.shell.isQuiescent() }
            val rows = r.player.state.queue.size + 1
            val cursor = (r.win.saveState()["stack"] as kotlinx.serialization.json.JsonArray)[0].let { it as kotlinx.serialization.json.JsonObject }["cursor"]
                .let { (it as kotlinx.serialization.json.JsonPrimitive).content.toInt() }
            repeat((rows - 1 - cursor).mod(rows)) { r.g(EvenHubMsg.EV_SCROLL_BOTTOM) }
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("the music menu") { r.shell.menuIsOpen && r.shell.menuTitle == "music" }
            repeat(13) { r.g(EvenHubMsg.EV_SCROLL_BOTTOM) }
            r.g(EvenHubMsg.EV_CLICK)
            awaitTrue("exclusive") { r.shell.exclusiveMode }
            awaitTrue("quiet 2") { r.shell.isQuiescent() }
            val ink = r.ink()
            assertTrue(ink in 0.005..0.30, "Music Mode ink at $height was ${"%.1f".format(ink * 100)}%")
            assertEquals("music", r.shell.currentWindowId())
            // the swallow test: tap, scroll, a long-press and its release change nothing; the chord cannot fire
            val before = r.shell.comp.composed.copy()
            r.g(EvenHubMsg.EV_CLICK); r.g(EvenHubMsg.EV_SCROLL_BOTTOM); r.g(EvenHubMsg.EV_SCROLL_TOP)
            r.g(EvenHubMsg.EV_RING_LONG_PRESS); r.g(EvenHubMsg.EV_RING_LONG_PRESS_RELEASE)
            awaitTrue("quiet 3") { r.shell.isQuiescent() }
            assertTrue(r.shell.exclusiveMode); assertFalse(r.shell.menuIsOpen)
            assertTrue(before.regionEquals(r.shell.comp.composed, Rect(0, 0, before.w, before.h)), "swallowed input repainted nothing")
            r.g(EvenHubMsg.EV_RING_LONG_PRESS); r.g(EvenHubMsg.EV_DOUBLE_CLICK)     // the chord: exits instead of opening the wheel
            awaitTrue("double-tap after a long-press exits, no wheel") { !r.shell.exclusiveMode && r.shell.currentWindowId() == "music" }
            // re-enter through the deep link; a notice shows in the small form and auto-dismisses
            r.shell.services.runOnShell { r.win.open("mode:music") }
            awaitTrue("exclusive again") { r.shell.exclusiveMode }
            r.shell.postNotice(wm.damage.core.shell.Notifications.Notice("SMS · TEST", "t1", "hello", "14:32"))
            awaitTrue("the notice shows over Music Mode") { r.shell.notifications.active }
            awaitTrue("and auto-dismisses", 12_000) { !r.shell.notifications.active }
            assertTrue(r.shell.exclusiveMode)
            // a delta paint: a track change repaints ≤ one rect per surface, all on the grid, inside the panel
            val g = Gray8(Geometry.PANEL_W, Geometry.PANEL_H)
            val safe = r.shell.comp.composed.let { Rect(0, 0, it.w, height) }
            awaitTrue("quiet 4") { r.shell.isQuiescent() }
            val full = r.win.paintExclusive(g, safe, full = true)        // resets every surface's key
            assertEquals(listOf(safe), full)
            assertTrue(r.win.paintExclusive(g, safe, full = false).isEmpty(), "nothing moved: no rect")
            r.skew += 30_000                                              // 30 s on: the card's 5 % bucket moved
            val delta = r.win.paintExclusive(g, safe, full = false)
            assertTrue(delta.size in 1..6, "delta rects ${delta.size}")
            for (rect in delta) {
                assertTrue(Geometry.checkRect(rect).isEmpty(), "rect $rect: ${Geometry.checkRect(rect)}")
                assertTrue(safe.contains(rect), "$rect inside $safe")
            }
            // persists: a restarted shell comes back in Music Mode on the same window
            r.shell.stop()
            val r2 = Rig(tmp, height)
            r2.shell.start()
            awaitTrue("restored exclusive") { r2.shell.exclusiveMode && r2.shell.currentWindowId() == "music" }
            r2.g(EvenHubMsg.EV_DOUBLE_CLICK)
            awaitTrue("exit after restore") { !r2.shell.exclusiveMode && r2.shell.currentWindowId() == "music" }
            r2.stop()
            r.scope.cancel()
        } catch (e: Throwable) {
            r.stop(); throw e
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun musicModeAt480() = run(480)
    @Test fun musicModeAt288() = run(288)
}
