package wm.damage.core.transport

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.util.Log
import wm.damage.core.wire.EvenHubMsg

/**
 * The driver arbitration (HANDOFF.md §8.1 decision 3/5, §8.2 "Arbitration"):
 * a [Transport] that reaches the glasses by whichever PATH works, in priority
 * order, and keeps trying every path until one does.
 *
 * `start()` runs every candidate's `start()` concurrently — the phone's
 * transport over the seam first, PC-direct BLE after a short head start — and
 * the first to complete wins; the others are cancelled (a cancelled attempt
 * clears its session and disconnects). A candidate whose attempt fails is
 * tried again after a pause that grows to [maxRetryMs], for as long as the
 * race is undecided: an unreachable phone is re-probed while the radio keeps
 * scanning, so the moment a better path becomes available it is taken. A
 * candidate refused at the capability gate (not the CFW) is DISABLED for the
 * process lifetime; when every candidate is disabled `start()` fails and the
 * session keeper goes terminal.
 *
 * Once a path drives, it is held until its link ends (no proactive handover:
 * that would blank the display and can fail). The link end propagates and the
 * keeper starts a new race. Events, state and the mirror delegate to the
 * active path; [mirror] is one stable object whose listeners survive a switch.
 */
class PathTransport(
    private val candidates: List<Candidate>,
    private val scope: CoroutineScope,
    private val headStartMs: Long = 1_500,
    private val retryMs: Long = 2_000,
    private val maxRetryMs: Long = 30_000,
) : Transport {

    class Candidate(val name: String, val transport: Transport)

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1024)
    override val events = _events.asSharedFlow()
    private val _state = MutableStateFlow(LinkState(transportName = "auto"))
    override val state = _state.asStateFlow()
    override val mirror: LensPanels get() = proxy

    @Volatile private var active: Candidate? = null
    private var forward: Job? = null
    val disabled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Which path drives now, for hosts' status lines; null while searching. */
    val activeName: String? get() = active?.name

    override fun injectInput(type: Int) {
        if (!_events.tryEmit(TransportEvent.Input(type, EvenHubMsg.SRC_RING)))
            Log.e("path", "input event buffer overflow — a gesture was DROPPED")
    }

    private fun emit(ev: TransportEvent) {
        if (!_events.tryEmit(ev)) Log.e("path", "event DROPPED (buffer full): $ev")
    }

    override suspend fun start(warmupFrame: ByteArray) {
        check(active == null) { "path transport already started" }
        val live = candidates.filter { it.name !in disabled }
        if (live.isEmpty()) throw LintError("every transport path is disabled (${disabled.joinToString()}) — nothing left to try")
        updateState { it.copy(connected = false, started = false, leaseHeld = false, transportName = "auto: searching") }
        val winner = coroutineScope {
            val result = CompletableDeferred<Candidate>()
            val attempts = live.mapIndexed { i, c ->
                launch {
                    var pause = retryMs
                    if (i > 0) delay(headStartMs * i)            // priority = a head start
                    while (!result.isCompleted) {
                        try {
                            Log.i("path", "trying ${c.name}")
                            c.transport.start(warmupFrame)
                            if (!result.complete(c)) {
                                // another path finished first: give this one back
                                try { c.transport.stop() } catch (e: Exception) { Log.w("path", "${c.name} stop after losing: ${e.message}") }
                            }
                            return@launch
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val msg = e.message ?: e.toString()
                            emit(TransportEvent.Fault("path", "${c.name}: $msg"))
                            if (msg.contains("capability gate")) {
                                disabled.add(c.name)
                                Log.e("path", "${c.name} refused at the capability gate — disabled for this run")
                                return@launch
                            }
                            Log.w("path", "${c.name} did not start ($msg) — again in ${pause / 1000} s")
                        }
                        if (result.isCompleted) return@launch
                        delay(pause)
                        pause = minOf(maxRetryMs, pause * 2)
                    }
                }
            }
            // every path gave up for good (all disabled) → the race cannot end
            launch {
                attempts.forEach { it.join() }
                if (!result.isCompleted)
                    result.completeExceptionally(LintError("no transport path could start — disabled: ${disabled.joinToString()}"))
            }
            try {
                result.await()
            } finally {
                attempts.forEach { it.cancel() }
            }
        }
        active = winner
        Log.i("path", "driving via ${winner.name}")
        forward = scope.launch {
            launch { winner.transport.events.collect { emit(it) } }
            launch { winner.transport.state.collect { st -> updateState { st } } }
        }
        proxy.attach(winner.transport.mirror)
        updateState { winner.transport.state.value }
    }

    override suspend fun submit(flush: FlushRequest): Long =
        (active ?: throw IllegalStateException("no path is driving")).transport.submit(flush)

    override suspend fun clearDiagFlags() {
        active?.transport?.clearDiagFlags()
    }

    override suspend fun stop() {
        val a = active
        active = null
        forward?.cancel()
        forward = null
        proxy.attach(null)
        if (a != null) {
            try { a.transport.stop() } catch (e: Exception) { Log.w("path", "${a.name} stop: ${e.message}") }
        }
        updateState { it.copy(connected = false, started = false, leaseHeld = false, transportName = "auto") }
        emit(TransportEvent.Link(false, "auto: stopped"))
    }

    private val stateLock = Any()
    private fun updateState(f: (LinkState) -> LinkState) {
        synchronized(stateLock) { _state.value = f(_state.value) }
    }

    /** One stable mirror whose listeners survive a path switch. */
    private val proxy = object : LensPanels {
        private val blank = ByteArray(((Geometry.PANEL_W + 1) / 2) * Geometry.PANEL_H)
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<LensPanels.LensListener>()
        @Volatile private var inner: LensPanels? = null
        private val relay = LensPanels.LensListener { arm -> for (l in listeners) l.panelChanged(arm) }

        fun attach(p: LensPanels?) {
            inner?.removeListener(relay)
            inner = p
            p?.addListener(relay)
            for (arm in Arm.entries) for (l in listeners) l.panelChanged(arm)
        }

        override val exact: Boolean get() = inner?.exact ?: false
        override val stride: Int get() = inner?.stride ?: ((Geometry.PANEL_W + 1) / 2)
        override fun panel(arm: Arm): ByteArray = inner?.panel(arm) ?: blank
        override fun addListener(l: LensPanels.LensListener) { listeners.add(l) }
        override fun removeListener(l: LensPanels.LensListener) { listeners.remove(l) }
    }
}
