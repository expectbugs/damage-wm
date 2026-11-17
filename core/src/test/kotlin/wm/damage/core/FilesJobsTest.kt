package wm.damage.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.AppHost
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Peer
import wm.damage.core.app.ProgramResult
import wm.damage.core.windows.files.FilesCore
import wm.damage.core.windows.files.FilesJobs
import wm.damage.core.windows.files.FilesPc
import wm.damage.core.web.Uploads
import wm.damage.core.windows.files.FilesProgram
import wm.damage.core.windows.files.FilesProvider
import wm.damage.core.windows.files.FilesService
import wm.damage.core.windows.files.SlottedFiles

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1 "One job at a time per PC", §5 F1; decisions A.4, A.12; S1-2 R0 §3): the
 * PC's one Files job slot. A second request while a job runs is refused with today's words; a job that fails frees
 * the slot and says why; a finished job stays in the slot until the next replaces it; the web's op, the PC's glasses
 * and the phone's glasses (the window channel) meet ONE slot; a copy counts its bytes. L1, the slot's supervisor: a
 * read-only job or an upload ticket with no body begun that makes no progress for its limit is ended and the slot
 * freed; a job that moves or changes a file never is, its busy line saying how long it has made no progress. The
 * supervisor's clock is the slot's injected one (no test sleeps). Every file is under the test's own temp root.
 */
class FilesJobsTest {
    private val tree = FilesTree()
    private val hosts = ArrayList<AppHost>()

    @AfterTest
    fun tearDown() {
        for (h in hosts) try { h.close(); h.executor.shutdown() } catch (e: Exception) { /* the next closes all the same */ }
        tree.close()
    }

    private fun pc(raw: FilesProvider = tree.files): FilesPc {
        val h = filesRecord(tree.root.resolve("record-${hosts.size}")).also { hosts += it }
        return FilesPc(h, raw, tree.books.toString(), null, null)
    }

    private fun op(action: String, targets: List<String>, params: JsonObject = JsonObject(emptyMap()), k: Long = 1) =
        Op("web-q5", OpId.Conn(7, k), FilesCore.WINDOW, action, targets, params = params, at = k)

    private val web = Peer("web-q5", Levels.FULL)

    private fun jobOf(r: ProgramResult): String =
        ((r as ProgramResult.Sent).result!!.jsonObject["job"] as JsonPrimitive).content

    private fun awaitEnd(jobs: FilesJobs, id: String): FilesJobs.Job {
        eventually("job $id ended") { jobs.current()?.let { it.id == id && !it.running } == true }
        return jobs.current()!!
    }

    /** The supervisor's clock (L1): moved by hand. */
    private var now = 1_000_000_000L

    /** A slot on [now] and the program ops in front of it, without a record (the supervisor's tests). */
    private fun program(raw: FilesProvider = tree.files, uploads: Uploads? = null): Pair<FilesJobs, FilesProgram> {
        val jobs = FilesJobs { now }
        return jobs to FilesProgram(SlottedFiles(raw, jobs), uploads)
    }

    private fun answer(r: ProgramResult): JsonObject = (r as ProgramResult.Sent).result!!.jsonObject

    @Test
    fun aSecondRequestWhileAJobRunsIsRefusedWithTodaysWords() {
        val jobs = FilesJobs()
        val h = jobs.begin("Paste 'a'", "copying")
        val e = assertFailsWith<FilesJobs.Busy> { jobs.begin("Move to Trash 'b'", "trashing") }
        assertEquals("busy — one operation at a time (Paste 'a' is running)", e.message, "L1 A-N12: it names its holder")
        assertEquals("Paste 'a'", e.holder.label)
        h.done(null)
        jobs.begin("Move to Trash 'b'", "trashing").done(null)
    }

    /** Pin (the spec's Q5: "a failed job keeps the slot"). */
    @Test
    fun aJobThatFailsFreesTheSlotAndSaysWhy() {
        val jobs = FilesJobs()
        assertFailsWith<IllegalStateException> { jobs.run<Unit>("Paste 'a'", "copying") { error("made-up disk error") } }
        val j = jobs.current()!!
        assertEquals(FilesJobs.FAILED, j.state)
        assertEquals("made-up disk error", (j.answer as JsonPrimitive).content)
        // the next request takes the slot
        jobs.begin("Rename 'b'", "renaming").done(null)
        assertEquals(FilesJobs.DONE, jobs.current()!!.state)
    }

