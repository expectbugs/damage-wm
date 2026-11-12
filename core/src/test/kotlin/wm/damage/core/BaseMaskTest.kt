package wm.damage.core

import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import wm.damage.core.comp.CachedText
import wm.damage.core.comp.Compositor
import wm.damage.core.comp.GlyphAtlas
import wm.damage.core.geom.FidAllocator
import wm.damage.core.geom.FidTracker
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Level
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Zl
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.text.Face
import wm.damage.core.text.FontMetrics
import wm.damage.core.text.FontSpec
import wm.damage.core.text.TextRasterizer
import wm.damage.core.transport.Arm
import wm.damage.core.transport.DisplayOp
import wm.damage.core.transport.Emit
import wm.damage.core.transport.FlushRequest
import wm.damage.core.wire.DamageMsg
import wm.damage.core.wire.TextureCache

/**
 * A rasterizer whose glyphs are anti-aliased, taken to the limit: every lit pixel of a glyph has a
 * level of its own, 1..15 (seeded noise, about 40 % of an 8 x 22 box). Text like this is where a
 * base with glyph-shaped holes is much cheaper than the pixels: the holes are a two-level mask and
 * the pixels sixteen levels of noise.
 */
class SoftText : TextRasterizer {
    override fun measure(text: String, font: FontSpec): Int = text.length * 9
    override fun metrics(font: FontSpec) = FontMetrics(18, 4, 24)
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        for ((i, ch) in text.withIndex()) {
            if (ch == ' ') continue
            val rnd = java.util.Random(ch.code * 7919L + 13)
            for (yy in 0 until 22) for (xx in 0 until 8) {
                val k = rnd.nextInt(38)
                if (k in 1..15) surface[x + i * 9 + xx, y + yy] = level * k / 15
            }
        }
    }
    override fun covers(text: String, font: FontSpec) = true
}

/**
 * A rasterizer whose glyph IS its box: 4 x 8, solid. A block of such text is a block of dirty cells
 * with no clean cell in it, so the diff plans ONE rect a block and a scene can count its
 * sub-messages exactly (`atTheBatchsLastSubMessageWhatIsLeftWaitsAFlush`).
 */
class BlockText : TextRasterizer {
    override fun measure(text: String, font: FontSpec): Int = text.length * 4
    override fun metrics(font: FontSpec) = FontMetrics(6, 2, 8)
    override fun draw(surface: Gray8, x: Int, y: Int, text: String, font: FontSpec, level: Int) {
        for ((i, ch) in text.withIndex()) if (ch != ' ') surface.fillRect(x + i * 4, y, 4, 8, level)
    }
    override fun covers(text: String, font: FontSpec) = true
}

/**
 * APK 0.64, P1 part a — what a cached rect's BASE blacks, and what the compositor does with a rect
 * that proves only because of it (`HANDOFF.md` §75.3; `Compositor.emitCached`).
 *
 * Until 0.63 the base was the composed pixels with every draw's whole BOX black, so anything
 * composed under a string's box — the rule a menu's title sits on, a lit cell under a character —
 * was missing from base + draws, the proof refused the rect and it went out as pixels (the menu's
 * box: 52 of 52 first paints at 2.6 KB on glass). The base is now black exactly where a draw
 * WRITES. With that come: the price (`dearer`) that keeps a lit field from costing more than its
 * pixels, the skip of a planned rect an earlier op already settled, one proof per grown box, and
 * the word `two-planes` for a failed proof no single shifted box could have passed.
 *
 * Compositor level, and every scene is judged ON THE GLASS as well as by the ops: each flush is
 * encoded by the shared emitter and dispatched to both arms of `GlassFirmwareSim` (the model the
 * conformance vectors hold against the fork's C), and the panels are compared with the
 * compositor's belief. The proof inside the compositor is built by the same walk as the base, so
 * a base that blacked the wrong pixel would pass the proof; it cannot pass this.
 */
class BaseMaskTest {
    private val f18 = FontSpec(Face.SYSTEM, 18)

    private fun codes(s: String) = BitSet().also { for (c in s) it.set(c.code) }

    /** A compositor, a recorder over [base], an atlas of the session's contract, and the model
     *  glasses with the lease held (and, on contract 2, the cache sized and DRAW2 armed). The
     *  keyframe has gone out: both lenses are black and belief says so. */
    private inner class Rig(val v2: Boolean, val base: TextRasterizer = NoisyText(), cached: Boolean = true) {
        val comp = Compositor()
        val ct = CachedText(base)
        val atlas = if (v2) GlyphAtlas(base, v2 = true, capacity = 96 * 1024) else GlyphAtlas(base)
        val sim = GlassFirmwareSim().also { if (v2) it.damageContract = 2 }
        private val fids = FidAllocator()
        private val tracker = FidTracker()
        val g: Gray8 get() = comp.composed
        /** The last flush: its miss words, its bytes on the wire (the encoded image), its compressions. */
        var misses = ""; var bytes = 0; var compressions = 0

        init {
            comp.v2 = v2
            ct.cacheMetric = true; ct.metricV2 = v2
            ct.atlas = atlas; ct.target = comp.composed
            if (cached) comp.cachedText = ct
            for (arm in Arm.entries) {
                sim.conformanceLease(arm, true, NOW)
                if (v2) {
                    sim.conformanceControl(arm, DamageMsg.OP_CACHE_SIZE, 96, NOW)
                    sim.conformanceControl(arm, DamageMsg.OP_FLAGS_SET, DamageMsg.FLAG_DRAW2, NOW)
                }
            }
            comp.requestKeyframe()
            flush("the keyframe")
        }

        fun face(spec: FontSpec, chars: String) {
            assertTrue(atlas.add(spec, codes(chars)), "the face packs")
            ct.live = ct.live + spec
            upload()
        }

        fun icon(key: Any, bm: Gray8) {
            assertTrue(atlas.addImage(key, TextureCache.Image(bm.w, bm.h, ByteArray(bm.w * bm.h) { Pack.level(bm.pix[it].toInt() and 0xFF).toByte() })))
            ct.liveImages = ct.liveImages + key
            upload()
        }

        private fun upload() {
            for (m in atlas.takeUpload()) {
                for (arm in Arm.entries) assertTrue(sim.dispatchForTest(arm, m, NOW), "the glasses took the cache write")
                atlas.acked()
            }
        }

        /** One assemble, encoded and dispatched to both arms. [leaves]: the flush is expected to
         *  leave work for the next one (a budget ran out), where every other scene expects none. */
        fun flush(what: String, leaves: Boolean = false): Compositor.Assembled {
            val a = assertNotNull(comp.assembleFlush(5), "$what: something ships")
            misses = comp.cacheMissSummary(); compressions = comp.lastCompressN
            val image = Emit.encode(FlushRequest(a.ops, a.epoch), fids, tracker, 1).image
            bytes = image.size
            for (arm in Arm.entries) assertTrue(sim.dispatchForTest(arm, image, NOW), "$what: the glasses took the flush (${shape(a).takeLast(400)})")
            if (leaves) assertTrue(comp.hasPending, "$what: the flush left work for the next (${shape(a).takeLast(400)})")
            else assertTrue(!comp.hasPending, "$what: one flush carried it all")
            return a
        }

        fun assertGlassIsBelief(what: String) {
            val stride = (Geometry.PANEL_W + 1) / 2
            for (arm in Arm.entries) {
                val panel = sim.snapshot(arm)
                val e = comp.expectedLens(arm == Arm.LEFT)
                var diffs = 0; var first = ""
                for (y in 0 until Geometry.PANEL_H) for (x in 0 until Geometry.PANEL_W) {
                    val b = panel[y * stride + (x shr 1)].toInt() and 0xFF
                    val n = if (x and 1 == 0) b shr 4 else b and 0x0F
                    if (n != Pack.level(e[x, y])) { if (diffs++ == 0) first = "($x,$y) belief ${Pack.level(e[x, y])} glass $n" }
                }
                assertEquals(0, diffs, "$what: $arm belief != glass ($diffs px, first $first)")
            }
        }

        /** Belief is the composed frame over [r] on both lenses (a rect on no depth plane). */
        fun assertBeliefIsComposed(what: String, r: Rect) {
            for (left in booleanArrayOf(true, false)) for (y in r.y until r.bottom) for (x in r.x until r.right)
                assertEquals(g[x, y], comp.expectedLens(left)[x, y], "$what: belief at ($x,$y), ${if (left) "L" else "R"}")
        }
    }

