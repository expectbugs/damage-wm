package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import wm.damage.core.content.IconResolver
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.IconRaster
import wm.damage.core.gfx.IconSource
import wm.damage.core.gfx.ImageDecoder
import wm.damage.core.util.Log

/**
 * Theme icons from the user's OWN desktop theme (2026-09-01, Adam: "use the
 * same icon set I currently use on my xfce4 desktop … converted to greyscale
 * for the G2" — Papirus-Dark on beardos, the DARK variant being exactly right
 * for an additive black-transparent panel). PERSONAL LANE like the FF1 assets:
 * nothing is baked into the repo or APK — this reads the installed theme at
 * runtime, converts, caches, and serves the phone over the content port; the
 * drawn set stays the fallback and the release path.
 *
 * Resolution follows the real freedesktop rules pragmatically: the active
 * theme from xfconf, its `Inherits` chain (Papirus-Dark → breeze-dark →
 * hicolor), sized directories in both layouts (`64x64/mimetypes` Papirus-style
 * and `mimetypes/64` breeze-style) plus `scalable`. SVGs rasterize through
 * `rsvg-convert` (ImageMagick as fallback); PNGs decode directly. Everything
 * lands as 8-bit luminance composited toward black, box-sampled square, and is
 * cached in memory and on disk (`~/.damage/icons/<theme>/<size>/`).
 *
 * [IconSource.icon] is the PAINT path: cache hits only; a miss schedules an
 * async resolve and returns null (the drawn icon paints this frame), then
 * [onLoaded] repaints. [resolve] is the BLOCKING form the content host uses
 * to serve the phone.
 */
