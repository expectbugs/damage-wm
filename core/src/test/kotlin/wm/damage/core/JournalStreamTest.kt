package wm.damage.core

import java.io.DataInputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import wm.damage.core.replica.ReplicaServer

/**
 * `HANDOFF.md` §71 (2026-11-04): `GET /journal` streams the file and never holds it. The phone's
 * journal is append-only and outgrew the APK's heap. A whole-file read raised an OutOfMemoryError
 * that `serve`'s `Exception` catch did not reach, and on Android an Error that escapes a thread
 * ends the process: that happened twice that morning, and the glasses' session went with it.
 *
 * The big files here are SPARSE (no disk blocks, no memory) and larger than any array can hold, so
 * the pins do not depend on the test JVM's heap: on the unfixed tree each big request ends with no
 * response at all.
 */
class JournalStreamTest {
    private val dir: Path = Files.createTempDirectory("journal-stream")

    @AfterTest fun clean() { dir.toFile().deleteRecursively() }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Past Int.MAX_VALUE (no array holds it) and past twice this JVM's heap. */
    private fun bigSize(): Long = maxOf(5L shl 29, 2 * Runtime.getRuntime().maxMemory() + (1L shl 30))

    /** A sparse file of [size] bytes with [at]'s strings written at their offsets. */
    private fun sparse(size: Long, vararg at: Pair<Long, String>): Path {
        val p = dir.resolve("journal.jsonl")
        RandomAccessFile(p.toFile(), "rw").use { f ->
            f.setLength(size)
            for ((off, s) in at) { f.seek(off); f.write(s.toByteArray(Charsets.UTF_8)) }
        }
        return p
    }

    private class Head(val status: String, val headers: Map<String, String>)