    @Test
    fun aFinishedJobStaysInTheSlotUntilTheNextReplacesIt() {
        val jobs = FilesJobs()
        val first = jobs.begin("New folder 'x'", "creating")
        first.done(JsonPrimitive("made"))
        val j = jobs.current()!!
        assertEquals(first.id, j.id)
        assertEquals(FilesJobs.DONE, j.state)
        assertEquals(1, jobs.json().size, "desc/files' jobs carries the finished job")
        val second = jobs.begin("Stats 'x'", "counting")
        assertEquals(second.id, jobs.current()!!.id)
        assertTrue(second.id != first.id)
        second.done(null)
    }

    /** Pin (the spec's Q5: "a web copy does not refuse the glasses' op (slot per instance)"). */
    @Test
    fun aWebCopyRefusesThePcsGlassesAndThePhonesGlasses_oneSlot() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val a = tree.file("a.txt")
        val blocking = object : FilesProvider by tree.files {
            override fun copy(src: String, destDir: String): String {
                if (src == a) {                                   // the web's copy is held; any other runs at once
                    entered.countDown()
                    assertTrue(gate.await(10, TimeUnit.SECONDS), "the test's own bound on its held copy")
                }
                return tree.files.copy(src, destDir)
            }
        }
        val pc = pc(blocking)
        val b = tree.file("b.txt")
        val dest = tree.dir("dest")
        val r = pc.program.act(op(FilesCore.PASTE, listOf(dest), buildJsonObject { put("verb", "copy"); put("path", a) }), web)
        val id = jobOf(r)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // the PC's glasses call the slotted provider (A.4: today's call), and are refused with today's words
        val glasses = assertFailsWith<FilesJobs.Busy> { pc.slotted.copy(b, dest) }
        assertTrue(glasses.message!!.startsWith("busy — one operation at a time (Paste "), "${glasses.message}")
        // the phone's glasses through the window channel, as FilesService serves them
        val phone = assertFailsWith<FilesJobs.Busy> {
            FilesService(pc.slotted).request("copy", buildJsonObject { put("src", b); put("dest", dest) })
        }
        assertEquals(glasses.message, phone.message, "the phone's glasses get the same words")
        // and a second web op is answered Refused with the same words
        val second = pc.program.act(op(FilesCore.TRASH, listOf(b), k = 2), web)
        assertTrue(second is ProgramResult.Refused && second.reason.startsWith("busy — one operation at a time"), "$second")
        gate.countDown()
        assertEquals(FilesJobs.DONE, awaitEnd(pc.jobs, id).state)
        assertTrue(Files.exists(tree.home.resolve("dest").resolve("a.txt")))
        pc.slotted.copy(b, dest)
        assertTrue(Files.exists(tree.home.resolve("dest").resolve("b.txt")))
    }

    /** Pin (L1, the supervisor): a stats stalled on a mount is ended after 10 min with no progress and the slot is freed;
     *  the next job runs on a thread of its own while the stalled call still waits. */
    @Test
    fun aStalledStatsIsEndedAfterTenMinutesWithoutProgress_andTheNextJobRuns() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val stalled = object : FilesProvider by tree.files {
            override fun du(path: String): Long {
                entered.countDown()
                assertTrue(gate.await(30, TimeUnit.SECONDS), "the test's own bound on its held du")
                return tree.files.du(path)
            }
        }
        val (jobs, program) = program(stalled)
        val dir = tree.dir("big")
        tree.file("big/one.txt", "x".repeat(1_000))
        val id = jobOf(program.act(op(FilesCore.STATS, listOf(dir)), web))
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        now += FilesJobs.READ_ONLY_MS - 60_000
        jobs.supervise()
        assertTrue(jobs.current()!!.running, "not before its 10 minutes")
        val refused = program.act(op(FilesCore.MKDIR, listOf(tree.home.toString()), buildJsonObject { put("name", "n") }, k = 2), web)
        assertEquals("busy — one operation at a time (Stats 'big' is running; no progress for 9 min)", (refused as ProgramResult.Refused).reason)
        now += 60_000
        jobs.supervise()
        val ended = jobs.current()!!
        assertEquals(id, ended.id)
        assertEquals(FilesJobs.FAILED, ended.state)
        assertEquals("ended by the supervisor: no progress for 10 min", (ended.answer as JsonPrimitive).content)
        val next = jobOf(program.act(op(FilesCore.MKDIR, listOf(tree.home.toString()), buildJsonObject { put("name", "n") }, k = 3), web))
        assertEquals(FilesJobs.DONE, awaitEnd(jobs, next).state, "the slot is free and the next job is not queued behind the stalled one")
        assertTrue(Files.isDirectory(tree.home.resolve("n")))
        gate.countDown()
    }

    /** Pin (L1): the glasses' stats (the slot's own call, on the caller's thread) is ended the same way. */
    @Test
    fun theGlassesStatsIsEndedToo() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val stalled = object : FilesProvider by tree.files {
            override fun du(path: String): Long {
                entered.countDown()
                assertTrue(gate.await(30, TimeUnit.SECONDS), "the test's own bound on its held du")
                return 0
            }
        }
        val jobs = FilesJobs { now }
        val call = java.util.concurrent.CompletableFuture.supplyAsync { SlottedFiles(stalled, jobs).du(tree.dir("g")) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        now += FilesJobs.READ_ONLY_MS
        jobs.supervise()
        assertEquals(FilesJobs.FAILED, jobs.current()!!.state)
        gate.countDown()
        call.get(10, TimeUnit.SECONDS)
    }

    /** Pin (L1): progress moves the supervisor's mark — ten minutes count from the last progress, not the start. */
    @Test
    fun aReadOnlyJobThatMakesProgressIsNotEnded() {
        val (jobs, _) = program()
        val h = jobs.begin("Stats 'x'", "counting")
        h.endAfter(FilesJobs.READ_ONLY_MS)
        now += 9 * 60_000
        h.progress(10, null, "counting")
        now += 9 * 60_000
        jobs.supervise()
        assertTrue(jobs.current()!!.running, "9 minutes since its last progress")
        now += 60_000
        jobs.supervise()
        assertEquals(FilesJobs.FAILED, jobs.current()!!.state)
    }

    /** Pin (L1, the one that matters most): a job that moves or copies files and makes no progress is never ended and
     *  the slot is never freed by the supervisor; its busy line says how long it has made no progress. */
    @Test
    fun aMutatingJobWithNoProgressIsNeverEnded_itsBusyLineSaysHowLong() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val a = tree.file("a.txt")
        val held = object : FilesProvider by tree.files {
            override fun copy(src: String, destDir: String): String {
                entered.countDown()
                assertTrue(gate.await(30, TimeUnit.SECONDS), "the test's own bound on its held copy")
                return tree.files.copy(src, destDir)
            }
        }
        val (jobs, program) = program(held)
        val dest = tree.dir("dest")
        val id = jobOf(program.act(op(FilesCore.PASTE, listOf(dest), buildJsonObject { put("verb", "copy"); put("path", a) }), web))
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        now += 6 * 60 * 60_000L
        jobs.supervise()
        assertEquals(id, jobs.current()!!.id)
        assertTrue(jobs.current()!!.running, "never ended by the supervisor")
        val refused = program.act(op(FilesCore.TRASH, listOf(a), k = 2), web)
        assertEquals("busy — one operation at a time (Paste 'a.txt' is running; no progress for 360 min)", (refused as ProgramResult.Refused).reason)
        gate.countDown()
        assertEquals(FilesJobs.DONE, awaitEnd(jobs, id).state)
    }

    /** Pin (L1, B-M1 and B-S4's recovery): an upload ticket whose body never begins is ended after 2 min and its ticket
     *  goes with it, so a body that comes later places nothing while the slot may be another job's. */
    @Test
    fun anUploadTicketWithNoBodyIsEndedAfterTwoMinutes_andItsTicketGoesWithIt() {
        val uploads = Uploads(null)
        val (jobs, program) = program(uploads = uploads)
        val dir = tree.dir("in")
        val r = answer(program.act(op(FilesCore.UPLOAD, listOf(dir), buildJsonObject {
            put("file", buildJsonObject { put("name", "late.bin"); put("size", 3) })
        }), web))
        now += FilesJobs.UPLOAD_WAIT_MS - 1
        jobs.supervise()
        assertTrue(jobs.current()!!.running)
        now += 1
        jobs.supervise()
        assertEquals(FilesJobs.FAILED, jobs.current()!!.state)
        assertEquals("ended by the supervisor: no progress for 2 min", (jobs.current()!!.answer as JsonPrimitive).content)
        val late = uploads.receive(r["upload"]!!.jsonPrimitive.content, "web-q5", 3, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertEquals(404, late.code, late.text)
        assertFalse(Files.exists(tree.home.resolve("in").resolve("late.bin")))
        jobs.begin("New folder 'x'", "creating").done(null)
    }

    /** Pin (L1): an upload whose body has begun writes his file, so the supervisor never ends it. */
    @Test
    fun anUploadWhoseBodyBeganIsNotEnded() {
        val uploads = Uploads(null)
        val (jobs, program) = program(uploads = uploads)
        val dir = tree.dir("in")
        val r = answer(program.act(op(FilesCore.UPLOAD, listOf(dir), buildJsonObject {
            put("file", buildJsonObject { put("name", "slow.bin"); put("size", 3) })
        }), web))
        val begun = CountDownLatch(1)
        val release = CountDownLatch(1)
        val body = object : InputStream() {
            override fun read(): Int = throw AssertionError("read in blocks")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                begun.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS), "the test's own bound on its held body")
                return -1
            }
        }
        val reply = java.util.concurrent.CompletableFuture.supplyAsync { uploads.receive(r["upload"]!!.jsonPrimitive.content, "web-q5", 3, body) }
        assertTrue(begun.await(10, TimeUnit.SECONDS))
        now += FilesJobs.READ_ONLY_MS
        jobs.supervise()
        assertTrue(jobs.current()!!.running, "its body is arriving: never ended")
        release.countDown()
        assertEquals(400, reply.get(10, TimeUnit.SECONDS).code)
        assertTrue("ended after 0 of 3" in (awaitEnd(jobs, r["job"]!!.jsonPrimitive.content).answer as JsonPrimitive).content)
    }

    /** Pin (L1 R1 B6): an upload's body is its job's progress, so its busy line never says "no progress" while bytes
     *  arrive. */
    @Test
    fun anUploadsBytesAreItsProgress() {
        val uploads = Uploads(null)
        val (jobs, program) = program(uploads = uploads)
        val r = answer(program.act(op(FilesCore.UPLOAD, listOf(tree.dir("in")), buildJsonObject {
            put("file", buildJsonObject { put("name", "slow.bin"); put("size", 3) })
        }), web))
        val seen = ArrayList<String>()
        var reads = 0
        val body = object : InputStream() {
            override fun read(): Int = throw AssertionError("read in blocks")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                if (reads == 2) now += 5 * 60_000L                      // five minutes between the first and second byte
                if (reads == 3) seen += jobs.busyLine(jobs.current()!!) + " · " + jobs.current()!!.phase
                b[off] = reads.toByte()
                return 1
            }
        }
        assertEquals(201, uploads.receive(r["upload"]!!.jsonPrimitive.content, "web-q5", 3, body).code)
        assertEquals(listOf("busy — one operation at a time (Upload 'slow.bin' is running) · receiving"), seen)
    }

    /** Pin (L1): the PC's Files runs the supervisor on the record's pacing tick. */
    @Test
    fun thePcsTickRunsTheSupervisor() {
        val pc = pc()
        pc.jobs.begin("Stats 'x'", "counting").endAfter(0)
        hosts.last().tick()
        hosts.last().executor.call {}
        assertEquals(FilesJobs.FAILED, pc.jobs.current()!!.state)
    }

    /** The PC's provider counts a copy: the tree is walked first ("counting"), then each file's bytes as it lands. */
    @Test
    fun theLocalProviderCountsACopysBytes() {
        for (i in 1..4) tree.file("count/f$i.txt", "z".repeat(1_000 * i))
        val dest = tree.dir("counted")
        val calls = CopyOnWriteArrayList<Triple<Long, Long?, String>>()
        assertTrue(java.nio.file.Path.of(dest).startsWith(tree.root), "the copy writes under the test's own root only")
        tree.local.copy(tree.home.resolve("count").toString(), dest) { done, total, phase -> calls += Triple(done, total, phase) }
        assertEquals("counting", calls.first().third)
        val copying = calls.filter { it.third == "copying" }
        assertEquals(4, copying.size, "one step per file: $calls")
        assertEquals(10_000L, copying.last().first)
        assertEquals(10_000L, copying.last().second, "the total the walk counted")
        assertTrue(copying.zipWithNext().all { (x, y) -> y.first > x.first })
    }

    @Test
    fun aCopyOfATreeCountsItsBytes_andEndsWhole() {
        val pc = pc()
        val seen = CopyOnWriteArrayList<FilesJobs.Job>()
        pc.jobs.addListener { seen += it }
        for (i in 1..5) tree.file("tree/f$i.txt", "y".repeat(10_000))
        val dest = tree.dir("into")
        val id = jobOf(pc.program.act(op(FilesCore.PASTE, listOf(dest), buildJsonObject {
            put("verb", "copy"); put("path", tree.home.resolve("tree").toString())
        }), web))
        val end = awaitEnd(pc.jobs, id)
        assertEquals(FilesJobs.DONE, end.state, "${end.answer}")
        assertEquals(1.0, end.fraction)
        assertEquals("Paste 'tree'", end.label)
        assertTrue((end.answer as JsonObject)["path"]!!.jsonPrimitive.content.endsWith("/into/tree"))
        assertEquals(5, Files.list(tree.home.resolve("into").resolve("tree")).use { it.count() }.toInt())
        assertTrue(seen.first().running && seen.last() == end, "a begin and an end were told: $seen")
    }
}
