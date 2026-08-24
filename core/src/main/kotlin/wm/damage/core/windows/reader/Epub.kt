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
    data class Book(val title: String, val author: String, val text: String)

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

            val sb = StringBuilder()
            var missing = 0
            for (idref in spine) {
                val href = items[idref]
                if (href == null) { missing++; continue }   // counted, not silent
                val doc = readByHref(::read, opfDir, href)
                if (doc == null) { missing++; continue }
                val t = htmlToText(doc)
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(t)
                }
            }
            if (missing > 0) Log.w("epub", "$path: $missing of ${spine.size} spine documents " +
                "could not be read — that text is MISSING from the book")
            if (sb.isBlank()) throw IllegalArgumentException("$path: spine extracted no text")
            return Book(title.trim(), author.trim(), normalize(sb.toString()))
        }
    }

    /** Just the metadata, cheaply (library scan). */
    fun meta(path: Path): Pair<String, String> = try {
        if (path.fileName.toString().lowercase().endsWith(".txt")) {
            path.fileName.toString().removeSuffix(".txt") to ""
        } else ZipFile(path.toFile()).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
                ?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
            val opfPath = container?.let { Regex("""full-path="([^"]+)"""").find(it)?.groupValues?.get(1) }
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

    /** Resolve a manifest href against the OPF directory: strip fragments,
     *  unescape entities, normalize ../ segments, try decoded and raw names. */
    private fun readByHref(read: (String) -> String?, opfDir: String, hrefRaw: String): String? {
        val href = unescape(hrefRaw).substringBefore('#')
        val joined = if (opfDir.isEmpty()) href else "$opfDir/$href"
        val parts = ArrayList<String>()
        for (seg in joined.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(seg)
        }
        val norm = parts.joinToString("/")
        return read(urlDecode(norm)) ?: read(norm)
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

    fun unescape(s: String): String = Regex("""&(#x?[0-9a-fA-F]+|\w+);""").replace(s) { m ->
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

    /** A numeric reference to a string — out-of-range or surrogate code points
     *  degrade to U+FFFD instead of throwing the whole book away (one bad
     *  entity killed an entire load in review round 1). */
    private fun codepoint(cp: Int?): String? = when {
        cp == null -> null
        cp in 0xD800..0xDFFF -> "\uFFFD"          // a lone surrogate is not text
        cp in 1 until 0x110000 -> String(Character.toChars(cp))
        else -> "\uFFFD"
    }

    /** Collapse markup whitespace but keep paragraph structure. */
    private fun normalize(s: String): String {
        val paragraphs = s.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n\\s*\n"))
            .map { it.replace(Regex("[ \t ]+"), " ").replace(Regex(" ?\n ?"), " ").trim() }
            .filter { it.isNotEmpty() }
        return paragraphs.joinToString("\n\n")
    }
}
