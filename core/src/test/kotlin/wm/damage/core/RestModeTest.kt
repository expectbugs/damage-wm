package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.comp.CachedText
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.gfx.Level
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.transport.CfwTransportBase
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.AaFrame
import wm.damage.core.wire.EvenHubMsg
import wm.damage.core.wire.SettingsMsg

/**
 * APK 0.64, build 1 of rest mode (`HANDOFF.md` §75.4): the shell's sleep has a REASON — the firmware's Silent
 * Mode, or a rest of its own — and the dev probe `rest=on|off` starts and ends a rest by hand. A rest is the
 * Silent Mode sleep under another name: frames stop, the lease is released on purpose, the page traffic and
 * the transport's watchdog sleep with it, and its wake — `rest=off` or any input event, which is then
 * consumed — is a session rebuild. Nothing rests by itself in this build, and nothing here reaches a radio:
 * every transport is the firmware model's.
 *
 * Each pin was watched to fail on the mutation `research/mutations-76-p4.json` names for it (`P4b-…`, and
 * `P4v-…` for the ones the verify pass added).
 *
 * The harness (read `P1-verify` §4 before changing it). Every wait decides on ONE evaluation ([Rig.until]).
 * Journal notes are read from the file the shell writes synchronously. [Rig.barrier] puts a mark through the
 * shell's own loop: when the mark is in the journal, everything posted to the loop before it has been
 * handled. An absence is asserted only behind such a barrier or a counted number of paced checks, and each
 * says so.
 */
class RestModeTest {

    /** A list window, so Main has a row above Settings and a tap there would open something. */
    private class Rows(private val tx: TextRasterizer) : DamageWindow("rows", "Rows", IconKind.FILES) {
        private val model = ListModel()
        private val font = FontSpec(Face.SYSTEM, 20)
        override fun view(): WindowView = WindowView.ListView(model, { 40 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 4, "Row $i text", font, Level.BODY) },
            paintLens = { g, r, i -> tx.draw(g, r.x + 44, r.y + 6, "Row $i", font, Level.HEAD) },
            onCommit = {})
        override fun summary() = Summary("40 rows")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** A list whose rows carry a line of text and a block of noise — a paint that does not compress, so
     *  opening it is an image of several 3,800 B messages (the size of a keyframe on glass), and with
     *  cached text live the same image carries cached draws. */
    private class Noisy(private val tx: TextRasterizer) : DamageWindow("noisy", "Noisy", IconKind.FILES) {
        private val model = ListModel()
        private val font = FontSpec(Face.SYSTEM, 20)
        override fun view(): WindowView = WindowView.ListView(model, { 40 },
            paintRow = { g, i, r, _ ->
                tx.draw(g, r.x + 150, r.y + 4, "Row $i text", font, Level.BODY)
                var h = 0x9E3779B9.toInt() xor (i * 7919)
                for (y in r.y + 2 until minOf(r.y + r.h - 2, r.y + 26)) for (x in r.x + 40 until r.x + 136) {
                    h = h * 1103515245 + 12345
                    g[x, y] = ((h ushr 16) and 0x0F) * 17
                }
            },
            paintLens = { _, _, _ -> },
            onCommit = {})
        override fun summary() = Summary("40 rows of noise")
        override fun saveState(): JsonObject = buildJsonObject {}
        override fun restoreState(state: JsonObject) {}
    }

    /** The simulator's transport behind a start the test can hold open, and fail. */
    private class GatedStart(val simT: SimTransport) : Transport by simT {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failNext = false
        val entered = AtomicInteger()
        override suspend fun start(warmupFrame: ByteArray) {
            entered.incrementAndGet()
            gate?.await()
            if (failNext) { failNext = false; throw java.io.IOException("test: this start fails") }
            simT.start(warmupFrame)
        }
    }

    /** The simulator's transport with a wear query and a session rebuild the test can refuse. */
    private class Refusing(val simT: SimTransport) : Transport by simT {
        @Volatile var noRadio = false
        val refuseRestarts = AtomicInteger()
        val restartsAsked = AtomicInteger()
        /** When each rebuild was asked for, on this JVM's clock. */
        val askedAtMs = java.util.concurrent.CopyOnWriteArrayList<Long>()
        override fun queryWear(): Boolean = if (noRadio) false else simT.queryWear()
        override suspend fun restartSession(reason: String): Boolean {
            askedAtMs.add(System.currentTimeMillis())
            restartsAsked.incrementAndGet()
            return if (refuseRestarts.getAndUpdate { maxOf(0, it - 1) } > 0) false else simT.restartSession(reason)
        }
    }

    /** The base choreography over the model, with writes the test can park: once [gate] is set, the next
     *  packet that is not a lease op waits on it — the control lane stops inside that write, and whatever
     *  is queued behind it stays queued until the gate opens. Lease ops pass, so the lane never parks
     *  half-way through an ACQUIRE's two arms. */
    private class Parked(val glass: GlassFirmwareSim, scope: CoroutineScope) : CfwTransportBase(scope, "parked") {
        override val instant: Boolean get() = true
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var parked = false

        init {
            glass.attachListener(object : GlassFirmwareSim.SimDiag {
                override fun event(kind: String, detail: String) {}
                override fun notify(arm: Arm, packet: ByteArray) { onNotifyPacket(arm, packet) }
                override fun panelChanged(arm: Arm) {}
            })
        }

        /** `['F','C',1,op]` with op 5 or 6 — `SettingsMsg.control`, the fork's field-101 body (settings_ext.c). */
        private fun leaseOp(p: ByteArray): Boolean = (0 until p.size - 3).any { i ->
            p[i] == 'F'.code.toByte() && p[i + 1] == 'C'.code.toByte() && p[i + 2].toInt() == 1 &&
                (p[i + 3].toInt() == SettingsMsg.OP_FB_ACQUIRE || p[i + 3].toInt() == SettingsMsg.OP_FB_RELEASE) }

        /** While set, the FIRST packet of every image message waits on it (the image lane stops there,
         *  holding the wire): [step] lets that message go and parks the next, [openImages] ends it. */
        @Volatile private var imageGate: CompletableDeferred<Unit>? = null
        val imagesParked = AtomicInteger()
        fun parkImages() { imageGate = CompletableDeferred() }
        fun step() { val old = imageGate; imageGate = CompletableDeferred(); old?.complete(Unit) }
        fun openImages() { val old = imageGate; imageGate = null; old?.complete(Unit) }

        /** The first AA packet (serial 1) of an EvenHub image fragment message: `08 03 …` after the 8-byte
         *  header — `AaFrame.frame`, `EvenHubMsg.imageFragment` (G2CC docs/G2_BLE_PROTOCOL.md §6.5). */
        private fun firstOfImage(p: ByteArray): Boolean = p.size > AaFrame.HEADER + 1 && p[5].toInt() == 1 &&
            p[6] == EvenHubMsg.SID.toByte() && p[AaFrame.HEADER].toInt() == 0x08 && p[AaFrame.HEADER + 1].toInt() == EvenHubMsg.CMD_IMAGE

        override suspend fun connectLink() {}
        override suspend fun disconnectLink() { glass.linkReset() }
        override suspend fun writeArm(arm: Arm, packet: ByteArray) {
            val g = gate
            if (g != null && !leaseOp(packet)) { parked = true; g.await() }
            val ig = imageGate
            if (ig != null && firstOfImage(packet)) { imagesParked.incrementAndGet(); ig.await() }
            glass.write(arm, packet, nowMs())
        }
        override fun onMaintenanceTick() { glass.tick(nowMs()) }
    }

