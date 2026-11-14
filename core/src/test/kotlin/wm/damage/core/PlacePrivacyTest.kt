package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import wm.damage.core.bastion.AlertStore
import wm.damage.core.bastion.BastionService
import wm.damage.core.bastion.FilePlaceStore
import wm.damage.core.bastion.PhonePresence
import wm.damage.core.bastion.PhonePresence.PathKind
import wm.damage.core.bastion.PhonePresence.PathRead
import wm.damage.core.bastion.PlaceLink
import wm.damage.core.bastion.PlaceReport
import wm.damage.core.bastion.PlaceWatch
import wm.damage.core.bastion.RemoteBastion
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.net.WinService
import wm.damage.core.util.Log

/**
 * Build W, package F (W-decisions G.1, G.3, G.6, G.7): the phone's position end to end on loopback — a real
 * `ContentHostServer` with the real `BastionService` and `damage.phone`, and the phone's real `RemoteBastion` with the
 * real `PlaceWatch` over a stand-in source (made-up positions in open water). The PC's path signal reaches the phone,
 * which learns the bus from a fix taken then — once an answer [PlaceWatch.LEARN_CONFIRM_MS] after the fix still says
 * the bus network (fix round 2, R2.1; the phone's clock is moved on for it, the channel's pings are real); before
 * that the fix is a candidate and nothing is stored. Away from it, the phone's report makes `damage.phone` away on the PC.
 * Then EVERYTHING written — every channel request and answer, every log line of the run, both journals' lines, the
 * state lines — is searched for a number that looks like a coordinate and for the distance: none may appear (G.6),
 * and the report carries only its word and the fix's age.
 */
class PlacePrivacyTest {

