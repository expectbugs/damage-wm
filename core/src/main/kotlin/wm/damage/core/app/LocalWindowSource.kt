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
    /** Fix round 1, ruling 1.5; round 2, ruling 1.2: every loss answered here, kept until a presenter has said it
     *  ([said]) and handed to each presenter that listens meanwhile (the PC's next stack; the phone's kept losses,
     *  handed while this source is built and before its presenter listens). */
    private val unheard = LinkedHashMap<OpId, Pair<Op, Answer>>()

    private val relay = object : SourceListener {
        override fun changed(state: WindowState, keys: Set<String>) {
            current = state
            if (keys.isEmpty()) return
            for (l in listeners) say("a change") { l.changed(state, keys) }
        }

        override fun answered(op: Op, answer: Answer) {
            val ls = synchronized(unheard) {
                if (answer is Answer.Lost) unheard[op.id] = op to answer
                listeners.toList()
            }
            if (ls.isEmpty() && answer is Answer.Lost) Log.i(TAG, "a loss of $window ${op.action} came while no presenter listened: kept for the next")
            if (ls.isEmpty() && answer is Answer.Refused && answer.code != Answer.RETRY && answer.code != Answer.GAP) {
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
            backend.stateOf(window)
        }
    }

    override fun state(): WindowState = current

    override fun desc(): JsonObject {
        val s = current
        described?.let { (at, d) -> if (at === s) return d }
        return core.describe(s).also { described = s to it }
    }

    override fun level(addr: String): JsonObject? = core.level(current, addr)

    override fun act(draft: Draft) = backend.executor.execute("act $window ${draft.action}") { backend.submit(window, draft) }

    override fun addListener(l: SourceListener) {
        val kept = synchronized(unheard) { listeners += l; unheard.values.toList() }
        if (kept.isNotEmpty()) backend.executor.execute("kept answers to the $window presenter") {
            for ((op, a) in kept) say("a kept answer") { l.answered(op, a) }
        }
    }

    override fun said(op: Op) {
        synchronized(unheard) { unheard.remove(op.id) }
        backend.executor.execute("a $window loss said") { backend.said(window, op) }
    }

    override fun removeListener(l: SourceListener) {
        listeners -= l
    }

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
 * The PC's own record as a backend: an op goes to [host] as [device] in [stream] (its `n` continues from the
 * stream's high-water, read on the executor at the first op), and each window's state follows the record's
 * published changes — an absolute entry replaces its old one, so nothing is re-read. The PC applies on the executor
 * after its log line lands; a presenter keeps its own optimistic fields meanwhile (Reader does, P4).
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
        if (next == 0L) next = host.highWater(StreamKey(device, stream)) + 1
        val op = Op(device, OpId.Queued(stream, next++), window, draft.action, draft.targets, draft.result, draft.expect,
            draft.params, base = host.seq)
        host.act(op) { a ->
            // fix round 1, ruling 1.6: a `retry` (the log could not be written) or a `gap` took no number: the next
            // submit reads the high-water again, or every later op of this source would be refused "expected N"
            if (a is Answer.Refused && (a.code == Answer.RETRY || a.code == Answer.GAP)) next = 0L
            for (l in watchers[window].orEmpty().toList()) l.answered(op, a)
        }
    }

    override fun watch(window: String, l: SourceListener) {
        watchers.getOrPut(window) { ArrayList() } += l
    }

    override fun unwatch(window: String, l: SourceListener) {
        watchers[window]?.remove(l)
    }

    override fun journal(line: String) = host.note(line)

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
