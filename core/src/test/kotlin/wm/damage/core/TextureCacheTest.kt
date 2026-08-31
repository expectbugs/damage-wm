package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import wm.damage.core.geom.Geometry
import wm.damage.core.geom.LintError
import wm.damage.core.gfx.Rle
import wm.damage.core.sim.GlassFirmwareSim
import wm.damage.core.transport.Arm
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.SettingsMsg
import wm.damage.core.wire.TextureCache

/**
 * The texture cache and its draw modes (g2flash a5d1c31, `patches/texture_cache.c`):
 * modes 11/12/13/14 against the firmware model, plus the capability changes that
 * came with them. Mode 15 is deliberately absent from the encoder and refused by
 * the model — see `CfwModes`.
 */
class TextureCacheTest {

    // ---------------------------------------------------------------- the codec
    @Test
    fun cachedImageRleCarriesExactlyWidthTimesHeightPixelsWithNoRowPad() {
        // 3x2 is the case that separates the two RLE flavours: an odd width means
        // modes 3/6 would carry a pad nibble per row and this must not.
        val levels = byteArrayOf(1, 1, 1, 2, 2, 2)
        val enc = TextureCache.Image(3, 2, levels).encode()
        assertEquals(3, enc[0].toInt(), "width byte")
        assertEquals(2, enc[1].toInt(), "height byte")
        // Two runs of three: [3<<4|1][3<<4|2]
        assertContentEquals(byteArrayOf(0x31, 0x32), enc.copyOfRange(2, enc.size))
        assertContentEquals(levels, Rle.decodeLevels(enc.copyOfRange(2, enc.size), 6))
    }

    @Test
    fun levelRunsLongerThanFifteenEscapeAndRoundTrip() {
        val levels = ByteArray(300) { if (it < 200) 7 else 0 }
        val rle = Rle.encodeLevels(levels)
        assertContentEquals(levels, Rle.decodeLevels(rle, 300))
    }

    @Test
    fun anImageBiggerThanAByteInEitherAxisIsRefused() {
        assertFailsWith<LintError> { TextureCache.Image(256, 4, ByteArray(1024)) }
        assertFailsWith<LintError> { TextureCache.Image(4, 0, ByteArray(0)) }
    }

    // ---------------------------------------------------------------- the layout
    @Test
    fun theAtlasLeavesOffsetZeroBlankSoAnUnwrittenTableEntryIsRejected() {
        val b = TextureCache.Builder()
        val off = b.add(TextureCache.Image(2, 2, byteArrayOf(5, 5, 5, 5)))
        assertTrue(off >= TextureCache.GUARD, "no image may sit where a zero offset points")
        val content = b.content()
        assertEquals(0, content[0].toInt())
        assertEquals(0, content[1].toInt(), "a zero table entry must read as a 0x0 image")
    }

    @Test
    fun identicalGlyphsShareOneCacheEntry() {
        val b = TextureCache.Builder()
        val a = b.add(TextureCache.Image(2, 2, byteArrayOf(3, 3, 3, 3)))
        val c = b.add(TextureCache.Image(2, 2, byteArrayOf(3, 3, 3, 3)))
        val d = b.add(TextureCache.Image(2, 2, byteArrayOf(4, 4, 4, 4)))
        assertEquals(a, c, "the same bytes reuse an offset")
        assertNotEquals(a, d)
    }

    @Test
    fun everyFontTableEntryIsFilledSoOneStrayCharCannotDropAWholeLine() {
        val b = TextureCache.Builder()
        val tofu = TextureCache.Image(4, 6, ByteArray(24) { 9 })
        val font = b.addFont(mapOf('A' to TextureCache.Image(3, 6, ByteArray(18) { 15 })), tofu)
        assertEquals(CfwModes.FONT_TABLE_CHARS, font.glyphOffsets.size)
        for ((i, off) in font.glyphOffsets.withIndex())
            assertTrue(off >= TextureCache.GUARD, "char ${i + 32} has no valid glyph offset")
    }

    @Test
    fun anAtlasThatOverflowsSixtyFourKiBRaisesRatherThanWrapping() {
        val b = TextureCache.Builder()
        assertFailsWith<LintError> {
            // Incompressible noise so dedup cannot save us: ~65 KB of distinct images.
            var n = 0
            while (true) {
                val levels = ByteArray(255 * 255) { i -> ((i * 7 + n * 13) and 0x0F).toByte() }
                b.add(TextureCache.Image(255, 255, levels))
                n++
                if (n > 200) break
            }
        }
    }

