package wm.damage.core.windows.reader

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P5 (the spec's §2 P5; `CONTRACT.md` §3.2 "document", §12 Reader, §14 (a) row 2; decision A.8):
 * Reader's document for the web — blocks and spans over today's text, exactly. The glasses' path does not read it.
 */

/**
 * One book as a document: [text] is today's text exactly (`Epub.load(path).text`, extractor [ReaderAnchor.EXTRACTOR]),
 * so a place is one offset on every screen. [blocks] are its paragraphs in order (each `[charStart, charEnd)` one
 * paragraph of [text], the separators between them `"\n\n"`), typed by a second, markup-aware walk over each spine
 * document aligned onto the text; [spans] mark emphasis, strong, code and links inside a block. [images] are the zip
 * paths the text's image tokens name, in order of first use (the cover appended when the text never shows it); blob id
 * `reader.<book>.<n>` names `images[n]`. No image bytes are held.
 */
class EpubDoc private constructor(
    val title: String,
    val author: String,
    val text: String,
    val parts: List<Part>,
    val blocks: List<Block>,
    val images: List<String>,
    /** The cover's index in [images]: the OPF's cover entry, else the first image; null when the book has none. */
    val cover: Int?,
    val counts: Counts,
) {
    /** One spine document that contributed text (a chapter): its title and its span of [text]. */
    data class Part(val index: Int, val title: String, val charStart: Int, val charEnd: Int)

    enum class Kind(val wire: String) {
        HEADING("heading"), PARA("para"), ITEM("item"), QUOTE("quote"), MONO("mono"), IMAGE("image"), RULE("rule")
    }

    /** A link that is kept: an http or https [href] (opens outside the app's page, C§3.2), or an in-book [jump] to the
     *  start of another spine document (its offset in [text], dec. A.8). Exactly one is set. */
    data class Link(val href: String? = null, val jump: Int? = null)

    /** Marks over `[charStart, charEnd)` of [text]: a set of [EM], [STRONG], [CODE], [LINK] bits, [link] with LINK. */
    data class Span(val charStart: Int, val charEnd: Int, val marks: Int, val link: Link? = null)

    /** One paragraph: [level] is a heading's level (1..6) or an item's depth (1..), else 0; [image] the index in
     *  [images] of an image block; [part] the spine document it belongs to. */
    data class Block(
        val kind: Kind, val charStart: Int, val charEnd: Int, val level: Int = 0, val image: Int? = null,
        val part: Int = 0, val spans: List<Span> = emptyList(),
    )

    /** What the walk could not carry over, said by `--epub-check` (nothing is dropped silently). */
    class Counts {
        var spansPlaced = 0; var spansDropped = 0
        var external = 0; var jumps = 0
        /** Links within their own document, a fragment or the document itself (fragment targets are not built,
         *  dec. A.8): dropped. */
        var fragmentLinks = 0
        /** In-book jumps whose fragment was dropped (the jump goes to the document's start). */
        var fragmentsTrimmed = 0
        /** Other schemes, targets outside the spine, empty or malformed hrefs: dropped. */
        var otherLinks = 0
        var partsUnaligned = 0; var resyncs = 0

        fun add(o: Counts) {
            spansPlaced += o.spansPlaced; spansDropped += o.spansDropped; external += o.external; jumps += o.jumps
            fragmentLinks += o.fragmentLinks; fragmentsTrimmed += o.fragmentsTrimmed; otherLinks += o.otherLinks
            partsUnaligned += o.partsUnaligned; resyncs += o.resyncs
        }

        fun line(): String = "spans $spansPlaced placed, $spansDropped dropped · links $external http(s) kept, " +
            "$jumps in-book jumps ($fragmentsTrimmed fragments dropped), $fragmentLinks fragment links dropped, " +
            "$otherLinks other links dropped · $resyncs realignments, $partsUnaligned parts not aligned to their end"
    }

    private val partFirst: IntArray = IntArray(parts.size + 1).also { a ->
        var b = 0
        for (p in parts.indices) {
            while (b < blocks.size && blocks[b].part < p) b++
            a[p] = b
        }
        a[parts.size] = blocks.size
    }

    /** The blocks of part [n] (empty when there is no such part). */
    fun blocksOf(n: Int): List<Block> =
        if (n !in parts.indices) emptyList() else blocks.subList(partFirst[n], partFirst[n + 1])

    /**
     * Null when the blocks are [today]'s text exactly: [text] equals it, the blocks cover it in order with only
     * `"\n\n"` between them, and every span lies inside its block. Else the first difference, said.
     */
    fun checkText(today: String): String? {
        if (text != today) {
            val at = text.indices.firstOrNull { it >= today.length || text[it] != today[it] } ?: today.length
            return "the document's text differs from today's at offset $at (${text.length} vs ${today.length} chars)"
        }
        var at = 0
        for ((i, b) in blocks.withIndex()) {
            if (i > 0) {
                if (!text.startsWith(SEP, at)) return "no paragraph break before block $i (offset $at)"
                at += SEP.length
            }
            if (b.charStart != at || b.charEnd < b.charStart) return "block $i starts at ${b.charStart}, not $at"
            if (text.indexOf('\n', b.charStart).let { it in b.charStart until b.charEnd }) return "block $i holds a line break"
            for (s in b.spans) if (s.charStart < b.charStart || s.charEnd > b.charEnd || s.charEnd <= s.charStart)
                return "a span [${s.charStart}, ${s.charEnd}) outside block $i [${b.charStart}, ${b.charEnd})"
            at = b.charEnd
        }
        return if (at != text.length) "the blocks end at $at of ${text.length}" else null
    }

    /**
     * Part [n] as C§3.2's document: its blocks with their kind, level, offsets and spans as contiguous runs
     * `{text, marks, href | jump}` covering the block (a jump as an anchor at its target); an image block names its
     * blob. Null when there is no part [n].
     */
    fun partJson(book: String, n: Int): JsonObject? {
        val p = parts.getOrNull(n) ?: return null
        return buildJsonObject {
            put("kind", "document")
            put("id", "book:$book/part:$n")
            put("extractor", ReaderAnchor.EXTRACTOR)
            put("part", n)
            put("parts", parts.size)
            put("title", p.title)
            put("charStart", p.charStart)
            put("charEnd", p.charEnd)
            put("blocks", buildJsonArray { for (b in blocksOf(n)) add(blockJson(book, b)) })
        }
    }

    private fun blockJson(book: String, b: Block): JsonObject = buildJsonObject {
        put("id", "b${b.charStart}")
        put("kind", b.kind.wire)
        if (b.level > 0) put("level", b.level)
        put("charStart", b.charStart)
        put("charEnd", b.charEnd)
        b.image?.let { put("ref", blobId(book, it)) }
        put("spans", buildJsonArray {
            if (b.kind == Kind.IMAGE || b.kind == Kind.RULE) return@buildJsonArray
            var at = b.charStart
            fun piece(end: Int, s: Span?) = add(buildJsonObject {
                put("text", text.substring(at, end))
                put("marks", buildJsonArray {
                    val m = s?.marks ?: 0
                    for ((bit, name) in MARK_NAMES) if (m and bit != 0) add(JsonPrimitive(name))
                })
                s?.link?.href?.let { put("href", it) }
                s?.link?.jump?.let { put("jump", ReaderAnchor.at(text, it).format()) }
            }.also { at = end })
            for (s in b.spans) {
                if (s.charStart > at) piece(s.charStart, null)
                piece(s.charEnd, s)
            }
            if (at < b.charEnd) piece(b.charEnd, null)
        })
    }

    companion object {
        const val EM = 1
        const val STRONG = 2
        const val CODE = 4
        const val LINK = 8
        private val MARK_NAMES = listOf(EM to "em", STRONG to "strong", CODE to "code", LINK to "link")
        private const val SEP = "\n\n"

        /** The blob id of image [n] of [book] (C§9.4). */
        fun blobId(book: String, n: Int) = "reader.$book.$n"

        /** [id] as (book, n), or null when it is not a Reader image's blob id. */
        fun parseBlobId(id: String): Pair<String, Int>? {
            val m = Regex("^reader\\.([0-9a-f]{1,64})\\.(\\d{1,6})$").matchEntire(id) ?: return null
            return m.groupValues[1] to m.groupValues[2].toInt()
        }

        /** [path] as a document. Reads no image's bytes. Throws what `Epub.load` throws for the same file. */
        fun load(path: Path): EpubDoc = build(Epub.extract(path, readImages = false, keepMarkup = true))

        /**
         * The zip paths [text]'s image tokens name, in order of first use, then [cover] when the text never shows it;
         * and the cover's index (the OPF's [cover], else the first image; null when there is none). The reader index
         * and the document both take their image numbers from here, so a blob id means the same in both.
         */
        fun imageList(text: String, cover: String?): Pair<List<String>, Int?> {
            val out = ArrayList<String>()
            val seen = HashSet<String>()
            forEachParagraph(text) { a, b ->
                if (b - a > 6 && text[a] == Epub.IMG_TOKEN) Epub.imagePath(text.substring(a, b))?.let { if (seen.add(it)) out += it }
            }
            val c = cover ?: out.firstOrNull() ?: return out to null
            if (seen.add(c)) out += c
            return out to out.indexOf(c)
        }

        private inline fun forEachParagraph(text: String, f: (Int, Int) -> Unit) {
            var a = 0
            while (a <= text.length) {
                val e = text.indexOf(SEP, a).let { if (it < 0) text.length else it }
                if (e > a) f(a, e)
                a = e + SEP.length
            }
        }

        internal fun build(ex: Epub.Extract): EpubDoc {
            val book = ex.book
            val text = book.text
            val (images, cover) = imageList(text, ex.cover)
            val counts = Counts()
            val parts = ex.parts.mapIndexed { i, p ->
                Part(i, book.chapters.getOrNull(i)?.title ?: book.title, p.offset, p.offset + p.length)
            }
            // the in-book jump targets: a contributing spine document's path (as written and url-decoded) -> its offset
            val targets = HashMap<String, Int>()
            for (p in ex.parts) if (p.path.isNotEmpty()) {
                targets.putIfAbsent(p.path, p.offset); targets.putIfAbsent(Epub.urlDecode(p.path), p.offset)
            }
            val blocks = ArrayList<Block>()
            for ((i, p) in ex.parts.withIndex()) {
                val walk = p.markup?.let { m ->
                    Walk { href -> classify(href, p.path.substringBeforeLast('/', ""), p.path, targets, counts) }.also { it.run(m) }
                }
                // the blocks are cut from today's text, never from the walk's: the walk only types and marks them
                val partText = text.substring(p.offset, p.offset + p.length)
                blocks += partBlocks(i, partText, p.offset, walk, images, counts)
            }
            return EpubDoc(book.title, book.author, text, parts, blocks, images, cover, counts)
        }

        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

        /** dec. A.8: http and https leave the book; a link to another contributing spine document is a jump to its
         *  start (a fragment dropped and counted); everything else is dropped and counted. */
        private fun classify(raw: String, docDir: String, self: String, targets: Map<String, Int>, counts: Counts): Link? {
            val href = Epub.unescape(raw).trim()
            if (href.isEmpty() || href.any { it < ' ' }) { counts.otherLinks++; return null }
            val scheme = SCHEME.find(href)?.groupValues?.get(1)?.lowercase()
            if (scheme != null) {
                if (scheme == "http" || scheme == "https") { counts.external++; return Link(href = href) }
                counts.otherLinks++
                return null
            }
            val path = href.substringBefore('#').substringBefore('?')
            val fragment = href.substringAfter('#', "")
            if (path.isEmpty()) { counts.fragmentLinks++; return null }
            val target = Epub.resolvePath(docDir, path)
            val off = targets[target] ?: targets[Epub.urlDecode(target)]
            if (off == null) { counts.otherLinks++; return null }
            if (target == self || Epub.urlDecode(target) == Epub.urlDecode(self)) { counts.fragmentLinks++; return null }
            if (fragment.isNotEmpty()) counts.fragmentsTrimmed++
            counts.jumps++
            return Link(jump = off)
        }

        /** The blocks of one part: [t] its text (today's), [base] its offset in the book's text. */
        private fun partBlocks(part: Int, t: String, base: Int, walk: Walk?, images: List<String>, counts: Counts): List<Block> {
            val map = walk?.let { align(it.chars, t, counts) }
            // the text's position -> the walk's character there (or -1)
            val back = IntArray(t.length) { -1 }
            if (map != null) for (i in map.indices) if (map[i] >= 0) back[map[i]] = i
            val out = ArrayList<Block>()
            forEachParagraph(t) { a, b ->
                val para = t.substring(a, b)
                val img = Epub.imagePath(para)
                var kind = Kind.PARA
                var level = 0
                if (img != null) kind = Kind.IMAGE
                else if (walk != null) {
                    val w = (a until b).firstOrNull { back[it] >= 0 }?.let { walk.attr(back[it]) }
                    if (w != null) {
                        kind = when ((w shr KIND_SHIFT) and 7) { K_HEADING -> Kind.HEADING; K_ITEM -> Kind.ITEM
                            K_QUOTE -> Kind.QUOTE; K_MONO -> Kind.MONO; else -> Kind.PARA }
                        level = (w shr LEVEL_SHIFT) and 15
                        if (w and RULE_BIT != 0 && para == Epub.RULE_TEXT) { kind = Kind.RULE; level = 0 }
                        if (kind == Kind.PARA || kind == Kind.QUOTE || kind == Kind.MONO) level = 0
                    }
                }
                out += Block(kind, base + a, base + b, level, img?.let { images.indexOf(it) }, part)
            }
            if (walk == null || map == null) return out
            // the walk's marked runs -> spans on the text, cut at paragraph breaks
            val spans = ArrayList<Span>()
            var i = 0
            var pi = 0                       // the first block a span may touch (the runs' places only grow)
            val n = walk.chars.length
            while (i < n) {
                val key = walk.attr(i) and SPAN_KEY
                if (key and MARKS == 0) { i++; continue }
                var j = i
                var lo = -1; var hi = -1; var ink = false
                while (j < n && (walk.attr(j) and SPAN_KEY) == key) {
                    val c = Epub.foldChar(walk.chars[j])
                    if (c != null && !c.isWhitespace()) ink = true
                    if (map[j] >= 0) { if (lo < 0) lo = map[j]; hi = map[j] }
                    j++
                }
                if (ink) {
                    if (lo < 0) counts.spansDropped++
                    else {
                        counts.spansPlaced++
                        val link = walk.link(key)
                        while (pi < out.size && out[pi].charEnd - base <= lo) pi++
                        var q = pi
                        while (q < out.size && out[q].charStart - base <= hi) {
                            val a = out[q].charStart - base
                            val b = out[q].charEnd - base
                            val s = maxOf(a, lo); val e = minOf(b, hi + 1)
                            if (s < e) spans += Span(base + s, base + e, key and MARKS, link)
                            q++
                        }
                    }
                }
                i = j
            }
            if (spans.isEmpty()) return out
            var k = 0
            return out.map { b ->
                while (k < spans.size && spans[k].charEnd <= b.charStart) k++
                if (b.kind == Kind.IMAGE || b.kind == Kind.RULE) return@map b
                val mine = ArrayList<Span>()
                var x = k
                while (x < spans.size && spans[x].charStart < b.charEnd) { mine += spans[x]; x++ }
                if (mine.isEmpty()) b else b.copy(spans = mine)
            }
        }

        private const val WINDOW = 200
        private const val AGREE = 6
        private const val MAX_RESYNCS = 2000

        /**
         * The whitespace-tolerant two-pointer pass: the walk's characters, folded as `Epub.fold` folds them, against
         * the part's text; whitespace on either side is skipped, every other character must agree. Where they do not
         * (markup the walk reads differently from the pipeline), the smallest skip on either side after which
         * [AGREE] characters agree again; none within [WINDOW] ends the part's alignment there (counted). Returns the
         * walk's index -> the text's index, or -1.
         */
        private fun align(w: CharSequence, t: String, counts: Counts): IntArray {
            val map = IntArray(w.length) { -1 }
            val wi = IntArray(w.length); val wc = CharArray(w.length); var wn = 0
            for (i in 0 until w.length) {
                val c = Epub.foldChar(w[i]) ?: continue
                if (c.isWhitespace()) continue
                wi[wn] = i; wc[wn] = c; wn++
            }
            val ti = IntArray(t.length); var tn = 0
            for (j in t.indices) if (!t[j].isWhitespace()) ti[tn++] = j
            var p = 0; var q = 0; var resyncs = 0
            fun agree(p0: Int, q0: Int): Boolean {
                val k = minOf(AGREE, wn - p0, tn - q0)
                if (k <= 0) return p0 == wn && q0 == tn
                for (x in 0 until k) if (wc[p0 + x] != t[ti[q0 + x]]) return false
                return true
            }
            while (p < wn && q < tn) {
                if (wc[p] == t[ti[q]]) { map[wi[p]] = ti[q]; p++; q++; continue }
                var found = -1L
                search@ for (d in 1..2 * WINDOW) for (dp in maxOf(0, d - WINDOW)..minOf(d, WINDOW)) {
                    val dq = d - dp
                    if (p + dp > wn || q + dq > tn) continue
                    if (agree(p + dp, q + dq)) { found = (dp.toLong() shl 32) or dq.toLong(); break@search }
                }
                if (found < 0 || ++resyncs > MAX_RESYNCS) { counts.partsUnaligned++; break }
                p += (found ushr 32).toInt(); q += (found and 0xFFFFFFFFL).toInt()
            }
            counts.resyncs += minOf(resyncs, MAX_RESYNCS)
            return map
        }

        // the walk's per-character attribute: marks (bits 0-3), the block context's kind (4-6) and level (7-10), the
        // rule bit (11), the link's number + 1 (12-31)
        private const val MARKS = 15
        private const val KIND_SHIFT = 4
        private const val LEVEL_SHIFT = 7
        private const val RULE_BIT = 1 shl 11
        private const val LINK_SHIFT = 12
        private const val SPAN_KEY = MARKS or (-1 shl LINK_SHIFT)
        private const val K_HEADING = 1
        private const val K_ITEM = 2
        private const val K_QUOTE = 3
        private const val K_MONO = 4

        private val NAME = Regex("""^<\s*(/?)\s*(?:[A-Za-z][\w.-]*:)?([A-Za-z][A-Za-z0-9]*)""")
        private val BLOCKS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dt", "dd",
            "blockquote", "pre", "section", "article", "aside", "header", "footer", "nav", "main", "figure",
            "figcaption", "table", "tr", "td", "th", "body", "address", "center", "hgroup")
        private val VOID = setOf("br", "hr", "img", "image", "meta", "link", "input", "area", "base", "col", "embed",
            "param", "source", "track", "wbr")
        private val EMS = setOf("em", "i")
        private val STRONGS = setOf("strong", "b")
        private val CODES = setOf("code", "tt", "kbd", "samp")
    }

    /**
     * The markup-aware walk over one spine document's markup (image tokens in place): the same dropped elements,
     * comments and tag boundaries as `Epub.htmlToText` (its shared patterns), so its characters are the pipeline's;
     * paragraph breaks at every block element's start and end, which the pipeline writes only at some ends — the
     * alignment skips that whitespace. Each character carries its marks, its block context and its link.
     */
    private class Walk(private val classify: (String) -> Link?) {
        val chars = StringBuilder()
        private var attrs = IntArray(1024)
        private val links = ArrayList<Link>()
        private class Open(val name: String, val link: Int)
        private val stack = ArrayList<Open>()
        private var state = 0
        private var dirty = true

        fun attr(i: Int): Int = attrs[i]

        fun link(key: Int): Link? = ((key ushr LINK_SHIFT) - 1).takeIf { it >= 0 }?.let { links[it] }

        fun run(markup: String) {
            var s = Epub.DROPPED.replace(markup, " ")
            s = Epub.COMMENT.replace(s, " ")
            var last = 0
            for (m in Epub.TAG.findAll(s)) {
                if (m.range.first > last) text(Epub.unescape(s.substring(last, m.range.first)))
                tag(m.value)
                last = m.range.last + 1
            }
            if (last < s.length) text(Epub.unescape(s.substring(last)))
        }

        private fun tag(v: String) {
            val m = NAME.find(v)
            val closing = m?.groupValues?.get(1) == "/"
            val name = m?.groupValues?.get(2)?.lowercase()
            when {
                Epub.BLOCK_END.matches(v) -> { close(name!!); text("\n\n") }
                Epub.BREAK.matches(v) -> text("\n")
                Epub.RULE.matches(v) -> { text("\n\n"); rule(); text("\n\n") }
                name == null -> {}                                  // not a tag: the pipeline strips it, writing nothing
                closing -> { close(name); if (name in BLOCKS) text("\n\n") }
                name == "br" -> text("\n")
                name == "hr" -> text("\n\n")                        // a rule the pipeline writes no "* * *" for
                else -> {
                    if (name in BLOCKS) text("\n\n")
                    if (name !in VOID && !v.endsWith("/>")) open(name, v)
                }
            }
        }

        private fun open(name: String, v: String) {
            var link = -1
            if (name == "a") Epub.attr(v, "href")?.let { classify(it) }?.let { links += it; link = links.size - 1 }
            stack += Open(name, link)
            dirty = true
        }

        private fun close(name: String) {
            val i = stack.indexOfLast { it.name == name }
            if (i < 0) return
            while (stack.size > i) stack.removeAt(stack.size - 1)
            dirty = true
        }

        private fun current(): Int {
            if (!dirty) return state
            var marks = 0; var link = -1; var heading = 0; var ctx = 0; var level = 0; var lists = 0
            for (o in stack) {
                when (o.name) {
                    in EMS -> marks = marks or EM
                    in STRONGS -> marks = marks or STRONG
                    in CODES -> marks = marks or CODE
                    "a" -> if (o.link >= 0) link = o.link
                    "ul", "ol" -> lists++
                    "li" -> { ctx = K_ITEM; level = maxOf(1, lists) }
                    "blockquote" -> { ctx = K_QUOTE; level = 0 }
                    "pre" -> { ctx = K_MONO; level = 0 }
                    else -> if (o.name.length == 2 && o.name[0] == 'h' && o.name[1] in '1'..'6') heading = o.name[1] - '0'
                }
            }
            if (link >= 0) marks = marks or LINK
            if (heading > 0) { ctx = K_HEADING; level = heading }
            state = marks or (ctx shl KIND_SHIFT) or (minOf(level, 15) shl LEVEL_SHIFT) or ((link + 1) shl LINK_SHIFT)
            dirty = false
            return state
        }

        private fun text(s: String) = append(s, current())

        private fun rule() = append(Epub.RULE_TEXT, current() or RULE_BIT)

        private fun append(s: String, a: Int) {
            if (s.isEmpty()) return
            val need = chars.length + s.length
            if (need > attrs.size) attrs = attrs.copyOf(maxOf(need, attrs.size * 2))
            java.util.Arrays.fill(attrs, chars.length, need, a)
            chars.append(s)
        }
    }
}

