package wm.damage.core.bastion

import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import wm.damage.core.net.Reach
import wm.damage.core.net.Side
import wm.damage.core.util.Exec
import wm.damage.core.util.Log

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. This is the built-in value `damage.phone` (build W, package E; the lead's
 * decision F.3): whether his phone is at the bus — `home`, `away` or `unknown` — usable only in a
 * rule's `when` (a check that runs "while I'm away from the bus").
 *
 * Once a minute ([PERIOD_MS]) the PC reads its own Tailscale daemon's status (`tailscale status
 * --json`: a local read over the daemon's own socket; no request leaves the PC) and finds the peer
 * whose tailnet address is the configured phone's (`phoneHost`; a MagicDNS short name is matched to
 * the peer's DNS name). The connection path decides ([read]; ruling R3.1 of 2026-11-13 replaces dec.
 * F.3's home rule):
 * - `home`: the peer is online and active and its current path is DIRECT to (a) an address inside the
 *   network of one of the PC's interfaces that is up, not loopback and not point-to-point ("direct,
 *   on the network of <iface>"), or (b) any other private address — IPv4 10/8, 172.16/12, 192.168/16;
 *   IPv6 unique-local fc00::/7 or link-local fe80::/10 — that is NOT inside the network of one of the
 *   PC's point-to-point interfaces ("direct, to a private address routed from this PC"). (a) is
 *   decided first, so an address inside both kinds of network (every interface's fe80::/64) is (a);
 * - `away`: the peer is online and active and its path is relayed or direct to a public address, and
 *   has been for [AWAY_HOLD_MS] (15 minutes);
 * - `unknown` otherwise: the command missing or failing, the daemon not running, the peer absent,
 *   offline or idle (an idle peer has no current path: the CLI itself names a path only for an active
 *   one), a private address inside a point-to-point interface's network (a tunnel with a private range
 *   is not the bus), an IPv4 link-local or old IPv6 site-local address on no network of the PC's (not
 *   in R3.1's list), an address in the tailnet's own ranges (100.64/10 is not private here), a path
 *   that just changed, or a read still running. `unknown` never arms a guard (it is no reading,
 *   [RuleEval]).
 *
 * Measured (R3, 2026-11-13, grade M): at the bus the phone's direct path alternates, minute to minute,
 * between a private address on the PC's own network and one on a second private network that routes to
 * the PC (two networks at the bus, one behind the other); under dec. F.3 the second read `unknown`, so
 * (b) counts it. That (b) opens no false `home` is REASONED, not measured: a direct path needs the PC's
 * packets to reach the phone's own address with no address translation toward the phone, and the PC
 * never leaves the bus, so a private address answers from the PC only on a network routed from the bus's
 * own router (the bus's networks, or the private network the bus is plugged into at a site); a phone on
 * some other private network cannot answer there, and another device at the same address cannot complete
 * the daemon's authenticated handshake. Every change of the value is one log line and one [say] line
 * (the journal's `presence` note on the service); a change of the path that does not change the value is
 * one log line.
 *
 * **Liveness as the hub's requests, no timeout:** the command runs on its own coroutine; a tick that
 * finds a read still running skips, says so, and counts as no reading (the value goes `unknown`);
 * never two reads at once; nothing waits on one.
 *
 * **Package F (W-decisions G.3) adds the phone's own report** ([phoneReport]: `home` / `away` / `unknown` and the
 * age of the fix behind it, carried by the phone's `table` asks on the bastion channel — never a position) and joins
 * it at [combineSignals]: `home` if either signal says home; `away` if the phone reports away, or, with no report,
 * the path has been far for [AWAY_HOLD_MS]; else `unknown`. [onBusNetwork] is package E's signal alone, told back to
 * the phone in every `table` answer so that it learns where the bus is (G.1).
 */
class PhonePresence(
    /** The configured phone: its tailnet address (the service pins `phoneHost` to it), or a MagicDNS short name. */
    private val phone: String,
    /** Runs the status read and returns its standard output; throws when it cannot run or fails ([runTailscale]
     *  on the PC; a stand-in returning fixture JSON in tests — the real command is never run by a test). */
    private val status: () -> String,
    /** The PC's networks ([interfaceNets]): those of its up, non-loopback interfaces, a point-to-point one marked
     *  (R3.1: the networks that can be the bus's, and the tunnels' networks that are excluded from it). */
    private val localNets: () -> List<LocalNet>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** One line per change of the value (the service writes it to the journal; host-only has no journal). */
    private val say: (String) -> Unit = {},
) {
    /** One network of the PC: an interface that is up and not loopback, an address on it and its prefix;
     *  [pointToPoint] marks a tunnel's network, which is never the bus (R3.1's exclusion list). */
    class LocalNet(val iface: String, val addr: ByteArray, val prefix: Int, val pointToPoint: Boolean = false)

    enum class PathKind { HOME, FAR, NONE }

    /** One read of the connection path: its kind, words for the state line, and the detail for the log and the
     *  journal's change line. Neither carries an address or a port (fix round 2, R2.3: away, a direct path is the
     *  public address of the network he is on — a coarse position by lookup; at home, the phone's address on the bus
     *  network): every read of a path address says its `why` words only. */
    class PathRead(val kind: PathKind, val why: String, val detail: String = why)

    /** The connection path's signal: the kind read and when this run of it began. */
    class PathSignal(val kind: PathKind, val since: Long, val why: String)

    /** The value, which signal decided it, and why. */
    data class Value(val word: String, val by: String, val why: String)

    /** The phone's last report (package F): its word, the age of the fix behind it as the phone gave it, and when
     *  this PC received it ([at], the PC's clock — what [REPORT_FRESH_MS] is measured on). */
    class PhoneReport(val word: String, val fixAgeMs: Long?, val at: Long)

    private val lock = Any()
    // ---- under [lock] ----
    private var path = PathSignal(PathKind.NONE, clock(), "not read yet")
    private var report: PhoneReport? = null
    private var value = Value(UNKNOWN, BY_PATH, "not read yet")
    private var valueSince = clock()
    private var lastDetail = ""
    // ----
    private val running = AtomicBoolean(false)
    @Volatile private var runningSince = 0L
    private var loop: Job? = null

    /** `home`, `away` or `unknown`: what a guard on `damage.phone` compares. */
    val word: String get() = synchronized(lock) { value.word }

    /** Whether a status read is running now (its coroutine has not finished). */
    val busy: Boolean get() = running.get()

    /** Starts the minute's read (pacing, not a timeout). */
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e(TAG, "a presence tick failed; the next minute's runs as usual", e)
                }
                delay(PERIOD_MS)
            }
        }
    }

    /** One minute's read: skipped and said while the last is still running; else one read on its own coroutine.
     *  Called by the loop; tests call it directly with an injected clock and a stand-in command. */
    fun tick() {
        val now = clock()
        if (!running.compareAndSet(false, true)) {
            val secs = (now - runningSince) / 1000
            Log.w(TAG, "tailscale status still running after $secs s - this minute's read is skipped " +
                "(no second read starts; nothing waits on it)")
            observe(PathRead(PathKind.NONE, "a status read is still running"), now)
            return
        }
        runningSince = now
        scope.launch(Dispatchers.IO) {
            try {
                val read = try {
                    read(status(), phone, localNets())
                } catch (e: Exception) {
                    PathRead(PathKind.NONE, "the status read failed (${e::class.simpleName})",
                        "the status read failed (${e::class.simpleName}: ${e.message ?: "no detail"})")
                }
                observe(read, clock())
            } finally {
                running.set(false)
            }
        }
    }

    /** Applies one read at [now]: the path signal, then the value; a change of the value is one log line and one
     *  [say] line. */
    fun observe(read: PathRead, now: Long) {
        var line: String? = null
        synchronized(lock) {
            path = if (read.kind == path.kind) PathSignal(read.kind, path.since, read.why) else PathSignal(read.kind, now, read.why)
            line = decideLocked(now, read.detail)
            if (line == null && read.detail != lastDetail) Log.i(TAG, "phone path: ${read.detail} (damage.phone stays ${value.word})")
            lastDetail = read.detail
        }
        line?.let {
            Log.i(TAG, it)
            say(it)
        }
    }

    /**
     * The phone's own report (package F, W-decisions G.2/G.3), from its `table` ask at [now]: [word] is `home`,
     * `away` or `unknown` ([BastionService] refuses anything else before this), [fixAgeMs] the age of the fix behind
     * it. The value is decided again at once; a change of it is one log line and one [say] line, as for the path.
     */
    fun phoneReport(word: String, fixAgeMs: Long?, now: Long = clock()) {
        require(word in WORDS) { "a presence word must be home, away or unknown" }
        var line: String? = null
        synchronized(lock) {
            val prev = report
            report = PhoneReport(word, fixAgeMs, now)
            line = decideLocked(now, lastDetail)
            if (line == null && prev?.word != word) Log.i(TAG, "the phone reports $word (damage.phone stays ${value.word})")
        }
        line?.let {
            Log.i(TAG, it)
            say(it)
        }
    }

    /** Package E's signal alone, for the phone's learning (G.1): the last read found a direct path that reads `home`
     *  (R3.1: a private address on one of this PC's networks, or routed from it outside its tunnels' networks). Never
     *  the combined value: the phone's own report must not teach it. */
    fun onBusNetwork(): Boolean = synchronized(lock) { path.kind == PathKind.HOME }

    /** Under [lock]: decides the value at [now]; returns the change line, or null when the word stays. */
    private fun decideLocked(now: Long, pathDetail: String): String? {
        val next = combineSignals(path, report, now)
        var line: String? = null
        if (next.word != value.word) {
            val detail = if (next.by == BY_PATH) pathDetail.ifEmpty { path.why } +
                (if (next.word == AWAY) ", for ${(now - path.since) / 60_000} min" else "") else next.why
            line = "damage.phone ${value.word} -> ${next.word} (by ${next.by}: $detail)"
            valueSince = now
        }
        value = next
        return line
    }

    /** One part of the watcher's state line: the value, since when and why (no address). */
    fun stateLine(): String = synchronized(lock) {
        "phone: ${value.word} since ${hhmm(valueSince)} (${value.why})"
    }

    companion object {
        private const val TAG = "presence"
        const val HOME = "home"
        const val AWAY = "away"
        const val UNKNOWN = "unknown"
        const val BY_PATH = "the connection path"
        const val BY_PHONE = "the phone's position"
        /** The three words of `damage.phone`, and the only ones a phone's report may carry. */
        val WORDS = setOf(HOME, AWAY, UNKNOWN)
        /** One read a minute (dec. F.3). */
        const val PERIOD_MS = 60_000L
        /** A relayed or public path counts as away once it has lasted this long (dec. F.3). */
        const val AWAY_HOLD_MS = 15 * 60_000L
        /** A phone report older than this (the PC's clock) is no report: the phone asks every 15 s, so this is eight
         *  asks missed — the channel is down or the phone has stopped (a call of package F). The minute's read
         *  decides again, so a lapsed report stops counting within a minute of this. */
        const val REPORT_FRESH_MS = 2 * 60_000L

        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        private fun hhmm(ms: Long): String = HHMM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

        /**
         * THE place where the two signals of `damage.phone` join (W-decisions G.3): `home` if either the connection
         * path or the phone's report says home; `away` if the phone reports away, or — with no report — the path has
         * been relayed or public for [AWAY_HOLD_MS]; else `unknown`. [Value.by] names the signal that decided.
         * "No report" is none, one older than [REPORT_FRESH_MS] on this PC's clock (either way: a clock stepped
         * back does not keep one alive), or one that says `unknown` — the phone without the permission, without a
         * fresh fix or without a learned bus position leaves the decision to the path (a call of package F).
         */
        fun combineSignals(path: PathSignal, phone: PhoneReport?, now: Long): Value {
            val said = phone?.takeIf { it.word != UNKNOWN && abs(now - it.at) <= REPORT_FRESH_MS }
            return when {
                path.kind == PathKind.HOME -> Value(HOME, BY_PATH, path.why)
                said?.word == HOME -> Value(HOME, BY_PHONE, phoneWhy(said))
                said?.word == AWAY -> Value(AWAY, BY_PHONE, phoneWhy(said))
                path.kind == PathKind.FAR -> {
                    val min = (now - path.since) / 60_000
                    if (now - path.since >= AWAY_HOLD_MS) Value(AWAY, BY_PATH, "${path.why} for $min min")
                    else Value(UNKNOWN, BY_PATH, "${path.why} for $min of ${AWAY_HOLD_MS / 60_000} min")
                }
                else -> Value(UNKNOWN, BY_PATH, path.why)
            }
        }

        /** The path signal alone (package E's form; tests). */
        fun combineSignals(path: PathSignal, now: Long): Value = combineSignals(path, null, now)

        /** The phone's part of a value's words: its word and the fix's age, nothing of where. */
        private fun phoneWhy(r: PhoneReport): String =
            "the phone reports ${r.word}" + (r.fixAgeMs?.let { ", fix ${it / 60_000} min old" } ?: "")

        /** The PC's status read: `tailscale status --json` through [Exec] (stderr drained apart, no timeout). A
         *  missing command throws (IOException); a non-zero exit throws with the exit code and what it wrote. */
        fun runTailscale(): String {
            val r = Exec.run(listOf("tailscale", "status", "--json"))
            if (r.code != 0) throw IOException("tailscale status exited ${r.code}: ${r.stderr.trim().ifEmpty { "it wrote nothing" }}")
            return String(r.stdout, Charsets.UTF_8)
        }

        /** The PC's networks: every address of every interface that is up and not loopback, each marked with
         *  whether its interface is point-to-point. A non-point-to-point one can be the bus network; a
         *  point-to-point one (a tunnel with a private address is not the bus; dec. B.4's rule) is R3.1's
         *  exclusion list. Read from the JVM's interface list at every read; no config key. */
        fun interfaceNets(): List<LocalNet> {
            val out = ArrayList<LocalNet>()
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!ni.isUp || ni.isLoopback) continue
                val p2p = ni.isPointToPoint
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address?.address ?: continue
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix in 1..a.size * 8) out += LocalNet(ni.name, a, prefix, p2p)
                }
            }
            return out
        }

        /**
         * One status read (pure): the phone's peer and its path (the home rule: R3.1, the class KDoc). [json] is
         * the daemon's status; [phone] the configured phone; [nets] the PC's networks, the point-to-point ones
         * marked ([interfaceNets]). Field names as `tailscale status --json`
         * writes them (read on the PC, tailscale 1.104): `BackendState`; `Peer` → `{TailscaleIPs, DNSName, Online,
         * Active, CurAddr ("ip:port", "[v6]:port"), Relay (the DERP region), PeerRelay}`.
         */
        fun read(json: String, phone: String, nets: List<LocalNet>): PathRead {
            val root = try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return none("the status is not a JSON object")
            val backend = str(root, "BackendState")
            if (backend.isNotEmpty() && backend != "Running") return none("tailscale is $backend")
            val peers = root["Peer"] as? JsonObject ?: return none("the status lists no peers")
            val want = literal(phone)
            val peer = peers.values.mapNotNull { it as? JsonObject }.firstOrNull { p ->
                if (want != null) ips(p).any { it.contentEquals(want) }
                else str(p, "DNSName").substringBefore('.').equals(phone, ignoreCase = true)
            } ?: return none("the phone ($phone) is not among the tailnet's peers")
            if (!bool(peer, "Online")) return none("the phone's peer is offline")
            if (!bool(peer, "Active")) return none("no traffic with the phone lately, so no current path")
            val cur = str(peer, "CurAddr")
            if (cur.isNotEmpty()) {
                val a = hostOf(cur)?.let { literal(it) }
                    ?: return PathRead(PathKind.NONE, "the path address is not understood")
                val v = Reach.classify(a, false, Side.PC)
                return when (v.cls) {
                    "public" -> PathRead(PathKind.FAR, "direct to a public address")
                    "bus" -> {                                   // R3.1, in its order: the PC's own network first
                        val net = nets.firstOrNull { !it.pointToPoint && inNet(a, it) }
                        val tunnel = nets.firstOrNull { it.pointToPoint && inNet(a, it) }
                        when {
                            net != null -> PathRead(PathKind.HOME, "direct, on the network of ${net.iface}")
                            tunnel != null -> PathRead(PathKind.NONE,
                                "direct to a private address on the network of ${tunnel.iface}, a point-to-point interface: a tunnel, not the bus")
                            routedPrivate(a) -> PathRead(PathKind.HOME, "direct, to a private address routed from this PC")
                            else -> PathRead(PathKind.NONE, "direct to an IPv4 link-local or old IPv6 site-local address on no network of this PC")
                        }
                    }
                    else -> PathRead(PathKind.NONE, "the path address is ${v.cls}")
                }
            }
            val peerRelay = str(peer, "PeerRelay")
            if (peerRelay.isNotEmpty()) return PathRead(PathKind.FAR, "through a peer relay")      // the relay's address: not said (R2.3)
            val relay = str(peer, "Relay")
            if (relay.isNotEmpty()) return PathRead(PathKind.FAR, "relayed", "relayed through DERP $relay")
            return none("the phone's peer reports no path")
        }

        private fun none(why: String) = PathRead(PathKind.NONE, why)

        /** R3.1's private ranges, the only ones a path off the PC's networks may read `home` on: IPv4 10/8, 172.16/12,
         *  192.168/16; IPv6 unique-local fc00::/7 and link-local fe80::/10. Asked only of an address [Reach] put in its
         *  `bus` class, so the tailnet's own ranges (100.64/10, and its fd7a:115c:a1e0::/48 inside fc00::/7) never
         *  reach it; an IPv4 link-local (169.254/16) or old IPv6 site-local (fec0::/10) address, which that class
         *  also holds, is not in R3.1's list. An IPv4-mapped literal already arrives as four bytes ([literal]: the
         *  JDK returns it as IPv4). */
        private fun routedPrivate(a: ByteArray): Boolean {
            fun u(i: Int) = a[i].toInt() and 0xFF
            return if (a.size == 4) u(0) == 10 || (u(0) == 172 && (u(1) and 0xF0) == 16) || (u(0) == 192 && u(1) == 168)
            else a.size == 16 && ((u(0) and 0xFE) == 0xFC || (u(0) == 0xFE && (u(1) and 0xC0) == 0x80))
        }

        private fun str(o: JsonObject, key: String): String =
            (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

        private fun bool(o: JsonObject, key: String): Boolean = (o[key] as? JsonPrimitive)?.booleanOrNull == true

        private fun ips(p: JsonObject): List<ByteArray> =
            (p["TailscaleIPs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { j -> j.isString }?.content?.let(::literal) }

        /** `ip:port` → `ip`; `[v6]:port` → `v6`. */
        private fun hostOf(addrPort: String): String? = when {
            addrPort.startsWith("[") -> addrPort.substring(1).substringBefore(']', "").ifEmpty { null }
            ':' in addrPort -> addrPort.substringBeforeLast(':').ifEmpty { null }
            else -> null
        }

        private val V4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
        private val V6 = Regex("^[0-9A-Fa-f:.]+$")

        /** An IP literal's bytes, or null for anything else. Never a name lookup: IPv4 is parsed here, IPv6 is
         *  handed to the JDK in brackets, which it parses as a literal or refuses (no DNS for a bracketed host). */
        fun literal(s: String): ByteArray? {
            V4.matchEntire(s)?.let { m ->
                val b = m.groupValues.drop(1).map { it.toInt() }
                return if (b.all { it in 0..255 }) ByteArray(4) { b[it].toByte() } else null
            }
            val bare = s.substringBefore('%')
            if (':' !in bare || !V6.matches(bare)) return null
            return try {
                InetAddress.getByName("[$bare]").address
            } catch (e: Exception) {
                null
            }
        }

        private fun inNet(a: ByteArray, net: LocalNet): Boolean {
            if (a.size != net.addr.size || net.prefix !in 1..a.size * 8) return false
            var bits = net.prefix
            var i = 0
            while (bits > 0) {
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((a[i].toInt() and mask) != (net.addr[i].toInt() and mask)) return false
                bits -= 8
                i++
            }
            return true
        }
    }
}
