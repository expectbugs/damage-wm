package wm.damage.core.windows.files

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1, §5 F1 and F3; decisions A.4; S1-2 R0 §3): the PC's filesystem provider
 * behind the one job slot ([FilesJobs]). Every PC caller receives this, never the bare provider: the PC's glasses
 * presenter (today's calls, A.4), `FilesService` on the window channel (so the phone's glasses meet the same slot),
 * and the web's program ops. Reads (locations, listings, viewers, the trash's listing) pass through untouched.
 *
 * The cut's clipboard is cleared in ONE place (R0 §3): when a move — the paste of a cut, from any caller — has landed,
 * [onMoved] (the core's clear, expecting the clip it took) is told its source path. A clip changed meanwhile wins.
 */
class SlottedFiles(
    /** The provider the slot is in front of (`LocalFilesProvider` on the PC). */
    val raw: FilesProvider,
    val jobs: FilesJobs,
    /** S1-2 R0 §3: a move of [String] (the source path) has landed — the core clears a cut clip that names it. */
    private val onMoved: (String) -> Unit = {},
) : FilesProvider by raw {

    /** The PC's Files this provider belongs to, when it is the PC's (a host builder's stack builds its glasses
     *  presenter on that record through it); null in a tool or a test of the slot alone. */
    @Volatile var pc: FilesPc? = null
        internal set

    /** Where a job started from a program op runs once its op has been answered (one at a time: the slot). */
    private val runner: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "files-job").apply { isDaemon = true } }
    }

    override fun copy(src: String, destDir: String): String =
        jobs.run("Paste '${name(src)}'", "copying", ::pathAnswer) { h -> raw.copyCounted(src, destDir, h.asProgress) }

    override fun move(src: String, destDir: String): String =
        jobs.run("Paste '${name(src)}'", "moving", ::pathAnswer) { h ->
            raw.moveCounted(src, destDir, h.asProgress).also { moved(src) }
        }

    override fun trash(path: String): String = jobs.run("Move to Trash '${name(path)}'", "trashing") { raw.trash(path) }

    override fun restore(id: String): String = jobs.run("Restore", "restoring", ::pathAnswer) { raw.restore(id) }

    override fun purge(id: String) = jobs.run("Delete forever", "deleting") { raw.purge(id) }

    override fun rename(path: String, newName: String): String =
        jobs.run("Rename '${name(path)}'", "renaming", ::pathAnswer) { raw.rename(path, newName) }

    override fun mkdir(dir: String, name: String): String =
        jobs.run("New folder '$name'", "creating", ::pathAnswer) { raw.mkdir(dir, name) }

    override fun du(path: String): Long =
        jobs.run("Stats '${name(path)}'", "counting") { h -> raw.duCounted(path, h.asProgress) }

    /**
     * A job for a program op: the slot is taken now, on the caller's thread ([FilesJobs.Busy] with today's words while
     * another runs), and [work] runs on this provider's own job thread after the op is answered; its return is the
     * job's answer, a throw its failure. Returns the job's id.
     */
    fun start(label: String, phase: String, work: (FilesJobs.Handle) -> JsonElement?): String {
        val h = jobs.begin(label, phase)
        try {
            runner.execute {
                val answer = try {
                    work(h)
                } catch (e: Throwable) {
                    h.failed(e.message ?: e::class.simpleName ?: "failed")
                    return@execute
                }
                h.done(answer)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            h.failed("the PC is stopping; $label was not run")
            throw e
        }
        return h.id
    }

    /** The core's clear after a move that landed; a failure to clear is said, never thrown into the paste. */
    internal fun moved(src: String) = try {
        onMoved(src)
    } catch (e: Exception) {
        Log.e(TAG, "the clipboard could not be cleared after moving $src", e)
    }

    companion object {
        private const val TAG = "files-slot"

        fun name(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

        /** A job's answer for a call that returns a path: the words the page shows, and the path. */
        fun pathAnswer(path: String): JsonElement = buildJsonObject {
            put("text", "→ $path")
            put("path", path)
        }

        fun words(s: String): JsonElement = JsonPrimitive(s)
    }
}
