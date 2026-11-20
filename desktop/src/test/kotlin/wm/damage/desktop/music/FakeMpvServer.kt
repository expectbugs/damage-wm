package wm.damage.desktop.music

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A stand-in for the player's JSON IPC socket (S1-4 §4): a UNIX socket server in a short temp directory that records
 * every command, answers as mpv 0.41 did in the hand run of 2026-11-19 (`loadfile` → `{"playlist_entry_id":n}` then a
 * `start-file`, `get_property pid` → its pid, anything else → success with no data), refuses the commands named in
 * [refuse], stops answering when [answering] is false, and emits events on cue. Plays nothing.
 */
class FakeMpvServer(val path: Path) : AutoCloseable {
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(path)) }
    private val clients = CopyOnWriteArrayList<SocketChannel>()
    private val entries = AtomicLong(0)
    /** Every command array received, in order. */
    val received = CopyOnWriteArrayList<JsonArray>()
    @Volatile var answering = true
    /** Emit `start-file` right after a `loadfile`'s answer, as mpv does. */
    @Volatile var autoStart = true
    /** Command name → the error it is answered with. */
    val refuse = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** A hook that may take a command first (FakeMpvMain's test commands); true = handled, no answer. */
    @Volatile var intercept: (JsonArray) -> Boolean = { false }

    init {
        Thread({
            try {
                while (true) {
                    val c = server.accept()
                    clients.add(c)
                    Thread({ serve(c) }, "fake-mpv-client").apply { isDaemon = true }.start()
                }
            } catch (e: IOException) {
                // the server was closed
            }
        }, "fake-mpv-accept").apply { isDaemon = true }.start()
    }

    /** The commands as compact JSON arrays, e.g. `["seek",3.0,"absolute"]`. */
    fun commands(): List<String> = received.map { it.toString() }

    fun emit(line: String) {
        for (c in clients) send(c, line)
    }

    private fun send(c: SocketChannel, line: String) {
        synchronized(c) {
            try {
                val b = ByteBuffer.wrap((line + "\n").toByteArray())
                while (b.hasRemaining()) c.write(b)
            } catch (e: IOException) {
                clients.remove(c)
            }
        }
    }

    private fun serve(c: SocketChannel) {
        val buf = ByteBuffer.allocate(8192)
        val line = java.io.ByteArrayOutputStream()
        try {
            while (c.read(buf.clear()) >= 0) {
                buf.flip()
                while (buf.hasRemaining()) {
                    val b = buf.get()
                    if (b == '\n'.code.toByte()) { handle(c, line.toString(Charsets.UTF_8)); line.reset() } else line.write(b.toInt())
                }
            }
        } catch (e: IOException) {
            // the client went away
        }
        clients.remove(c)
    }

    private fun handle(c: SocketChannel, text: String) {
        val msg = Json.parseToJsonElement(text) as JsonObject
        val cmd = msg["command"] as JsonArray
        val id = (msg["request_id"] as JsonPrimitive).longOrNull
        try { answer(c, cmd, id) } finally { received.add(cmd) } // recorded once answered: a test that saw it can emit after it
    }

    private fun answer(c: SocketChannel, cmd: JsonArray, id: Long?) {
        if (intercept(cmd) || !answering) return
        val name = (cmd[0] as JsonPrimitive).contentOrNull
        val err = refuse[name]
        when {
            err != null -> send(c, """{"request_id":$id,"error":"$err"}""")
            name == "loadfile" -> {
                val n = entries.incrementAndGet()
                send(c, """{"data":{"playlist_entry_id":$n},"request_id":$id,"error":"success"}""")
                if (autoStart) send(c, """{"event":"start-file","playlist_entry_id":$n}""")
            }
            name == "get_property" && (cmd[1] as JsonPrimitive).contentOrNull == "pid" ->
                send(c, """{"data":${ProcessHandle.current().pid()},"request_id":$id,"error":"success"}""")
            else -> send(c, """{"data":null,"request_id":$id,"error":"success"}""")
        }
    }

    override fun close() {
        try { server.close() } catch (e: IOException) { System.err.println("fake server close: ${e.message}") }
        for (c in clients) try { c.close() } catch (e: IOException) { System.err.println("fake client close: ${e.message}") }
        Files.deleteIfExists(path)
    }
}

/** Test harness: paced until [cond] holds, failing loudly after 10 s (a harness wait, never product code). */
fun waitUntil(what: String, cond: () -> Boolean) {
    val until = System.nanoTime() + 10_000_000_000L
    while (!cond()) {
        if (System.nanoTime() > until) throw AssertionError("waited 10 s for: $what")
        Thread.sleep(5)
    }
}
