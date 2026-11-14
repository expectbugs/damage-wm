package wm.damage.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import wm.damage.core.bastion.AlertState
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionWatcher
import wm.damage.core.bastion.EventKind
import wm.damage.core.bastion.HaReader
import wm.damage.core.bastion.PhonePresence

/**
 * Build W, package E (the lead's decisions F.1–F.3) through the running watcher, against a stand-in hub on loopback
 * port 0 with made-up entities: a guard's hub entity joins the reader's path set and is read; the guard ending clears
 * the alert with words saying so and drops the change rule's baseline; the baseline is kept in `alerts.json` across
 * a restart (a guard with no reading meanwhile keeps it) and dropped when the guard is false at the restart; an
 * acknowledge re-takes it; a guard on `damage.phone` follows the presence value (`unknown` never arms); a `withinMin`
 * rule is blind after a start and then compares with the sample from then. The clock is injected; the presence value
 * is driven through its own `observe` (no command runs).
 */
class WatcherGuardTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = AtomicLong(T0)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class Hub : AutoCloseable {
        val states = ConcurrentHashMap<String, String>()
        val requests = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { x ->
                val path = x.requestURI.rawPath
                requests += "${x.requestMethod} $path"
                val entity = path.removePrefix(HaReader.STATES)
                val (code, body) = when {
                    path == HaReader.HEALTH -> 200 to "{\"message\":\"API running.\"}"
                    path.startsWith(HaReader.STATES) && states.containsKey(entity) -> 200 to
                        "{\"entity_id\":\"$entity\",\"state\":\"${states[entity]}\",\"last_reported\":\"r\",\"last_updated\":\"u\"}"
                    else -> 404 to "{}"
                }
                val b = body.toByteArray()
                x.sendResponseHeaders(code, b.size.toLong())
                x.responseBody.use { it.write(b) }
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private fun files(dir: Path, vararg rule: String): Pair<Path, Path> {
        val rules = dir.resolve("bastion-rules.json").also { Files.writeString(it, "{\"rules\":[${rule.joinToString(",")}]}") }
        val token = dir.resolve("hub.token").also { Files.writeString(it, "made-up-test-token") }
        return rules to token
    }

    private fun watcher(hub: Hub, files: Pair<Path, Path>, store: AlertStore, presence: PhonePresence? = null) =
        BastionWatcher.build(hub.url, files.second.toString(), files.first, 10, 60, store, scope, clock::get, presence)
            .also { assertTrue(it.on, it.stateLine()) }

    /** One tick at the clock's time, and its round to the end. */
    private suspend fun round(w: BastionWatcher) {
        w.tick()
        waitUntil(5_000, 5, { "the round ends (${w.stateLine()})" }) { w.roundsInFlight == 0 }
    }

    private fun hhmm(ms: Long) = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private val guarded = """{"id":"leak","entity":"$FRESH","kind":"change","at":3,"text":"Fresh tank",""" +
        """"when":{"entity":"$AWAY","equals":"on"}}"""

    @Test
    fun aGuardIsReadFromTheHubAndItsEndClearsWithWordsSayingSo(): Unit = runBlocking {
        val dir = Files.createTempDirectory("watcher-guard")
        Hub().use { hub ->
            val store = AlertStore(dir.resolve("alerts.json"), clock::get)
            val w = watcher(hub, files(dir, guarded), store)
            hub.states[AWAY] = "on"
            hub.states[FRESH] = "100"
            round(w)
            assertTrue(hub.requests.contains("GET ${HaReader.STATES}$AWAY"), "the guard's entity is read: ${hub.requests}")
            assertTrue(hub.requests.indexOf("GET ${HaReader.STATES}$AWAY") < hub.requests.indexOf("GET ${HaReader.STATES}$FRESH"),
                "the guard is read before the rule it guards")
            assertEquals("100" to T0, store.baselineOf("leak"), "armed: the baseline is kept in the store")
            val seq = store.seq
            clock.addAndGet(5 * MIN)
            hub.states[FRESH] = "96"
            round(w)
            val raised = store.answer(seq, store.epoch).events.single()
            assertEquals(EventKind.RAISED, raised.kind)
            assertEquals("Fresh tank 100 → 96 since ${hhmm(T0)}", raised.text, "the baseline, the value and since when")
            clock.addAndGet(MIN)
            hub.states[AWAY] = "off"
            round(w)
            val cleared = store.answer(raised.seq, store.epoch).events.single()
            assertEquals(EventKind.CLEARED, cleared.kind)
            assertEquals("Fresh tank - check ended: $AWAY is off (the rule runs while $AWAY is on)", cleared.text)
            assertNull(store.baselineOf("leak"), "the guard's end drops the baseline")
            // the guard back on: armed again from the value now, not the old baseline
            clock.addAndGet(MIN)
            hub.states[AWAY] = "on"
            round(w)
            assertEquals("96" to clock.get(), store.baselineOf("leak"))
            assertEquals(AlertState.CLEAR, store.stateOf("leak"))
        }
    }

    @Test
    fun theBaselineSurvivesARestartAndIsDroppedWhenTheGuardIsFalseThere(): Unit = runBlocking {
        val dir = Files.createTempDirectory("watcher-baseline")
        Hub().use { hub ->
            val f = files(dir, guarded)
            val first = AlertStore(dir.resolve("alerts.json"), clock::get)
            hub.states[AWAY] = "on"
            hub.states[FRESH] = "100"
            round(watcher(hub, f, first))
            assertEquals("100" to T0, first.baselineOf("leak"))
            assertEquals(0L, first.seq, "a baseline is no alert change: no event")

            // a restart an hour later: the tank has drained to 97 meanwhile, and the guard reads unavailable at first
            clock.addAndGet(60 * MIN)
            hub.states[FRESH] = "97"
            hub.states[AWAY] = "unavailable"
            val again = AlertStore(dir.resolve("alerts.json"), clock::get)
            assertEquals("100" to T0, again.baselineOf("leak"), "the baseline was saved in alerts.json")
            val w = watcher(hub, f, again)
            round(w)
            assertEquals("100" to T0, again.baselineOf("leak"), "a guard with no reading keeps it")
            assertTrue("rule leak: no reading of its guard $AWAY since" in w.stateLine(), w.stateLine())
            clock.addAndGet(MIN)
            hub.states[AWAY] = "on"
            round(w)
            // judged from the saved 100, not re-armed at 97: 97 is a change of 3
            assertEquals(AlertState.RAISED, again.stateOf("leak"), "the saved baseline is used after the restart")
            assertTrue(again.table().single().text.startsWith("Fresh tank 100 → 97 since"), again.table().single().text)

            // a second restart with the guard off: the baseline is dropped and the alert clears
            clock.addAndGet(MIN)
            hub.states[AWAY] = "off"
            val third = AlertStore(dir.resolve("alerts.json"), clock::get)
            round(watcher(hub, f, third))
            assertNull(third.baselineOf("leak"), "dropped when the guard is false at the restart")
            assertEquals(AlertState.CLEAR, third.stateOf("leak"))
            assertNull(AlertStore(dir.resolve("alerts.json"), clock::get).baselineOf("leak"), "and saved so")
        }
    }

    @Test
    fun anAcknowledgeReTakesTheBaseline(): Unit = runBlocking {
        val dir = Files.createTempDirectory("watcher-ack")
        Hub().use { hub ->
            val store = AlertStore(dir.resolve("alerts.json"), clock::get)
            val w = watcher(hub, files(dir, """{"id":"leak","entity":"$FRESH","kind":"change","at":3,"text":"Fresh tank"}"""), store)
            hub.states[FRESH] = "100"
            round(w)
            assertEquals("100" to T0, store.baselineOf("leak"), "an unguarded rule arms at its first reading")
            clock.addAndGet(MIN)
            hub.states[FRESH] = "94"
            round(w)
            val rec = store.table().single()
            clock.addAndGet(MIN)
            assertTrue(store.ack("leak", rec.episode).ok)
            clock.addAndGet(MIN)
            val seq = store.seq
            round(w)
            val cleared = store.answer(seq, store.epoch).events.single()
            assertEquals(EventKind.CLEARED, cleared.kind, "the change he has seen ends its episode")
            assertTrue("acknowledged; changes are now measured from 94" in cleared.text, cleared.text)
            assertEquals("94" to clock.get(), store.baselineOf("leak"), "re-taken at the first reading after the ack")
            // the same value again: nothing; the next change: a new episode
            clock.addAndGet(MIN)
            round(w)
            assertEquals(AlertState.CLEAR, store.stateOf("leak"))
            clock.addAndGet(MIN)
            hub.states[FRESH] = "90"
            round(w)
            assertEquals(rec.episode + 1, store.table().single().episode)
            assertTrue(store.table().single().text.startsWith("Fresh tank 94 → 90 since"), store.table().single().text)
        }
    }

    @Test
    fun aGuardOnDamagePhoneFollowsThePresenceValueAndUnknownNeverArms(): Unit = runBlocking {
        val dir = Files.createTempDirectory("watcher-phone")
        Hub().use { hub ->
            val store = AlertStore(dir.resolve("alerts.json"), clock::get)
            val presence = PhonePresence("100.101.102.103", { error("no command runs in this test") }, { emptyList() },
                scope, clock::get)
            val rule = """{"id":"leak","entity":"$FRESH","kind":"change","at":3,"text":"Fresh tank",""" +
                """"when":{"entity":"damage.phone","equals":"away"}}"""
            // "not home" is also unarmed by unknown: unknown is no reading, never a state that differs from home
            val notHome = """{"id":"grey","entity":"$GREY","kind":"change","at":3,"text":"Grey tank",""" +
                """"when":{"entity":"damage.phone","notEquals":"home"}}"""
            val w = watcher(hub, files(dir, rule, notHome), store, presence)
            hub.states[FRESH] = "100"
            hub.states[GREY] = "50"
            round(w)
            assertTrue(hub.requests.none { "damage.phone" in it }, "a built-in value is never asked of the hub: ${hub.requests}")
            assertEquals(PhonePresence.UNKNOWN, presence.word)
            assertNull(store.baselineOf("leak"), "unknown never arms")
            assertTrue("rule leak: no reading of its guard damage.phone" in w.stateLine(), w.stateLine())
            assertTrue("phone: unknown" in w.stateLine(), w.stateLine())

            // relayed from now: unknown for fifteen minutes, then away
            val far = PhonePresence.PathRead(PhonePresence.PathKind.FAR, "relayed")
            for (m in 0..14) {
                presence.observe(far, clock.get())
                hub.states[FRESH] = "${100 - m}"
                round(w)
                assertNull(store.baselineOf("leak"), "minute $m: still unknown, nothing arms")
                assertNull(store.baselineOf("grey"), "minute $m: unknown does not arm a notEquals guard either")
                clock.addAndGet(MIN)
            }
            presence.observe(far, clock.get())
            assertEquals(PhonePresence.AWAY, presence.word)
            hub.states[FRESH] = "80"
            round(w)
            assertEquals("80" to clock.get(), store.baselineOf("leak"), "armed when the guard turned true")
            clock.addAndGet(MIN)
            hub.states[FRESH] = "76"
            round(w)
            assertEquals(AlertState.RAISED, store.stateOf("leak"))
            // home again: the check ends and says so
            presence.observe(PhonePresence.PathRead(PhonePresence.PathKind.HOME, "direct, on the network of eth-test"), clock.get())
            clock.addAndGet(MIN)
            round(w)
            assertEquals(AlertState.CLEAR, store.stateOf("leak"))
            assertTrue("check ended: damage.phone is home" in store.records().single { it.id == "leak" }.text)
        }
    }

    @Test
    fun withinMinIsBlindAfterAStartThenComparesWithTheSampleFromThen(): Unit = runBlocking {
        val dir = Files.createTempDirectory("watcher-within")
        Hub().use { hub ->
            val store = AlertStore(dir.resolve("alerts.json"), clock::get)
            val w = watcher(hub, files(dir,
                """{"id":"drop","entity":"$GREY","kind":"change","at":3,"direction":"down","withinMin":5,"text":"Grey tank"}"""),
                store)
            // 2 a minute down; ten-second polls between the minutes add no samples
            for (m in 0..4) {
                hub.states[GREY] = "${50 - 2 * m}"
                round(w)
                assertEquals(AlertState.CLEAR, store.stateOf("drop"), "minute $m: blind, however far it falls")
                assertTrue("rule drop: blind until ${hhmm(T0 + 5 * MIN)}" in w.stateLine(), w.stateLine())
                clock.addAndGet(10_000)
                round(w)
                clock.addAndGet(MIN - 10_000)
            }
            // minute 5: 40 against minute 0's 50
            hub.states[GREY] = "40"
            round(w)
            assertEquals(AlertState.RAISED, store.stateOf("drop"))
            assertEquals("Grey tank 50 → 40 since ${hhmm(T0)}", store.table().single().text)
            assertTrue("blind" !in w.stateLine(), w.stateLine())
            assertNull(store.baselineOf("drop"), "a withinMin rule keeps no baseline")
            // steady from here: once minute 5's change has left the window it clears
            for (m in 6..11) {
                clock.addAndGet(MIN)
                round(w)
            }
            assertEquals(AlertState.CLEAR, store.stateOf("drop"))
            // a gap: the sensor reads unavailable for ten minutes, then has fallen 10. The newest sample from five
            // minutes ago is the one before the gap, too old to stand for then: blind again, nothing raises
            hub.states[GREY] = "unavailable"
            for (m in 12..21) {
                clock.addAndGet(MIN)
                round(w)
            }
            clock.addAndGet(MIN)
            hub.states[GREY] = "30"
            round(w)
            assertEquals(AlertState.CLEAR, store.stateOf("drop"), "no comparison across a gap in the samples")
            assertTrue("rule drop: blind until" in w.stateLine(), w.stateLine())
        }
    }

    @Test
    fun aGoneRulesBaselineIsDroppedAtStart(): Unit = runBlocking {
        val dir = Files.createTempDirectory("watcher-gone")
        Hub().use { hub ->
            val store = AlertStore(dir.resolve("alerts.json"), clock::get)
            store.setBaseline("leak", "100", T0)
            store.setBaseline("kept", "7", T0)
            watcher(hub, files(dir, """{"id":"kept","entity":"$FRESH","kind":"change","at":3,"text":"Fresh tank"}"""), store)
            assertNull(store.baselineOf("leak"), "the rule is gone from a readable file: its baseline goes with it")
            assertEquals("7" to T0, store.baselineOf("kept"))
        }
    }

    companion object {
        const val T0 = 1_800_000_000_000L
        const val MIN = 60_000L
        const val FRESH = "sensor.fresh_tank"
        const val GREY = "sensor.grey_tank"
        const val AWAY = "binary_sensor.away_mode"
    }
}
