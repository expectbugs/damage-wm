package wm.damage.core.windows.music

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import wm.damage.core.util.Log

/**
 * The media endpoint (`MUSIC.md` §6.3): `GET /track/<id>?token=&profile=`
 * → 200 / 206 with `Accept-Ranges: bytes` and the profile's MIME. A plain
 * HTTP/1.1 server over `ServerSocket` (the ReplicaServer shape — core runs
 * inside the APK too, so no `com.sun.net.httpserver`), one thread per
 * connection, `Connection: close` after each answer (ExoPlayer's
 * HttpURLConnection source handles that fine).
 *
 * A cache miss transcodes to completion first (seconds, logged), then
 * serves. A MALFORMED Range answers 200 with the whole file: ExoPlayer
 * treats a 416 as fatal (the G2CC lesson). NO TIMEOUTS: a slow reader keeps
 * its connection; the OS reports a dead one.
 */
class MediaServer(
    private val port: Int,
    private val token: String,
    /** The file + MIME for a track under a profile — may transcode; throws
     *  when the track or file is gone (answered as 404 with the reason). */
    private val resolve: (trackId: Int, profile: AudioProfile) -> Pair<Path, String>,
) : AutoCloseable {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    fun start() {
        running = true
        val s = ServerSocket(port)
        server = s
        Thread({
            Log.i("media", "media endpoint on :$port")
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { if (running) Log.e("media", "accept failed", e); break }
                clients.add(c)
                Thread({ try { serve(c) } finally { clients.remove(c) } }, "media-${c.inetAddress}").apply { isDaemon = true }.start()
            }
        }, "media-server").apply { isDaemon = true }.start()
    }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    private fun readRequest(inp: InputStream): Request? {
        val line = readLine(inp) ?: return null
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(inp) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val path = target.substringBefore('?')
        val query = HashMap<String, String>()
        if ('?' in target) for (kv in target.substringAfter('?').split('&')) {
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            if (k.isNotEmpty()) query[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return Request(method, path, query, headers)
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) throw IllegalStateException("request line too long")
        }
        return sb.toString()
    }

    private fun reply(out: OutputStream, code: Int, reason: String, type: String, body: ByteArray, extra: List<String> = emptyList()) {
        val head = StringBuilder("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
        for (e in extra) head.append(e).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    /** `bytes=a-b` / `bytes=a-` / `bytes=-n` → (first, last) inclusive, or
     *  null when absent or malformed (→ 200 whole, never 416). */
    fun parseRange(h: String?, size: Long): Pair<Long, Long>? {
        if (h == null || !h.startsWith("bytes=")) return null
        val spec = h.removePrefix("bytes=").trim()
        if (',' in spec) return null                       // multi-range: whole file
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val a = spec.substring(0, dash).trim()
        val b = spec.substring(dash + 1).trim()
        return try {
            when {
                a.isEmpty() && b.isEmpty() -> null
                a.isEmpty() -> { val n = b.toLong(); if (n <= 0) null else maxOf(0L, size - n) to size - 1 }
                else -> {
                    val first = a.toLong()
                    val last = if (b.isEmpty()) size - 1 else minOf(b.toLong(), size - 1)
                    if (first < 0 || first >= size || last < first) null else first to last
                }
            }
        } catch (e: NumberFormatException) { null }
    }

    private fun serve(sock: Socket) {
        try {
            sock.use {
                val inp = BufferedInputStream(it.getInputStream())
                val out = BufferedOutputStream(it.getOutputStream(), 64 * 1024)
                val req = readRequest(inp) ?: return
                if (req.method != "GET" && req.method != "HEAD") { reply(out, 405, "Method Not Allowed", "text/plain", "GET only".toByteArray()); return }
                if (req.query["token"] != token) {
                    Log.w("media", "refused ${sock.inetAddress}: bad token")
                    reply(out, 403, "Forbidden", "text/plain", "bad token".toByteArray()); return
                }
                val id = Regex("^/track/(\\d+)$").find(req.path)?.groupValues?.get(1)?.toIntOrNull()
                if (id == null) { reply(out, 404, "Not Found", "text/plain", "unknown path".toByteArray()); return }
                val profile = req.query["profile"]?.let { p -> AudioProfile.parse(p) ?: run {
                    reply(out, 400, "Bad Request", "text/plain", "unknown profile '$p'".toByteArray()); return
                } } ?: AudioProfile.DEFAULT
                val (file, mime) = try {
                    resolve(id, profile)
                } catch (e: Exception) {
                    Log.w("media", "track $id (${profile.name}): ${e.message}")
                    reply(out, 404, "Not Found", "text/plain", (e.message ?: "unavailable").toByteArray()); return
                }
                val size = Files.size(file)
                val range = parseRange(req.headers["range"], size)
                val (first, last) = range ?: (0L to size - 1)
                val len = if (size == 0L) 0L else last - first + 1
                val status = if (range != null) "206 Partial Content" else "200 OK"
                val head = StringBuilder("HTTP/1.1 $status\r\nContent-Type: $mime\r\nAccept-Ranges: bytes\r\n" +
                    "Content-Length: $len\r\nConnection: close\r\nCache-Control: no-store\r\n")
                if (range != null) head.append("Content-Range: bytes $first-$last/$size\r\n")
                head.append("\r\n")
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                if (req.method == "GET" && len > 0) {
                    Files.newInputStream(file).use { s ->
                        var skip = first
                        while (skip > 0) { val n = s.skip(skip); if (n <= 0) break; skip -= n }
                        val buf = ByteArray(64 * 1024)
                        var left = len
                        while (left > 0) {
                            val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                }
                out.flush()
            }
        } catch (e: java.io.IOException) {
            // the reader left mid-stream (a seek, a skip): normal for a media client
            Log.d("media", "stream to ${sock.inetAddress} ended: ${e.message}")
        } catch (e: Exception) {
            Log.w("media", "session ${sock.inetAddress} ended: ${e.message}")
        }
    }

    override fun close() {
        running = false
        try { server?.close() } catch (e: Exception) { /* closing */ }
        for (c in clients) try { c.close() } catch (e: Exception) { /* closing */ }
        clients.clear()
    }
}
