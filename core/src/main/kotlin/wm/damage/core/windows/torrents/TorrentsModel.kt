package wm.damage.core.windows.torrents

import kotlinx.serialization.Serializable

/**
 * TORRENTS — the model shared by the window, the providers and the wire
 * (`TORRENTS.md`, design settled with Adam 2026-09-01). Field names follow
 * qBittorrent's Web API 2.11 `torrents/info` rows (read from the 5.1.4
 * source, `src/webui/api/serialize/serialize_torrent.cpp`) so the mapping
 * is one-to-one and nothing is guessed.
 */

@Serializable
data class Transfer(
    val hash: String,
    val name: String,
    /** qBittorrent's state string — 5.x names (`stoppedDL`, not `pausedDL`). */
    val state: String,
    val progress: Double,
    val size: Long,
    val downloaded: Long,
    val uploaded: Long,
    val dlSpeed: Long,
    val upSpeed: Long,
    /** Seconds; 8640000 is qBittorrent's "infinite". */
    val eta: Long,
    val ratio: Double,
    val seeds: Int,
    val seedsTotal: Int,
    val peers: Int,
    val peersTotal: Int,
    /** Unix seconds. */
    val addedOn: Long,
    /** Unix seconds; ≤ 0 while incomplete. */
    val completedOn: Long,
    /** Seconds spent finished — TL's one-week rule reads this. */
    val seedingTime: Long,
    val savePath: String,
    val contentPath: String,
    val category: String,
    val tags: String,
    val tracker: String,
) {
    val downloading: Boolean get() = state in DL_STATES
    val finished: Boolean get() = state in UP_STATES
    val stopped: Boolean get() = state == "stoppedDL" || state == "stoppedUP"
    val error: Boolean get() = state == "error" || state == "missingFiles"
    val checking: Boolean get() = state.startsWith("checking") || state == "moving"
    /** Finished but seeded for under a week — TorrentLeech's hit-and-run window (Adam). */
    val underAWeek: Boolean get() = finished && seedingTime < WEEK_S

    companion object {
        const val WEEK_S = 7L * 86_400
        val DL_STATES = setOf("downloading", "stalledDL", "queuedDL", "metaDL", "forcedMetaDL", "forcedDL", "checkingDL", "stoppedDL")
        val UP_STATES = setOf("uploading", "stalledUP", "queuedUP", "forcedUP", "checkingUP", "stoppedUP")
    }
}

@Serializable
data class SessionStats(
    val dlSpeed: Long = 0,
    val upSpeed: Long = 0,
    val dlSession: Long = 0,
    val upSession: Long = 0,
    val allDl: Long = 0,
    val allUl: Long = 0,
    val freeSpace: Long = -1,
    val ratio: String = "",
    val peers: Int = 0,
    val status: String = "",
    val version: String = "",
)

/** A change the host noticed between two polls. Kinds: done · error · added · removed. */
@Serializable
data class TorrentEvent(val seq: Long, val kind: String, val hash: String, val name: String, val atMs: Long)

@Serializable
data class Snapshot(
    /** Bumps whenever the transfer list or the session line changed. */
    val version: Long,
    /** The host process' identity: a new epoch means a restart (no event replay). */
    val epoch: Long,
    val atMs: Long,
    val transfers: List<Transfer>,
    val session: SessionStats,
    /** The newest event sequence the host holds. */
    val lastSeq: Long,
)

@Serializable
data class TFile(val name: String, val size: Long, val progress: Double, val priority: Int)

@Serializable
data class TransferDetail(
    val hash: String,
    val files: List<TFile>,
    val comment: String = "",
    val createdOn: Long = 0,
    val pieces: Int = 0,
    val pieceSize: Long = 0,
    val trackers: List<String> = emptyList(),
)

// ------------------------------------------------------------ the tracker
@Serializable
data class TlCategory(val id: Int, val group: String, val name: String)

@Serializable
data class TlItem(
    val fid: String,
    val name: String,
    val filename: String,
    val categoryId: Int,
    val size: Long,
    val seeders: Int,
    val leechers: Int,
    val snatched: Int,
    /** The site's timestamp text (UTC), shown as an age. */
    val addedAt: String,
    val tags: List<String>,
    val freeleech: Boolean,
    val imdb: String = "",
    val rating: Double = 0.0,
    val genres: String = "",
)

@Serializable
data class TlPage(val items: List<TlItem>, val page: Int, val perPage: Int, val total: Int)

@Serializable
data class TlFile(val name: String, val size: String)

