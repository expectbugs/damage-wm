package wm.damage.core.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import wm.damage.core.util.Log

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P2 (`CONTRACT.md` §9.3, §13 (b) "paced ping", "cursors", "contract version per window"; the
 * spec's §2 P2 and §3 "`AppClient` connection"): the Kotlin peer of the `app` lane, in `RemoteWin`'s shape
 * (`net/WinNet.kt`): one connection, reconnected on pacing for ever; the content port's hello (`proto: 1`), then the
 * lane's own (`proto: 3`). Each `welcome` asks the listener what to subscribe, with its cursors.
 *
 * Reach is decided by the paced ping alone: at each ping tick, a ping still unanswered from the tick before means the
 * PC is out of reach (said once, the connection closed so the next one starts); any answer — a `pong`, or a fresh
 * `welcome` — means it is back. A failed write or a failed connect decides nothing: the ping it took with it goes
 * unanswered and the next tick judges it. A refusal (the credential, or no lane this build speaks: the PC and the
 * phone are deployed together, L1 C3) is said once per run of them and retried on a longer pacing. Every callback runs
 * on the reading thread: hand off, never block.
 */
class AppClient(
    private val host: String,
    private val port: Int,
    private val credential: Credential,
    /** This device's stream (minted once and kept by the caller, C§2.4). */
    private val stream: String,
    private val listener: Listener,
    private val scope: CoroutineScope,
    private val contracts: Map<String, Int> = Contracts.MINE,
    val retryPacingMs: Long = 2_000,
    /** After a refusal (a refused credential, no lane this build speaks): nothing changes until a deploy. */
    val refusedPacingMs: Long = 30_000,
    /** 0 = no ping task (a test calls [pingTick]). */
    val pingEveryMs: Long = 15_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    sealed class Credential {
        /** The shared token (his phone, C§10.9). */
        data class Token(val token: String) : Credential() {
            override fun toString() = "Token(…)"
        }

        /** The local secret, on loopback only (the PC's command line and companion, C§10.6). */
        data class Local(val secret: String) : Credential() {
            override fun toString() = "Local(…)"
        }
    }

    /** The PC as the paced ping last judged it. */
    sealed class Reach {
        object Unknown : Reach() {
            override fun toString() = "Unknown"
        }

        object Up : Reach() {
            override fun toString() = "Up"
        }

        /** Out of reach since the ping that went unanswered was sent. */
        data class Down(val since: Long) : Reach()
    }

    /** A subscription to make on a connection: a scope and the client's cursor in it (null = first contact). */
    data class Sub(val scope: String, val cursor: Cursor?)

    interface Listener {
        /** What to subscribe on the connection [w] opened, with the cursors the caller holds. */
        fun subs(w: Welcome): List<Sub> = emptyList()

        /** After the subscriptions went out: resend queued ops here ([send]). */
        fun welcome(w: Welcome) {}
        fun snap(s: Snap) {}
        fun change(c: Chg) {}
        fun answer(a: Ans) {}

        /** The PC ended the connection with [reason] (`slow`, `proto`, `not open`, `shutting down`). */
        fun ended(reason: String) {}
        fun reach(r: Reach) {}
        fun contracts(s: Contracts.State) {}

        /** After each paced ping went out (or was counted as sent): the wire's pacing, for what waits on it (P3: the
         *  queue's head after a "retry" refusal, an append that failed). */
        fun pinged(k: Long) {}

        /** Stage L: a line on the PC–phone wall-clock difference ([ClockSkew]) for this device's journal. */
        fun clockSkew(line: String) {}

        /** R-b H2: a PC-served scope's description (a `snap` that carries `desc`, outside the record's order). */
        fun described(scope: String, desc: JsonObject) {}
    }

    @Volatile var reach: Reach = Reach.Unknown
        private set

    @Volatile var contractState: Contracts.State = Contracts.State.Unknown
        private set

    /** The live connection's `welcome`, or null while there is none. */
    @Volatile var welcome: Welcome? = null
        private set

    /** A state line for the log and a diagnosis row: empty while connected. */
    @Volatile var stateLine: String = "connecting to $host…"
        private set

    /** R-b H2: the PC answered but refused this device (its credential, or no lane this build speaks) — [stateLine] says
     *  which; cleared by the next `welcome`. */
    @Volatile var refused: Boolean = false
        private set

    @Volatile private var running = true
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var sockRef: Socket? = null
    private val writeLock = Any()

    private val pingLock = Any()
    private var pingK = 0L
    /** The ping sent at the last tick and not answered yet, and when it was sent. */
    private var outstanding: Long? = null
    private var outstandingAt = 0L
    /** Stage L: the last ping's number and when it went (this device's clock), for the clocks' line. */
    private var pingSentK = 0L
    private var pingSentMs = 0L
    private val skew = ClockSkew()

    private val loop: Job = scope.launch(Dispatchers.IO) { connectLoop() }
    private val pinger: Job? = if (pingEveryMs <= 0) null else scope.launch {
        while (isActive && running) {
            delay(pingEveryMs)                // the wire's pacing, not a bound on any work
            pingTick()
        }
    }

    /** Sends [o] on the live connection; false (the caller keeps it) when there is none or the write failed. */
    fun send(o: Op): Boolean = write(AppWire.op(o))

    fun subscribe(scope: String, cursor: Cursor?): Boolean = write(AppWire.sub(scope, cursor))

    fun unsubscribe(scope: String): Boolean = write(AppWire.unsub(scope))

    /** R-b H2: the phone's health lines to the PC; false (sent again at the next `welcome`) when there is no connection. */
    fun health(lines: List<HealthLine>): Boolean = write(AppWire.health(lines))

    /**
     * One paced decision (C§9.3): a ping still unanswered from the last tick means the PC is out of reach — said once
     * and the connection closed, so the next starts — and a new ping goes out (or is counted as sent when there is no
     * connection to carry it: the next tick judges it the same way).
     */
    fun pingTick() {
        val down: Reach.Down?
        val k: Long
        synchronized(pingLock) {
            down = if (outstanding != null && reach !is Reach.Down) Reach.Down(outstandingAt) else null
            if (down != null) reach = down
            // a run of unanswered pings is dated by its first
            if (outstanding == null) outstandingAt = clock()
            k = ++pingK
            outstanding = k
        }
        if (down != null) {
            Log.w(TAG, "the PC at $host is out of reach: ping ${k - 1} went unanswered")
            say { listener.reach(down) }
            closeSocket()
        }
        synchronized(pingLock) { pingSentK = k; pingSentMs = clock() }
        write(AppWire.ping(k))
        say { listener.pinged(k) }
    }

    override fun close() {
        running = false
        write(AppWire.end("closing"))
        loop.cancel()
        pinger?.cancel()
        closeSocket()
    }

    // ---- the connection ----

    private suspend fun connectLoop() {
        var refusedSaid = false
        var downSaid = false
        while (scope.isActive && running) {
            var pacing = retryPacingMs
            try {
                Socket(host, port).use { sock ->
                    sockRef = sock
                    sock.tcpNoDelay = true
                    sock.keepAlive = true   // OS liveness probing on an idle link, not a bound on work
                    val inp = DataInputStream(sock.getInputStream().buffered())
                    val o = DataOutputStream(sock.getOutputStream().buffered())
                    synchronized(writeLock) {
                        // the content port's hello stays proto 1 (dec. A.4); then the lane's own, one frame
                        AppWire.write(o, AppWire.encode(contentHello()))
                        AppWire.write(o, AppWire.encode(AppWire.hello(stream, contracts)))
                    }
                    out = o
                    while (true) {
                        val f = AppWire.read(inp)
                        when (val t = AppWire.type(f)) {
                            "welcome" -> {
                                val w = AppWire.parseWelcome(f)
                                welcome = w
                                refusedSaid = false
                                refused = false
                                downSaid = false
                                setState("")
                                answered()
                                val known = Contracts.State.Known(Contracts.compare(contracts, w.contracts))
                                if (known != contractState) {
                                    contractState = known
                                    say { listener.contracts(known) }
                                }
                                for (s in say({ listener.subs(w) }) ?: emptyList()) write(AppWire.sub(s.scope, s.cursor))
                                say { listener.welcome(w) }
                            }
                            // R-b H2: a PC-served scope's description carries `desc` and no place in the record's order
                            "snap" -> {
                                val d = f["desc"] as? JsonObject
                                if (d != null) say { listener.described(AppWire.str(f, "scope"), d) }
                                else AppWire.parseSnap(f).let { say { listener.snap(it) } }
                            }
                            "chg" -> AppWire.parseChg(f).let { say { listener.change(it) } }
                            "ans" -> AppWire.parseAns(f).let { say { listener.answer(it) } }
                            "pong" -> {
                                lastPong = AppWire.num(f, "k")
                                (f["ms"] as? JsonPrimitive)?.longOrNull?.let { pc -> clockSample(lastPong, pc, clock()) }
                                answered()
                            }
                            "end" -> {
                                val reason = text(f, "reason") ?: "no reason"
                                val detail = text(f, "detail")
                                Log.w(TAG, "the PC ended the app lane: $reason${detail?.let { " ($it)" } ?: ""}")
                                if (reason == AppWire.END_PROTO) {
                                    pacing = refusedPacingMs
                                    refused = true
                                    setState("the PC speaks another app lane (${detail ?: reason})")
                                }
                                say { listener.ended(reason) }
                                return@use
                            }
                            // an in-band refusal: the credential, or a PC that serves no app lane
                            "err" -> {
                                val detail = text(f, "detail") ?: ""
                                pacing = refusedPacingMs
                                refused = true
                                if (!refusedSaid) {
                                    refusedSaid = true
                                    Log.w(TAG, "the PC at $host refused the app lane ($detail); asked again every ${refusedPacingMs / 1000} s")
                                }
                                setState(if (detail.startsWith("bad token")) "the PC refused this device's credential"
                                    else "the PC refused the app lane ($detail)")
                                return@use
                            }
                            else -> Log.w(TAG, "unknown app message '$t' ignored")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running && !downSaid) {
                    downSaid = true
                    Log.w(TAG, "app lane to $host down: ${e::class.simpleName}: ${e.message}")
                }
            }
            out = null
            sockRef = null
            welcome = null
            if (stateLine.isEmpty()) setState("not connected to $host")
            if (!running) return
            delay(pacing)                     // pacing between attempts, not a timeout
        }
    }

    private fun contentHello(): JsonObject = buildJsonObject {
        put("t", "hello")
        when (credential) {
            is Credential.Token -> put("token", credential.token)
            is Credential.Local -> { put("token", ""); put("local", credential.secret) }
        }
        put("proto", 1)
    }

    /** Stage L: a pong carrying the PC's clock — the line, when [ClockSkew] has one to say (only the reading thread). */
    private fun clockSample(k: Long, pcMs: Long, gotMs: Long) {
        val sentMs = synchronized(pingLock) { if (k == pingSentK) pingSentMs else null } ?: return
        skew.sample(pcMs, sentMs, gotMs)?.let { line -> say { listener.clockSkew(line) } }
    }

    /** The PC answered: a pong or a fresh welcome. */
    private fun answered() {
        val back: Boolean
        synchronized(pingLock) {
            outstanding = null
            back = reach != Reach.Up
            reach = Reach.Up
        }
        if (back) say { listener.reach(Reach.Up) }
    }

    /** One frame on the live connection; false when there is none or the write failed. A failure closes the
     *  socket (the reading side then reconnects) and decides nothing about reach. */
    private fun write(frame: JsonObject): Boolean {
        val o = out ?: return false
        return try {
            synchronized(writeLock) { AppWire.write(o, AppWire.encode(frame)) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "a write to the PC failed (${e::class.simpleName}: ${e.message}): the connection is made again")
            closeSocket()
            false
        }
    }

    /** The number of the last ping the PC answered (what `PingTest` waits on before its next tick). */
    @Volatile var lastPong: Long = 0
        private set

    private fun closeSocket() {
        try { sockRef?.close() } catch (e: Exception) { Log.w(TAG, "closing the app lane: ${e.message}") }
    }

    private fun setState(s: String) {
        stateLine = s
    }

    private fun <T> say(f: () -> T): T? = try {
        f()
    } catch (e: Exception) {
        Log.e(TAG, "an app lane listener failed", e)
        null
    }

    private fun text(f: JsonObject, k: String): String? = (f[k] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val TAG = "app-client"
    }
}
