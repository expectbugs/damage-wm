package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.shell.Persistence
import wm.damage.core.shell.Shell
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.SimTransport
import wm.damage.core.windows.files.FEntry
import wm.damage.core.windows.files.FLocation
import wm.damage.core.windows.files.FilesProvider
import wm.damage.core.windows.files.FilesWindow
import wm.damage.core.windows.files.ListingCache
import wm.damage.core.windows.files.LocalFilesProvider
import wm.damage.core.wire.EvenHubMsg

/**
 * `HANDOFF.md` §47 (2026-09-12): the Files window shows a folder as the
 * provider last saw it AT ONCE and the live listing follows — over the
 * relayed tailnet of that day a folder took a 60–1,100 ms round trip to
 * appear at all. The cache is bounded, atomic and never a reason to show
 * nothing.
 */
class FilesCacheTest {

    @Test
    fun theListingCacheRoundTripsPrunesAndDropsWhatItCannotRead() {
        val tmp = Files.createTempDirectory("damage-lcache")
        try {
            val c = ListingCache(tmp.resolve("files"), maxListings = 3)
            assertNull(c.locations(), "empty before the first answer")
            assertNull(c.list("/x", false))
            val locs = listOf(FLocation("Home", "/home/user", "home", 10, 5))
            c.putLocations(locs)
            assertEquals(locs, c.locations())
            val a = listOf(FEntry("a.txt", false, 3, 1L), FEntry("sub", true, 0, 2L))
            c.putList("/x", false, a)
            assertEquals(a, c.list("/x", false))
            assertNull(c.list("/x", true), "hidden and visible listings are different keys")
            // bounded: the fourth listing evicts the oldest
            c.putList("/y", false, a); Thread.sleep(5)
            c.putList("/z", false, a); Thread.sleep(5)
            c.putList("/w", false, a)
            assertEquals(3, c.listingCount())
            // a torn file is dropped, not shown
            val files = Files.list(tmp.resolve("files").resolve("list")).use { it.toList() }
            Files.writeString(files.first(), "{not json")
            val n0 = c.listingCount()
            for (d in listOf("/x", "/y", "/z", "/w")) c.list(d, false)
            assertEquals(n0 - 1, c.listingCount(), "the unreadable listing was deleted")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /** A provider whose live listing waits on a latch: the cached rows must
     *  be on screen before it answers. */
    private class SlowProvider(private val local: LocalFilesProvider, private val root: Path) : FilesProvider by local {
        val gate = CountDownLatch(1)
        val cached = listOf(FEntry("old-a.txt", false, 1, 1L), FEntry("old-b.txt", false, 1, 1L))
        val live = listOf(FEntry("new-a.txt", false, 1, 1L), FEntry("new-b.txt", false, 1, 1L), FEntry("new-c.txt", false, 1, 1L))
        override fun locations(): List<FLocation> = listOf(FLocation("SC", root.toString(), "mount", 1_000, 500))
        override fun cachedList(dir: String, showHidden: Boolean): List<FEntry>? = if (dir == root.toString()) cached else null
        override fun list(dir: String, showHidden: Boolean): List<FEntry> {
            gate.await()
            return if (dir == root.toString()) live else local.list(dir, showHidden)
        }
    }

    private suspend fun awaitTrue(what: String, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < 20_000) delay(25)
        assertTrue(cond(), "did not converge: $what")
    }

    @Test
    fun theWindowShowsTheCachedFolderBeforeTheLiveListingLands(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-files-cached")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val root = tmp.resolve("sc-root"); Files.createDirectories(root)
            val local = LocalFilesProvider(tmp.resolve("books"), tmp.resolve("trash"), decoder = null, mountsFile = tmp.resolve("no-mounts"))
            val provider = SlowProvider(local, root)
            val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
            val shell = Shell(FakeText(), transport, Persistence(tmp.resolve("state.json")), null, scope)
            val win = FilesWindow(FakeText(), provider, scope)
            shell.register(win)
            shell.start()
            shell.postGesture(EvenHubMsg.EV_CLICK)          // Main cursor 0 = Files
            awaitTrue("locations listed") { win.summary().line == "1 locations" }
            shell.postGesture(EvenHubMsg.EV_CLICK)          // enter SC — the live listing is gated
            awaitTrue("the cached rows are up while the live listing waits: ${win.summary()}") {
                win.title().endsWith("sc-root") && win.summary().detail == "0 folders · 2 files"
            }
            provider.gate.countDown()
            awaitTrue("the live rows replaced them: ${win.summary()}") { win.summary().detail == "0 folders · 3 files" }
            shell.stop()
        } finally {
            scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }
}
