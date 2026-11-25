package wm.damage.desktop

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.HealthBand
import wm.damage.core.app.HealthCore
import wm.damage.core.app.HealthLine
import wm.damage.core.app.NoticeCore
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionWatcher
import wm.damage.core.bastion.HaReader
import wm.damage.core.bastion.Severity
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b stage H1 (`private/build085/RB-lean-spec.md` §3.2, §4.3, §4.8, §5): the PC's readers of the health record, each
 * on what really happens — the disk at Q2's two limits (strict "less than") and a folder that is not there, the memory
 * cap's group present, missing, unlimited, with swap, a file missing or holding something else, the hub through the
 * watcher's typed read over a stand-in hub on loopback (an hour of answers every 10 s publishes nothing; a failing hub
 * is attention, then alarm with its own alert, and the watcher's state line says the same), the nightly copy's line,
 * the last error, the windows' sources. Temp folders and fixture files only; no hub, no service, no data directory.
 */
class HealthPartsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root: Path = Files.createTempDirectory("damage-health-parts")
    private val clock = AtomicLong(LocalDateTime.of(2027, 1, 15, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
    private val closers = ArrayList<() -> Unit>()

    @After
    fun tearDown() {
        for (c in closers.reversed()) c()
        scope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun capFiles(name: String, max: String?, swap: String?): Path {
        val d = Files.createDirectories(root.resolve("cg").resolve(name))
        max?.let { Files.writeString(d.resolve("memory.max"), "$it\n") }
        swap?.let { Files.writeString(d.resolve("memory.swap.max"), "$it\n") }
        return d
    }

    /** Q2: alarm under 2 % free, attention under 5 %, both strict; the tag only in alarm; a value just under a limit
     *  never reads as the limit. */
    @Test
    fun theDiskIsBandedByQ2sLimitsStrictly() {
        val total = 3_600_000_000_000L
        fun at(pct: Double) = HealthParts.disk((total * pct / 100).toLong(), total)
        assertEquals(HealthBand.NORMAL to "71 % free of 3.6 TB", at(71.4).let { it.band to it.text })
        assertEquals(HealthBand.NORMAL, at(5.0).band, "exactly 5 % free is not less than 5 %")
        assertEquals(HealthBand.ATTENTION to "4.9 % free of 3.6 TB", at(4.99).let { it.band to it.text })
        assertEquals(HealthBand.ATTENTION, at(2.0).band, "exactly 2 % free is not less than 2 %")
        val low = at(1.99)
        assertEquals(Triple(HealthBand.ALARM, "1.9 % free of 3.6 TB", "disk 98%"), Triple(low.band, low.text, low.tag))
        assertNull(at(1.99).alert, "no alert of its own: the notice rule serves it")
        assertNull(at(4.0).tag, "a tag only in alarm")
        assertEquals("disk 100%", at(0.0).tag)
        assertTrue(DISK_ALARM_FREE_PCT < DISK_ATTENTION_FREE_PCT)
        assertFailsWith<IOException> { HealthParts.disk(0, 0) }
        // the real reader over a temp folder, and over one that is not there
        HealthParts.diskOf(root)
        assertFailsWith<NoSuchFileException> { HealthParts.diskOf(root.resolve("not-there")) }
    }

    @Test
    fun theMemoryCapFromItsGroupsFiles() {
        assertEquals(HealthBand.NORMAL to "claude-work: 16 GiB, no swap",
            HealthParts.cap(capFiles("claude-work", "17179869184", "0")).let { it.band to it.text })
        assertEquals(HealthBand.ATTENTION to "a: no limit (memory.max is max)",
            HealthParts.cap(capFiles("a", "max", "0")).let { it.band to it.text })
        assertEquals(HealthBand.ATTENTION to "b: 16 GiB, swap allowed (memory.swap.max is max)",
            HealthParts.cap(capFiles("b", "17179869184", "max")).let { it.band to it.text })
        assertEquals(HealthBand.ATTENTION, HealthParts.cap(capFiles("c", "17179869184", "1073741824")).band)
        assertEquals(HealthBand.ATTENTION to "gone: the group is missing, so nothing is capped",
            HealthParts.cap(root.resolve("cg/gone")).let { it.band to it.text })
        assertFailsWith<NoSuchFileException>("a file that is missing is a failed read, never a default") {
            HealthParts.cap(capFiles("d", "17179869184", null))
        }
        assertFailsWith<IOException> { HealthParts.cap(capFiles("e", "a lot", "0")) }
    }

    private fun hub(on: Boolean = true, offReason: String? = null, configured: Boolean = true, rereads: Boolean = true,
                    refused: Int = 0, answeredOnce: Boolean = true, lastGoodAt: Long = clock.get(), problem: String? = null,
                    failingSince: Long? = null, inFlightSince: Long? = null) =
        BastionWatcher.Hub(on, offReason, configured, rereads, refused, answeredOnce, lastGoodAt, problem, failingSince,
            inFlightSince, 10_000, 300_000)

    @Test
    fun theHubLineFromTheWatchersTypedRead() {
        val now = clock.get()
        fun line(h: BastionWatcher.Hub) = HealthParts.hub(h, now, ZoneOffset.UTC)
        assertEquals(HealthBand.ATTENTION, line(hub(on = false, offReason = "no haUrl in the config", configured = false)).band,
            "not configured: said, not an alarm")
        line(hub(on = false, offReason = "the token file /x is empty")).let {
            assertEquals(Triple(HealthBand.ALARM, BastionWatcher.TOKEN_FILE, "the token file is read again every minute"),
                Triple(it.band, it.alert, it.doing))
        }
        line(hub(refused = 401)).let { assertEquals(HealthBand.ALARM to BastionWatcher.TOKEN, it.band to it.alert) }
        line(hub(problem = "IOException: hub /api/: HTTP 500", failingSince = now - 60_000, lastGoodAt = now - 70_000)).let {
            assertEquals(HealthBand.ATTENTION, it.band)
            assertEquals("asking every 10 s; the alert after 5 min", it.doing)
            assertEquals(now - 60_000, it.since)
            assertNull(it.alert)
        }
        line(hub(problem = "IOException: x", failingSince = now - 290_000, lastGoodAt = now - 300_000)).let {
            assertEquals(Triple(HealthBand.ALARM, BastionWatcher.SILENT, "hub"), Triple(it.band, it.alert, it.tag))
        }
        line(hub(inFlightSince = now - 61_000)).let {
            assertEquals(HealthBand.NORMAL to "a request has been in flight since 07:58", it.band to it.stale)
        }
        assertEquals("waiting for its first answer", line(hub(answeredOnce = false)).text)
        assertEquals(HealthCore.Reading("answering", HealthBand.NORMAL, "asking every 10 s"), line(hub()))
    }

    /** A stand-in hub on loopback whose `/api/` answers [status]. */
    private fun standIn(status: AtomicInteger): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x ->
            x.sendResponseHeaders(status.get(), -1)
            x.close()
        }
        server.start()
        closers += { server.stop(0) }
        return "http://127.0.0.1:${server.address.port}"
    }

    private fun parts(watcher: BastionWatcher, store: AlertStore, dataDir: Path = root): HealthParts {
        val host = AppHost(Files.createDirectories(root.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-health-parts"), clock::get)
        closers += { host.executor.shutdown() }
        val net = AppNet(host)
        closers += { net.close() }
        val core = HealthCore(host, NoticeCore(host, clock = clock::get), ZoneOffset.UTC)
        return HealthParts(core, host, net, watcher, store, null, dataDir, capFiles("claude-work", "17179869184", "0"), ZoneOffset.UTC)
            .also { hosts[it] = host }
    }

    private fun HealthParts.line(id: String): HealthLine = core.lines().single { it.id == id }

    private val hosts = HashMap<HealthParts, AppHost>()

    /** The record's tick for the parts, and the core's sets it queued. */
    private fun HealthParts.tickNow() {
        val ex = hosts.getValue(this).executor
        ex.call { tick() }
        ex.call {}
    }

    /** One watcher round to its end (the stand-in answers at once), then the record's tick. */
    private fun poll(w: BastionWatcher, p: HealthParts) {
        w.tick()
        val until = System.currentTimeMillis() + 10_000
        while (w.roundsInFlight > 0 && System.currentTimeMillis() < until) Thread.sleep(5)
        assertEquals(0, w.roundsInFlight, "the stand-in answered")
        p.tickNow()
    }

    /** §4.1's last sentence, measured on the description: a hub answering every 10 s for an hour publishes nothing
     *  after its first answer; a failing hub publishes once for attention, once for alarm, and its polls between
     *  publish nothing; the watcher's own state line says the same problem. */
    @Test
    fun anHourOfHubAnswersPublishesNothing() {
        val status = AtomicInteger(200)
        val store = AlertStore(root.resolve("alerts.json"))
        val w = BastionWatcher(HaReader(standIn(status), "made-up-token"), emptyList(), store, scope, clock::get,
            pollSec = 10, silentMin = 5)
        val p = parts(w, store)
        val published = AtomicInteger()
        p.core.addListener { published.incrementAndGet() }
        poll(w, p)
        assertEquals("answering", p.line(HealthCore.HUB).text)
        val after = published.get()
        repeat(360) {
            clock.addAndGet(10_000)
            poll(w, p)
        }
        assertEquals(after, published.get(), "an hour of answers every 10 s publishes no new description")
        // the hub stops answering: attention once, its polls publish nothing, then alarm with its own alert
        status.set(500)
        clock.addAndGet(10_000)
        val failedAt = clock.get()
        poll(w, p)
        val hub = p.line(HealthCore.HUB)
        assertEquals(HealthBand.ATTENTION to "not answering: IOException: hub /api/: HTTP 500", hub.band to hub.text)
        assertEquals(failedAt, hub.since)
        val h = w.hub()
        assertTrue("hub not answering since " in w.stateLine() && "(${h.problem})" in w.stateLine(),
            "the state line is made from the same read: ${w.stateLine()}")
        val attention = published.get()
        assertEquals(after + 1, attention)
        repeat(28) {
            clock.addAndGet(10_000)
            poll(w, p)
        }
        assertEquals(attention, published.get(), "failing polls with the same problem publish nothing")
        clock.addAndGet(10_000)
        poll(w, p)
        val silent = p.line(HealthCore.HUB)
        assertEquals(Triple(HealthBand.ALARM, BastionWatcher.SILENT, failedAt), Triple(silent.band, silent.alert, silent.since))
        status.set(200)
        clock.addAndGet(10_000)
        poll(w, p)
        assertEquals(HealthBand.NORMAL, p.line(HealthCore.HUB).band, "it clears by itself")
    }

    @Test
    fun theCopyLine() {
        val now = clock.get()
        val at = LocalTime.of(3, 30)
        val z = ZoneOffset.UTC
        val fresh = NightlyCopy.Copy("damage-20270115-033000", now - 270 * 60_000L)
        assertEquals(HealthBand.NORMAL to "newest Jan 15 03:30", HealthParts.copyLine(fresh, null, null, at, now, z).let { it.band to it.text })
        val old = NightlyCopy.Copy("damage-20270113-033000", now - (52 * 60 + 30) * 60_000L)
        HealthParts.copyLine(old, null, now + 600_000, at, now, z).let {
            assertEquals(HealthBand.ATTENTION to "a catch-up copy at 08:10, then daily at 03:30", it.band to it.doing)
        }
        HealthParts.copyLine(old, null, null, at, now, z).let {
            assertEquals(Triple(HealthBand.ALARM, "copy", old.at), Triple(it.band, it.tag, it.since))
            assertEquals("newest Jan 13 03:30: older than 26 h", it.text)
            assertNull(it.alert, "no alert says a copy that did not run: the notice rule serves it")
        }
        assertEquals(HealthBand.ATTENTION, HealthParts.copyLine(null, now - 1_000, null, at, now, z).band, "the first copy running")
        assertEquals(HealthBand.ALARM, HealthParts.copyLine(null, null, null, at, now, z).band)
    }

    /** The copy's line through the parts: not read before the copy is given, off when none is, the alert when it is
     *  up, and a folder that cannot be listed leaves the line stale with the words. */
    @Test
    fun theCopyLineThroughTheParts() {
        val store = AlertStore(root.resolve("alerts.json"))
        val w = BastionWatcher(null, emptyList(), store, scope, clock::get, offReason = "no haUrl in the config")
        val p = parts(w, store)
        p.tickNow()
        assertEquals("not read yet", p.line(HealthCore.BACKUP).text, "before the copy is given")
        p.copy(null)
        p.tickNow()
        assertEquals(HealthBand.ATTENTION to "off: no backupDir in the config", p.line(HealthCore.BACKUP).let { it.band to it.text })
        val c = NightlyCopy(root.resolve("data"), root.resolve("no-such-backup"), 3, LocalTime.of(3, 30), root.resolve("r.json"),
            root.resolve("feed"), store, scope, clock::get, ZoneOffset.UTC)
        p.copy(c)
        val until = System.currentTimeMillis() + 10_000
        while (p.line(HealthCore.BACKUP).state != HealthCore.STALE && System.currentTimeMillis() < until) p.tickNow()
        assertTrue(p.line(HealthCore.BACKUP).reason!!.startsWith("the read failed: the copy folder could not be listed (NoSuchFileException"),
            "${p.line(HealthCore.BACKUP)}")
        store.raise(BastionWatcher.COPY, Severity.NORMAL, "Nightly copy failed: made-up. Older copies are untouched.", "", builtIn = true)
        p.tickNow()
        p.line(HealthCore.BACKUP).let {
            assertEquals(Triple(HealthBand.ALARM, BastionWatcher.COPY, "the next daily copy at 03:30 tries again"),
                Triple(it.band, it.alert, it.doing))
        }
        assertEquals(HealthBand.ATTENTION to "off: no haUrl in the config", p.line(HealthCore.HUB).let { it.band to it.text })
        assertEquals(HealthBand.ALARM, p.line(HealthCore.SERVICE).band, "a record that did not open is said")
        assertEquals(HealthBand.ATTENTION to "not attached", p.line(HealthCore.PHONE_LINE).let { it.band to it.text })
    }

    @Test
    fun theLastErrorAndTheSources() {
        val e = HealthParts.Errors()
        val before = e.reading().text
        Log.addSink(e)
        try {
            Log.e("made-up", "a made-up failure for the health record's test")
        } finally {
            Log.removeSink(e)
        }
        val r = e.reading()
        assertEquals(HealthBand.NORMAL, r.band, "an error is shown, never a band")
        assertTrue(r.text.endsWith("made-up: a made-up failure for the health record's test"), r.text)
        assertTrue(before != r.text)
        assertEquals("all 2 live", HealthParts.sources(listOf(
            buildJsonObject { put("name", "Reader"); put("availability", "live") },
            buildJsonObject { put("name", "Games"); put("availability", "live") })).text)
        HealthParts.sources(listOf(buildJsonObject { put("name", "Reader"); put("availability", "live") },
            buildJsonObject { put("name", "Torrents"); put("availability", "waiting"); put("reason", "no answer from qBittorrent yet") })).let {
            assertEquals(HealthBand.ATTENTION to "Torrents: waiting (no answer from qBittorrent yet)", it.band to it.text)
        }
        assertEquals("Reader: loading", HealthParts.sources(listOf(buildJsonObject {
            put("name", "Reader"); put("availability", "loading"); put("reason", "loading") })).text, "a reason that repeats the word is not said twice")
    }
}
