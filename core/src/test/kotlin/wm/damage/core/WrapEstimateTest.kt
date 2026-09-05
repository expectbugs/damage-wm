package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.gfx.Gray8
import wm.damage.core.text.Face
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.text.Wrap

/**
 * `Wrap.wrap` decides most candidates from an additive estimate and measures
 * exactly only inside a band around the width (2026-09-05, `HANDOFF.md` §32).
 * This pins that the result is IDENTICAL to the every-candidate loop it
 * replaced, over random text, for two rasterizers: one purely additive (AWT's
 * behaviour — no kerning) and one that kerns NEGATIVELY across every space
 * (the Android direction, exaggerated to a full pixel per space).
 */
class WrapEstimateTest {

    /** Per-character advances that vary, summed — what a font without kerning
     *  reports for any string. */
    private open class AdditiveText : TextRasterizer {
        open fun charW(c: Char): Int = 5 + (c.code % 7)
        override fun measure(text: String, font: FontSpec): Int = text.sumOf { charW(it) }
        override fun metrics(font: FontSpec) = FontMetrics(12, 4, 18)
        override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {}
        override fun covers(text: String, font: FontSpec) = true
    }

    /** The same, a pixel tighter at every space: the whole measures LESS than
     *  the sum of its words. */
    private class KernedText : AdditiveText() {
        override fun measure(text: String, font: FontSpec): Int =
            super.measure(text, font) - text.count { it == ' ' }
    }

    /** The loop as it was: every candidate measured. */
    private fun reference(text: String, font: FontSpec, r: TextRasterizer, width: Int): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { out.add(""); continue }
            val paraStart = out.size
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                var w = word
                while (true) {
                    val candidate = if (line.isEmpty()) w else "$line $w"
                    if (r.measure(candidate, font) <= width) { line = StringBuilder(candidate); break }
                    if (line.isNotEmpty()) { out.add(line.toString()); line = StringBuilder(); continue }
                    var cut = w.length
                    while (cut > 1 && r.measure(w.substring(0, cut), font) > width) cut--
                    out.add(w.substring(0, cut))
                    w = w.substring(cut)
                    if (w.isEmpty()) break
                }
            }
            if (line.isNotEmpty() || out.size == paraStart) out.add(line.toString())
        }
        if (out.size > 1 && out.last().isEmpty() && text.endsWith('\n')) out.removeAt(out.size - 1)
        return out
    }

    private fun randomText(rnd: kotlin.random.Random): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789,.;:'\"!?()-"
        val sb = StringBuilder()
        val paragraphs = 1 + rnd.nextInt(4)
        for (p in 0 until paragraphs) {
            if (p > 0) sb.append('\n')
            if (rnd.nextInt(8) == 0) continue                    // an empty paragraph
            val words = 1 + rnd.nextInt(60)
            for (i in 0 until words) {
                if (i > 0) sb.append(' ')
                // mostly short words; sometimes a word wider than any line, to
                // reach the hard-break path; sometimes two spaces in a row
                val len = when (rnd.nextInt(20)) { 0 -> 30 + rnd.nextInt(80); 1 -> 0; else -> 1 + rnd.nextInt(11) }
                repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            }
        }
        if (rnd.nextInt(4) == 0) sb.append('\n')
        return sb.toString()
    }

    private fun pin(r: TextRasterizer, seed: Int) {
        val rnd = kotlin.random.Random(seed)
        val f = FontSpec(Face.READER, 17)
        repeat(400) {
            val text = randomText(rnd)
            for (width in intArrayOf(96, 240, 560)) {
                val expect = reference(text, f, r, width)
                val got = Wrap.wrap(text, f, r, width)
                assertEquals(expect, got, "seed $seed width $width text '${text.take(80)}…'")
                // and the invariant the draw path depends on: no wrapped line
                // measures past the width unless it is a single hard-broken glyph run
                for (l in got) if (l.contains(' ')) assertTrue(r.measure(l, f) <= width, "'$l' overruns $width")
            }
        }
    }

    @Test fun theEstimateAgreesWithMeasuringEveryCandidate_additive() = pin(AdditiveText(), 1)
    @Test fun theEstimateAgreesWithMeasuringEveryCandidate_negativelyKerned() = pin(KernedText(), 2)

    @Test fun eachDistinctWordIsMeasuredOnce() {
        val counted = HashMap<String, Int>()
        val r = object : AdditiveText() {
            override fun measure(text: String, font: FontSpec): Int {
                counted.merge(text, 1, Int::plus)
                return super.measure(text, font)
            }
        }
        val words = (1..300).map { "w$it" }
        val text = (words + words + words).joinToString(" ")
        Wrap.wrap(text, FontSpec(Face.READER, 17), r, 560)
        for (w in words) assertEquals(1, counted[w], "word '$w' measured ${counted[w]} times")
        // the exact measures happen only near the width: far fewer than one per word
        val whole = counted.filterKeys { it.contains(' ') }.values.sum()
        assertTrue(whole < words.size, "$whole exact measures for ${words.size * 3} words")
    }
}
