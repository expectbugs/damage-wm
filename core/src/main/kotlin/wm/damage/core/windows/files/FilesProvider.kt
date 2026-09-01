package wm.damage.core.windows.files

import kotlinx.serialization.Serializable
import wm.damage.core.gfx.Gray8

/**
 * The Files window's provider seam (EXPLOSION §19-adjacent — the first real
 * consumer of the §16.10 window channel): the PC serves its filesystem, the
 * phone browses it remotely, laptop-direct binds the local provider. Every
 * operation THROWS on failure with a human message — the window surfaces it
 * (title notice / op cell), never swallows it.
 *
 * Paths are the HOST's absolute path strings everywhere (the phone never
 * interprets them beyond display) — which also makes Reader hand-off ids
 * computable on either end (LocalContent.idFor hashes the path string).
 */
@Serializable
data class FEntry(val name: String, val dir: Boolean, val size: Long, val mtimeMs: Long)

@Serializable
data class FLocation(
    val label: String, val path: String,
    /** For icon choice: home|root|downloads|books|project|mount|trash. */
    val kind: String,
    val totalBytes: Long = 0, val freeBytes: Long = 0,
)

@Serializable
data class FStat(
    val size: Long, val mtimeMs: Long,
    val mode: String, val owner: String, val dir: Boolean,
)

@Serializable
data class FTrashEntry(
    val id: String, val name: String, val origPath: String,
    val dir: Boolean, val size: Long, val atMs: Long,
)

@Serializable
data class TextChunk(val text: String, val more: Boolean, val totalBytes: Long)

@Serializable
data class PdfInfo(val pages: Int, val textChars: Long)

interface FilesProvider {
    /** §10.5 staleness line ("" healthy; "PC unreachable 40s" on the phone). */
    fun stateLine(): String = ""

    fun locations(): List<FLocation>
    fun list(dir: String, showHidden: Boolean): List<FEntry>
    fun stat(path: String): FStat

    /** Recursive size — slow on cold disks; call off-loop, narrate the op. */
    fun du(path: String): Long

    /** A bounded text window of the file; [more] says the file continues. */
    fun readText(path: String, offset: Long, maxBytes: Int): TextChunk

    /** Whole-file bytes, refused over [maxBytes] (image viewing). */
    fun readBytes(path: String, maxBytes: Int): ByteArray

    /** Server-side decoded, square-fitted thumbnail — tiny on the wire. */
    fun thumb(path: String, sizePx: Int): Gray8?

    fun pdfInfo(path: String): PdfInfo
    fun pdfText(path: String): String
    /** One page rasterized to PNG at [widthPx] (aspect kept). */
    fun pdfPage(path: String, page: Int, widthPx: Int): ByteArray

    fun trash(path: String): String
    fun trashList(): List<FTrashEntry>
    fun restore(id: String): String
    /** Permanent delete of a TRASH entry — the one unrecoverable op. */
    fun purge(id: String)

    fun rename(path: String, newName: String): String
    fun mkdir(dir: String, name: String): String
    fun copy(src: String, destDir: String): String
    fun move(src: String, destDir: String): String

    /** `xdg-open` on the host — the §16.11 "open on PC" verb. */
    fun openOnPc(path: String)
}

/** Human size: 1536 → "1.5K" (our own impl of the familiar form). */
fun fmtBytes(n: Long): String {
    if (n < 1024) return "${n}B"
    var v = n.toDouble()
    var u = -1
    val units = listOf("K", "M", "G", "T")
    while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
    return if (v >= 100) "${v.toInt()}${units[u]}" else "%.1f${units[u]}".format(v)
}

/** `rwxr-x---`-style mode string from a POSIX permission set. */
fun fmtMode(perms: Set<java.nio.file.attribute.PosixFilePermission>): String {
    val order = listOf(
        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
        java.nio.file.attribute.PosixFilePermission.GROUP_READ,
        java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
        java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
        java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
        java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
        java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
    )
    val chars = "rwxrwxrwx"
    return buildString {
        for ((i, p) in order.withIndex()) append(if (p in perms) chars[i] else '-')
    }
}

/** "Aug 31" for other days, "04:12" for today — the row's mtime column. */
fun fmtMtime(ms: Long, now: Long = System.currentTimeMillis()): String {
    val zone = java.time.ZoneId.systemDefault()
    val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    val n = java.time.Instant.ofEpochMilli(now).atZone(zone)
    return if (t.toLocalDate() == n.toLocalDate()) "%02d:%02d".format(t.hour, t.minute)
    else "${t.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${t.dayOfMonth}"
}
