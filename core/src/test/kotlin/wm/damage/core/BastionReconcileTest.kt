package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.bastion.AlertDelivery
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.RemoteBastion
import wm.damage.core.bastion.Severity

/**
 * Build W, package C (the spec's new T22; pins W11 and "the said-set not read on a truncated answer"): the phone's
 * client against a REAL content host with the REAL `BastionService` (package B) on loopback — every row of the
 * spec's §2(b) client table: first contact, events after it, a lost push recovered through the table, a truncated
 * answer of the same epoch (the PC restarted while the phone was away), another epoch (the store recreated), and an
 * APK restart. A counter of glasses notices and of the shade's loud posts proves nothing is said twice.
 */
class BastionReconcileTest {

    private val dir = Files.createTempDirectory("bastion-reconcile")
    private val cache = dir.resolve("wincache").resolve("bastion")
    private val clock = AtomicLong(NOON)

    private fun <T> client(port: Int, d: AlertDelivery, pingMs: Long = 100, body: suspend (RemoteBastion) -> T): T = runBlocking {
        val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rb = remote(port, cs, cache, d, pingMs)
        try { body(rb) } finally { rb.close(); cs.cancel() }
    }

    @Test
    fun firstContactAdoptsWithoutANoticeThenSaysEachChangeOnce() {
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
        store.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")
        store.raise("grey_full", Severity.NORMAL, "Grey tank nearly full", "88")
        store.ack("grey_full", 1)
        val d = DeliveryRig()
        d.phone.shown += "left_over"                                       // ours, with no alert behind it any more
        BastionHost(dir, store).use { h ->
            client(h.port, d.delivery) { rb ->
                synced(rb, store)
                ticks(rb, 5)
                assertEquals(emptyList(), d.glasses, "first contact: no glasses notice (CONTRACT §7.3)")
                assertEquals(emptyList(), d.phone.loud(), "nothing loud in the shade")
                assertEquals(setOf("fresh_full", "pump_fault", "grey_full"),
                    d.phone.posts.filter { it.silent }.map { it.id }.toSet(), "every alert up posted silently")
                assertTrue(d.phone.posts.single { it.id == "grey_full" }.acked, "the acknowledged one without its button")
                assertTrue("left_over" in d.phone.cancels, "ours with no record withdrawn")
                assertTrue(Files.isRegularFile(cache.resolve(RemoteBastion.FILE)), "delivery.json written")
                assertEquals(setOf("fresh_full", "pump_fault"), rb.raised().map { it.id }.toSet())

                store.raise("sump_high", Severity.NORMAL, "Sump high", "on")
                synced(rb, store)
                ticks(rb, 5)
                assertEquals(1, d.said("sump_high").size, "a raise after contact is said once")
                assertEquals(1, d.phone.loud("sump_high").size)
                assertEquals(1, d.glasses.size, "and nothing else was said")
            }
        }
    }

    @Test
    fun eventsAfterContactAreEachSaidOnce() {
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        val d = DeliveryRig()
        BastionHost(dir, store).use { h ->
            client(h.port, d.delivery) { rb ->
                synced(rb, store)
                store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                synced(rb, store)
                assertEquals(listOf("Fresh tank nearly full · 91 · 12:00"), d.said("fresh_full").map { it.body })
                clock.addAndGet(600_000)
                store.dueRepeats(clock.get(), mapOf("fresh_full" to 600_000L))
                synced(rb, store)
                assertEquals(2, d.said("fresh_full").size, "the repeat")
                assertEquals(2, d.phone.loud("fresh_full").size)
                val ok = rb.ack("fresh_full", 1)
                assertTrue(ok.ok, "acknowledged: $ok")
                synced(rb, store)
                assertTrue(d.phone.posts.last { it.id == "fresh_full" }.let { it.silent && it.acked }, "the shade updated silently")
                assertEquals(2, d.said("fresh_full").size, "an acknowledgement says nothing on the glasses")
                clock.addAndGet(60_000)
                store.clear("fresh_full")
                synced(rb, store)
                assertEquals("cleared · Fresh tank nearly full · 12:11", d.said("fresh_full").last().body)
                assertTrue("fresh_full" in d.phone.cancels)
                store.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")
                synced(rb, store)
                assertTrue(d.said("pump_fault").single().body.startsWith("urgent · Water pump fault · on · "))
                ticks(rb, 5)
                assertEquals(3, d.said("fresh_full").size, "raise, repeat, cleared: once each")
                assertEquals(1, d.said("pump_fault").size)
                assertEquals(3, d.phone.loud().size)
            }
        }
    }

