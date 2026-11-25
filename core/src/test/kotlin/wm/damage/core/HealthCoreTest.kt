package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.Change
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.HealthBand
import wm.damage.core.app.HealthCore
import wm.damage.core.app.HealthCore.Reading
import wm.damage.core.app.HealthLine
import wm.damage.core.app.Levels
import wm.damage.core.app.NoticeCore
import wm.damage.core.app.Notices
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b stage H1 (`private/build085/RB-lean-spec.md` §4.1, §4.2, §4.8, §5): the health record on the PC — each line
 * before its first read, `since` moving only on a band change, nothing published for a read that changes nothing (an
 * hour of hub polls), one notice per alarm episode for a line with no alert of its own and none for a line with one,
 * a failing reader making its own line stale and no other, the phone's lines and their going, a restart that reads
 * every line again and latches nothing, and `desc/health` served on the lane at level view, following Main's cards.
 * Over records in temp directories with made-up readings; nothing touches the real data directory.
 */
class HealthCoreTest {
    private val dir: Path = Files.createTempDirectory("damage-health")
    private val hosts = ArrayList<AppHost>()
    private var now = 1_800_000_000_000L       // 2027-01-15 08:00 UTC

    private fun host(): AppHost = AppHost(dir, AppHost.WINDOWS, CoreExecutor("damage-core-health"), { now })
        .also { hosts += it; it.open() }

    @AfterTest
    fun cleanUp() {
        for (h in hosts) { h.close(); h.executor.shutdown() }
        dir.toFile().deleteRecursively()
    }

    private fun drain(h: AppHost) = repeat(4) { h.executor.call {} }

    /** A core over [h] with its notices; [published] counts its descriptions; [raises] the record's notice writes. */
    private class Rig(val h: AppHost) {
        val core = HealthCore(h, NoticeCore(h, clock = h.clock), ZoneOffset.UTC)
        val published = AtomicInteger()
        val raises = CopyOnWriteArrayList<String>()

        init {
            core.addListener { published.incrementAndGet() }
            h.addListener { c: Change ->
                for (k in c.effect.items.keys) if (k.startsWith("rec.${Notices.WINDOW}.${HealthCore.SOURCE}:")) raises += k
            }
        }

        fun line(id: String): HealthLine = core.lines().single { it.id == id }
    }

    private fun rig(): Rig = Rig(host())

    private fun Rig.tick() {
        h.tick()
        drain(h)
    }

    private fun Rig.set(id: String, r: Reading) {
        core.set(id, r)
        drain(h)
    }

    private val diskOk = Reading("71 % free of 3.6 TB", HealthBand.NORMAL, "shown only: nothing is cleaned automatically")
    private val diskLow = Reading("1.6 % free of 3.6 TB", HealthBand.ALARM, "shown only: nothing is cleaned automatically",
        tag = "disk 98%")
    private val hubOk = Reading("answering", HealthBand.NORMAL, "asking every 10 s")

    @Test
    fun everyLineStartsNotReadYetSinceTheStart() {
        val r = rig()
        val ls = r.core.lines()
        assertEquals(HealthCore.LINES.map { it.first }, ls.map { it.id }, "the spec's §3.2 lines, in its order")
        for (l in ls) {
            assertEquals(r.core.startedAt, l.since, "${l.id}: since the service's start")
            assertEquals(HealthBand.NORMAL, l.band, l.id)
            if (l.id in HealthCore.PHONE_IDS) {
                assertEquals(HealthCore.LAST_KNOWN to "not reported", l.state to l.text, l.id)
                assertEquals("the phone has not reported since the service's start at 08:00", l.reason)
            } else {
                assertEquals(HealthCore.STALE to "not read yet", l.state to l.text, l.id)
                assertEquals("not read since the service's start at 08:00", l.reason)
            }
        }
        val d = assertNotNull(r.core.describe(HealthCore.SCOPE))
        assertEquals("All well", d["summary"]!!.jsonObject["line"]!!.jsonPrimitive.content)
        assertEquals("normal", d["worst"]!!.jsonObject["band"]!!.jsonPrimitive.content)
        assertNull(d["worst"]!!.jsonObject["id"], "no worst line while every line is normal")
        assertEquals(13, d["lines"]!!.jsonArray.size)
        assertEquals(AppNet.Gate(null, Levels.VIEW), r.core.gate(HealthCore.SCOPE))
        assertNull(r.core.gate("desc/main"))
        assertEquals(0, r.published.get())
    }