@Serializable
data class TlDetail(
    val fid: String,
    val name: String,
    val category: String,
    val size: String,
    val seeders: Int,
    val leechers: Int,
    val snatched: Int,
    val added: String,
    val uploader: String,
    val tags: List<String>,
    val description: String,
    val nfo: String,
    val files: List<TlFile>,
    /** The page URL — the "Open on PC" target. */
    val url: String,
)

@Serializable
data class TlAccount(
    val user: String,
    val uploaded: String,
    val downloaded: String,
    val ratio: String,
    val points: String,
    val klass: String,
)

/**
 * The provider seam (§16.10): Local on the PC (qBittorrent over loopback +
 * the tracker session), Remote on the phone (the window channel). Everything
 * here is called OFF the shell loop except [snapshot], [stateLine] and
 * [eventsSince], which read cached state.
 */
interface TorrentsProvider : AutoCloseable {
    interface Listener {
        /** A new snapshot landed (the list or the session line changed). */
        fun snapshot(s: Snapshot)
        /** An event the host noticed, in sequence order. */
        fun event(e: TorrentEvent)
        /** Provider health for the window's staleness surface: "" = healthy. */
        fun state(line: String)
    }

    fun stateLine(): String
    fun addListener(l: Listener)
    fun removeListener(l: Listener)

    /** The window is focused: poll at [paceMs]; unfocused: the idle pacing. */
    fun setFocused(focused: Boolean, paceMs: Long)

    /** The last snapshot, or null before the first poll — cheap. */
    fun snapshot(): Snapshot?

    /** Poll now (off-loop); the result arrives through the listeners. */
    fun refresh()

    /** Events after [seq] in [epoch]; a foreign epoch answers empty. */
    fun eventsSince(seq: Long, epoch: Long): List<TorrentEvent>

    fun detail(hash: String): TransferDetail
    fun start(hashes: List<String>)
    fun stop(hashes: List<String>)
    fun recheck(hashes: List<String>)
    fun delete(hashes: List<String>, withFiles: Boolean)

    fun tlCategories(): List<TlCategory>
    /** [sort]: added · seeders · size · name (all descending). */
    fun tlBrowse(categoryId: Int?, page: Int, sort: String): TlPage
    fun tlSearch(query: String, page: Int, sort: String): TlPage
    fun tlDetail(fid: String): TlDetail
    /** Fetch the torrent file with the tracker session and hand it to
     *  qBittorrent; returns the display name. */
    fun tlAdd(fid: String, stopped: Boolean): String
    fun tlAccount(): TlAccount

    /** `xdg-open` on the host: a payload path or the tracker page URL. */
    fun openOnPc(target: String)

    override fun close() {}
}

/** Display formatting shared by the window's rows, lens and documents. */
object Fmt {
    /** Locale-fixed (review 2026-09-01 C9): the phone's locale must not turn
     *  1.5 MB into 1,5 MB on glass. */
    private val L = java.util.Locale.ROOT

    fun bytes(b: Long): String = when {
        b < 0 -> "?"
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(L, b / 1048576.0)
        b < 1024L * 1024 * 1024 * 1024 -> "%.2f GB".format(L, b / 1073741824.0)
        else -> "%.2f TB".format(L, b / 1099511627776.0)
    }

    fun speed(bps: Long): String = when {
        bps <= 0 -> "0"
        bps < 1024L * 1024 -> "${bps / 1024} KB/s"
        else -> "%.1f MB/s".format(L, bps / 1048576.0)
    }

    /** A duration in seconds as `12 m`, `3h 05m`, `2d 4h`. */
    fun dur(s: Long): String = when {
        s < 0 -> "?"
        s < 60 -> "${s} s"
        s < 3600 -> "${s / 60} m"
        s < 86_400 -> "${s / 3600}h ${"%02d".format(L, (s % 3600) / 60)}m"
        else -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
    }

    /** qBittorrent reports 8640000 for "unknown"; drawn as "-" (Latin-1 only on this panel). */
    fun eta(s: Long): String = if (s <= 0 || s >= 8_640_000) "-" else dur(s)

    /** Age of a unix-seconds stamp, coarse. */
    fun age(unixS: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (unixS <= 0) return ""
        val d = maxOf(0L, nowMs / 1000 - unixS)
        return when {
            d < 60 -> "${d}s"
            d < 3600 -> "${d / 60}m"
            d < 86_400 -> "${d / 3600}h"
            else -> "${d / 86_400}d"
        }
    }

    fun pct(p: Double): String = "${(p * 100).toInt().coerceIn(0, 100)}%"

    fun ratio(r: Double): String = if (r < 0) "inf" else "%.2f".format(L, r)
}
