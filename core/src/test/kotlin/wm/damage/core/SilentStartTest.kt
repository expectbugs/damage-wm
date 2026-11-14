package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import wm.damage.core.comp.CachedText
import wm.damage.core.gfx.IconKind
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.Pb
import wm.damage.core.wire.SettingsMsg

/**
 * Build W, package G (the lead's decisions H row E1 and H2; G-design §1). Context for the reader: Adam's own
 * glasses, phone and window manager; a session start that finds the glasses in the firmware's Silent Mode.
 *
 * On 2026-11-12 nine starts in the charging case re-sent the carrier CREATE every 2 s for about 500 s each, none
 * answered while the start's READ said Silent Mode, until the msgId counter came round and failed the first copy;
 * the tenth completed at the exit and slept on its READ. These pins: the simulator answers no CREATE in Silent Mode
 * (`GlassFirmwareSim.createAnsweredWhileSilent`, the glass reading); a start whose READ says Silent Mode completes
 * asleep with no carrier (no CREATE, lease, cache size, flags, warmup or fid clear) and the push off rebuilds it;
 * the wake that arrives at each point of a start in progress ends with the shell awake or rebuilding; and an awake
 * start writes, byte for byte, what it wrote before the change.
 */
class SilentStartTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tmp = Files.createTempDirectory("damage-silent-start")

    @AfterTest fun clean() {
        scope.cancel()
        tmp.toFile().deleteRecursively()
    }

    /** The start rig with one more seam: [afterStart] runs once `transport.start()` has returned and before the
     *  shell reads the state (`Shell.startLocked`'s one read) — the fourth point of H2's trace. */
    private class Rig(glass: GlassFirmwareSim, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis,
                      instant: Boolean = true) : StartRig(glass, scope, clock, instant) {
        @Volatile var afterStart: (() -> Unit)? = null
        override suspend fun start(warmupFrame: ByteArray) {
            super.start(warmupFrame)
            afterStart?.let { afterStart = null; it() }
        }
    }

    private var n = 0

    /** [cached]: a `CachedText` host with Global `Cached text` on (fix round 2, R2.4's pin); [clock]: the transport's
     *  clock, moved by the test (R2.5's pin: the lease log's gaps). */
    private inner class Up(knobAnswered: Boolean = false, cached: Boolean = false, clock: () -> Long = System::currentTimeMillis) {
        val glass = GlassFirmwareSim().apply { damageContract = 2; createAnsweredWhileSilent = knobAnswered }
        val transport = Rig(glass, scope, clock)
        val id = ++n
        val journal: Path = tmp.resolve("journal-$id.jsonl")
        val text = if (cached) CachedText(NoisyText()) else FakeText()
        val store = Persistence(tmp.resolve("state-$id.json")).apply {
            if (cached) put("shell.settings", ShellSettings().copy(cachedText = "on").toJson())
        }
        val shell = Shell(text, transport, store, journal, scope)
            .apply { silentPacingMs = 60_000 }
        /** What the host's urgent hook was handed (the phone's high-importance notification). */
        val urgent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val keeper = ShellKeeper(shell, transport, scope, retryPauseMs = 100)
        /** Images (first packets of an EvenHub image message) written, and how many before the second prelude. */
        val images = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var imagesBeforeSecondStart = -1

        init {
            transport.onWrite = { _, p -> countImage(p); p }
            shell.onUrgent = { source, body -> urgent += "$source: $body" }
        }

        /** One turn of the shell's loop: everything posted before this has been handled; [read] runs on the loop. */
        suspend fun <T> onLoop(read: () -> T): T {
            val got = CompletableDeferred<T>()
            shell.services.runOnShell { got.complete(read()) }
            waitUntil(5_000, 5, { "the loop runs a posted action: ${what()}" }) { got.isCompleted }
            return got.await()                              // completed: returns at once
        }

        /** The Silent Mode notices on the glasses' side (shown or queued), read on the loop. */
        suspend fun silentNotices(): List<Notifications.Notice> = onLoop {
            (listOfNotNull(shell.notifications.current) + shell.notifications.queued())
                .filter { it.source == "DAMAGE · glasses" && "Silent Mode" in it.body }
        }

        fun countImage(p: ByteArray) {
            if (StartRig.firstOfImage(p)) images.incrementAndGet()
            if (isPrelude(p) && glass.preludeAcks == 1 && imagesBeforeSecondStart < 0) imagesBeforeSecondStart = images.get()
        }

        fun lines(): List<String> = if (Files.exists(journal)) Files.readAllLines(journal) else emptyList()
        fun notes(kind: String) = lines().filter { "\"kind\":\"$kind\"" in it }
        fun driving() = keeper.state == ShellKeeper.State.RUNNING && transport.state.value.started
        fun awakeAndPainting() = driving() && !shell.glassesAsleep && transport.state.value.leaseHeld && !glass.carrierLost
        fun what() = "keeper ${keeper.state} attempts ${keeper.attempts}; asleep=${shell.glassesAsleep}; " +
            "state ${transport.state.value.let { "started=${it.started} lease=${it.leaseHeld} silent=${it.glassesSilent}" }}; " +
            "creates ${glass.createsSeen} preludes ${glass.preludeAcks}; notes ${lines().filter { "\"ev\":\"note\"" in it }.takeLast(12)}"
    }

    // ------------------------------------------------------------------ packet shapes
    private fun body(p: ByteArray) = StartRig.body(p)
    private fun isPrelude(p: ByteArray) = StartRig.sid(p) == wm.damage.core.wire.LaunchMsg.SID
    /** The capability READ (`SettingsMsg.settingsQuery`: command 2 with the field-4 sub-request). */
    private fun isCapabilityRead(p: ByteArray) = StartRig.sid(p) == SettingsMsg.SID && p.size >= AaFrame.HEADER + AaFrame.CRC &&
        runCatching { Pb.varintField(body(p), 1) == 2L && Pb.bytesField(body(p), 4) != null }.getOrDefault(false)
    private fun isResetCheck(arm: Arm, p: ByteArray) = arm == Arm.RIGHT && StartRig.damageOp(p) == DamageMsg.OP_TELEMETRY
    private fun isAcquire(p: ByteArray) = StartRig.damageOp(p) == SettingsMsg.OP_FB_ACQUIRE
    private fun isCreate(p: ByteArray) = p.size > AaFrame.HEADER + 1 && p[5].toInt() == 1 && StartRig.sid(p) == EvenHubMsg.SID &&
        p[AaFrame.HEADER].toInt() == 0x08 && p[AaFrame.HEADER + 1].toInt() == EvenHubMsg.CMD_CREATE
    private fun isCapabilityAnswer(p: ByteArray) = StartRig.sid(p) == SettingsMsg.SID && p.size >= AaFrame.HEADER + AaFrame.CRC &&
        SettingsMsg.parseCapability(body(p)) != null

    // ------------------------------------------------------------------ E1
    /**
     * E1: a start whose READ says Silent Mode completes within the READ — no CREATE, no lease, no image — and the
     * shell sleeps on it; the push off then rebuilds the session: one CREATE, the lease, the warmup and a keyframe.
     * Watched on the unfixed tree: the start never completes (the CREATE is re-sent unanswered every 2 s), so the
     * 5 s wait fails.
     */
    @Test
    fun aStartInSilentModeCompletesAsleepAndThePushOffRebuildsIt(): Unit = runBlocking {
        val u = Up()
        u.glass.setSilent(true, push = false)               // the press came before the start: its READ says so
        u.keeper.start()
        waitUntil(5_000, 10, { "the start in Silent Mode completes asleep within 5 s: ${u.what()}" }) {
            u.driving() && u.shell.glassesAsleep
        }
        delay(300)
        assertEquals(0, u.glass.createsSeen, "no CREATE into a pair in Silent Mode")
        assertEquals(0, u.glass.acquiresSeen, "no lease into a pair in Silent Mode")
        assertEquals(0, u.images.get(), "no image (no warmup, no keyframe) into a pair in Silent Mode")
        assertTrue(!u.transport.state.value.leaseHeld, "the rested session holds no lease: ${u.what()}")
        assertTrue(!u.transport.state.value.carrierUp, "the rested session has no carrier: ${u.what()}")
        assertEquals(0, u.glass.releasesSeen, "no RELEASE for a lease never taken")
        assertTrue(u.notes("power").any { "fw-silent" in it }, "the power note says fw-silent: ${u.notes("power")}")
        assertTrue(u.notes("keeper").any { "start rests" in it }, "the keeper note says the start rests: ${u.notes("keeper")}")
        assertEquals(1, u.glass.preludeAcks)

        u.glass.setSilent(false)                            // the both-temple press again: the push off
        waitUntil(10_000, 10, { "the push off rebuilds a painting session: ${u.what()}" }) { u.awakeAndPainting() && u.glass.preludeAcks == 2 }
        waitUntil(10_000, 10, { "the rebuilt session settles: ${u.shell.quiescenceReport()}" }) { u.shell.isQuiescent() }
        assertEquals(1, u.glass.createsSeen, "one CREATE: the rebuilt session's")
        assertTrue(u.glass.acquiresSeen >= 2, "the rebuilt session took the lease on both arms: ${u.glass.acquiresSeen}")
        assertTrue(u.images.get() >= 2, "the warmup and the keyframe went out: ${u.images.get()}")
        assertEquals(0, u.imagesBeforeSecondStart, "no image went out before the rebuild")
        u.keeper.stop()
    }

    /**
     * E1 with H2 point 4: the push off lands after the start rested and before the shell reads the state. The shell
     * sleeps on the rested session (no image into it), and the queued `SilentMode(false)` wakes it into a rebuild.
     * Watched on the unfixed tree: the start never completes, as above.
     */
    @Test
    fun aWakeBetweenTheRestAndTheShellsReadRebuildsWithNoImageIntoTheRest(): Unit = runBlocking {
        val u = Up()
        u.glass.setSilent(true, push = false)
        u.transport.afterStart = { u.glass.setSilent(false) }
        u.keeper.start()
        waitUntil(5_000, 10, { "the rested start completes and the queued wake rebuilds it: ${u.what()}" }) {
            u.glass.preludeAcks >= 2
        }
        waitUntil(10_000, 10, { "the rebuilt session paints: ${u.what()}" }) { u.awakeAndPainting() }
        assertEquals(0, u.imagesBeforeSecondStart, "no image into the rested session")
        assertEquals(1, u.glass.createsSeen, "one CREATE: the rebuilt session's")
        // the shell's start read (`carrierUp`) put the rested session to sleep from its first frame: its power note
        // is `fw-silent`, never `lit` — read with that guard removed, the session started lit and only the queued
        // stale `SilentMode(true)` put it to sleep (the pump's guard alone kept its frames off the wire)
        val firstLive = u.notes("power").firstOrNull { "\"detail\":\"down\"" !in it }
        assertTrue(firstLive != null && "fw-silent" in firstLive, "the rested session's first power note: $firstLive")
        u.keeper.stop()
    }

    /**
     * A shell that ADOPTS a session which rested in Silent Mode, after a push off that no shell heard (the glasses
     * awake, no carrier, no lease), starts awake and rebuilds the session at once (`Shell.startLocked`'s adopted
     * branch), sending no frame into it first. Watched: with the start read's `!adopted` taken out (the design's
     * literal form) the shell slept on the adopted session and only the 60 s paced check would have rebuilt it.
     * (The pump's `carrierUp` guard is not what this pins: the rebuild's `restartSession` clears `started` before the
     * loop's first pump. That guard and the start read back each other up — with both removed, one image went into
     * the rested session in the test above; with either alone, none.)
     */
    @Test
    fun anAdoptedRestedSessionTakesNoFrameBeforeItsRebuild(): Unit = runBlocking {
        val u = Up()
        u.glass.setSilent(true, push = false)
        u.transport.start(wm.damage.core.gfx.Zl.encodeCfw(wm.damage.core.gfx.Pack.rect(wm.damage.core.gfx.Gray8(640, 480),
            wm.damage.core.geom.Rect(0, 0, 640, 480))))         // a driver's start: it rests (no shell runs it)
        assertTrue(u.transport.state.value.started && !u.transport.state.value.carrierUp, "the start rested: ${u.what()}")
        u.glass.setSilent(false)                            // the push off, heard by no shell
        waitUntil(5_000, 10, { "the transport read the push: ${u.what()}" }) { !u.transport.state.value.glassesSilent }
        u.keeper.start()                                    // this shell adopts the rested session
        waitUntil(10_000, 10, { "the adopted session is rebuilt and paints: ${u.what()}" }) {
            u.glass.preludeAcks >= 2 && u.awakeAndPainting()
        }
        assertEquals(0, u.imagesBeforeSecondStart, "no frame into the adopted carrier-less session before its rebuild")
        assertTrue(u.notes("keeper").any { "adopted a session whose lease is not held" in it }, "the adopted branch rebuilt it: ${u.notes("keeper")}")
        u.keeper.stop()
    }

    /**
     * The journal-only cue read (G-design §1): while the state says Silent Mode, an unasked `09-01` block whose
     * field-14 presence differs from the last such block's is one `cue` note each time, and every sid-0x0d frame is
     * one `cue` note; nothing acts on either (the shell stays asleep, no rebuild). Awake, neither is said.
     */
    @Test
    fun theCueReadJournalsUnaskedBlocksAndService0dWhileSilent(): Unit = runBlocking {
        val u = Up()
        u.glass.setSilent(true, push = false)
        u.keeper.start()
        waitUntil(5_000, 10, { "the start rests: ${u.what()}" }) { u.driving() && u.shell.glassesAsleep }
        fun unasked(f14: Boolean) = AaFrame.frame(1, SettingsMsg.SID, 0x01,
            Pb.cat(Pb.v(1, 1), Pb.l(4, Pb.cat(Pb.v(12, 98), Pb.v(13, 1), if (f14) Pb.v(14, 1) else ByteArray(0)))), AaFrame.TYPE_RESPONSE).single()
        fun sid0d(b: Int) = AaFrame.frame(2, 0x0d, 0x01, byteArrayOf(8, b.toByte()), AaFrame.TYPE_RESPONSE).single()
        u.transport.deliver(Arm.RIGHT, unasked(true))       // the first unasked block while silent: said
        u.transport.deliver(Arm.RIGHT, unasked(true))       // the same presence: not said again
        u.transport.deliver(Arm.RIGHT, unasked(false))      // field 14 gone (2026-11-12 14:17:05's shape): said
        u.transport.deliver(Arm.RIGHT, sid0d(1))
        u.transport.deliver(Arm.RIGHT, sid0d(2))            // every 0x0d frame while silent
        waitUntil(5_000, 10, { "four cue notes: ${u.notes("cue")}" }) { u.notes("cue").size == 4 }
        val cues = u.notes("cue")
        assertEquals(2, cues.count { "unasked 09-01" in it }, "two presence changes: $cues")
        assertTrue(cues.any { "field 14 absent" in it && "present in the last" in it }, "the change is said: $cues")
        assertEquals(2, cues.count { "sid 0x0d" in it }, "each 0x0d frame: $cues")
        delay(300)
        assertTrue(u.shell.glassesAsleep && u.glass.preludeAcks == 1, "nothing acted on a cue: ${u.what()}")
        u.glass.setSilent(false)
        waitUntil(10_000, 10, { "awake: ${u.what()}" }) { u.awakeAndPainting() }
        u.transport.deliver(Arm.RIGHT, unasked(false))      // (a block WITH field 14 > 0 is read as Silent Mode by
        u.transport.deliver(Arm.RIGHT, sid0d(3))            //  `parseSilentRestored` on any sid-0x09 frame: not sent here)
        delay(300)
        assertEquals(4, u.notes("cue").size, "awake, no cue note: ${u.notes("cue")}")
        u.keeper.stop()
    }

    // ------------------------------------------------------------------ H2: the wake at each point of a start
    private class Outcome(val point: String, val awake: Boolean, val preludes: Int, val creates: Int, val sleeps: Int,
                          val releases: Int, val fired: String, val detail: String) {
        override fun toString() = "| $point | ${if (awake) "awake" else "NOT AWAKE"} | starts $preludes | CREATEs $creates | " +
            "sleeps $sleeps | RELEASEs $releases | hook: $fired |"
    }

    private suspend fun wakeAt(point: String): Outcome {
        // the CREATE is answered in Silent Mode at the points the unfixed start can only reach that way (4, 5)
        val u = Up(knobAnswered = point[0] in "456")
        val fired = java.util.concurrent.atomic.AtomicReference("not fired")
        fun wake(where: String) { if (fired.compareAndSet("not fired", where)) u.glass.setSilent(false) }
        // fix round 2 (R2.8): row 3 is an AWAKE start — with package G a start in Silent Mode rests before any CREATE,
        // so the point after the CREATE's ack is reached only by a press during an awake start: the push ON, then OFF
        if (point[0] != '3') u.glass.setSilent(true, push = false)
        when (point[0]) {
            '1' -> u.transport.onWrite = { _, p -> u.countImage(p); if (isCapabilityRead(p)) wake("at the READ's write"); p }
            '2' -> u.transport.onWrite = { a, p -> u.countImage(p); if (isResetCheck(a, p)) wake("at the reset check's write"); p }
            '3' -> u.transport.onWrite = { _, p ->
                u.countImage(p)
                if (isAcquire(p) && fired.compareAndSet("not fired", "at the lease's write (after the CREATE's ack): a push ON, then OFF")) {
                    u.glass.setSilent(true)
                    u.glass.setSilent(false)
                }
                p
            }
            '4' -> u.transport.afterStart = { wake("after transport.start() returned") }
            '6' -> u.transport.afterStart = {
                // the cue is not the push: an unasked 09-01 block with an explicit field-14 zero, which
                // `parseSilentRestored` reads on any sid-0x09 frame (the glasses have not been seen to send one)
                if (fired.compareAndSet("not fired", "after transport.start() returned: an unasked block, field 14 = 0")) {
                    u.glass.setSilent(false, push = false)
                    u.transport.deliver(Arm.RIGHT, AaFrame.frame(1, SettingsMsg.SID, 0x01,
                        Pb.cat(Pb.v(1, 1), Pb.l(4, Pb.cat(Pb.v(12, 98), Pb.v(14, 0)))), AaFrame.TYPE_RESPONSE).single())
                }
            }
            '5' -> {}
        }
        u.keeper.start()
        if (point[0] == '5') {
            waitUntil(5_000, 10, { "point 5: the shell sleeps on the READ: ${u.what()}" }) { u.driving() && u.shell.glassesAsleep }
            delay(200)
            wake("after the shell slept on the READ")
        }
        val awake = endsAwake(u, "point $point")
        val o = Outcome(point, awake, u.glass.preludeAcks, u.glass.createsSeen,
            u.lines().count { "\"kind\":\"silent\"" in it && "glasses asleep" in it }, u.glass.releasesSeen, fired.get(), u.what())
        u.keeper.stop()
        return o
    }

    /** The END state, not a passing one: awake, painting and settled, then the same again 1.5 s later (a stale
     *  sleep and its rebuild run in that time), the second evaluation deciding. */
    private suspend fun endsAwake(u: Up, what: String): Boolean = runCatching {
        waitUntil(15_000, 10, { "$what: the shell is awake and settled: ${u.what()}" }) { u.awakeAndPainting() && u.shell.isQuiescent() }
        delay(1_500)
        waitUntil(15_000, 10, { "$what: and still is 1.5 s on: ${u.what()}" }) { u.awakeAndPainting() && u.shell.isQuiescent() }
    }.isSuccess

    /**
     * H2 items 1-3: the Silent Mode off cue (the push) sent at each point of a start in progress — (1) before the
     * READ's answer, (2) between the READ and the CREATE (the reset check; with E1, before the rest decision),
     * (3) after the CREATE's ack and before `started` — on this tree only an AWAKE start reaches it (a start in
     * Silent Mode rests before any CREATE), so row 3 is a push ON and then OFF there, during an awake start, and it
     * must end awake on a REBUILT session (fix round 2, R2.8; watched failing with the shell's wake on
     * `SilentMode(false)` disabled: it slept on the ON and stayed asleep), (4) between `started` and the shell's
     * first read, (5) after the shell slept on the READ. Every point must end with the shell awake (a rebuild on the
     * way counts); none may leave it asleep on the older READ. The table is printed for the report.
     */
    @Test
    fun theWakeAtEachPointOfAStartEndsAwake(): Unit = runBlocking {
        val points = listOf("1 before the READ's answer", "2 between the READ and the CREATE",
            "3 after the CREATE's ack, an awake start: a push ON, then OFF",
            "4 between started and the shell's read", "5 after the shell slept on the READ",
            "6 between started and the shell's read, an unasked block with field 14 = 0 as the cue")
        val out = points.map { wakeAt(it) }
        println("H2 trace table:\n" + out.joinToString("\n"))
        for (o in out) assertTrue(o.awake, "point ${o.point}: the cue was lost — ${o.detail}")
        val row3 = out.single { it.point.startsWith("3") }
        assertTrue("lease's write" in row3.fired, "row 3 ran at its named point (the lease's write): $row3")
        assertTrue(row3.preludes >= 2, "row 3 ends on a rebuilt session: $row3")
    }

    /**
     * H2 item 3, the order of 2026-11-12 14:08-14:18: a start begun in Silent Mode (the READ says so; no CREATE is
     * answered), the wake while it is in progress, and the shell's state afterwards: awake and painting.
     */
    @Test
    fun theOrderOfNovemberTwelfthEndsAwake(): Unit = runBlocking {
        val u = Up()
        u.glass.setSilent(true, push = false)
        u.transport.onNotify = { _, p ->
            if (isCapabilityAnswer(p)) scope.launch { delay(300); u.glass.setSilent(false) }   // the exit, 0.3 s on
            p
        }
        u.keeper.start()
        assertTrue(endsAwake(u, "the order of 11-12"), "the shell ends awake after the exit: ${u.what()}")
        println("H2 replay of 11-12: starts ${u.glass.preludeAcks}, CREATEs ${u.glass.createsSeen}, sleeps " +
            "${u.lines().count { "glasses asleep" in it }}, RELEASEs ${u.glass.releasesSeen}")
        u.keeper.stop()
    }

    // ------------------------------------------------------------------ fix round 2 (W-R2-decisions §A)
    /**
     * R2.2 (review 2, finding 2): a start that rests says no notice — on glass a start in the case before package G
     * never reached its sleep, and with G every one would have posted the urgent "the glasses are in Silent Mode"
     * notice (at the install, every app restart, every reconnect in the case). Neither the glasses' queue nor the
     * host's urgent hook (the phone's high-importance notification) holds one. A push ON in a live session still
     * says it once, as before. Watched failing with the start read's flag forced to say it: one notice, both sides.
     */
    @Test
    fun aStartThatRestsSaysNoNoticeAndAPushOnInALiveSessionStillDoes(): Unit = runBlocking {
        val u = Up()
        u.glass.setSilent(true, push = false)
        u.keeper.start()
        waitUntil(5_000, 10, { "the start rests: ${u.what()}" }) { u.driving() && u.shell.glassesAsleep }
        delay(300)                                          // the READ's queued SilentMode(true) is handled too
        assertEquals(emptyList(), u.silentNotices(), "a start that rests puts no notice in the glasses' queue")
        assertEquals(emptyList(), u.urgent.toList(), "a start that rests hands the urgent hook nothing")

        u.glass.setSilent(false)
        waitUntil(10_000, 10, { "the push off rebuilds a painting session: ${u.what()}" }) { u.awakeAndPainting() && u.glass.preludeAcks == 2 }
        waitUntil(10_000, 10, { "the rebuilt session settles: ${u.shell.quiescenceReport()}" }) { u.shell.isQuiescent() }
        u.glass.setSilent(true)                             // the both-temple press while he wears them: the push ON
        waitUntil(5_000, 10, { "the push ON puts the live session to sleep: ${u.what()}" }) { u.shell.glassesAsleep }
        assertEquals(1, u.silentNotices().size, "the push ON in a live session is said once, on the glasses' side")
        assertEquals(1, u.urgent.count { "Silent Mode" in it }, "...and to the urgent hook: ${u.urgent}")
        u.keeper.stop()
    }

    /** A window that counts the shell's `relayoutEverything` (every window is told its font scale again there). */
    private class Relayouts : DamageWindow("relayouts", "Relayouts", IconKind.FILES) {
        val count = java.util.concurrent.atomic.AtomicInteger()
        override fun onFontScaleChanged(scale: Double) { count.incrementAndGet() }
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 40 }, paintRow = { _, _, _, _ -> },
            paintLens = { _, _, _ -> }, onCommit = {})
        override fun summary() = Summary("none")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /**
     * R2.4 (review 2, finding 4): a start that rests does not enable the atlas. Its session has no flags in force, so
     * `draw2` reads false: the atlas it built was a v1 one and it flipped the layout metric to contract 1, re-wrapping
     * every window while asleep — and the wake's contract-2 session flipped it back and re-wrapped everything again
     * before its first flush. Across a rested start: the metric stays, no window is laid out again, no atlas is built.
     * The wake's start enables it as an awake start does (the `built for contract 2` note). Watched failing with the
     * start's `sleep == null` taken out: `built for the v1 cache`, the metric at contract 1, one re-layout.
     */
    @Test
    fun aStartThatRestsBuildsNoAtlasAndTheWakeEnablesIt(): Unit = runBlocking {
        val u = Up(cached = true)
        val win = Relayouts().also { u.shell.register(it) }
        val ct = u.text as CachedText
        u.glass.setSilent(true, push = false)
        u.keeper.start()
        waitUntil(5_000, 10, { "the start rests: ${u.what()}" }) { u.driving() && u.shell.glassesAsleep }
        delay(300)
        u.onLoop { }                                        // what the start posted has run
        assertTrue(ct.metricV2, "the metric is not moved by a rested start (${u.notes("atlas")})")
        assertEquals(0, win.count.get(), "no window is laid out again across a rested start")
        assertTrue(u.notes("atlas").none { "built for" in it }, "no atlas is built by a rested start: ${u.notes("atlas")}")

        u.glass.setSilent(false)
        waitUntil(10_000, 10, { "the push off rebuilds a painting session: ${u.what()}" }) { u.awakeAndPainting() && u.glass.preludeAcks == 2 }
        waitUntil(10_000, 10, { "the wake's start enables the atlas: ${u.notes("atlas")}" }) {
            u.notes("atlas").any { "built for contract 2" in it }
        }
        assertTrue(ct.metricV2, "contract 2's metric, as an awake start has it")
        assertEquals(0, win.count.get(), "the wake re-wraps nothing: the metric never moved")
        u.keeper.stop()
    }

    /**
     * R2.5 (review 2, finding 5): the rest decides no carry, so it commits none — the evidence from before it reaches
     * the next real decision, as it did before package G (a start in Silent Mode then never completed). Here LEFT's
     * link ends as a lens reboot would (a supervision timeout, 20 s into the lease), the next start's READ says
     * Silent Mode and it rests, and the push off rebuilds: the wake's start must still read LEFT's timeout and refuse
     * the carry. Cached text is on, so the first session's atlas puts a cache on both arms and RIGHT's own reading
     * ("R holds no cache") cannot decide in its place. Watched failing with the rest's `commitLeaseCarry()` put back:
     * "a renewal on both arms".
     */
    @Test
    fun evidenceFromBeforeARestedStartIsReadAtTheWakesStart(): Unit = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val u = Up(cached = true, clock = clock::get)
        u.keeper.start()
        waitUntil(10_000, 10, { "the first session paints: ${u.what()}" }) { u.awakeAndPainting() }
        waitUntil(15_000, 10, { "the first session's atlas is live: ${u.notes("atlas").takeLast(4)}" }) {
            u.shell.cachedFontsLive.isNotEmpty() && u.shell.isQuiescent()
        }
        assertTrue(Arm.entries.all { u.glass.cacheAllocated(it) }, "the first session put a cache on both arms")
        clock.addAndGet(20_000L)                            // past the link-settle strike, well inside the lease window
        u.transport.endLink("LEFT disconnected: supervision timeout")
        u.glass.setSilent(true, push = false)               // and the glasses are in Silent Mode when they reconnect
        waitUntil(10_000, 10, { "the keeper's next start rests: ${u.what()}" }) {
            u.glass.preludeAcks == 2 && u.driving() && u.shell.glassesAsleep
        }
        u.glass.setSilent(false)
        waitUntil(15_000, 10, { "the wake rebuilds a painting session: ${u.what()}" }) { u.glass.preludeAcks == 3 && u.awakeAndPainting() }
        val st = u.transport.state.value
        assertTrue(!st.leaseCarried && "L link ended as a reboot would" in st.leaseCarry && "holds no cache" !in st.leaseCarry,
            "the wake's carry decision read LEFT's timeout from before the rest: carried=${st.leaseCarried} (${st.leaseCarry})")
        u.keeper.stop()
    }

    // ------------------------------------------------------------------ the awake start, byte for byte
    /**
     * Package G's start must be byte-for-byte today's for an awake pair (the lead's decision H, row E1). Every packet
     * a start writes — prelude, READ, reset check, CREATE, lease, cache size, flags, warmup, fid clear, wear query —
     * for an upstream build, a contract-1 build and a contract-2 build, at the product's own pacing (not instant),
     * on a fixed clock so the nonces repeat, the heap3 reads off (launched, never awaited: their place in the order
     * is not fixed). The expectation files were written from the tree before package G (`wip/066` at 930e6b3).
     */
    @Test
    fun anAwakeStartWritesWhatItWroteBefore(): Unit = runBlocking {
        val missing = ArrayList<String>()
        for (contract in listOf<Int?>(null, 1, 2)) {
            val name = "awake-start-" + (contract?.let { "contract$it" } ?: "upstream") + ".txt"
            val glass = GlassFirmwareSim().apply { damageContract = contract }
            val t = Rig(glass, scope, clock = { 1_763_000_000_000L }, instant = false)
            t.heapReads = false
            val sent = java.util.Collections.synchronizedList(ArrayList<String>())
            t.onWrite = { a, p -> sent.add("${a.name.first()} " + p.joinToString("") { "%02x".format(it) }); p }
            val warmup = wm.damage.core.gfx.Zl.encodeCfw(wm.damage.core.gfx.Pack.rect(wm.damage.core.gfx.Gray8(640, 480),
                wm.damage.core.geom.Rect(0, 0, 640, 480)))
            val started = scope.async { t.start(warmup) }
            started.await()
            delay(300)
            val got = synchronized(sent) { sent.toList() }
            t.stop()
            val res = javaClass.getResource("/startwire/$name")
            if (res == null) {
                val out = Paths.get("build", "startwire").also { Files.createDirectories(it) }.resolve(name)
                Files.write(out, got)
                missing.add("$name: this run's ${got.size} packets written to ${out.toAbsolutePath()}")
                continue
            }
            val want = res.readText().lines().filter { it.isNotBlank() }
            assertEquals(want.size, got.size, "$name: packet count")
            for (i in want.indices) assertEquals(want[i], got[i], "$name: packet $i differs")
        }
        if (missing.isNotEmpty()) throw AssertionError("no expectation for " + missing.joinToString("; "))
    }
}