    /** A push lost on the way (the host's wrapper drops one): the next push shows the gap and asks the table, which
     *  carries the lost event — with a one-minute ping, only the gap's ask can have recovered it. */
    @Test
    fun aLostPushIsRecoveredThroughTheTableOnce() {
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        val d = DeliveryRig()
        BastionHost(dir, store).use { h ->
            client(h.port, d.delivery, pingMs = 60_000) { rb ->
                synced(rb, store)
                val asks = h.op("table")
                h.dropPushSeq = store.seq + 2
                store.raise("a_one", Severity.NORMAL, "one", "1")
                store.raise("b_two", Severity.NORMAL, "two", "2")          // its push is dropped
                store.raise("c_three", Severity.NORMAL, "three", "3")
                synced(rb, store)
                assertTrue(h.op("table") > asks, "the gap asked the table")
                for (id in listOf("a_one", "b_two", "c_three")) assertEquals(1, d.said(id).size, "$id said once")
                assertEquals(3, d.phone.loud().size)
            }
        }
    }

    /** The phone was away while the PC restarted (its ring empty): the answer is truncated, same epoch; the table
     *  reconcile delivers what was not said and nothing that was. */
    @Test
    fun aTruncatedAnswerOfTheSameEpochReadsTheSaidMarks() {
        val file = dir.resolve("alerts.json")
        val store1 = AlertStore(file, clock::get)
        val d = DeliveryRig()
        BastionHost(dir, store1).use { h ->
            client(h.port, d.delivery) { rb ->
                synced(rb, store1)
                store1.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                synced(rb, store1)
            }
        }
        store1.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")      // while the phone is away
        val store2 = AlertStore(file, clock::get)                                   // the PC restarts: floor = seq
        assertEquals(store1.epoch, store2.epoch)
        BastionHost(dir, store2).use { h ->
            client(h.port, d.delivery) { rb ->
                synced(rb, store2)
                ticks(rb, 5)
            }
        }
        assertEquals(1, d.said("fresh_full").size, "said before the gap, not again")
        assertEquals(1, d.said("pump_fault").size, "missed in the gap, said once from the table")
        assertEquals(2, d.phone.loud().size)
    }

    /** The PC's store is a new one (another epoch): an alert this phone held up is carried over by id without a
     *  word (its shade notification re-posted silently); one it did not know is said once. */
    @Test
    fun anotherEpochCarriesOverById() {
        val d = DeliveryRig()
        val store1 = AlertStore(dir.resolve("one").also { Files.createDirectories(it) }.resolve("alerts.json"), clock::get)
        BastionHost(dir, store1).use { h ->
            client(h.port, d.delivery) { rb ->
                synced(rb, store1)
                store1.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                synced(rb, store1)
            }
        }
        clock.addAndGet(1_000)
        val store2 = AlertStore(dir.resolve("two").also { Files.createDirectories(it) }.resolve("alerts.json"), clock::get)
        assertTrue(store2.epoch != store1.epoch)
        store2.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "92")
        store2.raise("grey_full", Severity.NORMAL, "Grey tank nearly full", "88")
        BastionHost(dir, store2).use { h ->
            client(h.port, d.delivery) { rb ->
                synced(rb, store2)
                ticks(rb, 5)
            }
        }
        assertEquals(1, d.said("fresh_full").size, "carried over: not said again")
        assertEquals(1, d.phone.posts.count { it.id == "fresh_full" && it.silent && it.kind == AlertDelivery.Kind.ADOPT },
            "its notification re-posted silently")
        assertEquals(1, d.said("grey_full").size, "a new one is said once")
    }

    /** The APK restarts: delivery.json is loaded, so it is not a first contact; what happened meanwhile is said once. */
    @Test
    fun anApkRestartSaysWhatHappenedMeanwhileOnce() {
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        val d = DeliveryRig()
        BastionHost(dir, store).use { h ->
            client(h.port, d.delivery) { rb ->
                synced(rb, store)
                store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                synced(rb, store)
            }
            store.raise("grey_full", Severity.NORMAL, "Grey tank nearly full", "88")
            store.clear("fresh_full")
            client(h.port, d.delivery) { rb ->
                synced(rb, store)
                ticks(rb, 5)
            }
        }
        assertEquals(listOf("Fresh tank nearly full · 91 · 12:00", "cleared · Fresh tank nearly full · 12:00"),
            d.said("fresh_full").map { it.body }, "its raise before, its clear after the restart: once each")
        assertEquals(1, d.said("grey_full").size, "raised meanwhile: said once")
    }
}
