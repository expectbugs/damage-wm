package wm.damage.core.windows.music

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import wm.damage.core.util.Exec
import wm.damage.core.util.Log

/**
 * Album art (`MUSIC.md` §6.4): the embedded picture (ffmpeg extracts it as
 * raw gray pixels, box-sampled by its `scale` filter with `flags=area`)
 * else `folder.jpg` / `cover.jpg` beside the file, else none. Packed to
 * 4-bit gray, two pixels a byte (high nibble first — the panel's own
 * nibble order), cached as `<key>-<px>.gray`; a miss is remembered as a
 * `.none` marker so a library of 3 k tracks is probed once.
 */
class Art(private val dir: Path, private val ffmpeg: String = "ffmpeg") {
    private val misses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init { Files.createDirectories(dir) }

    private val FOLDER_NAMES = listOf("folder.jpg", "cover.jpg", "Folder.jpg", "Cover.jpg", "front.jpg", "folder.png", "cover.png", "album.jpg")

    fun folderImage(trackPath: String): Path? {
        val parent = Path.of(trackPath).parent ?: return null
        return FOLDER_NAMES.map(parent::resolve).firstOrNull { Files.isRegularFile(it) }
    }

    /** The keys with a cached extract — listed ONCE per catalog build (a
     *  directory listing per track was O(n²) over a 3 k library). */
    @Volatile private var cachedKeys: Set<String>? = null

    fun beginCatalog() {
        cachedKeys = try {
            Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.contains('-') && it.endsWith(".gray") }
                .map { it.substringBeforeLast('-') }.toList().toHashSet() }
        } catch (e: Exception) { emptySet() }
    }

    /** Cheap existence bit for the catalog: a folder image, or a cached extract. */
    fun likelyHas(key: String, trackPath: String): Boolean =
        (cachedKeys?.contains(key) == true) || folderImage(trackPath) != null

    /** The packed 4-bit px×px image, or null when there is none. Throws only
     *  when ffmpeg itself is missing. */
    fun get(key: String, trackPath: String, px: Int): ByteArray? {
        val size = px.coerceIn(8, 256)
        val cached = dir.resolve("$key-$size.gray")
        if (Files.exists(cached)) return Files.readAllBytes(cached)
        val none = dir.resolve("$key.none")
        if (Files.exists(none) || key in misses) return null
        var gray = extract(trackPath, size)
        if (gray == null) folderImage(trackPath)?.let { gray = extract(it.toString(), size) }
        if (gray == null) {
            misses.add(key)
            try { Files.writeString(none, "") } catch (e: Exception) { Log.w("music-art", "marker for $key not written: ${e.message}") }
            return null
        }
        val packed = pack(gray!!, size * size)
        try {
            val tmp = cached.resolveSibling("$key-$size.gray.${System.nanoTime()}.tmp")
            Files.write(tmp, packed)
            Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Log.w("music-art", "art cache write failed for $key: ${e.message}")
        }
        return packed
    }

    /** `size*size` gray8 bytes from the first picture stream of [file], box-sampled, or null. */
    private fun extract(file: String, size: Int): ByteArray? {
        val r = try {
            Exec.run(listOf(ffmpeg, "-v", "error", "-i", file, "-an", "-map", "0:v:0", "-frames:v", "1",
                "-vf", "scale=$size:$size:flags=area", "-f", "rawvideo", "-pix_fmt", "gray", "-"))
        } catch (e: java.io.IOException) {
            throw IllegalStateException("ffmpeg not runnable: ${e.message}")
        }
        if (r.code != 0 || r.stdout.size < size * size) return null
        return r.stdout.copyOfRange(0, size * size)
    }

    companion object {
        /** gray8 → packed nibbles (high nibble first). */
        fun pack(gray: ByteArray, pixels: Int): ByteArray {
            val out = ByteArray((pixels + 1) / 2)
            for (i in 0 until pixels) {
                val v = if (i < gray.size) gray[i].toInt() and 0xFF else 0
                val n = minOf(15, (v + 8) / 17)
                if (i and 1 == 0) out[i shr 1] = (n shl 4).toByte() else out[i shr 1] = (out[i shr 1].toInt() or n).toByte()
            }
            return out
        }

        /** Packed nibbles → a Gray8 of [w]×[h] (levels ×17). */
        fun unpack(packed: ByteArray, w: Int, h: Int): wm.damage.core.gfx.Gray8 {
            val g = wm.damage.core.gfx.Gray8(w, h)
            for (i in 0 until w * h) {
                val b = packed.getOrNull(i shr 1)?.toInt()?.and(0xFF) ?: 0
                val n = if (i and 1 == 0) b shr 4 else b and 0x0F
                g[i % w, i / w] = n * 17
            }
            return g
        }
    }
}
