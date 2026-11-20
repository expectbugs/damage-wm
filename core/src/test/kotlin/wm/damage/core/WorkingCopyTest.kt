package wm.damage.core

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.app.Answer
import wm.damage.core.app.AppClient
import wm.damage.core.app.AppHost
import wm.damage.core.app.AppNet
import wm.damage.core.app.CoreExecutor
import wm.damage.core.app.Cursor
import wm.damage.core.app.Draft
import wm.damage.core.app.HostBackend
import wm.damage.core.app.LocalWindowSource
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.OpQueue
import wm.damage.core.app.Snap
import wm.damage.core.app.SourceListener
import wm.damage.core.app.WindowCore
import wm.damage.core.app.WindowState
import wm.damage.core.app.WorkingCopy
import wm.damage.core.content.ContentHostServer
import wm.damage.core.content.LocalContent
import wm.damage.core.shell.Persistence

/**
 * Build S1-1, package P3 (`CONTRACT.md` §2.3, §2.4, §9.3, §13 (b) "working copy"; the spec's §2 P3 and §3 "Where the
 * user sees a change"): the phone's working copy behind the `WindowSource` seam, against a real PC record served on a
 * loopback port (the same port across a cut and a restart). An equal answer draws nothing new; an older input that
 * arrives later changes nothing and the view shows the later one (L1 C1); and the envelope rows — the PC out of reach,
 * a phone restart, a PC restart, a new epoch, an op the PC took whose answer was lost, a "retry" refusal and the ops
 * behind it — each end with the PC holding the latest input. Made-up window `t`, devices and values; temp directories
 * only.
 */
class WorkingCopyTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closing = ArrayList<AutoCloseable>()
    private val phoneDir = Files.createTempDirectory("damage-workcopy")
    private val now = AtomicLong(T0)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        for (c in closing.asReversed()) c.close()
        phoneDir.toFile().deleteRecursively()
    }

    private fun <T : AutoCloseable> keep(t: T): T = t.also { closing += it }

    /** The PC: P1's record rig (window `t`), an [AppNet] over it and a content host whose loopback port stays the same
     *  when it is cut and brought back, and when the record restarts. */
    private class Pc : AutoCloseable {
        val rec = RecordRig().also { it.now = T0 + 3_600_000 }   // the PC's clock: an hour on, so no input is held to it
        var host: AppHost = rec.opened()
        private var net = AppNet(host, mapOf("t" to 1), clock = { rec.now })
        private var server: ContentHostServer? = null
        var port = 0
            private set

        init {
            serve(0)
        }

        private fun serve(p: Int) {
            val s = ContentHostServer(LocalContent(Files.createDirectories(rec.dir.resolve("books"))), p, "tok", app = net,
                bindAddress = InetAddress.getLoopbackAddress())
            s.start()
            server = s
            port = s.boundPort
        }

        fun cut() {
            server?.close()
            server = null
        }

        fun back() = serve(port)

        /** The process ends and starts again on the same data directory; [newEpoch] = its record.json is gone (L1 C2). */
        fun restart(newEpoch: Boolean) {
            cut()
            net.close()
            rec.stop(host)
            if (newEpoch) Files.delete(rec.recordFile)
            host = rec.opened()
            net = AppNet(host, mapOf("t" to 1), clock = { rec.now })
            back()
        }

        fun write(device: String, stream: String, n: Long, item: String, value: String, at: Long = T0): Answer =
            rec.ask(host, op(device, stream, n, "set", listOf(item), j(value), at = at))

        fun item(id: String) = rec.item(host, id)

        override fun close() {
            cut()
            net.close()
            rec.cleanUp()
        }
    }

    private val core = object : WindowCore {
        override val window = "t"
        override fun describe(state: WindowState): JsonObject = buildJsonObject { put("id", "t"); put("items", state.items.size) }
        override fun level(state: WindowState, addr: String): JsonObject? = null
    }

    private class Heard : SourceListener {
        val changes = CopyOnWriteArrayList<Set<String>>()
        val answers = CopyOnWriteArrayList<Pair<Op, Answer>>()
        override fun changed(state: WindowState, keys: Set<String>) {
            changes += keys
        }

        override fun answered(op: Op, answer: Answer) {
            answers += op to answer
        }
    }

    /** One of the phone's writers for a test: the writes from the [holdFrom]th on wait at a door, which opens when a
     *  task is handed to the writer while a write waits there (what a close that waits for its writes has to do), or
     *  at the test's end, where a write still held is dropped (the instance that held it has stopped). */
    private class Door(private val real: (Path, String) -> Unit, private val holdFrom: Int) {
        private val gate = CountDownLatch(1)
        private val writes = AtomicInteger()
        val held = AtomicInteger()
        val landed = AtomicInteger()
        @Volatile private var dropped = false
        private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "test-door-writer").apply { isDaemon = true } }
        val writer = Executor { r -> if (held.get() > 0) gate.countDown(); thread.execute(r) }
        val write: (Path, String) -> Unit = { f, t ->
            if (writes.incrementAndGet() >= holdFrom && gate.count > 0) {
                held.incrementAndGet()
                gate.await()
                held.decrementAndGet()
            }
            check(!dropped) { "a write of an instance the test stopped: not written" }
            real(f, t)
            landed.incrementAndGet()
        }

        fun drop() {
            dropped = true
            gate.countDown()
        }

        fun end() = thread.shutdown()
    }

    /** A door around the copy's saves or the queue's appends: the door opens (and drops) before the instance's close
     *  at the test's end, its thread ends after it. */
    private fun door(real: (Path, String) -> Unit, holdFrom: Int, phone: (Door) -> WorkingCopy): Pair<WorkingCopy, Door> {
        val d = Door(real, holdFrom)
        closing += AutoCloseable { d.end() }
        val wc = phone(d)
        closing += AutoCloseable { d.drop() }
        return wc to d
    }

    private fun phone(pc: Pc, note: (String) -> Unit = {},
                      saver: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-copy-saver").apply { isDaemon = true } },
                      saveWrite: (Path, String) -> Unit = SAVE, queueWriter: Executor = OpQueue.defaultWriter(),
                      queueAppend: (Path, String) -> Unit = OpQueue::appendForced,
                      wrap: (WorkingCopy) -> AppClient.Listener = { it }): WorkingCopy {
        val wc = WorkingCopy(phoneDir, TEST_ACTIONS, CoreExecutor("damage-core-phone-test"), contracts = mapOf("t" to 1),
            clock = now::get, note = note, queueWriter = queueWriter, queueAppend = queueAppend, saveWrite = saveWrite,
            saver = saver)
        wc.start { s ->
            AppClient("127.0.0.1", pc.port, AppClient.Credential.Token("tok"), s, wrap(wc), scope, contracts = mapOf("t" to 1),
                retryPacingMs = 20, refusedPacingMs = 50, pingEveryMs = 0, clock = now::get)
        }
        closing += AutoCloseable {
            wc.close()
            wc.executor.shutdown()
        }
        return wc
    }

    private fun source(wc: WorkingCopy, heard: Heard) = keep(LocalWindowSource(core, wc)).also { it.addListener(heard) }

    private fun state(wc: WorkingCopy): WindowState = wc.executor.call { wc.stateOf("t") }

    private suspend fun synced(wc: WorkingCopy, pc: Pc) = waitUntil(10_000, 5,
        { "in step: cursor ${wc.cursor}, the PC at ${pc.host.seq}, ${wc.queue.waiting} waiting" }) {
        wc.client?.welcome != null && wc.cursor?.seq == pc.host.seq && wc.queue.waiting == 0
    }

    private suspend fun cut(pc: Pc, wc: WorkingCopy) {
        pc.cut()
        waitUntil(10_000, 5, { "the connection noticed as gone" }) { wc.client?.welcome == null }
    }

    @Test
    fun anEqualAnswerChangesNothingDrawn(): Unit = runBlocking {
        val pc = keep(Pc())
        pc.write("desk", "d1", 1, "a", "one")
        val wc = phone(pc)
        synced(wc, pc)
        val heard = Heard()
        val src = source(wc, heard)
        assertEquals(j("one"), src.state().itemNamed("a")!!.value)
        src.act(Draft("set", listOf("a"), j("two")))
        waitUntil(10_000, 5, { "applied on the PC" }) { pc.item("a")?.value == j("two") }
        synced(wc, pc)
        wc.executor.call {}
        assertEquals(listOf(setOf("rec.t.a")), heard.changes.toList(),
            "drawn once, at once; its answer and the PC's change were equal to it")
        assertEquals(listOf<Answer>(Answer.Seen), heard.answers.map { it.second })
        assertEquals(j("two"), src.state().itemNamed("a")!!.value)
        assertEquals(emptySet(), src.state().pending)
    }

    /** L1 C1: the phone sets a value while the PC is away; the desk sets it a minute later; when the phone reaches the
     *  PC its older input changes nothing, it is answered `seen`, and the view shows the desk's value. */
    @Test
    fun anOlderInputArrivingLaterChangesNothing_andTheViewShowsTheLaterOne(): Unit = runBlocking {
        val pc = keep(Pc())
        pc.write("desk", "d1", 1, "a", "one")
        val wc = phone(pc)
        synced(wc, pc)
        val heard = Heard()
        val src = source(wc, heard)
        cut(pc, wc)
        src.act(Draft("set", listOf("a"), j("mine")))                  // the phone's input, at T0
        wc.executor.call {}
        assertEquals(j("mine"), src.state().itemNamed("a")!!.value, "drawn at once")
        pc.write("desk", "d1", 2, "a", "desk", at = T0 + 60_000)       // another device, a minute later
        pc.back()
        // the op changes nothing, so no change follows its answer: wait on the answer and the queue, not the cursor
        waitUntil(10_000, 5, { "answered" }) { heard.answers.isNotEmpty() && wc.queue.waiting == 0 }
        wc.executor.call {}
        assertEquals(listOf<Answer>(Answer.Seen), heard.answers.map { it.second }, "answered once, nothing to say")
        assertEquals(j("desk"), pc.item("a")!!.value, "the later input stands")
        assertEquals(j("desk"), src.state().itemNamed("a")!!.value, "and the view shows it")
        assertEquals(listOf(setOf("rec.t.a"), setOf("rec.t.a")), heard.changes.toList(), "drawn at once, then the desk's")
    }

    @Test
    fun thePcOutOfReach_opsWait_thenTheyDrain(): Unit = runBlocking {
        val pc = keep(Pc())
        val wc = phone(pc)
        synced(wc, pc)
        val src = source(wc, Heard())
        cut(pc, wc)
        val c = wc.client!!
        c.pingTick()
        now.set(T0 + 15_000)
        c.pingTick()
        waitUntil(10_000, 5, { "out of reach: ${wc.reach}" }) { wc.reach is AppClient.Reach.Down }
        src.act(Draft("set", listOf("x"), j("1")))
        src.act(Draft("set", listOf("y"), j("2")))
        wc.executor.call {}
        assertEquals(2, wc.queue.waiting)
        assertEquals(j("1"), src.state().itemNamed("x")!!.value, "drawn from the copy meanwhile")
        pc.back()
        waitUntil(10_000, 5, { "drained: ${wc.queue.waiting} waiting" }) {
            wc.queue.waiting == 0 && pc.item("x") != null && pc.item("y") != null
        }
        waitUntil(10_000, 5, { "back: ${wc.reach}" }) { wc.reach == AppClient.Reach.Up }
        assertEquals(listOf(j("1"), j("2")), listOf(pc.item("x")!!.value, pc.item("y")!!.value))
    }

    @Test
    fun aPhoneRestart_theCopyItsCursorAndTheQueueComeBackFromDisk(): Unit = runBlocking {
        val pc = keep(Pc())
        pc.write("desk", "d1", 1, "a", "one")
        val first = phone(pc)
        synced(first, pc)
        cut(pc, first)
        first.executor.call { first.submit("t", Draft("set", listOf("b"), j("two"))) }
        val queueFile = phoneDir.resolve(WorkingCopy.QUEUE_FILE)
        waitUntil(10_000, 5, { "landed" }) { Files.readAllLines(queueFile).any { "\"b\"" in it } }
        val at = first.cursor
        first.close()
        first.executor.shutdown()

        val again = phone(pc)
        waitUntil(10_000, 5, { "loaded" }) { again.client != null }
        assertEquals(at, again.cursor, "the cursor came back")
        val s = state(again)
        assertEquals(j("one"), s.itemNamed("a")!!.value, "the PC's state came back")
        assertEquals(j("two"), s.itemNamed("b")!!.value, "the waiting op is drawn")
        assertEquals(setOf("rec.t.b"), s.pending)
        pc.back()
        waitUntil(10_000, 5, { "sent at the welcome" }) { pc.item("b")?.value == j("two") && again.queue.waiting == 0 }
        assertEquals("phone", pc.item("b")!!.by)
    }

    /** Pin (WC, 2026-11-20): a clean close lands the copy's pending save before it returns. The snapshot's save waits
     *  at a door in the copy's writer; the close, then the restart BEFORE the door opens. A close that returns with the
     *  save still held leaves the restart no cursor — the whole-suite failure of the test above ("the cursor came back
     *  … was null"), made certain. */
    @Test
    fun aCleanClose_landsTheCopysPendingSave_beforeItReturns(): Unit = runBlocking {
        val pc = keep(Pc())
        pc.write("desk", "d1", 1, "a", "one")
        val (first, door) = door(SAVE, holdFrom = 1) { d -> phone(pc, saver = d.writer, saveWrite = d.write) }
        synced(first, pc)
        cut(pc, first)                                                  // the restart's cursor can only come from disk
        waitUntil(10_000, 5, { "the snapshot's save at the door" }) { door.held.get() == 1 }
        val at = first.cursor
        first.close()
        first.executor.shutdown()
        val heldAtClose = door.held.get()
        val again = phone(pc)
        waitUntil(10_000, 5, { "loaded" }) { again.client != null }
        assertEquals(at, again.cursor, "the cursor came back (saves held at the close's return: $heldAtClose)")
        assertEquals(j("one"), state(again).itemNamed("a")!!.value, "the PC's state came back")
    }

    /** Pin (WC, the class's sweep): a clean close lands the op lines handed to the queue's writer before it returns —
     *  an op taken just before the close is on disk, drawn and sent after the restart. */
    @Test
    fun aCleanClose_landsTheQueuesPendingLine_beforeItReturns(): Unit = runBlocking {
        val pc = keep(Pc())
        pc.write("desk", "d1", 1, "a", "one")
        val (first, door) = door(OpQueue::appendForced, holdFrom = 1) { d ->
            phone(pc, queueWriter = d.writer, queueAppend = d.write)
        }
        synced(first, pc)
        cut(pc, first)
        first.executor.call { first.submit("t", Draft("set", listOf("b"), j("two"))) }
        waitUntil(10_000, 5, { "the op's line at the door" }) { door.held.get() == 1 }
        first.close()
        first.executor.shutdown()
        val heldAtClose = door.held.get()
        val again = phone(pc)
        waitUntil(10_000, 5, { "loaded" }) { again.client != null }
        assertEquals(1, again.queue.waiting, "the op came back (lines held at the close's return: $heldAtClose)")
        assertEquals(j("two"), state(again).itemNamed("b")?.value, "and is drawn")
        pc.back()
        waitUntil(10_000, 5, { "sent at the welcome" }) { pc.item("b")?.value == j("two") && again.queue.waiting == 0 }
    }

    /** WC, the unclean stop (the process ends; no close): the copy's last save has not landed. Only the cursor's last
     *  advance is lost — the next start shows the copy as last saved (an older cursor), and its connection takes a
     *  SNAPSHOT (L1 C2: every subscription names no cursor; there is no catch-up). The op the PC took before the stop is
     *  still in the queue (its line leaves only after a save that holds it): it is drawn, sent once more and changes
     *  nothing on the PC (its `seq` stays). Nothing the phone queued is lost or applied twice. */
    @Test
    fun anUncleanStop_losesOnlyTheCursorsLastAdvance_andTheNextStartComesBackBySnapshot(): Unit = runBlocking {
        val pc = keep(Pc())
        pc.write("desk", "d1", 1, "a", "one")
        val (first, door) = door(SAVE, holdFrom = 2) { d -> phone(pc, saver = d.writer, saveWrite = d.write) }
        synced(first, pc)
        val epoch = pc.host.epoch
        waitUntil(10_000, 5, { "the snapshot's save landed" }) { door.landed.get() == 1 }
        first.executor.call { first.submit("t", Draft("set", listOf("b"), j("two"))) }
        synced(first, pc)
        waitUntil(10_000, 5, { "the next save at the door" }) { door.held.get() == 1 }
        assertEquals(Cursor(epoch, 2), first.cursor)
        val b = pc.item("b")
        pc.cut()
        first.client!!.close()                                          // the process ends: its wire with it
        val snaps = CopyOnWriteArrayList<Snap>()
        val again = phone(pc) { w ->
            object : AppClient.Listener by w {
                override fun snap(s: Snap) {
                    snaps += s
                    w.snap(s)
                }
            }
        }
        waitUntil(10_000, 5, { "loaded" }) { again.client != null }
        assertEquals(Cursor(epoch, 1), again.cursor, "the copy as last saved: the cursor's last advance is lost")
        val s = state(again)
        assertEquals(j("one"), s.itemNamed("a")!!.value)
        assertEquals(j("two"), s.itemNamed("b")?.value, "the op the PC took is drawn from the queue")
        assertEquals(1, again.queue.waiting)
        pc.back()
        // the op sent again takes a line of the PC's log with no effect (its seq is sent to no one): wait on the queue
        waitUntil(10_000, 5, { "answered: ${again.queue.waiting} waiting, cursor ${again.cursor}, the PC at ${pc.host.seq}" }) {
            again.client?.welcome != null && again.queue.waiting == 0 && pc.host.seq == 3L
        }
        again.executor.call {}
        assertEquals(listOf(2L), snaps.map { it.seq }, "one snapshot, at the PC's place")
        assertEquals(Cursor(epoch, 2), again.cursor, "the snapshot brought the lost advance back")
        assertEquals(b, pc.item("b"), "the op sent again changed nothing on the PC")
        assertEquals(j("two"), state(again).itemNamed("b")!!.value)
        assertEquals(emptySet(), state(again).pending)
    }

    @Test
    fun aPcRestart_aSnapshotEqualToTheCopyRepaintsNothing(): Unit = runBlocking {
        val pc = keep(Pc())
        pc.write("desk", "d1", 1, "a", "one")
        pc.write("desk", "d1", 2, "b", "two")
        val snaps = AtomicInteger()
        val wc = phone(pc) { w ->
            object : AppClient.Listener by w {
                override fun snap(s: Snap) {
                    snaps.incrementAndGet()
                    w.snap(s)
                }
            }
        }
        synced(wc, pc)
        val heard = Heard()
        source(wc, heard)
        pc.write("desk", "d1", 3, "a", "one")                   // a same-value op: its seq is sent to no one
        val epoch = pc.host.epoch
        pc.restart(newEpoch = false)
        assertEquals(epoch, pc.host.epoch, "the same epoch")
        waitUntil(10_000, 5, { "a snapshot after the restart (${snaps.get()})" }) { snaps.get() == 2 }
        synced(wc, pc)
        wc.executor.call {}
        assertEquals(Cursor(epoch, 3), wc.cursor)
        assertEquals(emptyList(), heard.changes.toList(), "equal to the copy: nothing repaints")
    }

    /** The PC's record.json is gone (a new epoch; its log replays): the waiting ops are sent again and apply. */
    @Test
    fun aNewEpoch_theWaitingOpsAreSentAgainAndApply(): Unit = runBlocking {
        val pc = keep(Pc())
        pc.write("desk", "d1", 1, "p", "1")
        pc.write("desk", "d1", 2, "q", "x")
        val wc = phone(pc)
        synced(wc, pc)
        val heard = Heard()
        val src = source(wc, heard)
        cut(pc, wc)
        src.act(Draft("drop", listOf("q")))
        src.act(Draft("place", listOf("p"), j("5")))
        val before = wc.stream
        val old = pc.host.epoch
        pc.rec.now += 1_000                                       // the store is minted again later than the first
        pc.restart(newEpoch = true)
        assertNotEquals(old, pc.host.epoch, "a new epoch")
        waitUntil(10_000, 5, { "both applied: ${heard.answers}" }) {
            pc.item("p")?.value == j("5") && pc.item("q")?.gone == true && heard.answers.size == 2 && wc.queue.waiting == 0
        }
        synced(wc, pc)
        wc.executor.call {}
        assertTrue(heard.answers.all { it.second == Answer.Seen }, "${heard.answers}")
        assertEquals(j("5"), src.state().itemNamed("p")!!.value)
        assertNull(src.state().itemNamed("q")!!.value.takeIf { it != JsonNull })
        assertEquals(before, wc.stream, "the stream goes on")
    }

    /** L1 C1: the PC took the op before the connection ended, and its answer went with the connection; the desk then
     *  set the item later. The op is sent again at the next connection and changes nothing: applying twice is a no-op. */
    @Test
    fun anOpThePcTookWhoseAnswerWasLost_isSentAgainAndChangesNothing(): Unit = runBlocking {
        val pc = keep(Pc())
        pc.write("desk", "d1", 1, "x", "a")
        val wc = phone(pc)
        synced(wc, pc)
        val heard = Heard()
        val src = source(wc, heard)
        cut(pc, wc)
        src.act(Draft("set", listOf("x"), j("b")))
        waitUntil(10_000, 5, { "waiting" }) { wc.queue.waiting == 1 }
        val stream = wc.stream
        assertEquals(Answer.Seen, pc.rec.ask(pc.host, Op("phone", OpId.Queued(stream, 1), "t", "set", listOf("x"), j("b"), at = T0)))
        pc.write("desk", "d1", 2, "x", "c", at = T0 + 1_000)
        pc.back()
        waitUntil(10_000, 5, { "answered: ${heard.answers}" }) { heard.answers.isNotEmpty() && wc.queue.waiting == 0 }
        wc.executor.call {}
        assertEquals(j("c"), pc.item("x")!!.value, "the later input stands")
        assertEquals(listOf<Answer>(Answer.Seen), heard.answers.map { it.second })
        assertEquals(j("c"), src.state().itemNamed("x")!!.value)
        assertEquals(stream, wc.stream, "the stream goes on")
    }

    @Test
    fun aRetryAndTheOpsBehindItAreSentAgainInOrderAtTheNextPing(): Unit = runBlocking {
        val pc = keep(Pc())
        val wc = phone(pc)
        synced(wc, pc)
        pc.rec.syncFails = true                                  // the PC's log cannot be written
        for (i in listOf("a", "b", "c")) wc.executor.execute("a test op") {
            wc.submit("t", Draft("set", listOf(i), j(i)))
        }
        waitUntil(10_000, 5, { "the PC tried the first" }) { pc.rec.forced.get() >= 1 }
        Thread.sleep(300)
        val tried = pc.rec.forced.get()
        assertTrue(tried in 1..3, "each op tried at most once (L1 C1: no gap holds the ops behind a retry): $tried")
        Thread.sleep(300)
        assertEquals(tried, pc.rec.forced.get(), "not sent again before the next ping")
        assertEquals(3, wc.queue.waiting)
        pc.rec.syncFails = false
        wc.client!!.pingTick()
        waitUntil(10_000, 5, { "all three: ${wc.queue.waiting} waiting" }) { wc.queue.waiting == 0 }
        assertEquals(listOf(j("a"), j("b"), j("c")), listOf("a", "b", "c").map { pc.item(it)!!.value })
    }

    @Test
    fun thePcsOwnSourceWritesAsItsDevice_acrossARestart(): Unit = runBlocking {
        val rec = RecordRig()
        closing += AutoCloseable { rec.cleanUp() }
        val host = rec.opened()
        val heard = Heard()
        val src = keep(LocalWindowSource(core, HostBackend(host, "pc", "p1"))).also { it.addListener(heard) }
        src.act(Draft("set", listOf("a"), j("one")))
        src.act(Draft("set", listOf("a"), j("two")))
        waitUntil(10_000, 5, { "answered: ${heard.answers}" }) { heard.answers.size == 2 }
        host.executor.call {}
        assertEquals(listOf<Answer>(Answer.Seen, Answer.Seen), heard.answers.map { it.second })
        assertEquals(j("two"), src.state().itemNamed("a")!!.value)
        assertEquals(2, heard.changes.size)
        rec.stop(host)
        val again = rec.opened()
        val src2 = keep(LocalWindowSource(core, HostBackend(again, "pc", "p1")))
        src2.act(Draft("set", listOf("a"), j("three")))
        waitUntil(10_000, 5, { "applied after the restart" }) { src2.state().itemNamed("a")?.value == j("three") }
        assertEquals("pc", rec.item(again, "a")!!.by)
        assertEquals(buildJsonObject { put("id", "t"); put("items", 1) }, src2.desc())
    }

    @Test
    fun thePcsOwnSource_afterARetry_itsNextOpsApply(): Unit = runBlocking {
        // fix round 1, ruling 1.6: a `retry` changed nothing; the source's later ops are taken
        val rec = RecordRig()
        closing += AutoCloseable { rec.cleanUp() }
        val host = rec.opened()
        val heard = Heard()
        val src = keep(LocalWindowSource(core, HostBackend(host, "pc", "p1"))).also { it.addListener(heard) }
        rec.syncFails = true                                          // the log cannot be written
        src.act(Draft("set", listOf("a"), j("one")))
        waitUntil(10_000, 5, { "answered: ${heard.answers}" }) { heard.answers.size == 1 }
        assertEquals(Answer.RETRY, (heard.answers[0].second as Answer.Refused).code)
        rec.syncFails = false
        src.act(Draft("set", listOf("a"), j("one")))
        src.act(Draft("set", listOf("a"), j("two")))
        waitUntil(10_000, 5, { "answered: ${heard.answers}" }) { heard.answers.size == 3 }
        assertEquals(listOf<Answer>(Answer.Seen, Answer.Seen), heard.answers.drop(1).map { it.second })
        assertEquals(j("two"), rec.item(host, "a")!!.value)
    }

    /** Fix round 2, ruling 1.7 (F1a's Found 4): a refusal for good that reaches no presenter is logged and journaled
     *  on the host where it arrives — the PC's own source with no presenter listening. */
    @Test
    fun aRefusalForGoodThatReachesNoPresenter_isJournaledOnThePc(): Unit = runBlocking {
        val rec = RecordRig()
        closing += AutoCloseable { rec.cleanUp() }
        val host = rec.opened()
        val src = keep(LocalWindowSource(core, HostBackend(host, "pc", "p1")))       // no presenter listens
        src.act(Draft("nonesuch", listOf("a"), j("one")))
        host.executor.call {}
        host.executor.call {}
        assertTrue(rec.notes().any { "refused for good" in it && "nonesuch" in it }, "the journal: ${rec.notes()}")
    }

    /** The same on the phone: the working copy, no presenter of the window watching. */
    @Test
    fun aRefusalForGoodThatReachesNoPresenter_isJournaledOnThePhone(): Unit = runBlocking {
        val pc = keep(Pc())
        val notes = CopyOnWriteArrayList<String>()
        val wc = phone(pc, note = { notes += it })
        synced(wc, pc)
        wc.executor.call { wc.submit("t", Draft("nonesuch", listOf("a"), j("one"))) }
        waitUntil(10_000, 5, { "answered: ${wc.queue.waiting} waiting; $notes" }) { notes.any { "refused for good" in it } }
        assertTrue(notes.any { "refused for good" in it && "nonesuch" in it }, "$notes")
    }

    /** Pin (L1 R1 RS NOTE 1): the phone folds a notice as the PC orders it, by its occurrence — a dismiss of occurrence
     *  1, a later input than the PC's raise of occurrence 2 the phone had not seen, does not hide occurrence 2 there. */
    @Test
    fun aDismissOfAnOlderOccurrenceDoesNotHideTheNewerOneOnThePhone(): Unit = runBlocking {
        val rig = keep(ReaderRig(scope))
        val h = rig.pc()
        val core = wm.damage.core.app.NoticeCore(h, clock = { rig.now.get() })
        val id = "torrent:n1"
        fun held() = wm.damage.core.app.Notices.parse(id, h.read(wm.damage.core.app.Notices.WINDOW).third
            .items[wm.damage.core.app.Record.key(wm.damage.core.app.Notices.WINDOW, id)]!!.value)!!
        core.raise("torrent", "n1", "done · a made-up download", "torrents", "t:n1", "Torrents")
        repeat(4) { h.executor.call {} }
        val first = held()
        core.raise("torrent", "n1", "done · again", "torrents", "t:n1", "Torrents")
        repeat(4) { h.executor.call {} }
        assertEquals("2", held().occ)
        rig.now.addAndGet(60_000)                                         // the dismiss is a later input than the raise
        val lanePort = java.net.ServerSocket(0).use { it.localPort }
        val wc = rig.phone(lanePort)                                      // the PC out of reach
        wc.executor.call { wc.submit(wm.damage.core.app.Notices.WINDOW, wm.damage.core.app.Notices.dismissDraft(first)) }
        rig.serve(h, lanePort)
        waitUntil(10_000, 5, { "answered: ${wc.queue.waiting} waiting" }) { wc.client?.welcome != null && wc.queue.waiting == 0 }
        wc.executor.call {}
        val shown = wm.damage.core.app.Notices.records(wc.executor.call { wc.stateOf(wm.damage.core.app.Notices.WINDOW) })[id]
        assertEquals("2", shown?.occ, "occurrence 2 on the phone: $shown")
        assertEquals(wm.damage.core.app.Notices.RAISED, shown?.state)
        assertEquals("2", held().occ)
    }

    /** L1 C1, C4, the decoders first: a `copy.json` as 0.67 saved it — items with `rev` and an `na` mark, `colls`, and
     *  a kept loss under `unheard` — is read: the items and the cursor come back, nothing is set aside. */
    @Test
    fun aCopyOf067IsRead() {
        Files.createDirectories(phoneDir)
        Files.writeString(phoneDir.resolve(WorkingCopy.COPY_FILE), """{"v":1,"epoch":1763300000000,"seq":42,"items":{""" +
            """"rec.t.a":{"v":"one","rev":3,"by":"desk","at":1763300000100,"na":{"mine":"x","by":"phone","at":1763300000200}},""" +
            """"rec.t.g":{"gone":true,"rev":2,"by":"phone","at":5}},"colls":{},"unheard":[{"op":{"device":"phone",""" +
            """"stream":"s","n":1,"window":"t","action":"set","targets":["a"],"result":"x","expect":{"value":"zero"}},""" +
            """"answer":{"a":"lost","losses":[{"item":"a","standing":"one","by":"desk","at":1763300000100,"mine":"x"}]}}]}""")
        val wc = WorkingCopy(phoneDir, TEST_ACTIONS, CoreExecutor("damage-core-copy-067"), contracts = mapOf("t" to 1), clock = now::get)
        closing += AutoCloseable { wc.close(); wc.executor.shutdown() }
        wc.open()
        assertEquals(Cursor(1_763_300_000_000L, 42), wc.cursor)
        val s = state(wc)
        assertEquals(wm.damage.core.app.Item(j("one"), "desk", 1_763_300_000_100L), s.itemNamed("a"))
        assertTrue(s.itemNamed("g")!!.gone)
        assertTrue(Files.list(phoneDir).use { l -> l.noneMatch { it.fileName.toString().contains(".bad") } }, "nothing set aside")
    }

    private companion object {
        /** 2023-11-14 22:13:20 UTC. */
        const val T0 = 1_700_000_000_000L
        /** The copy's save as the product makes it. */
        val SAVE: (Path, String) -> Unit = { f, t -> Persistence.writeAtomic(f, t, sync = true) }
    }
}