    /** §4.1's last sentence: published again only when what a line shows changes — an hour of a hub answering every
     *  10 s with the same state publishes nothing; `since` moves only on a band change (or to the source's own). */
    @Test
    fun anIdenticalReadPublishesNothingAndSinceMovesOnlyWithTheBand() {
        val r = rig()
        now += 1_000
        r.set(HealthCore.HUB, hubOk)
        assertEquals(1, r.published.get(), "the first read changes the line")
        assertEquals(r.core.startedAt, r.line(HealthCore.HUB).since, "normal before, normal now: since the start")
        val asOf = r.line(HealthCore.HUB).asOf
        repeat(360) {
            now += 10_000
            r.set(HealthCore.HUB, hubOk)
            r.tick()
        }
        assertEquals(1, r.published.get(), "an hour of identical polls publishes nothing")
        assertEquals(asOf, r.line(HealthCore.HUB).asOf, "nothing it shows changed")
        val failAt = now
        r.set(HealthCore.HUB, Reading("not answering: ConnectException: refused", HealthBand.ATTENTION, "asking every 10 s",
            since = failAt - 4_000))
        assertEquals(2, r.published.get())
        assertEquals(failAt - 4_000, r.line(HealthCore.HUB).since, "the source's own instant wins")
        now += 10_000
        r.set(HealthCore.DISK, diskOk)
        val diskSince = r.line(HealthCore.DISK).since
        now += 60_000
        r.set(HealthCore.DISK, diskOk.copy(text = "70 % free of 3.6 TB"))
        assertEquals(diskSince, r.line(HealthCore.DISK).since, "another reading in the same band keeps since")
        now += 60_000
        r.set(HealthCore.DISK, diskOk.copy(text = "4.9 % free of 3.6 TB", band = HealthBand.ATTENTION))
        assertEquals(now, r.line(HealthCore.DISK).since, "a band change moves since")
        val d = r.core.describe(HealthCore.SCOPE)!!
        assertEquals("hub", d["worst"]!!.jsonObject["id"]!!.jsonPrimitive.content, "the first line of the worst band")
        assertEquals("Home hub: not answering: ConnectException: refused", d["summary"]!!.jsonObject["line"]!!.jsonPrimitive.content)
    }

    /** §4.2's notice rule with Q1's length: one notice per alarm episode for a line with no alert of its own; none for
     *  a line whose alert already says it, none for attention. */
    @Test
    fun anAlarmHeldForTheLengthRaisesOneNoticePerEpisode() {
        val r = rig()
        val start = now
        r.set(HealthCore.DISK, diskLow)
        r.set(HealthCore.HUB, Reading("not answering", HealthBand.ALARM, "asking every 10 s; the alert is up", tag = "hub",
            alert = "watcher.silent"))
        r.set(HealthCore.CAP, Reading("claude-work: no limit (memory.max is max)", HealthBand.ATTENTION, "shown only"))
        now = start + HealthCore.NOTICE_AFTER_MS - 1
        r.tick()
        assertEquals(emptyList(), r.raises, "not before the length")
        now = start + HealthCore.NOTICE_AFTER_MS
        r.tick()
        drain(r.h)
        assertEquals(listOf("rec.notices.health:disk"), r.raises, "the disk, once; the hub has its own alert; attention none")
        repeat(30) { now += 2_000; r.tick() }
        drain(r.h)
        assertEquals(1, r.raises.size, "one per episode")
        val body = Notices.parse("health:disk", r.h.read(Notices.WINDOW).third.items["rec.notices.health:disk"]!!.value
            as JsonObject)!!.body
        assertEquals("Disk: 1.6 % free of 3.6 TB since 08:00. Shown only: nothing is cleaned automatically.", body)
        // the episode ends when the line leaves alarm; the next one raises one more
        r.set(HealthCore.DISK, diskOk)
        now += 60_000
        r.set(HealthCore.DISK, diskLow)
        now += HealthCore.NOTICE_AFTER_MS
        r.tick()
        drain(r.h)
        assertEquals(2, r.raises.size, "a second episode, a second notice")
        // a line whose read fails stays in alarm (its last reading) and raises nothing more in the same episode
        r.core.failed(HealthCore.DISK, "IOException: made-up")
        repeat(10) { now += 60_000; r.tick() }
        drain(r.h)
        assertEquals(2, r.raises.size)
    }

