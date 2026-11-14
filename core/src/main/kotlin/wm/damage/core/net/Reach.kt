package wm.damage.core.net

import java.net.InetAddress

/** Which device a listener runs on: the PC admits the bus network, the phone does not (dec. B.4). */
enum class Side { PC, PHONE }

/**
 * Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on
 * his own network. This decides whether a connection to one of Damage's listeners may be answered,
 * by the LOCAL address it arrived on (build W; the lead's decision B.4; `REPLAN.md` §15 A1;
 * `CONTRACT.md` §10.9). Pure, so a table test pins every row (`ReachTableTest`); the gate on the
 * accepted socket that calls it is `ReachGate`.
 *
 * - loopback (127/8, `::1`) → yes, both sides;
 * - the tailnet's ranges (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`) → yes, both sides, any interface;
 * - on the PC only, a site-local or link-local address (10/8, 172.16/12, 192.168/16, 169.254/16,
 *   `fe80::/10`, `fec0::/10` and the unique-local `fc00::/7` — Java's `isSiteLocalAddress` covers
 *   only `fec0::/10`, so the bytes are read here) on an interface that is NOT point-to-point → yes
 *   (the bus network). The same address on a point-to-point interface (a tunnel) → no; an
 *   interface that could not be read ([pointToPoint] null) → no, said "interface unreadable";
 * - anything else (public addresses, the any-local address, the phone's private ones) → no.
 *
 * An IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) is judged as its IPv4 address: a dual-stack
 * socket may report one either way.
 *
 * Two known gaps, recorded as ruled (dec. E.2; the shared token still guards every listener, and
 * Adam's word is that nobody else is on his networks):
 * 1. Linux accepts a packet addressed to the LAN address even when it arrives through the tunnel
 *    (the weak-host model), so a peer behind the VPN could reach a listener as "bus network" if
 *    the VPN routed to it (inferred; unlikely). This judges the address and its interface only.
 * 2. On the phone a mobile carrier's shared address space is the same `100.64.0.0/10` the tailnet
 *    uses; the range cannot tell them apart.
 */
object Reach {

    data class Verdict(val allowed: Boolean, val cls: String, val why: String)

    fun classify(addr: InetAddress, pointToPoint: Boolean?, side: Side): Verdict =
        classify(addr.address, pointToPoint, side)

    fun classify(addr: ByteArray, pointToPoint: Boolean?, side: Side): Verdict {
        val a = unmapped(addr)
        if (a.size != 4 && a.size != 16) return no("invalid", "not an IP address (${addr.size} bytes)")
        if (loopback(a)) return yes("loopback", "loopback")
        if (tailnet(a)) return yes("tailnet", "the tailnet's range")
        if (a.all { it == 0.toByte() }) return no("any-local", "the any-local address")
        if (!siteLocal(a) && !linkLocal(a)) return no("public", "not loopback, the tailnet or a private address")
        if (side == Side.PHONE) return no("private", "the phone answers on loopback and the tailnet only")
        if (pointToPoint == null) return no("unreadable", "interface unreadable")
        if (pointToPoint) return no("tunnel", "a private address on a point-to-point interface (a tunnel)")
        return yes("bus", "a private address on a local network (the bus network)")
    }

    private fun yes(cls: String, why: String) = Verdict(true, cls, why)
    private fun no(cls: String, why: String) = Verdict(false, cls, why)

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    /** `::ffff:a.b.c.d` → `a.b.c.d`; anything else as given. */
    private fun unmapped(a: ByteArray): ByteArray {
        if (a.size != 16) return a
        for (i in 0 until 10) if (a[i] != 0.toByte()) return a
        if (u(a[10]) != 0xFF || u(a[11]) != 0xFF) return a
        return a.copyOfRange(12, 16)
    }

    private fun loopback(a: ByteArray): Boolean =
        if (a.size == 4) u(a[0]) == 127
        else (0 until 15).all { a[it] == 0.toByte() } && u(a[15]) == 1

    private fun tailnet(a: ByteArray): Boolean =
        if (a.size == 4) u(a[0]) == 100 && (u(a[1]) and 0xC0) == 0x40          // 100.64.0.0/10
        else u(a[0]) == 0xFD && u(a[1]) == 0x7A && u(a[2]) == 0x11 &&          // fd7a:115c:a1e0::/48
            u(a[3]) == 0x5C && u(a[4]) == 0xA1 && u(a[5]) == 0xE0

    private fun siteLocal(a: ByteArray): Boolean =
        if (a.size == 4) u(a[0]) == 10 ||
            (u(a[0]) == 172 && (u(a[1]) and 0xF0) == 16) ||
            (u(a[0]) == 192 && u(a[1]) == 168)
        else (u(a[0]) and 0xFE) == 0xFC ||                                       // fc00::/7, unique-local
            (u(a[0]) == 0xFE && (u(a[1]) and 0xC0) == 0xC0)                      // fec0::/10, the old site-local

    private fun linkLocal(a: ByteArray): Boolean =
        if (a.size == 4) u(a[0]) == 169 && u(a[1]) == 254
        else u(a[0]) == 0xFE && (u(a[1]) and 0xC0) == 0x80                       // fe80::/10
}
