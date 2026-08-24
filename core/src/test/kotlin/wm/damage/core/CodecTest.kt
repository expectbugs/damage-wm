package wm.damage.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import wm.damage.core.geom.LintError
import wm.damage.core.geom.Rect
import wm.damage.core.gfx.Gray8
import wm.damage.core.gfx.Pack
import wm.damage.core.gfx.Rle
import wm.damage.core.gfx.Zl
import wm.damage.core.wire.Crc16

private fun hex(s: String): ByteArray {
    val t = s.replace(" ", "")
    return ByteArray(t.length / 2) { ((t[it * 2].digitToInt(16) shl 4) or t[it * 2 + 1].digitToInt(16)).toByte() }
}

class CodecTest {
    /** Vectors generated from research/fbfeas.py rle_nibble — the encoder that
     *  was verified by round-tripping 301 cases through a port of the firmware's
     *  own decoder. Kotlin must match it byte for byte. */
    @Test
    fun rleMatchesPythonReference() {
        val vectors = listOf(
            "00000000000000000000" to "0014",
            "123456" to "111213141516",
            "aaaaaaaaaaaaaaaa" to "0a10",
            ("390c8c7d7247342cd8100f2f6f770d65d670e58e0351d8ae8e4f6eac342fc231" +
                "b7b08716eb3fc12896b96223177494287733c28ee8ba53bdb56b8824577d53ec") to
                ("1319101c181c171d171214171314121c1d1811201f121f161f27101d16151d16" +
                    "17101e15181e101315111d181a1e181e141f161e1a1c1314121f1c1213111b17" +
                    "1b10181711161e1b131f1c11121819161b191622131127141914121827231c12" +
                    "182e181b1a15131b1d1b15161b28121415271d15131e1c"),
        )
        for ((inp, out) in vectors) {
            assertContentEquals(hex(out), Rle.encode(hex(inp)), "RLE of $inp")
        }
        // 600 F-nibbles -> 16-bit escape [0f][00][5802 LE]
        assertContentEquals(hex("0f005802"), Rle.encode(ByteArray(300) { 0xFF.toByte() }))
        // 65536 nibbles of colour 1 -> a 65535 token plus a 1 token
        assertContentEquals(hex("0100ffff11"), Rle.encode(ByteArray(32768) { 0x11 }))
    }

    @Test
    fun rleRoundTrips() {
        val rnd = Random(7)
        repeat(50) {
            val n = 1 + rnd.nextInt(4000)
            val buf = ByteArray(n) { rnd.nextInt(256).toByte() }
            val decoded = Rle.decode(Rle.encode(buf), n * 2)
            assertContentEquals(buf, decoded)
        }
        // runny content (the realistic case)
        repeat(20) {
            val n = 1 + rnd.nextInt(4000)
            val buf = ByteArray(n)
            var i = 0
            while (i < n) {
                val run = 1 + rnd.nextInt(200)
                val v = rnd.nextInt(16)
                val b = ((v shl 4) or v).toByte()
                for (j in i until minOf(n, i + run)) buf[j] = b
                i += run
            }
            assertContentEquals(buf, Rle.decode(Rle.encode(buf), n * 2))
        }
    }

    @Test
    fun rleRejectsWrongLength() {
        val enc = Rle.encode(ByteArray(10) { 0x22 })
        assertFailsWith<LintError> { Rle.decode(enc, 19) }
        assertFailsWith<LintError> { Rle.decode(enc, 21) }
    }

    @Test
    fun zlibChainRoundTrips() {
        val rnd = Random(3)
        val buf = ByteArray(5000) { (rnd.nextInt(3) * 0x11).toByte() }
        val z = Zl.encodeCfw(buf)
        assertContentEquals(buf, Zl.decodeCfw(z, buf.size * 2))
    }

    @Test
    fun packRectIsWireOrder() {
        val s = Gray8(8, 2)
        // row 0: levels 1..8 at 8-bit (n*17); row 1: zeros
        for (x in 0 until 8) s[x, 0] = (x + 1) * 17
        val packed = Pack.rect(s, Rect(0, 0, 8, 2))
        assertContentEquals(hex("12345678" + "00000000"), packed)
        // odd width pads the final nibble with 0
        val odd = Pack.rect(s, Rect(0, 0, 7, 1))
        assertContentEquals(hex("12345670"), odd)
    }

    @Test
    fun packUnpackRoundTrips() {
        val rnd = Random(11)
        val s = Gray8(64, 32)
        for (i in s.pix.indices) s.pix[i] = (rnd.nextInt(16) * 17).toByte()
        val packed = Pack.rect(s, Rect(0, 0, 64, 32))
        val back = Pack.unpack(packed, 64, 32)
        assertContentEquals(s.pix, back.pix)
    }

    /** The documented CRC vector: keepalive payload 080c104f7200 -> 0xCC79
     *  (G2CC docs/G2_BLE_PROTOCOL.md §2). */
    @Test
    fun crcVector() {
        assertEquals(0xCC79, Crc16.compute(hex("080c104f7200")))
    }
}
