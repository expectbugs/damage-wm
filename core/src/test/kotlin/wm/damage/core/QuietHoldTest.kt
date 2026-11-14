package wm.damage.core

import java.nio.file.Files
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.RemoteBastion
import wm.damage.core.bastion.Severity
import wm.damage.core.shell.ShellSettings

/**
 * Build W, package C (the spec's new T21; pins "the quiet test removed from `decide`" and "the release not marking
 * said"): the quiet-hours hold of the spec's §2(a), case by case, through the real channel with the phone's wall
 * clock and zone injected. Global `Quiet hours` = 22–07; the phone's clock starts at 23:00 UTC.
 */
class QuietHoldTest {

    private val dir = Files.createTempDirectory("bastion-quiet")
    private val cache = dir.resolve("wincache").resolve("bastion")
    private val hour = 3_600_000L
    private val night = NOON + 11 * hour                                    // 23:00 UTC
    private val morning = NOON + 19 * hour                                  // 07:00 UTC the next day
    private val pcClock = AtomicLong(night)

    private fun rig() = DeliveryRig(ShellSettings(quietHours = "22-07"), start = night)

    private fun <T> client(port: Int, d: DeliveryRig, body: suspend (RemoteBastion) -> T): T = runBlocking {
        val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val rb = remote(port, cs, cache, d.delivery, pingMs = 50)
        try { body(rb) } finally { rb.close(); cs.cancel() }
    }

    @Test
    fun aNormalRaiseIsHeldAndReleasedOnceUrgentPasses() {
        val store = AlertStore(dir.resolve("alerts.json"), pcClock::get)
        val d = rig()
        BastionHost(dir, store).use { h ->
            client(h.port, d) { rb ->
                synced(rb, store)
                store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                synced(rb, store)
                assertEquals(setOf("fresh_full"), rb.held(), "held")
                assertEquals(emptyList(), d.glasses, "inside quiet hours: no glasses notice")
                assertEquals(emptyList(), d.phone.loud(), "and no post")
                pcClock.addAndGet(600_000)
                store.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")
                synced(rb, store)
                assertEquals(1, d.said("pump_fault").size, "urgent passes quiet hours")
                pcClock.addAndGet(600_000)
                store.dueRepeats(pcClock.get(), mapOf("fresh_full" to 600_000L))   // both repeat
                synced(rb, store)
                assertEquals(2, d.said("pump_fault").size, "an urgent repeat at once")
                assertEquals(0, d.said("fresh_full").size, "a normal repeat is held too")
                ticks(rb, 5)
                assertEquals(0, d.said("fresh_full").size)

                d.clock.set(morning)                                        // the window ends
                waitUntil(10_000, 5, { "released (${d.glasses})" }) { d.said("fresh_full").isNotEmpty() }
                ticks(rb, 6)
                assertEquals(listOf("Fresh tank nearly full · 91 · since 23:00"), d.said("fresh_full").map { it.body },
                    "delivered ONCE, saying since when")
                assertEquals(1, d.phone.loud("fresh_full").size)
                assertEquals(emptySet(), rb.held())
                assertEquals(2, d.said("pump_fault").size, "the urgent one is not said again")
            }
        }
    }

    @Test
    fun acknowledgedOrClearedBeforeTheEndSaysNothing() {
        val store = AlertStore(dir.resolve("alerts.json"), pcClock::get)
        val d = rig()
        d.clock.set(night - 2 * hour)                                        // 21:00: outside
        BastionHost(dir, store).use { h ->
            client(h.port, d) { rb ->
                synced(rb, store)
                store.raise("sump_high", Severity.NORMAL, "Sump high", "on")
                synced(rb, store)
                assertEquals(1, d.said("sump_high").size, "said before the window")
                d.clock.set(night)
                store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                store.raise("grey_full", Severity.NORMAL, "Grey tank nearly full", "88")
                synced(rb, store)
                assertEquals(setOf("fresh_full", "grey_full"), rb.held())
                assertTrue(rb.ack("fresh_full", 1).ok)
                store.clear("grey_full")
                store.clear("sump_high")
                synced(rb, store)
                assertEquals(emptySet(), rb.held(), "acknowledged and cleared drop their marks")
                assertEquals(1, d.said("sump_high").size, "a normal clear inside quiet hours is dropped")
                assertTrue("sump_high" in d.phone.cancels && "grey_full" in d.phone.cancels, "…its notification withdrawn")
                d.clock.set(morning)
                ticks(rb, 6)
                assertEquals(1, d.glasses.size, "nothing said at the end: only the raise before the window")
                assertEquals(1, d.phone.loud().size)
            }
        }
    }

