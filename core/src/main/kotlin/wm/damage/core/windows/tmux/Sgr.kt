package wm.damage.core.windows.tmux

/**
 * SGR/ANSI parsing for `tmux capture-pane -e` output (TMUX.md §3.3): escaped
 * lines -> a grid of styled cells on the 16-gray panel.
 *
 * tmux re-encodes pane attributes as standard SGR sequences, so the subset
 * here is bounded and known: SGR (CSI ... m) with 0/1/2/4/7/22/24/27, the
 * 16 base colours (30-37/90-97 fg, 40-47/100-107 bg), 256-colour (38;5;n /
 * 48;5;n), truecolour (38;2;r;g;b / 48;2;r;g;b — Claude Code uses it), and
 * defaults (39/49). Anything else escape-shaped is skipped and COUNTED, never
 * silently mangled into text (NO SILENT FAILURES: the count reaches the
 * window's status line).
 *
 * Colour -> gray: luminance of the xterm palette value, quantized to the 16
 * levels, with a floor of 3 on non-default foregrounds so a saturated blue
 * stays readable rather than faithful-and-invisible (the panel shows one
 * colour; contrast is the job, fidelity is not available).
 */
object Sgr {

    private const val ESC = '\u001B'
    private const val BEL = '\u0007'

    /** Default foreground: the reading level (Level.BODY / 17). */
    const val FG_DEFAULT = 8
    const val BG_DEFAULT = 0

    const val BOLD = 1
    const val DIM = 2
    const val UNDERLINE = 4
    const val REVERSE = 8

    /** One parsed row: aligned arrays per COLUMN. A wide glyph owns its column
     *  and marks the next with [CONT] (codepoint 0); blanks are ' ' cells. */
    class Row(val cp: IntArray, val fg: ByteArray, val bg: ByteArray, val flags: ByteArray) {
        val cols: Int get() = cp.size
    }

    const val CONT = 0

    /** Mutable SGR state while scanning a line. */
    private class State {
        var fg = FG_DEFAULT
        var bg = BG_DEFAULT
        var flags = 0
    }

    class Parsed(val rows: List<Row>, val skippedEscapes: Int, val droppedCombining: Int)

    /** Parse captured lines into [cols]-wide rows (tmux pads with spaces up to
     *  the pane width only where content exists; we pad the rest). Lines
     *  longer than [cols] are the pane's own truth and are kept whole — the
     *  renderer clips to the grid, the model does not lie. */
    fun parse(lines: List<String>, cols: Int): Parsed {
        var skipped = 0
        var combining = 0
        val rows = ArrayList<Row>(lines.size)
        for (line in lines) {
            val cp = IntArray(maxOf(cols, 4)) { ' '.code }
            val fg = ByteArray(cp.size) { FG_DEFAULT.toByte() }
            val bg = ByteArray(cp.size) { BG_DEFAULT.toByte() }
            val fl = ByteArray(cp.size)
            var out = cp
            var outFg = fg
            var outBg = bg
            var outFl = fl
            var col = 0
            val st = State()
            var i = 0
            fun ensure(n: Int) {
                if (n <= out.size) return
                val grown = maxOf(n, out.size * 2)
                out = out.copyOf(grown).also { it.fill(' '.code, out.size, grown) }
                outFg = outFg.copyOf(grown).also { it.fill(FG_DEFAULT.toByte(), outFg.size, grown) }
                outBg = outBg.copyOf(grown)
                outFl = outFl.copyOf(grown)
            }
            while (i < line.length) {
                val c = line[i]
                if (c == ESC) {
                    i = skipOrApplyEscape(line, i, st) { skipped++ }
                    continue
                }
                val point = line.codePointAt(i)
                i += Character.charCount(point)
                val w = width(point)
                if (w == 0) { combining++; continue }
                ensure(col + w)
                out[col] = point
                outFg[col] = st.fg.toByte()
                outBg[col] = st.bg.toByte()
                outFl[col] = st.flags.toByte()
                if (w == 2) {
                    out[col + 1] = CONT
                    outFg[col + 1] = st.fg.toByte()
                    outBg[col + 1] = st.bg.toByte()
                    outFl[col + 1] = st.flags.toByte()
                }
                col += w
            }
            rows.add(Row(out, outFg, outBg, outFl))
        }
        return Parsed(rows, skipped, combining)
    }

    /** One styled span of a logical line — the FLOW model (2026-08-31, the
     *  grid retirement): [fg]/[bg] are already-mapped 0..15 levels, [flags]
     *  the BOLD/DIM/UNDERLINE/REVERSE set. Wide glyphs and combining marks
     *  stay in [text] — flow has no columns, the rasterizer lays them out. */
    class Run(val text: String, val fg: Int, val bg: Int, val flags: Int)

