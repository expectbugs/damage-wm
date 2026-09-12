package wm.damage.core.comp

import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.util.Log
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.TextureCache

/**
 * Text through the firmware's texture cache — `HANDOFF.md` §40, Adam's ruling
 * of 2026-09-05 ("adopt it as far as it goes"); the CFW's modes 12/13/14
 * (`patches/texture_cache.c`, modeled byte-exactly by `GlassFirmwareSim`).
 *
 * The shape. A [GlyphAtlas] renders every glyph 32..126 of a resolved font
 * ONCE, through the host's own rasterizer, into an advance-width box the
 * face's ink height tall (`TextureCache`: mode 14 advances by the cached
 * image's width and nothing else), quantised to the wire's 4 bits, and packs
 * them with a 96-entry table; the shell uploads the packed bytes with mode
 * 12 once per lease. A [CachedText] wraps the host rasterizer: a string in a
 * font the glasses HOLD is blitted from those same glyph images, pixel for
 * pixel as the firmware will draw it (source 0 skipped — the TRANSPARENT
 * option — every other level through the LUT `s × top / 15`), and recorded
 * as a [TextDraw]; everything else draws as before. The compositor, for a
 * dirty rect at disparity 0, re-renders the recorded draws over black and,
 * ONLY when that equals the composed pixels byte for byte, ships one clear
 * and the strings as mode-14 draws instead of the pixels
 * (`Compositor.emitCached`). So the path is exact by construction and
 * opportunistic by construction: a font not on the glasses, a string with a
 * character outside 32..126, text over anything but black, a rect on a
 * depth plane — each simply stays pixels.
 *
 * Why disparity 0 only: modes 13/14 ignore the batch's "lenses differ" bit
 * and draw at one x into each lens's shadow (`zlib_glue.c`), so a cached
 * draw is always flat. Content planes park at depth 8 and chrome at 12;
 * plane 0 is the lens band (the heavy part of a list notch, 3–6 KB
 * measured), menus, notifications, the switcher — and everything when the
 * Depth setting is 0. A per-lens variant is a firmware-side ask.
 *
 * What changes visibly: cached text advances by integer glyph widths with no
 * pair kerning (the same glyph bitmaps, a pixel apart here and there), and a
 * level's ramp is the LUT's integer one. Belief and glass agree on both by
 * construction; the design's eye may not — the Global `Cached text` row is
 * off until it has been seen on glass.
 */
class GlyphAtlas(private val base: TextRasterizer) {

    /** One packed font: its table, its 96 images (tofu where the face has no
     *  glyph), the box height every glyph shares, the rendered glyphs and
     *  tofu a repack re-places without rendering again (§47), and the bytes
     *  it took when packed (dedup makes it an upper bound). */
    class Entry(val font: TextureCache.Font, val images: Array<TextureCache.Image>, val lineH: Int,
        val glyphs: Map<Char, TextureCache.Image>, val tofu: TextureCache.Image, val bytes: Int)

    /** A face rendered but not yet placed — priced by [price], placed by [add]. */
    private class Rendered(val glyphs: Map<Char, TextureCache.Image>, val tofu: TextureCache.Image, val lineH: Int)

    private var builder = TextureCache.Builder()
    private val entries = HashMap<FontSpec, Entry>()
    private val refused = HashSet<FontSpec>()
    private val rendered = HashMap<FontSpec, Rendered>()

    /** One packed icon (§41): its cache offset and the 4-bit image. */
    class ImageEntry(val offset: Int, val image: TextureCache.Image, val bytes: Int)
    private val images = HashMap<Any, ImageEntry>()
    private val refusedImages = HashSet<Any>()
    /** Bytes the icons take — capped at [IMAGE_BUDGET], fonts come first. */
    var imageBytes = 0
        private set
    fun image(key: Any): ImageEntry? = images[key]
    fun hasImage(key: Any) = key in images
    fun isImageRefused(key: Any) = key in refusedImages
    fun imageKeys(): Set<Any> = images.keys.toSet()
    fun isImageAcked(key: Any): Boolean = images[key]?.let { it.offset + it.bytes <= ackedBytes } ?: false

