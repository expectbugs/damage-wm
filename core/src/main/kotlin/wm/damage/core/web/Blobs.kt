package wm.damage.core.web

import java.io.OutputStream
import java.util.zip.ZipFile
import wm.damage.core.app.Levels
import wm.damage.core.replica.HttpRequest
import wm.damage.core.replica.HttpWs
import wm.damage.core.util.Log
import wm.damage.core.windows.reader.EpubDoc
import wm.damage.core.windows.reader.ReaderCore
import wm.damage.core.windows.reader.ReaderDocs

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (`CONTRACT.md` §9.4): `GET /blob/<id>` on the web interface listener, under the device secret
 * and the blob's minimum level, with Range. Every answer carries `X-Content-Type-Options: nosniff` and
 * `Content-Security-Policy: sandbox`, and `Content-Disposition: attachment` for any type outside a short list of image,
 * audio and video types (SVG is not on it), so no file the PC serves runs script as the app's page. S1-1 serves
 * Reader's images and covers ([ReaderBlobs]); renditions (`?r=`) and uploads are S1-2's.
 *
 * Build S1-2, package Q2 (the spec's §2.4, §5 V5): several sources by id prefix ([BlobSources]: `reader.` Reader's,
 * `f.` Files'); a [FileBlob] streamed from its file with Range — no copy in memory and no size cap, its length the size
 * read once at open; `?dl=1` makes any answer a download (`Content-Disposition: attachment`), so a video asked for as a
 * download never plays inline; `?r=<rendition>` asks the blob's source for a form it makes with today's code.
 */

/** What a blob answer serves: its type, the level it needs, and the file name an attachment carries. */
sealed interface BlobBody {
    val type: String
    val minLevel: String
    val name: String
}

/** One blob held in memory (Reader's images, a rendition made on the PC). */
class Blob(override val type: String, val bytes: ByteArray, override val minLevel: String, override val name: String) : BlobBody

/** S1-2 Q2: one file served from [path] as it is read, with Range; never read whole into memory. */
class FileBlob(val path: java.nio.file.Path, override val type: String, override val minLevel: String, override val name: String) : BlobBody

/** Names a blob by id; null = no such blob. May block (call it off any loop). Throws [BlobTooLarge] for a blob past
 *  what the listener serves, and an [Exception] for one that could not be read (each answered with its status). */
fun interface BlobSource {
    fun resolve(id: String): BlobBody?

    /** S1-2 Q2: [id]'s rendition [r] (`?r=`, e.g. `thumb:320`, `pdf:2:1024`), made by this source's own code; null =
     *  no such blob or no such rendition of it. Throws as [resolve] does. */
    fun rendition(id: String, r: String): BlobBody? = null
}

/** S1-2 Q2: the blob sources by id prefix (the spec's §2.4 registry); an id no prefix names is no blob. */
class BlobSources(private val byPrefix: Map<String, BlobSource>) : BlobSource {
    private fun of(id: String): BlobSource? = byPrefix.entries.firstOrNull { id.startsWith(it.key) }?.value

    override fun resolve(id: String): BlobBody? = of(id)?.resolve(id)

    override fun rendition(id: String, r: String): BlobBody? = of(id)?.rendition(id, r)
}

/** A blob past the size the listener serves: refused (413) with this reason, never cut (fix round 1, ruling 2.3 (8)). */
class BlobTooLarge(message: String) : RuntimeException(message)

object Blobs {
    /** The types a browser may show inline; everything else is a download. No SVG: it can carry script. */
    val INLINE: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp",
        "audio/mpeg", "audio/ogg", "audio/flac", "audio/wav", "audio/mp4", "audio/aac", "video/mp4", "video/webm")

    /** The headers every blob answer carries (before any range header); [download] (`?dl=1`) makes any type an
     *  attachment. */
    fun headers(b: BlobBody, download: Boolean = false): List<Pair<String, String>> = buildList {
        add("X-Content-Type-Options" to "nosniff")
        add("Content-Security-Policy" to "sandbox")
        if (download || b.type !in INLINE) add("Content-Disposition" to "attachment; filename=\"${b.name.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "blob" }}\"")
        add("Accept-Ranges" to "bytes")
        add("Cache-Control" to "private, no-cache")
        add("Referrer-Policy" to "no-referrer")
    }

    /** Writes [b] for [req]: whole (200), one range (206), or 416 for a range outside it. Several ranges get the whole
     *  blob (RFC 9110 lets a server ignore Range). A [FileBlob] is streamed from its file, its length the size read
     *  once at open (a file growing meanwhile is served to that length). */
    fun serve(out: OutputStream, req: HttpRequest, b: BlobBody, download: Boolean = false) {
        val h = headers(b, download)
        when (b) {
            is Blob -> {
                val n = b.bytes.size.toLong()
                val range = req.headers["range"]?.let { parseRange(it, n) }
                when {
                    range == null -> HttpWs.replyBytes(out, 200, b.type, b.bytes, h)
                    range.first < 0 -> outside(out, n, h)
                    else -> {
                        val (from, to) = range
                        HttpWs.head(out, 206, b.type, to - from + 1, h + ("Content-Range" to "bytes $from-$to/$n"))
                        out.write(b.bytes, from.toInt(), (to - from + 1).toInt())
                        out.flush()
                    }
                }
            }
            is FileBlob -> java.nio.channels.FileChannel.open(b.path, java.nio.file.StandardOpenOption.READ).use { ch ->
                val n = ch.size()
                val range = req.headers["range"]?.let { parseRange(it, n) }
                when {
                    range == null -> {
                        HttpWs.head(out, 200, b.type, n, h)
                        copy(ch, 0, n, out)
                    }
                    range.first < 0 -> outside(out, n, h)
                    else -> {
                        val (from, to) = range
                        HttpWs.head(out, 206, b.type, to - from + 1, h + ("Content-Range" to "bytes $from-$to/$n"))
                        copy(ch, from, to - from + 1, out)
                    }
                }
            }
        }
    }

    private fun outside(out: OutputStream, n: Long, h: List<Pair<String, String>>) =
        HttpWs.replyBytes(out, 416, "text/plain", "range outside the ${n}-byte blob".toByteArray(), h + ("Content-Range" to "bytes */$n"))

    /** [len] bytes of [ch] from [from] into [out], a buffer at a time. A file that ends early (cut meanwhile) throws:
     *  the answer's length was promised, so the request ends rather than send fewer bytes than it said. */
    private fun copy(ch: java.nio.channels.FileChannel, from: Long, len: Long, out: OutputStream) {
        val buf = java.nio.ByteBuffer.allocate(COPY_BUFFER)
        var at = from
        var left = len
        while (left > 0) {
            buf.clear()
            if (left < buf.capacity()) buf.limit(left.toInt())
            val got = ch.read(buf, at)
            if (got < 0) throw java.io.EOFException("the file ended at byte $at, before the $len bytes promised from $from")
            out.write(buf.array(), 0, got)
            at += got
            left -= got
        }
        out.flush()
    }

    private const val COPY_BUFFER = 64 * 1024

    /**
     * One `bytes=` range of a blob of [n] bytes as (first, last), both inclusive; (-1, -1) when it cannot be met (416);
     * null when the header is not one range of bytes (served whole).
     */
    fun parseRange(h: String, n: Long): Pair<Long, Long>? {
        val v = h.trim()
        if (!v.startsWith("bytes=")) return null
        val spec = v.removePrefix("bytes=").trim()
        if (',' in spec) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val z = spec.substring(dash + 1).trim()
        return when {
            a.isEmpty() -> {
                val k = z.toLongOrNull() ?: return null
                if (k <= 0 || n == 0L) -1L to -1L else maxOf(0, n - k) to n - 1
            }
            else -> {
                val from = a.toLongOrNull() ?: return null
                val to = if (z.isEmpty()) n - 1 else z.toLongOrNull()?.coerceAtMost(n - 1) ?: return null
                if (from >= n || from > to) -1L to -1L else from to to
            }
        }
    }

    /** A type from a file name's extension. */
    fun typeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}

