package wm.damage.core.windows.reader

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P4 (`CONTRACT.md` §5, §12; the spec's §2 P4): a place in a book as the PC record holds it.
 */

/**
 * A place in a book: a character [offset] into the text extractor version [ver] produced, and up to [QUOTE] characters
 * of that text from the offset ([quote], "" when the writer did not have the text at hand — a migrated place). Written
 * `@c<offset>.<ver>~<quote>`; the quote runs to the end of the string, so it may hold any character.
 *
 * The quote exists for one case: a place written against another extractor version is found again by its quote
 * ([refind]). S1-1 has one extractor version, so the offset is used as it is.
 */
data class ReaderAnchor(val offset: Int, val ver: Int = EXTRACTOR, val quote: String = "") {
    init {
        require(offset >= 0) { "a place's offset is never negative ($offset)" }
        require(ver >= 1) { "an extractor version starts at 1 ($ver)" }
    }

    fun format(): String = "@c$offset.$ver~$quote"

    override fun toString() = format()

    companion object {
        /** The text extractor of this build (`Epub.load`): a place written by another version is re-found by quote. */
        const val EXTRACTOR = 1

        /** The most characters of text a place carries. */
        const val QUOTE = 32

        private val FORM = Regex("^@c(\\d{1,10})\\.(\\d{1,4})(?:~(.*))?$", RegexOption.DOT_MATCHES_ALL)

        /** The anchor at [offset] in [text], quoting what follows it. */
        fun at(text: String, offset: Int): ReaderAnchor {
            val o = offset.coerceIn(0, text.length)
            return ReaderAnchor(o, EXTRACTOR, text.substring(o, minOf(text.length, o + QUOTE)))
        }

        /** [s] as an anchor, or null when it is not one (never a guess: a malformed place is not a place). */
        fun parse(s: String?): ReaderAnchor? {
            val m = FORM.matchEntire(s ?: return null) ?: return null
            val off = m.groupValues[1].toLongOrNull()?.takeIf { it <= Int.MAX_VALUE } ?: return null
            val ver = m.groupValues[2].toIntOrNull()?.takeIf { it >= 1 } ?: return null
            return ReaderAnchor(off.toInt(), ver, m.groupValues[3])
        }

        /**
         * The offset [a] names in [text], an extractor version [ver]'s output: the offset as it is for the same version;
         * for another version, the quote's occurrence nearest the old offset, or the offset clamped when the quote is
         * empty or not found.
         */
        fun refind(a: ReaderAnchor, text: String, ver: Int = EXTRACTOR): Int {
            if (a.ver == ver || a.quote.isEmpty()) return a.offset.coerceIn(0, text.length)
            var best = -1
            var i = text.indexOf(a.quote)
            while (i >= 0) {
                if (best < 0 || kotlin.math.abs(i - a.offset) < kotlin.math.abs(best - a.offset)) best = i
                i = text.indexOf(a.quote, i + 1)
            }
            return if (best >= 0) best else a.offset.coerceIn(0, text.length)
        }
    }
}
