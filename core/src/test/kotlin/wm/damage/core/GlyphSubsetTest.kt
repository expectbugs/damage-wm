package wm.damage.core

import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.comp.CachedText
import wm.damage.core.comp.GlyphAtlas
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.text.Face
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.text.Wrap
import wm.damage.core.wire.TextureCache

/**
 * A rasterizer whose glyphs DIFFER per character and per size and do not collapse into one record
 * (seeded noise), with the properties the subset pins need and no other rig had (`HANDOFF.md` §73):
 * a code the face does not cover ([uncovered]), glyphs whose ink runs two columns past their
 * advance when the HOST draws them ([overhang] — the cached image is the advance box, as on the
 * real rasterizers), and glyphs that are byte-identical to each other ([twins]: every character of
 * a twin group draws its first member's pixels).
 */
class NoisyText(
    private val uncovered: Set<Char> = emptySet(),
    private val overhang: Set<Char> = emptySet(),
    private val twins: List<String> = emptyList(),
) : TextRasterizer {
    private fun adv(font: FontSpec) = maxOf(6, font.sizePx / 2)
    private fun shape(ch: Char): Char = twins.firstOrNull { ch in it }?.first() ?: ch
    override fun measure(text: String, font: FontSpec): Int = text.length * adv(font)
    override fun metrics(font: FontSpec) = FontMetrics(font.sizePx, 4, font.sizePx + 6)
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        val a = adv(font)
        val h = font.sizePx + 4
        for ((i, ch) in text.withIndex()) {
            if (ch == ' ') continue
            val x0 = x + i * a
            val rnd = java.util.Random((shape(ch).code * 131L + font.sizePx) * 7919L + (if (font.bold) 17 else 0))
            for (yy in 0 until h) for (xx in 0 until a - 1) if (rnd.nextInt(5) < 2) surface[x0 + xx, y + yy] = level
            // ink past the advance: a solid two-column bar in the next character's box
            if (ch in overhang) for (yy in 2 until h - 2) { surface[x0 + a, y + yy] = level; surface[x0 + a + 1, y + yy] = level }
        }
    }
    override fun covers(text: String, font: FontSpec) = text.none { it in uncovered }
}

/** A rasterizer as the real ones are: fractional advances, so a string measures LESS than the sum
 *  of its characters each rounded up on its own (7.4 px a character: ten are 74, not 80). */
class FractionalText : TextRasterizer {
    private val inner = NoisyText()
    override fun measure(text: String, font: FontSpec): Int = Math.ceil(text.length * 7.4).toInt()
    override fun metrics(font: FontSpec) = inner.metrics(font)
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        for ((i, ch) in text.withIndex()) inner.draw(surface, x + Math.floor(i * 7.4).toInt(), y, ch.toString(), FontSpec(font.face, 12, font.bold), level)
    }
    override fun covers(text: String, font: FontSpec) = true
}

/** A rasterizer WITH pair kerning, as the phone's is (AWT's kerns are 0, so no desktop render can
 *  show a kern defect): 8 px a character, ink 6 px wide; "To" tucks two pixels, a vowel after
 *  anything but a vowel or a space tucks one. Latin-1. Records every string it is asked to draw. */
class KernedText : TextRasterizer {
    val drawn = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, String>>()
    override fun measure(text: String, font: FontSpec): Int {
        var w = 0; var prev: Char? = null
        for (ch in text) { prev?.let { w += kern(it, ch, font) }; prev = ch; w += 8 }
        return w
    }
    override fun metrics(font: FontSpec) = FontMetrics(12, 4, 16)
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        drawn.add(x to text)
        var px = x; var prev: Char? = null
        for (ch in text) {
            prev?.let { px += kern(it, ch, font) }
            prev = ch
            if (ch != ' ') surface.fillRect(px, y + 2, 6, 3 + ch.code % 9, level)
            px += 8
        }
    }
    override fun covers(text: String, font: FontSpec) = text.all { it.code in 32..255 && it.code != 127 }
    override fun kern(a: Char, b: Char, font: FontSpec): Int = when {
        a == 'T' && b == 'o' -> -2
        b in "aeiou" && a !in "aeiou " -> -1
        else -> 0
    }
}

/** A rasterizer that refuses what a real one cannot draw: an unpaired surrogate, or a string that
 *  starts with a combining mark (a mark with no letter under it). Records what it was asked for. */
class StrictText : TextRasterizer {
    private val inner = NoisyText()
    val drawn = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun check(text: String, what: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) throw IllegalArgumentException("$what: an unpaired high surrogate in '${text.map { it.code.toString(16) }}'")
                i += 2; continue
            }
            if (Character.isLowSurrogate(c)) throw IllegalArgumentException("$what: an unpaired low surrogate in '${text.map { it.code.toString(16) }}'")
            i++
        }
        if (text.isNotEmpty() && Character.getType(text.codePointAt(0)).toByte() == Character.NON_SPACING_MARK)
            throw IllegalArgumentException("$what: a combining mark with no letter before it")
    }
    override fun measure(text: String, font: FontSpec): Int { check(text, "measure"); return inner.measure(text.filter { Character.getType(it).toByte() != Character.NON_SPACING_MARK && !Character.isLowSurrogate(it) }, font) }
    override fun metrics(font: FontSpec) = inner.metrics(font)
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) { check(text, "draw"); drawn.add(text); inner.draw(surface, x, y, text.take(1), font, level) }
    override fun covers(text: String, font: FontSpec) = true
}

/**
 * `HANDOFF.md` §73 — a face is packed with the glyphs it draws and gains more in place. The pure
 * half: what the atlas packs, what it hands the wire, and what each lifecycle step leaves behind.
 * Every observable here is read from the packed IMAGE or the upload messages themselves (a table
 * entry decoded from the bytes, the messages replayed into a blank cache), never from the atlas's
 * own belief — a wrong entry written consistently on both sides is invisible to belief = glass.
 */
class GlyphSubsetTest {
    private val f18 = FontSpec(Face.SYSTEM, 18)
    private val f24 = FontSpec(Face.SYSTEM, 24)

    private fun codes(s: String) = BitSet().also { b -> s.forEach { b.set(it.code) } }

    /** The cache offset the packed image's table names for [ch], in bytes. */
    private fun tableEntry(a: GlyphAtlas, spec: FontSpec, ch: Char, content: ByteArray = a.contentForTest()): Int {
        val e = a.entry(spec) ?: a.pendingExt(spec)?.entry ?: error("no entry for $spec")
        val at = e.font.tableOffset + (ch.code - TextureCache.FIRST_CHAR) * 2
        val field = (content[at].toInt() and 0xFF) or ((content[at + 1].toInt() and 0xFF) shl 8)
        return if (a.v2) field * 4 else field
    }

    /** True when the record the IMAGE's table names for [ch] is exactly [img]'s bytes. */
    private fun names(a: GlyphAtlas, spec: FontSpec, ch: Char, img: TextureCache.Image, content: ByteArray = a.contentForTest()): Boolean {
        val off = tableEntry(a, spec, ch, content)
        val enc = img.encode()
        return off + enc.size <= content.size && content.copyOfRange(off, off + enc.size).contentEquals(enc)
    }

    /** Every packed code of [spec]'s entry (or [entry]) is named by the image's table; every other names the tofu. */
    private fun assertTableMatches(a: GlyphAtlas, spec: FontSpec, entry: GlyphAtlas.Entry = a.entry(spec)!!, content: ByteArray = a.contentForTest(), what: String = "") {
        for (i in entry.packed.indices) {
            val ch = (i + TextureCache.FIRST_CHAR).toChar()
            val at = entry.font.tableOffset + i * 2
            val field = (content[at].toInt() and 0xFF) or ((content[at + 1].toInt() and 0xFF) shl 8)
            val off = if (a.v2) field * 4 else field
            val enc = entry.images[i].encode()
            assertTrue(off + enc.size <= content.size && content.copyOfRange(off, off + enc.size).contentEquals(enc),
                "$what: the table entry for U+%04X names a record that is not its image (offset $off)".format(ch.code))
            assertEquals(off, entry.font.glyphOffsets[i], "$what: the entry's own offset for U+%04X".format(ch.code))
        }
    }

    /** Apply one mode-12 / mode-19 message to [cache] as the firmware does: plain copies, in order. */
    private fun apply(msg: ByteArray, cache: ByteArray): List<Pair<Int, Int>> {
        val v2 = msg[0].toInt() == 19
        assertTrue(v2 || msg[0].toInt() == 12, "a cache write is mode 12 or 19, not ${msg[0]}")
        val writes = ArrayList<Pair<Int, Int>>()
        var pos = 1
        while (pos < msg.size) {
            val off = ((msg[pos].toInt() and 0xFF) or ((msg[pos + 1].toInt() and 0xFF) shl 8)) * (if (v2) 4 else 1)
            val len = (msg[pos + 2].toInt() and 0xFF) or ((msg[pos + 3].toInt() and 0xFF) shl 8)
            assertTrue(len > 0, "no empty write")
            System.arraycopy(msg, pos + 4, cache, off, len)
            writes += off to len
            pos += 4 + len
        }
        assertTrue(writes.isNotEmpty(), "no empty message")
        return writes
    }

    private fun upload(a: GlyphAtlas, cache: ByteArray, maxMessage: Int = 3072): List<ByteArray> {
        val msgs = a.takeUpload(maxMessage)
        for (m in msgs) { apply(m, cache); a.acked() }
        return msgs
    }