/**
 * Reader's blobs (C§9.4; the P5 report's "For the next packages"): `reader.<book>.<n>` is image n of the book's
 * document — the cover from the reader index without a parse when n is its cover, else from the parsed document
 * ([ReaderDocs], the last two books). The zip entry is read the way `Epub` reads it: the decoded path, then the path as
 * written. Minimum level view (reading). An entry past [maxBytes] is refused and said, never cut.
 */
class ReaderBlobs(
    private val core: ReaderCore,
    private val docs: ReaderDocs,
    private val maxBytes: Long = MAX_BYTES,
) : BlobSource {
    override fun resolve(id: String): Blob? {
        val (book, n) = EpubDoc.parseBlobId(id) ?: return null
        val file = core.pathOf(book) ?: return null
        val meta = core.library.firstOrNull { it.id == book }
        val entry = meta?.let { core.index?.entry(it.file, it.bytes) }
        val zipPath = if (entry?.coverRef == n && entry.cover != null) entry.cover else docs.doc(book)?.images?.getOrNull(n)
        zipPath ?: return null
        val bytes = ZipFile(file.toFile()).use { zip ->
            val e = zip.getEntry(urlDecode(zipPath)) ?: zip.getEntry(zipPath) ?: return null
            if (e.size > maxBytes) throw BlobTooLarge("image $n of book $book is ${e.size} bytes, past the $maxBytes this listener serves")
            zip.getInputStream(e).use { it.readBytes() }
        }
        val name = zipPath.substringAfterLast('/')
        return Blob(Blobs.typeOf(name), bytes, Levels.VIEW, name)
    }

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        Log.w("web", "an image path does not decode (${e::class.simpleName}); read as written")
        s
    }

    companion object {
        const val MAX_BYTES = 32L shl 20
        /** S1-2 Q2: Reader's ids in the blob registry (`EpubDoc.blobId`). */
        const val PREFIX = "reader."
    }
}
