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
 * The shape. A [GlyphAtlas] renders a resolved font's glyphs
 * ONCE each, through the host's own rasterizer, into an advance-width box the
 * face's ink height tall (`TextureCache`: mode 14 advances by the cached
 * image's width and nothing else), quantised to the wire's 4 bits, and packs
 * them with a 96-entry table; the shell uploads the packed bytes with mode
 * 12 once per lease. A [CachedText] wraps the host rasterizer: a string in a
 * font the glasses HOLD is blitted from those same glyph images, pixel for
 * pixel as the firmware will draw it (source 0 skipped — the TRANSPARENT
 * option — every other level through the LUT `s × top / 15`), and recorded
 * as a [TextDraw]; everything else draws as before. The compositor, for a
 * dirty rect, builds what the glasses would hold after a BASE delta — the
 * composed pixels with every pixel a draw writes black — and the recorded
 * draws on top of it, and ONLY when that equals each lens's truth byte for
 * byte ships the base and the strings as draws instead of the pixels
 * (`Compositor.emitCached`). So the path is exact by construction and
 * opportunistic by construction: a font not on the glasses, a character its
 * table holds no glyph for, something painted over the text after it was
 * drawn — each simply stays pixels. Since APK 0.64 what lies UNDER a draw's
 * box (a rule, a lit cell) rides the base: the base is black only where a
 * draw writes ([blit]'s `hole`), where it used to be black over the whole
 * box, and a rect whose base then carries lit pixels is priced against the
 * pixels it replaces (`dearer`), so text on a lit field ships as draws only
 * when that is the cheaper message.
 *
 * WHICH glyphs (`HANDOFF.md` §73, 2026-11-09): the ones the face has drawn.
 * Until then a face was packed whole — every Latin-1 code on a v2 table,
 * 13–18 KB a face of which a day draws a fifth; the cache held seven faces
 * and repacked on every window change with all text as pixels for seconds.
 * Now [CachedText] notes every character each face draws
 * ([CachedText.codesFor]), the atlas packs a face with those, a character it
 * was not packed with is the host's pixels on the flush that reveals it (the
 * path a code no table can hold has always taken), and the face then GAINS
 * it off the gesture path: the record appended at the cache's end, the
 * table's entry rewritten in place ([GlyphAtlas.extend]), live once the
 * glasses hold both ([GlyphAtlas.promote]).
 *
 * Depth planes. Modes 13/14 ignore the batch's "lenses differ" bit and draw
 * at one x into each lens's shadow (`zlib_glue.c`), so a contract-1 draw is
 * always flat: a rect on a depth plane ships as a flat base widened by the
 * disparity, the draws at their nominal x and one per-lens copy (§41,
 * `Compositor.emitCachedAt`). Contract 2 places every draw per lens (modes
 * 17/18, the last paragraph below). This comment said "disparity 0 only"
 * until 0.64; that had not been true since §41.
 *
 * What changes visibly: cached text advances by integer glyph widths (with
 * the platform's pair kerning as adjust bytes since Phase 2 — the same glyph
 * bitmaps a pixel apart here and there where the platform has no kerning),
 * and a level's ramp is the LUT's integer one. Belief and glass agree on both
 * by construction; the design's eye may not — the Global `Cached text` row
 * is off until it has been seen on glass.
 *
 * Contract 2 (`FIRMWARE.md` §4, a [GlyphAtlas] built with [GlyphAtlas.v2]): the
 * same glyph images behind 224-entry tables (Latin-1), icons as v2 records, the
 * cache the session's size (op 5), and every draw placed PER LENS by mode 17/18 —
 * `Compositor.emitCachedV2` ships a rect on a depth plane as one stereo base delta
 * plus the draws at each lens's own x, no widening and no copy.
 */
class GlyphAtlas(private val base: TextRasterizer, val v2: Boolean = false, val capacity: Int = CfwModes.TEXTURE_CACHE_SIZE) {

    /**
     * One packed font: its table, one image per table entry (the tofu where the entry holds no
     * glyph), the box height every glyph shares, the rendered glyphs and tofu a repack re-places
     * without rendering again (§47), and the bytes it took (dedup makes that an upper bound).
     *
     * Since `HANDOFF.md` §73 a face holds only the glyphs it was packed with: [packed] says which
     * table entries name a real glyph, and it is the ONLY thing that separates a glyph from the
     * tofu downstream — belief ([CachedText.blit]) and the wire (`TextureCache.layout`) both
     * resolve an entry to whatever image it names, so a code drawn through the cache without
     * being packed is a hollow box on the lens that no proof can see. Hence the constructor's
     * check, and the recorder's split on [packs]. [needEnd] is the end of the highest packed byte
     * this entry's table or glyph records need: a face that gained glyphs has records ABOVE its
     * table, so "the table is acked" stopped meaning "the font is on the glasses".
     *
     * Immutable: a face that gains glyphs gets a new Entry (`measure` reads entries off the loop).
     */
    class Entry(val font: TextureCache.Font, val images: Array<TextureCache.Image>, val lineH: Int,
        val glyphs: Map<Char, TextureCache.Image>, val tofu: TextureCache.Image, val bytes: Int,
        val packed: BooleanArray, val needEnd: Int) {
        init {
            if (images.size != font.glyphOffsets.size || packed.size != images.size)
                throw LintError("a font entry of ${images.size} images, ${packed.size} flags and a ${font.glyphOffsets.size}-entry table")
            var n = 0
            for (i in packed.indices) {
                val ch = (i + TextureCache.FIRST_CHAR).toChar()
                val real = images[i] !== tofu
                if (packed[i] != real || real != (ch in glyphs))
                    throw LintError("font entry: U+%04X is %s but its table entry holds %s".format(ch.code,
                        if (packed[i]) "marked packed" else "not marked packed", if (real) "a glyph" else "the tofu"))
                if (packed[i] && !cacheableCode(ch.code, font.v2))
                    throw LintError("font entry: U+%04X is packed, and a cached run never holds it".format(ch.code))
                if (packed[i]) n++
            }
            if (glyphs.size != n) throw LintError("font entry: ${glyphs.size} glyphs for $n packed codes")
            if (needEnd < font.tableOffset + font.glyphOffsets.size * 2)
                throw LintError("font entry: needEnd $needEnd is below its own table's end")
        }

        /** True when [ch] has a real glyph in this entry's table. */
        fun packs(ch: Char): Boolean { val i = ch.code - TextureCache.FIRST_CHAR; return i >= 0 && i < packed.size && packed[i] }
        fun packs(text: String): Boolean { for (ch in text) if (!packs(ch)) return false; return true }
        val packedCount: Int get() = glyphs.size
    }

    /** What the host rasterizer gave for a face so far: glyphs by code, the codes it has no glyph
     *  for ([absent] — asked once, never again), the tofu and the box height. Kept across a
     *  repack; dropped with an evicted face. */
    private class Face(val lineH: Int, val tofu: TextureCache.Image) {
        val glyphs = HashMap<Char, TextureCache.Image>()
        val absent = java.util.BitSet()
    }

    /**
     * A face's glyphs appended and its table patched in the builder's image, waiting for the
     * glasses to hold both (`HANDOFF.md` §73). [entry] is the face as it will stand — the same
     * table offset, the new entries — and it replaces the live one ([promote]) only after the
     * message carrying the last of [spans] is acked ([ackedAt] = the count of acked upload
     * messages then, -1 before). At most one per face.
     */
    class Ext(val spec: FontSpec, val entry: Entry, val spans: List<IntRange>, val codes: String, val bytes: Int) {
        var handedOut = false
            internal set
        var ackedAt = -1
            internal set
    }

