package wm.damage.core

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionService
import wm.damage.core.bastion.PhonePresence
import wm.damage.core.bastion.PhonePresence.LocalNet
import wm.damage.core.bastion.PhonePresence.PathKind
import wm.damage.core.bastion.PhonePresence.PathRead
import wm.damage.core.bastion.StoreAnswer
import wm.damage.core.util.Log

/**
 * Build W, package E (the lead's decision F.3): the built-in value `damage.phone` from the PC's own Tailscale status.
 * The status is FIXTURE JSON with made-up addresses (the field names as `tailscale status --json` writes them); the
 * command is a stand-in lambda — the real one is never run by a test. Pinned: the path read for each case (ruling
 * R3.1: direct to a private address on one of the PC's networks, or to one of R3.1's private ranges outside its
 * point-to-point interfaces' networks = home; relayed or public = far; offline, idle, absent, a private address in a
 * tunnel's network, an address outside R3.1's ranges, the tailnet's own ranges, a stopped daemon = no reading); home
 * at once, away only after fifteen minutes of a far path, unknown otherwise; a path alternating between the bus's two
 * networks stays home; one line per change of the value; a read still running is skipped and said, never two at once.
 */
class PhonePresenceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }

    @After
    fun tearDown() {
        Log.removeSink(sink)
        scope.cancel()
    }

    /** The PC's networks (made-up): the tunnels listed first, so which one decides is by kind, never by order. */
    private val nets = listOf(
        LocalNet("tun-test", byteArrayOf(10, 77, 0, 8), 16, pointToPoint = true),
        LocalNet("tun6-test", PhonePresence.literal("fd55:77::8")!!, 64, pointToPoint = true),
        LocalNet("eth-test", byteArrayOf(10, 250.toByte(), 3, 7), 24),
        LocalNet("wlan-test", PhonePresence.literal("fd00:abcd::5")!!, 64),
    )

    /** A status with the phone's peer (made-up addresses) and one other peer. */
    private fun status(
        online: Boolean = true, active: Boolean = true, cur: String = "", relay: String = "xyz", peerRelay: String = "",
        ips: String = """"100.101.102.103","fd7a:115c:a1e0::1:2"""", dns: String = "test-phone.example-tailnet.ts.net.",
        backend: String = "Running",
    ) = """
        {"Version":"1.0.0-test","BackendState":"$backend","Self":{"TailscaleIPs":["100.64.0.1"]},
         "Peer":{
          "nodekey:other":{"TailscaleIPs":["100.64.0.9"],"DNSName":"other.example-tailnet.ts.net.","Online":true,
                           "Active":true,"CurAddr":"10.250.3.9:41641","Relay":"xyz","PeerRelay":""},
          "nodekey:phone":{"TailscaleIPs":[$ips],"DNSName":"$dns","OS":"android","Online":$online,"Active":$active,
                           "CurAddr":"$cur","Relay":"$relay","PeerRelay":"$peerRelay","Addrs":null}
         }}
    """.trimIndent()

    private fun read(json: String, phone: String = "100.101.102.103", on: List<LocalNet> = nets) =
        PhonePresence.read(json, phone, on)

    @Test
    fun theStatusReadDecidesThePath() {
        assertEquals(PathKind.HOME, read(status(cur = "10.250.3.20:41641")).kind, "direct to the bus network")
        assertEquals(PathKind.HOME, read(status(cur = "[fd00:abcd::20]:41641")).kind, "IPv6 on a local network")
        // R3.1 pin 1 (rewritten from dec. F.3's `unknown`): a private address on no network of the PC's is routed from
        // it — the bus's second network — and reads home, in each of R3.1's ranges, with R3.1's words
        val elsewhere = read(status(cur = "10.99.0.4:41641"))
        assertEquals(PathKind.HOME, elsewhere.kind, "a private address routed from the PC is home")
        assertEquals("direct, to a private address routed from this PC", elsewhere.why)
        for (cur in listOf("172.20.1.4:41641", "192.168.77.4:41641", "[fd99:5::4]:41641", "[fe80::4%test0]:41641"))
            assertEquals(PathKind.HOME, read(status(cur = cur)).kind, "R3.1's range: $cur")
        // R3.1 pin 2: a private address inside a point-to-point interface's network is a tunnel, not the bus
        val tunnel = read(status(cur = "10.77.3.4:41641"))
        assertEquals(PathKind.NONE, tunnel.kind, "a private address in a tunnel's network is not home")
        assertTrue("tun-test" in tunnel.why && "point-to-point" in tunnel.why && "not the bus" in tunnel.why, tunnel.why)
        assertEquals(PathKind.NONE, read(status(cur = "[fd55:77::4]:41641")).kind, "IPv6 in a tunnel's network")
        // the PC's own network is decided first: every interface carries fe80::/64, a tunnel's too
        val linkLocal = listOf(LocalNet("tun6-test", PhonePresence.literal("fe80::8")!!, 64, pointToPoint = true),
            LocalNet("wlan-test", PhonePresence.literal("fe80::5")!!, 64))
        assertEquals("direct, on the network of wlan-test", read(status(cur = "[fe80::4%test0]:41641"), on = linkLocal).why)
        assertEquals(PathKind.NONE, read(status(cur = "[fe80::4%test0]:41641"), on = linkLocal.take(1)).kind,
            "with only a tunnel's fe80::/64, a link-local path is in the tunnel's network")
        // outside R3.1's list (in Reach's bus class all the same): no network of the PC's, so no reading
        assertEquals(PathKind.NONE, read(status(cur = "169.254.9.4:41641")).kind, "an IPv4 link-local address elsewhere")
        assertEquals(PathKind.NONE, read(status(cur = "[fec0::4]:41641")).kind, "an old IPv6 site-local address elsewhere")
        // R3.1 pin 3: the tailnet's own ranges are not private here (100.64/10, and its IPv6 range inside fc00::/7)
        assertEquals("the path address is tailnet", read(status(cur = "100.70.1.2:41641")).why)
        assertEquals("the path address is tailnet", read(status(cur = "[fd7a:115c:a1e0::9]:41641")).why)
        assertEquals(PathKind.FAR, read(status(cur = "203.0.113.9:41641")).kind, "direct to a public address")
        assertEquals("relayed", read(status()).why)
        assertEquals(PathKind.FAR, read(status()).kind, "no direct address, a DERP region: relayed")
        assertEquals(PathKind.FAR, read(status(relay = "", peerRelay = "100.64.0.9:41641")).kind, "a peer relay")
        assertEquals(PathKind.NONE, read(status(relay = "")).kind, "no path at all")
        assertEquals(PathKind.NONE, read(status(online = false)).kind, "offline")
        assertTrue("no current path" in read(status(active = false, cur = "10.250.3.20:41641")).why, "idle: no current path")
        assertEquals(PathKind.NONE, read(status(ips = "\"100.101.102.200\"")).kind, "the phone absent")
        assertEquals("tailscale is Stopped", read(status(backend = "Stopped")).why)
        assertEquals(PathKind.NONE, read("not json").kind)
        assertEquals(PathKind.HOME, read(status(cur = "10.250.3.20:41641"), phone = "fd7a:115c:a1e0::1:2").kind,
            "matched by its IPv6 tailnet address")
        assertEquals(PathKind.HOME, read(status(cur = "10.250.3.20:41641"), phone = "test-phone").kind,
            "a MagicDNS short name matches the peer's DNS name")
        assertEquals(PathKind.NONE, read(status(cur = "10.250.3.20:41641"), phone = "other-phone").kind)
        // neither the state line's words nor the log's detail carry the address (fix round 2, R2.3)
        val home = read(status(cur = "10.250.3.20:41641"))
        assertTrue("10.250" !in home.why && "10.250" !in home.detail && "41641" !in home.detail, "${home.why} / ${home.detail}")
        // a name is never looked up: a non-literal is not an address
        assertEquals(null, PhonePresence.literal("test-phone"))
        assertEquals(null, PhonePresence.literal("10.250.3.300"))
    }

    @Test
    fun homeAtOnceAwayAfterFifteenMinutesOfAFarPathUnknownOtherwise() {
        Log.addSink(sink)
        val clock = AtomicLong(T0)
        val said = CopyOnWriteArrayList<String>()
        val p = PhonePresence("100.101.102.103", { error("not run") }, { nets }, scope, clock::get) { said += it }
        val far = PathRead(PathKind.FAR, "relayed", "relayed through DERP xyz")
        val home = PathRead(PathKind.HOME, "direct, on the network of eth-test")     // what the read gives (R2.3: no address)
        val none = PathRead(PathKind.NONE, "the phone's peer is offline")
        assertEquals(PhonePresence.UNKNOWN, p.word)

        p.observe(home, clock.get())
        assertEquals(PhonePresence.HOME, p.word, "home at once")
        assertEquals(listOf("damage.phone unknown -> home (by the connection path: ${home.detail})"), said)
        // relayed: unknown at once (a path that just changed), away only after fifteen minutes of it
        for (m in 0..14) {
            p.observe(far, clock.get())
            assertEquals(PhonePresence.UNKNOWN, p.word, "minute $m of the far path")
            clock.addAndGet(MIN)
        }
        p.observe(far, clock.get())
        assertEquals(PhonePresence.AWAY, p.word, "fifteen minutes relayed")
        assertTrue(said.last().startsWith("damage.phone unknown -> away (by the connection path: relayed through DERP xyz, for 15 min"), said.last())
        // a run broken by no reading starts the fifteen minutes again
        p.observe(none, clock.get())
        assertEquals(PhonePresence.UNKNOWN, p.word)
        clock.addAndGet(MIN)
        p.observe(far, clock.get())
        clock.addAndGet(14 * MIN)
        p.observe(far, clock.get())
        assertEquals(PhonePresence.UNKNOWN, p.word, "fourteen minutes since the run began again")
        clock.addAndGet(MIN)
        p.observe(far, clock.get())
        assertEquals(PhonePresence.AWAY, p.word)
        // a relay changing to a public direct path is still far: the run goes on
        p.observe(PathRead(PathKind.FAR, "direct to a public address"), clock.get())
        assertEquals(PhonePresence.AWAY, p.word)
        // every change of the value: one said line and one log line, nothing else said
        assertEquals(5, said.size, said.joinToString("\n"))
        for (line in said.toSet()) assertEquals(said.count { it == line }, logs.count { it.endsWith(line) }, "one log line each: $line")
        assertTrue(p.stateLine().startsWith("phone: away since "), p.stateLine())
    }

    /**
     * Fix round 2 (R2.3; review 2, finding 3): no path address and no port in the PC's log, the journal's change line
     * (`say`, which the PC's host writes to its journal as a `presence` note) or the state line. Every fixture path is
     * read and applied in turn — direct to the bus network (v4 and v6), a private address routed from this PC (v4 and
     * v6, R3.1), one in a tunnel's network, an IPv4 link-local one elsewhere, a public address, a tailnet address, one
     * that is not understood, a peer relay, a DERP region — and every line
     * written is searched for the fixture addresses and their port. (The DERP region code is not an address and is
     * said.) Watched failing with the bus network's detail given its address back: the log and the journal named it.
     */
    @Test
    fun noPathAddressOrPortInTheLogTheJournalOrTheStateLine() {
        Log.addSink(sink)
        val clock = AtomicLong(T0)
        val said = CopyOnWriteArrayList<String>()
        val p = PhonePresence("100.101.102.103", { error("not run") }, { nets }, scope, clock::get) { said += it }
        val states = ArrayList<String>()
        val reads = listOf(status(cur = "10.250.3.20:41641"), status(cur = "10.99.0.4:41641"),
            status(cur = "[fd00:abcd::20]:41641"), status(cur = "[fd99:5::4]:41641"), status(cur = "10.77.3.4:41641"),
            status(cur = "169.254.9.4:41641"), status(cur = "203.0.113.9:41641"), status(cur = "100.70.1.2:41641"),
            status(cur = "10.250.3.300:41641"), status(relay = "", peerRelay = "100.64.0.77:41641"),
            status(cur = "10.250.3.20:41641"), status(), status(cur = "203.0.113.9:41641"))
        for (json in reads) {
            val r = read(json)
            repeat(16) {                                   // long enough for a far path to become away
                p.observe(r, clock.get())
                clock.addAndGet(MIN)
            }
            states += p.stateLine()
        }
        assertTrue(said.size >= 6, "the value changed with the paths: ${said.joinToString("\n")}")
        assertTrue(logs.any { "phone path:" in it }, "the unchanged-value line was written too: ${logs.joinToString("\n")}")
        val addresses = listOf("10.250.3.20", "10.99.0.4", "fd00:abcd::20", "fd99:5::4", "10.77.3.4", "169.254.9.4",
            "203.0.113.9", "100.70.1.2", "10.250.3.300", "100.64.0.77", "41641")
        val written = logs + said + states
        val found = written.filter { line -> addresses.any { it in line } }
        assertTrue(found.isEmpty(), "a path address or port was written:\n" + found.joinToString("\n"))
        for (words in listOf("direct, to a private address routed from this PC", "a point-to-point interface: a tunnel, not the bus",
                "address on no network of this PC"))
            assertTrue(written.any { words in it }, "that case says exactly so: $words")
    }

    /**
     * R3.1 pin 4 (the case R3 measured): the phone's direct path alternating, minute to minute, between a private
     * address on the PC's own network and one on a second private network routed from the PC (two networks at the
     * bus, one behind the other; v6 too). `damage.phone` stays `home`, the `bus` word of every `table` answer stays
     * true (so the phone's candidate bus position is never dropped, R2.1), and after the first no `damage.phone`
     * change line is written. Watched failing with the routed-private branch disabled (dec. F.3's rule): the second
     * address read `unknown` and the value flipped at minute 1.
     */
    @Test
    fun aPathAlternatingBetweenTheBusNetworksStaysHome() {
        Log.addSink(sink)
        val dir = Files.createTempDirectory("damage-presence-alternate")
        try {
            val clock = AtomicLong(T0)
            val said = CopyOnWriteArrayList<String>()
            val p = PhonePresence("100.101.102.103", { error("not run") }, { nets }, scope, clock::get) { said += it }
            val svc = BastionService(AlertStore(dir.resolve("alerts.json")), { "1 rule(s)" }, scope, p)
            fun bus(): Boolean? = StoreAnswer.fromJson(svc.request(BastionService.OP_TABLE, buildJsonObject {
                put("since", 0); put("epoch", 0)
            }).data).bus
            val paths = listOf("10.250.3.20:41641", "10.99.0.4:41641", "10.250.3.20:41641", "[fd99:5::4]:41641")
            for (m in 0 until 12) {
                p.observe(read(status(cur = paths[m % paths.size])), clock.get())
                assertEquals(PhonePresence.HOME, p.word, "minute $m: ${p.stateLine()}")
                assertEquals(true, bus(), "minute $m: the bus word of the table answer")
                clock.addAndGet(MIN)
            }
            assertEquals(listOf("damage.phone unknown -> home (by the connection path: direct, on the network of eth-test)"), said)
            assertEquals(1, logs.count { "damage.phone " in it && " -> " in it }, logs.joinToString("\n"))
            assertTrue(logs.any { "phone path: direct, to a private address routed from this PC (damage.phone stays home)" in it },
                "a change of the path that keeps the value is one log line: ${logs.joinToString("\n")}")
            svc.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aReadStillRunningIsSkippedAndSaidNeverTwoAtOnce(): Unit = runBlocking {
        Log.addSink(sink)
        val clock = AtomicLong(T0)
        val gate = CountDownLatch(1)
        val running = AtomicInteger(0)
        val most = AtomicInteger(0)
        val runs = AtomicInteger(0)
        val stub = {
            runs.incrementAndGet()
            most.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                gate.await()                       // the stand-in hangs until the test lets it go
                status(cur = "10.250.3.20:41641")
            } finally {
                running.decrementAndGet()
            }
        }
        val p = PhonePresence("100.101.102.103", stub, { nets }, scope, clock::get)
        try {
            p.tick()
            waitUntil(5_000, 5, { "the first read starts" }) { runs.get() == 1 }
            repeat(3) {
                clock.addAndGet(MIN)
                p.tick()
            }
            delay(200)
            assertEquals(1, runs.get(), "no second read starts while one runs")
            assertEquals(PhonePresence.UNKNOWN, p.word)
            assertTrue(logs.count { "tailscale status still running after" in it } == 3, logs.joinToString("\n"))
            assertTrue("a status read is still running" in p.stateLine(), p.stateLine())
            gate.countDown()
            waitUntil(5_000, 5, { "the read lands: ${p.stateLine()}" }) { p.word == PhonePresence.HOME }
            waitUntil(5_000, 5, { "the read is done" }) { !p.busy }
            clock.addAndGet(MIN)
            p.tick()
            waitUntil(5_000, 5, { "the next minute reads again" }) { runs.get() == 2 }
            assertEquals(1, most.get(), "never two at once")
        } finally {
            gate.countDown()                       // a failed assertion leaves no stand-in hanging
        }

        // a command that cannot run: unknown, and why
        val missing = PhonePresence("100.101.102.103", { throw IOException("Cannot run program \"tailscale\"") },
            { nets }, scope, clock::get)
        missing.observe(PathRead(PathKind.HOME, "direct, on the network of eth-test"), clock.get())
        missing.tick()
        waitUntil(5_000, 5, { "the failure lands: ${missing.stateLine()}" }) { missing.word == PhonePresence.UNKNOWN }
        assertTrue("the status read failed (IOException)" in missing.stateLine(), missing.stateLine())
    }

    companion object {
        const val T0 = 1_800_000_000_000L
        const val MIN = 60_000L
    }
}
