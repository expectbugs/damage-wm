package wm.damage.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wm.damage.core.geom.LintError
import wm.damage.core.wire.CfwModes
import wm.damage.core.wire.CfwModes.BindParam

/**
 * Build C3 (spec DP1, `FIRMWARE.md` §7.7–§7.8): the phone's encoders of mode 26 (PLAY, RETARGET, CLOSE, PLAY-AFTER) and
 * mode 27 (SET with kind 3, LIST, LIST-RING) against every such message `make_vectors.py` wrote into the `v5-*` vectors,
 * batches unwrapped: decoded by this file's reader and encoded again, byte for byte. A message the encoder's own lint
 * refuses (a vector's deliberate refusal: a bad slot, period, kind, length) is counted, not compared.
 */
class C3WireTest {

    private fun vectors(): List<Path> {
        var d: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (d != null && !Files.isDirectory(d.resolve("firmware/vectors"))) d = d.parent
        val dir = d?.resolve("firmware/vectors") ?: fail("firmware/vectors not found")
        return Files.list(dir).use { s -> s.filter { it.fileName.toString().let { n -> n.startsWith("v5-") && n.endsWith(".json") } }.sorted().toList() }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    private fun params(b: ByteArray, at: Int) = IntArray(4) { s16(b, at + 2 * it) }

    /** The message's own encoding by `CfwModes`, or null when this reader has no encoder for its (mode, sub, length). */
    private fun encode(m: ByteArray): ByteArray? {
        val mode = m[0].toInt() and 0xFF
        val sub = if (m.size > 1) m[1].toInt() and 0xFF else return null
        fun u8(i: Int) = m[i].toInt() and 0xFF
        return when {
            mode == 26 && sub == 0 && m.size == 18 -> CfwModes.motionPlay(u8(2), u8(3), u16(m, 4), u16(m, 6), u16(m, 8), params(m, 10))
            mode == 26 && sub == 1 && m.size == 11 -> CfwModes.motionRetarget(u8(2), params(m, 3))
            mode == 26 && sub == 2 && m.size == 3 -> CfwModes.motionClose(u8(2))
            mode == 26 && sub == 8 && m.size == 17 -> CfwModes.motionPlayAfter(u8(2), u8(3), u16(m, 4), u16(m, 6), u8(8), params(m, 9))
            mode == 27 && sub == 0 && m.size == 23 -> CfwModes.bindSet(u8(2), u8(3), u8(4), u8(5), m[6].toInt(), u8(7), u8(8), u16(m, 9),
                List(4) { BindParam(u8(11 + 3 * it), s16(m, 12 + 3 * it)) }, effects = true)
            mode == 27 && sub == 2 && m.size == 12 -> CfwModes.bindList(u8(2), u16(m, 3), u16(m, 5), s16(m, 7), s16(m, 9), u8(11))
            mode == 27 && sub == 4 && m.size == 13 && u8(12) <= 1 -> CfwModes.bindListRing(u8(2), u16(m, 3), u16(m, 5), s16(m, 7), s16(m, 9), u8(11), u8(12) == 1)
            else -> null
        }
    }

    /** The top-level message and, for a batch, each sub-message. */
    private fun messages(m: ByteArray): List<ByteArray> {
        if (m.isEmpty() || (m[0].toInt() and 0x7F) != 8 || m.size < 2) return listOf(m)
        val out = ArrayList<ByteArray>()
        var i = 2
        repeat(m[1].toInt() and 0xFF) {
            if (i + 2 > m.size) return out
            val n = u16(m, i)
            if (i + 2 + n > m.size) return out
            out += m.copyOfRange(i + 2, i + 2 + n)
            i += 2 + n
        }
        return out
    }

    @Test
    fun theEncodersGiveEveryV5MessageBackByteForByte() {
        val compared = HashMap<String, Int>()
        var refused = 0
        val problems = ArrayList<String>()
        for (f in vectors()) {
            val vec = Json.parseToJsonElement(Files.readString(f)).jsonObject
            val name = vec["name"]!!.jsonPrimitive.content
            for ((i, st) in vec["steps"]!!.jsonArray.withIndex()) for (op in st.jsonObject["ops"]!!.jsonArray) {
                val h = op.jsonObject["msg"]?.jsonPrimitive?.content ?: continue
                for (m in messages(hex(h))) {
                    val want = m.joinToString("") { "%02x".format(it) }
                    val got = try { encode(m) } catch (e: LintError) { refused++; continue } ?: continue
                    val key = "${m[0].toInt() and 0xFF}/${m[1].toInt() and 0xFF}"
                    compared[key] = (compared[key] ?: 0) + 1
                    val g = got.joinToString("") { "%02x".format(it) }
                    if (g != want) problems += "$name step $i $key: the vector has $want, CfwModes gives $g"
                }
            }
        }
        println("C3WireTest: compared ${compared.toSortedMap()}, refused by the encoders' lint $refused")
        assertEquals(emptyList(), problems)
        for (k in listOf("26/0", "26/2", "26/8", "27/0", "27/2", "27/4")) assertTrue((compared[k] ?: 0) > 0, "no $k message compared: $compared")
    }
}
