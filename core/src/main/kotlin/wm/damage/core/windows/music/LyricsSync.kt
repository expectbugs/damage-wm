package wm.damage.core.windows.music

/**
 * PURE lyric machinery (`MUSIC.md` §3.7): LRC parsing — line stamps
 * `[mm:ss.xx]` (several per line allowed) and enhanced word stamps
 * `<mm:ss.xx>` — and the scheduler math that makes the on-glass line land
 * on the beat: the line for a position is found through the per-output
 * latency OFFSET (Bluetooth adds ~100–250 ms, calibrated by the ring), and
 * the next flush is sent AHEAD by the known display latency (the measured
 * `60 + bytes/50` curve). No clock in here: callers pass positions.
 */
object LyricsSync {

    data class Word(val tMs: Long, val text: String)
    data class Line(val tMs: Long, val text: String, val words: List<Word> = emptyList())

    class Parsed(val lines: List<Line>, val enhanced: Boolean) {
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    private val STAMP = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val WORD = Regex("<(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    private val META = Regex("^\\[[a-zA-Z]+:[^]]*]\\s*$")

    private fun ms(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3].ifEmpty { "0" }.padEnd(3, '0').take(3).toLong()
        return min * 60_000 + sec * 1_000 + frac
    }

    /** Tolerant: blank lines and `[ar:…]` metadata skipped; several stamps on
     *  one line repeat it; word stamps make the line enhanced. Sorted by time. */
    fun parse(lrc: String): Parsed {
        val out = ArrayList<Line>()
        var enhanced = false
        for (raw in lrc.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || META.matches(line)) continue
            val stamps = STAMP.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val rest = line.substring(stamps.last().range.last + 1)
            val words = ArrayList<Word>()
            val wm = WORD.findAll(rest).toList()
            val text: String
            if (wm.isNotEmpty()) {
                enhanced = true
                for ((i, w) in wm.withIndex()) {
                    val end = if (i + 1 < wm.size) wm[i + 1].range.first else rest.length
                    val t = rest.substring(w.range.last + 1, end).trim()
                    if (t.isNotEmpty()) words.add(Word(ms(w), t))
                }
                text = words.joinToString(" ") { it.text }
            } else text = rest.trim()
            for (s in stamps) out.add(Line(ms(s), text, words))
        }
        out.sortBy { it.tMs }
        return Parsed(out, enhanced)
    }

    /** Index of the line active at [posMs] (the last stamp ≤ position), -1 before the first. */
    fun lineAt(lines: List<Line>, posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var best = -1
        while (lo <= hi) {
            val m = (lo + hi) / 2
            if (lines[m].tMs <= posMs) { best = m; lo = m + 1 } else hi = m - 1
        }
        return best
    }

    /** The word active within [line] at [posMs] (enhanced lyrics), -1 before the first. */
    fun wordAt(line: Line, posMs: Long): Int {
        var best = -1
        for ((i, w) in line.words.withIndex()) if (w.tMs <= posMs) best = i else break
        return best
    }

    /**
     * The scheduler's one equation. [audioPosMs] = the player's position
     * (what the buds will HEAR [deviceOffsetMs] later than the player thinks
     * — a positive offset means the sound lags the position). The line the
     * listener hears now is the line at `audioPos - deviceOffset`.
     */
    fun heardPos(audioPosMs: Long, deviceOffsetMs: Long): Long = audioPosMs - deviceOffsetMs

    /**
     * When to START the flush for the NEXT line so its pixels land as the
     * line is heard: at the line's stamp + the device offset − the display
     * latency; never in the past. Returns the delay in ms from now, or
     * null when no line follows. [displayMs] is the modeled cost of the
     * repaint (`60 + bytes/50`).
     */
    fun nextFlushDelay(lines: List<Line>, audioPosMs: Long, deviceOffsetMs: Long, displayMs: Long): Long? {
        val heard = heardPos(audioPosMs, deviceOffsetMs)
        val cur = lineAt(lines, heard)
        val next = lines.getOrNull(cur + 1) ?: return null
        // the next line is heard at audio position next.tMs + offset; the
        // flush must begin displayMs before that instant
        val due = (next.tMs + deviceOffsetMs) - audioPosMs - displayMs
        return maxOf(0L, due)
    }

    /** The context window painted around the current line: [before] lines
     *  above, the current, and the rest below, clipped to the song. */
    fun window(lines: List<Line>, cur: Int, before: Int, total: Int): IntRange {
        if (lines.isEmpty() || total <= 0) return IntRange.EMPTY
        val start = (cur - before).coerceIn(0, maxOf(0, lines.size - total))
        val end = minOf(lines.size - 1, start + total - 1)
        return start..end
    }

    /** Plain-text pages for lyrics without stamps: [linesPerPage] lines each. */
    fun pages(plain: String, linesPerPage: Int): List<List<String>> {
        val ls = plain.split('\n').map { it.trimEnd('\r') }
        if (ls.isEmpty()) return emptyList()
        return ls.chunked(maxOf(1, linesPerPage))
    }
}
