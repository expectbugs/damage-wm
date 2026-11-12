package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.BatteryLatch
import wm.damage.core.shell.BatteryLatch.Verdict.NONE
import wm.damage.core.shell.BatteryLatch.Verdict.REOPENED
import wm.damage.core.shell.BatteryLatch.Verdict.SAY
import wm.damage.core.shell.Notifications
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent

/**
 * APK 0.64 (`HANDOFF.md` §75.4 calls 1-3; `DESIGN.md` §9.3): the low glasses battery, said once per discharge
 * — the host's own alert path whatever `Phone notifications` says, and a notice on the glasses when the shell
 * can paint, whatever `Notify · Damage` says. No radio and no phone here: the hook is a list.
 *
 * Each pin was watched to fail on the mutation `research/mutations-76-p4.json` names for it.
 *
 * The harness (read `P1-verify` §4 before changing it). Two sources feed the shell one flow, in one order:
 * the model's own READ answers (a poll every 60 ms on this transport) and what a test emits by hand while
 * no session runs. [Lab.handed] counts an event once the shell's collector has TAKEN it, so a wait for a
 * reading is a wait for that reading; [Rig.mark] then sends a note down the same flow, and when the note is
 * in the journal everything taken before it has been handled. Waits decide on ONE evaluation. Where the
 * order of two messages is the subject (a live reading against a held one), [Lab.afterStart] holds the
 * start until the live one is queued — the order is made, not hoped for.
 */
class LowBatteryTest {

    // ------------------------------------------------------------------ the latch, pure
    @Test
    fun theLatchSaysItOncePerDischarge() {
        val l = BatteryLatch()
        fun r(pct: Int, charging: Boolean = false, canTell: Boolean = true) = l.reading(pct, charging, canTell)
        assertEquals(NONE, r(21))
        assertEquals(NONE, r(20), "20 is not below 20")
        assertEquals(SAY, r(19))
        assertEquals(listOf(NONE, NONE, NONE), listOf(r(18), r(20), r(24)), "closed: nothing more this discharge, and 24 does not re-arm")
        assertEquals(REOPENED, r(25), "25 or more re-arms")
        assertEquals(NONE, r(25), "open already")
        assertEquals(SAY, r(19))
        assertEquals(REOPENED, r(19, charging = true), "a charging reading re-arms at any level")
        assertEquals(SAY, r(19))
        assertEquals(REOPENED, r(15, charging = true))
        assertEquals(NONE, r(15, charging = true), "charging with the latch open says nothing")
        assertEquals(NONE, r(100))
        // nobody to tell: the notice is not spent — the latch stays open and the next reading tries again
        assertEquals(NONE, r(19, canTell = false))
        assertEquals(NONE, r(18, canTell = false))
        assertEquals(SAY, r(18), "said to the first reading that could be told to somebody")
        assertEquals(REOPENED, r(30, canTell = false), "re-arming needs nobody")
        assertEquals(20, BatteryLatch.LOW_PCT); assertEquals(25, BatteryLatch.REARM_PCT)
    }

    // ------------------------------------------------------------------ the shell
    /** The simulator's transport, plus: events by hand, a count of what the shell has taken, a held start. */
    private class Lab(val simT: SimTransport, scope: CoroutineScope) : Transport by simT {
        val extra = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
        private val taken = ArrayList<TransportEvent>()
        /** The shell's collector runs its own action inside `emit`: an event is added once that returned. */
        override val events: Flow<TransportEvent> = flow { extra.collect { emit(it); synchronized(taken) { taken.add(it) } } }
        init { scope.launch(start = CoroutineStart.UNDISPATCHED) { simT.events.collect { extra.emit(it) } } }
        fun handed(p: (TransportEvent) -> Boolean): Int = synchronized(taken) { taken.count(p) }
        /** Runs after the transport's start and before the shell's start goes on — events the start
         *  produced can be waited for here, so they are queued ahead of anything the shell posts next. */
        @Volatile var afterStart: (suspend () -> Unit)? = null
        override suspend fun start(warmupFrame: ByteArray) { simT.start(warmupFrame); afterStart?.invoke() }
    }

