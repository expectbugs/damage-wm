package wm.damage.core.windows.music

/**
 * The queue post-processing rules every lane applies (`MUSIC.md` §9.3, the
 * G2CC resolver facts re-stated as our own code): 'sound effects' excluded
 * unless named; 'spoken word' excluded from SHUFFLE-class (discovery) lanes
 * unless asked for; one member per dupe cluster with the higher-fidelity file
 * winning (non-archived, then the lower path on a tie — order-independent);
 * a mild artist-spread shuffle; a size cap except for finite album/playlist
 * sets. Pure — unit-tested without a host.
 */
object Rules {

    /** What a lane needs to know about a candidate row. */
    data class Cand(
        val id: Int,
        val title: String,
        val artist: String = "",
        val album: String = "",
        val durMs: Int = 0,
        val path: String = "",
        val genres: List<String> = emptyList(),
        val styles: List<String> = emptyList(),
        val moods: List<String> = emptyList(),
        val dupeCluster: Int? = null,
    ) {
        fun ref(): TrackRef = TrackRef(id, title, artist, album, durMs)
        fun terms(): Sequence<String> = (genres.asSequence() + styles.asSequence() + moods.asSequence()).map { it.lowercase() }
    }

    class Opts(
        val shuffle: Boolean,
        val excludeSpoken: Boolean,
        /** null = play the whole set (album / playlist). */
        val cap: Int?,
        val requestLc: String,
        val random: java.util.Random = java.util.Random(),
    )

    /** The library's actual term is SINGULAR ('sound effect', mostly under styles). */
    val SFX_TERMS = setOf("sound effect", "sound effects", "sfx")
    val SPOKEN_TERMS = setOf("spoken word")

    fun hasTerm(c: Cand, names: Set<String>): Boolean = c.terms().any { it in names }

    fun fidelityRank(path: String): Int = when (path.substringAfterLast('.', "").lowercase()) {
        "flac" -> 5
        "wav", "aiff" -> 4
        "m4a", "aac" -> 3
        "ogg", "opus" -> 2
        "mp3" -> 1
        else -> 0
    }

    private fun archived(path: String): Boolean = path.contains("/Archive/")

    /** One member per dupe cluster; keeps the first-seen slot for the winner
     *  so a sorted input stays sorted. */
    fun dedupeClusters(rows: List<Cand>): List<Cand> {
        val best = HashMap<Int, Int>()          // cluster → index in out
        val out = ArrayList<Cand>()
        for (r in rows) {
            val c = r.dupeCluster
            if (c == null) { out.add(r); continue }
            val at = best[c]
            if (at == null) { best[c] = out.size; out.add(r); continue }
            val cur = out[at]
            val rr = fidelityRank(r.path)
            val cr = fidelityRank(cur.path)
            val better = rr > cr ||
                (rr == cr && !archived(r.path) && archived(cur.path)) ||
                (rr == cr && archived(r.path) == archived(cur.path) && r.path < cur.path)
            if (better) out[at] = r
        }
        return out
    }

    /** Fisher–Yates + one mild spread pass so the same artist rarely plays
     *  back to back. */
    fun <T> artistSpreadShuffle(rows: List<T>, artistOf: (T) -> String, random: java.util.Random = java.util.Random()): List<T> {
        val a = ArrayList(rows)
        for (i in a.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        for (i in 1 until a.size) {
            val prev = artistOf(a[i - 1])
            if (prev.isNotEmpty() && artistOf(a[i]) == prev) {
                for (j in i + 1 until a.size) {
                    if (artistOf(a[j]) != prev) { val t = a[i]; a[i] = a[j]; a[j] = t; break }
                }
            }
        }
        return a
    }

    fun postProcess(rows: List<Cand>, o: Opts): List<TrackRef> {
        var out = rows
        val wantsSfx = Regex("sound effects?|sfx").containsMatchIn(o.requestLc)
        if (!wantsSfx) out = out.filter { !hasTerm(it, SFX_TERMS) }
        if (o.excludeSpoken && !Regex("spoken|interlude").containsMatchIn(o.requestLc)) {
            out = out.filter { !hasTerm(it, SPOKEN_TERMS) }
        }
        out = dedupeClusters(out)
        if (o.shuffle) out = artistSpreadShuffle(out, { it.artist }, o.random)
        val cap = o.cap
        if (cap != null && out.size > cap) out = out.subList(0, cap)
        return out.map { it.ref() }
    }

    /** "N matched but everything was excluded" reads as exactly that. */
    fun exclusionNote(matched: Int, queued: Int): String =
        if (queued == 0 && matched > 0) " — all matches are excluded content (sound effects / spoken word)" else ""

    val STOPWORDS = setOf(
        "play", "some", "stuff", "something", "anything", "music", "songs", "song",
        "tracks", "track", "a", "an", "the", "of", "and", "or", "me", "my", "please",
        "mix", "good", "nice", "like", "random", "shuffle", "shuffled", "surprise",
    )
    val RANDOM_RE = Regex("^(random( mix)?|surprise( me)?|anything|shuffle|mix)$", RegexOption.IGNORE_CASE)

    /** Leading/trailing punctuation off a token; internal characters survive (AC/DC). */
    fun trimPunct(t: String): String = t.replace(Regex("^[\"'`.,!?;:()]+|[\"'`.,!?;:()]+$"), "")

    /** The request's content tokens (lower-cased, punctuation-trimmed, stopwords dropped). */
    fun tokens(request: String): List<String> =
        request.lowercase().split(Regex("\\s+")).map(::trimPunct).filter { it.isNotEmpty() && it !in STOPWORDS }

    /** LIKE metacharacters in a user token must match literally. */
    fun escapeLike(t: String): String = t.replace(Regex("[%_\\\\]")) { "\\" + it.value }
}
