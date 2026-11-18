package wm.damage.core.windows.feed

import wm.damage.core.app.Levels
import wm.damage.core.web.Blob
import wm.damage.core.web.BlobBody
import wm.damage.core.web.BlobSource

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-3, piece B2 (`CONTRACT.md` §9.4): Feed's pictures for the web, in colour and uninverted — an article's
 * images, a comic and its bonus panel, the archive's pages. `feed.<key>` (`FeedStore.imageKey` of the picture's URL)
 * names only a picture a Feed scope handed out in this process (the 4,096 newest), never a URL a page names; its bytes
 * are the engine's cached original, else fetched once. The type comes from the bytes; anything else is a download.
 */
class FeedBlobs(private val provider: FeedProvider) : BlobSource {
    private val urls = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > KEEP
    }

    /** The blob id of [url], remembered so a page may ask for it. */
    fun idOf(url: String): String {
        val id = PREFIX + FeedStore.imageKey(url)
        synchronized(urls) { urls[id] = url }
        return id
    }

    override fun resolve(id: String): BlobBody? {
        val url = synchronized(urls) { urls[id] } ?: return null
        val bytes = provider.original(url)
        return Blob(typeOf(bytes), bytes, Levels.VIEW, id.removePrefix(PREFIX) + extOf(typeOf(bytes)))
    }

    companion object {
        const val PREFIX = "feed."
        const val KEEP = 4_096

        /** The type the bytes say (the picture formats the sites serve); anything else is served as a download. */
        fun typeOf(b: ByteArray): String {
            fun at(i: Int, vararg v: Int) = b.size >= i + v.size && v.indices.all { (b[i + it].toInt() and 0xff) == v[it] }
            return when {
                at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
                at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
                at(0, 0x47, 0x49, 0x46, 0x38) -> "image/gif"
                at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
                at(4, 0x66, 0x74, 0x79, 0x70, 0x61, 0x76, 0x69, 0x66) -> "image/avif"
                at(0, 0x42, 0x4D) -> "image/bmp"
                else -> "application/octet-stream"
            }
        }

        private fun extOf(type: String): String = when (type) {
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "image/avif" -> ".avif"
            "image/bmp" -> ".bmp"
            else -> ".bin"
        }
    }
}
