package wm.damage.desktop.music

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import wm.damage.core.util.Log

/** What [MpvSink] needs of the player program: a connected player, started when none runs. */
interface MpvLink {
    /** A connected player for [owner], started if none runs. Blocks until it answers on its socket or ends;
     *  throws [IOException] in words when it cannot start. */
    fun ensure(owner: MpvProcess.Owner): MpvIpc
}

/**
 * The PC's player program as a child of the service (S1-4 §3.6, lane P): mpv over its JSON IPC socket.
 *
 * - **Started lazily** by [ensure] — the first open or Play that needs sound — never by construction.
 * - **Its life is the JVM's:** `--input-ipc-client=fd://0` on the stdin pipe the JVM holds and never writes; mpv
 *   quits when that connection closes (`man mpv`; seen in the hand run: quit 0.24 s after the close), so any end of
 *   the JVM ends it. [stop] closes the pipe first for the same reason.
 * - **The pidfile** `mpv.pid` (pid, start time, socket) backs the tie up: [clearLeftover], at the service's start,
 *   ends a live leftover ONLY when its pid, its start time and our socket path in its command line all match — never
 *   a process by its name (Adam runs mpv himself on this PC).
 * - **The supervisor:** an exit nobody asked for is said ([Owner.exited]) and never restarted here — the next open
 *   or Play starts a fresh one; a command unanswered for [boundMs] (30 s), a socket not opened in that time, or a
 *   connection that ended while the process kept running ends the process (SIGKILL by its pid) and says why. Off the
 *   radio, render and input paths: this is the "supervise externally" of the first absolute rule.
 */
