package wm.damage.core.windows.feed

import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * A small namespace-aware DOM helper over `javax.xml.parsers` — present on
 * both runtimes (FEED.md §2.7). Lookups are by LOCAL name, so `media:thumbnail`,
 * `slash:comments` and `dc:creator` resolve without binding prefixes. DTDs and
 * external entities are refused: a feed is untrusted input.
 */
object Xml {
    fun parse(bytes: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.isExpandEntityReferences = false
        f.isValidating = false
        for ((feature, on) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )) {
            try { f.setFeature(feature, on) } catch (e: Exception) { /* not every parser knows every feature */ }
        }
        try { f.isXIncludeAware = false } catch (e: Exception) { /* unsupported on some runtimes */ }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        return doc.documentElement ?: throw IllegalArgumentException("empty XML document")
    }

    fun local(n: Node): String = n.localName ?: n.nodeName.substringAfter(':')

    fun children(e: Element, local: String): List<Element> {
        val out = ArrayList<Element>()
        var c = e.firstChild
        while (c != null) {
            if (c.nodeType == Node.ELEMENT_NODE && local(c) == local) out.add(c as Element)
            c = c.nextSibling
        }
        return out
    }

    fun child(e: Element, local: String): Element? = children(e, local).firstOrNull()

    fun text(e: Element, local: String): String = child(e, local)?.textContent?.trim() ?: ""

    /** Every descendant with this local name, document order. */
    fun descendants(e: Element, local: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c.nodeType == Node.ELEMENT_NODE) {
                    if (local(c) == local) out.add(c as Element)
                    walk(c)
                }
                c = c.nextSibling
            }
        }
        walk(e)
        return out
    }

    fun attr(e: Element, name: String): String = e.getAttribute(name) ?: ""
}

/** Feed date forms: RFC 3339 / ISO offsets (Atom, dc:date), RFC 1123 (RSS
 *  2.0 pubDate), and a bare local stamp (WordPress' `2001-03-02T00:00:00`,
 *  taken as UTC). 0 when none parse — an unknown age is drawn as nothing. */
object FeedDates {
    fun parse(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0L
        try { return OffsetDateTime.parse(t).toInstant().toEpochMilli() } catch (e: Exception) { /* next form */ }
        try { return ZonedDateTime.parse(t).toInstant().toEpochMilli() } catch (e: Exception) { /* next form */ }
        try { return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (e: Exception) { /* next form */ }
        try { return LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (e: Exception) { /* next form */ }
        // "Tue, 09 Sep 2026 12:00:00 +0000" with a two-digit day is RFC 1123;
        // some feeds write a one-digit day, which that formatter refuses
        try {
            val fixed = t.replace(Regex("^([A-Za-z]{3}), (\\d) "), "$1, 0$2 ")
            return ZonedDateTime.parse(fixed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: Exception) { /* give up */ }
        return 0L
    }

    /** A calendar day as a UTC noon stamp (xkcd's day/month/year). */
    fun day(year: Int, month: Int, day: Int): Long =
        try { LocalDateTime.of(year, month, day, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (e: Exception) { 0L }
}
