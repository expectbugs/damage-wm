package wm.damage.desktop

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import wm.damage.core.gfx.ImageDecoder
import wm.damage.core.util.Log

/**
 * Desktop ImageDecoder over ImageIO (2026-08-31, Reader ebook images):
 * PNG/JPEG/GIF/BMP -> 8-bit luminance, alpha premultiplied toward black
 * (transparent = unlit, which is what the additive panel means by
 * background). Failures return null — the Reader shows a loud placeholder.
 */
class AwtImages : ImageDecoder {
    override fun decode(bytes: ByteArray): ImageDecoder.Decoded? = try {
        val im = ImageIO.read(ByteArrayInputStream(bytes))
        if (im == null || im.width <= 0 || im.height <= 0) null
        else {
            val w = im.width
            val h = im.height
            val gray = ByteArray(w * h)
            val row = IntArray(w)
            for (y in 0 until h) {
                im.getRGB(0, y, w, 1, row, 0, w)
                for (x in 0 until w) {
                    val c = row[x]
                    val a = (c ushr 24) and 0xFF
                    val lum = (((c ushr 16) and 0xFF) * 299 + ((c ushr 8) and 0xFF) * 587 +
                        (c and 0xFF) * 114) / 1000
                    gray[y * w + x] = (lum * a / 255).toByte()
                }
            }
            ImageDecoder.Decoded(w, h, gray)
        }
    } catch (e: Exception) {
        Log.w("images", "decode failed: ${e.message}")
        null
    }
}
