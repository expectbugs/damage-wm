package wm.damage.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
import wm.damage.core.bastion.AlertState
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionWatcher
import wm.damage.core.bastion.EventKind
import wm.damage.core.bastion.HaReader
import wm.damage.core.bastion.Severity
import wm.damage.core.util.Log

/**
 * Build W, package B: the watcher's start and its built-in alerts (the spec's B2, B4 and §2(f); the lead's decision
 * A.6). `BastionWatcher.build` reads the token file and the rules once: no `haUrl` is off and clears the watcher's
 * alerts; a token file it cannot use (not set, missing, empty, not one token) is off, said with the PATH and the
 * case, with `watcher.tokenfile` raised; a refused rule raises `watcher.rule.<id>`; the next good start clears what
 * no longer holds. Running: a 404 raises `watcher.entity.<entity>` and an answer clears it; a 401 stops the polling
 * with `watcher.token`, and an urgent alert still repeats. The token's text is in no line. Made-up ids and token.
 */
class WatcherStartTest {

    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        Log.removeSink(sink)
        scope.cancel()
    }

    private fun rules(dir: Path, vararg body: String): Path = dir.resolve("bastion-rules.json").also {
        Files.writeString(it, "{\"rules\":[${body.joinToString(",")}]}")
    }

    private val good = "{\"id\":\"fresh_full\",\"entity\":\"$ENTITY\",\"kind\":\"above\",\"at\":90,\"clearAt\":80," +
        "\"text\":\"Fresh tank nearly full\"}"
    private val bad = "{\"id\":\"pump_fault\",\"entity\":\"binary_sensor.pump_fault\",\"kind\":\"equals\"," +
        "\"text\":\"Water pump fault\"}"                // equals without a value: refused

    @Test
    fun noHaUrlIsOffAndClearsTheWatchersOwnAlerts() {
        Log.addSink(sink)
        val dir = Files.createTempDirectory("watcher-off")
        val store = AlertStore(dir.resolve("alerts.json"))
        store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
        store.raise(BastionWatcher.SILENT, Severity.NORMAL, "silent", "", builtIn = true)
        store.raise(BastionWatcher.COPY, Severity.NORMAL, "copy failed", "", builtIn = true)
        val w = BastionWatcher.build("", "", rules(dir, good), 10, 5, store, scope)
        assertFalse(w.on)
        assertEquals("bus watcher off: no haUrl in the config", w.stateLine())
        assertEquals(AlertState.CLEAR, store.stateOf("fresh_full"), "a rule alert with no watcher is retired")
        assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.SILENT), "the watcher's own alerts are cleared")
        assertEquals(AlertState.RAISED, store.stateOf(BastionWatcher.COPY), "the copy's alert is the copy's to clear")
        w.tick()
        w.start()
        assertEquals(0, w.roundsInFlight, "an off watcher runs nothing")
    }

    @Test
    fun aTokenFileItCannotUseIsSaidWithItsPathAndTheNextGoodStartClearsIt() {
        Log.addSink(sink)
        val dir = Files.createTempDirectory("watcher-token")
        val store = AlertStore(dir.resolve("alerts.json"))
        val rulesFile = rules(dir, good, bad)
        val tokenFile = dir.resolve("hub.token")
        val url = "http://127.0.0.1:9"                       // never asked: no watcher is started here
        val cases = listOf(
            "" to "no haTokenFile is set",
            tokenFile.toString() to "$tokenFile does not exist",
        )
        for ((file, want) in cases) {
            val w = BastionWatcher.build(url, file, rulesFile, 10, 5, store, scope)
            assertFalse(w.on, file)
            assertTrue(want in w.stateLine(), "${w.stateLine()} names $want")
            val alert = store.table().single { it.id == BastionWatcher.TOKEN_FILE }
            assertTrue(want in alert.text && alert.builtIn, alert.text)
        }
        Files.writeString(tokenFile, "  \n")
        assertTrue("is empty" in BastionWatcher.build(url, tokenFile.toString(), rulesFile, 10, 5, store, scope).stateLine())
        Files.writeString(tokenFile, "Bearer $TOKEN\n")
        val malformed = BastionWatcher.build(url, tokenFile.toString(), rulesFile, 10, 5, store, scope)
        assertTrue("does not hold one token" in malformed.stateLine(), malformed.stateLine())
        assertTrue(store.table().single { it.id == BastionWatcher.TOKEN_FILE }.text.contains("does not hold one token"),
            "a new reason replaces the old text")
        assertEquals(AlertState.RAISED, store.stateOf(BastionWatcher.RULE_PREFIX + "pump_fault"), "the refused rule is said")
        val ruleEpisode = store.table().single { it.id == BastionWatcher.RULE_PREFIX + "pump_fault" }.episode

        Files.writeString(tokenFile, "$TOKEN\n")
        val on = BastionWatcher.build(url, tokenFile.toString(), rulesFile, 10, 5, store, scope)
        assertTrue(on.on, on.stateLine())
        assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.TOKEN_FILE), "a good token file clears its alert")
        assertEquals(ruleEpisode, store.table().single { it.id == BastionWatcher.RULE_PREFIX + "pump_fault" }.episode,
            "a refusal that still holds is not said again")

        Files.writeString(rulesFile, "{\"rules\":[$good]}")
        BastionWatcher.build(url, tokenFile.toString(), rulesFile, 10, 5, store, scope)
        assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.RULE_PREFIX + "pump_fault"), "a fixed rule clears its alert")
        Files.writeString(rulesFile, "{ not json")
        BastionWatcher.build(url, tokenFile.toString(), rulesFile, 10, 5, store, scope)
        assertEquals(AlertState.RAISED, store.stateOf(BastionWatcher.RULES), "a rules file that is not JSON is said")
        assertEquals(AlertState.CLEAR, store.stateOf("fresh_full"))

        val said = logs + store.records().map { it.text }
        assertTrue(said.none { TOKEN in it }, "the token's text is in no line")
    }

    /** Fix round 1 (review 1, finding 3; R1.3): the watcher's own trouble must not end an alert. A rules file that
     *  is not JSON retires nothing; a refused rule's alert is held and `watcher.rule.<id>` raised; only a rule absent
     *  from a READABLE file is gone (its alert retired). */
    @Test
    fun theWatchersOwnTroubleNeverEndsAnAlert() {
        Log.addSink(sink)
        val dir = Files.createTempDirectory("watcher-trouble")
        val store = AlertStore(dir.resolve("alerts.json"))
        store.raise("fresh_full", Severity.NORMAL, "Fresh tank nearly full", "91")
        store.raise("pump_fault", Severity.URGENT, "Water pump fault", "on")
        val tokenFile = dir.resolve("hub.token").also { Files.writeString(it, TOKEN) }
        val url = "http://127.0.0.1:9"                       // never asked: no watcher is ticked here
        val rulesFile = dir.resolve("bastion-rules.json")
        val upBefore = store.table().associate { it.id to it.episode }

        Files.writeString(rulesFile, "{ \"rules\": [ $good, ")    // cut short: not JSON
        BastionWatcher.build(url, tokenFile.toString(), rulesFile, 10, 5, store, scope)
        assertEquals(AlertState.RAISED, store.stateOf(BastionWatcher.RULES), "the file's problem is said")
        for ((id, ep) in upBefore) {
            assertEquals(AlertState.RAISED, store.stateOf(id), "$id: a rules file with a problem retires nothing")
            assertEquals(ep, store.table().single { it.id == id }.episode, "$id: the same episode, not said again")
        }

        rules(dir, good, bad)                                // pump_fault's rule is refused
        BastionWatcher.build(url, tokenFile.toString(), rulesFile, 10, 5, store, scope)
        assertEquals(AlertState.RAISED, store.stateOf(BastionWatcher.RULE_PREFIX + "pump_fault"), "the refusal is said")
        assertEquals(AlertState.RAISED, store.stateOf("pump_fault"), "a refused rule's alert is held")
        assertEquals(upBefore["pump_fault"], store.table().single { it.id == "pump_fault" }.episode)
        assertEquals(AlertState.CLEAR, store.stateOf(BastionWatcher.RULES), "the file reads again")

        rules(dir, good)                                     // pump_fault's rule removed from a readable file
        BastionWatcher.build(url, tokenFile.toString(), rulesFile, 10, 5, store, scope)
        assertEquals(AlertState.CLEAR, store.stateOf("pump_fault"), "a rule removed: its alert retired")
        assertEquals(AlertState.RAISED, store.stateOf("fresh_full"))
    }

    /** Fix round 1 (W-verify §7.1; R1.3): the token file is missing, so this start's watcher is off; an urgent alert
     *  of the last run is kept (by design) and still repeats every ten minutes — its loop runs repeat-only. */
    @Test
    fun anOffWatcherStillRepeatsAnUrgentAlert(): Unit = runBlocking {
        Log.addSink(sink)
        val dir = Files.createTempDirectory("watcher-off-repeat")
        val clock = AtomicLong(1_800_000_000_000L)
        val store = AlertStore(dir.resolve("alerts.json"), clock::get)
        store.raise("fresh_full", Severity.URGENT, "Fresh tank nearly full", "91")
        val w = BastionWatcher.build("http://127.0.0.1:9", dir.resolve("hub.token").toString(), rules(dir, good),
            10, 5, store, scope, clock::get)
        assertFalse(w.on, w.stateLine())
        assertEquals(AlertState.RAISED, store.stateOf("fresh_full"), "a config mistake does not clear a real alarm")
        val seq = store.seq
        clock.addAndGet(600_000)
        w.start()                                            // its first tick runs at once, then every haPollSec
        waitUntil(5_000, 5, { "no repeat from the off watcher: ${store.answer(seq, store.epoch).events}" }) {
            store.answer(seq, store.epoch).events.any { it.kind == EventKind.REPEAT && it.id == "fresh_full" } }
        assertEquals(0, w.roundsInFlight, "and the hub is never asked")
    }

    @Test
    fun aMissingEntityIsSaidAndARefusedTokenStopsThePolling(): Unit = runBlocking {
        Log.addSink(sink)
        val dir = Files.createTempDirectory("watcher-builtin")
        val status = ConcurrentHashMap<String, Int>()
        val requests = CopyOnWriteArrayList<String>()
        val hub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        hub.createContext("/") { x ->
            requests += x.requestURI.rawPath
            val s = status[x.requestURI.rawPath] ?: 200
            val b = if (s == 200 && x.requestURI.rawPath != HaReader.HEALTH)
                "{\"entity_id\":\"$ENTITY\",\"state\":\"50\",\"last_reported\":\"a\",\"last_updated\":\"b\"}".toByteArray()
            else "{}".toByteArray()
            x.sendResponseHeaders(s, b.size.toLong())
            x.responseBody.use { it.write(b) }
        }
        hub.start()
        try {
            val tokenFile = dir.resolve("hub.token").also { Files.writeString(it, TOKEN) }
            val clock = AtomicLong(1_800_000_000_000L)
            val store = AlertStore(dir.resolve("alerts.json"), clock::get)
            val w = BastionWatcher.build("http://127.0.0.1:${hub.address.port}", tokenFile.toString(), rules(dir, good),
                10, 5, store, scope, clock::get)
            assertTrue(w.on, w.stateLine())
            val entityAlert = BastionWatcher.ENTITY_PREFIX + ENTITY
            status[HaReader.STATES + ENTITY] = 404
            w.tick()
            waitUntil(5_000, 5, { "the 404 is said: ${store.table()}" }) { store.stateOf(entityAlert) == AlertState.RAISED }
            waitUntil(5_000, 5, { "round ended" }) { w.roundsInFlight == 0 }
            assertTrue("not on the hub" in w.stateLine(), w.stateLine())
            status.remove(HaReader.STATES + ENTITY)
            w.tick()
            waitUntil(5_000, 5, { "an answer clears it: ${store.table()}" }) { store.stateOf(entityAlert) == AlertState.CLEAR }
            waitUntil(5_000, 5, { "round ended" }) { w.roundsInFlight == 0 }

            store.raise("urgent_one", Severity.URGENT, "Urgent made-up alert", "x")
            status[HaReader.HEALTH] = 401
            w.tick()
            waitUntil(5_000, 5, { "the refusal is said: ${store.table()}" }) { store.stateOf(BastionWatcher.TOKEN) == AlertState.RAISED }
            waitUntil(5_000, 5, { "round ended" }) { w.roundsInFlight == 0 }
            assertTrue(w.stateLine().startsWith("bus watcher stopped: the hub refused its token (HTTP 401)"), w.stateLine())
            val asked = requests.size
            val seq = store.seq
            clock.addAndGet(600_000)
            w.tick()
            delay(300)
            assertEquals(asked, requests.size, "after a refused token nothing more is asked")
            assertEquals(EventKind.REPEAT, store.answer(seq, store.epoch).events.single().kind,
                "an urgent alert still repeats while the polling is stopped")
            assertTrue((logs + store.records().map { it.text } + w.stateLine()).none { TOKEN in it }, "the token in no line")
        } finally {
            hub.stop(0)
        }
    }

    companion object {
        const val ENTITY = "sensor.fresh_tank"
        const val TOKEN = "made-up-test-token-abcdef"
    }
}
