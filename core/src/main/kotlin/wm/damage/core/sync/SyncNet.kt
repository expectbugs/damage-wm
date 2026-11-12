package wm.damage.core.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import wm.damage.core.shell.Persistence
import wm.damage.core.util.Log

/**
 * The state-sync channel over the CONTENT port (HANDOFF.md §19.2): a
 * connection that sends `{"t":"sync",…}` after the hello becomes the
 * persistent sync channel. Both sides hold a stamped [Persistence] store;
 * the handshake exchanges stamp maps and each side's clock, and a record
 * flows only when its stamp is newer than the peer's by more than
 * [Persistence.STAMP_TOLERANCE_MS] (HANDOFF §75.1: two copies whose stamps
 * sit inside the tolerance are the same record, and the handshake leaves
 * them). Afterwards every LOCAL change pushes live, and a pushed record
 * with a different value is decided on arrival by strict stamp order
 * ([Persistence.tryApplyRemote] holds the table). The client re-handshakes
 * every 5 minutes on a live connection: that carries a lost push again when
 * the two copies' stamps are more than the tolerance apart. Inside it the
 * handshake cannot see the difference (it carries stamps, not values) and
 * the two copies stay as they are until that key's next edit.
 *
 * Framing matches Content.kt (4-byte length + JSON). Synced keys are
 * [Persistence.syncable] only. Skew: each handshake frame carries the
 * sender's clock, stamped where the frame is written; the receiver takes its
 * own time when the frame's length prefix arrives and shifts incoming stamps
 * by the difference before comparing. The estimate errs by the one-way
 * delay, which is what the tolerance is sized against. A stamp of 0 (a
 * baseline, an absent key) is never shifted.
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class SRec(val k: String, val v: JsonObject, val t: Long)

@Serializable
private data class SWire(
    val t: String,                                  // "sync" | "syncok" | "syncrec"
    val stamps: Map<String, Long> = emptyMap(),
    val clock: Long = 0,
    val records: List<SRec> = emptyList(),
    val want: List<String> = emptyList(),
)

/** The frames that carry the sender's clock (the handshake and its answer). */
private fun carriesClock(w: SWire) = w.t == "sync" || w.t == "syncok"

/**
 * Writes one frame. A handshake frame gets its `clock` HERE, under the write
 * lock, as the last thing before its bytes leave (0.64 P3, review finding 9).
 * Stamped where it was built, a re-handshake that waited in the outbox behind
 * a 191 KB push carried a clock older by that wait, and the peer's skew
 * estimate was off by as much. Whatever `clock` the caller set is replaced.
 */
private fun DataOutputStream.sendWire(w: SWire, now: () -> Long) {
    fun frame(x: SWire) {
        val b = json.encodeToString(SWire.serializer(), x).toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
        flush()
    }
    if (carriesClock(w)) {
        synchronized(this) { frame(w.copy(clock = now())) }
    } else {
        // a push is encoded before the lock is taken, as before: only its bytes wait
        val b = json.encodeToString(SWire.serializer(), w).toByteArray(Charsets.UTF_8)
        synchronized(this) {
            writeInt(b.size)
            write(b)
            flush()
        }
    }
}

/** A frame and THIS side's time when its length prefix arrived. */
private class Got(val w: SWire, val at: Long)

/**
 * Reads one frame. The receive time is taken right after the length prefix
 * (finding 9): taken after the body was read and decoded, a 216 KB `syncok`
 * put its own transfer time into the skew estimate.
 */
private fun DataInputStream.readWire(now: () -> Long, maxLen: Int = 16 shl 20): Got {
    val n = readInt()
    val at = now()
    require(n in 1..maxLen) { "sync frame length $n out of range" }
    val b = ByteArray(n)
    readFully(b)
    return Got(json.decodeFromString(SWire.serializer(), b.toString(Charsets.UTF_8)), at)
}

