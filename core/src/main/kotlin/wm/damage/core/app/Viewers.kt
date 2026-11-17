package wm.damage.core.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-2, package Q2 (the spec's §2.3 and §5 V1; `CONTRACT.md` §8; S1-2 R0 §3): the viewers of the PC-served and
 * record scopes. A viewer is one connection's subscription that carries a need — `focus` (in front, being worked in,
 * with the pace it asks), `glance`, or none — and a live source runs at the pace of its most demanding viewer and parks
 * with none. A viewer ends with its `unsub`, a later `sub` of the same scope whose need is none (a page gone hidden;
 * a second `sub` REPLACES the need), its connection's end, or when its connection has sent no `ping` for two of its
 * intervals (C§8's one liveness rule, built here for viewers only: the connection itself still ends on TCP keepalive).
 *
 * Writes run on the core executor ([AppNet]); a source reads [of] on its own poll thread, once per tick, and that
 * read applies the same liveness rule, so a connection gone quiet is never counted between two sweeps.
 */
class Viewers(
    /** The lane's pacing (a page and the phone both ping every 15 s); an interval is never taken shorter. */
    private val pingEveryMs: Long = PING_EVERY_MS,
) {
    /** A viewer's need: [kind] `focus`, `glance` or `none`; [paceMs] the pace a focus asks (null: the source's own). */
    data class Need(val kind: String, val paceMs: Long? = null) {
        companion object {
            const val FOCUS = "focus"
            const val GLANCE = "glance"
            const val NONE = "none"
            val KINDS = setOf(FOCUS, GLANCE, NONE)

            /** `{kind, paceMs?}`; a kind this build does not know, or a pace that is not a positive whole number,
             *  throws [IllegalArgumentException] naming it. */
            fun parse(o: JsonObject): Need {
                val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?: throw IllegalArgumentException("need: kind")
                require(kind in KINDS) { "need: kind '$kind' is none of ${KINDS.sorted()}" }
                val pace = o["paceMs"]?.let { p ->
                    (p as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("need: paceMs is not a positive whole number")
                }
                return Need(kind, pace)
            }
        }
    }

    /** One viewer: [window] is the window its scope belongs to (null: a page of the PC's own). */
    data class Viewer(val conn: Long, val scope: String, val window: String?, val need: Need)

    /** Told on the executor of each viewer that starts, changes its need, or ends ([ended], with [why]). */
    fun interface Listener {
        fun changed(v: Viewer, ended: Boolean, why: String)
    }

    private class Ping(@Volatile var last: Long, @Volatile var gap: Long)

    private val viewers = ConcurrentHashMap<Pair<Long, String>, Viewer>()
    private val pings = ConcurrentHashMap<Long, Ping>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) {
        listeners += l
    }

    /** A connection started at [now]: its welcome counts as its first ping. Executor only. */
    fun opened(conn: Long, now: Long) {
        pings[conn] = Ping(now, 0)
    }

    /** [conn] sent a ping at [now]. Executor only. */
    fun pinged(conn: Long, now: Long) {
        val p = pings[conn] ?: return
        p.gap = now - p.last
        p.last = now
    }

    /** [conn] holds [scope] with [need]; a need of none ends its viewer (R0 §3: a second `sub` replaces the need).
     *  Executor only. */
    fun put(conn: Long, scope: String, window: String?, need: Need) {
        val key = conn to scope
        if (need.kind == Need.NONE) {
            remove(conn, scope, "its need is none")
            return
        }
        val v = Viewer(conn, scope, window, need)
        val was = viewers.put(key, v)
        if (was != v) tell(v, false, if (was == null) "started" else "need changed from ${was.need.kind}")
    }

    /** [conn] no longer holds [scope]. Executor only. */
    fun remove(conn: Long, scope: String, why: String) {
        viewers.remove(conn to scope)?.let { tell(it, true, why) }
    }

    /** [conn] ended: every viewer it held ends. Executor only. */
    fun ended(conn: Long, why: String) {
        pings.remove(conn)
        for (k in viewers.keys.filter { it.first == conn }) viewers.remove(k)?.let { tell(it, true, why) }
    }

    /** Ends the viewers of every connection that has sent no ping for two of its intervals; returns how many.
     *  Executor only (the lane's sweep, on its pacing). */
    fun sweep(now: Long): Int {
        var n = 0
        for (conn in viewers.keys.map { it.first }.toSet()) {
            val quiet = quietFor(conn, now) ?: continue
            Log.i(TAG, "connection $conn sent no ping for ${quiet / 1000} s (two of its intervals): its viewers end")
            for (k in viewers.keys.filter { it.first == conn }) viewers.remove(k)?.let {
                n++
                tell(it, true, "no ping for ${quiet / 1000} s")
            }
        }
        return n
    }

    /** How long [conn] has been quiet past two of its intervals at [now], or null when it is live. */
    private fun quietFor(conn: Long, now: Long): Long? {
        val p = pings[conn] ?: return now                       // no connection known: nothing keeps it
        val interval = maxOf(p.gap, pingEveryMs)
        val quiet = now - p.last
        return if (quiet > 2 * interval) quiet else null
    }

    /** The live viewers of [window] at [now]. Any thread. */
    fun of(window: String, now: Long): List<Viewer> =
        viewers.values.filter { it.window == window && quietFor(it.conn, now) == null }.sortedBy { it.conn }

    /** [conn]'s viewers, one word each (the diagnosis). Any thread. */
    fun lines(conn: Long): List<String> = viewers.values.filter { it.conn == conn }.sortedBy { it.scope }
        .map { "${it.scope} ${it.need.kind}" + (it.need.paceMs?.let { p -> " $p ms" } ?: "") }

    private fun tell(v: Viewer, ended: Boolean, why: String) {
        for (l in listeners) {
            try {
                l.changed(v, ended, why)
            } catch (e: Exception) {
                Log.e(TAG, "a viewer listener failed on ${v.scope} of connection ${v.conn}", e)
            }
        }
    }

    companion object {
        private const val TAG = "app-viewers"
        /** The lane's ping pacing: the page's `conn.mjs` and `AppClient` both ping every 15 s. */
        const val PING_EVERY_MS = 15_000L

        /** The stronger of two needs: focus over glance over none; between two of a kind, the faster pace. */
        fun stronger(a: Need?, b: Need): Need {
            if (a == null) return b
            fun rank(n: Need) = when (n.kind) { Need.FOCUS -> 2; Need.GLANCE -> 1; else -> 0 }
            if (rank(a) != rank(b)) return if (rank(a) > rank(b)) a else b
            val pa = a.paceMs ?: Long.MAX_VALUE
            val pb = b.paceMs ?: Long.MAX_VALUE
            return if (pb < pa) b else a
        }
    }
}
