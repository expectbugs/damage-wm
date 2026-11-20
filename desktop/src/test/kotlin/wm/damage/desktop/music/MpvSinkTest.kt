package wm.damage.desktop.music

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import wm.damage.core.windows.music.TrackRef

/**
 * S1-4 lane P: [MpvIpc] and [MpvSink] against [FakeMpvServer] (no player program, no sound device). Pinned: the
 * forms the hand run settled (loadfile with `replace -1 start=,pause=`, `set_property pause`, `seek <s> absolute`,
 * `volume`); that only open and Play start the program; that the previous entry's messages after a replace — its
 * `end-file stop`, a late `time-pos` — change nothing; `eof` → ended, `error` → failed with mpv's words; after an exit
 * Play opens the last file at the last place.
 */
class MpvSinkTest {
    private val dir: Path = Files.createTempDirectory("mpvs")
    private val server = FakeMpvServer(dir.resolve("s.sock"))
    private val ipcs = CopyOnWriteArrayList<MpvIpc>()

    private inner class Link : MpvLink {
        var ensures = 0
        override fun ensure(owner: MpvProcess.Owner): MpvIpc {
            ensures++
            return MpvIpc.connect(server.path, owner).also { ipcs.add(it) }
        }
    }

    private class Heard : MpvSink.Events {
        val said = CopyOnWriteArrayList<String>()
        override fun ended() { said.add("ended") }
        override fun failed(detail: String) { said.add("failed: $detail") }
        override fun restarted(playing: Boolean) { said.add("restarted playing=$playing") }
        override fun exited(detail: String) { said.add("exited: $detail") }
    }

    private val link = Link()
    private val heard = Heard()
    private val sink = MpvSink(link, heard)
    private val track = TrackRef(7, "Tone", durMs = 4000)

    @After fun tearDown() {
        for (i in ipcs) i.close()
        server.close()
        dir.toFile().deleteRecursively()
        assertFalse(Files.exists(dir), "the temp directory and its socket are gone")
    }

    private fun sent(n: Int): List<String> { waitUntil("$n commands at the fake") { server.received.size >= n }; return server.commands() }

    @Test fun `the IPC client numbers commands, routes answers, properties and events, and answers everything at its end`() {
        val props = CopyOnWriteArrayList<String>()
        val evs = CopyOnWriteArrayList<String>()
        val closed = CopyOnWriteArrayList<String>()
        val ipc = MpvIpc.connect(server.path, object : MpvIpc.Listener {
            override fun event(name: String, msg: JsonObject) { evs.add(name) }
            override fun property(name: String, value: JsonElement?) { props.add("$name=$value") }
            override fun closed(detail: String) { closed.add(detail) }
        }).also { ipcs.add(it) }
        val answers = CopyOnWriteArrayList<String>()
        ipc.command("get_property", "pid") { answers.add("${it.ok} ${it.data}") }
        server.refuse["seek"] = "error running command"
        ipc.command("seek", 1, "absolute") { answers.add("${it.ok} ${it.error}") }
        ipc.observe("time-pos")
        waitUntil("three answers") { answers.size == 2 && server.received.size == 3 }
        assertEquals(listOf("true ${ProcessHandle.current().pid()}", "false error running command"), answers)
        assertEquals("""["observe_property",1,"time-pos"]""", server.commands()[2])
        server.emit("""{"event":"property-change","id":1,"name":"time-pos","data":1.500000}""")
        server.emit("""{"event":"property-change","id":1,"name":"time-pos"}""")
        server.emit("""{"event":"playback-restart"}""")
        waitUntil("two properties and an event") { props.size == 2 && evs.size == 1 }
        assertEquals(listOf("time-pos=1.500000", "time-pos=null"), props)
        assertEquals(listOf("playback-restart"), evs)

        server.answering = false
        ipc.command("stop") { answers.add("${it.ok} ${it.error}") }
        waitUntil("the stop at the fake") { server.received.size == 4 }
        Thread.sleep(30)
        val (what, age) = ipc.oldestUnanswered()!!
        assertEquals("stop", what); assertTrue(age >= 30, "age $age")
        server.close()
        waitUntil("the end") { closed.size == 1 }
        assertFalse(ipc.open)
        assertTrue(answers.last().startsWith("false the player's connection ended"), answers.last())
        ipc.command("pause") { answers.add("${it.ok} ${it.error}") }
        assertEquals("false the player's connection has ended", answers.last())
    }

    @Test fun `nothing but open and Play starts the program, and the forms are mpv's`() {
        sink.setVolumePct(70); sink.pause(); sink.seekTo(5_000); sink.stop()
        assertEquals(5_000, sink.positionMs(), "a seek with no program is the next start's place")
        assertEquals(0, link.ensures, "no program before an open")
        Thread.sleep(50)
        assertTrue(server.received.isEmpty())

        sink.open("/music/Björk, A/01 a.flac", 1_500, false, track)
        assertEquals(1, link.ensures)
        val c = sent(4)
        assertEquals(listOf(
            """["observe_property",1,"time-pos"]""", """["observe_property",2,"duration"]""",
            """["set_property","volume",70]""",
            """["loadfile","/music/Björk, A/01 a.flac","replace",-1,"start=1.500,pause=yes"]""",
        ), c)
        assertFalse(sink.isPlaying())
        sink.play(); sink.seekTo(3_000); sink.setVolumePct(55); sink.pause(); sink.stop()
        assertEquals(listOf(
            """["set_property","pause",false]""", """["seek",3.0,"absolute"]""", """["set_property","volume",55]""",
            """["set_property","pause",true]""", """["stop"]""",
        ), sent(9).drop(4))
        assertEquals(1, link.ensures, "one program for all of it")
        sink.open("/music/b.flac", 0, true, track)
        assertEquals("""["loadfile","/music/b.flac","replace",-1,"start=0.000,pause=no"]""", sent(10)[9])
    }