    private fun shape(a: Compositor.Assembled) = a.ops.joinToString(" ") {
        when (it) {
            is DisplayOp.Delta -> "D${it.box}(${it.payload.size},d${it.disparity})"
            is DisplayOp.DrawText -> "T(${it.x},${it.y} n${it.text.size})"
            is DisplayOp.DrawText2 -> "T2(${it.xL}/${it.xR},${it.y} n${it.text.size})"
            is DisplayOp.DrawImage -> "I(${it.x},${it.y})"
            is DisplayOp.DrawImage2 -> "I2(${it.xL}/${it.xR},${it.y})"
            is DisplayOp.CopyPair -> "CP"
            is DisplayOp.Fill -> "F${it.left}"
            is DisplayOp.PresentHint -> "H"
            else -> it::class.simpleName ?: "?"
        }
    }

    private fun isTextDraw(op: DisplayOp) = op is DisplayOp.DrawText || op is DisplayOp.DrawText2
    private fun isImageDraw(op: DisplayOp) = op is DisplayOp.DrawImage || op is DisplayOp.DrawImage2
    private fun textDraws(a: Compositor.Assembled) = a.ops.count(::isTextDraw)
    private fun imageDraws(a: Compositor.Assembled) = a.ops.count(::isImageDraw)
    private fun deltas(a: Compositor.Assembled) = a.ops.filterIsInstance<DisplayOp.Delta>()

    /** The base deltas of [a]: every delta that a draw follows directly. */
    private fun bases(a: Compositor.Assembled): List<DisplayOp.Delta> =
        a.ops.withIndex().filter { (i, op) -> op is DisplayOp.Delta && i + 1 < a.ops.size && (isTextDraw(a.ops[i + 1]) || isImageDraw(a.ops[i + 1])) }
            .map { it.value as DisplayOp.Delta }

    /** How often the last assemble counted [word] (the journal's `cacheMiss`, `word=n,…`). */
    private fun counted(misses: String, word: String): Int =
        misses.split(',').firstOrNull { it.substringBefore('=') == word }?.substringAfter('=')?.toInt() ?: 0

    /** The pixels a delta carries, decoded from its payload — what was SENT, not what was meant. */
    private fun sent(d: DisplayOp.Delta): Gray8 = Pack.unpack(Zl.decodeCfw(d.payload, d.box.w * d.box.h), d.box.w, d.box.h)

    /**
     * The base's rule, read from the wire: over the delta's box the decoded pixels are the composed
     * ones, except black wherever a draw wrote. In these scenes the draws are the only thing at
     * [ink] (text at HOT over a rule or a field at a lower level), so "a draw wrote here" is
     * "composed is [ink]". Returns (lit pixels left in the base, draw pixels blacked).
     */
    private fun assertBaseIsComposedLessInk(what: String, d: DisplayOp.Delta, composed: Gray8, ink: Int = Level.HOT): Pair<Int, Int> {
        val px = sent(d)
        var lit = 0; var holes = 0
        for (y in 0 until d.box.h) for (x in 0 until d.box.w) {
            val c = composed[d.box.x + x, d.box.y + y]
            val want = if (c == ink) 0 else Pack.level(c)
            if (c == ink) holes++ else if (want != 0) lit++
            assertEquals(want, Pack.level(px[x, y]), "$what: the base at (${d.box.x + x},${d.box.y + y}), composed $c")
        }
        return lit to holes
    }

    // ------------------------------------------------------------------ the reference walk

    /** `CachedText.blit` / `blitImage` with a base: the base goes black at exactly the pixels the
     *  walk writes into the surface and nowhere else — at any level, level 0 included (dark text
     *  writes 0, and that is a write) — and a base of another size is refused.
     *  Mutations watched: the store into the base removed, in `blit` and in `blitImage`. */
    @Test
    fun theBlitBlacksTheBaseExactlyWhereItWrites() {
        val atlas = GlyphAtlas(SoftText(), v2 = true, capacity = 96 * 1024)
        assertTrue(atlas.add(f18, codes("Rule 7")))
        val e = atlas.entry(f18)!!
        val img = TextureCache.Image(5, 4, byteArrayOf(0, 15, 7, 0, 1,  15, 0, 0, 3, 0,  0, 0, 0, 0, 0,  9, 9, 0, 0, 15))
        for (level in intArrayOf(Level.HOT, Level.BODY, Level.BG)) for (image in booleanArrayOf(false, true)) {
            // sentinels no draw can write: every written value is a level x 17
            val g = Gray8(96, 40).also { it.clear(50) }
            val base = Gray8(96, 40).also { it.clear(100) }
            if (image) CachedText.blitImage(g, 90, 37, img, level, hole = base)              // clipped at the right and the bottom
            else CachedText.blit(g, 3, 5, "Rule 7", e, level, hole = base)
            var written = 0
            for (i in g.pix.indices) {
                val wrote = g.pix[i].toInt() != 50
                if (wrote) written++
                assertEquals(if (wrote) 0 else 100, base.pix[i].toInt() and 0xFF,
                    "${if (image) "image" else "text"} at level $level: the base at pixel $i (surface ${g.pix[i].toInt() and 0xFF})")
            }
            assertTrue(written > 0, "the draw landed (${if (image) "image" else "text"}, level $level)")
        }
        assertFailsWith<LintError> { CachedText.blit(Gray8(96, 40), 0, 0, "R", e, Level.HOT, hole = Gray8(96, 41)) }
        assertFailsWith<LintError> { CachedText.blitImage(Gray8(96, 40), 0, 0, img, Level.HOT, hole = Gray8(95, 40)) }
    }

