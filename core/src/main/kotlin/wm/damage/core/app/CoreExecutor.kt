package wm.damage.core.app

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (`CONTRACT.md` §2.2, §14 (b) "one core executor per host — not a thread per window"): the
 * one thread every window core's state is written on. Today's single-writer rule (`WindowContract.kt`, "the shell
 * loop only") carried over to the cores: ops, saves and snapshots for the record all run here, one at a time, in the
 * order they were handed in.
 */
class CoreExecutor(name: String = "damage-core") {
    @Volatile private var thread: Thread? = null

    private val ex: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true; thread = this }
    }

    /** True on the executor's thread. */
    fun isCurrent(): Boolean = Thread.currentThread() === thread

    /** Runs [task] on the executor after everything handed in before it. A task that throws is said (`Log.e`, with
     *  the task's [what]) and the next task runs: one window's fault never stops the others. */
    fun execute(what: String, task: () -> Unit) {
        try {
            ex.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    Log.e(TAG, "a core task failed ($what)", t)
                }
            }
        } catch (e: Exception) {
            // after shutdown: said, never silent
            Log.e(TAG, "the core executor refused a task ($what)", e)
        }
    }

    /** Runs [task] on the executor and returns its value; on the executor itself it runs in place (no wait on
     *  itself). A throw reaches the caller. For a process start and for tests, never for a gesture's first flush. */
    fun <T> call(task: () -> T): T {
        if (isCurrent()) return task()
        try {
            return ex.submit(Callable { task() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun shutdown() {
        ex.shutdown()
    }

    private companion object {
        const val TAG = "core"
    }
}
