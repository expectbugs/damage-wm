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

    private fun buildArgs(e: Long, pos: Long, playing: Boolean) = kotlinx.serialization.json.buildJsonObject {
        put("e", kotlinx.serialization.json.JsonPrimitive(e)); put("pos", kotlinx.serialization.json.JsonPrimitive(pos))
        put("playing", kotlinx.serialization.json.JsonPrimitive(playing))
    }
}