    /** What [extend] did. */
    sealed class Extended {
        /** Nothing to do: every code is packed already, or the face has no glyph for it. */
        object None : Extended()
        /** An extension of this face is still on its way; the codes wait for it. */
        object Busy : Extended()
        /** Nothing was written. [need] > 0: no room for that many bytes — remembered until
         *  [unrefuseAll] or a repack; the atlas never repacks for it, the shell may evict the faces
         *  nobody is drawing for a face in use (`Shell.atlasGrow`). [need] == 0: the glyphs could
         *  not be rendered at the face's box height — not a room refusal, never retried. [first]:
         *  this call is the one that found it out (the journal says it once). */
        class Refused(val need: Int, val free: Int, val codes: String, val first: Boolean) : Extended()
        /** The glyphs were byte-identical to what the table already named: live at once, nothing to send. */
        class Live(val codes: String) : Extended()
        /** Appended and patched in the image; [takeUpload] hands the bytes out. */
        class Queued(val ext: Ext) : Extended()
    }

    private var builder = TextureCache.Builder(v2, capacity)
    /** Read off the loop by `measure` (a layout thread): a concurrent map, and an entry is never mutated. */
    private val entries: MutableMap<FontSpec, Entry> = java.util.concurrent.ConcurrentHashMap()
    private val refused = HashSet<FontSpec>()
    private val faces = HashMap<FontSpec, Face>()
    private val pending = LinkedHashMap<FontSpec, Ext>()
    private val extRefused = HashSet<FontSpec>()
    /** Faces whose new glyphs could not be RENDERED at the face's box height: not a room refusal,
     *  nothing a retry or an eviction can change — for the life of this atlas. */
    private val extBroken = HashSet<FontSpec>()

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
        // a v2 cache holds icons as v2 records (u16 dims, mode 17); the blit reads the levels either way
        val enc = if (v2) TextureCache.Image2(img.w, img.h, img.levels).encode() else img.encode()
        if (imageBytes + enc.size > imageBudget || builder.free < enc.size + 3) {
            refusedImages += key
            Log.w("atlas", "icon $key (${img.w}x${img.h}, ${enc.size} B) stays pixels — icons hold $imageBytes B of $imageBudget, ${builder.free} B free")
            return false
        }
        val off = builder.addEncoded(enc)
        images[key] = ImageEntry(off, img, enc.size)
        imageBytes += enc.size
        return true
    }

    /** Where an upload of the whole atlas starts: past the guard (2 B on a v1 cache, 4 on a v2 one). */
    val uploadStart: Int get() = builder.guard
    /** Packed bytes queued for the glasses — the upload watermark. */
    var sentBytes = builder.guard
        private set
    /** Packed bytes the glasses have ACKED (§41): a font whose bytes end
     *  below this is on the glasses whatever the setting did since. */
    var ackedBytes = builder.guard
        private set
    /** Upload messages acked over this atlas's life, never reset (`HANDOFF.md` §73): the
     *  read-back gate is owed by this as well as by [ackedBytes], because a message that only
     *  rewrites table entries acks without moving a byte mark. */
    var ackedMsgs = 0
        private set
    /** Icons take at most a quarter of the cache — a lens icon is ~0.4–1 KB
     *  packed, a row icon ~150 B; fonts are where the bytes are. */
    val imageBudget: Int get() = capacity / 4
    /** What the ack of each message [takeUpload] handed out completes, in order: the packed
     *  bytes up to [end], the extensions whose last table span rode in it, the image ranges the
     *  message wrote ([writes]), whether it was a re-send and the queued re-sends it completes. */
    private class Done(val end: Int, val exts: List<Ext>, val writes: List<IntRange>,
        val resend: Boolean, val resent: List<Resend>)
    private val chunkEnds = ArrayDeque<Done>()

    /** What one acked upload message wrote — image ranges, appended bytes and table spans alike (a
     *  cache write is a plain copy at an offset) — and whether the message was itself a re-send
     *  (0.64: the record a count shortfall needs to write a message once more; before it nothing
     *  kept what a message wrote past its ack, nor a promoted extension's spans). [n] is the
     *  atlas's count of acked messages with this one. */
    class Acked(val n: Int, val writes: List<IntRange>, val resend: Boolean) {
        val bytes: Int get() = writes.sumOf { it.last - it.first + 1 }
    }
    /** One image range queued to be written once more ([resend]); gone at the ack of the message
     *  that carries its last piece, at a lapse and at a repack. */
    private class Resend(val range: IntRange) { var handedOut = false }
    private val resends = ArrayList<Resend>()
    /** Ranges queued to go once more and not yet acked. */
    val resendsQueued: Int get() = resends.size

    /**
     * Queue image [ranges] of the layout that stands to be written once more (0.64: the glasses
     * acked a message and their generation count did not move for it). Returns the bytes queued.
     * [takeUpload] cuts them into messages of their own, from the image AS IT STANDS at the cut.
     * A range outside the packed bytes is refused loudly; an empty one, or one equal to a range
     * already queued, is skipped. Ranges are NOT merged: a table span keeps its own start, so a
     * cut never splits a table entry. Moves no mark, bumps no generation, changes no entry.
     */
    fun resend(ranges: List<IntRange>): Int {
        var bytes = 0
        for (r in ranges) {
            if (r.isEmpty()) continue
            if (r.first < builder.guard || r.last >= builder.used)
                throw LintError("a re-send of $r lies outside the packed bytes ${builder.guard}..${builder.used - 1}")
            if (resends.any { it.range == r }) continue
            resends += Resend(r)
            bytes += r.last - r.first + 1
        }
        return bytes
    }

    val used: Int get() = builder.used
    val free: Int get() = builder.free
    /** The packed image as it stands, guard included (tests decode tables and replay uploads against it). */
    internal fun contentForTest(): ByteArray = builder.content()
    fun entry(spec: FontSpec): Entry? = entries[spec]
    fun has(spec: FontSpec) = spec in entries
    fun isRefused(spec: FontSpec) = spec in refused
    fun specs(): Set<FontSpec> = entries.keys.toSet()
    /** True when every byte [spec]'s entry needs — its table AND its glyph records, wherever
     *  they sit — has been acked by the glasses. */
    fun isAcked(spec: FontSpec): Boolean = entries[spec]?.let { it.needEnd <= ackedBytes } ?: false

    /** The extension of [spec] on its way, if any. */
    fun pendingExt(spec: FontSpec): Ext? = pending[spec]
    fun pendingExts(): List<Ext> = pending.values.toList()
    fun isExtRefused(spec: FontSpec) = spec in extRefused

    private fun boxHeight(spec: FontSpec): Int =
        base.metrics(spec).let { (it.ascent + it.descent).coerceIn(1, CfwModes.MAX_TEXTURE_DIM) }

    /** The host's glyphs for [codes] of [spec], rendered once each and kept: an advance-width
     *  box the face's ink height tall, quantised to the wire's 4 bits. A code the face has no
     *  glyph for (or whose advance no record can hold) lands in `absent`. Every glyph of a face
     *  shares one box height: the firmware draws a string's records at one top y and checks no
     *  height, so a face whose metrics moved since its first glyph is refused loudly. */
    private fun render(spec: FontSpec, codes: java.util.BitSet): Face {
        val t0 = System.nanoTime()
        try { return renderTimed(spec, codes) } finally {
            wm.damage.core.text.TextProfile.faceRenders.incrementAndGet()          // package G (E4): the `slow` note's count
            wm.damage.core.text.TextProfile.faceRenderNs.addAndGet(System.nanoTime() - t0)
        }
    }

    private fun renderTimed(spec: FontSpec, codes: java.util.BitSet): Face {
        val h = boxHeight(spec)
        val f = faces.getOrPut(spec) {
            // the tofu: a hollow box — a missing glyph must look wrong, not like nothing
            val q = if (base.covers("?", spec)) base.measure("?", spec) else 0
            val tw = (if (q in 1..CfwModes.MAX_TEXTURE_DIM) q else 8).coerceIn(3, CfwModes.MAX_TEXTURE_DIM)
            Face(h, TextureCache.Image(tw, h, ByteArray(tw * h) { i ->
                val x = i % tw; val y = i / tw
                if (x == 0 || x == tw - 1 || y == 0 || y == h - 1) 15 else 0
            }))
        }
        if (f.lineH != h) throw LintError("font $spec: its ink height is $h now and was ${f.lineH} when its first glyphs were rendered")
        var c = codes.nextSetBit(0)
        while (c >= 0) {
            val ch = c.toChar()
            if (cacheableCode(c, v2) && ch !in f.glyphs && !f.absent.get(c)) {
                val s = ch.toString()
                val adv = if (base.covers(s, spec)) base.measure(s, spec) else 0
                if (adv < 1 || adv > CfwModes.MAX_TEXTURE_DIM) f.absent.set(c)
                else {
                    val g = Gray8(adv, h)
                    base.draw(g, 0, 0, s, spec, 255)
                    f.glyphs[ch] = TextureCache.Image(adv, h, ByteArray(adv * h) { Pack.level(g.pix[it].toInt() and 0xFF).toByte() })
                }
            }
            c = codes.nextSetBit(c + 1)
        }
        return f
    }

    /** [f]'s rendered glyphs for exactly [codes] (cacheable ones; a code with no glyph is left out). */
    private fun glyphsFor(f: Face, codes: java.util.BitSet): Map<Char, TextureCache.Image> {
        val out = HashMap<Char, TextureCache.Image>()
        var c = codes.nextSetBit(0)
        while (c >= 0) {
            if (cacheableCode(c, v2)) f.glyphs[c.toChar()]?.let { out[c.toChar()] = it }
            c = codes.nextSetBit(c + 1)
        }
        return out
    }

    private fun entryOf(font: TextureCache.Font, f: Face, glyphs: Map<Char, TextureCache.Image>, bytes: Int): Entry {
        val n = font.glyphOffsets.size
        return Entry(font, Array(n) { i -> glyphs[(i + TextureCache.FIRST_CHAR).toChar()] ?: f.tofu }, f.lineH, glyphs, f.tofu, bytes,
            BooleanArray(n) { i -> (i + TextureCache.FIRST_CHAR).toChar() in glyphs }, builder.used)
    }

    /** The bytes [spec] would take in the cache as it stands, packed with [codes] (table
     *  included, records already present counted once) — what an eviction must free. */
    fun price(spec: FontSpec, codes: java.util.BitSet): Int =
        entries[spec]?.bytes ?: render(spec, codes).let { builder.priceFont(glyphsFor(it, codes), it.tofu) }

    /** The bytes [spec] took when it was packed, with whatever it gained since; 0 when it is not resident. */
    fun bytesOf(spec: FontSpec): Int = (pending[spec]?.entry ?: entries[spec])?.bytes ?: 0

    /** §47: forget every refusal — the shell retries a refused face once the
     *  faces around it have gone stale enough to evict (a refusal is a
     *  short-cut past re-pricing, never a verdict for the session). */
    fun unrefuseAll() { refused.clear(); refusedImages.clear(); extRefused.clear() }

    /**
     * Render and pack [spec] with the glyphs for [codes] — the characters the face has drawn
     * (`CachedText.codesFor`; `HANDOFF.md` §73: a face packed whole is 13–18 KB of which a day
     * draws a fifth, and the cache held seven). False, and remembered, when it does not fit
     * what is left (a fit is first come, first served). Glyphs are rendered at full level; the
     * draw's LUT dims them. Every table entry is filled: a code outside [codes] names the tofu
     * and is the host's to draw until [extend] gives it a glyph.
     */
    fun add(spec: FontSpec, codes: java.util.BitSet): Boolean {
        if (spec in entries) return true
        if (spec in refused) return false
        return try {
            val f = render(spec, codes)
            val glyphs = glyphsFor(f, codes)
            val before = builder.used
            val font = builder.addFont(glyphs, f.tofu)
            entries[spec] = entryOf(font, f, glyphs, builder.used - before)
            true
        } catch (e: LintError) {
            refused += spec
            Log.w("atlas", "font $spec stays pixels — the texture cache is full (${builder.used} B used): ${e.message}")
            false
        }
    }

    /** The codes of [codes] a resident [spec] could still gain: cacheable, not packed (nor in
     *  the extension on its way), and not known to have no glyph. Empty for a face not here. */
    fun missing(spec: FontSpec, codes: java.util.BitSet): java.util.BitSet {
        val out = java.util.BitSet()
        val cur = (pending[spec]?.entry ?: entries[spec]) ?: return out
        val absent = faces[spec]?.absent
        var c = codes.nextSetBit(0)
        while (c >= 0) {
            if (cacheableCode(c, v2) && !cur.packs(c.toChar()) && absent?.get(c) != true) out.set(c)
            c = codes.nextSetBit(c + 1)
        }
        return out
    }

    /**
     * `HANDOFF.md` §73: give a resident [spec] glyphs for the codes of [codes] it lacks. The
     * records go at the end of the packed bytes and the face's table entries are rewritten in
     * place in the builder's image (`TextureCache.Builder.extendFont`); the entry the recorder
     * and the compositor read does NOT change here — the glasses do not hold the bytes yet —
     * so those codes stay the host's until [promote]. One extension per face at a time
     * ([Extended.Busy]): the codes that turn up meanwhile go in the next one. An extension
     * that does not fit writes nothing and is remembered ([Extended.Refused]) until
     * [unrefuseAll] or a repack; the atlas never repacks for it by itself.
     */
    fun extend(spec: FontSpec, codes: java.util.BitSet): Extended {
        val cur = entries[spec] ?: return Extended.None
        if (spec in pending) return Extended.Busy
        val want = missing(spec, codes)
        if (want.isEmpty) return Extended.None
        if (spec in extBroken) return Extended.Refused(0, builder.free, codeString(want), false)
        // said once; not priced again on every grow (need -1: unknown, and not 0 — it IS a room refusal)
        if (spec in extRefused) return Extended.Refused(-1, builder.free, codeString(want), false)
        val f = try { render(spec, want) } catch (e: LintError) {
            extBroken += spec
            Log.e("atlas", "font $spec cannot gain glyphs: ${e.message}")
            return Extended.Refused(0, builder.free, codeString(want), true)
        }
        val fresh = glyphsFor(f, want)
        if (fresh.isEmpty()) return Extended.None          // the face has no glyph for any of them: asked once
        val names = fresh.keys.sorted().joinToString("")
        val before = builder.used
        val (font, spans) = try { builder.extendFont(cur.font, fresh) } catch (e: LintError) {
            extRefused += spec
            return Extended.Refused(builder.priceRecords(fresh.values), builder.free, names, true)
        }
        val grown = builder.used - before
        val entry = entryOf(font, f, cur.glyphs + fresh, cur.bytes + grown)
        if (spans.isEmpty()) {
            // every new glyph is byte-identical to the record its entry already named (the tofu):
            // the glasses hold the face as it now stands, nothing to send and nothing to wait for
            check(grown == 0) { "an extension of $spec appended $grown B and changed no table entry" }
            entries[spec] = entry
            return Extended.Live(names)
        }
        val ext = Ext(spec, entry, spans, names, grown)
        pending[spec] = ext
        return Extended.Queued(ext)
    }

    private fun codeString(codes: java.util.BitSet): String = buildString {
        var c = codes.nextSetBit(0)
        while (c >= 0) { append(c.toChar()); c = codes.nextSetBit(c + 1) }
    }

    /**
     * The acked extensions become the faces' entries: from here the recorder and the compositor
     * draw those codes from the cache. The caller (`Shell.goLive`) runs only when every acked
     * message is covered by a read-back or none can be had — and what that proves is the
     * cache's presence, size and flags, as for a font's first upload; NOT that the glasses
     * applied this message (the ack precedes the decode). On the loop only: the compositor
     * reads an entry twice per draw (the op and the proof's re-render) and the two must be one
     * entry.
     */
    fun promote(): List<Ext> {
        val out = ArrayList<Ext>()
        val it = pending.values.iterator()
        while (it.hasNext()) {
            val x = it.next()
            if (x.ackedAt < 0) continue
            entries[x.spec] = x.entry
            it.remove()
            out += x
        }
        return out
    }

    /** Every pending extension becomes its face's entry at once — only where NOTHING is live and
     *  every byte is about to go up again (a lapse, a repack): the image already holds the
     *  patched tables, and [isAcked] stays false until [Entry.needEnd] is acked again. */
    private fun foldPending() {
        for (x in pending.values) entries[x.spec] = x.entry
        pending.clear()
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
     *
     * An extension on its way is folded into its face FIRST (`HANDOFF.md` §73): the rebuild
     * below re-places each kept face's glyphs at new offsets, and an extension folded after it
     * would put the old layout's table offset back over the rebuilt entry.
     */
    fun repack(keepFonts: List<FontSpec>, keepImages: List<Any>): Int {
        foldPending()
        val fonts = keepFonts.mapNotNull { s -> entries[s]?.let { s to it } }
        val icons = keepImages.mapNotNull { k -> images[k]?.let { k to it.image } }
        builder = TextureCache.Builder(v2, capacity)
        entries.clear(); refused.clear(); extRefused.clear(); extBroken.clear()
        images.clear(); refusedImages.clear(); imageBytes = 0
        faces.keys.retainAll(fonts.map { it.first }.toSet())     // an evicted face is rendered again if it returns
        for ((s, e) in fonts) {
            try {
                val f = faces[s] ?: throw LintError("no rendered face for kept font $s")
                val before = builder.used
                val font = builder.addFont(e.glyphs, e.tofu)
                entries[s] = entryOf(font, f, e.glyphs, builder.used - before)
            } catch (x: LintError) {
                refused += s
                Log.e("atlas", "repack: kept font $s no longer fits (${builder.used} B used) — refused: ${x.message}")
            }
        }
        for ((k, img) in icons) if (!addImage(k, img)) Log.e("atlas", "repack: kept icon $k no longer fits")
        forgetUpload()
        return builder.used
    }

    /**
     * The cache-write messages for everything not yet handed out, each at most [maxMessage] B,
     * and the watermark moved past them: the packed bytes past [sentBytes] in order, then the
     * table spans of every extension not yet sent. A message is ONE mode-12 / mode-19 message
     * (the shell submits one per flush and counts one generation bump for it); the spans ride
     * the message that carries the last appended bytes when they fit, so an extension is
     * normally a single flush. Never an empty message.
     *
     * 0.64: ranges queued by [resend] follow in messages of their OWN — never sharing one with
     * first-time bytes, so the ack of such a message says "a re-send" and nothing else. A normal
     * upload's messages are byte-identical to what they were without a re-send queued.
     */
    fun takeUpload(maxMessage: Int = 3072): List<ByteArray> {
        val exts = pending.values.filter { !it.handedOut }
        if (builder.used <= sentBytes && exts.isEmpty() && resends.all { it.handedOut }) return emptyList()
        // refused BEFORE anything is cut: under 9 B a message cannot hold one aligned unit behind
        // its own bytes, and the cut used to throw part-way with chunk ends already recorded
        if (maxMessage < builder.messageOverhead + builder.writeOverhead + 4)
            throw LintError("cache chunk size $maxMessage leaves no room for data")
        val all = builder.content()
        val out = ArrayList<ByteArray>()
        var writes = ArrayList<Pair<Int, ByteArray>>()
        var size = builder.messageOverhead
        var done = ArrayList<Ext>()
        var resentDone = ArrayList<Resend>()
        var pos = sentBytes
        fun flush(resend: Boolean = false) {
            if (writes.isEmpty()) return
            out += builder.message(writes)
            // a re-send moves no byte mark: its end is the guard, below every acked mark
            chunkEnds.addLast(Done(if (resend) builder.guard else pos, done,
                writes.map { (off, d) -> off until off + d.size }, resend, resentDone))
            writes = ArrayList(); size = builder.messageOverhead; done = ArrayList(); resentDone = ArrayList()
        }
        while (pos < all.size) {
            val n = builder.chunkLen(all.size, pos, maxMessage)
            writes += pos to all.copyOfRange(pos, pos + n)
            size += builder.writeOverhead + n
            pos += n
            if (pos < all.size) flush()          // the message with the last appended bytes stays open for the spans
        }
        val unit = if (v2) 4 else 2
        for (x in exts) {
            for (span in x.spans) {
                var a = span.first
                while (a <= span.last) {
                    if (maxMessage - size - builder.writeOverhead < unit) flush()
                    var n = minOf(maxMessage - size - builder.writeOverhead, span.last + 1 - a)
                    n -= n % unit
                    if (n <= 0) throw LintError("a $maxMessage B message carries no table span")
                    writes += a to builder.read(a until a + n)
                    size += builder.writeOverhead + n
                    a += n
                }
            }
            x.handedOut = true
            done += x                            // complete when the message being built is acked
        }
        flush()
        sentBytes = all.size
        // the re-sends not yet handed out, after the first-time bytes and in messages of their own.
        // The image as it stands, not bytes kept from the first send: a later extension of the same
        // face may have rewritten the same table unit in place; the newer bytes are a superset
        // (entries only gain codes inside one layout), and an entry naming a record the glasses do
        // not hold yet is not drawn from until its own promotion.
        for (r in resends) {
            if (r.handedOut) continue
            var a = r.range.first
            while (a <= r.range.last) {
                if (maxMessage - size - builder.writeOverhead < unit) flush(resend = true)
                var n = minOf(maxMessage - size - builder.writeOverhead, r.range.last + 1 - a)
                if (a + n <= r.range.last) n -= n % unit       // only a range's last piece may end off a unit
                if (n <= 0) throw LintError("a $maxMessage B message carries no re-sent bytes")
                writes += a to builder.read(a until a + n)
                size += builder.writeOverhead + n
                a += n
            }
            r.handedOut = true
            resentDone += r                      // complete when the message being built is acked
        }
        flush(resend = true)
        return out
    }

    /** The layout these chunks belong to. A repack — or a lapse that forgets the upload — re-lays
     *  the packed bytes out, so a chunk handed out before it carries bytes the glasses would now
     *  be given at different offsets: its ack must not move this layout's watermark (2026-09-15,
     *  the second review — 12 old chunks' acks used to consume 12 new chunk ends, and every font
     *  below that mark went live while the glasses held the old bytes). Since `HANDOFF.md` §73 a
     *  REWIND is a new generation too: the messages cut before it are cut again from the acked
     *  mark, and the ack of an old one still in flight would otherwise complete a new one. */
    var uploadGen = 0
        private set

    /** One message acked, in the order [takeUpload] handed them out; [gen] is the generation it
     *  was taken from. A message of an older generation moves nothing. Returns what the message
     *  wrote, or null when it moved nothing (another generation, no outstanding entry). A re-send
     *  counts in [ackedMsgs] like any message, so the read-back gate asks its own round trip. */
    fun acked(gen: Int = uploadGen): Acked? {
        if (gen != uploadGen) return null
        val d = chunkEnds.removeFirstOrNull()
        if (d == null) {
            // never a quiet no-op: an ack with no outstanding chunk end means the queue was
            // cleared under an in-flight chunk, and the watermark would silently stand still
            wm.damage.core.util.Log.w("atlas", "a chunk ack arrived with no outstanding chunk end (layout $gen) — the acked watermark stands at $ackedBytes B")
            return null
        }
        ackedBytes = maxOf(ackedBytes, d.end)
        ackedMsgs++
        for (x in d.exts) if (pending[x.spec] === x) x.ackedAt = ackedMsgs
        resends.removeAll(d.resent)
        return Acked(ackedMsgs, d.writes, d.resend)
    }

    /** The messages past the acked watermark may or may not have landed (the link ended
     *  with their acks owed — §54 — or the setting went off under them): they go again from
     *  that mark, and so does the table patch of every extension not yet promoted. A cache
     *  write is a plain copy at an offset, so one that did land is rewritten with its own bytes. */
    fun rewindToAcked() {
        sentBytes = ackedBytes
        chunkEnds.clear()
        for (x in pending.values) { x.handedOut = false; x.ackedAt = -1 }
        for (r in resends) r.handedOut = false         // a re-send cut and not acked goes again, like a table patch
        uploadGen++                                    // an ack of a message cut before this completes nothing
    }

    /** The glasses freed the cache (the lease lapsed), or a repack re-laid it: everything goes
     *  again. Nothing is live at either, so an extension on its way is simply the face now. */
    fun forgetUpload() {
        foldPending()
        sentBytes = builder.guard
        ackedBytes = builder.guard
        chunkEnds.clear()
        resends.clear()                                // everything goes again from the guard anyway
        uploadGen++                                    // chunks handed out before this belong to the old layout
    }

    companion object {
        /** DEL (127) is in the table but is no glyph: the table entry is tofu. */
        const val LAST_GLYPH = 126
        /** §41: icons take at most a quarter of a 64 KiB cache — a lens icon is ~0.4–1 KB
         *  packed, a row icon ~150 B; fonts are where the bytes are. */
        const val IMAGE_BUDGET = 16 * 1024

        /** The codes a cached run may hold: 32..126 on a v1 atlas, Latin-1 on a v2 one but DEL, the C1
         *  controls and the soft hyphen — codes the platform draws as nothing, which the table could only
         *  hold as the tofu box (2026-09-15 review); the host draws them as it always did. */
        fun cacheableCode(c: Int, v2: Boolean): Boolean =
            if (v2) c in TextureCache.FIRST_CHAR..TextureCache.LAST_CHAR2 && c !in 127..159 && c != 0xAD
            else c in TextureCache.FIRST_CHAR..LAST_GLYPH

        /** Every code a table of this contract can hold a glyph for — a whole face, as an atlas
         *  packed every face before `HANDOFF.md` §73. The shell never asks for this; tools and
         *  tests that want a face with nothing left to gain do. */
        fun allCodes(v2: Boolean): java.util.BitSet = java.util.BitSet().also { b ->
            for (c in TextureCache.FIRST_CHAR..TextureCache.LAST_CHAR2) if (cacheableCode(c, v2)) b.set(c)
        }
    }
}