    @Test
    fun aRestartInsideKeepsTheMarksAndOneAfterTheEndReleases() {
        val store = AlertStore(dir.resolve("alerts.json"), pcClock::get)
        val d = rig()
        BastionHost(dir, store).use { h ->
            client(h.port, d) { rb ->
                synced(rb, store)
                store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                synced(rb, store)
                assertEquals(setOf("fresh_full"), rb.held())
            }
            client(h.port, d) { rb ->
                assertEquals(setOf("fresh_full"), rb.held(), "the mark is in delivery.json")
                synced(rb, store)
                ticks(rb, 5)
                assertEquals(0, d.glasses.size, "still inside: still held")
            }
            d.clock.set(morning)
            client(h.port, d) { rb ->
                synced(rb, store)
                waitUntil(10_000, 5, { "released after the restart (${d.glasses})" }) { d.glasses.isNotEmpty() }
                ticks(rb, 6)
                assertEquals(listOf("Fresh tank nearly full · 91 · since 23:00"), d.glasses.map { it.body })
                assertEquals(emptySet(), rb.held())
            }
        }
    }

    @Test
    fun aZoneChangeOrTheRowOffReleasesAtTheNextTick() {
        val store = AlertStore(dir.resolve("alerts.json"), pcClock::get)
        val d = rig()
        BastionHost(dir, store).use { h ->
            client(h.port, d) { rb ->
                synced(rb, store)
                store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                synced(rb, store)
                assertEquals(setOf("fresh_full"), rb.held())
                d.zone = ZoneOffset.ofHours(10)                              // 09:00 there: outside the window
                waitUntil(10_000, 5, { "released by the zone (${d.glasses})" }) { d.glasses.isNotEmpty() }
                assertEquals("Fresh tank nearly full · 91 · since 09:00", d.glasses.single().body,
                    "since HH:MM in the zone now")

                d.zone = ZoneOffset.UTC
                store.raise("grey_full", Severity.NORMAL, "Grey tank nearly full", "88")
                synced(rb, store)
                assertEquals(setOf("grey_full"), rb.held())
                d.settings = ShellSettings(quietHours = "off")
                waitUntil(10_000, 5, { "released by the row (${d.glasses})" }) { d.said("grey_full").isNotEmpty() }
                ticks(rb, 6)
                assertEquals(2, d.glasses.size, "each released once")
            }
        }
    }

    /** First contact inside quiet hours: a normal raised alert with no notification in the shade is held, not
     *  posted; one already in the shade is re-posted silently; an urgent one is posted silently. At the end only
     *  the held one is delivered. */
    @Test
    fun firstContactInsideHoldsWhatTheShadeLacks() {
        val store = AlertStore(dir.resolve("alerts.json"), pcClock::get)
        store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
        store.raise("grey_full", Severity.NORMAL, "Grey tank nearly full", "88")
        store.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")
        val d = rig()
        d.phone.shown += "grey_full"
        BastionHost(dir, store).use { h ->
            client(h.port, d) { rb ->
                synced(rb, store)
                assertEquals(setOf("fresh_full"), rb.held(), "only the one the shade lacks")
                assertEquals(setOf("grey_full", "pump_fault"), d.phone.posts.map { it.id }.toSet())
                assertTrue(d.phone.posts.all { it.silent })
                assertEquals(0, d.glasses.size)
                d.clock.set(morning)
                waitUntil(10_000, 5, { "released (${d.glasses})" }) { d.glasses.isNotEmpty() }
                ticks(rb, 6)
                assertEquals(listOf("fresh_full"), d.glasses.map { it.thread }, "only the held one, once")
                assertEquals(listOf("fresh_full"), d.phone.loud().map { it.id })
            }
        }
    }
}
