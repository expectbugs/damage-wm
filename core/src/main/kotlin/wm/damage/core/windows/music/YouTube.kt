package wm.damage.core.windows.music

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import wm.damage.core.util.Exec
import wm.damage.core.util.Log

/**
 * yt-dlp: search, and the audio-only grab into the YouTube dir
 * (`MUSIC.md` §3.9 / §9.6, verdict 7).
 *
 * Two calls, both explicit-request only. [search] lists what YouTube offers so
 * Adam picks a row — this class never picks for him and is never a fallback
 * for a library miss. [grab] downloads exactly the one video he picked as
 * audio, into [dir], and reports 0–100 % as the bytes arrive; the caller runs
 * the §9.5 ingest afterwards.
 *
 * NO TIMEOUTS: neither call is time-bounded. A slow network makes a slow grab,
 * and the window renders the percentage while it happens; supervision is the
 * caller's business (the shell can drop the job), not a wrapper here.
 *
 * NO SILENT FAILURES: a non-zero exit, an absent binary, a search line that
 * will not parse and a grab that wrote no file each reach the log with the
 * reason, and everything except the skipped line also throws.
 *
 * Interaction shape and the flag set were read from Adam's own G2CC
 * implementation (`/home/user/G2CC/server/src/youtube.ts`, his licence — facts
 * only, no code carried over); every flag re-verified against
 * `~/.local/bin/yt-dlp --help` (2026.06.09) before it was written here.
 */
