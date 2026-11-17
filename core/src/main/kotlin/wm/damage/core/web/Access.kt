package wm.damage.core.web

import java.net.InetAddress
import java.net.Socket
import wm.damage.core.replica.HttpRequest

/*
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
 * Build S1-1, package P6 (`CONTRACT.md` §10.5): the checks every request and WebSocket upgrade to the web interface
 * listener passes, in this order, after the reach gate has admitted the socket:
 *  1. `Host` names this PC: one of the configured names ([hosts]: `localhost` and the machine's own name by default,
 *     `webHosts` from his config — his names never live in the tree), or the very address the request arrived on, and
 *     the listener's own port. A page served from another name (a name made to resolve to this PC) is refused.
 *  2. `Origin`, when present, is the PC's own page origin: exactly `http://` and the `Host` just checked. Any page open
 *     in his browser could otherwise open a WebSocket here (CORS does not cover WebSockets).
 *  3. The device secret, from the `damage` cookie ([cookie]); the device table answers who it is and at what level,
 *     read ONCE per request ([Devices.authenticate]).
 * L1 A1: a pairing request from the PC's own browser ([ownBrowser]) is approved at once at level full.
 */
class Access(
    /** The names `Host` may carry, lower-cased, without a port. */
    hosts: Collection<String>,
    /** L1 A1: the PC's own browser needs no approval (the service's listener; the test rigs leave it off). */
    private val ownBrowserFull: Boolean = false,
) {
    private val names: Set<String> = hosts.map { it.trim().lowercase().removePrefix("[").removeSuffix("]") }.filter { it.isNotEmpty() }.toSet()

    /** Why [req] on [sock] (the listener's [port]) is refused, or null. Never names the header's own text. */
    fun refusal(req: HttpRequest, sock: Socket, port: Int): String? {
        val host = req.headers["host"] ?: return "no Host header"
        val (name, p) = split(host) ?: return "a Host header that does not parse"
        if ((p ?: 80) != port) return "a Host header naming another port"
        if (name !in names && !arrivedOn(name, sock.localAddress)) return "a Host header naming another site"
        val origin = req.headers["origin"] ?: return null
        if (!origin.equals("http://$host", ignoreCase = true)) return "an Origin that is not this PC's own page"
        return null
    }

    /** L1 A1: [req] is the PC's own browser — it arrived on the loopback interface ([local]), its `Host` is a loopback
     *  name and its `Origin` this listener's own loopback origin (a foreign page cannot present it; a local process
     *  is him). */
    fun ownBrowser(req: HttpRequest, local: InetAddress?, port: Int): Boolean {
        if (!ownBrowserFull || local?.isLoopbackAddress != true) return false
        val name = req.headers["host"]?.let { split(it) }?.first ?: return false
        val origin = req.headers["origin"] ?: return false
        return name in LOOPBACK_NAMES && LOOPBACK_NAMES.any { origin.equals("http://${if (':' in it) "[$it]" else it}:$port", true) }
    }

    companion object {
        /** L1 A1: the name and the approver the PC's own browser is recorded under. */
        const val OWN_BROWSER = "this PC's browser"
        /** The cookie that carries the device secret (C§10.3). */
        const val COOKIE = "damage"
        /** Chromium's cap is 400 days; the cookie is re-set on each visit (C§10.3). */
        const val COOKIE_MAX_AGE_S = 400L * 24 * 3600

        /** Always allowed: the loopback names. */
        val LOOPBACK_NAMES = listOf("localhost", "127.0.0.1", "::1")

        /** The `Set-Cookie` value for [secret]: HttpOnly and SameSite=Strict, no Secure (plain HTTP, C§10.3). */
        fun setCookie(secret: String): String =
            "$COOKIE=$secret; Path=/; Max-Age=$COOKIE_MAX_AGE_S; HttpOnly; SameSite=Strict"

        /** The `damage` cookie's value in [req], or null. */
        fun cookie(req: HttpRequest): String? {
            val h = req.headers["cookie"] ?: return null
            for (part in h.split(';')) {
                val i = part.indexOf('=')
                if (i > 0 && part.substring(0, i).trim() == COOKIE) return part.substring(i + 1).trim().takeIf { it.isNotEmpty() }
            }
            return null
        }

        /** `name[:port]` or `[v6][:port]`, lower-cased name; null when it does not parse. */
        fun split(host: String): Pair<String, Int?>? {
            val h = host.trim().lowercase()
            if (h.isEmpty()) return null
            if (h.startsWith("[")) {
                val end = h.indexOf(']')
                if (end < 0) return null
                val rest = h.substring(end + 1)
                val port = if (rest.isEmpty()) null else rest.removePrefix(":").toIntOrNull() ?: return null
                return h.substring(1, end) to port
            }
            val i = h.lastIndexOf(':')
            if (i < 0) return h to null
            if (h.indexOf(':') != i) return null              // a bare IPv6 literal is not a Host
            val port = h.substring(i + 1).toIntOrNull() ?: return null
            return h.substring(0, i).takeIf { it.isNotEmpty() }?.let { it to port }
        }

        /** True when [name] is an address literal equal to [local], the address the request arrived on. */
        private fun arrivedOn(name: String, local: InetAddress?): Boolean {
            if (local == null) return false
            val lit = wm.damage.core.bastion.PhonePresence.literal(name) ?: return false
            return lit.contentEquals(local.address)
        }
    }
}
