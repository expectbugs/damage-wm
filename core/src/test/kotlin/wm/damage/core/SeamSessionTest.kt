package wm.damage.core

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.RemoteTransportServer
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent

/**
 * HANDOFF.md §8.2 amendment 10 (round 2, b2-1; round 3, a3): a seam that ends
 * answers every outstanding flush as failed BEFORE its link-down, exactly
 * once; a link loss on the far end lowers the client's `started` (the session
 * keeper's restart signal) and is reported with its reason; the same client
 * reconnects afterwards.
 */
class SeamSessionTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    /** A far end whose flushes are accepted and never completed (a phone whose
     *  radio stopped answering). */
    private class Hanging(inner: Transport) : Transport by inner {
        private val ids = java.util.concurrent.atomic.AtomicLong(100)
        override suspend fun submit(flush: FlushRequest): Long = ids.getAndIncrement()
    }

    /** A sim-backed far-end transport whose link the test can end. */
    private class Fragile(val glass: GlassFirmwareSim, scope: CoroutineScope) : CfwTransportBase(scope, "fragile") {
        override val instant: Boolean get() = true

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() {}
        override suspend fun writeArm(arm: Arm, packet: ByteArray) { glass.write(arm, packet, nowMs()) }
        override fun onMaintenanceTick() { glass.tick(nowMs()) }
        fun endLink(reason: String) = onLinkDown(reason)
    }

    @Test
    fun aSeamThatEndsFailsTheOutstandingFlushOnceBeforeItsLinkDown(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val inner = Hanging(SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)))
        val server = RemoteTransportServer(inner, port, "tok", scope)
        server.start()
        try {
            val client = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            val events = ArrayList<TransportEvent>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                client.events.collect { synchronized(events) { events.add(it) } }
            }
            client.start(warmup())
            assertTrue(client.state.value.started)
            val id = client.submit(FlushRequest(listOf(DisplayOp.Keyframe(warmup())), 1L))
            delay(300)
            assertTrue(synchronized(events) { events.none { it is TransportEvent.FlushDone } }, "the far end holds the flush")

            server.close()                                  // the phone app went away mid-flush
            val t0 = System.currentTimeMillis()
            fun linkDown() = synchronized(events) { events.any { it is TransportEvent.Link && !it.connected } }
            while (!linkDown() && System.currentTimeMillis() - t0 < 5_000) delay(10)
            assertTrue(linkDown(), "the link end was reported")
            delay(200)                                      // anything late would show up here
            val seq = synchronized(events) { events.toList() }
            val doneIx = seq.indexOfFirst { it is TransportEvent.FlushDone && it.id == id }
            val downIx = seq.indexOfFirst { it is TransportEvent.Link && !it.connected }
            assertTrue(doneIx >= 0, "the outstanding flush was answered: $seq")
            val done = seq[doneIx] as TransportEvent.FlushDone
            assertFalse(done.ok, "answered as failed")
            assertTrue("${done.error}".contains("seam link ended"), "with the reason: ${done.error}")
            assertTrue(doneIx < downIx, "the failed flush precedes the link-down, so the shell rolls back first: $seq")
            assertEquals(1, seq.count { it is TransportEvent.FlushDone }, "answered exactly once: $seq")
            assertFalse(client.state.value.started, "the session is over for the keeper's poll")
            client.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun aFarEndLinkLossLowersStartedReportsItAndTheClientReconnects(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val glass = GlassFirmwareSim()
        val inner = Fragile(glass, scope)
        val server = RemoteTransportServer(inner, port, "tok", scope)
        server.start()
        try {
            val client = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            val links = ArrayList<TransportEvent.Link>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                client.events.collect { if (it is TransportEvent.Link) synchronized(links) { links.add(it) } }
            }
            client.start(warmup())
            assertTrue(client.state.value.started)
            assertEquals(1, glass.preludeAcks)

            inner.endLink("test: out of range")
            val t0 = System.currentTimeMillis()
            fun down() = synchronized(links) { links.any { !it.connected && it.detail.contains("out of range") } }
            while (!(down() && !client.state.value.started) && System.currentTimeMillis() - t0 < 5_000) delay(10)
            assertTrue(down(), "the far end's link end reached the client with its reason: $links")
            assertFalse(client.state.value.started, "the keeper's poll sees the session end")

            // the keeper's next attempt: the same client, a fresh session. The
            // server releases its driver slot LAST during teardown (round 3 D6),
            // so an attempt that lands inside the teardown is answered "busy"
            // — the keeper's pause covers that; here a short retry does.
            client.stop()
            var attempts = 0
            while (true) {
                attempts++
                try { client.start(warmup()); break } catch (e: IllegalStateException) {
                    assertTrue("driven by" in "${e.message}" && attempts < 50, "unexpected: ${e.message}")
                    delay(100)
                }
            }
            // `started` settles rather than being sampled at one instant: a
            // stale forwarded state frame may flip it for one message right
            // after the start (the round 3 a3-8 tolerance the keeper already
            // documents — a restart from that is a plain reconnect)
            val t1 = System.currentTimeMillis()
            while (!client.state.value.started && System.currentTimeMillis() - t1 < 5_000) delay(10)
            assertTrue(client.state.value.started)
            assertEquals(2, glass.preludeAcks, "the far end connected the glasses again")
            client.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /** One evaluation decides. */
    private suspend fun until(what: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (true) {
            if (cond()) return
            if (System.currentTimeMillis() - t0 >= 10_000) throw AssertionError("never true: $what")
            delay(10)
        }
    }

    /**
     * APK 0.64 (`HANDOFF.md` §75.4): the far end's wear report crosses the seam as the same event, and its
     * wear state and wear-detection switch ride the state frame — a field left out of either mapper
     * compiles and is silently not carried, and an unknown control is only logged. And (the P4 review's
     * finding 8) what the far end said about ITS session — the wear report, the switch, the firmware's
     * Silent Mode — is forgotten by the client at both of its session ends, the stop and the seam's own
     * end: kept, it would be read as the next session's.
     */
    @Test
    fun theWearReportCrossesTheSeamAndIsForgottenAtTheClientsSessionEnds(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val glass = GlassFirmwareSim()
        glass.setWearDetectionSwitch(true)
        val inner = Fragile(glass, scope)
        val server = RemoteTransportServer(inner, port, "tok", scope)
        server.start()
        try {
            val client = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            val wears = ArrayList<TransportEvent.Wear>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                client.events.collect { if (it is TransportEvent.Wear) synchronized(wears) { wears.add(it) } }
            }
            fun seen() = synchronized(wears) { wears.toList() }
            client.start(warmup())
            assertEquals(null, client.state.value.worn, "no report yet")
            assertFalse(client.queryWear(), "the query is the owner's to send: nothing goes out from the driver")

            glass.sensorReportsWear(true)
            until("the far end's report arrives as the same event (${seen()})") { seen() == listOf(TransportEvent.Wear(worn = true, first = true)) }
            until("its wear state and the switch ride the state frame (${client.state.value})") {
                client.state.value.worn == true && client.state.value.wearSwitch == true }
            glass.sensorReportsWear(false)
            until("a change is not a first report (${seen()})") { seen().lastOrNull() == TransportEvent.Wear(worn = false, first = false) }
            glass.setSilent(true)
            until("the far end's Silent Mode is in the client's state") { client.state.value.glassesSilent }

            // the driver releases its claim: the session it described is no longer this client's to describe
            client.stop()
            assertEquals(Triple(null, null, false), client.state.value.let { Triple(it.worn, it.wearSwitch, it.glassesSilent) },
                "the stop forgets what the far end said about the session")

            // claimed again: the owner's fresh state comes first, strictly before its `started`
            var attempts = 0
            while (true) {
                attempts++
                try { client.start(warmup()); break } catch (e: IllegalStateException) {
                    assertTrue("driven by" in "${e.message}" && attempts < 50, "unexpected: ${e.message}")
                    delay(100)
                }
            }
            assertEquals(Triple(false, true, true), client.state.value.let { Triple(it.worn, it.wearSwitch, it.glassesSilent) },
                "the new claim reads the owner's state as it stands")

            server.close()                                  // the seam itself ends
            until("the seam's end reached the client") { !client.state.value.connected }
            assertEquals(Triple(null, null, false), client.state.value.let { Triple(it.worn, it.wearSwitch, it.glassesSilent) },
                "the seam's end forgets it too")
            client.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /**
     * R2 (RG-6): a paced check asks nothing of a transport with no session. A shell drives over the seam and
     * sleeps for the firmware's Silent Mode; the seam ends. The client's end clears what the far end said
     * (`glassesSilent` false, `started` false), so the paced check read "the glasses say they are awake",
     * asked for a rebuild, was refused (nothing is started) and raised a `rebuild` notice about a refusal
     * that was the link being down. No keeper here: in the product the keeper stops the shell at its next
     * poll, up to 250 ms later, and a pacing tick inside that window did the same.
     * The absences are asserted behind a mark through the loop after more than three pacings.
     */
    @Test
    fun aPacedCheckAsksNothingOverASeamThatEnded(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = java.nio.file.Files.createTempDirectory("damage-seam-paced")
        val port = freePort()
        val glass = GlassFirmwareSim()
        val server = RemoteTransportServer(SimTransport(glass, scope, SimTransport.Timing(instant = true)), port, "tok", scope)
        server.start()
        val journal = tmp.resolve("journal.jsonl")
        fun notes(kind: String) = (if (java.nio.file.Files.exists(journal)) java.nio.file.Files.readAllLines(journal) else emptyList())
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").removeSuffix("\"}") }
        try {
            val client = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            val shell = wm.damage.core.shell.Shell(FakeText(), client,
                wm.damage.core.shell.Persistence(tmp.resolve("state.json")), journal, scope).also { it.silentPacingMs = 50 }
            shell.start()
            glass.setSilent(true)
            until("the shell sleeps with the glasses (Silent Mode crossed the seam)") { shell.glassesAsleep && client.state.value.glassesSilent }
            server.close()                                  // the seam itself ends
            until("the seam's end reached the client") { !client.state.value.connected }
            delay(400)                                      // eight pacings
            shell.services.runOnShell { shell.journalNote("mark", "m1") }
            until("the mark went through the loop") { "m1" in notes("mark") }
            assertEquals(emptyList(), notes("silent").filter { "rebuild refused" in it },
                "a paced check asked a transport with no session for a rebuild (behind the mark, eight pacings)")
            val faults = listOfNotNull(shell.notifications.current) + shell.notifications.queued()
            assertEquals(emptyList(), faults.filter { it.thread == "rebuild" }.map { it.body }, "and raised a notice about a refusal that was the link being down")
            shell.stop()
        } finally {
            server.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * APK 0.64 (the P4 review's finding 4): a sleep's released lease does not outlive the hand-back. A
     * resting shell has released the lease on purpose — the renewals, the keepalive and the carrier refresh
     * are off, the watchdog stands down — and the transport wants its lease again only inside `start()`,
     * which an ADOPTED start does not call. So the owner's own shell, resumed after a seam claim, started
     * awake on a session with no lease and painted into nothing until the link happened to end.
     * Phone-shaped wiring as `HandoverTest`'s (a local keeper, a seam server whose claim pauses it and whose
     * release resumes it):
     *   (1) the phone's shell rests; a driver claims the session through the seam and leaves; the phone's
     *       shell adopts back a session whose lease it released itself — and rebuilds it: a second
     *       connect, the lease held, awake;
     *   (2) the same claim and release with the lease HELD: adopted as it is, no rebuild;
     *   (3) the same with the glasses in the firmware's Silent Mode: the lease is released for that sleep,
     *       and the adopted session is that sleep — no rebuild (its wake is the push OFF).
     * The driver in between paints nothing here. One that does — a PC shell claiming a resting phone — is
     * NOT covered by this build: the adoption is the far end's, the seam carries no word of it, and that
     * shell has no lease until it leaves (`Shell.startLocked` says so where the check is; the 0.64 record
     * lists it as not built).
     * Mutations watched: the check removed (no second connect, the lease never back); the lease's term
     * dropped from it (every adopted session is rebuilt); the check made whatever the glasses say (an
     * adopted Silent Mode sleep is rebuilt).
     */
    @Test
    fun anAdoptedSessionWhoseLeaseASleepingShellReleasedIsRebuilt(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = java.nio.file.Files.createTempDirectory("damage-seam-adopt")
        val port = freePort()
        val glass = GlassFirmwareSim()
        val transport = SimTransport(glass, scope, SimTransport.Timing(instant = true))
        val journal = tmp.resolve("journal.jsonl")
        fun notes(kind: String) = (if (java.nio.file.Files.exists(journal)) java.nio.file.Files.readAllLines(journal) else emptyList())
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").removeSuffix("\"}") }
        fun leaseHeld() = System.currentTimeMillis().let { glass.leaseHeld(Arm.LEFT, it) && glass.leaseHeld(Arm.RIGHT, it) }
        val adopted = "adopted a session whose lease is not held and whose glasses do not say Silent Mode " +
            "(released by a driver that was asleep, or lapsed) — rebuilding the session"
        val phone = wm.damage.core.shell.Shell(FakeText(), transport,
            wm.damage.core.shell.Persistence(tmp.resolve("state.json")), journal, scope).also { it.silentPacingMs = 60_000 }
        val keeper = wm.damage.core.shell.ShellKeeper(phone, transport, scope, retryPauseMs = 100)
        val server = RemoteTransportServer(transport, port, "tok", scope,
            onRemoteDriver = { driving -> if (driving) runBlocking { keeper.pause("PC shell driving") } else keeper.resume() })
        server.start()
        try {
            fun phoneDrives() = keeper.state == wm.damage.core.shell.ShellKeeper.State.RUNNING && transport.state.value.started
            /** A driver that paints nothing claims the phone's session and leaves again. */
            suspend fun claimAndLeave() {
                val raw = RemoteTransportClient("127.0.0.1", port, "tok", scope)
                var attempts = 0
                while (true) {
                    attempts++
                    try { raw.start(warmup()); break } catch (e: IllegalStateException) {
                        assertTrue("driven by" in "${e.message}" && attempts < 50, "unexpected: ${e.message}")
                        delay(100)                            // the server frees its driver slot last (round 3 D6)
                    }
                }
                until("the phone's shell yielded") { keeper.state == wm.damage.core.shell.ShellKeeper.State.PAUSED }
                raw.stop()
            }
            keeper.start()
            until("the phone's shell drives") { phoneDrives() && phone.isQuiescent() }
            assertEquals(1, glass.preludeAcks)

            // (1) the phone rests, is claimed, and adopts back the session whose lease it released itself
            assertTrue(phone.devProbe("rest", "on"))
            until("the phone's shell rests") { phone.resting }
            until("its release reached the glasses") { !leaseHeld() }
            claimAndLeave()
            until("the phone's shell rebuilt the session it adopted back: a second connect, the lease held") {
                glass.preludeAcks == 2 && leaseHeld() && phoneDrives() }
            assertEquals(listOf(adopted), notes("keeper").filter { it.startsWith("adopted ") }, "said once, by the shell that found it")
            until("and it is awake and painting: its rest ended with its session") { !phone.glassesAsleep && !phone.resting && phone.isQuiescent() }
            assertTrue(glass.left.panel.any { it.toInt() != 0 }, "the rebuilt session painted the panel")

            // (2) the same claim with the lease held: adopted as it is
            claimAndLeave()
            until("the phone's shell drives again") { phoneDrives() && phone.isQuiescent() }
            delay(400)                                       // the keeper's poll and its pause, twice over
            assertEquals(2, glass.preludeAcks, "a session adopted with its lease held is not rebuilt")
            assertEquals(1, notes("keeper").count { it.startsWith("adopted ") }, "and nothing more is said: ${notes("keeper")}")
            assertTrue(leaseHeld() && !phone.glassesAsleep)

            // (3) the glasses in the firmware's Silent Mode (the P4 verify pass: "and whose glasses are not
            //     silent" had no pin): the lease is released for THAT sleep, the adopted session is the same
            //     sleep, and its wake is the push OFF — never a rebuild from the start that adopts it
            fun starts() = notes("build").count { "session start via" in it }
            glass.setSilent(true)
            until("the phone's shell sleeps with the glasses, the lease released") { phone.glassesAsleep && !phone.resting && !leaseHeld() }
            val before = starts()
            claimAndLeave()
            until("the phone's shell adopted the session back") { starts() >= before + 1 }
            delay(400)                                       // as above: time for a rebuild that must not come
            assertEquals(2, glass.preludeAcks, "an adopted session whose glasses say Silent Mode is not rebuilt")
            assertEquals(before + 1, starts(), "one start, the adopted one")
            assertTrue(phoneDrives(), "and the phone's shell drives it")
            assertEquals(1, notes("keeper").count { it.startsWith("adopted ") }, "and it is not said to be one: ${notes("keeper")}")
            assertTrue(phone.glassesAsleep && !phone.resting && !leaseHeld(), "asleep for the firmware's Silent Mode, as before the claim")
            keeper.stop()
        } finally {
            server.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