/** One icon blitted from the cache into the shell's surface (§41). */
class ImageDraw(val rect: Rect, val key: Any, val x: Int, val y: Int, val level: Int)

/** One string blitted from the atlas into the shell's surface. [rect] is
 *  the glyph boxes' union — exactly the pixels the blit could have touched. [keep] > 0 marks a
 *  run that FOLLOWS a character the host drew in the same string (`HANDOFF.md` §73): the first
 *  [keep] columns from the run's pen may hold that character's ink (a rasterizer's mask runs
 *  past the advance — an `f`, most of an italic face). [keepEnd] is the same for a run that
 *  PRECEDES a host-drawn character: its last columns may hold that character's ink — a glyph
 *  whose mask starts left of its pen, or one a pair kern tucked in.
 *
 *  Until 0.63 the compositor's base blacked a draw's whole box and these columns were the
 *  exception it left as composed. Since 0.64 the base is black only where a draw writes
 *  ([CachedText.blit]'s `hole`), so the two fields no longer steer the base, and the proof's
 *  verdict does not depend on them. They are still recorded and still read: they say which
 *  columns 0.63 already left to the base, which is how the price guard tells a rect that shipped
 *  as draws on 0.63 from one that proves only since 0.64 (`Compositor.oldBox`, `dearer`). */
