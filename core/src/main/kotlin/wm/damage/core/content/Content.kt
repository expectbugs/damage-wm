package wm.damage.core.content

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
                        "library" ->
                            out.sendJson(json.encodeToString(LibraryMsg.serializer(), LibraryMsg(books = provider.library())))
                        "get" -> {
                            val get = json.decodeFromString(GetMsg.serializer(), line)
                            // resolve BEFORE the header goes out: a stale-listing
                            // miss answers cleanly and keeps the session
                            val path = try {
                                provider.openBook(get.id)
                            } catch (e: Exception) {
                                Log.w("content-host", "get ${get.id} failed: ${e.message}")
                                out.sendJson(json.encodeToString(ErrMsg.serializer(),
                                    ErrMsg(detail = "book ${get.id}: ${e.message}")))
                                continue
                            }
                            val size = Files.size(path)
                            out.sendJson(json.encodeToString(BlobMsg.serializer(), BlobMsg(id = get.id, len = size)))
                            // stream, never buffer. A MID-STREAM failure cannot be
                            // answered in-band (the byte stream is committed) —
                            // the throw closes the session, which the client sees
                            // as a short read, loudly.
                            Files.newInputStream(path).use { it.copyTo(out, 64 * 1024) }
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
 */
class RemoteContent(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val cacheDir: Path,
    /** Called when host reachability changes, with a status-bar string. */
    private val onState: (String) -> Unit = {},
) : ContentProvider {
    @Volatile private var offlineSince: Long = 0
    private val libCache: Path get() = cacheDir.resolve("library.json")

    private fun <T> withHost(block: (DataInputStream, DataOutputStream) -> T): T {
        val sock = Socket(host, port)
        return sock.use {
            val inp = DataInputStream(it.getInputStream().buffered())
            val out = DataOutputStream(it.getOutputStream().buffered())
            out.sendJson(json.encodeToString(Hello.serializer(), Hello(token = token)))
            val r = block(inp, out)
            markOnline()
            r
        }
    }

    private fun markOnline() {
        if (offlineSince != 0L) Log.i("content", "host $host back")
        offlineSince = 0
        onState("")
    }

    private fun markOffline() {
        if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
        val mins = (System.currentTimeMillis() - offlineSince) / 60_000
        onState("PC ${if (mins < 1) "gone" else "${mins}m"}")
    }

    override fun library(): List<BookMeta> = try {
        withHost { inp, out ->
            out.sendJson("""{"t":"library"}""")
            val line = inp.readJson()
            if (json.decodeFromString(Probe.serializer(), line).t == "err") {
                val err = json.decodeFromString(ErrMsg.serializer(), line)
                throw IllegalStateException("host refused: ${err.detail}")
            }
            val lib = json.decodeFromString(LibraryMsg.serializer(), line)
            Files.createDirectories(cacheDir)
            val tmp = libCache.resolveSibling("library.json.tmp")
            Files.writeString(tmp, json.encodeToString(LibraryMsg.serializer(), lib))
            Files.move(tmp, libCache, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            lib.books
        }
    } catch (e: Exception) {
        // NO SILENT FAILURES: the fallback is deliberate, but it must be LOUD —
        // a wrong token looked identical to "PC off" in review round 1
        Log.e("content", "host library failed (${e.message}) — using the cache")
        markOffline()
        val cached = cachedLibrary()
        if (cached.isEmpty()) throw IllegalStateException(
            "library unavailable: host unreachable (${e.message}) and no cache")
        cached
    }

    /** Offline view: the cached listing, with books we hold marked available. */
    fun cachedLibrary(): List<BookMeta> = try {
        if (Files.exists(libCache)) {
            json.decodeFromString(LibraryMsg.serializer(), Files.readString(libCache)).books
        } else emptyList()
    } catch (e: Exception) {
        Log.e("content", "cached library unreadable", e)
        emptyList()
    }

    fun isCached(id: String): Boolean = Files.exists(cachePath(id))

    private fun cachePath(id: String): Path = cacheDir.resolve("books").resolve("$id.book")

    override fun openBook(id: String): Path {
        val cached = cachePath(id)
        if (Files.exists(cached)) return cached
        // copy-on-open: fetch the whole book to LOCAL STORAGE first, so a PC
        // drop mid-read never disrupts (Adam, stage-1 requirement). Streamed in
        // chunks — a big book must never build a heap-sized array (OOM is an
        // Error and would skip every catch on the reader path).
        return withHost { inp, out ->
            out.sendJson(json.encodeToString(GetMsg.serializer(), GetMsg(id = id)))
            val line = inp.readJson()
            if (json.decodeFromString(Probe.serializer(), line).t == "err") {
                val err = json.decodeFromString(ErrMsg.serializer(), line)
                throw IllegalStateException(err.detail)
            }
            val blob = json.decodeFromString(BlobMsg.serializer(), line)
            require(blob.len in 1..(64L shl 20)) { "book size ${blob.len} out of sane range" }
            Files.createDirectories(cached.parent)
            // unique tmp: concurrent opens of the same id must not interleave
            // into one file and poison the cache permanently
            val tmp = cached.resolveSibling("$id.${System.nanoTime()}.tmp")
            try {
                Files.newOutputStream(tmp).use { o ->
                    val buf = ByteArray(64 * 1024)
                    var left = blob.len
                    while (left > 0) {
                        val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw java.io.EOFException("book stream ended ${left} B early")
                        o.write(buf, 0, n)
                        left -= n
                    }
                }
                if (Files.size(tmp) != blob.len)
                    throw IllegalStateException("cached ${Files.size(tmp)} B, expected ${blob.len}")
                Files.move(tmp, cached, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
            Log.i("content", "cached book $id (${blob.len} B)")
            cached
        }
    }

    override fun state(): String =
        if (offlineSince == 0L) ""
        else "PC ${((System.currentTimeMillis() - offlineSince) / 60_000).coerceAtLeast(0)}m"
}
