package wm.damage.core

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.comp.Journal
import wm.damage.core.util.InstanceLock
import wm.damage.core.util.Log

/**
 * R2 item 19 (review 1b RE-3 with RF-2 (b)): one process per data directory on the PC. A second
 * shell or `--host-only` on the service's directory would append to its journal, rename it past
 * 64 MiB and take its pending uncaught-error record; the first statement of `runShell` and of
 * `hostOnly` claims `instance.lock` there and a start that finds it held exits with
 * [InstanceLock.EXIT_HELD] and starts nothing.
 *
 * Every file here is in a temp directory of this test; nothing names the real data directory and
 * the program is never started. Within one JVM the refused arm is reached two ways: a second claim
 * of this process (refused before a second descriptor is opened) and a lock held through another
 * channel (the JVM raises `OverlappingFileLockException`). The cross-process arm (`tryLock()`
 * returns null) is not reached here. No pin waits: each reads a return value, a file, a captured
 * stream or `/proc/locks` once.
 */
class InstanceLockTest {
    private val dir: Path = Files.createTempDirectory("instance-lock")
    private val at = LocalDateTime.of(2026, 11, 11, 20, 0, 0)

    @AfterTest fun clean() { dir.toFile().deleteRecursively() }

    private class Exited(val code: Int) : RuntimeException("exit $code")

    /** Does `/proc/locks` list a POSIX lock of THIS process on [file]? (Linux; the kernel's own table,
     *  which is what another process meets.) */
    private fun kernelHolds(file: Path): Boolean {
        val ino = (Files.getAttribute(file, "unix:ino") as Number).toLong()
        val pid = ProcessHandle.current().pid()
        return Files.readAllLines(Path.of("/proc/locks")).any { l ->
            val f = l.trim().split(Regex("\\s+"))
            f.size >= 6 && f[1] == "POSIX" && f[4] == pid.toString() && f[5].substringAfterLast(':') == ino.toString()
        }
    }

    @Test
    fun aSecondClaimOnOneDirectoryIsRefusedUntilTheFirstIsClosed() {
        val first = assertNotNull(InstanceLock.tryClaim(dir), "the first claim is granted")
        val file = dir.resolve(InstanceLock.FILE)
        assertTrue(Files.isRegularFile(file), "a dedicated lock file, in the data directory")
        assertTrue(kernelHolds(file), "the kernel holds the lock for this process")
        assertNull(InstanceLock.tryClaim(dir), "a second claim on the directory is refused")
        assertNull(InstanceLock.tryClaim(dir.resolve("sub").resolve("..")), "and on the directory spelled another way")
        // a refused claim must not open and close a second descriptor of the file: on Linux closing ANY
        // descriptor of a file drops every POSIX lock this process holds on it, and the first claim
        // would then refuse nobody in another process
        assertTrue(kernelHolds(file), "the refused claims left the first claim's lock in the kernel")
        val other = Files.createDirectories(dir.resolve("another-data-dir"))
        assertNotNull(InstanceLock.tryClaim(other), "another directory is another claim").close()
        first.close()
        assertTrue(!kernelHolds(file), "closed: the kernel no longer holds it")
        val again = assertNotNull(InstanceLock.tryClaim(dir), "granted again after close()")
        again.close()
    }

    @Test
    fun aHeldDirectoryEndsTheStartBeforeTheJournalIsTouched() {
        val live = dir.resolve("journal.jsonl")
        val old = "x".repeat(50)
        Files.writeString(live, old)                       // past the limit the start below asks with
        val file = dir.resolve(InstanceLock.FILE)
        val err = ByteArrayOutputStream()
        // the lock held through a channel of the test's own: the claim meets OverlappingFileLockException
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { theirs ->
            val lock = theirs.lock()
            val e = assertFailsWith<Exited> {
                InstanceLock.claimOrExit(dir, PrintStream(err, true)) { throw Exited(it) }
                Journal.rotateAtStart(live, limit = 10, now = at)      // the start's next step: never reached
            }
            assertEquals(InstanceLock.EXIT_HELD, e.code, "the start exits with the held status")
            assertEquals(4, InstanceLock.EXIT_HELD)
            lock.release()
        }
        assertEquals(old, Files.readString(live), "the journal past the limit is NOT renamed")
        assertEquals(listOf(InstanceLock.FILE, "journal.jsonl"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
        val lines = err.toString().lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size, "one line on stderr: $lines")
        assertTrue(dir.toString() in lines[0] && InstanceLock.FILE in lines[0], "it names the directory and the lock file: ${lines[0]}")

        // the control: with nobody holding it the same start goes on to the rotation
        val claim = assertNotNull(InstanceLock.claimOrExit(dir, PrintStream(ByteArrayOutputStream(), true)) { throw Exited(it) })
        assertNotNull(Journal.rotateAtStart(live, limit = 10, now = at), "granted: the rotation runs")
        claim.close()
    }

    @Test
    fun aLockThatCannotBeTriedIsSaidAndTheStartGoesOn() {
        val notADir = dir.resolve("a-file").also { Files.writeString(it, "x") }
        val err = ByteArrayOutputStream()
        val got = InstanceLock.claimOrExit(notADir, PrintStream(err, true)) { throw Exited(it) }
        assertNull(got, "no claim, and no exit: the service must start")
        assertEquals(1, Log.recent().count { " E instance: " in it && notADir.toString() in it && "goes on without it" in it },
            Log.recent().takeLast(5).toString())
    }
}
