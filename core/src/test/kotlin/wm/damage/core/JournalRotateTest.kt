package wm.damage.core

import java.io.DataInputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import wm.damage.core.comp.Journal
import wm.damage.core.replica.ReplicaServer
import wm.damage.core.util.Log

/**
 * `HANDOFF.md` §75.1 (Adam's ruling), APK 0.64: the journal rotates at a process start past 64 MiB,
 * nothing is deleted, the archives are listed and served by name.
 *
 * Every file here is in a temp directory of this test. No test, and nothing these tests call, names
 * the real data directory: `Journal.rotateAtStart` is reached in main code from the two process
 * starts only (`tools/check_apk_wiring.py` holds that).
 *
 * No pin waits: each decides on a return value, a file's bytes, a log line or an HTTP response.
 * Log lines are found by a file name only this test uses (the log is process-wide).
 */
class JournalRotateTest {
    private val dir: Path = Files.createTempDirectory("journal-rotate")
    private val at = LocalDateTime.of(2026, 11, 10, 21, 30, 45)

    @AfterTest fun clean() { dir.toFile().deleteRecursively() }

    /** A live journal name no other test uses, so `Log.recent()` can be read for it. */
    private fun unique(): Path = dir.resolve("j${System.nanoTime()}.jsonl")

    private fun names(): List<String> = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    /** The log lines of [level] about [live]: every line names the live file or an archive of it,
     *  and both carry its stem. */
    private fun said(level: Char, live: Path, text: String): List<String> {
        val stem = live.fileName.toString().removeSuffix(".jsonl")
        return Log.recent().filter { " $level journal: " in it && stem in it && text in it }
    }

    private fun noteOf(line: String): Pair<String, String> {
        val o = Json.parseToJsonElement(line).jsonObject
        assertEquals("note", o["ev"]!!.jsonPrimitive.content, "a note line: $line")
        return o["kind"]!!.jsonPrimitive.content to o["detail"]!!.jsonPrimitive.content
    }

    private fun notes(p: Path): List<Pair<String, String>> = Files.readAllLines(p).filter { it.isNotBlank() }.map(::noteOf)

    @Test
    fun aJournalPastTheLimitIsSetAsideAndAFreshOneStarted() {
        val live = dir.resolve("journal.jsonl")
        val old = "{\"ev\":\"submit\",\"id\":1}\n{\"ev\":\"done\",\"id\":1}\n"
        Files.writeString(live, old)
        val archive = Journal.rotateAtStart(live, limit = 10, now = at)
        assertEquals(dir.resolve("journal-20261110-213045.jsonl"), archive, "set aside under the dated name")
        assertEquals(old, Files.readString(archive!!), "the archive holds the old bytes exactly")
        assertTrue(Files.isRegularFile(live), "a fresh live file exists from the rotation on")
        assertEquals(listOf("journal" to "rotated at this process start: the ${old.length} B before this line are in journal-20261110-213045.jsonl"),
            notes(live), "the fresh file's only line names the archive and its size")
        assertEquals(listOf("journal-20261110-213045.jsonl", "journal.jsonl"), names(), "nothing else was made, nothing deleted")
    }

    @Test
    fun aJournalAtOrUnderTheLimitStays() {
        val live = unique()
        Files.writeString(live, "0123456789")
        assertNull(Journal.rotateAtStart(live, limit = 10, now = at), "exactly the limit is not past it")
        assertEquals(listOf(live.fileName.toString()), names())
        assertEquals("0123456789", Files.readString(live), "the file is as it was")
    }

    @Test
    fun oncePerProcessAndPath() {
        val live = unique()
        Files.writeString(live, "x".repeat(50))
        assertNotNull(Journal.rotateAtStart(live, limit = 10, now = at))
        Files.writeString(live, "y".repeat(50))                 // the fresh file, grown past the limit again
        assertNull(Journal.rotateAtStart(live, limit = 10, now = at.plusHours(1)), "a second call in this process does nothing")
        assertEquals(2, names().size, "still the archive and the live file: ${names()}")
        assertEquals("y".repeat(50), Files.readString(live))
    }

