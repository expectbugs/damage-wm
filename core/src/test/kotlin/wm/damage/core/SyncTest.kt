package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconKind
import wm.damage.core.geom.Rect
import wm.damage.core.shell.DamageWindow
import wm.damage.core.shell.ListModel
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.shell.WindowView
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.sync.RemoteSync
import wm.damage.core.sync.SyncPeer
import wm.damage.core.transport.RemoteTransportServer
import wm.damage.core.transport.SeamProbe
import wm.damage.core.transport.SeamStatus
import wm.damage.core.transport.SimTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HANDOFF.md §19: the stamped store's last-write-wins semantics, the sync
 * channel over the content port, the shell's freshen-then-apply, and the
 * seam's non-claiming status probe.
 */
class SyncTest {

    private fun blob(n: Int): JsonObject = buildJsonObject { put("v", n) }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun awaitTrue(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(25)
        assertTrue(cond(), "did not converge: $what")
    }

    // ------------------------------------------------------------- the store

    @Test
    fun putRestampsOnlyOnChange_andApplyIsStrictLww(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-sync-store")
        try {
            val p = Persistence(tmp.resolve("state.json"))
            var fired = 0
            p.addListener { fired++ }

            p.put("window.w", blob(1))
            val t1 = p.stamp("window.w")
            assertTrue(t1 > 0)
            assertEquals(1, fired)

            p.put("window.w", blob(1))                  // unchanged: no restamp, no listener
            assertEquals(t1, p.stamp("window.w"))
            assertEquals(1, fired)

            p.put("window.w", blob(2))                  // changed: monotonic restamp
            assertTrue(p.stamp("window.w") > t1)
            assertEquals(2, fired)

            // strictly newer applies, silently (no listener echo)
            val tNew = p.stamp("window.w") + 5_000
            assertTrue(p.tryApplyRemote("window.w", blob(3), tNew))
            assertEquals(blob(3), p.get("window.w"))
            assertEquals(tNew, p.stamp("window.w"))
            assertEquals(2, fired, "an applied record must not echo")

            // older is refused; equal value adopts the higher stamp silently
            assertFalse(p.tryApplyRemote("window.w", blob(9), tNew - 1))
            assertEquals(blob(3), p.get("window.w"))
            assertFalse(p.tryApplyRemote("window.w", blob(3), tNew + 7))
            assertEquals(tNew + 7, p.stamp("window.w"))

            // a peer's future clock is clamped, not trusted
            assertTrue(p.tryApplyRemote("window.w", blob(4), System.currentTimeMillis() + 3_600_000))
            assertTrue(p.stamp("window.w") <= System.currentTimeMillis() + Persistence.FUTURE_SLOP_MS)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyStoreMigratesWithMtimeStamps(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-sync-legacy")
        try {
            val f = tmp.resolve("state.json")
            Files.writeString(f, """{"shell.settings":{"a":1},"window.r":{"b":2}}""")
            val p = Persistence(f)
            assertTrue(p.load())
            assertEquals(2, p.get("window.r")!!["b"]!!.jsonPrimitive.toString().toInt())
            val mtime = Files.getLastModifiedTime(f).toMillis()
            assertEquals(mtime, p.stamp("window.r"))
            // v2 round trip
            p.save()
            val q = Persistence(f)
            assertTrue(q.load())
            assertEquals(p.get("shell.settings"), q.get("shell.settings"))
            assertEquals(p.stamp("shell.settings"), q.stamp("shell.settings"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------- the wire

    @Test
    fun handshakeConvergesBothWays_andLivePushesFlow(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-sync-net")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        var host: ContentHostServer? = null
        var client: RemoteSync? = null
        try {
            val storeA = Persistence(tmp.resolve("a.json"))      // the PC side
            val storeB = Persistence(tmp.resolve("b.json"))      // the phone side
            storeA.put("window.reader", blob(10))                // newer on A
            delay(5)
            storeB.put("shell.settings", blob(20))               // newer on B
            storeB.put("shell.state", blob(99))                  // must NEVER travel

            host = ContentHostServer(LocalContent(tmp), port, "tok", sync = SyncPeer(storeA))
            host.start()
            client = RemoteSync("127.0.0.1", port, "tok", scope, SyncPeer(storeB), retryPacingMs = 200)

            awaitTrue("A's record reached B") { storeB.get("window.reader") == blob(10) }
            awaitTrue("B's record reached A") { storeA.get("shell.settings") == blob(20) }
            assertNull(storeA.get("shell.state"), "shell.state is per-device and never syncs")

            // live push, both directions
            storeB.put("window.reader", blob(11))
            awaitTrue("B's live change reached A") { storeA.get("window.reader") == blob(11) }
            storeA.put("shell.settings", blob(21))
            awaitTrue("A's live change reached B") { storeB.get("shell.settings") == blob(21) }
        } finally {
            client?.close()
            host?.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun aHostWithoutSyncIsSurvivedQuietly(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-sync-old")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        var host: ContentHostServer? = null
        var client: RemoteSync? = null
        try {
            host = ContentHostServer(LocalContent(tmp), port, "tok", sync = null)
            host.start()
            val store = Persistence(tmp.resolve("b.json"))
            store.put("window.reader", blob(1))
            client = RemoteSync("127.0.0.1", port, "tok", scope, SyncPeer(store), retryPacingMs = 100)
            delay(600)      // several refusal round trips
            assertEquals(blob(1), store.get("window.reader"), "the store is untouched and the client lives")
        } finally {
            client?.close()
            host?.close()
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------- the shell

    private class FakeWin : DamageWindow("fw", "Fake", IconKind.FILES) {
        var value = 0
        override fun view(): WindowView = WindowView.ListView(ListModel(), { 0 },
            { _: Gray8, _: Int, _: Rect, _: Boolean -> }, { _: Gray8, _: Rect, _: Int -> }, { })
        override fun summary() = Summary("fake")
        override fun saveState(): JsonObject = buildJsonObject { put("v", value) }
        override fun restoreState(state: JsonObject) {
            value = state["v"]!!.jsonPrimitive.toString().toInt()
        }
    }

    @Test
    fun postSyncFreshensThenApplies(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-sync-shell")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = Persistence(tmp.resolve("s.json"))
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, store, null, scope) {
                Shell.LocalClock(12, 0, "12:00", "PM")
            }
            val w = FakeWin()
            shell.register(w)

            assertFalse(shell.postSync("window.fw", blob(5), System.currentTimeMillis()),
                "a stopped shell refuses — the caller goes to the store")

            shell.start()
            settle(shell)

            // a NEWER record applies live
            shell.postSync("window.fw", blob(5), System.currentTimeMillis() + 2_000)
            settle(shell)
            assertEquals(5, w.value, "the synced state is live, not just stored")

            // the freshen wins: the live window moved on, an OLDER record loses
            w.value = 7
            shell.postSync("window.fw", blob(9), System.currentTimeMillis() - 60_000)
            settle(shell)
            assertEquals(7, w.value, "an older record never overwrites the live state")
            assertEquals(7, store.get("window.fw")!!["v"]!!.jsonPrimitive.toString().toInt(),
                "the freshen captured the live state into the store")
            shell.stop()
        } finally {
            scope.cancel()
            tmp.toFile().deleteRecursively()
        }
    }

    private suspend fun settle(shell: Shell) {
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 10_000) delay(10)
        assertTrue(shell.isQuiescent(), "shell did not settle: ${shell.quiescenceReport()}")
    }

    // ------------------------------------------------------------- the probe

    @Test
    fun statusProbeAnswersWithoutClaiming(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        var server: RemoteTransportServer? = null
        try {
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            server = RemoteTransportServer(transport, port, "tok", scope,
                statusFor = { SeamStatus(wantsRadio = true, driving = false, line = "here") })
            server.start()
            delay(100)

            val r1 = SeamProbe.probe("127.0.0.1", port, "tok")
            assertTrue(r1 is SeamProbe.Result.Reachable && r1.wantsRadio == true && r1.detail == "here")

            // a probe never takes the driver slot: a second probe still answers
            val r2 = SeamProbe.probe("127.0.0.1", port, "tok")
            assertTrue(r2 is SeamProbe.Result.Reachable && r2.wantsRadio == true,
                "the slot must be free after a probe — a claim would answer busy")

            // a wrong token (and an old server's busy) read as unknown → conservative
            val r3 = SeamProbe.probe("127.0.0.1", port, "wrong")
            assertTrue(r3 is SeamProbe.Result.Reachable && r3.wantsRadio == null)

            val r4 = SeamProbe.probe("127.0.0.1", freePort(), "tok")
            assertTrue(r4 is SeamProbe.Result.Unreachable)
        } finally {
            server?.close()
            scope.cancel()
        }
    }
}
