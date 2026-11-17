package wm.damage.core.app

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P3 (`CONTRACT.md` §2.3 step 2, §2.4 "Numbering" and "Coalescing", §13 (b) "op queue"; the spec's
 * §2 P3 and §3): the phone's queue of ops the PC has not answered, on disk in `queue.jsonl`.
 *
 * An op is numbered once (the next `n`, read once per op) and its line is appended by the queue's own writer thread,
 * forced to disk; it is sent only once that line has landed — never on the caller's thread, and never a version that
 * has not landed. An unsent position op takes the next one for the same item into itself, its result and its input
 * time; a sent op is frozen. An op leaves on its answer; its line leaves the file later (`forget`, once the working
 * copy's saved state holds its effect), so a phone that restarts in between still shows it and sends it again (L1 C1:
 * the PC applies an op twice to no effect, so a resend is never a question). A failed append is said once per streak,
 * the op kept in memory and tried again at the wire's next ping (or the next op); it is not sent until its line lands.
 *
 * The file: a header `{"stream", "next"}` first, then `{"op", "v"}` (an op; a later line of the same `n` is its
 * coalesced body) and `{"done": n}` lines. It is rewritten whole (temp, forced, moved) at each process start and when
 * it grows past [REWRITE_LINES], with the header and every line still held.
 */
