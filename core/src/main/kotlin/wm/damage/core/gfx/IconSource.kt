package wm.damage.core.gfx

/**
 * The desktop-theme icon seam (2026-09-01, Adam: use his XFCE theme's icons —
 * Papirus-Dark — "for everything in DamageWM that uses icons", converted to
 * grayscale for the G2). Core stays platform-free: the desktop resolves and
 * rasterizes from the installed theme (rsvg-convert), the phone fetches
 * converted bitmaps over the content port, and EVERY call site falls back to
 * the drawn set ([Icons.draw]) on null or a miss — the drawn icons remain the
 * built-in fallback and the release path (theme sets are third-party assets;
 * the FF1 personal-lane rule applies).
 */
interface IconSource {
    /** A theme bitmap for freedesktop icon [name] at [sizePx] — 8-bit
     *  luminance, alpha composited toward black (transparent = unlit) — or
     *  null. Must be CHEAP on the paint path: memory-cached; a miss may
     *  resolve asynchronously and repaint via the host's hook. */
    fun icon(name: String, sizePx: Int): Gray8?

    /** True when [name] is KNOWN absent from the theme (a clean miss — never
     *  a transient failure). Lets the paint path walk a fallback chain past
     *  confirmed misses while scheduling only ONE unresolved name at a time
     *  (review 2026-09-01: the old walk fanned out a fetch per chain name). */
    fun missing(name: String, sizePx: Int): Boolean = false
}

/**
 * Freedesktop icon names for the shell's needs: each list is a fallback chain
 * (first hit wins), ending implicitly in the DRAWN icon.
 */
object IconNames {

    fun forKind(kind: IconKind): List<String> = when (kind) {
        IconKind.TERMINAL -> listOf("utilities-terminal", "terminal")
        IconKind.FILES -> listOf("system-file-manager", "folder")
        IconKind.READER -> listOf("accessories-ebook-reader", "org.gnome.Books", "x-office-document")
        IconKind.SETTINGS -> listOf("preferences-system", "applications-system")
        IconKind.MAIL -> listOf("internet-mail", "mail-client", "emblem-mail")
        IconKind.SMS -> listOf("internet-chat", "chat", "mail-message")
        IconKind.MUSIC -> listOf("multimedia-audio-player", "audio-x-generic")
        IconKind.NOTICES -> listOf("preferences-desktop-notification", "dialog-information")
        IconKind.CALENDAR -> listOf("office-calendar", "x-office-calendar")
        IconKind.TIMER -> listOf("preferences-system-time", "alarm-clock")
        IconKind.SCOUT -> listOf("system-search")
    }

    /** Extension → mimetype-icon fallback chain for file rows ("like a real
     *  file manager"). Curated to the names Papirus-class themes carry, with
     *  freedesktop generics behind them. */
    fun forFile(name: String, isDir: Boolean): List<String> {
        if (isDir) return listOf("folder")
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "text", "log" -> listOf("text-plain", "text-x-generic")
            "md", "markdown" -> listOf("text-markdown", "text-x-markdown", "text-x-generic")
            "pdf" -> listOf("application-pdf")
            "epub" -> listOf("application-epub+zip", "x-office-document")
            "png" -> listOf("image-png", "image-x-generic")
            "jpg", "jpeg" -> listOf("image-jpeg", "image-x-generic")
            "gif" -> listOf("image-gif", "image-x-generic")
            "bmp" -> listOf("image-bmp", "image-x-generic")
            "webp", "svg", "tiff", "heic" -> listOf("image-x-generic")
            "mp3" -> listOf("audio-mpeg", "audio-x-generic")
            "flac" -> listOf("audio-flac", "audio-x-generic")
            "ogg", "opus", "wav", "m4a" -> listOf("audio-x-generic")
            "mp4", "mkv", "webm", "avi", "mov" -> listOf("video-x-generic")
            "zip" -> listOf("application-zip", "package-x-generic")
            "gz", "bz2", "xz", "zst", "tar", "tgz", "7z", "rar" ->
                listOf("application-x-compressed-tar", "package-x-generic")
            "iso", "img" -> listOf("application-x-cd-image", "media-optical")
            "sh", "bash" -> listOf("text-x-script", "application-x-shellscript")
            "py" -> listOf("text-x-python", "text-x-script")
            "kt", "kts" -> listOf("text-x-kotlin", "text-x-script")
            "c", "h", "cpp", "rs", "go", "java", "js", "ts" -> listOf("text-x-script", "text-x-generic")
            "json" -> listOf("application-json", "text-x-generic")
            "xml", "html", "htm" -> listOf("text-html", "text-x-generic")
            "conf", "cfg", "ini", "toml", "yaml", "yml" -> listOf("text-x-generic")
            "jar" -> listOf("application-x-java-archive", "package-x-generic")
            "apk" -> listOf("android-package-archive", "package-x-generic")
            "deb", "rpm", "ebuild" -> listOf("package-x-generic")
            "bin", "exe", "so", "o" -> listOf("application-x-executable")
            "torrent" -> listOf("application-x-bittorrent", "text-x-generic")
            else -> listOf("application-x-generic", "text-x-generic")
        }
    }

    /** Non-file places the Files window shows. */
    val TRASH = listOf("user-trash-full", "user-trash")
    val HOME = listOf("user-home", "folder-home", "folder")
    val ROOT = listOf("drive-harddisk-root", "drive-harddisk", "folder")
    val DOWNLOADS = listOf("folder-download", "folder")
    val BOOKS = listOf("folder-books", "folder-library", "folder")
    val MOUNT = listOf("drive-harddisk", "drive-removable-media")
    val PROJECT = listOf("folder-development", "folder-code", "folder")
}