    // ------------------------------------------------------------------ rule (1): the base blacks what a draw writes

    /** The menu's title, reduced: a two-row rule filled first, then a string whose box holds it.
     *  On 0.63 the string's box blacked its own rule in the base and the proof refused the rect. */
    private fun aRuleUnderAString(v2: Boolean) {
        val r = Rig(v2)
        r.face(f18, "Title")
        val box = Rect(200, 200, 48, 22)                       // five 9 px advances, the face's 22 rows, aligned out
        r.g.fillRect(200, 218, 45, 2, Level.MID)               // rows 18 and 19 of the string's box
        r.ct.draw(r.g, 200, 200, "Title", f18, Level.HOT)
        assertEquals(listOf(Rect(200, 200, 45, 22)), r.ct.frameDraws().map { it.rect })
        r.comp.damage(box)
        val a = r.flush("a string over its rule")
        val what = "contract ${if (v2) 2 else 1}: ${shape(a)} misses '${r.misses}'"
        assertEquals(0, counted(r.misses, "proof") + counted(r.misses, "two-planes") + counted(r.misses, "dearer"), "base + draws is the composed rect, and it is the cheaper message — $what")
        assertTrue(textDraws(a) >= 1 && a.ops.all { op -> if (v2) op !is DisplayOp.DrawText else op !is DisplayOp.DrawText2 }, "the string ships as a draw of its contract — $what")
        val base = bases(a).first()
        assertEquals(box, base.box, what)
        val (lit, holes) = assertBaseIsComposedLessInk(what, base, r.g)
        assertTrue(lit > 0 && holes > 0, "the base carries the rule ($lit px lit) and is black where the string writes ($holes px) — $what")
        var onTheRule = 0
        for (y in 218..219) for (x in 200 until 245) if (r.g[x, y] == Level.HOT) onTheRule++
        assertTrue(onTheRule > 0, "the scene has glyph pixels ON the rule ($onTheRule): those are black in the base and the rule beside them is lit")
        r.assertBeliefIsComposed(what, box)
        r.assertGlassIsBelief(what)
    }

    @Test fun aRuleInsideATextDrawsBoxRidesTheBaseOnContractTwo() = aRuleUnderAString(v2 = true)
    @Test fun aRuleInsideATextDrawsBoxRidesTheBaseOnContractOne() = aRuleUnderAString(v2 = false)

    /** The same on a depth plane: contract 1 ships a flat base widened by the disparity, the draw
     *  and one per-lens copy; contract 2 one stereo base and the draw placed per lens. */
    @Test
    fun aRuleInsideATextDrawsBoxOnADepthPlaneBothContracts() {
        for (v2 in booleanArrayOf(false, true)) {
            val r = Rig(v2)
            r.face(f18, "Title")
            r.comp.planes = listOf(Compositor.PlaneRegion(Rect(160, 160, 320, 160), 8))
            r.g.fillRect(200, 218, 45, 2, Level.MID)
            r.ct.draw(r.g, 200, 200, "Title", f18, Level.HOT)
            r.comp.damage(Rect(200, 200, 48, 22))
            val a = r.flush("a string over its rule at depth 8")
            val what = "contract ${if (v2) 2 else 1} at depth 8: ${shape(a)} misses '${r.misses}'"
            assertEquals(0, counted(r.misses, "proof") + counted(r.misses, "two-planes") + counted(r.misses, "dearer"), what)
            val base = bases(a).first()
            val (lit, holes) = assertBaseIsComposedLessInk(what, base, r.g)
            assertTrue(lit > 0 && holes > 0, "the rule rides the base, the string's pixels do not — $what")
            if (v2) {
                assertEquals(8, base.disparity, "one stereo base — $what")
                val t = a.ops.filterIsInstance<DisplayOp.DrawText2>()
                assertTrue(t.isNotEmpty() && t.all { it.xR - it.xL == 16 && it.xL == 200 - 8 }, "the draw sits at each lens's own x — $what")
                assertTrue(a.ops.none { it is DisplayOp.CopyPair || it is DisplayOp.DrawText }, what)
            } else {
                assertEquals(0, base.disparity, "a flat base — $what")
                assertTrue(a.ops.any { it is DisplayOp.DrawText } && a.ops.any { it is DisplayOp.CopyPair }, "the draw at nominal x and one per-lens copy — $what")
            }
            r.assertGlassIsBelief(what)
        }
    }

