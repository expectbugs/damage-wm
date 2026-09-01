package wm.damage.core.wire

/**
 * CRC-16/CCITT-FALSE and minimal protobuf wire primitives.
 *
 * Adapted from G2CC (Adam's own code, licence his): ble/Crc16.kt, ble/Varint.kt
 * and the field helpers in ble/EvenHub.kt. CRC: init 0xFFFF, poly 0x1021, over
 * PAYLOAD bytes only, stored little-endian (G2CC docs/G2_BLE_PROTOCOL.md §2 —
 * verified vector: payload 080c104f7200 -> 0xCC79, on-wire 79 cc).
 */
object Crc16 {
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "CRC16: invalid range offset=$offset length=$length size=${data.size}"
        }
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}

/** Protobuf wire helpers — wire type 0 (varint) and 2 (length-delimited) are all
 *  this protocol uses. Encoding side builds messages; decoding side walks them. */
object Pb {
    fun varint(value: Long): ByteArray {
        require(value >= 0) { "varint: only non-negative supported, got $value" }
        if (value == 0L) return byteArrayOf(0)
        val out = ArrayList<Byte>(10)
        var v = value
        while (v > 0x7F) {
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        out.add((v and 0x7F).toByte())
        return out.toByteArray()
    }

    fun key(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())
    fun v(field: Int, value: Int): ByteArray = key(field, 0) + varint(value.toLong())
    fun v(field: Int, value: Long): ByteArray = key(field, 0) + varint(value)
    fun l(field: Int, body: ByteArray): ByteArray = key(field, 2) + varint(body.size.toLong()) + body
    fun s(field: Int, str: String): ByteArray = l(field, str.toByteArray(Charsets.UTF_8))

    fun cat(vararg parts: ByteArray): ByteArray = cat(parts.asList())

    fun cat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    // --- decoding -------------------------------------------------------------
    /** Decode a varint at [offset]; returns value and bytes consumed. */
    fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return value to (i - offset)
            shift += 7
            if (shift >= 64) throw IllegalArgumentException("varint: malformed (>9 bytes)")
        }
        throw IllegalArgumentException("varint: truncated at $offset (size=${data.size})")
    }

    /** Walk top-level fields of a message. Unknown wire types abort loudly. */
    fun fields(data: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        while (i < data.size) {
            val (k, kn) = readVarint(data, i)
            i += kn
            val field = (k shr 3).toInt()
            when ((k and 7L).toInt()) {
                0 -> {
                    val (v, vn) = readVarint(data, i)
                    i += vn
                    out += Field(field, v, null, 0)
                }
                2 -> {
                    val (len, ln) = readVarint(data, i)
                    i += ln
                    if (i + len > data.size)
                        throw IllegalArgumentException("field $field length $len overruns buffer")
                    out += Field(field, null, data.copyOfRange(i, i + len.toInt()), 2)
                    i += len.toInt()
                }
                5 -> { // fixed32 — appears in vendor schemas; skip with the value kept
                    if (i + 4 > data.size) throw IllegalArgumentException("fixed32 truncated")
                    var v = 0L
                    for (b in 0 until 4) v = v or ((data[i + b].toLong() and 0xFF) shl (8 * b))
                    i += 4
                    out += Field(field, v, null, 5)
                }
                1 -> { // fixed64
                    if (i + 8 > data.size) throw IllegalArgumentException("fixed64 truncated")
                    var v = 0L
                    for (b in 0 until 8) v = v or ((data[i + b].toLong() and 0xFF) shl (8 * b))
                    i += 8
                    out += Field(field, v, null, 1)
                }
                else -> throw IllegalArgumentException("unsupported wire type ${(k and 7L)} for field $field")
            }
        }
        return out
    }

    fun varintField(data: ByteArray, field: Int): Long? =
        fields(data).firstOrNull { it.field == field && it.varint != null }?.varint

    fun bytesField(data: ByteArray, field: Int): ByteArray? =
        fields(data).firstOrNull { it.field == field && it.bytes != null }?.bytes

    /** [wireType] distinguishes fixed32/fixed64 (5/1) from true varints (0) —
     *  both land in [varint]; re-encoders must not conflate them (L8). */
    data class Field(val field: Int, val varint: Long?, val bytes: ByteArray?, val wireType: Int = 0)
}
