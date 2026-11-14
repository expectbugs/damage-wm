package wm.damage.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.net.Reach
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side
import wm.damage.core.replica.ReplicaServer
import wm.damage.core.windows.music.MediaServer

/**
 * Build W, package B: the reach gate is ON every core listener (the spec's T26-B; the lead's decision B.4) — the
 * content host, the media endpoint, the browser replica and (package D, T26-D) the phone's transport seam. Each runs with a gate whose verdict the test sets: while
 * it refuses, a connection that sends nothing is closed at once (EOF; the server's own handler would wait for the
 * request) and the refusal is said naming the listener; while it admits, the same server answers. The setup page's
 * case is `desktop`'s `SetupServerTest`.
 */
class ReachSweepTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Gate {
        val allow = AtomicBoolean(false)
        val lines = CopyOnWriteArrayList<String>()
        val reach = ReachGate(Side.PHONE, sink = { lines += it },
            classify = { _, _, _ -> Reach.Verdict(allow.get(), "test", "refused by the test") })
    }

    /** Refused: EOF before anything is sent, the line said. Admitted: [probe]'s request gets an answer. */
    private fun sweep(port: Int, gate: Gate, listener: String, probe: ByteArray, handlerRuns: () -> Int) {
        Socket().use { c ->
            c.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            c.soTimeout = 5_000                       // the test's own bound; a server with no gate would wait here
            assertEquals(-1, c.getInputStream().read(), "$listener: a refused arrival is closed unread")
        }
        assertTrue(gate.lines.any { it.startsWith("reach: $listener refused 127.0.0.1") }, "$listener: said: ${gate.lines}")
        assertEquals(0, handlerRuns(), "$listener: the handler never ran for a refusal")
        gate.allow.set(true)
        Socket().use { c ->
            c.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            c.soTimeout = 5_000
            c.getOutputStream().write(probe)
            c.getOutputStream().flush()
            assertTrue(c.getInputStream().read() >= 0, "$listener: an admitted arrival is answered")
        }
    }

    @Test
    fun theContentHostClosesARefusalUnread() {
        val gate = Gate()
        val port = freePort()
        val dir = Files.createTempDirectory("reach-content")
        val host = ContentHostServer(LocalContent(dir.resolve("books")), port, "tok", reach = gate.reach)
        host.start()
        try {
            // admitted: a hello with a wrong token is answered with the refusal frame
            val hello = "{\"t\":\"hello\",\"token\":\"wrong\",\"proto\":1}".toByteArray()
            val frame = java.io.ByteArrayOutputStream().also { o ->
                java.io.DataOutputStream(o).apply { writeInt(hello.size); write(hello); flush() }
            }.toByteArray()
            sweep(port, gate, "content host", frame) { 0 }
        } finally {
            host.close()
        }
    }

    @Test
    fun theMediaEndpointClosesARefusalUnread() {
        val gate = Gate()
        val port = freePort()
        val resolved = AtomicInteger()
        val media = MediaServer(port, "tok", gate.reach) { _, _ -> resolved.incrementAndGet(); throw IllegalStateException("no track") }
        media.start()
        try {
            sweep(port, gate, "media endpoint", "GET /track/1?token=wrong HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()) { resolved.get() }
        } finally {
            media.close()
        }
    }

    /** Package D (T26-D): the phone's transport seam. Admitted, a `status` probe with the right token is answered
     *  (the probe the PC's standby sends); refused, the connection is closed before the probe is read. */
    @Test
    fun theTransportSeamClosesARefusalUnread() {
        val gate = Gate()
        val port = freePort()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val asked = AtomicInteger()
        val inner = wm.damage.core.transport.SimTransport(wm.damage.core.sim.GlassFirmwareSim(), scope,
            wm.damage.core.transport.SimTransport.Timing(instant = true))
        val seam = wm.damage.core.transport.RemoteTransportServer(inner, port, "tok", scope,
            statusFor = { asked.incrementAndGet(); wm.damage.core.transport.SeamStatus(wantsRadio = false, driving = false, line = "") },
            reach = gate.reach)
        seam.start()
        try {
            val probe = "{\"t\":\"status\",\"token\":\"tok\"}".toByteArray()
            val frame = java.io.ByteArrayOutputStream().also { o ->
                java.io.DataOutputStream(o).apply { writeInt(probe.size); write(probe); flush() }
            }.toByteArray()
            sweep(port, gate, "transport seam", frame) { asked.get() }
            assertEquals(1, asked.get(), "the admitted probe reached the seam's status")
        } finally {
            seam.close()
            scope.cancel()
        }
    }

    @Test
    fun theBrowserReplicaClosesARefusalUnread() {
        val gate = Gate()
        val port = freePort()
        val inputs = AtomicInteger()
        val replica = ReplicaServer(port, "tok", { null }, { ReplicaServer.Status() }, onInput = { inputs.incrementAndGet() },
            reach = gate.reach)
        replica.start()
        try {
            sweep(port, gate, "browser replica", "GET /?token=wrong HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()) { inputs.get() }
        } finally {
            replica.close()
        }
    }
}