    private class Rig(
        scope: CoroutineScope,
        contract: Int? = null,
        cached: Boolean = false,
        make: (GlassFirmwareSim, CoroutineScope) -> Transport = { sim, sc -> SimTransport(sim, sc, SimTransport.Timing(instant = true)) },
    ) {
        val tmp = Files.createTempDirectory("damage-rest")
        val sim = GlassFirmwareSim().also { if (contract != null) it.damageContract = contract }
        val transport: Transport = make(sim, scope)
        val journalPath = tmp.resolve("journal.jsonl")
        val store = Persistence(tmp.resolve("state.json"))
        val text: TextRasterizer = if (cached) CachedText(FakeText()) else FakeText()
        val shell = Shell(text, transport, store, journalPath, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val keeper = ShellKeeper(shell, transport, scope, retryPauseMs = 100)
        val rings = AtomicInteger()
        private val done = ArrayList<TransportEvent.FlushDone>()
        private var marks = 0

        init {
            shell.register(Rows(text))
            shell.register(Noisy(text))
            shell.onUrgent = { _, _ -> rings.incrementAndGet() }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.events.collect { if (it is TransportEvent.FlushDone) synchronized(done) { done.add(it) } }
            }
        }

        fun flushes() = synchronized(done) { done.size }
        fun leaseHeld() = System.currentTimeMillis().let { sim.leaseHeld(Arm.LEFT, it) && sim.leaseHeld(Arm.RIGHT, it) }
        /** Neither arm holds the lease — both releases have landed. `!leaseHeld()` holds once ONE arm has released,
         *  and a release count read there can be one short (R2 item 30: `R1c-built.md` §6.1's miss). */
        fun bothArmsReleased() = System.currentTimeMillis().let { now -> Arm.entries.none { sim.leaseHeld(it, now) } }
        fun driving() = keeper.state == ShellKeeper.State.RUNNING && transport.state.value.started

        fun lines(): List<String> = if (!Files.exists(journalPath)) emptyList() else Files.readAllLines(journalPath)
        /** Flushes the journal shows submitted and not completed, oldest first: (id, label). */
        fun inFlight(): List<Pair<Long, String>> {
            val all = lines()
            val done = all.mapNotNull { Regex("\"ev\":\"done\",\"id\":(\\d+),").find(it)?.groupValues?.get(1)?.toLong() }.toSet()
            return all.mapNotNull { l ->
                val id = Regex("\"ev\":\"submit\",\"id\":(\\d+),").find(l)?.groupValues?.get(1)?.toLong() ?: return@mapNotNull null
                if (id in done) null else id to (Regex("\"label\":\"([^\"]*)\"").find(l)?.groupValues?.get(1) ?: "?")
            }.sortedBy { it.first }
        }
        fun notes(kind: String): List<String> = lines().filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }
            .map { it.substringAfter("\"detail\":\"").removeSuffix("\"}") }
        /** The shown and queued notices, sampled ON the loop (R2, RH-8: the queue is the loop's, and a copy
         *  taken from the test thread can meet the loop adding to it). The session must be running: a loop
         *  that does not answer within 10 s fails here, saying so. */
        fun notices(): List<Notifications.Notice> {
            val out = java.util.concurrent.CompletableFuture<List<Notifications.Notice>>()
            shell.services.runOnShell { out.complete(listOfNotNull(shell.notifications.current) + shell.notifications.queued()) }
            return try { out.get(10, java.util.concurrent.TimeUnit.SECONDS) } catch (e: java.util.concurrent.TimeoutException) {
                throw AssertionError("the notices were not sampled: the loop did not answer in 10 s (is the session running?) — ${shell.quiescenceReport()}")
            }
        }
        fun saved(key: String): String? = store.get("shell.state")?.get(key)?.jsonPrimitive?.contentOrNull

        /** One evaluation decides. */
        suspend fun until(what: String, maxMs: Long = 15_000, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (true) {
                if (cond()) return
                if (System.currentTimeMillis() - t0 >= maxMs)
                    throw AssertionError("never true: $what — keeper ${keeper.state} attempts ${keeper.attempts} '${keeper.lastReason}'; " +
                        "shell ${shell.quiescenceReport()}; rest=${notes("rest")} silent=${notes("silent")} power=${notes("power")}")
                delay(5)
            }
        }
        suspend fun settle(what: String) = until("$what: settled") { shell.isQuiescent() }

        /** Everything posted to the shell's loop before this call has been handled when it returns
         *  (the session must be running: a stopped loop drops the mark, and the wait says so). */
        suspend fun barrier() {
            val tag = "m${++marks}"
            shell.services.runOnShell { shell.journalNote("mark", tag) }
            until("the mark $tag went through the loop") { notes("mark").contains(tag) }
        }

        suspend fun up(what: String = "start") {
            keeper.start()
            until("$what: the keeper drives") { driving() }
            settle(what)
        }

        /** `rest=on` by the probe, the release written and its page traffic stopped. */
        suspend fun rest() {
            assertTrue(shell.devProbe("rest", "on"), "the probe is the shell's")
            until("resting") { shell.resting && shell.glassesAsleep }
            until("the lease is released on both arms") { bothArmsReleased() }
            settle("resting")
        }

        fun lens(left: Boolean): Gray8 {
            val ctx = if (left) sim.left else sim.right
            val g = Gray8(640, 480)
            for (y in 0 until 480) for (x in 0 until 640) {
                val b = ctx.panel[y * ctx.stride + (x shr 1)].toInt() and 0xFF
                g[x, y] = (if (x and 1 == 0) b shr 4 else b and 0x0F) * 17
            }
            return g
        }

        fun assertGlassMatchesBelief(what: String) {
            for (left in booleanArrayOf(true, false)) {
                val e = shell.comp.expectedLens(left); val g = lens(left)
                var diffs = 0
                for (y in 0 until 480) for (x in 0 until 640) {
                    val q = minOf(15, (e[x, y] + 8) / 17) * 17
                    if (q != g[x, y]) diffs++
                }
                assertEquals(0, diffs, "$what: ${if (left) "L" else "R"} belief != glass ($diffs px)")
            }
        }

