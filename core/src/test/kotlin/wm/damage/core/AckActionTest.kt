package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import wm.damage.core.bastion.AlertState
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionRows
import wm.damage.core.bastion.RemoteBastion
import wm.damage.core.bastion.Severity
import wm.damage.core.shell.HostSetting
import wm.damage.core.shell.ShellSettings

/**
 * Build W, package C (the spec's new T23; the lead's decision B.3: acknowledge is an action on the alert): the
 * Bastion category's `Acknowledge` row and a direct `RemoteBastion.ack` reach the same host op; a stale episode is
 * answered and changes nothing; with the channel down the row says "not acknowledged — home not answering", and
 * nothing is queued or retried when home comes back. A real shell (for the row's answer notice), a real host.
 */
class AckActionTest {

    private val dir = Files.createTempDirectory("bastion-ack-action")
    private val clock = AtomicLong(NOON)

    @Test
    fun theRowAndTheDirectAckAreOneHostOp(): Unit = runBlocking {
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        ShellRig(ShellSettings(notifyDamage = false)).use { r ->
            r.shell.start(); r.settle("start")
            BastionHost(dir, store).use { h ->
                val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val d = DeliveryRig()
                val rb = remote(h.port, cs, dir.resolve("cache"), d.delivery)
                try {
                    val (name, rows) = BastionRows.category(r.shell, listOf(HostSetting("Phone · alerts", listOf("on", "off"), { "on" }, {})), rb, cs)
                    assertEquals("Bastion", name)
                    assertEquals(listOf("Watcher", "Notify · alerts", "Notify · cleared", "Phone · alerts", "Acknowledge"),
                        rows.map { it.name }, "the display-only Watcher row first (fix round 1, R1.2(b))")
                    val ack = rows.last()
                    synced(rb, store)
                    assertEquals(listOf(BastionRows.NONE), ack.options(), "nothing raised: nothing to choose")

                    store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                    store.raise("grey_full", Severity.NORMAL, "Grey tank nearly full", "88")
                    synced(rb, store)
                    assertEquals(listOf(BastionRows.NONE, "all raised (2)"), ack.options())
                    assertEquals(BastionRows.NONE, ack.current(), "it rests at —: an action, not a value")
                    r.shell.services.runOnShell { ack.apply("all raised (2)") }
                    waitUntil(10_000, 10, { "the row's answer (${r.posted()})" }) { r.posted().any { it.body == "acknowledged 2" } }
                    assertEquals(2, h.op("ack"), "one host op per alert")
                    assertEquals(AlertState.ACKED, store.stateOf("fresh_full"))
                    assertEquals(AlertState.ACKED, store.stateOf("grey_full"))
                    assertTrue(r.posted().single { it.body == "acknowledged 2" }.answer, "an answer to his gesture")

                    store.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")
                    synced(rb, store)
                    val direct = rb.ack("pump_fault", 1)
                    assertTrue(direct.ok && direct.state == AlertState.ACKED, "the direct ack: $direct")
                    assertEquals(3, h.op("ack"), "the same host op as the row's")
                    val seq = store.seq
                    val stale = rb.ack("pump_fault", 7)
                    assertFalse(stale.ok, "another episode: $stale")
                    assertEquals("that episode has ended", stale.reason)
                    assertEquals(seq, store.seq, "a stale ack changes nothing")
                    assertEquals(AlertState.ACKED, store.stateOf("pump_fault"))
                    assertEquals(0, r.urgent.get())
                } finally { rb.close(); cs.cancel() }
            }
            r.shell.stop()
        }
    }

    /** Fix round 1 (review 1, finding 2; R1.2(b)): the category's first row shows the PC watcher's state line from
     *  its last answer, part by part while adjusting; with the channel down it says "home not answering"; when home
     *  answers again, the line again. A tap keeps nothing. */
    @Test
    fun theWatcherRowShowsThePcsStateLine(): Unit = runBlocking {
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        ShellRig(ShellSettings(notifyDamage = false)).use { r ->
            val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val first = BastionHost(dir, store)
            val port = first.port
            val rb = remote(port, cs, dir.resolve("cache"), DeliveryRig().delivery)
            try {
                val watcher = BastionRows.category(r.shell, emptyList(), rb, cs).second.first()
                assertEquals(BastionRows.WATCHER, watcher.name)
                synced(rb, store)
                val line = "1 rule(s) · hub answered 12:00"                     // BastionHost's state line
                waitUntil(10_000, 10, { "the line (${watcher.current()})" }) { watcher.current() == line }
                assertEquals(listOf(line, "1 rule(s)", "hub answered 12:00"), watcher.options(), "each part whole")
                watcher.apply("hub answered 12:00")
                assertEquals(line, watcher.current(), "a tap keeps nothing")
                first.close()                                                 // home stops answering
                waitUntil(10_000, 10, { "the row says home is down (${watcher.current()})" }) {
                    watcher.current() == RemoteBastion.HOME_DOWN }
                BastionHost(dir, store, port).use { _ ->                      // home answers again, same port
                    waitUntil(10_000, 10, { "the line again (${watcher.current()})" }) { watcher.current() == line }
                }
            } finally { rb.close(); cs.cancel() }
        }
    }

    @Test
    fun withTheChannelDownNothingIsQueuedOrRetried(): Unit = runBlocking {
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        ShellRig(ShellSettings(notifyDamage = false)).use { r ->
            r.shell.start(); r.settle("start")
            val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val d = DeliveryRig()
            val first = BastionHost(dir, store)
            val port = first.port
            val rb = remote(port, cs, dir.resolve("cache"), d.delivery)
            try {
                val ack = BastionRows.category(r.shell, emptyList(), rb, cs).second.last()
                synced(rb, store)
                store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
                synced(rb, store)
                first.close()                                                 // home stops answering
                waitUntil(10_000, 10, { "the channel is down (${rb.stateLine()})" }) { rb.stateLine().startsWith("PC unreachable") }
                assertEquals(listOf(BastionRows.NONE, "all raised (1)"), ack.options(), "the copy still knows it is raised")
                r.shell.services.runOnShell { ack.apply("all raised (1)") }
                waitUntil(10_000, 10, { "the row's answer (${r.posted()})" }) {
                    r.posted().any { it.body == "not acknowledged — home not answering" } }
                BastionHost(dir, store, port).use { again ->                  // home answers again, same port
                    waitUntil(10_000, 10, { "the channel is back and asked the table (${rb.stateLine()})" }) { again.op("table") > 0 }
                    ticks(rb, 10)
                    assertEquals(0, again.op("ack"), "nothing was queued: no ack reaches home later")
                    assertEquals(AlertState.RAISED, store.stateOf("fresh_full"), "still raised")
                }
            } finally { rb.close(); cs.cancel() }
            r.shell.stop()
        }
    }
}
