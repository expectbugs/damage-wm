package wm.damage.core.app

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.2 and §5 V2; `CONTRACT.md` §3.2 "collection … paged", §9.3 "paged
 * collections"): a paged collection's pages as PC-served scopes. A page is its own scope,
 * `<window>/<listing>/page:<n>` (S1-1's grammar of `reader/book:<id>/part:<n>`, so the lane's frames do not change),
 * answered `{count | null, version, from, size, rows, more}`; `count: null` with `more` is a listing of unknown length
 * (the tracker's, endless as the glasses treat it).
 *
 * A listing's `version` moves when a new read of one of its pages differs from the last read of that page; the
 * provider's listeners are then told, and the `app` lane describes again every page of that listing a connection holds
 * at an older version (`AppNet`, `Described.version`). A client keeps its scroll by row id. No row patches in S1-2: a
 * moved version sends the held pages again. Versions are per process: a client subscribes again after a restart and
 * gets fresh pages.
 */
class Paged(
    /** Rows per page for a listing the PC reads whole and pages itself (Files' folders: the spec's 500). */
    val pageRows: Int = PAGE_ROWS,
) {
    /** One read of page n: [rows] the page's rows; [count] the listing's length, or null when unknown; [more] true
     *  when rows follow it; [from] its first row's index (default `n * pageRows`); [signature] what reads are compared
     *  by — the page's rows unless the provider names another (the whole listing, for one read whole). */
    data class Read(
        val rows: List<JsonObject>,
        val count: Int?,
        val more: Boolean,
        val from: Long? = null,
        val signature: String? = null,
    )

    private class Listing {
        var version = 1L
        val seen = HashMap<Int, String>()
    }

    private val listings = ConcurrentHashMap<String, Listing>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** [l] is told the listing whose version moved; on the describing thread. */
    fun addListener(l: (String) -> Unit) {
        listeners += l
    }

    /** Page [n] of [listing] from one [read]: the page answer, at the listing's version after this read. */
    fun describe(listing: String, n: Int, read: () -> Read): JsonObject {
        val r = read()
        val sig = r.signature ?: digest(JsonArray(r.rows).toString())
        val l = listings.computeIfAbsent(listing) { Listing() }
        val (version, moved) = synchronized(l) {
            val before = l.seen.put(n, sig)
            if (before != null && before != sig) l.version++
            l.version to (before != null && before != sig)
        }
        if (moved) for (f in listeners) f(listing)
        return page(r, r.from ?: n.toLong() * pageRows, version)
    }

    /** [listing]'s version now, or null when no page of it was read. */
    fun version(listing: String): Long? = listings[listing]?.let { synchronized(it) { it.version } }

    /** Forgets [listing] (its pages are no longer held anywhere the provider knows of). */
    fun forget(listing: String) {
        listings.remove(listing)
    }

    companion object {
        /** The spec's page of a folder (§4.1: 500 rows). */
        const val PAGE_ROWS = 500
        private val SCOPE = Regex("^([a-z][a-z0-9]{0,31})/(.+)/page:(\\d{1,6})$")

        /** A page scope as (window, listing, n), or null. */
        fun parse(scope: String): Triple<String, String, Int>? =
            SCOPE.matchEntire(scope)?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt()) }

        fun scope(window: String, listing: String, n: Int): String = "$window/$listing/page:$n"

        /** The page answer (the spec's §2.2 shape). */
        fun page(r: Read, from: Long, version: Long): JsonObject = buildJsonObject {
            if (r.count == null) put("count", JsonNull) else put("count", r.count)
            put("version", version)
            put("from", from)
            put("size", r.rows.size)
            put("rows", JsonArray(r.rows))
            put("more", r.more)
        }

        fun digest(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
