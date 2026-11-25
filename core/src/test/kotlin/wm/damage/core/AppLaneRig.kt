package wm.damage.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.AppWire
import wm.damage.core.app.OpJson
import wm.damage.core.app.Welcome
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.net.WinService

/*
 * Build S1-1, package P2: the rig the `app` lane's tests share — P1's [RecordRig] host (the made-up window `t`), an
 * [AppNet] over it, and a real `ContentHostServer` bound to loopback port 0 (never another interface, never the
 * service's port), with a raw lane client ([RawLane]) that speaks the frames by hand. Nothing touches the real data
 * directory.
 */
internal class AppLaneRig(
    val rec: RecordRig = RecordRig(),
    val host: AppHost = rec.opened(),
    contracts: Map<String, Int> = mapOf("t" to 1),
    outboxCap: Int = AppNet.OUTBOX_CAP,
    @Volatile var localSecret: String? = null,
    onLoopback: ((Socket) -> Boolean)? = null,
    win: Map<String, WinService> = emptyMap(),
    withApp: Boolean = true,
) : AutoCloseable {
    val net = AppNet(host, contracts, outboxCap, clock = { rec.now })
    val server: ContentHostServer

    init {
        val books = Files.createDirectories(rec.dir.resolve("books"))
        server = if (onLoopback == null) ContentHostServer(LocalContent(books), 0, TOKEN, win = win,
            app = net.takeIf { withApp }, localSecret = { localSecret }, bindAddress = InetAddress.getLoopbackAddress())
        else ContentHostServer(LocalContent(books), 0, TOKEN, win = win, app = net.takeIf { withApp },
            localSecret = { localSecret }, onLoopback = onLoopback, bindAddress = InetAddress.getLoopbackAddress())
        server.start()
    }

    val port: Int get() = server.boundPort

    /** A queued op by [device] on window `t`, answered by the record directly (another device's write). */
    fun write(device: String, stream: String, n: Long, item: String, value: String): Answer =
        rec.ask(host, op(device, stream, n, "set", listOf(item), j(value)))

    override fun close() {
        server.close()
        net.close()
        rec.cleanUp()
    }

    companion object {
        const val TOKEN = "tok"
    }
}

/** A lane client written by hand: the content hello, the lane's hello, then every frame into a queue. */
internal class RawLane(
    port: Int,
    token: String = AppLaneRig.TOKEN,
    local: String? = null,
    stream: String = "s1",
    contracts: Map<String, Int> = mapOf("t" to 1),
    proto: Int = AppWire.PROTO,
    /** False: the content hello alone (a hello the host refuses). */
    lane: Boolean = true,
) : AutoCloseable {
    val sock = Socket(InetAddress.getLoopbackAddress(), port)
    private val out = DataOutputStream(sock.getOutputStream().buffered())
    private val inp = DataInputStream(sock.getInputStream().buffered())
    val frames = LinkedBlockingQueue<JsonObject>()
    @Volatile var ended = false

    init {
        send(buildJsonObject {
            put("t", "hello")
            put("token", token)
            local?.let { put("local", it) }
            put("proto", 1)
        })
        if (lane) send(AppWire.hello(stream, contracts, proto))
        Thread({
            try {
                while (true) frames.put(AppWire.read(inp))
            } catch (e: Exception) {
                ended = true
            }
        }, "raw-lane").apply { isDaemon = true }.start()
    }

    fun send(o: JsonObject) = synchronized(out) { AppWire.write(out, AppWire.encode(o)) }

    fun next(): JsonObject = frames.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no frame within 10 s")

    fun next(t: String): JsonObject {
        val f = next()
        assertEquals(t, AppWire.type(f), "the next frame: $f")
        return f
    }

    fun welcome(): Welcome = AppWire.parseWelcome(next("welcome"))

    /** Nothing else is queued for this connection: a pong goes through the executor behind everything before it. */
    fun quiet(k: Long = 9_999) {
        send(AppWire.ping(k))
        val f = next()
        assertEquals("pong", AppWire.type(f), "nothing was to come before the pong, but: $f")
    }

    fun op(o: wm.damage.core.app.Op) = send(AppWire.op(o))

    fun opJson(o: JsonObject) = send(buildJsonObject { put("t", "op"); put("op", o) })

    fun answer(): Answer = OpJson.answer(next("ans")["answer"] as JsonObject)

    override fun close() {
        try { sock.close() } catch (e: Exception) { /* closing a test socket */ }
    }
}
