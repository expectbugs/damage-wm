package wm.damage.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.OpQueue
import wm.damage.core.util.Log

/**
 * Build S1-1, package P3 (`CONTRACT.md` §2.3 step 2, §2.4 "Numbering" and "Coalescing", §13 (b) "op queue"; the spec's
 * §2 P3): the phone's queue on disk. Nothing is sent before its line lands; a failed append is said once per streak,
 * kept in memory and tried again, and sent only once it lands; a position op coalesces only into an op never sent,
 * keeping the first `expect`; a restart sends again in order; the high-water takes the ops it covers out. A temp
 * directory, made-up window `t`, items and values; no network.
 */
class OpQueueTest {

    private val dir: Path = Files.createTempDirectory("damage-queue")
    private val file: Path = dir.resolve("queue.jsonl")
    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> if (tag == "queue") logs += "$level $message" }
    private val direct = Executor { it.run() }

    init {
        Log.addSink(sink)
    }

    @AfterTest
    fun tearDown() {
        Log.removeSink(sink)
        dir.toFile().deleteRecursively()
    }

    /** A place op, its input at [at]. */
    private fun place(id: OpId.Queued, item: String, to: Int, at: Int) =
        Op("phone", id, "t", "place", listOf(item), JsonPrimitive(to), at = at.toLong())

    private fun sameItem(item: String): (Op) -> Boolean = { o -> o.action == "place" && o.targets == listOf(item) }

    private fun n(o: Op?) = (o!!.id as OpId.Queued).n

    @Test
    fun nothingIsSentBeforeItsLineLands() {
        val w = HeldWriter()
        val q = OpQueue(file, w, mint = { "s1" })
        q.load()
        q.add({ id -> place(id, "b1", 10, 5) })
        assertNull(q.nextToSend(1) { true }, "its line has not landed: nothing goes")
        assertTrue(Files.readAllLines(file).none { "\"op\"" in it }, "and nothing of it is on disk yet")
        w.runAll()
        val sent = q.nextToSend(1) { true }
        assertEquals(1L, n(sent), "landed: sent")
        assertTrue(Files.readAllLines(file).any { "\"op\"" in it && "\"b1\"" in it })
        // a second op that has not landed holds the stream: nothing after it may go first
        q.add({ id -> place(id, "b2", 3, 1) })
        assertNull(q.nextToSend(1) { true })
        w.runAll()
        assertEquals(2L, n(q.nextToSend(1) { true }))
    }

    @Test
    fun aFailedAppendIsSaidOncePerStreakKeptInMemoryAndTriedAgain() {
        var full = true
        val notes = CopyOnWriteArrayList<String>()
        val q = OpQueue(file, direct, append = { f, t ->
            if (full) throw IOException("no space left on device (test)")
            OpQueue.appendForced(f, t)
        }, note = { notes += it }, mint = { "s1" })
        q.load()
        q.add({ id -> place(id, "b1", 10, 5) })
        q.add({ id -> place(id, "b2", 4, 2) })
        q.retry()
        assertEquals(2, q.waiting, "both kept in memory")
        assertNull(q.nextToSend(1) { true }, "neither has landed: neither is sent")
        assertEquals(1, logs.count { it.startsWith("ERROR") }, "said once for the streak: $logs")
        assertEquals(1, notes.count { it.startsWith("FAILED") })
        full = false
        q.retry()
        assertEquals(listOf(1L, 2L), listOf(n(q.nextToSend(1) { true }), n(q.nextToSend(1) { true })), "landed, then sent in order")
        assertTrue(logs.any { it.startsWith("INFO") && "after 4 failure(s)" in it }, "the streak's end is said with its count: $logs")
        val again = OpQueue(file, direct, mint = { "other" })
        assertEquals(listOf(1L, 2L), again.load().map { n(it) }, "both are on disk now")
    }

    @Test
    fun coalescingOnlyIntoUnsentOps() {
        val w = HeldWriter()
        val q = OpQueue(file, w, mint = { "s1" })
        q.load()
        q.add({ id -> place(id, "b1", 10, 5) }, sameItem("b1"))
        w.runAll()
        val merged = q.add({ id -> place(id, "b1", 20, 10) }, sameItem("b1"))
        assertEquals(1L, n(merged), "an unsent op takes the next one for its item")
        assertEquals(JsonPrimitive(20), merged.result)
        assertEquals(10L, merged.at, "with the next one's input time")
        assertEquals(1, q.waiting)
        assertNull(q.nextToSend(1) { true }, "its new body has not landed: it is not sent")
        w.runAll()
        assertEquals(JsonPrimitive(20), q.nextToSend(1) { true }!!.result)
        val after = q.add({ id -> place(id, "b1", 30, 20) }, sameItem("b1"))
        assertEquals(2L, n(after), "a sent op is frozen: the next place is an op of its own")
        assertEquals(listOf(JsonPrimitive(20), JsonPrimitive(30)), q.ops().map { it.result })
    }

    @Test
    fun aRestartSendsAgainInOrder() {
        val q = OpQueue(file, direct, mint = { "s1" })
        q.load()
        for ((k, item) in listOf("a", "b", "c").withIndex()) q.add({ id -> place(id, item, k + 10, k) }, sameItem(item))
        assertEquals(1L, n(q.nextToSend(1) { true }))
        assertEquals(2L, n(q.nextToSend(1) { true }))
        assertNotNull(q.answered(1))
        q.forget(listOf(OpId.Queued("s1", 1)))
        // the process ends here: op 2 was sent and not answered, op 3 never sent
        val again = OpQueue(file, direct, mint = { "other" })
        assertEquals(listOf(2L, 3L), again.load().map { n(it) })
        assertEquals("s1", again.stream, "the stream is kept")
        assertEquals(3, Files.readAllLines(file).size, "rewritten at the start: the header and the two ops")
        assertEquals(listOf(2L, 3L), listOf(n(again.nextToSend(1) { true }), n(again.nextToSend(1) { true })), "in order")
        val next = again.add({ id -> place(id, "c", 40, 12) }, sameItem("c"))
        assertEquals(4L, n(next), "an op loaded from disk may have been sent: frozen; the numbers go on")
    }

    @Test
    fun noFileIsANewStreamSaid() {
        val notes = CopyOnWriteArrayList<String>()
        val q = OpQueue(file, direct, note = { notes += it }, mint = { "fresh" })
        assertEquals(emptyList(), q.load())
        assertEquals("fresh", q.stream)
        assertTrue(notes.any { "a new stream" in it }, "$notes")
        assertTrue(Files.readAllLines(file).first().contains("\"stream\":\"fresh\""))
    }

    /** L1: a queue file that cannot be read is moved aside under a dated name and said, never overwritten. */
    @Test
    fun anUnreadableFileIsKeptAsideAndSaid() {
        val first = OpQueue(file, direct, mint = { "s1" })
        first.load()
        first.add({ id -> place(id, "a", 10, 1) })
        val bytes = Files.readAllBytes(file)
        val perms = Files.getPosixFilePermissions(file)
        Files.setPosixFilePermissions(file, emptySet())                 // this start cannot read it
        val notes = CopyOnWriteArrayList<String>()
        val q = OpQueue(file, direct, note = { notes += it }, mint = { "fresh" })
        assertEquals(emptyList(), q.load())
        assertEquals("fresh", q.stream)
        val kept = Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith("queue.jsonl.bad-") }.toList() }.single()
        Files.setPosixFilePermissions(kept, perms)
        assertTrue(bytes.contentEquals(Files.readAllBytes(kept)), "its ops kept as they were")
        assertTrue(notes.any { "could not be read" in it && "kept as ${kept.fileName}" in it }, "$notes")
        assertTrue(Files.readAllLines(file).first().contains("\"stream\":\"fresh\""), "a new file in its place")
    }

    /** L1 C1: a queue line 0.67 wrote — its op with `expect` and no `at` — loads, its input time the load's; a migrated
     *  place (`expect.pre`) keeps its old stamp. */
    @Test
    fun aQueueLineOf067LoadsWithTheLoadsTime() {
        Files.createDirectories(file.parent)
        Files.writeString(file, """
            {"stream":"a1b2","next":3}
            {"op":{"device":"phone","stream":"a1b2","n":1,"window":"reader","action":"place","targets":["place.x"],"result":"12|v1|","expect":{"value":null},"base":7},"v":1}
            {"op":{"device":"phone","stream":"a1b2","n":2,"window":"reader","action":"place","targets":["place.y"],"result":"5|v1|","expect":{"pre":1700000000000}},"v":1}
        """.trimIndent() + "\n")
        val before = System.currentTimeMillis()
        val q = OpQueue(file, direct, mint = { "other" })
        val ops = q.load()
        assertEquals("a1b2", q.stream)
        assertEquals(listOf(1L, 2L), ops.map { n(it) })
        assertTrue(ops[0].at >= before, "an op without its input time takes the load's: ${ops[0].at}")
        assertEquals(1_700_000_000_000L, ops[1].at, "a migrated place keeps its old stamp")
    }
}