class YouTube(
    private val ytDlp: String,
    dir: Path,
) : YtClient {

    /** Absolute so the `-o` template — and therefore `after_move:filepath` —
     *  is absolute whatever directory the host process happens to run in. */
    private val dir: Path = dir.toAbsolutePath().normalize()

    // ------------------------------------------------------------------ search

    /**
     * `ytsearch<n>:<q>`, metadata only — nothing is downloaded.
     *
     * `--no-download --flat-playlist --dump-json` prints one JSON object per
     * line; a line that will not parse, or that carries no id/title, is logged
     * and skipped (one defective row must not lose the other nine). A non-zero
     * exit throws with the meaningful part of stderr.
     */
    override fun search(q: String, n: Int): List<YtResult> {
        val query = q.trim()
        require(query.isNotEmpty()) { "yt search called with an empty query" }
        require(n >= 1) { "yt search called with n=$n (must be at least 1)" }
        val argv = listOf(
            ytDlp, "--no-download", "--flat-playlist", "--dump-json", "ytsearch$n:$query",
        )
        Log.i(TAG, "search \"$query\" (top $n)")
        val r = try {
            // Exec drains stderr on its own thread — a chatty yt-dlp cannot
            // stall the stdout read (see Exec.kt's header).
            Exec.run(argv)
        } catch (e: IOException) {
            Log.e(TAG, "yt-dlp is not runnable at $ytDlp", e)
            throw IllegalStateException("yt-dlp is not runnable at $ytDlp: ${e.message}", e)
        }
        if (r.code != 0) {
            Log.e(TAG, "search \"$query\" exited ${r.code}; stderr follows:\n${r.stderr}")
            throw IllegalStateException("yt-dlp search \"$query\" exited ${r.code}: ${why(r.stderr)}")
        }
        val out = r.stdout.toString(Charsets.UTF_8)
        val hits = ArrayList<YtResult>()
        for (raw in out.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val hit = parseHit(line)
            if (hit != null) hits.add(hit)
        }
        Log.i(TAG, "search \"$query\": ${hits.size} result(s)")
        return hits
    }

    /** One `--dump-json` line → a result row, or null (logged) when it is not
     *  one. Every field is cast at the boundary: yt-dlp's JSON has been seen
     *  to carry `duration` as a number, as a string and as null. */
    private fun parseHit(line: String): YtResult? {
        val el = try {
            JSON.parseToJsonElement(line)
        } catch (e: Exception) {
            Log.w(TAG, "search line skipped, not JSON (${e::class.simpleName}): ${brief(line)}")
            return null
        }
        val o = el as? JsonObject
        if (o == null) {
            Log.w(TAG, "search line skipped, not a JSON object: ${brief(line)}")
            return null
        }
        val id = str(o, "id")
        val title = str(o, "title")
        if (id.isNullOrBlank() || title.isNullOrBlank()) {
            Log.w(TAG, "search line skipped, no id/title: ${brief(line)}")
            return null
        }
        return YtResult(
            id = id,
            title = title,
            channel = str(o, "channel") ?: str(o, "uploader") ?: "(unknown channel)",
            durS = seconds(o, "duration"),
            url = str(o, "url") ?: str(o, "webpage_url") ?: watchUrl(id),
        )
    }

    // ------------------------------------------------------------------- grab

    /**
     * Downloads ONE picked video's audio into [dir] and returns the file
     * yt-dlp wrote. [progress] is called with a monotonic 0–100 as the
     * `[download] …%` lines arrive.
     *
     * `--audio-format opus` (not `best`) is deliberate: the extractor then
     * always writes Ogg Opus, an extension the library indexer accepts, so the
     * grab is indexable without a second guess at the container.
     *
     * `ProcessBuilder` rather than [Exec] because the progress has to be read
     * while the child runs; both pipes are drained concurrently for the reason
     * Exec.kt's header records — reading one to EOF first stalls forever once
     * the other's 64 KiB pipe fills.
     */
    override fun grab(id: String, progress: (Int) -> Unit): Path {
        require(ID.matches(id)) {
            "yt grab called with a defective video id \"${brief(id)}\" — " +
                "ids are letters, digits, '_' and '-'"
        }
        Files.createDirectories(dir)
        val url = watchUrl(id)
        // `%(title)s [%(id)s].%(ext)s` is G2CC's naming, kept so the two
        // libraries' YouTube dirs stay one shape.
        val template = dir.resolve("%(title)s [%(id)s].%(ext)s").toString()
        val argv = listOf(
            ytDlp,
            "-f", "bestaudio",              // audio-only: no video stream is fetched
            "-x", "--audio-format", "opus", // …and the extractor writes Ogg Opus
            "--embed-metadata",             // real tags, so the indexer reads more than the filename
            "--no-playlist",                // one video, whatever the url also belongs to
            "--max-filesize", MAX_FILESIZE, // a song is never this big; a mis-pick stops early
            "--no-simulate",                // --print alone would only simulate
            "--newline", "--progress",      // --print implies --quiet; --progress puts it back, one line each
            "--print", "after_move:filepath", // the final path, after the extractor moved it
            "-o", template,
            "--", url,                      // after `--` the url can never read as a flag
        )
        Log.i(TAG, "grab $id → $dir")

        val p = try {
            ProcessBuilder(argv).start()
        } catch (e: IOException) {
            Log.e(TAG, "yt-dlp is not runnable at $ytDlp", e)
            throw IllegalStateException("yt-dlp is not runnable at $ytDlp: ${e.message}", e)
        }
        // Nothing is ever written to the child's stdin; leaving the pipe open
        // would only give it something to block on.
        try {
            p.outputStream.close()
        } catch (e: IOException) {
            Log.w(TAG, "closing yt-dlp's stdin failed (harmless): ${e.message}")
        }

        val gate = Any()
        var lastPct = -1
        fun report(pct: Int) {
            // The callback runs under the lock so two drainers can never
            // deliver percentages out of order or at the same time. It is a
            // cheap push; a caller that blocks in it also stops the drain,
            // lock or no lock.
            synchronized(gate) {
                val c = pct.coerceIn(0, 100)
                if (c > lastPct) {
                    lastPct = c
                    progress(c)
                }
            }
        }

        val candidates = ArrayList<String>()   // stdout lines that are not progress
        val errLines = ArrayDeque<String>()    // the retained tail of stderr
        var errDropped = 0
        val errLock = Any()

        val drainer = Thread({
            try {
                pump(p.errorStream) { raw ->
                    val line = clean(raw)
                    if (line.isEmpty()) return@pump
                    val pct = percentOf(line)
                    // Progress belongs on stdout on this yt-dlp (the download
                    // printer writes to `_out_files.out`), but a config or a
                    // future build could move it; read it from either pipe.
                    if (pct != null) report(pct)
                    synchronized(errLock) {
                        errLines.addLast(line)
                        while (errLines.size > ERR_LINES) { errLines.removeFirst(); errDropped++ }
                    }
                }
            } catch (e: IOException) {
                // The child exiting closes the pipe under the read — ordinary.
                Log.d(TAG, "stderr drain ended: ${e.message}")
            } catch (e: Throwable) {
                // Anything else came out of the caller's progress callback or
                // out of us; it must not disappear into a thread nobody reads.
                Log.e(TAG, "stderr drain for grab $id stopped early", e)
            }
        }, "yt-dlp-stderr").apply { isDaemon = true }
        drainer.start()

        try {
            pump(p.inputStream) { raw ->
                val line = clean(raw)
                if (line.isEmpty()) return@pump
                val pct = percentOf(line)
                if (pct != null) report(pct) else candidates.add(line)
            }
        } catch (e: Exception) {
            p.destroy()   // our own failure must not leave the download running
            Log.e(TAG, "reading yt-dlp's output stopped for grab $id", e)
            throw e
        }

        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            p.destroy()
            Log.e(TAG, "grab $id was interrupted", e)
            throw e
        }
        // A completeness grace for the drainer's last buffered read, not an
        // execution bound (the same reasoning as Exec.kt): the child has
        // exited and its bytes are already in the pipe.
        drainer.join(2_000)

        val stderr = synchronized(errLock) {
            (if (errDropped > 0) "…$errDropped earlier line(s) dropped…\n" else "") +
                errLines.joinToString("\n")
        }
        if (code != 0) {
            Log.e(TAG, "grab $id exited $code; retained stderr follows:\n$stderr")
            throw IllegalStateException("yt-dlp grab $id exited $code: ${why(stderr)}")
        }

        // The path is the last thing printed, but pick the last line that
        // actually names a file we can see: a stray notice on stdout must not
        // become the answer, and a run that wrote nothing (a --max-filesize
        // stop, an extractor that produced no audio) has to be loud.
        val path = candidates.asReversed().firstOrNull { c ->
            val q = runCatching { Path.of(c) }.getOrNull()
            q != null && Files.isRegularFile(q)
        }
        if (path == null) {
            Log.e(TAG, "grab $id wrote no readable file; printed ${candidates.size} line(s); stderr:\n$stderr")
            throw IllegalStateException(
                "yt-dlp grab $id reported no output file " +
                    "(printed: ${candidates.takeLast(3).joinToString(" | ") { brief(it) }.ifEmpty { "nothing" }})" +
                    if (stderr.isNotBlank()) "; ${why(stderr)}" else "",
            )
        }
        val out = Path.of(path)
        val bytes = try {
            Files.size(out)
        } catch (e: IOException) {
            // A log line must never turn a finished grab into a failure.
            Log.w(TAG, "could not read the size of $out: ${e.message}")
            -1L
        }
        Log.i(TAG, "grabbed $id → $out (${if (bytes >= 0) "$bytes B" else "size unavailable"})")
        return out
    }

    // ----------------------------------------------------------------- pieces

    /**
     * Reads [stream] to EOF and hands [onLine] each completed line. Splits on
     * both '\n' and '\r' so a progress bar is read the same whether or not
     * `--newline` took effect. Blank lines are dropped.
     */
    private fun pump(stream: InputStream, onLine: (String) -> Unit) {
        stream.use { s ->
            val rd = InputStreamReader(s, Charsets.UTF_8)
            val buf = CharArray(4096)
            val sb = StringBuilder()
            while (true) {
                val n = rd.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = buf[i]
                    if (c == '\n' || c == '\r') {
                        if (sb.isNotEmpty()) { onLine(sb.toString()); sb.setLength(0) }
                    } else {
                        sb.append(c)
                        // A child that never writes a separator must not grow
                        // this without bound; hand over what we have instead.
                        if (sb.length >= LINE_CAP) { onLine(sb.toString()); sb.setLength(0) }
                    }
                }
            }
            if (sb.isNotEmpty()) onLine(sb.toString())
        }
    }

    private companion object {
        const val TAG = "music-yt"

        /** A song is never this big — a mis-picked hour-long upload stops
         *  early instead of filling the disk (G2CC used the same ceiling). */
        const val MAX_FILESIZE = "300m"

        /** Retained stderr tail: enough to explain a failure, bounded so a
         *  repeating warning cannot grow without limit. Draining never stops. */
        const val ERR_LINES = 200
        const val LINE_CAP = 64 * 1024

        val JSON = Json { ignoreUnknownKeys = true; isLenient = false }

        /** yt-dlp ids as we ever see them: our own search produced them, and
         *  this keeps anything flag-shaped out of the url we build. */
        val ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** `[download]  42.3% of …` / `[download] 100% of … in 00:00:03`. */
        val PCT = Regex("""\[download]\s+(\d{1,3}(?:\.\d+)?)%""")

        /** CSI escapes: yt-dlp drops colour when stdout is a pipe, but a
         *  `--color always` in a config would put them back. */
        val ANSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")

        fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

        fun clean(s: String) = ANSI.replace(s, "").trim()

        fun percentOf(line: String): Int? {
            val m = PCT.find(line) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            return v.toInt().coerceIn(0, 100)
        }

        fun str(o: JsonObject, key: String): String? {
            val p = o[key] as? JsonPrimitive ?: return null
            return p.contentOrNull?.takeIf { it.isNotBlank() }
        }

        /** `duration` arrives as 213, as 213.0, as "213.4" or as null. */
        fun seconds(o: JsonObject, key: String): Int {
            val c = str(o, key) ?: return 0
            val d = c.toDoubleOrNull() ?: return 0
            if (!d.isFinite() || d <= 0.0) return 0
            return d.toInt()
        }

        /** The part of stderr worth putting in front of a person: yt-dlp's own
         *  ERROR lines when it wrote any, otherwise the last few lines. The
         *  whole retained buffer is logged separately — nothing is lost. */
        fun why(stderr: String): String {
            val lines = stderr.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return "no stderr"
            val errs = lines.filter { it.startsWith("ERROR") || it.contains("ERROR:") }
            val pick = if (errs.isNotEmpty()) errs.takeLast(5) else lines.takeLast(5)
            val joined = pick.joinToString(" | ")
            return if (joined.length <= 600) joined else joined.take(600) + "… (full stderr in the log)"
        }

        /** For a log line about a defective input — the full value is never
         *  the point there, and the caller's content is not touched. */
        fun brief(s: String) = if (s.length <= 120) s else s.take(120) + "…"
    }
}
