package wm.damage.core.util

import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import wm.damage.core.comp.Journal

/**
 * A record of an uncaught error (`HANDOFF.md` §75.1, Adam's ruling; APK 0.64).
 *
 * Context for the reader: a personal device; Damage's own phone app and PC service. On the phone an
 * uncaught throwable on any thread ends the app's process, and there is no adb on this setup: the
 * only trace was Android's own log, which nothing here can read. On the PC's JVM it ends one thread
 * and the trace goes to stderr.
 *
 * [install] sets a process default handler that appends the trace, the thread and the time to
 * [PENDING] in the host's data directory, then hands the throwable to the handler that was there
 * (Android's, which ends the process; none on the PC, where the JVM's own print is reproduced).
 * The NEXT process start calls [takePending]: the record is journaled whole as an `uncaught` note,
 * the file is renamed to `uncaught-<time>.txt` so it is said once, and one line comes back for the
 * log. Kept files are never deleted.
 *
 * The handler touches nothing but its file and stderr: no network, no `Log`, no notification, no
 * shell, store or transport state. It never throws.
 *
 * Bounds (`FORK.md` §3.8, question (b) — what happens when the same thing happens again and again):
 *  - a process whose threads keep failing (the PC's JVM goes on after one): [MAX_PER_PROCESS]
 *    records, then one closing line in the file and one on stderr;
 *  - a process that starts, fails BEFORE the pickup and ends, at every start (nothing renames the
 *    file then): the file stops at [MAX_PENDING_BYTES], a record that reaches the limit is cut there
 *    and says so, and the closing line is not added a second time;
 *  - a process that fails AFTER the pickup at every start (the PC's supervisor restarts it every
 *    5 s without limit; the phone at Android's restart pacing): the pickup finds the same first
 *    class, thread and first frames as the newest kept file's, appends to that file instead of making
 *    a new one, and journals one short line. Past [MAX_PER_PROCESS] whole records that file ends with
 *    ONE counter line, rewritten in place at each start, and the short line is journaled for n ≤ 8
 *    and at powers of two (R2, RF-4): the file and the journal stop growing with the loop. Nothing
 *    here stops the restarts themselves; a pending file that holds two different faults, and a fault
 *    that alternates with another, take the whole-note path and are not bounded by this.
 */
object UncaughtRecord {
    const val PENDING = "uncaught-pending.txt"

    /** On Android the first uncaught throwable ends the process, so more than one happens only when
     *  threads fail together; on the PC a thread ends and the process goes on, and a job relaunched on
     *  pacing would otherwise append without end. The first faults are the ones that explain the rest. */
    const val MAX_PER_PROCESS = 8

    /** The pending file never passes this. Two full-depth traces fit; [takePending] reads the file
     *  in one bounded read and journals all of it. */
    const val MAX_PENDING_BYTES = 256 * 1024L

    /** Kept free under the limit for the closing lines, so they never carry the file past it. */
    private const val CLOSING_ROOM = 512L

    /** How much of a kept file is read for its first record's class, thread and frames. */
    private const val HEAD_BYTES = 64 * 1024L

    /** How many frames of a record's first section "the same again" compares (R2, review 1b RF-3).
     *  Capped: a deep recursion's first section is about 1,024 frames (≈ 97 KiB on HotSpot, modeled),
     *  past [HEAD_BYTES]; its first 32 are the same from start to start, and 32 × ~100 B sit well
     *  inside the head read. */
    internal const val SIGNATURE_FRAMES = 32

