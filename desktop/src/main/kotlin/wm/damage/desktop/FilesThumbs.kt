package wm.damage.desktop

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q5 (the spec's §4.1 "Blobs"): the colour thumbnails the Files page's grid shows (`?r=thumb:<px>`),
 * made on the PC by AWT and handed to the core's `FilesBlobs` (the core stays free of AWT). A picture is fitted inside
 * a square of the asked size, never cropped, and written as a JPEG; one that does not decode answers null (no
 * thumbnail: the page shows the file's icon). L1 B-S5: the picture's dimensions are read first and it is decoded
 * subsampled to about twice the asked size, one at a time (a grid of large photos never holds many full rasters).
 */
object FilesThumbs {
    /** One rendition at a time: a caller waits its turn (a wait, not a timeout). */
    internal val one = Any()

    fun jpeg(bytes: ByteArray, maxPx: Int): ByteArray? = synchronized(one) {
        val src = try { decodeAbout(bytes, 2 * maxPx) } catch (e: Exception) { null } ?: return null
        val scale = minOf(1.0, maxPx.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)                    // a transparent picture's background, as a viewer shows it
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        val buf = ByteArrayOutputStream()
        return if (ImageIO.write(out, "jpg", buf)) buf.toByteArray() else null
    }

    /** The picture in [bytes] with every n-th pixel of every n-th row, n the largest that keeps its long side at
     *  [px] or more (its dimensions read before any pixel); null when no reader knows it. */
    internal fun decodeAbout(bytes: ByteArray, px: Int): BufferedImage? =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { inp ->
            val r = ImageIO.getImageReaders(inp).asSequence().firstOrNull() ?: return null
            try {
                r.input = inp
                val n = maxOf(1, maxOf(r.getWidth(0), r.getHeight(0)) / px)
                r.read(0, r.defaultReadParam.apply { setSourceSubsampling(n, n, 0, 0) })
            } finally {
                r.dispose()
            }
        }
}
