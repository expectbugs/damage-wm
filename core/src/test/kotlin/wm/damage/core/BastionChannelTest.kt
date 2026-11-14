package wm.damage.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.bastion.AckResult
import wm.damage.core.bastion.AlertEvent
import wm.damage.core.bastion.AlertRule
import wm.damage.core.bastion.AlertState
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionService
import wm.damage.core.bastion.BastionWatcher
import wm.damage.core.bastion.EventKind
import wm.damage.core.bastion.HaReader
import wm.damage.core.bastion.RuleKind
import wm.damage.core.bastion.Severity
import wm.damage.core.bastion.StoreAnswer
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.net.RemoteWin

/**
 * Build W, package B: the `bastion` channel over a REAL content host (R6 T9 as amended by the lead's decision B.3
 * and the spec's §2(b)). A `ContentHostServer` on a free loopback port with the [BastionService], and a
 * `RemoteWin` client: every `table` row of §2(b) — first contact, another epoch, nothing new, events from the ring,
 * older than the ring after the store is reloaded under a running host (the same epoch), a client ahead of the
 * store — the `ack` round trip, a repeated and a stale ack, never a request to the hub, and the pushes in seq order.
 */
class BastionChannelTest {

    private class Host(dir: Path, val store: AlertStore, line: String = "2 rule(s) · hub answered 10:00") : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = ServerSocket(0).use { it.localPort }
        val service = BastionService(store, { line }, scope)
        val host = ContentHostServer(LocalContent(dir.resolve("books")), port, "tok", win = mapOf("bastion" to service))
        val pushes = CopyOnWriteArrayList<JsonObject>()
        val client: RemoteWin

        init {
            host.start()
            client = RemoteWin("127.0.0.1", port, "tok", "bastion", scope, retryPacingMs = 100,
                onPush = { op, args, _ -> if (op == BastionService.OP_EVENT) pushes += args })
        }

        suspend fun up() = waitUntil(10_000, 10, { "channel up: ${client.stateLine}" }) { client.stateLine.isEmpty() }

        fun table(since: Long, epoch: Long): StoreAnswer =
            StoreAnswer.fromJson(client.request("table", buildJsonObject { put("since", since); put("epoch", epoch) }).data)

        fun ack(id: String, episode: Long): AckResult =
            AckResult.fromJson(client.request("ack", buildJsonObject { put("id", id); put("episode", episode) }).data)

