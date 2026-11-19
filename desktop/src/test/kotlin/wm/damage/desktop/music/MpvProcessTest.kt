package wm.damage.desktop.music

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After

/**
 * S1-4 lane P: [MpvProcess] with [FakeMpvMain] as the child (a test JVM, no player program, no sound device) and,
 * for the leftover cases, `/bin/sh` stand-ins started here. Pinned: nothing starts at construction; the start
 * writes the pidfile and passes the argv of §3.6 with the stdin tie; [MpvProcess.stop] ends the child through its
 * stdin; an exit nobody asked for is said and not restarted; a command unanswered past the bound and a socket never
 * opened end the child, said; a leftover is ended only when the pidfile's pid, its start time and our socket in its
 * command line all match — a stand-in named like a player without our socket, or with another start time, is left
 * alone. After each test no child of this JVM is left running and the temp directory is gone.
 */
class MpvProcessTest {
    private val dir: Path = Files.createTempDirectory("mpvp")
    private val procs = CopyOnWriteArrayList<MpvProcess>()
    private val others = CopyOnWriteArrayList<Process>()

    private val fake: List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx32m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
        "-cp", System.getProperty("java.class.path"), FakeMpvMain::class.java.name,
    )

    private class Owner : MpvProcess.Owner {
        val exits = CopyOnWriteArrayList<String>()
        override fun event(name: String, msg: JsonObject) {}
        override fun property(name: String, value: JsonElement?) {}
        override fun exited(detail: String) { exits.add(detail) }
    }

    private fun process(program: List<String> = fake, boundMs: Long = MpvProcess.UNANSWERED_MS) =
        MpvProcess(program, dir.resolve("music"), boundMs = boundMs).also { procs.add(it) }

    @After fun tearDown() {
        for (p in procs) p.stop("test end")
        for (o in others) if (o.isAlive) { o.destroyForcibly(); o.waitFor() }
        waitUntil("no FakeMpvMain of this JVM running") { fakesAlive() == 0L }
        dir.toFile().deleteRecursively()
        assertFalse(Files.exists(dir))
    }

    private fun fakesAlive(): Long = ProcessHandle.current().children()
        .filter { it.isAlive && MpvProcess.cmdline(it.pid())?.contains(FakeMpvMain::class.java.name) == true }.count()

    private fun pidOf(ipc: MpvIpc): Long {
        var pid = -1L
        ipc.command("get_property", "pid") { pid = (it.data as JsonPrimitive).longOrNull ?: -2 }
        waitUntil("the pid answer") { pid != -1L }
        return pid
    }

    @Test fun `started lazily with the argv and the pidfile, and stop ends it through its stdin`() {
        val p = process()
        assertNull(p.pid, "nothing runs at construction")
        assertFalse(Files.exists(p.pidfile))
        val owner = Owner()
        val ipc = p.ensure(owner)
        val pid = p.pid!!
        assertEquals(pid, pidOf(ipc), "the socket is our child's")
        assertTrue(ipc === p.ensure(owner), "a running child is reused")
        val cmd = MpvProcess.cmdline(pid)!!
        assertEquals(p.argv().drop(fake.size), cmd.drop(fake.size))
        assertEquals(listOf("--no-config", "--idle=yes", "--terminal=no", "--vid=no", "--audio-display=no", "--volume=100",
            "--audio-channels=stereo", "--ao=pipewire", "--input-ipc-server=${p.socket}", "--input-ipc-client=fd://0"), cmd.drop(fake.size))
        val start = ProcessHandle.of(pid).get().info().startInstant().get().toEpochMilli()
        assertEquals("pid=$pid\nstart=$start\nsocket=${p.socket}\n", Files.readString(p.pidfile))
        assertTrue(MpvProcess(listOf("mpv"), dir, "Desk").argv().last() == "--audio-device=Desk")

        p.stop("test")
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertTrue(Files.exists(p.socket.resolveSibling("fake-stdin-eof")), "it ended because its stdin closed")
        waitUntil("the pidfile removed") { !Files.exists(p.pidfile) }
        assertTrue(owner.exits.isEmpty(), "an asked end is not said as a fault")
    }

    @Test fun `an exit nobody asked for is said and not restarted, and the next ensure starts a fresh one`() {
        val p = process()
        val owner = Owner()
        val first = p.ensure(owner)
        val pid1 = p.pid!!
        first.command("damage-fake-exit", 3)
        waitUntil("the exit said") { owner.exits.size == 1 }
        assertTrue(owner.exits[0].contains("ended: by itself, exit code 3"), owner.exits[0])
        Thread.sleep(300)
        assertNull(p.pid, "not restarted by itself")
        assertEquals(0, fakesAlive())
        assertFalse(Files.exists(p.pidfile))
        val second = p.ensure(owner)
        assertNotEquals(pid1, p.pid)
        assertTrue(second.open)
    }

    @Test fun `a command unanswered past the bound ends the child, said`() {
        val p = process(boundMs = 3_000) // the bound covers the fake JVM's own start as well
        val owner = Owner()
        val ipc = p.ensure(owner)
        val pid = p.pid!!
        ipc.command("set_property", "pause", true)
        ipc.command("damage-fake-hang")
        waitUntil("the end said") { owner.exits.size == 1 }
        assertTrue(owner.exits[0].contains("it did not answer `damage-fake-hang`"), owner.exits[0])
        assertTrue(owner.exits[0].contains("(signal 9)"), owner.exits[0])
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun `a socket never opened ends the child at its start, said by the open`() {
        val p = process(program = fake + "--fake-no-socket", boundMs = 3_000)
        val owner = Owner()
        val e = assertFailsWith<IOException> { p.ensure(owner) }
        assertTrue(e.message!!.contains("did not open its socket"), e.message)
        waitUntil("the child gone") { p.pid == null && fakesAlive() == 0L }
        Thread.sleep(50)
        assertTrue(owner.exits.isEmpty(), "said once, by the open")
    }

    /** A `/bin/sh` that waits on its stdin, named like a player, with [socket] on its command line. */
    private fun standIn(socket: String): Process =
        ProcessBuilder("/bin/sh", "-c", "read x", "mpv", "--input-ipc-server=$socket").start().also { others.add(it) }

    private fun startOf(p: Process): Long = p.info().startInstant().get().toEpochMilli()

    @Test fun `a leftover is ended only when its pid, start time and our socket all match`() {
        val p = process()
        Files.createDirectories(p.pidfile.parent)

        val elsewhere = standIn("/elsewhere/mpv.sock")
        Files.writeString(p.pidfile, "pid=${elsewhere.pid()}\nstart=${startOf(elsewhere)}\nsocket=${p.socket}\n")
        p.clearLeftover()
        Thread.sleep(200)
        assertTrue(elsewhere.isAlive, "named like a player, without our socket: left alone")
        assertFalse(Files.exists(p.pidfile))

        val reused = standIn(p.socket.toString())
        Files.writeString(p.pidfile, "pid=${reused.pid()}\nstart=${startOf(reused) - 10_000}\nsocket=${p.socket}\n")
        p.clearLeftover()
        Thread.sleep(200)
        assertTrue(reused.isAlive, "another start time (a reused pid): left alone")

        val ours = standIn(p.socket.toString())
        Files.writeString(p.pidfile, "pid=${ours.pid()}\nstart=${startOf(ours)}\nsocket=${p.socket}\n")
        p.clearLeftover()
        assertFalse(ours.isAlive, "ours: ended")
        assertEquals(143, ours.waitFor(), "by SIGTERM")
        assertFalse(Files.exists(p.pidfile))
        p.clearLeftover() // no pidfile: nothing

        for (o in listOf(elsewhere, reused)) {
            o.outputStream.close() // its `read` meets the end of its input: it exits by itself, status 1
            assertEquals(1, o.waitFor())
        }
    }
}
