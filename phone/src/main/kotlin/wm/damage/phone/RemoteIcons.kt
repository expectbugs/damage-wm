package wm.damage.phone

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconSource
import wm.damage.core.util.Log

/**
 * The phone's theme-icon source (2026-09-01): converted bitmaps fetched from
 * the PC over the content port (`{"t":"icon",...}` → gray blob), cached on
 * disk so app-alone keeps the last-known theme look. A miss caches as missing
 * for the run; everything falls back to the drawn set until a fetch lands,
 * then [onLoaded] repaints. PERSONAL LANE: nothing here ships in the APK —
 * the bitmaps come from Adam's own installed desktop theme at runtime.
 */
class RemoteIcons(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val cacheDir: Path,
    private val scope: CoroutineScope,
    private val onLoaded: () -> Unit = {},
) : IconSource {

    @Serializable
    private data class Hello(val t: String = "hello", val token: String, val proto: Int = 1)

    @Serializable
    private data class IconMsg(val t: String = "icon", val name: String, val size: Int)

    @Serializable
    private data class IconBlobMsg(val t: String = "iconblob", val w: Int = 0, val h: Int = 0, val len: Int = 0)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mem = ConcurrentHashMap<String, Gray8>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    override fun icon(name: String, sizePx: Int): Gray8? {
        val key = "$name@$sizePx"
        mem[key]?.let { return it }
        if (key in missing) return null
        diskRead(name, sizePx)?.let { mem[key] = it; return it }
        if (inFlight.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val g = fetch(name, sizePx)
                    if (g == null) {
                        // a miss OR an unreachable PC: don't cache "missing"
                        // when the fetch itself failed — retry next session
                        Log.i("icons", "no theme icon for '$name' from $host this run")
                        missing.add(key)
                    } else {
                        mem[key] = g
                        diskWrite(name, sizePx, g)
                        onLoaded()
                    }
                } catch (e: Exception) {
                    Log.w("icons", "icon fetch '$name' failed (${e.message}) — drawn fallback; will not retry this run")
                    missing.add(key)
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return null
    }

    private fun fetch(name: String, size: Int): Gray8? {
        Socket(host, port).use { sock ->
            sock.tcpNoDelay = true
            val inp = DataInputStream(sock.getInputStream().buffered())
            val out = DataOutputStream(sock.getOutputStream().buffered())
            fun send(s: String) {
                val b = s.toByteArray(Charsets.UTF_8)
                out.writeInt(b.size); out.write(b); out.flush()
            }
            send(json.encodeToString(Hello.serializer(), Hello(token = token)))
            send(json.encodeToString(IconMsg.serializer(), IconMsg(name = name, size = size)))
            val n = inp.readInt()
            require(n in 1..(1 shl 20)) { "icon frame length $n" }
            val fb = ByteArray(n)
            inp.readFully(fb)
            val head = json.decodeFromString(IconBlobMsg.serializer(), fb.toString(Charsets.UTF_8))
            if (head.t == "err") throw IllegalStateException("host refused the icon request")
            if (head.len <= 0 || head.w <= 0 || head.h <= 0) return null      // a real miss
            require(head.len == head.w * head.h && head.len <= (1 shl 20)) { "icon blob ${head.len}" }
            val pix = ByteArray(head.len)
            inp.readFully(pix)
            val g = Gray8(head.w, head.h)
            System.arraycopy(pix, 0, g.pix, 0, pix.size)
            return g
        }
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._+-]"), "_")
    private fun diskPath(name: String, size: Int) = cacheDir.resolve("$size").resolve(safe(name) + ".gray")

    private fun diskRead(name: String, size: Int): Gray8? {
        val p = diskPath(name, size)
        if (!Files.isRegularFile(p)) return null
        return try {
            val b = Files.readAllBytes(p)
            if (b.size < 8) return null
            val w = (b[0].toInt() and 0xFF shl 8) or (b[1].toInt() and 0xFF)
            val h = (b[2].toInt() and 0xFF shl 8) or (b[3].toInt() and 0xFF)
            if (w <= 0 || h <= 0 || b.size != 8 + w * h) return null
            val g = Gray8(w, h)
            System.arraycopy(b, 8, g.pix, 0, w * h)
            g
        } catch (e: Exception) {
            null
        }
    }

    private fun diskWrite(name: String, size: Int, g: Gray8) {
        try {
            val p = diskPath(name, size)
            Files.createDirectories(p.parent)
            val b = ByteArray(8 + g.pix.size)
            b[0] = (g.w shr 8).toByte(); b[1] = g.w.toByte()
            b[2] = (g.h shr 8).toByte(); b[3] = g.h.toByte()
            System.arraycopy(g.pix, 0, b, 8, g.pix.size)
            val tmp = p.resolveSibling(p.fileName.toString() + ".${System.nanoTime()}.tmp")
            Files.write(tmp, b)
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Log.w("icons", "icon cache write '$name': ${e.message}")
        }
    }
}
