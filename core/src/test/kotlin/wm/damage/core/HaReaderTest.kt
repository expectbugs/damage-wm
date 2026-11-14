package wm.damage.core

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import wm.damage.core.bastion.HaReader
import wm.damage.core.util.Log

/**
 * Build W, package A: the hub reader only READS (R6 T1, T2, T3 for the reader, T8). No real network: a stand-in
 * hub on loopback port 0 (the `ResolverTest` shape) that records every request it receives. Made-up entity ids
 * and a made-up token only.
 */
class HaReaderTest {

    private class Req(val method: String, val path: String, val query: String?, val bodyLen: Int,
        val connection: String?, val auth: String?)

    private class Answer(val status: Int, val body: String, val delayMs: Long = 0)

    /** A stand-in hub: answers by path from [answers] (a 404 for any other), records every request. */
    private class FakeHub : AutoCloseable {
        val requests = CopyOnWriteArrayList<Req>()
        val answers = ConcurrentHashMap<String, Answer>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { x ->
                val body = x.requestBody.readBytes()
                requests += Req(x.requestMethod, x.requestURI.rawPath, x.requestURI.rawQuery, body.size,
                    x.requestHeaders.getFirst("Connection"), x.requestHeaders.getFirst("Authorization"))
                val a = answers[x.requestURI.rawPath] ?: Answer(404, "{\"message\":\"Entity not found.\"}")
                if (a.delayMs > 0) Thread.sleep(a.delayMs)       // a slow hub; the reader has no deadline
                val b = a.body.toByteArray()
                x.sendResponseHeaders(a.status, if (b.isEmpty()) -1 else b.size.toLong())
                x.responseBody.use { it.write(b) }
            }
            server.start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }

    @After
    fun tearDown() {
        Log.removeSink(sink)
    }

    private fun state(entity: String, value: String, reported: String = "2026-11-13T10:00:00+00:00") =
        "{\"entity_id\":\"$entity\",\"state\":\"$value\",\"attributes\":{},\"last_changed\":\"x\"," +
            "\"last_reported\":\"$reported\",\"last_updated\":\"2026-11-13T09:00:00+00:00\"}"

    private fun chain(e: Throwable): List<String> = generateSequence(e) { it.cause }.map { it.toString() }.toList()

    /** T1: every request over a healthy hub, a 404, a 401, a 500, bad JSON and a slow answer is a GET to an
     *  allowed path, with no body and `Connection: close`. */
    @Test
    fun everyRequestIsAGetWithNoBodyToAnAllowedPath() {
        FakeHub().use { hub ->
            hub.answers["/api/"] = Answer(200, "{\"message\":\"API running.\"}")
            hub.answers["/api/states/sensor.fresh_tank"] = Answer(200, state("sensor.fresh_tank", "42.5"))
            hub.answers["/api/states/binary_sensor.pump_fault"] = Answer(500, "internal detail from the hub")
            hub.answers["/api/states/sensor.bad_json"] = Answer(200, "<html>not json</html>")
            hub.answers["/api/states/sensor.slow"] = Answer(200, state("sensor.slow", "7"), delayMs = 300)
            val entities = listOf("sensor.fresh_tank", "binary_sensor.pump_fault", "sensor.bad_json", "sensor.slow",
                "sensor.gone")
            val r = HaReader(hub.url, TOKEN, entities)
            r.health()
            val s = r.state("sensor.fresh_tank")
            assertEquals("42.5", s.state)
            assertEquals("2026-11-13T10:00:00+00:00", s.lastReported)
            assertEquals("2026-11-13T09:00:00+00:00", s.lastUpdated)
            assertEquals("hub: no entity sensor.gone (HTTP 404)",
                assertFailsWith<HaReader.NotFound> { r.state("sensor.gone") }.message)
            val e500 = assertFailsWith<IOException> { r.state("binary_sensor.pump_fault") }
            assertEquals("hub /api/states/binary_sensor.pump_fault: HTTP 500", e500.message)
            val bad = assertFailsWith<IOException> { r.state("sensor.bad_json") }
            assertFalse("not json" in bad.message!!, "a body reached an exception text: ${bad.message}")
            assertEquals("7", r.state("sensor.slow").state)
            // the 401 path, on its own reader (it latches)
            hub.answers["/api/states/sensor.locked"] = Answer(401, "401: Unauthorized")
            assertFailsWith<HaReader.TokenRefused> { HaReader(hub.url, TOKEN, listOf("sensor.locked")).state("sensor.locked") }

            assertEquals(7, hub.requests.size, "one request per call")
            val allowed = setOf("/api/") + (entities + "sensor.locked").map { "/api/states/$it" }
            for (q in hub.requests) {
                assertEquals("GET", q.method)
                assertTrue(q.path in allowed && q.query == null, "a request outside the set: ${q.path}?${q.query}")
                assertEquals(0, q.bodyLen, "a request carried a body")
                assertEquals("close", q.connection?.lowercase(), "Connection: close missing on ${q.path}")
                assertEquals("Bearer $TOKEN", q.auth)
            }
        }
    }