/**
 * The documents the PC serves (the spec's §2 P5): one book parsed at a time, the last [keep] kept (the PC's heap history,
 * `HANDOFF.md` §72), each kept while its file's size and modification time stand. [pathOf] names a book's file from
 * the shelf the core holds (null when the shelf has no such book).
 */
class ReaderDocs(private val pathOf: (String) -> Path?, private val keep: Int = KEEP) {
    private class Held(val size: Long, val mtime: Long, val doc: EpubDoc)
    private val held = LinkedHashMap<String, Held>(4, 0.75f, true)

    /** [book]'s document, parsed now unless held; null when the shelf has no such book. Throws what the parse throws.
     *  Blocking: call it off any loop. */
    @Synchronized
    fun doc(book: String): EpubDoc? {
        val p = pathOf(book) ?: return null
        val size = Files.size(p)
        val mtime = Files.getLastModifiedTime(p).toMillis()
        held[book]?.let { if (it.size == size && it.mtime == mtime) return it.doc }
        held.remove(book)
        val d = EpubDoc.load(p)
        held[book] = Held(size, mtime, d)
        while (held.size > keep) held.remove(held.keys.first())
        return d
    }

    /** Part [n] of [book] as C§3.2's document, or null when there is no such book or part. */
    fun part(book: String, n: Int): JsonObject? = doc(book)?.partJson(book, n)

    /** The books held now, least recently used first (introspection). */
    @Synchronized
    fun heldBooks(): List<String> = held.keys.toList()

    companion object {
        const val KEEP = 2
    }
}
