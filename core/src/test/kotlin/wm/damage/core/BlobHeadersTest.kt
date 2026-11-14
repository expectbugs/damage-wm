package wm.damage.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import wm.damage.core.app.Levels
import wm.damage.core.content.LocalContent
import wm.damage.core.web.Access
import wm.damage.core.web.Blobs
import wm.damage.core.web.ReaderBlobs
import wm.damage.core.windows.reader.EpubDoc
import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.reader.ReaderDocs

/*
 * Build S1-1, package P6 (`CONTRACT.md` §9.4, §10.5): every blob answer is `nosniff` and `sandbox`, a type outside the
 * short inline list (SVG among them) is a download, one range is served as asked and a range outside the blob is 416,
 * the blob's minimum level is checked against the device's, and Reader's image ids name the book's own images.
 */
class BlobHeadersTest {

    private val png = ByteArray(40) { it.toByte() }

    private fun rig() = WebRig(blobs = { id ->
        when (id) {
            "img" -> WebRig.blob("image/png", png, name = "pic.png")
            "svg" -> WebRig.blob("image/svg+xml", "<svg onload=\"x()\"/>".toByteArray(), name = "pic.svg")
            "bin" -> WebRig.blob("application/octet-stream", byteArrayOf(1, 2), name = "a b.bin")
            "ctl" -> WebRig.blob("image/png", png, min = Levels.CONTROL)
            "big" -> throw wm.damage.core.web.BlobTooLarge("image 3 of book f00d is 40000000 bytes, past the 33554432 this listener serves")
            "bad" -> throw java.util.zip.ZipException("invalid entry compressed size (fixture)")
            else -> null
        }
    })

    @Test
    fun everyBlobAnswerIsSandboxedAndNotSniffable() = rig().use { r ->
        val (_, secret) = r.approved(Levels.VIEW)
        val c = mapOf("Cookie" to "${Access.COOKIE}=$secret")
        for (id in listOf("img", "svg", "bin")) {
            val got = http(r.port, "GET", "/blob/$id", c)
            assertEquals(200, got.code, id)
            assertEquals("nosniff", got.header("x-content-type-options"), "$id: nosniff")
            assertEquals("sandbox", got.header("content-security-policy"), "$id: sandbox")
        }
        val img = http(r.port, "GET", "/blob/img", c)
        assertNull(img.header("content-disposition"), "an image on the list is shown inline")
        assertContentEquals(png, img.body)
        for (id in listOf("svg", "bin")) {
            val d = http(r.port, "GET", "/blob/$id", c).header("content-disposition")
            assertNotNull(d, "$id is a download")
            assertTrue(d.startsWith("attachment"), "$id: $d")
        }
        assertEquals(404, http(r.port, "GET", "/blob/none", c).code)
    }

    /** Fix round 1, ruling 2.3 (8): a blob the PC cannot serve is answered with its status and the reason, and said
     *  in the log — never a request ended with no answer (the page showed a broken image with no reason). */
    @Test
    fun aBlobThatCannotBeServedIsAnsweredWithItsStatusAndSaid() = rig().use { r ->
        val (_, secret) = r.approved(Levels.VIEW)
        val c = mapOf("Cookie" to "${Access.COOKIE}=$secret")
        val big = http(r.port, "GET", "/blob/big", c)
        assertEquals(413, big.code, big.text)
        assertTrue("past the" in big.text, big.text)
        val bad = http(r.port, "GET", "/blob/bad", c)
        assertEquals(500, bad.code, bad.text)
        assertTrue("could not be read" in bad.text && "ZipException" in bad.text, bad.text)
        assertTrue(recentLog().any { "blob big refused" in it } && recentLog().any { "blob bad could not be read" in it },
            "both are said in the log")
    }

    @Test
    fun oneRangeIsServedAndARangeOutsideIs416() = rig().use { r ->
        val (_, secret) = r.approved(Levels.VIEW)
        val c = mapOf("Cookie" to "${Access.COOKIE}=$secret")
        val part = http(r.port, "GET", "/blob/img", c + ("Range" to "bytes=2-5"))
        assertEquals(206, part.code)
        assertEquals("bytes 2-5/40", part.header("content-range"))
        assertContentEquals(png.copyOfRange(2, 6), part.body)
        val tail = http(r.port, "GET", "/blob/img", c + ("Range" to "bytes=-4"))
        assertContentEquals(png.copyOfRange(36, 40), tail.body)
        val open = http(r.port, "GET", "/blob/img", c + ("Range" to "bytes=30-"))
        assertEquals("bytes 30-39/40", open.header("content-range"))
        val out = http(r.port, "GET", "/blob/img", c + ("Range" to "bytes=40-"))
        assertEquals(416, out.code)
        assertEquals("bytes */40", out.header("content-range"))
        assertEquals("nosniff", out.header("x-content-type-options"))
        assertEquals(200, http(r.port, "GET", "/blob/img", c + ("Range" to "bytes=0-1,4-5")).code, "several ranges: whole")
        assertEquals(Pair(0L, 39L), Blobs.parseRange("bytes=0-99", 40), "a last byte past the end is the end")
    }

    @Test
    fun aBlobAboveTheDevicesLevelIsRefused() = rig().use { r ->
        val (_, view) = r.approved(Levels.VIEW, name = "view page")
        val (_, control) = r.approved(Levels.CONTROL, name = "control page")
        assertEquals(403, http(r.port, "GET", "/blob/ctl", mapOf("Cookie" to "${Access.COOKIE}=$view")).code)
        assertEquals(200, http(r.port, "GET", "/blob/ctl", mapOf("Cookie" to "${Access.COOKIE}=$control")).code)
    }

    @Test
    fun readersImageIdsNameTheBooksOwnImages() {
        val dir = Files.createTempDirectory("damage-blobs")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pic = ByteArray(64) { (it * 3).toByte() }
            val book = EpubFixture.write(dir.resolve("books/a.epub"), listOf("c1.xhtml" to "<p>One</p><img src=\"pic.png\"/><p>Two</p>"),
                images = mapOf("pic.png" to pic))
            val core = ReaderCore(LocalContent(dir.resolve("books")), scope)
            core.refreshLibrary()
            eventually("the shelf is read") { core.library.isNotEmpty() }
            val id = LocalContent.idFor(book)
            val blobs = ReaderBlobs(core, ReaderDocs(core::pathOf))
            val b = blobs.resolve(EpubDoc.blobId(id, 0))
            assertNotNull(b, "image 0 of the book")
            assertContentEquals(pic, b.bytes)
            assertEquals("image/png", b.type)
            assertEquals(Levels.VIEW, b.minLevel)
            assertNull(blobs.resolve(EpubDoc.blobId(id, 7)), "no such image")
            assertNull(blobs.resolve("reader.0123.0"), "no such book")
            assertNull(blobs.resolve("../a.epub"), "not a blob id")
            // ruling 2.3 (8): past the size the listener serves, the reason the listener answers 413 with
            val tooLarge = runCatching { ReaderBlobs(core, ReaderDocs(core::pathOf), maxBytes = 10).resolve(EpubDoc.blobId(id, 0)) }
            assertTrue(tooLarge.exceptionOrNull() is wm.damage.core.web.BlobTooLarge, "refused, never cut: $tooLarge")
        } finally {
            scope.cancel()
            dir.toFile().deleteRecursively()
        }
    }
}
