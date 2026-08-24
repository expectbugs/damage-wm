package wm.damage.core.text

import wm.damage.core.geom.LintError
import wm.damage.core.gfx.Gray8

/**
 * The platform text seam. Core composes everything except glyphs; TEXT enters
 * through this interface (AWT on desktop, android.graphics on the phone), so
 * the one shell runs on both (DESIGN.md §10.2).
 *
 * Rules enforced here rather than trusted to discipline:
 *  - NO TRUNCATION: there is no clipping draw — callers lay out with measure()
 *    and use the shell's marquee / continuation-mark mechanisms (§2.4 rule 3).
 *  - UI symbols are DRAWN, never typed (§Type): draw() refuses codepoints
 *    outside Latin-1 unless the platform rasterizer proves coverage in every
 *    locked face that could draw the string. A tofu box on glass is silent.
 */
interface TextRasterizer {
    /** Advance width in px of [text] at [font]. */
    fun measure(text: String, font: FontSpec): Int

    /** Font metrics for line layout. */
    fun metrics(font: FontSpec): FontMetrics

    /**
     * Draw [text] with its glyph-box top-left at (x, y), paint level [level]
     * (0..255, normally Level.*), alpha-composited like PIL so renders match
     * design/render_shots.py. Origin is snapped to the damage grid by the
     * caller's layout; this draw itself must not shift it.
     */
    fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int)

    /** True when EVERY codepoint of [text] has a real glyph in [font]. */
    fun covers(text: String, font: FontSpec): Boolean
}

data class FontMetrics(val ascent: Int, val descent: Int, val lineHeight: Int)

/** A face role + pixel size + weight. Roles map to concrete files per platform. */
data class FontSpec(val face: Face, val sizePx: Int, val bold: Boolean = false, val italic: Boolean = false)

/**
 * The locked typeface assignments — DESIGN.md §Type, decided 2026-08-18 from
 * the 1x comparison sheets. The system face is NOT negotiable per window.
 */
enum class Face(val family: String, val role: String) {
    /** Clear Sans — all chrome, everywhere, plus Main. Cheapest and lowest-ink
     *  of every candidate on every surface. */
    SYSTEM("Clear Sans", "system face — all chrome + Main"),

    /** Fira Sans — Mail and other dense lists. */
    LIST("Fira Sans", "dense lists"),

    /** Alegreya — Reader and long-form. */
    READER("Alegreya", "long-form serif"),

    /** JetBrains Mono — Terminal and column-aligned views. */
    MONO("JetBrains Mono", "column-aligned"),
}

/** Guard used by every shell draw call: only Latin-1 goes through the font;
 *  anything else is a drawn shape (icons, triangles, blocks). SYM001 at runtime. */
object DrawnStrings {
    fun check(text: String, r: TextRasterizer, font: FontSpec) {
        for (ch in text) {
            val cp = ch.code
            if (cp in 0x20..0x7E || cp in 0xA0..0xFF) continue
            if (!r.covers(ch.toString(), font))
                throw LintError(
                    "SYM001 '${ch}' U+%04X reached a text draw and ${font.face.family} cannot render it — ".format(cp) +
                        "draw it as a shape; only plain text goes through the font (DESIGN.md §Type)",
                )
        }
    }
}

/** Greedy word wrap against real measured widths. Words longer than the width
 *  break mid-word (long content stays long — it wraps, never disappears). */
object Wrap {
    fun wrap(text: String, font: FontSpec, r: TextRasterizer, width: Int): List<String> {
        if (width <= 0) throw LintError("wrap width $width")
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { out.add(""); continue }
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                var w = word
                while (true) {
                    val candidate = if (line.isEmpty()) w else "$line $w"
                    if (r.measure(candidate, font) <= width) {
                        line = StringBuilder(candidate)
                        break
                    }
                    if (line.isNotEmpty()) {
                        out.add(line.toString())
                        line = StringBuilder()
                        continue
                    }
                    // single word wider than the line: hard-break it. cut floors
                    // at 1 so progress is GUARANTEED — a glyph wider than the
                    // whole line ships oversize rather than spinning forever
                    // (review round 1 traced an infinite loop at cut == 0)
                    var cut = w.length
                    while (cut > 1 && r.measure(w.substring(0, cut), font) > width) cut--
                    out.add(w.substring(0, cut))
                    w = w.substring(cut)
                    if (w.isEmpty()) break
                }
            }
            out.add(line.toString())
        }
        // trim the trailing empty line a final \n produces
        if (out.size > 1 && out.last().isEmpty() && text.endsWith('\n')) out.removeAt(out.size - 1)
        return out
    }
}
