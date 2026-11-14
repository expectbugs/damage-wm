package wm.damage.core

import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.AppWire
import wm.damage.core.app.Outbox

/**
 * Build S1-1, package P2 (the spec's §3 "per-connection outbox"): a connection whose reader takes nothing is ended once
 * its outbox holds its cap — at once when the sender is stuck in a write, with `end{slow}` when the sender is free —
 * and every other connection keeps flowing. The first two tests drive the outbox with a write the test holds; the
 * last does it over loopback with a client that never reads.
 */
class OutboxTest {

    private val closing = ArrayList<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        for (c in closing.asReversed()) c.close()
    }

    private fun frame(i: Int) = "frame $i".toByteArray()

    @Test
    fun aStuckReaderIsEndedAndOthersFlow(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val stuck = Outbox("stuck", 4, write = {
            entered.countDown()
            release.await()
            if (closed.get()) throw IOException("socket closed (test)")
        }, close = { closed.set(true); release.countDown() })
        val got = CopyOnWriteArrayList<String>()
        val other = Outbox("other", 256, write = { got += String(it) }, close = {})
        stuck.start()
        other.start()
        assertTrue(stuck.offer(frame(0)))
        entered.await()                                  // the sender is in a write that does not return
        for (i in 1..4) assertTrue(stuck.offer(frame(i)), "frame $i fits under the cap")
        assertFalse(stuck.offer(frame(5)), "the cap is reached: the connection ends")
        assertTrue(closed.get(), "a sender stuck in a write is ended by closing its socket")
        assertNotNull(stuck.endedWhy)
        assertFalse(stuck.offer(frame(6)), "an ended outbox takes nothing more")
        for (i in 0 until 100) assertTrue(other.offer(frame(i)))
        waitUntil(10_000, 5, { "the other connection flows: ${got.size}" }) { got.size == 100 }
        assertEquals((0 until 100).map { "frame $it" }, got.toList())
    }

    @Test
    fun aFreeSenderSendsEndSlowAndCloses(): Unit = runBlocking {
        val written = CopyOnWriteArrayList<String>()
        val closed = AtomicBoolean()
        val box = Outbox("slow", 3, write = { written += String(it) }, close = { closed.set(true) })
        for (i in 0 until 3) assertTrue(box.offer(frame(i)))
        assertFalse(box.offer(frame(3)))                 // the sender has not started: free, not stuck
        assertFalse(closed.get(), "a free sender is left to say why")
        box.start()
        waitUntil(10_000, 5, { "closed after end{slow}: $written" }) { closed.get() }
        assertEquals(listOf(AppWire.encode(AppWire.end(AppWire.END_SLOW)).toString(Charsets.UTF_8)), written.toList(),
            "the queue was dropped; end{slow} alone went out")
    }

    /** Over the wire: a client that never reads (a small receive buffer) is ended by the PC once its outbox fills; a
     *  client that reads gets every change in order. */
    @Test
    fun aStuckSocketIsEndedAndTheOtherConnectionKeepsAllItsChanges(): Unit = runBlocking {
        val rig = AppLaneRig(outboxCap = 16).also { closing += it }
        val stuck = Socket().also { closing += it }
        stuck.receiveBufferSize = 4_096
        stuck.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), rig.port))
        val o = DataOutputStream(stuck.getOutputStream().buffered())
        AppWire.write(o, AppWire.encode(buildJsonObject { put("t", "hello"); put("token", AppLaneRig.TOKEN); put("proto", 1) }))
        AppWire.write(o, AppWire.encode(AppWire.hello("s9", mapOf("t" to 1))))
        AppWire.write(o, AppWire.encode(AppWire.sub("t", null)))
        val reader = RawLane(rig.port).also { closing += it }
        reader.welcome()
        reader.send(AppWire.sub("t", null))
        reader.next("snap")
        waitUntil(10_000, 5, { "two connections: ${rig.net.connections()}" }) { rig.net.connections() == 2 }
        val big = "x".repeat(64 * 1024)
        var n = 0L
        while (rig.net.connections() == 2) {
            n++
            if (n > 2_000) throw AssertionError("2,000 changes of 64 KiB and the stuck connection is still served")
            rig.write("desk", "d1", n, "a", "$n:$big")
        }
        val seqs = (1..n).map { AppWire.parseChg(reader.next("chg")).seq }
        assertEquals((1..n).toList(), seqs, "the reading connection got every change, in order")
        reader.quiet()
    }
}
