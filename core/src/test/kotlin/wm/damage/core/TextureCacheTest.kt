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
