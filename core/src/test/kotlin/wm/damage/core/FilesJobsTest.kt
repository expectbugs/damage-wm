package wm.damage.core

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import wm.damage.core.app.Levels
import wm.damage.core.app.Op
import wm.damage.core.app.OpId
import wm.damage.core.app.Peer
import wm.damage.core.app.ProgramResult
import wm.damage.core.windows.files.FilesCore
import wm.damage.core.windows.files.FilesJobs
import wm.damage.core.windows.files.FilesPc
import wm.damage.core.windows.files.FilesProvider
import wm.damage.core.windows.files.FilesService

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1 "One job at a time per PC", §5 F1; decisions A.4, A.12; S1-2 R0 §3): the
 * PC's one Files job slot. A second request while a job runs is refused with today's words; a job that fails frees
 * the slot and says why; a finished job stays in the slot until the next replaces it; the web's op, the PC's glasses
 * and the phone's glasses (the window channel) meet ONE slot; a stalled `du` holds it, its age shown, nothing frees it
 * but its end; a copy counts its bytes. Every file is under the test's own temp root ([FilesTree]).
 */
class FilesJobsTest {
    private val tree = FilesTree()
    private val hosts = ArrayList<wm.damage.core.app.AppHost>()

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
        Op("web-q5", OpId.Conn(7, k), FilesCore.WINDOW, action, targets, params = params)

    private val web = Peer("web-q5", Levels.FULL)

    private fun jobOf(r: ProgramResult): String =
        ((r as ProgramResult.Sent).result!!.jsonObject["job"] as JsonPrimitive).content

    private fun awaitEnd(jobs: FilesJobs, id: String): FilesJobs.Job {
        eventually("job $id ended") { jobs.current()?.let { it.id == id && !it.running } == true }
        return jobs.current()!!
    }

    @Test
    fun aSecondRequestWhileAJobRunsIsRefusedWithTodaysWords() {
        val jobs = FilesJobs()
        val h = jobs.begin("Paste 'a'", "copying")
        val e = assertFailsWith<FilesJobs.Busy> { jobs.begin("Move to Trash 'b'", "trashing") }
        assertEquals("busy — one operation at a time", e.message)
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
        assertEquals("busy — one operation at a time", glasses.message)
        // the phone's glasses through the window channel, as FilesService serves them
        val phone = assertFailsWith<FilesJobs.Busy> {
            FilesService(pc.slotted).request("copy", buildJsonObject { put("src", b); put("dest", dest) })
        }
        assertEquals("busy — one operation at a time", phone.message)
        // and a second web op is answered Refused with the same words
        val second = pc.program.act(op(FilesCore.TRASH, listOf(b), k = 2), web)
        assertTrue(second is ProgramResult.Refused && second.reason.startsWith("busy — one operation at a time"), "$second")
        gate.countDown()
        assertEquals(FilesJobs.DONE, awaitEnd(pc.jobs, id).state)
        assertTrue(Files.exists(tree.home.resolve("dest").resolve("a.txt")))
        pc.slotted.copy(b, dest)
        assertTrue(Files.exists(tree.home.resolve("dest").resolve("b.txt")))
    }

    /** Decisions A.12: a stalled `du` holds the slot, its age shown, and nothing but its end frees it. */
    @Test
    fun aStalledDuHoldsTheSlotWithItsAge_untilItsEnd() {
        val gate = CountDownLatch(1)
        val stalled = object : FilesProvider by tree.files {
            override fun du(path: String): Long {
                assertTrue(gate.await(10, TimeUnit.SECONDS), "the test's own bound on its held du")
                return tree.files.du(path)
            }
        }
        val pc = pc(stalled)
        val dir = tree.dir("big")
        tree.file("big/one.txt", "x".repeat(1_000))
        val id = jobOf(pc.program.act(op(FilesCore.STATS, listOf(dir)), web))
        Thread.sleep(300)
        val running = pc.jobs.current()!!
        assertEquals(id, running.id)
        assertTrue(running.running, "still running: nothing frees a stalled job")
        val desc = pc.scopes.describe("desc/files")!!
        val job = (desc["jobs"] as kotlinx.serialization.json.JsonArray).single().jsonObject
        assertEquals("running", job["state"]!!.jsonPrimitive.content)
        assertNotNull(job["startedAt"]!!.jsonPrimitive.content.toLongOrNull(), "the job's age is read from its start")
        val refused = pc.program.act(op(FilesCore.MKDIR, listOf(tree.home.toString()), buildJsonObject { put("name", "n") }, k = 2), web)
        assertTrue(refused is ProgramResult.Refused && refused.reason.contains("Stats 'big' is running"), "$refused")
        gate.countDown()
        val end = awaitEnd(pc.jobs, id)
        assertEquals(FilesJobs.DONE, end.state)
        assertEquals(1_000L, (end.answer as JsonObject)["size"]!!.jsonPrimitive.content.toLong(), "the facts are the job's answer")
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
