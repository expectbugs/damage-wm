package wm.damage.desktop.music

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
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
 * - **No pidfile** (the S1-4 review, removal R1): the tie was measured (quit 0.24 s after its stdin closed) and the
 *   kernel closes the pipe at every end of the JVM, so no code here signals a pid read from a file. Were a child ever
 *   to outlive the service, nothing here would end it: he would hear it and see it in his process list.
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

    /** The command line, as built (§3.6 checked against `man mpv` 0.41 and the hand run). A named device is
     *  `--audio-device=pipewire/<node name>` — "the audio output name … followed by /, followed by the audio output
     *  specific device name", as `mpv --audio-device=help` lists it — and then without `--ao` ("don't use --ao and
     *  --audio-device together", `man mpv` 0.41); none named: `--ao=pipewire`, PipeWire's default device. */
    fun argv(): List<String> = program + listOf(
        "--no-config", "--idle=yes", "--terminal=no", "--vid=no", "--audio-display=no", "--volume=100",
        "--audio-channels=stereo", "--input-ipc-server=$socket", "--input-ipc-client=fd://0",
    ) + (device?.let { listOf("--audio-device=$it") } ?: listOf("--ao=pipewire"))

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
        // a device in another form plays nowhere he chose: refused in words at the start, so the open says it
        if (device != null && !device.startsWith(DEVICE_PREFIX)) throw IOException("the PC output device \"$device\" is not " +
            "in the form ${DEVICE_PREFIX}<node name> (as `mpv --audio-device=help` lists it): set musicPcDevice to that form")
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

    private fun waiter(r: Run) {
        val code = r.proc.waitFor()
        r.ipc?.close()
        synchronized(lock) {
            if (run === r) run = null
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

    companion object {
        private const val TAG = "music-pc"
        /** §2: a command unanswered this long ends the player. */
        const val UNANSWERED_MS = 30_000L
        /** A child whose connection ended gets this long to exit by itself (it quits 0.24 s after, in the hand run). */
        const val GRACE_MS = 2_000L
        /** The one form of a named device: PipeWire's output, then its node name (`man mpv` 0.41, `--audio-device`). */
        const val DEVICE_PREFIX = "pipewire/"

        /** `/proc/<pid>/cmdline` as its arguments, or null when unreadable. */
        fun cmdline(pid: Long): List<String>? = try {
            String(Files.readAllBytes(Path.of("/proc/$pid/cmdline")), Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        } catch (e: IOException) { null }

        private fun uid(): Int = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int
    }
}
