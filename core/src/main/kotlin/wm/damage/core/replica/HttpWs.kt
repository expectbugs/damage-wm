package wm.damage.core.replica

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * The dependency-free HTTP/1.1 request reader and RFC 6455 frame codec the browser replica has used since §8.2, moved
 * here unchanged from `ReplicaServer.kt` (build S1-1, package P6) so the PC's web interface listener (`core/web/`)
 * shares them. One request per connection (`Connection: close`); fragmented client messages are refused.
 */

/** One request line and its headers (names lower-cased). */
class HttpRequest(val method: String, val target: String, val headers: Map<String, String>) {
    operator fun component1() = method
    operator fun component2() = target
    val path: String get() = target.substringBefore('?')
}

/** One client frame, unmasked. */
class WsFrame(val opcode: Int, val payload: ByteArray)

object HttpWs {
    const val OP_TEXT = 1
    const val OP_BINARY = 2
    const val OP_CLOSE = 8
    const val OP_PING = 9
    const val OP_PONG = 10
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** The request line and headers; null at EOF before the blank line. */
    fun readRequest(inp: DataInputStream): HttpRequest? {
        val first = readLine(inp) ?: return null
        val parts = first.split(' ')
        // fix round 2, ruling 2.4: its length, never its text (the replica's target carries the config token, and this
        // message reaches the log tail the diagnosis serves)
        if (parts.size < 2) throw IOException("bad request line (${first.length} characters, no space)")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(inp) ?: return null
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > MAX_HEADERS) throw IOException("more than $MAX_HEADERS header lines")
        }
        return HttpRequest(parts[0], parts[1], headers)
    }

    /** One ISO-8859-1 header line (CRLF-terminated); null at EOF. */
    fun readLine(inp: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("header line too long")
        }
    }

    fun queryOf(target: String): Map<String, String> {
        val q = target.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i < 0) null else URLDecoder.decode(kv.substring(0, i), "UTF-8") to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()
    }

    fun reply(out: OutputStream, code: Int, type: String, body: String, extra: List<Pair<String, String>> = emptyList()) =
        replyBytes(out, code, type, body.toByteArray(Charsets.UTF_8), extra)

    /** A response head; [extra] headers follow the standard ones in order. */
    fun head(out: OutputStream, code: Int, type: String, length: Long, extra: List<Pair<String, String>> = emptyList()) {
        val sb = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $type\r\nContent-Length: $length\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun replyBytes(out: OutputStream, code: Int, type: String, b: ByteArray, extra: List<Pair<String, String>> = emptyList()) {
        head(out, code, type, b.size.toLong(), extra)
        out.write(b)
        out.flush()
    }

    fun reason(code: Int): String = when (code) {
        200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 413 -> "Content Too Large"; 416 -> "Range Not Satisfiable"; else -> "Error"
    }

    /** The upgrade's answer (RFC 6455 §4.2.2). */
    fun switching(out: OutputStream, key: String) {
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** Read one client frame (masked per RFC 6455); null at EOF. Fragments (FIN=0 or continuation) are refused
     *  loudly, as is a frame past [max] bytes. */
    fun readFrame(inp: DataInputStream, max: Long): WsFrame? {
        val b0 = inp.read(); if (b0 < 0) return null
        val b1 = inp.read(); if (b1 < 0) return null
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0F
        if (!fin || opcode == 0) throw IOException("fragmented websocket message (unsupported)")
        val masked = b1 and 0x80 != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = inp.readUnsignedShort().toLong()
        else if (len == 127L) len = inp.readLong()
        if (len < 0 || len > max) throw IOException("client frame of $len B refused")
        if (!masked) throw IOException("unmasked client frame (RFC 6455 requires a mask)")
        val mask = ByteArray(4); inp.readFully(mask)
        val payload = ByteArray(len.toInt()); inp.readFully(payload)
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return WsFrame(opcode, payload)
    }

    /** RFC 6455 §4.2.2: base64(SHA-1(key + GUID)). */
    fun acceptKey(key: String): String {
        val sha = MessageDigest.getInstance("SHA-1").digest((key.trim() + WS_GUID).toByteArray(Charsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(sha)
    }

    /** A server→client frame (unmasked), FIN set. */
    fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val n = payload.size
        val header = when {
            n < 126 -> byteArrayOf((0x80 or opcode).toByte(), n.toByte())
            n < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
            else -> ByteArray(10).also {
                it[0] = (0x80 or opcode).toByte(); it[1] = 127.toByte()
                for (i in 0 until 8) it[2 + i] = (n.toLong() shr (8 * (7 - i))).toByte()
            }
        }
        return header + payload
    }

    private const val MAX_HEADERS = 100
}
