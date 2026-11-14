package wm.damage.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import wm.damage.core.net.Reach
import wm.damage.core.net.ReachGate
import wm.damage.core.net.Side

/**
 * Build W, package B: the reach gate on an accepted socket (the spec's T25; the lead's decision B.4). A raw
 * `ServerSocket` on loopback with the gate in its accept loop and an injected classifier: an admitted arrival
 * reaches the handler; a refused one is closed BEFORE a byte is read (the client sees EOF without sending
 * anything — a gate that read first would leave it waiting); one line per source per minute, with the count held
 * back; `open` admits everything without asking; the PC asks the interface's kind, the phone never does.
 */
class ReachGateTest {

    /** An accepted socket's two addresses, without a connection (the pacing cases). */
    private class FakeSocket(private val local: InetAddress, private val remote: InetAddress) : Socket() {
        override fun getLocalAddress(): InetAddress = local
        override fun getInetAddress(): InetAddress = remote
    }

    @Test
    fun aRefusalIsClosedBeforeAByteIsReadAndAnAdmittedArrivalIsServed(): Unit = runBlocking {
        val allow = AtomicBoolean(true)
        val lines = CopyOnWriteArrayList<String>()
        val asked = CopyOnWriteArrayList<Pair<Boolean?, Side>>()
        val gate = ReachGate(Side.PC, sink = { lines += it }, pointToPoint = { false },
            classify = { _, p, side -> asked += p to side; Reach.Verdict(allow.get(), "test", "the test's verdict") })
        val handled = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val accepts = AtomicInteger()
        val loop = Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                accepts.incrementAndGet()
                if (!gate.admit(s, "test listener")) { s.close(); continue }
                Thread {
                    s.use {
                        val line = it.getInputStream().bufferedReader().readLine()
                        handled += line ?: "(eof)"
                        it.getOutputStream().write("ok\n".toByteArray())
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
        try {
            Socket().use { c ->
                c.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
                c.soTimeout = 5_000                    // the test's own bound; the gate has none
                c.getOutputStream().write("hello\n".toByteArray())
                assertEquals("ok", c.getInputStream().bufferedReader().readLine(), "an admitted arrival is served")
            }
            assertEquals(listOf("hello"), handled.toList())
            assertEquals(listOf<Pair<Boolean?, Side>>(false to Side.PC), asked.toList(), "the PC passes the interface's kind")

            allow.set(false)
            Socket().use { c ->
                c.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
                c.soTimeout = 5_000
                // nothing is written: a refusal must close at once, unread (EOF), never wait for the client's bytes
                assertEquals(-1, c.getInputStream().read(), "a refusal is closed before anything is read")
            }
            waitUntil(5_000, 5, { "the refusal was said: $lines" }) { lines.size == 1 }
            assertEquals(listOf("hello"), handled.toList(), "the refused connection never reached the handler")
            assertTrue(lines.single().startsWith("reach: test listener refused 127.0.0.1 arriving on 127.0.0.1 (the test's verdict)"),
                lines.single())
            assertEquals(2, accepts.get())
        } finally {
            server.close()
            loop.join(5_000)
        }
    }

    @Test
    fun oneLinePerSourcePerMinuteWithTheCountHeldBack() {
        val clock = AtomicLong(1_800_000_000_000L)
        val lines = CopyOnWriteArrayList<String>()
        val gate = ReachGate(Side.PHONE, sink = { lines += it }, clock = clock::get)
        val lan = InetAddress.getByName("192.168.1.20")
        val a = InetAddress.getByName("192.168.1.50")
        val b = InetAddress.getByName("192.168.1.51")
        repeat(3) { assertEquals(false, gate.admit(FakeSocket(lan, a), "replica")) }
        assertEquals(false, gate.admit(FakeSocket(lan, b), "replica"))
        assertEquals(2, lines.size, "one line per source: $lines")
        clock.addAndGet(59_999)
        assertEquals(false, gate.admit(FakeSocket(lan, a), "replica"))
        assertEquals(2, lines.size, "still inside the minute: held back")
        clock.addAndGet(1)
        assertEquals(false, gate.admit(FakeSocket(lan, a), "replica"))
        assertEquals(3, lines.size)
        assertTrue(lines.last().endsWith("(the phone answers on loopback and the tailnet only) (+3 more since)"), lines.last())
        // admitted arrivals are never said
        assertEquals(true, gate.admit(FakeSocket(InetAddress.getByName("100.101.102.103"), a), "replica"))
        assertEquals(true, gate.admit(FakeSocket(InetAddress.getLoopbackAddress(), a), "replica"))
        assertEquals(3, lines.size)
    }

    @Test
    fun aSourcePrunedWithRefusalsHeldBackIsSaidAsItGoes() {
        val clock = AtomicLong(1_800_000_000_000L)
        val lines = CopyOnWriteArrayList<String>()
        val gate = ReachGate(Side.PHONE, sink = { lines += it }, clock = clock::get)
        val lan = InetAddress.getByName("192.168.1.20")
        val first = InetAddress.getByName("10.0.0.1")
        gate.admit(FakeSocket(lan, first), "replica")
        gate.admit(FakeSocket(lan, first), "replica")          // held back
        clock.addAndGet(60_000)
        for (i in 0..ReachGate.PRUNE_PAST) gate.admit(FakeSocket(lan, InetAddress.getByName("10.1.${i / 250}.${i % 250 + 1}")), "replica")
        assertTrue(lines.any { it.startsWith("reach: 10.0.0.1 refused 1 more time(s) in the minute after") },
            "a pruned count is said, never dropped: ${lines.filter { "10.0.0.1" in it }}")
    }

    @Test
    fun openAdmitsEverythingWithoutAskingAndThePhoneNeverAsksTheInterface() {
        val asked = AtomicInteger()
        val open = ReachGate(Side.PC, open = true, classify = { _, _, _ -> asked.incrementAndGet(); Reach.Verdict(false, "x", "x") })
        assertEquals(true, open.admit(FakeSocket(InetAddress.getByName("203.0.113.5"), InetAddress.getByName("203.0.113.6")), "content host"))
        assertEquals(0, asked.get(), "reachOpen: the classifier is never asked")

        val ifaceAsked = AtomicInteger()
        var seen: Boolean? = true
        val phone = ReachGate(Side.PHONE, sink = {}, pointToPoint = { ifaceAsked.incrementAndGet(); false },
            classify = { a, p, s -> seen = p; Reach.classify(a, p, s) })
        assertEquals(false, phone.admit(FakeSocket(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("192.168.1.50")), "replica"))
        assertEquals(0, ifaceAsked.get(), "the phone never reads the interface")
        assertNull(seen)
        // the PC's real decisions through the gate: the bus network yes, a tunnel no, an unreadable interface no
        val lines = CopyOnWriteArrayList<String>()
        val pc = { p2p: Boolean? -> ReachGate(Side.PC, sink = { lines += it }, pointToPoint = { p2p }) }
        val bus = InetAddress.getByName("192.168.1.20")
        val peer = InetAddress.getByName("192.168.1.50")
        assertEquals(true, pc(false).admit(FakeSocket(bus, peer), "content host"))
        assertEquals(false, pc(true).admit(FakeSocket(InetAddress.getByName("10.8.0.2"), peer), "content host"))
        assertEquals(false, pc(null).admit(FakeSocket(bus, peer), "content host"))
        assertTrue(lines.any { "interface unreadable" in it } && lines.any { "tunnel" in it }, "$lines")
    }
}