    /**
     * An icon is blacked by what it writes too: a rule through a ring's unlit middle stays in the
     * base and the ring ships as one image draw. This small rect is also where the price has to be
     * EXACT: it is inked (the rule lies in the icon's box), so it is priced, and the base plus one
     * draw beats its pixels by a few bytes on the wire. Priced by the batch budget's allowance
     * (`Compositor.sizeOf`, 15 B over every draw) it would be `dearer` and stay pixels.
     * Mutations watched: the base not passed to `blitImage` (each contract); the store into the
     * base removed in `blitImage`; the price taken with `sizeOf` (each contract).
     */
    @Test
    fun anIconOverARuleShipsAsAnImageDrawBothContracts() {
        val ring = Gray8(12, 12).also { it.outlineRect(0, 0, 12, 12, 255, 2) }
        fun scene(v2: Boolean, cached: Boolean): Pair<BaseMaskTest.Rig, Compositor.Assembled> {
            val r = Rig(v2, cached = cached)
            r.icon("ring", ring)
            r.g.fillRect(196, 205, 20, 2, Level.MID)            // through the ring's rows 5 and 6
            assertTrue(r.ct.drawIcon(r.g, "ring", ring, 200, 200, Level.HOT), "the icon is drawn from the cache")
            r.comp.damage(Rect(196, 200, 20, 12))
            return r to r.flush("an icon over a rule")
        }
        for (v2 in booleanArrayOf(false, true)) {
            val (r, a) = scene(v2, cached = true)
            val (p, _) = scene(v2, cached = false)
            val what = "contract ${if (v2) 2 else 1}: ${shape(a)} misses '${r.misses}', ${r.bytes} B against ${p.bytes} B of pixels"
            assertEquals(0, counted(r.misses, "proof") + counted(r.misses, "two-planes") + counted(r.misses, "dearer"), what)
            assertTrue(imageDraws(a) >= 1 && a.ops.any { if (v2) it is DisplayOp.DrawImage2 else it is DisplayOp.DrawImage }, "the icon ships as an image draw of its contract — $what")
            val (lit, holes) = assertBaseIsComposedLessInk(what, bases(a).first(), r.g)
            assertTrue(lit >= 2 * 8 && holes > 0, "the rule inside the ring ($lit px lit) rides the base — $what")
            assertTrue(r.bytes < p.bytes, "and that is the smaller flush — $what")
            r.assertGlassIsBelief(what)
        }
    }

    /** What must not move: something painted over a string AFTER it was drawn is not what the draw
     *  writes, base + draws is not the truth, and the proof refuses it — under the word `proof`
     *  (the truth is one plane's pixels; the cache could not serve them). The pixels ship.
     *  And a refusal is that assemble's: the same box, painted properly on the next frame, proves
     *  and ships as a draw (the failed-box set does not outlive the frame it judged).
     *  Mutations watched: `sameOver` answering true; `proofMiss` always `two-planes`; the
     *  failed-box set never cleared. */
    @Test
    fun aBarPaintedOverTextIsStillRefused() {
        for (v2 in booleanArrayOf(false, true)) {
            val r = Rig(v2)
            val box = Rect(200, 200, 48, 22)
            r.face(f18, "Title")
            r.ct.draw(r.g, 200, 200, "Title", f18, Level.HOT)
            r.g.fillRect(200, 208, 45, 4, Level.MID)            // across the glyphs, after them
            r.comp.damage(box)
            val a = r.flush("a bar over a string")
            val what = "contract ${if (v2) 2 else 1}: ${shape(a)} misses '${r.misses}'"
            assertTrue(counted(r.misses, "proof") >= 1, "the proof refuses it and says so — $what")
            assertEquals(0, counted(r.misses, "two-planes"), "nothing here is another plane's — $what")
            assertEquals(0, textDraws(a), "no draw — $what")
            r.assertBeliefIsComposed(what, box)
            r.assertGlassIsBelief(what)

            r.g.fillRect(box, 0)
            r.ct.draw(r.g, 200, 200, "Title", f18, Level.HOT)
            r.comp.damage(box)
            val b = r.flush("the same string with nothing over it")
            val then = "contract ${if (v2) 2 else 1}, the next frame: ${shape(b)} misses '${r.misses}'"
            assertTrue(textDraws(b) >= 1 && counted(r.misses, "proof") == 0, "the box that was refused a frame ago proves now — $then")
            assertEquals(box, bases(b).first().box, then)
            r.assertGlassIsBelief(then)
        }
    }

    // ------------------------------------------------------------------ the price

    /**
     * Text on a lit field proves since 0.64; whether it SHIPS as draws is the bytes' call
     * (`Compositor.inked`). Two scenes, one each way, each against its twin sent as pixels.
     * A: anti-aliased text — the base is the field with two-level holes, the pixels sixteen
     *    levels: draws are the smaller message and ship.
     * B: text at level 0 — the draws write black, so the base IS the pixels and each draw is
     *    bytes on top: `dearer`, and what ships is byte for byte the pixel path's flush.
     * Mutation watched: the full comparison (after the base is compressed) removed.
     */
    @Test
    fun textOnALitFieldShipsAsDrawsOnlyWhenThatIsCheaper() {
        val field = Rect(196, 198, 100, 26)
        fun scene(v2: Boolean, level: Int, cached: Boolean): Pair<BaseMaskTest.Rig, Compositor.Assembled> {
            val r = Rig(v2, SoftText(), cached)
            r.face(f18, "0123456789")
            r.g.fillRect(field, Level.of(5))
            r.ct.draw(r.g, 200, 200, "0123456789", f18, level)
            r.comp.damage(field)
            return r to r.flush("text on a lit field")
        }
        for (v2 in booleanArrayOf(false, true)) {
            val (ra, a) = scene(v2, Level.HOT, cached = true)
            val (pa, _) = scene(v2, Level.HOT, cached = false)
            val whatA = "contract ${if (v2) 2 else 1}, scene A: ${shape(a)} misses '${ra.misses}', ${ra.bytes} B against ${pa.bytes} B of pixels"
            assertEquals(1, textDraws(a), "the string ships as a draw — $whatA")
            assertEquals(0, counted(ra.misses, "dearer") + counted(ra.misses, "proof"), whatA)
            assertTrue(ra.bytes < pa.bytes, "because that is the smaller flush — $whatA")
            // the base, from the wire: the field, black exactly where the string writes. A glyph pixel may
            // have the field's own level here, so "a draw wrote this pixel" is read from the glyphs
            // alone on a blank surface rather than from the composed frame
            val base = bases(a).single()
            assertEquals(field, base.box, whatA)
            val px = sent(base)
            val glyphs = Gray8(640, 480).also { SoftText().draw(it, 200, 200, "0123456789", f18, 255) }
            var litField = 0; var holes = 0
            for (y in 0 until field.h) for (x in 0 until field.w) {
                val wrote = glyphs[field.x + x, field.y + y] != 0
                if (wrote) holes++ else litField++
                assertEquals(if (wrote) 0 else Level.of(5), px[x, y], "the base at (${field.x + x},${field.y + y}) — $whatA")
            }
            assertTrue(litField > field.area / 2 && holes > 500, "the base holds the field ($litField px) with the glyphs' pixels black ($holes) — $whatA")
            ra.assertBeliefIsComposed(whatA, field)
            ra.assertGlassIsBelief(whatA)

            val (rb, b) = scene(v2, Level.BG, cached = true)
            val (pb, p) = scene(v2, Level.BG, cached = false)
            val whatB = "contract ${if (v2) 2 else 1}, scene B: ${shape(b)} misses '${rb.misses}', ${rb.bytes} B against ${pb.bytes} B of pixels"
            assertTrue(counted(rb.misses, "dearer") >= 1, "the draws only add bytes — $whatB")
            assertEquals(0, textDraws(b), whatB)
            assertEquals(shape(p), shape(b), "what ships is the pixel path's flush — $whatB")
            assertEquals(pb.bytes, rb.bytes, whatB)
            rb.assertGlassIsBelief(whatB)
        }
    }

