package wm.damage.core.app

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.comp.Journal

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P1 (`CONTRACT.md` §9.7 item 1, §13 (b) "operation journal"; dec. A.12, B.1): the PC record's
 * append-only operation log, `oplog.jsonl` in the data directory (never `journal.jsonl`, which is the flush journal).
 * One line per op: its `seq`, the op, its effect and its answer, appended, written and forced
 * to disk BEFORE the op is answered or its change published. `record.json` covers the lines up to its `seq`; a start
 * replays the lines past it. Nothing here deletes a line: the file is set aside whole (a dated name beside it) at a
 * process start once a save covers all of it and it is past [AppHost.LOG_SET_ASIDE_BYTES] or holds a bad line.
 * The core executor alone appends.
 */
class OpLog(val file: Path, private val sync: (FileChannel) -> Unit = { it.force(true) }) {

    /** One line: the op's [seq] and what it did. */
    data class Line(val seq: Long, val op: Op, val effect: Effect, val answer: Answer)

    /** What a start found: the lines past the covered `seq` ([lines]), how many covered lines it passed over, whether
     *  the last line was incomplete (it was cut), and every line that did not decode. */
    class Opened(
        val lines: List<Line>,
        val passed: Int,
        val incomplete: Boolean,
        val bad: List<String>,
    )

    private var ch: FileChannel? = null
    /** The length of the file's complete lines: where the next line goes. */
    private var size = 0L
    /** A failed append whose bytes could not be cut back: the next append cuts them first, or fails. */
    private var tailDirty = false

    val length: Long get() = size

    /**
     * Opens the log for appending and reads it once. Lines with `seq` at or below [after] (covered by `record.json`)
     * are passed over without decoding; the rest are decoded in order. An incomplete last line (a write that a stop
     * cut short — never answered, since the answer follows the force: the device sends it again) is cut, so the next
     * line starts clean, and the caller says so (L1 C6). A line that does not decode, or whose `seq` does not rise, is
     * named in [Opened.bad] and skipped. Opened again (L1 R-a: a start's open that failed), the earlier channel is
     * closed first.
     */
    fun open(after: Long): Opened {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        ch?.close()
        val c = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
        ch = c
        val bytes = ByteArray(c.size().toInt().also { require(c.size() <= Int.MAX_VALUE) { "the log is past 2 GiB" } })
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) if (c.read(buf, buf.position().toLong()) < 0) break
        var end = bytes.size
        while (end > 0 && bytes[end - 1] != NL) end--
        val incomplete = end < bytes.size
        if (incomplete) {
            c.truncate(end.toLong())
            c.force(true)
        }
        size = end.toLong()
        val lines = ArrayList<Line>()
        val bad = ArrayList<String>()
        var passed = 0
        var lastSeq = Long.MIN_VALUE
        var start = 0
        var no = 0
        while (start < end) {
            var nl = start
            while (bytes[nl] != NL) nl++
            no++
            val peeked = peekSeq(bytes, start, nl)
            if (peeked != null && peeked <= after) {
                passed++
                lastSeq = maxOf(lastSeq, peeked)
            } else {
                try {
                    val line = decode(String(bytes, start, nl - start, Charsets.UTF_8))
                    if (line.seq <= lastSeq) throw IllegalArgumentException("seq ${line.seq} after $lastSeq")
                    lastSeq = line.seq
                    if (line.seq <= after) passed++ else lines += line
                } catch (e: Exception) {
                    bad += "line $no: ${e::class.simpleName}: ${e.message}"
                }
            }
            start = nl + 1
        }
        return Opened(lines, passed, incomplete, bad)
    }

    /**
     * Appends [line], writes it and forces it to disk; returns only once it has landed. Throws what the write or the
     * force throws, after cutting the file back to its length before the call (a refused op leaves no line for a
     * replay to apply); when even the cut fails, the next append cuts first or fails.
     */
    fun append(line: Line) {
        val c = ch ?: throw IOException("the operation log is not open")
        if (tailDirty) {
            c.truncate(size)
            tailDirty = false
        }
        val bytes = (encode(line) + "\n").toByteArray(Charsets.UTF_8)
        try {
            val buf = ByteBuffer.wrap(bytes)
            var at = size
            while (buf.hasRemaining()) at += c.write(buf, at)
            sync(c)
            size += bytes.size
        } catch (e: Exception) {
            try {
                c.truncate(size)
            } catch (t: Exception) {
                tailDirty = true
            }
            throw e
        }
    }

    /** Sets the whole file aside under a dated name beside it (`oplog-20261114-001700.jsonl`) and starts a fresh one.
     *  For a process start only, once a save covers every line. Throws when the rename fails (the log is then
     *  reopened as it was). */
    fun setAside(now: LocalDateTime = LocalDateTime.now()): Path {
        val target = file.resolveSibling(Journal.archiveName(file, now))
        ch = null
        try {
            Files.move(file, target)   // no options: an existing target is refused, never replaced
        } finally {
            val c = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            ch = c
            size = c.size()
            tailDirty = false
        }
        return target
    }

    fun close() {
        ch = null
    }

    companion object {
        private const val NL = '\n'.code.toByte()
        private val PREFIX = "{\"seq\":".toByteArray(Charsets.US_ASCII)

        fun encode(l: Line): String = buildJsonObject {
            put("seq", l.seq)                       // first: a start reads it without decoding the line
            put("op", OpJson.op(l.op))
            put("eff", RecordJson.effect(l.effect))
            put("ans", OpJson.answer(l.answer))
        }.toString()

        /** L1 C1: a 0.67 line's `hw`, its op's `expect` and a `lost` answer are read past (a replay needs only the
         *  line's absolute effect). */
        fun decode(s: String): Line {
            val j = Json.parseToJsonElement(s) as? JsonObject ?: throw IllegalArgumentException("not an object")
            return Line(
                seq = OpJson.num(j, "seq"),
                op = OpJson.op(j["op"] as? JsonObject ?: throw IllegalArgumentException("no op")),
                effect = RecordJson.effect(j["eff"] as? JsonObject ?: throw IllegalArgumentException("no eff")),
                answer = OpJson.answer(j["ans"] as? JsonObject ?: throw IllegalArgumentException("no ans")),
            )
        }

        /** The `seq` a line opens with, read from its bytes; null when the line does not open as [encode] writes. */
        private fun peekSeq(b: ByteArray, from: Int, to: Int): Long? {
            if (to - from < PREFIX.size + 2) return null
            for (i in PREFIX.indices) if (b[from + i] != PREFIX[i]) return null
            var i = from + PREFIX.size
            var v = 0L
            var digits = 0
            while (i < to && b[i] in '0'.code.toByte()..'9'.code.toByte() && digits < 18) {
                v = v * 10 + (b[i] - '0'.code.toByte())
                i++
                digits++
            }
            return if (digits > 0 && i < to && b[i] == ','.code.toByte()) v else null
        }
    }
}
