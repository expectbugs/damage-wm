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
     *  glyph) and the box height every glyph shares. */
    class Entry(val font: TextureCache.Font, val images: Array<TextureCache.Image>, val lineH: Int)

    private val builder = TextureCache.Builder()
    private val entries = HashMap<FontSpec, Entry>()
    private val refused = HashSet<FontSpec>()

    /** Packed bytes the glasses hold — the upload watermark. */
    var sentBytes = TextureCache.GUARD
        private set

    val used: Int get() = builder.used
    fun entry(spec: FontSpec): Entry? = entries[spec]
    fun has(spec: FontSpec) = spec in entries
    fun isRefused(spec: FontSpec) = spec in refused
    fun specs(): Set<FontSpec> = entries.keys.toSet()

    /**
     * Render and pack [spec]. False, and remembered, when it does not fit
     * what is left of the 64 KiB (a fit is first come, first served — the
     * fonts a session draws first are the lens's, which is where the bytes
     * are). Glyphs are rendered at full level; the draw's LUT dims them.
     */
    fun add(spec: FontSpec): Boolean {
        if (spec in entries) return true
        if (spec in refused) return false
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
        return try {
            val font = builder.addFont(glyphs, tofu)
            val images = Array(CfwModes.FONT_TABLE_CHARS) { i -> glyphs[(i + TextureCache.FIRST_CHAR).toChar()] ?: tofu }
            entries[spec] = Entry(font, images, h)
            true
        } catch (e: LintError) {
            refused += spec
            Log.w("atlas", "font $spec stays pixels — the texture cache is full (${builder.used} B used): ${e.message}")
            false
        }
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
        }
        sentBytes = all.size
        return out
    }

    /** The glasses freed the cache (the lease lapsed): everything goes again. */
    fun forgetUpload() {
        sentBytes = TextureCache.GUARD
    }

    companion object {
        /** DEL (127) is in the table but is no glyph: the table entry is tofu. */
        const val LAST_GLYPH = 126
    }
}

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
class CachedText(val base: TextRasterizer) : TextRasterizer {
    @Volatile var atlas: GlyphAtlas? = null
    @Volatile var live: Set<FontSpec> = emptySet()
    @Volatile var target: Gray8? = null

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

    /** The draws recorded since the last [endFrame]. */
    fun frameDraws(): List<TextDraw> = synchronized(draws) { draws.toList() }

    /** The compositor consumed the frame: records die with it. */
    fun endFrame() = synchronized(draws) { draws.clear() }

    fun clearSeen() = synchronized(seen) { seen.clear() }

    private fun liveEntry(spec: FontSpec): GlyphAtlas.Entry? =
        if (spec in live) atlas?.entry(spec) else null

    override fun measure(text: String, font: FontSpec): Int {
        val e = liveEntry(font)
        if (e == null || !cacheable(text)) return base.measure(text, font)
        var w = 0
        for (ch in text) w += e.images[ch.code - TextureCache.FIRST_CHAR].w
        return w
    }

    override fun metrics(font: FontSpec): FontMetrics = base.metrics(font)
    override fun covers(text: String, font: FontSpec): Boolean = base.covers(text, font)

    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        val onTarget = surface === target
        if (onTarget) synchronized(seen) {
            if (seen.add(font)) seenVersion++
            lastRect[font] = Rect(x, y, maxOf(1, base.measure(text, font)), base.metrics(font).let { it.ascent + it.descent })
        }
        val e = liveEntry(font)
        if (e == null || !cacheable(text)) {
            base.draw(surface, x, y, text, font, level)
            return
        }
        val w = blit(surface, x, y, text, e, level)
        if (onTarget) synchronized(draws) {
            if (draws.size >= MAX_DRAWS) draws.removeAt(0)
            draws.add(TextDraw(Rect(x, y, w, e.lineH), text, font, x, y, level))
        }
    }

    companion object {
        /** Records kept per frame; a frame that paints more than this is a
         *  full repaint the pixel path serves anyway. */
        const val MAX_DRAWS = 512

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
