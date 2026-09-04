package wm.damage.core.windows.games.kit

import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.text.Face
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer

/**
 * Money formatting and the SCOREBOARD (`HOLDEM.md` §4).
 *
 * Chips are dollars 1:1, so every amount in the whole game is a plain Int and
 * a table is conserved by construction: 6 × buy-in in, the same amount out.
 *
 * The scoreboard is drawn in the seven-segment digits the silent-mode clock
 * already uses (`DESIGN.md` §1.5 — *"drawn, never typed"*, so the locked faces
 * stay four). The digit drawing here is the same tapered-segment construction
 * as `Icons.sevenSegClock`, generalized over size so the ladder can carry it
 * at four heights; the captions beside it are ordinary text drawn by the
 * caller — a seven-segment display cannot spell "CASH".
 */
object Money {

    /** `$1,234`. Negative amounts read `-$12`, never `$-12`. */
    fun fmt(v: Int): String = (if (v < 0) "-$" else "$") + group(kotlin.math.abs(v))

    /** `1,234` — no currency mark, for columns that carry their own. */
    fun group(v: Int): String {
        val s = kotlin.math.abs(v).toString()
        val b = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) b.append(',')
            b.append(c)
        }
        return (if (v < 0) "-" else "") + b
    }

    /** Compact stack sizes for a crowded seat cell: `$1.2k`, `$14k`. */
    fun compact(v: Int): String = when {
        kotlin.math.abs(v) < 10_000 -> fmt(v)
        kotlin.math.abs(v) < 1_000_000 -> (if (v < 0) "-$" else "$") + "${kotlin.math.abs(v) / 1000}k"
        else -> (if (v < 0) "-$" else "$") + "%.1fm".format(kotlin.math.abs(v) / 1_000_000.0)
    }

    /** Big blinds, the only unit that means anything about pressure. */
    fun bb(chips: Int, bigBlind: Int): String =
        if (bigBlind <= 0) "" else "${(chips.toDouble() / bigBlind).let { if (it >= 10) it.toInt().toString() else "%.1f".format(it) }}bb"

    /**
     * A drawn CHIP STACK (`HOLDEM.md` §9.2, the 480 rung: *"drawn chip stacks
     * in place of bare numbers"*). Height is logarithmic in big blinds, so a
     * $4,000 bet is a taller stack than $400 without being ten times taller —
     * and it caps, because the point is "a lot" and not an exact count.
     *
     * Chips are horizontal runs, which is what the RLE wants (§2.4 r9).
     */
    fun chipStack(g: Gray8, x: Int, y: Int, w: Int, amount: Int, bigBlind: Int, lv: Int,
        maxChips: Int = 5): Int {
        if (amount <= 0 || bigBlind <= 0) return 0
        val bb = amount.toDouble() / bigBlind
        // 🔴 ROUND chips, overlapping. Square bars with square gaps read as
        // punctuation next to the amount they annotate — one bar was an
        // em-dash in front of the word "pot" and two were an equals sign
        // between two amounts (first live session, 2026-09-04). Round ends and
        // a 1 px overlap give a scalloped column that reads as a stack at two
        // chips, which is the commonest case: a seat that has just posted a
        // blind. Two is also the floor — a single chip is a dash whatever it
        // is drawn beside.
        val n = (1 + kotlin.math.log2(bb.coerceAtLeast(1.0))).toInt().coerceIn(2, maxOf(2, maxChips))
        for (i in 0 until n) g.fillEllipse(x, y - i * CHIP_PITCH, w, CHIP_H, lv)
        return (n - 1) * CHIP_PITCH + CHIP_H
    }

    /** One drawn chip: 4 rows tall, stacked on a 3-row pitch so each overlaps
     *  the one below and the column reads as continuous. */
    const val CHIP_H = 4
    const val CHIP_PITCH = 3

    // ================================================================ seven segment
    /** A digit cell's metrics. Every dimension is even so the drawn extent
     *  lands on the damage grid (`Geometry` Y_STEP = 2). */
    data class Seg(val w: Int, val h: Int, val t: Int, val gap: Int) {
        val pitch: Int get() = w + gap

        companion object {
            /** The ladder used by the Games surfaces: small rides in a list
             *  lens, large heads the Bankroll level. */
            val SMALL = Seg(12, 20, 3, 4)
            val MEDIUM = Seg(18, 30, 4, 6)
            val LARGE = Seg(26, 44, 6, 6)
        }
    }

    // bit order A B C D E F G, bit 6 .. bit 0 — Icons.sevenSegClock's table
    private val SEGS = intArrayOf(
        0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
        0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011,
    )

    /** Total width of [text] rendered by [digits] at [s]. */
    fun digitsWidth(text: String, s: Seg): Int {
        var w = 0
        for (c in text) w += cellWidth(c, s) + s.gap
        return if (w > 0) w - s.gap else 0
    }

    private fun cellWidth(c: Char, s: Seg): Int = when (c) {
        ',' -> s.t + 2
        '.' -> s.t
        ' ' -> s.w / 2
        '-' -> s.w
        else -> s.w
    }

    /**
     * Draw [text] — digits, `,`, `.`, `-`, space — as seven-segment glyphs with
     * the glyph box top-left at ([x],[y]). Anything else throws: a silently
     * skipped character is the class of failure this project bans, and callers
     * pass captions through the text rasterizer instead.
     */
    fun digits(g: Gray8, x: Int, y: Int, text: String, s: Seg, lv: Int = Level.of(9)) {
        val edge = Level.of(((lv / 17) * 2 / 3).coerceAtLeast(1))
        var cx = x
        for (c in text) {
            when (c) {
                in '0'..'9' -> digit(g, cx, y, c - '0', s, lv, edge)
                ',' -> g.fillRect(cx, y + s.h - s.t, s.t, s.t + s.t / 2, lv)
                '.' -> g.fillRect(cx, y + s.h - s.t, s.t, s.t, lv)
                '-' -> hseg(g, cx + s.t / 2, y + s.h / 2 - s.t / 2, s.w - s.t, s.t, lv, edge)
                ' ' -> {}
                else -> throw IllegalArgumentException(
                    "the seven-segment display cannot draw '$c' — captions go through the font")
            }
            cx += cellWidth(c, s) + s.gap
        }
    }

    private fun hseg(g: Gray8, x: Int, y: Int, len: Int, t: Int, lv: Int, edge: Int) {
        for (r in 0 until t) {
            val inset = kotlin.math.abs(2 * r - (t - 1)) / 2
            g.fillRect(x + inset, y + r, len - 2 * inset, 1, if (r == 0 || r == t - 1) edge else lv)
        }
    }

    private fun vseg(g: Gray8, x: Int, y: Int, len: Int, t: Int, lv: Int, edge: Int) {
        for (c in 0 until t) {
            val inset = kotlin.math.abs(2 * c - (t - 1)) / 2
            g.fillRect(x + c, y + inset, 1, len - 2 * inset, if (c == 0 || c == t - 1) edge else lv)
        }
    }

    /**
     * One digit. The vertical runs are sized to MEET the horizontals: the
     * first version placed the lower pair two pixels short of the bottom bar
     * and every glyph rendered as loose fragments at true 1× (the first card
     * render, 2026-09-04). The rule is A ∈ [0,t), the upper verticals
     * [t, gTop), G ∈ [gTop, gTop+t), the lower verticals [gTop+t, h−t) and
     * D ∈ [h−t, h) — every joint touches, and the taper does the rest.
     */
    private fun digit(g: Gray8, x: Int, y: Int, d: Int, s: Seg, lv: Int, edge: Int) {
        val m = SEGS[d]
        val t = s.t
        val gTop = (s.h - t) / 2
        val upY = y + t
        val upLen = gTop - t
        val loY = y + gTop + t
        val loLen = s.h - gTop - 2 * t
        val hx = x + t / 2 + 1
        val hw = s.w - t - 2
        if (m and 0b1000000 != 0) hseg(g, hx, y, hw, t, lv, edge)                       // A
        if (m and 0b0100000 != 0) vseg(g, x + s.w - t, upY, upLen, t, lv, edge)         // B
        if (m and 0b0010000 != 0) vseg(g, x + s.w - t, loY, loLen, t, lv, edge)         // C
        if (m and 0b0001000 != 0) hseg(g, hx, y + s.h - t, hw, t, lv, edge)             // D
        if (m and 0b0000100 != 0) vseg(g, x, loY, loLen, t, lv, edge)                   // E
        if (m and 0b0000010 != 0) vseg(g, x, upY, upLen, t, lv, edge)                   // F
        if (m and 0b0000001 != 0) hseg(g, hx, y + gTop, hw, t, lv, edge)                // G
    }

    /**
     * The `$` mark, DRAWN (never typed) so it sits on the segment baseline in
     * the same stroke weight. A seven-segment `5` already IS an S — the mark
     * is that glyph plus a short stem above and below. An earlier version
     * hand-rolled the bars and ran the stem the whole height, which read as a
     * double-dagger at 1× (the first card render, 2026-09-04).
     */
    fun dollarMark(g: Gray8, x: Int, y: Int, s: Seg, lv: Int = Level.of(9)) {
        val edge = Level.of(((lv / 17) * 2 / 3).coerceAtLeast(1))
        digit(g, x, y, 5, s, lv, edge)
        val sw = maxOf(2, s.t / 2)
        val sx = x + s.w / 2 - sw / 2
        g.fillRect(sx, y - s.t + 1, sw, s.t, lv)
        g.fillRect(sx, y + s.h - 1, sw, s.t, lv)
    }

    /** The `$` occupies a full digit cell so a scoreboard column lines up. */
    fun dollarWidth(s: Seg): Int = s.w

    // ================================================================ scoreboard
    /**
     * `$847 · W12 · L3` as Adam asked for it (§4) — cash, tournaments won,
     * Loser Count — with the numbers in seven-segment and the CAPTIONS in the
     * app's face beneath them, because a segment display cannot spell "CASH".
     *
     * Returns the drawn width. [captions] off gives the numbers alone, for a
     * lens band with no room under them.
     */
    fun scoreboard(g: Gray8, tx: TextRasterizer, x: Int, y: Int, s: Seg,
        cash: Int, won: Int, lost: Int, lv: Int = Level.of(9), captions: Boolean = true): Int {
        val cf = FontSpec(Face.SYSTEM, if (s.h >= 40) 13 else 11, bold = true)
        val capY = y + s.h + 3
        var cx = x
        fun cell(caption: String, draw: (Int) -> Int, hot: Boolean = false) {
            val w = draw(cx)
            if (captions) {
                val cw = tx.measure(caption, cf)
                tx.draw(g, ((cx + (w - cw) / 2) / 4) * 4, capY, caption, cf,
                    if (hot) Level.MID else Level.DIM)
            }
            cx += w + GROUP_GAP
        }
        cell("CASH", { gx ->
            dollarMark(g, gx, y, s, lv)
            val txt = group(cash)
            digits(g, gx + dollarWidth(s) + s.gap, y, txt, s, lv)
            dollarWidth(s) + s.gap + digitsWidth(txt, s)
        })
        cell("WON", { gx -> digits(g, gx, y, won.toString(), s, lv); digitsWidth(won.toString(), s) })
        // the Loser Count is the one number that is meant to embarrass: it is
        // drawn HOT once it is not zero (verdict 14)
        cell("LOSER", { gx ->
            val l = if (lost > 0) Level.HOT else lv
            digits(g, gx, y, lost.toString(), s, l)
            digitsWidth(lost.toString(), s)
        }, hot = lost > 0)
        return cx - x - GROUP_GAP
    }

    /** The drawn WIDTH of [scoreboard] — measured, not rendered into a
     *  throwaway surface, because a lens paints on every frame. */
    fun scoreboardWidth(tx: TextRasterizer, s: Seg, cash: Int, won: Int, lost: Int): Int {
        val cash_ = dollarWidth(s) + s.gap + digitsWidth(group(cash), s)
        return cash_ + GROUP_GAP + digitsWidth(won.toString(), s) +
            GROUP_GAP + digitsWidth(lost.toString(), s)
    }

    /** The whole drawn extent of [scoreboard], captions included. */
    fun scoreboardHeight(s: Seg, captions: Boolean = true): Int =
        if (captions) s.h + 3 + 14 else s.h

    private const val GROUP_GAP = 28
}
