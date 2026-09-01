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
 * the handshake exchanges stamp maps and each side's clock, strictly-newer
 * records flow each way (last-write-wins per key), and afterwards every
 * LOCAL change pushes live. The client re-handshakes every 5 minutes on a
 * live connection, so convergence never depends on no push ever being lost.
 *
 * Framing matches Content.kt (4-byte length + JSON). Synced keys are
 * [Persistence.syncable] only. Skew: each handshake carries the sender's
 * clock; the receiver shifts incoming stamps by the measured offset before
 * comparing — NTP does the real work, this is insurance.
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

private fun DataOutputStream.sendWire(w: SWire) {
    val b = json.encodeToString(SWire.serializer(), w).toByteArray(Charsets.UTF_8)
    synchronized(this) {
        writeInt(b.size)
        write(b)
        flush()
    }
}

private fun DataInputStream.readWire(maxLen: Int = 16 shl 20): SWire {
    val n = readInt()
    require(n in 1..maxLen) { "sync frame length $n out of range" }
    val b = ByteArray(n)
    readFully(b)
    return json.decodeFromString(SWire.serializer(), b.toString(Charsets.UTF_8))
}

/**
 * One side's sync surface: the store, plus the applier that routes an incoming
 * record — through the live shell when one runs (`Shell.postSync`: freshen,
 * LWW, live-apply) and straight into the store otherwise. The stamp handed to
 * the applier is already skew-normalized to THIS side's clock.
 */
class SyncPeer(
    val store: Persistence,
    val applier: (key: String, value: JsonObject, stamp: Long) -> Unit = { k, v, t ->
        store.tryApplyRemote(k, v, t)
    },
)

object SyncNet {

    /** Sender-thread stop sentinel — LinkedBlockingQueue refuses nulls. */
    private val END = SWire(t = "__end")

    /** R3#5: the server outbox's bound (the client's cap is its own const). */
    private const val SERVE_OUTBOX_CAP = 256

    private fun syncableStamps(store: Persistence): Map<String, Long> =
        store.stamps().filterKeys { Persistence.syncable(it) }

    /** The records of [store] strictly newer than [theirs] (already normalized
     *  to local time), plus the keys THEY hold strictly newer than us. */
    private fun diff(store: Persistence, theirs: Map<String, Long>): Pair<List<SRec>, List<String>> {
        val mine = syncableStamps(store)
        val newerHere = ArrayList<SRec>()
        for ((k, t) in mine) {
            if (t > (theirs[k] ?: 0L)) store.record(k)?.let { (v, stamp) -> newerHere.add(SRec(k, v, stamp)) }
        }
        val newerThere = theirs.entries
            .filter { Persistence.syncable(it.key) && it.value > (mine[it.key] ?: 0L) }
            .map { it.key }
        return newerHere to newerThere
    }

    /**
     * Serve one sync client on an already-helloed content connection whose
     * first post-hello line was [helloLine] (the client's `sync` handshake).
     * Blocks until the client leaves; the caller owns the socket.
     */
    fun serve(sock: Socket, inp: DataInputStream, out: DataOutputStream, peer: SyncPeer, helloLine: String) {
        var skew = 0L      // clientClock - ourNow at the last handshake
        fun normalize(t: Long) = t - skew

        fun handshake(w: SWire) {
            skew = w.clock - System.currentTimeMillis()
            val theirs = w.stamps.mapValues { normalize(it.value) }
            val (records, want) = diff(peer.store, theirs)
            out.sendWire(SWire(t = "syncok", records = records, want = want,
                clock = System.currentTimeMillis()))
        }

        // bounded like the client's (R3#5 — R2#20e applied to one side only):
        // a half-open peer parks the sender in the TCP buffer while local puts
        // keep producing; overflow drops the OLDEST loudly, and the client's
        // periodic re-handshake heals any dropped push
        val outbox = LinkedBlockingQueue<SWire>(SERVE_OUTBOX_CAP)
        fun enqueue(w: SWire) {
            while (!outbox.offer(w)) {
                outbox.poll()
                Log.w("sync-host", "outbox full — dropped the oldest push (the re-handshake heals it)")
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
                    out.sendWire(w)
                }
            } catch (e: Exception) {
                try { sock.close() } catch (c: Exception) { /* closing */ }
            }
        }, "sync-sender-${sock.inetAddress}")
        sender.start()
        peer.store.addListener(listener)
        Log.i("sync-host", "peer ${sock.inetAddress} attached to the sync channel")
        try {
            handshake(json.decodeFromString(SWire.serializer(), helloLine))
            while (true) {
                val w = inp.readWire()
                when (w.t) {
                    "sync" -> handshake(w)          // the client's periodic re-handshake
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
     *  SHELL LOOP for every save-path put (review 2026-09-01: a silently dead
     *  path plus a full TCP send buffer parked the loop mid-saveAll). The
     *  outbox is cleared on reconnect; the re-handshake carries anything lost. */
    private val outbox = LinkedBlockingQueue<SWire>(OUTBOX_CAP)
    /** Bounded (R2#20e): a half-open link otherwise grows the queue for the
     *  hours TCP keepalive takes to notice. Overflow drops the OLDEST push
     *  loudly — the 5-minute re-handshake is the designed healing path for
     *  any lost push, so a drop here converges the same way. */
    private fun enqueue(w: SWire) {
        while (!outbox.offer(w)) {
            outbox.poll()
            Log.w("sync", "outbox full — dropped the oldest push (the re-handshake heals it)")
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

    private fun handshakeWire() = SWire(t = "sync",
        stamps = peer.store.stamps().filterKeys { Persistence.syncable(it) },
        clock = System.currentTimeMillis())

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
                    o.sendWire(handshakeWire())
                    // the sender: drains queued pushes off the listener's thread
                    outbox.clear()
                    val sender = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            while (isActive) {
                                val w = kotlinx.coroutines.runInterruptible { outbox.take() }
                                o.sendWire(w)
                            }
                        } catch (e: Exception) {
                            try { sock.close() } catch (c: Exception) { /* closing */ }
                        }
                    }
                    // the periodic re-handshake: the convergence net for any
                    // push lost around a shell stop or a dropped write
                    val ticker = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        while (isActive) {
                            delay(rehandshakeMs)
                            enqueue(handshakeWire())
                        }
                    }
                    try {
                        while (true) {
                            val w = inp.readWire()
                            when (w.t) {
                                "syncok" -> {
                                    skew = w.clock - System.currentTimeMillis()
                                    if (!attached) {
                                        attached = true
                                        saidNoSync = false
                                        out = o
                                        Log.i("sync", "sync channel to $host up (${w.records.size} newer there, ${w.want.size} wanted)")
                                    }
                                    for (r in w.records) peer.applier(r.k, r.v, r.t - skew)
                                    if (w.want.isNotEmpty()) {
                                        val recs = w.want.mapNotNull { k ->
                                            peer.store.record(k)?.let { (v, t) -> SRec(k, v, t) }
                                        }
                                        if (recs.isNotEmpty()) o.sendWire(SWire(t = "syncrec", records = recs))
                                    }
                                }
                                "syncrec" -> for (r in w.records) peer.applier(r.k, r.v, r.t - skew)
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
                        // its dead socket before dying — a flapping-WiFi day
                        // leaked dozens of threads and dropped records until
                        // the 5-minute re-handshake healed them
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
