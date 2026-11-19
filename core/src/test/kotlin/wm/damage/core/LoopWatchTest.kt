package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.LoopTurns
import wm.damage.core.shell.LoopWatch
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.shell.ShellLoopStalled
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.util.UncaughtRecord

/** A handler that does not return until [release]: the frame a stuck turn's record must name. */
private fun heldInAHandler(inside: CountDownLatch, release: CountDownLatch) {
    inside.countDown()
    release.await()
}

/**
 * Build RA-1: the liveness supervisor of the shell's loop (`shell/LoopWatch.kt`). Context for the reader: Adam's own
 * window manager; a turn of the shell's loop that never ends is recorded and the process ends itself so that its
 * supervisor starts it again. Nothing here ends a process: the hook records. The measure and the watcher are driven
 * by a clock the test moves; one case runs a real shell whose handler does not return.
 */
class LoopWatchTest {
    private class Clock { @Volatile var now = 1_000L }

    /** A turn begun on a thread of its own that sits inside [heldInAHandler] until released. */
    private class Parked(turns: LoopTurns, msg: Any) {
        private val inside = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val thread = Thread({ turns.begin(msg); heldInAHandler(inside, release) }, "fake-loop-7").apply { isDaemon = true; start() }
        init { check(inside.await(10, TimeUnit.SECONDS)) { "the parked turn did not begin" } }
        fun release() { release.countDown(); thread.join(10_000) }
    }

    private class Watched(c: Clock) {
        val turns = LoopTurns { "type:$it" }.apply { clock = { c.now } }
        val records = java.util.concurrent.CopyOnWriteArrayList<Pair<Thread, Throwable>>()
        /** How many times the host's hook was called. */
        val ends = java.util.concurrent.atomic.AtomicInteger()
        val watch = LoopWatch(turns, { t, e -> records += t to e; true }, { ends.incrementAndGet() })
    }

    /** One turn open 30 s of running time: acted on at the look that reaches it, not before, once — the record holds
     *  the turn's thread, its frames and the message's type, and the hook is handed the same. */
    @Test
    fun aTurnPastTheThresholdIsRecordedAndEndedOnce() {
        val c = Clock()
        val w = Watched(c)
        val p = Parked(w.turns, "transport FlushDone")
        try {
            repeat(5) { c.now += LoopWatch.PERIOD_MS; assertFalse(w.watch.look(), "25 s of running time is not stuck") }
            c.now += LoopWatch.PERIOD_MS
            assertTrue(w.watch.look(), "30 s of running time is stuck")
            assertEquals(1, w.records.size)
            val (thread, error) = w.records[0]
            assertSame(p.thread, thread, "the record names the turn's thread")
            val e = error as ShellLoopStalled
            assertTrue(e.stackTrace.any { it.methodName == "heldInAHandler" }, "the frames are the turn's thread's: ${e.stackTrace.take(4)}")
            assertTrue("type:transport FlushDone" in e.message!!, e.message)
            assertTrue("fake-loop-7" in e.message!!, e.message)
            assertTrue("for ${LoopWatch.STUCK_MS / 1000} s of running time" in e.message!!, e.message)
            assertEquals(1, w.ends.get())
            repeat(20) { c.now += LoopWatch.PERIOD_MS; assertFalse(w.watch.look(), "acted on once") }
            assertEquals(1, w.records.size)
            assertEquals(1, w.ends.get())
        } finally {
            p.release()
        }
    }

    /** The review's finding 1: a log line that throws (a sink of the host's) never keeps the hook from being called —
     *  once; the throw still reaches the caller, and the watcher acts no more. */
    @Test
    fun aLogLineThatThrowsStillEndsTheProcess() {
        val c = Clock()
        val w = Watched(c)
        val p = Parked(w.turns, "Run held")
        val sink = wm.damage.core.util.Log.Sink { level, tag, _ ->
            if (tag == "watch" && level == wm.damage.core.util.Log.Level.ERROR) throw IllegalStateException("test: this sink throws")
        }
        wm.damage.core.util.Log.addSink(sink)
        try {
            repeat(5) { c.now += LoopWatch.PERIOD_MS; assertFalse(w.watch.look()) }
            c.now += LoopWatch.PERIOD_MS
            kotlin.test.assertFailsWith<IllegalStateException> { w.watch.look() }
            assertEquals(1, w.records.size, "the record came first")
            assertEquals(1, w.ends.get(), "the hook was called although the log line threw")
            repeat(10) { c.now += LoopWatch.PERIOD_MS; assertFalse(w.watch.look()) }
            assertEquals(1, w.ends.get())
        } finally {
            wm.damage.core.util.Log.removeSink(sink)
            p.release()
        }
    }

    /** After it has acted the watcher never acts again, on any turn (a test's hook returns; a host's does not). */
    @Test
    fun afterActingItNeverActsAgain() {
        val c = Clock()
        val w = Watched(c)
        val first = Parked(w.turns, "Run first")
        try {
            repeat(6) { c.now += LoopWatch.PERIOD_MS; w.watch.look() }
            assertTrue(w.watch.fired)
        } finally {
            first.release()
        }
        w.turns.end()
        val second = Parked(w.turns, "Run second")
        try {
            repeat(40) { c.now += LoopWatch.PERIOD_MS; assertFalse(w.watch.look(), "a second stuck turn is not acted on") }
        } finally {
            second.release()
        }
        assertEquals(1, w.ends.get())
        assertTrue("type:Run first" in w.records.single().second.message!!)
        assertEquals(1, w.records.size)
    }

