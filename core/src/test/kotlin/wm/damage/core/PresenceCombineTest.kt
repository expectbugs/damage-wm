package wm.damage.core

import java.nio.file.Files
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionService
import wm.damage.core.bastion.PhonePresence
import wm.damage.core.bastion.PhonePresence.PathKind
import wm.damage.core.bastion.PhonePresence.PathRead
import wm.damage.core.bastion.PhonePresence.PathSignal
import wm.damage.core.bastion.PhonePresence.PhoneReport
import wm.damage.core.bastion.StoreAnswer
import wm.damage.core.util.Log

/**
 * Build W, package F (W-decisions G.3, G.7): `damage.phone` on the PC from both signals — the connection path
 * (package E) and the phone's own report on the bastion channel. Pinned: the combination table; a report older than
 * two minutes, or one that says unknown, is no report; every change of the value is one line naming the signal that
 * decided; the `table` ask hands the report over and every answer carries the PATH signal alone; a report the PC
 * cannot use is said once and the table is answered as usual.
 */
class PresenceCombineTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }

    init { Log.addSink(sink) }

    @After
    fun tearDown() {
        Log.removeSink(sink)
        scope.cancel()
    }

    @Test
    fun theCombinationTable() {
        val now = T0
        val paths = linkedMapOf(
            "home" to PathSignal(PathKind.HOME, now, "direct, on the network of eth-test"),
            "far 20 min" to PathSignal(PathKind.FAR, now - 20 * MIN, "relayed"),
            "far 5 min" to PathSignal(PathKind.FAR, now - 5 * MIN, "relayed"),
            "none" to PathSignal(PathKind.NONE, now, "the phone's peer is offline"),
        )
        val reports = linkedMapOf<String, PhoneReport?>(
            "no report" to null,
            "home" to PhoneReport("home", 3 * MIN, now - 10_000),
            "away" to PhoneReport("away", 3 * MIN, now - 10_000),
            "unknown" to PhoneReport("unknown", null, now - 10_000),
            "away, 3 min old" to PhoneReport("away", 3 * MIN, now - 3 * MIN),
            "home, 3 min old" to PhoneReport("home", 3 * MIN, now - 3 * MIN),
            "away, from the PC's future" to PhoneReport("away", 0, now + 3 * MIN),
        )
        val P = PhonePresence.BY_PATH
        val F = PhonePresence.BY_PHONE
        // expected [word by] per path row, in the order of [reports]
        val want = mapOf(
            "home" to List(7) { "home $P" },
            "far 20 min" to listOf("away $P", "home $F", "away $F", "away $P", "away $P", "away $P", "away $P"),
            "far 5 min" to listOf("unknown $P", "home $F", "away $F", "unknown $P", "unknown $P", "unknown $P", "unknown $P"),
            "none" to listOf("unknown $P", "home $F", "away $F", "unknown $P", "unknown $P", "unknown $P", "unknown $P"),
        )
        val wrong = ArrayList<String>()
        for ((pn, path) in paths) for ((i, rn) in reports.keys.withIndex()) {
            val v = PhonePresence.combineSignals(path, reports[rn], now)
            val got = "${v.word} ${v.by}"
            if (got != want.getValue(pn)[i]) wrong += "path $pn, phone $rn: expected ${want.getValue(pn)[i]}, was $got"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        // the phone's words: the word and the fix's age, nothing else
        assertEquals("the phone reports away, fix 3 min old",
            PhonePresence.combineSignals(paths.getValue("none"), reports.getValue("away"), now).why)
        // exactly at the two minutes the report still counts; a millisecond later it does not
        val edge = PhoneReport("away", null, now - PhonePresence.REPORT_FRESH_MS)
        assertEquals("away", PhonePresence.combineSignals(paths.getValue("none"), edge, now).word)
        assertEquals("unknown", PhonePresence.combineSignals(paths.getValue("none"), edge, now + 1).word)
    }

    @Test
    fun eachChangeIsOneLineNamingTheSignalThatDecided() {
        val clock = AtomicLong(T0)
        val said = CopyOnWriteArrayList<String>()
        val p = PhonePresence("100.101.102.103", { error("not run") }, { emptyList() }, scope, clock::get) { said += it }
        val none = PathRead(PathKind.NONE, "the phone's peer is offline")
        val home = PathRead(PathKind.HOME, "direct, on the network of eth-test")     // what the read gives (R2.3: no address)
        p.observe(none, clock.get())
        assertEquals(PhonePresence.UNKNOWN, p.word)
        p.phoneReport("away", 3 * MIN, clock.get())
        assertEquals(PhonePresence.AWAY, p.word, "the phone reports away: away at once")
        assertEquals("damage.phone unknown -> away (by the phone's position: the phone reports away, fix 3 min old)", said.last())
        p.observe(home, clock.get())
        assertEquals(PhonePresence.HOME, p.word, "either says home")
        assertEquals("damage.phone away -> home (by the connection path: ${home.detail})", said.last())
        clock.addAndGet(MIN)
        p.observe(none, clock.get())
        assertEquals(PhonePresence.AWAY, p.word, "the report, a minute old, still counts")
        assertEquals("damage.phone home -> away (by the phone's position: the phone reports away, fix 3 min old)", said.last())
        p.phoneReport("away", 4 * MIN, clock.get())
        assertEquals(3, said.size, "a report that changes nothing writes no line")
        clock.addAndGet(2 * MIN + 1)
        p.observe(none, clock.get())
        assertEquals(PhonePresence.UNKNOWN, p.word, "no report for over two minutes: the path decides")
        assertEquals("damage.phone away -> unknown (by the connection path: the phone's peer is offline)", said.last())
        p.phoneReport("unknown", null, clock.get())
        assertEquals(PhonePresence.UNKNOWN, p.word)
        assertEquals(4, said.size, said.joinToString("\n"))
        for (line in said.toSet()) assertEquals(said.count { it == line }, logs.count { it.endsWith(line) }, "one log line each: $line")
        assertTrue(p.stateLine().startsWith("phone: unknown since "), p.stateLine())
    }

    @Test
    fun theTableHandsTheReportOverAndAnswersThePathSignalAlone() {
        val dir = Files.createTempDirectory("damage-presence-table")
        try {
            val clock = AtomicLong(T0)
            val p = PhonePresence("100.101.102.103", { error("not run") }, { emptyList() }, scope, clock::get)
            val store = AlertStore(dir.resolve("alerts.json"))
            val svc = BastionService(store, { "1 rule(s)" }, scope, p)
            fun table(presence: JsonObject?): StoreAnswer = StoreAnswer.fromJson(svc.request(BastionService.OP_TABLE, buildJsonObject {
                put("since", 0); put("epoch", 0)
                if (presence != null) put("presence", presence)
            }).data)
            fun report(word: String, age: Long?) = buildJsonObject { put("word", word); if (age != null) put("fixAgeMs", age) }

            assertEquals(false, table(null).bus, "no path read yet")
            p.observe(PathRead(PathKind.HOME, "direct, on the network of eth-test"), clock.get())
            assertEquals(true, table(null).bus, "the phone's link arrives over a private path on the bus network")
            p.observe(PathRead(PathKind.FAR, "relayed"), clock.get())
            val a = table(report("home", 5 * MIN))
            assertEquals(PhonePresence.HOME, p.word, "the report reached damage.phone")
            assertEquals(false, a.bus, "the answer carries the PATH signal, never the value the phone's report made")
            table(report("away", 2 * MIN))
            assertEquals(PhonePresence.AWAY, p.word)

            // a report this host cannot use: said once per kind, the table answered as usual, the value unchanged
            val bad = listOf(report("nearby", null), report("nearby", null),
                buildJsonObject { put("word", "home"); put("fixAgeMs", "soon") }, buildJsonObject { put("word", "home"); put("fixAgeMs", -5) })
            for (b in bad) assertEquals(store.epoch, table(b).epoch, "answered")
            assertEquals(PhonePresence.AWAY, p.word, "nothing from a bad report reached damage.phone")
            assertEquals(1, logs.count { "presence report from the phone was ignored: its word" in it }, logs.joinToString("\n"))
            assertEquals(1, logs.count { "presence report from the phone was ignored: its fixAgeMs" in it }, logs.joinToString("\n"))
            val raw = svc.request(BastionService.OP_TABLE, buildJsonObject {
                put("since", 0); put("epoch", 0); put("presence", JsonPrimitive("home"))
            })
            assertEquals(store.epoch, StoreAnswer.fromJson(raw.data).epoch)
            assertEquals(PhonePresence.AWAY, p.word)

            // a host with no presence value: no `bus` on the wire, a report said once and ignored
            val bare = BastionService(store, { "1 rule(s)" }, scope)
            val ans = bare.request(BastionService.OP_TABLE, buildJsonObject {
                put("since", 0); put("epoch", 0); put("presence", report("home", 0))
            }).data
            assertNull(ans["bus"], "no presence value, no bus field")
            assertTrue(logs.any { "this host keeps no damage.phone value" in it })
            svc.close()
            bare.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    companion object {
        const val T0 = 1_800_000_000_000L
        const val MIN = 60_000L
    }
}
