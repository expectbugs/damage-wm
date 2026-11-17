package wm.damage.core.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (`CONTRACT.md` §9.5): the web client as one bundle — the files listed in `web/files.txt` on the
 * class path (`core/src/main/resources/web/`; P7 writes the client and lists its files there), read once, with a content
 * hash over every file's name and bytes, carried in a web page's `welcome` (an open page reloads itself when it moves).
 * Served with a content security policy that allows no inline script and connects only back to the PC's own page
 * origin; inline styles are allowed (L1 AC28: a terminal emulator sets them). The bundle is static: it answers without
 * the device secret (dec. A.5).
 */
class Bundle(val files: Map<String, ByteArray>) {
    /** Hex SHA-256 over each file's name, a zero byte, its length and its bytes, in name order. */
    val hash: String = run {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for ((name, b) in files.toSortedMap()) {
            md.update(name.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(b.size.toString().toByteArray(Charsets.US_ASCII)); md.update(0)
            md.update(b)
        }
        Devices.hex(md.digest())
    }

    /** `welcome`'s `bundle`. */
    fun info(): JsonObject = buildJsonObject { put("hash", hash) }

    /** The file a request path names (`/` = `index.html`), or null. */
    fun file(path: String): ByteArray? {
        val p = path.removePrefix("/").ifEmpty { INDEX }
        if (p.contains("..") || p.startsWith("/")) return null
        return files[p]
    }

    companion object {
        const val INDEX = "index.html"
        const val LIST = "files.txt"

        /** The bundle on the class path; a listed file that is missing is said and left out (never a silent gap). */
        fun fromResources(root: String = "/web/"): Bundle {
            val list = Bundle::class.java.getResourceAsStream(root + LIST)?.use { String(it.readBytes(), Charsets.UTF_8) }
            if (list == null) {
                Log.e(TAG, "no web bundle in this build (${root}$LIST missing): the web listener serves no pages")
                return Bundle(emptyMap())
            }
            val files = LinkedHashMap<String, ByteArray>()
            for (line in list.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                val b = Bundle::class.java.getResourceAsStream(root + line)?.use { it.readBytes() }
                if (b == null) Log.e(TAG, "the web bundle lists $line, which this build does not hold") else files[line] = b
            }
            return Bundle(files)
        }

        /** The policy every page answer carries: scripts from the bundle only (no inline script), styles from the
         *  bundle and inline (L1 AC28), images from the PC and the blobs, connections back to the page's own origin
         *  alone. [host] is the checked `Host`. */
        fun csp(host: String): String = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; " +
            "font-src 'self'; connect-src 'self' ws://$host; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

        fun typeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "woff2" -> "font/woff2"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }

        private const val TAG = "web"
    }
}
