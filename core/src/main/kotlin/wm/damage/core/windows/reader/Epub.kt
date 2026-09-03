package wm.damage.core.windows.reader

import java.nio.file.Path
import java.util.zip.ZipFile
import wm.damage.core.util.Log

/**
 * Minimal, dependency-free EPUB text extraction: container.xml -> OPF ->
 * spine-ordered XHTML -> plain text with paragraph breaks. Content scrolls, it
 * does not get cut (NO TRUNCATION): everything the spine holds is extracted;
 * markup we cannot interpret degrades to its text, never to silence — and a
 * file that cannot be parsed at all raises loudly rather than opening empty.
 */
object Epub {
    /** A chapter = one spine document that contributed text (2026-08-31): its
     *  CHARACTER offset into [Book.text] — the same monotonic offsets the
     *  reading positions use — and a title from the EPUB's own nav/toc,
     *  falling back to the document's first heading-ish line, then a number. */
    data class Chapter(val title: String, val offset: Int)

    data class Book(
        val title: String,
        val author: String,
        val text: String,
        val chapters: List<Chapter> = emptyList(),
        /** Raw bytes of every image the spine references (2026-08-31), keyed
         *  by resolved zip path — the [IMG_TOKEN] placeholders in [text] name
         *  these keys. Decoded lazily at layout via the ImageDecoder seam. */
        val images: Map<String, ByteArray> = emptyMap(),
    )

    /** An image placeholder is its own paragraph: `IMG:<zip path>`.
     *  The control character cannot occur in book text and survives normalize() untouched. */
    const val IMG_TOKEN = '\u0001'
    fun imagePath(para: String): String? =
        if (para.length > 6 && para[0] == IMG_TOKEN && para.endsWith(IMG_TOKEN) &&
            para.startsWith("${IMG_TOKEN}IMG:")) para.substring(5, para.length - 1) else null

    /** Per-image raw-byte cap — over it, the image shows as a placeholder. */
    const val IMG_MAX_BYTES = 8 shl 20

    fun isEpub(p: Path): Boolean = p.fileName.toString().lowercase().endsWith(".epub")

    fun load(path: Path): Book {
        if (path.fileName.toString().lowercase().endsWith(".txt")) {
            // UTF-8 first; a large share of plain-text books are Latin-1 — a
            // strict decode threw "Input length = 1" at the whole file
            val bytes = java.nio.file.Files.readAllBytes(path)
            val raw = try {
                java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                Log.i("epub", "$path is not UTF-8 — decoding as ISO-8859-1")
                String(bytes, Charsets.ISO_8859_1)
            }
            return Book(path.fileName.toString().removeSuffix(".txt"), "", normalize(raw))
        }
        ZipFile(path.toFile()).use { zip ->
            fun read(name: String): String? {
                val e = zip.getEntry(name) ?: zip.getEntry(name.removePrefix("/")) ?: return null
                return zip.getInputStream(e).readBytes().toString(Charsets.UTF_8)
            }

            val container = read("META-INF/container.xml")
                ?: throw IllegalArgumentException("$path: no META-INF/container.xml — not an EPUB")
            val opfPath = attr(container, "full-path")
                ?: throw IllegalArgumentException("$path: container.xml names no rootfile")
            val opf = read(opfPath) ?: throw IllegalArgumentException("$path: missing OPF $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val title = xmlText(opf, "dc:title") ?: path.fileName.toString().removeSuffix(".epub")
            val author = xmlText(opf, "dc:creator") ?: ""

            // manifest id -> href. Real EPUBs use single OR double quotes and
            // sometimes namespace-prefixed tags (<opf:item>) — every miss here
            // was a SILENTLY dropped chapter in review round 1.
            val items = HashMap<String, String>()
            for (m in Regex("""<(?:\w+:)?item\b[^>]*>""").findAll(opf)) {
                val tag = m.value
                val id = attr(tag, "id") ?: continue
                val href = attr(tag, "href") ?: continue
                items[id] = href
            }
            val spine = Regex("""<(?:\w+:)?itemref\b[^>]*>""").findAll(opf)
                .mapNotNull { attr(it.value, "idref") }.toList()
            if (spine.isEmpty()) throw IllegalArgumentException("$path: OPF has an empty spine")

            // Per-document normalize so each contribution's CHARACTER OFFSET in
            // the final text is exact (chapters + reading positions share that
            // coordinate space); joining normalized parts with "\n\n" yields
            // the same text the old whole-book normalize produced.
            fun readBytes(name: String): ByteArray? {
                val e = zip.getEntry(name) ?: zip.getEntry(name.removePrefix("/")) ?: return null
                return zip.getInputStream(e).readBytes()
            }

            val images = HashMap<String, ByteArray>()
            val parts = ArrayList<Pair<String, String>>()      // resolved path -> text
            var missing = 0
            for (idref in spine) {
                val href = items[idref]
                if (href == null) { missing++; continue }   // counted, not silent
                val norm = resolvePath(opfDir, unescape(href).substringBefore('#'))
                val doc = read(urlDecode(norm)) ?: read(norm)
                if (doc == null) { missing++; continue }
                // image references become token paragraphs BEFORE the tag
                // strip (2026-08-31); their bytes are read while the zip is open
                val withImages = extractImages(doc, norm.substringBeforeLast('/', ""), ::readBytes, images, path)
                val t = normalize(htmlToText(withImages))
                if (t.isNotBlank()) parts.add(norm to t)
            }
            if (missing > 0) Log.w("epub", "$path: $missing of ${spine.size} spine documents " +
                "could not be read — that text is MISSING from the book")
            if (parts.isEmpty()) throw IllegalArgumentException("$path: spine extracted no text")

            val tocTitles = tocTitles(::read, opf, opfDir)
            val sb = StringBuilder()
            val chapters = ArrayList<Chapter>()
            for ((docPath, t) in parts) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                chapters.add(Chapter(
                    tocTitles[docPath] ?: fallbackTitle(t, chapters.size + 1), sb.length))
                sb.append(t)
            }
            if (images.isNotEmpty()) {
                val total = images.values.sumOf { it.size }
                Log.i("epub", "$path: ${images.size} image(s), ${total / 1024} KB raw")
            }
            return Book(title.trim(), author.trim(), sb.toString(), chapters, images)
        }
    }

