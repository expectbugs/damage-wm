package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.FaultEpisodes
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellSettings
import wm.damage.core.shell.ShellState
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/**
 * The status cell as states, every event as a notice (`HANDOFF.md` §66.2, §69 — Adam's ruling).
 * One pin per decision; each was watched to fail on the tree before the change, where the cell
 * held one stored string that the last writer won.
 */
class StatusCellTest {

    // ------------------------------------------------------------------ the word, pure
    @Test
    fun theWordIsTheFirstConditionThatHoldsAndOneExitNeverClearsAnother() {
        fun w(connected: Boolean = true, silent: Boolean = false, rebuilding: Boolean = false, lease: Boolean = true,
              halted: Boolean = false, fault: Boolean = false, slow: Boolean = false) =
            ShellState.of(connected, silent, rebuilding, lease, halted, fault, slow).word
        assertEquals("ok", w())
        assertEquals("link slow", w(slow = true))
        assertEquals("fault", w(slow = true, fault = true))
        assertEquals("halted", w(slow = true, fault = true, halted = true))
        assertEquals("no lease", w(halted = true, lease = false))
        assertEquals("rebuild", w(lease = false, rebuilding = true))
        assertEquals("silent", w(lease = false, rebuilding = true, silent = true), "a lease released on purpose is never `no lease`")
        assertEquals("no link", w(connected = false, silent = true, rebuilding = true, lease = false, halted = true, fault = true, slow = true))
        // the class the stored string had (`if (statusText == "X") setStatus("ok")`): a fault that
        // ends under a slow link leaves `link slow`, and the slow link ending leaves the fault
        assertEquals("link slow", w(slow = true, fault = false))
        assertEquals("fault", w(slow = false, fault = true))
    }

    // ------------------------------------------------------------------ episodes, pure
    @Test
    fun aThousandRepeatsAreOnePostTenUpdatesAndOneRing() {
        val e = FaultEpisodes { 60_000L }
        var posts = 0; var updates = 0; var rings = 0
        for (i in 0 until 1000) {
            val v = e.raise("pump", urgent = true, nowMs = 1_000L + i * 16)     // one per pump
            if (v.action == FaultEpisodes.Action.POST) posts++
            if (v.action == FaultEpisodes.Action.UPDATE) updates++
            if (v.ring) rings++
        }
        assertEquals(1, posts)
        assertEquals(9, updates, "×2 … ×512")
        assertEquals(1, rings, "the phone hears an episode once")
        assertTrue(e.anyOpen(1_000L + 1000 * 16))
        assertFalse(e.anyOpen(1_000L + 1000 * 16 + 60_000L), "a quiet minute closes it")
        assertEquals(FaultEpisodes.Action.POST, e.raise("pump", true, 200_000L).action, "and the next one is news")
    }

    @Test
    fun anUrgentRepeatOfAnEpisodeOpenedQuietlyStillRingsOnce() {
        val e = FaultEpisodes { 60_000L }
        assertFalse(e.raise("transport/ack", urgent = false, nowMs = 0).ring)
        assertTrue(e.raise("transport/ack", urgent = true, nowMs = 10).ring)
        assertFalse(e.raise("transport/ack", urgent = true, nowMs = 20).ring)
        // classes are independent, and a clear (a session start) makes everything news again
        assertEquals(FaultEpisodes.Action.POST, e.raise("flush", false, 30).action)
        e.clear()
        assertEquals(FaultEpisodes.Action.POST, e.raise("transport/ack", false, 40).action)
    }

    // ------------------------------------------------------------------ the shell
    /** The sim's transport with a way to say a fault. ONE shared flow the shell subscribes to as it
     *  does to the sim's own, fed from construction on (undispatched: subscribed before anything can
     *  be emitted) — a `merge` per collection subscribes late and loses the first FlushDone. */
    private class FaultyTransport(private val inner: Transport, scope: CoroutineScope) : Transport by inner {
        val extra = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
        override val events: Flow<TransportEvent> get() = extra
        init { scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { inner.events.collect { extra.emit(it) } } }
    }

