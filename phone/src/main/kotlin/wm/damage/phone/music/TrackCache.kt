package wm.damage.phone.music

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import wm.damage.core.util.Log

/**
 * The prefetch store (`MUSIC.md` §7): the next N queue entries' files for
 * the current profile, fetched over the media endpoint into the app's
 * cache dir, LRU beyond N + 2; ExoPlayer plays the local file when present
 * and falls back to the PC stream. With the PC down the queue plays from
 * here. One download at a time (a queue replace must not fan out); NO
 * TIMEOUTS — a stalled fetch is reported by the link, and the next
 * prefetch pass re-asks.
 */
class TrackCache(private val dir: File) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "music-prefetch").apply { isDaemon = true } }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var keep = 5

    init { dir.mkdirs() }

    private fun key(url: String): String {
        // <id>-<profile> from the endpoint URL: /track/<id>?token=…&profile=<name>
        val id = Regex("/track/(\\d+)").find(url)?.groupValues?.get(1) ?: url.hashCode().toString()
        val profile = Regex("[?&]profile=([^&]+)").find(url)?.groupValues?.get(1) ?: "default"
        return "$id-$profile"
    }

    fun fileFor(url: String): File? = File(dir, key(url) + ".bin").takeIf { it.isFile && it.length() > 0 }?.also { it.setLastModified(System.currentTimeMillis()) }

    fun isCached(url: String): Boolean = fileFor(url) != null

    /** Keep [urls] (in queue order) at hand; the first is the most urgent. */
    fun prefetch(urls: List<Pair<Int, String>>, keepN: Int) {
        keep = maxOf(2, keepN + 2)
        val wanted = urls.map { key(it.second) }.toHashSet()
        for ((_, url) in urls) {
            val k = key(url)
            if (File(dir, "$k.bin").isFile || !inFlight.add(k)) continue
            exec.execute {
                try { download(url, File(dir, "$k.bin")) }
                catch (e: Exception) { Log.w("music-cache", "prefetch $k: ${e.message}") }
                finally { inFlight.remove(k) }
            }
        }
        exec.execute { evict(wanted) }
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dir, dest.name + ".part")
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.useCaches = false
        try {
            val status = c.responseCode
            if (status != 200) throw IllegalStateException("HTTP $status for ${dest.name}")
            c.inputStream.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out, 64 * 1024) } }
            if (tmp.length() <= 0) throw IllegalStateException("empty body")
            if (!tmp.renameTo(dest)) throw IllegalStateException("rename failed")
            Log.i("music-cache", "prefetched ${dest.name} (${dest.length() / 1024} KB)")
        } finally {
            tmp.delete()
            c.disconnect()
        }
    }

    /** LRU beyond [keep], never a file the queue still wants. */
    private fun evict(wanted: Set<String>) {
        val files = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedByDescending { it.lastModified() } ?: return
        var kept = 0
        for (f in files) {
            val k = f.name.removeSuffix(".bin")
            if (k in wanted || kept < keep) { kept++; continue }
            if (f.delete()) Log.d("music-cache", "evicted $k")
        }
    }

    fun close() { exec.shutdownNow() }
}
