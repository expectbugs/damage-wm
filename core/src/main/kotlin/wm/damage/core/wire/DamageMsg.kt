package wm.damage.core.wire

/**
 * The Damage extension of the sid-0x09 settings channel — `FIRMWARE.md` §0, §3 and §4
 * (Phase 1 and 2). Written from the contract text only (clean room); the firmware
 * side is the fork's own implementation of the same text.
 *
 *  - field 110 `DamageCaps` on every settings READ response: `{1: "DMG", 2: contract,
 *    3: features}`; absent on an upstream g2flash build. Contract 1 is the Phase 1 build
 *    (installed 2026-09-15), contract 2 the Phase 2 build (a FLAGS_SET with no lease is
 *    refused; features bit 5 = the v2 drawing ops, bit 6 = the link edits)
 *  - field 112 `DamageControl` in a request: `['D','M',1,op,argLo,argHi]`; ops
 *    1 TELEMETRY (arg = request id), 2 FLAGS_SET (arg = the complete set), 3 FLAGS_CLEAR,
 *    4 CACHE_INFO (arg = request id; the reply adds the cache's CRC), 5 CACHE_SIZE (arg =
 *    KiB, the session's texture-cache size before its first write; contract 2)
 *  - field 111 `DamageTelemetry` in the reply to every op: uint32 fields 1 request id ·
 *    2 uptime ms · 3 flags · 4 the status register (what the last recording op left — FLAGS_SET,
 *    FLAGS_CLEAR, a malformed or unknown request; TELEMETRY and CACHE_INFO record nothing) ·
 *    5 worker µs · 6 copy µs · 7/8/9 free KiB in arenas 13/20/27 · 10 panel record · 11 sticky
 *    diagnostics · 12 lease ms left · 13 boot count (not sent by the Phase 1 build) · 14 lens ·
 *    15 the last Damage frame's panel-transfer µs · 16 direct presents · 17 cache generation ·
 *    18 cache size (when allocated) · 19 cache CRC-32 (CACHE_INFO, when allocated) · 20 self-test
 *    steps · 21/22 the last step's refusal and scratch CRC-32 (after a step) · 23/24/25 the last
 *    image-lane refusal's mode byte, reason and copy sequence (contract 2, once one was recorded) ·
 *    26 the last Damage transfer's path (0 the full refresh, 1 the partial rows of mode 24); a
 *    field is absent when the firmware does not know it
 *  - field 113 `DamagePresented`, a device-sent notify after each panel transfer of a Damage
 *    frame while flag bit 0 is armed: `{1 sequence, 2 worker µs, 3 copy µs, 4 transfer µs, 5 lens,
 *    6 path}`
 *
 * Requests are fire-and-forget like the lease (MagicRandom 0) and go to both arms. **Only the
 * RIGHT lens answers**: the stock senders refuse on the left lens (`CLAIMS.md`, 2026-09-14), so a
 * left lens's flags, cache and self-test state follow the same writes but cannot be read back.
 */
object DamageMsg {
    const val CAPS_FIELD = 110
    const val TELEMETRY_FIELD = 111
    const val CONTROL_FIELD = 112
    const val PRESENTED_FIELD = 113

    /** The Phase 1 build's contract (installed 2026-09-15). */
    const val CONTRACT_V1 = 1
    /** The Phase 2 build's contract (`FIRMWARE.md` §4): FLAGS_SET needs the lease. */
    const val CONTRACT = 2
    const val FEATURE_TELEMETRY = 1 shl 0
    const val FEATURE_FLAGS = 1 shl 1
    const val FEATURE_PRESENTED = 1 shl 2
    const val FEATURE_CACHE_KEEP = 1 shl 3
    const val FEATURE_SELF_TEST = 1 shl 4
    /** Bit 5: modes 17–24, op 5, telemetry fields 23–26 (`FIRMWARE.md` §4). */
    const val FEATURE_DRAW2 = 1 shl 5
    /** Bit 6: the link edits are in the image — LE 2M offered, 7.5 ms fast profile, no slow set. */
    const val FEATURE_LINK = 1 shl 6
    const val PHASE1_FEATURES = FEATURE_TELEMETRY or FEATURE_FLAGS or FEATURE_PRESENTED or FEATURE_CACHE_KEEP or FEATURE_SELF_TEST
    const val PHASE2_FEATURES = PHASE1_FEATURES or FEATURE_DRAW2 or FEATURE_LINK

    const val OP_TELEMETRY = 1
    const val OP_FLAGS_SET = 2
    const val OP_FLAGS_CLEAR = 3
    const val OP_CACHE_INFO = 4
    /** Contract 2: the session's texture-cache size in KiB, before its first write. */
    const val OP_CACHE_SIZE = 5
    /** The largest CACHE_SIZE a Phase 2 build takes (budget A, `FIRMWARE.md` §4). */
    const val CACHE_BUDGET_KIB = 160

