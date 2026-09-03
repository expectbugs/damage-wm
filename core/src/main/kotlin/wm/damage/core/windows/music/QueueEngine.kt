package wm.damage.core.windows.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * PURE queue logic (`MUSIC.md` §5, unit-tested without a host): the modes
 * (Queue · Shuffle · Radio · Library random), next/prev, "shuffle keeps the
 * current entry first", the radio/library-random low-water request,
 * remove/move/insert, and entry ids (`qid`) — the row identity the window's
 * cursor follows (verdict 4 + the §12 row-identity trap). No I/O, no clock:
 * the player decides what to do when [next] runs out (a fill, a stop).
 */
class QueueEngine(private val random: java.util.Random = java.util.Random()) {
    private val list = ArrayList<QueueEntry>()
    var index: Int = 0
        private set
    var mode: Mode = Mode.SHUFFLE
        private set
    var label: String = ""
        private set
    private var nextQid = 1L

    val entries: List<QueueEntry> get() = list
    val size: Int get() = list.size
    val current: QueueEntry? get() = list.getOrNull(index)
    val remaining: Int get() = maxOf(0, list.size - index - 1)
    val isEmpty: Boolean get() = list.isEmpty()

    private fun mint(t: TrackRef) = QueueEntry(nextQid++, t)

    /** A fresh queue starting at [startIndex]; in SHUFFLE the start track
     *  stays first and the rest is artist-spread shuffled (verdict 9 — the
     *  default mode shuffles the queue it was handed). */
    fun set(tracks: List<TrackRef>, startIndex: Int, mode: Mode, label: String) {
        list.clear()
        this.mode = mode
        this.label = label
        if (tracks.isEmpty()) { index = 0; return }
        val start = startIndex.coerceIn(0, tracks.size - 1)
        val ordered = if (mode == Mode.SHUFFLE) {
            listOf(tracks[start]) + Rules.artistSpreadShuffle(tracks.filterIndexed { i, _ -> i != start }, { it.artist }, random)
        } else tracks
        for (t in ordered) list.add(mint(t))
        index = if (mode == Mode.SHUFFLE) 0 else start
    }

    fun next(): Boolean {
        if (index + 1 >= list.size) return false
        index++
        return true
    }

    fun prev(): Boolean {
        if (index <= 0) return false
        index--
        return true
    }

    fun indexOf(qid: Long): Int = list.indexOfFirst { it.qid == qid }

    fun playFrom(qid: Long): Boolean {
        val i = indexOf(qid)
        if (i < 0) return false
        index = i
        return true
    }

    /** After the current entry (the last inserted lands right after it, so
     *  the batch plays in order). */
    fun insertNext(tracks: List<TrackRef>): List<QueueEntry> {
        val fresh = tracks.map(::mint)
        val at = if (list.isEmpty()) 0 else index + 1
        list.addAll(at.coerceIn(0, list.size), fresh)
        return fresh
    }

    fun append(tracks: List<TrackRef>): List<QueueEntry> {
        val fresh = tracks.map(::mint)
        list.addAll(fresh)
        return fresh
    }

    /** Never the current entry (§8.1: it is never removable). */
    fun remove(qid: Long): Boolean {
        val i = indexOf(qid)
        if (i < 0 || i == index) return false
        list.removeAt(i)
        if (i < index) index--
        return true
    }

    /** Move an entry by [delta] rows; the current entry may move too (the
     *  index follows it). */
    fun move(qid: Long, delta: Int): Boolean {
        val i = indexOf(qid)
        if (i < 0) return false
        val j = (i + delta).coerceIn(0, list.size - 1)
        if (j == i) return false
        // the entry the cursor is ON, captured BEFORE the list moves — the
        // index rule below has to leave that same row current whatever moved
        // (the old assertion read `current` after the move and then admitted
        // any in-range index, so it asserted nothing)
        val was = list.getOrNull(index)
        val e = list.removeAt(i)
        list.add(j, e)
        index = when {
            i == index -> j                          // the current entry itself moved
            i < index && j >= index -> index - 1     // it slid out from above and back in below
            i > index && j <= index -> index + 1     // …or in from below
            else -> index
        }
        check(was == null || list.getOrNull(index) === was) {
            "queue move left the cursor on a different entry (i=$i j=$j index=$index)"
        }
        return true
    }

