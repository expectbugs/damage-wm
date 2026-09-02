package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.music.Backend
import wm.damage.core.windows.music.LyricsSync
import wm.damage.core.windows.music.MirrorMusicPlayer
import wm.damage.core.windows.music.Mode
import wm.damage.core.windows.music.MusicPlayer
import wm.damage.core.windows.music.MusicWindow
import wm.damage.core.windows.music.PlayState
import wm.damage.core.windows.music.PlayerEvent
import wm.damage.core.windows.music.QueueEngine
import wm.damage.core.windows.music.SimMusicPlayer
import wm.damage.core.windows.music.Sleep
import wm.damage.core.windows.music.TrackRef
import wm.damage.core.wire.EvenHubMsg

/**
 * MUSIC (MUSIC.md §10) — M2: the pure queue engine, the player core over
 * the in-memory sink (transport rules, the low-water fill, sleep, boost
 * reset, the hold-my-volume classification with its pacing, the Spotify
 * switch/switchback, restore never auto-plays), the LRC parser + scheduler
 * math, the window grammar at 288 and 480 (the card, the row menu, the
 * wrap-end menu, browse → artist → play, playlists, the keyboard asks,
 * lyrics, confirms, deep links), persistence + the continuity test, and
 * the desktop mirror's loud refusals.
 */
