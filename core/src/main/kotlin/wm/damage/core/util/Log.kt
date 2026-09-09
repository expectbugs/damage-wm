package wm.damage.core.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loud, minimal logging. NO SILENT FAILURES: warnings and errors always reach
 * every sink; the default sink prints, platforms add their own (logcat, the
 * phone notification channel per DESIGN.md §9.3). Nothing here ever swallows.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String)
    }

    private val sinks = CopyOnWriteArrayList<Sink>(
        listOf(Sink { level, tag, message ->
            val out = if (level >= Level.WARN) System.err else System.out
            out.println("[${level.name.first()}] $tag: $message")
        }),
    )

    @Volatile var minLevel: Level = Level.INFO

    /** The last [RECENT_LINES] lines that passed the level gate, oldest first
     *  (§42, 2026-09-09): served by every host's replica at `/log`, because
     *  the phone has no adb on Adam's setup and a morning's three-minute loop
     *  of session attempts left only its side effects in the journal. Each
     *  line: `HH:mm:ss.SSS L tag: message`, local time. */
    const val RECENT_LINES = 4000
    private val recentLines = ArrayDeque<String>(RECENT_LINES)
    private val stamp = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** A copy of the retained lines, oldest first; [tail] keeps only the last that many. */
    fun recent(tail: Int = RECENT_LINES): List<String> = synchronized(recentLines) {
        if (tail >= recentLines.size) recentLines.toList() else recentLines.toList().takeLast(tail)
    }

    fun addSink(s: Sink) { sinks.add(s) }

    fun removeSink(s: Sink) { sinks.remove(s) }

    fun replaceSinks(s: Sink) { sinks.clear(); sinks.add(s) }

    fun d(tag: String, msg: String) = emit(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = emit(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = emit(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = emit(Level.ERROR, tag, msg)

    fun e(tag: String, msg: String, t: Throwable) =
        emit(Level.ERROR, tag, "$msg: ${t::class.simpleName}: ${t.message}")

    private fun emit(level: Level, tag: String, msg: String) {
        if (level < minLevel && level < Level.WARN) return
        synchronized(recentLines) {
            if (recentLines.size >= RECENT_LINES) recentLines.removeFirst()
            recentLines.addLast("${java.time.LocalTime.now().format(stamp)} ${level.name.first()} $tag: $msg")
        }
        for (s in sinks) s.log(level, tag, msg)
    }
}
