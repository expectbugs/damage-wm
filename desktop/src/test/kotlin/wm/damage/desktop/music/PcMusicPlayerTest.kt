package wm.damage.desktop.music

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import wm.damage.core.app.Draft
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.MemoryRecord
import wm.damage.core.windows.music.Mode
import wm.damage.core.windows.music.MusicCore
import wm.damage.core.windows.music.PlayState
import wm.damage.core.windows.music.QueueEntry
import wm.damage.core.windows.music.TrackRef

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-4 K: the PC's player over lane P's sink against [FakeMpvServer] (no player program, no sound device): a
 * move to the PC opens the file at the place and writes `out` only on mpv's `playback-restart`; the program's exit is
 * a stop at its place, said; nothing opens at a binding.
 */
class PcMusicPlayerTest {
    private val dir: Path = Files.createTempDirectory("pcmp")
    private val server = FakeMpvServer(dir.resolve("s.sock"))
    private val ipcs = CopyOnWriteArrayList<MpvIpc>()
    @Volatile private var owner: MpvProcess.Owner? = null
    private val link = object : MpvLink {
        override fun ensure(owner: MpvProcess.Owner): MpvIpc {
            this@PcMusicPlayerTest.owner = owner
            return MpvIpc.connect(server.path, owner).also { ipcs.add(it) }
        }
    }
    private val track = TrackRef(1, "Tone", durMs = 200_000)
    private val source = LocalWindowSource(MusicCore(), MemoryRecord(MusicCore.ACTIONS))
    private val notices = CopyOnWriteArrayList<String>()

    @After fun tearDown() {
        for (i in ipcs) i.close()
        server.close()
        dir.toFile().deleteRecursively()
    }

    private fun now() = MusicCore.nowOf(source.state())
    private fun out() = MusicCore.outOf(source.state())

    @Test fun `a move to the PC writes out on playback-restart, and the program's end is a stop at its place`() {
        source.act(Draft(MusicCore.A_QUEUE, listOf(MusicCore.Q), MusicCore.queueJson(listOf(QueueEntry(9L, track)), Mode.QUEUE, "L")))
        source.act(Draft(MusicCore.A_NOW, listOf(MusicCore.NOW), MusicCore.Now(9L, 5_000, System.currentTimeMillis(), MusicCore.PAUSED, 50).json()))
        waitUntil("the record") { now() != null }
        val pc = PcMusicPlayer(wm.damage.desktop.ScriptedMusic(), link, null, media = { t -> "/music/${t.id}.flac" },
            notice = { notices += it })
        pc.bind(source, MusicCore.PC) { _, _, done -> done("no phone here", true) }
        Thread.sleep(100)
        assertTrue(server.received.isEmpty(), "a binding starts nothing: ${server.commands()}")
        val answer = java.util.concurrent.CompletableFuture<String?>()
        pc.move(MusicCore.PC, buildArgs(9L, 5_000, true), System.currentTimeMillis()) { answer.complete(it) }
        waitUntil("the open") { server.commands().any { it.startsWith("""["loadfile","/music/1.flac","replace",-1,"start=5.000,pause=no"]""") } }
        assertEquals(MusicCore.PHONE, out().host, "out is not written before mpv plays")
        assertTrue(!answer.isDone)
        server.emit("""{"event":"playback-restart"}""")
        assertEquals(null, answer.get())
        waitUntil("out names the PC") { out().host == MusicCore.PC && now()?.state == MusicCore.PLAYING }
        assertEquals(PlayState.PLAYING, pc.state.play)
        owner!!.exited("exit 1")                           // the program ended with nobody asking (lane P's supervisor says so)
        waitUntil("stopped at its place") { now()?.state == MusicCore.STOPPED }
        assertTrue(now()!!.why!!.contains("Play resumes at"), "${now()}")
    }

    /** The PC as the output at entry 9 (track 1), playing; [media] what mpv opens; [clock] the player's clock. */
    private fun pcPlaying(media: (TrackRef) -> String, clock: () -> Long = { System.currentTimeMillis() }): PcMusicPlayer {
        source.act(Draft(MusicCore.A_QUEUE, listOf(MusicCore.Q), MusicCore.queueJson(listOf(QueueEntry(9L, track)), Mode.QUEUE, "L")))
        source.act(Draft(MusicCore.A_NOW, listOf(MusicCore.NOW), MusicCore.Now(9L, 0, System.currentTimeMillis(), MusicCore.PAUSED, 50).json()))
        waitUntil("the record") { now() != null }
        val pc = PcMusicPlayer(wm.damage.desktop.ScriptedMusic(), link, null, media = media, clock = clock)
        pc.bind(source, MusicCore.PC) { _, _, done -> done("no phone here", true) }
        return pc
    }

