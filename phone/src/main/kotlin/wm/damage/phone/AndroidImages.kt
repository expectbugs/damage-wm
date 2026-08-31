package wm.damage.phone

import android.graphics.BitmapFactory
import wm.damage.core.gfx.ImageDecoder
import wm.damage.core.util.Log

/**
 * Phone ImageDecoder over BitmapFactory (2026-08-31, Reader ebook images):
 * bytes -> 8-bit luminance, alpha premultiplied toward black (transparent =
 * unlit on the additive panel). Failures return null — the Reader shows a
 * loud placeholder line.
 */
class AndroidImages : ImageDecoder {
    override fun decode(bytes: ByteArray): ImageDecoder.Decoded? = try {
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bm == null || bm.width <= 0 || bm.height <= 0) null
        else {
            val w = bm.width
            val h = bm.height
            val px = IntArray(w * h)
            bm.getPixels(px, 0, w, 0, 0, w, h)
            bm.recycle()
            val gray = ByteArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                val a = (c ushr 24) and 0xFF
                val lum = (((c ushr 16) and 0xFF) * 299 + ((c ushr 8) and 0xFF) * 587 +
                    (c and 0xFF) * 114) / 1000
                gray[i] = (lum * a / 255).toByte()
            }
            ImageDecoder.Decoded(w, h, gray)
        }
    } catch (e: Exception) {
        Log.w("images", "decode failed: ${e.message}")
        null
    }
}
