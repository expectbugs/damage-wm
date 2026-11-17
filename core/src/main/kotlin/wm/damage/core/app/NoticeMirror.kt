package wm.damage.core.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q3 (the spec's §2.1, §5 rows N2–N5; `CONTRACT.md` §7.2, §7.3; S1-2 R0 §2): one host's view of
 * the PC's notice record, between the record (the PC's own through a `HostBackend`, the phone's working copy) and the
 * host's live shell. It keeps the notice cursor (epoch, occ): on first contact and on a new epoch it adopts the highest
 * occurrence and pops nothing; it pops each RAISED record raised past the cursor and not held (`heldUntil`, which the PC
 * decided at the raise); when a record it handed on moves to DISMISSED, ACKED or CLEARED (or READ, for queued ones) it
 * has it taken away; and it sends the shell's `read` and `dismiss` from input only. Every state field is the core
 * executor's.
 */

/** What the mirror hands the shell; each call returns at once (the shell posts it to its loop, held while no session
 *  runs). */
interface NoticeSink {
    /** Show [rec]: raised past this host's cursor and not held. */
    fun pop(rec: NoticeRecord)

    /** [ref] moved on another interface: a queued entry leaves; the shown box folds away — unless [queuedOnly] (the
     *  record turned READ elsewhere: the shown box stays, as an activation leaves it). */
    fun withdraw(ref: NoticeRef, queuedOnly: Boolean)
}

/** What the shell tells the mirror: reading effects from input only (C§7.2), and the notices it restored. */
interface NoticeInput {
    /** The tap-through, or an activation from input, read [ref]. */
    fun read(ref: NoticeRef)

    /** A double-tap, a long-press or an app-less tap dismissed [ref]. */
    fun dismiss(ref: NoticeRef)

    /** At a session start: of the record notices [refs] the shell restored, those the record still holds RAISED at
     *  that occurrence (or every one, while this host has no state of the record yet). Blocks for one executor turn:
     *  the start path only, never a gesture. */
    fun restored(refs: List<NoticeRef>): Set<NoticeRef>

    /** The shell's minute tick: held records whose `heldUntil` has passed pop, together. */
    fun minute()
}

/**
 * The mirror over [backend]'s `notices` window. [shared] = this host's connection shares the window (the phone: its
 * working copy's welcome; the PC: always) — while it does not, nothing is adopted or popped. [cursorFile]: where the
 * cursor is kept (the phone, beside the copy); null keeps it in memory (the PC, where `shell.state` restores what was
 * shown). [clock] is this device's wall clock (ms), read against `heldUntil`. [say] writes a journal line.
 */
class NoticeMirror(
    private val backend: RecordBackend,
    private val shared: () -> Boolean = { true },
    private val cursorFile: Path? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val say: (String) -> Unit = {},
    private val writer: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "damage-notice-cursor").apply { isDaemon = true } },
) : NoticeInput, AutoCloseable {

    /** The notice cursor (C§7.3): every record at or below [occ] of [epoch] was popped, adopted or is past. */
    data class Cursor(val epoch: Long, val occ: Long)

    private val ex: CoreExecutor get() = backend.executor
    private var sink: NoticeSink? = null
    @Volatile var cursor: Cursor? = null
        private set
    /** The records handed to the shell (popped, or restored and still live), by id: what a move elsewhere withdraws. */
    private val handed = HashMap<String, NoticeRef>()
    private val readHanded = HashSet<String>()

    private val listener = object : SourceListener {
        override fun changed(state: WindowState, keys: Set<String>) = evaluate(state)
        override fun answered(op: Op, answer: Answer) = answeredNow(op, answer)
    }

    init {
        ex.execute("notice mirror start") {
            cursor = loadCursor()
            backend.watch(Notices.WINDOW, listener)
            evaluate(backend.stateOf(Notices.WINDOW))
        }
    }

    /** The shell of a stack starts taking pops (the mirror outlives stacks: records raised meanwhile pop now). */
    fun attach(s: NoticeSink) = ex.execute("notice sink attached") {
        sink = s
        evaluate(backend.stateOf(Notices.WINDOW))
    }

    fun detach(s: NoticeSink) = ex.execute("notice sink detached") { if (sink === s) sink = null }

    override fun read(ref: NoticeRef) = input(ref, Notices.MARK_READ)

    override fun dismiss(ref: NoticeRef) = input(ref, Notices.DISMISS)

    override fun restored(refs: List<NoticeRef>): Set<NoticeRef> = if (refs.isEmpty()) emptySet() else ex.call {
        val st = backend.stateOf(Notices.WINDOW)
        if (!shared() || st.cursor == null) {
            // no state of the record yet: kept, and withdrawn later if the record says so
            for (r in refs) handed[r.id] = r
            return@call refs.toSet()
        }
        val recs = Notices.records(st)
        val keep = refs.filter { ref -> recs[ref.id]?.let { it.occ == ref.occ && it.state == Notices.RAISED } == true }
        for (r in keep) handed[r.id] = r
        val dropped = refs.size - keep.size
        if (dropped > 0) say("$dropped restored notice(s) the record no longer holds raised were not shown again")
        keep.toSet()
    }

    override fun minute() = ex.execute("notice minute") { evaluate(backend.stateOf(Notices.WINDOW)) }

    override fun close() {
        ex.execute("notice mirror close") { backend.unwatch(Notices.WINDOW, listener) }
        (writer as? ExecutorService)?.shutdown()
    }

    // ---- on the executor ----

    private fun evaluate(state: WindowState) {
        if (!shared()) return
        val epoch = state.cursor?.epoch ?: return                    // no state of the PC's record yet
        val recs = Notices.records(state, say)
        val s = sink
        if (s != null) withdrawMoved(recs, s)
        val top = recs.values.maxOfOrNull { it.occN } ?: 0L
        val c = cursor
        if (c == null || c.epoch != epoch) {
            // C§7.3: first contact and a new epoch adopt and pop nothing
            setCursor(Cursor(epoch, top))
            say(if (c == null) "first contact with the notice record: occurrence $top adopted, nothing popped"
                else "the record has a new epoch (${c.epoch} -> $epoch): occurrence $top adopted, nothing popped")
            return
        }
        if (s == null) return                                         // pops wait for a shell
        val now = clock()
        val pending = recs.values.filter { it.state == Notices.RAISED && it.occN > c.occ && handed[it.id] != it.ref }
        for (r in pending.filter { !it.heldAt(now) }.sortedBy { it.occN }) {
            handed[r.id] = r.ref
            readHanded -= r.id
            s.pop(r)
        }
        // never past a record still held: after a restart (the phone's cursor is on disk) it must still pop at its end
        val held = pending.filter { it.heldAt(now) }.minOfOrNull { it.occN }
        val next = if (held != null) minOf(top, held - 1) else top
        if (next > c.occ) setCursor(c.copy(occ = next))
    }

    /** §5 N5: a record handed on that moved elsewhere is taken away; a newer occurrence is a new box, not a withdraw. */
    private fun withdrawMoved(recs: Map<String, NoticeRecord>, s: NoticeSink) {
        val it = handed.entries.iterator()
        while (it.hasNext()) {
            val (id, ref) = it.next()
            val r = recs[id]
            when {
                r == null || (r.occ == ref.occ && !r.live) -> {
                    it.remove()
                    readHanded -= id
                    s.withdraw(ref, queuedOnly = false)
                }
                r.occ != ref.occ -> { it.remove(); readHanded -= id }
                r.state == Notices.READ && readHanded.add(id) -> s.withdraw(ref, queuedOnly = true)
            }
        }
    }

    private fun input(ref: NoticeRef, action: String) = ex.execute("notice $action") {
        val r = Notices.records(backend.stateOf(Notices.WINDOW))[ref.id]
        if (r == null || r.occ != ref.occ) {
            // the record moved on (another occurrence, or gone): the PC would change nothing; nothing is sent
            Log.i(TAG, "$action of ${ref.id} at occurrence ${ref.occ} not sent: the record holds ${r?.occ ?: "none"}")
            return@execute
        }
        backend.submit(Notices.WINDOW, Notices.draft(r, action))
    }

    private fun answeredNow(op: Op, a: Answer) {
        when (a) {
            is Answer.Refused -> {
                val line = "${op.action} of notice ${op.targets} not taken: ${a.reason}"
                Log.w(TAG, line)
                say(line)
            }
            else -> {}
        }
    }

    private fun setCursor(c: Cursor) {
        cursor = c
        val f = cursorFile ?: return
        val text = buildJsonObject { put("epoch", c.epoch); put("occ", c.occ) }.toString()
        writer.execute {
            try {
                Files.createDirectories(f.parent)
                Persistence.writeAtomic(f, text, sync = true)
            } catch (e: Exception) {
                val line = "the notice cursor could not be saved (${e::class.simpleName}: ${e.message}); a restart adopts again"
                Log.e(TAG, line)
                say(line)
            }
        }
    }

    private fun loadCursor(): Cursor? {
        val f = cursorFile ?: return null
        if (!Files.exists(f)) return null
        return try {
            val o = Json.parseToJsonElement(Files.readString(f)) as JsonObject
            Cursor((o["epoch"] as JsonPrimitive).longOrNull!!, (o["occ"] as JsonPrimitive).longOrNull!!)
        } catch (e: Exception) {
            val line = "the notice cursor ${f.fileName} does not read (${e::class.simpleName}: ${e.message}); adopting again"
            Log.e(TAG, line)
            say(line)
            null
        }
    }

    companion object {
        private const val TAG = "notices"
        /** The phone's cursor file, beside its working copy (`workcopy/`). */
        const val CURSOR_FILE = "notice-cursor.json"
    }
}