class OpQueue(
    val file: Path,
    private val writer: Executor = defaultWriter(),
    private val append: (Path, String) -> Unit = ::appendForced,
    private val rewrite: (Path, String) -> Unit = { f, t -> Persistence.writeAtomic(f, t, sync = true) },
    private val note: (String) -> Unit = {},
    /** On the writer thread, after a line landed: the wire may send. */
    private val onLanded: () -> Unit = {},
    private val mint: () -> String = ::newStream,
) {
    private class Entry(var op: Op, var version: Int) {
        var landed = 0                 // the version whose line is on disk; 0 = none
        var landedLine: String? = null
        var frozen = false             // sent (or possibly sent, before a restart): its body never changes again
        var sentGen = 0L               // the connection it was sent on; 0 = not sent on the live one
        var failed = false             // its last append failed: tried again by [retry]
        val n: Long get() = (op.id as OpId.Queued).n
    }

    private val lock = Any()
    private val live = ArrayList<Entry>()                  // unanswered, in `n` order
    private val held = LinkedHashMap<Long, String>()       // answered, their lines still in the file: n → line
    private var lines = 0
    private var failures = 0

    @Volatile var stream: String = ""
        private set
    private var nextN = 1L

    /** The ops not answered yet, in order. */
    fun ops(): List<Op> = synchronized(lock) { live.map { it.op } }

    val waiting: Int get() = synchronized(lock) { live.size }

    val next: Long get() = synchronized(lock) { nextN }

    /**
     * Reads the file once (a process start) and rewrites it whole: the ops it holds, in order, all frozen (any of them
     * may have been sent before the restart). No file means a new stream, said (C§13 (b): a lost store means a new
     * stream); an incomplete last line or a line that does not decode is said, and the file as it was is kept beside
     * it. L1 C1: an op 0.67 wrote, with no input time, takes the load's ([OpJson.op]).
     */
    fun load(): List<Op> = synchronized(lock) {
        val loadedAt = System.currentTimeMillis()
        live.clear()
        held.clear()
        if (!Files.exists(file)) {
            stream = mint()
            nextN = 1
            say("no queue file: a new stream for this device ($stream)")
            rewriteNow()
            return emptyList()
        }
        val text = try {
            Files.readAllBytes(file).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            stream = mint()
            nextN = 1
            val line = "the queue file could not be read (${e::class.simpleName}: ${e.message}); a new stream ($stream)"
            Log.e(TAG, line)
            note("FAILED: $line")
            rewriteNow()
            return emptyList()
        }
        val problems = ArrayList<String>()
        val body = if (text.isEmpty() || text.endsWith("\n")) text else {
            problems += "an incomplete last line (an append the process ended inside: never sent)"
            text.substring(0, text.lastIndexOf('\n') + 1)
        }
        var headerStream: String? = null
        var headerNext = 1L
        val ops = HashMap<Long, Pair<Op, String>>()
        val done = HashSet<Long>()
        var maxN = 0L
        for ((i, line) in body.lineSequence().withIndex()) {
            if (line.isBlank()) continue
            try {
                val o = json.parseToJsonElement(line) as? JsonObject ?: throw IllegalArgumentException("not an object")
                when {
                    o["op"] != null -> {
                        val op = OpJson.op(o["op"] as? JsonObject ?: throw IllegalArgumentException("op"), defaultAt = loadedAt)
                        val q = op.queued ?: throw IllegalArgumentException("an op without a stream")
                        ops[q.n] = op to line
                        maxN = maxOf(maxN, q.n)
                    }
                    o["done"] != null -> OpJson.longOf(o["done"])?.let { done += it; maxN = maxOf(maxN, it) }
                        ?: throw IllegalArgumentException("done")
                    o["stream"] != null -> {
                        headerStream = OpJson.str(o, "stream")
                        headerNext = OpJson.num(o, "next")
                    }
                    else -> throw IllegalArgumentException("no op, done or stream")
                }
            } catch (e: Exception) {
                problems += "line ${i + 1} does not decode (${e.message})"
            }
        }
        val s = headerStream ?: ops.values.firstOrNull()?.first?.queued?.stream
        if (s == null) {
            stream = mint()
            problems += "no stream in the file: a new stream ($stream)"
        } else {
            stream = s
        }
        for ((n, p) in ops.toSortedMap()) {
            if (n in done) continue
            if (p.first.queued!!.stream != stream) {
                problems += "op $n of stream ${p.first.queued!!.stream}, not $stream: not sent"
                continue
            }
            live += Entry(p.first, 1).apply { landed = 1; landedLine = p.second; frozen = true }
        }
        nextN = maxOf(headerNext, maxN + 1)
        if (problems.isNotEmpty()) {
            val kept = file.resolveSibling("${file.fileName}.bad-${System.currentTimeMillis()}")
            try {
                Files.copy(file, kept)
            } catch (e: Exception) {
                problems += "the file could not be kept as ${kept.fileName} (${e.message})"
            }
            Log.e(TAG, "the queue file: ${problems.joinToString("; ")}; kept as ${kept.fileName}")
            note("FAILED: the queue file: ${problems.joinToString("; ")}")
        }
        rewriteNow()
        note("opened: stream $stream, ${live.size} op(s) waiting, next $nextN")
        return live.map { it.op }
    }

    /**
     * Takes an op: [make] builds it from its number. With [coalesce] (a position op), the last waiting op it matches
     * takes the new result and input time into itself, keeping its own number — unless that op was sent, which
     * freezes it (C§2.4 "Coalescing"). Returns the op as it now stands. Called on the core executor; the line is
     * appended on the writer thread.
     */
    fun add(make: (OpId.Queued) -> Op, coalesce: ((Op) -> Boolean)? = null): Op = synchronized(lock) {
        if (coalesce != null) {
            val e = live.lastOrNull { coalesce(it.op) }
            if (e != null && !e.frozen) {
                val fresh = make(e.op.id as OpId.Queued)
                e.op = e.op.copy(result = fresh.result, params = fresh.params, base = fresh.base, at = fresh.at)
                e.version++
                schedule(e)
                return e.op
            }
        }
        val e = Entry(make(OpId.Queued(stream, nextN++)), 1)
        live += e
        schedule(e)
        e.op
    }

    /**
     * The next op to send on the connection [gen], marked sent there and frozen — the oldest op not yet sent on it,
     * and only once its present version has landed; null when there is none, when that op has not landed (nothing
     * after it may go first: the PC takes a stream in order), or when [can] refuses it.
     */
    fun nextToSend(gen: Long, can: (Op) -> Boolean): Op? = synchronized(lock) {
        val e = live.firstOrNull { it.sentGen != gen } ?: return null
        if (e.landed != e.version) return null
        if (!can(e.op)) return null
        e.sentGen = gen
        e.frozen = true
        e.op
    }

    /** From [n] on, nothing counts as sent on the live connection: sent again from there (a retry, a failed write). */
    fun resendFrom(n: Long) = synchronized(lock) {
        for (e in live) if (e.n >= n) e.sentGen = 0
    }

    /** The answered op [n] leaves the waiting list (its line stays until [forget]); null when it is not waiting. */
    fun answered(n: Long): Op? = synchronized(lock) {
        val i = live.indexOfFirst { it.n == n }
        if (i < 0) return null
        leave(live.removeAt(i)).op
    }

    private fun leave(e: Entry): Entry {
        e.landedLine?.let { held[e.n] = it }
        return e
    }

    /** The lines of answered ops leave the file: a `done` line each, on the writer thread. Ops of another stream are
     *  passed over. */
    fun forget(ids: Collection<OpId.Queued>) {
        if (ids.isEmpty()) return
        val list = ids.toList()
        hand("done lines") {
            val s = synchronized(lock) { list.filter { it.stream == stream && it.n in held }.map { it.n } }
            try {
                if (s.isNotEmpty()) append(file, s.joinToString("") { "{\"done\":$it}\n" })
                synchronized(lock) {
                    for (n in s) held.remove(n)
                    lines += s.size
                    if (lines > REWRITE_LINES) rewriteNow()
                }
            } catch (e: Exception) {
                // not a loss: a restart sends them again and the PC answers from its record
                Log.w(TAG, "the queue's done lines for ${s.size} op(s) could not be written: ${e.message}")
            }
        }
    }

    /** Appends that failed are tried again: the wire's next ping, or the next op. */
    fun retry() {
        hand("a retry") { synchronized(lock) { for (e in live) if (e.failed) schedule(e) } }
    }

    /** Hands [e]'s present version to the writer. Under [lock]. */
    private fun schedule(e: Entry) {
        val v = e.version
        val line = buildJsonObject { put("op", OpJson.op(e.op)); put("v", v) }.toString()
        hand("op ${e.n}") { land(e, v, line) }
    }

    private fun land(e: Entry, v: Int, line: String) {
        synchronized(lock) {
            if (e.version != v || live.none { it === e } || e.landed == v) return   // a newer version follows, or it left
        }
        try {
            append(file, line + "\n")
        } catch (ex: Exception) {
            synchronized(lock) { e.failed = true }
            failed("op ${e.n} could not be appended", ex)
            return
        }
        synchronized(lock) {
            lines++
            if (e.version == v) {
                e.landed = v
                e.landedLine = line
                e.failed = false
            }
        }
        ok()
        onLanded()
    }

    /** The file rewritten whole: the header, then every line still held, landed ones as they landed and the rest as
     *  they stand (they land with it). Under [lock], on the writer thread or at the load. */
    private fun rewriteNow() {
        val sb = StringBuilder()
        sb.append(buildJsonObject { put("stream", stream); put("next", nextN) }.toString()).append('\n')
        for (l in held.values) sb.append(l).append('\n')
        val written = ArrayList<Pair<Entry, String>>()
        for (e in live) {
            val line = buildJsonObject { put("op", OpJson.op(e.op)); put("v", e.version) }.toString()
            written += e to line
            sb.append(line).append('\n')
        }
        try {
            rewrite(file, sb.toString())
        } catch (ex: Exception) {
            failed("the queue file could not be rewritten", ex)
            for ((e, _) in written) e.failed = e.landed != e.version
            return
        }
        ok()
        lines = 1 + held.size + live.size
        for ((e, line) in written) {
            e.landed = e.version
            e.landedLine = line
            e.failed = false
        }
        if (written.isNotEmpty()) hand("the rewrite's ops") { onLanded() }
    }

    /** A failure said once when a run of them starts (`Log.e`, a journal note) and once when it ends. */
    private fun failed(what: String, e: Exception) {
        val first = synchronized(lock) { failures++ == 0 }
        if (!first) return
        val line = "$what (${e::class.simpleName}: ${e.message}); the op stays in memory, is not sent until its line " +
            "lands, and is tried again at the next ping or op"
        Log.e(TAG, line)
        note("FAILED: $line")
    }

    private fun ok() {
        val n = synchronized(lock) { failures.also { failures = 0 } }
        if (n == 0) return
        val line = "the queue file is written again after $n failure(s)"
        Log.i(TAG, line)
        note(line)
    }

    private fun say(line: String) {
        Log.w(TAG, line)
        note(line)
    }

    /** [task] to the writer; after the writer has ended (the service's stop) it is said and not done — the line is
     *  then written by the next process start, or its op sent again from the file (never a throw on the caller). */
    private fun hand(what: String, task: () -> Unit) {
        try {
            writer.execute(task)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "$what after the queue's writer ended: not written")
        }
    }

    /** Ends the writer after the lines handed to it. */
    fun close() {
        (writer as? ExecutorService)?.shutdown()
    }

    companion object {
        private const val TAG = "queue"
        /** Past this many lines since the last rewrite, the file is rewritten with only what it still holds. */
        const val REWRITE_LINES = 2_000
        private val json = Json { ignoreUnknownKeys = true }
        private val random = SecureRandom()

        fun defaultWriter(): ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "damage-queue-writer").apply { isDaemon = true }
        }

        /** A random 64-bit stream, as text (C§2.4: a browser's numbers hold 53 bits). */
        fun newStream(): String = java.lang.Long.toUnsignedString(random.nextLong(), 16)

        /** Appends [text] at the end of [file] and forces it to disk before returning: a line counts as landed only
         *  then (a phone that loses power must not send a number again that the PC already took). */
        fun appendForced(file: Path, text: String) {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
                val buf = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
        }
    }
}
