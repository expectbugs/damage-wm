package wm.damage.core.windows.reader

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.content.BookMeta
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P5 (the spec's §2 P5 and §3's "reader index" row; decision B.1): the PC's reader index — what a
 * shelf card and the chapters level need from each book (its text length, chapters and cover), read one book at a time
 * and kept on disk, so a description never parses a book and the PC never holds a shelf in memory.
 */

/**
 * Per book `(path, size, mtime) -> {textLength, chapters, cover}`, computed one book at a time on [bg] (`Dispatchers.IO`)
 * after each shelf scan, reading the text and the image paths but never an image's bytes, and kept in [file]
 * (`reader-index.json` in the data directory; null keeps it in memory). An entry stands while its file's size and
 * modification time do; a moved one is computed again, a book that fails to parse is kept with its reason (its fraction
 * absent) until its file changes, and a book gone from the shelf leaves the index at the next walk. Readers take
 * [snapshot]s: the map is replaced, never changed in place.
 */
class ReaderIndex(private val file: Path?, private val bg: CoroutineScope) {
    @Serializable
    data class Chapter(val title: String, val offset: Int)

    @Serializable
    data class Entry(
        val path: String,
        val size: Long,
        val mtime: Long,
        val textLength: Int = 0,
        val chapters: List<Chapter> = emptyList(),
        /** The cover's zip path, and its number in the document's images (blob id `reader.<book>.<n>`). */
        val cover: String? = null,
        val coverRef: Int? = null,
        val images: Int = 0,
        /** Why the book could not be read; the other fields are then empty. */
        val error: String? = null,
    ) {
        /** [place]'s share of the text (0 with no place), or null when the length is unknown (C§12 `fraction`). */
        fun fraction(place: ReaderAnchor?): Double? {
            if (error != null || textLength <= 0) return null
            return ((place?.offset ?: 0).toDouble() / textLength).coerceIn(0.0, 1.0)
        }
    }

    @Serializable
    private data class Disk(val v: Int = 1, val extractor: Int = 0, val books: List<Entry> = emptyList())

    @Volatile private var entries: Map<String, Entry> = load()
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()
    private var pending: List<BookMeta>? = null
    private var running = false

    /** The index as it stands, by path. */
    fun snapshot(): Map<String, Entry> = entries

    /** The entry for the file at [path] of [size] bytes, or null (not indexed yet, or the file moved since). */
    fun entry(path: String, size: Long): Entry? = entries[path]?.takeIf { it.size == size }

    /** [l] gets each entry the walk computes, on the walk's thread (a presenter re-describes). */
    fun addListener(l: (Entry) -> Unit) {
        listeners += l
    }

    fun removeListener(l: (Entry) -> Unit) {
        listeners -= l
    }

    /** Walk [lib] (a whole shelf listing) on [bg]: one walk at a time; a listing that arrives meanwhile is walked
     *  next, and only the latest one. */
    @Synchronized
    fun update(lib: List<BookMeta>) {
        pending = lib
        if (running) return
        running = true
        bg.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val next = synchronized(this@ReaderIndex) {
                        pending.also { pending = null; if (it == null) running = false }
                    } ?: break
                    walk(next)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                synchronized(this@ReaderIndex) { running = false }
                throw e
            } catch (e: Exception) {
                // said; the next shelf scan starts a walk again
                synchronized(this@ReaderIndex) { running = false }
                Log.e("reader", "the reader index's walk stopped", e)
            }
        }
    }

    private suspend fun walk(lib: List<BookMeta>) {
        val listed = HashSet<String>()
        var changed = false
        var computed = 0
        for (b in lib) {
            kotlin.coroutines.coroutineContext.ensureActive()
            listed += b.file
            val p = Path.of(b.file)
            val size: Long
            val mtime: Long
            try {
                size = Files.size(p)
                mtime = Files.getLastModifiedTime(p).toMillis()
            } catch (e: Exception) {
                Log.w("reader", "reader index: ${b.file} could not be read (${e.message}); left as it was")
                continue
            }
            val had = entries[b.file]
            if (had != null && had.size == size && had.mtime == mtime) continue
            val e = compute(p, size, mtime)
            entries = entries + (b.file to e)
            changed = true
            // a first walk over a whole shelf is kept as it goes, so a stop part-way loses at most a few books' work
            if (++computed % SAVE_EVERY == 0) save()
            for (l in listeners) {
                try {
                    l(e)
                } catch (x: Exception) {
                    Log.e("reader", "a reader index listener failed", x)
                }
            }
        }
        val gone = entries.keys - listed
        if (gone.isNotEmpty()) {
            entries = entries - gone
            changed = true
        }
        if (changed) save()
    }

    private fun save() {
        val f = file ?: return
        try {
            Persistence.writeAtomic(f, JSON.encodeToString(Disk.serializer(),
                Disk(extractor = ReaderAnchor.EXTRACTOR, books = entries.values.sortedBy { it.path })))
        } catch (e: Exception) {
            // the index stays in memory and the next walk that changes it writes it again
            Log.e("reader", "the reader index could not be written to $f", e)
        }
    }

    /** One read at start: the file, or nothing (absent, unreadable, or another extractor's — every book is then
     *  read again). */
    private fun load(): Map<String, Entry> {
        val f = file ?: return emptyMap()
        if (!Files.exists(f)) return emptyMap()
        return try {
            val d = JSON.decodeFromString(Disk.serializer(), Files.readString(f))
            if (d.extractor != ReaderAnchor.EXTRACTOR) {
                Log.i("reader", "reader index $f is of extractor ${d.extractor}, this build reads ${ReaderAnchor.EXTRACTOR}: every book is read again")
                emptyMap()
            } else d.books.associateBy { it.path }
        } catch (e: Exception) {
            Log.e("reader", "reader index $f could not be read (${e.message}); every book is read again", e)
            emptyMap()
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private const val SAVE_EVERY = 8

        /** One book's entry, read now: the text pipeline with no image bytes read (dec. B.1). */
        fun compute(p: Path, size: Long, mtime: Long): Entry = try {
            val ex = Epub.extract(p, readImages = false, keepMarkup = false)
            val (images, cover) = EpubDoc.imageList(ex.book.text, ex.cover)
            Entry(p.toString(), size, mtime, ex.book.text.length, ex.book.chapters.map { Chapter(it.title, it.offset) },
                cover?.let { images[it] }, cover, images.size)
        } catch (e: Exception) {
            Log.w("reader", "reader index: $p could not be read: ${e.message}")
            Entry(p.toString(), size, mtime, error = e.message ?: e.javaClass.simpleName)
        }
    }
}