    // ---------------------------------------------------------------- the wire
    @Test
    fun modeThirteenIsExactlyEightBytesAsTheFirmwareDemands() {
        val m = CfwModes.drawImage(cacheOffset = 0x1234, x = 100, y = 200, options = 15)
        assertEquals(8, m.size, "the firmware requires len == 7 after the mode byte")
        assertEquals(13, m[0].toInt())
        assertContentEquals(byteArrayOf(0x34, 0x12), m.copyOfRange(1, 3))
        assertContentEquals(byteArrayOf(100, 0), m.copyOfRange(3, 5))
        assertContentEquals(byteArrayOf(200.toByte(), 0), m.copyOfRange(5, 7))
    }

    @Test
    fun modeFourteenRefusesBytesTheFirmwareWouldRejectTheWholeStringFor() {
        assertFailsWith<LintError> {
            CfwModes.drawCachedText(0, 0, 0, 15, byteArrayOf(65, 0, 66))     // NUL
        }
        assertFailsWith<LintError> {
            CfwModes.drawCachedText(0, 0, 0, 15, byteArrayOf(65, 200.toByte())) // > 127
        }
        // 1..31 are legal x adjustments, not glyphs.
        CfwModes.drawCachedText(0, 0, 0, 15, byteArrayOf(CfwModes.xAdjust(-3), 65))
    }

    @Test
    fun theInlineAdjustByteCoversExactlyMinusTenToPlusTwenty() {
        assertEquals(1, CfwModes.xAdjust(-10).toInt())
        assertEquals(11, CfwModes.xAdjust(0).toInt())
        assertEquals(31, CfwModes.xAdjust(20).toInt())
        assertFailsWith<LintError> { CfwModes.xAdjust(-11) }
        assertFailsWith<LintError> { CfwModes.xAdjust(21) }
    }

