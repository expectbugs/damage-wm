package wm.damage.core.windows.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import wm.damage.core.util.Log

/**
 * §47 (2026-09-12): the phone's copy of what the PC last answered — the
 * locations and each folder's listing — so the Files window shows a folder
 * at once and the live answer follows behind it. The measured case: every
 * window-channel round trip rode a Tailscale relay at 60–1,100 ms that day,
 * and a folder took that long to appear at all.
 *
 * Plain JSON files under [dir]: `locations.json` and `list/<sha1>.json`, one
 * per (folder, hidden) key, written whole and moved into place (a torn file
 * is never read). Bounded: past [MAX_LISTINGS] files the oldest by write
 * time go. Every read that fails is logged and answers null — a cache is
 * never a reason to show nothing. Never consulted by the PC's own provider
 * (a filesystem does not cache); the phone's remote provider wraps it.
 */
class ListingCache(private val dir: Path, private val maxListings: Int = MAX_LISTINGS) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listDir = dir.resolve("list")

    init {
        Files.createDirectories(listDir)
    }

    /** One key per (folder, hidden): the folder path hashed, the flag after
     *  a separator no path can contain. */
    private fun keyOf(folder: String, hidden: Boolean): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(folder.toByteArray(Charsets.UTF_8))
        md.update(if (hidden) 1 else 0)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun locations(): List<FLocation>? = read(dir.resolve("locations.json"), "locations") {
        json.decodeFromString(ListSerializer(FLocation.serializer()), it)
    }

    fun putLocations(locs: List<FLocation>) =
        write(dir.resolve("locations.json"), json.encodeToString(ListSerializer(FLocation.serializer()), locs), "locations")

    fun list(folder: String, hidden: Boolean): List<FEntry>? =
        read(listDir.resolve(keyOf(folder, hidden) + ".json"), "listing of $folder") {
            json.decodeFromString(ListSerializer(FEntry.serializer()), it)
        }

    fun putList(folder: String, hidden: Boolean, entries: List<FEntry>) {
        write(listDir.resolve(keyOf(folder, hidden) + ".json"),
            json.encodeToString(ListSerializer(FEntry.serializer()), entries), "listing of $folder")
        prune()
    }

    /** Listings on disk right now (tests, status). */
    fun listingCount(): Int = try {
        Files.list(listDir).use { s -> s.filter { it.toString().endsWith(".json") }.count().toInt() }
    } catch (e: Exception) { 0 }

    private fun <T> read(file: Path, what: String, decode: (String) -> T): T? {
        if (!Files.isRegularFile(file)) return null
        return try {
            decode(Files.readString(file, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w("files-cache", "cached $what unreadable (${e.message}) — dropped")
            try { Files.deleteIfExists(file) } catch (x: Exception) { Log.w("files-cache", "drop failed: ${x.message}") }
            null
        }
    }

    private fun write(file: Path, text: String, what: String) {
        try {
            val tmp = file.resolveSibling(file.fileName.toString() + ".${System.nanoTime()}.tmp")
            Files.writeString(tmp, text, Charsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Log.w("files-cache", "cached $what not written: ${e.message}")
        }
    }

    /** Oldest listings past the cap go — a tour through a big tree must not
     *  grow the phone's storage without bound. */
    private fun prune() {
        try {
            val files = Files.list(listDir).use { s -> s.filter { it.toString().endsWith(".json") }.toList() }
            if (files.size <= maxListings) return
            val byAge = files.sortedBy { try { Files.getLastModifiedTime(it).toMillis() } catch (e: Exception) { 0L } }
            for (f in byAge.take(files.size - maxListings)) try { Files.deleteIfExists(f) } catch (e: Exception) {
                Log.w("files-cache", "prune ${f.fileName}: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w("files-cache", "prune failed: ${e.message}")
        }
    }

    companion object {
        const val MAX_LISTINGS = 200
    }
}