    /**
     * The Tmux shape (`TermRender.drawGlyph`): a lit row, ONE draw per character. The base is the
     * row with glyph-shaped holes — about what the pixels cost — and forty draws ride on top. The
     * flush is never larger than the pixel path's: here it is the same flush.
     * Mutation watched: the `dearer` guard's full comparison removed (the row ships as a base and
     * forty draws, several hundred bytes over its pixels).
     */
    @Test
    fun aLitRowDrawnOneCharacterAtATimeIsNeverLargerThanItsPixels() {
        val row = Rect(100, 100, 368, 24)
        val text = "the quick brown fox jumps over lazy dogs"
        fun scene(v2: Boolean, cached: Boolean): Pair<BaseMaskTest.Rig, Compositor.Assembled> {
            val r = Rig(v2, NoisyText(), cached)
            r.face(f18, text)
            r.g.fillRect(row, Level.of(5))
            for ((i, ch) in text.withIndex()) if (ch != ' ') r.ct.draw(r.g, 104 + 9 * i, 101, ch.toString(), f18, Level.HOT)
            assertEquals(text.count { it != ' ' }, r.ct.frameDraws().size, "one record per character")
            r.comp.damage(row)
            return r to r.flush("a lit row, a draw per character")
        }
        for (v2 in booleanArrayOf(false, true)) {
            val (rc, c) = scene(v2, cached = true)
            val (rp, p) = scene(v2, cached = false)
            val what = "contract ${if (v2) 2 else 1}: ${shape(c)} misses '${rc.misses}', ${rc.bytes} B against ${rp.bytes} B of pixels"
            assertTrue(rc.bytes <= rp.bytes, "never larger than the pixel path's flush — $what")
            assertEquals(1, counted(rc.misses, "dearer"), "and the journal says why it stayed pixels — $what")
            assertEquals(0, textDraws(c), what)
            assertEquals(shape(p), shape(c), what)
            rc.assertGlassIsBelief(what)
        }
    }

    /**
     * The cheap half of the price runs first: when the draws ALONE already cost more than the
     * rect's pixels, the answer is `dearer` before the base is compressed. Read on the thing it
     * names — the assemble's count of compressions: one (the planned rect's, which the pixel path
     * then takes from the memo), not two. Mutation watched: the early comparison removed.
     */
    @Test
    fun drawsThatAloneCostMoreThanThePixelsAreRefusedBeforeTheBaseIsCompressed() {
        val row = Rect(100, 100, 328, 20)
        val text = "abcdefghijklmnopqrstuvwxyzabcdefghijklmn"
        for (v2 in booleanArrayOf(false, true)) {
            val r = Rig(v2, KernedText())                        // solid glyphs: the row's pixels compress to very little
            r.face(f18, text)
            r.g.fillRect(row, Level.of(5))
            for ((i, ch) in text.withIndex()) r.ct.draw(r.g, 104 + 8 * i, 102, ch.toString(), f18, Level.HOT)
            r.comp.damage(row)
            val a = r.flush("a lit row of solid glyphs")
            val what = "contract ${if (v2) 2 else 1}: ${shape(a)} misses '${r.misses}', ${r.compressions} compression(s)"
            assertEquals(1, counted(r.misses, "dearer"), what)
            assertEquals(0, textDraws(a), what)
            assertEquals(1, r.compressions, "the base was not compressed to be thrown away — $what")
            r.assertGlassIsBelief(what)
        }
    }

    /**
     * A rect that GROWS is priced against the rect that was planned, not against the box it grew
     * to: one character of a string on a lit field changes, the planned rect is that character's
     * cells, the cached rect would be the whole string's box with the field in its base. `dearer`,
     * and the flush is the pixel path's. Mutation watched: the price taken against the grown rect.
     */
    @Test
    fun aSmallChangeOnALitFieldIsPricedAgainstItsPixels() {
        val field = Rect(196, 198, 100, 26)
        fun scene(v2: Boolean, cached: Boolean): Pair<BaseMaskTest.Rig, Compositor.Assembled> {
            val r = Rig(v2, SoftText(), cached)
            r.face(f18, "0123456789X")
            r.g.fillRect(field, Level.of(5)); r.ct.draw(r.g, 200, 200, "0123456789", f18, Level.HOT)
            r.comp.damage(field); r.flush("the line")
            r.g.fillRect(field, Level.of(5)); r.ct.draw(r.g, 200, 200, "01234X6789", f18, Level.HOT)
            r.comp.damage(field)
            return r to r.flush("one character changed")
        }
        for (v2 in booleanArrayOf(false, true)) {
            val (rc, c) = scene(v2, cached = true)
            val (rp, p) = scene(v2, cached = false)
            val what = "contract ${if (v2) 2 else 1}: ${shape(c)} misses '${rc.misses}', ${rc.bytes} B against ${rp.bytes} B of pixels"
            assertTrue(counted(rc.misses, "dearer") >= 1, what)
            assertEquals(0, counted(rc.misses, "proof") + counted(rc.misses, "two-planes"), what)
            assertEquals(0, textDraws(c), what)
            assertEquals(shape(p), shape(c), "the changed cells go out as their pixels — $what")
            assertTrue(rc.bytes <= rp.bytes && rp.bytes < 200, what)
            rc.assertGlassIsBelief(what)
        }
    }

    /** An icon's box is priced like a string's: an icon drawn at level 0 on a lit field writes
     *  black, its base is the pixels, and the draw is bytes on top. Mutation watched: `inked`
     *  reading the text draws' boxes only. */
    @Test
    fun aDarkIconOnALitFieldIsPricedLikeText() {
        val ring = Gray8(12, 12).also { it.outlineRect(0, 0, 12, 12, 255, 2) }
        for (v2 in booleanArrayOf(false, true)) {
            val r = Rig(v2)
            r.icon("ring", ring)
            r.g.fillRect(196, 196, 20, 20, Level.of(5))
            assertTrue(r.ct.drawIcon(r.g, "ring", ring, 200, 200, Level.BG))
            r.comp.damage(Rect(196, 196, 20, 20))
            val a = r.flush("a dark icon on a lit field")
            val what = "contract ${if (v2) 2 else 1}: ${shape(a)} misses '${r.misses}'"
            assertTrue(counted(r.misses, "dearer") >= 1, what)
            assertEquals(0, imageDraws(a), what)
            r.assertGlassIsBelief(what)
        }
    }