    @Test
    fun aCacheWriteOffTheEndOfTheCacheIsRefusedBeforeItReachesTheWire() {
        assertFailsWith<LintError> {
            CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(65530, ByteArray(10))))
        }
        assertFailsWith<LintError> { CfwModes.cacheUpdate(emptyList()) }
    }

    @Test
    fun aBatchNowCarriesCachedDrawsAlongsidePixelDeltas() {
        val sub = CfwModes.drawImage(4, 0, 0, 15)
        val batch = CfwModes.batch(listOf(sub, CfwModes.copy(
            wm.damage.core.geom.Rect(0, 0, 8, 8), wm.damage.core.geom.Rect(8, 0, 8, 8))))
        assertEquals(8, batch[0].toInt())
        assertEquals(2, batch[1].toInt())
        // Mode 15 stays legal on the wire even though nothing here builds one.
        assertTrue(15 in CfwModes.BATCH_SUBMODES)
        assertFailsWith<LintError> { CfwModes.batch(listOf(byteArrayOf(10, 1))) }  // mode 10
    }

    // ---------------------------------------------------------------- the model
    /** Drive the sim's dispatcher directly with a lease held, as the transport would. */
    private fun leased(sim: GlassFirmwareSim, now: Long = 1_000L): GlassFirmwareSim {
        for (arm in Arm.entries) sim.forceLease(arm, now + SettingsMsg.LEASE_EXPIRY_MS)
        return sim
    }

    @Test
    fun anUploadedImageDrawsTheExactPixelsTheModelPredicts() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val off = b.add(TextureCache.Image(2, 2, byteArrayOf(15, 0, 0, 15)))
        for (m in b.messages()) assertTrue(sim.dispatchForTest(Arm.LEFT, m, 1_000L), "cache write")
        assertTrue(sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 10, 20, 15), 1_000L))
        val panel = sim.snapshot(Arm.LEFT)
        val stride = (Geometry.PANEL_W + 1) / 2
        fun px(x: Int, y: Int): Int {
            val v = panel[y * stride + (x shr 1)].toInt() and 0xFF
            return if (x and 1 == 0) v shr 4 else v and 0x0F
        }
        assertEquals(15, px(10, 20)); assertEquals(0, px(11, 20))
        assertEquals(0, px(10, 21)); assertEquals(15, px(11, 21))
    }

    @Test
    fun transparencyTestsTheSourceLevelAndInverseReversesTheRamp() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val off = b.add(TextureCache.Image(2, 1, byteArrayOf(0, 15)))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        // Seed a known background, then draw with transparency: pixel 0 must survive.
        sim.fillShadowForTest(Arm.LEFT, 7)
        val opts = CfwModes.options(top = 15, transparent = true)
        assertTrue(sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, opts), 1_000L))
        val stride = (Geometry.PANEL_W + 1) / 2
        val p = sim.snapshot(Arm.LEFT)
        assertEquals(7, (p[0].toInt() and 0xFF) shr 4, "source 0 was skipped, background kept")
        assertEquals(15, p[0].toInt() and 0x0F, "source 15 drew at top colour 15")

        // Inverse maps source 15 -> 0 and source 0 -> top.
        sim.fillShadowForTest(Arm.LEFT, 0)
        val inv = CfwModes.options(top = 15, inverse = true)
        assertTrue(sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, inv), 1_000L))
        val q = sim.snapshot(Arm.LEFT)
        assertEquals(15, (q[0].toInt() and 0xFF) shr 4, "inverse turned source 0 into 15")
        assertEquals(0, q[0].toInt() and 0x0F, "inverse turned source 15 into 0")
    }

    @Test
    fun aTopColourBelowFifteenScalesTheRampProportionally() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val off = b.add(TextureCache.Image(2, 1, byteArrayOf(15, 5)))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        sim.fillShadowForTest(Arm.LEFT, 0)
        sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, CfwModes.options(top = 6)), 1_000L)
        val p = sim.snapshot(Arm.LEFT)
        assertEquals(6, (p[0].toInt() and 0xFF) shr 4, "(15*6)/15")
        assertEquals(2, p[0].toInt() and 0x0F, "(5*6)/15 truncates to 2")
    }

    @Test
    fun cachedTextAdvancesByGlyphWidthAndHonoursTheInlineAdjust() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val bar = TextureCache.Image(2, 3, ByteArray(6) { 15 })     // a solid 2x3 block
        val font = b.addFont(mapOf('A' to bar, 'B' to bar), TextureCache.Image(1, 1, byteArrayOf(1)))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        sim.fillShadowForTest(Arm.LEFT, 0)
        // "AB" with a +2 adjust between them: A at x=0..1, B at x=4..5.
        val text = byteArrayOf('A'.code.toByte(), CfwModes.xAdjust(2), 'B'.code.toByte())
        assertTrue(sim.dispatchForTest(Arm.LEFT,
            CfwModes.drawCachedText(font.tableOffset, 0, 0, 15, text), 1_000L))
        val stride = (Geometry.PANEL_W + 1) / 2
        val p = sim.snapshot(Arm.LEFT)
        fun px(x: Int): Int {
            val v = p[x shr 1].toInt() and 0xFF
            return if (x and 1 == 0) v shr 4 else v and 0x0F
        }
        assertEquals(15, px(0)); assertEquals(15, px(1))
        assertEquals(0, px(2)); assertEquals(0, px(3))
        assertEquals(15, px(4)); assertEquals(15, px(5))
        assertEquals(0, px(6))
    }

    @Test
    fun everyCacheModeIsRefusedWithoutAFramebufferLease() {
        val sim = GlassFirmwareSim()          // no lease
        val b = TextureCache.Builder()
        b.add(TextureCache.Image(2, 2, byteArrayOf(1, 1, 1, 1)))
        for (m in b.messages())
            assertFalse(sim.dispatchForTest(Arm.LEFT, m, 1_000L), "mode 12 needs the lease")
        assertFalse(sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(2, 0, 0, 15), 1_000L))
    }

    @Test
    fun theCacheDiesWithTheLeaseAndMustBeUploadedAgain() {
        val sim = leased(GlassFirmwareSim(), now = 0L)
        val b = TextureCache.Builder()
        val off = b.add(TextureCache.Image(2, 2, byteArrayOf(1, 1, 1, 1)))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 0L)
        assertTrue(sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, 15), 0L))
        sim.tick(SettingsMsg.LEASE_EXPIRY_MS + 1)          // lease lapses, cache is freed
        sim.forceLease(Arm.LEFT, SettingsMsg.LEASE_EXPIRY_MS * 3)
        assertFalse(
            sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, 15),
                SettingsMsg.LEASE_EXPIRY_MS + 2),
            "the atlas does not survive a lapsed lease",
        )
    }

    @Test
    fun modeElevenHandsTheScreenBackAndDropsTheCache() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val off = b.add(TextureCache.Image(2, 2, byteArrayOf(1, 1, 1, 1)))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        assertTrue(sim.dispatchForTest(Arm.LEFT, CfwModes.cleanup(), 1_000L))
        assertFalse(sim.leaseHeld(Arm.LEFT, 1_000L), "mode 11 releases the framebuffer lease")
        assertFalse(sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, 15), 1_000L))
    }

    @Test
    fun aDrawFromAnEmptyCacheSlotIsRefusedLoudly() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        b.add(TextureCache.Image(2, 2, byteArrayOf(1, 1, 1, 1)))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        // Offset 0 is the guard: zeroes, so width == 0, so the firmware rejects it.
        assertFalse(sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(0, 0, 0, 15), 1_000L))
    }

    @Test
    fun modeFifteenIsRefusedByTheModelBecauseItsPixelsCannotBeKnown() {
        val sim = leased(GlassFirmwareSim())
        val m15 = byteArrayOf(15, 0, 0, 0, 0, 15, 1, 'A'.code.toByte())
        assertFalse(sim.dispatchForTest(Arm.LEFT, m15, 1_000L),
            "mode 15 draws with the firmware's own font; no offline model can predict it")
    }

    // ------------------------------------------- the lease lifecycle, driven for real
    // Review round 1 found two defects here that no test could see, because every
    // model test set the deadline directly with forceLease instead of sending the
    // firmware the acquire/release it actually reacts to. These drive `settings()`.

    private fun lease(sim: GlassFirmwareSim, arm: Arm, op: Int, now: Long) =
        sim.write(arm, wm.damage.core.wire.AaFrame.frame(
            1, SettingsMsg.SID, SettingsMsg.FLAG_REQUEST,
            SettingsMsg.control(op, 1), wm.damage.core.wire.AaFrame.TYPE_COMMAND).single(), now)

    private fun uploadAtlas(sim: GlassFirmwareSim, arm: Arm, now: Long): Int {
        val b = TextureCache.Builder()
        val off = b.add(TextureCache.Image(2, 2, byteArrayOf(1, 1, 1, 1)))
        for (m in b.messages()) assertTrue(sim.dispatchForTest(arm, m, now), "cache write")
        return off
    }

    @Test
    fun releasingTheLeaseFreesTheCacheAsTheFirmwareDoes() {
        val sim = GlassFirmwareSim()
        lease(sim, Arm.LEFT, SettingsMsg.OP_FB_ACQUIRE, 0L)
        val off = uploadAtlas(sim, Arm.LEFT, 100L)
        assertTrue(sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, 15), 200L))

        lease(sim, Arm.LEFT, SettingsMsg.OP_FB_RELEASE, 300L)
        lease(sim, Arm.LEFT, SettingsMsg.OP_FB_ACQUIRE, 400L)
        assertFalse(
            sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, 15), 500L),
            "FB_RELEASE frees the cache; the atlas must be uploaded again after re-acquiring",
        )
    }

    @Test
    fun aFreshAcquireAfterALapseFreesTheCacheButARenewalKeepsIt() {
        val sim = GlassFirmwareSim()
        lease(sim, Arm.LEFT, SettingsMsg.OP_FB_ACQUIRE, 0L)
        val off = uploadAtlas(sim, Arm.LEFT, 100L)

        // A renewal inside the 90 s window keeps the atlas.
        lease(sim, Arm.LEFT, SettingsMsg.OP_FB_ACQUIRE, SettingsMsg.LEASE_RENEW_MS)
        assertTrue(
            sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, 15),
                SettingsMsg.LEASE_RENEW_MS + 1),
            "a renewal of a live lease keeps the cache",
        )

        // Past the deadline, the next acquire is FRESH and drops it — the reconnect case.
        val late = SettingsMsg.LEASE_RENEW_MS + SettingsMsg.LEASE_EXPIRY_MS + 1
        lease(sim, Arm.LEFT, SettingsMsg.OP_FB_ACQUIRE, late)
        assertFalse(
            sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, 15), late + 1),
            "a fresh lease after a lapse frees the cache",
        )
    }

    @Test
    fun noticingALapseFreesTheCacheEvenWithoutATick() {
        // cfw_fb_lease_active() releases on the call that detects expiry; the model
        // must not depend on tick() having run first.
        val sim = GlassFirmwareSim()
        lease(sim, Arm.LEFT, SettingsMsg.OP_FB_ACQUIRE, 0L)
        val off = uploadAtlas(sim, Arm.LEFT, 100L)
        assertFalse(
            sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, 15),
                SettingsMsg.LEASE_EXPIRY_MS + 1),
            "the draw itself must notice the lapse",
        )
    }

    // -------------------------------------------------- malformed cache and edges
    @Test
    fun aCachedImageThatUnderRunsItsPixelCountIsRejected() {
        val sim = leased(GlassFirmwareSim())
        // [w=2][h=2] claims 4 pixels; one run of 1 supplies one.
        val bad = CfwModes.cacheUpdate(listOf(
            CfwModes.CacheWrite(TextureCache.GUARD, byteArrayOf(2, 2, 0x11))))
        assertTrue(sim.dispatchForTest(Arm.LEFT, bad, 1_000L), "the write itself is well-formed")
        assertFalse(
            sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(TextureCache.GUARD, 0, 0, 15), 1_000L),
            "an RLE stream that does not fill w*h is rejected",
        )
    }

    @Test
    fun aGlyphStraddlingTheEndOfTheCacheIsRejected() {
        val sim = leased(GlassFirmwareSim())
        // A header at the very last two bytes leaves no room for a single token.
        val at = CfwModes.TEXTURE_CACHE_SIZE - 2
        val write = CfwModes.cacheUpdate(listOf(CfwModes.CacheWrite(at, byteArrayOf(1, 1))))
        assertTrue(sim.dispatchForTest(Arm.LEFT, write, 1_000L))
        assertFailsWith<LintError>("the builder refuses the offset too") {
            CfwModes.drawImage(at, 0, 0, 15)
        }
        // And the model refuses it when hand-built, which is what the glass does.
        val handBuilt = byteArrayOf(13,
            (at and 0xFF).toByte(), ((at shr 8) and 0xFF).toByte(), 0, 0, 0, 0, 15)
        assertFalse(sim.dispatchForTest(Arm.LEFT, handBuilt, 1_000L),
            "a header at the last two bytes leaves no room for a single RLE token")
    }

    @Test
    fun aPartlyOffPanelDrawClipsInsteadOfWrappingOrFailing() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val off = b.add(TextureCache.Image(4, 4, ByteArray(16) { 15 }))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        sim.fillShadowForTest(Arm.LEFT, 0)
        assertTrue(sim.dispatchForTest(Arm.LEFT,
            CfwModes.drawImage(off, Geometry.PANEL_W - 2, Geometry.PANEL_H - 2, 15), 1_000L))
        val stride = (Geometry.PANEL_W + 1) / 2
        val p = sim.snapshot(Arm.LEFT)
        val last = (Geometry.PANEL_H - 1) * stride + ((Geometry.PANEL_W - 1) shr 1)
        assertEquals(15, p[last].toInt() and 0x0F, "the visible corner drew")
        // The two clipped columns must not have wrapped onto the next row.
        assertEquals(0, p[(Geometry.PANEL_H - 1) * stride].toInt() and 0xFF, "no wrap to x=0")
    }

    @Test
    fun aLeadingNegativeAdjustClipsTheFirstGlyphRatherThanWrapping() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val bar = TextureCache.Image(4, 2, ByteArray(8) { 15 })
        val font = b.addFont(mapOf('A' to bar), TextureCache.Image(1, 1, byteArrayOf(1)))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        sim.fillShadowForTest(Arm.LEFT, 0)
        val text = byteArrayOf(CfwModes.xAdjust(-10), 'A'.code.toByte())
        assertTrue(sim.dispatchForTest(Arm.LEFT,
            CfwModes.drawCachedText(font.tableOffset, 0, 0, 15, text), 1_000L))
        val p = sim.snapshot(Arm.LEFT)
        assertEquals(0, p[0].toInt() and 0xFF, "x went negative; nothing wrapped to the row start")
    }

    @Test
    fun aRejectedCacheUpdateLeavesTheCacheByteIdentical() {
        val sim = leased(GlassFirmwareSim())
        val off = uploadAtlas(sim, Arm.LEFT, 1_000L)
        // Two entries. The FIRST would zero the width/height of the image we just
        // uploaded; the SECOND runs off the end of the cache. The firmware validates
        // the whole list before writing anything, so the first must never land.
        val bad = byteArrayOf(
            12,
            (off and 0xFF).toByte(), ((off shr 8) and 0xFF).toByte(), 2, 0, 0, 0,
            0xFF.toByte(), 0xFF.toByte(), 4, 0, 1, 2, 3, 4,
        )
        assertFalse(sim.dispatchForTest(Arm.LEFT, bad, 1_000L), "the whole update is rejected")
        assertTrue(sim.dispatchForTest(Arm.LEFT, CfwModes.drawImage(off, 0, 0, 15), 1_000L),
            "the first entry's bytes never landed, so the original atlas is intact")
    }

    @Test
    fun theModelRejectsAModeFourteenStringByteTheBuilderWouldNeverEmit() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val font = b.addFont(mapOf('A' to TextureCache.Image(2, 2, ByteArray(4) { 9 })),
            TextureCache.Image(1, 1, byteArrayOf(1)))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        // Hand-built: [14][font16][x16][y16][opt][len][ 'A', 0xC8 ]
        val msg = byteArrayOf(14,
            (font.tableOffset and 0xFF).toByte(), ((font.tableOffset shr 8) and 0xFF).toByte(),
            0, 0, 0, 0, 15, 2, 'A'.code.toByte(), 0xC8.toByte())
        assertFalse(sim.dispatchForTest(Arm.LEFT, msg, 1_000L),
            "a byte above 127 drops the whole string, not just that character")
    }

    @Test
    fun aBatchOfCachedDrawsRunsThroughTheModelAndPresentsOnce() {
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val dot = TextureCache.Image(2, 2, ByteArray(4) { 15 })
        val off = b.add(dot)
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        sim.fillShadowForTest(Arm.LEFT, 0)
        val batch = CfwModes.batch(listOf(
            CfwModes.drawImage(off, 0, 0, 15),
            CfwModes.drawImage(off, 10, 0, 15),
        ))
        assertTrue(sim.dispatchForTest(Arm.LEFT, batch, 1_000L))
        val p = sim.snapshot(Arm.LEFT)
        assertEquals(15, (p[0].toInt() and 0xFF) shr 4, "first cached draw landed")
        assertEquals(15, (p[5].toInt() and 0xFF) shr 4, "second cached draw landed in the same flush")
    }

    // ---------------------------------------------------------------- the layout
    @Test
    fun layoutPutsAKernBeforeTheGlyphItAppliesTo() {
        val b = TextureCache.Builder()
        val two = TextureCache.Image(2, 2, ByteArray(4) { 15 })
        val font = b.addFont(mapOf('A' to two, 'B' to two), TextureCache.Image(1, 1, byteArrayOf(1)))
        val bytes = TextureCache.layout("AB", font) { l, r -> if (l == 'A' && r == 'B') 2 else 0 }
        assertContentEquals(
            byteArrayOf('A'.code.toByte(), CfwModes.xAdjust(2), 'B'.code.toByte()),
            bytes,
            "the adjust lands between the pair, where the firmware applies it",
        )
        assertEquals(6, font.measure("AB") { l, r -> if (l == 'A' && r == 'B') 2 else 0 })
    }

    @Test
    fun layoutTakesItsAdvanceFromTheAtlasNotFromTheCaller() {
        val b = TextureCache.Builder()
        val font = b.addFont(
            mapOf('A' to TextureCache.Image(3, 2, ByteArray(6) { 15 }),
                'B' to TextureCache.Image(5, 2, ByteArray(10) { 15 })),
            TextureCache.Image(1, 1, byteArrayOf(1)))
        assertEquals(3, font.width('A'))
        assertEquals(5, font.width('B'))
        assertEquals(8, font.measure("AB"), "advance is the cached image width, nothing else")
        // An unmapped character still measures, because it draws the tofu box.
        assertEquals(1, font.width('~'))
    }

    @Test
    fun layoutAndTheModelAgreeOnWhereGlyphsLand() {
        // The encoder and the firmware model are written from the same C but by
        // different hands here; make them agree on a real string.
        val sim = leased(GlassFirmwareSim())
        val b = TextureCache.Builder()
        val a = TextureCache.Image(3, 1, byteArrayOf(15, 15, 15))
        val c = TextureCache.Image(2, 1, byteArrayOf(15, 15))
        val font = b.addFont(mapOf('A' to a, 'B' to c), TextureCache.Image(1, 1, byteArrayOf(1)))
        for (m in b.messages()) sim.dispatchForTest(Arm.LEFT, m, 1_000L)
        sim.fillShadowForTest(Arm.LEFT, 0)
        val kern = { l: Char, r: Char -> if (l == 'A' && r == 'B') -1 else 0 }
        val bytes = TextureCache.layout("AB", font, kern)
        assertTrue(sim.dispatchForTest(Arm.LEFT,
            CfwModes.drawCachedText(font.tableOffset, 0, 0, 15, bytes), 1_000L))
        val p = sim.snapshot(Arm.LEFT)
        fun px(x: Int): Int {
            val v = p[x shr 1].toInt() and 0xFF
            return if (x and 1 == 0) v shr 4 else v and 0x0F
        }
        // A at 0..2, kern -1 pulls B to 2..3 — B overlaps A's last column.
        for (x in 0..3) assertEquals(15, px(x), "pixel $x")
        assertEquals(0, px(4), "the line ends where measure() says it does")
        assertEquals(4, font.measure("AB", kern))
    }

    // ---------------------------------------------------------- the capabilities
    @Test
    fun theNewFirmwareStillSatisfiesTheStartupGate() {
        val caps = GlassFirmwareSim().capabilityString
        assertTrue(caps.startsWith("EVENCFW/"))
        assertEquals(emptyList(), SettingsMsg.missingCaps(caps))
        assertEquals(16, SettingsMsg.contractVersion(caps))
        assertTrue(SettingsMsg.hasTextureCache(caps))
    }

    @Test
    fun weDoNotGateOnTokensTheFirmwareDroppedWhileKeepingTheFeature() {
        // a5d1c31 removed img576 and compass10 from the string to get it back under
        // 127 bytes; both features are still implemented. Demanding either would
        // refuse a firmware that supports them.
        assertFalse("img576" in SettingsMsg.REQUIRED_CAPS)
        assertFalse("compass10" in SettingsMsg.REQUIRED_CAPS)
    }

    @Test
    fun aCapabilityStringPastOneTwentySevenBytesStillParses() {
        // The firmware wrote field 100's length as a bare byte until a5d1c31 and
        // shipped one build where that broke the whole response. Ours reads a varint.
        val long = "EVENCFW/99 " + SettingsMsg.REQUIRED_CAPS.joinToString(" ") +
            " " + (1..30).joinToString(" ") { "padtoken$it" }
        assertTrue(long.length > 127, "the fixture must actually cross the boundary")
        val payload = wm.damage.core.wire.Pb.cat(
            wm.damage.core.wire.Pb.v(1, 2),
            wm.damage.core.wire.Pb.s(SettingsMsg.CAPABILITY_FIELD, long),
            wm.damage.core.wire.Pb.l(104, ByteArray(21)),   // the mic read-back now trails it
        )
        assertEquals(long, SettingsMsg.parseCapability(payload))
        assertEquals(emptyList(), SettingsMsg.missingCaps(long))
    }

    @Test
    fun longPressCarriesNoEventSourceBecauseTheFirmwareNeverWritesOne() {
        // Verified at instruction level: the stock sender writes EventSource only
        // for EventType 0 and 3. Since a5d1c31 a temple touchpad can raise event 9
        // too, and nothing on the wire says which surface it came from.
        assertFalse(wm.damage.core.wire.EvenHubMsg.reportsSource(
            wm.damage.core.wire.EvenHubMsg.EV_RING_LONG_PRESS))
        assertFalse(wm.damage.core.wire.EvenHubMsg.reportsSource(
            wm.damage.core.wire.EvenHubMsg.EV_RING_LONG_PRESS_RELEASE))
        assertTrue(wm.damage.core.wire.EvenHubMsg.reportsSource(
            wm.damage.core.wire.EvenHubMsg.EV_CLICK))
    }
}