    /** T2: an entity outside the rules' set, or one carrying `/` or `?`, never reaches the hub. */
    @Test
    fun aPathOutsideTheSetNeverReachesTheHub() {
        FakeHub().use { hub ->
            val r = HaReader(hub.url, TOKEN, listOf("sensor.fresh_tank"))
            for (e in listOf("sensor.other", "sensor.fresh_tank/../../config", "sensor.fresh_tank?x=1",
                "../config/core", "", "sensor.fresh_tank/", "SENSOR.FRESH_TANK")) {
                assertFailsWith<HaReader.PathRefused>("state(\"$e\")") { r.state(e) }
            }
            for (bad in listOf("sensor.a/b", "sensor.a?b=1", "sensor", "sensor.a.b", "../x")) {
                assertFailsWith<IllegalArgumentException>("entity \"$bad\"") { HaReader(hub.url, TOKEN, listOf(bad)) }
            }
            assertEquals(0, hub.requests.size, "a refused path reached the hub")
        }
    }

    /** T3 (the reader): over the 401, 500, bad-JSON, not-found and connection-refused paths, and a token with a
     *  line break, the token is in no exception text, no log line and no `toString`. */
    @Test
    fun theTokenIsNeverSaid() {
        Log.addSink(sink)
        val texts = ArrayList<String>()
        FakeHub().use { hub ->
            hub.answers["/api/states/sensor.fresh_tank"] = Answer(401, "401: Unauthorized $TOKEN")
            hub.answers["/api/states/sensor.slow"] = Answer(500, "error for Bearer $TOKEN")
            hub.answers["/api/states/sensor.bad_json"] = Answer(200, "{\"oops\": \"$TOKEN\"")
            val r401 = HaReader(hub.url, TOKEN, listOf("sensor.fresh_tank"))
            texts += r401.toString()
            repeat(2) { try { r401.state("sensor.fresh_tank") } catch (e: Exception) { texts += chain(e) } }
            val r = HaReader(hub.url, TOKEN, listOf("sensor.slow", "sensor.bad_json", "sensor.gone"))
            for (e in listOf("sensor.slow", "sensor.bad_json", "sensor.gone", "sensor.not_in_set")) {
                try { r.state(e) } catch (x: Exception) { texts += chain(x) }
            }
            // a token with a line break: the JDK's header check would quote the whole value
            val split = "$TOKEN\nsecond-line"
            var threw = false
            try {
                HaReader(hub.url, split, listOf("sensor.fresh_tank")).health()
            } catch (x: Exception) {
                threw = true
                texts += chain(x)
            }
            assertTrue(threw, "a token with a line break was accepted")
        }
        // the hub is gone: connection refused
        val gone = HaReader("http://127.0.0.1:1", TOKEN, listOf("sensor.fresh_tank"))
        try { gone.health() } catch (x: Exception) { texts += chain(x) }
        assertTrue(texts.size >= 8, "every path threw: $texts")
        for (t in texts + logs) assertFalse(TOKEN in t, "the token was said: $t")
    }

    /** T8: after a 401 (and a 403) one request went out and no more, whatever is asked next. */
    @Test
    fun aRefusedTokenLatches() {
        for (status in listOf(401, 403)) FakeHub().use { hub ->
            hub.answers["/api/"] = Answer(status, "")
            hub.answers["/api/states/sensor.fresh_tank"] = Answer(status, "")
            val r = HaReader(hub.url, TOKEN, listOf("sensor.fresh_tank"))
            repeat(3) {
                assertEquals(status, assertFailsWith<HaReader.TokenRefused> { r.state("sensor.fresh_tank") }.status)
            }
            assertEquals(status, assertFailsWith<HaReader.TokenRefused> { r.health() }.status)
            assertEquals(1, hub.requests.size, "HTTP $status: requests after the refusal")
            // a restart reads the token again: a new reader asks once more
            assertFailsWith<HaReader.TokenRefused> { HaReader(hub.url, TOKEN, listOf("sensor.fresh_tank")).health() }
            assertEquals(2, hub.requests.size)
        }
    }

    private companion object {
        /** Made up for the tests; nothing like a real token. */
        const val TOKEN = "made-up.TEST_token-0123456789abcdef"
    }
}