/** A peer's stamp on this side's clock. A stamp of 0 is "never edited" (a baseline, an
 *  absent key) and stays 0: shifted, it read as a real stamp of ±skew and a baseline could
 *  be asked for or sent on the strength of the clocks' difference alone (review NIT 13). */
private fun shifted(t: Long, skew: Long): Long = if (t <= 0L) 0L else t - skew

/**
 * One side's sync surface: the store, plus the applier that routes an incoming
 * record — through the live shell when one runs (`Shell.postSync`: a record
 * the store already holds ends there; otherwise freshen, apply, live-apply)
 * and straight into the store otherwise. The stamp handed to the applier is
 * already skew-normalized to THIS side's clock. Every incoming record reaches
 * the applier: whether the store already holds it is decided where it is
 * applied (the shell loop, or [Persistence.tryApplyRemote] itself), never on
 * the reader's thread — records for one key are queued in order, and the
 * reader would compare against a store the queued ones have not reached yet.
 */
class SyncPeer(
    val store: Persistence,
    val applier: (key: String, value: JsonObject, stamp: Long) -> Unit = { k, v, t ->
        store.tryApplyRemote(k, v, t)
    },
)

object SyncNet {

    /** The end of both "outbox full" lines: what becomes of a dropped push (0.64 P3). */
    internal val DROPPED_PUSH_NOTE = "the re-handshake carries it again unless the two copies' stamps sit inside " +
        "${Persistence.STAMP_TOLERANCE_MS} ms; then it stays until that key's next edit"

    /** Sender-thread stop sentinel — LinkedBlockingQueue refuses nulls. */
    private val END = SWire(t = "__end")

    /** R3#5: the server outbox's bound (the client's cap is its own const). */
    private const val SERVE_OUTBOX_CAP = 256

