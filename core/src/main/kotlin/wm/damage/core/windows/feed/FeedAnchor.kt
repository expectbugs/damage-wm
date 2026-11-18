package wm.damage.core.windows.feed

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B2 (`CONTRACT.md` §5: Feed's anchor `@b<block>.<offset>`): the glasses' wrapped article lines and
 * the shared place `{b, o}` — a block index and a character offset in that block's text — each way. A wrapped line
 * knows its block and where its text starts in the block's text; `Block.text` never changes (B3), so an offset saved
 * on one surface means the same words on another.
 */
object FeedAnchor {
    /** One wrapped line: its block (-1: the title, byline, a spacer) and its first character's offset there. */
    data class Line(val block: Int, val off: Int)

    /** The place of the page whose top line is [top]: the first line from there that belongs to a block. */
    fun at(lines: List<Line>, top: Int): Pair<Int, Int>? {
        for (i in top.coerceAtLeast(0) until lines.size) {
            val l = lines[i]
            if (l.block >= 0) return l.block to l.off
        }
        return null
    }

    /** The line that shows place ([b], [o]): the last line of block [b] starting at or before [o], else the first line
     *  of the next block there is; null when the lines hold no such block or later one. */
    fun line(lines: List<Line>, b: Int, o: Int): Int? {
        var best: Int? = null
        for ((i, l) in lines.withIndex()) {
            if (l.block == b && l.off <= o) best = i
            if (l.block > b) return best ?: i
        }
        return best
    }

    /** Where each of [wrapped] (the lines [text] wrapped into, in order, spaces dropped at the breaks) starts in
     *  [text]; a line not found where expected starts where the previous one ended. */
    fun offsets(text: String, wrapped: List<String>): IntArray {
        val out = IntArray(wrapped.size)
        var at = 0
        for ((i, w) in wrapped.withIndex()) {
            val found = if (w.isEmpty()) -1 else text.indexOf(w.trim(), at)
            val start = if (found >= 0) found else at
            out[i] = start
            at = (start + w.trim().length).coerceAtMost(text.length)
        }
        return out
    }
}
