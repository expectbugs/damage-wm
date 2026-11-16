package wm.damage.core.windows.files

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import wm.damage.core.app.Levels
import wm.damage.core.web.Blob
import wm.damage.core.web.BlobBody
import wm.damage.core.web.BlobSource
import wm.damage.core.web.FileBlob

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1 "Blobs", §2.4; `CONTRACT.md` §9.4; decisions A.8): Files' blobs on the web
 * interface listener, at level full. `f.<base64url of the path's UTF-8, no padding>` is the file itself, streamed
 * with Range (`?dl=1` a download, `Blobs.headers`); its renditions are made on the PC by today's code:
 * `?r=thumb:<px>` a colour JPEG for the grid (an injected scaler: the desktop's AWT; the core stays free of it) and
 * `?r=pdf:<page>:<width>` a PNG page by the provider's `pdfPage` (`pdftoppm`), the width capped at 2048.
 */
class FilesBlobs(
    private val files: FilesProvider,
    /** Makes a colour JPEG at most [Int] pixels on its long side from an image's bytes, or null when they do not
     *  decode; null = no thumbnails on this host. */
    private val thumbs: ((ByteArray, Int) -> ByteArray?)? = null,
) : BlobSource {
    override fun resolve(id: String): BlobBody? {
        val path = pathOf(id) ?: return null
        val p = Path.of(path)
        if (!Files.isRegularFile(p)) return null
        return FileBlob(p, typeOf(p.fileName.toString()), Levels.FULL, p.fileName.toString())
    }

    override fun rendition(id: String, r: String): BlobBody? {
        val path = pathOf(id) ?: return null
        val name = Path.of(path).fileName?.toString() ?: return null
        if (!Files.isRegularFile(Path.of(path))) return null
        val parts = r.split(':')
        return when {
            parts.size == 2 && parts[0] == "thumb" -> {
                val px = parts[1].toIntOrNull()?.takeIf { it in 16..THUMB_MAX } ?: return null
                val make = thumbs ?: return null
                val jpeg = make(files.readBytes(path, THUMB_SOURCE_MAX), px) ?: return null
                Blob("image/jpeg", jpeg, Levels.FULL, "$name.thumb.jpg")
            }
            parts.size == 3 && parts[0] == "pdf" -> {
                val page = parts[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                val width = parts[2].toIntOrNull()?.takeIf { it >= 16 }?.coerceAtMost(PDF_MAX) ?: return null
                Blob("image/png", files.pdfPage(path, page, width), Levels.FULL, "$name.$page.png")
            }
            else -> null
        }
    }

    companion object {
        const val PREFIX = "f."
        const val THUMB_MAX = 1024
        const val PDF_MAX = 2048
        /** The biggest picture a thumbnail is made from (the glasses' lens thumbnail cap). */
        const val THUMB_SOURCE_MAX = 24 shl 20

        /** The blob id of [path]. */
        fun idOf(path: String): String =
            PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))

        /** The absolute path [id] names, or null when it names none. */
        fun pathOf(id: String): String? {
            if (!id.startsWith(PREFIX)) return null
            val p = try {
                String(Base64.getUrlDecoder().decode(id.removePrefix(PREFIX)), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                return null
            }
            return p.takeIf { it.startsWith("/") && '\u0000' !in it }
        }

        /** The type a browser is told (anything outside `Blobs.INLINE` is a download there). */
        fun typeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "txt", "md", "log", "nfo", "srt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
