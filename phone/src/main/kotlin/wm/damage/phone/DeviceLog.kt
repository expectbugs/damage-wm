package wm.damage.phone

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import wm.damage.core.util.Log

/**
 * Forwards this phone's log lines to the PC's content port so they land in
 * `~/.damage/device.log`, readable directly with no adb (HANDOFF.md §19.4
 * follow-up — the ring-probe frames need to reach the PC). Installed as a
 * [Log.Sink], so EVERY `Log.x` line on the phone is captured, ring-probe
 * frames included.
 *
 * One best-effort daemon connection over the existing token-gated content
 * seam ({"t":"devlog"} after the hello, then {"t":"log","line":…} frames),
 * keeper-style reconnect. A bounded queue drops the OLDEST line when the PC
 * is unreachable — this is diagnostics, never at the cost of the shell.
 * Its own operational trouble goes to android.util.Log directly, never back
 * through [Log], so it cannot feed itself.
 */
class DeviceLog(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val deviceName: String,
) : Log.Sink, AutoCloseable {

    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var running = true
    @Volatile private var sock: Socket? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"") }

    private val worker = Thread({ loop() }, "damage-devlog").apply { isDaemon = true; start() }

    override fun log(level: Log.Level, tag: String, message: String) {
        val line = "${fmt.format(System.currentTimeMillis())} ${level.name.first()}/$tag: $message"
        if (!queue.offer(line)) {           // bounded via the drain side; drop oldest to stay live
            queue.poll()
            queue.offer(line)
        }
        // hard cap so an unreachable PC cannot grow the queue without bound
        while (queue.size > MAX_QUEUE) queue.poll()
    }

    private fun loop() {
        while (running) {
            try {
                Socket(host, port).use { s ->
                    sock = s
                    s.tcpNoDelay = true
                    val out = DataOutputStream(s.getOutputStream().buffered())
                    val inp = DataInputStream(s.getInputStream().buffered())
                    sendJson(out, """{"t":"hello","token":"${esc(token)}","proto":1}""")
                    sendJson(out, """{"t":"devlog","dev":"${esc(deviceName)}"}""")
                    android.util.Log.i("damage/devlog", "device log streaming to $host:$port")
                    // best-effort: a line taken and then failed to send is lost,
                    // acceptable for diagnostics; the connection is stable in
                    // practice (the sync channel proves the path)
                    while (running) {
                        val line = queue.take()
                        sendJson(out, """{"t":"log","line":"${esc(line)}"}""")
                        // opportunistically drain a burst without re-flushing each
                        var more = queue.poll()
                        var n = 0
                        while (more != null && n < 64) {
                            sendJson(out, """{"t":"log","line":"${esc(more)}"}""", flush = false)
                            more = queue.poll(); n++
                        }
                        out.flush()
                        // the server never speaks; a read here only detects close
                        if (inp.available() < 0) break
                    }
                }
            } catch (e: Exception) {
                if (running) android.util.Log.i("damage/devlog", "device log link down (${e.message}) — retrying")
            }
            sock = null
            if (!running) return
            try { Thread.sleep(RETRY_MS) } catch (e: InterruptedException) { return }
        }
    }

    private fun sendJson(out: DataOutputStream, s: String, flush: Boolean = true) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
        if (flush) out.flush()
    }

    override fun close() {
        running = false
        worker.interrupt()
        try { sock?.close() } catch (e: Exception) { /* closing */ }
    }

    companion object {
        private const val RETRY_MS = 15_000L
        private const val MAX_QUEUE = 4_000
    }
}
