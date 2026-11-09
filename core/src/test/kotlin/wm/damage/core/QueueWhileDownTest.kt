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
 * `HANDOFF.md` §72 (2026-11-08): what the shell holds while no session is up.
 *
 * On 2026-11-06 the PC standby's keeper retried a start that could not succeed every 2–4 s.
 * Each attempt ran every window's restore. The Reader's restore laid out the whole open book
 * and handed it to a message queue that only a running session drains, so the heap was gone
 * by attempt 845. Measured before the fix: 5.4 MB and 2 queued messages per failed start,
 * never released. The pins below are each watched to fail on the unfixed tree for the
 * reason they name, except where a pin says it guards behaviour that must NOT change.
 */
class QueueWhileDownTest {
    private val tmp: Path = Files.createTempDirectory("queue-while-down")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun clean() { scope.cancel(); tmp.toFile().deleteRecursively() }

    private fun queued(shell: Shell): Int =
        Regex("queued=(\\d+)").find(shell.quiescenceReport())?.groupValues?.get(1)?.toInt() ?: 0

    /** Until [shell]'s queue depth has held still for a second (bounded: a test wait only). */
    private suspend fun settledQueue(shell: Shell): Int {
        var last = -1
        var stillSince = System.currentTimeMillis()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 30_000) {
            val q = queued(shell)
            if (q != last) { last = q; stillSince = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - stillSince >= 1_000) return q
            delay(50)
        }
        return queued(shell)
    }

    /** A transport whose start fails until [fail] is cleared — beardos's powered-off adapter. */
    private class Flaky(val sim: SimTransport) : Transport by sim {
        @Volatile var fail = true
        override suspend fun start(warmupFrame: ByteArray) {
            if (fail) throw java.io.IOException("adapter hci0 (C4:BD:E5:2E:C9:75) is not powered")
            sim.start(warmupFrame)
        }
    }

    private fun bookShelf(): Pair<LocalContent, String> {
        val books = tmp.resolve("books")
        Files.createDirectories(books)
        Files.writeString(books.resolve("big.txt"), buildString {
            appendLine("CHAPTER I"); appendLine()
            for (i in 1..4_000) { appendLine("Paragraph $i. The compositor batches damage into one flush per frame."); appendLine() }
        })
        val content = LocalContent(books)
        return content to content.library().single().id
    }

    @Test
    fun failedStartsHoldNothingAndTheBookStillOpensAfter(): Unit = runBlocking {
        val (content, id) = bookShelf()
        val store = Persistence(tmp.resolve("state.json"))
        store.put("window.reader", buildJsonObject { put("level", "BOOK"); put("bookId", id) })
        store.put("shell.state", buildJsonObject { put("current", "reader"); put("mode", "WINDOW") })
        store.save()
        val transport = Flaky(SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true)))
        val shell = Shell(FakeText(), transport, store, null, scope)
        val reader = wm.damage.core.windows.reader.ReaderWindow(FakeText(), content, scope)
        shell.register(reader)
        repeat(20) {
            try { shell.start() } catch (e: Exception) { /* the keeper's failed start */ }
            delay(150)
        }
        assertEquals(0, settledQueue(shell),
            "twenty failed starts left messages queued behind a loop that never ran (the unfixed tree: two per start, " +
                "each restore holding a whole laid-out book)")

        // the glasses come back: the restore still lands — nothing a session needs was lost
        transport.fail = false
        shell.start()
        val t0 = System.currentTimeMillis()
        while (!reader.title().contains("p.") && System.currentTimeMillis() - t0 < 20_000) delay(50)
        assertTrue(reader.title().contains("p."), "the book did not reopen after the failed starts: '${reader.title()}'")
        shell.stop()
    }

    @Test
    fun transportEventsWhileDownAreDropped(): Unit = runBlocking {
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(instant = true))
        val shell = Shell(FakeText(), transport, store, null, scope)
        shell.start()
        shell.stop()
        // what the stop itself leaves for the next session (a flush answered after the loop
        // ended is KEPT: it completes the in-flight bookkeeping) is the baseline
        val base = settledQueue(shell)
        // ring events with no session: a session that is gone, or that never came up
        repeat(200) { transport.injectInput(EvenHubMsg.EV_SCROLL_BOTTOM) }
        assertEquals(base, settledQueue(shell),
            "ring events with no session running piled up for the next one (the unfixed tree: base + 200)")
    }

    /** The phone's shelf with the PC unreachable (2026-11-07: blank for 130 s while Android gave
     *  up on the connect). A host that accepts and never answers holds the old path forever. */
    @Test
    fun theShelfAnswersFromItsCacheWhileThePcDoesNot() {
        val (_, _) = bookShelf()
        val cache = tmp.resolve("bookcache")
        val port = ServerSocket(0).use { it.localPort }
        val host = ContentHostServer(LocalContent(tmp.resolve("books")), port, "tok")
        host.start()
        try {
            val first = RemoteContent("127.0.0.1", port, "tok", cache).library()
            assertEquals(1, first.size, "the first listing comes from the host and is cached")
        } finally { host.close() }
        // a "PC" that takes the connection and never says a word
        ServerSocket(0).use { mute ->
            val rc = RemoteContent("127.0.0.1", mute.localPort, "tok", cache)
            var got: List<wm.damage.core.content.BookMeta>? = null
            val t = Thread { got = rc.library() }.apply { isDaemon = true; start() }
            t.join(5_000)
            assertTrue(!t.isAlive, "the shelf waited on a PC that does not answer instead of reading its cache")
            assertEquals(1, got?.size, "the cached listing")
        }
    }

    /** The second review's finding: a flush still in flight when a session stops is answered while
     *  no session runs. That answer must still reach the next session, or its in-flight bookkeeping
     *  never empties: no quiescence, no divergence check, the atlas and staging uploads stuck.
     *  (It guards §72's own first draft, which dropped every transport event while down.) */
    @Test
    fun aFlushAnsweredWhileDownStillCompletesInTheNextSession(): Unit = runBlocking {
        val store = Persistence(tmp.resolve("state.json"))
        val transport = SimTransport(GlassFirmwareSim(), scope, SimTransport.Timing(ackMs = 1_500))
        val shell = Shell(FakeText(), transport, store, null, scope)
        shell.start()
        delay(200)                                  // the session's first flushes are on their way
        assertTrue(shell.quiescenceReport().contains("inflight="), "a flush is in flight at the stop: ${shell.quiescenceReport()}")
        shell.stop(stopTransport = false)           // the yield form: the acks land with no session running
        delay(2_500)
        shell.start()
        val t0 = System.currentTimeMillis()
        while (!shell.isQuiescent() && System.currentTimeMillis() - t0 < 30_000) delay(50)
        assertTrue(shell.isQuiescent(), "the next session never settled: ${shell.quiescenceReport()}")
        shell.stop()
    }
}
