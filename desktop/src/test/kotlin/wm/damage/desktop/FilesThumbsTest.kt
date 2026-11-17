package wm.damage.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build L1, B-S5: a Files thumbnail reads the picture's dimensions first and decodes it subsampled to about twice the
 * asked size (a large photo never becomes a full raster on the PC's heap), one rendition at a time. Made-up pictures
 * in memory only.
 */
class FilesThumbsTest {
    private fun png(w: Int, h: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    /** Pin (B-S5): a 2400 × 1200 picture asked at 100 px is decoded at 200 × 100 (every 12th pixel of every 12th row),
     *  and its thumbnail fits the square, never cropped. */
    @Test
    fun aLargePictureIsDecodedSubsampled() {
        val big = png(2400, 1200)
        val decoded = FilesThumbs.decodeAbout(big, 200)!!
        assertEquals(200 to 100, decoded.width to decoded.height)
        val thumb = ImageIO.read(ByteArrayInputStream(FilesThumbs.jpeg(big, 100)!!))
        assertEquals(100 to 50, thumb.width to thumb.height)
        assertEquals(30 to 20, FilesThumbs.decodeAbout(png(30, 20), 200)!!.let { it.width to it.height }, "a small one whole")
        assertNull(FilesThumbs.jpeg("not a picture".toByteArray(), 100))
    }

    /** Pin (B-S5): one rendition at a time — a second caller waits while one is made. */
    @Test
    fun oneRenditionAtATime() {
        val small = png(40, 20)
        FilesThumbs.jpeg(small, 20)                                              // ImageIO's own start, before the watch
        val t = Thread { FilesThumbs.jpeg(small, 20) }
        val mx = ManagementFactory.getThreadMXBean()
        fun waitsOnTheLock() = mx.getThreadInfo(t.id)?.lockInfo?.identityHashCode == System.identityHashCode(FilesThumbs.one)
        synchronized(FilesThumbs.one) {
            t.start()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)        // the test's own bound
            while (!waitsOnTheLock() && t.state != Thread.State.TERMINATED && System.nanoTime() < until) Thread.onSpinWait()
            assertTrue(waitsOnTheLock(), "the second rendition waits its turn (${t.state})")
        }
        t.join(10_000)
        assertTrue(!t.isAlive)
    }
}
