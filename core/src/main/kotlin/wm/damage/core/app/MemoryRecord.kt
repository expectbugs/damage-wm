package wm.damage.core.app

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 (the spec's §2 P4, "a top-level `fun ReaderWindow(…)` builds core + local source +
 * presenter"): a record held in memory, judged by the one merge rule, for a window built the way it was before its
 * conversion — the test sites, `--selfcheck`, the snapshot judge's first arrangement. Nothing is written to disk and
 * nothing outlives the process: the window's own local copy (`window.<id>` keys) is what a restart of such a window
 * reads.
 */

/**
 * A [RecordBackend] over an in-memory [Record]: each op is checked and judged as the PC's executor would ([Merge]),
 * applied, answered, then published to the window's watchers — in that order, as `AppHost` does. Every op is
 * [device]'s, numbered in one stream of its own. Runs on [executor] (one shared daemon thread by default: many test
 * windows, one thread).
 */
class MemoryRecord(
    private val actions: ActionTable,
    private val device: String = Peer.LOCAL.device,
    override val executor: CoreExecutor = SHARED,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RecordBackend {
    private val record = Record()
    private val states = HashMap<String, WindowState>()
    private val watchers = HashMap<String, MutableList<SourceListener>>()
    private var n = 0L
    private var seq = 0L

    override fun stateOf(window: String): WindowState = states.getOrPut(window) {
        val e = record.window(window)
        WindowState(window, e.items, cursor = Cursor(EPOCH, seq))
    }

    override fun submit(window: String, draft: Draft) {
        val op = Op(device, OpId.Queued(STREAM, ++n), window, draft.action, draft.targets, draft.result, draft.params,
            at = draft.at ?: now(), base = seq)
        val spec = actions.spec(window, draft.action)
        val why = if (spec == null) "no action \"${draft.action}\" in $window" else Merge.check(spec, op)
        if (spec == null || why != null) {
            answer(op, Answer.Refused(why ?: "refused", if (spec == null) Answer.UNKNOWN else Answer.INVALID))
            return
        }
        val effect = Merge.judge(record, spec, op, minOf(op.at, clock()))
        record.apply(effect)
        if (!effect.isEmpty()) seq++
        answer(op, Answer.Seen())
        if (effect.isEmpty()) return
        val old = states[window] ?: return                         // nobody has read it: read whole when asked
        val e = record.window(window)
        val s = WindowState(window, e.items, cursor = Cursor(EPOCH, seq))
        states[window] = s
        val keys = s.drawnChanges(old)
        for (l in watchers[window].orEmpty().toList()) l.changed(s, keys)
    }

    override fun now(): Long = clock()

    override fun watch(window: String, l: SourceListener) {
        watchers.getOrPut(window) { ArrayList() } += l
    }

    override fun unwatch(window: String, l: SourceListener) {
        watchers[window]?.remove(l)
    }

    private fun answer(op: Op, a: Answer) {
        for (l in watchers[op.window].orEmpty().toList()) l.answered(op, a)
    }

    companion object {
        /** One daemon thread for every in-memory record of the process. */
        val SHARED: CoreExecutor by lazy { CoreExecutor("damage-core-memory") }
        private const val STREAM = "memory"
        private const val EPOCH = 1L
    }
}
