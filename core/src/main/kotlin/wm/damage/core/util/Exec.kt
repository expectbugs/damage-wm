package wm.damage.core.util

/**
 * Subprocess runner that cannot deadlock on a chatty child (review 2026-09-01
 * finding: reading stdout to EOF before draining stderr blocks forever once
 * the child fills the 64 KiB stderr pipe — pdftoppm on a damaged PDF does
 * exactly that while streaming a large PNG). stderr drains on its own daemon
 * thread into a bounded buffer while the caller consumes stdout.
 */
object Exec {

    class Result(val code: Int, val stdout: ByteArray, val stderr: String)

    /** Run [cmd] to completion. Throws IOException when the tool is absent —
     *  callers decide whether that is loud or a fallback. */
    fun run(cmd: List<String>, stderrCap: Int = 64 * 1024): Result {
        val p = ProcessBuilder(cmd).start()
        val err = StringBuilder()
        val drainer = Thread({
            try {
                val buf = ByteArray(8 * 1024)
                p.errorStream.use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        if (err.length < stderrCap) {
                            err.append(String(buf, 0, minOf(n, stderrCap - err.length), Charsets.UTF_8))
                        }
                        // past the cap: keep DRAINING (that is the whole point)
                    }
                }
            } catch (e: Exception) {
                // the process ending closes the pipe under us — normal
            }
        }, "exec-stderr-${cmd.firstOrNull()}").apply { isDaemon = true }
        drainer.start()
        val out = p.inputStream.readBytes()
        val code = p.waitFor()
        drainer.join(2_000)   // the pipe closed with the process; a hung drainer is daemon anyway
        return Result(code, out, err.toString())
    }
}