    /**
     * What 0.63 already shipped as draws is not priced: `HANDOFF.md` §73's line with a host-drawn
     * character ("ToTo", `o` not packed and tucked two columns into each `T`'s box). The host's ink
     * lies in the columns 0.63's base left as composed (`TextDraw.keep` / `keepEnd`), so the rect
     * is not `inked` and ships as it did — also when one cell changed and the rect grows from that
     * cell to the run's box, where a price against the cell would always say `dearer`.
     * Mutations watched: `oldBox` ignoring `keepEnd`; `oldBox` ignoring `keep`.
     */
    @Test
    fun aRunBesideAHostDrawnCharacterIsNotPriced() {
        // T at 200 (ink 200..205), o at 206 (the host's, ink 206..211), T at 214, o at 220; rows 202..207
        for (v2 in booleanArrayOf(false, true)) for ((where, spot) in listOf("the run before a host-drawn character" to 206, "the run after one" to 215)) {
            val name = "contract ${if (v2) 2 else 1}, $where"
            val r = Rig(v2, base = KernedText())
            r.face(f18, "T")
            r.ct.draw(r.g, 200, 200, "ToTo", f18, Level.HOT)
            r.comp.damage(Rect(200, 200, 32, 16)); r.flush("the line")
            r.g.fillRect(200, 200, 32, 16, 0)
            r.ct.draw(r.g, 200, 200, "ToTo", f18, Level.HOT)
            assertEquals(listOf(Rect(200, 200, 8, 16), Rect(214, 200, 8, 16)), r.ct.frameDraws().map { it.rect })
            assertEquals(listOf(0 to 4, 4 to 4), r.ct.frameDraws().map { it.keep to it.keepEnd })
            r.g[spot, 210] = Level.HOT                            // one pixel of "host ink" in a kept column, three rows under the glyphs
            r.comp.damage(Rect(200, 200, 32, 16))
            val a = r.flush("one cell changed")
            val what = "$name: ${shape(a)} misses '${r.misses}'"
            assertEquals(1, deltas(a).size, what)
            assertTrue(deltas(a).single().box.area >= 8 * 16, "the rect grew from the cell to the run's box — $what")
            assertEquals(1, textDraws(a), "the run ships as a draw, as on 0.63 — $what")
            assertEquals(0, counted(r.misses, "dearer") + counted(r.misses, "proof"), "and was not priced — $what")
            r.assertGlassIsBelief(what)
        }
    }

    /** Contract 1's full-width retry is the planned rect's second attempt and is priced against
     *  THAT rect's pixels: a character changes on a lit field whose edge sits inside the copy's
     *  margin, the first attempt fails on its context, the retry over the row's full width
     *  proves — and is `dearer` than the character's cells. Priced against the widened rect it
     *  would ship the whole row. Mutation watched: the retry not passing the planned rect. */
    @Test
    fun onContractOneTheFullWidthRetryIsPricedAgainstThePlannedRect() {
        val field = Rect(196, 198, 100, 26)                       // ends 4 px past the string's aligned box
        fun scene(cached: Boolean): Pair<BaseMaskTest.Rig, Compositor.Assembled> {
            val r = Rig(v2 = false, base = SoftText(), cached = cached)
            r.face(f18, "0123456789X")
            r.comp.planes = listOf(Compositor.PlaneRegion(Rect(160, 160, 320, 160), 8))
            r.g.fillRect(field, Level.of(5)); r.ct.draw(r.g, 200, 200, "0123456789", f18, Level.HOT)
            r.comp.damage(field); r.flush("the line")
            r.g.fillRect(field, Level.of(5)); r.ct.draw(r.g, 200, 200, "01234X6789", f18, Level.HOT)
            r.comp.damage(field)
            return r to r.flush("one character changed")
        }
        val (rc, c) = scene(cached = true)
        val (rp, p) = scene(cached = false)
        val what = "${shape(c)} misses '${rc.misses}', ${rc.bytes} B against ${rp.bytes} B of pixels"
        assertTrue("proof=0" in rc.misses, "the first attempt failed on its context and the retry ran (its count taken back) — $what")
        assertTrue(counted(rc.misses, "dearer") >= 1, what)
        assertEquals(0, textDraws(c), what)
        assertEquals(shape(p), shape(c), what)
        assertTrue(rc.bytes <= rp.bytes, what)
        rc.assertGlassIsBelief(what)
    }

    // ------------------------------------------------------------------ a rect already brought to the truth

    /** Two cells of one string change. Each planned rect grows to the string's box; the first
     *  ships the base and the draw and the shadows take the truth over the whole box, so the
     *  second has nothing left to send. On 0.63 the same base and draw went out once per rect.
     *  Mutation watched: `settled` answering false. */
    @Test
    fun twoChangedCellsOfOneStringShipItOnce() {
        for (v2 in booleanArrayOf(false, true)) {
            val r = Rig(v2)
            r.face(f18, "abcdefghijklmnopqrstuvAV")
            val box = Rect(100, 100, 200, 22)
            r.ct.draw(r.g, 100, 100, "abcdefghijklmnopqrstuv", f18, Level.HOT)
            r.comp.damage(box); r.flush("the line")
            r.g.fillRect(box, 0)
            r.ct.draw(r.g, 100, 100, "AbcdefghijklmnopqrstuV", f18, Level.HOT)
            r.comp.damage(box)
            val a = r.flush("its first and last characters changed")
            val what = "contract ${if (v2) 2 else 1}: ${shape(a)} misses '${r.misses}'"
            assertEquals(1, deltas(a).size, "one base — $what")
            assertEquals(1, textDraws(a), "and the string once — $what")
            assertEquals(22, a.ops.first(::isTextDraw).let { if (it is DisplayOp.DrawText2) it.text.size else (it as DisplayOp.DrawText).text.size }, what)
            r.assertGlassIsBelief(what)
        }
    }