    /** Pack an icon. False, and remembered, when it would take the atlas
     *  past the icons' share or past the cache. */
    fun addImage(key: Any, img: TextureCache.Image): Boolean {
        if (key in images) return true
        if (key in refusedImages) return false
        val enc = img.encode()
        if (imageBytes + enc.size > IMAGE_BUDGET || builder.free < enc.size) {
            refusedImages += key
            Log.w("atlas", "icon $key (${img.w}x${img.h}, ${enc.size} B) stays pixels — icons hold $imageBytes B of $IMAGE_BUDGET, ${builder.free} B free")
            return false
        }
        val off = builder.add(img)
        images[key] = ImageEntry(off, img, enc.size)
        imageBytes += enc.size
        return true
    }

    /** Packed bytes queued for the glasses — the upload watermark. */
    var sentBytes = TextureCache.GUARD
        private set
    /** Packed bytes the glasses have ACKED (§41): a font whose table ends
     *  below this is on the glasses whatever the setting did since. */
    var ackedBytes = TextureCache.GUARD
        private set
    /** The end offset of every chunk [takeUpload] handed out, in order —
     *  [acked] moves the acked watermark to the next one. */
    private val chunkEnds = ArrayDeque<Int>()

    val used: Int get() = builder.used
    val free: Int get() = builder.free
    fun entry(spec: FontSpec): Entry? = entries[spec]
    fun has(spec: FontSpec) = spec in entries
    fun isRefused(spec: FontSpec) = spec in refused
    fun specs(): Set<FontSpec> = entries.keys.toSet()
    /** True when [spec]'s glyphs AND table have been acked by the glasses. */
    fun isAcked(spec: FontSpec): Boolean =
        entries[spec]?.let { it.font.tableOffset + CfwModes.FONT_TABLE_BYTES <= ackedBytes } ?: false

    /** Render every glyph of [spec] once (kept until [add] places it). */
    private fun render(spec: FontSpec): Rendered = rendered.getOrPut(spec) {
        val m: FontMetrics = base.metrics(spec)
        val h = (m.ascent + m.descent).coerceIn(1, CfwModes.MAX_TEXTURE_DIM)
        val glyphs = HashMap<Char, TextureCache.Image>()
        for (c in TextureCache.FIRST_CHAR..LAST_GLYPH) {
            val ch = c.toChar()
            val s = ch.toString()
            if (!base.covers(s, spec)) continue
            val adv = base.measure(s, spec)
            if (adv < 1 || adv > CfwModes.MAX_TEXTURE_DIM) continue
            val g = Gray8(adv, h)
            base.draw(g, 0, 0, s, spec, 255)
            val levels = ByteArray(adv * h) { Pack.level(g.pix[it].toInt() and 0xFF).toByte() }
            glyphs[ch] = TextureCache.Image(adv, h, levels)
        }
        // the tofu: a hollow box — a missing glyph must look wrong, not like nothing
        val tw = (glyphs['?']?.w ?: 8).coerceIn(3, CfwModes.MAX_TEXTURE_DIM)
        val tofu = TextureCache.Image(tw, h, ByteArray(tw * h) { i ->
            val x = i % tw; val y = i / tw
            if (x == 0 || x == tw - 1 || y == 0 || y == h - 1) 15 else 0
        })
        Rendered(glyphs, tofu, h)
    }

    /** The bytes [spec] would take in the cache as it stands (table included,
     *  glyphs already present counted once) — what an eviction must free. */
    fun price(spec: FontSpec): Int = entries[spec]?.bytes ?: render(spec).let { builder.priceFont(it.glyphs, it.tofu) }

    /** The bytes [spec] took when it was packed; 0 when it is not resident. */
    fun bytesOf(spec: FontSpec): Int = entries[spec]?.bytes ?: 0

