package wm.damage.core.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import wm.damage.core.util.Log
import wm.damage.core.web.Devices

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P2 (`CONTRACT.md` §9.2, §9.3, §13 (b) "cursors"; the spec's §2 P2 and §3 "per-connection
 * outbox"): the PC's side of the `app` lane. One [AppNet] per process over the record's [AppHost]; the content host
 * hands it every connection that asks for the lane. Per connection: the content host's thread reads, ONE sender thread
 * writes from a bounded [Outbox], and everything that touches the record or the connection table runs on the core
 * executor — a `sub`'s snapshot and the changes published after it are queued in one order, so a client
 * never misses a change between its snapshot and the live stream, and never gets one twice.
 *
 * Package P6 (`CONTRACT.md` §10.4, §10.5, §10.8): the same lane on the web interface listener (a WebSocket, [LaneIo]),
 * every op, subscription and the device table checked against the connection's level at apply time ([levels],
 * [minLevel]), the device table's description and actions ([DevicesCore], level full, never queued), and a revoked or
 * replaced device's connections ended with the reason ([endNow]).
 *
 * Package P7 (C§9.2 `snap` "the description or level"; the spec's §2 P7 "Kotlin side"): PC-served scopes outside the
 * record's order — a window's description, one of its levels, Main's cards, the diagnosis ([Described], [attach]) —
 * each answered as a `desc` frame, described off the executor (a book's parse never holds it), checked against the
 * connection's level and contracts on the executor, and described again, coalesced, when a record change or the
 * provider's own source moves them.
 *
 * Build S1-2, package Q2 (the spec's §2.2–§2.4, §5 V1–V3): several providers of PC-served scopes ([register], and each
 * converted window's running parts through [serve]), each described on its own executor when it names one — a tracker
 * page that takes seconds or a folder on a cold disk never holds Reader's part or Main's cards; a held scope whose
 * source version moved is described again ([Described.version]: a paged listing's pages); viewers with a need and
 * the ping liveness rule ([viewers]); and never-queued program ops by `(conn, k)`, checked here at apply time and run
 * on the window's own pool, answered Sent (with the call's `result`) or Refused to their connection only.
 *
 * Build S1-3, piece B11 (C§8, C§9.2): live sources ([Live], `live/<window>/<src>`): a `sub` is checked against the
 * source's level at attach, takes a viewer with its need, and the source sends `live {src, …}` frames through the
 * connection's outbox (a page that cannot keep up is ended `slow` and comes back to a fresh `init`); its `unsub`, a
 * need of none, the viewers' sweep and the connection's end detach it. Each takes the usage note of a described scope.
 */
class AppNet(
    private val host: AppHost,
    /** This PC's contract versions ([Contracts.MINE]; tests name their own window). */
    private val contracts: Map<String, Int> = Contracts.MINE,
    private val outboxCap: Int = OUTBOX_CAP,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** The level each action needs (P6; tests name their own window's). */
    private val levels: ActionLevels = AppHost.LEVELS,
    /** The level a window's subscription needs. */
    private val minLevel: (String) -> String = AppHost::minLevel,
    /** The device table's description and actions on the PC (P6); null = not served on this lane. */
    private val devices: DevicesCore? = null,
    /** S1-2 Q2: true when a never-queued op of this window and action goes into an outside program ([Windows]). */
    private val isProgram: (String, String) -> Boolean = Windows::isProgram,
    /** S1-2 Q2: the lane's ping pacing, below which no viewer's interval is taken ([Viewers]). */
    pingEveryMs: Long = Viewers.PING_EVERY_MS,
) : AutoCloseable {

    /** One connection's frames: the content port's length-prefixed frames, or a WebSocket's text frames (P6). */
    interface LaneIo {
        /** The next frame; throws at its end (an [EOFException] when the client closed). */
        fun read(): JsonObject

        /** Writes one frame; called by the connection's one sender thread. */
        fun write(frame: ByteArray)
    }

    /**
     * P7: the PC-served scopes of one provider. [gate] and [follows] run on the executor and are cheap; [describe] runs
     * on [describeOn] (the lane's shared describer when null) and may block (a book's parse, a tracker page).
     */
    interface Described {
        /** What [scope] needs, or null when this provider does not serve it. */
        fun gate(scope: String): Gate?

        /** [scope]'s description now, or null when it names nothing. */
        fun describe(scope: String): JsonObject?

        /** True when [c] changes [scope]'s description; [c] null = the provider's own source moved (a shelf scan, the
         *  reader index). */
        fun follows(scope: String, c: Change?): Boolean

        /** [l] is called when the provider's own source moved; any thread. */
        fun addListener(l: () -> Unit)

        /** S1-2 Q2: the executor this provider's descriptions run on; null = the lane's one shared describer. */
        val describeOn: Executor? get() = null

        /** S1-2 Q2: [scope]'s source version now (a paged listing's), or null when it has none. When the provider's
         *  source moves, a held scope whose description carried another `version` is described again. Any thread. */
        fun version(scope: String): Long? = null
    }

    /** A PC-served scope's needs: [window] the contract window it belongs to (null: a page of the PC's own — Main's
     *  cards, the diagnosis), [min] the level it needs. */
    data class Gate(val window: String?, val min: String)

    /**
     * S1-3 B11 (C§8, C§9.2): one window's live sources, `live/<window>/<src>` — a stream or pictures that run only while
     * a viewer holds them and are never in the record. [gate] runs on the executor and is cheap; [attach] and [detach]
     * are called there and must not block (a process is started or ended on a thread of the source's own).
     */
    interface Live {
        /** What [scope] needs, or null when this source does not serve it. */
        fun gate(scope: String): Gate?

        /** [scope] for connection [conn]: its messages through [send] (any thread, in order), each an object with one of
         *  `init`, `frame`, `data`, `end`. Called again for the same pair (a page shown again): started afresh, a new
         *  `init`. A source that cannot start throws, and the subscription is refused in its words. */
        fun attach(scope: String, conn: Long, send: (JsonObject) -> Unit)

        /** [conn] no longer holds [scope]: nothing more is sent to it. */
        fun detach(scope: String, conn: Long)
    }

    /** Connection ids grow across restarts (seeded from the clock), so an op naming a connection of an earlier
     *  process never matches a new one. */
    private val nextConn = AtomicLong(clock() * 1_000)

    /** P7: the shared describer thread, and the (connection, scope) pairs queued on a describer (one each at a time). */
    private val describing = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-describe").apply { isDaemon = true }
    }
    private val toDescribe = ConcurrentHashMap.newKeySet<Pair<Long, String>>()

    /** S1-2 Q2: one provider of PC-served scopes: its executor, and for a converted window its id and Main's card. */
    private class Provider(val d: Described, val on: Executor, val window: String?, val card: (() -> JsonObject?)?)

    /** Asked in the order registered: the first whose gate names a scope serves it. */
    private val providers = CopyOnWriteArrayList<Provider>()

    /** S1-2 Q2: a window's program handler and its pool ([own] = made here, ended at [close]). */
    private class Program(val handler: ProgramHandler, val pool: ExecutorService, val own: Boolean)

    private val programs = ConcurrentHashMap<String, Program>()

    /** S1-3 B11: the live sources served here, asked in the order registered. */
    private val lives = CopyOnWriteArrayList<Live>()

    /** S1-2 Q2 (the spec's §2.3): the viewers of this lane's scopes; a live source reads [Viewers.of] per tick. */
    val viewers = Viewers(pingEveryMs)

    /** S1-2 Q2: told on the executor of each connection that ends (an upload not yet begun is cancelled with it). */
    private val endListeners = CopyOnWriteArrayList<(Long) -> Unit>()

    /** A PC-served scope a connection holds: its provider, and the source version its last description carried. */
    private class Held(val p: Provider) {
        @Volatile var sent: Long? = null
    }

    /** A program op in flight: what it is and when it was taken (the diagnosis shows its age). */
    private class Flight(val window: String, val action: String, val at: Long)

    /** [page]: a web page (its welcome carries the bundle), whose scopes are the usage record's (L1 R-e). */
    private inner class Conn(val id: Long, val peer: Peer, val shared: Set<String>, val outbox: Outbox, val page: Boolean) {
        /** Executor only. */
        val scopes = LinkedHashSet<String>()
        /** The PC-served scopes this connection holds (P7). Executor only. */
        val described = LinkedHashMap<String, Held>()
        /** The windows this connection may read: shared, and within its level (P6). */
        val visible: Set<String> = shared.filterTo(LinkedHashSet()) { Levels.atLeast(peer.level, minLevel(it)) }
        /** Why the PC ended this connection (its device revoked or replaced); executor only. */
        var endedBy: String? = null
        /** S1-2 Q2: program ops taken and not yet answered, by `k`. Executor only. */
        val flights = LinkedHashMap<Long, Flight>()
        /** L1 R-a: subscriptions refused while the record's log was closed, served again on the tick it opens. */
        val closedSubs = ArrayList<() -> Unit>()
        /** S1-3 B11: the live sources this connection holds (one viewer each). Executor only. */
        val lives = LinkedHashMap<String, Live>()
        /** S1-3 fix A4: the connection ended (closed, or its device revoked or replaced); read on a program's pool, so a
         *  key queued behind a stalled one is not typed late into a prompt that has moved on. */
        @Volatile var gone = false
    }

    /** The live connections. Executor only. */
    private val conns = LinkedHashMap<Long, Conn>()

    private val listener: (Change) -> Unit = { c -> publish(c) }

    private val devicesListener: (Devices.Event) -> Unit = { e -> onDevices(e) }

    /** S1-2 Q2: the viewers' sweep, on the record's pacing tick (2 s); L1 R-a: once the record's log is open, what it
     *  refused meanwhile is served. */
    private val sweep: () -> Unit = {
        viewers.sweep(clock())
        if (host.logOpen) for (c in conns.values) if (c.closedSubs.isNotEmpty()) {
            val again = c.closedSubs.toList()
            c.closedSubs.clear()
            again.forEach { it() }
        }
    }

    init {
        host.addListener(listener)
        host.addTickListener(sweep)
        devices?.table?.addListener(devicesListener)
        // S1-3 B11: a live viewer that ends — its `unsub`, a need of none, the viewers' sweep — detaches its source; a
        // connection's end detaches its sources itself ([detachLives]) before its viewers end
        viewers.addListener { v, ended, _ -> if (ended && v.scope.startsWith(AppWire.LIVE)) liveEnded(v.conn, v.scope) }
    }

    /** How many connections are live (for the start line and the tests); read on the executor. */
    fun connections(): Int = host.executor.call { conns.size }

    /** The live connections, one line each (the diagnosis page, P7; S1-2 Q2: with their viewers and the program ops
     *  in flight, each with its age); read on the executor. Never call it there. */
    fun connectionLines(): List<String> = host.executor.call {
        val now = clock()
        conns.values.map { c ->
            "${c.peer.device} (${c.peer.level}) · connection ${c.id} · ${c.scopes.size + c.described.size + c.lives.size} scope(s)" +
                viewers.lines(c.id).joinToString("") { " · viewer $it" } +
                c.flights.values.joinToString("") { " · ${it.window} ${it.action} sent ${(now - it.at) / 1000} s ago, no answer yet" }
        }
    }

    /**
     * P7: serves [d]'s scopes on this lane from now on (both PC host builders, from `startWeb`). Its source's moves
     * describe again, coalesced, every scope that follows them. S1-2 Q2: one of several providers ([register]).
     */
    fun attach(d: Described) = register(d)

    /** S1-2 Q2: adds a provider of PC-served scopes (the PC's own pages: [PcScopes]); asked after those before it. */
    fun register(d: Described) = add(Provider(d, d.describeOn ?: describing, null, null))

    /**
     * S1-2 Q2 (the spec's §2.5, the registry's running half): one converted window's parts on this lane — its scopes,
     * the handler of its program ops (run on [pool], or on a pool of its own made here), and its card on Main (asked
     * on the describer of Main's provider; Main is described again whenever [scopes]' source moves). Called by both PC
     * host builders, once per window, before a page can ask.
     */
    fun serve(window: String, scopes: Described? = null, program: ProgramHandler? = null,
              card: (() -> JsonObject?)? = null, pool: ExecutorService? = null, live: Live? = null) {
        live?.let { lives += it }      // S1-3 B11: the window's live sources
        if (scopes != null || card != null) add(Provider(scopes ?: NO_SCOPES, scopes?.describeOn ?: describing, window, card))
        if (program != null) programs.put(window, Program(program, pool ?: Executors.newCachedThreadPool { r ->
            Thread(r, "program-$window").apply { isDaemon = true }
        }, pool == null))?.let { old -> if (old.own) old.pool.shutdown() }
    }

    /** Main's cards of the windows served here with one, in the registry's order; on the caller's thread. A card that
     *  fails is said and left out of this description (the next asks again). */
    fun cards(): List<JsonObject> {
        val order = Windows.ALL.map { it.id }
        return providers.filter { it.card != null && it.window != null }
            .sortedBy { order.indexOf(it.window).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .mapNotNull { p ->
                try {
                    p.card!!.invoke()
                } catch (e: Exception) {
                    Log.w(TAG, "Main's card of ${p.window} could not be made: ${e::class.simpleName}: ${e.message}")
                    null
                }
            }
    }

    /** S1-2 Q2: [l] is told, on the executor, the id of every connection that ends. */
    fun addEndListener(l: (Long) -> Unit) {
        endListeners += l
    }

    /** L1 F10: the PC-served scopes some connection holds now (Files reads again, at a job's end, only the folders a
     *  page holds). Never call it on the executor. */
    fun held(): Set<String> = host.executor.call { conns.values.flatMapTo(HashSet()) { it.described.keys } }

    private fun add(p: Provider) {
        providers += p
        p.d.addListener { host.executor.execute("app described moved") { moved(p) } }
    }

    /** [p]'s source moved (executor): every scope it serves that follows the move, or whose source version moved
     *  since its last description, is described again; so is Main where [p] carries a card. */
    private fun moved(p: Provider) {
        for (c in conns.values) for ((s, h) in c.described) {
            val again = if (h.p === p) p.d.follows(s, null) || versionMoved(p, s, h)
                else p.card != null && s == PcScopes.MAIN
            if (again) describeFor(c, s)
        }
    }

    private fun versionMoved(p: Provider, scope: String, h: Held): Boolean {
        val now = p.d.version(scope) ?: return false
        return now != h.sent
    }

    /**
     * Serves one connection whose first lane frame, [helloLine], asked for the lane; blocks until it ends. The
     * caller (the content host) has checked the credential: [peer] is who it names. The caller owns the socket.
     */
    fun serve(sock: Socket, inp: DataInputStream, out: DataOutputStream, helloLine: String, peer: Peer) =
        serve(sock, object : LaneIo {
            override fun read(): JsonObject = AppWire.read(inp)
            override fun write(frame: ByteArray) = AppWire.write(out, frame)
        }, helloLine, peer)

    /** As above over [io] (P6: the web listener's WebSocket); [bundle] goes into its `welcome` (C§9.5). */
    fun serve(sock: Socket, io: LaneIo, helloLine: String, peer: Peer, bundle: JsonObject? = null) {
        val hello = try {
            AppWire.parseHello(AppWire.parse(helloLine))
        } catch (e: Exception) {
            refuse(io, sock, AppWire.END_PROTO, "the lane's hello does not decode (${e.message})")
            return
        }
        if (hello.proto != AppWire.PROTO) {
            refuse(io, sock, AppWire.END_PROTO, "lane protocol ${hello.proto} unsupported (this PC speaks ${AppWire.PROTO})")
            return
        }
        if (!host.isOpen) {
            refuse(io, sock, AppWire.END_NOT_OPEN, "the PC's record is not open; see its log")
            return
        }
        try { sock.keepAlive = true } catch (e: Exception) { Log.w(TAG, "keepalive not set on ${sock.inetAddress}: ${e.message}") }
        val id = nextConn.getAndIncrement()
        val shared = Contracts.shared(contracts, hello.contracts)
        val outbox = Outbox("app-$id", outboxCap, write = { f -> io.write(f) }, close = { closeQuietly(sock) })
        val conn = Conn(id, peer, shared, outbox, page = bundle != null)
        val differ = Contracts.compare(contracts, hello.contracts).values.filter { !it.same }
        Log.i(TAG, "connection $id from ${sock.inetAddress}: ${peer.device}, stream ${hello.stream}" +
            (if (differ.isEmpty()) "" else "; different builds for " + differ.joinToString { "${it.window} (PC ${it.mine ?: "-"}, its ${it.theirs ?: "-"})" }))
        outbox.start()
        // the welcome and the registration in one executor step: nothing published after it can be missed
        host.executor.execute("app welcome $id") {
            conns[id] = conn
            viewers.opened(id, clock())
            conn.outbox.offer(AppWire.encode(AppWire.welcome(Welcome(AppWire.PROTO, peer.device, peer.level, id, clock(),
                host.epoch, host.seq, contracts, bundle))))
        }
        val unknown = HashSet<String>()
        try {
            while (true) {
                val f = io.read()
                when (val t = AppWire.type(f)) {
                    "sub" -> sub(conn, f)
                    "unsub" -> {
                        val scope = AppWire.str(f, "scope")
                        host.executor.execute("app unsub $id") {
                            if (conn.scopes.remove(scope) or (conn.described.remove(scope) != null)) used(conn, "leave", scope)
                            viewers.remove(id, scope, "unsubscribed")
                        }
                    }
                    "op" -> op(conn, f)
                    // through the executor: a pong says the PC's core answers, not only its socket
                    "ping" -> {
                        val k = AppWire.num(f, "k")
                        host.executor.execute("app ping $id") {
                            viewers.pinged(id, clock())
                            conn.outbox.offer(AppWire.encode(AppWire.pong(k)))
                        }
                    }
                    "end" -> {
                        Log.i(TAG, "connection $id ended by the client (${(f["reason"] as? JsonPrimitive)?.contentOrNull ?: "no reason"})")
                        return
                    }
                    // protocol growth stays additive: said once per kind per connection, never fatal
                    else -> if (unknown.add(t)) Log.w(TAG, "connection $id sent an unknown message '$t'; ignored")
                }
            }
        } catch (e: EOFException) {
            Log.i(TAG, "connection $id closed by the client")
        } catch (e: Exception) {
            Log.w(TAG, "connection $id ended: ${e::class.simpleName}: ${e.message}" +
                (conn.outbox.endedWhy?.let { " ($it)" } ?: ""))
        } finally {
            conn.gone = true
            outbox.stop()
            closeQuietly(sock)
            host.executor.execute("app end $id") {
                conns.remove(id)?.let { c ->
                    for (s in c.scopes + c.described.keys) used(c, "leave", s)
                    detachLives(c)
                }
                ended(id, "its connection ended")
            }
        }
    }

    /** At the process's stop: every connection is told and closed. */
    override fun close() {
        host.removeListener(listener)
        host.removeTickListener(sweep)
        devices?.table?.removeListener(devicesListener)
        host.executor.call {
            for (c in conns.values) {
                c.outbox.finish(AppWire.encode(AppWire.end(AppWire.END_SHUTTING_DOWN)))
                detachLives(c)
            }
            conns.clear()
        }
        describing.shutdownNow()
        for (p in programs.values) if (p.own) p.pool.shutdown()
    }

    /** S1-2 Q2, for the tests: the viewers' sweep now, as the pacing tick runs it; how many viewers it ended. */
    fun sweepViewers(): Int = host.executor.call { viewers.sweep(clock()) }

    /** Executor only: a connection ended — its viewers end and the end listeners are told. */
    private fun ended(id: Long, why: String) {
        viewers.ended(id, why)
        for (l in endListeners) {
            try {
                l(id)
            } catch (e: Exception) {
                Log.e(TAG, "an end listener failed on connection $id", e)
            }
        }
    }

    // ---- per message ----

    private fun sub(conn: Conn, f: JsonObject) {
        val scope = AppWire.str(f, "scope")
        val need = try {
            AppWire.needOf(f)
        } catch (e: IllegalArgumentException) {
            val no = Answer.Refused("the subscription's need does not decode (${e.message})", Answer.INVALID)
            host.executor.execute("app sub refused ${conn.id}") { conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope, no)))) }
            return
        }
        if (scope.startsWith(AppWire.LIVE)) {
            val l = lives.firstOrNull { it.gate(scope) != null }
            if (l != null) liveSub(conn, scope, l.gate(scope)!!, l, need)
            else host.executor.execute("app sub refused ${conn.id}") {
                conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope,
                    Answer.Refused("$scope names no live source on this PC", Answer.UNKNOWN)))))
            }
            return
        }
        for (p in providers) {
            val gate = p.d.gate(scope) ?: continue
            describedSub(conn, scope, gate, p, need)
            return
        }
        if (scope == DevicesCore.SCOPE && devices != null) {
            host.executor.execute("app sub devices ${conn.id}") {
                // C§10.5: the device table at level full, checked here at apply time
                val no = Levels.refusal(conn.peer.level, Levels.FULL, "the device table")
                if (no != null) conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope, no))))
                else {
                    conn.outbox.offer(AppWire.encode(AppWire.desc(scope, devices.describe())))
                    if (conn.scopes.add(scope)) used(conn, "open", scope)
                }
            }
            return
        }
        if (scope != AppWire.RECORD && scope in conn.shared && scope !in conn.visible) {
            val no = Levels.refusal(conn.peer.level, minLevel(scope), scope)
                ?: Answer.Refused("$scope is not readable on this connection", Answer.LEVEL)
            host.executor.execute("app sub refused ${conn.id}") {
                conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope, no))))
            }
            return
        }
        if (scope != AppWire.RECORD && scope !in conn.shared) {
            val why = if (scope in contracts) Contracts.mismatchLine(scope) else "this PC shares no window '$scope'"
            // on the executor, behind the welcome
            host.executor.execute("app sub refused ${conn.id}") {
                conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope, Answer.Refused(why, Answer.CONTRACT)))))
            }
            return
        }
        host.executor.execute("app sub ${conn.id} $scope") {
            // fix round 2, ruling 1.6: never a record that lacks the log's unreplayed lines (a phone keeps its own
            // copy, as with the PC out of reach; a page shows the refusal and keeps none)
            if (!host.logOpen) {
                conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope, Answer.Refused(AppHost.LOG_CLOSED, Answer.RETRY)))))
                conn.closedSubs += { sub(conn, f) }
                return@execute
            }
            // L1 C2: every subscription takes a snapshot (a cursor it names is not read); the record is a few KB
            val items = HashMap<String, Item>()
            for (w in AppWire.windowsOf(scope, conn.visible)) items.putAll(host.read(w).third.items)
            conn.outbox.offer(AppWire.encode(AppWire.snap(Snap(scope, host.epoch, host.seq, Effect(items)))))
            if (conn.scopes.add(scope)) used(conn, "open", scope)
            // S1-2 Q2: a record scope's viewer (a second `sub` replaces its need; none ends it)
            viewers.put(conn.id, scope, scope.takeIf { it != AppWire.RECORD }, need ?: NO_NEED)
        }
    }

    /** P7: a PC-served scope, checked on the executor at apply time (the contract window, then the level, C§10.5),
     *  then described on its provider's executor. S1-2 Q2: its viewer takes the `sub`'s need, replacing one held. */
    private fun describedSub(conn: Conn, scope: String, g: Gate, p: Provider, need: Viewers.Need?) {
        host.executor.execute("app sub described ${conn.id}") {
            val w = g.window
            val no = when {
                w != null && w !in conn.shared -> Answer.Refused(
                    if (w in contracts) Contracts.mismatchLine(w) else "this PC shares no window '$w'", Answer.CONTRACT)
                w != null && !host.logOpen -> Answer.Refused(AppHost.LOG_CLOSED, Answer.RETRY)   // ruling 1.6: a window's scope
                else -> Levels.refusal(conn.peer.level, g.min, scope)
            }
            if (no != null) {
                conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope, no))))
                if (no.reason == AppHost.LOG_CLOSED) conn.closedSubs += { describedSub(conn, scope, g, p, need) }
            } else {
                if (conn.described[scope]?.p !== p && conn.described.put(scope, Held(p)) == null) used(conn, "open", scope)
                viewers.put(conn.id, scope, w, need ?: NO_NEED)
                describeFor(conn, scope)
            }
        }
    }

    /**
     * S1-3 B11: a live source, checked on the executor at attach (the contract window, then the level against the
     * source's minimum: refused `level`), its viewer registered with the `sub`'s need (none given = focus: a live source
     * runs only for a viewer), then attached — again for a second `sub`, which starts it afresh. A need of none ends the
     * viewer, and its end detaches the source ([liveEnded]).
     */
    private fun liveSub(conn: Conn, scope: String, g: Gate, l: Live, need: Viewers.Need?) {
        host.executor.execute("app sub live ${conn.id}") {
            val w = g.window
            val no = when {
                conns[conn.id] !== conn -> return@execute
                w != null && w !in conn.shared -> Answer.Refused(
                    if (w in contracts) Contracts.mismatchLine(w) else "this PC shares no window '$w'", Answer.CONTRACT)
                else -> Levels.refusal(conn.peer.level, g.min, scope)
            }
            if (no != null) {
                conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope, no))))
                return@execute
            }
            val n = need ?: LIVE_NEED
            if (n.kind == Viewers.Need.NONE) {
                viewers.remove(conn.id, scope, "its need is none")
                return@execute
            }
            if (conn.lives.put(scope, l) == null) used(conn, "open", scope)
            viewers.put(conn.id, scope, w, n)
            try {
                l.attach(scope, conn.id) { body -> conn.outbox.offer(AppWire.encode(AppWire.live(scope, body))) }
            } catch (e: Exception) {
                Log.w(TAG, "the live source $scope did not start for connection ${conn.id}: ${e::class.simpleName}: ${e.message}")
                conn.lives.remove(scope)
                viewers.remove(conn.id, scope, "its source did not start")
                conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope,
                    Answer.Refused("$scope did not start (${e.message ?: e::class.simpleName})", Answer.RETRY)))))
            }
        }
    }

    /** Executor only: [conn]'s viewer of [scope] ended — the source is detached and the leave noted. */
    private fun liveEnded(conn: Long, scope: String) {
        val c = conns[conn] ?: return
        val l = c.lives.remove(scope) ?: return
        used(c, "leave", scope)
        detachQuietly(l, scope, conn)
    }

    /** Executor only: a connection that ends or is ended leaves every live source it holds. */
    private fun detachLives(c: Conn) {
        for ((s, l) in c.lives) {
            used(c, "leave", s)
            detachQuietly(l, s, c.id)
        }
        c.lives.clear()
    }

    private fun detachQuietly(l: Live, scope: String, conn: Long) {
        try {
            l.detach(scope, conn)
        } catch (e: Exception) {
            Log.e(TAG, "the live source $scope could not be detached from connection $conn", e)
        }
    }

    /** Executor only. Queues [scope]'s description for [conn] on its provider's executor; one queued per pair at a
     *  time (a burst of moves is described once, at its newest). A scope that names nothing, or whose describing
     *  fails, is answered as a refusal of the scope, said in the log: never a silent gap. */
    private fun describeFor(conn: Conn, scope: String) {
        val h = conn.described[scope] ?: return
        val key = conn.id to scope
        if (!toDescribe.add(key)) return
        try {
            h.p.on.execute {
                toDescribe.remove(key)
                val frame = try {
                    h.p.d.describe(scope)?.let { d ->
                        h.sent = (d["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                        AppWire.encode(AppWire.desc(scope, d))
                    } ?: AppWire.encode(AppWire.ans(Ans(null, scope, Answer.Refused("$scope names nothing on this PC", Answer.UNKNOWN))))
                } catch (e: Throwable) {
                    // as the web listener (§71): an Error here ends this description, never the describer
                    Log.w(TAG, "describing $scope for connection ${conn.id} failed: ${e::class.simpleName}: ${e.message}")
                    AppWire.encode(AppWire.ans(Ans(null, scope,
                        Answer.Refused("$scope could not be described (${e::class.simpleName}: ${e.message})", Answer.RETRY))))
                }
                conn.outbox.offer(frame)
            }
        } catch (e: RejectedExecutionException) {
            toDescribe.remove(key)   // the process is stopping: close() has ended the describer
        }
    }

    private fun op(conn: Conn, f: JsonObject) {
        val raw = f["op"] as? JsonObject
        val decoded = try {
            // L1 C1: an op without its input time (a page not yet reloaded) takes its arrival
            raw?.let { OpJson.op(it, defaultAt = host.clock()) } ?: throw IllegalArgumentException("no op object")
        } catch (e: IllegalArgumentException) {
            undecodable(conn, raw, e.message ?: "does not decode")
            return
        }
        // the connection's device, never the op's own word for it (C§10.1: a device is its credential)
        val o = decoded.copy(device = conn.peer.device)
        val id = o.id
        when {
            id is OpId.Conn && id.conn != conn.id -> answer(conn, id, Answer.Refused(
                "connection ${id.conn} has ended; an op of a connection is never queued", Answer.ENDED))
            o.window == DevicesCore.SCOPE && devices != null -> deviceOp(conn, o)
            o.window !in conn.shared -> answer(conn, id, Answer.Refused(
                if (o.window in contracts) Contracts.mismatchLine(o.window) else "this PC shares no window '${o.window}'",
                Answer.CONTRACT))
            // S1-2 Q2: an op into an outside program — never queued, checked here at apply time, run on its pool
            id is OpId.Conn && isProgram(o.window, o.action) -> programOp(conn, o, id)
            // the reply runs on the executor after the op's line, before its change: the record's place now is the
            // op's own line (or later, for an op answered from the record), which the client's state must reach.
            // The gate (P6, C§10.5) is read there at apply time: the connection's device ended, then its level
            else -> host.act(o, gate = { gate(conn, o) }) { a -> answer(conn, id, a, Cursor(host.epoch, host.seq)) }
        }
    }

    /** On the executor at apply time (C§10.5, C§10.8): a revoked or replaced device's unsent op is refused; an op its
     *  level does not reach is refused with the reason. */
    private fun gate(conn: Conn, o: Op): Answer.Refused? {
        conn.endedBy?.let { return Answer.Refused("this device was $it; approve it again", Answer.ENDED) }
        return Levels.refusal(conn.peer.level, levels.need(o.window, o.action) ?: Levels.FULL, "${o.window} ${o.action}")
    }

    /**
     * S1-2 Q2 (the spec's §2.4, §5 V3): a program op. On the executor at apply time: a connection that has ended →
     * `ended` (it never runs); then the device and the level ([gate]); then the window's handler, on the window's pool.
     * Its answer comes back through the executor to this connection only; a connection that ended during the call
     * gets none (the page says "not known whether it ran"). One `program` journal note per op.
     */
    private fun programOp(conn: Conn, o: Op, id: OpId.Conn) {
        host.executor.execute("app program ${conn.id}") {
            val prog = programs[o.window]
            val no = when {
                conns[conn.id] !== conn -> Answer.Refused(
                    "this connection ended before the op was taken; an op of a connection is never queued", Answer.ENDED)
                else -> gate(conn, o)
            } ?: if (prog == null) Answer.Refused("this PC serves no ${o.window} program on this lane", Answer.UNSUPPORTED) else null
            if (no != null) {
                note("program", o, no)
                answer(conn, id, no)
                return@execute
            }
            conn.flights[id.k] = Flight(o.window, o.action, clock())
            try {
                prog!!.pool.execute {
                    val r = if (conn.gone) ProgramResult.Refused("its connection ended before its turn; not run", Answer.ENDED)
                    else try {
                        prog.handler.act(o, conn.peer)
                    } catch (e: Throwable) {
                        // the program's own words; an Error here ends this call, never the pool
                        Log.w(TAG, "${o.window} ${o.action} for ${conn.peer.device} threw: ${e::class.simpleName}: ${e.message}")
                        ProgramResult.Refused(e.message ?: e::class.simpleName ?: "the program call failed")
                    }
                    note("program", o, r.answer)
                    host.executor.execute("app program answer ${conn.id}") {
                        conn.flights.remove(id.k)
                        if (conns[conn.id] === conn) answer(conn, id, r.answer, result = (r as? ProgramResult.Sent)?.result)
                        else Log.i(TAG, "${o.window} ${o.action} of connection ${conn.id} answered after its connection ended; " +
                            "the answer is dropped (the page says it is not known whether it ran)")
                    }
                }
            } catch (e: RejectedExecutionException) {
                conn.flights.remove(id.k)
                answer(conn, id, Answer.Refused("the PC is stopping; ${o.window} ${o.action} was not run", Answer.RETRY))
            }
        }
    }

    /** One journal note of [kind] per program op or device action: who (the connection's device), what, and its
     *  answer; the note's line carries when. */
    private fun note(kind: String, o: Op, a: Answer) = note(kind, "${o.device} ${o.window} ${o.action} ${o.targets}", a)

    /** One line in the PC's journal: what was done, by whom ([what]), and its answer; the journal stamps when. */
    internal fun note(kind: String, what: String, a: Answer) {
        val words = when (a) {
            is Answer.Refused -> "refused (${a.code}): ${a.reason}"
            else -> a.toString().lowercase()
        }
        try {
            host.noteAs(kind, "$what · $words")
        } catch (e: Exception) {
            Log.e(TAG, "the $kind note could not be written", e)
        }
    }

    /**
     * L1 R-e, the usage record: a web page took or left a scope (a window, a book, a part, a folder): one `usage` note
     * in the PC's journal, `<device> web <window> open|leave <scope>`, beside the program and device notes (the
     * note's `t` is when). What a page does is an op and in the op log already; the glasses' window is on every flush
     * line of the phone's own journal (`win`), and their notices' read and dismiss are ops. Executor only.
     */
    private fun used(conn: Conn, action: String, scope: String) {
        val window = scope.removePrefix("desc/").removePrefix(AppWire.LIVE).substringBefore('/')
        if (conn.page) host.noteAs("usage", "${conn.peer.device} web $window $action $scope")
    }

    /** A device-table action (C§10.3, C§10.8): level full, never queued, answered on the executor. */
    private fun deviceOp(conn: Conn, o: Op) {
        val table = devices ?: return
        host.executor.execute("app devices ${conn.id}") {
            val a = when {
                o.id !is OpId.Conn -> Answer.Refused("a device-table action is never queued; send it as (conn, k)", Answer.INVALID)
                conn.endedBy != null -> Answer.Refused("this device was ${conn.endedBy}", Answer.ENDED)
                else -> Levels.refusal(conn.peer.level, Levels.FULL, "the device table") ?: table.act(o, conn.peer.device)
            }
            answer(conn, o.id, a)
            note("devices", o, a)                     // L1: approve, revoke, rename — who and when, as program ops
        }
    }

    /** Ends every connection of [device] with [reason] (`revoked`, `replaced`; C§10.8): each is told and closed, and an
     *  op it sent that has not been applied yet is refused by the gate. Executor only. */
    private fun endNow(device: String, reason: String) {
        val mine = conns.values.filter { it.peer.device == device }
        for (c in mine) {
            c.endedBy = reason
            c.gone = true
            c.outbox.finish(AppWire.encode(AppWire.end(reason)))
            conns.remove(c.id)
            detachLives(c)
            ended(c.id, "its device was $reason")
        }
        if (mine.isNotEmpty()) Log.i(TAG, "${mine.size} connection(s) of $device ended: $reason")
    }

    /** The device table moved: every connection subscribed to it gets the new description; a revoked or replaced
     *  device's connections end. From the device table's caller's thread: queued on the executor. */
    private fun onDevices(e: Devices.Event) {
        when (e) {
            is Devices.Event.Ended -> host.executor.execute("app device ended") { endNow(e.device, e.reason) }
            Devices.Event.Changed -> host.executor.execute("app devices changed") {
                val table = devices ?: return@execute
                val frame = AppWire.encode(AppWire.desc(DevicesCore.SCOPE, table.describe()))
                for (c in conns.values) if (DevicesCore.SCOPE in c.scopes) c.outbox.offer(frame)
            }
        }
    }

    /** An op that does not decode: refused for good, in words, to the id it names (L1 C1: no stream waits on it). */
    private fun undecodable(conn: Conn, raw: JsonObject?, why: String) {
        Log.w(TAG, "connection ${conn.id}: an op that does not decode ($why)")
        answer(conn, raw?.let { AppWire.idOf(it) }, Answer.Refused("the op does not decode ($why)", Answer.INVALID))
    }

    /** On the executor (an op's reply is there already, before its change is published); from the reading thread
     *  it is queued there, so no answer overtakes the welcome. */
    private fun answer(conn: Conn, id: OpId?, a: Answer, at: Cursor? = null, result: kotlinx.serialization.json.JsonElement? = null) {
        val frame = AppWire.encode(AppWire.ans(Ans(id, null, a, at, result)))
        if (host.executor.isCurrent()) conn.outbox.offer(frame)
        else host.executor.execute("app answer ${conn.id}") { conn.outbox.offer(frame) }
    }

    /** On the executor, in `seq` order: each connection gets the change's entries in each scope it holds. */
    private fun publish(c: Change) {
        for (conn in conns.values) {
            for (scope in conn.scopes) {
                if (scope == DevicesCore.SCOPE) continue
                chgFrame(c, scope, AppWire.windowsOf(scope, conn.visible))?.let { conn.outbox.offer(it) }
            }
            // P7: a PC-served scope this change moves is described again (coalesced, on its provider's executor)
            for ((scope, h) in conn.described) if (h.p.d.follows(scope, c)) describeFor(conn, scope)
        }
    }

    /** [c] limited to [windows], or null when none of its entries is theirs. */
    private fun chgFrame(c: Change, scope: String, windows: Set<String>): ByteArray? {
        val mine = AppWire.within(c.effect, windows)
        if (mine.isEmpty()) return null
        return AppWire.encode(AppWire.chg(Chg(scope, c.epoch, c.seq, mine)))
    }

    private fun refuse(io: LaneIo, sock: Socket, reason: String, detail: String) {
        Log.w(TAG, "lane refused for ${sock.inetAddress}: $detail")
        try {
            io.write(AppWire.encode(AppWire.end(reason, detail)))
        } catch (e: Exception) {
            Log.w(TAG, "the refusal could not be sent to ${sock.inetAddress}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "app-net"
        /** The frames a connection's outbox holds before it ends the connection as slow. */
        const val OUTBOX_CAP = 4_096
        private val NO_NEED = Viewers.Need(Viewers.Need.NONE)
        /** S1-3 B11: a live `sub` that names no need is a viewer in front. */
        private val LIVE_NEED = Viewers.Need(Viewers.Need.FOCUS)

        /** A window served with a card and no scopes of its own. */
        private val NO_SCOPES = object : Described {
            override fun gate(scope: String): Gate? = null
            override fun describe(scope: String): JsonObject? = null
            override fun follows(scope: String, c: Change?) = false
            override fun addListener(l: () -> Unit) {}
        }

        private fun closeQuietly(sock: Socket) {
            try { sock.close() } catch (e: Exception) { Log.w(TAG, "closing ${sock.inetAddress}: ${e.message}") }
        }
    }
}

/**
 * One connection's outbound frames: a bounded queue and ONE sender thread (the spec's §3 row: "the executor enqueues,
 * one sender per connection writes"). [offer] never blocks. When the queue already holds [cap] frames the reader is
 * not taking them: the connection ends — `end{slow}` goes out when the sender is free to write it, and the socket is
 * closed at once when the sender is stuck in a write — and the client takes a snapshot again. A stuck reader thus
 * holds one thread and one socket, never the executor or another connection.
 */
class Outbox(
    private val name: String,
    private val cap: Int,
    private val write: (ByteArray) -> Unit,
    private val close: () -> Unit,
) {
    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    /** No more frames are taken. */
    private var ended = false
    /** Close once the queue has been written (after an `end`). */
    private var closeWhenSent = false
    /** The connection ended: the sender stops. */
    private var stopped = false

    /** Why the outbox ended the connection, or null. */
    @Volatile var endedWhy: String? = null
        private set

    fun start() {
        Thread({ run() }, "$name-send").apply { isDaemon = true }.start()
    }

    /** Queues [frame]; false when the connection has ended (by this offer, if it overflowed the queue). */
    fun offer(frame: ByteArray): Boolean = synchronized(lock) {
        if (ended) return false
        if (queue.size >= cap) {
            ended = true
            queue.clear()
            endedWhy = "its reader took none of the last $cap frames"
            Log.w(TAG, "$name ended: $endedWhy; it takes a snapshot when it reconnects")
            if (writing) {
                close()
            } else {
                queue.addLast(AppWire.encode(AppWire.end(AppWire.END_SLOW)))
                closeWhenSent = true
            }
            lock.notifyAll()
            return false
        }
        queue.addLast(frame)
        lock.notifyAll()
        true
    }

    /** Sends [frame] after what is queued, then closes (the PC's own stop). */
    fun finish(frame: ByteArray) = synchronized(lock) {
        if (ended) return@synchronized
        ended = true
        queue.addLast(frame)
        closeWhenSent = true
        lock.notifyAll()
    }

    /** The connection ended: nothing more is written. */
    fun stop() = synchronized(lock) {
        stopped = true
        ended = true
        queue.clear()
        lock.notifyAll()
    }

    private fun run() {
        while (true) {
            val f = synchronized(lock) {
                while (queue.isEmpty() && !stopped) lock.wait()
                if (stopped) return
                writing = true
                queue.removeFirst()
            }
            try {
                write(f)
            } catch (e: Exception) {
                // after stop() the reading side has closed the socket under this write: nothing to say
                if (!synchronized(lock) { stopped }) Log.w(TAG, "$name: a write failed (${e::class.simpleName}: ${e.message}); the connection ends")
                close()
                return
            } finally {
                synchronized(lock) { writing = false }
            }
            val last = synchronized(lock) { closeWhenSent && queue.isEmpty() }
            if (last) {
                close()
                return
            }
        }
    }

    private companion object {
        const val TAG = "app-net"
    }
}
