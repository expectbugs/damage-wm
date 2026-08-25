package wm.damage.core.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wm.damage.core.transport.Transport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.util.Log

/**
 * The one reconnect loop every host uses (HANDOFF.md §8.2 "Session keeper"):
 * keeps a [Shell] driving its [Transport] for as long as the process lives.
 *
 *  - [start] runs `shell.start()`; when the link ends (`Link(false)`) or a
 *    start fails, the shell is stopped (state saved) and started again after a
 *    short pause, forever. The pause is pacing between attempts, not a
 *    timeout: each attempt waits as long as the link takes (a scan has no
 *    deadline).
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
    private var loop: Job? = null
    private var watcher: Job? = null
    @Volatile private var wanted = false          // the host wants the shell driving
    @Volatile private var paused = false
    @Volatile private var capabilityRefused = false
    /** Bumped on every link end so the loop restarts exactly once per loss. */
    @Volatile private var linkLosses = 0

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
                        is TransportEvent.Link -> if (!ev.connected && !paused) {
                            linkLosses++
                            status("link ended: ${ev.detail}")
                            kick()
                        }
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

    /** Stop keeping the shell up and stop the shell. */
    suspend fun stop() {
        wanted = false
        loop?.cancel()
        loop = null
        lock.withLock {
            state = State.STOPPED
            try { shell.stop() } catch (e: Exception) { Log.w("keeper", "stop: ${e.message}") }
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
            try { shell.stop() } catch (e: Exception) { Log.w("keeper", "pause stop: ${e.message}") }
        }
        status("paused: $reason")
    }

    fun resume() {
        if (!wanted) return
        paused = false
        status("resuming")
        kick()
    }

    /** Ensure exactly one loop runs. */
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
            val seen = linkLosses
            state = State.STARTING
            status(if (attempts == 1) "starting" else "reconnecting (attempt $attempts)")
            val ok = lock.withLock {
                try {
                    shell.start()
                    true
                } catch (e: Exception) {
                    // a refusal is not a link problem: the message names the gate
                    if (e.message?.contains("capability gate") == true) capabilityRefused = true
                    status("start failed: ${e.message}")
                    false
                }
            }
            if (ok) {
                state = State.RUNNING
                status("driving via ${transport.state.value.transportName}")
                // stay here until the link ends (the watcher bumps linkLosses)
                while (scope.isActive && wanted && !paused && linkLosses == seen &&
                    transport.state.value.started) delay(250)
                if (!wanted || paused) return
                lock.withLock {
                    state = State.WAITING
                    try { shell.stop() } catch (e: Exception) { Log.w("keeper", "stop after link end: ${e.message}") }
                }
            }
            if (capabilityRefused) continue
            state = State.WAITING
            status("retrying in ${retryPauseMs / 1000} s")
            delay(retryPauseMs)
        }
    }
}
