package wm.damage.core.text

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded caches for the platform rasterizers (2026-09-05, `HANDOFF.md` §32).
 *
 * Both rasterizers rendered every drawn string into a fresh platform bitmap
 * and blended it pixel by pixel, on the shell loop, on every paint — a tmux
 * pane is 30+ lines of that per pushed frame, and the phone runs the same
 * loop. And every measure was a platform call (on Android a shaping pass) even
 * when the same run of text had been measured a frame earlier. The rendered
 * COVERAGE of a string depends only on the text and the resolved font, never
 * on where or at what level it is blended, so it is cached as a mask and the
 * blend reads the mask. Pixel-identical by construction: the mask is exactly
 * the bytes the uncached path read from the bitmap.
 *
 * Bounded by wholesale clearing — simple, thread-safe, and a cold restart
 * costs one extra rasterization per string. A cache is never correctness: a
 * miss renders exactly as before.
 */
data class GlyphKey(val face: Face, val px: Int, val bold: Boolean, val italic: Boolean, val text: String)

/** A rendered string: [w] × [h] coverage bytes (0..255), row-major, tight. */
class GlyphMask(val w: Int, val h: Int, val cov: ByteArray)

class MeasureCache(private val maxEntries: Int = 16_384) {
    private val map = ConcurrentHashMap<GlyphKey, Int>()
    fun get(key: GlyphKey, compute: () -> Int): Int {
        map[key]?.let { return it }
        val v = compute()
        if (map.size >= maxEntries) map.clear()
        map[key] = v
        return v
    }
    fun clear() = map.clear()
}

class RasterCache(private val maxBytes: Long = 8L shl 20) {
    private val map = ConcurrentHashMap<GlyphKey, GlyphMask>()
    private val bytes = AtomicLong(0)
    fun get(key: GlyphKey, render: () -> GlyphMask): GlyphMask {
        map[key]?.let { return it }
        val m = render()
        if (bytes.get() + m.cov.size > maxBytes) { map.clear(); bytes.set(0) }
        if (map.putIfAbsent(key, m) == null) bytes.addAndGet(m.cov.size.toLong())
        return m
    }
    fun clear() { map.clear(); bytes.set(0) }
}