    private const val HEADER = "=== uncaught "
    private const val CLOSING = "--- not recorded past this line: "
    /** The kept file's counter line (R2, RF-4): `--- and N more since, the last at MM-dd HH:mm:ss ---`. */
    private const val COUNTER = "--- and "
    /** The line under a record's header that names its root cause (R2, RF-6). */
    private const val ROOT = "--- root cause: "
    /** How much of the root cause's message that line carries; the whole text is in the trace. */
    private const val ROOT_MESSAGE_CHARS = 300
    private val COUNTER_RE = Regex("""--- and (\d+) more since, the last at (.+) ---""")
    private val HEADER_RE = Regex("""^=== uncaught (\S+) thread "(.*)" build "(.*)" ===$""", RegexOption.MULTILINE)
    private val KEPT_RE = Regex("""uncaught-\d{8}-\d{6}\.txt""")
    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val SAID_STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val DIGITS = Regex("[0-9]+")

    /** One lock for every write of the pending file AND for the pickup's read, note and rename: a
     *  record written while the pickup runs lands either before its read or in a fresh pending file,
     *  never in a file that was already said. */
    private val lock = Any()

    @Volatile private var ours: Thread.UncaughtExceptionHandler? = null
    @Volatile private var previousName = "none"

    /**
     * Makes the record the process's default handler. A no-op while it already is. [dir] is the
     * host's data directory (it exists on both hosts); [build] is the build tag the journal's
     * `build` note carries. The handler that was there is captured here, once, and is always called
     * after the write.
     */
    @Synchronized
    fun install(dir: Path, build: String) {
        val now = Thread.getDefaultUncaughtExceptionHandler()
        if (now != null && now === ours) return
        previousName = now?.javaClass?.name ?: "none"
        val h = handlerFor(dir, build, now)
        ours = h
        Thread.setDefaultUncaughtExceptionHandler(h)
    }

    /** "uncaught-error record installed (previous handler …)" when this process's default handler IS
     *  the record right now, else null. Read from the JVM, not from a flag: an APK whose manifest
     *  does not name `DamageApp` assembles and runs, and only this line (and
     *  `tools/check_apk_wiring.py` at build time) says the handler is absent. */
    fun installedLine(): String? {
        val h = ours ?: return null
        return if (Thread.getDefaultUncaughtExceptionHandler() === h)
            "uncaught-error record installed (previous handler $previousName)" else null
    }

