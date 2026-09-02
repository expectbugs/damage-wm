package wm.damage.core.windows.music

import java.io.IOException
import java.nio.file.Path
import wm.damage.core.util.Log

/**
 * The bridge to the Python side of the music system (`MUSIC.md` §9.5): the
 * `audio/` package taken over from G2CC, plus `audio/viz.py`.
 *
 * Two jobs, both one subprocess each:
 *  - [enrich] walks a NEW track through the ingest passes, in order, one
 *    `-m enrich.run_enrichment <pass> --track-id <id>` call per pass;
 *  - [viz] computes that track's DVIZ visualizer blob with `-m viz <file>`.
 *
 * Both run with the working directory set to [audioDir] — that is what makes
 * `-m enrich.…` and `-m viz` resolve, since `enrich/` and `viz.py` sit there.
 * [python] is the config key `musicPython` (G2CC's venv until Damage owns
 * one, `MUSIC.md` §9.7).
 *
 * Failure policy, from the house rules and `MUSIC.md` §12:
 *  - NO TIMEOUTS on this side. A pass takes as long as it takes (the profile pass calls a
 *    language model; the audio pass decodes the file). Nothing here is
 *    time-bounded, and the caller supervises. (The Python profile pass keeps
 *    G2CC's own 15-minute cap on its one-shot subprocess — Adam's code, the
 *    resource-cap class its header sanctions — which is not a bound of ours.)
 *  - NO SILENT FAILURES. A pass that exits non-zero is logged with its exit
 *    code and the head of its stderr, and per-track failures the runner
 *    itself reports on stdout (its `FAILED #<id>` lines) are logged too.
 *  - The chain CONTINUES past a failing pass: a track that plays must not
 *    become unplayable because MusicBrainz was unreachable. Only the case
 *    where the whole chain failed — the interpreter or the package is not
 *    there at all — is raised to the caller, which logs it and keeps the
 *    track (`LocalMusicLibrary.ytGrab`).
 */