    /** Replace `<img src>` / SVG `<image (xlink:)href>` with [IMG_TOKEN]
     *  paragraphs and collect the referenced bytes (per-image cap, data: URIs
     *  skipped — both loudly). Hrefs resolve against the DOCUMENT's own
     *  directory, not the OPF's. */
    private fun extractImages(
        html: String,
        docDir: String,
        readBytes: (String) -> ByteArray?,
        sink: MutableMap<String, ByteArray>,
        bookPath: Path,
    ): String = Regex("""<(?:img|(?:\w+:)?image)\b[^>]*>""", RegexOption.IGNORE_CASE).replace(html) { m ->
        val tag = m.value
        val src = attr(tag, "src") ?: attr(tag, "xlink:href") ?: attr(tag, "href")
        when {
            src == null -> " "
            src.startsWith("data:") -> {
                Log.w("epub", "$bookPath: inline data: image skipped (unsupported)"); " "
            }
            else -> {
                val p = resolvePath(docDir, unescape(src).substringBefore('#'))
                if (p !in sink) {
                    val b = readBytes(urlDecode(p)) ?: readBytes(p)
                    when {
                        b == null -> Log.w("epub", "$bookPath: image '$p' is not in the archive")
                        b.size > IMG_MAX_BYTES -> Log.w("epub",
                            "$bookPath: image '$p' is ${b.size / 1024} KB — over the cap, placeholder shown")
                        else -> sink[p] = b
                    }
                }
                "\n\n${IMG_TOKEN}IMG:$p$IMG_TOKEN\n\n"
            }
        }
    }

