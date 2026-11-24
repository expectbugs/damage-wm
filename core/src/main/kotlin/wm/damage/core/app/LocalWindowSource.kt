package wm.damage.core.app

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P3 (`CONTRACT.md` §2.2; the spec's §2 P3): a window core on its host's core executor over a
 * record view — the PC's own record ([HostBackend]) or the phone's working copy (`WorkingCopy`).
 */

/**
 * A window core over a [RecordBackend] (the spec's `LocalWindowSource`): the state as the backend last told it, a
 * description cached per state, and acts handed to the executor. Build it at a start, never on a gesture: the first
 * state is read through the executor once.
 */
class LocalWindowSource(val core: WindowCore, private val backend: RecordBackend) : WindowSource, AutoCloseable {
    override val window: String = core.window
    private val listeners = CopyOnWriteArrayList<SourceListener>()
    @Volatile private var current: WindowState
    @Volatile private var described: Pair<WindowState, JsonObject>? = null
    /** S1-5 G: latched after [current] holds a state the backend had heard, so a reader that sees it true reads that
     *  state or a later one. */
    @Volatile private var heard = false

    private val relay = object : SourceListener {
        override fun changed(state: WindowState, keys: Set<String>) {
            current = state
            val first = !heard && backend.heard(window)
            if (first) heard = true
            if (keys.isEmpty() && !first) return
            for (l in listeners) say("a change") { l.changed(state, keys) }
        }

        override fun answered(op: Op, answer: Answer) {
            val ls = listeners.toList()
            if (ls.isEmpty() && answer is Answer.Refused && answer.code != Answer.RETRY) {
                // fix round 2, ruling 1.7: a refusal for good is never told to no one: said where it arrived (no notice kept)
                val line = "$window ${op.action} of ${op.targets} was refused for good while no presenter listened: ${answer.reason}"
                Log.w(TAG, line)
                backend.journal(line)
            }
            for (l in ls) say("an answer") { l.answered(op, answer) }
        }
    }

    init {
        current = backend.executor.call {
            backend.watch(window, relay)
            heard = backend.heard(window)
            backend.stateOf(window)
        }
    }

    override fun heard(): Boolean = heard

    override fun state(): WindowState = current

    override fun desc(): JsonObject {
        val s = current
        described?.let { (at, d) -> if (at === s) return d }
        return core.describe(s).also { described = s to it }
    }

    override fun level(addr: String): JsonObject? = core.level(current, addr)

    override fun act(draft: Draft) = backend.executor.execute("act $window ${draft.action}") { backend.submit(window, draft) }

    override fun addListener(l: SourceListener) {
        listeners += l
    }

    override fun removeListener(l: SourceListener) {
        listeners -= l
    }

    override fun now(): Long = backend.now()

    override fun close() = backend.executor.execute("close the $window source") { backend.unwatch(window, relay) }

    private inline fun say(what: String, f: () -> Unit) = try {
        f()
    } catch (e: Exception) {
        Log.e(TAG, "a $window presenter failed on $what", e)
    }

    private companion object {
        const val TAG = "source"
    }
}

/**
 * The PC's own record as a backend: an op goes to [host] as [device] in [stream], stamped with the PC's clock unless
 * its draft carries the input's own time, and each window's state follows the record's published changes — an
 * absolute entry replaces its old one, so nothing is re-read. The PC applies on the executor after its log line lands;
 * a presenter keeps its own optimistic fields meanwhile (Reader does, P4).
 */
class HostBackend(private val host: AppHost, private val device: String, private val stream: String) : RecordBackend {
    override val executor: CoreExecutor get() = host.executor
    private val states = HashMap<String, WindowState>()
    private val watchers = HashMap<String, MutableList<SourceListener>>()
    private var next = 0L

    private val onChange: (Change) -> Unit = { c -> changed(c) }

    init {
        host.addListener(onChange)
    }

    override fun stateOf(window: String): WindowState = states.getOrPut(window) {
        val (epoch, seq, e) = host.read(window)
        WindowState(window, e.items, cursor = Cursor(epoch, seq))
    }

    override fun submit(window: String, draft: Draft) {
        val op = Op(device, OpId.Queued(stream, ++next), window, draft.action, draft.targets, draft.result, draft.params,
            at = draft.at ?: now())
        host.act(op) { a -> for (l in watchers[window].orEmpty().toList()) l.answered(op, a) }
    }

    override fun watch(window: String, l: SourceListener) {
        watchers.getOrPut(window) { ArrayList() } += l
    }

    override fun unwatch(window: String, l: SourceListener) {
        watchers[window]?.remove(l)
    }

    override fun journal(line: String) = host.note(line)

    override fun now(): Long = host.clock()

    fun close() = host.removeListener(onChange)

    private fun changed(c: Change) {
        for (w in windowsOf(c.effect)) {
            val old = states[w] ?: continue                       // nobody has read it: read whole when asked
            val p = Record.prefix(w)
            val s = WindowState(w, old.items + c.effect.items.filterKeys { it.startsWith(p) }, cursor = Cursor(c.epoch, c.seq))
            states[w] = s
            val keys = s.drawnChanges(old)
            for (l in watchers[w].orEmpty().toList()) l.changed(s, keys)
        }
    }

    companion object {
        /** The windows [e] writes (its keys are `rec.<window>.<name>`). */
        fun windowsOf(e: Effect): Set<String> = e.items.keys.mapTo(HashSet()) { windowOf(it) }

        fun windowOf(key: String): String = key.removePrefix("rec.").substringBefore('.')
    }
}