    @Test
    fun aFaceIsPackedWithTheCodesAskedForAndEveryOtherEntryIsTheTofu() {
        for (v2 in booleanArrayOf(false, true)) {
            val a = GlyphAtlas(NoisyText(), v2 = v2, capacity = if (v2) 96 * 1024 else 65536)
            assertTrue(a.add(f18, codes("Row 12")))
            val e = a.entry(f18)!!
            assertEquals(6, e.packedCount, "R o w space 1 2")
            for (ch in "Row 12") assertTrue(e.packs(ch) && e.images[ch.code - 32] !== e.tofu, "'$ch' is packed with its own glyph")
            for (ch in "xyZ9~") assertTrue(!e.packs(ch) && e.images[ch.code - 32] === e.tofu, "'$ch' is not packed: its entry is the tofu")
            assertFalse(e.packs('é'), "é is not packed (and a v1 table has no entry for it at all)")
            assertFalse(e.packs("Row 13"), "a string with one unpacked code is not packed")
            assertTableMatches(a, f18, what = if (v2) "v2" else "v1")
            // the whole face costs many times this (the measure behind §73)
            val whole = GlyphAtlas(NoisyText(), v2 = v2, capacity = if (v2) 96 * 1024 else 65536)
            assertTrue(whole.add(f18, GlyphAtlas.allCodes(v2)))
            assertTrue(whole.used > 5 * a.used, "six glyphs ${a.used} B against the whole face ${whole.used} B")
        }
    }

    /** F2 of the design review: a code outside what a cached run may hold (DEL, the C1 controls, the
     *  soft hyphen) is never packed whatever the face drew, and a v1 table packs nothing past 126. */
    @Test
    fun aCodeNoRunMayHoldIsNeverPacked() {
        val drawn = codes("a\u007f\u0085­é")
        val v2 = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
        assertTrue(v2.add(f18, drawn))
        assertEquals("aé".toSet(), v2.entry(f18)!!.glyphs.keys)
        val v1 = GlyphAtlas(NoisyText())
        assertTrue(v1.add(f18, drawn))
        assertEquals(setOf('a'), v1.entry(f18)!!.glyphs.keys)
    }

    /** The extension itself, read from the bytes: the new records sit at the end, the table's entries
     *  for them are rewritten in place, no other entry or record moved, and nothing is live until
     *  the message carrying the patch is acked. */
    @Test
    fun aFaceGainsGlyphsInPlaceAndTheyAreLiveOnlyAfterTheirMessageIsAcked() {
        for (v2 in booleanArrayOf(false, true)) {
            val what = if (v2) "v2" else "v1"
            val a = GlyphAtlas(NoisyText(), v2 = v2, capacity = if (v2) 96 * 1024 else 65536)
            val cache = ByteArray(if (v2) 96 * 1024 else 65536)
            assertTrue(a.add(f18, codes("Row 12")))
            upload(a, cache)
            assertTrue(a.isAcked(f18))
            val before = a.entry(f18)!!
            val usedBefore = a.used
            val contentBefore = a.contentForTest()

            // an odd-indexed code, an even one and the table's last (v2) — every span shape
            val more = if (v2) "!\"ÿ" else "!\""
            val x = a.extend(f18, codes(more))
            assertTrue(x is GlyphAtlas.Extended.Queued, "$what: queued, got $x")
            val ext = (x as GlyphAtlas.Extended.Queued).ext
            assertTrue(a.entry(f18) === before, "$what: the live entry does not change at the extend")
            assertFalse(a.entry(f18)!!.packs('!'), "$what: not drawable from the cache yet")
            assertEquals(before.font.tableOffset, ext.entry.font.tableOffset, "$what: the table stays where it is")
            assertTrue(a.used > usedBefore, "$what: the records went at the end")
            // nothing below the old end moved but the table's own bytes
            val contentAfter = a.contentForTest()
            val table = before.font.tableOffset until before.font.tableOffset + before.font.glyphOffsets.size * 2
            for (i in 0 until usedBefore) if (i !in table) assertEquals(contentBefore[i], contentAfter[i], "$what: byte $i moved")
            assertTableMatches(a, f18, ext.entry, what = "$what, in the image")
            for (ch in "Row 12") assertEquals(before.font.glyphOffsets[ch.code - 32], ext.entry.font.glyphOffsets[ch.code - 32], "$what: '$ch' stays where it was")

            // one message: the appended bytes and the table spans together
            val msgs = a.takeUpload()
            assertEquals(1, msgs.size, "$what: an extension is one message")
            val writes = apply(msgs[0], cache)
            assertTrue(writes.size >= 2, "$what: the append and at least one table span: $writes")
            for ((off, len) in writes.drop(1)) {
                assertTrue(off >= table.first && off + len <= table.last + 1, "$what: span [$off,+$len) lies inside the table $table")
                if (v2) assertTrue(off % 4 == 0 && len % 4 == 0, "$what: whole 4-byte units: [$off,+$len)")
            }
            assertTrue(a.promote().isEmpty(), "$what: nothing is promoted before the ack")
            a.acked()
            assertEquals(listOf(ext), a.promote(), "$what: promoted on the ack")
            assertTrue(a.entry(f18) === ext.entry && a.entry(f18)!!.packs("Row 12!\""))
            assertTrue(a.isAcked(f18))
            assertNull(a.pendingExt(f18))
            // the glasses' cache, as the messages wrote it, IS the packed image
            assertContentEquals(a.contentForTest().copyOfRange(a.uploadStart, a.used), cache.copyOfRange(a.uploadStart, a.used), "$what: replayed uploads equal the image")
            assertTableMatches(a, f18, content = cache, what = "$what, on the glasses")
        }
    }

    /** A v1 table may start at an odd byte (a v1 record has no alignment): its spans are single
     *  entries relative to the table, never rounded to an absolute boundary that could leave it. */
    @Test
    fun aVOneSpanIsAnEntryOfItsOwnTableWhereverTheTableStarts() {
        val a = GlyphAtlas(NoisyText())
        // pack faces until one's table starts at an odd offset
        var spec = f18
        var n = 0
        while (true) {
            spec = FontSpec(Face.SYSTEM, 18 + n)
            assertTrue(a.add(spec, codes("ab")))
            if (a.entry(spec)!!.font.tableOffset % 2 == 1) break
            assertTrue(++n < 40, "no odd table offset in 40 faces")
        }
        val cache = ByteArray(65536)
        upload(a, cache)
        val e = a.entry(spec)!!
        assertTrue(a.extend(spec, codes("c")) is GlyphAtlas.Extended.Queued)
        val writes = a.takeUpload().flatMap { apply(it, cache) }
        val span = writes.last()
        assertEquals(e.font.tableOffset + ('c'.code - 32) * 2 to 2, span, "one 2-byte entry at its own place")
        a.acked(); a.promote()
        assertTableMatches(a, spec, content = cache, what = "v1 odd table")
    }

    /** One extension per face at a time; what turns up meanwhile goes in the next. */
    @Test
    fun aSecondExtensionWaitsForTheFirstsPromotion() {
        val a = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
        val cache = ByteArray(96 * 1024)
        assertTrue(a.add(f18, codes("ab")))
        upload(a, cache)
        assertTrue(a.extend(f18, codes("c")) is GlyphAtlas.Extended.Queued)
        assertTrue(a.extend(f18, codes("d")) === GlyphAtlas.Extended.Busy)
        assertEquals(codes("d"), a.missing(f18, codes("abcd")), "c is on its way; d is still missing")
        upload(a, cache); a.promote()
        assertTrue(a.extend(f18, codes("d")) is GlyphAtlas.Extended.Queued)
        upload(a, cache); a.promote()
        assertTrue(a.entry(f18)!!.packs("abcd"))
        assertTableMatches(a, f18, content = cache)
        assertTrue(a.extend(f18, codes("abcd")) === GlyphAtlas.Extended.None, "nothing left to gain")
    }