    private val STAMP_FMT = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())

    /** §72 (2026-11-08): the keys a handshake moves, each with both sides' stamps on THIS
     *  side's clock. The 2026-11-07 reconnect said "72 newer there, 5 wanted" after 11-04's
     *  "0 newer there, 77 wanted", and nothing said which keys or why: the next one names
     *  them. Every key is listed (never cut); "none" is a side with no stamp for it. */
    internal fun describeKeys(keys: List<String>, first: Map<String, Long>, firstName: String,
                              second: Map<String, Long>, secondName: String): String {
        fun at(t: Long?) = if (t == null || t <= 0L) "none" else STAMP_FMT.format(java.time.Instant.ofEpochMilli(t))
        if (keys.isEmpty()) return "none"
        // an empty [secondName]: that side's stamps are not known here (the client's "wanted"
        // list carries keys only), so no column is printed rather than a false "none"
        return keys.joinToString(", ") { k ->
            if (secondName.isEmpty()) "$k ($firstName ${at(first[k])})"
            else "$k ($firstName ${at(first[k])} · $secondName ${at(second[k])})"
        }
    }

    /** What one handshake moves, and the stamps it was decided on. */
    private class Diff(val records: List<SRec>, val want: List<String>, val mine: Map<String, Long>)

    /** The records of [store] newer than [theirs] (already on this side's clock) by more
     *  than the tolerance, plus the keys THEY hold newer than ours by more than it
     *  ([Persistence.newer]; HANDOFF §75.1). Two copies inside the tolerance are in neither
     *  list. Decided on ONE locked read of the store: the value and the stamp that go out
     *  are the ones that were compared. */
    private fun diff(store: Persistence, theirs: Map<String, Long>): Diff {
        val mine = store.snapshot { Persistence.syncable(it) }
        val newerHere = ArrayList<SRec>()
        for ((k, r) in mine) {
            if (Persistence.newer(r.second, theirs[k] ?: 0L)) newerHere.add(SRec(k, r.first, r.second))
        }
        val newerThere = theirs.entries
            .filter { Persistence.syncable(it.key) && Persistence.newer(it.value, mine[it.key]?.second ?: 0L) }
            .map { it.key }
        return Diff(newerHere, newerThere, mine.mapValues { it.value.second })
    }

    /**
     * Serve one sync client on an already-helloed content connection whose
     * first post-hello line was [helloLine] (the client's `sync` handshake).
     * Blocks until the client leaves; the caller owns the socket.
     */
    fun serve(sock: Socket, inp: DataInputStream, out: DataOutputStream, peer: SyncPeer, helloLine: String,
              now: () -> Long = System::currentTimeMillis) {
        // the content host read the hello line whole before handing the connection over (a
        // `sync` frame is the stamp map, a few KB), so this is the earliest this side's time
        // can be taken for it; every later frame's is taken at its length prefix (readWire)
        val helloAt = now()
        var skew = 0L      // clientClock - ourNow at the last handshake
        fun normalize(t: Long) = shifted(t, skew)

        fun handshake(w: SWire, at: Long) {
            skew = w.clock - at
            val theirs = w.stamps.mapValues { normalize(it.value) }
            val d = diff(peer.store, theirs)
            if (d.records.isNotEmpty() || d.want.isNotEmpty()) {
                Log.i("sync-host", "handshake with ${sock.inetAddress}: newer here: " +
                    describeKeys(d.records.map { it.k }, d.mine, "here", theirs, "peer") +
                    "; newer on the peer: " + describeKeys(d.want, theirs, "peer", d.mine, "here"))
            }
            // `clock` is stamped by sendWire, under the write lock: this frame can wait there
            // behind a push the sender thread is writing
            out.sendWire(SWire(t = "syncok", records = d.records, want = d.want), now)
        }

        // bounded like the client's (R3#5 — R2#20e applied to one side only):
        // a half-open peer parks the sender in the TCP buffer while local puts
        // keep producing; overflow drops the OLDEST loudly. The client's periodic
        // re-handshake carries a dropped push again only when the two copies'
        // stamps are more than the tolerance apart (0.64 P3): inside it the
        // difference stays until that key's next edit, and the line says so
        val outbox = LinkedBlockingQueue<SWire>(SERVE_OUTBOX_CAP)
        fun enqueue(w: SWire) {
            while (!outbox.offer(w)) {
                outbox.poll()
                Log.w("sync-host", "outbox full — dropped the oldest push ($DROPPED_PUSH_NOTE)")
            }
        }
        val listener: (String) -> Unit = { key ->
            if (Persistence.syncable(key)) {
                peer.store.record(key)?.let { (v, t) -> enqueue(SWire(t = "syncrec", records = listOf(SRec(key, v, t)))) }
            }
        }
        val sender = Thread({
            try {
                while (true) {
                    val w = outbox.take()
                    if (w === END) break
                    out.sendWire(w, now)
                }
            } catch (e: Exception) {
                try { sock.close() } catch (c: Exception) { /* closing */ }
            }
        }, "sync-sender-${sock.inetAddress}").apply { isDaemon = true }
        sender.start()
        peer.store.addListener(listener)
        peer.store.syncConnectionStarted()
        Log.i("sync-host", "peer ${sock.inetAddress} attached to the sync channel")
        try {
            handshake(json.decodeFromString(SWire.serializer(), helloLine), helloAt)
            while (true) {
                val got = inp.readWire(now)
                val w = got.w
                when (w.t) {
                    "sync" -> handshake(w, got.at)  // the client's periodic re-handshake
                    "syncrec" -> for (r in w.records) {
                        if (!Persistence.syncable(r.k)) { Log.w("sync-host", "unsyncable key '${r.k}' ignored"); continue }
                        peer.applier(r.k, r.v, normalize(r.t))
                    }
                    else -> Log.w("sync-host", "unknown sync control '${w.t}' ignored")
                }
            }
        } catch (e: EOFException) {
            Log.i("sync-host", "peer ${sock.inetAddress} left the sync channel")
        } catch (e: Exception) {
            Log.w("sync-host", "sync channel ended: ${e.message}")
        } finally {
            peer.store.removeListener(listener)
            // the bounded queue must never park the teardown: make room
            while (!outbox.offer(END)) outbox.poll()
            try { sock.close() } catch (e: Exception) { /* closed */ }
        }
    }
}