/**
 * The EvenHub ack status field, pinned to Even's own enum. First light on
 * 2026-08-30 found the transport treating a SUCCESS status as a failure: the
 * glasses ack an image with 4 (`UPGRADE_IMAGE_RAW_DATA_SUCCESS`) and the
 * "non-zero means error" reading refused every session start. The simulator had
 * modeled success as an ABSENT field 8, so no offline test could see it.
 */
class AckStatusTest {

    private fun ackPayload(ackType: Int, msgId: Int, status: Long?): ByteArray =
        wm.damage.core.wire.Pb.cat(
            wm.damage.core.wire.Pb.v(1, ackType),
            wm.damage.core.wire.Pb.v(2, msgId),
            *(if (status == null) emptyArray()
              else arrayOf(wm.damage.core.wire.Pb.l(5, wm.damage.core.wire.Pb.v(8, status.toInt())))),
        )

    @Test
    fun theImageSuccessStatusIsFourAndIsNotAFailure() {
        val ack = wm.damage.core.wire.EvenHubMsg.parseAck(ackPayload(4, 7, 4L))!!
        assertEquals(4L, ack.status)
        assertEquals(null, ack.errorCode, "status 4 is UPGRADE_IMAGE_RAW_DATA_SUCCESS")
        assertEquals("UPGRADE_IMAGE_RAW_DATA_SUCCESS", ack.statusText)
    }