    @Test fun `a relayed playNow whose next file is missing is answered, and said`() {
        // S1-4 review 2, F2 (its probe, inverted): the lookup for an upcoming entry threw out of the verb and the phone's
        // music connection waited for an answer that never came
        val missing = TrackRef(2, "Missing", durMs = 200_000)
        val pc = pcPlaying({ t -> if (t.id == 2) throw IllegalStateException("track 2 source file missing: /x") else "/music/${t.id}.flac" })
        val moved = java.util.concurrent.CompletableFuture<String?>()
        pc.move(MusicCore.PC, buildArgs(9L, 0, true), System.currentTimeMillis()) { moved.complete(it) }
        waitUntil("the open") { server.commands().any { it.startsWith("[\"loadfile\"") } }
        server.emit("""{"event":"playback-restart"}""")
        assertEquals(null, moved.get(5, java.util.concurrent.TimeUnit.SECONDS))
        val answer = java.util.concurrent.CompletableFuture<String?>()
        pc.command("playNow", kotlinx.serialization.json.buildJsonObject {
            put("entries", MusicCore.queueJson(listOf(QueueEntry(11L, track), QueueEntry(12L, missing)), Mode.QUEUE, "A")["entries"]!!)
        }) { answer.complete(it) }
        assertEquals(null, answer.get(5, java.util.concurrent.TimeUnit.SECONDS), "answered")
        pc.command("playNow", kotlinx.serialization.json.buildJsonObject { put("entries", kotlinx.serialization.json.JsonPrimitive("not a list")) }) { }
        val bad = java.util.concurrent.CompletableFuture<String?>()
        pc.command("moveEntry", kotlinx.serialization.json.buildJsonObject { put("e", kotlinx.serialization.json.JsonPrimitive(999L)) }) { bad.complete(it) }
        assertTrue(bad.get(5, java.util.concurrent.TimeUnit.SECONDS)!!.contains("not in the queue"))
    }

    @Test fun `a take-over that never starts playing is ended and answered`() {
        var t = System.currentTimeMillis()
        val pc = pcPlaying({ x -> "/music/${x.id}.flac" }, clock = { t })
        val answer = java.util.concurrent.CompletableFuture<String?>()
        pc.move(MusicCore.PC, buildArgs(9L, 0, true), t) { answer.complete(it) }
        waitUntil("the open") { server.commands().any { it.startsWith("[\"loadfile\"") } }
        t += wm.damage.core.windows.music.PlayerCore.TAKEOVER_BOUND_MS + 1_000                    // mpv opened and never said playback-restart
        val why = answer.get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(why!!.contains("did not start playing"), why)
        assertEquals(MusicCore.PHONE, out().host)
    }

    @Test fun `the phone's old player record does not become the PC's output`() {
        // S1-4 review 1, 3.1: the PC store's window.music.player was the phone's synced record
        val pc = PcMusicPlayer(wm.damage.desktop.ScriptedMusic(), link, null, media = { "/x" })
        pc.restore(kotlinx.serialization.json.buildJsonObject {
            put("output", kotlinx.serialization.json.JsonPrimitive("17")); put("profile", kotlinx.serialization.json.JsonPrimitive("standard-mono-loudnorm"))
            put("backend", kotlinx.serialization.json.JsonPrimitive("SPOTIFY"))
        })
        waitUntil("restored") { pc.state.output == wm.damage.core.windows.music.Output.PC }
        assertEquals(wm.damage.core.windows.music.Backend.LIBRARY, pc.state.backend)
        assertEquals(PcMusicPlayer.LOSSLESS, pc.state.profile)
    }

    private fun buildArgs(e: Long, pos: Long, playing: Boolean) = kotlinx.serialization.json.buildJsonObject {
        put("e", kotlinx.serialization.json.JsonPrimitive(e)); put("pos", kotlinx.serialization.json.JsonPrimitive(pos))
        put("playing", kotlinx.serialization.json.JsonPrimitive(playing))
    }
}
