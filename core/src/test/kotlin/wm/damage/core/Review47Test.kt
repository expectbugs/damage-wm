package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.LinkState
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §47 (2026-09-12) — the link kept fast and the slow case
 * made loud, on the byte-exact model:
 *
 *  - a settings WRITE (brightness) is answered by the firmware (`09-00`
 *    echoing the msgId — G2CC §3 row 15) and the transport sees it;
 *  - a write the firmware ate is released by the next sid-0x09 answer and
 *    RE-SENT once, so the session-start class of loss (the eaten
 *    re-sends, §34) no longer leaves the glasses on their own brightness
 *    for the whole session;
 *  - a write lost twice is a fault, never silence;
 *  - slow connection parameters (105 ms / latency 4, the 2026-09-12 case)
 *    flip the shell's regime AT ONCE, show in the status cell, raise one
 *    notice, and the recovery clears both;
 *  - the Global `Link` row pushes its choice to the transport at start and
 *    on change.
 */
class Review47Test {

    private class Rig(scope: CoroutineScope, val sim: GlassFirmwareSim = GlassFirmwareSim()) {
        val tmp = Files.createTempDirectory("damage-r47")
        val inner = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val transport = ParamTransport(inner, scope)
        val events = ArrayList<TransportEvent>()
        val collector = scope.launch {
            transport.events.collect { synchronized(events) { events.add(it) } }
        }
        val journalPath = tmp.resolve("journal.jsonl")
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), journalPath, scope) {
            Shell.LocalClock(12, 0, "12:00", "PM")
        }

        fun notes(kind: String): List<String> = if (!Files.exists(journalPath)) emptyList()
            else Files.readAllLines(journalPath).filter { it.contains("\"ev\":\"note\"") && it.contains("\"kind\":\"$kind\"") }
        fun faults(): List<TransportEvent.Fault> = synchronized(events) { events.filterIsInstance<TransportEvent.Fault>() }
        fun transportNotes(): List<TransportEvent.Note> = synchronized(events) { events.filterIsInstance<TransportEvent.Note>() }

        suspend fun settle(what: String) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 10_000) {
                if (shell.isQuiescent()) return
                delay(10)
            }
            throw AssertionError("$what: did not settle — ${shell.quiescenceReport()}")
        }

        suspend fun until(what: String, cond: () -> Boolean) {
            val t0 = System.currentTimeMillis()
            while (!cond() && System.currentTimeMillis() - t0 < 10_000) delay(10)
            assertTrue(cond(), what)
        }

        fun close() { collector.cancel(); tmp.toFile().deleteRecursively() }
    }

    /** The sim's transport with the connection parameters a test dictates
     *  laid over its state — the platform's callback, on the model. */
    private class ParamTransport(private val inner: Transport, scope: CoroutineScope) : Transport by inner {
        @Volatile private var intervalMs = 0.0
        @Volatile private var latency = 0
        @Volatile private var params = ""
        private val mine = MutableStateFlow(overlay(inner.state.value))
        override val state = mine
        val priorities = ArrayList<String>()

        init { scope.launch { inner.state.collect { mine.value = overlay(it) } } }

        private fun overlay(s: LinkState) = s.copy(linkIntervalMs = intervalMs, linkLatency = latency, linkParams = params)

        fun setParams(intervalMs: Double, latency: Int) {
            this.intervalMs = intervalMs; this.latency = latency
            params = "L %.2fms/%d/6000ms · R %.2fms/%d/6000ms".format(intervalMs, latency, intervalMs, latency)
            mine.value = overlay(inner.state.value)
        }

        override fun setLinkPriority(name: String) { synchronized(priorities) { priorities.add(name) }; inner.setLinkPriority(name) }
    }

    @Test
    fun aBrightnessWriteIsAnsweredAndAnEatenOneIsReSentOnce(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.sim.brightnessAuto = false
            r.sim.brightnessLevel = 7
            r.sim.eatSettingsWrites = 1          // the session-start push is eaten
            r.shell.start()
            r.settle("start")
            // the next sid-0x09 answer (the device-info poll) releases the eaten
            // write; the re-send lands and the firmware model holds the setting
            r.until("the re-sent brightness write reached the firmware model (auto=${r.sim.brightnessAuto} level=${r.sim.brightnessLevel})") {
                r.sim.brightnessAuto && r.sim.brightnessLevel == null
            }
            r.until("the transport noted the re-send") { r.transportNotes().any { "re-sent once" in it.detail && "brightness" in it.detail } }
            assertTrue(r.sim.settingsWritesAnswered >= 1, "the firmware model answered the re-send")
            assertTrue(r.faults().none { it.what == "settings" }, "one loss is a note, not a fault: ${r.faults()}")
            r.shell.stop()
        } finally {
            r.close(); scope.cancel()
        }
    }

    @Test
    fun aWriteLostTwiceIsAFault(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.sim.brightnessAuto = false
            r.sim.brightnessLevel = 7
            r.sim.eatSettingsWrites = 2          // the push AND its re-send
            r.shell.start()
            r.settle("start")
            r.until("the second loss surfaced as a fault: ${r.faults()}") {
                r.faults().any { it.what == "settings" && "lost twice" in it.detail }
            }
            assertFalse(r.sim.brightnessAuto, "nothing reached the model — both copies were eaten")
            r.shell.stop()
        } finally {
            r.close(); scope.cancel()
        }
    }

    @Test
    fun theAnsweredCaseIsQuiet(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.sim.brightnessAuto = false
            r.sim.brightnessLevel = 7
            r.shell.start()
            r.settle("start")
            r.until("the push reached the model") { r.sim.brightnessAuto }
            r.until("the model answered it") { r.sim.settingsWritesAnswered >= 1 }
            // a few device-info answers later nothing has been released as lost
            delay(300)
            assertTrue(r.transportNotes().none { "brightness" in it.detail }, "no loss noted: ${r.transportNotes().map { it.detail }}")
            assertTrue(r.faults().none { it.what == "settings" }, "no settings fault: ${r.faults()}")
            r.shell.stop()
        } finally {
            r.close(); scope.cancel()
        }
    }

    @Test
    fun slowParametersFlipTheRegimeAtOnceShowInTheStatusCellAndRaiseOneNotice(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start()
            r.settle("start")
            assertEquals("ok", r.shell.statusLine)
            r.transport.setParams(105.0, 4)
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)     // a flush re-evaluates the regime
            r.settle("notch under slow parameters")
            r.until("the status cell says LINK SLOW (is '${r.shell.statusLine}')") { r.shell.statusLine == "LINK SLOW" }
            assertTrue(r.notes("link").any { "SLOW regime" in it }, "the regime flipped without waiting for the EMA: ${r.notes("link")}")
            assertTrue(r.notes("link").any { "105.00ms/4" in it }, "the parameters were journaled: ${r.notes("link")}")
            // one more notch: no second notice, the episode is one
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_BOTTOM)
            r.settle("second notch")
            assertEquals("LINK SLOW", r.shell.statusLine)

            r.transport.setParams(15.0, 1)
            r.shell.postGesture(EvenHubMsg.EV_SCROLL_TOP)
            r.settle("notch under fast parameters")
            r.until("the status cell cleared (is '${r.shell.statusLine}')") { r.shell.statusLine == "ok" }
            assertTrue(r.notes("link").any { "fast regime" in it }, "the regime came back: ${r.notes("link")}")
            r.shell.stop()
        } finally {
            r.close(); scope.cancel()
        }
    }

    @Test
    fun theLinkRowPushesItsChoiceAtStartAndOnChange(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val r = Rig(scope)
        try {
            r.shell.start()
            r.settle("start")
            assertEquals(listOf("high"), synchronized(r.transport.priorities) { r.transport.priorities.toList() }, "the default rides the start")
            r.shell.updateSettings { it.copy(linkPriority = "balanced") }
            r.settle("setting")
            assertEquals(listOf("high", "balanced"), synchronized(r.transport.priorities) { r.transport.priorities.toList() })
            // a value outside the ladder clamps back to the default on load
            assertEquals("high", wm.damage.core.shell.ShellSettings.fromJson(
                wm.damage.core.shell.ShellSettings().copy(linkPriority = "turbo").toJson()).linkPriority)
            r.shell.stop()
        } finally {
            r.close(); scope.cancel()
        }
    }
}