class TextDraw(val rect: Rect, val text: String, val spec: FontSpec, val x: Int, val y: Int, val level: Int,
    val keep: Int = 0, val keepEnd: Int = 0)

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
    /** The icons drawn within the last [window] frames that have been drawn often enough to pack (a copy). */
    fun imagesInUse(window: Long): List<Any> = synchronized(seen) {
        seenImages.filter { it.value.uses >= PACK_AFTER_USES && useClock - it.value.lastUse <= window }.keys.toList()
    }
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

    /** The faces seen this session that were drawn within the last [window] frames (a copy). */
    fun specsInUse(window: Long): List<FontSpec> = synchronized(seen) { seen.filter { useClock - (lastUse[it] ?: 0L) <= window } }

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

    /** A new session's atlas starts from what THIS session draws. With [keepUse] (a session whose
     *  atlas was carried across the rebuild, `HANDOFF.md` §73) the frame stamps and the icons
     *  stay: [useClock] keeps counting across sessions, so a cleared stamp read every resident
     *  face and icon as stale at once and the first full-cache grow after a kept rebuild evicted
     *  what was in use (2026-11-09 12:06:36 on glass: a repack 0.57 s after "kept … 0 B to go
     *  again", 77.6 KB re-sent). The characters each face has drawn ([chars]) are never cleared
     *  here: they are content, not session state. */
    fun clearSeen(keepUse: Boolean = false) = synchronized(seen) {
        seen.clear(); usage.clear()
        if (!keepUse) { seenImages.clear(); lastUse.clear() }
    }

    /**
     * `HANDOFF.md` §73: every code 32..255 each face has drawn on the target or a relayed temp
     * — what the atlas packs a face with ([codesFor]) and extends it by. Guarded by the [seen]
     * lock; kept for the life of the process (a fresh atlas packs each face with everything it
     * is known to draw, instead of learning it again one character at a time after every
     * session start); bounded: at most [MAX_CHAR_SPECS] faces by last use, 224 bits each.
     */
    private val chars = object : LinkedHashMap<FontSpec, java.util.BitSet>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FontSpec, java.util.BitSet>?) = size > MAX_CHAR_SPECS
    }

    /** Bumped when a face draws a code it had not drawn before — the shell's cue to look for a
     *  resident face that can gain a glyph. At most 224 times per face: a code the face cannot
     *  get, or one waiting behind an extension, never bumps it again. */
    @Volatile var wantVersion = 0
        private set

    /** Host-drawn characters so far that a live face's table does not hold although a cached run
     *  may hold the code: not packed yet, waiting on a promotion, or a code the face has no glyph
     *  for at all (that last kind is counted on every draw, for good) — the journal's per-flush
     *  count of what the cache left to the pixels. */
    @Volatile var unpackedDrawn = 0L
        private set

    private fun noteChars(text: String, font: FontSpec) {
        var set = chars[font]
        if (set == null) { set = java.util.BitSet(); chars[font] = set }
        var gained = false
        for (ch in text) {
            val c = ch.code
            if (c < TextureCache.FIRST_CHAR || c > TextureCache.LAST_CHAR2 || set.get(c)) continue
            set.set(c); gained = true
        }
        if (gained) wantVersion++
    }

    /** The characters [spec] has drawn so far, as recorded ([chars]) — a copy. */
    fun charsOf(spec: FontSpec): java.util.BitSet = synchronized(seen) { chars[spec]?.clone() as? java.util.BitSet ?: java.util.BitSet() }

    /**
     * The codes the atlas packs [spec] with (`HANDOFF.md` §73): exactly what the face has drawn,
     * plus ALL TEN DIGITS once it has drawn one (a clock, a counter, a percentage shows a new
     * digit every minute; ten glyphs are ~0.8 KB), plus the space, plus every capital once the
     * face is plainly running text. Nothing else is closed over:
     * measured on the 35 faces of a scripted walk, a face packed this way is 3 KB where its
     * whole Latin-1 table was 16, and the cache holds the day's faces instead of seven.
     */
    fun codesFor(spec: FontSpec): java.util.BitSet {
        val set = charsOf(spec)
        if (set.nextSetBit('0'.code).let { it in '0'.code..'9'.code }) set.set('0'.code, '9'.code + 1)
        set.set(' '.code)
        // running text closes over the capitals: a face that has drawn most of the lower-case
        // alphabet and several capitals is prose, and prose shows a new capital every few lines
        // for pages (a book's first 25 notches on the simulator: A M B F R Y N T V W O S H, one
        // small cache write and one read-back each). ~2.4 KB a text face; a label face is not prose.
        if (set.get('a'.code, 'z'.code + 1).cardinality() >= CLOSE_UPPER_AT_LOWER &&
            set.get('A'.code, 'Z'.code + 1).cardinality() >= CLOSE_UPPER_AT_UPPER) set.set('A'.code, 'Z'.code + 1)
        return set
    }

    /** The live entry for [spec] in [a] — the atlas the CALLER read, once: the field is
     *  `@Volatile` and a swap between two reads (a v1 atlas for a v2 one at a session rebuild)
     *  would index a 96-entry table with a Latin-1 code (2026-09-15, second review). */
    private fun liveEntry(spec: FontSpec, a: GlyphAtlas? = atlas): GlyphAtlas.Entry? =
        if (spec in live) a?.entry(spec) else null

    /**
     * `HANDOFF.md` §73: with cached text ON, text is laid out by the CACHE's metric from the first
     * measure — the sum of each character's own advance (what a glyph's cached image is as wide
     * as) plus the pair kerns a cached run carries — whether or not the face is on the glasses
     * yet. Until then `measure` followed the live state: the host's whole-string measure while a
     * face was pixels, the per-glyph sum once it was live, and the second is the wider (each
     * advance is rounded up on its own: +15..25 px on a 65-character line of the Reader's face,
     * measured on AWT; the phone's paint is sub-pixel too). No layout was re-derived at the
     * change, so a book wrapped at its restore — before its face was uploaded — had lines past
     * their width the moment the face went live, and `Draw.fit` CUT them with the continuation
     * mark: content lost (seen on the simulator, old build and new, 2026-11-09). A face packed
     * with what it draws is live far more of the time, so this could not stay.
     *
     * The shell sets it from the Global `Cached text` row before the first layout of a session
     * and re-derives every wrap when the row changes. While it is on, a face that is NOT live is
     * drawn by the same layout it is measured by ([layout]: the host draws each unit at the
     * cache's pen position), so `measure` equals what `draw` advances in every state — not live,
     * live, live with characters the host still draws. A caret, a styled run or an underline
     * placed at `measure` of the text before it lands where that text ends, and nothing on screen
     * moves when a face goes live. (Drawn whole by the host, a face that was not live came out a
     * few pixels narrower than it measured: found by the review of §73.)
     */
    @Volatile var cacheMetric = false

    /** The contract the metric lays out for: which codes a cached run may hold, and so which
     *  pairs carry a kern. The shell sets it with the atlas's contract (`Shell.atlasEnable`) and
     *  re-derives the layouts when it changes; it does NOT follow [atlas] — that field is null
     *  between a reset and the next enable, and a rule that moved with it re-measured accented
     *  text on a contract-1 session with no layout re-derived. */
    @Volatile var metricV2 = true

    /** Per face: each code 32..255's advance + 1 (0 = not measured yet), and for the pairs of
     *  32..127 the kern + 64 (0 = not measured yet). Idempotent caches: a race writes the same value. */
    private val advances = java.util.concurrent.ConcurrentHashMap<FontSpec, IntArray>()
    private val kernTables = java.util.concurrent.ConcurrentHashMap<FontSpec, ByteArray>()

    override fun measure(text: String, font: FontSpec): Int {
        if (!cacheMetric && liveEntry(font) == null) return base.measure(text, font)
        return layout(text, font, metricV2) { _, _, _, _ -> }
    }

    /** True for a code point that belongs to the character before it: a combining mark, a
     *  variation selector, an emoji modifier. Never true below U+0300. */
    private fun joinsPrevious(cp: Int): Boolean {
        if (cp < 0x0300) return false
        if (cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }

    /** The end of the unit of text starting at [i] that the host must be handed WHOLE: one code
     *  point (both halves of a surrogate pair), the marks and selectors that belong to it, what a
     *  zero-width joiner ties on, and the second of two regional indicators. Handed out one
     *  UTF-16 unit at a time — as a live face's text was until §73's review — a pair reached the
     *  host as two unpaired surrogates and a mark was drawn with no letter under it. */
    private fun unitEnd(text: String, i: Int): Int {
        val first = text.codePointAt(i)
        var j = i + Character.charCount(first)
        if (first in 0x1F1E6..0x1F1FF && j < text.length && text.codePointAt(j) in 0x1F1E6..0x1F1FF) j += 2
        while (j < text.length) {
            val cp = text.codePointAt(j)
            if (cp == 0x200D) {
                j++
                if (j < text.length) j += Character.charCount(text.codePointAt(j))
            } else if (joinsPrevious(cp)) j += Character.charCount(cp)
            else break
        }
        return j
    }

    /**
     * [text] as the cache lays it out — the ONE walk `measure` and `draw` share, so they cannot
     * drift. Each unit is reported as (start, end, pen x from the string's start, code):
     *  - `code >= 0`: one code a cached run may hold on the [v2] contract, at its own advance (what
     *    its cached image is as wide as), a pair kern before it when the unit before it was one too;
     *  - `code == -1`: text only the host draws — a code outside the contract's range, or a whole
     *    [unitEnd] unit — at the host's measure of that unit, no kern on either side.
     * Whether a `code >= 0` unit is drawn from the cache or by the host (not packed yet, no glyph,
     * the face not live) does not move anything: the metric is independent of what is live.
     * Returns the width.
     */
    private inline fun layout(text: String, font: FontSpec, v2: Boolean, unit: (start: Int, end: Int, x: Int, code: Int) -> Unit): Int {
        if (advances.size > MAX_CHAR_SPECS) { advances.clear(); kernTables.clear() }      // bounded; recomputable
        val adv = advances.getOrPut(font) { IntArray(224) }
        var kt: ByteArray? = null
        var w = 0
        var prev = -1
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i].code
            val alone = c >= TextureCache.FIRST_CHAR && c <= TextureCache.LAST_CHAR2 &&
                (i + 1 >= n || text[i + 1].code < 0x0300 || !joinsPrevious(text.codePointAt(i + 1)))
            if (!alone) {
                val end = unitEnd(text, i)
                unit(i, end, w, -1)
                w += base.measure(text.substring(i, end), font)
                prev = -1
                i = end
                continue
            }
            val k = c - TextureCache.FIRST_CHAR
            var av = adv[k]
            if (av == 0) { av = base.measure(text[i].toString(), font) + 1; adv[k] = av }
            if (GlyphAtlas.cacheableCode(c, v2)) {
                if (prev >= 0) {
                    w += if (prev < 128 && c < 128) {
                        val t = kt ?: kernTables.getOrPut(font) { ByteArray(96 * 96) }.also { kt = it }
                        val at = (prev - 32) * 96 + (c - 32)
                        var kv = t[at].toInt()
                        if (kv == 0) { kv = base.kern(prev.toChar(), text[i], font) + 64; t[at] = kv.toByte() }
                        kv - 64
                    } else base.kern(prev.toChar(), text[i], font)
                }
                unit(i, i + 1, w, c)
                prev = c
            } else {
                unit(i, i + 1, w, -1)
                prev = -1
            }
            w += av - 1
            i++
        }
        return w
    }

    /** The pair kerning the base rasterizer measures for [font] — the adjust bytes a cached
     *  draw carries, and what [blit] advances by, so belief and glass agree (Phase 2). */
    fun kernOf(font: FontSpec): (Char, Char) -> Int = { a, b -> base.kern(a, b, font) }

    override fun metrics(font: FontSpec): FontMetrics = base.metrics(font)
    override fun covers(text: String, font: FontSpec): Boolean = base.covers(text, font)
    override fun kern(a: Char, b: Char, font: FontSpec): Int = base.kern(a, b, font)

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
            noteChars(text, font)
        }
        val a = atlas                                  // one read: the field can be swapped under us
        val e = liveEntry(font, a)
        if (e == null) {
            if (!cacheMetric) { base.draw(surface, x, y, text, font, level); return }
            // §73: not live, and the cache's metric is in force — the host draws each unit where
            // the cache will ([layout]), so what is drawn is as wide as it measures
            layout(text, font, metricV2) { s, end, ux, _ ->
                if (end - s > 1 || text[s] != ' ') base.draw(surface, x + ux, y, text.substring(s, end), font, level)
            }
            return
        }
        // §41: the runs the atlas holds blit from it and are recorded; everything else is the
        // host's, and the base delta carries its pixels. Since `HANDOFF.md` §73 the split is the
        // ENTRY's: a run holds only codes [e] packs — a code outside the contract's range (an
        // arrow, a dash), one the face was not packed with yet, and one it has no glyph for are
        // all the host's, and nothing downstream can tell a packed code from the tofu, so this is
        // the one guard. Positions are [layout]'s, for cached and host units alike. A run is
        // capped so the draw's u8 length holds it with an adjust byte between every pair
        // ([MAX_RUN] codes, 2 × [MAX_RUN] − 1 bytes).
        val kern = kernOf(font)
        val v2 = metricV2
        var runStart = -1                              // an open run of packed codes: [runStart, i)
        var runX = 0
        var afterHost = false                          // the run being built follows a host-drawn unit
        var unpacked = 0
        layout(text, font, v2) { s, end, ux, code ->
            if (code >= 0 && e.packs(text[s])) {
                if (runStart >= 0 && s - runStart >= MAX_RUN) {
                    cachedRun(surface, x + runX, y, text.substring(runStart, s), e, font, level, kern, onTarget, onRelay,
                        if (afterHost) HOST_OVERHANG_PX else 0, 0)
                    afterHost = false
                    runStart = -1
                }
                if (runStart < 0) { runStart = s; runX = ux }
            } else {
                if (runStart >= 0) {
                    cachedRun(surface, x + runX, y, text.substring(runStart, s), e, font, level, kern, onTarget, onRelay,
                        if (afterHost) HOST_OVERHANG_PX else 0, HOST_OVERHANG_PX)
                    runStart = -1
                }
                if (end - s > 1 || text[s] != ' ') base.draw(surface, x + ux, y, text.substring(s, end), font, level)
                afterHost = true
                // counted where the character goes out as PIXELS: the target, or a slide's strip on its
                // way there — not a strip rendered for staging (the relay that keeps nothing,
                // `Shell.NO_RECORDS`), which goes up inside a cache write
                if (code >= 0 && (onTarget || (onRelay && relayKeep.w > 0))) unpacked++
            }
        }
        if (runStart >= 0) cachedRun(surface, x + runX, y, text.substring(runStart), e, font, level, kern, onTarget, onRelay,
            if (afterHost) HOST_OVERHANG_PX else 0, 0)
        if (unpacked > 0) unpackedDrawn += unpacked     // loop-only writer (target and relay draws are the loop's)
    }

    /** One run of packed codes: blitted from the atlas at [px] and recorded where it lands. */
    private fun cachedRun(surface: Gray8, px: Int, y: Int, run: String, e: GlyphAtlas.Entry, font: FontSpec, level: Int,
        kern: (Char, Char) -> Int, onTarget: Boolean, onRelay: Boolean, keep: Int, keepEnd: Int) {
        blit(surface, px, y, run, e, level, kern)
        val (inkL, inkR) = inkSpan(run, e, kern)      // the rect is the INK's, not the pen's
        if (onTarget) record(px, y, inkL, inkR, e, run, font, level, keep, keepEnd)
        else if (onRelay) {
            // the draw lands on the target translated. A box the strip
            // clips (a row half outside a 16 px strip) is recorded whole
            // when it stays inside the band: the rest of the row is
            // already on the target from the previous strip and the
            // copies, and the proof checks every pixel of the box before
            // a draw ships. A box that leaves the band claims pixels the
            // blit never paints — dropped.
            val box = Rect(px + relayDx + inkL, y + relayDy, inkR - inkL, e.lineH)
            if (relayKeep.contains(box)) record(px + relayDx, y + relayDy, inkL, inkR, e, run, font, level, keep, keepEnd)
        }
    }

    /** [x] is the pen's start (what the draw carries); [inkL]..[inkR] is the ink's span around it
     *  ([CachedText.inkSpan]), which is what the rect promises. */
    private fun record(x: Int, y: Int, inkL: Int, inkR: Int, e: GlyphAtlas.Entry, text: String, font: FontSpec, level: Int,
        keep: Int = 0, keepEnd: Int = 0) =
        synchronized(draws) {
            if (draws.size >= MAX_DRAWS) draws.removeAt(0)
            draws.add(TextDraw(Rect(x + inkL, y, inkR - inkL, e.lineH), text, font, x, y, level, keep, keepEnd))
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

    /** A painter that decides for itself what it repaints has just filled [rects] and drawn them
     *  again (0.64 R1: the whole-panel mode's own repaint, `Shell.paintExclusiveDelta`): of the
     *  records taken BEFORE that paint ([olderDraws], [olderImages] — [frameDraws] and
     *  [frameImageDraws] as they stood), those that overlap one of [rects] describe what the paint
     *  covered and are dropped; what the paint itself recorded stays. By identity: a record is a
     *  plain object, and two paints of one string are two records. A painter that names a rect it
     *  did not fill loses records it could have kept — pixels once, never a wrong pixel. */
    fun dropOlderRecords(olderDraws: List<TextDraw>, olderImages: List<ImageDraw>, rects: List<Rect>) = synchronized(draws) {
        if (rects.isEmpty()) return@synchronized
        if (olderDraws.isNotEmpty()) {
            val old = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TextDraw, Boolean>(olderDraws.size)).also { it.addAll(olderDraws) }
            draws.removeAll { d -> d in old && rects.any { d.rect.overlaps(it) } }
        }
        if (olderImages.isNotEmpty()) {
            val old = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ImageDraw, Boolean>(olderImages.size)).also { it.addAll(olderImages) }
            imageDraws.removeAll { d -> d in old && rects.any { d.rect.overlaps(it) } }
        }
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
            if (src.contains(d.rect)) it.set(TextDraw(d.rect.translate(dx, dy), d.text, d.spec, d.x + dx, d.y + dy, d.level, d.keep, d.keepEnd))
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
        /** The codes one cached run holds: with a kerning adjust between every pair the string is
         *  2 × 128 − 1 = 255 bytes, the u8 length (a 255-code run could need 509 and went to pixels). */
        const val MAX_RUN = 128
        /** An icon is packed once it has been drawn this often (§41). */
        const val PACK_AFTER_USES = 2
        /** Icons remembered between grows, oldest out first. */
        const val MAX_SEEN_IMAGES = 256
        /** Faces whose drawn characters are remembered, least recently drawn out first. */
        const val MAX_CHAR_SPECS = 256
        /** A face that has drawn this many distinct lower-case letters and this many capitals is
         *  running text: it is packed with all 26 capitals ([codesFor]). */
        const val CLOSE_UPPER_AT_LOWER = 13
        const val CLOSE_UPPER_AT_UPPER = 8
        /** How far a host-drawn character's ink may run past its advance into the run that
         *  follows it: both rasterizers render a glyph into a mask 4 px wider than the advance
         *  (`AwtText`, `AndroidText`). Measured on AWT: with these columns left to the base no
         *  line's proof fails for a host-drawn character in any of the four locked faces, the
         *  italic included (`HANDOFF.md` §73). */
        const val HOST_OVERHANG_PX = 4

        /** The firmware's image draw (`cfw_texture_render`, TRANSPARENT): the
         *  same LUT as [blit], for a whole image. Shared with the proof. [hole] is the base's
         *  rule, as in [blit]: black in the base exactly where this draw writes. */
        fun blitImage(g: Gray8, x0: Int, y0: Int, img: TextureCache.Image, level: Int, hole: Gray8? = null) {
            if (hole != null && (hole.w != g.w || hole.h != g.h))
                throw LintError("blitImage: the base is ${hole.w}x${hole.h} and the surface ${g.w}x${g.h}")
            val top = Pack.level(level)
            val w = img.w
            for (p in img.levels.indices) {
                val s = img.levels[p].toInt()
                if (s == 0) continue
                val x = x0 + p % w
                val y = y0 + p / w
                if (x < 0 || y < 0 || x >= g.w || y >= g.h) continue
                val i = y * g.w + x
                g.pix[i] = ((s * top) / 15 * 17).toByte()
                if (hole != null) hole.pix[i] = 0
            }
        }

        /** Mode 14 draws 32..127 from a u8 string; DEL is tofu, so 32..126 ([v2]: Latin-1 but DEL, the
         *  C1 controls and the soft hyphen). */
        fun cacheable(text: String, v2: Boolean = false): Boolean =
            text.isNotEmpty() && text.length <= MAX_RUN &&        // an adjust byte may ride between every pair
                text.all { GlyphAtlas.cacheableCode(it.code, v2) }

        /**
         * The ink's own span relative to the pen's start, as `(left, rightExclusive)` — what
         * [blit] actually paints, which the ADVANCE it returns is not. A kern moves the pen
         * before the next glyph, so the pen is not monotonic and its final value is not the
         * furthest edge: a kern more negative than the following glyph's width puts ink outside
         * a rect sized from the advance, and ink outside a declared rect is never sent and no
         * check can see it (`CLAUDE.md`, §27). A recorded rect is a promise, so the record takes
         * this (2026-09-15, the third review; §61.4 had reasoned the case unreachable with the
         * locked faces — it is, but the rect no longer depends on that).
         */
        fun inkSpan(text: String, e: GlyphAtlas.Entry, kern: (Char, Char) -> Int = { _, _ -> 0 }): Pair<Int, Int> {
            var px = 0
            var prev: Char? = null
            var left = 0
            var right = 0
            for (ch in text) {
                prev?.let { px += kern(it, ch) }
                prev = ch
                val w = e.images[ch.code - TextureCache.FIRST_CHAR].w
                if (px < left) left = px
                if (px + w > right) right = px + w
                px += w
            }
            return left to right
        }

        /**
         * The firmware's draw (`cfw_texture_render` with TRANSPARENT): source
         * level 0 is skipped, every other level lands as `s × top / 15`
         * (integer), here as the 8-bit `k × 17` the wire's quantiser maps
         * back to k. Returns the pen advance. Shared by the recorder and the
         * compositor's re-render so the two can never disagree.
         *
         * [hole] (0.64) is the base's rule: a surface of [g]'s size that is set to 0 at every
         * pixel this draw writes, in the same iteration that writes it. The compositor passes
         * the BASE of a cached rect there while it builds the proof's surface in [g], so a
         * pixel is black in the base exactly when a draw writes it, and the base's holes and the
         * proof's draws cannot disagree: one loop makes both. The recorder passes nothing.
         */
        fun blit(g: Gray8, x0: Int, y0: Int, text: String, e: GlyphAtlas.Entry, level: Int,
                 kern: (Char, Char) -> Int = { _, _ -> 0 }, hole: Gray8? = null): Int {
            if (hole != null && (hole.w != g.w || hole.h != g.h))
                throw LintError("blit: the base is ${hole.w}x${hole.h} and the surface ${g.w}x${g.h}")
            val top = Pack.level(level)
            var px = x0
            var prev: Char? = null
            for (ch in text) {
                prev?.let { px += kern(it, ch) }        // the adjust byte the draw carries (Phase 2)
                prev = ch
                val img = e.images[ch.code - TextureCache.FIRST_CHAR]
                val w = img.w
                for (p in img.levels.indices) {
                    val s = img.levels[p].toInt()
                    if (s == 0) continue
                    val x = px + p % w
                    val y = y0 + p / w
                    if (x < 0 || y < 0 || x >= g.w || y >= g.h) continue
                    val i = y * g.w + x
                    g.pix[i] = ((s * top) / 15 * 17).toByte()
                    if (hole != null) hole.pix[i] = 0
                }
                px += w
            }
            return px - x0
        }
    }
}