    @Test
    fun aRotationAfterTheJournalWasOpenedIsRefusedAndSaid() {
        val live = unique()
        Files.writeString(live, "x".repeat(50))
        Journal(live).use { it.note("t", "a writer holds the path") }
        val before = Files.readString(live)
        assertNull(Journal.rotateAtStart(live, limit = 10, now = at), "a rename under a writer of this process is refused")
        assertEquals(listOf(live.fileName.toString()), names())
        assertEquals(before, Files.readString(live))
        assertEquals(1, said('W', live, "was opened in this process").size, "and said")
    }

    /** NIT 14: the two sites name a path the same way, however it was spelled. */
    @Test
    fun aPathSpelledTwoWaysIsOnePath() {
        val live = unique()
        Files.writeString(live, "x".repeat(50))
        Files.createDirectories(dir.resolve("sub"))
        val spelled = dir.resolve("sub").resolve("..").resolve(live.fileName.toString())
        Journal(spelled).use { it.note("t", "opened through another spelling") }
        assertNull(Journal.rotateAtStart(live, limit = 10, now = at), "the writer's path and the rotation's are one file")
        assertEquals(listOf(live.fileName.toString(), "sub"), names())
    }

    @Test
    fun aTakenNameIsNeverOverwritten() {
        val live = unique()
        Files.writeString(live, "x".repeat(50))
        val taken = dir.resolve(Journal.archiveName(live, at))
        Files.writeString(taken, "an archive that is already there")
        // the contract the rotation leans on, checked on this JVM: no options = an existing target is refused
        val a = dir.resolve("a.txt").also { Files.writeString(it, "a") }
        assertFailsWith<FileAlreadyExistsException> { Files.move(a, taken) }
        Files.delete(a)

        assertNull(Journal.rotateAtStart(live, limit = 10, now = at), "a taken name is a rotation that did not happen")
        assertEquals("an archive that is already there", Files.readString(taken), "the file under that name is intact")
        assertTrue(Files.readString(live).startsWith("x".repeat(50)), "the live file keeps its bytes")
        assertEquals(1, said('E', live, "could not be set aside as ${taken.fileName}").size, "said as an error")
    }

    @Test
    fun aRenameThatFailsIsSaidAndTheOldFileKeepsGrowing() {
        val live = unique()
        Files.writeString(live, "x".repeat(50) + "\n")
        val r = Journal.rotateAtStart(live, limit = 10, now = at, move = { _, _ -> throw java.io.IOException("the move was refused") })
        assertNull(r)
        val name = live.fileName.toString()
        val e = said('E', live, "the journal is 51 B and could not be set aside as ${Journal.archiveName(live, at)} — it keeps growing")
        assertEquals(1, e.size, "one ERROR line (the phone's sink raises its notice from it)")
        assertTrue("IOException: the move was refused" in e[0], e[0])
        val lines = Files.readAllLines(live)
        assertEquals("x".repeat(50), lines[0], "the old bytes stay")
        assertEquals(listOf("journal" to "rotation FAILED: 51 B, wanted ${Journal.archiveName(live, at)}: IOException: the move was refused"),
            lines.drop(1).map(::noteOf), "and a note in the old file says so")
        Journal(live).use { it.note("t", "still journalling") }
        assertEquals(3, Files.readAllLines(live).size, "and the old file keeps growing")
        assertEquals(listOf(name), names(), "no archive was made")
    }