    /** §4.8: a reader that fails leaves its own line stale with the words, said once per run; the others stay live. */
    @Test
    fun aFailingReaderMakesOnlyItsOwnLineStale() {
        val said = CopyOnWriteArrayList<String>()
        val sink = Log.Sink { _, tag, m -> if (tag == "health") said += m }
        Log.addSink(sink)
        try {
            val r = rig()
            r.set(HealthCore.DISK, diskOk)
            r.set(HealthCore.HUB, hubOk)
            r.core.failed(HealthCore.DISK, "NoSuchFileException: /made/up")
            r.core.failed(HealthCore.DISK, "NoSuchFileException: /made/up")
            drain(r.h)
            val disk = r.line(HealthCore.DISK)
            assertEquals(HealthCore.STALE to "the read failed: NoSuchFileException: /made/up", disk.state to disk.reason)
            assertEquals(diskOk.text, disk.text, "the last reading is kept")
            assertEquals(HealthCore.LIVE, r.line(HealthCore.HUB).state, "the other lines go on")
            assertEquals(1, said.count { "the read failed" in it }, "said once per run of failures: $said")
            r.set(HealthCore.DISK, diskOk)
            assertEquals(HealthCore.LIVE to null, r.line(HealthCore.DISK).let { it.state to it.reason })
            assertTrue(said.any { "disk is read again" in it })
        } finally {
            Log.removeSink(sink)
        }
    }

    /** The phone's lines as it reports them; a line that is not its own is left out; its going makes them lastKnown. */
    @Test
    fun thePhonesLinesAndTheirGoing() {
        val r = rig()
        val reach = HealthLine(HealthCore.REACH, "x", "PC answering", HealthBand.NORMAL, now - 5_000, now - 5_000,
            HealthCore.PHONE, HealthCore.LIVE, "pinging every 15 s")
        val notMine = reach.copy(id = HealthCore.DISK, text = "a phone's word on the PC's disk")
        r.core.phone(listOf(reach, notMine), now)
        drain(r.h)
        val l = r.line(HealthCore.REACH)
        assertEquals(Triple("PC answering", HealthCore.LIVE, now), Triple(l.text, l.state, l.receivedAt))
        assertEquals("PC reach", l.label, "the PC's label")
        assertEquals("not read yet", r.line(HealthCore.DISK).text, "the PC's own line is never the phone's")
        now += 60_000
        r.core.phoneGone(now)
        drain(r.h)
        val gone = r.line(HealthCore.REACH)
        assertEquals(HealthCore.LAST_KNOWN to "phone not connected since 08:01", gone.state to gone.reason)
        assertEquals("PC answering", gone.text, "its last state, with its age from asOf")
    }

    /** A PC restart: every line is read again from its source (none is saved), the notice the earlier process raised
     *  stays in the notice record, and a line still in alarm starts a new episode — nothing needs a hand to clear. */
    @Test
    fun aRestartReadsEveryLineAgainAndLatchesNothing() {
        val first = rig()
        first.set(HealthCore.DISK, diskLow)
        now += HealthCore.NOTICE_AFTER_MS
        first.tick()
        drain(first.h)
        assertEquals(1, first.raises.size)
        first.h.close()
        first.h.executor.shutdown()
        hosts.remove(first.h)
        now += 3_600_000                              // the PC was off for an hour
        val second = rig()
        assertTrue(second.h.read(Notices.WINDOW).third.items.containsKey("rec.notices.health:disk"),
            "the earlier notice is the notice record's, kept")
        for (l in second.core.lines()) {
            assertEquals(second.core.startedAt, l.since, l.id)
            assertTrue(l.text == "not read yet" || l.text == "not reported", "${l.id}: ${l.text}")
        }
        second.set(HealthCore.DISK, diskLow)
        now += HealthCore.NOTICE_AFTER_MS
        second.tick()
        drain(second.h)
        assertEquals(1, second.raises.size, "the disk still low after the restart: a new episode, one notice")
        second.set(HealthCore.DISK, diskOk)
        assertEquals(HealthBand.NORMAL, second.line(HealthCore.DISK).band, "it clears by itself")
    }

