package wm.damage.core

import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wm.damage.core.app.Levels
import wm.damage.core.web.Access
import wm.damage.core.web.Uploads

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.4 "Uploads", §5 V4; `CONTRACT.md` §9.4): `PUT /blob/upload/<id>`. Only a
 * whole body is given its name; a file already there is never replaced (L1: the upload takes the first free numbered
 * name, also when the name appeared during the upload; the temp name holds no name, so a long one fits); the space is
 * read before a byte of the body; a cut-off body leaves
 * nothing; the id is single use and bound to its device; `uploads.jsonl` lets a start delete what a stopped process
 * left; a ticket whose connection ends before its body is cancelled. A real web listener on loopback port 0
 * (`WebRig`), made-up files in temp directories.
 */
class UploadTest {
    private val data: Path = Files.createTempDirectory("damage-upload-data")
    private val dest: Path = Files.createTempDirectory("damage-upload-dest")

    @AfterTest
    fun cleanUp() {
        data.toFile().deleteRecursively()
        dest.toFile().deleteRecursively()
    }

    private val body = ByteArray(200_000) { (it * 31 % 253).toByte() }
    private val ends = CopyOnWriteArrayList<Uploads.End>()

    private fun rig(usable: (Path) -> Long = { Long.MAX_VALUE }, uploads: Uploads = Uploads(data, usable)) =
        WebRig(uploads = uploads)

    /** A device approved at full, its id and secret. */
    private fun WebRig.device(): Pair<String, String> = approved(Levels.FULL).let { (row, secret) -> row.id to secret }

    private fun WebRig.mint(device: String, name: String = "survey.bin", size: Long = body.size.toLong(), conn: Long = 1) =
        uploads!!.mint(device, conn, dest, name, size) { ends += it }

