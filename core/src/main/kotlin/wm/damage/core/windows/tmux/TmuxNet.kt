package wm.damage.core.windows.tmux

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wm.damage.core.util.Log

/**
 * The tmux channel over the CONTENT port (TMUX.md §3.1): a Reader-style
 * connection that sends `{"t":"tmux"}` after the hello becomes a persistent
 * bidirectional tmux session — the server pushes status/frames/alerts through
 * one ordered sender, the client sends subscriptions and id-correlated
 * requests. Same port, same token, zero new phone config.
 *
 * Framing matches Content.kt (4-byte length + JSON); unknown `t` values are
 * logged, not fatal, so protocol growth stays additive.
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class TWire(
    val t: String,
    val id: Long = 0,
    val op: String = "",
    val target: TmuxTarget? = null,
    val keys: List<String> = emptyList(),
    val text: String = "",
    val lines: Int = 0,
    val idx: Int = 0,
    val cols: Int = 0,
    val rows: Int = 0,
    val host: String = "",
    val name: String = "",
    val ok: Boolean = false,
    val detail: String = "",
    val sessions: List<TmuxSessionInfo> = emptyList(),
    val cfg: TmuxConfig? = null,
    val frame: PaneFrame? = null,
    val session: TmuxSessionInfo? = null,
    val outLines: List<String> = emptyList(),
    val wins: List<TmuxWinInfo> = emptyList(),
    /** t="tpace": the driver's capture pacing (2026-08-31, the flow rework).
     *  Additive: an old host logs the unknown control and keeps its default. */
    val paceMs: Long = 0,
)

private fun DataOutputStream.sendWire(w: TWire) {
    val b = json.encodeToString(TWire.serializer(), w).toByteArray(Charsets.UTF_8)
    synchronized(this) {
        writeInt(b.size)
        write(b)
        flush()
    }
}

private fun DataInputStream.readWire(maxLen: Int = 16 shl 20): TWire {
    val n = readInt()
    require(n in 1..maxLen) { "tmux frame length $n out of range" }
    val b = ByteArray(n)
    readFully(b)
    return json.decodeFromString(TWire.serializer(), b.toString(Charsets.UTF_8))
}

object TmuxNet {

    /**
     * Serve one connected driver on an already-helloed content connection.
     * Blocks until the client leaves; the caller owns the socket. The
     * provider outlives the session (it belongs to the host process).
     */
    /** Sender-thread stop sentinel — LinkedBlockingQueue refuses nulls. */
    private val END = TWire(t = "__end")

    /** Hard cap on queued pushes toward one driver (L3) — beyond it the
     *  oldest drops loudly; the reconnecting driver re-subscribes anyway. */
    private const val OUTBOX_CAP = 64

