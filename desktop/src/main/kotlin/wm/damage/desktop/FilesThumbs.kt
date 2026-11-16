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
 * thumbnail: the page shows the file's icon).
 */
object FilesThumbs {
    fun jpeg(bytes: ByteArray, maxPx: Int): ByteArray? {
        val src = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (e: Exception) { null } ?: return null
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
}