    /** Parse one captured line into styled runs (same state machine as the
     *  cell parser; unknown escapes are skipped and COUNTED via [onSkip]). */
    fun parseRuns(line: String, onSkip: () -> Unit = {}): List<Run> {
        val out = ArrayList<Run>(4)
        val sb = StringBuilder()
        val st = State()
        var fg = st.fg
        var bg = st.bg
        var fl = st.flags
        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(Run(sb.toString(), fg, bg, fl))
                sb.setLength(0)
            }
        }
        var i = 0
        while (i < line.length) {
            if (line[i] == ESC) {
                i = skipOrApplyEscape(line, i, st, onSkip)
                if (st.fg != fg || st.bg != bg || st.flags != fl) {
                    flush()
                    fg = st.fg; bg = st.bg; fl = st.flags
                }
                continue
            }
            sb.append(line[i])
            i++
        }
        flush()
        return out
    }

    /** Strip every escape from [line] — the history/reading path wants plain
     *  text through the ordinary wrap machinery. */
    fun strip(line: String): String {
        if (ESC !in line) return line
        val sb = StringBuilder(line.length)
        var i = 0
        val st = State()
        while (i < line.length) {
            if (line[i] == ESC) { i = skipOrApplyEscape(line, i, st) {}; continue }
            sb.append(line[i]); i++
        }
        return sb.toString()
    }

    /** Consume one escape sequence at [i]; SGR mutates [st], everything else
     *  is skipped and reported through [onSkip]. Returns the next index. */
    private inline fun skipOrApplyEscape(line: String, i: Int, st: State, onSkip: () -> Unit): Int {
        var j = i + 1
        if (j >= line.length) return j
        when (line[j]) {
            '[' -> {
                j++
                val start = j
                while (j < line.length && line[j].code in 0x20..0x3F) j++
                if (j < line.length) {
                    val final = line[j]
                    if (final == 'm') applySgr(line.substring(start, j), st) else onSkip()
                    j++
                } else onSkip()
            }
            ']' -> {   // OSC ... (BEL | ESC \)
                onSkip()
                j++
                while (j < line.length && line[j] != BEL &&
                    !(line[j] == ESC && j + 1 < line.length && line[j + 1] == '\\')) j++
                j += if (j < line.length && line[j] == BEL) 1 else 2
            }
            else -> { onSkip(); j++ }
        }
        return minOf(j, line.length)
    }

    private fun applySgr(params: String, st: State) {
        val p = if (params.isEmpty()) intArrayOf(0)
        else params.split(';', ':').map { it.toIntOrNull() ?: 0 }.toIntArray()
        var i = 0
        while (i < p.size) {
            when (val n = p[i]) {
                0 -> { st.fg = FG_DEFAULT; st.bg = BG_DEFAULT; st.flags = 0 }
                1 -> st.flags = st.flags or BOLD
                2 -> st.flags = st.flags or DIM
                4 -> st.flags = st.flags or UNDERLINE
                7 -> st.flags = st.flags or REVERSE
                21, 22 -> st.flags = st.flags and (BOLD or DIM).inv()
                24 -> st.flags = st.flags and UNDERLINE.inv()
                27 -> st.flags = st.flags and REVERSE.inv()
                in 30..37 -> st.fg = fgLevel(basic(n - 30))
                39 -> st.fg = FG_DEFAULT
                in 40..47 -> st.bg = bgLevel(basic(n - 40))
                49 -> st.bg = BG_DEFAULT
                in 90..97 -> st.fg = fgLevel(basic(n - 90 + 8))
                in 100..107 -> st.bg = bgLevel(basic(n - 100 + 8))
                38, 48 -> {
                    val fgSide = n == 38
                    val mode = p.getOrNull(i + 1)
                    if (mode == 5 && i + 2 < p.size) {
                        val lum = xterm256(p[i + 2])
                        if (fgSide) st.fg = fgLevel(lum) else st.bg = bgLevel(lum)
                        i += 2
                    } else if (mode == 2 && i + 4 < p.size) {
                        val lum = lum(p[i + 2], p[i + 3], p[i + 4])
                        if (fgSide) st.fg = fgLevel(lum) else st.bg = bgLevel(lum)
                        i += 4
                    } else i = p.size   // malformed extension: drop the rest of this SGR
                }
                else -> { /* unsupported attribute (blink, italic, …): ignored */ }
            }
            i++
        }
    }

    private fun lum(r: Int, g: Int, b: Int): Int = (r * 299 + g * 587 + b * 114) / 1000

    /** Non-default foregrounds floor at 3: readable beats faithful. */
    private fun fgLevel(lum255: Int): Int = maxOf(3, (lum255 * 15 + 127) / 255)
    private fun bgLevel(lum255: Int): Int = (lum255 * 15 + 127) / 255

    /** xterm's 16 base colours as luminance 0..255. */
    private fun basic(n: Int): Int = when (n) {
        0 -> 0; 1 -> 61; 2 -> 120; 3 -> 181; 4 -> 27; 5 -> 88; 6 -> 147; 7 -> 229
        8 -> 127; 9 -> 76; 10 -> 150; 11 -> 226; 12 -> 63; 13 -> 105; 14 -> 179; else -> 255
    }

    /** Luminance of xterm-256 entry [n]: 16 base, 216 cube, 24-step gray ramp. */
    private fun xterm256(n: Int): Int = when {
        n < 16 -> basic(n.coerceIn(0, 15))
        n in 16..231 -> {
            val c = n - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            lum(steps[c / 36], steps[(c / 6) % 6], steps[c % 6])
        }
        n in 232..255 -> 8 + (n - 232) * 10
        else -> 229
    }

    /** Column width: 2 for East-Asian wide + emoji blocks (matching what tmux
     *  will have laid out), 0 for combining marks, else 1. An exotic glyph we
     *  misjudge shears one line by one column — visible, bounded, logged via
     *  the parse counts. */
    fun width(cp: Int): Int = when {
        cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x20D0..0x20FF || cp == 0x200D ||
            cp in 0xFE00..0xFE0F -> 0
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF ||
            cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF ||
            cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F ||
            cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 ||
            cp in 0x1F300..0x1F64F || cp in 0x1F680..0x1F6FF || cp in 0x1F900..0x1F9FF ||
            cp in 0x20000..0x3FFFD -> 2
        else -> 1
    }
}
