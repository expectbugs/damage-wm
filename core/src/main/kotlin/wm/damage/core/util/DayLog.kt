package wm.damage.core.util

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * The log kept on disk for [KEEP_DAYS] days (build Q, Adam 2026-11-17: "let's make the phone log hold a lot more
 * than a day … since it's just text"). Installed as a [Log.Sink], so every line that passes the log's level gate
 * is also appended to the file of its day in [dir], `log-YYYYMMDD.txt`, with its date and time on the line.
 *
 * The caller never touches the disk: [log] stamps the line and hands it to a queue, and returns. One writer thread
 * appends and flushes once the queue is empty. A full queue drops the line and counts it, and the count is written
 * as a line when the writer catches up. A day's file stops at [DAY_CAP_BYTES] with one line saying so (a repeating
 * fault must not fill the phone), and the count of the lines not written goes into the next day's first line.
 * `token=` values are masked as a line is written (the setup link carries the token). Files older than [KEEP_DAYS]
 * days are removed when the writer starts and at the day's turn: that is the only deletion.
 */
class DayLog(
    private val dir: Path,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    queueLines: Int = QUEUE_LINES,
    /** Tests only: runs on the writer thread before each line is written (a slow disk). */
    internal val beforeWrite: () -> Unit = {},
) : Log.Sink {

    private class Entry(val day: LocalDate, val text: String)

    private val queueCap = queueLines
    private val queue = ArrayBlockingQueue<Entry>(queueLines)
    private val dropped = AtomicLong()

    // the writer thread's own state
    private var day: LocalDate? = null
    private var out: OutputStream? = null
    private var bytes = 0L
    private var capped = false
    private var unwritten = 0L
    /** A write failed: lines are counted until one is written again, and the failure was said once. */
    private var failing: String? = null
    private var failed = 0L

    override fun log(level: Log.Level, tag: String, message: String) {
        val t = now()
        if (!queue.offer(Entry(t.toLocalDate(), "${t.format(STAMP)} ${level.name.first()} $tag: $message"))) dropped.incrementAndGet()
    }

    private fun run() {
        guarded { prune(now().toLocalDate()) }
        while (true) {
            val e = queue.take()
            guarded {
                beforeWrite()
                write(e)
                val d = dropped.getAndSet(0)
                if (d > 0) write(Entry(e.day, "${now().format(STAMP)} W daylog: $d line(s) not kept: the queue of $queueCap lines was full while the writer was behind"))
                if (queue.isEmpty()) flush()
            }
        }
    }

    /** A fault on one line never ends the writer: the line is counted, and the fault said once (its own line is
     *  queued like any other, so saying it per line would feed itself). */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            failed++
            if (failing == null) {
                failing = "${e::class.simpleName}: ${e.message}"
                Log.e("daylog", "the day's log writer failed on a line: $failing")
            }
        }
    }

    private fun write(e: Entry) {
        if (e.day != day) turn(e.day)
        val line = (mask(e.text) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes + line.size > DAY_CAP_BYTES) {
            if (capped) { unwritten++; return }
            capped = true
            unwritten++
            append("${now().format(STAMP)} W daylog: this day's file reached ${DAY_CAP_BYTES shr 20} MiB: the lines after this one are counted, and the count is the next day's first line\n".toByteArray(Charsets.UTF_8))
            return
        }
        append(line)
    }

    /** The day's turn: the next file, the files past [KEEP_DAYS] removed, the previous day's count carried. */
    private fun turn(next: LocalDate) {
        val was = day
        val carried = unwritten
        close()
        day = next
        unwritten = 0
        val f = dir.resolve(nameOf(next))
        bytes = try { if (Files.isRegularFile(f)) Files.size(f) else 0L } catch (e: IOException) { 0L }
        capped = bytes >= DAY_CAP_BYTES          // a restart on a day already at the cap: its line is in the file
        if (was != null) prune(next)
        if (carried > 0 && was != null)
            write(Entry(next, "${now().format(STAMP)} W daylog: $carried line(s) of $was not written: that day's file reached ${DAY_CAP_BYTES shr 20} MiB"))
    }

    private fun append(b: ByteArray) {
        try {
            val o = out ?: open().also { out = it }
            o.write(b)
            bytes += b.size
            if (failing != null) {
                val n = failed
                failing = null; failed = 0
                val back = "${now().format(STAMP)} W daylog: $n line(s) not written while the file could not be written\n".toByteArray(Charsets.UTF_8)
                o.write(back); bytes += back.size
            }
        } catch (e: IOException) {
            close()
            failed++
            if (failing == null) {
                failing = e.message ?: e::class.simpleName
                // said once per failure, through the other sinks too (this line is queued and counted while failing)
                Log.e("daylog", "the day's log file could not be written (${dir.resolve(nameOf(day ?: now().toLocalDate()))}): $failing")
            }
        }
    }

    private fun open(): OutputStream {
        Files.createDirectories(dir)
        val f = dir.resolve(nameOf(day ?: now().toLocalDate()))
        return BufferedOutputStream(Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND), 64 * 1024)
    }

    private fun flush() {
        try { out?.flush() } catch (e: IOException) { close(); failed++; if (failing == null) { failing = e.message; Log.e("daylog", "the day's log file could not be flushed: ${e.message}") } }
    }

    private fun close() {
        val o = out ?: return
        out = null
        try { o.close() } catch (e: IOException) { Log.w("daylog", "closing the day's log file: ${e.message}") }
    }

    /** The files whose day is more than [KEEP_DAYS] days before [today]. */
    private fun prune(today: LocalDate) {
        val names = try { Files.newDirectoryStream(dir).use { ds -> ds.map { it.fileName.toString() } } } catch (e: IOException) { return }
        for (n in names) {
            val d = dayOf(n) ?: continue
            if (ChronoUnit.DAYS.between(d, today) <= KEEP_DAYS) continue
            try {
                Files.delete(dir.resolve(n))
                Log.i("daylog", "removed $n: older than $KEEP_DAYS days")
            } catch (e: IOException) {
                Log.w("daylog", "$n is older than $KEEP_DAYS days and could not be removed: ${e.message}")
            }
        }
    }

    // declared last: the writer starts once every field above is set
    private val writer = Thread(::run, "damage-daylog").apply { isDaemon = true; start() }

    companion object {
        const val KEEP_DAYS = 10L
        const val DAY_CAP_BYTES = 32L shl 20
        const val QUEUE_LINES = 10_000
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
        /** A day file's name in full (the routes resolve only names that match it). */
        val NAME = Regex("log-(\\d{8})\\.txt")
        private val TOKEN = Regex("(token=)[^&\\s\"'<>]+")

        fun nameOf(d: LocalDate): String = "log-${d.format(DAY)}.txt"

        fun dayOf(name: String): LocalDate? {
            val m = NAME.matchEntire(name) ?: return null
            return try { LocalDate.parse(m.groupValues[1], DAY) } catch (e: java.time.format.DateTimeParseException) { null }
        }

        fun mask(s: String): String = if (!s.contains("token=")) s else TOKEN.replace(s, "$1***")
    }
}