    /** Open question 6: nothing deletes an archive, so every start says how many there are and
     *  their total. NIT 23: also when no live file exists (a rotation whose fresh file never started). */
    @Test
    fun everyStartSaysTheArchivesCountAndTotal() {
        val live = unique()
        val name = live.fileName.toString()
        Files.writeString(dir.resolve(Journal.archiveName(live, at)), "a".repeat(10))
        Files.writeString(dir.resolve(Journal.archiveName(live, at.plusDays(1))), "b".repeat(20))
        Files.writeString(dir.resolve("other-20261110-213045.jsonl"), "not an archive of this journal")
        assertNull(Journal.rotateAtStart(live, limit = 10, now = at.plusDays(2)), "no live file: nothing to set aside")
        assertEquals(1, said('I', live, "no $name yet; 2 archive(s) beside it, 30 B").size, Log.recent().takeLast(5).toString())
        // R2 item 26: the same count and total for the start's `process` note (both hosts write it)
        assertEquals("2 archive(s) beside it, 30 B", Journal.archivesSaid(live))

        val second = unique()
        val n2 = second.fileName.toString()
        Files.writeString(second, "12345")
        Files.writeString(dir.resolve(Journal.archiveName(second, at)), "c".repeat(7))
        assertNull(Journal.rotateAtStart(second, limit = 10, now = at))
        assertEquals(1, said('I', second, "$n2 is 5 B; 1 archive(s) beside it, 7 B").size, Log.recent().takeLast(5).toString())
    }

