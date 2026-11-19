package wm.damage.core.shell

import wm.damage.core.util.Log

/**
 * Build RA-1 (`REPLAN.md` §17.4 item R-a; `HANDOFF.md` §92.9): the liveness supervisor of the shell's loop.
 *
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC. The shell's loop is
 * one coroutine that takes one message at a time ([Shell]'s `loop`). A handler that never returns stops input and
 * drawing for good — the glasses keep their last frame — and before this build only a restart by hand cleared it
 * (§92.9: an unbounded loop of that kind was found in the compositor's hold loop; fixed, but the class had no net).
 *
 * [LoopTurns] is the measure: the loop marks the start and the end of each turn (one message: its handler and the
 * pump after it). [LoopWatch] is the supervisor on a thread of its own: when ONE turn has been open past [STUCK_MS]
 * of running time it records where the loop is (the turn's thread's frames and the message's type) through the
 * uncaught-error record (`UncaughtRecord.recordOnly`: the same file, the same pickup at the next start, so the next
 * process raises it as a `process` fault and steps a stored C1 ladder stage down exactly as after an uncaught
 * error), says it in the log, and calls the host's [end], which ends the process so the host's supervisor starts it
 * again (Android's restart of the sticky foreground service on the phone; OpenRC's supervise-daemon on the PC).
 *
 * This is the "supervise externally" of `CLAUDE.md`'s first absolute rule: nothing on the radio, render or input path
 * gains a time bound; a loop waiting for its next message has no open turn and is never stuck.
 */
class LoopTurns(private val describe: (Any) -> String) {
    /** The clock both sides read, in ms: monotonic, and not advancing while the device sleeps. The default is the
     *  JVM's `System.nanoTime` (CLOCK_MONOTONIC on Linux, which does not count a suspend); the phone sets
     *  `SystemClock.uptimeMillis` (documented as not counting deep sleep). Set before the loop runs; a test drives it. */
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** One open turn: its number, when it began on [clock], the thread that began it and the message. */
    class Turn internal constructor(val seq: Long, val since: Long, val thread: Thread,
                                    private val msg: Any, private val describe: (Any) -> String) {
        /** The message's type ("transport FlushDone", "Run <tag>", "IdleTick"); built only when a turn is stuck. */
        fun what(): String = try { describe(msg) } catch (e: Exception) { "${msg.javaClass.simpleName} (its description failed: $e)" }
    }

    @Volatile private var current: Turn? = null
    /** Written by the loop only (one coroutine; a resumption on another thread is ordered after the suspension). */
    private var seq = 0L

    /** The loop takes [msg]: a turn begins on this thread. One read of [clock] and one small object per message. */
    fun begin(msg: Any) { current = Turn(++seq, clock(), Thread.currentThread(), msg, describe) }

    /** The turn is over: the loop is idle until its next message. */
    fun end() { current = null }

    /** The turn open now, or null when the loop is idle (or not running). */
    fun open(): Turn? = current
}

/** What a stuck turn's record carries, as an error class of its own: never thrown, only recorded. Its frames are the
 *  turn's thread's at the moment the watcher looked. */
class ShellLoopStalled(message: String) : Exception(message)

/**
 * The watcher. [record] is `UncaughtRecord::recordOnly` in the hosts (true when the record was written); [end] is the
 * host's hook that ends the process, and a test's that records. A stuck turn is acted on ONCE: after [end] returns
 * (a test's hook) the watcher stops. [end] is called whatever the record and the log lines did (a `finally`).
 *
 * What survives the end: the record (written and synced before [end]). On the phone the log lines may not — the day
 * log's writer thread may not have written them, and the error notice, with `Phone notifications` off, is a post into
 * the stuck shell — so there the record is the one durable channel.
 *
 * **Running time, not clock time.** Each look credits the open turn with the time since the last look, but never
 * more than [MAX_CREDIT_PERIODS] periods: a look that comes much later than its period means this process (or this
 * thread) did not run meanwhile, and that gap is not counted. So a process frozen by the system, the whole VM held
 * by a debugger ("suspend all") or a device asleep does not fire it when it runs again; the turn must stay open for
 * [STUCK_MS] of time the process ran. Read in AOSP main (not on his phone's build, which is not readable here):
 * the cached-apps freezer freezes a process only at an oom adj of `FREEZER_CUTOFF_ADJ` = `CACHED_APP_MIN_ADJ` (900)
 * or more (`OomAdjuster.getFreezePolicy`, `ProcessList`), and a process with a foreground service is held at
 * `PERCEPTIBLE_APP_ADJ` (200) or better (`OomAdjuster`, "fg-service") — so the phone's shell, which runs in its
 * foreground service, is not frozen by it; this rule covers it all the same. Not covered: a debugger that suspends
 * the loop's thread ALONE for longer than [STUCK_MS] (no adb on his phone; the PC's service is not run under one).
 *
 * The thread: a plain daemon thread, not a coroutine (the loop's dispatcher can be the very thing that is stuck).
 * Its sleep is pacing; no wait on any path is bounded by it.
 */
