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
import wm.damage.core.util.Exec
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
 * hicolor), sized directories in BOTH layouts — Papirus-style
 * `64x64/mimetypes` and breeze-style `mimetypes/64` (sizes discovered per
 * layout — review 2026-09-01: root-only discovery made category-first themes
 * unreachable) — plus `scalable`. SVGs rasterize through `rsvg-convert`
 * (ImageMagick as fallback) via the deadlock-proof [Exec]; PNGs decode
 * directly. Results are 8-bit luminance composited toward black, box-sampled
 * square, cached in memory and on disk (`~/.damage/icons/<theme>/<size>/`).
 *
 * Failure discipline (review 2026-09-01): a CLEAN miss (the theme has no such
 * icon) caches as [missing] permanently; a FAILURE (tool refused, IO hiccup)
 * only pauses that key for [RETRY_PACING_MS] — an emerge replacing theme
 * files or one bad moment must not latch drawn icons for an all-day service.
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
    /** Clean misses — permanent for the run. */
    private val missingKeys = ConcurrentHashMap.newKeySet<String>()
    /** Transient failures — retried after pacing, never latched. */
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "theme-icons").apply { isDaemon = true }
    }

    init {
        Log.i("icons", "theme '$themeName' — chain: ${chain.joinToString { it.fileName.toString() }}")
    }

    override fun themeId(): String = themeName

    /** Resolves still outstanding — the snapshot harness drains this instead
     *  of guessing with fixed delays. */
    fun pending(): Int = inFlight.size

    // ------------------------------------------------------------- the seam
    override fun missing(name: String, sizePx: Int): Boolean = "$name@$sizePx" in missingKeys

    override fun icon(name: String, sizePx: Int): Gray8? {
        val key = "$name@$sizePx"
        mem[key]?.let { return it }
        if (key in missingKeys) return null
        retryAt[key]?.let { if (System.currentTimeMillis() < it) return null else retryAt.remove(key) }
        // disk cache is cheap enough for the paint path (a few KB, once)
        diskRead(name, sizePx)?.let { mem[key] = it; return it }
        if (inFlight.add(key)) {
            pool.execute {
                try {
                    val (g, cleanMiss) = resolveUncached(name, sizePx)
                    when {
                        g != null -> {
                            mem[key] = g
                            diskWrite(name, sizePx, g)
                            onLoaded()
                        }
                        cleanMiss -> {
                            missingKeys.add(key)
                            onLoaded()   // the chain's next name gets its turn
                        }
                        else -> {
                            retryAt[key] = System.currentTimeMillis() + RETRY_PACING_MS
                            Log.w("icons", "resolve of '$name'@$sizePx failed — retrying after pacing")
                        }
                    }
                } catch (e: Exception) {
                    retryAt[key] = System.currentTimeMillis() + RETRY_PACING_MS
                    Log.w("icons", "resolve of '$name'@$sizePx threw (${e.message}) — retrying after pacing")
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return null
    }

    /** Blocking resolve — the content host serves the phone through this.
     *  Throws on FAILURE (the host answers err, the phone retries); returns
     *  null only for a clean miss (the phone may cache it). */
    override fun resolve(name: String, sizePx: Int): Gray8? {
        val key = "$name@$sizePx"
        mem[key]?.let { return it }
        if (key in missingKeys) return null
        diskRead(name, sizePx)?.let { mem[key] = it; return it }
        val (g, cleanMiss) = resolveUncached(name, sizePx)
        when {
            g != null -> { mem[key] = g; diskWrite(name, sizePx, g) }
            cleanMiss -> missingKeys.add(key)
            else -> throw IllegalStateException("icon '$name'@$sizePx did not resolve (transient)")
        }
        return g
    }

    // --------------------------------------------------------------- lookup
    /** (bitmap, cleanMiss): (g, _) success · (null, true) the theme has no
     *  USABLE icon of this name · (null, false) or a THROW = transient. A
     *  file a tool ran on and refused, or one that will not decode, is a
     *  CLEAN MISS (R2#13): deterministically dead for this theme session —
     *  calling it transient re-ran the tool every 30 s all day while the
     *  paint chain never advanced past it. TOOL-level trouble (nothing
     *  installed, signal-class exits, IO read failures) stays transient
     *  (R3d#4 — rasterizeSvg throws for those). */
    private fun resolveUncached(name: String, size: Int): Pair<Gray8?, Boolean> {
        val f = findFile(name, size) ?: return null to true
        val png: ByteArray = when {
            f.toString().endsWith(".svg") -> rasterizeSvg(f, size) ?: run {
                Log.w("icons", "$f will not rasterize — treating '$name'@$size as missing for this theme")
                return null to true
            }
            else -> try { Files.readAllBytes(f) } catch (e: Exception) {
                Log.w("icons", "read of $f failed: ${e.message}")
                return null to false
            }
        }
        val d = decoder.decode(png) ?: run {
            Log.w("icons", "$f will not decode — treating '$name'@$size as missing for this theme")
            return null to true
        }
        return IconRaster.toSquare(d, size) to false
    }

    private val categories = listOf("mimetypes", "places", "apps", "devices", "status", "actions", "categories", "emblems")

    /** Size tokens available in [theme], best-first for [size] — discovered
     *  from the theme ROOT (Papirus: `64x64/…`) AND from inside category dirs
     *  (breeze: `mimetypes/64`), since either layout may be the only one. */
    private val sizeDirCache = ConcurrentHashMap<String, List<String>>()

    private fun sizeDirs(theme: Path, size: Int): List<String> =
        sizeDirCache.getOrPut("$theme@$size") {
            val sizes = sortedSetOf<Int>()
            fun scan(dir: Path) {
                try {
                    Files.list(dir).use { s ->
                        for (p in s) {
                            val n = p.fileName.toString()
                            val m = Regex("^(\\d+)x\\1$").matchEntire(n)
                            if (m != null) sizes.add(m.groupValues[1].toInt())
                            else n.toIntOrNull()?.let { sizes.add(it) }
                        }
                    }
                } catch (e: Exception) { /* unreadable dir — the chain shrinks */ }
            }
            scan(theme)
            for (cat in categories) {
                val c = theme.resolve(cat)
                if (Files.isDirectory(c)) scan(c)
            }
            val ordered = sizes.toList().sortedWith(compareBy({ it < size }, { kotlin.math.abs(it - size) }))
            ordered.flatMap { listOf("${it}x${it}", "$it") }.distinct() + "scalable"
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

    private val toolAbsent = ConcurrentHashMap.newKeySet<String>()

    /** null = a tool RAN and refused the file (deterministic — the caller may
     *  latch a clean miss). Tool-level trouble — no rasterizer installed,
     *  interrupted, a signal-class exit (an OOM-stopped rsvg-convert during a
     *  -j12 emerge) — THROWS instead (R3d#4): those are transient and must
     *  keep the paced-retry lane, never latch drawn icons for an all-day
     *  service. */
    private fun rasterizeSvg(f: Path, size: Int): ByteArray? {
        var refusals = 0
        for (cmd in listOf(
            listOf("rsvg-convert", "-w", "$size", "-h", "$size", f.toString()),
            listOf("magick", f.toString(), "-background", "none", "-resize", "${size}x${size}", "png:-"),
        )) {
            if (cmd[0] in toolAbsent) continue
            try {
                val r = Exec.run(cmd)
                if (r.code == 0 && r.stdout.isNotEmpty()) return r.stdout
                Log.w("icons", "${cmd[0]} on ${f.fileName} exit ${r.code}: ${r.stderr.take(200)}")
                // a normal-exit refusal — including exit 0 with NO output
                // (R4 note: some tools "succeed" emptily on a bad file) —
                // is deterministic; signal-class exits stay transient
                if (r.code in 0..127) refusals++
            } catch (e: java.io.IOException) {
                if (toolAbsent.add(cmd[0])) {
                    Log.w("icons", "${cmd[0]} is not installed — SVG theme icons need it (trying the next tool)")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("rasterize of ${f.fileName} interrupted")
            }
        }
        if (refusals == 0) throw IllegalStateException(
            "no rasterizer could run for ${f.fileName} (absent tools or transient failures)")
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
        /** Transient-failure pacing — a hiccup pauses one key, never latches
         *  the run (an all-day service must recover by itself). */
        const val RETRY_PACING_MS = 30_000L

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
