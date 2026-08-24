package wm.damage.core.content

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.util.Log
import wm.damage.core.windows.reader.Epub

/**
 * The shell <-> content seam (DESIGN.md §10.1's third role) for the Reader:
 * a library of books served by whichever host has them. Three providers:
 *
 *   LocalContent   — a directory on this machine (laptop-direct, and the PC
 *                    side of the host server)
 *   ContentHostServer — serves LocalContent over TCP (Tailscale) to the phone
 *   RemoteContent  — the phone side: fetches from the PC and CACHES EVERY BOOK
 *                    LOCALLY ON OPEN, so losing the PC mid-read costs nothing
 *                    (Adam's explicit stage-1 requirement); falls back to the
 *                    cache entirely when the host is unreachable
 *
 * Wire: length-prefixed JSON lines + raw byte blocks over TCP, token-gated.
 * Small and dependency-free; WSS can wrap the same framing later for the
 * cloudflared path (§10.6).
 */
@Serializable
data class BookMeta(val id: String, val title: String, val author: String, val bytes: Long, val file: String)

interface ContentProvider {
    /** List the library. Throws on hard failure (callers surface it). */
    fun library(): List<BookMeta>

    /** Return a LOCAL path for the book, fetching/caching if remote. */
    fun openBook(id: String): Path

    /** Human state for the status bar ("" = healthy). */
    fun state(): String

    /** The local copy of [id] could not be READ (a cut or damaged file):
     *  forget it so the next open fetches afresh (round 3 R5). Callers pass
     *  only failures a refetch can fix — a deterministic parse failure is
     *  not one (round 4). No-op where there is no cache. */
    fun invalidate(id: String) {}
}

class LocalContent(private val dir: Path) : ContentProvider {
    override fun library(): List<BookMeta> {
        if (!Files.isDirectory(dir)) return emptyList()
        val out = ArrayList<BookMeta>()
        Files.walk(dir, 3).use { stream ->
            for (p in stream) {
                val n = p.fileName?.toString()?.lowercase() ?: continue
                if (!Files.isRegularFile(p) || (!n.endsWith(".epub") && !n.endsWith(".txt"))) continue
                val (title, author) = Epub.meta(p)
                out.add(BookMeta(idFor(p), title, author, Files.size(p), p.toString()))
            }
        }
        return out.sortedBy { it.title.lowercase() }
    }

    override fun openBook(id: String): Path {
        val m = library().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("book $id not in the library")
        return Path.of(m.file)
    }

    override fun state(): String = ""