    fun clear() {
        list.clear()
        index = 0
    }

    /** Randomize what follows the current entry (the current stays first). */
    fun shuffleRest() {
        if (list.size - index - 1 < 2) return
        val head = list.subList(0, index + 1).toList()
        val rest = Rules.artistSpreadShuffle(list.subList(index + 1, list.size).toList(), { it.track.artist }, random)
        list.clear(); list.addAll(head); list.addAll(rest)
    }

    /** SHUFFLE reshuffles the remainder once; the other modes only flag. */
    fun setMode(m: Mode) {
        val was = mode
        mode = m
        if (m == Mode.SHUFFLE && was != Mode.SHUFFLE) shuffleRest()
    }

    /** Radio / library random: fewer than [LOW_WATER] unplayed left. */
    fun needsFill(): Boolean = (mode == Mode.RADIO || mode == Mode.LIBRARY_RANDOM) && list.isNotEmpty() && remaining <= LOW_WATER

    /** The last few played ids up to the current (radio seeds). */
    fun seedIds(n: Int = 5): List<Int> = list.subList(maxOf(0, index - n + 1), minOf(list.size, index + 1)).map { it.track.id }

    fun ids(): List<Int> = list.map { it.track.id }

    /** The synced record's slice: entries with their qids, the index, mode, label. */
    fun toJson(): JsonObject = jsonOf(list, index, mode, label, nextQid)

    companion object {
        const val LOW_WATER = 2

        /** The same record from a SNAPSHOT (a caller off the engine's thread). */
        fun jsonOf(queue: List<QueueEntry>, index: Int, mode: Mode, label: String, nextQid: Long = 0L): JsonObject = buildJsonObject {
            put("index", index)
            put("mode", mode.name)
            put("label", label)
            put("nextQid", maxOf(nextQid, (queue.maxOfOrNull { it.qid } ?: 0L) + 1))
            put("queue", buildJsonArray {
                for (e in queue) add(buildJsonObject {
                    put("qid", e.qid); put("id", e.track.id); put("title", e.track.title)
                    put("artist", e.track.artist); put("album", e.track.album); put("dur", e.track.durMs)
                })
            })
        }
    }

    fun fromJson(o: JsonObject) {
        list.clear()
        (o["queue"] as? JsonArray)?.forEach { el ->
            try {
                val e = el.jsonObject
                list.add(QueueEntry(e["qid"]?.jsonPrimitive?.longOrNull ?: nextQid++,
                    TrackRef(e["id"]?.jsonPrimitive?.intOrNull ?: return@forEach, e["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        e["artist"]?.jsonPrimitive?.contentOrNull ?: "", e["album"]?.jsonPrimitive?.contentOrNull ?: "",
                        e["dur"]?.jsonPrimitive?.intOrNull ?: 0)))
            } catch (e: Exception) {
                // the rest of the queue survives a torn row, but the loss is SAID
                wm.damage.core.util.Log.w("queue", "unreadable queue row dropped: ${e.message}")
            }
        }
        nextQid = maxOf(o["nextQid"]?.jsonPrimitive?.longOrNull ?: 1L, (list.maxOfOrNull { it.qid } ?: 0L) + 1)
        index = (o["index"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, maxOf(0, list.size - 1))
        mode = o["mode"]?.jsonPrimitive?.contentOrNull?.let { n -> Mode.entries.firstOrNull { it.name == n } } ?: Mode.SHUFFLE
        label = o["label"]?.jsonPrimitive?.contentOrNull ?: ""
    }
}
