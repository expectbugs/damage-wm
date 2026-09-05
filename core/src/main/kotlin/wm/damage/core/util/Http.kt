package wm.damage.core.util

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * A small HTTP helper over `java.net.HttpURLConnection` — the one client
 * both hosts have (core runs inside the APK too; `java.net.http` does not).
 * NO TIMEOUTS by law (`CLAUDE.md`): a stalled peer is reported by the
 * caller's liveness surface, never abandoned here. Redirects are NOT
 * followed unless asked — a tracker's "you are logged out" redirect is a
 * fact the adapter wants to see.
 */
object Http {

    class Response(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
        fun text(): String = body.toString(Charsets.UTF_8)
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key != null && it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        val contentType: String get() = header("Content-Type") ?: ""
        fun setCookies(): List<String> =
            headers.entries.firstOrNull { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }?.value
                ?: emptyList()
    }

    fun request(
        method: String, url: String, headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null, contentType: String? = null, followRedirects: Boolean = false,
    ): Response {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = followRedirects
        conn.useCaches = false
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        if (body != null) {
            conn.doOutput = true
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val bytes = try {
            stream?.use { it.readBytes() } ?: ByteArray(0)
        } catch (e: java.io.IOException) {
            // a body that ends early is a transport failure, never an empty
            // answer a parser would then call "format changed" (review
            // 2026-09-01 C3)
            conn.disconnect()
            throw java.io.IOException("$method $url: body read failed after HTTP $status: ${e.message}", e)
        }
        val hdrs = HashMap<String, List<String>>()
        for ((k, v) in conn.headerFields) if (k != null) hdrs[k] = v
        // NOT disconnect()ed on the success path (2026-09-05, §32): a fully
        // read body returns the socket to the JDK's keep-alive pool, so the
        // next request to the same host reuses it — for TorrentLeech that is
        // a TLS handshake per page saved; qBittorrent over loopback does not
        // care either way. disconnect() stays on the failure path above, where
        // the connection's state is unknown. A stale pooled connection is
        // retried by the JDK for GET only; a POST here streams its body with a
        // fixed length, which the JDK never retries — no request can double.
        return Response(status, hdrs, bytes)
    }

    fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

    /** Path-segment encoding (spaces as %20, never '+'). */
    fun pathEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** A multipart/form-data body: text fields + one file part. Returns the
     *  content type (with its boundary) and the bytes. */
    fun multipart(
        fields: Map<String, String>, fileField: String, fileName: String,
        fileBytes: ByteArray, fileType: String,
    ): Pair<String, ByteArray> {
        val boundary = "----DamageWM" + java.lang.Long.toHexString(System.nanoTime())
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        val safeName = fileName.replace("\"", "_").replace("\r", "").replace("\n", "")
        w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$safeName\"\r\n" +
            "Content-Type: $fileType\r\n\r\n")
        out.write(fileBytes)
        w("\r\n--$boundary--\r\n")
        return "multipart/form-data; boundary=$boundary" to out.toByteArray()
    }
}
