package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.windows.reader.Epub
import wm.damage.core.windows.reader.EpubDoc
import wm.damage.core.windows.reader.EpubDoc.Companion.CODE
import wm.damage.core.windows.reader.EpubDoc.Companion.EM
import wm.damage.core.windows.reader.EpubDoc.Companion.LINK
import wm.damage.core.windows.reader.EpubDoc.Companion.STRONG
import wm.damage.core.windows.reader.ReaderAnchor

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P5 (the spec's §2 P5; CONTRACT.md §3.2; decision A.8): Reader's document — its text is today's
 * text exactly (a place is one offset on every screen), its blocks typed and its spans marked by a markup walk aligned
 * onto that text; only http and https leave the book.
 */
class EpubDocTest {
    private val tmp: Path = Files.createTempDirectory("damage-epubdoc")

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    private var n = 0

    private fun book(vararg docs: Pair<String, String>, images: Map<String, ByteArray> = emptyMap(), coverMeta: String? = null,
                     extraManifest: String = ""): Path =
        EpubFixture.write(tmp.resolve("b${n++}.epub"), docs.toList(), images, coverMeta = coverMeta, extraManifest = extraManifest)

    private fun EpubDoc.spanText(s: EpubDoc.Span) = text.substring(s.charStart, s.charEnd)
    private fun EpubDoc.blockText(b: EpubDoc.Block) = text.substring(b.charStart, b.charEnd)
    private fun EpubDoc.spans() = blocks.flatMap { it.spans }

    /** The text-identity check on one file: the document's text and blocks are `Epub.load`'s text. */
    private fun identical(p: Path): EpubDoc {
        val today = Epub.load(p).text
        val doc = EpubDoc.load(p)
        assertNull(doc.checkText(today), "the blocks are today's text for $p")
        return doc
    }

    @Test
    fun theTextIsTodaysTextExactly() {
        // the markup the walk reads differently from today's pipeline — whitespace only, which the alignment skips
        val edge = listOf(
            "<div>glued<p>words</p></div><p>next</p>",                            // today's text joins "gluedwords"
            "<p>line<br class=\"x\"/>break</p><p>tail </p ><p>after</p>",         // a classed br, a spaced end tag
            "<p>before</p><hr class=\"scene\"/><p>after the classed rule</p><hr/><p>after the bare rule</p>",
            "<pre>  code\n    indented\n\n  block  </pre><p>x</p>",
            "<p>a&nbsp;&nbsp;b \t c&#160;d</p><p>&lt;tag&gt; &amp;amp; &unknownent; &#x2014; &#151;</p>",
            "<p>zero​width‑hyphen \u0097 mojibake ﻿</p>",
            "<p>   </p><p>\n\n</p><div> <span> </span> </div><p>only this</p>",
            "<ul>\n  <li>one</li>\n  <li>two<ul><li>nested</li></ul></li>\n</ul>",
            "<p>x <em>a < b</em></p><p>c</p>",                                   // a literal '<' the pipeline reads as a tag
            "<!-- a comment <p>not text</p> --><script>var x = '<p>';</script><p>shown</p>",
            "<p>&am<em>p;</em>split entity</p>",
        )
        for (body in edge) identical(book("c.xhtml" to body))
        identical(book("a.xhtml" to edge[0], "b.xhtml" to edge[2], "c.xhtml" to "<p> </p>", "d.xhtml" to edge[4]))
        // a plain-text book: one part, its paragraphs
        val txt = tmp.resolve("plain.txt")
        Files.writeString(txt, "First  para\nwraps.\n\n\n  Second.\r\n\r\nThird")
        val d = identical(txt)
        assertEquals(listOf("First para wraps.", "Second.", "Third"), d.blocks.map { d.blockText(it) })
        assertEquals(1, d.parts.size)
    }

    @Test
    fun theTextIdentityHoldsOverGeneratedMarkup() {
        val r = java.util.Random(20261114L)
        val inline = listOf("em", "i", "strong", "b", "code", "span", "tt", "a")
        val block = listOf("p", "div", "h1", "h3", "blockquote", "li", "pre", "section")
        val words = listOf("alpha", "beta", "&amp;", "&nbsp;", "&mdash;", "&#8217;s", "&bogus;", "gamma‑delta",
            " ", "zeta​", "&lt;x&gt;", "\u0093q\u0094", "x<br/>y", "x<br class='k'/>y", "\n", "\t", "  ", "<hr/>",
            "<hr class='s'/>", "<img src=\"i.png\"/>")
        fun inlineRun(depth: Int): String = buildString {
            repeat(1 + r.nextInt(5)) {
                if (depth < 3 && r.nextInt(3) == 0) {
                    val t = inline[r.nextInt(inline.size)]
                    val attrs = if (t == "a") " href=\"${listOf("http://e.example/x", "#n", "b.xhtml", "mailto:q@e.example", "b.xhtml#s")[r.nextInt(5)]}\"" else ""
                    append("<$t$attrs>").append(inlineRun(depth + 1)).append("</$t>")
                } else append(words[r.nextInt(words.size)]).append(if (r.nextBoolean()) " " else "")
            }
        }
        fun blockRun(depth: Int): String = buildString {
            repeat(1 + r.nextInt(4)) {
                val t = block[r.nextInt(block.size)]
                append(if (r.nextBoolean()) "\n  " else "").append("<$t>")
                if (depth < 2 && r.nextInt(3) == 0) append(blockRun(depth + 1)) else append(inlineRun(0))
                append(if (r.nextInt(5) == 0) "</$t >" else "</$t>")
            }
        }
        var docs = 0
        repeat(12) {
            val parts = (0 until 1 + r.nextInt(4)).map { i -> "p$i.xhtml" to blockRun(0) }.toTypedArray()
            identical(book(*parts, "b.xhtml" to blockRun(0), images = mapOf("i.png" to byteArrayOf(1, 2, 3))))
            docs += parts.size + 1
        }
        assertTrue(docs >= 24, "generated $docs documents")
    }

    @Test
    fun spansMarkEmphasisStrongCodeAndNesting() {
        val d = identical(book("c.xhtml" to
            "<p>Plain <em>soft <strong>both</strong></em> and <code>x()</code>, <i>it</i> <b>bold</b>.</p>" +
            "<p><em>one<br/><br/>two</em></p>"))
        val got = d.spans().map { d.spanText(it) to it.marks }
        assertEquals(listOf("soft" to EM, "both" to (EM or STRONG), "x()" to CODE, "it" to EM, "bold" to STRONG,
            "one" to EM, "two" to EM), got, "the marks, nested, and a span cut at the paragraph break")
        assertEquals(6, d.counts.spansPlaced, "the cut span counts once: ${d.counts.line()}")
        assertEquals(0, d.counts.spansDropped)
    }

    @Test
    fun headingsItemsQuotesMonoImagesAndRules() {
        val d = identical(book("c.xhtml" to
            "<h2>Title</h2><p>Para.</p><ul><li><p>top</p><ul>\n<li>deep</li></ul></li></ul>\n<li>loose</li>" +
            "<blockquote><p>Quoted.</p></blockquote><pre>mono  text</pre><p>p</p><hr/><p>* * *</p>" +
            "<img src=\"img/a.png\"/><p>end</p>", images = mapOf("img/a.png" to byteArrayOf(9))))
        val kinds = d.blocks.map { Triple(it.kind, it.level, d.blockText(it)) }
        assertEquals(listOf(
            Triple(EpubDoc.Kind.HEADING, 2, "Title"),
            Triple(EpubDoc.Kind.PARA, 0, "Para."),
            Triple(EpubDoc.Kind.ITEM, 1, "top"),
            Triple(EpubDoc.Kind.ITEM, 2, "deep"),
            Triple(EpubDoc.Kind.ITEM, 1, "loose"),
            Triple(EpubDoc.Kind.QUOTE, 0, "Quoted."),
            Triple(EpubDoc.Kind.MONO, 0, "mono textp"),       // today's text has no break after </pre>
            Triple(EpubDoc.Kind.RULE, 0, "* * *"),
            Triple(EpubDoc.Kind.PARA, 0, "* * *"),             // the book's own asterisks stay its text
            Triple(EpubDoc.Kind.IMAGE, 0, "${Epub.IMG_TOKEN}IMG:OEBPS/img/a.png${Epub.IMG_TOKEN}"),
            Triple(EpubDoc.Kind.PARA, 0, "end"),
        ), kinds)
        val img = d.blocks.single { it.kind == EpubDoc.Kind.IMAGE }
        assertEquals("OEBPS/img/a.png", d.images[img.image!!])
        assertEquals(0, d.cover, "no cover entry: the first image")
        val json = d.partJson("abcd", 0)!!
        val blocks = json["blocks"]!!.jsonArray.map { it.jsonObject }
        assertEquals("reader.abcd.0", blocks.single { it["kind"]!!.jsonPrimitive.content == "image" }["ref"]!!.jsonPrimitive.content)
        assertEquals("2", blocks.first()["level"]!!.jsonPrimitive.content)
    }

    @Test
    fun onlyHttpLinksLeaveTheBook() {
        val links = listOf("http://a.example/1", "https://b.example/2", "HTTPS://C.example/3", "  http://d.example/4 ",
            "mailto:x@e.example", "javascript:alert(1)", "data:text/html,hi", "ftp://f.example", "file:///etc/x",
            "java\nscript:void(0)", "#note1", "c1.xhtml#own", "c2.xhtml", "c2.xhtml#sec", "../OEBPS/c2.xhtml",
            "notinspine.xhtml", "", "c3%20x.xhtml", "http://g.example/&#1;x")
        val body = links.joinToString(" ") { "<a href=\"$it\">L</a>" }
        val d = identical(book("c1.xhtml" to "<p>$body</p>", "c2.xhtml" to "<p>Second.</p>", "c3 x.xhtml" to "<p>Third.</p>",
            extraManifest = """<item id="ns" href="notinspine.xhtml" media-type="application/xhtml+xml"/>"""))
        val kept = d.spans().filter { it.marks and LINK != 0 }.map { it.link!! }
        val hrefs = kept.mapNotNull { it.href }
        assertEquals(listOf("http://a.example/1", "https://b.example/2", "HTTPS://C.example/3", "http://d.example/4"), hrefs,
            "only http and https leave the book")
        for (h in hrefs) assertTrue(h.lowercase().startsWith("http://") || h.lowercase().startsWith("https://"), h)
        val c2 = d.parts[1].charStart
        val c3 = d.parts[2].charStart
        assertEquals(listOf(c2, c2, c2, c3), kept.mapNotNull { it.jump }, "a link to another spine document jumps to its start")
        val c = d.counts
        assertEquals(4, c.external)
        assertEquals(4, c.jumps)
        assertEquals(1, c.fragmentsTrimmed, "c2.xhtml#sec keeps the jump, drops the fragment")
        assertEquals(2, c.fragmentLinks, "#note1 and the link into its own document")
        assertEquals(8, c.otherLinks, "five other schemes, a hidden scheme, a target outside the spine, an http link " +
            "holding a control character (an empty href is no link)")
        // the wire: a jump is an anchor at its target, an href is kept as written
        val runs = d.partJson("b00k", 0)!!["blocks"]!!.jsonArray.flatMap { it.jsonObject["spans"]!!.jsonArray }.map { it.jsonObject }
        assertEquals(4, runs.count { "href" in it })
        val jump = runs.first { "jump" in it }["jump"]!!.jsonPrimitive.content
        assertEquals(c2, ReaderAnchor.parse(jump)!!.offset)
    }

    @Test
    fun aSpanThatCannotBePlacedIsDroppedAndCounted() {
        // an entity split by a tag: the pipeline reads "&x", the walk's emphasised "p;" is in no character of the text
        val d = identical(book("c.xhtml" to "<p>&am<em>p;</em>x and <em>kept</em></p>"))
        assertEquals(listOf("kept"), d.spans().map { d.spanText(it) })
        assertEquals(1, d.counts.spansDropped, d.counts.line())
        assertEquals(1, d.counts.spansPlaced)
        assertTrue(d.counts.resyncs >= 1)
    }

    @Test
    fun theCoverIsTheOpfsEntryElseTheFirstImage() {
        val imgs = mapOf("img/a.png" to byteArrayOf(1), "img/cover.png" to byteArrayOf(2))
        val withCover = identical(book("c.xhtml" to "<p>x</p><img src=\"img/a.png\"/>", images = imgs, coverMeta = "img1"))
        assertEquals(listOf("OEBPS/img/a.png", "OEBPS/img/cover.png"), withCover.images, "the cover appended: the text never shows it")
        assertEquals(1, withCover.cover)
        val noCover = identical(book("c.xhtml" to "<p>x</p><img src=\"img/cover.png\"/><img src=\"img/a.png\"/>", images = imgs))
        assertEquals(0, noCover.cover)
        assertEquals("OEBPS/img/cover.png", noCover.images[0])
        assertNull(identical(book("c.xhtml" to "<p>no pictures</p>")).cover)
        assertEquals("b1" to 3, EpubDoc.parseBlobId(EpubDoc.blobId("b1", 3)))
        assertNull(EpubDoc.parseBlobId("reader.../x.png"))
    }

    @Test
    fun theDocumentReadsNoImageBytes() {
        val p = book("c.xhtml" to "<p>Words.</p><img src=\"img/n.png\"/><p>More.</p>", images = mapOf("img/n.png" to EpubFixture.noise(20_000)))
        val today = Epub.load(p).text
        assertTrue(EpubFixture.spoilEntry(p, "OEBPS/img/n.png"), "the fixture's image can no longer be read")
        val doc = EpubDoc.load(p)          // today's loader would now fail on the image's bytes; the document never reads them
        assertNull(doc.checkText(today))
        assertEquals(listOf("OEBPS/img/n.png"), doc.images)
        assertNotNull(doc.partJson("x", 0))
    }
}
