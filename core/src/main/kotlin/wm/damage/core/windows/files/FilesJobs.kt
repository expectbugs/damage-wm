package wm.damage.core.windows.files

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1 "One job at a time per PC", §5 F1; `CONTRACT.md` §3.2 job, §12 Files;
 * decisions A.12; S1-2 R0 §3): the PC's one Files job slot. Paste (copy, move), trash, restore, purge, rename, new
 * folder, stats (`du`) and an upload (from its op to the end of its body) each hold it; a second request while one
 * runs is refused with today's words, "busy — one operation at a time". Every PC caller meets the same slot
 * (`SlottedFiles`): the PC's glasses, the phone's glasses through the window channel, and the web's program ops.
 *
 * A running job is freed by its own end, or (L1, the ruling's slot item) by the ONE recovery, [supervise], on the
 * PC's pacing tick: a job that writes nothing and has made no progress for its limit ([Handle.endAfter]: a `stats`,
 * an upload whose body has not begun) is ended as failed, said, and the slot freed. A job that moves, copies,
 * trashes, restores, deletes, renames or makes a folder has no limit and is never ended or freed by it; its busy line
 * says how long it has made no progress ([busyLine]): freeing the slot while such a job may still run would let a
 * second op touch the same files. A finished job stays in the slot, with its answer, until the
 * next one replaces it (R0 §3: a page reads a job's end from the slot). A restart starts with an empty slot.
 */
class FilesJobs(private val clock: () -> Long = { System.currentTimeMillis() }) {
    /** One job as `desc/files` carries it (C§3.2). [answer] is the end's words or facts (null while it runs). */
    data class Job(
        val id: String,
        val label: String,
        val state: String,
        val fraction: Double?,
        val phase: String,
        val startedAt: Long,
        val updatedAt: Long,
        val answer: JsonElement?,
        /** L1: how long [supervise] lets this job make no progress before it ends it; null = never. */
        val endAfterMs: Long? = null,
    ) {
        val running: Boolean get() = state == RUNNING

        fun json(): JsonObject = buildJsonObject {
            put("id", id)
            put("label", label)
            put("state", state)
            fraction?.let { put("fraction", it) }
            put("phase", phase)
            put("startedAt", startedAt)
            put("updatedAt", updatedAt)
            put("answer", answer ?: JsonNull)
        }
    }

    /** A request refused because a job holds the slot: today's words. */
    class Busy(val holder: Job) : IllegalStateException(BUSY)

    /** The running job's handle: its progress and its one end. A second end is ignored (said in the log). */
    inner class Handle internal constructor(val id: String) {
        fun progress(done: Long, total: Long?, phase: String) {
            val f = if (total != null && total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else null
            update(id, f, phase)
        }

        fun done(answer: JsonElement?) = end(id, DONE, answer)

        fun failed(reason: String) = end(id, FAILED, kotlinx.serialization.json.JsonPrimitive(reason))

        /** L1: this job writes nothing — [supervise] may end it after [ms] with no progress, asking [stop] first
         *  (false: it writes now and runs to its end). Never called for a job that moves or changes a file. */
        fun endAfter(ms: Long, stop: () -> Boolean = { true }) = synchronized(lock) {
            slot?.takeIf { it.id == id && it.running }?.let { slot = it.copy(endAfterMs = ms); stopper = stop }
        }

        /** As a [FilesProgress] for the provider's counted calls. */
        val asProgress: FilesProgress get() = FilesProgress { done, total, phase -> progress(done, total, phase) }
    }

    private val lock = Any()
    private var slot: Job? = null
    /** What [supervise] asks before it ends the running job ([Handle.endAfter]'s `stop`). */
    private var stopper: () -> Boolean = { true }
    /** Job ids grow across restarts (seeded from the clock), so a page holding an id of an earlier process never
     *  matches a new job. */
    private var next = clock()
    private var lastSaid = 0L
    private val listeners = CopyOnWriteArrayList<(Job) -> Unit>()

    /** [l] is told each change of the slot: a begin and an end at once, progress at most once a second. Any thread. */
    fun addListener(l: (Job) -> Unit) {
        listeners += l
    }

    /** The slot now: the running job, or the last one to end, or null. */
    fun current(): Job? = synchronized(lock) { slot }

    /** The slot as `desc/files`'s `jobs`. */
    fun json(): JsonArray = buildJsonArray { current()?.let { add(it.json()) } }

    /** Takes the slot for [label], or throws [Busy] with today's words while a job runs. */
    fun begin(label: String, phase: String): Handle {
        val j = synchronized(lock) {
            slot?.takeIf { it.running }?.let { throw Busy(it) }
            val now = clock()
            Job("j${++next}", label, RUNNING, null, phase, now, now, null).also { slot = it; lastSaid = now }
        }
        tell(j)
        return Handle(j.id)
    }

    /** L1, the slot's one recovery (the header says what it ends and why the rest never): on the PC's pacing tick. */
    fun supervise() {
        val (j, stop) = synchronized(lock) {
            val j = slot?.takeIf { it.running && it.endAfterMs != null } ?: return
            if (clock() - j.updatedAt < j.endAfterMs!!) return
            j to stopper
        }
        if (!stop()) return                    // an upload's body began meanwhile: it writes now and runs to its end
        end(j.id, FAILED, kotlinx.serialization.json.JsonPrimitive("ended by the supervisor: no progress for ${j.endAfterMs!! / 60_000} min"))
    }

    /** The words of a refusal while [holder] runs: today's, its label, and how long it has made no progress. */
    fun busyLine(holder: Job): String {
        val idle = (clock() - holder.updatedAt) / 60_000
        return "$BUSY (${holder.label} is running" + (if (idle >= 1) "; no progress for $idle min" else "") + ")"
    }

    /** [work] in the slot, on the caller's thread: its end is the job's (a throw is a failed job, then thrown on). */
    fun <T> run(label: String, phase: String, answer: (T) -> JsonElement? = { null }, work: (Handle) -> T): T {
        val h = begin(label, phase)
        val r = try {
            work(h)
        } catch (e: Throwable) {
            h.failed(e.message ?: e::class.simpleName ?: "failed")
            throw e
        }
        h.done(try { answer(r) } catch (e: Exception) { null })
        return r
    }

    private fun update(id: String, fraction: Double?, phase: String) {
        val said = synchronized(lock) {
            val j = slot?.takeIf { it.id == id && it.running } ?: return
            val now = clock()
            slot = j.copy(fraction = fraction ?: j.fraction, phase = phase, updatedAt = now)
            if (now - lastSaid < SAY_EVERY_MS) null else slot.also { lastSaid = now }
        }
        said?.let(::tell)
    }

    private fun end(id: String, state: String, answer: JsonElement?) {
        val j = synchronized(lock) {
            val j = slot?.takeIf { it.id == id }
            if (j == null || !j.running) {
                Log.w(TAG, "job $id ended twice, or after the slot moved on (${j?.state}): the second end is not taken")
                return
            }
            j.copy(state = state, updatedAt = clock(), answer = answer, fraction = if (state == DONE) 1.0 else j.fraction)
                .also { slot = it }
        }
        if (state == FAILED) Log.w(TAG, "${j.label}: failed — ${(answer as? kotlinx.serialization.json.JsonPrimitive)?.content}")
        tell(j)
    }

    private fun tell(j: Job) {
        for (l in listeners) try {
            l(j)
        } catch (e: Exception) {
            Log.e(TAG, "a job listener failed", e)
        }
    }

    companion object {
        private const val TAG = "files-jobs"
        const val BUSY = "busy — one operation at a time"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        /** `desc/files` is described again on a job's progress at most this often (the spec's §4.1). */
        const val SAY_EVERY_MS = 1_000L
        /** L1 (the ruling): the supervisor's limits — an upload ticket whose body has not begun, a read-only job. */
        const val UPLOAD_WAIT_MS = 2 * 60_000L
        const val READ_ONLY_MS = 10 * 60_000L
    }
}
