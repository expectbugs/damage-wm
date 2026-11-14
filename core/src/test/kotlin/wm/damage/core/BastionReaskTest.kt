package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.RemoteBastion
import wm.damage.core.bastion.Severity
import wm.damage.core.bastion.StoreAnswer
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.net.WinService
import wm.damage.core.util.Log

/**
 * Build W, fix round 1 (review 1, finding 1; the lead's R1.1): the phone's client when its copy is AHEAD of the PC's
 * store (a save that failed before a PC restart, or a restored alerts.json — §2(b)'s "future" row), a push at or
 * below its cursor, and the pacing of every re-ask. A real content host on loopback; no phone, glasses or hub.
 */
class BastionReaskTest {

    private val dir = Files.createTempDirectory("bastion-reask")
    private val cache = dir.resolve("wincache").resolve("bastion")
    private val clock = AtomicLong(NOON)

    private fun <T> client(port: Int, d: DeliveryRig, pingMs: Long, reaskMs: Long = RemoteBastion.REASK_MS,
                           body: suspend (RemoteBastion) -> T): T = runBlocking {
        val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rb = remote(port, cs, cache, d.delivery, pingMs, reaskMs)
        try { body(rb) } finally { rb.close(); cs.cancel() }
    }

    private fun <T> journal(body: (List<String>) -> T): T {
        val lines = CopyOnWriteArrayList<String>()
        val sink = Log.Sink { _, tag, m -> if (tag == "bastion-remote") lines += m }
        Log.addSink(sink)
        try { return body(lines) } finally { Log.removeSink(sink) }
    }

    private fun savedCopy(epoch: Long, lastSeq: Long, alerts: String = "") {
        Files.createDirectories(cache)
        Files.writeString(cache.resolve(RemoteBastion.FILE), """{"epoch":$epoch,"lastSeq":$lastSeq,"alerts":{$alerts}}""")
    }

    /** The phone's cursor five past the store, same epoch: the PC answers truncated with seq below it; that answer is
     *  a snapshot and is taken — the cursor moves to the store's seq after ONE answer, and the next raise is said. */
    @Test
    fun thePhoneAheadOfTheStoreIsReconciledFromTheTruncatedAnswer() {
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
        savedCopy(store.epoch, store.seq + 5, """"fresh_full":{"episode":1,"state":"raised","severity":"normal",""" +
            """"raisedAt":$NOON,"text":"Fresh tank nearly full","value":"91","saidEpisode":1}""")
        val d = DeliveryRig()
        BastionHost(dir, store).use { h ->
            client(h.port, d, pingMs = 60_000) { rb ->
                synced(rb, store)
                assertTrue(h.op("table") <= 2, "a bounded number of table asks: ${h.op("table")}")
                store.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")
                synced(rb, store)
                assertEquals(1, d.said("pump_fault").size, "the next raise is said once")
                assertEquals(1, d.phone.loud("pump_fault").size)
                assertEquals(0, d.said("fresh_full").size, "said before the PC restarted: not again")
                assertTrue(h.op("table") <= 2, "and no more asks: ${h.op("table")}")
            }
        }
    }

    /** A duplicate push lands at the cursor: not applied, and journaled ONCE per reason on this connection; a gap is
     *  another reason with its own one line; everything is said once. */
    @Test
    fun aPushAtOrBelowTheCursorIsDroppedAndSaidOncePerReason() = journal { lines ->
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        val d = DeliveryRig()
        BastionHost(dir, store).use { h ->
            client(h.port, d, pingMs = 60_000) { rb ->
                synced(rb, store)
                h.dupPushSeq = store.seq + 1
                store.raise("a_one", Severity.NORMAL, "one", "1")
                waitUntil(10_000, 5, { "the duplicate was not journaled: $lines" }) {
                    lines.any { "dropped: this copy is at seq" in it } }
                h.dupPushSeq = store.seq + 1
                val h0 = rb.handled()
                store.raise("b_two", Severity.NORMAL, "two", "2")
                waitUntil(10_000, 5, { "the push and its duplicate not both handled (${rb.handled()} after $h0)" }) {
                    rb.handled() >= h0 + 2 }
                assertEquals(1, lines.count { "dropped: this copy is at seq" in it },
                    "one line per reason per connection: $lines")
                h.dropPushSeq = store.seq + 1
                store.raise("c_three", Severity.NORMAL, "three", "3")      // its push is lost
                store.raise("d_four", Severity.NORMAL, "four", "4")        // so this one is a gap
                synced(rb, store)
                assertEquals(1, lines.count { "a gap after" in it }, "a gap: its own one line: $lines")
                for (id in listOf("a_one", "b_two", "c_three", "d_four")) assertEquals(1, d.said(id).size, "$id said once")
                assertEquals(4, d.phone.loud().size)
            }
        }
    }

    /** A stand-in PC whose every table answer is one this copy cannot use: (a) changed without its table, (b) behind
     *  this copy and not truncated. Each asks again; with the re-ask pacing long, every ask after the first re-ask
     *  rides on a ping — so the asks are bounded by the pings, never a loop of asks back to back. */
    @Test
    fun everyReaskIsPacedNeverAnImmediateLoop() {
        val cases = listOf(
            "changed without its table" to { e: Long -> StoreAnswer(e, 5, 0, true, false, emptyList(), null) },
            "behind this copy" to { e: Long -> StoreAnswer(e, 5, 0, true, false, emptyList(), emptyList()) },
        )
        for ((name, answer) in cases) {
            Files.createDirectories(cache)
            Files.deleteIfExists(cache.resolve(RemoteBastion.FILE))
            if (name == "behind this copy") savedCopy(77, 10)
            val standIn = object : WinService {
                val asks = AtomicInteger()
                override fun request(op: String, args: JsonObject): WinService.Answer {
                    if (op == "table") asks.incrementAndGet()
                    return WinService.Answer(answer(if (name == "behind this copy") 77L else 9L).toJson())
                }
            }
            val port = ServerSocket(0).use { it.localPort }
            val host = ContentHostServer(LocalContent(dir.resolve("books")), port, "tok", win = mapOf("bastion" to standIn))
            host.start()
            try {
                client(port, DeliveryRig(), pingMs = 100, reaskMs = 60_000) { rb ->
                    waitUntil(10_000, 5, { "$name: five ticks not handled (${rb.ticked()})" }) { rb.ticked() >= 5 }
                    val asks = standIn.asks.get()
                    val ticks = rb.ticked()
                    // the channel-up ask, the one re-ask sent at once, one per ping (a ping goes out before its tick)
                    assertTrue(asks <= ticks + 4, "$name: $asks table asks over $ticks pings")
                }
            } finally { host.close() }
        }
    }
}
