package wm.damage.core.gfx

/**
 * Platform image decoding seam (2026-08-31, for Reader's ebook images): core
 * stays platform-free the way text does (TextRasterizer) — AWT/ImageIO
 * implements this on the desktop, BitmapFactory on the phone. Returns 8-bit
 * LUMINANCE rows (alpha premultiplied toward black: transparent = unlit,
 * which is what an additive panel means by background).
 */
interface ImageDecoder {
    class Decoded(val w: Int, val h: Int, val gray: ByteArray)

    /** Null when the bytes are not a decodable image — the caller shows a
     *  loud placeholder line, never silence. */
    fun decode(bytes: ByteArray): Decoded?
}
