package wm.damage.core

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.app.Levels
import wm.damage.core.web.Access
import wm.damage.core.web.Blob
import wm.damage.core.web.BlobBody
import wm.damage.core.web.BlobSource
import wm.damage.core.web.BlobSources
import wm.damage.core.web.FileBlob
import wm.damage.core.web.ReaderBlobs

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.4 "Blobs", §5 V5; `CONTRACT.md` §9.4): the blob registry by id prefix, a file
 * streamed from disk with Range and no size cap, `?dl=1` a download whatever the type, `?r=` a rendition its source
 * makes. A real web listener on loopback port 0 (`WebRig`), made-up files in a temp directory.
 */
class FileBlobTest {
    private val dir: Path = Files.createTempDirectory("damage-fileblob")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val pattern = ByteArray(100_000) { (it % 251).toByte() }
    private val small: Path = Files.write(dir.resolve("pattern.bin"), pattern)
    private val picture: Path = Files.write(dir.resolve("pic.png"), ByteArray(300) { 7 })

    /** A file past the 32 MiB Reader's in-memory blobs stop at, sparse, with a marker in its last bytes. */
    private val big: Path = dir.resolve("big.mkv").also { p ->
        RandomAccessFile(p.toFile(), "rw").use { f ->
            f.setLength(BIG)
            f.seek(BIG - 4)
            f.write(byteArrayOf(1, 2, 3, 4))
        }
    }

    private val files = object : BlobSource {
        override fun resolve(id: String): BlobBody? = when (id) {
            "f.big" -> FileBlob(big, "video/x-matroska", Levels.FULL, "big.mkv")
            "f.pattern" -> FileBlob(small, "application/octet-stream", Levels.FULL, "pattern.bin")
            "f.pic" -> FileBlob(picture, "image/png", Levels.FULL, "pic.png")
            else -> null
        }

        override fun rendition(id: String, r: String): BlobBody? =
            if (id == "f.pic" && r == "thumb:320") Blob("image/jpeg", byteArrayOf(9, 9, 9), Levels.FULL, "pic.jpg") else null
    }

    private fun rig() = WebRig(blobs = BlobSources(mapOf("f." to files, ReaderBlobs.PREFIX to BlobSource { id ->
        if (id == "reader.ab.0") WebRig.blob("image/png", byteArrayOf(5), Levels.VIEW, "a.png") else null
    })))

    private fun WebRig.cookie(level: String = Levels.FULL) = mapOf("Cookie" to "${Access.COOKIE}=${approved(level).second}")

    /** Pin (the spec's Q2: "a file past the old 32 MiB cap is refused 413"): a file blob is streamed whole, however
     *  large, never held in memory under Reader's cap. */
    @Test
    fun aFilePastTheOldCapIsStreamedWhole() = rig().use { r ->
        val got = http(r.port, "GET", "/blob/f.big", r.cookie())
        assertEquals(200, got.code, got.text.take(200))
        assertEquals(BIG, got.body.size.toLong())
        assertEquals("$BIG", got.header("content-length"))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), got.body.copyOfRange(got.body.size - 4, got.body.size))
        assertTrue(got.header("content-disposition") != null, "a video type outside the inline list is a download")
    }

    /** Pin (the spec's Q2: "a Range of a file answers the wrong bytes"). */
    @Test
    fun aRangeOfAFileAnswersThoseBytes() = rig().use { r ->
        val c = r.cookie()
        val mid = http(r.port, "GET", "/blob/f.pattern", c + ("Range" to "bytes=1000-1999"))
        assertEquals(206, mid.code)
        assertEquals("bytes 1000-1999/100000", mid.header("content-range"))
        assertContentEquals(pattern.copyOfRange(1000, 2000), mid.body)
        val tail = http(r.port, "GET", "/blob/f.pattern", c + ("Range" to "bytes=-10"))
        assertContentEquals(pattern.copyOfRange(99_990, 100_000), tail.body)
        val open = http(r.port, "GET", "/blob/f.pattern", c + ("Range" to "bytes=99000-"))
        assertContentEquals(pattern.copyOfRange(99_000, 100_000), open.body)
        val outside = http(r.port, "GET", "/blob/f.pattern", c + ("Range" to "bytes=100000-100010"))
        assertEquals(416, outside.code)
        assertEquals("bytes */100000", outside.header("content-range"))
        assertContentEquals(pattern, http(r.port, "GET", "/blob/f.pattern", c).body)
    }

    /** Pin (the spec's Q2: "&dl=1 serves an inline type inline"). */
    @Test
    fun aDownloadIsAnAttachmentWhateverItsType() = rig().use { r ->
        val c = r.cookie()
        val shown = http(r.port, "GET", "/blob/f.pic", c)
        assertNull(shown.header("content-disposition"), "an image on the inline list is shown inline")
        val dl = http(r.port, "GET", "/blob/f.pic?dl=1", c)
        assertEquals(200, dl.code)
        val d = dl.header("content-disposition")
        assertNotNull(d, "?dl=1 is a download")
        assertTrue(d.startsWith("attachment") && "pic.png" in d, d)
        assertEquals("sandbox", dl.header("content-security-policy"))
        assertEquals("nosniff", dl.header("x-content-type-options"))
    }

    @Test
    fun aRenditionIsMadeByItsSourceAndAnUnknownOneIsSaid() = rig().use { r ->
        val c = r.cookie()
        val thumb = http(r.port, "GET", "/blob/f.pic?r=thumb%3A320", c)
        assertEquals(200, thumb.code)
        assertEquals("image/jpeg", thumb.header("content-type"))
        assertContentEquals(byteArrayOf(9, 9, 9), thumb.body)
        val none = http(r.port, "GET", "/blob/f.pic?r=pdf%3A1%3A800", c)
        assertEquals(404, none.code)
        assertEquals("no rendition 'pdf:1:800' of this blob", none.text)
        val both = http(r.port, "GET", "/blob/f.pic?r=thumb%3A320&dl=1", c)
        assertTrue(both.header("content-disposition")!!.startsWith("attachment"), "a rendition can be a download too")
    }

    @Test
    fun theRegistryRoutesByPrefixAndChecksTheBlobsLevel() = rig().use { r ->
        val full = r.cookie()
        assertEquals(200, http(r.port, "GET", "/blob/reader.ab.0", full).code, "Reader's prefix")
        assertEquals(404, http(r.port, "GET", "/blob/x.thing", full).code, "a prefix nothing serves")
        assertEquals(404, http(r.port, "GET", "/blob/f.none", full).code)
        val view = r.cookie(Levels.VIEW)
        val refused = http(r.port, "GET", "/blob/f.pattern", view)
        assertEquals(403, refused.code, "a file at full asked by a view device")
        assertTrue("needs the level full" in refused.text, refused.text)
        assertEquals(200, http(r.port, "GET", "/blob/reader.ab.0", view).code)
    }

    private companion object {
        const val BIG = (32L shl 20) + 4_096
    }
}
