package wm.damage.core.windows.games.kit

import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * Card art, CODE-DRAWN (`HOLDEM.md` verdict 8) — no generated assets, nothing
 * third-party, sharp at 48×66.
 *
 * 🔴 **Verdict 7: the colour is carried at CARD scale, not pip scale.** A
 * black-suit card is an unfilled OUTLINE card; a red-suit card is a FILLED
 * mid-grey card. Adam: *"A wireframe-esque card vs a filled mid-grey-level card
 * is easy to tell the difference in colour regardless of what is behind the
 * transparency in real life."* That split is also what makes the look
 * affordable — a bright-filled body for ALL cards modelled at ≈29 % ink where
 * this scheme is ≈8–10 % (§2).
 *
 * Two facts carried forward verbatim from the G2CC card research
 * (`/home/user/G2CC/games/gamelist.md`, 2026-06-28):
 *
 *  - **Suits must read by SHAPE as well as level** — red and black both go
 *    dark in 16-gray, so the pip silhouettes have to be unmistakable.
 *  - **The corner index is what shows when cards are fanned**, so it is the
 *    crisp element and gets the budget.
 *
 * Drawing rules are the compression rules (`DESIGN.md` §2.4 r9): thick strokes,
 * closed forms, no hairlines, few levels, solid fills.
 */
object CardArt {

    /** One rung of the §9.1 ladder. All four are grid-legal (w %4, h %2) and
     *  near the real 0.714 card aspect. */
    data class Size(val w: Int, val h: Int) {
        val indexPx: Int get() = (h * 0.22).toInt()          // rank glyph size
        // 0.21 left the corner pip 10 px at the 288 rung, where a spade and a
        // diamond are the same little lozenge; 0.25 is the smallest that
        // separates the four shapes there (first live session, 2026-09-04)
        val pipPx: Int get() = (w * 0.25).toInt()            // the corner pip
        val bigPip: Int get() = (w * 0.42).toInt()           // the big suit pip
        val pad: Int get() = maxOf(3, w / 12)
        val stroke: Int get() = if (w >= 64) 3 else 2

        companion object {
            val S288 = Size(48, 66)
            val S352 = Size(56, 78)
            val S416 = Size(64, 88)
            val S480 = Size(72, 100)
            val LADDER = listOf(S288, S352, S416, S480)

            /** The rung for a shell height mode (288/352/416/480). */
            fun forHeight(h: Int): Size = when {
                h <= 288 -> S288
                h <= 352 -> S352
                h <= 416 -> S416
                else -> S480
            }
        }
    }

    /** The mid-grey a red-suit card's body is filled with (verdict 7). */
    val FILL = Level.of(5)
    /** The card edge. Bright enough to read as a boundary against the fill. */
    val EDGE = Level.of(9)
    /** Rank index and pips. */
    val INK = Level.HEAD

    /**
     * Draw [c] with its top-left at ([x],[y]). [dim] draws the whole card one
     * step down — a folded seat, a card that is not yours.
     */
    fun card(g: Gray8, tx: TextRasterizer, x: Int, y: Int, s: Size, c: Card, dim: Boolean = false) {
        val edge = if (dim) Level.DIM else EDGE
        val ink = if (dim) Level.MID else INK
        val fill = if (dim) Level.of(3) else FILL
        body(g, x, y, s, filled = c.suit.red, edge = edge, fill = fill)
        index(g, tx, x, y, s, c, ink)
        // the big pip sits BOTTOM-RIGHT, diagonally opposite the index. A
        // centred pip is the classic placement and it is wrong here: at
        // 48x66 the corner block reaches the middle of the card and the two
        // shapes merged into one blob (the first render, 2026-09-04).
        val bp = s.bigPip
        pip(g, x + s.w - s.pad - bp, y + s.h - s.pad - bp, bp, bp, c.suit, ink)
    }

    /** The face-down back (§9.2: an ink trap — five seats × two backs is ten
     *  lit rectangles carrying no information, so this is for the 480 layout
     *  and for your own cards before a deal resolves). */
    fun back(g: Gray8, x: Int, y: Int, s: Size, dim: Boolean = false) {
        val edge = if (dim) Level.DIM else EDGE
        body(g, x, y, s, filled = false, edge = edge, fill = 0)
        // a lattice would shred the RLE runs; two nested diamonds are closed
        // forms with long horizontal spans
        val ink = if (dim) Level.REST else Level.of(4)
        val w = s.w - 4 * s.pad
        val h = s.h - 4 * s.pad
        diamond(g, x + (s.w - w) / 2, y + (s.h - h) / 2, w, h, ink)
        val w2 = w / 2
        val h2 = h / 2
        diamond(g, x + (s.w - w2) / 2, y + (s.h - h2) / 2, w2, h2, 0)
    }

    /**
     * The small "this seat holds cards" mark the 288/352 opponent strips use
     * instead of drawn backs (§9.2). Two stacked bars, [held] of them lit.
     */
    fun holdingMark(g: Gray8, x: Int, y: Int, w: Int, h: Int, held: Int, lv: Int) {
        val bw = (w - 2) / 2
        for (i in 0 until 2) {
            val bx = x + i * (bw + 2)
            if (i < held) g.fillRect(bx, y, bw, h, lv)
            else g.outlineRect(bx, y, bw, h, Level.FAINT, 1)
        }
    }

