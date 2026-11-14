package wm.damage.core

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import wm.damage.core.net.Reach
import wm.damage.core.net.Side

/**
 * Build W, package A (T24; dec. B.4; the spec's §2(c)): every row of the reach table — which arriving address a
 * listener answers, on the PC and on the phone. Addresses are documentation, private and made-up examples; none is
 * an address of Adam's networks.
 */
class ReachTableTest {

    /** One row: the arriving address, whether its interface is point-to-point (null = unreadable), and the
     *  verdicts on the PC and the phone. */
    private class Row(val label: String, val addr: ByteArray, val p2p: Boolean?, val pc: Boolean, val phone: Boolean,
        val why: String? = null)

    private fun ip(s: String): ByteArray = InetAddress.getByName(s).address

    /** `::ffff:a.b.c.d` built by hand: `InetAddress` turns a mapped literal into an IPv4 address itself, which
     *  would hide the unwrap this table pins. */
    private fun mapped(a: Int, b: Int, c: Int, d: Int): ByteArray =
        ByteArray(10) + byteArrayOf(-1, -1, a.toByte(), b.toByte(), c.toByte(), d.toByte())

    private val rows = buildList {
        // loopback, unmapped first
        for (p2p in listOf(null, true, false)) {
            add(Row("127.0.0.1", ip("127.0.0.1"), p2p, pc = true, phone = true))
            add(Row("127.5.5.5", ip("127.5.5.5"), p2p, pc = true, phone = true))
            add(Row("::1", ip("::1"), p2p, pc = true, phone = true))
            add(Row("::ffff:127.0.0.1", mapped(127, 0, 0, 1), p2p, pc = true, phone = true))
        }
        // the tailnet's ranges, any interface
        for (p2p in listOf(null, true, false)) {
            add(Row("100.101.102.103", ip("100.101.102.103"), p2p, pc = true, phone = true))
            add(Row("::ffff:100.101.102.103", mapped(100, 101, 102, 103), p2p, pc = true, phone = true))
            add(Row("fd7a:115c:a1e0::1234", ip("fd7a:115c:a1e0::1234"), p2p, pc = true, phone = true))
            add(Row("100.64.0.1 (the phone's cellular)", ip("100.64.0.1"), p2p, pc = true, phone = true))
        }
        // just outside 100.64.0.0/10
        add(Row("100.63.255.255", ip("100.63.255.255"), false, pc = false, phone = false))
        add(Row("100.128.0.1", ip("100.128.0.1"), false, pc = false, phone = false))
        // the bus network: a private address on an interface that is not point-to-point
        add(Row("192.168.1.20", ip("192.168.1.20"), false, pc = true, phone = false))
        add(Row("::ffff:192.168.1.20", mapped(192, 168, 1, 20), false, pc = true, phone = false))
        add(Row("172.17.0.1 (a container bridge)", ip("172.17.0.1"), false, pc = true, phone = false))
        add(Row("169.254.10.10", ip("169.254.10.10"), false, pc = true, phone = false))
        add(Row("fe80::1", ip("fe80::1"), false, pc = true, phone = false))
        add(Row("fd12:3456::1 (unique-local)", ip("fd12:3456::1"), false, pc = true, phone = false))
        // a tunnel carrying a private address
        add(Row("10.8.0.2 on a tunnel", ip("10.8.0.2"), true, pc = false, phone = false, why = "tunnel"))
        add(Row("::ffff:10.8.0.2 on a tunnel", mapped(10, 8, 0, 2), true, pc = false, phone = false, why = "tunnel"))
        add(Row("fe80::1 on a tunnel", ip("fe80::1"), true, pc = false, phone = false, why = "tunnel"))
        add(Row("fd12:3456::1 on a tunnel", ip("fd12:3456::1"), true, pc = false, phone = false, why = "tunnel"))
        // public and any-local, whatever the interface
        for (p2p in listOf(null, true, false)) {
            add(Row("203.0.113.5", ip("203.0.113.5"), p2p, pc = false, phone = false))
            add(Row("2001:db8::1", ip("2001:db8::1"), p2p, pc = false, phone = false))
            add(Row("0.0.0.0", ip("0.0.0.0"), p2p, pc = false, phone = false))
            add(Row("::", ip("::"), p2p, pc = false, phone = false))
        }
        // a private address whose interface could not be read
        add(Row("192.168.1.20, interface unreadable", ip("192.168.1.20"), null, pc = false, phone = false,
            why = "unreadable"))
    }

    @Test
    fun everyRow() {
        val wrong = ArrayList<String>()
        for (r in rows) {
            for ((side, want) in listOf(Side.PC to r.pc, Side.PHONE to r.phone)) {
                val v = Reach.classify(r.addr, r.p2p, side)
                if (v.allowed != want) wrong += "${r.label} p2p=${r.p2p} on the $side: allowed=${v.allowed} (${v.cls}: ${v.why})"
                if (side == Side.PC && r.why != null && v.cls != r.why) wrong += "${r.label}: class ${v.cls}, wanted ${r.why}"
                if (v.why.isBlank()) wrong += "${r.label}: no reason given"
            }
        }
        assertEquals(emptyList(), wrong)
        assertEquals("interface unreadable", Reach.classify(ip("192.168.1.20"), null, Side.PC).why)
    }

    @Test
    fun theInetAddressFormIsTheSame() {
        for (s in listOf("127.0.0.1", "100.101.102.103", "192.168.1.20", "203.0.113.5", "fe80::1")) {
            val a = InetAddress.getByName(s)
            for (side in Side.entries) for (p2p in listOf(null, true, false)) {
                assertEquals(Reach.classify(a.address, p2p, side), Reach.classify(a, p2p, side))
            }
        }
        assertFalse(Reach.classify(ByteArray(5), false, Side.PC).allowed, "five bytes are not an address")
    }
}