        override fun close() {
            client.close()
            host.close()
            service.close()
            scope.cancel()
        }
    }

    /** Every `table` row of §2(b), over the wire. */
    @Test
    fun everyTableRowOverTheWire(): Unit = runBlocking {
        val dir = Files.createTempDirectory("bastion-chan")
        val clock = AtomicLong(1_800_000_000_000L)
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
        clock.addAndGet(1_000)
        store.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")
        val e = store.epoch
        Host(dir, store).use { h ->
            h.up()
            // first contact: adopt, no history, the table
            val first = h.table(0, 0)
            assertTrue(first.changed && !first.truncated && first.events.isEmpty(), "first contact: $first")
            assertEquals(listOf("fresh_full", "pump_fault"), first.table!!.map { it.id })
            assertEquals(e, first.epoch)
            assertEquals("2 rule(s) · hub answered 10:00", first.stateLine, "the watcher's line rides the answer")
            val s = first.seq
            // another epoch: the table, truncated
            val other = h.table(s, e + 1)
            assertTrue(other.changed && other.truncated && other.events.isEmpty() && other.table != null, "another epoch: $other")
            // nothing new: a few bytes, no table
            val same = h.table(s, e)
            assertTrue(!same.changed && !same.truncated && same.events.isEmpty() && same.table == null, "nothing new: $same")
            // events from the ring
            clock.addAndGet(1_000)
            store.clear("fresh_full")
            val since = h.table(s, e)
            assertTrue(since.changed && !since.truncated, "events since: $since")
            assertEquals(listOf(s + 1), since.events.map { it.seq })
            assertEquals(EventKind.CLEARED, since.events.single().kind)
            assertEquals(listOf("pump_fault"), since.table!!.map { it.id })
            // a client ahead of the store (a restored copy, a save lost before a restart): the table, truncated
            val ahead = h.table(store.seq + 5, e)
            assertTrue(ahead.changed && ahead.truncated && ahead.events.isEmpty() && ahead.table != null, "ahead: $ahead")
        }
        // the store reloaded under a running host (every PC restart): the same epoch, the ring empty
        val reloaded = AlertStore(dir.resolve("alerts.json"), clock::get)
        assertEquals(e, reloaded.epoch, "the epoch belongs to the store, not the process")
        Host(dir, reloaded).use { h ->
            h.up()
            val older = h.table(reloaded.seq - 1, e)
            assertTrue(older.changed && older.truncated && older.events.isEmpty(), "older than the ring: $older")
            assertEquals(listOf("pump_fault"), older.table!!.map { it.id }, "a snapshot instead of a silent gap")
            assertEquals(reloaded.seq, older.floor)
        }
    }

    /** `ack {id, episode}`: RAISED → acked with one event; again → the same answer, no event; another episode →
     *  refused, no change; never a request to the hub (a reader on a stand-in hub sees none). Pushes in seq order. */
    @Test
    fun ackRoundTripAndPushesInSeqOrder(): Unit = runBlocking {
        val dir = Files.createTempDirectory("bastion-ack")
        val hubRequests = AtomicLong(0)
        val hub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        hub.createContext("/") { x -> hubRequests.incrementAndGet(); x.sendResponseHeaders(500, -1); x.close() }
        hub.start()
        val store = AlertStore(dir.resolve("alerts.json"))
        try {
            Host(dir, store).use { h ->
                // a watcher on the same store, never ticked: an ack must not reach its hub through any path
                val rule = AlertRule(id = "pump_fault", entity = "binary_sensor.pump_fault", kind = RuleKind.EQUALS,
                    value = "on", text = "Water pump fault")
                BastionWatcher(HaReader("http://127.0.0.1:${hub.address.port}", "made-up-test-token", listOf(rule.entity)),
                    listOf(rule), store, h.scope)
                h.up()
                val first = h.table(0, 0)
                for (i in 1..5) store.raise("a$i", Severity.NORMAL, "alert $i", "$i")
                store.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")
                val episode = store.table().single { it.id == "pump_fault" }.episode

                val ok = h.ack("pump_fault", episode)
                assertTrue(ok.ok && ok.state == AlertState.ACKED, "acknowledged: $ok")
                assertEquals(AlertState.ACKED, store.stateOf("pump_fault"))
                val seqAfterAck = store.seq
                val again = h.ack("pump_fault", episode)
                assertTrue(again.ok && again.state == AlertState.ACKED, "the same ack again: $again")
                assertEquals(seqAfterAck, store.seq, "a repeated ack makes no event")
                val stale = h.ack("pump_fault", episode - 1)
                assertFalse(stale.ok, "another episode is refused: $stale")
                assertEquals("that episode has ended", stale.reason)
                assertEquals(AlertState.ACKED, store.stateOf("pump_fault"), "a stale ack changes nothing")
                assertEquals(0L, hubRequests.get(), "an ack is the store's alone: never a request to the hub")

                waitUntil(10_000, 10, { "every event pushed: ${h.pushes.size}" }) { h.pushes.size == (store.seq - first.seq).toInt() }
                val events = h.pushes.map { AlertEvent.fromJson(it["event"]!!.jsonObject) }
                assertEquals(((first.seq + 1)..store.seq).toList(), events.map { it.seq }, "pushes in seq order, none missing")
                assertTrue(h.pushes.all { it["epoch"]!!.jsonPrimitive.long == store.epoch }, "every push names the epoch")
                assertEquals(EventKind.ACKED, events.last().kind)
                assertNotNull(events.firstOrNull { it.id == "pump_fault" && it.kind == EventKind.RAISED })
                val unknown = try { h.client.request("drop"); null } catch (e: Exception) { e.message }
                assertEquals("unknown bastion op 'drop'", unknown, "an unknown op is refused in-band")
            }
        } finally {
            hub.stop(0)
        }
    }
}