    private class Rig(scope: CoroutineScope, settings: ShellSettings? = null, hook: Boolean = true) {
        val tmp = Files.createTempDirectory("damage-lowbatt")
        val sim = GlassFirmwareSim().also { it.batteryPct = 50 }
        val simT = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val lab = Lab(simT, scope)
        val journalPath = tmp.resolve("journal.jsonl")
        val store = Persistence(tmp.resolve("state.json")).also { p -> if (settings != null) p.put("shell.settings", settings.toJson()) }
        val shell = Shell(FakeText(), lab, store, journalPath, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        /** The host's alert path: (low, pct) as the latch closes and opens. */
        val hookCalls = CopyOnWriteArrayList<Pair<Boolean, Int>>()
        @Volatile var hookThrows = false
        /** What the hook answers to "shown?" (R2, RG-3): false = a phone whose notifications are off. */
        @Volatile var hookShows = true
        val rings = AtomicInteger()
        private var marks = 0

        init {
            shell.onUrgent = { _, _ -> rings.incrementAndGet() }
            if (hook) shell.onGlassesBattery = { low, pct ->
                hookCalls.add(low to pct)
                if (hookThrows) throw IllegalStateException("the notification service is gone")
                hookShows
            }
        }

        fun notes(kind: String): List<String> = (if (!Files.exists(journalPath)) emptyList() else Files.readAllLines(journalPath))
            .filter { "\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").removeSuffix("\"}") }
        fun batteryNotices(): List<Notifications.Notice> =
            (listOfNotNull(shell.notifications.current) + shell.notifications.queued()).filter { it.thread == "battery" }

        suspend fun until(what: String, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (true) {
                if (cond()) return
                if (System.currentTimeMillis() - t0 >= 10_000)
                    throw AssertionError("never true: $what — hook=$hookCalls lowbatt=${notes("lowbatt")} box=${shell.notifications.current} ${shell.quiescenceReport()}")
                delay(5)
            }
        }
        suspend fun settle(what: String) = until("$what: settled") { shell.isQuiescent() }

        /** Everything the shell's collector took before this call has been handled when it returns. */
        suspend fun mark() {
            val tag = "m${++marks}"
            lab.extra.emit(TransportEvent.Note("mark", tag))
            until("the mark $tag is in the journal") { notes("mark").contains(tag) }
        }

        /** One event by hand (what arrives while no session runs), handled before this returns. */
        suspend fun emit(ev: TransportEvent) { lab.extra.emit(ev); mark() }

        /** The model reads [pct] / [charging] from now on (change one of the two per call): returns once a
         *  READ's answer with those values has been taken by the shell and handled. */
        suspend fun reads(pct: Int, charging: Boolean = false) {
            fun n() = lab.handed { it is TransportEvent.Battery && it.glassesPct == pct && it.glassesCharging == charging }
            sim.batteryPct = pct; sim.charging = charging
            val n0 = n()
            until("a reading of $pct% (charging=$charging) reached the shell") { n() > n0 }
            mark()
        }
        fun close() { tmp.toFile().deleteRecursively() }
    }

    private val low = true to 19
    private val open19 = false to 19

