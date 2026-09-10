package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wm.damage.core.windows.music.AdaptivePlaylists
import wm.damage.core.windows.music.Db
import wm.damage.core.windows.music.MusicDb

/**
 * `MUSIC.md` §9.8 (2026-09-10) — the rule-managed playlists G2CC refreshed
 * at its boot are Damage's now. Pinned over a recording fake: the diff
 * keeps order / appends / drops / collapses duplicates and is null when
 * nothing moved; materialize drops sound effects, keeps one member per dupe
 * cluster and orders artist → album → path with the nameless last; a
 * refresh writes only the playlists that changed, skips a corrupt rule
 * loudly, survives a failing one; and the self-check's plan writes nothing.
 */
class AdaptivePlaylistsTest {

    private class FakeDb : Db {
        val calls = ArrayList<Pair<String, List<Any?>>>()
        var rules: List<Db.Row> = emptyList()
        var current: (Int) -> List<Int> = { emptyList() }
        var plan: (List<Any?>) -> List<Db.Row> = { emptyList() }
        var fault: String? = null
        override fun query(sql: String, vararg args: Any?): List<Db.Row> {
            calls.add(sql to args.toList())
            return when {
                sql.contains("rule::text AS rule") -> rules
                sql.contains("FOR UPDATE") -> listOf(Db.Row(mapOf("adaptive" to true)))
                sql.contains("SELECT track_id FROM playlist_tracks") -> current(args[0] as Int).map { Db.Row(mapOf("track_id" to it)) }
                sql.contains("FROM tracks t JOIN track_meta m") -> { fault?.let { throw IllegalStateException(it) }; plan(args.toList()) }
                else -> emptyList()
            }
        }
        override fun exec(sql: String, vararg args: Any?): Int { calls.add(sql to args.toList()); return 1 }
        override fun <T> tx(block: (Db) -> T): T = block(this)
        fun writes() = calls.filter { it.first.startsWith("INSERT") || it.first.startsWith("DELETE") || it.first.startsWith("UPDATE") }
    }

    private fun cand(id: Int, artist: String, album: String, path: String, styles: List<String> = emptyList(), cluster: Int? = null) =
        Db.Row(mapOf("id" to id, "path" to path, "title" to "t$id", "artist" to artist, "album" to album, "dur_ms" to 1000,
            "genres" to null, "styles" to styles, "moods" to null, "dupe_cluster" to cluster))

    private fun rule(id: Int, name: String, json: String) = Db.Row(mapOf("id" to id, "name" to name, "rule" to json))

    @Test
    fun nextKeepsOrderAppendsDropsCollapsesDuplicatesAndIsNullWhenNothingMoved() {
        val n = AdaptivePlaylists.next(listOf(1, 2, 3), listOf(3, 1, 4))
        assertNotNull(n)
        assertEquals(listOf(1, 3, 4), n!!.ids, "retained keep their order, new append in target order")
        assertEquals(1, n.added); assertEquals(1, n.removed)
        assertNull(AdaptivePlaylists.next(listOf(1, 2), listOf(2, 1)), "same members = no write")
        assertNull(AdaptivePlaylists.next(emptyList(), emptyList()))
        val dup = AdaptivePlaylists.next(listOf(1, 1), listOf(1))
        assertNotNull(dup, "a duplicate-only difference still rewrites")
        assertEquals(listOf(1), dup!!.ids); assertEquals(0, dup.added); assertEquals(0, dup.removed)
        assertEquals(listOf(5, 6), AdaptivePlaylists.next(emptyList(), listOf(5, 6))!!.ids)
    }

