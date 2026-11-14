package wm.damage.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.net.RemoteWin

/**
 * Build W, package C (R6 T10, pin W12): `RemoteWin.lastHeardMs` moves on EVERY frame the host sends — the greeting
 * `wup`, an answer `wres`, a push `wpush` and the in-band refusal `err` (a bad token or an old PC still answered) —
 * and never on a failed connect. A stand-in host on loopback speaks the channel's framing by hand, so each frame
 * is sent alone.
 */
class RemoteWinLastHeardTest {

    /** Accepts one driver at a time, reads its hello and upgrade, then sends what the test queues. */
    private class StandIn : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port = server.localPort
        val accepted = LinkedBlockingQueue<Pair<Socket, DataOutputStream>>()
        private val t = Thread({
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                val inp = DataInputStream(s.getInputStream())
                repeat(2) { val n = inp.readInt(); inp.readFully(ByteArray(n)) }        // hello, then the upgrade
                accepted.put(s to DataOutputStream(s.getOutputStream()))
            }
        }, "lastheard-standin").apply { isDaemon = true; start() }

        /** The next accepted driver; a test that never sees one fails rather than waits for ever. */
        fun next(): Pair<Socket, DataOutputStream> =
            accepted.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("no driver connected")

        fun send(out: DataOutputStream, frame: String) {
            val b = frame.toByteArray(Charsets.UTF_8)
            synchronized(out) { out.writeInt(b.size); out.write(b); out.flush() }
        }

        override fun close() { server.close() }
    }

    @Test
    fun everyFrameMovesItAndTheRefusalToo(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        StandIn().use { h ->
            val rw = RemoteWin("127.0.0.1", h.port, "tok", "bastion", scope, retryPacingMs = 50)
            try {
                val (sock, out) = h.next()
                assertEquals(0L, rw.lastHeardMs, "nothing heard before the first frame")
                var last = 0L
                for (frame in listOf("""{"t":"wup"}""", """{"t":"wpush","op":"event"}""",
                        """{"t":"wres","id":99,"ok":true}""", """{"t":"err","detail":"bad token"}""")) {
                    delay(5)                                         // a later wall-clock ms than the last frame's
                    h.send(out, frame)
                    val before = last
                    waitUntil(5_000, 5, { "lastHeardMs moved past $before on $frame (is ${rw.lastHeardMs})" }) {
                        rw.lastHeardMs > before }
                    last = rw.lastHeardMs
                }
                // the refusal ended that connection; the client reconnects (paced) and is accepted again
                val (sock2, _) = h.next()
                sock.close()
                val atRefusal = rw.lastHeardMs
                delay(200)
                assertEquals(atRefusal, rw.lastHeardMs, "a connect that sends no frame moves nothing")
                sock2.close()
            } finally { rw.close() }
        }
        scope.cancel()
    }

    @Test
    fun aFailedConnectNeverMovesIt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dead = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }    // nothing listens
        val rw = RemoteWin("127.0.0.1", dead, "tok", "bastion", scope, retryPacingMs = 20)
        try {
            waitUntil(5_000, 10, { "the client tried and failed (${rw.stateLine})" }) { rw.stateLine.startsWith("PC unreachable") }
            delay(300)                                               // many more paced attempts
            assertEquals(0L, rw.lastHeardMs, "a refused connect is not a frame")
            assertTrue(rw.stateLine.startsWith("PC unreachable"))
        } finally { rw.close(); scope.cancel() }
    }
}
