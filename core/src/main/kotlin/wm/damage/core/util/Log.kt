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

    fun addSink(s: Sink) { sinks.add(s) }

    fun replaceSinks(s: Sink) { sinks.clear(); sinks.add(s) }

    fun d(tag: String, msg: String) = emit(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = emit(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = emit(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = emit(Level.ERROR, tag, msg)

    fun e(tag: String, msg: String, t: Throwable) =
        emit(Level.ERROR, tag, "$msg: ${t::class.simpleName}: ${t.message}")

    private fun emit(level: Level, tag: String, msg: String) {
        if (level < minLevel && level < Level.WARN) return
        for (s in sinks) s.log(level, tag, msg)
    }
}