class LoopWatch(
    private val turns: LoopTurns,
    private val record: (Thread, Throwable) -> Boolean,
    private val end: () -> Unit,
    private val stuckMs: Long = STUCK_MS,
    private val periodMs: Long = PERIOD_MS,
) {
    // The watcher's own state: read and written by the thread that calls [look] only.
    private var lastLook = turns.clock()
    private var seen = 0L
    private var credited = 0L

    /** Set once, when a stuck turn has been acted on; the watcher does nothing after it. */
    @Volatile var fired = false
        private set
    @Volatile private var closed = false
    private var thread: Thread? = null

    /** Starts the watcher's thread (once). */
    @Synchronized fun start() {
        check(thread == null) { "the loop watcher is already started" }
        lastLook = turns.clock()
        thread = Thread({ run() }, "damage-loop-watch").apply { isDaemon = true; start() }
    }

    /** Stops the watcher (the stack it watches has stopped). Called after the shell's stop returned: a stop that
     *  waits on a stuck loop is itself watched until then. */
    fun close() {
        closed = true
        synchronized(this) { thread }?.interrupt()
    }

    private fun run() {
        while (!closed && !fired) {
            try {
                Thread.sleep(periodMs)
            } catch (e: InterruptedException) {
                return                      // only close() interrupts this thread
            }
            if (closed) return
            try {
                look()
            } catch (e: Exception) {
                // the watcher must not end with the process left unwatched: said, and the next look comes
                Log.e("watch", "the loop watcher's look failed: it keeps watching", e)
            }
        }
    }

    /** One look at the loop; true when it acted on a stuck turn. Called by the watcher's thread (and by a test). */
    internal fun look(): Boolean {
        if (fired) return false
        val now = turns.clock()
        val awake = (now - lastLook).coerceIn(0L, MAX_CREDIT_PERIODS * periodMs)
        lastLook = now
        val t = turns.open() ?: return false            // idle: the next turn has a new number, which resets below
        if (t.seq != seen) {
            // a turn not seen before: credited with its own age, but no more than this look's running time
            seen = t.seq
            credited = (now - t.since).coerceIn(0L, awake)
        } else credited += awake
        if (credited < stuckMs) return false
        fired = true
        act(t, now)
        return true
    }

    /** The record first, then the log lines; [end] in a `finally` (the review's finding 1: a log sink that threw
     *  skipped it, and the watcher stopped with the loop still stuck). */
    private fun act(t: LoopTurns.Turn, now: Long) {
        try {
            val what = t.what()
            val frames = t.thread.stackTrace
            val inShell = frames.any { it.className == SHELL_CLASS || it.className.startsWith("$SHELL_CLASS\$") }
            val text = "the shell's loop has not finished one turn for ${credited / 1000} s of running time " +
                "(${(now - t.since) / 1000} s on the clock): it is handling $what on thread ${t.thread.name} (${t.thread.state}); " +
                (if (inShell) "the frames are that thread's" else "that thread is not in the shell's code now (the turn " +
                    "is suspended or moved threads): the frames are that thread's, not the turn's") +
                " - the process ends itself so that it is started again"
            val error = ShellLoopStalled(text).apply { stackTrace = frames }
            val recorded = try { record(t.thread, error) } catch (e: Exception) {
                Log.e("watch", "the stuck loop's record was not written", e)
                false
            }
            Log.e("watch", text + if (recorded) " (the record is kept for the next start)"
                else " (NO record was written: the next start will not say it, and on the phone this line may not outlive the end)")
            Log.w("watch", "the loop thread's frames:\n" + frames.joinToString("\n") { "\tat $it" })
        } finally {
            end()                           // whatever the record and the log lines did
        }
    }

    companion object {
        /**
         * One turn open this long, in running time, is stuck. The evidence (phone, measured): over 12,952 `loop`
         * notes of 2026-11-12 to 11-18 the longest handler held the loop 1,241.5 ms and the longest pump 323.6 ms;
         * the `slow` notes of the pulled journals reach 720 ms, and the cited 484 ms (§92.1) and 365 ms (§91.7). A
         * session start is not a turn (it runs on the keeper's coroutine), nor is a staging (it is paced across
         * pumps). The one bounded wait known inside a turn is the PC's `--transport remote` dev path: a flush's
         * write parks the pump until the seam-quiet decision, `SEAM_QUIET_MS` (20 s) plus its 2 s pacing. 30 s is
         * above that and about 19 times the longest turn measured; what it costs is up to 30 s (plus one period)
         * of a frozen picture before the restart, against a stall that lasted until he restarted the app by hand.
         */
        const val STUCK_MS = 30_000L
        /** How often the watcher looks: a stall is acted on between [STUCK_MS] and one period after it. */
        const val PERIOD_MS = 5_000L
        /** A look credits at most this many periods: a longer gap is time the process did not run. */
        const val MAX_CREDIT_PERIODS = 2L
        /** The status the PC's hook ends the JVM with (any status is restarted; supervise-daemon only logs it). */
        const val EXIT_STATUS = 70
        /** The loop's class: a frame of it or of one of its inner classes (its coroutine bodies) is the shell's. */
        private const val SHELL_CLASS = "wm.damage.core.shell.Shell"
    }
}
