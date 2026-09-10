package wm.damage.core.windows.music

import java.text.Collator
import java.util.Locale
import wm.damage.core.util.Log

/**
 * Adaptive (rule-managed) playlists — the G2CC mechanism re-stated as our
 * own code (`MUSIC.md` §9.8, 2026-09-10; G2CC's `server/src/playlists.ts`
 * and `resolver.ts materializeRule` read for the facts the day its server
 * was retired). A playlist whose `rule` column holds a plan owns its
 * membership: the rule is the plan filter (genres / styles / moods each AND
 * across lists, OR within, over the union of the three tag columns; energy
 * and bpm ranges; vocals and artists exact; exclude), materialized UNCAPPED,
 * sound-effect variants out, ONE member per dupe cluster (the higher
 * fidelity file), spoken word IN (a genre collection is not shuffle
 * discovery), ordered artist → album → path. A refresh keeps retained
 * members in their relative order, appends new matches at the tail and
 * drops non-matches — and writes nothing when nothing changed.
 *
 * Runs at host start, after a grab's enrichment and after a rescan;
 * SERIALIZED (one refresh at a time — two write phases on one playlist
 * collided in G2CC). A corrupt rule is skipped loudly; one playlist's
 * failure never stops the others. [plan] is the same derivation with no
 * write at all — the self-check's view.
 */
class AdaptivePlaylists(private val db: MusicDb) {
    private val lock = Any()

    /** The next membership: null when nothing would change. */
    class Next(val ids: List<Int>, val added: Int, val removed: Int)

    class Outcome(val playlists: Int, val changed: Int, val added: Int, val removed: Int, val skipped: Int, val failed: Int) {
        override fun toString(): String = "$playlists adaptive · $changed changed (+$added −$removed)" +
            (if (skipped > 0) " · $skipped corrupt" else "") + (if (failed > 0) " · $failed FAILED" else "")
    }

    class Planned(val id: Int, val name: String, val corrupt: Boolean, val current: Int, val members: Int, val next: Next?)

    /** The full membership a rule selects today, in playlist order. */
    fun materialize(rule: MusicDb.Plan): List<Rules.Cand> {
        val rows = db.planCands(rule, MATERIALIZE_LIMIT)
        val deduped = Rules.dedupeClusters(rows.filter { !Rules.hasTerm(it, Rules.SFX_TERMS) })
        val collator = Collator.getInstance(Locale.ENGLISH)
        return deduped.sortedWith(Comparator { a, b ->
            collator.compare(a.artist.ifEmpty { LAST }, b.artist.ifEmpty { LAST }).takeIf { it != 0 }
                ?: collator.compare(a.album.ifEmpty { LAST }, b.album.ifEmpty { LAST }).takeIf { it != 0 }
                ?: a.path.compareTo(b.path)
        })
    }

    /** Re-derive EVERY rule-managed playlist. */
    fun refreshAll(reason: String): Outcome = synchronized(lock) {
        val rules = db.adaptiveRules()
        if (rules.isEmpty()) return Outcome(0, 0, 0, 0, 0, 0)
        Log.i(TAG, "refreshing ${rules.size} adaptive playlist(s) ($reason)")
        var changed = 0; var added = 0; var removed = 0; var skipped = 0; var failed = 0
        for (r in rules) {
            val rule = Resolver.parsePlan(r.ruleJson)
            if (rule == null) {
                Log.e(TAG, "adaptive \"${r.name}\" (id ${r.id}) has a CORRUPT rule — skipped (fix or delete it): ${r.ruleJson}")
                skipped++
                continue
            }
            try {
                val target = materialize(rule).map { it.id }
                val n = db.refreshAdaptive(r.id, target)
                if (n != null) {
                    changed++; added += n.added; removed += n.removed
                    Log.i(TAG, "adaptive \"${r.name}\" refreshed: ${n.ids.size} members (+${n.added} −${n.removed})")
                }
            } catch (e: Exception) {
                failed++
                Log.e(TAG, "adaptive \"${r.name}\" refresh FAILED (the others continue)", e)
            }
        }
        Outcome(rules.size, changed, added, removed, skipped, failed).also { Log.i(TAG, "adaptive refresh ($reason): $it") }
    }

    /** What a refresh WOULD do — no write anywhere (the self-check). */
    fun plan(): List<Planned> = synchronized(lock) {
        db.adaptiveRules().map { r ->
            val rule = Resolver.parsePlan(r.ruleJson)
            if (rule == null) Planned(r.id, r.name, corrupt = true, current = 0, members = 0, next = null)
            else {
                val target = materialize(rule).map { it.id }
                val current = db.playlistTrackIds(r.id)
                Planned(r.id, r.name, corrupt = false, current = current.size, members = target.size, next = next(current, target))
            }
        }
    }

    companion object {
        const val TAG = "music-adaptive"
        /** Uncapped in practice: a genre playlist is ALL of that genre. */
        const val MATERIALIZE_LIMIT = 100_000
        /** Sorts after every name (a sort key, never drawn — hence no literal glyph). */
        private val LAST = Char.MAX_VALUE.toString()

        /** The next membership from the current rows (position order) and the
         *  rule's target: retained members keep their relative order — first
         *  occurrence only, since a converted manual playlist may carry
         *  duplicate appends that a rule's one-member semantics collapse —
         *  new matches append in target order, non-matches drop. Null = no
         *  write; a duplicate-only difference still rewrites (kept < current). */
        fun next(current: List<Int>, target: List<Int>): Next? {
            val targetSet = target.toHashSet()
            val currentSet = current.toHashSet()
            val seen = HashSet<Int>()
            val kept = current.filter { it in targetSet && seen.add(it) }
            val added = target.filter { it !in currentSet }
            val removed = current.count { it !in targetSet }
            if (added.isEmpty() && removed == 0 && kept.size == current.size) return null
            return Next(kept + added, added.size, removed)
        }
    }
}