    @Test fun `place, end and restart follow the entry last opened only`() {
        sink.open("/music/a.flac", 0, true, track)
        waitUntil("entry 1 known") { server.received.size == 3 }
        server.emit("""{"event":"property-change","id":2,"name":"duration","data":4.000000}""")
        server.emit("""{"event":"playback-restart"}""")
        server.emit("""{"event":"property-change","id":1,"name":"time-pos","data":2.250000}""")
        waitUntil("the place") { sink.positionMs() == 2_250L }
        assertEquals(4_000, sink.durationMs())
        assertEquals(listOf("restarted playing=true"), heard.said)
        assertTrue(sink.isPlaying())

        // A replace: the answer comes, the old file's last messages still arrive before the new start-file.
        server.autoStart = false
        sink.open("/music/b.flac", 60_000, false, track)
        waitUntil("the second loadfile") { server.received.size == 4 }
        Thread.sleep(30)
        server.emit("""{"event":"property-change","id":1,"name":"time-pos","data":2.300000}""")
        server.emit("""{"event":"end-file","reason":"stop","playlist_entry_id":1}""")
        server.emit("""{"event":"end-file","reason":"eof","playlist_entry_id":1}""")
        server.emit("""{"event":"playback-restart"}""")
        Thread.sleep(80)
        assertEquals(60_000, sink.positionMs(), "the previous file's place is not taken")
        assertEquals(listOf("restarted playing=true"), heard.said, "nothing said for the previous entry")
        server.emit("""{"event":"start-file","playlist_entry_id":2}""")
        server.emit("""{"event":"property-change","id":1,"name":"time-pos","data":60.000000}""")
        server.emit("""{"event":"playback-restart"}""")
        server.emit("""{"event":"property-change","id":1,"name":"time-pos","data":60.020000}""")
        waitUntil("the new place") { sink.positionMs() == 60_020L }
        waitUntil("the staged restart") { heard.said.size == 2 }
        assertEquals("restarted playing=false", heard.said[1], "a staged open restarts paused")

        server.emit("""{"event":"end-file","reason":"eof","playlist_entry_id":2}""")
        waitUntil("ended") { heard.said.size == 3 }
        assertEquals("ended", heard.said[2])
        assertFalse(sink.isPlaying())
        server.emit("""{"event":"end-file","reason":"eof","playlist_entry_id":2}""")
        Thread.sleep(50)
        assertEquals(3, heard.said.size, "one end per entry")

        server.autoStart = true
        sink.open("/missing.wav", 0, true, track)
        waitUntil("the third loadfile") { server.received.size == 5 }
        Thread.sleep(30)
        server.emit("""{"event":"end-file","reason":"error","playlist_entry_id":3,"file_error":"loading failed"}""")
        waitUntil("failed") { heard.said.size == 4 }
        assertEquals("failed: the PC player could not play the file: loading failed", heard.said[3])
    }

    @Test fun `a refused loadfile is said, and Play after an exit opens the last file at the last place`() {
        server.refuse["loadfile"] = "invalid parameter"
        sink.open("/music/a.flac", 0, true, track)
        waitUntil("failed") { heard.said.size == 1 }
        assertEquals("failed: the PC player refused the file: invalid parameter", heard.said[0])
        server.refuse.clear()

        sink.open("/music/a.flac", 0, true, track)
        waitUntil("loadfile 2") { server.received.size == 4 }
        Thread.sleep(30)
        server.emit("""{"event":"property-change","id":1,"name":"time-pos","data":133.400000}""")
        waitUntil("the place") { sink.positionMs() == 133_400L }
        ipcs.last().close()
        sink.exited("the PC player (pid 1) ended: by itself, exit code 3")
        assertEquals("exited: the PC player (pid 1) ended: by itself, exit code 3", heard.said[1])
        assertFalse(sink.isPlaying())
        assertEquals(1, link.ensures, "an exit starts nothing")
        sink.play()
        assertEquals(2, link.ensures, "Play starts a fresh one")
        assertEquals("""["loadfile","/music/a.flac","replace",-1,"start=133.400,pause=no"]""", sent(7)[6])
    }

    @Test fun `the PC has one output and no boost`() {
        sink.setBoostPct(100)
        assertFailsWith<UnsupportedOperationException> { sink.setBoostPct(120) }
        assertEquals(listOf(MpvSink.OUTPUT_ID), sink.outputs().map { it.id })
        assertTrue(sink.setOutput(MpvSink.OUTPUT_ID)); assertFalse(sink.setOutput("buds"))
        assertFalse(sink.streamed()); assertFalse(sink.waitingForData())
        assertEquals("Desk speakers", MpvSink(link, heard, "Desk speakers").outputs().single().name)
        assertEquals(0, link.ensures)
    }
}
