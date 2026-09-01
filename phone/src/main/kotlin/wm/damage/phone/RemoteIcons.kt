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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconSource
import wm.damage.core.util.Log

/**
 * The phone's theme-icon source (2026-09-01): converted bitmaps fetched from
 * the PC over the content port (`{"t":"icon",...}` → gray blob), cached on
 * disk so app-alone keeps the last-known theme look. PERSONAL LANE: nothing
 * here ships in the APK — the bitmaps come from Adam's own desktop theme.
 *
 * Review 2026-09-01 hardening: a fetch FAILURE (unreachable PC — the normal
 * cellular morning) only pauses the key for [RETRY_PACING_MS], never latches
 * "missing" for the run; only an in-band clean miss (len 0) caches as
 * [missing]. The disk cache is theme-keyed via the host's theme tag — a theme
 * change on the PC wipes it. Fetches ride a small semaphore (a cold first
 * paint must not open dozens of sockets), and [close] releases in-flight
 * sockets so a stack rebuild cannot pin IO threads.
 */
class RemoteIcons(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val cacheDir: Path,
    private val scope: CoroutineScope,
    private val onLoaded: () -> Unit = {},
) : IconSource, AutoCloseable {

    @Serializable
    private data class Hello(val t: String = "hello", val token: String, val proto: Int = 1)

    @Serializable
    private data class IconMsg(val t: String = "icon", val name: String, val size: Int)

    @Serializable
    private data class IconBlobMsg(val t: String = "iconblob", val w: Int = 0, val h: Int = 0,
        val len: Int = 0, val theme: String = "", val detail: String = "")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mem = ConcurrentHashMap<String, Gray8>()
    /** Clean in-band misses — permanent for the run. */
    private val missingKeys = ConcurrentHashMap.newKeySet<String>()
    /** Transient failures — paced retries, never latched. */
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val fetchGate = Semaphore(4)   // a cold Main paints ~15 icons; 4 at a time is plenty
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false

    override fun missing(name: String, sizePx: Int): Boolean = "$name@$sizePx" in missingKeys

    override fun icon(name: String, sizePx: Int): Gray8? {
        if (closed) return null
        val key = "$name@$sizePx"
        mem[key]?.let { return it }
        if (key in missingKeys) return null
        retryAt[key]?.let { if (System.currentTimeMillis() < it) return null else retryAt.remove(key) }
        diskRead(name, sizePx)?.let { mem[key] = it; return it }
        if (inFlight.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    fetchGate.withPermit {
                        if (closed) return@withPermit
                        val r = fetch(name, sizePx)
                        // the theme adoption and the cache insert are ONE
                        // atomic step (R3#6): no interleaving can put a bitmap
                        // into a cache whose marker disagrees with it. A stale
                        // old-theme result that lands LAST still re-adopts the
                        // old theme briefly (wipe + marker back) — reachable
                        // only across a desktop restart mid-fetch, and the
                        // next fetch re-adopts the real theme (R4#7 honesty)
                        when {
                            r.bitmap != null -> {
                                synchronized(themeLock) {
                                    syncThemeLocked(r.theme)
                                    mem[key] = r.bitmap
                                    diskWrite(name, sizePx, r.bitmap)
                                }
                                onLoaded()
                            }
                            r.cleanMiss -> {
                                synchronized(themeLock) {
                                    syncThemeLocked(r.theme)
                                    missingKeys.add(key)
                                }
                                onLoaded()   // the chain's next name gets its turn
                            }
                        }
                    }
                } catch (e: Exception) {
                    retryAt[key] = System.currentTimeMillis() + RETRY_PACING_MS
                    if (!closed) Log.i("icons",
                        "icon fetch '$name' failed (${e.message}) — drawn fallback, retrying after pacing")
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return null
    }

    private class Fetched(val bitmap: Gray8?, val cleanMiss: Boolean, val theme: String)

    private fun fetch(name: String, size: Int): Fetched {
        val sock = Socket(host, port)
        openSockets.add(sock)
        try {
            sock.use {
                it.tcpNoDelay = true
                it.keepAlive = true   // OS liveness probing (R3#1)
                val inp = DataInputStream(it.getInputStream().buffered())
                val out = DataOutputStream(it.getOutputStream().buffered())
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
                if (head.t != "iconblob") throw IllegalStateException(
                    "host answered ${head.t}: ${head.detail.ifEmpty { "refused" }}")
                if (head.len <= 0) return Fetched(null, cleanMiss = true, theme = head.theme)
                require(head.w in 1..256 && head.h in 1..256 && head.len == head.w * head.h) {
                    "icon blob shape ${head.w}x${head.h}/${head.len}"
                }
                val pix = ByteArray(head.len)
                inp.readFully(pix)
                val g = Gray8(head.w, head.h)
                System.arraycopy(pix, 0, g.pix, 0, pix.size)
                return Fetched(g, cleanMiss = false, theme = head.theme)
            }
        } finally {
            openSockets.remove(sock)
        }
    }

    /** A different serving theme wipes the cache (once), so the phone follows
     *  desktop re-themes instead of showing the old set forever. */
    private val themeLock = Any()
    /** Callers hold [themeLock] — the adoption must be atomic with the
     *  caller's cache insert (R3#6). */
    private fun syncThemeLocked(theme: String) {
        if (theme.isEmpty()) return
        run {
            val marker = cacheDir.resolve("theme.txt")
            val known = try {
                if (Files.isRegularFile(marker)) String(Files.readAllBytes(marker), Charsets.UTF_8).trim() else ""
            } catch (e: Exception) { "" }
            if (known == theme) return
            try {
                if (known.isNotEmpty()) {
                    Log.i("icons", "desktop theme changed '$known' → '$theme' — icon cache wiped")
                    Files.walk(cacheDir).use { s ->
                        s.sorted(Comparator.reverseOrder()).forEach { p ->
                            if (p != cacheDir) Files.deleteIfExists(p)
                        }
                    }
                    mem.clear()
                    missingKeys.clear()
                }
                Files.createDirectories(cacheDir)
                Files.write(marker, theme.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w("icons", "theme marker update failed: ${e.message}")
            }
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
            Log.w("icons", "icon cache read '${p.fileName}': ${e.message}")
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

    /** Stack teardown: no new fetches, and every in-flight socket is closed so
     *  a blocked read unparks (review 2026-09-01: the one stack channel that
     *  could pin IO threads across rebuilds). */
    override fun close() {
        closed = true
        for (s in openSockets) try { s.close() } catch (e: Exception) { /* closing */ }
        openSockets.clear()
    }

    companion object {
        const val RETRY_PACING_MS = 30_000L
    }
}