class Enrich(
    private val python: String,
    private val audioDir: Path,
) : Ingester {

    override fun enrich(trackId: Int, phase: (String) -> Unit) {
        require(trackId > 0) { "enrich needs a real track id (got $trackId)" }
        val failed = ArrayList<String>()
        for (pass in PASSES) {
            phase(pass)
            val t0 = System.currentTimeMillis()
            val r = try {
                run(listOf(python, "-m", "enrich.run_enrichment", pass, "--track-id", trackId.toString()))
            } catch (e: IOException) {
                // The interpreter or the package directory is not there. The
                // remaining passes would report the identical thing, so stop
                // and say it once, with everything needed to fix it.
                throw IllegalStateException(
                    "the enrichment runner could not start: '$python -m enrich.run_enrichment' " +
                        "with the working directory $audioDir (${e.message}). Check the " +
                        "musicPython config key and that audio/enrich exists there.", e)
            }
            val secs = (System.currentTimeMillis() - t0) / 1000.0
            reportPerTrackFailures(trackId, pass, r.stdout.toString(Charsets.UTF_8))
            if (r.code != 0) {
                failed += pass
                Log.e("music-enrich", "pass '$pass' for track $trackId exited ${r.code} after $secs s " +
                    "— the remaining passes still run: ${head(r.stderr)}")
            } else {
                Log.i("music-enrich", "pass '$pass' for track $trackId done in $secs s")
            }
        }
        if (failed.size == PASSES.size) {
            throw IllegalStateException(
                "every enrichment pass failed for track $trackId (${failed.joinToString(" ")}) — " +
                    "the Python side is not working; see the per-pass errors above. The track " +
                    "itself is untouched and still plays.")
        }
        if (failed.isNotEmpty()) {
            Log.w("music-enrich", "track $trackId enriched with ${failed.size} of ${PASSES.size} " +
                "passes failing (${failed.joinToString(" ")}) — re-run them later with " +
                "'$python -m enrich.run_enrichment <pass> --track-id $trackId' in $audioDir")
        }
    }

    override fun viz(t: MusicDb.TrackFile): ByteArray? {
        val r = try {
            run(listOf(python, "-m", "viz", t.path))
        } catch (e: IOException) {
            Log.e("music-enrich", "the visualizer precompute could not start: '$python -m viz' " +
                "with the working directory $audioDir (${e.message}) — track ${t.id} gets no " +
                "visualizer data. Check the musicPython config key and that audio/viz.py exists there.")
            return null
        }
        if (r.code != 0) {
            // viz.py's own exits: 2 = bad arguments / missing file, 1 = the
            // analysis failed. An import error (no librosa, no numpy) surfaces
            // here as a traceback in stderr.
            Log.e("music-enrich", "viz.py exited ${r.code} for track ${t.id} (${t.path}) — no " +
                "visualizer data for it: ${head(r.stderr)}")
            return null
        }
        if (r.stdout.isEmpty()) {
            Log.e("music-enrich", "viz.py exited 0 but wrote no blob for track ${t.id} " +
                "(${t.path}): ${head(r.stderr)}")
            return null
        }
        Log.i("music-enrich", "viz for track ${t.id}: ${r.stdout.size} B (${head(r.stderr, 200)})")
        return r.stdout
    }

    // ------------------------------------------------------------------ plumbing

    /** The runner records per-track trouble in `pass_status` and still exits
     *  0 (a batch keeps going), so a non-zero exit code alone would hide it.
     *  Its `FAILED` lines go to stdout — surface every one. */
    private fun reportPerTrackFailures(trackId: Int, pass: String, stdout: String) {
        for (line in stdout.lineSequence()) {
            if (line.contains("FAILED")) {
                Log.w("music-enrich", "pass '$pass' for track $trackId reported: ${line.trim()}")
            }
        }
    }

    private class Res(val code: Int, val stdout: ByteArray, val stderr: String)

    /**
     * One subprocess, run to completion in [audioDir], with stderr drained on
     * its own thread while the caller consumes stdout — the deadlock
     * `util/Exec.kt` documents (reading stdout to EOF first stalls forever
     * once the child fills the 64 KiB stderr pipe, and the enrichment passes
     * are chatty). `Exec.run` has no working-directory knob, so the same
     * discipline is repeated here rather than changing a shared utility.
     *
     * NO TIMEOUTS: `waitFor()` has no bound. The only `join` is the drainer's
     * completeness grace AFTER the child has exited and its bytes are already
     * in the pipe.
     */
    private fun run(cmd: List<String>): Res {
        val p = ProcessBuilder(cmd).directory(audioDir.toFile()).start()
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
                            if (err.length < STDERR_CAP) {
                                err.append(String(buf, 0, minOf(n, STDERR_CAP - err.length), Charsets.UTF_8))
                            }
                        }
                        // past the cap: keep DRAINING, or the child stalls
                    }
                }
            } catch (e: Exception) {
                // the child exiting closes the pipe under us — normal
            }
        }, "music-enrich-stderr").apply { isDaemon = true }
        drainer.start()
        val out = try {
            p.inputStream.readBytes()
        } catch (e: Exception) {
            p.destroy()      // our own failure must not leave the child running
            throw e
        }
        val code = try {
            p.waitFor()
        } catch (e: InterruptedException) {
            p.destroy()
            throw e
        }
        drainer.join(2_000)  // completeness grace, not an execution bound
        return Res(code, out, synchronized(errLock) { err.toString() })
    }

    companion object {
        /**
         * The ingest chain for ONE new track (`MUSIC.md` §9.5), in order.
         * `consistency` and `videosweep` are library-wide sweeps and have no
         * place here; `speech` and `acoustid` are opt-in and slow;
         * `pretranscode` is the cache job, which `MediaCache` already does
         * for the profile actually being played.
         */
        val PASSES: List<String> = listOf(
            "tags", "musicbrainz", "lyrics", "audio", "profile", "embed", "dedupe",
        )

        private const val STDERR_CAP = 64 * 1024
        private const val HEAD_CHARS = 400

        /** The head of a subprocess's stderr for a one-line log. Nothing is
         *  dropped quietly: when there is more, the line says how much. */
        internal fun head(s: String, n: Int = HEAD_CHARS): String {
            val t = s.trim()
            return if (t.length <= n) t else t.take(n).trimEnd() + " … (+${t.length - n} more chars)"
        }
    }
}
