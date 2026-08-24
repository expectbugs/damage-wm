package wm.damage.core.wire

/**
 * sid 0x09 (SETTING) messages: the CFW's private control channel and the stock
 * settings we use.
 *
 * Lineage:
 *  - The lease/control channel is protobuf field 101 on G2SettingPackage, bytes
 *    ['F','C', version=1, op, nonceLo, nonceHi] — g2flash/patches/settings_ext.c
 *    (overview.md §4.1). Ops: 1 ACQUIRE/RENEW (wake), 2 RELEASE, 3 WAKE_CLAIM,
 *    4 WAKE_READY, 5 FB_ACQUIRE, 6 FB_RELEASE, 7 WEAR_QUERY. Damage needs 5/6.
 *    MagicRandom stays 0: fire-and-forget, consumed before the stock nanopb
 *    decoder discards the unknown field.
 *  - The FB lease lasts 90 s, is renewed every 45 s, must be sent to BOTH arms,
 *    and FAILS OPEN — stop renewing and stock LVGL silently repaints (§4.1).
 *  - Capability detection: field 100 of the settings READ response is a string
 *    starting "EVENCFW/" (settings_ext.c). Tag 100 sits above the stock fields,
 *    so stock decoders skip it — detection needs no timeout by construction.
 */
object SettingsMsg {
    const val SID = 0x09
    const val FLAG_REQUEST = 0x20
    const val FLAG_RESPONSE = 0x00

    const val CONTROL_FIELD = 101
    const val EVENT_FIELD = 102
    const val CAPABILITY_FIELD = 100

    const val OP_WAKE_ACQUIRE = 1
    const val OP_WAKE_RELEASE = 2
    const val OP_FB_ACQUIRE = 5
    const val OP_FB_RELEASE = 6
    const val OP_WEAR_QUERY = 7

    /** Lease timing constants (settings_ext.c / FACECLAW_WAKE_LEASE_RENEW_MS). */
    const val LEASE_EXPIRY_MS = 90_000L
    const val LEASE_RENEW_MS = 45_000L

    /** The capability tokens Damage requires before painting anything
     *  (DESIGN.md §9.2b startup capability gate). */
    val REQUIRED_CAPS = listOf("img640", "directfb", "fbguard", "imgz", "rle")

    fun control(op: Int, nonce: Int): ByteArray = Pb.cat(
        Pb.v(1, 1),                    // commandId
        Pb.v(2, 0),                    // MagicRandom 0 — fire-and-forget
        Pb.l(
            CONTROL_FIELD,
            byteArrayOf('F'.code.toByte(), 'C'.code.toByte(), 1,
                op.toByte(), (nonce and 0xFF).toByte(), ((nonce shr 8) and 0xFF).toByte()),
        ),
    )

    fun fbAcquire(nonce: Int): ByteArray = control(OP_FB_ACQUIRE, nonce)
    fun fbRelease(nonce: Int): ByteArray = control(OP_FB_RELEASE, nonce)

    /** Settings READ (commandId 2 + request wrapper) — its response carries the
     *  EVENCFW capability string in field 100 on a CFW, nothing extra on stock. */
    fun settingsQuery(msgId: Int): ByteArray = Pb.cat(
        Pb.v(1, 2),
        Pb.v(2, msgId),
        Pb.l(4, Pb.v(1, 1)),
    )

    /** Extract the capability string from a settings response payload, or null. */
    fun parseCapability(payload: ByteArray): String? = try {
        Pb.bytesField(payload, CAPABILITY_FIELD)?.toString(Charsets.UTF_8)
            ?.takeIf { it.startsWith("EVENCFW/") }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Which required capability tokens are missing from an EVENCFW string. */
    fun missingCaps(capability: String): List<String> {
        val tokens = capability.substringAfter(' ', "").split(' ').filter { it.isNotEmpty() }.toSet()
        return REQUIRED_CAPS.filter { it !in tokens }
    }
}
