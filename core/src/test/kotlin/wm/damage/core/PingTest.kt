package wm.damage.core

import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.app.AppClient
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent

/**
 * Build S1-1, package P2 (`CONTRACT.md` §9.3, §13 (b) "paced ping"): the PC is out of reach only when a ping is still
 * unanswered as the next falls due — never on a failed write or a failed connect — and any answer (a pong, a fresh
 * welcome) brings it back. The test drives the ticks; the PC is a real content host on loopback port 0, stopped and
 * started on the same port, and a core executor held busy (a pong goes through it).
 */
class PingTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closing = ArrayList<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        for (c in closing.asReversed()) c.close()
    }

    @Test
    fun outOfReachOnlyFromTheUnansweredPing(): Unit = runBlocking {
        val rig = AppLaneRig().also { closing += it }
        val port = rig.port
        val now = AtomicLong(1_000)
        val heard = CopyOnWriteArrayList<AppClient.Reach>()
        val client = AppClient("127.0.0.1", port, AppClient.Credential.Token("tok"), "s1",
            object : AppClient.Listener {
                override fun reach(r: AppClient.Reach) { heard += r }
            }, scope, contracts = mapOf("t" to 1), retryPacingMs = 20, pingEveryMs = 0, clock = now::get)
            .also { closing += it }
        waitUntil(10_000, 5, { "welcomed" }) { client.welcome != null }
        assertEquals(listOf<AppClient.Reach>(AppClient.Reach.Up), heard.toList(), "a welcome is the PC answering")
        client.pingTick()
        waitUntil(10_000, 5, { "ping 1 answered: ${client.lastPong}" }) { client.lastPong == 1L }
        client.pingTick()                                // ping 1 was answered: nothing to decide
        assertEquals(AppClient.Reach.Up, client.reach)
        waitUntil(10_000, 5, { "ping 2 answered: ${client.lastPong}" }) { client.lastPong == 2L }

        // the PC's listener goes away: connects fail and writes fail, and neither decides anything
        rig.server.close()
        waitUntil(10_000, 5, { "the connection noticed as gone" }) { client.welcome == null }
        assertFalse(client.send(Op("phone", OpId.Queued("s1", 1), "t", "set", listOf("a"), j("x"))), "no connection to carry it")
        assertEquals(AppClient.Reach.Up, client.reach, "a failed write is not an unanswered ping")
        now.set(5_000)
        client.pingTick()                                // ping 3 counted as sent: nobody can answer it
        assertEquals(AppClient.Reach.Up, client.reach, "one unanswered ping decides nothing until the next is due")
        now.set(20_000)
        client.pingTick()
        val down = client.reach as AppClient.Reach.Down
        assertEquals(5_000L, down.since, "out of reach since the ping that went unanswered")
        assertEquals(listOf(AppClient.Reach.Up, down), heard.toList())

        // the listener is back on the same port: the reconnect's welcome is the answer
        val back = ContentHostServer(LocalContent(rig.rec.dir.resolve("books")), port, AppLaneRig.TOKEN, app = rig.net,
            bindAddress = InetAddress.getLoopbackAddress()).also { closing += it }
        back.start()
        waitUntil(10_000, 5, { "back: ${client.reach}" }) { client.reach == AppClient.Reach.Up && client.welcome != null }

        // connected, but the PC's core does not answer: the pong waits behind it
        val release = CountDownLatch(1)
        rig.host.executor.execute("a held core (test)") { release.await() }
        try {
            now.set(30_000)
            client.pingTick()
            now.set(45_000)
            client.pingTick()
            assertEquals(AppClient.Reach.Down(30_000), client.reach, "connected is not answering")
        } finally {
            release.countDown()                          // a failed assertion must not leave the core held
        }
        waitUntil(10_000, 5, { "answered again: ${client.reach}" }) { client.reach == AppClient.Reach.Up }
        assertEquals(5, heard.size, "Up, Down, Up, Down, Up, said once each: $heard")
    }
}