    internal fun handlerFor(dir: Path, build: String,
                            previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Recorder(dir.resolve(PENDING), build, previous)

    private class Recorder(private val file: Path, private val build: String,
                           private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        // both under [lock]; one Recorder per process in production, so these are per process
        private var written = 0
        private var closed = false

        override fun uncaughtException(thread: Thread, e: Throwable) {
            try {
                synchronized(lock) { record(thread, e) }
            } catch (t: Throwable) {
                // The handler must not throw, and nothing but stderr is safe to use here. This is
                // the one place the failure can be said; if stderr itself fails there is none left.
                try { System.err.println("damage: the uncaught-error record was not written: $t") } catch (unsaid: Throwable) { }
            } finally {
                if (previous != null) previous.uncaughtException(thread, e)
                else {
                    // setting a default handler replaces the JVM's own print (the PC's damage.log
                    // depends on it): say exactly what the JVM says
                    System.err.print("Exception in thread \"${thread.name}\" ")
                    e.printStackTrace()
                }
            }
        }

        private fun record(thread: Thread, e: Throwable) {
            if (written >= MAX_PER_PROCESS) { close("$MAX_PER_PROCESS records in this process"); return }
            val size = if (Files.exists(file)) Files.size(file) else 0L
            val room = MAX_PENDING_BYTES - CLOSING_ROOM - size
            if (room <= 0) { close("the file's limit of $MAX_PENDING_BYTES B"); return }
            written++
            var cut = false
            FileOutputStream(file.toFile(), true).use { fos ->
                val limited = Limited(fos, room)
                // The header goes out first and by itself: the trace below allocates, and after an
                // OutOfMemoryError it may be short or absent. A header with little under it says so.
                val at = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                limited.write("$HEADER$at thread \"${thread.name}\" build \"$build\" ===\n".toByteArray(Charsets.UTF_8))
                // R2 (RF-6): the root cause's name next, so a record cut at the file's limit still says it
                // (`printStackTrace` writes the outer frames first and each `Caused by` after them)
                rootLine(e)?.let { limited.write(it.toByteArray(Charsets.UTF_8)) }
                limited.flush()
                val pw = PrintWriter(OutputStreamWriter(limited, Charsets.UTF_8))
                e.printStackTrace(pw)          // causes and suppressed included
                pw.println()
                // PrintWriter keeps an I/O error to itself; this is where it is read (a disk that filled)
                if (pw.checkError())
                    System.err.println("damage: the uncaught-error record in ${file.fileName} may be short: its writer reported an I/O error")
                cut = limited.cut
                if (cut) fos.write("\n${CLOSING}the file's limit of $MAX_PENDING_BYTES B (the record above is cut) ---\n".toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (cut) {
                closed = true
                System.err.println("damage: uncaught errors are no longer recorded in ${file.fileName}: the file's limit of $MAX_PENDING_BYTES B")
            }
        }

        /** `--- root cause: <class>: <message> ---` when [e] has a cause, else null. Built inside its own
         *  `try`: a `getMessage()` that throws costs this line and nothing else (null). The message is
         *  one line, capped at [ROOT_MESSAGE_CHARS] with its full length said. */
        private fun rootLine(e: Throwable): String? = try {
            if (e.cause == null) null else {
                var root = e
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                while (seen.add(root)) root = root.cause ?: break       // a cause chain can loop: stop at a repeat
                val msg = root.message?.replace('\r', ' ')?.replace('\n', ' ')
                val shown = when {
                    msg == null -> ""
                    msg.length <= ROOT_MESSAGE_CHARS -> ": $msg"
                    else -> ": ${msg.take(ROOT_MESSAGE_CHARS)} … (${msg.length} characters; the whole text is in the trace below)"
                }
                "$ROOT${root.javaClass.name}$shown ---\n"
            }
        } catch (t: Throwable) {
            null
        }

        /** A limit stopped a write: one closing line in the file and one on stderr, once per
         *  process — and no second closing line when the file already ends with one (each process
         *  of a start loop would otherwise add its own). */
        private fun close(why: String) {
            if (closed) return
            closed = true
            if (!endsWithClosing())
                FileOutputStream(file.toFile(), true).use {
                    // on a line of its own, whatever the last write left (one cut short ends mid-line)
                    it.write("\n$CLOSING$why ---\n".toByteArray(Charsets.UTF_8)); it.fd.sync()
                }
            System.err.println("damage: uncaught errors are no longer recorded in ${file.fileName}: $why")
        }

        private fun endsWithClosing(): Boolean {
            if (!Files.isRegularFile(file)) return false
            val size = Files.size(file)
            val tail = String(readAt(file, maxOf(0L, size - CLOSING_ROOM), CLOSING_ROOM), Charsets.UTF_8).trimEnd('\n')
            return tail.substringAfterLast('\n').startsWith(CLOSING)
        }
    }

    /** Passes at most [room] bytes on and remembers that it dropped some. */
    private class Limited(private val out: OutputStream, private var room: Long) : OutputStream() {
        var cut = false
            private set

        override fun write(b: Int) {
            if (room >= 1) { out.write(b); room-- } else cut = true
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val n = minOf(len.toLong(), room).toInt()
            if (n > 0) { out.write(b, off, n); room -= n }
            if (n < len) cut = true
        }

        override fun flush() = out.flush()
    }

    /** At most [max] bytes of [p] from [from], in one bounded read loop (never the whole of a file
     *  whose size this code does not control). */
    private fun readAt(p: Path, from: Long, max: Long): ByteArray =
        FileChannel.open(p, StandardOpenOption.READ).use { ch ->
            val buf = ByteBuffer.allocate(minOf(max, maxOf(0L, ch.size() - from)).toInt())
            var pos = from
            while (buf.hasRemaining()) {
                val n = ch.read(buf, pos)
                if (n <= 0) break
                pos += n
            }
            buf.array().copyOf(buf.position())
        }

    /** The first record of a file's text: when, on which thread, which class, its first frames; and
     *  how many records the text holds. Null when the text has no whole header (a write cut short). */
    private class Parsed(val at: LocalDateTime,
                         /** The last record's time (the first's when it does not parse). */
                         val lastAt: LocalDateTime, val thread: String, val cls: String,
                         /** The first [SIGNATURE_FRAMES] `\tat` lines of the first record's first section. */
                         val frames: List<String>, val count: Int,
                         /** Every record of the text has the first one's [signature]. A pending file holds
                          *  several records when threads fail together or when starts end before the
                          *  pickup; one of them can be another fault than the first. */
                         val uniform: Boolean) {
        val signature: List<String> get() = signatureOf(cls, thread, frames)
    }

    /** What makes two records "the same again": the class, the thread with its digits levelled (a
     *  pool's worker number and a connection's address differ from run to run for one fault), and the
     *  first [SIGNATURE_FRAMES] frames of the first section. Not the top frame alone (R2, RF-3): a
     *  library frame on top (a list's iterator, a parser's exception factory) is shared by different
     *  faults, and the second would then sit in a kept file with only a short line said. */
    private fun signatureOf(cls: String, thread: String, frames: List<String>): List<String> =
        listOf(cls, thread.replace(DIGITS, "#")) + frames

    private fun parse(text: String): Parsed? {
        val heads = HEADER_RE.findAll(text).toList()
        val first = heads.firstOrNull() ?: return null
        val at = try { OffsetDateTime.parse(first.groupValues[1]).toLocalDateTime() } catch (e: DateTimeParseException) { return null }
        /** The class and the first section's first [SIGNATURE_FRAMES] frames of the record under
         *  header [i]: the `\tat` lines from the first one (a message can span lines) up to the first
         *  line that is not one (`Caused by`, `... N more`, the record's end). */
        fun under(i: Int): Pair<String, List<String>> {
            val body = text.substring(heads[i].range.last + 1, heads.getOrNull(i + 1)?.range?.first ?: text.length)
            val lines = body.lineSequence().filter { it.isNotBlank() && !it.startsWith(CLOSING) && !it.startsWith(COUNTER) && !it.startsWith(ROOT) }.toList()
            val cls = lines.firstOrNull()?.takeUnless { it.startsWith("\tat ") }?.substringBefore(':')?.trim().orEmpty().ifEmpty { "unread" }
            val frames = lines.asSequence().dropWhile { !it.startsWith("\tat ") }.takeWhile { it.startsWith("\tat ") }
                .take(SIGNATURE_FRAMES).map { it.trim() }.toList()
            return cls to frames
        }
        val (cls, frames) = under(0)
        val mine = signatureOf(cls, first.groupValues[2], frames)
        val uniform = (1 until heads.size).all { i -> under(i).let { (c, f) -> signatureOf(c, heads[i].groupValues[2], f) } == mine }
        val lastAt = try { OffsetDateTime.parse(heads.last().groupValues[1]).toLocalDateTime() } catch (e: DateTimeParseException) { at }
        return Parsed(at, lastAt, first.groupValues[2], cls, frames, heads.size, uniform)
    }

    private fun keptFiles(dir: Path): List<Path> =
        Files.newDirectoryStream(dir).use { ds ->
            ds.filter { KEPT_RE.matches(it.fileName.toString()) && Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }
        }

    /** Headers in [p], counted through a 64 KiB window: a kept file that takes one record per start
     *  of a start loop has no size bound, so it is never read whole. */
    private fun countRecords(p: Path): Int {
        val mark = "\n$HEADER".toByteArray(Charsets.UTF_8)
        var count = 0
        var matched = 1                 // the file's first byte follows a line start
        Files.newInputStream(p).use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = buf[i]
                    matched = if (b == mark[matched]) matched + 1 else if (b == mark[0]) 1 else 0
                    if (matched == mark.size) { count++; matched = 0 }
                }
            }
        }
        return count
    }

