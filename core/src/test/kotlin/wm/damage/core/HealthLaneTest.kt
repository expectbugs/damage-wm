package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.AppClient
import wm.damage.core.app.AppHost
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
import wm.damage.core.app.PhoneHealth
import wm.damage.core.app.WorkingCopy
import wm.damage.core.bastion.AlertDelivery
import wm.damage.core.shell.HostTag
import wm.damage.core.transport.CfwTransportBase

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * R-b stage H2 (`private/build085/RB-lean-spec.md` §4.4, §4.5, §4.8, §5): the lane's two additions and the phone's
 * half. On a real content host bound to loopback port 0: the phone's `health` reaches the PC's record, only its last
 * connection's end leaves those lines `lastKnown`, and a page's or another device's is ignored; with a real working copy
 * and app lane, the phone's lines go at the welcome and the PC's description comes back as the link cell's tag, and a
 * reconnect sends them again. Without a network: the phone sends on a change and at a welcome only, the tag's order (PC,
 * hub, disk, copy), the battery's strict "less than", nothing latches, and the reach line names HomeWatch's alert so
 * the PC rings no second notice (the second pin, watched failing without it), and a send lost with its connection is
 * made again at the next pass. Made-up lines; temp directories only.
 */
class HealthLaneTest {
    private val dir: Path = Files.createTempDirectory("damage-health-lane")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closing = ArrayList<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        for (c in closing.asReversed()) c.close()
        dir.toFile().deleteRecursively()
    }

    // ---- the PC ----

    private fun pc(localSecret: String? = null): Pair<AppLaneRig, HealthCore> {
        val rig = AppLaneRig(localSecret = localSecret).also { closing += it }
        val core = HealthCore(rig.host, NoticeCore(rig.host, clock = rig.host.clock), ZoneOffset.UTC)
        rig.net.register(core)
        rig.net.health = core
        return rig to core
    }

    private fun HealthCore.line(id: String): HealthLine = lines().single { it.id == id }

    private fun reachLine(text: String) = HealthLine(HealthCore.REACH, "-", text, HealthBand.NORMAL, 1L, 1L,
        HealthCore.PHONE, HealthCore.LIVE, "pinged every 15 s")

    private fun RawLane.health(vararg ls: HealthLine) = send(AppWire.health(ls.toList()))

    @Test
    fun thePhonesLinesReachTheRecordAndOnlyItsLastConnectionsEndLeavesThemLastKnown() {
        val (rig, core) = pc()
        val a = RawLane(rig.port).also { closing += it }
        a.welcome()
        a.health(reachLine("answering on a"))
        a.quiet()
        val got = core.line(HealthCore.REACH)
        assertEquals(Triple("answering on a", HealthCore.LIVE, HealthCore.PHONE), Triple(got.text, got.state, got.source))
        assertEquals("PC reach", got.label, "the PC's own label, whatever the phone sent")
        // a reconnect whose new connection reports before the old one's end is seen
        val b = RawLane(rig.port).also { closing += it }
        b.welcome()
        b.health(reachLine("answering on b"))
        b.quiet()
        a.close()
        eventually("a's end handled") { rig.net.connectionLines().size == 1 }
        assertEquals("answering on b" to HealthCore.LIVE, core.line(HealthCore.REACH).let { it.text to it.state },
            "the old connection's end does not leave the new connection's lines lastKnown")
        b.close()
        eventually("b's end leaves the phone's lines lastKnown") {
            HealthCore.PHONE_IDS.all { core.line(it).state == HealthCore.LAST_KNOWN }
        }
        assertTrue(core.line(HealthCore.REACH).reason!!.startsWith("phone not connected since"))
    }

    @Test
    fun aPagesOrAnotherDevicesHealthIsIgnored() {
        val (rig, core) = pc(localSecret = "sec")
        val local = RawLane(rig.port, token = "", local = "sec").also { closing += it }
        local.welcome()
        local.health(reachLine("from the PC's own command line"))
        local.quiet()
        assertEquals("not reported", core.line(HealthCore.REACH).text, "only the phone reports the phone's lines")

        WebRig().use { w ->
            val c = HealthCore(w.host, NoticeCore(w.host, clock = w.host.clock), ZoneOffset.UTC)
            w.net.register(c)
            w.net.health = c
            val (_, secret) = w.approved(Levels.FULL, "fixture page")
            val p = Page(w.port, secret)
            p.hello()
            p.next("welcome")
            p.send(AppWire.health(listOf(reachLine("from a page"))))
            p.send(AppWire.ping(7))
            val until = System.currentTimeMillis() + 10_000
            var pong = false
            while (!pong && System.currentTimeMillis() < until) {
                val f = p.frames.poll(50, TimeUnit.MILLISECONDS) ?: continue
                pong = AppWire.type(f) == "pong"
            }
            assertTrue(pong, "the page's ping was answered, so its health was handled before it")
            assertEquals("not reported", c.line(HealthCore.REACH).text, "a page's health is ignored")
            p.close()
        }
    }

    // ---- the phone ----

    private fun copy(name: String): WorkingCopy {
        val wc = WorkingCopy(Files.createDirectories(dir.resolve(name)), TEST_ACTIONS, CoreExecutor("damage-core-health-$name"), contracts = mapOf("t" to 1))
        closing += AutoCloseable {
            wc.close()
            wc.executor.shutdown()
        }
        return wc
    }

    private class Phone(val wc: WorkingCopy, scope: CoroutineScope) {
        @Volatile var now = 1_800_000_000_000L      // 2027-01-15 08:00 UTC
        @Volatile var glasses = PhoneHealth.Glasses("ok", false, 80, CfwTransportBase.Ladder(5, 5, null))
        val sent = CopyOnWriteArrayList<List<HealthLine>>()
        /** The live connection (a new object = a new connection's welcome; null = none), and whether a send lands. */
        @Volatile var conn: Any? = Any()
        @Volatile var ok = true
        val ph = PhoneHealth(wc, { glasses }, "0.85 (85)", scope, clock = { now }, zone = ZoneOffset.UTC, everyMs = 0,
            send = { if (ok) sent += it; ok }, connection = { conn })

        fun last(id: String): HealthLine = sent.last().single { it.id == id }
    }

    private fun phone(): Phone = Phone(copy("phone").also { it.open() }, scope)

    private fun desc(vararg ls: HealthLine): JsonObject = buildJsonObject {
        put("lines", buildJsonArray { for (l in ls) add(l.json()) })
    }

    private fun pcLine(id: String, tag: String?, since: Long, band: HealthBand = HealthBand.ALARM) =
        HealthLine(id, id, "wrong", band, since, since, HealthCore.PC, HealthCore.LIVE, "shown only", tag = tag)

    @Test
    fun thePhoneSendsOnAChangeAndAtAWelcomeOnly() {
        val p = phone()
        p.ph.step()
        assertEquals(1, p.sent.size, "the first pass sends")
        assertEquals(HealthCore.PHONE_IDS, p.sent.last().map { it.id }.toSet(), "the phone's four lines, and only those")
        p.ph.step()
        p.now += 60_000
        p.ph.step()
        assertEquals(1, p.sent.size, "a pass that reads the same sends nothing, whatever the time")
        p.glasses = p.glasses.copy(batteryPct = 19)
        p.ph.step()
        assertEquals(2, p.sent.size)
        assertEquals(HealthBand.ATTENTION, p.last(HealthCore.GLASSES_BATTERY).band, "19 % is less than 20 %")
        p.glasses = p.glasses.copy(batteryPct = 20)
        p.ph.step()
        assertEquals(HealthBand.NORMAL, p.last(HealthCore.GLASSES_BATTERY).band, "exactly 20 % is not less than 20 %")
        p.conn = Any()
        p.ph.step()
        assertEquals(4, p.sent.size, "a new connection is sent them whatever they hold")
        p.glasses = p.glasses.copy(word = "no lease")
        p.ph.step()
        assertEquals(HealthBand.ATTENTION, p.last(HealthCore.GLASSES_LINK).band)
        p.glasses = p.glasses.copy(word = "silent")
        p.ph.step()
        assertEquals(HealthBand.NORMAL, p.last(HealthCore.GLASSES_LINK).band, "Silent Mode is on purpose")
        p.glasses = p.glasses.copy(ladder = CfwTransportBase.Ladder(5, 3, "a lens restarted at 14:02"))
        p.ph.step()
        assertEquals(HealthBand.ATTENTION, p.last(HealthCore.GLASSES_STAGE).band, "a hold-back")
    }

    /** The send rule by connection: what a connection is owed stays owed until a send on it has succeeded. A failed
     *  send on an unchanged connection is not a state the real code reaches (a failed write closes the socket, and the
     *  copy's lane names the new connection before its welcome arrives); the behaviour that matters, a new connection
     *  sent the lines whatever they hold, is [thePhoneSendsOnAChangeAndAtAWelcomeOnly]'s. Watched failing with the
     *  connection left out of the comparison. */
    @Test
    fun aSendLostWithItsConnectionIsMadeAgainAtTheNextPass() {
        val p = phone()
        p.ph.step()
        assertEquals(1, p.sent.size)
        p.conn = Any()
        p.ok = false
        p.ph.step()
        assertEquals(1, p.sent.size, "the send went nowhere")
        p.ok = true
        p.ph.step()
        assertEquals(2, p.sent.size, "the next pass sends the same lines again, on the connection live now")
        p.ph.step()
        assertEquals(2, p.sent.size, "and then nothing more")
        p.conn = null
        p.glasses = p.glasses.copy(batteryPct = 50)
        p.ph.step()
        assertEquals(2, p.sent.size, "no connection: nothing is sent")
        p.conn = Any()
        p.ph.step()
        assertEquals(3, p.sent.size, "the next connection gets the change")
    }

    @Test
    fun theTagFollowsPcHubDiskCopyAndNothingLatches() {
        val p = phone()
        val t0 = p.now
        p.wc.reach(AppClient.Reach.Up)
        val hub = pcLine(HealthCore.HUB, "hub", t0 - 10 * 60_000)
        val disk = pcLine(HealthCore.DISK, "disk 99%", t0 - 60_000)
        val copy = pcLine(HealthCore.BACKUP, "copy", t0 - 2 * 86_400_000L)
        p.ph.described(HealthCore.SCOPE, desc(copy, disk, hub))
        assertEquals(HostTag("hub", t0 - 10 * 60_000), p.ph.step().tag)
        assertEquals("hub 10m", p.ph.view.tag.text(t0))
        p.ph.described(HealthCore.SCOPE, desc(copy, disk))
        assertEquals(HostTag("disk 99%"), p.ph.step().tag, "a tag that carries its reading has no age")
        p.ph.described(HealthCore.SCOPE, desc(copy))
        assertEquals("copy 2d", p.ph.step().tag.text(t0))
        // the PC out of reach comes first, its age from the unanswered ping
        p.wc.reach(AppClient.Reach.Down(t0 - 4 * 60_000))
        val down = p.ph.step()
        assertEquals("PC 4m", down.tag.text(t0))
        assertTrue(down.sentence.startsWith("PC reach: not answering since 07:56"), down.sentence)
        p.now += 2 * 60_000
        assertEquals("PC 6m", p.ph.step().tag.text(p.now), "counted per minute")
        // back: every line from its source again, nothing held
        p.wc.reach(AppClient.Reach.Up)
        p.ph.described(HealthCore.SCOPE, desc(copy.copy(band = HealthBand.NORMAL, tag = null)))
        val back = p.ph.step()
        assertEquals(HostTag.NONE to "", back.tag to back.sentence, "all well again: the dim mark, no sentence")
    }

    @Test
    fun theAgeMovesOnTheWallClocksMinute() {
        val m = 1_800_000_000_000L                 // a whole minute
        assertEquals("<1m", HostTag.age(m + 59_000, m + 59_999))
        assertEquals("1m", HostTag.age(m + 59_000, m + 60_000), "a wall minute later, with the clock cell")
        assertEquals("59m", HostTag.age(m, m + 59 * 60_000))
        assertEquals("1h", HostTag.age(m, m + 60 * 60_000))
        assertEquals("23h", HostTag.age(m, m + 24 * 3_600_000L - 1))
        assertEquals("1d", HostTag.age(m, m + 24 * 3_600_000L))
        assertEquals("PC no", HostTag(HostTag.REFUSED).text(m), "a refusal has no age")
    }

    /** The second pin (H1's note): the reach line in alarm names HomeWatch's "Home unreachable", so the PC's notice
     *  rule, which serves lines with no alert of their own, rings nothing for it. Watched failing without the name. */
    @Test
    fun theReachLineNamesHomeWatchsAlertSoThePcRingsNoSecondNotice() {
        var pcNow = 1_800_000_000_000L
        val h = AppHost(Files.createDirectories(dir.resolve("pc")), AppHost.WINDOWS, CoreExecutor("damage-core-health-pc"), { pcNow })
        h.open()
        closing += AutoCloseable { h.close(); h.executor.shutdown() }
        val core = HealthCore(h, NoticeCore(h, clock = h.clock), ZoneOffset.UTC)
        val raises = CopyOnWriteArrayList<String>()
        h.addListener { c: Change ->
            for (k in c.effect.items.keys) if (k.startsWith("rec.${Notices.WINDOW}.${HealthCore.SOURCE}:")) raises += k
        }
        val p = phone()
        p.wc.reach(AppClient.Reach.Down(p.now - 60_000))
        p.ph.step()
        val reach = p.last(HealthCore.REACH)
        assertEquals(HealthBand.ALARM, reach.band)
        core.phone(p.sent.last(), pcNow)
        repeat(4) { h.executor.call {} }
        pcNow += HealthCore.NOTICE_AFTER_MS
        h.tick()
        repeat(4) { h.executor.call {} }
        assertEquals(emptyList(), raises.toList(), "the reach line has an alert of its own: no health notice")
        assertEquals(AlertDelivery.HOME_THREAD, reach.alert)
    }

    /** The whole path: a real working copy and app lane over the content host. */
    @Test
    fun theLaneCarriesBothWaysAndAReconnectSendsAgain() {
        val (rig, core) = pc()
        val wc = copy("wire")
        val ph = PhoneHealth(wc, { PhoneHealth.Glasses("ok", false, 80, null) }, "0.85 (85)", scope, everyMs = 0)
        wc.laneWatcher = ph
        fun client(s: String) = AppClient("127.0.0.1", rig.port, AppClient.Credential.Token(AppLaneRig.TOKEN), s, wc, scope,
            contracts = mapOf("t" to 1), retryPacingMs = 20, refusedPacingMs = 50, pingEveryMs = 0)
        wc.start { s -> client(s) }
        // each wait runs a pass per look, as the phone's 15 s pace does
        eventually("the phone's lines on the PC") {
            ph.step()
            core.line(HealthCore.REACH).let { it.state == HealthCore.LIVE && it.text.startsWith("answering") }
        }
        assertEquals("ok", core.line(HealthCore.GLASSES_LINK).text)
        core.set(HealthCore.DISK, Reading("1.6 % free of 3.6 TB", HealthBand.ALARM, "shown only", tag = "disk 98%"))
        eventually("the PC's description back on the phone, as the link cell's tag") { ph.view.tag == HostTag("disk 98%") }
        assertTrue(ph.view.sentence.startsWith("Disk: 1.6 % free of 3.6 TB since"), ph.view.sentence)
        // each wait decides on ONE evaluation (`waitUntil`): the first form of this test read a moment before the old
        // connection's end (its lines still live) and then once more after it, and failed on the second reading
        val old = assertNotNull(wc.client?.welcome).conn
        wc.reconnect { s -> client(s) }
        runBlocking {
            waitUntil(10_000, 20, { "the old connection's end and the new welcome: ${rig.net.connectionLines()}" }) {
                val now = wc.client?.welcome?.conn
                now != null && now != old && rig.net.connectionLines().none { "connection $old " in it }
            }
            waitUntil(10_000, 20, { "the new connection sent the lines again: ${core.line(HealthCore.REACH)}" }) {
                ph.step()
                core.line(HealthCore.REACH).state == HealthCore.LIVE
            }
        }
    }
}