    /** §47: forget every refusal — the shell retries a refused face once the
     *  faces around it have gone stale enough to evict (a refusal is a
     *  short-cut past re-pricing, never a verdict for the session). */
    fun unrefuseAll() { refused.clear(); refusedImages.clear() }

    /**
     * Render and pack [spec]. False, and remembered, when it does not fit
     * what is left of the 64 KiB (a fit is first come, first served — the
     * fonts a session draws first are the lens's, which is where the bytes
     * are). Glyphs are rendered at full level; the draw's LUT dims them.
     */
    fun add(spec: FontSpec): Boolean {
        if (spec in entries) return true
        if (spec in refused) return false
        val r = render(spec)
        return try {
            val before = builder.used
            val font = builder.addFont(r.glyphs, r.tofu)
            val images = Array(CfwModes.FONT_TABLE_CHARS) { i -> r.glyphs[(i + TextureCache.FIRST_CHAR).toChar()] ?: r.tofu }
            entries[spec] = Entry(font, images, r.lineH, r.glyphs, r.tofu, builder.used - before)
            rendered.remove(spec)
            true
        } catch (e: LintError) {
            refused += spec
            Log.w("atlas", "font $spec stays pixels — the texture cache is full (${builder.used} B used): ${e.message}")
            false
        }
    }

    /**
     * §47 (2026-09-12): rebuild the cache with only [keepFonts] and
     * [keepImages], in that order — the fonts first, heaviest first, then
     * the icons into what they leave. Every offset changes, so the caller
     * has ALREADY taken every font and icon off the live sets (the glasses
     * are about to be rewritten from the guard up, and a mode-14 draw
     * against a half-written table would draw the wrong glyphs); the upload
     * watermarks reset so [takeUpload] hands out the whole new content, and
     * the refusals are forgotten so the faces that did not fit get their
     * turn. A kept font that fit before fits again (removing bytes never
     * costs room); one that somehow does not is refused loudly, not kept
     * half-placed. Returns the bytes in use after the rebuild.
     */
    fun repack(keepFonts: List<FontSpec>, keepImages: List<Any>): Int {
        val fonts = keepFonts.mapNotNull { s -> entries[s]?.let { s to it } }
        val icons = keepImages.mapNotNull { k -> images[k]?.let { k to it.image } }
        builder = TextureCache.Builder()
        entries.clear(); refused.clear()
        images.clear(); refusedImages.clear(); imageBytes = 0
        for ((s, e) in fonts) {
            try {
                val before = builder.used
                val font = builder.addFont(e.glyphs, e.tofu)
                entries[s] = Entry(font, e.images, e.lineH, e.glyphs, e.tofu, builder.used - before)
            } catch (x: LintError) {
                refused += s
                Log.e("atlas", "repack: kept font $s no longer fits (${builder.used} B used) — refused: ${x.message}")
            }
        }
        for ((k, img) in icons) if (!addImage(k, img)) Log.e("atlas", "repack: kept icon $k no longer fits")
        forgetUpload()
        return builder.used
    }

    /** The mode-12 messages for the packed bytes not yet sent, each at most
     *  [maxMessage] B, and the watermark moved past them. */
    fun takeUpload(maxMessage: Int = 3072): List<ByteArray> {
        val all = builder.content()
        if (all.size <= sentBytes) return emptyList()
        val out = ArrayList<ByteArray>()
        var pos = sentBytes
        while (pos < all.size) {
            val n = minOf(maxMessage - 5, all.size - pos)
            out += CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(pos, all.copyOfRange(pos, pos + n))))
            pos += n
            chunkEnds.addLast(pos)
        }
        sentBytes = all.size
        return out
    }

    /** One chunk acked, in the order [takeUpload] handed them out. A chunk
     *  the lapse forgot answers after [forgetUpload]: nothing to move. */
    fun acked() {
        val end = chunkEnds.removeFirstOrNull() ?: return
        ackedBytes = maxOf(ackedBytes, end)
    }

    /** The glasses freed the cache (the lease lapsed): everything goes again. */
    fun forgetUpload() {
        sentBytes = TextureCache.GUARD
        ackedBytes = TextureCache.GUARD
        chunkEnds.clear()
    }

    companion object {
        /** DEL (127) is in the table but is no glyph: the table entry is tofu. */
        const val LAST_GLYPH = 126
        /** §41: icons take at most a quarter of the cache — a lens icon is
         *  ~0.4–1 KB packed, a row icon ~150 B; fonts are where the bytes are. */
        const val IMAGE_BUDGET = 16 * 1024
    }
}