    private fun WebRig.page(): Page {
        val (_, secret) = approved(Levels.VIEW, "fixture page")
        val p = Page(port, secret)
        p.hello()
        p.next("welcome")
        return p
    }

    private fun Page.desc(scope: String, within: Long = 10_000): JsonObject? {
        val until = System.currentTimeMillis() + within
        while (System.currentTimeMillis() < until) {
            val f = frames.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (AppWire.type(f) == "snap" && f["scope"]?.jsonPrimitive?.content == scope) return f["desc"]!!.jsonObject
        }
        return null
    }

    /** `desc/health` on the lane at level view: described at the ask and again only when a line changes. */
    @Test
    fun descHealthIsServedAtViewAndDescribedAgainOnlyOnAChange() = WebRig().use { w ->
        val core = HealthCore(w.host, NoticeCore(w.host, clock = w.host.clock), ZoneOffset.UTC)
        w.net.register(core)
        val p = w.page()
        p.send(buildJsonObject { put("t", "sub"); put("scope", HealthCore.SCOPE) })
        val first = assertNotNull(p.desc(HealthCore.SCOPE), "a page at level view reads it")
        assertEquals(13, first["lines"]!!.jsonArray.size)
        core.set(HealthCore.DISK, diskOk)
        val again = assertNotNull(p.desc(HealthCore.SCOPE))
        assertEquals("71 % free of 3.6 TB", again["lines"]!!.jsonArray.map { it.jsonObject }
            .single { it["id"]!!.jsonPrimitive.content == "disk" }["text"]!!.jsonPrimitive.content)
        core.set(HealthCore.DISK, diskOk)
        core.set(HealthCore.DISK, diskOk)
        assertNull(p.desc(HealthCore.SCOPE, within = 700), "an identical read is not described again")
        p.close()
    }

    /** What Main's cards follow beside Reader's own sources reaches the card listener: a window served with a card
     *  whose source moves, and a card-only window's record; a provider with no card does not. */
    @Test
    fun theCardListenerFollowsWhatMainsCardsFollow() = WebRig().use { w ->
        val told = AtomicInteger()
        w.net.addCardListener { told.incrementAndGet() }
        val sources = CopyOnWriteArrayList<() -> Unit>()
        fun provider(prefix: String) = object : AppNet.Described {
            override fun gate(scope: String) = if (scope.startsWith(prefix)) AppNet.Gate("t", Levels.VIEW) else null
            override fun describe(scope: String) = buildJsonObject { put("from", prefix) }
            override fun follows(scope: String, c: Change?) = c == null
            override fun addListener(l: () -> Unit) { sources += l }
        }
        w.net.register(provider("plain/"))                 // no card
        sources.single().invoke()
        w.host.executor.call {}
        assertEquals(0, told.get(), "a provider with no card is not Main's")
        w.net.serve("u", scopes = provider("u/"), card = { buildJsonObject { put("window", "u") } })
        sources.last().invoke()
        w.host.executor.call {}
        assertEquals(1, told.get(), "a card window's source moved")
        w.net.serve("t", card = { buildJsonObject { put("window", "t") } })     // card only: Main follows its record
        w.rec.ask(w.host, op("local", "s", 1, "set", listOf("x"), j("v")))
        repeat(3) { w.host.executor.call {} }
        assertEquals(2, told.get(), "a card-only window's record changed")
    }
}
