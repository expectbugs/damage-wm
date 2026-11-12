package wm.damage.core.util

import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * One process per data directory on the PC (APK 0.64 R2 item 19; review 1b RE-3 with RF-2 (b)).
 *
 * Context for the reader: a personal device; Damage's own PC service. Since 0.64 P3 a process start
 * writes into the data directory before anything else: it appends a `process` note to the journal,
 * renames the journal past 64 MiB and takes the last run's uncaught-error record. A second shell or
 * `--host-only` on the directory the service holds (a dev session without the stop, a stray start)
 * would do all three under the running service. So the first statement of the desktop's `runShell`
 * and `hostOnly` is [claimOrExit]: an exclusive lock on [FILE] in the data directory; a start that
 * finds it held says so in one stderr line and exits with [EXIT_HELD], and nothing else starts.
 *
 *  - A DEDICATED file, never the journal: on Linux closing ANY descriptor of a file drops every
 *    POSIX lock the process holds on it, and the journal is closed at every session stop.
 *  - The claim is held in [held] for the life of the process: a channel that is collected is
 *    closed, and the kernel drops the lock.
 *  - The kernel releases it when the process ends for any reason; a file left behind holds nothing.
 *  - Tools (`--selfcheck`, `--snapshot`, the `--*-check` entries) never take it
 *    (`tools/check_apk_wiring.py` holds the callers to the two starts).
 *  - The phone is one process by construction and takes none.
 */
object InstanceLock {
    /** The lock file, in the data directory. Its bytes are never read or written. */
    const val FILE = "instance.lock"

    /** The exit status of a start refused because another process holds the directory. The desktop's
     *  others: 0 help or a clean end, 1 a tool's failure, 2 a refused argument list, 3 the JVM's own
     *  (`-XX:+ExitOnOutOfMemoryError`). */
    const val EXIT_HELD = 4

    /** This process's claims, by the lock file's absolute normalised path: the strong reference that
     *  keeps each channel (and so its lock) open, and the in-process "held" answer that never opens a
     *  second descriptor of a file this process has locked. */
    private val held = ConcurrentHashMap<Path, Claim>()

    /** A granted claim. [close] releases it (tests; production holds it until the process ends). */
    class Claim internal constructor(val file: Path, private val channel: FileChannel) : AutoCloseable {
        override fun close() {
            held.remove(file, this)
            channel.close()                 // releases the lock
        }
    }

    /**
     * Takes the exclusive lock on [FILE] in [dir]. Returns the claim, or null when it is held — by
     * another process (`tryLock()` answers null) or within this JVM (a claim of this process, or a
     * lock taken through another channel: `OverlappingFileLockException`). Throws what opening the
     * file or trying the lock throws (an `IOException` when the directory cannot be made or the file
     * system refuses locks); [claimOrExit] says that and goes on.
     */
    fun tryClaim(dir: Path): Claim? {
        val file = dir.resolve(FILE).toAbsolutePath().normalize()
        if (held.containsKey(file)) return null
        Files.createDirectories(file.parent)
        val ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try {
            ch.tryLock()
        } catch (e: OverlappingFileLockException) {
            null
        } catch (t: Throwable) {
            ch.close(); throw t
        }
        if (lock == null) { ch.close(); return null }
        return Claim(file, ch).also { held[file] = it }
    }

    /**
     * The first statement of the PC's two process starts, once the config is read. Held → one line
     * on [err] naming the directory and the lock file, then [exit] with [EXIT_HELD] (it does not
     * return). A lock that cannot be tried → one ERROR line and null: the start goes on without the
     * guard, as before it existed (the service must start). Granted → the claim.
     */
    fun claimOrExit(dir: Path, err: PrintStream = System.err, exit: (Int) -> Nothing = { exitProcess(it) }): Claim? {
        val claim = try {
            tryClaim(dir)
        } catch (e: Exception) {
            Log.e("instance", "the instance lock ${dir.resolve(FILE)} could not be tried (${e::class.simpleName}: ${e.message}) — " +
                "this process goes on without it: a second process on this directory is not refused")
            return null
        }
        if (claim == null) {
            err.println("damage: another process holds the data directory $dir (its lock file ${dir.resolve(FILE)}) — " +
                "nothing is started here (exit $EXIT_HELD). Stop that one first (the service: rc-service damage stop), " +
                "or start this one under a scratch -Duser.home whose config.json names another dataDir.")
            err.flush()
            exit(EXIT_HELD)
        }
        return claim
    }
}
