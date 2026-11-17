package wm.damage.core.app

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P3 (`CONTRACT.md` §2.3, §2.4, §2.5, §9.3, §9.6, §13 (b) "working copy" and "op queue"; the
 * spec's §2 P3 and §3): the phone's working copy of the PC's record. The view is the PC's state plus this device's ops
 * the PC has not answered, less those `welcome`'s high-water covers. An op applies to the view at once (the first
 * flush may draw it; no disk and no network on the caller's thread), its line lands on the queue's writer thread, and
 * the wire sends it after that. The PC's answer and change come back and the view is rebuilt: equal to what it showed,
 * nothing repaints; different, the change is said to the presenters (and a loss is answered).
 *
 * An answered op keeps its place in the view until the PC's state that holds it has arrived — its answer carries that
 * place (`Ans.at`), and every `snap` after an answer holds it — so an answer never shows the old value for a moment.
 * The PC's state and its cursor are saved in `workcopy/copy.json` (temp and move, on the saver thread); an answered
 * op's queue line leaves only once a save holds its effect, so a phone that restarts in between still shows it.
 *
 * A new epoch (the PC's record was restored or its log ended inside a line): pending ops are kept and judged on their
 * values; ops that expected a revision of the old record lose, said with the standing value once the new snapshot is
 * in. A PC that no longer holds this device's numbers (its high-water below the oldest op held) gets the waiting ops in
 * a new stream, numbered from 1 — the old stream could only meet "expected n" for ever.
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
     *  ([at]; null = the next state), and for a loss the marks to draw instead of its own result. */
    private class Settled(val op: Op, val at: Cursor?, val loss: Answer.Lost?, val covered: Boolean = false)

    // ---- the executor's ----
    private val base = HashMap<String, Entries>()
    private val views = HashMap<String, WindowState>()
    private val watchers = HashMap<String, MutableList<SourceListener>>()
    private val settled = ArrayList<Settled>()
    private val unsaved = ArrayList<OpId.Queued>()
    private val losers = ArrayList<Op>()
    /** Fix round 1, ruling 1.5 (b); round 2, ruling 1.2: every loss answered, kept and saved with the copy until a
     *  presenter has said it ([said]), and handed to each watcher of its window meanwhile (a stack rebuild hands it
     *  again; a process end hands it again from the saved copy). */
    private val unheard = ArrayList<Pair<Op, Answer.Lost>>()
    /** Ruling 1.5 (d): the ops whose loss was told (or kept), the last [TOLD_KEPT]: a loss is said once per (stream, n). */
    private val told = LinkedHashSet<OpId.Queued>()
    /** The windows the live connection shares (its `welcome`'s contracts against this build's). */
    private var shared: Set<String> = emptySet()
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
    @Volatile private var notApplied = 0
    @Volatile var client: AppClient? = null
        private set
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

    /** Ends the wire, saves what is not saved and ends the writers after what was handed to them. */
    override fun close() {
        try {
            // on the executor, behind [start]: a stop that comes before the start's task still ends the wire it builds
            executor.call {
                client?.close()
                client = null
                if (dirty || unsaved.isNotEmpty()) save()
            }
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
            cursor = AppWire.cursorOf(o)
            (o["unheard"] as? JsonArray)?.forEach { k ->
                val ko = k as JsonObject
                val op = OpJson.op(ko["op"] as JsonObject)
                val a = OpJson.answer(ko["answer"] as JsonObject) as Answer.Lost
                unheard += op to a
                (op.id as? OpId.Queued)?.let { told += it }
            }
        } catch (e: Exception) {
            base.clear()
            cursor = null
            unheard.clear()
            told.clear()
            val kept = copyFile.resolveSibling("${copyFile.fileName}.bad-${clock()}")
            val moved = try { Files.move(copyFile, kept); "kept as ${kept.fileName}" } catch (m: Exception) { "not kept (${m.message})" }
            val line = "the saved copy could not be read (${e::class.simpleName}: ${e.message}); $moved; the next snapshot fills it"
            Log.e(TAG, line)
            note("FAILED: $line")
        }
    }

    // ---- the backend of a source ----

    override fun stateOf(window: String): WindowState = views.getOrPut(window) { build(window, queue.ops()) }

    /** C§2.3 step 2: the op is numbered (or a position op coalesces into an unsent one for its item), applied to the
     *  view and handed to the queue's writer; the wire sends it once its line has landed. */
    override fun submit(window: String, draft: Draft) {
        val spec = actions.spec(window, draft.action)
        val coalesce: ((Op) -> Boolean)? = if (spec?.kind == Kind.POSITION && draft.targets.size == 1) {
            { o -> o.window == window && o.action == draft.action && o.targets == draft.targets }
        } else null
        queue.add({ id ->
            Op(device, id, window, draft.action, draft.targets, draft.result, draft.expect, draft.params, cursor?.seq)
        }, coalesce, clock())
        refresh(setOf(window))
    }

    override fun watch(window: String, l: SourceListener) {
        watchers.getOrPut(window) { ArrayList() } += l
        // ruling 1.5 (b), 1.2: the losses of this window not said yet are handed to each watcher; kept until said
        val kept = unheard.filter { it.first.window == window }
        if (kept.isEmpty()) return
        say("${kept.size} loss(es) of $window not said yet are handed to its presenter now")
        for ((op, a) in kept) try {
            l.answered(op, a)
        } catch (e: Exception) {
            Log.e(TAG, "a $window presenter failed on a kept answer", e)
        }
    }

    override fun unwatch(window: String, l: SourceListener) {
        watchers[window]?.remove(l)
    }

    override fun said(window: String, op: Op) {
        if (unheard.removeAll { it.first.id == op.id }) scheduleSave()
    }

    override fun journal(line: String) = note(line)

    /** S1-2 Q3: true when the live connection shares [window] (its last `welcome`); on the executor. */
    fun shares(window: String): Boolean = window in shared

    // ---- the wire (AppClient's reading thread: hand off) ----

    override fun subs(w: Welcome): List<AppClient.Sub> = listOf(AppClient.Sub(AppWire.RECORD, cursor))

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
        val touched = HashSet<String>()
        val hw = w.highWater[queue.stream] ?: 0L
        // C§2.4: the high-water covers ops the PC has handled (their answers went with an ended connection); they
        // stay drawn until the PC's state at this welcome has arrived
        for (op in queue.covered(hw)) {
            settled += Settled(op, Cursor(w.epoch, w.seq), null, covered = true)
            touched += op.window
        }
        val c = cursor
        var holes = false
        if (c != null && c.epoch != w.epoch) {
            val lost = queue.take { it.expect is Expect.Rev }
            losers += lost
            holes = lost.isNotEmpty()
            for (op in lost) touched += op.window
            say("the PC's record has a new epoch (${c.epoch} -> ${w.epoch}): ${queue.waiting} waiting op(s) are sent " +
                "again and judged on their values; ${lost.size} that expected a revision of the old record are not applied")
        }
        val ns = queue.ops().map { (it.id as OpId.Queued).n }
        if (holes || queue.head != hw + 1 || ns.zipWithNext().any { (a, b) -> b != a + 1 }) {
            queue.renumber("the PC holds stream ${queue.stream} of this device only up to $hw")
        }
        refresh(touched)
        pump()
    }

    private fun onSnap(s: Snap, parts: Map<String, Entries>) {
        val touched = HashSet<String>()
        // every window this connection shares is in the snapshot, empty or not; the others keep their copy
        for (w in shared + parts.keys) {
            base[w] = parts[w] ?: Entries.EMPTY
            touched += w
        }
        cursor = Cursor(s.epoch, s.seq)
        // a snapshot sent after an answer holds that op (or is of a new epoch, which judged it afresh)
        val derived = ArrayList<Pair<Op, Answer.Lost>>()
        for (st in settled) {
            unsaved += st.op.id as OpId.Queued
            touched += st.op.window
            if (st.covered) lossFromState(st.op)?.let { derived += st.op to it }
        }
        settled.clear()
        if (losers.isNotEmpty()) {
            for (op in losers) {
                touched += op.window
                tell(op, Answer.Lost(op.targets.ifEmpty { listOf(op.window) }.map { t -> lossAgainst(op, t) }))
            }
            queue.forget(losers.map { it.id as OpId.Queued })
            losers.clear()
        }
        refresh(touched)
        for ((op, a) in derived) tell(op, a)
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
        cursor = Cursor(c.epoch, c.seq)
        val derived = settle(touched)
        refresh(touched)
        for ((op, a) in derived) tell(op, a)
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
        settled += Settled(op, a.at, ans as? Answer.Lost)
        val touched = hashSetOf(op.window)
        val derived = settle(touched)
        refresh(touched)
        tell(op, ans)
        for ((o, l) in derived) tell(o, l)
    }

    /** C§2.4 / P2's codes: `retry` (and `ended`, never meant for a queued op) waits for the next ping; `gap` sends
     *  again from the number named; `contract` keeps it until the next welcome; any other refusal is for good. */
    private fun refused(id: OpId.Queued, r: Answer.Refused) {
        when (r.code) {
            Answer.RETRY, Answer.ENDED -> {
                retryHeld = true
                queue.resendFrom(id.n)
                Log.w(TAG, "op ${id.n} not taken (${r.reason}): sent again at the next ping")
            }
            Answer.GAP -> {
                val e = GAP_RE.find(r.reason)?.groupValues?.get(1)?.toLongOrNull()
                when {
                    e != null && queue.holds(e) -> queue.resendFrom(e)
                    e != null && e < queue.head -> queue.renumber("the PC expects op $e of stream ${queue.stream}, " +
                        "which this device holds as answered")
                    else -> Log.w(TAG, "op ${id.n}: ${r.reason}, which names no op this device holds")
                }
                pump()
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

    /** Settled ops the PC's state now holds leave the view's fold; their lines leave the queue after the next save.
     *  Returns the losses read from that state for ops a welcome settled without their answer (ruling 1.5 (c)). */
    private fun settle(touched: MutableSet<String>): List<Pair<Op, Answer.Lost>> {
        val cur = cursor
        val derived = ArrayList<Pair<Op, Answer.Lost>>()
        val it = settled.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val held = s.at == null || (cur != null && s.at.epoch == cur.epoch && s.at.seq <= cur.seq)
            if (!held) continue
            it.remove()
            unsaved += s.op.id as OpId.Queued
            touched += s.op.window
            if (s.covered) lossFromState(s.op)?.let { derived += s.op to it }
        }
        return derived
    }

    /**
     * Fix round 1, ruling 1.5 (c): an op the welcome's high-water settled without its answer (the answer went with an
     * ended connection) is not assumed applied. The PC marks an item an op lost on with the op's own value and device
     * (`Merge`'s "not applied"); when the state that holds the op shows that mark over another device's value, the
     * loss is handed on with both values. A mark a later write has cleared tells nothing, and nothing is said.
     */
    private fun lossFromState(op: Op): Answer.Lost? {
        val b = base[op.window] ?: return null
        val losses = op.targets.mapNotNull { t ->
            val i = b.items[Record.key(op.window, t)] ?: return@mapNotNull null
            val m = i.notApplied ?: return@mapNotNull null
            if (m.by != op.device || m.mine != op.result || i.by == op.device) return@mapNotNull null
            Loss(t, if (i.gone) JsonNull else i.value, i.by, i.at, op.result, gone = i.gone)
        }
        return if (losses.isEmpty()) null else Answer.Lost(losses)
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
        notApplied = views.values.sumOf { it.notApplied }
    }

    /** The PC's state of [w], then its settled ops, then its waiting ops, each as this device meant it. */
    private fun build(w: String, ops: List<Op>): WindowState {
        val b = base[w] ?: Entries.EMPTY
        val items = HashMap(b.items)
        for (s in settled) if (s.op.window == w) fold(s.op, s.loss, items)
        val pending = HashSet<String>()
        for (op in ops) if (op.window == w) {
            fold(op, null, items)
            for (t in op.targets) pending += Record.key(w, t)
        }
        return WindowState(w, items, pending, cursor)
    }

    /** One op over the view: judged as if what it expected still held (the device's own intent), per target for a
     *  removal; a loss draws the standing entry with its "not applied" mark instead, for the targets that lost. */
    private fun fold(op: Op, loss: Answer.Lost?, items: HashMap<String, Item>) {
        val spec = actions.spec(op.window, op.action) ?: return
        if (Merge.check(spec, op) != null) return
        val view = RecordView { key -> items[key] }
        val lostItems = loss?.losses?.map { it.item }?.toSet() ?: emptySet()
        fun apply(o: Op) {
            items.putAll(Merge.judge(view, spec, o, clock()).effect.items)
        }
        when (spec.kind) {
            Kind.REGISTER, Kind.POSITION ->
                (op.targets - lostItems).takeIf { it.isNotEmpty() }?.let { apply(op.copy(targets = it, expect = Expect.Any)) }
            Kind.REMOVE -> for (t in op.targets - lostItems) {
                apply(op.copy(targets = listOf(t), expect = Expect.Rev(items[Record.key(op.window, t)]?.rev ?: 0L)))
            }
        }
        for (l in loss?.losses.orEmpty()) {
            val k = Record.key(op.window, l.item)
            val mark = NotApplied(l.mine, op.device, l.at)
            items[k] = (items[k] ?: Item(JsonNull, 0L, l.by, l.at)).copy(notApplied = mark)
        }
    }

    /** A revision op of the old epoch, against the new snapshot's entry for [target]. */
    private fun lossAgainst(op: Op, target: String): Loss {
        val k = Record.key(op.window, target)
        val b = base[op.window]
        val i = b?.items?.get(k)
        val standing: JsonElement = if (i == null || i.gone) JsonNull else i.value
        return Loss(target, standing, i?.by ?: "", i?.at ?: 0L, op.result, gone = i?.gone == true)
    }

    private fun tell(op: Op, a: Answer) {
        if (a is Answer.Lost) {
            val id = op.id as? OpId.Queued
            // ruling 1.5 (d): a loss is said once per (stream, n)
            if (id != null && !told.add(id)) {
                Log.i(TAG, "op ${id.n} of stream ${id.stream}: its loss was told already; not told again")
                return
            }
            while (told.size > TOLD_KEPT) told.remove(told.first())
            // ruling 1.5 (b), 1.2: kept with the copy until a presenter says it, whether or not one watches now
            unheard += op to a
            scheduleSave()
            if (watchers[op.window].isNullOrEmpty()) {
                say("a loss of ${op.window} ${op.action} (op ${id?.n}) came while no presenter listened: kept for the next")
                return
            }
        } else if (a is Answer.Refused && watchers[op.window].isNullOrEmpty()) {
            // fix round 2, ruling 1.7: a refusal for good with no presenter to say it is said here, never to no one
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
        val kept = unheard.map { (op, a) -> buildJsonObject { put("op", OpJson.op(op)); put("answer", OpJson.answer(a)) } }
        hand(saver, "a save") {
            val err = try {
                saveWrite(copyFile, buildJsonObject {
                    put("v", 1)
                    at?.let { put("epoch", it.epoch); put("seq", it.seq) }
                    put("items", RecordJson.items(items))
                    if (kept.isNotEmpty()) put("unheard", JsonArray(kept))
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

    // ---- what the away line says (C§2.5; INTERFACES.md P-04) ----

    /** `N changes waiting · N not applied · PC not answering since HH:MM`, each part only when it holds. */
    fun awayLine(zone: ZoneId = ZoneId.systemDefault()): String = awayLine(queue.waiting, notApplied, reach, zone)

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
        /** The ops whose loss was told that are remembered (the PC keeps as many answers per stream). */
        private const val TOLD_KEPT = 1_000
        private val GAP_RE = Regex("expected (\\d+)")
        private val json = Json { ignoreUnknownKeys = true }
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        fun awayLine(waiting: Int, notApplied: Int, reach: AppClient.Reach, zone: ZoneId): String {
            val parts = ArrayList<String>()
            if (waiting > 0) parts += "$waiting ${if (waiting == 1) "change" else "changes"} waiting"
            if (notApplied > 0) parts += "$notApplied not applied"
            if (reach is AppClient.Reach.Down) parts += "PC not answering since " + HHMM.format(Instant.ofEpochMilli(reach.since).atZone(zone))
            return parts.joinToString(" · ")
        }

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
