package wm.damage.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import wm.damage.core.transport.TransportEvent
import wm.damage.core.replica.ReplicaServer
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.LensPanels
import wm.damage.core.transport.RemoteTransportServer
import wm.damage.core.transport.SeamProbe
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.util.Log

/**
 * `HANDOFF.md` §71 (2026-11-04): an Error inside one connection of the two servers the phone runs
 * (the replica on :7403, the seam on :7402) ends that connection and is logged; it never leaves
 * its thread or coroutine. On Android an Error that escapes a thread ends the APK's process, and
 * the glasses' session with it. That is what a whole-journal read did, twice. On the desktop JVM
 * an escaped Error only ends the thread, so each pin checks the two things the unfixed tree
 * cannot do: say it on the host's log, and keep it from reaching the uncaught-exception handler.
 */
class ServerErrorGuardTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Runs [body] with every uncaught throwable carrying [marker] recorded instead of printed;
     *  any other uncaught throwable goes where it went before. */
    private fun captureUncaught(marker: String, body: () -> Unit): List<Throwable> {
        val seen = Collections.synchronizedList(ArrayList<Throwable>())
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (e.message?.contains(marker) == true) seen.add(e)
            else if (prev != null) prev.uncaughtException(t, e)
            else { System.err.print("Exception in thread \"${t.name}\" "); e.printStackTrace() }
        }
        try { body() } finally { Thread.setDefaultUncaughtExceptionHandler(prev) }
        return seen.toList()
    }

    private fun logged(tagAndText: String, marker: String): Boolean =
        Log.recent().any { tagAndText in it && "OutOfMemoryError: $marker" in it }

    private fun failingPanels(marker: String) = object : LensPanels {
        override val exact = true
        override val stride = 320
        override fun panel(arm: Arm): ByteArray = throw OutOfMemoryError(marker)
        override fun snapshot(arm: Arm): ByteArray = throw OutOfMemoryError(marker)
        override fun addListener(l: LensPanels.LensListener) {}
        override fun removeListener(l: LensPanels.LensListener) {}
    }

    /** The replica's request thread: the site of 2026-11-04. */
    @Test
    fun anErrorInAReplicaRequestIsSaidAndStaysInItsThread() {
        val marker = "replica-request-pin-${System.nanoTime()}"
        val port = freePort()
        val server = ReplicaServer(port, "tok", { null }, { ReplicaServer.Status() }, onInput = {},
            journalPath = { throw OutOfMemoryError(marker) })
        server.start()
        val escaped = captureUncaught(marker) {
            try {
                Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 20_000
                    s.getOutputStream().write("GET /journal?token=tok HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                    assertEquals(-1, s.getInputStream().read(), "the request ends with no response")
                }
                assertTrue(logged("E replica: request from", marker), "the Error is said on the host's log")
                Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 20_000
                    s.getOutputStream().write("GET /log?token=tok&tail=1 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                    val line = s.getInputStream().bufferedReader().readLine()
                    assertEquals("HTTP/1.1 200 OK", line, "the server still answers")
                }
            } finally { server.close() }
        }
        assertTrue(escaped.isEmpty(), "nothing escaped the request thread: $escaped")
    }

    /** The replica's per-page sender thread. */
    @Test
    fun anErrorBuildingAReplicaFrameDropsThatPageOnly() {
        val marker = "replica-sender-pin-${System.nanoTime()}"
        val port = freePort()
        val failing = failingPanels(marker)
        val server = ReplicaServer(port, "tok", { failing }, { ReplicaServer.Status() }, onInput = {})
        server.start()
        val escaped = captureUncaught(marker) {
            try {
                Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 20_000    // the unfixed sender never closes the page: this ends the test
                    s.getOutputStream().write(("GET /ws?token=tok HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                        .toByteArray())
                    s.getOutputStream().flush()
                    val inp = DataInputStream(s.getInputStream().buffered())
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val b = inp.read()
                        assertTrue(b >= 0, "the server answered the upgrade before closing: $head")
                        head.append(b.toChar())
                    }
                    assertTrue(head.startsWith("HTTP/1.1 101"), "upgraded: $head")
                    // the attach queues both panels first; the first build fails and the page is dropped
                    while (inp.read() >= 0) { /* any frame before the drop */ }
                }
                assertTrue(logged("E replica: send to", marker), "the Error is said on the host's log")
            } finally { server.close() }
        }
        assertTrue(escaped.isEmpty(), "nothing escaped the sender thread: $escaped")
    }

    /** The seam's reader thread: a status probe whose answer fails. */
    @Test
    fun anErrorInASeamSessionEndsThatSessionOnly() {
        val marker = "seam-reader-pin-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val inner = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val server = RemoteTransportServer(inner, port, "tok", scope, statusFor = { throw OutOfMemoryError(marker) })
        server.start()
        val escaped = captureUncaught(marker) {
            try {
                assertEquals(SeamProbe.Result.Unreachable, SeamProbe.probe("127.0.0.1", port, "tok"),
                    "the probe's session ended without an answer")
                assertTrue(logged("E transport-server: driver session failed", marker), "the Error is said on the host's log")
            } finally { server.close(); scope.cancel() }
        }
        assertTrue(escaped.isEmpty(), "nothing escaped the seam's reader thread: $escaped")
    }

    /** The seam's sender coroutine: the first panel build of a claimed session fails. */
    @Test
    fun anErrorInTheSeamSenderEndsThatSessionOnly() {
        val marker = "seam-sender-pin-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val failing = failingPanels(marker)
        val inner = object : Transport by sim { override val mirror: LensPanels get() = failing }
        val escaped = seamSession(inner, marker)
        scope.cancel()
        assertTrue(logged("E transport-server: sender ended — closing session", marker), "the Error is said on the host's log")
        assertTrue(escaped.isEmpty(), "nothing escaped the seam's sender coroutine: $escaped")
    }

    /** One seam session with [inner] behind the server: a raw client that says hello, reads
     *  until the server closes the connection, and returns how it ended. */
    private fun seamSession(inner: Transport, marker: String, then: (DataOutputStream) -> Unit = {}): List<Throwable> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val server = RemoteTransportServer(inner, port, "tok", scope)
        server.start()
        return captureUncaught(marker) {
            try {
                Socket("127.0.0.1", port).use { s ->
                    val out = DataOutputStream(s.getOutputStream())
                    sendCtl(out, """{"t":"hello","token":"tok"}""")
                    then(out)
                    // Until the server closes the session, bounded as a WHOLE (a bound on the test
                    // only). The unfixed tree can leave the session up with its liveness ticker
                    // sending a ping every few seconds, so a per-read bound alone never fires.
                    s.soTimeout = 1_000
                    val inp = s.getInputStream()
                    val t0 = System.currentTimeMillis()
                    var closed = false
                    while (!closed && System.currentTimeMillis() - t0 < 20_000) {
                        try {
                            if (inp.read() < 0) closed = true
                        } catch (e: java.net.SocketTimeoutException) {
                            // a quiet second: look at the clock again
                        } catch (e: java.net.SocketException) {
                            // a reset is an end too: a server that fails before reading the
                            // hello closes with it unread
                            closed = true
                        }
                    }
                    assertTrue(closed, "the server closed the session within 20 s")
                }
            } finally { server.close(); scope.cancel() }
        }
    }

    private fun sendCtl(out: DataOutputStream, json: String) {
        val b = json.toByteArray()
        out.writeInt(b.size); out.write(b); out.flush()
    }

    /** serve's set-up before the session's try: the mirror's stride is read there. */
    @Test
    fun anErrorInTheSeamSetUpEndsThatConnectionOnly() {
        val marker = "seam-setup-pin-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val broken = object : LensPanels by failingPanels(marker) {
            override val stride: Int get() = throw OutOfMemoryError(marker)
        }
        val inner = object : Transport by sim { override val mirror: LensPanels get() = broken }
        val escaped = seamSession(inner, marker)
        scope.cancel()
        assertTrue(logged("E transport-server: driver connection", marker), "the Error is said on the host's log")
        assertTrue(escaped.isEmpty(), "nothing escaped the connection thread: $escaped")
    }

    /** The forwarding job's other children: the event collector. */
    @Test
    fun anErrorInTheSeamEventCollectorEndsThatSessionOnly() {
        val marker = "seam-events-pin-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val inner = object : Transport by sim {
            override val events: Flow<TransportEvent> = flow { throw OutOfMemoryError(marker) }
        }
        val escaped = seamSession(inner, marker)
        scope.cancel()
        assertTrue(logged("E transport-server: session coroutine failed — closing session", marker), "the Error is said on the host's log")
        assertTrue(escaped.isEmpty(), "nothing escaped the forwarding job: $escaped")
    }

    /** The start job: a driver's start whose inner start fails with an Error. */
    @Test
    fun anErrorInTheSeamStartEndsThatSessionOnly() {
        val marker = "seam-start-pin-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sim = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val inner = object : Transport by sim {
            override suspend fun start(warmupFrame: ByteArray) { throw OutOfMemoryError(marker) }
        }
        val escaped = seamSession(inner, marker) { out -> sendCtl(out, """{"t":"start"}""") }
        scope.cancel()
        assertTrue(logged("E transport-server: session coroutine failed — closing session", marker), "the Error is said on the host's log")
        assertTrue(escaped.isEmpty(), "nothing escaped the start job: $escaped")
    }

    /** The teardown: a throwing step must still release the driver slot, or every later
     *  driver is answered "busy" for the life of the process. */
    @Test
    fun aFailedTeardownStillReleasesTheDriverSlot() {
        val marker = "seam-teardown-pin-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val inner = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val server = RemoteTransportServer(inner, port, "tok", scope,
            onRemoteDriver = { driving -> if (!driving) throw OutOfMemoryError(marker) })
        server.start()
        fun firstReply(s: Socket): String {
            val inp = DataInputStream(s.getInputStream())
            val b = ByteArray(inp.readInt()); inp.readFully(b)
            return String(b, Charsets.UTF_8)
        }
        val escaped = captureUncaught(marker) {
            try {
                Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 20_000
                    sendCtl(DataOutputStream(s.getOutputStream()), """{"t":"hello","token":"tok"}""")
                    assertTrue("\"t\":\"grant\"" in firstReply(s), "the first driver is granted")
                }
                // the teardown logs only after the slot is released: wait for that line
                val t0 = System.currentTimeMillis()
                while (!logged("E transport-server: driver connection", marker) && System.currentTimeMillis() - t0 < 10_000) Thread.sleep(5)
                assertTrue(logged("E transport-server: driver connection", marker), "the teardown's Error is said on the host's log")
                Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 20_000
                    sendCtl(DataOutputStream(s.getOutputStream()), """{"t":"hello","token":"tok"}""")
                    val r = firstReply(s)
                    assertTrue("\"t\":\"grant\"" in r, "the next driver is granted, not refused: $r")
                }
            } finally { server.close(); scope.cancel() }
        }
        assertTrue(escaped.isEmpty(), "nothing escaped the connection thread: $escaped")
    }
}
