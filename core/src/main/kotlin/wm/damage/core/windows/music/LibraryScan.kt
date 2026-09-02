package wm.damage.core.windows.music

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.util.Exec
import wm.damage.core.util.Log

/**
 * The library walk (`MUSIC.md` §9.2, the `music.ts` facts in our own code):
 * every audio file under the library roots, ffprobe'd for its tags —
 * format AND stream tags (Ogg stores vorbiscomments per stream; a
 * format-only probe indexes tagged .ogg files as artistless) — incremental
 * by mtime, vanished rows deleted only under roots this walk actually
 * read (an unmounted root walks to zero files and must never wipe its
 * rows), and never a row whose path moved mid-walk.
 */
class LibraryScan(private val db: MusicDb, private val roots: List<String>, private val ffprobe: String = "ffprobe") {

    class Summary(var scanned: Int = 0, var added: Int = 0, var updated: Int = 0, var removed: Int = 0, var failed: Int = 0) {
        override fun toString() = "$scanned files, +$added ~$updated -$removed${if (failed > 0) " ($failed FAILED)" else ""}"
    }

    class Probe(val title: String, val artist: String?, val album: String?, val durMs: Int?, val trackNo: Int?, val discNo: Int?)

    private val lock = Any()

    /** One scan at a time (a second caller waits and gets the fresh result). */
    fun scan(): Summary = synchronized(lock) { doScan() }

    private fun doScan(): Summary {
        val t0 = System.currentTimeMillis()
        val known = db.knownFiles()
        val seen = HashSet<String>()
        val s = Summary()
        val failedRoots = HashSet<String>()
        for (root in roots) {
            val rp = Path.of(root)
            if (!Files.isDirectory(rp)) { failedRoots.add(root); Log.w("music-scan", "root $root is not a directory — skipped, its rows kept"); continue }
            val files = ArrayList<Path>()
            try {
                Files.walk(rp).use { st ->
                    for (p in st) {
                        val n = p.fileName?.toString() ?: continue
                        if (n.startsWith(".") || n == "lost+found") continue
                        if (Files.isRegularFile(p) && n.substringAfterLast('.', "").lowercase() in AUDIO_EXTS) files.add(p)
                    }
                }
            } catch (e: Exception) {
                failedRoots.add(root)
                Log.e("music-scan", "cannot walk $root — its rows are kept", e)
                continue
            }
            for (p in files) {
                s.scanned++
                val path = p.toString()
                seen.add(path)
                val mtime = try { Files.getLastModifiedTime(p).toMillis() } catch (e: Exception) { continue }
                val existing = known[path]
                if (existing != null && existing.second == mtime) continue
                try {
                    val m = probe(path)
                    db.upsertTrack(path, m.title, m.artist, m.album, m.durMs, mtime, m.trackNo, m.discNo)
                    if (existing != null) s.updated++ else s.added++
                } catch (e: Exception) {
                    s.failed++
                    Log.e("music-scan", "probe/index failed for $path: ${e.message}")
                }
            }
        }
        val deletable = roots.filter { it !in failedRoots }.map { if (it.endsWith("/")) it else "$it/" }
        for ((path, info) in known) {
            if (path in seen) continue
            if (deletable.none { path.startsWith(it) }) continue
            try {
                if (db.deleteVanished(info.first, path) == 0) Log.w("music-scan", "vanished-row delete skipped for $path — the row moved mid-scan")
                else s.removed++
            } catch (e: Exception) {
                Log.e("music-scan", "removing vanished $path: ${e.message}")
            }
        }
        Log.i("music-scan", "scan done in ${(System.currentTimeMillis() - t0) / 1000.0} s: $s")
        return s
    }

    /** ffprobe's tags for one file; the file name is the title of last resort. */
    fun probe(path: String): Probe {
        val r = Exec.run(listOf(ffprobe, "-v", "error", "-show_entries",
            "format=duration:format_tags=title,artist,album,album_artist,track,disc,tracknumber,discnumber:stream_tags=title,artist,album,album_artist,track,disc,tracknumber,discnumber",
            "-of", "json", path))
        if (r.code != 0) throw IllegalStateException("ffprobe rc=${r.code}: ${r.stderr.take(200).trim()}")
        val j = musicJson.parseToJsonElement(r.stdout.toString(Charsets.UTF_8)).jsonObject
        val tags = HashMap<String, String>()
        (j["streams"] as? kotlinx.serialization.json.JsonArray)?.forEach { st ->
            st.jsonObject["tags"]?.jsonObject?.forEach { (k, v) -> tags[k.lowercase()] = v.jsonPrimitive.content }
        }
        j["format"]?.jsonObject?.get("tags")?.jsonObject?.forEach { (k, v) -> tags[k.lowercase()] = v.jsonPrimitive.content }
        val durS = j["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.content?.toDoubleOrNull()
        val base = path.substringAfterLast('/').substringBeforeLast('.')
        return Probe(
            title = tags["title"]?.trim()?.ifEmpty { null } ?: base,
            artist = tags["artist"]?.trim()?.ifEmpty { null } ?: tags["album_artist"]?.trim()?.ifEmpty { null },
            album = tags["album"]?.trim()?.ifEmpty { null },
            durMs = durS?.let { Math.round(it * 1000).toInt() },
            trackNo = parseTagNumber(tags["track"]) ?: parseTagNumber(tags["tracknumber"]),
            discNo = parseTagNumber(tags["disc"]) ?: parseTagNumber(tags["discnumber"]),
        )
    }

    companion object {
        val AUDIO_EXTS = setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma", "aiff")

        /** "3", "3/12", "03" → 3; junk → null (tag values are strings and lie). */
        fun parseTagNumber(v: String?): Int? {
            if (v == null) return null
            val m = Regex("^\\s*(\\d{1,4})\\s*(?:/|$)").find(v) ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: return null
            return if (n > 0) n else null
        }
    }
}
