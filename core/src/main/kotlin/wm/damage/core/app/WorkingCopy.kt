package wm.damage.core.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P3 (`CONTRACT.md` §2.3, §2.4, §2.5, §9.3, §9.6, §13 (b) "working copy" and "op queue"; the
 * spec's §2 P3 and §3): the phone's working copy of the PC's record. The view is the PC's state plus this device's ops
 * the PC has not answered, each folded by the PC's own rule (L1 C1: the latest input wins, so an op older than the
 * state's item does not show over it). An op applies to the view at once (the first flush may draw it; no disk and no
 * network on the caller's thread), its line lands on the queue's writer thread, and the wire sends it after that. The
 * PC's answer and change come back and the view is rebuilt: equal to what it showed, nothing repaints; different, the
 * change is said to the presenters.
 *
 * An answered op keeps its place in the view until the PC's state that holds it has arrived — its answer carries that
 * place (`Ans.at`), and every `snap` after an answer holds it — so an answer never shows the old value for a moment.
 * The PC's state and its cursor are saved in `workcopy/copy.json` (temp and move, on the saver thread); an answered
 * op's queue line leaves only once a save holds its effect, so a phone that restarts in between still shows it. Every
 * connection takes a snapshot (L1 C2) and sends every op still waiting: one the PC took before is applied again to no
 * effect.
 *
 * Every state field is the core executor's alone; the AppClient's callbacks hand off to it.
 */
class WorkingCopy(
    val dir: Path,
    private val actions: ActionTable,
    override val executor: CoreExecutor,
    val device: String = Peer.PHONE.device,
    private val contracts: Map<String, Int> = Contracts.MINE,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val note: (String) -> Unit = {},
    queueWriter: Executor = OpQueue.defaultWriter(),
    queueAppend: (Path, String) -> Unit = OpQueue::appendForced,
    mint: () -> String = OpQueue::newStream,
    private val saveWrite: (Path, String) -> Unit = { f, t -> Persistence.writeAtomic(f, t, sync = true) },
    private val saver: Executor = single("damage-copy-saver"),
    private val wire: Executor = single("damage-app-send"),
) : RecordBackend, AppClient.Listener, AutoCloseable {

    val queue = OpQueue(dir.resolve(QUEUE_FILE), queueWriter, queueAppend, note = note, onLanded = { pump() }, mint = mint)
    private val copyFile = dir.resolve(COPY_FILE)

    private class Entries(val items: Map<String, Item>) {
        companion object {
            val EMPTY = Entries(emptyMap())
        }
    }

    /** An answered op still drawn over the PC's state until a state holding it arrives: the place its answer gave
     *  ([at]; null = the next state). */
    private class Settled(val op: Op, val at: Cursor?)

    // ---- the executor's ----
    private val base = HashMap<String, Entries>()
    private val views = HashMap<String, WindowState>()
    private val watchers = HashMap<String, MutableList<SourceListener>>()
    private val settled = ArrayList<Settled>()
    private val unsaved = ArrayList<OpId.Queued>()
    /** The windows the live connection shares (its `welcome`'s contracts against this build's). Written on the
     *  executor, read by [shares] from the shell loop (L1 A-N9). */
    @Volatile private var shared: Set<String> = emptySet()
    /** S1-5 G: the windows this copy holds the PC's state of — had in a snapshot, or holding items in the saved copy.
     *  A copy saved by a build without a window has none of it until a snapshot shares it (S1-4's R1.5 class: its
     *  cursor does not say so). Written on the executor before the views are told. */
    @Volatile private var heardOf: Set<String> = emptySet()
    private var saving = false
    private var dirty = false
    private var saveFailures = 0

    /** The PC's place the saved state stands at; null before the first snapshot. */
    @Volatile var cursor: Cursor? = null
        private set
    /** The live connection: a `welcome` starts the next one. */
    @Volatile private var gen = 0L
    @Volatile private var retryHeld = false
    @Volatile private var contractHeld = false
    @Volatile var client: AppClient? = null
        private set
    /** The PC's reach as the lane last decided it: with the queue's waiting count, what a health view reads (L1 C6). */
    @Volatile var reach: AppClient.Reach = AppClient.Reach.Unknown
        private set

    val stream: String get() = queue.stream

    // ---- start and stop ----

    /** Loads the copy and the queue on the executor, then builds the wire with this device's stream. */
    fun start(connect: (stream: String) -> AppClient) = executor.execute("working copy start") {
        openNow()
        client = connect(queue.stream)
    }

    /** The PC's address or credential changed: a new wire, the same copy and queue. */
    fun reconnect(connect: (stream: String) -> AppClient) = executor.execute("working copy reconnect") {
        client?.close()
        client = connect(queue.stream)
    }

    /** Loads without a wire (a test; the phone uses [start]). */
    fun open() = executor.call { openNow() }

    /** Ends the wire, saves what is not saved and returns once the copy's saves and the queue's lines handed to their
     *  writers have landed — a clean close, bounded by the writes and never by a clock (WC, 2026-11-20) — then ends the
     *  writers. Called from a thread of its own, never the core executor (it waits on the executor's "copy saved"). */
    override fun close() {
        try {
            // on the executor, behind [start]: a stop that comes before the start's task still ends the wire it builds
            executor.call {
                client?.close()
                client = null
                if (!saving && (dirty || unsaved.isNotEmpty())) save()
            }
            // the save in flight lands, then any its end starts ([saved] on the executor, handed on by the saver)
            while (executor.call { saving }) OpQueue.drain(saver)
        } catch (e: Exception) {
            Log.e(TAG, "the stop could not be handed to the core executor (${e::class.simpleName}: ${e.message})")
            client?.close()
            client = null
        }
        queue.close()
        (saver as? ExecutorService)?.shutdown()
        (wire as? ExecutorService)?.shutdown()
    }

    private fun openNow() {
        loadCopy()
        queue.load()
        refresh(base.keys + queue.ops().map { it.window })
    }

    private fun loadCopy() {
        if (!Files.exists(copyFile)) return
        try {
            val o = json.parseToJsonElement(Files.readAllBytes(copyFile).toString(Charsets.UTF_8)) as? JsonObject
                ?: throw IllegalArgumentException("not an object")
            // L1 C4: a 0.67 copy's `colls` (always empty) is not read
            val e = Effect(RecordJson.items(o["items"] as? JsonObject ?: JsonObject(emptyMap())))
            base.putAll(group(e))
            heardOf = base.keys.toSet()
            cursor = AppWire.cursorOf(o)          // L1 C1: a 0.67 copy's `unheard` losses are not read
        } catch (e: Exception) {
            base.clear()
            heardOf = emptySet()
            cursor = null
            val kept = copyFile.resolveSibling("${copyFile.fileName}.bad-${clock()}")
            val moved = try { Files.move(copyFile, kept); "kept as ${kept.fileName}" } catch (m: Exception) { "not kept (${m.message})" }
            val line = "the saved copy could not be read (${e::class.simpleName}: ${e.message}); $moved; the next snapshot fills it"
            Log.e(TAG, line)
            note("FAILED: $line")
        }
    }

    // ---- the backend of a source ----

    override fun stateOf(window: String): WindowState = views.getOrPut(window) { build(window, queue.ops()) }

    /** C§2.3 step 2: the op is numbered (or a position op coalesces into an unsent one for its item), stamped with its
     *  input time, applied to the view and handed to the queue's writer; the wire sends it once its line has landed. */
    override fun submit(window: String, draft: Draft) {
        val spec = actions.spec(window, draft.action)
        val coalesce: ((Op) -> Boolean)? = if (spec?.kind == Kind.POSITION && draft.targets.size == 1) {
            { o -> o.window == window && o.action == draft.action && o.targets == draft.targets }
        } else null
        val at = draft.at ?: now()
        queue.add({ id ->
            Op(device, id, window, draft.action, draft.targets, draft.result, draft.params, at = at)
        }, coalesce)
        refresh(setOf(window))
    }

    override fun watch(window: String, l: SourceListener) {
        watchers.getOrPut(window) { ArrayList() } += l
    }

    override fun unwatch(window: String, l: SourceListener) {
        watchers[window]?.remove(l)
    }

    override fun journal(line: String) = note(line)

    override fun now(): Long = clock()

    /** S1-2 Q3: true when the live connection shares [window] (its last `welcome`); on the executor. */
    fun shares(window: String): Boolean = window in shared

    override fun heard(window: String): Boolean = window in heardOf

    // ---- the wire (AppClient's reading thread: hand off) ----

    /** L1 C2: every subscription takes a snapshot, so none names a cursor. */
    override fun subs(w: Welcome): List<AppClient.Sub> = listOf(AppClient.Sub(AppWire.RECORD, null))

    override fun welcome(w: Welcome) = executor.execute("welcome") { onWelcome(w) }

    override fun snap(s: Snap) {
        if (s.scope != AppWire.RECORD) return
        val parts = group(s.state)                             // a large snapshot is split off the executor
        executor.execute("snapshot") { onSnap(s, parts) }
    }

    override fun change(c: Chg) = executor.execute("change") { onChange(c) }

    override fun answer(a: Ans) = executor.execute("answer") { onAnswer(a) }

    override fun reach(r: AppClient.Reach) {
        reach = r
    }

    override fun contracts(s: Contracts.State) = pump()

    override fun pinged(k: Long) = executor.execute("ping") { onPing() }

    private fun onWelcome(w: Welcome) {
        gen++
        retryHeld = false
        contractHeld = false
        shared = Contracts.shared(contracts, w.contracts)
        pump()
    }

    private fun onSnap(s: Snap, parts: Map<String, Entries>) {
        val touched = HashSet<String>()
        // every window this connection shares is in the snapshot, empty or not; the others keep their copy
        for (w in shared + parts.keys) {
            base[w] = parts[w] ?: Entries.EMPTY
            touched += w
        }
        heardOf = heardOf + touched
        cursor = Cursor(s.epoch, s.seq)
        // a snapshot sent after an answer holds that op
        for (st in settled) {
            unsaved += st.op.id as OpId.Queued
            touched += st.op.window
        }
        settled.clear()
        refresh(touched)
        scheduleSave()
    }

    private fun onChange(c: Chg) {
        val cur = cursor ?: return                              // no snapshot yet: one is coming
        if (c.scope != AppWire.RECORD || c.epoch != cur.epoch || c.seq <= cur.seq) return
        val touched = HashSet<String>()
        for ((w, part) in group(c.patches)) {
            val b = base[w] ?: Entries.EMPTY
            base[w] = Entries(b.items + part.items)
            touched += w
        }
        if (!heardOf.containsAll(touched)) heardOf = heardOf + touched
        cursor = Cursor(c.epoch, c.seq)
        settle(touched)
        refresh(touched)
        scheduleSave()
    }

    private fun onAnswer(a: Ans) {
        val id = a.id as? OpId.Queued
        if (id == null) {
            Log.w(TAG, "an answer that names no queued op: ${a.answer}")
            return
        }
        if (id.stream != queue.stream) {
            Log.i(TAG, "an answer to op ${id.n} of stream ${id.stream}, which this device no longer sends: not used")
            return
        }
        val ans = a.answer
        if (ans is Answer.Refused) {
            refused(id, ans)
            return
        }
        val op = queue.answered(id.n)
        if (op == null) {
            Log.i(TAG, "op ${id.n} answered again (${ans::class.simpleName}): it was sent twice; nothing to do")
            return
        }
        settled += Settled(op, a.at)
        val touched = hashSetOf(op.window)
        settle(touched)
        refresh(touched)
        tell(op, ans)
    }

    /** P2's codes: `retry` (and `ended`, never meant for a queued op) waits for the next ping; `contract` keeps it
     *  until the next welcome; any other refusal is for good. */
    private fun refused(id: OpId.Queued, r: Answer.Refused) {
        when (r.code) {
            Answer.RETRY, Answer.ENDED -> {
                retryHeld = true
                queue.resendFrom(id.n)
                Log.w(TAG, "op ${id.n} not taken (${r.reason}): sent again at the next ping")
            }
            Answer.CONTRACT -> {
                contractHeld = true
                queue.resendFrom(id.n)
                Log.w(TAG, "op ${id.n} not taken (${r.reason}): kept until the next connection")
            }
            else -> {
                val op = queue.answered(id.n) ?: return
                queue.forget(listOf(id))
                Log.w(TAG, "op ${id.n} (${op.window} ${op.action}) refused for good: ${r.reason}")
                refresh(setOf(op.window))
                tell(op, r)
            }
        }
    }

    private fun onPing() {
        if (retryHeld) {
            retryHeld = false
            pump()
        }
        queue.retry()
        if (saveFailures > 0 && !saving) save()
    }

    /** Settled ops the PC's state now holds leave the view's fold; their lines leave the queue after the next save. An
     *  op the state already outranks or equals (L1 C1: an older input, a same value — the PC changed nothing, so no
     *  change of its `seq` follows) leaves at once. */
    private fun settle(touched: MutableSet<String>) {
        val cur = cursor
        val it = settled.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val held = s.at == null || (cur != null && s.at.epoch == cur.epoch && s.at.seq <= cur.seq) || shown(s.op)
            if (!held) continue
            it.remove()
            unsaved += s.op.id as OpId.Queued
            touched += s.op.window
        }
    }

    /** The wire task: the waiting ops in order, each once its line has landed (`OpQueue.nextToSend`). */
    private fun pump(): Unit = hand(wire, "a send") { pumpNow() }

    private fun pumpNow() {
        val c = client ?: return
        val g = gen
        if (g == 0L || c.welcome == null || retryHeld || contractHeld) return
        val st = c.contractState
        while (true) {
            val op = queue.nextToSend(g) { o -> st.shared(o.window) } ?: return
            // A `retry` or `contract` answer handled on the executor while this loop runs has set its flag and THEN
            // marked its op unsent (`refused`): read here, after the take, or the loop sends that op again at once
            // instead of at the next ping (the lead's ruling R3.1; the test missed 5 runs in 20 without this read).
            if (retryHeld || contractHeld) {
                queue.resendFrom((op.id as OpId.Queued).n)
                return
            }
            if (!c.send(op)) {
                queue.resendFrom((op.id as OpId.Queued).n)
                return
            }
        }
    }

    // ---- the view ----

    /** Rebuilds each window of [windows] and tells its presenters the keys whose drawn form moved. */
    private fun refresh(windows: Collection<String>) {
        if (windows.isEmpty()) return
        val ops = queue.ops()
        for (w in windows) {
            val old = views[w]
            val s = build(w, ops)
            views[w] = s
            val keys = s.drawnChanges(old)
            for (l in watchers[w].orEmpty().toList()) {
                try {
                    l.changed(s, keys)
                } catch (e: Exception) {
                    Log.e(TAG, "a $w presenter failed on a change", e)
                }
            }
        }
    }

    /** The PC's state of [w], then its settled ops, then its waiting ops, each folded by the PC's rule. */
    private fun build(w: String, ops: List<Op>): WindowState {
        val items = HashMap((base[w] ?: Entries.EMPTY).items)
        for (s in settled) if (s.op.window == w) fold(s.op, items)
        val pending = HashSet<String>()
        for (op in ops) if (op.window == w) {
            fold(op, items)
            for (t in op.targets) pending += Record.key(w, t)
        }
        return WindowState(w, items, pending, cursor)
    }

    /** True when the PC's state as this copy holds it already shows [op] (folding it changes nothing). */
    private fun shown(op: Op): Boolean {
        val spec = actions.spec(op.window, op.action) ?: return true
        val b = base[op.window] ?: Entries.EMPTY
        return judged(RecordView { key -> b.items[key] }, spec, op).isEmpty()
    }

    /** One op over the view, as the PC will judge it ([Merge]): written unless the view holds a later input. */
    private fun fold(op: Op, items: HashMap<String, Item>) {
        val spec = actions.spec(op.window, op.action) ?: return
        if (Merge.check(spec, op) != null) return
        items.putAll(judged(RecordView { key -> items[key] }, spec, op).items)
    }

    /** [op]'s effect as `AppHost` makes it: the window's own rule first ([Windows.RULES]: the notice record is ordered
     *  by its occurrence, L1 R1 RS NOTE 1), else the merge rule. */
    private fun judged(rec: RecordView, spec: ActionSpec, op: Op): Effect = when (val r = Windows.RULES.admit(rec, op)) {
        is Admit.Refuse, Admit.Keep -> Effect.NONE
        else -> Merge.judge(rec, spec, op, always = r == Admit.Apply)
    }

    /** An answer to one of this device's ops to its window's presenters; a refusal for good with none to hear it is
     *  said here (fix round 2, ruling 1.7: never to no one). */
    private fun tell(op: Op, a: Answer) {
        if (a is Answer.Refused && watchers[op.window].isNullOrEmpty()) {
            say("${op.window} ${op.action} (op ${(op.id as? OpId.Queued)?.n}) was refused for good while no presenter listened: ${a.reason}")
            return
        }
        for (l in watchers[op.window].orEmpty().toList()) {
            try {
                l.answered(op, a)
            } catch (e: Exception) {
                Log.e(TAG, "a ${op.window} presenter failed on an answer", e)
            }
        }
    }

    // ---- the saved copy ----

    private fun scheduleSave() {
        dirty = true
        if (!saving) save()
    }

    /** The PC's state and cursor, taken in one step here and written on the saver thread. */
    private fun save() {
        saving = true
        dirty = false
        val items = HashMap<String, Item>()
        for (e in base.values) items.putAll(e.items)
        val at = cursor
        val taken = ArrayList(unsaved)
        unsaved.clear()
        hand(saver, "a save") {
            val err = try {
                saveWrite(copyFile, buildJsonObject {
                    put("v", 1)
                    at?.let { put("epoch", it.epoch); put("seq", it.seq) }
                    put("items", RecordJson.items(items))
                }.toString())
                null
            } catch (e: Exception) {
                e
            }
            executor.execute("copy saved") { saved(err, taken) }
        }
    }

    private fun saved(err: Exception?, taken: List<OpId.Queued>) {
        saving = false
        if (err != null) {
            unsaved.addAll(0, taken)
            dirty = true
            if (saveFailures++ == 0) {
                val line = "the copy could not be saved (${err::class.simpleName}: ${err.message}); it is kept in " +
                    "memory and saved again at the next ping"
                Log.e(TAG, line)
                note("FAILED: $line")
            }
            return
        }
        if (saveFailures > 0) {
            val line = "the copy is saved again after $saveFailures failure(s)"
            Log.i(TAG, line)
            note(line)
            saveFailures = 0
        }
        queue.forget(taken.filter { it.stream == queue.stream })
        if (dirty || unsaved.isNotEmpty()) save()
    }

    private fun say(line: String) {
        Log.w(TAG, line)
        note(line)
    }

    /** [task] to one of this copy's own threads; after it has ended (the service's stop) said, never thrown at the
     *  caller (a writer's thread, the reading thread). */
    private fun hand(to: Executor, what: String, task: () -> Unit) {
        try {
            to.execute(task)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "$what after the working copy closed: not done")
            if (to === saver) saving = false
        }
    }

    companion object {
        private const val TAG = "workcopy"
        const val COPY_FILE = "copy.json"
        const val QUEUE_FILE = "queue.jsonl"
        private val json = Json { ignoreUnknownKeys = true }

        private fun group(e: Effect): Map<String, Entries> {
            val items = HashMap<String, HashMap<String, Item>>()
            for ((k, v) in e.items) items.getOrPut(HostBackend.windowOf(k)) { HashMap() }[k] = v
            return items.mapValues { Entries(it.value) }
        }

        private fun single(name: String): ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, name).apply { isDaemon = true }
        }
    }
}
