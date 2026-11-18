package wm.damage.core

import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.windows.tmux.TmuxStream

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B10: the control-mode stream against a REAL tmux — on a throwaway server of this test's own
 * (`tmux -L damage-test-<pid>-<n> -f /dev/null`), never the default socket or any session of his; the server is ended
 * and its socket removed at the end. One client per session however many viewers, its init the pane as captured, the
 * pane's output after it, `ignore-size` (the session keeps its size), the last viewer's leave ending the client, a
 * rename and a killed session told to the viewers.
 */
class TmuxStreamTest {
    private val sock = "damage-test-${ProcessHandle.current().pid()}-${System.nanoTime() % 100_000}"
    private val stream = TmuxStream({ _, s -> listOf("tmux", "-L", sock, "-f", "/dev/null", "-C", "attach-session", "-t", "=$s:", "-f", "ignore-size") })

    private fun tmux(vararg a: String): String {
        val p = ProcessBuilder(listOf("tmux", "-L", sock, "-f", "/dev/null") + a).redirectErrorStream(true)
            .also { it.environment().remove("TMUX") }.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(10, TimeUnit.SECONDS)
        return out.trim()
    }

    /** The throwaway server's socket file (tmux leaves it behind after `kill-server`: seen on 3.5a). */
    private val socketFile = java.nio.file.Path.of(System.getenv("TMUX_TMPDIR") ?: "/tmp",
        "tmux-${java.nio.file.Files.getAttribute(java.nio.file.Path.of("/proc/self"), "unix:uid")}", sock)

    @AfterTest
    fun tearDown() {
        stream.close()
        tmux("kill-server")
        java.nio.file.Files.deleteIfExists(socketFile)
    }

    private class Viewer {
        val q = LinkedBlockingQueue<JsonObject>()
        fun next(): JsonObject = q.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no message within 10 s")
        /** The bytes of `data` messages until [s] has arrived in them. */
        fun dataUntil(s: String): String {
            val sb = StringBuilder()
            val until = System.currentTimeMillis() + 10_000
            while (s !in sb && System.currentTimeMillis() < until) {
                val m = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                m["data"]?.let { sb.append(String(Base64.getDecoder().decode(it.jsonPrimitive.content))) }
            }
            assertTrue(s in sb, "'$s' in the stream's output: $sb")
            return sb.toString()
        }
    }

    private fun clients() = tmux("list-clients", "-F", "#{client_pid}").lines().filter { it.isNotBlank() }

    @Test
    fun oneClientPerSession_initThenOutput_ignoreSize_andTheLastLeaveEndsIt() {
        assertTrue(tmux("new-session", "-d", "-s", "work", "-x", "80", "-y", "24", "sh", "-c", "printf 'ready-1\\n'; exec cat").isEmpty())
        val a = Viewer()
        stream.attach("local", "work", 1) { a.q.put(it) }
        val init = a.next()["init"]!!.jsonObject
        assertEquals(80, init["cols"]!!.jsonPrimitive.intOrNull)
        assertEquals(24, init["rows"]!!.jsonPrimitive.intOrNull)
        assertTrue("ready-1" in String(Base64.getDecoder().decode(init["data"]!!.jsonPrimitive.content)), "the pane as captured: $init")
        tmux("send-keys", "-t", "=work:", "-l", "hello-2")
        tmux("send-keys", "-t", "=work:", "Enter")
        a.dataUntil("hello-2")
        assertEquals("80x24", tmux("display-message", "-p", "-t", "=work:", "#{window_width}x#{window_height}"), "ignore-size: the session keeps its size")
        // a second viewer: the same client, an init of its own
        val b = Viewer()
        stream.attach("local", "work", 2) { b.q.put(it) }
        assertTrue(b.next().containsKey("init"))
        assertEquals(1, clients().size, "one control client for the session")
        assertEquals(1, stream.running())
        tmux("send-keys", "-t", "=work:", "-l", "both-3")
        tmux("send-keys", "-t", "=work:", "Enter")
        a.dataUntil("both-3"); b.dataUntil("both-3")
        stream.detach("local", "work", 1)
        assertEquals(1, clients().size, "a viewer left; the other holds the client")
        stream.detach("local", "work", 2)
        eventually("the last viewer's leave ended the client") { clients().isEmpty() && stream.running() == 0 }
    }

    @Test
    fun aRenameAndAKilledSessionAreToldToTheViewers() {
        tmux("new-session", "-d", "-s", "old", "-x", "80", "-y", "24", "sh", "-c", "exec cat")
        val a = Viewer()
        stream.attach("local", "old", 1) { a.q.put(it) }
        assertTrue(a.next().containsKey("init"))
        tmux("rename-session", "-t", "=old:", "new")
        val end = a.next()["end"]!!.jsonObject
        assertEquals("session:local:new", end["to"]!!.jsonPrimitive.content, "$end")
        eventually("the renamed session's client ended") { clients().isEmpty() && stream.running() == 0 }
        val b = Viewer()
        stream.attach("local", "new", 2) { b.q.put(it) }
        assertTrue(b.next().containsKey("init"))
        tmux("kill-session", "-t", "=new:")
        assertTrue(b.next().containsKey("end"), "a killed session ends its viewers")
        eventually("its client ended") { stream.running() == 0 }
        // a session that does not exist: the viewer is told, nothing is left running
        val c = Viewer()
        tmux("new-session", "-d", "-s", "keep", "sh", "-c", "exec cat")     // the server stays up
        stream.attach("local", "nosuch", 3) { c.q.put(it) }
        assertTrue(c.next().containsKey("end"), "an unknown session ends its viewer")
        eventually("nothing left running") { stream.running() == 0 }
    }
}
