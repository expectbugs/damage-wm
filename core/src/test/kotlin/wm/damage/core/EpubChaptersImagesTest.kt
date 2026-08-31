package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import wm.damage.core.windows.reader.Epub

/**
 * 2026-08-31: chapters (spine-document boundaries titled from the book's own
 * NCX, offsets in the SAME character space the reading positions use) and
 * ebook images (token paragraphs + captured bytes). Offsets being exact is
 * what the first-open chapter picker and lineAtOffset stand on.
 */
class EpubChaptersImagesTest {

    private fun buildEpub(dir: Path): Path {
        val f = dir.resolve("t.epub")
        ZipOutputStream(Files.newOutputStream(f)).use { z ->
            fun put(name: String, body: ByteArray) {
                z.putNextEntry(ZipEntry(name)); z.write(body); z.closeEntry()
            }
            fun put(name: String, body: String) = put(name, body.toByteArray())
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
            put("OEBPS/content.opf",
                """<package><metadata><dc:title>T</dc:title><dc:creator>A</dc:creator></metadata>
                <manifest>
                  <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
                  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  <item id="i1" href="img/pic.png" media-type="image/png"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
            put("OEBPS/toc.ncx",
                """<ncx><navMap>
                <navPoint><navLabel><text>One</text></navLabel><content src="c1.xhtml"/></navPoint>
                <navPoint><navLabel><text>Two</text></navLabel><content src="c2.xhtml"/></navPoint>
                </navMap></ncx>""")
            put("OEBPS/c1.xhtml",
                """<html><body><p>Alpha beta.</p><img src="img/pic.png"/><p>Gamma.</p></body></html>""")
            put("OEBPS/c2.xhtml", "<html><body><p>Delta epsilon zeta.</p></body></html>")
            put("OEBPS/img/pic.png", byteArrayOf(1, 2, 3, 4))   // raw capture only; decode is the seam's job
        }
        return f
    }

    @Test
    fun chaptersOffsetsTokensAndImages() {
        val tmp = Files.createTempDirectory("damage-epub")
        try {
            val book = Epub.load(buildEpub(tmp))
            assertEquals("T", book.title)
            assertEquals(2, book.chapters.size)
            assertEquals("One", book.chapters[0].title)
            assertEquals("Two", book.chapters[1].title)
            assertEquals(0, book.chapters[0].offset)
            assertEquals(book.text.indexOf("Delta"), book.chapters[1].offset,
                "chapter 2's offset must land exactly where its text begins")

            val tokenPara = book.text.split("\n\n").firstOrNull { Epub.imagePath(it) != null }
            assertEquals("OEBPS/img/pic.png", tokenPara?.let { Epub.imagePath(it) },
                "the image reference becomes a token paragraph naming the resolved zip path")
            // the token sits between its surrounding paragraphs, in order
            val paras = book.text.split("\n\n")
            assertEquals(listOf("Alpha beta.", null, "Gamma.", "Delta epsilon zeta.").map { it ?: "TOKEN" },
                paras.map { if (Epub.imagePath(it) != null) "TOKEN" else it })
            assertContentEquals(byteArrayOf(1, 2, 3, 4), book.images["OEBPS/img/pic.png"])
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
