package wm.damage.desktop

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import wm.damage.core.util.Log

/**
 * The setup page (2026-09-10, `HANDOFF.md` §44): `GET /setup` and
 * `GET /damage-apk` on the port and under the token G2CC's server served
 * them on until it was retired — the URL Adam's phone has and the token
 * its link carries stay exactly the same. The gate is the one G2CC applied:
 * the page and the APK answer only on the Tailscale interface or loopback
 * (a LAN or other arrival gets a loud 403), and the APK also wants the
 * token (`?token=` as the page's link carries it, or `Authorization:
 * Bearer`). The download filename carries the staged file's mtime stamp so
 * a stale copy in the phone's Downloads can never pass for the fresh one.
 * The G2CC boxes (its QR codes, its own APK, the PC page, the identity
 * review, the endpoint list) are gone; the DamageWM box is the page.
 *
 * Plain HTTP/1.1 over ServerSocket, one thread per connection (the
 * MediaServer shape); `HEAD` answers the headers alone. NO TIMEOUTS.
 */
class SetupServer(
    private val port: Int,
    private val token: String,
    private val apk: Path,
    /** May the page answer on this server-side address? Injected for the tests. */
    private val interfaceAllowed: (InetAddress) -> Boolean = ::tailscaleOrLoopback,
) : AutoCloseable {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    /** The port actually bound (a test binds 0). */
    val boundPort: Int get() = server?.localPort ?: port

    fun start() {
        require(token.isNotEmpty()) { "the setup page needs a token" }
        running = true
        val s = ServerSocket(port)
        server = s
        Thread({
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { if (running) Log.e(TAG, "accept failed", e); break }
                Thread({ serve(c) }, "setup-${c.inetAddress}").apply { isDaemon = true }.start()
            }
        }, "setup-server").apply { isDaemon = true }.start()
        val ts = tailscaleAddress()
        Log.i(TAG, "setup page on :${s.localPort} — " +
            (if (ts != null) "http://${ts.hostAddress}:${s.localPort}/setup" else "no Tailscale interface up: loopback only for now") +
            " (Tailscale + loopback only; the APK link carries the token)")
    }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    private fun serve(sock: Socket) {
        try {
            sock.use { c ->
                val inp = BufferedInputStream(c.getInputStream())
                val out = BufferedOutputStream(c.getOutputStream())
                val req = readRequest(inp) ?: return
                val client = c.inetAddress.hostAddress
                val local = c.localAddress
                val head = req.method == "HEAD"
                if (req.method != "GET" && !head) { reply(out, 405, "Method Not Allowed", "application/json", "{\"error\":\"GET only\"}".toByteArray(), head); return }
                when (req.path) {
                    "/setup", "/setup/" -> {
                        if (!interfaceAllowed(local)) { denied(out, "/setup", local, client, head); return }
                        reply(out, 200, "OK", "text/html; charset=utf-8", page().toByteArray(), head)
                    }
                    "/damage-apk" -> {
                        if (!interfaceAllowed(local)) { denied(out, "/damage-apk", local, client, head); return }
                        val bearer = req.headers["authorization"]
                        val ok = (req.query["token"]?.let { same(it, token) } ?: false) ||
                            (bearer?.let { same(it, "Bearer $token") } ?: false)
                        if (!ok) {
                            Log.w(TAG, "/damage-apk refused: bad or missing token from $client")
                            reply(out, 401, "Unauthorized", "application/json", "{\"error\":\"unauthorized\"}".toByteArray(), head)
                            return
                        }
                        if (!Files.isRegularFile(apk)) {
                            Log.w(TAG, "/damage-apk asked by $client but nothing is staged at $apk")
                            reply(out, 404, "Not Found", "application/json",
                                "{\"error\":\"DamageWM APK not staged (in damagewm: ./gradlew :phone:stageApk)\"}".toByteArray(), head)
                            return
                        }
                        val bytes = Files.readAllBytes(apk)
                        val name = "damage-wm-${stamp()}.apk"
                        Log.i(TAG, "/damage-apk served: ${bytes.size} B as $name to $client${if (head) " (HEAD)" else ""}")
                        reply(out, 200, "OK", "application/vnd.android.package-archive", bytes, head,
                            listOf("Content-Disposition: attachment; filename=\"$name\""))
                    }
                    else -> reply(out, 404, "Not Found", "application/json", "{\"error\":\"not found\"}".toByteArray(), head)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "connection ${sock.inetAddress}: ${e.message}")
        }
    }

    private fun denied(out: OutputStream, route: String, local: InetAddress, client: String, head: Boolean) {
        Log.e(TAG, "$route DENIED: arrived on ${local.hostAddress} from $client — Tailscale/loopback only")
        reply(out, 403, "Forbidden", "application/json",
            "{\"error\":\"${route.removePrefix("/")} is served on the Tailscale interface only\"}".toByteArray(), head)
    }

    /** The staged build's stamp, local time — the download's filename. */
    fun stamp(): String = STAMP.format(Files.getLastModifiedTime(apk).toInstant())

    /** The human line under the link: which staged build the link serves. */
    fun stagedInfo(): String {
        if (!Files.isRegularFile(apk)) return "NOT STAGED — in damagewm: ./gradlew :phone:stageApk"
        return try {
            val mb = "%.1f".format(Files.size(apk) / (1024.0 * 1024.0))
            "staged ${stamp()} · $mb MB — saves as damage-wm-${stamp()}.apk"
        } catch (e: Exception) { "stat failed: ${e.message}" }
    }

    fun page(): String {
        val t = URLEncoder.encode(token, "UTF-8")
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DamageWM Setup</title>
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, system-ui, sans-serif; max-width: 480px; margin: 20px auto; padding: 16px; color: #222; }
  h1 { font-size: 1.6em; margin-bottom: 0; }
  .subtitle { color: #666; margin-top: 4px; }
  .box { background: #f5f5f5; padding: 12px; border-radius: 8px; margin: 12px 0; }
  code { background: #fff; padding: 2px 6px; border-radius: 4px; font-size: 0.9em; }
  ol { line-height: 1.6; }
  .warn { color: #b00; font-size: 0.9em; }
</style>
</head>
<body>
  <h1>DamageWM Setup</h1>
  <p class="subtitle">Served by the beardos <code>damage</code> service on the Tailscale interface only.</p>

  <div class="box">
    <strong>DamageWM APK</strong><br>
    <a href="/damage-apk?token=$t">&#x2B07; Download the staged APK</a>
    <p class="subtitle">&#x1F4E6; ${escape(stagedInfo())}</p>
    <p class="subtitle">Damage — the framebuffer window manager for the CFW pair. Sideload it, grant the Bluetooth + notification + battery-exemption prompts, and flip Target → glasses from the strip. Staged from ~/.damage/damage-wm.apk (damagewm: ./gradlew :phone:stageApk); the download filename carries the build stamp — if the install fails, check the tapped file's stamp matches the line above (old copies in Downloads are stale builds).</p>
  </div>
</body>
</html>
"""
    }

    private fun readRequest(inp: InputStream): Request? {
        val line = readLine(inp) ?: return null
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(inp) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val target = parts[1]
        val query = HashMap<String, String>()
        if ('?' in target) for (kv in target.substringAfter('?').split('&')) {
            val k = kv.substringBefore('=')
            if (k.isNotEmpty()) query[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
        }
        return Request(parts[0].uppercase(), target.substringBefore('?'), query, headers)
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

    private fun reply(out: OutputStream, code: Int, reason: String, type: String, body: ByteArray, headOnly: Boolean, extra: List<String> = emptyList()) {
        val head = StringBuilder("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
        for (e in extra) head.append(e).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    override fun close() {
        running = false
        try { server?.close() } catch (e: Exception) { Log.w(TAG, "close: ${e.message}") }
    }

    companion object {
        const val TAG = "setup"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())

        /** Constant-time equality — a token compare never leaks its length in timing. */
        fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** An interface named for Tailscale (the G2CC classifier: the name
         *  carries `tailscale` or `tailnet`), re-resolved per call — tailscaled
         *  can restart under a running server. */
        fun isTailscaleInterface(name: String): Boolean =
            name.lowercase().let { it.contains("tailscale") || it.contains("tailnet") }

        /** True when [local] (the server-side address a request arrived on) is
         *  loopback or belongs to a Tailscale interface. Loopback is allowed
         *  because a same-host process can already read the config file. */
        fun tailscaleOrLoopback(local: InetAddress): Boolean {
            if (local.isLoopbackAddress) return true
            val ifaces = try { NetworkInterface.getNetworkInterfaces() } catch (e: Exception) {
                Log.w(TAG, "interfaces unreadable: ${e.message}"); return false
            } ?: return false
            for (i in ifaces) {
                if (!isTailscaleInterface(i.name)) continue
                for (a in i.inetAddresses) if (a == local) return true
            }
            return false
        }

        /** The first IPv4 address of a Tailscale interface, for the start-up line. */
        fun tailscaleAddress(): InetAddress? {
            val ifaces = try { NetworkInterface.getNetworkInterfaces() } catch (e: Exception) { return null } ?: return null
            for (i in ifaces) {
                if (!isTailscaleInterface(i.name)) continue
                for (a in i.inetAddresses) if (a is Inet4Address) return a
            }
            return null
        }
    }
}