/** One icon blitted from the cache into the shell's surface (§41). */
class ImageDraw(val rect: Rect, val key: Any, val x: Int, val y: Int, val level: Int)

/** One string blitted from the atlas into the shell's surface. [rect] is
 *  the glyph boxes' union — exactly the pixels the blit could have touched. */
class TextDraw(val rect: Rect, val text: String, val spec: FontSpec, val x: Int, val y: Int, val level: Int)

/**
 * The recording rasterizer (see the file comment). Hosts wrap their
 * platform rasterizer in one of these and hand it to the shell and to every
 * window, so every draw everywhere passes through — the shell decides which
 * fonts are [live] (uploaded and acked) and which surface is the [target]
 * (its composed surface; draws into a slide's temp are blitted the same
 * way but not recorded — their destination is unknown here).
 */
class CachedText(val base: TextRasterizer) : TextRasterizer, wm.damage.core.gfx.IconRecorder {
    @Volatile var atlas: GlyphAtlas? = null
    @Volatile var live: Set<FontSpec> = emptySet()
    /** Icons the glasses hold (§41), by key. */
    @Volatile var liveImages: Set<Any> = emptySet()
    @Volatile var target: Gray8? = null

    private class ImageInfo(val image: TextureCache.Image, var uses: Int, var usage: Long, var lastUse: Long = 0L)

    /** §47: a frame counter — bumped by [endFrame], stamped on every draw —
     *  so the shell can tell a face the current window keeps drawing from
     *  one the previous window left behind. */
    @Volatile var useClock = 0L
        private set
    private val lastUse = HashMap<FontSpec, Long>()
    fun lastUseOf(spec: FontSpec): Long = synchronized(seen) { lastUse[spec] ?: 0L }
    fun imageLastUse(key: Any): Long = synchronized(seen) { seenImages[key]?.lastUse ?: 0L }
    /** Icons drawn on the target so far, with the 4-bit image the atlas
     *  will pack (quantised once) and how often and how large. */
    private val seenImages = LinkedHashMap<Any, ImageInfo>()
    private val imageDraws = ArrayList<ImageDraw>()
    fun seenImageKeys(): List<Any> = synchronized(seen) { seenImages.keys.toList() }
    fun imageOf(key: Any): TextureCache.Image? = synchronized(seen) { seenImages[key]?.image }
    fun imageUses(key: Any): Int = synchronized(seen) { seenImages[key]?.uses ?: 0 }
    fun imageUsage(key: Any): Long = synchronized(seen) { seenImages[key]?.usage ?: 0L }
    /** The icon draws recorded since the last [endFrame]. */
    fun frameImageDraws(): List<ImageDraw> = synchronized(draws) { imageDraws.toList() }