    @Test
    fun everyOperationsSuccessStatusReadsAsSuccess() {
        for (code in wm.damage.core.wire.EvenHubMsg.STATUS_SUCCESS) {
            val ack = wm.damage.core.wire.EvenHubMsg.parseAck(ackPayload(1, 1, code))!!
            assertEquals(null, ack.errorCode, "status $code (${ack.statusText}) is a success")
        }
    }

    @Test
    fun theFailureStatusesStillReadAsFailures() {
        for (code in listOf(1L, 2L, 3L, 5L, 7L, 9L, 11L, 14L)) {
            val ack = wm.damage.core.wire.EvenHubMsg.parseAck(ackPayload(1, 1, code))!!
            assertEquals(code, ack.errorCode, "status $code must still fail")
            assertFalse(ack.statusText.startsWith("unknown"), "status $code should be named")
        }
    }

    @Test
    fun anUnknownStatusIsTreatedAsAFailureAndSaysSo() {
        val ack = wm.damage.core.wire.EvenHubMsg.parseAck(ackPayload(1, 1, 99L))!!
        assertEquals(99L, ack.errorCode, "an unrecognised status is not assumed benign")
        assertEquals("unknown status 99", ack.statusText)
    }

    @Test
    fun theSuccessStatusMatchesTheOperationTheFirmwareIsAcking() {
        val m = wm.damage.core.wire.EvenHubMsg
        assertEquals(0L, m.successStatusFor(m.CMD_CREATE))
        assertEquals(4L, m.successStatusFor(m.CMD_IMAGE))
        assertEquals(6L, m.successStatusFor(m.CMD_REBUILD))
        assertEquals(8L, m.successStatusFor(m.CMD_TEXT_UPGRADE))
        assertEquals(10L, m.successStatusFor(m.CMD_SHUTDOWN))
        assertEquals(12L, m.successStatusFor(m.CMD_KEEPALIVE))
    }