        fun close() { tmp.toFile().deleteRecursively() }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------ the probe: on
    /** Mutation watched: `restProbe`'s "on" enters nothing. */
    @Test
    fun restOnStopsFramesReleasesTheLeaseAndTheReadGoesOn(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            r.shell.silentPacingMs = 150
            assertTrue(r.shell.devProbe("rest", "on"), "the name is the shell's even with no session")
            assertEquals(listOf("rest=on not taken — no session"), r.notes("rest"))
            r.up()
            assertFalse(r.shell.devProbe("diag", "show"), "any other name is not the shell's: the host hands it to the transport")
            assertTrue(r.leaseHeld() && !r.shell.glassesAsleep && !r.shell.resting)
            val releases = r.sim.releasesSeen
            val queries = r.sim.wearQueriesSeen

            r.rest()
            assertEquals(releases + 2, r.sim.releasesSeen, "one release, both arms")
            assertTrue(r.notes("rest").any { it == "resting — probe rest=on; frames stop, the lease is released, the page traffic sleeps" },
                "a rest's entry is a `rest` note of its own: rest=${r.notes("rest")} silent=${r.notes("silent")}")
            assertTrue(r.notes("silent").isEmpty(), "and no `silent` note: ${r.notes("silent")}")
            r.shell.postIdleTick(); r.barrier()
            assertEquals("silent", r.shell.statusLine, "the cell's word for a shell asleep (composed, never sent while it rests)")
            assertEquals(0, r.rings.get(), "a rest never rings the phone")
            assertTrue(r.notices().isEmpty(), "and posts no notice: ${r.notices()}")

            // the surface goes on changing — an idle tick, a notice — and nothing is sent. Not a gesture:
            // an input event is the rest's wake. The barrier puts each through the loop first.
            val flushes = r.flushes()
            repeat(3) { r.shell.postIdleTick(); r.shell.postMinuteTick(); r.barrier() }
            r.shell.services.notifyInternal("torrent", "done · something"); r.barrier(); r.settle("a notice while resting")
            // the paced checks: each sends one wear query to both arms and waits for nothing
            r.until("three paced checks asked for the wear state") { r.sim.wearQueriesSeen >= queries + 6 }
            assertEquals(flushes, r.flushes(), "no flush leaves while the shell rests")
            assertTrue(r.notes("rest").any { it == "still resting (check 1) — wear query sent" }, "${r.notes("rest")}")
            // the page traffic sleeps: counted behind the release and three more checks (an absence over a
            // bounded span — nine instant keepalive periods, said as such)
            val keepalives = r.sim.keepalivesSeen
            val upgrades = r.sim.textUpgradesSeen          // P5 item 17 (GG-5): the carrier refresh and the lease's
            val acquires = r.sim.acquiresSeen              // acquires, counted over the same span
            val reads = r.sim.deviceInfoReadsSeen
            val q2 = r.sim.wearQueriesSeen
            r.until("three more checks") { r.sim.wearQueriesSeen >= q2 + 6 }
            assertEquals(keepalives, r.sim.keepalivesSeen, "no keepalive into a page the shell has left")
            assertEquals(upgrades, r.sim.textUpgradesSeen, "no carrier refresh into a page the shell has left")
            assertEquals(acquires, r.sim.acquiresSeen, "no lease acquire or renewal while the shell rests")
            assertTrue(r.sim.deviceInfoReadsSeen > reads, "the 60 s device-info READ goes on through the rest")
            assertTrue(r.shell.resting && !r.leaseHeld(), "still resting, the lease still released (no renewal)")
            assertEquals(1, r.keeper.attempts, "one session throughout")
            assertEquals(releases + 2, r.sim.releasesSeen)
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    // ------------------------------------------------------------------ the probe: off
    /** Mutation watched: `wakeFromRest` returns before it asks for the rebuild. */
    @Test
    fun restOffRebuildsTheSessionAndTheGlassEqualsBelief(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            // no paced check inside this test: a check that found a wake wanted would ask for the rebuild
            // itself and stand in for the probe's own ask (seen when this pin was first watched to fail)
            r.shell.silentPacingMs = 60_000
            r.up()
            val flushes = r.flushes()
            r.rest()
            // twice, queued together from the loop's own thread so the second runs right behind the first:
            // the rebuild is asked for once, and the second ask is a note (the P4 review's finding 9)
            r.shell.services.runOnShell { r.shell.devProbe("rest", "off"); r.shell.devProbe("rest", "off") }
            r.until("the wake rebuilds the session") { r.keeper.attempts == 2 && r.driving() }
            assertEquals(listOf("probe rest=off"), r.notes("restart"), "the rebuild the probe asked for, by its own reason")
            r.until("the glasses saw a second connect") { r.sim.preludeAcks == 2 }
            r.until("awake in the new session") { !r.shell.glassesAsleep && !r.shell.resting }
            r.until("the lease is back") { r.leaseHeld() }
            r.settle("the keyframe after the rebuild")
            assertTrue(r.flushes() > flushes, "the new session painted")
            r.assertGlassMatchesBelief("after the rest")
            val rest = r.notes("rest")
            assertTrue(rest.any { it.startsWith("awake after ") && "probe rest=off; rebuilding the session" in it }, "$rest")
            assertEquals(1, rest.count { it.startsWith("awake after ") }, "asked once: $rest")
            assertEquals(listOf("the wake is on its way already — not asked again (probe rest=off)"),
                rest.filter { "on its way" in it }, "the second ask is said: $rest")
            assertTrue(r.notes("silent").isEmpty(), "a rest writes no `silent` note: ${r.notes("silent")}")
            delay(400)                                      // the keeper's poll and its pause, twice over
            assertEquals(2, r.keeper.attempts, "exactly one rebuild per wake")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    /** Mutation watched: none of its own — every branch is a note; the awake branch must not rebuild. */
    @Test
    fun restOffWhileAwakeAndAValueItDoesNotKnowAreNotes(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            r.up()
            r.shell.devProbe("rest", "off"); r.shell.devProbe("rest", "sideways"); r.barrier()
            assertEquals(listOf("rest=off — not resting", "rest=sideways — expected on | off"), r.notes("rest"))
            assertEquals(listOf("rest=off", "rest=sideways"), r.notes("probe"), "each ask is a `probe` note, as every probe is")
            delay(400)
            // an absence over a bounded wait (R2, RH-8): a rebuild asked by either probe ends the session at once,
            // and the keeper (a poll every 250 ms, a retry 100 ms after) has started the next one inside 400 ms
            assertEquals(1, r.keeper.attempts, "no rebuild (an absence over 400 ms: the keeper's poll and its retry fit inside it)")
            assertTrue(r.leaseHeld() && !r.shell.glassesAsleep)
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    // ------------------------------------------------------------------ the wake by input
    /**
     * The P4 review's finding 2: the input that wakes a rest is NOT delivered. The panel is dark while the
     * shell rests, so a ring tap handled as a tap would commit whatever the cursor sits on, unseen — at Main
     * it opens the row under the cursor, and the stop's save keeps that. Any input wakes (the test is before
     * the ring-only filter): the ring, a temple, a scroll.
     * Mutations watched: the event delivered after the wake (the tap opens the window under the cursor); a
     * rest routed to the Silent Mode check (no wake at all).
     */
    @Test
    fun anInputEventWakesARestAndIsNotDelivered(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            r.shell.silentPacingMs = 60_000                 // no paced check in this test: only the events below act
            r.up()
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) // Main's cursor: from its first row (Rows) to its second (Noisy)
            r.barrier(); r.settle("at Main, the cursor on its second row")
            assertEquals(null, r.shell.currentWindowId(), "at Main")

            suspend fun wakeBy(session: Int, what: String, inject: () -> Unit) {
                r.rest()
                inject()
                r.until("$what wakes the rest: session $session") { r.keeper.attempts == session && r.driving() && !r.shell.glassesAsleep }
                r.until("the lease is back") { r.leaseHeld() }
                r.settle("session $session")
                assertEquals(null, r.shell.currentWindowId(), "$what was not delivered: still at Main, nothing opened")
                assertEquals(Triple("MAIN", "", "1"), Triple(r.saved("mode"), r.saved("current"), r.saved("mainCursor")),
                    "$what was not delivered: the state the wake's stop saved is the state before it")
            }
            wakeBy(2, "a ring tap") { r.sim.injectGesture(EvenHubMsg.EV_CLICK) }
            assertTrue(r.notes("rest").any { "an input event (type ${EvenHubMsg.EV_CLICK}, source ${EvenHubMsg.SRC_RING}) — the event is not delivered" in it },
                "the journal says the event was consumed: ${r.notes("rest")}")
            wakeBy(3, "a temple tap") { r.sim.injectGesture(EvenHubMsg.EV_CLICK, EvenHubMsg.SRC_GLASSES_R) }
            wakeBy(4, "a scroll") { r.sim.injectGesture(EvenHubMsg.EV_SCROLL_TOP) }
            r.assertGlassMatchesBelief("after three wakes")
            // awake, the same tap is a tap again
            r.sim.injectGesture(EvenHubMsg.EV_CLICK)
            r.until("awake, a ring tap opens the row under the cursor") { r.shell.currentWindowId() == "noisy" }
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    // ------------------------------------------------------------------ rest and the firmware's Silent Mode
    /**
     * Silent Mode coming on during a rest is the SAME sleep under the stronger reason: one episode — nothing
     * released twice, no second entry (its `glasses asleep` note and its reset of the check count are the
     * entry's), the Silent notice said once — and the rest is over as a rest: `rest=off` no longer wakes,
     * the push OFF does. (The transport's own `setLeaseWanted` would swallow a second release, and a second
     * entry would end the first pacing loop by its generation — so neither count can show a second entry;
     * the notes do.)
     * Mutations watched: the reason not changed for a resting shell (it stays a rest); the change run as a
     * full second entry.
     */
    @Test
    fun aSilentModePushDuringARestBecomesTheSilentSleep(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            r.shell.silentPacingMs = 150
            r.up()
            r.rest()
            r.until("a paced check of the rest") { r.notes("rest").any { it.startsWith("still resting (check 1)") } }
            val releases = r.sim.releasesSeen

            r.sim.setSilent(true)                            // the both-temple press, during the rest
            r.until("the reason is the firmware's now") { r.shell.glassesAsleep && !r.shell.resting }
            r.barrier()
            assertEquals(listOf("resting, and the firmware's Silent Mode came on — the firmware's Silent Mode is on; its push off or the READ is the wake now"),
                r.notes("silent").filter { !it.startsWith("still silent") }, "one episode: the change is said, and no second entry is")
            assertEquals(releases, r.sim.releasesSeen, "nothing is released twice")
            r.until("the Silent Mode notice is said") { r.notices().any { it.source == "DAMAGE · glasses" && "Silent Mode" in it.body } }
            assertEquals(1, r.rings.get(), "and the phone hears it, once")

            r.shell.devProbe("rest", "off"); r.shell.devProbe("rest", "on"); r.barrier()
            assertTrue(r.notes("rest").containsAll(listOf(
                "rest=off — not resting: asleep for the firmware's Silent Mode, its push off is the wake",
                "rest=on not taken — asleep for the firmware's Silent Mode")), "${r.notes("rest")}")
            assertTrue(r.shell.glassesAsleep && !r.shell.resting, "still asleep, still the firmware's")
            // an input event under Silent Mode is the Silent path's: a check, counted on from the rest's own
            r.sim.injectGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            r.until("the ring's check under the Silent rule") { r.notes("silent").any { it.startsWith("still silent per the glasses (input, check ") } }
            val n = Regex("check (\\d+)\\)").find(r.notes("silent").first { it.startsWith("still silent per the glasses (input") })!!.groupValues[1].toInt()
            assertTrue(n >= 2, "the count goes on from the rest's check(s) — one episode, not a second entry: check $n")
            assertEquals(1, r.keeper.attempts, "no wake so far")

            r.sim.setSilent(false)                           // the push OFF is the wake
            r.until("the push OFF rebuilds the session") { r.keeper.attempts == 2 && r.driving() && !r.shell.glassesAsleep }
            // R2 (RH-1): the paced check would rebuild too (the transport's flag is false after the push): the reason says which
            r.until("the wake was the push OFF's: ${r.notes("restart")}") { r.notes("restart") == listOf("the firmware's Silent Mode is off") }
            r.until("the lease is back") { r.leaseHeld() }
            r.settle("after the wake"); r.assertGlassMatchesBelief("after the wake")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    @Test
    fun restOnTwiceAndRestOnUnderSilentModeDoNothingMore(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            r.shell.silentPacingMs = 60_000
            r.up()
            r.rest()
            val releases = r.sim.releasesSeen
            r.shell.devProbe("rest", "on"); r.barrier()
            assertTrue("rest=on — already resting" in r.notes("rest"), "${r.notes("rest")}")
            assertEquals(1, r.notes("rest").count { it.startsWith("resting — ") }, "one entry")
            assertEquals(releases, r.sim.releasesSeen, "no second release")
            r.shell.devProbe("rest", "off")
            r.until("awake again") { r.keeper.attempts == 2 && r.driving() && !r.shell.glassesAsleep }
            r.settle("session 2")

            r.sim.setSilent(true)
            r.until("asleep for the firmware's Silent Mode") { r.shell.glassesAsleep }
            r.until("its release, on both arms") { r.bothArmsReleased() }      // the count is read after BOTH (item 30)
            val releases2 = r.sim.releasesSeen
            r.shell.devProbe("rest", "on"); r.barrier()
            assertTrue("rest=on not taken — asleep for the firmware's Silent Mode" in r.notes("rest"), "${r.notes("rest")}")
            assertTrue(r.shell.glassesAsleep && !r.shell.resting)
            assertEquals(releases2, r.sim.releasesSeen, "rest=on refused under Silent Mode releases nothing more")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    /** `rest=on` needs a link that is up and steady (the P4 verify pass: the branch had no pin): with the
     *  link down the release could not be written and the keeper is about to start another session anyway.
     *  The shell is started without its keeper here, so nothing stops it when its link ends.
     *  Mutation watched: the branch removed (the shell rests on a link that is down). */
    @Test
    fun restOnIsNotTakenOnALinkThatIsDown(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            r.shell.silentPacingMs = 60_000
            r.shell.start(); r.settle("start")
            (r.transport as SimTransport).endLink("test: the link ends, and nothing stops this shell")
            r.until("the link is down") { r.transport.state.value.let { !it.connected && !it.started } }
            assertTrue(r.shell.devProbe("rest", "on"), "the probe is the shell's, and its session still runs")
            r.barrier()
            assertEquals(listOf("rest=on not taken — the link is down or the session is rebuilding"), r.notes("rest"))
            assertTrue(!r.shell.resting && !r.shell.glassesAsleep, "and nothing was entered")
            assertTrue(r.notes("power").none { it.startsWith("rest") }, "${r.notes("power")}")
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    /** R2 (RH-7): a rest is taken only by a host whose transport can release the lease. Through the seam's
     *  client (`--transport remote`) the release is a log line: the owner keeps the lease, its renewals and
     *  the lit frame, while this shell's journal said "the lease is released" and its `power` note charged
     *  the hour to `rest`. Here the model's transport with the release made a no-op and the capability
     *  false, as the seam's client has them. The absence is asserted behind a barrier.
     *  Mutation watched: the refusal removed (the shell rests, writes `rest`, and the model's lease stays held). */
    @Test
    fun restOnIsNotTakenWhereTheTransportCannotReleaseTheLease(): Unit = runBlocking {
        class Seamlike(val simT: SimTransport) : Transport by simT {
            override suspend fun setLeaseWanted(wanted: Boolean) {}        // the seam's client: a log line
            override val leaseOnDemand: Boolean get() = false
        }
        val scope = scope(); val r = Rig(scope, make = { sim, sc -> Seamlike(SimTransport(sim, sc, SimTransport.Timing(instant = true))) })
        try {
            r.shell.silentPacingMs = 60_000
            r.up()
            assertTrue(r.shell.devProbe("rest", "on"), "the probe is the shell's")
            r.barrier()
            assertEquals(listOf("rest=on not taken — this host's transport cannot release the lease (the seam): rest from the host that owns the radio"),
                r.notes("rest"))
            assertTrue(!r.shell.resting && !r.shell.glassesAsleep, "and nothing was entered")
            assertTrue(r.notes("power").none { it.startsWith("rest") }, "no hour is charged to a rest: ${r.notes("power")}")
            assertTrue(r.leaseHeld(), "the model's lease is held, as it is on the owner's side")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    /** One sleep is entered ONCE however often it is said (the P4 verify pass: the first test of
     *  `enterSleep` had no pin). A session that starts with the glasses in Silent Mode hears it twice — the
     *  start reads the transport's state, and the `SilentMode` event the same READ raised is in the queue
     *  behind it — and the second word must be nothing: no second entry, and not the note a REST asked
     *  for under Silent Mode gets ("not taken — asleep for the firmware's Silent Mode already"), which
     *  would put a `rest` note into every session that starts under Silent Mode.
     *  Mutation watched: the same reason is not tested first. */
    @Test
    fun theSameSleepSaidTwiceIsEnteredOnce(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            r.shell.silentPacingMs = 60_000
            r.sim.setSilent(true)                            // before any session: the start's READ says so
            r.keeper.start()
            r.until("the session starts asleep") { r.driving() && r.shell.glassesAsleep }
            r.barrier()                                      // the READ's own SilentMode event was queued by the start: handled by now
            assertEquals(1, r.notes("silent").count { it.startsWith("glasses asleep — ") }, "one entry: ${r.notes("silent")}")
            assertTrue(r.notes("rest").isEmpty(), "and the second word of the same sleep says nothing: ${r.notes("rest")}")
            assertTrue(!r.shell.resting)
            assertEquals(listOf("fw-silent"), r.notes("power").map { it.substringBefore(' ') }, "asleep from its first frame")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    // ------------------------------------------------------------------ a rest does not cross a session
    /** Mutation watched: the sleep's reason kept across a session start. */
    @Test
    fun aLinkEndDuringARestStartsAnAwakeSession(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            r.shell.silentPacingMs = 150
            r.up()
            val flushes = r.flushes()
            r.rest()
            (r.transport as SimTransport).endLink("test: the link ends during the rest")
            r.until("the keeper rebuilds") { r.keeper.attempts == 2 && r.driving() }
            r.until("the new session is awake: a rest does not cross a session") { !r.shell.glassesAsleep && !r.shell.resting }
            r.until("it holds its lease") { r.leaseHeld() }
            r.settle("the new session"); r.barrier()
            assertTrue(r.flushes() > flushes, "and it paints")
            r.assertGlassMatchesBelief("the session after a rest that a link end cut")
            assertEquals(listOf("lit", "rest", "down", "lit"), r.notes("power").map { it.substringBefore(' ') },
                "the power notes: a rest, the link's gap, a painting session")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    // ------------------------------------------------------------------ the probe's identity
    /**
     * The P4 review's finding 1(a). A probe is run only in the session it was taken in. Where that guard
     * acts: `running` is true from the top of a start, the loop launches at its end — so a probe that
     * arrives DURING a start (seconds long on glass), or one kept in the queue across a start that failed,
     * would otherwise run as the first message of a session it was never meant for.
     * Mutation watched: the serial not compared (the probe rests the session at its first message).
     */
    @Test
    fun aProbeTakenDuringAStartIsNotRunInTheSessionThatFollows(): Unit = runBlocking {
        val scope = scope()
        val r = Rig(scope, make = { sim, sc -> GatedStart(SimTransport(sim, sc, SimTransport.Timing(instant = true))) })
        val gated = r.transport as GatedStart
        try {
            // (1) taken while a start is on its way
            gated.gate = CompletableDeferred()
            val starting = scope.launch { r.shell.start() }
            r.until("the start is inside the transport's") { gated.entered.get() == 1 }
            assertTrue(r.shell.devProbe("rest", "on"), "taken: a session is starting, its loop is not up")
            gated.gate!!.complete(Unit)
            starting.join()
            r.until("the probe says why it did nothing") { r.notes("rest") == listOf("rest=on not taken — the session changed") }
            r.barrier(); r.settle("session 1")
            assertTrue(!r.shell.resting && !r.shell.glassesAsleep && r.leaseHeld(), "the session it landed in is awake and painting")
            // taken in this session, it is run in this session
            r.rest()
            r.shell.stop()

            // (2) kept across a start that failed
            gated.gate = CompletableDeferred(); gated.failNext = true
            var failed: Throwable? = null
            val failing = scope.launch { try { r.shell.start() } catch (e: Exception) { failed = e } }
            r.until("the second start is inside the transport's") { gated.entered.get() == 2 }
            assertTrue(r.shell.devProbe("rest", "on"))
            gated.gate!!.complete(Unit)
            failing.join()
            assertTrue(failed != null, "the start failed")
            assertEquals(1, r.notes("rest").count { it == "rest=on not taken — the session changed" }, "queued, not yet answered: ${r.notes("rest")}")
            gated.gate = null
            r.shell.start()
            r.until("the kept probe is answered in the next session") { r.notes("rest").count { it == "rest=on not taken — the session changed" } == 2 }
            r.barrier(); r.settle("session 3")
            assertTrue(!r.shell.resting && !r.shell.glassesAsleep, "and did not rest it")
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    // ------------------------------------------------------------------ the power notes
    /** Mutation watched: the per-message call removed (no state change inside a session is ever said). */
    @Test
    fun thePowerNotesFollowTheShellsState(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope)
        try {
            r.shell.silentPacingMs = 60_000
            r.sim.batteryPct = 61
            r.up()
            // the first note of a first session has no level: the start's call runs before the loop has
            // handled a reading (the P4 review's finding 1, the smaller ones)
            assertEquals(listOf("lit"), r.notes("power"))
            r.until("a reading reached the chrome") { r.shell.glassesBatteryPct == 61 }
            r.sim.injectGesture(EvenHubMsg.EV_DOUBLE_CLICK)  // at Main: the shell's own silent clock screen
            r.until("the shell's silent screen") { r.notes("power").size == 2 }
            r.sim.injectGesture(EvenHubMsg.EV_DOUBLE_CLICK)  // and back
            r.until("back at Main") { r.notes("power").size == 3 }
            r.rest()
            r.sim.setSilent(true)
            r.until("the firmware's Silent Mode takes the rest over") { r.shell.glassesAsleep && !r.shell.resting }
            r.barrier()
            r.keeper.stop()
            assertEquals(listOf("lit", "shell-silent 61%", "lit 61%", "rest 61%", "fw-silent 61%", "down"), r.notes("power"),
                "one note per change of state, none repeating the one before it")
        } finally { r.close(); scope.cancel() }
    }

    /** A session that ends WITHOUT its loop handling the stop — the scope under the shell is gone — still
     *  says `down`: every place a session ends writes it, not only the loop's own Shutdown.
     *  Mutation watched: no `power` note where a session's pushes are held. */
    @Test
    fun aSessionThatEndsWithoutItsLoopStillSaysDown(): Unit = runBlocking {
        val transportScope = scope(); val shellScope = scope()
        val tmp = Files.createTempDirectory("damage-rest-scope")
        try {
            val journal = tmp.resolve("journal.jsonl")
            val t = SimTransport(GlassFirmwareSim(), transportScope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("state.json")), journal, shellScope)
            fun power() = (if (Files.exists(journal)) Files.readAllLines(journal) else emptyList())
                .filter { "\"ev\":\"note\",\"kind\":\"power\"" in it }.map { it.substringAfter("\"detail\":\"").removeSuffix("\"}").substringBefore(' ') }
            shell.start()
            val t0 = System.currentTimeMillis()
            while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 10_000) delay(5)
            assertEquals(listOf("lit"), power())
            shellScope.cancel()
            shellScope.coroutineContext[kotlinx.coroutines.Job]!!.join()     // the loop is gone with its scope
            shell.stop()
            assertEquals(listOf("lit", "down"), power(), "the stop that found no loop still closes the state")
        } finally { tmp.toFile().deleteRecursively(); transportScope.cancel(); shellScope.cancel() }
    }

    // ------------------------------------------------------------------ the paced check, and a wake refused
    /** The P4 review's finding 7, its second note: the check's words follow what the transport did with
     *  the query — a transport with no session of its own to ask with (the seam's client) sends none.
     *  Mutation watched: the note says "sent" whatever the transport answered. */
    @Test
    fun theRestsCheckSaysWhetherItsQueryWentOut(): Unit = runBlocking {
        val scope = scope()
        val r = Rig(scope, make = { sim, sc -> Refusing(SimTransport(sim, sc, SimTransport.Timing(instant = true))).also { it.noRadio = true } })
        try {
            r.shell.silentPacingMs = 150
            r.up()
            val queries = r.sim.wearQueriesSeen
            r.rest()
            r.until("the first paced check") { r.notes("rest").any { it.startsWith("still resting (check 1) — ") } }
            assertEquals(listOf("still resting (check 1) — no wear query sent: this transport has no session of its own to ask with"),
                r.notes("rest").filter { it.startsWith("still resting") })
            assertEquals(queries, r.sim.wearQueriesSeen, "and none reached the glasses")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    /**
     * A wake the transport refuses does not end the rest and is not lost: the journal says it (a `rest`
     * note — the kind follows the reason, the P4 review's finding 11 — and one notice of the rebuild class),
     * an input event inside the pacing asks nothing more (a ring spun twice asks once) and says so, and the
     * paced check asks again.
     * Mutations watched: the paced check never asks again; an input event asks again inside the pacing;
     * the refusal journaled as a `silent` note; and (the P4 verify pass) the paced check asking again at
     * its very next tick, inside the pacing of the refused ask — the refused ask is made 300 ms into the
     * rest's pacing here, so that tick comes about 700 ms after it and the next one about 1,700 ms.
     */
    @Test
    fun aWakeTheTransportRefusedIsAskedAgainByThePacedCheck(): Unit = runBlocking {
        val scope = scope()
        val r = Rig(scope, make = { sim, sc -> Refusing(SimTransport(sim, sc, SimTransport.Timing(instant = true))) })
        val t = r.transport as Refusing
        try {
            r.shell.silentPacingMs = 1_000
            r.up()
            r.rest()
            delay(300)                                      // the refused ask lands well inside the first pacing
            t.refuseRestarts.set(1)
            r.shell.devProbe("rest", "off")
            r.until("the refusal is journaled") { r.notes("rest").any { it.startsWith("session rebuild refused by the transport") } }
            assertTrue("session rebuild refused by the transport (probe rest=off) — still asleep; asking again on the next check" in r.notes("rest"), "${r.notes("rest")}")
            r.until("and is one notice") { r.notices().any { it.thread == "rebuild" } }
            assertTrue(r.shell.resting, "still resting")
            // inside the pacing an input event is consumed and asks nothing more
            r.sim.injectGesture(EvenHubMsg.EV_CLICK)
            r.until("the ring's ask inside the pacing is said (bound: the event must be handled within the 1,000 ms pacing " +
                "of the refused ask — one handled later is outside it, asks again, and this line is not written)") {
                r.notes("rest").any { it.startsWith("the wake was refused a moment ago — the paced check asks again, not this one (an input event") } }
            // ...and a ring spun on a dark panel does not write a line per event: the next ones of the same
            // wake are consumed in silence (posted to the loop directly, so the barrier covers them)
            repeat(3) { r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM) }; r.barrier()
            assertEquals(1, r.notes("rest").count { it.startsWith("the wake was refused a moment ago") }, "said once per wake: ${r.notes("rest")}")
            assertEquals(1, t.restartsAsked.get(), "asked once so far")
            assertTrue(r.shell.resting, "still resting")
            // the paced check asks again; this time the transport takes it
            r.until("the paced check asks again and the session is rebuilt") { r.keeper.attempts == 2 && r.driving() && !r.shell.glassesAsleep }
            assertEquals(2, t.restartsAsked.get(), "asked twice in all")
            // at most once per pacing: the tick that came inside the refused ask's pacing asked nothing
            // (50 ms of slack for the two asks' own dispatch; the tick inside the pacing is ~300 ms short)
            val gap = t.askedAtMs[1] - t.askedAtMs[0]
            assertTrue(gap >= 950, "the paced check asks again no sooner than one pacing after the refused ask: $gap ms (bound: the " +
                "950 ms leaves 50 ms for the two asks' own dispatch — a refused ask whose dispatch lagged the paced one's by more " +
                "than that misses here on a sound tree)")
            assertTrue(r.notes("restart").any { "the rest's wake, asked again" in it }, "${r.notes("restart")}")
            assertTrue(r.notes("silent").isEmpty(), "a rest writes no `silent` note: ${r.notes("silent")}")
            r.until("the lease is back") { r.leaseHeld() }
            r.settle("after the wake"); r.assertGlassMatchesBelief("after a wake asked twice")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    // ------------------------------------------------------------------ what lands after the sleep
    /**
     * The P4 review's finding 3: the firmware model's own refusal of what was decoded after the release is
     * the expected answer to a sleep, like the firmware's `imgres` — a journal note, never a notice waiting
     * in the queue for the wake, never the `fault` word. The three kinds are handed to the shell by hand
     * here, so the pin does not depend on which fragment the release overtook.
     * Mutation watched: only `imgres` expected while asleep (0.63's rule).
     */
    @Test
    fun aRefusalThatLandsAfterTheSleepIsANoteNotANotice(): Unit = runBlocking {
        val scope = scope()
        val extra = kotlinx.coroutines.flow.MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
        class Lab(val simT: SimTransport) : Transport by simT {
            override val events: kotlinx.coroutines.flow.Flow<TransportEvent> = extra
            init { scope.launch(start = CoroutineStart.UNDISPATCHED) { simT.events.collect { extra.emit(it) } } }
        }
        val r = Rig(scope, make = { sim, sc -> Lab(SimTransport(sim, sc, SimTransport.Timing(instant = true))) })
        try {
            r.shell.silentPacingMs = 60_000
            r.up()
            r.rest()
            for (what in listOf("imgres", "decode", "mirror/decode"))
                extra.emit(TransportEvent.Fault(what, "RIGHT mode-21 with no FB lease"))
            extra.emit(TransportEvent.Fault("control", "a fault that is not a refusal"))
            // the four are handled in the order sent, and a fault's notice is posted by its own handler: once
            // the last one's box exists, a box for any of the three before it would exist too
            r.until("the fault that is not a refusal became a notice") { r.notices().any { it.thread == "transport/control" } }
            assertEquals(listOf("imgres", "decode", "mirror/decode").map { "refused while asleep ($it): RIGHT mode-21 with no FB lease" },
                r.notes("rest").filter { it.startsWith("refused while asleep") }, "each refusal is a note of the rest's own kind")
            assertEquals(listOf("control: a fault that is not a refusal"), r.notes("fault"), "no refusal is journaled as a fault")
            assertEquals(listOf("transport/control"), r.notices().map { it.thread },
                "and none became a notice — the one that is not a refusal did: ${r.notices()}")
            assertTrue(r.notes("silent").isEmpty(), "a rest writes no `silent` note: ${r.notes("silent")}")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    /**
     * The other side of both rules, AWAKE (the P4 verify pass: neither had a pin). (1) The model's refusal
     * is expected only while the shell is asleep: awake, a `decode` fault is a fault — journaled as one and
     * raised as a notice — or a real divergence would pass as a note. (2) A session rebuild the transport
     * refuses while the shell is awake (a lease that lapsed on a Damage build; an adopted session with no
     * lease) is not a `silent` note — that kind is the firmware's Silent Mode's — and promises no second
     * ask: no paced check runs for an awake shell. The lapse is handed in by hand, and this transport
     * takes no rebuild.
     * Mutations watched: the refusals expected whether or not the shell is asleep; an awake shell's refused
     * rebuild journaled as a `silent` note; its words promising the next check.
     */
    @Test
    fun awakeADecodeFaultIsAFaultAndARefusedRebuildIsNotASilentNote(): Unit = runBlocking {
        val scope = scope()
        val extra = kotlinx.coroutines.flow.MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
        class Lab(val simT: SimTransport) : Transport by simT {
            override val events: kotlinx.coroutines.flow.Flow<TransportEvent> = extra
            init { scope.launch(start = CoroutineStart.UNDISPATCHED) { simT.events.collect { extra.emit(it) } } }
            override suspend fun restartSession(reason: String): Boolean = false
        }
        val r = Rig(scope, contract = 2, make = { sim, sc -> Lab(SimTransport(sim, sc, SimTransport.Timing(instant = true))) })
        try {
            r.up()
            assertTrue(r.transport.state.value.damageContract > 0, "a Damage build: a lapse there asks for a rebuild")
            extra.emit(TransportEvent.Fault("decode", "RIGHT mode-21 with no FB lease"))
            r.until("awake, the model's refusal is a notice") { r.notices().any { it.thread == "transport/decode" } }
            assertEquals(listOf("decode: RIGHT mode-21 with no FB lease"), r.notes("fault"), "and is journaled as a fault")

            extra.emit(TransportEvent.Lease(false, "test: the lease lapsed"))
            r.until("the refused rebuild is one notice") { r.notices().any { it.thread == "rebuild" } }
            r.barrier()
            val why = "the framebuffer lease lapsed on a Damage build — its flags went with it"
            assertEquals(listOf("session rebuild refused by the transport ($why) — not asleep, so no check asks again: the session goes on as it is"),
                r.notes("keeper").filter { it.startsWith("session rebuild refused") }, "an awake shell's refusal is a `keeper` note that promises nothing")
            assertTrue(r.notes("silent").isEmpty() && r.notes("rest").isEmpty(),
                "and neither a `silent` nor a `rest` note: silent=${r.notes("silent")} rest=${r.notes("rest")}")
            assertEquals("the transport refused a session rebuild ($why)", r.notices().single { it.thread == "rebuild" }.body,
                "the notice promises no second ask either")
            assertTrue(!r.shell.glassesAsleep, "awake throughout")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    /**
     * The same finding on the simulator with MODELED link timing, as the review writes the pin: a paint of
     * more than one 3,800 B image message is started, and the rest follows at once. The lanes share the
     * wire per message, so the release goes out between two messages of the image; the image completes
     * after it, and the model refuses what needs the lease — here the cached draws of the rows' text (a
     * plain window paint is v1 deltas, which the model streams into its shadow with no lease test: with
     * cached text off this scenario refuses nothing). (The review's `statusLine == "ok"` cannot hold: the
     * cell's word for a shell asleep is `silent`; what is asserted is that no fault is journaled and no
     * notice waits.) This form depends on the release overtaking the image, which the modeled timing gives
     * (about a third of a second per message); the pin says so if it ever does not, rather than pass on
     * nothing.
     * Mutation watched: only `imgres` expected while asleep.
     */
    @Test
    fun aPaintTheReleaseOvertakesIsRefusedInTheJournalOnly(): Unit = runBlocking {
        val scope = scope()
        val r = Rig(scope, contract = 2, cached = true, make = { sim, sc -> SimTransport(sim, sc) })     // 176 ms an ack, 11 KB/s
        try {
            r.shell.silentPacingMs = 60_000
            r.upWithTheAtlasLive()                           // the rows' face is on the glasses
            r.shell.services.runOnShell { r.shell.services.openWindow("noisy", null) }
            r.until("the window's paint is on its way") { r.inFlight().isNotEmpty() }
            assertTrue(r.shell.devProbe("rest", "on"))
            r.until("resting") { r.shell.resting }
            r.until("the paint's completion landed") { r.inFlight().isEmpty() }
            // an absence over a bounded wait (R2, RH-8): 500 ms after the paint's completion landed — about three of the
            // modeled acks — and a barrier; a fault or a notice raised later than that is not seen by the two asserts below
            delay(500); r.barrier()
            // first that the scenario happened at all — the model refused something after the release,
            // whichever kind of note took it — and only then where it went
            val asNote = r.notes("rest").filter { it.startsWith("refused while asleep (decode): ") }
            val asFault = r.notes("fault").filter { it.startsWith("decode: ") }
            assertTrue(asNote.isNotEmpty() || asFault.isNotEmpty(),
                "the release did not overtake the paint in this run, so nothing was refused and nothing is pinned: rest=${r.notes("rest")} " +
                    "lines=${r.lines().filter { "\"ev\":\"submit\"" in it || "\"ev\":\"done\"" in it }.takeLast(2)}")
            assertTrue(r.notes("fault").isEmpty(), "what the release overtook is not journaled as a fault: ${r.notes("fault")}")
            assertTrue(asNote.isNotEmpty(), "it is a note of the rest's own kind: ${r.notes("rest")}")
            assertTrue(r.notices().none { it.thread.startsWith("transport/") }, "and none waits as a notice for the wake: ${r.notices()}")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    // ------------------------------------------------------------------ the lease stays released
    /**
     * The P4 review's finding 12. The renewal loop tests `leaseWanted` and THEN enqueues; `setLeaseWanted(false)`
     * sets the flag and then enqueues its release — so an ACQUIRE can sit in the control lane's queue with
     * the lease no longer wanted (interleaved the other way round it sits BEHIND the release: a fresh
     * acquire into a rest, and a lapse 88 s later). The lane now decides at the write, from the flag as it
     * stands. The order is MADE here, not hoped for: the lane is parked inside another write, an acquire is
     * queued behind it while the lease is wanted (`setLeaseWanted(true)`, taken to its first suspension by
     * an undispatched launch), and the release that follows flips the flag before the lane moves again.
     * Mutation watched: the lane tests `running` alone (the queued acquire is written, one per arm).
     */
    @Test
    fun anAcquireQueuedBeforeTheReleaseIsNotWrittenOnceTheLeaseIsNotWanted(): Unit = runBlocking {
        val scope = scope()
        val sim = GlassFirmwareSim()
        val t = Parked(sim, scope)
        try {
            suspend fun until(what: String, cond: () -> Boolean) {
                val t0 = System.currentTimeMillis()
                while (true) {
                    if (cond()) return
                    if (System.currentTimeMillis() - t0 >= 10_000) throw AssertionError("never true: $what")
                    delay(5)
                }
            }
            t.start(wm.damage.core.gfx.Zl.encodeCfw(wm.damage.core.gfx.Pack.rect(Gray8(640, 480), wm.damage.core.geom.Rect(0, 0, 640, 480))))
            until("renewals flow while the lease is wanted (the instant loop runs every 50 ms)") { sim.acquiresSeen >= 6 }

            t.gate = CompletableDeferred()
            until("the control lane is parked inside a write that is not a lease op") { t.parked }
            // nothing is written while the lane is parked: this count stands until the gate opens
            val acquires = sim.acquiresSeen
            val releases = sim.releasesSeen
            val jobs = listOf(false, true, false).map { wanted ->
                // undispatched: each call runs to its own first suspension — the flag set, its op queued —
                // before the next line, so the queue behind the parked write is RELEASE, ACQUIRE, RELEASE
                scope.launch(start = CoroutineStart.UNDISPATCHED) { t.setLeaseWanted(wanted) }
            }
            t.gate!!.also { t.gate = null }.complete(Unit)
            jobs.forEach { it.join() }
            until("both releases reached the glasses") { sim.releasesSeen == releases + 4 }
            assertEquals(acquires, sim.acquiresSeen,
                "no ACQUIRE is written once the lease is not wanted — the one queued between the two releases, and any renewal queued before them")
            val now = System.currentTimeMillis()
            assertTrue(!sim.leaseHeld(Arm.LEFT, now) && !sim.leaseHeld(Arm.RIGHT, now), "the glasses' lease is released")
            // and the renewal loop stays quiet for as long as the lease is not wanted (an absence over
            // six instant renewal periods, said as such)
            delay(300)
            assertEquals(acquires, sim.acquiresSeen, "no renewal while the lease is not wanted")
            t.stop()
        } finally { scope.cancel() }
    }

    /**
     * P5 item 3 (review 1b RH-5, the confirmed form of P4-verify §4.2 a): a caller of the session before — a sleep's
     * release coroutine that runs late — turned the lease off under the next `start()`. The lane then dropped the
     * start's ACQUIRE (the lease not wanted), the start still reported the lease held, and the renewal loop, the
     * keepalive, the carrier refresh and the watchdog were off for an awake session. The order is MADE: the gate is
     * set before the start, so the lane parks in the prelude's write with the start in progress; the call comes then.
     * Fixed: a lease change asked during a start is not taken (a `control` note), and the start's ACQUIRE is written.
     * Mutation watched: `setLeaseWanted` takes the call during a start.
     */
    @Test
    fun aLeaseChangeAskedDuringAStartIsNotTaken(): Unit = runBlocking {
        val scope = scope()
        val sim = GlassFirmwareSim()
        val t = Parked(sim, scope)
        val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            t.events.collect { if (it is TransportEvent.Note && it.kind == "control") notes.add(it.detail) }
        }
        try {
            suspend fun until(what: String, cond: () -> Boolean) {
                val t0 = System.currentTimeMillis()
                while (true) {
                    if (cond()) return
                    if (System.currentTimeMillis() - t0 >= 10_000) throw AssertionError("never true: $what")
                    delay(5)
                }
            }
            t.gate = CompletableDeferred()
            val starting = scope.launch {
                t.start(wm.damage.core.gfx.Zl.encodeCfw(wm.damage.core.gfx.Pack.rect(Gray8(640, 480), wm.damage.core.geom.Rect(0, 0, 640, 480))))
            }
            until("the start is in progress, its lane parked in the prelude's write") { t.parked }
            t.setLeaseWanted(false)                                     // a caller of the session before
            t.gate!!.also { t.gate = null }.complete(Unit)
            starting.join()
            assertTrue(t.state.value.leaseHeld, "the start reports the lease held")
            until("the glasses hold the lease the start reports (ACQUIRE seen ${sim.acquiresSeen}; " +
                "the call taken during the start leaves the start's ACQUIRE dropped)") {
                val now = System.currentTimeMillis()
                sim.acquiresSeen >= 2 && sim.leaseHeld(Arm.LEFT, now) && sim.leaseHeld(Arm.RIGHT, now)
            }
            assertTrue(notes.any { "during a start" in it && "not taken" in it }, "the refused call is said: $notes")
            t.stop()
        } finally { scope.cancel() }
    }

    // ------------------------------------------------------------------ the atlas
    /** Up, in the Rows window, with cached text on and its face live. */
    private suspend fun Rig.upWithTheAtlasLive() {
        up()
        shell.services.runOnShell { shell.services.openWindow("rows", null) }
        barrier(); settle("rows open")
        shell.updateSettings { it.copy(cachedText = "on") }
        until("the atlas uploads and the face goes live") { shell.cachedTextActive && shell.cachedFontsLive.isNotEmpty() }
        settle("after the upload")
    }

    /** Existing behaviour, pinned for a rest (no mutation of its own): the release frees the glasses' cache,
     *  the wake's acquire is not a renewal, so the atlas is reset and goes up again. */
    @Test
    fun theAtlasGoesAgainAfterARest(): Unit = runBlocking {
        val scope = scope(); val r = Rig(scope, contract = 2, cached = true)
        try {
            r.shell.silentPacingMs = 60_000
            r.upWithTheAtlasLive()
            assertTrue(Arm.entries.all { r.sim.cacheAllocated(it) }, "the model holds the cache on both arms")
            val resets = r.notes("atlas").count { it.startsWith("reset — ") }
            r.rest()
            assertTrue(Arm.entries.none { r.sim.cacheAllocated(it) }, "the release freed the cache on both arms")
            assertTrue(r.notes("atlas").none { "cached text off" in it } && r.notices().isEmpty(), "a rest judges nothing: ${r.notes("atlas").takeLast(4)} ${r.notices()}")
            r.shell.devProbe("rest", "off")
            r.until("the wake rebuilds the session") { r.keeper.attempts == 2 && r.driving() && !r.shell.glassesAsleep }
            r.until("the atlas is reset for a release, not kept") {
                r.notes("atlas").count { it.startsWith("reset — ") } == resets + 1 }
            assertTrue("L released, R released" in r.notes("atlas").last { it.startsWith("reset — ") }, "${r.notes("atlas").last { it.startsWith("reset — ") }}")
            r.until("and goes up again: the face is live") { r.shell.cachedTextActive && r.shell.cachedFontsLive.isNotEmpty() }
            r.settle("after the second upload")
            assertTrue(Arm.entries.all { r.sim.cacheAllocated(it) })
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); r.barrier(); r.settle("a notch from the cache")
            r.assertGlassMatchesBelief("cached draws after a rest")
            r.keeper.stop()
        } finally { r.close(); scope.cancel() }
    }

    /**
     * P4's Q7: a cache write in flight when the shell goes to sleep is NOT judged against the cache the
     * release frees. Until 0.64 its completion was read as "this layout's upload finished": the read-back
     * that followed found no cache ("the glasses hold no texture cache"), or the firmware's Silent Mode had
     * refused the chunk ("the glasses refused a cache write") — a fault notice and cached text off, for a
     * sleep. Nothing may be sent into the sleeping pair either: the unjudged messages leave the books with
     * a note and NO keyframe is asked for (the wake's session start keyframes by itself).
     *
     * The order is made, not hoped for (the first form of this pin sent the sleep "at once" on a link with
     * modeled timing and lost a race of the model's own about one run in eight): the image lane is stepped
     * one message at a time until the message parked on the wire is the batch's LAST chunk — the note
     * ending "(0 to go)"; only a batch's last completion is judged, the review's finding 1c — the shell is
     * put to sleep with that chunk still unwritten, and then the wire is opened. In the rest's case the
     * chunk lands with the lease still held (the release waits for the wire behind it) and is acked; under
     * Silent Mode the model refuses it. An ABSENCE after a bounded wait, said as such: the chunk's own
     * completion is waited for by its id, then 600 ms for a read-back's round trip on a link that answers
     * within milliseconds.
     * Mutations watched: the two lines removed from the sleep's entry; the keyframe asked for at a sleep.
     */
    @Test
    fun aCacheWriteInFlightAtARestIsNotJudged(): Unit = runBlocking { aCacheWriteInFlightAtTheSleep("a rest") }

    /** The same pin for the sleep 0.63 already had: the hole was the Silent Mode path's before it was a rest's. */
    @Test
    fun aCacheWriteInFlightAtTheSilentSleepIsNotJudged(): Unit = runBlocking { aCacheWriteInFlightAtTheSleep("the firmware's Silent Mode") }

    private suspend fun aCacheWriteInFlightAtTheSleep(variant: String) {
        run {
            val scope = scope()
            val r = Rig(scope, contract = 2, cached = true, make = { sim, sc -> Parked(sim, sc) })
            val t = r.transport as Parked
            try {
                r.shell.silentPacingMs = 60_000
                r.up(variant)
                r.shell.services.runOnShell { r.shell.services.openWindow("rows", null) }
                r.barrier(); r.settle("$variant: rows open")
                t.parkImages()
                r.shell.updateSettings { it.copy(cachedText = "on") }
                var seen = 0
                var id = -1L
                while (true) {
                    r.until("$variant: image message ${seen + 1} is parked on the wire") { t.imagesParked.get() > seen }
                    seen = t.imagesParked.get()
                    // the parked message is of the oldest flush submitted and not done; the loop journals a
                    // submit right after it, and an atlas chunk's own note right after that
                    r.until("$variant: its submit is journaled") { r.inFlight().isNotEmpty() }
                    val (flush, label) = r.inFlight().first()
                    if (label == "ATLAS") {
                        val mine = Regex("chunk of \\d+ B submitted as flush $flush \\((\\d+) to go\\)")
                        var toGo = -1
                        r.until("$variant: chunk $flush's note") { r.notes("atlas").firstNotNullOfOrNull { mine.find(it) }?.also { toGo = it.groupValues[1].toInt() } != null }
                        if (toGo == 0) { id = flush; break }
                    }
                    t.step()
                }
                if (variant == "a rest") assertTrue(r.shell.devProbe("rest", "on")) else r.sim.setSilent(true)
                r.until("$variant: asleep") { r.shell.glassesAsleep }
                r.barrier()
                assertEquals(listOf(id), r.inFlight().map { it.first }, "$variant: the batch's last chunk, and only it, is in flight at the sleep")
                assertTrue("keyframe" !in r.shell.quiescenceReport(), "$variant: no keyframe is owed into a sleeping pair: ${r.shell.quiescenceReport()}")
                val sent = r.lines().count { "\"ev\":\"submit\"" in it }

                t.openImages()                                  // the chunk reaches the glasses now
                r.until("$variant: the chunk's completion landed") { r.inFlight().isEmpty() }
                delay(600); r.barrier()
                val atlas = r.notes("atlas")
                assertTrue(atlas.none { "cached text off" in it }, "$variant: nothing is judged against the freed cache: ${atlas.takeLast(4)}")
                assertTrue(r.notices().none { it.thread == "atlas" || it.thread.startsWith("transport/") },
                    "$variant: and no notice waits for the wake: ${r.notices()}")
                assertEquals(sent, r.lines().count { "\"ev\":\"submit\"" in it }, "$variant: nothing was sent after the sleep")
                val rebased = r.notes("atlas").filter { it.startsWith("count re-based — ") }
                assertTrue(rebased.size == 1 && rebased[0].endsWith("are not judged (the shell went to sleep); " +
                    "no keyframe: nothing is sent while asleep, and the wake's session start keyframes"),
                    "$variant: the batch's earlier messages, acked and unjudged, left the books at the sleep, and the note asks for no keyframe: $rebased")
                assertTrue(r.shell.glassesAsleep && "keyframe" !in r.shell.quiescenceReport(), "$variant: ${r.shell.quiescenceReport()}")
                r.keeper.stop()
            } finally { t.openImages(); r.close(); scope.cancel() }
        }
    }
}
