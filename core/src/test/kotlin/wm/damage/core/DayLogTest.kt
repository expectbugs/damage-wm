package wm.damage.core

import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import wm.damage.core.replica.ReplicaServer
import wm.damage.core.util.DayLog
import wm.damage.core.util.Log

/**
 * Build Q (L, Adam 2026-11-17): the phone's log kept 10 days in day files. The in-memory 4,000 lines were about a
 * day deep: the Music incident of 2026-11-16 had rotated out when it was read a day later.
 */
class DayLogTest {

    private val dir: Path = Files.createTempDirectory("daylog-test")
    private val at = LocalDateTime.of(2026, 11, 17, 20, 45, 1, 123_000_000)

    @AfterTest
    fun cleanup() { dir.toFile().deleteRecursively() }

    private fun file(day: LocalDateTime = at): Path = dir.resolve(DayLog.nameOf(day.toLocalDate()))
    private fun text(p: Path): String = if (Files.isRegularFile(p)) Files.readString(p) else ""

    /** ONE evaluation per wait (§27.6). */
    private fun until(what: String, cond: () -> Boolean) = runBlocking { waitUntil(10_000, 10, { what }) { cond() } }

    @Test
    fun aLineLandsInItsDaysFileWithItsDateAndATokenNeverDoes() {
        var now = at
        val log = DayLog(dir, now = { now })
        log.log(Log.Level.INFO, "music", "hello")
        log.log(Log.Level.WARN, "setup", "link http://phone:1/?token=SECRET-TOKEN-77&x=1 and token=OTHER")
        now = at.plusDays(1).withHour(0).withMinute(0).withSecond(5)
        log.log(Log.Level.INFO, "music", "after midnight")
        until("the next day's line written") { text(file(now)).contains("after midnight") }
        val day1 = text(file(at))
        assertTrue(day1.startsWith("2026-11-17 20:45:01.123 I music: hello\n"), day1)
        assertTrue(day1.contains("token=***&x=1 and token=***"), day1)
        assertFalse(day1.contains("SECRET-TOKEN-77") || day1.contains("OTHER"), "a token value reached the file: $day1")
        assertEquals("2026-11-18 00:00:05.123 I music: after midnight\n", text(file(now)))
    }

    @Test
    fun aFile11DaysOldIsRemovedAndOne9DaysOldIsKept() {
        val old = file(at.minusDays(11)).also { Files.writeString(it, "old\n") }
        val ten = file(at.minusDays(10)).also { Files.writeString(it, "ten\n") }
        val nine = file(at.minusDays(9)).also { Files.writeString(it, "nine\n") }
        val other = dir.resolve("log-notes.txt").also { Files.writeString(it, "not a day file\n") }
        val log = DayLog(dir, now = { at })
        log.log(Log.Level.INFO, "t", "started")
        until("the first line written (the start's removal runs before it)") { text(file()).contains("started") }
        assertFalse(Files.exists(old), "11 days old: removed")
        assertTrue(Files.exists(ten) && Files.exists(nine), "10 and 9 days old: kept")
        assertTrue(Files.exists(other), "only day files are ever removed")
    }

    @Test
    fun aSlowWriterDoesNotDelayTheCallerAndWhatItDropsIsCounted() {
        val gate = CountDownLatch(1)
        val log = DayLog(dir, now = { at }, queueLines = 50, beforeWrite = { gate.await() })
        val done = CountDownLatch(1)
        val caller = Thread {
            for (i in 1..500) log.log(Log.Level.INFO, "t", "line $i")
            done.countDown()
        }
        try {
            caller.start()
            until("the caller's 500 lines returned while the writer is held") { done.count == 0L }
        } finally {
            gate.countDown()
        }
        until("the dropped count written") { text(file()).contains("not kept: the queue of 50 lines was full") }
        val body = text(file())
        val kept = body.lines().count { Regex(".* I t: line \\d+").matches(it) }
        val droppedN = Regex("(\\d+) line\\(s\\) not kept").find(body)!!.groupValues[1].toInt()
        assertEquals(500, kept + droppedN, "every line written or counted (kept $kept, dropped $droppedN)")
    }

    // ------------------------------------------------------------------ the routes (L4), the in-process replica

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Reply(val status: String, val body: String)

    private fun get(port: Int, path: String): Reply = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 20_000        // a bound on the TEST only
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); s.getOutputStream().flush()
        val inp = DataInputStream(s.getInputStream().buffered())
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) break; sb.append(b.toChar()) }
        Reply(sb.lines().first(), String(inp.readBytes(), Charsets.UTF_8))
    }

    @Test
    fun theDayFilesAreListedAndServedLikeTheJournal() {
        val d1 = file(at.minusDays(1)).also { Files.writeString(it, "a\nb\nc\n") }
        file().also { Files.writeString(it, "today\n") }
        Files.writeString(dir.resolve("state.json"), "the store's own bytes")
        Log.i("daylog-test", "an in-memory line")
        val port = freePort()
        val server = ReplicaServer(port, "tok", { null }, { ReplicaServer.Status() }, onInput = {}, logDir = { dir })
        server.start()
        try {
            val list = get(port, "/logs?token=tok")
            assertEquals("HTTP/1.1 200 OK", list.status)
            val files = Json.parseToJsonElement(list.body).jsonObject["files"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf(d1.fileName.toString(), file().fileName.toString()), files.map { it["name"]!!.jsonPrimitive.content },
                "the day files, oldest first, nothing else")
            assertEquals(6L, files[0]["bytes"]!!.jsonPrimitive.long)
            assertEquals("a\nb\nc\n", get(port, "/log?token=tok&name=${d1.fileName}").body, "one day file by its name")
            assertEquals("c\n", get(port, "/log?token=tok&name=${d1.fileName}&tail=3").body, "tail in bytes, whole lines")
            assertTrue(get(port, "/log?token=tok&tail=50").body.contains("daylog-test: an in-memory line"),
                "no name: the in-memory lines, as before")
            for (bad in listOf("state.json", "..%2Fstate.json", "log-2026111.txt", "log-20261117.txt.bak"))
                assertEquals("HTTP/1.1 400 Bad Request", get(port, "/log?token=tok&name=$bad").status, bad)
            assertEquals("HTTP/1.1 404 Not Found", get(port, "/log?token=tok&name=log-20200101.txt").status)
            assertEquals("HTTP/1.1 403 Forbidden", get(port, "/logs?token=no").status)
            assertEquals("HTTP/1.1 403 Forbidden", get(port, "/log?token=no&name=${d1.fileName}").status)
        } finally {
            server.close()
        }
        val none = freePort()
        val bare = ReplicaServer(none, "tok", { null }, { ReplicaServer.Status() }, onInput = {})
        bare.start()
        try {
            assertEquals("HTTP/1.1 404 Not Found", get(none, "/logs?token=tok").status, "a host with no day logs says so")
        } finally {
            bare.close()
        }
        assertNotNull(DayLog.dayOf(d1.fileName.toString()))
    }
}