    /** Bit 0: a field-113 notify after each panel transfer of a Damage frame (F1.3). */
    const val FLAG_PRESENTED = 1 shl 0
    /** Bit 1: the texture cache survives the next lease lapse and the fresh acquire after it (F1.5). */
    const val FLAG_CACHE_KEEP = 1 shl 1
    /** Bit 2: the v2 drawing ops answer (contract 2; `FIRMWARE.md` §4). */
    const val FLAG_DRAW2 = 1 shl 2
    /** Bit 15: no behaviour — it proves arming, the echo and the clear-on-lapse. */
    const val FLAG_PROBE = 1 shl 15
    /** What the Phase 1 build implements; any other bit is refused with status 2. */
    const val FLAGS_IMPLEMENTED_V1 = FLAG_PRESENTED or FLAG_CACHE_KEEP or FLAG_PROBE
    /** What the Phase 2 build implements. */
    const val FLAGS_IMPLEMENTED = FLAGS_IMPLEMENTED_V1 or FLAG_DRAW2

    const val STATUS_OK = 0
    const val STATUS_MALFORMED = 1
    const val STATUS_UNSUPPORTED = 2
    /** Contract 2: a FLAGS_SET or CACHE_SIZE with no lease held. */
    const val STATUS_NO_LEASE = 3
    /** CACHE_SIZE while the cache is allocated: the size stands. */
    const val STATUS_ALLOCATED = 4
    /** CACHE_SIZE of zero or over [CACHE_BUDGET_KIB]. */
    const val STATUS_BUDGET = 5

    /** The reasons of an image-lane refusal (field 24), by number. */
    val REASONS = mapOf(1 to "length", 2 to "bounds", 3 to "no-lease", 4 to "no-shadow", 5 to "record", 6 to "code",
        7 to "scratch", 8 to "mode", 9 to "draw2-unarmed", 10 to "no-batch", 11 to "no-memory", 12 to "value", 13 to "stream")

    fun control(op: Int, arg: Int): ByteArray = Pb.cat(
        Pb.v(1, 1),                 // commandId: a write
        Pb.v(2, 0),                 // MagicRandom 0 — fire-and-forget, as the lease
        Pb.l(CONTROL_FIELD, byteArrayOf('D'.code.toByte(), 'M'.code.toByte(), 1,
            op.toByte(), (arg and 0xFF).toByte(), ((arg shr 8) and 0xFF).toByte())),
    )

    data class Caps(val contract: Int, val features: Int) {
        fun has(feature: Int) = features and feature != 0
    }

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
        val transferUs get() = fields[15]
        val presents get() = fields[16]
        val cacheGen get() = fields[17]
        val cacheSize get() = fields[18]
        val cacheCrc get() = fields[19]
        val selfTestSteps get() = fields[20]
        val selfTestRefused get() = fields[21]
        val selfTestCrc get() = fields[22]
        /** Contract 2: the last image-lane refusal, or null when none has been recorded. */
        val refusal: Refusal? get() = fields[23]?.let { m -> Refusal(m.toInt(), (fields[24] ?: 0L).toInt(), fields[25] ?: 0L) }
        val lastPath get() = fields[26]

        /** The journal's one-line form: named, in field order. */
        fun describe(): String = fields.toSortedMap().entries.joinToString(" ") { (k, v) ->
            val name = NAMES[k] ?: "f$k"
            when (k) {
                3, 11 -> "$name=0x${v.toString(16)}"
                10 -> "$name=0x${v.toString(16)}${PANELS[v]?.let { "($it)" } ?: ""}"
                19, 22 -> "$name=%08x".format(v)
                else -> "$name=$v"
            }
        }
    }

    private val NAMES = mapOf(1 to "id", 2 to "uptimeMs", 3 to "flags", 4 to "lastStatus", 5 to "workerUs",
        6 to "copyUs", 7 to "free13KiB", 8 to "free20KiB", 9 to "free27KiB", 10 to "panel", 11 to "diag",
        12 to "leaseMs", 13 to "boots", 14 to "lens", 15 to "transferUs", 16 to "presents", 17 to "cacheGen",
        18 to "cacheSize", 19 to "cacheCrc", 20 to "stSteps", 21 to "stRefused", 22 to "stCrc",
        23 to "refMode", 24 to "refReason", 25 to "refSeq", 26 to "path")

    /** One image-lane refusal as telemetry fields 23–25 report it. */
    data class Refusal(val mode: Int, val reason: Int, val seq: Long) {
        fun describe(): String = "mode ${mode and 0x7F}${if (mode and 0x80 != 0) "|80" else ""} ${REASONS[reason] ?: "reason $reason"} at present $seq"
    }

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

    /** One presented notify (F1.3): the panel transfer that followed a Damage frame's copy.
     *  [workerUs] may lag a frame (the display task can run before the worker stores its time). */
    data class Presented(val seq: Long, val workerUs: Long, val copyUs: Long, val transferUs: Long, val lens: Long,
        /** Contract 2, field 6: 0 the full refresh, 1 the partial rows of a mode-24 hint. */
        val path: Long = 0)

    /** Field 113 of a device-sent settings message; null when absent. */
    fun parsePresented(payload: ByteArray): Presented? = try {
        Pb.bytesField(payload, PRESENTED_FIELD)?.let { body ->
            val f = Pb.fields(body).filter { it.wireType == 0 && it.varint != null }.associate { it.field to it.varint!! }
            Presented(f[1] ?: 0L, f[2] ?: 0L, f[3] ?: 0L, f[4] ?: 0L, f[5] ?: 0L, f[6] ?: 0L)
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}
