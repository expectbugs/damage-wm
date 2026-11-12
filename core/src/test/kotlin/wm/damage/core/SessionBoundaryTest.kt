package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.EvenHubMsg

/**
 * APK 0.65, P5 part a: state that crosses a session start, and a session's end judged as its own failure
 * (the boundary-clear sweep, `scratchpad/sweeps/boundary-clear-verified.md`). Every pin here is watched to fail
 * on the tree before its fix, for the reason it names; the build report's pins table has each one.
 */
class SessionBoundaryTest {
    private val tmp: Path = Files.createTempDirectory("session-boundary")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    /** The base choreography over the model, with modeled acks ([ackMs] per image message) and image writes the
     *  test can fail on script: while [failImages] is above zero, the first packet of the next image message
     *  throws, as a write that never left does (the lane fails that flush with the link up). */
    private class Scripted(val glass: GlassFirmwareSim, scope: CoroutineScope, private val ackMs: Long) :
        CfwTransportBase(scope, "scripted") {
        val failImages = AtomicInteger()
        /** While set, `connectLink()` waits on it: a start stays in progress until the test opens it. */
        @Volatile var connectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var connecting = false
        /** While set, the model's image acks are not handed to the transport: acks lost on the air. The pacing is
         *  the product's (not instant), so the msgId counter does not come round inside a test of seconds. */
        @Volatile var dropImageAcks = false
        /** While set, the first packet of the next image message waits on it: the lane parks inside a write. */
        @Volatile var writeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var writeParked = false

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { if (!(dropImageAcks && isImageAck(packet))) onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }

        /** The first AA packet (serial 1) of an EvenHub image fragment message: `08 03 …` after the 8-byte
         *  header — `AaFrame.frame`, `EvenHubMsg.imageFragment` (G2CC docs/G2_BLE_PROTOCOL.md §6.5). */
        private fun firstOfImage(p: ByteArray): Boolean = p.size > AaFrame.HEADER + 1 && p[5].toInt() == 1 &&
            p[6] == EvenHubMsg.SID.toByte() && p[AaFrame.HEADER].toInt() == 0x08 && p[AaFrame.HEADER + 1].toInt() == EvenHubMsg.CMD_IMAGE

        override suspend fun connectLink() {
            val g = connectGate ?: return
            connecting = true
            g.await()
        }
        /** While set, `disconnectLink()` waits on it: the phone's two disconnects in turn, which take real time. */
        @Volatile var disconnectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var disconnectParked = false
        override suspend fun disconnectLink() {
            val g = disconnectGate
            if (g != null) { disconnectParked = true; g.await() }
            glass.linkReset()
        }
        /** The lease lapses as the maintenance tick reports one (`SimTransport.onMaintenanceTick`). */
        fun lapse() = setLease(false, "test: the lease lapsed (fail-open)")
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            if (firstOfImage(packet) && failImages.getAndUpdate { maxOf(0, it - 1) } > 0)
                throw java.io.IOException("scripted write failure")
            val g = writeGate
            if (g != null && firstOfImage(packet)) { writeGate = null; writeParked = true; g.await() }
            glass.write(arm, packet, nowMs())
        }
        override suspend fun onImageDelivered() { delay(ackMs) }
        override fun onMaintenanceTick() { glass.tick(nowMs()) }
        fun endLink(reason: String) = onLinkDown(reason)
    }

    private fun lines(journal: Path): List<String> = if (Files.exists(journal)) Files.readAllLines(journal) else emptyList()
    private fun notes(journal: Path, kind: String) = lines(journal).filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }
    private fun ids(journal: Path, ev: String, extra: String = ""): Set<Long> = lines(journal)
        .filter { "\"ev\":\"$ev\"" in it && extra in it }
        .mapNotNull { Regex("\"id\":(\\d+),").find(it)?.groupValues?.get(1)?.toLong() }.toSet()
    private fun unanswered(journal: Path): Set<Long> = ids(journal, "submit") - ids(journal, "done")
    private fun failedDone(journal: Path): Set<Long> = ids(journal, "done", "\"ok\":false")

    private companion object {
        /** An image ack: an AA response frame on the EvenHub sid with the ack flag (as `PendingAckReleaseTest` reads it). */
        fun isImageAck(p: ByteArray): Boolean =
            p.size >= AaFrame.HEADER && (p[1].toInt() and 0xFF) == AaFrame.TYPE_RESPONSE &&
                (p[6].toInt() and 0xFF) == EvenHubMsg.SID && (p[7].toInt() and 0xFF) == EvenHubMsg.FLAG_ACK
    }

    /** The shown and queued notices, sampled ON the loop; a session must be running. */
    private fun noticeThreads(shell: Shell): List<String> {
        val out = java.util.concurrent.CompletableFuture<List<String>>()
        shell.services.runOnShell { out.complete((listOfNotNull(shell.notifications.current) + shell.notifications.queued()).map { it.thread }) }
        return try { out.get(10, java.util.concurrent.TimeUnit.SECONDS) } catch (e: java.util.concurrent.TimeoutException) {
            throw AssertionError("the notices were not sampled: the loop did not answer in 10 s — ${shell.quiescenceReport()}")
        }
    }

    /**
     * P5 item 2 (boundary-clear F1, the corrected pin "holds (2)"): the failures a link end's own sweep makes are
     * the link's story. One flush fails with the link up (a scripted write failure), then two are on their way with
     * their acks lost when the link ends; the sweep fails both. The `flush` notice was already gated on
     * `linkUpAndSteady()`; the `resync` and `halt` arms were not, so the sweep's two made the third of a streak with
     * the one before: the urgent `resync` ring, and the notice saved for the next session.
     * Holds both halves: the arms' rule in `completeFlush`, and the transport's `onLinkDown` publishing the ended
     * state BEFORE its sweep (the challenge's fix 3: with the shell's half alone this pin failed 1 of 1 on the
     * model — the idle loop handled the swept failures before the state flipped and read a steady link).
     * The rig paces like the product: on the instant transport the msgId counter came round every ~4.4 s with the
     * acks lost, and its "counter cycled" failures are failures with the link up — the count before the end was the
     * rig's timing (one miss of the fixed tree in sixteen runs, cause not kept). Here it is one, by script; the
     * flush the drain holds behind the un-acked one is in the lane at the end and is swept with the other.
     */
    @Test
    fun aLinkEndsSweepRaisesNoResyncAndNoHalt(): Unit = runBlocking {
        val journal = tmp.resolve("journal-sweep.jsonl")
        val transport = Scripted(GlassFirmwareSim(), scope, ackMs = 50)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-sweep.json")), journal, scope)
        val rings = AtomicInteger()
        shell.onUrgent = { _, _ -> rings.incrementAndGet() }
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        transport.dropImageAcks = true                                  // the image acks are lost; everything else answers
        transport.failImages.set(1)                                     // ...and the next image write fails with the link up
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        waitUntil(10_000, message = { "one failure with the link up and its re-send on the wire: failed=${failedDone(journal)} owed=${unanswered(journal)}" }) {
            failedDone(journal).size == 1 && unanswered(journal).isNotEmpty()
        }
        shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
        waitUntil(10_000, message = { "a second flush on its way: ${unanswered(journal)} ${shell.quiescenceReport()}" }) { unanswered(journal).size >= 2 }
        val before = failedDone(journal)
        val owed = unanswered(journal)
        transport.endLink("test: the link ends with two flushes on their way")
        waitUntil(10_000, message = { "the sweep's failures are handled: owed=$owed failed=${failedDone(journal)}" }) {
            failedDone(journal).containsAll(owed)
        }
        assertEquals(1, before.size, "exactly one failure with the link up before the end: $before")
        assertTrue(owed.size >= 2, "two flushes were on their way at the link end: $owed")
        val threads = noticeThreads(shell)
        assertTrue(notes(journal, "panic").isEmpty() && notes(journal, "halt").isEmpty(),
            "a link end's sweep was judged a flush failure streak: ${notes(journal, "panic") + notes(journal, "halt")}; " +
                "the failures: ${lines(journal).filter { "\"ok\":false" in it }}")
        assertTrue("resync" !in threads && "halt" !in threads, "a resync or halt notice for a session that ended: $threads")
        assertEquals(0, rings.get(), "the phone was rung for a link end's own sweep")
        shell.stop()
    }

    /**
     * P5 item 2 (boundary-clear F1, the corrected pin "holds (1)"): the failure counts are per session. Two flush
     * failures with the link up leave `flushFailStreak` at 2; the shell yields (the stop that keeps the transport's
     * session) and starts again; ONE failure in the new session made the third of a streak: a `panic` note, a
     * keyframe resync and the urgent `resync` ring for a session that had failed once. The flush still on the wire
     * at the stop is answered while down; since item 1 that answer resets nothing in the next session.
     */
    @Test
    fun aFailureCountDoesNotCrossASessionStart(): Unit = runBlocking {
        val journal = tmp.resolve("journal-carry.jsonl")
        val transport = Scripted(GlassFirmwareSim(), scope, ackMs = 1_000)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-carry.json")), journal, scope)
        val rings = AtomicInteger()
        shell.onUrgent = { _, _ -> rings.incrementAndGet() }
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        transport.failImages.set(2)
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
        // two failures with the link up, and the re-send after them on the wire (its ack is a second away)
        waitUntil(10_000, message = { "two write failures and a third flush on the wire: failed=${failedDone(journal)} owed=${unanswered(journal)}" }) {
            failedDone(journal).size >= 2 && unanswered(journal).isNotEmpty()
        }
        assertTrue(notes(journal, "panic").isEmpty(), "two failures make no streak: ${notes(journal, "panic")}")
        shell.stop(stopTransport = false)                               // the yield form: the transport's session goes on
        assertEquals(2, failedDone(journal).size, "no success and no third failure before the stop: ${failedDone(journal)}")
        transport.failImages.set(1)
        shell.start()                                                   // adopts the session: one keyframe, whose write fails
        waitUntil(10_000, message = { "the new session's first flush failed: ${failedDone(journal)}" }) { failedDone(journal).size >= 3 }
        waitUntil(30_000, message = { "the new session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        assertTrue(notes(journal, "panic").isEmpty() && notes(journal, "halt").isEmpty(),
            "one failure in a new session was the third of a streak the last session began: ${notes(journal, "panic") + notes(journal, "halt")}")
        assertEquals(0, rings.get(), "the phone was rung for a streak carried across the start")
        shell.stop()
    }

    /**
     * P5 item 2, at the start's own keyframe: two failures of a start's first flushes, the third on the wire at a
     * yield stop; one failure at the next start made the third of a streak (a `panic` or a `halt`, and its ring).
     */
    @Test
    fun aStartsFailuresDoNotCarryIntoTheNextStart(): Unit = runBlocking {
        val journal = tmp.resolve("journal-halt.jsonl")
        val transport = Scripted(GlassFirmwareSim(), scope, ackMs = 1_000)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-halt.json")), journal, scope)
        val rings = AtomicInteger()
        shell.onUrgent = { _, _ -> rings.incrementAndGet() }
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        // the start's first flush fails twice; the third is on the wire at the stop
        shell.stop(stopTransport = false)
        transport.failImages.set(2)
        shell.start()
        waitUntil(10_000, message = { "two keyframe failures and a third on the wire: failed=${failedDone(journal)} owed=${unanswered(journal)}" }) {
            failedDone(journal).size >= 2 && unanswered(journal).isNotEmpty()
        }
        shell.stop(stopTransport = false)
        assertEquals(2, failedDone(journal).size, "no third failure before the stop: ${failedDone(journal)}")
        transport.failImages.set(1)
        shell.start()
        waitUntil(10_000, message = { "the next start's keyframe failed: ${failedDone(journal)}" }) { failedDone(journal).size >= 3 }
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        assertTrue(notes(journal, "halt").isEmpty() && notes(journal, "panic").isEmpty(),
            "one keyframe failure at a start was the third of a streak begun before it: ${notes(journal, "halt") + notes(journal, "panic")}")
        assertEquals(0, rings.get(), "the phone was rung for a keyframe streak carried across the start")
        shell.stop()
    }

    /**
     * P5 item 2 (boundary-clear F1, its fix 3; the challenge's pin "holds (3)"): a link end publishes the ended state
     * BEFORE its sweep fails the work. The shell judges each failed completion by the state it reads when it handles
     * it; emitted first, a swept failure could be handled while the state still said started and steady. One flush
     * is parked inside its write, a second waits in the lane's queue; the link ends; a collector that runs in the
     * emitter's own step records the published `started` at each failed completion. Unfixed order: the queued
     * flush's record is `true` (the sweep emits it before the state flips).
     */
    @Test
    fun aLinkEndSaysEndedBeforeItsSweepFailsTheWork(): Unit = runBlocking {
        val t = Scripted(GlassFirmwareSim(), scope, ackMs = 50)
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Boolean>>()
        scope.launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            t.events.collect { if (it is TransportEvent.FlushDone && !it.ok) seen.add(it.id to t.state.value.started) }
        }
        t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
        val comp = Compositor()
        comp.requestKeyframe()
        val a = comp.assembleFlush(Geometry.rectBudget(3))!!
        t.writeGate = kotlinx.coroutines.CompletableDeferred()
        val gate = t.writeGate!!
        val first = t.submit(FlushRequest(a.ops, a.epoch, "parked", wide = a.wide))
        waitUntil(10_000, message = { "the first flush is parked inside its write" }) { t.writeParked }
        val second = t.submit(FlushRequest(a.ops, a.epoch, "queued", wide = a.wide))
        t.endLink("test: the link ends with one flush in its write and one queued")
        gate.complete(Unit)
        waitUntil(10_000, message = { "both flushes failed: $seen (first $first, second $second)" }) {
            seen.map { it.first }.containsAll(listOf(first, second))
        }
        assertTrue(seen.none { it.second }, "a swept failure was emitted while the state still said started: $seen")
        t.stop()
    }

    private fun queued(shell: Shell): Int =
        Regex("queued=(\\d+)").find(shell.quiescenceReport())?.groupValues?.get(1)?.toInt() ?: 0

    /**
     * P5 item 3 (boundary-clear F4): the session stamps moved late. `tickGen` and `silentGen` were bumped only after
     * `transport.start()` returned, and `running` is true from the start's first line — so the last session's idle
     * loop (5 s) and minute loop, woken during a start in progress (a long scan for the glasses), read their guard
     * as true and posted a tick every period into a queue no loop drains. Fixed: both stamps move at the start's
     * first lines. Read after the last session's one-shot timers have fired (the rest timer is 12 s): two reads
     * 11 s apart, with the start parked at a gated connect. Unfixed: +2 (an idle tick every 5 s).
     */
    @Test
    fun aStartInProgressTakesNoTicksFromTheSessionBefore(): Unit = runBlocking {
        val transport = Scripted(GlassFirmwareSim(), scope, ackMs = 50)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-ticks.json")), null, scope)
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        shell.stop()
        transport.connectGate = kotlinx.coroutines.CompletableDeferred()
        val starting = scope.launch { shell.start() }
        waitUntil(10_000, message = { "the next start is in progress, parked in its connect" }) { transport.connecting }
        delay(13_000)                                                   // the last session's one-shot timers have fired
        val q1 = queued(shell)
        delay(11_000)                                                   // two periods of the old idle loop
        val q2 = queued(shell)
        transport.connectGate!!.complete(Unit)
        starting.join()
        assertEquals(q1, q2, "the session before posted ticks into a start in progress (queued $q1, then $q2 eleven seconds on)")
        shell.stop()
    }

    /**
     * P5 item 4 (boundary-clear F5): input during a start in progress was replayed into the new session. `post` drops
     * a transport event only while no session runs, and `running` is true from a start's first line — a touch on the
     * phone's lens view or a replica while the phone scans for the glasses (the keeper is inside `start()` nearly all
     * the time then) was queued and acted on when the session came up. Fixed: an `Input` that arrives while a start is
     * in progress is dropped, counted and said in the start's `queue` note (§72's rule, applied to the third state).
     * Unfixed: the first click opens Main's focused row. A click after the start opens it (the start's mark is
     * lowered when the start has its session — left raised, every ring event of the session was dropped).
     */
    @Test
    fun inputDuringAStartInProgressIsNotReplayedIntoTheSession(): Unit = runBlocking {
        val journal = tmp.resolve("journal-input.jsonl")
        val transport = Scripted(GlassFirmwareSim(), scope, ackMs = 50)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-input.json")), journal, scope)
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        assertEquals(null, shell.currentWindowId(), "the session is on Main")
        shell.stop()
        transport.connectGate = kotlinx.coroutines.CompletableDeferred()
        val starting = scope.launch { shell.start() }
        waitUntil(10_000, message = { "the next start is in progress, parked in its connect" }) { transport.connecting }
        repeat(3) { transport.injectInput(EvenHubMsg.EV_CLICK) }       // a touch on the lens view while the phone scans
        // the three are decided once the shell's collector has queued a line typed after them (the event flow keeps
        // order per collector): a typed line is kept whatever the state, so it is the sync point, not a sleep
        transport.injectText("mark")
        waitUntil(10_000, message = { "the three clicks have reached the shell: queued=${queued(shell)}" }) { queued(shell) >= 1 }
        transport.connectGate!!.complete(Unit)
        starting.join()
        waitUntil(30_000, message = { "the new session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        assertEquals(null, shell.currentWindowId(), "a click made during the start in progress acted on the new session")
        assertTrue(notes(journal, "queue").any { "3 ring event(s) that came during the start dropped" in it },
            "the start's queue note says the three were dropped: ${notes(journal, "queue")}")
        // ...and the start in progress ends with the start: a click now is this session's
        transport.injectInput(EvenHubMsg.EV_CLICK)
        waitUntil(10_000, message = { "a click after the start acts on the session: ${shell.currentWindowId()} ${shell.quiescenceReport()}" }) {
            shell.currentWindowId() != null
        }
        shell.stop()
    }

    /**
     * P5 item 8 (boundary-clear F3): `restartSession` ends the session privately (`running`, `started`) and then waits
     * for the disconnect — two in turn on the phone — while the published state still says started. A pump with content
     * pending submitted; the lane failed the flush at once ("transport not running"), the completion rolled it back and
     * the next pump submitted it again — a round trip with nothing on the air, and with the link read as steady (the
     * watchdog's rebuild; the shell did not ask for it) a `flush` notice per failure and the `resync` ring at the third.
     * Fixed: `submit` reads the latch `restartSession` writes first, so the pump's own catch drops the flush with a
     * `link` note and a rollback and no completion comes back. Decided on one evaluation: a failed `done` or that note.
     */
    @Test
    fun aRestartsDisconnectWindowFailsNoFlush(): Unit = runBlocking {
        val journal = tmp.resolve("journal-restart.jsonl")
        val transport = Scripted(GlassFirmwareSim(), scope, ackMs = 50)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-restart.json")), journal, scope)
        val rings = java.util.concurrent.CopyOnWriteArrayList<String>()
        shell.onUrgent = { source, body -> rings.add("$source: $body") }
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        val before = failedDone(journal)
        transport.disconnectGate = kotlinx.coroutines.CompletableDeferred()
        val restarting = scope.async { transport.restartSession("test: the watchdog's rebuild") }
        waitUntil(10_000, message = { "the restart is inside its disconnect" }) { transport.disconnectParked }
        shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)                  // content pending inside the window
        waitUntil(10_000, message = { "the gesture's flush met the window: failed=${failedDone(journal) - before} ${shell.quiescenceReport()}" }) {
            (failedDone(journal) - before).isNotEmpty() || notes(journal, "link").any { "flush dropped" in it }
        }
        val failed = lines(journal).filter { "\"ev\":\"done\"" in it && "transport not running" in it }
        val threads = noticeThreads(shell)
        transport.disconnectGate!!.complete(Unit)
        assertTrue(restarting.await(), "the restart ran")
        assertTrue(failed.isEmpty(), "a lane whose session was ending failed a flush at once: $failed")
        assertTrue(notes(journal, "panic").isEmpty() && notes(journal, "halt").isEmpty(), "a streak in the window: ${notes(journal, "panic") + notes(journal, "halt")}")
        assertTrue("flush" !in threads && "resync" !in threads && "halt" !in threads, "a notice for the window: $threads")
        assertTrue(rings.isEmpty(), "the phone was rung for the window: $rings")
        shell.stop()
    }

    /**
     * P5 item 8, the lanes' half: inside the same window the maintenance loops still read the published `started` and
     * enqueue (the instant pacing's 50 ms keepalive and carrier refresh, the 60 ms READ), and the control lane's `Hub`,
     * `Settings`, `BothArms` and `Launch` arms wrote into an arm that was going away. Fixed: each fails its work through
     * `failCtlWork` while `!running` (a waiter completes; the drop is a log line). Decided on one evaluation: a write
     * that reached an arm after the restart began, or the lane's drop line.
     */
    @Test
    fun nothingReachesAnArmOnceARestartHasBegun(): Unit = runBlocking {
        val t = StartRig(GlassFirmwareSim(), scope)
        val drops = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sink = wm.damage.core.util.Log.Sink { _, tag, msg -> if (tag == "start-rig" && "transport not running" in msg) drops.add(msg) }
        wm.damage.core.util.Log.addSink(sink)
        try {
            t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))
            t.disconnectGate = kotlinx.coroutines.CompletableDeferred()
            val restarting = scope.async { t.restartSession("test: a rebuild") }
            waitUntil(10_000, message = { "the restart is inside its disconnect" }) { t.disconnectParked }
            t.counting = true
            waitUntil(10_000, message = { "a write or a drop inside the window" }) { t.counted.get() > 0 || drops.isNotEmpty() }
            val written = t.counted.get()
            t.disconnectGate!!.complete(Unit)
            assertTrue(restarting.await(), "the restart ran")
            assertEquals(0, written, "a write reached an arm after the restart began (drops: $drops)")
        } finally {
            wm.damage.core.util.Log.removeSink(sink)
        }
    }

    /**
     * Review 1a's RC-N1, settled with the rig it names: the shell's OWN rebuild after a lapse on a Damage build (the
     * box unfurls, a keyframe is owed, `restartRequested` is set) with the disconnect held on a gate and content
     * pending: no `resync` or `halt` notice inside the window, no `panic` or `halt` note, no ring but the lapse's own.
     * Decided on one evaluation: six failed completions in the window, a `halt` note, or the pump's `flush dropped`.
     */
    @Test
    fun aLapseRebuildsDisconnectWindowRaisesNoResyncAndNoHalt(): Unit = runBlocking {
        val journal = tmp.resolve("journal-lapse.jsonl")
        val transport = Scripted(GlassFirmwareSim().also { it.damageContract = 1 }, scope, ackMs = 50)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-lapse.json")), journal, scope)
        val rings = java.util.concurrent.CopyOnWriteArrayList<String>()
        shell.onUrgent = { source, body -> rings.add("$source: $body") }
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        val before = failedDone(journal)
        transport.disconnectGate = kotlinx.coroutines.CompletableDeferred()
        transport.lapse()
        waitUntil(10_000, message = { "the shell's rebuild is inside its disconnect: ${notes(journal, "keeper")}" }) { transport.disconnectParked }
        waitUntil(10_000, message = { "the window: failed=${failedDone(journal) - before} ${shell.quiescenceReport()}" }) {
            (failedDone(journal) - before).size >= 6 || notes(journal, "halt").isNotEmpty() || notes(journal, "link").any { "flush dropped" in it }
        }
        val failedInWindow = (failedDone(journal) - before).size
        val threads = noticeThreads(shell)
        val panics = notes(journal, "panic") + notes(journal, "halt")
        transport.disconnectGate!!.complete(Unit)
        waitUntil(10_000, message = { "the link end reached the shell" }) { notes(journal, "link").any { "DOWN: restart:" in it } }
        println("RC-N1 rig: $failedInWindow failed completion(s) in the window; notices $threads; rings $rings")
        assertTrue(panics.isEmpty(), "a streak in the rebuild's window: $panics")
        assertTrue("resync" !in threads && "halt" !in threads, "a resync or halt notice about a session the shell ended: $threads")
        assertTrue(rings.all { it.startsWith("lease") }, "a ring beside the lapse's own: $rings")
        shell.stop()
    }

    /** A window whose saved state throws an `Error` once the test arms it — the final save's Error class
     *  (review §29's `NoClassDefFoundError`, an OOM in the encode). */
    private class ThrowsOnSave : wm.damage.core.shell.DamageWindow("throws", "Throws", wm.damage.core.gfx.IconKind.FILES) {
        @Volatile var armed = false
        override fun view(): wm.damage.core.shell.WindowView = wm.damage.core.shell.WindowView.CanvasView({ _, _ -> })
        override fun summary() = Summary("throws")
        override fun saveState(): kotlinx.serialization.json.JsonObject {
            if (armed) throw AssertionError("saveState fails after the test armed it")
            return kotlinx.serialization.json.JsonObject(emptyMap())
        }
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
    }

    /**
     * R4 item 2 (review 3A-3): an `Error` out of the final save left the `Shutdown` arm before `running = false`
     * and `done.complete`. The loop's `Error` catch raised the fault and the loop went on serving with the pushes
     * held; `stop()` waited on `done` (or the scope's job, which never ends) and did not return, so no later start
     * or stop could take `lifecycle`. Fixed: the arm's steps run under `try … finally { running = false;
     * done.complete }` — the throw still reaches the catch and its fault. Unfixed: the stop is not back in 10 s.
     */
    @Test
    fun anErrorInTheFinalSaveStillEndsTheLoopAndTheStopReturns(): Unit = runBlocking {
        val journal = tmp.resolve("journal-shutdown-throw.jsonl")
        val transport = Scripted(GlassFirmwareSim(), scope, ackMs = 50)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-shutdown-throw.json")), journal, scope)
        val win = ThrowsOnSave()
        shell.register(win)
        shell.start()
        waitUntil(30_000, message = { "the session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        win.armed = true
        val stopping = scope.async { shell.stop() }
        waitUntil(10_000, message = { "the stop returns: ${shell.quiescenceReport()}" }) { stopping.isCompleted }
        stopping.await()
        assertTrue("LOOP-ENDED" in shell.quiescenceReport(), "the loop has ended: ${shell.quiescenceReport()}")
        assertTrue(lines(journal).any { "\"ev\":\"note\",\"kind\":\"error\"" in it && "saveState fails" in it },
            "the throw reached the loop's catch and is said")
    }

    /** A window whose `onRegistered` throws on its [nth] call — a throw in the start's prelude, before any `try`. */
    private class ThrowsOnRegister(private val nth: Int) : wm.damage.core.shell.DamageWindow("throwsreg", "ThrowsReg", wm.damage.core.gfx.IconKind.FILES) {
        val calls = AtomicInteger()
        override fun view(): wm.damage.core.shell.WindowView = wm.damage.core.shell.WindowView.CanvasView({ _, _ -> })
        override fun summary() = Summary("throwsreg")
        override fun saveState(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
        override fun onRegistered(ctx: wm.damage.core.shell.ShellServices) {
            if (calls.incrementAndGet() == nth) throw RuntimeException("onRegistered fails on start $nth")
        }
    }

    /**
     * R4 item 3 (review 3A open finding (c)): a throw in the start's prelude — before the `try` around
     * `transport.start()` — left `running` and `starting` set: every later start failed `check(!running)` with
     * "shell already started" (the keeper retries for good) and `post` dropped every ring event as a start's.
     * Fixed: the failed start's rollback covers the prelude and the stretch after `transport.start()`. Unfixed:
     * the second start throws "shell already started".
     */
    @Test
    fun aStartWhosePreludeThrowsLeavesTheShellStartable(): Unit = runBlocking {
        val transport = Scripted(GlassFirmwareSim(), scope, ackMs = 50)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-prelude.json")), null, scope)
        shell.register(ThrowsOnRegister(1))
        val first = runCatching { shell.start() }.exceptionOrNull()
        assertEquals("onRegistered fails on start 1", first?.message, "the first start throws the prelude's throw")
        shell.start()
        waitUntil(30_000, message = { "the second start's session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        shell.stop()
    }

    /** R4 item 3, the reader's third step: after a session, a start whose prelude throws, then a stop. Unfixed:
     *  `running` stays set and `loopLaunched` (never reset) is true, so the stop posts a Shutdown no loop reads and
     *  does not return. Fixed: the failed start leaves `running` false and the stop returns at once. */
    @Test
    fun aStopAfterAFailedStartThatFollowsASessionReturns(): Unit = runBlocking {
        val transport = Scripted(GlassFirmwareSim(), scope, ackMs = 50)
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-prelude2.json")), null, scope)
        shell.register(ThrowsOnRegister(2))
        shell.start()
        waitUntil(30_000, message = { "the first session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        shell.stop()
        val second = runCatching { shell.start() }.exceptionOrNull()
        assertEquals("onRegistered fails on start 2", second?.message, "the second start throws the prelude's throw")
        val stopping = scope.async { shell.stop() }
        waitUntil(10_000, message = { "the stop after the failed start returns: ${shell.quiescenceReport()}" }) { stopping.isCompleted }
        stopping.await()
    }

    /** R4 item 3, the stretch after `transport.start()`: a throw there (here the brightness push, its first line)
     *  was outside both of the start's catches. Unfixed: `running` stays set, the transport this start started stays
     *  up, and the second start throws "shell already started". Fixed: the rollback runs — the transport is stopped
     *  as the existing rollback stops it — and the second start succeeds. */
    @Test
    fun aStartThatThrowsAfterTheTransportStartedStopsItAndLeavesTheShellStartable(): Unit = runBlocking {
        val inner = Scripted(GlassFirmwareSim(), scope, ackMs = 50)
        val pushes = AtomicInteger()
        val transport = object : wm.damage.core.transport.Transport by inner {
            override fun setBrightness(auto: Boolean, level: Int) {
                if (pushes.incrementAndGet() == 1) throw RuntimeException("the brightness push fails on start 1")
                inner.setBrightness(auto, level)
            }
        }
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-gap.json")), null, scope)
        val first = runCatching { shell.start() }.exceptionOrNull()
        assertEquals("the brightness push fails on start 1", first?.message, "the first start throws the stretch's throw")
        assertEquals(false, inner.state.value.started, "the transport this start started is stopped by the rollback")
        shell.start()
        waitUntil(30_000, message = { "the second start's session settles: ${shell.quiescenceReport()}" }) { shell.isQuiescent() }
        shell.stop()
    }
}
