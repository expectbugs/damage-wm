package wm.damage.core.windows.music

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import wm.damage.core.util.Log

/**
 * The resolver's lane-2 language model, run as a self-terminating CLI
 * one-shot (`MUSIC.md` §9.3, verdict 5: "the latest Claude Opus at low or
 * medium effort via the CLI one-shot"). One request in, one answer out, no
 * session on disk, no tools, no timeout — the caller's fallback chain owns
 * recovery, so a model that does not answer must never mean no music.
 *
 * The invocation follows G2CC's exercised one-shot (`server/src/resolver.ts`
 * `llmParse` + `cc-session.ts` `claudeChildEnv` — read for the env-scrub fact
 * and the flag set, written here in our own code). Flags verified against
 * `claude --help` on 2026-09-02: `-p/--print`, `--bare`, `--tools`,
 * `--no-session-persistence`, `--model`, `--effort`, `--system-prompt` all
 * exist with these shapes.
 *
 * ⚠ MEASURED 2026-09-02 on beardos: `--bare` documents its auth as *strictly*
 * `ANTHROPIC_API_KEY` or an `apiKeyHelper` — "OAuth and keychain are never
 * read". Adam's CLI is signed in over OAuth and the env scrub removes
 * `ANTHROPIC_API_KEY`, so `-p --bare` answered `Not logged in · Please run
 * /login` **and still exited 0**, while the same call without `--bare`
 * answered normally. So [bare] defaults to false, matching the invocation
 * G2CC actually runs. Turn it on only where an API key is present. The exit-0
 * part is why [run] also refuses a blank answer and why the resolver treats
 * any non-JSON reply as a lane failure rather than trusting the exit code.
 */
class ClaudeOneShot(
    private val claudeBin: String = "claude",
    private val model: String,
    private val effort: String,
    /** A neutral working directory — the user home, never a project tree. */
    private val cwd: Path = Paths.get(System.getProperty("user.home") ?: "/"),
    /** See the class note: false because OAuth logins cannot use `--bare`. */
    private val bare: Boolean = false,
) {

    /** Sends [payload] on stdin under [system] and returns stdout verbatim.
     *  Blocks for as long as the model takes (no timeout); a non-zero exit or
     *  a blank answer throws with the reason. */
    fun run(system: String, payload: String): String {
        val args = ArrayList<String>()
        args.add(claudeBin)
        args.add("-p")
        if (bare) args.add("--bare")
        args.add("--tools"); args.add("")                 // "" = every built-in tool off
        args.add("--no-session-persistence")              // nothing written to ~/.claude
        args.add("--model"); args.add(model)
        args.add("--effort"); args.add(effort)
        args.add("--system-prompt"); args.add(system)

        val r = MusicProc.run(args, cwd.toFile(), payload, SCRUBBED_ENV, EXTRA_ENV, "claude")
        if (r.stderr.isNotBlank()) Log.w(TAG, "claude one-shot stderr:\n${r.stderr}")
        if (r.code != 0) throw IllegalStateException("claude one-shot exit ${r.code}: ${MusicProc.head(r.stderr)}")
        if (r.stdout.isBlank()) {
            throw IllegalStateException(
                "claude one-shot returned nothing (exit 0)" +
                    if (r.stderr.isNotBlank()) ": ${MusicProc.head(r.stderr)}" else "")
        }
        return r.stdout
    }

    companion object {
        private const val TAG = "music-llm"

        /** Everything that would make the child think it is a nested Claude
         *  Code session, plus every credential the parent holds (G2CC's
         *  `claudeChildEnv` fact — the child authenticates as itself). */
        val SCRUBBED_ENV: Set<String> = setOf(
            "CLAUDECODE", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_SESSION_ID",
            "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_EXECPATH", "AI_AGENT", "CLAUDE_EFFORT",
            "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_API_KEY",
        )

        val EXTRA_ENV: Map<String, String> = mapOf("CLAUDE_CODE_DISABLE_AUTO_MEMORY" to "1")
    }
}

/**
 * The one subprocess shape the music host needs that [wm.damage.core.util.Exec]
 * does not cover: a child that is FED on stdin. Exec's note applies here too —
 * reading stdout to EOF before draining stderr stops responding once the child
 * fills the 64 KiB stderr pipe — so stderr drains on its own thread, and stdin
 * is written on a third so a payload larger than the pipe buffer cannot wedge
 * against a child that answers before it finishes reading.
 *
 * NO TIMEOUTS: nothing here bounds the child's run. The two `join` calls are a
 * completeness grace for buffered bytes AFTER the child has already exited (the
 * `Exec.kt` precedent), not an execution bound.
 */
internal object MusicProc {

    class Result(val code: Int, val stdout: String, val stderr: String)

    /** The first line of [s], for an exception message; the full text is
     *  logged separately so nothing is lost. */
    fun head(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return "(no output)"
        val first = t.lineSequence().first()
        return if (first.length <= 300) first else first.substring(0, 300) + "…"
    }

    fun run(
        cmd: List<String>,
        cwd: File,
        stdin: String,
        scrub: Set<String> = emptySet(),
        extraEnv: Map<String, String> = emptyMap(),
        tag: String = "proc",
        stderrCap: Int = 64 * 1024,
    ): Result {
        val pb = ProcessBuilder(cmd)
        pb.directory(cwd)
        val env = pb.environment()
        for (k in scrub) env.remove(k)
        env.putAll(extraEnv)
        val p = pb.start()

        val stdinFault = AtomicReference<Exception?>(null)
        val writer = Thread({
            try {
                p.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)); it.flush() }
            } catch (e: Exception) {
                // A child that answers without reading its input closes the
                // pipe under us. Loud, never fatal: the exit code and the
                // blank-answer check below are what judge the run.
                stdinFault.set(e)
            }
        }, "music-proc-stdin-$tag").apply { isDaemon = true }
        writer.start()

        val err = StringBuilder()
        val errLock = Any()
        val drainer = Thread({
            try {
                val buf = ByteArray(8 * 1024)
                p.errorStream.use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        synchronized(errLock) {
                            if (err.length < stderrCap) {
                                err.append(String(buf, 0, minOf(n, stderrCap - err.length), Charsets.UTF_8))
                            }
                        }
                        // past the cap: keep DRAINING — that is the whole point
                    }
                }
            } catch (e: Exception) {
                // the process ending closes the pipe under us — normal, but
                // it is still said out loud rather than swallowed
                Log.d("music-proc", "stderr drain for $tag ended: ${e::class.simpleName}: ${e.message}")
            }
        }, "music-proc-stderr-$tag").apply { isDaemon = true }
        drainer.start()

        val out = try {
            p.inputStream.readBytes()
        } catch (e: Exception) {
            p.destroy()          // our own failure must not leave the child running
            throw e
        }
        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            p.destroy()
            throw e
        }
        drainer.join(2_000)
        writer.join(2_000)
        stdinFault.get()?.let { Log.w("music-proc", "stdin write to $tag stopped: ${it.message}") }
        return Result(code, out.toString(Charsets.UTF_8), synchronized(errLock) { err.toString() })
    }
}
