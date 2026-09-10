package wm.damage.core

import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.windows.feed.*

/**
 * FEED over the window channel (`FEED.md` §3.6): the remote provider through
 * a real loopback content host — the status list, a page, an article, a comic
 * whose strips ride deflated and come back byte-identical, an episode,
 * comments, browse and forget, the `changed` push — and the switching
 * provider: the PC lost → the phone engine after the threshold, its sources
 * announced as changed; the PC back → nothing moves until `Back to PC`.
 */
class FeedNetTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun awaitTrue(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!cond() && System.currentTimeMillis() - t0 < ms) delay(20)
        assertTrue(cond(), "did not converge: $what")
    }

    private class Collect : FeedProvider.Listener {
        val changed = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Long>>()
        val states = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun changed(sourceId: String, version: Long) { changed.add(sourceId to version) }
        override fun state(line: String) { states.add(line) }
    }

    @Test
    fun remoteProviderThroughARealHost(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-net")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val fake = ScriptedFeed()
        val host = ContentHostServer(LocalContent(tmp), port, "tok", win = mapOf("feed" to FeedService(fake)))
        host.start()
        val remote = RemoteFeedProvider("127.0.0.1", port, "tok", scope)
        val c = Collect()
        remote.addListener(c)
        try {
            awaitTrue("the status list arrives") { remote.sources().size == 5 }
            assertEquals("", remote.stateLine())
            assertEquals("PC", remote.engineName())
            val page = remote.items("popular", 0, 10)
            assertEquals(10, page.items.size); assertEquals(30, page.total)
            assertEquals(page.items[0].title, remote.item(page.items[0].id)!!.title)
            val link = page.items.first { it.kind == ItemKind.LINK }
            val a = remote.article(link.id)
            assertTrue(a.extracted && a.blocks.size == 8)
            // a comic: the strip and the bonus ride deflated and come back byte-identical
            val xk = remote.items("xkcd", 0, 1).items[0]
            val direct = fake.comic(xk.id, 564, 16, LineArt.AUTO)
            val over = remote.comic(xk.id, 564, 16, LineArt.AUTO)
            assertEquals(direct.strip.w, over.strip.w); assertEquals(direct.strip.h, over.strip.h)
            assertTrue(direct.strip.packed.contentEquals(over.strip.packed)); assertTrue(over.strip.inverted)
            val sm = remote.items("smbc", 0, 1).items[0]
            val smOver = remote.comic(sm.id, 564, 16, LineArt.AUTO)
            assertNotNull(smOver.bonus); assertEquals(360, smOver.bonus!!.w)
            assertTrue(fake.comic(sm.id, 564, 16, LineArt.AUTO).bonus!!.packed.contentEquals(smOver.bonus!!.packed))
            val img = remote.image("https://example.org/img/x.jpg", 564, 16, LineArt.AUTO)
            assertTrue(img.w in 4..400)
            assertEquals(12, remote.bingeIndex("8bt").size)
            val ep = remote.bingeEpisode("8bt", 3, 564, 16, LineArt.AUTO)
            assertEquals(3, ep.episode.num); assertEquals(564, ep.strip.w)
            assertEquals(6, remote.comments(link.id).size)
            assertEquals(1..3296, remote.comicRange("xkcd")); assertNull(remote.comicRange("smbc"))
            assertEquals(1000, remote.comicAt("xkcd", 1000)!!.num); assertNull(remote.comicAt("xkcd", 3290))
            // browse makes a transient source the status list then carries
            val b = remote.browse(SourceKind.REDDIT, "linux")
            assertEquals("r:linux", b.id)
            awaitTrue("the transient source is listed") { remote.sources().any { it.id == "r:linux" } }
            remote.forget("r:linux")
            awaitTrue("and forgotten") { remote.sources().none { it.id == "r:linux" } }
            remote.refresh("popular")
            assertTrue(fake.ops.contains("refresh:popular"))
            remote.configure(300_000, 7L * 86_400_000, 30_000)
            assertTrue(fake.ops.any { it.startsWith("configure:300000:") })
            // a change on the host reaches the phone as a push
            val before = c.changed.size
            fake.fireNew("popular")
            awaitTrue("the changed push lands") { c.changed.size > before && c.changed.last().first == "popular" }
            awaitTrue("the status list refreshed on the push") { remote.sources().first { it.id == "popular" }.count == 31 }
        } finally {
            remote.close(); host.close(); scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun theSwitchFallsToThePhoneAfterTheThresholdAndReturnsOnlyByHand(): Unit = runBlocking {
        val tmp = Files.createTempDirectory("damage-feed-switch")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = freePort()
        val pcSide = ScriptedFeed()
        var host = ContentHostServer(LocalContent(tmp), port, "tok", win = mapOf("feed" to FeedService(pcSide)))
        host.start()
        val remote = RemoteFeedProvider("127.0.0.1", port, "tok", scope)
        val phoneSide = ScriptedFeed()
        val localActive = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val sw = SwitchingFeedProvider(remote, phoneSide, scope, pcLossMs = 5_000, onLocalActive = { localActive.add(it) })
        val c = Collect()
        sw.addListener(c)
        try {
            awaitTrue("the PC serves") { sw.sources().size == 5 && !sw.fallbackActive() }
            assertEquals("PC", sw.engineName())
            assertEquals(listOf(false), localActive)
            // the PC goes away: the phone engine takes over after the threshold, not before
            host.close()
            awaitTrue("the link is seen down") { remote.downSinceMs != 0L }
            delay(1_500)
            assertFalse(sw.fallbackActive(), "not before the threshold")
            awaitTrue("the phone engine serves after the threshold", ms = 15_000) { sw.fallbackActive() }
            assertEquals("phone", sw.engineName())
            assertEquals(listOf(false, true), localActive)
            assertTrue(sw.pcDownLine().startsWith("PC down"))
            assertEquals("", sw.stateLine(), "the phone engine is healthy on its own")
            awaitTrue("every phone source announced as changed") { c.changed.count { it.first == "popular" } >= 1 }
            assertEquals(30, sw.items("popular", 0, 50).total)
            // the PC returns: nothing switches by itself
            host = ContentHostServer(LocalContent(tmp), port, "tok", win = mapOf("feed" to FeedService(pcSide)))
            host.start()
            awaitTrue("the link is back") { remote.downSinceMs == 0L }
            delay(1_500)
            assertTrue(sw.fallbackActive(), "switchback is deliberate")
            assertEquals("", sw.pcDownLine())
            sw.backToPc()
            assertFalse(sw.fallbackActive()); assertEquals("PC", sw.engineName())
            assertEquals(listOf(false, true, false), localActive)
            awaitTrue("the PC's list again") { sw.sources().size == 5 }
        } finally {
            sw.close(); host.close(); scope.cancel(); tmp.toFile().deleteRecursively()
        }
    }
}
