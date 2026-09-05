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

/** A face role + pixel size + weight. Roles map to concrete files per platform.
 *  [raw] = exempt from every [StyleTransform] — the Settings previews that
 *  must show a CANDIDATE font exactly as chosen (Style.kt, 2026-08-31). */
data class FontSpec(val face: Face, val sizePx: Int, val bold: Boolean = false, val italic: Boolean = false,
    val raw: Boolean = false)

/**
 * The locked typeface assignments — DESIGN.md §Type, decided 2026-08-18 from
 * the 1x comparison sheets. These are the DEFAULTS: since 2026-08-31 (Adam's
 * recorded reversal of "the system face is not negotiable") Settings can remap
 * chrome+Main globally and each app's content per app, via Style.kt transforms
 * — always choosing from these four loaded faces.
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
        var i = 0
        while (i < text.length) {
            // whole code points: a surrogate pair is one glyph, and asking the
            // rasterizer about a lone surrogate would refuse a glyph a face
            // can actually draw (round 5)
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x20..0x7E || cp in 0xA0..0xFF) continue
            val s = String(Character.toChars(cp))
            if (!r.covers(s, font))
                throw LintError(
                    "SYM001 '$s' U+%04X reached a text draw and ${font.face.family} cannot render it — ".format(cp) +
                        "draw it as a shape; only plain text goes through the font (DESIGN.md §Type)",
                )
        }
    }
}

/**
 * Greedy word wrap against real measured widths. Words longer than the width
 * break mid-word (long content stays long — it wraps, never disappears).
 *
 * 2026-09-05 (`HANDOFF.md` §32): the decision for a candidate line is taken
 * from an ADDITIVE estimate — the measured widths of its words plus spaces —
 * whenever the estimate is clearly inside or clearly outside the width, and
 * from an exact measure of the whole candidate only inside a band of
 * [ESTIMATE_SLACK_PX] either side of it. The old loop measured every
 * candidate: one platform measure per WORD of a book, and on Android each of
 * those is a shaping pass over a string nothing has seen before. Each
 * distinct word is now measured once.
 *
 * Why the result is the same. Within a word the measure is exact (the word
 * is measured whole, ligatures and all). Across a space the only difference
 * between the sum and the whole is kerning against the space, which fonts
 * either lack or make NEGATIVE (tighter) and by about a pixel — so the true
 * width is never above the estimate by more than a pixel or two per line,
 * and a candidate the estimate accepts with 24 px to spare cannot overrun
 * the width `Draw.fit` will measure it against. AWT applies no kerning at
 * all, so on the desktop the estimate is exact and `WrapEstimateTest` pins
 * equality with the every-candidate loop over random text. Once a candidate
 * IS measured, the measured width replaces the estimate for the rest of the
 * line, so the estimate never drifts beyond one word.
 */
object Wrap {
    /** Half-width of the band in which a candidate is measured exactly. */
    const val ESTIMATE_SLACK_PX = 24

    fun wrap(text: String, font: FontSpec, r: TextRasterizer, width: Int): List<String> {
        if (width <= 0) throw LintError("wrap width $width")
        val out = ArrayList<String>()
        val spaceW = r.measure(" ", font)
        val wordW = HashMap<String, Int>()
        fun wordWidth(w: String): Int = wordW.getOrPut(w) { r.measure(w, font) }
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { out.add(""); continue }
            val paraStart = out.size
            var line = StringBuilder()
            var lineW = 0            // `line`'s width: estimated, or exact once measured
            for (word in paragraph.split(' ')) {
                var w = word
                while (true) {
                    val est = if (line.isEmpty()) wordWidth(w) else lineW + spaceW + wordWidth(w)
                    var fitW = -1
                    if (est <= width - ESTIMATE_SLACK_PX) fitW = est
                    else if (est <= width + ESTIMATE_SLACK_PX) {
                        val m = r.measure(if (line.isEmpty()) w else "$line $w", font)
                        if (m <= width) fitW = m
                    }
                    if (fitW >= 0) {
                        if (line.isNotEmpty()) line.append(' ')
                        line.append(w)
                        lineW = fitW
                        break
                    }
                    if (line.isNotEmpty()) {
                        out.add(line.toString())
                        line = StringBuilder()
                        lineW = 0
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
            // a hard-broken final word leaves `line` empty — adding it would
            // fabricate a blank line the source does not contain
            if (line.isNotEmpty() || out.size == paraStart) out.add(line.toString())
        }
        // trim the trailing empty line a final \n produces
        if (out.size > 1 && out.last().isEmpty() && text.endsWith('\n')) out.removeAt(out.size - 1)
        return out
    }
}
