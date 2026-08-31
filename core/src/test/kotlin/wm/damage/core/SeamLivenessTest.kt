package wm.damage.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.RemoteTransportServer
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.TransportEvent

/**
 * The seam heartbeat (2026-08-31, the APK mission): a silent path death — the
 * TCP link stands, nothing arrives — must end the session within the quiet
 * bound on BOTH ends, because "one of them must ALWAYS be driving" cannot wait
 * for TCP retransmission to give up. Quiet is enforced only against a peer
 * that has spoken the liveness protocol (sent at least one ping), so a
 * version-skewed peer keeps today's TCP-event-only behaviour.
 *
 * The fake peers here are raw sockets that go quiet WITHOUT closing — the
 * silence is real, not a FIN the old code already handled.
 */
class SeamLivenessTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    private fun DataOutputStream.frame(json: String) {
        val b = json.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
        flush()
    }

    /** Read one control frame; consume any trailing blob the JSON declares. */
    private fun DataInputStream.readFrame(): String {
        val n = readInt()
        val b = ByteArray(n)
        readFully(b)
        val s = b.toString(Charsets.UTF_8)
        val o = Json.parseToJsonElement(s).jsonObject
        var blob = 0
        o["warmupLen"]?.jsonPrimitive?.int?.let { blob += it }
        o["blobLen"]?.jsonPrimitive?.int?.let { blob += it }
        if (blob > 0) readFully(ByteArray(blob))
        return s
    }

    @Test
    fun theClientEndsAQuietSessionOnceThePeerHasSpokenLiveness(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val server = ServerSocket(port)
        // a fake seam server: handshake, one ping, then silence with the
        // socket held open (it keeps reading so the client's pings drain)
        val fake = Thread({
            try {
                val sock = server.accept()
                val inp = DataInputStream(sock.getInputStream().buffered())
                val out = DataOutputStream(sock.getOutputStream().buffered())
                inp.readFrame()                        // hello
                out.frame("""{"t":"grant"}""")
                inp.readFrame()                        // start (+ warmup blob)
                out.frame("""{"t":"started"}""")
                out.frame("""{"t":"ping"}""")          // liveness spoken once
                while (true) inp.readFrame()           // then: total silence
            } catch (e: Exception) { /* the client closing ends this thread */ }
        }, "fake-seam-server")
        fake.start()
        try {
            val client = RemoteTransportClient("127.0.0.1", port, "tok", scope, pingMs = 200, quietMs = 1_000)
            val links = ArrayList<TransportEvent.Link>()
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                client.events.collect { if (it is TransportEvent.Link) synchronized(links) { links.add(it) } }
            }
            client.start(warmup())
            assertTrue(client.state.value.started)
            val t0 = System.currentTimeMillis()
            fun quietDown() = synchronized(links) { links.any { !it.connected && "quiet" in it.detail } }
            while (!quietDown() && System.currentTimeMillis() - t0 < 8_000) delay(50)
            assertTrue(quietDown(), "the quiet seam was reported as the link ending: $links")
            assertFalse(client.state.value.started, "the keeper's poll sees the session end")
            val took = System.currentTimeMillis() - t0
            assertTrue(took < 6_000, "detection rode the quiet bound (took $took ms), not TCP retransmission")
            client.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun theServerEndsAQuietDriverSoTheLocalShellCanResume(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val inner = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val driverStates = ArrayList<Boolean>()
        val server = RemoteTransportServer(inner, port, "tok", scope,
            onRemoteDriver = { synchronized(driverStates) { driverStates.add(it) } },
            pingMs = 200, quietMs = 1_000)
        server.start()
        try {
            delay(100)   // accept loop up
            val sock = Socket("127.0.0.1", port)
            val inp = DataInputStream(sock.getInputStream().buffered())
            val out = DataOutputStream(sock.getOutputStream().buffered())
            // keep draining what the server sends (panels, state, pings) so
            // nothing here depends on socket buffers — only on the protocol
            val drain = Thread({ try { while (true) inp.readFrame() } catch (e: Exception) { /* closed */ } }, "fake-driver-drain")
            out.frame("""{"t":"hello","token":"tok"}""")
            drain.start()
            out.frame("""{"t":"ping"}""")              // liveness spoken once
            val t0 = System.currentTimeMillis()
            fun claimed() = synchronized(driverStates) { driverStates.contains(true) }
            while (!claimed() && System.currentTimeMillis() - t0 < 3_000) delay(50)
            assertTrue(claimed(), "the fake driver claimed the transport")
            // then: total silence. The server must hand the glasses back.
            fun released() = synchronized(driverStates) { driverStates.lastOrNull() == false }
            while (!released() && System.currentTimeMillis() - t0 < 8_000) delay(50)
            assertTrue(released(), "the quiet driver was released: $driverStates")
            val took = System.currentTimeMillis() - t0
            assertTrue(took < 6_000, "release rode the quiet bound (took $took ms), not TCP retransmission")
            sock.close()
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun aPeerThatNeverSpeaksLivenessIsNotDisconnected(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val inner = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val driverStates = ArrayList<Boolean>()
        val server = RemoteTransportServer(inner, port, "tok", scope,
            onRemoteDriver = { synchronized(driverStates) { driverStates.add(it) } },
            pingMs = 200, quietMs = 1_000)
        server.start()
        try {
            delay(100)
            val sock = Socket("127.0.0.1", port)
            val inp = DataInputStream(sock.getInputStream().buffered())
            val out = DataOutputStream(sock.getOutputStream().buffered())
            val drain = Thread({ try { while (true) inp.readFrame() } catch (e: Exception) { /* closed */ } }, "fake-old-driver-drain")
            out.frame("""{"t":"hello","token":"tok"}""")
            drain.start()
            val t0 = System.currentTimeMillis()
            while (synchronized(driverStates) { driverStates.isEmpty() } && System.currentTimeMillis() - t0 < 3_000) delay(50)
            // an old-protocol driver: never pings, sends nothing — three quiet
            // bounds pass and the session must still stand (skew-safe)
            delay(3_000)
            assertEquals(listOf(true), synchronized(driverStates) { driverStates.toList() },
                "a peer that never spoke liveness keeps the old TCP-event-only behaviour")
            sock.close()
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
