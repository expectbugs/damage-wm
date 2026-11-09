package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
import wm.damage.core.content.RemoteContent
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.transport.Transport
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §72 (2026-11-08): the new surfaces — the keyed post, the keeper's failed-start
 * count, the sync handshake's key list. New API: each is watched to fail under a mutation of
 * the fixed code (the unfixed tree has none of them), see the record.
 *
 * On 2026-11-06 the PC standby's keeper retried a start that could not succeed every 2–4 s.
 * Each attempt ran every window's restore. The Reader's restore laid out the whole open book
 * and handed it to a message queue that only a running session drains, so the heap was gone
 * by attempt 845. Measured before the fix: 5.4 MB and 2 queued messages per failed start,
 * never released. The pins below are each watched to fail on the unfixed tree for the
 * reason they name, except where a pin says it guards behaviour that must NOT change.
 */
class QueueWhileDownApiTest {
    private val tmp: Path = Files.createTempDirectory("queue-while-down-api")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private fun queued(shell: Shell): Int =
        Regex("queued=(\\d+)").find(shell.quiescenceReport())?.groupValues?.get(1)?.toInt() ?: 0

    private class Flaky(val sim: SimTransport) : Transport by sim {
        @Volatile var fail = true
        override suspend fun start(warmupFrame: ByteArray) {
            if (fail) throw java.io.IOException("adapter hci0 (C4:BD:E5:2E:C9:75) is not powered")
            sim.start(warmupFrame)
        }
    }

    /** Behaviour that must NOT change (a guard, not a fix pin): a keyed push while down runs ONCE,
     *  the latest, when the next session is up; a plain completion is still delivered. */
    @Test
    fun keyedPushesWhileDownRunTheLatestOncePlainOnesAllRun(): Unit = runBlocking {
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        shell.start()
        shell.stop()
        // what the stop itself leaves (a flush answered after the loop ended is kept, and under
        // load one may still be in flight at the stop) is the baseline — a harness rule (§30)
        var base = queued(shell)
        val tq = System.currentTimeMillis()
        var still = System.currentTimeMillis()
        while (System.currentTimeMillis() - tq < 30_000) {
            val q = queued(shell)
            if (q != base) { base = q; still = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - still >= 1_000) break
            delay(50)
        }
        val ran = java.util.concurrent.atomic.AtomicInteger(0)
        val lastSeen = java.util.concurrent.atomic.AtomicInteger(-1)
        val plain = java.util.concurrent.atomic.AtomicInteger(0)
        for (i in 0 until 1_000) shell.services.runOnShell("probe.tick") { ran.incrementAndGet(); lastSeen.set(i) }
        repeat(3) { shell.services.runOnShell { plain.incrementAndGet() } }
        assertEquals(base + 3, queued(shell), "only the three plain posts wait in the queue while down (baseline $base)")
        shell.start()
        val t0 = System.currentTimeMillis()
        while ((ran.get() == 0 || plain.get() < 3) && System.currentTimeMillis() - t0 < 10_000) delay(20)
        delay(300)
        assertEquals(1, ran.get(), "a keyed push held while down runs once")
        assertEquals(999, lastSeen.get(), "and it is the latest one")
        assertEquals(3, plain.get(), "every plain completion posted while down is still delivered")
        shell.stop()
    }