    @Test
    fun noCoordinateAndNoDistanceLeaveThePhone(): Unit = runBlocking {
        val dir = Files.createTempDirectory("damage-place-privacy")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val logs = CopyOnWriteArrayList<String>()
        val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }
        Log.addSink(sink)
        val closers = ArrayList<AutoCloseable>()
        try {
            // the PC
            val pcJournal = CopyOnWriteArrayList<String>()
            val presence = PhonePresence("100.101.102.103", { error("no command runs in this test") }, { emptyList() },
                scope) { pcJournal += it }
            val store = AlertStore(dir.resolve("pc/alerts.json"))
            val service = BastionService(store, { "1 rule(s) · " + presence.stateLine() }, scope, presence)
            val wire = CopyOnWriteArrayList<String>()
            val reports = CopyOnWriteArrayList<JsonObject>()
            val tap = object : WinService {
                override fun request(op: String, args: JsonObject): WinService.Answer {
                    wire += "$op $args"
                    (args["presence"] as? JsonObject)?.let { reports += it }
                    return service.request(op, args).also { wire += "answer ${it.data}" }
                }
                override fun attached(push: WinService.Push) = service.attached(push)
                override fun detached(push: WinService.Push) = service.detached(push)
            }
            val port = ServerSocket(0).use { it.localPort }
            val host = ContentHostServer(LocalContent(dir.resolve("books")), port, "tok", win = mapOf("bastion" to tap))
            host.start()
            closers += AutoCloseable { host.close(); service.close() }

            // the phone
            val phoneJournal = CopyOnWriteArrayList<String>()
            val src = FakePlaceSource()
            val placeFile = dir.resolve("phone/no-backup/bus-place.txt")
            val skew = AtomicLong(0)                         // the phone's clock, moved on past the confirm time (R2.1)
            val place = PlaceWatch(src, FilePlaceStore(placeFile), { true }, { System.currentTimeMillis() + skew.get() }) {
                phoneJournal += it }
            place.tick()
            val rig = DeliveryRig()
            // one clock for both, as on the phone (each reads the wall clock): the ask's time is the confirm age's
            // (verify 3), so the skew moves the asks on with the watch
            val rb = RemoteBastion("127.0.0.1", port, "tok", scope, dir.resolve("phone/wincache"), rig.delivery,
                clock = { System.currentTimeMillis() + skew.get() }, pingMs = 100, retryPacingMs = 50, place = place)
            closers += rb

            // the PC sees the phone on the bus network: the phone learns the bus from a fix taken after it hears so,
            // once a later answer confirms it (R2.1); until then the fix is a candidate and nothing is stored
            presence.observe(PathRead(PathKind.HOME, "direct, on the network of eth-test"), System.currentTimeMillis())
            waitUntil(10_000, 10, { "the phone hears it is on the bus network: ${place.stateLine()}" }) { place.learning }
            src.give(Places.north(0.0, System.currentTimeMillis()))
            fun busAnswers() = wire.count { it.startsWith("answer") && "\"bus\":true" in it }
            val before = busAnswers()
            waitUntil(10_000, 10, { "three more answers say the bus network: ${busAnswers() - before}" }) { busAnswers() >= before + 3 }
            assertTrue(!Files.exists(placeFile), "the candidate is not stored before an answer confirms it")
            skew.set(PlaceWatch.LEARN_CONFIRM_MS)
            waitUntil(10_000, 10, { "the next answer confirms it: ${place.stateLine()}" }) { Files.exists(placeFile) }
            // the commit runs on the channel's thread: the file is written under the watch's lock and the word decided
            // after it, so the word is waited for too (verify 3: read at once, it saw `unknown` in that gap)
            waitUntil(10_000, 10, { "the confirmed position decides home: ${place.stateLine()}" }) {
                place.currentWord == PhonePresence.HOME }

            // the phone leaves on the cellular path: the PC sees a relayed path (away only after 15 min by the path),
            // the phone's own fix 2,345 m from the bus says away now
            presence.observe(PathRead(PathKind.FAR, "relayed", "relayed through DERP xyz"), System.currentTimeMillis())
            // (a fix taken before the phone hears it is a candidate that the bus:false answer drops — R2.1; the test
            // gives the far fixes once the phone has heard it)
            waitUntil(10_000, 10, { "the phone hears the path is no longer the bus network" }) { !place.learning }
            val learned = Files.readString(placeFile)
            waitUntil(10_000, 20, { "the PC hears away from the phone: ${presence.stateLine()} / ${place.stateLine()}" }) {
                src.give(Places.north(2_345.0, System.currentTimeMillis()))
                presence.word == PhonePresence.AWAY
            }
            assertEquals(learned, Files.readString(placeFile), "off the bus network the stored bus position is not moved")
            assertTrue(pcJournal.any { "-> away (by the phone's position: the phone reports away" in it }, pcJournal.joinToString("\n"))
            waitUntil(10_000, 20, { "the phone hears its own away back in the watcher line: ${rb.watcherLine()}" }) {
                "the phone reports away" in rb.watcherLine()
            }

            // what reached the PC: the word and the fix's age, nothing else
            assertTrue(reports.isNotEmpty(), "the table asks carried reports")
            for (r in reports) {
                assertTrue(r.keys.all { it == "word" || it == "fixAgeMs" }, "a report holds only its word and the fix's age: $r")
                assertTrue(r["word"].toString().trim('"') in PhonePresence.WORDS, "$r")
            }

            // everything written: none of it may hold a coordinate or the distance
            val written = wire + logs + pcJournal + phoneJournal +
                listOf(place.stateLine(), presence.stateLine(), rb.watcherLine(), rb.stateLine()) +
                rig.glasses.map { it.body } + rig.phone.posts.map { it.body }
            // an IPv4 address is not a coordinate: dotted quads are taken out before the search (the path read says no
            // address since fix round 2, R2.3 — `PhonePresenceTest` searches for them; this search is for positions)
            val quad = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
            val coordinate = Regex("""\d+\.\d{3,}""")
            val distance = Regex("""\b234[0-9]\b|\b2[.,]3\d*\s*km\b|\b2,34\d\b""")
            // any distance in metres or kilometres, the home band's included (the home fix sits 0 m from the bus, so
            // the 2,345 above cannot see it; verify stage 2 watched a "0 m" in the home word's words pass without
            // this). The one number with a unit that is not a distance: the updates' own spacing, said at their start
            val spacing = "or ${PlaceWatch.DISTANCE_M.toInt()} m; no GPS asked for"
            val anyDistance = Regex("""\b\d+(?:[.,]\d+)?\s?(?:m|km|metres|meters)\b""")
            val found = written.filter { w -> val t = quad.replace(w, "<ipv4>").replace(spacing, "<spacing>")
                coordinate.containsMatchIn(t) || distance.containsMatchIn(t) || anyDistance.containsMatchIn(t) ||
                    "12.34" in t || "98.76" in t || "12.36" in t }
            assertTrue(found.isEmpty(), "a coordinate or a distance was written:\n" + found.joinToString("\n"))
            assertTrue(wire.any { "\"bus\":true" in it } && wire.any { "\"bus\":false" in it }, "the path signal was told to the phone")
        } finally {
            for (c in closers.reversed()) try { c.close() } catch (e: Exception) { Log.w("test", "close: ${e.message}") }
            Log.removeSink(sink)
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    /** The channel's end stops the learning at once: no answer says so, so the phone must not go on taking its fixes
     *  as the bus's position while it cannot hear the PC (G.1: only while the PC sees it on the bus network). */
    @Test
    fun theChannelsEndStopsTheLearning(): Unit = runBlocking {
        val dir = Files.createTempDirectory("damage-place-down")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var rb: RemoteBastion? = null
        try {
            val presence = PhonePresence("100.101.102.103", { error("no command runs in this test") }, { emptyList() }, scope)
            presence.observe(PathRead(PathKind.HOME, "direct, on the network of eth-test"), System.currentTimeMillis())
            val store = AlertStore(dir.resolve("pc/alerts.json"))
            val service = BastionService(store, { "1 rule(s)" }, scope, presence)
            val port = ServerSocket(0).use { it.localPort }
            val host = ContentHostServer(LocalContent(dir.resolve("books")), port, "tok", win = mapOf("bastion" to service))
            host.start()
            val heard = CopyOnWriteArrayList<Boolean>()
            val link = object : PlaceLink {
                override fun report(): PlaceReport = PlaceReport(PhonePresence.UNKNOWN, null)
                override fun onBus(on: Boolean, askedAt: Long) { heard += on }
            }
            rb = RemoteBastion("127.0.0.1", port, "tok", scope, dir.resolve("phone/wincache"), DeliveryRig().delivery,
                pingMs = 100, retryPacingMs = 50, place = link)
            waitUntil(10_000, 10, { "an answer says the bus network: $heard" }) { heard.lastOrNull() == true }
            host.close()
            service.close()
            waitUntil(10_000, 10, { "the channel's end says not on the bus network: $heard" }) { heard.lastOrNull() == false }
        } finally {
            rb?.close()
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }

    /** Verify 3 (the focused read's D1): an answer reaches the place watch with the time its ask was SENT, so a late
     *  answer is aged at the read it carries, not at its arrival. The PC here holds each `table` ask while the
     *  phone's clock moves on 200 s. Watched failing with the arrival time (`clock()` at the answer) passed instead. */
    @Test
    fun anAnswerReachesTheWatchWithTheTimeItsAskWasSent(): Unit = runBlocking {
        val dir = Files.createTempDirectory("damage-place-asked")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var rb: RemoteBastion? = null
        var host: ContentHostServer? = null
        try {
            val presence = PhonePresence("100.101.102.103", { error("no command runs in this test") }, { emptyList() }, scope)
            presence.observe(PathRead(PathKind.HOME, "direct, on the network of eth-test"), System.currentTimeMillis())
            val store = AlertStore(dir.resolve("pc/alerts.json"))
            val service = BastionService(store, { "1 rule(s)" }, scope, presence)
            val phoneClock = AtomicLong(1_800_000_000_000L)
            val late = object : WinService {
                override fun request(op: String, args: JsonObject): WinService.Answer {
                    if (op == BastionService.OP_TABLE) phoneClock.addAndGet(200_000)   // the answer arrives 200 s on
                    return service.request(op, args)
                }
                override fun attached(push: WinService.Push) = service.attached(push)
                override fun detached(push: WinService.Push) = service.detached(push)
            }
            val port = ServerSocket(0).use { it.localPort }
            host = ContentHostServer(LocalContent(dir.resolve("books")), port, "tok", win = mapOf("bastion" to late))
            host.start()
            val heard = CopyOnWriteArrayList<Pair<Long, Long>>()     // (the ask's time, the phone's clock at the answer)
            val link = object : PlaceLink {
                override fun report(): PlaceReport = PlaceReport(PhonePresence.UNKNOWN, null)
                override fun onBus(on: Boolean, askedAt: Long) { if (on) heard += askedAt to phoneClock.get() }
            }
            rb = RemoteBastion("127.0.0.1", port, "tok", scope, dir.resolve("phone/wincache"), DeliveryRig().delivery,
                clock = phoneClock::get, pingMs = 100, retryPacingMs = 50, place = link)
            waitUntil(10_000, 10, { "three answers say the bus network: $heard" }) { heard.size >= 3 }
            for ((asked, at) in heard.toList())
                assertTrue(at - asked >= 200_000, "the answer carries its ask's time, 200 s before it arrived: asked $asked, heard at $at")
            service.close()
        } finally {
            rb?.close()
            host?.close()
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }
}
