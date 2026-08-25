package wm.damage.core

import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import wm.damage.core.comp.Compositor
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.Layout
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.replica.ReplicaServer
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent
import wm.damage.core.wire.EvenHubMsg

/** HANDOFF.md §8.2 "Replica page": the handshake, the token gate, full panels
 *  on connect, dirty-row frames after a flush, and input frames as gestures. */
class ReplicaServerTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** A minimal RFC 6455 client: masked frames out, server frames in. */
    private class WsClient(port: Int, token: String) : AutoCloseable {
        val sock = Socket("127.0.0.1", port)
        val inp = DataInputStream(sock.getInputStream().buffered())
        val out = sock.getOutputStream()
        val accept: String

        init {
            out.write(("GET /ws?token=$token HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            out.flush()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(inp.read().toChar())
            val h = head.toString()
            assertTrue(h.startsWith("HTTP/1.1 101"), "upgrade refused: $h")
            accept = Regex("Sec-WebSocket-Accept: (\\S+)").find(h)!!.groupValues[1]
        }

        fun send(text: String) {
            val p = text.toByteArray()
            val mask = byteArrayOf(1, 2, 3, 4)
            out.write(0x81); out.write(0x80 or p.size)   // small frames only
            out.write(mask)
            for (i in p.indices) out.write(p[i].toInt() xor mask[i and 3].toInt())
            out.flush()
        }

        /** (opcode, payload) of the next frame. */
        fun read(): Pair<Int, ByteArray> {
            val b0 = inp.read(); val b1 = inp.read()
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = inp.readUnsignedShort().toLong() else if (len == 127L) len = inp.readLong()
            val p = ByteArray(len.toInt()); inp.readFully(p)
            return (b0 and 0x0F) to p
        }

        override fun close() { sock.close() }
    }

    @Test
    fun acceptKeyMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", ReplicaServer.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun pageTokenPanelsAndInput(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val sim = GlassFirmwareSim()
        val t = SimTransport(sim, scope, SimTransport.Timing(instant = true))
        val inputs = ArrayList<Int>()
        val server = ReplicaServer(port, "tok", { t.mirror }, { ReplicaServer.Status(transport = "sim", connected = true) },
            { synchronized(inputs) { inputs.add(it) } })
        server.start()
        try {
            // the token gate
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); s.getOutputStream().flush()
                val line = s.getInputStream().bufferedReader().readLine()
                assertTrue(line.contains("403"), "no token → 403, got: $line")
            }
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("GET /?token=tok HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); s.getOutputStream().flush()
                val body = s.getInputStream().bufferedReader().readText()
                assertTrue(body.startsWith("HTTP/1.1 200"), "page served")
                assertTrue(body.contains("Damage replica") && body.contains("/ws?token="), "the page is the replica")
            }

            val done = HashSet<Long>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.events.collect { if (it is TransportEvent.FlushDone) synchronized(done) { done.add(it.id) } }
            }
            t.start(Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480))))

            WsClient(port, "tok").use { c ->
                assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", c.accept)
                // two full panels first (blank → nothing to send until something is painted),
                // so paint through the pipeline and expect the changed rows
                val comp = Compositor()
                val l = Layout()
                comp.composed.fillRect(l.lens, 12 * 17)
                comp.requestKeyframe()
                while (comp.hasPending || comp.needsKeyframe) {
                    val a = comp.assembleFlush(Geometry.rectBudget(3)) ?: break
                    val id = t.submit(FlushRequest(a.ops, a.epoch, "", wide = a.wide))
                    val t0 = System.currentTimeMillis()
                    while (synchronized(done) { id !in done } && System.currentTimeMillis() - t0 < 10_000) delay(5)
                }
                var panelsSeen = 0
                var statusSeen = false
                val t0 = System.currentTimeMillis()
                while ((panelsSeen < 2 || !statusSeen) && System.currentTimeMillis() - t0 < 10_000) {
                    val (op, p) = c.read()
                    if (op == ReplicaServer.OP_BINARY) {
                        val arm = p[0].toInt(); val y0 = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
                        val rows = (p[3].toInt() and 0xFF) or ((p[4].toInt() and 0xFF) shl 8)
                        assertEquals(5 + rows * 320, p.size, "frame carries rows × stride bytes")
                        assertTrue(y0 <= l.lens.y && y0 + rows >= l.lens.bottom, "the lens rows are inside the update (arm $arm)")
                        panelsSeen++
                    } else if (op == ReplicaServer.OP_TEXT) {
                        if (String(p).contains("\"t\":\"status\"")) statusSeen = true
                    }
                }
                assertTrue(panelsSeen >= 2, "both lenses streamed (saw $panelsSeen)")
                assertTrue(statusSeen, "a status frame arrived")

                c.send("""{"t":"input","ev":"tap"}""")
                c.send("""{"t":"input","ev":"down"}""")
                val t1 = System.currentTimeMillis()
                while (synchronized(inputs) { inputs.size } < 2 && System.currentTimeMillis() - t1 < 5_000) delay(5)
                assertEquals(listOf(EvenHubMsg.EV_CLICK, EvenHubMsg.EV_SCROLL_BOTTOM), synchronized(inputs) { inputs.toList() })
            }
            t.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
