package wm.damage.core.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import wm.damage.core.util.Log
import wm.damage.core.web.Devices

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P2 (`CONTRACT.md` §9.2, §9.3, §13 (b) "cursors"; the spec's §2 P2 and §3 "per-connection
 * outbox"): the PC's side of the `app` lane. One [AppNet] per process over the record's [AppHost]; the content host
 * hands it every connection that asks for the lane. Per connection: the content host's thread reads, ONE sender thread
 * writes from a bounded [Outbox], and everything that touches the record or the connection table runs on the core
 * executor — a `sub`'s snapshot or resume and the changes published after it are queued in one order, so a client
 * never misses a change between its snapshot and the live stream, and never gets one twice.
 *
 * Package P6 (`CONTRACT.md` §10.4, §10.5, §10.8): the same lane on the web interface listener (a WebSocket, [LaneIo]),
 * every op, subscription and the device table checked against the connection's level at apply time ([levels],
 * [minLevel]), the device table's description and actions ([DevicesCore], level full, never queued), and a revoked or
 * replaced device's connections ended with the reason ([endDevice]).
 *
 * Package P7 (C§9.2 `snap` "the description or level"; the spec's §2 P7 "Kotlin side"): PC-served scopes outside the
 * record's order — a window's description, one of its levels, Main's cards, the diagnosis ([Described], [attach]) —
 * each answered as a `desc` frame, described on ONE describer thread (a book's parse never holds the executor), checked
 * against the connection's level and contracts on the executor, and described again, coalesced, when a record change
 * or the provider's own source moves them.
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
     * on the describer thread and may block (a book's parse).
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
    }

    /** A PC-served scope's needs: [window] the contract window it belongs to (null: a page of the PC's own — Main's
     *  cards, the diagnosis), [min] the level it needs. */
    data class Gate(val window: String?, val min: String)

    /** Connection ids grow across restarts (seeded from the clock), so an op naming a connection of an earlier
     *  process never matches a new one. */
    private val nextConn = AtomicLong(clock() * 1_000)

    /** P7: the provider of PC-served scopes ([attach]); null = none served. */
    @Volatile private var described: Described? = null

    /** P7: the one describer thread, and the (connection, scope) pairs queued on it (one each at a time). */
    private val describing = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-describe").apply { isDaemon = true }
    }
    private val toDescribe = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, String>>()

    private inner class Conn(val id: Long, val peer: Peer, val shared: Set<String>, val outbox: Outbox) {
        /** Executor only. */
        val scopes = LinkedHashSet<String>()
        /** The PC-served scopes this connection holds (P7). Executor only. */
        val described = LinkedHashSet<String>()
        /** The windows this connection may read: shared, and within its level (P6). */
        val visible: Set<String> = shared.filterTo(LinkedHashSet()) { Levels.atLeast(peer.level, minLevel(it)) }
        /** Why the PC ended this connection (its device revoked or replaced); executor only. */
        var endedBy: String? = null
    }

    /** The live connections. Executor only. */
    private val conns = LinkedHashMap<Long, Conn>()

    private val listener: (Change) -> Unit = { c -> publish(c) }

    private val devicesListener: (DevicesCore.Event) -> Unit = { e -> onDevices(e) }

    init {
        host.addListener(listener)
        devices?.addListener(devicesListener)
    }

    /** How many connections are live (for the start line and the tests); read on the executor. */
    fun connections(): Int = host.executor.call { conns.size }

    /** The live connections, one line each (the diagnosis page, P7); read on the executor. Never call it there. */
    fun connectionLines(): List<String> = host.executor.call {
        conns.values.map { c -> "${c.peer.device} (${c.peer.level}) · connection ${c.id} · ${c.scopes.size + c.described.size} scope(s)" }
    }

    /**
     * P7: serves [d]'s scopes on this lane from now on (both PC host builders, from `startWeb`). Its source's moves
     * describe again, coalesced, every scope that follows them.
     */
    fun attach(d: Described) {
        described = d
        d.addListener {
            host.executor.execute("app described moved") {
                for (c in conns.values) for (s in c.described) if (d.follows(s, null)) describeFor(c, s)
            }
        }
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
        val conn = Conn(id, peer, shared, outbox)
        val differ = Contracts.compare(contracts, hello.contracts).values.filter { !it.same }
        Log.i(TAG, "connection $id from ${sock.inetAddress}: ${peer.device}, stream ${hello.stream}" +
            (if (differ.isEmpty()) "" else "; different builds for " + differ.joinToString { "${it.window} (PC ${it.mine ?: "-"}, its ${it.theirs ?: "-"})" }))
        outbox.start()
        // the welcome and the registration in one executor step: nothing published after it can be missed
        host.executor.execute("app welcome $id") {
            // fix round 1, ruling 2.3 (9), and fix round 2, ruling 2.4: a revocation or a re-approval whose end ran
            // between this connection's upgrade and this step found no connection to end; the device table answers again
            // now (the row live, at the approval the upgrade read), so it never runs at the old level
            val ended = devices?.takeIf { Devices.isTableId(peer.device) }?.table?.endedSince(peer.device, peer.approval)
            if (ended != null) {
                conn.endedBy = ended
                conn.outbox.finish(AppWire.encode(AppWire.end(ended)))
                Log.i(TAG, "connection $id of ${peer.device} ended at its welcome: the device was " +
                    (if (ended == AppWire.END_REVOKED) "removed" else "approved again") + " meanwhile")
                return@execute
            }
            conns[id] = conn
            conn.outbox.offer(AppWire.encode(AppWire.welcome(Welcome(AppWire.PROTO, peer.device, peer.level, id, clock(),
                host.epoch, host.seq, host.highWaters(peer.device), contracts, bundle))))
        }
        val unknown = HashSet<String>()
        try {
            while (true) {
                val f = io.read()
                when (val t = AppWire.type(f)) {
                    "sub" -> sub(conn, f)
                    "unsub" -> {
                        val scope = AppWire.str(f, "scope")
                        host.executor.execute("app unsub $id") { conn.scopes.remove(scope); conn.described.remove(scope) }
                    }
                    "op" -> op(conn, f)
                    // through the executor: a pong says the PC's core answers, not only its socket
                    "ping" -> {
                        val k = AppWire.num(f, "k")
                        host.executor.execute("app ping $id") { conn.outbox.offer(AppWire.encode(AppWire.pong(k))) }
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
            outbox.stop()
            closeQuietly(sock)
            host.executor.execute("app end $id") { conns.remove(id) }
        }
    }

    /** At the process's stop: every connection is told and closed. */
    override fun close() {
        host.removeListener(listener)
        devices?.removeListener(devicesListener)
        host.executor.call {
            for (c in conns.values) c.outbox.finish(AppWire.encode(AppWire.end(AppWire.END_SHUTTING_DOWN)))
            conns.clear()
        }
        describing.shutdownNow()
    }

    // ---- per message ----

    private fun sub(conn: Conn, f: JsonObject) {
        val scope = AppWire.str(f, "scope")
        val cursor = AppWire.cursorOf(f)
        val gate = described?.gate(scope)
        if (gate != null) {
            describedSub(conn, scope, gate)
            return
        }
        if (scope == DevicesCore.SCOPE && devices != null) {
            host.executor.execute("app sub devices ${conn.id}") {
                // C§10.5: the device table at level full, checked here at apply time
                val no = Levels.refusal(conn.peer.level, Levels.FULL, "the device table")
                if (no != null) conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope, no))))
                else {
                    conn.outbox.offer(AppWire.encode(AppWire.desc(scope, devices.describe())))
                    conn.scopes += scope
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
            // fix round 2, ruling 1.6: never a record that lacks the log's unreplayed lines; the device keeps its own copy,
            // as with the PC out of reach
            if (!host.logOpen) {
                conn.outbox.offer(AppWire.encode(AppWire.ans(Ans(null, scope, Answer.Refused(AppHost.LOG_CLOSED, Answer.RETRY)))))
                return@execute
            }
            val windows = AppWire.windowsOf(scope, conn.visible)
            // C§9.3: same epoch and a seq inside the ring → the changes since; otherwise a snapshot, never a gap
            val since = cursor?.takeIf { it.epoch == host.epoch }?.let { host.ring.since(it.seq) }
            if (since != null) {
                for (c in since) chgFrame(c, scope, windows)?.let { conn.outbox.offer(it) }
            } else {
                val items = HashMap<String, Item>()
                val colls = HashMap<String, Coll>()
                for (w in windows) {
                    val e = host.read(w).third
                    items.putAll(e.items)
                    colls.putAll(e.colls)
                }
                conn.outbox.offer(AppWire.encode(AppWire.snap(Snap(scope, host.epoch, host.seq, Effect(items, colls)))))
            }
            conn.scopes += scope
        }
    }

    /** P7: a PC-served scope, checked on the executor at apply time (the contract window, then the level, C§10.5),
     *  then described on the describer thread. */
    private fun describedSub(conn: Conn, scope: String, g: Gate) {
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
            } else {
                conn.described += scope
                describeFor(conn, scope)
            }
        }
    }

    /** Queues [scope]'s description for [conn] on the describer thread; one queued per pair at a time (a burst of
     *  moves is described once, at its newest). Any thread. A scope that names nothing, or whose describing fails, is
     *  answered as a refusal of the scope, said in the log: never a silent gap. */
    private fun describeFor(conn: Conn, scope: String) {
        val d = described ?: return
        val key = conn.id to scope
        if (!toDescribe.add(key)) return
        try {
            describing.execute {
                toDescribe.remove(key)
                val frame = try {
                    d.describe(scope)?.let { AppWire.encode(AppWire.desc(scope, it)) }
                        ?: AppWire.encode(AppWire.ans(Ans(null, scope, Answer.Refused("$scope names nothing on this PC", Answer.UNKNOWN))))
                } catch (e: Throwable) {
                    // as the web listener (§71): an Error here ends this description, never the describer
                    Log.w(TAG, "describing $scope for connection ${conn.id} failed: ${e::class.simpleName}: ${e.message}")
                    AppWire.encode(AppWire.ans(Ans(null, scope,
                        Answer.Refused("$scope could not be described (${e::class.simpleName}: ${e.message})", Answer.RETRY))))
                }
                conn.outbox.offer(frame)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            toDescribe.remove(key)   // the process is stopping: close() has ended the describer
        }
    }

    private fun op(conn: Conn, f: JsonObject) {
        val raw = f["op"] as? JsonObject
        val decoded = try {
            raw?.let { OpJson.op(it) } ?: throw IllegalArgumentException("no op object")
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
        }
    }

    /**
     * Ends every connection of [device] with [reason] (`revoked`, `replaced`; C§10.8): each is told and closed, and an
     * op it sent that has not been applied yet is refused by the gate. Runs on the executor; returns how many ended.
     */
    fun endDevice(device: String, reason: String): Int = host.executor.call { endNow(device, reason) }

    /** Executor only. */
    private fun endNow(device: String, reason: String): Int {
        val mine = conns.values.filter { it.peer.device == device }
        for (c in mine) {
            c.endedBy = reason
            c.outbox.finish(AppWire.encode(AppWire.end(reason)))
            conns.remove(c.id)
        }
        if (mine.isNotEmpty()) Log.i(TAG, "${mine.size} connection(s) of $device ended: $reason")
        return mine.size
    }

    /** The device table moved: every connection subscribed to it gets the new description; a revoked or replaced
     *  device's connections end. From the device table's caller's thread: queued on the executor. */
    private fun onDevices(e: DevicesCore.Event) {
        when (e) {
            is DevicesCore.Event.Ended -> host.executor.execute("app device ended") { endNow(e.device, e.reason) }
            DevicesCore.Event.Changed -> host.executor.execute("app devices changed") {
                val table = devices ?: return@execute
                val frame = AppWire.encode(AppWire.desc(DevicesCore.SCOPE, table.describe()))
                for (c in conns.values) if (DevicesCore.SCOPE in c.scopes) c.outbox.offer(frame)
            }
        }
    }

    /** An op that does not decode. One that still names its stream and number takes that number as a refusal for
     *  good (a placeholder that names no window: the record answers it "unknown" and logs it), so the client drops it
     *  and its stream goes on instead of meeting "expected n" for ever; the answer says why. */
    private fun undecodable(conn: Conn, raw: JsonObject?, why: String) {
        val id = raw?.let { AppWire.idOf(it) }
        Log.w(TAG, "connection ${conn.id}: an op that does not decode ($why)")
        val reason = "the op does not decode ($why)"
        if (id is OpId.Queued) {
            host.act(Op(conn.peer.device, id, UNDECODED, UNDECODED, emptyList())) { a ->
                answer(conn, id, if (a is Answer.Refused) Answer.Refused(reason, a.code) else a)
            }
        } else {
            answer(conn, id, Answer.Refused(reason, Answer.INVALID))
        }
    }

    /** On the executor (an op's reply is there already, before its change is published); from the reading thread
     *  it is queued there, so no answer overtakes the welcome. */
    private fun answer(conn: Conn, id: OpId?, a: Answer, at: Cursor? = null) {
        val frame = AppWire.encode(AppWire.ans(Ans(id, null, a, at)))
        if (host.executor.isCurrent()) conn.outbox.offer(frame)
        else host.executor.execute("app answer ${conn.id}") { conn.outbox.offer(frame) }
    }

    /** On the executor, in `seq` order: each connection gets the change's entries in each scope it holds. */
    private fun publish(c: Change) {
        val d = described
        for (conn in conns.values) {
            for (scope in conn.scopes) {
                if (scope == DevicesCore.SCOPE) continue
                chgFrame(c, scope, AppWire.windowsOf(scope, conn.visible))?.let { conn.outbox.offer(it) }
            }
            // P7: a PC-served scope this change moves is described again (coalesced, on the describer thread)
            if (d != null) for (scope in conn.described) if (d.follows(scope, c)) describeFor(conn, scope)
        }
    }

    /** [c] limited to [windows], or null when none of its entries is theirs (a client's cursor then lags; its resume
     *  is a snapshot once the ring has moved past it). */
    private fun chgFrame(c: Change, scope: String, windows: Set<String>): ByteArray? {
        val mine = AppWire.within(c.effect, windows)
        if (mine.isEmpty()) return null
        return AppWire.encode(AppWire.chg(Chg(scope, c.epoch, c.seq, c.origin, mine)))
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
        /** Above the ring (1,000), so a whole resume fits behind a snapshot already queued. */
        const val OUTBOX_CAP = 4_096
        /** The window and action of an op that did not decode: never a window's name (`Record.validWindow`). */
        const val UNDECODED = "?"

        private fun closeQuietly(sock: Socket) {
            try { sock.close() } catch (e: Exception) { Log.w(TAG, "closing ${sock.inetAddress}: ${e.message}") }
        }
    }
}

/**
 * One connection's outbound frames: a bounded queue and ONE sender thread (the spec's §3 row: "the executor enqueues,
 * one sender per connection writes"). [offer] never blocks. When the queue already holds [cap] frames the reader is
 * not taking them: the connection ends — `end{slow}` goes out when the sender is free to write it, and the socket is
 * closed at once when the sender is stuck in a write — and the client resumes from its cursor. A stuck reader thus
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
            Log.w(TAG, "$name ended: $endedWhy; it resumes from its cursor")
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