    /** `settled` reads the unknown marks as well as the pixels: a flush that was lost leaves its
     *  rects UNKNOWN while the shadows still hold what it was believed to paint, and the rect is
     *  sent again. Mutation watched: `lensSettled` not reading the unknown cells (nothing ships). */
    @Test
    fun aRectALostFlushLeftUnknownIsSentAgainThoughItsShadowLooksRight() {
        val r = Rig(v2 = true)
        r.face(f18, "Title")
        r.ct.draw(r.g, 200, 200, "Title", f18, Level.HOT)
        r.comp.damage(Rect(200, 200, 48, 22))
        val lost = assertNotNull(r.comp.assembleFlush(5))          // assembled, never delivered
        r.comp.rollback(lost)
        r.ct.draw(r.g, 200, 200, "Title", f18, Level.HOT)          // the next frame paints it again
        val a = r.flush("after the lost flush")
        assertTrue(deltas(a).any { it.box.overlaps(Rect(200, 200, 48, 22)) }, "the rect goes out again: ${shape(a)}")
        r.assertGlassIsBelief("after the lost flush")
    }

    // ------------------------------------------------------------------ a failed proof's two words, one attempt per box

    /** A row on a depth plane whose text ends at a nearer plane's edge. Its far-lens box runs 8
     *  columns into that plane, where the lens shows the nearer plane's pixels: no single shifted
     *  box holds that, whatever the records say. The miss is `two-planes`, not `proof`; the
     *  pixels ship (and the nearer plane's columns are repaired in the same flush, as before).
     *  Mutation watched: `proofMiss` always answering `proof`. */
    private fun aRowEndingAtANearerPlane(v2: Boolean): BaseMaskTest.Rig {
        val r = Rig(v2)
        r.face(f18, "0123456789ABC")
        r.comp.planes = listOf(Compositor.PlaneRegion(Rect(160, 160, 200, 100), 8), Compositor.PlaneRegion(Rect(360, 160, 120, 100), 0))
        r.ct.draw(r.g, 270, 180, "0123456789", f18, Level.HOT)   // ink to column 358; the nearer plane starts at 360
        r.comp.damage(Rect(268, 180, 92, 22))
        return r
    }

    @Test
    fun aRectWhoseFarLensBoxReachesANearerPlaneIsNamedTwoPlanes() {
        for (v2 in booleanArrayOf(false, true)) {
            val r = aRowEndingAtANearerPlane(v2)
            val a = r.flush("a row ending at a nearer plane")
            val what = "contract ${if (v2) 2 else 1}: ${shape(a)} misses '${r.misses}'"
            assertTrue(counted(r.misses, "two-planes") >= 1, "the failed proof is named for what it is — $what")
            assertEquals(0, counted(r.misses, "proof"), "and is not counted as a rect the cache could have served — $what")
            assertEquals(0, textDraws(a), what)
            r.assertGlassIsBelief(what)
        }
    }

    /** One attempt per grown box: three characters of that row change, three planned rects grow
     *  to the one string's box, and the proof that cannot pass runs once — one miss, not three
     *  (`proof=3` for one string on glass, 2026-11-10). On contract 1 the full-width retry is
     *  part of the same attempt and the count is still one.
     *  Mutations watched: a known box not short-circuited; the retry un-counting the wrong word. */
    @Test
    fun aStringUnderATwoPlaneBoxCountsOneMissNotThree() {
        for (v2 in booleanArrayOf(false, true)) {
            val r = aRowEndingAtANearerPlane(v2)
            r.flush("the row")
            r.g.fillRect(Rect(268, 180, 92, 22), 0)
            r.ct.draw(r.g, 270, 180, "A123B5678C", f18, Level.HOT)
            r.comp.damage(Rect(268, 180, 92, 22))
            val a = r.flush("three of its characters changed")
            val what = "contract ${if (v2) 2 else 1}: ${shape(a)} misses '${r.misses}'"
            assertTrue(deltas(a).count { it.disparity == 8 } >= 3, "three planned rects inside one record — $what")
            assertEquals(1, counted(r.misses, "two-planes") + counted(r.misses, "proof"), "one box, one miss — $what")
            assertEquals(1, counted(r.misses, "two-planes"), what)
            r.assertGlassIsBelief(what)
        }
    }

    // ------------------------------------------------------------------ the batch's count of sub-messages (0.64 R1, RA-1)

    /**
     * A mode-8 batch carries its count of sub-messages in ONE byte (`CfwModes.batch`). The plan loop
     * budgeted fids and bytes and not that count, and a cached rect is a base and up to 96 draws: five
     * changed rows of 52 one-character draws (the Tmux shape, `TermRender.drawGlyph`) with unchanged
     * text rows between them — which is what keeps `price` from joining them — made 5 x 53 = 265
     * sub-messages and the encoder refused the whole flush.
     *
     * The scene: nine rows of 52 characters, each painted and flushed by itself (the setup does not
     * lean on how nine rows partition); then rows 0, 2, 4, 6, 8 change in one frame. Four rows ship
     * as a base and 52 draws, the fifth finds 42 sub-messages left for its 53 and goes out as one
     * pixel delta in the same flush, under the word `batch-count`.
     * THE SHAPE GUARD is the two counts at the end: four rows as draws and exactly one `batch-count`.
     * If a later change to `partition` / `price` merges the rows, the scene no longer reaches the
     * count and this pin would pass with the budget removed — the guard fails instead.
     * Mutations watched: the count's check removed in `emitCachedV2` and in `emitCachedAt` (each on
     * its contract: `mode-8 count 265 exceeds the u8 field`).
     */
    @Test
    fun aFlushOfManyCachedRowsStaysInsideTheBatchsCount() {
        val was = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"            // 52 characters
        val now = was.reversed()                                                     // every position changes
        fun rowRect(k: Int) = Rect(80, 100 + 28 * k, 52 * 9, 22)                     // 9 px advance, the face's 22 rows; on the grid
        fun paintRow(r: BaseMaskTest.Rig, k: Int, text: String) {
            r.g.fillRect(rowRect(k), 0)
            for ((i, ch) in text.withIndex()) r.ct.draw(r.g, 80 + 9 * i, 100 + 28 * k, ch.toString(), f18, Level.HOT)
            r.comp.damage(rowRect(k))
        }
        for (v2 in booleanArrayOf(false, true)) {
            val r = Rig(v2)
            r.face(f18, was)
            for (k in 0 until 9) { paintRow(r, k, was); r.flush("row $k") }
            for (k in 0 until 9 step 2) paintRow(r, k, now)
            assertEquals(5 * 52, r.ct.frameDraws().size, "one record per character of the five changed rows")
            val a = r.flush("five changed rows of 52 one-character draws")       // (a) the encode holds and both arms take it
            val what = "contract ${if (v2) 2 else 1}: ${a.ops.size} sub-messages, ${textDraws(a)} draws, ${deltas(a).size} deltas, misses '${r.misses}'"
            assertTrue(a.ops.size <= 254, "the batch keeps inside its one-byte count, with one left for the hint — $what")
            assertEquals(4 * 52, textDraws(a), "the shape guard: four rows ship as draws, so the scene reached the limit — $what")
            assertEquals(1, counted(r.misses, "batch-count"), "the shape guard: the fifth row is the one refusal, by its own word — $what")
            assertEquals(5, deltas(a).size, "four bases and the fifth row's pixels — $what")
            for (k in 0 until 9 step 2) r.assertBeliefIsComposed(what, rowRect(k))
            r.assertGlassIsBelief(what)
        }
    }

