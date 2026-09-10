package wm.damage.core.windows.feed

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import wm.damage.core.util.Log

/**
 * FEED.md §3.6 — the engine's files, the same on both hosts: one JSON per
 * source with its items, extracted articles and comments by item, source
 * images by URL, prepared strips by URL + width + levels + policy, and the
 * binge index. Every write is a temp file moved into place. Retention
 * (verdict 12: 30 days or 500 items, whichever first) is applied on save.
 */
class FeedStore(
    private val dir: Path,
    var keepMs: Long = 30L * 86_400_000,
    var keepMax: Int = 500,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class SourceFile(
        val version: Long = 0,
        val lastFetchMs: Long = 0,
        val lastOkMs: Long = 0,
        val items: List<Item> = emptyList(),
    )

    @Serializable
    data class CommentsFile(val atMs: Long, val comments: List<Comment>)

    @Serializable
    data class EpisodesFile(val atMs: Long, val episodes: List<Episode>)

    init { Files.createDirectories(dir) }

    private fun safe(id: String): String = id.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '_' }.joinToString("")

    private fun sourcePath(id: String) = dir.resolve("sources").resolve(safe(id) + ".json")
    private fun articlePath(itemId: String) = dir.resolve("articles").resolve(safe(itemId) + ".json")
    private fun commentsPath(itemId: String) = dir.resolve("comments").resolve(safe(itemId) + ".json")
    private fun imagePath(key: String) = dir.resolve("images").resolve(safe(key) + ".bin")
    private fun stripPath(key: String) = dir.resolve("strips").resolve(safe(key) + ".dstr")
    private fun episodesPath(id: String) = dir.resolve("binge").resolve(safe(id) + ".json")

    // ------------------------------------------------------------ sources
    fun loadSource(id: String): SourceFile? = read(sourcePath(id)) { json.decodeFromString(SourceFile.serializer(), it) }
    fun saveSource(id: String, f: SourceFile) = write(sourcePath(id), json.encodeToString(SourceFile.serializer(), f).toByteArray(Charsets.UTF_8))
    fun deleteSource(id: String) { try { Files.deleteIfExists(sourcePath(id)) } catch (e: Exception) { Log.w("feed-store", "delete ${sourcePath(id)}: ${e.message}") } }

    /** Retention: newest first, the cap, then the age limit by first-seen
     *  (a comic strip's own date can be years old; what matters is when
     *  it reached this engine). */
    fun prune(items: List<Item>, nowMs: Long): List<Item> {
        val sorted = items.sortedWith(compareByDescending<Item> { it.publishedMs }.thenByDescending { it.seenMs }.thenBy { it.id })
        val cutoff = nowMs - keepMs
        return sorted.filter { it.seenMs == 0L || it.seenMs >= cutoff }.take(keepMax)
    }

    // ------------------------------------------------------------ articles, comments
    fun loadArticle(itemId: String): Article? = read(articlePath(itemId)) { json.decodeFromString(Article.serializer(), it) }
    fun saveArticle(a: Article) = write(articlePath(a.itemId), json.encodeToString(Article.serializer(), a).toByteArray(Charsets.UTF_8))

    fun loadComments(itemId: String): CommentsFile? = read(commentsPath(itemId)) { json.decodeFromString(CommentsFile.serializer(), it) }
    fun saveComments(itemId: String, f: CommentsFile) = write(commentsPath(itemId), json.encodeToString(CommentsFile.serializer(), f).toByteArray(Charsets.UTF_8))

    // ------------------------------------------------------------ images, strips
    fun loadImage(key: String): ByteArray? = readBytes(imagePath(key))
    fun saveImage(key: String, bytes: ByteArray) = write(imagePath(key), bytes)

    fun stripKey(imageKey: String, width: Int, levels: Int, mode: LineArt) = "$imageKey-$width-$levels-${mode.name.lowercase()}"
    fun loadStrip(key: String): Strip? = readBytes(stripPath(key))?.let { b ->
        try { Strips.decode(b) } catch (e: Exception) { Log.w("feed-store", "strip $key unreadable — re-derived: ${e.message}"); null }
    }
    fun saveStrip(key: String, s: Strip) = write(stripPath(key), Strips.encode(s))

    // ------------------------------------------------------------ the binge index
    fun loadEpisodes(id: String): EpisodesFile? = read(episodesPath(id)) { json.decodeFromString(EpisodesFile.serializer(), it) }
    fun saveEpisodes(id: String, f: EpisodesFile) = write(episodesPath(id), json.encodeToString(EpisodesFile.serializer(), f).toByteArray(Charsets.UTF_8))

    // ------------------------------------------------------------ plumbing
    private fun <T> read(p: Path, decode: (String) -> T): T? {
        if (!Files.isRegularFile(p)) return null
        return try { decode(Files.readString(p)) } catch (e: Exception) {
            Log.w("feed-store", "$p unreadable — treated as absent: ${e.message}")
            null
        }
    }

    private fun readBytes(p: Path): ByteArray? {
        if (!Files.isRegularFile(p)) return null
        return try { Files.readAllBytes(p) } catch (e: Exception) { Log.w("feed-store", "$p unreadable: ${e.message}"); null }
    }

    private fun write(p: Path, bytes: ByteArray) {
        Files.createDirectories(p.parent)
        val tmp = p.resolveSibling(p.fileName.toString() + ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun imageKey(url: String): String = FeedIds.sha1(url).substring(0, 20)
    }
}
