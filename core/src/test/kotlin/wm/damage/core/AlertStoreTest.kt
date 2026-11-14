package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import wm.damage.core.bastion.AckResult
import wm.damage.core.bastion.AlertEvent
import wm.damage.core.bastion.AlertRecord
import wm.damage.core.bastion.AlertState
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.EventKind
import wm.damage.core.bastion.Severity
import wm.damage.core.bastion.StoreAnswer
import wm.damage.core.util.Log

/**
 * Build W, package A (R6 T6 as amended; dec. B.1, B.3; the spec's §2(b)): the alert store's states, episodes,
 * repeats, its persisted epoch and sequence, the `.bad` path, the failed save, and every `table {since, epoch}`
 * answer. Temp directories and made-up ids only.
 */
class AlertStoreTest {

    private val dir: Path = Files.createTempDirectory("damage-alerts")
    private val file: Path = dir.resolve("alerts.json")
    private var now = 1_000_000L
    private val clock = { now }
    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }

    @After
    fun tearDown() {
        Log.removeSink(sink)
        dir.toFile().walkTopDown().forEach { it.setWritable(true) }
        dir.toFile().deleteRecursively()
    }

    private fun store() = AlertStore(file, clock)

    @Test
    fun statesAndEpisodes() {
        val s = store()
        assertEquals(0L, s.seq)
        val r1 = assertNotNull(s.raise("fresh_low", Severity.NORMAL, "Fresh water low", "12"))
        assertEquals(EventKind.RAISED, r1.kind)
        assertEquals(1L, r1.episode)
        assertNull(s.raise("fresh_low", Severity.NORMAL, "again", "11"), "a raise of an alert that is up changes nothing")
        assertEquals(EventKind.CLEARED, assertNotNull(s.clear("fresh_low")).kind)
        assertNull(s.clear("fresh_low"))
        assertEquals(2L, assertNotNull(s.raise("fresh_low", Severity.NORMAL, "Fresh water low", "10")).episode)
        assertEquals(3L, s.seq)
        assertEquals(listOf("fresh_low"), s.table().map { it.id })
        assertEquals(AlertState.RAISED, s.stateOf("fresh_low"))
        assertEquals(AlertState.CLEAR, s.stateOf("never_seen"))
    }

    @Test
    fun anAckNamesItsEpisode() {
        val s = store()
        s.raise("pump", Severity.URGENT, "Pump fault", "on")
        assertEquals(AckResult(false, AlertState.RAISED, "that episode has ended"), s.ack("pump", 7))
        assertEquals(AlertState.RAISED, s.stateOf("pump"), "a stale-episode ack changed the state")
        val seq = s.seq
        assertEquals(AckResult(true, AlertState.ACKED, null), s.ack("pump", 1))
        assertEquals(seq + 1, s.seq)
        assertEquals(AckResult(true, AlertState.ACKED, null), s.ack("pump", 1), "the same ack again: the same answer")
        assertEquals(seq + 1, s.seq, "and no second event")
        assertTrue(s.table().single().acknowledged)
        s.clear("pump")
        assertEquals(AckResult(false, AlertState.CLEAR, "that episode has ended"), s.ack("pump", 1))
        assertEquals(AckResult(false, AlertState.CLEAR, "that episode has ended"), s.ack("nothing", 1))
    }

    @Test
    fun repeatsFollowSeverityAndNeverWhileAcknowledged() {
        val s = store()
        val t0 = now
        s.raise("n0", Severity.NORMAL, "said once", "")
        s.raise("n1", Severity.NORMAL, "every minute", "")
        s.raise("u", Severity.URGENT, "urgent", "")
        val every = mapOf("n0" to 0L, "n1" to 60_000L, "u" to 60_000L)  // the urgent rule's own repeat is ignored
        assertEquals(emptyList(), s.dueRepeats(t0 + 59_999, every).map { it.id })
        assertEquals(listOf("n1"), s.dueRepeats(t0 + 60_000, every).map { it.id })
        assertEquals(1, s.table().first { it.id == "n1" }.repeatNo)
        assertEquals(emptyList(), s.dueRepeats(t0 + 119_999, every).map { it.id })
        assertEquals(listOf("n1"), s.dueRepeats(t0 + 120_000, every).map { it.id })
        assertEquals(listOf("u"), s.dueRepeats(t0 + 600_000, every).filter { it.id != "n1" }.map { it.id },
            "urgent repeats at ten minutes whatever its repeatMin")
        assertEquals(listOf("u"), s.dueRepeats(t0 + 1_200_000, mapOf()).map { it.id }, "and with no repeat given")
        assertTrue(s.dueRepeats(t0 + 100_000_000, mapOf("n0" to 0L)).none { it.id == "n0" }, "repeatMin 0 never repeats")
        // acknowledged: no more repeats, however long
        s.ack("u", 1)
        s.ack("n1", 1)
        assertEquals(emptyList(), s.dueRepeats(t0 + 1_000_000_000, every), "a repeat fired while acknowledged")
        val ev = s.raise("x", Severity.URGENT, "x", "")
        assertEquals(0, assertNotNull(ev).repeatNo)
    }

    @Test
    fun theEpochAndTheSequenceSurviveAReload() {
        val first = store()
        val epoch = first.epoch
        assertEquals(now, epoch)
        first.raise("a", Severity.NORMAL, "A", "1")
        first.clear("a")
        first.raise("a", Severity.NORMAL, "A", "2")
        first.raise("b", Severity.URGENT, "B", "")
        first.ack("b", 1)
        val seq = first.seq
        now += 3_600_000
        val second = store()
        assertNull(second.loadProblem)
        assertEquals(epoch, second.epoch, "a reload minted a new epoch")
        assertEquals(seq, second.seq, "the sequence was not continued")
        assertEquals(seq, second.floor, "the ring is empty after a reload: floor = the loaded seq")
        assertEquals(listOf("a" to 2L, "b" to 1L), second.table().map { it.id to it.episode })
        assertEquals(AlertState.ACKED, second.stateOf("b"))
        // the episode is the store's: an ack of the old episode is stale; the next raise is episode 3
        assertFalse(second.ack("a", 1).ok)
        assertTrue(second.ack("a", 2).ok)
        second.clear("a")
        assertEquals(3L, assertNotNull(second.raise("a", Severity.NORMAL, "A", "3")).episode)
        assertEquals(seq + 3, second.seq)
        assertEquals(epoch, store().epoch)
    }

    @Test
    fun anUnreadableFileIsMovedAsideAndANewEpochStarts() {
        val old = store()
        old.raise("a", Severity.NORMAL, "A", "")
        Files.writeString(file, "{\"epoch\": 5, \"seq\": oops")
        Log.addSink(sink)
        now += 1_000
        val s = store()
        assertNotNull(s.loadProblem)
        assertTrue(Files.exists(dir.resolve("alerts.json.bad")))
        assertEquals("{\"epoch\": 5, \"seq\": oops", Files.readString(dir.resolve("alerts.json.bad")))
        assertNotEquals(old.epoch, s.epoch)
        assertEquals(now, s.epoch)
        assertEquals(0L, s.seq)
        assertEquals(emptyList(), s.table())
        assertTrue(logs.any { it.startsWith("ERROR") && "alerts.json.bad" in it }, "the move was not said: $logs")
        assertEquals(s.epoch, store().epoch, "the new epoch was written at once")
    }

    @Test
    fun aFailedSaveIsSaidOnceRaisesTheStoreAlertAndIsRetried() {
        if (System.getProperty("user.name") == "root") return      // root writes through a read-only folder
        val ro = Files.createDirectory(dir.resolve("read-only"))
        val f = ro.resolve("alerts.json")
        assertTrue(ro.toFile().setWritable(false, false))
        Log.addSink(sink)
        val s = AlertStore(f, clock)
        val seen = CopyOnWriteArrayList<AlertEvent>()
        s.addListener { seen += it }
        assertEquals(AlertState.RAISED, s.stateOf(AlertStore.STORE_ALERT), "the failed first save is unsaid")
        assertTrue(s.table().single().builtIn)
        s.raise("a", Severity.NORMAL, "A", "")
        s.raise("b", Severity.NORMAL, "B", "")
        assertEquals(1, logs.count { it.startsWith("ERROR") && "not saved" in it }, "said once per run of failures: $logs")
        assertFalse(Files.exists(f))
        // the folder becomes writable: the next change saves and clears the store alert
        assertTrue(ro.toFile().setWritable(true, false))
        s.clear("b")
        assertEquals(AlertState.CLEAR, s.stateOf(AlertStore.STORE_ALERT))
        assertEquals(listOf(EventKind.RAISED to "a", EventKind.RAISED to "b", EventKind.CLEARED to "b",
            EventKind.CLEARED to AlertStore.STORE_ALERT), seen.map { it.kind to it.id })
        val reloaded = AlertStore(f, clock)
        assertEquals(s.epoch, reloaded.epoch)
        assertEquals(s.seq, reloaded.seq)
        assertEquals(listOf("a"), reloaded.table().map { it.id })
        // and a new run of failures is said again
        assertTrue(ro.toFile().setWritable(false, false))
        s.raise("c", Severity.NORMAL, "C", "")
        assertEquals(2, logs.count { it.startsWith("ERROR") && "not saved" in it })
        assertEquals(AlertState.RAISED, s.stateOf(AlertStore.STORE_ALERT))
        assertTrue(ro.toFile().setWritable(true, false))
    }

    @Test
    fun retireClearsAnAlertWhoseRuleIsGone() {
        val s = store()
        s.raise("kept", Severity.NORMAL, "K", "")
        s.raise("gone", Severity.NORMAL, "G", "")
        s.raise("acked_gone", Severity.NORMAL, "G", "")
        s.ack("acked_gone", 1)
        s.raise("watcher.hub", Severity.NORMAL, "Hub not answering", "", builtIn = true)
        val out = s.retire(setOf("kept"))
        assertEquals(listOf("gone", "acked_gone"), out.map { it.id })
        assertTrue(out.all { it.kind == EventKind.CLEARED })
        assertEquals(listOf("kept", "watcher.hub"), s.table().map { it.id })
    }

    @Test
    fun listenersHearEveryEventInSeqOrder() {
        val s = store()
        val seen = CopyOnWriteArrayList<Long>()
        s.addListener { seen += it.seq }
        s.addListener { throw IllegalStateException("a listener that fails does not stop the others") }
        Log.addSink(sink)
        for (i in 0 until 5) {
            s.raise("a$i", Severity.URGENT, "A", "")
            if (i % 2 == 0) s.ack("a$i", 1)
        }
        s.dueRepeats(now + 600_000, emptyMap())
        s.retire(emptySet())
        assertEquals((1L..s.seq).toList(), seen.toList())
        assertTrue(logs.any { it.startsWith("ERROR") && "listener failed" in it })
    }

    @Test
    fun everyTableAnswer() {
        val s = store()
        s.raise("a", Severity.NORMAL, "A", "")
        s.raise("b", Severity.NORMAL, "B", "")
        s.clear("b")
        val e = s.epoch
        val big = s.seq                                  // 3
        // first contact: changed, not truncated, no events, the table
        s.answer(0, 0).let {
            assertTrue(it.changed); assertFalse(it.truncated); assertEquals(0, it.events.size)
            assertEquals(listOf("a"), it.table!!.map { r -> r.id })
        }
        // another epoch: the store was recreated
        s.answer(big, e + 1).let { assertTrue(it.changed); assertTrue(it.truncated); assertEquals(0, it.events.size); assertNotNull(it.table) }
        // up to date: nothing changed, no table
        s.answer(big, e).let { assertFalse(it.changed); assertFalse(it.truncated); assertEquals(0, it.events.size); assertNull(it.table) }
        // inside the ring: the events after since, and the table
        s.answer(1, e).let {
            assertTrue(it.changed); assertFalse(it.truncated)
            assertEquals(listOf(2L, 3L), it.events.map { ev -> ev.seq }); assertNotNull(it.table)
        }
        // ahead of the store: truncated with the table, said
        Log.addSink(sink)
        s.answer(big + 5, e).let { assertTrue(it.changed); assertTrue(it.truncated); assertEquals(0, it.events.size); assertNotNull(it.table) }
        assertTrue(logs.any { it.startsWith("WARN") && "ahead of the store" in it })
        // a restart: the ring is empty, so anything older than the loaded seq is truncated
        val again = store()
        again.answer(1, e).let { assertTrue(it.changed); assertTrue(it.truncated); assertEquals(0, it.events.size); assertNotNull(it.table) }
        again.answer(big, e).let { assertFalse(it.changed) }
        again.raise("c", Severity.NORMAL, "C", "")
        again.answer(big, e).let { assertFalse(it.truncated); assertEquals(listOf("c"), it.events.map { ev -> ev.id }) }
    }

    @Test
    fun theRingAnswersForItsLastTwoHundredEvents() {
        val s = store()
        repeat(AlertStore.RING_CAP + 30) { i -> if (i % 2 == 0) s.raise("a", Severity.NORMAL, "A", "") else s.clear("a") }
        val seq = s.seq
        assertEquals(seq - AlertStore.RING_CAP, s.floor)
        s.answer(s.floor, s.epoch).let { assertFalse(it.truncated); assertEquals(AlertStore.RING_CAP, it.events.size) }
        s.answer(s.floor - 1, s.epoch).let { assertTrue(it.truncated); assertEquals(0, it.events.size) }
    }

    @Test
    fun theWireShapesRoundTrip() {
        val s = store()
        s.raise("a", Severity.URGENT, "Tank \"low\" · 12 %", "12")
        s.ack("a", 1)
        s.raise("b", Severity.NORMAL, "B", "")
        val ans = s.answer(0, s.epoch).copy(stateLine = "reading 2 entities")
        val back = StoreAnswer.fromJson(ans.toJson())
        assertEquals(ans, back)
        val withEvents = s.answer(1, s.epoch)
        assertEquals(withEvents, StoreAnswer.fromJson(withEvents.toJson()))
        val rec = s.table().first()
        assertEquals(true, rec.toJson()["acknowledged"].toString().toBoolean())
        assertEquals(rec, AlertRecord.fromJson(rec.toJson()))
        val ack = AckResult(false, AlertState.CLEAR, "that episode has ended")
        assertEquals(ack, AckResult.fromJson(ack.toJson()))
        assertNull(s.answer(s.seq, s.epoch).toJson()["table"], "no table when nothing changed")
    }
}