    /**
     * §41: an icon through the cache. Seen on the target or a relayed temp
     * it is counted (packed once drawn twice — a thumbnail scrolled past
     * once must not take the fonts' room); held by the glasses it blits
     * from the packed image through the firmware's LUT and is recorded;
     * anything else and the caller paints it as before.
     */
    override fun drawIcon(g: Gray8, key: Any, bm: Gray8, x: Int, y: Int, lv: Int): Boolean {
        val onTarget = g === target
        val rel = relay
        val onRelay = !onTarget && rel != null && g === rel
        if (!onTarget && !onRelay) return false
        if (bm.w !in 1..CfwModes.MAX_TEXTURE_DIM || bm.h !in 1..CfwModes.MAX_TEXTURE_DIM) return false
        synchronized(seen) {
            val info = seenImages.getOrPut(key) {
                // bounded: a thumbnail shelf scrolled through decodes a new
                // bitmap per file, and the table must not grow with the shelf
                while (seenImages.size >= MAX_SEEN_IMAGES) seenImages.remove(seenImages.keys.first())
                ImageInfo(TextureCache.Image(bm.w, bm.h, ByteArray(bm.w * bm.h) { Pack.level(bm.pix[it].toInt() and 0xFF).toByte() }), 0, 0L)
            }
            info.uses++
            info.usage += bm.w.toLong() * bm.h
            info.lastUse = useClock
            if (info.uses == PACK_AFTER_USES) seenVersion++      // the shell's cue to grow the atlas
        }
        if (key !in liveImages) return false
        val e = atlas?.image(key) ?: return false
        blitImage(g, x, y, e.image, lv)
        if (onTarget) recordImage(x, y, e.image, key, lv)
        else if (onRelay) {
            val box = Rect(x + relayDx, y + relayDy, e.image.w, e.image.h)
            if (relayKeep.contains(box)) recordImage(box.x, box.y, e.image, key, lv)
        }
        return true
    }

    private fun recordImage(x: Int, y: Int, img: TextureCache.Image, key: Any, lv: Int) = synchronized(draws) {
        if (imageDraws.size >= MAX_DRAWS) imageDraws.removeAt(0)
        imageDraws.add(ImageDraw(Rect(x, y, img.w, img.h), key, x, y, lv))
    }

    private val seen = LinkedHashSet<FontSpec>()
    /** Where each spec was last drawn on the target — the shell's cue for
     *  which fonts sit on plane 0 (cached draws are flat) and so belong in
     *  the atlas first. */
    private val lastRect = HashMap<FontSpec, Rect>()
    /** Bumped when a spec is drawn into the target for the first time —
     *  the shell's cue to grow the atlas. */
    @Volatile var seenVersion = 0
        private set
    private val draws = ArrayList<TextDraw>()

    /** Resolved specs drawn into the target so far, first use first. */
    fun seenSpecs(): List<FontSpec> = synchronized(seen) { seen.toList() }

    /** The last rect [spec] was drawn at on the target, if ever. */
    fun lastRectOf(spec: FontSpec): Rect? = synchronized(seen) { lastRect[spec] }

    /** Pixels (glyph-box area) [spec] has drawn onto the target so far —
     *  the atlas packs the heaviest faces first (§41). */
    private val usage = HashMap<FontSpec, Long>()
    fun usageOf(spec: FontSpec): Long = synchronized(seen) { usage[spec] ?: 0L }

    /** The draws recorded since the last [endFrame]. */
    fun frameDraws(): List<TextDraw> = synchronized(draws) { draws.toList() }

    /** The compositor consumed the frame: records die with it. */
    fun endFrame() { synchronized(draws) { draws.clear(); imageDraws.clear() }; useClock++ }

    fun clearSeen() = synchronized(seen) { seen.clear(); seenImages.clear(); usage.clear(); lastUse.clear() }

    private fun liveEntry(spec: FontSpec): GlyphAtlas.Entry? =
        if (spec in live) atlas?.entry(spec) else null

    override fun measure(text: String, font: FontSpec): Int {
        val e = liveEntry(font) ?: return base.measure(text, font)
        var w = 0
        forEachRun(text) { run, cached ->
            if (cached) for (ch in run) w += e.images[ch.code - TextureCache.FIRST_CHAR].w
            else w += base.measure(run, font)
        }
        return w
    }

    /** §41: [text] as the cacheable RUNS the atlas draws and the characters
     *  between them the host draws — a status line's "·", an arrow. Runs
     *  are capped at the u8 length mode 14 carries. */
    private inline fun forEachRun(text: String, f: (run: String, cached: Boolean) -> Unit) {
        var i = 0
        while (i < text.length) {
            val c = text[i].code
            if (c in TextureCache.FIRST_CHAR..GlyphAtlas.LAST_GLYPH) {
                var j = i
                while (j < text.length && j - i < 0xFF && text[j].code in TextureCache.FIRST_CHAR..GlyphAtlas.LAST_GLYPH) j++
                f(text.substring(i, j), true)
                i = j
            } else {
                f(text.substring(i, i + 1), false)
                i++
            }
        }
    }

