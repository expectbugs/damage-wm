package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import org.junit.After
import wm.damage.core.app.Answer
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.OpLog
import wm.damage.core.util.Log

/**
 * Build S1-1, package P1 (`CONTRACT.md` §2.2, §14 (b) "one core executor per host"): one thread, tasks in the order
 * handed in, a failing task said and the next run, a call from the executor run in place; ops from several threads
 * applied one at a time in arrival order, each answered once, every line's `seq` the next.
 */
class ExecutorTest {
    private val rig = RecordRig()
    private val logs = CopyOnWriteArrayList<String>()
    private val sink = Log.Sink { level, tag, message -> logs += "$level $tag $message" }

    @After
    fun tearDown() {
        Log.removeSink(sink)
        rig.cleanUp()
    }

    @Test
    fun oneThreadInTheOrderHandedIn() {
        val ex = CoreExecutor("damage-core-order")
        val seen = ArrayList<String>()
        for (i in 1..50) ex.execute("t$i") { seen += "$i ${Thread.currentThread().name}" }
        ex.call {}
        assertEquals((1..50).map { "$it damage-core-order" }, seen)
        assertTrue(ex.call { ex.isCurrent() })
        assertTrue(!ex.isCurrent())
        assertEquals(7, ex.call { ex.call { 7 } }, "a call on the executor runs in place")
        ex.shutdown()
    }

    @Test
    fun aFailingTaskIsSaidAndTheNextRuns() {
        Log.addSink(sink)
        val ex = CoreExecutor("damage-core-fault")
        var after = false
        ex.execute("the faulty one") { throw IllegalStateException("made up") }
        ex.execute("the next") { after = true }
        ex.call {}
        assertTrue(after)
        assertTrue(logs.any { it.startsWith("ERROR core") && "the faulty one" in it && "made up" in it }, logs.toString())
        ex.shutdown()
        ex.execute("after shutdown") {}
        assertTrue(logs.any { "refused a task (after shutdown)" in it }, "refused, and said")
    }

    @Test
    fun opsFromSeveralThreadsApplyOneAtATimeInArrivalOrder() {
        val h = rig.opened()
        val answers = CopyOnWriteArrayList<Answer>()
        val start = CountDownLatch(1)
        val threads = (1..4).map { t ->
            Thread {
                start.await()
                for (n in 1L..40L) h.act(op("dev$t", "s$t", n, "set", listOf("i.s$t.$n"), j("$t-$n"))) {
                    answers += it
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        h.executor.call {}
        assertEquals(160, answers.size, "each op answered once")
        assertTrue(answers.all { it == Answer.Seen })
        assertEquals(160L, h.seq)
        val lines = Files.readAllLines(rig.logFile).map { OpLog.decode(it) }
        assertEquals(160, h.read("t").third.items.size)
        for (t in 1..4) assertEquals((1L..40L).toList(), lines.mapNotNull { l -> l.op.queued?.takeIf { it.stream == "s$t" }?.n },
            "a stream's ops in its order")
        assertEquals((1L..160L).toList(), lines.map { it.seq }, "every line takes the next seq")
    }
}
