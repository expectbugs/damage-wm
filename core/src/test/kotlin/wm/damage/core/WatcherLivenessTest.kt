package wm.damage.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import wm.damage.core.bastion.AlertRule
import wm.damage.core.bastion.AlertState
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionWatcher
import wm.damage.core.bastion.HaReader
import wm.damage.core.bastion.RuleKind
import wm.damage.core.bastion.Severity
import wm.damage.core.util.Log

/**
 * Build W, package B: the watcher's liveness WITHOUT timeouts (R6 T7). A stand-in hub on loopback port 0 whose
 * handler blocks on a latch; the clock is injected and the ticks are called directly. Pinned: a tick skips while a
 * round is in flight (W9), a round stuck for 60 s is SAID and one more goes beside it, never a third; no good answer
 * for `haSilentMin` raises the silent alert and the next answer clears it; a late answer never overwrites a newer
 * one (W8). Made-up entity ids and a made-up token only.
 */
class WatcherLivenessTest {

    /** A stand-in hub: per path a queue of answers (the last repeats); an answer may wait on a latch first. */
    private class Hub : AutoCloseable {
        class Answer(val status: Int, val body: String, val gate: CountDownLatch? = null)

        val requests = CopyOnWriteArrayList<String>()
        val answers = ConcurrentHashMap<String, List<Answer>>()
        private val served = ConcurrentHashMap<String, AtomicInteger>()
        private val pool = Executors.newCachedThreadPool()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.executor = pool                      // a blocked answer must not hold the others back
            server.createContext("/") { x ->
                val path = x.requestURI.rawPath
                requests += "${x.requestMethod} $path"
                val list = answers[path] ?: listOf(Answer(404, "{}"))
                val n = served.computeIfAbsent(path) { AtomicInteger() }.getAndIncrement()
                val a = list[minOf(n, list.size - 1)]
                a.gate?.await()                          // the stuck hub: released by the test
                val b = a.body.toByteArray()
                x.sendResponseHeaders(a.status, if (b.isEmpty()) -1 else b.size.toLong())
                x.responseBody.use { it.write(b) }
            }
            server.start()
        }

