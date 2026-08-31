package wm.damage.core.wire

import java.io.ByteArrayOutputStream

/**
 * The AA transport envelope — G2CC docs/G2_BLE_PROTOCOL.md §2 (capture-derived,
 * confirmed against g2-kit's CODE and Even's schemas; their prose docs are wrong
 * and unused here):
 *
 *   [AA][type][seq][len][pktTot][pktSer][sidHi][sidLo][payload...][crcLo][crcHi]
 *
 *   type 0x21 command (host->glasses), 0x12 response. len = chunk length, +2 on
 *   the final packet only, whose two trailing bytes are ONE CRC-16/CCITT-FALSE
 *   over the ENTIRE reassembled payload, little-endian. Non-final packets carry
 *   no CRC. ~232 B payload per fragment (measured; g2-kit code agrees).
 *
 * Adapted from G2CC ble/EvenHub.kt frame()/buildRawPacket() and
 * ble/FrameReassembler.kt — Adam's own code.
 */
object AaFrame {
    const val MAGIC = 0xAA
    const val TYPE_COMMAND = 0x21
    const val TYPE_RESPONSE = 0x12
    const val HEADER = 8
    const val CRC = 2

    /** Proven fragment payload size (G2_BLE_PROTOCOL.md §2.1; faceclaw uses the
     *  same 232 — protocol fact). */
    const val CHUNK = 232

    /** Frame one payload into 1..n AA packets sharing [seq]. CRC over the whole
     *  payload rides only the final packet. */
    fun frame(seq: Int, sid: Int, flag: Int, payload: ByteArray, type: Int = TYPE_COMMAND): List<ByteArray> {
        require(seq in 0..0xFF) { "seq out of range: $seq" }
        val crc = Crc16.compute(payload)
        val total = maxOf(1, (payload.size + CHUNK - 1) / CHUNK)
        require(total <= 0xFF) { "payload ${payload.size} B needs $total fragments (>255)" }
        val out = ArrayList<ByteArray>(total)
        var off = 0
        var serial = 1
        do {
            val end = minOf(off + CHUNK, payload.size)
            val chunk = payload.copyOfRange(off, end)
            val isFinal = serial >= total
            val crcSize = if (isFinal) CRC else 0
            val len = chunk.size + crcSize
            require(len <= 0xFF) { "chunk Len $len exceeds the 1-byte field" }
            val pkt = ByteArray(HEADER + chunk.size + crcSize)
            pkt[0] = MAGIC.toByte()
            pkt[1] = type.toByte()
            pkt[2] = (seq and 0xFF).toByte()
            pkt[3] = len.toByte()
            pkt[4] = total.toByte()
            pkt[5] = serial.toByte()
            pkt[6] = (sid and 0xFF).toByte()
            pkt[7] = (flag and 0xFF).toByte()
            chunk.copyInto(pkt, HEADER)
            if (isFinal) {
                pkt[pkt.size - 2] = (crc and 0xFF).toByte()
                pkt[pkt.size - 1] = ((crc ushr 8) and 0xFF).toByte()
            }
            out += pkt
            off = end
            serial++
        } while (off < payload.size)
        return out
    }

    data class Frame(val type: Int, val seq: Int, val sid: Int, val flag: Int, val payload: ByteArray)

    /**
     * Reassembles fragments into logical frames. One instance per link; offer()
     * from a single thread. There is ONE reassembly buffer keyed by transport
     * seq — interleaving multi-fragment messages corrupts it (CLAUDE.md
     * forbidden pattern), which the send path must respect; the receive side
     * mirrors G2CC ble/FrameReassembler.kt.
     */
    class Reassembler(private val warn: (String) -> Unit) {
        private var seq = -1
        private var type = 0
        private var sid = 0
        private var flag = 0
        private var total = 0
        private var nextSerial = 1
        private val buf = ByteArrayOutputStream()

        private fun pending() = total > 0
        private fun reset() { seq = -1; total = 0; nextSerial = 1; buf.reset() }

        /** Feed one raw packet; returns a complete frame when one finishes. */
        fun offer(pkt: ByteArray): Frame? {
            if (pkt.size < HEADER || (pkt[0].toInt() and 0xFF) != MAGIC) {
                if (pending()) { warn("non-frame bytes mid-reassembly; dropped partial"); reset() }
                warn("non-AA bytes (${pkt.size} B) ignored")
                return null
            }
            val pTotal = pkt[4].toInt() and 0xFF
            val pSerial = pkt[5].toInt() and 0xFF
            val pSeq = pkt[2].toInt() and 0xFF

            if (pTotal <= 1) {
                if (pending()) { warn("single-packet frame interrupted reassembly; dropped partial"); reset() }
                return single(pkt)
            }
            if (pSerial == 1) {
                if (pending()) warn("new message started before previous completed; dropped partial")
                reset()
                seq = pSeq
                type = pkt[1].toInt() and 0xFF
                sid = pkt[6].toInt() and 0xFF
                flag = pkt[7].toInt() and 0xFF
                total = pTotal
                nextSerial = 1
            } else if (!pending() || pSeq != seq || pSerial != nextSerial || pTotal != total) {
                warn("out-of-order fragment (serial=$pSerial expected=$nextSerial seq=$pSeq/$seq); dropped")
                reset()
                return null
            }
            val chunkLen = pkt[3].toInt() and 0xFF
            val isFinal = pSerial == total
            val body = if (isFinal) chunkLen - CRC else chunkLen
            // body < 0 = a final fragment whose Len cannot even hold the CRC: a
            // malformed packet, not a crash — buf.write with a negative length
            // would throw on the notify thread (a Nordic callback on the phone)
            if (body < 0 || HEADER + body > pkt.size) { warn("fragment Len $chunkLen invalid; dropped"); reset(); return null }
            buf.write(pkt, HEADER, body)
            nextSerial = pSerial + 1
            if (!isFinal) return null

            val payload = buf.toByteArray()
            val got = ((pkt[pkt.size - 1].toInt() and 0xFF) shl 8) or (pkt[pkt.size - 2].toInt() and 0xFF)
            val f = Frame(type, seq, sid, flag, payload)
            reset()
            if (got != Crc16.compute(payload)) {
                warn("reassembled frame CRC mismatch (sid=0x${sid.toString(16)}); dropped")
                return null
            }
            return f
        }

        private fun single(pkt: ByteArray): Frame? {
            val len = pkt[3].toInt() and 0xFF
            if (len < CRC || HEADER + len > pkt.size) { warn("frame Len $len invalid; dropped"); return null }
            val payload = pkt.copyOfRange(HEADER, HEADER + len - CRC)
            val got = ((pkt[HEADER + len - 1].toInt() and 0xFF) shl 8) or (pkt[HEADER + len - 2].toInt() and 0xFF)
            if (got != Crc16.compute(payload)) { warn("frame CRC mismatch; dropped"); return null }
            return Frame(pkt[1].toInt() and 0xFF, pkt[2].toInt() and 0xFF,
                pkt[6].toInt() and 0xFF, pkt[7].toInt() and 0xFF, payload)
        }
    }
}
