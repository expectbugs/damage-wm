package wm.damage.core.windows.files

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconRaster
import wm.damage.core.gfx.ImageDecoder
import wm.damage.core.util.Log

/**
 * The host-side filesystem provider. Lineage: G2CC `files.ts` (read for facts
 * — the locations shape, the /proc/mounts rule, the dirent lesson), rebuilt
 * on java.nio for the DamageWindow contract.
 *
 * Trash is Damage's OWN (`~/.damage/trash/<id>/…` + `manifest.json`) — never
 * G2CC's, never the desktop's. Restore is a first-class verb (an upgrade over
 * G2CC's navigate-and-move-out); purge is the one unrecoverable op and lives
 * behind the window's double confirm.
 *
 * Cross-device DIRECTORY trash/move is refused loudly (a recursive
 * copy+delete of a whole tree from a cold HDD is not a glasses-sized op);
 * cross-device FILES fall back to copy+delete. Known limit, stated.
 */
class LocalFilesProvider(
    private val booksDir: Path,
    private val trashDir: Path,
    private val decoder: ImageDecoder? = null,
    private val extraRoots: List<Pair<String, String>> = emptyList(),
    /** Injected for tests: mounts source + exec runner. */
    private val mountsFile: Path = Path.of("/proc/mounts"),
    private val exec: (List<String>) -> ExecResult = ::runExec,
) : FilesProvider {

    class ExecResult(val code: Int, val stdout: ByteArray, val stderr: String)

    @Serializable
    private data class Manifest(val entries: List<FTrashEntry> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val manifestPath: Path get() = trashDir.resolve("manifest.json")
    private val trashLock = Any()

    // ------------------------------------------------------------- locations
    override fun locations(): List<FLocation> {
        val home = System.getProperty("user.home")
        val out = ArrayList<FLocation>()
        fun add(label: String, path: String, kind: String) {
            val p = Path.of(path)
            if (!Files.isDirectory(p)) return
            val (total, free) = space(p)
            out.add(FLocation(label, path, kind, total, free))
        }
        add("Root", "/", "root")
        add("Home", home, "home")
        add("Downloads", "$home/Downloads", "downloads")
        add("Books", booksDir.toString(), "books")
        add("damagewm", "$home/damagewm", "project")
        for ((label, path) in extraRoots) add(label, path, "mount")
        // mounted volumes per /proc/mounts (G2CC rule: an unmounted mountpoint
        // is just an empty dir — never list it); mountpoints octal-escape
        // spaces etc. (\040)
        try {
            val seen = HashSet<String>()
            for (line in Files.readAllLines(mountsFile)) {
                val mp = line.split(' ').getOrNull(1) ?: continue
                val path = mp.replace(Regex("\\\\([0-7]{3})")) {
                    it.groupValues[1].toInt(8).toChar().toString()
                }
                if ((path.startsWith("/mnt/") || path.startsWith("/run/media/")) && seen.add(path)) {
                    add(path.substringAfterLast('/'), path, "mount")
                }
            }
        } catch (e: Exception) {
            Log.w("files", "cannot read $mountsFile: ${e.message}")
        }
        // Trash appears once something is in it
        if (trashList().isNotEmpty()) out.add(FLocation("Trash", trashDir.toString(), "trash"))
        return out
    }

    private fun space(p: Path): Pair<Long, Long> = try {
        val fs = Files.getFileStore(p)
        fs.totalSpace to fs.usableSpace
    } catch (e: Exception) {
        0L to 0L
    }

    // --------------------------------------------------------------- browse
    override fun list(dir: String, showHidden: Boolean): List<FEntry> {
        val d = Path.of(dir)
        require(Files.isDirectory(d)) { "$dir is not a directory" }
        val out = ArrayList<FEntry>()
        Files.newDirectoryStream(d).use { stream ->
            for (p in stream) {
                val name = p.fileName?.toString() ?: continue
                if (!showHidden && name.startsWith('.')) continue
                // NOFOLLOW so a broken symlink still lists (as a file) instead
                // of throwing the whole listing away
                val a = try {
                    Files.readAttributes(p, BasicFileAttributes::class.java)
                } catch (e: IOException) {
                    try {
                        Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (e2: IOException) {
                        Log.w("files", "unreadable entry $p: ${e2.message}")
                        continue
                    }
                }
                out.add(FEntry(name, a.isDirectory, if (a.isDirectory) 0 else a.size(),
                    a.lastModifiedTime().toMillis()))
            }
        }
        return out
    }

    override fun stat(path: String): FStat {
        val p = Path.of(path)
        val a = Files.readAttributes(p, BasicFileAttributes::class.java)
        val posix = try {
            Files.getFileAttributeView(p, PosixFileAttributeView::class.java)?.readAttributes()
        } catch (e: Exception) { null }
        return FStat(
            size = if (a.isDirectory) 0 else a.size(),
            mtimeMs = a.lastModifiedTime().toMillis(),
            mode = posix?.permissions()?.let { fmtMode(it) } ?: "",
            owner = posix?.owner()?.name ?: "",
            dir = a.isDirectory,
        )
    }

    override fun du(path: String): Long {
        var total = 0L
        Files.walkFileTree(Path.of(path), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                total += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                Log.w("files", "du: $file unreadable (${exc.message})")
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    // -------------------------------------------------------------- viewers
    override fun readText(path: String, offset: Long, maxBytes: Int): TextChunk {
        val p = Path.of(path)
        val total = Files.size(p)
        require(offset in 0..total) { "offset $offset outside $total-byte file" }
        var n = minOf(maxBytes.toLong(), total - offset).toInt()
        val buf = ByteArray(n)
        java.io.RandomAccessFile(p.toFile(), "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buf)
        }
        // never cut a UTF-8 character at the chunk seam (review 2026-09-01
        // Fi#2): back off over a trailing partial sequence (≤3 bytes) unless
        // this is the file's end. Binary junk stays lossy but [bytesRead]
        // keeps the offset truthful either way.
        if (offset + n < total) {
            var trim = 0
            while (trim < 3 && n - 1 - trim >= 0) {
                val b = buf[n - 1 - trim].toInt() and 0xFF
                if (b and 0xC0 == 0x80) { trim++; continue }        // continuation byte
                // a lead byte whose sequence would run past the end is dropped
                val need = when {
                    b and 0x80 == 0 -> 1
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                if (need > trim + 1) trim++ else trim = -trim       // sequence complete: keep all
                break
            }
            if (trim > 0 && trim < n) n -= trim
        }
        return TextChunk(String(buf, 0, n, Charsets.UTF_8), offset + n < total, total, n.toLong())
    }

    override fun readBytes(path: String, maxBytes: Int): ByteArray {
        val p = Path.of(path)
        val size = Files.size(p)
        require(size <= maxBytes) { "${fmtBytes(size)} is over the ${fmtBytes(maxBytes.toLong())} viewer cap" }
        return Files.readAllBytes(p)
    }

    override fun thumb(path: String, sizePx: Int): Gray8? {
        val dec = decoder ?: return null
        val bytes = try { readBytes(path, 24 shl 20) } catch (e: Exception) { return null }
        val d = dec.decode(bytes) ?: return null
        return IconRaster.toSquare(d, sizePx.coerceIn(16, 128))
    }

    // ------------------------------------------------------------------ pdf
    override fun pdfInfo(path: String): PdfInfo {
        val r = exec(listOf("pdfinfo", path))
        require(r.code == 0) { "pdfinfo failed: ${r.stderr.take(200)}" }
        val pages = Regex("Pages:\\s+(\\d+)").find(r.stdout.toString(Charsets.UTF_8))
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IllegalStateException("pdfinfo gave no page count")
        // extractability probe: the first two pages' text volume
        val t = exec(listOf("pdftotext", "-l", "2", path, "-"))
        val chars = if (t.code == 0) t.stdout.size.toLong() else 0L
        return PdfInfo(pages, chars)
    }

    override fun pdfText(path: String): String {
        val r = exec(listOf("pdftotext", "-layout", path, "-"))
        require(r.code == 0) { "pdftotext failed: ${r.stderr.take(200)}" }
        require(r.stdout.size <= (16 shl 20)) { "extracted text over the 16 MB cap" }
        return r.stdout.toString(Charsets.UTF_8)
    }

    override fun pdfPage(path: String, page: Int, widthPx: Int): ByteArray {
        val r = exec(listOf("pdftoppm", "-png", "-f", "$page", "-l", "$page",
            "-scale-to-x", "$widthPx", "-scale-to-y", "-1", path))
        require(r.code == 0 && r.stdout.isNotEmpty()) { "pdftoppm page $page failed: ${r.stderr.take(200)}" }
        return r.stdout
    }

    // ---------------------------------------------------------------- trash
    private fun readManifest(): Manifest = synchronized(trashLock) {
        if (!Files.isRegularFile(manifestPath)) return Manifest()
        try {
            json.decodeFromString(Manifest.serializer(), Files.readString(manifestPath))
        } catch (e: Exception) {
            Log.e("files", "trash manifest unreadable — treating as empty (files stay on disk)", e)
            Manifest()
        }
    }

    private fun writeManifest(m: Manifest) = synchronized(trashLock) {
        Files.createDirectories(trashDir)
        val tmp = manifestPath.resolveSibling("manifest.json.${System.nanoTime()}.tmp")
        Files.writeString(tmp, json.encodeToString(Manifest.serializer(), m))
        Files.move(tmp, manifestPath, StandardCopyOption.REPLACE_EXISTING)
    }

    // Every trash op holds [trashLock] across its WHOLE read-modify-write:
    // the provider is shared by the local shell and the phone's channel
    // (review 2026-09-01 Fi#9 — two near-simultaneous trashes lost the first
    // manifest entry, leaving an invisible, un-restorable payload).
    override fun trash(path: String): String = synchronized(trashLock) {
        val p = Path.of(path).toAbsolutePath()
        require(Files.exists(p, LinkOption.NOFOLLOW_LINKS)) { "$path does not exist" }
        require(!p.startsWith(trashDir)) { "already in the trash" }
        val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val id = "${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        val destDir = trashDir.resolve(id)
        Files.createDirectories(destDir)
        val dest = destDir.resolve(p.fileName.toString())
        moveEntry(p, dest, allowCrossDeviceDirCopy = false,
            what = "trash of a folder on another volume — delete it on the PC instead")
        val e = FTrashEntry(id, p.fileName.toString(), p.toString(), a.isDirectory,
            if (a.isDirectory) 0 else a.size(), System.currentTimeMillis())
        writeManifest(Manifest(readManifest().entries + e))
        id
    }

    override fun trashList(): List<FTrashEntry> = readManifest().entries.sortedByDescending { it.atMs }

    override fun restore(id: String): String = synchronized(trashLock) {
        val m = readManifest()
        val e = m.entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no trash entry $id")
        val src = trashDir.resolve(id).resolve(e.name)
        require(Files.exists(src, LinkOption.NOFOLLOW_LINKS)) { "trash payload for $id is gone" }
        val dest = Path.of(e.origPath)
        require(!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) { "${e.origPath} already exists — restore refused" }
        dest.parent?.let { Files.createDirectories(it) }
        moveEntry(src, dest, allowCrossDeviceDirCopy = false,
            what = "restore of a folder across volumes — move it on the PC instead")
        writeManifest(Manifest(m.entries.filterNot { it.id == id }))
        try { Files.deleteIfExists(trashDir.resolve(id)) } catch (e2: IOException) {
            Log.w("files", "empty trash slot $id not removed: ${e2.message}")
        }
        dest.toString()
    }

    override fun purge(id: String): Unit = synchronized(trashLock) {
        val m = readManifest()
        require(m.entries.any { it.id == id }) { "no trash entry $id" }
        deleteRecursively(trashDir.resolve(id))
        writeManifest(Manifest(m.entries.filterNot { it.id == id }))
    }

    // ------------------------------------------------------------------ ops
    override fun rename(path: String, newName: String): String {
        require(newName.isNotBlank() && !newName.contains('/')) { "'$newName' is not a valid name" }
        val p = Path.of(path)
        val dest = p.resolveSibling(newName)
        require(!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) { "$newName already exists here" }
        Files.move(p, dest)
        return dest.toString()
    }

    override fun mkdir(dir: String, name: String): String {
        require(name.isNotBlank() && !name.contains('/')) { "'$name' is not a valid name" }
        val dest = Path.of(dir).resolve(name)
        require(!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) { "$name already exists here" }
        Files.createDirectory(dest)
        return dest.toString()
    }

    override fun copy(src: String, destDir: String): String {
        val s = Path.of(src)
        val dest = Path.of(destDir).resolve(s.fileName.toString())
        require(!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) { "${s.fileName} already exists there" }
        require(!dest.toAbsolutePath().startsWith(s.toAbsolutePath())) { "cannot copy a folder into itself" }
        if (Files.isDirectory(s, LinkOption.NOFOLLOW_LINKS)) {
            try {
                copyTree(s, dest)
            } catch (e: IOException) {
                // no half-copied tree left behind (review 2026-09-01 Fi#14)
                try { deleteRecursively(dest) } catch (r: IOException) {
                    Log.w("files", "partial copy at $dest not removed: ${r.message}")
                }
                throw e
            }
        } else {
            Files.copy(s, dest, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
        }
        return dest.toString()
    }

    override fun move(src: String, destDir: String): String {
        val s = Path.of(src)
        val dest = Path.of(destDir).resolve(s.fileName.toString())
        require(!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) { "${s.fileName} already exists there" }
        require(!dest.toAbsolutePath().startsWith(s.toAbsolutePath())) { "cannot move a folder into itself" }
        moveEntry(s, dest, allowCrossDeviceDirCopy = false,
            what = "move of a folder across volumes — do it on the PC instead")
        return dest.toString()
    }

    override fun openOnPc(path: String) {
        val env = HashMap(System.getenv())
        if (env["DISPLAY"].isNullOrEmpty()) env["DISPLAY"] = ":0.0"
        val pb = ProcessBuilder("xdg-open", path)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val p = pb.start()
        // fire and detach; a refusal shows up fast — read a moment's output
        Thread({
            try {
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                val code = p.waitFor()
                if (code != 0) Log.e("files", "xdg-open '$path' exit $code: ${out.take(200)}")
            } catch (e: Exception) {
                Log.w("files", "xdg-open watcher: ${e.message}")
            }
        }, "files-xdg-open").apply { isDaemon = true }.start()
    }

    // -------------------------------------------------------------- helpers
    /** Move with the honest cross-device policy: rename when possible; FILES
     *  fall back to copy+delete; DIRECTORIES across devices are refused with
     *  [what] — a whole-tree copy is not a glasses-sized op (known limit). */
    private fun moveEntry(src: Path, dest: Path, allowCrossDeviceDirCopy: Boolean, what: String) {
        try {
            Files.move(src, dest)
            return
        } catch (e: IOException) {
            // cross-device (EXDEV surfaces as IOException here) — or a real failure
            if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
                if (!allowCrossDeviceDirCopy) throw IOException("$what (${e.message})")
                copyTree(src, dest)
                deleteRecursively(src)
                return
            }
            try {
                // NOFOLLOW: a symlink moves AS a link — silently materializing
                // its target's content changed what the object IS (Fi#14)
                Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                Files.delete(src)
            } catch (e2: IOException) {
                try { Files.deleteIfExists(dest) } catch (e3: IOException) {
                    Log.w("files", "half-copied $dest not removed: ${e3.message}")
                }
                throw e2
            }
        }
    }

    private fun copyTree(src: Path, dest: Path) {
        // symlinks inside the tree copy AS links (Fi#14): walkFileTree does
        // not follow them, and the file copy must not either
        Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(dest.resolve(src.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, dest.resolve(src.relativize(file).toString()),
                    StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteRecursively(p: Path) {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)

        /** Through the deadlock-proof runner (review 2026-09-01: the old
         *  stdout-then-stderr read hung on chatty children — pdftoppm on a
         *  damaged PDF fills stderr while streaming the PNG). */
        fun runExec(cmd: List<String>): ExecResult {
            val r = wm.damage.core.util.Exec.run(cmd)
            return ExecResult(r.code, r.stdout, r.stderr)
        }
    }
}