class MusicWindowTest {

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(20)
        assertTrue(cond(), "did not converge: $what")
    }

    private fun t(id: Int, artist: String = "A", dur: Int = 200_000) = TrackRef(id, "T$id", artist, "Al", dur)

    // =========================================================== the queue engine
    @Test
    fun queueEngineModesIdentityAndFill() {
        val q = QueueEngine(java.util.Random(1))
        val tracks = (1..6).map { t(it, if (it % 2 == 0) "A" else "B") }
        q.set(tracks, 2, Mode.QUEUE, "six")
        assertEquals(2, q.index); assertEquals(3, q.current!!.track.id); assertEquals(6, q.size)
        val qids = q.entries.map { it.qid }
        assertEquals(qids.toSet().size, 6, "qids are unique")
        // shuffle keeps the current entry first
        q.set(tracks, 2, Mode.SHUFFLE, "six")
        assertEquals(0, q.index); assertEquals(3, q.current!!.track.id)
        assertEquals(tracks.map { it.id }.toSet(), q.ids().toSet())
        // next/prev + bounds
        assertTrue(q.next()); assertEquals(1, q.index)
        assertTrue(q.prev()); assertFalse(q.prev())
        // remove never the current; move keeps the current's identity
        val cur = q.current!!
        assertFalse(q.remove(cur.qid))
        val second = q.entries[1]
        assertTrue(q.remove(second.qid)); assertEquals(5, q.size); assertEquals(cur.qid, q.current!!.qid)
        val last = q.entries.last()
        assertTrue(q.move(last.qid, -10)); assertEquals(last.qid, q.entries[0].qid); assertEquals(cur.qid, q.current!!.qid); assertEquals(1, q.index)
        assertTrue(q.playFrom(last.qid)); assertEquals(0, q.index)
        assertFalse(q.playFrom(999L))
        // "play next" from BEFORE the current is one row less than from after (the removal shifts the current)
        q.set((1..5).map { t(it) }, 2, Mode.QUEUE, "pn")
        val a = q.entries[0]; val e5 = q.entries[4]
        assertTrue(q.move(a.qid, 2 - 0)); assertEquals(listOf(2, 3, 1, 4, 5), q.ids()); assertEquals(1, q.index)
        assertTrue(q.move(e5.qid, q.index + 1 - 4)); assertEquals(listOf(2, 3, 5, 1, 4), q.ids()); assertEquals(1, q.index)
        q.set(tracks, 2, Mode.SHUFFLE, "six"); q.remove(q.entries[1].qid); q.move(q.entries.last().qid, -10); q.playFrom(q.entries[0].qid)
        // insert next lands after the current
        val fresh = q.insertNext(listOf(t(7), t(8)))
        assertEquals(fresh.map { it.qid }, q.entries.subList(1, 3).map { it.qid })
        // the fill request
        q.set(tracks, 5, Mode.QUEUE, "x"); assertFalse(q.needsFill())
        q.setMode(Mode.RADIO); assertTrue(q.needsFill()); assertEquals(listOf(2, 3, 4, 5, 6), q.seedIds())
        q.setMode(Mode.SHUFFLE)     // reshuffles the remainder only (none here) and stays consistent
        assertEquals(6, q.current!!.track.id)
        // json round trip keeps qids, index, mode, label
        val j = q.toJson()
        val q2 = QueueEngine(); q2.fromJson(j)
        assertEquals(q.entries.map { it.qid }, q2.entries.map { it.qid }); assertEquals(q.index, q2.index); assertEquals(Mode.SHUFFLE, q2.mode); assertEquals("x", q2.label)
        val minted = q2.append(listOf(t(9)))
        assertTrue(minted[0].qid > q.entries.maxOf { it.qid })
        q.clear(); assertTrue(q.isEmpty); assertFalse(q.needsFill())
    }

    // =========================================================== the player core over the sim sink
    private class Lib : wm.damage.core.windows.music.MusicLibrary {
        val played = ArrayList<String>()
        var similarAnswer: List<TrackRef> = listOf(TrackRef(50, "N50"), TrackRef(51, "N51"))
        var fail = false
        override fun stateLine() = ""
        override fun catalog() = wm.damage.core.windows.music.Catalog.EMPTY
        override fun refreshCatalog() {}
        override fun search(q: String) = emptyList<TrackRef>()
        override fun ask(request: String) = wm.damage.core.windows.music.ResolvedQueue()
        override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int): List<TrackRef> { if (fail) throw IllegalStateException("qdrant down"); return similarAnswer.filter { it.id !in exclude } }
        override fun randomLibrary(n: Int, exclude: List<Int>) = listOf(TrackRef(60, "R60"), TrackRef(61, "R61")).filter { it.id !in exclude }
        override fun playlists() = emptyList<wm.damage.core.windows.music.Playlist>()
        override fun playlistTracks(id: Int) = emptyList<TrackRef>()
        override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean) = wm.damage.core.windows.music.Playlist(1, name)
        override fun renamePlaylist(id: Int, name: String) {}
        override fun deletePlaylist(id: Int) {}
        override fun setPlaylistTracks(id: Int, trackIds: List<Int>) {}
        override fun lyrics(trackId: Int) = null
        override fun searchLyrics(trackId: Int, query: String) = emptyList<wm.damage.core.windows.music.Lyrics>()
        override fun setLyrics(trackId: Int, choice: wm.damage.core.windows.music.Lyrics) {}
        override fun art(trackId: Int, px: Int): ByteArray? = null
        override fun viz(trackId: Int) = null
        override fun ytSearch(q: String) = emptyList<wm.damage.core.windows.music.YtResult>()
        override fun ytGrab(id: String) = "j"
        override fun ytStatus(job: String) = wm.damage.core.windows.music.YtJob(job)
        override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) { played.add("$trackId:$completed:$skipped") }
        override fun streamUrl(trackId: Int, profile: wm.damage.core.windows.music.AudioProfile) = "sim://$trackId?${profile.name}"
        override fun pretranscode(profile: wm.damage.core.windows.music.AudioProfile) = ""
        override fun rescan() = ""
        override fun addListener(l: wm.damage.core.windows.music.MusicLibrary.Listener) {}
        override fun removeListener(l: wm.damage.core.windows.music.MusicLibrary.Listener) {}
        override fun setFocused(focused: Boolean, paceMs: Long) {}
    }

    private class Events : MusicPlayer.Listener {
        val events = ArrayList<PlayerEvent>()
        override fun event(e: PlayerEvent) { events.add(e) }
    }

    @Test
    fun playerCoreTransportFillSleepBoostHoldAndSpotify() {
        var now = 1_000_000L
        val lib = Lib()
        val p = SimMusicPlayer(lib, { now }, spotify = "Spotify Song")
        val ev = Events(); p.addListener(ev)
        assertEquals(PlayState.STOPPED, p.state.play)
        // a queue in Shuffle keeps its start first; play opens + prefetches the next 3
        p.playQueue((1..5).map { t(it, "A$it", 100_000) }, 0, Mode.SHUFFLE, "five")
        assertEquals(PlayState.PLAYING, p.state.play); assertEquals(1, p.state.track!!.id)
        assertEquals(3, p.prefetched.size)
        assertTrue(ev.events.any { it is PlayerEvent.TrackChange })
        // position runs on the clock; pause freezes; seek moves
        now += 10_000; assertEquals(10_000L, p.positionMs())
        p.pause(); now += 5_000; assertEquals(10_000L, p.positionMs()); assertEquals(PlayState.PAUSED, p.state.play)
        p.play(); now += 1_000; assertEquals(11_000L, p.positionMs())
        p.seekBy(-100_000); assertEquals(0L, p.positionMs())
        // prev ≥ 3 s in restarts; at the head it restarts; next advances; a skip before 80 % is 'skipped'
        now += 5_000; p.prev(); assertEquals(0L, p.positionMs()); assertEquals(0, p.state.index)
        p.next(); assertEquals(1, p.state.index)
        assertTrue(lib.played.last().endsWith(":false:true"), lib.played.toString())
        now += 2_000; p.prev(); assertEquals(0, p.state.index)          // < 3 s in: back a track
        // the track ends on its own: completed, not skipped
        now += 100_001; p.advance(); assertEquals(1, p.state.index)
        assertTrue(lib.played.last() == "1:true:false", lib.played.toString())
        // boost dies with the track; a boost at max volume raises the loud notice
        p.setVolume(100, "test"); p.setBoost(200); assertEquals(200, p.state.boost); assertTrue(ev.events.any { it is PlayerEvent.BoostLoud })
        p.next(); assertEquals(100, p.state.boost); assertTrue(ev.events.any { it is PlayerEvent.BoostOff })
        // hold my volume: a large instant drop is the limiter → re-set; single steps are the user
        p.setVolume(80, "settings"); assertEquals(80, p.sinkVolume)
        p.observeVolume(75, "user-button"); assertEquals(75, p.state.volume)
        p.observeVolume(40, "unknown"); assertEquals(75, p.state.volume); assertEquals(75, p.sinkVolume)
        assertTrue(ev.events.any { it is PlayerEvent.LimiterUndone && it.restoredTo == 75 })
        // pacing: the 4th re-set within 10 minutes is left alone, said loudly
        p.observeVolume(40); p.observeVolume(40)
        p.observeVolume(40); assertEquals(40, p.state.volume)
        assertTrue(ev.events.any { it is PlayerEvent.LimiterKeeps })
        // the listener's notice restores too (after the window moves on)
        now += 11 * 60_000; p.setVolume(70, "settings"); p.observeVolume(30); p.onLimiterNotice("Volume lowered to a safer level"); assertEquals(70, p.state.volume)
        p.setHoldVolume(false); p.observeVolume(20); assertEquals(20, p.state.volume)
        // the radio low-water fill appends neighbours when ≤ 2 remain; the queue never ends while it can fill
        p.playQueue((1..3).map { t(it, "A", 50_000) }, 0, Mode.QUEUE, "three")
        p.setMode(Mode.RADIO)
        assertEquals(5, p.state.queue.size)          // appended at once (1 unplayed left)
        lib.fail = true
        p.playQueue((1..2).map { t(it, "A", 50_000) }, 1, Mode.RADIO, "two")
        p.next()                                       // no fill possible → the queue ends honestly
        assertEquals(PlayState.STOPPED, p.state.play)
        assertTrue(ev.events.any { it is PlayerEvent.QueueEnd })
        lib.fail = false
        // sleep: a timer deadline pauses on a tick; after-this-track stops at the end
        p.playQueue((1..3).map { t(it, "A", 50_000) }, 0, Mode.QUEUE, "s")
        p.setSleep(Sleep(Sleep.Kind.TIMER, now + 1_000))
        now += 2_000; p.advance()
        assertEquals(PlayState.PAUSED, p.state.play); assertEquals(Sleep.Kind.OFF, p.state.sleep.kind)
        assertTrue(ev.events.any { it is PlayerEvent.SleepEnded })
        p.play(); p.setSleep(Sleep(Sleep.Kind.AFTER_TRACK))
        now += 60_000; p.advance()
        assertEquals(PlayState.PAUSED, p.state.play); assertEquals(1, p.state.index)
        // a route loss after the sink already paused itself still says so (the buds-gone notice)
        p.play(); p.pause(); val n0 = ev.events.size
        p.onRouteLost("earbuds gone"); assertTrue(ev.events.drop(n0).any { it is PlayerEvent.RouteLost })
        p.stop(); p.onRouteLost("earbuds gone"); assertFalse(ev.events.drop(n0 + 1).any { it is PlayerEvent.RouteLost })   // nothing loaded: silent by design
        // persist reads the cached snapshot (the saver is not the player's thread) and round-trips the queue
        p.playQueue((1..3).map { t(it, "A", 50_000) }, 1, Mode.QUEUE, "persist")
        val rec0 = p.persist()
        val q0 = QueueEngine(); q0.fromJson(rec0["engine"] as kotlinx.serialization.json.JsonObject)
        assertEquals(p.state.queue.map { it.qid }, q0.entries.map { it.qid }); assertEquals(1, q0.index)
        // outputs: a refused one is an error, never silently "set"
        p.refuseOutput = "speaker"; p.setOutput("speaker"); assertEquals("auto", p.state.output); assertTrue(ev.events.last() is PlayerEvent.Error)
        p.setOutput("buds"); assertEquals("buds", p.state.output)
        // Spotify: a deliberate switch, then the deliberate switchback; the automatic fallback marks itself
        p.setBackend(Backend.SPOTIFY); assertEquals(Backend.SPOTIFY, p.state.backend); assertFalse(p.state.spotifyAuto); assertEquals("Spotify Song", p.state.spotify!!.title)
        p.next(); assertTrue(p.cmds.contains("spotify:next"))
        p.backToPc(); assertEquals(Backend.LIBRARY, p.state.backend)
        p.pretendPcDown(); assertEquals(Backend.SPOTIFY, p.state.backend); assertTrue(p.state.spotifyAuto); assertFalse(p.state.pcLink.up)
        assertTrue(ev.events.any { it is PlayerEvent.BackendChanged && it.automatic })
        p.pretendPcUp(); assertTrue(p.state.pcLink.up); assertEquals(Backend.SPOTIFY, p.state.backend)   // switchback is DELIBERATE
        p.backToPc(); assertEquals(Backend.LIBRARY, p.state.backend)
        // persist → restore never auto-plays, keeps the queue, index and settings
        val rec = p.persist()
        assertNotEquals(PlayState.STOPPED, p.state.play)
        assertEquals(p.state.play.name, (rec["play"] as kotlinx.serialization.json.JsonPrimitive).content)   // the truth travels (never forced to STOPPED); restore never plays
        val p2 = SimMusicPlayer(Lib(), { now })
        p2.setVolume(30, "the phone's real level")
        p2.restore(rec)
        assertEquals(PlayState.STOPPED, p2.state.play)
        assertEquals(30, p2.state.volume, "a record's volume never replaces the sink's level")
        // our own echo clears the marker even when the level already matches (no stale held level)
        p2.setHoldVolume(true)   // p turned it off above; the record carried that
        p2.setVolume(60, "settings"); p2.observeVolume(60, "broadcast"); p2.observeVolume(60, "user-button"); p2.observeVolume(35, "unknown")
        assertEquals(60, p2.state.volume, "a large drop from the held level is still the limiter")
        assertEquals(p.state.queue.map { it.qid }, p2.state.queue.map { it.qid })
        assertEquals(p.state.index, p2.state.index)
        assertEquals("buds", p2.state.output)
        assertTrue(p2.opened.isEmpty(), "a restore opens nothing")
        p2.toggle(); assertEquals(PlayState.PLAYING, p2.state.play)   // the bud tap / Play starts the staged queue
        // a fallback with the setting off is an error, not a switch
        val p3 = SimMusicPlayer(Lib(), { now }, spotify = "S"); val e3 = Events(); p3.addListener(e3)
        p3.setSpotifyFallback(false); p3.pretendPcDown(); assertEquals(Backend.LIBRARY, p3.state.backend); assertTrue(e3.events.last() is PlayerEvent.Error)
        // an open that fails is loud and leaves the state honest
        val p4 = SimMusicPlayer(object : wm.damage.core.windows.music.MusicLibrary by Lib() {
            override fun streamUrl(trackId: Int, profile: wm.damage.core.windows.music.AudioProfile) = "sim://fail/$trackId"
        }, { now }); val e4 = Events(); p4.addListener(e4)
        p4.playQueue(listOf(TrackRef(1, "fail me")), 0, Mode.QUEUE, "x")
        assertTrue(e4.events.any { it is PlayerEvent.Error }, e4.events.toString())
    }

    // =========================================================== lyrics math
    @Test
    fun lrcParsingAndSchedulerMath() {
        val p = LyricsSync.parse("[ar:X]\n[00:01.50][00:20.00] twice\n[00:05.00] <00:05.00> hello <00:05.50> world\n\n[01:00.1] late\nno stamp here\n")
        assertEquals(listOf(1_500L, 5_000L, 20_000L, 60_100L), p.lines.map { it.tMs })
        assertTrue(p.enhanced)
        assertEquals("hello world", p.lines[1].text)
        assertEquals(2, p.lines[1].words.size); assertEquals(5_500L, p.lines[1].words[1].tMs)
        assertEquals("twice", p.lines[2].text)
        assertEquals(-1, LyricsSync.lineAt(p.lines, 0)); assertEquals(0, LyricsSync.lineAt(p.lines, 1_500)); assertEquals(1, LyricsSync.lineAt(p.lines, 19_999)); assertEquals(3, LyricsSync.lineAt(p.lines, 999_999))
        assertEquals(1, LyricsSync.wordAt(p.lines[1], 5_600))
        // a positive device offset: the sound lags the position, so the heard line is earlier
        assertEquals(4_800L, LyricsSync.heardPos(5_000, 200))
        // next flush: line at 5 000 heard at 5 200 (offset 200) — from position 4 000 the flush is due in 5200-4000-70 = 1130 ms
        assertEquals(1_130L, LyricsSync.nextFlushDelay(p.lines, 4_000, 200, 70))
        assertEquals(0L, LyricsSync.nextFlushDelay(p.lines, 5_150, 200, 70))       // never in the past
        assertNull(LyricsSync.nextFlushDelay(p.lines, 70_000, 0, 70))               // nothing follows
        assertEquals(0..2, LyricsSync.window(p.lines, 0, 2, 3)); assertEquals(1..3, LyricsSync.window(p.lines, 3, 2, 3))
        assertEquals(2, LyricsSync.pages("a\nb\nc\nd\ne", 2).size + 0 - 1)
        assertTrue(LyricsSync.parse("just text").isEmpty)
    }

    // =========================================================== the window
    private class Rig(tmp: Path, val height: Int = 480, val lib: wm.damage.core.windows.music.MusicLibrary = TestLib(), player: MusicPlayer? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        var skew = 0L
        val now: () -> Long = { System.currentTimeMillis() + skew }
        val player: MusicPlayer = player ?: SimMusicPlayer(lib, now)
        val win = MusicWindow(FakeText(), lib, this.player, scope, mirror = player is MirrorMusicPlayer, clock = now)
        init {
            if (height != 480) store.put("shell.settings", ShellSettings(heightMode = height).toJson())
            shell.register(win)
        }
        suspend fun start() { shell.start(); shell.postGesture(EvenHubMsg.EV_CLICK) }   // Main row 0 = Music
        suspend fun stop() { shell.stop(); scope.cancel() }
        fun tap() = shell.postGesture(EvenHubMsg.EV_CLICK)
        fun back() = shell.postGesture(EvenHubMsg.EV_DOUBLE_CLICK)
        fun down() = shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        fun up() = shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
        /** Scroll the QUEUE cursor onto its wrap-end menu row, wherever it rests. */
        suspend fun toMenuRow() {
            while (!shell.isQuiescent()) delay(20)
            val st = win.saveState()["stack"]!!.jsonArray[0].jsonObject
            val cursor = st["cursor"]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0
            val rows = maxOf(1, player.state.queue.size) + 1
            repeat((rows - 1 - cursor).mod(rows)) { down() }
        }
        suspend fun backToMain() {
            var n = 0
            while (shell.currentWindowId() != null && n++ < 10) { back(); delay(150) }
        }
    }

    /** A small catalog-backed library for the window (the desktop's ScriptedMusic shape, in core). */
    private class TestLib : wm.damage.core.windows.music.MusicLibrary {
        val ops = ArrayList<String>()
        val tracks = listOf(
            wm.damage.core.windows.music.TrackMeta(1, "Time", "Pink Floyd", "Dark Side", 413_000, 4, 1, 1973, listOf("rock"), listOf("reflective"), hasLyrics = true, folder = "Library/PF"),
            wm.damage.core.windows.music.TrackMeta(2, "Money", "Pink Floyd", "Dark Side", 382_000, 6, 1, 1973, listOf("rock"), emptyList(), folder = "Library/PF"),
            wm.damage.core.windows.music.TrackMeta(3, "Dragula", "Rob Zombie", "Hellbilly", 222_000, 3, 1, 1998, listOf("metal"), listOf("aggressive"), folder = "Library/RZ"),
            wm.damage.core.windows.music.TrackMeta(4, "Superbeast", "Rob Zombie", "Hellbilly", 220_000, 2, 1, 1998, listOf("metal"), emptyList(), folder = "Library/RZ"),
        )
        @Volatile var cat = wm.damage.core.windows.music.Catalog("c1", 1, tracks,
            listOf(wm.damage.core.windows.music.Artist("Pink Floyd", 2, 1), wm.damage.core.windows.music.Artist("Rob Zombie", 2, 1)),
            listOf(wm.damage.core.windows.music.Album("Dark Side", "Pink Floyd", 2, 1973), wm.damage.core.windows.music.Album("Hellbilly", "Rob Zombie", 2, 1998)),
            listOf(wm.damage.core.windows.music.Playlist(7, "Hard Stuff", "manual", false, 2), wm.damage.core.windows.music.Playlist(8, "Rules", "rule", true, 1)),
            listOf(wm.damage.core.windows.music.VocabTerm("rock", "genre", 2), wm.damage.core.windows.music.VocabTerm("metal", "genre", 2)), listOf(3, 1))
        val listeners = java.util.concurrent.CopyOnWriteArrayList<wm.damage.core.windows.music.MusicLibrary.Listener>()
        override fun stateLine() = ""
        override fun catalog() = cat
        override fun refreshCatalog() { ops.add("refresh") }
        override fun search(q: String) = tracks.filter { it.title.contains(q, true) }.map { it.ref() }
        override fun ask(request: String) = if (request.contains("metal").also { ops.add("ask:$request") }) wm.damage.core.windows.music.ResolvedQueue(listOf(tracks[2].ref(), tracks[3].ref()), "vocab", "metal", "lane vocab [metal]: 2 matched → 2 queued")
            else wm.damage.core.windows.music.ResolvedQueue(emptyList(), "empty", request, "no library match for \"$request\" (all lanes)")
        override fun similar(trackIds: List<Int>, exclude: List<Int>, n: Int) = tracks.map { it.ref() }.filter { it.id !in exclude }.take(n)
        override fun randomLibrary(n: Int, exclude: List<Int>) = tracks.map { it.ref() }.filter { it.id !in exclude }.take(n)
        override fun playlists() = cat.playlists
        override fun playlistTracks(id: Int) = when (id) { 7 -> listOf(tracks[2].ref(), tracks[3].ref()); 8 -> listOf(tracks[0].ref()); else -> throw IllegalStateException("no playlist $id") }
        override fun savePlaylist(name: String, trackIds: List<Int>, overwrite: Boolean): wm.damage.core.windows.music.Playlist { ops.add("save:$name:$trackIds:$overwrite"); return wm.damage.core.windows.music.Playlist(9, name, count = trackIds.size) }
        override fun renamePlaylist(id: Int, name: String) { ops.add("rename:$id:$name") }
        override fun deletePlaylist(id: Int) { ops.add("delete:$id") }
        override fun setPlaylistTracks(id: Int, trackIds: List<Int>) { if (id == 8) throw IllegalStateException("adaptive playlist"); ops.add("set:$id:$trackIds") }
        override fun lyrics(trackId: Int) = if (trackId == 1) wm.damage.core.windows.music.Lyrics("lrclib", "[00:01.00] one\n[00:05.00] two\n[00:09.00] three") else null
        override fun searchLyrics(trackId: Int, query: String) = listOf(wm.damage.core.windows.music.Lyrics("netease", "[00:01.00] x", null, "found · netease"))
        override fun setLyrics(trackId: Int, choice: wm.damage.core.windows.music.Lyrics) { ops.add("lyrics.set:$trackId:${choice.source}") }
        override fun art(trackId: Int, px: Int): ByteArray? = if (trackId == 1) ByteArray(px * px / 2) { 0x99.toByte() } else null
        override fun viz(trackId: Int) = null
        override fun ytSearch(q: String) = listOf(wm.damage.core.windows.music.YtResult("v1", "$q video", "chan", 180, "u"))
        override fun ytGrab(id: String): String { ops.add("grab:$id"); return "j1" }
        override fun ytStatus(job: String) = wm.damage.core.windows.music.YtJob(job, "grab", "done", 100, 4)
        override fun played(trackId: Int, startedMs: Long, endedMs: Long, completed: Boolean, skipped: Boolean) {}
        override fun streamUrl(trackId: Int, profile: wm.damage.core.windows.music.AudioProfile) = "sim://$trackId"
        override fun pretranscode(profile: wm.damage.core.windows.music.AudioProfile) = "building"
        override fun rescan() = "rescan: 4 files"
        override fun addListener(l: wm.damage.core.windows.music.MusicLibrary.Listener) { listeners.addIfAbsent(l) }
        override fun removeListener(l: wm.damage.core.windows.music.MusicLibrary.Listener) { listeners.remove(l) }
        override fun setFocused(focused: Boolean, paceMs: Long) { ops.add("focus:$focused") }
        fun grabDone() { for (l in listeners) l.ytJob(wm.damage.core.windows.music.YtJob("j1", "Grabbed", "done", 100, 4)) }
    }

    private fun grammar(height: Int): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-music-win-$height")
        val lib = TestLib()
        val r = Rig(tmp, height, lib)
        val player = r.player as SimMusicPlayer
        try {
            r.start()
            awaitTrue("music opens empty") { r.win.title() == "music" && r.shell.currentWindowId() == "music" }
            assertEquals("idle", r.win.summary().line)
            assertTrue(lib.ops.contains("focus:true"))
            // the empty row → Browse → Artists → Pink Floyd → All tracks → Play now
            r.tap(); awaitTrue("browse") { r.win.title() == "browse" }
            r.tap(); awaitTrue("artists") { r.win.title() == "artists" }
            r.tap(); awaitTrue("artist") { r.win.title() == "Pink Floyd" }
            r.tap(); awaitTrue("set menu") { r.shell.menuIsOpen && r.shell.menuTitle == "Pink Floyd" }
            r.tap()                                                   // Play now
            awaitTrue("playing") { player.state.play == PlayState.PLAYING && player.state.queue.size == 2 }
            assertEquals(1, player.state.track!!.id)
            awaitTrue("the summary follows the player") { r.win.summary().line.startsWith("playing · Time") }
            repeat(3) { r.back() }
            awaitTrue("back at the queue") { r.win.title().startsWith("queue 1/2") && r.win.levelDepth() == 1 }
            // the cursor rests on the current entry; the row menu opens with Pause first
            r.tap(); awaitTrue("entry menu") { r.shell.menuIsOpen && r.shell.menuTitle == "Time" }
            r.tap(); awaitTrue("paused") { player.state.play == PlayState.PAUSED }
            // the second row: Play from here
            r.down(); r.tap(); awaitTrue("row 2 menu") { r.shell.menuIsOpen && r.shell.menuTitle == "Money" }
            r.tap(); awaitTrue("play from here") { player.state.index == 1 && player.state.play == PlayState.PLAYING }
            // one up from the top wraps to the Music menu; Ask via the keyboard (a replica line commits the draft)
            r.toMenuRow(); r.tap()
            awaitTrue("music menu") { r.shell.menuIsOpen && r.shell.menuTitle == "music" }
            repeat(4) { r.down() }; r.tap()                            // Ask
            awaitTrue("keyboard") { r.shell.keyboardIsOpen && r.shell.keyboardTitle == "ask for music" }
            r.transport.injectText("hard metal")
            awaitTrue("the ask played the vocab lane") { player.state.label == "metal" && player.state.queue.size == 2 && player.state.track!!.id == 3 }
            assertTrue(lib.ops.contains("ask:hard metal"))
            // Lyrics: the current track has none → the honest line
            r.toMenuRow(); r.tap(); awaitTrue("menu again") { r.shell.menuIsOpen && r.shell.menuTitle == "music" }
            repeat(10) { r.down() }; r.tap()                           // Lyrics
            awaitTrue("lyrics level") { r.win.title() == "lyrics" && r.win.levelDepth() == 2 }
            r.back(); awaitTrue("back from lyrics") { r.win.levelDepth() == 1 }
            // Seek: rest on +10 s; tap seeks
            r.toMenuRow(); r.tap(); awaitTrue("menu 3") { r.shell.menuIsOpen && r.shell.menuTitle == "music" }
            repeat(11) { r.down() }; r.tap()                           // Seek
            awaitTrue("seek") { r.win.title() == "seek" }
            r.tap(); awaitTrue("sought +10 s") { player.positionMs() >= 10_000 }
            r.back()
            // Playlists → Hard Stuff → Play at random (a replace while playing CONFIRMS)
            r.toMenuRow(); r.tap(); awaitTrue("menu 4") { r.shell.menuIsOpen && r.shell.menuTitle == "music" }
            repeat(6) { r.down() }; r.tap()                            // Playlists
            awaitTrue("playlists") { r.win.title() == "playlists" }
            r.tap(); awaitTrue("playlist rows") { r.win.title() == "Hard Stuff" && r.win.saveState()["stack"]!!.jsonArray.size == 3 }
            awaitTrue("rows loaded") { r.shell.isQuiescent() }
            r.tap(); awaitTrue("replace confirm") { r.shell.menuIsOpen && r.shell.menuTitle == "replace the queue?" }
            r.down(); r.tap()
            awaitTrue("played at random") { player.state.label == "Hard Stuff" && player.state.mode == Mode.SHUFFLE }
            r.up(); r.tap(); awaitTrue("playlist menu") { r.shell.menuIsOpen && r.shell.menuTitle == "Hard Stuff" }
            r.back()
            // Save queue as playlist: an existing name asks TWICE
            r.back(); r.back(); awaitTrue("queue again") { r.win.levelDepth() == 1 }
            r.toMenuRow(); r.tap(); awaitTrue("menu 5") { r.shell.menuIsOpen && r.shell.menuTitle == "music" }
            repeat(12) { r.down() }; r.tap()                           // Save queue as playlist
            awaitTrue("name keyboard") { r.shell.keyboardIsOpen && r.shell.keyboardTitle == "playlist name" }
            r.transport.injectText("hard stuff")
            awaitTrue("first confirm") { r.shell.menuIsOpen && (r.shell.menuTitle ?: "").startsWith("'hard stuff' exists") }
            assertTrue(lib.ops.none { it.startsWith("save:") })
            r.down(); r.tap()
            awaitTrue("second confirm") { r.shell.menuIsOpen && (r.shell.menuTitle ?: "").startsWith("really replace") }
            r.down(); r.down(); r.tap()
            awaitTrue("saved over") { lib.ops.any { it.startsWith("save:hard stuff:[") && it.endsWith("]:true") } }
            // a YouTube result grab stages a confirm; the done push offers the track
            r.toMenuRow(); r.tap(); awaitTrue("menu 6") { r.shell.menuIsOpen && r.shell.menuTitle == "music" }
            repeat(5) { r.down() }; r.tap()                            // Browse
            awaitTrue("browse 2") { r.win.title() == "browse" }
            repeat(7) { r.down() }; r.tap()                            // YouTube
            awaitTrue("yt keyboard") { r.shell.keyboardIsOpen && r.shell.keyboardTitle == "search youtube" }
            r.transport.injectText("dragula live")
            awaitTrue("yt results") { r.win.title() == "youtube" && r.shell.isQuiescent() }
            r.tap(); awaitTrue("grab confirm") { r.shell.menuIsOpen && (r.shell.menuTitle ?: "").startsWith("grab '") }
            assertTrue(lib.ops.none { it.startsWith("grab:") })
            r.down(); r.tap(); awaitTrue("grab started") { lib.ops.contains("grab:v1") }
            lib.grabDone()
            awaitTrue("the finished grab is offered") { r.shell.menuIsOpen && (r.shell.menuTitle ?: "").startsWith("added ") }
            r.back()
            // deep links: t:<queued id> lands on the row; pl:<id> opens the playlist; a gone id refuses
            val links = java.util.concurrent.CopyOnWriteArrayList<String>()
            r.shell.services.runOnShell {
                for (t in listOf("t:3", "pl:7", "t:999", "pl:999")) links.add("$t=" + try { r.win.open(t) } catch (e: Throwable) { "threw ${e.message}" })
            }
            awaitTrue("deep links answered") { links.size == 4 }
            assertEquals(listOf("t:3=true", "pl:7=true", "t:999=false", "pl:999=false"), links.toList())
            awaitTrue("pl deep link (title was '${r.win.title()}', depth ${r.win.levelDepth()})") { r.win.title() == "Hard Stuff" }
            // the track-change notice fires only while the window is NOT on screen
            r.backToMain()
            awaitTrue("at Main") { r.shell.currentWindowId() == null }
            player.next()
            awaitTrue("track change notice") { r.shell.notifications.active && r.shell.notifications.current?.body?.startsWith("playing · ") == true }
            assertTrue(r.win.dirty)
            // the mirror rule: Music Mode is not available on this host → said, not silent (the exclusive shell lands with M3)
            r.stop()
        } catch (e: Throwable) {
            r.stop(); throw e
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test fun windowGrammarAt480() = grammar(480)
    @Test fun windowGrammarAt288() = grammar(288)

    @Test
    fun persistenceRoundTripAndContinuityKeepThePlayerRecordAndTheLevel(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-music-persist")
        val lib = TestLib()
        val r = Rig(tmp, 480, lib)
        val player = r.player as SimMusicPlayer
        try {
            r.start()
            awaitTrue("music opens") { r.shell.currentWindowId() == "music" }
            player.playQueue(lib.tracks.map { it.ref() }, 2, Mode.QUEUE, "all")
            awaitTrue("playing") { r.win.summary().line.startsWith("playing · Dragula") }
            r.tap(); awaitTrue("entry menu") { r.shell.menuIsOpen }
            r.down(); r.tap()                                          // Track info
            awaitTrue("info") { r.win.title() == "track" && r.win.levelDepth() == 2 }
            val saved = r.win.saveState()
            val sub = r.win.saveSubState()["player"]!!
            assertEquals("INFO", saved["stack"]!!.jsonArray[1].jsonObject["kind"]?.jsonPrimitive?.contentOrNull)
            assertEquals("3", saved["stack"]!!.jsonArray[1].jsonObject["key"]?.jsonPrimitive?.contentOrNull)
            r.stop()
            // a NEW shell over the same store: the level returns; the queue is STAGED (never auto-played)
            val r2 = Rig(tmp, 480, TestLib())
            r2.shell.start()
            awaitTrue("info restored") { r2.win.title() == "track" && r2.win.levelDepth() == 2 }
            val p2 = r2.player as SimMusicPlayer
            awaitTrue("queue restored, staged") { p2.state.queue.size == 4 && p2.state.index == 2 && p2.state.play == PlayState.STOPPED }
            awaitTrue("the summary reads the staged queue") { r2.win.summary().line.contains("queued") }
            r2.stop()
            // continuity (§16.4c): the records applied LIVE on a third shell converge to the same place
            val r3 = Rig(Files.createTempDirectory("damage-music-c"), 480, TestLib())
            r3.shell.start()
            awaitTrue("third shell up") { r3.shell.isQuiescent() }
            val stamp = System.currentTimeMillis() + 60_000
            r3.shell.postSync("window.music", saved, stamp)
            r3.shell.postSync("window.music.player", sub, stamp)
            awaitTrue("live records applied") { r3.win.levelDepth() == 2 && (r3.player as SimMusicPlayer).state.queue.size == 4 }
            assertEquals("track", r3.win.title())
            r3.stop()
            // the desktop MIRROR shows the record and refuses transport loudly
            val mirror = MirrorMusicPlayer()
            val r4 = Rig(Files.createTempDirectory("damage-music-m"), 480, TestLib(), mirror)
            r4.shell.start()
            awaitTrue("mirror up") { r4.shell.isQuiescent() }
            r4.shell.postSync("window.music.player", sub, stamp)
            awaitTrue("mirror shows the queue") { mirror.state.queue.size == 4 && mirror.state.index == 2 }
            assertEquals(sub, mirror.persist())                       // byte-equal: the store never re-stamps it
            r4.shell.postGesture(EvenHubMsg.EV_CLICK)                 // into Music
            awaitTrue("mirror window") { r4.shell.currentWindowId() == "music" }
            r4.tap(); awaitTrue("mirror menu") { r4.shell.menuIsOpen }
            r4.tap()                                                  // Pause → refused
            awaitTrue("refusal said") { r4.win.title() == "playback needs the phone" }
            assertTrue(r4.win.needs.contains(wm.damage.core.shell.DamageWindow.Need.HOST))
            r4.stop()
        } catch (e: Throwable) {
            r.stop(); throw e
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