    /** No open turn (the loop waiting for its next message) is never stuck, however long it waits. */
    @Test
    fun anIdleLoopNeverActs() {
        val c = Clock()
        val w = Watched(c)
        repeat(2_000) { c.now += LoopWatch.PERIOD_MS; assertFalse(w.watch.look()) }
        assertTrue(w.records.isEmpty() && w.ends.get() == 0)
    }

    /** Many short turns, a different one open at every look (each begun just under one period before it): their
     *  times are never summed. */
    @Test
    fun manyShortTurnsNeverAct() {
        val c = Clock()
        val w = Watched(c)
        repeat(2_000) { i ->
            w.turns.end()
            c.now += 100
            w.turns.begin("short $i")
            c.now += LoopWatch.PERIOD_MS - 100
            assertFalse(w.watch.look(), "look $i")
        }
        assertTrue(w.records.isEmpty() && w.ends.get() == 0)
    }

    /** A clock that does not move (a device asleep) credits nothing: an open turn is never stuck by it. */
    @Test
    fun aClockThatDoesNotAdvanceNeverActs() {
        val c = Clock()
        val w = Watched(c)
        val p = Parked(w.turns, "Run held")
        try {
            repeat(2_000) { assertFalse(w.watch.look()) }
        } finally {
            p.release()
        }
        assertTrue(w.records.isEmpty() && w.ends.get() == 0)
    }

    /** A gap between two looks far longer than the period is time the process did not run (frozen by the system, a
     *  debugger holding the whole VM): it credits two periods, and the turn must stay open for the rest of the
     *  threshold in running time. */
    @Test
    fun timeTheProcessDidNotRunIsNotCounted() {
        val c = Clock()
        val w = Watched(c)
        val p = Parked(w.turns, "Run held")
        try {
            c.now += LoopWatch.PERIOD_MS
            assertFalse(w.watch.look())                                            // 5 s
            c.now += 10 * 60_000L
            assertFalse(w.watch.look(), "ten minutes frozen count as two periods") // 15 s
            repeat(2) { c.now += LoopWatch.PERIOD_MS; assertFalse(w.watch.look()) } // 25 s
            c.now += LoopWatch.PERIOD_MS
            assertTrue(w.watch.look(), "30 s of running time")
            assertEquals(1, w.ends.get())
            assertTrue("for ${LoopWatch.STUCK_MS / 1000} s of running time" in w.records.single().second.message!!)
        } finally {
            p.release()
        }
    }

    /**
     * The shell itself: a settled shell on the simulator has no open turn between messages and is not acted on while
     * it runs, then a handler that does not return is acted on — the record is in the data directory, named as the
     * next start's pickup says it, with the handler's frame and the message's type, and the hook (which records here)
     * is called.
     */
    @Test
    fun aHandlerThatDoesNotReturnIsRecordedAndEnded(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-loopwatch")
        val release = CountDownLatch(1)
        var watch: LoopWatch? = null
        try {
            val t = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), t, Persistence(tmp.resolve("state.json")), tmp.resolve("journal.jsonl"), scope)
            val keeper = ShellKeeper(shell, t, scope, retryPauseMs = 100)
            keeper.start()
            waitUntil(10_000, 10, { "the shell drives the simulator: keeper ${keeper.state}" }) {
                keeper.state == ShellKeeper.State.RUNNING && t.state.value.started
            }
            val settled = CompletableDeferred<Unit>()
            shell.services.runOnShell { settled.complete(Unit) }
            waitUntil(10_000, 10, { "the loop runs a posted action" }) { settled.isCompleted }

            // every turn the loop takes ends: between messages no turn is open. Read directly — the idle tick posts a
            // message every second, which would hide a turn left open from a watcher with a threshold of a second;
            // a rest or Silent Mode posts none, and there such a turn would be acted on
            waitUntil(5_000, 1, { "an idle shell has no open turn: ${shell.turns.open()?.what()}" }) { shell.turns.open() == null }

            val ended = CompletableDeferred<Unit>()
            val write = UncaughtRecord.recorderFor(tmp, "test build")
            val wrote = CompletableDeferred<Pair<Thread, Boolean>>()
            val w = LoopWatch(shell.turns, { th, e -> write(th, e).also { wrote.complete(th to it) } }, { ended.complete(Unit) },
                stuckMs = 1_000, periodMs = 20)
            watch = w
            w.start()
            delay(1_500)
            assertFalse(w.fired, "a live shell's ordinary turns are not stuck")
            assertFalse(UncaughtRecord.isPending(tmp))

            val inside = CountDownLatch(1)
            shell.services.runOnShell { heldInAHandler(inside, release) }
            waitUntil(10_000, 10, { "the watcher acts on the held turn" }) { ended.isCompleted }
            val (stuckThread, recorded) = wrote.await()          // the record comes before the hook
            assertTrue(inside.count == 0L, "the handler was entered")
            assertTrue(recorded)
            assertTrue(UncaughtRecord.isPending(tmp), "the record is where the next start looks")
            val text = Files.readString(tmp.resolve(UncaughtRecord.PENDING))
            assertTrue(ShellLoopStalled::class.java.name in text && "heldInAHandler" in text && "Run runOnShell" in text, text)
            val line = assertNotNull(UncaughtRecord.takePending(tmp, tmp.resolve("journal-next.jsonl")))
            assertTrue("an uncaught ${ShellLoopStalled::class.java.name} in thread ${stuckThread.name}" in line, line)

            release.countDown()
            keeper.stop()
        } finally {
            release.countDown()
            watch?.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }
}