        fun count(path: String) = requests.count { it.endsWith(" $path") }
        val url: String get() = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        Log.removeSink(sink)
        scope.cancel()
    }

    private fun state(entity: String, value: String) =
        "{\"entity_id\":\"$entity\",\"state\":\"$value\",\"attributes\":{}," +
            "\"last_reported\":\"2026-11-13T10:00:00+00:00\",\"last_updated\":\"2026-11-13T09:00:00+00:00\"}"

    private val rule = AlertRule(id = "fresh_full", entity = ENTITY, kind = RuleKind.ABOVE, at = 90.0, clearAt = 80.0,
        text = "Fresh tank nearly full", severity = Severity.NORMAL)

    /** Gives a round that should NOT have started the time to reach the hub. */
    private suspend fun settle() = delay(300)

    /** T7 + W9: one round at a time; at 60 s in flight the line says so and ONE more goes beside it, never a
     *  third; five minutes with no answer raise the silent alert; the answer clears it. The token in no line. */
    @Test
    fun aStuckRoundIsSaidAndOneMoreGoesBesideItNeverAThird(): Unit = runBlocking {
        Log.addSink(sink)
        val dir = Files.createTempDirectory("watcher-live")
        val stuck = CountDownLatch(1)
        Hub().use { hub ->
            try {
                hub.answers[HaReader.HEALTH] = listOf(Hub.Answer(200, "{\"message\":\"API running.\"}", stuck))
                hub.answers[HaReader.STATES + ENTITY] = listOf(Hub.Answer(200, state(ENTITY, "50")))
                val clock = AtomicLong(T0)
                val store = AlertStore(dir.resolve("alerts.json"), clock::get)
                val w = BastionWatcher(HaReader(hub.url, TOKEN, listOf(ENTITY)), listOf(rule), store, scope,
                    clock::get, pollSec = 10, silentMin = 5)

                w.tick()
                waitUntil(5_000, 5, { "the first round reached the hub: ${hub.requests}" }) { hub.count(HaReader.HEALTH) == 1 }
                clock.addAndGet(10_000)
                w.tick()
                settle()
                assertEquals(1, hub.count(HaReader.HEALTH), "a tick while a round is in flight skips (W9)")
                assertFalse("in flight" in w.stateLine(), "10 s in flight is not said yet: ${w.stateLine()}")

                clock.set(T0 + 61_000)
                w.tick()
                assertTrue("hub request in flight 61s" in w.stateLine(), "a round stuck 60 s is said: ${w.stateLine()}")
                waitUntil(5_000, 5, { "one more round went beside the stuck one: ${hub.requests}" }) { hub.count(HaReader.HEALTH) == 2 }
                assertEquals(2, w.roundsInFlight)

                clock.set(T0 + 71_000)
                w.tick()
                settle()
                assertEquals(2, hub.count(HaReader.HEALTH), "two in flight at most: no third request")
                assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.SILENT), "silent only after haSilentMin")

                clock.set(T0 + 5 * 60_000)
                w.tick()
                assertEquals(AlertState.RAISED, store.stateOf(BastionWatcher.SILENT), "five minutes with no answer is said")
                val silent = store.table().single { it.id == BastionWatcher.SILENT }
                assertTrue("in flight" in silent.text && silent.builtIn, "the silent alert says why: ${silent.text}")

                stuck.countDown()
                waitUntil(5_000, 5, { "both rounds ended: ${w.roundsInFlight} in flight" }) { w.roundsInFlight == 0 }
                assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.SILENT), "the next good answer clears it")
                assertFalse("in flight" in w.stateLine(), "nothing in flight is said: ${w.stateLine()}")
                assertTrue(hub.requests.all { it.startsWith("GET ") }, "only reads: ${hub.requests}")

                val said = logs + store.records().map { it.text } + w.stateLine()
                assertTrue(said.none { TOKEN in it }, "the token is in no log line, alert text or state line")
            } finally {
                stuck.countDown()
            }
        }
    }

    /** T7 + W8: a round stuck on its entity read answers AFTER a newer round applied its reading; the late
     *  reading is dropped (it would clear an alert the newer reading holds up). */
    @Test
    fun aLateAnswerNeverOverwritesANewerOne(): Unit = runBlocking {
        Log.addSink(sink)
        val dir = Files.createTempDirectory("watcher-late")
        val stuck = CountDownLatch(1)
        Hub().use { hub ->
            try {
                hub.answers[HaReader.HEALTH] = listOf(Hub.Answer(200, "{\"message\":\"API running.\"}"))
                // the first read is stuck and will say 10 (a clear); every later read says 95 (still full)
                hub.answers[HaReader.STATES + ENTITY] = listOf(
                    Hub.Answer(200, state(ENTITY, "10"), stuck), Hub.Answer(200, state(ENTITY, "95")))
                val clock = AtomicLong(T0)
                val store = AlertStore(dir.resolve("alerts.json"), clock::get)
                store.raise(rule.id, rule.severity, rule.text, "95")          // up before this start
                val w = BastionWatcher(HaReader(hub.url, TOKEN, listOf(ENTITY)), listOf(rule), store, scope,
                    clock::get, pollSec = 10, silentMin = 5)

                w.tick()
                waitUntil(5_000, 5, { "round 1 is stuck on its entity read: ${hub.requests}" }) {
                    hub.count(HaReader.STATES + ENTITY) == 1
                }
                clock.set(T0 + 61_000)
                w.tick()
                waitUntil(5_000, 5, { "round 2 ended, round 1 still stuck: ${w.roundsInFlight}" }) {
                    hub.count(HaReader.STATES + ENTITY) == 2 && w.roundsInFlight == 1
                }
                assertEquals(AlertState.RAISED, store.stateOf(rule.id), "round 2's reading (95) holds the alert up")

                stuck.countDown()
                waitUntil(5_000, 5, { "round 1 ended: ${w.roundsInFlight}" }) { w.roundsInFlight == 0 }
                assertEquals(AlertState.RAISED, store.stateOf(rule.id),
                    "round 1's late reading (10) must not clear what round 2's newer reading holds (W8)")
                assertTrue(logs.any { "late answer for $ENTITY (round 1) dropped" in it }, "the drop is said")
            } finally {
                stuck.countDown()
            }
        }
    }

    /** Fix round 1 (review 1, finding 2; R1.2(a)): the hub answers its health check but one entity's reads keep
     *  failing (HTTP 500). Its rule reads nothing and holds, so after `haSilentMin` of failed reads the entity alert
     *  is raised with the failure's words; its next good read clears it. The hub-silent alert stays clear. */
    @Test
    fun anEntityWhoseReadsKeepFailingIsSaidAfterSilentMin(): Unit = runBlocking {
        val dir = Files.createTempDirectory("watcher-entity")
        Hub().use { hub ->
            hub.answers[HaReader.HEALTH] = listOf(Hub.Answer(200, "{\"message\":\"API running.\"}"))
            hub.answers[HaReader.STATES + ENTITY] = listOf(Hub.Answer(500, "{}"))
            val clock = AtomicLong(T0)
            val store = AlertStore(dir.resolve("alerts.json"), clock::get)
            val w = BastionWatcher(HaReader(hub.url, TOKEN, listOf(ENTITY)), listOf(rule), store, scope,
                clock::get, pollSec = 10, silentMin = 5)
            val alert = BastionWatcher.ENTITY_PREFIX + ENTITY
            suspend fun roundAt(ms: Long) {
                clock.set(T0 + ms)
                w.tick()
                waitUntil(5_000, 5, { "the round at $ms ended: ${w.roundsInFlight} in flight" }) { w.roundsInFlight == 0 }
            }
            roundAt(0)
            roundAt(4 * 60_000)
            assertEquals(AlertState.CLEAR, store.stateOf(alert), "four minutes of failed reads: not yet")
            roundAt(5 * 60_000)
            assertEquals(AlertState.RAISED, store.stateOf(alert), "five minutes of failed reads are said")
            val rec = store.table().single { it.id == alert }
            assertTrue("500" in rec.text && "fresh_full" in rec.text && rec.builtIn, "the alert says why: ${rec.text}")
            assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.SILENT), "the hub answers: not silent")
            hub.answers[HaReader.STATES + ENTITY] = listOf(Hub.Answer(200, state(ENTITY, "50")))
            roundAt(5 * 60_000 + 10_000)
            assertEquals(AlertState.CLEAR, store.stateOf(alert), "the next good read clears it")
            assertTrue(hub.requests.all { it.startsWith("GET ") }, "only reads: ${hub.requests}")
        }
    }

    companion object {
        const val ENTITY = "sensor.fresh_tank"
        const val TOKEN = "made-up-test-token-0123456789"
        const val T0 = 1_800_000_000_000L
    }
}