    /** Titles from the book's own navigation: EPUB2 NCX (navLabel text +
     *  content src) and EPUB3 nav (properties="nav", its anchors), keyed by
     *  the target document's resolved path. First label per document wins. */
    private fun tocTitles(read: (String) -> String?, opf: String, opfDir: String): Map<String, String> {
        val out = HashMap<String, String>()
        val tocs = Regex("""<(?:\w+:)?item\b[^>]*>""").findAll(opf).mapNotNull { m ->
            val tag = m.value
            val href = attr(tag, "href") ?: return@mapNotNull null
            val mt = attr(tag, "media-type") ?: ""
            val props = attr(tag, "properties") ?: ""
            when {
                "dtbncx" in mt || href.lowercase().substringBefore('#').endsWith(".ncx") -> "ncx" to href
                "nav" in props.split(' ') -> "nav" to href
                else -> null
            }
        }.toList()
        for ((kind, href) in tocs) {
            val tocPath = resolvePath(opfDir, unescape(href).substringBefore('#'))
            val tocDir = tocPath.substringBeforeLast('/', "")
            val doc = read(urlDecode(tocPath)) ?: read(tocPath) ?: continue
            if (kind == "ncx") {
                // navLabel text precedes its content src in every navPoint:
                // walk both in order, pairing each src with the last label
                var label: String? = null
                for (m in Regex("""<text[^>]*>([^<]*)</text>|<content\b[^>]*>""",
                        RegexOption.IGNORE_CASE).findAll(doc)) {
                    if (m.value.startsWith("<text", ignoreCase = true)) {
                        label = unescape(m.groupValues[1]).trim().takeIf { it.isNotEmpty() }
                    } else {
                        val src = attr(m.value, "src") ?: continue
                        val target = resolvePath(tocDir, unescape(src).substringBefore('#'))
                        label?.let { out.putIfAbsent(target, it) }
                    }
                }
            } else {
                for (a in Regex("""<a\b[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).findAll(doc)) {
                    val h = attr(a.value.substringBefore('>') + ">", "href") ?: continue
                    val label = unescape(Regex("<[^>]+>").replace(a.groupValues[1], " "))
                        .trim().replace(Regex("\\s+"), " ")
                    if (label.isEmpty()) continue
                    out.putIfAbsent(resolvePath(tocDir, unescape(h).substringBefore('#')), label)
                }
            }
        }
        return out
    }

    /** A chapter title when the toc names none: the document's first line when
     *  it reads as a heading, else a plain number. Image-token lines never
     *  title a chapter (a cover-only document would otherwise show its raw
     *  token in the picker — seen in the first snapshot run). */
    private fun fallbackTitle(text: String, n: Int): String {
        val first = text.lineSequence()
            .firstOrNull { it.isNotBlank() && imagePath(it.trim()) == null }?.trim() ?: ""
        return if (first.length in 1..60 && first.any { it.isLetter() }) first else "Chapter $n"
    }

    /** Just the metadata, cheaply (library scan). */
    fun meta(path: Path): Pair<String, String> = try {
        if (path.fileName.toString().lowercase().endsWith(".txt")) {
            path.fileName.toString().removeSuffix(".txt") to ""
        } else ZipFile(path.toFile()).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
                ?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
            // attr() handles both quote styles — the double-quote-only regex
            // here silently fell back to the filename on single-quoted
            // container.xml files (review round 2 #B12)
            val opfPath = container?.let { attr(it, "full-path") }
            val opf = opfPath?.let { p -> zip.getEntry(p)?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) } }
            val title = opf?.let { xmlText(it, "dc:title") } ?: path.fileName.toString().removeSuffix(".epub")
            val author = opf?.let { xmlText(it, "dc:creator") } ?: ""
            title.trim() to author.trim()
        }
    } catch (e: Exception) {
        Log.w("epub", "metadata of $path unreadable: ${e.message}")
        path.fileName.toString() to ""
    }

    private fun xmlText(xml: String, tag: String): String? =
        Regex("""<$tag\b[^>]*>([^<]*)</$tag>""").find(xml)?.groupValues?.get(1)
            ?.let { unescape(it) }?.takeIf { it.isNotBlank() }

    /** An attribute value, either quote style. */
    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""").find(tag)
            ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            ?.takeIf { it.isNotEmpty() }

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /** Resolve an href against a base directory: normalize ./ and ../
     *  segments into a canonical zip path. Callers strip fragments and
     *  unescape entities first, and try both the URL-decoded and raw names
     *  when reading (real EPUBs disagree about %20). */
    fun resolvePath(baseDir: String, href: String): String {
        val joined = if (baseDir.isEmpty()) href else "$baseDir/$href"
        val parts = ArrayList<String>()
        for (seg in joined.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(seg)
        }
        return parts.joinToString("/")
    }

    /** XHTML -> text: scripts/styles dropped, block elements become paragraph
     *  or line breaks, inline markup becomes its text, entities decoded. */
    fun htmlToText(html: String): String {
        var s = html
        s = Regex("""<(script|style|head|title)\b[\s\S]*?</\1>""", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("""<!--[\s\S]*?-->""").replace(s, " ")
        // block-level boundaries -> paragraph breaks
        s = Regex("""</(p|div|h[1-6]|li|blockquote|tr|section|article)>""", RegexOption.IGNORE_CASE)
            .replace(s, "\n\n")
        s = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n\n* * *\n\n")
        s = Regex("""<[^>]+>""").replace(s, "")
        return unescape(s)
    }

    /** The XML five + the Latin-1 set + common typographic entities — matching
     *  the shell's Latin-1 glyph policy. Unknown names stay literal AND are
     *  logged once, so fidelity loss is never silent. */
    private val NAMED: Map<String, String> = buildMap {
        put("amp", "&"); put("lt", "<"); put("gt", ">"); put("quot", "\""); put("apos", "'")
        put("nbsp", "\u00A0"); put("shy", ""); put("mdash", "\u2014"); put("ndash", "\u2013")
        put("lsquo", "\u2018"); put("rsquo", "\u2019"); put("ldquo", "\u201C"); put("rdquo", "\u201D")
        put("hellip", "\u2026"); put("copy", "\u00A9"); put("reg", "\u00AE"); put("deg", "\u00B0")
        put("laquo", "\u00AB"); put("raquo", "\u00BB"); put("middot", "\u00B7"); put("times", "\u00D7")
        put("frac12", "\u00BD"); put("frac14", "\u00BC"); put("frac34", "\u00BE"); put("plusmn", "\u00B1")
        put("sup1", "\u00B9"); put("sup2", "\u00B2"); put("sup3", "\u00B3"); put("micro", "\u00B5")
        put("para", "\u00B6"); put("sect", "\u00A7"); put("cent", "\u00A2"); put("pound", "\u00A3")
        put("yen", "\u00A5"); put("iexcl", "\u00A1"); put("iquest", "\u00BF")
        // Latin-1 letters: entity name -> code point
        val latin = mapOf(
            "Agrave" to 0xC0, "Aacute" to 0xC1, "Acirc" to 0xC2, "Atilde" to 0xC3, "Auml" to 0xC4,
            "Aring" to 0xC5, "AElig" to 0xC6, "Ccedil" to 0xC7, "Egrave" to 0xC8, "Eacute" to 0xC9,
            "Ecirc" to 0xCA, "Euml" to 0xCB, "Igrave" to 0xCC, "Iacute" to 0xCD, "Icirc" to 0xCE,
            "Iuml" to 0xCF, "ETH" to 0xD0, "Ntilde" to 0xD1, "Ograve" to 0xD2, "Oacute" to 0xD3,
            "Ocirc" to 0xD4, "Otilde" to 0xD5, "Ouml" to 0xD6, "Oslash" to 0xD8, "Ugrave" to 0xD9,
            "Uacute" to 0xDA, "Ucirc" to 0xDB, "Uuml" to 0xDC, "Yacute" to 0xDD, "THORN" to 0xDE,
            "szlig" to 0xDF, "agrave" to 0xE0, "aacute" to 0xE1, "acirc" to 0xE2, "atilde" to 0xE3,
            "auml" to 0xE4, "aring" to 0xE5, "aelig" to 0xE6, "ccedil" to 0xE7, "egrave" to 0xE8,
            "eacute" to 0xE9, "ecirc" to 0xEA, "euml" to 0xEB, "igrave" to 0xEC, "iacute" to 0xED,
            "icirc" to 0xEE, "iuml" to 0xEF, "eth" to 0xF0, "ntilde" to 0xF1, "ograve" to 0xF2,
            "oacute" to 0xF3, "ocirc" to 0xF4, "otilde" to 0xF5, "ouml" to 0xF6, "oslash" to 0xF8,
            "ugrave" to 0xF9, "uacute" to 0xFA, "ucirc" to 0xFB, "uuml" to 0xFC, "yacute" to 0xFD,
            "thorn" to 0xFE, "yuml" to 0xFF,
        )
        for ((k, v) in latin) put(k, v.toChar().toString())
    }

    private val warnedEntities = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun unescape(s: String): String = Regex("""&(#[xX]?[0-9a-fA-F]+|\w+);""").replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                codepoint(body.drop(2).toIntOrNull(16)) ?: m.value
            body.startsWith("#") ->
                codepoint(body.drop(1).toIntOrNull()) ?: m.value
            else -> NAMED[body] ?: m.value.also {
                if (warnedEntities.add(body))
                    Log.w("epub", "unknown entity &$body; left literal — text fidelity reduced")
            }
        }
    }

    /**
     * The Windows-1252 characters authors write as `&#145;`..`&#151;` — curly
     * quotes, dashes, the bullet. HTML5 mandates this remap for numeric
     * references in 0x80..0x9F, and skipping it is why Adam's real shelf held
     * **14,315** C1 control code points (U+0092 x2362, U+0093/94 x10,577,
     * U+0097 x1345 ...) that NO font can draw — every one a tofu box in the
     * middle of a sentence (review 2026-09-03). Code points the range leaves
     * undefined stay U+FFFD, exactly as the spec says.
     */
    private val CP1252 = mapOf(
        0x80 to '\u20AC', 0x82 to '\u201A', 0x83 to '\u0192', 0x84 to '\u201E', 0x85 to '\u2026',
        0x86 to '\u2020', 0x87 to '\u2021', 0x88 to '\u02C6', 0x89 to '\u2030', 0x8A to '\u0160',
        0x8B to '\u2039', 0x8C to '\u0152', 0x8E to '\u017D', 0x91 to '\u2018', 0x92 to '\u2019',
        0x93 to '\u201C', 0x94 to '\u201D', 0x95 to '\u2022', 0x96 to '\u2013', 0x97 to '\u2014',
        0x98 to '\u02DC', 0x99 to '\u2122', 0x9A to '\u0161', 0x9B to '\u203A', 0x9C to '\u0153',
        0x9E to '\u017E', 0x9F to '\u0178',
    )

    /** A numeric reference to a string — out-of-range or surrogate code points
     *  degrade to U+FFFD instead of throwing the whole book away (one bad
     *  entity killed an entire load in review round 1). */
    private fun codepoint(cp: Int?): String? = when {
        cp == null -> null
        cp in 0xD800..0xDFFF -> "\uFFFD"          // a lone surrogate is not text
        cp in 0x80..0x9F -> (CP1252[cp] ?: '\uFFFD').toString()
        cp in 1 until 0x110000 -> String(Character.toChars(cp))
        else -> "\uFFFD"
    }

    /** Collapse markup whitespace but keep paragraph structure. */
    private fun normalize(s: String): String {
        val paragraphs = fold(s).replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n\\s*\n"))
            .map { it.replace(Regex("[ \t ]+"), " ").replace(Regex(" ?\n ?"), " ").trim() }
            .filter { it.isNotEmpty() }
        return paragraphs.joinToString("\n\n")
    }

    /**
     * Fold the characters NONE of the four locked §Type faces can draw onto
     * what they mean. Measured across the real 58-book shelf on 2026-09-03,
     * where Alegreya could not draw 14,365 code points of ordinary prose:
     *
     *  - **U+0080..U+009F (14,315x)** — C1 CONTROLS that are really
     *    Windows-1252 punctuation. Byte-level check: the files hold `C2 97`
     *    (the UTF-8 encoding of U+0097) exactly where an em dash belongs, next
     *    to correctly-encoded `E2 80 99` quotes in the same sentence — the
     *    books were transcoded cp1252-as-latin-1 before they ever reached us.
     *    C1 controls have no meaning in book text, so [CP1252] is the only
     *    reading; it is the same table HTML5 mandates for numeric references,
     *    applied to the characters as well as to `&#151;`.
     *  - **U+2011 NON-BREAKING HYPHEN (284x)** -> "-". Missing from Clear Sans,
     *    Fira Sans, Alegreya AND JetBrains Mono; it is a hyphen.
     *  - the **zero-width formatters** (U+200B..U+200F, U+2060, U+FEFF; 40x)
     *    are DROPPED: they are invisible by definition, so a '?' substitute
     *    would add junk the source never had.
     *
     * Everything else a face cannot draw keeps Draw.dynamic's visible '?' and
     * its one log line — fidelity loss is never silent.
     */
    private fun fold(s: String): String {
        if (s.none { needsFold(it) }) return s
        val out = StringBuilder(s.length)
        for (c in s) when {
            c in ZERO_WIDTH -> {}
            c == '\u2011' -> out.append('-')
            c.code in 0x80..0x9F -> out.append(CP1252[c.code] ?: '\uFFFD')
            else -> out.append(c)
        }
        if (foldWarned.compareAndSet(false, true)) {
            Log.i("epub", "folded characters no locked face can draw (mojibake C1 punctuation, " +
                "U+2011, zero-width formatters) \u2014 see Epub.fold")
        }
        return out.toString()
    }

    private fun needsFold(c: Char): Boolean =
        c.code in 0x80..0x9F || c == '\u2011' || c in ZERO_WIDTH

    private val foldWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    private val ZERO_WIDTH = setOf(
        '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u2060', '\uFEFF')
}
