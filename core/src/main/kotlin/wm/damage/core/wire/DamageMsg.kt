package wm.damage.core.wire

/**
 * The Damage extension of the sid-0x09 settings channel — `FIRMWARE.md` §0 and §3
 * (draft, Phase 1). Written from the contract text only (clean room); the firmware
 * side is the fork's own implementation of the same text.
 *
 *  - field 110 `DamageCaps` on every settings READ response: `{1: "DMG", 2: contract,
 *    3: features}`; absent on an upstream g2flash build (the installed one)
 *  - field 112 `DamageControl` in a request: `['D','M',1,op,argLo,argHi]`; ops
 *    1 TELEMETRY (arg = request id), 2 FLAGS_SET (arg = the complete set), 3 FLAGS_CLEAR
 *  - field 111 `DamageTelemetry` in the reply to every op: uint32 fields 1 request id ·
 *    2 uptime ms · 3 flags · 4 the status register (what the last recording op left — FLAGS_SET,
 *    FLAGS_CLEAR, a malformed or unknown request; TELEMETRY records nothing) · 5 worker µs ·
 *    6 copy µs · 7/8/9 free KiB in arenas 13/20/27 · 10 panel record · 11 sticky diagnostics ·
 *    12 lease ms left · 13 boot count (not sent by the Phase 1 build) · 14 lens; a field is
 *    absent when the firmware does not know it
 *
 * Requests are fire-and-forget like the lease (MagicRandom 0) and go to both arms.
 */
object DamageMsg {
    const val CAPS_FIELD = 110
    const val TELEMETRY_FIELD = 111
    const val CONTROL_FIELD = 112

    const val CONTRACT = 1
    const val FEATURE_TELEMETRY = 1 shl 0
    const val FEATURE_FLAGS = 1 shl 1

    const val OP_TELEMETRY = 1
    const val OP_FLAGS_SET = 2
    const val OP_FLAGS_CLEAR = 3

    /** Bit 15: no behaviour — it proves arming, the echo and the clear-on-lapse. */
    const val FLAG_PROBE = 1 shl 15

    const val STATUS_OK = 0
    const val STATUS_MALFORMED = 1
    const val STATUS_UNSUPPORTED = 2

    fun control(op: Int, arg: Int): ByteArray = Pb.cat(
        Pb.v(1, 1),                 // commandId: a write
        Pb.v(2, 0),                 // MagicRandom 0 — fire-and-forget, as the lease
        Pb.l(CONTROL_FIELD, byteArrayOf('D'.code.toByte(), 'M'.code.toByte(), 1,
            op.toByte(), (arg and 0xFF).toByte(), ((arg shr 8) and 0xFF).toByte())),
    )

    data class Caps(val contract: Int, val features: Int)

    /** Field 110 of a settings READ response; null when absent or not a DamageCaps. */
    fun parseCaps(payload: ByteArray): Caps? = try {
        Pb.bytesField(payload, CAPS_FIELD)?.let { body ->
            if (Pb.bytesField(body, 1)?.toString(Charsets.US_ASCII) != "DMG") null
            else Caps((Pb.varintField(body, 2) ?: 0L).toInt(), (Pb.varintField(body, 3) ?: 0L).toInt())
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** One telemetry record: field number → value, absent fields left out. */
    data class Telemetry(val fields: Map<Int, Long>) {
        val requestId get() = fields[1]
        val uptimeMs get() = fields[2]
        val flags get() = fields[3]
        val lastStatus get() = fields[4]
        val leaseMsLeft get() = fields[12]
        val lens get() = fields[14]

        /** The journal's one-line form: named, in field order. */
        fun describe(): String = fields.toSortedMap().entries.joinToString(" ") { (k, v) ->
            val name = NAMES[k] ?: "f$k"
            when (k) {
                3, 11 -> "$name=0x${v.toString(16)}"
                10 -> "$name=0x${v.toString(16)}${PANELS[v]?.let { "($it)" } ?: ""}"
                else -> "$name=$v"
            }
        }
    }

    private val NAMES = mapOf(1 to "id", 2 to "uptimeMs", 3 to "flags", 4 to "lastStatus", 5 to "workerUs",
        6 to "copyUs", 7 to "free13KiB", 8 to "free20KiB", 9 to "free27KiB", 10 to "panel", 11 to "diag",
        12 to "leaseMs", 13 to "boots", 14 to "lens")

    /** The two panel operations records of stock 2.2.6.10 (`CLAIMS.md`, 2026-09-13). */
    private val PANELS = mapOf(0x0070AFE4L to "A6N-G", 0x0070B024L to "JBD4010")

    /** Field 111 of a device-sent settings message; null when absent. */
    fun parseTelemetry(payload: ByteArray): Telemetry? = try {
        Pb.bytesField(payload, TELEMETRY_FIELD)?.let { body ->
            Telemetry(Pb.fields(body).filter { it.wireType == 0 && it.varint != null }.associate { it.field to it.varint!! })
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}