    /** The status line and headers, the stream left at the body; null when the
     *  connection ended before a whole header arrived. */
    private fun readHead(inp: DataInputStream): Head? {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) return null
            sb.append(b.toChar())
        }
        val lines = sb.toString().trimEnd().split("\r\n")
        return Head(lines[0], lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() })
    }

    private fun <T> withJournal(p: Path, body: (Int) -> T): T {
        val port = freePort()
        val server = ReplicaServer(port, "tok", { null }, { ReplicaServer.Status() }, onInput = {}, journalPath = { p })
        server.start()
        try { return body(port) } finally { server.close() }
    }

    private fun get(port: Int, path: String): Socket = Socket("127.0.0.1", port).apply {
        soTimeout = 20_000          // a bound on the TEST only
        getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        getOutputStream().flush()
    }

    @Test
    fun theWholeJournalIsStreamedNeverHeld() {
        val size = bigSize()
        val p = sparse(size, 0L to "{\"first\":1}\n")
        withJournal(p) { port ->
            get(port, "/journal?token=tok").use { s ->
                val inp = DataInputStream(s.getInputStream().buffered())
                val h = assertNotNull(readHead(inp), "a response, not an ended connection: no array holds $size B")
                assertEquals("HTTP/1.1 200 OK", h.status)
                assertEquals(size, h.headers["content-length"]?.toLong(), "the whole file is promised")
                val first = ByteArray(64 * 1024)
                inp.readFully(first)
                assertEquals("{\"first\":1}\n", String(first, 0, 12, Charsets.UTF_8), "the body is the file from its start")
                assertTrue((12 until first.size).all { first[it] == 0.toByte() }, "then the file's own (sparse) bytes")
            }
        }
    }

    @Test
    fun aTailBeyondAnyArrayStartsAtItsFirstWholeLine() {
        val size = bigSize()
        val tail = size - (1L shl 28)                   // > Int.MAX_VALUE
        val from = size - tail                          // 256 MiB in
        // a line straddles the cut; the line after it is the first whole one
        val p = sparse(size, (from - 5) to "straddle\n{\"whole\":1}\n")
        val start = from - 5 + "straddle\n".length
        withJournal(p) { port ->
            get(port, "/journal?token=tok&tail=$tail").use { s ->
                val inp = DataInputStream(s.getInputStream().buffered())
                val h = assertNotNull(readHead(inp), "a response, not an ended connection")
                assertEquals("HTTP/1.1 200 OK", h.status)
                assertEquals(size - start, h.headers["content-length"]?.toLong(), "from the first whole line to the end")
                val b = ByteArray(12)
                inp.readFully(b)
                assertEquals("{\"whole\":1}\n", String(b, Charsets.UTF_8))
            }
        }
    }

    /** The cut on a small file: every whole line that starts inside the tail, and
     *  exactly the bytes the header promised. */
    @Test
    fun theTailKeepsEveryWholeLineThatStartsInsideIt() {
        val p = dir.resolve("small.jsonl").also { Files.writeString(it, "aaaa\nbbbb\ncccc\n") }
        withJournal(p) { port ->
            fun body(q: String): String = get(port, "/journal?token=tok$q").use { s ->
                val inp = DataInputStream(s.getInputStream().buffered())
                val h = assertNotNull(readHead(inp), "a response for '$q'")
                assertEquals("HTTP/1.1 200 OK", h.status, q)
                val b = inp.readBytes()
                assertEquals(h.headers["content-length"]?.toLong(), b.size.toLong(), "the length promised is the length sent ('$q')")
                String(b, Charsets.UTF_8)
            }
            assertEquals("aaaa\nbbbb\ncccc\n", body(""))
            assertEquals("aaaa\nbbbb\ncccc\n", body("&tail=0"), "no tail is the whole file")
            assertEquals("aaaa\nbbbb\ncccc\n", body("&tail=15"))
            assertEquals("aaaa\nbbbb\ncccc\n", body("&tail=99"))
            assertEquals("bbbb\ncccc\n", body("&tail=10"), "a cut ON a line start keeps that line")
            assertEquals("cccc\n", body("&tail=8"), "a cut inside a line starts at the next")
            assertEquals("", body("&tail=3"), "no line starts inside the tail")
        }
        val q = dir.resolve("open.jsonl").also { Files.writeString(it, "aaaa\nbb") }
        withJournal(q) { port ->
            fun body(t: Int): String = get(port, "/journal?token=tok&tail=$t").use { s ->
                val inp = DataInputStream(s.getInputStream().buffered())
                assertNotNull(readHead(inp))
                String(inp.readBytes(), Charsets.UTF_8)
            }
            assertEquals("bb", body(2), "a last line still being written is a line that starts there")
            assertEquals("", body(1))
        }
    }

    /** Chunk boundaries (64 KiB): a cut whose first newline lies chunks past it, and a
     *  multi-chunk file read whole — every byte as on disk, the length as promised. */
    @Test
    fun theStreamCrossesChunksByteForByte() {
        val long = "x".repeat(200 * 1024)            // one line, no newline: the scan crosses chunks
        val text = buildString {
            append(long).append('\n')
            for (i in 0 until 20_000) append("{\"n\":").append(i).append("}\n")
        }
        val p = dir.resolve("chunks.jsonl").also { Files.writeString(it, text) }
        val bytes = Files.readAllBytes(p)
        withJournal(p) { port ->
            fun body(q: String): ByteArray = get(port, "/journal?token=tok$q").use { s ->
                val inp = DataInputStream(s.getInputStream().buffered())
                val h = assertNotNull(readHead(inp), "a response for '$q'")
                val b = inp.readBytes()
                assertEquals(h.headers["content-length"]?.toLong(), b.size.toLong(), "the length promised is the length sent ('$q')")
                b
            }
            assertTrue(bytes.contentEquals(body("")), "the whole file, byte for byte, across chunks")
            val cut = body("&tail=${bytes.size - 1000L}")   // the cut lands 1,000 B into the long line
            assertTrue(bytes.copyOfRange(long.length + 1, bytes.size).contentEquals(cut),
                "the cut starts just past the long line's newline, chunks after the cut")
        }
    }
}
