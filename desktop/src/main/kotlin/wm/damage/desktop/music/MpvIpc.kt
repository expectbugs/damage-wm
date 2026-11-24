package wm.damage.desktop.music

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/**
 * The PC player's JSON IPC client (S1-4 lane P; `man mpv` 0.41, "JSON IPC"; the forms seen in the hand run of
 * 2026-11-19, `private/build078/S1-4-P-built.md`): one UNIX socket connection, one line of JSON per message.
 * A command carries a `request_id` and is answered in order (`{"request_id":n,"error":"success","data":…}`);
 * events (`{"event":"end-file",…}`) and observed property changes (`{"event":"property-change","name":…,
 * "data":…}`, no `data` = unavailable) arrive between answers. Nothing here waits for an answer: a command takes a
 * callback, and [oldestUnanswered] lets [MpvProcess]'s supervisor end a player that stops answering.
 *
 * Reads and writes go straight to the channel: `Channels.newInputStream`/`newOutputStream` on one blocking channel
 * share its blocking lock, so a waiting read would hold every write back.
 */
class MpvIpc private constructor(private val ch: SocketChannel, private val listener: Listener) : AutoCloseable {

    /** Called on the reading thread. */
    interface Listener {
        fun event(name: String, msg: JsonObject)
        /** An observed property changed; [value] null = the property is unavailable (no file, after a stop). */
        fun property(name: String, value: JsonElement?)
        /** The connection ended (the player quit, or [close]). Every unanswered command was answered first. */
        fun closed(detail: String) {}
    }

    /** The player's answer; [ok] false carries mpv's `error` ("property unavailable", "error running command", …). */
    class Answer(val ok: Boolean, val error: String, val data: JsonElement?)

    private class Pending(val what: String, val atMs: Long, val then: (Answer) -> Unit)

    private val pending = ConcurrentHashMap<Long, Pending>()
    private val ids = AtomicLong(0)
    private val observeIds = AtomicLong(0)
    private val writeLock = Any()
    private val ended = AtomicBoolean(false)

    /** False once the connection has ended. */
    val open: Boolean get() = !ended.get()
    /** When it ended (monotonic ms); 0 while open. */
    @Volatile var closedAtMs = 0L
        private set

    /** Send `{"command":[args…],"request_id":n}`; [then] runs on the reading thread with the answer (a failed
     *  write or an ended connection answers `ok = false` at once). Returns the request id. */
    fun command(vararg args: Any, then: (Answer) -> Unit = {}): Long {
        val id = ids.incrementAndGet()
        val what = args.firstOrNull()?.toString() ?: "?"
        if (!open) { then(Answer(false, "the player's connection has ended", null)); return id }
        pending[id] = Pending(what, nowMs(), then)
        if (!open) { pending.remove(id)?.let { answer(it, Answer(false, "the player's connection has ended", null)) }; return id }
        val line = buildJsonObject {
            put("command", JsonArray(args.map { toJson(it) }))
            put("request_id", id)
        }.toString() + "\n"
        try {
            synchronized(writeLock) {
                val buf = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                while (buf.hasRemaining()) ch.write(buf)
            }
        } catch (e: IOException) {
            pending.remove(id)?.let { answer(it, Answer(false, "write failed: ${e.message}", null)) }
            end("write failed: ${e.message}")
        }
        return id
    }

    /** `observe_property` [name]: its changes reach [Listener.property]. A refusal is said in the log. */
    fun observe(name: String) {
        command("observe_property", observeIds.incrementAndGet(), name) { a ->
            if (!a.ok) Log.w(TAG, "observe $name refused: ${a.error}")
        }
    }

    /** The oldest command still unanswered: its name and how long it has waited (ms), or null. */
    fun oldestUnanswered(now: Long = nowMs()): Pair<String, Long>? =
        pending.values.minByOrNull { it.atMs }?.let { it.what to (now - it.atMs) }

    override fun close() {
        end("closed by Damage")
    }

    private fun end(detail: String) {
        if (!ended.compareAndSet(false, true)) return
        closedAtMs = nowMs()
        try { ch.close() } catch (e: IOException) { Log.w(TAG, "socket close: ${e.message}") }
        for (id in pending.keys.toList()) pending.remove(id)?.let { answer(it, Answer(false, "the player's connection ended ($detail)", null)) }
        try { listener.closed(detail) } catch (e: Exception) { Log.e(TAG, "closed listener", e) }
    }

    private fun answer(p: Pending, a: Answer) {
        try { p.then(a) } catch (e: Exception) { Log.e(TAG, "answer to ${p.what}", e) }
    }

    private fun readLoop() {
        val buf = ByteBuffer.allocate(64 * 1024)
        var line = java.io.ByteArrayOutputStream()
        var detail = "the player closed the connection"
        try {
            while (true) {
                buf.clear()
                if (ch.read(buf) < 0) break
                buf.flip()
                while (buf.hasRemaining()) {
                    val b = buf.get()
                    if (b == '\n'.code.toByte()) {
                        dispatch(line.toString(Charsets.UTF_8))
                        line = java.io.ByteArrayOutputStream()
                    } else line.write(b.toInt())
                }
            }
        } catch (e: IOException) {
            if (open) detail = "read failed: ${e.message}"
        }
        end(detail)
    }

    private fun dispatch(text: String) {
        if (text.isBlank()) return
        val msg = try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
        if (msg == null) { Log.w(TAG, "unreadable line from the player: $text"); return }
        val event = (msg["event"] as? JsonPrimitive)?.contentOrNull
        try {
            when {
                event == "property-change" ->
                    listener.property((msg["name"] as? JsonPrimitive)?.contentOrNull ?: "", msg["data"]?.takeIf { it !is JsonNull })
                event != null -> listener.event(event, msg)
                else -> {
                    val id = (msg["request_id"] as? JsonPrimitive)?.longOrNull
                    val p = id?.let { pending.remove(it) }
                    if (p == null) { Log.w(TAG, "an answer nobody asked for: $text"); return }
                    val err = msg["error"]?.jsonPrimitive?.contentOrNull ?: "no error field"
                    answer(p, Answer(err == "success", err, msg["data"]?.takeIf { it !is JsonNull }))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handling a message from the player ($text)", e)
        }
    }

    companion object {
        private const val TAG = "music-pc"

        fun nowMs(): Long = System.nanoTime() / 1_000_000

        /** Connect to the player's socket at [path] and start reading. Throws [IOException] when nothing listens. */
        fun connect(path: Path, listener: Listener): MpvIpc {
            val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                ch.connect(UnixDomainSocketAddress.of(path))
            } catch (e: IOException) {
                ch.close(); throw e
            }
            val ipc = MpvIpc(ch, listener)
            Thread({ ipc.readLoop() }, "mpv-ipc").apply { isDaemon = true }.start()
            return ipc
        }

        private fun toJson(v: Any): JsonElement = when (v) {
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> throw IllegalArgumentException("an IPC argument must be a string, number or boolean: ${v::class.simpleName}")
        }
    }
}