    fun serve(sock: Socket, inp: DataInputStream, out: DataOutputStream, provider: TmuxProvider) {
        val outbox = LinkedBlockingQueue<TWire>()
        // COALESCE state-bearing pushes (review 2026-09-01 L3): a half-open
        // driver (Doze without FIN) wedges the sender in the TCP buffer while
        // the listener keeps producing ~1 frame/s — only the NEWEST tstat /
        // tstate / per-target tframe matters, so replace instead of append
        // (the ReplicaServer build-at-send lesson). Alerts and answers queue.
        fun coalesce(w: TWire, same: (TWire) -> Boolean) {
            synchronized(outbox) {
                outbox.removeIf { it.t == w.t && same(it) }
                outbox.put(w)
                if (outbox.size > OUTBOX_CAP) {
                    // drop the oldest STATE-BEARING wire only (R2#11): a
                    // dropped tres parks the driver's id-correlated request
                    // forever (no timeouts by law), a dropped talert is a
                    // lost alert — both are non-re-derivable. State pushes
                    // are; and this wire itself is one, so a victim exists.
                    val victim = outbox.firstOrNull {
                        it.t == "tstat" || it.t == "tframe" || it.t == "tstate"
                    }
                    if (victim != null) {
                        outbox.remove(victim)
                        Log.w("tmux-host", "outbox over $OUTBOX_CAP — oldest ${victim.t} dropped (driver stalled?)")
                    }
                }
            }
        }
        val listener = object : TmuxProvider.Listener {
            override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) {
                coalesce(TWire(t = "tstat", sessions = sessions, cfg = cfg)) { true }
            }
            override fun frame(target: TmuxTarget, frame: PaneFrame) {
                coalesce(TWire(t = "tframe", target = target, frame = frame)) { it.target == target }
            }
            override fun alert(session: TmuxSessionInfo) {
                outbox.put(TWire(t = "talert", session = session))
            }
            override fun state(state: String) {
                coalesce(TWire(t = "tstate", detail = state)) { true }
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
        }, "tmux-sender-${sock.inetAddress}")
        sender.start()
        provider.addListener(listener)
        Log.i("tmux-host", "driver ${sock.inetAddress} attached to the tmux channel")
        try {
            while (true) {
                val w = inp.readWire()
                when (w.t) {
                    "tsub" -> provider.subscribe(listener, w.target)
                    "tpace" -> provider.setCapturePacing(w.paceMs)
                    "treq" -> {
                        val res = try {
                            when (w.op) {
                                "keys" -> { provider.sendKeys(w.target!!, w.keys); TWire("tres", id = w.id, ok = true) }
                                "lit" -> { provider.sendLiteral(w.target!!, w.text); TWire("tres", id = w.id, ok = true) }
                                "hist" -> TWire("tres", id = w.id, ok = true,
                                    outLines = provider.history(w.target!!, w.lines))
                                "win" -> TWire("tres", id = w.id, ok = true, wins = provider.windows(w.target!!))
                                "new" -> TWire("tres", id = w.id, ok = true, name = provider.newSession(w.host))
                                "kill" -> { provider.killSession(w.target!!); TWire("tres", id = w.id, ok = true) }
                                "ren" -> { provider.renameSession(w.target!!, w.name); TWire("tres", id = w.id, ok = true) }
                                "sel" -> { provider.selectWindow(w.target!!, w.idx); TWire("tres", id = w.id, ok = true) }
                                "fit" -> { provider.resizeWindow(w.target!!, w.cols, w.rows); TWire("tres", id = w.id, ok = true) }
                                else -> TWire("tres", id = w.id, ok = false, detail = "unknown op '${w.op}'")
                            }
                        } catch (e: Exception) {
                            TWire("tres", id = w.id, ok = false, detail = e.message ?: e.toString())
                        }
                        outbox.put(res)
                    }
                    else -> Log.w("tmux-host", "unknown tmux control '${w.t}' ignored")
                }
            }
        } catch (e: EOFException) {
            Log.i("tmux-host", "driver ${sock.inetAddress} left the tmux channel")
        } catch (e: Exception) {
            Log.w("tmux-host", "tmux channel ended: ${e.message}")
        } finally {
            provider.removeListener(listener)
            outbox.put(END)                      // stop the sender
            try { sock.close() } catch (e: Exception) { /* closed */ }
        }
    }
}

/**
 * The phone/remote side: a [TmuxProvider] whose real implementation is on the
 * content host. One persistent connection, keeper-style reconnect (2 s
 * pacing, forever, no timeouts); requests are id-correlated and park until
 * the answer or the link's end fails them loudly. While disconnected the
 * window sees `state("PC unreachable …")` — the §10.5 staleness surface.
 */