    @Test
    fun materializeDropsSoundEffectsKeepsOneMemberPerClusterAndOrdersArtistAlbumPath() {
        val db = FakeDb()
        db.plan = { listOf(
            cand(1, "Zed", "A", "/z.mp3"),
            cand(2, "", "X", "/n.flac"),
            cand(3, "Alpha", "B", "/a.mp3", styles = listOf("sound effect")),
            cand(4, "beta", "C", "/b.mp3", cluster = 7),
            cand(5, "beta", "C", "/b.flac", cluster = 7),
        ) }
        val a = AdaptivePlaylists(MusicDb(db, listOf("/home/user/Music")))
        val out = a.materialize(MusicDb.Plan(genres = listOf("metal")))
        assertEquals(listOf(5, 1, 2), out.map { it.id }, "flac wins its cluster; the sound effect is out; the nameless artist sorts last")
        val (sql, args) = db.calls.last { it.first.contains("FROM tracks t JOIN track_meta m") }
        assertTrue(sql.contains("LIMIT ?") && args.last() == AdaptivePlaylists.MATERIALIZE_LIMIT, "uncapped in practice")
        assertEquals(listOf("metal"), (args[0] as Db.TextArr).v)
    }

    @Test
    fun refreshWritesOnlyWhatChangedSkipsACorruptRuleAndSurvivesAFailure() {
        val db = FakeDb()
        db.rules = listOf(
            rule(1, "Metal", "{\"genres\": [\"metal\"]}"),
            rule(2, "Corrupt", "{\"nope\": 1}"),
            rule(3, "Same", "{\"styles\": [\"x\"]}"),
        )
        db.current = { id -> when (id) { 1 -> listOf(10, 99); 3 -> listOf(20); else -> emptyList() } }
        db.plan = { args -> when ((args[0] as Db.TextArr).v.first()) {
            "metal" -> listOf(cand(10, "A", "A", "/a.mp3"), cand(11, "B", "B", "/b.mp3"))
            "x" -> listOf(cand(20, "C", "C", "/c.mp3"))
            else -> emptyList()
        } }
        val a = AdaptivePlaylists(MusicDb(db, listOf("/home/user/Music")))
        val o = a.refreshAll("test")
        assertEquals(3, o.playlists); assertEquals(1, o.changed); assertEquals(1, o.added); assertEquals(1, o.removed)
        assertEquals(1, o.skipped); assertEquals(0, o.failed)
        val w = db.writes()
        assertEquals(listOf("UPDATE playlists SET updated_at = now() WHERE id = ?", "DELETE FROM playlist_tracks WHERE playlist_id = ?"),
            w.take(2).map { it.first })
        assertEquals(listOf<Any?>(1), w[0].second)
        val inserts = w.filter { it.first.startsWith("INSERT") }
        assertEquals(listOf(listOf<Any?>(1, 0, 10), listOf<Any?>(1, 1, 11)), inserts.map { it.second }, "dense positions, kept first")
        assertTrue(w.none { it.second.firstOrNull() == 3 }, "the unchanged playlist is not written")
        // the lock comes first, and only for real rules
        val locks = db.calls.filter { it.first.contains("FOR UPDATE") }.map { it.second[0] }
        assertEquals(listOf<Any?>(1, 3), locks)
        // a database fault: the failure is counted, the others continue, nothing is written
        db.calls.clear(); db.fault = "db down"
        val f = a.refreshAll("test 2")
        assertEquals(2, f.failed); assertEquals(1, f.skipped); assertEquals(0, f.changed)
        assertTrue(db.writes().isEmpty())
    }

    @Test
    fun planDerivesTheSameOutcomeAndWritesNothing() {
        val db = FakeDb()
        db.rules = listOf(rule(1, "Metal", "{\"genres\": [\"metal\"]}"), rule(2, "Corrupt", "[]"))
        db.current = { listOf(10, 99) }
        db.plan = { listOf(cand(10, "A", "A", "/a.mp3"), cand(11, "B", "B", "/b.mp3")) }
        val p = AdaptivePlaylists(MusicDb(db, listOf("/home/user/Music"))).plan()
        assertEquals(2, p.size)
        assertEquals(2, p[0].current); assertEquals(2, p[0].members)
        assertEquals(listOf(10, 11), p[0].next!!.ids); assertEquals(1, p[0].next!!.removed)
        assertTrue(p[1].corrupt)
        assertTrue(db.writes().isEmpty(), "the self-check never writes")
    }
}