class ThemeIcons(
    private val decoder: ImageDecoder,
    private val cacheDir: Path,
    themeOverride: String? = null,
    private val onLoaded: () -> Unit = {},
) : IconSource, IconResolver {

    private val themeName: String = themeOverride ?: detectXfceTheme() ?: "hicolor"
    private val chain: List<Path> = buildChain(themeName)
    private val mem = ConcurrentHashMap<String, Gray8>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "theme-icons").apply { isDaemon = true }
    }

    init {
        Log.i("icons", "theme '$themeName' — chain: ${chain.joinToString { it.fileName.toString() }}")
    }

    // ------------------------------------------------------------- the seam
    override fun icon(name: String, sizePx: Int): Gray8? {
        val key = "$name@$sizePx"
        mem[key]?.let { return it }
        if (key in missing) return null
        // disk cache is cheap enough for the paint path (a few KB, once)
        diskRead(name, sizePx)?.let { mem[key] = it; return it }
        if (inFlight.add(key)) {
            pool.execute {
                try {
                    val g = resolveUncached(name, sizePx)
                    if (g == null) {
                        missing.add(key)
                        Log.i("icons", "no theme icon for '$name' — drawn fallback stays")
                    } else {
                        mem[key] = g
                        diskWrite(name, sizePx, g)
                        onLoaded()
                    }
                } catch (e: Exception) {
                    missing.add(key)
                    Log.w("icons", "resolve of '$name'@$sizePx failed: ${e.message}")
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return null
    }

    /** Blocking resolve — the content host serves the phone through this. */
    override fun resolve(name: String, sizePx: Int): Gray8? {
        val key = "$name@$sizePx"
        mem[key]?.let { return it }
        if (key in missing) return null
        diskRead(name, sizePx)?.let { mem[key] = it; return it }
        val g = try { resolveUncached(name, sizePx) } catch (e: Exception) {
            Log.w("icons", "resolve of '$name'@$sizePx failed: ${e.message}")
            null
        }
        if (g == null) missing.add(key) else { mem[key] = g; diskWrite(name, sizePx, g) }
        return g
    }

    // --------------------------------------------------------------- lookup
    private fun resolveUncached(name: String, size: Int): Gray8? {
        val f = findFile(name, size) ?: return null
        val png: ByteArray = when {
            f.toString().endsWith(".svg") -> rasterizeSvg(f, size) ?: return null
            else -> Files.readAllBytes(f)
        }
        val d = decoder.decode(png) ?: return null
        return IconRaster.toSquare(d, size)
    }

    private val categories = listOf("mimetypes", "places", "apps", "devices", "status", "actions", "categories", "emblems")

    /** Candidate dirs inside one theme for [size], best first: exact size,
     *  then larger sizes ascending (downscaling keeps quality), scalable last. */
    private fun sizeDirs(theme: Path, size: Int): List<String> {
        val sizes = ArrayList<Int>()
        try {
            Files.list(theme).use { s ->
                for (p in s) {
                    val n = p.fileName.toString()
                    val m = Regex("^(\\d+)x\\1(@2x)?$").matchEntire(n)
                    if (m != null && m.groupValues[2].isEmpty()) sizes.add(m.groupValues[1].toInt())
                    else n.toIntOrNull()?.let { sizes.add(it) }   // breeze bare-number dirs
                }
            }
        } catch (e: Exception) { /* unreadable theme dir */ }
        val ordered = sizes.distinct().sortedWith(compareBy({ it < size }, { kotlin.math.abs(it - size) }))
        return ordered.flatMap { listOf("${it}x${it}", "$it") }.distinct() + "scalable"
    }

    private fun findFile(name: String, size: Int): Path? {
        for (theme in chain) {
            val dims = sizeDirs(theme, size)
            for (dim in dims) for (cat in categories) {
                for (candidate in listOf(
                    theme.resolve(dim).resolve(cat),      // Papirus: 64x64/mimetypes
                    theme.resolve(cat).resolve(dim),      // breeze: mimetypes/64
                )) {
                    for (ext in listOf("svg", "png")) {
                        val p = candidate.resolve("$name.$ext")
                        if (Files.isRegularFile(p) || Files.isSymbolicLink(p)) {
                            // follow theme-internal symlinks (folder.svg → folder-blue.svg)
                            return try { p.toRealPath() } catch (e: Exception) { null } ?: continue
                        }
                    }
                }
            }
        }
        return null
    }

    private fun rasterizeSvg(f: Path, size: Int): ByteArray? {
        for (cmd in listOf(
            listOf("rsvg-convert", "-w", "$size", "-h", "$size", f.toString()),
            listOf("magick", f.toString(), "-background", "none", "-resize", "${size}x${size}", "png:-"),
        )) {
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
                val outBytes = p.inputStream.readBytes()
                val err = p.errorStream.readBytes()
                val code = p.waitFor()
                if (code == 0 && outBytes.isNotEmpty()) return outBytes
                Log.w("icons", "${cmd[0]} on ${f.fileName} exit $code: ${err.toString(Charsets.UTF_8).take(200)}")
            } catch (e: java.io.IOException) {
                // tool not installed — try the next one
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    // --------------------------------------------------------------- caches
    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._+-]"), "_")

    private fun diskPath(name: String, size: Int): Path =
        cacheDir.resolve(safe(themeName)).resolve("$size").resolve(safe(name) + ".gray")

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
            Log.w("icons", "icon cache read ${p.fileName}: ${e.message}")
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

    // ---------------------------------------------------------------- theme
    companion object {
        private val ICON_ROOTS = listOf(
            Path.of(System.getProperty("user.home"), ".icons"),
            Path.of(System.getProperty("user.home"), ".local/share/icons"),
            Path.of("/usr/share/icons"),
        )

        /** The active theme from xfconf's xsettings channel (a plain file read
         *  — no D-Bus session needed from the headless service). */
        fun detectXfceTheme(): String? {
            val f = Path.of(System.getProperty("user.home"),
                ".config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml")
            if (!Files.isRegularFile(f)) return null
            return try {
                Regex("name=\"IconThemeName\"[^/>]*value=\"([^\"]+)\"")
                    .find(Files.readString(f))?.groupValues?.get(1)
            } catch (e: Exception) {
                Log.w("icons", "xfconf read failed: ${e.message}")
                null
            }
        }

        /** BFS over `Inherits`, ending in hicolor; missing themes are skipped
         *  (the chain shrinks, never errors). */
        fun buildChain(themeName: String, roots: List<Path> = ICON_ROOTS): List<Path> {
            val out = ArrayList<Path>()
            val seen = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            queue.add(themeName)
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                if (!seen.add(n)) continue
                val dir = roots.map { it.resolve(n) }.firstOrNull { Files.isDirectory(it) } ?: continue
                out.add(dir)
                val idx = dir.resolve("index.theme")
                if (Files.isRegularFile(idx)) {
                    try {
                        Files.readAllLines(idx).firstOrNull { it.startsWith("Inherits=") }
                            ?.removePrefix("Inherits=")?.split(',')
                            ?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?.forEach { queue.add(it) }
                    } catch (e: Exception) {
                        Log.w("icons", "index.theme of $n unreadable: ${e.message}")
                    }
                }
            }
            if (seen.add("hicolor")) {
                roots.map { it.resolve("hicolor") }.firstOrNull { Files.isDirectory(it) }?.let { out.add(it) }
            }
            return out
        }
    }
}
