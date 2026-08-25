package wm.damage.core.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wm.damage.core.transport.CapabilityRefused
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.util.Log

/**
 * The one reconnect loop every host uses (HANDOFF.md §8.2 "Session keeper"):
 * keeps a [Shell] driving its [Transport] for as long as the process lives.
 *
 *  - [start] runs `shell.start()`; when the link ends (the transport's
 *    `started` goes false — polled, 250 ms pacing) or a start fails, the shell
 *    is stopped (state saved) and started again after a short pause, forever.
 *    The pause is pacing between attempts, not a timeout: each attempt waits
 *    as long as the link takes (a scan has no deadline). The loop narrates the
 *    link end itself, with the reason the last `Link(false)` event carried.
 *  - A **capability refusal** is terminal for this transport: the firmware
 *    answered and is not the CFW, which no retry can change. The loop stops,
 *    [onTerminal] fires, and the host decides what to fall back to.
 *  - [pause]/[resume] hand the transport to another driver (a PC takeover on
 *    the phone) and take it back.
 *  - Every transition reaches [onStatus] so the host's status line and the
 *    phone notification can narrate it.
 *
 * Serialised on one mutex: a pause arriving while an attempt is in flight
 * waits for the attempt's stop, so two drivers never overlap.
 */
class ShellKeeper(
    private val shell: Shell,
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit = {},
    private val onTerminal: (String) -> Unit = {},
    /** Pause between attempts, ms — pacing, not a timeout. */
    private val retryPauseMs: Long = 2_000,
) {
    enum class State { STOPPED, STARTING, RUNNING, WAITING, PAUSED, TERMINAL }

    @Volatile var state: State = State.STOPPED
        private set
    @Volatile var attempts: Int = 0
        private set
    @Volatile var lastReason: String = ""
        private set

    private val lock = Mutex()
    @Volatile private var loop: Job? = null
    private var watcher: Job? = null
    @Volatile private var wanted = false          // the host wants the shell driving
    @Volatile private var paused = false
    @Volatile private var capabilityRefused = false
    /** The reason of the last `Link(false)` seen; the loop narrates it when
     *  its poll sees the session end. */
    @Volatile private var lastLinkEnd: String? = null

    private fun status(s: String) {
        lastReason = s
        Log.i("keeper", s)
        onStatus(s)
    }

    /** Begin keeping the shell up. Returns immediately; the loop runs on [scope]. */
    fun start() {
        wanted = true
        paused = false
        if (watcher == null) {
            watcher = scope.launch {
                transport.events.collect { ev ->
                    when (ev) {
                        // recorded only: the running loop polls the transport's
                        // `started`, restarts on its own and narrates the end
                        // from there (round 2, a2-3 — an event count could not
                        // tell a self-stop from a loss; round 4, R4-1 — a gate
                        // on this watcher's timing could miss a genuine end)
                        is TransportEvent.Link -> if (!ev.connected) lastLinkEnd = ev.detail
                        is TransportEvent.Fault -> if (ev.what == "capability") {
                            capabilityRefused = true
                        }
                        else -> {}
                    }
                }
            }
        }
        kick()
    }

    /** The keeper's own stop of the shell: never interrupted midway (a
     *  cancelled stop would leave the shell stopped and the transport started
     *  for good — round 1, d5). Never narrated as a link end: the loop
     *  narrates only when its own poll saw the session end. */
    private suspend fun stopShell(why: String) {
        withContext(NonCancellable) {
            try { shell.stop() } catch (e: Exception) { Log.w("keeper", "$why: ${e.message}") }
        }
    }

    /** Stop keeping the shell up and stop the shell. */
    suspend fun stop() {
        wanted = false
        loop?.cancel()
        loop = null
        lock.withLock {
            state = State.STOPPED
            stopShell("stop")
        }
        status("stopped")
    }

    /** Another driver takes the transport: stop the shell and hold off until [resume]. */
    suspend fun pause(reason: String) {
        paused = true
        loop?.cancel()
        loop = null
        lock.withLock {
            state = State.PAUSED
            stopShell("pause")
        }
        status("paused: $reason")
    }

    fun resume() {
        if (!wanted) return
        synchronized(this) {
            paused = false
            status("resuming")
            kick()
        }
    }

    /** Ensure exactly one loop runs — serialised: a resume and the watcher's
     *  kick can arrive together (round 1, b4). */
    @Synchronized
    private fun kick() {
        if (!wanted || paused) return
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    private suspend fun run() {
        while (scope.isActive && wanted && !paused) {
            if (capabilityRefused) {
                state = State.TERMINAL
                status("this firmware refused the display: not the CFW — no retry can change that")
                onTerminal(lastReason)
                return
            }
            attempts++
            state = State.STARTING
            lastLinkEnd = null            // this attempt's end, not an earlier one's
            status(if (attempts == 1) "starting" else "reconnecting (attempt $attempts)")
            val ok = lock.withLock {
                try {
                    shell.start()
                    true
                } catch (e: CancellationException) {
                    throw e             // a pause/stop: unwind, no narration of a failure
                } catch (e: CapabilityRefused) {
                    // the firmware answered and is not the CFW: no retry can change that
                    capabilityRefused = true
                    status("start refused: ${e.message}")
                    false
                } catch (e: Exception) {
                    status("start failed: ${e.message}")
                    false
                }
            }
            if (ok) {
                state = State.RUNNING
                status("driving via ${transport.state.value.transportName}")
                // stay here until the link ends: the transport's `started`
                // goes false on a link loss and stays false until this loop
                // starts it again. (Over the seam a forwarded far-end state
                // could in theory flip it for the length of one message —
                // round 3, a3-8; a restart from that is a plain reconnect.)
                while (scope.isActive && wanted && !paused && transport.state.value.started) delay(250)
                if (!wanted || paused) return
                // the session ended under a driving shell: say so HERE, at the
                // decision, with the reason the event carried (the watcher
                // usually has it already; one yield lets it catch up)
                if (lastLinkEnd == null) yield()
                status("link ended: ${lastLinkEnd ?: "(no reason reported yet)"}")
                lock.withLock {
                    state = State.WAITING
                    stopShell("stop after link end")
                }
            }
            if (capabilityRefused) continue
            state = State.WAITING
            status("retrying in ${retryPauseMs / 1000} s")
            delay(retryPauseMs)
        }
    }
}
