package wm.damage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import wm.damage.core.wire.Pb

/**
 * The protobuf field reader's bounds (0.64 R2, the review's RG-2): every parser of a frame from the glasses
 * catches [IllegalArgumentException] and reads it as "not protobuf", so a length the reader cannot honour must
 * be refused as exactly that — never as another exception class, which leaves every parser and reaches the
 * router (and on the phone the BLE library, which writes it to logcat alone).
 */
class ProtoReaderTest {

    private fun unhex(h: String) = ByteArray(h.length / 2) { i -> h.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    /** A ten-byte length varint with bit 63 set reads as a negative Long; `i + len > size` is false for it. */
    @Test
    fun aLengthPastTheLongRangeIsRefusedAsNotProtobuf() {
        val e = assertFailsWith<IllegalArgumentException>("field 3, length Long.MIN_VALUE + 1000") {
            Pb.fields(unhex("1ae8878080808080808001"))
        }
        assertEquals(true, e.message?.contains("field 3"), "the message names the field: ${e.message}")
        assertFailsWith<IllegalArgumentException>("the same with a byte after it") { Pb.fields(unhex("1ae887808080808080800108")) }
    }

    /** The bounds around the end: a length that ends at the last byte is read; one past it, or one that
     *  `toInt()` would fold back into range, is refused. */
    @Test
    fun aLengthIsReadUpToTheEndAndRefusedPastIt() {
        val f = Pb.fields(unhex("0a020801"))
        assertEquals(1, f.size)
        assertEquals("0801", f[0].bytes!!.joinToString("") { "%02x".format(it) })
        assertEquals(0, Pb.fields(unhex("0a00"))[0].bytes!!.size, "an empty field at the end")
        assertFailsWith<IllegalArgumentException>("one byte past the end") { Pb.fields(unhex("0a0208")) }
        assertFailsWith<IllegalArgumentException>("2^32 + 1: toInt() would read 1") { Pb.fields(unhex("0a818080801008")) }
    }
}