    /** A PUT on its own connection: the head, then [send] bytes of [bytes] (all of them by default), then the answer.
     *  [length] is the `Content-Length` sent (none when null); [close] closes the socket after the bytes, unanswered. */
    private fun put(port: Int, id: String, secret: String, bytes: ByteArray = body, send: Int = bytes.size,
                    length: Long? = bytes.size.toLong(), close: Boolean = false, waitMs: Int = 10_000): Resp? {
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = waitMs
            val head = StringBuilder("PUT /blob/upload/$id HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Cookie: ${Access.COOKIE}=$secret\r\n")
            length?.let { head.append("Content-Length: $it\r\n") }
            head.append("\r\n")
            val out = s.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(bytes, 0, send)
            out.flush()
            if (close) return null
            return try {
                readResp(DataInputStream(s.getInputStream().buffered()))
            } catch (e: SocketTimeoutException) {
                null
            }
        }
    }

    private fun leftovers() = Files.list(dest).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".part") }.toList() }

    private fun listed() = Files.readAllLines(data.resolve(Uploads.LIST_FILE)).filter { it.isNotBlank() }

    @Test
    fun aWholeBodyIsPlacedUnderItsName() = rig().use { r ->
        val (device, secret) = r.device()
        val id = r.mint(device)
        val got = put(r.port, id, secret)!!
        assertEquals(201, got.code, got.text)
        assertEquals("{\"name\":\"survey.bin\",\"bytes\":200000}", got.text)
        assertContentEquals(body, Files.readAllBytes(dest.resolve("survey.bin")))
        assertEquals(emptyList(), leftovers())
        assertEquals(emptyList(), listed(), "uploads.jsonl lists nothing once it is placed")
        assertEquals(listOf<Uploads.End>(Uploads.End.Done(dest.resolve("survey.bin"), body.size.toLong())), ends.toList())
        // single use
        assertEquals(404, put(r.port, id, secret, send = 0)!!.code)
    }

    /** Pin (the spec's Q2: "a cut-off upload leaves a file under the real name"). */
    @Test
    fun aCutOffBodyLeavesNothing() = rig().use { r ->
        val (device, secret) = r.device()
        val id = r.mint(device)
        put(r.port, id, secret, send = body.size / 2, close = true)
        eventually("the cut-off upload ended") { ends.isNotEmpty() }
        assertFalse(Files.exists(dest.resolve("survey.bin")), "nothing under the real name")
        assertEquals(emptyList(), leftovers(), "the temp file was deleted")
        val e = ends.single()
        assertTrue(e is Uploads.End.Failed && "ended after 100000 of 200000 bytes" in e.reason, "$e")
        assertEquals(emptyList(), listed())
    }

    /** Pin (L1, "an upload never replaces"): a name that appears while the body arrives is never replaced — the link
     *  refuses it and the upload takes the next free numbered name, which the answer names. */
    @Test
    fun aNameThatAppearsDuringTheUploadIsNeverReplaced() {
        val mine = "the made-up file already there".toByteArray()
        // the space is read before the body: the file appears then
        rig(usable = { folder -> Files.write(folder.resolve("survey.bin"), mine); Long.MAX_VALUE }).use { r ->
            val (device, secret) = r.device()
            val got = put(r.port, r.mint(device), secret)!!
            assertEquals(201, got.code, got.text)
            assertEquals("{\"name\":\"survey (1).bin\",\"bytes\":200000}", got.text)
            assertContentEquals(mine, Files.readAllBytes(dest.resolve("survey.bin")), "the file there is untouched")
            assertContentEquals(body, Files.readAllBytes(dest.resolve("survey (1).bin")))
            assertEquals(listOf<Uploads.End>(Uploads.End.Done(dest.resolve("survey (1).bin"), body.size.toLong())), ends.toList())
            assertEquals(emptyList(), leftovers())
        }
    }

    /** Pin (L1): names already there are kept and the upload takes the first free numbered name. */
    @Test
    fun anExistingNameGetsTheFirstFreeNumberedName() = rig().use { r ->
        Files.write(dest.resolve("survey.bin"), byteArrayOf(1))
        Files.write(dest.resolve("survey (1).bin"), byteArrayOf(2))
        val (device, secret) = r.device()
        val got = put(r.port, r.mint(device), secret)!!
        assertEquals(201, got.code, got.text)
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(dest.resolve("survey.bin")))
        assertContentEquals(byteArrayOf(2), Files.readAllBytes(dest.resolve("survey (1).bin")))
        assertContentEquals(body, Files.readAllBytes(dest.resolve("survey (2).bin")))
        assertEquals(listOf("survey (1).bin", "notes (3)", ".bashrc (1)", "a.tar (2).gz", "same"),
            listOf(Uploads.numbered("survey.bin", 1), Uploads.numbered("notes", 3), Uploads.numbered(".bashrc", 1),
                Uploads.numbered("a.tar.gz", 2), Uploads.numbered("same", 0)))
    }

    /** Pin (L1): the numbered names are tried a bounded number of times; past them nothing is placed or replaced. */
    @Test
    fun theNumberedNamesAreBounded() =
        rig(uploads = Uploads(data, { Long.MAX_VALUE }) { _, d -> throw java.nio.file.FileAlreadyExistsException(d.toString()) }).use { r ->
            val (device, secret) = r.device()
            val got = put(r.port, r.mint(device), secret)!!
            assertEquals(409, got.code, got.text)
            assertTrue("up to (${Uploads.MAX_NUMBER})" in got.text && "nothing was replaced" in got.text, got.text)
            assertEquals(emptyList(), leftovers())
        }

    /** Pin (B-N10): the temp name holds no name, so a name of 250 bytes uploads (beside it the old temp name would pass
     *  the filesystem's 255). */
    @Test
    fun aLongNameUploads() = rig().use { r ->
        val long = "n".repeat(246) + ".bin"
        val (device, secret) = r.device()
        val got = put(r.port, r.mint(device, name = long), secret)!!
        assertEquals(201, got.code, got.text)
        assertContentEquals(body, Files.readAllBytes(dest.resolve(long)))
    }

    /** Pin (the spec's Q2: "a full disk is found after the body"): the space is read before a byte of the body. */
    @Test
    fun aFullDiskIsFoundBeforeTheBody() = rig(usable = { 1_000 }).use { r ->
        val (device, secret) = r.device()
        val got = put(r.port, r.mint(device), secret, send = 0, waitMs = 5_000)
        assertEquals(507, got?.code, "answered without the body: ${got?.text}")
        assertTrue("not enough space" in got!!.text && "1000 are free" in got.text, got.text)
        assertFalse(Files.exists(dest.resolve("survey.bin")))
        assertEquals(emptyList(), leftovers())
        assertTrue(ends.single() is Uploads.End.Failed)
    }

    @Test
    fun theLengthTheDeviceAndTheNameAreChecked() = rig().use { r ->
        val (device, secret) = r.device()
        assertEquals(411, put(r.port, r.mint(device), secret, send = 0, length = null)!!.code, "no Content-Length")
        val short = put(r.port, r.mint(device, size = 10), secret, send = 0)!!
        assertEquals(400, short.code, "a length that is not the op's")
        val (_, other) = r.device()
        assertEquals(403, put(r.port, r.mint(device), other, send = 0)!!.code, "another device's upload")
        // a refusal before the body reaches the uploader that is still sending it (the rest is read and dropped)
        val sending = put(r.port, r.mint(device), other)
        assertEquals(403, sending?.code, "answered, not reset, while the body was still being sent")
        assertEquals(403, http(r.port, "PUT", "/blob/upload/x", body = byteArrayOf(1)).code, "no approved device")
        for (bad in listOf("../escape", "a/b", ".", "..", "")) assertFailsWith<IllegalArgumentException>(bad) { r.mint(device, name = bad) }
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun aStartDeletesWhatAStoppedProcessLeftAndATicketEndsWithItsConnection() {
        // 0.67's temp name (the file's name in it) and L1's
        val part = Files.write(dest.resolve(".survey.bin.damage-upload-0a0b.part"), byteArrayOf(1, 2))
        val part2 = Files.write(dest.resolve(".damage-upload-0c0d.part"), byteArrayOf(1, 2))
        val keep = Files.write(dest.resolve("not-ours.part"), byteArrayOf(3))
        Files.writeString(data.resolve(Uploads.LIST_FILE), "{\"id\":\"0a0b\",\"part\":\"$part\"}\n{\"id\":\"zz\",\"part\":\"$keep\"}\n" +
            "{\"id\":\"0c0d\",\"part\":\"$part2\"}\n")
        val said = ArrayList<String>()
        val u = Uploads(data)
        u.atStart { said += it }
        assertFalse(Files.exists(part) || Files.exists(part2), "the listed temp files were deleted")
        assertTrue(Files.exists(keep), "a line naming no upload's temp file deletes nothing")
        assertEquals(3, said.size, "$said")
        assertTrue(said[0].startsWith("an upload cut off by the last stop was deleted"), said[0])
        assertEquals(emptyList(), Files.readAllLines(data.resolve(Uploads.LIST_FILE)).filter { it.isNotBlank() })
        // a ticket whose connection ends before its body is cancelled, its owner told
        val got = CopyOnWriteArrayList<Uploads.End>()
        u.mint("web-000001", 42, dest, "late.bin", 3) { got += it }
        u.mint("web-000001", 43, dest, "other.bin", 3) { got += it }
        u.connectionEnded(42)
        assertEquals(listOf<Uploads.End>(Uploads.End.Failed("the page's connection ended before the upload began")), got.toList())
    }
}