    private class Rig(scope: CoroutineScope, settings: ShellSettings? = null) {
        val tmp = Files.createTempDirectory("damage-status")
        val transport = FaultyTransport(SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), scope)
        val store = Persistence(tmp.resolve("state.json")).also { p -> if (settings != null) p.put("shell.settings", settings.toJson()) }
        val shell = Shell(FakeText(), transport, store, null, scope) { Shell.LocalClock(12, 0, "12:00", "PM") }
        val rings = AtomicInteger()
        init { shell.onUrgent = { _, _ -> rings.incrementAndGet() } }

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) { if (shell.isQuiescent()) return; delay(10) }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }
        suspend fun until(what: String, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < 10_000) delay(10)
            assertTrue(cond(), what)
        }
        fun close() { tmp.toFile().deleteRecursively() }
    }

    private val long = "a message far longer than any status cell: java.lang.IllegalStateException in wm.damage.core.shell.Something at line 1234"

    @Test
    fun aLoopErrorIsANoticeWithItsWholeTextAndTheCellKeepsTheEcho(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); r.settle("a notch")
            assertEquals("ok", r.shell.statusLine)
            r.shell.services.runOnShell { throw IllegalStateException(long) }
            r.until("the notice is up (${r.shell.notifications.current})") { r.shell.notifications.current?.body?.contains(long) == true }
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM); r.settle("a notch after the error")
            assertEquals("fault", r.shell.statusLine, "the cell says a fault episode is open")
            assertEquals("fault", r.shell.chromeStatus); assertEquals("down", r.shell.chromeEcho, "the echo is still painted")
            assertEquals(0, r.shell.chromeStatusFitRuns, "nothing in the cell was fitted or cut")
            assertEquals("DAMAGE · shell", r.shell.notifications.current?.source)
            assertEquals(1, r.rings.get(), "a loop error reaches the phone, once")
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    @Test
    fun aFaultOnEveryMessageIsOneBoxOneRingAndACount(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            repeat(300) { i -> r.shell.services.runOnShell { throw IllegalStateException("fault $i") } }
            r.settle("three hundred loop errors")
            val n = r.shell.notifications
            assertEquals(0, n.queueDepth, "one box, nothing queued behind it")
            assertTrue(n.current?.body?.endsWith("(×256)") == true, "the one box carries the count: ${n.current?.body}")
            assertEquals(1, r.rings.get(), "the phone heard it once")
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    @Test
    fun aDismissedFaultStaysGoneUntilTheClassHasBeenQuiet(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.faultQuietMs = 5_000L          // longer than the box's 2.5 s grace: the dismissal lands INSIDE the episode
            r.shell.start(); r.settle("start")
            r.transport.extra.emit(TransportEvent.Fault("wrap", "first"))
            r.until("the notice is up") { r.shell.notifications.current?.body == "wrap: first" }
            r.until("the box took focus after its grace") { r.shell.notifications.focused }
            r.shell.postGesture(EvenHubMsg.EV_CLICK)                       // a tap on a focused box dismisses it
            r.until("dismissed") { r.shell.notifications.current == null }
            repeat(8) { r.transport.extra.emit(TransportEvent.Fault("wrap", "again $it")) }
            r.settle("repeats inside the episode")
            assertEquals(null, r.shell.notifications.current, "a repeat does not bring a dismissed box back")
            assertEquals(0, r.shell.notifications.queueDepth)
            assertEquals(0, r.rings.get(), "`wrap` is not one of the urgent transport faults")
            r.until("the word cleared itself after the quiet period (is '${r.shell.statusLine}')") { r.shell.statusLine == "ok" }
            r.transport.extra.emit(TransportEvent.Fault("holdback", "a feature was held back after a reset"))
            r.until("a new class is news at once, and urgent (box ${r.shell.notifications.current}, rings ${r.rings.get()})") {
                r.shell.notifications.current?.body?.startsWith("holdback") == true && r.rings.get() == 1 }
            r.transport.extra.emit(TransportEvent.Fault("wrap", "later"))
            r.until("and the quiet class is news again") { r.shell.notifications.queueDepth == 1 }
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    @Test
    fun aFaultIsShownWithNotifyDamageOffAndACourtesyNoticeIsNot(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope, ShellSettings(notifyDamage = false))
        try {
            r.shell.start(); r.settle("start")
            r.shell.services.notifyInternal("torrent", "done · something")
            r.settle("a courtesy notice")
            assertEquals(null, r.shell.notifications.current, "Notify · Damage off filters courtesy notices")
            r.transport.extra.emit(TransportEvent.Fault("wrap", "a real fault"))
            r.until("the fault shows all the same") { r.shell.notifications.current?.body == "wrap: a real fault" }
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }

    @Test
    fun aSessionStartClearsTheEpisodes(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start(); r.settle("start")
            r.transport.extra.emit(TransportEvent.Fault("wrap", "before"))
            r.until("the notice is up") { r.shell.notifications.current?.body == "wrap: before" }
            r.settle("the box unfurled")              // nothing in flight across the stop
            assertEquals("fault", r.shell.statusLine)
            r.shell.stop()
            r.shell.start(); r.settle("the same instance, started again (the keeper does this all day)")
            r.until("no episode crossed the boundary (is '${r.shell.statusLine}')") { r.shell.statusLine == "ok" }
            r.shell.stop()
        } finally { r.close(); scope.cancel() }
    }
}