/**
 * One paint entry point for every icon in the shell: theme bitmap when the
 * source resolves one, the drawn icon otherwise — so a missing theme, a
 * pending async resolve, or the phone before its first fetch all render the
 * drawn set and repaint into the theme set as bitmaps arrive.
 */
object IconPaint {

    fun draw(g: Gray8, src: IconSource?, names: List<String>, x: Int, y: Int, size: Int,
        fallback: IconKind, lv: Int) {
        val bm = src?.let { s ->
            var found: Gray8? = null
            for (n in names) {
                if (s.missing(n, size)) continue      // confirmed miss: next in chain
                found = s.icon(n, size)
                break                                  // hit, or ONE pending resolve
            }
            found
        }
        if (bm == null) {
            Icons.draw(g, x, y, size, size, fallback, lv)
            return
        }
        blit(g, bm, x, y, lv)
    }

    /** Like [draw] but with NO drawn fallback shape that fits (file-type rows
     *  fall back to the generic FILES glyph). */
    fun drawFile(g: Gray8, src: IconSource?, fileName: String, isDir: Boolean,
        x: Int, y: Int, size: Int, lv: Int) =
        draw(g, src, IconNames.forFile(fileName, isDir), x, y, size,
            if (isDir) IconKind.FILES else IconKind.READER, lv)

    /** Blit a theme bitmap scaled to the paint level: at full levels the
     *  imagery keeps its 16-level range; at dim levels the whole icon dims
     *  proportionally, matching how the drawn set behaves. Level 0 pixels stay
     *  unlit (the additive panel's transparency). */
    fun blit(g: Gray8, bm: Gray8, x: Int, y: Int, lv: Int) {
        val f = lv.coerceIn(0, 255)
        for (yy in 0 until bm.h) for (xx in 0 until bm.w) {
            val v = bm[xx, yy] * f / 255
            if (v > 0) g[x + xx, y + yy] = v
        }
    }
}

/** Shared raster helpers for icon conversion (both hosts use them). */
object IconRaster {
    /** Box-sample [d] into a [size]×[size] square, aspect kept, centered;
     *  output pixels land where the quantizer keeps them stable. */
    fun toSquare(d: ImageDecoder.Decoded, size: Int): Gray8 {
        val out = Gray8(size, size)
        if (d.w <= 0 || d.h <= 0) return out
        val s = minOf(size.toDouble() / d.w, size.toDouble() / d.h)
        val w = maxOf(1, (d.w * s).toInt())
        val h = maxOf(1, (d.h * s).toInt())
        val ox = (size - w) / 2
        val oy = (size - h) / 2
        for (y in 0 until h) {
            val sy0 = y * d.h / h
            val sy1 = maxOf(sy0 + 1, (y + 1) * d.h / h)
            for (x in 0 until w) {
                val sx0 = x * d.w / w
                val sx1 = maxOf(sx0 + 1, (x + 1) * d.w / w)
                var sum = 0
                var n = 0
                for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                    sum += d.gray[sy * d.w + sx].toInt() and 0xFF
                    n++
                }
                out[ox + x, oy + y] = ((sum / n + 8) / 17).coerceAtMost(15) * 17
            }
        }
        return out
    }
}