    companion object {
        fun idFor(p: Path): String {
            val d = MessageDigest.getInstance("SHA-256")
                .digest(p.toAbsolutePath().toString().toByteArray())
            return d.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}

// ------------------------------------------------------------------ protocol
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable private data class Probe(val t: String = "")

@Serializable private data class Hello(val t: String = "hello", val token: String, val proto: Int = 1)
@Serializable private data class LibraryMsg(val t: String = "library", val books: List<BookMeta>)
@Serializable private data class GetMsg(val t: String = "get", val id: String)
@Serializable private data class BlobMsg(val t: String = "blob", val id: String, val len: Long)
@Serializable private data class ErrMsg(val t: String = "err", val detail: String)

private fun DataOutputStream.sendJson(s: String) {
    val b = s.toByteArray(Charsets.UTF_8)
    writeInt(b.size)
    write(b)
    flush()
}

private fun DataInputStream.readJson(maxLen: Int = 4 shl 20): String {
    val n = readInt()
    require(n in 1..maxLen) { "frame length $n out of range" }
    val b = ByteArray(n)
    readFully(b)
    return b.toString(Charsets.UTF_8)
}

/** Serves the library over TCP. One thread per connection; token required. */
class ContentHostServer(
    private val provider: LocalContent,
    private val port: Int,
    private val token: String,
) : AutoCloseable {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    fun start() {
        running = true
        val s = ServerSocket(port)
        server = s
        Thread({
            Log.i("content-host", "serving library on :$port")
            while (running) {
                val client = try { s.accept() } catch (e: Exception) {
                    if (running) Log.e("content-host", "accept failed", e)
                    break
                }
                clients.add(client)
                Thread({
                    try { serve(client) } finally { clients.remove(client) }
                }, "content-client-${client.inetAddress}").apply { isDaemon = true }.start()
            }
        }, "content-host").apply { isDaemon = true }.start()
    }

    private fun serve(sock: Socket) {
        try {
            sock.use {
                val inp = DataInputStream(it.getInputStream().buffered())
                val out = DataOutputStream(it.getOutputStream().buffered())
                val hello = json.decodeFromString(Hello.serializer(), inp.readJson())
                if (hello.token != token) {
                    out.sendJson(json.encodeToString(ErrMsg.serializer(), ErrMsg(detail = "bad token")))
                    Log.w("content-host", "rejected ${sock.inetAddress}: bad token")
                    return
                }
                if (hello.proto != 1) {
                    out.sendJson(json.encodeToString(ErrMsg.serializer(),
                        ErrMsg(detail = "protocol ${hello.proto} unsupported (host speaks 1)")))
                    return
                }
                while (running) {
                    val line = inp.readJson()
                    // typed dispatch on the t discriminator — substring matching
                    // against raw JSON was review round 1's misroute finding
                    when (val t = json.decodeFromString(Probe.serializer(), line).t) {
                        "library" -> {
                            // a scan failure answers in-band: dropping the session
                            // would show on the phone as "PC gone" (round 3)
                            val books = try {
                                provider.library()
                            } catch (e: Exception) {
                                Log.e("content-host", "library scan failed", e)
                                out.sendJson(json.encodeToString(ErrMsg.serializer(),
                                    ErrMsg(detail = "library scan failed: ${e.message}")))
                                continue
                            }
                            out.sendJson(json.encodeToString(LibraryMsg.serializer(), LibraryMsg(books = books)))
                        }
                        "get" -> {
                            val get = json.decodeFromString(GetMsg.serializer(), line)
                            // resolve, size AND open BEFORE the header goes out: a
                            // stale-listing miss or an unreadable file answers
                            // cleanly in-band and keeps the session (round 4 #5)
                            val (stream, size) = try {
                                val p = provider.openBook(get.id)
                                Files.newInputStream(p) to Files.size(p)
                            } catch (e: Exception) {
                                Log.w("content-host", "get ${get.id} failed: ${e.message}")
                                out.sendJson(json.encodeToString(ErrMsg.serializer(),
                                    ErrMsg(detail = "book ${get.id}: ${e.message}")))
                                continue
                            }
                            stream.use { s ->
                                out.sendJson(json.encodeToString(BlobMsg.serializer(), BlobMsg(id = get.id, len = size)))
                                // stream, never buffer. A MID-STREAM failure cannot
                                // be answered in-band (the byte stream is committed)
                                // — the throw closes the session, which the client
                                // sees as a short read, loudly.
                                s.copyTo(out, 64 * 1024)
                            }
                            out.flush()
                        }
                        else -> {
                            Log.w("content-host", "unknown request t='$t' — closing session")
                            return
                        }
                    }
                }
            }
        } catch (e: java.io.EOFException) {
            // client closed — normal
        } catch (e: Exception) {
            Log.w("content-host", "session ${sock.inetAddress} ended: ${e.message}")
        }
    }

    override fun close() {
        running = false
        server?.close()
        for (c in clients) try { c.close() } catch (e: Exception) { /* closing */ }
        clients.clear()
    }
}

/**
 * The phone/remote side. library() prefers the live host and falls back to the
 * cached listing; openBook() copies the book into [cacheDir] the first time it
 * is opened, then always reads the local copy.
 *
 * Reachability is decided INSIDE [withHost] and nowhere else (round 4): a
 * connect/stream failure marks the host gone, an in-band refusal marks it
 * refusing, a success marks it back — and a failure from an OLDER attempt
 * that lands after a newer success is ignored. Local disk failures are never
 * the host's fault and never touch the banner.
 */
class RemoteContent(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val cacheDir: Path,
    /** Called when host reachability changes, with a SHORT status-bar string
     *  ("" healthy · "PC gone" / "PC 4m" · "PC refused"). */
    private val onState: (String) -> Unit = {},
    /** Called with the human detail behind a state change — the notification
     *  box has room for it, the link cell does not (round 4 #1). */
    private val onNotice: (String) -> Unit = {},
) : ContentProvider {
    @Volatile private var offlineSince: Long = 0
    /** Set when the host is REACHABLE but said no (bad token, wrong proto,
     *  a scan failure on its side) — never the "PC gone" banner (#B7). */
    @Volatile private var refusedState: String? = null
    private val attempts = AtomicLong(0)
    /** The newest attempt that SUCCEEDED: an older attempt failing later must
     *  not re-mark the host offline (round 4 #3). */
    private val lastSuccess = AtomicLong(0)
    private val libCache: Path get() = cacheDir.resolve("library.json")

    /** The host answered with an in-band err frame: reachable, refusing. */
    private class HostRefused(detail: String) : IllegalStateException(detail)

    /** A LOCAL disk failure inside a host exchange — not the host's fault, so
     *  it must not be mistaken for an IOException from the link (round 4 #4). */
    private class CacheWriteFailed(cause: IOException) :
        IllegalStateException("cache write failed: ${cause.message}", cause)

    private inline fun <T> disk(op: () -> T): T = try { op() } catch (e: IOException) { throw CacheWriteFailed(e) }

    private fun <T> withHost(block: (DataInputStream, DataOutputStream) -> T): T {
        val attempt = attempts.incrementAndGet()
        try {
            val r = Socket(host, port).use { sock ->
                val inp = DataInputStream(sock.getInputStream().buffered())
                val out = DataOutputStream(sock.getOutputStream().buffered())
                out.sendJson(json.encodeToString(Hello.serializer(), Hello(token = token)))
                block(inp, out)
            }
            markOnline(attempt)
            return r
        } catch (e: HostRefused) {
            offlineSince = 0
            val banner = "PC refused"
            refusedState = banner
            onState(banner)
            onNotice("PC refused the request: ${e.message}")
            throw e
        } catch (e: IOException) {
            markOffline(attempt)
            throw e
        }
    }

    private fun markOnline(attempt: Long) {
        lastSuccess.updateAndGet { maxOf(it, attempt) }
        if (offlineSince != 0L) Log.i("content", "host $host back")
        offlineSince = 0
        refusedState = null
        onState("")
    }

    private fun markOffline(attempt: Long) {
        if (attempt < lastSuccess.get()) {
            Log.i("content", "stale failure from attempt $attempt ignored — the host has answered since")
            return
        }
        if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
        refusedState = null
        onState(banner())
    }

    private fun banner(): String {
        val mins = (System.currentTimeMillis() - offlineSince) / 60_000
        return "PC ${if (mins < 1) "gone" else "${mins}m"}"
    }

    override fun library(): List<BookMeta> = try {
        val lib = withHost { inp, out ->
            out.sendJson("""{"t":"library"}""")
            val line = inp.readJson()
            if (json.decodeFromString(Probe.serializer(), line).t == "err") {
                val err = json.decodeFromString(ErrMsg.serializer(), line)
                throw HostRefused(err.detail)
            }
            json.decodeFromString(LibraryMsg.serializer(), line)
        }
        // the cache write happens OUTSIDE the exchange: a full phone must not
        // read as "PC gone", and the fresh listing is used regardless
        try {
            writeLibraryCache(lib)
        } catch (e: IOException) {
            Log.e("content", "library cache write failed (${e.message}) — listing kept in memory only")
        }
        lib.books
    } catch (e: Exception) {
        // NO SILENT FAILURES: the fallback is deliberate, but it must be LOUD —
        // a wrong token looked identical to "PC off" in review round 1
        Log.e("content", "host library failed (${e.message}) — using the cache")
        val cached = cachedLibrary()
        if (cached.isEmpty()) throw IllegalStateException(
            if (e is HostRefused) "library unavailable: host refused (${e.message}) and no cache"
            else "library unavailable: host unreachable (${e.message}) and no cache")
        cached
    }

    private fun writeLibraryCache(lib: LibraryMsg) {
        Files.createDirectories(cacheDir)
        // unique tmp (#B6): two concurrent refreshes must not interleave
        // writes into one file and then move a torn listing into place
        val tmp = libCache.resolveSibling("library.json.${System.nanoTime()}.tmp")
        try {
            Files.writeString(tmp, json.encodeToString(LibraryMsg.serializer(), lib))
            Files.move(tmp, libCache, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Offline view: the cached listing. */
    fun cachedLibrary(): List<BookMeta> = try {
        if (Files.exists(libCache)) {
            json.decodeFromString(LibraryMsg.serializer(), Files.readString(libCache)).books
        } else emptyList()
    } catch (e: Exception) {
        Log.e("content", "cached library unreadable", e)
        emptyList()
    }

    fun isCached(id: String): Boolean = cachedExisting(id) != null

    /** The cache keeps the REAL extension (#B3): the reader dispatches epub vs
     *  txt on it, and a `.book` suffix would send zip bytes down the text path. */
    private fun cachePath(id: String, ext: String): Path =
        cacheDir.resolve("books").resolve("$id.$ext")

    private fun cachedExisting(id: String): Path? =
        listOf("epub", "txt").map { cachePath(id, it) }.firstOrNull { Files.exists(it) }

    override fun invalidate(id: String) {
        for (ext in listOf("epub", "txt")) {
            if (Files.deleteIfExists(cachePath(id, ext)))
                Log.w("content", "dropped unreadable cached copy $id.$ext — next open refetches")
        }
    }

    /** The extension the PC serves the book under, from the cached listing —
     *  authoritative, unlike a byte sniff (round 4 #2: a text file beginning
     *  "PK" would otherwise be cached as an epub forever). */
    private fun extFromListing(id: String): String? =
        cachedLibrary().firstOrNull { it.id == id }?.file
            ?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it == "epub" || it == "txt" }

    override fun openBook(id: String): Path = cachedExisting(id) ?: fetchBook(id)

    // copy-on-open: fetch the whole book to LOCAL STORAGE first, so a PC drop
    // mid-read never disrupts (Adam, stage-1 requirement). Streamed in chunks
    // — a big book must never build a heap-sized array (OOM is an Error and
    // would skip every catch on the reader path).
    private fun fetchBook(id: String): Path = withHost { inp, out ->
        out.sendJson(json.encodeToString(GetMsg.serializer(), GetMsg(id = id)))
        val line = inp.readJson()
        if (json.decodeFromString(Probe.serializer(), line).t == "err") {
            val err = json.decodeFromString(ErrMsg.serializer(), line)
            throw IllegalStateException(err.detail)      // per-book, not a host state
        }
        val blob = json.decodeFromString(BlobMsg.serializer(), line)
        require(blob.len in 1..(64L shl 20)) { "book size ${blob.len} out of sane range" }
        val booksDir = cacheDir.resolve("books")
        disk { Files.createDirectories(booksDir) }
        // unique tmp: concurrent opens of the same id must not interleave
        // into one file and poison the cache permanently
        val tmp = booksDir.resolve("$id.${System.nanoTime()}.tmp")
        try {
            disk { Files.newOutputStream(tmp) }.use { o ->
                val buf = ByteArray(64 * 1024)
                var left = blob.len
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.EOFException("book stream ended ${left} B early")
                    disk { o.write(buf, 0, n) }
                    left -= n
                }
            }
            if (Files.size(tmp) != blob.len)
                throw IllegalStateException("cached ${Files.size(tmp)} B, expected ${blob.len}")
            val ext = extFromListing(id) ?: sniffExt(tmp)
            val dest = cachePath(id, ext)
            disk { Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            Log.i("content", "cached book $id (${blob.len} B, .$ext)")
            dest
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Fallback when the listing does not know the book: epubs are zip
     *  containers ("PK"); everything else this library serves is text. A
     *  plain 2-byte read — readNBytes(int) is API 33, the APK's floor is 31. */
    private fun sniffExt(p: Path): String {
        val head = ByteArray(2)
        var got = 0
        Files.newInputStream(p).use { s ->
            while (got < 2) {
                val n = s.read(head, got, 2 - got)
                if (n < 0) break
                got += n
            }
        }
        return if (got == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) "epub" else "txt"
    }

    override fun state(): String {
        refusedState?.let { return it }
        return if (offlineSince == 0L) "" else banner()
    }
}
