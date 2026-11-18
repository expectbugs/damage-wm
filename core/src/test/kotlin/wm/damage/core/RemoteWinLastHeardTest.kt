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

    /** X3's class (ruling §5: Torrents' `snap`, Feed's `sources`, Music's `catalog`): a quick request the host took
     *  and never answered (a link gone half-open) drops the connection once its bound passes; the request fails, said,
     *  and the channel connects again. */
    @Test
    fun aQuickRequestWithNoAnswerDropsTheConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        StandIn().use { h ->
            val rw = RemoteWin("127.0.0.1", h.port, "tok", "torrents", scope, retryPacingMs = 50, stuckMs = 300)
            try {
                val (sock, out) = h.next()
                h.send(out, """{"t":"wup"}""")
                waitUntil(5_000, 5, { "the channel came up (${rw.stateLine})" }) { rw.stateLine.isEmpty() }
                val failed = LinkedBlockingQueue<String>()
                val asker = Thread({
                    try { rw.request("snap", quick = true) } catch (e: Exception) { failed.put(e.message ?: "") }
                }, "x3-asker").apply { isDaemon = true; start() }
                asker.join(5_000)
                assertTrue(!asker.isAlive, "the unanswered request ended with the drop")
                assertTrue("down" in failed.poll()!!, "it failed with the channel's end")
                h.next().first.close()                                   // the channel connected again
                sock.close()
            } finally { rw.close() }
        }
        scope.cancel()
    }

    /** L1 R2 A2 (RX NOTE 5): the PC serves a channel's requests one at a time, so a quick request sent behind a long
     *  one waits its minute only from the moment no older request is pending. Here the long ask is answered after
     *  three of the quick request's bounds, then the quick one at once: both are answered on the one connection. */
    @Test
    fun aQuickRequestBehindALongOneIsNotDroppedWhileTheLongOneIsWorkedOn(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        StandIn().use { h ->
            val rw = RemoteWin("127.0.0.1", h.port, "tok", "music", scope, retryPacingMs = 50, stuckMs = 300)
            try {
                val (sock, out) = h.next()
                val inp = DataInputStream(sock.getInputStream())
                fun readOp(): String { val b = ByteArray(inp.readInt()); inp.readFully(b); return String(b, Charsets.UTF_8) }
                h.send(out, """{"t":"wup"}""")
                waitUntil(5_000, 5, { "the channel came up (${rw.stateLine})" }) { rw.stateLine.isEmpty() }
                val answers = LinkedBlockingQueue<String>()
                fun ask(op: String, quick: Boolean) = Thread({
                    answers.put(try { "$op: " + rw.request(op, quick = quick).data["v"] } catch (e: Exception) { "$op failed: ${e.message}" })
                }, "a2-$op").apply { isDaemon = true; start() }
                ask("ask", quick = false)
                assertTrue("\"ask\"" in readOp(), "the long ask reached the PC first")
                ask("catalog", quick = true)
                assertTrue("\"catalog\"" in readOp(), "then the quick one")
                delay(900)                                         // the PC is still working on the long ask
                h.send(out, """{"t":"wres","id":1,"ok":true,"data":{"v":"long"}}""")
                h.send(out, """{"t":"wres","id":2,"ok":true,"data":{"v":"quick"}}""")
                val got = setOf(answers.poll(5, java.util.concurrent.TimeUnit.SECONDS), answers.poll(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(setOf("ask: \"long\"", "catalog: \"quick\""), got)
                assertEquals(null, h.accepted.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS), "the connection was never dropped")
                sock.close()
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

    /** S1-3 fix B2: an outage of many attempts changes the channel's line ONCE — to its start ("PC unreachable since
     *  H:MM"), so the glasses' chrome is not repainted at every 2 s attempt; the same for the Tmux channel (the twin).
     *  The host accepts each attempt and ends it at once, so the attempts are counted. */
    @Test
    fun anOutageOfManyAttemptsChangesTheLineOnce(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        Thread({ while (!server.isClosed) try { server.accept().close(); attempts.incrementAndGet() } catch (e: Exception) { break } },
            "outage-host").apply { isDaemon = true; start() }
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val rw = RemoteWin("127.0.0.1", server.localPort, "tok", "feed", scope, retryPacingMs = 20, onState = { lines += it })
        val tmuxLines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val tmux = wm.damage.core.windows.tmux.RemoteTmuxProvider("127.0.0.1", server.localPort, "tok", scope, retryPacingMs = 20)
        tmux.addListener(object : wm.damage.core.windows.tmux.TmuxProvider.Listener {
            override fun status(sessions: List<wm.damage.core.windows.tmux.TmuxSessionInfo>, cfg: wm.damage.core.windows.tmux.TmuxConfig) {}
            override fun frame(target: wm.damage.core.windows.tmux.TmuxTarget, frame: wm.damage.core.windows.tmux.PaneFrame) {}
            override fun alert(session: wm.damage.core.windows.tmux.TmuxSessionInfo) {}
            override fun state(state: String) { tmuxLines += state }
        })
        try {
            waitUntil(10_000, 10, { "many attempts (${attempts.get()})" }) { attempts.get() >= 12 }
            assertEquals(1, lines.size, "the line changed once over ${attempts.get()} attempts: $lines")
            assertTrue(Regex("""PC unreachable since \d\d:\d\d""").matches(lines[0]), lines[0])
            assertEquals(lines[0], rw.stateLine)
            assertEquals(2, tmuxLines.size, "the Tmux channel: its first line, then the outage's, once: $tmuxLines")
            assertTrue(tmuxLines[1].startsWith("PC unreachable since "), "$tmuxLines")
        } finally { rw.close(); tmux.close(); server.close(); scope.cancel() }
    }
}