/**
 * The client side (the phone; also any host syncing INTO a content server):
 * one persistent connection, keeper-style reconnect with pacing, forever.
 * A host that predates the channel closes the session on the `sync` request
 * — logged once, then quietly retried at the same pacing (it will start
 * answering the moment the PC runs a build that speaks it).
 */
class RemoteSync(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val scope: CoroutineScope,
    private val peer: SyncPeer,
    private val retryPacingMs: Long = 15_000,
    private val rehandshakeMs: Long = 300_000,
    /** This side's clock: the stamp of a handshake frame and the receive time of a frame. */
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    @Serializable
    private data class Hello(val t: String = "hello", val token: String, val proto: Int = 1)

    @Volatile private var running = true
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var sockRef: Socket? = null
    @Volatile private var skew = 0L                  // serverClock - ourNow
    private var saidNoSync = false
    /** Pushes leave through this queue and a sender coroutine — NEVER by a
     *  blocking socket write on the store listener's thread, which is the
     *  SHELL LOOP for every save-path put (review 2026-09-01: a silently stopped
     *  path plus a full TCP send buffer parked the loop mid-saveAll). The
     *  outbox is cleared on reconnect; the handshake that follows carries what
     *  was lost wherever the two copies' stamps are more than the tolerance
     *  apart, and leaves a difference inside it until that key's next edit. */
    private val outbox = LinkedBlockingQueue<SWire>(OUTBOX_CAP)
    /** Bounded (R2#20e): a half-open link otherwise grows the queue for the
     *  hours TCP keepalive takes to notice. Overflow drops the OLDEST push
     *  loudly. The 5-minute re-handshake carries a dropped push again when the
     *  two copies' stamps are more than the tolerance apart; inside it (a
     *  second edit of one key within 2 s whose push was the one dropped) the
     *  copies stay different until that key's next edit, and the line says so. */
    private fun enqueue(w: SWire) {
        while (!outbox.offer(w)) {
            outbox.poll()
            Log.w("sync", "outbox full — dropped the oldest push (${SyncNet.DROPPED_PUSH_NOTE})")
        }
    }
    private val listener: (String) -> Unit = { key ->
        if (Persistence.syncable(key) && out != null) {
            peer.store.record(key)?.let { (v, t) ->
                enqueue(SWire(t = "syncrec", records = listOf(SRec(key, v, t))))
            }
        }
    }
    private val loop: Job = scope.launch(kotlinx.coroutines.Dispatchers.IO) { connectLoop() }

    init {
        peer.store.addListener(listener)
    }

    /** The handshake frame. Its `clock` is stamped by sendWire when the frame is written,
     *  not here: the ticker's copy waits in the outbox behind whatever pushes are queued. */
    private fun handshakeWire() = SWire(t = "sync",
        stamps = peer.store.stamps().filterKeys { Persistence.syncable(it) })

    /** Queue a handshake behind the pushes already waiting: the ticker's step, and the
     *  door a test uses to place one without waiting five minutes. */
    internal fun rehandshake() = enqueue(handshakeWire())

    /** A peer's stamp on this side's clock (a 0 stays 0). */
    private fun local(t: Long) = shifted(t, skew)

    private suspend fun connectLoop() {
        while (scope.isActive && running) {
            var attached = false
            try {
                Socket(host, port).use { sock ->
                    sockRef = sock
                    sock.tcpNoDelay = true
                    sock.keepAlive = true   // OS liveness probing on an idle link, not a bound on work
                    val inp = DataInputStream(sock.getInputStream().buffered())
                    val o = DataOutputStream(sock.getOutputStream().buffered())
                    val hello = json.encodeToString(Hello.serializer(), Hello(token = token)).toByteArray(Charsets.UTF_8)
                    synchronized(o) { o.writeInt(hello.size); o.write(hello); o.flush() }
                    peer.store.syncConnectionStarted()
                    o.sendWire(handshakeWire(), now)
                    // the sender: drains queued pushes off the listener's thread
                    outbox.clear()
                    val sender = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            while (isActive) {
                                val w = kotlinx.coroutines.runInterruptible { outbox.take() }
                                o.sendWire(w, now)
                            }
                        } catch (e: Exception) {
                            try { sock.close() } catch (c: Exception) { /* closing */ }
                        }
                    }
                    // the periodic re-handshake: it carries again a push lost around
                    // a shell stop or a dropped write, when the two copies' stamps are
                    // more than the tolerance apart (inside it they stay as they are)
                    val ticker = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        while (isActive) {
                            delay(rehandshakeMs)
                            rehandshake()
                        }
                    }
                    try {
                        while (true) {
                            val got = inp.readWire(now)
                            val w = got.w
                            when (w.t) {
                                "syncok" -> {
                                    skew = w.clock - got.at
                                    if (!attached) {
                                        attached = true
                                        saidNoSync = false
                                        out = o
                                        Log.i("sync", "sync channel to $host up (${w.records.size} newer there, ${w.want.size} wanted)")
                                    }
                                    if (w.records.isNotEmpty() || w.want.isNotEmpty()) {
                                        // §72: which keys, with both stamps on this clock, BEFORE the apply
                                        val mine = peer.store.stamps()
                                        val theirs = w.records.associate { it.k to local(it.t) }
                                        Log.i("sync", "handshake with $host: newer there: " +
                                            SyncNet.describeKeys(w.records.map { it.k }, theirs, "there", mine, "here") +
                                            "; newer here: " + SyncNet.describeKeys(w.want, mine, "here", emptyMap<String, Long>(), ""))
                                    }
                                    for (r in w.records) peer.applier(r.k, r.v, local(r.t))
                                    if (w.want.isNotEmpty()) {
                                        val recs = w.want.mapNotNull { k ->
                                            peer.store.record(k)?.let { (v, t) -> SRec(k, v, t) }
                                        }
                                        if (recs.isNotEmpty()) o.sendWire(SWire(t = "syncrec", records = recs), now)
                                    }
                                }
                                "syncrec" -> for (r in w.records) peer.applier(r.k, r.v, local(r.t))
                                // a host running WITHOUT a sync peer answers the request
                                // in-band: treat it like an old host — retry at pacing
                                "err" -> throw java.io.IOException("host refused the sync channel")
                                else -> Log.w("sync", "unknown sync control '${w.t}' ignored")
                            }
                        }
                    } finally {
                        ticker.cancel()
                        // the sender too (R2#6): left running it parks in
                        // outbox.take() pinning an IO thread per reconnect,
                        // and each stale sender STEALS one queued record for
                        // its closed socket before ending — a flapping-WiFi day
                        // leaked dozens of threads and dropped records until
                        // the 5-minute re-handshake carried them again
                        sender.cancel()
                    }
                }
            } catch (e: Exception) {
                out = null
                if (attached) {
                    Log.w("sync", "sync channel to $host down: ${e.message}")
                } else if (!saidNoSync) {
                    // an old host closes the session on the unknown request —
                    // said once, then retried quietly at the same pacing
                    saidNoSync = true
                    Log.i("sync", "no sync channel at $host yet (${e.message ?: "closed"}) — will keep asking")
                }
            }
            if (!running) return
            delay(retryPacingMs)          // pacing between attempts, not a timeout
        }
    }

    override fun close() {
        running = false
        peer.store.removeListener(listener)
        loop.cancel()
        out = null
        try { sockRef?.close() } catch (e: Exception) { /* closing */ }
    }

    private companion object {
        const val OUTBOX_CAP = 256
    }
}