    /** The counter line at the end of a kept file: where it starts, its count, whether it parsed. */
    private class Counter(val offset: Long, val n: Int, val readable: Boolean)

    /** The kept file's last line when it is a counter line (it opens with [COUNTER]), from one read of
     *  the last [CLOSING_ROOM] bytes; null when the last line is something else. */
    private fun readCounter(p: Path): Counter? {
        val size = Files.size(p)
        val from = maxOf(0L, size - CLOSING_ROOM)
        val tail = readAt(p, from, CLOSING_ROOM)
        var end = tail.size
        while (end > 0 && tail[end - 1] == NL) end--
        var start = end
        while (start > 0 && tail[start - 1] != NL) start--
        if (start == 0 && from > 0) return null          // a last line longer than the window: not one of ours
        val last = String(tail, start, end - start, Charsets.UTF_8)
        if (!last.startsWith(COUNTER)) return null
        val m = COUNTER_RE.matchEntire(last)
        val n = m?.groupValues?.get(1)?.toIntOrNull()
        return Counter(from + start, n ?: 0, n != null)
    }

    /** Writes the counter line: over the old one in place (then cut to the new length), or after the
     *  file's last line when there is none yet. The new line is never shorter than the old (the count
     *  only grows; the time is fixed width). */
    private fun writeCounter(p: Path, old: Counter?, n: Int, last: LocalDateTime) {
        FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
            val size = ch.size()
            val endsWithNl = size == 0L || readAt(p, size - 1, 1).firstOrNull() == NL
            val text = (if (old == null && !endsWithNl) "\n" else "") + "$COUNTER$n more since, the last at ${last.format(SAID_STAMP)} ---\n"
            val buf = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            val at = old?.offset ?: size
            var pos = at
            while (buf.hasRemaining()) pos += ch.write(buf, pos)
            ch.truncate(pos)
            ch.force(true)
        }
    }

    private const val NL = '\n'.code.toByte()

    /**
     * The pickup, at a process start, before the stack is built: journals the last run's record
     * and marks it said. Returns the one line for the log (the caller logs it at ERROR level; the
     * phone's caller also raises it on the glasses as a `process` fault, 0.65 P7), or null when no record is
     * pending. It never throws.
     *
     *  - The pending text is journaled WHOLE as one `uncaught` note (the dated file sits where no
     *    route serves it; the journal is what can be read off the phone).
     *  - The file is renamed only after the note LANDED (or when no [journal] is given): a note that
     *    could not be written leaves the record pending, and the next start says it again.
     *  - A record whose first class, thread and first frames equal the newest kept file's is appended
     *    to that file and journaled as one short line (the class comment's third bound) — when
     *    every record of the pending file is that same fault. One that is another fault makes the
     *    whole file a new record: journaled whole, its own kept file.
     *  - Past [MAX_PER_PROCESS] whole records in that file the record is not appended: the file's
     *    one counter line is rewritten (R2, RF-4), and the short line is journaled and returned only
     *    for n ≤ 8 and when n reaches a power of two (else null: the start says nothing).
     *  - A file with no whole header (a write cut short) is still journaled, renamed by its
     *    modified time, and said as "unread".
     */
    fun takePending(dir: Path, journal: Path?, now: () -> LocalDateTime = { LocalDateTime.now() }): String? =
        try {
            synchronized(lock) { pickup(dir, journal, now) }
        } catch (e: Exception) {
            "the last run's uncaught-error record could not be picked up (${e::class.simpleName}: ${e.message}) — it stays in $PENDING"
        }

    private fun pickup(dir: Path, journal: Path?, now: () -> LocalDateTime): String? {
        val pending = dir.resolve(PENDING)
        if (!Files.isRegularFile(pending)) return null
        val size = Files.size(pending)
        var text = String(readAt(pending, 0, MAX_PENDING_BYTES), Charsets.UTF_8)
        if (size > MAX_PENDING_BYTES)
            // not a file this code wrote (it stops under the limit): say what the note does not hold
            text += "\n[the file holds ${size - MAX_PENDING_BYTES} B more than the $MAX_PENDING_BYTES B this build writes; they are not in this note]\n"
        val rec = parse(text)
        val inJournal = if (journal != null) "the journal (an uncaught note) and in " else ""

        val newest = if (rec == null) null else keptFiles(dir).lastOrNull()
        val before = newest?.let { parse(String(readAt(it, 0, HEAD_BYTES), Charsets.UTF_8)) }
        // `uniform`: a pending file that also holds ANOTHER fault takes the whole-note path below.
        // On this path only the short line is journaled, and that fault's trace would then sit
        // in a kept file alone, which no route serves (0.64 P3 verify).
        if (rec != null && rec.uniform && newest != null && before != null && before.signature == rec.signature) {
            val whole = countRecords(newest)
            // R2 (RF-4): past MAX_PER_PROCESS whole records the kept file takes no more; its last line
            // counts the rest, so a start loop on one fault ends at a bound in fact
            val counting = whole >= MAX_PER_PROCESS
            val counter = if (counting) readCounter(newest) else null
            val more = counter?.n ?: 0
            val n = whole + more + rec.count
            val unreadable = counter != null && !counter.readable
            val line = "the same again, $n since ${before.at.format(SAID_STAMP)}: an uncaught ${rec.cls} in thread ${rec.thread}" +
                " at ${rec.at.format(SAID_STAMP)} — " +
                (if (counting) "counted in the last line of ${newest.fileName}" else "appended to ${newest.fileName}") +
                (if (unreadable) " (its counter line did not read as one and is rewritten; the count before it is lost)" else "")
            // n ≤ 8, and each time n reaches the next power of two (a pending file of several records can
            // step over one): the journal and the log say a loop without a line per start
            val say = n <= MAX_PER_PROCESS || Integer.highestOneBit(n) != Integer.highestOneBit(n - rec.count) || unreadable
            if (say && journal != null && !Journal.appendNote(journal, "uncaught", line))
                return "$line: NOT journaled and not ${if (counting) "counted" else "appended"} (the journal could not be written) — it stays in $PENDING for the next start"
            if (counting) writeCounter(newest, counter, more + rec.count, rec.lastAt)
            else Files.newOutputStream(newest, StandardOpenOption.APPEND).use { out -> Files.newInputStream(pending).use { it.copyTo(out) } }
            Files.delete(pending)       // its bytes are in the kept file now, or counted there
            return if (say) line else null
        }

        val said = if (rec != null)
            "an uncaught ${rec.cls} in thread ${rec.thread} at ${rec.at.format(SAID_STAMP)} during the last run"
        else
            "an unread uncaught-error record ($size B, written ${modified(pending).format(SAID_STAMP)}) from the last run"
        val count = if (rec != null && rec.count > 1) " (${rec.count} records)" else ""
        if (journal != null && !Journal.appendNote(journal, "uncaught", text))
            return "$said$count — NOT journaled (the journal could not be written); it stays in $PENDING for the next start"
        // named by the record's own time; when that name is taken (a clock set back), by this
        // pickup's time, so a taken name cannot fail the same way at every start
        val byRecord = dir.resolve("uncaught-${(rec?.at ?: modified(pending)).format(FILE_STAMP)}.txt")
        val target = if (Files.exists(byRecord)) dir.resolve("uncaught-${now().format(FILE_STAMP)}.txt") else byRecord
        return try {
            Files.move(pending, target)     // no options: an existing target is refused, never replaced
            "$said — the trace is in $inJournal${target.fileName}$count"
        } catch (e: Exception) {
            "$said$count — $PENDING could not be renamed to ${target.fileName} (${e::class.simpleName}: ${e.message}); it is said again at the next start"
        }
    }

    private fun modified(p: Path): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis()), ZoneId.systemDefault())
}
