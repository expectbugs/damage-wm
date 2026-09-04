package wm.damage.core.windows.games.kit

import wm.damage.core.gfx.Gray8
import wm.damage.core.text.TextRasterizer

/**
 * Laying N cards in a row (`HOLDEM.md` §6). Width is never the constraint on
 * this panel — five cards at 72 wide with 16 px gaps is 424 px inside 608 —
 * so the fan only overlaps when a caller asks for more cards than fit, and it
 * always leaves the CORNER INDEX of every card visible, because that is what
 * a fanned hand is read from (§2, the G2CC finding).
 */
object HandFan {

    /** Where each card lands, and how much of it is visible. */
    data class Layout(val xs: List<Int>, val pitch: Int, val width: Int, val overlapped: Boolean)

    /**
     * [n] cards of [s] laid inside [availW], centred on [cx]. Preferred gap
     * [gap]; when they do not fit, the pitch shrinks toward the minimum that
     * still shows the corner index, and only then do cards overlap.
     */
    fun layout(n: Int, s: CardArt.Size, availW: Int, cx: Int, gap: Int): Layout {
        if (n <= 0) return Layout(emptyList(), 0, 0, false)
        val wanted = s.w + gap
        val minPitch = indexWidth(s)
        var pitch = wanted
        if (n * s.w + (n - 1) * gap > availW) {
            // grid-legal pitch: x positions must stay multiples of 4
            pitch = ((availW - s.w) / maxOf(1, n - 1) / 4) * 4
            if (pitch < minPitch) pitch = minPitch
        }
        val width = (n - 1) * pitch + s.w
        val x0 = ((cx - width / 2) / 4) * 4
        return Layout((0 until n).map { x0 + it * pitch }, pitch, width, pitch < wanted)
    }

    /** The narrowest pitch that still shows a card's corner index. */
    fun indexWidth(s: CardArt.Size): Int = (((s.pad * 2 + s.w / 2) + 3) / 4) * 4

    /**
     * Draw the fan. Overlapping cards get the 1 px SEPARATION STROKE — a dark
     * column immediately left of each card that has one behind it — so two
     * adjacent outline cards never read as one wide box.
     */
    fun draw(g: Gray8, tx: TextRasterizer, l: Layout, y: Int, s: CardArt.Size,
        cards: List<Card?>, dim: Boolean = false) {
        for ((i, x) in l.xs.withIndex()) {
            if (l.overlapped && i > 0) g.fillRect(x - 2, y, 2, s.h, 0)
            val c = cards.getOrNull(i)
            if (c == null) CardArt.back(g, x, y, s, dim) else CardArt.card(g, tx, x, y, s, c, dim)
        }
    }
}