class RemoteTmuxProvider(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val scope: CoroutineScope,
    private val retryPacingMs: Long = 2_000,
) : TmuxProvider {

    @Serializable
    private data class Hello(val t: String = "hello", val token: String, val proto: Int = 1)

    private val listeners = ConcurrentHashMap.newKeySet<TmuxProvider.Listener>()
    @Volatile private var wantedTarget: TmuxTarget? = null
    @Volatile private var wantedPace: Long? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var running = true
    @Volatile private var lastStatus: List<TmuxSessionInfo> = emptyList()
    @Volatile private var lastCfg: TmuxConfig = TmuxConfig()
    @Volatile private var stateLine = "connecting to $host…"
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<TWire>>()
    @Volatile private var sockRef: Socket? = null
    @Volatile private var subSet = false
    /** Control sends (tsub/tpace) go through ONE conflated lane (R2#12): two
     *  independent launches could arrive REVERSED, leaving the host on the
     *  previous target while wantedTarget said otherwise. The consumer always
     *  sends the CURRENT wanted values — off the shell loop (L5), ordered,
     *  and a queued-stale send cannot exist. */
    private val ctlKick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val ctlSender: Job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
        for (k in ctlKick) {
            val o = out ?: continue
            try {
                wantedPace?.let { o.sendWire(TWire(t = "tpace", paceMs = it)) }
                if (subSet) o.sendWire(TWire(t = "tsub", target = wantedTarget))
            } catch (e: Exception) {
                Log.w("tmux-remote", "control not sent: ${e.message} — dropping the link so the reader reconnects")
                // a failed control write is how a SILENT path death becomes
                // visible (R3#1): the parked reader never errors on its own —
                // closing the socket unparks it and the connect loop heals.
                // Only while the failed stream is STILL the live one (R4#4):
                // a slow failure landing after a reconnect must not close the
                // healthy new socket
                if (out === o) try { sockRef?.close() } catch (c: Exception) { /* closing */ }
            }
        }
    }
    private val loop: Job = scope.launch(kotlinx.coroutines.Dispatchers.IO) { connectLoop() }

    private suspend fun connectLoop() {
        var offlineSince = 0L
        while (scope.isActive && running) {
            try {
                Socket(host, port).use { sock ->
                    sockRef = sock
                    sock.tcpNoDelay = true
                    sock.keepAlive = true   // OS liveness probing on an idle link (R3#1)
                    val inp = DataInputStream(sock.getInputStream().buffered())
                    val o = DataOutputStream(sock.getOutputStream().buffered())
                    // the content hello, then switch the connection to tmux
                    val hello = json.encodeToString(Hello.serializer(), Hello(token = token)).toByteArray(Charsets.UTF_8)
                    synchronized(o) { o.writeInt(hello.size); o.write(hello); o.flush() }
                    o.sendWire(TWire(t = "tmux"))
                    out = o
                    // re-assert what a reconnect interrupted: the subscription
                    // and any non-default capture pacing — through the same
                    // lane every control send uses (R2#12)
                    ctlKick.trySend(Unit)
                    // paced liveness (R3#1): while subscribed, re-asserting is
                    // an idempotent WRITE — on a silently dead path the send
                    // fails within TCP's own retransmission bound and the ctl
                    // sender drops the link, where a pure reader parks forever
                    val liveness = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        while (isActive) {
                            delay(LIVENESS_PACING_MS)
                            if (subSet || wantedPace != null) ctlKick.trySend(Unit)
                        }
                    }
                    try {
                        while (true) {
                            val w = inp.readWire()
                            // healthy only on a KNOWN-GOOD frame (R3#2 + R4#3):
                            // flipping on the "err" refusal re-created the very
                            // flap the first-frame rule removed
                            if (w.t == "tstat" || w.t == "tstate" || w.t == "tframe" ||
                                w.t == "talert" || w.t == "tres") {
                                if (offlineSince != 0L) { offlineSince = 0; setState("") }
                            }
                            route(w)
                        }
                    } finally {
                        liveness.cancel()
                    }
                }
            } catch (e: Exception) {
                out = null
                if (offlineSince == 0L) {
                    offlineSince = System.currentTimeMillis()
                    Log.w("tmux-remote", "tmux channel to $host down: ${e.message}")
                }
                failPending("tmux channel to $host down: ${e.message}")
                setState("PC unreachable ${(System.currentTimeMillis() - offlineSince) / 1000}s")
            }
            if (!running) return
            delay(retryPacingMs)      // pacing between attempts, not a timeout
        }
    }

    private fun route(w: TWire) {
        when (w.t) {
            "tstat" -> {
                lastStatus = w.sessions
                w.cfg?.let { lastCfg = it }
                for (l in listeners) l.status(w.sessions, lastCfg)
            }
            "tstate" -> setState(w.detail)
            "tframe" -> {
                val t = w.target ?: return
                val f = w.frame ?: return
                for (l in listeners) l.frame(t, f)
            }
            "talert" -> w.session?.let { s -> for (l in listeners) l.alert(s) }
            "tres" -> pending.remove(w.id)?.complete(w)
            // the host's in-band refusal: paced quiet retry, never "attached"
            "err" -> throw java.io.IOException(
                "host refused the tmux channel${if (w.detail.isNotEmpty()) ": ${w.detail}" else ""}")
            else -> Log.w("tmux-remote", "unknown tmux control '${w.t}' ignored")
        }
    }

    private fun setState(s: String) {
        stateLine = s
        for (l in listeners) try { l.state(s) } catch (e: Exception) { Log.e("tmux-remote", "state listener", e) }
    }

    private fun failPending(why: String) {
        for (id in pending.keys.toList()) {
            pending.remove(id)?.completeExceptionally(IllegalStateException(why))
        }
    }

    private fun request(w: TWire): TWire {
        val o = out ?: throw IllegalStateException("tmux host $host is not reachable")
        val id = nextId.getAndIncrement()
        val fut = CompletableFuture<TWire>()
        pending[id] = fut
        try {
            o.sendWire(w.copy(id = id))
        } catch (e: Exception) {
            pending.remove(id)
            throw IllegalStateException("tmux request not sent: ${e.message}")
        }
        // parks until the host answers or the connect loop fails it on link
        // end — the request's fate is always decided, never abandoned
        val res = fut.get()
        if (!res.ok) throw IllegalStateException(res.detail.ifEmpty { "tmux ${w.op} refused" })
        return res
    }

    override fun addListener(l: TmuxProvider.Listener) {
        listeners.add(l)
        l.status(lastStatus, lastCfg)
        l.state(stateLine)
    }

    override fun removeListener(l: TmuxProvider.Listener) {
        listeners.remove(l)
    }

    override fun subscribe(l: TmuxProvider.Listener, target: TmuxTarget?) {
        wantedTarget = target
        subSet = true
        ctlKick.trySend(Unit)
    }

    override fun setCapturePacing(ms: Long) {
        wantedPace = ms
        ctlKick.trySend(Unit)
    }

    override fun sendKeys(target: TmuxTarget, keys: List<String>) {
        request(TWire(t = "treq", op = "keys", target = target, keys = keys))
    }

    override fun sendLiteral(target: TmuxTarget, text: String) {
        request(TWire(t = "treq", op = "lit", target = target, text = text))
    }

    override fun history(target: TmuxTarget, lines: Int): List<String> =
        request(TWire(t = "treq", op = "hist", target = target, lines = lines)).outLines

    override fun windows(target: TmuxTarget): List<TmuxWinInfo> =
        request(TWire(t = "treq", op = "win", target = target)).wins

    override fun newSession(host: String): String =
        request(TWire(t = "treq", op = "new", host = host)).name

    override fun killSession(target: TmuxTarget) {
        request(TWire(t = "treq", op = "kill", target = target))
    }

    override fun renameSession(target: TmuxTarget, newName: String) {
        request(TWire(t = "treq", op = "ren", target = target, name = newName))
    }

    override fun selectWindow(target: TmuxTarget, idx: Int) {
        request(TWire(t = "treq", op = "sel", target = target, idx = idx))
    }

    override fun resizeWindow(target: TmuxTarget, cols: Int, rows: Int) {
        request(TWire(t = "treq", op = "fit", target = target, cols = cols, rows = rows))
    }

    override fun close() {
        running = false
        loop.cancel()
        ctlKick.close()                  // ends the control-send lane (R2#12)
        failPending("tmux provider closed")
        try { sockRef?.close() } catch (e: Exception) { /* closing */ }   // unparks a blocked read
    }

    private companion object {
        /** Idempotent re-assert pacing while subscribed (R3#1) — the write
         *  that turns a silent path death into a visible failure. */
        const val LIVENESS_PACING_MS = 60_000L
    }
}
