package wm.damage.core.transport

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wm.damage.core.geom.Geometry
import wm.damage.core.util.Log
import wm.damage.core.wire.EvenHubMsg

/**
 * The driver arbitration (HANDOFF.md §8.1 decision 3/5, §8.2 "Arbitration"):
 * a [Transport] that reaches the glasses by whichever PATH works, in priority
 * order, and keeps trying every path until one does.
 *
 * `start()` runs the candidates' `start()` concurrently, in rank order:
 *  - the highest-ranked path (the phone's transport over the seam) tries at
 *    once; a lower-ranked path (PC-direct BLE) waits while any higher-ranked
 *    attempt is ENGAGED — has committed its far end (the seam's grant: the
 *    phone has yielded its shell and is reconnecting the glasses for us) —
 *    and otherwise only gives it a short head start. So when the phone is
 *    reachable the phone path wins by construction, not by speed; when it is
 *    not, the radio takes over within seconds (review round 1, d1).
 *  - the first to complete wins; the others are cancelled (a cancelled
 *    attempt rolls back and disconnects; a cancelled engaged seam attempt
 *    closes its socket, and the phone resumes its own shell).
 *  - a candidate whose attempt fails is tried again after a pause that grows
 *    to [maxRetryMs], for as long as the race is undecided: an unreachable
 *    phone is re-probed while the radio scans, so the moment a better path
 *    becomes available it is taken.
 *  - a candidate refused at the capability gate ([CapabilityRefused]: not the
 *    CFW) is DISABLED for the process lifetime; when every candidate is
 *    disabled `start()` fails with the same refusal and the session keeper
 *    goes terminal.
 *
 * Once a path drives, it is held until its link ends (no proactive handover:
 * that would blank the display and can fail). The link end propagates and the
 * keeper starts a new race. Events, state and the mirror delegate to the
 * active path; [mirror] is one stable object whose listeners survive a switch.
 *
 * Known limit (documented, not hidden): a phone that is reachable but cannot
 * itself see the glasses holds the seam attempt engaged and the radio off —
 * the status line says which path is waiting on what.
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
        if (live.isEmpty())
            throw CapabilityRefused("every transport path was refused at the capability gate (${disabled.joinToString()}) — nothing left to try")
        updateState { it.copy(connected = false, started = false, leaseHeld = false, transportName = "auto: searching") }
        val winner = coroutineScope {
            val result = CompletableDeferred<Candidate>()
            val attempts = live.mapIndexed { i, c ->
                launch {
                    var pause = retryMs
                    suspend fun holdForHigherRanks(headStart: Long) {
                        // a lower rank holds off while a higher one is engaged;
                        // without an engagement it waits the head start once
                        var waited = 0L
                        while (!result.isCompleted) {
                            val engaged = live.take(i).any { it.transport.engaged }
                            if (!engaged && waited >= headStart) return
                            delay(HOLD_POLL_MS)
                            waited += HOLD_POLL_MS
                        }
                    }
                    if (i > 0) holdForHigherRanks(headStartMs * i)
                    while (!result.isCompleted) {
                        try {
                            Log.i("path", "trying ${c.name}")
                            updateState { it.copy(transportName = "auto: trying ${c.name}") }
                            c.transport.start(warmupFrame)
                            if (!result.complete(c)) {
                                // another path finished first: give this one back,
                                // whatever happens to this coroutine next
                                withContext(NonCancellable) {
                                    try { c.transport.stop() } catch (e: Exception) { Log.w("path", "${c.name} stop after losing: ${e.message}") }
                                }
                            }
                            return@launch
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: CapabilityRefused) {
                            disabled.add(c.name)
                            emit(TransportEvent.Fault("path", "${c.name}: ${e.message}"))
                            Log.e("path", "${c.name} refused at the capability gate — disabled for this run")
                            return@launch
                        } catch (e: Exception) {
                            val msg = e.message ?: e.toString()
                            emit(TransportEvent.Fault("path", "${c.name}: $msg"))
                            Log.w("path", "${c.name} did not start ($msg) — again in ${pause / 1000} s")
                        }
                        if (result.isCompleted) return@launch
                        delay(pause)
                        pause = minOf(maxRetryMs, pause * 2)
                        if (i > 0) holdForHigherRanks(0)
                    }
                }
            }
            // every path gave up for good (all disabled) → the race cannot end
            launch {
                attempts.forEach { it.join() }
                if (!result.isCompleted)
                    result.completeExceptionally(CapabilityRefused(
                        "no transport path could start — refused at the capability gate: ${disabled.joinToString()}"))
            }
            try {
                result.await()
            } finally {
                attempts.forEach { it.cancel() }
            }
        }
        active = winner
        Log.i("path", "driving via ${winner.name}")
        // UNDISPATCHED: subscribed before anything else runs, so nothing the
        // winner emits from here on is missed (round 1, d6)
        forward = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) { winner.transport.events.collect { emit(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) { winner.transport.state.collect { st -> updateState { st } } }
        }
        proxy.attach(winner.transport.mirror)
        val st = winner.transport.state.value
        updateState { st }
        // the winner's start-time events happened before anyone listened here
        emit(TransportEvent.Link(true, "${winner.name} link up (via auto)"))
        if (st.leaseHeld) emit(TransportEvent.Lease(true, "lease held (${winner.name})"))
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
            withContext(NonCancellable) {
                try { a.transport.stop() } catch (e: Exception) { Log.w("path", "${a.name} stop: ${e.message}") }
            }
        }
        updateState { it.copy(connected = false, started = false, leaseHeld = false, transportName = "auto") }
        if (a != null) emit(TransportEvent.Link(false, "auto: stopped"))
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
        override fun snapshot(arm: Arm): ByteArray = inner?.snapshot(arm) ?: blank.copyOf()
        override fun addListener(l: LensPanels.LensListener) { listeners.add(l) }
        override fun removeListener(l: LensPanels.LensListener) { listeners.remove(l) }
    }

    companion object {
        const val HOLD_POLL_MS = 250L
    }
}
