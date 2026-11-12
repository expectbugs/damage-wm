package wm.damage.core

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.util.UncaughtRecord

/**
 * `HANDOFF.md` §75.1 (Adam's ruling), APK 0.64: a record of an uncaught error — written by a process
 * default handler, journaled and said once by the next start (`UncaughtRecord`).
 *
 * Every file is in a temp directory of this test. Only one test installs a process default handler
 * and it puts back the one that was there in a `finally`; the others call the handler object
 * itself. No pin decides on elapsed time: each reads a file, a return value, a captured stream, or
 * waits for one of two states that do not change once reached.
 */
class UncaughtRecordTest {
    private val dir: Path = Files.createTempDirectory("uncaught-record")
    private val pending: Path get() = dir.resolve(UncaughtRecord.PENDING)
    private val journal: Path get() = dir.resolve("journal.jsonl")

    @AfterTest fun clean() { dir.toFile().deleteRecursively() }

    /** Every fault of this test is made on this one line, so two of them share a top frame. Faults
     *  that must be "the same again" are also made from ONE call line (R2, RF-3: the first 32 frames
     *  are compared, so the caller's line counts). */
    private fun fault(message: String): Throwable = IllegalStateException(message)

    private class Handed : Thread.UncaughtExceptionHandler {
        val got = LinkedBlockingQueue<Pair<String, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) { got.put(t.name to e) }
    }

    private fun <T> capturingErr(body: () -> T): Pair<T, String> {
        val orig = System.err
        val buf = ByteArrayOutputStream()
        System.setErr(PrintStream(buf, true))
        try { return body() to buf.toString() } finally { System.setErr(orig) }
    }

    private fun uncaughtNotes(p: Path = journal): List<String> =
        if (!Files.exists(p)) emptyList() else Files.readAllLines(p).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["kind"]!!.jsonPrimitive.content == "uncaught" }.map { it["detail"]!!.jsonPrimitive.content }

    private fun kept(): List<String> = Files.list(dir).use { s ->
        s.map { it.fileName.toString() }.filter { it.startsWith("uncaught-") && it != UncaughtRecord.PENDING }.sorted().toList()
    }

    private fun headers(text: String): Int = Regex("^=== uncaught ", RegexOption.MULTILINE).findAll(text).count()

    @Test
    fun theTraceIsOnDiskBeforeThePreviousHandlerRuns() {
        var seenByPrevious: String? = null
        var handedOn: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e ->
            handedOn = e
            seenByPrevious = if (Files.exists(pending)) Files.readString(pending) else "(no file yet)"
        }
        val h = UncaughtRecord.handlerFor(dir, "test build 7", previous)
        val thrown = fault("marker-on-disk-first")
        val t = Thread({ throw thrown }, "failing-thread-1").apply { uncaughtExceptionHandler = h }
        t.start(); t.join()
        assertSame(thrown, handedOn, "the handler that was there gets the same throwable")
        val text = assertNotNull(seenByPrevious)
        val head = assertNotNull(Regex("""^=== uncaught (\S+) thread "failing-thread-1" build "test build 7" ===$""", RegexOption.MULTILINE).find(text),
            "at the moment the previous handler ran, the file already held the header: $text")
        OffsetDateTime.parse(head.groupValues[1])           // a time that parses
        assertTrue("java.lang.IllegalStateException: marker-on-disk-first" in text, "the class and the message: $text")
        assertTrue("\tat wm.damage.core.UncaughtRecordTest.fault(UncaughtRecordTest.kt:" in text, "and the frames: $text")
    }

    @Test
    fun anUncaughtThrowableOnAnyThreadReachesItThroughInstall() {
        val orig = Thread.getDefaultUncaughtExceptionHandler()
        val there = Handed()                                   // "the handler that was there"
        val other = Files.createTempDirectory("uncaught-record-other")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun awaitHanded(marker: String) {
            while (true) {      // a leftover thread of another test may fail meanwhile: only ours counts
                val e = assertNotNull(there.got.poll(20, TimeUnit.SECONDS), "the handler that was there never got '$marker'")
                if (e.second.message == marker) return
            }
        }
        try {
            Thread.setDefaultUncaughtExceptionHandler(there)
            UncaughtRecord.install(dir, "test build")
            assertEquals("uncaught-error record installed (previous handler ${there.javaClass.name})", UncaughtRecord.installedLine())
            UncaughtRecord.install(other, "a second call")      // a no-op while the record is the default
            Thread({ throw fault("from-a-plain-thread") }, "plain-thread").start()
            awaitHanded("from-a-plain-thread")
            scope.launch { throw fault("from-a-coroutine") }
            awaitHanded("from-a-coroutine")
            val text = Files.readString(pending)
            assertTrue("thread \"plain-thread\"" in text && "IllegalStateException: from-a-plain-thread" in text, text)
            assertTrue("IllegalStateException: from-a-coroutine" in text, "a coroutine in a scope with no handler reaches it too: $text")
            assertFalse(Files.exists(other.resolve(UncaughtRecord.PENDING)), "the second install did not wrap the first")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(orig)
            scope.cancel()
            other.toFile().deleteRecursively()
        }
        assertNull(UncaughtRecord.installedLine(), "the line reads the process's handler as it is now, not a flag set at install")
    }

    @Test
    fun theHandlerNeverThrowsAndStillHandsOn() {
        val notADir = dir.resolve("a-file").also { Files.writeString(it, "x") }
        val there = Handed()
        val h = UncaughtRecord.handlerFor(notADir, "b", there)
        val thrown = fault("nowhere-to-write")
        val (_, err) = capturingErr { h.uncaughtException(Thread("t"), thrown) }       // returns normally
        assertSame(thrown, there.got.poll()?.second, "the handler that was there still ran")
        assertTrue("damage: the uncaught-error record was not written" in err, "and the one place left says so: $err")
    }

    @Test
    fun withNoPreviousHandlerTheTraceStillReachesStderr() {
        val h = UncaughtRecord.handlerFor(dir, "b", null)
        val (_, err) = capturingErr { h.uncaughtException(Thread("lonely-thread"), fault("printed-as-the-jvm-would")) }
        assertTrue(err.startsWith("Exception in thread \"lonely-thread\" java.lang.IllegalStateException: printed-as-the-jvm-would"), err)
        assertTrue("\tat wm.damage.core.UncaughtRecordTest.fault(" in err, err)
    }

    @Test
    fun aRepeatingFaultIsBounded() {
        val there = Handed()
        val h = UncaughtRecord.handlerFor(dir, "b", there)
        val (_, err) = capturingErr { repeat(20) { h.uncaughtException(Thread("t$it"), fault("again-$it")) } }
        val text = Files.readString(pending)
        assertEquals(UncaughtRecord.MAX_PER_PROCESS, headers(text), "8 records for one process")
        assertEquals(20, there.got.size, "every one was still handed on")
        val closing = "--- not recorded past this line: 8 records in this process ---"
        assertEquals(1, text.lines().count { it == closing }, "one closing line in the file: ${text.takeLast(300)}")
        assertTrue(text.endsWith("$closing\n"))
        assertEquals(1, err.lines().count { "uncaught errors are no longer recorded in ${UncaughtRecord.PENDING}" in it }, "and one on stderr: $err")

        // a start loop that never reaches the pickup: process after process appends to one file
        Files.write(pending, ByteArray((UncaughtRecord.MAX_PENDING_BYTES - 100).toInt()) { 'x'.code.toByte() })
        val first = UncaughtRecord.handlerFor(dir, "b", there)             // "the next process"
        repeat(3) { first.uncaughtException(Thread("t"), fault("past-the-limit")) }
        val after = Files.readString(pending)
        assertEquals(0, headers(after), "a file at its limit takes no record")
        assertTrue(after.endsWith("x\n--- not recorded past this line: the file's limit of 262144 B ---\n"), after.takeLast(120))
        val size = Files.size(pending)
        val second = UncaughtRecord.handlerFor(dir, "b", there)            // "and the one after it"
        repeat(3) { second.uncaughtException(Thread("t"), fault("past-the-limit")) }
        assertEquals(size, Files.size(pending), "a file that already ends with the closing line gains nothing")
        assertTrue(size <= UncaughtRecord.MAX_PENDING_BYTES, "$size B")
    }

    @Test
    fun aRecordThatReachesTheLimitIsCutThereAndSaysSo() {
        Files.write(pending, ByteArray((UncaughtRecord.MAX_PENDING_BYTES - 1024).toInt()) { 'x'.code.toByte() } + "\n".toByteArray())
        val h = UncaughtRecord.handlerFor(dir, "b", Handed())
        val (_, err) = capturingErr { h.uncaughtException(Thread("t"), fault("m".repeat(20_000))) }
        val text = Files.readString(pending)
        assertTrue(Files.size(pending) <= UncaughtRecord.MAX_PENDING_BYTES, "the file never passes its limit: ${Files.size(pending)} B")
        assertEquals(1, headers(text), "the header is whole")
        assertTrue(text.endsWith("\n--- not recorded past this line: the file's limit of 262144 B (the record above is cut) ---\n"), text.takeLast(160))
        assertTrue("no longer recorded" in err, err)

        // the cut closed the record for this process: later faults add nothing to the file and say
        // nothing more on stderr (0.64 P3 verify: once per process had no pin on this arm)
        val (_, later) = capturingErr { repeat(2) { h.uncaughtException(Thread("t"), fault("after the cut")) } }
        assertEquals(text, Files.readString(pending), "the file is as the cut left it")
        assertEquals(0, later.lines().count { "no longer recorded" in it }, "said once for the process, at the cut: $later")
    }

    /**
     * R2 (review 1b RF-6): the cut at the file's limit stays, and the root cause's name survives it — one
     * line under the header, only when the throwable has a cause, its message capped at 300 characters
     * with the full length said, written only when building it did not throw.
     */
    @Test
    fun aCutRecordStillNamesItsRootCause() {
        Files.write(pending, ByteArray((UncaughtRecord.MAX_PENDING_BYTES - 1024).toInt()) { 'x'.code.toByte() } + "\n".toByteArray())
        val h = UncaughtRecord.handlerFor(dir, "b", Handed())
        capturingErr { h.uncaughtException(Thread("t"), RuntimeException("outer", RuntimeException("middle", fault("the cause")))) }
        val text = Files.readString(pending)
        assertTrue(text.endsWith("(the record above is cut) ---\n"), "the record is cut: ${text.takeLast(200)}")
        val body = text.substringAfter("=== uncaught ").substringAfter("===\n")
        assertTrue(body.startsWith("--- root cause: java.lang.IllegalStateException: the cause ---\n"), body.take(200))
        // the record still reads as the OUTER throwable's (the line is skipped as the closing line is)
        Files.delete(pending)
        h.uncaughtException(Thread("t2"), RuntimeException("outer", fault("m".repeat(20_000))))
        val line = assertNotNull(UncaughtRecord.takePending(dir, journal))
        assertTrue(line.startsWith("an uncaught java.lang.RuntimeException in thread t2 at "), line)
        val capped = uncaughtNotes().single().lines()[1]
        assertEquals("--- root cause: java.lang.IllegalStateException: ${"m".repeat(300)} … (20000 characters; the whole text is in the trace below) ---", capped)

        // no cause, no line; a message that throws when the line asks for it costs the line and nothing
        // else (it answers the trace's own ask after that: printStackTrace reads it again)
        val alone = UncaughtRecord.handlerFor(Files.createDirectories(dir.resolve("alone")), "b", Handed())
        alone.uncaughtException(Thread("t"), fault("no cause"))
        assertFalse("--- root cause" in Files.readString(dir.resolve("alone").resolve(UncaughtRecord.PENDING)))
        val odd = object : RuntimeException("odd") {
            var asked = 0
            override val message: String get() = if (asked++ == 0) throw IllegalStateException("no message") else "odd"
        }
        val throwing = UncaughtRecord.handlerFor(Files.createDirectories(dir.resolve("throwing")), "b", Handed())
        capturingErr { throwing.uncaughtException(Thread("t"), RuntimeException("outer", odd)) }
        val t = Files.readString(dir.resolve("throwing").resolve(UncaughtRecord.PENDING))
        assertFalse("--- root cause" in t, t.take(300))
        assertTrue(t.lines()[1].startsWith("java.lang.RuntimeException: outer"), "the trace follows the header: ${t.take(300)}")
    }

    @Test
    fun theNextStartSaysItOnce() {
        val h = UncaughtRecord.handlerFor(dir, "test build", Handed())
        h.uncaughtException(Thread("worker-3"), RuntimeException("outer", fault("the cause")))
        h.uncaughtException(Thread("worker-4"), fault("a second one"))
        val text = Files.readString(pending)
        val at = OffsetDateTime.parse(Regex("=== uncaught (\\S+) ").find(text)!!.groupValues[1]).toLocalDateTime()
        val stamp = "%04d%02d%02d-%02d%02d%02d".format(at.year, at.monthValue, at.dayOfMonth, at.hour, at.minute, at.second)
        val said = "%02d-%02d %02d:%02d:%02d".format(at.monthValue, at.dayOfMonth, at.hour, at.minute, at.second)

        val line = UncaughtRecord.takePending(dir, journal)
        assertEquals("an uncaught java.lang.RuntimeException in thread worker-3 at $said during the last run — " +
            "the trace is in the journal (an uncaught note) and in uncaught-$stamp.txt (2 records)", line)
        assertEquals(listOf(text), uncaughtNotes(), "ONE note, and it carries the whole record (Caused by and all)")
        assertTrue("Caused by: java.lang.IllegalStateException: the cause" in text)
        assertFalse(Files.exists(pending), "the pending name is gone")
        assertEquals(listOf("uncaught-$stamp.txt"), kept())
        assertEquals(text, Files.readString(dir.resolve("uncaught-$stamp.txt")), "the kept file holds the same bytes")

        assertNull(UncaughtRecord.takePending(dir, journal), "a second start says nothing")
        assertEquals(1, uncaughtNotes().size, "and journals nothing")
        assertNull(UncaughtRecord.takePending(Files.createDirectories(dir.resolve("empty")), null), "no pending file: nothing")
    }

    /** NIT 17: the record is marked said only when its note landed. */
    @Test
    fun aNoteThatDidNotLandKeepsTheRecordPending() {
        UncaughtRecord.handlerFor(dir, "b", Handed()).uncaughtException(Thread("w"), fault("kept until it is journaled"))
        val text = Files.readString(pending)
        val blocker = dir.resolve("a-file").also { Files.writeString(it, "x") }
        val line = assertNotNull(UncaughtRecord.takePending(dir, blocker.resolve("journal.jsonl")))
        assertTrue("NOT journaled" in line && "stays in ${UncaughtRecord.PENDING}" in line, line)
        assertEquals(text, Files.readString(pending), "still pending, untouched")
        assertEquals(emptyList(), kept())
        val again = assertNotNull(UncaughtRecord.takePending(dir, journal), "the next start says it again")
        assertTrue("the trace is in the journal" in again, again)
        assertEquals(listOf(text), uncaughtNotes())
        assertFalse(Files.exists(pending))
    }

    /** NIT 19: a file with no whole header (a write cut short) is journaled and said all the same. */
    @Test
    fun aPendingFileThatDoesNotParseIsStillSaid() {
        Files.writeString(pending, "=== uncaught 2026-11-10T21:30")
        val line = assertNotNull(UncaughtRecord.takePending(dir, journal))
        assertTrue(line.startsWith("an unread uncaught-error record (29 B, written "), line)
        assertEquals(listOf("=== uncaught 2026-11-10T21:30"), uncaughtNotes())
        assertEquals(1, kept().size, "kept under its modified time: ${kept()}")
        assertTrue(kept()[0] in line, line)
        assertFalse(Files.exists(pending))

        // a WHOLE header whose time does not parse is unread too — said and renamed, not a pickup
        // that cannot finish and says so again at every start (0.64 P3 verify: no pin read this arm)
        val odd = "=== uncaught not-a-time thread \"t\" build \"b\" ===\njava.lang.Error: x\n"
        Files.writeString(pending, odd)
        val second = assertNotNull(UncaughtRecord.takePending(dir, journal) { LocalDateTime.of(2031, 1, 2, 3, 4, 5) })
        assertTrue(second.startsWith("an unread uncaught-error record (${odd.length} B, written "), second)
        assertEquals(odd, uncaughtNotes().last())
        assertFalse(Files.exists(pending))
        assertEquals(2, kept().size, kept().toString())
    }

    /** F7: a fault that comes back at every start is one kept file and one short line a start. */
    @Test
    fun theSameFaultAgainIsAppendedAndSaidShort() {
        val h = UncaughtRecord.handlerFor(dir, "b", Handed())
        // one call line for the three: their frames agree to the test method's own
        val (firstTime, secondTime, thirdTime) = listOf("the first time", "the second time", "the third time").map { fault(it) }
        h.uncaughtException(Thread("DefaultDispatcher-worker-3"), firstTime)
        val first = Files.readString(pending)
        assertTrue("the trace is in" in assertNotNull(UncaughtRecord.takePending(dir, journal)))
        val file = kept().single()

        h.uncaughtException(Thread("DefaultDispatcher-worker-11"), secondTime)     // another worker, the same fault
        val second = Files.readString(pending)
        val line = assertNotNull(UncaughtRecord.takePending(dir, journal))
        assertTrue(Regex("""the same again, 2 since \d\d-\d\d \d\d:\d\d:\d\d: an uncaught java\.lang\.IllegalStateException in thread DefaultDispatcher-worker-11 at \d\d-\d\d \d\d:\d\d:\d\d — appended to \Q$file\E""").matches(line), line)
        assertEquals(listOf(first, line), uncaughtNotes(), "the journal gained the short line, not the trace again")
        assertEquals(listOf(file), kept(), "no second file")
        assertEquals(first + second, Files.readString(dir.resolve(file)), "the record went into the kept file")
        assertFalse(Files.exists(pending))

        h.uncaughtException(Thread("DefaultDispatcher-worker-5"), thirdTime)
        assertTrue(assertNotNull(UncaughtRecord.takePending(dir, journal)).startsWith("the same again, 3 since "))

        // another class is another fault: its own file, its trace whole
        h.uncaughtException(Thread("DefaultDispatcher-worker-5"), UnsupportedOperationException("something else"))
        val other = Files.readString(pending)
        val t = LocalDateTime.of(2031, 1, 2, 3, 4, 5)            // the name when the record's own second is taken
        assertTrue("the trace is in" in assertNotNull(UncaughtRecord.takePending(dir, journal) { t }))
        assertEquals(2, kept().size, kept().toString())
        assertEquals(other, uncaughtNotes().last())
    }

    /**
     * R2 (review 1b RF-4): a start loop on one fault is bounded in the kept file IN FACT. Whole records
     * while the kept file holds fewer than [UncaughtRecord.MAX_PER_PROCESS]; past that ONE counter line
     * at its end, rewritten in place at each start. The short line is journaled and returned for
     * n ≤ 8 and at powers of two. Forty starts, each a new process that fails once after the pickup.
     */
    @Test
    fun aStartLoopOnOneFaultKeepsEightRecordsAndOneCounterLine() {
        val faults = (1..40).map { fault("start $it") }                  // one call line: one fault
        val said = mutableListOf<Pair<Int, String>>()
        var sizeAfter9 = -1L
        for (i in 1..40) {
            UncaughtRecord.handlerFor(dir, "b", Handed()).uncaughtException(Thread("DefaultDispatcher-worker-$i"), faults[i - 1])
            UncaughtRecord.takePending(dir, journal)?.let { said += i to it }
            if (i == 9) sizeAfter9 = Files.size(dir.resolve(kept().single()))
        }
        val file = dir.resolve(kept().single())
        val text = Files.readString(file)
        assertFalse(Files.exists(pending))
        assertEquals(UncaughtRecord.MAX_PER_PROCESS, headers(text), "eight whole records in the kept file")
        val last = text.trimEnd('\n').substringAfterLast('\n')
        assertTrue(Regex("""--- and 32 more since, the last at \d\d-\d\d \d\d:\d\d:\d\d ---""").matches(last), last)
        assertEquals(1, text.lines().count { it.startsWith("--- and ") }, "ONE counter line")
        assertEquals("32".length - "1".length, (Files.size(file) - sizeAfter9).toInt(),
            "the file after the 40th start is the file after the 9th but for the counter's digits")
        assertEquals((1..8).toList() + listOf(16, 32), said.map { it.first }, "said at n = 1 (whole), 2-8, 16 and 32 only: $said")
        assertTrue(said.drop(1).all { (n, line) -> line.startsWith("the same again, $n since ") }, said.toString())
        assertEquals(said.map { it.second }.drop(1), uncaughtNotes().drop(1), "and journaled exactly then")
        assertTrue(said[8].second.endsWith("— counted in the last line of ${file.fileName}"), said[8].second)
    }

    /** R2 (RF-4): a counter line a torn write left unreadable (it opens with the prefix and does not
     *  parse) is rewritten whole, counted from this start, and said even where n would not be. */
    @Test
    fun aCounterLineThatDoesNotReadIsRewrittenAndSaid() {
        fun record(at: String) = "=== uncaught 2026-11-10T$at-06:00 thread \"sync\" build \"b\" ===\njava.lang.Error: x\n\tat a.b(c.kt:1)\n\n"
        val eight = (0 until 8).joinToString("") { record("21:30:0$it") }
        Files.writeString(dir.resolve("uncaught-20261110-213000.txt"), eight + "--- and 1x more since, the last at 11-10 21:3 ---\n")
        Files.writeString(pending, record("21:40:00"))
        val line = assertNotNull(UncaughtRecord.takePending(dir, journal), "n = 9 is said because the counter did not read")
        assertTrue(line.startsWith("the same again, 9 since 11-10 21:30:00: ") && "did not read as one" in line, line)
        assertEquals(eight + "--- and 1 more since, the last at 11-10 21:40:00 ---\n", Files.readString(dir.resolve("uncaught-20261110-213000.txt")))
        assertEquals(listOf(line), uncaughtNotes())
    }

    /** R2 verify (RF-4's rewrite in place): an unreadable counter line LONGER than the line that replaces it
     *  leaves none of its bytes behind — the file is cut at the new line's end. Left there, the next pickup read
     *  the stale tail as the file's last line, found no counter and appended a second one.
     *  Watched with the cut (`truncate`) removed: the old line's tail stayed after the new one. */
    @Test
    fun aLongerUnreadableCounterLineLeavesNothingBehindItsRewrite() {
        fun record(at: String) = "=== uncaught 2026-11-10T$at-06:00 thread \"sync\" build \"b\" ===\njava.lang.Error: x\n\tat a.b(c.kt:1)\n\n"
        val eight = (0 until 8).joinToString("") { record("21:30:0$it") }
        val file = dir.resolve("uncaught-20261110-213000.txt")
        Files.writeString(file, eight + "--- and 123456789x more since, the last at 11-10 21:39:59, and bytes a torn write left ---\n")
        Files.writeString(pending, record("21:40:00"))
        assertNotNull(UncaughtRecord.takePending(dir, journal), "said: the counter did not read")
        assertEquals(eight + "--- and 1 more since, the last at 11-10 21:40:00 ---\n", Files.readString(file),
            "the rewritten counter line is the file's end")
    }

    /** "The same again" is the same class AND thread AND top frame: one of them differing is another
     *  fault, with its own file and its trace whole. (A thread's digits alone do not count: the
     *  test above.) */
    @Test
    fun anotherClassOrThreadOnTheSameFrameIsAnotherFault() {
        fun record(at: String, thread: String, cls: String) =
            "=== uncaught 2026-11-10T$at-06:00 thread \"$thread\" build \"b\" ===\n$cls: x\n\tat a.b(c.kt:1)\n\n"
        Files.writeString(dir.resolve("uncaught-20261110-213045.txt"), record("21:30:45", "sync", "java.lang.Error"))
        Files.writeString(pending, record("21:31:00", "sync", "java.lang.IllegalStateException"))
        val byClass = assertNotNull(UncaughtRecord.takePending(dir, journal))
        assertTrue(byClass.startsWith("an uncaught java.lang.IllegalStateException in thread sync at 11-10 21:31:00 during the last run"), byClass)
        Files.writeString(pending, record("21:32:00", "music", "java.lang.IllegalStateException"))
        val byThread = assertNotNull(UncaughtRecord.takePending(dir, journal))
        assertTrue(byThread.startsWith("an uncaught java.lang.IllegalStateException in thread music at 11-10 21:32:00 during the last run"), byThread)
        assertEquals(listOf("uncaught-20261110-213045.txt", "uncaught-20261110-213100.txt", "uncaught-20261110-213200.txt"), kept())
        assertEquals(2, uncaughtNotes().size)
        // and the control: the same three again IS the same
        Files.writeString(pending, record("21:33:00", "music", "java.lang.IllegalStateException"))
        assertTrue(assertNotNull(UncaughtRecord.takePending(dir, journal)).startsWith("the same again, 2 since 11-10 21:32:00: "))
    }

    /**
     * R2 (review 1b RF-3): "the same again" compares the first [32] frames of the first section, not the
     * top frame alone. Two faults of one class and thread that share a library frame on top (a list's
     * iterator, a parser's exception factory) reached from different callers are two faults: the second
     * is journaled whole and kept in a file of its own. The comparison is capped, so a deep recursion's
     * first section (about 1,024 frames, past the kept file's head read) still compares: two records
     * whose first 32 frames agree and whose 40th differs ARE the same again.
     */
    @Test
    fun aSharedTopFrameFromAnotherCallerIsAnotherFault() {
        fun record(at: String, frames: List<String>) =
            "=== uncaught 2026-11-10T$at-06:00 thread \"sync\" build \"b\" ===\njava.lang.IllegalStateException: x\n" +
                frames.joinToString("") { "\tat $it\n" } + "\n"
        Files.writeString(dir.resolve("uncaught-20261110-213045.txt"), record("21:30:45", listOf("lib.top(L.kt:1)", "a.callerX(A.kt:1)")))
        Files.writeString(pending, record("21:31:00", listOf("lib.top(L.kt:1)", "b.callerY(B.kt:1)")))
        val line = assertNotNull(UncaughtRecord.takePending(dir, journal))
        assertTrue(line.startsWith("an uncaught java.lang.IllegalStateException in thread sync at 11-10 21:31:00 during the last run — the trace is in"), line)
        assertEquals(listOf("uncaught-20261110-213045.txt", "uncaught-20261110-213100.txt"), kept(), "a second kept file")
        assertEquals(1, uncaughtNotes().size, "journaled whole")

        // the cap: the first 32 frames agree, the 40th differs → the same again
        val deep = (1..40).map { "rec.f$it(R.kt:$it)" }
        Files.writeString(dir.resolve("uncaught-20261110-214000.txt"), record("21:40:00", deep))
        Files.writeString(pending, record("21:41:00", deep.take(39) + "rec.other(R.kt:99)"))
        val same = assertNotNull(UncaughtRecord.takePending(dir, journal))
        assertTrue(same.startsWith("the same again, 2 since 11-10 21:40:00: "), same)
        // ...and a difference inside the 32 is another fault
        Files.writeString(pending, record("21:42:00", deep.take(31) + "rec.other(R.kt:99)" + deep.drop(32)))
        val other = assertNotNull(UncaughtRecord.takePending(dir, journal))
        assertTrue(other.startsWith("an uncaught java.lang.IllegalStateException in thread sync at 11-10 21:42:00 during the last run"), other)
    }

    /**
     * 0.64 P3 verify. A pending file can hold several records (threads that fail together; starts
     * that end before the pickup). "The same again" journals one short line and no trace, so it is
     * taken only when EVERY record of the file is that fault: another fault behind the first one
     * would otherwise have its trace in a kept file alone, which nothing serves off the phone, and
     * be counted as "the same".
     */
    @Test
    fun anotherFaultBehindTheSameOneIsJournaledWhole() {
        fun record(at: String, cls: String) =
            "=== uncaught 2026-11-10T$at-06:00 thread \"sync\" build \"b\" ===\n$cls: x\n\tat a.b(c.kt:1)\n\n"
        Files.writeString(dir.resolve("uncaught-20261110-213045.txt"), record("21:30:45", "java.lang.Error"))
        val mixed = record("21:31:00", "java.lang.Error") + record("21:31:01", "java.lang.IllegalStateException")
        Files.writeString(pending, mixed)
        val line = assertNotNull(UncaughtRecord.takePending(dir, journal))
        assertEquals("an uncaught java.lang.Error in thread sync at 11-10 21:31:00 during the last run — " +
            "the trace is in the journal (an uncaught note) and in uncaught-20261110-213100.txt (2 records)", line)
        assertEquals(listOf(mixed), uncaughtNotes(), "the note holds the other fault's trace")
        assertEquals(listOf("uncaught-20261110-213045.txt", "uncaught-20261110-213100.txt"), kept())
        assertEquals(record("21:30:45", "java.lang.Error"), Files.readString(dir.resolve("uncaught-20261110-213045.txt")),
            "nothing was appended to the older file")
        // the control: two records of the one fault ARE the same again, and both are counted
        Files.writeString(dir.resolve("uncaught-20261110-214000.txt"), record("21:40:00", "java.lang.Error"))
        Files.writeString(pending, record("21:41:00", "java.lang.Error") + record("21:41:05", "java.lang.Error"))
        assertTrue(assertNotNull(UncaughtRecord.takePending(dir, journal)).startsWith("the same again, 3 since 11-10 21:40:00: "))
    }

    /**
     * The pickup never throws (0.64 P3 verify: the guard had no pin). It runs in the service's
     * `onCreate` and in `runShell`'s first lines; an exception leaving it would end the start it is
     * there to explain, at every start. Here the newest kept file cannot be read.
     */
    @Test
    fun aPickupThatCannotFinishSaysSoAndLeavesTheRecord() {
        fun record(at: String) = "=== uncaught 2026-11-10T$at-06:00 thread \"sync\" build \"b\" ===\njava.lang.Error: x\n\tat a.b(c.kt:1)\n\n"
        val keptFile = dir.resolve("uncaught-20261110-213045.txt").also { Files.writeString(it, record("21:30:45")) }
        Files.writeString(pending, record("21:31:00"))
        Files.setPosixFilePermissions(keptFile, emptySet())
        try {
            // an account that reads past file permissions cannot make this failure: skipped, not passed
            org.junit.Assume.assumeFalse("the kept file is readable whatever its permissions", Files.isReadable(keptFile))
            val line = assertNotNull(UncaughtRecord.takePending(dir, journal))
            assertTrue(line.startsWith("the last run's uncaught-error record could not be picked up (AccessDeniedException: ") &&
                line.endsWith(" — it stays in ${UncaughtRecord.PENDING}"), line)
            assertEquals(record("21:31:00"), Files.readString(pending), "still pending")
            assertEquals(emptyList(), uncaughtNotes(), "nothing was journaled for a pickup that did not finish")
        } finally {
            Files.setPosixFilePermissions(keptFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
    }

    /** NIT 17 on the same-again path: nothing is appended and nothing removed until the line landed. */
    @Test
    fun aSameAgainNoteThatDidNotLandKeepsTheRecordPending() {
        fun record(at: String) = "=== uncaught 2026-11-10T$at-06:00 thread \"sync\" build \"b\" ===\njava.lang.Error: x\n\tat a.b(c.kt:1)\n\n"
        val keptFile = dir.resolve("uncaught-20261110-213045.txt").also { Files.writeString(it, record("21:30:45")) }
        Files.writeString(pending, record("21:31:00"))
        val blocker = dir.resolve("a-file").also { Files.writeString(it, "x") }
        val line = assertNotNull(UncaughtRecord.takePending(dir, blocker.resolve("journal.jsonl")))
        assertTrue(line.startsWith("the same again, 2 since ") && "NOT journaled and not appended" in line, line)
        assertEquals(record("21:31:00"), Files.readString(pending), "still pending")
        assertEquals(record("21:30:45"), Files.readString(keptFile), "the kept file is as it was")
        assertTrue(assertNotNull(UncaughtRecord.takePending(dir, journal)).endsWith("appended to uncaught-20261110-213045.txt"))
        assertEquals(record("21:30:45") + record("21:31:00"), Files.readString(keptFile))
        assertFalse(Files.exists(pending))
    }

    @Test
    fun aTakenDatedNameFallsBackToThePickupsTime() {
        Files.writeString(pending, "=== uncaught 2026-11-10T21:30:45.123-06:00 thread \"t\" build \"b\" ===\njava.lang.Error: x\n\tat a.b(c.kt:1)\n\n")
        Files.writeString(dir.resolve("uncaught-20261110-213045.txt"), "already here")
        val line = assertNotNull(UncaughtRecord.takePending(dir, journal) { LocalDateTime.of(2026, 11, 11, 8, 0, 0) })
        assertEquals("an uncaught java.lang.Error in thread t at 11-10 21:30:45 during the last run — " +
            "the trace is in the journal (an uncaught note) and in uncaught-20261111-080000.txt", line)
        assertEquals("already here", Files.readString(dir.resolve("uncaught-20261110-213045.txt")), "never replaced")
        assertEquals(listOf("uncaught-20261110-213045.txt", "uncaught-20261111-080000.txt"), kept())
        // and with no journal path the line names the file only
        Files.writeString(pending, "=== uncaught 2026-11-12T01:02:03-06:00 thread \"t\" build \"b\" ===\njava.lang.Error: y\n\n")
        assertEquals("an uncaught java.lang.Error in thread t at 11-12 01:02:03 during the last run — the trace is in uncaught-20261112-010203.txt",
            UncaughtRecord.takePending(dir, null))
    }

    /** A file longer than this build writes (it stops under the limit) is read to the limit, and
     *  the note says what it does not hold. */
    @Test
    fun aPendingFilePastWhatThisBuildWritesIsSaid() {
        val head = "=== uncaught 2026-11-10T21:30:45-06:00 thread \"t\" build \"b\" ===\njava.lang.Error: x\n"
        Files.writeString(pending, head + "y".repeat(300 * 1024))
        val size = Files.size(pending)
        assertNotNull(UncaughtRecord.takePending(dir, journal))
        val note = uncaughtNotes().single()
        assertTrue(note.endsWith("\n[the file holds ${size - UncaughtRecord.MAX_PENDING_BYTES} B more than the 262144 B this build writes; they are not in this note]\n"),
            note.takeLast(200))
        assertEquals(size, Files.size(dir.resolve("uncaught-20261110-213045.txt")), "the kept file is whole")
    }

    /**
     * NIT 18: the pickup and the handler share one lock, so a record written while the pickup runs
     * is never in a file that was already said. The pickup is parked INSIDE its lock by a journal
     * that is a named pipe with no reader yet (the note's open waits for one). A thread then fails.
     * The wait below ends on one of two states that do not change: the failing thread BLOCKED on a
     * monitor the pickup's thread owns (the shared lock), or TERMINATED (it wrote without waiting).
     */
    @Test
    fun aRecordWrittenDuringThePickupIsNeverInAFileAlreadySaid() {
        val there = Handed()
        val h = UncaughtRecord.handlerFor(dir, "b", there)
        h.uncaughtException(Thread("w1"), fault("before the pickup"))
        val first = Files.readString(pending)
        val pipe = dir.resolve("pipe.jsonl")
        assertEquals(0, ProcessBuilder("mkfifo", pipe.toString()).start().waitFor(), "mkfifo")
        var line: String? = null
        val pickup = Thread({ line = UncaughtRecord.takePending(dir, pipe) }, "pickup")
        pickup.start()
        // the pickup is parked once it WAITS in the pipe's open, having read the pending file
        val mx = ManagementFactory.getThreadMXBean()
        val failing = Thread({ throw fault("during the pickup") }, "w2").apply { uncaughtExceptionHandler = h }
        val parked = CountDownLatch(1)
        val piped = ByteArrayOutputStream()
        val reader = Thread({
            // The reader side: open the pipe only when told; then the note flows and the pickup goes on.
            // A plain read loop: `readBytes()` asks a pipe for its position first, which a pipe refuses
            // (the first version of this rig did that, its reader ended at once, and the pin passed
            // only in the runs where the note was written before the read end closed).
            parked.await()
            java.io.FileInputStream(pipe.toFile()).use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = inp.read(buf); if (n < 0) break; piped.write(buf, 0, n) }
            }
        }, "pipe-reader").apply { isDaemon = true }
        reader.start()
        val giveUp = System.nanoTime() + 20_000_000_000L       // a bound on the TEST only: a rig that never parks fails, it does not hang
        try {
            while (pickup.stackTrace.none { it.methodName == "appendNote" }) {
                assertTrue(System.nanoTime() < giveUp && pickup.isAlive, "the pickup never reached its note")
                Thread.onSpinWait()
            }
            failing.start()
            while (true) {
                val info = mx.getThreadInfo(failing.id)
                if (info == null || info.threadState == Thread.State.TERMINATED) break
                if (info.threadState == Thread.State.BLOCKED && info.lockOwnerId == pickup.id) break
                assertTrue(System.nanoTime() < giveUp, "the failing thread neither waited for the pickup nor ended")
                Thread.onSpinWait()
            }
        } finally {
            parked.countDown()
        }
        pickup.join(); failing.join(); reader.join()
        assertTrue("the trace is in the journal" in assertNotNull(line), "the pickup said the record: $line")
        val note = Json.parseToJsonElement(piped.toString(Charsets.UTF_8)).jsonObject
        assertEquals(first, note["detail"]!!.jsonPrimitive.content, "the note that went out is the record as read")
        val file = kept().single()
        assertEquals(first, Files.readString(dir.resolve(file)), "the file that was said holds exactly what was said")
        val fresh = Files.readString(pending)
        assertTrue("during the pickup" in fresh && headers(fresh) == 1, "the record written meanwhile is pending for the next start: $fresh")
    }
}