    /**
     * It never throws (0.64 P3 verify: the guard had no pin). It is the first thing a process start
     * does on both hosts, and an exception leaving it would end that start, at every start. Here the
     * journal's directory can be entered and not listed: the live file is found and sized, and the
     * read of the archives beside it is refused.
     */
    @Test
    fun aRotationCheckThatCannotFinishSaysSoAndLeavesTheJournal() {
        val sub = Files.createDirectories(dir.resolve("entered-not-listed"))
        val live = sub.resolve("j${System.nanoTime()}.jsonl")
        Files.writeString(live, "x".repeat(50))
        Files.setPosixFilePermissions(sub, java.nio.file.attribute.PosixFilePermissions.fromString("--x------"))
        try {
            // an account that reads past directory permissions cannot make this failure: skipped, not passed
            org.junit.Assume.assumeFalse("the directory is listed whatever its permissions", Files.isReadable(sub))
            assertNull(Journal.rotateAtStart(live, limit = 10, now = at), "nothing was set aside")
            val e = said('E', live, "the rotation check of ${live.fileName} did not finish")
            assertEquals(1, e.size, "one ERROR line says so")
            assertTrue("AccessDeniedException" in e[0], e[0])
        } finally {
            Files.setPosixFilePermissions(sub, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals("x".repeat(50), Files.readString(live), "the journal is as it was")
        assertEquals(listOf(live.fileName.toString()), Files.list(sub).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    /** NIT 17: a note that did not land says so to its caller. */
    @Test
    fun appendNoteSaysWhetherTheLineLanded() {
        val live = unique()
        assertTrue(Journal.appendNote(live, "process", "a line\twith a tab"))
        assertEquals(listOf("process" to "a line\twith a tab"), notes(live))
        val blocker = dir.resolve("a-file").also { Files.writeString(it, "not a directory") }
        val under = blocker.resolve("j${System.nanoTime()}.jsonl")
        assertFalse(Journal.appendNote(under, "process", "nowhere to go"), "the line did not land")
        assertEquals(1, said('E', under, "a process note could not be appended").size)
    }

    // ------------------------------------------------------------------ the routes

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Reply(val status: String, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    private fun readHead(inp: DataInputStream): Pair<String, Map<String, String>>? {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) return null
            sb.append(b.toChar())
        }
        val lines = sb.toString().trimEnd().split("\r\n")
        return lines[0] to lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    }

    private fun open(port: Int, path: String): Socket = Socket("127.0.0.1", port).apply {
        soTimeout = 20_000          // a bound on the TEST only
        getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        getOutputStream().flush()
    }

    private fun get(port: Int, path: String): Reply = open(port, path).use { s ->
        val inp = DataInputStream(s.getInputStream().buffered())
        val h = assertNotNull(readHead(inp), "a response to $path, not an ended connection")
        val body = inp.readBytes()
        assertEquals(h.second["content-length"]?.toLong(), body.size.toLong(), "the length promised is the length sent ($path)")
        Reply(h.first, h.second, body)
    }

    private fun <T> withServer(journal: () -> Path?, body: (Int) -> T): T {
        val port = freePort()
        val server = ReplicaServer(port, "tok", { null }, { ReplicaServer.Status() }, onInput = {}, journalPath = journal)
        server.start()
        try { return body(port) } finally { server.close() }
    }

    @Test
    fun archivesAreListedAndServedByName() {
        val live = dir.resolve("journal.jsonl")
        Files.writeString(live, "{\"live\":1}\n")
        val a1 = dir.resolve(Journal.archiveName(live, at)).also { Files.writeString(it, "aaaa\nbbbb\ncccc\n") }
        val a2 = dir.resolve(Journal.archiveName(live, at.plusDays(1))).also { Files.writeString(it, "second\n") }
        val secret = "the store's own bytes"
        Files.writeString(dir.resolve("state.json"), secret)
        Files.createDirectories(dir.resolve("sub"))
        Files.writeString(dir.resolve("sub").resolve(a1.fileName.toString()), secret)
        withServer({ live }) { port ->
            val list = get(port, "/journals?token=tok")
            assertEquals("HTTP/1.1 200 OK", list.status)
            assertEquals("application/json", list.headers["content-type"])
            val o = Json.parseToJsonElement(list.text).jsonObject
            assertEquals("journal.jsonl", o["live"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(11L, o["live"]!!.jsonObject["bytes"]!!.jsonPrimitive.long)
            val arch = o["archives"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(a1.fileName.toString() to 15L, a2.fileName.toString() to 7L),
                arch.map { it["name"]!!.jsonPrimitive.content to it["bytes"]!!.jsonPrimitive.long }, "oldest first, with their sizes")
            assertEquals(Files.getLastModifiedTime(a1).toMillis(), arch[0]["modified"]!!.jsonPrimitive.long)

            assertEquals("aaaa\nbbbb\ncccc\n", get(port, "/journal?token=tok&name=${a1.fileName}").text, "an archive by its name")
            assertEquals("cccc\n", get(port, "/journal?token=tok&name=${a1.fileName}&tail=8").text, "tail works on an archive as on the live file")
            assertEquals("{\"live\":1}\n", get(port, "/journal?token=tok").text, "no name is the live file, as before")

            assertEquals("HTTP/1.1 403 Forbidden", get(port, "/journals?token=no").status)
            assertEquals("HTTP/1.1 403 Forbidden", get(port, "/journal?token=no&name=${a1.fileName}").status)
            for (bad in listOf("state.json", "../state.json", "sub/${a1.fileName}", "sub%2F${a1.fileName}",
                    "journal.jsonl", "${a1.fileName}/../state.json", "journal-20261110-213045-2.jsonl")) {
                val r = get(port, "/journal?token=tok&name=$bad")
                assertEquals("HTTP/1.1 400 Bad Request", r.status, "name=$bad")
                assertEquals("not a journal archive name", r.text, "name=$bad: nothing of any file in the reply")
            }
            assertEquals("HTTP/1.1 404 Not Found", get(port, "/journal?token=tok&name=journal-20200101-000000.jsonl").status,
                "a well-formed name with no file behind it")
        }
        // NIT 23: archives with no live file are still listed, and say so
        Files.delete(live)
        withServer({ live }) { port ->
            val o = Json.parseToJsonElement(get(port, "/journals?token=tok").text).jsonObject
            assertEquals(JsonNull, o["live"], "no live file is said, not an error")
            assertEquals(2, o["archives"]!!.jsonArray.size)
            assertEquals("HTTP/1.1 404 Not Found", get(port, "/journal?token=tok").status)
        }
        // NIT 23: a host with no journal path at all
        withServer({ null }) { port ->
            assertEquals("HTTP/1.1 404 Not Found", get(port, "/journals?token=tok").status)
            assertEquals("HTTP/1.1 404 Not Found", get(port, "/journal?token=tok&name=${a1.fileName}").status,
                "a name on a host with no journal is not found, not a fault")
        }
    }

    /** An archive is the size the live file was allowed to reach or far past it (the phone's first
     *  one holds everything since August): it goes through the same stream, never an array. */
    @Test
    fun anArchivePastAnyArrayIsStreamed() {
        val live = dir.resolve("journal.jsonl")
        val size = maxOf(5L shl 29, 2 * Runtime.getRuntime().maxMemory() + (1L shl 30))
        val archive = dir.resolve(Journal.archiveName(live, at))
        RandomAccessFile(archive.toFile(), "rw").use { f ->
            f.setLength(size)                                    // sparse: no disk blocks, no memory
            f.seek(0); f.write("{\"first\":1}\n".toByteArray())
            f.seek(size - 12); f.write("\n{\"last\":1}\n".toByteArray())
        }
        withServer({ live }) { port ->
            open(port, "/journal?token=tok&name=${archive.fileName}").use { s ->
                val inp = DataInputStream(s.getInputStream().buffered())
                val h = assertNotNull(readHead(inp), "a response, not an ended connection: no array holds $size B")
                assertEquals("HTTP/1.1 200 OK", h.first)
                assertEquals(size, h.second["content-length"]?.toLong(), "the whole archive is promised")
                val b = ByteArray(12)
                inp.readFully(b)
                assertEquals("{\"first\":1}\n", String(b, Charsets.UTF_8))
            }
            assertEquals("{\"last\":1}\n", get(port, "/journal?token=tok&name=${archive.fileName}&tail=11").text)
        }
    }

    /**
     * A second process that renames the file while a fetch of it is underway (the only way a fetch
     * can meet the rename: in the process that rotates, the rename precedes the server). The stream
     * holds the file open with its length fixed, so the response completes with the OLD file's
     * bytes. This passes on the tree before 0.64 too: it pins `streamJournal` as it is, because
     * the rotation's answer to that question leans on it.
     */
    @Test
    fun aFetchUnderwayWhenTheFileIsRenamedCompletes() {
        val live = dir.resolve("journal.jsonl")
        val size = 256L * 1024 * 1024          // past this machine's largest socket buffers: the server is mid-stream
        RandomAccessFile(live.toFile(), "rw").use { f ->
            f.setLength(size)
            f.seek(0); f.write("{\"old\":1}\n".toByteArray())
            f.seek(size - 10); f.write("{\"end\":1}\n".toByteArray())
        }
        withServer({ live }) { port ->
            open(port, "/journal?token=tok").use { s ->
                val inp = DataInputStream(s.getInputStream().buffered(64 * 1024))
                val h = assertNotNull(readHead(inp))
                assertEquals(size, h.second["content-length"]?.toLong())
                // the header is read, the body is not: the server is held by the socket. Rename under it.
                Files.move(live, dir.resolve(Journal.archiveName(live, at)))
                Files.writeString(live, "{\"fresh\":1}\n")
                val first = ByteArray(10); inp.readFully(first)
                assertEquals("{\"old\":1}\n", String(first, Charsets.UTF_8))
                var left = size - 20
                val buf = ByteArray(1 shl 20)
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    assertTrue(n > 0, "the body ended $left B short of the length promised")
                    left -= n
                }
                val last = ByteArray(10); inp.readFully(last)
                assertEquals("{\"end\":1}\n", String(last, Charsets.UTF_8), "the old file to its end")
                assertEquals(-1, inp.read(), "and nothing of the fresh file after it")
            }
            assertEquals("{\"fresh\":1}\n", get(port, "/journal?token=tok").text, "the next request reads the fresh file")
        }
    }
}
