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
 * Nothing frees a running job but its own end: a `du` stalled on a mount holds the slot until the process ends, and
 * the job's age is shown meanwhile (A.12; no timeout). A finished job stays in the slot, with its answer, until the
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

    /** A request refused because a job holds the slot: [busyLine]'s words (L1 A-N12: the PC's glasses show them, and the
     *  phone's through the window channel). */
    class Busy(val holder: Job, line: String) : IllegalStateException(line)

    /** The running job's handle: its progress and its one end. A second end is ignored (said in the log). */
    inner class Handle internal constructor(val id: String) {
        fun progress(done: Long, total: Long?, phase: String) {
            val f = if (total != null && total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else null
            update(id, f, phase)
        }

        fun done(answer: JsonElement?) = end(id, DONE, answer)

        fun failed(reason: String) = end(id, FAILED, kotlinx.serialization.json.JsonPrimitive(reason))

        /** As a [FilesProgress] for the provider's counted calls. */
        val asProgress: FilesProgress get() = FilesProgress { done, total, phase -> progress(done, total, phase) }
    }

    private val lock = Any()
    private var slot: Job? = null
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
            slot?.takeIf { it.running }?.let { throw Busy(it, busyLine(it)) }
            val now = clock()
            Job("j${++next}", label, RUNNING, null, phase, now, now, null).also { slot = it; lastSaid = now }
        }
        tell(j)
        return Handle(j.id)
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
    }
}
