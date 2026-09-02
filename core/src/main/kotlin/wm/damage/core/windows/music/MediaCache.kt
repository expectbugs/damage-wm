package wm.damage.core.windows.music

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import wm.damage.core.util.Exec
import wm.damage.core.util.Log

/**
 * The transcode cache + the transcoder (`MUSIC.md` §6.4/§9.2). One profile =
 * one directory under [root] (`~/.damage/media-cache/<profile>/`); a file is
 * keyed `<id>-<mtime>-<sha1(path)[:8]>.<ext>` so an id reused after a DB
 * rebuild never serves stale audio for a different file. The legacy G2CC
 * cache ([legacy], 2,981 opus 96 k mono loudnorm files) IS the
 * `standard-mono-loudnorm` profile and is read in place — the same key
 * shape, verified against its files.
 *
 * ffmpeg runs ONE AT A TIME (a 16-core box would otherwise fork a storm on a
 * queue replace); a transcode writes `.part` then moves atomically; orphaned
 * parts from an ended process are swept at start. NO TIMEOUTS: a transcode
 * runs to completion and its duration is logged.
 */
class MediaCache(
    private val root: Path,
    private val legacy: Path?,
    private val ffmpeg: String = "ffmpeg",
) {
    private val lock = ReentrantLock()
    /** In-flight transcodes by output path: concurrent opens share one run —
     *  a LATCH per output (review 2026-09-03: joining the runner's THREAD
     *  waited for a request thread's whole stream, or the sweep's whole day). */
    private val inFlight = HashMap<Path, java.util.concurrent.CountDownLatch>()

    init {
        Files.createDirectories(root)
        sweepParts()
    }

    fun keyFor(t: MusicDb.TrackFile): String {
        val h = MessageDigest.getInstance("SHA-1").digest(t.path.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }
        return "${t.id}-${t.mtimeMs}-$h"
    }

    fun dirFor(p: AudioProfile): Path = root.resolve(p.name)

    /** Where the cached file for [t] under [p] lives (or would live). The
     *  legacy directory answers for its profile when it holds the file. */
    fun pathFor(t: MusicDb.TrackFile, p: AudioProfile): Path {
        val name = keyFor(t) + "." + p.ext(t.ext)
        val own = dirFor(p).resolve(name)
        if (p == AudioProfile.LEGACY && legacy != null && !Files.exists(own)) {
            val l = legacy.resolve(name)
            if (Files.exists(l)) return l
        }
        return own
    }

    fun isCached(t: MusicDb.TrackFile, p: AudioProfile): Boolean =
        if (p.quality == AudioProfile.Quality.LOSSLESS) Files.exists(Path.of(t.path)) else Files.exists(pathFor(t, p))

    /** MIME of what [p] serves for [t]. */
    fun mimeFor(t: MusicDb.TrackFile, p: AudioProfile): String =
        if (p.quality == AudioProfile.Quality.LOSSLESS) sourceMime(t.ext) else "audio/ogg"

    /** The file to serve — transcoded to completion first on a miss (logged
     *  with its duration). Lossless = the source file itself. */
    fun fileFor(t: MusicDb.TrackFile, p: AudioProfile): Path {
        val src = Path.of(t.path)
        if (!Files.isRegularFile(src)) throw IllegalStateException("track ${t.id} source file missing: ${t.path}")
        if (p.quality == AudioProfile.Quality.LOSSLESS) return src
        val out = pathFor(t, p)
        if (Files.exists(out)) return out
        transcode(t, p, out)
        return out
    }

    /** The ffmpeg argument list per profile (§9.2). */
    fun ffmpegArgs(t: MusicDb.TrackFile, p: AudioProfile, tmp: Path): List<String> {
        val args = arrayListOf(ffmpeg, "-v", "error", "-y", "-i", t.path, "-map", "0:a:0", "-vn",
            "-ac", "${p.channels}", "-c:a", "libopus", "-b:a", "${p.kbps}k")
        if (p.loudnorm) { args.add("-af"); args.add("loudnorm=I=-16:TP=-1.5:LRA=11") }
        args.add("-f"); args.add("ogg"); args.add(tmp.toString())
        return args
    }

    private fun transcode(t: MusicDb.TrackFile, p: AudioProfile, out: Path) {
        // one transcode at a time, and one per OUTPUT: a second caller for
        // the same file waits for the first run instead of starting another
        val mine: Boolean
        val latch: java.util.concurrent.CountDownLatch
        lock.withLock {
            val existing = inFlight[out]
            if (existing != null) { latch = existing; mine = false }
            else { latch = java.util.concurrent.CountDownLatch(1); inFlight[out] = latch; mine = true }
        }
        if (!mine) {
            latch.await()                    // the other caller's transcode, not its thread
            if (!Files.exists(out)) throw IllegalStateException("transcode of track ${t.id} failed in another caller")
            return
        }
        try {
            ffmpegLock.withLock {
                if (Files.exists(out)) return
                Files.createDirectories(out.parent)
                val tmp = out.resolveSibling(out.fileName.toString() + ".part")
                val t0 = System.currentTimeMillis()
                Log.i("music-cache", "transcoding track ${t.id} → ${p.name} (${Path.of(t.path).fileName})")
                val r = try {
                    Exec.run(ffmpegArgs(t, p, tmp))
                } catch (e: Exception) {
                    Files.deleteIfExists(tmp)
                    throw IllegalStateException("ffmpeg could not run: ${e.message}")
                }
                if (r.code != 0) {
                    Files.deleteIfExists(tmp)
                    throw IllegalStateException("ffmpeg transcode failed for track ${t.id} (${t.path}): ${r.stderr.take(300).trim()}")
                }
                Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                Log.i("music-cache", "transcode of track ${t.id} done in ${(System.currentTimeMillis() - t0) / 1000.0} s")
            }
        } finally {
            lock.withLock { inFlight.remove(out) }
            latch.countDown()
        }
    }

    private val ffmpegLock = ReentrantLock()

    private fun sweepParts() {
        try {
            var swept = 0
            Files.walk(root, 2).use { s ->
                for (p in s) if (Files.isRegularFile(p) && p.fileName.toString().endsWith(".part")) { Files.deleteIfExists(p); swept++ }
            }
            if (swept > 0) Log.w("music-cache", "swept $swept orphaned .part file(s) from $root")
        } catch (e: Exception) {
            Log.w("music-cache", "part sweep failed (continuing): ${e.message}")
        }
    }

    // ------------------------------------------------------------ the pre-transcode job
    @Volatile var jobStatus: String = ""
        private set
    @Volatile private var jobThread: Thread? = null
    @Volatile private var jobStop = false

    /** Build every missing cache entry for [p] in the background, one ffmpeg
     *  at a time, resumable (existing entries are skipped) and stoppable.
     *  Returns a line for the notice. */
    fun pretranscode(p: AudioProfile, tracks: () -> List<MusicDb.TrackFile>, onDone: (MusicDb.TrackFile, AudioProfile) -> Unit = { _, _ -> }): String {
        if (p.quality == AudioProfile.Quality.LOSSLESS) return "lossless plays the source files — nothing to build"
        val running = jobThread
        if (running != null && running.isAlive) return "a pre-transcode is already running: $jobStatus"
        jobStop = false
        val all = tracks()
        val todo = all.filter { !isCached(it, p) }
        if (todo.isEmpty()) { jobStatus = ""; return "${p.name}: all ${all.size} tracks are cached" }
        jobStatus = "${p.name}: 0 of ${todo.size}"
        val th = Thread({
            var ok = 0; var failed = 0
            for ((i, t) in todo.withIndex()) {
                if (jobStop) break
                try {
                    fileFor(t, p)
                    onDone(t, p)
                    ok++
                } catch (e: Exception) {
                    failed++
                    Log.w("music-cache", "pre-transcode: ${e.message}")
                }
                jobStatus = "${p.name}: ${i + 1} of ${todo.size}${if (failed > 0) " ($failed failed)" else ""}"
            }
            Log.i("music-cache", "pre-transcode ${p.name} ${if (jobStop) "stopped" else "done"}: $ok built, $failed failed")
            jobStatus = ""
        }, "music-pretranscode").apply { isDaemon = true }
        jobThread = th
        th.start()
        return "building ${todo.size} of ${all.size} for ${p.name} in the background"
    }

    fun stopJob() { jobStop = true }

    companion object {
        fun sourceMime(ext: String): String = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "aiff" -> "audio/aiff"
            else -> "application/octet-stream"
        }
    }
}
