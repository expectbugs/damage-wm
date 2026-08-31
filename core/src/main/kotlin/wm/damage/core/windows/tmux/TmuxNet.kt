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

    fun serve(sock: Socket, inp: DataInputStream, out: DataOutputStream, provider: TmuxProvider) {
        val outbox = LinkedBlockingQueue<TWire>()
        val listener = object : TmuxProvider.Listener {
            override fun status(sessions: List<TmuxSessionInfo>, cfg: TmuxConfig) {
                outbox.put(TWire(t = "tstat", sessions = sessions, cfg = cfg))
            }
            override fun frame(target: TmuxTarget, frame: PaneFrame) {
                outbox.put(TWire(t = "tframe", target = target, frame = frame))
            }
            override fun alert(session: TmuxSessionInfo) {
                outbox.put(TWire(t = "talert", session = session))
            }
            override fun state(state: String) {
                outbox.put(TWire(t = "tstate", detail = state))
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
    private val loop: Job = scope.launch(kotlinx.coroutines.Dispatchers.IO) { connectLoop() }

    private suspend fun connectLoop() {
        var offlineSince = 0L
        while (scope.isActive && running) {
            try {
                Socket(host, port).use { sock ->
                    sockRef = sock
                    sock.tcpNoDelay = true
                    val inp = DataInputStream(sock.getInputStream().buffered())
                    val o = DataOutputStream(sock.getOutputStream().buffered())
                    // the content hello, then switch the connection to tmux
                    val hello = json.encodeToString(Hello.serializer(), Hello(token = token)).toByteArray(Charsets.UTF_8)
                    synchronized(o) { o.writeInt(hello.size); o.write(hello); o.flush() }
                    o.sendWire(TWire(t = "tmux"))
                    out = o
                    offlineSince = 0
                    setState("")
                    // re-assert what a reconnect interrupted: the subscription
                    // and any non-default capture pacing
                    wantedPace?.let { o.sendWire(TWire(t = "tpace", paceMs = it)) }
                    wantedTarget?.let { o.sendWire(TWire(t = "tsub", target = it)) }
                    while (true) route(inp.readWire())
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
        try {
            out?.sendWire(TWire(t = "tsub", target = target))
        } catch (e: Exception) {
            Log.w("tmux-remote", "subscribe not sent (reconnect will re-assert): ${e.message}")
        }
    }

    override fun setCapturePacing(ms: Long) {
        wantedPace = ms
        try {
            out?.sendWire(TWire(t = "tpace", paceMs = ms))
        } catch (e: Exception) {
            Log.w("tmux-remote", "pacing not sent (reconnect will re-assert): ${e.message}")
        }
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
        failPending("tmux provider closed")
        try { sockRef?.close() } catch (e: Exception) { /* closing */ }   // unparks a blocked read
    }
}