    // ------------------------------------------------------------------ pieces
    private fun body(g: Gray8, x: Int, y: Int, s: Size, filled: Boolean, edge: Int, fill: Int) {
        val t = s.stroke
        if (filled) g.fillRect(x, y, s.w, s.h, fill)
        g.outlineRect(x, y, s.w, s.h, edge, t)
        // chamfer the four corners so the card reads as a card and not a box;
        // knocking 2×2 out of each corner keeps every row one run
        for (dy in 0 until t) for (dx in 0 until t - dy) {
            g[x + dx, y + dy] = 0
            g[x + s.w - 1 - dx, y + dy] = 0
            g[x + dx, y + s.h - 1 - dy] = 0
            g[x + s.w - 1 - dx, y + s.h - 1 - dy] = 0
        }
    }

    /**
     * The CORNER INDEX: rank over a small suit pip, top-left. This is the
     * crisp element — it is what shows when a hand is fanned, and the fan
     * overlaps left-to-right so the left edge of every card stays visible.
     */
    private fun index(g: Gray8, tx: TextRasterizer, x: Int, y: Int, s: Size, c: Card, ink: Int) {
        val corner = s.w / 2 + 2
        val f = rankFont(tx, c.rank, s, corner)
        val ix = x + s.pad
        tx.draw(g, ix, y + s.pad - 1, c.rank.label, f, ink)
        val p = s.pipPx
        // positioned off the ASCENT, not the line height: the descent is empty
        // for every rank label ("10", "J", "Q", "K", "A" and the digits) and
        // spending it pushed the pip into the big one
        pip(g, ix, y + s.pad + tx.metrics(f).ascent + 1, p, p, c.suit, ink)
    }

    /** The largest ladder font whose rank label fits the corner. Never a
     *  silent overrun: it steps down until it fits, and "10" is the case that
     *  forces it. */
    private fun rankFont(tx: TextRasterizer, r: Rank, s: Size, maxW: Int): FontSpec {
        var px = s.indexPx
        while (px > 8) {
            val f = FontSpec(Face.SYSTEM, px, bold = true)
            if (tx.measure(r.label, f) <= maxW) return f
            px -= 1
        }
        return FontSpec(Face.SYSTEM, 8, bold = true)
    }

    /** A suit pip in the box ([x],[y],[w],[h]). Shapes are closed and solid. */
    fun pip(g: Gray8, x: Int, y: Int, w: Int, h: Int, suit: Suit, lv: Int) {
        if (w < 4 || h < 4) { g.fillRect(x, y, maxOf(1, w), maxOf(1, h), lv); return }
        when (suit) {
            Suit.DIAMONDS -> diamond(g, x, y, w, h, lv)
            Suit.HEARTS -> heart(g, x, y, w, h, lv, up = false)
            Suit.SPADES -> {
                heart(g, x, y, w, (h * 0.82).toInt(), lv, up = true)
                // the stem STARTS INSIDE the leaf: at 0.82h the two shapes
                // met at a single row and integer truncation opened a gap, so
                // the stem read as a separate blob under the pip (first live
                // session, 2026-09-04)
                stem(g, x, y + (h * 0.56).toInt(), w, h - (h * 0.56).toInt(), lv)
            }
            Suit.CLUBS -> {
                val d = (w * 0.52).toInt()
                g.fillEllipse(x + (w - d) / 2, y, d, d, lv)
                g.fillEllipse(x, y + (h * 0.34).toInt(), d, d, lv)
                g.fillEllipse(x + w - d, y + (h * 0.34).toInt(), d, d, lv)
                stem(g, x, y + (h * 0.46).toInt(), w, h - (h * 0.46).toInt(), lv)
            }
        }
    }

    private fun diamond(g: Gray8, x: Int, y: Int, w: Int, h: Int, lv: Int) {
        g.fillPolygon(
            intArrayOf(x + w / 2, x + w, x + w / 2, x),
            intArrayOf(y, y + h / 2, y + h, y + h / 2), lv)
    }

    /** A heart ([up] = false) or its vertical mirror, the spade's leaf. */
    private fun heart(g: Gray8, x: Int, y: Int, w: Int, h: Int, lv: Int, up: Boolean) {
        val lobe = (w * 0.58).toInt()
        val lobeH = (h * 0.62).toInt()
        if (!up) {
            g.fillEllipse(x, y, lobe, lobeH, lv)
            g.fillEllipse(x + w - lobe, y, lobe, lobeH, lv)
            g.fillPolygon(
                intArrayOf(x, x + w, x + w / 2),
                intArrayOf(y + (h * 0.34).toInt(), y + (h * 0.34).toInt(), y + h), lv)
        } else {
            g.fillEllipse(x, y + h - lobeH, lobe, lobeH, lv)
            g.fillEllipse(x + w - lobe, y + h - lobeH, lobe, lobeH, lv)
            g.fillPolygon(
                intArrayOf(x, x + w, x + w / 2),
                intArrayOf(y + h - (h * 0.34).toInt(), y + h - (h * 0.34).toInt(), y), lv)
        }
    }

    /** The spade/club stem: a trapezoid from ([x],[y]) down to the pip's
     *  baseline, flaring into a foot. The caller starts it INSIDE the leaf or
     *  the lobes — a stem that merely abuts them reads as a second shape. */
    private fun stem(g: Gray8, x: Int, y: Int, w: Int, h: Int, lv: Int) {
        val waist = maxOf(2, w / 7)
        val foot = maxOf(waist + 2, w / 3)
        val cx = x + w / 2
        g.fillPolygon(
            intArrayOf(cx - waist / 2, cx + (waist + 1) / 2, cx + (foot + 1) / 2, cx - foot / 2),
            intArrayOf(y, y, y + h, y + h), lv)
    }
}