class MpvProcess(
    /** The program and any leading arguments (`musicPcPlayer`, default `/usr/bin/mpv`; tests: the fake's JVM). */
    private val program: List<String>,
    /** `<data>/music`: the socket and the pidfile. */
    private val dir: Path,
    /** `musicPcDevice`: the one output device he names; null = PipeWire's default. */
    private val device: String? = null,
    private val boundMs: Long = UNANSWERED_MS,
) : MpvLink {

    /** Who hears the player: its events and properties, and an exit nobody asked for. */
    interface Owner : MpvIpc.Listener {
        fun exited(detail: String)
    }

    val socket: Path = dir.resolve("mpv.sock")
    val pidfile: Path = dir.resolve("mpv.pid")

    init {
        require(socket.toString().toByteArray().size < 108) { "the player's socket path must stay under 108 bytes: $socket" }
    }

    private class Run(val proc: Process, val stdin: OutputStream, val owner: Owner) {
        val startedAtMs = MpvIpc.nowMs()
        @Volatile var ipc: MpvIpc? = null
        /** [stop]'s reason: the exit is expected, logged, not said as a fault. */
        @Volatile var asked: String? = null
        /** The supervisor's reason for ending it. */
        @Volatile var endedBy: String? = null
        /** [ensure] already threw for it: the open said the failure. */
        @Volatile var startFailed = false
    }

    private val lock = Object()
    private var run: Run? = null

    /** The command line, as built (§3.6 checked against `man mpv` 0.41 and the hand run). */
    fun argv(): List<String> = program + listOf(
        "--no-config", "--idle=yes", "--terminal=no", "--vid=no", "--audio-display=no", "--volume=100",
        "--audio-channels=stereo", "--ao=pipewire", "--input-ipc-server=$socket", "--input-ipc-client=fd://0",
    ) + (device?.let { listOf("--audio-device=$it") } ?: emptyList())

    /** The live child's pid, or null. */
    val pid: Long? get() = synchronized(lock) { run?.proc?.takeIf { it.isAlive }?.pid() }

    override fun ensure(owner: Owner): MpvIpc = synchronized(lock) {
        // A child whose connection has ended is on its way out (or the supervisor ends it): wait for the waiter.
        while (true) {
            val r = run ?: break
            val ipc = r.ipc
            if (ipc != null && ipc.open && r.proc.isAlive) return ipc
            lock.wait(100)
        }
        start(owner)
    }

    private fun start(owner: Owner): MpvIpc {
        Files.createDirectories(dir)
        Files.deleteIfExists(socket) // mpv leaves its socket file behind at exit (hand run): never connect to a stale one
        val argv = argv()
        val pb = ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
        // stdin stays a pipe: the tie. The service's environment carries only HOME and PATH (/etc/init.d/damage).
        if (pb.environment()["XDG_RUNTIME_DIR"] == null) pb.environment()["XDG_RUNTIME_DIR"] = "/run/user/${uid()}"
        val proc = try { pb.start() } catch (e: IOException) {
            throw IOException("the PC player did not start (${argv.first()}): ${e.message}", e)
        }
        val r = Run(proc, proc.outputStream, owner)
        run = r
        writePidfile(proc)
        Log.i(TAG, "started the PC player, pid ${proc.pid()}")
        Thread({ waiter(r) }, "mpv-waiter").apply { isDaemon = true }.start()
        Thread({ supervise(r) }, "mpv-supervisor").apply { isDaemon = true }.start()
        // Paced until it answers on its socket (26 ms in the hand run), it ends, or the supervisor ends it.
        while (true) {
            if (!proc.isAlive) {
                r.startFailed = true
                throw IOException("the PC player ended at its start: ${r.endedBy ?: "exit code ${proc.exitValue()}"}")
            }
            try {
                val ipc = MpvIpc.connect(socket, Watch(r))
                r.ipc = ipc
                return ipc
            } catch (e: IOException) {
                Thread.sleep(10)
            }
        }
    }

    /** The orderly end (the shutdown hook, after `now` is written): close the tie, then SIGTERM, then SIGKILL. */
    fun stop(why: String) {
        val r = synchronized(lock) { run } ?: return
        r.asked = why
        try { r.stdin.close() } catch (e: IOException) { Log.w(TAG, "closing the player's stdin: ${e.message}") }
        if (r.proc.waitFor(2, TimeUnit.SECONDS)) return
        Log.w(TAG, "the PC player (pid ${r.proc.pid()}) did not quit when its stdin closed: SIGTERM")
        r.proc.destroy()
        if (r.proc.waitFor(2, TimeUnit.SECONDS)) return
        Log.w(TAG, "the PC player (pid ${r.proc.pid()}) did not end on SIGTERM: SIGKILL")
        r.proc.destroyForcibly()
        r.proc.waitFor()
    }

    /** At the service's start: end OUR leftover player from an earlier run, recognised by the pidfile's pid, its
     *  start time and our socket in its command line together. Anything else is left alone and said. */
    fun clearLeftover() {
        val text = try { Files.readString(pidfile) } catch (e: NoSuchFileException) { return }
        Files.deleteIfExists(pidfile)
        val f = text.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val pid = f["pid"]?.toLongOrNull()
        val start = f["start"]?.toLongOrNull()
        if (pid == null || start == null) { Log.w(TAG, "the player's pidfile was unreadable (removed): $text"); return }
        val h = ProcessHandle.of(pid).orElse(null)
        if (h == null || !h.isAlive) { Log.i(TAG, "no leftover player: pid $pid from the pidfile is not running"); return }
        val startNow = h.info().startInstant().map { it.toEpochMilli() }.orElse(-1L)
        val cmd = cmdline(pid)
        val ours = startNow == start && cmd != null && "--input-ipc-server=$socket" in cmd
        if (!ours) {
            Log.w(TAG, "the pidfile names pid $pid, which is not our player (start time ${if (startNow == start) "matches" else "differs"}, " +
                "our socket ${if (cmd != null && "--input-ipc-server=$socket" in cmd) "in" else "not in"} its command line): left alone")
            return
        }
        Log.w(TAG, "a leftover PC player from an earlier run (pid $pid): SIGTERM")
        h.destroy()
        val until = MpvIpc.nowMs() + 3_000
        while (h.isAlive && MpvIpc.nowMs() < until) Thread.sleep(20)
        if (h.isAlive) Log.w(TAG, "the leftover PC player (pid $pid) is still running 3 s after SIGTERM")
        else Log.i(TAG, "the leftover PC player (pid $pid) ended")
    }

    private fun waiter(r: Run) {
        val code = r.proc.waitFor()
        r.ipc?.close()
        synchronized(lock) {
            if (run === r) run = null
            removePidfileOf(r.proc.pid())
            val detail = "the PC player (pid ${r.proc.pid()}) ended: ${r.endedBy ?: "by itself"}, exit code $code" +
                (if (code > 128) " (signal ${code - 128})" else "")
            when {
                r.asked != null -> Log.i(TAG, "$detail — asked: ${r.asked}")
                r.startFailed -> Log.w(TAG, detail)
                else -> {
                    Log.w(TAG, "$detail — not restarted; the next Play starts a fresh one")
                    try { r.owner.exited(detail) } catch (e: Exception) { Log.e(TAG, "exit listener", e) }
                }
            }
            lock.notifyAll()
        }
    }

    private fun supervise(r: Run) {
        val tick = (boundMs / 10).coerceIn(10, 1_000)
        while (r.proc.isAlive) {
            Thread.sleep(tick)
            if (r.asked != null || r.endedBy != null) continue
            val now = MpvIpc.nowMs()
            val ipc = r.ipc
            val why = when {
                ipc == null -> if (now - r.startedAtMs >= boundMs) "it did not open its socket in ${boundMs / 1000.0} s" else null
                !ipc.open -> if (now - ipc.closedAtMs >= minOf(GRACE_MS, boundMs)) "its connection ended and it kept running" else null
                else -> ipc.oldestUnanswered(now)?.takeIf { it.second >= boundMs }?.let { "it did not answer `${it.first}` for ${it.second / 1000.0} s" }
            } ?: continue
            r.endedBy = why
            Log.w(TAG, "ending the PC player (pid ${r.proc.pid()}): $why")
            r.proc.destroyForcibly()
        }
    }

    /** The connection's listener: events and properties go to the owner; the end is the supervisor's to judge. */
    private class Watch(private val r: Run) : MpvIpc.Listener {
        override fun event(name: String, msg: JsonObject) = r.owner.event(name, msg)
        override fun property(name: String, value: JsonElement?) = r.owner.property(name, value)
        override fun closed(detail: String) {
            if (r.asked == null && r.proc.isAlive) Log.i(TAG, "the player's connection ended: $detail")
        }
    }

    private fun writePidfile(proc: Process) {
        val start = proc.info().startInstant().map { it.toEpochMilli() }.orElse(-1L)
        if (start < 0) Log.w(TAG, "the player's start time is not readable: a leftover of this run will not be recognised")
        try {
            Files.writeString(pidfile, "pid=${proc.pid()}\nstart=$start\nsocket=$socket\n")
        } catch (e: IOException) {
            Log.e(TAG, "the player's pidfile was not written (a leftover of this run will not be recognised)", e)
        }
    }

    private fun removePidfileOf(pid: Long) {
        try {
            if (Files.exists(pidfile) && Files.readString(pidfile).lines().firstOrNull() == "pid=$pid") Files.delete(pidfile)
        } catch (e: IOException) {
            Log.w(TAG, "the player's pidfile was not removed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "music-pc"
        /** §2: a command unanswered this long ends the player. */
        const val UNANSWERED_MS = 30_000L
        /** A child whose connection ended gets this long to exit by itself (it quits 0.24 s after, in the hand run). */
        const val GRACE_MS = 2_000L

        /** `/proc/<pid>/cmdline` as its arguments, or null when unreadable. */
        fun cmdline(pid: Long): List<String>? = try {
            String(Files.readAllBytes(Path.of("/proc/$pid/cmdline")), Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        } catch (e: IOException) { null }

        private fun uid(): Int = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int
    }
}
