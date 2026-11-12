package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.content.RemoteContent
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §72 (2026-11-08): what the shell holds while no session is up.
 *
 * On 2026-11-06 the PC standby's keeper retried a start that could not succeed every 2–4 s.
 * Each attempt ran every window's restore. The Reader's restore laid out the whole open book
 * and handed it to a message queue that only a running session drains, so the heap was gone
 * by attempt 845. Measured before the fix: 5.4 MB and 2 queued messages per failed start,
 * never released. The pins below are each watched to fail on the unfixed tree for the
 * reason they name, except where a pin says it guards behaviour that must NOT change.
 */
class QueueWhileDownTest {
    private val tmp: Path = Files.createTempDirectory("queue-while-down")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private fun queued(shell: Shell): Int =
        Regex("queued=(\\d+)").find(shell.quiescenceReport())?.groupValues?.get(1)?.toInt() ?: 0

    /** Until [shell]'s queue depth has held still for a second (bounded: a test wait only). */
    private suspend fun settledQueue(shell: Shell): Int {
        var last = -1
        var stillSince = System.currentTimeMillis()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            val q = queued(shell)
            if (q != last) { last = q; stillSince = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - stillSince >= 1_000) return q
            delay(50)
        }
        return queued(shell)
    }

    /** A transport whose start fails until [fail] is cleared — beardos's powered-off adapter. */
    private class Flaky(val sim: SimTransport) : Transport by sim {
        @Volatile var fail = true
        override suspend fun start(warmupFrame: ByteArray) {
            if (fail) throw java.io.IOException("adapter hci0 (C4:BD:E5:2E:C9:75) is not powered")
            sim.start(warmupFrame)
        }
    }

    private fun bookShelf(): Pair<LocalContent, String> {
        val books = tmp.resolve("books")
        Files.createDirectories(books)
        Files.writeString(books.resolve("big.txt"), buildString {
            appendLine("CHAPTER I"); appendLine()
            for (i in 1..4_000) { appendLine("Paragraph $i. The compositor batches damage into one flush per frame."); appendLine() }
        })
        val content = LocalContent(books)
        return content to content.library().single().id
    }

    @Test
    fun failedStartsHoldNothingAndTheBookStillOpensAfter(): Unit = runBlocking {
        val (content, id) = bookShelf()
        val store = Persistence(tmp.resolve("state.json"))
        store.put("window.reader", buildJsonObject { put("level", "BOOK"); put("bookId", id) })
        store.put("shell.state", buildJsonObject { put("current", "reader"); put("mode", "WINDOW") })
        store.save()
        val transport = Flaky(SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val reader = wm.damage.core.windows.reader.ReaderWindow(FakeText(), content, scope)
        shell.register(reader)
        repeat(20) {
            try { shell.start() } catch (e: Exception) { /* the keeper's failed start */ }
            delay(150)
        }
        assertEquals(0, settledQueue(shell),
            "twenty failed starts left messages queued behind a loop that never ran (the unfixed tree: two per start, " +
                "each restore holding a whole laid-out book)")

        // the glasses come back: the restore still lands — nothing a session needs was lost
        transport.fail = false
        shell.start()
        val t0 = System.currentTimeMillis()
        while (!reader.title().contains("p.") && System.currentTimeMillis() - t0 < 20_000) delay(50)
        assertTrue(reader.title().contains("p."), "the book did not reopen after the failed starts: '${reader.title()}'")
        shell.stop()
    }

    @Test
    fun transportEventsWhileDownAreDropped(): Unit = runBlocking {
        val store = Persistence(tmp.resolve("state.json"))
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val wears = java.util.concurrent.atomic.AtomicInteger()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            transport.events.collect { if (it is wm.damage.core.transport.TransportEvent.Wear) wears.incrementAndGet() }
        }
        val shell = Shell(FakeText(), transport, store, null, scope)
        shell.start()
        shell.stop()
        // what the stop itself leaves for the next session (a flush answered after the loop
        // ended is KEPT: it completes the in-flight bookkeeping) is the baseline
        val base = settledQueue(shell)
        // ring events with no session: a session that is gone, or that never came up
        repeat(200) { transport.injectInput(EvenHubMsg.EV_SCROLL_BOTTOM) }
        // 0.64: a wear report joins the class — it describes a session that is gone, and a late
        // "not worn" must not be waiting for the next one
        sim.setWearDetectionSwitch(true)
        sim.sensorReportsWear(true); sim.sensorReportsWear(false)
        waitUntil(10_000, message = { "the model's two reports reached the transport's events with no session running: ${wears.get()}" }) { wears.get() == 2 }
        // R2 (RG-9 b): a sync point, not a stillness window. A typed line is KEPT while down, and the event flow
        // keeps order per collector: once the shell's collector has queued the line, every ring event and wear
        // report emitted before it has been decided — so the queue holds the baseline and the line, nothing else
        transport.injectText("mark")
        waitUntil(10_000, message = { "ring events and wear reports with no session running piled up for the next one " +
            "(the unfixed tree: base + 200 + 2 + the line): queued=${queued(shell)}, base=$base" }) { queued(shell) == base + 1 }
    }

    /**
     * APK 0.64 (the P4 review's finding 9): a message that carries an answer for the case "the loop will
     * never run me" (`Msg.Run(dropped = …)`) gets that answer behind a Shutdown too. The loop told such a
     * message only when it reached a stopped loop BEFORE the Shutdown; whatever was queued behind the
     * Shutdown was drained in the loop's `finally` with no word — the dev probe `rest=…` taken in a
     * session's last instant left its `probe` note and nothing else.
     * The order is made: the loop is held inside one message, the stop's Shutdown is queued behind it (an
     * undispatched launch runs `stop()` to its wait, past the post), and the probe behind the Shutdown.
     * The journal is read once `stop()` has returned, which is after the drain since R2 (RH-6: the stop
     * joins the loop; before, the note normally won the race by a few thread hand-offs).
     * Mutation watched: the drain counts the leftovers and tells nobody (0.63's loop).
     */
    @Test
    fun aMessageQueuedBehindTheShutdownIsToldItWasDropped(): Unit = runBlocking {
        val journal = tmp.resolve("journal-drain.jsonl")
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-drain.json")), journal, scope)
        fun notes(kind: String) = (if (Files.exists(journal)) Files.readAllLines(journal) else emptyList())
            .filter { "\"ev\":\"note\",\"kind\":\"$kind\"" in it }.map { it.substringAfter("\"detail\":\"").removeSuffix("\"}") }
        shell.start()
        val inLoop = kotlinx.coroutines.CompletableDeferred<Unit>()
        val go = java.util.concurrent.CountDownLatch(1)
        shell.services.runOnShell { inLoop.complete(Unit); go.await() }      // the loop stays inside this message
        inLoop.await()
        val stopping = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { shell.stop() }
        // `running` is still true (the Shutdown is queued, not handled), so the probe is taken — behind it
        assertTrue(shell.devProbe("rest", "on"), "the probe is the shell's and a session is running")
        go.countDown()
        stopping.join()
        assertEquals(listOf("rest=on"), notes("probe"))
        assertEquals(listOf("rest=on not taken — the session ended"), notes("rest"),
            "the probe queued behind the Shutdown says what became of it")
        // (the queue's depth after the stop is not asserted: a flush answered once the loop has ended is
        // KEPT for the next session — `aFlushAnsweredWhileDownStillCompletesInTheNextSession` — and whether
        // one is in flight at this stop depends on the moment)
    }

    /**
     * R2 (RH-6): `stop()` returns only once the loop's drain has run. The Shutdown's arm completes `done` and
     * the loop still has its drain ahead; a stop that returned at `done` let the next start run beside it.
     * Held here at a known point of the drain: a low reading queued behind the Shutdown is judged by
     * `keepWhileDown` there, and the host's hook does not return until the test lets it — when it is called
     * from the loop (`Shell.loop` on the stack; a reading that arrives once the loop has ended is judged on the
     * collector's stack, and is not this pin's; the loop may resume on another worker thread). The instant model answers a device-info READ every 60 ms: two low readings seen by the
     * test's own subscriber before the loop is let go puts the shell's copy of one behind the Shutdown.
     * The stop cannot have returned while the hook is held — asserted after 300 ms in which, unjoined, it
     * returns (its remaining work is a transport stop and a journal close on the instant model).
     * Mutation watched: `stopLocked` without the join.
     */
    @Test
    fun aStopReturnsOnlyOnceTheLoopsDrainHasRun(): Unit = runBlocking {
        val sim = GlassFirmwareSim()
        val transport = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-join.json")), null, scope)
        val lowSeen = java.util.concurrent.atomic.AtomicInteger()
        val watch = scope.launch {
            transport.events.collect { if (it is wm.damage.core.transport.TransportEvent.Battery && it.glassesPct == 15) lowSeen.incrementAndGet() }
        }
        val judgedInLoop = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val inHook = java.util.concurrent.CountDownLatch(1)
        val hookGo = java.util.concurrent.CountDownLatch(1)
        shell.onGlassesBattery = { low, _ ->
            val inLoopFrame = Thread.currentThread().stackTrace.any { it.className == Shell::class.java.name && it.methodName == "loop" }
            if (low && judgedInLoop.compareAndSet(null, inLoopFrame)) {
                inHook.countDown()
                if (inLoopFrame) hookGo.await()
            }
            true
        }
        shell.start()
        val inLoop = kotlinx.coroutines.CompletableDeferred<Unit>()
        val go = java.util.concurrent.CountDownLatch(1)
        shell.services.runOnShell { inLoop.complete(Unit); go.await() }      // the loop stays inside this message
        inLoop.await()
        val stopping = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { shell.stop() }
        sim.batteryPct = 15                                                  // every reading from now on is behind the Shutdown
        waitUntil(10_000, message = { "two low readings reached the transport's events: ${lowSeen.get()}" }) { lowSeen.get() >= 2 }
        go.countDown()
        try {
            assertTrue(inHook.await(10, java.util.concurrent.TimeUnit.SECONDS), "the low reading was judged")
            assertTrue(judgedInLoop.get() == true, "the low reading was judged in the loop's drain, behind the Shutdown (not on the collector after it)")
            delay(300)
            assertTrue(!stopping.isCompleted, "stop() returned while the loop's drain was still running (its hook is held)")
        } finally { hookGo.countDown() }
        stopping.join()
        watch.cancel()
    }

    /**
     * R2 (RF-1): a shell built on a loaded store reads its settings at once, before any session. The phone's
     * error sink reads `Phone notifications` from the shell between its construction and the keeper's first
     * start; the field was the defaults until `startLocked` read the store, so a launch's first ERROR lines
     * (the previous process's uncaught record, a bind failure) were never raised on a phone that has the
     * switch on. The sink itself is in the phone module, which has no tests: that half is not pinned here.
     */
    @Test
    fun aShellReadsItsSettingsFromTheStoreBeforeAnySession() {
        val f = tmp.resolve("state-settings.json")
        Persistence(f).apply {
            put("shell.settings", wm.damage.core.shell.ShellSettings(phoneNotifications = true).toJson())
            assertTrue(save())
        }
        val store = Persistence(f).also { it.load() }
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), store, null, scope)
        assertTrue(shell.settings.phoneNotifications, "the settings of a shell not yet started are the store's, not the defaults")
    }

    /** A list window that takes a typed line, so a line has somewhere to go in the next session. */
    private class Typer(private val tx: wm.damage.core.text.TextRasterizer) :
        wm.damage.core.shell.DamageWindow("typer", "Typer", wm.damage.core.gfx.IconKind.FILES) {
        private val model = wm.damage.core.shell.ListModel()
        private val font = wm.damage.core.text.FontSpec(wm.damage.core.text.Face.SYSTEM, 20)
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun view(): wm.damage.core.shell.WindowView = wm.damage.core.shell.WindowView.ListView(model, { 4 },
            paintRow = { g, i, r, _ -> tx.draw(g, r.x + 40, r.y + 4, "Row $i", font, wm.damage.core.gfx.Level.BODY) },
            paintLens = { _, _, _ -> }, onCommit = {})
        override fun summary() = Summary("4 rows")
        override fun saveState(): kotlinx.serialization.json.JsonObject = buildJsonObject {}
        override fun restoreState(state: kotlinx.serialization.json.JsonObject) {}
        override fun onTypedText(line: String): Boolean { lines.add(line); return true }
    }

    /**
     * R2 (RH-N1): the loop's drain decides a transport event by `keepWhileDown`, as a post with no session
     * running does. A flush answered while the loop is held behind the Shutdown, and a line typed then, were
     * taken by the drain and counted: the flush's in-flight entry stayed for the life of the process (no
     * quiescence, no mirror check, nothing said) and the line was gone. Both reach the next session now.
     *
     * The typed line is behind the Shutdown by construction (it is typed after the stop has posted it). The
     * flush's answer is behind it unless it lands in the few milliseconds between the held message's start
     * and the Shutdown's post — 1 s of modeled ack makes that a miss in the safe direction only (a pass).
     * Mutation watched: the drain counts a transport event as unhandled (the 0.63 / P4 drain).
     */
    @Test
    fun aFlushAnswerAndATypedLineQueuedBehindTheShutdownReachTheNextSession(): Unit = runBlocking {
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(ackMs = 1_000))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state-behind.json")), null, scope)
        val typer = Typer(FakeText())
        shell.register(typer)
        shell.start()
        // the typer is focused, so the next session restores it and a typed line has a taker there
        shell.services.runOnShell { shell.services.openWindow("typer", null) }
        waitUntil(10_000, message = { "the typer is focused and a flush is in flight: ${shell.currentWindowId()} ${shell.quiescenceReport()}" }) {
            shell.currentWindowId() == "typer" && "inflight=" in shell.quiescenceReport()
        }
        val inLoop = kotlinx.coroutines.CompletableDeferred<Unit>()
        val go = java.util.concurrent.CountDownLatch(1)
        shell.services.runOnShell { inLoop.complete(Unit); go.await() }      // the loop stays inside this message
        inLoop.await()
        val held = shell.quiescenceReport()
        assertTrue("inflight=" in held, "a flush is in flight while the loop is held: $held")
        val stopping = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { shell.stop(stopTransport = false) }
        transport.injectText("behind the shutdown")                          // the Shutdown is queued: this is behind it
        // the held message, the Shutdown, the typed line and the flush's answer
        waitUntil(10_000, message = { "the flush's answer and the line queued behind the Shutdown: ${shell.quiescenceReport()}" }) { queued(shell) >= 4 }
        go.countDown()
        stopping.join()
        shell.start()
        waitUntil(30_000, message = { "the next session settles — the flush answered behind the Shutdown completes in it: ${shell.quiescenceReport()}" }) {
            shell.isQuiescent()
        }
        waitUntil(10_000, message = { "the line typed behind the Shutdown is handled by the next session: ${typer.lines}" }) {
            typer.lines.toList() == listOf("behind the shutdown")
        }
        shell.stop()
    }

    /** The phone's shelf with the PC unreachable (2026-11-07: blank for 130 s while Android gave
     *  up on the connect). A host that accepts and never answers holds the old path forever. */
    @Test
    fun theShelfAnswersFromItsCacheWhileThePcDoesNot() {
        val (_, _) = bookShelf()
        val cache = tmp.resolve("bookcache")
        val port = ServerSocket(0).use { it.localPort }
        val host = ContentHostServer(LocalContent(tmp.resolve("books")), port, "tok")
        host.start()
        try {
            val first = RemoteContent("127.0.0.1", port, "tok", cache).library()
            assertEquals(1, first.size, "the first listing comes from the host and is cached")
        } finally { host.close() }
        // a "PC" that takes the connection and never says a word
        ServerSocket(0).use { mute ->
            val rc = RemoteContent("127.0.0.1", mute.localPort, "tok", cache)
            var got: List<wm.damage.core.content.BookMeta>? = null
            val t = Thread { got = rc.library() }.apply { isDaemon = true; start() }
            t.join(5_000)
            assertTrue(!t.isAlive, "the shelf waited on a PC that does not answer instead of reading its cache")
            assertEquals(1, got?.size, "the cached listing")
        }
    }

    /** The second review's finding: a flush still in flight when a session stops is answered while
     *  no session runs. That answer must still reach the next session, or its in-flight bookkeeping
     *  never empties: no quiescence, no divergence check, the atlas and staging uploads stuck.
     *  (It guards §72's own first draft, which dropped every transport event while down.) */
    @Test
    fun aFlushAnsweredWhileDownStillCompletesInTheNextSession(): Unit = runBlocking {
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(ackMs = 1_500))
        val shell = Shell(FakeText(), transport, store, null, scope)
        shell.start()
        delay(200)                                  // the session's first flushes are on their way
        assertTrue(shell.quiescenceReport().contains("inflight="), "a flush is in flight at the stop: ${shell.quiescenceReport()}")
        shell.stop(stopTransport = false)           // the yield form: the acks land with no session running
        delay(2_500)
        shell.start()
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 30_000) delay(50)
        assertTrue(shell.isQuiescent(), "the next session never settled: ${shell.quiescenceReport()}")
        shell.stop()
    }
}
