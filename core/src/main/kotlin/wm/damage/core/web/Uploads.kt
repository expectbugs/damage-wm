package wm.damage.core.web

import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.4 "Uploads", §5 V4; `CONTRACT.md` §9.4): `PUT /blob/upload/<id>` on the web
 * interface listener. The id is single use, minted by a window's `upload` program op for one device and one of its
 * connections ([mint]). The body goes to `.<name>.damage-upload-<id>.part` in the destination folder and only a whole
 * body is moved to its name, without ever replacing a file there:
 *  - `Content-Length` is required, and must be the size the op named;
 *  - the destination's usable space is read once, before a byte of the body (507 with the reason);
 *  - an existing name is refused (409 with the reason) — before the body, and again at the move, which links the
 *    temp file to its name (refused by the filesystem when the name exists) and only then removes the temp file;
 *  - a cut-off body, a write error or a full disk deletes the temp file and answers with the reason.
 * The uploads in flight are listed in `<data>/uploads.jsonl`, so a start deletes a temp file a stopped process left
 * ([atStart], said). A ticket whose connection ends before its body begins is cancelled ([connectionEnded]); a body
 * that stalls holds its socket until TCP keepalive ends it or the page aborts it (no timeout).
 */
class Uploads(
    /** The data directory (`uploads.jsonl`); null = in memory only (a test). */
    private val dataDir: Path?,
    /** The usable bytes of the store holding a folder, read once per upload. */
    private val usable: (Path) -> Long = { Files.getFileStore(it).usableSpace },
    /** Gives a whole temp file its name without replacing one there (the temp name may stay: the caller removes
     *  it); throws [FileAlreadyExistsException] when the name exists. */
    private val place: (Path, Path) -> Unit = ::placeNoReplace,
) {
    /** How an upload ended, told to the op's owner (Files frees its job slot). */
    sealed class End {
        data class Done(val path: Path, val bytes: Long) : End()
        data class Failed(val reason: String) : End()
    }

    /** One minted upload: who may send it, where it goes, how long it is. */
    class Ticket internal constructor(
        val id: String,
        val device: String,
        val conn: Long,
        val folder: Path,
        val name: String,
        val size: Long,
        internal val onEnd: (End) -> Unit,
    )

    /** An answer to the uploader: its status and its words; [bodyLeft] = refused before its body was read (the
     *  listener drains what the uploader still sends, so the answer reaches it rather than a reset). */
    data class Reply(val code: Int, val text: String, val bodyLeft: Boolean = false)

    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val inFlight = ConcurrentHashMap<String, Path>()
    private val rnd = SecureRandom()
    private val listLock = Any()

    /**
     * A new upload of [size] bytes named [name] into [folder] for [device]'s connection [conn]; its id. Throws
     * [IllegalArgumentException] with the reason (a name with a path in it, a folder that is not one, a size below 0).
     * [onEnd] is told once: the file placed, or why not.
     */
    fun mint(device: String, conn: Long, folder: Path, name: String, size: Long, onEnd: (End) -> Unit): String {
        require(validName(name)) { "'$name' is not a file name (no '/', no NUL, not . or .., at most 255 bytes)" }
        require(size >= 0) { "the upload's size is below 0" }
        require(folder.isAbsolute && Files.isDirectory(folder)) { "$folder is not a folder on the PC" }
        val id = ByteArray(12).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        tickets[id] = Ticket(id, device, conn, folder, name, size, onEnd)
        Log.i(TAG, "upload $id minted for $device: $name ($size bytes) into $folder")
        return id
    }

    /** [conn] ended: each ticket it minted whose body has not begun is cancelled (its owner told). */
    fun connectionEnded(conn: Long) {
        for (t in tickets.values.filter { it.conn == conn }) {
            if (tickets.remove(t.id, t)) end(t, End.Failed("the page's connection ended before the upload began"))
        }
    }

    /**
     * Receives upload [id]'s body from [inp] for [device]: [length] is the request's `Content-Length` (null when it
     * has none). Blocks while the body arrives. Every refusal and failure is answered with its reason and said.
     */
    fun receive(id: String, device: String, length: Long?, inp: InputStream): Reply {
        val t = tickets[id] ?: return Reply(404, "no upload '$id' is waiting (each is used once)", bodyLeft = true)
        if (t.device != device) return Reply(403, "upload '$id' belongs to another device", bodyLeft = true)
        // single use from here: a second PUT finds nothing
        if (!tickets.remove(id, t)) return Reply(404, "no upload '$id' is waiting (each is used once)", bodyLeft = true)
        var read = false
        fun failed(code: Int, why: String): Reply {
            Log.w(TAG, "upload $id (${t.name}) not placed: $why")
            end(t, End.Failed(why))
            return Reply(code, why, bodyLeft = !read)
        }
        if (length == null) return failed(411, "an upload needs its Content-Length")
        if (length != t.size) return failed(400, "the body is $length bytes; the upload was asked for ${t.size}")
        val dest = t.folder.resolve(t.name)
        if (Files.exists(dest)) return failed(409, "${t.name} already exists in ${t.folder}; nothing was replaced")
        // the space, read once, before a byte of the body
        val free = try { usable(t.folder) } catch (e: IOException) {
            return failed(500, "the free space of ${t.folder} could not be read (${e::class.simpleName}: ${e.message})")
        }
        if (free < length) return failed(507, "not enough space in ${t.folder}: ${t.name} is $length bytes and $free are free")
        val part = t.folder.resolve(".${t.name}.damage-upload-$id.part")
        if (!listed(id, part)) return failed(500, "${LIST_FILE} could not be written; the upload was not begun")
        read = true
        try {
            var got = 0L
            Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                val buf = ByteArray(BUFFER)
                while (got < length) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), length - got).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                }
            }
            if (got != length) {
                drop(id, part)
                return failed(400, "the body ended after $got of $length bytes; nothing was kept")
            }
            Files.newByteChannel(part, StandardOpenOption.WRITE).use { (it as java.nio.channels.FileChannel).force(true) }
            try {
                place(part, dest)
            } catch (e: FileAlreadyExistsException) {
                drop(id, part)
                return failed(409, "${t.name} appeared in ${t.folder} during the upload; nothing was replaced")
            }
            drop(id, part)                     // the link's temp name (a rename left none)
            Log.i(TAG, "upload $id placed: $dest ($length bytes)")
            end(t, End.Done(dest, length))
            return Reply(201, buildJsonObject { put("name", t.name); put("bytes", length) }.toString())
        } catch (e: IOException) {
            drop(id, part)
            val full = e.message?.contains("No space left", ignoreCase = true) == true
            return failed(if (full) 507 else 500, "the upload could not be written (${e::class.simpleName}: ${e.message}); nothing was kept")
        }
    }

    /**
     * At a process start: every temp file `uploads.jsonl` lists is deleted (only a name of the temp form), each said
     * through [say]; the list is then emptied. A list that cannot be read is said and left for the next start.
     */
    fun atStart(say: (String) -> Unit) {
        val f = dataDir?.resolve(LIST_FILE) ?: return
        if (!Files.exists(f)) return
        val lines = try { Files.readAllLines(f) } catch (e: IOException) {
            say("$LIST_FILE could not be read (${e::class.simpleName}: ${e.message}); its temp files stay until a start reads it")
            return
        }
        for (l in lines.filter { it.isNotBlank() }) {
            val o = try { Json.parseToJsonElement(l) as? JsonObject } catch (e: Exception) { null }
            val id = (o?.get("id") as? JsonPrimitive)?.contentOrNull
            val part = (o?.get("part") as? JsonPrimitive)?.contentOrNull?.let { Path.of(it) }
            if (id == null || part == null || !part.fileName.toString().endsWith(".damage-upload-$id.part")) {
                say("$LIST_FILE holds a line that names no upload's temp file; left as it is: ${l.take(200)}")
                continue
            }
            try {
                if (Files.deleteIfExists(part)) say("an upload cut off by the last stop was deleted: $part")
            } catch (e: IOException) {
                say("an upload's temp file could not be deleted ($part: ${e::class.simpleName}: ${e.message})")
            }
        }
        if (!writeList(emptyMap())) say("$LIST_FILE could not be emptied; the next start reads it again")
    }

    private fun end(t: Ticket, e: End) {
        try {
            t.onEnd(e)
        } catch (x: Exception) {
            Log.e(TAG, "the owner of upload ${t.id} failed on its end", x)
        }
    }

    private fun drop(id: String, part: Path) {
        try {
            Files.deleteIfExists(part)
        } catch (e: IOException) {
            Log.e(TAG, "the temp file of upload $id could not be deleted ($part); the next start deletes it", e)
            return
        }
        unlist(id)
    }

    private fun listed(id: String, part: Path): Boolean = synchronized(listLock) {
        inFlight[id] = part
        writeList(inFlight).also { if (!it) inFlight.remove(id) }
    }

    private fun unlist(id: String) = synchronized(listLock) {
        inFlight.remove(id)
        if (!writeList(inFlight)) Log.e(TAG, "$LIST_FILE could not be written after upload $id ended; a start reads it again")
    }

    /** The whole list, written to a temp file and moved over the old one. */
    private fun writeList(m: Map<String, Path>): Boolean {
        val f = dataDir?.resolve(LIST_FILE) ?: return true
        return try {
            val tmp = f.resolveSibling("$LIST_FILE.tmp")
            Files.writeString(tmp, m.entries.joinToString("") { (id, p) ->
                buildJsonObject { put("id", id); put("part", p.toString()) }.toString() + "\n"
            })
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            Log.e(TAG, "$LIST_FILE could not be written (${e::class.simpleName}: ${e.message})")
            false
        }
    }

    companion object {
        private const val TAG = "uploads"
        const val LIST_FILE = "uploads.jsonl"
        private const val BUFFER = 64 * 1024

        fun validName(n: String): Boolean =
            n.isNotEmpty() && n != "." && n != ".." && '/' !in n && '\u0000' !in n && n.toByteArray(Charsets.UTF_8).size <= 255

        /**
         * Gives [part]'s bytes the name [dest] only if no file has that name: a hard link (the filesystem refuses an
         * existing name, `FileAlreadyExistsException`); the temp name stays for the caller to remove. On a filesystem
         * without hard links: the name checked, then an atomic rename (a name made between the two would be replaced;
         * one user, his folders). A plain atomic move never serves: on Linux it replaces an existing file silently.
         */
        fun placeNoReplace(part: Path, dest: Path) {
            try {
                Files.createLink(dest, part)
            } catch (e: FileAlreadyExistsException) {
                throw e
            } catch (e: UnsupportedOperationException) {
                renameIfFree(part, dest)
            } catch (e: IOException) {
                if (Files.exists(dest)) throw FileAlreadyExistsException(dest.toString())
                renameIfFree(part, dest)
            }
        }

        private fun renameIfFree(part: Path, dest: Path) {
            if (Files.exists(dest)) throw FileAlreadyExistsException(dest.toString())
            Files.move(part, dest, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