    @Test
    fun theKeeperCountsFailedStartsInARow(): Unit = runBlocking {
        val store = Persistence(tmp.resolve("state.json"))
        val transport = Flaky(SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val keeper = wm.damage.core.shell.ShellKeeper(shell, transport, scope, retryPauseMs = 20)
        keeper.start()
        val t0 = System.currentTimeMillis()
        while (keeper.failedStarts < 4 && System.currentTimeMillis() - t0 < 10_000) delay(10)
        assertTrue(keeper.failedStarts >= 4, "failed starts are counted (${keeper.failedStarts})")
        transport.fail = false
        while (keeper.state != wm.damage.core.shell.ShellKeeper.State.RUNNING && System.currentTimeMillis() - t0 < 20_000) delay(10)
        assertEquals(wm.damage.core.shell.ShellKeeper.State.RUNNING, keeper.state)
        assertEquals(0, keeper.failedStarts, "a start that succeeds clears the count")
        keeper.stop()
    }

    @Test
    fun theSyncHandshakeNamesEveryKeyItMoves() {
        val line = wm.damage.core.sync.SyncNet.describeKeys(listOf("window.reader.book.a", "window.feed"),
            mapOf("window.reader.book.a" to 1_793_900_000_000L), "there", emptyMap(), "here")
        assertTrue("window.reader.book.a (there " in line && "here none" in line, line)
        assertTrue("window.feed (there none · here none)" in line, line)
        assertEquals("none", wm.damage.core.sync.SyncNet.describeKeys(emptyList(), emptyMap(), "a", emptyMap(), "b"))
        assertEquals("k (here none)", wm.damage.core.sync.SyncNet.describeKeys(listOf("k"), emptyMap(), "here", emptyMap(), ""),
            "a side whose stamps are unknown prints no column, never a false \"none\"")
    }

    /** The second review: a cached shelf must not stay one refresh behind, and an explicit open of a
     *  book the cache does not list yet asks the host. */
    @Test
    fun theBackgroundRefreshReplacesTheCacheAndSaysSo() {
        val books = tmp.resolve("books"); Files.createDirectories(books)
        Files.writeString(books.resolve("first.txt"), "CHAPTER I\n\nThe first book.\n")
        val cache = tmp.resolve("bookcache")
        val port = java.net.ServerSocket(0).use { it.localPort }
        val host = wm.damage.core.content.ContentHostServer(wm.damage.core.content.LocalContent(books), port, "tok")
        host.start()
        try {
            assertEquals(1, wm.damage.core.content.RemoteContent("127.0.0.1", port, "tok", cache).library().size)
            Files.writeString(books.resolve("second.txt"), "CHAPTER I\n\nA second book.\n")
            val changed = java.util.concurrent.CountDownLatch(1)
            val rc = wm.damage.core.content.RemoteContent("127.0.0.1", port, "tok", cache, onLibraryChanged = { changed.countDown() })
            assertEquals(1, rc.library().size, "the cached listing answers first")
            assertTrue(changed.await(10, java.util.concurrent.TimeUnit.SECONDS), "the refresh said the listing changed")
            assertEquals(2, rc.library().size, "the next call has the refreshed listing")
            assertEquals(2, rc.library(fresh = true).size, "an explicit open asks the host itself")
        } finally { host.close() }
    }

    /** The second review: the Reader's "scan in flight" must be the scan itself, not a flag its
     *  completion clears. A completion lost at a session's end used to stop every later scan. */
    @Test
    fun aLostScanCompletionDoesNotStopTheNextScan(): Unit = runBlocking {
        val books = tmp.resolve("books"); Files.createDirectories(books)
        Files.writeString(books.resolve("first.txt"), "CHAPTER I\n\nThe first book.\n")
        val local = wm.damage.core.content.LocalContent(books)
        val scans = java.util.concurrent.atomic.AtomicInteger(0)
        val content = object : wm.damage.core.content.ContentProvider by local {
            override fun library(): List<wm.damage.core.content.BookMeta> { scans.incrementAndGet(); return local.library() }
        }
        val store = Persistence(tmp.resolve("state.json"))
        val shell = Shell(FakeText(), SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)), store, null, scope)
        // services whose posts are LOST (a completion drained behind a Shutdown)
        val lossy = object : wm.damage.core.shell.ShellServices by shell.services {
            override fun runOnShell(action: () -> Unit) {}
            override fun runOnShell(key: String, action: () -> Unit) {}
        }
        val reader = wm.damage.core.windows.reader.ReaderWindow(FakeText(), content, scope)
        reader.onRegistered(lossy)
        val t0 = System.currentTimeMillis()
        while (scans.get() < 1 && System.currentTimeMillis() - t0 < 10_000) delay(10)
        delay(300)                                   // the first scan has finished; its completion was lost
        reader.onRegistered(lossy)                   // the next start attempt registers again
        while (scans.get() < 2 && System.currentTimeMillis() - t0 < 10_000) delay(10)
        assertEquals(2, scans.get(), "the next registration scanned again")
    }
}