    /** A new glyph whose record ANOTHER face already put in the cache appends nothing — and the
     *  table patch must still go out and be counted (the read-back gate is owed by messages, a byte
     *  mark does not move), and once the cache is rewritten the face must not read as acked on its
     *  table alone: the record it now names sits above its table, in the other face's bytes. */
    @Test
    fun aGlyphTheCacheAlreadyHoldsIsAPatchOnlyExtension() {
        // bold draws the regular's pixels: the two faces share every glyph record
        val plain = NoisyText()
        val base = object : TextRasterizer by plain {
            override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) =
                plain.draw(surface, x, y, text, font.copy(bold = false), level)
        }
        val f = f18; val g = f18.copy(bold = true)
        val a = GlyphAtlas(base, v2 = true, capacity = 96 * 1024)
        val cache = ByteArray(96 * 1024)
        assertTrue(a.add(f, codes("ab")))            // F: its table low in the cache
        assertTrue(a.add(g, codes("cdx")))           // G: its records above F's table
        upload(a, cache)
        val usedBefore = a.used; val bytesBefore = a.ackedBytes; val msgsBefore = a.ackedMsgs
        val x = a.extend(f, codes("x"))              // F gains 'x' — G's record, byte for byte
        assertTrue(x is GlyphAtlas.Extended.Queued, "$x")
        val ext = (x as GlyphAtlas.Extended.Queued).ext
        assertEquals(usedBefore, a.used, "nothing was appended")
        assertEquals(a.entry(g)!!.font.glyphOffsets['x'.code - 32], ext.entry.font.glyphOffsets['x'.code - 32], "F names G's record")
        val msgs = a.takeUpload()
        assertEquals(1, msgs.size, "the patch still goes out, alone")
        apply(msgs[0], cache); a.acked()
        assertEquals(bytesBefore, a.ackedBytes, "no byte mark moved")
        assertEquals(msgsBefore + 1, a.ackedMsgs, "the message count did — what the read-back gate is owed by")
        a.promote()
        assertTrue(a.entry(f)!!.packs('x'))
        assertTableMatches(a, f, content = cache)
        // the cache is freed and rewritten: F's table is acked long before the record 'x' names
        a.forgetUpload()
        val tableEnd = a.entry(f)!!.font.tableOffset + 448
        val again = a.takeUpload(120)
        var i = 0
        while (a.ackedBytes < tableEnd) { a.acked(); i++ }
        assertTrue(a.ackedBytes < a.entry(g)!!.font.glyphOffsets['x'.code - 32], "G's record of 'x' is not acked yet")
        assertFalse(a.isAcked(f), "F's table is on the glasses and the record its 'x' names is not")
        while (i++ < again.size) a.acked()
        assertTrue(a.isAcked(f))
    }

    /** A glyph byte-identical to the tofu its entry already names changes no byte anywhere: live at once. */
    @Test
    fun aGlyphIdenticalToTheTofuIsLiveWithNothingToSend() {
        // a rasterizer whose '#' is exactly the hollow box the atlas builds for the tofu
        val base = object : TextRasterizer by NoisyText() {
            override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
                if (text != "#") return NoisyText().draw(surface, x, y, text, font, level)
                val w = measure("?", font); val h = font.sizePx + 4
                for (yy in 0 until h) for (xx in 0 until w) if (xx == 0 || xx == w - 1 || yy == 0 || yy == h - 1) surface[x + xx, y + yy] = level
            }
        }
        val a = GlyphAtlas(base, v2 = true, capacity = 96 * 1024)
        assertTrue(a.add(f18, codes("ab")))
        a.takeUpload().forEach { _ -> a.acked() }
        val used = a.used
        val x = a.extend(f18, codes("#"))
        assertTrue(x is GlyphAtlas.Extended.Live, "$x")
        assertEquals(used, a.used)
        assertTrue(a.takeUpload().isEmpty(), "nothing to send")
        assertTrue(a.entry(f18)!!.packs('#') && a.isAcked(f18))
    }

    /** An extension that does not fit writes NOTHING, is remembered, and is released by a retry. */
    @Test
    fun anExtensionThatDoesNotFitWritesNothing() {
        val a = GlyphAtlas(NoisyText())                       // 64 KiB
        val small = FontSpec(Face.SYSTEM, 60)                 // big glyphs: its whole table could never fit beside the rest
        assertTrue(a.add(small, codes("a")))
        var n = 0
        while (a.add(FontSpec(Face.LIST, 30 + n), GlyphAtlas.allCodes(false))) n++      // whole faces until one is refused
        assertTrue(n >= 1, "at least one whole face fits")
        var k = 0
        while (a.add(FontSpec(Face.READER, 30 + k), codes("b"))) k++                    // and single glyphs into what is left
        val used = a.used; val content = a.contentForTest()
        val x = a.extend(small, GlyphAtlas.allCodes(false))
        assertTrue(x is GlyphAtlas.Extended.Refused && x.first && x.need > x.free, "refused, said once, with the numbers: $x")
        assertEquals(used, a.used, "nothing was appended")
        assertContentEquals(content, a.contentForTest(), "and no table byte changed")
        assertNull(a.pendingExt(small))
        assertTrue(a.isExtRefused(small))
        assertFalse(a.entry(small)!!.packs('b'))
        val again = a.extend(small, GlyphAtlas.allCodes(false))
        assertTrue(again is GlyphAtlas.Extended.Refused && !again.first, "remembered: not priced or said again")
        assertTrue(a.takeUpload().isNotEmpty(), "the faces packed above still go up")
        a.unrefuseAll()
        assertFalse(a.isExtRefused(small), "a retry forgets the refusal")
    }

    /** A code the face has no glyph for is asked once, never wanted again, never an empty extension. */
    @Test
    fun aCodeTheFaceCannotDrawIsAskedOnce() {
        val a = GlyphAtlas(NoisyText(uncovered = setOf('é')), v2 = true, capacity = 96 * 1024)
        assertTrue(a.add(f18, codes("abé")))
        assertEquals("ab".toSet(), a.entry(f18)!!.glyphs.keys, "é has no glyph: its entry is the tofu, it is not packed")
        a.takeUpload().forEach { _ -> a.acked() }
        assertTrue(a.missing(f18, codes("abé")).isEmpty, "known to have no glyph: not missing")
        assertTrue(a.extend(f18, codes("é")) === GlyphAtlas.Extended.None)
        assertNull(a.pendingExt(f18), "no extension was made of nothing")
        assertTrue(a.takeUpload().isEmpty(), "and no message")
        // a coverable code still extends afterwards
        assertTrue(a.extend(f18, codes("éc")) is GlyphAtlas.Extended.Queued)
    }

    /** F2a of the design review: a face priced with one set (its glyphs rendered and kept) and
     *  packed later with a larger one packs the larger one — never "packed" over the tofu. */
    @Test
    fun aFacePricedSmallAndPackedLargerHoldsEveryGlyph() {
        val a = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
        val p1 = a.price(f18, codes("ab"))
        val p2 = a.price(f18, codes("abcdef"))
        assertTrue(p2 > p1, "priced by its set: $p1, $p2")
        assertTrue(a.add(f18, codes("abcdef")))
        val e = a.entry(f18)!!
        assertEquals("abcdef".toSet(), e.glyphs.keys)
        for (ch in "abcdef") assertTrue(e.images[ch.code - 32] !== e.tofu)
        assertTableMatches(a, f18)
    }

    /** Messages obey the bound they were asked for, an extension is acked only with the message
     *  that carries the LAST of its spans, and a half-patched table in between draws nothing new. */
    @Test
    fun aLargeExtensionSplitsAndIsAckedWithItsLastSpan() {
        val a = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
        val cache = ByteArray(96 * 1024)
        assertTrue(a.add(f18, codes("ab")))
        upload(a, cache, 200)
        val sixty = (0 until 60).map { ('A'.code + it).toChar() }.filter { GlyphAtlas.cacheableCode(it.code, true) }.joinToString("")
        val ext = (a.extend(f18, codes(sixty)) as GlyphAtlas.Extended.Queued).ext
        val msgs = a.takeUpload(200)
        assertTrue(msgs.size > 2, "split: ${msgs.size}")
        for ((i, m) in msgs.withIndex()) {
            assertTrue(m.size <= 200, "message $i is ${m.size} B")
            apply(m, cache)
            a.acked()
            if (i < msgs.size - 1) assertEquals(-1, ext.ackedAt, "not acked before its last span (message $i of ${msgs.size})")
        }
        assertTrue(ext.ackedAt > 0)
        a.promote()
        assertTableMatches(a, f18, content = cache)
        assertContentEquals(a.contentForTest().copyOfRange(4, a.used), cache.copyOfRange(4, a.used))
    }

    /** The link ended with the extension's message owed: after the rewind it goes again — the
     *  appended bytes from the acked mark AND the table patch — an ack of the message cut before
     *  the rewind completes nothing, and the message count is the atlas's, not the generation's. */
    @Test
    fun aRewindSendsTheExtensionAgainAndAnOldAckCompletesNothing() {
        val a = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
        val cache = ByteArray(96 * 1024)
        assertTrue(a.add(f18, codes("ab")))
        upload(a, cache)
        val msgs0 = a.ackedMsgs
        val ext = (a.extend(f18, codes("cd")) as GlyphAtlas.Extended.Queued).ext
        val old = a.uploadGen
        val lost = a.takeUpload()
        assertEquals(1, lost.size)
        assertTrue(a.takeUpload().isEmpty(), "handed out once")
        a.rewindToAcked()                                     // the link ended before its ack
        assertTrue(a.uploadGen != old, "a rewind is a new generation")
        assertEquals(msgs0, a.ackedMsgs, "the count of acked messages is untouched by a rewind")
        a.acked(old)                                          // the lost message's ack arrives late
        assertEquals(-1, ext.ackedAt, "an ack of the old generation completes nothing")
        assertTrue(a.promote().isEmpty())
        val again = a.takeUpload()
        assertEquals(1, again.size, "the extension goes again")
        assertContentEquals(lost[0], again[0], "the same bytes: a cache write is a plain copy")
        apply(again[0], cache); a.acked()
        assertEquals(msgs0 + 1, a.ackedMsgs)
        assertEquals(listOf(ext), a.promote())
        assertTableMatches(a, f18, content = cache)
    }

    /** A lapse freed the cache: the extension is the face now (nothing is live), and the face is NOT
     *  acked again when its table is — its new records sit above the table. */
    @Test
    fun aLapseFoldsTheExtensionAndTheFaceIsAckedOnlyWithItsLastRecord() {
        val a = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
        assertTrue(a.add(f18, codes("ab")))
        assertTrue(a.add(f24, codes("xy")))                   // something between F's table and its new records
        a.takeUpload().forEach { _ -> a.acked() }
        val ext = (a.extend(f18, codes("cd")) as GlyphAtlas.Extended.Queued).ext
        a.forgetUpload()
        assertNull(a.pendingExt(f18), "folded")
        assertTrue(a.entry(f18) === ext.entry && a.entry(f18)!!.packs("abcd"))
        assertFalse(a.isAcked(f18))
        val tableEnd = ext.entry.font.tableOffset + ext.entry.font.glyphOffsets.size * 2
        // re-upload in small messages; stop acking just past F's table
        val msgs = a.takeUpload(120)
        var i = 0
        while (a.ackedBytes < tableEnd) { a.acked(); i++ }
        assertTrue(a.ackedBytes < a.used, "the upload is not complete")
        assertFalse(a.isAcked(f18), "its table is acked (${a.ackedBytes} >= $tableEnd) and its new records are not: not on the glasses")
        while (i++ < msgs.size) a.acked()
        assertTrue(a.isAcked(f18))
        val cache = ByteArray(96 * 1024)
        for (m in msgs) apply(m, cache)
        assertTableMatches(a, f18, content = cache)
    }

    /** F1 of the design review: a repack rebuilds every kept face at new offsets, so an extension on
     *  its way is folded in BEFORE the rebuild — folded after, the old layout's table offset would
     *  stand over the rebuilt entry and an evicted face would come back. */
    @Test
    fun aRepackFoldsAnExtensionBeforeItRebuilds() {
        fun fresh(): Pair<GlyphAtlas, GlyphAtlas.Ext> {
            val a = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
            assertTrue(a.add(f24, codes("xyz")))               // B first: A sits above it
            assertTrue(a.add(f18, codes("ab")))
            a.takeUpload().forEach { _ -> a.acked() }
            return a to (a.extend(f18, codes("cd")) as GlyphAtlas.Extended.Queued).ext
        }
        // keep A (drop B): A moves down; its table and all four glyphs must be where the new image says
        val (a, ext) = fresh()
        val oldTable = ext.entry.font.tableOffset
        a.repack(listOf(f18), emptyList())
        assertNull(a.pendingExt(f18))
        val e = a.entry(f18)!!
        assertTrue(e.packs("abcd"), "the extension's glyphs came through the repack")
        assertTrue(e.font.tableOffset != oldTable, "the face moved: ${e.font.tableOffset}")
        assertTableMatches(a, f18, what = "after the repack")
        assertFalse(a.isAcked(f18), "nothing of the new layout is acked")
        val cache = ByteArray(96 * 1024)
        upload(a, cache)
        assertTrue(a.isAcked(f18))
        assertTableMatches(a, f18, content = cache, what = "after the repack, on the glasses")
        // keep B (drop A): A is gone — entry, extension and all
        val (b, _) = fresh()
        b.repack(listOf(f24), emptyList())
        assertFalse(b.has(f18), "the evicted face did not come back")
        assertNull(b.pendingExt(f18))
        assertTrue(b.pendingExts().isEmpty())
    }

    /** The recorder's half: a run holds only what the ENTRY packs, the rest is the host's own draw
     *  at its own advance, and the face's drawn characters are noted once each. */
    @Test
    fun theRecorderSplitsOnTheEntryAndNotesWhatTheFaceDrew() {
        for (v2 in booleanArrayOf(false, true)) {
            val base = NoisyText()
            val ct = CachedText(base)
            val atlas = GlyphAtlas(base, v2 = v2, capacity = if (v2) 96 * 1024 else 65536)
            ct.atlas = atlas
            assertTrue(atlas.add(f18, codes("abc")))
            ct.live = setOf(f18)
            val g = Gray8(640, 480)
            ct.target = g
            val want0 = ct.wantVersion
            ct.draw(g, 40, 40, "abXc", f18, 255)
            assertEquals(listOf("ab", "c"), ct.frameDraws().map { it.text }, "two runs around the host's X")
            assertEquals(listOf(0, CachedText.HOST_OVERHANG_PX), ct.frameDraws().map { it.keep }, "the run after the host's character keeps its first columns")
            assertEquals(want0 + 1, ct.wantVersion, "one cue for the string's new characters")
            assertTrue(ct.charsOf(f18).get('X'.code))
            // X's pixels are the host's own glyph, not the hollow box
            val host = Gray8(640, 480).also { base.draw(it, 40 + 2 * 9, 40, "X", f18, 255) }
            for (y in 40 until 40 + 22) for (x in 40 + 18 until 40 + 27) assertEquals(host[x, y], g[x, y], "X at ($x,$y)")
            assertEquals(base.measure("abXc", f18), ct.measure("abXc", f18), "X measures as itself")
            // drawn again: no new cue, and the count of what subset packing left to the host moves
            val unpacked = ct.unpackedDrawn
            ct.endFrame()
            ct.draw(g, 40, 40, "abXc", f18, 255)
            assertEquals(want0 + 1, ct.wantVersion, "a character already noted is no cue")
            assertEquals(unpacked + 1, ct.unpackedDrawn)
        }
    }

    /** The compositor's own guard: a recorded run with a code the entry in force at the assemble
     *  does not pack is a named miss, never a draw (the entry is read at emit time, and a record can
     *  outlive the atlas it was made under). Without the guard the same frame is refused by the
     *  proof instead — pixels either way, which is why the pin reads the reason, not the glass. */
    @Test
    fun aRunTheEntryDoesNotPackIsANamedMissAtTheAssemble() {
        val base = NoisyText()
        val ct = CachedText(base)
        val comp = wm.damage.core.comp.Compositor()
        comp.requestKeyframe()
        kotlin.test.assertNotNull(comp.assembleFlush(5))
        val with = GlyphAtlas(base); assertTrue(with.add(f18, codes("abX")))
        val without = GlyphAtlas(base); assertTrue(without.add(f18, codes("ab")))
        ct.atlas = with; ct.live = setOf(f18); ct.target = comp.composed
        comp.cachedText = ct
        ct.draw(comp.composed, 200, 200, "abX", f18, 255)
        assertEquals(listOf("abX"), ct.frameDraws().map { it.text })
        comp.damage(wm.damage.core.geom.Rect(200, 200, 28, 22))
        ct.atlas = without                                  // the atlas in force at the assemble lacks X
        val a = kotlin.test.assertNotNull(comp.assembleFlush(5))
        assertTrue("glyph-not-packed" in comp.cacheMissSummary(), "the miss is named: '${comp.cacheMissSummary()}'")
        assertTrue(a.ops.none { it is wm.damage.core.transport.DisplayOp.DrawText }, "and nothing was drawn through the cache")
    }

    /** What a face is packed with: what it drew, all ten digits once it drew one, and the space. */
    @Test
    fun aFaceIsPackedWithWhatItDrewPlusTheDigitsAndTheSpace() {
        val ct = CachedText(NoisyText())
        val g = Gray8(640, 480); ct.target = g
        ct.draw(g, 0, 0, "Row", f18, 255)
        assertEquals(codes("Row "), ct.codesFor(f18), "no digit drawn: no digits")
        ct.draw(g, 0, 0, "7", f18, 255)
        assertEquals(codes("Row 0123456789"), ct.codesFor(f18), "one digit drawn: all ten")
        assertEquals(codes(" "), ct.codesFor(f24), "a face that drew nothing: the space")
        // the characters outlive a session's clearSeen; the frame stamps outlive a KEPT one only
        ct.endFrame(); ct.endFrame()
        ct.draw(g, 0, 0, "x", f18, 255)
        val stamp = ct.lastUseOf(f18)
        assertTrue(stamp > 0)
        ct.clearSeen(keepUse = true)
        assertEquals(stamp, ct.lastUseOf(f18), "a kept atlas keeps what was in use")
        assertTrue(ct.seenSpecs().isEmpty())
        ct.clearSeen()
        assertEquals(0L, ct.lastUseOf(f18))
        assertTrue(ct.charsOf(f18).get('R'.code), "what a face draws is content, not session state")
    }

    /** Running text closes over the capitals — thirteen distinct lower-case letters AND eight
     *  distinct capitals drawn — and a label face does not (a book's first 25 notches gained
     *  A M B F R Y N T V W O S H one cache write and one read-back each, on the simulator). */
    @Test
    fun runningTextIsPackedWithEveryCapitalAndALabelFaceIsNot() {
        val ct = CachedText(NoisyText())
        val g = Gray8(640, 480); ct.target = g
        val lower13 = "abcdefghijklm"
        fun upper(set: BitSet) = set.get('A'.code, 'Z'.code + 1).cardinality()
        ct.draw(g, 0, 0, lower13 + "ABCDEFG", f18, 255)                  // 13 lower, 7 capitals
        assertEquals(7, upper(ct.codesFor(f18)), "seven capitals drawn: not yet prose")
        ct.draw(g, 0, 0, "H", f18, 255)                                  // the eighth
        assertEquals(26, upper(ct.codesFor(f18)), "thirteen lower-case letters and eight capitals: every capital")
        assertEquals(13, ct.codesFor(f18).get('a'.code, 'z'.code + 1).cardinality(), "and nothing else is closed over: the lower case is what was drawn")
        assertFalse(ct.codesFor(f18).get('0'.code), "no digit drawn: no digits")
        assertEquals(8, upper(ct.charsOf(f18)), "what the face DREW is unchanged — the closure is the packing's")
        // a label face: all capitals and few lower-case letters (MAIN, SETTINGS, a header)
        ct.draw(g, 0, 0, "ABCDEFGHIJKLMNOP abcdefghijkl", f24, 255)      // 16 capitals, 12 lower
        assertEquals(16, upper(ct.codesFor(f24)), "twelve lower-case letters: a label, not prose")
        // and prose with few capitals yet
        val f20 = FontSpec(Face.SYSTEM, 20)
        ct.draw(g, 0, 0, "the quick brown fox jumps over the lazy dog", f20, 255)
        assertEquals(0, upper(ct.codesFor(f20)), "no capital drawn: none packed")
    }

    /** The per-face record of drawn characters is bounded by last use: the 257th face drops the
     *  one drawn longest ago, and a face drawn again is not that one. */
    @Test
    fun theDrawnCharactersOfAtMost256FacesAreKeptByLastUse() {
        val ct = CachedText(NoisyText())
        val g = Gray8(640, 480); ct.target = g
        val specs = (0..CachedText.MAX_CHAR_SPECS).map { FontSpec(Face.SYSTEM, 8 + it) }      // 257 faces
        for (sp in specs.dropLast(1)) ct.draw(g, 0, 0, "a", sp, 255)
        assertTrue(specs.dropLast(1).all { ct.charsOf(it).get('a'.code) }, "256 faces: all kept")
        ct.draw(g, 0, 0, "b", specs[0], 255)                             // the first face is drawn again
        ct.draw(g, 0, 0, "a", specs.last(), 255)                         // the 257th
        assertTrue(ct.charsOf(specs[1]).isEmpty, "the face drawn longest ago is forgotten")
        assertEquals(codes("ab"), ct.charsOf(specs[0]), "the face drawn again is not the eldest")
        assertTrue(ct.charsOf(specs.last()).get('a'.code) && ct.charsOf(specs[2]).get('a'.code))
        assertEquals(codes(" "), ct.codesFor(specs[1]), "a forgotten face packs like a new one")
    }

    /** The entry's own check: a table entry that holds a glyph and is not marked packed (or the
     *  reverse) is a hollow box no proof can see — refused where the entry is made. */
    @Test
    fun anEntryWhoseFlagsImagesOrBoundsDisagreeIsRefused() {
        val a = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
        assertTrue(a.add(f18, codes("ab")))
        val e = a.entry(f18)!!
        fun make(images: Array<TextureCache.Image> = e.images, glyphs: Map<Char, TextureCache.Image> = e.glyphs,
            packed: BooleanArray = e.packed, needEnd: Int = e.needEnd) =
            GlyphAtlas.Entry(e.font, images, e.lineH, glyphs, e.tofu, e.bytes, packed, needEnd)
        make()                                                           // the entry as built passes
        val c = 'c'.code - TextureCache.FIRST_CHAR
        val b = 'b'.code - TextureCache.FIRST_CHAR
        val del = 0x7F - TextureCache.FIRST_CHAR
        val glyphB = e.images[b]
        assertFailsWith<LintError>("marked packed, holds the tofu") { make(packed = e.packed.copyOf().also { it[c] = true }) }
        assertFailsWith<LintError>("holds a glyph, not marked packed") { make(images = e.images.copyOf().also { it[c] = glyphB }) }
        assertFailsWith<LintError>("a glyph and a flag with no entry in the glyph map") {
            make(images = e.images.copyOf().also { it[c] = glyphB }, packed = e.packed.copyOf().also { it[c] = true })
        }
        assertFailsWith<LintError>("a glyph in the map the table does not hold") { make(glyphs = e.glyphs + ('c' to glyphB)) }
        assertFailsWith<LintError>("a code no cached run may hold") {
            make(images = e.images.copyOf().also { it[del] = glyphB }, packed = e.packed.copyOf().also { it[del] = true }, glyphs = e.glyphs + ('\u007f' to glyphB))
        }
        assertFailsWith<LintError>("needEnd below the table's end") { make(needEnd = e.font.tableOffset + e.font.glyphOffsets.size * 2 - 1) }
        assertFailsWith<LintError>("a flag array of another size") { make(packed = BooleanArray(96)) }
    }

    /** A message size that cannot hold one aligned unit is refused BEFORE anything is cut: thrown
     *  part-way, the chunk ends already recorded stood, and every later ack moved the watermark
     *  to a stale end (a v1 table span needs 2 B behind 5 B of headers; at 6 B the appended bytes
     *  were cut one per message and the span then had no room). */
    @Test
    fun aMessageSizeThatHoldsNoUnitIsRefusedBeforeAnythingIsCut() {
        for (v2 in booleanArrayOf(false, true)) {
            val a = GlyphAtlas(NoisyText(), v2 = v2, capacity = if (v2) 96 * 1024 else 65536)
            val cache = ByteArray(if (v2) 96 * 1024 else 65536)
            assertTrue(a.add(f18, codes("ab")))
            upload(a, cache)
            val ext = (a.extend(f18, codes("cd")) as GlyphAtlas.Extended.Queued).ext
            val acked0 = a.ackedBytes
            for (size in 0..8) assertFailsWith<LintError>("a $size B message") { a.takeUpload(size) }
            // nothing was cut, nothing recorded: the extension goes out whole and ITS ack is the one that completes it
            val msgs = a.takeUpload()
            assertEquals(1, msgs.size, "the extension is still one message")
            assertEquals(acked0, a.ackedBytes)
            apply(msgs[0], cache); a.acked()
            assertEquals(a.used, a.ackedBytes, "the one ack moved the watermark to the end, not to a stale chunk end")
            assertTrue(ext.ackedAt > 0, "and completed the extension")
            assertEquals(listOf(ext), a.promote())
            assertTableMatches(a, f18, content = cache, what = if (v2) "v2" else "v1")
            // the smallest size that is taken carries everything, in units
            assertTrue(a.extend(f18, codes("e")) is GlyphAtlas.Extended.Queued)
            val small = a.takeUpload(9)
            assertTrue(small.size > 3 && small.all { it.size <= 9 }, "${small.size} messages of at most 9 B")
            for (m in small) { apply(m, cache); a.acked() }
            a.promote()
            assertTableMatches(a, f18, content = cache, what = "at 9 B")
        }
    }

    /** `HANDOFF.md` §73: with the cache's metric in force a string measures the same whether its
     *  face is pixels, live, or gaining glyphs — so a page wrapped before its face was uploaded
     *  still fits when the face goes live. Without it the wrap is the host's whole-string measure
     *  and the same lines measure wider the moment the face is live: `Draw.fit` cut them. */
    @Test
    fun withTheCacheMetricAWrapMadeBeforeAFaceIsLiveStillFitsAfter() {
        val text = "It was the best of times it was the worst of times it was the age of wisdom it was the age of " +
            "foolishness it was the epoch of belief it was the epoch of incredulity it was the season of Light"
        val width = 300
        fun widest(ct: CachedText, lines: List<String>) = lines.maxOf { ct.measure(it, f18) }
        for (metric in booleanArrayOf(true, false)) {
            val base = FractionalText()
            val ct = CachedText(base)
            ct.cacheMetric = metric
            val atlas = GlyphAtlas(base, v2 = true, capacity = 96 * 1024)
            ct.atlas = atlas
            val before = ct.measure("abcdefghij", f18)
            val lines = Wrap.wrap(text, f18, ct, width)                  // wrapped while the face is pixels
            assertTrue(lines.size > 3 && widest(ct, lines) <= width, "the wrap fits as made")
            assertTrue(atlas.add(f18, codes("It was the")))              // the face goes live with a FEW of its glyphs
            ct.live = setOf(f18)
            val live = ct.measure("abcdefghij", f18)
            val wideLive = widest(ct, lines)
            assertTrue(atlas.extend(f18, codes("bfwisdomage")) is GlyphAtlas.Extended.Queued)
            atlas.takeUpload().forEach { _ -> atlas.acked() }
            assertEquals(1, atlas.promote().size)                        // and gains more
            val extended = ct.measure("abcdefghij", f18)
            if (metric) {
                assertEquals(80, before, "ten characters at the cache's metric: each advance rounded up on its own")
                assertEquals(before, live, "the same measure once the face is live")
                assertEquals(before, extended, "and after it gained glyphs")
                assertTrue(wideLive <= width && widest(ct, lines) <= width, "every line wrapped before the face was live still fits ($wideLive of $width)")
            } else {
                // the state the metric replaces, pinned so this test cannot pass on a rasterizer that hides it
                assertEquals(74, before, "the host's whole-string measure while the face is pixels")
                assertEquals(80, live, "the per-glyph sum once it is live")
                assertTrue(wideLive > width, "and a line wrapped at the first is past its width at the second ($wideLive of $width)")
            }
        }
    }

    /** What subset packing left to the host is counted where the character goes out as PIXELS: on
     *  the target and on a slide's strip — not on a strip rendered for staging (the relay that
     *  keeps no record), whose pixels go up inside a cache write. */
    @Test
    fun aHostDrawnCharacterIsCountedOnTheTargetAndASlideStripNotOnAStagedStrip() {
        val base = NoisyText()
        val ct = CachedText(base)
        val atlas = GlyphAtlas(base, v2 = true, capacity = 96 * 1024)
        ct.atlas = atlas
        assertTrue(atlas.add(f18, codes("abc")))
        ct.live = setOf(f18)
        val g = Gray8(640, 480); ct.target = g
        val tmp = Gray8(640, 48)
        val n0 = ct.unpackedDrawn
        ct.via(tmp, 0, 100, Rect(0, 0, 0, 0)) { ct.draw(tmp, 8, 8, "abXc", f18, 255) }       // a staged strip
        assertEquals(n0, ct.unpackedDrawn, "a strip rendered for staging counts nothing")
        assertTrue(ct.charsOf(f18).get('X'.code), "but its characters are noted: the face can gain them")
        ct.via(tmp, 0, 100, Rect(0, 100, 640, 48)) { ct.draw(tmp, 8, 8, "abXc", f18, 255) }   // a slide's strip
        assertEquals(n0 + 1, ct.unpackedDrawn, "a slide's strip is on its way to the panel as pixels")
        ct.draw(g, 8, 8, "abXYc", f18, 255)
        assertEquals(n0 + 3, ct.unpackedDrawn, "and the target")
        ct.draw(g, 8, 8, "ab→c", f18, 255)
        assertEquals(n0 + 3, ct.unpackedDrawn, "a code no run may hold is not subset packing's cost")
    }

    /** The journal's submit line carries the two fields §73 added and stays one valid JSON object
     *  (`tools/journal_report.py` parses every line; a bad one would cost the report its flush). */
    @Test
    fun theSubmitLineCarriesTheUnpackedCountAndTheInputFlagAsValidJson() {
        val dir = java.nio.file.Files.createTempDirectory("journal-submit")
        try {
            val path = dir.resolve("journal.jsonl")
            val ops = listOf(wm.damage.core.transport.DisplayOp.CacheWrite2(byteArrayOf(19, 0, 0, 4, 0, 1, 2, 3, 4)))
            wm.damage.core.comp.Journal(path).use { j ->
                j.flushSubmitted(7, 3, ops, "notch \"down\"", "pump", wm.damage.core.comp.Journal.Timing(unpacked = 5, input = 1))
                j.flushSubmitted(8, 3, ops, "ATLAS")
            }
            val lines = java.nio.file.Files.readAllLines(path).map { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
            assertEquals(2, lines.size)
            fun int(o: kotlinx.serialization.json.JsonObject, k: String) = (o.getValue(k) as kotlinx.serialization.json.JsonPrimitive).content.toLong()
            assertEquals(5L, int(lines[0], "unpacked")); assertEquals(1L, int(lines[0], "in"))
            assertEquals(-1L, int(lines[1], "unpacked"), "a flush that is not the pump's says so"); assertEquals(-1L, int(lines[1], "in"))
            assertEquals(7L, int(lines[0], "id"))
            assertEquals(1, (lines[0].getValue("ops") as kotlinx.serialization.json.JsonArray).size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** The rightmost inked column of rows [y0, y1) of [g], or -1. */
    private fun rightmostInk(g: Gray8, y0: Int, y1: Int): Int {
        var r = -1
        for (y in y0 until y1) for (x in 0 until g.w) if (g[x, y] != 0 && x > r) r = x
        return r
    }

    /**
     * `HANDOFF.md` §73, from its review: with the cache's metric in force, what is DRAWN is laid out
     * as it is measured in every state of the face — not live (the host draws each unit at the
     * cache's pen position), live and fully packed, live with a character the host still draws
     * (the pair kerns either side of it are kept). Until then a face that was not live was drawn
     * whole by the host, narrower than it measured, and a host-drawn character inside a live face
     * lost its two kerns: a caret or a styled run placed at `measure` of the text before it sat
     * off its end, and text moved when a face went live. On a KERNING rasterizer, which no other
     * gate of the metric has. Mutations watched: the not-live draw handed to the host whole; the
     * kern before a unit the entry does not pack dropped.
     */
    @Test
    fun whatIsDrawnIsLaidOutAsItIsMeasuredInEveryStateOfTheFace() {
        val text = "ToTo"
        val states = listOf("not live", "live, o not packed", "live, all packed")
        val inkEnd = ArrayList<Int>(); val runs = ArrayList<List<Pair<Int, String>>>()
        for (state in states) {
            val base = KernedText()
            val ct = CachedText(base)
            ct.cacheMetric = true
            val g = Gray8(640, 480); ct.target = g
            if (state != "not live") {
                val atlas = GlyphAtlas(base, v2 = true, capacity = 96 * 1024)
                ct.atlas = atlas
                assertTrue(atlas.add(f18, codes(if (state == "live, all packed") "To" else "T")))
                ct.live = setOf(f18)
            }
            assertEquals(28, ct.measure(text, f18), "$state: four advances of 8 and two kerns of -2")
            base.drawn.clear()
            ct.draw(g, 40, 40, text, f18, 255)
            inkEnd += rightmostInk(g, 40, 60)
            runs += ct.frameDraws().map { it.x to it.text }
            // T at 40, o at 46 (tucked two), T at 54, o at 60: the last o's ink ends at column 65
            assertEquals(65, inkEnd.last(), "$state: the ink ends where the metric says the string does, less the last glyph's side bearing")
            assertTrue(inkEnd.last() < 40 + ct.measure(text, f18), "$state: nothing is drawn past the measured width")
            when (state) {
                "not live" -> assertEquals(listOf(40 to "T", 46 to "o", 54 to "T", 60 to "o"), base.drawn.toList(), "the host draws each unit at the cache's pen position")
                "live, o not packed" -> {
                    assertEquals(listOf(40 to "T", 54 to "T"), runs.last(), "the cached runs sit where the metric puts them")
                    assertEquals(listOf(46 to "o", 60 to "o"), base.drawn.filter { it.second == "o" }, "and so do the host's characters, kerns kept")
                    assertEquals(listOf(CachedText.HOST_OVERHANG_PX, CachedText.HOST_OVERHANG_PX), ct.frameDraws().map { it.keepEnd }, "a run before a host-drawn character keeps its last columns as composed")
                }
                else -> assertEquals(listOf(40 to "ToTo"), runs.last(), "one run, its kerns inside it")
            }
        }
        assertEquals(1, inkEnd.distinct().size, "the same pixels' extent in all three states: $inkEnd")
        // and off: the host's own layout, whole
        val base = KernedText(); val ct = CachedText(base); val g = Gray8(640, 480); ct.target = g
        ct.draw(g, 40, 40, text, f18, 255)
        assertEquals(listOf(40 to text), base.drawn.toList(), "with the row off a face that is not live is the host's, whole")
    }

    /** The same on fractional advances (the cut-line case): drawn unit by unit, a face that is not
     *  live is as wide as it measures; spaces are positions, not draws. */
    @Test
    fun aFaceThatIsNotLiveIsDrawnAsWideAsItMeasures() {
        val calls = ArrayList<Pair<Int, String>>()
        val frac = FractionalText()
        val base = object : TextRasterizer by frac {
            override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) { calls.add(x to text); frac.draw(surface, x, y, text, font, level) }
        }
        val ct = CachedText(base)
        val g = Gray8(640, 480); ct.target = g
        ct.cacheMetric = true
        ct.draw(g, 100, 40, "abcde fghi", f18, 255)
        assertEquals(180, 100 + ct.measure("abcde fghi", f18))
        assertEquals(180, calls.maxOf { it.first + base.measure(it.second, f18) }, "the last unit ends where the measure does: $calls")
        assertEquals("abcdefghi", calls.joinToString("") { it.second }, "one draw per character, none for the space")
        assertEquals((0 until 10).filter { it != 5 }.map { 100 + 8 * it }, calls.map { it.first }, "each at the cache's pen position")
    }

    /**
     * A unit of text the host must be handed WHOLE: both halves of a surrogate pair, a letter with
     * its combining mark. A live face's text went to the host one UTF-16 unit at a time (older
     * code; the cache metric then measured that way for every face): a pair arrived as two
     * unpaired surrogates and a mark was drawn with no letter under it. Mutation watched: the
     * unit cut back to one UTF-16 unit.
     */
    @Test
    fun aSurrogatePairAndALetterWithItsMarkGoToTheHostWhole() {
        val pizza = String(Character.toChars(0x1F355))
        val eAcute = "é"
        for (live in booleanArrayOf(true, false)) {
            val base = StrictText()
            val ct = CachedText(base)
            ct.cacheMetric = true
            val g = Gray8(640, 480); ct.target = g
            if (live) {
                val atlas = GlyphAtlas(base, v2 = true, capacity = 96 * 1024)
                ct.atlas = atlas
                assertTrue(atlas.add(f18, codes("abe")))
                ct.live = setOf(f18)
            }
            val what = if (live) "live" else "not live"
            val text = "ab${pizza}ab${eAcute}b"
            val w = ct.measure(text, f18)                          // throws on the unfixed tree: a lone surrogate reaches the host
            base.drawn.clear()
            ct.draw(g, 40, 40, text, f18, 255)
            assertTrue(pizza in base.drawn, "$what: the pair reached the host whole: ${base.drawn.map { d -> d.map { it.code.toString(16) } }}")
            assertTrue(eAcute in base.drawn, "$what: the letter and its mark reached the host together (e is packed: alone it would be a cached glyph with a mark drawn beside it)")
            assertTrue(base.drawn.none { it.length == 1 && (Character.isSurrogate(it[0]) || it[0] == '́') }, "$what: no half was drawn alone")
            val adv = base.measure("a", f18)
            assertEquals(5 * adv + base.measure(pizza, f18) + base.measure(eAcute, f18), w, "$what: the measure is the same walk")
            if (live) assertEquals(listOf("ab", "ab", "b"), ct.frameDraws().map { it.text }, "the cached runs stop at each host unit; e before a mark is not one of them")
        }
    }

    /** The metric's contract — which codes a run may hold, so which pairs kern — is the shell's to
     *  set and does not follow the atlas object: it read "v2" with no atlas and "v1" once a
     *  contract-1 atlas existed, so accented text measured a kern wider after the atlas was built,
     *  with no layout re-derived (and narrower again at every reset). */
    @Test
    fun theMetricsContractDoesNotFollowTheAtlasObject() {
        val base = KernedText()
        val ct = CachedText(base)
        ct.cacheMetric = true
        val text = "caféa x"
        val m0 = ct.measure(text, f18)
        ct.atlas = GlyphAtlas(base)                               // a contract-1 atlas appears
        val m1 = ct.measure(text, f18)
        ct.atlas = null                                           // and goes (a reset)
        assertEquals(listOf(m0, m0), listOf(m1, ct.measure(text, f18)), "the measure does not move with the atlas")
        ct.metricV2 = false                                       // the shell's word: this session is contract 1
        assertEquals(m0 + 1, ct.measure(text, f18), "on contract 1 the accent is the host's: no kern beside it (kern(é, a) = -1 here)")
    }

    /** `glyph-not-packed` on the contract-2 emit path too (the daily one): the twin of the pin
     *  above, which ran the v1 path only. Mutation watched: the guard removed from `emitCachedV2`. */
    @Test
    fun aRunTheEntryDoesNotPackIsANamedMissOnContractTwo() {
        val base = NoisyText()
        val ct = CachedText(base)
        val comp = wm.damage.core.comp.Compositor()
        comp.v2 = true
        comp.requestKeyframe()
        assertNotNull(comp.assembleFlush(5))
        val with = GlyphAtlas(base, v2 = true, capacity = 96 * 1024); assertTrue(with.add(f18, codes("abX")))
        val without = GlyphAtlas(base, v2 = true, capacity = 96 * 1024); assertTrue(without.add(f18, codes("ab")))
        ct.atlas = with; ct.live = setOf(f18); ct.target = comp.composed
        comp.cachedText = ct
        ct.draw(comp.composed, 200, 200, "abX", f18, 255)
        assertEquals(listOf("abX"), ct.frameDraws().map { it.text })
        comp.damage(Rect(200, 200, 28, 22))
        ct.atlas = without                                  // the atlas in force at the assemble lacks X
        val a = assertNotNull(comp.assembleFlush(5))
        assertTrue("glyph-not-packed" in comp.cacheMissSummary(), "the miss is named: '${comp.cacheMissSummary()}'")
        assertTrue(a.ops.none { it is wm.damage.core.transport.DisplayOp.DrawText2 || it is wm.damage.core.transport.DisplayOp.DrawText }, "and nothing was drawn through the cache")
    }

    /**
     * A run that PRECEDES a host-drawn character keeps its last columns as composed in the base
     * (`TextDraw.keepEnd`), as the run after one keeps its first: the host's glyph may have ink
     * there — tucked in by a pair kern, or a mask that starts left of its pen. Blacked with the
     * rest of the run's box, that ink was missing from base + draws, the proof refused the rect
     * and the whole line went out as pixels on the flush that first showed the character.
     *
     * Since 0.64 the base is black only where a draw writes, so the host's ink rides it whatever
     * the kept columns say and the proof no longer depends on them. What the fields are read for
     * now is the price: with `keepEnd` ignored or never recorded (mutations-73 M76, M76b) the
     * host's ink reads as "lit where 0.63 had black", the rect is priced against its pixels and
     * stays pixels (`dearer`) — so those two mutations still fail this pin, for that reason
     * (watched 2026-11-10). The reader's own pin is
     * `BaseMaskTest.aRunBesideAHostDrawnCharacterIsNotPriced`. The expectation is unchanged.
     */
    @Test
    fun aRunBeforeAHostDrawnCharacterKeepsItsLastColumnsAndStillShipsAsADraw() {
        val base = KernedText()
        val ct = CachedText(base)
        ct.cacheMetric = true
        val comp = wm.damage.core.comp.Compositor()
        comp.v2 = true
        comp.requestKeyframe()
        assertNotNull(comp.assembleFlush(5))
        val atlas = GlyphAtlas(base, v2 = true, capacity = 96 * 1024)
        assertTrue(atlas.add(f18, codes("T")))                 // o is the host's: tucked two columns into T's box
        ct.atlas = atlas; ct.live = setOf(f18); ct.target = comp.composed
        comp.cachedText = ct
        ct.draw(comp.composed, 200, 200, "ToTo", f18, 255)
        assertEquals(listOf("T", "T"), ct.frameDraws().map { it.text })
        comp.damage(Rect(200, 200, 32, 16))
        val a = assertNotNull(comp.assembleFlush(5))
        assertTrue("proof" !in comp.cacheMissSummary(), "base + draws is the composed line: '${comp.cacheMissSummary()}'")
        assertEquals(2, a.ops.count { it is wm.damage.core.transport.DisplayOp.DrawText2 }, "both runs shipped as draws: ${a.ops.map { it::class.simpleName }}")
    }

    // ---- 0.64: what an acked message wrote, and a re-send (`Shell.atlasShortfall`'s material)

    private fun ranges(writes: List<Pair<Int, Int>>): List<IntRange> = writes.map { (off, len) -> off until off + len }
    private fun covered(r: List<IntRange>): java.util.BitSet = java.util.BitSet().also { b -> r.forEach { b.set(it.first, it.last + 1) } }

    /** Each ack returns the ranges its message wrote — exactly what the message's bytes copy into the
     *  cache — and says it was a first send; an ack of another generation returns nothing.
     *  Mutation watched: the ranges recorded from the appended run only (the table spans left out). */
    @Test
    fun anAckSaysWhichBytesItsMessageWrote() {
        for (v2 in booleanArrayOf(false, true)) {
            val size = if (v2) 96 * 1024 else 65536
            val a = GlyphAtlas(NoisyText(), v2 = v2, capacity = size)
            val cache = ByteArray(size)
            assertTrue(a.add(f24, codes(AtlasRepackTest.ASCII)))
            val first = a.takeUpload(1200)
            assertTrue(first.size >= 2, "a first upload of several messages: ${first.size}")
            assertNull(a.acked(a.uploadGen - 1), "an ack of another generation returns nothing")
            for ((i, m) in first.withIndex()) {
                val wrote = ranges(apply(m, cache))
                val ack = assertNotNull(a.acked(), "message $i")
                assertEquals(wrote, ack.writes, "v2=$v2: message $i's ranges are what its bytes write")
                assertFalse(ack.resend)
                assertEquals(i + 1, ack.n)
                assertEquals(wrote.sumOf { it.last - it.first + 1 }, ack.bytes)
            }
            assertNull(a.acked(), "an ack with nothing outstanding returns nothing")
            // an extension: appended records AND table spans, all in the ack's ranges
            val b = GlyphAtlas(NoisyText(), v2 = v2, capacity = size)
            assertTrue(b.add(f18, codes("ab")))
            upload(b, ByteArray(size))
            val x = (b.extend(f18, codes("cd")) as GlyphAtlas.Extended.Queued).ext
            val msgs = b.takeUpload()
            assertEquals(1, msgs.size)
            val wrote = ranges(apply(msgs[0], ByteArray(size)))
            val ack = assertNotNull(b.acked())
            assertEquals(wrote, ack.writes)
            assertTrue(x.spans.all { span -> ack.writes.any { it.first <= span.first && it.last >= span.last } },
                "v2=$v2: the extension's table spans ${x.spans} are among the ranges ${ack.writes}")
            assertTrue(ack.writes.size > x.spans.size, "and so is its run of appended records: ${ack.writes}")
        }
    }

    /** `resend(ranges)` then `takeUpload()`: messages that write exactly those ranges with the
     *  image's bytes, no mark and no generation moved, each acked as a re-send and counted in
     *  `ackedMsgs`; then nothing is left to hand out. A range already queued is not queued twice;
     *  an empty one is skipped; one outside the packed bytes is refused.
     *  Mutations watched: `takeUpload`'s early return ignoring re-sends (nothing is handed out);
     *  the ack not removing the re-send (it is handed out for ever). */
    @Test
    fun aReSendWritesTheSameRangesAgainAndMovesNoMark() {
        for (v2 in booleanArrayOf(false, true)) {
            val size = if (v2) 96 * 1024 else 65536
            val a = GlyphAtlas(NoisyText(), v2 = v2, capacity = size)
            val cache = ByteArray(size)
            assertTrue(a.add(f18, codes("ab")))
            upload(a, cache)
            assertTrue(a.extend(f18, codes("cd")) is GlyphAtlas.Extended.Queued)
            val m = a.takeUpload().single()
            apply(m, cache)
            val ack = assertNotNull(a.acked())
            a.promote()
            val sent = a.sentBytes; val acked = a.ackedBytes; val gen = a.uploadGen; val msgs0 = a.ackedMsgs
            val bytes = a.resend(ack.writes)
            assertEquals(ack.bytes, bytes, "the bytes queued")
            assertEquals(0, a.resend(ack.writes + listOf(IntRange.EMPTY)), "queued once: a repeat and an empty range add nothing")
            assertEquals(ack.writes.size, a.resendsQueued)
            assertFailsWith<LintError> { a.resend(listOf(a.used until a.used + 4)) }
            assertFailsWith<LintError> { a.resend(listOf(0..1)) }
            val again = a.takeUpload()
            assertTrue(again.isNotEmpty(), "v2=$v2: the re-send is handed out")
            val blank = ByteArray(size)
            val wrote = again.flatMap { ranges(apply(it, blank)) }
            assertEquals(covered(ack.writes), covered(wrote), "v2=$v2: exactly the ranges of the message, again")
            val image = a.contentForTest()
            for (r in ack.writes) assertContentEquals(image.copyOfRange(r.first, r.last + 1), blank.copyOfRange(r.first, r.last + 1), "the image's bytes at $r")
            assertEquals(sent, a.sentBytes); assertEquals(acked, a.ackedBytes); assertEquals(gen, a.uploadGen)
            for (x in again) { val k = assertNotNull(a.acked()); assertTrue(k.resend, "acked as a re-send") }
            assertEquals(msgs0 + again.size, a.ackedMsgs, "a re-send counts as a message: its read-back is owed")
            assertEquals(acked, a.ackedBytes, "and its ack moves no byte mark")
            assertEquals(0, a.resendsQueued)
            assertTrue(a.takeUpload().isEmpty(), "handed out once")
        }
    }

    /** A re-send is cut in whole units into messages of its own: every size from the smallest that
     *  holds a unit up, on both contracts. On v2 every offset is a multiple of 4; on v1 no table
     *  SPAN is split inside an entry (a re-sent run of appended bytes may be cut at an odd distance
     *  from a table inside it — the bytes are identical either way; the spans are what this pins).
     *  No message without data, none over its bound, and none shared with first-time bytes.
     *  Mutation watched: the unit rounding removed from the re-send's cut. */
    @Test
    fun aReSendIsCutInWholeUnitsInItsOwnMessagesAndNeverEmpty() {
        for (v2 in booleanArrayOf(false, true)) for (max in (9..40) + 3072) {
            val size = if (v2) 96 * 1024 else 65536
            val a = GlyphAtlas(NoisyText(), v2 = v2, capacity = size)
            assertTrue(a.add(f18, codes("ab")))
            upload(a, ByteArray(size))
            val ext = (a.extend(f18, codes("cdefgh")) as GlyphAtlas.Extended.Queued).ext
            val first = a.takeUpload()
            val acks = first.map { assertNotNull(a.acked()) }
            a.promote()
            a.resend(acks.flatMap { it.writes })
            // first-time bytes waiting too: another face's extension, cut in the same call
            assertTrue(a.extend(f18, codes("xyz")) is GlyphAtlas.Extended.Queued)
            val msgs = a.takeUpload(max)
            val kinds = msgs.map { m -> ranges(apply(m, ByteArray(size))) to assertNotNull(a.acked()) }
            assertTrue(kinds.any { it.second.resend } && kinds.any { !it.second.resend }, "v2=$v2 max=$max: both kinds were cut")
            val firstResend = kinds.indexOfFirst { it.second.resend }
            assertTrue(kinds.drop(firstResend).all { it.second.resend }, "v2=$v2 max=$max: the re-sends follow the first-time bytes, in messages of their own")
            val unit = if (v2) 4 else 2
            val resent = kinds.filter { it.second.resend }
            assertEquals(covered(acks.flatMap { it.writes }), covered(resent.flatMap { it.first }), "v2=$v2 max=$max: every byte of the ranges, nothing else")
            for ((i, m) in msgs.withIndex()) assertTrue(m.size <= max, "v2=$v2 max=$max: message $i is ${m.size} B")
            for ((wrote, _) in resent) for (r in wrote) {
                if (v2) assertEquals(0, r.first % 4, "v2 max=$max: a re-sent piece starts on a unit: $r")
                for (span in ext.spans) if (r.first in span) assertEquals(0, (r.first - span.first) % unit, "v2=$v2 max=$max: the piece $r starts inside an entry of span $span")
                for (span in ext.spans) if (r.last in span && r.last != span.last) assertEquals(unit - 1, (r.last - span.first) % unit, "v2=$v2 max=$max: the piece $r ends inside an entry of span $span")
            }
        }
    }

    /** The re-send carries the image AS IT STANDS when it is cut, not the bytes of the first send:
     *  a later extension of the same face rewrote the same table unit in place, and writing the
     *  old unit back would take the newer entry off the glasses under a face that is about to
     *  draw from it. Mutation watched: the bytes kept at `resend()` and sent as they were. */
    @Test
    fun aReSendCarriesTheImageAsItStandsAtTheCut() {
        val a = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
        val cache = ByteArray(96 * 1024)
        assertTrue(a.add(f18, codes("a")))
        upload(a, cache)
        // b and c share one 4-byte table unit (entries 66 and 67)
        val xb = (a.extend(f18, codes("b")) as GlyphAtlas.Extended.Queued).ext
        val mb = a.takeUpload().single()
        apply(mb, cache)
        val ack = assertNotNull(a.acked())
        a.promote()
        val unitOfB = xb.spans.single()
        val firstSent = cache.copyOfRange(unitOfB.first, unitOfB.last + 1)
        a.resend(ack.writes)                                  // queued; not cut yet
        val xc = (a.extend(f18, codes("c")) as GlyphAtlas.Extended.Queued).ext
        assertEquals(unitOfB, xc.spans.single(), "c's entry sits in the unit b's patch wrote")
        val msgs = a.takeUpload()
        assertEquals(2, msgs.size, "c's extension, then the re-send in a message of its own")
        val blank = ByteArray(96 * 1024)
        apply(msgs[0], cache); assertFalse(assertNotNull(a.acked()).resend)
        apply(msgs[1], blank); apply(msgs[1], cache); assertTrue(assertNotNull(a.acked()).resend)
        val now = a.contentForTest().copyOfRange(unitOfB.first, unitOfB.last + 1)
        assertFalse(now.contentEquals(firstSent), "the unit changed between the first send and the cut")
        assertContentEquals(now, blank.copyOfRange(unitOfB.first, unitOfB.last + 1), "the re-send carries the unit as the image holds it now")
        a.promote()
        assertTableMatches(a, f18, content = cache, what = "after the re-send")
    }

    /** A re-send cut and not acked goes again after a rewind, still marked a re-send; the ack of
     *  the message cut before the rewind completes nothing.
     *  Mutation watched: `rewindToAcked` leaving re-sends handed out (nothing goes again). */
    @Test
    fun aRewindSendsAnUnackedReSendAgainAndAnOldAckCompletesNothing() {
        for (v2 in booleanArrayOf(false, true)) {
            val size = if (v2) 96 * 1024 else 65536
            val a = GlyphAtlas(NoisyText(), v2 = v2, capacity = size)
            assertTrue(a.add(f18, codes("ab")))
            upload(a, ByteArray(size))
            assertTrue(a.extend(f18, codes("cd")) is GlyphAtlas.Extended.Queued)
            a.takeUpload()
            val ack = assertNotNull(a.acked())
            a.promote()
            a.resend(ack.writes)
            val old = a.uploadGen
            val lost = a.takeUpload()
            assertTrue(lost.isNotEmpty() && a.takeUpload().isEmpty(), "handed out once")
            a.rewindToAcked()                                 // the link ended before its ack
            assertNull(a.acked(old), "an ack of the cut before the rewind completes nothing")
            assertEquals(ack.writes.size, a.resendsQueued, "still queued")
            val again = a.takeUpload()
            assertEquals(lost.size, again.size, "v2=$v2: the re-send goes again")
            for ((x, y) in lost.zip(again)) assertContentEquals(x, y)
            for (m in again) assertTrue(assertNotNull(a.acked()).resend, "still marked a re-send: it is never queued a third time")
            assertEquals(0, a.resendsQueued)
        }
    }

    /** A lapse or a repack sends everything again from the guard: a queued re-send is dropped with
     *  the layout it named. Mutation watched: `forgetUpload` not clearing re-sends (the old
     *  layout's ranges go out over the new one). */
    @Test
    fun aLapseOrARepackDropsAQueuedReSend() {
        for (repack in booleanArrayOf(false, true)) {
            val a = GlyphAtlas(NoisyText(), v2 = true, capacity = 96 * 1024)
            assertTrue(a.add(f24, codes("xyz")))
            assertTrue(a.add(f18, codes("ab")))
            upload(a, ByteArray(96 * 1024))
            assertTrue(a.extend(f18, codes("cd")) is GlyphAtlas.Extended.Queued)
            a.takeUpload()
            val ack = assertNotNull(a.acked())
            a.promote()
            a.resend(ack.writes)
            if (repack) a.repack(listOf(f18), emptyList()) else a.forgetUpload()
            assertEquals(0, a.resendsQueued, "repack=$repack: dropped with the layout")
            val msgs = a.takeUpload()
            for (m in msgs) assertFalse(assertNotNull(a.acked()).resend, "repack=$repack: the whole layout goes again as first sends")
            assertEquals(a.used, a.ackedBytes)
        }
    }
}