    @Test
    fun theModelNowSendsAStatusOnEverySuccessLikeTheGlassesDo() {
        // Guards the regression directly: if the sim ever goes back to omitting
        // field 8 on success, a transport that mishandles it passes offline again.
        val sim = GlassFirmwareSim()
        val seen = ArrayList<wm.damage.core.wire.EvenHubMsg.Ack>()
        sim.attachListener(object : GlassFirmwareSim.SimDiag {
            override fun event(kind: String, detail: String) {}
            override fun panelChanged(arm: Arm) {}
            override fun notify(arm: Arm, packet: ByteArray) {
                val f = wm.damage.core.wire.AaFrame.Reassembler {}.offer(packet) ?: return
                if (f.sid == wm.damage.core.wire.EvenHubMsg.SID)
                    wm.damage.core.wire.EvenHubMsg.parseAck(f.payload)?.let { seen += it }
            }
        })
        sim.write(Arm.RIGHT, wm.damage.core.wire.AaFrame.frame(
            1, wm.damage.core.wire.EvenHubMsg.SID, 0x20,
            wm.damage.core.wire.EvenHubMsg.keepalive(3),
            wm.damage.core.wire.AaFrame.TYPE_COMMAND).single(), 0L)
        val acks = seen.filter { it.status != null }
        assertTrue(acks.isNotEmpty(), "the model must carry a status field like the firmware")
        assertTrue(acks.all { it.errorCode == null }, "and a success must not read as a failure")
    }
}
