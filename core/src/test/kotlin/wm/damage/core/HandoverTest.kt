package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.ShellKeeper
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.FlushRequest
import wm.damage.core.transport.RemoteTransportClient
import wm.damage.core.transport.RemoteTransportServer
import wm.damage.core.transport.SimTransport

/**
 * "The session outlives the driver" (2026-08-31, after Adam's first day out):
 * a WiFi edge used to cost TWO visible session teardowns — the phone tearing
 * the glasses session to take over, then the PC tearing it again to re-claim.
 * The G2CC bridge never blinked because its BLE session's lifetime was
 * decoupled from the server link's. These tests pin the same decoupling:
 * across claim, release, silent driver death, pause and resume, the firmware
 * model must see EXACTLY ONE connect choreography (`preludeAcks == 1` is the
 * probe — a torn session would re-run the prelude), with the lease held
 * throughout and every driver rebaselining by keyframe alone.
 */
class HandoverTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    private fun warmup() = Zl.encodeCfw(Pack.rect(Gray8(640, 480), Rect(0, 0, 640, 480)))

    private suspend fun await(what: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 8_000) delay(10)
        assertTrue(cond(), what)
    }

    @Test
    fun aDriverReleaseAndReclaimNeverTearTheSession(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val glass = GlassFirmwareSim()
        val inner = SimTransport(glass, scope, SimTransport.Timing(instant = true))
        val driverStates = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val server = RemoteTransportServer(inner, port, "tok", scope,
            onRemoteDriver = { driverStates.add(it) })
        server.start()
        try {
            val a = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            a.start(warmup())
            assertEquals(1, glass.preludeAcks, "driver A ran the one and only choreography")
            assertTrue(inner.state.value.leaseHeld)
            a.stop()   // A releases its CLAIM — never the owner's session
            await("the release reached the local shell") { driverStates.lastOrNull() == false }
            assertTrue(inner.state.value.started, "the session OUTLIVES driver A")
            assertTrue(inner.state.value.leaseHeld, "the lease never lapsed")

            val b = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            b.start(warmup())   // adopt: answered started, no re-choreography
            assertEquals(1, glass.preludeAcks, "driver B ADOPTED — the prelude never ran again")
            val id = b.submit(FlushRequest(listOf(DisplayOp.Keyframe(warmup())), 1L, wide = true))
            assertTrue(id > 0, "the adopting driver's keyframe rebaselines over the live session")
            b.stop()
        } finally {
            server.close()
            scope.cancel()
        }
    }

    /** Raw seam framing for the fake driver (the SeamLivenessTest pattern):
     *  a peer that speaks liveness once and then goes SILENT with the socket
     *  open — the true WiFi-edge shape. */
    private fun java.io.DataOutputStream.frame(json: String) {
        val b = json.toByteArray(Charsets.UTF_8)
        writeInt(b.size); write(b); flush()
    }

    @Test
    fun aSilentDriverDeathLeavesTheSessionForTheLocalShell(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val glass = GlassFirmwareSim()
        val inner = SimTransport(glass, scope, SimTransport.Timing(instant = true))
        val driverStates = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val server = RemoteTransportServer(inner, port, "tok", scope,
            onRemoteDriver = { driverStates.add(it) }, pingMs = 150, quietMs = 700)
        server.start()
        try {
            delay(100)
            val sock = java.net.Socket("127.0.0.1", port)
            val out = java.io.DataOutputStream(sock.getOutputStream().buffered())
            val inp = java.io.DataInputStream(sock.getInputStream().buffered())
            val w = warmup()
            out.frame("""{"t":"hello","token":"tok"}""")
            // drain server frames (grant/panels/state/pings) off-thread
            val sawStarted = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread({
                try {
                    while (true) {
                        val n = inp.readInt()
                        val b = ByteArray(n); inp.readFully(b)
                        val s = b.toString(Charsets.UTF_8)
                        if ("\"started\"" in s || "\"t\":\"started\"" in s) sawStarted.set(true)
                        val o = kotlinx.serialization.json.Json.parseToJsonElement(s)
                            .let { it as? kotlinx.serialization.json.JsonObject }
                        val blob = (o?.get("blobLen")?.toString()?.trim('"')?.toIntOrNull() ?: 0)
                        if (blob > 0) inp.readFully(ByteArray(blob))
                    }
                } catch (e: Exception) { /* closed */ }
            }, "fake-driver-drain").start()
            out.frame("""{"t":"ping"}""")
            out.frame("""{"t":"start","warmupLen":${w.size}}""")
            out.write(w); out.flush()
            await("the fake driver's session started") { sawStarted.get() && inner.state.value.started }
            assertEquals(1, glass.preludeAcks)
            // then: total silence with the socket open — the server's liveness
            // ends the CLAIM, and only the claim
            await("the quiet driver was released") { driverStates.lastOrNull() == false }
            assertTrue(inner.state.value.started, "the session outlives a silently dead driver")
            assertTrue(inner.state.value.leaseHeld, "renewals kept running with no driver at all")
            assertEquals(1, glass.preludeAcks, "and nothing re-choreographed")
            sock.close()
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun keeperPauseAndResumeAdoptWithOneChoreographyTotal(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-handover")
        val glass = GlassFirmwareSim()
        val transport = SimTransport(glass, scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
        val keeper = ShellKeeper(shell, transport, scope)
        try {
            keeper.start()
            await("the shell drives") { keeper.state == ShellKeeper.State.RUNNING }
            assertEquals(1, glass.preludeAcks)

            keeper.pause("PC shell driving")      // YIELD: shell stops, session lives
            assertTrue(transport.state.value.started, "pause yields — the transport session keeps running")
            assertTrue(transport.state.value.leaseHeld, "the lease survives the yield")

            keeper.resume()                        // ADOPT: no re-choreography
            await("the shell drives again") { keeper.state == ShellKeeper.State.RUNNING }
            assertEquals(1, glass.preludeAcks, "resume ADOPTED the live session — one choreography, ever")
            assertTrue(glass.left.panel.any { it.toInt() != 0 }, "the adopting shell repainted the panel")

            keeper.stop()                          // the OWNER's teardown is still a real stop
            await("the owner's stop tears the session") { !transport.state.value.started }
        } finally {
            tmp.toFile().deleteRecursively()
            scope.cancel()
        }
    }

    @Test
    fun theFullWifiEdgeLoopBlinksZeroTimes(): Unit = runBlocking {
        // phone-shaped wiring: a local keeper driving, a seam server whose
        // claim/release pauses and resumes it (ShellService.onRemoteDriver),
        // then a remote driver claiming, painting, and leaving — the whole
        // WiFi-edge story with ONE choreography end to end
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tmp = Files.createTempDirectory("damage-handover2")
        val port = freePort()
        val glass = GlassFirmwareSim()
        val transport = SimTransport(glass, scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
        val keeper = ShellKeeper(shell, transport, scope)
        val server = RemoteTransportServer(transport, port, "tok", scope,
            onRemoteDriver = { driving ->
                if (driving) runBlocking { keeper.pause("PC shell driving") } else keeper.resume()
            })
        server.start()
        try {
            keeper.start()
            await("the local shell drives") { keeper.state == ShellKeeper.State.RUNNING }
            assertEquals(1, glass.preludeAcks)

            val pc = RemoteTransportClient("127.0.0.1", port, "tok", scope)
            pc.start(warmup())                     // claim -> local pause -> adopt
            await("the local shell yielded") { keeper.state == ShellKeeper.State.PAUSED }
            assertEquals(1, glass.preludeAcks, "the PC adopted the phone's session")
            pc.submit(FlushRequest(listOf(DisplayOp.Keyframe(warmup())), 1L, wide = true))

            pc.stop()                              // the PC leaves (or dies): release
            await("the local shell resumed and drives") { keeper.state == ShellKeeper.State.RUNNING }
            assertEquals(1, glass.preludeAcks, "the resume adopted too — zero teardowns across the whole edge")
            assertTrue(transport.state.value.leaseHeld, "the lease was held through every transition")
            keeper.stop()
        } finally {
            server.close()
            tmp.toFile().deleteRecursively()
            scope.cancel()
        }
    }
}