    @Test
    fun belowTwentySaysItOnThePhoneAndOnTheGlasses(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // both rows that gate other notices are OFF: `Notify · Damage` here, `Phone notifications` by default
        val r = Rig(scope, ShellSettings(notifyDamage = false))
        try {
            assertTrue(!r.shell.settings.phoneNotifications)
            r.shell.start(); r.settle("start")
            r.reads(21); r.reads(20)
            assertTrue(r.hookCalls.isEmpty() && r.notes("lowbatt").isEmpty(), "20 is not below 20")
            r.reads(19)
            r.until("the notice is on the glasses") { r.shell.notifications.current?.body == "glasses battery 19% — time to charge" }
            assertEquals("DAMAGE · glasses", r.shell.notifications.current?.source)
            assertEquals(listOf(low), r.hookCalls.toList(), "the host's own path heard it, once")
            assertEquals(0, r.rings.get(), "not through the urgent path, which `Phone notifications` gates")
            assertEquals(listOf("said at 19% — the phone and the glasses"), r.notes("lowbatt"))
            r.settle("the box unfurled")
            assertEquals("ok", r.shell.statusLine, "no fault episode: the cell does not say `fault` for a low battery")

            r.reads(18); r.reads(19); r.reads(17)
            assertEquals(listOf(low), r.hookCalls.toList(), "once per discharge")
            assertEquals(1, r.notes("lowbatt").size)
            assertEquals(1, r.batteryNotices().size, "one box, nothing queued behind it")
            // the row is off for everything else: a courtesy notice is filtered, which is what the box above is not
            r.shell.services.notifyInternal("torrent", "done · something")
            r.mark(); r.settle("a courtesy notice")
            assertEquals(0, r.shell.notifications.queueDepth)
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    @Test
    fun asleepThePhoneAloneIsTold(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            r.sim.setSilent(true)
            r.until("the shell sleeps with the glasses") { r.shell.glassesAsleep }
            r.reads(19)                                        // the 60 s READ goes on through a sleep
            assertEquals(listOf(low), r.hookCalls.toList())
            assertEquals(listOf("said at 19% — the phone only (asleep)"), r.notes("lowbatt"))
            assertTrue(r.batteryNotices().isEmpty(), "never an image into a pair that refuses them: ${r.batteryNotices()}")
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    /** R2 (RG-3, step 1): the hook says whether the phone showed it — a phone with Damage's notifications
     *  (or its Glasses battery channel) off shows nothing and `notify()` says no error — and the note claims
     *  the phone only when it did. Awake, the glasses alone were told; asleep, nobody was.
     *  Mutation watched: `shown = true` whatever the hook returned. */
    @Test
    fun aPhoneThatWillNotShowItIsNotClaimed(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope).also { it.hookShows = false }
        try {
            r.shell.start(); r.settle("start")
            r.reads(19)
            r.until("the notice is on the glasses") { r.shell.notifications.current?.body == "glasses battery 19% — time to charge" }
            assertEquals(listOf(low), r.hookCalls.toList(), "the hook was asked")
            assertEquals(1, r.notes("lowbatt").size, "${r.notes("lowbatt")}")
            assertTrue(r.notes("lowbatt")[0].startsWith("said at 19% — the glasses only (the phone will not show it"), "${r.notes("lowbatt")}")
            r.shell.stop()
        } finally { r.close(); scope.cancel() }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r2 = Rig(scope2).also { it.hookShows = false }
        try {
            r2.shell.start(); r2.settle("start")
            r2.sim.setSilent(true)
            r2.until("the shell sleeps with the glasses") { r2.shell.glassesAsleep }
            r2.reads(19)
            assertEquals(listOf(low), r2.hookCalls.toList())
            assertEquals(1, r2.notes("lowbatt").size, "${r2.notes("lowbatt")}")
            assertTrue(r2.notes("lowbatt")[0].startsWith("said at 19% — nowhere (the phone will not show it") &&
                r2.notes("lowbatt")[0].endsWith("; asleep)"), "${r2.notes("lowbatt")}")
            r2.shell.stop()
        } finally { r2.close(); scope2.cancel() }
    }

    /** The glasses' half needs a link that is up and steady, not only an awake shell (the P4 verify pass:
     *  the term had no pin). Between a link's end and the keeper's stop the shell still runs — here no
     *  keeper stops it at all — and a notice posted then belongs to a session that is ending: the phone
     *  alone is told, and the note says why. The reading is handed in by hand: no READ comes back over a
     *  link that is down.
     *  Mutation watched: the notice posted whenever the shell is awake. */
    @Test
    fun onALinkThatIsNotSteadyThePhoneAloneIsTold(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            r.simT.endLink("test: the link ends, and nothing stops this shell")
            r.until("the link is down") { r.simT.state.value.let { !it.connected && !it.started } }
            r.emit(TransportEvent.Battery(19, false))
            assertEquals(listOf(low), r.hookCalls.toList(), "the phone's half needs no link")
            assertEquals(listOf("said at 19% — the phone only (the link is not steady)"), r.notes("lowbatt"))
            assertTrue(r.batteryNotices().isEmpty(), "no notice into a session that is ending: ${r.batteryNotices()}")
            assertTrue(!r.shell.glassesAsleep, "the shell was awake: the link was the reason")
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    @Test
    fun aSessionBoundaryDoesNotOpenTheLatch(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            r.reads(19)
            assertEquals(listOf(low), r.hookCalls.toList())
            r.settle("the box unfurled")                       // nothing in flight across the stop
            r.shell.stop()
            r.shell.start(); r.settle("the same instance, started again (the keeper does this all day)")
            r.reads(19); r.reads(18)
            assertEquals(listOf(low), r.hookCalls.toList(), "a discharge is not a session")
            assertEquals(1, r.notes("lowbatt").size, "${r.notes("lowbatt")}")
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    @Test
    fun chargingOrTwentyFiveOpensItAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            r.reads(19)
            r.reads(19, charging = true)
            assertEquals(listOf(low, open19), r.hookCalls.toList(), "the host withdraws what it showed")
            assertEquals("open again — a charging reading", r.notes("lowbatt").last())
            r.reads(19, charging = false)
            assertEquals(listOf(low, open19, low), r.hookCalls.toList(), "off the charger and still low: a new discharge")
            r.reads(25)
            assertEquals(false to 25, r.hookCalls.last())
            assertEquals("open again — 25%", r.notes("lowbatt").last())
            r.reads(24)
            assertEquals(4, r.hookCalls.size, "24 with the latch open: nothing to say")
            r.reads(19)
            r.reads(24); r.reads(19)
            assertEquals(listOf(low, open19, low, false to 25, low), r.hookCalls.toList(), "24 after a say does not re-arm")
            assertEquals(listOf("said at 19% — the phone and the glasses", "open again — a charging reading",
                "said at 19% — the phone and the glasses", "open again — 25%", "said at 19% — the phone and the glasses"), r.notes("lowbatt"))
            // the glasses' notice is not withdrawn when the latch opens: it stays as the record of each crossing
            // (same thread, so the three coalesce into one box)
            assertEquals(1, r.batteryNotices().size)
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    /** The P4 review's finding 1b. A reading with no session running tells the phone at its ARRIVAL and is
     *  not judged again: the held action is posted behind whatever the next start itself queued, so a
     *  replayed "19 %, not charging" behind a live "19 %, charging" would say it a second time. */
    @Test
    fun aReadingWithNoSessionRunningTellsThePhoneOnceAndIsNotReplayed(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            r.shell.stop()
            r.emit(TransportEvent.Battery(19, false))
            assertEquals(listOf(low), r.hookCalls.toList(), "the phone's half needs no link and no session")
            assertEquals(listOf("said at 19% — the phone only (no session running)"), r.notes("lowbatt"))

            // the pair goes on the charger before the next session: that start's own READ says so, and is
            // queued by the start AHEAD of the held reading (the gate below makes the order, not the clock)
            r.sim.batteryPct = 19; r.sim.charging = true
            r.lab.afterStart = { r.until("the start's own reading is queued") { r.lab.handed { it is TransportEvent.Battery && it.glassesCharging == true } >= 1 } }
            r.shell.start()
            r.lab.afterStart = null
            r.until("the live charging reading opened the latch") { r.hookCalls.toList() == listOf(low, open19) }
            r.reads(19, charging = true); r.settle("the session")
            assertEquals(listOf(low, open19), r.hookCalls.toList(), "the held reading was not judged a second time")
            assertEquals(1, r.notes("lowbatt").count { it.startsWith("said at") })
            assertEquals(19, r.shell.glassesBatteryPct)
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    /** The review's finding 6: on a host with no alert path of its own (the PC), a crossing nobody could be
     *  told about leaves the latch open — the notice is said to the first reading that can be painted. */
    @Test
    fun aHostWithNoPhonePathDoesNotSpendTheNoticeOnNobody(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope, hook = false)
        try {
            r.shell.start(); r.settle("start")
            r.shell.stop()
            r.emit(TransportEvent.Battery(19, false))
            r.emit(TransportEvent.Battery(18, false))
            assertTrue(r.notes("lowbatt").isEmpty(), "no phone path and no session to paint in: nothing is claimed — ${r.notes("lowbatt")}")
            r.sim.batteryPct = 18
            r.shell.start()
            r.until("the next session's reading is said on the glasses") { r.shell.notifications.current?.body == "glasses battery 18% — time to charge" }
            r.mark()
            assertEquals(listOf("said at 18% — the glasses only (this host has no phone path)"), r.notes("lowbatt"))
            r.settle("the box unfurled"); r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    /** The review's finding 13: the reading held while down is OLDER than one the start itself read, and
     *  is posted behind it — it must not put the stale level back in the battery cell. With no newer
     *  reading it is still the best there is. Poll answers are dropped below, so the level the chrome ends
     *  on is decided by those two messages alone. */
    @Test
    fun theHeldLevelDoesNotOverwriteTheLiveOne(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            r.reads(50)
            assertEquals(50, r.shell.glassesBatteryPct)
            r.shell.stop()

            r.emit(TransportEvent.Battery(41, false))          // held: the latest while down
            r.sim.batteryPct = 40
            val passed = AtomicInteger()
            // the start's own READ answer passes (this model's capability READ carries the block); every poll's is dropped
            r.simT.notifyFilter = { _, p -> !isDeviceInfoAnswer(p) || passed.getAndIncrement() == 0 }
            r.lab.afterStart = { r.until("the start's own reading is queued") { r.lab.handed { it is TransportEvent.Battery && it.glassesPct == 40 } >= 1 } }
            r.shell.start(); r.mark(); r.settle("the second session")
            assertEquals(40, r.shell.glassesBatteryPct, "the live reading stands; the held one behind it was dropped")
            r.shell.stop()

            // no reading of the start's own this time (as on the glasses: the capability READ carries no block)
            r.emit(TransportEvent.Battery(33, false))
            r.sim.capabilityReadCarriesDeviceInfo = false
            r.simT.notifyFilter = { _, p -> !isDeviceInfoAnswer(p) }
            r.lab.afterStart = null
            r.shell.start(); r.mark(); r.settle("the third session")
            assertEquals(33, r.shell.glassesBatteryPct, "with nothing newer, the held level reaches the chrome")
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    @Test
    fun aRingReadingIsNotAGlassesReading(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            r.emit(TransportEvent.Battery(ringPct = 5))
            r.shell.stop()
            r.emit(TransportEvent.Battery(ringPct = 4))
            assertTrue(r.hookCalls.isEmpty() && r.notes("lowbatt").isEmpty(), "hook=${r.hookCalls} notes=${r.notes("lowbatt")}")
        } finally { r.close(); scope.cancel() }
    }

    /** A host hook that throws is logged and journaled; it ends neither the loop's message nor — with no
     *  session running — the collector that delivers every transport event. */
    @Test
    fun aHookThatThrowsEndsNeitherTheLoopNorTheDelivery(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.hookThrows = true
            r.shell.start(); r.settle("start")
            r.reads(19)
            r.until("the glasses still show it") { r.shell.notifications.current?.body == "glasses battery 19% — time to charge" }
            assertEquals(listOf("the host's hook failed at 19%: the notification service is gone", "said at 19% — the glasses only (the phone's hook failed)"),
                r.notes("lowbatt"))
            r.settle("the box unfurled")
            assertEquals("ok", r.shell.statusLine, "a failing host hook is not a loop error")
            r.shell.stop()
            r.emit(TransportEvent.Battery(19, true))           // opens the latch with no session: the hook throws on the collector's thread
            assertEquals(listOf(low, open19), r.hookCalls.toList())
            r.emit(TransportEvent.Fault("after", "the delivery goes on"))
            assertTrue(r.notes("fault").any { it.startsWith("after: the delivery goes on") }, "an event after the throw still arrived: ${r.notes("fault")}")
        } finally { r.close(); scope.cancel() }
    }
}