    /**
     * The limit itself. Three blocks of solid text ship as 97 + 96 + 61 = 254 sub-messages — every
     * one the budget gives out — and three more things are planned BEHIND them in the same frame:
     * the pixels of a field that moved to a depth plane, a rect a staged record could serve
     * (contract 2; plain pixels on contract 1) and the seam's black box. Each needs a sub-message
     * the batch no longer has, so each waits: the first flush is the 254 (and, on contract 2, the
     * mode-24 hint in the slot kept for it — the three blocks span exactly the 40 rows a hint may
     * name, so the hint IS sent and the batch is full to its last count), and the next flush
     * carries the rest. Both flushes are encoded and judged on the model's panels.
     *
     * The order is the planner's: the screen-plane remainder first (the blocks, kept apart by
     * plane-0 regions so neither `partition` nor `price` may join them), then the far plane's rect,
     * then the plane-0 region's, the black box last.
     * THE SHAPE GUARD: 251 draws on three bases in the first flush, nothing else in it.
     * Mutations watched (each makes the first flush's encode refuse the batch, or leaves it one
     * sub-message over the 254): the check removed in `emitDeltaPlain`, in `emitStaged`, in each
     * arm of the black box; `subsLeft` not keeping the hint's slot.
     */
    @Test
    fun atTheBatchsLastSubMessageWhatIsLeftWaitsAFlush() {
        val fB = FontSpec(Face.SYSTEM, 8)
        val sep1 = Rect(136, 100, 8, 40); val sep2 = Rect(220, 100, 8, 40)
        val shelf = Rect(288, 100, 64, 40)                         // a plane-0 region: planned after the remainder
        val deep = Rect(400, 100, 32, 40)                          // on the screen plane first, then 8 px back
        val stripAt = Rect(296, 108, 16, 16)
        val strip = Gray8(16, 16).also { for (y in 0 until 16) for (x in 0 until 16) it[x, y] = Level.of(1 + (x + y) % 15) }
        class Block(val x: Int, val cols: Int, val rows: Int) { val rect get() = Rect(x, 100, 4 * cols, 8 * rows) }
        val blocks = listOf(Block(40, 24, 4), Block(144, 19, 5), Block(228, 12, 5))   // 96, 95 and 60 draws
        fun planes(depth: Int) = listOf(Compositor.PlaneRegion(sep1, 0), Compositor.PlaneRegion(sep2, 0),
            Compositor.PlaneRegion(shelf, 0), Compositor.PlaneRegion(deep, depth))
        for (v2 in booleanArrayOf(false, true)) {
            val r = Rig(v2, BlockText())
            r.face(fB, "A")
            if (v2) r.icon("strip", strip)                         // the record a placement draws from, held by the glasses
            r.comp.planes = planes(0)
            r.g.fillRect(deep, Level.MID)
            r.comp.damage(deep)
            r.flush("a lit field on the screen plane")

            for (b in blocks) {
                for (row in 0 until b.rows) for (col in 0 until b.cols) r.ct.draw(r.g, b.x + 4 * col, 100 + 8 * row, "A", fB, Level.HOT)
                r.comp.damage(b.rect)
            }
            for (y in 0 until 16) for (x in 0 until 16) r.g[stripAt.x + x, stripAt.y + y] = strip[x, y]
            r.comp.damage(stripAt)
            if (v2) r.comp.placements = listOf(Compositor.StagedPlacement(stripAt, r.atlas.image("strip")!!.offset / 4, 16, 16, stripAt.x, stripAt.y, strip))
            r.comp.planes = planes(8)                              // the field's pixels move per lens; its old columns are seam

            val a = r.flush("three blocks that fill the batch's count, and three things behind them", leaves = true)
            val what = "contract ${if (v2) 2 else 1}: ${a.ops.size} sub-messages, ${textDraws(a)} draws, misses '${r.misses}', ends ${shape(a).takeLast(60)}"
            assertEquals(251, textDraws(a), "the shape guard: the three blocks ship as draws — $what")
            assertEquals(blocks.map { it.rect }.toSet(), deltas(a).map { it.box }.toSet(), "the shape guard: three bases and no other delta — $what")
            val rest = a.ops.filter { !isTextDraw(it) && it !is DisplayOp.Delta }
            if (v2) assertTrue(rest.size == 1 && a.ops.last() is DisplayOp.PresentHint, "the hint takes the slot kept for it, and nothing else rides — $what")
            else assertTrue(rest.isEmpty(), "nothing else rides — $what")
            assertEquals(254 + (if (v2) 1 else 0), a.ops.size, "254 sub-messages given out, the 255th only to the hint — $what")
            assertEquals(0, counted(r.misses, "batch-count"), "no cached rect was refused: what waits is pixels, a placement and a black box — $what")
            r.assertGlassIsBelief(what)

            val b = r.flush("the next flush")
            val then = "contract ${if (v2) 2 else 1}, the next flush: ${shape(b)} misses '${r.misses}'"
            assertTrue(deltas(b).any { it.disparity == 8 && it.box.overlaps(deep) }, "the field's pixels on its plane — $then")
            if (v2) {
                assertEquals(1, b.ops.count { it is DisplayOp.DrawImage2 }, "the rect a staged record serves, from the record — $then")
                assertEquals(2, b.ops.count { it is DisplayOp.Clip }, then)
                assertTrue(b.ops.any { it is DisplayOp.Fill }, "the seam's black box as a fill — $then")
                assertTrue(deltas(b).none { it.box.overlaps(stripAt) }, then)
            } else {
                assertTrue(deltas(b).any { it.box.overlaps(stripAt) }, "the rect as pixels — $then")
                assertTrue(b.ops.any { it is DisplayOp.StereoPair }, "the seam's black box as a stereo pair — $then")
            }
            for (blk in blocks) r.assertBeliefIsComposed(then, blk.rect)
            r.assertGlassIsBelief(then)
        }
    }

    private companion object {
        const val NOW = 1_000L
    }
}
