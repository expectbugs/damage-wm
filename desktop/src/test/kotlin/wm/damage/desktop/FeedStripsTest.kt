package wm.damage.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wm.damage.core.windows.feed.LineArt
import wm.damage.core.windows.feed.Strips

/**
 * FEED (`FEED.md` §2.6): the real xkcd 3200 PNG through the real decoder —
 * the strip lands at 596x180 (0.81x), the §3.4 decision inverts it, and the
 * inverted strip is mostly unlit (measured 8.5 % in the pricing pass).
 */
class FeedStripsTest {
    @Test
    fun xkcdThroughTheAwtDecoderFitsInvertsAndStaysDark() {
        val png = Path.of("../core/src/test/resources/feed/xkcd-3200.png")
        assertTrue(Files.isRegularFile(png), "fixture missing at $png (run from the desktop module)")
        val d = AwtImages().decode(Files.readAllBytes(png)) ?: error("xkcd-3200.png did not decode")
        assertEquals(740, d.w); assertEquals(225, d.h)
        val s = Strips.prepare(d, 596, 16, LineArt.AUTO)
        assertEquals(596, s.w); assertEquals(180, s.h)
        assertTrue(s.inverted, "a white page inverts")
        val ink = Strips.ink(s)
        assertTrue(ink in 0.04..0.16, "inverted line art is mostly unlit: $ink")
        val plain = Strips.prepare(d, 596, 16, LineArt.NEVER)
        assertTrue(Strips.ink(plain) > 0.9, "as-is it is a lit page: ${Strips.ink(plain)}")
        assertEquals(6, Strips.cut(Strips.unpack(s)).size)
    }
}