    override fun metrics(font: FontSpec): FontMetrics = base.metrics(font)
    override fun covers(text: String, font: FontSpec): Boolean = base.covers(text, font)

    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        val onTarget = surface === target
        val rel = relay
        val onRelay = !onTarget && rel != null && surface === rel
        if (onTarget || onRelay) synchronized(seen) {
            if (seen.add(font)) seenVersion++
            val r = Rect(x, y, maxOf(1, base.measure(text, font)), base.metrics(font).let { it.ascent + it.descent })
            lastRect[font] = r
            usage[font] = (usage[font] ?: 0L) + r.w.toLong() * r.h
            lastUse[font] = useClock
        }
        val e = liveEntry(font)
        if (e == null) {
            base.draw(surface, x, y, text, font, level)
            return
        }
        // §41: the cacheable runs blit from the atlas and are recorded; a
        // character the cache cannot hold (the status line's "·", an arrow)
        // is the host's, and the base delta carries its pixels — before, one
        // such character sent the whole string to pixels
        var px = x
        forEachRun(text) { run, cached ->
            if (cached) {
                val w = blit(surface, px, y, run, e, level)
                if (onTarget) record(px, y, w, e, run, font, level)
                else if (onRelay) {
                    // the draw lands on the target translated. A box the strip
                    // clips (a row half outside a 16 px strip) is recorded whole
                    // when it stays inside the band: the rest of the row is
                    // already on the target from the previous strip and the
                    // copies, and the proof checks every pixel of the box before
                    // a draw ships. A box that leaves the band claims pixels the
                    // blit never paints — dropped.
                    val box = Rect(px + relayDx, y + relayDy, w, e.lineH)
                    if (relayKeep.contains(box)) record(box.x, box.y, w, e, run, font, level)
                }
                px += w
            } else {
                base.draw(surface, px, y, run, font, level)
                px += base.measure(run, font)
            }
        }
    }

    private fun record(x: Int, y: Int, w: Int, e: GlyphAtlas.Entry, text: String, font: FontSpec, level: Int) =
        synchronized(draws) {
            if (draws.size >= MAX_DRAWS) draws.removeAt(0)
            draws.add(TextDraw(Rect(x, y, w, e.lineH), text, font, x, y, level))
        }

    private var relay: Gray8? = null
    private var relayDx = 0
    private var relayDy = 0
    private var relayKeep = Rect(0, 0, 0, 0)

    /** §41: draws into [tmp] inside [block] are recorded as if at ([dx],
     *  [dy]) on the target — a slide paints its strip into a temp and blits
     *  it there next, and the strips are where a notch's bytes are. Only a
     *  draw whose whole box lands inside [keep] (the band, in target
     *  coordinates) is recorded. On the loop, one at a time. */
    fun <T> via(tmp: Gray8, dx: Int, dy: Int, keep: Rect, block: () -> T): T {
        val prev = relay; val pdx = relayDx; val pdy = relayDy; val pk = relayKeep
        relay = tmp; relayDx = dx; relayDy = dy; relayKeep = keep
        try { return block() } finally { relay = prev; relayDx = pdx; relayDy = pdy; relayKeep = pk }
    }

    /** A temp painted INTO [parent] at ([dx], [dy]) — a row temp inside a
     *  strip temp, or straight into the target: the offsets compose. Outside
     *  either, nothing is recorded (the destination is unknown here). */
    fun <T> viaInto(parent: Gray8, tmp: Gray8, dx: Int, dy: Int, block: () -> T): T {
        val rel = relay
        return when {
            rel != null && parent === rel -> via(tmp, relayDx + dx, relayDy + dy, relayKeep, block)
            parent === target -> via(tmp, dx, dy, Rect(0, 0, parent.w, parent.h), block)
            else -> block()
        }
    }

    /** [r] on the target is about to be repainted whole: every record inside
     *  it is stale (§41 — a stale record only costs the proof, but a slide's
     *  settle repaints its whole band, and two records for one row would
     *  refuse the band for nothing). Records straddling [r] are dropped too. */
    fun dropRecords(r: Rect) = synchronized(draws) {
        draws.removeAll { it.rect.overlaps(r) }
        imageDraws.removeAll { it.rect.overlaps(r) }
    }

    /** The pixels of [src] moved to [dst] on the target (a slide's
     *  translation): records wholly inside [src] move with them; a record
     *  that straddles the edge, or sat where the copy landed, is dropped —
     *  its pixels are partly gone, and a stale record only costs the proof. */
    fun moveRecords(src: Rect, dst: Rect) = synchronized(draws) {
        val dx = dst.x - src.x; val dy = dst.y - src.y
        val it = draws.listIterator()
        while (it.hasNext()) {
            val d = it.next()
            if (!d.rect.overlaps(src) && !d.rect.overlaps(dst)) continue
            if (src.contains(d.rect)) it.set(TextDraw(d.rect.translate(dx, dy), d.text, d.spec, d.x + dx, d.y + dy, d.level))
            else it.remove()
        }
        val ii = imageDraws.listIterator()
        while (ii.hasNext()) {
            val d = ii.next()
            if (!d.rect.overlaps(src) && !d.rect.overlaps(dst)) continue
            if (src.contains(d.rect)) ii.set(ImageDraw(d.rect.translate(dx, dy), d.key, d.x + dx, d.y + dy, d.level))
            else ii.remove()
        }
    }

    companion object {
        /** Records kept per frame; a frame that paints more than this is a
         *  full repaint the pixel path serves anyway. */
        const val MAX_DRAWS = 1024
        /** An icon is packed once it has been drawn this often (§41). */
        const val PACK_AFTER_USES = 2
        /** Icons remembered between grows, oldest out first. */
        const val MAX_SEEN_IMAGES = 256

        /** The firmware's image draw (`cfw_texture_render`, TRANSPARENT): the
         *  same LUT as [blit], for a whole image. Shared with the proof. */
        fun blitImage(g: Gray8, x0: Int, y0: Int, img: TextureCache.Image, level: Int) {
            val top = Pack.level(level)
            val w = img.w
            for (p in img.levels.indices) {
                val s = img.levels[p].toInt()
                if (s == 0) continue
                val x = x0 + p % w
                val y = y0 + p / w
                if (x < 0 || y < 0 || x >= g.w || y >= g.h) continue
                g.pix[y * g.w + x] = ((s * top) / 15 * 17).toByte()
            }
        }

        /** Mode 14 draws 32..127 from a u8 string; DEL is tofu, so 32..126. */
        fun cacheable(text: String): Boolean =
            text.isNotEmpty() && text.length <= 0xFF && text.all { it.code in TextureCache.FIRST_CHAR..GlyphAtlas.LAST_GLYPH }

        /**
         * The firmware's draw (`cfw_texture_render` with TRANSPARENT): source
         * level 0 is skipped, every other level lands as `s × top / 15`
         * (integer), here as the 8-bit `k × 17` the wire's quantiser maps
         * back to k. Returns the pen advance. Shared by the recorder and the
         * compositor's re-render so the two can never disagree.
         */
        fun blit(g: Gray8, x0: Int, y0: Int, text: String, e: GlyphAtlas.Entry, level: Int): Int {
            val top = Pack.level(level)
            var px = x0
            for (ch in text) {
                val img = e.images[ch.code - TextureCache.FIRST_CHAR]
                val w = img.w
                for (p in img.levels.indices) {
                    val s = img.levels[p].toInt()
                    if (s == 0) continue
                    val x = px + p % w
                    val y = y0 + p / w
                    if (x < 0 || y < 0 || x >= g.w || y >= g.h) continue
                    g.pix[y * g.w + x] = ((s * top) / 15 * 17).toByte()
                }
                px += w
            }
            return px - x0
        }
    }
}
