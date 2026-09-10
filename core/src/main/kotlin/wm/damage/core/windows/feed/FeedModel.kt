package wm.damage.core.windows.feed

import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * FEED — the model shared by the window, the engine and the wire
 * (`FEED.md`, design settled with Adam 2026-09-09). Field names are ours;
 * each fetcher documents which feed field fills which.
 */

/** FEED.md §3.7 — the kinds a configured source can be. */
@Serializable
enum class SourceKind { REDDIT, SLASHDOT, XKCD, EIGHTBIT, SMBC, RSS }

/** One configured (or typed) source. */
@Serializable
data class SourceCfg(
    val id: String,
    val kind: SourceKind,
    val name: String,
    /** Reddit: the subreddit (`popular`, `linux`). */
    val sub: String = "",
    /** Slashdot: the section feed name (`Main`, `Linux` — §2.2's table). */
    val section: String = "",
    /** RSS: the feed URL. */
    val url: String = "",
    /** RSS: the entry's first image IS the content (a webcomic). */
    val image: Boolean = false,
) {
    val binge: Boolean get() = kind == SourceKind.EIGHTBIT

    companion object {
        /** The day-one list (verdict 1) — applied when config.json has no `feedSources`. */
        val DEFAULTS = listOf(
            SourceCfg("popular", SourceKind.REDDIT, "popular", sub = "popular"),
            SourceCfg("slashdot", SourceKind.SLASHDOT, "slashdot", section = "Main"),
            SourceCfg("xkcd", SourceKind.XKCD, "xkcd"),
            SourceCfg("smbc", SourceKind.SMBC, "SMBC"),
            SourceCfg("8bt", SourceKind.EIGHTBIT, "8-Bit Theater"),
        )
    }
}

/** What the window sees per source — CHEAP, read from the engine's cache. */
@Serializable
data class SourceStatus(
    val id: String,
    val kind: SourceKind,
    val name: String,
    val binge: Boolean,
    /** Typed through Browse… and not pinned — forgotten when the engine forgets it. */
    val transient: Boolean,
    val count: Int,
    /** Bumps whenever the item list changed. */
    val version: Long,
    val lastFetchMs: Long,
    val lastOkMs: Long,
    /** "" = healthy; else the staleness / refusal line, said with a duration. */
    val state: String,
    /** The binge archive's page count once its index is known. */
    val bingeTotal: Int = 0,
    /** The newest item's publish stamp — the window's "new since" reference. */
    val newestMs: Long = 0,
    /** The configured source itself (not for transient ones) — a fallback
     *  engine adopts the PC's list from these (FEED.md §3.7). */
    val cfg: SourceCfg? = null,
)

@Serializable
enum class ItemKind { TEXT, LINK, IMAGE, VIDEO, COMIC }

@Serializable
data class Item(
    val id: String,
    val source: String,
    val title: String,
    /** The item's own page (a Reddit permalink, a Slashdot story, a comic's page). */
    val link: String,
    val author: String = "",
    val publishedMs: Long = 0,
    /** Plain text, short — the lens and the article floor. */
    val summary: String = "",
    /** The feed's own HTML fragment (selftext, a description) — the article
     *  when extraction has nothing better. */
    val body: String = "",
    val kind: ItemKind = ItemKind.LINK,
    /** LINK: where the item points (the article to extract); IMAGE: the image. */
    val target: String = "",
    /** -1 = the feed does not say. */
    val comments: Int = -1,
    val commentsUrl: String = "",
    /** COMIC: the strip's image URL. */
    val image: String = "",
    /** COMIC: xkcd's alt text, SMBC's hovertext. */
    val alt: String = "",
    /** SMBC: the bonus panel's URL, filled when the comic is first opened. */
    val bonus: String = "",
    /** xkcd number. */
    val num: Int = 0,
    /** The subreddit / the Slashdot section, for the row tail. */
    val extra: String = "",
    /** First seen by THIS engine (retention, "new" ordering ties). */
    val seenMs: Long = 0,
)

@Serializable
data class ItemPage(val items: List<Item>, val total: Int, val version: Long)

/** One article block: `h` heading · `p` paragraph · `li` list item · `q`
 *  quote · `pre` preformatted · `img` an image by URL. */
@Serializable
data class Block(val kind: String, val text: String = "", val url: String = "")

@Serializable
data class Article(
    val itemId: String,
    val title: String,
    val blocks: List<Block>,
    /** False = the feed's own text is showing (extraction found nothing better). */
    val extracted: Boolean,
    /** Said on the title when [extracted] is false for a reason worth saying. */
    val note: String = "",
)

@Serializable
data class Comment(
    val author: String, val publishedMs: Long, val text: String, val depth: Int = 0,
    /** Slashdot: the comment's own title and its moderation (`5, Insightful`). */
    val title: String = "", val score: String = "",
)

/** One page of a binge archive. */
@Serializable
data class Episode(val num: Int, val title: String, val dateMs: Long, val url: String, val image: String = "")

/** A prepared strip: scaled to the document width, quantized, packed two
 *  pixels per byte (high nibble first — the firmware's own 4bpp order). */
class Strip(val w: Int, val h: Int, val inverted: Boolean, val levels: Int, val packed: ByteArray)

class ComicPack(val item: Item, val strip: Strip, val bonus: Strip?)

class EpisodePack(val episode: Episode, val strip: Strip)

/** FEED.md §3.4 — the inversion policy. */
enum class LineArt { AUTO, NEVER, ALWAYS }

object FeedIds {
    /** A stable item id: the source and the feed's own key, hashed — the
     *  same on every engine, so read marks match across the PC and the phone. */
    fun id(source: String, key: String): String = sha1("$source|$key").substring(0, 16)

    fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("0123456789abcdef"[(b.toInt() shr 4) and 15]).append("0123456789abcdef"[b.toInt() and 15])
        return sb.toString()
    }
}

/** Display formatting shared by rows, lenses and documents. */
object FeedFmt {
    /** Age of a millisecond stamp, coarse. */
    fun age(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return ""
        val d = maxOf(0L, (nowMs - ms) / 1000)
        return when {
            d < 60 -> "${d}s"
            d < 3600 -> "${d / 60}m"
            d < 86_400 -> "${d / 3600}h"
            else -> "${d / 86_400}d"
        }
    }

    /** A duration in seconds as `40 s`, `3 min`, `2 h`. */
    fun dur(s: Long): String = when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h"
    }
}
