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
 * S1-4 lane P: [MpvProcess] with [FakeMpvMain] as the child (a test JVM, no player program, no sound device).
 * Pinned: nothing starts at construction; the start passes the argv of §3.6 with the stdin tie, and a named device
 * only in the manual's form `pipewire/<node>` (without `--ao`; any other form refused in words, starting nothing);
 * [MpvProcess.stop] ends the child through its stdin; an exit nobody asked for is said and not restarted; a command
 * unanswered past the bound and a socket never opened end the child, said. (The pidfile and its leftover check were
 * removed in S1-4's fix stage.) After each test no child of this JVM is left running and the temp directory is gone.
 */
class MpvProcessTest {
    // a short base of its own in /tmp, removed in tearDown: the socket is `<dir>/music/mpv.sock`, which under the build
    // directory's test-tmp reaches 106 of a Unix socket path's 107 bytes in a 12-letter worktree (stage TMP)
    private val dir: Path = Files.createTempDirectory(Path.of("/tmp"), "mpvp")
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

    @Test fun `started lazily with the argv, and stop ends it through its stdin`() {
        val p = process()
        assertNull(p.pid, "nothing runs at construction")
        val owner = Owner()
        val ipc = p.ensure(owner)
        val pid = p.pid!!
        assertEquals(pid, pidOf(ipc), "the socket is our child's")
        assertTrue(ipc === p.ensure(owner), "a running child is reused")
        val cmd = MpvProcess.cmdline(pid)!!
        assertEquals(p.argv().drop(fake.size), cmd.drop(fake.size))
        assertEquals(listOf("--no-config", "--idle=yes", "--terminal=no", "--vid=no", "--audio-display=no", "--volume=100",
            "--audio-channels=stereo", "--input-ipc-server=${p.socket}", "--input-ipc-client=fd://0", "--ao=pipewire"), cmd.drop(fake.size))
        // a named device: the manual's form, and then no `--ao` (S1-4 review 2, F1)
        val named = MpvProcess(fake, dir, "pipewire/alsa_output.usb-desk.analog-stereo").argv()
        assertEquals("--audio-device=pipewire/alsa_output.usb-desk.analog-stereo", named.last())
        assertTrue(named.none { it.startsWith("--ao") }, "$named")

        p.stop("test")
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertTrue(Files.exists(p.socket.resolveSibling("fake-stdin-eof")), "it ended because its stdin closed")
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

    @Test fun `a device in another form is refused in words and starts nothing`() {
        val p = MpvProcess(fake, dir, "Desk speakers")
        val e = try { assertFailsWith<java.io.IOException> { p.ensure(Owner()) } } finally { p.stop("test") }
        assertTrue(e.message!!.contains("pipewire/<node name>"), e.message)
        assertNull(p.pid)
        assertEquals(0, fakesAlive())
    }
}
