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
            val raw = java.nio.file.Files.readString(path)
            return Book(path.fileName.toString().removeSuffix(".txt"), "", normalize(raw))
        }
        ZipFile(path.toFile()).use { zip ->
            fun read(name: String): String? {
                val e = zip.getEntry(name) ?: zip.getEntry(name.removePrefix("/")) ?: return null
                return zip.getInputStream(e).readBytes().toString(Charsets.UTF_8)
            }

            val container = read("META-INF/container.xml")
                ?: throw IllegalArgumentException("$path: no META-INF/container.xml — not an EPUB")
            val opfPath = Regex("""full-path="([^"]+)"""").find(container)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("$path: container.xml names no rootfile")
            val opf = read(opfPath) ?: throw IllegalArgumentException("$path: missing OPF $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val title = xmlText(opf, "dc:title") ?: path.fileName.toString().removeSuffix(".epub")
            val author = xmlText(opf, "dc:creator") ?: ""

            // manifest id -> href
            val items = HashMap<String, String>()
            for (m in Regex("""<item\b[^>]*>""").findAll(opf)) {
                val tag = m.value
                val id = Regex("""\bid="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: continue
                val href = Regex("""\bhref="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: continue
                items[id] = href
            }
            // spine order
            val spine = Regex("""<itemref\b[^>]*\bidref="([^"]+)"""").findAll(opf)
                .map { it.groupValues[1] }.toList()
            if (spine.isEmpty()) throw IllegalArgumentException("$path: OPF has an empty spine")

            val sb = StringBuilder()
            var missing = 0
            for (idref in spine) {
                val href = items[idref] ?: continue
                val full = if (opfDir.isEmpty()) href else "$opfDir/$href"
                val doc = read(urlDecode(full)) ?: read(full)
                if (doc == null) { missing++; continue }
                val t = htmlToText(doc)
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(t)
                }
            }
            if (missing > 0) Log.w("epub", "$path: $missing spine documents missing from the archive")
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

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /** XHTML -> text: scripts/styles dropped, block elements become paragraph
     *  or line breaks, inline markup becomes its text, entities decoded. */
    fun htmlToText(html: String): String {
        var s = html
        s = Regex("""<(script|style)\b[\s\S]*?</\1>""", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("""<!--[\s\S]*?-->""").replace(s, " ")
        // block-level boundaries -> paragraph breaks
        s = Regex("""</(p|div|h[1-6]|li|blockquote|tr|section|article)>""", RegexOption.IGNORE_CASE)
            .replace(s, "\n\n")
        s = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n\n* * *\n\n")
        s = Regex("""<[^>]+>""").replace(s, "")
        return unescape(s)
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "hellip" to "…", "shy" to "", "copy" to "©",
    )

    fun unescape(s: String): String = Regex("""&(#x?[0-9a-fA-F]+|\w+);""").replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.let { cp -> String(Character.toChars(cp)) } ?: m.value
            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.let { cp -> String(Character.toChars(cp)) } ?: m.value
            else -> NAMED[body] ?: m.value
        }
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
