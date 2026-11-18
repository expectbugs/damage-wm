package wm.damage.core.windows.tmux

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B10 (`CONTRACT.md` §3.4 as amended by `REPLAN.md` §15 B4): the terminal stream a desk browser's
 * terminal reads. One tmux control-mode client per (host, session) while at least one viewer holds it, attached with
 * `-f ignore-size` (`man tmux` 3.5a: the client does not affect the size of other clients), so his own client's size
 * never changes. It writes only its init commands, never a key (keys are program ops, `TmuxScopes`).
 *
 * Measured on a throwaway server (`tmux -L damage-test-…`, tmux 3.5a, 2026-11-17; the report's part H):
 *  - each command gets its own `%begin <t> <n> 1` … `%end <t> <n> 1` block (flag 1: sent by this client; the attach's
 *    own block carries 0), also two commands on one line;
 *  - no `%output` falls between the display block and the capture block, the capture holds every line whose `%output`
 *    came before its `%begin`, and the first `%output` after its `%end` is the next line (10 of 10 trials) — so a
 *    viewer waiting for an init drops `%output` until the capture's `%end` and takes it after;
 *  - `%output` arrives for every pane of the session, the current window's or not: only the active pane's is sent;
 *  - a split or select-pane gives `%window-pane-changed`, select-window `%session-window-changed`, a resize
 *    `%layout-change`, a rename `%session-renamed $id <name>`, kill-session `%exit` and the end of the output;
 *  - closing the client's stdin ends it within milliseconds (`%exit`, then EOF); SIGKILL of its parent ends it within
 *    0.05 s (its pipes close). Over ssh this is not measured (no ssh from a build).
 */
class TmuxStream(
    /** The argv of a control client of (host, session), or null for a host the PC does not know. */
    private val argv: (String, String) -> List<String>?,
    /** The process seam: a real process, or a scripted one (`LookServer`, tests). */
    private val spawn: (List<String>) -> Proc = ::realProcess,
) : AutoCloseable {

    /** A started control client as the stream needs it. */
    interface Proc {
        val stdin: OutputStream
        val stdout: InputStream
        /** What the client said on stderr so far (a few KB, for the end's reason). */
        fun stderr(): String
        /** Waits up to [ms] for its exit; true when it has exited. */
        fun exited(ms: Long): Boolean
        fun exitCode(): Int
        val name: String
    }

    private val clients = ConcurrentHashMap<Pair<String, String>, Client>()

    /** [key] views (host, session): its messages go to [send]. A second attach of the same key starts it afresh. */
    fun attach(host: String, session: String, key: Long, send: (JsonObject) -> Unit) {
        val at = host to session
        // one try: a client its last viewer closes has already left [clients] ([Client.finish]); one that has ended
        // (its session gone, or no such session) ends this viewer with tmux's words — never a loop of new processes
        val c = clients.computeIfAbsent(at) { Client(host, session) }
        val why = c.join(key, send) ?: return
        clients.remove(at, c)
        send(end(why))
    }

    fun detach(host: String, session: String, key: Long) {
        clients[host to session]?.leave(key)
    }

    /** How many control clients run now (tests, the diagnosis). */
    fun running(): Int = clients.size

    /** At the process's stop: every client's stdin closes, so each ends (its viewers are told). */
    override fun close() {
        for (c in clients.values) c.closeAll("the PC is stopping")
        clients.clear()
    }

    private class Viewer(val send: (JsonObject) -> Unit) {
        /** The init this viewer waits for, or 0 when it is live (takes `%output`). */
        var waitingFor = 0
    }

    /** One init in flight: its two blocks (display, then capture). */
    private class Init(val gen: Int, val all: Boolean) {
        var blocks = 0
        var begun = false
        var display: String? = null
        var error: String? = null
        val capture = ArrayList<ByteArray>()
    }

    private inner class Client(val host: String, val session: String) {
        private val lock = Any()
        private val viewers = LinkedHashMap<Long, Viewer>()
        private val inits = ArrayDeque<Init>()
        private var gen = 0
        private var pane: String? = null
        private var sessionId: String? = null
        private var ended = false
        private var closing = false
        /** The block being read: its number, and whether it is one of ours (flag 1). */
        private var block: String? = null
        private var ours = false
        /** The attach's own block's lines (flag 0): when it ends `%error`, its words are the viewers' end reason (a
         *  session that does not exist: `can't find session: <name>`, then `%exit`; measured). */
        private val attachLines = ArrayList<String>()
        private var attachError: String? = null
        private val proc: Proc

        init {
            val a = argv(host, session) ?: throw IllegalArgumentException("unknown tmux host '$host'")
            proc = spawn(a)
            Log.i(TAG, "control client for $host/$session started (${proc.name})")
            Thread({ read() }, "tmux-stream-$host-$session").apply { isDaemon = true }.start()
        }

        /** Null when [key] joined; else why this client takes no viewer (it ended, or its last viewer closed it). */
        fun join(key: Long, send: (JsonObject) -> Unit): String? = synchronized(lock) {
            if (ended || closing) return endWhy()
            viewers[key] = Viewer(send)
            requestInit(listOf(key), all = false)
            null
        }

        /** Why this client ended, in tmux's words when it said any. */
        private fun endWhy(): String {
            val err = proc.stderr().trim()
            return if (err.isNotEmpty()) "tmux: ${err.lines().last()}" else "tmux's control client ended"
        }

        fun leave(key: Long) = synchronized(lock) {
            if (viewers.remove(key) == null || viewers.isNotEmpty()) return
            finish("its last viewer left")
        }

        fun closeAll(why: String) = synchronized(lock) {
            for (v in viewers.values) v.send(end(why))
            viewers.clear()
            finish(why)
        }

        /** Under [lock]: no viewer holds this client — its stdin closes, so tmux ends it; the exit is waited for on a
         *  thread of its own and said if it lingers. */
        private fun finish(why: String) {
            if (closing) return
            closing = true
            clients.remove(host to session, this)
            try { proc.stdin.close() } catch (e: Exception) { Log.w(TAG, "closing the control client of $host/$session: ${e.message}") }
            Thread({
                if (!proc.exited(LINGER_MS)) {
                    Log.w(TAG, "the control client of $host/$session (${proc.name}) has not ended ${LINGER_MS / 1000} s after its stdin closed; waiting on")
                    while (!proc.exited(60_000)) Log.w(TAG, "the control client of $host/$session (${proc.name}) is still running")
                }
                Log.i(TAG, "control client for $host/$session ended ($why; exit ${proc.exitCode()})")
            }, "tmux-stream-end-$host-$session").apply { isDaemon = true }.start()
        }

        /** Under [lock]: asks tmux for the pane's place and screen; [keys] wait for it (their `%output` dropped until
         *  it lands: the capture holds it). [all]: every viewer, after a change of pane, window or layout. */
        private fun requestInit(keys: Collection<Long>, all: Boolean) {
            if (keys.isEmpty()) return
            if (all && inits.any { it.all && !it.begun }) return      // one not yet run already sees the change
            val g = ++gen
            for (k in keys) viewers[k]?.waitingFor = g
            inits.addLast(Init(g, all))
            val t = LocalTmuxProvider.shq("=$session:")
            write("display-message -p -t $t '#{pane_id} #{cursor_x} #{cursor_y} #{pane_width} #{pane_height} " +
                "#{alternate_on} #{cursor_flag}'\ncapture-pane -p -e -t $t\n")
        }

        private fun write(s: String) {
            try {
                proc.stdin.write(s.toByteArray(Charsets.UTF_8))
                proc.stdin.flush()
            } catch (e: Exception) {
                // the client has ended: its reader sees the end of its output and tells the viewers
                Log.w(TAG, "the control client of $host/$session took no command: ${e.message}")
            }
        }

        private fun read() {
            val inp = proc.stdout
            val line = ByteArrayOutputStream(256)
            try {
                while (true) {
                    val b = inp.read()
                    if (b < 0) break
                    if (b == '\n'.code) {
                        val l = line.toByteArray()
                        line.reset()
                        synchronized(lock) { onLine(l) }
                    } else line.write(b)
                }
            } catch (e: Exception) {
                if (!closing) Log.w(TAG, "reading the control client of $host/$session ended: ${e.message}")
            }
            if (!closing) proc.exited(LINGER_MS)      // its stderr read to the end before an end's reason is taken
            synchronized(lock) {
                ended = true
                clients.remove(host to session, this)
                if (viewers.isNotEmpty()) {
                    val why = endWhy()
                    for (v in viewers.values) v.send(end(why))
                    viewers.clear()
                }
            }
            if (!closing) Log.i(TAG, "control client for $host/$session ended by itself (exit ${if (proc.exited(LINGER_MS)) proc.exitCode() else "not yet"})")
        }

        /** Under [lock]: one line of the client's output. */
        private fun onLine(l: ByteArray) {
            val b = block
            if (b != null) {
                if (startsWith(l, "%end ") || startsWith(l, "%error ")) {
                    val f = String(l, Charsets.UTF_8).split(' ')
                    if (f.getOrNull(2) == b) {
                        block = null
                        if (ours) blockDone(f[0] == "%error")
                        else if (f[0] == "%error") attachError = attachLines.joinToString(" ").ifEmpty { null }
                        return
                    }
                }
                if (ours) inits.firstOrNull()?.let { i -> if (i.blocks == 0) i.display = String(l, Charsets.UTF_8) else i.capture += l }
                else attachLines += String(l, Charsets.UTF_8)
                return
            }
            if (l.isEmpty() || l[0] != '%'.code.toByte()) return
            val s = String(l, 0, minOf(l.size, 200), Charsets.UTF_8)
            val f = s.split(' ')
            when (f[0]) {
                "%output" -> if (f.getOrNull(1) == pane) {
                    val start = "%output ".length + f[1].length + 1
                    if (start <= l.size) {
                        val data = Base64.getEncoder().encodeToString(unescape(l, start))
                        for (v in viewers.values) if (v.waitingFor == 0) v.send(buildJsonObject { put("data", data) })
                    }
                }
                "%begin" -> {
                    block = f.getOrNull(2)
                    ours = f.getOrNull(3) == "1" && inits.isNotEmpty()
                    if (ours) inits.first().begun = true else attachLines.clear()
                }
                "%session-changed" -> if (sessionId == null) sessionId = f.getOrNull(1)
                "%window-pane-changed", "%session-window-changed", "%layout-change" ->
                    if (f[0] != "%session-window-changed" || f.getOrNull(1) == sessionId) requestInit(viewers.keys.toList(), all = true)
                "%session-renamed" -> if (f.getOrNull(1) == sessionId) {
                    val to = s.substringAfter(' ').substringAfter(' ')
                    for (v in viewers.values) v.send(buildJsonObject {
                        put("end", buildJsonObject {
                            put("reason", "the session was renamed to $to")
                            put("to", TmuxCore.sessionTarget(host, to))
                        })
                    })
                    viewers.clear()
                    finish("renamed to $to")
                }
                "%exit" -> {
                    val why = s.removePrefix("%exit").trim().ifEmpty { attachError?.let { "tmux: $it" } ?: "the session ended" }
                    for (v in viewers.values) v.send(end(why))
                    viewers.clear()
                    closing = true                 // tmux ends it; the reader sees the end of the output
                }
                else -> {}
            }
        }

        /** Under [lock]: one of our blocks ended. */
        private fun blockDone(error: Boolean) {
            val i = inits.firstOrNull() ?: return
            if (error) i.error = (if (i.blocks == 0) i.display else i.capture.lastOrNull()?.let { String(it, Charsets.UTF_8) })
                ?: "tmux refused the init"
            i.blocks++
            if (i.blocks < 2) return
            inits.removeFirst()
            val waiting = viewers.filterValues { it.waitingFor == i.gen }
            val err = i.error
            val d = i.display?.split(' ')
            if (err != null || d == null || d.size < 7) {
                val why = err ?: "tmux answered no pane geometry"
                Log.w(TAG, "the init of $host/$session failed: $why")
                for ((k, v) in waiting) { v.send(end(why)); viewers.remove(k) }
                if (viewers.isEmpty()) finish("its init failed")
                return
            }
            pane = d[0]
            val out = ByteArrayOutputStream()
            for ((n, l) in i.capture.withIndex()) { if (n > 0) out.write('\n'.code); out.write(l) }
            val init = buildJsonObject {
                put("init", buildJsonObject {
                    put("cols", d[3].toIntOrNull() ?: 80)
                    put("rows", d[4].toIntOrNull() ?: 24)
                    put("cursor", buildJsonObject {
                        put("x", d[1].toIntOrNull() ?: 0)
                        put("y", d[2].toIntOrNull() ?: 0)
                        put("visible", d[6] == "1")
                    })
                    put("alternate", d[5] == "1")
                    put("capturedAt", System.currentTimeMillis())     // the picture's age while the PC is away
                    put("data", Base64.getEncoder().encodeToString(out.toByteArray()))
                })
            }
            for (v in waiting.values) { v.send(init); v.waitingFor = 0 }
        }
    }

    companion object {
        private const val TAG = "tmux-stream"
        /** How long an ended client may take before its lingering is said (a liveness note: nothing is killed). */
        const val LINGER_MS = 5_000L

        private fun end(reason: String) = buildJsonObject { put("end", buildJsonObject { put("reason", reason) }) }

        private fun startsWith(l: ByteArray, p: String): Boolean {
            if (l.size < p.length) return false
            for (i in p.indices) if (l[i] != p[i].code.toByte()) return false
            return true
        }

        /** `%output`'s data: tmux writes a byte below 32 and the backslash as `\ooo` (octal), every other byte as is. */
        fun unescape(l: ByteArray, from: Int): ByteArray {
            val out = ByteArrayOutputStream(l.size - from)
            var i = from
            while (i < l.size) {
                val b = l[i]
                if (b == '\\'.code.toByte() && i + 3 < l.size && (1..3).all { k -> l[i + k] in '0'.code.toByte()..'7'.code.toByte() }) {
                    out.write(((l[i + 1] - 48) shl 6) or ((l[i + 2] - 48) shl 3) or (l[i + 3] - 48))
                    i += 4
                } else {
                    out.write(b.toInt())
                    i++
                }
            }
            return out.toByteArray()
        }

        /** A real control client: its stderr drained on a thread of its own (the last 4 KB kept for the end's reason). */
        fun realProcess(argv: List<String>): Proc {
            // a PC process started from inside a tmux session would carry its TMUX, and tmux refuses a nested attach
            val p = ProcessBuilder(argv).also { it.environment().remove("TMUX") }.start()
            val err = StringBuilder()
            val drain = Thread({
                try {
                    p.errorStream.bufferedReader().forEachLine { l ->
                        synchronized(err) { err.append(l).append('\n'); if (err.length > 4096) err.delete(0, err.length - 4096) }
                    }
                } catch (e: Exception) {
                    synchronized(err) { err.append("(its stderr could not be read: ${e.message})\n") }
                }
            }, "tmux-stream-stderr").apply { isDaemon = true }
            drain.start()
            return object : Proc {
                override val stdin: OutputStream = p.outputStream
                override val stdout: InputStream = p.inputStream.buffered()
                override fun stderr(): String = synchronized(err) { err.toString() }
                // once it has exited, its last words on stderr are read before an end's reason is taken from them
                override fun exited(ms: Long): Boolean = p.waitFor(ms, TimeUnit.MILLISECONDS).also { if (it) drain.join(ms) }
                override fun exitCode(): Int = p.exitValue()
                override val name: String = "pid ${p.pid()}"
            }
        }
    }
}
